package fi.liikennevirasto.digiroad2.util.assetUpdater

import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.client.{RoadLinkChange, RoadLinkChangeType}
import fi.liikennevirasto.digiroad2.dao.RoadLinkOverrideDAO.{FunctionalClassDao, TrafficDirectionDao}
import fi.liikennevirasto.digiroad2.dao.linearasset.PostGISSpeedLimitDao
import fi.liikennevirasto.digiroad2.linearasset.LinearAssetFiller._
import fi.liikennevirasto.digiroad2.linearasset._
import fi.liikennevirasto.digiroad2.service.linearasset.{Measures, SpeedLimitService}

class SpeedLimitUpdater(service: SpeedLimitService) extends DynamicLinearAssetUpdater(service) {

  val speedLimitDao = new PostGISSpeedLimitDao(roadLinkService)

  override def assetFiller: AssetFiller = SpeedLimitFiller
  
  override def operationForNewLink(change: RoadLinkChange, assetsAll: Seq[PersistedLinearAsset], changeSets: ChangeSet): Option[OperationStep] = {
    service.persistUnknown(Seq(UnknownSpeedLimit(change.newLinks.head.linkId, change.newLinks.head.municipality, change.newLinks.head.adminClass)),newTransaction = false)
    None
  }

  override def additionalRemoveOperationMass(expiredLinks:Seq[String]): Unit = {
    service.purgeUnknown(Set(),expiredLinks,newTransaction = false)
  }

  override def additionalUpdateOrChange(change: RoadLinkChange, assetsAll: Seq[PersistedLinearAsset], changeSets: ChangeSet): Option[OperationStep] = {
    change.changeType match {
      case RoadLinkChangeType.Replace | RoadLinkChangeType.Split => {
        val oldLinkIds = change.oldLink.get.linkId
        val trafficDirectionChanged = isRealTrafficDirectionChange(change)
        val oldUnknownLSpeedLimit = service.getUnknownByLinkIds(Set(oldLinkIds),newTransaction = false)
        if (oldUnknownLSpeedLimit.nonEmpty || trafficDirectionChanged) {
          service.purgeUnknown(Set(),oldUnknownLSpeedLimit.map(_.linkId),newTransaction = false) // recreate unknown speedlimit
          service.persistUnknown(change.newLinks.map(l=>UnknownSpeedLimit(l.linkId, l.municipality, l.adminClass)),newTransaction = false)
        }
        None
      }
      case _ => None
    }
  }
  
  def isRealTrafficDirectionChange(change: RoadLinkChange): Boolean = {
    change.newLinks.exists(newLink => {
      val oldOriginalTrafficDirection = change.oldLink.get.trafficDirection
      val newOriginalTrafficDirection = newLink.trafficDirection
      val replaceInfo = change.replaceInfo.find(_.newLinkId == newLink.linkId).get
      val isDigitizationChange = replaceInfo.digitizationChange
      val overWrittenTdValueOnNewLink = TrafficDirectionDao.getExistingValue(newLink.linkId)

      if (overWrittenTdValueOnNewLink.nonEmpty) false
      else {
        if (isDigitizationChange) oldOriginalTrafficDirection != TrafficDirection.switch(newOriginalTrafficDirection)
        else oldOriginalTrafficDirection != newOriginalTrafficDirection
      }
    })
  }
  
  override def filterChanges(changes: Seq[RoadLinkChange]): Seq[RoadLinkChange] = {
    val (remove, other) = changes.partition(_.changeType == RoadLinkChangeType.Remove)
    val linksOther = other.flatMap(_.newLinks.map(_.linkId)).toSet
    val filterChanges = if (linksOther.nonEmpty) {
      val links = roadLinkService.getRoadLinksAndComplementariesByLinkIds(linksOther,false)
      val filteredLinks = links.filter(_.functionalClass > 4).map(_.linkId)
      other.filter(p => filteredLinks.contains(p.newLinks.head.linkId))
    } else  Seq()
    
    filterChanges ++ remove
  }
  
  override def adjustLinearAssetsOnChangesGeometry(roadLinks: Seq[RoadLinkForFillTopology], linearAssets: Map[String, Seq[PieceWiseLinearAsset]],
                                                   typeId: Int, changeSet: Option[ChangeSet] = None, counter: Int = 1): (Seq[PieceWiseLinearAsset], ChangeSet) = {
   assetFiller.fillTopologyChangesGeometry(roadLinks, linearAssets, typeId, changeSet)
  }

  override def persistProjectedLinearAssets(newLinearAssets: Seq[PersistedLinearAsset]): Unit = {
    val (newlimits, changedlimits) = newLinearAssets.partition(_.id <= 0)
    newlimits.foreach { limit =>
      speedLimitDao.createSpeedLimit(limit.createdBy.getOrElse(AutoGeneratedUsername.generatedInUpdate), limit.linkId, Measures(limit.startMeasure, limit.endMeasure).roundMeasures(),
        SideCode(limit.sideCode), service.getSpeedLimitValue(limit.value).get, Some(limit.timeStamp), limit.createdDateTime, limit.modifiedBy,
        limit.modifiedDateTime, limit.linkSource)
    }
    service.purgeUnknown(newLinearAssets.map(_.linkId).toSet, Seq())
  }
  
  override def adjustedSideCode(adjustment: SideCodeAdjustment): Unit = {
    val oldSpeedLimit = service.getPersistedSpeedLimitById(adjustment.assetId, newTransaction = false).getOrElse(throw new IllegalStateException("Asset no longer available"))

    service.expireAsset(SpeedLimitAsset.typeId, oldSpeedLimit.id, AutoGeneratedUsername.generatedInUpdate,true,  false)
    val newId = service.createWithoutTransaction(Seq(NewLimit(oldSpeedLimit.linkId, oldSpeedLimit.startMeasure, oldSpeedLimit.endMeasure)), service.getSpeedLimitValue(oldSpeedLimit.value).get, AutoGeneratedUsername.generatedInUpdate, adjustment.sideCode)
    logger.info("SideCodeAdjustment newID" + newId)
  }
}
