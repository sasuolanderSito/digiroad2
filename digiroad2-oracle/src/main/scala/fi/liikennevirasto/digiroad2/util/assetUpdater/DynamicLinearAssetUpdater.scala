package fi.liikennevirasto.digiroad2.util.assetUpdater

import fi.liikennevirasto.digiroad2.asset.{AutoGeneratedUsername, DynamicProperty, MmlNls}
import fi.liikennevirasto.digiroad2.dao.DynamicLinearAssetDao
import fi.liikennevirasto.digiroad2.linearasset.LinearAssetFiller.SideCodeAdjustment
import fi.liikennevirasto.digiroad2.linearasset._
import fi.liikennevirasto.digiroad2.service.linearasset.{DynamicLinearAssetService, Measures}
import fi.liikennevirasto.digiroad2.util.LinearAssetUtils

class DynamicLinearAssetUpdater(service: DynamicLinearAssetService) extends LinearAssetUpdater(service) {

  def dynamicLinearAssetDao: DynamicLinearAssetDao = new DynamicLinearAssetDao
  override def persistProjectedLinearAssets(newLinearAssets: Seq[PersistedLinearAsset]): Unit = {
    val (toInsert, toUpdate) = newLinearAssets.partition(_.id == 0L)
    val roadLinks = roadLinkService.getExistingAndExpiredRoadLinksByLinkIds(newLinearAssets.map(_.linkId).toSet, newTransaction = false)

    if (toUpdate.nonEmpty) {
      val persisted = dynamicLinearAssetDao.fetchDynamicLinearAssetsByIds(toUpdate.map(_.id).toSet).groupBy(_.id)
      updateProjected(toUpdate, persisted, roadLinks)

      if (newLinearAssets.nonEmpty)
        logger.info(s"Updated ids/linkids ${toUpdate.map(a => (a.id, a.linkId))}")
    }

    toInsert.foreach { linearAsset =>
      val roadLink = roadLinks.find(_.linkId == linearAsset.linkId)

      val id =
        (linearAsset.createdBy, linearAsset.createdDateTime) match {
          case (Some(createdBy), Some(createdDateTime)) =>
            dao.createLinearAsset(linearAsset.typeId, linearAsset.linkId, linearAsset.expired, linearAsset.sideCode,
              Measures(linearAsset.startMeasure, linearAsset.endMeasure).roundMeasures(), AutoGeneratedUsername.generatedInUpdate, linearAsset.timeStamp,
              service.getLinkSource(roadLink), fromUpdate = true, Some(createdBy), Some(createdDateTime), linearAsset.modifiedBy, linearAsset.modifiedDateTime, linearAsset.verifiedBy, linearAsset.verifiedDate, geometry = service.getGeometry(roadLink))
          case _ =>
            dao.createLinearAsset(linearAsset.typeId, linearAsset.linkId, linearAsset.expired, linearAsset.sideCode,
              Measures(linearAsset.startMeasure, linearAsset.endMeasure).roundMeasures(), AutoGeneratedUsername.generatedInUpdate, linearAsset.timeStamp,
              service.getLinkSource(roadLink), geometry = service.getGeometry(roadLink))
        }
      linearAsset.value match {
        case Some(DynamicValue(multiTypeProps)) =>
          val props = setDefaultAndFilterProperties(multiTypeProps, roadLink, linearAsset.typeId)
          service.validateRequiredProperties(linearAsset.typeId, props)
          dynamicLinearAssetDao.updateAssetProperties(id, props, linearAsset.typeId)
        case _ => None
      }
    }
    if (newLinearAssets.nonEmpty)
      logger.info(s"Added assets for linkids ${toInsert.map(_.linkId)}")
  }

  protected def updateProjected(toUpdate: Seq[PersistedLinearAsset], persisted: Map[Long, Seq[PersistedLinearAsset]], roadLinks: Seq[RoadLink]) : Unit = {
    def valueChanged(assetToPersist: PersistedLinearAsset, persistedLinearAsset: Option[PersistedLinearAsset]) = {
      !persistedLinearAsset.exists(_.value == assetToPersist.value)
    }

    toUpdate.foreach { linearAsset =>
      val roadLink = roadLinks.find(_.linkId == linearAsset.linkId)
      val persistedLinearAsset = persisted.getOrElse(linearAsset.id, Seq()).headOption
      val id = linearAsset.id
      if (valueChanged(linearAsset, persistedLinearAsset)) {
        linearAsset.value match {
          case Some(DynamicValue(multiTypeProps)) =>
            dynamicLinearAssetDao.updateAssetLastModified(id, AutoGeneratedUsername.generatedInUpdate) match {
              case Some(id) =>
                val props = setDefaultAndFilterProperties(multiTypeProps, roadLink, linearAsset.typeId)
                service.validateRequiredProperties(linearAsset.typeId, props)
                dynamicLinearAssetDao.updateAssetProperties(id, props, linearAsset.typeId)
              case _ => None
            }
          case _ => None
        }
      }
    }
  }

  protected def setDefaultAndFilterProperties(multiTypeProps: DynamicAssetValue, roadLink: Option[RoadLinkLike], typeId: Int) : Seq[DynamicProperty] = {
    val properties = service.setPropertiesDefaultValues(multiTypeProps.properties, roadLink)
    val defaultValues = dynamicLinearAssetDao.propertyDefaultValues(typeId).filterNot(defaultValue => properties.exists(_.publicId == defaultValue.publicId))
    properties ++ defaultValues.toSet
  }
  protected override def adjustedSideCode(adjustment: SideCodeAdjustment, oldAssets: Seq[PersistedLinearAsset], roadLinks: Seq[RoadLink]): Unit = {
    val oldAsset = oldAssets.find(_.id == adjustment.assetId).getOrElse(throw new IllegalStateException(s"Old asset ${adjustment.assetId} of type ${adjustment.typeId} no longer available"))
    val roadLink = roadLinks.find(_.linkId == oldAsset.linkId).getOrElse(throw new IllegalStateException(s"Road link ${oldAsset.linkId} no longer available"))
    service.expireAsset(oldAsset.typeId, oldAsset.id, AutoGeneratedUsername.generatedInUpdate, expired = true, newTransaction = false)
    service.createWithoutTransaction(oldAsset.typeId, oldAsset.linkId, oldAsset.value.get, adjustment.sideCode.value, Measures(oldAsset.startMeasure, oldAsset.endMeasure),
      AutoGeneratedUsername.generatedInUpdate, LinearAssetUtils.createTimeStamp(), Some(roadLink), true, oldAsset.createdBy, oldAsset.createdDateTime, oldAsset.modifiedBy, oldAsset.modifiedDateTime, oldAsset.verifiedBy, oldAsset.informationSource.map(_.value))
  }

}
