package fi.liikennevirasto.digiroad2.util

import org.scalatest._
import fi.liikennevirasto.digiroad2.asset.{Asset, RoadLink}
import GeometryUtils._

class GeometryUtilsSpec extends FunSuite with Matchers {
  test("calculate bearing at asset position") {
    val asset = Asset(0, 0, 10.0, 10.0, 0)
    val rlDegenerate = RoadLink(id = 0, lonLat = Seq(), municipalityNumber = 235)
    val rlQuadrant1 = RoadLink(id = 0, lonLat = Seq((1,1), (2, 2)), municipalityNumber = 235)
    val rlQuadrant2 = RoadLink(id = 0, lonLat = Seq((-1,1), (-2, 2)), municipalityNumber = 235)
    val rlQuadrant3 = RoadLink(id = 0, lonLat = Seq((-1,-1), (-2, -2)), municipalityNumber = 235)
    val rlQuadrant4 = RoadLink(id = 0, lonLat = Seq((1,-1), (2, -2)), municipalityNumber = 235)
    calculateBearing(asset, rlDegenerate) should be (0)
    calculateBearing(asset, rlQuadrant1) should be (45)
    calculateBearing(asset, rlQuadrant2) should be (315)
    calculateBearing(asset, rlQuadrant3) should be (225)
    calculateBearing(asset, rlQuadrant4) should be (135)
  }
}