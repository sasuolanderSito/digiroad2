package fi.liikennevirasto.digiroad2.util.assetUpdater

import fi.liikennevirasto.digiroad2.asset.ConstructionType.InUse
import fi.liikennevirasto.digiroad2.asset.TrafficDirection.{AgainstDigitizing, TowardsDigitizing}
import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.client.RoadLinkChangeType.{Add, Remove, Replace, Split}
import fi.liikennevirasto.digiroad2.client._
import fi.liikennevirasto.digiroad2.dao.{Queries}
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

  /**
   * Transfers the functional class from an old link to a new link.
   *
   * @param changeType                The type of change being processed.
   * @param oldLink                   The old link from which the functional class is transferred.
   * @param newLink                   The new link to which the functional class is transferred.
   * @param timestamp                 The timestamp of the transfer operation.
   * @param existingFunctionalClasses A map containing existing functional classes for relevant links.
   * @return An Option containing the FunctionalClassChange, or None if the transfer was not possible.
   */
  private def transferFunctionalClass(changeType: RoadLinkChangeType, oldLink: RoadLinkInfo, newLink: RoadLinkInfo, existingFunctionalClasses: Map[String, Option[Int]]): Option[FunctionalClassChange] = {
    existingFunctionalClasses.get(oldLink.linkId).flatten match {
      case Some(functionalClass) =>
        Some(FunctionalClassChange(newLink.linkId, roadLinkChangeToChangeType(changeType), Some(functionalClass), Some(functionalClass), "oldLink", Some(oldLink.linkId)))
      case _ => None
    }
  }

  private def generateFunctionalClass(changeType: RoadLinkChangeType, newLink: RoadLinkInfo): Option[FunctionalClassChange] = {
    val featureClass = KgvUtil.extractFeatureClass(newLink.roadClass)
    featureClass match {
      case FeatureClass.TractorRoad => Some(FunctionalClassChange(newLink.linkId, roadLinkChangeToChangeType(changeType), None, Some(PrimitiveRoad.value), "mtkClass",None))
      case FeatureClass.HardShoulder => Some(FunctionalClassChange(newLink.linkId, roadLinkChangeToChangeType(changeType), None, Some(FunctionalClass9.value), "mtkClass",None))
      case FeatureClass.DrivePath | FeatureClass.CarRoad_IIIb => Some(FunctionalClassChange(newLink.linkId, roadLinkChangeToChangeType(changeType), None, Some(AnotherPrivateRoad.value), "mtkClass",None))
      case FeatureClass.CycleOrPedestrianPath => Some(FunctionalClassChange(newLink.linkId, roadLinkChangeToChangeType(changeType), None, Some(WalkingAndCyclingPath.value), "mtkClass",None))
      case FeatureClass.SpecialTransportWithoutGate => Some(FunctionalClassChange(newLink.linkId, roadLinkChangeToChangeType(changeType), None, Some(UnknownFunctionalClass.value), "mtkClass",None))
      case FeatureClass.SpecialTransportWithGate => Some(FunctionalClassChange(newLink.linkId, roadLinkChangeToChangeType(changeType), None, Some(UnknownFunctionalClass.value), "mtkClass",None))
      case FeatureClass.CarRoad_IIIa => newLink.adminClass match {
        case State => Some(FunctionalClassChange(newLink.linkId, roadLinkChangeToChangeType(changeType), None, Some(FunctionalClass4.value), "mtkClass",None))
        case Municipality | Private => Some(FunctionalClassChange(newLink.linkId, roadLinkChangeToChangeType(changeType), None, Some(FunctionalClass5.value), "mtkClass",None))
        case _ => None
      }
      case _ => None
    }
  }

  /**
   * Transfers the link type from an old link to a new link.
   *
   * @param changeType        The type of change being processed.
   * @param oldLink           The old link from which the link type is transferred.
   * @param newLink           The new link to which the link type is transferred.
   * @param timestamp         The timestamp of the transfer operation.
   * @param existingLinkTypes A map containing existing link types for relevant links.
   * @return An Option containing the LinkTypeChange, or None if the transfer was not possible.
   */
  private def transferLinkType(changeType: RoadLinkChangeType, oldLink: RoadLinkInfo, newLink: RoadLinkInfo, existingLinkTypes: Map[String, Option[Int]]): Option[LinkTypeChange] = {
    existingLinkTypes.get(oldLink.linkId).flatten match {
      case Some(linkType) =>
        Some(LinkTypeChange(newLink.linkId, roadLinkChangeToChangeType(changeType), Some(linkType), Some(linkType), "oldLink", Some(oldLink.linkId)))
      case _ =>
        None
    }
  }

  /**
   * Generates a new link type for a new link based on its feature class.
   *
   * @param changeType The type of change being processed.
   * @param newLink    The new link for which the link type is to be generated.
   * @return An Option containing the LinkTypeChange, or None if no valid feature class is found.
   */
  private def generateLinkType(changeType: RoadLinkChangeType, newLink: RoadLinkInfo): Option[LinkTypeChange] = {
    val featureClass = KgvUtil.extractFeatureClass(newLink.roadClass)

    val newLinkType = featureClass match {
      case FeatureClass.TractorRoad => TractorRoad.value
      case FeatureClass.HardShoulder => HardShoulder.value
      case FeatureClass.DrivePath | FeatureClass.CarRoad_IIIb => SingleCarriageway.value
      case FeatureClass.CycleOrPedestrianPath => CycleOrPedestrianPath.value
      case FeatureClass.SpecialTransportWithoutGate => SpecialTransportWithoutGate.value
      case FeatureClass.SpecialTransportWithGate => SpecialTransportWithGate.value
      case FeatureClass.CarRoad_IIIa => SingleCarriageway.value
      case _ => return None
    }

    Some(LinkTypeChange(newLink.linkId, roadLinkChangeToChangeType(changeType), None, Some(newLinkType), "mtkClass", None))
  }

  def incompleteLinkIsInUse(incompleteLink: IncompleteLink, roadLinkData: Seq[RoadLink]) = {
    val correspondingRoadLink = roadLinkData.find(_.linkId == incompleteLink.linkId)
    correspondingRoadLink match {
      case Some(roadLink) => roadLink.constructionType == InUse
      case _ => false
    }
  }

  /**
   * Transfers or generates a functional class for a new link depending on the existence of old and new link functional classes.
   *
   * @param changeType                The type of change being processed.
   * @param optionalOldLink           An optional old link from which the functional class might be transferred.
   * @param newLink                   The new link for which the functional class is to be set.
   * @param existingFunctionalClasses A map containing existing functional classes for relevant links.
   * @return An Option containing the FunctionalClassChange, or None if no change is made.
   */
  private def transferOrGenerateFunctionalClass(changeType: RoadLinkChangeType, optionalOldLink: Option[RoadLinkInfo], newLink: RoadLinkInfo, existingFunctionalClasses: Map[String, Option[Int]]): Option[FunctionalClassChange] = {
    optionalOldLink match {
      case Some(oldLink) =>
        transferFunctionalClass(changeType, oldLink, newLink, existingFunctionalClasses) match {
          case Some(functionalClassChange) => Some(functionalClassChange)
          case _ =>
            generateFunctionalClass(changeType, newLink) match {
              case Some(functionalClassChange) => Some(functionalClassChange)
              case _ => None
            }
        }
      case None =>
        generateFunctionalClass(changeType, newLink) match {
          case Some(generatedFunctionalClass) => Some(generatedFunctionalClass)
          case _ => None
        }
    }
  }

  /**
   * Transfers or generates a link type for a new link depending on the existence of old and new link types.
   *
   * @param changeType        The type of change being processed.
   * @param optionalOldLink   An optional old link from which the link type might be transferred.
   * @param newLink           The new link for which the link type is to be set.
   * @param existingLinkTypes A map containing existing link types for relevant links.
   * @return An Option containing the LinkTypeChange, or None if no change is made.
   */
  private def transferOrGenerateLinkType(changeType: RoadLinkChangeType, optionalOldLink: Option[RoadLinkInfo], newLink: RoadLinkInfo, existingLinkTypes: Map[String, Option[Int]]): Option[LinkTypeChange] = {
    optionalOldLink match {
      case (Some(oldLink)) =>
        transferLinkType(changeType, oldLink, newLink, existingLinkTypes) match {
          case Some(linkTypeChange) => Some(linkTypeChange)
          case _ =>
            generateLinkType(changeType, newLink) match {
              case Some(linkTypeChange) => Some(linkTypeChange)
              case _ => None
            }
        }
      case None =>
        generateLinkType(changeType, newLink) match {
          case Some(generatedLinkType) => Some(generatedLinkType)
          case _ => None
        }
    }
  }

  /***
   * Filters out links that need no property update processing due to already having Functional Class or Link type,
   * or having ignorable Feature Class
   * @param newLink
   * @return
   */
  def isProcessableLink(newLink: RoadLinkInfo, functionalClassMap: Map[String, Option[Int]], linkTypeMap: Map[String, Option[Int]]): Boolean = {
    val hasFunctionalClass = functionalClassMap.contains(newLink.linkId)
    val hasLinkType = linkTypeMap.contains(newLink.linkId)

    if (hasFunctionalClass) {
      logger.info(s"Functional Class already exists for new link ${newLink.linkId}")
    }
    if (hasLinkType) {
      logger.info(s"Link Type already exists for new link ${newLink.linkId}")
    }

    val featureClass = KgvUtil.extractFeatureClass(newLink.roadClass)
    val hasIgnoredFeatureClass = FeatureClass.featureClassesToIgnore.contains(featureClass)

    !hasFunctionalClass && !hasLinkType && !hasIgnoredFeatureClass
  }

  /**
   * Processes a sequence of road link changes to generate or transfer functional classes and link types.
   * Minimizes database calls by fetching existing values in bulk and processes changes in memory before doing bulk inserts.
   *
   * @param changes The sequence of RoadLinkChange instances to process.
   * @return A sequence of ReportedChange containing the generated or transferred properties, a sequence of Links where generation of transfer of properties failed
   */
  def transferOrGenerateFunctionalClassesAndLinkTypes(changes: Seq[RoadLinkChange],
                                                      existingFunctionalClasses: Map[String, Option[Int]],
                                                      existingLinkTypes: Map[String, Option[Int]]): (Seq[ReportedChange], Seq[IncompleteLink]) = {
    var iteratedNewLinks = Set[String]()
    var incompleteLinks = List[IncompleteLink]()
    var createdProperties = List[ReportedChange]()

    changes.foreach { change =>
      change.changeType match {
        case Replace =>
          val newLink = change.newLinks.head
          val featureClass = KgvUtil.extractFeatureClass(newLink.roadClass)
          if (!iteratedNewLinks.contains(newLink.linkId) && !FeatureClass.featureClassesToIgnore.contains(featureClass)){
            val relatedMerges = changes.filter(chg => chg.changeType == Replace && chg.newLinks.head.linkId == newLink.linkId)
            val (created, failed) = transferFunctionalClassAndLinkTypeForSingleReplace(relatedMerges, newLink, DateTime.now().toString, existingFunctionalClasses, existingLinkTypes)
            createdProperties = createdProperties ++ created.flatten
            incompleteLinks = incompleteLinks ++ failed
            iteratedNewLinks = iteratedNewLinks + newLink.linkId
          }
        case _ =>
          val processableNewLinks = change.newLinks.filter(newLink =>
            isProcessableLink(newLink, existingFunctionalClasses, existingLinkTypes) && !iteratedNewLinks.contains(newLink.linkId)
          )
          processableNewLinks.foreach { newLink =>
            val functionalClassChange = transferOrGenerateFunctionalClass(change.changeType, change.oldLink, newLink, existingFunctionalClasses)
            val linkTypeChange = transferOrGenerateLinkType(change.changeType, change.oldLink, newLink, existingLinkTypes)
            if (functionalClassChange.isEmpty || linkTypeChange.isEmpty) {
              incompleteLinks = incompleteLinks :+ IncompleteLink(newLink.linkId, newLink.municipality.getOrElse(throw new NoSuchElementException(s"${newLink.linkId} does not have municipality code")), newLink.adminClass)
            }
            createdProperties = createdProperties ++ functionalClassChange
            createdProperties = createdProperties ++ linkTypeChange
            iteratedNewLinks = iteratedNewLinks + newLink.linkId
          }
      }
    }

    (createdProperties.distinct, incompleteLinks)
  }

  /**
   * Transfers properties from old links to the new link for a given set of related road link changes.
   * Each property is transferred exactly once, and if any property is missing from any old link, the new link is deemed as incomplete.
   *
   * @param relatedMerges All changes related to the merge.
   * @param newLink The new link to which properties are transferred.
   * @param timestamp The timestamp of the transfer operation.
   * @param existingFunctionalClasses A map containing existing functional classes for relevant links.
   * @param existingLinkTypes A map containing existing link types for relevant links.
   * @return A tuple (List of created properties, List of incomplete links).
   */
  private def transferFunctionalClassAndLinkTypeForSingleReplace(relatedMerges: Seq[RoadLinkChange],
                                                             newLink: RoadLinkInfo,
                                                             timestamp: String,
                                                             existingFunctionalClasses: Map[String, Option[Int]],
                                                             existingLinkTypes: Map[String, Option[Int]]
                                                           ): (List[Option[ReportedChange]], List[IncompleteLink]) = {
    var incompleteLink = List[IncompleteLink]()
    var createdProperties = List[Option[ReportedChange]]()
    var functionalClassTransferred = false
    var linkTypeTransferred = false

    relatedMerges.foreach { merge =>
      if (incompleteLink.isEmpty) {
        if (!functionalClassTransferred) {
          transferFunctionalClass(merge.changeType, merge.oldLink.get, newLink, existingFunctionalClasses) match {
            case Some(functionalClassChange) =>
              createdProperties = createdProperties :+ Some(functionalClassChange)
              functionalClassTransferred = true
            case None =>
          }
        }

        if (!linkTypeTransferred) {
          transferLinkType(merge.changeType, merge.oldLink.get, newLink, existingLinkTypes) match {
            case Some(linkTypeChange) =>
              createdProperties = createdProperties :+ Some(linkTypeChange)
              linkTypeTransferred = true
            case None =>
          }
        }

        if (!functionalClassTransferred || !linkTypeTransferred) {
          incompleteLink = incompleteLink :+ IncompleteLink(newLink.linkId, newLink.municipality.getOrElse(throw new NoSuchElementException(s"${newLink.linkId} does not have municipality code")), newLink.adminClass)
        }
      }
    }

    (createdProperties, incompleteLink)
  }

  import scala.collection.mutable

  def transferOverriddenPropertiesAndPrivateRoadInfo(
                                                      changes: Seq[RoadLinkChange],
                                                      existingTrafficDirections: Map[String, Option[Int]],
                                                      existingAdministrativeClasses: Map[String, Option[Int]],
                                                      existingLinkAttributes: Map[String, Map[String, String]]
                                                    ): Seq[ReportedChange] = {
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
    val processedNewLinksTraffic = mutable.Set[String]()
    val processedNewLinksAdmin = mutable.Set[String]()
    val processedNewLinksAttributes = mutable.Set[String]()

    changes.foreach { change =>
      val oldLink = change.oldLink.get
      val versionChange = oldLink.linkId.substring(0, 36) == change.newLinks.head.linkId.substring(0, 36)
      if (versionChange) {
        val overriddenTrafficDirection = existingTrafficDirections.getOrElse(oldLink.linkId, None)
        overriddenTrafficDirection.foreach { direction =>
          change.newLinks.foreach { newLink =>
            if (!processedNewLinksTraffic.contains(newLink.linkId)) {
              val alreadyUpdatedValue = existingTrafficDirections.get(newLink.linkId).flatten
              val featureClass = KgvUtil.extractFeatureClass(newLink.roadClass)
              val digitizationChange = change.replaceInfo.find(_.newLinkId.get == newLink.linkId).get.digitizationChange
              val trafficDirectionWithDigitizationCheck = applyDigitizationChange(digitizationChange, direction)
              if (trafficDirectionWithDigitizationCheck != newLink.trafficDirection.value && alreadyUpdatedValue.isEmpty && !FeatureClass.featureClassesToIgnore.contains(featureClass)) {
                transferredProperties += TrafficDirectionChange(newLink.linkId, roadLinkChangeToChangeType(change.changeType), direction, Some(trafficDirectionWithDigitizationCheck), Some(oldLink.linkId))
                processedNewLinksTraffic += newLink.linkId
              }
            }
          }
        }

        val overriddenAdminClass = existingAdministrativeClasses.getOrElse(oldLink.linkId, None)
        overriddenAdminClass.foreach { adminClass =>
          change.newLinks.foreach { newLink =>
            if (!processedNewLinksAdmin.contains(newLink.linkId)) {
              val alreadyUpdatedValue = existingAdministrativeClasses.get(newLink.linkId).flatten
              val featureClass = KgvUtil.extractFeatureClass(newLink.roadClass)
              if (adminClass != newLink.adminClass.value && alreadyUpdatedValue.isEmpty && !FeatureClass.featureClassesToIgnore.contains(featureClass)) {
                transferredProperties += AdministrativeClassChange(newLink.linkId, roadLinkChangeToChangeType(change.changeType), adminClass, Some(adminClass), Some(oldLink.linkId))
                processedNewLinksAdmin += newLink.linkId
              }
            }
          }
        }
      }

      val roadLinkAttributes = existingLinkAttributes.getOrElse(oldLink.linkId, Map())
      if (roadLinkAttributes.nonEmpty) {
        change.newLinks.foreach { newLink =>
          if (!processedNewLinksAttributes.contains(newLink.linkId)) {
            val alreadyUpdatedValues = existingLinkAttributes.getOrElse(newLink.linkId, Map())
            val featureClass = KgvUtil.extractFeatureClass(newLink.roadClass)
            if (alreadyUpdatedValues.isEmpty && !FeatureClass.featureClassesToIgnore.contains(featureClass)) {
              transferredProperties += RoadLinkAttributeChange(newLink.linkId, roadLinkChangeToChangeType(change.changeType), roadLinkAttributes, roadLinkAttributes, Some(oldLink.linkId))
              processedNewLinksAttributes += newLink.linkId
            }
          }
        }
      }
    }
    transferredProperties.distinct
  }


  def updateProperties(): Unit = {
    val latestSuccess = PostGISDatabase.withDynSession( Queries.getLatestSuccessfulSamuutus(RoadLinkProperties.typeId) )
    val changeSets = roadLinkChangeClient.getRoadLinkChanges(latestSuccess)

    changeSets.foreach(changeSet => {withDynTransaction {
      logger.info(s"Started processing change set ${changeSet.key}")
      val changeReport = runProcess(changeSet.changes)
      val (reportBody, contentRowCount) = ChangeReporter.generateCSV(changeReport)
      ChangeReporter.saveReportToS3("roadLinkProperties", changeSet.targetDate, reportBody, contentRowCount)
      Queries.updateLatestSuccessfulSamuutus(RoadLinkProperties.typeId, changeSet.targetDate)
    }
    
    })
  }

  def performIncompleteLinkUpdate(incompleteLinks: Seq[IncompleteLink]) = {
    val roadLinkData = roadLinkService.getExistingAndExpiredRoadLinksByLinkIds(incompleteLinks.map(_.linkId).toSet, false)
    val incompleteLinksInUse = incompleteLinks.filter(il => incompleteLinkIsInUse(il, roadLinkData))
    roadLinkService.updateIncompleteLinks(incompleteLinksInUse)
  }

  def runProcess(changes: Seq[RoadLinkChange]): ChangeReport = {
    val (addChanges, removeChanges, otherChanges) = partitionChanges(changes)
    val allLinkIds = getAllLinkIds(changes)

    val (functionalClassMap, linkTypeMap, trafficDirectionMap, adminClassMap, linkAttributeMap) = roadLinkService.getRoadLinkValuesMass(allLinkIds)

    val removeReports = createReportsForPropertiesToBeDeleted(removeChanges, trafficDirectionMap, adminClassMap, functionalClassMap, linkTypeMap, linkAttributeMap)
    val transferredProperties = transferOverriddenPropertiesAndPrivateRoadInfo(otherChanges, trafficDirectionMap, adminClassMap, linkAttributeMap)
    val (createdProperties, incompleteLinks) = transferOrGenerateFunctionalClassesAndLinkTypes(addChanges ++ otherChanges, functionalClassMap, linkTypeMap)

    val (oldLinkList, newLinkList) = compileOldAndNewLinkLists(addChanges ++ otherChanges)

    val functionalClassChanges = accumulateFunctionalClassChanges(createdProperties)
    val linkTypeChanges = accumulateLinkTypeChanges(createdProperties)
    val trafficDirectionChanges = accumulateTrafficDirectionChanges(transferredProperties)
    val administrativeClassChanges = accumulateAdministrativeClassChanges(transferredProperties)
    val linkAttributeChanges = accumulateLinkAttributeChanges(transferredProperties)

    roadLinkService.insertRoadLinkValuesMass(functionalClassChanges, linkTypeChanges, trafficDirectionChanges, administrativeClassChanges, linkAttributeChanges)
    performIncompleteLinkUpdate(incompleteLinks)

    val finalChanges = transferredProperties ++ createdProperties ++ removeReports
    val constructionTypeChanges = finalizeConstructionTypeChanges(finalChanges, oldLinkList, newLinkList)

    ChangeReport(RoadLinkProperties.typeId, finalChanges ++ constructionTypeChanges)
  }

  private def partitionChanges(changes: Seq[RoadLinkChange]): (Seq[RoadLinkChange], Seq[RoadLinkChange], Seq[RoadLinkChange]) = {
    val (addChanges, remaining) = changes.partition(_.changeType == Add)
    val (removeChanges, otherChanges) = remaining.partition(_.changeType == Remove)
    (addChanges, removeChanges, otherChanges)
  }

  private def getAllLinkIds(changes: Seq[RoadLinkChange]): Seq[String] = {
    changes.flatMap(change => change.newLinks.map(_.linkId) ++ change.oldLink.map(_.linkId)).toSet.toSeq
  }

  private def compileOldAndNewLinkLists(changes: Seq[RoadLinkChange]): (ListBuffer[RoadLinkInfo], ListBuffer[RoadLinkInfo]) = {
    val oldLinkList = ListBuffer[RoadLinkInfo]()
    val newLinkList = ListBuffer[RoadLinkInfo]()
    changes.foreach { change =>
      newLinkList ++= change.newLinks
      oldLinkList ++= change.oldLink.toSeq
    }
    (oldLinkList, newLinkList)
  }

  private def accumulateFunctionalClassChanges(createdProperties: Seq[ReportedChange]): Map[String, Int] = {
    createdProperties.collect { case f: FunctionalClassChange => f.linkId -> f.newValue.get }.toMap
  }

  private def accumulateLinkTypeChanges(createdProperties: Seq[ReportedChange]): Map[String, Int] = {
    createdProperties.collect { case l: LinkTypeChange => l.linkId -> l.newValue.get }.toMap
  }

  private def accumulateTrafficDirectionChanges(transferredProperties: Seq[ReportedChange]): Map[String, Int] = {
    transferredProperties.collect { case t: TrafficDirectionChange => t.linkId -> t.newValue.get }.toMap
  }

  private def accumulateAdministrativeClassChanges(transferredProperties: Seq[ReportedChange]): Map[String, Int] = {
    transferredProperties.collect { case a: AdministrativeClassChange => a.linkId -> a.newValue.get }.toMap
  }

  private def accumulateLinkAttributeChanges(transferredProperties: Seq[ReportedChange]): Map[String, Map[String, String]] = {
    transferredProperties.collect {
      case r: RoadLinkAttributeChange =>
        r.linkId -> r.newValues.map { case (key, value) => key -> value }
    }.toMap
  }

  private def finalizeConstructionTypeChanges(
                                               changes: Seq[ReportedChange],
                                               oldLinkList: ListBuffer[RoadLinkInfo],
                                               newLinkList: ListBuffer[RoadLinkInfo]
                                             ): Seq[ConstructionTypeChange] = {
    changes.collect {
      case AdministrativeClassChange(newLinkId, _, _, _, oldLinkId) => addConstructionTypeChange(oldLinkList, newLinkList, newLinkId, oldLinkId)
      case TrafficDirectionChange(newLinkId, _, _, _, oldLinkId) => addConstructionTypeChange(oldLinkList, newLinkList, newLinkId, oldLinkId)
      case RoadLinkAttributeChange(newLinkId, _, _, _, oldLinkId) => addConstructionTypeChange(oldLinkList, newLinkList, newLinkId, oldLinkId)
      case FunctionalClassChange(newLinkId, _, _, _, _, oldLinkId) => addConstructionTypeChange(oldLinkList, newLinkList, newLinkId, oldLinkId)
      case LinkTypeChange(newLinkId, _, _, _, _, oldLinkId) => addConstructionTypeChange(oldLinkList, newLinkList, newLinkId, oldLinkId)
    }.flatten.distinct
  }

  private def addConstructionTypeChange(oldLinkList: ListBuffer[RoadLinkInfo], newLinkList:ListBuffer[RoadLinkInfo], newLinkId: String, oldLinkId: Option[String]): Option[ConstructionTypeChange] = {
    val oldLink = oldLinkList.find(_.linkId == oldLinkId.getOrElse(""))
    val newLink = newLinkList.find(_.linkId == newLinkId)
    val lifeCycleStatusOld = if (oldLink.nonEmpty) Some(oldLink.get.lifeCycleStatus) else None
    val lifeCycleStatusNew = if (newLink.nonEmpty) Some(newLink.get.lifeCycleStatus) else None
    Some(ConstructionTypeChange(newLinkId, ChangeTypeReport.Dummy, lifeCycleStatusOld, lifeCycleStatusNew))
  }
  /**
    *  Create ReportedChange objects for removed road link properties.
    *  Expired road links and their properties are only deleted later after all samuutus-batches have completed
    *  and the expired links have no assets left on them
    *
   * @param removeChanges                 RoadLinkChanges with changeType Remove.
   * @param existingTrafficDirections     Existing traffic direction values mapped by link ID.
   * @param existingAdministrativeClasses Existing administrative class values mapped by link ID.
   * @param existingFunctionalClasses     Existing functional class values mapped by link ID.
   * @param existingLinkTypes             Existing link type values mapped by link ID.
   * @param existingLinkAttributes        Existing link attributes mapped by link ID.
   * @return Created ReportedChange objects for the report.
   */
  def createReportsForPropertiesToBeDeleted(
                                             removeChanges: Seq[RoadLinkChange],
                                             existingTrafficDirections: Map[String, Option[Int]],
                                             existingAdministrativeClasses: Map[String, Option[Int]],
                                             existingFunctionalClasses: Map[String, Option[Int]],
                                             existingLinkTypes: Map[String, Option[Int]],
                                             existingLinkAttributes: Map[String, Map[String, String]]
                                           ): Seq[ReportedChange] = {
    val groupedChanges = removeChanges.groupBy(_.oldLink.get.linkId)
    val oldLinkIds = groupedChanges.keys.toSet

    val filteredTrafficDirections = existingTrafficDirections.filterKeys(oldLinkIds.contains)
    val filteredAdministrativeClasses = existingAdministrativeClasses.filterKeys(oldLinkIds.contains)
    val filteredFunctionalClasses = existingFunctionalClasses.filterKeys(oldLinkIds.contains)
    val filteredLinkTypes = existingLinkTypes.filterKeys(oldLinkIds.contains)
    val filteredLinkAttributes = existingLinkAttributes.filterKeys(oldLinkIds.contains)

    val deletedTrafficDirections = filteredTrafficDirections.map { case (linkId, value) =>
      TrafficDirectionChange(linkId, roadLinkChangeToChangeType(groupedChanges(linkId).head.changeType), value.get, None, linkIdOld = Some(linkId))
    }
    val deletedAdministrativeClasses = filteredAdministrativeClasses.map { case (linkId, value) =>
      AdministrativeClassChange(linkId, roadLinkChangeToChangeType(groupedChanges(linkId).head.changeType), value.get, None, linkIdOld = Some(linkId))
    }
    val deletedFunctionalClasses = filteredFunctionalClasses.map { case (linkId, value) =>
      FunctionalClassChange(linkId, roadLinkChangeToChangeType(groupedChanges(linkId).head.changeType), value, None, linkIdOld = Some(linkId))
    }
    val deletedLinkTypes = filteredLinkTypes.map { case (linkId, value) =>
      LinkTypeChange(linkId, roadLinkChangeToChangeType(groupedChanges(linkId).head.changeType), value, None, linkIdOld = Some(linkId))
    }
    val deletedAttributes = filteredLinkAttributes.map { case (linkId, oldAttributes) =>
      RoadLinkAttributeChange(linkId, roadLinkChangeToChangeType(groupedChanges(linkId).head.changeType), oldAttributes, Map(), linkIdOld = Some(linkId))
    }
    (deletedTrafficDirections ++ deletedAdministrativeClasses ++ deletedFunctionalClasses ++ deletedLinkTypes ++ deletedAttributes).toSeq.distinct

  }

}
