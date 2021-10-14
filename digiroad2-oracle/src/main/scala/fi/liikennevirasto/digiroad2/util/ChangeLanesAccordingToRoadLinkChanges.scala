package fi.liikennevirasto.digiroad2.util

import fi.liikennevirasto.digiroad2.client.vvh.{ChangeInfo, VVHClient}
import fi.liikennevirasto.digiroad2.lane.LaneFiller.ChangeSet
import fi.liikennevirasto.digiroad2.lane.{PersistedLane, PieceWiseLane}
import fi.liikennevirasto.digiroad2.linearasset.RoadLink
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.service.lane.LaneService
import fi.liikennevirasto.digiroad2.util.LaneUtils.laneService.eventBus
import fi.liikennevirasto.digiroad2.{DigiroadEventBus, DummyEventBus, DummySerializer}
import org.joda.time.DateTime
import org.slf4j.LoggerFactory

import scala.concurrent.Await
import scala.concurrent.duration.Duration

object ChangeLanesAccordingToRoadLinkChanges {

  lazy val vvhClient: VVHClient = {
    new VVHClient(Digiroad2Properties.vvhRestApiEndPoint)
  }

  lazy val roadLinkService: RoadLinkService = {
    new RoadLinkService(vvhClient, new DummyEventBus, new DummySerializer)
  }

  lazy val laneService: LaneService = {
    new LaneService(roadLinkService, new DummyEventBus)
  }

  val logger = LoggerFactory.getLogger(getClass)

  // Main process, gets roadlinks from VVH which have been changed in the past 24 hours.
  // handleChanges changes lanes on these roadlinks according to VVH change info
  def process(): Unit = {
    val since = DateTime.now().minusDays(1)
    val until = DateTime.now()

    println("Getting changed links Since: " + since + " Until: " + until)
    //logger.info("Getting changed links\nSince: " + since + "\nUntil: " + until)
    val changedVVHRoadLinks = roadLinkService.getChanged(since, until)
    val linkIds : Set[Long] = changedVVHRoadLinks.map(_.link.linkId).toSet

    val roadLinks = roadLinkService.getRoadLinksByLinkIdsFromVVH(linkIds)
    val changes = Await.result(vvhClient.roadLinkChangeInfo.fetchByLinkIdsF(linkIds), Duration.Inf)

    val changedLanes = handleChanges(roadLinks, changes)

    println("Lanes changed: " + changedLanes.map(_.id).mkString("\n"))
    //logger.info("Lanes changed: " + changedLanes.map(_.id).mkString("\n"))

  }

  def handleChanges(roadLinks: Seq[RoadLink], changes: Seq[ChangeInfo]): Seq[PieceWiseLane] = {
    val mappedChanges = LaneUtils.getMappedChanges(changes)
    val removedLinkIds = LaneUtils.deletedRoadLinkIds(mappedChanges, roadLinks.map(_.linkId).toSet)
    val existingAssets = laneService.fetchExistingLanesByLinkIds(roadLinks.map(_.linkId).distinct, removedLinkIds)

    val timing = System.currentTimeMillis
    val (assetsOnChangedLinks, lanesWithoutChangedLinks) = existingAssets.partition(a => LaneUtils.newChangeInfoDetected(a, mappedChanges))

    val initChangeSet = ChangeSet(
      expiredLaneIds = existingAssets.filter(asset => removedLinkIds.contains(asset.linkId)) // Get only the assets marked to remove
        .map(_.id)                                             // Get only the Ids
        .toSet
        .filterNot( _ == 0L)                                   // Remove the new assets (ID == 0 )
    )

    val (projectedLanes, changedSet) = laneService.fillNewRoadLinksWithPreviousAssetsData(roadLinks, assetsOnChangedLinks, assetsOnChangedLinks, changes, initChangeSet)
    val newLanes = projectedLanes ++ lanesWithoutChangedLinks

    if (newLanes.nonEmpty) {
      logger.info("Finnish transfer %d assets at %d ms after start".format(newLanes.length, System.currentTimeMillis - timing))
    }

    val allLanes = assetsOnChangedLinks.filterNot(a => projectedLanes.exists(_.linkId == a.linkId)) ++ projectedLanes ++ lanesWithoutChangedLinks
    val groupedAssets = allLanes.groupBy(_.linkId)
    val (filledTopology, changeSet) = laneService.laneFiller.fillTopology(roadLinks, groupedAssets, Some(changedSet))

    val generatedMappedById = changeSet.generatedPersistedLanes.groupBy(_.id)
    val modifiedLanes = projectedLanes.filterNot(lane => generatedMappedById(lane.id).nonEmpty) ++ changeSet.generatedPersistedLanes

    laneService.updateChangeSet(changeSet)
    filledTopology
  }




}
