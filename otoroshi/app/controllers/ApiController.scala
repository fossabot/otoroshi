package controllers

import java.lang.management.ManagementFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import javax.management.{Attribute, ObjectName}
import gnieh.diffson.playJson._
import actions.{ApiAction, UnAuthApiAction}
import akka.NotUsed
import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.{Framing, Source, FileIO}
import akka.util.ByteString
import auth.{AuthModuleConfig, BasicAuthModule, BasicAuthUser, GenericOauth2ModuleConfig}
import cluster.{ClusterMode, MemberView, StatsView}
import env.Env
import events._
import models._
import org.joda.time.DateTime
import org.mindrot.jbcrypt.BCrypt
import play.api.Logger
import play.api.http.HttpEntity
import play.api.libs.json.{JsSuccess, Json, _}
import play.api.libs.streams.Accumulator
import play.api.mvc._
import security.IdGenerator
import ssl.Cert
import storage.{Healthy, Unhealthy, Unreachable}
import utils.Metrics
import utils.future.Implicits._

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success}

class ApiController(ApiAction: ApiAction, UnAuthApiAction: UnAuthApiAction, cc: ControllerComponents)(implicit env: Env)
    extends AbstractController(cc) {

  implicit lazy val ec  = env.otoroshiExecutionContext
  implicit lazy val mat = env.otoroshiMaterializer

  lazy val logger = Logger("otoroshi-admin-api")

  val sourceBodyParser = BodyParser("ApiController BodyParser") { _ =>
    Accumulator.source[ByteString].map(Right.apply)
  }

  def processMetrics() = Action.async { req =>

    val format  = req.getQueryString("format")

    def transformToArray(input: String): JsValue = {
      val metrics = Json.parse(input)
      metrics.as[JsObject].value.toSeq.foldLeft(Json.arr()) {
        case (arr, (key, JsObject(value))) =>
          arr ++ value.toSeq.foldLeft(Json.arr()) {
            case (arr2, (key2, value2@JsObject(_))) =>
              arr2 ++ Json.arr(value2 ++ Json.obj("name" -> key2, "type" -> key))
            case (arr2, (key2, value2)) =>
              arr2
          }
        case (arr, (key, value)) => arr
      }
    }

    def fetchMetrics(): Result = {
      if (format.contains("old_json") || format.contains("old")) {
        Ok(env.metrics.jsonExport(None)).as("application/json")
      } else if (format.contains("json")) {
        Ok(transformToArray(env.metrics.jsonExport(None))).as("application/json")
      } else if (format.contains("prometheus") || format.contains("prom")) {
        Ok(env.metrics.prometheusExport(None)).as("text/plain")
      } else if (req.accepts("application/json")) {
        Ok(transformToArray(env.metrics.jsonExport(None))).as("application/json")
      } else if (req.accepts("application/prometheus")) {
        Ok(env.metrics.prometheusExport(None)).as("text/plain")
      } else {
        Ok(transformToArray(env.metrics.jsonExport(None))).as("application/json")
      }
    }

    if (env.metricsEnabled) {
      FastFuture.successful(
        ((req.getQueryString("access_key"), req.getQueryString("X-Access-Key"), env.metricsAccessKey) match {
          case (_, _, None)                                  => fetchMetrics()
          case (Some(header), _, Some(key)) if header == key => fetchMetrics()
          case (_, Some(header), Some(key)) if header == key => fetchMetrics()
          case _                                             => Unauthorized(Json.obj("error" -> "unauthorized"))
        }) withHeaders (
          env.Headers.OtoroshiStateResp -> req.headers
            .get(env.Headers.OtoroshiState)
            .getOrElse("--")
        )
      )
    } else {
      FastFuture.successful(NotFound(Json.obj("error" -> "metrics not enabled")))
    }
  }

  def health() = Action.async { req =>
    def fetchHealth() = {
      val membersF = if (env.clusterConfig.mode == ClusterMode.Leader) {
        env.datastores.clusterStateDataStore.getMembers()
      } else {
        FastFuture.successful(Seq.empty[MemberView])
      }
      for {
        _health  <- env.datastores.health()
        scripts  <- env.scriptManager.state()
        overhead <- env.datastores.serviceDescriptorDataStore.globalCallsOverhead()
        members  <- membersF
      } yield {
        val cluster = env.clusterConfig.mode match {
          case ClusterMode.Off => Json.obj()
          case ClusterMode.Worker =>
            Json.obj("cluster" -> Json.obj("health" -> "healthy", "lastSync" -> env.clusterAgent.lastSync.toString()))
          case ClusterMode.Leader => {
            val healths     = members.map(_.health)
            val foundOrange = healths.contains("orange")
            val foundRed    = healths.contains("red")
            val health      = if (foundRed) "unhealthy" else (if (foundOrange) "notthathealthy" else "healthy")
            Json.obj("cluster" -> Json.obj("health" -> health))
          }
        }
        val payload = Json.obj(
          "otoroshi" -> JsString(_health match {
            case Healthy if overhead <= env.healthLimit => "healthy"
            case Healthy if overhead > env.healthLimit  => "unhealthy"
            case Unhealthy                              => "unhealthy"
            case Unreachable                            => "down"
          }),
          "datastore" -> JsString(_health match {
            case Healthy     => "healthy"
            case Unhealthy   => "unhealthy"
            case Unreachable => "unreachable"
          }),
          "scripts" -> scripts
        ) ++ cluster
        val err = (payload \ "otoroshi").asOpt[String].exists(_ != "healthy") ||
        (payload \ "datastore").asOpt[String].exists(_ != "healthy") ||
        (payload \ "cluster").asOpt[String].orElse(Some("healthy")).exists(v => v != "healthy")
        if (err) {
          InternalServerError(payload)
        } else {
          Ok(payload)
        }
      }
    }

    ((req.getQueryString("access_key"), req.getQueryString("X-Access-Key"), env.healthAccessKey) match {
      case (_, _, None)                                  => fetchHealth()
      case (Some(header), _, Some(key)) if header == key => fetchHealth()
      case (_, Some(header), Some(key)) if header == key => fetchHealth()
      case _                                             => FastFuture.successful(Unauthorized(Json.obj("error" -> "unauthorized")))
    }) map { res =>
      res.withHeaders(
        env.Headers.OtoroshiStateResp -> req.headers
          .get(env.Headers.OtoroshiState)
          .getOrElse("--")
      )
    }
  }

  private def avgDouble(value: Double, extractor: StatsView => Double, stats: Seq[StatsView]): Double = {
    stats.map(extractor).:+(value).fold(0.0)(_ + _) / (stats.size + 1)
  }

  private def sumDouble(value: Double, extractor: StatsView => Double, stats: Seq[StatsView]): Double = {
    stats.map(extractor).:+(value).fold(0.0)(_ + _)
  }

  def globalLiveStats() = ApiAction.async { ctx =>
    Audit.send(
      AdminApiEvent(
        env.snowflakeGenerator.nextIdStr(),
        env.env,
        Some(ctx.apiKey),
        ctx.user,
        "ACCESS_GLOBAL_LIVESTATS",
        "User accessed global livestats",
        ctx.from,
        ctx.ua
      )
    )
    for {
      calls                     <- env.datastores.serviceDescriptorDataStore.globalCalls()
      dataIn                    <- env.datastores.serviceDescriptorDataStore.globalDataIn()
      dataOut                   <- env.datastores.serviceDescriptorDataStore.globalDataOut()
      rate                      <- env.datastores.serviceDescriptorDataStore.globalCallsPerSec()
      duration                  <- env.datastores.serviceDescriptorDataStore.globalCallsDuration()
      overhead                  <- env.datastores.serviceDescriptorDataStore.globalCallsOverhead()
      dataInRate                <- env.datastores.serviceDescriptorDataStore.dataInPerSecFor("global")
      dataOutRate               <- env.datastores.serviceDescriptorDataStore.dataOutPerSecFor("global")
      concurrentHandledRequests <- env.datastores.requestsDataStore.asyncGetHandledRequests()
      membersStats              <- env.datastores.clusterStateDataStore.getMembers().map(_.map(_.statsView))
    } yield
      Ok(
        Json.obj(
          "calls"       -> calls,
          "dataIn"      -> dataIn,
          "dataOut"     -> dataOut,
          "rate"        -> sumDouble(rate, _.rate, membersStats),
          "duration"    -> avgDouble(duration, _.duration, membersStats),
          "overhead"    -> avgDouble(overhead, _.overhead, membersStats),
          "dataInRate"  -> sumDouble(dataInRate, _.dataInRate, membersStats),
          "dataOutRate" -> sumDouble(dataOutRate, _.dataOutRate, membersStats),
          "concurrentHandledRequests" -> sumDouble(concurrentHandledRequests.toDouble,
                                                   _.concurrentHandledRequests.toDouble,
                                                   membersStats).toLong
        )
      )
  }

  def hostMetrics() = ApiAction { ctx =>
    Audit.send(
      AdminApiEvent(
        env.snowflakeGenerator.nextIdStr(),
        env.env,
        Some(ctx.apiKey),
        ctx.user,
        "ACCESS_HOST_METRICS",
        "User accessed global livestats",
        ctx.from,
        ctx.ua
      )
    )
    val appEnv         = Option(System.getenv("APP_ENV")).getOrElse("--")
    val commitId       = Option(System.getenv("COMMIT_ID")).getOrElse("--")
    val instanceNumber = Option(System.getenv("INSTANCE_NUMBER")).getOrElse("--")
    val appId          = Option(System.getenv("APP_ID")).getOrElse("--")
    val instanceId     = Option(System.getenv("INSTANCE_ID")).getOrElse("--")

    val mbs = ManagementFactory.getPlatformMBeanServer
    val rt  = Runtime.getRuntime

    def getProcessCpuLoad(): Double = {
      val name = ObjectName.getInstance("java.lang:type=OperatingSystem")
      val list = mbs.getAttributes(name, Array("ProcessCpuLoad"))
      if (list.isEmpty) return 0.0
      val att   = list.get(0).asInstanceOf[Attribute]
      val value = att.getValue.asInstanceOf[Double]
      if (value == -1.0) return 0.0
      (value * 1000) / 10.0
      // ManagementFactory.getOperatingSystemMXBean.getSystemLoadAverage
    }

    val source = Source
      .tick(FiniteDuration(0, TimeUnit.MILLISECONDS), FiniteDuration(2000, TimeUnit.MILLISECONDS), NotUsed)
      .map(
        _ =>
          Json.obj(
            "cpu_usage"         -> getProcessCpuLoad(),
            "heap_used"         -> (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024,
            "heap_size"         -> rt.totalMemory() / 1024 / 1024,
            "live_threads"      -> ManagementFactory.getThreadMXBean.getThreadCount,
            "live_peak_threads" -> ManagementFactory.getThreadMXBean.getPeakThreadCount,
            "daemon_threads"    -> ManagementFactory.getThreadMXBean.getDaemonThreadCount,
            "env"               -> appEnv,
            "commit_id"         -> commitId,
            "instance_number"   -> instanceNumber,
            "app_id"            -> appId,
            "instance_id"       -> instanceId
        )
      )
      .map(Json.stringify)
      .map(slug => s"data: $slug\n\n")
    Ok.chunked(source).as("text/event-stream")
  }

  def serviceLiveStats(id: String, every: Option[Int]) = ApiAction { ctx =>
    Audit.send(
      AdminApiEvent(
        env.snowflakeGenerator.nextIdStr(),
        env.env,
        Some(ctx.apiKey),
        ctx.user,
        "ACCESS_SERVICE_LIVESTATS",
        "User accessed service livestats",
        ctx.from,
        ctx.ua,
        Json.obj("serviceId" -> id)
      )
    )
    def fetch() = id match {
      case "global" =>
        for {
          calls                     <- env.datastores.serviceDescriptorDataStore.globalCalls()
          dataIn                    <- env.datastores.serviceDescriptorDataStore.globalDataIn()
          dataOut                   <- env.datastores.serviceDescriptorDataStore.globalDataOut()
          rate                      <- env.datastores.serviceDescriptorDataStore.globalCallsPerSec()
          duration                  <- env.datastores.serviceDescriptorDataStore.globalCallsDuration()
          overhead                  <- env.datastores.serviceDescriptorDataStore.globalCallsOverhead()
          dataInRate                <- env.datastores.serviceDescriptorDataStore.dataInPerSecFor("global")
          dataOutRate               <- env.datastores.serviceDescriptorDataStore.dataOutPerSecFor("global")
          concurrentHandledRequests <- env.datastores.requestsDataStore.asyncGetHandledRequests()
          membersStats              <- env.datastores.clusterStateDataStore.getMembers().map(_.map(_.statsView))
        } yield
          Json.obj(
            "calls"       -> calls,
            "dataIn"      -> dataIn,
            "dataOut"     -> dataOut,
            "rate"        -> sumDouble(rate, _.rate, membersStats),
            "duration"    -> avgDouble(duration, _.duration, membersStats),
            "overhead"    -> avgDouble(overhead, _.overhead, membersStats),
            "dataInRate"  -> sumDouble(dataInRate, _.dataInRate, membersStats),
            "dataOutRate" -> sumDouble(dataOutRate, _.dataOutRate, membersStats),
            "concurrentHandledRequests" -> sumDouble(concurrentHandledRequests.toDouble,
                                                     _.concurrentHandledRequests.toDouble,
                                                     membersStats).toLong
          )
      case serviceId =>
        for {
          calls                     <- env.datastores.serviceDescriptorDataStore.calls(serviceId)
          dataIn                    <- env.datastores.serviceDescriptorDataStore.dataInFor(serviceId)
          dataOut                   <- env.datastores.serviceDescriptorDataStore.dataOutFor(serviceId)
          rate                      <- env.datastores.serviceDescriptorDataStore.callsPerSec(serviceId)
          duration                  <- env.datastores.serviceDescriptorDataStore.callsDuration(serviceId)
          overhead                  <- env.datastores.serviceDescriptorDataStore.callsOverhead(serviceId)
          dataInRate                <- env.datastores.serviceDescriptorDataStore.dataInPerSecFor(serviceId)
          dataOutRate               <- env.datastores.serviceDescriptorDataStore.dataOutPerSecFor(serviceId)
          concurrentHandledRequests <- env.datastores.requestsDataStore.asyncGetHandledRequests()
          membersStats              <- env.datastores.clusterStateDataStore.getMembers().map(_.map(_.statsView))
        } yield
          Json.obj(
            "calls"       -> calls,
            "dataIn"      -> dataIn,
            "dataOut"     -> dataOut,
            "rate"        -> sumDouble(rate, _.rate, membersStats),
            "duration"    -> avgDouble(duration, _.duration, membersStats),
            "overhead"    -> avgDouble(overhead, _.overhead, membersStats),
            "dataInRate"  -> sumDouble(dataInRate, _.dataInRate, membersStats),
            "dataOutRate" -> sumDouble(dataOutRate, _.dataOutRate, membersStats),
            "concurrentHandledRequests" -> sumDouble(concurrentHandledRequests.toDouble,
                                                     _.concurrentHandledRequests.toDouble,
                                                     membersStats).toLong
          )
    }
    every match {
      case Some(millis) =>
        Ok.chunked(
            Source
              .tick(FiniteDuration(0, TimeUnit.MILLISECONDS), FiniteDuration(millis, TimeUnit.MILLISECONDS), NotUsed)
              .flatMapConcat(_ => Source.fromFuture(fetch()))
              .map(json => s"data: ${Json.stringify(json)}\n\n")
          )
          .as("text/event-stream")
      case None => Ok.chunked(Source.single(1).flatMapConcat(_ => Source.fromFuture(fetch()))).as("application/json")
    }
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  def allLines() = ApiAction.async { ctx =>
    Audit.send(
      AdminApiEvent(
        env.snowflakeGenerator.nextIdStr(),
        env.env,
        Some(ctx.apiKey),
        ctx.user,
        "ACCESS_ALL_LINES",
        "User accessed all lines",
        ctx.from,
        ctx.ua
      )
    )
    env.datastores.globalConfigDataStore.allEnv().map {
      case lines => Ok(JsArray(lines.toSeq.map(l => JsString(l))))
    }
  }

  def servicesForALine(line: String) = ApiAction.async { ctx =>
    val paginationPage: Int = ctx.request.queryString.get("page").flatMap(_.headOption).map(_.toInt).getOrElse(1)
    val paginationPageSize: Int =
      ctx.request.queryString.get("pageSize").flatMap(_.headOption).map(_.toInt).getOrElse(Int.MaxValue)
    val paginationPosition = (paginationPage - 1) * paginationPageSize
    Audit.send(
      AdminApiEvent(
        env.snowflakeGenerator.nextIdStr(),
        env.env,
        Some(ctx.apiKey),
        ctx.user,
        "ACCESS_SERVICES_FOR_LINES",
        s"User accessed service list for line $line",
        ctx.from,
        ctx.ua,
        Json.obj("line" -> line)
      )
    )
    env.datastores.serviceDescriptorDataStore.findByEnv(line).map {
      case descriptors => Ok(JsArray(descriptors.drop(paginationPosition).take(paginationPageSize).map(_.toJson)))
    }
  }

  def globalConfig() = ApiAction.async { ctx =>
    env.datastores.globalConfigDataStore.findById("global").map {
      case None => NotFound(Json.obj("error" -> "GlobalConfig not found"))
      case Some(ak) => {
        Audit.send(
          AdminApiEvent(
            env.snowflakeGenerator.nextIdStr(),
            env.env,
            Some(ctx.apiKey),
            ctx.user,
            "ACCESS_GLOBAL_CONFIG",
            s"User accessed global Otoroshi config",
            ctx.from,
            ctx.ua
          )
        )
        Ok(ak.toJson)
      }
    }
  }

  def updateGlobalConfig() = ApiAction.async(parse.json) { ctx =>
    val user = ctx.user.getOrElse(ctx.apiKey.toJson)
    GlobalConfig.fromJsonSafe(ctx.request.body) match {
      case JsError(e) => FastFuture.successful(BadRequest(Json.obj("error" -> "Bad GlobalConfig format")))
      case JsSuccess(ak, _) => {
        env.datastores.globalConfigDataStore.singleton().flatMap { conf =>
          val admEvt = AdminApiEvent(
            env.snowflakeGenerator.nextIdStr(),
            env.env,
            Some(ctx.apiKey),
            ctx.user,
            "UPDATE_GLOBAL_CONFIG",
            s"User updated global Otoroshi config",
            ctx.from,
            ctx.ua,
            ctx.request.body
          )
          Audit.send(admEvt)
          Alerts.send(
            GlobalConfigModification(env.snowflakeGenerator.nextIdStr(),
                                     env.env,
                                     user,
                                     conf.toJson,
                                     ak.toJson,
                                     admEvt,
                                     ctx.from,
                                     ctx.ua)
          )
          ak.save().map(_ => Ok(Json.obj("done" -> true))) // TODO : rework
        }
      }
    }
  }

  def patchGlobalConfig() = ApiAction.async(parse.json) { ctx =>
    val user = ctx.user.getOrElse(ctx.apiKey.toJson)
    env.datastores.globalConfigDataStore.singleton().flatMap { conf =>
      val currentConfigJson = conf.toJson
      val patch             = JsonPatch(ctx.request.body)
      val newConfigJson     = patch(currentConfigJson)
      GlobalConfig.fromJsonSafe(newConfigJson) match {
        case JsError(e) => FastFuture.successful(BadRequest(Json.obj("error" -> "Bad GlobalConfig format")))
        case JsSuccess(ak, _) => {
          val admEvt = AdminApiEvent(
            env.snowflakeGenerator.nextIdStr(),
            env.env,
            Some(ctx.apiKey),
            ctx.user,
            "UPDATE_GLOBAL_CONFIG",
            s"User updated global Otoroshi config",
            ctx.from,
            ctx.ua,
            ctx.request.body
          )
          Audit.send(admEvt)
          Alerts.send(
            GlobalConfigModification(env.snowflakeGenerator.nextIdStr(),
                                     env.env,
                                     user,
                                     conf.toJson,
                                     ak.toJson,
                                     admEvt,
                                     ctx.from,
                                     ctx.ua)
          )
          ak.save().map(_ => Ok(Json.obj("done" -> true))) // TODO : rework
        }
      }
    }
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  def createGroup() = ApiAction.async(parse.json) { ctx =>
    val body: JsObject = (ctx.request.body \ "id").asOpt[String] match {
      case None    => ctx.request.body.as[JsObject] ++ Json.obj("id" -> IdGenerator.token(64))
      case Some(b) => ctx.request.body.as[JsObject]
    }
    ServiceGroup.fromJsonSafe(body) match {
      case JsError(e) => BadRequest(Json.obj("error" -> "Bad ServiceGroup format")).asFuture
      case JsSuccess(group, _) =>
        group.save().map {
          case true => {
            val event: AdminApiEvent = AdminApiEvent(
              env.snowflakeGenerator.nextIdStr(),
              env.env,
              Some(ctx.apiKey),
              ctx.user,
              "CREATE_SERVICE_GROUP",
              s"User created a service group",
              ctx.from,
              ctx.ua,
              body
            )
            Audit.send(event)
            Alerts.send(
              ServiceGroupCreatedAlert(env.snowflakeGenerator.nextIdStr(),
                                       env.env,
                                       ctx.user.getOrElse(ctx.apiKey.toJson),
                                       event,
                                       ctx.from,
                                       ctx.ua)
            )
            Ok(group.toJson)
          }
          case false => InternalServerError(Json.obj("error" -> "Developer not stored ..."))
        }
    }
  }

  def updateGroup(serviceGroupId: String) = ApiAction.async(parse.json) { ctx =>
    env.datastores.serviceGroupDataStore.findById(serviceGroupId).flatMap {
      case None => NotFound(Json.obj("error" -> s"ServiceGroup with clienId '$serviceGroupId' not found")).asFuture
      case Some(group) => {
        ServiceGroup.fromJsonSafe(ctx.request.body) match {
          case JsError(e) => BadRequest(Json.obj("error" -> "Bad ServiceGroup format")).asFuture
          case JsSuccess(newGroup, _) if newGroup.id != serviceGroupId =>
            BadRequest(Json.obj("error" -> "Bad ServiceGroup format")).asFuture
          case JsSuccess(newGroup, _) if newGroup.id == serviceGroupId => {
            val event: AdminApiEvent = AdminApiEvent(
              env.snowflakeGenerator.nextIdStr(),
              env.env,
              Some(ctx.apiKey),
              ctx.user,
              "UPDATE_SERVICE_GROUP",
              s"User updated a service group",
              ctx.from,
              ctx.ua,
              ctx.request.body
            )
            Audit.send(event)
            Alerts.send(
              ServiceGroupUpdatedAlert(env.snowflakeGenerator.nextIdStr(),
                                       env.env,
                                       ctx.user.getOrElse(ctx.apiKey.toJson),
                                       event,
                                       ctx.from,
                                       ctx.ua)
            )
            newGroup.save().map(_ => Ok(newGroup.toJson))
          }
        }
      }
    }
  }

  def patchGroup(serviceGroupId: String) = ApiAction.async(parse.json) { ctx =>
    env.datastores.serviceGroupDataStore.findById(serviceGroupId).flatMap {
      case None => NotFound(Json.obj("error" -> s"ServiceGroup with clienId '$serviceGroupId' not found")).asFuture
      case Some(group) => {
        val currentGroupJson = group.toJson
        val patch            = JsonPatch(ctx.request.body)
        val newGroupJson     = patch(currentGroupJson)
        ServiceGroup.fromJsonSafe(newGroupJson) match {
          case JsError(e) => BadRequest(Json.obj("error" -> "Bad ServiceGroup format")).asFuture
          case JsSuccess(newGroup, _) if newGroup.id != serviceGroupId =>
            BadRequest(Json.obj("error" -> "Bad ServiceGroup format")).asFuture
          case JsSuccess(newGroup, _) if newGroup.id == serviceGroupId => {
            val event: AdminApiEvent = AdminApiEvent(
              env.snowflakeGenerator.nextIdStr(),
              env.env,
              Some(ctx.apiKey),
              ctx.user,
              "UPDATE_SERVICE_GROUP",
              s"User updated a service group",
              ctx.from,
              ctx.ua,
              ctx.request.body
            )
            Audit.send(event)
            Alerts.send(
              ServiceGroupUpdatedAlert(env.snowflakeGenerator.nextIdStr(),
                                       env.env,
                                       ctx.user.getOrElse(ctx.apiKey.toJson),
                                       event,
                                       ctx.from,
                                       ctx.ua)
            )
            newGroup.save().map(_ => Ok(newGroup.toJson))
          }
        }
      }
    }
  }

  def deleteGroup(serviceGroupId: String) = ApiAction.async { ctx =>
    env.datastores.serviceGroupDataStore.findById(serviceGroupId).flatMap {
      case None => NotFound(Json.obj("error" -> s"ServiceGroup with id: '$serviceGroupId' not found")).asFuture
      case Some(group) =>
        group.delete().map { res =>
          val event: AdminApiEvent = AdminApiEvent(
            env.snowflakeGenerator.nextIdStr(),
            env.env,
            Some(ctx.apiKey),
            ctx.user,
            "DELETE_SERVICE_GROUP",
            s"User deleted a service group",
            ctx.from,
            ctx.ua,
            Json.obj("serviceGroupId" -> serviceGroupId)
          )
          Audit.send(event)
          Alerts.send(
            ServiceGroupDeletedAlert(env.snowflakeGenerator.nextIdStr(),
                                     env.env,
                                     ctx.user.getOrElse(ctx.apiKey.toJson),
                                     event,
                                     ctx.from,
                                     ctx.ua)
          )
          Ok(Json.obj("deleted" -> res))
        }
    }
  }

  // TODO
  def addServiceToGroup(serviceGroupId: String) = ApiAction.async(parse.json) { ctx =>
    ???
  }

  // TODO
  def addExistingServiceToGroup(serviceGroupId: String, serviceId: String) = ApiAction.async(parse.json) { ctx =>
    ???
  }

  // TODO
  def removeServiceFromGroup(serviceGroupId: String, serviceId: String) = ApiAction.async { ctx =>
    ???
  }

  def allServiceGroups() = ApiAction.async { ctx =>
    val paginationPage: Int = ctx.request.queryString.get("page").flatMap(_.headOption).map(_.toInt).getOrElse(1)
    val paginationPageSize: Int =
      ctx.request.queryString.get("pageSize").flatMap(_.headOption).map(_.toInt).getOrElse(Int.MaxValue)
    val paginationPosition = (paginationPage - 1) * paginationPageSize
    Audit.send(
      AdminApiEvent(
        env.snowflakeGenerator.nextIdStr(),
        env.env,
        Some(ctx.apiKey),
        ctx.user,
        "ACCESS_ALL_SERVICES_GROUPS",
        s"User accessed all services groups",
        ctx.from,
        ctx.ua
      )
    )
    val id: Option[String]   = ctx.request.queryString.get("id").flatMap(_.headOption)
    val name: Option[String] = ctx.request.queryString.get("name").flatMap(_.headOption)
    val hasFilters           = id.orElse(name).orElse(name).isDefined
    env.datastores.serviceGroupDataStore.streamedFindAndMat(_ => true, 50, paginationPage, paginationPageSize).map {
      groups =>
        if (hasFilters) {
          Ok(
            JsArray(
              groups
                .filter {
                  case group if id.isDefined && group.id == id.get       => true
                  case group if name.isDefined && group.name == name.get => true
                  case _                                                 => false
                }
                .map(_.toJson)
            )
          )
        } else {
          Ok(JsArray(groups.map(_.toJson)))
        }
    }
  }

  def serviceGroup(serviceGroupId: String) = ApiAction.async { ctx =>
    env.datastores.serviceGroupDataStore.findById(serviceGroupId).map {
      case None => NotFound(Json.obj("error" -> s"ServiceGroup with id: '$serviceGroupId' not found"))
      case Some(group) => {
        Audit.send(
          AdminApiEvent(
            env.snowflakeGenerator.nextIdStr(),
            env.env,
            Some(ctx.apiKey),
            ctx.user,
            "ACCESS_SERVICES_GROUP",
            s"User accessed a service group",
            ctx.from,
            ctx.ua,
            Json.obj("serviceGroupId" -> serviceGroupId)
          )
        )
        Ok(group.toJson)
      }
    }
  }

  def serviceGroupServices(serviceGroupId: String) = ApiAction.async { ctx =>
    val paginationPage: Int = ctx.request.queryString.get("page").flatMap(_.headOption).map(_.toInt).getOrElse(1)
    val paginationPageSize: Int =
      ctx.request.queryString.get("pageSize").flatMap(_.headOption).map(_.toInt).getOrElse(Int.MaxValue)
    val paginationPosition = (paginationPage - 1) * paginationPageSize
    env.datastores.serviceGroupDataStore.findById(serviceGroupId).flatMap {
      case None => NotFound(Json.obj("error" -> s"ServiceGroup with id: '$serviceGroupId' not found")).asFuture
      case Some(group) => {
        Audit.send(
          AdminApiEvent(
            env.snowflakeGenerator.nextIdStr(),
            env.env,
            Some(ctx.apiKey),
            ctx.user,
            "ACCESS_SERVICES_FROM_SERVICES_GROUP",
            s"User accessed all services from a services group",
            ctx.from,
            ctx.ua,
            Json.obj("serviceGroupId" -> serviceGroupId)
          )
        )
        group.services
          .map(services => Ok(JsArray(services.drop(paginationPosition).take(paginationPageSize).map(_.toJson))))
      }
    }
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  def initiateApiKey(groupId: Option[String]) = ApiAction.async { ctx =>
    groupId match {
      case Some(gid) => {
        env.datastores.serviceGroupDataStore.findById(gid).map {
          case Some(group) => {
            val apiKey = env.datastores.apiKeyDataStore.initiateNewApiKey(gid)
            Ok(apiKey.toJson)
          }
          case None => NotFound(Json.obj("error" -> s"Group with id `$gid` does not exist"))
        }
      }
      case None => {
        val apiKey = env.datastores.apiKeyDataStore.initiateNewApiKey("default")
        FastFuture.successful(Ok(apiKey.toJson))
      }
    }
  }

  def initiateServiceGroup() = ApiAction { ctx =>
    val group = env.datastores.serviceGroupDataStore.initiateNewGroup()
    Ok(group.toJson)
  }

  def initiateService() = ApiAction { ctx =>
    val desc = env.datastores.serviceDescriptorDataStore.initiateNewDescriptor()
    Ok(desc.toJson)
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  def createService() = ApiAction.async(parse.json) { ctx =>
    val rawBody = ctx.request.body.as[JsObject]
    env.datastores.globalConfigDataStore.singleton().flatMap { globalConfig =>
      val body: JsObject = ((rawBody \ "id").asOpt[String] match {
        case None    => rawBody ++ Json.obj("id" -> IdGenerator.token(64))
        case Some(b) => rawBody
      }) ++ ((rawBody \ "groupId").asOpt[String] match {
        case None if globalConfig.autoLinkToDefaultGroup => rawBody ++ Json.obj("groupId" -> "default")
        case Some(b)                                     => rawBody
      })
      ServiceDescriptor.fromJsonSafe(body) match {
        case JsError(e) => BadRequest(Json.obj("error" -> "Bad ServiceDescriptor format")).asFuture
        case JsSuccess(desc, _) =>
          desc.save().map {
            case false => InternalServerError(Json.obj("error" -> "ServiceDescriptor not stored ..."))
            case true => {
              val event: AdminApiEvent = AdminApiEvent(
                env.snowflakeGenerator.nextIdStr(),
                env.env,
                Some(ctx.apiKey),
                ctx.user,
                "CREATE_SERVICE",
                s"User created a service",
                ctx.from,
                ctx.ua,
                desc.toJson
              )
              Audit.send(event)
              Alerts.send(
                ServiceCreatedAlert(env.snowflakeGenerator.nextIdStr(),
                                    env.env,
                                    ctx.user.getOrElse(ctx.apiKey.toJson),
                                    event,
                                    ctx.from,
                                    ctx.ua)
              )
              ServiceDescriptorQuery(desc.subdomain, desc.env, desc.domain, desc.root).addServices(Seq(desc))
              Ok(desc.toJson)
            }
          }
      }
    }
  }

  def updateService(serviceId: String) = ApiAction.async(parse.json) { ctx =>
    env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
      case None => NotFound(Json.obj("error" -> s"ServiceDescriptor with id '$serviceId' not found")).asFuture
      case Some(desc) => {
        ServiceDescriptor.fromJsonSafe(ctx.request.body) match {
          case JsError(e) => BadRequest(Json.obj("error" -> "Bad ServiceDescriptor format")).asFuture
          case JsSuccess(newDesc, _) if newDesc.id != serviceId =>
            BadRequest(Json.obj("error" -> "Bad ServiceDescriptor format")).asFuture
          case JsSuccess(newDesc, _) if newDesc.id == serviceId => {
            val event: AdminApiEvent = AdminApiEvent(
              env.snowflakeGenerator.nextIdStr(),
              env.env,
              Some(ctx.apiKey),
              ctx.user,
              "UPDATE_SERVICE",
              s"User updated a service",
              ctx.from,
              ctx.ua,
              desc.toJson
            )
            Audit.send(event)
            Alerts.send(
              ServiceUpdatedAlert(env.snowflakeGenerator.nextIdStr(),
                                  env.env,
                                  ctx.user.getOrElse(ctx.apiKey.toJson),
                                  event,
                                  ctx.from,
                                  ctx.ua)
            )
            if (desc.canary.enabled && !newDesc.canary.enabled) {
              env.datastores.canaryDataStore.destroyCanarySession(newDesc.id)
            }
            if (desc.clientConfig != newDesc.clientConfig) {
              env.circuitBeakersHolder.resetCircuitBreakersFor(serviceId) // pretty much useless as its mono instance
            }
            if (desc.groupId != newDesc.groupId) {
              env.datastores.apiKeyDataStore.clearFastLookupByService(newDesc.id)
            }
            ServiceDescriptorQuery(desc.subdomain, desc.env, desc.domain, desc.root).remServices(Seq(desc))
            newDesc.save().map { _ =>
              ServiceDescriptorQuery(newDesc.subdomain, newDesc.env, newDesc.domain, newDesc.root)
                .addServices(Seq(newDesc))
              Ok(newDesc.toJson)
            }
          }
        }
      }
    }
  }

  def patchService(serviceId: String) = ApiAction.async(parse.json) { ctx =>
    val body = ctx.request.body
    env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
      case None => NotFound(Json.obj("error" -> s"ServiceDescriptor with id '$serviceId' not found")).asFuture
      case Some(desc) => {
        val currentDescJson = desc.toJson
        val patch           = JsonPatch(body)
        val newDescJson     = patch(currentDescJson)
        ServiceDescriptor.fromJsonSafe(newDescJson) match {
          case JsError(e) => BadRequest(Json.obj("error" -> "Bad ServiceDescriptor format")).asFuture
          case JsSuccess(newDesc, _) if newDesc.id != serviceId =>
            BadRequest(Json.obj("error" -> "Bad ServiceDescriptor format")).asFuture
          case JsSuccess(newDesc, _) if newDesc.id == serviceId => {
            val event: AdminApiEvent = AdminApiEvent(
              env.snowflakeGenerator.nextIdStr(),
              env.env,
              Some(ctx.apiKey),
              ctx.user,
              "UPDATE_SERVICE",
              s"User updated a service",
              ctx.from,
              ctx.ua,
              desc.toJson
            )
            Audit.send(event)
            Alerts.send(
              ServiceUpdatedAlert(env.snowflakeGenerator.nextIdStr(),
                                  env.env,
                                  ctx.user.getOrElse(ctx.apiKey.toJson),
                                  event,
                                  ctx.from,
                                  ctx.ua)
            )
            if (desc.canary.enabled && !newDesc.canary.enabled) {
              env.datastores.canaryDataStore.destroyCanarySession(newDesc.id)
            }
            if (desc.clientConfig != newDesc.clientConfig) {
              env.circuitBeakersHolder.resetCircuitBreakersFor(serviceId) // pretty much useless as its mono instance
            }
            if (desc.groupId != newDesc.groupId) {
              env.datastores.apiKeyDataStore.clearFastLookupByService(newDesc.id)
            }
            ServiceDescriptorQuery(desc.subdomain, desc.env, desc.domain, desc.root).remServices(Seq(desc))
            newDesc.save().map { _ =>
              ServiceDescriptorQuery(newDesc.subdomain, newDesc.env, newDesc.domain, newDesc.root)
                .addServices(Seq(newDesc))
              Ok(newDesc.toJson)
            }
          }
        }
      }
    }
  }

  def deleteService(serviceId: String) = ApiAction.async { ctx =>
    env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
      case None => NotFound(Json.obj("error" -> s"ServiceDescriptor with id: '$serviceId' not found")).asFuture
      case Some(desc) =>
        desc.delete().map { res =>
          val admEvt = AdminApiEvent(
            env.snowflakeGenerator.nextIdStr(),
            env.env,
            Some(ctx.apiKey),
            ctx.user,
            "DELETE_SERVICE",
            s"User deleted a service",
            ctx.from,
            ctx.ua,
            desc.toJson
          )
          Audit.send(admEvt)
          Alerts.send(
            ServiceDeletedAlert(env.snowflakeGenerator.nextIdStr(),
                                env.env,
                                ctx.user.getOrElse(ctx.apiKey.toJson),
                                admEvt,
                                ctx.from,
                                ctx.ua)
          )
          env.datastores.canaryDataStore.destroyCanarySession(desc.id)
          ServiceDescriptorQuery(desc.subdomain, desc.env, desc.domain, desc.root).remServices(Seq(desc))
          Ok(Json.obj("deleted" -> res))
        }
    }
  }

  def allServices() = ApiAction.async { ctx =>
    val paginationPage: Int = ctx.request.queryString.get("page").flatMap(_.headOption).map(_.toInt).getOrElse(1)
    val paginationPageSize: Int =
      ctx.request.queryString.get("pageSize").flatMap(_.headOption).map(_.toInt).getOrElse(Int.MaxValue)
    val paginationPosition            = (paginationPage - 1) * paginationPageSize
    val _env: Option[String]          = ctx.request.queryString.get("env").flatMap(_.headOption)
    val group: Option[String]         = ctx.request.queryString.get("group").flatMap(_.headOption)
    val id: Option[String]            = ctx.request.queryString.get("id").flatMap(_.headOption)
    val name: Option[String]          = ctx.request.queryString.get("name").flatMap(_.headOption)
    val target: Option[String]        = ctx.request.queryString.get("target").flatMap(_.headOption)
    val exposedDomain: Option[String] = ctx.request.queryString.get("exposedDomain").flatMap(_.headOption)
    val domain: Option[String]        = ctx.request.queryString.get("domain").flatMap(_.headOption)
    val subdomain: Option[String]     = ctx.request.queryString.get("subdomain").flatMap(_.headOption)
    val hasFilters = _env
      .orElse(group)
      .orElse(id)
      .orElse(name)
      .orElse(target)
      .orElse(exposedDomain)
      .orElse(domain)
      .orElse(subdomain)
      .isDefined
    Audit.send(
      AdminApiEvent(
        env.snowflakeGenerator.nextIdStr(),
        env.env,
        Some(ctx.apiKey),
        ctx.user,
        "ACCESS_ALL_SERVICES",
        s"User accessed all service descriptors",
        ctx.from,
        ctx.ua,
        Json.obj(
          "env"   -> JsString(_env.getOrElse("--")),
          "group" -> JsString(_env.getOrElse("--"))
        )
      )
    )
    env.datastores.serviceDescriptorDataStore
      .streamedFindAndMat(_ => true, 50, paginationPage, paginationPageSize)
      .map { descs =>
        if (hasFilters) {
          Ok(
            JsArray(
              descs
                .filter {
                  case desc if _env.isDefined && desc.env == _env.get                                 => true
                  case desc if group.isDefined && desc.groupId == group.get                           => true
                  case desc if id.isDefined && desc.id == id.get                                      => true
                  case desc if name.isDefined && desc.name == name.get                                => true
                  case desc if target.isDefined && desc.targets.find(_.asUrl == target.get).isDefined => true
                  case desc if exposedDomain.isDefined && desc.exposedDomain == exposedDomain.get     => true
                  case desc if domain.isDefined && desc.domain == domain.get                          => true
                  case desc if subdomain.isDefined && desc.subdomain == subdomain.get                 => true
                  case _                                                                              => false
                }
                .map(_.toJson)
            )
          )
        } else {
          Ok(JsArray(descs.map(_.toJson)))
        }
      }
  }

  def service(serviceId: String) = ApiAction.async { ctx =>
    env.datastores.serviceDescriptorDataStore.findById(serviceId).map {
      case None => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found"))
      case Some(desc) => {
        Audit.send(
          AdminApiEvent(
            env.snowflakeGenerator.nextIdStr(),
            env.env,
            Some(ctx.apiKey),
            ctx.user,
            "ACCESS_SERVICE",
            s"User accessed a service descriptor",
            ctx.from,
            ctx.ua,
            Json.obj("serviceId" -> serviceId)
          )
        )
        Ok(desc.toJson)
      }
    }
  }

  def serviceTargets(serviceId: String) = ApiAction.async { ctx =>
    env.datastores.serviceDescriptorDataStore.findById(serviceId).map {
      case None => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found"))
      case Some(desc) => {
        Audit.send(
          AdminApiEvent(
            env.snowflakeGenerator.nextIdStr(),
            env.env,
            Some(ctx.apiKey),
            ctx.user,
            "ACCESS_SERVICE_TARGETS",
            s"User accessed a service targets",
            ctx.from,
            ctx.ua,
            Json.obj("serviceId" -> serviceId)
          )
        )
        Ok(JsArray(desc.targets.map(t => JsString(s"${t.scheme}://${t.host}"))))
      }
    }
  }

  def updateServiceTargets(serviceId: String) = ApiAction.async(parse.json) { ctx =>
    val body = ctx.request.body
    env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found")).asFuture
      case Some(desc) => {
        val event = AdminApiEvent(
          env.snowflakeGenerator.nextIdStr(),
          env.env,
          Some(ctx.apiKey),
          ctx.user,
          "UPDATE_SERVICE_TARGETS",
          s"User updated a service targets",
          ctx.from,
          ctx.ua,
          Json.obj("serviceId" -> serviceId, "patch" -> body)
        )
        val actualTargets = JsArray(desc.targets.map(t => JsString(s"${t.scheme}://${t.host}")))
        val patch         = JsonPatch(body)
        val newTargets = patch(actualTargets)
          .as[JsArray]
          .value
          .map(_.as[String])
          .map(s => s.split("://"))
          .map(arr => Target(scheme = arr(0), host = arr(1)))
        val newDesc = desc.copy(targets = newTargets)
        Audit.send(event)
        Alerts.send(
          ServiceUpdatedAlert(env.snowflakeGenerator.nextIdStr(),
                              env.env,
                              ctx.user.getOrElse(ctx.apiKey.toJson),
                              event,
                              ctx.from,
                              ctx.ua)
        )
        ServiceDescriptorQuery(desc.subdomain, desc.env, desc.domain, desc.root).remServices(Seq(desc))
        newDesc.save().map { _ =>
          ServiceDescriptorQuery(newDesc.subdomain, newDesc.env, newDesc.domain, newDesc.root)
            .addServices(Seq(newDesc))
          Ok(JsArray(newTargets.map(t => JsString(s"${t.scheme}://${t.host}"))))
        }
      }
    }
  }

  def serviceAddTarget(serviceId: String) = ApiAction.async(parse.json) { ctx =>
    val body = ctx.request.body
    env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found")).asFuture
      case Some(desc) => {
        val event = AdminApiEvent(
          env.snowflakeGenerator.nextIdStr(),
          env.env,
          Some(ctx.apiKey),
          ctx.user,
          "UPDATE_SERVICE_TARGETS",
          s"User updated a service targets",
          ctx.from,
          ctx.ua,
          Json.obj("serviceId" -> serviceId, "patch" -> body)
        )
        val newTargets = (body \ "target").asOpt[String] match {
          case Some(target) =>
            val parts = target.split("://")
            val tgt   = Target(scheme = parts(0), host = parts(1))
            if (desc.targets.contains(tgt))
              desc.targets
            else
              desc.targets :+ tgt
          case None => desc.targets
        }
        val newDesc = desc.copy(targets = newTargets)
        Audit.send(event)
        Alerts.send(
          ServiceUpdatedAlert(env.snowflakeGenerator.nextIdStr(),
                              env.env,
                              ctx.user.getOrElse(ctx.apiKey.toJson),
                              event,
                              ctx.from,
                              ctx.ua)
        )
        ServiceDescriptorQuery(desc.subdomain, desc.env, desc.domain, desc.root).remServices(Seq(desc))
        newDesc.save().map { _ =>
          ServiceDescriptorQuery(newDesc.subdomain, newDesc.env, newDesc.domain, newDesc.root)
            .addServices(Seq(newDesc))
          Ok(JsArray(newTargets.map(t => JsString(s"${t.scheme}://${t.host}"))))
        }
      }
    }
  }

  def serviceDeleteTarget(serviceId: String) = ApiAction.async(parse.json) { ctx =>
    val body = ctx.request.body
    env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found")).asFuture
      case Some(desc) => {
        val event = AdminApiEvent(
          env.snowflakeGenerator.nextIdStr(),
          env.env,
          Some(ctx.apiKey),
          ctx.user,
          "DELETE_SERVICE_TARGET",
          s"User deleted a service target",
          ctx.from,
          ctx.ua,
          Json.obj("serviceId" -> serviceId, "patch" -> body)
        )
        val newTargets = (body \ "target").asOpt[String] match {
          case Some(target) =>
            val parts = target.split("://")
            val tgt   = Target(scheme = parts(0), host = parts(1))
            if (desc.targets.contains(tgt))
              desc.targets.filterNot(_ == tgt)
            else
              desc.targets
          case None => desc.targets
        }
        val newDesc = desc.copy(targets = newTargets)
        Audit.send(event)
        Alerts.send(
          ServiceUpdatedAlert(env.snowflakeGenerator.nextIdStr(),
                              env.env,
                              ctx.user.getOrElse(ctx.apiKey.toJson),
                              event,
                              ctx.from,
                              ctx.ua)
        )
        ServiceDescriptorQuery(desc.subdomain, desc.env, desc.domain, desc.root).remServices(Seq(desc))
        newDesc.save().map { _ =>
          ServiceDescriptorQuery(newDesc.subdomain, newDesc.env, newDesc.domain, newDesc.root)
            .addServices(Seq(newDesc))
          Ok(JsArray(newTargets.map(t => JsString(s"${t.scheme}://${t.host}"))))
        }
      }
    }
  }

  def serviceLiveStats(serviceId: String) = ApiAction.async { ctx =>
    Audit.send(
      AdminApiEvent(
        env.snowflakeGenerator.nextIdStr(),
        env.env,
        Some(ctx.apiKey),
        ctx.user,
        "ACCESS_SERVICE_LIVESTATS",
        s"User accessed a service descriptor livestats",
        ctx.from,
        ctx.ua,
        Json.obj("serviceId" -> serviceId)
      )
    )
    for {
      calls       <- env.datastores.serviceDescriptorDataStore.calls(serviceId)
      dataIn      <- env.datastores.serviceDescriptorDataStore.dataInFor(serviceId)
      dataOut     <- env.datastores.serviceDescriptorDataStore.dataOutFor(serviceId)
      rate        <- env.datastores.serviceDescriptorDataStore.callsPerSec(serviceId)
      duration    <- env.datastores.serviceDescriptorDataStore.callsDuration(serviceId)
      overhead    <- env.datastores.serviceDescriptorDataStore.callsOverhead(serviceId)
      dataInRate  <- env.datastores.serviceDescriptorDataStore.dataInPerSecFor(serviceId)
      dataOutRate <- env.datastores.serviceDescriptorDataStore.dataOutPerSecFor(serviceId)
    } yield
      Ok(
        Json.obj(
          "calls"       -> calls,
          "dataIn"      -> dataIn,
          "dataOut"     -> dataOut,
          "rate"        -> rate,
          "duration"    -> duration,
          "overhead"    -> overhead,
          "dataInRate"  -> dataInRate,
          "dataOutRate" -> dataOutRate
        )
      )
  }

  def serviceHealth(serviceId: String) = ApiAction.async { ctx =>
    val paginationPage: Int = ctx.request.queryString.get("page").flatMap(_.headOption).map(_.toInt).getOrElse(1)
    val paginationPageSize: Int =
      ctx.request.queryString.get("pageSize").flatMap(_.headOption).map(_.toInt).getOrElse(Int.MaxValue)
    val paginationPosition = (paginationPage - 1) * paginationPageSize
    env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found")).asFuture
      case Some(desc) => {
        Audit.send(
          AdminApiEvent(
            env.snowflakeGenerator.nextIdStr(),
            env.env,
            Some(ctx.apiKey),
            ctx.user,
            "ACCESS_SERVICE_HEALTH",
            s"User accessed a service descriptor helth",
            ctx.from,
            ctx.ua,
            Json.obj("serviceId" -> serviceId)
          )
        )
        env.datastores.healthCheckDataStore
          .findAll(desc)
          .map(evts => Ok(JsArray(evts.drop(paginationPosition).take(paginationPageSize).map(_.toJson)))) // .map(_.toEnrichedJson))))
      }
    }
  }

  def serviceTemplate(serviceId: String) = ApiAction.async { ctx =>
    env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found")).asFuture
      case Some(desc) => {
        env.datastores.errorTemplateDataStore.findById(desc.id).map {
          case Some(template) => Ok(template.toJson)
          case None           => NotFound(Json.obj("error" -> "template not found"))
        }
      }
    }
  }

  def updateServiceTemplate(serviceId: String) = ApiAction.async(parse.json) { ctx =>
    val body: JsObject = (ctx.request.body \ "serviceId").asOpt[String] match {
      case None    => ctx.request.body.as[JsObject] ++ Json.obj("serviceId" -> serviceId)
      case Some(_) => ctx.request.body.as[JsObject]
    }
    env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found")).asFuture
      case Some(_) => {
        ErrorTemplate.fromJsonSafe(body) match {
          case JsError(e) => BadRequest(Json.obj("error" -> "Bad ErrorTemplate format")).asFuture
          case JsSuccess(errorTemplate, _) =>
            env.datastores.errorTemplateDataStore.set(errorTemplate).map {
              case false => InternalServerError(Json.obj("error" -> "ErrorTemplate not stored ..."))
              case true => {
                val event: AdminApiEvent = AdminApiEvent(
                  env.snowflakeGenerator.nextIdStr(),
                  env.env,
                  Some(ctx.apiKey),
                  ctx.user,
                  "UPDATE_ERROR_TEMPLATE",
                  s"User updated an error template",
                  ctx.from,
                  ctx.ua,
                  errorTemplate.toJson
                )
                Audit.send(event)
                Ok(errorTemplate.toJson)
              }
            }
        }
      }
    }
  }

  def createServiceTemplate(serviceId: String) = ApiAction.async(parse.json) { ctx =>
    val body: JsObject = (ctx.request.body \ "serviceId").asOpt[String] match {
      case None    => ctx.request.body.as[JsObject] ++ Json.obj("serviceId" -> serviceId)
      case Some(_) => ctx.request.body.as[JsObject]
    }
    env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found")).asFuture
      case Some(_) => {
        ErrorTemplate.fromJsonSafe(body) match {
          case JsError(e) => BadRequest(Json.obj("error" -> s"Bad ErrorTemplate format $e")).asFuture
          case JsSuccess(errorTemplate, _) =>
            env.datastores.errorTemplateDataStore.set(errorTemplate).map {
              case false => InternalServerError(Json.obj("error" -> "ErrorTemplate not stored ..."))
              case true => {
                val event: AdminApiEvent = AdminApiEvent(
                  env.snowflakeGenerator.nextIdStr(),
                  env.env,
                  Some(ctx.apiKey),
                  ctx.user,
                  "CREATE_ERROR_TEMPLATE",
                  s"User created an error template",
                  ctx.from,
                  ctx.ua,
                  errorTemplate.toJson
                )
                Audit.send(event)
                Ok(errorTemplate.toJson)
              }
            }
        }
      }
    }
  }

  def deleteServiceTemplate(serviceId: String) = ApiAction.async { ctx =>
    env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found")).asFuture
      case Some(desc) => {
        env.datastores.errorTemplateDataStore.findById(desc.id).flatMap {
          case None => NotFound(Json.obj("error" -> "template not found")).asFuture
          case Some(errorTemplate) =>
            env.datastores.errorTemplateDataStore.delete(desc.id).map { _ =>
              val event: AdminApiEvent = AdminApiEvent(
                env.snowflakeGenerator.nextIdStr(),
                env.env,
                Some(ctx.apiKey),
                ctx.user,
                "DELETE_ERROR_TEMPLATE",
                s"User deleted an error template",
                ctx.from,
                ctx.ua,
                errorTemplate.toJson
              )
              Audit.send(event)
              Ok(Json.obj("done" -> true))
            }
        }
      }
    }
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  def createApiKey(serviceId: String) = ApiAction.async(parse.json) { ctx =>
    val body: JsObject = (ctx.request.body \ "clientId").asOpt[String] match {
      case None    => ctx.request.body.as[JsObject] ++ Json.obj("clientId" -> IdGenerator.token(16))
      case Some(b) => ctx.request.body.as[JsObject]
    }
    env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Service with id $serviceId not found")).asFuture
      case Some(desc) =>
        desc.group.flatMap {
          case None => NotFound(Json.obj("error" -> s"Service group not found")).asFuture
          case Some(group) => {
            val apiKeyJson = (body \ "authorizedGroup").asOpt[String] match {
              case None                                 => body ++ Json.obj("authorizedGroup" -> group.id)
              case Some(groupId) if groupId != group.id => body ++ Json.obj("authorizedGroup" -> group.id)
              case Some(groupId) if groupId == group.id => body
            }
            ApiKey.fromJsonSafe(apiKeyJson) match {
              case JsError(e) => BadRequest(Json.obj("error" -> "Bad ApiKey format")).asFuture
              case JsSuccess(apiKey, _) =>
                apiKey.save().map {
                  case false => InternalServerError(Json.obj("error" -> "ApiKey not stored ..."))
                  case true => {
                    val event: AdminApiEvent = AdminApiEvent(
                      env.snowflakeGenerator.nextIdStr(),
                      env.env,
                      Some(ctx.apiKey),
                      ctx.user,
                      "CREATE_APIKEY",
                      s"User created an ApiKey",
                      ctx.from,
                      ctx.ua,
                      Json.obj(
                        "desc"   -> desc.toJson,
                        "apikey" -> apiKey.toJson
                      )
                    )
                    Audit.send(event)
                    Alerts.send(
                      ApiKeyCreatedAlert(env.snowflakeGenerator.nextIdStr(),
                                         env.env,
                                         ctx.user.getOrElse(ctx.apiKey.toJson),
                                         event,
                                         ctx.from,
                                         ctx.ua)
                    )
                    env.datastores.apiKeyDataStore.addFastLookupByService(serviceId, apiKey).map { _ =>
                      env.datastores.apiKeyDataStore.findAll()
                    }
                    Ok(apiKey.toJson)
                  }
                }
            }
          }
        }
    }
  }

  def createApiKeyFromGroup(groupId: String) = ApiAction.async(parse.json) { ctx =>
    val body: JsObject = (ctx.request.body \ "clientId").asOpt[String] match {
      case None    => ctx.request.body.as[JsObject] ++ Json.obj("clientId" -> IdGenerator.token(16))
      case Some(b) => ctx.request.body.as[JsObject]
    }
    env.datastores.serviceGroupDataStore.findById(groupId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Service group not found")).asFuture
      case Some(group) => {
        val apiKeyJson = (body \ "authorizedGroup").asOpt[String] match {
          case None                         => body ++ Json.obj("authorizedGroup" -> group.id)
          case Some(gid) if gid != group.id => body ++ Json.obj("authorizedGroup" -> group.id)
          case Some(gid) if gid == group.id => body
        }
        ApiKey.fromJsonSafe(apiKeyJson) match {
          case JsError(e) => BadRequest(Json.obj("error" -> "Bad ApiKey format")).asFuture
          case JsSuccess(apiKey, _) =>
            apiKey.save().map {
              case false => InternalServerError(Json.obj("error" -> "ApiKey not stored ..."))
              case true => {
                val event: AdminApiEvent = AdminApiEvent(
                  env.snowflakeGenerator.nextIdStr(),
                  env.env,
                  Some(ctx.apiKey),
                  ctx.user,
                  "CREATE_APIKEY",
                  s"User created an ApiKey",
                  ctx.from,
                  ctx.ua,
                  Json.obj(
                    "group"  -> group.toJson,
                    "apikey" -> apiKey.toJson
                  )
                )
                Audit.send(event)
                Alerts.send(
                  ApiKeyCreatedAlert(env.snowflakeGenerator.nextIdStr(),
                                     env.env,
                                     ctx.user.getOrElse(ctx.apiKey.toJson),
                                     event,
                                     ctx.from,
                                     ctx.ua)
                )
                env.datastores.apiKeyDataStore.addFastLookupByGroup(groupId, apiKey).map { _ =>
                  env.datastores.apiKeyDataStore.findAll()
                }
                Ok(apiKey.toJson)
              }
            }
        }
      }
    }
  }

  def updateApiKey(serviceId: String, clientId: String) = ApiAction.async(parse.json) { ctx =>
    env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found")).asFuture
      case Some(desc) =>
        env.datastores.apiKeyDataStore.findById(clientId).flatMap {
          case None => NotFound(Json.obj("error" -> s"ApiKey with clienId '$clientId' not found")).asFuture
          case Some(apiKey) if apiKey.authorizedGroup != desc.groupId =>
            NotFound(
              Json.obj("error" -> s"ApiKey with clienId '$clientId' not found for service with id: '$serviceId'")
            ).asFuture
          case Some(apiKey) if apiKey.authorizedGroup == desc.groupId => {
            ApiKey.fromJsonSafe(ctx.request.body) match {
              case JsError(e) => BadRequest(Json.obj("error" -> "Bad ApiKey format")).asFuture
              case JsSuccess(newApiKey, _) if newApiKey.clientId != clientId =>
                BadRequest(Json.obj("error" -> "Bad ApiKey format")).asFuture
              case JsSuccess(newApiKey, _) if newApiKey.clientId == clientId => {
                val event: AdminApiEvent = AdminApiEvent(
                  env.snowflakeGenerator.nextIdStr(),
                  env.env,
                  Some(ctx.apiKey),
                  ctx.user,
                  "UPDATE_APIKEY",
                  s"User updated an ApiKey",
                  ctx.from,
                  ctx.ua,
                  Json.obj(
                    "desc"   -> desc.toJson,
                    "apikey" -> apiKey.toJson
                  )
                )
                Audit.send(event)
                Alerts.send(
                  ApiKeyUpdatedAlert(env.snowflakeGenerator.nextIdStr(),
                                     env.env,
                                     ctx.user.getOrElse(ctx.apiKey.toJson),
                                     event,
                                     ctx.from,
                                     ctx.ua)
                )
                newApiKey.save().map(_ => Ok(newApiKey.toJson))
              }
            }
          }
        }
    }
  }

  def patchApiKey(serviceId: String, clientId: String) = ApiAction.async(parse.json) { ctx =>
    env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found")).asFuture
      case Some(desc) =>
        env.datastores.apiKeyDataStore.findById(clientId).flatMap {
          case None => NotFound(Json.obj("error" -> s"ApiKey with clienId '$clientId' not found")).asFuture
          case Some(apiKey) if apiKey.authorizedGroup != desc.groupId =>
            NotFound(
              Json.obj("error" -> s"ApiKey with clienId '$clientId' not found for service with id: '$serviceId'")
            ).asFuture
          case Some(apiKey) if apiKey.authorizedGroup == desc.groupId => {
            val currentApiKeyJson = apiKey.toJson
            val patch             = JsonPatch(ctx.request.body)
            val newApiKeyJson     = patch(currentApiKeyJson)
            ApiKey.fromJsonSafe(newApiKeyJson) match {
              case JsError(e) => BadRequest(Json.obj("error" -> "Bad ApiKey format")).asFuture
              case JsSuccess(newApiKey, _) if newApiKey.clientId != clientId =>
                BadRequest(Json.obj("error" -> "Bad ApiKey format")).asFuture
              case JsSuccess(newApiKey, _) if newApiKey.clientId == clientId => {
                val event: AdminApiEvent = AdminApiEvent(
                  env.snowflakeGenerator.nextIdStr(),
                  env.env,
                  Some(ctx.apiKey),
                  ctx.user,
                  "UPDATE_APIKEY",
                  s"User updated an ApiKey",
                  ctx.from,
                  ctx.ua,
                  Json.obj(
                    "desc"   -> desc.toJson,
                    "apikey" -> apiKey.toJson
                  )
                )
                Audit.send(event)
                Alerts.send(
                  ApiKeyUpdatedAlert(env.snowflakeGenerator.nextIdStr(),
                                     env.env,
                                     ctx.user.getOrElse(ctx.apiKey.toJson),
                                     event,
                                     ctx.from,
                                     ctx.ua)
                )
                newApiKey.save().map(_ => Ok(newApiKey.toJson))
              }
            }
          }
        }
    }
  }

  def updateApiKeyFromGroup(groupId: String, clientId: String) = ApiAction.async(parse.json) { ctx =>
    env.datastores.serviceGroupDataStore.findById(groupId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Service Group with id: '$groupId' not found")).asFuture
      case Some(group) =>
        env.datastores.apiKeyDataStore.findById(clientId).flatMap {
          case None => NotFound(Json.obj("error" -> s"ApiKey with clienId '$clientId' not found")).asFuture
          case Some(apiKey) if apiKey.authorizedGroup != group.id =>
            NotFound(Json.obj("error" -> s"ApiKey with clienId '$clientId' not found for group with id: '$groupId'")).asFuture
          case Some(apiKey) if apiKey.authorizedGroup == group.id => {
            ApiKey.fromJsonSafe(ctx.request.body) match {
              case JsError(e) => BadRequest(Json.obj("error" -> "Bad ApiKey format")).asFuture
              case JsSuccess(newApiKey, _) if newApiKey.clientId != clientId =>
                BadRequest(Json.obj("error" -> "Bad ApiKey format")).asFuture
              case JsSuccess(newApiKey, _) if newApiKey.clientId == clientId => {
                val event: AdminApiEvent = AdminApiEvent(
                  env.snowflakeGenerator.nextIdStr(),
                  env.env,
                  Some(ctx.apiKey),
                  ctx.user,
                  "UPDATE_APIKEY",
                  s"User updated an ApiKey",
                  ctx.from,
                  ctx.ua,
                  Json.obj(
                    "group"  -> group.toJson,
                    "apikey" -> apiKey.toJson
                  )
                )
                Audit.send(event)
                Alerts.send(
                  ApiKeyUpdatedAlert(env.snowflakeGenerator.nextIdStr(),
                                     env.env,
                                     ctx.user.getOrElse(ctx.apiKey.toJson),
                                     event,
                                     ctx.from,
                                     ctx.ua)
                )
                newApiKey.save().map(_ => Ok(newApiKey.toJson))
              }
            }
          }
        }
    }
  }

  def patchApiKeyFromGroup(groupId: String, clientId: String) = ApiAction.async(parse.json) { ctx =>
    env.datastores.serviceGroupDataStore.findById(groupId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Service Group with id: '$groupId' not found")).asFuture
      case Some(group) =>
        env.datastores.apiKeyDataStore.findById(clientId).flatMap {
          case None => NotFound(Json.obj("error" -> s"ApiKey with clienId '$clientId' not found")).asFuture
          case Some(apiKey) if apiKey.authorizedGroup != group.id =>
            NotFound(Json.obj("error" -> s"ApiKey with clienId '$clientId' not found for group with id: '$groupId'")).asFuture
          case Some(apiKey) if apiKey.authorizedGroup == group.id => {
            val currentApiKeyJson = apiKey.toJson
            val patch             = JsonPatch(ctx.request.body)
            val newApiKeyJson     = patch(currentApiKeyJson)
            ApiKey.fromJsonSafe(newApiKeyJson) match {
              case JsError(e) => BadRequest(Json.obj("error" -> "Bad ApiKey format")).asFuture
              case JsSuccess(newApiKey, _) if newApiKey.clientId != clientId =>
                BadRequest(Json.obj("error" -> "Bad ApiKey format")).asFuture
              case JsSuccess(newApiKey, _) if newApiKey.clientId == clientId => {
                val event: AdminApiEvent = AdminApiEvent(
                  env.snowflakeGenerator.nextIdStr(),
                  env.env,
                  Some(ctx.apiKey),
                  ctx.user,
                  "UPDATE_APIKEY",
                  s"User updated an ApiKey",
                  ctx.from,
                  ctx.ua,
                  Json.obj(
                    "group"  -> group.toJson,
                    "apikey" -> apiKey.toJson
                  )
                )
                Audit.send(event)
                Alerts.send(
                  ApiKeyUpdatedAlert(env.snowflakeGenerator.nextIdStr(),
                                     env.env,
                                     ctx.user.getOrElse(ctx.apiKey.toJson),
                                     event,
                                     ctx.from,
                                     ctx.ua)
                )
                newApiKey.save().map(_ => Ok(newApiKey.toJson))
              }
            }
          }
        }
    }
  }

  def deleteApiKeyFromGroup(groupId: String, clientId: String) = ApiAction.async { ctx =>
    env.datastores.serviceGroupDataStore.findById(groupId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Group with id: '$groupId' not found")).asFuture
      case Some(group) =>
        env.datastores.apiKeyDataStore.findById(clientId).flatMap {
          case None => NotFound(Json.obj("error" -> s"ApiKey with clienId '$clientId' not found")).asFuture
          case Some(apiKey) if apiKey.authorizedGroup != group.id =>
            NotFound(Json.obj("error" -> s"ApiKey with clienId '$clientId' not found for group with id: '$groupId'")).asFuture
          case Some(apiKey) if apiKey.authorizedGroup == group.id => {
            val event: AdminApiEvent = AdminApiEvent(
              env.snowflakeGenerator.nextIdStr(),
              env.env,
              Some(ctx.apiKey),
              ctx.user,
              "DELETE_APIKEY",
              s"User deleted an ApiKey",
              ctx.from,
              ctx.ua,
              Json.obj(
                "group"  -> group.toJson,
                "apikey" -> apiKey.toJson
              )
            )
            Audit.send(event)
            Alerts.send(
              ApiKeyDeletedAlert(env.snowflakeGenerator.nextIdStr(),
                                 env.env,
                                 ctx.user.getOrElse(ctx.apiKey.toJson),
                                 event,
                                 ctx.from,
                                 ctx.ua)
            )
            env.datastores.apiKeyDataStore.deleteFastLookupByGroup(groupId, apiKey)
            apiKey.delete().map(res => Ok(Json.obj("deleted" -> true)))
          }
        }
    }
  }

  def deleteApiKey(serviceId: String, clientId: String) = ApiAction.async { ctx =>
    env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found")).asFuture
      case Some(desc) =>
        env.datastores.apiKeyDataStore.findById(clientId).flatMap {
          case None => NotFound(Json.obj("error" -> s"ApiKey with clienId '$clientId' not found")).asFuture
          case Some(apiKey) if apiKey.authorizedGroup != desc.groupId =>
            NotFound(
              Json.obj("error" -> s"ApiKey with clienId '$clientId' not found for service with id: '$serviceId'")
            ).asFuture
          case Some(apiKey) if apiKey.authorizedGroup == desc.groupId => {
            val event: AdminApiEvent = AdminApiEvent(
              env.snowflakeGenerator.nextIdStr(),
              env.env,
              Some(ctx.apiKey),
              ctx.user,
              "DELETE_APIKEY",
              s"User deleted an ApiKey",
              ctx.from,
              ctx.ua,
              Json.obj(
                "desc"   -> desc.toJson,
                "apikey" -> apiKey.toJson
              )
            )
            Audit.send(event)
            Alerts.send(
              ApiKeyDeletedAlert(env.snowflakeGenerator.nextIdStr(),
                                 env.env,
                                 ctx.user.getOrElse(ctx.apiKey.toJson),
                                 event,
                                 ctx.from,
                                 ctx.ua)
            )
            env.datastores.apiKeyDataStore.deleteFastLookupByService(serviceId, apiKey)
            apiKey.delete().map(res => Ok(Json.obj("deleted" -> true)))
          }
        }
    }
  }

  def apiKeys(serviceId: String) = ApiAction.async { ctx =>
    val paginationPage: Int = ctx.request.queryString.get("page").flatMap(_.headOption).map(_.toInt).getOrElse(1)
    val paginationPageSize: Int =
      ctx.request.queryString.get("pageSize").flatMap(_.headOption).map(_.toInt).getOrElse(Int.MaxValue)
    val paginationPosition       = (paginationPage - 1) * paginationPageSize
    val clientId: Option[String] = ctx.request.queryString.get("clientId").flatMap(_.headOption)
    val name: Option[String]     = ctx.request.queryString.get("name").flatMap(_.headOption)
    val group: Option[String]    = ctx.request.queryString.get("group").flatMap(_.headOption)
    val enabled: Option[String]  = ctx.request.queryString.get("enabled").flatMap(_.headOption)
    val hasFilters               = clientId.orElse(name).orElse(group).orElse(name).orElse(enabled).isDefined
    env.datastores.apiKeyDataStore.findByService(serviceId).fold {
      case Failure(_) => NotFound(Json.obj("error" -> s"ApiKeys for service with id: '$serviceId' does not exist"))
      case Success(apiKeys) => {
        Audit.send(
          AdminApiEvent(
            env.snowflakeGenerator.nextIdStr(),
            env.env,
            Some(ctx.apiKey),
            ctx.user,
            "ACCESS_SERVICE_APIKEYS",
            s"User accessed apikeys from a service descriptor",
            ctx.from,
            ctx.ua,
            Json.obj("serviceId" -> serviceId)
          )
        )
        if (hasFilters) {
          Ok(
            JsArray(
              apiKeys
                .filter {
                  case keys if group.isDefined && keys.authorizedGroup == group.get       => true
                  case keys if clientId.isDefined && keys.clientId == clientId.get        => true
                  case keys if name.isDefined && keys.clientName == name.get              => true
                  case keys if enabled.isDefined && keys.enabled == enabled.get.toBoolean => true
                  case _                                                                  => false
                }
                .drop(paginationPosition)
                .take(paginationPageSize)
                .map(_.toJson)
            )
          )
        } else {
          Ok(JsArray(apiKeys.map(_.toJson)))
        }
      }
    }
  }

  def apiKeysFromGroup(groupId: String) = ApiAction.async { ctx =>
    val paginationPage: Int = ctx.request.queryString.get("page").flatMap(_.headOption).map(_.toInt).getOrElse(1)
    val paginationPageSize: Int =
      ctx.request.queryString.get("pageSize").flatMap(_.headOption).map(_.toInt).getOrElse(Int.MaxValue)
    val paginationPosition       = (paginationPage - 1) * paginationPageSize
    val clientId: Option[String] = ctx.request.queryString.get("clientId").flatMap(_.headOption)
    val name: Option[String]     = ctx.request.queryString.get("name").flatMap(_.headOption)
    val group: Option[String]    = ctx.request.queryString.get("group").flatMap(_.headOption)
    val enabled: Option[String]  = ctx.request.queryString.get("enabled").flatMap(_.headOption)
    val hasFilters               = clientId.orElse(name).orElse(group).orElse(name).orElse(enabled).isDefined
    env.datastores.apiKeyDataStore.findByGroup(groupId).fold {
      case Failure(_) => NotFound(Json.obj("error" -> s"ApiKeys for group with id: '$groupId' does not exist"))
      case Success(apiKeys) => {
        Audit.send(
          AdminApiEvent(
            env.snowflakeGenerator.nextIdStr(),
            env.env,
            Some(ctx.apiKey),
            ctx.user,
            "ACCESS_SERVICE_APIKEYS",
            s"User accessed apikeys from a group",
            ctx.from,
            ctx.ua,
            Json.obj("groupId" -> groupId)
          )
        )
        if (hasFilters) {
          Ok(
            JsArray(
              apiKeys
                .filter {
                  case keys if group.isDefined && keys.authorizedGroup == group.get       => true
                  case keys if clientId.isDefined && keys.clientId == clientId.get        => true
                  case keys if name.isDefined && keys.clientName == name.get              => true
                  case keys if enabled.isDefined && keys.enabled == enabled.get.toBoolean => true
                  case _                                                                  => false
                }
                .drop(paginationPosition)
                .take(paginationPageSize)
                .map(_.toJson)
            )
          )
        } else {
          Ok(JsArray(apiKeys.map(_.toJson)))
        }
      }
    }
  }

  def allApiKeys() = ApiAction.async { ctx =>
    Audit.send(
      AdminApiEvent(
        env.snowflakeGenerator.nextIdStr(),
        env.env,
        Some(ctx.apiKey),
        ctx.user,
        "ACCESS_ALL_APIKEYS",
        s"User accessed all apikeys",
        ctx.from,
        ctx.ua
      )
    )
    val paginationPage: Int = ctx.request.queryString.get("page").flatMap(_.headOption).map(_.toInt).getOrElse(1)
    val paginationPageSize: Int =
      ctx.request.queryString.get("pageSize").flatMap(_.headOption).map(_.toInt).getOrElse(Int.MaxValue)
    val paginationPosition       = (paginationPage - 1) * paginationPageSize
    val clientId: Option[String] = ctx.request.queryString.get("clientId").flatMap(_.headOption)
    val name: Option[String]     = ctx.request.queryString.get("name").flatMap(_.headOption)
    val group: Option[String]    = ctx.request.queryString.get("group").flatMap(_.headOption)
    val enabled: Option[String]  = ctx.request.queryString.get("enabled").flatMap(_.headOption)
    val hasFilters               = clientId.orElse(name).orElse(group).orElse(name).orElse(enabled).isDefined
    env.datastores.apiKeyDataStore.streamedFindAndMat(_ => true, 50, paginationPage, paginationPageSize).map { keys =>
      if (hasFilters) {
        Ok(
          JsArray(
            keys
              .filter {
                case keys if group.isDefined && keys.authorizedGroup == group.get       => true
                case keys if clientId.isDefined && keys.clientId == clientId.get        => true
                case keys if name.isDefined && keys.clientName == name.get              => true
                case keys if enabled.isDefined && keys.enabled == enabled.get.toBoolean => true
                case _                                                                  => false
              }
              .map(_.toJson)
          )
        )
      } else {
        Ok(JsArray(keys.map(_.toJson)))
      }
    }
  }

  def apiKey(serviceId: String, clientId: String) = ApiAction.async { ctx =>
    env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found")).asFuture
      case Some(desc) =>
        env.datastores.apiKeyDataStore.findById(clientId).map {
          case None => NotFound(Json.obj("error" -> s"ApiKey with clienId '$clientId' not found"))
          case Some(apiKey) if apiKey.authorizedGroup != desc.groupId =>
            NotFound(
              Json.obj("error" -> s"ApiKey with clienId '$clientId' not found for service with id: '$serviceId'")
            )
          case Some(apiKey) if apiKey.authorizedGroup == desc.groupId => {
            Audit.send(
              AdminApiEvent(
                env.snowflakeGenerator.nextIdStr(),
                env.env,
                Some(ctx.apiKey),
                ctx.user,
                "ACCESS_SERVICE_APIKEY",
                s"User accessed an apikey from a service descriptor",
                ctx.from,
                ctx.ua,
                Json.obj("serviceId" -> serviceId, "clientId" -> clientId)
              )
            )
            Ok(apiKey.toJson)
          }
        }
    }
  }

  def apiKeyFromGroup(groupId: String, clientId: String) = ApiAction.async { ctx =>
    env.datastores.serviceGroupDataStore.findById(groupId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Group with id: '$groupId' not found")).asFuture
      case Some(group) =>
        env.datastores.apiKeyDataStore.findById(clientId).map {
          case None => NotFound(Json.obj("error" -> s"ApiKey with clienId '$clientId' not found"))
          case Some(apiKey) if apiKey.authorizedGroup != group.id =>
            NotFound(Json.obj("error" -> s"ApiKey with clienId '$clientId' not found for group with id: '$groupId'"))
          case Some(apiKey) if apiKey.authorizedGroup == group.id => {
            Audit.send(
              AdminApiEvent(
                env.snowflakeGenerator.nextIdStr(),
                env.env,
                Some(ctx.apiKey),
                ctx.user,
                "ACCESS_SERVICE_APIKEY",
                s"User accessed an apikey from a service descriptor",
                ctx.from,
                ctx.ua,
                Json.obj("groupId" -> groupId, "clientId" -> clientId)
              )
            )
            Ok(apiKey.toJson)
          }
        }
    }
  }

  def apiKeyGroup(serviceId: String, clientId: String) = ApiAction.async { ctx =>
    env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found")).asFuture
      case Some(desc) =>
        env.datastores.apiKeyDataStore.findById(clientId).flatMap {
          case None => NotFound(Json.obj("error" -> s"ApiKey with clienId '$clientId' not found")).asFuture
          case Some(apiKey) if apiKey.authorizedGroup != desc.groupId =>
            NotFound(
              Json.obj("error" -> s"ApiKey with clienId '$clientId' not found for service with id: '$serviceId'")
            ).asFuture
          case Some(apiKey) if apiKey.authorizedGroup == desc.groupId =>
            apiKey.group.map {
              case None => NotFound(Json.obj("error" -> s"ServiceGroup for ApiKey '$clientId' not found"))
              case Some(group) => {
                Audit.send(
                  AdminApiEvent(
                    env.snowflakeGenerator.nextIdStr(),
                    env.env,
                    Some(ctx.apiKey),
                    ctx.user,
                    "ACCESS_SERVICE_APIKEY_GROUP",
                    s"User accessed an apikey servicegroup from a service descriptor",
                    ctx.from,
                    ctx.ua,
                    Json.obj("serviceId" -> serviceId, "clientId" -> clientId)
                  )
                )
                Ok(group.toJson)
              }
            }
        }
    }
  }

  // fixme : use a body to update
  def updateApiKeyGroup(serviceId: String, clientId: String) = ApiAction.async(parse.json) { ctx =>
    env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found")).asFuture
      case Some(desc) =>
        env.datastores.apiKeyDataStore.findById(clientId).flatMap {
          case None => NotFound(Json.obj("error" -> s"ApiKey with clienId '$clientId' not found")).asFuture
          case Some(apiKey) if apiKey.authorizedGroup != desc.groupId =>
            NotFound(
              Json.obj("error" -> s"ApiKey with clienId '$clientId' not found for service with id: '$serviceId'")
            ).asFuture
          case Some(apiKey) if apiKey.authorizedGroup == desc.groupId =>
            apiKey.group.flatMap {
              case None => NotFound(Json.obj("error" -> s"ServiceGroup for ApiKey '$clientId' not found")).asFuture
              case Some(group) => {
                val newApiKey = apiKey.copy(authorizedGroup = group.id)
                val event: AdminApiEvent = AdminApiEvent(
                  env.snowflakeGenerator.nextIdStr(),
                  env.env,
                  Some(ctx.apiKey),
                  ctx.user,
                  "UPDATE_APIKEY",
                  s"User updated an ApiKey",
                  ctx.from,
                  ctx.ua,
                  Json.obj(
                    "desc"   -> desc.toJson,
                    "apikey" -> apiKey.toJson
                  )
                )
                Audit.send(event)
                Alerts.send(
                  ApiKeyUpdatedAlert(env.snowflakeGenerator.nextIdStr(),
                                     env.env,
                                     ctx.user.getOrElse(ctx.apiKey.toJson),
                                     event,
                                     ctx.from,
                                     ctx.ua)
                )
                newApiKey.save().map(_ => Ok(newApiKey.toJson))
              }
            }
        }
    }
  }

  def apiKeyQuotas(serviceId: String, clientId: String) = ApiAction.async { ctx =>
    env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found")).asFuture
      case Some(desc) =>
        env.datastores.apiKeyDataStore.findById(clientId).flatMap {
          case None => NotFound(Json.obj("error" -> s"ApiKey with clienId '$clientId' not found")).asFuture
          case Some(apiKey) if apiKey.authorizedGroup != desc.groupId =>
            NotFound(
              Json.obj("error" -> s"ApiKey with clienId '$clientId' not found for service with id: '$serviceId'")
            ).asFuture
          case Some(apiKey) if apiKey.authorizedGroup == desc.groupId => {
            Audit.send(
              AdminApiEvent(
                env.snowflakeGenerator.nextIdStr(),
                env.env,
                Some(ctx.apiKey),
                ctx.user,
                "ACCESS_SERVICE_APIKEY_QUOTAS",
                s"User accessed an apikey quotas from a service descriptor",
                ctx.from,
                ctx.ua,
                Json.obj("serviceId" -> serviceId, "clientId" -> clientId)
              )
            )
            apiKey.remainingQuotas().map(rq => Ok(rq.toJson))
          }
        }
    }
  }

  def resetApiKeyQuotas(serviceId: String, clientId: String) = ApiAction.async { ctx =>
    env.datastores.serviceDescriptorDataStore.findById(serviceId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Service with id: '$serviceId' not found")).asFuture
      case Some(desc) =>
        env.datastores.apiKeyDataStore.findById(clientId).flatMap {
          case None => NotFound(Json.obj("error" -> s"ApiKey with clienId '$clientId' not found")).asFuture
          case Some(apiKey) if apiKey.authorizedGroup != desc.groupId =>
            NotFound(
              Json.obj("error" -> s"ApiKey with clienId '$clientId' not found for service with id: '$serviceId'")
            ).asFuture
          case Some(apiKey) if apiKey.authorizedGroup == desc.groupId => {
            Audit.send(
              AdminApiEvent(
                env.snowflakeGenerator.nextIdStr(),
                env.env,
                Some(ctx.apiKey),
                ctx.user,
                "RESET_SERVICE_APIKEY_QUOTAS",
                s"User reset an apikey quotas for a service descriptor",
                ctx.from,
                ctx.ua,
                Json.obj("serviceId" -> serviceId, "clientId" -> clientId)
              )
            )
            env.datastores.apiKeyDataStore.resetQuotas(apiKey).map(rq => Ok(rq.toJson))
          }
        }
    }
  }

  def apiKeyFromGroupQuotas(groupId: String, clientId: String) = ApiAction.async { ctx =>
    env.datastores.serviceGroupDataStore.findById(groupId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Group with id: '$groupId' not found")).asFuture
      case Some(group) =>
        env.datastores.apiKeyDataStore.findById(clientId).flatMap {
          case None => NotFound(Json.obj("error" -> s"ApiKey with clienId '$clientId' not found")).asFuture
          case Some(apiKey) if apiKey.authorizedGroup != group.id =>
            NotFound(Json.obj("error" -> s"ApiKey with clienId '$clientId' not found for group with id: '$groupId'")).asFuture
          case Some(apiKey) if apiKey.authorizedGroup == group.id => {
            Audit.send(
              AdminApiEvent(
                env.snowflakeGenerator.nextIdStr(),
                env.env,
                Some(ctx.apiKey),
                ctx.user,
                "ACCESS_SERVICE_APIKEY_QUOTAS",
                s"User accessed an apikey quotas from a service descriptor",
                ctx.from,
                ctx.ua,
                Json.obj("groupId" -> groupId, "clientId" -> clientId)
              )
            )
            apiKey.remainingQuotas().map(rq => Ok(rq.toJson))
          }
        }
    }
  }

  def resetApiKeyFromGroupQuotas(groupId: String, clientId: String) = ApiAction.async { ctx =>
    env.datastores.serviceGroupDataStore.findById(groupId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Group with id: '$groupId' not found")).asFuture
      case Some(group) =>
        env.datastores.apiKeyDataStore.findById(clientId).flatMap {
          case None => NotFound(Json.obj("error" -> s"ApiKey with clienId '$clientId' not found")).asFuture
          case Some(apiKey) if apiKey.authorizedGroup != group.id =>
            NotFound(Json.obj("error" -> s"ApiKey with clienId '$clientId' not found for group with id: '$groupId'")).asFuture
          case Some(apiKey) if apiKey.authorizedGroup == group.id => {
            Audit.send(
              AdminApiEvent(
                env.snowflakeGenerator.nextIdStr(),
                env.env,
                Some(ctx.apiKey),
                ctx.user,
                "RESET_SERVICE_APIKEY_QUOTAS",
                s"User accessed an apikey quotas from a service descriptor",
                ctx.from,
                ctx.ua,
                Json.obj("groupId" -> groupId, "clientId" -> clientId)
              )
            )
            env.datastores.apiKeyDataStore.resetQuotas(apiKey).map(rq => Ok(rq.toJson))
          }
        }
    }
  }

  ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  def serviceCanaryMembers(serviceId: String) = ApiAction.async { ctx =>
    env.datastores.canaryDataStore.canaryCampaign(serviceId).map { campaign =>
      Ok(
        Json.obj(
          "canaryUsers"   -> campaign.canaryUsers,
          "standardUsers" -> campaign.standardUsers
        )
      )
    }
  }

  def resetServiceCanaryMembers(serviceId: String) = ApiAction.async { ctx =>
    env.datastores.canaryDataStore.destroyCanarySession(serviceId).map { done =>
      Ok(Json.obj("done" -> done))
    }
  }

  ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  def fullExport() = ApiAction.async { ctx =>
    ctx.request.accepts("application/x-ndjson") match {
      case true => {
        env.datastores.fullNdJsonExport().map { source =>
          val event = AdminApiEvent(
            env.snowflakeGenerator.nextIdStr(),
            env.env,
            Some(ctx.apiKey),
            ctx.user,
            "FULL_OTOROSHI_EXPORT",
            s"Admin exported Otoroshi",
            ctx.from,
            ctx.ua,
            Json.obj()
          )
          Audit.send(event)
          Alerts.send(
            OtoroshiExportAlert(env.snowflakeGenerator.nextIdStr(),
              env.env,
              ctx.user.getOrElse(Json.obj()),
              event,
              Json.obj(),
              ctx.from,
              ctx.ua)
          )
          Ok.sendEntity(HttpEntity.Streamed.apply(source.map(v => ByteString(Json.stringify(v) + "\n")), None, Some("application/x-ndjson"))).as("application/x-ndjson")
        }
      }
      case false => {
        env.datastores.globalConfigDataStore.fullExport().map { e =>
          val event = AdminApiEvent(
            env.snowflakeGenerator.nextIdStr(),
            env.env,
            Some(ctx.apiKey),
            ctx.user,
            "FULL_OTOROSHI_EXPORT",
            s"Admin exported Otoroshi",
            ctx.from,
            ctx.ua,
            e
          )
          Audit.send(event)
          Alerts.send(
            OtoroshiExportAlert(env.snowflakeGenerator.nextIdStr(),
              env.env,
              ctx.user.getOrElse(Json.obj()),
              event,
              e,
              ctx.from,
              ctx.ua)
          )
          Ok(Json.prettyPrint(e)).as("application/json")
        }
      }
    }
  }

  def fullImportFromFile() = ApiAction.async(parse.temporaryFile) { ctx =>
    ctx.request.headers.get("X-Content-Type") match {
      case Some("application/x-ndjson") => {
        val body = FileIO.fromPath(ctx.request.body.path)
        val source = body
          .via(Framing.delimiter(ByteString("\n"), Int.MaxValue, false))
          .map(v => Json.parse(v.utf8String))
        env.datastores
          .fullNdJsonImport(source)
          .map(_ => Ok(Json.obj("done" -> true))) recover {
          case e => InternalServerError(Json.obj("error" -> e.getMessage))
        }
      }
      case _ => {
        val source = scala.io.Source.fromFile(ctx.request.body.path.toFile, "utf-8").getLines().mkString("\n")
        val json   = Json.parse(source).as[JsObject]
        env.datastores.globalConfigDataStore
          .fullImport(json)
          .map(_ => Ok(Json.obj("done" -> true)))
          .recover {
            case e => InternalServerError(Json.obj("error" -> e.getMessage))
          }
      }
    }
  }

  def fullImport() = ApiAction.async(sourceBodyParser) { ctx =>
    ctx.request.contentType match {
      case Some("application/x-ndjson") => {
        val source = ctx.request.body
          .via(Framing.delimiter(ByteString("\n"), Int.MaxValue, false))
          .map(v => Json.parse(v.utf8String))
        env.datastores
          .fullNdJsonImport(source)
          .map(_ => Ok(Json.obj("done" -> true))) recover {
            case e => InternalServerError(Json.obj("error" -> e.getMessage))
          }
      }
      case _ => {
        ctx.request.body.runFold(ByteString.empty)(_ ++ _).flatMap { body =>
          val json = Json.parse(body.utf8String).as[JsObject]
          env.datastores.globalConfigDataStore
            .fullImport(json)
            .map(_ => Ok(Json.obj("done" -> true)))
        } recover {
          case e => InternalServerError(Json.obj("error" -> e.getMessage))
        }
      }
    }
  }

  ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  def startSnowMonkey() = ApiAction.async { ctx =>
    env.datastores.chaosDataStore.startSnowMonkey().map { _ =>
      val event = AdminApiEvent(
        env.snowflakeGenerator.nextIdStr(),
        env.env,
        Some(ctx.apiKey),
        ctx.user,
        "STARTED_SNOWMONKEY",
        s"User started snowmonkey",
        ctx.from,
        ctx.ua,
        Json.obj()
      )
      Audit.send(event)
      Alerts.send(
        SnowMonkeyStartedAlert(env.snowflakeGenerator.nextIdStr(),
                               env.env,
                               ctx.user.getOrElse(ctx.apiKey.toJson),
                               event,
                               ctx.from,
                               ctx.ua)
      )
      Ok(Json.obj("done" -> true))
    }
  }

  def stopSnowMonkey() = ApiAction.async { ctx =>
    env.datastores.chaosDataStore.stopSnowMonkey().map { _ =>
      val event = AdminApiEvent(
        env.snowflakeGenerator.nextIdStr(),
        env.env,
        Some(ctx.apiKey),
        ctx.user,
        "STOPPED_SNOWMONKEY",
        s"User stopped snowmonkey",
        ctx.from,
        ctx.ua,
        Json.obj()
      )
      Audit.send(event)
      Alerts.send(
        SnowMonkeyStoppedAlert(env.snowflakeGenerator.nextIdStr(),
                               env.env,
                               ctx.user.getOrElse(ctx.apiKey.toJson),
                               event,
                               ctx.from,
                               ctx.ua)
      )
      Ok(Json.obj("done" -> true))
    }
  }

  def getSnowMonkeyOutages() = ApiAction.async { ctx =>
    env.datastores.chaosDataStore.getOutages().map { outages =>
      Ok(JsArray(outages.map(_.asJson)))
    }
  }

  def getSnowMonkeyConfig() = ApiAction.async { ctx =>
    env.datastores.globalConfigDataStore.singleton().map { c =>
      Ok(c.snowMonkeyConfig.asJson)
    }
  }

  def updateSnowMonkey() = ApiAction.async(parse.json) { ctx =>
    SnowMonkeyConfig.fromJsonSafe(ctx.request.body) match {
      case JsError(e) => BadRequest(Json.obj("error" -> "Bad SnowMonkeyConfig format")).asFuture
      case JsSuccess(config, _) => {
        config.save().map { _ =>
          val event = AdminApiEvent(
            env.snowflakeGenerator.nextIdStr(),
            env.env,
            Some(ctx.apiKey),
            ctx.user,
            "UPDATED_SNOWMONKEY_CONFIG",
            s"User updated snowmonkey config",
            ctx.from,
            ctx.ua,
            config.asJson
          )
          Audit.send(event)
          Alerts.send(
            SnowMonkeyConfigUpdatedAlert(env.snowflakeGenerator.nextIdStr(),
                                         env.env,
                                         ctx.user.getOrElse(ctx.apiKey.toJson),
                                         event,
                                         ctx.from,
                                         ctx.ua)
          )
          Ok(config.asJson)
        }
      }
    }
  }

  def patchSnowMonkey() = ApiAction.async(parse.json) { ctx =>
    env.datastores.globalConfigDataStore.singleton().flatMap { globalConfig =>
      val currentSnowMonkeyConfigJson = globalConfig.snowMonkeyConfig.asJson
      val patch                       = JsonPatch(ctx.request.body)
      val newSnowMonkeyConfigJson     = patch(currentSnowMonkeyConfigJson)
      SnowMonkeyConfig.fromJsonSafe(newSnowMonkeyConfigJson) match {
        case JsError(e) => BadRequest(Json.obj("error" -> "Bad SnowMonkeyConfig format")).asFuture
        case JsSuccess(newSnowMonkeyConfig, _) => {
          val event: AdminApiEvent = AdminApiEvent(
            env.snowflakeGenerator.nextIdStr(),
            env.env,
            Some(ctx.apiKey),
            ctx.user,
            "PATCH_SNOWMONKEY_CONFIG",
            s"User patched snowmonkey config",
            ctx.from,
            ctx.ua,
            newSnowMonkeyConfigJson
          )
          Audit.send(event)
          Alerts.send(
            SnowMonkeyConfigUpdatedAlert(env.snowflakeGenerator.nextIdStr(),
                                         env.env,
                                         ctx.user.getOrElse(ctx.apiKey.toJson),
                                         event,
                                         ctx.from,
                                         ctx.ua)
          )
          newSnowMonkeyConfig.save().map(_ => Ok(newSnowMonkeyConfig.asJson))
        }
      }
    }
  }

  def resetSnowMonkey() = ApiAction.async { ctx =>
    env.datastores.chaosDataStore.resetOutages().map { _ =>
      val event: AdminApiEvent = AdminApiEvent(
        env.snowflakeGenerator.nextIdStr(),
        env.env,
        Some(ctx.apiKey),
        ctx.user,
        "RESET_SNOWMONKEY_OUTAGES",
        s"User reset snowmonkey outages for the day",
        ctx.from,
        ctx.ua,
        Json.obj()
      )
      Audit.send(event)
      Alerts.send(
        SnowMonkeyResetAlert(env.snowflakeGenerator.nextIdStr(),
                             env.env,
                             ctx.user.getOrElse(ctx.apiKey.toJson),
                             event,
                             ctx.from,
                             ctx.ua)
      )
      Ok(Json.obj("done" -> true))
    }
  }

  ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  def findAllGlobalJwtVerifiers() = ApiAction.async { ctx =>
    env.datastores.globalJwtVerifierDataStore.findAll().map(all => Ok(JsArray(all.map(_.asJson))))
  }

  def findGlobalJwtVerifiersById(id: String) = ApiAction.async { ctx =>
    env.datastores.globalJwtVerifierDataStore.findById(id).map {
      case Some(verifier) => Ok(verifier.asJson)
      case None =>
        NotFound(
          Json.obj("error" -> s"GlobalJwtVerifier with id $id not found")
        )
    }
  }

  def createGlobalJwtVerifier() = ApiAction.async(parse.json) { ctx =>
    GlobalJwtVerifier.fromJson(ctx.request.body) match {
      case Left(e) => BadRequest(Json.obj("error" -> "Bad GlobalJwtVerifier format")).asFuture
      case Right(newVerifier) =>
        env.datastores.globalJwtVerifierDataStore.set(newVerifier).map(_ => Ok(newVerifier.asJson))
    }
  }

  def updateGlobalJwtVerifier(id: String) = ApiAction.async(parse.json) { ctx =>
    env.datastores.globalJwtVerifierDataStore.findById(id).flatMap {
      case None =>
        NotFound(
          Json.obj("error" -> s"GlobalJwtVerifier with id $id not found")
        ).asFuture
      case Some(verifier) => {
        GlobalJwtVerifier.fromJson(ctx.request.body) match {
          case Left(e) => BadRequest(Json.obj("error" -> "Bad GlobalJwtVerifier format")).asFuture
          case Right(newVerifier) => {
            env.datastores.globalJwtVerifierDataStore.set(newVerifier).map(_ => Ok(newVerifier.asJson))
          }
        }
      }
    }
  }

  def patchGlobalJwtVerifier(id: String) = ApiAction.async(parse.json) { ctx =>
    env.datastores.globalJwtVerifierDataStore.findById(id).flatMap {
      case None =>
        NotFound(
          Json.obj("error" -> s"GlobalJwtVerifier with id $id not found")
        ).asFuture
      case Some(verifier) => {
        val currentJson = verifier.asJson
        val patch       = JsonPatch(ctx.request.body)
        val newVerifier = patch(currentJson)
        GlobalJwtVerifier.fromJson(newVerifier) match {
          case Left(e) => BadRequest(Json.obj("error" -> "Bad GlobalJwtVerifier format")).asFuture
          case Right(newVerifier) => {
            env.datastores.globalJwtVerifierDataStore.set(newVerifier).map(_ => Ok(newVerifier.asJson))
          }
        }
      }
    }
  }

  def deleteGlobalJwtVerifier(id: String) = ApiAction.async { ctx =>
    env.datastores.globalJwtVerifierDataStore.delete(id).map(_ => Ok(Json.obj("done" -> true)))
  }

  ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  def findAllGlobalAuthModules() = ApiAction.async { ctx =>
    env.datastores.authConfigsDataStore.findAll().map(all => Ok(JsArray(all.map(_.asJson))))
  }

  def findGlobalAuthModuleById(id: String) = ApiAction.async { ctx =>
    env.datastores.authConfigsDataStore.findById(id).map {
      case Some(verifier) => Ok(verifier.asJson)
      case None =>
        NotFound(
          Json.obj("error" -> s"GlobalAuthModule with id $id not found")
        )
    }
  }

  def createGlobalAuthModule() = ApiAction.async(parse.json) { ctx =>
    AuthModuleConfig._fmt.reads(ctx.request.body) match {
      case JsError(e) => BadRequest(Json.obj("error" -> "Bad GlobalAuthModule format")).asFuture
      case JsSuccess(newVerifier, _) =>
        env.datastores.authConfigsDataStore.set(newVerifier).map(_ => Ok(newVerifier.asJson))
    }
  }

  def updateGlobalAuthModule(id: String) = ApiAction.async(parse.json) { ctx =>
    env.datastores.authConfigsDataStore.findById(id).flatMap {
      case None =>
        NotFound(
          Json.obj("error" -> s"GlobalAuthModule with id $id not found")
        ).asFuture
      case Some(verifier) => {
        AuthModuleConfig._fmt.reads(ctx.request.body) match {
          case JsError(e) => BadRequest(Json.obj("error" -> "Bad GlobalAuthModule format")).asFuture
          case JsSuccess(newVerifier, _) => {
            env.datastores.authConfigsDataStore.set(newVerifier).map(_ => Ok(newVerifier.asJson))
          }
        }
      }
    }
  }

  def patchGlobalAuthModule(id: String) = ApiAction.async(parse.json) { ctx =>
    env.datastores.authConfigsDataStore.findById(id).flatMap {
      case None =>
        NotFound(
          Json.obj("error" -> s"GlobalAuthModule with id $id not found")
        ).asFuture
      case Some(verifier) => {
        val currentJson     = verifier.asJson
        val patch           = JsonPatch(ctx.request.body)
        val patchedVerifier = patch(currentJson)
        AuthModuleConfig._fmt.reads(patchedVerifier) match {
          case JsError(e) => BadRequest(Json.obj("error" -> "Bad GlobalAuthModule format")).asFuture
          case JsSuccess(newVerifier, _) => {
            env.datastores.authConfigsDataStore.set(newVerifier).map(_ => Ok(newVerifier.asJson))
          }
        }
      }
    }
  }

  def deleteGlobalAuthModule(id: String) = ApiAction.async { ctx =>
    env.datastores.authConfigsDataStore.delete(id).map(_ => Ok(Json.obj("done" -> true)))
  }

  def startRegistration(id: String) = ApiAction.async { ctx =>
    env.datastores.authConfigsDataStore.findById(id).flatMap {
      case Some(auth) => {
        auth.authModule(env.datastores.globalConfigDataStore.latest()) match {
          case bam: BasicAuthModule if bam.authConfig.webauthn =>
            bam.webAuthnRegistrationStart(ctx.request.body.asJson.get).map {
              case Left(err)  => BadRequest(err)
              case Right(reg) => Ok(reg)
            }
          case _ => BadRequest(Json.obj("error" -> s"Not supported")).future
        }
      }
      case None =>
        NotFound(
          Json.obj("error" -> s"GlobalAuthModule with id $id not found")
        ).future
    }
  }

  def finishRegistration(id: String) = ApiAction.async { ctx =>
    env.datastores.authConfigsDataStore.findById(id).flatMap {
      case Some(auth) => {
        auth.authModule(env.datastores.globalConfigDataStore.latest()) match {
          case bam: BasicAuthModule if bam.authConfig.webauthn =>
            bam.webAuthnRegistrationFinish(ctx.request.body.asJson.get).map {
              case Left(err)  => BadRequest(err)
              case Right(reg) => Ok(reg)
            }
          case _ => BadRequest(Json.obj("error" -> s"Not supported")).future
        }
      }
      case None =>
        NotFound(
          Json.obj("error" -> s"GlobalAuthModule with id $id not found")
        ).future
    }
  }

  ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  def createCert() = ApiAction.async(parse.json) { ctx =>
    val body: JsObject = (ctx.request.body \ "id").asOpt[String] match {
      case None    => ctx.request.body.as[JsObject] ++ Json.obj("id" -> IdGenerator.token(64))
      case Some(b) => ctx.request.body.as[JsObject]
    }
    Cert.fromJsonSafe(body) match {
      case JsError(e) => BadRequest(Json.obj("error" -> "Bad Cert format")).asFuture
      case JsSuccess(group, _) =>
        group.enrich().save().map {
          case true => {
            val event: AdminApiEvent = AdminApiEvent(
              env.snowflakeGenerator.nextIdStr(),
              env.env,
              Some(ctx.apiKey),
              ctx.user,
              "CREATE_CERTIFICATE",
              s"User created a certificate",
              ctx.from,
              ctx.ua,
              body
            )
            Audit.send(event)
            Alerts.send(
              CertCreatedAlert(env.snowflakeGenerator.nextIdStr(),
                               env.env,
                               ctx.user.getOrElse(ctx.apiKey.toJson),
                               event,
                               ctx.from,
                               ctx.ua)
            )
            Ok(group.toJson)
          }
          case false => InternalServerError(Json.obj("error" -> "Certificate not stored ..."))
        }
    }
  }

  def updateCert(CertId: String) = ApiAction.async(parse.json) { ctx =>
    env.datastores.certificatesDataStore.findById(CertId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Certificate with clienId '$CertId' not found")).asFuture
      case Some(group) => {
        Cert.fromJsonSafe(ctx.request.body) match {
          case JsError(e) => BadRequest(Json.obj("error" -> "Bad Certificate format")).asFuture
          case JsSuccess(newGroup, _) if newGroup.id != CertId =>
            BadRequest(Json.obj("error" -> "Bad Certificate format")).asFuture
          case JsSuccess(newGroup, _) if newGroup.id == CertId => {
            val event: AdminApiEvent = AdminApiEvent(
              env.snowflakeGenerator.nextIdStr(),
              env.env,
              Some(ctx.apiKey),
              ctx.user,
              "UPDATE_CERTIFICATE",
              s"User updated a certificate",
              ctx.from,
              ctx.ua,
              ctx.request.body
            )
            Audit.send(event)
            Alerts.send(
              CertUpdatedAlert(env.snowflakeGenerator.nextIdStr(),
                               env.env,
                               ctx.user.getOrElse(ctx.apiKey.toJson),
                               event,
                               ctx.from,
                               ctx.ua)
            )
            newGroup.enrich().save().map(_ => Ok(newGroup.toJson))
          }
        }
      }
    }
  }

  def patchCert(CertId: String) = ApiAction.async(parse.json) { ctx =>
    env.datastores.certificatesDataStore.findById(CertId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Certificate with clienId '$CertId' not found")).asFuture
      case Some(group) => {
        val currentGroupJson = group.toJson
        val patch            = JsonPatch(ctx.request.body)
        val newGroupJson     = patch(currentGroupJson)
        Cert.fromJsonSafe(newGroupJson) match {
          case JsError(e) => BadRequest(Json.obj("error" -> "Bad Certificate format")).asFuture
          case JsSuccess(newGroup, _) if newGroup.id != CertId =>
            BadRequest(Json.obj("error" -> "Bad Certificate format")).asFuture
          case JsSuccess(newGroup, _) if newGroup.id == CertId => {
            val event: AdminApiEvent = AdminApiEvent(
              env.snowflakeGenerator.nextIdStr(),
              env.env,
              Some(ctx.apiKey),
              ctx.user,
              "UPDATE_CERTIFICATE",
              s"User updated a certificate",
              ctx.from,
              ctx.ua,
              ctx.request.body
            )
            Audit.send(event)
            Alerts.send(
              CertUpdatedAlert(env.snowflakeGenerator.nextIdStr(),
                               env.env,
                               ctx.user.getOrElse(ctx.apiKey.toJson),
                               event,
                               ctx.from,
                               ctx.ua)
            )
            newGroup.enrich().save().map(_ => Ok(newGroup.toJson))
          }
        }
      }
    }
  }

  def deleteCert(CertId: String) = ApiAction.async { ctx =>
    env.datastores.certificatesDataStore.findById(CertId).flatMap {
      case None => NotFound(Json.obj("error" -> s"Certificate with id: '$CertId' not found")).asFuture
      case Some(cert) =>
        cert.delete().map { res =>
          val event: AdminApiEvent = AdminApiEvent(
            env.snowflakeGenerator.nextIdStr(),
            env.env,
            Some(ctx.apiKey),
            ctx.user,
            "DELETE_CERTIFICATE",
            s"User deleted a certificate",
            ctx.from,
            ctx.ua,
            Json.obj("CertId" -> CertId)
          )
          Audit.send(event)
          Alerts.send(
            CertDeleteAlert(env.snowflakeGenerator.nextIdStr(),
                            env.env,
                            ctx.user.getOrElse(ctx.apiKey.toJson),
                            event,
                            ctx.from,
                            ctx.ua)
          )
          Ok(Json.obj("deleted" -> res))
        }
    }
  }

  def renewCert(id: String) = ApiAction.async { ctx =>
    env.datastores.certificatesDataStore.findById(id).map(_.map(_.enrich())).flatMap {
      case None => FastFuture.successful(NotFound(Json.obj("error" -> s"No Certificate found")))
      case Some(cert) => cert.renew().map(c => Ok(c.toJson))
    }
  }

  def allCerts() = ApiAction.async { ctx =>
    val paginationPage: Int = ctx.request.queryString.get("page").flatMap(_.headOption).map(_.toInt).getOrElse(1)
    val paginationPageSize: Int =
      ctx.request.queryString.get("pageSize").flatMap(_.headOption).map(_.toInt).getOrElse(Int.MaxValue)
    val paginationPosition = (paginationPage - 1) * paginationPageSize
    Audit.send(
      AdminApiEvent(
        env.snowflakeGenerator.nextIdStr(),
        env.env,
        Some(ctx.apiKey),
        ctx.user,
        ctx.ua,
        "ACCESS_ALL_CERTIFICATES",
        s"User accessed all certificates",
        ctx.from
      )
    )
    val id: Option[String]       = ctx.request.queryString.get("id").flatMap(_.headOption)
    val domain: Option[String]   = ctx.request.queryString.get("domain").flatMap(_.headOption)
    val client: Option[Boolean]  = ctx.request.queryString.get("client").flatMap(_.headOption).map(_.contains("true"))
    val ca: Option[Boolean]      = ctx.request.queryString.get("ca").flatMap(_.headOption).map(_.contains("true"))
    val keypair: Option[Boolean] = ctx.request.queryString.get("keypair").flatMap(_.headOption).map(_.contains("true"))
    val hasFilters               = id.orElse(domain).orElse(client).orElse(ca).orElse(keypair).isDefined
    env.datastores.certificatesDataStore.streamedFindAndMat(_ => true, 50, paginationPage, paginationPageSize).map {
      groups =>
        if (hasFilters) {
          Ok(
            JsArray(
              groups
                .filter {
                  case group if keypair.isDefined && keypair.get && group.keypair => true
                  case group if ca.isDefined && ca.get && group.ca                => true
                  case group if client.isDefined && client.get && group.client    => true
                  case group if id.isDefined && group.id == id.get                => true
                  case group if domain.isDefined && group.domain == domain.get    => true
                  case _                                                          => false
                }
                .map(_.toJson)
            )
          )
        } else {
          Ok(JsArray(groups.map(_.toJson)))
        }
    }
  }

  def oneCert(CertId: String) = ApiAction.async { ctx =>
    env.datastores.certificatesDataStore.findById(CertId).map {
      case None => NotFound(Json.obj("error" -> s"Certificate with id: '$CertId' not found"))
      case Some(group) => {
        Audit.send(
          AdminApiEvent(
            env.snowflakeGenerator.nextIdStr(),
            env.env,
            Some(ctx.apiKey),
            ctx.user,
            "ACCESS_CERTIFICATE",
            s"User accessed a certificate",
            ctx.from,
            ctx.ua,
            Json.obj("certId" -> CertId)
          )
        )
        Ok(group.toJson)
      }
    }
  }

  ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  // def registerSimpleAdmin() = ApiAction.async(parse.json) { ctx =>
  //   val usernameOpt = (ctx.request.body \ "username").asOpt[String]
  //   val passwordOpt = (ctx.request.body \ "password").asOpt[String]
  //   val labelOpt    = (ctx.request.body \ "label").asOpt[String]
  //   val authorizedGroupOpt    = (ctx.request.body \ "authorizedGroup").asOpt[String]
  //   (usernameOpt, passwordOpt, labelOpt, authorizedGroupOpt) match {
  //     case (Some(username), Some(password), Some(label), authorizedGroup) => {
  //       val saltedPassword = BCrypt.hashpw(password, BCrypt.gensalt())
  //       env.datastores.simpleAdminDataStore.registerUser(username, saltedPassword, label, authorizedGroup).map { _ =>
  //         Ok(Json.obj("username" -> username))
  //       }
  //     }
  //     case _ => FastFuture.successful(BadRequest(Json.obj("error" -> "no username or token provided")))
  //   }
  // }
}
