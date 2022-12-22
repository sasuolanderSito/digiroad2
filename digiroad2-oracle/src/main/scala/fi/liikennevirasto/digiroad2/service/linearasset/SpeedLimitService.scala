package fi.liikennevirasto.digiroad2.service.linearasset

import java.util.NoSuchElementException
import fi.liikennevirasto.digiroad2.GeometryUtils.Projection
import fi.liikennevirasto.digiroad2._
import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.client.RoadLinkFetched
import fi.liikennevirasto.digiroad2.client.vvh.ChangeType.New
import fi.liikennevirasto.digiroad2.client.vvh.{ChangeInfo, ChangeType}
import fi.liikennevirasto.digiroad2.dao.{InaccurateAssetDAO, PostGISAssetDao}
import fi.liikennevirasto.digiroad2.dao.linearasset.PostGISSpeedLimitDao
import fi.liikennevirasto.digiroad2.linearasset.LinearAssetFiller._
import fi.liikennevirasto.digiroad2.linearasset.SpeedLimitFiller.{fillTopology, generateUnknownSpeedLimitsForLink}
import fi.liikennevirasto.digiroad2.linearasset._
import fi.liikennevirasto.digiroad2.postgis.PostGISDatabase
import fi.liikennevirasto.digiroad2.process.SpeedLimitValidator
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.service.pointasset.TrafficSignService
import fi.liikennevirasto.digiroad2.util.assetUpdater.LinearAssetUpdateProcess.speedLimitUpdater
import fi.liikennevirasto.digiroad2.util.{LinearAssetUtils, LogUtils, PolygonTools}
import org.joda.time.DateTime
import org.slf4j.LoggerFactory

case class ChangedSpeedLimit(speedLimit: SpeedLimit, link: RoadLink)

class SpeedLimitService(eventbus: DigiroadEventBus, roadLinkService: RoadLinkService) {
  val dao: PostGISSpeedLimitDao = new PostGISSpeedLimitDao(roadLinkService)
  val inaccurateAssetDao: InaccurateAssetDAO = new InaccurateAssetDAO()
  val assetDao: PostGISAssetDao = new PostGISAssetDao()
  val logger = LoggerFactory.getLogger(getClass)
  val polygonTools: PolygonTools = new PolygonTools
  def withDynTransaction[T](f: => T): T = PostGISDatabase.withDynTransaction(f)
  def withDynSession[T](f: => T): T = PostGISDatabase.withDynSession(f)
  def createTimeStamp(offsetHours:Int=5): Long = LinearAssetUtils.createTimeStamp(offsetHours)
  private val RECORD_NUMBER = 4000

  lazy val manoeuvreService = {
    new ManoeuvreService(roadLinkService, eventbus)
  }

  lazy val trafficSignService: TrafficSignService = {
    new TrafficSignService(roadLinkService, eventbus)
  }

  lazy val speedLimitValidator: SpeedLimitValidator = {
    new SpeedLimitValidator(trafficSignService)
  }

  def validateMunicipalities(id: Long,  municipalityValidation: (Int, AdministrativeClass) => Unit, newTransaction: Boolean = true): Unit = {
    getLinksWithLengthFromVVH(id, newTransaction).foreach(vvhLink => municipalityValidation(vvhLink._4, vvhLink._6))
  }

  def validateMinDistance(measure1: Double, measure2: Double): Boolean = {
    val minDistanceAllow = 0.01
    val (maxMeasure, minMeasure) = (math.max(measure1, measure2), math.min(measure1, measure2))
    (maxMeasure - minMeasure) > minDistanceAllow
  }

  def getLinksWithLengthFromVVH(id: Long, newTransaction: Boolean = true): Seq[(String, Double, Seq[Point], Int, LinkGeomSource, AdministrativeClass)] = {
    if (newTransaction)
      withDynTransaction {
        dao.getLinksWithLength(id)
      }
    else
      dao.getLinksWithLength(id)
  }

  def getSpeedLimitAssetsByIds(ids: Set[Long], newTransaction: Boolean = true): Seq[SpeedLimit] = {
    if (newTransaction)
      withDynTransaction {
        dao.getSpeedLimitLinksByIds(ids)
      }
    else
      dao.getSpeedLimitLinksByIds(ids)
  }

  def getSpeedLimitById(id: Long, newTransaction: Boolean = true): Option[SpeedLimit] = {
    getSpeedLimitAssetsByIds(Set(id), newTransaction).headOption
  }

  def getPersistedSpeedLimitByIds(ids: Set[Long], newTransaction: Boolean = true):  Seq[PersistedSpeedLimit] = {
    if (newTransaction)
      withDynTransaction {
        dao.getPersistedSpeedLimitByIds(ids)
      }
    else
      dao.getPersistedSpeedLimitByIds(ids)
  }

  def getPersistedSpeedLimitById(id: Long, newTransaction: Boolean = true): Option[PersistedSpeedLimit] = {
    getPersistedSpeedLimitByIds(Set(id), newTransaction).headOption
  }

  def updateByExpiration(id: Long, expired: Boolean, username: String, newTransaction: Boolean = true):Option[Long] = {
    if (newTransaction)
      withDynTransaction {
        dao.updateExpiration(id, expired, username)
      }
    else
      dao.updateExpiration(id, expired, username)
  }

  /**
    * Returns speed limits for Digiroad2Api /speedlimits GET endpoint.
    */
  def get(bounds: BoundingRectangle, municipalities: Set[Int]): Seq[Seq[SpeedLimit]] = {
    val (roadLinks, change) = roadLinkService.getRoadLinksWithComplementaryAndChanges(bounds, municipalities,asyncMode = false)
    withDynTransaction {
      val (filledTopology,roadLinksByLinkId) = getByRoadLinks(roadLinks, change, roadFilterFunction = {roadLinkFilter: RoadLink => roadLinkFilter.isCarTrafficRoad})
      LinearAssetPartitioner.partition(enrichSpeedLimitAttributes(filledTopology, roadLinksByLinkId), roadLinksByLinkId)
    }
  }

  /**
    * Returns speed limits by municipality. Used by IntegrationApi speed_limits endpoint.
    */
  def get(municipality: Int): Seq[SpeedLimit] = {
    val (roadLinks, changes) = roadLinkService.getRoadLinksWithComplementaryAndChanges(municipality)
    withDynTransaction {
      val speedLimits = getByRoadLinks(roadLinks, changes, roadFilterFunction = {roadLinkFilter: RoadLink => roadLinkFilter.isCarRoadOrCyclePedestrianPath}, adjust = false)._1
      speedLimits.map(speedLimit => {
        val roadLink = roadLinks.find(_.linkId == speedLimit.linkId).get
        val (startMeasure, endMeasure, geometry) = GeometryUtils.useRoadLinkMeasuresIfCloseEnough(speedLimit.startMeasure, speedLimit.endMeasure, roadLink)
        speedLimit.copy(startMeasure = startMeasure, endMeasure = endMeasure, geometry = geometry)
      })
    }
  }

  /**
    * Returns speed limits history for Digiroad2Api /history speedlimits GET endpoint.
    */
  def getHistory(bounds: BoundingRectangle, municipalities: Set[Int]): Seq[Seq[SpeedLimit]] = {
    val roadLinks = roadLinkService.getRoadLinksHistory(bounds, municipalities)
    withDynTransaction {
      val (filledTopology, roadLinksByLinkId) = getByRoadLinks(roadLinks, Seq(), true, {roadLinkFilter: RoadLink => roadLinkFilter.isCarTrafficRoad})
      LinearAssetPartitioner.partition(filledTopology, roadLinksByLinkId)
    }
  }

  /**
    * This method returns speed limits that have been changed in OTH between given date values. It is used by TN-ITS ChangeApi.
    *
    * @param sinceDate
    * @param untilDate
    * @param withAdjust
    * @return Changed speed limits
    */
  def getChanged(sinceDate: DateTime, untilDate: DateTime, withAdjust: Boolean = false, token: Option[String] = None): Seq[ChangedSpeedLimit] = {
    val persistedSpeedLimits = withDynTransaction {
      dao.getSpeedLimitsChangedSince(sinceDate, untilDate, withAdjust, token)
    }
    val roadLinks = roadLinkService.getRoadLinksAndComplementariesByLinkIds(persistedSpeedLimits.map(_.linkId).toSet)
    val roadLinksWithoutWalkways = roadLinks.filterNot(_.linkType == CycleOrPedestrianPath).filterNot(_.linkType == TractorRoad)

    persistedSpeedLimits.flatMap { speedLimit =>
      roadLinksWithoutWalkways.find(_.linkId == speedLimit.linkId).map { roadLink =>
        ChangedSpeedLimit(
          speedLimit = SpeedLimit(
            id = speedLimit.id,
            linkId = speedLimit.linkId,
            sideCode = speedLimit.sideCode,
            trafficDirection = roadLink.trafficDirection,
            value = speedLimit.value.map(_.asInstanceOf[SpeedLimitValue]),
            geometry = GeometryUtils.truncateGeometry3D(roadLink.geometry, speedLimit.startMeasure, speedLimit.endMeasure),
            startMeasure = speedLimit.startMeasure,
            endMeasure = speedLimit.endMeasure,
            modifiedBy = speedLimit.modifiedBy, modifiedDateTime = speedLimit.modifiedDate,
            createdBy = speedLimit.createdBy, createdDateTime = speedLimit.createdDate,
            timeStamp = speedLimit.timeStamp, geomModifiedDate = speedLimit.geomModifiedDate,
            expired = speedLimit.expired,
            linkSource = roadLink.linkSource
          ),
          link = roadLink
        )
      }
    }
  }

  /**
    * Returns unknown speed limits for Digiroad2Api /speedlimits/unknown GET endpoint.
    */
  def getUnknown(municipalities: Set[Int], administrativeClass: Option[AdministrativeClass]): Map[String, Map[String, Any]] = {
    withDynSession {
      dao.getUnknownSpeedLimits(municipalities, administrativeClass)
    }
  }

  def hideUnknownSpeedLimits(linkIds: Set[String]): Set[String] = {
    withDynTransaction {
      dao.hideUnknownSpeedLimits(linkIds)
    }
  }

  def getMunicipalitiesWithUnknown(administrativeClass: Option[AdministrativeClass]): Seq[(Long, String)] = {
    withDynSession {
      dao.getMunicipalitiesWithUnknown(administrativeClass)
    }
  }

  /**
    * Removes speed limit from unknown speed limits list if speed limit exists. Used by SpeedLimitUpdater actor.
    */
  def purgeUnknown(linkIds: Set[String], expiredLinkIds: Seq[String]): Unit = {
    val roadLinks = roadLinkService.fetchRoadlinksByIds(linkIds)
    withDynTransaction {
      roadLinks.foreach { rl =>
        dao.purgeFromUnknownSpeedLimits(rl.linkId, GeometryUtils.geometryLength(rl.geometry))
      }

      //To remove nonexistent road links of unknown speed limits list
      if (expiredLinkIds.nonEmpty)
        dao.deleteUnknownSpeedLimits(expiredLinkIds)
    }
  }

  protected def createUnknownLimits(speedLimits: Seq[SpeedLimit], roadLinksByLinkId: Map[String, RoadLink]): Seq[UnknownSpeedLimit] = {
    val generatedLimits = speedLimits.filter(speedLimit => speedLimit.id == 0 && speedLimit.value.isEmpty)
    generatedLimits.map { limit =>
      val roadLink = roadLinksByLinkId(limit.linkId)
      UnknownSpeedLimit(roadLink.linkId, roadLink.municipalityCode, roadLink.administrativeClass)
    }
  }

  def getExistingAssetByRoadLink(roadLink: RoadLink, newTransaction: Boolean = true): Seq[SpeedLimit] = {
    if (newTransaction)
      withDynTransaction {
        dao.getCurrentSpeedLimitsByLinkIds(Some(Set(roadLink.linkId)))
      }
    else
      dao.getCurrentSpeedLimitsByLinkIds(Some(Set(roadLink.linkId)))
  }

  private def getByRoadLinks(roadLinks: Seq[RoadLink], change: Seq[ChangeInfo], showSpeedLimitsHistory: Boolean = false,
                             roadFilterFunction: RoadLink => Boolean, adjust: Boolean = true) = {

    val roadLinksFiltered = roadLinks.filter(roadFilterFunction)
    val (speedLimitLinks, topology) = dao.getSpeedLimitLinksByRoadLinks(roadLinksFiltered, showSpeedLimitsHistory)
    val speedLimits = (speedLimitLinks).groupBy(_.linkId)
    val roadLinksByLinkId = topology.groupBy(_.linkId).mapValues(_.head)

    if(adjust) {
      val filledTopology = LogUtils.time(logger, "Check for and adjust possible linearAsset adjustments on " + roadLinks.size + " roadLinks. TypeID: " + SpeedLimitAsset.typeId) {
        adjustSpeedLimitsAndGenerateUnknowns(roadLinksFiltered, speedLimits, geometryChanged = false)
      }
      (filledTopology, roadLinksByLinkId)
    }
    else (speedLimitLinks, roadLinksByLinkId)
  }

  def adjustSpeedLimitsAndGenerateUnknowns(roadLinksFiltered: Seq[RoadLink], speedLimits: Map[String, Seq[SpeedLimit]],
                        changeSet:Option[ChangeSet] = None, geometryChanged: Boolean, counter: Int = 1): Seq[SpeedLimit] = {
    val (filledTopology, changedSet) = fillTopology(roadLinksFiltered, speedLimits, changeSet, geometryChanged)
    val cleanedChangeSet = speedLimitUpdater.cleanRedundantMValueAdjustments(changedSet, speedLimits.values.flatten.toSeq).filterGeneratedAssets

    cleanedChangeSet.isEmpty match {
      case true => filledTopology
      case false if counter > 3 =>
        speedLimitUpdater.updateChangeSet(cleanedChangeSet)
        filledTopology
      case false if counter <= 3 =>
        speedLimitUpdater.updateChangeSet(cleanedChangeSet)
        val speedLimitsToAdjust = filledTopology.filterNot(speedLimit => speedLimit.id <= 0 && speedLimit.value.isEmpty).groupBy(_.linkId)
        adjustSpeedLimitsAndGenerateUnknowns(roadLinksFiltered, speedLimitsToAdjust, None, geometryChanged, counter + 1)
    }
  }

  def getAssetsAndPoints(existingAssets: Seq[SpeedLimit], roadLinks: Seq[RoadLink], changeInfo: (ChangeInfo, RoadLink)): Seq[(Point, SpeedLimit)] = {
    existingAssets.filter { asset => asset.createdDateTime.get.isBefore(changeInfo._1.timeStamp)}
      .flatMap { asset =>
        val roadLink = roadLinks.find(_.linkId == asset.linkId)
        if (roadLink.nonEmpty && roadLink.get.administrativeClass == changeInfo._2.administrativeClass) {
          GeometryUtils.calculatePointFromLinearReference(roadLink.get.geometry, asset.endMeasure).map(point => (point, asset)) ++
            (if (asset.startMeasure == 0)
              GeometryUtils.calculatePointFromLinearReference(roadLink.get.geometry, asset.startMeasure).map(point => (point, asset))
            else
              Seq())
        } else
          Seq()
      }
  }

  def getAdjacentAssetByPoint(assets: Seq[(Point, SpeedLimit)], point: Point) : Seq[SpeedLimit] = {
    assets.filter{case (assetPt, _) => GeometryUtils.areAdjacent(assetPt, point)}.map(_._2)
  }







  /**
    * Saves speed limit value changes received from UI. Used by Digiroad2Api /speedlimits PUT endpoint.
    */
  def updateValues(ids: Seq[Long], value: SpeedLimitValue, username: String, municipalityValidation: (Int, AdministrativeClass) => Unit, newTransaction: Boolean = true): Seq[Long] = {
    if (newTransaction) {
      withDynTransaction {
        ids.foreach(id => validateMunicipalities(id, municipalityValidation, newTransaction = false))
        ids.flatMap(dao.updateSpeedLimitValue(_, value, username))
      }
    } else {
      ids.foreach(id => validateMunicipalities(id, municipalityValidation, newTransaction = false))
      ids.flatMap(dao.updateSpeedLimitValue(_, value, username))
    }
  }

  /**
    * Create new speed limit when value received from UI changes and expire the old one. Used by SpeeedLimitsService.updateValues.
    */

  def update(id: Long, newLimits: Seq[NewLinearAsset], username: String, newTransaction: Boolean = true): Seq[Long] = {
    val oldSpeedLimit = getPersistedSpeedLimitById(id, newTransaction).map(toSpeedLimit(_, newTransaction)).get

    newLimits.flatMap (limit =>  limit.value match {
      case SpeedLimitValue(suggestion, intValue) =>

        if ((validateMinDistance(limit.startMeasure, oldSpeedLimit.startMeasure) || validateMinDistance(limit.endMeasure, oldSpeedLimit.endMeasure)) || SideCode(limit.sideCode) != oldSpeedLimit.sideCode)
          updateSpeedLimitWithExpiration(id, SpeedLimitValue(suggestion, intValue), username, Some(Measures(limit.startMeasure, limit.endMeasure)), Some(limit.sideCode), (_, _) => Unit)
        else
          updateValues(Seq(id), SpeedLimitValue(suggestion, intValue), username, (_, _) => Unit, newTransaction)
      case _ => Seq.empty[Long]
    })
  }


  def updateSpeedLimitWithExpiration(id: Long, value: SpeedLimitValue, username: String, measures: Option[Measures] = None, sideCode: Option[Int] = None, municipalityValidation: (Int, AdministrativeClass) => Unit): Option[Long] = {
    validateMunicipalities(id, municipalityValidation, newTransaction = false)

    //Get all data from the speedLimit to update
    val speedLimit = dao.getPersistedSpeedLimit(id).filterNot(_.expired).getOrElse(throw new IllegalStateException("Asset no longer available"))

    //Expire old speed limit
    dao.updateExpiration(id)

    //Create New Asset copy by the old one with new value
    val newAssetId =
      dao.createSpeedLimit(username, speedLimit.linkId, measures.getOrElse(Measures(speedLimit.startMeasure, speedLimit.endMeasure)),
        SideCode.apply(sideCode.getOrElse(speedLimit.sideCode.value)), value, None, None, None, None, speedLimit.linkSource)

    existOnInaccuratesList(id, newAssetId)
    newAssetId
  }

  private def existOnInaccuratesList(oldId: Long, newId: Option[Long] = None) = {
    (inaccurateAssetDao.getInaccurateAssetById(oldId), newId) match {
      case (Some(idOld), Some(idNew)) =>
        inaccurateAssetDao.deleteInaccurateAssetById(idOld)
        checkInaccurateSpeedLimit(newId)
      case _ => None
    }
  }

  private def checkInaccurateSpeedLimit(id: Option[Long] = None) = {
    getSpeedLimitById(id.head, false) match {
      case Some(speedLimit) =>
        val roadLink = roadLinkService.getRoadLinkAndComplementaryByLinkId(speedLimit.linkId, false)
          .find(roadLink => roadLink.administrativeClass == State || roadLink.administrativeClass == Municipality)
          .getOrElse(throw new NoSuchElementException("RoadLink Not Found"))

        val trafficSigns = trafficSignService.getPersistedAssetsByLinkIdWithoutTransaction(roadLink.linkId)

        val inaccurateAssets =
          speedLimitValidator.checkSpeedLimitUsingTrafficSign(trafficSigns, roadLink, Seq(speedLimit)).map {
            inaccurateAsset =>
              println(s"Inaccurate asset ${inaccurateAsset.id} found ")
              (inaccurateAsset, roadLink.administrativeClass)
          }

        inaccurateAssets.foreach { case (speedLimit, administrativeClass) =>
          inaccurateAssetDao.createInaccurateAsset(speedLimit.id, SpeedLimitAsset.typeId, roadLink.municipalityCode, administrativeClass)
        }
      case _ => None
    }
  }

  /**
    * Saves speed limit values when speed limit is split to two parts in UI (scissors icon). Used by Digiroad2Api /speedlimits/:speedLimitId/split POST endpoint.
    */
  def split(id: Long, splitMeasure: Double, existingValue: Int, createdValue: Int, username: String, municipalityValidation: (Int, AdministrativeClass) => Unit): Seq[SpeedLimit] = {
    withDynTransaction {
      getPersistedSpeedLimitById(id, newTransaction = false) match {
        case Some(speedLimit) =>
          val roadLink = roadLinkService.fetchRoadlinkAndComplementary(speedLimit.linkId).getOrElse(throw new IllegalStateException("Road link no longer available"))
          municipalityValidation(roadLink.municipalityCode, roadLink.administrativeClass)

          val (newId ,idUpdated) = split(speedLimit, roadLink, splitMeasure, existingValue, createdValue, username)

          val assets = getPersistedSpeedLimitByIds(Set(idUpdated, newId), newTransaction = false)

          val speedLimits = assets.map{ asset =>
            SpeedLimit(asset.id, asset.linkId, asset.sideCode, roadLink.trafficDirection, asset.value, GeometryUtils.truncateGeometry3D(roadLink.geometry, asset.startMeasure, asset.endMeasure),
              asset.startMeasure, asset.endMeasure, asset.modifiedBy, asset.modifiedDate, asset.createdBy, asset.createdDate, asset.timeStamp, asset.geomModifiedDate, linkSource = asset.linkSource)
          }
          speedLimits.filter(asset => asset.id == idUpdated || asset.id == newId)

        case _ => Seq()
      }
    }
  }

  /**
    * Splits speed limit by given split measure.
    * Used by SpeedLimitService.split.
    */
  def split(speedLimit: PersistedSpeedLimit, roadLinkFetched: RoadLinkFetched, splitMeasure: Double, existingValue: Int, createdValue: Int, username: String): (Long, Long) = {
    val (existingLinkMeasures, createdLinkMeasures) = GeometryUtils.createSplit(splitMeasure, (speedLimit.startMeasure, speedLimit.endMeasure))

    dao.updateExpiration(speedLimit.id)

    val existingId = dao.createSpeedLimit(speedLimit.createdBy.getOrElse(username), speedLimit.linkId, Measures(existingLinkMeasures._1, existingLinkMeasures._2),
      speedLimit.sideCode, SpeedLimitValue(existingValue), Some(speedLimit.timeStamp), speedLimit.createdDate, Some(username), Some(DateTime.now()) , roadLinkFetched.linkSource).get

    val createdId = dao.createSpeedLimit(speedLimit.createdBy.getOrElse(username), roadLinkFetched.linkId, Measures(createdLinkMeasures._1, createdLinkMeasures._2),
      speedLimit.sideCode, SpeedLimitValue(createdValue), Option(speedLimit.timeStamp), speedLimit.createdDate, Some(username), Some(DateTime.now()), roadLinkFetched.linkSource).get
    (existingId, createdId)
  }

  private def toSpeedLimit(persistedSpeedLimit: PersistedSpeedLimit, newTransaction: Boolean = true): SpeedLimit = {
    val roadLink = roadLinkService.getRoadLinkAndComplementaryByLinkId(persistedSpeedLimit.linkId, newTransaction).get

    SpeedLimit(
      persistedSpeedLimit.id, persistedSpeedLimit.linkId, persistedSpeedLimit.sideCode,
      roadLink.trafficDirection, persistedSpeedLimit.value,
      GeometryUtils.truncateGeometry3D(roadLink.geometry, persistedSpeedLimit.startMeasure, persistedSpeedLimit.endMeasure),
      persistedSpeedLimit.startMeasure, persistedSpeedLimit.endMeasure,
      persistedSpeedLimit.modifiedBy, persistedSpeedLimit.modifiedDate,
      persistedSpeedLimit.createdBy, persistedSpeedLimit.createdDate, persistedSpeedLimit.timeStamp, persistedSpeedLimit.geomModifiedDate,
      linkSource = persistedSpeedLimit.linkSource)
  }

  private def isSeparableValidation(speedLimit: SpeedLimit): SpeedLimit = {
    val separable = speedLimit.sideCode == SideCode.BothDirections && speedLimit.trafficDirection == TrafficDirection.BothDirections
    if (!separable) throw new IllegalArgumentException
    speedLimit
  }

  /**
    * Saves speed limit values when speed limit is separated to two sides in UI. Used by Digiroad2Api /speedlimits/:speedLimitId/separate POST endpoint.
    */
  def separate(id: Long, valueTowardsDigitization: SpeedLimitValue, valueAgainstDigitization: SpeedLimitValue, username: String, municipalityValidation: (Int, AdministrativeClass) => Unit): Seq[SpeedLimit] = {
    val speedLimit = getPersistedSpeedLimitById(id)
      .map(toSpeedLimit(_))
      .map(isSeparableValidation)
      .get

    validateMunicipalities(id, municipalityValidation)

    updateByExpiration(id, expired = true, username)

    val(newId1, newId2) = withDynTransaction {
      (dao.createSpeedLimit(speedLimit.createdBy.getOrElse(username), speedLimit.linkId, Measures(speedLimit.startMeasure, speedLimit.endMeasure), SideCode.TowardsDigitizing, valueTowardsDigitization, None, createdDate = speedLimit.createdDateTime , modifiedBy = Some(username), modifiedAt = Some(DateTime.now()), linkSource = speedLimit.linkSource).get,
       dao.createSpeedLimit(speedLimit.createdBy.getOrElse(username), speedLimit.linkId, Measures(speedLimit.startMeasure, speedLimit.endMeasure), SideCode.AgainstDigitizing, valueAgainstDigitization, None, createdDate = speedLimit.createdDateTime, modifiedBy = Some(username), modifiedAt = Some(DateTime.now()),  linkSource = speedLimit.linkSource).get)
    }
    val assets = getSpeedLimitAssetsByIds(Set(newId1, newId2))
    Seq(assets.find(_.id == newId1).get, assets.find(_.id == newId2).get)
  }

  def getByMunicpalityAndRoadLinks(municipality: Int): Seq[(SpeedLimit, RoadLink)] = {
    val (roadLinks, changes) = roadLinkService.getRoadLinksWithComplementaryAndChanges(municipality)
    val speedLimits = withDynTransaction {
      getByRoadLinks(roadLinks, changes, roadFilterFunction = {roadLinkFilter: RoadLink => roadLinkFilter.isCarTrafficRoad}, adjust = false)._1
    }
    speedLimits.map{ speedLimit => (speedLimit, roadLinks.find(_.linkId == speedLimit.linkId).getOrElse(throw new NoSuchElementException))}
  }

  /**
    * This method was created for municipalityAPI, in future could be merge with the other create method.
    */
  def createMultiple(newLimits: Seq[NewLinearAsset], username: String, timeStamp: Long = createTimeStamp(), municipalityValidation: (Int, AdministrativeClass) => Unit): Seq[Long] = {
    val createdIds = newLimits.flatMap { limit =>
      limit.value match {
        case SpeedLimitValue(suggestion, intValue) => dao.createSpeedLimit(username, limit.linkId, Measures(limit.startMeasure, limit.endMeasure), SideCode.apply(limit.sideCode), SpeedLimitValue(suggestion, intValue), timeStamp, municipalityValidation)
        case _ => None
      }
    }

    eventbus.publish("speedLimits:purgeUnknownLimits", (newLimits.map(_.linkId).toSet, Seq()))
    createdIds
  }

  /**
    * Saves new speed limit from UI. Used by Digiroad2Api /speedlimits PUT and /speedlimits POST endpoints.
    */
  def create(newLimits: Seq[NewLimit], value: SpeedLimitValue, username: String, municipalityValidation: (Int, AdministrativeClass) => Unit): Seq[Long] = {
    withDynTransaction {
      val createdIds = newLimits.flatMap { limit =>
        dao.createSpeedLimit(username, limit.linkId, Measures(limit.startMeasure, limit.endMeasure), SideCode.BothDirections, value, createTimeStamp(), municipalityValidation)
      }
      eventbus.publish("speedLimits:purgeUnknownLimits", (newLimits.map(_.linkId).toSet, Seq()))
      createdIds
    }
  }

  def createWithoutTransaction(newLimits: Seq[NewLimit], value: SpeedLimitValue, username: String, sideCode: SideCode): Seq[Long] = {
    newLimits.flatMap { limit =>
      dao.createSpeedLimit(username, limit.linkId, Measures(limit.startMeasure, limit.endMeasure), sideCode, value, createTimeStamp(), (_, _) => Unit)
    }
  }

  private def addRoadAdministrationClassAttribute(speedLimit: SpeedLimit, roadLink: RoadLink): SpeedLimit = {
    speedLimit.copy(attributes = speedLimit.attributes ++ Map("ROAD_ADMIN_CLASS" -> roadLink.administrativeClass))
  }

  private def addMunicipalityCodeAttribute(speedLimit: SpeedLimit, roadLink: RoadLink): SpeedLimit = {
    speedLimit.copy(attributes = speedLimit.attributes ++ Map("municipalityCode" -> roadLink.municipalityCode))
  }

  private def addConstructionTypeAttribute(speedLimit: SpeedLimit, roadLink: RoadLink): SpeedLimit = {
    speedLimit.copy(attributes = speedLimit.attributes ++ Map("constructionType" -> roadLink.constructionType.value))
  }

  private def enrichSpeedLimitAttributes(speedLimits: Seq[SpeedLimit], roadLinksForSpeedLimits: Map[String, RoadLink]): Seq[SpeedLimit] = {
    val speedLimitAttributeOperations: Seq[(SpeedLimit, RoadLink) => SpeedLimit] = Seq(
      addRoadAdministrationClassAttribute,
      addMunicipalityCodeAttribute,
      addConstructionTypeAttribute
      //In the future if we need to add more attributes just add a method here
    )

    speedLimits.map(speedLimit =>
      speedLimitAttributeOperations.foldLeft(speedLimit) { case (asset, operation) =>
        roadLinksForSpeedLimits.get(asset.linkId).map{
          roadLink =>
            operation(asset, roadLink)
        }.getOrElse(asset)
      }
    )
  }

  def getInaccurateRecords(municipalities: Set[Int] = Set(), adminClass: Set[AdministrativeClass] = Set()): Map[String, Map[String, Any]] = {
    withDynTransaction {
      inaccurateAssetDao.getInaccurateAsset(SpeedLimitAsset.typeId, municipalities, adminClass)
        .groupBy(_.municipality)
        .mapValues {
          _.groupBy(_.administrativeClass)
            .mapValues(_.map{values => Map("assetId" -> values.assetId, "linkId" -> values.linkId)})
        }
    }
  }
}
