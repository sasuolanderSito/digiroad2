package fi.liikennevirasto.digiroad2.util.assetUpdater

import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.client.RoadLinkChange
import fi.liikennevirasto.digiroad2.linearasset.LinearAssetFiller._
import fi.liikennevirasto.digiroad2.linearasset._
import fi.liikennevirasto.digiroad2.service.linearasset.{LinearAssetTypes, Measures, RoadWidthService}
import fi.liikennevirasto.digiroad2.util.LinearAssetUtils
import org.joda.time.DateTime
sealed case class RoadWidthMap(linkId: String, adminClass: AdministrativeClass, mTKClassWidth:MTKClassWidth, linkLength:Double)

class RoadWidthUpdater(service: RoadWidthService) extends DynamicLinearAssetUpdater(service) {
  
  override def operationForNewLink(change: RoadLinkChange, assetsAll: Seq[PersistedLinearAsset], changeSets: ChangeSet): Option[OperationStep] = {
    val newLinks = change.newLinks.head
    val roadWidth = MTKClassWidth(newLinks.roadClass)
    if (newLinks.adminClass != State && roadWidth.value != MTKClassWidth.Unknown.value){
      val newAsset = PersistedLinearAsset(0, newLinks.linkId, sideCode = SideCode.BothDirections.value,
        value = Some(createValue(roadWidth)),
        startMeasure = 0, endMeasure = newLinks.linkLength, createdBy = Some(AutoGeneratedUsername.mtkClassDefault),
        createdDateTime = Some(DateTime.now()),
        modifiedBy = None, modifiedDateTime = None, expired = false, typeId = LinearAssetTypes.RoadWidthAssetTypeId,
        timeStamp = LinearAssetUtils.createTimeStamp(), geomModifiedDate = Some(DateTime.now()),
        linkSource = LinkGeomSource.NormalLinkInterface, 
        verifiedBy = service.getVerifiedBy(AutoGeneratedUsername.mtkClassDefault, LinearAssetTypes.RoadWidthAssetTypeId), 
        verifiedDate = None,
        informationSource = Some(MmlNls))
      Some(OperationStep(Seq(newAsset), Some(changeSets),Seq()))
    }else {
      None
    }
  }

  override def additionalOperations(operationStep: OperationStep, changes: Seq[RoadLinkChange]): Option[OperationStep] ={
    expireAndCreate(operationStep,changes)
  }

  private def expireAndCreate(operationStep: OperationStep,changes: Seq[RoadLinkChange]) = {
    val changeSets: ChangeSet = operationStep.changeInfo.get
    val changesNewLinks =changes.flatMap(_.newLinks)
    
    val newLinksMapped = changesNewLinks
      .map(a => RoadWidthMap(a.linkId, a.adminClass, MTKClassWidth(a.roadClass), a.linkLength))
      .filter(p => p.adminClass != State && p.mTKClassWidth.value != MTKClassWidth.Unknown.value)
    val filterOnlyRelevant = operationStep.
      assetsAfter.filter(selectOnlyMachineCreated)
      .filter(a => newLinksMapped.map(_.linkId).contains(a.linkId))
    
    if (filterOnlyRelevant.nonEmpty) {
      val systemEditedUpdated = filterOnlyRelevant.map(asset => {
        val replace = newLinksMapped.find(_.linkId == asset.linkId).get
        if (asset.id != 0) {
          val operation = operationStep.copy(
              assetsAfter = Seq(PersistedLinearAsset(0, replace.linkId, 
              sideCode = SideCode.BothDirections.value, value = Some(createValue(replace.mTKClassWidth)),
              startMeasure = 0, endMeasure = replace.linkLength, 
              createdBy = Some(AutoGeneratedUsername.mtkClassDefault),
              createdDateTime = Some(DateTime.now()),
              modifiedBy = None, modifiedDateTime = None, 
              expired = false, typeId = LinearAssetTypes.RoadWidthAssetTypeId,
              timeStamp = LinearAssetUtils.createTimeStamp(), geomModifiedDate = Some(DateTime.now()),
              linkSource = LinkGeomSource.NormalLinkInterface, 
              verifiedBy = service.getVerifiedBy(AutoGeneratedUsername.mtkClassDefault, LinearAssetTypes.RoadWidthAssetTypeId), verifiedDate = None,
              informationSource = Some(MmlNls), oldId = asset.id
            )),
            changeInfo = Some(changeSets.copy(expiredAssetIds = changeSets.expiredAssetIds ++ Set(asset.id)))
          )
          operation
        } else {
          handleGeneratedPart( changeSets, asset, replace,operationStep)
        }
      })
      val merge = systemEditedUpdated.foldLeft(OperationStep(operationStep.assetsAfter, Some(changeSets)))((a, b) => {
        OperationStep((a.assetsAfter ++ b.assetsAfter).distinct, Some(LinearAssetFiller.combineChangeSets(a.changeInfo.get, b.changeInfo.get)), b.assetsBefore)
      })
      Some(merge)
    } else None
  }
  private def createValue(replace:MTKClassWidth) = {
    val newWidth = Seq(DynamicProperty("width", "integer", required = true, List(DynamicPropertyValue(replace.width.toString))))
    DynamicValue(value = DynamicAssetValue(newWidth))
  }
  //TODO add test
  private def handleGeneratedPart(changeSets: ChangeSet, asset: PersistedLinearAsset, replace: RoadWidthMap, operationStep: OperationStep) = {
    val (_, other) = asset.value.get.asInstanceOf[DynamicValue].value.properties.partition(_.publicId == "width")
    val newWidth = Seq(DynamicProperty("width", "integer", required = true, List(DynamicPropertyValue(replace.mTKClassWidth.width.toString))))
    val newValue = DynamicValue(value = DynamicAssetValue(other ++ newWidth))
    val operationUpdated = operationStep.copy( assetsAfter = Seq(asset.copy(value = Some(newValue))), changeInfo = Some(changeSets))
    if (widthNeedUpdating(asset, replace)) operationUpdated else  operationStep
  }
  private def widthNeedUpdating(asset: PersistedLinearAsset, replace: RoadWidthMap) = {
    extractPropertyValue("width", asset.value.get.asInstanceOf[DynamicValue].value.properties).head != replace.mTKClassWidth.width.toString
  }
  private def extractPropertyValue(key: String, properties: Seq[DynamicProperty]) = {
    properties.filter { property => property.publicId == key }.flatMap { property =>
      property.values.map { value =>
        value.value.toString
      }
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
    val roadLinks = roadLinkService.getExistingAndExpiredRoadLinksByLinkIds(newLinearAssets.map(_.linkId).toSet, newTransaction = false)
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

