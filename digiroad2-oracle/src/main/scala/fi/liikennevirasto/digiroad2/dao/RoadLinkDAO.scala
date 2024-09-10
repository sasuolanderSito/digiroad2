package fi.liikennevirasto.digiroad2.dao

import slick.driver.JdbcDriver.backend.Database
import Database.dynamicSession
import org.locationtech.jts.geom.Polygon
import fi.liikennevirasto.digiroad2.{Geometry, Point}
import fi.liikennevirasto.digiroad2.asset.{AdministrativeClass, BoundingRectangle, ConstructionType, FunctionalClass, LinkGeomSource, LinkType, TrafficDirection, UnknownFunctionalClass, UnknownLinkType}
import fi.liikennevirasto.digiroad2.client.{EnrichedRoadLinkFetched, FeatureClass, LinkIdAndExpiredDate, RoadLinkFetched}
import fi.liikennevirasto.digiroad2.linearasset.RoadLink
import fi.liikennevirasto.digiroad2.postgis.PostGISDatabase
import fi.liikennevirasto.digiroad2.postgis.PostGISDatabase.withDbConnection
import fi.liikennevirasto.digiroad2.util.{KgvUtil, LogUtils}
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import org.slf4j.LoggerFactory
import slick.jdbc.{GetResult, PositionedResult}
import slick.jdbc.StaticQuery.interpolation

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import java.sql.{Array => SqlArray}
import org.postgresql.util.PGobject

class RoadLinkDAO {
  protected val geometryColumn: String = "shape"

  val logger = LoggerFactory.getLogger(getClass)

  // Query filters methods
  protected def withFilter[T](attributeName: String, ids: Set[T]): String = {
    if (ids.nonEmpty) {
      attributeName match {
        case "LINKID" => s"$attributeName in (${ids.asInstanceOf[Set[String]].map(t=>s"'$t'").mkString(",")})"
        case _ => s"$attributeName in (${ids.mkString(",")})"
      }
    } else ""
  }

  protected def withRoadNameFilter(attributeName: String, names: Set[String]): String = {
    if (names.nonEmpty) {
      val nameString = names.map(name =>
      {
        "[\']".r.findFirstMatchIn(name) match {
          case Some(_) => s"'${name.replaceAll("\'","\'\'")}'"
          case None => s"'$name'"
        }
      })
      s"$attributeName in (${nameString.mkString(",")})"
    } else ""
  }

  protected def withLimitFilter(attributeName: String, low: Int, high: Int,
                                          includeAllPublicRoads: Boolean = false): String = {
    if (low < 0 || high < 0 || low > high) {
      ""
    } else {
      if (includeAllPublicRoads) {
        s"ADMINCLASS = 1 OR $attributeName >= $low and $attributeName <= $high)"
      } else {
        s"( $attributeName >= $low and $attributeName <= $high )"
      }
    }
  }

  protected def withRoadNumberFilter(roadNumbers: (Int, Int), includeAllPublicRoads: Boolean): String = {
    withLimitFilter("ROADNUMBER", roadNumbers._1, roadNumbers._2, includeAllPublicRoads)
  }

  protected def withLinkIdFilter(linkIds: Set[String]): String = {
    withFilter("LINKID", linkIds)
  }

  protected def withFinNameFilter(roadNameSource: String)(roadNames: Set[String]): String = {
    withRoadNameFilter(roadNameSource, roadNames)
  }

  protected def withMmlIdFilter(mmlIds: Set[Long]): String = {
    withFilter("MTKID", mmlIds)
  }

  protected def withMtkClassFilter(ids: Set[Long]): String = {
    withFilter("MTKCLASS", ids)
  }

  protected  def withLastEditedDateFilter(lowerDate: DateTime, higherDate: DateTime): String = {
    withDateLimitFilter("LAST_EDITED_DATE", lowerDate, higherDate)
  }

  protected def withDateLimitFilter(attributeName: String, lowerDate: DateTime, higherDate: DateTime): String = {
    val formatter = DateTimeFormat.forPattern("yyyy-MM-dd")
    val since = formatter.print(lowerDate)
    val until = formatter.print(higherDate)

    s"( $attributeName >= date '$since' and $attributeName <=date '$until' )"
  }


  protected def withRoadNumbersFilter(roadNumbers: Seq[(Int, Int)], includeAllPublicRoads: Boolean, filter: String = ""): String = {
    if (roadNumbers.isEmpty) return s"($filter)"
    if (includeAllPublicRoads)
      return withRoadNumbersFilter(roadNumbers, false, "ADMINCLASS = 1")
    val limit = roadNumbers.head
    val filterAdd = s"(ROADNUMBER >= ${limit._1} and ROADNUMBER <= ${limit._2})"
    if (filter == "")
      withRoadNumbersFilter(roadNumbers.tail, includeAllPublicRoads, filterAdd)
    else
      withRoadNumbersFilter(roadNumbers.tail, includeAllPublicRoads, s"""$filter OR $filterAdd""")
  }

  protected def combineFiltersWithAnd(filter1: String, filter2: String): String = {
    (filter1.isEmpty, filter2.isEmpty) match {
      case (true,true) => ""
      case (true,false) => filter2
      case (false,true) => filter1
      case (false,false) => s"$filter1 AND $filter2"
    }
  }
  
  protected def combineFiltersWithAnd(filter1: String, filter2: Option[String]): String = {
    combineFiltersWithAnd(filter2.getOrElse(""), filter1)
  }

  implicit val getLinkIdAndExpiredDate: GetResult[LinkIdAndExpiredDate] = new GetResult[LinkIdAndExpiredDate] {
    def apply(r: PositionedResult): LinkIdAndExpiredDate = {
      val linkId = r.nextString()
      val expiredDate = r.nextTimestamp()
      LinkIdAndExpiredDate(linkId, new DateTime(expiredDate))
    }
  }

  implicit val getRoadLink: GetResult[RoadLinkFetched] = new GetResult[RoadLinkFetched] {
    def apply(r: PositionedResult): RoadLinkFetched = {
      val linkId = r.nextString()
      val mtkId = r.nextLong()
      val mtkHereFlip = r.nextInt()
      val municipality = r.nextInt()
      val path = r.nextObjectOption().map(KgvUtil.extractGeometry).get
      val administrativeClass = r.nextInt()
      val directionType = r.nextIntOption()
      val mtkClass = r.nextInt()
      val roadNameFi = r.nextStringOption()
      val roadNameSe = r.nextStringOption()
      val roadNameSme = r.nextStringOption()
      val roadNameSmn = r.nextStringOption()
      val roadNameSms = r.nextStringOption()
      val roadNumber = r.nextLongOption()
      val roadPart = r.nextIntOption()
      val constructionType = r.nextInt()
      val verticalLevel = r.nextInt()
      val horizontalAccuracy = r.nextBigDecimalOption()
      val verticalAccuracy = r.nextBigDecimalOption()
      val createdDate = r.nextTimestampOption().map(new DateTime(_))
      val lastEditedDate = r.nextTimestampOption().map(new DateTime(_))
      val fromLeft = r.nextLongOption()
      val toLeft = r.nextLongOption()
      val fromRight = r.nextLongOption()
      val toRight = r.nextLongOption()
      val surfaceType = r.nextInt()
      val length  = r.nextDouble()

      val geometry = path.map(point => Point(point(0), point(1), point(2)))
      val geometryForApi = path.map(point => Map("x" -> point(0), "y" -> point(1), "z" -> point(2), "m" -> point(3)))
      val geometryWKT = "LINESTRING ZM (" + path.map(point => s"${point(0)} ${point(1)} ${point(2)} ${point(3)}").mkString(", ") + ")"
      val featureClass = extractFeatureClass(mtkClass)
      val modifiedAt = extractModifiedDate(createdDate.map(_.getMillis), lastEditedDate.map(_.getMillis))

      val attributes = Map(
        "MTKID" -> mtkId,
        "MTKCLASS" -> mtkClass,
        "HORIZONTALACCURACY" -> horizontalAccuracy,
        "VERTICALACCURACY" -> verticalAccuracy,
        "VERTICALLEVEL" -> BigInt(verticalLevel),
        "CONSTRUCTIONTYPE" -> constructionType,
        "ROADNAME_FI" -> roadNameFi,
        "ROADNAME_SE" -> roadNameSe,
        "ROADNAMESME" -> roadNameSme,
        "ROADNAMESMN" -> roadNameSmn,
        "ROADNAMESMS" -> roadNameSms,
        "ROADNUMBER" -> roadNumber,
        "ROADPARTNUMBER" -> roadPart,
        "FROM_LEFT" -> fromLeft,
        "TO_LEFT" -> toLeft,
        "FROM_RIGHT" -> fromRight,
        "TO_RIGHT" -> toRight,
        "MUNICIPALITYCODE" -> BigInt(municipality),
        "MTKHEREFLIP" -> mtkHereFlip,
        "CREATED_DATE" -> createdDate.map(time => BigInt(time.toDateTime.getMillis)).getOrElse(None),
        "LAST_EDITED_DATE" -> lastEditedDate.map(time => BigInt(time.toDateTime.getMillis)).getOrElse(None),
        "SURFACETYPE" -> BigInt(surfaceType),
        "points" -> geometryForApi,
        "geometryWKT" -> geometryWKT
      ).collect {
        case (key, Some(value)) => key -> value
        case (key, value) if value != None => key -> value
      }

      RoadLinkFetched(linkId, municipality, geometry, AdministrativeClass.apply(administrativeClass),
        KgvUtil.extractTrafficDirection(directionType), featureClass, modifiedAt, attributes,
        ConstructionType.apply(constructionType), LinkGeomSource.NormalLinkInterface, length)
    }
  }

  /**
    * Returns road links from db that have been changed between given time period.
    */
  def fetchByChangesDates(lowerDate: DateTime, higherDate: DateTime): Seq[RoadLinkFetched] = {
    withDbConnection {
      getLinksWithFilter(withLastEditedDateFilter(lowerDate, higherDate))
    }
  }
  
  /**
    * Returns road link by mml id.
    * Used by RoadLinkService.getRoadLinkMiddlePointByMmlId
    */
  def fetchByMmlId(mmlId: Long): Option[RoadLinkFetched] = fetchByMmlIds(Set(mmlId)).headOption

  /**
    * Returns road links by mml ids.
    * Used by RoadLinkService.fetchByMmlIds.
    */
  def fetchByMmlIds(mmlIds: Set[Long]): Seq[RoadLinkFetched] = {
    getByMultipleValues(mmlIds, withMmlIdFilter)
  }

  /**
    * Returns road links by Finnish road names.
    * Used by RoadLinkService road link fetch functions.
    */
  def fetchByRoadNames(roadNamePublicId: String, roadNames: Set[String]): Seq[RoadLinkFetched] = {
    getByMultipleValues(roadNames, withFinNameFilter(roadNamePublicId))
  }

  /**
    * Returns road link by link id.
    * Used by RoadLinkService road link fetch functions.
    */
  def fetchByLinkId(linkId: String): Option[RoadLinkFetched] = fetchByLinkIds(Set(linkId)).headOption

  /**
    * Returns road links by link id.
    * Used by RoadLinkService road link fetch functions.
  */
  def fetchByLinkIds(linkIds: Set[String]): Seq[RoadLinkFetched] = {
    getByMultipleValues(linkIds, withLinkIdFilter)
  }

  def fetchByLinkIdsF(linkIds: Set[String]) = {
    Future(fetchByLinkIds(linkIds))
  }

  def fetchByRoadNamesF(roadNamePublicIds: String, roadNameSource: Set[String]) = {
    Future(fetchByRoadNames(roadNamePublicIds, roadNameSource))
  }


  def fetchByPolygon(polygon : Polygon): Seq[RoadLinkFetched] = {
    withDbConnection {getByPolygon(polygon)}
  }

  def fetchByPolygonF(polygon : Polygon): Future[Seq[RoadLinkFetched]] = {
    Future(fetchByPolygon(polygon))
  }

  def fetchLinkIdsByPolygonF(polygon : Polygon): Future[Seq[String]] = {
    Future(getLinksIdByPolygons(polygon))
  }

  def fetchLinkIdsByPolygon(polygon : Polygon): Seq[String] = {
   getLinksIdByPolygons(polygon)
  }

  def fetchByMunicipality(municipality: Int): Seq[RoadLinkFetched] = {
    getByMunicipality(municipality)
  }

  def fetchByMunicipalityF(municipality: Int): Future[Seq[RoadLinkFetched]] = {
    Future(getByMunicipality(municipality))
  }

  /**
    * Returns road links.
    * Used by RoadLinkService and AssetDataImporter.
    */
  def fetchByMunicipalitiesAndBounds(bounds: BoundingRectangle, municipalities: Set[Int]): Seq[RoadLinkFetched] = {
    getByMunicipalitiesAndBounds(bounds, municipalities,None)
  }

  def fetchByBounds(bounds: BoundingRectangle): Seq[RoadLinkFetched] = {
    getByMunicipalitiesAndBounds(bounds, Set[Int](),None)
  }

  /**
    * Returns road links. Uses Scala Future for concurrent operations.
    */
  def fetchByMunicipalitiesAndBoundsF(bounds: BoundingRectangle, municipalities: Set[Int]): Future[Seq[RoadLinkFetched]] = {
    Future(getByMunicipalitiesAndBounds(bounds, municipalities,None))
  }

  def fetchWalkwaysByMunicipalities(municipality:Int): Seq[RoadLinkFetched] = {
    getByMunicipality(municipality, Some(withMtkClassFilter(Set(12314))))
  }
  def fetchWalkwaysByMunicipalitiesF(municipality: Int): Future[Seq[RoadLinkFetched]] =
    Future(getByMunicipality(municipality, Some(withMtkClassFilter(Set(12314)))))

  def fetchWalkwaysByBoundsAndMunicipalitiesF(bounds: BoundingRectangle, municipalities: Set[Int]): Future[Seq[RoadLinkFetched]] = {
    Future(getByMunicipalitiesAndBounds(bounds, municipalities, Some(withMtkClassFilter(Set(12314)))))
  }

  def fetchWalkwaysByBoundsAndMunicipalities(bounds: BoundingRectangle, municipalities: Set[Int]): Seq[RoadLinkFetched] = {
    getByMunicipalitiesAndBounds(bounds, municipalities, Some(withMtkClassFilter(Set(12314))))
  }

  def fetchExpiredRoadLinks(): Seq[RoadLinkFetched] = {
    getExpiredRoadLinks()
  }
  def fetchExpiredRoadLink(linkId: String): Seq[RoadLinkFetched] = {
    fetchExpiredByLinkIds(Set(linkId))
  }

  def fetchExpiredByLinkIds(linkIds: Set[String]): Seq[RoadLinkFetched] = {
    getExpiredByMultipleValues(linkIds, withLinkIdFilter)
  }
  
  /**
    * Calls db operation to fetch roadlinks with given filter.
    */
  private def getByMultipleValues[T, A](values: Set[A],
                                        filter: Set[A] => String): Seq[T] = {
    if (values.nonEmpty) getLinksWithFilter(filter(values)).asInstanceOf[Seq[T]]
    else Seq.empty[T]
  }

  /**
    * Calls db operation to fetch expired road links with given filter.
    */
  private def getExpiredByMultipleValues[T, A](values: Set[A],
                                        filter: Set[A] => String): Seq[T] = {
    if (values.nonEmpty) getExpiredLinksWithFilter(filter(values)).asInstanceOf[Seq[T]]
    else Seq.empty[T]
  }

  protected def getLinksWithFilter(filter: String): Seq[RoadLinkFetched] = {
    val constructionFilter = Seq(ConstructionType.Planned.value, ConstructionType.UnderConstruction.value).mkString(", ")
    LogUtils.time(logger,"TEST LOG Getting roadlinks" ){
      sql"""select linkid, mtkid, mtkhereflip, municipalitycode, shape, adminclass, directiontype, mtkclass, roadname_fi,
                 roadname_se, roadnamesme, roadnamesmn, roadnamesms, roadnumber, roadpartnumber, constructiontype, verticallevel, horizontalaccuracy,
                 verticalaccuracy, created_date, last_edited_date, from_left, to_left, from_right, to_right,
                 surfacetype, geometrylength
          from kgv_roadlink
          where #$filter
          and expired_date is null
          and constructiontype not in (#$constructionFilter)
          """.as[RoadLinkFetched].list
    }
  }

  def fetchEnrichedByMunicipalitiesAndBounds(bounds: BoundingRectangle, municipalities: Set[Int]) = {
    val links = getEnrichedRoadLinksWithFilter(bounds, municipalities)
    links.map {link =>

      RoadLink(link.linkId, link.geometry,
        link.length,
        link.administrativeClass,
        link.functionalClass.value,
        link.trafficDirection,
        link.linkType,
        None,
        None, link.attributes)
    }
  }

  protected def getEnrichedRoadLinksWithFilter(bounds: BoundingRectangle, municipalities: Set[Int]): Seq[EnrichedRoadLinkFetched] = {
    val bboxFilter = PostGISDatabase.boundingBoxFilter(bounds, "shape")
    val municipalitiesFilter = if (municipalities.nonEmpty) s"AND municipalitycode IN (${municipalities.mkString(",")})" else ""
    val constructionFilter = Seq(ConstructionType.Planned.value, ConstructionType.UnderConstruction.value).mkString(", ")
    val featureClassFilter = FeatureClass.featureClassesToIgnore
    val query =
      sql"""
          WITH cte_kgv_roadlink AS (
          SELECT
            linkid, mtkid, mtkhereflip, municipalitycode, shape, adminclass, directiontype,
            mtkclass, roadname_fi, roadname_se, roadnamesme, roadnamesmn, roadnamesms,
            roadnumber, roadpartnumber, constructiontype, verticallevel, horizontalaccuracy,
            verticalaccuracy, created_date, last_edited_date, from_left, to_left, from_right, to_right,
            surfacetype, geometrylength
          FROM kgv_roadlink kgv
          WHERE
            #$bboxFilter
            #$municipalitiesFilter
            AND kgv.expired_date IS NULL
            AND kgv.constructiontype NOT IN (#$constructionFilter)
            AND kgv.mtkclass NOT IN (12318, 12312) -- Filter out HardShoulder and WinterRoad links
        )

          SELECT
           cte.linkid, cte.mtkid, cte.mtkhereflip, cte.municipalitycode, cte.shape,
           COALESCE(ac.administrative_class, cte.adminclass) AS adminclass,
           cte.directiontype,
           td.traffic_direction,
           cte.mtkClass,
           fc.functional_class,
           lt.link_type,
           cte.roadname_fi, cte.roadname_se, cte.roadnamesme, cte.roadnamesmn, cte.roadnamesms, cte.roadnumber,
           cte.roadpartnumber, cte.constructiontype, cte.verticallevel, cte.horizontalaccuracy, cte.verticalaccuracy,
           cte.created_date, cte.last_edited_date, cte.from_left, cte.to_left, cte.from_right, cte.to_right,
           cte.surfacetype, cte.geometrylength,
           ARRAY_AGG(ROW(ral.name, ral.value)) FILTER (WHERE ral.name IS NOT NULL) AS attributes
         FROM cte_kgv_roadlink cte
         LEFT JOIN administrative_class ac ON cte.linkid = ac.link_id AND (ac.valid_to IS NULL OR ac.valid_to > current_timestamp)
         LEFT JOIN link_type lt ON cte.linkid = lt.link_id
         LEFT JOIN functional_class fc ON cte.linkid = fc.link_id
         LEFT JOIN traffic_direction td ON cte.linkid = td.link_id
         LEFT JOIN road_link_attributes ral ON cte.linkid = ral.link_id AND ral.valid_to IS NULL
         GROUP BY
           cte.linkid, cte.mtkid, cte.mtkhereflip, cte.municipalitycode, cte.shape,
           COALESCE(ac.administrative_class, cte.adminclass),
           cte.directiontype,
           td.traffic_direction,
           cte.mtkClass,
           fc.functional_class,
           lt.link_type,
           cte.roadname_fi, cte.roadname_se, cte.roadnamesme, cte.roadnamesmn, cte.roadnamesms, cte.roadnumber, cte.roadpartnumber,
           cte.constructiontype, cte.verticallevel, cte.horizontalaccuracy, cte.verticalaccuracy,
           cte.created_date, cte.last_edited_date,
           cte.from_left, cte.to_left, cte.from_right, cte.to_right,
           cte.surfacetype, cte.geometrylength
      """
        query.as[EnrichedRoadLinkFetched].list
  }

  implicit val getEnrichedRoadLink = new GetResult[EnrichedRoadLinkFetched] {
    def apply(r: PositionedResult): EnrichedRoadLinkFetched = {
      val linkId = r.nextString()
      val mtkId = r.nextLong()
      val mtkHereFlip = r.nextInt()
      val municipalityCode = r.nextInt()
      val path = r.nextObjectOption().map(KgvUtil.extractGeometry).get
      val administrativeClassValue = r.nextInt()
      val directionType = r.nextIntOption()
      val trafficDirection = r.nextIntOption()
      val mtkClass = r.nextInt()
      val functionalClassValue = r.nextIntOption()
      val linkTypeValue = r.nextIntOption()
      val roadNameFi = r.nextStringOption()
      val roadNameSe = r.nextStringOption()
      val roadNameSme = r.nextStringOption()
      val roadNameSmn = r.nextStringOption()
      val roadNameSms = r.nextStringOption()
      val roadNumber = r.nextLongOption()
      val roadPart = r.nextIntOption()
      val constructionTypeValue = r.nextInt()
      val verticalLevel = r.nextInt()
      val horizontalAccuracy = r.nextBigDecimalOption()
      val verticalAccuracy = r.nextBigDecimalOption()
      val createdDate = r.nextTimestampOption().map(new DateTime(_))
      val lastEditedDate = r.nextTimestampOption().map(new DateTime(_))
      val fromLeft = r.nextLongOption()
      val toLeft = r.nextLongOption()
      val fromRight = r.nextLongOption()
      val toRight = r.nextLongOption()
      val surfaceType = r.nextInt()
      val length  = r.nextDouble()
      val attributesArray = r.nextObjectOption()

      val attributesSeq: Seq[(Option[String], Option[String])] = attributesArray match {
        case Some(sqlArray: SqlArray) =>
          // Extract the array as an array of objects
          val array = sqlArray.getArray.asInstanceOf[Array[AnyRef]]
          // Handle each PGobject
          array.map {
            case pgObject: PGobject =>
              val value = pgObject.getValue.stripPrefix("(").stripSuffix(")").split(",", -1)
              (Option(value(0)).filter(_.nonEmpty), Option(value(1)).filter(_.nonEmpty))
            case other =>
              throw new IllegalArgumentException(s"Unhandled case: $other")
          }.toSeq
        case _ => Seq.empty
      }

      // Debugging: Print intermediate values
      println(s"linkId: $linkId")
      println(s"mtkId: $mtkId")
      println(s"attributesArray: $attributesArray")

      val geometry = path.map(point => Point(point(0), point(1), point(2)))
      val administrativeClass = AdministrativeClass.apply(administrativeClassValue)
      val constructionType = ConstructionType.apply(constructionTypeValue)
      val functionalClass = functionalClassValue.map(FunctionalClass.apply).getOrElse(UnknownFunctionalClass)
      val linkType = linkTypeValue.map(LinkType.apply).getOrElse(UnknownLinkType)
      val adjustedTrafficDirection = trafficDirection.map(TrafficDirection.apply).getOrElse(KgvUtil.extractTrafficDirection(directionType))
      val geometryForApi = path.map(point => Map("x" -> point(0), "y" -> point(1), "z" -> point(2), "m" -> point(3)))
      val geometryWKT = "LINESTRING ZM (" + path.map(point => s"${point(0)} ${point(1)} ${point(2)} ${point(3)}").mkString(", ") + ")"
      val featureClass = extractFeatureClass(mtkClass)
      val modifiedAt = extractModifiedDate(createdDate.map(_.getMillis), lastEditedDate.map(_.getMillis))

      // Debugging: Print attributes sequence
      println(s"attributesSeq: $attributesSeq")

      val attributes = attributesSeq.collect {
        case (Some(name), Some(value)) => name -> value
      }.toMap ++ Map(
        "MTKID" -> mtkId,
        "MTKCLASS" -> mtkClass,
        "HORIZONTALACCURACY" -> horizontalAccuracy,
        "VERTICALACCURACY" -> verticalAccuracy,
        "VERTICALLEVEL" -> BigInt(verticalLevel),
        "ROADNAME_FI" -> roadNameFi,
        "ROADNAME_SE" -> roadNameSe,
        "ROADNAMESME" -> roadNameSme,
        "ROADNAMESMN" -> roadNameSmn,
        "ROADNAMESMS" -> roadNameSms,
        "ROADNUMBER" -> roadNumber,
        "ROADPARTNUMBER" -> roadPart,
        "FROM_LEFT" -> fromLeft,
        "TO_LEFT" -> toLeft,
        "FROM_RIGHT" -> fromRight,
        "TO_RIGHT" -> toRight,
        "MUNICIPALITYCODE" -> BigInt(municipalityCode),
        "MTKHEREFLIP" -> mtkHereFlip,
        "CREATED_DATE" -> createdDate.map(time => BigInt(time.toDateTime.getMillis)).getOrElse(None),
        "LAST_EDITED_DATE" -> lastEditedDate.map(time => BigInt(time.toDateTime.getMillis)).getOrElse(None),
        "SURFACETYPE" -> BigInt(surfaceType),
        "points" -> geometryForApi,
        "geometryWKT" -> geometryWKT
      ).collect {
        case (key, Some(value)) => key -> value
        case (key, value) if value != None => key -> value
      }

      EnrichedRoadLinkFetched(linkId = linkId, municipalityCode = municipalityCode, geometry = geometry,
        administrativeClass = administrativeClass, trafficDirection = adjustedTrafficDirection, linkType = linkType,
        functionalClass = functionalClass, featureClass = featureClass, modifiedAt = modifiedAt,
        attributes = attributes, constructionType = constructionType, LinkGeomSource.NormalLinkInterface, length = length
      )
    }
  }


  protected def deleteLinksWithFilter(filter: String): Unit = {
    LogUtils.time(logger, "TEST LOG Delete road links"){
      sqlu"""delete from kgv_roadlink
           where #$filter
         """.execute
    }
  }

  protected def getExpiredRoadLinks(): Seq[RoadLinkFetched] = {
    sql"""select linkid, mtkid, mtkhereflip, municipalitycode, shape, adminclass, directiontype, mtkclass, roadname_fi,
                 roadname_se, roadnamesme, roadnamesmn, roadnamesms, roadnumber, roadpartnumber, constructiontype, verticallevel, horizontalaccuracy,
                 verticalaccuracy, created_date, last_edited_date, from_left, to_left, from_right, to_right,
                 surfacetype, geometrylength
          from kgv_roadlink
          where expired_date is not null
          """.as[RoadLinkFetched].list
  }

  protected def getRoadLinkExpiredDateWithFilter(filter: String): Seq[LinkIdAndExpiredDate] = {
    sql"""select linkid, expired_date
          from kgv_roadlink
          where #$filter
       """.as[LinkIdAndExpiredDate].list
  }

  protected def getExpiredLinksWithFilter(filter: String): Seq[RoadLinkFetched] = {
    sql"""select linkid, mtkid, mtkhereflip, municipalitycode, shape, adminclass, directiontype, mtkclass, roadname_fi,
                 roadname_se, roadnamesme, roadnamesmn, roadnamesms, roadnumber, roadpartnumber, constructiontype, verticallevel, horizontalaccuracy,
                 verticalaccuracy, created_date, last_edited_date, from_left, to_left, from_right, to_right,
                 surfacetype, geometrylength
          from kgv_roadlink
          where expired_date is not null and #$filter
          """.as[RoadLinkFetched].list
  }

  private def getByMunicipalitiesAndBounds(bounds: BoundingRectangle, municipalities: Set[Int],
                                                 filter: Option[String]): Seq[RoadLinkFetched] = {
    val bboxFilter = PostGISDatabase.boundingBoxFilter(bounds, geometryColumn)
    val withFilter = (municipalities.nonEmpty, filter.nonEmpty) match {
      case (true, true) => s"and municipalitycode in (${municipalities.mkString(",")}) and ${filter.get}"
      case (true, false) => s"and municipalitycode in (${municipalities.mkString(",")})"
      case (false, true) => s"and ${filter.get}"
      case _ => ""
    }

    getLinksWithFilter(s"$bboxFilter $withFilter")
  }

  private def getByMunicipality(municipality: Int, filter: Option[String] = None): Seq[RoadLinkFetched] = {
    val queryFilter =
      if (filter.nonEmpty) s"and ${filter.get}"
      else ""

    getLinksWithFilter(s"municipalitycode = $municipality $queryFilter")
  }

  private def getByPolygon(polygon: Polygon): Seq[RoadLinkFetched] = {
    if (polygon.getCoordinates.isEmpty) return Seq[RoadLinkFetched]()

    val polygonFilter = PostGISDatabase.polygonFilter(polygon, geometryColumn)

    getLinksWithFilter(polygonFilter)
  }

  protected def getLinksIdByPolygons(polygon: Polygon): Seq[String] = {
    if (polygon.getCoordinates.isEmpty) return Seq.empty[String]

    val polygonFilter = PostGISDatabase.polygonFilter(polygon, geometryColumn)
    LogUtils.time(logger, "TEST LOG Getting roadlinks by polygon") {
      sql"""select linkid
          from kgv_roadlink
          where #$polygonFilter
          and expired_date is null
       """.as[String].list
    }
  }

  protected def extractFeatureClass(code: Int): FeatureClass = {
    KgvUtil.extractFeatureClass(code)
  }

  protected def extractModifiedDate(createdDate:Option[Long],lastEdited:Option[Long]): Option[DateTime] = {
    KgvUtil.extractModifiedAt(createdDate,lastEdited)
  }

  def deleteRoadLinksByIds(linkIdsToDelete: Set[String]) = {
    deleteLinksWithFilter(withLinkIdFilter(linkIdsToDelete))
  }

  def getRoadLinkExpiredDateWithLinkIds(linkIds: Set[String]): Seq[LinkIdAndExpiredDate] = {
    getRoadLinkExpiredDateWithFilter(withLinkIdFilter(linkIds))
  }

}
