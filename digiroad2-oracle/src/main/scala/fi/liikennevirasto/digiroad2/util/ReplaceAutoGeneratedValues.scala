package fi.liikennevirasto.digiroad2.util

import fi.liikennevirasto.digiroad2.postgis.PostGISDatabase
import slick.driver.JdbcDriver.backend.Database
import Database.dynamicSession
import fi.liikennevirasto.digiroad2.asset.AutoGeneratedUsername
import org.slf4j.LoggerFactory
import slick.jdbc.StaticQuery.interpolation

// A batch job for one time use to replace vvh-terminology from db and incorrect form 'auto_generation'.
object ReplaceAutoGeneratedUsernames {

  val tablesWithValuesToReplace = Seq("asset", "lane", "lane_attribute", "lane_history", "lane_history_attribute",
  "text_property_value")
  val columnsWithValuesToReplace = Seq("created_by", "modified_by")
  val valuesToReplace = Map("vvh_generated" -> AutoGeneratedUsername.generatedInUpdate, "vvh_modified" -> AutoGeneratedUsername.automaticAdjustment,
    "vvh_mtkclass_default" -> AutoGeneratedUsername.mtkClassDefault, "auto_generation" -> AutoGeneratedUsername.automaticGeneration,
    "dr1conversion" -> AutoGeneratedUsername.dr1Conversion)
  val batchSize = 20000
  private val logger = LoggerFactory.getLogger(getClass)

  def withDynSession[T](f: => T): T = PostGISDatabase.withDynSession(f)
  def withDynTransaction(f: => Unit): Unit = PostGISDatabase.withDynTransaction(f)

  def selectIdsToUpdate(tableName: String, columnName: String, oldValue: String, transActionOpen: Boolean) = {
    if (transActionOpen) {
      sql"select id from #${tableName} where #${columnName} = '#${oldValue}' order by id".as[Long].list
    } else {
      withDynSession(sql"select id from #${tableName} where #${columnName} = '#${oldValue}' order by id".as[Long].list)
    }
  }

  def updateColumnValues(tableName: String, columnName: String, ids: Seq[Long], newValue: String, transactionOpen: Boolean) = {
    val idsAsString = ids.mkString("(", ",", ")")
    if (transactionOpen) {
      sqlu"update #${tableName} set #${columnName} = '#${newValue}' where id in #${idsAsString}".execute
    } else {
      withDynTransaction(sqlu"update #${tableName} set #${columnName} = '#${newValue}' where id in #${idsAsString}".execute)
    }
  }

  def main(transactionOpen: Boolean = false) = {
    tablesWithValuesToReplace.foreach {
      tableName => columnsWithValuesToReplace.foreach {
        columnName => valuesToReplace.foreach {
          value =>
            val idGroups = selectIdsToUpdate(tableName, columnName, value._1, transactionOpen).grouped(batchSize).toSeq
            idGroups.foreach {
              ids =>
              try {
                updateColumnValues(tableName, columnName, ids, value._2, transactionOpen)
                logger.info(s"Updated ${value._1} to ${value._2} in table $tableName column $columnName for ids between " +
                  s"${ids.head} and ${ids.last}.")
              } catch {
                case e => logger.error(s"Update from ${value._1} to ${value._2} in table $tableName column $columnName " +
                  s"for ids between ${ids.head} and ${ids.last} failed due to ${e.getMessage}.")
              }
            }
        }
      }
    }
  }
}

