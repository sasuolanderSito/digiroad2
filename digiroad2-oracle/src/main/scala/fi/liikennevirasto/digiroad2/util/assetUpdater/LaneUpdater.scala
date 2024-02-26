package fi.liikennevirasto.digiroad2.util.assetUpdater

import fi.liikennevirasto.digiroad2.GeometryUtils.Projection
import fi.liikennevirasto.digiroad2.asset.SideCode.switch
import fi.liikennevirasto.digiroad2.asset.{AutoGeneratedUsername, Lanes, SideCode, TractorRoad, TrafficDirection}
import fi.liikennevirasto.digiroad2.client.{RoadLinkChangeType, _}
import fi.liikennevirasto.digiroad2.client.viite.SearchViiteClient
import fi.liikennevirasto.digiroad2.dao.Queries
import fi.liikennevirasto.digiroad2.dao.RoadLinkOverrideDAO.TrafficDirectionDao
import fi.liikennevirasto.digiroad2.dao.lane.{AutoProcessedLanesWorkListItem, LaneWorkListItem}
import fi.liikennevirasto.digiroad2.lane.LaneFiller._
import fi.liikennevirasto.digiroad2.lane.LaneNumber.isMainLane
import fi.liikennevirasto.digiroad2.lane._
import fi.liikennevirasto.digiroad2.postgis.PostGISDatabase
import fi.liikennevirasto.digiroad2.service.lane.{AutoProcessedLanesWorkListService, LaneService, LaneWorkListService}
import fi.liikennevirasto.digiroad2.service.{RoadAddressService, RoadLinkService}
import fi.liikennevirasto.digiroad2.util.assetUpdater.ChangeTypeReport.{Creation, Deletion, Divided, Replaced}
import fi.liikennevirasto.digiroad2.util.{Digiroad2Properties, KgvUtil, LaneUtils, LogUtils, MainLanePopulationProcess}
import fi.liikennevirasto.digiroad2._
import fi.liikennevirasto.digiroad2.Point
import fi.liikennevirasto.digiroad2.client.RoadLinkChangeType.Add
import fi.liikennevirasto.digiroad2.linearasset.RoadLink
import org.apache.http.impl.client.HttpClientBuilder
import org.joda.time.DateTime
import org.json4s.JsonAST.JObject
import org.json4s.jackson.compactJson
import org.slf4j.LoggerFactory

import java.text.SimpleDateFormat
import scala.util.Random

object LaneUpdater {
  lazy val roadLinkChangeClient: RoadLinkChangeClient = new RoadLinkChangeClient
  lazy val roadLinkClient: RoadLinkClient = new RoadLinkClient(Digiroad2Properties.vvhRestApiEndPoint)
  lazy val roadLinkService: RoadLinkService = new RoadLinkService(roadLinkClient, new DummyEventBus, new DummySerializer)
  lazy val viiteClient: SearchViiteClient = new SearchViiteClient(Digiroad2Properties.viiteRestApiEndPoint, HttpClientBuilder.create().build())
  lazy val roadAddressService: RoadAddressService = new RoadAddressService(viiteClient)
  lazy val laneService: LaneService = new LaneService(roadLinkService, new DummyEventBus, roadAddressService)
  lazy val laneWorkListService: LaneWorkListService = new LaneWorkListService()
  lazy val autoProcessedLanesWorkListService: AutoProcessedLanesWorkListService = new AutoProcessedLanesWorkListService
  private val logger = LoggerFactory.getLogger(getClass)
  lazy val laneFiller: LaneFiller = new LaneFiller
  private var changes: Seq[ReportedChange] = Seq()
  
  def withDynTransaction[T](f: => T): T = PostGISDatabase.withDynTransaction(f)
  case class RoadLinkChangeWithResults(roadLinkChange: RoadLinkChange, changeSet: ChangeSet, lanesOnAdjustedLink: Seq[PersistedLane])

  //Returns random negative Long value for lanes to use as id before creation
  //Used for split lanes, so that final result can be adjusted in method fuseLanesOnMergedRoadLink
  def getPseudoId: Long = {
    val randomGen = new Random()
    -Math.abs(randomGen.nextLong)
  }

  def logChangeSetSizes(changeSet: ChangeSet): Unit = {
    logger.info(s"adjustedMValues size: ${changeSet.adjustedMValues.size}")
    logger.info(s"adjustedSideCodes size: ${changeSet.adjustedSideCodes.size}")
    logger.info(s"positionAdjustments size: ${changeSet.positionAdjustments.size}")
    logger.info(s"expiredLaneIds size: ${changeSet.expiredLaneIds.size}")
    logger.info(s"generatedPersistedLanes size: ${changeSet.generatedPersistedLanes.size}")
    logger.info(s"splitLanes size: ${changeSet.splitLanes.size}")
  }

  def fuseLaneSections(replacementResults: Seq[RoadLinkChangeWithResults]): (Seq[PersistedLane], ChangeSet) = {
    val newLinkIds = replacementResults.flatMap(_.roadLinkChange.newLinks.map(_.linkId)).distinct
    val changeSets = replacementResults.map(_.changeSet)
    val lanesOnNewLinks = replacementResults.flatMap(_.lanesOnAdjustedLink)
    val lanesGroupedByNewLinkId = lanesOnNewLinks.groupBy(_.linkId)
    val initialChangeSet = changeSets.foldLeft(ChangeSet())(LaneFiller.combineChangeSets)
    var percentageProcessed = 0

    val (lanesAfterFuse, changeSet) = newLinkIds.zipWithIndex.foldLeft(Seq.empty[PersistedLane], initialChangeSet) { case (accumulatedAdjustments, (linkId, index)) =>
      percentageProcessed = LogUtils.logArrayProgress(logger, "Fusing lane sections", newLinkIds.size, index, percentageProcessed)
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
    def partsAreContinues(origin: PersistedLane, sl: PersistedLane) = Math.abs(sl.startMeasure - origin.endMeasure) < 0.1
    
    val sortedList = lanes.sortBy(_.startMeasure)

    if (lanes.nonEmpty) {
      val origin = sortedList.head
      val target = sortedList.tail.find(sl =>
        (partsAreContinues(origin, sl) || (LaneNumber.isMainLane(origin.laneCode) && LaneNumber.isMainLane(sl.laneCode)))
        && equalAttributes(origin, sl) && sl.sideCode == origin.sideCode)
      if (target.nonEmpty) {
        // pick id if it already has one regardless of which one is newer
        val toBeFused = Seq(origin, target.get).sortWith(laneFiller.modifiedSort)
        val propertiesToUse = if(LaneNumber.isMainLane(origin.laneCode)) {
          getLatestStartDatePropertiesForFusedLanes(toBeFused)
        } else origin.attributes
        val newId = toBeFused.find(_.id != 0).map(_.id).getOrElse(0L)

        val modified = toBeFused.head.copy(id = newId, startMeasure = origin.startMeasure, endMeasure = target.get.endMeasure, attributes = propertiesToUse)
        val expiredId = Set(origin.id, target.get.id) -- Set(newId, 0L) // never attempt to expire id zero


        val positionAdjustment = changeSet.positionAdjustments.find(a => a.laneId == modified.id) match {
          case Some(adjustment) => Seq(adjustment.copy(startMeasure = modified.startMeasure, endMeasure = modified.endMeasure,
            sideCode = SideCode.apply(modified.sideCode), attributesToUpdate = Some(propertiesToUse)))
          case None if modified.id < 0 => Seq()
          case _ => Seq(LanePositionAdjustment(modified.id, modified.linkId, modified.startMeasure, modified.endMeasure,
            SideCode.apply(modified.sideCode), attributesToUpdate = Some(propertiesToUse)))
        }

        val splitLanesWithAdjusted = changeSet.splitLanes.map(laneSplit => {
          val lanesToCreateWithAdjusted = laneSplit.lanesToCreate.flatMap(laneToCreate => {
            laneToCreate.id match {
              case id if id == newId => Some(modified)
              case id if id == expiredId.headOption.getOrElse(0) => None
              case _ => Some(laneToCreate)
            }
          })
          laneSplit.copy(lanesToCreate = lanesToCreateWithAdjusted)
        })

        // Replace origin and target with this new item in the list and recursively call itself again
        val filteredList = Seq(modified) ++ sortedList.tail.filterNot(sl => Set(origin, target.get).contains(sl))
        // Filter out pseudo IDs
        val realIdsToExpire = expiredId.filter(id => id > 0)
        val expiredIdsAfterFuse = changeSet.expiredLaneIds ++ realIdsToExpire
        val positionAdjustmentsAfterFuse = changeSet.positionAdjustments.filter(a => a.laneId > 0 && a.laneId != modified.id &&
          !changeSet.expiredLaneIds.contains(a.laneId) && !realIdsToExpire.contains(a.laneId)) ++ positionAdjustment

        val changeSetWithFused = changeSet.copy(expiredLaneIds = expiredIdsAfterFuse,
          positionAdjustments = positionAdjustmentsAfterFuse, splitLanes = splitLanesWithAdjusted)
        fuseLanesOnMergedRoadLink(filteredList,changeSetWithFused)
      } else {
        val fused = fuseLanesOnMergedRoadLink(sortedList.tail, changeSet)
        (Seq(origin) ++ fused._1, fused._2)
      }
    } else {
      (lanes, changeSet)
    }
  }

  def updateSamuutusChangeSet(changeSet: ChangeSet, roadLinkChanges: Seq[RoadLinkChange]): Seq[ChangedAsset] = {

    def expireLanes(laneIdsToExpire: Set[Long]): Seq[ChangedAsset] = {
      val persistedLanesToExpire = LogUtils.time(logger, s"Fetch ${laneIdsToExpire.size} lanes for expiring and report") {
        laneService.getPersistedLanesByIds(laneIdsToExpire, newTransaction = false)
      }
      val lanesToExpireWithNewIds = persistedLanesToExpire.map(lane => OldLaneWithNewId(lane, None))
      LogUtils.time(logger, s"Move ${laneIdsToExpire.size} expired lanes to history") {
        laneService.moveToHistoryBatch(lanesToExpireWithNewIds, AutoGeneratedUsername.generatedInUpdate)
      }
      persistedLanesToExpire.map(laneToExpire => {
        reportLaneChanges(Some(laneToExpire), Seq(), Deletion, roadLinkChanges)
      })
    }

    def saveLanePositionAdjustments(positionAdjustments: Seq[LanePositionAdjustment]): Seq[ChangedAsset] = {
      val toAdjustLanes = LogUtils.time(logger, s"Fetch ${positionAdjustments.size} lanes for adjusting and report") {
        laneService.getPersistedLanesByIds(positionAdjustments.map(_.laneId).toSet, newTransaction = false)
      }

      val oldLanesAndLanesToCreate = positionAdjustments.map { adjustment =>
        val oldLane = toAdjustLanes.find(_.id == adjustment.laneId)
        // If adjustment has new attributes to update, use them
        // Used for updating fused main lane start dates
        val attributesToUse = adjustment.attributesToUpdate match {
          case Some(attributes) => attributes
          case None => oldLane.get.attributes
        }
        val laneToCreate = oldLane.get.copy(id = 0, linkId = adjustment.linkId, startMeasure = adjustment.startMeasure,
          endMeasure = adjustment.endMeasure, sideCode = adjustment.sideCode.value, attributes = attributesToUse)

        (oldLane.get, laneToCreate)
      }

      val newIds = LogUtils.time(logger, "Create adjusted lanes"){
        laneService.createMultipleLanes(oldLanesAndLanesToCreate.map(_._2), AutoGeneratedUsername.generatedInUpdate).map(_.id)
      }
      val oldLanesWithNewIdAndLaneToCreate = oldLanesAndLanesToCreate.zip(newIds).map {
        case ((oldLane, laneToCreate), newId) =>
          (OldLaneWithNewId(oldLane, Some(newId)), laneToCreate.copy(id = newId))
      }

      val oldLanesToHistory = oldLanesWithNewIdAndLaneToCreate.map(_._1)
      LogUtils.time(logger, s"Move ${oldLanesToHistory.size} adjusted lanes to history") {
        laneService.moveToHistoryBatch(oldLanesToHistory, AutoGeneratedUsername.generatedInUpdate)
      }

      oldLanesWithNewIdAndLaneToCreate.map(oldAndNewLane => {
        val oldLane = oldAndNewLane._1.lane
        val newLane = oldAndNewLane._2
        reportLaneChanges(Some(oldLane), Seq(newLane), Replaced, roadLinkChanges)
      })
    }

    def saveDividedLanes(laneSplits: Seq[LaneSplit]): Seq[ChangedAsset] = {
      val lanesToCreate = laneSplits.flatMap(_.lanesToCreate).toSet

      logger.info("Creating " + lanesToCreate.size + " new lanes due to split on link ids: " + lanesToCreate.map(_.linkId).mkString(", "))
      //TODO If too slow, refactor code to create all split lanes in a single query
      val laneSplitsWithCreatedIds = LogUtils.time(logger, s"Create ${laneSplits.flatMap(_.lanesToCreate).size} lanes due to split") {
        laneSplits.map(split => {
          val createdLanes = laneService.createMultipleLanes(split.lanesToCreate, split.originalLane.createdBy.get)
          LaneSplit(createdLanes, split.originalLane)
        })
      }

      val (splitsWithNewLanes, splitsWithNoNewLanes) = laneSplitsWithCreatedIds.partition(_.lanesToCreate.nonEmpty)
      // Each old lane must have history row referencing to each new lane created from it
      val oldLanesWithNewIds = splitsWithNewLanes.flatMap(split => {
        split.lanesToCreate.map(newLane => {
          OldLaneWithNewId(split.originalLane, Some(newLane.id))
        })
      })
      val oldLanesWithNoNewIds = splitsWithNoNewLanes.map(split => {
        OldLaneWithNewId(split.originalLane, None)
      })

      val oldLanesToHistory = oldLanesWithNewIds ++ oldLanesWithNoNewIds
      LogUtils.time(logger, s"Move ${oldLanesToHistory.size} split lanes to history") {
        laneService.moveToHistoryBatch(oldLanesToHistory,  AutoGeneratedUsername.generatedInUpdate)
      }

      laneSplitsWithCreatedIds.map(split => {
        reportLaneChanges(Some(split.originalLane), split.lanesToCreate, Divided, roadLinkChanges)
      })
    }


    def saveGeneratedLanes(lanesToGenerate: Seq[PersistedLane]): Seq[ChangedAsset] = {
      val createdLanes = LogUtils.time(logger, s"Create ${lanesToGenerate.size} generated lanes") {
        laneService.createMultipleLanes(lanesToGenerate, AutoGeneratedUsername.generatedInUpdate)
      }
      createdLanes.map(createdLane => {
        reportLaneChanges(None, Seq(createdLane), Creation, roadLinkChanges)
      })
    }

    logChangeSetSizes(changeSet)

    // Expire lanes which have been marked to be expired
    val expiredChangedLanes = if(changeSet.expiredLaneIds.nonEmpty) {
      logger.info("Expiring " + changeSet.expiredLaneIds.size + "ids: " + changeSet.expiredLaneIds.mkString(", "))
      expireLanes(changeSet.expiredLaneIds)
    } else Seq()

    // Save generated lanes
    val generatedChangedLanes = if(changeSet.generatedPersistedLanes.nonEmpty) {
      logger.info(s"${changeSet.generatedPersistedLanes.size} lanes to be created for ${changeSet.generatedPersistedLanes.map(_.linkId).toSet.size} links")
      saveGeneratedLanes(changeSet.generatedPersistedLanes)
    } else Seq()

    // Save samuutus sideCode and M-value adjustments
    val adjustedPositionChangedLanes = if (changeSet.positionAdjustments.nonEmpty) {
      logger.info(s"Saving SideCode/M-Value adjustments for ${changeSet.positionAdjustments.size} lanes")
      saveLanePositionAdjustments(changeSet.positionAdjustments)
    } else Seq()

    // Create new divide lanes, and expire old lanes due to road link splits
    val splitChangedLanes = if (changeSet.splitLanes.nonEmpty) {
      logger.info(s"Saving divides for ${changeSet.splitLanes.size} lanes")
      saveDividedLanes(changeSet.splitLanes)
    } else Seq()

    expiredChangedLanes ++ generatedChangedLanes ++ adjustedPositionChangedLanes ++ splitChangedLanes
  }



  def updateLanes(): Unit = {
    val lastSuccess = PostGISDatabase.withDynSession( Queries.getLatestSuccessfulSamuutus(Lanes.typeId) )
    val changeSets = roadLinkChangeClient.getRoadLinkChanges(lastSuccess)

    changeSets.foreach( roadLinkChangeSet => {
      try {
        PostGISDatabase.withDynTransaction {
          changes = updateByRoadLinks(roadLinkChangeSet)
          ValidateSamuutus.validate(Lanes.typeId, roadLinkChangeSet)
          generateAndSaveReport(roadLinkChangeSet.targetDate)
        }
      } catch {
        case e: SamuutusFailed =>
          generateAndSaveReport(roadLinkChangeSet.targetDate)
          throw e
      }
    })
  }

  /**
   * Filters processed RoadLinkChanges with the following principles:
   *
   * Remove new links that already have lanes, so that duplicate lanes will not be created.
   *
   * @param changes
   * @return filtered changes
   */
  def filterChanges(changes: Seq[RoadLinkChange]): Seq[RoadLinkChange] = {
    val newLinkIds = changes.flatMap(_.newLinks.map(_.linkId))
    val linkIdsWithExistingLanes = laneService.fetchAllLanesByLinkIds(newLinkIds, newTransaction = false).map(_.linkId)
    if (linkIdsWithExistingLanes.nonEmpty) logger.info(s"found already created lanes on new links ${linkIdsWithExistingLanes}")
    changes.filterNot(c => c.changeType == Add && linkIdsWithExistingLanes.contains(c.newLinks.head.linkId))
  }

  private def updateByRoadLinks(roadLinkChangeSet: RoadLinkChangeSet) = {
    logger.info(s"Started processing change set ${roadLinkChangeSet.key}")
    val allRoadLinkChanges = roadLinkChangeSet.changes
    val filteredRoadLinkChanges = filterChanges(allRoadLinkChanges)

    logger.info("Starting to process traffic direction changes")
    LogUtils.time(logger, "Update Lane Work List with possible traffic direction changes") {
      updateTrafficDirectionChangesLaneWorkList(filteredRoadLinkChanges)
    }
    val (workListChanges, roadLinkChanges) = filteredRoadLinkChanges.partition(change => isOldLinkOnLaneWorkLists(change))
    logger.info("Starting to process changes")
    val changeSet = LogUtils.time(logger, s"Process ${workListChanges.size} workListChanges and ${roadLinkChanges.size} roadLinkChanges") {
      handleChanges(roadLinkChanges, workListChanges)
    }
    logger.info("Starting to save lane samuutus results")
    val changedLanes = LogUtils.time(logger, "Saving Lane samuutus results") {
      updateSamuutusChangeSet(changeSet, filteredRoadLinkChanges)
    }
    changedLanes
  }
  /**
    * Each report saving array [[LaneUpdater.changes]] is erased.
    */
  def generateAndSaveReport(processedTo: DateTime): Unit = {
    val (reportBody, contentRowCount) = ChangeReporter.generateCSV(ChangeReport(Lanes.typeId, changes))
    ChangeReporter.saveReportToS3(Lanes.label, processedTo, reportBody, contentRowCount)
    val (reportBodyWithGeom, _) = ChangeReporter.generateCSV(ChangeReport(Lanes.typeId, changes), withGeometry = true)
    ChangeReporter.saveReportToS3(Lanes.label, processedTo, reportBodyWithGeom, contentRowCount, hasGeometry = true)
    changes = Seq()
  }

  def updateTrafficDirectionChangesLaneWorkList(roadLinkChanges: Seq[RoadLinkChange]): Unit = {
    val oldLinkIds = roadLinkChanges.flatMap(_.oldLink).map(_.linkId)
    val (mainLanesOnOldLinks, additionalLanesOnOldLinks) = laneService.fetchAllLanesByLinkIds(oldLinkIds, newTransaction = false).partition(lane => LaneNumber.isMainLane(lane.laneCode))
    val changesFiltered = roadLinkChanges.filterNot(change => change.changeType == RoadLinkChangeType.Add ||
      change.changeType == RoadLinkChangeType.Remove)
    changesFiltered.foreach(change => {
      if (isRealTrafficDirectionChange(change)) {
        change.newLinks.foreach(newLink => {
          val oldLink = change.oldLink.get
          val mainLanesOnLink = mainLanesOnOldLinks.filter(_.linkId == oldLink.linkId)
          val additionalLanesOnLink = additionalLanesOnOldLinks.filter(_.linkId == oldLink.linkId)
          val oldTD = oldLink.trafficDirection
          val newTD = newLink.trafficDirection
          val mainLanesStartDates = mainLanesOnLink.flatMap(lane => laneService.getPropertyValue(lane, "start_date")).map(_.value).asInstanceOf[Seq[String]]
          // If Traffic direction has changed on replacing link, link needs to be inserted to Automatically processed lanes work list
          // because main lanes on old link will be expired and new ones with no connecting history will be generated for replacing link in samuutus
          val autoProcessedLanesWorkListItem = AutoProcessedLanesWorkListItem(0, newLink.linkId, "traffic_direction", oldTD.value, newTD.value, mainLanesStartDates,
            DateTime.now(), AutoGeneratedUsername.generatedInUpdate)
          autoProcessedLanesWorkListService.insertToAutoProcessedLanesWorkList(autoProcessedLanesWorkListItem, newTransaction = false)
          // If old link has additional lanes, insert link also to Lane work list for later manual processing, additional
          // lanes on links with changed traffic direction are not processed during samuutus
          if(additionalLanesOnLink.nonEmpty) {
            val laneWorkListItem = LaneWorkListItem(0, oldLink.linkId, "traffic_direction", oldTD.value, newTD.value,
              DateTime.now(), AutoGeneratedUsername.generatedInUpdate)
            laneWorkListService.insertToLaneWorkList(laneWorkListItem, newTransaction = false)
          }
        })
      }
    })
  }

  // If lanes from old roadLink are currently on Lane work list or Automatically processed lanes work list
  // then only process the main lanes on changed link
  def isOldLinkOnLaneWorkLists(change: RoadLinkChange): Boolean = {
    val linkIdsOnLaneWorkList = laneWorkListService.getLaneWorkList(false).map(_.linkId)
    val linkIdsOnAutoProcessedLanesWorkList = autoProcessedLanesWorkListService.getAutoProcessedLanesWorkList(false).map(_.linkId)
    change.oldLink match {
      case Some(oldLink) =>
        linkIdsOnLaneWorkList.contains(oldLink.linkId) || linkIdsOnAutoProcessedLanesWorkList.contains(oldLink.linkId)
      case None => false
    }
  }

  def isRealTrafficDirectionChange(change: RoadLinkChange): Boolean = {
    change.newLinks.exists(newLink => {
      val oldOriginalTrafficDirection = change.oldLink.get.trafficDirection
      val newOriginalTrafficDirection = newLink.trafficDirection
      val replaceInfo = change.replaceInfo.find(_.newLinkId.getOrElse(None) == newLink.linkId).getOrElse(throw new NoSuchElementException(s"Replace info for link ${newLink.linkId} not found from change ${change}"))
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
      val addedRoadLinks = roadLinkService.getExistingAndExpiredRoadLinksByLinkIds(newLinkIds.toSet, newTransaction = false)
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

      ((roadLinkChangeOldLinkId.nonEmpty && laneOldLinkId.nonEmpty) && roadLinkChangeOldLinkId == laneOldLinkId) || roadLinkChangeNewLinkIds == lanesNewLinkIds
    }).get

    val before = oldLane match {
      case Some(ol) =>
        val values = compactJson(JObject(ol.attributes.flatMap(_.toJson).toList))
        val linkGeometry = relevantRoadLinkChange.oldLink.get.geometry
        val linkInfo = Some(LinkInfo(relevantRoadLinkChange.oldLink.get.lifeCycleStatus))
        val laneGeometry = GeometryUtils.truncateGeometry3D(linkGeometry, ol.startMeasure, ol.endMeasure)
        val linearReference = LinearReferenceForReport(ol.linkId, ol.startMeasure, Some(ol.endMeasure), Some(ol.sideCode), None, None, ol.endMeasure - ol.startMeasure)
        Some(Asset(ol.id, values, Some(ol.municipalityCode.toInt), Some(laneGeometry), Some(linearReference),linkInfo))
      case None => None
    }

    val after = newLanes.map(nl => {
      val maybeLink = relevantRoadLinkChange.newLinks.find(_.linkId == nl.linkId)
      val linkInfo = if (maybeLink.nonEmpty) Some(LinkInfo(maybeLink.get.lifeCycleStatus)) else None
      val values = compactJson(JObject(nl.attributes.flatMap(_.toJson).toList))
      val linkGeometry = if (maybeLink.nonEmpty) maybeLink.get.geometry else Seq.empty[Point]
      val laneGeometry = GeometryUtils.truncateGeometry3D(linkGeometry, nl.startMeasure, nl.endMeasure)
      val linearReference = LinearReferenceForReport(nl.linkId, nl.startMeasure, Some(nl.endMeasure), Some(nl.sideCode), None, None, nl.endMeasure - nl.startMeasure)
      Asset(nl.id, values, Some(nl.municipalityCode.toInt), Some(laneGeometry), Some(linearReference),linkInfo)
    })

    ChangedAsset(linkId, assetId, changeType, relevantRoadLinkChange.changeType, before, after)
  }

  def handleChanges(roadLinkChanges: Seq[RoadLinkChange], workListChanges: Seq[RoadLinkChange] = Seq()): ChangeSet = {
    val oldLinkIds = roadLinkChanges.flatMap(_.oldLink).map(_.linkId)
    val oldWorkListLinkIds = workListChanges.flatMap(_.oldLink).map(_.linkId)

    val newLinkIds = roadLinkChanges.flatMap(_.newLinks.map(_.linkId))
    val newRoadLinks = roadLinkService.getExistingAndExpiredRoadLinksByLinkIds(newLinkIds.toSet, newTransaction = false)

    val linkIdsWithExistingLane = laneService.fetchAllLanesByLinkIds(newLinkIds, newTransaction = false).map(_.linkId)
    if (linkIdsWithExistingLane.nonEmpty) logger.info(s"found already created lanes on new links ${linkIdsWithExistingLane.mkString(", ")}")
    val filteredChanges = roadLinkChanges.filterNot(c => c.changeType == Add && linkIdsWithExistingLane.contains(c.newLinks.head.linkId))

    val lanesOnOldRoadLinks = laneService.fetchAllLanesByLinkIds(oldLinkIds, newTransaction = false)
    val lanesOnWorkListLinks = laneService.fetchAllLanesByLinkIds(oldWorkListLinkIds, newTransaction = false)

    // Additional lanes can't be processed if link is on the lane work list, only handle main lanes on those links
    val workListMainLanes = lanesOnWorkListLinks.filter(lane => LaneNumber.isMainLane(lane.laneCode))
    val (trafficDirectionChangeSet, trafficDirectionCreatedMainLanes) = handleTrafficDirectionChange(workListChanges, workListMainLanes)

    var percentageProcessed = 0
    val changeSetsAndAdjustedLanes = LogUtils.time(logger, s"Core samuutus handling for ${filteredChanges.size} changes") {
      filteredChanges.zipWithIndex.map(changeWithIndex => {
        val (change, index) = changeWithIndex
        percentageProcessed = LogUtils.logArrayProgress(logger, "Core samuutus handling", filteredChanges.size, index, percentageProcessed)
        change.changeType match {
          case RoadLinkChangeType.Add =>
            handleAddChange(change, newRoadLinks)
          case RoadLinkChangeType.Remove =>
            handleRemoveChange(change, lanesOnOldRoadLinks)
          case RoadLinkChangeType.Replace =>
            handleReplaceChange(change, newRoadLinks, lanesOnOldRoadLinks)
          case RoadLinkChangeType.Split =>
            handleSplitChange(change, newRoadLinks, lanesOnOldRoadLinks)
        }
      })
    }

    val linksPartOfReplacement = changeSetsAndAdjustedLanes.filter(_.roadLinkChange.changeType == RoadLinkChangeType.Replace)
      .flatMap(_.roadLinkChange.newLinks.map(_.linkId))
    
    val (_, changeSetAfterFuse) = LogUtils.time(logger, s"Fusing lane sections"){
      fuseLaneSections(changeSetsAndAdjustedLanes)
    }
    val finalChangeSet = Seq(trafficDirectionChangeSet, changeSetAfterFuse).foldLeft(ChangeSet())(LaneFiller.combineChangeSets)
    val removedSplit = removeSplitWhichAreAlsoPartOfMerger(finalChangeSet,linksPartOfReplacement)
    finalChangeSet.copy(splitLanes = removedSplit)
  }

  private def handleAddChange(change: RoadLinkChange, newRoadLinks: Seq[RoadLink]): RoadLinkChangeWithResults = {
    val newRoadLinkInfo = change.newLinks.headOption
      .getOrElse(throw new NoSuchElementException(s"Replacement change is missing new link info, old linkID: ${change.oldLink.get.linkId}"))
    val addedRoadLinkOption = newRoadLinks.find(_.linkId == newRoadLinkInfo.linkId)
    addedRoadLinkOption match {
      case Some(roadLink) =>
        if (roadLink.linkType == TractorRoad) {
          RoadLinkChangeWithResults(change, ChangeSet(), Seq())
        } else {
          val createdMainLanes = MainLanePopulationProcess.createMainLanesForRoadLinks(Seq(roadLink), saveResult = false)
          RoadLinkChangeWithResults(change, ChangeSet(generatedPersistedLanes = createdMainLanes), createdMainLanes)
        }
      case None =>
        RoadLinkChangeWithResults(change, ChangeSet(), Seq())
    }
  }

  private def handleRemoveChange(change: RoadLinkChange, lanesOnOldRoadLinks: Seq[PersistedLane]): RoadLinkChangeWithResults = {
    val removedLinkId = change.oldLink.get.linkId
    val lanesToExpireOnRemovedLink = lanesOnOldRoadLinks.filter(_.linkId == removedLinkId).map(_.id).toSet
    RoadLinkChangeWithResults(change, ChangeSet(expiredLaneIds = lanesToExpireOnRemovedLink), Seq())
  }

  private def handleReplaceChange(change: RoadLinkChange, newRoadLinks: Seq[RoadLink], lanesOnOldRoadLinks: Seq[PersistedLane]): RoadLinkChangeWithResults = {
    val lanesOnReplacedLink = lanesOnOldRoadLinks.filter(lane => change.oldLink.get.linkId == lane.linkId)
    val newRoadLinkInfo = change.newLinks.headOption
      .getOrElse(throw new NoSuchElementException(s"Replacement change is missing new link info, old linkID: ${change.oldLink.get.linkId}"))
    val newLinkId = newRoadLinkInfo.linkId
    val replacementRoadLinkOption = newRoadLinks.find(_.linkId == newLinkId)
    replacementRoadLinkOption match {
      case Some(roadLink) =>
        if (roadLink.linkType == TractorRoad) {
          RoadLinkChangeWithResults(change, ChangeSet(expiredLaneIds = lanesOnReplacedLink.map(_.id).toSet), Seq())
        } else {
          val adjustmentsAndAdjustedLanes = fillReplacementLinksWithExistingLanes(lanesOnReplacedLink, change)
          val adjustments = adjustmentsAndAdjustedLanes.map(_._1)
          val adjustedLanes = adjustmentsAndAdjustedLanes.map(_._2)
          RoadLinkChangeWithResults(change, ChangeSet(positionAdjustments = adjustments), adjustedLanes)
        }
      case None =>
        RoadLinkChangeWithResults(change, ChangeSet(expiredLaneIds = lanesOnReplacedLink.map(_.id).toSet), Seq())
    }

  }

  private def handleSplitChange(change: RoadLinkChange, newRoadLinks: Seq[RoadLink], lanesOnOldRoadLinks: Seq[PersistedLane]): RoadLinkChangeWithResults= {
    val oldRoadLink = change.oldLink.get
    val newSplitRoadLinks = newRoadLinks.filter(link => change.newLinks.map(_.linkId).contains(link.linkId))
    val lanesOnSplitLink = lanesOnOldRoadLinks.filter(_.linkId == oldRoadLink.linkId)
    val adjustmentsAndAdjustedLanes = fillSplitLinksWithExistingLanes(lanesOnSplitLink, newSplitRoadLinks, change)
    val adjustments = adjustmentsAndAdjustedLanes._1
    val adjustedLanes = adjustmentsAndAdjustedLanes._2
    RoadLinkChangeWithResults(change, ChangeSet(splitLanes = adjustments), adjustedLanes)
  }

  private def removeSplitWhichAreAlsoPartOfMerger(finalChangeSet: ChangeSet,linksPartOfReplacement:Seq[String]) = {
    finalChangeSet.splitLanes.map(b => {
      b.copy(lanesToCreate = b.lanesToCreate.filterNot(c => linksPartOfReplacement.contains(c.linkId)))
    })
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
        GeometryUtils.liesInBetweenExclusiveEnd(originalAdditionalLane.startMeasure, (replaceInfo.oldFromMValue.getOrElse(0.0), replaceInfo.oldToMValue.getOrElse(0.0))) ||
          GeometryUtils.liesInBetweenExclusiveStart(originalAdditionalLane.endMeasure, (replaceInfo.oldFromMValue.getOrElse(0.0), replaceInfo.oldToMValue.getOrElse(0.0)))
      })
      val lanesSplitFromOriginal = replaceInfosAffectingLane.map(replaceInfo => {
        val newId = replaceInfo.newLinkId.getOrElse("")
        val newRoadLinkLength = if (newId.nonEmpty) change.newLinks.find(_.linkId == newId).get.linkLength else 0
        val laneLinearReference = AssetLinearReference(originalAdditionalLane.id, originalAdditionalLane.startMeasure,
          originalAdditionalLane.endMeasure, originalAdditionalLane.sideCode)
        val newMValues = if (replaceInfo.newFromMValue.nonEmpty && replaceInfo.newToMValue.nonEmpty) (replaceInfo.newFromMValue.get, replaceInfo.newToMValue.get) else (0.0, 0.0)
        val projection = Projection(replaceInfo.oldFromMValue.getOrElse(0.0), replaceInfo.oldToMValue.getOrElse(0.0), newMValues._1, newMValues._2)
        val (newStartM, newEndM, newSideCode) = MValueCalculator.calculateNewMValues(laneLinearReference, projection, newRoadLinkLength, replaceInfo.digitizationChange)
        originalAdditionalLane.copy(id = getPseudoId ,startMeasure = newStartM, endMeasure = newEndM, linkId = newId, sideCode = newSideCode)
      }).filter(_.linkId.nonEmpty)
      LaneSplit(lanesSplitFromOriginal, originalAdditionalLane)
    })
  }

  def createSplitMainLanes(oldMainLanes: Seq[PersistedLane], splitChange: RoadLinkChange): Seq[LaneSplit] = {
    oldMainLanes.map(originalMainLane => {
      val replaceInfos = splitChange.replaceInfo
      val splitMainLanesToCreate = replaceInfos.map(replaceInfo => {
        val newId = replaceInfo.newLinkId.getOrElse("")
        val newRoadLinkLength = if (newId.nonEmpty) splitChange.newLinks.find(_.linkId == newId).get.linkLength else 0
        val newSideCode = if(replaceInfo.digitizationChange) switch(SideCode.apply(originalMainLane.sideCode)).value
        else originalMainLane.sideCode
        val splitLaneStartMeasure = 0.0
        val splitLaneEndMeasure = LaneUtils.roundMeasure(newRoadLinkLength)
        originalMainLane.copy(id = getPseudoId, linkId =replaceInfo.newLinkId.getOrElse(""), sideCode = newSideCode, startMeasure = splitLaneStartMeasure, endMeasure = splitLaneEndMeasure)
      }).filter(_.linkId.nonEmpty)
      LaneSplit(splitMainLanesToCreate, originalMainLane)
    })
  }

  def fillSplitLinksWithExistingLanes(lanesToUpdate: Seq[PersistedLane], newRoadLinks: Seq[RoadLink], change: RoadLinkChange): (Seq[LaneSplit], Seq[PersistedLane]) = {
    /**
      * Filter divided lanes to be created by linkType and finding replacing roadLink.
      * If RoadLink is missing, it means it's not handled by Digiroad because of its FeatureClass or ConstructionType
      * @param laneSplit LaneSplit object in which lanesToCreate are filtered from
      * @return LaneSplit object with lanes on incorrect links filtered out
      */
    def filterSplitLanes(laneSplit: LaneSplit): LaneSplit = {
      val lanesToCreateFiltered = laneSplit.lanesToCreate.filter(laneToCreate => {
        val newRoadLinkOption = newRoadLinks.find(_.linkId == laneToCreate.linkId)
        newRoadLinkOption match {
          case Some(roadLink) => roadLink.linkType != TractorRoad
          case None =>
            false
        }
      })
      laneSplit.copy(lanesToCreate = lanesToCreateFiltered)
    }

    val (mainLanesOnOldLink, additionalLanesOnOldLink) = lanesToUpdate.partition(lane => isMainLane(lane.laneCode))
    val mainLaneSplits = createSplitMainLanes(mainLanesOnOldLink, change)
    val additionalLaneSplits = calculateAdditionalLanePositionsOnSplitLinks(additionalLanesOnOldLink, change)
    val adjustments = (mainLaneSplits ++ additionalLaneSplits).map(filterSplitLanes)
    val splitLanes = adjustments.flatMap(_.lanesToCreate)
    (adjustments, splitLanes)
  }

  def fillReplacementLinksWithExistingLanes(lanesToUpdate: Seq[PersistedLane], change: RoadLinkChange): Seq[(LanePositionAdjustment, PersistedLane)] = {
    val newRoadLinks = change.newLinks
    newRoadLinks.flatMap(newRoadlink => {
      val replaceInfo = change.replaceInfo.find(_.newLinkId.get == newRoadlink.linkId).get
      val laneAdjustmentsOnLink = lanesToUpdate.map(lane => {
        val laneLinearReference = AssetLinearReference(lane.id, lane.startMeasure, lane.endMeasure, lane.sideCode)
        val projection = Projection(replaceInfo.oldFromMValue.getOrElse(0.0), replaceInfo.oldToMValue.getOrElse(0.0), replaceInfo.newFromMValue.get, replaceInfo.newToMValue.get)
        val (newStartM, newEndM, newSideCode) = if (LaneNumber.isMainLane(lane.laneCode)) {
          val newMainLaneSideCode = if (replaceInfo.digitizationChange) {
            SideCode.switch(SideCode.apply(lane.sideCode)).value
          } else lane.sideCode
          (0.0, MValueCalculator.roundMeasure(newRoadlink.linkLength), newMainLaneSideCode)
        } else {
          MValueCalculator.calculateNewMValues(laneLinearReference, projection, newRoadlink.linkLength, replaceInfo.digitizationChange)
        }
        val adjustment = LanePositionAdjustment(lane.id, newRoadlink.linkId, newStartM, newEndM, SideCode.apply(newSideCode))
        val adjustedLane = lane.copy(linkId = newRoadlink.linkId, startMeasure = newStartM, endMeasure = newEndM, sideCode = newSideCode)
        (adjustment, adjustedLane)
      })
      laneAdjustmentsOnLink
    })
  }
}
