package fi.liikennevirasto.digiroad2.util.assetUpdater

import fi.liikennevirasto.digiroad2.asset.{DynamicProperty, DynamicPropertyValue, MmlNls, UnknownLinkType}
import fi.liikennevirasto.digiroad2.client.vvh.ChangeInfo
import fi.liikennevirasto.digiroad2.linearasset.LinearAssetFiller._
import fi.liikennevirasto.digiroad2.linearasset._
import fi.liikennevirasto.digiroad2.service.linearasset.{LinearAssetTypes, Measures, RoadWidthService}
import fi.liikennevirasto.digiroad2.util.LinearAssetUtils

class RoadWidthUpdater(service: RoadWidthService) extends DynamicLinearAssetUpdater(service) {

  override def updateByRoadLinks(typeId: Int, municipality: Int, roadLinks: Seq[RoadLink], changes: Seq[ChangeInfo]) = {

    try {
      val linkIds = roadLinks.map(_.linkId)
      val mappedChanges = LinearAssetUtils.getMappedChanges(changes)
      val removedLinkIds = LinearAssetUtils.deletedRoadLinkIds(mappedChanges, linkIds.toSet)
      val existingAssets = dynamicLinearAssetDao.fetchDynamicLinearAssetsByLinkIds(LinearAssetTypes.RoadWidthAssetTypeId, linkIds ++ removedLinkIds)

      val timing = System.currentTimeMillis
      val (assetsOnChangedLinks, assetsWithoutChangedLinks) = existingAssets.partition(a => LinearAssetUtils.newChangeInfoDetected(a, mappedChanges))

      val projectableTargetRoadLinks = roadLinks.filter(rl => rl.linkType.value == UnknownLinkType.value || rl.isCarTrafficRoad)

      val initChangeSet: ChangeSet = ChangeSet(droppedAssetIds = Set.empty[Long],
        expiredAssetIds = existingAssets.filter(asset => removedLinkIds.contains(asset.linkId)).map(_.id).toSet.filterNot(_ == 0L),
        adjustedMValues = Seq.empty[MValueAdjustment],
        adjustedVVHChanges = Seq.empty[VVHChangesAdjustment],
        adjustedSideCodes = Seq.empty[SideCodeAdjustment],
        valueAdjustments = Seq.empty[ValueAdjustment])

      val (projectedAssets, changedSetProjected) = fillNewRoadLinksWithPreviousAssetsData(projectableTargetRoadLinks,
        assetsOnChangedLinks, assetsOnChangedLinks, changes, initChangeSet, existingAssets)

      val (newRoadWidthAssets, changedSet) = service.getRoadWidthAssetChanges(existingAssets, projectedAssets, roadLinks, changes, newAssetIds =>
        dao.fetchExpireAssetLastModificationsByLinkIds(LinearAssetTypes.RoadWidthAssetTypeId, newAssetIds), changedSetProjected)

      val newAssets = assetsWithoutChangedLinks ++ projectedAssets.filterNot(a =>
        newRoadWidthAssets.exists(b => b.linkId == a.linkId && b.sideCode == a.sideCode && b.startMeasure == a.startMeasure && b.endMeasure == a.endMeasure)) ++
        newRoadWidthAssets

      val groupedAssets = (existingAssets.filterNot(a => changedSet.expiredAssetIds.contains(a.id) || newAssets.exists(_.linkId == a.linkId)) ++ newAssets).groupBy(_.linkId)
      val (filledTopology, changeSet) = assetFiller.fillTopology(roadLinks, groupedAssets, typeId, Some(changedSet))

      updateChangeSet(changeSet)
      persistProjectedLinearAssets(newAssets.filter(_.id == 0))
      logger.info(s"Updated road widths in municipality $municipality.")
    } catch {
      case e => logger.error(s"Updating road widths in municipality $municipality failed due to ${e.getMessage}.")
    }
  }

  override def persistProjectedLinearAssets(newLinearAssets: Seq[PersistedLinearAsset]): Unit = {
    val (toInsert, toUpdate) = newLinearAssets.partition(_.id == 0L)
    val roadLinks = roadLinkService.getRoadLinksAndComplementariesFromVVH(newLinearAssets.map(_.linkId).toSet, newTransaction = false)
    if (toUpdate.nonEmpty) {
      val persisted = dynamicLinearAssetDao.fetchDynamicLinearAssetsByIds(toUpdate.map(_.id).toSet).groupBy(_.id)
      updateProjected(toUpdate, persisted)

      if (newLinearAssets.nonEmpty)
        logger.info("Updated ids/linkids " + toUpdate.map(a => (a.id, a.linkId)))
    }

    toInsert.foreach { linearAsset =>
      val roadLink = roadLinks.find(_.linkId == linearAsset.linkId)
      val id = (linearAsset.createdBy, linearAsset.createdDateTime) match {
        case (Some(createdBy), Some(createdDateTime)) =>
          dao.createLinearAsset(linearAsset.typeId, linearAsset.linkId, linearAsset.expired, linearAsset.sideCode,
            Measures(linearAsset.startMeasure, linearAsset.endMeasure), linearAsset.modifiedBy.getOrElse(LinearAssetTypes.VvhGenerated), linearAsset.vvhTimeStamp,
            service.getLinkSource(roadLink), fromUpdate = true, Some(createdBy), Some(createdDateTime), linearAsset.verifiedBy, linearAsset.verifiedDate, Some(MmlNls.value), geometry = service.getGeometry(roadLink))
        case _ =>
          dao.createLinearAsset(linearAsset.typeId, linearAsset.linkId, linearAsset.expired, linearAsset.sideCode,
            Measures(linearAsset.startMeasure, linearAsset.endMeasure), linearAsset.createdBy.getOrElse(LinearAssetTypes.VvhGenerated), linearAsset.vvhTimeStamp,
            service.getLinkSource(roadLink), verifiedBy = linearAsset.verifiedBy, informationSource = Some(MmlNls.value), geometry = service.getGeometry(roadLink))
      }
      linearAsset.value match {
        case Some(DynamicValue(multiTypeProps)) =>
          val props = setDefaultAndFilterProperties(multiTypeProps, roadLink, linearAsset.typeId)
          service.validateRequiredProperties(linearAsset.typeId, props)
          dynamicLinearAssetDao.updateAssetProperties(id, props, linearAsset.typeId)

        case Some(NumericValue(intValue)) =>
          val multiTypeProps = DynamicAssetValue(Seq(DynamicProperty("width", "integer", true, Seq(DynamicPropertyValue(intValue)))))
          val props = setDefaultAndFilterProperties(multiTypeProps, roadLink, linearAsset.typeId)
          service.validateRequiredProperties(linearAsset.typeId, props)
          dynamicLinearAssetDao.updateAssetProperties(id, props, linearAsset.typeId)

        case _ => logger.error("Updating asset's " + linearAsset.id + " properties failed")
      }
    }
    if (newLinearAssets.nonEmpty)
      logger.info("Added assets for linkids " + toInsert.map(_.linkId))
  }

  override def updateChangeSet(changeSet: ChangeSet) : Unit = {
    dao.floatLinearAssets(changeSet.droppedAssetIds)

    if (changeSet.adjustedMValues.nonEmpty)
      logger.info("Saving adjustments for asset/link ids=" + changeSet.adjustedMValues.map(a => "" + a.assetId + "/" + a.linkId).mkString(", "))

    changeSet.adjustedMValues.foreach { adjustment =>
      dao.updateMValues(adjustment.assetId, (adjustment.startMeasure, adjustment.endMeasure))
    }

    //      //This changes only should be apply if the asset created_by or modified_by are different of "vvh_mtkclass_default"
    if (changeSet.adjustedVVHChanges.nonEmpty)
      logger.info("Saving adjustments for asset/link ids=" + changeSet.adjustedVVHChanges.map(a => "" + a.assetId + "/" + a.linkId).mkString(", "))

    changeSet.adjustedVVHChanges.foreach { adjustment =>
      dao.updateMValuesChangeInfo(adjustment.assetId, (adjustment.startMeasure, adjustment.endMeasure), adjustment.vvhTimestamp, LinearAssetTypes.VvhGenerated)
    }

    changeSet.adjustedSideCodes.foreach { adjustment =>
      adjustedSideCode(adjustment)
    }

    val ids = changeSet.expiredAssetIds.toSeq
    if (ids.nonEmpty)
      logger.info("Expiring ids " + ids.mkString(", "))
    ids.foreach(dao.updateExpiration(_, expired = true, "vvh_mtkclass_default"))

  }
}

