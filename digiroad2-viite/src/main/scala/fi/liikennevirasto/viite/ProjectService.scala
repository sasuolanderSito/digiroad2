package fi.liikennevirasto.viite

import fi.liikennevirasto.digiroad2.asset.{BoundingRectangle, SideCode}
import fi.liikennevirasto.digiroad2.linearasset.RoadLink
import fi.liikennevirasto.digiroad2.masstransitstop.oracle.Sequences
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.util.{RoadAddressException, Track}
import fi.liikennevirasto.digiroad2.{DigiroadEventBus, GeometryUtils, RoadLinkService, VVHRoadlink}
import fi.liikennevirasto.viite.dao.ProjectState._
import fi.liikennevirasto.viite.dao.{ProjectDAO, RoadAddressDAO, _}
import fi.liikennevirasto.viite.model.{ProjectAddressLink, RoadAddressLink, RoadAddressLinkLike}
import fi.liikennevirasto.viite.process._
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import org.slf4j.LoggerFactory

import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import scala.util.control.NonFatal
case class PreFillInfo(RoadNumber:BigInt, RoadPart:BigInt)

class ProjectService(roadAddressService: RoadAddressService, roadLinkService: RoadLinkService, eventbus: DigiroadEventBus) {
  def withDynTransaction[T](f: => T): T = OracleDatabase.withDynTransaction(f)

  def withDynSession[T](f: => T): T = OracleDatabase.withDynSession(f)

  val logger = LoggerFactory.getLogger(getClass)
  val allowedSideCodes = List(SideCode.TowardsDigitizing, SideCode.AgainstDigitizing)

  /**
    *
    * @param roadNumber    Road's number (long)
    * @param roadStartPart Starting part (long)
    * @param roadEndPart   Ending part (long)
    * @return Optional error message, None if no error
    */
  def checkRoadAddressNumberAndSEParts(roadNumber: Long, roadStartPart: Long, roadEndPart: Long): Option[String] = {
    withDynTransaction {
      if (!RoadAddressDAO.roadPartExists(roadNumber, roadStartPart)) {
        if (!RoadAddressDAO.roadNumberExists(roadNumber)) {
          Some("Tienumeroa ei ole olemassa, tarkista tiedot")
        }
        else //roadnumber exists, but starting roadpart not
          Some("Tiellä ei ole olemassa valittua alkuosaa, tarkista tiedot")
      } else if (!RoadAddressDAO.roadPartExists(roadNumber, roadEndPart)) { // ending part check
        Some("Tiellä ei ole olemassa valittua loppuosaa, tarkista tiedot")
      } else
        None
    }
  }

  /**
    * Checks that new road address is not already reserved (currently only checks roadaddresstable)
    *
    * @param roadNumber road number
    * @param roadPart road part number
    * @param project  roadaddress project needed for id and error message
    * @return
    */
  def checkNewRoadAddressNumberAndPart(roadNumber: Long, roadPart: Long, project :RoadAddressProject): Option[String] = {
    val projectLinks = RoadAddressDAO.isNewRoadPartUsed(roadNumber, roadPart, project.id)
    if (projectLinks.isEmpty) {
      None
    } else {
      val fmt = DateTimeFormat.forPattern("dd.MM.yyyy")
      Some(s"TIE $roadNumber OSA $roadPart on jo olemassa projektin alkupäivänä ${project.startDate.toString(fmt)}, tarkista tiedot") //message to user if address is already in use
    }
  }

  private def createNewProjectToDB(roadAddressProject: RoadAddressProject): RoadAddressProject = {
    val id = Sequences.nextViitePrimaryKeySeqValue
    val project = roadAddressProject.copy(id = id)
    ProjectDAO.createRoadAddressProject(project)
    project
  }

  private def projectFound(roadAddressProject: RoadAddressProject): Option[RoadAddressProject] = {
    val newRoadAddressProject=0
    if (roadAddressProject.id==newRoadAddressProject) return None
    withDynTransaction {
      return ProjectDAO.getRoadAddressProjectById(roadAddressProject.id)
    }
  }

  def fetchPrefillfromVVH(linkId: Long): Either[String,PreFillInfo] = {
    parsePrefillData(roadLinkService.fetchVVHRoadlinks(Set(linkId)))
  }

  def parsePrefillData(vvhRoadLinks:Seq[VVHRoadlink]): Either[String,PreFillInfo] = {
    if (vvhRoadLinks.isEmpty) {
      Left("Link could not be found in VVH")    }
    else {
      val vvhlink = vvhRoadLinks.head
      (vvhlink.attributes.get("ROADNUMBER"), vvhlink.attributes.get("ROADPARTNUMBER")) match {
        case (Some(roadnumber:BigInt), Some(roadpartnumber:BigInt)) => {
          return Right(PreFillInfo(roadnumber,roadpartnumber))
        }
        case _ => return Left("Link does not contain valid prefill info")
      }
    }
  }

  def checkReservability(roadNumber: Long, startPart: Long, endPart: Long): Either[String, Seq[ReservedRoadPart]] = {
    withDynTransaction {
      var listOfAddressParts: ListBuffer[ReservedRoadPart] = ListBuffer.empty
      for (part <- startPart to endPart) {
        val reserved = ProjectDAO.roadPartReservedByProject(roadNumber, part)
        reserved match {
          case Some(projectname) => return Left(s"TIE $roadNumber OSA $part on jo varattuna projektissa $projectname, tarkista tiedot")
          case None =>
            val (roadpartID, linkID, length, discontinuity, ely, startDate, endDate, foundAddress) = getAddressPartinfo(roadNumber, part)
            if (foundAddress) // db search failed or we couldnt get info from VVH
              listOfAddressParts += ReservedRoadPart(roadpartID, roadNumber, part, length, Discontinuity.apply(discontinuity), ely, startDate, endDate)
        }
      }
      Right(listOfAddressParts)
    }
  }

  def projDateValidation(reservedParts:Seq[ReservedRoadPart], projDate:DateTime): Option[String] = {
    reservedParts.foreach( part => {
      if(part.startDate.nonEmpty && part.startDate.get.isAfter(projDate)) return Option(s"Tieosalla TIE ${part.roadNumber} OSA ${part.roadPartNumber} alkupäivämäärä ${part.startDate.get.toString("dd.MM.yyyy")} on uudempi kuin tieosoiteprojektin alkupäivämäärä ${projDate.toString("dd.MM.yyyy")}, tarkista tiedot.")
      if(part.endDate.nonEmpty && part.endDate.get.isAfter(projDate)) return Option(s"Tieosalla TIE ${part.roadNumber} OSA ${part.roadPartNumber} loppupäivämäärä ${part.endDate.get.toString("dd.MM.yyyy")} on uudempi kuin tieosoiteprojektin alkupäivämäärä ${projDate.toString("dd.MM.yyyy")}, tarkista tiedot.")
    })
    None
  }

  private def getAddressPartinfo(roadnumber: Long, roadpart: Long): (Long, Long, Double, String, Long, Option[DateTime], Option[DateTime], Boolean) = {
    RoadAddressDAO.getRoadPartInfo(roadnumber, roadpart) match {
      case Some((roadpartid, linkid, lenght, discontinuity, startDate, endDate)) => {
        val enrichment = false
        val roadLink = roadLinkService.getRoadLinksByLinkIdsFromVVH(Set(linkid), enrichment)
        val ely: Option[Long] = roadLink.headOption.map(rl => MunicipalityDAO.getMunicipalityRoadMaintainers.getOrElse(rl.municipalityCode, 0))
        ely match {
          case Some(value) if ely.nonEmpty && ely.get != 0 => (roadpartid, linkid, lenght, Discontinuity.apply(discontinuity.toInt).description, value, Option(startDate), Option(endDate), true)
          case _ => (0, 0, 0, "", 0, None, None, false)
        }
      }
      case None =>
        (0, 0, 0, "", 0, None, None, false)
    }
  }

  /**
    * Used when adding road address that does not have previous address
    */
  def addNewLinksToProject(projectAddressLinks: Seq[ProjectAddressLink], roadAddressProjectID: Long, newRoadNumber: Long,
                           newRoadPartNumber: Long, newTrackCode: Long, newDiscontinuity: Long): Option[String] = {
    val linksInProject = getLinksByProjectLinkId(ProjectDAO.fetchByProjectNewRoadPart(newRoadNumber,newRoadPartNumber,roadAddressProjectID, false).map(l => l.linkId).toSet, roadAddressProjectID, false)
    //Deleting all existent roads for same road_number and road_part_number, in order to recalculate the full road if it is already in project
    if(linksInProject.nonEmpty){
      ProjectDAO.removeProjectLinksByProjectAndRoadNumber(roadAddressProjectID, newRoadNumber, newRoadPartNumber)
    }
    val randomSideCode = SideCode.TowardsDigitizing
    ProjectDAO.getRoadAddressProjectById(roadAddressProjectID) match {
      case Some(project) => {
        checkNewRoadAddressNumberAndPart(newRoadNumber, newRoadPartNumber, project) match {
          case Some(errorMessage) => Some(errorMessage)
          case None => {
            val reservedTo = ProjectDAO.roadPartReservedByProject(newRoadNumber, newRoadPartNumber, project.id, true)
            reservedTo match {
              case Some(projectName) =>
                Some(s"TIE $newRoadNumber OSA $newRoadPartNumber on jo varattuna projektissa $projectName, tarkista tiedot")
              case None =>
                val newProjectLinks = projectAddressLinks.map(projectLink => { projectLink.linkId ->
                  ProjectLink(NewRoadAddress, newRoadNumber, newRoadPartNumber, Track.apply(newTrackCode.toInt), Discontinuity.apply(newDiscontinuity.toInt), projectLink.startAddressM,
                    projectLink.endAddressM, Some(project.startDate), None, Some(project.createdBy), -1, projectLink.linkId, projectLink.startMValue, projectLink.endMValue, randomSideCode,
                    (projectLink.startCalibrationPoint, projectLink.endCalibrationPoint), false, projectLink.geometry, roadAddressProjectID, LinkStatus.New, projectLink.roadType, projectLink.roadLinkSource, projectLink.length)
                }).toMap
                val existingLinks = linksInProject.map(projectLink => { projectLink.linkId ->
                  ProjectLink(projectLink.id, newRoadNumber, newRoadPartNumber, Track.apply(projectLink.trackCode.toInt), Discontinuity.apply(newDiscontinuity.toInt), projectLink.startAddressM,
                    projectLink.endAddressM, Some(project.startDate), None, Some(project.createdBy), -1, projectLink.linkId, projectLink.startMValue, projectLink.endMValue, randomSideCode,
                    (projectLink.startCalibrationPoint, projectLink.endCalibrationPoint), false, projectLink.geometry, roadAddressProjectID, LinkStatus.New, projectLink.roadType, projectLink.roadLinkSource, projectLink.length)
                }).toMap
                val combinedLinks = (newProjectLinks.keySet ++ existingLinks.keySet).map(
                  linkId => newProjectLinks.getOrElse(linkId, existingLinks(linkId))
                )
                //Determine geometries for the mValues and addressMValues
                val linksWithMValues = ProjectSectionCalculator.determineMValues(combinedLinks.toSeq,
                  existingLinks.filterKeys(linkId => newProjectLinks.keySet.contains(linkId)).values.toSeq)
                ProjectDAO.removeProjectLinksByLinkId(roadAddressProjectID, newProjectLinks.keySet)
                ProjectDAO.create(linksWithMValues ++ combinedLinks.filterNot(link =>
                  linksWithMValues.exists(_.linkId == link.linkId)))
                None
            }
          }
        }
      }
      case None => Some("Projektikoodilla ei löytynyt projektia")
    }
  }

  def changeDirection(projectId : Long, roadNumber : Long, roadPartNumber : Long): Option[String] = {
    try {
      withDynTransaction {
        val projectLinkIds= ProjectDAO.projectLinksExist(projectId, roadNumber, roadPartNumber)
        if (!projectLinkIds.contains(projectLinkIds.head)){
         return Some("Linkit kuuluvat useampaan projektiin")
        }
        ProjectDAO.flipProjectLinksSideCodes(projectId, roadNumber, roadPartNumber)
        val projectLinks = ProjectDAO.getProjectLinks(projectId)
        val projectLinksWithFixedTopology = ProjectSectionCalculator.determineMValues(projectLinks, Seq.empty[ProjectLink])
        recalculateMValues(projectLinksWithFixedTopology).foreach(
          link => ProjectDAO.updateMValues(link))
        None
      }
    } catch{
      case NonFatal(e) =>
        Some("Päivitys ei onnistunut")
    }
  }

  //TODO VIITE-568
  /**
    * This will run through all the project links that are to be created and simply add calibration points to the links
    * whose track code change from one another.
    *
    * @param processed ProjectLinks that were run through
    * @param unprocessed ProjectLinks that still were not through
    * @return all the processed ProjectLinks with calibration points
    */
  private def addCalibrationPoints(processed: Seq[ProjectLink], unprocessed: Seq[ProjectLink]): Seq[ProjectLink] = {

    processed
  }

  /**
    * Adds reserved road links (from road parts) to a road address project. Reservability is check before this.
    *
    * @param project
    * @return
    */
  private def addLinksToProject(project: RoadAddressProject): Option[String] = {
    def toProjectLink(roadTypeMap: Map[Long, RoadType])(roadAddress: RoadAddress): ProjectLink = {
      ProjectLink(id=NewRoadAddress, roadAddress.roadNumber, roadAddress.roadPartNumber, roadAddress.track,
        roadAddress.discontinuity, roadAddress.startAddrMValue, roadAddress.endAddrMValue, roadAddress.startDate,
        roadAddress.endDate, modifiedBy=Option(project.createdBy), 0L, roadAddress.linkId, roadAddress.startMValue, roadAddress.endMValue,
        roadAddress.sideCode, roadAddress.calibrationPoints, floating=false, roadAddress.geometry, project.id,
        LinkStatus.NotHandled, roadTypeMap.getOrElse(roadAddress.linkId, RoadType.Unknown),roadAddress.linkGeomSource, GeometryUtils.geometryLength(roadAddress.geometry))
    }
    //TODO: Check that there are no floating road addresses present when starting
    val errors = project.reservedParts.map(roadAddress =>
      if (!RoadAddressDAO.roadPartExists(roadAddress.roadNumber, roadAddress.roadPartNumber)) {
        s"TIE ${roadAddress.roadNumber} OSA: ${roadAddress.roadPartNumber}"
      } else "").filterNot(_ == "")
    if (errors.nonEmpty)
      Some(s"Seuraavia tieosia ei löytynyt tietokannasta: ${errors.mkString(", ")}")
    else {
      val elyErrors = project.reservedParts.map(roadAddress =>
        if (project.ely.nonEmpty && roadAddress.ely != project.ely.get) {
          s"TIE ${roadAddress.roadNumber} OSA: ${roadAddress.roadPartNumber} (ELY != ${project.ely.get})"
        } else "").filterNot(_ == "")
      if (elyErrors.nonEmpty)
        return Some(s"Seuraavat tieosat ovat eri ELY-numerolla kuin projektin muut osat: ${errors.mkString(", ")}")
      val addresses = project.reservedParts.flatMap { roadaddress =>
        val addressesOnPart = RoadAddressDAO.fetchByRoadPart(roadaddress.roadNumber, roadaddress.roadPartNumber, false)
        val mapping = roadLinkService.getRoadLinksByLinkIdsFromVVH(addressesOnPart.map(_.linkId).toSet, false)
          .map(rl => rl.linkId -> RoadAddressLinkBuilder.getRoadType(rl.administrativeClass, rl.linkType)).toMap
        addressesOnPart.map(toProjectLink(mapping))
      }
      val ely = project.reservedParts.map(_.ely)
      if (ely.distinct.size > 1) {
        Some(s"Tieosat ovat eri ELYistä")
      } else {
        ProjectDAO.create(addresses)
        if (project.ely.isEmpty)
          ProjectDAO.updateProjectEly(project.id, ely.head)
        None
      }
    }
  }

  private def createFormOfReservedLinksToSavedRoadParts(project: RoadAddressProject): (Seq[ProjectFormLine], Option[ProjectLink]) = {
    val createdAddresses = ProjectDAO.getProjectLinks(project.id)
    val groupedAddresses = createdAddresses.groupBy { address =>
      (address.roadNumber, address.roadPartNumber)
    }.toSeq.sortBy(_._1._2)(Ordering[Long])
    val adddressestoform = groupedAddresses.map(addressGroup => {
      val lastAddressM = addressGroup._2.last.endAddrMValue
      val roadLink = roadLinkService.getRoadLinksByLinkIdsFromVVH(Set(addressGroup._2.last.linkId), false)
      val addressFormLine = ProjectFormLine(addressGroup._2.last.linkId, project.id, addressGroup._1._1,
        addressGroup._1._2, lastAddressM, MunicipalityDAO.getMunicipalityRoadMaintainers.getOrElse(roadLink.head.municipalityCode, -1),
        addressGroup._2.last.discontinuity.description)
      //TODO:case class RoadAddressProjectFormLine(projectId: Long, roadNumber: Long, roadPartNumber: Long, RoadLength: Long, ely : Long, discontinuity: String)
      addressFormLine
    })
    val addresses = createdAddresses.headOption
    (adddressestoform, addresses)
  }

  private def createNewRoadLinkProject(roadAddressProject: RoadAddressProject) = {
    withDynTransaction {
      val project = createNewProjectToDB(roadAddressProject)
      if (project.reservedParts.isEmpty) //check if new project has links
      {
        val (forminfo, createdlink) = createFormOfReservedLinksToSavedRoadParts(project)
        (project, None, forminfo, "ok")
      } else {
        //project with links success field contains errors if any, else "ok"
        val errorMessage = addLinksToProject(project)

        val (forminfo, createdlink) = createFormOfReservedLinksToSavedRoadParts(project)

        (project, createdlink, forminfo, errorMessage.getOrElse("ok"))
      }
    }
  }

  def saveRoadLinkProject(roadAddressProject: RoadAddressProject): (RoadAddressProject, Option[ProjectLink], Seq[ProjectFormLine], String) = {
    if (projectFound(roadAddressProject).isEmpty)
      throw new IllegalArgumentException("Project not found")
    withDynTransaction {
      if (roadAddressProject.reservedParts.isEmpty) { //roadaddresses to update is empty
        ProjectDAO.updateRoadAddressProject(roadAddressProject)
        val (forminfo, createdlink) = createFormOfReservedLinksToSavedRoadParts(roadAddressProject)
        (roadAddressProject, createdlink, forminfo, "ok")
      } else {
        //list contains road addresses that we need to add
        val errorMessage = addLinksToProject(roadAddressProject)
        if (errorMessage.isEmpty) {
          //adding links succeeeded
          ProjectDAO.updateRoadAddressProject(roadAddressProject)
          val (forminfo, createdlink) = createFormOfReservedLinksToSavedRoadParts(roadAddressProject)
          (roadAddressProject, createdlink, forminfo, "ok")
        } else {
          //adding links failed
          val (forminfo, createdlink) = createFormOfReservedLinksToSavedRoadParts(roadAddressProject)
          (roadAddressProject, createdlink, forminfo, errorMessage.get)
        }
      }
    }
  }

  def createRoadLinkProject(roadAddressProject: RoadAddressProject): (RoadAddressProject, Option[ProjectLink], Seq[ProjectFormLine], String) = {
    if (roadAddressProject.id != 0)
      throw new IllegalArgumentException(s"Road address project to create has an id ${roadAddressProject.id}")
    createNewRoadLinkProject(roadAddressProject)
  }

  def getRoadAddressSingleProject(projectId: Long): Seq[RoadAddressProject] = {
    withDynTransaction {
      ProjectDAO.getRoadAddressProjects(projectId)
    }
  }

  def getRoadAddressAllProjects(): Seq[RoadAddressProject] = {
    withDynTransaction {
      ProjectDAO.getRoadAddressProjects()
    }
  }

  def getProjectsWithReservedRoadParts(projectId: Long): (RoadAddressProject, Seq[ProjectFormLine]) = {
    withDynTransaction {
      val project:RoadAddressProject = ProjectDAO.getRoadAddressProjects(projectId).head
      val createdAddresses = ProjectDAO.getProjectLinks(project.id)
      val groupedAddresses = createdAddresses.groupBy { address =>
        (address.roadNumber, address.roadPartNumber)
      }.toSeq.sortBy(_._1._2)(Ordering[Long])
      val formInfo: Seq[ProjectFormLine] = groupedAddresses.map(addressGroup => {
        val endAddressM = addressGroup._2.last.endAddrMValue
        val roadLink = roadLinkService.getRoadLinksByLinkIdsFromVVH(Set(addressGroup._2.head.linkId), false)
        val addressFormLine = ProjectFormLine(addressGroup._2.head.linkId, project.id,
          addressGroup._2.head.roadNumber, addressGroup._2.head.roadPartNumber, endAddressM,
          MunicipalityDAO.getMunicipalityRoadMaintainers.getOrElse(roadLink.head.municipalityCode, -1),
          addressGroup._2.last.discontinuity.description)
        addressFormLine
      })

      (project, formInfo)
    }
  }

  def getProjectLinksWithSuravage(roadAddressService: RoadAddressService,projectId:Long, boundingRectangle: BoundingRectangle, roadNumberLimits: Seq[(Int, Int)], municipalities: Set[Int], everything: Boolean = false, publicRoads: Boolean=false): Seq[ProjectAddressLink] ={
    val combinedFuture=  for{
      fProjectLink <-  Future(getProjectRoadLinks(projectId, boundingRectangle, Seq(), Set(), everything = true))
      fSuravage <- Future(roadAddressService.getSuravageRoadLinkAddresses(boundingRectangle, Set()))
    } yield (fProjectLink, fSuravage)
    val (projectLinkList,suravageList) =Await.result(combinedFuture, Duration.Inf)
    roadAddressLinkToProjectAddressLink(suravageList) ++ projectLinkList
  }

  def getChangeProject(projectId:Long): Option[ChangeProject] = {
    val changeProjectData = withDynTransaction {
      try {
        val delta = ProjectDeltaCalculator.delta(projectId)
        //TODO would be better to give roadType to ProjectLinks table instead of calling vvh here
        val vvhRoadLinks = roadLinkService.getRoadLinksByLinkIdsFromVVH(delta.terminations.map(_.linkId).toSet, false)
        val filledTerminations = withFetchedDataFromVVH(delta.terminations, vvhRoadLinks, RoadType)

        if (setProjectDeltaToDB(delta.copy(terminations = filledTerminations), projectId)) {
          val roadAddressChanges = RoadAddressChangesDAO.fetchRoadAddressChanges(Set(projectId))
          Some(ViiteTierekisteriClient.convertToChangeProject(roadAddressChanges.sortBy(r => (r.changeInfo.source.trackCode, r.changeInfo.source.startAddressM, r.changeInfo.source.startRoadPartNumber, r.changeInfo.source.roadNumber))))
        } else {
          None
        }
      } catch {
        case NonFatal(e) =>
          logger.info(s"Change info not available for project $projectId: " + e.getMessage)
          None
      }
    }
    changeProjectData
  }

  def enrichTerminations(terminations: Seq[RoadAddress], roadlinks: Seq[RoadLink]): Seq[RoadAddress] = {
    val withRoadType = terminations.par.map{
      t =>
        val relatedRoadLink = roadlinks.filter(rl => rl.linkId == t.linkId).headOption
        relatedRoadLink match {
          case None => t
          case Some(rl) =>
            val roadType = RoadAddressLinkBuilder.getRoadType(rl.administrativeClass, rl.linkType)
            t.copy(roadType = roadType)
        }
    }
    withRoadType.toList
  }

  def getRoadAddressChangesAndSendToTR(projectId: Set[Long]) = {
    val roadAddressChanges = RoadAddressChangesDAO.fetchRoadAddressChanges(projectId)
    ViiteTierekisteriClient.sendChanges(roadAddressChanges)
  }

  def getProjectRoadLinksByLinkIds(linkIdsToGet : Set[Long], newTransaction : Boolean = true): Seq[ProjectAddressLink] = {

    if(linkIdsToGet.isEmpty)
      return Seq()

    val fetchVVHStartTime = System.currentTimeMillis()
    val complementedRoadLinks = roadLinkService.getRoadLinksByLinkIdsFromVVH(linkIdsToGet, newTransaction)
    val fetchVVHEndTime = System.currentTimeMillis()
    logger.info("End fetch vvh road links in %.3f sec".format((fetchVVHEndTime - fetchVVHStartTime) * 0.001))

    val projectRoadLinks = complementedRoadLinks
      .map { rl =>
        val ra = Seq()
        val missed =  Seq()
        rl.linkId -> roadAddressService.buildRoadAddressLink(rl, ra, missed)
      }.toMap

    val filledProjectLinks = RoadAddressFiller.fillTopology(complementedRoadLinks, projectRoadLinks)

    filledProjectLinks._1.map(toProjectAddressLink)

  }

  def getLinksByProjectLinkId(linkIdsToGet : Set[Long], projectId: Long, newTransaction : Boolean = true): Seq[ProjectAddressLink] = {

    if(linkIdsToGet.isEmpty)
      return Seq()

    val fetchVVHStartTime = System.currentTimeMillis()
    val complementedRoadLinks = roadLinkService.getRoadLinksByLinkIdsFromVVH(linkIdsToGet, newTransaction)
    val fetchVVHEndTime = System.currentTimeMillis()
    logger.info("End fetch vvh road links in %.3f sec".format((fetchVVHEndTime - fetchVVHStartTime) * 0.001))
    val fetchProjectLinks = ProjectDAO.getProjectLinks(projectId).groupBy(_.linkId)

    val projectRoadLinks = complementedRoadLinks.map {
      rl =>
        val pl = fetchProjectLinks.getOrElse(rl.linkId, Seq())
          rl.linkId -> buildProjectRoadLink(rl, pl)
    }.toMap.mapValues(_.get)

    RoadAddressFiller.fillProjectTopology(complementedRoadLinks, projectRoadLinks)
  }

  def getProjectRoadLinks(projectId: Long, boundingRectangle: BoundingRectangle, roadNumberLimits: Seq[(Int, Int)], municipalities: Set[Int],
                          everything: Boolean = false, publicRoads: Boolean = false): Seq[ProjectAddressLink] = {
    def complementaryLinkFilter(roadNumberLimits: Seq[(Int, Int)], municipalities: Set[Int],
                                everything: Boolean = false, publicRoads: Boolean = false)(roadAddressLink: RoadAddressLink) = {
      everything || publicRoads || roadNumberLimits.exists {
        case (start, stop) => roadAddressLink.roadNumber >= start && roadAddressLink.roadNumber <= stop
      }
    }

    val fetchRoadAddressesByBoundingBoxF = Future(withDynTransaction {
      val (floating, addresses) = RoadAddressDAO.fetchRoadAddressesByBoundingBox(boundingRectangle, fetchOnlyFloating = false).partition(_.floating)
      (floating.groupBy(_.linkId), addresses.groupBy(_.linkId))
    })
    val fetchProjectLinksF = Future(withDynTransaction {
      ProjectDAO.getProjectLinks(projectId).groupBy(_.linkId)
    })
    val fetchVVHStartTime = System.currentTimeMillis()
    val (complementedRoadLinks, complementaryLinkIds) = fetchRoadLinksWithComplementary(boundingRectangle, roadNumberLimits, municipalities, everything, publicRoads)
    val linkIds = complementedRoadLinks.map(_.linkId).toSet
    val fetchVVHEndTime = System.currentTimeMillis()
    logger.info("End fetch vvh road links in %.3f sec".format((fetchVVHEndTime - fetchVVHStartTime) * 0.001))

    val fetchMissingRoadAddressStartTime = System.currentTimeMillis()
    val ((floating, addresses), projectLinks) = Await.result(fetchRoadAddressesByBoundingBoxF.zip(fetchProjectLinksF), Duration.Inf)
    // TODO: When floating handling is enabled we need this - but ignoring the result otherwise here
    val missingLinkIds = linkIds -- floating.keySet -- addresses.keySet -- projectLinks.keySet

    val missedRL = withDynTransaction {
      RoadAddressDAO.getMissingRoadAddresses(missingLinkIds)
    }.groupBy(_.linkId)
    val fetchMissingRoadAddressEndTime = System.currentTimeMillis()
    logger.info("End fetch missing and floating road address in %.3f sec".format((fetchMissingRoadAddressEndTime - fetchMissingRoadAddressStartTime) * 0.001))

    val buildStartTime = System.currentTimeMillis()

    val projectRoadLinks = complementedRoadLinks.map {
      rl =>
        val pl = projectLinks.getOrElse(rl.linkId, Seq())
        rl.linkId -> buildProjectRoadLink(rl, pl)
    }.filterNot { case (_, optPAL) => optPAL.isEmpty}.toMap.mapValues(_.get)

    val filledProjectLinks = RoadAddressFiller.fillProjectTopology(complementedRoadLinks, projectRoadLinks)

    val nonProjectRoadLinks = complementedRoadLinks.filterNot(rl => projectRoadLinks.keySet.contains(rl.linkId))

    val viiteRoadLinks = nonProjectRoadLinks
      .map { rl =>
        val ra = addresses.getOrElse(rl.linkId, Seq())
        val missed = missedRL.getOrElse(rl.linkId, Seq())
        rl.linkId -> roadAddressService.buildRoadAddressLink(rl, ra, missed)
      }.toMap

    val buildEndTime = System.currentTimeMillis()
    logger.info("End building road address in %.3f sec".format((buildEndTime - buildStartTime) * 0.001))

    val (filledTopology, _) = RoadAddressFiller.fillTopology(nonProjectRoadLinks, viiteRoadLinks)

    val returningTopology = filledTopology.filter(link => !complementaryLinkIds.contains(link.linkId) ||
      complementaryLinkFilter(roadNumberLimits, municipalities, everything, publicRoads)(link))

    returningTopology.map(toProjectAddressLink) ++ filledProjectLinks

  }

  def roadAddressLinkToProjectAddressLink(roadAddresses: Seq[RoadAddressLink]): Seq[ProjectAddressLink]= {
    roadAddresses.map(toProjectAddressLink)
  }
  def updateProjectLinkStatus(projectId: Long, linkIds: Set[Long], linkStatus: LinkStatus, userName: String): Boolean = {
    withDynTransaction{
      val projectLinks = ProjectDAO.getProjectLinks(projectId)
      val changed = projectLinks.filter(pl => linkIds.contains(pl.linkId)).map(_.id).toSet
      ProjectDAO.updateProjectLinkStatus(changed, linkStatus, userName)
      try {
        val delta = ProjectDeltaCalculator.delta(projectId)
        setProjectDeltaToDB(delta,projectId)
        true
      } catch {
        case ex: RoadAddressException =>
          logger.info("Delta calculation not possible: " + ex.getMessage)
          false
      }
    }
  }

  def projectLinkPublishable(projectId: Long): Boolean = {
    // TODO: add other checks after transfers etc. are enabled
    withDynSession{
      ProjectDAO.getProjectLinks(projectId, Some(LinkStatus.NotHandled)).isEmpty
    }
  }

  /**
    * Publish project with id projectId
    *
    * @param projectId Project to publish
    * @return optional error message, empty if no error
    */
  def publishProject(projectId: Long): PublishResult = {
    // TODO: Check that project actually is finished: projectLinkPublishable(projectId)
    // TODO: Run post-change tests for the roads that have been edited and throw an exception to roll back if not acceptable
    withDynTransaction {
      try {
        val delta=ProjectDeltaCalculator.delta(projectId)
        if(!setProjectDeltaToDB(delta,projectId)) {return PublishResult(false, false, Some("Muutostaulun luonti epäonnistui. Tarkasta ely"))}
        val trProjectStateMessage = getRoadAddressChangesAndSendToTR(Set(projectId))
        trProjectStateMessage.status match {
          case it if 200 until 300 contains it => {
            setProjectStatusToSend2TR(projectId)
            PublishResult(true, true, Some(trProjectStateMessage.reason))
          }
          case _ => {
            //rollback
            PublishResult(true, false, Some(trProjectStateMessage.reason))
          }
        }
      } catch{
        case NonFatal(e) =>  PublishResult(false, false, None)
      }
    }
  }

  private def setProjectDeltaToDB(projectDelta:Delta, projectId:Long):Boolean= {
    RoadAddressChangesDAO.clearRoadChangeTable(projectId)
    val batchInsertionResult = RoadAddressChangesDAO.insertDeltaToRoadChangeTable(projectDelta, projectId)
    batchInsertionResult
  }

  private def toProjectAddressLink(ral: RoadAddressLinkLike): ProjectAddressLink = {
    ProjectAddressLink(ral.id, ral.linkId, ral.geometry, ral.length, ral.administrativeClass, ral.linkType, ral.roadLinkType,
      ral.constructionType, ral.roadLinkSource, ral.roadType, ral.roadName, ral.municipalityCode, ral.modifiedAt, ral.modifiedBy,
      ral.attributes, ral.roadNumber, ral.roadPartNumber, ral.trackCode, ral.elyCode, ral.discontinuity,
      ral.startAddressM, ral.endAddressM, ral.startMValue, ral.endMValue, ral.sideCode, ral.startCalibrationPoint, ral.endCalibrationPoint,
      ral.anomaly, ral.lrmPositionId, LinkStatus.Unknown)
  }

  private def buildProjectRoadLink(rl: RoadLink, projectLinks: Seq[ProjectLink]): Option[ProjectAddressLink] = {
    val pl = projectLinks.size match {
      case 0 => return None
      case 1 => projectLinks.head
      case _ => fuseProjectLinks(projectLinks)
    }

    Some(RoadAddressLinkBuilder.projectBuild(rl, pl))
  }

  private def fuseProjectLinks(links: Seq[ProjectLink]) = {
    val linkIds = links.map(_.linkId).distinct
    if (linkIds.size != 1)
      throw new IllegalArgumentException(s"Multiple road link ids given for building one link: ${linkIds.mkString(", ")}")
    val (startM, endM, startA, endA) = (links.map(_.startMValue).min, links.map(_.endMValue).max,
      links.map(_.startAddrMValue).min, links.map(_.endAddrMValue).max)
    links.head.copy(startMValue = startM, endMValue = endM, startAddrMValue = startA, endAddrMValue = endA)
  }

  private def fetchRoadLinksWithComplementary(boundingRectangle: BoundingRectangle, roadNumberLimits: Seq[(Int, Int)], municipalities: Set[Int],
                                              everything: Boolean = false, publicRoads: Boolean = false): (Seq[RoadLink], Set[Long]) = {
    val roadLinksF = Future(roadLinkService.getViiteRoadLinksFromVVH(boundingRectangle, roadNumberLimits, municipalities, everything, publicRoads))
    val complementaryLinksF = Future(roadLinkService.getComplementaryRoadLinksFromVVH(boundingRectangle, municipalities))
    val (roadLinks, complementaryLinks) = Await.result(roadLinksF.zip(complementaryLinksF), Duration.Inf)
    (roadLinks ++ complementaryLinks, complementaryLinks.map(_.linkId).toSet)
  }

  def getProjectStatusFromTR(projectId: Long) = {
    ViiteTierekisteriClient.getProjectStatus(projectId)
  }

  private def getStatusFromTRObject(trProject:Option[TRProjectStatus]):Option[ProjectState] = {
    trProject match {
      case Some(trPojectobject) => mapTRstateToViiteState(trPojectobject.status.getOrElse(""))
      case None => None
      case _ => None
    }
  }

  private def getTRErrorMessage(trProject:Option[TRProjectStatus]):String = {
    trProject match {
      case Some(trProjectobject) => trProjectobject.errorMessage.getOrElse("")
      case None => ""
      case _ => ""
    }
  }

  def setProjectStatusToSend2TR(projectId:Long) =
  {
    ProjectDAO.updateProjectStatus(projectId, ProjectState.Sent2TR,"")
  }

  def updateProjectStatusIfNeeded(currentStatus:ProjectState, newStatus:ProjectState, errorMessage:String,projectId:Long) :(ProjectState)= {
    if (currentStatus.value!=newStatus.value && newStatus != ProjectState.Unknown)
    {
      ProjectDAO.updateProjectStatus(projectId,newStatus,errorMessage)
    }
    if (newStatus != ProjectState.Unknown){
      newStatus
    } else
    {
      currentStatus
    }
  }

  private def getProjectsPendingInTR() :Seq[Long]= {
    withDynSession {
      ProjectDAO.getProjectsWithWaitingTRStatus()
    }
  }
  def updateProjectsWaitingResponseFromTR(): Unit =
  {
    val listOfPendingProjects=getProjectsPendingInTR()
    for(project<-listOfPendingProjects)
    {
      withDynSession {
        val status = checkProjectStatus(project)
        ProjectDAO.incrementCheckCounter(project, 1)
        status
      }
    }

  }

  private def checkProjectStatus(projectID: Long) =
  {
    val projectStatus=ProjectDAO.getProjectStatus(projectID)
    if (projectStatus.isDefined)
    {
      val currentState = projectStatus.getOrElse(ProjectState.Unknown)
      val trProjectState = ViiteTierekisteriClient.getProjectStatusObject(projectID)
      val newState = getStatusFromTRObject(trProjectState).getOrElse(ProjectState.Unknown)
      val errorMessage = getTRErrorMessage(trProjectState)
      val updatedStatus = updateProjectStatusIfNeeded(currentState,newState,errorMessage,projectID)
      updateRoadAddressWithProject(newState, projectID)
      updatedStatus
    }
  }

  private def mapTRstateToViiteState(trState:String): Option[ProjectState] ={

    trState match {
      case "S" => Some(ProjectState.apply(ProjectState.TRProcessing.value))
      case "K" => Some(ProjectState.apply(ProjectState.TRProcessing.value))
      case "T" => Some(ProjectState.apply(ProjectState.Saved2TR.value))
      case "V" => Some(ProjectState.apply(ProjectState.ErroredInTR.value))
      case "null" => Some(ProjectState.apply(ProjectState.ErroredInTR.value))
      case _=> None
    }
  }

  def updateRoadAddressWithProject(newState: ProjectState, projectID: Long): Seq[Long] = {
    if(newState == Saved2TR){
      val delta = ProjectDeltaCalculator.delta(projectID)
      val changes = RoadAddressChangesDAO.fetchRoadAddressChanges(Set(projectID))
      val newLinks = delta.terminations.map(terminated => terminated.copy(id = NewRoadAddress,
        endDate = Some(changes.head.projectStartDate)))
      //Expiring old addresses
      roadAddressService.expireRoadAddresses(delta.terminations.map(_.id).toSet)
      //Creating new addresses with the applicable changes
      val newRoads = RoadAddressDAO.create(newLinks, None)
      //Remove the ProjectLinks from PROJECT_LINK table?
      //      val projectLinks = ProjectDAO.getProjectLinks(projectID, Some(LinkStatus.Terminated))
      //      ProjectDAO.removeProjectLinksById(projectLinks.map(_.projectId).toSet)
      newRoads
    } else {
      throw new RuntimeException(s"Project state not at Saved2TR: $newState")
    }
  }

  def withFetchedDataFromVVH(roadAdddresses: Seq[RoadAddress], roadLinks: Seq[RoadLink], Type: Object): Seq[RoadAddress] = {
    val fetchedAddresses = Type match {
      case RoadType =>
        val withRoadType: Seq[RoadAddress] = roadAdddresses.par.map {
          ra =>
            val relatedRoadLink = roadLinks.filter(rl => rl.linkId == ra.linkId).headOption
            relatedRoadLink match {
              case None => ra
              case Some(rl) =>
                val roadType = RoadAddressLinkBuilder.getRoadType(rl.administrativeClass, rl.linkType)
                ra.copy(roadType = roadType)
            }
        }.toList
        withRoadType
    }
    fetchedAddresses
  }

  def recalculateMValues(projectLinks: Seq[ProjectLink]) ={
    var lastEndM = 0.0
    projectLinks.map(l => {
      val endValue = lastEndM + l.geometryLength
      val updatedProjectLink = l.copy(startMValue = 0.0, endMValue = l.geometryLength, startAddrMValue = Math.round(lastEndM), endAddrMValue = Math.round(endValue))
      lastEndM = endValue
      updatedProjectLink
    })

  }

  def setProjectEly(currentProjectId:Long, newEly: Long): Option[String] = {
    val currentProjectEly = getProjectEly(currentProjectId)
    if (currentProjectEly == -1) {
      ProjectDAO.updateProjectEly(currentProjectId, newEly)
      None
    } else if (currentProjectEly == newEly){
      logger.info("ProjectId: " + currentProjectId + " Ely is \"" + currentProjectEly + "\" no need to update")
      None
    } else {
      logger.error(s"The project can not handle multiple ELY areas (the project ELY range is ${currentProjectEly}). Recording was discarded.")
      Some(s"Projektissa ei voi käsitellä useita ELY-alueita (projektin ELY-alue on ${currentProjectEly}). Tallennus hylättiin.")
    }
  }

  def getProjectEly(projectId: Long): Long = {
      ProjectDAO.getProjectEly(projectId).get
  }

  case class PublishResult(validationSuccess: Boolean, sendSuccess: Boolean, errorMessage: Option[String])
}
