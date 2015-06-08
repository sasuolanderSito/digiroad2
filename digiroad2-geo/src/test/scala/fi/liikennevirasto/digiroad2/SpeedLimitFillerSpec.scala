package fi.liikennevirasto.digiroad2

import fi.liikennevirasto.digiroad2.asset.Unknown
import fi.liikennevirasto.digiroad2.linearasset.{SpeedLimitDTO, RoadLinkForSpeedLimit}
import org.scalatest._

class SpeedLimitFillerSpec extends FunSuite with Matchers {
  test("extend middle segment of a speed limit") {
    val topology = Map(1l -> RoadLinkForSpeedLimit(Seq(Point(0.0, 0.0), Point(1.0, 0.0)), 1.0, Unknown, 1),
      2l -> RoadLinkForSpeedLimit(Seq(Point(1.0, 0.0), Point(2.0, 0.0)), 1.0, Unknown, 2),
      3l -> RoadLinkForSpeedLimit(Seq(Point(2.0, 0.0), Point(3.0, 0.0)), 1.0, Unknown, 3))
    val speedLimits = Map(1l -> Seq(
      SpeedLimitDTO(1, 1, 0, None, Seq(Point(0.0, 0.0), Point(1.0, 0.0)), 0.0, 1.0),
      SpeedLimitDTO(1, 2, 0, None, Seq(Point(1.0, 0.0), Point(1.5, 0.0)), 0.0, 0.5),
      SpeedLimitDTO(1, 3, 0, None, Seq(Point(2.0, 0.0), Point(3.0, 0.0)), 0.0, 1.0)))
    val (filledTopology, changeSet) = SpeedLimitFiller.fillTopology2(topology, speedLimits)
    filledTopology.map(_.mmlId) should be (Seq(1, 2, 3))
    filledTopology.map(_.points) should be (Seq(Seq(Point(0.0, 0.0), Point(1.0, 0.0)),
      Seq(Point(1.0, 0.0), Point(2.0, 0.0)),
      Seq(Point(2.0, 0.0), Point(3.0, 0.0))))
  }
}
