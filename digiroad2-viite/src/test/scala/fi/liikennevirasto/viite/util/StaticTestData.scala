package fi.liikennevirasto.viite.util

import java.io.{File, FileReader}

import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.linearasset.{RoadLink, ValidityPeriodDayOfWeek}
import fi.liikennevirasto.digiroad2.util.VVHSerializer
import fi.liikennevirasto.digiroad2._
import org.json4s.JsonAST.{JInt, JString}
import org.json4s.jackson.Serialization.read
import org.json4s.{CustomSerializer, DefaultFormats, Formats}

import scala.util.parsing.json.JSON

object StaticTestData {
  val deserializer = new RoadLinkDeserializer

  val roadLinkFile = new File(getClass.getResource("/road1130links.json").toURI)
  val historyRoadLinkFile = new File(getClass.getResource("/road1130historyLinks.json").toURI)
  // Contains Road 1130 part 4 and 5 links plus some extra that fit the bounding box approx (351714,6674367)-(361946,6681967)
  val road1130Links: Seq[RoadLink] = deserializer.readCachedGeometry(roadLinkFile)
  val road1130HistoryLinks: Seq[VVHHistoryRoadLink] = deserializer.readCachedHistoryLinks(historyRoadLinkFile)
  def mappedGeoms(linkIds: Iterable[Long]): Map[Long, Seq[Point]] = {
    val set = linkIds.toSet
    geometryMap.filterKeys(l => set.contains(l))
  }

  def toGeom(json: Option[Any]): List[Point] = {
    json.get.asInstanceOf[List[Map[String, Double]]].map(m => Point(m("x"), m("y"), m("z")))
  }


  private val points = Map(
    5170271L -> "[ {\"x\": 530492.408, \"y\": 6994103.892, \"z\": 114.60400000000664},{\"x\": 530490.492, \"y\": 6994104.815, \"z\": 114.63800000000629},{\"x\": 530459.903, \"y\": 6994118.958, \"z\": 114.97299999999814},{\"x\": 530427.446, \"y\": 6994134.189, \"z\": 115.30400000000373},{\"x\": 530392.422, \"y\": 6994153.545, \"z\": 115.721000000005},{\"x\": 530385.114, \"y\": 6994157.976, \"z\": 115.71099999999569},{\"x\": 530381.104, \"y\": 6994161.327, \"z\": 115.77000000000407},{\"x\": 530367.101, \"y\": 6994170.075, \"z\": 115.93099999999686},{\"x\": 530330.275, \"y\": 6994195.603, \"z\": 116.37200000000303}]",
    5170414L -> "[ {\"x\": 531540.842, \"y\": 6993806.017, \"z\": 114.1530000000057},{\"x\": 531515.135, \"y\": 6993815.644, \"z\": 114.74400000000605}]",
    5170067L -> "[ {\"x\": 529169.924, \"y\": 6994631.929, \"z\": 121.52999999999884},{\"x\": 529158.557, \"y\": 6994635.609, \"z\": 121.47999999999593},{\"x\": 529149.47, \"y\": 6994638.618, \"z\": 121.43300000000454}]",
    5170066L -> "[ {\"x\": 529149.47, \"y\": 6994638.618, \"z\": 121.43300000000454},{\"x\": 529147.068, \"y\": 6994639.416, \"z\": 121.45200000000477},{\"x\": 529142.91, \"y\": 6994640.794, \"z\": 121.41700000000128},{\"x\": 529116.198, \"y\": 6994650.179, \"z\": 121.32600000000093},{\"x\": 529099.946, \"y\": 6994655.993, \"z\": 121.2670000000071}]",
    5170074L -> "[ {\"x\": 528982.934, \"y\": 6994703.835, \"z\": 120.9030000000057},{\"x\": 528972.656, \"y\": 6994708.219, \"z\": 120.87699999999313},{\"x\": 528948.747, \"y\": 6994719.171, \"z\": 120.72999999999593},{\"x\": 528924.998, \"y\": 6994730.062, \"z\": 120.64500000000407},{\"x\": 528915.753, \"y\": 6994734.337, \"z\": 120.62799999999697}]",
    5170057L -> "[ {\"x\": 529099.946, \"y\": 6994655.993, \"z\": 121.2670000000071},{\"x\": 529090.588, \"y\": 6994659.353, \"z\": 121.22400000000198},{\"x\": 529065.713, \"y\": 6994668.98, \"z\": 121.1469999999972},{\"x\": 529037.245, \"y\": 6994680.687, \"z\": 121.11000000000058},{\"x\": 529015.841, \"y\": 6994689.617, \"z\": 121.03100000000268},{\"x\": 528994.723, \"y\": 6994698.806, \"z\": 120.93499999999767},{\"x\": 528982.934, \"y\": 6994703.835, \"z\": 120.9030000000057}]",
    5170208L -> "[ {\"x\": 531208.529, \"y\": 6993930.35, \"z\": 113.57600000000093},{\"x\": 531206.956, \"y\": 6993930.852, \"z\": 113.52400000000489},{\"x\": 531206.551, \"y\": 6993930.982, \"z\": 113.51799999999639},{\"x\": 531152.258, \"y\": 6993947.596, \"z\": 112.50900000000547},{\"x\": 531097.601, \"y\": 6993961.148, \"z\": 111.63300000000163},{\"x\": 531035.674, \"y\": 6993974.085, \"z\": 111.00199999999313},{\"x\": 531000.05, \"y\": 6993980.598, \"z\": 110.81100000000151},{\"x\": 530972.845, \"y\": 6993985.159, \"z\": 110.65600000000268}]",
    5170419L -> "[ {\"x\": 531580.116, \"y\": 6993791.375, \"z\": 113.05299999999988},{\"x\": 531559.788, \"y\": 6993798.928, \"z\": 113.63000000000466}]",
    5170105L -> "[ {\"x\": 528699.202, \"y\": 6994841.305, \"z\": 119.86999999999534},{\"x\": 528679.331, \"y\": 6994852.48, \"z\": 119.7390000000014},{\"x\": 528655.278, \"y\": 6994865.047, \"z\": 119.68700000000536},{\"x\": 528627.407, \"y\": 6994880.448, \"z\": 119.5679999999993},{\"x\": 528605.245, \"y\": 6994891.79, \"z\": 119.5219999999972},{\"x\": 528580.964, \"y\": 6994906.041, \"z\": 119.48200000000361}]",
    5170278L -> "[ {\"x\": 530685.408, \"y\": 6994033.6, \"z\": 112.65899999999965},{\"x\": 530681.24, \"y\": 6994034.74, \"z\": 112.66800000000512},{\"x\": 530639.419, \"y\": 6994047.211, \"z\": 113.10400000000664},{\"x\": 530635.275, \"y\": 6994048.447, \"z\": 113.14400000000023},{\"x\": 530624.882, \"y\": 6994051.624, \"z\": 113.22599999999511},{\"x\": 530603.496, \"y\": 6994059.168, \"z\": 113.48699999999371},{\"x\": 530570.252, \"y\": 6994070.562, \"z\": 113.73600000000442},{\"x\": 530537.929, \"y\": 6994083.499, \"z\": 114.09399999999732},{\"x\": 530512.29, \"y\": 6994094.305, \"z\": 114.38899999999558},{\"x\": 530508.822, \"y\": 6994095.977, \"z\": 114.39999999999418}]",
    5170104L -> "[ {\"x\": 528833.042, \"y\": 6994773.324, \"z\": 120.32499999999709},{\"x\": 528806.698, \"y\": 6994786.487, \"z\": 120.21099999999569},{\"x\": 528778.343, \"y\": 6994800.373, \"z\": 120.12699999999313},{\"x\": 528754.485, \"y\": 6994812.492, \"z\": 120.03200000000652},{\"x\": 528728.694, \"y\": 6994826.297, \"z\": 119.9210000000021},{\"x\": 528710.804, \"y\": 6994835.775, \"z\": 119.86599999999453},{\"x\": 528700.208, \"y\": 6994840.792, \"z\": 119.8640000000014},{\"x\": 528699.202, \"y\": 6994841.305, \"z\": 119.86999999999534}]",
    5170250L -> "[ {\"x\": 530972.845, \"y\": 6993985.159, \"z\": 110.65600000000268},{\"x\": 530934.626, \"y\": 6993989.73, \"z\": 110.67999999999302},{\"x\": 530884.749, \"y\": 6993996.905, \"z\": 110.87300000000687},{\"x\": 530849.172, \"y\": 6994001.746, \"z\": 111.07600000000093},{\"x\": 530787.464, \"y\": 6994011.154, \"z\": 111.68300000000454}]",
    5170274L -> "[ {\"x\": 530508.822, \"y\": 6994095.977, \"z\": 114.39999999999418},{\"x\": 530492.408, \"y\": 6994103.892, \"z\": 114.60400000000664}]",
    5170253L -> "[ {\"x\": 530787.464, \"y\": 6994011.154, \"z\": 111.68300000000454},{\"x\": 530735.969, \"y\": 6994021.569, \"z\": 112.14800000000105},{\"x\": 530685.408, \"y\": 6994033.6, \"z\": 112.65899999999965}]",
    5170071L -> "[ {\"x\": 528915.753, \"y\": 6994734.337, \"z\": 120.62799999999697},{\"x\": 528870.534, \"y\": 6994755.246, \"z\": 120.46899999999732},{\"x\": 528853.387, \"y\": 6994763.382, \"z\": 120.41899999999441}]",
    5170200L -> "[ {\"x\": 531515.135, \"y\": 6993815.644, \"z\": 114.74400000000605},{\"x\": 531490.088, \"y\": 6993825.357, \"z\": 115.1469999999972},{\"x\": 531434.788, \"y\": 6993847.717, \"z\": 115.81100000000151},{\"x\": 531382.827, \"y\": 6993867.291, \"z\": 115.9320000000007},{\"x\": 531341.785, \"y\": 6993883.123, \"z\": 115.70500000000175},{\"x\": 531279.229, \"y\": 6993906.106, \"z\": 114.83800000000338},{\"x\": 531263.983, \"y\": 6993911.659, \"z\": 114.55400000000373},{\"x\": 531244.769, \"y\": 6993918.512, \"z\": 114.25299999999697},{\"x\": 531235.891, \"y\": 6993921.64, \"z\": 114.028999999995},{\"x\": 531208.529, \"y\": 6993930.35, \"z\": 113.57600000000093}]",
    5167598L -> "[ {\"x\": 528349.166, \"y\": 6995051.88, \"z\": 119.27599999999802},{\"x\": 528334.374, \"y\": 6995062.151, \"z\": 119.37900000000081},{\"x\": 528318.413, \"y\": 6995072.576, \"z\": 119.49800000000687},{\"x\": 528296.599, \"y\": 6995087.822, \"z\": 119.59200000000419},{\"x\": 528278.343, \"y\": 6995100.519, \"z\": 119.69999999999709},{\"x\": 528232.133, \"y\": 6995133.027, \"z\": 119.97299999999814},{\"x\": 528212.343, \"y\": 6995147.292, \"z\": 120.07700000000477},{\"x\": 528190.409, \"y\": 6995162.14, \"z\": 120.19000000000233},{\"x\": 528161.952, \"y\": 6995182.369, \"z\": 120.3579999999929},{\"x\": 528137.864, \"y\": 6995199.658, \"z\": 120.34200000000419},{\"x\": 528105.957, \"y\": 6995221.607, \"z\": 120.3530000000028}]",
    5170095L -> "[ {\"x\": 528580.964, \"y\": 6994906.041, \"z\": 119.48200000000361},{\"x\": 528562.314, \"y\": 6994917.077, \"z\": 119.4030000000057},{\"x\": 528545.078, \"y\": 6994926.326, \"z\": 119.37200000000303},{\"x\": 528519.958, \"y\": 6994942.165, \"z\": 119.23099999999977},{\"x\": 528497.113, \"y\": 6994955.7, \"z\": 119.18600000000151},{\"x\": 528474.271, \"y\": 6994969.872, \"z\": 119.07200000000012},{\"x\": 528452.7, \"y\": 6994983.398, \"z\": 119.05400000000373},{\"x\": 528435.576, \"y\": 6994994.982, \"z\": 119.01900000000023},{\"x\": 528415.274, \"y\": 6995007.863, \"z\": 119.0460000000021},{\"x\": 528398.486, \"y\": 6995018.309, \"z\": 119.07399999999325},{\"x\": 528378.206, \"y\": 6995031.988, \"z\": 119.12799999999697},{\"x\": 528355.441, \"y\": 6995047.458, \"z\": 119.2390000000014},{\"x\": 528349.166, \"y\": 6995051.88, \"z\": 119.27599999999802}]",
    5170060L -> "[ {\"x\": 528853.387, \"y\": 6994763.382, \"z\": 120.41899999999441},{\"x\": 528843.513, \"y\": 6994768.09, \"z\": 120.37399999999616},{\"x\": 528833.042, \"y\": 6994773.324, \"z\": 120.32499999999709}]",
    5169973L -> "[ {\"x\": 530293.785, \"y\": 6994219.573, \"z\": 116.8070000000007},{\"x\": 530284.91, \"y\": 6994225.31, \"z\": 116.93399999999383},{\"x\": 530236.998, \"y\": 6994260.627, \"z\": 117.38700000000244},{\"x\": 530201.104, \"y\": 6994288.586, \"z\": 117.58599999999569},{\"x\": 530151.371, \"y\": 6994326.968, \"z\": 117.95799999999872},{\"x\": 530124.827, \"y\": 6994345.782, \"z\": 118.0399999999936},{\"x\": 530085.669, \"y\": 6994374.285, \"z\": 118.43399999999383},{\"x\": 530046.051, \"y\": 6994399.019, \"z\": 118.89900000000489},{\"x\": 530004.759, \"y\": 6994422.268, \"z\": 119.39900000000489}]",
    5170344L -> "[ {\"x\": 531642.975, \"y\": 6993763.489, \"z\": 110.8579999999929},{\"x\": 531600.647, \"y\": 6993781.993, \"z\": 112.40600000000268},{\"x\": 531580.116, \"y\": 6993791.375, \"z\": 113.05299999999988}]",
    5170036L -> "[ {\"x\": 530004.759, \"y\": 6994422.268, \"z\": 119.39900000000489},{\"x\": 529971.371, \"y\": 6994440.164, \"z\": 119.82799999999406},{\"x\": 529910.61, \"y\": 6994469.099, \"z\": 120.69400000000314},{\"x\": 529849.474, \"y\": 6994494.273, \"z\": 121.42600000000675},{\"x\": 529816.479, \"y\": 6994506.294, \"z\": 121.8350000000064},{\"x\": 529793.423, \"y\": 6994513.982, \"z\": 122.00699999999779},{\"x\": 529746.625, \"y\": 6994527.76, \"z\": 122.31900000000314},{\"x\": 529708.779, \"y\": 6994537.658, \"z\": 122.49700000000303},{\"x\": 529696.431, \"y\": 6994540.722, \"z\": 122.54099999999744},{\"x\": 529678.274, \"y\": 6994544.52, \"z\": 122.57200000000012},{\"x\": 529651.158, \"y\": 6994549.764, \"z\": 122.63700000000244},{\"x\": 529622.778, \"y\": 6994555.281, \"z\": 122.65899999999965},{\"x\": 529605.13, \"y\": 6994557.731, \"z\": 122.6929999999993},{\"x\": 529530.471, \"y\": 6994567.94, \"z\": 122.75500000000466},{\"x\": 529502.649, \"y\": 6994571.568, \"z\": 122.74199999999837}]",
    5170418L -> "[ {\"x\": 531559.788, \"y\": 6993798.928, \"z\": 113.63000000000466},{\"x\": 531558.07, \"y\": 6993799.566, \"z\": 113.67799999999988},{\"x\": 531540.842, \"y\": 6993806.017, \"z\": 114.1530000000057}]",
    5170114L -> "[ {\"x\": 532675.864, \"y\": 6993667.121, \"z\": 119.63899999999558},{\"x\": 532585, \"y\": 6993623.826, \"z\": 119.29899999999907},{\"x\": 532524.074, \"y\": 6993601.11, \"z\": 119.1420000000071},{\"x\": 532471.813, \"y\": 6993584.678, \"z\": 118.99300000000221},{\"x\": 532432.652, \"y\": 6993575.034, \"z\": 118.85099999999511},{\"x\": 532390.813, \"y\": 6993567.143, \"z\": 118.47699999999895},{\"x\": 532344.481, \"y\": 6993559.882, \"z\": 117.69999999999709},{\"x\": 532300.07, \"y\": 6993555.626, \"z\": 116.75400000000081},{\"x\": 532254.457, \"y\": 6993553.43, \"z\": 115.49499999999534},{\"x\": 532213.217, \"y\": 6993553.879, \"z\": 114.13999999999942},{\"x\": 532166.868, \"y\": 6993558.077, \"z\": 112.27599999999802},{\"x\": 532123.902, \"y\": 6993564.359, \"z\": 110.53599999999278},{\"x\": 532078.039, \"y\": 6993574.524, \"z\": 108.90499999999884},{\"x\": 532026.264, \"y\": 6993589.43, \"z\": 107.60099999999511},{\"x\": 531990.015, \"y\": 6993602.5, \"z\": 106.84299999999348},{\"x\": 531941.753, \"y\": 6993623.417, \"z\": 106.15499999999884},{\"x\": 531885.2, \"y\": 6993648.616, \"z\": 105.94100000000617},{\"x\": 531847.551, \"y\": 6993667.432, \"z\": 106.03100000000268},{\"x\": 531829.085, \"y\": 6993676.017, \"z\": 106.096000000005},{\"x\": 531826.495, \"y\": 6993677.286, \"z\": 106.17600000000675},{\"x\": 531795.338, \"y\": 6993692.819, \"z\": 106.59100000000035},{\"x\": 531750.277, \"y\": 6993714.432, \"z\": 107.46099999999569},{\"x\": 531702.109, \"y\": 6993736.085, \"z\": 108.73500000000058},{\"x\": 531652.731, \"y\": 6993759.226, \"z\": 110.49000000000524},{\"x\": 531642.975, \"y\": 6993763.489, \"z\": 110.8579999999929}]",
    5170266L -> "[ {\"x\": 530330.275, \"y\": 6994195.603, \"z\": 116.37200000000303},{\"x\": 530328.819, \"y\": 6994196.919, \"z\": 116.34900000000198},{\"x\": 530293.785, \"y\": 6994219.573, \"z\": 116.8070000000007}]",
    5170076L -> "[ {\"x\": 529502.649, \"y\": 6994571.568, \"z\": 122.74199999999837},{\"x\": 529488.539, \"y\": 6994573.408, \"z\": 122.75999999999476},{\"x\": 529461.147, \"y\": 6994576.534, \"z\": 122.63099999999395},{\"x\": 529432.538, \"y\": 6994579.398, \"z\": 122.49700000000303},{\"x\": 529402.112, \"y\": 6994583.517, \"z\": 122.36199999999371},{\"x\": 529383.649, \"y\": 6994585.553, \"z\": 122.22500000000582},{\"x\": 529366.46, \"y\": 6994587.58, \"z\": 122.16700000000128},{\"x\": 529340.392, \"y\": 6994591.142, \"z\": 122.0679999999993},{\"x\": 529316.184, \"y\": 6994596.203, \"z\": 121.92500000000291},{\"x\": 529292.004, \"y\": 6994600.827, \"z\": 121.79200000000128},{\"x\": 529274.998, \"y\": 6994603.419, \"z\": 121.74300000000221},{\"x\": 529245.538, \"y\": 6994610.622, \"z\": 121.74899999999616},{\"x\": 529215.54, \"y\": 6994618.628, \"z\": 121.68499999999767},{\"x\": 529200.025, \"y\": 6994623.205, \"z\": 121.58400000000256},{\"x\": 529182.346, \"y\": 6994628.596, \"z\": 121.5109999999986},{\"x\": 529172.437, \"y\": 6994631.118, \"z\": 121.50999999999476},{\"x\": 529169.924, \"y\": 6994631.929, \"z\": 121.52999999999884}]",
    5171309L -> "[ {\"x\": 532675.864, \"y\": 6993667.121, \"z\": 119.63899999999558},{\"x\": 532683.902, \"y\": 6993675.669, \"z\": 119.55599999999686},{\"x\": 532705.617, \"y\": 6993689.231, \"z\": 119.68700000000536},{\"x\": 532738.146, \"y\": 6993711.117, \"z\": 120.0170000000071},{\"x\": 532746.793, \"y\": 6993717.431, \"z\": 120.10199999999895}]",
    5171311L -> "[ {\"x\": 532746.793, \"y\": 6993717.431, \"z\": 120.10199999999895},{\"x\": 532772.872, \"y\": 6993736.47, \"z\": 120.65099999999802},{\"x\": 532796.699, \"y\": 6993755.46, \"z\": 121.12600000000384},{\"x\": 532823.779, \"y\": 6993779.309, \"z\": 121.846000000005},{\"x\": 532851.887, \"y\": 6993806.211, \"z\": 122.5},{\"x\": 532872.336, \"y\": 6993827.537, \"z\": 123.10000000000582},{\"x\": 532888.184, \"y\": 6993844.293, \"z\": 123.59900000000198}]",
    5171041L -> "[ {\"x\": 532900.164, \"y\": 6993858.933, \"z\": 123.9600000000064},{\"x\": 532900.464, \"y\": 6993859.263, \"z\": 123.96099999999569},{\"x\": 532913.982, \"y\": 6993873.992, \"z\": 124.37900000000081},{\"x\": 532945.588, \"y\": 6993907.014, \"z\": 125.26499999999942},{\"x\": 532967.743, \"y\": 6993930.553, \"z\": 125.91099999999278}]",
    5171044L -> "[ {\"x\": 532888.184, \"y\": 6993844.293, \"z\": 123.59900000000198},{\"x\": 532895.422, \"y\": 6993852.852, \"z\": 123.8179999999993},{\"x\": 532900.164, \"y\": 6993858.933, \"z\": 123.9600000000064}]",
    5171310L -> "[ {\"x\": 532752.967, \"y\": 6993710.487, \"z\": 120.50299999999697},{\"x\": 532786.845, \"y\": 6993735.945, \"z\": 121.07200000000012},{\"x\": 532821.582, \"y\": 6993764.354, \"z\": 121.84900000000198},{\"x\": 532852.237, \"y\": 6993791.247, \"z\": 122.63400000000547},{\"x\": 532875.743, \"y\": 6993813.072, \"z\": 123.15700000000652},{\"x\": 532895.051, \"y\": 6993834.921, \"z\": 123.71400000000722}]",
    5171042L -> "[ {\"x\": 532895.051, \"y\": 6993834.921, \"z\": 123.71400000000722},{\"x\": 532904.782, \"y\": 6993844.523, \"z\": 123.94599999999627},{\"x\": 532911.053, \"y\": 6993850.749, \"z\": 124.0789999999979}]",
    5171040L -> "[ {\"x\": 532911.053, \"y\": 6993850.749, \"z\": 124.0789999999979},{\"x\": 532915.004, \"y\": 6993854.676, \"z\": 124.16400000000431},{\"x\": 532934.432, \"y\": 6993875.496, \"z\": 124.625},{\"x\": 532952.144, \"y\": 6993896.59, \"z\": 125.21799999999348},{\"x\": 532976.907, \"y\": 6993922.419, \"z\": 125.43700000000536}]",
    5171308L -> "[ {\"x\": 532675.864, \"y\": 6993667.121, \"z\": 119.63899999999558},{\"x\": 532706.975, \"y\": 6993682.696, \"z\": 119.89999999999418},{\"x\": 532731.983, \"y\": 6993696.366, \"z\": 120.1820000000007},{\"x\": 532752.967, \"y\": 6993710.487, \"z\": 120.50299999999697}]",
    5176552L -> "[{\"x\":537869.292,\"y\":6997722.466,\"z\":110.39800000000105},{\"x\":538290.056,\"y\":6998265.169,\"z\":85.4429999999993}]",
    5176512L -> "[{\"x\":537152.306,\"y\":6996873.826,\"z\":108.27700000000186},{\"x\":537869.292,\"y\":6997722.466,\"z\":110.39800000000105}]",
    5176584L -> "[{\"x\":538290.056,\"y\":6998265.169,\"z\":85.4429999999993},{\"x\":538418.3307786948,\"y\":6998426.422734798,\"z\":88.17597963771014}]",
    6117675L -> "[{\"x\": 347362.773,\"y\": 6689481.461,\"z\": 67.55100000000675}, {\"x\": 347362.685,\"y\": 6689482.757,\"z\": 67.42900000000373}, {\"x\": 347361.105,\"y\": 6689506.288,\"z\": 66.12099999999919}, {\"x\": 347357.894,\"y\": 6689536.08,\"z\": 65.97999999999593}, {\"x\": 347356.682,\"y\": 6689566.328,\"z\": 66.05199999999604}, {\"x\": 347356.982,\"y\": 6689596.011,\"z\": 66.38800000000629}, {\"x\": 347359.306,\"y\": 6689619.386,\"z\": 66.45799999999872}, {\"x\": 347362.853,\"y\": 6689631.277,\"z\": 66.1649999999936}, {\"x\": 347369.426,\"y\": 6689645.863,\"z\": 65.88700000000244}, {\"x\": 347372.682,\"y\": 6689657.912,\"z\": 66.06600000000617}, {\"x\": 347371.839,\"y\": 6689672.648,\"z\": 66.73399999999674}, {\"x\": 347368.015,\"y\": 6689689.588,\"z\": 67.28399999999965}, {\"x\": 347361.671,\"y\": 6689711.619,\"z\": 68.07300000000396}, {\"x\": 347356.726,\"y\": 6689730.288,\"z\": 68.57300000000396}, {\"x\": 347350.081,\"y\": 6689751.062,\"z\": 69.09399999999732}, {\"x\": 347343.06,\"y\": 6689773.574,\"z\": 68.86900000000605}, {\"x\": 347337.186,\"y\": 6689793.763,\"z\": 68.30999999999767}, {\"x\": 347328.501,\"y\": 6689814.216,\"z\": 67.84900000000198}, {\"x\": 347320.495,\"y\": 6689828.259,\"z\": 67.35000000000582}, {\"x\": 347308.837,\"y\": 6689849.704,\"z\": 66.91300000000047}, {\"x\": 347297.244,\"y\": 6689866.546,\"z\": 66.88499999999476}, {\"x\": 347282.117,\"y\": 6689886.857,\"z\": 66.67999999999302}, {\"x\": 347268.669,\"y\": 6689905.865,\"z\": 66.65899999999965}, {\"x\": 347253.307,\"y\": 6689927.723,\"z\": 66.75400000000081}, {\"x\": 347238.302,\"y\": 6689951.051,\"z\": 66.63099999999395}, {\"x\": 347224.587,\"y\": 6689973.39,\"z\": 66.65200000000186}, {\"x\": 347211.253,\"y\": 6689997.583,\"z\": 66.38800000000629}, {\"x\": 347205.387,\"y\": 6690009.305,\"z\": 66.6030000000028}, {\"x\": 347202.742,\"y\": 6690023.57,\"z\": 66.18600000000151}, {\"x\": 347204.208,\"y\": 6690034.352,\"z\": 65.58400000000256}, {\"x\": 347208.566,\"y\": 6690044.173,\"z\": 65.14599999999336}, {\"x\": 347216.713,\"y\": 6690056.021,\"z\": 64.80599999999686}, {\"x\": 347220.291,\"y\": 6690066.224,\"z\": 64.93600000000151}, {\"x\": 347222.248,\"y\": 6690080.454,\"z\": 65.24400000000605}, {\"x\": 347220.684,\"y\": 6690096.777,\"z\": 65.56500000000233}, {\"x\": 347217.723,\"y\": 6690116.388,\"z\": 65.8920000000071}\n\t\t\t]",
    6638374L -> "[{\"x\": 347217.723,\"y\": 6690116.388,\"z\": 65.8920000000071}, {\"x\": 347214.916,\"y\": 6690134.934,\"z\": 66.25999999999476}, {\"x\": 347213.851,\"y\": 6690152.423,\"z\": 66.79899999999907}, {\"x\": 347213.827,\"y\": 6690152.93,\"z\": 66.82300000000396}\n\t\t\t]",
    6638371L -> "[{\"x\": 347213.827,\"y\": 6690152.93,\"z\": 66.82300000000396}, {\"x\": 347212.547,\"y\": 6690180.465,\"z\": 67.92699999999604}, {\"x\": 347210.573,\"y\": 6690206.911,\"z\": 68.28800000000047}, {\"x\": 347209.134,\"y\": 6690230.456,\"z\": 67.83299999999872}\n\t\t\t]",
    6638357L -> "[{\"x\": 347209.134,\"y\": 6690230.456,\"z\": 67.83299999999872}, {\"x\": 347208.897,\"y\": 6690234.161,\"z\": 67.80599999999686}, {\"x\": 347205.6,\"y\": 6690271.347,\"z\": 67.84200000000419}, {\"x\": 347203.144,\"y\": 6690303.761,\"z\": 66.14299999999639}, {\"x\": 347200.251,\"y\": 6690347.968,\"z\": 63.921000000002095}, {\"x\": 347198.697,\"y\": 6690365.988,\"z\": 63.820000000006985}, {\"x\": 347197.349,\"y\": 6690400.247,\"z\": 64.25500000000466}, {\"x\": 347197.166,\"y\": 6690417.044,\"z\": 64.45799999999872}, {\"x\": 347197.532,\"y\": 6690435.302,\"z\": 64.53800000000047}, {\"x\": 347198.261,\"y\": 6690455.932,\"z\": 64.63700000000244}, {\"x\": 347197.615,\"y\": 6690476.327,\"z\": 65.04899999999907}, {\"x\": 347194.793,\"y\": 6690493.361,\"z\": 65.16099999999278}, {\"x\": 347191.141,\"y\": 6690509.063,\"z\": 65.49199999999837}, {\"x\": 347180.369,\"y\": 6690544.848,\"z\": 66.14400000000023}, {\"x\": 347174.891,\"y\": 6690563.471,\"z\": 66.61299999999756}\n\t\t\t]",
    6117732L -> "[{\"x\": 347174.891,\"y\": 6690563.471,\"z\": 66.61299999999756}, {\"x\": 347169.843,\"y\": 6690582.738,\"z\": 67.47599999999511}, {\"x\": 347166.225,\"y\": 6690601.016,\"z\": 68.7670000000071}, {\"x\": 347165.843,\"y\": 6690612.347,\"z\": 69.56200000000536}\n\t\t\t]",
    6117725L -> "[{\"x\": 347165.843,\"y\": 6690612.347,\"z\": 69.56200000000536}, {\"x\": 347167.477,\"y\": 6690629.902,\"z\": 70.70500000000175}, {\"x\": 347170.578,\"y\": 6690644.885,\"z\": 71.59399999999732}, {\"x\": 347176.414,\"y\": 6690664.977,\"z\": 72.79399999999441}, {\"x\": 347184.98,\"y\": 6690684.201,\"z\": 73.8920000000071}, {\"x\": 347194.603,\"y\": 6690703.442,\"z\": 74.57399999999325}, {\"x\": 347212.124,\"y\": 6690731.843,\"z\": 74.42999999999302}, {\"x\": 347225.858,\"y\": 6690748.726,\"z\": 74.8469999999943}\n\t\t\t]",
    6638363L -> "[{\"x\": 347225.858,\"y\": 6690748.726,\"z\": 74.8469999999943}, {\"x\": 347232.073,\"y\": 6690754.294,\"z\": 75.07499999999709}, {\"x\": 347243.715,\"y\": 6690765.894,\"z\": 75.11699999999837}, {\"x\": 347254.071,\"y\": 6690775.234,\"z\": 74.75299999999697}, {\"x\": 347267.791,\"y\": 6690791.279,\"z\": 74.37600000000384}, {\"x\": 347281.366,\"y\": 6690808.298,\"z\": 74.34900000000198}, {\"x\": 347291.699,\"y\": 6690819.847,\"z\": 74.12200000000303}, {\"x\": 347303.653,\"y\": 6690832.815,\"z\": 73.45699999999488}, {\"x\": 347317.525,\"y\": 6690848.754,\"z\": 72.03599999999278}, {\"x\": 347329.296,\"y\": 6690864.119,\"z\": 70.91700000000128}, {\"x\": 347339.883,\"y\": 6690882.667,\"z\": 69.96099999999569}, {\"x\": 347354.706,\"y\": 6690911.347,\"z\": 68.72500000000582}, {\"x\": 347356.35,\"y\": 6690915.047,\"z\": 68.6140000000014}\n\t\t\t]",
    6117633L -> "[{\"x\": 347356.35,\"y\": 6690915.047,\"z\": 68.6140000000014}, {\"x\": 347369.27,\"y\": 6690940.397,\"z\": 68.20699999999488}, {\"x\": 347378.369,\"y\": 6690956.751,\"z\": 68.01900000000023}, {\"x\": 347390.322,\"y\": 6690972.648,\"z\": 67.98699999999371}, {\"x\": 347404.727,\"y\": 6690989.705,\"z\": 68.12799999999697}, {\"x\": 347416.172,\"y\": 6691004.385,\"z\": 68.15499999999884}, {\"x\": 347428.658,\"y\": 6691024.734,\"z\": 68.24300000000221}, {\"x\": 347444.421,\"y\": 6691050.043,\"z\": 68.63199999999779}, {\"x\": 347456.181,\"y\": 6691071.866,\"z\": 68.99199999999837}, {\"x\": 347468.537,\"y\": 6691089.503,\"z\": 69.02700000000186}, {\"x\": 347482.425,\"y\": 6691101.472,\"z\": 68.6820000000007}\n\t\t\t]",
    6117621L -> "[{\"x\": 347482.425,\"y\": 6691101.472,\"z\": 68.6820000000007}, {\"x\": 347482.004,\"y\": 6691116.142,\"z\": 69.1710000000021}, {\"x\": 347477.901,\"y\": 6691138.644,\"z\": 69.45200000000477}, {\"x\": 347467.696,\"y\": 6691173.502,\"z\": 69.49400000000605}, {\"x\": 347463.345,\"y\": 6691196.774,\"z\": 69.23200000000361}, {\"x\": 347462.146,\"y\": 6691209.426,\"z\": 69.03100000000268}, {\"x\": 347462.459,\"y\": 6691219.919,\"z\": 68.83900000000722}, {\"x\": 347462.513,\"y\": 6691220.325,\"z\": 68.82700000000477}\n\t\t\t]",
    6638300L -> "[{\"x\": 347225.858,\"y\": 6690748.726,\"z\": 74.8469999999943}, {\"x\": 347226.867,\"y\": 6690762.837,\"z\": 75.01900000000023}, {\"x\": 347227.161,\"y\": 6690771.948,\"z\": 75.16999999999825}, {\"x\": 347226.7,\"y\": 6690786.654,\"z\": 75.30000000000291}, {\"x\": 347227.131,\"y\": 6690799.799,\"z\": 75.40200000000186}, {\"x\": 347230.363,\"y\": 6690815.529,\"z\": 75.29899999999907}, {\"x\": 347236.181,\"y\": 6690836.43,\"z\": 75.36299999999756}, {\"x\": 347243.358,\"y\": 6690863.377,\"z\": 75.95699999999488}, {\"x\": 347244.89,\"y\": 6690869.357,\"z\": 76.20600000000559}, {\"x\": 347245.719,\"y\": 6690874.941,\"z\": 76.49899999999616}, {\"x\": 347245.728,\"y\": 6690882.712,\"z\": 76.80199999999604}, {\"x\": 347243.45,\"y\": 6690897.118,\"z\": 77.18099999999686}, {\"x\": 347241.137,\"y\": 6690918.745,\"z\": 77.59100000000035}, {\"x\": 347237.904,\"y\": 6690943.525,\"z\": 78.78200000000652}, {\"x\": 347235.534,\"y\": 6690971.968,\"z\": 80.66700000000128}, {\"x\": 347234.241,\"y\": 6690992.008,\"z\": 81.91400000000431}, {\"x\": 347235.526,\"y\": 6691007.878,\"z\": 81.33599999999569}, {\"x\": 347237.475,\"y\": 6691021.321,\"z\": 80.84399999999732}, {\"x\": 347238.276,\"y\": 6691035.857,\"z\": 80.14299999999639}, {\"x\": 347237.701,\"y\": 6691047.139,\"z\": 79.40600000000268}, {\"x\": 347235.322,\"y\": 6691063.305,\"z\": 77.7670000000071}, {\"x\": 347227.128,\"y\": 6691070.636,\"z\": 76.79399999999441}, {\"x\": 347215.484,\"y\": 6691076.458,\"z\": 75.79799999999523}, {\"x\": 347192.844,\"y\": 6691084.436,\"z\": 74.4890000000014}\n\t\t\t]",
    6117634L -> "[{\"x\": 347192.844,\"y\": 6691084.436,\"z\": 74.4890000000014}, {\"x\": 347195.215,\"y\": 6691095.648,\"z\": 75.3530000000028}, {\"x\": 347196.811,\"y\": 6691103.099,\"z\": 75.49400000000605}, {\"x\": 347205.175,\"y\": 6691113.893,\"z\": 75.48200000000361}, {\"x\": 347213.149,\"y\": 6691121.525,\"z\": 75.04799999999523}, {\"x\": 347222.515,\"y\": 6691129.117,\"z\": 74.36599999999453}\n\t\t\t]",
    6117622L -> "[{\"x\": 347222.515,\"y\": 6691129.117,\"z\": 74.36599999999453}, {\"x\": 347296.059,\"y\": 6691163.552,\"z\": 73.25999999999476}, {\"x\": 347329.959,\"y\": 6691183.572,\"z\": 72.48200000000361}, {\"x\": 347375.14,\"y\": 6691205.863,\"z\": 72.48799999999756}, {\"x\": 347389.638,\"y\": 6691212.811,\"z\": 72.50900000000547}, {\"x\": 347399.44,\"y\": 6691215.717,\"z\": 72.32399999999325}, {\"x\": 347408.855,\"y\": 6691217.184,\"z\": 72.49899999999616}, {\"x\": 347428.039,\"y\": 6691217.13,\"z\": 72.18600000000151}, {\"x\": 347462.513,\"y\": 6691220.325,\"z\": 68.82700000000477}\n\t\t\t]",
    6638330L -> "[{\"x\": 347462.513,\"y\": 6691220.325,\"z\": 68.82700000000477}, {\"x\": 347465.816,\"y\": 6691233.667,\"z\": 68.30000000000291}, {\"x\": 347469.693,\"y\": 6691245.746,\"z\": 67.46199999999953}\n\t\t\t]",
    6638318L -> "[{\"x\": 347469.693,\"y\": 6691245.746,\"z\": 67.46199999999953}, {\"x\": 347474.552,\"y\": 6691259.384,\"z\": 66.81200000000536}, {\"x\": 347481.131,\"y\": 6691278.714,\"z\": 66.34799999999814}, {\"x\": 347489.276,\"y\": 6691301.289,\"z\": 66.16800000000512}, {\"x\": 347508.206,\"y\": 6691356.431,\"z\": 66.41899999999441}, {\"x\": 347510.825,\"y\": 6691363.505,\"z\": 66.48699999999371}, {\"x\": 347517.822,\"y\": 6691383.715,\"z\": 66.65499999999884}, {\"x\": 347523.459,\"y\": 6691411.166,\"z\": 66.71400000000722}, {\"x\": 347529.779,\"y\": 6691465.798,\"z\": 65.80599999999686}, {\"x\": 347531.806,\"y\": 6691490.947,\"z\": 65.40899999999965}, {\"x\": 347532.674,\"y\": 6691502.775,\"z\": 65.3350000000064}, {\"x\": 347536.133,\"y\": 6691514.845,\"z\": 65.5219999999972}, {\"x\": 347540.827,\"y\": 6691527.15,\"z\": 65.86299999999756}, {\"x\": 347543.003,\"y\": 6691531.606,\"z\": 65.91199999999662}\n\t\t\t]",
    6117602L -> "[{\"x\": 347543.003,\"y\": 6691531.606,\"z\": 65.91199999999662}, {\"x\": 347549.505,\"y\": 6691541.083,\"z\": 66.16999999999825}, {\"x\": 347558.624,\"y\": 6691553.305,\"z\": 66.45600000000559}, {\"x\": 347566.507,\"y\": 6691565.246,\"z\": 66.63800000000629}, {\"x\": 347569.67,\"y\": 6691574.842,\"z\": 66.4780000000028}, {\"x\": 347570.568,\"y\": 6691586.054,\"z\": 66.2670000000071}, {\"x\": 347568.041,\"y\": 6691610.28,\"z\": 66.2719999999972}, {\"x\": 347565.481,\"y\": 6691630.916,\"z\": 66.46700000000419}, {\"x\": 347562.544,\"y\": 6691652.941,\"z\": 66.52999999999884}, {\"x\": 347561.214,\"y\": 6691669.255,\"z\": 66.74199999999837}, {\"x\": 347562.115,\"y\": 6691680.721,\"z\": 66.70299999999406}, {\"x\": 347566.876,\"y\": 6691702.688,\"z\": 66.37099999999919}, {\"x\": 347572.721,\"y\": 6691728.019,\"z\": 65.76200000000244}, {\"x\": 347577.691,\"y\": 6691742.972,\"z\": 65.99300000000221}, {\"x\": 347589.125,\"y\": 6691757.394,\"z\": 66.46700000000419}\n\t\t\t]",
    6638304L -> "[{\"x\": 347589.125,\"y\": 6691757.394,\"z\": 66.46700000000419}, {\"x\": 347605.166,\"y\": 6691763.643,\"z\": 66.93600000000151}, {\"x\": 347621.417,\"y\": 6691767.811,\"z\": 67.88700000000244}, {\"x\": 347631.827,\"y\": 6691772.695,\"z\": 68.62300000000687}, {\"x\": 347644.334,\"y\": 6691781.978,\"z\": 69.44400000000314}, {\"x\": 347655.168,\"y\": 6691792.186,\"z\": 69.64400000000023}, {\"x\": 347666.21,\"y\": 6691800.729,\"z\": 69.82399999999325}, {\"x\": 347681.001,\"y\": 6691809.27,\"z\": 70.12399999999616}, {\"x\": 347698.488,\"y\": 6691813.432,\"z\": 70.43300000000454}, {\"x\": 347714.753,\"y\": 6691815.104,\"z\": 71.29700000000594}, {\"x\": 347735.586,\"y\": 6691816.771,\"z\": 73.25400000000081}, {\"x\": 347752.67,\"y\": 6691822.397,\"z\": 74.45200000000477}, {\"x\": 347769.753,\"y\": 6691831.563,\"z\": 76.11599999999453}, {\"x\": 347782.671,\"y\": 6691841.772,\"z\": 77.9149999999936}, {\"x\": 347794.175,\"y\": 6691857.513,\"z\": 77.91999999999825}, {\"x\": 347804.113,\"y\": 6691870.818,\"z\": 77.25}, {\"x\": 347816.213,\"y\": 6691894.065,\"z\": 77.00199999999313}, {\"x\": 347818.504,\"y\": 6691907.815,\"z\": 76.95799999999872}, {\"x\": 347815.796,\"y\": 6691921.149,\"z\": 76.63899999999558}, {\"x\": 347812.255,\"y\": 6691935.107,\"z\": 76.23500000000058}, {\"x\": 347808.504,\"y\": 6691948.441,\"z\": 75.87099999999919}, {\"x\": 347807.463,\"y\": 6691963.025,\"z\": 75.77700000000186}, {\"x\": 347808.087,\"y\": 6691979.9,\"z\": 75.65799999999581}, {\"x\": 347808.296,\"y\": 6691994.484,\"z\": 75.32000000000698}, {\"x\": 347807.045,\"y\": 6692008.235,\"z\": 75.09900000000198}, {\"x\": 347804.962,\"y\": 6692018.026,\"z\": 74.73099999999977}, {\"x\": 347798.921,\"y\": 6692026.985,\"z\": 74.33299999999872}, {\"x\": 347790.795,\"y\": 6692039.068,\"z\": 74.06100000000151}, {\"x\": 347784.753,\"y\": 6692048.652,\"z\": 73.76399999999558}, {\"x\": 347780.586,\"y\": 6692057.818,\"z\": 73.55100000000675}, {\"x\": 347777.461,\"y\": 6692070.319,\"z\": 73.46799999999348}, {\"x\": 347774.336,\"y\": 6692088.861,\"z\": 73.45699999999488}, {\"x\": 347771.836,\"y\": 6692111.361,\"z\": 73.25599999999395}, {\"x\": 347770.957,\"y\": 6692132.505,\"z\": 73.46499999999651}, {\"x\": 347770.586,\"y\": 6692154.696,\"z\": 73.65700000000652}, {\"x\": 347767.669,\"y\": 6692177.821,\"z\": 73.69599999999627}, {\"x\": 347765.585,\"y\": 6692201.989,\"z\": 74.3530000000028}, {\"x\": 347764.961,\"y\": 6692214.696,\"z\": 74.52099999999336}, {\"x\": 347766.419,\"y\": 6692223.864,\"z\": 74.40899999999965}, {\"x\": 347771.133,\"y\": 6692242.917,\"z\": 74.57200000000012}, {\"x\": 347773.409,\"y\": 6692254.612,\"z\": 74.51399999999558}, {\"x\": 347779.769,\"y\": 6692295.669,\"z\": 74.72500000000582}\n\t\t\t]"
  )
  private val geometryMap = points.mapValues(v => toGeom(JSON.parseFull(v)))
}

class RoadLinkDeserializer extends VVHSerializer {
  case object SideCodeSerializer extends CustomSerializer[SideCode](format => ( {
    null
  }, {
    case s: SideCode => JInt(s.value)
  }))

  case object TrafficDirectionSerializer extends CustomSerializer[TrafficDirection](format => ( {
    case JString(direction) => TrafficDirection(direction)
  }, {
    case t: TrafficDirection => JString(t.toString)
  }))

  case object DayofWeekSerializer extends CustomSerializer[ValidityPeriodDayOfWeek](format => ( {
    case JString(dayOfWeek) => ValidityPeriodDayOfWeek(dayOfWeek)
  }, {
    case d: ValidityPeriodDayOfWeek => JString(d.toString)
  }))

  case object LinkTypeSerializer extends CustomSerializer[LinkType](format => ( {
    case JInt(linkType) => LinkType(linkType.toInt)
  }, {
    case lt: LinkType => JInt(BigInt(lt.value))
  }))

  case object AdministrativeClassSerializer extends CustomSerializer[AdministrativeClass](format => ( {
    case JInt(typeInt) => AdministrativeClass(typeInt.toInt)
  }, {
    case ac: AdministrativeClass =>
      JInt(BigInt(ac.value))
  }))

  case object LinkGeomSourceSerializer extends CustomSerializer[LinkGeomSource](format => ( {
    case JInt(typeInt) => LinkGeomSource(typeInt.toInt)
  }, {
    case geomSource: LinkGeomSource =>
      JInt(BigInt(geomSource.value))
  }))

  case object ConstructionTypeSerializer extends CustomSerializer[ConstructionType](format => ( {
    case JInt(typeInt) => ConstructionType(typeInt.toInt)
  }, {
    case constructionType: ConstructionType =>
      JInt(BigInt(constructionType.value))
  }))

  case object FeatureClassSerializer extends CustomSerializer[FeatureClass](format => ( {
    case _ => FeatureClass.AllOthers
  }, {
    case fc: FeatureClass =>
      JString("")
  }))

  protected implicit val jsonFormats: Formats = DefaultFormats + SideCodeSerializer + TrafficDirectionSerializer +
    LinkTypeSerializer + DayofWeekSerializer + AdministrativeClassSerializer + LinkGeomSourceSerializer + ConstructionTypeSerializer +
    FeatureClassSerializer

  def readCachedHistoryLinks(file: File): Seq[VVHHistoryRoadLink] = {
    val json = new FileReader(file)
    read[Seq[VVHHistoryRoadLink]](json)
  }

  override def readCachedGeometry(file: File): Seq[RoadLink] = {
    val json = new FileReader(file)
    read[Seq[RoadLink]](json)
  }

  override def readCachedChanges(file: File): Seq[ChangeInfo] = {
    val json = new FileReader(file)
    read[Seq[ChangeInfo]](json)
  }
  override def writeCache(file: File, objects: Seq[Object]): Boolean = {
    false
  }

  override def readCachedNodes(file: File): Seq[VVHRoadNodes] = {
    val json = new FileReader(file)
    read[Seq[VVHRoadNodes]](json)
  }
}
