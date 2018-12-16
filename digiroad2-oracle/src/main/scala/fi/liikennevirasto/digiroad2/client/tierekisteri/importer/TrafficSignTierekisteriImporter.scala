package fi.liikennevirasto.digiroad2.client.tierekisteri.importer

import fi.liikennevirasto.digiroad2.{GeometryUtils, TrafficSignType, TrafficSignTypeGroup}
import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.client.tierekisteri.TierekisteriTrafficSignAssetClient
import fi.liikennevirasto.digiroad2.client.vvh.{VVHClient, VVHRoadlink}
import fi.liikennevirasto.digiroad2.dao.{RoadAddress => ViiteRoadAddress}
import fi.liikennevirasto.digiroad2.dao.pointasset.OracleTrafficSignDao
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.digiroad2.service.linearasset.{ManoeuvreCreationException, ManoeuvreProvider, ManoeuvreService}
import fi.liikennevirasto.digiroad2.service.pointasset.{AdditionalPanelInfo, IncomingTrafficSign, TrafficSignService}
import fi.liikennevirasto.digiroad2.util.Track
import org.apache.http.impl.client.HttpClientBuilder
import org.joda.time.DateTime

class TrafficSignTierekisteriImporter extends TierekisteriAssetImporterOperations {

  lazy val trafficSignService: TrafficSignService = new TrafficSignService(roadLinkService, userProvider, eventbus)
  lazy val manoeuvreService: ManoeuvreService = new ManoeuvreService(roadLinkService, eventbus)

  override def typeId: Int = 300
  override def assetName = "trafficSigns"
  override type TierekisteriClientType = TierekisteriTrafficSignAssetClient
  override def withDynSession[T](f: => T): T = OracleDatabase.withDynSession(f)
  override def withDynTransaction[T](f: => T): T = OracleDatabase.withDynTransaction(f)
  override val tierekisteriClient = new TierekisteriTrafficSignAssetClient(getProperty("digiroad2.tierekisteriRestApiEndPoint"),
    getProperty("digiroad2.tierekisteri.enabled").toBoolean,
    HttpClientBuilder.create().build())

  private val typePublicId = "trafficSigns_type"
  private val valuePublicId = "trafficSigns_value"
  private val infoPublicId = "trafficSigns_info"

  private val additionalInfoTypeGroups = Set(TrafficSignTypeGroup.GeneralWarningSigns, TrafficSignTypeGroup.ProhibitionsAndRestrictions, TrafficSignTypeGroup.AdditionalPanels)

  private def generateProperties(trAssetData: TierekisteriAssetData, additionalProperties: Set[AdditionalPanelInfo] = Set()) = {
    val trafficType = trAssetData.assetType
    val typeProperty = SimpleTrafficSignProperty(typePublicId, Seq(TextPropertyValue(trafficType.value.toString)))
    val valueProperty = additionalInfoTypeGroups.exists(group => group == trafficType.group) match {
      case true => SimpleTrafficSignProperty(infoPublicId, Seq(TextPropertyValue(trAssetData.assetValue)))
      case _ => SimpleTrafficSignProperty(valuePublicId, Seq(TextPropertyValue(trAssetData.assetValue)))
    }
    val additionalPanels = SimpleTrafficSignProperty(trafficSignService.additionalPublicId,
      additionalProperties.zipWithIndex.map{ case (panel, index) =>
        AdditionalPanel(panel.propertyData.find(p => p.publicId == trafficSignService.typePublicId).get.values.headOption.get.asInstanceOf[TextPropertyValue].propertyValue.toInt,
          panel.propertyData.find(p => p.publicId == trafficSignService.infoPublicId).get.asInstanceOf[TextPropertyValue].propertyValue,
          panel.propertyData.find(p => p.publicId == trafficSignService.valuePublicId).get.asInstanceOf[TextPropertyValue].propertyValue,
          index)
      }.toSeq)

    Set(typeProperty, valueProperty, additionalPanels)
  }

  protected override def getAllTierekisteriHistoryAddressSection(roadNumber: Long, lastExecution: DateTime) = {
    println("\nFetch " + assetName + " History by Road Number " + roadNumber)
    val trAsset = tierekisteriClient.fetchHistoryAssetData(roadNumber, Some(lastExecution)).filter(_.assetType != TrafficSignType.Unknown)

    trAsset.foreach { tr => println(s"TR: address ${tr.roadNumber}/${tr.startRoadPartNumber}-${tr.endRoadPartNumber}/${tr.track.value}/${tr.startAddressMValue}-${tr.endAddressMValue}") }
    trAsset.map(_.asInstanceOf[TierekisteriAssetData]).flatMap(getRoadAddressSections)
  }

  protected override def getAllTierekisteriAddressSections(roadNumber: Long) = {
    println("\nFetch Tierekisteri " + assetName + " by Road Number " + roadNumber)
    val trAsset = tierekisteriClient.fetchActiveAssetData(roadNumber).filter(_.assetType != Unknown)

    trAsset.foreach { tr => println(s"TR: address ${tr.roadNumber}/${tr.startRoadPartNumber}-${tr.endRoadPartNumber}/${tr.track.value}/${tr.startAddressMValue}-${tr.endAddressMValue}") }
    trAsset.map(_.asInstanceOf[TierekisteriAssetData]).flatMap(getRoadAddressSections)
  }

  protected override def getAllTierekisteriAddressSections(roadNumber: Long, roadPart: Long): Seq[(AddressSection, TierekisteriAssetData)] = {
    println("\nFetch Tierekisteri " + assetName + " by Road Number " + roadNumber)
    val trAsset = tierekisteriClient.fetchActiveAssetData(roadNumber, roadPart).filter(_.assetType != TrafficSignType.Unknown)

    trAsset.foreach { tr => println(s"TR: address ${tr.roadNumber}/${tr.startRoadPartNumber}-${tr.endRoadPartNumber}/${tr.track.value}/${tr.startAddressMValue}-${tr.endAddressMValue}") }
    trAsset.map(_.asInstanceOf[TierekisteriAssetData]).flatMap(getRoadAddressSections)
  }

  protected def createPointAsset(roadAddress: ViiteRoadAddress, vvhRoadlink: VVHRoadlink, mValue: Double, trAssetData: TierekisteriAssetData, properties: Set[AdditionalPanelInfo]): Unit = {
    //TODO this filter could remove and only exclude the Telematic
//    if(TrafficSignType.applyvalue(trAssetData.assetType.value).source.contains("TRimport"))
      GeometryUtils.calculatePointFromLinearReference(vvhRoadlink.geometry, mValue).map{
        point =>
          val trafficSign = IncomingTrafficSign(point.x, point.y, vvhRoadlink.linkId, generateProperties(trAssetData, properties),
            getSideCode(roadAddress, trAssetData.track, trAssetData.roadSide).value, Some(GeometryUtils.calculateBearing(vvhRoadlink.geometry)))

          val newId =  OracleTrafficSignDao.create(trafficSign, mValue, "batch_process_trafficSigns", vvhRoadlink.municipalityCode,
            VVHClient.createVVHTimeStamp(), vvhRoadlink.linkSource)

          roadLinkService.getRoadLinkAndComplementaryFromVVH(vvhRoadlink.linkId, newTransaction = false).map{
            link =>
              if (trafficSignService.belongsToTurnRestriction(trafficSign)) {
                println(s"Creating manoeuvre on linkId: ${vvhRoadlink.linkId} from import traffic sign with id $newId" )
                try {
                  manoeuvreService.createManoeuvreBasedOnTrafficSign(ManoeuvreProvider(trafficSignService.getPersistedAssetsByIdsWithoutTransaction(Set(newId)).head, link), newTransaction = false)
                }catch {
                  case e: ManoeuvreCreationException =>
                    println("Manoeuvre creation error: " + e.response.mkString(" "))
                }
              }
              newId
          }
      }
    println(s"Created OTH $assetName asset on link ${vvhRoadlink.linkId} from TR data")
  }

  protected override def expireAssets(linkIds: Seq[Long]): Unit = {
    val trafficSignsIds = assetDao.getAssetIdByLinks(typeId, linkIds)
    trafficSignsIds.foreach( sign => trafficSignService.expireAssetWithoutTransaction(sign, "batch_process_trafficSigns"))
  }

  private def getAdditionalPanels(trAdditionalData: Seq[(AddressSection, TierekisteriAssetData)], existingRoadAddresses: Map[(Long, Long, Track), Seq[ViiteRoadAddress]],  vvhRoadLinks: Seq[VVHRoadlink]): Seq[AdditionalPanelInfo] = {
    trAdditionalData.flatMap { case (section, properties) =>
      val roadAddressLink = filterRoadAddressBySection(existingRoadAddresses, section, vvhRoadLinks)

      roadAddressLink.flatMap { case (ra, roadlink) =>
        ra.addressMValueToLRM(section.startAddressMValue).map{
          mValue =>
            AdditionalPanelInfo(None, mValue, roadlink.get.linkId, generateProperties(properties), getSideCode(ra, properties.track, properties.roadSide).value)
        }
      }
    }
  }

  override def importAssets(): Unit = {
    //Expire all asset in state roads in all the municipalities
    val municipalities = getAllMunicipalities
//    municipalities.foreach { municipality =>
//      withDynTransaction{
//        expireAssets(municipality, Some(State))
//      }
//    }

    val roadNumbers = getAllViiteRoadNumbers

    roadNumbers.foreach {
      roadNumber =>
        //Fetch asset from Tierekisteri and then generates the sections foreach returned asset
        //For example if Tierekisteri returns
        //One asset with start part = 2, end part = 5, start address = 10, end address 20
        //We will generate the middle parts and return a AddressSection for each one
        val trAddressSections = getAllTierekisteriAddressSections(roadNumber)

        val (trProperties, trAssetsSections) = trAddressSections.partition(_._2.assetType.group == TrafficSignTypeGroup.AdditionalPanels)

        //Fetch all the existing road address from viite client
        //If in the future this process get slow we can start using the returned sections
        //from trAddressSections sequence so we reduce the amount returned
        val roadAddresses = roadAddressService.getAllByRoadNumber(roadNumber)
        val mappedRoadAddresses = roadAddresses.groupBy(ra => (ra.roadNumber, ra.roadPartNumber, ra.track))
        val mappedRoadLinks  = roadLinkService.fetchVVHRoadlinks(roadAddresses.map(ra => ra.linkId).toSet)

        val additionalProperties = getAdditionalPanels(trProperties, mappedRoadAddresses, mappedRoadLinks)

        //For each section creates a new OTH asset
        trAssetsSections.foreach {
          case (section, trAssetData) =>
            withDynTransaction {
              createAsset(section, trAssetData, mappedRoadAddresses, mappedRoadLinks, additionalProperties)
            }
        }
    }
  }

  protected def createAsset(section: AddressSection, trAssetData: TierekisteriAssetData, existingRoadAddresses: Map[(Long, Long, Track), Seq[ViiteRoadAddress]], vvhRoadLinks: Seq[VVHRoadlink], trAdditionalData: Seq[AdditionalPanelInfo]): Unit = {
    println(s"Fetch Road Addresses from Viite: R:${section.roadNumber} P:${section.roadPartNumber} T:${section.track.value} ADDRM:${section.startAddressMValue}-${section.endAddressMValue.map(_.toString).getOrElse("")}")

    //Returns all the match Viite road address for the given section
    val roadAddressLink = filterRoadAddressBySection(existingRoadAddresses, section, vvhRoadLinks)
    roadAddressLink
      .foreach { case (ra, roadlink) =>
        ra.addressMValueToLRM(section.startAddressMValue).foreach{
          mValue =>
            val sideCode = getSideCode(ra, trAssetData.track, trAssetData.roadSide).value
            val trafficSignType = trAssetData.assetType.value
            val allowedProperties = trafficSignService.getAdditionalPanels(ra.linkId, mValue, sideCode, trafficSignType, roadlink.get.geometry, trAdditionalData, vvhRoadLinks)
            createPointAsset(ra, roadlink.get, mValue, trAssetData, allowedProperties)
        }
      }
  }

  override protected def createAsset(section: AddressSection, trAssetData: TierekisteriAssetData, sectionRoadAddresses: Map[(Long, Long, Track), Seq[ViiteRoadAddress]], mappedRoadLinks: Seq[VVHRoadlink]): Unit = throw new UnsupportedOperationException("Not Supported Method")
}
