package fi.liikennevirasto.digiroad2

import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.client.vvh.VVHClient
import fi.liikennevirasto.digiroad2.dao.AwsDao
import fi.liikennevirasto.digiroad2.linearasset._
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.service.linearasset._
import fi.liikennevirasto.digiroad2.service.pointasset.{HeightLimit => _, WidthLimit => _, _}
import org.json4s._
import org.json4s.jackson.Serialization.write
import org.scalatra._
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.swagger.{Swagger, SwaggerSupport}

case class Dataset(datasetId: String, featuresCollection: FeatureCollection, roadlinks: List[List[Long]])
case class FeatureCollection(typee: String, features: List[Feature])
case class Feature(typee: String, geometry: Geometry, properties: Map[String, String])
case class Geometry(typee: String, coordinates: List[List[Double]], crs: Map[String, Any])

sealed trait DatasetStatus{
  def value: Int
  def description: String
}

object DatasetStatus{
  val values = Set[DatasetStatus](Inserted, FeatureRoadlinksDontMatch, ErrorsFeatures, Processed, ErrorsProcessing)

  def apply(intValue: Int): DatasetStatus= {
    values.find(_.value == intValue).getOrElse(ErrorsProcessing)
  }

  case object Inserted extends DatasetStatus{ def value = 0; def description = "Inserted successfuly";}
  case object FeatureRoadlinksDontMatch extends DatasetStatus{ def value = 1; def description = "Amount of features and roadlinks do not match";}
  case object ErrorsFeatures extends DatasetStatus{ def value = 2; def description = "Errors in the features";}
  case object Processed extends DatasetStatus{ def value = 3; def description = "Processed successfuly";}
  case object ErrorsProcessing extends DatasetStatus{ def value = 4; def description = "Errors while processing";}
}

sealed trait FeatureStatus{
  def value: Int
  def description: String
}

object FeatureStatus{
  val values = Set[FeatureStatus](Inserted, Processed, WrongSpeedLimit, WrongPavementClass, WrongObstacleClass,
    WrongSideCode, NoGeometryType, RoadlinkNoTypeInProperties, ErrorsWhileProcessing, WrongRoadlinks)

  def apply(intValue: Int): FeatureStatus= {
    values.find(_.value == intValue).getOrElse(ErrorsWhileProcessing)
  }

  case object Inserted extends FeatureStatus{ def value = 0; def description = "Inserted successfuly";}
  case object Processed extends FeatureStatus{ def value = 1; def description = "Processed successfuly";}
  case object WrongSpeedLimit extends FeatureStatus{ def value = 2; def description = "SpeedLimit with invalid speed";}
  case object WrongPavementClass extends FeatureStatus{ def value = 3; def description = "PavementClass with invalid pavement class";}
  case object WrongObstacleClass extends FeatureStatus{ def value = 4; def description = "Obstacle with invalid class";}
  case object WrongSideCode extends FeatureStatus{ def value = 5; def description = "Invalid sideCode";}
  case object NoGeometryType extends FeatureStatus{ def value = 6; def description = "Geometry type not found";}
  case object RoadlinkNoTypeInProperties extends FeatureStatus{ def value = 7; def description = "Roadlink with no type in properties";}
  case object ErrorsWhileProcessing extends FeatureStatus{ def value = 8; def description = "Errors while updating";}
  case object WrongRoadlinks extends FeatureStatus{ def value = 9; def description = "Wrong roadlinks";}
}

class MunicipalityApi(val vvhClient: VVHClient,
                      val roadLinkService: RoadLinkService,
                      val speedLimitService: SpeedLimitService,
                      val pavedRoadService: PavedRoadService,
                      val obstacleService: ObstacleService,
                      implicit val swagger: Swagger
                     ) extends ScalatraServlet with JacksonJsonSupport with AuthenticationSupport with SwaggerSupport {

  override def baseAuth: String = "municipality."
  override val realm: String = "Municipality API"
  protected val applicationDescription = "Municipality API "

  case object GeometrySerializer extends CustomSerializer[Geometry](format =>
    ({
      case jsonObj: JObject =>
        val typee = (jsonObj \ "type").extract[String]
        val coordinates = (jsonObj \ "coordinates").extract[List[List[Double]]]
        val crs = (jsonObj \ "crs").extract[Map[String, Any]]

        Geometry(typee, coordinates, crs)
    },
      {
        case tv : Geometry => Extraction.decompose(tv)
      }))

  case object FeatureSerializer extends CustomSerializer[Feature](format =>
    ({
      case jsonObj: JObject =>
        val typee = (jsonObj \ "type").extract[String]
        val geometry = (jsonObj \ "geometry").extract[Geometry]
        val properties = (jsonObj \ "properties").extract[Map[String, String]]

        Feature(typee, geometry, properties)
    },
      {
        case tv : Feature => Extraction.decompose(tv)
      }))

  case object FeatureCollectionSerializer extends CustomSerializer[FeatureCollection](format =>
    ({
      case jsonObj: JObject =>
        val typee = (jsonObj \ "type").extract[String]
        val featuresCollection = (jsonObj \ "features").extract[List[Feature]]

        FeatureCollection(typee, featuresCollection)
    },
      {
        case tv : FeatureCollection => Extraction.decompose(tv)
      }))

  case object DatasetSerializer extends CustomSerializer[Dataset](format =>
    ({
      case jsonObj: JObject =>
        val id = (jsonObj \ "datasetId").extract[String]
        val roadlinks = (jsonObj \ "matchedRoadlinks").extract[List[List[Long]]]
        val features = (jsonObj \ "geojson").extract[FeatureCollection]

        Dataset(id, features, roadlinks)
    },
      {
        case tv : Dataset => Extraction.decompose(tv)
      }))

  protected implicit val jsonFormats: Formats = DefaultFormats + DatasetSerializer + FeatureCollectionSerializer + FeatureSerializer + GeometrySerializer

  before() {
    basicAuth
  }

  def withDynTransaction[T](f: => T): T = OracleDatabase.withDynTransaction(f)
  def withDynSession[T](f: => T): T = OracleDatabase.withDynSession(f)
  def awsDao: AwsDao = new AwsDao

  final val AwsUser = "AwsUpdater"

  private def insertFeatureAndUpdateDataset(datasetId: String, featureId: Long, featureStatus: List[FeatureStatus]) = {
    val allFeatureStatus = featureStatus.distinct.filterNot(feature => feature == FeatureStatus.Inserted)

    if (allFeatureStatus.isEmpty) {
      awsDao.insertFeature(featureId, datasetId, FeatureStatus.Inserted.value.toString)
    } else {
      awsDao.insertFeature(featureId, datasetId, allFeatureStatus.map(feature => feature.value).mkString(","))
      awsDao.updateDatasetStatus(datasetId, DatasetStatus.ErrorsFeatures.value)
    }
  }

  private def linkIdValidation(datasetId: String, featureId: Long, linkIds: Set[Long]): FeatureStatus = {
    val roadLinks = roadLinkService.getRoadsLinksFromVVH(linkIds, false)
    if(!(linkIds.nonEmpty && roadLinks.count(road => road.administrativeClass != State) == linkIds.size))
    {
      FeatureStatus.WrongRoadlinks
    } else {
      FeatureStatus.Inserted
    }
  }

  private def validatePoint(datasetId: String, featureId: Long, properties: Map[String, String]): List[FeatureStatus] = {
    val assetType = properties("type")

    val status = assetType match {
      case "obstacle" =>
        properties.get("class") match {
          case Some(value) =>
            if (!Set(1, 2).contains(value.toInt)) {
              FeatureStatus.WrongObstacleClass
            } else {
              FeatureStatus.Inserted
            }
          case None => FeatureStatus.WrongObstacleClass
        }
    }
    List(status)
  }

  private def validateLinearAssets(datasetId: String, featureId: Long, properties: Map[String, String]): List[FeatureStatus] = {
    val speedLimit = properties.get("speedLimit")
    val pavementClass = properties.get("pavementClass")
    val sideCode = properties.get("sideCode")

    val speedlimitStatus = speedLimit match {
      case Some(value) =>
        if (!Set("20", "30", "40", "50", "60", "70", "80", "90", "100", "120").contains(value)) {
          FeatureStatus.WrongSpeedLimit
        } else {
          FeatureStatus.Inserted
        }
      case None => FeatureStatus.Inserted
    }

    val pavementClassStatus = pavementClass match {
      case Some(value) =>
        if (!Seq("1", "2", "10", "20", "30", "40", "50").contains(value)) {
          FeatureStatus.WrongPavementClass
        } else {
          FeatureStatus.Inserted
        }
      case None => FeatureStatus.Inserted
    }

    val sideCodeStatus = sideCode match {
      case Some(value) =>
        if (!Seq(SideCode.BothDirections.value, SideCode.TowardsDigitizing.value, SideCode.AgainstDigitizing.value).contains(value.toInt)) {
          FeatureStatus.WrongSideCode
        } else {
          FeatureStatus.Inserted
        }
      case None =>
        FeatureStatus.WrongSideCode
    }

    List(speedlimitStatus, pavementClassStatus, sideCodeStatus)
  }

  private def updatePoint(properties: Map[String, String], link: RoadLink, assetType: String, assetCoordinates: List[Double]): Unit = {
    assetType match {
      case "obstacle" =>
        val obstacleType = properties("class").toInt
        val newObstacle = IncomingObstacle(assetCoordinates.head, assetCoordinates(1), link.linkId, obstacleType)
        obstacleService.createFromCoordinates(newObstacle, link, AwsUser, false)
    }
  }

  private def updateLinearAssets(properties: Map[String, String], links: Seq[RoadLink]) = {
    val speedLimit = properties.get("speedLimit")
    val pavementClass = properties.get("pavementClass")

    links.foreach { link =>
      speedLimit match {
        case Some(value) =>
          val speedLimitValue = value.toInt
          val speedLimitsOnRoadLink = speedLimitService.getExistingAssetByRoadLink(link, false)

          if (speedLimitsOnRoadLink.isEmpty) {
            val newSpeedLimitAsset = NewLinearAsset(link.linkId, 0, link.length, NumericValue(speedLimitValue), properties("sideCode").toInt, vvhClient.roadLinkData.createVVHTimeStamp(), None)
            speedLimitService.createMultiple(Seq(newSpeedLimitAsset), speedLimitValue, AwsUser, vvhClient.roadLinkData.createVVHTimeStamp(), (_, _) => Unit)
          } else
            speedLimitsOnRoadLink.foreach { sl =>
              val newSpeedLimitAsset = NewLinearAsset(link.linkId, sl.startMeasure, sl.endMeasure, NumericValue(speedLimitValue), properties("sideCode").toInt, vvhClient.roadLinkData.createVVHTimeStamp(), None)
              speedLimitService.update(sl.id, Seq(newSpeedLimitAsset), AwsUser)
            }
        case None =>
      }

      pavementClass match {
        case Some(value) =>
          val pavementClassValue = DynamicValue(DynamicAssetValue(Seq(DynamicProperty("paallysteluokka", "single_choice", required = false, Seq(DynamicPropertyValue(value.toInt))))))

          val duplicate = pavedRoadService.getPersistedAssetsByLinkIds(PavedRoad.typeId, Seq(link.linkId))
          if(duplicate.isEmpty) {
            val newPavementClassAsset = NewLinearAsset(link.linkId, 0, link.length, pavementClassValue, properties("sideCode").toInt, vvhClient.roadLinkData.createVVHTimeStamp(), None)
            pavedRoadService.create(Seq(newPavementClassAsset), PavedRoad.typeId, AwsUser)
          } else {
            pavedRoadService.update(Seq(duplicate.head.id), pavementClassValue, AwsUser)
          }
        case None =>
      }
    }
  }

  def validateAndInsertDataset(dataset: Dataset): Option[Int] = {
    val assets = dataset.featuresCollection.features
    val roadlinks = dataset.roadlinks

    if (assets.length != roadlinks.length) {
      //TODO write geojson to db
      awsDao.insertDataset(dataset.datasetId, "write(dataset.features)", write(dataset.roadlinks), DatasetStatus.FeatureRoadlinksDontMatch.value)
      None
    }
    else{
      awsDao.insertDataset(dataset.datasetId, "write(dataset.features)", write(dataset.roadlinks), DatasetStatus.Inserted.value)

      val featuresWithoutIds: List[Option[Int]] = (roadlinks, assets).zipped.map((featureRoadlinks, feature) => {
        val properties = feature.properties
        properties.get("id") match {
          case Some(id) =>
            val featureId = id.toLong
            val linkIdValidationStatus = linkIdValidation(dataset.datasetId, featureId, featureRoadlinks.toSet)
            val assetTypeGeometry = feature.geometry.typee
            val propertiesStatus: List[FeatureStatus] = assetTypeGeometry match {
              case "LineString" =>
                properties("type") match {
                  case "Roadlink" => validateLinearAssets(dataset.datasetId, featureId, properties)
                  case _ =>
                    List(FeatureStatus.RoadlinkNoTypeInProperties)
                }
              case "Point" => validatePoint(dataset.datasetId, featureId, properties)
              case _ =>
                List(FeatureStatus.NoGeometryType)
            }
            val featureStatus = linkIdValidationStatus :: propertiesStatus

            insertFeatureAndUpdateDataset(dataset.datasetId, featureId, featureStatus)
            None

          case None => awsDao.updateDatasetStatus(dataset.datasetId, DatasetStatus.ErrorsFeatures.value)
            Some(1)
        }
      })
      val totalFeaturesWithoutIds = featuresWithoutIds.flatten

      if(totalFeaturesWithoutIds.isEmpty){
        None
      } else {
        Some(totalFeaturesWithoutIds.sum)
      }
    }
  }

  def updateDataset(dataset: Dataset) = {
    if (awsDao.getDatasetStatus(dataset.datasetId) != DatasetStatus.FeatureRoadlinksDontMatch.value) {
      val assets = dataset.featuresCollection.features
      val roadlinks = dataset.roadlinks

      (roadlinks, assets).zipped.foreach((featureRoadlinks, feature) => {
        val properties = feature.properties

        properties.get("id") match {
          case Some(id) =>
            val featureId = id.toLong
            val status = awsDao.getFeatureStatus(featureId, dataset.datasetId)
            if (status == FeatureStatus.Inserted.value.toString) {

              val assetTypeGeometry = feature.geometry.typee
              val assetType = properties("type")

              assetTypeGeometry match {
                case "LineString" => assetType match {
                  case "Roadlink" =>
                    val links = roadLinkService.getRoadsLinksFromVVH(featureRoadlinks.toSet, false)
                      .filter(link => !Set(7,8,99).contains(link.functionalClass))

                    updateLinearAssets(properties, links)
                }
                  awsDao.updateFeatureStatus(featureId, FeatureStatus.Processed.value)

                case "Point" =>
                  val assetCoordinates = feature.geometry.coordinates.head
                  val link = roadLinkService.getRoadLinkFromVVH(featureRoadlinks.head, false).get
                  updatePoint(properties, link, assetType, assetCoordinates)
                  awsDao.updateFeatureStatus(featureId, FeatureStatus.Processed.value)
              }
            }
          case None =>
        }
      })

      val errors = awsDao.getProcessedDatasetFeaturesForErrors(dataset.datasetId)
      if (errors == 0) {
        awsDao.updateDatasetStatus(dataset.datasetId, DatasetStatus.Processed.value)
      } else {
        awsDao.updateDatasetStatus(dataset.datasetId, DatasetStatus.ErrorsProcessing.value)
      }
    }
  }

  def getDatasetStatusById(datasetId: String): String = {
    DatasetStatus(awsDao.getDatasetStatus(datasetId)).description
  }

  def getFeatureErrorsByDatasetId(datasetId: String, datasetFeaturesWithoutIds: Int): Any = {
    val featuresStatusCode = awsDao.getAllFeatureIdAndStatusByDataset(datasetId).filter { case (_, status) => status != DatasetStatus.ErrorsFeatures.value.toString}

    val featuresStatusMap = featuresStatusCode.map { case (featureId, status) =>
      Map(
        "FeatureId" -> featureId.toString,
        "Message" -> status.split(",").map(message => FeatureStatus(message.toInt).description)
      )
    } ++ {
      if (datasetFeaturesWithoutIds != 0) {
        Map("Features without ids" -> datasetFeaturesWithoutIds.toString)
      } else {
        Map()
      }
    }

    featuresStatusMap
  }

  put("/assetUpdateFromAWS") {
    try {
      val listDatasets: List[Dataset] = parsedBody.extractOrElse[List[Dataset]](throw new ClassCastException)

      OracleDatabase.withDynTransaction {
        val datasetFeaturesWithoutIds: Map[String, Option[Int]] = listDatasets.flatMap(dataset =>
          Map(dataset.datasetId -> validateAndInsertDataset(dataset))).toMap

        listDatasets.foreach(dataset =>
          updateDataset(dataset)
        )

        listDatasets.map{dataset =>
          val datasetId = dataset.datasetId
          val datasetStatus = getDatasetStatusById(datasetId)
          if ((datasetStatus == DatasetStatus.Processed.description || datasetStatus == DatasetStatus.FeatureRoadlinksDontMatch.description) && datasetFeaturesWithoutIds(datasetId).isEmpty) {
            Map(
              "DataSetId" -> datasetId,
              "Status" -> datasetStatus
            )
          } else {
            Map(
              "DataSetId" -> datasetId,
              "Status" -> datasetStatus,
              "Features with errors" -> getFeatureErrorsByDatasetId(datasetId, datasetFeaturesWithoutIds(datasetId).get)
            )
          }
        }
      }
    } catch {
      case cce: ClassCastException => halt(BadRequest("Error when extracting dataSet in JSON"))
      case e: Exception => halt(BadRequest("Could not process Datasets. Verify information provided"))
    }
  }
}
