package fi.liikennevirasto.digiroad2.middleware

import java.io.InputStream
import java.util.Properties

import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2._
import fi.liikennevirasto.digiroad2.csvDataImporter.{MaintenanceRoadCsvImporter, MassTransitStopCsvImporter, MassTransitStopCsvOperation, ObstaclesCsvImporter, PedestrianCrossingCsvImporter, RailwayCrossingCsvImporter, RoadLinkCsvImporter, ServicePointCsvImporter, TrafficLightsCsvImporter, TrafficSignCsvImporter}
import fi.liikennevirasto.digiroad2.user.{User, UserProvider}

sealed trait AdditionalImportValue {
  def toJson: Any
}

case class AdministrativeValues(administrativeClasses: AdministrativeClass) extends AdditionalImportValue {
  override def toJson: Any = administrativeClasses
}

case class NumericValues(values: Int) extends  AdditionalImportValue {
  override def toJson: Any = values
}

case class CsvDataImporterInfo(assetTypeName: String, fileName: String, user: User, inputStream: InputStream, additionalImportInfo: Set[AdditionalImportValue] = Set())

class DataImportManager(roadLinkService: RoadLinkService, eventBus: DigiroadEventBus) {

  lazy val trafficSignCsvImporter: TrafficSignCsvImporter = new TrafficSignCsvImporter(roadLinkService, eventBus)
  lazy val maintenanceRoadCsvImporter: MaintenanceRoadCsvImporter = new MaintenanceRoadCsvImporter(roadLinkService, eventBus)
  lazy val massTransitStopCsvImporter: MassTransitStopCsvOperation = new MassTransitStopCsvOperation(roadLinkService, eventBus)
  lazy val roadLinkCsvImporter: RoadLinkCsvImporter = new RoadLinkCsvImporter(roadLinkService, eventBus)
  lazy val obstaclesCsvImporter: ObstaclesCsvImporter = new ObstaclesCsvImporter(roadLinkService, eventBus)
  lazy val trafficLightsCsvImporter: TrafficLightsCsvImporter = new TrafficLightsCsvImporter(roadLinkService, eventBus)
  lazy val pedestrianCrossingCsvImporter: PedestrianCrossingCsvImporter = new PedestrianCrossingCsvImporter(roadLinkService, eventBus)
  lazy val railwayCrossingCsvImporter: RailwayCrossingCsvImporter = new RailwayCrossingCsvImporter(roadLinkService, eventBus)
  lazy val servicePointCsvImporter: ServicePointCsvImporter = new ServicePointCsvImporter(roadLinkService, eventBus)

  def importer(dataImporterInfo: CsvDataImporterInfo) {

    dataImporterInfo.assetTypeName match {
      case TrafficSigns.layerName =>
        trafficSignCsvImporter.importAssets(dataImporterInfo.inputStream, dataImporterInfo.fileName, dataImporterInfo.user, dataImporterInfo.additionalImportInfo.map(_.asInstanceOf[NumericValues].values))
      case MaintenanceRoadAsset.layerName =>
        maintenanceRoadCsvImporter.importAssets(dataImporterInfo.inputStream, dataImporterInfo.fileName, dataImporterInfo.user.username)
      case "roadLinks" =>
        roadLinkCsvImporter.importAssets(dataImporterInfo.inputStream, dataImporterInfo.fileName, dataImporterInfo.user.username)
      case MassTransitStopAsset.layerName =>
        massTransitStopCsvImporter.importAssets(dataImporterInfo.inputStream, dataImporterInfo.fileName, dataImporterInfo.user, dataImporterInfo.additionalImportInfo.map(_.asInstanceOf[AdministrativeValues].administrativeClasses))
      case Obstacles.layerName =>
        obstaclesCsvImporter.importAssets(dataImporterInfo.inputStream, dataImporterInfo.fileName, dataImporterInfo.user)
      case TrafficLights.layerName =>
        trafficLightsCsvImporter.importAssets(dataImporterInfo.inputStream, dataImporterInfo.fileName, dataImporterInfo.user)
      case RailwayCrossings.layerName =>
        railwayCrossingCsvImporter.importAssets(dataImporterInfo.inputStream, dataImporterInfo.fileName, dataImporterInfo.user)
      case PedestrianCrossings.layerName =>
        pedestrianCrossingCsvImporter.importAssets(dataImporterInfo.inputStream, dataImporterInfo.fileName, dataImporterInfo.user)
      case ServicePoints.layerName =>
        servicePointCsvImporter.importAssets(dataImporterInfo.inputStream, dataImporterInfo.fileName, dataImporterInfo.user)
      case _ =>
    }
  }
}