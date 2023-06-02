package fi.liikennevirasto.digiroad2.util.assetUpdater

import fi.liikennevirasto.digiroad2.asset.{AutoGeneratedUsername, MaintenanceRoadAsset, UnknownLinkType}
import fi.liikennevirasto.digiroad2.client.vvh.ChangeInfo
import fi.liikennevirasto.digiroad2.linearasset.LinearAssetFiller._
import fi.liikennevirasto.digiroad2.linearasset.{DynamicValue, PersistedLinearAsset, PieceWiseLinearAsset, RoadLink}
import fi.liikennevirasto.digiroad2.service.linearasset.{LinearAssetTypes, MaintenanceService, Measures}
import fi.liikennevirasto.digiroad2.util.LinearAssetUtils

class MaintenanceRoadUpdater(service: MaintenanceService) extends DynamicLinearAssetUpdater(service) {

  override def updateByRoadLinks(typeId: Int, municipality: Int, roadLinks: Seq[RoadLink], changes: Seq[ChangeInfo]) = {
    val roads: Seq[RoadLink] = roadLinks.filter(_.functionalClass > 4)
    super.updateByRoadLinks(typeId, municipality, roads, changes)
  }


  override def persistProjectedLinearAssets(newMaintenanceAssets: Seq[PersistedLinearAsset]): Unit = {
    val (toInsert, toUpdate) = newMaintenanceAssets.partition(_.id == 0L)
    val roadLinks = roadLinkService.getRoadLinksAndComplementariesByLinkIds(newMaintenanceAssets.map(_.linkId).toSet, newTransaction = false)
    if (toUpdate.nonEmpty) {
      val persisted = dynamicLinearAssetDao.fetchDynamicLinearAssetsByIds(toUpdate.map(_.id).toSet).groupBy(_.id)
      updateProjected(toUpdate, persisted)
      if (newMaintenanceAssets.nonEmpty)
        logger.info("Updated ids/linkids " + toUpdate.map(a => (a.id, a.linkId)))
    }
    toInsert.foreach { linearAsset =>
      val roadLink = roadLinks.find(_.linkId == linearAsset.linkId)
      val area = service.getAssetArea(roadLinks.find(_.linkId == linearAsset.linkId), Measures(linearAsset.startMeasure, linearAsset.endMeasure))
      val id = service.maintenanceDAO.createLinearAsset(linearAsset.typeId, linearAsset.linkId, linearAsset.expired, linearAsset.sideCode,
        Measures(linearAsset.startMeasure, linearAsset.endMeasure), linearAsset.createdBy.getOrElse(AutoGeneratedUsername.generatedInUpdate), linearAsset.timeStamp, service.getLinkSource(roadLink), area = area)
      linearAsset.value match {
        case Some(DynamicValue(multiTypeProps)) =>
          val props = setDefaultAndFilterProperties(multiTypeProps, roadLink, linearAsset.typeId)
          service.validateRequiredProperties(linearAsset.typeId, props)
          dynamicLinearAssetDao.updateAssetProperties(id, props, linearAsset.typeId)
        case _ => None
      }
    }
    if (newMaintenanceAssets.nonEmpty)
      logger.info("Added assets for linkids " + toInsert.map(_.linkId))
  }
}