package fi.liikennevirasto.digiroad2

import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.client.vvh.FeatureClass.AllOthers
import fi.liikennevirasto.digiroad2.client.vvh.{RoadLinkClient, RoadLinkFetched}
import fi.liikennevirasto.digiroad2.dao.AwsDao
import fi.liikennevirasto.digiroad2.linearasset.{RoadLink, _}
import fi.liikennevirasto.digiroad2.postgis.PostGISDatabase
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.service.linearasset._
import fi.liikennevirasto.digiroad2.service.pointasset.{HeightLimit => _, WidthLimit => _, _}
import fi.liikennevirasto.digiroad2.util.{LinkIdGenerator, LinearAssetUtils}
import javax.sql.DataSource
import org.json4s.{DefaultFormats, Formats}
import org.mockito.ArgumentMatchers._
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfter, FunSuite, Matchers}
import slick.driver.JdbcDriver.backend.Database
import slick.driver.JdbcDriver.backend.Database.dynamicSession

object sTestTransactions {
  def runWithRollback(ds: DataSource = PostGISDatabase.ds)(f: => Unit): Unit = {
    Database.forDataSource(ds).withDynTransaction {
      f
      dynamicSession.rollback()
    }
  }
  def withDynTransaction[T](ds: DataSource = PostGISDatabase.ds)(f: => T): T = {
    Database.forDataSource(ds).withDynTransaction {
      f
    }
  }
  def withDynSession[T](ds: DataSource = PostGISDatabase.ds)(f: => T): T = {
    Database.forDataSource(ds).withDynSession {
      f
    }
  }
}

class MunicipalityApiSpec extends FunSuite with Matchers with BeforeAndAfter {

  val mockRoadLinkClient = MockitoSugar.mock[RoadLinkClient]
  val mockRoadLinkService = MockitoSugar.mock[RoadLinkService]
  val obstacleService = new ObstacleService(mockRoadLinkService)
  val speedLimitService = new SpeedLimitService(new DummyEventBus, mockRoadLinkClient, mockRoadLinkService)
  val pavedRoadService = new PavedRoadService(mockRoadLinkService, new DummyEventBus)

  protected implicit val jsonFormats: Formats = DefaultFormats

  val (linkId1, linkId2, linkId3, linkId4, linkId5) = (LinkIdGenerator.generateRandom(), LinkIdGenerator.generateRandom(),
    LinkIdGenerator.generateRandom(), LinkIdGenerator.generateRandom(), LinkIdGenerator.generateRandom())

  val dataSetId = "ab70d6a9-9616-4cc4-abbe-6272c2344709"
  val roadLinksList: List[List[String]] = List(List(linkId1, linkId2, linkId3, linkId4), List(linkId5))

  val commonLinearProperties: Map[String, String] = Map("name" -> "Mannerheimintie", "pavementClass" -> "1", "speedLimit" -> "100", "sideCode" -> "1", "id" -> "100001", "functionalClass" -> "Katu", "type" -> "Roadlink")
  val commonPointProperties: Map[String, String] = Map("id" -> "100000", "type" -> "obstacle", "class" -> "1")

  val commonLinearGeometry: Geometry = Geometry("LineString", List(List(384594.081, 6674141.478, 105.55299999999988), List(384653.656, 6674029.718, 106.02099999999336), List(384731.654, 6673901.8, 106.37600000000384), List(384919.538, 6673638.735, 106.51600000000326)))
  val commonPointGeometry: Geometry = Geometry("Point", List(List(385786, 6671390, 0)))

  val commonPointFeature: Feature = Feature("Feature", commonPointGeometry, commonPointProperties)
  val commonLinearFeature: Feature = Feature("Feature", commonLinearGeometry, commonLinearProperties)

  val commonFeatureCollection: FeatureCollection = FeatureCollection("FeatureCollection", List(commonLinearFeature, commonPointFeature))

  object ServiceWithDao extends MunicipalityApi(mockRoadLinkClient, mockRoadLinkService, speedLimitService, pavedRoadService, obstacleService, new OthSwagger){
    override def awsDao: AwsDao = new AwsDao
  }
  def runWithRollback(test: => Unit): Unit = sTestTransactions.runWithRollback()(test)

  test("number of features doesn't match with the number of list of road links give") {

    val wrongRoadLinksList: List[List[String]] = List(List(linkId1, linkId2, linkId3, linkId4))
    val dataSet = Dataset(dataSetId, commonFeatureCollection, wrongRoadLinksList)

    runWithRollback {
      ServiceWithDao.validateAndInsertDataset(dataSet)
      val datasetStatus = ServiceWithDao.awsDao.getDatasetStatus(dataSetId)
      datasetStatus should be(1)
    }
  }

  test("validate if features have id key/value") {
    val linkId = LinkIdGenerator.generateRandom()
    val newRoadLinks = Seq(RoadLink(linkId, List(Point(0.0, 0.0), Point(100.0, 0.0)), 10.0, Municipality, 1, TrafficDirection.BothDirections, Freeway, None, None, Map("MUNICIPALITYCODE" -> BigInt(235))))
    when(mockRoadLinkService.getRoadsLinksFromVVH(Set(linkId), false)).thenReturn(newRoadLinks)
    val pointProperties: Map[String, String] = Map("type" -> "obstacle", "class" -> "1")
    val pointGeometry: Geometry = Geometry("Point", List(List(385786, 6671390, 0)))
    val pointFeature: Feature = Feature("Feature", pointGeometry, pointProperties)
    val featureCollection: FeatureCollection = FeatureCollection("FeatureCollection", List(pointFeature))
    val roadLinksList: List[List[String]] = List(List(linkId))

    val dataSet = Dataset(dataSetId, featureCollection, roadLinksList)

    runWithRollback {
      val numberOfFeaturesWithoutId = ServiceWithDao.validateAndInsertDataset(dataSet)
      val datasetStatus = ServiceWithDao.awsDao.getDatasetStatus(dataSetId)

      numberOfFeaturesWithoutId should be (Some(1))
      datasetStatus should be(2)
    }
  }

  test("validate if roadLink exists on VVH") {
    val linkId1 = LinkIdGenerator.generateRandom()
    val linkId2 = LinkIdGenerator.generateRandom()
    when(mockRoadLinkService.getRoadsLinksFromVVH(Set(linkId1, linkId2), false)).thenReturn(Seq())

    val roadLinksList: List[List[String]] = List(List(linkId1),List(linkId2))
    val dataSet = Dataset(dataSetId, commonFeatureCollection, roadLinksList)

    runWithRollback {
      val numberOfFeaturesWithoutId = ServiceWithDao.validateAndInsertDataset(dataSet)
      val datasetStatus = ServiceWithDao.awsDao.getDatasetStatus(dataSetId)
      val featuresStatus = ServiceWithDao.awsDao.getAllFeatureIdAndStatusByDataset(dataSetId)

      numberOfFeaturesWithoutId should be(None)
      datasetStatus should be(2)
      featuresStatus.sortBy(status => status._1) should be (List(("100000","6"), ("100001","6")))
    }
  }

  test("validate if the Geometry Type is one of the allowed") {
    val linkId = LinkIdGenerator.generateRandom()
    val newRoadLinks = Seq(RoadLink(linkId, List(Point(0.0, 0.0), Point(100.0, 0.0)), 10.0, Municipality, 1, TrafficDirection.BothDirections, Freeway, None, None, Map("MUNICIPALITYCODE" -> BigInt(235))))
    when(mockRoadLinkService.getRoadsLinksFromVVH(Set(linkId), false)).thenReturn(newRoadLinks)

    val roadLinksList: List[List[String]] = List(List(linkId))
    val pointGeometry: Geometry = Geometry("WrongGeometryType", List(List(385786, 6671390, 0)))
    val pointFeature: Feature = Feature("Feature", pointGeometry, commonPointProperties)
    val featureCollection: FeatureCollection = FeatureCollection("FeatureCollection", List(pointFeature))

    val dataSet = Dataset(dataSetId, featureCollection, roadLinksList)

    runWithRollback {
      val numberOfFeaturesWithoutId = ServiceWithDao.validateAndInsertDataset(dataSet)
      val datasetStatus = ServiceWithDao.awsDao.getDatasetStatus(dataSetId)
      val featuresStatus = ServiceWithDao.awsDao.getAllFeatureIdAndStatusByDataset(dataSetId)

      numberOfFeaturesWithoutId should be(None)
      datasetStatus should be(2)
      featuresStatus should be (List(("100000","3")))
    }
  }

  test("new obstacle with nonvalid value to be created/updated") {
    val linkId = LinkIdGenerator.generateRandom()
    val newRoadLinks = Seq(RoadLink(linkId, List(Point(0.0, 0.0), Point(100.0, 0.0)), 10.0, Municipality, 1, TrafficDirection.BothDirections, Freeway, None, None, Map("MUNICIPALITYCODE" -> BigInt(235))))
    when(mockRoadLinkService.getRoadsLinksFromVVH(Set(linkId), false)).thenReturn(newRoadLinks)

    val roadLinksList: List[List[String]] = List(List(linkId))
    val pointProperties: Map[String, String] = Map("id" -> "100000", "type" -> "obstacle", "class" -> "10")
    val pointFeature: Feature = Feature("Feature", commonPointGeometry, pointProperties)
    val featureCollection: FeatureCollection = FeatureCollection("FeatureCollection", List(pointFeature))

    val dataSet = Dataset(dataSetId, featureCollection, roadLinksList)

    runWithRollback {
      val numberOfFeaturesWithoutId = ServiceWithDao.validateAndInsertDataset(dataSet)
      val datasetStatus = ServiceWithDao.awsDao.getDatasetStatus(dataSetId)
      val featuresStatus = ServiceWithDao.awsDao.getAllFeatureIdAndStatusByDataset(dataSetId)

      numberOfFeaturesWithoutId should be(None)
      datasetStatus should be(2)
      featuresStatus should be (List(("100000","2")))
    }
  }

  test("new speedlimit with nonvalid value to be created/updated") {
    val linkId = LinkIdGenerator.generateRandom()
    val newRoadLinks = Seq(RoadLink(linkId, List(Point(0.0, 0.0), Point(100.0, 0.0)), 10.0, Municipality, 1, TrafficDirection.BothDirections, Freeway, None, None, Map("MUNICIPALITYCODE" -> BigInt(235))))
    when(mockRoadLinkService.getRoadsLinksFromVVH(Set(linkId), false)).thenReturn(newRoadLinks)

    val roadLinksList: List[List[String]] = List(List(linkId))
    val linearProperties: Map[String, String] = Map("name" -> "Mannerheimintie", "speedLimit" -> "210", "sideCode" -> "1", "id" -> "200000", "functionalClass" -> "Katu", "type" -> "Roadlink")
    val linearFeature: Feature = Feature("Feature", commonLinearGeometry, linearProperties)
    val featureCollection: FeatureCollection = FeatureCollection("FeatureCollection", List(linearFeature))

    val dataSet = Dataset(dataSetId, featureCollection, roadLinksList)

    runWithRollback {
      val numberOfFeaturesWithoutId = ServiceWithDao.validateAndInsertDataset(dataSet)
      val datasetStatus = ServiceWithDao.awsDao.getDatasetStatus(dataSetId)
      val featuresStatus = ServiceWithDao.awsDao.getAllFeatureIdAndStatusByDataset(dataSetId)

      numberOfFeaturesWithoutId should be(None)
      datasetStatus should be(2)
      featuresStatus should be (List(("200000","2")))
    }
  }

  test("new pavementClass with nonvalid value to be created/updated") {
    val linkId = LinkIdGenerator.generateRandom()
    val newRoadLinks = Seq(RoadLink(linkId, List(Point(0.0, 0.0), Point(100.0, 0.0)), 10.0, Municipality, 1, TrafficDirection.BothDirections, Freeway, None, None, Map("MUNICIPALITYCODE" -> BigInt(235))))
    when(mockRoadLinkService.getRoadsLinksFromVVH(Set(linkId), false)).thenReturn(newRoadLinks)

    val roadLinksList: List[List[String]] = List(List(linkId))
    val linearProperties: Map[String, String] = Map("name" -> "Mannerheimintie", "pavementClass" -> "100", "sideCode" -> "1", "id" -> "200000", "functionalClass" -> "Katu", "type" -> "Roadlink")
    val linearFeature: Feature = Feature("Feature", commonLinearGeometry, linearProperties)
    val featureCollection: FeatureCollection = FeatureCollection("FeatureCollection", List(linearFeature))

    val dataSet = Dataset(dataSetId, featureCollection, roadLinksList)

    runWithRollback {
      val numberOfFeaturesWithoutId = ServiceWithDao.validateAndInsertDataset(dataSet)
      val datasetStatus = ServiceWithDao.awsDao.getDatasetStatus(dataSetId)
      val featuresStatus = ServiceWithDao.awsDao.getAllFeatureIdAndStatusByDataset(dataSetId)

      numberOfFeaturesWithoutId should be(None)
      datasetStatus should be(2)
      featuresStatus should be (List(("200000","2")))
    }
  }

  test("new speedlimit with valid value to be created") {
    val linkId = LinkIdGenerator.generateRandom()
    val newRoadLink = RoadLink(linkId, List(Point(0.0, 0.0), Point(100.0, 0.0)), 100.0, Municipality, 1, TrafficDirection.BothDirections, Freeway, None, None, Map("MUNICIPALITYCODE" -> BigInt(235)))
    val newFetchedRoadLink = RoadLinkFetched(linkId, 235, List(Point(0.0, 0.0), Point(100.0, 0.0)), Municipality, TrafficDirection.BothDirections, AllOthers)
    when(mockRoadLinkService.getRoadsLinksFromVVH(Set(linkId), false)).thenReturn(Seq(newRoadLink))
    when(mockRoadLinkService.getRoadLinksAndComplementariesFromVVH(Set(linkId), false)).thenReturn(Seq(newRoadLink))
    when(mockRoadLinkService.fetchVVHRoadlinkAndComplementary(linkId)).thenReturn(Some(newFetchedRoadLink))
    
    val timeStamp = LinearAssetUtils.createTimeStamp()
    when(mockRoadLinkClient.createTimeStamp(any[Int])).thenReturn(timeStamp)

    val roadLinksList: List[List[String]] = List(List(linkId))
    val linearProperties: Map[String, String] = Map("name" -> "Mannerheimintie", "speedLimit" -> "100", "sideCode" -> "1", "id" -> "200000", "functionalClass" -> "Katu", "type" -> "Roadlink")
    val linearFeature: Feature = Feature("Feature", commonLinearGeometry, linearProperties)
    val featureCollection: FeatureCollection = FeatureCollection("FeatureCollection", List(linearFeature))

    val dataSet = Dataset(dataSetId, featureCollection, roadLinksList)

    runWithRollback {
      when(mockRoadLinkService.getRoadLinkAndComplementaryFromVVH(linkId, false)).thenReturn(Some(newRoadLink))
      when(mockRoadLinkService.fetchVVHRoadlinksAndComplementary(Set(linkId))).thenReturn(Seq(newFetchedRoadLink))
      val numberOfFeaturesWithoutId = ServiceWithDao.validateAndInsertDataset(dataSet)
      val datasetStatus = ServiceWithDao.awsDao.getDatasetStatus(dataSetId)
      val featuresStatus = ServiceWithDao.awsDao.getAllFeatureIdAndStatusByDataset(dataSetId)

      numberOfFeaturesWithoutId should be(None)
      datasetStatus should be(0)
      featuresStatus should be (List(("200000","0")))

      ServiceWithDao.updateDataset(dataSet)
      val datasetStatus2 = ServiceWithDao.awsDao.getDatasetStatus(dataSetId)
      val featuresStatus2 = ServiceWithDao.awsDao.getAllFeatureIdAndStatusByDataset(dataSetId)
      val createdSpeedLimit = speedLimitService.getExistingAssetByRoadLink(newRoadLink, false)

      datasetStatus2 should be(3)
      featuresStatus2 should be (List(("200000","1")))
      createdSpeedLimit.head.linkId should be (linkId)
      createdSpeedLimit.head.value should be(Some(SpeedLimitValue(100)))
      createdSpeedLimit.head.startMeasure should be (0.0)
      createdSpeedLimit.head.endMeasure should be (100.0)
      createdSpeedLimit.head.createdBy should be (Some("AwsUpdater"))
    }
  }

  test("new pavementClass with valid value to be created") {
    val linkId = LinkIdGenerator.generateRandom()
    val newRoadLink = RoadLink(linkId, List(Point(0.0, 0.0), Point(100.0, 0.0)), 100.0, Municipality, 1, TrafficDirection.BothDirections, Freeway, None, None, Map("MUNICIPALITYCODE" -> BigInt(235)))
    val newFetchedRoadLink = RoadLinkFetched(linkId, 235, List(Point(0.0, 0.0), Point(100.0, 0.0)), Municipality, TrafficDirection.BothDirections, AllOthers)
    when(mockRoadLinkService.getRoadsLinksFromVVH(Set(linkId), false)).thenReturn(Seq(newRoadLink))
    when(mockRoadLinkService.getRoadLinksAndComplementariesFromVVH(Set(linkId), false)).thenReturn(Seq(newRoadLink))
    when(mockRoadLinkService.fetchVVHRoadlinkAndComplementary(linkId)).thenReturn(Some(newFetchedRoadLink))
    
    val timeStamp = LinearAssetUtils.createTimeStamp()
    when(mockRoadLinkClient.createTimeStamp(any[Int])).thenReturn(timeStamp)
    when(mockRoadLinkClient.createTimeStamp(any[Int])).thenReturn(timeStamp)

    val roadLinksList: List[List[String]] = List(List(linkId))
    val linearProperties: Map[String, String] = Map("name" -> "Mannerheimintie", "pavementClass" -> "1", "sideCode" -> "1", "id" -> "200000", "functionalClass" -> "Katu", "type" -> "Roadlink")
    val linearFeature: Feature = Feature("Feature", commonLinearGeometry, linearProperties)
    val featureCollection: FeatureCollection = FeatureCollection("FeatureCollection", List(linearFeature))

    val dataSet = Dataset(dataSetId, featureCollection, roadLinksList)

    runWithRollback {
      val numberOfFeaturesWithoutId = ServiceWithDao.validateAndInsertDataset(dataSet)
      val datasetStatus = ServiceWithDao.awsDao.getDatasetStatus(dataSetId)
      val featuresStatus = ServiceWithDao.awsDao.getAllFeatureIdAndStatusByDataset(dataSetId)

      numberOfFeaturesWithoutId should be(None)
      datasetStatus should be(0)
      featuresStatus should be (List(("200000","0")))

      ServiceWithDao.updateDataset(dataSet)
      val datasetStatus2 = ServiceWithDao.awsDao.getDatasetStatus(dataSetId)
      val featuresStatus2 = ServiceWithDao.awsDao.getAllFeatureIdAndStatusByDataset(dataSetId)
      val createdPavementClass = pavedRoadService.getPersistedAssetsByLinkIds(PavedRoad.typeId, Seq(newRoadLink.linkId), false)

      datasetStatus2 should be(3)
      featuresStatus2 should be (List(("200000","1")))
      createdPavementClass.head.linkId should be (linkId)
      createdPavementClass.head.value.toString should be(
        Some(DynamicValue(DynamicAssetValue(List(
          DynamicProperty("paallysteluokka", "single_choice", false, List(DynamicPropertyValue(1))),
          DynamicProperty("suggest_box", "checkbox", false, List())
        )))).toString
      )
      createdPavementClass.head.startMeasure should be (0.0)
      createdPavementClass.head.endMeasure should be (100.0)
      createdPavementClass.head.createdBy should be (Some("AwsUpdater"))
    }
  }

  test("new obstacle with valid value to be created") {
    val linkId = LinkIdGenerator.generateRandom()
    val newRoadLink = RoadLink(linkId, List(Point(0.0, 0.0), Point(100.0, 0.0)), 100.0, Municipality, 1, TrafficDirection.BothDirections, Freeway, None, None, Map("MUNICIPALITYCODE" -> BigInt(235)))
    val newFetchedRoadLink = RoadLinkFetched(linkId, 235, List(Point(0.0, 0.0), Point(100.0, 0.0)), Municipality, TrafficDirection.BothDirections, AllOthers)
    when(mockRoadLinkService.getRoadsLinksFromVVH(Set(linkId), false)).thenReturn(Seq(newRoadLink))
    when(mockRoadLinkService.getRoadLinksAndComplementariesFromVVH(Set(linkId), false)).thenReturn(Seq(newRoadLink))
    when(mockRoadLinkService.fetchVVHRoadlinkAndComplementary(linkId)).thenReturn(Some(newFetchedRoadLink))
    when(mockRoadLinkService.getRoadLinkFromVVH(linkId, false)).thenReturn(Some(newRoadLink))

    when(mockRoadLinkService.getRoadLinksWithComplementaryAndChangesFromVVH(235)).thenReturn((Seq(newRoadLink), Seq()))
    
    val timeStamp = LinearAssetUtils.createTimeStamp()
    when(mockRoadLinkClient.createTimeStamp(any[Int])).thenReturn(timeStamp)
    when(mockRoadLinkClient.createTimeStamp(any[Int])).thenReturn(timeStamp)

    val roadLinksList: List[List[String]] = List(List(linkId))
    val featureCollection: FeatureCollection = FeatureCollection("FeatureCollection", List(commonPointFeature))

    val dataSet = Dataset(dataSetId, featureCollection, roadLinksList)

    runWithRollback {
      val numberOfFeaturesWithoutId = ServiceWithDao.validateAndInsertDataset(dataSet)
      val datasetStatus = ServiceWithDao.awsDao.getDatasetStatus(dataSetId)
      val featuresStatus = ServiceWithDao.awsDao.getAllFeatureIdAndStatusByDataset(dataSetId)

      numberOfFeaturesWithoutId should be(None)
      datasetStatus should be(0)
      featuresStatus should be (List(("100000","0")))

      ServiceWithDao.updateDataset(dataSet)
      val datasetStatus2 = ServiceWithDao.awsDao.getDatasetStatus(dataSetId)
      val featuresStatus2 = ServiceWithDao.awsDao.getAllFeatureIdAndStatusByDataset(dataSetId)

      datasetStatus2 should be(3)
      featuresStatus2 should be (List(("100000","1")))
    }
  }
}