package fi.liikennevirasto.digiroad2.service.pointasset

import fi.liikennevirasto.digiroad2._
import fi.liikennevirasto.digiroad2.asset.{AutoGeneratedUsername, SimplePointAssetProperty}
import fi.liikennevirasto.digiroad2.client.RoadLinkInfo
import fi.liikennevirasto.digiroad2.dao.pointasset.{DirectionalTrafficSign, PostGISDirectionalTrafficSignDao}
import fi.liikennevirasto.digiroad2.linearasset.{LinkId, RoadLink, RoadLinkLike}
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import org.joda.time.DateTime

case class IncomingDirectionalTrafficSign(lon: Double, lat: Double, linkId: String, validityDirection: Int, bearing: Option[Int], propertyData: Set[SimplePointAssetProperty], mValue: Option[Double] = None) extends IncomingPointAsset


class DirectionalTrafficSignService(val roadLinkService: RoadLinkService) extends PointAssetOperations {
  type IncomingAsset = IncomingDirectionalTrafficSign
  type PersistedAsset = DirectionalTrafficSign

  override def typeId: Int = 240
  override def fetchPointAssetsWithExpired(queryFilter: String => String, roadLinks: Seq[RoadLinkLike]): Seq[DirectionalTrafficSign] = { throw new UnsupportedOperationException("Not Supported Method") }
  override def fetchPointAssetsWithExpiredLimited(queryFilter: String => String, token: Option[String]): Seq[DirectionalTrafficSign] =  throw new UnsupportedOperationException("Not Supported Method")

  override def setAssetPosition(asset: IncomingDirectionalTrafficSign, geometry: Seq[Point], mValue: Double): IncomingDirectionalTrafficSign = {
    GeometryUtils.calculatePointFromLinearReference(geometry, mValue) match {
      case Some(point) =>
        asset.copy(lon = point.x, lat = point.y)
      case _ =>
        asset
    }
  }

  override def fetchPointAssets(queryFilter: String => String, roadLinks: Seq[RoadLinkLike]): Seq[DirectionalTrafficSign] = {
    val assets = PostGISDirectionalTrafficSignDao.fetchByFilter(queryFilter)
    assets.map { asset =>
      asset.copy(geometry = roadLinks.find(_.linkId == asset.linkId).map(_.geometry).getOrElse(Nil))}
  }

  def createFromCoordinates(incomingDirectionalTrafficSign: IncomingDirectionalTrafficSign, roadLink: RoadLink, username: String, isFloating: Boolean): Long = {
    if(isFloating)
      createFloatingWithoutTransaction(incomingDirectionalTrafficSign.copy(linkId = LinkId.Unknown.value), username, roadLink)
    else {
      checkDuplicates(incomingDirectionalTrafficSign) match {
        case Some(existingAsset) =>
          updateWithoutTransaction(existingAsset.id, incomingDirectionalTrafficSign, roadLink, username)
        case _ =>
          create(incomingDirectionalTrafficSign, username, roadLink, false)
      }
    }
  }

  def checkDuplicates(incomingDirectionalTrafficSign: IncomingDirectionalTrafficSign): Option[DirectionalTrafficSign] = {
    val position = Point(incomingDirectionalTrafficSign.lon, incomingDirectionalTrafficSign.lat)
    val signsInRadius = PostGISDirectionalTrafficSignDao.fetchByFilter(withBoundingBoxFilter(position, TwoMeters))
      .filter(sign => GeometryUtils.geometryLength(Seq(position, Point(sign.lon, sign.lat))) <= TwoMeters
        && Math.abs(sign.bearing.getOrElse(0) - incomingDirectionalTrafficSign.bearing.getOrElse(0)) <= BearingLimit)

    if(signsInRadius.nonEmpty) Some(getLatestModifiedAsset(signsInRadius)) else  None
  }

  def getLatestModifiedAsset(signs: Seq[DirectionalTrafficSign]): DirectionalTrafficSign = {
    signs.maxBy(sign => sign.modifiedAt.getOrElse(sign.createdAt.get).getMillis)
  }


  override def setFloating(persistedAsset: DirectionalTrafficSign, floating: Boolean) = {
    persistedAsset.copy(floating = floating)
  }

  override def create(asset: IncomingDirectionalTrafficSign, username: String, roadLink: RoadLink, newTransaction: Boolean): Long = {
    val mValue = GeometryUtils.calculateLinearReferenceFromPoint(Point(asset.lon, asset.lat), roadLink.geometry)
    if(newTransaction) {
      withDynTransaction {
        PostGISDirectionalTrafficSignDao.create(setAssetPosition(asset, roadLink.geometry, mValue), mValue, roadLink.municipalityCode ,username, false)
      }
    } else {
      PostGISDirectionalTrafficSignDao.create(setAssetPosition(asset, roadLink.geometry, mValue), mValue, roadLink.municipalityCode ,username, false)
    }
  }

  override def update(id: Long, updatedAsset: IncomingDirectionalTrafficSign, roadLink: RoadLink, username: String): Long = {
    withDynTransaction {
      updateWithoutTransaction(id, updatedAsset, roadLink, username)
    }
  }

  def createFloatingWithoutTransaction(asset: IncomingDirectionalTrafficSign, username: String, roadLink: RoadLink): Long = {
    val mValue = GeometryUtils.calculateLinearReferenceFromPoint(Point(asset.lon, asset.lat), roadLink.geometry)
    PostGISDirectionalTrafficSignDao.create(setAssetPosition(asset, roadLink.geometry, mValue), mValue, roadLink.municipalityCode ,username, true)
  }

  def updateWithoutTransaction(id: Long, updatedAsset: IncomingDirectionalTrafficSign, roadLink: RoadLink,  username: String): Long = {
    val mValue = updatedAsset.mValue.getOrElse(GeometryUtils.calculateLinearReferenceFromPoint(Point(updatedAsset.lon, updatedAsset.lat), roadLink.geometry))
    updateWithoutTransaction(id, updatedAsset, Some(mValue), roadLink.geometry, roadLink.municipalityCode, username)
  }

  def updateWithoutTransaction(id: Long, updatedAsset: IncomingDirectionalTrafficSign, mValue: Option[Double],
                               linkGeom: Seq[Point], linkMunicipality: Int, username: String, fromPointAssetUpdater: Boolean = false): Long = {
    val value = mValue.getOrElse(GeometryUtils.calculateLinearReferenceFromPoint(Point(updatedAsset.lon, updatedAsset.lat), linkGeom))
    getPersistedAssetsByIdsWithoutTransaction(Set(id)).headOption.getOrElse(throw new NoSuchElementException("Asset not found")) match {
      case old if old.bearing != updatedAsset.bearing || ( old.lat != updatedAsset.lat || old.lon != updatedAsset.lon) =>
        expireWithoutTransaction(id)
        PostGISDirectionalTrafficSignDao.create(setAssetPosition(updatedAsset, linkGeom, value), value,
          linkMunicipality, username, old.createdBy, old.createdAt, old.externalId, fromPointAssetUpdater, old.modifiedBy, old.modifiedAt)
      case _ =>
        PostGISDirectionalTrafficSignDao.update(id, updatedAsset, value,
          linkMunicipality, username, fromPointAssetUpdater)
    }
  }

  override def createOperation(asset: PersistedAsset, adjustment: AssetUpdate): PersistedAsset = {
    val validityDirection = adjustment.validityDirection.getOrElse(asset.validityDirection)
    new PersistedAsset(adjustment.assetId, adjustment.linkId, adjustment.lon, adjustment.lat, adjustment.mValue,
      adjustment.floating, asset.timeStamp, asset.municipalityCode, asset.propertyData, validityDirection,
      adjustment.bearing, asset.createdBy, asset.createdAt, asset.modifiedBy, asset.modifiedAt,
      linkSource = asset.linkSource, externalId = asset.externalId)
  }

  override def adjustmentOperation(persistedAsset: PersistedAsset, adjustment: AssetUpdate, link: RoadLinkInfo): Long = {
    val validityDirection = adjustment.validityDirection.getOrElse(persistedAsset.validityDirection)
    val updated = IncomingDirectionalTrafficSign(adjustment.lon, adjustment.lat, adjustment.linkId, validityDirection,
      adjustment.bearing, persistedAsset.propertyData.map(prop => SimplePointAssetProperty(prop.publicId, prop.values)).toSet)
    updateWithoutTransaction(adjustment.assetId, updated, Some(adjustment.mValue), link.geometry, link.municipality.getOrElse(throw new NoSuchElementException(s"${link.linkId} does not have municipality code")),
      persistedAsset.createdBy.getOrElse(AutoGeneratedUsername.generatedInUpdate), true)
  }

  override def getChanged(sinceDate: DateTime, untilDate: DateTime, token: Option[String] = None): Seq[ChangedPointAsset] = { throw new UnsupportedOperationException("Not Supported Method") }
}


