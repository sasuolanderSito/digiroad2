package fi.liikennevirasto.digiroad2.util

import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.client.RoadLinkClient
import fi.liikennevirasto.digiroad2.dao.Queries
import fi.liikennevirasto.digiroad2.dao.linearasset.PostGISLinearAssetDao
import fi.liikennevirasto.digiroad2.linearasset.MTKClassWidth
import fi.liikennevirasto.digiroad2.postgis.PostGISDatabase
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.service.linearasset.{Measures, RoadWidthService}
import fi.liikennevirasto.digiroad2.DummyEventBus
import org.joda.time.DateTime
import org.slf4j.LoggerFactory

class RoadWidthGenerator {

  val dao = new PostGISLinearAssetDao()
  val roadLinkClient: RoadLinkClient = new RoadLinkClient()
  val roadLinkService = new RoadLinkService(roadLinkClient, new DummyEventBus)
  val roadWidthService: RoadWidthService = new RoadWidthService(roadLinkService, new DummyEventBus)
  val logger = LoggerFactory.getLogger(getClass)

  def withDynTransaction[T](f: => T): T = PostGISDatabase.withDynTransaction(f)

  def fillRoadWidths() = {
    logger.info("Generate road width for road links without it.")
    logger.info(DateTime.now().toString())

    withDynTransaction {
      val municipalities = Queries.getMunicipalities
      municipalities.foreach { municipality =>
        logger.info("Working on municipality " + municipality)
        fillRoadWidthsByMunicipality(municipality)
      }
    }
  }

  def fillRoadWidthsByMunicipality(municipality: Int) = {
    val roadLinks = roadLinkService.getRoadLinksByMunicipality(municipality, false)
    val nonStateRoads = roadLinks.filter(_.administrativeClass != State)
    val roadLinksWithMTKClass = nonStateRoads.filter(road => MTKClassWidth.values.toSeq.contains(road.extractMTKClassWidth(road.attributes)))

    val roadWidths = roadWidthService.fetchExistingAssetsByLinksIds(RoadWidth.typeId, roadLinksWithMTKClass, Seq(), false)
    val roadLinksLackingWidth = roadLinksWithMTKClass.filterNot(rl => roadWidths.map(_.linkId).contains(rl.linkId))
    logger.info(s"Filling ${roadLinksLackingWidth.size} road links in municipality ${municipality}.")
    roadLinksLackingWidth.foreach { rl =>
      val id = dao.createLinearAsset(RoadWidth.typeId, rl.linkId, false, SideCode.BothDirections.value,
        Measures(0, rl.length), AutoGeneratedUsername.mtkClassDefault,
        LinearAssetUtils.createTimeStamp(), Some(rl.linkSource.value), geometry = rl.geometry)
      val mtkClassWidth = rl.extractMTKClassWidth(rl.attributes)
      dao.insertValue(id, "width", mtkClassWidth.width, Some(RoadWidth.typeId))
    }
  }
}
