package fi.liikennevirasto.digiroad2.util.assetUpdater

import fi.liikennevirasto.digiroad2.asset.ConstructionType.InUse
import fi.liikennevirasto.digiroad2.asset.TrafficDirection.{AgainstDigitizing, TowardsDigitizing}
import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.client.FeatureClass.WinterRoads
import fi.liikennevirasto.digiroad2.client.RoadLinkChangeType.{Add, Remove, Replace, Split}
import fi.liikennevirasto.digiroad2.client._
import fi.liikennevirasto.digiroad2.dao.RoadLinkOverrideDAO.{AdministrativeClass, TrafficDirection => TrafficDirectionString, _}
import fi.liikennevirasto.digiroad2.dao.{Queries, RoadLinkOverrideDAO}
import fi.liikennevirasto.digiroad2.linearasset.RoadLink
import fi.liikennevirasto.digiroad2.postgis.PostGISDatabase
import fi.liikennevirasto.digiroad2.service.{IncompleteLink, RoadLinkService}
import fi.liikennevirasto.digiroad2.util.assetUpdater.ChangeTypeReport.{Creation, Deletion, Divided, Replaced}
import fi.liikennevirasto.digiroad2.util.{Digiroad2Properties, KgvUtil, LinearAssetUtils}
import fi.liikennevirasto.digiroad2.{DummyEventBus, DummySerializer}
import org.joda.time.DateTime
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.mutable.ListBuffer

class RoadLinkPropertyUpdater {

  lazy val roadLinkService: RoadLinkService = new RoadLinkService(new RoadLinkClient(Digiroad2Properties.vvhRestApiEndPoint), new DummyEventBus, new DummySerializer)
  lazy val roadLinkChangeClient: RoadLinkChangeClient = new RoadLinkChangeClient
  val logger: Logger = LoggerFactory.getLogger(getClass)

  def withDynTransaction[T](f: => T): T = PostGISDatabase.withDynTransaction(f)

  def roadLinkChangeToChangeType(roadLinkChangeType: RoadLinkChangeType): ChangeType = {
    roadLinkChangeType match {
      case Add => Creation
      case Remove => Deletion
      case Split => Divided
      case Replace => Replaced
    }
  }

  def transferFunctionalClass(changeType: RoadLinkChangeType, oldLink: RoadLinkInfo, newLink: RoadLinkInfo, timeStamp: String): Option[FunctionalClassChange] = {
    FunctionalClassDao.getExistingValue(oldLink.linkId) match {
      case Some(functionalClass) =>
        FunctionalClassDao.insertValues(newLink.linkId, Some(AutoGeneratedUsername.automaticGeneration), functionalClass, timeStamp)
        Some(FunctionalClassChange(newLink.linkId, roadLinkChangeToChangeType(changeType), Some(functionalClass), Some(functionalClass), "oldLink"))
      case _ => None
    }
  }

  def generateFunctionalClass(changeType: RoadLinkChangeType, newLink: RoadLinkInfo): Option[FunctionalClassChange] = {
    val featureClass = KgvUtil.extractFeatureClass(newLink.roadClass)
    val timeStamp = DateTime.now().toString()
    featureClass match {
      case FeatureClass.TractorRoad =>
        FunctionalClassDao.insertValues(newLink.linkId, Some(AutoGeneratedUsername.automaticGeneration), PrimitiveRoad.value, timeStamp)
        Some(FunctionalClassChange(newLink.linkId, roadLinkChangeToChangeType(changeType), None, Some(PrimitiveRoad.value), "mtkClass"))
      case FeatureClass.HardShoulder =>
        FunctionalClassDao.insertValues(newLink.linkId, Some(AutoGeneratedUsername.automaticGeneration), FunctionalClass9.value, timeStamp)
        Some(FunctionalClassChange(newLink.linkId, roadLinkChangeToChangeType(changeType), None, Some(FunctionalClass9.value), "mtkClass"))
      case FeatureClass.DrivePath | FeatureClass.CarRoad_IIIb =>
        FunctionalClassDao.insertValues(newLink.linkId, Some(AutoGeneratedUsername.automaticGeneration), AnotherPrivateRoad.value, timeStamp)
        Some(FunctionalClassChange(newLink.linkId, roadLinkChangeToChangeType(changeType), None, Some(AnotherPrivateRoad.value), "mtkClass"))
      case FeatureClass.CycleOrPedestrianPath =>
        FunctionalClassDao.insertValues(newLink.linkId, Some(AutoGeneratedUsername.automaticGeneration), WalkingAndCyclingPath.value, timeStamp)
        Some(FunctionalClassChange(newLink.linkId, roadLinkChangeToChangeType(changeType), None, Some(WalkingAndCyclingPath.value), "mtkClass"))
      case FeatureClass.SpecialTransportWithoutGate =>
        FunctionalClassDao.insertValues(newLink.linkId, Some(AutoGeneratedUsername.automaticGeneration), UnknownFunctionalClass.value, timeStamp)
        Some(FunctionalClassChange(newLink.linkId, roadLinkChangeToChangeType(changeType), None, Some(UnknownFunctionalClass.value), "mtkClass"))
      case FeatureClass.SpecialTransportWithGate =>
        FunctionalClassDao.insertValues(newLink.linkId, Some(AutoGeneratedUsername.automaticGeneration), UnknownFunctionalClass.value, timeStamp)
        Some(FunctionalClassChange(newLink.linkId, roadLinkChangeToChangeType(changeType), None, Some(UnknownFunctionalClass.value), "mtkClass"))
      case FeatureClass.CarRoad_IIIa => newLink.adminClass match {
        case State =>
          FunctionalClassDao.insertValues(newLink.linkId, Some(AutoGeneratedUsername.automaticGeneration), FunctionalClass4.value, timeStamp)
          Some(FunctionalClassChange(newLink.linkId, roadLinkChangeToChangeType(changeType), None, Some(FunctionalClass4.value), "mtkClass"))
        case Municipality | Private =>
          FunctionalClassDao.insertValues(newLink.linkId, Some(AutoGeneratedUsername.automaticGeneration), FunctionalClass5.value, timeStamp)
          Some(FunctionalClassChange(newLink.linkId, roadLinkChangeToChangeType(changeType), None, Some(FunctionalClass5.value), "mtkClass"))
        case _ => None
      }
      case _ => None
    }
  }

  def transferLinkType(changeType: RoadLinkChangeType, oldLink: RoadLinkInfo, newLink: RoadLinkInfo, timeStamp: String): Option[LinkTypeChange] = {
    LinkTypeDao.getExistingValue(oldLink.linkId) match {
      case Some(linkType) =>
        LinkTypeDao.insertValues(newLink.linkId, Some(AutoGeneratedUsername.automaticGeneration), linkType, timeStamp)
        Some(LinkTypeChange(newLink.linkId, roadLinkChangeToChangeType(changeType), Some(linkType), Some(linkType), "oldLink"))
      case _ =>
        None
    }
  }

  def generateLinkType(changeType: RoadLinkChangeType, newLink: RoadLinkInfo): Option[LinkTypeChange] = {
    val featureClass = KgvUtil.extractFeatureClass(newLink.roadClass)
    val timeStamp = DateTime.now().toString()
    featureClass match {
      case FeatureClass.TractorRoad =>
        LinkTypeDao.insertValues(newLink.linkId, Some(AutoGeneratedUsername.automaticGeneration), TractorRoad.value, timeStamp)
        Some(LinkTypeChange(newLink.linkId, roadLinkChangeToChangeType(changeType), None, Some(TractorRoad.value), "mtkClass"))
      case FeatureClass.HardShoulder =>
        LinkTypeDao.insertValues(newLink.linkId, Some(AutoGeneratedUsername.automaticGeneration), HardShoulder.value, timeStamp)
        Some(LinkTypeChange(newLink.linkId, roadLinkChangeToChangeType(changeType), None, Some(HardShoulder.value), "mtkClass"))
      case FeatureClass.DrivePath | FeatureClass.CarRoad_IIIb =>
        LinkTypeDao.insertValues(newLink.linkId, Some(AutoGeneratedUsername.automaticGeneration), SingleCarriageway.value, timeStamp)
        Some(LinkTypeChange(newLink.linkId, roadLinkChangeToChangeType(changeType), None, Some(SingleCarriageway.value), "mtkClass"))
      case FeatureClass.CycleOrPedestrianPath =>
        LinkTypeDao.insertValues(newLink.linkId, Some(AutoGeneratedUsername.automaticGeneration), CycleOrPedestrianPath.value, timeStamp)
        Some(LinkTypeChange(newLink.linkId, roadLinkChangeToChangeType(changeType), None, Some(CycleOrPedestrianPath.value), "mtkClass"))
      case FeatureClass.SpecialTransportWithoutGate =>
        LinkTypeDao.insertValues(newLink.linkId, Some(AutoGeneratedUsername.automaticGeneration), SpecialTransportWithoutGate.value, timeStamp)
        Some(LinkTypeChange(newLink.linkId, roadLinkChangeToChangeType(changeType), None, Some(SpecialTransportWithoutGate.value), "mtkClass"))
      case FeatureClass.SpecialTransportWithGate =>
        LinkTypeDao.insertValues(newLink.linkId, Some(AutoGeneratedUsername.automaticGeneration), SpecialTransportWithGate.value, timeStamp)
        Some(LinkTypeChange(newLink.linkId, roadLinkChangeToChangeType(changeType), None, Some(SpecialTransportWithGate.value), "mtkClass"))
      case FeatureClass.CarRoad_IIIa =>
        LinkTypeDao.insertValues(newLink.linkId, Some(AutoGeneratedUsername.automaticGeneration), SingleCarriageway.value, timeStamp)
        Some(LinkTypeChange(newLink.linkId, roadLinkChangeToChangeType(changeType), None, Some(SingleCarriageway.value), "mtkClass"))
      case _ => None
    }
  }

  def incompleteLinkIsInUse(incompleteLink: IncompleteLink, roadLinkData: Seq[RoadLink]) = {
    val correspondingRoadLink = roadLinkData.find(_.linkId == incompleteLink.linkId)
    correspondingRoadLink match {
      case Some(roadLink) => roadLink.constructionType == InUse
      case _ => false
    }
  }

  def transferOrGenerateFunctionalClass(changeType: RoadLinkChangeType, optionalOldLink: Option[RoadLinkInfo], newLink: RoadLinkInfo): Option[FunctionalClassChange] = {
    val timeStamp = DateTime.now().toString()
    val alreadyUpdatedFunctionalClass = FunctionalClassDao.getExistingValue(newLink.linkId)
    (alreadyUpdatedFunctionalClass, optionalOldLink) match {
      case (Some(_), _) =>
        None
      case (None, Some(oldLink)) =>
        transferFunctionalClass(changeType, oldLink, newLink, timeStamp) match {
          case Some(functionalClassChange) => Some(functionalClassChange)
          case _ =>
            generateFunctionalClass(changeType, newLink) match {
              case Some(generatedFunctionalClassChange) => Some(generatedFunctionalClassChange)
              case _ => None
            }
        }
      case (None, None) =>
        generateFunctionalClass(changeType, newLink) match {
          case Some(generatedFunctionalClass) => Some(generatedFunctionalClass)
          case _ => None
        }
    }
  }

  def transferOrGenerateLinkType(changeType: RoadLinkChangeType, optionalOldLink: Option[RoadLinkInfo], newLink: RoadLinkInfo): Option[LinkTypeChange] = {
    val timeStamp = DateTime.now().toString()
    val alreadyUpdatedLinkType = LinkTypeDao.getExistingValue(newLink.linkId)
    (alreadyUpdatedLinkType, optionalOldLink) match {
      case (Some(_), _) =>
        None
      case (None, Some(oldLink)) =>
        transferLinkType(changeType, oldLink, newLink, timeStamp) match {
          case Some(linkTypeChange) => Some(linkTypeChange)
          case _ =>
            generateLinkType(changeType, newLink) match {
              case Some(linkTypeChange) => Some(linkTypeChange)
              case _ => None
            }
        }
      case (None, None) =>
        generateLinkType(changeType, newLink) match {
          case Some(generatedLinkType) => Some(generatedLinkType)
          case _ => None
        }
    }
  }

  def transferOrGenerateFunctionalClassesAndLinkTypes(changes: Seq[RoadLinkChange]): Seq[ReportedChange] = {
    val incompleteLinks = new ListBuffer[IncompleteLink]()
    val createdProperties = new ListBuffer[Option[ReportedChange]]()
    val iteratedNewLinks = new ListBuffer[RoadLinkInfo]
    val timeStamp = DateTime.now().toString()
    changes.foreach { change =>
      change.changeType match {
        case Replace =>
          val newLink = change.newLinks.head
          if (!(iteratedNewLinks.contains(newLink)) && KgvUtil.extractFeatureClass(newLink.roadClass) != WinterRoads) {
            val relatedMerges = changes.filter(change => change.changeType == Replace && change.newLinks.head == newLink)
            val (created, failed) = transferFunctionalClassesAndLinkTypesForSingleReplace(relatedMerges, newLink, timeStamp)
            createdProperties ++= created
            incompleteLinks ++= failed
            iteratedNewLinks += newLink
          }
        case _ =>
          change.newLinks.foreach { newLink =>
            if (!(iteratedNewLinks.contains(newLink)) && KgvUtil.extractFeatureClass(newLink.roadClass) != WinterRoads) {
              val functionalClassChange = transferOrGenerateFunctionalClass(change.changeType, change.oldLink, newLink)
              val linkTypeChange = transferOrGenerateLinkType(change.changeType, change.oldLink, newLink)
              if (functionalClassChange.isEmpty || linkTypeChange.isEmpty) {
                incompleteLinks += IncompleteLink(newLink.linkId, newLink.municipality, newLink.adminClass)
              }
              createdProperties += functionalClassChange
              createdProperties += linkTypeChange
            }
          }
      }
    }
    val roadLinkData = roadLinkService.getExistingAndExpiredRoadLinksByLinkIds(incompleteLinks.map(_.linkId).toSet, false)
    val incompleteLinksInUse = incompleteLinks.filter(il => incompleteLinkIsInUse(il, roadLinkData))
    roadLinkService.updateIncompleteLinks(incompleteLinksInUse)
    createdProperties.flatten
  }

  /**
   * Transfers properties from old links to the new link. Each property is transfered exactly once, and if any property is missing from any old link, new link is deemed as incomplete.
   * @param relatedChanges all changes related to the merge
   * @param newLink
   * @param timeStamp
   * @return All created properties and possible incomplete link
   */
  def transferFunctionalClassesAndLinkTypesForSingleReplace(relatedMerges: Seq[RoadLinkChange], newLink: RoadLinkInfo, timeStamp: String): (ListBuffer[Option[ReportedChange]], ListBuffer[IncompleteLink]) = {
    val incompleteLink = new ListBuffer[IncompleteLink]()
    val createdProperties = new ListBuffer[Option[ReportedChange]]()
    val transferedFunctionalClasses = new ListBuffer[FunctionalClassChange]
    val transferedLinkTypes = new ListBuffer[LinkTypeChange]
    relatedMerges.foreach { merge =>
      if (incompleteLink.isEmpty) {
        val functionalClassChange = (transferedFunctionalClasses.isEmpty) match {
          case true => transferFunctionalClass(merge.changeType, merge.oldLink.get, newLink, timeStamp)
          case false => None
        }
        if (functionalClassChange.nonEmpty) {
          transferedFunctionalClasses += functionalClassChange.get
          createdProperties += functionalClassChange
        }
        val linkTypeChange = (transferedLinkTypes.isEmpty) match {
          case true => transferLinkType(merge.changeType, merge.oldLink.get, newLink, timeStamp)
          case false => None
        }
        if (linkTypeChange.nonEmpty) {
          transferedLinkTypes += linkTypeChange.get
          createdProperties += linkTypeChange
        }
        if (transferedFunctionalClasses.isEmpty || transferedLinkTypes.isEmpty) {
          incompleteLink += IncompleteLink(newLink.linkId, newLink.municipality, newLink.adminClass)
        }
      }
    }
    (createdProperties, incompleteLink)
  }

  def transferOverriddenPropertiesAndPrivateRoadInfo(changes: Seq[RoadLinkChange]): Seq[ReportedChange] = {
    def applyDigitizationChange(digitizationChange: Boolean, trafficDirectionValue: Int) = {
      digitizationChange match {
        case true =>
          TrafficDirection(trafficDirectionValue) match {
            case TowardsDigitizing => AgainstDigitizing.value
            case AgainstDigitizing => TowardsDigitizing.value
            case _ => trafficDirectionValue
          }
        case false =>
          trafficDirectionValue
      }
    }

    val transferredProperties = ListBuffer[ReportedChange]()
    changes.foreach { change =>
      val oldLink = change.oldLink.get
      val versionChange = oldLink.linkId.substring(0, 36) == change.newLinks.head.linkId.substring(0, 36)
      if (versionChange) {
        val optionalOverriddenTrafficDirection = TrafficDirectionDao.getExistingValue(oldLink.linkId)
        optionalOverriddenTrafficDirection match {
          case Some(overriddenTrafficDirection) =>
            change.newLinks.foreach { newLink =>
              val alreadyUpdatedValue = TrafficDirectionDao.getExistingValue(newLink.linkId)
              val digitizationChange = change.replaceInfo.find(_.newLinkId.get == newLink.linkId).get.digitizationChange
              val trafficDirectionWithDigitizationCheck = applyDigitizationChange(digitizationChange, overriddenTrafficDirection)
              if (trafficDirectionWithDigitizationCheck != newLink.trafficDirection.value && alreadyUpdatedValue.isEmpty) {
                RoadLinkOverrideDAO.insert(TrafficDirectionString, newLink.linkId, Some(AutoGeneratedUsername.automaticGeneration), trafficDirectionWithDigitizationCheck)
                transferredProperties += TrafficDirectionChange(newLink.linkId, roadLinkChangeToChangeType(change.changeType), overriddenTrafficDirection, Some(trafficDirectionWithDigitizationCheck))
              }
            }
          case _ => //do nothing
        }

        val optionalOverriddenAdminClass = AdministrativeClassDao.getExistingValue(oldLink.linkId)
        optionalOverriddenAdminClass match {
          case Some(overriddenAdminClass) =>
            change.newLinks.foreach { newLink =>
              val alreadyUpdatedValue = AdministrativeClassDao.getExistingValue(newLink.linkId)
              if (overriddenAdminClass != newLink.adminClass.value && alreadyUpdatedValue.isEmpty) {
                RoadLinkOverrideDAO.insert(AdministrativeClass, newLink.linkId, Some(AutoGeneratedUsername.automaticGeneration), overriddenAdminClass)
                transferredProperties += AdministrativeClassChange(newLink.linkId, roadLinkChangeToChangeType(change.changeType), overriddenAdminClass, Some(overriddenAdminClass))
              }
            }
          case _ => //do nothing
        }
      }

      val roadLinkAttributes = LinkAttributesDao.getExistingValues(oldLink.linkId)
      if (roadLinkAttributes.nonEmpty) {
        change.newLinks.foreach { newLink =>
          val alreadyUpdatedValues = LinkAttributesDao.getExistingValues(newLink.linkId)
          if (alreadyUpdatedValues.isEmpty) {
            roadLinkAttributes.foreach { attribute =>
              LinkAttributesDao.insertAttributeValueByChanges(newLink.linkId, AutoGeneratedUsername.automaticGeneration, attribute._1, attribute._2, LinearAssetUtils.createTimeStamp())
            }
            transferredProperties += RoadLinkAttributeChange(newLink.linkId, roadLinkChangeToChangeType(change.changeType), roadLinkAttributes, roadLinkAttributes)
          }
        }
      }
    }
    transferredProperties
  }

  def updateProperties(): Unit = {
    val latestSuccess = PostGISDatabase.withDynSession( Queries.getLatestSuccessfulSamuutus(RoadLinkProperties.typeId) )
    val changeSets = roadLinkChangeClient.getRoadLinkChanges(latestSuccess)

    changeSets.foreach(changeSet => {
      withDynTransaction {
        logger.info(s"Started processing change set ${changeSet.key}")
        val changes = changeSet.changes
        val (addChanges, remaining) = changes.partition(_.changeType == Add)
        val (_, otherChanges) = remaining.partition(_.changeType == Remove)

        val transferredProperties = transferOverriddenPropertiesAndPrivateRoadInfo(otherChanges)
        val createdProperties = transferOrGenerateFunctionalClassesAndLinkTypes(addChanges ++ otherChanges)
        val changeReport = ChangeReport(RoadLinkProperties.typeId, transferredProperties ++ createdProperties)
        val (reportBody, contentRowCount) = ChangeReporter.generateCSV(changeReport)
        ChangeReporter.saveReportToS3("roadLinkProperties", changeSet.targetDate, reportBody, contentRowCount)
        Queries.updateLatestSuccessfulSamuutus(RoadLinkProperties.typeId, changeSet.targetDate)
      }
    })
  }
}
