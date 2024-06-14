package fi.liikennevirasto.digiroad2.util

import fi.liikennevirasto.digiroad2.DummyEventBus
import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.client.RoadLinkClient
import fi.liikennevirasto.digiroad2.dao.Queries
import fi.liikennevirasto.digiroad2.linearasset.{DynamicAssetValue, DynamicValue, PersistedLinearAsset}
import fi.liikennevirasto.digiroad2.postgis.PostGISDatabase
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.service.linearasset.DamagedByThawService
import org.joda.time.DateTime
import org.joda.time.format.{DateTimeFormat, DateTimeFormatter}
import org.slf4j.{Logger, LoggerFactory}

class DamagedByThawRepeater {
  val ActivePeriod = "spring_thaw_period"
  val Repetition = "annual_repetition"
  val dateFormat = "dd.MM.yyyy"
  val formatter: DateTimeFormatter = DateTimeFormat.forPattern(dateFormat)
  val today: DateTime = DateTime.now()
  val roadLinkClient: RoadLinkClient = new RoadLinkClient()
  val dummyEventBus = new DummyEventBus
  val roadLinkService: RoadLinkService = new RoadLinkService(roadLinkClient, dummyEventBus)
  val damagedByThawService: DamagedByThawService = new DamagedByThawService(roadLinkService, dummyEventBus)
  val logger: Logger = LoggerFactory.getLogger(getClass)

  def withDynTransaction[T](f: => T): T = PostGISDatabase.withDynTransaction(f)

  def getProperties(publicId: String, propertyData: Seq[DynamicProperty]): Seq[DynamicPropertyValue] = {
    propertyData.find(p => p.publicId == publicId) match {
      case Some(props) => props.values
      case _ => Seq()
    }
  }

  def toCurrentYear(period: DatePeriodValue): DatePeriodValue = {
    val endDate = DateParser.stringToDate(period.endDate, DateParser.DatePropertyFormat)
    val startDate = DateParser.stringToDate(period.startDate, DateParser.DatePropertyFormat)
    val difference = today.getYear - endDate.getYear
    if (difference == 0)
      DatePeriodValue(DateParser.dateToString(startDate.plusYears(1), DateParser.DatePropertyFormat), DateParser.dateToString(endDate.plusYears(1), DateParser.DatePropertyFormat))
    else
      DatePeriodValue(DateParser.dateToString(startDate.plusYears(difference), DateParser.DatePropertyFormat), DateParser.dateToString(endDate.plusYears(difference), DateParser.DatePropertyFormat))
  }

  def outsidePeriod(value: DynamicPropertyValue): Boolean = {
    val period = DatePeriodValue.fromMap(value.value.asInstanceOf[Map[String, String]])
    val endDate = DateParser.stringToDate(period.endDate, DateParser.DatePropertyFormat)
    val thisYear = today.getYear
    val endDateYear = endDate.getYear

    thisYear - endDateYear >= 0 && endDate.isBefore(today)
  }

  def isRepeated(checkbox: Seq[DynamicPropertyValue]): Boolean = {
    checkbox.exists(x => x.value.asInstanceOf[String].equals("1"))
  }

  def needUpdates(properties: Seq[DynamicProperty]): Boolean = {
    isRepeated(getProperties(Repetition, properties)) &&
      getProperties(ActivePeriod, properties).exists { period =>
        outsidePeriod(period)
      }
  }

  def updateAllDamagedByThawActivityPeriods(): Unit = {
    withDynTransaction {
      val municipalities = Queries.getMunicipalities
      municipalities.foreach(municipality => {
        updateActivityPeriods(municipality)
      })
    }
  }

  def updateActivityPeriods(municipality: Int): Unit = {
    val assetsToUpdate = getAssetsToUpdate(municipality)
    val assetsWithUpdatedProps = getUpdatedProps(assetsToUpdate)
    if(assetsWithUpdatedProps.nonEmpty) {
      logger.info(s"Updating activity period for ${assetsWithUpdatedProps.size} assets in municipality: $municipality")
    }
    assetsWithUpdatedProps.foreach(asset => {
      damagedByThawService.updateWithoutTransaction(ids = Seq(asset.id), value = asset.value.get, username = AutoGeneratedUsername.annualUpdate)
    })
  }

  def getAssetsToUpdate(municipality: Int): Seq[PersistedLinearAsset] = {
    val allAssetsInMunicipality = damagedByThawService.getAssetsByMunicipality(DamagedByThaw.typeId, municipality, newTransaction = false)
    val enrichedWithPropertyValues = damagedByThawService.enrichPersistedLinearAssetProperties(allAssetsInMunicipality)
    enrichedWithPropertyValues.filter(asset =>
      asset.value.map(_.asInstanceOf[DynamicValue].value.properties).exists {
        propertyData => needUpdates(propertyData)
      })
  }

  def getUpdatedProps(assetsInMunicipality: Seq[PersistedLinearAsset]): Seq[PersistedLinearAsset] = {
    val assetsWithUpdatedProps = assetsInMunicipality.map { asset =>
      asset.copy(value = Some(DynamicValue(DynamicAssetValue(asset.value.get.asInstanceOf[DynamicValue].value.properties.map { prop =>
        if (prop.publicId == ActivePeriod) {
          prop.copy(values = prop.values.map { period =>
            if (outsidePeriod(period))
              DynamicPropertyValue(DatePeriodValue.toMap(toCurrentYear(DatePeriodValue.fromMap(period.value.asInstanceOf[Map[String, String]]))))
            else
              period
          })
        } else prop
      }))), modifiedBy = Some(AutoGeneratedUsername.annualUpdate))
    }
    assetsWithUpdatedProps
  }
}
