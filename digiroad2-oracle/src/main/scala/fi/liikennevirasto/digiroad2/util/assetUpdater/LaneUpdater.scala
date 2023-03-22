package fi.liikennevirasto.digiroad2.util.assetUpdater

import fi.liikennevirasto.digiroad2.{DummyEventBus, DummySerializer, GeometryUtils, asset}
import fi.liikennevirasto.digiroad2.GeometryUtils.Projection
import fi.liikennevirasto.digiroad2.asset.{AutoGeneratedUsername, SideCode}
import fi.liikennevirasto.digiroad2.client.viite.SearchViiteClient
import fi.liikennevirasto.digiroad2.client.vvh.{ChangeInfo, ChangeType}
import fi.liikennevirasto.digiroad2.client.{RoadLinkChange, RoadLinkChangeClient, RoadLinkChangeType, RoadLinkClient}
import fi.liikennevirasto.digiroad2.lane.LaneFiller.{ChangeSet, LaneSplit, MValueAdjustment, SideCodeAdjustment, baseAdjustment}
import fi.liikennevirasto.digiroad2.lane.LaneNumber.isMainLane
import fi.liikennevirasto.digiroad2.lane.{LaneFiller, NewLane, PersistedLane}
import fi.liikennevirasto.digiroad2.linearasset.{LinkId, RoadLink}
import fi.liikennevirasto.digiroad2.service.{RoadAddressService, RoadLinkService}
import fi.liikennevirasto.digiroad2.service.lane.LaneService
import fi.liikennevirasto.digiroad2.service.linearasset.Measures
import fi.liikennevirasto.digiroad2.util.{Digiroad2Properties, MainLanePopulationProcess}
import org.apache.http.impl.client.HttpClientBuilder
import org.joda.time.DateTime
import org.slf4j.LoggerFactory

object LaneUpdater {
  lazy val roadLinkChangeClient: RoadLinkChangeClient = new RoadLinkChangeClient
  lazy val roadLinkClient: RoadLinkClient = new RoadLinkClient(Digiroad2Properties.vvhRestApiEndPoint)
  lazy val roadLinkService: RoadLinkService = new RoadLinkService(roadLinkClient, new DummyEventBus, new DummySerializer)
  lazy val viiteClient: SearchViiteClient = new SearchViiteClient(Digiroad2Properties.viiteRestApiEndPoint, HttpClientBuilder.create().build())
  lazy val roadAddressService: RoadAddressService = new RoadAddressService(viiteClient)
  lazy val laneService: LaneService = new LaneService(roadLinkService, new DummyEventBus, roadAddressService)
  val username: String = "samuutus"
  private val logger = LoggerFactory.getLogger(getClass)
  def laneFiller: LaneFiller = new LaneFiller

  def getMainLaneAdjustments(mainLanes: Seq[PersistedLane], newRoadLink: RoadLink,digitizationChange: Boolean): Seq[(Option[MValueAdjustment], Option[SideCodeAdjustment])] = {
    mainLanes.map(mainLane => {
      val mainLaneSideCodeAdjustment = if (digitizationChange) {
        val newSideCode = SideCode.apply(mainLane.sideCode) match {
          case SideCode.AgainstDigitizing => SideCode.TowardsDigitizing.value
          case SideCode.TowardsDigitizing => SideCode.AgainstDigitizing.value
          case _ => mainLane.sideCode
        }
        Some(SideCodeAdjustment(mainLane.id, SideCode.apply(newSideCode)))
      }
      else None
      val newEndMeasure = Math.round(newRoadLink.length * 1000).toDouble / 1000
      val mainLaneMValueAdjustment = if(newEndMeasure != mainLane.endMeasure) Some(MValueAdjustment(mainLane.id, newRoadLink.linkId, 0.0, newEndMeasure))
      else None
      (mainLaneMValueAdjustment, mainLaneSideCodeAdjustment)
    })
  }

  // Calculate new lane measures on new road link
  def calculateMValuesOnNewGeometry(lane: PersistedLane, newRoadLink: RoadLink, roadLinkChange: RoadLinkChange):Measures = {
    val oldRoadLink = roadLinkChange.oldLink.get
    val laneStartPoint = GeometryUtils.calculatePointFromLinearReference(oldRoadLink.geometry, lane.startMeasure).get
    val laneEndPoint = GeometryUtils.calculatePointFromLinearReference(oldRoadLink.geometry, lane.endMeasure).get

    val laneStartMeasureOnNewGeometry = GeometryUtils.calculateLinearReferenceFromPoint(laneStartPoint, newRoadLink.geometry)
    val laneEndMeasureOnNewGeometry = GeometryUtils.calculateLinearReferenceFromPoint(laneEndPoint, newRoadLink.geometry)

    Measures(laneStartMeasureOnNewGeometry, laneEndMeasureOnNewGeometry)
  }

  def updateChangeSet(changeSet: ChangeSet) : Unit = {
    def treatChangeSetData(changeSetToTreat: Seq[baseAdjustment]): Unit = {
      val toAdjustLanes = laneService.getPersistedLanesByIds(changeSetToTreat.map(_.laneId).toSet, false)

      changeSetToTreat.foreach { adjustment =>
        val oldLane = toAdjustLanes.find(_.id == adjustment.laneId)
        if(oldLane.nonEmpty){
          // TODO Samaan tallennukseen SideCode ja Measure adj. ettei tule kaksi historiointia
          laneService.moveToHistory(oldLane.get.id, None, expireHistoryLane = false, deleteFromLanes = false, AutoGeneratedUsername.generatedInUpdate)
          laneService.dao.updateLanePositionAndModifiedDate(adjustment.laneId, adjustment.linkId, adjustment.startMeasure, adjustment.endMeasure, AutoGeneratedUsername.generatedInUpdate)
        }
        else{
          logger.error("Old lane not found with ID: " + adjustment.laneId + " Adjustment link ID: " + adjustment.linkId)
        }

      }
    }

    def saveSplitChanges(laneSplits: Seq[LaneSplit]): Unit = {
      val lanesToCreate = laneSplits.map(_.laneToCreate)
      val lanesToExpire = laneSplits.map(_.oldLane)

      logger.info("Creating " + lanesToCreate.size + " new lanes due to split on link ids: " +
        lanesToCreate.map(_.linkId).mkString(", "))
      val createdLaneIdsWithOldLanes = laneSplits.map(split => {
        val createdLane = laneService.createWithoutTransaction(split.laneToCreate, AutoGeneratedUsername.generatedInUpdate)
        (createdLane, split.oldLane)
      })

      logger.info("Expiring " + lanesToExpire.size + " old lanes due to split on link ids: " +
        lanesToExpire.map(_.linkId).mkString(", "))

      // Each old lane must have history row referencing to each new lane created from it
      createdLaneIdsWithOldLanes.foreach(createdLaneIdAndOldLane => {
        val createdLaneId = createdLaneIdAndOldLane._1
        val oldLane = createdLaneIdAndOldLane._2
        laneService.moveToHistory(oldLane.id, Some(createdLaneId), expireHistoryLane = true, deleteFromLanes = false, AutoGeneratedUsername.generatedInUpdate)
      })

      logger.info("Deleting " + lanesToExpire.size + " old lanes due to split on linkIds: " + lanesToExpire.map(_.linkId).mkString(", "))
      lanesToExpire.foreach(oldLane => {
        laneService.dao.deleteEntryLane(oldLane.id)
      })
    }

    // TODO Niputa SideCode ja MvalueAdj muutokset yhteen, jotta voidaan tallentaa yhdellä historioinnilla kummatkin
//    val pairsByMValue = changeSet.adjustedMValues.map(mValueAdjustment => {
//      val sideCodeAdjustmentForSameLane = changeSet.adjustedSideCodes.find(_.laneId == mValueAdjustment.laneId)
//      (mValueAdjustment, sideCodeAdjustmentForSameLane)
//    })
//    val pairsBySideCode = changeSet.adjustedSideCodes.map(sideCodeAdjustment => {
//      val mValueAdjustmentForSameLane = changeSet.adjustedMValues.find(_.laneId == sideCodeAdjustment.laneId)
//      (mValueAdjustmentForSameLane, sideCodeAdjustment)
//    })
//
//    val combinedPositionChanges = pairsByMValue.map(pairByMValue => {
//      pairsBySideCode.find(pairBySideCode => pairBySideCode._1 match {
//        case Some(mValueAdj) => mValueAdj.laneId == pairByMValue._1.laneId
//      })
//    })

    // Expire lanes marked to be expired
    val laneIdsToExpire = changeSet.expiredLaneIds.toSeq
    if (laneIdsToExpire.nonEmpty)
      logger.info("Expiring ids " + laneIdsToExpire.mkString(", "))
    laneIdsToExpire.foreach(laneService.moveToHistory(_, None, expireHistoryLane = true, deleteFromLanes = true, AutoGeneratedUsername.generatedInUpdate))

    // Create generated new lanes
    if (changeSet.generatedPersistedLanes.nonEmpty){
      logger.info(s"${changeSet.generatedPersistedLanes.size} lanes created for ${changeSet.generatedPersistedLanes.map(_.linkId).toSet.size} links")
      laneService.createMultipleLanes(changeSet.generatedPersistedLanes, AutoGeneratedUsername.generatedInUpdate)
    }

    // Create new lanes, and expire old lanes due to road link splits
    if (changeSet.splitLanes.nonEmpty) {
      saveSplitChanges(changeSet.splitLanes)
    }

    // Adjust side codes for lanes marked to be adjusted
    if (changeSet.adjustedSideCodes.nonEmpty)
      logger.info("Saving SideCode adjustments for lane/link ids:" + changeSet.adjustedSideCodes.map(a => "" + a.laneId).mkString(", "))

    changeSet.adjustedSideCodes.foreach { adjustment =>
      laneService.moveToHistory(adjustment.laneId, None, expireHistoryLane = false, deleteFromLanes = false, AutoGeneratedUsername.generatedInUpdate)
      laneService.dao.updateSideCode(adjustment.laneId, adjustment.sideCode.value, AutoGeneratedUsername.generatedInUpdate)
    }

    // Adjust M-values for lanes marked to be adjusted
    if (changeSet.adjustedMValues.nonEmpty)
      logger.info("Saving adjustments for lane/link ids:" + changeSet.adjustedMValues.map(a => "" + a.laneId + "/" + a.linkId).mkString(", "))

    treatChangeSetData(changeSet.adjustedMValues)

  }

  def expireLanesOnDeletedLinks(removedLinkId: String, lanesOnChangedLinks: Seq[PersistedLane]): Set[Long] = {
    val removedLaneIds = lanesOnChangedLinks.filter(_.linkId == removedLinkId).map(_.id)
    removedLaneIds.map(id => laneService.moveToHistory(id, None, expireHistoryLane = true, deleteFromLanes = true, username)).toSet
  }

  def updateLanes(): Unit = {
    val roadLinkChanges = roadLinkChangeClient.getRoadLinkChanges()
    handleChanges(roadLinkChanges)
  }

  def handleChanges(roadLinkChanges: Seq[RoadLinkChange]): Unit = {
    val newLinkIds = roadLinkChanges.flatMap(_.newLinks.map(_.linkId))
    val oldLinkIds = roadLinkChanges.flatMap(_.oldLink).map(_.linkId)

    val lanesOnChangedLinks = laneService.fetchAllLanesByLinkIds(oldLinkIds, newTransaction = false)

    val changeSets = roadLinkChanges.map(change => {
      change.changeType match {
        case RoadLinkChangeType.Add =>
          val addedLinkIds = change.newLinks.map(_.linkId)
          val addedRoadLinks = roadLinkService.getRoadLinksByLinkIds(addedLinkIds.toSet)
          val createdMainLanes = MainLanePopulationProcess.createMainLanesForRoadLinks(addedRoadLinks, saveResult = false)
          ChangeSet(generatedPersistedLanes = createdMainLanes)
        case RoadLinkChangeType.Remove =>
          val removedLinkId = change.oldLink.get.linkId
          val expiredLaneIds = expireLanesOnDeletedLinks(removedLinkId, lanesOnChangedLinks)
          ChangeSet(expiredLaneIds = expiredLaneIds)
        case RoadLinkChangeType.Replace =>
          val replacementRoadLinkIds = change.newLinks.map(_.linkId)
          val replacementRoadLinks = roadLinkService.getRoadLinksByLinkIds(replacementRoadLinkIds.toSet)
          val lanesOnReplacedLink = lanesOnChangedLinks.filter(lane => change.oldLink.get.linkId == lane.linkId)
          fillReplacementLinksWithExistingLanes(replacementRoadLinks, lanesOnReplacedLink, change)
        case RoadLinkChangeType.Split =>
          val newRoadLinkIds = change.newLinks.map(_.linkId)
          val newRoadLinks = roadLinkService.getRoadLinksByLinkIds(newRoadLinkIds.toSet)
          val oldRoadLinkId = change.oldLink.get.linkId
          val oldRoadLink = roadLinkService.getRoadLinksByLinkIds(Set(oldRoadLinkId)).head
          val lanesOnOldLink = lanesOnChangedLinks.filter(_.linkId == oldRoadLink.linkId)
          fillSplitLinksWithExistingLanes(newRoadLinks, oldRoadLink, lanesOnOldLink, change)
      }
    })


    val changeSet = changeSets.foldLeft(ChangeSet())(LaneFiller.combineChangeSets)
    updateChangeSet(changeSet)
  }

  def calculateAdditionalLanePositionsOnSplitLinks(newRoadLinks: Seq[RoadLink], oldAdditionalLanes: Seq[PersistedLane],
                                                   change: RoadLinkChange): Seq[LaneSplit] = {
    ???
  }

  def moveMainLanesToNewSplitLinks(newRoadLinks: Seq[RoadLink], oldMainLanes: Seq[PersistedLane],
                                   change: RoadLinkChange): Seq[LaneSplit] = {
    val newMainLanes = MainLanePopulationProcess.createMainLanesForRoadLinks(newRoadLinks, saveResult = false)
    val replacementInfos = change.replaceInfo

    val laneSplits = oldMainLanes.flatMap { oldLane =>
      replacementInfos.flatMap { replaceInfo =>
        val newMainLanesToHandle = if (replaceInfo.digitizationChange) {
          newMainLanes.filter(newLane => newLane.sideCode == SideCode.switch(SideCode.apply(oldLane.sideCode)).value || newLane.sideCode == SideCode.BothDirections.value)
        } else {
          newMainLanes.filter(newLane => newLane.sideCode == oldLane.sideCode || newLane.sideCode == SideCode.BothDirections.value)
        }
        newMainLanesToHandle.map { newLane =>
          LaneSplit(newLane, oldLane)
        }
      }
    }

    laneSplits
  }

  // TODO Lisäkaistojen split logiikka
  def fillSplitLinksWithExistingLanes(newRoadLinks: Seq[RoadLink], oldRoadLink: RoadLink, lanesToUpdate: Seq[PersistedLane], change: RoadLinkChange): ChangeSet = {
    val (mainLanesOnOldLink, additionalLanesOnOldLink) = lanesToUpdate.partition(lane => isMainLane(lane.laneCode))
    val mainLaneSplits = moveMainLanesToNewSplitLinks(newRoadLinks, mainLanesOnOldLink, change)
    val additionalLaneSplits = calculateAdditionalLanePositionsOnSplitLinks(newRoadLinks, additionalLanesOnOldLink, change)
    ???
  }

  // Replacement käsittely lisäkaistoille
  // Todo muuta käyttämään yhtä Change
  def fillReplacementLinksWithExistingLanes(newRoadLinks: Seq[RoadLink], lanesToUpdate: Seq[PersistedLane], change: RoadLinkChange): ChangeSet = {
    val allLaneAdjustments = newRoadLinks.map(newRoadlink => {
      val digitizationChange = change.replaceInfo.find(_.newLinkId == newRoadlink.linkId).get.digitizationChange
      val (mainLanes, additionalLanes) = lanesToUpdate.partition(lane => isMainLane(lane.laneCode))

      val mainLaneAdjustments = getMainLaneAdjustments(mainLanes, newRoadlink, digitizationChange)

      // TODO Projection toiminta kunnolla selväksi, Option käsittely
      // TODO SideCode muuttaminen yhteisesti kaikille caseille myöhemmin?
      // TODO Turhien adjustment karsiminen
      val additionalLaneMValueAndSideCodeAdjustments = additionalLanes.map(additionalLane => {
        val newMeasures = calculateMValuesOnNewGeometry(additionalLane, newRoadlink, change)
        val measureAdjustment = Some(MValueAdjustment(additionalLane.id, newRoadlink.linkId, newMeasures.startMeasure, newMeasures.endMeasure))
        val newSideCode = if(digitizationChange) SideCode.switch(SideCode.apply(additionalLane.sideCode))
        else SideCode.apply(additionalLane.sideCode)
        val sideCodeAdjustment = if (newSideCode.value != additionalLane.sideCode) {
          Some(SideCodeAdjustment(additionalLane.id, newSideCode))
        } else {
          None
        }

        (measureAdjustment, sideCodeAdjustment)
      })

      val mainLaneMValueAdjustments = mainLaneAdjustments.flatMap(_._1)
      val mainLaneSideCodeAdjustments = mainLaneAdjustments.flatMap(_._2)
      val additionalLaneMValueAdjustments = additionalLaneMValueAndSideCodeAdjustments.flatMap(_._1)
      val additionalLaneSideCodeAdjustments = additionalLaneMValueAndSideCodeAdjustments.flatMap(_._2)
      (mainLaneMValueAdjustments ++ additionalLaneMValueAdjustments, mainLaneSideCodeAdjustments ++ additionalLaneSideCodeAdjustments)
    })

    val changeSetWithAdjustments = allLaneAdjustments.foldLeft(ChangeSet()){(changedSet, adjustments) =>
      val mValueAdjustments = adjustments._1
      val sideCodeAdjustments = adjustments._2

      changedSet.copy(adjustedMValues = changedSet.adjustedMValues ++ mValueAdjustments, adjustedSideCodes = changedSet.adjustedSideCodes ++ sideCodeAdjustments)
    }

    changeSetWithAdjustments
  }


  private def getProjection(change: RoadLinkChange, newRoadLink: RoadLink): Option[Projection] = {
    val replaceInfoOption = change.replaceInfo.find(_.newLinkId == newRoadLink.linkId)
    replaceInfoOption match {
      case Some(replaceInfo) => Some(Projection(replaceInfo.oldFromMValue, replaceInfo.oldToMValue, replaceInfo.newFromMValue, replaceInfo.newToMValue, DateTime.now.getMillis))
      case None => None
    }
  }

  def fillNewRoadLinksWithPreviousAssetsData(roadLinks: Seq[RoadLink], historyRoadLinks: Seq[RoadLink], lanesToUpdate: Seq[PersistedLane],
                                             currentLanes: Seq[PersistedLane], changes: Seq[ChangeInfo], changeSet: ChangeSet) : (Seq[PersistedLane], ChangeSet) ={

    val (replacementChanges, otherChanges) = changes.partition( ChangeType.isReplacementChange)
    val reverseLookupMap = replacementChanges.filterNot(c=>c.oldId.isEmpty || c.newId.isEmpty).map(c => c.newId.get -> c).groupBy(_._1).mapValues(_.map(_._2))

    val extensionChanges = otherChanges.filter(ChangeType.isExtensionChange).flatMap(
      ext => reverseLookupMap.getOrElse(ext.newId.getOrElse(LinkId.Unknown.value), Seq()).flatMap(
        rep => addSourceRoadLinkToChangeInfo(ext, rep)))

    val fullChanges = extensionChanges ++ replacementChanges
    val projections = mapReplacementProjections(lanesToUpdate, currentLanes, roadLinks, fullChanges).filterNot(p => p._2._1.isEmpty || p._2._2.isEmpty)

    val (projectedLanesMapped, newChangeSet) = projections.foldLeft((Seq.empty[Option[PersistedLane]], changeSet)) {
      case ((persistedAssets, cs), (asset, (Some(roadLink), Some(projection)))) =>
        val historyRoadLink = historyRoadLinks.find(_.linkId == asset.linkId)
        val relevantChange = fullChanges.find(_.newId.contains(roadLink.linkId))
        relevantChange match {
          case Some(change) =>
            val (linearAsset, changes) = projectLinearAsset(asset, roadLink, historyRoadLink, projection, cs, change)
            (persistedAssets ++ Seq(linearAsset), changes)
          case _ => (Seq.empty[Option[PersistedLane]], changeSet)
        }
      case _ => (Seq.empty[Option[PersistedLane]], changeSet)
    }

    val projectedLanes = projectedLanesMapped.flatten
    (projectedLanes, newChangeSet)
  }

  def projectLinearAsset(lane: PersistedLane, targetRoadLink: RoadLink, historyRoadLink: Option[RoadLink], projection: Projection, changedSet: ChangeSet, change: ChangeInfo) : (Option[PersistedLane], ChangeSet)= {
//    val newLinkId = targetRoadLink.linkId
//    val laneId = lane.linkId match {
//      case targetRoadLink.linkId => lane.id
//      case _ => 0
//    }
//    val typed = ChangeType.apply(change.changeType)
//
//    val (newStart, newEnd, newSideCode) = typed match {
//      case ChangeType.LengthenedCommonPart | ChangeType.LengthenedNewPart | ChangeType.ReplacedNewPart =>
//        laneFiller.calculateNewMValuesAndSideCode(lane, historyRoadLink, projection, targetRoadLink.length, true)
//      case ChangeType.DividedModifiedPart | ChangeType.DividedNewPart if (lane.endMeasure < projection.oldStart ||
//        lane.startMeasure > projection.oldEnd) =>
//        (0.0, 0.0, 99)
//      case _ =>
//        laneFiller.calculateNewMValuesAndSideCode(lane, historyRoadLink, projection, targetRoadLink.length)
//    }
//    val projectedLane = Some(PersistedLane(laneId, newLinkId, newSideCode, lane.laneCode, lane.municipalityCode, newStart, newEnd, lane.createdBy,
//      lane.createdDateTime, lane.modifiedBy, lane.modifiedDateTime, lane.expiredBy, lane.expiredDateTime,
//      expired = false, projection.timeStamp, lane.geomModifiedDate, lane.attributes))
//
//
//    val changeSet = laneId match {
//      case 0 => changedSet
//      case _ if(newSideCode != lane.sideCode) => changedSet.copy(adjustedVVHChanges =  changedSet.adjustedVVHChanges ++
//        Seq(VVHChangesAdjustment(laneId, newLinkId, newStart, newEnd, projection.timeStamp)),
//        adjustedSideCodes = changedSet.adjustedSideCodes ++ Seq(SideCodeAdjustment(laneId, SideCode.apply(newSideCode))))
//
//      case _ => changedSet.copy(adjustedVVHChanges =  changedSet.adjustedVVHChanges ++
//        Seq(VVHChangesAdjustment(laneId, newLinkId, newStart, newEnd, projection.timeStamp)))
//    }
//
//    (projectedLane, changeSet)
???
  }


  private def mapReplacementProjections(oldLinearAssets: Seq[PersistedLane], currentLinearAssets: Seq[PersistedLane], roadLinks: Seq[RoadLink], changes: Seq[ChangeInfo]) : Seq[(PersistedLane, (Option[RoadLink], Option[Projection]))] = {

    val targetLinks = changes.flatMap(_.newId).toSet
    val newRoadLinks = roadLinks.filter(rl => targetLinks.contains(rl.linkId)).groupBy(_.linkId)
    val changeMap = changes.filterNot(c => c.newId.isEmpty || c.oldId.isEmpty).map(c => (c.oldId.get, c.newId.get)).groupBy(_._1)
    val targetRoadLinks = changeMap.mapValues(a => a.flatMap(b => newRoadLinks.getOrElse(b._2, Seq())).distinct)
    val groupedLinearAssets = currentLinearAssets.groupBy(_.linkId)
    val groupedOldLinearAssets = oldLinearAssets.groupBy(_.linkId)
    oldLinearAssets.flatMap{asset =>
      targetRoadLinks.getOrElse(asset.linkId, Seq()).map(newRoadLink =>
        (asset,
          getRoadLinkAndProjection(roadLinks, changes, asset.linkId, newRoadLink.linkId, groupedOldLinearAssets, groupedLinearAssets))
      )}
  }

  private def getRoadLinkAndProjection(roadLinks: Seq[RoadLink], changes: Seq[ChangeInfo], oldId: String, newId: String,
                                       linearAssetsToUpdate: Map[String, Seq[PersistedLane]],
                                       currentLinearAssets: Map[String, Seq[PersistedLane]]): (Option[RoadLink], Option[Projection]) = {
    val roadLink = roadLinks.find(rl => newId == rl.linkId)
    val changeInfo = changes.find(c => c.oldId.getOrElse(LinkId.Unknown.value) == oldId && c.newId.getOrElse(LinkId.Unknown.value) == newId)
    val projection = changeInfo match {
      case Some(changedPart) =>
        // ChangeInfo object related assets; either mentioned in oldId or in newId
        val linearAssets = (linearAssetsToUpdate.getOrElse(changedPart.oldId.getOrElse(LinkId.Unknown.value), Seq()) ++
          currentLinearAssets.getOrElse(changedPart.newId.getOrElse(LinkId.Unknown.value), Seq())).distinct
        mapChangeToProjection(changedPart, linearAssets)
      case _ => None
    }
    (roadLink,projection)
  }

  private def mapChangeToProjection(change: ChangeInfo, linearAssets: Seq[PersistedLane]): Option[Projection] = {
    val typed = ChangeType.apply(change.changeType)
    typed match {
      // cases 5, 6, 1, 2
      case ChangeType.DividedModifiedPart  | ChangeType.DividedNewPart | ChangeType.CombinedModifiedPart |
           ChangeType.CombinedRemovedPart => projectAssetsConditionally(change, linearAssets, testNoAssetExistsOnTarget, useOldId=false)
      // cases 3, 7, 13, 14
      case ChangeType.LengthenedCommonPart | ChangeType.ShortenedCommonPart | ChangeType.ReplacedCommonPart |
           ChangeType.ReplacedNewPart =>
        projectAssetsConditionally(change, linearAssets, testAssetOutdated, useOldId=false)

      case ChangeType.LengthenedNewPart | ChangeType.ReplacedNewPart =>
        projectAssetsConditionally(change, linearAssets, testAssetsContainSegment, useOldId=true)
      case _ =>
        None
    }
  }

  private def testNoAssetExistsOnTarget(lanes: Seq[PersistedLane], linkId: String, mStart: Double, mEnd: Double,
                                        timeStamp: Long): Boolean = {
    !lanes.exists(l => l.linkId == linkId && GeometryUtils.overlaps((l.startMeasure,l.endMeasure),(mStart,mEnd)))
  }

  private def testAssetOutdated(lanes: Seq[PersistedLane], linkId: String, mStart: Double, mEnd: Double,
                                timeStamp: Long): Boolean = {
    val targetLanes = lanes.filter(a => a.linkId == linkId)
    targetLanes.nonEmpty && !targetLanes.exists(a => a.timeStamp >= timeStamp)
  }

  private def projectAssetsConditionally(change: ChangeInfo, lanes: Seq[PersistedLane],
                                         condition: (Seq[PersistedLane], String, Double, Double, Long) => Boolean,
                                         useOldId: Boolean): Option[Projection] = {
    val id = if (useOldId) {
      change.oldId
    } else {
      change.newId
    }

    (id, change.oldStartMeasure, change.oldEndMeasure, change.newStartMeasure, change.newEndMeasure, change.timeStamp) match {
      case (Some(targetId), Some(oldStart:Double), Some(oldEnd:Double),
      Some(newStart:Double), Some(newEnd:Double), timeStamp) =>

        if (condition(lanes, targetId, oldStart, oldEnd, timeStamp)) {
          Some(Projection(oldStart, oldEnd, newStart, newEnd, timeStamp))
        } else {
          None
        }

      case _ => None
    }
  }

  private def testAssetsContainSegment(lanes: Seq[PersistedLane], linkId: String, mStart: Double, mEnd: Double,
                                       timeStamp: Long): Boolean = {
    val targetAssets = lanes.filter(a => a.linkId == linkId)
    targetAssets.nonEmpty && !targetAssets.exists(a => a.timeStamp >= timeStamp) && targetAssets.exists(
      a => GeometryUtils.covered((a.startMeasure, a.endMeasure),(mStart,mEnd)))
  }

  private def addSourceRoadLinkToChangeInfo(extensionChangeInfo: ChangeInfo, replacementChangeInfo: ChangeInfo) = {
    def givenAndEqualDoubles(v1: Option[Double], v2: Option[Double]) = {
      (v1, v2) match {
        case (Some(d1), Some(d2)) => d1 == d2
        case _ => false
      }
    }

    // Test if these change infos extend each other. Then take the small little piece just after tolerance value to test if it is true there
    val (mStart, mEnd) = (givenAndEqualDoubles(replacementChangeInfo.newStartMeasure, extensionChangeInfo.newEndMeasure),
      givenAndEqualDoubles(replacementChangeInfo.newEndMeasure, extensionChangeInfo.newStartMeasure)) match {
      case (true, false) =>
        (replacementChangeInfo.oldStartMeasure.get + laneFiller.AllowedTolerance,
          replacementChangeInfo.oldStartMeasure.get + laneFiller.AllowedTolerance + laneFiller.MaxAllowedError)
      case (false, true) =>
        (Math.max(0.0, replacementChangeInfo.oldEndMeasure.get - laneFiller.AllowedTolerance - laneFiller.MaxAllowedError),
          Math.max(0.0, replacementChangeInfo.oldEndMeasure.get - laneFiller.AllowedTolerance))
      case (_, _) => (0.0, 0.0)
    }

    if (mStart != mEnd && extensionChangeInfo.timeStamp == replacementChangeInfo.timeStamp)
      Option(extensionChangeInfo.copy(oldId = replacementChangeInfo.oldId, oldStartMeasure = Option(mStart), oldEndMeasure = Option(mEnd)))
    else
      None
  }



}
