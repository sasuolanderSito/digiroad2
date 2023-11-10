package fi.liikennevirasto.digiroad2.util.assetUpdater

import fi.liikennevirasto.digiroad2.asset.{AutoGeneratedUsername, Prohibition}
import fi.liikennevirasto.digiroad2.linearasset.LinearAssetFiller._
import fi.liikennevirasto.digiroad2.linearasset.{PersistedLinearAsset, Prohibitions, RoadLink}
import fi.liikennevirasto.digiroad2.service.linearasset.{Measures, ProhibitionService}
import fi.liikennevirasto.digiroad2.util.LinearAssetUtils

class ProhibitionUpdater(service: ProhibitionService) extends LinearAssetUpdater(service) {
  protected override def persistProjectedLinearAssets(newLinearAssets: Seq[PersistedLinearAsset], roadLinks: Seq[RoadLink]): Unit = {
    val (toInsert, toUpdate) = newLinearAssets.partition(_.id == 0L)
    val roadLinks = roadLinkService.getExistingAndExpiredRoadLinksByLinkIds(newLinearAssets.map(_.linkId).toSet, newTransaction = false)
    if (toUpdate.nonEmpty) {
      val prohibitions = toUpdate.filter(a => Set(Prohibition.typeId).contains(a.typeId))
      val persisted = dao.fetchProhibitionsByIds(Prohibition.typeId, prohibitions.map(_.id).toSet).groupBy(_.id)
      updateProjected(toUpdate, persisted)
      if (newLinearAssets.nonEmpty)
        logger.info(s"Updated ids/linkids ${toUpdate.map(a => (a.id, a.linkId))}")
    }
    toInsert.foreach { linearAsset =>
      val id =
        (linearAsset.createdBy, linearAsset.createdDateTime) match {
          case (Some(createdBy), Some(createdDateTime)) =>
            dao.createLinearAsset(linearAsset.typeId, linearAsset.linkId, linearAsset.expired, linearAsset.sideCode,
              Measures(linearAsset.startMeasure, linearAsset.endMeasure), AutoGeneratedUsername.generatedInUpdate, linearAsset.timeStamp,
              service.getLinkSource(roadLinks.find(_.linkId == linearAsset.linkId)), true, Some(createdBy), Some(createdDateTime), linearAsset.modifiedBy, linearAsset.modifiedDateTime, linearAsset.verifiedBy, linearAsset.verifiedDate)
          case _ =>
            dao.createLinearAsset(linearAsset.typeId, linearAsset.linkId, linearAsset.expired, linearAsset.sideCode,
              Measures(linearAsset.startMeasure, linearAsset.endMeasure), AutoGeneratedUsername.generatedInUpdate, linearAsset.timeStamp, service.getLinkSource(roadLinks.find(_.linkId == linearAsset.linkId)))
        }
      linearAsset.value match {
        case Some(prohibitions: Prohibitions) =>
          dao.insertProhibitionValue(id, Prohibition.typeId, prohibitions)
        case _ => None
      }
    }
    if (newLinearAssets.nonEmpty)
      logger.info(s"Added assets for linkids ${toInsert.map(_.linkId)}")
  }

  protected override def adjustedSideCode(adjustment: SideCodeAdjustment, oldAssets: Seq[PersistedLinearAsset], roadLinks: Seq[RoadLink], adjustedSideCodes: Seq[SideCodeAdjustment]): Unit = {
    val oldAsset = oldAssets.find(_.id == adjustment.assetId).getOrElse(throw new IllegalStateException(s"Prohibition: Old asset ${adjustment.assetId} no longer available"))
    val roadLink = roadLinks.find(_.linkId == oldAsset.linkId).getOrElse(throw new IllegalStateException(s"Road link ${oldAsset.linkId} no longer available"))
    service.expireAsset(oldAsset.typeId, oldAsset.id, AutoGeneratedUsername.generatedInUpdate, expired = true, newTransaction = false)
    service.createWithoutTransaction(oldAsset.typeId, oldAsset.linkId, oldAsset.value.get, adjustment.sideCode.value,
      Measures(oldAsset.startMeasure, oldAsset.endMeasure), AutoGeneratedUsername.generatedInUpdate, LinearAssetUtils.createTimeStamp(),
      Some(roadLink), true, oldAsset.createdBy, oldAsset.createdDateTime, oldAsset.modifiedBy, oldAsset.modifiedDateTime, oldAsset.verifiedBy, oldAsset.informationSource.map(_.value))
  }

  override protected def updateProjected(toUpdate: Seq[PersistedLinearAsset], persisted: Map[Long, Seq[PersistedLinearAsset]]) = {
    def valueChanged(assetToPersist: PersistedLinearAsset, persistedLinearAsset: Option[PersistedLinearAsset]) = {
      !persistedLinearAsset.exists(_.value == assetToPersist.value)
    }

    toUpdate.foreach { linearAsset =>
      val persistedLinearAsset = persisted.getOrElse(linearAsset.id, Seq()).headOption
      val id = linearAsset.id
      if (valueChanged(linearAsset, persistedLinearAsset)) {
        linearAsset.value match {
          case Some(prohibitions: Prohibitions) =>
            dao.updateProhibitionValue(id, linearAsset.typeId, prohibitions, AutoGeneratedUsername.generatedInUpdate)
          case _ => None
        }
      }
    }
  }

}
