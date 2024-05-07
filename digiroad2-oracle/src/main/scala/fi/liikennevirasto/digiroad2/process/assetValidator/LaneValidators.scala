package fi.liikennevirasto.digiroad2.process.assetValidator

import fi.liikennevirasto.digiroad2.dao.LaneValidatorDao
import fi.liikennevirasto.digiroad2.util.LogUtils
import org.slf4j.LoggerFactory

object LaneValidators extends Validators {
  private val logger = LoggerFactory.getLogger(getClass)
  
  override def forSamuutus: Seq[ValidatorFunction] = Seq(laneFitIntoLink, lanesIsNotTooShort, lanesDoesNotOverlap, incoherentLaneCode)
  override def forTopology: Seq[ValidatorFunction] = Seq(laneFitIntoLink, lanesIsNotTooShort, lanesDoesNotOverlap, incoherentLaneCode)

  private def laneFitIntoLink(assetType: Int, linkIds: Set[String]): returnResult = {
    LogUtils.time(logger,"laneFitIntoLink"){
      Validators.returnValidationResult("laneWhichDoesNotFallInFullyToLink",LaneValidatorDao.laneWhichDoesNotFallInFullyToLink(linkIds))
    }
  }
  
  private def lanesDoesNotOverlap(assetType: Int, linkIds: Set[String]): returnResult = {
    LogUtils.time(logger,"lanesDoesNotOverlap"){
      Validators.returnValidationResult("overFlappingLanes",LaneValidatorDao.overFlappingLanes(linkIds))
    }
  }
  
  private def lanesIsNotTooShort(assetType: Int, linkIds: Set[String]): returnResult = {
    LogUtils.time(logger,"lanesIsNotTooShort"){
      Validators.returnValidationResult("laneTooShort",LaneValidatorDao.laneTooShort(linkIds)) 
    }
    
  }
  
  private def incoherentLaneCode(assetType: Int, linkIds: Set[String]): returnResult = {
    LogUtils.time(logger,"incoherentLaneCode"){
      Validators.returnValidationResult("incoherentLaneCode",LaneValidatorDao.incoherentLane(linkIds))
    }
    
  }
}
