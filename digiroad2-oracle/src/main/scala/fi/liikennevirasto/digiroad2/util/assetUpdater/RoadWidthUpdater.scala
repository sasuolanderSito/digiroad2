package fi.liikennevirasto.digiroad2.util.assetUpdater

import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.client.{RoadLinkChange, RoadLinkChangeType}
import fi.liikennevirasto.digiroad2.linearasset.LinearAssetFiller._
import fi.liikennevirasto.digiroad2.linearasset.{MTKClassWidth, _}
import fi.liikennevirasto.digiroad2.service.linearasset.{LinearAssetTypes, Measures, RoadWidthService}
import fi.liikennevirasto.digiroad2.util.LinearAssetUtils
import org.joda.time.DateTime

class RoadWidthUpdater(service: RoadWidthService) extends DynamicLinearAssetUpdater(service) {
  
  override def operationForNewLink(change: RoadLinkChange, assetsAll: Seq[PersistedLinearAsset], changeSets: ChangeSet): Seq[(PersistedLinearAsset, ChangeSet)] = {
    val newLinks = change.newLinks.head
    val roadWidth = MTKClassWidth(newLinks.roadClass)
    if (newLinks.adminClass != State && roadWidth.value != MTKClassWidth.Unknown.value){
      val newAsset = PersistedLinearAsset(0, newLinks.linkId, sideCode = SideCode.BothDirections.value, value = Some(NumericValue(roadWidth.width)),
        startMeasure = 0, endMeasure = newLinks.linkLength, createdBy = Some(AutoGeneratedUsername.mtkClassDefault),
        createdDateTime = Some(DateTime.now()),
        modifiedBy = None, modifiedDateTime = None, expired = false, typeId = LinearAssetTypes.RoadWidthAssetTypeId,
        timeStamp = LinearAssetUtils.createTimeStamp(), geomModifiedDate = Some(DateTime.now())
        /** validate this* */
        , linkSource = LinkGeomSource.NormalLinkInterface, verifiedBy = service.getVerifiedBy(AutoGeneratedUsername.mtkClassDefault, LinearAssetTypes.RoadWidthAssetTypeId), verifiedDate = None,
        informationSource = Some(MmlNls))
      Seq((newAsset, changeSets))
    }else {
      Seq.empty[(PersistedLinearAsset, ChangeSet)]
    }
  }

  override def additionalUpdateOrChange(change: RoadLinkChange, assetsAll: Seq[PersistedLinearAsset], changeSets: ChangeSet): Seq[(PersistedLinearAsset, ChangeSet)] = {
    change.changeType match {
    // expire and recreate width
      case RoadLinkChangeType.Replace | RoadLinkChangeType.Split =>
        val newLinksMapped = change.newLinks.map(a=>(a.linkId,a.adminClass,MTKClassWidth(a.roadClass),a.linkLength)).filter(p =>p._2 != State && p._3.value != MTKClassWidth.Unknown.value)

       val systemEditedUpdated =  assetsAll.filter(selectOnlyMachineCreated).filter(a=>newLinksMapped.map(_._1).contains(a.linkId)).map(asset => {
          val replace = newLinksMapped.find(_._1==asset.linkId).get
         if (asset.id!=0) {
           (PersistedLinearAsset(0, replace._1, sideCode = SideCode.BothDirections.value, value = Some(NumericValue(replace._3.width)),
             startMeasure = 0, endMeasure = replace._4, createdBy = Some(AutoGeneratedUsername.mtkClassDefault),
             createdDateTime = Some(DateTime.now()),
             modifiedBy = None, modifiedDateTime = None, expired = false, typeId = LinearAssetTypes.RoadWidthAssetTypeId,
             timeStamp = LinearAssetUtils.createTimeStamp(), geomModifiedDate = Some(DateTime.now()),
             linkSource = LinkGeomSource.NormalLinkInterface, verifiedBy = service.getVerifiedBy(AutoGeneratedUsername.mtkClassDefault, LinearAssetTypes.RoadWidthAssetTypeId), verifiedDate = None,
             informationSource = Some(MmlNls)),
             changeSets.copy(expiredAssetIds = changeSets.expiredAssetIds ++ Set(asset.id)))
         }else {
           (asset.copy(value = Some(NumericValue(replace._3.width))),changeSets)
         }
        })
        systemEditedUpdated
      case _ => Seq.empty[(PersistedLinearAsset, ChangeSet)]
    }
  }

  private def selectOnlyMachineCreated(asset: PersistedLinearAsset) = {
    val noEdit = asset.modifiedBy.getOrElse(asset.createdBy.getOrElse("")) == AutoGeneratedUsername.mtkClassDefault
    val partOfConversion = asset.modifiedBy.getOrElse(asset.createdBy.getOrElse("")) == AutoGeneratedUsername.dr1Conversion
    val changesOnlyUnderSamuutus = asset.modifiedBy.getOrElse("") == AutoGeneratedUsername.generatedInUpdate && asset.createdBy.getOrElse("") == AutoGeneratedUsername.mtkClassDefault
    noEdit || partOfConversion || changesOnlyUnderSamuutus
  }
  override def persistProjectedLinearAssets(newLinearAssets: Seq[PersistedLinearAsset]): Unit = {
    val (toInsert, toUpdate) = newLinearAssets.partition(_.id == 0L)
    val roadLinks = roadLinkService.getRoadLinksAndComplementariesByLinkIds(newLinearAssets.map(_.linkId).toSet, newTransaction = false)
    if (toUpdate.nonEmpty) {
      val persisted = dynamicLinearAssetDao.fetchDynamicLinearAssetsByIds(toUpdate.map(_.id).toSet).groupBy(_.id)
      updateProjected(toUpdate, persisted)

      if (newLinearAssets.nonEmpty)
        logger.info(s"Updated ids/linkids ${toUpdate.map(a => (a.id, a.linkId))}")
    }

    toInsert.foreach { linearAsset =>
      val roadLink = roadLinks.find(_.linkId == linearAsset.linkId)
      val id = (linearAsset.createdBy, linearAsset.createdDateTime) match {
        case (Some(createdBy), Some(createdDateTime)) =>
          dao.createLinearAsset(linearAsset.typeId, linearAsset.linkId, linearAsset.expired, linearAsset.sideCode,
            Measures(linearAsset.startMeasure, linearAsset.endMeasure).roundMeasures(), linearAsset.modifiedBy.getOrElse(AutoGeneratedUsername.generatedInUpdate), linearAsset.timeStamp,
            service.getLinkSource(roadLink), fromUpdate = true, Some(createdBy), Some(createdDateTime), linearAsset.verifiedBy, linearAsset.verifiedDate, Some(MmlNls.value), geometry = service.getGeometry(roadLink))
        case _ =>
          dao.createLinearAsset(linearAsset.typeId, linearAsset.linkId, linearAsset.expired, linearAsset.sideCode,
            Measures(linearAsset.startMeasure, linearAsset.endMeasure).roundMeasures(), linearAsset.createdBy.getOrElse(AutoGeneratedUsername.generatedInUpdate), linearAsset.timeStamp,
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

        case _ => logger.error(s"Updating asset's ${linearAsset.id} properties failed")
      }
    }
    if (newLinearAssets.nonEmpty)
      logger.info(s"Added assets for linkids ${toInsert.map(_.linkId)}")
  }

}

