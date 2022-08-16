package fi.liikennevirasto.digiroad2.util

import fi.liikennevirasto.digiroad2.asset.SideCode
import fi.liikennevirasto.digiroad2.dao.RoadAddressTEMP
import fi.liikennevirasto.digiroad2.lane.{LaneEndPoints, LaneRoadAddressInfo}
import org.scalatest.{FunSuite, Matchers}

class LaneUtilsSpec extends FunSuite with Matchers {
  val addLaneToAddress1: LaneRoadAddressInfo = LaneRoadAddressInfo(1, 1, 10, 1, 120, 1)
  val addLaneToAddress2: LaneRoadAddressInfo = LaneRoadAddressInfo(1, 1, 10, 4, 120, 1)
  val addLaneToAddress3: LaneRoadAddressInfo = LaneRoadAddressInfo(1, 2, 10, 2, 120, 1)

  val linkId: String = LinkIdGenerator.generateRandom()

  test("Return None for link with road addresses outside of scope") {
    val linkLength = 50.214
    val addressesOnLink1 = Set( // At same road part but start after selection ends
      RoadAddressTEMP(linkId, 1, 1, Track.RightSide, 120, 320, 0.0, 49.281, Seq(), None, None, None)
    )
    val addressesOnLink2 = Set( // At same road part but ends before selection starts
      RoadAddressTEMP(linkId, 1, 1, Track.RightSide, 0, 10, 0.0, 49.281, Seq(), None, None, None)
    )
    val addressesOnLink3 = Set( // road part > selection.endRoadPart
      RoadAddressTEMP(linkId, 1, 2, Track.RightSide, 10, 120, 0.0, 49.281, Seq(), None, None, None)
    )
    val addressesOnLink4 = Set( // road part < selection.startRoadPart
      RoadAddressTEMP(linkId, 1, 1, Track.RightSide, 10, 120, 0.0, 49.281, Seq(), None, None, None)
    )
    val addressesOnLink5 = Set( // multiple addresses on link and all outside scope
      RoadAddressTEMP(linkId, 1, 1, Track.RightSide, 120, 220, 0.0, 49.281, Seq(), None, None, None),
      RoadAddressTEMP(linkId, 1, 1, Track.RightSide, 220, 320, 0.0, 49.281, Seq(), None, None, None)
    )

    val endPoints1 = LaneUtils.calculateStartAndEndPoint(addLaneToAddress1, addressesOnLink1, linkLength)
    val endPoints2 = LaneUtils.calculateStartAndEndPoint(addLaneToAddress1, addressesOnLink2, linkLength)
    val endPoints3 = LaneUtils.calculateStartAndEndPoint(addLaneToAddress1, addressesOnLink3, linkLength)
    val endPoints4 = LaneUtils.calculateStartAndEndPoint(addLaneToAddress3, addressesOnLink4, linkLength)
    val endPoints5 = LaneUtils.calculateStartAndEndPoint(addLaneToAddress1, addressesOnLink5, linkLength)

    endPoints1.isEmpty should be(true)
    endPoints2.isEmpty should be(true)
    endPoints3.isEmpty should be(true)
    endPoints4.isEmpty should be(true)
    endPoints5.isEmpty should be(true)
  }

  test("Return start and end point for link with one road address") {
    val linkLength = 50.214
    val addressesOnLink1 = Set( // road part == selection.startRoadPart && road part == selection.endRoadPart
      RoadAddressTEMP(linkId, 1, 1, Track.RightSide, 10, 120, 0.0, 49.281, Seq(), None, None, None)
    )
    val addressesOnLink2 = Set( // road part > selection.startRoadPart && road part < selection.endRoadPart
      RoadAddressTEMP(linkId, 1, 3, Track.RightSide, 10, 120, 0.0, 49.281, Seq(), None, None, None)
    )

    val endPoints1 = LaneUtils.calculateStartAndEndPoint(addLaneToAddress1, addressesOnLink1, linkLength)
    val endPoints2 = LaneUtils.calculateStartAndEndPoint(addLaneToAddress2, addressesOnLink2, linkLength)

    endPoints1.isEmpty should be(false)
    endPoints2.isEmpty should be(false)

    endPoints1.get should be(LaneEndPoints(0.0, 50.214))
    endPoints2.get should be(LaneEndPoints(0.0, 50.214))
  }

  test("Selection ends before address ends") {
    val linkLength = 50.214
    val addressesOnLink1a = Set( // TowardsDigitizing
      RoadAddressTEMP(linkId, 1, 1, Track.RightSide, 10, 130, 0.0, 49.281, Seq(), Some(SideCode.TowardsDigitizing), None, None)
    )
    val addressesOnLink1b = Set( // AgainstDigitizing
      RoadAddressTEMP(linkId, 1, 1, Track.RightSide, 10, 130, 0.0, 49.281, Seq(), Some(SideCode.AgainstDigitizing), None, None)
    )

    val endPoints1a = LaneUtils.calculateStartAndEndPoint(addLaneToAddress1, addressesOnLink1a, linkLength)
    val endPoints1b = LaneUtils.calculateStartAndEndPoint(addLaneToAddress1, addressesOnLink1b, linkLength)

    endPoints1a.isEmpty should be(false)
    endPoints1b.isEmpty should be(false)

    endPoints1a.get should be(LaneEndPoints(0.0, 46.03))     // End adjusted
    endPoints1b.get should be(LaneEndPoints(4.185, 50.214))  // Start adjusted
  }

  test("Selection starts after start of address") {
    val linkLength = 50.214
    val addressesOnLink1a = Set( // TowardsDigitizing
      RoadAddressTEMP(linkId, 1, 1, Track.RightSide, 0, 120, 0.0, 49.281, Seq(), Some(SideCode.TowardsDigitizing), None, None)
    )
    val addressesOnLink1b = Set( // AgainstDigitizing
      RoadAddressTEMP(linkId, 1, 1, Track.RightSide, 0, 120, 0.0, 49.281, Seq(), Some(SideCode.AgainstDigitizing), None, None)
    )

    val endPoints1a = LaneUtils.calculateStartAndEndPoint(addLaneToAddress1, addressesOnLink1a, linkLength)
    val endPoints1b = LaneUtils.calculateStartAndEndPoint(addLaneToAddress1, addressesOnLink1b, linkLength)

    endPoints1a.isEmpty should be(false)
    endPoints1b.isEmpty should be(false)

    endPoints1a.get should be(LaneEndPoints(4.185, 50.214)) // Start adjusted
    endPoints1b.get should be(LaneEndPoints(0.0, 46.03))    // End adjusted
  }

  test("Multiple addresses on link") {
    val linkLength = 150.214
    val addressesOnLink1a = Set( // TowardsDigitizing
      RoadAddressTEMP(linkId, 1, 1, Track.RightSide, 10, 50, 0.0, 49.281, Seq(), Some(SideCode.TowardsDigitizing), None, None),
      RoadAddressTEMP(linkId, 1, 1, Track.RightSide, 50, 120, 49.281, 182.984, Seq(), Some(SideCode.TowardsDigitizing), None, None)
    )
    val addressesOnLink1b = Set( // AgainstDigitizing
      RoadAddressTEMP(linkId, 1, 1, Track.RightSide, 10, 50, 49.281, 182.984, Seq(), Some(SideCode.AgainstDigitizing), None, None),
      RoadAddressTEMP(linkId, 1, 1, Track.RightSide, 50, 120, 0.0, 49.281, Seq(), Some(SideCode.AgainstDigitizing), None, None)
    )
    val addressesOnLink2 = Set( // Road part changes during link
      RoadAddressTEMP(linkId, 1, 1, Track.RightSide, 10, 50, 0.0, 49.281, Seq(), None, None, None),
      RoadAddressTEMP(linkId, 1, 2, Track.RightSide, 0, 70, 49.281, 182.984, Seq(), None, None, None)
    )

    val endPoints1a = LaneUtils.calculateStartAndEndPoint(addLaneToAddress1, addressesOnLink1a, linkLength)
    val endPoints1b = LaneUtils.calculateStartAndEndPoint(addLaneToAddress1, addressesOnLink1b, linkLength)
    val endPoints2 = LaneUtils.calculateStartAndEndPoint(addLaneToAddress2, addressesOnLink2, linkLength)

    endPoints1a.isEmpty should be(false)
    endPoints1b.isEmpty should be(false)
    endPoints2.isEmpty should be(false)

    endPoints1a.get should be(LaneEndPoints(0.0, 150.214))
    endPoints1b.get should be(LaneEndPoints(0.0, 150.214))
    endPoints2.get should be(LaneEndPoints(0.0, 150.214))
  }
}
