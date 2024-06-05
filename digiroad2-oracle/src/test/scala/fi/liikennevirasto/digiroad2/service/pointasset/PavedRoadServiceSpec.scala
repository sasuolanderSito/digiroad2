package fi.liikennevirasto.digiroad2.service.pointasset

import fi.liikennevirasto.digiroad2.asset.SideCode.BothDirections
import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.client.{FeatureClass, RoadLinkFetched}
import fi.liikennevirasto.digiroad2.dao.{DynamicLinearAssetDao, Sequences}
import fi.liikennevirasto.digiroad2.dao.linearasset.PostGISLinearAssetDao
import fi.liikennevirasto.digiroad2.linearasset.LinearAssetFiller.{ChangeSet, MValueAdjustment, SideCodeAdjustment}
import fi.liikennevirasto.digiroad2.linearasset._
import fi.liikennevirasto.digiroad2.postgis.PostGISDatabase
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.util.{LinearAssetUtils, LinkIdGenerator, PolygonTools, TestTransactions}
import fi.liikennevirasto.digiroad2.{DigiroadEventBus, DummyEventBus, GeometryUtils, Point}
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers._
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{FunSuite, Matchers}
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation

class PavedRoadServiceSpec extends FunSuite with Matchers {

  val mockRoadLinkService = MockitoSugar.mock[RoadLinkService]
  val mockPolygonTools = MockitoSugar.mock[PolygonTools]
  val mockLinearAssetDao = MockitoSugar.mock[PostGISLinearAssetDao]
  val mockDynamicLinearAssetDao = MockitoSugar.mock[DynamicLinearAssetDao]
  val mockEventBus = MockitoSugar.mock[DigiroadEventBus]
  val linearAssetDao = new PostGISLinearAssetDao()
  val dynamicLinearAssetDao = new DynamicLinearAssetDao()

  val multiTypePropSeq = DynamicAssetValue(Seq(DynamicProperty("suggest_box","checkbox",required = false,List()),DynamicProperty("paallysteluokka", "single_choice", required = false, Seq(DynamicPropertyValue("50")))))
  val multiTypePropSeq1 = DynamicAssetValue(Seq(DynamicProperty("paallysteluokka", "single_choice", required = false, Seq(DynamicPropertyValue("40")))))
  val multiTypePropSeq2 = DynamicAssetValue(Seq(DynamicProperty("paallysteluokka", "single_choice", required = false, Seq(DynamicPropertyValue("10")))))
  val multiTypePropSeq3 = DynamicAssetValue(Seq(DynamicProperty("paallysteluokka", "single_choice", required = false, Seq(DynamicPropertyValue("99")))))
  val multiTypePropSeq4 = DynamicAssetValue(Seq(DynamicProperty("paallysteluokka", "single_choice", required = false, Seq(DynamicPropertyValue("0")))))

  val propertyData = DynamicValue(multiTypePropSeq)
  val propertyData1 = DynamicValue(multiTypePropSeq1)
  val propertyData2 = DynamicValue(multiTypePropSeq2)
  val propertyData3 = DynamicValue(multiTypePropSeq3)
  val propertyData4 = DynamicValue(multiTypePropSeq4)

  val linkId1: String = LinkIdGenerator.generateRandom()
  val linkId2: String = LinkIdGenerator.generateRandom()
  val linkId3: String = LinkIdGenerator.generateRandom()
  val linkId4: String = LinkIdGenerator.generateRandom()

  val randomLinkId1: String = LinkIdGenerator.generateRandom()
  val randomLinkId2: String = LinkIdGenerator.generateRandom()
  val randomLinkId3: String = LinkIdGenerator.generateRandom()
  val randomLinkId4: String = LinkIdGenerator.generateRandom()

  when(mockRoadLinkService.fetchByLinkId(linkId2)).thenReturn(Some(RoadLinkFetched(linkId2, 235, Seq(Point(0, 0), Point(10, 0)), Municipality, TrafficDirection.UnknownDirection, FeatureClass.AllOthers)))
  when(mockRoadLinkService.fetchRoadlinksByIds(any[Set[String]])).thenReturn(Seq(RoadLinkFetched(linkId2, 235, Seq(Point(0, 0), Point(10, 0)), Municipality, TrafficDirection.UnknownDirection, FeatureClass.AllOthers)))
  when(mockRoadLinkService.fetchNormalOrComplimentaryRoadLinkByLinkId(any[String])).thenReturn(Some(RoadLinkFetched(linkId2, 235, Seq(Point(0, 0), Point(10, 0)), Municipality, TrafficDirection.UnknownDirection, FeatureClass.AllOthers)))

  val roadLinkWithLinkSource = Seq(RoadLink(linkId1, Seq(Point(0.0, 0.0), Point(10.0, 0.0)), 10.0, Municipality,
    1, TrafficDirection.BothDirections, Motorway, None, None, Map("MUNICIPALITYCODE" -> BigInt(235), "SURFACETYPE" -> BigInt(2)), ConstructionType.InUse, LinkGeomSource.NormalLinkInterface)
    ,RoadLink(linkId2, Seq(Point(0.0, 0.0), Point(10.0, 0.0)), 10.0, Municipality,
      1, TrafficDirection.BothDirections, Motorway, None, None, Map("MUNICIPALITYCODE" -> BigInt(235), "SURFACETYPE" -> BigInt(2)), ConstructionType.InUse, LinkGeomSource.NormalLinkInterface)
    ,RoadLink(linkId3, Seq(Point(0.0, 0.0), Point(10.0, 0.0)), 10.0, Municipality,
      1, TrafficDirection.BothDirections, Motorway, None, None, Map("MUNICIPALITYCODE" -> BigInt(235), "SURFACETYPE" -> BigInt(2)), ConstructionType.InUse, LinkGeomSource.NormalLinkInterface)
    ,RoadLink(linkId4, Seq(Point(0.0, 0.0), Point(10.0, 0.0)), 10.0, Municipality,
      1, TrafficDirection.BothDirections, Motorway, None, None, Map("MUNICIPALITYCODE" -> BigInt(235), "SURFACETYPE" -> BigInt(2)), ConstructionType.InUse, LinkGeomSource.NormalLinkInterface))

  when(mockRoadLinkService.getRoadLinks(any[BoundingRectangle], any[Set[Int]],any[Boolean])).thenReturn((roadLinkWithLinkSource, Nil))
  when(mockRoadLinkService.getRoadLinksAndComplementariesByLinkIds(any[Set[String]], any[Boolean])).thenReturn(roadLinkWithLinkSource)
  when(mockRoadLinkService.getRoadLinkAndComplementaryByLinkId(any[String], any[Boolean])).thenReturn(roadLinkWithLinkSource.headOption)

  when(mockDynamicLinearAssetDao.fetchDynamicLinearAssetsByLinkIds(30, Seq(linkId1)))
    .thenReturn(Seq(PersistedLinearAsset(1, linkId1, 1, Some(propertyData), 0.4, 9.6, None, None, None, None, false, 30, 0, None, LinkGeomSource.NormalLinkInterface, None, None, None)))

  object ServiceWithDao extends PavedRoadService(mockRoadLinkService, mockEventBus) {
    override def withDynTransaction[T](f: => T): T = f
    override def roadLinkService: RoadLinkService = mockRoadLinkService
    override def dao: PostGISLinearAssetDao = linearAssetDao
    override def eventBus: DigiroadEventBus = mockEventBus
    override def polygonTools: PolygonTools = mockPolygonTools

    override def getUncheckedLinearAssets(areas: Option[Set[Int]]) = throw new UnsupportedOperationException("Not supported method")
  }

  def runWithRollback(test: => Unit): Unit = assetLock.synchronized {
    TestTransactions.runWithRollback()(test)
  }

  val assetLock = "Used to prevent deadlocks"

  private def createService() = {
    val mockRoadLinkService = MockitoSugar.mock[RoadLinkService]
    val service = new PavedRoadService(mockRoadLinkService, new DummyEventBus) {
      override def withDynTransaction[T](f: => T): T = f
    }
    service
  }

  private def createRoadLinks(municipalityCode: Int) = {
    val newLinkId1 = randomLinkId1
    val newLinkId2 = randomLinkId2
    val newLinkId3 = randomLinkId3
    val newLinkId4 = randomLinkId4
    val administrativeClass = Municipality
    val trafficDirection = TrafficDirection.BothDirections
    val functionalClass = 1
    val linkType = Freeway
    val attributes1 = Map("MUNICIPALITYCODE" -> BigInt(municipalityCode), "SURFACETYPE" -> BigInt(0))
    val attributes2 = Map("MUNICIPALITYCODE" -> BigInt(municipalityCode), "SURFACETYPE" -> BigInt(1))
    val attributes3 = Map("MUNICIPALITYCODE" -> BigInt(municipalityCode), "SURFACETYPE" -> BigInt(2))

    val geometry = List(Point(0.0, 0.0), Point(20.0, 0.0))
    val newRoadLink1 = RoadLink(newLinkId1, geometry, GeometryUtils.geometryLength(geometry), administrativeClass,
      functionalClass, trafficDirection, linkType, None, None, attributes1)
    val newRoadLink2 = newRoadLink1.copy(linkId=newLinkId2, attributes = attributes2)
    val newRoadLink3 = newRoadLink1.copy(linkId=newLinkId3, attributes = attributes3)
    val newRoadLink4 = newRoadLink1.copy(linkId=newLinkId4, attributes = attributes3)
    List(newRoadLink1, newRoadLink2, newRoadLink3, newRoadLink4)
  }

  test("Should not create new paved road assets and return the existing paved road assets when VVH doesn't have change information") {
    //TODO DELETE

    val mockRoadLinkService = MockitoSugar.mock[RoadLinkService]
  
    val service = new PavedRoadService(mockRoadLinkService, new DummyEventBus) {
      override def withDynTransaction[T](f: => T): T = f
      override def withDynSession[T](f: => T): T = f
    }

    val newLinkId = LinkIdGenerator.generateRandom()
    val municipalityCode = 235
    val administrativeClass = Municipality
    val trafficDirection = TrafficDirection.BothDirections
    val functionalClass = 1
    val linkType = Freeway
    val boundingBox = BoundingRectangle(Point(123, 345), Point(567, 678))
    val attributes = Map("MUNICIPALITYCODE" -> BigInt(municipalityCode), "SURFACETYPE" -> BigInt(2))

    val newRoadLink = RoadLink(newLinkId, List(Point(0.0, 0.0), Point(20.0, 0.0)), 20.0, administrativeClass, functionalClass, trafficDirection, linkType, None, None, attributes)
    when(mockRoadLinkService.getRoadLinksAndComplementariesByLinkIds(any[Set[String]], any[Boolean])).thenReturn(Seq(newRoadLink))
    runWithRollback {

      val newAssetId = service.create(Seq(NewLinearAsset(newLinkId, 0, 20, propertyData, 1, 0, None)), PavedRoad.typeId, "testuser")
      val newAsset = service.getPersistedAssetsByIds(PavedRoad.typeId, newAssetId.toSet)

      when(mockRoadLinkService.getRoadLinksByBoundsAndMunicipalities(any[BoundingRectangle], any[Set[Int]], any[Boolean])).thenReturn(List(newRoadLink))
      when(mockDynamicLinearAssetDao.fetchDynamicLinearAssetsByLinkIds(any[Int], any[Seq[String]], any[Boolean], any[Boolean])).thenReturn(newAsset)

      val existingAssets = service.getByBoundingBox(PavedRoad.typeId, boundingBox).toList.flatten

      val existingAssetData = existingAssets.filter(p => (p.linkId == newLinkId && p.value.isDefined)).head

      existingAssets.length should be (1)
      existingAssetData.typeId should be (PavedRoad.typeId)
      existingAssetData.value should be (Some(propertyData))
      existingAssetData.id should be (newAsset.head.id)
    }
  }

  test("create pavedRoad and check if informationSource is Municipality Maintainer ") {
    val service = createService()

    val toInsert = Seq(NewLinearAsset(LinkIdGenerator.generateRandom(), 0, 20, propertyData, 1, 0, None),
      NewLinearAsset(LinkIdGenerator.generateRandom(), 0, 20, propertyData, 1, 0, None))
    runWithRollback {
      val assetsIds = ServiceWithDao.create(toInsert, PavedRoad.typeId, "test")
      val assetsCreated = service.getPersistedAssetsByIds(PavedRoad.typeId, assetsIds.toSet)

      assetsCreated.length should be(2)
      assetsCreated.foreach { asset =>
        asset.informationSource should be(Some(MunicipalityMaintenainer))
      }
    }
  }

  test("update pavedRoad and check if informationSource is Municipality Maintainer "){

    val service = createService()
    val toInsert = Seq(NewLinearAsset(LinkIdGenerator.generateRandom(), 0, 20, propertyData1, BothDirections.value, 0, None), NewLinearAsset(LinkIdGenerator.generateRandom(), 0, 20, propertyData2, BothDirections.value, 0, None))
    runWithRollback {
      val assetsIds = ServiceWithDao.create(toInsert, PavedRoad.typeId, "test")
      val updated = ServiceWithDao.update(assetsIds, propertyData, "userTest")

      val assetsUpdated = ServiceWithDao.getPersistedAssetsByIds(PavedRoad.typeId, updated.toSet)

      assetsUpdated.length should be (2)
      assetsUpdated.foreach{asset =>
        asset.informationSource should be (Some(MunicipalityMaintenainer))
        asset.modifiedBy should be (Some("userTest"))
        asset.value should be (Some(propertyData))
      }
    }
  }
}
