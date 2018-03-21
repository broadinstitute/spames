package net.floomi.spames

import com.sksamuel.elastic4s.ElasticsearchClientUri
import com.sksamuel.elastic4s.http.HttpClient
import com.sksamuel.elastic4s.http.search.SearchResponse
import org.elasticsearch.action.support.WriteRequest.RefreshPolicy

import scala.util.Random

/*
 * you probably wanna run this first
 * docker run -p 9200:9200 -p 9300:9300 -d -e "discovery.type=single-node" docker.elastic.co/elasticsearch/elasticsearch:6.2.3
 */

object SpamES extends App {

  // you must import the DSL to use the syntax helpers
  import com.sksamuel.elastic4s.http.ElasticDsl._

  val client = HttpClient(ElasticsearchClientUri("localhost", 9200))

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
    val entities = (1 to numEntities).map { i =>
      indexInto("myIndex" / "entity").fields(generateFields(i))
    }

    client.execute { bulk(entities).refresh(RefreshPolicy.WAIT_UNTIL) }.await
  }

  def run() = {
    //generate some entities
    populateEntities(1000)

    //make a ton of writes to them
    //TODO

    //done now
    cleanup
  }

  def cleanup() = {
    //clean up after ourselves
    client.execute {
      deleteIndex("myindex")
    }

    client.close()
  }

  run()

}