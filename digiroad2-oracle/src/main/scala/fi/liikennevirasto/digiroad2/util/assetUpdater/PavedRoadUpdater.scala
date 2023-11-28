package fi.liikennevirasto.digiroad2.util.assetUpdater

import fi.liikennevirasto.digiroad2.GeometryUtils
import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.client.{RoadLinkChange, RoadLinkChangeType}
import fi.liikennevirasto.digiroad2.linearasset.LinearAssetFiller._
import fi.liikennevirasto.digiroad2.linearasset._
import fi.liikennevirasto.digiroad2.service.linearasset.LinearAssetTypes
import fi.liikennevirasto.digiroad2.service.pointasset.PavedRoadService
import fi.liikennevirasto.digiroad2.util.LinearAssetUtils
import org.joda.time.DateTime

class PavedRoadUpdater(service: PavedRoadService) extends DynamicLinearAssetUpdater(service) {
  
  override def operationForNewLink(change: RoadLinkChange, assetsAll: Seq[PersistedLinearAsset], changeSets: ChangeSet): Option[OperationStep] = {
    val newLink = change.newLinks.head
    
    if (newLink.surfaceType == SurfaceType.Paved) {
      val defaultMultiTypePropSeq = DynamicAssetValue(Seq(DynamicProperty("paallysteluokka", "single_choice", required = false, Seq(DynamicPropertyValue("99")))))
      val defaultPropertyData = DynamicValue(defaultMultiTypePropSeq)
      
      val newAsset = PersistedLinearAsset(0, newLink.linkId,
        sideCode = SideCode.BothDirections.value,
        value = Some(defaultPropertyData),
        startMeasure = 0, endMeasure = GeometryUtils.geometryLength(newLink.geometry), createdBy = Some(AutoGeneratedUsername.mmlPavedDefault),
        createdDateTime = Some(DateTime.now()),
        modifiedBy = None, modifiedDateTime = None, expired = false, 
        typeId = LinearAssetTypes.PavedRoadAssetTypeId,
        timeStamp = LinearAssetUtils.createTimeStamp(), geomModifiedDate = Some(DateTime.now()),
        linkSource = LinkGeomSource.NormalLinkInterface,
        verifiedBy = None, verifiedDate = None,
        informationSource = Some(MmlNls))
      Some(OperationStep(Seq(newAsset), Some(changeSets),Seq()))
    } else {
      None
    }
    
  }

  /**
    * Check if pavedRoad asset is generated from MML information, and not modified, or only modified by samuutus
    * @param asset
    * @return True if generated from MML info and not modified by user or batch, else false
    */
  private def isGeneratedFromMML(asset: PersistedLinearAsset): Boolean = {
    val createdBy = asset.createdBy.getOrElse("")
    val modifiedBy = asset.modifiedBy.getOrElse("")

    val generatedFromMML = createdBy == AutoGeneratedUsername.generatedInUpdate || createdBy == AutoGeneratedUsername.mmlPavedDefault
    val notModified = modifiedBy == "" || modifiedBy == AutoGeneratedUsername.generatedInUpdate

    generatedFromMML && notModified
  }

  private def removePavement(operatio: OperationStep,changes: Seq[RoadLinkChange]): Option[OperationStep] = {
    def pavementShouldBeRemoved(asset: PersistedLinearAsset, surfaceTypeIsNone: Seq[String]): Boolean = {
      surfaceTypeIsNone.contains(asset.linkId) && isGeneratedFromMML(asset)
    }

    val assetsAll: Seq[PersistedLinearAsset] = operatio.assetsAfter
    val changeSets: ChangeSet = operatio.changeInfo.get
    val changesRemovePavement = changes.flatMap(_.newLinks).filter(_.surfaceType == SurfaceType.None).map(_.linkId)

    val (assetToBeRemoved, assetToPersist) = assetsAll.partition(a => pavementShouldBeRemoved(a, changesRemovePavement))
    val expiredPavementSteps = assetToBeRemoved.map(asset => {
        if (asset.id != 0) {
          operatio.copy(changeInfo = Some(changeSets.copy(expiredAssetIds = changeSets.expiredAssetIds ++ Set(asset.id))))
        } else {
          val originalAsset = operatio.assetsBefore.find(_.id == asset.oldId)
            .getOrElse(throw new NoSuchElementException(s"Could not find original asset for reporting," +
              s" asset.id: ${asset.id}, asset.oldId: ${asset.oldId}, asset.linkId: ${asset.linkId}"))
          reportAssetChanges(Some(originalAsset), None, changes,  operatio.copy(assetsAfter = Seq()), Some(ChangeTypeReport.Deletion))
        }
    })

    val combinedSteps = expiredPavementSteps.foldLeft(OperationStep(assetToPersist, Some(changeSets), operatio.assetsBefore))((a, b) => {
      OperationStep((a.assetsAfter ++ b.assetsAfter).distinct, Some(LinearAssetFiller.combineChangeSets(a.changeInfo.get, b.changeInfo.get)), b.assetsBefore)
    })
    Some(combinedSteps)
  }
  override def additionalOperations(operationStep: OperationStep, changes: Seq[RoadLinkChange]): Option[OperationStep] = {
    removePavement(operationStep,changes)
  }

}
