package focusedreading.sqlite

import java.io.File
import java.sql._

import com.typesafe.scalalogging.LazyLogging
import jdk.internal.org.objectweb.asm.util.Printer
import focusedreading.ir.{LuceneIRStrategy, Query, QueryStrategy}
import focusedreading.{Connection => _, _}

import scala.util.{Failure, Success, Try}

/**
  * Class that encapsulates the SQLite access related to Lucene queries
  * Created by enrique on 21/02/17.
  */


class LuceneDataAccess(sqlitePath:String, val indexDir:String) extends LazyLogging with LuceneIRStrategy{

  // Load the JDBC driver
  Class.forName("org.sqlite.JDBC")

  // DDL Commands
  val queryTable =
    """ CREATE TABLE Queries (
      |   id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
      |   pa TEXT NOT NULL,
      |   pb TEXT,
      |   type TEXT NOT NULL,
      |   UNIQUE (pa, pb, type)
    | );""".stripMargin

  val queryResultTable =
    """ CREATE TABLE QueryResults (
      |   queryid INTEGER NOT NULL,
      |   pmcid TEXT NOT NULL,
      |   FOREIGN KEY(queryid) REFERENCES Queries(id),
      |   PRIMARY KEY(queryid, pmcid)
    | );""".stripMargin

  val ddlStatements = Seq(queryTable, queryResultTable)
  ////////////////


  private def getConnection = DriverManager.getConnection(s"jdbc:sqlite:$sqlitePath");

  def createDatabase(): Unit ={

    logger.info(s"Creating database in $sqlitePath")
    val connection:Connection = getConnection

    // Create all the tables
    for(command <- ddlStatements){
      val statement:Statement = connection.createStatement()
      statement.executeUpdate(command)
      statement.close
    }

    connection.close
    logger.info("Finished creating database")
  }

  val insertQueryCommand = "INSERT INTO Queries(pa, pb, type) VALUES(?, ?, ?);"
  val insertQueryResults = "INSERT INTO QueryResults(queryid, pmcid) VALUES(?, ?);"

  def insert(q:Query): Unit ={

    val conn = getConnection

    val selectStatement = q.B match {
      case Some(b) => conn.prepareStatement("SELECT id FROM Queries WHERE pa = ? AND pb = ? AND type = ?")
      case None => conn.prepareStatement("SELECT id FROM Queries WHERE pa = ? AND pb IS NULL AND type = ?")
    }

    selectStatement.setString(1, q.A.id)

    q.B match {
      case Some(b) =>
        selectStatement.setString(2, b.id)
        selectStatement.setString(3, q.strategy.toString)
      case None =>
        selectStatement.setString(2, q.strategy.toString)
    }

    // Only continue if this result set is empty
    val rs = selectStatement.executeQuery
    if(!rs.next()){
      // Test wether the record already exists
      // Execute the lucene query to get the results
      val resultsTry = Try(this.informationRetrival(q))

      resultsTry match {
        case Success(results) => {


          try{
            val statement = conn.prepareStatement(insertQueryCommand)
            statement.setString(1, q.A.id)

            q.B match {
              case Some(b) =>
                statement.setString(2, b.id)
              case None =>
                statement.setNull(2, java.sql.Types.VARCHAR)
            }

            statement.setString(3, q.strategy.toString)
            statement.executeUpdate

            // Get the qid
            val x = conn.prepareStatement("SELECT last_insert_rowid();");
            val rs = x.executeQuery()
            rs.next
            val qid = rs.getInt(1)

            val statement2 = conn.prepareStatement(insertQueryResults)
            for(r <- results){
              statement2.setInt(1, qid)
              statement2.setString(2, r._1) // TODO: Store the name IR Score into the tables as well
              statement2.addBatch
            }

            try{
              conn.setAutoCommit(false)
              statement2.executeBatch
              conn.setAutoCommit(true)
            }catch {
              case e:Exception =>
                logger.error(s"Problem storing results for $q")
            }

          } catch {
            case e:Exception =>
              logger.error(s"Problem storing results for $q")
          }



        }
        case Failure(e) => logger.error(s"Error doing information retrival for $q")

      }
    }

    conn.close

  }




}

// TODO: Fix this by parameterizing "few" and "many" in a config file
//object BuildLuceneDB extends App {
//  // Get the db path as the first parameter and the gid_pairs as the second
//  val dbPath = args(0)
//  val pairPath = args(1)
//
//  // Instantiate our DB controller
//  val controller = new LuceneDataAccess(dbPath)
//
//  // Create the database
//  if(!new File(dbPath).exists){
//    println("Creating the database ...")
//    controller.createDatabase()
//  }
//
//
//  // Read the pairs as a stream
//  println("Reading the participant pairs list")
//  val pairs = io.Source.fromFile(pairPath).getLines().toList.map(_.split('\t'))
//
//  // Go into the populating loop
//  println("Executing the queries ...")
//  var i = 0
//  val amount = 4*pairs.size
//  for(pair <- pairs){
//    // Create the query instances per pair
//    val queries = Seq(Query(QueryStrategy.Spatial, Participant("", pair(0)), Some(Participant("", pair(1)))),
//      Query(QueryStrategy.Conjunction, Participant("", pair(0)), Some(Participant("", pair(1)))),
//      Query(QueryStrategy.Singleton, Participant("", pair(0)), None),
//      Query(QueryStrategy.Singleton, Participant("", pair(1)), None)
//    )
//
//    // Insert them into the database
//    queries foreach {
//      q =>
//        i += 1
//        controller.insert(q)
//    }
//
//    if(i % 40 == 0){
//      println(s"Processed $i queries out of $amount")
//    }
//  }
//
//}
