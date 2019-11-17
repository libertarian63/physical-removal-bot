import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

import com.bot4s.telegram.api.RequestHandler
import com.bot4s.telegram.methods.KickChatMember
import com.bot4s.telegram.models.ChatId
import com.bot4s.telegram.models.User
import slogging.LazyLogging

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success

class UserVerificationService(client: RequestHandler[Future])(implicit ec: ExecutionContext) extends LazyLogging {
  private val threadPool = Executors.newScheduledThreadPool(10)
  private val unverifiedUsers: mutable.Map[(ChatId, User), ScheduledFuture[_]] = mutable.Map.empty[(ChatId, User), ScheduledFuture[_]]
  
  def +=(chatId: ChatId, user: User): Unit ={
    logger.debug(s"New users: $user")
    scheduleDeleting(chatId, user)
  }
  
  def ++=(chatId: ChatId, users: Array[User]): Unit ={
    logger.debug(s"New users: ${users.mkString}")
    users.foreach(scheduleDeleting(chatId, _))
  }
  
  def contains(chatId: ChatId, user: User): Boolean ={
    unverifiedUsers.contains(chatId, user)
  }

  def approve(chatId: ChatId, user: User): Option[ScheduledFuture[_]] = {
    unverifiedUsers(chatId, user).cancel(true)
    unverifiedUsers.remove(chatId, user)
  }

  def disapprove(chatId: ChatId, user: User): Unit = {
    unverifiedUsers(chatId, user).cancel(false)
    deleteUser(chatId, user).run()
  }
  
  private def scheduleDeleting(chatId: ChatId, user: User): Unit ={
    logger.debug(s"Start delete scheduler for user: ${user.username}")
    unverifiedUsers.put((chatId, user), threadPool.schedule(deleteUser(chatId, user), 1L, TimeUnit.MINUTES))
    logger.debug(s"Scheduled for user: ${user.username}")
  }
  
  private def deleteUser(chatId: ChatId, user: User): Runnable = () => {
    unverifiedUsers.remove(chatId, user)
    client(KickChatMember(chatId = chatId, userId = user.id)) onComplete {
      case Failure(ex) => logger.error(
        s"""Error while deleting ${user.username}[${user.id}] from $chatId
           |cause ${ex.getLocalizedMessage}""".stripMargin, ex)
      case Success(result) => logger.info(s"${user.username} ${if(!result) "un"}deleted!")
    }
  }
}
