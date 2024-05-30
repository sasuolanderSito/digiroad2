package fi.liikennevirasto.digiroad2.util.assetUpdater

import fi.liikennevirasto.digiroad2.asset.ConstructionType.{ExpiringSoon, Planned, UnderConstruction}
import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.client.FeatureClass.WinterRoads
import fi.liikennevirasto.digiroad2.client.RoadLinkChangeType.{Remove, Replace, Split}
import fi.liikennevirasto.digiroad2.client.{FeatureClass, RoadLinkChange, RoadLinkChangeType}
import fi.liikennevirasto.digiroad2.dao.RoadLinkOverrideDAO.TrafficDirectionDao
import fi.liikennevirasto.digiroad2.dao.linearasset.PostGISSpeedLimitDao
import fi.liikennevirasto.digiroad2.linearasset.LinearAssetFiller._
import fi.liikennevirasto.digiroad2.linearasset._
import fi.liikennevirasto.digiroad2.service.linearasset.{Measures, NewSpeedLimitMassOperation, SpeedLimitService}
import fi.liikennevirasto.digiroad2.util.{KgvUtil, LogUtils}

class SpeedLimitUpdater(service: SpeedLimitService) extends DynamicLinearAssetUpdater(service) {

  val speedLimitDao = new PostGISSpeedLimitDao(roadLinkService)

  override def assetFiller: AssetFiller = SpeedLimitFiller

  private def isUnsupportedRoadLinkType(roadLink: RoadLink) = {
    !Seq(Municipality, State).contains(roadLink.administrativeClass) ||
      Seq(PedestrianZone, CycleOrPedestrianPath, ServiceAccess, SpecialTransportWithoutGate, SpecialTransportWithGate,
        CableFerry, ServiceOrEmergencyRoad, RestArea, TractorRoad, HardShoulder).contains(roadLink.linkType) ||
      Seq(Planned, UnderConstruction, ExpiringSoon).contains(roadLink.constructionType)
  }

  override def operationForNewLink(change: RoadLinkChange, onlyNeededNewRoadLinks: Seq[RoadLink], changeSets: ChangeSet): Option[OperationStep] = {
    val newLinkInfo = change.newLinks.head
    val filteredLinks = onlyNeededNewRoadLinks.filterNot(link => isUnsupportedRoadLinkType(link))
    val roadLinkFound = filteredLinks.exists(_.linkId == newLinkInfo.linkId)
    if(roadLinkFound) {
      service.persistUnknown(Seq(UnknownSpeedLimit(newLinkInfo.linkId, newLinkInfo.municipality.getOrElse(throw new NoSuchElementException(s"${newLinkInfo.linkId} does not have municipality code")), newLinkInfo.adminClass)))
      None
    } else None
  }

  private def updateUnknowns(operationStep: OperationStep, changes: Seq[RoadLinkChange]): Option[OperationStep] = {
    val oldLinkIds = changes.filter(c => Seq(Remove, Replace, Split).contains(c.changeType)).map(c => c.oldLink.get.linkId)
    val linkIdsWithExistingSpeedLimits = operationStep.assetsAfter.map(_.linkId)
    val newLinkIdsWithoutSpeedLimit = changes.filter(c => c.changeType == Replace || c.changeType == Split).flatMap(_.newLinks).map(_.linkId)
      .filterNot(linkId => linkIdsWithExistingSpeedLimits.contains(linkId))
    val roadLinksWithoutSpeedLimit = roadLinkService.getRoadLinksByLinkIds(newLinkIdsWithoutSpeedLimit.toSet, newTransaction = false)
    val (unSupportedLinks, supportedLinks) = roadLinksWithoutSpeedLimit.partition(link => isUnsupportedRoadLinkType(link))

    service.persistUnknown(supportedLinks.map(l => UnknownSpeedLimit(l.linkId, l.municipalityCode, l.administrativeClass)))
    service.purgeUnknown(unSupportedLinks.map(_.linkId).toSet, oldLinkIds, false)
    Some(operationStep)
  }

  override def additionalOperations(operationStep: OperationStep, changes: Seq[RoadLinkChange]): Option[OperationStep] = {
    updateUnknowns(operationStep, changes)
  }
  
  def isRealTrafficDirectionChange(change: RoadLinkChange): Boolean = {
    change.newLinks.exists(newLink => {
      val oldOriginalTrafficDirection = change.oldLink.get.trafficDirection
      val newOriginalTrafficDirection = newLink.trafficDirection
      val replaceInfo = change.replaceInfo.find(_.newLinkId.getOrElse("") == newLink.linkId).get
      val isDigitizationChange = replaceInfo.digitizationChange
      val overWrittenTdValueOnNewLink = TrafficDirectionDao.getExistingValue(newLink.linkId)

      if (overWrittenTdValueOnNewLink.nonEmpty) false
      else {
        if (isDigitizationChange) oldOriginalTrafficDirection != TrafficDirection.switch(newOriginalTrafficDirection)
        else oldOriginalTrafficDirection != newOriginalTrafficDirection
      }
    })
  }
  
  override def adjustLinearAssets(typeId: Int,roadLinks: Seq[RoadLinkForFillTopology], assets: Map[String, Seq[PieceWiseLinearAsset]],
                                  changeSet: Option[ChangeSet] = None): (Seq[PieceWiseLinearAsset], ChangeSet) = {
   assetFiller.fillTopologyChangesGeometry(roadLinks, assets, typeId, changeSet)
  }

  protected override def persistProjectedLinearAssets(newLinearAssets: Seq[PersistedLinearAsset], onlyNeededNewRoadLinks: Seq[RoadLink]): Unit = {
    service.createMultipleLinearAssetsSpeedLimit(newLinearAssets.map(createMassOperationRow))
    LogUtils.time(logger,s"purgeUnknown for newlimits ${newLinearAssets.size}"){
      service.purgeUnknown(newLinearAssets.map(_.linkId).toSet, Seq(), newTransaction = false)
    }
  }

  private def createMassOperationRow(a: PersistedLinearAsset): NewSpeedLimitMassOperation = {
    NewSpeedLimitMassOperation(
      a.createdBy.getOrElse(AutoGeneratedUsername.generatedInUpdate), a.typeId, a.linkId, Measures(a.startMeasure, a.endMeasure).roundMeasures(), SideCode(a.sideCode),
      service.getSpeedLimitValue(a.value), Some(a.timeStamp),
      a.createdDateTime, a.modifiedBy, a.modifiedDateTime, a.linkSource
    )
  }
}
