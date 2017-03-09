package fi.liikennevirasto.digiroad2

import fi.liikennevirasto.digiroad2.linearasset.{RoadLink, RoadLinkLike}
import fi.liikennevirasto.digiroad2.util.MunicipalityCodeImporter
import org.joda.time.DateTime


object PointAssetFiller {
  case class testPersistedPointAsset(id: Long, lon: Double, lat: Double, municipalityCode: Int, linkId: Long, mValue: Double, floating: Boolean) extends PersistedPointAsset


  case class AssetAdjustment(assetId: Long, lon: Double, lat: Double, linkId: Long, mValue: Double, floating: Boolean)
  private val MaxDistanceDiffAllowed = 3.0

  def correctRoadLinkAndGeometry(asset: PersistedPointAsset , roadLinks: Seq[RoadLink], changeInfos: Seq[ChangeInfo]) : Option [AssetAdjustment] = {
    val pointAssetLastChange = changeInfos.filter(_.oldId.getOrElse(0L) == asset.linkId).head
    val newRoadLink = roadLinks.filter(_.linkId == pointAssetLastChange.newId.getOrElse(0L)).head
    val typed = ChangeType.apply(pointAssetLastChange.changeType)

    ChangeType.apply(pointAssetLastChange.changeType) match {
      case ChangeType.ShortenedCommonPart | ChangeType.ShortenedRemovedPart => {

        val points = GeometryUtils.geometryEndpoints(newRoadLink.geometry)
        val assetPoint = Point(asset.lon, asset.lat)
        val pointToIni = Seq(assetPoint, points._1)
        val pointToEnd = Seq(assetPoint, points._2)
        val distBetweenPointEnd = GeometryUtils.geometryLength(pointToEnd)

        val newAssetPoint = GeometryUtils.geometryLength(pointToIni) match {

          case iniDist if (iniDist > MaxDistanceDiffAllowed && distBetweenPointEnd <= MaxDistanceDiffAllowed) => points._2
          case iniDist if (iniDist <= MaxDistanceDiffAllowed && iniDist <= distBetweenPointEnd) => points._1
          case iniDist if (iniDist <= MaxDistanceDiffAllowed && iniDist > distBetweenPointEnd) => points._2
          case iniDist if (iniDist <= MaxDistanceDiffAllowed) => points._1
          case _ => return None
        }

        val mValue = GeometryUtils.calculateLinearReferenceFromPoint(newAssetPoint, newRoadLink.geometry)

        Some(AssetAdjustment(asset.id, newAssetPoint.x, newAssetPoint.y, newRoadLink.linkId, mValue, false))
      }
        case _ => None
      }
  }

  def correctOnlyGeometry(asset: PersistedPointAsset, roadLinks: Seq[RoadLink], changeInfos: Seq[ChangeInfo]): Option[AssetAdjustment] = {
    val pointAssetLastChange = changeInfos.filter(_.oldId.getOrElse(0L) == asset.linkId).groupBy(_.oldId.get)

    if (!pointAssetLastChange.isEmpty) {
      pointAssetLastChange.map {
        case (linkId, filteredChangeInfos) =>
          ChangeType.apply(filteredChangeInfos.head.changeType) match {

            case ChangeType.CombinedModifiedPart | ChangeType.CombinedRemovedPart
              if filteredChangeInfos.head.newId != filteredChangeInfos.head.oldId => //Geometry Combined
              val newRoadLink = roadLinks.find(_.linkId == filteredChangeInfos.head.newId.getOrElse(0L)).get
              correctCombinedGeometry(asset, newRoadLink.linkId, newRoadLink.geometry)

            case ChangeType.LenghtenedCommonPart | ChangeType.LengthenedNewPart => //Geometry Lengthened
              filteredChangeInfos.map {
                case (filteredChangeInfo) =>
                  if (asset.mValue < filteredChangeInfo.newStartMeasure.getOrElse(0.0)) {
                    val newRoadLink = filteredChangeInfo.newId.getOrElse(0L)
                    val newMValue = asset.mValue + filteredChangeInfo.newStartMeasure.getOrElse(0.0)
                    correctLengthenedGeometry(asset, newRoadLink, newMValue)
                  } else {
                    None
                  }
              }.head

            case ChangeType.DividedModifiedPart | ChangeType.DividedNewPart => //Geometry Divided
              val newRoadLinkChangeInfo = filteredChangeInfos.filter(fci => fci.oldId != fci.newId).head
              if (asset.mValue >= newRoadLinkChangeInfo.newStartMeasure.getOrElse(0.0)) {
                val newMValue = asset.mValue - newRoadLinkChangeInfo.newStartMeasure.getOrElse(0.0)
                val newRoadLink = roadLinks.find(_.linkId == newRoadLinkChangeInfo.newId.getOrElse(0L)).get
                correctDividedGeometry(asset, newRoadLink.linkId, newMValue)
              } else {
                None
              }

            case ChangeType.ShortenedCommonPart | ChangeType.ShortenedRemovedPart => //Geometry Shortened
              correctRoadLinkAndGeometry(asset, roadLinks, changeInfos)

            case _ => None
          }
        case _ => None
      }.head
    } else {
      None
    }
  }

  private def correctCombinedGeometry(asset: PersistedPointAsset, newRoadLinkId: Long, newRoadLinkGeometry: Seq[Point]): Option[AssetAdjustment] = {
    val newAssetPoint = Point(asset.lon, asset.lat)
    val mValue = GeometryUtils.calculateLinearReferenceFromPoint(newAssetPoint, newRoadLinkGeometry)
    Some(AssetAdjustment(asset.id, newAssetPoint.x, newAssetPoint.y, newRoadLinkId, mValue, false))
  }

  private def correctDividedGeometry(asset: PersistedPointAsset, newRoadLinkId: Long, newMValue: Double): Option[AssetAdjustment] = {
    val newAssetPoint = Point(asset.lon, asset.lat)
    Some(AssetAdjustment(asset.id, newAssetPoint.x, newAssetPoint.y, newRoadLinkId, newMValue, false))
  }

  private def correctLengthenedGeometry(asset: PersistedPointAsset, newRoadLinkId: Long, newMValue: Double): Option[AssetAdjustment] = {
    val newAssetPoint = Point(asset.lon, asset.lat)
    Some(AssetAdjustment(asset.id, newAssetPoint.x, newAssetPoint.y, newRoadLinkId, newMValue, false))
  }

}