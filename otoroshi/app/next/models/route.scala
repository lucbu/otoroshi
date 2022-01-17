package otoroshi.next.models

import otoroshi.env.Env
import otoroshi.models.{ApiKeyRouteMatcher, _}
import otoroshi.next.plugins._
import otoroshi.security.IdGenerator
import otoroshi.storage.{BasicStore, RedisLike, RedisLikeStore}
import otoroshi.utils.RegexPool
import otoroshi.utils.http.RequestImplicits._
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.mvc.RequestHeader

import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

case class DomainAndPath(raw: String) {
  private lazy val parts = raw.split("\\/")
  lazy val domain = parts.head
  lazy val path = if (parts.size == 1) "/" else parts.tail.mkString("/", "/", "")
  def json: JsValue = JsString(raw)
}

case class Frontend(domains: Seq[DomainAndPath], headers: Map[String, String], stripPath: Boolean, apikey: ApiKeyRouteMatcher) {
  def json: JsValue = Json.obj(
    "domains" -> JsArray(domains.map(_.json)),
    "strip_path" -> stripPath,
    "headers" -> headers,
    "apikey" -> apikey.gentleJson
  )
}

object Frontend {
  def readFrom(lookup: JsLookupResult): Frontend = {
    lookup.asOpt[JsObject] match {
      case None => Frontend(Seq.empty, Map.empty, true, ApiKeyRouteMatcher())
      case Some(obj) => Frontend(
        domains = obj.select("domains").asOpt[Seq[String]].map(_.map(DomainAndPath.apply)).getOrElse(Seq.empty),
        stripPath = obj.select("strip_path").asOpt[Boolean].getOrElse(true),
        headers = obj.select("headers").asOpt[Map[String, String]].getOrElse(Map.empty),
        apikey = obj.select("apikey").asOpt[JsValue].flatMap(v => ApiKeyRouteMatcher.format.reads(v).asOpt).getOrElse(ApiKeyRouteMatcher())
      )
    }
  }
}

case class Backends(targets: Seq[Backend], root: String, loadBalancing: LoadBalancing) {
  def json: JsValue = Json.obj(
    "targets" -> JsArray(targets.map(_.json)),
    "root" -> root,
    "load_balancing" -> loadBalancing.toJson
  )
}

object Backends {
  def readFrom(lookup: JsLookupResult): Backends = {
    lookup.asOpt[JsObject] match {
      case None => Backends(Seq.empty, "/", RoundRobin)
      case Some(obj) => Backends(
        targets = obj.select("targets").asOpt[Seq[JsValue]].map(_.map(Backend.readFrom)).getOrElse(Seq.empty),
        root = obj.select("root").asOpt[String].getOrElse("/"),
        loadBalancing = LoadBalancing.format.reads(obj.select("load_balancing").asOpt[JsObject].getOrElse(Json.obj())).getOrElse(RoundRobin)
      )
    }
  }
}

case class Route(
  location: EntityLocation,
  id: String,
  name: String,
  description: String,
  tags: Seq[String],
  metadata: Map[String, String],
  enabled: Boolean,
  debugFlow: Boolean,
  groups: Seq[String] = Seq("default"),
  frontend: Frontend,
  backends: Backends,
  client: ClientConfig,
  healthCheck: HealthCheck,
  plugins: Plugins
) extends EntityLocationSupport {

  override def internalId: String = id
  override def theName: String = name
  override def theDescription: String = description
  override def theTags: Seq[String] = tags
  override def theMetadata: Map[String, String] = metadata
  override def json: JsValue = location.jsonWithKey ++ Json.obj(
    "id" -> id,
    "name" -> name,
    "description" -> description,
    "tags" -> tags,
    "metadata" -> metadata,
    "enabled" -> enabled,
    "debug_flow" -> debugFlow,
    "groups" -> groups,
    "frontend" -> frontend.json,
    "backend" -> backends.json,
    "client" -> client.toJson,
    "health_check" -> healthCheck.toJson,
    "plugins" -> plugins.json
  )

  def matches(request: RequestHeader, skipDomainVerif: Boolean)(implicit env: Env): Boolean = {
    if (enabled) {
      val path = request.thePath
      val domain = request.theDomain
      frontend.domains
        .applyOnIf(!skipDomainVerif)(_.filter(d => d.domain == domain || RegexPool(d.domain).matches(domain)))
        .exists { d =>
          path.startsWith(d.path) || RegexPool(d.path).matches(path)
        }
        .applyOnIf(frontend.headers.nonEmpty) { firstRes =>
          val headers = request.headers.toSimpleMap.map(t => (t._1.toLowerCase, t._2))
          val secondRes = frontend.headers.map(t => (t._1.toLowerCase, t._2)).forall {
            case (key, value) => headers.get(key).contains(value)
          }
          firstRes && secondRes
        }
    } else {
      false
    }
  }

  lazy val userFacing: Boolean = metadata.get("otoroshi-core-user-facing").contains("true")
  lazy val useAkkaHttpClient: Boolean = metadata.get("otoroshi-core-use-akka-http-client").contains("true")
  lazy val useAkkaHttpWsClient: Boolean = metadata.get("otoroshi-core-use-akka-http-ws-client").contains("true")
  lazy val issueLetsEncryptCertificate: Boolean = metadata.get("otoroshi-core-issue-lets-encrypt-certificate").contains("true")
  lazy val issueCertificate: Boolean = metadata.get("otoroshi-core-issue-certificate").contains("true")
  lazy val issueCertificateCA: Option[String] = metadata.get("otoroshi-core-issue-certificate-ca").filter(_.nonEmpty)
  lazy val openapiUrl: Option[String] = metadata.get("otoroshi-core-openapi-url").filter(_.nonEmpty)

  // TODO: move creation/mutation logic on each plugin to avoid desync issues
  lazy val serviceDescriptor: ServiceDescriptor = {
    ServiceDescriptor(
      location = location,
      id = id,
      name = name,
      description = description,
      tags = tags,
      metadata = metadata,
      enabled = enabled,
      groups = groups,
      env = "prod",
      domain = "--",
      subdomain = "--",
      targets = backends.targets.map(_.toTarget),
      hosts = frontend.domains.map(_.domain),
      paths = frontend.domains.map(_.path),
      stripPath = frontend.stripPath,
      clientConfig = client,
      healthCheck = healthCheck,
      matchingHeaders = frontend.headers,
      handleLegacyDomain = false,
      targetsLoadBalancing = backends.loadBalancing,
      issueCert = issueCertificate,
      issueCertCA = issueCertificateCA,
      useAkkaHttpClient = useAkkaHttpClient,
      useNewWSClient = useAkkaHttpWsClient,
      letsEncrypt = issueLetsEncryptCertificate,
      userFacing = userFacing,
      forceHttps = plugins.getPluginByClass[ForceHttpsTraffic].isDefined,
      maintenanceMode = plugins.getPluginByClass[MaintenanceMode].isDefined,
      buildMode =  plugins.getPluginByClass[BuildMode].isDefined,
      strictlyPrivate = plugins.getPluginByClass[PublicPrivatePaths].flatMap(_.config.raw.select("strict").asOpt[Boolean]).getOrElse(false),
      sendOtoroshiHeadersBack =  plugins.getPluginByClass[SendOtoroshiHeadersBack].isDefined,
      readOnly =  plugins.getPluginByClass[ReadOnlyCalls].isDefined,
      xForwardedHeaders = plugins.getPluginByClass[XForwardedHeaders].isDefined,
      overrideHost = plugins.getPluginByClass[OverrideHost].isDefined,
      allowHttp10 = plugins.getPluginByClass[DisableHttp10].isEmpty,
      // ///////////////////////////////////////////////////////////
      enforceSecureCommunication = plugins.getPluginByClass[OtoroshiChallenge].orElse(plugins.getPluginByClass[OtoroshiInfos]).isDefined,
      sendInfoToken = plugins.getPluginByClass[OtoroshiInfos].isDefined,
      sendStateChallenge = plugins.getPluginByClass[OtoroshiChallenge].isDefined,
      secComHeaders = SecComHeaders(
        claimRequestName = plugins.getPluginByClass[OtoroshiInfos].flatMap(_.config.raw.select("header_name").asOpt[String]),
        stateRequestName = plugins.getPluginByClass[OtoroshiChallenge].flatMap(_.config.raw.select("request_header_name").asOpt[String]),
        stateResponseName = plugins.getPluginByClass[OtoroshiChallenge].flatMap(_.config.raw.select("response_header_name").asOpt[String]),
      ),
      secComTtl = plugins.getPluginByClass[OtoroshiChallenge].flatMap(p => p.config.raw.select("ttl").asOpt[Long])
        .orElse(plugins.getPluginByClass[OtoroshiChallenge].flatMap(p => p.config.raw.select("ttl").asOpt[Long]))
        .getOrElse(30L).seconds,
      secComVersion = plugins.getPluginByClass[OtoroshiChallenge].map(p => p.config.raw.select("version").asOpt[String].getOrElse("V2")).flatMap(SecComVersion.apply).getOrElse(SecComVersion.V2),
      secComInfoTokenVersion = plugins.getPluginByClass[OtoroshiInfos].map(p => p.config.raw.select("version").asOpt[String].getOrElse("Latest")).flatMap(SecComInfoTokenVersion.apply).getOrElse(SecComInfoTokenVersion.Latest),
      secComExcludedPatterns = {
        (
          plugins.getPluginByClass[OtoroshiChallenge].map(_.exclude).getOrElse(Seq.empty) ++
            plugins.getPluginByClass[OtoroshiInfos].map(_.exclude).getOrElse(Seq.empty)
        ).distinct
      },
      // not needed because of the next line // secComSettings: AlgoSettings = HSAlgoSettings(512, "secret", false)
      secComUseSameAlgo = false,
      secComAlgoChallengeOtoToBack = plugins.getPluginByClass[OtoroshiChallenge].flatMap(p => AlgoSettings.fromJson(p.config.raw.select("algo_to_backend").asOpt[JsValue].getOrElse(Json.obj())).toOption).getOrElse(HSAlgoSettings(512, "secret", false)),
      secComAlgoChallengeBackToOto = plugins.getPluginByClass[OtoroshiChallenge].flatMap(p => AlgoSettings.fromJson(p.config.raw.select("algo_from_backend").asOpt[JsValue].getOrElse(Json.obj())).toOption).getOrElse(HSAlgoSettings(512, "secret", false)),
      secComAlgoInfoToken = plugins.getPluginByClass[OtoroshiInfos].flatMap(p => AlgoSettings.fromJson(p.config.raw.select("algo").asOpt[JsValue].getOrElse(Json.obj())).toOption).getOrElse(HSAlgoSettings(512, "secret", false)),
      // ///////////////////////////////////////////////////////////
      privateApp = plugins.getPluginByClass[AuthModule].isDefined,
      authConfigRef = plugins.getPluginByClass[AuthModule].flatMap(_.config.raw.select("auth_module").asOpt[String]),
      securityExcludedPatterns  = plugins.getPluginByClass[AuthModule].map(_.exclude).getOrElse(Seq.empty),
      // ///////////////////////////////////////////////////////////
      publicPatterns = plugins.getPluginByClass[PublicPrivatePaths].flatMap(p => p.config.raw.select("public_patterns").asOpt[Seq[String]]).getOrElse(Seq.empty),
      privatePatterns = plugins.getPluginByClass[PublicPrivatePaths].flatMap(p => p.config.raw.select("private_patterns").asOpt[Seq[String]]).getOrElse(Seq.empty),
      additionalHeaders = plugins.getPluginByClass[AdditionalHeadersIn].flatMap(p => p.config.raw.select("headers").asOpt[Map[String, String]]).getOrElse(Map.empty),
      additionalHeadersOut = plugins.getPluginByClass[AdditionalHeadersOut].flatMap(p => p.config.raw.select("headers").asOpt[Map[String, String]]).getOrElse(Map.empty),
      missingOnlyHeadersIn = plugins.getPluginByClass[MissingHeadersIn].flatMap(p => p.config.raw.select("headers").asOpt[Map[String, String]]).getOrElse(Map.empty),
      missingOnlyHeadersOut = plugins.getPluginByClass[MissingHeadersOut].flatMap(p => p.config.raw.select("headers").asOpt[Map[String, String]]).getOrElse(Map.empty),
      removeHeadersIn = plugins.getPluginByClass[RemoveHeadersIn].flatMap(p => p.config.raw.select("header_names").asOpt[Seq[String]]).getOrElse(Seq.empty),
      removeHeadersOut = plugins.getPluginByClass[RemoveHeadersOut].flatMap(p => p.config.raw.select("header_names").asOpt[Seq[String]]).getOrElse(Seq.empty),
      headersVerification = plugins.getPluginByClass[HeadersValidation].flatMap(p => p.config.raw.select("headers").asOpt[Map[String, String]]).getOrElse(Map.empty),
      ipFiltering = if (plugins.getPluginByClass[IpAddressBlockList].isDefined || plugins.getPluginByClass[IpAddressAllowedList].isDefined) {
        IpFiltering(
          whitelist = plugins.getPluginByClass[IpAddressAllowedList].flatMap(_.config.raw.select("addresses").asOpt[Seq[String]]).getOrElse(Seq.empty),
          blacklist = plugins.getPluginByClass[IpAddressBlockList].flatMap(_.config.raw.select("addresses").asOpt[Seq[String]]).getOrElse(Seq.empty),
        )
      } else {
        IpFiltering()
      },
      api = openapiUrl.map(url => ApiDescriptor(true, url.some)).getOrElse(ApiDescriptor(false, None)),
      jwtVerifier = plugins.getPluginByClass[JwtVerification].flatMap(p => p.config.raw.select("verifiers").asOpt[Seq[String]].map(ids => RefJwtVerifier(ids, true, p.exclude))).getOrElse(RefJwtVerifier()),
      cors = plugins.getPluginByClass[otoroshi.next.plugins.Cors].flatMap(p => CorsSettings.fromJson(p.config.raw).toOption).getOrElse(CorsSettings()),
      redirection = plugins.getPluginByClass[Redirection].flatMap(p => RedirectionSettings.format.reads(p.config.raw).asOpt).getOrElse(RedirectionSettings(false)),
      restrictions = plugins.getPluginByClass[RoutingRestrictions].flatMap(p => Restrictions.format.reads(p.config.raw).asOpt.map(_.copy(enabled = true))).getOrElse(Restrictions()),
      // tcpUdpTunneling: Boolean = false,
      // detectApiKeySooner: Boolean = false,
      // canary: Canary = Canary(),
      // chaosConfig: ChaosConfig = ChaosConfig(),
      // gzip: GzipConfig = GzipConfig(),
      apiKeyConstraints = {
        plugins.getPluginByClass[ApikeyCalls].flatMap { plugin =>
          ApiKeyConstraints.format.reads(plugin.config.raw).asOpt
        }.map { constraints =>
          constraints.copy(routing = frontend.apikey)
        }.getOrElse(ApiKeyConstraints())
      }
    )
  }
}

object Route {

  val fake = Route(
    location = EntityLocation.default,
    id = s"route_${IdGenerator.uuid}",
    name = "Fake route",
    description = "A fake route to tryout the new engine",
    tags = Seq.empty,
    metadata = Map.empty,
    enabled = true,
    debugFlow = true,
    groups = Seq("default"),
    frontend = Frontend(
      domains = Seq(DomainAndPath("fake-next-gen.oto.tools")),
      headers = Map.empty,
      stripPath = true,
      apikey = ApiKeyRouteMatcher()
    ),
    backends = Backends(
      targets = Seq(Backend(
        id = "tls://mirror.otoroshi.io:443",
        hostname = "mirror.otoroshi.io",
        port = 443,
        tls = true
      )),
      root = "/",
      loadBalancing = RoundRobin
    ),
    client = ClientConfig(),
    healthCheck = HealthCheck(false, "/"),
    plugins = Plugins(Seq(
      // PluginInstance(
      //   plugin = "cp:otoroshi.next.plugins.ForceHttpsTraffic",
      // ),
      PluginInstance(
        plugin = "cp:otoroshi.next.plugins.ApikeyCalls"
      ),
      PluginInstance(
        plugin = "cp:otoroshi.next.plugins.OverrideHost",
      ),
      //PluginInstance(
      //  plugin = "cp:otoroshi.next.plugins.HeadersValidation",
      //  config = PluginInstanceConfig(Json.obj(
      //    "headers" -> Json.obj(
      //      "foo" -> "bar"
      //    )
      //  ))
      //),
      PluginInstance(
        plugin = "cp:otoroshi.next.plugins.AdditionalHeadersOut",
        config = PluginInstanceConfig(Json.obj(
          "headers" -> Json.obj(
            "bar" -> "foo"
          )
        ))
      ),
      PluginInstance(
        plugin = "cp:otoroshi.next.plugins.AdditionalHeadersOut",
        config = PluginInstanceConfig(Json.obj(
          "headers" -> Json.obj(
            "bar2" -> "foo2"
          )
        ))
      )
    ))
  )

  val fmt = new Format[Route] {
    override def writes(o: Route): JsValue = o.json
    override def reads(json: JsValue): JsResult[Route] = Try {
      Route(
        location = otoroshi.models.EntityLocation.readFromKey(json),
        id = json.select("id").as[String],
        name = json.select("id").as[String],
        description = json.select("description").asOpt[String].getOrElse(""),
        tags = json.select("tags").asOpt[Seq[String]].getOrElse(Seq.empty),
        metadata = json.select("metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        enabled = json.select("enabled").asOpt[Boolean].getOrElse(true),
        debugFlow = json.select("debug_flow").asOpt[Boolean].getOrElse(false),
        groups = json.select("groups").asOpt[Seq[String]].getOrElse(Seq("default")),
        frontend = Frontend.readFrom(json.select("frontend")),
        backends = Backends.readFrom(json.select("backend")),
        healthCheck = (json \ "health_check").asOpt(HealthCheck.format).getOrElse(HealthCheck(false, "/")),
        client = (json \ "client").asOpt(ClientConfig.format).getOrElse(ClientConfig()),
        plugins = Plugins.readFrom(json.select("plugins")),
      )
    } match {
      case Failure(exception) => JsError(exception.getMessage)
      case Success(route) => JsSuccess(route)
    }
  }

  // TODO: move creation/mutation logic on each plugin to avoid desync issues
  def fromServiceDescriptor(service: ServiceDescriptor, debug: Boolean): Route = {
    Route(
      location = service.location,
      id = service.id,
      name = service.name,
      description = service.description,
      tags = service.tags,
      metadata = service.metadata.applyOnIf(service.useAkkaHttpClient) { meta =>
        meta ++ Map("otoroshi-core-use-akka-http-client" -> "true")
      }.applyOnIf(service.useNewWSClient) { meta =>
        meta ++ Map("otoroshi-core-use-akka-http-ws-client" -> "true")
      }.applyOnIf(service.letsEncrypt) { meta =>
        meta ++ Map("otoroshi-core-issue-lets-encrypt-certificate" -> "true")
      }.applyOnIf(service.issueCert) { meta =>
        meta ++ Map(
          "otoroshi-core-issue-certificate" -> "true",
          "otoroshi-core-issue-certificate-ca" -> service.issueCertCA.getOrElse("")
        )
      }.applyOnIf(service.userFacing) { meta =>
        meta ++ Map("otoroshi-core-user-facing" -> "true")
      }.applyOnIf(service.api.exposeApi) { meta =>
        meta ++ Map("otoroshi-core-openapi-url" -> service.api.openApiDescriptorUrl.getOrElse(""))
      },
      enabled = service.enabled,
      debugFlow = debug, // TODO: remove it when release ;)
      frontend = Frontend(
        domains = {
          val dap = if (service.allPaths.isEmpty) {
            service.allHosts.map(h => s"$h${service.matchingRoot.getOrElse("/")}")
          } else {
            service.allPaths.flatMap(path => service.allHosts.map(host => s"$host$path"))
          }
          dap.map(DomainAndPath.apply).distinct
        },
        headers = service.matchingHeaders,
        stripPath = service.stripPath,
        apikey = service.apiKeyConstraints.routing,
      ),
      backends = Backends(
        targets = service.targets.map(Backend.fromTarget),
        root = service.root,
        loadBalancing = service.targetsLoadBalancing
      ),
      groups = service.groups,
      client = service.clientConfig,
      healthCheck = service.healthCheck,
      plugins = Plugins(
        Seq.empty[PluginInstance]
          .applyOnIf(service.forceHttps) { seq =>
            seq :+ PluginInstance(
              plugin = "cp:otoroshi.next.plugins.ForceHttpsTraffic",
            )
          }
          .applyOnIf(service.overrideHost) { seq =>
            seq :+ PluginInstance(
              plugin = "cp:otoroshi.next.plugins.OverrideHost",
            )
          }
          .applyOnIf(service.headersVerification.nonEmpty) { seq =>
            seq :+ PluginInstance(
              plugin = "cp:otoroshi.next.plugins.HeadersValidation",
              config = PluginInstanceConfig(Json.obj(
                "headers" -> JsObject(service.headersVerification.mapValues(JsString.apply))
              ))
            )
          }
          .applyOnIf(service.maintenanceMode) { seq =>
            seq :+ PluginInstance(
              plugin = "cp:otoroshi.next.plugins.MaintenanceMode"
            )
          }
          .applyOnIf(service.buildMode) { seq =>
            seq :+ PluginInstance(
              plugin = "cp:otoroshi.next.plugins.BuildMode"
            )
          }
          .applyOnIf(!service.allowHttp10) { seq =>
            seq :+ PluginInstance(
              plugin = "cp:otoroshi.next.plugins.DisableHttp10"
            )
          }
          .applyOnIf(service.readOnly) { seq =>
            seq :+ PluginInstance(
              plugin = "cp:otoroshi.next.plugins.ReadOnlyCalls"
            )
          }
          .applyOnIf(service.ipFiltering.blacklist.nonEmpty) { seq =>
            seq :+ PluginInstance(
              plugin = "cp:otoroshi.next.plugins.IpAddressBlockList",
              config = PluginInstanceConfig(Json.obj(
                "addresses" -> JsArray(service.ipFiltering.blacklist.map(JsString.apply))
              ))
            )
          }
          .applyOnIf(service.ipFiltering.whitelist.nonEmpty) { seq =>
            seq :+ PluginInstance(
              plugin = "cp:otoroshi.next.plugins.IpAddressAllowedList",
              config = PluginInstanceConfig(Json.obj(
                "addresses" -> JsArray(service.ipFiltering.whitelist.map(JsString.apply))
              ))
            )
          }
          .applyOnIf(service.redirection.enabled) { seq =>
            seq :+ PluginInstance(
              plugin = "cp:otoroshi.next.plugins.Redirection",
              config = PluginInstanceConfig(service.redirection.toJson.as[JsObject])
            )
          }
          .applyOnIf(service.additionalHeaders.nonEmpty) { seq =>
            seq :+ PluginInstance(
              plugin = "cp:otoroshi.next.plugins.AdditionalHeadersIn",
              config = PluginInstanceConfig(Json.obj(
                "headers" -> JsObject(service.additionalHeaders.mapValues(JsString.apply))
              ))
            )
          }
          .applyOnIf(service.additionalHeadersOut.nonEmpty) { seq =>
            seq :+ PluginInstance(
              plugin = "cp:otoroshi.next.plugins.AdditionalHeadersOut",
              config = PluginInstanceConfig(Json.obj(
                "headers" -> JsObject(service.additionalHeadersOut.mapValues(JsString.apply))
              ))
            )
          }
          .applyOnIf(service.missingOnlyHeadersIn.nonEmpty) { seq =>
            seq :+ PluginInstance(
              plugin = "cp:otoroshi.next.plugins.MissingHeadersIn",
              config = PluginInstanceConfig(Json.obj(
                "headers" -> JsObject(service.missingOnlyHeadersIn.mapValues(JsString.apply))
              ))
            )
          }
          .applyOnIf(service.missingOnlyHeadersOut.nonEmpty) { seq =>
            seq :+ PluginInstance(
              plugin = "cp:otoroshi.next.plugins.MissingHeadersOut",
              config = PluginInstanceConfig(Json.obj(
                "headers" -> JsObject(service.missingOnlyHeadersOut.mapValues(JsString.apply))
              ))
            )
          }
          .applyOnIf(service.removeHeadersIn.nonEmpty) { seq =>
            seq :+ PluginInstance(
              plugin = "cp:otoroshi.next.plugins.RemoveHeadersIn",
              config = PluginInstanceConfig(Json.obj(
                "headers" -> JsArray(service.removeHeadersIn.map(JsString.apply))
              ))
            )
          }
          .applyOnIf(service.removeHeadersOut.nonEmpty) { seq =>
            seq :+ PluginInstance(
              plugin = "cp:otoroshi.next.plugins.RemoveHeadersOut",
              config = PluginInstanceConfig(Json.obj(
                "headers" -> JsArray(service.removeHeadersOut.map(JsString.apply))
              ))
            )
          }
          .applyOnIf(service.sendOtoroshiHeadersBack) { seq =>
            seq :+ PluginInstance(
              plugin = "cp:otoroshi.next.plugins.SendOtoroshiHeadersBack",
            )
          }
          .applyOnIf(service.xForwardedHeaders) { seq =>
            seq :+ PluginInstance(
              plugin = "cp:otoroshi.next.plugins.XForwardedHeaders",
            )
          }
          .applyOnIf(service.publicPatterns.nonEmpty || service.privatePatterns.nonEmpty) { seq =>
            seq :+ PluginInstance(
              plugin = "cp:otoroshi.next.plugins.PublicPrivatePaths",
              config = PluginInstanceConfig(Json.obj(
                "private_patterns" -> JsArray(service.privatePatterns.map(JsString.apply)),
                "public_patterns" -> JsArray(service.publicPatterns.map(JsString.apply)),
                "strict" -> service.strictlyPrivate
              ))
            )
          }
          .applyOnIf(service.jwtVerifier.enabled && service.jwtVerifier.isRef) { seq =>
            val verifier = service.jwtVerifier.asInstanceOf[RefJwtVerifier]
            seq :+ PluginInstance(
              plugin = "cp:otoroshi.next.plugins.JwtVerification",
              exclude = verifier.excludedPatterns,
              config = PluginInstanceConfig(Json.obj(
                "verifiers" -> JsArray(verifier.ids.map(JsString.apply)),
              ))
            )
          }
          .applyOnIf(service.restrictions.enabled) { seq =>
            seq :+ PluginInstance(
              plugin = "cp:otoroshi.next.plugins.RoutingRestrictions",
              config = PluginInstanceConfig(service.restrictions.json.asObject)
            )
          }
          .applyOnIf(service.privateApp && service.authConfigRef.isDefined) { seq =>
            seq :+ PluginInstance(
              plugin = "cp:otoroshi.next.plugins.AuthModule",
              exclude = service.securityExcludedPatterns,
              config = PluginInstanceConfig(Json.obj(
                "auth_module" -> service.authConfigRef.get
              ))
            )
          }
          .applyOnIf(service.enforceSecureCommunication && service.sendStateChallenge) { seq =>
            seq :+ PluginInstance(
              plugin = "cp:otoroshi.next.plugins.OtoroshiChallenge",
              exclude = service.secComExcludedPatterns,
              config = PluginInstanceConfig(Json.obj(
                "version" -> service.secComVersion.str,
                "ttl" -> service.secComTtl.toSeconds,
                "request_header_name" -> service.secComHeaders.stateRequestName.getOrElse("Otoroshi-State").json,
                "response_header_name" -> service.secComHeaders.stateResponseName.getOrElse("Otoroshi-State-Resp").json,
                "algo_to_backend" -> (if (service.secComUseSameAlgo) service.secComSettings.asJson else service.secComAlgoChallengeOtoToBack.asJson),
                "algo_from_backend" -> (if (service.secComUseSameAlgo) service.secComSettings.asJson else service.secComAlgoChallengeBackToOto.asJson),
              ))
            )
          }
          .applyOnIf(service.enforceSecureCommunication && service.sendInfoToken) { seq =>
            seq :+ PluginInstance(
              plugin = "cp:otoroshi.next.plugins.OtoroshiInfos",
              exclude = service.secComExcludedPatterns,
              config = PluginInstanceConfig(Json.obj(
                "version" -> service.secComInfoTokenVersion.version,
                "ttl" -> service.secComTtl.toSeconds,
                "header_name" -> service.secComHeaders.stateRequestName.getOrElse("Otoroshi-Claim").json,
                "algo" -> (if (service.secComUseSameAlgo) service.secComSettings.asJson else service.secComAlgoInfoToken.asJson),
              ))
            )
          }
          .applyOnIf(service.cors.enabled) { seq =>
            seq :+ PluginInstance(
              plugin = "cp:otoroshi.next.plugins.Cors",
              exclude = service.cors.excludedPatterns,
              config = PluginInstanceConfig(service.cors.asJson.asObject)
            )
          }
          .applyOnIf(service.detectApiKeySooner) { seq =>
            seq :+ PluginInstance(
              plugin = "cp:otoroshi.next.plugins.ApikeyExtractor",
              config = PluginInstanceConfig(service.apiKeyConstraints.json.asObject)
            )
          }
          .applyOnIf(true) { seq => // TODO: always true ?
            seq :+ PluginInstance(
              plugin = "cp:otoroshi.next.plugins.ApikeyCalls",
              include = service.privatePatterns,
              exclude = service.publicPatterns,
              config = PluginInstanceConfig(service.apiKeyConstraints.json.asObject)
            )
          }
      )
    )
  }
}

trait RouteDataStore extends BasicStore[Route]

class KvRouteDataStore(redisCli: RedisLike, _env: Env)
  extends RouteDataStore
    with RedisLikeStore[Route] {
  override def redisLike(implicit env: Env): RedisLike = redisCli
  override def fmt: Format[Route]               = Route.fmt
  override def key(id: String): Key             = Key.Empty / _env.storageRoot / "routes" / id
  override def extractId(value: Route): String  = value.id
}
