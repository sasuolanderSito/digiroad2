package fi.liikennevirasto.digiroad2.service.pointasset

import fi.liikennevirasto.digiroad2.PointAssetFiller.AssetAdjustment
import fi.liikennevirasto.digiroad2.{asset, _}
import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.asset.SideCode._
import fi.liikennevirasto.digiroad2.client.vvh.{ChangeInfo, VVHClient, VVHRoadlink}
import fi.liikennevirasto.digiroad2.dao.pointasset.{OracleTrafficSignDao, PersistedTrafficSign}
import fi.liikennevirasto.digiroad2.linearasset.{RoadLink, RoadLinkLike}
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.service.linearasset.{ManoeuvreCreationException, ManoeuvreProvider}
import fi.liikennevirasto.digiroad2.user.{User, UserProvider}
import org.slf4j.LoggerFactory
import org.joda.time.DateTime

case class IncomingTrafficSign(lon: Double, lat: Double, linkId: Long, propertyData: Set[SimpleTrafficSignProperty], validityDirection: Int, bearing: Option[Int]) extends IncomingPointAsset
case class AdditionalPanelInfo(id: Option[Long], mValue: Double, linkId: Long, propertyData: Set[SimpleTrafficSignProperty], validityDirection: Int)

class TrafficSignService(val roadLinkService: RoadLinkService, val userProvider: UserProvider, eventBusImpl: DigiroadEventBus) extends PointAssetOperations {

  def eventBus: DigiroadEventBus = eventBusImpl
  type IncomingAsset = IncomingTrafficSign
  type PersistedAsset = PersistedTrafficSign
  val logger = LoggerFactory.getLogger(getClass)

  implicit def dateTimeOrdering: Ordering[DateTime] = Ordering.fromLessThan(_ isBefore _ )

  override def typeId: Int = 300

  override def setAssetPosition(asset: IncomingTrafficSign, geometry: Seq[Point], mValue: Double): IncomingTrafficSign = {
    GeometryUtils.calculatePointFromLinearReference(geometry, mValue) match {
      case Some(point) =>
        asset.copy(lon = point.x, lat = point.y)
      case _ =>
        asset
    }
  }

  val typePublicId = "trafficSigns_type"
  val valuePublicId = "trafficSigns_value"
  val infoPublicId = "trafficSigns_info"
  val additionalPublicId = "additional_panel"
  private val counterPublicId = "counter"
  private val counterDisplayValue = "Merkkien määrä"
  private val batchProcessName = "batch_process_trafficSigns"
  private val groupingDistance = 2
  private val AdditionalPanelDistance = 2

  private val additionalInfoTypeGroups =
    Set(
      TrafficSignTypeGroup.GeneralWarningSigns,
      TrafficSignTypeGroup.ProhibitionsAndRestrictions,
      TrafficSignTypeGroup.AdditionalPanels
    )

  override def fetchPointAssets(queryFilter: String => String, roadLinks: Seq[RoadLinkLike]): Seq[PersistedTrafficSign] = OracleTrafficSignDao.fetchByFilter(queryFilter)

  override def setFloating(persistedAsset: PersistedTrafficSign, floating: Boolean): PersistedTrafficSign = {
    persistedAsset.copy(floating = floating)
  }

  override def create(asset: IncomingTrafficSign, username: String, roadLink: RoadLink): Long = {
    withDynTransaction {
      createWithoutTransaction(asset, username, roadLink)
    }
  }

  def createWithoutTransaction(asset: IncomingTrafficSign, username: String, roadLink: RoadLink): Long = {
    val mValue = GeometryUtils.calculateLinearReferenceFromPoint(Point(asset.lon, asset.lat), roadLink.geometry)
    val id = OracleTrafficSignDao.create(setAssetPosition(asset, roadLink.geometry, mValue), mValue, username, roadLink.municipalityCode, VVHClient.createVVHTimeStamp(), roadLink.linkSource)
    if (belongsToTurnRestriction(asset)) {
      eventBus.publish("manoeuvre:create", ManoeuvreProvider(getPersistedAssetsByIdsWithoutTransaction(Set(id)).head, roadLink))
    }
    id
  }

  def belongsToTurnRestriction(asset: IncomingTrafficSign)  = {
    val turnRestrictionsGroup =  Seq(NoUTurn, NoRightTurn, NoLeftTurn)
    turnRestrictionsGroup.contains(asset.propertyData.find(p => p.publicId == "trafficSigns_type").get.values.headOption.map(t => TrafficSignType.applyOTHValue(t.asInstanceOf[TextPropertyValue].propertyValue.toInt)).get)
  }

  def createFloating(asset: IncomingTrafficSign, username: String, municipality: Int): Long = {
    withDynTransaction {
      createFloatingWithoutTransaction(asset, username, municipality)
    }
  }

  def createFloatingWithoutTransaction(asset: IncomingTrafficSign, username: String, municipality: Int): Long = {
    OracleTrafficSignDao.createFloating(asset, 0, username, municipality, VVHClient.createVVHTimeStamp(), LinkGeomSource.Unknown, floating = true)
  }

  override def update(id: Long, updatedAsset: IncomingTrafficSign, roadLink: RoadLink, username: String): Long = {
    withDynTransaction {
      updateWithoutTransaction(id, updatedAsset, roadLink, username, None, None)
    }
  }

  def updateWithoutTransaction(id: Long, updatedAsset: IncomingTrafficSign, roadLink: RoadLink, username: String, mValue: Option[Double], vvhTimeStamp: Option[Long]): Long = {
    val value = mValue.getOrElse(GeometryUtils.calculateLinearReferenceFromPoint(Point(updatedAsset.lon, updatedAsset.lat), roadLink.geometry))
    getPersistedAssetsByIdsWithoutTransaction(Set(id)).headOption.getOrElse(throw new NoSuchElementException("Asset not found")) match {
      case old if old.bearing != updatedAsset.bearing || (old.lat != updatedAsset.lat || old.lon != updatedAsset.lon) || old.validityDirection != updatedAsset.validityDirection =>
        expireWithoutTransaction(id)
        val newId = OracleTrafficSignDao.create(setAssetPosition(updatedAsset, roadLink.geometry, value), value, username, roadLink.municipalityCode, vvhTimeStamp.getOrElse(VVHClient.createVVHTimeStamp()), roadLink.linkSource, old.createdBy, old.createdAt)
        if(belongsToTurnRestriction(updatedAsset)) {
          println(s"Creating manoeuvre on linkId: ${roadLink.linkId} from import traffic sign with id $newId" )
          eventBus.publish("manoeuvre:expire", id)
          eventBus.publish("manoeuvre:create", ManoeuvreProvider(getPersistedAssetsByIdsWithoutTransaction(Set(newId)).head, roadLink))
        }
        newId
      case _ =>
        OracleTrafficSignDao.update(id, setAssetPosition(updatedAsset, roadLink.geometry, value), value, roadLink.municipalityCode, username, Some(vvhTimeStamp.getOrElse(VVHClient.createVVHTimeStamp())), roadLink.linkSource)
    }
  }

  override def getByBoundingBox(user: User, bounds: BoundingRectangle): Seq[PersistedAsset] = {
    val (roadLinks, changeInfo) = roadLinkService.getRoadLinksWithComplementaryAndChangesFromVVH(bounds)
    val result = super.getByBoundingBox(user, bounds, roadLinks, changeInfo, floatingAdjustment(adjustmentOperation, createOperation))
    val (pc, others) = result.partition(asset => asset.createdBy.contains(batchProcessName) && asset.propertyData.find(_.publicId == typePublicId).get.values.head.asInstanceOf[TextPropertyValue].propertyValue.toInt == PedestrianCrossingSign.OTHvalue)

    sortCrossings(pc, Seq()) ++ others
  }

  def sortCrossings(sorted: Seq[PersistedAsset], result: Seq[PersistedAsset]): Seq[PersistedAsset] = {
    val centerSignOpt = sorted.headOption
    if(centerSignOpt.nonEmpty) {
      val centerSign = centerSignOpt.get
      val (inProximity, outsiders) = sorted.tail.partition(sign => centerSign.linkId == sign.linkId && centerSign.validityDirection == sign.validityDirection && GeometryUtils.withinTolerance(Seq(Point(centerSign.lon, centerSign.lat)), Seq(Point(sign.lon, sign.lat)), tolerance = groupingDistance))
      val counterProp = TrafficSignProperty(0, counterPublicId, PropertyTypes.ReadOnlyNumber, values = Seq(TextPropertyValue((1 + inProximity.size).toString, Some(counterDisplayValue))))
      val withCounter = centerSign.copy(propertyData = centerSign.propertyData ++ Seq(counterProp))
      sortCrossings(outsiders, result ++ Seq(withCounter))
    } else {
      result
    }
  }

  private def createOperation(asset: PersistedAsset, adjustment: AssetAdjustment): PersistedAsset = {
    createPersistedAsset(asset, adjustment)
  }

  private def adjustmentOperation(persistedAsset: PersistedAsset, adjustment: AssetAdjustment, roadLink: RoadLink): Long = {
    val updated = IncomingTrafficSign(adjustment.lon, adjustment.lat, adjustment.linkId,
      persistedAsset.propertyData.map(prop => SimpleTrafficSignProperty(prop.publicId, prop.values)).toSet,
      persistedAsset.validityDirection, persistedAsset.bearing)

    updateWithoutTransaction(adjustment.assetId, updated, roadLink,
      "vvh_generated", Some(adjustment.mValue), Some(adjustment.vvhTimeStamp))
  }

  override def getByMunicipality(municipalityCode: Int): Seq[PersistedAsset] = {
    val (roadLinks, changeInfo) = roadLinkService.getRoadLinksWithComplementaryAndChangesFromVVH(municipalityCode)
    val mapRoadLinks = roadLinks.map(l => l.linkId -> l).toMap
    getByMunicipality(mapRoadLinks, roadLinks, changeInfo, floatingAdjustment(adjustmentOperation, createOperation), withMunicipality(municipalityCode))
  }

  def withMunicipalityAndGroup(municipalityCode: Int, trafficSignTypes: Set[Int])(query: String): String = {
    withFilter(s"""where a.asset_type_id = $typeId and a.municipality_code = $municipalityCode
                    and a.id in( select at.id
                        from asset at
                        join property p on at.asset_type_id = p.asset_type_id
                        join single_choice_value scv on scv.asset_id = at.id and scv.property_id = p.id and p.property_type = 'single_choice'
                        join enumerated_value ev on scv.enumerated_value_id = ev.id and ev.value in (${trafficSignTypes.mkString(",")}))""")(query)

  }

  def getByMunicipalityAndGroup(municipalityCode: Int, groupName: TrafficSignTypeGroup): Seq[PersistedAsset] = {
    val (roadLinks, changeInfo) = roadLinkService.getRoadLinksWithComplementaryAndChangesFromVVH(municipalityCode)
    val mapRoadLinks = roadLinks.map(l => l.linkId -> l).toMap
    val assetTypes = TrafficSignType.apply(groupName)
    getByMunicipality(mapRoadLinks, roadLinks, changeInfo, floatingAdjustment(adjustmentOperation, createOperation), withMunicipalityAndGroup(municipalityCode, assetTypes))
  }

  def getByMunicipalityExcludeByAdminClass(municipalityCode: Int, filterAdministrativeClass: AdministrativeClass): Seq[PersistedAsset] = {
    val (roadLinks, changeInfo) = roadLinkService.getRoadLinksWithComplementaryAndChangesFromVVHByMunicipality(municipalityCode)
    val mapRoadLinks = roadLinks.map(l => l.linkId -> l).toMap
    val assets = getByMunicipality(mapRoadLinks, roadLinks, changeInfo, floatingAdjustment(adjustmentOperation, createOperation), withMunicipality(municipalityCode))
    val filterRoadLinks = mapRoadLinks.filterNot(_._2.administrativeClass == filterAdministrativeClass)
    assets.filter{a => filterRoadLinks.get(a.linkId).nonEmpty}
  }

  private def createPersistedAsset[T](persistedStop: PersistedAsset, asset: AssetAdjustment) = {
    new PersistedAsset(asset.assetId, asset.linkId, asset.lon, asset.lat,
      asset.mValue, asset.floating, persistedStop.vvhTimeStamp, persistedStop.municipalityCode, persistedStop.propertyData, persistedStop.createdBy,
      persistedStop.createdAt, persistedStop.modifiedBy, persistedStop.modifiedAt, persistedStop.validityDirection, persistedStop.bearing,
      persistedStop.linkSource)
  }

  def getAssetValidityDirection(bearing: Int): Int = {
    bearing >= 270 || bearing < 90 match {
      case true => AgainstDigitizing.value
      case false => TowardsDigitizing.value
    }
  }

  def getTrafficSignValidityDirection(assetLocation: Point, geometry: Seq[Point]): Int = {

    val mValue = GeometryUtils.calculateLinearReferenceFromPoint(Point(assetLocation.x, assetLocation.y, 0), geometry)
    val roadLinkPoint = GeometryUtils.calculatePointFromLinearReference(geometry, mValue)
    val linkBearing = GeometryUtils.calculateBearing(geometry)

    val lonDifference = assetLocation.x - roadLinkPoint.get.x
    val latDifference = assetLocation.y - roadLinkPoint.get.y

    (latDifference <= 0 && linkBearing <= 90) || (latDifference >= 0 && linkBearing > 270) match {
      case true => TowardsDigitizing.value
      case false => AgainstDigitizing.value
    }
  }

  def getAssetBearing(validityDirection: Int, geometry: Seq[Point]): Int = {
    val linkBearing = GeometryUtils.calculateBearing(geometry)
    GeometryUtils.calculateActualBearing(validityDirection, Some(linkBearing)).get
  }

  private def generateProperties(trafficSignType: TrafficSignType, value: Any, additionalInfo: String) = {
    val signValue = value.toString
    val signAdditionalInfo = additionalInfo

    val typeProperty = SimpleTrafficSignProperty(typePublicId, Seq(TextPropertyValue(trafficSignType.OTHvalue.toString)))
    val valueProperty = additionalInfoTypeGroups.exists(group => group == trafficSignType.group) match {
      case true => SimpleTrafficSignProperty(infoPublicId, Seq(TextPropertyValue(signAdditionalInfo)))
      case _ => SimpleTrafficSignProperty(valuePublicId, Seq(TextPropertyValue(signValue)))
    }

    Set(typeProperty, valueProperty)
  }

  def createFromCoordinates(lon: Long, lat: Long, trafficSignType: TrafficSignType, value: Option[Int],
                            twoSided: Option[Boolean], trafficDirection: TrafficDirection, bearing: Option[Int],
                            additionalInfo: Option[String], roadLinks: Seq[VVHRoadlink]): Long = {

      val closestLink: VVHRoadlink = roadLinks.minBy(r => GeometryUtils.minimumDistance(Point(lon, lat), r.geometry))
      val (vvhRoad, municipality) = (roadLinks.filter(_.administrativeClass != State), closestLink.municipalityCode)

      if (vvhRoad.isEmpty || vvhRoad.size > 1) {
        val asset = IncomingTrafficSign(lon, lat, 0, generateProperties(trafficSignType, value.getOrElse(""), additionalInfo.getOrElse("")), SideCode.Unknown.value, None)
        createFloatingWithoutTransaction(asset, userProvider.getCurrentUser().username, municipality)
      }
      else {
        roadLinkService.enrichRoadLinksFromVVH(Seq(closestLink)).map { roadLink =>
          val validityDirection =
            bearing match {
              case Some(assetBearing) if twoSided.getOrElse(false) => BothDirections.value
              case Some(assetBearing) => getAssetValidityDirection(assetBearing)
              case None if twoSided.getOrElse(false) => BothDirections.value
              case _ => getTrafficSignValidityDirection(Point(lon, lat), roadLink.geometry)
            }
          val newAsset = IncomingTrafficSign(lon, lat, roadLink.linkId, generateProperties(trafficSignType, value.getOrElse(""), additionalInfo.getOrElse("")), validityDirection, Some(GeometryUtils.calculateBearing(roadLink.geometry)))
          checkDuplicates(newAsset) match {
            case Some(existingAsset) =>
              updateWithoutTransaction(existingAsset.id, newAsset, roadLink, userProvider.getCurrentUser().username, None, None)
            case _ =>
              createWithoutTransaction(newAsset, userProvider.getCurrentUser().username, roadLink)
          }
        }
      }.head
  }

  def checkDuplicates(asset: IncomingTrafficSign): Option[PersistedTrafficSign] = {
    val signToCreateLinkId = asset.linkId
    val signToCreateType = getTrafficSignsProperties(asset, typePublicId).get.asInstanceOf[TextPropertyValue].propertyValue.toInt
    val signToCreateDirection = asset.validityDirection
    val groupType = Some(TrafficSignTypeGroup.apply(signToCreateType))

    val trafficSignsInRadius = getTrafficSignByRadius(Point(asset.lon, asset.lat), 10, groupType).filter(
      ts =>
        getTrafficSignsProperties(ts, typePublicId).get.asInstanceOf[TextPropertyValue].propertyValue.toInt == signToCreateType
          && ts.linkId == signToCreateLinkId && ts.validityDirection == signToCreateDirection
    )

    if (trafficSignsInRadius.nonEmpty) {
      return Some(getLatestModifiedAsset(trafficSignsInRadius))
    }
    None
  }

  def getTrafficSignByRadius(position: Point, meters: Int, optGroupType: Option[TrafficSignTypeGroup] = None): Seq[PersistedTrafficSign] = {
    val assets = OracleTrafficSignDao.fetchByRadius(position, meters)
    optGroupType match {
      case Some(groupType) => assets.filter(asset => TrafficSignType.applyOTHValue(asset.propertyData.find(p => p.publicId == "trafficSigns_type").get.values.head.asInstanceOf[TextPropertyValue].propertyValue.toInt).group == groupType)
      case _ => assets
    }
  }

  def getTrafficSign(linkIds : Seq[Long]): Seq[PersistedTrafficSign] = {
    OracleTrafficSignDao.fetchByLinkId(linkIds)
  }

  def getTrafficSignsWithTrafficRestrictions( municipality: Int, newTransaction: Boolean = true): Seq[PersistedTrafficSign] = {
    val enumeratedValueIds = getRestrictionsEnumeratedValues(newTransaction)
    if(newTransaction)
        withDynSession {
          OracleTrafficSignDao.fetchByTurningRestrictions(enumeratedValueIds, municipality)
        }
    else {
      OracleTrafficSignDao.fetchByTurningRestrictions(enumeratedValueIds, municipality)
    }
  }


  private def getRestrictionsEnumeratedValues(newTransaction: Boolean = true): Seq[Long] = {
    if(newTransaction)
      withDynSession {
        OracleTrafficSignDao.fetchEnumeratedValueIds(Seq(NoLeftTurn, NoRightTurn, NoUTurn))
      }
    else {
      OracleTrafficSignDao.fetchEnumeratedValueIds(Seq(NoLeftTurn, NoRightTurn, NoUTurn))
    }
  }

  override def expire(id: Long, username: String): Long = {
    withDynSession {
      expireWithoutTransaction(id, username)
    }
    eventBus.publish("manoeuvre:expire", id)
    id
  }

  def expireAssetWithoutTransaction(id: Long, username: String): Long = {
    expireWithoutTransaction(id)
    eventBus.publish("manoeuvre:expire", id)
    id
  }

  def getLatestModifiedAsset(trafficSigns: Seq[PersistedTrafficSign]): PersistedTrafficSign = {
    trafficSigns.maxBy { ts => ts.modifiedAt.getOrElse(ts.createdAt.get) }
  }

  def getTrafficSignsProperties(trafficSign: PersistedTrafficSign, property: String) : Option[PointAssetValue] = {
    trafficSign.propertyData.find(p => p.publicId == property).get.values.headOption
  }

  def getTrafficSignsProperties(trafficSign: IncomingTrafficSign, property: String) : Option[PointAssetValue] = {
    trafficSign.propertyData.find(p => p.publicId == property).get.values.headOption
  }

  def getAllTrafficSignsProperties(trafficSign: PersistedTrafficSign, property: String) : Seq[PointAssetValue] = {
    trafficSign.propertyData.find(p => p.publicId == property).get.values
  }

  def getTrafficSignsByDistance(sign: PersistedAsset, groupedAssets: Map[Long, Seq[PersistedAsset]], distance: Int): Seq[PersistedTrafficSign]={
    val sameLinkAssets = groupedAssets.getOrElse(sign.linkId, Seq())

    sameLinkAssets.filter{ ts =>
      (getTrafficSignsProperties(ts, typePublicId).get.asInstanceOf[TextPropertyValue].propertyValue.toInt == getTrafficSignsProperties(sign, typePublicId).get.asInstanceOf[TextPropertyValue].propertyValue.toInt) &&
        ts.validityDirection == sign.validityDirection &&
        GeometryUtils.geometryLength(Seq(Point(sign.lon, sign.lat), Point(ts.lon, ts.lat))) <= distance
    }
  }

  override def expireAssetsByMunicipalities(municipalityCodes: Set[Int]) : Unit = {
    OracleTrafficSignDao.expireAssetsByMunicipality(municipalityCodes)
  }


  protected def getPointOfInterest(first: Point, last: Point, sideCode: SideCode): Seq[Point] = {
    sideCode match {
      case SideCode.TowardsDigitizing => Seq(last)
      case SideCode.AgainstDigitizing => Seq(first)
      case _ => Seq(first, last)
    }
  }

  def getAdditionalPanels(linkId: Long, mValue: Double, validityDirection: Int, signPropertyValue: Int, geometry: Seq[Point],
                          additionalPanels: Seq[AdditionalPanelInfo], roadLinks: Seq[VVHRoadlink]) : Set[AdditionalPanelInfo] = {
    val allowedAdditionalPanels = additionalPanels.filter {panel =>
      val trafficSign =  TrafficSignType.applyOTHValue(signPropertyValue)
      val additionalPanel =  TrafficSignType.applyOTHValue(panel.propertyData.find(p => p.publicId == typePublicId).get.values.map(_.asInstanceOf[TextPropertyValue].propertyValue.toInt).head).asInstanceOf[AdditionalPanelsType]
      trafficSign.allowed(additionalPanel)
    }

    val (first, last) = GeometryUtils.geometryEndpoints(geometry)

    Seq(first, last).flatMap { point =>
      val signDistance = if (GeometryUtils.areAdjacent(point, first)) mValue else GeometryUtils.geometryLength(geometry) - mValue
      if (signDistance > AdditionalPanelDistance) {
        allowedAdditionalPanels.filter { additionalPanel =>
          Math.abs(additionalPanel.mValue - mValue) <= AdditionalPanelDistance &&
            additionalPanel.validityDirection == validityDirection &&
            additionalPanel.linkId == linkId
        }
      } else {
        //TODO check if is the most correct method
        val adjacentRoadLinkInfo = if (roadLinks.isEmpty) {
          roadLinkService.getAdjacent(linkId, Seq(point), false).map { adjacentRoadLink =>
            val (start, end) = GeometryUtils.geometryEndpoints(adjacentRoadLink.geometry)
            (start, end, adjacentRoadLink.linkId, adjacentRoadLink.geometry)
          }
        }
        else {
          getAdjacent(point: Point, roadLinks: Seq[VVHRoadlink]).map { case (start, end, roadLink) =>
            (start, end, roadLink.linkId, roadLink.geometry)
          }
        }

        if (adjacentRoadLinkInfo.isEmpty) {
          allowedAdditionalPanels.filter { additionalPanel =>
            Math.abs(additionalPanel.mValue - mValue) <= AdditionalPanelDistance &&
              additionalPanel.validityDirection == validityDirection &&
              additionalPanel.linkId == linkId
          }
        } else if (adjacentRoadLinkInfo.size == 1) {
          allowedAdditionalPanels.filter(additionalSign => additionalSign.validityDirection == validityDirection && additionalSign.linkId == linkId) ++
            allowedAdditionalPanels.filter { additionalSign =>
              val additionalPanelDistance = if (GeometryUtils.areAdjacent(adjacentRoadLinkInfo.map(_._1).head, point)) additionalSign.mValue else GeometryUtils.geometryLength(adjacentRoadLinkInfo.map(_._4).head) - additionalSign.mValue
              additionalSign.validityDirection == validityDirection && additionalSign.linkId == adjacentRoadLinkInfo.map(_._3).head && (additionalPanelDistance + signDistance) <= AdditionalPanelDistance
            }
        } else
          Seq()
      }
    }.toSet
  }

  def getAdjacent(point: Point, roadLinks : Seq[VVHRoadlink]): Seq[(Point, Point, VVHRoadlink)] = {
    roadLinks.flatMap{ roadLink =>
      val (first, last) = GeometryUtils.geometryEndpoints(roadLink.geometry)
      if(GeometryUtils.areAdjacent(point, first) || GeometryUtils.areAdjacent(point, last))
        Seq((first, last, roadLink))
      else
        Seq()
    }
  }
}
