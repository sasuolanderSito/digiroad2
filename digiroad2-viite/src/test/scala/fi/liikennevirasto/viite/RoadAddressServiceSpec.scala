package fi.liikennevirasto.viite

import java.util.{Date, Properties}

import fi.liikennevirasto.digiroad2.FeatureClass.AllOthers
import fi.liikennevirasto.digiroad2.RoadLinkType.NormalRoadLinkType
import fi.liikennevirasto.digiroad2._
import fi.liikennevirasto.digiroad2.asset.ConstructionType.InUse
import fi.liikennevirasto.digiroad2.asset.LinkGeomSource.{HistoryLinkInterface, NormalLinkInterface}
import fi.liikennevirasto.digiroad2.asset.TrafficDirection.BothDirections
import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.linearasset.RoadLink
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.viite.util._
import fi.liikennevirasto.digiroad2.user.{Configuration, User}
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.viite.dao.Discontinuity.Discontinuous
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.model.{Anomaly, RoadAddressLink, RoadAddressLinkPartitioner}
import fi.liikennevirasto.viite.process.RoadAddressFiller.LRMValueAdjustment
import fi.liikennevirasto.viite.process.{LinkRoadAddressCalculator, RoadAddressFiller}
import fi.liikennevirasto.viite.util.StaticTestData
import org.joda.time.DateTime
import org.mockito.ArgumentCaptor
import org.mockito.Matchers._
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FunSuite, Matchers}
import slick.driver.JdbcDriver.backend.Database
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery
import slick.jdbc.StaticQuery.interpolation

class RoadAddressServiceSpec extends FunSuite with Matchers{
  val mockRoadLinkService = MockitoSugar.mock[RoadLinkService]
  val mockEventBus = MockitoSugar.mock[DigiroadEventBus]
  val roadAddressService = new RoadAddressService(mockRoadLinkService,mockEventBus) {
    override def withDynSession[T](f: => T): T = f
    override def withDynTransaction[T](f: => T): T = f
  }
  def runWithRollback[T](f: => T): T = {
    Database.forDataSource(OracleDatabase.ds).withDynTransaction {
      val t = f
      dynamicSession.rollback()
      t
    }
  }

  private def calibrationPoint(geometry: Seq[Point], calibrationPoint: Option[CalibrationPoint]) = {
    calibrationPoint match {
      case Some(point) =>
        val mValue = point.segmentMValue match {
          case 0.0 => 0.0
          case _ => Math.min(point.segmentMValue, GeometryUtils.geometryLength(geometry))
        }
        Option(Seq(("point", GeometryUtils.calculatePointFromLinearReference(geometry, mValue)), ("value", point.addressMValue)).toMap)
      case _ => None
    }
  }

  def roadAddressLinkToApi(roadLink: RoadAddressLink): Map[String, Any] = {
    Map(
      "segmentId" -> roadLink.id,
      "linkId" -> roadLink.linkId,
      "mmlId" -> roadLink.attributes.get("MTKID"),
      "points" -> roadLink.geometry,
      "calibrationPoints" -> Seq(calibrationPoint(roadLink.geometry, roadLink.startCalibrationPoint),
        calibrationPoint(roadLink.geometry, roadLink.endCalibrationPoint)),
      "administrativeClass" -> roadLink.administrativeClass.toString,
      "linkType" -> roadLink.linkType.value,
      "modifiedAt" -> roadLink.modifiedAt,
      "modifiedBy" -> roadLink.modifiedBy,
      "municipalityCode" -> roadLink.attributes.get("MUNICIPALITYCODE"),
      "verticalLevel" -> roadLink.attributes.get("VERTICALLEVEL"),
      "roadNameFi" -> roadLink.attributes.get("ROADNAME_FI"),
      "roadNameSe" -> roadLink.attributes.get("ROADNAME_SE"),
      "roadNameSm" -> roadLink.attributes.get("ROADNAME_SM"),
      "minAddressNumberRight" -> roadLink.attributes.get("FROM_RIGHT"),
      "maxAddressNumberRight" -> roadLink.attributes.get("TO_RIGHT"),
      "minAddressNumberLeft" -> roadLink.attributes.get("FROM_LEFT"),
      "maxAddressNumberLeft" -> roadLink.attributes.get("TO_LEFT"),
      "roadNumber" -> roadLink.roadNumber,
      "roadPartNumber" -> roadLink.roadPartNumber,
      "elyCode" -> roadLink.elyCode,
      "trackCode" -> roadLink.trackCode,
      "startAddressM" -> roadLink.startAddressM,
      "endAddressM" -> roadLink.endAddressM,
      "discontinuity" -> roadLink.discontinuity,
      "endDate" -> roadLink.endDate)
  }

  test("testGetCalibrationPoints") {
    //TODO
  }

  test("testRoadClass") {
    //TODO
  }

  test("test getRoadLinkFromVVH should have specific fields (still to be defined) not empty"){

    OracleDatabase.withDynTransaction {

      val roadLinks = Seq(RoadAddressLink(0,5171208,Seq(Point(532837.14110884,6993543.6296834,0.0),Point(533388.14110884,6994014.1296834,0.0)),0.0,Municipality, UnknownLinkType, NormalRoadLinkType, InUse, NormalLinkInterface, RoadType.MunicipalityStreetRoad,None,None,Map("linkId" ->5171208, "segmentId" -> 63298 ),5,205,1,0,0,0,1,"2015-01-01","2016-01-01",0.0,0.0,SideCode.Unknown,None,None, Anomaly.None, 0))
      val partitionedRoadLinks = RoadAddressLinkPartitioner.partition(roadLinks)
      partitionedRoadLinks.map {
        _.map(roadAddressLinkToApi)
      }
      val roadPartNumber = partitionedRoadLinks.head.head.roadPartNumber
      val roadNumber = partitionedRoadLinks.head.head.roadNumber
      val trackCode = partitionedRoadLinks.head.head.trackCode
      val segmentId = partitionedRoadLinks.head.head.id
      val constructionType = partitionedRoadLinks.head.head.constructionType.value

      segmentId should not be None
      roadNumber should be (5)
      roadPartNumber should be (205)
      trackCode should be (1)
      constructionType should be (0)
    }
  }

  test("test createMissingRoadAddress should not add two equal roadAddresses"){
    runWithRollback {
      val roadAddressLinks = Seq(
        RoadAddressLink(0, 1611616, Seq(Point(374668.195, 6676884.282, 24.48399999999674), Point(374643.384, 6676882.176, 24.42399999999907)), 297.7533188814259, State, SingleCarriageway, NormalRoadLinkType, InUse, NormalLinkInterface, RoadType.PrivateRoadType,  Some("22.09.2016 14:51:28"), Some("dr1_conversion"), Map("linkId" -> 1611605, "segmentId" -> 63298), 1, 3, 0, 0, 0, 0, 0, "", "", 0.0, 0.0, SideCode.Unknown, None, None, Anomaly.None, 0)
      )
      val oldMissingRA = RoadAddressDAO.getMissingRoadAddresses(Set()).size
      roadAddressLinks.foreach { links =>
        RoadAddressDAO.createMissingRoadAddress(
          MissingRoadAddress(links.linkId, Some(links.startAddressM), Some(links.endAddressM), RoadType.PublicRoad, Some(links.roadNumber),
            Some(links.roadPartNumber), None, None, Anomaly.NoAddressGiven))
      }
      val linksFromDB = getSpecificMissingRoadAddresses(roadAddressLinks(0).linkId)
      RoadAddressDAO.getMissingRoadAddresses(Set()) should have size(oldMissingRA)
      linksFromDB(0)._2 should be(0)
      linksFromDB(0)._3 should be(0)
      linksFromDB(0)._4 should be(1)
      linksFromDB(0)._5 should be(3)
      linksFromDB(0)._6 should be(1)
    }
  }

  private def getSpecificMissingRoadAddresses(linkId :Long): List[(Long, Long, Long, Long, Long, Int)] = {
    sql"""
          select link_id, start_addr_m, end_addr_m, road_number, road_part_number, anomaly_code
            from missing_road_address where link_id = $linkId
      """.as[(Long, Long, Long, Long, Long, Int)].list
  }

  test("Check the correct return of a RoadAddressLink by Municipality") {
    val municipalityId = 235

    val modifificationDate = "1455274504000l"
    val modificationUser = "testUser"
    runWithRollback {
      val (linkId) = sql""" Select pos.LINK_ID
                                From ROAD_ADDRESS ra inner join LRM_POSITION pos on ra.LRM_POSITION_ID = pos.id
                                Order By ra.id asc""".as[Long].firstOption.get
      val roadLink = RoadLink(linkId, Seq(Point(50200, 7630000.0, 0.0), Point(50210, 7630000.0, 10.0)), 0, Municipality, 0, TrafficDirection.TowardsDigitizing, Freeway, Some(modifificationDate), Some(modificationUser), attributes = Map("MUNICIPALITYCODE" -> BigInt(235)))

      when(mockRoadLinkService.getViiteRoadLinksFromVVHByMunicipality(municipalityId)).thenReturn(Seq(roadLink))
      val roadAddressLink = roadAddressService.getRoadAddressesLinkByMunicipality(municipalityId)

      roadAddressLink.isInstanceOf[Seq[RoadAddressLink]] should be(true)
      roadAddressLink.nonEmpty should be(true)
      roadAddressLink.head.linkId should be(linkId)
      roadAddressLink.head.attributes.contains("MUNICIPALITYCODE") should be (true)
      roadAddressLink.head.attributes.get("MUNICIPALITYCODE") should be (Some(municipalityId))
    }
  }

  test("check PO temporary restrictions"){

    val l1: Long = 5168616
    val l2: Long = 5168617
    val l3: Long = 5168618 //meet dropSegmentsOutsideGeometry restrictions
    val l4: Long = 5168619 //meet extendToGeometry restrictions
    val l5: Long = 5168620 //meet capToGeometry restrictions

    val roadLinksSeq = Seq(RoadLink(l1, Seq(Point(532686.507,6997280.405,99.28599999999278), Point(532682.145,6997307.366,98.99700000000303),
      Point(532673.695,6997367.113,99.11299999999756), Point(532665.336,6997428.384,99.31699999999546), Point(532655.448,6997496.461,99.58400000000256),
      Point(532647.278,6997553.917,99.76600000000326), Point(532640.024,6997604.115,99.93700000000536), Point(532635.796,6997630.174,100.08000000000175),
      Point(532635.575,6998631.749,100.07700000000477)), 355.82666256921844, State, 99, BothDirections, UnknownLinkType, Some("25.11.2013 02:00:00"), Some("vvh_modified"), Map("MUNICIPALITYCODE" -> BigInt(235))),
      RoadLink(l2, Seq(Point(532686.507,6997280.405,99.28599999999278), Point(532682.145,6997307.366,98.99700000000303),
        Point(532673.695,6997367.113,99.11299999999756), Point(532665.336,6997428.384,99.31699999999546), Point(532655.448,6997496.461,99.58400000000256),
        Point(532647.278,6997553.917,99.76600000000326), Point(532640.024,6997604.115,99.93700000000536), Point(532635.796,6997630.174,100.08000000000175),
        Point(532635.575,6997631.749,100.07700000000477)), 355.02666256921844, State, 99, BothDirections, UnknownLinkType, Some("25.11.2013 02:00:00"), Some("vvh_modified"), Map("MUNICIPALITYCODE" -> BigInt(235))),
      RoadLink(l3, Seq(Point(532686.507,6997280.405,99.28599999999278), Point(532682.145,6997307.366,98.99700000000303),
        Point(532673.695,6997367.113,99.11299999999756), Point(532665.336,6997428.384,99.31699999999546), Point(532655.448,6997496.461,99.58400000000256),
        Point(532647.278,6997553.917,99.76600000000326), Point(532640.024,6997604.115,99.93700000000536), Point(532635.796,6997630.174,100.08000000000175),
        Point(532635.575,6997631.749,100.07700000000477)), 355.02666256921844, State, 99, BothDirections, UnknownLinkType, Some("25.11.2013 02:00:00"), Some("vvh_modified"), Map("MUNICIPALITYCODE" -> BigInt(235))),
      RoadLink(l4, Seq(Point(532686.507,6997280.405,99.28599999999278), Point(532682.145,6997307.366,98.99700000000303),
        Point(532673.695,6997367.113,99.11299999999756), Point(532665.336,6997428.384,99.31699999999546), Point(532655.448,6997496.461,99.58400000000256),
        Point(532647.278,6997553.917,99.76600000000326), Point(532640.024,6997604.115,99.93700000000536), Point(532635.796,6997630.174,100.08000000000175),
        Point(532635.575,6997632.749,100.07700000000477)), 355.02666256921844, State, 99, BothDirections, UnknownLinkType, Some("25.11.2013 02:00:00"), Some("vvh_modified"), Map("MUNICIPALITYCODE" -> BigInt(235))),
      RoadLink(l5, Seq(Point(532686.507,6997280.405,99.28599999999278), Point(532682.145,6997307.366,98.99700000000303),
        Point(532673.695,6997367.113,99.11299999999756), Point(532665.336,6997428.384,99.31699999999546), Point(532655.448,6997496.461,99.58400000000256),
        Point(532647.278,6997553.917,99.76600000000326), Point(532640.024,6997604.115,99.93700000000536), Point(532635.796,6997630.174,100.08000000000175),
        Point(532635.575,6997632.749,100.07700000000477)), 355.02666256921844, State, 99, BothDirections, UnknownLinkType, Some("25.11.2013 02:00:00"), Some("vvh_modified"), Map("MUNICIPALITYCODE" -> BigInt(235)))
    )
    val roadAddressLinksMap = Map(l2 -> Seq(RoadAddressLink(333012, l2, Seq(Point(532686.507,6997280.405,0.0), Point(532682.145,6997307.366,0.0),
      Point(532673.695,6997367.113,0.0),Point(532665.336,6997428.384,0.0), Point(532655.448,6997496.461,0.0),
      Point(532647.278,6997553.917,0.0),Point(532640.024,6997604.115,0.0), Point(532635.796,6997630.174,0.0),
      Point(532635.575,6997631.749,0.0)), 355.02666256921844, State, UnknownLinkType, NormalRoadLinkType, InUse, NormalLinkInterface, RoadType.PublicRoad, Some("29.10.2015 17:34:02"), Some("vvh_modified"), Map("linkId" -> 1611605, "segmentId" -> 63298), 5, 206, 0, 8, 5, 3446, 3800, "", "", 0.0, 355.027, SideCode.BothDirections, None, None, Anomaly.None, 0)),
      l1 -> Seq(RoadAddressLink(333013, l1, Seq(Point(532686.507,6997280.405,0.0), Point(532682.145,6997307.366,0.0),
        Point(532673.695,6997367.113,0.0),Point(532665.336,6997428.384,0.0), Point(532655.448,6997496.461,0.0),
        Point(532647.278,6997553.917,0.0),Point(532640.024,6997604.115,0.0), Point(532635.796,6997630.174,0.0),
        Point(532635.575,6997631.749,0.0)), 355.02666256921844, State, UnknownLinkType, NormalRoadLinkType, InUse, NormalLinkInterface, RoadType.PublicRoad, Some("29.10.2015 17:34:02"), Some("vvh_modified"), Map("linkId" -> 1611605, "segmentId" -> 63298), 5, 206, 0, 8, 5, 3446, 3800, "", "", 0.0, 355.027, SideCode.BothDirections, None, None, Anomaly.None, 0)),
      l4 -> Seq(RoadAddressLink(333014, l4, Seq(Point(532686.507,6997280.405,0.0), Point(532682.145,6997307.366,0.0),
        Point(532673.695,6997367.113,0.0),Point(532665.336,6997428.384,0.0), Point(532655.448,6997496.461,0.0),
        Point(532647.278,6997553.917,0.0),Point(532640.024,6997604.115,0.0), Point(532635.796,6997630.174,0.0),
        Point(532635.575,6997631.749,0.0)), 355.02666256921844, State, UnknownLinkType, NormalRoadLinkType, InUse, NormalLinkInterface, RoadType.PublicRoad, Some("29.10.2015 17:34:02"), Some("vvh_modified"), Map("linkId" -> 1611605, "segmentId" -> 63298), 5, 206, 0, 8, 5, 3446, 3800, "", "", 354.0276, 355.029, SideCode.BothDirections, None, None,Anomaly.None, 0)),
      l3 -> Seq(RoadAddressLink(333015, l3, Seq(Point(532686.507,6997280.405,0.0), Point(532682.145,6997307.366,0.0),
        Point(532673.695,6997367.113,0.0),Point(532665.336,6997428.384,0.0), Point(532655.448,6997496.461,0.0),
        Point(532647.278,6997553.917,0.0),Point(532640.024,6997604.115,0.0), Point(532635.796,6997630.174,0.0),
        Point(532637.575,6996631.749,0.0)), 355.02666256921844, State, UnknownLinkType, NormalRoadLinkType, InUse, NormalLinkInterface, RoadType.PublicRoad, Some("29.10.2015 17:34:02"), Some("vvh_modified"), Map("linkId" -> 1611605, "segmentId" -> 63298), 5, 206, 0, 8, 5, 3446, 3800, "", "", 355.82666256921844, 355.927, SideCode.BothDirections, None, None, Anomaly.None, 0)),
      l5 -> Seq(RoadAddressLink(333016, l5, Seq(Point(532686.507,6997280.405,0.0), Point(532682.145,6997307.366,0.0),
        Point(532673.695,6997367.113,0.0),Point(532665.336,6997428.384,0.0), Point(532655.448,6997496.461,0.0),
        Point(532647.278,6997553.917,0.0),Point(532640.024,6997604.115,0.0), Point(532635.796,6997630.174,0.0)),
        352.0, State, UnknownLinkType, NormalRoadLinkType, InUse, NormalLinkInterface, RoadType.PublicRoad, Some("29.10.2015 17:34:02"), Some("vvh_modified"), Map("linkId" -> 1611605, "segmentId" -> 63298), 5, 206, 0, 8, 5, 3446, 3800, "", "", 355.82666256921844, 355.927, SideCode.BothDirections, None, None, Anomaly.None, 0))
    )

    val (topology, changeSet) = RoadAddressFiller.fillTopology(roadLinksSeq, roadAddressLinksMap)
    changeSet.adjustedMValues.size should be (2)
    changeSet.toFloatingAddressIds.size should be (1)
    changeSet.toFloatingAddressIds.contains(333015L) should be (true)
    changeSet.adjustedMValues.map(_.linkId) should be (Seq(l4, l5))
  }

  test("LRM modifications are published"){
    val localMockRoadLinkService = MockitoSugar.mock[RoadLinkService]
    val localMockEventBus = MockitoSugar.mock[DigiroadEventBus]
    val localRoadAddressService = new RoadAddressService(localMockRoadLinkService,localMockEventBus)
    val boundingRectangle = BoundingRectangle(Point(533341.472,6988382.846), Point(533333.28,6988419.385))
    val filter = OracleDatabase.boundingBoxFilter(boundingRectangle, "geometry")
    runWithRollback {
      val modificationDate = "1455274504000l"
      val modificationUser = "testUser"
      val query = s"""select pos.LINK_ID, pos.end_measure
        from ROAD_ADDRESS ra inner join LRM_POSITION pos on ra.LRM_POSITION_ID = pos.id
        where $filter and (ra.valid_to > sysdate or ra.valid_to is null) order by ra.id asc"""
      val (linkId, endM) = StaticQuery.queryNA[(Long, Double)](query).firstOption.get
      val roadLink = RoadLink(linkId, Seq(Point(0.0, 0.0), Point(endM + .5, 0.0)), endM + .5, Municipality, 1, TrafficDirection.TowardsDigitizing, Freeway, Some(modificationDate), Some(modificationUser), attributes = Map("MUNICIPALITYCODE" -> BigInt(235)))
      when(localMockRoadLinkService.getViiteRoadLinksFromVVH(any[BoundingRectangle], any[Seq[(Int,Int)]], any[Set[Int]], any[Boolean], any[Boolean])).thenReturn(Seq(roadLink))
      when(localMockRoadLinkService.getComplementaryRoadLinksFromVVH(any[BoundingRectangle], any[Set[Int]])).thenReturn(Seq.empty)
      when(localMockRoadLinkService.getViiteRoadLinksHistoryFromVVH(any[Set[Long]])).thenReturn(Seq.empty)
      val captor: ArgumentCaptor[Iterable[Any]] = ArgumentCaptor.forClass(classOf[Iterable[Any]])
      reset(localMockEventBus)
      val links = localRoadAddressService.getRoadAddressLinks(boundingRectangle, Seq(), Set())
      links.size should be (1)
      verify(localMockEventBus, times(3)).publish(any[String], captor.capture)
      val capturedAdjustments = captor.getAllValues
      val missing = capturedAdjustments.get(0)
      val adjusting = capturedAdjustments.get(1)
      val floating = capturedAdjustments.get(2)
      missing.size should be (0)
      adjusting.size should be (1)
      floating.size should be (0)
      adjusting.head.asInstanceOf[LRMValueAdjustment].endMeasure should be (Some(endM+.5))
    }
  }

  test("Floating check gets geometry updated") {
    val roadLink = VVHRoadlink(5171359L, 1, Seq(Point(0.0, 0.0), Point(0.0, 31.045)), State, TrafficDirection.BothDirections,
      AllOthers, None, Map(), ConstructionType.InUse, LinkGeomSource.NormalLinkInterface)
    when(mockRoadLinkService.getCurrentAndComplementaryVVHRoadLinks(Set(5171359L))).thenReturn(Seq(roadLink))
    runWithRollback {
      val addressList = RoadAddressDAO.fetchByLinkId(Set(5171359L))
      addressList should have size (1)
      val address = addressList.head
      address.floating should be (false)
      address.geom shouldNot be (roadLink.geometry)
      roadAddressService.checkRoadAddressFloatingWithoutTX(Set(address.id))
      dynamicSession.rollback()
      val addressUpdated = RoadAddressDAO.queryById(Set(address.id)).head
      addressUpdated.geom shouldNot be (address.geom)
      addressUpdated.geom should be(roadLink.geometry)
      addressUpdated.floating should be (false)
    }
  }

  test("Floating check gets floating flag updated, not geometry") {
    when(mockRoadLinkService.getCurrentAndComplementaryVVHRoadLinks(Set(5171359L))).thenReturn(Nil)
    runWithRollback {
      val addressList = RoadAddressDAO.fetchByLinkId(Set(5171359L))
      addressList should have size (1)
      val address = addressList.head
      address.floating should be (false)
      roadAddressService.checkRoadAddressFloatingWithoutTX(Set(address.id))
      dynamicSession.rollback()
      val addressUpdated = RoadAddressDAO.queryById(Set(address.id)).head
      addressUpdated.geom should be (address.geom)
      addressUpdated.floating should be (true)
    }
  }

  test("merge road addresses") {
    runWithRollback {
      val addressList = RoadAddressDAO.fetchByLinkId(Set(5171285L, 5170935L, 5171863L))
      addressList should have size (3)
      val address = addressList.head
      val newAddr = address.copy(id = -1000L, startAddrMValue = addressList.map(_.startAddrMValue).min,
        endAddrMValue = addressList.map(_.endAddrMValue).max)
      val merger = RoadAddressMerge(addressList.map(_.id).toSet, Seq(newAddr))
      roadAddressService.mergeRoadAddressInTX(merger)
      val addressListMerged = RoadAddressDAO.fetchByLinkId(Set(5171285L, 5170935L, 5171863L))
      addressListMerged should have size (1)
      addressListMerged.head.linkId should be (address.linkId)
    }
    runWithRollback {
      RoadAddressDAO.fetchByLinkId(Set(5171285L, 5170935L, 5171863L)) should have size (3)
    }
  }


  test("transferRoadAddress should keep calibration points") {
    runWithRollback {
      val floatGeom = Seq(Point(532837.14110884, 6993543.6296834, 0.0), Point(533388.14110884, 6994014.1296834, 0.0))
      val floatGeomLength = GeometryUtils.geometryLength(floatGeom)
      val floatingLinks = Seq(
        RoadAddressLink(15171208, 15171208, floatGeom,
          floatGeomLength, Municipality, SingleCarriageway, NormalRoadLinkType, InUse, HistoryLinkInterface, RoadType.MunicipalityStreetRoad,
          None, None, Map("linkId" -> 15171208, "segmentId" -> 63298), 5, 205, 1, 0, 0, 0, 500, "2015-01-01", "2016-01-01", 0.0, floatGeomLength,
          SideCode.TowardsDigitizing, Option(CalibrationPoint(15171208, 0.0, 0)), Option(CalibrationPoint(15171208, floatGeomLength, 500)), Anomaly.None, 0))
      RoadAddressDAO.create(floatingLinks.map(roadAddressLinkToRoadAddress(true)))

      val cutPoint = GeometryUtils.calculatePointFromLinearReference(floatGeom, 230.0).get
      val geom1 = Seq(floatGeom.head, cutPoint)
      val geom2 = Seq(cutPoint, floatGeom.last)
      val targetLinks = Seq(
        RoadAddressLink(0, 15171208, geom1,
          GeometryUtils.geometryLength(geom1), Municipality, SingleCarriageway, NormalRoadLinkType, InUse, HistoryLinkInterface, RoadType.MunicipalityStreetRoad,
          None, None, Map("linkId" -> 15171208, "segmentId" -> 63298), 5, 205, 1, 0, 0, 0, 1, "2015-01-01", "2016-01-01", 0.0, 0.0,
          SideCode.Unknown, None, None, Anomaly.None, 0),
        RoadAddressLink(0, 15171209, geom2,
          GeometryUtils.geometryLength(geom2), Municipality, SingleCarriageway, NormalRoadLinkType, InUse, HistoryLinkInterface, RoadType.MunicipalityStreetRoad,
          None, None, Map("linkId" -> 15171209, "segmentId" -> 63299), 5, 205, 1, 0, 0, 1, 2, "2015-01-01", "2016-01-01", 0.0, 0.0,
          SideCode.Unknown, None, None, Anomaly.None, 0))
      when(mockRoadLinkService.getViiteCurrentAndHistoryRoadLinksFromVVH(any[Set[Long]])).thenReturn((targetLinks.map(roadAddressLinkToRoadLink), floatingLinks.map(roadAddressLinkToHistoryLink)))
      when(mockRoadLinkService.getViiteRoadLinksHistoryFromVVH(any[Set[Long]])).thenReturn(floatingLinks.map(roadAddressLinkToHistoryLink))
      when(mockRoadLinkService.getRoadLinksFromVVH(any[BoundingRectangle], any[BoundingRectangle])).thenReturn(targetLinks.map(roadAddressLinkToRoadLink))
      val newLinks = roadAddressService.transferRoadAddress(floatingLinks, targetLinks, User(1L, "foo", new Configuration()))
      newLinks should have size (2)
      newLinks.filter(_.linkId == 15171208).head.endCalibrationPoint should be (None)
      newLinks.filter(_.linkId == 15171209).head.startCalibrationPoint should be (None)
      newLinks.filter(_.linkId == 15171208).head.startCalibrationPoint.isEmpty should be (false)
      newLinks.filter(_.linkId == 15171209).head.endCalibrationPoint.isEmpty should be (false)
      val startCP = newLinks.filter(_.linkId == 15171208).head.startCalibrationPoint.get
      val endCP = newLinks.filter(_.linkId == 15171209).head.endCalibrationPoint.get
      startCP.segmentMValue should be (0.0)
      endCP.segmentMValue should be (GeometryUtils.geometryLength(geom2) +- 0.1)
      startCP.addressMValue should be (0L)
      endCP.addressMValue should be (500L)
    }
  }

  private def roadAddressLinkToRoadLink(roadAddressLink: RoadAddressLink) = {
    RoadLink(roadAddressLink.linkId,roadAddressLink.geometry
      ,GeometryUtils.geometryLength(roadAddressLink.geometry),roadAddressLink.administrativeClass,99,TrafficDirection.AgainstDigitizing
      ,SingleCarriageway,Some("25.06.2015 03:00:00"), Some("vvh_modified"),Map("MUNICIPALITYCODE" -> BigInt.apply(749)),
      InUse,NormalLinkInterface)
  }

  private def roadAddressLinkToHistoryLink(roadAddressLink: RoadAddressLink) = {
    VVHHistoryRoadLink(roadAddressLink.linkId,749,roadAddressLink.geometry
      ,roadAddressLink.administrativeClass,TrafficDirection.AgainstDigitizing
      ,FeatureClass.AllOthers,123,123,Map("MUNICIPALITYCODE" -> BigInt.apply(749)))
  }

  private def roadAddressLinkToRoadAddress(floating: Boolean)(l: RoadAddressLink) = {
    RoadAddress(l.id, l.roadNumber, l.roadPartNumber, Track.apply(l.trackCode.toInt), Discontinuity.apply(l.discontinuity.toInt),
      l.startAddressM, l.endAddressM, Option(new DateTime(new Date())), None, None, 0, l.linkId, l.startMValue, l.endMValue, l.sideCode, 0,
      (l.startCalibrationPoint, l.endCalibrationPoint), floating, l.geometry)
  }

  test("recalculate one track road with single part") {
    runWithRollback {
      val roads = RoadAddressDAO.fetchByRoadPart(833, 1)
      val adjusted = LinkRoadAddressCalculator.recalculate(roads)
      adjusted.head.endAddrMValue should be (22)
      adjusted.lift(1).get.endAddrMValue should be (400)
      adjusted.filter(_.startAddrMValue == 0) should have size (1)
    }
  }

  test("Defloating road links on road 1130 part 4") {
    val links = StaticTestData.road1130Links.filter(_.roadNumber.getOrElse("") == "1130").filter(_.attributes("ROADPARTNUMBER").asInstanceOf[BigInt].intValue == 4)
    val history = StaticTestData.road1130HistoryLinks
    val roadAddressService = new RoadAddressService(mockRoadLinkService,mockEventBus)
    when(mockRoadLinkService.getViiteCurrentAndHistoryRoadLinksFromVVH(any[Set[Long]])).thenReturn((StaticTestData.road1130Links, StaticTestData.road1130HistoryLinks))
    when(mockRoadLinkService.getViiteRoadLinksFromVVH(BoundingRectangle(Point(351714,6674367),Point(361946,6681967)), Seq((1,50000)), Set(), false, true)).thenReturn(links)
    when(mockRoadLinkService.getComplementaryRoadLinksFromVVH(any[BoundingRectangle], any[Set[Int]])).thenReturn(Seq())
    when(mockRoadLinkService.getViiteRoadLinksHistoryFromVVH(any[Set[Long]])).thenReturn(history)
    runWithRollback {
      val addressLinks = roadAddressService.getRoadAddressLinks(BoundingRectangle(Point(351714, 6674367), Point(361946, 6681967)), Seq((1, 50000)), Set(), false, true)
      addressLinks.count(_.id == 0L) should be(2) // >There should be 2 unknown address links
      addressLinks.forall(_.id == 0L) should be(false)
      addressLinks.count(_.roadLinkSource == LinkGeomSource.HistoryLinkInterface) should be(4)
      // >There should be 4 floating links
      val replacement1s = addressLinks.filter(l => l.linkId == 1717639 || l.linkId == 499897217)
      val replacement1t = addressLinks.filter(l => l.linkId == 500130192)
      replacement1s.size should be(2)
      replacement1t.size should be(1)
      val result1 = roadAddressService.transferRoadAddress(replacement1s, replacement1t, User(0L, "foo", Configuration())).sortBy(_.startAddrMValue)
      sanityCheck(result1)
      result1.head.startMValue should be(0.0)
      result1.head.startAddrMValue should be(replacement1s.map(_.startAddressM).min)
      result1.last.endAddrMValue should be(replacement1s.map(_.endAddressM).max)

      val replacement2s = addressLinks.filter(l => l.linkId == 1718096 || l.linkId == 1718097)
      val replacement2t = addressLinks.filter(l => l.linkId == 500130201)
      replacement2s.size should be(2)
      replacement2t.size should be(1)
      val result2 = roadAddressService.transferRoadAddress(replacement2s, replacement2t, User(0L, "foo", Configuration())).sortBy(_.startAddrMValue)
      sanityCheck(result2)

      result2.head.startMValue should be(0.0)
      result2.head.startAddrMValue should be(replacement2s.map(_.startAddressM).min)
      result2.last.endAddrMValue should be(replacement2s.map(_.endAddressM).max)
    }
  }

  test("GetFloatingAdjacents road links on road 75 part 2 sourceLinkId 5176142") {
    val roadAddressService = new RoadAddressService(mockRoadLinkService,mockEventBus)
    val road75FloatingAddresses = RoadAddress(367,75,2,Track.Combined,Discontinuity.Continuous,3532,3598,None,None,Some("tr"),70000389,5176142,0.0,65.259,SideCode.TowardsDigitizing,(None,None),true,List(Point(538889.668,6999800.979,0.0), Point(538912.266,6999862.199,0.0)))

    when(mockRoadLinkService.getViiteCurrentAndHistoryRoadLinksFromVVH(any[Set[Long]])).thenReturn(
      (Seq(), Stream()))

    val result = roadAddressService.getFloatingAdjacent(Set(road75FloatingAddresses.linkId), road75FloatingAddresses.linkId, road75FloatingAddresses.roadNumber, road75FloatingAddresses.roadPartNumber, road75FloatingAddresses.track.value)
    result.size should be (0)
  }

  test("GetAdjacents road links on road 75 part 2 targetLinkId 5176147") {
    val roadAddressService = new RoadAddressService(mockRoadLinkService,mockEventBus)
    val road75TargetLink = Seq(RoadLink(5176147,List(Point(538909.794,6999855.848,101.153999999995), Point(538915.453,6999869.226,100.69899999999325), Point(538918.052,6999875.753,101.44500000000698)),21.551092889334765,State,99,BothDirections,UnknownLinkType,Some(""),Some("vvh_modified"),Map("TO_RIGHT" -> 873, "LAST_EDITED_DATE" -> BigInt.apply(0L), "FROM_LEFT" -> 872, "MTKHEREFLIP" -> 0, "MTKID" -> 441179395, "ROADNAME_FI" -> "Nilsiäntie", "VERTICALACCURACY" -> 201, "VALIDFROM" -> BigInt.apply(0L), "CONSTRUCTIONTYPE" -> 0, "SURFACETYPE" -> 2, "MTKCLASS" -> 12121, "ROADPARTNUMBER" -> 2, "points" -> List(Map("x" -> 538909.794, "y" -> 6999855.848, "z" -> 101.153999999995, "m" -> 0), Map("x" -> 538915.453, "y" -> 6999869.226, "z" -> 100.69899999999325, "m" -> 14.525699999998324), Map("x" -> 538918.052, "y" -> 6999875.753, "z" -> 101.44500000000698, "m" -> 21.55109999999695)), "OBJECTID" -> 2739051, "TO_LEFT" -> 874, "VERTICALLEVEL" -> 0, "MUNICIPALITYCODE" -> 749, "FROM_RIGHT" -> 871, "CREATED_DATE" -> BigInt.apply(0L), "GEOMETRY_EDITED_DATE" -> BigInt.apply(0L), "HORIZONTALACCURACY" -> 3000, "ROADNUMBER" -> 75),InUse,NormalLinkInterface))
    val roadLinks = Seq(
      RoadLink(5176153,List(Point(538918.701,6999861.318,97.69500000000698), Point(538908.109,6999873.097,97.49700000000303)),15.840937630113233,Unknown,8,BothDirections,CycleOrPedestrianPath,Some("08.06.2017 18:02:01"),Some("automatic_generation"),Map("MTKHEREFLIP" -> 1, "MTKID" -> 1046479017, "VERTICALACCURACY" -> 201, "VALIDFROM" -> BigInt.apply(1418083200000L), "CONSTRUCTIONTYPE" -> 0, "SURFACETYPE" -> 2, "MTKCLASS" -> 12314, "points" -> List(Map("x" -> 538918.701, "y" -> 6999861.318, "z" -> 97.69500000000698, "m" -> 0), Map("x" -> 538908.109, "y" -> 6999873.097, "z" -> 97.49700000000303, "m" -> 15.8408999999956)), "OBJECTID" -> 2739057, "VERTICALLEVEL" -> BigInt.apply(-1), "MUNICIPALITYCODE" -> BigInt.apply(749), "CREATED_DATE" -> BigInt.apply(1446132842000L) , "HORIZONTALACCURACY" -> 15000),InUse,NormalLinkInterface),
      RoadLink(5176148,List(Point(538947.853,6999873.982,100.79399999999441), Point(538933.887,6999875.424,101.00699999999779), Point(538918.052,6999875.753,101.44500000000698)),29.878663844853985,Private,6,BothDirections,SingleCarriageway,Some("08.06.2017 18:02:01"),Some("automatic_generation"),Map("MTKHEREFLIP" -> 1, "MTKID" -> 1046464265, "VERTICALACCURACY" -> 201, "VALIDFROM" -> BigInt.apply(1418083200000L), "CONSTRUCTIONTYPE" -> 0, "SURFACETYPE" -> 1, "MTKCLASS" -> 12141, "points" -> List(Map("x" -> 538947.853, "y" -> 6999873.982, "z" -> 100.79399999999441, "m" -> 0), Map("x" -> 538933.887, "y" -> 6999875.424, "z" -> 101.00699999999779, "m" -> 14.040200000003097), Map("x" -> 538918.052, "y" -> 6999875.753, "z" -> 101.44500000000698, "m" -> 29.878700000001118)), "OBJECTID" -> 2739052, "VERTICALLEVEL" -> BigInt.apply(0), "MUNICIPALITYCODE" -> BigInt.apply(749), "CREATED_DATE" -> BigInt.apply(1446132842000L), "HORIZONTALACCURACY" -> 2000),InUse,NormalLinkInterface),
      RoadLink(499836959,List(Point(538792.385,6999181.636,98.24300000000221), Point(538790.62,6999210.402,98.5280000000057), Point(538789.2,6999243.536,98.74000000000524), Point(538787.224,6999284.683,98.7219999999943), Point(538784.755,6999334.547,98.10700000000361), Point(538783.407,6999371.984,97.54200000000128), Point(538782.819,6999399.938,97.05400000000373), Point(538783.484,6999433.826,96.68099999999686), Point(538785.575,6999467.829,96.61800000000221), Point(538787.164,6999483.295,96.68700000000536), Point(538792.141,6999514.696,96.99599999999919), Point(538801.236,6999555.027,97.4829999999929), Point(538810.254,6999586.333,97.86900000000605), Point(538819.572,6999614.06,98.2039999999979), Point(538834.679,6999655.645,98.71799999999348), Point(538845.987,6999685.356,99.0850000000064), Point(538864.988,6999735.798,99.71499999999651), Point(538880.908,6999778.072,100.24899999999616), Point(538889.669,6999800.979,100.51200000000244), Point(538896.546,6999818.978,100.73200000000361), Point(538909.551,6999855.126,101.1469999999972), Point(538909.794,6999855.848,101.153999999995)),695.0810638900756,State,99,BothDirections,UnknownLinkType,Some("07.04.2017 14:20:02"),Some("vvh_modified"),Map("TO_RIGHT" -> 869, "LAST_EDITED_DATE" -> BigInt.apply(1491564002000L), "FROM_LEFT" -> 796, "MTKHEREFLIP" -> 0, "MTKID" -> 318861771, "ROADNAME_FI" -> "Nilsiäntie", "VERTICALACCURACY" -> 201, "VALIDFROM" -> BigInt.apply(1418083200000L), "CONSTRUCTIONTYPE" -> 0, "SURFACETYPE" -> 2, "MTKCLASS" -> 12121, "ROADPARTNUMBER" -> 2, "points" -> List(Map("x" -> 538792.385, "y" -> 6999181.636, "z" -> 98.24300000000221, "m" -> 0), Map("x" -> 538790.62, "y" -> 6999210.402, "z" -> 98.5280000000057, "m" -> 28.820099999997183), Map("x" -> 538789.2, "y" -> 6999243.536, "z" -> 98.74000000000524, "m" -> 61.98450000000594), Map("x" -> 538787.224, "y" -> 6999284.683, "z" -> 98.7219999999943, "m" -> 103.17889999999898), Map("x" -> 538784.755, "y" -> 6999334.547, "z" -> 98.10700000000361, "m" -> 153.10400000000664), Map("x" -> 538783.407, "y" -> 6999371.984, "z" -> 97.54200000000128, "m" -> 190.56530000000203), Map("x" -> 538782.819, "y" -> 6999399.938, "z" -> 97.05400000000373, "m" -> 218.52550000000338), Map("x" -> 538783.484, "y" -> 6999433.826, "z" -> 96.68099999999686, "m" -> 252.41999999999825), Map("x" -> 538785.575, "y" -> 6999467.829, "z" -> 96.61800000000221, "m" -> 286.4872000000032), Map("x" -> 538787.164, "y" -> 6999483.295, "z" -> 96.68700000000536, "m" -> 302.03459999999905), Map("x" -> 538792.141, "y" -> 6999514.696, "z" -> 96.99599999999919, "m" -> 333.82760000000417), Map("x" -> 538801.236, "y" -> 6999555.027, "z" -> 97.4829999999929, "m" -> 375.17140000000654), Map("x" -> 538810.254, "y" -> 6999586.333, "z" -> 97.86900000000605, "m" -> 407.75040000000445), Map("x" -> 538819.572, "y" -> 6999614.06, "z" -> 98.2039999999979, "m" -> 437.0011999999988), Map("x" -> 538834.679, "y" -> 6999655.645, "z" -> 98.71799999999348, "m" -> 481.24520000000484), Map("x" -> 538845.987, "y" -> 6999685.356, "z" -> 99.0850000000064, "m" -> 513.0353999999934), Map("x" -> 538864.988, "y" -> 6999735.798, "z" -> 99.71499999999651, "m" -> 566.9375), Map("x" -> 538880.908, "y" -> 6999778.072, "z" -> 100.24899999999616, "m" -> 612.1098000000056), Map("x" -> 538889.669, "y" -> 6999800.979, "z" -> 100.51200000000244, "m" -> 636.6349999999948), Map("x" -> 538896.546, "y" -> 6999818.978, "z" -> 100.73200000000361, "m" -> 655.9030000000057), Map("x" -> 538909.551, "y" -> 6999855.126, "z" -> 101.1469999999972, "m" -> 694.3193000000028), Map("x" -> 538909.794, "y" -> 6999855.848, "z" -> 101.153999999995, "m" -> 695.0810999999958)), "OBJECTID" -> 2739046, "TO_LEFT" -> 870, "VERTICALLEVEL" -> BigInt.apply(0), "MUNICIPALITYCODE" -> BigInt.apply(749), "FROM_RIGHT" -> 795, "CREATED_DATE" -> BigInt.apply(1446132842000L), "GEOMETRY_EDITED_DATE" -> BigInt.apply(1472941768000L), "HORIZONTALACCURACY" -> 3000, "ROADNUMBER" -> 75),InUse,NormalLinkInterface),
      RoadLink(5176151,List(Point(538909.794,6999855.848,101.153999999995), Point(538905.753,6999857.568,101.08800000000338), Point(538901.355,6999859.052,100.9890000000014), Point(538898.319,6999859.816,100.8969999999972)),12.164095949095342,State,99,BothDirections,UnknownLinkType,Some("12.02.2016 12:55:04"),Some("vvh_modified"),Map("TO_RIGHT" -> 2, "LAST_EDITED_DATE" -> BigInt.apply(1455274504000L), "FROM_LEFT" -> 1, "MTKHEREFLIP" -> 1, "MTKID" -> 441179434, "ROADNAME_FI" -> "Kinnusentie", "VERTICALACCURACY" -> 201, "VALIDFROM" -> BigInt.apply(1418083200000L), "CONSTRUCTIONTYPE" -> 0, "SURFACETYPE" -> 2, "MTKCLASS" -> 12131, "points" -> List(Map("x" -> 538909.794, "y" -> 6999855.848, "z" -> 101.153999999995, "m" -> 0), Map("x" -> 538905.753, "y" -> 6999857.568, "z" -> 101.08800000000338, "m" -> 4.391799999997602), Map("x" -> 538901.355, "y" -> 6999859.052, "z" -> 100.9890000000014, "m" -> 9.033400000000256), Map("x" -> 538898.319, "y" -> 6999859.816, "z" -> 100.8969999999972, "m" -> 12.164099999994505)), "OBJECTID" -> 2739055, "TO_LEFT" -> 1, "VERTICALLEVEL" -> BigInt.apply(0), "MUNICIPALITYCODE" -> BigInt.apply(749), "FROM_RIGHT" -> 2, "CREATED_DATE" -> BigInt.apply(1446132842000L), "GEOMETRY_EDITED_DATE" -> BigInt.apply(1455274504000L), "HORIZONTALACCURACY" -> 3000),InUse,NormalLinkInterface),
      RoadLink(5176147,List(Point(538909.794,6999855.848,101.153999999995), Point(538915.453,6999869.226,100.69899999999325), Point(538918.052,6999875.753,101.44500000000698)),21.551092889334765,State,99,BothDirections,UnknownLinkType,Some("07.04.2017 14:20:02"),Some("vvh_modified"),Map("TO_RIGHT" -> 873, "LAST_EDITED_DATE" -> BigInt.apply(1491564002000L), "FROM_LEFT" -> 872, "MTKHEREFLIP" -> 0, "MTKID" -> 441179395, "ROADNAME_FI" -> "Nilsiäntie", "VERTICALACCURACY" -> 201, "VALIDFROM" -> BigInt.apply(1418083200000L), "CONSTRUCTIONTYPE" -> 0, "SURFACETYPE" -> 2, "MTKCLASS" -> 12121, "ROADPARTNUMBER" -> 2, "points" -> List(Map("x" -> 538909.794, "y" -> 6999855.848, "z" -> 101.153999999995, "m" -> 0), Map("x" -> 538915.453, "y" -> 6999869.226, "z" -> 100.69899999999325, "m" -> 14.525699999998324), Map("x" -> 538918.052, "y" -> 6999875.753, "z" -> 101.44500000000698, "m" -> 21.55109999999695)), "OBJECTID" -> 2739051, "TO_LEFT" -> 874, "VERTICALLEVEL" -> 0, "MUNICIPALITYCODE" -> BigInt.apply(749), "FROM_RIGHT" -> 871, "CREATED_DATE" -> BigInt.apply(1446132842000L), "GEOMETRY_EDITED_DATE" -> BigInt.apply(1455274504000L), "HORIZONTALACCURACY" -> 3000, "ROADNUMBER" -> 75),InUse,NormalLinkInterface),
      RoadLink(6479168,List(Point(538918.052,6999875.753,101.44500000000698), Point(538921.358,6999884.381,101.54099999999744), Point(538935.727,6999923.678,102.04499999999825), Point(538956.539,6999978.306,102.78399999999965), Point(538956.722,6999978.783,102.79399999999441), Point(538967.108,7000005.884,103.1079999999929), Point(538971.654,7000019.597,103.1420000000071)),153.5202644294187,State,99,BothDirections,UnknownLinkType,Some("07.04.2017 14:20:02"),Some("vvh_modified"),Map("TO_RIGHT" -> 883, "LAST_EDITED_DATE" -> BigInt.apply(1491564002000L), "FROM_LEFT" -> 876, "MTKHEREFLIP" -> 0, "MTKID" -> 318861789, "ROADNAME_FI" -> "Nilsiäntie", "VERTICALACCURACY" -> 201, "VALIDFROM" -> BigInt.apply(1418083200000L), "CONSTRUCTIONTYPE" -> 0, "SURFACETYPE" -> 2, "MTKCLASS" -> 12121, "ROADPARTNUMBER" -> 2, "points" -> List(Map("x" -> 538918.052, "y" -> 6999875.753, "z" -> 101.44500000000698, "m" -> 0), Map("x" -> 538921.358, "y" -> 6999884.381, "z" -> 101.54099999999744, "m" -> 9.239700000005541), Map("x" -> 538935.727, "y" -> 6999923.678, "z" -> 102.04499999999825, "m" -> 51.081300000005285), Map("x" -> 538956.539, "y" -> 6999978.306, "z" -> 102.78399999999965, "m" -> 109.53949999999895), Map("x" -> 538956.722, "y" -> 6999978.783, "z" -> 102.79399999999441, "m" -> 110.0503999999928), Map("x" -> 538967.108, "y" -> 7000005.884, "z" -> 103.1079999999929, "m" -> 139.07339999999385), Map("x" -> 538971.654, "y" -> 7000019.597, "z" -> 103.1420000000071, "m" -> 153.52030000000377)), "OBJECTID" -> 2739027, "TO_LEFT" -> 884, "VERTICALLEVEL" -> BigInt.apply(0), "MUNICIPALITYCODE" -> BigInt.apply(749), "FROM_RIGHT" -> 875, "CREATED_DATE" -> BigInt.apply(1446132842000L), "GEOMETRY_EDITED_DATE" -> BigInt.apply(1448028123000L), "HORIZONTALACCURACY" -> 3000, "ROADNUMBER" -> 75),InUse,NormalLinkInterface)
    )
    val changeInfo = Seq(
      ChangeInfo(Some(5176142),Some(499836959),318861771,1,Some(0.0),Some(58.44607544),Some(636.63498845),Some(695.08106389),1472941768000L),
      ChangeInfo(Some(5176109),Some(499836959),318861771,2,Some(0.0),Some(566.9374729),Some(0.0),Some(566.9374729),1472941768000L),
      ChangeInfo(Some(5176143),Some(499836959),318861771,2,Some(0.0),Some(69.69751556),Some(566.9374729),Some(636.63498845),1472941768000L),
      ChangeInfo(Some(5176142),Some(5176142),318861771,7,Some(0.0),Some(58.4456179),Some(0.0),Some(58.44607544),1455274504000L),
      ChangeInfo(Some(5176142),None,318861771,8,Some(58.4456179),Some(65.25904657),None,None,1455274504000L),
      ChangeInfo(Some(5176147),Some(5176147),441179395,3,Some(0.0),Some(14.74135819),Some(6.81226464),Some(21.55109289),1455274504000L),
      ChangeInfo(None,Some(5176147),441179395,4,None,None,Some(0.0),Some(6.81226464),1455274504000L),
      ChangeInfo(Some(5176151),Some(5176151),441179434,7,Some(11.41471577),Some(0.0),Some(0.0),Some(12.16409595),1455274504000L),
      ChangeInfo(Some(5176151),None,441179434,8,Some(0.0),Some(11.41471577),None,None,1455274504000L)
    )

    when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]], any[Boolean])).thenReturn(road75TargetLink)
    when(mockRoadLinkService.getRoadLinksAndChangesFromVVH(any[BoundingRectangle], any[Set[Int]])).thenReturn((roadLinks, changeInfo))

    val result = roadAddressService.getAdjacent(Set(road75TargetLink.head.linkId),road75TargetLink.head.linkId)
    result.size should be (3)
    GeometryUtils.areAdjacent(road75TargetLink.head.geometry, result(0).geometry) should be (true)
    GeometryUtils.areAdjacent(road75TargetLink.head.geometry, result(1).geometry) should be (true)
    GeometryUtils.areAdjacent(road75TargetLink.head.geometry, result(2).geometry) should be (true)
  }

  test("Defloating road links from three links to two links") {
    val sources = Seq(
      createRoadAddressLink(8000001L, 123L, Seq(Point(0.0,0.0), Point(10.0, 10.0)), 1L, 1L, 0, 100, 114, SideCode.TowardsDigitizing, Anomaly.None),
      createRoadAddressLink(8000002L, 124L, Seq(Point(10.0,10.0), Point(20.0, 20.0)), 1L, 1L, 0, 114, 128, SideCode.TowardsDigitizing, Anomaly.None),
      createRoadAddressLink(8000003L, 125L, Seq(Point(20.0,20.0), Point(30.0, 30.0)), 1L, 1L, 0, 128, 142, SideCode.TowardsDigitizing, Anomaly.None)
    )
    val targets = Seq(
      createRoadAddressLink(0L, 457L, Seq(Point(15.0,15.0), Point(30.0, 30.0)), 0, 0, 0, 0, 0, SideCode.Unknown, Anomaly.NoAddressGiven),
      createRoadAddressLink(0L, 456L, Seq(Point(0.0,0.0), Point(15.0, 15.0)), 0, 0, 0, 0, 0, SideCode.Unknown, Anomaly.NoAddressGiven)
    )
    when(mockRoadLinkService.getViiteCurrentAndHistoryRoadLinksFromVVH(any[Set[Long]])).thenReturn(
      (targets.map(roadAddressLinkToRoadLink), sources.map(roadAddressLinkToHistoryLink)))
    when(mockRoadLinkService.getViiteRoadLinksHistoryFromVVH(any[Set[Long]])).thenReturn(Seq())
    val targetLinks = targets.map(roadAddressLinkToRoadLink)
    when(mockRoadLinkService.getRoadLinksFromVVH(any[BoundingRectangle], any[BoundingRectangle])).thenReturn(targetLinks)
    val result = runWithRollback {
      RoadAddressDAO.create(sources.map(roadAddressLinkToRoadAddress(true)))
      roadAddressService.transferRoadAddress(sources, targets, User(0L, "foo", Configuration()))
    }
    sanityCheck(result)
    val linkResult = result.map(ra => RoadAddressLinkBuilder.build(targetLinks.find(_.linkId == ra.linkId).get, ra))
    val link456 = linkResult.find(_.linkId == 456L)
    val link457 = linkResult.find(_.linkId == 457L)
    link456.nonEmpty should be(true)
    link457.nonEmpty should be(true)
    link456.get.startAddressM should be(100)
    link457.get.startAddressM should be(121)
    link456.get.endAddressM should be(121)
    link457.get.endAddressM should be(142)
    result.forall(l => l.startCalibrationPoint.isEmpty && l.endCalibrationPoint.isEmpty) should be(true)
  }

  test("Defloating road links from three links to two links with against digitizing direction") {
    val sources = Seq(
      createRoadAddressLink(800001L, 123L, Seq(Point(0.0,0.0), Point(10.0, 10.0)), 1L, 1L, 0, 128, 142, SideCode.AgainstDigitizing, Anomaly.None),
      createRoadAddressLink(800002L, 124L, Seq(Point(10.0,10.0), Point(20.0, 20.0)), 1L, 1L, 0, 114, 128, SideCode.AgainstDigitizing, Anomaly.None),
      createRoadAddressLink(800003L, 125L, Seq(Point(20.0,20.0), Point(30.0, 30.0)), 1L, 1L, 0, 100, 114, SideCode.AgainstDigitizing, Anomaly.None)
    )
    val targets = Seq(
      createRoadAddressLink(0L, 457L, Seq(Point(15.0,15.0), Point(30.0, 30.0)), 0, 0, 0, 0, 0, SideCode.Unknown, Anomaly.NoAddressGiven),
      createRoadAddressLink(0L, 456L, Seq(Point(0.0,0.0), Point(15.0, 15.0)), 0, 0, 0, 0, 0, SideCode.Unknown, Anomaly.NoAddressGiven)
    )
    when(mockRoadLinkService.getViiteCurrentAndHistoryRoadLinksFromVVH(any[Set[Long]])).thenReturn(
      (targets.map(roadAddressLinkToRoadLink), sources.map(roadAddressLinkToHistoryLink)))
    when(mockRoadLinkService.getViiteRoadLinksHistoryFromVVH(any[Set[Long]])).thenReturn(Seq())
    when(mockRoadLinkService.getRoadLinksFromVVH(any[BoundingRectangle], any[BoundingRectangle])).thenReturn(Seq())
    val result = runWithRollback {
      RoadAddressDAO.create(sources.map(roadAddressLinkToRoadAddress(true)))
      roadAddressService.transferRoadAddress(sources, targets, User(0L, "foo", Configuration()))
    }
    sanityCheck(result)
    val link456 = result.find(_.linkId == 456L)
    val link457 = result.find(_.linkId == 457L)
    link456.nonEmpty should be (true)
    link457.nonEmpty should be (true)
    link456.get.startAddrMValue should be (121)
    link457.get.startAddrMValue should be (100)
    link456.get.endAddrMValue should be (142)
    link457.get.endAddrMValue should be (121)
    result.forall(l => l.startCalibrationPoint.isEmpty && l.endCalibrationPoint.isEmpty) should be (true)
    result.forall(l => l.sideCode == SideCode.AgainstDigitizing) should be (true)
  }

  test("Defloating road links from three links to two links with one calibration point in beginning") {
    val sources = Seq(
      createRoadAddressLink(800001L, 123L, Seq(Point(0.0,0.0), Point(10.0, 10.0)), 1L, 1L, 0, 0, 14, SideCode.TowardsDigitizing, Anomaly.None, startCalibrationPoint = true),
      createRoadAddressLink(800003L, 125L, Seq(Point(20.0,20.0), Point(30.0, 30.0)), 1L, 1L, 0, 28, 42, SideCode.TowardsDigitizing, Anomaly.None),
      createRoadAddressLink(800002L, 124L, Seq(Point(10.0,10.0), Point(20.0, 20.0)), 1L, 1L, 0, 14, 28, SideCode.TowardsDigitizing, Anomaly.None)
    )
    val targets = Seq(
      createRoadAddressLink(0L, 456L, Seq(Point(0.0,0.0), Point(15.0, 15.0)), 0, 0, 0, 0, 0, SideCode.Unknown, Anomaly.NoAddressGiven),
      createRoadAddressLink(0L, 457L, Seq(Point(15.0,15.0), Point(30.0, 30.0)), 0, 0, 0, 0, 0, SideCode.Unknown, Anomaly.NoAddressGiven)
    )
    when(mockRoadLinkService.getViiteCurrentAndHistoryRoadLinksFromVVH(any[Set[Long]])).thenReturn(
      (targets.map(roadAddressLinkToRoadLink), sources.map(roadAddressLinkToHistoryLink)))
    when(mockRoadLinkService.getViiteRoadLinksHistoryFromVVH(any[Set[Long]])).thenReturn(Seq())
    when(mockRoadLinkService.getRoadLinksFromVVH(any[BoundingRectangle], any[BoundingRectangle])).thenReturn(Seq())
    val result = runWithRollback {
      RoadAddressDAO.create(sources.map(roadAddressLinkToRoadAddress(true)))
      roadAddressService.transferRoadAddress(sources, targets, User(0L, "foo", Configuration()))
    }
    sanityCheck(result)
    val link456 = result.find(_.linkId == 456L)
    val link457 = result.find(_.linkId == 457L)
    link456.nonEmpty should be (true)
    link457.nonEmpty should be (true)
    link456.get.startAddrMValue should be (0)
    link457.get.startAddrMValue should be (21)
    link456.get.endAddrMValue should be (21)
    link457.get.endAddrMValue should be (42)
    result.forall(l => l.endCalibrationPoint.isEmpty) should be (true)
    link456.get.startCalibrationPoint.nonEmpty should be (true)
    link457.get.startCalibrationPoint.nonEmpty should be (false)
  }

  test("Defloating road links from three links to two links with one calibration point in the end") {
    val sources = Seq(
      createRoadAddressLink(800001L, 123L, Seq(Point(0.0,0.0), Point(10.0, 10.0)), 1L, 1L, 0, 0, 14, SideCode.TowardsDigitizing, Anomaly.None),
      createRoadAddressLink(800003L, 125L, Seq(Point(20.0,20.0), Point(30.0, 30.0)), 1L, 1L, 0, 28, 42, SideCode.TowardsDigitizing, Anomaly.None, endCalibrationPoint = true),
      createRoadAddressLink(800002L, 124L, Seq(Point(10.0,10.0), Point(20.0, 20.0)), 1L, 1L, 0, 14, 28, SideCode.TowardsDigitizing, Anomaly.None)
    )
    val targets = Seq(
      createRoadAddressLink(0L, 456L, Seq(Point(0.0,0.0), Point(15.0, 15.0)), 0, 0, 0, 0, 0, SideCode.Unknown, Anomaly.NoAddressGiven),
      createRoadAddressLink(0L, 457L, Seq(Point(15.0,15.0), Point(30.0, 30.0)), 0, 0, 0, 0, 0, SideCode.Unknown, Anomaly.NoAddressGiven)
    )
    when(mockRoadLinkService.getViiteCurrentAndHistoryRoadLinksFromVVH(any[Set[Long]])).thenReturn(
      (targets.map(roadAddressLinkToRoadLink), sources.map(roadAddressLinkToHistoryLink)))
    when(mockRoadLinkService.getViiteRoadLinksHistoryFromVVH(any[Set[Long]])).thenReturn(Seq())
    when(mockRoadLinkService.getRoadLinksFromVVH(any[BoundingRectangle], any[BoundingRectangle])).thenReturn(Seq())
    val result = runWithRollback {
      RoadAddressDAO.create(sources.map(roadAddressLinkToRoadAddress(true)))
      roadAddressService.transferRoadAddress(sources, targets, User(0L, "foo", Configuration()))
    }
    sanityCheck(result)
    val link456 = result.find(_.linkId == 456L)
    val link457 = result.find(_.linkId == 457L)
    link456.nonEmpty should be (true)
    link457.nonEmpty should be (true)
    link456.get.startAddrMValue should be (0)
    link457.get.startAddrMValue should be (21)
    link456.get.endAddrMValue should be (21)
    link457.get.endAddrMValue should be (42)
    result.forall(l => l.startCalibrationPoint.isEmpty) should be (true)
    link456.get.endCalibrationPoint.isEmpty should be (true)
    link457.get.endCalibrationPoint.isEmpty should be (false)
  }

  test("Defloating road links from three links to two links with one calibration point in between") {
    val sources = Seq(
      createRoadAddressLink(800001L, 123L, Seq(Point(0.0,0.0), Point(10.0, 10.0)), 1L, 1L, 0, 100, 114, SideCode.TowardsDigitizing, Anomaly.None, endCalibrationPoint = true),
      createRoadAddressLink(800003L, 125L, Seq(Point(20.0,20.0), Point(30.0, 30.0)), 1L, 1L, 0, 128, 142, SideCode.TowardsDigitizing, Anomaly.None),
      createRoadAddressLink(800002L, 124L, Seq(Point(10.0,10.0), Point(20.0, 20.0)), 1L, 1L, 0, 114, 128, SideCode.TowardsDigitizing, Anomaly.None, startCalibrationPoint = true)
    )
    val targets = Seq(
      createRoadAddressLink(0L, 456L, Seq(Point(0.0,0.0), Point(10.0, 10.0)), 0, 0, 0, 0, 0, SideCode.Unknown, Anomaly.NoAddressGiven),
      createRoadAddressLink(0L, 457L, Seq(Point(10.0,10.0), Point(30.0, 30.0)), 0, 0, 0, 0, 0, SideCode.Unknown, Anomaly.NoAddressGiven)
    )
    when(mockRoadLinkService.getViiteCurrentAndHistoryRoadLinksFromVVH(any[Set[Long]])).thenReturn(
      (targets.map(roadAddressLinkToRoadLink), sources.map(roadAddressLinkToHistoryLink)))
    when(mockRoadLinkService.getViiteRoadLinksHistoryFromVVH(any[Set[Long]])).thenReturn(Seq())
    when(mockRoadLinkService.getRoadLinksFromVVH(any[BoundingRectangle], any[BoundingRectangle])).thenReturn(Seq())
    the [IllegalArgumentException] thrownBy {
      runWithRollback {
        RoadAddressDAO.create(sources.map(roadAddressLinkToRoadAddress(true)))
        roadAddressService.transferRoadAddress(sources, targets, User(0L, "foo", Configuration()))
      }
    } should have message "Start calibration point not in the first link of source"
  }

  test("Zigzag geometry defloating") {

    /*

   (10,10) x                     x (30,10)           ,                     x (30,10)
          / \                   /                   / \                   /
         /   \                 /                   /   \                 /
        /     \               /                   /     \               /
       /       \             /                   /       \             /
      x (5,5)   \           /                   x (5,5)   \           /
                 \         /                               \         /
                  \       /                                 \       /
                   \     /                                   \     /
                    \   /                                     \   /
                     \ /                                (19,1) x /
                      x (20,0)                                  v

     */

    val sources = Seq(
      createRoadAddressLink(800001L, 123L, Seq(Point(5.0,5.0), Point(10.0, 10.0)), 1L, 1L, 0, 100, 107, SideCode.TowardsDigitizing, Anomaly.None),
      createRoadAddressLink(800003L, 125L, Seq(Point(20.0,0.0), Point(30.0, 10.0)), 1L, 1L, 0, 121, 135, SideCode.TowardsDigitizing, Anomaly.None),
      createRoadAddressLink(800002L, 124L, Seq(Point(20.0, 0.0), Point(10.0,10.0)), 1L, 1L, 0, 107, 121, SideCode.AgainstDigitizing, Anomaly.None)
    )
    val targets = Seq(
      createRoadAddressLink(0L, 456L, Seq(Point(19.0, 1.0), Point(10.0, 10.0), Point(5.0,5.0)), 0, 0, 0, 0, 0, SideCode.Unknown, Anomaly.NoAddressGiven),
      createRoadAddressLink(0L, 457L, Seq(Point(19.0,1.0), Point(20.0, 0.0), Point(30.0, 10.0)), 0, 0, 0, 0, 0, SideCode.Unknown, Anomaly.NoAddressGiven)
    )
    when(mockRoadLinkService.getViiteCurrentAndHistoryRoadLinksFromVVH(any[Set[Long]])).thenReturn(
      (targets.map(roadAddressLinkToRoadLink), sources.map(roadAddressLinkToHistoryLink)))
    when(mockRoadLinkService.getViiteRoadLinksHistoryFromVVH(any[Set[Long]])).thenReturn(Seq())
    when(mockRoadLinkService.getRoadLinksFromVVH(any[BoundingRectangle], any[BoundingRectangle])).thenReturn(Seq())
    val result = runWithRollback {
      RoadAddressDAO.create(sources.map(roadAddressLinkToRoadAddress(true)))
      roadAddressService.transferRoadAddress(sources, targets, User(0L, "foo", Configuration()))
    }
    sanityCheck(result)
    val link456 = result.find(_.linkId == 456L)
    val link457 = result.find(_.linkId == 457L)
    link456.nonEmpty should be (true)
    link457.nonEmpty should be (true)
    link456.get.sideCode should be (SideCode.AgainstDigitizing)
    link457.get.sideCode should be (SideCode.TowardsDigitizing)
    link456.get.startAddrMValue should be (100)
    link457.get.startAddrMValue should be (120)
    link456.get.endAddrMValue should be (120)
    link457.get.endAddrMValue should be (135)
    link456.get.startCalibrationPoint.nonEmpty should be (false)
    link457.get.startCalibrationPoint.nonEmpty should be (false)
    link457.get.endCalibrationPoint.nonEmpty should be (false)
    link456.get.endCalibrationPoint.nonEmpty should be (false)
  }

  test("test mapping 6760") {
    val properties: Properties = {
      val props = new Properties()
      props.load(getClass.getResourceAsStream("/digiroad2.properties"))
      props
    }
    val VVHClient = new VVHClient(properties.getProperty("digiroad2.VVHRestApiEndPoint"))
    val myService = new RoadAddressService(new RoadLinkService(VVHClient, mockEventBus, new DummySerializer()),mockEventBus) {
      override def withDynTransaction[T](f: => T): T = f
      override def withDynSession[T](f: => T): T = f
    }
    val targetIds = Seq("500055834","500055835","500055830","500055829")
    val roadAddresses = runWithRollback {
      targetIds.map(_.toLong).foreach(id => RoadAddressDAO.createMissingRoadAddress(id, 0L, 0L, 1))
      myService.getRoadAddressesAfterCalculation(Seq("3611217","3611218"), targetIds, User(0L, "foo", Configuration()))
    }
    roadAddresses.size should be >0
  }

  private def createRoadAddressLink(id: Long, linkId: Long, geom: Seq[Point], roadNumber: Long, roadPartNumber: Long, trackCode: Long,
                                    startAddressM: Long, endAddressM: Long, sideCode: SideCode, anomaly: Anomaly, startCalibrationPoint: Boolean = false,
                                    endCalibrationPoint: Boolean = false) = {
    val length = GeometryUtils.geometryLength(geom)
    RoadAddressLink(id, linkId, geom, length, State, LinkType.apply(1), NormalRoadLinkType,
      ConstructionType.InUse, NormalLinkInterface, RoadType.PublicRoad, None, None, Map(), roadNumber, roadPartNumber,
      trackCode, 1, 5, startAddressM, endAddressM, "2016-01-01", "", 0.0, GeometryUtils.geometryLength(geom), sideCode,
      if (startCalibrationPoint) { Option(CalibrationPoint(linkId, if (sideCode == SideCode.TowardsDigitizing) 0.0 else length, startAddressM))} else None,
      if (endCalibrationPoint) { Option(CalibrationPoint(linkId, if (sideCode == SideCode.AgainstDigitizing) 0.0 else length, endAddressM))} else None,
      anomaly, 0)

  }

  private def sanityCheck(result: Seq[RoadAddress]) = {
    result.size should be > 0
    result.forall(l =>
      l.startCalibrationPoint.isEmpty || l.startCalibrationPoint.get.addressMValue == l.startAddrMValue) should be (true)
    result.forall(l =>
      l.endCalibrationPoint.isEmpty || l.endCalibrationPoint.get.addressMValue == l.endAddrMValue) should be (true)
    result.forall(l =>
      Set[SideCode](SideCode.AgainstDigitizing, SideCode.TowardsDigitizing).contains(l.sideCode)
    )
    result.forall(l =>
      l.startAddrMValue < l.endAddrMValue
    )

  }


  test("verify the correct annexation of changeInfos to roadAddresses"){
    val linkId1 = 12345L
    val linkId2 = 67890L
    val linkId3 = 98765L
    val defaultVVTimestamp = 1459452603000L
    val roadAddress = Seq(
      RoadAddress(1, 1, 1, Track.Combined, Discontinuous, 0L, 10L, Some(DateTime.parse("1901-01-01")), None, Option("tester"),0, linkId1, 0.0, 9.8,
        SideCode.TowardsDigitizing, 0, (None, None), true, Seq(Point(0.0, 0.0), Point(0.0, 9.8))),
      RoadAddress(2, 1, 1, Track.Combined, Discontinuous, 10L, 20L, Some(DateTime.parse("1901-01-01")), None, Option("tester"),0, linkId2, 0.0, 10.4,
        SideCode.TowardsDigitizing, defaultVVTimestamp, (None, None), true, Seq(Point(0.0, 9.8), Point(0.0, 20.2))),
      RoadAddress(2, 1, 1, Track.Combined, Discontinuous, 10L, 20L, Some(DateTime.parse("1901-01-01")), None, Option("tester"),0, linkId3, 0.0, 10.4,
        SideCode.TowardsDigitizing, defaultVVTimestamp, (None, None), true, Seq(Point(0.0, 9.8), Point(0.0, 20.2)))
    )

    val changeInfo =
      Seq(
        ChangeInfo(Option(602156), Option(linkId2), 6798918, 1, Option(10.52131863), Option(106.62158114), Option(0.0), Option(96.166451179999996), defaultVVTimestamp+5L) ,
        ChangeInfo(Option(linkId2), Option(602156), 6798918, 2, Option(10.52131863), Option(106.62158114), Option(0.0), Option(96.166451179999996), defaultVVTimestamp+5L) ,
        ChangeInfo(Option(linkId2), Option(602156), 6798918, 3, Option(10.52131863), Option(106.62158114), Option(0.0), Option(96.166451179999996), defaultVVTimestamp) ,
        ChangeInfo(Option(602156), Option(602156), 6798918, 4, Option(10.52131863), Option(106.62158114), Option(0.0), Option(96.166451179999996), defaultVVTimestamp) ,
        ChangeInfo(Option(602156), Option(linkId1), 6798918, 5, Option(10.52131863), Option(106.62158114), Option(0.0), Option(96.166451179999996), defaultVVTimestamp+5L)
      )
    val matchedResults = roadAddressService.matchChangesWithRoadAddresses(roadAddress, changeInfo)

    matchedResults.size should be(3)
    val firstMatch = matchedResults.find(_._1.linkId == linkId1).get
    val secondMatch = matchedResults.find(_._1.linkId == linkId2).get
    val thirdMatch = matchedResults.find(_._1.linkId == linkId3).get

    firstMatch._2.size should be (1)
    firstMatch._2.head.changeType should be (5)
    secondMatch._2.size should be (2)
    secondMatch._2(0).changeType should be (1)
    secondMatch._2(1).changeType should be (2)
    thirdMatch._2.size should be (0)

    matchedResults.flatMap(_._2).find(_.changeType == 3).isEmpty should be (true)

  }
}
