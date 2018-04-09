package fi.liikennevirasto.digiroad2.client.tierekisteri

import fi.liikennevirasto.digiroad2.util.Track
import org.apache.http.impl.client.CloseableHttpClient

case class TierekisteriRoadWidthData(roadNumber: Long, startRoadPartNumber: Long, endRoadPartNumber: Long,
                                     track: Track, startAddressMValue: Long, endAddressMValue: Long, assetValue: Int) extends TierekisteriAssetData

class TierekisteriRoadWidthAssetClient(trEndPoint: String, trEnable: Boolean, httpClient: CloseableHttpClient) extends TierekisteriAssetDataClient{
  override def tierekisteriRestApiEndPoint: String = trEndPoint
  override def tierekisteriEnabled: Boolean = trEnable
  override def client: CloseableHttpClient = httpClient
  type TierekisteriType = TierekisteriRoadWidthData

  override val trAssetType = "tl136"
  private val trALEV = "ALEV"

  override def mapFields(data: Map[String, Any]): Option[TierekisteriRoadWidthData] = {
    //Mandatory field
    val assetValue = convertToInt(getMandatoryFieldValue(data, trALEV)).get * 10 //To convert to cm
    val roadNumber = convertToLong(getMandatoryFieldValue(data, trRoadNumber)).get
    val roadPartNumber = convertToLong(getMandatoryFieldValue(data, trRoadPartNumber)).get
    val endRoadPartNumber = convertToLong(getMandatoryFieldValue(data, trEndRoadPartNumber)).getOrElse(roadPartNumber)
    val startMValue = convertToLong(getMandatoryFieldValue(data, trStartMValue)).get
    val endMValue = convertToLong(getMandatoryFieldValue(data, trEndMValue)).get
    val track = convertToInt(getMandatoryFieldValue(data, trTrackCode)).map(Track.apply).getOrElse(Track.Unknown)

    Some(TierekisteriRoadWidthData(roadNumber, roadPartNumber, endRoadPartNumber, track, startMValue, endMValue, assetValue))
  }
}

