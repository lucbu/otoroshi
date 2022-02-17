package otoroshi.next.controllers.adminapi

import otoroshi.actions.ApiAction
import otoroshi.env.Env
import otoroshi.models._
import otoroshi.next.models._
import otoroshi.security.IdGenerator
import otoroshi.utils.controllers._
import play.api.Logger
import play.api.libs.json._
import play.api.mvc._

import scala.concurrent.{ExecutionContext, Future}

class NgBackendsController(val ApiAction: ApiAction, val cc: ControllerComponents)(implicit val env: Env)
    extends AbstractController(cc)
    with BulkControllerHelper[StoredNgBackend, JsValue]
    with CrudControllerHelper[StoredNgBackend, JsValue] {

  implicit lazy val ec  = env.otoroshiExecutionContext
  implicit lazy val mat = env.otoroshiMaterializer

  lazy val logger = Logger("otoroshi-backends-api")

  override def buildError(status: Int, message: String): ApiError[JsValue] =
    JsonApiError(status, play.api.libs.json.JsString(message))

  override def extractId(entity: StoredNgBackend): String = entity.id

  override def readEntity(json: JsValue): Either[String, StoredNgBackend] =
    StoredNgBackend.format.reads(json).asEither match {
      case Left(e)  => Left(e.toString())
      case Right(r) => Right(r)
    }

  override def writeEntity(entity: StoredNgBackend): JsValue = StoredNgBackend.format.writes(entity)

  override def findByIdOps(
      id: String
  )(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[Either[ApiError[JsValue], OptionalEntityAndContext[StoredNgBackend]]] = {
    env.datastores.backendsDataStore.findById(id).map { opt =>
      Right(
        OptionalEntityAndContext(
          entity = opt,
          action = "ACCESS_STORED_NG_BACKEND",
          message = "User accessed a backend",
          metadata = Json.obj("StoredNgBackendId" -> id),
          alert = "StoredNgBackendAccessed"
        )
      )
    }
  }

  override def findAllOps(
      req: RequestHeader
  )(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[Either[ApiError[JsValue], SeqEntityAndContext[StoredNgBackend]]] = {
    env.datastores.backendsDataStore.findAll().map { seq =>
      Right(
        SeqEntityAndContext(
          entity = seq,
          action = "ACCESS_ALL_STORED_NG_BACKENDS",
          message = "User accessed all backends",
          metadata = Json.obj(),
          alert = "StoredNgBackendsAccessed"
        )
      )
    }
  }

  override def createEntityOps(
      entity: StoredNgBackend
  )(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], EntityAndContext[StoredNgBackend]]] = {
    env.datastores.backendsDataStore.set(entity).map {
      case true  => {
        Right(
          EntityAndContext(
            entity = entity,
            action = "CREATE_STORED_NG_BACKEND",
            message = "User created a backend",
            metadata = entity.json.as[JsObject],
            alert = "StoredNgBackendCreatedAlert"
          )
        )
      }
      case false => {
        Left(
          JsonApiError(
            500,
            Json.obj("error" -> "backend not stored ...")
          )
        )
      }
    }
  }

  override def updateEntityOps(
      entity: StoredNgBackend
  )(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], EntityAndContext[StoredNgBackend]]] = {
    env.datastores.backendsDataStore.set(entity).map {
      case true  => {
        Right(
          EntityAndContext(
            entity = entity,
            action = "UPDATE_STORED_NG_BACKEND",
            message = "User updated a backend",
            metadata = entity.json.as[JsObject],
            alert = "StoredNgBackendUpdatedAlert"
          )
        )
      }
      case false => {
        Left(
          JsonApiError(
            500,
            Json.obj("error" -> "backend not stored ...")
          )
        )
      }
    }
  }

  override def deleteEntityOps(
      id: String
  )(implicit env: Env, ec: ExecutionContext): Future[Either[ApiError[JsValue], NoEntityAndContext[StoredNgBackend]]] = {
    env.datastores.backendsDataStore.delete(id).map {
      case true  => {
        Right(
          NoEntityAndContext(
            action = "DELETE_STORED_NG_BACKEND",
            message = "User deleted a backend",
            metadata = Json.obj("StoredNgBackendId" -> id),
            alert = "StoredNgBackendDeletedAlert"
          )
        )
      }
      case false => {
        Left(
          JsonApiError(
            500,
            Json.obj("error" -> "backend not deleted ...")
          )
        )
      }
    }
  }

  def initiateStoredNgBackend() = ApiAction {
    Ok(
      StoredNgBackend(
        location = EntityLocation.default,
        id = s"backend_${IdGenerator.uuid}",
        name = "New backend",
        description = "A new backend",
        tags = Seq.empty,
        metadata = Map.empty,
        backend = NgBackend.empty
      ).json
    )
  }
}
