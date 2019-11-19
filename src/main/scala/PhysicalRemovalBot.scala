import cats.implicits._
import com.bot4s.telegram.api.RequestHandler
import com.bot4s.telegram.api.declarative.Callbacks
import com.bot4s.telegram.api.declarative.Commands
import com.bot4s.telegram.api.declarative.InlineQueries
import com.bot4s.telegram.clients.FutureSttpClient
import com.bot4s.telegram.future.Polling
import com.bot4s.telegram.future.TelegramBot
import com.bot4s.telegram.methods.EditMessageReplyMarkup
import com.bot4s.telegram.models.InlineKeyboardButton
import com.bot4s.telegram.models.InlineKeyboardMarkup
import com.bot4s.telegram.models.Message
import com.softwaremill.sttp.SttpBackend
import com.softwaremill.sttp.okhttp.OkHttpFutureBackend

import scala.concurrent.Future
import scala.util.Failure

class PhysicalRemovalBot(val token: String) extends TelegramBot
  with Polling
  with Commands[Future]
  with InlineQueries[Future]
  with Callbacks[Future] {

    implicit val backend: SttpBackend[Future, Nothing] = OkHttpFutureBackend()
    override val client: RequestHandler[Future] = new FutureSttpClient(token)
    val userRemovalService = new UserVerificationService(client)
    var started: Boolean = false

    def newChatUsers(implicit msg: Message) = msg.newChatMembers.toList.flatten

    onCommand("start") { implicit msg =>
      started = true
      reply("Вертолёт готов к вылету!").void
    }

    onMessage { implicit msg =>
      val answers: List[Future[Message]] = newChatUsers.filter(!_.isBot && started) map { user =>
        userRemovalService += (msg.chat.id, user)
        reply(
          s"""
             |Стой, ${user.username.getOrElse(user.firstName)}!
             |Если хочешь продолжить жить, то ответь на вопрос. Иначе тебе грозит физическое удаление из чата!
             |Сколько у человека пальцев на одной руке?
             |""".stripMargin, replyMarkup = Some(markupSwitcher(user.id)))
      }

      answers.foldRight(unit)(_ *> _)
    }

    onMessage { implicit msg =>
      val answers: Unit = msg.from.filter { user =>
        started && userRemovalService.contains(msg.chat.id, user) && !newChatUsers.contains(user)
      } foreach { user =>
        userRemovalService.disapprove(msg.chat.id, user)
        reply(s"Нам придётся прокатиться на вертолёте, ${user.username.getOrElse(user.firstName)}!")
      }

      answers.pure[Future]
    }

    val TAG = "ANSWER"

    def tag: String => String = prefixTag(TAG)

    def markupSwitcher(userId: Int): InlineKeyboardMarkup = {
      InlineKeyboardMarkup.singleRow(Seq(
        InlineKeyboardButton.callbackData(
          s"5",
          tag(s"T$userId")),
        InlineKeyboardButton.callbackData(
          s"25",
          tag(s"F$userId"))))
    }

    onCallbackWithTag(TAG) { implicit cbq =>
      logger.debug(s"Received answer: $cbq")
      val answer: Option[String] = for {
        data <- cbq.data if data === s"T${cbq.from.id}"
        msg <- cbq.message
        chatId = msg.chat.id
      } yield {
        logger.debug(s"Data: $data")
        userRemovalService.approve(chatId, cbq.from)
        request(EditMessageReplyMarkup(Some(msg.source), Some(msg.messageId), replyMarkup = Some(InlineKeyboardMarkup(Seq.empty)))) onComplete {
          case Failure(exception) => logger.error(s"Request for edit markup failed! Cause ${exception.getLocalizedMessage}", exception)
          case otherwise => logger.debug(s"Success: $otherwise")
        }
        """
          |Ты отлично справился! Добро пожаловать в самарскую комунну, с её правилами можешь ознакомиться в описании чата.
        """.stripMargin
      }

      ackCallback(answer.orElse(Some("Попробуй ещё раз."))).void
    }
}