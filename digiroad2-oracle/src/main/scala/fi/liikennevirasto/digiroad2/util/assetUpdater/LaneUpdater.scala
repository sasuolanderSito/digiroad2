package fi.liikennevirasto.digiroad2.util.assetUpdater

import fi.liikennevirasto.digiroad2.GeometryUtils.Projection
import fi.liikennevirasto.digiroad2.asset.SideCode.switch
import fi.liikennevirasto.digiroad2.asset.{AutoGeneratedUsername, Lanes, SideCode, TrafficDirection}
import fi.liikennevirasto.digiroad2.client._
import fi.liikennevirasto.digiroad2.client.viite.SearchViiteClient
import fi.liikennevirasto.digiroad2.dao.Queries
import fi.liikennevirasto.digiroad2.dao.RoadLinkOverrideDAO.TrafficDirectionDao
import fi.liikennevirasto.digiroad2.dao.lane.LaneWorkListItem
import fi.liikennevirasto.digiroad2.lane.LaneFiller._
import fi.liikennevirasto.digiroad2.lane.LaneNumber.isMainLane
import fi.liikennevirasto.digiroad2.lane._
import fi.liikennevirasto.digiroad2.postgis.PostGISDatabase
import fi.liikennevirasto.digiroad2.service.lane.{LaneService, LaneWorkListService}
import fi.liikennevirasto.digiroad2.service.{RoadAddressService, RoadLinkService}
import fi.liikennevirasto.digiroad2.util.assetUpdater.ChangeTypeReport.{Creation, Deletion, Divided, Replaced}
import fi.liikennevirasto.digiroad2.util.{Digiroad2Properties, LaneUtils, MainLanePopulationProcess}
import fi.liikennevirasto.digiroad2.{AssetLinearReference, DummyEventBus, DummySerializer, GeometryUtils, MValueCalculator}
import org.apache.http.impl.client.HttpClientBuilder
import org.joda.time.DateTime
import org.json4s.JsonAST.JObject
import org.json4s.jackson.compactJson
import org.slf4j.LoggerFactory

import java.text.SimpleDateFormat

object LaneUpdater {
  lazy val roadLinkChangeClient: RoadLinkChangeClient = new RoadLinkChangeClient
  lazy val roadLinkClient: RoadLinkClient = new RoadLinkClient(Digiroad2Properties.vvhRestApiEndPoint)
  lazy val roadLinkService: RoadLinkService = new RoadLinkService(roadLinkClient, new DummyEventBus, new DummySerializer)
  lazy val viiteClient: SearchViiteClient = new SearchViiteClient(Digiroad2Properties.viiteRestApiEndPoint, HttpClientBuilder.create().build())
  lazy val roadAddressService: RoadAddressService = new RoadAddressService(viiteClient)
  lazy val laneService: LaneService = new LaneService(roadLinkService, new DummyEventBus, roadAddressService)
  lazy val laneWorkListService: LaneWorkListService = new LaneWorkListService()
  private val logger = LoggerFactory.getLogger(getClass)
  lazy val laneFiller: LaneFiller = new LaneFiller
  def withDynTransaction[T](f: => T): T = PostGISDatabase.withDynTransaction(f)
  case class RoadLinkChangeWithResults(roadLinkChange: RoadLinkChange, changeSet: ChangeSet, lanesOnAdjustedLink: Seq[PersistedLane])

  def fuseReplacementLanes(replacementResults: Seq[RoadLinkChangeWithResults]): (Seq[PersistedLane], ChangeSet) = {
    val newLinkIds = replacementResults.flatMap(_.roadLinkChange.newLinks.map(_.linkId))
    val changeSets = replacementResults.map(_.changeSet)
    val lanesOnNewLinks = replacementResults.flatMap(_.lanesOnAdjustedLink)
    val lanesGroupedByNewLinkId = lanesOnNewLinks.groupBy(_.linkId)
    val initialChangeSet = changeSets.foldLeft(ChangeSet())(LaneFiller.combineChangeSets)

    val (lanesAfterFuse, changeSet) = newLinkIds.foldLeft(Seq.empty[PersistedLane], initialChangeSet) { case (accumulatedAdjustments, linkId) =>
      val (existingAssets, changedSet) = accumulatedAdjustments
      val assetsOnRoadLink = lanesGroupedByNewLinkId.getOrElse(linkId, Nil)
      val (adjustedAssets, assetAdjustments) = fuseLanesOnMergedRoadLink(assetsOnRoadLink, changedSet)

      (existingAssets ++ adjustedAssets, assetAdjustments)
    }

    (lanesAfterFuse, changeSet)

  }

  def fuseLanesOnMergedRoadLink(lanes: Seq[PersistedLane], changeSet: ChangeSet): (Seq[PersistedLane], ChangeSet) = {

    def equalAttributes(lane: PersistedLane, targetLane: PersistedLane): Boolean = {
      if(LaneNumber.isMainLane(lane.laneCode)) {
        LaneNumber.isMainLane(targetLane.laneCode)
      } else lane.attributes.equals(targetLane.attributes)
    }

    val sortedList = lanes.sortBy(_.startMeasure)

    if (lanes.nonEmpty) {
      val origin = sortedList.head
      val target = sortedList.tail.find(sl => Math.abs(sl.startMeasure - origin.endMeasure) < 0.1
        && equalAttributes(origin, sl)
        && sl.sideCode == origin.sideCode)

      if (target.nonEmpty) {
        // pick id if it already has one regardless of which one is newer
        val toBeFused = Seq(origin, target.get).sortWith(laneFiller.modifiedSort)
        val propertiesToUse = if(LaneNumber.isMainLane(origin.laneCode)) {
          getLatestStartDatePropertiesForFusedLanes(toBeFused)
        } else origin.attributes
        val newId = toBeFused.find(_.id > 0).map(_.id).getOrElse(0L)

        val modified = toBeFused.head.copy(id = newId, startMeasure = origin.startMeasure, endMeasure = target.get.endMeasure, attributes = propertiesToUse)
        val expiredId = Set(origin.id, target.get.id) -- Set(modified.id, 0L) // never attempt to expire id zero

        val positionAdjustment = Seq(changeSet.positionAdjustments.find(a => a.laneId == modified.id) match {
          case Some(adjustment) => adjustment.copy(startMeasure = modified.startMeasure, endMeasure = modified.endMeasure,
            sideCode = SideCode.apply(modified.sideCode), attributesToUpdate = Some(propertiesToUse))
          case _ => LanePositionAdjustment(modified.id, modified.linkId, modified.startMeasure, modified.endMeasure,
            SideCode.apply(modified.sideCode), attributesToUpdate = Some(propertiesToUse))
        })

        // Replace origin and target with this new item in the list and recursively call itself again
        fuseLanesOnMergedRoadLink(Seq(modified) ++ sortedList.tail.filterNot(sl => Set(origin, target.get).contains(sl)),
          changeSet.copy(expiredLaneIds = changeSet.expiredLaneIds ++ expiredId,
            positionAdjustments = changeSet.positionAdjustments.filter(a => a.laneId > 0 && a.laneId != modified.id &&
              !changeSet.expiredLaneIds.contains(a.laneId) && !expiredId.contains(a.laneId)) ++ positionAdjustment))

      } else {
        val fused = fuseLanesOnMergedRoadLink(sortedList.tail, changeSet)
        (Seq(origin) ++ fused._1, fused._2)
      }
    } else {
      (lanes, changeSet)
    }
  }

  def updateSamuutusChangeSet(changeSet: ChangeSet, roadLinkChanges: Seq[RoadLinkChange] = Seq(), generateReports: Boolean = false): Seq[ChangedAsset] = {

    def expireLanes(laneIdsToExpire: Set[Long]): Seq[ChangedAsset] = {
      laneIdsToExpire.flatMap(laneId => {
        val laneToExpire = laneService.getPersistedLanesByIds(Set(laneId), newTransaction = false).headOption
        val changedLane = if (generateReports) {
          Some(reportLaneChanges(laneToExpire, Seq(), Deletion, roadLinkChanges))
        } else None
        laneService.moveToHistory(laneId, None, expireHistoryLane = true, deleteFromLanes = true, AutoGeneratedUsername.generatedInUpdate)
        changedLane
      }).toSeq
    }

    def saveLanePositionAdjustments(positionAdjustments: Seq[LanePositionAdjustment]): Seq[ChangedAsset] = {
      val toAdjustLanes = laneService.getPersistedLanesByIds(positionAdjustments.map(_.laneId).toSet, newTransaction = false)

      positionAdjustments.flatMap { adjustment =>
        val oldLane = toAdjustLanes.find(_.id == adjustment.laneId)
        // If adjustment has new attributes to update, use them
        // Used for updating fused main lane start dates
        val attributesToUse = adjustment.attributesToUpdate match {
          case Some(attributes) => attributes
          case None => oldLane.get.attributes
        }
        val laneToCreate = oldLane.get.copy(id = 0, linkId = adjustment.linkId, startMeasure = adjustment.startMeasure,
          endMeasure = adjustment.endMeasure, sideCode = adjustment.sideCode.value, attributes = attributesToUse)
        val newLaneId = laneService.createWithoutTransaction(laneToCreate, AutoGeneratedUsername.generatedInUpdate)
        laneService.moveToHistory(oldLane.get.id, Some(newLaneId), expireHistoryLane = true, deleteFromLanes = true, AutoGeneratedUsername.generatedInUpdate)
        if (generateReports) {
          Some(reportLaneChanges(oldLane, Seq(laneToCreate.copy(id = newLaneId)), Replaced, roadLinkChanges))
        } else None
      }
    }

    def saveDividedLanes(laneSplits: Seq[LaneSplit]): Seq[ChangedAsset] = {
      val lanesToCreate = laneSplits.flatMap(_.lanesToCreate).toSet
      val lanesToExpire = laneSplits.map(_.originalLane).toSet

      logger.info("Creating " + lanesToCreate.size + " new lanes due to split on link ids: " + lanesToCreate.map(_.linkId).mkString(", "))
      val createdLanesWithOldLanes = laneSplits.map(split => {
        val createdLanes = split.lanesToCreate.map(laneToCreate => {
          val newId = laneService.createWithoutTransaction(laneToCreate, AutoGeneratedUsername.generatedInUpdate)
          laneToCreate.copy(id = newId)
        })
        (createdLanes, split.originalLane)
      })

      // Each old lane must have history row referencing to each new lane created from it
      logger.info("Expiring " + lanesToExpire.size + " old lanes due to split on link ids: " + lanesToExpire.map(_.linkId).mkString(", "))
      val changedLanes = createdLanesWithOldLanes.flatMap(createdLanesAndOldLane => {
        val createdLanes = createdLanesAndOldLane._1
        val originalLane = createdLanesAndOldLane._2
        createdLanes.foreach(createdLane => {
          laneService.moveToHistory(originalLane.id, Some(createdLane.id), expireHistoryLane = true, deleteFromLanes = false, AutoGeneratedUsername.generatedInUpdate)
        })
        if (generateReports) {
          Some(reportLaneChanges(Some(originalLane), createdLanes, Divided, roadLinkChanges))
        } else None
      })

      // After history is created, delete lanes
      logger.info("Deleting " + lanesToExpire.size + " old lanes from lane tables due to split on linkIds: " + lanesToExpire.map(_.linkId).mkString(", "))
      lanesToExpire.foreach(laneToExpire => {
        laneService.dao.deleteEntryLane(laneToExpire.id)
      })

      changedLanes
    }


    def saveGeneratedLanes(lanesToGenerate: Seq[PersistedLane]): Seq[ChangedAsset] = {
      val createdLanes = laneService.createMultipleLanes(lanesToGenerate, AutoGeneratedUsername.generatedInUpdate)
      createdLanes.flatMap(createdLane => {
        if (generateReports) {
          Some(reportLaneChanges(None, Seq(createdLane), Creation, roadLinkChanges))
        } else None
      })
    }

    // Expire lanes which have been marked to be expired
    val expiredChangedLanes = if(changeSet.expiredLaneIds.nonEmpty) {
      logger.info("Expiring ids: " + changeSet.expiredLaneIds.mkString(", "))
      expireLanes(changeSet.expiredLaneIds)
    } else Seq()

    // Save generated lanes
    val generatedChangedLanes = if(changeSet.generatedPersistedLanes.nonEmpty) {
      logger.info(s"${changeSet.generatedPersistedLanes.size} lanes to be created for ${changeSet.generatedPersistedLanes.map(_.linkId).toSet.size} links")
      saveGeneratedLanes(changeSet.generatedPersistedLanes)
    } else Seq()

    // Save samuutus sideCode and M-value adjustments
    val adjustedPositionChangedLanes = if (changeSet.positionAdjustments.nonEmpty) {
      logger.info("Saving SideCode/M-Value adjustments for lane Ids: " + changeSet.positionAdjustments.map(a => "" + a.laneId).mkString(", "))
      saveLanePositionAdjustments(changeSet.positionAdjustments)
    } else Seq()

    // Create new divide lanes, and expire old lanes due to road link splits
    val splitChangedLanes = if (changeSet.splitLanes.nonEmpty) {
      saveDividedLanes(changeSet.splitLanes)
    } else Seq()

    expiredChangedLanes ++ generatedChangedLanes ++ adjustedPositionChangedLanes ++ splitChangedLanes
  }



  def updateLanes(): Unit = {
    withDynTransaction {
      val lastSuccessfulSamuutus = Queries.getLatestSuccessfulSamuutus(Lanes.typeId)
      val allRoadLinkChanges = roadLinkChangeClient.getRoadLinkChanges(lastSuccessfulSamuutus)
      updateTrafficDirectionChangesLaneWorkList(allRoadLinkChanges)
      val (workListChanges, roadLinkChanges) = allRoadLinkChanges.partition(change => isOldLinkOnLaneWorkList(change))
      val changeSet = handleChanges(roadLinkChanges, workListChanges)
      val changedLanes = updateSamuutusChangeSet(changeSet, allRoadLinkChanges, generateReports = true)
      val changeReport = ChangeReport(Lanes.typeId, changedLanes)
      generateAndSaveReport(changeReport)
      Queries.updateLatestSuccessfulSamuutus(Lanes.typeId)
    }
  }

  def generateAndSaveReport(changeReport: ChangeReport): Unit = {
    val (reportBody, contentRowCount) = ChangeReporter.generateCSV(changeReport)
    ChangeReporter.saveReportToS3(Lanes.label, reportBody, contentRowCount)
    val (reportBodyWithGeom, _) = ChangeReporter.generateCSV(changeReport, withGeometry = true)
    ChangeReporter.saveReportToS3(Lanes.label, reportBodyWithGeom, contentRowCount, hasGeometry = true)
  }

  def updateTrafficDirectionChangesLaneWorkList(roadLinkChanges: Seq[RoadLinkChange]): Unit = {
    val oldLinkIds = roadLinkChanges.flatMap(_.oldLink).map(_.linkId)
    val lanesOnOldLinks = laneService.fetchAllLanesByLinkIds(oldLinkIds, newTransaction = false)
    val changesFiltered = roadLinkChanges.filterNot(change => change.changeType == RoadLinkChangeType.Add ||
      change.changeType == RoadLinkChangeType.Remove)
    changesFiltered.foreach(change => {
      val oldLink = change.oldLink.get
      val trafficDirectionChanged = isRealTrafficDirectionChange(change)
      val oldLinkHasAdditionalLanes = lanesOnOldLinks.exists(lane => lane.linkId == oldLink.linkId && !LaneNumber.isMainLane(lane.laneCode))
      // If traffic direction has changed and old link has additional lanes, raise road link to lane work list
      if (trafficDirectionChanged && oldLinkHasAdditionalLanes) {
        change.newLinks.foreach(newLink => {
          val oldTD = oldLink.trafficDirection
          val newTD = newLink.trafficDirection
          val workListItem = LaneWorkListItem(0, oldLink.linkId, "traffic_direction", oldTD.value, newTD.value,
            DateTime.now(), AutoGeneratedUsername.generatedInUpdate)
          laneWorkListService.workListDao.insertItem(workListItem)
        })
      }
    })
  }

  // If lanes from old roadLink are currently on lane work list
  // then only process the main lanes
  def isOldLinkOnLaneWorkList(change: RoadLinkChange): Boolean = {
      val linkIdsOnWorkList = laneWorkListService.getLaneWorkList.map(_.linkId)
      change.oldLink match {
        case Some(oldLink) =>
          linkIdsOnWorkList.contains(oldLink.linkId)
        case None => false
      }
  }

  def isRealTrafficDirectionChange(change: RoadLinkChange): Boolean = {
    change.newLinks.exists(newLink => {
      val oldOriginalTrafficDirection = change.oldLink.get.trafficDirection
      val newOriginalTrafficDirection = newLink.trafficDirection
      val replaceInfo = change.replaceInfo.find(_.newLinkId == newLink.linkId).get
      val isDigitizationChange = replaceInfo.digitizationChange
      val overWrittenTdValueOnNewLink = TrafficDirectionDao.getExistingValue(newLink.linkId)

      if (overWrittenTdValueOnNewLink.nonEmpty) false
      else {
        if (isDigitizationChange) oldOriginalTrafficDirection != TrafficDirection.switch(newOriginalTrafficDirection)
        else oldOriginalTrafficDirection != newOriginalTrafficDirection
      }
    })
  }

  def handleTrafficDirectionChange(workListChanges: Seq[RoadLinkChange], workListMainLanes: Seq[PersistedLane]): (ChangeSet, Seq[PersistedLane]) = {
    val laneIdsToExpire = workListMainLanes.map(_.id).toSet

    val createdMainLanes = workListChanges.flatMap(change => {
      val newLinkIds = change.newLinks.map(_.linkId)
      // Need to fetch RoadLinks because Link Type is needed for main lane creation
      val addedRoadLinks = roadLinkService.getRoadLinksByLinkIds(newLinkIds.toSet)
      val createdMainLanes = MainLanePopulationProcess.createMainLanesForRoadLinks(addedRoadLinks, saveResult = false)
      createdMainLanes
    })

    (ChangeSet(expiredLaneIds = laneIdsToExpire, generatedPersistedLanes = createdMainLanes), createdMainLanes)
  }

  def reportLaneChanges(oldLane: Option[PersistedLane], newLanes: Seq[PersistedLane], changeType: ChangeType, roadLinkChanges: Seq[RoadLinkChange]): ChangedAsset = {
    val linkId = if (oldLane.nonEmpty) oldLane.get.linkId else newLanes.head.linkId
    val assetId = if (oldLane.nonEmpty) oldLane.get.id else 0

    val relevantRoadLinkChange = roadLinkChanges.find(change => {
      val roadLinkChangeOldLinkId = change.oldLink match {
        case Some(oldLink) => Some(oldLink.linkId)
        case None => None
      }
      val laneOldLinkId = oldLane match {
        case Some(lane) => Some(lane.linkId)
        case None => None
      }
      val roadLinkChangeNewLinkIds = change.newLinks.map(_.linkId).sorted
      val lanesNewLinkIds = newLanes.map(_.linkId).sorted

      roadLinkChangeOldLinkId == laneOldLinkId && roadLinkChangeNewLinkIds == lanesNewLinkIds
    }).get

    val before = oldLane match {
      case Some(ol) =>
        val values = compactJson(JObject(ol.attributes.flatMap(_.toJson).toList))
        val linkGeometry = relevantRoadLinkChange.oldLink.get.geometry
        val laneGeometry = GeometryUtils.truncateGeometry3D(linkGeometry, ol.startMeasure, ol.endMeasure)
        val linearReference = LinearReference(ol.linkId, ol.startMeasure, Some(ol.endMeasure), Some(ol.sideCode), None, ol.endMeasure - ol.startMeasure)
        Some(Asset(ol.id, values, Some(ol.municipalityCode.toInt), Some(laneGeometry), Some(linearReference)))
      case None => None
    }

    val after = newLanes.map(nl => {
      val newLink = relevantRoadLinkChange.newLinks.find(_.linkId == nl.linkId).get
      val values = compactJson(JObject(nl.attributes.flatMap(_.toJson).toList))
      val linkGeometry = newLink.geometry
      val laneGeometry = GeometryUtils.truncateGeometry3D(linkGeometry, nl.startMeasure, nl.endMeasure)
      val linearReference = LinearReference(nl.linkId, nl.startMeasure, Some(nl.endMeasure), Some(nl.sideCode), None, nl.endMeasure - nl.startMeasure)
      Asset(nl.id, values, Some(nl.municipalityCode.toInt), Some(laneGeometry), Some(linearReference))
    })

    ChangedAsset(linkId, assetId, changeType, relevantRoadLinkChange.changeType, before, after)
  }

  def handleChanges(roadLinkChanges: Seq[RoadLinkChange], workListChanges: Seq[RoadLinkChange] = Seq()): ChangeSet = {
    val oldLinkIds = roadLinkChanges.flatMap(_.oldLink).map(_.linkId)
    val oldWorkListLinkIds = workListChanges.flatMap(_.oldLink).map(_.linkId)

    val newLinkIds = roadLinkChanges.flatMap(_.newLinks.map(_.linkId))
    val newRoadLinks = roadLinkService.getRoadLinksByLinkIds(newLinkIds.toSet)

    val lanesOnOldRoadLinks = laneService.fetchAllLanesByLinkIds(oldLinkIds, newTransaction = false)
    val lanesOnWorkListLinks = laneService.fetchAllLanesByLinkIds(oldWorkListLinkIds, newTransaction = false)

    // Additional lanes can't be processed if link is on the lane work list, only handle main lanes on those links
    val workListMainLanes = lanesOnWorkListLinks.filter(lane => LaneNumber.isMainLane(lane.laneCode))
    val (trafficDirectionChangeSet, trafficDirectionCreatedMainLanes) = handleTrafficDirectionChange(workListChanges, workListMainLanes)

    val changeSetsAndAdjustedLanes = roadLinkChanges.map(change => {
      change.changeType match {
        case RoadLinkChangeType.Add =>
          val newLinkIds = change.newLinks.map(_.linkId)
          val addedRoadLinks = newRoadLinks.filter(roadLink => newLinkIds.contains(roadLink.linkId))
          val createdMainLanes = MainLanePopulationProcess.createMainLanesForRoadLinks(addedRoadLinks, saveResult = false)
          RoadLinkChangeWithResults(change, ChangeSet(generatedPersistedLanes = createdMainLanes), createdMainLanes)
        case RoadLinkChangeType.Remove =>
          val removedLinkId = change.oldLink.get.linkId
          val lanesToExpireOnRemovedLink = lanesOnOldRoadLinks.filter(_.linkId == removedLinkId).map(_.id).toSet
          RoadLinkChangeWithResults(change, ChangeSet(expiredLaneIds = lanesToExpireOnRemovedLink), Seq())
        case RoadLinkChangeType.Replace =>
          val lanesOnReplacedLink = lanesOnOldRoadLinks.filter(lane => change.oldLink.get.linkId == lane.linkId)
          val adjustmentsAndAdjustedLanes = fillReplacementLinksWithExistingLanes(lanesOnReplacedLink, change)
          val adjustments = adjustmentsAndAdjustedLanes.map(_._1)
          val adjustedLanes = adjustmentsAndAdjustedLanes.map(_._2)
          RoadLinkChangeWithResults(change, ChangeSet(positionAdjustments = adjustments), adjustedLanes)
        case RoadLinkChangeType.Split =>
          val oldRoadLink = change.oldLink.get
          val lanesOnSplitLink = lanesOnOldRoadLinks.filter(_.linkId == oldRoadLink.linkId)
          val adjustmentsAndAdjustedLanes = fillSplitLinksWithExistingLanes(lanesOnSplitLink, change)
          val adjustments = adjustmentsAndAdjustedLanes._1
          val adjustedLanes = adjustmentsAndAdjustedLanes._2
          RoadLinkChangeWithResults(change, ChangeSet(splitLanes = adjustments), adjustedLanes)
      }
    })

    val (replacementsResults, otherResults) = changeSetsAndAdjustedLanes.partition(_.roadLinkChange.changeType == RoadLinkChangeType.Replace)
    val (lanesAfterFuse, replacementChangeSet) = fuseReplacementLanes(replacementsResults)

    val otherChangeSets = otherResults.map(_.changeSet)
    val finalChangeSet = (otherChangeSets :+ trafficDirectionChangeSet :+ replacementChangeSet).foldLeft(ChangeSet())(LaneFiller.combineChangeSets)

    finalChangeSet
  }
  // In case main lane's parent lanes have different start dates, we want to inherit the latest date
  def getLatestStartDatePropertiesForFusedLanes(lanesToMerge: Seq[PersistedLane]): Seq[LaneProperty] = {
    val startDateStrings = lanesToMerge.flatMap(parentLane => laneService.getPropertyValue(parentLane, "start_date")).map(_.value).asInstanceOf[Seq[String]]
    val dateFormat = new SimpleDateFormat("d.M.yyyy")
    val startDates = startDateStrings.map(dateString => dateFormat.parse(dateString))
    val latestDate = startDates.max
    val latestDateString = dateFormat.format(latestDate)

    val lanePropertiesToUse = Seq(
      LaneProperty("lane_code", Seq(LanePropertyValue(LaneNumber.MainLane.oneDigitLaneCode))),
      LaneProperty("lane_type", Seq(LanePropertyValue(LaneType.Main.value))),
      LaneProperty("start_date", Seq(LanePropertyValue(latestDateString)))
    )

    lanePropertiesToUse
  }

  def calculateAdditionalLanePositionsOnSplitLinks(oldAdditionalLanes: Seq[PersistedLane], change: RoadLinkChange): Seq[LaneSplit] = {
    oldAdditionalLanes.map(originalAdditionalLane => {
      val replaceInfosAffectingLane = change.replaceInfo.filter(replaceInfo => {
        GeometryUtils.liesInBetween(replaceInfo.oldFromMValue, (originalAdditionalLane.startMeasure, originalAdditionalLane.endMeasure)) ||
          GeometryUtils.liesInBetween(replaceInfo.oldToMValue, (originalAdditionalLane.startMeasure, originalAdditionalLane.endMeasure))
      })
      val lanesSplitFromOriginal = replaceInfosAffectingLane.map(replaceInfo => {
        val newRoadLinkLength = change.newLinks.find(_.linkId == replaceInfo.newLinkId).get.linkLength
        val laneLinearReference = AssetLinearReference(originalAdditionalLane.id, originalAdditionalLane.startMeasure,
          originalAdditionalLane.endMeasure, originalAdditionalLane.sideCode)
        val projection = Projection(replaceInfo.oldFromMValue, replaceInfo.oldToMValue, replaceInfo.newFromMValue, replaceInfo.newToMValue)
        val (newStartM, newEndM, newSideCode) = MValueCalculator.calculateNewMValues(laneLinearReference, projection, newRoadLinkLength, replaceInfo.digitizationChange)
        originalAdditionalLane.copy(id = 0 ,startMeasure = newStartM, endMeasure = newEndM, linkId = replaceInfo.newLinkId, sideCode = newSideCode)
      })
      LaneSplit(lanesSplitFromOriginal, originalAdditionalLane)
    })
  }

  def createSplitMainLanes(oldMainLanes: Seq[PersistedLane], splitChange: RoadLinkChange): Seq[LaneSplit] = {
    oldMainLanes.map(originalMainLane => {
      val replaceInfos = splitChange.replaceInfo
      val splitMainLanesToCreate = replaceInfos.map(replaceInfo => {
        val newSideCode = if(replaceInfo.digitizationChange) switch(SideCode.apply(originalMainLane.sideCode)).value
        else originalMainLane.sideCode
        val splitLaneStartMeasure = 0.0
        val splitLaneEndMeasure = LaneUtils.roundMeasure(replaceInfo.newToMValue)
        originalMainLane.copy(id = 0, linkId = replaceInfo.newLinkId, sideCode = newSideCode, startMeasure = splitLaneStartMeasure, endMeasure = splitLaneEndMeasure)
      })
      LaneSplit(splitMainLanesToCreate, originalMainLane)
    })
  }

  def fillSplitLinksWithExistingLanes(lanesToUpdate: Seq[PersistedLane], change: RoadLinkChange): (Seq[LaneSplit], Seq[PersistedLane]) = {
    val (mainLanesOnOldLink, additionalLanesOnOldLink) = lanesToUpdate.partition(lane => isMainLane(lane.laneCode))
    val mainLaneSplits = createSplitMainLanes(mainLanesOnOldLink, change)
    val additionalLaneSplits = calculateAdditionalLanePositionsOnSplitLinks(additionalLanesOnOldLink, change)
    val adjustments = mainLaneSplits ++ additionalLaneSplits
    val splitLanes = adjustments.flatMap(_.lanesToCreate)
    (adjustments, splitLanes)
  }

  def fillReplacementLinksWithExistingLanes(lanesToUpdate: Seq[PersistedLane], change: RoadLinkChange): Seq[(LanePositionAdjustment, PersistedLane)] = {
    val newRoadLinks = change.newLinks
    newRoadLinks.flatMap(newRoadlink => {
      val replaceInfo = change.replaceInfo.find(_.newLinkId == newRoadlink.linkId).get
      val laneAdjustmentsOnLink = lanesToUpdate.map(lane => {
        val laneLinearReference = AssetLinearReference(lane.id, lane.startMeasure, lane.endMeasure, lane.sideCode)
        val projection = Projection(replaceInfo.oldFromMValue, replaceInfo.oldToMValue, replaceInfo.newFromMValue, replaceInfo.newToMValue)
        val (newStartM, newEndM, newSideCode) = MValueCalculator.calculateNewMValues(laneLinearReference, projection, newRoadlink.linkLength, replaceInfo.digitizationChange)
        val adjustment = LanePositionAdjustment(lane.id, newRoadlink.linkId, newStartM, newEndM, SideCode.apply(newSideCode))
        val adjustedLane = lane.copy(linkId = newRoadlink.linkId, startMeasure = newStartM, endMeasure = newEndM, sideCode = newSideCode)
        (adjustment, adjustedLane)
      })
      laneAdjustmentsOnLink
    })
  }
}
