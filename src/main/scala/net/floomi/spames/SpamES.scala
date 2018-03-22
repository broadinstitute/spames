package net.floomi.spames

import java.util.Calendar
import java.util.concurrent.Executors

import com.sksamuel.elastic4s.http.search.{SearchHit, SearchResponse}
import org.apache.http.HttpHost
import com.sksamuel.elastic4s.{ElasticsearchClientUri, RefreshPolicy}
import com.sksamuel.elastic4s.http.{HttpClient, HttpExecutable, RequestFailure, RequestSuccess}
import org.elasticsearch.client.RestClient

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Random

/*
 * you probably wanna run this first
 * docker run -p 9200:9200 -p 9300:9300 -d -e "discovery.type=single-node" docker.elastic.co/elasticsearch/elasticsearch:6.2.3
 */

object SpamES extends App {

  // you must import the DSL to use the syntax helpers
  import com.sksamuel.elastic4s.http.ElasticDsl._

  val client = {
    val restClient = RestClient
      .builder(new HttpHost("localhost", 9200, "http"))
      // The default timeout is 100̈ms, which is too slow for query operations.
      .setRequestConfigCallback( { configBuilder =>
      configBuilder
        .setConnectionRequestTimeout(5.minutes.toMillis.toInt)
        .setConnectTimeout(5.minutes.toMillis.toInt)
        .setSocketTimeout(5.minutes.toMillis.toInt)
      }
    )
      .build()
    HttpClient.fromRestClient(restClient)
  }

  def randomValue(): Any = {
    Random.nextInt(7) match {
      case 0 => Random.alphanumeric.take(Random.nextInt(100)).mkString
      case 1 => Random.alphanumeric.take(Random.nextInt(250)).mkString
      case 2 => Random.alphanumeric.take(Random.nextInt(500)).mkString
      case 3 => Random.nextDouble()
      case 4 => Random.nextInt()
      case 5 => Random.nextBoolean()
      case 6 => (1 to Random.nextInt(100)).map(_ => Random.alphanumeric.take(Random.nextInt(100)).mkString)
    }
  }

  def generateFields(i: Int): Map[String, Any] = {
    val baseFields = Map(
      "name" -> s"entity_$i",
      "numeric" -> 42.0,
      "string" -> "foo",
      "simple_array" -> Seq(1,2,3,4,5,6),
      "humongous_array" -> (1 to 1000).map ( _ => s"gs://${Random.alphanumeric.take(64).mkString}" )
    )

    val randomFields = (1 to Random.nextInt(100)).map { _ =>
      Random.alphanumeric.take(10).mkString -> randomValue
    }

    baseFields ++ randomFields
  }

  def populateEntities(numEntities: Int) = {
    val entities = (0 until numEntities).map { i =>
      indexInto("myindex"/"entity").id(s"entity_$i").fields(generateFields(i))
    }

    //we're awaiting in the main thread
    execute { bulk(entities).refresh(RefreshPolicy.WaitFor) }
  }

  def singleUpdate(x:Int): Map[String, Any] = {
    Random.nextInt(4) match {
      case 0 => Map("numeric" -> Random.nextDouble())
      case 1 => Map("string" -> Random.alphanumeric.take(10))
      case 2 => Map("simple_array" -> Seq(1,2,3,4,5, Random.nextInt(10)))
      case 3 => Map("humongous_array" -> (1 to 1000).map ( _ => s"gs://${Random.alphanumeric.take(64).mkString}" ))
    }
  }

  class ElasticException(rf: RequestFailure) extends RuntimeException(rf.error.reason)
  class NoResultsException() extends RuntimeException()
  class TooManyResultsException(hits: Array[SearchHit], msg: String) extends RuntimeException(s"$msg hits: ${hits.map(_.id).mkString(" ")}")

  def extractSingleResult(res: Either[RequestFailure, RequestSuccess[SearchResponse]]): Future[SearchHit] = res match {
    case Left(rf: RequestFailure) =>
      Future.failed(new ElasticException(rf))
    case Right(rs: RequestSuccess[SearchResponse]) =>
      rs.result.totalHits match {
        case 0 => Future.failed(new NoResultsException())
        case 1 => Future.successful(rs.result.hits.hits.head)
        case _ => Future.failed(new TooManyResultsException(rs.result.hits.hits, rs.result.totalHits.toString))
      }
  }

  def parallelWrites(numEntities: Int, numUpdates: Int, parallelism: Int): Future[Unit] = {
    implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(parallelism))

    //traverse executes in parallel
    Future.traverse(1 to numUpdates){ _ =>

      for {
        chosen <- Future.successful{ Random.nextInt(numEntities) }
        //sometimes this search fails to show up any results, so we'll switch to using the ID directly
        //res <- execute { searchWithType("myindex"/"entity").termQuery("name", s"entity_$chosen").timeout(5.minutes) }
        //extracted <- extractSingleResult(res)
        _ <- execute { update(s"entity_$chosen").in("myindex"/"entity").doc(singleUpdate(chosen)).timeout(5.minutes).refresh(RefreshPolicy.Immediate) }
      } yield ()

    }.map(_ => ())

  }

  def execute[T, U](request: T)(implicit exec: HttpExecutable[T, U], executionContext: ExecutionContext = ExecutionContext.Implicits.global): Future[RequestSuccess[U]] = {
    client.execute(request) flatMap {
      case Left(fail: RequestFailure) => Future.failed(new ElasticException(fail))
      case Right(success: RequestSuccess[U]) => Future.successful(success)
    }
  }

  def run() = {
    val numEntities = 1000

    //make the index, field limit TURN IT UP
    val makeIndex = execute { createIndex("myindex").indexSetting("mapping.total_fields.limit", 1000000) }.await

    //generate some entities
    println(s"${Calendar.getInstance.getTime} populating $numEntities entities...")
    val upload = populateEntities(numEntities).await(Duration.Inf)
    println(s"${Calendar.getInstance.getTime} populated")

    //refresh
    execute { refreshIndex("myindex") }.await

    //make a ton of writes to them

    //NOTE: Some failure cases that start showing up >50 updates:
    //1. Sometimes search incorrectly returns no hits, possibly because the object has fallen off the index?
    // --- RefreshPolicy.Immediate helps, but doesn't solve it
    // --- is switching to IDs better?
    //2. Timeout exceptions -- fixed by increasing timeout
    val numWrites = 1000
    val numEntitiesWrittenTo = 10
    val numThreads = 20
    println(s"${Calendar.getInstance.getTime} doing $numWrites writes to $numEntitiesWrittenTo entities in $numThreads threads...")
    parallelWrites(numEntitiesWrittenTo, numWrites, numThreads).await(Duration.Inf)
    println(s"${Calendar.getInstance.getTime} done")

    //refresh again
    execute { refreshIndex("myindex") }.await

    println(s"${Calendar.getInstance.getTime} querying...")
    val res = execute { searchWithType("myindex"/"entity").termQuery("name", s"entity_${Random.nextInt(numEntities)}") }.await
    println(s"${Calendar.getInstance.getTime} done")
    println(res.result.hits.hits.head.sourceAsString)
  }

  def deleteESIndex() = {
    //clean up after ourselves
    execute {
      deleteIndex("myindex")
    }.await
  }

  def search() = {
    val numEntities = 1000
    println(s"${Calendar.getInstance.getTime} querying...")
    val res = client.execute { searchWithType("myindex"/"entity").termQuery("name", s"entity_${Random.nextInt(numEntities)}") }.await
    println(s"${Calendar.getInstance.getTime} done")
    println(res.right.get.result.hits.hits.head.sourceAsString)
  }

  try {
    run()
    //search()
  } catch {
    case e: Exception => e.printStackTrace()
  } finally {
    deleteESIndex()
    client.close()
    sys.exit()
  }


}