package fi.liikennevirasto.digiroad2.util

import java.util.Properties
import fi.liikennevirasto.digiroad2.asset.SideCode
import fi.liikennevirasto.digiroad2.client.VKMClient
import fi.liikennevirasto.digiroad2.client.viite.SearchViiteClient
import fi.liikennevirasto.digiroad2.client.vvh.{ChangeInfo, VVHClient}
import fi.liikennevirasto.digiroad2.dao.{RoadAddressTEMP, RoadLinkTempDAO}
import fi.liikennevirasto.digiroad2.lane.LaneNumber.MainLane
import fi.liikennevirasto.digiroad2.lane.{LaneFiller, LaneRoadAddressInfo, NewLane, PersistedLane}
import fi.liikennevirasto.digiroad2.postgis.PostGISDatabase
import fi.liikennevirasto.digiroad2.service.lane.LaneService
import fi.liikennevirasto.digiroad2.service.{RoadAddressService, RoadLinkService}
import fi.liikennevirasto.digiroad2.{DummyEventBus, DummySerializer}
import org.apache.http.impl.client.HttpClientBuilder
import org.joda.time.DateTime


case class LaneUtils(){
  // DROTH-3057: Remove after lanes csv import is disabled
  def processNewLanesByRoadAddress(newLanes: Set[NewLane], laneRoadAddressInfo: LaneRoadAddressInfo,
                                   sideCode: Int, username: String, withTransaction: Boolean = true): Set[Long] = {
    LaneUtils.processNewLanesByRoadAddress(newLanes, laneRoadAddressInfo, sideCode, username, withTransaction)
  }
}
object LaneUtils {
  lazy val roadLinkTempDAO: RoadLinkTempDAO = new RoadLinkTempDAO
  val eventbus = new DummyEventBus
  def withDynTransaction[T](f: => T): T = PostGISDatabase.withDynTransaction(f)

  lazy val laneService: LaneService = new LaneService(roadLinkService, eventbus, roadAddressService)
  lazy val roadLinkService: RoadLinkService = new RoadLinkService(vvhClient, eventbus, new DummySerializer)
  lazy val vvhClient: VVHClient = { new VVHClient(Digiroad2Properties.vvhRestApiEndPoint) }
  lazy val viiteClient: SearchViiteClient = { new SearchViiteClient(Digiroad2Properties.viiteRestApiEndPoint, HttpClientBuilder.create().build()) }
  lazy val roadAddressService: RoadAddressService = new RoadAddressService(viiteClient)


  lazy val MAIN_LANES = Seq(MainLane.towardsDirection, MainLane.againstDirection, MainLane.motorwayMaintenance)

  // DROTH-3057: Remove after lanes csv import is disabled
  def processNewLanesByRoadAddress(newLanes: Set[NewLane], laneRoadAddressInfo: LaneRoadAddressInfo,
                                   sideCode: Int, username: String, withTransaction: Boolean = true): Set[Long] = {
    // Main process
    def process() = {

      val filteredRoadAddresses = getRoadAddressToProcess(laneRoadAddressInfo)

      //Get only the lanes to create
      val lanesToInsert = newLanes.filter(_.id == 0)


      val allLanesToCreate = filteredRoadAddresses.flatMap { road =>
        val vvhTimeStamp = vvhClient.roadLinkData.createVVHTimeStamp()

        lanesToInsert.flatMap { lane =>
          val laneCode = laneService.getLaneCode(lane).toInt
          laneService.validateStartDate(lane, laneCode)

          val isTwoDigitLaneCode = laneCode.toString.length > 1
          val finalSideCode = laneService.fixSideCode( road, laneCode.toString )
          val laneCodeOneDigit = if (isTwoDigitLaneCode) laneCode.toString.substring(1).toInt
                                 else laneCode

          calculateStartAndEndPoint(road, laneRoadAddressInfo) match {
            case Some((start: Double, end: Double)) =>
              Some(PersistedLane(0, road.linkId, finalSideCode.value, laneCodeOneDigit, road.municipalityCode.getOrElse(0).toLong,
                start, end, Some(username), Some(DateTime.now()), None, None, None, None, expired = false,
                vvhTimeStamp, None, lane.properties))

            case _ => None
            }
        }
      }

      //Create lanes
      allLanesToCreate.map(laneService.createWithoutTransaction(_, username))

    }

    if(withTransaction) {
      withDynTransaction {
        process()
      }
    }else{
      process()
    }

  }

  def getRoadAddressToProcess(laneRoadAddressInfo: LaneRoadAddressInfo): Set[RoadAddressTEMP] = {

    // Generate a sequence from initialRoadPartNumber to endRoadPartNumber
    // If initialRoadPartNumber = 1 and endRoadPartNumber = 4
    // Result will be Seq(1,2,3,4)
    val roadParts = laneRoadAddressInfo.initialRoadPartNumber to laneRoadAddressInfo.endRoadPartNumber

    // Get the road address information from Viite and convert the data to RoadAddressesAux
    val roadAddresses = roadAddressService.getAllByRoadNumberAndParts(laneRoadAddressInfo.roadNumber, roadParts, Seq(Track.apply(laneRoadAddressInfo.track)))
      .map (elem => RoadAddressTEMP (elem.linkId, elem.roadNumber, elem.roadPartNumber, elem.track,
        elem.startAddrMValue, elem.endAddrMValue, elem.startMValue, elem.endMValue,elem.geom, Some(elem.sideCode), Some(0) )
      )

    // Get the road address information from our DB and convert the data to RoadAddressesAux
    val vkmRoadAddress = roadLinkTempDAO.getByRoadNumberRoadPartTrack(laneRoadAddressInfo.roadNumber.toInt, laneRoadAddressInfo.track, roadParts.toSet)


    val vkmLinkIds = vkmRoadAddress.map(_.linkId)

    // Remove from Viite the information we have updated in our DB and add ou information
    val allRoadAddress = (roadAddresses.filterNot( ra => vkmLinkIds.contains( ra.linkId)) ++ vkmRoadAddress).toSet

    // Get all updated information from VVH
    val mappedRoadLinks = roadLinkService.fetchVVHRoadlinks(allRoadAddress.map(_.linkId))
      .groupBy(_.linkId)

    val finalRoads = allRoadAddress.filter { elem =>       // Remove the links that are not in VVH and roadPart between our initial and end
      val existsInVVH = mappedRoadLinks.contains(elem.linkId)
      val roadPartNumber = elem.roadPart
      val inInitialAndEndRoadPart = roadPartNumber >= laneRoadAddressInfo.initialRoadPartNumber && roadPartNumber <= laneRoadAddressInfo.endRoadPartNumber

      existsInVVH && inInitialAndEndRoadPart
    }
      .map{ elem =>             //In case we don't have municipalityCode we will get it from VVH info
        if (elem.municipalityCode.getOrElse(0) == 0)
          elem.copy( municipalityCode = Some( mappedRoadLinks(elem.linkId).head.municipalityCode) )
        else
          elem
      }

    finalRoads
  }

  def calculateStartAndEndPoint(road: RoadAddressTEMP, laneRoadAddressInfo: LaneRoadAddressInfo): Option[(Double, Double)] = {
    val startDifferenceAddr = laneRoadAddressInfo.initialDistance - road.startAddressM
    val startPoint = if (startDifferenceAddr <= 0) road.startMValue else startDifferenceAddr
    val endDifferenceAddr = road.endAddressM - laneRoadAddressInfo.endDistance
    val endPoint = if (endDifferenceAddr <= 0) road.endMValue else road.endMValue - endDifferenceAddr

    val endPoints = if (road.roadPart > laneRoadAddressInfo.initialRoadPartNumber && road.roadPart < laneRoadAddressInfo.endRoadPartNumber) {
      Some( road.startMValue, road.endMValue)

    }
    else if (road.roadPart == laneRoadAddressInfo.initialRoadPartNumber && road.roadPart == laneRoadAddressInfo.endRoadPartNumber) {

      if (!(road.endAddressM > laneRoadAddressInfo.initialDistance && road.startAddressM < laneRoadAddressInfo.endDistance))
        None

      else if (road.startAddressM <= laneRoadAddressInfo.initialDistance && road.endAddressM >= laneRoadAddressInfo.endDistance) {
        Some( startPoint, endPoint )

      }
      else if (road.startAddressM <= laneRoadAddressInfo.initialDistance && road.endAddressM < laneRoadAddressInfo.endDistance) {
        Some( startPoint, road.endMValue )

      }
      else if (road.startAddressM > laneRoadAddressInfo.initialDistance && road.endAddressM >= laneRoadAddressInfo.endDistance) {
        Some( road.startMValue, endPoint )

      }
      else {
        Some( road.startMValue, road.endMValue )

      }

    }
    else if (road.roadPart == laneRoadAddressInfo.initialRoadPartNumber) {
      if (road.endAddressM <= laneRoadAddressInfo.initialDistance) {
        None

      } else if (road.startAddressM < laneRoadAddressInfo.initialDistance) {
        Some( startPoint, road.endMValue )

      } else {
        Some( road.startMValue, road.endMValue )
      }

    }
    else if (road.roadPart == laneRoadAddressInfo.endRoadPartNumber) {
      if (road.startAddressM >= laneRoadAddressInfo.endDistance) {
        None

      } else if (road.endAddressM > laneRoadAddressInfo.endDistance) {
        Some( road.startMValue, endPoint )

      } else {
        Some( road.startMValue, road.endMValue )

      }
    }
    else {
      None
    }

    //Fix the start and end point when the roadAddress SideCode is AgainstDigitizing
    endPoints match {
      case Some((s: Double , e: Double)) =>  if (road.sideCode.getOrElse(SideCode.TowardsDigitizing) == SideCode.AgainstDigitizing)
        Some(road.endMValue - e, road.endMValue - s)
      else
        endPoints
      case _  => None
    }
  }

  def getMappedChanges(changes: Seq[ChangeInfo]): Map[Long, Seq[ChangeInfo]] = {
    (changes.filter(_.oldId.nonEmpty).map(c => c.oldId.get -> c) ++ changes.filter(_.newId.nonEmpty)
      .map(c => c.newId.get -> c)).groupBy(_._1).mapValues(_.map(_._2))
  }

  def deletedRoadLinkIds(changes: Map[Long, Seq[ChangeInfo]], currentLinkIds: Set[Long]): Seq[Long] = {
    changes.filter(c =>
      !c._2.exists(ci => ci.newId.contains(c._1)) &&
        !currentLinkIds.contains(c._1)
    ).keys.toSeq
  }

  def newChangeInfoDetected(lane : PersistedLane, changes: Map[Long, Seq[ChangeInfo]]): Boolean = {
    changes.getOrElse(lane.linkId, Seq()).exists(c =>
      c.vvhTimeStamp > lane.vvhTimeStamp && (c.oldId.getOrElse(0) == lane.linkId || c.newId.getOrElse(0) == lane.linkId)
    )
  }


}
