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
        startMeasure = 0, endMeasure = GeometryUtils.geometryLength(newLink.geometry), createdBy = None,
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

  private def removePavement(operatio: OperationStep,changes: Seq[RoadLinkChange]) = {
    val assetsAll: Seq[PersistedLinearAsset] = operatio.assetsAfter
    val changeSets: ChangeSet = operatio.changeInfo.get
    val changesRemovePavement = changes.flatMap(_.newLinks).filter(_.surfaceType == SurfaceType.None).map(_.linkId)
    val expiredPavement = assetsAll.filter(a => changesRemovePavement.contains(a.linkId)).map(asset => {
        if (asset.id != 0) {
          operatio.copy(changeInfo = Some(changeSets.copy(expiredAssetIds = changeSets.expiredAssetIds ++ Set(asset.id))))
        } else {
          reportAssetChanges(Some(asset), None, changes,  operatio.copy(assetsAfter = Seq(asset.copy(id = removePart))), Some(ChangeTypeReport.Deletion))
        }
    }).foldLeft(OperationStep(assetsAll, Some(changeSets)))((a, b) => {
      OperationStep((a.assetsAfter ++ b.assetsAfter).distinct, Some(LinearAssetFiller.combineChangeSets(a.changeInfo.get, b.changeInfo.get)), b.assetsBefore)
    })
    Some(expiredPavement)
  }
  override def additionalOperations(operationStep: OperationStep, changes: Seq[RoadLinkChange]): Option[OperationStep] = {
    removePavement(operationStep,changes)
  }

  override def filterChanges(changes: Seq[RoadLinkChange]): Seq[RoadLinkChange] = {
    val (remove, other) = changes.partition(_.changeType == RoadLinkChangeType.Remove)
    val linksOther = other.flatMap(_.newLinks.map(_.linkId)).toSet
    val filterChanges = if (linksOther.nonEmpty) {
      val links = roadLinkService.getExistingAndExpiredRoadLinksByLinkIds(linksOther,false)
      val filteredLinks = links.filter(_.functionalClass > 4).map(_.linkId)
      other.filter(p => filteredLinks.contains(p.newLinks.head.linkId))
    } else Seq()
    filterChanges ++ remove
  }

}
