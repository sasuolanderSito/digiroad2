package fi.liikennevirasto.digiroad2.util.assetUpdater

import fi.liikennevirasto.digiroad2.asset.SideCode.switch
import fi.liikennevirasto.digiroad2.asset.{AutoGeneratedUsername, SideCode, TrafficDirection}
import fi.liikennevirasto.digiroad2.client._
import fi.liikennevirasto.digiroad2.client.viite.SearchViiteClient
import fi.liikennevirasto.digiroad2.dao.RoadLinkOverrideDAO.TrafficDirectionDao
import fi.liikennevirasto.digiroad2.dao.lane.LaneWorkListItem
import fi.liikennevirasto.digiroad2.lane.LaneFiller._
import fi.liikennevirasto.digiroad2.lane.LaneNumber.isMainLane
import fi.liikennevirasto.digiroad2.lane._
import fi.liikennevirasto.digiroad2.service.lane.{LaneService, LaneWorkListService}
import fi.liikennevirasto.digiroad2.service.{LinkPropertyChange, RoadAddressService, RoadLinkService}
import fi.liikennevirasto.digiroad2.util.{Digiroad2Properties, MainLanePopulationProcess}
import fi.liikennevirasto.digiroad2.{DummyEventBus, DummySerializer, GeometryUtils}
import org.apache.http.impl.client.HttpClientBuilder
import org.joda.time.DateTime
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

  def moveLanesToMergedRoadLink(mergeChanges: Seq[RoadLinkChange], lanesOnOldLinks: Seq[PersistedLane], newMergedRoadLink: RoadLinkInfo): ChangeSet = {
    val newLinkId = newMergedRoadLink.linkId
    val newLinkLength = newMergedRoadLink.linkLength

    val projectedLanesAndAdjustments = lanesOnOldLinks.map(lane => {
      val relevantChange = mergeChanges.find(_.oldLink.get.linkId == lane.linkId).get
      val relevantReplaceInfo = relevantChange.replaceInfo.find(_.oldLinkId == lane.linkId).get

      val (newStartM, newEndM, newSideCode) = calculateNewMValuesAndSideCode(lane, relevantReplaceInfo, newLinkLength)
      val laneOnMergedLink = lane.copy(linkId = newLinkId, startMeasure = newStartM, endMeasure = newEndM, sideCode = newSideCode)
      val lanePositionAdjustment = LanePositionAdjustment(lane.id, newLinkId, newStartM, newEndM, SideCode.apply(newSideCode))
      (laneOnMergedLink, lanePositionAdjustment)
    })

    val projectedLanes = projectedLanesAndAdjustments.map(_._1)
    val lanePositionAdjustments = projectedLanesAndAdjustments.map(_._2)

    val (fusedLanes, changeSetWithFusedLanes) = fuseLanesOnMergedRoadLink(projectedLanes, ChangeSet(positionAdjustments = lanePositionAdjustments))
    changeSetWithFusedLanes
  }


  def updateChangeSet(changeSet: ChangeSet) : Unit = {

    def saveLanePositionAdjustments(positionAdjustments: Seq[LanePositionAdjustment]): Unit = {
      val toAdjustLanes = laneService.getPersistedLanesByIds(positionAdjustments.map(_.laneId).toSet, newTransaction = false)

      positionAdjustments.foreach { adjustment =>
        val oldLane = toAdjustLanes.find(_.id == adjustment.laneId)
        if(oldLane.nonEmpty){
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
        }
        else{
          logger.error("Old lane not found with ID: " + adjustment.laneId + " Adjustment link ID: " + adjustment.linkId)
        }
      }
    }

    def saveSplitChanges(laneSplits: Seq[LaneSplit]): Unit = {
      val lanesToCreate = laneSplits.flatMap(_.lanesToCreate).toSet
      val lanesToExpire = laneSplits.map(_.originalLane).toSet

      logger.info("Creating " + lanesToCreate.size + " new lanes due to split on link ids: " + lanesToCreate.map(_.linkId).mkString(", "))
      val createdLaneIdsWithOldLanes = laneSplits.map(split => {
        val createdLaneIds = split.lanesToCreate.map(laneToCreate => {laneService.createWithoutTransaction(laneToCreate, AutoGeneratedUsername.generatedInUpdate)})
        (createdLaneIds, split.originalLane)
      })

      // Each old lane must have history row referencing to each new lane created from it
      logger.info("Expiring " + lanesToExpire.size + " old lanes due to split on link ids: " + lanesToExpire.map(_.linkId).mkString(", "))
      createdLaneIdsWithOldLanes.foreach(createdLaneIdAndOldLane => {
        val createdLaneIds = createdLaneIdAndOldLane._1
        val originalLane = createdLaneIdAndOldLane._2
        createdLaneIds.foreach(newId => {
          laneService.moveToHistory(originalLane.id, Some(newId), expireHistoryLane = true, deleteFromLanes = false, AutoGeneratedUsername.generatedInUpdate)
        })
      })

      // After history is created, delete lanes
      logger.info("Deleting " + lanesToExpire.size + " old lanes due to split on linkIds: " + lanesToExpire.map(_.linkId).mkString(", "))
      lanesToExpire.foreach(laneToExpire => {
        laneService.dao.deleteEntryLane(laneToExpire.id)
      })
    }

    // Expire lanes marked to be expired
    val laneIdsToExpire = changeSet.expiredLaneIds.toSeq
    if (laneIdsToExpire.nonEmpty) {
      logger.info("Expiring ids: " + laneIdsToExpire.mkString(", "))
      laneIdsToExpire.foreach(laneId => {
        laneService.moveToHistory(laneId, None, expireHistoryLane = true, deleteFromLanes = true, AutoGeneratedUsername.generatedInUpdate)
      })
    }

    // Create generated new lanes
    if (changeSet.generatedPersistedLanes.nonEmpty){
      logger.info(s"${changeSet.generatedPersistedLanes.size} lanes created for ${changeSet.generatedPersistedLanes.map(_.linkId).toSet.size} links")
      laneService.createMultipleLanes(changeSet.generatedPersistedLanes, AutoGeneratedUsername.generatedInUpdate)
    }

    // Create new lanes, and expire old lanes due to road link splits
    if (changeSet.splitLanes.nonEmpty) {
      saveSplitChanges(changeSet.splitLanes)
    }

    // Adjust m-values and side codes for lanes marked to be adjusted
    if (changeSet.positionAdjustments.nonEmpty) {
      logger.info("Saving SideCode/M-Value adjustments for lane Ids:" + changeSet.positionAdjustments.map(a => "" + a.laneId).mkString(", "))
      saveLanePositionAdjustments(changeSet.positionAdjustments)
    }
  }



  def updateLanes(): Unit = {
    val allRoadLinkChanges = roadLinkChangeClient.getRoadLinkChanges()
    updateTrafficDirectionChangesLaneWorkList(allRoadLinkChanges)
    val (workListChanges, roadLinkChanges) = allRoadLinkChanges.partition(change => isOldLinkOnLaneWorkList(change))
    val changeSet = handleChanges(roadLinkChanges, workListChanges)
    updateChangeSet(changeSet)
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

  def handleTrafficDirectionChange(workListChanges: Seq[RoadLinkChange], workListMainLanes: Seq[PersistedLane]): Seq[ChangeSet] = {
    val laneIds = workListMainLanes.map(_.id).toSet
    val changeSetWithExpired = ChangeSet(expiredLaneIds = laneIds)

    val changeSetsWithAddedMainLanes = workListChanges.map(change => {
      val newLinkIds = change.newLinks.map(_.linkId)
      // Need to fetch RoadLinks because Link Type is needed for main lane creation
      val addedRoadLinks = roadLinkService.getRoadLinksByLinkIds(newLinkIds.toSet)
      val createdMainLanes = MainLanePopulationProcess.createMainLanesForRoadLinks(addedRoadLinks, saveResult = false)
      ChangeSet(generatedPersistedLanes = createdMainLanes)
    })

    changeSetsWithAddedMainLanes :+ changeSetWithExpired
  }

  def handleChanges(roadLinkChanges: Seq[RoadLinkChange], workListChanges: Seq[RoadLinkChange] = Seq()): ChangeSet = {
    val oldLinkIds = roadLinkChanges.flatMap(_.oldLink).map(_.linkId)
    val oldWorkListLinkIds = workListChanges.flatMap(_.oldLink).map(_.linkId)

    val lanesOnOldRoadLinks = laneService.fetchAllLanesByLinkIds(oldLinkIds, newTransaction = false)
    val lanesOnWorkListLinks = laneService.fetchAllLanesByLinkIds(oldWorkListLinkIds, newTransaction = false)

    // Additional lanes can't be processed if link is on the lane work list, only handle main lanes on those links
    val workListMainLanes = lanesOnWorkListLinks.filter(lane => LaneNumber.isMainLane(lane.laneCode))
    val trafficDirectionChangeSets = handleTrafficDirectionChange(workListChanges, workListMainLanes)

    // Merge changes consist of multiple messages, group them and handle separately from rest of the changes
    val (mergeChanges, singleMessageChanges) = roadLinkChanges.partition(roadLinkChange => {
      val otherRoadLinkChanges = roadLinkChanges.filterNot(change => change == roadLinkChange)
      roadLinkChangeClient.partitionMergeChanges(roadLinkChange, otherRoadLinkChanges)
    })

    val mergeChangesGrouped = mergeChanges.groupBy(_.newLinks).values.toSeq
    val mergeChangeSets = mergeChangesGrouped.map(mergeChanges => {
      val oldLinkIds = mergeChanges.flatMap(_.oldLink).map(_.linkId)
      val newMergedRoadLink = mergeChanges.head.newLinks.head
      val lanesOnOldLinks = lanesOnOldRoadLinks.filter(lane => oldLinkIds.contains(lane.linkId))
      val changeSetWithMergeChanges = moveLanesToMergedRoadLink(mergeChanges, lanesOnOldLinks, newMergedRoadLink)
      changeSetWithMergeChanges
    })

    val otherChangeSets = singleMessageChanges.map(change => {
      change.changeType match {
        case RoadLinkChangeType.Add =>
          val newLinkIds = change.newLinks.map(_.linkId)
          // Need to fetch RoadLinks because Link Type is needed for main lane creation
          val addedRoadLinks = roadLinkService.getRoadLinksByLinkIds(newLinkIds.toSet)
          val createdMainLanes = MainLanePopulationProcess.createMainLanesForRoadLinks(addedRoadLinks, saveResult = false)
          ChangeSet(generatedPersistedLanes = createdMainLanes)
        case RoadLinkChangeType.Remove =>
          val removedLinkId = change.oldLink.get.linkId
          val lanesToExpireOnRemovedLink = lanesOnOldRoadLinks.filter(_.linkId == removedLinkId).map(_.id).toSet
          ChangeSet(expiredLaneIds = lanesToExpireOnRemovedLink)
        case RoadLinkChangeType.Replace =>
          val lanesOnReplacedLink = lanesOnOldRoadLinks.filter(lane => change.oldLink.get.linkId == lane.linkId)
          val allLanePositionAdjustments = fillReplacementLinksWithExistingLanes(lanesOnReplacedLink, change)
          ChangeSet(positionAdjustments = allLanePositionAdjustments)
        case RoadLinkChangeType.Split =>
          val oldRoadLink = change.oldLink.get
          val lanesOnSplitLink = lanesOnOldRoadLinks.filter(_.linkId == oldRoadLink.linkId)
          val lanesOnSplitLinks = fillSplitLinksWithExistingLanes(lanesOnSplitLink, change)
          ChangeSet(splitLanes = lanesOnSplitLinks)
      }
    })


    val changeSet = (otherChangeSets ++ mergeChangeSets ++ trafficDirectionChangeSets).foldLeft(ChangeSet())(LaneFiller.combineChangeSets)
    changeSet
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

  def calculateNewMValuesAndSideCode(lane: PersistedLane, replaceInfo: ReplaceInfo,
                                     roadLinkLength: Double): (Double, Double, Int) = {

    val oldLength = replaceInfo.oldToMValue - replaceInfo.oldFromMValue
    val newLength = replaceInfo.newToMValue - replaceInfo.newFromMValue
    val roadLinkLengthRounded = (Math.round(roadLinkLength * 1000).toDouble / 1000)

    // Test if the direction has changed -> side code will be affected, too
    if (replaceInfo.digitizationChange) {
      val newSideCode = switch(SideCode.apply(lane.sideCode)).value

      val newStart = replaceInfo.newFromMValue - (lane.endMeasure - replaceInfo.oldFromMValue) * Math.abs(newLength / oldLength)
      val newEnd = replaceInfo.newToMValue - (lane.startMeasure - replaceInfo.oldToMValue) * Math.abs(newLength / oldLength)

      // Test if asset is affected by projection
      if (lane.endMeasure <= replaceInfo.oldFromMValue || lane.startMeasure >= replaceInfo.oldToMValue)
        (lane.startMeasure, lane.endMeasure, newSideCode)
      else {
        val newStartMRounded = (Math.round(newStart * 1000).toDouble / 1000)
        val newEndMRounded = (Math.round(newEnd * 1000).toDouble / 1000)
        (Math.min(roadLinkLengthRounded, Math.max(0.0, newStartMRounded)), Math.max(0.0, Math.min(roadLinkLengthRounded, newEndMRounded)), newSideCode)
      }
    } else {
      val newStart = replaceInfo.newFromMValue + (lane.startMeasure - replaceInfo.oldFromMValue) * Math.abs(newLength / oldLength)
      val newEnd = replaceInfo.newToMValue + (lane.endMeasure - replaceInfo.oldToMValue) * Math.abs(newLength / oldLength)

      // Test if asset is affected by projection
      if (lane.endMeasure <= replaceInfo.oldFromMValue || lane.startMeasure >= replaceInfo.oldToMValue) {
        (lane.startMeasure, lane.endMeasure, lane.sideCode)
      } else {
        val newStartMRounded = (Math.round(newStart * 1000).toDouble / 1000)
        val newEndMRounded = (Math.round(newEnd * 1000).toDouble / 1000)
        (Math.min(roadLinkLengthRounded, Math.max(0.0, newStartMRounded)), Math.max(0.0, Math.min(roadLinkLengthRounded, newEndMRounded)), lane.sideCode)
      }
    }
  }

  def calculateAdditionalLanePositionsOnSplitLinks(oldAdditionalLanes: Seq[PersistedLane], change: RoadLinkChange): Seq[LaneSplit] = {
    oldAdditionalLanes.map(originalAdditionalLane => {
      val replaceInfosAffectingLane = change.replaceInfo.filter(replaceInfo => {
        GeometryUtils.liesInBetween(replaceInfo.oldFromMValue, (originalAdditionalLane.startMeasure, originalAdditionalLane.endMeasure)) ||
          GeometryUtils.liesInBetween(replaceInfo.oldToMValue, (originalAdditionalLane.startMeasure, originalAdditionalLane.endMeasure))
      })
      val lanesSplitFromOriginal = replaceInfosAffectingLane.map(replaceInfo => {
        val newRoadLinkLength = change.newLinks.find(_.linkId == replaceInfo.newLinkId).get.linkLength
        val (newStartM, newEndM, newSideCode) = calculateNewMValuesAndSideCode(originalAdditionalLane, replaceInfo, newRoadLinkLength)
        originalAdditionalLane.copy(startMeasure = newStartM, endMeasure = newEndM, linkId = replaceInfo.newLinkId, sideCode = newSideCode)
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
        val splitLaneEndMeasure = Math.round(replaceInfo.newToMValue * 1000).toDouble / 1000
        originalMainLane.copy(id = 0, linkId = replaceInfo.newLinkId, sideCode = newSideCode, startMeasure = splitLaneStartMeasure, endMeasure = splitLaneEndMeasure)
      })
      LaneSplit(splitMainLanesToCreate, originalMainLane)
    })
  }

  def fillSplitLinksWithExistingLanes(lanesToUpdate: Seq[PersistedLane], change: RoadLinkChange): Seq[LaneSplit] = {
    val (mainLanesOnOldLink, additionalLanesOnOldLink) = lanesToUpdate.partition(lane => isMainLane(lane.laneCode))
    val mainLaneSplits = createSplitMainLanes(mainLanesOnOldLink, change)
    val additionalLaneSplits = calculateAdditionalLanePositionsOnSplitLinks(additionalLanesOnOldLink, change)
    mainLaneSplits ++ additionalLaneSplits
  }

  def fillReplacementLinksWithExistingLanes(lanesToUpdate: Seq[PersistedLane], change: RoadLinkChange): Seq[LanePositionAdjustment] = {
    val newRoadLinks = change.newLinks
    val allLaneAdjustments = newRoadLinks.flatMap(newRoadlink => {
      val replaceInfo = change.replaceInfo.find(_.newLinkId == newRoadlink.linkId).get
      val laneAdjustmentsOnLink = lanesToUpdate.map(lane => {
        val (newStartM, newEndM, newSideCode) = calculateNewMValuesAndSideCode(lane, replaceInfo, newRoadlink.linkLength)
        LanePositionAdjustment(lane.id, newRoadlink.linkId, newStartM, newEndM, SideCode.apply(newSideCode))
      })
      laneAdjustmentsOnLink
    })
    allLaneAdjustments
  }
}
