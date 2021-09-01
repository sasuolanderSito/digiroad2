package fi.liikennevirasto.digiroad2.util

import fi.liikennevirasto.digiroad2.{DummyEventBus, DummySerializer}
import fi.liikennevirasto.digiroad2.client.viite.{ChangeInformation, IntegrationViiteClient, Source}
import fi.liikennevirasto.digiroad2.client.vvh.VVHClient
import fi.liikennevirasto.digiroad2.lane.LaneNumber.MainLane
import fi.liikennevirasto.digiroad2.lane.{ LaneProperty, LanePropertyValue, PersistedLane}
import fi.liikennevirasto.digiroad2.postgis.PostGISDatabase
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.service.lane.LaneService
import org.apache.http.impl.client.HttpClientBuilder
import org.joda.time.DateTime

object AutomaticLaneCreationModificationProcess {
  def withDynTransaction[T](f: => T): T = PostGISDatabase.withDynTransaction(f)

  lazy val integrationViiteClient: IntegrationViiteClient = {
    new IntegrationViiteClient(Digiroad2Properties.viiteRestApiEndPoint, HttpClientBuilder.create().build())
  }

  lazy val vvhClient: VVHClient = {
    new VVHClient(Digiroad2Properties.vvhRestApiEndPoint)
  }

  lazy val roadLinkService: RoadLinkService = {
    new RoadLinkService(vvhClient, new DummyEventBus, new DummySerializer)
  }
  
  lazy val laneService: LaneService = {
    new LaneService(roadLinkService,new DummyEventBus)
  }
  
  lazy val user="autogenerated_lane"

  // change type 2
  private def newLane(changeInfo: ChangeInformation): Seq[Long] = {

    if (changeInfo.changeType == 2) {
      val source = changeInfo.oldRoadNumbering
      val maintenanceHole = Seq(30000, 39999)
      val pedestrianAndCycleRoute = Seq(70000, 99999)
      val path = Seq(62000, 62999)

      def mapPropertiesDefaultValue(source: Source, laneCode: Int, laneType: Int = 1): Seq[LaneProperty] = {
        Seq(
          LaneProperty("lane_type", Seq(LanePropertyValue(value = laneType))),
          LaneProperty("lane_code", Seq(LanePropertyValue(value = laneCode))),

          // RoadAddress
          LaneProperty("roadNumber", Seq(LanePropertyValue(value = source.roadNumber))),
          LaneProperty("roadPartNumber", Seq(LanePropertyValue(value = source.roadPartNumber))),
          LaneProperty("startAddrMValue", Seq(LanePropertyValue(value = source.startAddrMValue))),
          LaneProperty("endAddrMValue", Seq(LanePropertyValue(value = source.endAddrMValue))),

          LaneProperty("track", Seq(LanePropertyValue(value = source.track))),
          LaneProperty("administrativeClass", Seq(LanePropertyValue(value = source.administrativeValues))),
          LaneProperty("start_date", Seq(LanePropertyValue(value = DateTime.now()))) // what format
        )
      }

      def checkRange(number: Int, range: Seq[Int]): Boolean = {
        (range.head to range.last).contains(number)
      }

      def laneCode(source: Source): Option[Int] = {

        val (track, roadNumber, roadPartNumber) = (source.track, source.roadNumber, source.roadPartNumber)

        // RoadNumbers 70000 - 99999: Lane Code 31
        // RoadNumbers 62000 - 62999: Lane Code 31
        val pedestrianAndCycleRouteAndPathCheck = (checkRange(roadNumber.toInt, path)
          || checkRange(roadNumber.toInt, pedestrianAndCycleRoute))

        // RoadNumbers 30000 - 39999 RoadPartNumber 9: Lane Code 31
        val maintenanceHoleCheck = checkRange(roadNumber.toInt, maintenanceHole) && roadPartNumber == 9

        // RoadNumbers 20000 - 29999, RoadPartNumbers 995-999, Track 0: Lane Code 31
        val checkMotorwayMaintenance = track.value == 0 &&
          checkRange(roadNumber.toInt, Seq(20000, 29999)) &&
          checkRange(roadPartNumber.toInt, Seq(995, 999))

        if (track.value == 2) {
          // Track 2: Lane Code 21
          Some(MainLane.againstDirection)
        } else if (pedestrianAndCycleRouteAndPathCheck || maintenanceHoleCheck || checkMotorwayMaintenance) {
          Some(MainLane.motorwayMaintenance)
        } else if (track.value == 1 || (track.value == 0 && checkRange(roadNumber.toInt, Seq(20000, 39999)))) {
          // Track 1: Lane Code 11
          // RoadNumbers 20000 - 39999, Track 0: Lane Code 11
          Some(MainLane.towardsDirection)
        } else {
          None
        }
      }

      // check if there is already lane in part of section
      // further requirement engineering is needed for situation when lane already exist
      val laneExist = false

      //get startpoint and endpoint, for each link Mvalue in start and MValue in end
      val startMeasureFromVKMOrVVH = 0
      val endMeasureFromVKMOrVVH = 0
      val vvhTimeStamp = vvhClient.roadLinkData.createVVHTimeStamp()
      val municipalityCodeVKM = 0 //?
      if (laneExist) {

        // new lane to empty road
        val newLaneProperties = if (checkRange(source.roadNumber.toInt, pedestrianAndCycleRoute)) {
          mapPropertiesDefaultValue(source, laneCode(source).get, 20)
        } else {
          mapPropertiesDefaultValue(source, laneCode(source).get)
        }

        //Track 0, RoadNumbers 1-19999 and 40000 - 61999: Lane Code 11 and 21
        if (source.track.value == 0 && (checkRange(source.roadNumber.toInt, Seq(1, 19999))
          || checkRange(source.roadNumber.toInt, Seq(40000, 61999)))) {
          // for loop/map all  something like this links.map(link =>{add} )
          val newLanes = Seq(
            PersistedLane(id = 0, linkId = 0, sideCode = 0,
                          laneCode = laneCode(source).get, municipalityCode = municipalityCodeVKM,
                          startMeasure = startMeasureFromVKMOrVVH, endMeasure = endMeasureFromVKMOrVVH,
                          createdBy = Some(user), createdDateTime = Some(DateTime.now()),
                          modifiedBy = None, modifiedDateTime = None,
                          expiredBy = None, expiredDateTime = None, expired = false,
                          vvhTimeStamp = vvhTimeStamp, geomModifiedDate = None,
                          attributes = mapPropertiesDefaultValue(source, 11)),
            PersistedLane(id = 0, linkId = 0, sideCode = 0,
                          laneCode = laneCode(source).get, municipalityCode = municipalityCodeVKM,
                          startMeasure = startMeasureFromVKMOrVVH, endMeasure = endMeasureFromVKMOrVVH,
                          createdBy = Some(user), createdDateTime = Some(DateTime.now()),
                          modifiedBy = None, modifiedDateTime = None,
                          expiredBy = None, expiredDateTime = None, expired = false,
                          vvhTimeStamp = vvhTimeStamp, geomModifiedDate = None,
                          attributes = mapPropertiesDefaultValue(source, 21)))
          newLanes.map(laneService.createWithoutTransaction(_, user))
        } else {
          // for loop/map all something like this links.map(link =>{add} )
          val newLane = PersistedLane(id = 0, linkId = 0, sideCode = 0,
                                      laneCode = laneCode(source).get, municipalityCode = municipalityCodeVKM,
                                      startMeasure = startMeasureFromVKMOrVVH, endMeasure = endMeasureFromVKMOrVVH,
                                      createdBy = Some(user), createdDateTime = Some(DateTime.now()),
                                      modifiedBy = None, modifiedDateTime = None,
                                      expiredBy = None, expiredDateTime = None, expired = false,
                                      vvhTimeStamp = vvhTimeStamp, geomModifiedDate = None,
                                      attributes = newLaneProperties)
          laneService.createWithoutTransaction(newLane, user)
        }
      }
    }

    throw new NotImplementedError()
  }
  // change type 3
  private def transferLane(changeInfo:ChangeInformation):Long = {
    throw new NotImplementedError()
  }
  // change type 4
  private def renumbering(changeInfo:ChangeInformation):Long = {
    throw new NotImplementedError()
  }
  // change type 5
  private def expiringLane(changeInfo:ChangeInformation):Long= {
    throw new NotImplementedError()
  }

  def process() = {
    val changeInformations = integrationViiteClient.fetchRoadwayChangesChanges(new DateTime().minusDays(1))
    
    if(changeInformations.isDefined){
      withDynTransaction {
        changeInformations.get.foreach(change=>{
          // add operation
        })
      }
    }
  }

}
