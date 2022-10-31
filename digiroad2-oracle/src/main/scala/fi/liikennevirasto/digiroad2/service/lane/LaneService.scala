package fi.liikennevirasto.digiroad2.service.lane

import fi.liikennevirasto.digiroad2.GeometryUtils.Projection
import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.client.{MassQueryParams, VKMClient}
import fi.liikennevirasto.digiroad2.client.vvh.{ChangeInfo, ChangeType}
import fi.liikennevirasto.digiroad2.dao.lane.{LaneDao, LaneHistoryDao}
import fi.liikennevirasto.digiroad2.dao.MunicipalityDao
import fi.liikennevirasto.digiroad2.lane.LaneFiller._
import fi.liikennevirasto.digiroad2.lane._
import fi.liikennevirasto.digiroad2.linearasset.{LinkId, RoadLink}
import fi.liikennevirasto.digiroad2.postgis.PostGISDatabase
import fi.liikennevirasto.digiroad2.service.{RoadAddressForLink, RoadAddressService, RoadLinkService}
import fi.liikennevirasto.digiroad2.util.ChangeLanesAccordingToVvhChanges.updateChangeSet
import fi.liikennevirasto.digiroad2.util.RoadAddress.isCarTrafficRoadAddress
import fi.liikennevirasto.digiroad2.util.{LaneUtils, LinearAssetUtils, LogUtils, PolygonTools, RoadAddress}
import fi.liikennevirasto.digiroad2.{DigiroadEventBus, GeometryUtils}
import org.joda.time.DateTime
import org.slf4j.LoggerFactory

import java.security.InvalidParameterException


case class LaneChange(lane: PersistedLane, oldLane: Option[PersistedLane], changeType: LaneChangeType, roadLink: Option[RoadLink])

class LaneService(roadLinkServiceImpl: RoadLinkService, eventBusImpl: DigiroadEventBus, roadAddressServiceImpl: RoadAddressService) extends LaneOperations {
  override def roadLinkService: RoadLinkService = roadLinkServiceImpl
  override def dao: LaneDao = new LaneDao()
  override def historyDao: LaneHistoryDao = new LaneHistoryDao()
  override def municipalityDao: MunicipalityDao = new MunicipalityDao
  override def eventBus: DigiroadEventBus = eventBusImpl
  override def polygonTools: PolygonTools = new PolygonTools()
  override def vkmClient: VKMClient = new VKMClient
  override def roadAddressService: RoadAddressService = roadAddressServiceImpl

}

trait LaneOperations {

  def withDynTransaction[T](f: => T): T = PostGISDatabase.withDynTransaction(f)
  def withDynSession[T](f: => T): T = PostGISDatabase.withDynSession(f)
  def roadLinkService: RoadLinkService
  def dao: LaneDao
  def historyDao: LaneHistoryDao
  def municipalityDao: MunicipalityDao
  def eventBus: DigiroadEventBus
  def polygonTools: PolygonTools
  def laneFiller: LaneFiller = new LaneFiller
  def vkmClient: VKMClient
  def roadAddressService: RoadAddressService


  val logger = LoggerFactory.getLogger(getClass)

  case class ActionsPerLanes(lanesToDelete: Set[NewLane] = Set(),
                             lanesToUpdate: Set[NewLane] = Set(),
                             lanesToInsert: Set[NewLane] = Set(),
                             multiLanesOnLink: Set[NewLane] = Set())

  def getByZoomLevel( linkGeomSource: Option[LinkGeomSource] = None) : Seq[Seq[LightLane]] = {
    withDynTransaction {
      val assets = dao.fetchLanes( linkGeomSource)
      Seq(assets)
    }
  }

  /**
    * Returns linear assets for Digiroad2Api /lanes GET endpoint.
    *
    * @param bounds
    * @param municipalities
    * @return
    */
  def getByBoundingBox(bounds: BoundingRectangle, municipalities: Set[Int] = Set(), withWalkingCycling: Boolean = false): (Seq[Seq[PieceWiseLane]], Seq[RoadLink]) = {
    val roadLinks = roadLinkService.getRoadLinksByBoundsAndMunicipalities(bounds, municipalities,asyncMode = false)
    val filteredRoadLinks = if (withWalkingCycling) roadLinks else roadLinks.filter(_.functionalClass != WalkingAndCyclingPath.value)
    val linearAssets = getLanesByRoadLinks(filteredRoadLinks)

    val roadLinksWithoutLanes = filteredRoadLinks.filter { link => !linearAssets.exists(_.linkId == link.linkId) }
    val lanesWithRoadAddress = LogUtils.time(logger, "TEST LOG Get Viite road address for lanes")(roadAddressService.laneWithRoadAddress(linearAssets))
    val lanesWithAddressAndLinkType = lanesWithRoadAddress.map { lane =>
      val linkType = filteredRoadLinks.find(_.linkId == lane.linkId).headOption match {

        case Some(roadLink) => roadLink.linkType.value
        case _ => UnknownLinkType.value
      }
      lane.copy(attributes = lane.attributes + ("linkType" -> linkType))
    }

    val partitionedLanes = LogUtils.time(logger, "TEST LOG Partition lanes")(LanePartitioner.partition(lanesWithAddressAndLinkType, filteredRoadLinks.groupBy(_.linkId).mapValues(_.head)))
    (partitionedLanes, roadLinksWithoutLanes)
  }

  /**
   * Returns lanes for Digiroad2Api /lanes/viewOnlyLanes GET endpoint.
   * This is only to be used for visualization purposes after the getByBoundingBox ran first
   */
  def getViewOnlyByBoundingBox (bounds :BoundingRectangle, municipalities: Set[Int] = Set(), withWalkingCycling: Boolean = false): Seq[ViewOnlyLane] = {
    val roadLinks = roadLinkService.getRoadLinksByBoundsAndMunicipalities(bounds, municipalities,asyncMode = false)
    val filteredRoadLinks = if (withWalkingCycling) roadLinks else roadLinks.filter(_.functionalClass != WalkingAndCyclingPath.value)

    val linkIds = filteredRoadLinks.map(_.linkId)
    val allLanes = fetchExistingLanesByLinkIds(linkIds)

    getSegmentedViewOnlyLanes(allLanes, filteredRoadLinks)
  }


  /**
    * Use lanes measures to create segments with lanes with same link id and side code
    * @param allLanes lanes to be segmented
    * @param roadLinks  roadlinks to relate to a segment piece and use it to construct the segment points
    * @return Segmented view only lanes
    */
  def getSegmentedViewOnlyLanes(allLanes: Seq[PersistedLane], roadLinks: Seq[RoadLink]): Seq[ViewOnlyLane] = {
    //Separate lanes into groups by linkId and side code
    val lanesGroupedByLinkIdAndSideCode = allLanes.groupBy(lane => (lane.linkId, lane.sideCode))

    lanesGroupedByLinkIdAndSideCode.flatMap {
      case ((linkId, sideCode), lanes) =>
        val roadLink = roadLinks.find(_.linkId == linkId).get

        //Find measures for the segments
        val segments = lanes.flatMap(lane => Seq(lane.startMeasure, lane.endMeasure)).distinct

        //Separate each segment in the lanes as a ViewOnlyLane
        segments.sorted.sliding(2).map { measures =>
          val startMeasure = measures.head
          val endMeasure = measures.last
          val consideredLanes = lanes.filter(lane => lane.startMeasure <= startMeasure && lane.endMeasure >= endMeasure).map(_.laneCode)
          val geometry = GeometryUtils.truncateGeometry3D(roadLink.geometry, startMeasure, endMeasure)

          ViewOnlyLane(linkId, startMeasure, endMeasure, sideCode, roadLink.trafficDirection, geometry, consideredLanes, roadLink.linkType.value)
        }
    }.toSeq
  }

   def getLanesByRoadLinks(roadLinks: Seq[RoadLink]): Seq[PieceWiseLane] = {
    val lanes = LogUtils.time(logger, "TEST LOG Fetch lanes from DB")(fetchExistingLanesByLinkIds(roadLinks.map(_.linkId).distinct))
    val lanesMapped = lanes.groupBy(_.linkId)
    val filledTopology = LogUtils.time(logger, "Check for and adjust possible lane adjustments on " + roadLinks.size + " roadLinks")(adjustLanes(roadLinks, lanesMapped))

    filledTopology
  }

  def adjustLanes(roadLinks: Seq[RoadLink], lanes: Map[String, Seq[PersistedLane]]): Seq[PieceWiseLane] = {
    val (filledTopology, adjustmentsChangeSet) = laneFiller.fillTopology(roadLinks, lanes, geometryChanged = false)
    if(adjustmentsChangeSet.isEmpty) {
      laneFiller.toLPieceWiseLaneOnMultipleLinks(filledTopology, roadLinks)
    }
    else {
      withDynTransaction {
        updateChangeSet(adjustmentsChangeSet)
      }
      adjustLanes(roadLinks, filledTopology.groupBy(_.linkId))
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
    val newLinkId = targetRoadLink.linkId
    val laneId = lane.linkId match {
      case targetRoadLink.linkId => lane.id
      case _ => 0
    }
    val typed = ChangeType.apply(change.changeType)

    val (newStart, newEnd, newSideCode) = typed match {
      case ChangeType.LengthenedCommonPart | ChangeType.LengthenedNewPart | ChangeType.ReplacedNewPart =>
        laneFiller.calculateNewMValuesAndSideCode(lane, historyRoadLink, projection, targetRoadLink.length, true)
      case ChangeType.DividedModifiedPart | ChangeType.DividedNewPart if (lane.endMeasure < projection.oldStart ||
        lane.startMeasure > projection.oldEnd) =>
        (0.0, 0.0, 99)
      case _ =>
        laneFiller.calculateNewMValuesAndSideCode(lane, historyRoadLink, projection, targetRoadLink.length)
    }
    val projectedLane = Some(PersistedLane(laneId, newLinkId, newSideCode, lane.laneCode, lane.municipalityCode, newStart, newEnd, lane.createdBy,
      lane.createdDateTime, lane.modifiedBy, lane.modifiedDateTime, lane.expiredBy, lane.expiredDateTime,
      expired = false, projection.timeStamp, lane.geomModifiedDate, lane.attributes))


    val changeSet = laneId match {
      case 0 => changedSet
      case _ if(newSideCode != lane.sideCode) => changedSet.copy(adjustedVVHChanges =  changedSet.adjustedVVHChanges ++
        Seq(VVHChangesAdjustment(laneId, newLinkId, newStart, newEnd, projection.timeStamp)),
        adjustedSideCodes = changedSet.adjustedSideCodes ++ Seq(SideCodeAdjustment(laneId, SideCode.apply(newSideCode))))

      case _ => changedSet.copy(adjustedVVHChanges =  changedSet.adjustedVVHChanges ++
        Seq(VVHChangesAdjustment(laneId, newLinkId, newStart, newEnd, projection.timeStamp)))
    }

    (projectedLane, changeSet)

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

  def fetchExistingMainLanesByRoadLinks( roadLinks: Seq[RoadLink], removedLinkIds: Seq[String], newTransaction: Boolean = true): Seq[PersistedLane] = {
    val linkIds = roadLinks.map(_.linkId)

    if (newTransaction)
      withDynTransaction {
        dao.fetchLanesByLinkIds( linkIds ++ removedLinkIds,mainLanes = true)
      }.filterNot(_.expired)
    else dao.fetchLanesByLinkIds( linkIds ++ removedLinkIds,mainLanes = true).filterNot(_.expired)
  }

  def fetchExistingLanesByLinkIds(linkIds: Seq[String], removedLinkIds: Seq[String] = Seq()): Seq[PersistedLane] = {
    withDynTransaction {
      dao.fetchLanesByLinkIds(linkIds ++ removedLinkIds)
    }.filterNot(_.expired)
  }

  def fetchAllLanesByLinkIds(linkIds: Seq[String], newTransaction: Boolean = true): Seq[PersistedLane] = {
    if (newTransaction)
      withDynTransaction {
        dao.fetchAllLanesByLinkIds(linkIds)
      }
    else dao.fetchLanesByLinkIds(linkIds)
  }

  def fetchExistingLanesByLinksIdAndSideCode(linkId: String, sideCode: Int): Seq[PieceWiseLane] = {
    val roadLink = roadLinkService.getRoadLinkByLinkId(linkId).head

    val existingAssets =
      withDynTransaction {
        dao.fetchLanesByLinkIdAndSideCode(linkId, sideCode)
      }

    existingAssets.map { lane =>
    val geometry = GeometryUtils.truncateGeometry3D(roadLink.geometry, lane.startMeasure, lane.endMeasure)
    val endPoints = GeometryUtils.geometryEndpoints(geometry)

    PieceWiseLane(lane.id, lane.linkId, lane.sideCode, lane.expired, geometry,
      lane.startMeasure, lane.endMeasure,
      Set(endPoints._1, endPoints._2), lane.modifiedBy, lane.modifiedDateTime,
      lane.createdBy, lane.createdDateTime,  roadLink.timeStamp,
      lane.geomModifiedDate, roadLink.administrativeClass, lane.attributes, attributes = Map("municipality" -> lane.municipalityCode, "trafficDirection" -> roadLink.trafficDirection))
    }
  }

  /**
    * Get lanes that were touched between two dates
    * @param sinceDate  start date
    * @param untilDate  end date
    * @param withAutoAdjust with automatic modified lanes
    * @param token  interval of records
    * @return lanes modified between the dates
    */
  def getChanged(sinceDate: DateTime, untilDate: DateTime, withAutoAdjust: Boolean = false, token: Option[String] = None, twoDigitLaneCodesInResult: Boolean = false): Seq[LaneChange] = {
    //Sort changes by id and their created/modified/expired times
    //With this we get a unique ordering with the same values position so the token can be effective
    def customSort(laneChanges: Seq[LaneChange]): Seq[LaneChange] = {
      laneChanges.sortBy{ laneChange =>
        val lane = laneChange.lane
        val defaultTime = DateTime.now()

        (lane.id, lane.createdDateTime.getOrElse(defaultTime).getMillis,
          lane.modifiedDateTime.getOrElse(defaultTime).getMillis, lane.expiredDateTime.getOrElse(defaultTime).getMillis)
      }
    }

    val (upToDateLanes, historyLanes) = withDynTransaction {
      (dao.getLanesChangedSince(sinceDate, untilDate, withAutoAdjust),
        historyDao.getHistoryLanesChangedSince(sinceDate, untilDate, withAutoAdjust))
    }

    val linkIds = (upToDateLanes.map(_.linkId) ++ historyLanes.map(_.linkId)).toSet
    val roadLinks = roadLinkService.getRoadLinksByLinkIds(linkIds)

    // Filter out walking and cycling road links for now
    val roadLinksWithRoadAddressInfo = LaneUtils.roadAddressService.roadLinkWithRoadAddress(roadLinks).filter(roadLink => {
      val roadNumber = roadLink.attributes.get("ROAD_NUMBER").asInstanceOf[Option[Long]]
      roadNumber match {
        case None => false
        case Some(rn) => isCarTrafficRoadAddress(rn)
      }
    })

    val historyLanesWithRoadAddress = historyLanes.filter(lane => roadLinksWithRoadAddressInfo.map(_.linkId).contains(lane.linkId))

    val upToDateLaneChanges = upToDateLanes.flatMap{ upToDate =>
      val roadLink = roadLinksWithRoadAddressInfo.find(_.linkId == upToDate.linkId)
      val relevantHistory = historyLanesWithRoadAddress.find(history => history.newId == upToDate.id)

      relevantHistory match {
        case Some(history) =>
          val uptoDateLastModification = historyLanesWithRoadAddress.filter(historyLane =>
            history.newId == historyLane.oldId && historyLane.newId == 0 &&
              (historyLane.historyCreatedDate.isAfter(history.historyCreatedDate) || historyLane.historyCreatedDate.isEqual(history.historyCreatedDate)))

          if (historyLanesWithRoadAddress.count(historyLane => historyLane.newId != 0 && historyLane.oldId == history.oldId) >= 2)
            Some(LaneChange(upToDate, Some(historyLaneToPersistedLane(history)), LaneChangeType.Divided, roadLink))

          else if (upToDate.endMeasure - upToDate.startMeasure > history.endMeasure - history.startMeasure)
            Some(LaneChange(upToDate, Some(historyLaneToPersistedLane(history)), LaneChangeType.Lengthened, roadLink))

          else if(upToDate.endMeasure - upToDate.startMeasure < history.endMeasure - history.startMeasure)
            Some(LaneChange(upToDate, Some(historyLaneToPersistedLane(history)), LaneChangeType.Shortened, roadLink))

          else
            None

        case None if upToDate.modifiedDateTime.isEmpty =>
          Some(LaneChange(upToDate, None, LaneChangeType.Add, roadLink))

        case _ =>
          val historyRelatedLanes = historyLanesWithRoadAddress.filter(_.oldId == upToDate.id)

          if(historyRelatedLanes.nonEmpty){
            val historyLane = historyLaneToPersistedLane(historyRelatedLanes.maxBy(_.historyCreatedDate.getMillis))

            if (upToDate.laneCode != historyLane.laneCode)
              Some(LaneChange(upToDate, Some(historyLane), LaneChangeType.LaneCodeTransfer, roadLink))

            else if (isSomePropertyDifferent(historyLane, upToDate.attributes))
              Some(LaneChange(upToDate, Some(historyLane), LaneChangeType.AttributesChanged, roadLink))
            else
              None
          }else{
            None
          }
      }
    }

    val expiredLanes = historyLanesWithRoadAddress.filter(lane => lane.expired && lane.newId == 0).map{ history =>
      val roadLink = roadLinksWithRoadAddressInfo.find(_.linkId == history.linkId)
      LaneChange(historyLaneToPersistedLane(history), None, LaneChangeType.Expired, roadLink)
    }

    val historyLaneChanges = historyLanesWithRoadAddress.groupBy(_.oldId).flatMap{ case (_, lanes) =>
      val roadLink = roadLinksWithRoadAddressInfo.find(_.linkId == lanes.head.linkId)

      val lanesSorted = lanes.sortBy(- _.historyCreatedDate.getMillis)
      lanesSorted.foldLeft(Seq.empty[Option[LaneChange]], lanesSorted){ case (foldLeftParameters, lane) =>

        val (treatedLaneChanges, lanesNotTreated) = foldLeftParameters
        val laneAsPersistedLane = historyLaneToPersistedLane(lane)
        val relevantLanes = lanesNotTreated.filterNot(_.id == lane.id)

        val laneChangeReturned = {
          if (relevantLanes.isEmpty) {
            val newIdRelation = historyLanesWithRoadAddress.find(_.newId == laneAsPersistedLane.id)

            newIdRelation match {
              case Some(relation) if historyLanesWithRoadAddress.count(historyLane => historyLane.newId != 0 && historyLane.oldId == relation.oldId) >= 2 =>
                Some(LaneChange(laneAsPersistedLane, Some(historyLaneToPersistedLane(relation)), LaneChangeType.Divided, roadLink))

              case Some(relation) if laneAsPersistedLane.endMeasure - laneAsPersistedLane.startMeasure > relation.endMeasure - relation.startMeasure =>
                Some(LaneChange(laneAsPersistedLane, Some(historyLaneToPersistedLane(relation)), LaneChangeType.Lengthened, roadLink))

              case Some(relation) if laneAsPersistedLane.endMeasure - laneAsPersistedLane.startMeasure < relation.endMeasure - relation.startMeasure =>
                Some(LaneChange(laneAsPersistedLane, Some(historyLaneToPersistedLane(relation)), LaneChangeType.Shortened, roadLink))

              case _ =>
                val wasCreateInTimePeriod = lane.modifiedDateTime.isEmpty &&
                  (lane.createdDateTime.get.isAfter(sinceDate) || lane.createdDateTime.get.isEqual(sinceDate))

                if (wasCreateInTimePeriod)
                  Some(LaneChange(laneAsPersistedLane, None, LaneChangeType.Add, roadLink))
                else
                  None
            }
          } else {
            val relevantLane = historyLaneToPersistedLane(relevantLanes.maxBy(_.historyCreatedDate.getMillis))

            if (isSomePropertyDifferent(relevantLane, laneAsPersistedLane.attributes))
              Some(LaneChange(laneAsPersistedLane, Some(relevantLane), LaneChangeType.AttributesChanged, roadLink))
            else
              None
          }
        }

        (treatedLaneChanges :+ laneChangeReturned, relevantLanes)

      }._1.flatten
    }

    val relevantLanesChanged = (upToDateLaneChanges ++ expiredLanes ++ historyLaneChanges).filter(_.roadLink.isDefined)
    val sortedLanesChanged = customSort(relevantLanesChanged)

    val lanesChangedResult = token match {
      case Some(tk) =>
        val (start, end) = Decode.getPageAndRecordNumber(tk)

        sortedLanesChanged.slice(start - 1, end)
      case _ => sortedLanesChanged
    }
    if(twoDigitLaneCodesInResult){
      laneChangesToTwoDigitLaneCode(lanesChangedResult, roadLinks)
    }
    else lanesChangedResult

  }

  def laneChangesToTwoDigitLaneCode(laneChanges: Seq[LaneChange], roadLinks: Seq[RoadLink]): Seq[LaneChange] = {
    val existingPersistedLanes = laneChanges.map(_.lane)
    val oldPersistedLanes = laneChanges.flatMap(_.oldLane)

    val existingLanesWithRoadAddress = pwLanesToPersistedLanesWithAddress(existingPersistedLanes, roadLinks)
    val oldLanesWithRoadAddress = pwLanesToPersistedLanesWithAddress(oldPersistedLanes, roadLinks)

    val twoDigitExistingPwLanes = pieceWiseLanesToTwoDigitWithMassQuery(existingLanesWithRoadAddress).flatten
    val twoDigitOldPwLanes = pieceWiseLanesToTwoDigitWithMassQuery(oldLanesWithRoadAddress).flatten

    val twoDigitExistingPersistedLanes = pieceWiseLanesToPersistedLane(twoDigitExistingPwLanes)
    val twoDigitOldPersistedLanes = pieceWiseLanesToPersistedLane(twoDigitOldPwLanes)

    laneChanges.flatMap(laneChange => {
      val twoDigitExistingPersistedLane = twoDigitExistingPersistedLanes.find(lane => lane.id == laneChange.lane.id && lane.modifiedDateTime == laneChange.lane.modifiedDateTime)
      val twoDigitOldPersistedLane = laneChange.oldLane match {
        case Some(oldLane) => twoDigitOldPersistedLanes.find(lane => lane.id == oldLane.id && lane.modifiedDateTime == oldLane.modifiedDateTime)
        case _ => None
      }

      twoDigitExistingPersistedLane match {
        case Some(twoDigitExistingLane) =>
          twoDigitOldPersistedLane match {
            case Some(twoDigitOldLane) =>
              Some(laneChange.copy(lane = twoDigitExistingLane, oldLane = Some(twoDigitOldLane)))
            case _ => Some(laneChange.copy(lane = twoDigitExistingLane))
          }
        case _ => None
      }
    })

  }

  def pwLanesToPersistedLanesWithAddress(lanes: Seq[PersistedLane], roadLinks: Seq[RoadLink]): Seq[PieceWiseLane] = {
    val lanesWithRoadLinks = roadLinks.map(roadLink => (lanes.filter(_.linkId == roadLink.linkId), roadLink))
    val pwLanes = lanesWithRoadLinks.flatMap(pair => laneFiller.toLPieceWiseLane(pair._1, pair._2))

    val lanesWithRoadAddressInfo = LogUtils.time(logger, "TEST LOG Get Viite road address for lanes")(roadAddressService.laneWithRoadAddress(pwLanes))
    lanesWithRoadAddressInfo
  }

  def pieceWiseLanesToPersistedLane(pwLanes: Seq[PieceWiseLane]): Seq[PersistedLane] = {
    pwLanes.map { pwLane =>
      val municipalityCode = pwLane.attributes.getOrElse("municipality", 99).asInstanceOf[Long]
      val laneCode = getLaneCode(pwLane)
      PersistedLane(pwLane.id, pwLane.linkId, pwLane.sideCode, laneCode, municipalityCode, pwLane.startMeasure, pwLane.endMeasure,
        pwLane.createdBy, pwLane.createdDateTime, pwLane.modifiedBy, pwLane.modifiedDateTime, None, None, false, pwLane.timeStamp,
        pwLane.geomModifiedDate, pwLane.laneAttributes)
    }
  }

  def historyLaneToPersistedLane(historyLane: PersistedHistoryLane): PersistedLane = {
    PersistedLane(historyLane.oldId, historyLane.linkId, historyLane.sideCode, historyLane.laneCode,
                  historyLane.municipalityCode, historyLane.startMeasure, historyLane.endMeasure, historyLane.createdBy,
                  historyLane.createdDateTime, historyLane.modifiedBy, historyLane.modifiedDateTime,
                  Some(historyLane.historyCreatedBy), Some(historyLane.historyCreatedDate),
                  historyLane.expired, historyLane.timeStamp, historyLane.geomModifiedDate, historyLane.attributes)
  }

  def fixSideCode(roadAddress: RoadAddressForLink, laneCode: String ): SideCode = {
    // Need to pay attention of the SideCode and laneCode. This will have influence in representation of the lane
    roadAddress.sideCode match {
      case SideCode.AgainstDigitizing =>
        if (laneCode.startsWith("1")) SideCode.AgainstDigitizing else SideCode.TowardsDigitizing
      case SideCode.TowardsDigitizing =>
        if (laneCode.startsWith("1")) SideCode.TowardsDigitizing else SideCode.AgainstDigitizing
      case _ => SideCode.BothDirections
    }
  }

  /**
    * Returns lanes ids. Used by Digiroad2Api /lane POST and /lane DELETE endpoints.
    */
  def getPersistedLanesByIds( ids: Set[Long], newTransaction: Boolean = true): Seq[PersistedLane] = {
    if(newTransaction)
      withDynTransaction {
        dao.fetchLanesByIds( ids )
      }
    else
      dao.fetchLanesByIds( ids )
  }

  def getPropertyValue(newLaneProperties: Seq[LaneProperty], publicId: String) = {
    val laneProperty = newLaneProperties.find(_.publicId == publicId)

    laneProperty match {
      case Some(prop) if prop.values.nonEmpty =>
        prop.values.head.value
      case _ => None
    }
  }

  def getPropertyValue(pwLane: PieceWiseLane, publicIdToSearch: String): Option[LanePropertyValue] = {
    pwLane.laneAttributes.find(_.publicId == publicIdToSearch).get.values.headOption
  }

  def getPropertyValue(pLane: PersistedLane, publicIdToSearch: String): Option[LanePropertyValue] = {
    pLane.attributes.find(_.publicId == publicIdToSearch).get.values.headOption
  }

  def getLaneCode (newLane: NewLane): String = {
    val laneCodeValue = getPropertyValue(newLane.properties, "lane_code")

    if (laneCodeValue != None && laneCodeValue.toString.trim.nonEmpty)
      laneCodeValue.toString.trim
    else
      throw new IllegalArgumentException("Lane code attribute not found!")
  }

  def getLaneCode (pwLane: PieceWiseLane): Int = {
    val laneCodeValue = getPropertyValue(pwLane.laneAttributes, "lane_code")

    if (laneCodeValue != None && laneCodeValue.toString.trim.nonEmpty)
      laneCodeValue.asInstanceOf[Int]
    else
      throw new IllegalArgumentException("Lane code attribute not found!")
  }

  def validateStartDateOneDigit(newLane: NewLane, laneCode: Int): Unit = {
    if (!LaneNumberOneDigit.isMainLane(laneCode)) {
      val startDateValue = getPropertyValue(newLane.properties, "start_date")
      if (startDateValue == None || startDateValue.toString.trim.isEmpty)
        throw new IllegalArgumentException("Start Date attribute not found on additional lane!")
    }
  }

  def validateStartDate(newLane: NewLane, laneCode: Int): Unit = {
    if (!LaneNumber.isMainLane(laneCode)) {
      val startDateValue = getPropertyValue(newLane.properties, "start_date")
      if (startDateValue == None || startDateValue.toString.trim.isEmpty)
        throw new IllegalArgumentException("Start Date attribute not found on additional lane!")
    }
  }

  def isSomePropertyDifferent(oldLane: PersistedLane, newLaneProperties: Seq[LaneProperty]): Boolean = {
    oldLane.attributes.length != newLaneProperties.length ||
      oldLane.attributes.exists { property =>
        val oldPropertyValue = if (property.values.nonEmpty) {property.values.head.value} else None
        val newPropertyValue = getPropertyValue(newLaneProperties, property.publicId)

        oldPropertyValue != newPropertyValue
      }
  }

  /**
    * @param updateNewLane The lanes that will be updated
    * @param linkIds  If only 1 means the user are change a specific lane and it can be cut or properties update
    *                 If multiple means the chain is being updated and only properties are change ( Lane size / cut cannot be done)
    * @param sideCode To know the direction
    * @param username Username of the user is doing the changes
    * @return
    */
  def update(updateNewLane: Seq[NewLane], linkIds: Set[String], sideCode: Int, username: String,
             sideCodesForLinks: Seq[SideCodesForLinkIds], allExistingLanes: Seq[PersistedLane]): Seq[Long] = {
    if (updateNewLane.isEmpty || linkIds.isEmpty)
      return Seq()

    updateNewLane.flatMap { laneToUpdate =>
      val originalSelectedLane = allExistingLanes.find(_.id == laneToUpdate.id)
      val laneToUpdateOriginalLaneCode = getLaneCode(laneToUpdate).toInt

      linkIds.map{ linkId =>
        val sideCodeForLink = sideCodesForLinks.find(_.linkId == linkId)
          .getOrElse(throw new InvalidParameterException("Side Code not found for link ID: " + linkId))
          .sideCode
        val laneRelatedByLaneCode = allExistingLanes.find(laneAux => laneAux.laneCode == laneToUpdateOriginalLaneCode && laneAux.linkId == linkId && laneAux.sideCode == sideCodeForLink)
          .getOrElse(throw new InvalidParameterException(s"LinkId: $linkId dont have laneCode: $laneToUpdateOriginalLaneCode for update!"))

        originalSelectedLane match {
          case Some(lane) =>
            //oldLane is the lane related with the laneToUpdate by Id, when various links we need to find this lane Id and lane code
            val oldLane = allExistingLanes.find(laneAux => laneAux.laneCode == lane.laneCode && laneAux.linkId == linkId && laneAux.sideCode == sideCodeForLink)
              .getOrElse(throw new InvalidParameterException(s"LinkId: $linkId dont have laneCode: ${lane.laneCode} for update!"))

            val isExactlyMatchingSingleLinkLane = (lane: PersistedLane) => linkIds.size == 1 &&
              lane.startMeasure == laneToUpdate.startMeasure && lane.startMeasure == laneToUpdate.startMeasure && lane.laneCode == laneToUpdateOriginalLaneCode

            if (isExactlyMatchingSingleLinkLane(lane)) {
              var newLaneID = lane.id
              if (isSomePropertyDifferent(lane, laneToUpdate.properties) || laneToUpdate.newLaneCode.nonEmpty) {
                val laneCode = laneToUpdate.newLaneCode match {
                  case Some(newLaneCode) => newLaneCode
                  case None => laneToUpdateOriginalLaneCode
                }
                val persistedLaneToUpdate = PersistedLane(lane.id, linkId, sideCode, laneCode, lane.municipalityCode,
                  lane.startMeasure, lane.endMeasure, Some(username), None, None, None, None, None, false, 0, None, laneToUpdate.properties)
                moveToHistory(lane.id, None, false, false, username)
                newLaneID = dao.updateEntryLane(persistedLaneToUpdate, username)
              }
              newLaneID
            } else if (linkIds.size == 1 &&
              (oldLane.startMeasure != laneToUpdate.startMeasure || oldLane.endMeasure != laneToUpdate.endMeasure)) {
              val newLaneID = create(Seq(laneToUpdate), Set(linkId), sideCode, username)
              moveToHistory(oldLane.id, Some(newLaneID.head), true, true, username)
              newLaneID.head
            } else if (oldLane.laneCode != laneToUpdateOriginalLaneCode || isSomePropertyDifferent(oldLane, laneToUpdate.properties) || laneToUpdate.newLaneCode.nonEmpty) {
              //Something changed on properties or lane code
              val laneCode = laneToUpdate.newLaneCode match {
                case Some(newLaneCode) => newLaneCode
                case None => laneToUpdateOriginalLaneCode
              }
              val persistedLaneToUpdate = PersistedLane(oldLane.id, linkId, sideCodeForLink, laneCode, oldLane.municipalityCode,
                oldLane.startMeasure, oldLane.endMeasure, Some(username), None, None, None, None, None, false, 0, None, laneToUpdate.properties)

              moveToHistory(oldLane.id, None, false, false, username)
              dao.updateEntryLane(persistedLaneToUpdate, username)

            } else {
              oldLane.id
            }
          case _ if laneToUpdate.id == 0 =>
            //User deleted and created another lane in same lane code
            //so it will be a update, if have some modification, and not a expire and then create

            if (linkIds.size == 1 &&
              (laneRelatedByLaneCode.startMeasure != laneToUpdate.startMeasure || laneRelatedByLaneCode.endMeasure != laneToUpdate.endMeasure)) {
              val newLaneID = create(Seq(laneToUpdate), Set(linkId), sideCode, username)
              moveToHistory(laneRelatedByLaneCode.id, Some(newLaneID.head), true, true, username)
              newLaneID.head
            } else if(isSomePropertyDifferent(laneRelatedByLaneCode, laneToUpdate.properties)){
              val persistedLaneToUpdate = PersistedLane(laneRelatedByLaneCode.id, linkId, sideCodeForLink, laneToUpdateOriginalLaneCode, laneToUpdate.municipalityCode,
                laneToUpdate.startMeasure, laneToUpdate.endMeasure, Some(username), None, None, None, None, None, false, 0, None, laneToUpdate.properties)

              moveToHistory(laneRelatedByLaneCode.id, None, false, false, username)
              dao.updateEntryLane(persistedLaneToUpdate, username)
            } else {
              laneRelatedByLaneCode.id
            }
          case _ => throw new InvalidParameterException(s"Error: Could not find lane with lane Id ${laneToUpdate.id}!")
        }
      }
    }
  }

  def createWithoutTransaction(newLane: PersistedLane, username: String): Long = {
    val laneId = dao.createLane( newLane, username )

    newLane.attributes match {
      case props: Seq[LaneProperty] =>
        props.filterNot( _.publicId == "lane_code" )
             .map( attr => dao.insertLaneAttributes(laneId, attr, username) )

      case _ => None
    }

    laneId
  }

  def createMultipleLanes(newLanes: Seq[PersistedLane], username: String, newTransaction: Boolean = false): Seq[PersistedLane]  = {
    def createLanes(): Seq[PersistedLane] = {
      val createdLanes = dao.createMultipleLanes(newLanes, username)
      dao.insertLaneAttributesForMultipleLanes(createdLanes, username)
      createdLanes
    }

    if (newTransaction) withDynTransaction( createLanes() ) else createLanes()
  }

  def updateMultipleLaneAttributes(lanes: Seq[PersistedLane], username: String, newTransaction: Boolean = false): Unit = {
    if (newTransaction){
      withDynTransaction(dao.updateLaneAttributesForMultipleLanes(lanes, username))
    }
    else dao.updateLaneAttributesForMultipleLanes(lanes, username)
  }


  def validateMinDistance(measure1: Double, measure2: Double): Boolean = {
    val minDistanceAllow = 0.01
    val (maxMeasure, minMeasure) = (math.max(measure1, measure2), math.min(measure1, measure2))
    (maxMeasure - minMeasure) > minDistanceAllow
  }

  /**
    * Saves new linear assets from UI. Used by Digiroad2Api /linearassets POST endpoint.
    */
  def create(newLanes: Seq[NewLane], linkIds: Set[String], sideCode: Int, username: String, sideCodesForLinkIds: Seq[SideCodesForLinkIds] = Seq(), timeStamp: Long = LinearAssetUtils.createTimeStamp()): Seq[Long] = {

    if (newLanes.isEmpty || linkIds.isEmpty)
      return Seq()

      // if it is only 1 link it can be just a new lane with same size as the link or it can be a cut
      // for that reason we need to use the measure that come inside the newLane
      if (linkIds.size == 1) {

        val linkId = linkIds.head

        newLanes.map { newLane =>
          val laneCode = getLaneCode(newLane)
          validateStartDateOneDigit(newLane, laneCode.toInt)

          val laneToInsert = PersistedLane(0, linkId, sideCode, laneCode.toInt, newLane.municipalityCode,
                                      newLane.startMeasure, newLane.endMeasure, Some(username), Some(DateTime.now()), None, None, None, None,
                                      expired = false, timeStamp, None, newLane.properties)

          createWithoutTransaction(laneToInsert, username)
        }

      } else {
        // If we have more than 1 linkId than we have a chain selected
        // Lanes will be created with the size of the link
        val groupedRoadLinks = roadLinkService.getRoadLinksByLinkIds(linkIds, false)
                                                  .groupBy(_.linkId)

        val result = linkIds.map { linkId =>

          newLanes.map { newLane =>

            val laneCode = getLaneCode(newLane)
            validateStartDateOneDigit(newLane, laneCode.toInt)

            val roadLink = if (groupedRoadLinks(linkId).nonEmpty)
                            groupedRoadLinks(linkId).head
                           else
                            throw new InvalidParameterException(s"No RoadLink found: $linkId")

            val endMeasure = Math.round(roadLink.length * 1000).toDouble / 1000

            val laneToInsert = sideCodesForLinkIds.isEmpty match{
              case true => PersistedLane(0, linkId, sideCode, laneCode.toInt, newLane.municipalityCode,
                0,endMeasure, Some(username), Some(DateTime.now()), None, None, None, None,
                expired = false, timeStamp, None, newLane.properties)

              case false =>
                val correctSideCode = sideCodesForLinkIds.find(_.linkId == linkId)
                correctSideCode match {
                  case Some(_) => PersistedLane(0, linkId, correctSideCode.get.sideCode, laneCode.toInt, newLane.municipalityCode,
                    0,endMeasure, Some(username), Some(DateTime.now()), None, None, None, None,
                    expired = false, timeStamp, None, newLane.properties)

                  case None => PersistedLane(0, linkId, sideCode, laneCode.toInt, newLane.municipalityCode,
                    0,endMeasure, Some(username), Some(DateTime.now()), None, None, None, None,
                    expired = false, timeStamp, None, newLane.properties)
                }
            }



            createWithoutTransaction(laneToInsert, username)
          }
        }

        result.head
      }
  }

  /**
   * Determine links with correct sideCode by selected lane's id
   * @param selectedLane    Lane we use to determine side code to other links
   * @param linkIds         Set of continuing link ids
   * @param newTransaction  Boolean
   * @return                Map of link ids with correct side code
   */
  def getLinksWithCorrectSideCodes(selectedLane: PersistedLane, linkIds: Set[String],
                                   newTransaction: Boolean = true): Map[String, SideCode] = {
    val roadLinks = roadLinkService.getRoadLinksByLinkIds(linkIds, newTransaction)

    // Determine side codes with existing main lanes
    val lanes = fetchExistingMainLanesByRoadLinks(roadLinks, Seq(), newTransaction)
    val pieceWiseLanes = lanes.flatMap(lane => {
      val link = roadLinks.filter(_.linkId == lane.linkId).head
      laneFiller.toLPieceWiseLane(Seq(lane), link)
    })

    val lanesOfInterest = pieceWiseLanes.filter(pieceWise => {
      val link = roadLinks.filter(_.linkId == pieceWise.linkId).head
      link.trafficDirection match {
        case TrafficDirection.BothDirections =>
          if (pieceWise.sideCode == selectedLane.sideCode) true
          else false
        case _ => true
      }
    })
    val lanesWithContinuing = lanesOfInterest.map(lane => {
      // Add to list if lanes have same endpoint and that tested lane is not the lane we are comparing to
      val continuingFromLane = lanesOfInterest.filter(continuing =>
        continuing.endpoints.map(_.round()).exists(lane.endpoints.map(_.round()).contains) &&
          continuing.id != lane.id
      )
      LanePartitioner.LaneWithContinuingLanes(lane, continuingFromLane)
    })

    val connectedGroups = LanePartitioner.getConnectedGroups(lanesWithContinuing)
    val connectedLanes = connectedGroups.find(lanes => lanes.exists(_.id == selectedLane.id)).getOrElse(Seq())
    val selectedContinuingLanes = lanesWithContinuing.filter(laneWithContinuing =>
      connectedLanes.exists(pieceWise => pieceWise.id == laneWithContinuing.lane.id))

    val lanesWithAdjustedSideCode = LanePartitioner.handleLanes(selectedContinuingLanes, pieceWiseLanes)
    val selectedWithinAdjusted = lanesWithAdjustedSideCode.find(_.linkId == selectedLane.linkId)
    val changeSideCode = selectedWithinAdjusted.isDefined && selectedWithinAdjusted.get.sideCode != selectedLane.sideCode

    lanesWithAdjustedSideCode.map(lane => {
      val sideCode = if (changeSideCode) SideCode.switch(SideCode.apply(lane.sideCode))
                     else SideCode.apply(lane.sideCode)
      (lane.linkId, sideCode)
    }).toMap
  }

  def updatePersistedLaneAttributes( id: Long, attributes: Seq[LaneProperty], username: String): Unit = {
    attributes.foreach{ prop =>
      if (prop.values.isEmpty) dao.deleteLaneAttribute(id, prop)
      else dao.updateLaneAttributes(id, prop, username)
    }
  }

  /**
    * Delete lanes with specified laneIds
    * @param laneIds  lanes codes to delete
    * @param username username of the one who expired the lanes
    */
  def deleteMultipleLanes(laneIds: Set[Long], username: String): Seq[Long] = {
    if (laneIds.nonEmpty) {
      laneIds.map(moveToHistory(_, None, true, true, username)).toSeq
    } else
      Seq()
  }

  def moveToHistory(oldId: Long, newId: Option[Long], expireHistoryLane: Boolean = false, deleteFromLanes: Boolean = false,
                    username: String): Long = {
    val historyLaneId = historyDao.insertHistoryLane(oldId, newId, username)

    if (expireHistoryLane)
      historyDao.expireHistoryLane(historyLaneId, username)

    if (deleteFromLanes)
      dao.deleteEntryLane(oldId)

    historyLaneId
  }

  def processNewLanes(newLanes: Set[NewLane], linkIds: Set[String],
                      sideCode: Int, username: String, sideCodesForLinks: Seq[SideCodesForLinkIds]): Seq[Long] = {
    withDynTransaction {
      val allExistingLanes = dao.fetchLanesByLinkIdsAndLaneCode(linkIds.toSeq)
      val actionsLanes = separateNewLanesInActions(newLanes, linkIds, sideCode, allExistingLanes, sideCodesForLinks)
      val laneIdsToBeDeleted = actionsLanes.lanesToDelete.map(_.id)

      create(actionsLanes.lanesToInsert.toSeq, linkIds, sideCode, username, sideCodesForLinks) ++
      update(actionsLanes.lanesToUpdate.toSeq, linkIds, sideCode, username, sideCodesForLinks, allExistingLanes) ++
      deleteMultipleLanes(laneIdsToBeDeleted, username) ++
      createMultiLanesOnLink(actionsLanes.multiLanesOnLink.toSeq, linkIds, sideCode, username)
    }
  }

  def processLanesByRoadAddress(newLanes: Set[NewLane], laneRoadAddressInfo: LaneRoadAddressInfo,
                                username: String): Set[Long] = {
    withDynTransaction {
      val linksWithAddresses = LaneUtils.getRoadAddressToProcess(laneRoadAddressInfo)
      //Get only the lanes to create
      val lanesToInsert = newLanes.filter(_.id == 0)
      val clickedMainLane = newLanes.filter(_.id != 0).head
      val selectedLane = getPersistedLanesByIds(Set(clickedMainLane.id), newTransaction = false).head

      val roadLinkIds = linksWithAddresses.map(_.linkId)
      val linksWithSideCodes = getLinksWithCorrectSideCodes(selectedLane, roadLinkIds, newTransaction = false)
      val existingLanes = fetchAllLanesByLinkIds(roadLinkIds.toSeq, newTransaction = false)

      val allLanesToCreate = linksWithAddresses.flatMap { link =>
        val timeStamp = LinearAssetUtils.createTimeStamp()

        lanesToInsert.flatMap { lane =>
          val laneCode = getLaneCode(lane).toInt
          validateStartDate(lane, laneCode)
          val fixedSideCode = linksWithSideCodes.get(link.linkId)
          val startAndEndPoints = LaneUtils.calculateStartAndEndPoint(laneRoadAddressInfo, link.addresses, link.link.length)

          (startAndEndPoints, fixedSideCode) match {
            case (Some(endPoints), Some(sideCode: SideCode)) =>
              val lanesExists = existingLanes.filter(pLane =>
                pLane.linkId == link.linkId && pLane.sideCode == sideCode.value && pLane.laneCode == laneCode &&
                ((endPoints.start >= pLane.startMeasure && endPoints.start < pLane.endMeasure) ||
                  (endPoints.end > pLane.startMeasure && endPoints.end <= pLane.endMeasure))
              )
              if (lanesExists.nonEmpty)
                throw new InvalidParameterException(s"Lane with given lane code already exists in the selection")

              Some(PersistedLane(0, link.linkId, sideCode.value, laneCode, link.link.municipalityCode,
                endPoints.start, endPoints.end, Some(username), Some(DateTime.now()), None, None, None, None, expired = false,
                timeStamp, None, lane.properties))
            case (Some(_), None) =>
              // Throw error if sideCode is not determined due to missing road addresses
              throw new InvalidParameterException(s"All links in selection do not have road address")
            case _ => None
          }
        }
      }

      // Create lanes
      allLanesToCreate.map(createWithoutTransaction(_, username))
    }
  }

  def separateNewLanesInActions(newLanes: Set[NewLane], linkIds: Set[String], sideCode: Int,
                                allExistingLanes: Seq[PersistedLane], sideCodesForLinkIds: Seq[SideCodesForLinkIds]): ActionsPerLanes = {

    val lanesOnLinksBySideCodes = sideCodesForLinkIds.flatMap(sideCodeForLink => {
      allExistingLanes.filter(lane => lane.linkId == sideCodeForLink.linkId && lane.sideCode == sideCodeForLink.sideCode)
    })

    //Get Lanes to be deleted
    val resultWithDeleteActions = newLanes.foldLeft(ActionsPerLanes()) {
      (result, existingLane) =>
        if (existingLane.isExpired)
          result.copy(lanesToDelete = Set(existingLane) ++ result.lanesToDelete)
        else
          result
    }

    //Get multiple lanes in one link
    val resultWithMultiLanesInLink = newLanes.filter(_.id == 0).foldLeft(resultWithDeleteActions) {
      (result, newLane) =>
        val newLaneCode: Int = getPropertyValue(newLane.properties, "lane_code").toString.toInt

        val numberOfOldLanesByCode = lanesOnLinksBySideCodes.count(_.laneCode == newLaneCode)
        val numberOfFutureLanesByCode = newLanes.filter(_.isExpired != true).count { newLane => getLaneCode(newLane).toInt == newLaneCode }

        if ((numberOfFutureLanesByCode >= 2 && numberOfOldLanesByCode >= 1) || (numberOfFutureLanesByCode < numberOfOldLanesByCode))
          result.copy(multiLanesOnLink = Set(newLane) ++ result.multiLanesOnLink)
        else
          result
    }

    //Get Lanes to be created and updated
    val resultWithAllActions =
      newLanes.filter(_.isExpired != true).diff(resultWithMultiLanesInLink.multiLanesOnLink)
        .foldLeft(resultWithMultiLanesInLink) {
          (result, lane) =>
            val laneCode = getPropertyValue(lane.properties, "lane_code")

            // If new Lane already exist at data base will be marked to be updated
            if (lanesOnLinksBySideCodes.exists(_.laneCode == laneCode)) {
              result.copy(lanesToUpdate = Set(lane) ++ result.lanesToUpdate)
            } else {
              // If new Lane doesnt exist at data base will be marked to be created
              result.copy(lanesToInsert = Set(lane) ++ result.lanesToInsert)
            }
        }

    resultWithAllActions
  }

  def createMultiLanesOnLink(updateNewLanes: Seq[NewLane], linkIds: Set[String], sideCode: Int, username: String): Seq[Long] = {
    val laneCodesToModify = updateNewLanes.map { newLane => getLaneCode(newLane).toInt }
    val oldLanes = dao.fetchLanesByLinkIdsAndLaneCode(linkIds.toSeq, laneCodesToModify).filter(lane => lane.sideCode == sideCode)
    val newLanesByLaneCode = updateNewLanes.groupBy(il => getLaneCode(il).toInt)

    //By lane check if exist something to modify
    newLanesByLaneCode.flatMap { case (laneCode, lanesToUpdate) =>
      val oldLanesByCode = oldLanes.filter(_.laneCode == laneCode)

      //When one or more lanes are cut to smaller pieces
      val newLanesIDs = lanesToUpdate.map { lane =>
        create(Seq(lane), linkIds, sideCode, username).head
      }

      val createdNewLanes = dao.fetchLanesByIds(newLanesIDs.toSet)

      //Expire only those old lanes that have been replaced by new lane pieces.
      oldLanesByCode.foreach { oldLane =>
        val newLanesWithinRange = newLanesWithinOldLaneRange(createdNewLanes, oldLane)
        if (!newLanesWithinRange.isEmpty) {
          createExpiredPartsOfOldLane(newLanesWithinRange, oldLane, sideCode, linkIds, username)
          newLanesWithinRange.foreach { newLane =>
            moveToHistory(oldLane.id, Some(newLane.id), true, false, username)
          }
          dao.deleteEntryLane(oldLane.id)
        }
      }

      newLanesIDs
    }.toSeq
  }

  def newLanesWithinOldLaneRange(newLanes: Seq[PersistedLane], oldLane: PersistedLane) = {
    newLanes.filter(newLane => newLane.startMeasure >= oldLane.startMeasure && newLane.endMeasure <= oldLane.endMeasure)
  }

  /*Creates an explicit expired lane piece for those parts of the split line that have been deleted before saving.
  Needed for change messages, so that the expiration change will be mapped to the expired lane part in ChangeAPI.*/
  def createExpiredPartsOfOldLane(newLanes: Seq[PersistedLane], oldLane: PersistedLane, sideCode: Int, linkIds: Set[String], username: String): Unit = {
    val sortedNewLanes = newLanes.sortBy(_.startMeasure)
    var start = oldLane.startMeasure
    sortedNewLanes.foreach { newLane =>
      if (start < newLane.startMeasure) {
        val replacementLaneId = create(Seq(NewLane(0, start, newLane.startMeasure, oldLane.municipalityCode, true, true,
          oldLane.attributes, Some(sideCode))), linkIds, sideCode, username).head
        moveToHistory(oldLane.id, Some(replacementLaneId), true, false, username)
        moveToHistory(replacementLaneId, None, true, true, username)
        start = newLane.startMeasure
      } else {
        start = newLane.endMeasure
      }

    }
    val lastNewLane = sortedNewLanes.last
    if (lastNewLane.endMeasure < oldLane.endMeasure) {
      val replacementLaneId = create(Seq(NewLane(0, lastNewLane.endMeasure, oldLane.endMeasure, oldLane.municipalityCode, true, true,
        oldLane.attributes, Some(sideCode))), linkIds, sideCode, username).head
      moveToHistory(oldLane.id, Some(replacementLaneId), true, false, username)
      moveToHistory(replacementLaneId, None, true, true, username)
    }
  }

  def expireAllAdditionalLanes(username: String): Unit = {
    dao.expireAdditionalLanes(username)
  }

  // Used by initial main lane population process
  def expireAllMunicipalityLanes(municipality: Int, username: String): Unit = {
    val laneIds = dao.fetchLanesByMunicipality(municipality).map(_.id)
    if (laneIds.nonEmpty) {
      val lanesWithHistoryId = historyDao.insertHistoryLanes(laneIds, username)

      historyDao.expireHistoryLanes(lanesWithHistoryId, username)
      dao.deleteEntryLanes(laneIds)
    }
  }

  //Deletes all lane info, only to be used in MainLanePopulation initial process
  def deleteAllPreviousLaneData(): Unit = {
    dao.truncateLaneTables()
  }

  def persistedLaneToTwoDigitLaneCode(lane: PersistedLane, newTransaction: Boolean = true): Option[PersistedLane] = {
    val roadLink = roadLinkService.getRoadLinksByLinkIds(Set(lane.linkId), newTransaction).head
    val pwLane = laneFiller.toLPieceWiseLane(Seq(lane), roadLink).head
    val newLaneCode = getTwoDigitLaneCode(roadLink, pwLane)
    newLaneCode match {
      case Some(_) => Option(lane.copy(laneCode = newLaneCode.get))
      case None => None
    }
  }

  def pieceWiseLanesToTwoDigitWithMassQuery(pwLanes: Seq[PieceWiseLane]): Seq[Option[PieceWiseLane]] = {
    val vkmParameters =
      pwLanes.map(lane => {
        MassQueryParams(lane.id.toString + "/starting", lane.endpoints.minBy(_.y), lane.attributes.get("ROAD_NUMBER").asInstanceOf[Option[Long]],
          lane.attributes.get("ROAD_PART_NUMBER").asInstanceOf[Option[Long]])
      }) ++ pwLanes.map(lane => {
        MassQueryParams(lane.id.toString + "/ending", lane.endpoints.maxBy(_.y), lane.attributes.get("ROAD_NUMBER").asInstanceOf[Option[Long]],
          lane.attributes.get("ROAD_PART_NUMBER").asInstanceOf[Option[Long]])
      })

    val vkmParametersSplit = vkmParameters.grouped(1000).toSeq
    val roadAddressesSplit = vkmParametersSplit.map(parameterGroup => {
      LogUtils.time(logger, "VKM transformation for " + parameterGroup.size + " coordinates") {
        vkmClient.coordToAddressMassQuery(parameterGroup)}
    })
    val roadAddresses = roadAddressesSplit.foldLeft(Map.empty[String, RoadAddress])(_ ++ _)

    pwLanes.map(lane => {
      val startingAddress = roadAddresses.get(lane.id.toString + "/starting")
      val endingAddress = roadAddresses.get(lane.id.toString + "/ending")

      (startingAddress, endingAddress) match {
        case (Some(_), Some(_)) =>
          val startingPointM = startingAddress.get.addrM
          val endingPointM = endingAddress.get.addrM
          val firstDigit = lane.sideCode match {
            case 1 => Option(3)
            case 2 if startingPointM > endingPointM => Option(2)
            case 2 if startingPointM < endingPointM => Option(1)
            case 3 if startingPointM > endingPointM => Option(1)
            case 3 if startingPointM < endingPointM => Option(2)
            case _ if startingPointM == endingPointM =>
              logger.error("VKM returned same addresses for both endpoints on lane: " + lane.id)
              None
          }
          if (firstDigit.isEmpty) None
          else {
            val oldLaneCode = lane.laneAttributes.find(_.publicId == "lane_code").get.values.head.value.toString
            val newLaneCode = firstDigit.get.toString.concat(oldLaneCode).toInt
            val newLaneCodeProperty = LaneProperty("lane_code", Seq(LanePropertyValue(newLaneCode)))
            val newLaneAttributes = lane.laneAttributes.filterNot(_.publicId == "lane_code") ++ Seq(newLaneCodeProperty)
            Option(lane.copy(laneAttributes = newLaneAttributes))
          }

        case _ =>
          logger.error("VKM didnt find address for one or two endpoints for lane: " + lane.id)
          None

      }
    })
  }

  def getTwoDigitLaneCode(roadLink: RoadLink, pwLane: PieceWiseLane ): Option[Int] = {
    val roadNumber = roadLink.attributes.get("ROADNUMBER").asInstanceOf[Option[Int]]
    val roadPartNumber = roadLink.attributes.get("ROADPARTNUMBER").asInstanceOf[Option[Int]]

    roadNumber match {
      case Some(_) =>
        val startingPoint = pwLane.endpoints.minBy(_.y)
        val endingPoint = pwLane.endpoints.maxBy(_.y)
        val startingPointAddress = vkmClient.coordToAddress(startingPoint, roadNumber, roadPartNumber)
        val endingPointAddress = vkmClient.coordToAddress(endingPoint, roadNumber, roadPartNumber)

        val startingPointM = startingPointAddress.addrM
        val endingPointM = endingPointAddress.addrM

        val firstDigit = pwLane.sideCode match {
          case 1 => Option(3)
          case 2 if startingPointM > endingPointM => Option(2)
          case 2 if startingPointM < endingPointM => Option(1)
          case 3 if startingPointM > endingPointM => Option(1)
          case 3 if startingPointM < endingPointM => Option(2)
          case _ if startingPointM == endingPointM =>
            logger.error("VKM returned same addresses for both endpoints on lane: " + pwLane.id)
            None
        }
        if (firstDigit.isEmpty) None
        else{
          val oldLaneCode = pwLane.laneAttributes.find(_.publicId == "lane_code").get.values.head.value.toString
          val newLaneCode = firstDigit.get.toString.concat(oldLaneCode).toInt
          Option(newLaneCode)
        }
      case None => None
    }
  }
}