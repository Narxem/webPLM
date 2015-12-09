package actors

import java.util.{ Locale, Properties, UUID }
import scala.concurrent.Future
import akka.actor._
import akka.pattern.{ ask, pipe }
import akka.util.Timeout
import scala.concurrent.duration._
import codes.reactive.scalatime.{ Duration, Instant }
import json._
import log.PLMLogger
import models.GitHubIssueManager
import models.User
import models.daos.UserDAORestImpl
import models.execution.ExecutionManager
import models.lesson.Lecture
import play.api.Logger
import play.api.Play
import play.api.Play.current
import play.api.i18n.Lang
import play.api.libs.json._
import plm.core.lang.ProgrammingLanguage
import plm.core.model.lesson.Exercise
import plm.core.model.lesson.Exercise.WorldKind
import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.postfixOps
import LessonsActor._
import ExercisesActor._
import ExecutionActor._
import GitActor._
import SessionActor._
import models.lesson.Lesson
import play.api.libs.functional.syntax._
import plm.core.model.lesson.ExecutionProgress
import org.xnap.commons.i18n.{ I18n, I18nFactory }
import models.ProgrammingLanguages
import scala.concurrent.Await
import plm.universe.{ Entity, Operation, World }
import json.operation.OperationToJson

object PLMActor {
  def props(pushActor: ActorRef, executionManager: ExecutionManager, userAgent: String, actorUUID: String, gitID: String, newUser: Boolean, preferredLang: Option[Lang], lastProgLang: Option[String], trackUser: Option[Boolean])(out: ActorRef) = Props(new PLMActor(pushActor, executionManager, userAgent, actorUUID, gitID, newUser, preferredLang, lastProgLang, trackUser, out))
  def propsWithUser(pushActor: ActorRef, executionManager: ExecutionManager, userAgent: String, actorUUID: String, user: User)(out: ActorRef) = Props(new PLMActor(pushActor, executionManager, userAgent, actorUUID, user, out))
}

class PLMActor (
    pushActor: ActorRef,
    executionManager: ExecutionManager, 
    userAgent: String, 
    actorUUID: String, 
    gitID: String, 
    newUser: Boolean, 
    preferredLang: Option[Lang], 
    lastProgLang: Option[String], 
    trackUser: Option[Boolean], 
    out: ActorRef)
  extends Actor {

  def this(pushActor: ActorRef, executionManager: ExecutionManager, userAgent: String, actorUUID: String, user: User, out: ActorRef) {
    this(pushActor, executionManager, userAgent, actorUUID, user.gitID.toString, false, user.preferredLang, user.lastProgLang, user.trackUser, out)
    setCurrentUser(user)
  }

  implicit val timeout = Timeout(5 seconds)

  var optCurrentLesson: Option[String] = None
  var optCurrentExercise: Option[Exercise] = None
  var optCurrentUser: Option[User] = None
  var currentProgLang: ProgrammingLanguage = initProgLang(lastProgLang)
  var currentHumanLang: Lang = initHumanLang(preferredLang)

  val lessonsActor: ActorRef = context.actorOf(LessonsActor.props)
  val exercisesActor: ActorRef = context.actorOf(ExercisesActor.props)
  val executionActor: ActorRef = context.actorOf(ExecutionActor.props(currentHumanLang))
  val gitActor: ActorRef = context.actorOf(GitActor.props(pushActor, "dummy", None, userAgent))
  val sessionActor: ActorRef = context.actorOf(SessionActor.props(gitActor, ProgrammingLanguages.programmingLanguages))

  val i18n: I18n = I18nFactory.getI18n(getClass(),"org.plm.i18n.Messages", new Locale("en"), I18nFactory.FALLBACK)

  val gitHubIssueManager: GitHubIssueManager = new GitHubIssueManager

  val availableLangs: Seq[Lang] = Lang.availables

  sendProgLangs
  sendHumanLangs

  var currentGitID: String = null
  setCurrentGitID(gitID, newUser)

  var optIdle: Option[Instant] = None

  registerActor

  def receive = {
    case msg: JsValue =>
      Logger.debug("Received a message")
      Logger.debug(msg.toString())
      val cmd: Option[String] = (msg \ "cmd").asOpt[String]
      cmd.getOrElse(None) match {
        case "signIn" | "signUp" =>
          val currentUser: User = (msg \ "user").asOpt[User].get
          setCurrentUser(currentUser)
          gitActor ! SwitchUser(currentUser.gitID, currentUser.trackUser)
          currentUser.preferredLang match {
            case Some(newLang: Lang) =>
              updateHumanLang(newLang.code)
            case _ =>
              savePreferredLang
          }
          currentUser.lastProgLang match {
          case Some(progLang: String) =>
            updateProgLang(progLang)
          case _ =>
            saveLastProgLang
          }
        case "signOut" =>
          clearCurrentUser
          gitActor ! SwitchUser(currentGitID, None)
        case "getLessons" =>
          (lessonsActor ? GetLessonsList).mapTo[Array[Lesson]].map { lessons =>
            val jsonLessons: JsArray = Lesson.arrayToJson(lessons, currentHumanLang)
            sendMessage("lessons", Json.obj(
              "lessons" -> jsonLessons
            ))
          }
        case "getExercisesList" =>
          val optLessonName: Option[String] = (msg \ "args" \ "lessonName").asOpt[String]
          optLessonName match {
            case Some(lessonName: String) =>
              (lessonsActor ? GetExercisesList(lessonName)).mapTo[Array[Lecture]].map { lectures =>
                val jsonLectures: JsArray = Lecture.arrayToJson(lectures, currentHumanLang, currentProgLang)
                sendMessage("lectures", Json.obj(
                  "lectures" -> jsonLectures
                ))
              }
            case _ =>
              Logger.debug("getExercisesList: non-correct JSON")
          }
        case "setProgLang" =>
          val optProgLang: Option[String] = (msg \ "args" \ "progLang").asOpt[String]
          optProgLang match {
            case Some(progLang: String) =>
              updateProgLang(progLang)
              saveLastProgLang
            case _ =>
              Logger.debug("setProgrammingLanguage: non-correct JSON")
          }
        case "setHumanLang" =>
          val optLang: Option[String] =  (msg \ "args" \ "lang").asOpt[String]
          optLang match {
            case Some(lang: String) =>
              updateHumanLang(lang)
              executionActor ! UpdateLang(currentHumanLang)
              savePreferredLang
            case _ =>
              Logger.debug("setLang: non-correct JSON")
          }
        case "getExercise" =>
          val optLessonID: Option[String] = (msg \ "args" \ "lessonID").asOpt[String]
          val optExerciseID: Option[String] = (msg \ "args" \ "exerciseID").asOpt[String]

          optLessonID match {
          case Some(lessonID: String) =>
            (lessonsActor ? LessonExists(lessonID)).mapTo[Boolean].map { lessonExists => 
              if(lessonExists) {
                optExerciseID match {
                case Some(exerciseID: String) =>
                  (lessonsActor ? CheckLessonAndExercise(lessonID, exerciseID)).mapTo[Boolean].map { ok =>
                    if(ok) {
                      switchExercise(lessonID, exerciseID)
                    } else {
                      sendMessage("exerciseNotFound", Json.obj())
                    }
                  }
                case _ =>
                  (lessonsActor ? GetFirstExerciseID(lessonID)).mapTo[String].map { exerciseID =>
                    switchExercise(lessonID, exerciseID)
                  }
                }
              } else {
                Logger.debug("getExercise: tried to access not known lesson")
                sendMessage("lessonNotFound", Json.obj())
              }
            }
          case _ =>
            Logger.debug("getExercise: non-correctJSON")
            sendMessage("lessonNotFound", Json.obj())
          }
        case "runExercise" =>
          val exercise: Exercise = optCurrentExercise.get
          val optCode: Option[String] = (msg \ "args" \ "code").asOpt[String]
          optCode match {
            case Some(code: String) =>
              (executionActor ? StartExecution(out, exercise, currentProgLang, code)).mapTo[ExecutionProgress].map { executionResult =>
                sessionActor ! SetCode(exercise, currentProgLang, code)
                gitActor ! Executed(exercise, executionResult, code, currentHumanLang.language)

                val msgType: Int = if (executionResult.outcome == ExecutionProgress.outcomeKind.PASS) 1 else 0
                val commonErrorID: Int = executionResult.commonErrorID
                val commonErrorText: String = executionResult.commonErrorText
                val msg: String = executionResult.getMsg(i18n)

                val mapArgs: JsValue = Json.obj(
                  "msgType" -> msgType,
                  "msg" -> msg, 
                  "commonErrorID" -> commonErrorID, 
                  "commonErrorText" -> commonErrorText
                )

                sendMessage("executionResult", mapArgs)
              }
            case _ =>
              Logger.debug("runExercise: non-correctJSON")
          }
        case "stopExecution" =>
          // FIXME: Re-implement me
          // plm.stopExecution
        case "runDemo" =>
          optCurrentExercise match {
            case Some(currentExercise: Exercise) =>
              var steps: JsArray = JsArray()
              currentExercise.getWorlds(WorldKind.ANSWER).toArray(Array[World]()).foreach { world =>
                for(i <- 0 to world.getSteps.size-1) {
                  val operations: Array[Operation] = world.getSteps.get(i).toArray(Array[Operation]())
                  steps = steps.append(Json.obj("worldID" -> world.getName, "operations" -> OperationToJson.operationsWrite(operations)))
                }
              }
              sendMessage("demoOperations", Json.obj("buffer" -> steps))
            case _ =>
          }
        case "revertExercise" =>
          // FIXME: Re-implement me
          optCurrentExercise match {
            case Some(currentExercise: Exercise) =>
              currentExercise.getDefaultSourceFile(currentProgLang).getBody
            case _ =>
          }
          /*
          var lecture = plm.revertExercise
          sendMessage("exercise", Json.obj(
              "exercise" -> LectureToJson.lectureWrites(lecture, plm.programmingLanguage, plm.getStudentCode, plm.getInitialWorlds, plm.getSelectedWorldID)
          ))
          */
        case "updateUser" =>
          val optFirstName: Option[String] = (msg \ "args" \ "firstName").asOpt[String]
          val optLastName: Option[String] = (msg \ "args" \ "lastName").asOpt[String]
          val optTrackUser: Option[Boolean] = (msg \ "args" \ "trackUser").asOpt[Boolean]
          (optCurrentUser, optFirstName, optLastName) match {
            case (Some(currentUser: User), Some(firstName:String), Some(lastName: String)) =>
              val newUser = currentUser.copy(
                  firstName = optFirstName,
                  lastName = optLastName,
                  trackUser = optTrackUser
              )
              optCurrentUser = Some(newUser)
              UserDAORestImpl.update(newUser)
              sendMessage("userUpdated", Json.obj())
              optTrackUser match {
                case Some(trackUser: Boolean) =>
                  gitActor ! SetTrackUser(optTrackUser)
                case _ =>
                  Logger.debug("setTrackUser: non-correct JSON")
              }
            case _ =>
              Logger.debug("updateUser: non-correct JSON")
          }
        case "userIdle" =>
          setIdle
        case "userBack" =>
          clearIdle
        case "setTrackUser" =>
          val optTrackUser: Option[Boolean] = (msg \ "args" \ "trackUser").asOpt[Boolean]
          optTrackUser match {
            case Some(trackUser: Boolean) =>
              gitActor ! SetTrackUser(optTrackUser)
              saveTrackUser(trackUser)
            case _ =>
              Logger.debug("setTrackUser: non-correct JSON")
          }
        case "submitBugReport" =>
          var optTitle: Option[String] = (msg \ "args" \ "title").asOpt[String]
          var optBody: Option[String] = (msg \ "args" \ "body").asOpt[String]
          (optTitle, optBody) match {
            case (Some(title: String), Some(body: String)) =>
              gitHubIssueManager.isCorrect(title, body) match {
                case Some(errorMsg: String) =>
                  Logger.debug("Try to post incorrect issue...")
                  Logger.debug("Title: "+title+", body: "+body)
                  sendMessage("incorrectIssue", Json.obj("msg" -> errorMsg))
                case None =>
                  gitHubIssueManager.postIssue(title, body) match {
                    case Some(issueUrl: String) =>
                      Logger.debug("Issue created at: "+ issueUrl)
                      sendMessage("issueCreated", Json.obj("url" -> issueUrl))
                    case None =>
                      Logger.debug("Error while uploading issue...")
                      sendMessage("issueErrored", Json.obj())
                  }
              }
            case (_, _) =>
              Logger.debug("submitBugReport: non-correct JSON")
          }
        case "commonErrorFeedback" =>
          var optCommonErrorID: Option[Int] = (msg \ "args" \ "commonErrorID").asOpt[Int]
          var optAccuracy: Option[Int] = (msg \ "args" \ "accuracy").asOpt[Int]
          var optHelp: Option[Int] = (msg \ "args" \ "help").asOpt[Int]
          var optComment: Option[String] = (msg \ "args" \ "comment").asOpt[String]
          (optCommonErrorID, optAccuracy, optHelp, optComment) match  {
            case (Some(commonErrorID: Int), Some(accuracy: Int), Some(help: Int), Some(comment: String)) =>
              // FIXME: Re-implement me
              // plm.signalCommonErrorFeedback(commonErrorID, accuracy, help, comment)
            case _ =>
              Logger.debug("commonErrorFeedback: non-correct JSON")
          }
        case "readTip" =>
          var optTipID: Option[String] = (msg \ "args" \ "tipID").asOpt[String]
          optTipID match {
            case Some(tipID: String) =>
              // FIXME: Re-implement me
              // plm.signalReadTip(tipID)
            case _ =>
              Logger.debug("readTip: non-correct JSON")
          } 
        case "ping" =>
          // Do nothing
        case _ =>
          Logger.error("cmd: non-correct JSON")
      }
  }

  def createMessage(cmdName: String, mapArgs: JsValue): JsValue = {
    Json.obj(
      "cmd" -> cmdName,
      "args" -> mapArgs
    )
  }

  def sendMessage(cmdName: String, mapArgs: JsValue) {
    out ! createMessage(cmdName, mapArgs)
  }

  def registerActor() {
    ActorsMap.add(actorUUID, self)
    sendMessage("actorUUID", Json.obj(
        "actorUUID" -> actorUUID  
      )
    )
  }

  def switchExercise(lessonID: String, exerciseID: String) {
    (exercisesActor ? GetExercise(exerciseID)).mapTo[Exercise].map { exercise =>
      gitActor ! SwitchExercise(exercise, optCurrentExercise)

      optCurrentLesson = Some(lessonID)
      optCurrentExercise = Some(exercise)

      (sessionActor ? RetrieveCode(exercise, currentProgLang)).mapTo[String].map { code =>
        val json: JsObject = ExerciseToJson.exerciseWrites(exercise, currentProgLang, code, currentHumanLang.toLocale)
        sendMessage("exercise", Json.obj(
          "exercise" -> json
        ))
      }
    }
  }
  
  def sendProgLangs() {
    sendMessage("progLangs", Json.obj(
      "selected" -> ProgrammingLanguageToJson.programmingLanguageWrite(currentProgLang),
      "availables" -> ProgrammingLanguageToJson.programmingLanguagesWrite(ProgrammingLanguages.programmingLanguages)
    ))
  }

  def sendHumanLangs() {
    sendMessage("humanLangs", Json.obj(
      "selected" -> LangToJson.langWrite(currentHumanLang),
      "availables" -> LangToJson.langsWrite(availableLangs)
    ))
  }

  def setCurrentUser(newUser: User) {
    optCurrentUser = Some(newUser)
    sendMessage("user", Json.obj(
        "user" -> newUser
      )
    )

    setCurrentGitID(newUser.gitID, false)
  }

  def clearCurrentUser() {
    optCurrentUser = None
    sendMessage("user", Json.obj())

    currentGitID = UUID.randomUUID.toString
    setCurrentGitID(currentGitID, true)
  }

  def setCurrentGitID(newGitID: String, toSend: Boolean) {
    currentGitID = newGitID;
    if(toSend) {
      sendMessage("gitID", Json.obj(
          "gitID" -> currentGitID  
        )
      )
    }
  } 

  def saveLastProgLang() {
    optCurrentUser match {
    case Some(currentUser: User) =>
      val newUser: User = currentUser.copy(
          lastProgLang = Some(currentProgLang.getLang)
      )
      optCurrentUser = Some(newUser)
      UserDAORestImpl.update(newUser)
    case _ =>
    }
  }

  def savePreferredLang() {
    optCurrentUser match {
    case Some(currentUser: User) =>
      val newUser: User = currentUser.copy(
          preferredLang = Some(currentHumanLang)
      )
      optCurrentUser = Some(newUser)
      UserDAORestImpl.update(newUser)
    case _ =>
    }
  }

  def saveTrackUser(trackUser: Boolean) {
    optCurrentUser match {
    case Some(currentUser: User) =>
      val newUser: User = currentUser.copy(
          trackUser = Some(trackUser)
      )
      optCurrentUser = Some(newUser)
      UserDAORestImpl.update(newUser)
    case _ =>
    }
  }

  def setIdle() {
    val idleStart: Instant = Instant.apply
    optIdle = Some(idleStart)
    Logger.debug("start idling at: "+ idleStart)      
  }

  def clearIdle() {
    optIdle match {
    case Some(idleStart: Instant) =>
      val idleEnd: Instant = Instant.apply
      val duration = Duration.between(idleStart, idleEnd)
      Logger.debug("end idling at: "+ idleEnd)
      Logger.debug("duration: " + duration)
      gitActor ! Idle(idleStart.toString, idleEnd.toString, duration.toString)
    case _ =>
    }
    optIdle = None
  }

  def initProgLang(lastProgLang: Option[String]): ProgrammingLanguage = {
    lastProgLang match {
    case Some(progLang: String) =>
      ProgrammingLanguages.getProgrammingLanguage(progLang)
    case _ =>
      ProgrammingLanguages.defaultProgrammingLanguage
    }
  }

  def initHumanLang(lastHumanLang: Option[Lang]): Lang = {
    lastHumanLang match {
      case Some(lang: Lang) =>
        lang
      case _ =>
        Lang("en")
    }
  }

  def updateHumanLang(humanLang: String) {
    currentHumanLang = Lang(humanLang)
    sendMessage("newHumanLang", generateUpdatedHumanLangJson)
  }

  def updateProgLang(progLang: String) {
    currentProgLang = ProgrammingLanguages.getProgrammingLanguage(progLang)
    sendMessage("newProgLang", generateUpdatedProgLangJson)
  }

  def generateUpdatedExerciseJson(): JsObject = {
    optCurrentExercise match {
    case Some(exercise: Exercise) => 
      val exerciseJson: JsObject = Json.obj(
        "instructions" -> exercise.getMission(currentHumanLang.language, currentProgLang),
        "api" -> exercise.getWorldAPI(currentHumanLang.toLocale, currentProgLang)
      )
      exerciseJson
    case _ =>
      Json.obj()
    }
  }

  def generateUpdatedHumanLangJson(): JsObject = {
    var json: JsObject = Json.obj("newHumanLang" -> LangToJson.langWrite(currentHumanLang))

    val futureTuple = for {
      lessonsListJson <- generateUpdatedLessonsListJson
      exercisesListJson <- generateUpdatedExercisesListJson
      exerciseJson <- Future { generateUpdatedExerciseJson }
    } yield (lessonsListJson, exercisesListJson, exerciseJson)

    val tuple = Await.result(futureTuple, 1 seconds)
    tuple.productIterator.foreach { additionalJson =>
      json = json ++ additionalJson.asInstanceOf[JsObject]
    }
    json
  }

  def generateUpdatedLessonsListJson(): Future[JsValue] = {
    (lessonsActor ? GetLessonsList).mapTo[Array[Lesson]].map { lessons =>
      Json.obj(
        "lessons" -> Lesson.arrayToJson(lessons, currentHumanLang)
      )
    }
  }
  
  def generateUpdatedExercisesListJson(): Future[JsObject] = {
    optCurrentLesson match {
    case Some(lessonName: String) =>
      (lessonsActor ? GetExercisesList(lessonName)).mapTo[Array[Lecture]].map { lectures =>
        Json.obj(
          "lectures" -> Lecture.arrayToJson(lectures, currentHumanLang, currentProgLang)
        )
      }
    case _ =>
      Future { Json.obj() }
    }
  }

  def generateUpdatedProgLangJson(): JsObject = {
    var json: JsObject = Json.obj("newProgLang" -> ProgrammingLanguageToJson.programmingLanguageWrite(currentProgLang))

    val futureTuple = for {
      codeJson <- generateUpdatedCodeJson
      exercisesListJson <- generateUpdatedExercisesListJson
      exerciseJson <- Future { generateUpdatedExerciseJson }
    } yield (codeJson, exercisesListJson, exerciseJson)

    val tuple = Await.result(futureTuple, 1 seconds)
    tuple.productIterator.foreach { additionalJson =>
      json = json ++ additionalJson.asInstanceOf[JsObject]
    }
    json
  }

  def generateUpdatedCodeJson(): Future[JsObject] = {
    optCurrentExercise match {
    case Some(exercise: Exercise) => 
      (sessionActor ? RetrieveCode(exercise, currentProgLang)).mapTo[String].map { code =>
        val codeJson: JsObject = Json.obj(
          "code" -> code
        )
        codeJson
      }
    case _ =>
      Future { Json.obj() }
    }
  }

  override def postStop() = {
    Logger.debug("postStop: websocket closed - removing the spies")
    clearIdle
    ActorsMap.remove(actorUUID)
  }
}
