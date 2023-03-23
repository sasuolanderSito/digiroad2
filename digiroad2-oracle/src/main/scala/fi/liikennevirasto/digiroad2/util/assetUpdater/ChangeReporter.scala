package fi.liikennevirasto.digiroad2.util.assetUpdater

import com.github.tototoshi.csv.CSVWriter
import fi.liikennevirasto.digiroad2.asset.RoadLinkProperties
import fi.liikennevirasto.digiroad2.service.AwsService
import fi.liikennevirasto.digiroad2.util.Digiroad2Properties
import fi.liikennevirasto.digiroad2.{GeometryUtils, Point}
import org.joda.time.DateTime
import org.json4s.jackson.Serialization
import org.json4s.{DefaultFormats, Formats}
import org.slf4j.LoggerFactory

import java.io.StringWriter

/**
  *  For point like asset mark [[endMValue]] None
  * @param linkId Road Link id
  * @param startMValue start point
  * @param endMValue end point
  * @param sideCode                 
  * @param length 
  */
sealed case class LinearReference(linkId: String, startMValue: Double, endMValue: Option[Double],sideCode: Int, length: Double)

/**
  * 
  * @param assetId
  * @param values values as string. Convert into json format. TODO add json formatter into class as needed.
  * @param municipalityCode
  * @param geometry
  * @param linearReference Where asset is in. For floating use None.
  * @param isPointAsset
  */
sealed case class Asset(assetId: Long, values: String, municipalityCode: Option[Int], geometry: Option[Seq[Point]],
                        linearReference: Option[LinearReference], isPointAsset: Boolean = false) {

  def directLink: String = Digiroad2Properties.feedbackAssetsEndPoint
  val logger = LoggerFactory.getLogger(getClass)
  def geometryToString: String = {
    if (geometry.nonEmpty) {
      if (!isPointAsset) {
        GeometryUtils.toWktLineString(GeometryUtils.toDefaultPrecision(geometry.get)).string
      } else {
        val point = geometry.get.last
        GeometryUtils.toWktPoint(point.x, point.y).string
      }

    } else {
      logger.warn("Asset does not have geometry")
      ""
    }
  }

  def getUrl: String = {
    if (linearReference.nonEmpty) {
      s"""$directLink#linkProperty/${linearReference.get.linkId}"""
    }  else ""
  }

}

sealed trait ChangeType {
  def value: Int
}

object ChangeTypeReport {
  
  case object Creation extends ChangeType {
    def value: Int = 1
  }

  case object Deletion extends ChangeType {
    def value: Int = 2
  }

  case object Divided extends ChangeType {
    def value: Int = 3
  }

  case object Replaced extends ChangeType {
    def value: Int = 4
  }
  case object PropertyChange extends ChangeType {
    def value: Int = 5
  }
  
  /**
    * For point asset
    * */
  case object Move extends ChangeType {
    def value: Int = 7
  }

  /**
    * For point asset
    * */
  case object Floating extends ChangeType {
    def value: Int = 8
  }
}

sealed trait ReportedChange {
  def linkId: String
  def changeType: ChangeType
}

case class AdministrativeClassChange(linkId: String, changeType: ChangeType, oldValue: Int, newValue: Option[Int]) extends ReportedChange
case class TrafficDirectionChange(linkId: String, changeType: ChangeType, oldValue: Int, newValue: Option[Int]) extends ReportedChange
case class RoadLinkAttributeChange(linkId: String, changeType: ChangeType, oldValues: Map[String, String], newValues: Map[String, String]) extends ReportedChange
case class FunctionalClassChange(linkId: String, changeType: ChangeType, oldValue: Option[Int], newValue: Option[Int], source: String = "") extends ReportedChange
case class LinkTypeChange(linkId: String, changeType: ChangeType, oldValue: Option[Int], newValue: Option[Int], source: String = "") extends ReportedChange


/**
  * 
  * @param linkId     link where changes is happening TODO remove if not needed
  * @param assetId    asset which is under samuutus, When there is more than one asset under samuutus (e.g merger or join) create new  [[ChangedAsset]] item for each asset.
  * @param changeType characteristic of change
  * @param before     situation before samuutus
  * @param after      after samuutus
  * */
case class ChangedAsset(linkId: String, assetId: Long, changeType: ChangeType, before: Asset, after: Seq[Asset]) extends ReportedChange

/**
  *
  * @param assetType
  * @param changes
  */
case class ChangeReport(assetType: Int, changes: Seq[ReportedChange])

object ChangeReporter {

  lazy val awsService = new AwsService
  lazy val s3Service: awsService.S3.type = awsService.S3
  lazy val s3Bucket: String = Digiroad2Properties.samuutusReportsBucketName
  implicit lazy val serializationFormats: Formats = DefaultFormats

  private def getCSVRowForRoadLinkPropertyChanges(linkId: String, changeType: Int, changes: Seq[ReportedChange]) = {
    val trafficDirectionChange = changes.find(_.isInstanceOf[TrafficDirectionChange])
    val (oldTrafficDirection, newTrafficDirection) = trafficDirectionChange match {
      case trChange: Some[TrafficDirectionChange] =>
        val oldValue = trChange.get.oldValue
        val newValue = trChange.get.newValue match {
          case Some(value) => value
          case _ => null
        }
        (oldValue, newValue)
      case _ => (null, null)
    }
    val adminClassChange = changes.find(_.isInstanceOf[AdministrativeClassChange])
    val (oldAdminClass, newAdminClass) = adminClassChange match {
      case acChange: Some[AdministrativeClassChange] =>
        val oldValue = acChange.get.oldValue
        val newValue = acChange.get.newValue match {
          case Some(value) => value
          case _ => null
        }
        (oldValue, newValue)
      case _ => (null, null)
    }
    val functionalClassChange = changes.find(_.isInstanceOf[FunctionalClassChange])
    val (oldFunctionalClass, newFunctionalClass, fcSource) = functionalClassChange match {
      case fcChange: Some[FunctionalClassChange] =>
        val oldValue = fcChange.get.oldValue match {
          case Some(value) => value
          case _ => null
        }
        val newValue = fcChange.get.newValue match {
          case Some(value) => value
          case _ => null
        }
        val source = fcChange.get.source
        (oldValue, newValue, source)
      case _ => (null, null, null)
    }
    val linkTypeChange = changes.find(_.isInstanceOf[LinkTypeChange])
    val (oldLinkType, newLinkType, ltSource) = linkTypeChange match {
      case ltChange: Some[LinkTypeChange] =>
        val oldValue = ltChange.get.oldValue match {
          case Some(value) => value
          case _ => null
        }
        val newValue = ltChange.get.newValue match {
          case Some(value) => value
          case _ => null
        }
        val source = ltChange.get.source
        (oldValue, newValue, source)
      case _ => (null, null, null)
    }
    val attributeChange = changes.find(_.isInstanceOf[RoadLinkAttributeChange])
    val (oldAttributes, newAttributes) = attributeChange match {
      case attributeChange: Some[RoadLinkAttributeChange] =>
        (Serialization.write(attributeChange.get.oldValues), Serialization.write(attributeChange.get.newValues))

      case _ => (null, null)
    }
    Seq(linkId, changeType, oldTrafficDirection, newTrafficDirection, oldAdminClass, newAdminClass, oldFunctionalClass,
      newFunctionalClass, fcSource, oldLinkType, newLinkType, ltSource, oldAttributes, newAttributes)
  }

  def generateCSV(changeReport: ChangeReport) = {
    val stringWriter = new StringWriter()
    val csvWriter = new CSVWriter(stringWriter)

    val (assetTypeId, changes) = (changeReport.assetType, changeReport.changes)
    val linkIds = changes.map(_.linkId).toSet
    if (assetTypeId == RoadLinkProperties.typeId) {
      val labels = Seq("linkId", "changeType", "oldTrafficDirection", "newTrafficDirection", "oldAdminClass", "newAdminClass", "oldFunctionalClass",
        "newFunctionalClass", "functionalClassSource", "oldLinkType", "newLinkType", "linkTypeSource", "oldLinkAttributes", "newLinkAttributes")
      csvWriter.writeRow(labels)
      linkIds.foreach { linkId =>
        val propertyChangesForLink = changes.filter(_.linkId == linkId)
        val changeType = propertyChangesForLink.head.changeType
        val csvRow = getCSVRowForRoadLinkPropertyChanges(linkId, changeType.value, propertyChangesForLink)
        csvWriter.writeRow(csvRow)
      }
    } else {
      //implement logic for other assets
    }
    (stringWriter.toString, linkIds.size)
  }

  def saveReportToS3(assetName: String, body: String, contentRowCount: Int, hasGeometry: Boolean = false) = {
    val date = DateTime.now().toString("YYYY-MM-dd")
    val withGeometry = if (hasGeometry) "_withGeometry" else ""
    val path = s"${date}/${assetName}_${date}_${contentRowCount}content_rows${withGeometry}.csv"
    s3Service.saveFileToS3(s3Bucket, path, body, "csv")
  }
}
