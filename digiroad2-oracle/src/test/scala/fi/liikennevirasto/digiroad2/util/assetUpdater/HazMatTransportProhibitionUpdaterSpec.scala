
package fi.liikennevirasto.digiroad2.util.assetUpdater

import fi.liikennevirasto.digiroad2.asset.{HazmatTransportProhibition, Prohibition, SideCode}
import fi.liikennevirasto.digiroad2.client.{RoadLinkChangeClient, RoadLinkClient, RoadLinkFetched}
import fi.liikennevirasto.digiroad2.dao.linearasset.PostGISLinearAssetDao
import fi.liikennevirasto.digiroad2.linearasset.{ProhibitionValue, Prohibitions}
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.service.linearasset.{HazmatTransportProhibitionService, LinearAssetTypes, Measures}
import fi.liikennevirasto.digiroad2.util.{Digiroad2Properties, TestTransactions}
import fi.liikennevirasto.digiroad2.{DigiroadEventBus, DummySerializer}
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{FunSuite, Matchers}

class HazMatTransportProhibitionUpdaterSpec extends FunSuite with Matchers{

  val mockRoadLinkService = MockitoSugar.mock[RoadLinkService]
  val mockEventBus = MockitoSugar.mock[DigiroadEventBus]
  val mockRoadLinkClient = MockitoSugar.mock[RoadLinkClient]
  val linearAssetDao = new PostGISLinearAssetDao()
  val service = new HazmatTransportProhibitionService(mockRoadLinkService, mockEventBus)

  def runWithRollback(test: => Unit): Unit = TestTransactions.runWithRollback()(test)

  object TestHazMatProhibitionUpdater extends HazMatTransportProhibitionUpdater(service) {
    override def withDynTransaction[T](f: => T): T = f
    override def dao: PostGISLinearAssetDao = linearAssetDao
    override def eventBus: DigiroadEventBus = mockEventBus
    override def roadLinkClient: RoadLinkClient = mockRoadLinkClient
  }
  
  lazy val roadLinkClient: RoadLinkClient = {
    new RoadLinkClient(Digiroad2Properties.vvhRestApiEndPoint)
  }
  lazy val roadLinkService: RoadLinkService = {
    new RoadLinkService(roadLinkClient, mockEventBus, new DummySerializer)
  }

  val roadLinkChangeClient = new RoadLinkChangeClient

  lazy val source = scala.io.Source.fromFile("digiroad2-oracle/src/test/resources/smallChangeSet.json").mkString
  
  val assetValues = Prohibitions(Seq(ProhibitionValue(2, Set.empty, Set.empty)))

  test("case 1 links under asset is split, smoke test") {
    val linkId = "f8fcc994-6e3e-41b5-bb0f-ae6089fe6acc:1"
    val newLinks = Seq("753279ca-5a4d-4713-8609-0bd35d6a30fa:1", "c83d66e9-89fe-4b19-8f5b-f9f2121e3db7:1", "c3beb1ca-05b4-44d6-8d69-2a0e09f22580:1")
    val changes = roadLinkChangeClient.convertToRoadLinkChange(source)

    runWithRollback {
      val oldRoadLink = roadLinkService.getExpiredRoadLinkByLinkId(linkId).get
      val oldRoadLinkRaw = roadLinkService.getExpiredRoadLinkByLinkIdNonEncrished(linkId)
      when(mockRoadLinkService.fetchRoadlinkAndComplementary(linkId)).thenReturn(oldRoadLinkRaw)
      when(mockRoadLinkService.fetchRoadlinksByIds(any[Set[String]])).thenReturn(Seq.empty[RoadLinkFetched])

      val id = service.createWithoutTransaction(HazmatTransportProhibition.typeId, linkId,
        assetValues, SideCode.BothDirections.value, Measures(0, 56.061), "testuser", 0L, Some(oldRoadLink), false, None, None)
      val assetsBefore = service.getPersistedAssetsByIds(HazmatTransportProhibition.typeId, Set(id), false)

      assetsBefore.size should be(1)
      assetsBefore.head.expired should be(false)
      TestHazMatProhibitionUpdater.updateByRoadLinks(HazmatTransportProhibition.typeId, changes)
      val assetsAfter = service.getPersistedAssetsByLinkIds(HazmatTransportProhibition.typeId, newLinks,false)
      assetsAfter.size should be(3)
      val sorted = assetsAfter.sortBy(_.endMeasure)
      sorted.head.startMeasure should be(0)
      sorted.head.endMeasure should be(9.334)

      sorted(1).startMeasure should be(0)
      sorted(1).endMeasure should be(11.841)

      sorted(2).startMeasure should be(0)
      sorted(2).endMeasure should be(34.906)
      assetsAfter.map(v => v.value.isEmpty should be(false))
      assetsAfter.map(v => v.value.get.equals(assetValues))
    }
  }
}
