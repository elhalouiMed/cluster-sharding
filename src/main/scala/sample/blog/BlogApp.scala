package sample.blog
import scala.concurrent.duration._
import com.typesafe.config.ConfigFactory
import akka.actor.ActorIdentity
import akka.actor.ActorPath
import akka.actor.ActorSystem
import akka.actor.Identify
import akka.actor.Props
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import akka.pattern.ask
import akka.persistence.journal.leveldb.SharedLeveldbJournal
import akka.persistence.journal.leveldb.SharedLeveldbStore
import akka.util.Timeout
import sample.blog.read.PostEventListener
import slick.basic.DatabaseConfig
import slick.jdbc.JdbcProfile
import slick.jdbc.PostgresProfile.api._
object BlogApp {
  def main(args: Array[String]): Unit = {
    if (args.isEmpty)
      startup(Seq("2551", "2552", "0"))
    else
      startup(args)
  }

  def startup(ports: Seq[String]): Unit = {
    ports foreach { port =>
      // Override the configuration of the port
      val config = ConfigFactory.parseString("akka.remote.netty.tcp.port=" + port).
        withFallback(ConfigFactory.load())
      // Create an Akka system
      val system = ActorSystem("ClusterSystem", config)

      val authorListingRegion = ClusterSharding(system).start(
        typeName = AuthorListing.shardName,
        entityProps = AuthorListing.props(),
        settings = ClusterShardingSettings(system),
        extractEntityId = AuthorListing.idExtractor,
        extractShardId = AuthorListing.shardResolver)
      ClusterSharding(system).start(
        typeName = Post.shardName,
        entityProps = Post.props(authorListingRegion),
        settings = ClusterShardingSettings(system),
        extractEntityId = Post.idExtractor,
        extractShardId = Post.shardResolver)
      if (port != "2551" && port != "2552"){
        system.actorOf(Props[Bot], "bot")
        val database = Database.forConfig("slick.db", config)
        system.actorOf(PostEventListener.props(database))
      }

    }

  }
}

