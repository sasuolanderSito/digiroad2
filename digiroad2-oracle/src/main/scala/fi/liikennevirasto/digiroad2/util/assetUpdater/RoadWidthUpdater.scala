package fi.liikennevirasto.digiroad2.util.assetUpdater

import fi.liikennevirasto.digiroad2.asset.TrafficDirection.toSideCode
import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.client.{RoadLinkChange, RoadLinkInfo}
import fi.liikennevirasto.digiroad2.linearasset.LinearAssetFiller._
import fi.liikennevirasto.digiroad2.linearasset._
import fi.liikennevirasto.digiroad2.service.linearasset.{LinearAssetTypes, Measures, RoadWidthService}
import fi.liikennevirasto.digiroad2.util.{LinearAssetUtils, Parallel}
import org.joda.time.DateTime

sealed case class RoadWidthMap(linkId: String, adminClass: AdministrativeClass, mTKClassWidth: MTKClassWidth, linkLength: Double, trafficDirection: TrafficDirection)

class RoadWidthUpdater(service: RoadWidthService) extends DynamicLinearAssetUpdater(service) {
  
  override def operationForNewLink(change: RoadLinkChange, assetsAll: Seq[PersistedLinearAsset], onlyNeededNewRoadLinks: Seq[RoadLink], changeSets: ChangeSet): Option[OperationStep] = {
    val newLinkInfo = change.newLinks.head
    val roadWidth = MTKClassWidth(newLinkInfo.roadClass)
    val roadLink = onlyNeededNewRoadLinks.find(_.linkId == newLinkInfo.linkId)
    if (newLinkInfo.adminClass != State && roadWidth.value != MTKClassWidth.Unknown.value && roadLink.nonEmpty){
      val newAsset = PersistedLinearAsset(0, newLinkInfo.linkId, sideCode = toSideCode(roadLink.get.trafficDirection).value,
        value = Some(createValue(roadWidth)),
        startMeasure = 0, endMeasure = newLinkInfo.linkLength, createdBy = Some(AutoGeneratedUsername.mtkClassDefault),
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
    adjustWidthsByRoadClass(operationStep,changes)
  }

  private def adjustWidthsByRoadClass(operationStep: OperationStep,changes: Seq[RoadLinkChange]) = {

    def adjustValue(newLinksWithAssetsGroup: Seq[(RoadLinkInfo, Seq[PersistedLinearAsset])], changeSet: ChangeSet) = {
      newLinksWithAssetsGroup.flatMap(newLinkWithAssets => {
        val (newLink, assets) = newLinkWithAssets
        assets.map(asset => {
          if (asset.id != 0) {
            val modifiedAsset = PersistedLinearAsset(asset.id, newLink.linkId,
              sideCode = toSideCode(newLink.trafficDirection).value, value = Some(createValue(MTKClassWidth(newLink.roadClass))),
              startMeasure = asset.startMeasure, endMeasure = asset.endMeasure,
              createdBy = asset.createdBy,
              createdDateTime = asset.createdDateTime,
              modifiedBy = Some(AutoGeneratedUsername.mtkClassDefault), Some(DateTime.now()),
              expired = false, typeId = LinearAssetTypes.RoadWidthAssetTypeId,
              timeStamp = LinearAssetUtils.createTimeStamp(), geomModifiedDate = Some(DateTime.now()),
              linkSource = LinkGeomSource.NormalLinkInterface,
              verifiedBy = service.getVerifiedBy(AutoGeneratedUsername.mtkClassDefault, LinearAssetTypes.RoadWidthAssetTypeId), verifiedDate = None,
              informationSource = Some(MmlNls), oldId = asset.id
            )
            val operation = operationStep.copy(
              assetsAfter = Seq(modifiedAsset),
              changeInfo = Some(changeSet.copy(valueAdjustments = changeSet.valueAdjustments ++ Seq(ValueAdjustment(modifiedAsset.id, modifiedAsset.typeId, modifiedAsset.value.get, modifiedAsset.modifiedBy.get))))
            )
            operation
          } else {
            handleGeneratedPart(changeSet, asset, newLink, operationStep)
          }
        })
      })
    }

    def parallelLoop(filteredNewLinksWithAssetsAfter: Seq[(RoadLinkInfo, Seq[PersistedLinearAsset])], changeSet: ChangeSet) = {
      val newLinksWithAssetsGroups = filteredNewLinksWithAssetsAfter.grouped(groupSizeForParallelRun).toList.par
      val totalTasks = newLinksWithAssetsGroups.size
      val level = if (totalTasks < maximumParallelismLevel) totalTasks else maximumParallelismLevel
      logger.info(s"Asset groups: $totalTasks, parallelism level used: $level")

      new Parallel().operation(newLinksWithAssetsGroups, level) { tasks =>
        tasks.flatMap(newLinksWithAssetsGroup => {
          adjustValue(newLinksWithAssetsGroup, changeSet)
        })
      }.toList
    }

    val changeSet: ChangeSet = operationStep.changeInfo.get
    val changesNewLinks = changes.flatMap(_.newLinks)
    val filteredNewLinks = changesNewLinks.filter(newLink => newLink.adminClass != State && MTKClassWidth(newLink.roadClass).value != MTKClassWidth.Unknown.value)
    val machineCreatedAssets = operationStep.assetsAfter.filter(selectOnlyMachineCreated)
    val assetsAfterGrouped = machineCreatedAssets.groupBy(_.linkId)

    val filteredNewLinksWithAssetsAfter = filteredNewLinks.flatMap { newLink =>
      val linkedAssets = assetsAfterGrouped.getOrElse(newLink.linkId, Seq.empty)
      if(linkedAssets.nonEmpty) Some((newLink, linkedAssets))
      else None
    }

    if (filteredNewLinksWithAssetsAfter.nonEmpty) {
      val systemEditedUpdated = filteredNewLinksWithAssetsAfter.size match {
        case a if a >= parallelizationThreshold => parallelLoop(filteredNewLinksWithAssetsAfter, changeSet)
        case _ => adjustValue(filteredNewLinksWithAssetsAfter, changeSet)
      }


      val assetsAfterFiltered = operationStep.assetsAfter.filterNot(assetAfter => filteredNewLinksWithAssetsAfter.flatMap(_._2).contains(assetAfter))
      val merge = systemEditedUpdated.foldLeft(OperationStep(assetsAfterFiltered, Some(changeSet)))((a, b) => {
        OperationStep((a.assetsAfter ++ b.assetsAfter).distinct, Some(LinearAssetFiller.combineChangeSets(a.changeInfo.get, b.changeInfo.get)), b.assetsBefore)
      })
      Some(merge)
    } else None
  }

  private def createValue(replace:MTKClassWidth) = {
    val newWidth = Seq(DynamicProperty("width", "integer", required = true, List(DynamicPropertyValue(replace.width.toString))))
    DynamicValue(value = DynamicAssetValue(newWidth))
  }
  
  private def handleGeneratedPart(changeSets: ChangeSet, asset: PersistedLinearAsset, newLink: RoadLinkInfo, operationStep: OperationStep) = {
    val (_, other) = asset.value.get.asInstanceOf[DynamicValue].value.properties.partition(_.publicId == "width")
    val newWidth = Seq(DynamicProperty("width", "integer", required = true, List(DynamicPropertyValue(MTKClassWidth(newLink.roadClass).width.toString))))
    val newValue = DynamicValue(value = DynamicAssetValue(other ++ newWidth))
    val operationUpdated = operationStep.copy( assetsAfter = Seq(asset.copy(value = Some(newValue))), changeInfo = Some(changeSets))
    if (widthNeedUpdating(asset, newLink)) operationUpdated else  operationStep.copy(assetsAfter = Seq(asset))
  }
  private def widthNeedUpdating(asset: PersistedLinearAsset, newLink: RoadLinkInfo) = {
    extractPropertyValue("width", asset.value.get.asInstanceOf[DynamicValue].value.properties).head != MTKClassWidth(newLink.roadClass).width.toString
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
  protected override def persistProjectedLinearAssets(newLinearAssets: Seq[PersistedLinearAsset], onlyNeededNewRoadLinks: Seq[RoadLink]): Unit = {
    newLinearAssets.foreach { linearAsset =>
      val roadLink = onlyNeededNewRoadLinks.find(_.linkId == linearAsset.linkId)
      val id = (linearAsset.createdBy, linearAsset.createdDateTime) match {
        case (Some(createdBy), Some(createdDateTime)) =>
          dao.createLinearAsset(linearAsset.typeId, linearAsset.linkId, linearAsset.expired, linearAsset.sideCode,
            Measures(linearAsset.startMeasure, linearAsset.endMeasure).roundMeasures(), linearAsset.modifiedBy.getOrElse(AutoGeneratedUsername.generatedInUpdate), linearAsset.timeStamp,
            service.getLinkSource(roadLink), fromUpdate = true, Some(createdBy), Some(createdDateTime), linearAsset.modifiedBy, linearAsset.modifiedDateTime, linearAsset.verifiedBy, linearAsset.verifiedDate, Some(MmlNls.value), geometry = service.getGeometry(roadLink))
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
      logger.debug(s"Added assets for linkids ${newLinearAssets.map(_.linkId)}")
  }

}

