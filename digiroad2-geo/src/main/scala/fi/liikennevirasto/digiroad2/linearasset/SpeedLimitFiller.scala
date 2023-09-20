package fi.liikennevirasto.digiroad2.linearasset

import fi.liikennevirasto.digiroad2.GeometryUtils
import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.linearasset.LinearAssetFiller._

object SpeedLimitFiller extends AssetFiller {

  def getOperations(geometryChanged: Boolean): Seq[(RoadLinkForFillTopology, Seq[PieceWiseLinearAsset], ChangeSet) => (Seq[PieceWiseLinearAsset], ChangeSet)] = {
    val fillOperations: Seq[(RoadLinkForFillTopology, Seq[PieceWiseLinearAsset], ChangeSet) => (Seq[PieceWiseLinearAsset], ChangeSet)] = Seq(
      debugLogging("start running fillTopology state now"),
      expireSegmentsOutsideGeometry,
      debugLogging("expireSegmentsOutsideGeometry"),
      combine,
      debugLogging("combine"),
      fuse,
      debugLogging("fuse"),
      adjustAssets,
      debugLogging("adjustAssets"),
      capToGeometry,
      debugLogging("capToGeometry"),
      droppedSegmentWrongDirection,
      debugLogging("droppedSegmentWrongDirection"),
      adjustSegmentSideCodes,
      debugLogging("adjustSegmentSideCodes"),
      dropShortSegments,
      debugLogging("dropShortSegments"),
      fillHoles,
      debugLogging("fillHoles"),
      generateTwoSidedNonExistingLinearAssets(SpeedLimitAsset.typeId),
      debugLogging("generateTwoSidedNonExistingLinearAssets"),
      generateOneSidedNonExistingLinearAssets(SideCode.TowardsDigitizing, SpeedLimitAsset.typeId),
      debugLogging("generateOneSidedNonExistingLinearAssets"),
      generateOneSidedNonExistingLinearAssets(SideCode.AgainstDigitizing, SpeedLimitAsset.typeId),
      debugLogging("generateOneSidedNonExistingLinearAssets"),
      clean,
      debugLogging("clean")
    )

    val adjustmentOperations: Seq[(RoadLinkForFillTopology, Seq[PieceWiseLinearAsset], ChangeSet) => (Seq[PieceWiseLinearAsset], ChangeSet)] = Seq(
      combine,
      fuse,
      adjustAssets,
      droppedSegmentWrongDirection,
      adjustSegmentSideCodes,
      dropShortSegments,
      fillHoles,
      generateTwoSidedNonExistingLinearAssets(SpeedLimitAsset.typeId),
      debugLogging("generateTwoSidedNonExistingLinearAssets"),
      generateOneSidedNonExistingLinearAssets(SideCode.TowardsDigitizing, SpeedLimitAsset.typeId),
      debugLogging("generateOneSidedNonExistingLinearAssets"),
      generateOneSidedNonExistingLinearAssets(SideCode.AgainstDigitizing, SpeedLimitAsset.typeId),
      debugLogging("generateOneSidedNonExistingLinearAssets"),
      clean)

    if(geometryChanged) fillOperations
    else adjustmentOperations
  }

  override def getGenerateUnknowns(typeId: Int): Seq[(RoadLinkForFillTopology, Seq[PieceWiseLinearAsset], ChangeSet) => (Seq[PieceWiseLinearAsset], ChangeSet)] = {
    Seq(
      generateTwoSidedNonExistingLinearAssets(SpeedLimitAsset.typeId),
      debugLogging("generateTwoSidedNonExistingLinearAssets"),
      generateOneSidedNonExistingLinearAssets(SideCode.TowardsDigitizing, SpeedLimitAsset.typeId),
      debugLogging("generateOneSidedNonExistingLinearAssets"),
      generateOneSidedNonExistingLinearAssets(SideCode.AgainstDigitizing, SpeedLimitAsset.typeId),
      debugLogging("generateOneSidedNonExistingLinearAssets")
    )
  }


  override protected def adjustLopsidedLimit(roadLink: RoadLinkForFillTopology, assets: Seq[PieceWiseLinearAsset], changeSet: ChangeSet): (Seq[PieceWiseLinearAsset], ChangeSet) = {
    val onlyLimitOnLink = assets.length == 1 && assets.head.sideCode != SideCode.BothDirections
    if (onlyLimitOnLink) {
      val segment = assets.head
      val sideCodeAdjustments = Seq(SideCodeAdjustment(segment.id, SideCode.BothDirections, SpeedLimitAsset.typeId))
      (Seq(segment.copy(sideCode = SideCode.BothDirections)), changeSet.copy(adjustedSideCodes = changeSet.adjustedSideCodes ++ sideCodeAdjustments))
    } else {
      (assets, changeSet)
    }
  }
  override def dropShortSegments(roadLink: RoadLinkForFillTopology, assets: Seq[PieceWiseLinearAsset], changeSet: ChangeSet): (Seq[PieceWiseLinearAsset], ChangeSet) = {
    val limitsToDrop = assets.filter { limit =>
      GeometryUtils.geometryLength(limit.geometry) < MinAllowedLength && roadLink.length > MinAllowedLength
    }.map(_.id).toSet
    val limits = assets.filterNot { x => limitsToDrop.contains(x.id) }
    (limits, changeSet.copy(droppedAssetIds = changeSet.droppedAssetIds ++ limitsToDrop))
  }

  override protected def generateTwoSidedNonExistingLinearAssets(typeId: Int)(roadLink: RoadLinkForFillTopology, segments: Seq[PieceWiseLinearAsset], changeSet: ChangeSet): (Seq[PieceWiseLinearAsset], ChangeSet) = {
    val lrmPositions: Seq[(Double, Double)] = segments.map { x => (x.startMeasure, x.endMeasure) }
    val remainders = lrmPositions.foldLeft(Seq((0.0, roadLink.length)))(GeometryUtils.subtractIntervalFromIntervals).filter { case (start, end) => math.abs(end - start) > 0.5 }
    val generatedLinearAssets =   if (roadLink.isSimpleCarTrafficRoad) {
      val generated = remainders.map { segment =>
        PersistedLinearAsset(0L, roadLink.linkId, 1, None, segment._1, segment._2, None, None, None, None, false, typeId, 0, None, roadLink.linkSource, None, None, None)
      }
      toLinearAsset(generated, roadLink)
    } else Seq.empty[PieceWiseLinearAsset]
    (segments ++ generatedLinearAssets, changeSet)
  }

  override protected def generateOneSidedNonExistingLinearAssets(sideCode: SideCode, typeId: Int)(roadLink: RoadLinkForFillTopology, segments: Seq[PieceWiseLinearAsset], changeSet: ChangeSet): (Seq[PieceWiseLinearAsset], ChangeSet) = {
    val generated = if (roadLink.trafficDirection == TrafficDirection.BothDirections && roadLink.isSimpleCarTrafficRoad) {
      val lrmPositions: Seq[(Double, Double)] = segments
        .filter { s => s.sideCode == sideCode || s.sideCode == SideCode.BothDirections }
        .map { x => (x.startMeasure, x.endMeasure) }
      val remainders = lrmPositions.foldLeft(Seq((0.0, roadLink.length)))(GeometryUtils.subtractIntervalFromIntervals).filter { case (start, end) => math.abs(end - start) > 0.5 }
      val persisted = remainders.map { segment =>
        PersistedLinearAsset(0L, roadLink.linkId, sideCode.value, None, segment._1, segment._2, None, None, None, None, false, typeId, 0, None, roadLink.linkSource, None, None, None)
      }
      toLinearAsset(persisted, roadLink)
    } else {
      Nil
    }
    (segments ++ generated, changeSet)
  }

  override def fillTopology(roadLinks: Seq[RoadLinkForFillTopology], speedLimits: Map[String, Seq[PieceWiseLinearAsset]], typeId:Int, changedSet: Option[ChangeSet] = None,
                            geometryChanged: Boolean = true): (Seq[PieceWiseLinearAsset], ChangeSet) = {
    val operations = getOperations(geometryChanged)
    // TODO: Do not create dropped asset ids but mark them expired when they are no longer valid or relevant
    val changeSet = LinearAssetFiller.useOrEmpty(changedSet)

    roadLinks.foldLeft(Seq.empty[PieceWiseLinearAsset], changeSet) { case (acc, roadLink) =>
      val (existingSegments, changeSet) = acc
      val segments = speedLimits.getOrElse(roadLink.linkId, Nil)
      val validSegments = segments.filterNot { segment => changeSet.droppedAssetIds.contains(segment.id) }

      val (adjustedSegments, segmentAdjustments) = operations.foldLeft(validSegments, changeSet) { case ((currentSegments, currentAdjustments), operation) =>
        operation(roadLink, currentSegments, currentAdjustments)
      }
      (existingSegments ++ adjustedSegments, segmentAdjustments)
    }
  }

  override def fillTopologyChangesGeometry(topology: Seq[RoadLinkForFillTopology], linearAssets: Map[String, Seq[PieceWiseLinearAsset]], typeId: Int,
                                           changedSet: Option[ChangeSet] = None): (Seq[PieceWiseLinearAsset], ChangeSet) = {
    val operations: Seq[(RoadLinkForFillTopology, Seq[PieceWiseLinearAsset], ChangeSet) => (Seq[PieceWiseLinearAsset], ChangeSet)] = Seq(
      debugLogging("operation start"),
      fuse,
      debugLogging("fuse"),
      dropShortSegments,
      debugLogging("dropShortSegments"),
      adjustAssets,
      debugLogging("adjustAssets"),
      expireOverlappingSegments,
      debugLogging("expireOverlappingSegments"),
      droppedSegmentWrongDirection,
      debugLogging("droppedSegmentWrongDirection"),
      adjustSegmentSideCodes,
      debugLogging("adjustSegmentSideCodes"),
      fillHoles,
      debugLogging("fillHoles"),
      clean
    )
    val changeSet = LinearAssetFiller.useOrEmpty(changedSet)
    // if links does not have any asset filter it away
    topology.filter(p => linearAssets.keySet.contains(p.linkId)).foldLeft(Seq.empty[PieceWiseLinearAsset], changeSet) { case (acc, roadLink) =>
      val (existingAssets, changeSet) = acc
      val assetsOnRoadLink = linearAssets.getOrElse(roadLink.linkId, Nil)

      val (adjustedAssets, assetAdjustments) = operations.foldLeft(assetsOnRoadLink, changeSet) { case ((currentSegments, currentAdjustments), operation) =>
        operation(roadLink, currentSegments, currentAdjustments)
      }
      val filterExpiredAway = assetAdjustments.copy(adjustedMValues = assetAdjustments.adjustedMValues.filterNot(p =>
        assetAdjustments.droppedAssetIds.contains(p.assetId) ||
          assetAdjustments.expiredAssetIds.contains(p.assetId)))

      val noDuplicate = filterExpiredAway.copy(
        adjustedMValues = filterExpiredAway.adjustedMValues.distinct,
        adjustedSideCodes = filterExpiredAway.adjustedSideCodes.distinct,
        valueAdjustments = filterExpiredAway.valueAdjustments.distinct
      )

      (existingAssets ++ adjustedAssets, noDuplicate)
    }
  }
  override def adjustSideCodes(roadLinks: Seq[RoadLinkForFillTopology], speedLimits: Map[String, Seq[PieceWiseLinearAsset]], typeId: Int, changedSet: Option[ChangeSet] = None): (Seq[PieceWiseLinearAsset], ChangeSet) = {
    // TODO: Do not create dropped asset ids but mark them expired when they are no longer valid or relevant
    val changeSet = LinearAssetFiller.useOrEmpty(changedSet)

    roadLinks.foldLeft(Seq.empty[PieceWiseLinearAsset], changeSet) { case (acc, roadLink) =>
      val (existingSegments, changeSet) = acc
      val segments = speedLimits.getOrElse(roadLink.linkId, Nil)
      val validSegments = segments.filterNot { segment => changeSet.droppedAssetIds.contains(segment.id) }

      val (adjustedSegments, segmentAdjustments) = getUpdateSideCodes.foldLeft(validSegments, changeSet) { case ((currentSegments, currentAdjustments), operation) =>
        operation(roadLink, currentSegments, currentAdjustments)
      }
      (existingSegments ++ adjustedSegments, segmentAdjustments)
    }
  }

  /**
    * For debugging; print speed limit relevant data
    * @param speedLimit speedlimit to print
    */
  def printSL(speedLimit: PieceWiseLinearAsset) = {
    val ids = "%d (%d)".format(speedLimit.id, speedLimit.linkId)
    val dir = speedLimit.sideCode match {
      case SideCode.BothDirections => "⇅"
      case SideCode.TowardsDigitizing => "↑"
      case SideCode.AgainstDigitizing => "↓"
      case _ => "?"
    }
    val details = "%d %.4f %.4f %s".format(speedLimit.value.getOrElse(SpeedLimitValue(0)), speedLimit.startMeasure, speedLimit.endMeasure, speedLimit.timeStamp.toString)
    if (speedLimit.expired) {
      println("N/A")
    } else {
      println("%s %s %s".format(ids, dir, details))
    }
  }

}