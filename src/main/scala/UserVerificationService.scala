import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

import com.bot4s.telegram.api.RequestHandler
import com.bot4s.telegram.methods.KickChatMember
import com.bot4s.telegram.models.ChatId
import com.bot4s.telegram.models.User
import slogging.StrictLogging

import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success

class UserVerificationService(client: RequestHandler[Future])(implicit ec: ExecutionContext) extends StrictLogging {
  private val threadPool = Executors.newScheduledThreadPool(10)
  private val unverifiedUsers: TrieMap[(ChatId, User), ScheduledFuture[_]] = TrieMap.empty[(ChatId, User), ScheduledFuture[_]]
  
  def +=(chatId: ChatId, user: User): Unit ={
    logger.debug(s"New users: $user in chat $chatId")
    scheduleDeleting(chatId, user)
  }
  
  def ++=(chatId: ChatId, users: Array[User]): Unit ={
    logger.debug(s"New users: ${users.mkString(", ")} in chat $chatId")
    users.foreach(scheduleDeleting(chatId, _))
  }
  
  def contains(chatId: ChatId, user: User): Boolean ={
    val contains = unverifiedUsers.contains(chatId, user)
    logger.debug(s"Unverified users ${if(!contains)"not " else ""}contains ${user.username.getOrElse(user.firstName)} in chat $chatId")
    contains
  }

  def approve(chatId: ChatId, user: User): Unit = {
    unverifiedUsers(chatId, user).cancel(true)
    unverifiedUsers.remove((chatId, user))
    logger.debug(s"Approved user ${user.username.getOrElse(user.firstName)} in chat $chatId")
  }

  def disapprove(chatId: ChatId, user: User): Unit = {
    unverifiedUsers(chatId, user).cancel(false)
    deleteUser(chatId, user).run()
    logger.debug(s"Disapprove user ${user.username.getOrElse(user.firstName)} in chat $chatId")
  }
  
  private def scheduleDeleting(chatId: ChatId, user: User): Unit ={
    unverifiedUsers.put((chatId, user), threadPool.schedule(deleteUser(chatId, user), 1L, TimeUnit.MINUTES))
    logger.debug(s"Scheduled for user: ${user.username.getOrElse(user.firstName)} in chat $chatId")
  }
  
  private def deleteUser(chatId: ChatId, user: User): Runnable = () => {
    if(unverifiedUsers.contains(chatId, user)) {
      unverifiedUsers.remove((chatId, user))
      client(KickChatMember(chatId = chatId, userId = user.id)) onComplete {
        case Failure(ex) => logger.error(
          s"""Error while deleting ${user.username.getOrElse(user.firstName)}[${user.id}] from $chatId
             |cause ${ex.getLocalizedMessage}""".stripMargin, ex)
        case Success(result) => logger.info(s"${user.username.getOrElse(user.firstName)} ${if(!result) "un" else ""}deleted in chat $chatId!")
      }
    } else {
      logger.debug(s"User: ${user.username.getOrElse(user.firstName)} can't be deleted from chat $chatId cause it doesn't contains in unverified users.")
    }
  }
}
