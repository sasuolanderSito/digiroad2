package fi.liikennevirasto.digiroad2.linearasset

import fi.liikennevirasto.digiroad2.asset.SideCode
import scala.util.Try

object LinearAssetPartitioner extends GraphPartitioner {
  def partition[T <: LinearAsset](links: Seq[T], roadLinksForSpeedLimits: Map[String, RoadLink]): Seq[Seq[T]] = {
    val (twoWayLinks, oneWayLinks) = links.partition(_.sideCode == SideCode.BothDirections)
    val linkGroups = twoWayLinks.groupBy { link =>
      val roadLink = roadLinksForSpeedLimits.get(link.linkId)
      val roadIdentifier = roadLink.flatMap(_.roadIdentifier)
      (roadIdentifier, roadLink.map(_.administrativeClass), link.value, link.id == 0)
    }

    val (linksToPartition, linksToPass) = linkGroups.partition { case ((roadIdentifier, _, _, _), _) => roadIdentifier.isDefined }

    val clusters = for (linkGroup <- linksToPartition.values.toSeq;
                        cluster <- clusterLinks(linkGroup)) yield cluster
    val linkPartitions = clusters.map(linksFromCluster)

    linkPartitions ++ linksToPass.values.flatten.map(x => Seq(x)) ++ oneWayLinks.map(x => Seq(x))
  }

  def partition[T <: PieceWiseLinearAsset](links: Seq[T]): Seq[Seq[T]] = {
    val (twoWayLinks, oneWayLinks) = links.partition(_.sideCode == SideCode.BothDirections)

    def extractRoadIdentifier(link: T): Option[Either[Int, String]] = {
      Try(Left(link.attributes("ROADNUMBER").asInstanceOf[BigInt].intValue()))
        .orElse(Try(Right(getStringAttribute(link.attributes)("ROADNAME_FI"))))
        .orElse(Try(Right(getStringAttribute(link.attributes)("ROADNAME_SE"))))
        .toOption
    }

    def getStringAttribute(attributes: Map[String, Any])(key: String): String =
      attributes.get(key).map(_.toString).getOrElse("")

    val linkGroups = twoWayLinks.groupBy { link =>
      (extractRoadIdentifier(link), link.administrativeClass, link.value, link.id == 0)
    }
    val (linksToPartition, linksToPass) = linkGroups.partition { case ((roadIdentifier, _, _, _), _) => roadIdentifier.isDefined }
    val clusters = linksToPartition.values.map(p => {
      clusterLinks(p)
    }).toSeq.flatten
    val linkPartitions = clusters.map(linksFromCluster)
    linkPartitions ++ linksToPass.values.flatten.map(x => Seq(x)) ++ oneWayLinks.map(x => Seq(x))
  }
}
