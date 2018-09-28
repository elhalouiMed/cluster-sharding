package sample.blog
import java.time.OffsetDateTime
import scala.collection.immutable
import scala.concurrent.duration._
import akka.actor.ActorLogging
import akka.actor.Props
import akka.actor.PoisonPill
import akka.actor.ReceiveTimeout
import akka.cluster.sharding.{ClusterSharding, ShardRegion}
import akka.cluster.sharding.ShardRegion.Passivate
import akka.persistence.{PersistentActor, SnapshotOffer}
import sample.blog.Post.Remove
import scala.collection.mutable.ListBuffer

object AuthorListing {

  def props(): Props = Props(new AuthorListing)
  sealed trait Command {

    def author: String
  }
  case class PostSummary(author: String, postId: String, title: String, published: OffsetDateTime) extends Command
  case class PostSummaryEx(author: String, postId: String, title: String, published: OffsetDateTime, persisted: OffsetDateTime) extends Command
  object PostSummaryEx {

    def fromPostSummary(ps: PostSummary): PostSummaryEx =
      PostSummaryEx(ps.author, ps.postId, ps.title, ps.published, null)
  }
  case class GetPosts(author: String) extends Command
  case class Posts(list: ListBuffer[PostSummaryEx])
  case class RemovePost(author: String, postId: String) extends Command
  case class RemoveFirstN(author: String, n: Int) extends Command
  val idExtractor: ShardRegion.ExtractEntityId = {
    case c: Command => (c.author, c)
  }

  val shardResolver: ShardRegion.ExtractShardId = msg => msg match {
    case c: Command => (math.abs(c.author.hashCode) % 100).toString
  }

  val shardName: String = "AuthorListing"
}
class AuthorListing extends PersistentActor with ActorLogging {
  import AuthorListing._

  override def persistenceId: String = self.path.parent.name + "-" + self.path.name

  // passivate the entity when no activity
  context.setReceiveTimeout(2.minutes)

  var posts: ListBuffer[PostSummaryEx] = ListBuffer.empty[PostSummaryEx]

  val snapShotInterval = 5

  private val postRegion = ClusterSharding(context.system).shardRegion(Post.shardName)

  def receiveCommand = {
    case s: PostSummaryEx =>
      persist(s.copy(persisted = OffsetDateTime.now)) { evt =>
        posts += evt
        log.info("Post added to {}'s list: {}", s.author, s.title)
        if (posts.size % snapShotInterval == 0 && lastSequenceNr != 0)
          saveSnapshot(posts)
      }
    case r: RemovePost =>
      persist(r) { evt =>
        val post = posts.filter(_.postId == r.postId).head
        posts = posts.filter(ps => ps.postId != r.postId)
        log.info("Post removed {} from {}'s list", r.postId, post.author)
      }
    case r: RemoveFirstN => persist(r) {
      evt =>
        posts.take(evt.n).foreach(ps => postRegion ! Remove(ps.postId))
        posts = posts.drop(evt.n)
        log.warning("First {} posts removed in AuthorListing", evt.n)
    }
    case GetPosts(_) =>
      sender() ! Posts(posts)
    case ReceiveTimeout => context.parent ! Passivate(stopMessage = PoisonPill)
  }

  override def receiveRecover: Receive = {
    case evt: PostSummaryEx =>
      posts :+= evt
    case r: RemovePost =>
      posts = posts.filter(ps => ps.postId != r.postId)
    case r: RemoveFirstN =>
      posts = posts.drop(r.n)
    case so: SnapshotOffer =>
      so.snapshot match {
        case l: ListBuffer[PostSummary] if l.nonEmpty && l.head.isInstanceOf[PostSummary]=>
          posts = l.map(a => PostSummaryEx.fromPostSummary(a))
        case l: ListBuffer[PostSummaryEx] =>
          posts = l
      }
  }
}