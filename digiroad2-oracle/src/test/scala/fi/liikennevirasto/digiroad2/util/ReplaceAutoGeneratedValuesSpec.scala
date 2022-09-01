package fi.liikennevirasto.digiroad2.util

import org.scalatest.{FunSuite, Matchers}
import slick.driver.JdbcDriver.backend.Database
import Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation

class ReplaceAutoGeneratedValuesSpec extends FunSuite with Matchers {

  def runWithRollback(test: => Unit): Unit = {
    TestTransactions.runWithRollback()(test)
  }

  test("Update 'vvh_generated' and 'vvh_modified'") {
    runWithRollback {
      val ids = List.tabulate(1000)(_ +1000000000)
      ids.foreach { id =>
        sqlu"insert into ASSET (ID,ASSET_TYPE_ID,CREATED_BY,MUNICIPALITY_CODE, MODIFIED_BY) values ($id,220,'vvh_generated',235, 'vvh_modified')".execute
      }
      ReplaceAutoGeneratedValues.main(true)
      val idsAsString = ids.mkString("(", ",", ")")
      val updatedValues = sql"select CREATED_BY, MODIFIED_BY from asset where id in #$idsAsString".as[(String, String)].list

      updatedValues.foreach { values =>
        values._1 should be("generated_in_update")
        values._2 should be("automatic_adjustment")
      }
    }
  }

  test("Update 'vvh_mtkclass_default' and 'auto_generation'") {
    runWithRollback {
      val ids = List.tabulate(1000)(_ +1000000000)
      ids.foreach { id =>
        sqlu"insert into ASSET (ID,ASSET_TYPE_ID,CREATED_BY,MUNICIPALITY_CODE, MODIFIED_BY) values ($id,220,'vvh_mtkclass_default',235, 'auto_generation')".execute
      }
      ReplaceAutoGeneratedValues.main(true)
      val idsAsString = ids.mkString("(", ",", ")")
      val updatedValues = sql"select CREATED_BY, MODIFIED_BY from asset where id in #$idsAsString".as[(String, String)].list

      updatedValues.foreach { values =>
        values._1 should be("mtk_class_default")
        values._2 should be("automatic_generation")
      }
    }
  }
}

