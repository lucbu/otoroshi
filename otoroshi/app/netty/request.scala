package otoroshi.netty

import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.github.blemale.scaffeine.Scaffeine
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.cookie.ServerCookieDecoder
import org.reactivestreams.Publisher
import otoroshi.security.IdGenerator
import play.api.Logger
import play.api.libs.typedmap.{TypedKey, TypedMap}
import play.api.mvc._
import play.api.mvc.request.{Cell, RemoteConnection, RequestAttrKey, RequestTarget}
import reactor.core.publisher.Flux
import reactor.netty.http.server.HttpServerRequest

import java.net.{InetAddress, URI}
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.{SSLPeerUnverifiedException, SSLSession}
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters._
import scala.util.Try

object NettyRequestKeys {
  val TrailerHeadersIdKey = TypedKey[String]("Trailer-Headers-Id")
}

object NettyRequestAwaitingTrailers {

  private val awaiting = Scaffeine()
    .maximumSize(1000)
    .expireAfterWrite(10.seconds)
    .build[String, Either[Future[Map[String, Seq[String]]], Map[String, Seq[String]]]]()

  def add(key: String, value: Either[Future[Map[String, Seq[String]]], Map[String, Seq[String]]]): Unit = {
    awaiting.put(key, value)
  }

  def get(key: String): Option[Either[Future[Map[String, Seq[String]]], Map[String, Seq[String]]]] = {
    awaiting.getIfPresent(key)
  }

  def remove(key: String): Unit = {
    awaiting.invalidate(key)
  }
}

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

object ReactorNettyRemoteConnection {
  val logger = Logger("otoroshi-experimental-netty-server-remote-connection")
}

object ReactorNettyRequest {
  val counter = new AtomicLong(0L)
}

class ReactorNettyRemoteConnection(req: HttpServerRequest, val secure: Boolean, sessionOpt: Option[SSLSession])
    extends RemoteConnection {
  lazy val remoteAddress: InetAddress = req.remoteAddress().getAddress
  lazy val clientCertificateChain: Option[Seq[X509Certificate]] = {
    if (secure) {
      sessionOpt match {
        case None          =>
          ReactorNettyRemoteConnection.logger.warn(
            s"Something weird happened with the TLS session: it does not exists ..."
          )
          None
        case Some(session) => {
          if (session.isValid) {
            val certs =
              try {
                session.getPeerCertificates.toSeq.collect { case c: X509Certificate => c }
              } catch {
                case e: SSLPeerUnverifiedException => Seq.empty[X509Certificate]
              }
            if (certs.nonEmpty) {
              Some(certs)
            } else {
              None
            }
          } else {
            None
          }
        }
      }
    } else {
      None
    }
  }
}

class ReactorNettyRequestTarget(req: HttpServerRequest) extends RequestTarget {
  lazy val kUri                               = akka.http.scaladsl.model.Uri(uriString)
  lazy val uri: URI                           = new URI(uriString)
  lazy val uriString: String                  = req.uri()
  lazy val path: String                       = req.fullPath()
  lazy val queryMap: Map[String, Seq[String]] = kUri.query().toMultiMap.mapValues(_.toSeq)
}

class ReactorNettyRequest(
    req: HttpServerRequest,
    secure: Boolean,
    sessionOpt: Option[SSLSession],
    sessionCookieBaker: SessionCookieBaker,
    flashCookieBaker: FlashCookieBaker
) extends ReactorNettyRequestHeader(req, secure, sessionOpt, sessionCookieBaker, flashCookieBaker)
    with Request[Source[ByteString, _]] {
  lazy val body: Source[ByteString, _] = {
    val flux: Publisher[ByteString] = req.receive().map { bb =>
      val builder = ByteString.newBuilder
      bb.readBytes(builder.asOutputStream, bb.readableBytes())
      builder.result()
    }
    Source.fromPublisher(flux)
  }
}

class ReactorNettyRequestHeader(
    req: HttpServerRequest,
    secure: Boolean,
    sessionOpt: Option[SSLSession],
    sessionCookieBaker: SessionCookieBaker,
    flashCookieBaker: FlashCookieBaker
) extends RequestHeader {

  lazy val zeSession: Session = {
    Option(req.cookies().get(sessionCookieBaker.COOKIE_NAME))
      .flatMap(_.asScala.headOption)
      .flatMap { value =>
        Try(sessionCookieBaker.deserialize(sessionCookieBaker.decode(value.value()))).toOption
      }
      .getOrElse(Session())
  }
  lazy val zeFlash: Flash = {
    Option(req.cookies().get(flashCookieBaker.COOKIE_NAME))
      .flatMap(_.asScala.headOption)
      .flatMap { value =>
        Try(flashCookieBaker.deserialize(flashCookieBaker.decode(value.value()))).toOption
      }
      .getOrElse(Flash())
  }
  val count                             = ReactorNettyRequest.counter.incrementAndGet()
  lazy val attrs                        = TypedMap.apply(
    RequestAttrKey.Id                    -> count,
    RequestAttrKey.Session               -> Cell(zeSession),
    RequestAttrKey.Flash                 -> Cell(zeFlash),
    RequestAttrKey.Server                -> "netty-experimental",
    NettyRequestKeys.TrailerHeadersIdKey -> s"${IdGenerator.uuid}-${count}",
    RequestAttrKey.Cookies               -> Cell(Cookies(req.cookies().asScala.toSeq.flatMap { case (_, cookies) =>
      cookies.asScala.map {
        case cookie: io.netty.handler.codec.http.cookie.DefaultCookie => {
          play.api.mvc.Cookie(
            name = cookie.name(),
            value = cookie.value(),
            maxAge = Option(cookie.maxAge()).map(_.toInt),
            path = Option(cookie.path()).filter(_.nonEmpty).getOrElse("/"),
            domain = Option(cookie.domain()).filter(_.nonEmpty),
            secure = cookie.isSecure,
            httpOnly = cookie.isHttpOnly,
            sameSite = Option(cookie.sameSite()).map {
              case e if e == io.netty.handler.codec.http.cookie.CookieHeaderNames.SameSite.None   =>
                play.api.mvc.Cookie.SameSite.None
              case e if e == io.netty.handler.codec.http.cookie.CookieHeaderNames.SameSite.Strict =>
                play.api.mvc.Cookie.SameSite.Strict
              case e if e == io.netty.handler.codec.http.cookie.CookieHeaderNames.SameSite.Lax    =>
                play.api.mvc.Cookie.SameSite.Lax
              case _                                                                              => play.api.mvc.Cookie.SameSite.None
            }
          )
        }
        case cookie                                                   => {
          play.api.mvc.Cookie(
            name = cookie.name(),
            value = cookie.value(),
            maxAge = Option(cookie.maxAge()).map(_.toInt),
            path = Option(cookie.path()).filter(_.nonEmpty).getOrElse("/"),
            domain = Option(cookie.domain()).filter(_.nonEmpty),
            secure = cookie.isSecure,
            httpOnly = cookie.isHttpOnly,
            sameSite = None
          )
        }
      }
    }))
  )
  lazy val method: String               = req.method().toString
  lazy val version: String              = req.version().toString
  lazy val headers: Headers             = Headers(
    (req.requestHeaders().entries().asScala.map(e => (e.getKey, e.getValue)) ++ sessionOpt.map(s =>
      ("Tls-Session-Info", s.toString)
    )): _*
  )
  lazy val connection: RemoteConnection = new ReactorNettyRemoteConnection(req, secure, sessionOpt)
  lazy val target: RequestTarget        = new ReactorNettyRequestTarget(req)
}

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

object NettyRemoteConnection {
  val logger = Logger("otoroshi-experimental-netty-server-remote-connection")
}

object NettyRequest {
  val counter = new AtomicLong(0L)
}

class NettyRemoteConnection(
    req: HttpRequest,
    ctx: ChannelHandlerContext,
    val secure: Boolean,
    sessionOpt: Option[SSLSession]
) extends RemoteConnection {
  lazy val remoteAddress: InetAddress = {
    ctx.channel() match {
      case c: io.netty.incubator.codec.quic.QuicChannel       => InetAddress.getLocalHost
      case c: io.netty.incubator.codec.quic.QuicStreamChannel => InetAddress.getLocalHost
      case _                                                  => InetAddress.getLocalHost
    }
  }
  lazy val clientCertificateChain: Option[Seq[X509Certificate]] = {
    if (secure) {
      sessionOpt match {
        case None          =>
          ReactorNettyRemoteConnection.logger.warn(
            s"Something weird happened with the TLS session: it does not exists ..."
          )
          None
        case Some(session) => {
          if (session.isValid) {
            val certs =
              try {
                session.getPeerCertificates.toSeq.collect { case c: X509Certificate => c }
              } catch {
                case e: SSLPeerUnverifiedException => Seq.empty[X509Certificate]
              }
            if (certs.nonEmpty) {
              Some(certs)
            } else {
              None
            }
          } else {
            None
          }
        }
      }
    } else {
      None
    }
  }
}

class NettyRequestTarget(req: HttpRequest) extends RequestTarget {
  lazy val kUri                               = akka.http.scaladsl.model.Uri(uriString)
  lazy val uri: URI                           = new URI(uriString)
  lazy val uriString: String                  = req.uri()
  lazy val path: String                       = kUri.path.toString()
  lazy val queryMap: Map[String, Seq[String]] = kUri.query().toMultiMap.mapValues(_.toSeq)
}

class NettyRequest(
    req: HttpRequest,
    ctx: ChannelHandlerContext,
    rawBody: Flux[ByteString],
    secure: Boolean,
    sessionOpt: Option[SSLSession],
    sessionCookieBaker: SessionCookieBaker,
    flashCookieBaker: FlashCookieBaker
) extends NettyRequestHeader(req, ctx, secure, sessionOpt, sessionCookieBaker, flashCookieBaker)
    with Request[Source[ByteString, _]] {
  lazy val body: Source[ByteString, _] = {
    Source.fromPublisher(rawBody)
  }
  def withBody(newBody: Flux[ByteString]): NettyRequest =
    new NettyRequest(req, ctx, newBody, secure, sessionOpt, sessionCookieBaker, flashCookieBaker)
}

class NettyRequestHeader(
    req: HttpRequest,
    ctx: ChannelHandlerContext,
    secure: Boolean,
    sessionOpt: Option[SSLSession],
    sessionCookieBaker: SessionCookieBaker,
    flashCookieBaker: FlashCookieBaker
) extends RequestHeader {

  lazy val _cookies                     = Option(req.headers().get("Cookie"))
    .map(c => ServerCookieDecoder.LAX.decode(c).asScala.groupBy(_.name()).mapValues(_.toSeq))
    .getOrElse(Map.empty[String, Seq[io.netty.handler.codec.http.cookie.DefaultCookie]])

  lazy val zeSession: Session = {
    _cookies
      .get(sessionCookieBaker.COOKIE_NAME)
      .flatMap(_.headOption)
      .flatMap { value =>
        Try(sessionCookieBaker.deserialize(sessionCookieBaker.decode(value.value()))).toOption
      }
      .getOrElse(Session())
  }
  lazy val zeFlash: Flash = {
    _cookies
      .get(flashCookieBaker.COOKIE_NAME)
      .flatMap(_.headOption)
      .flatMap { value =>
        Try(flashCookieBaker.deserialize(flashCookieBaker.decode(value.value()))).toOption
      }
      .getOrElse(Flash())
  }
  val count                             = NettyRequest.counter.incrementAndGet()
  lazy val attrs                        = TypedMap.apply(
    RequestAttrKey.Id                    -> count,
    RequestAttrKey.Session               -> Cell(zeSession),
    RequestAttrKey.Flash                 -> Cell(zeFlash),
    RequestAttrKey.Server                -> "netty-experimental",
    NettyRequestKeys.TrailerHeadersIdKey -> s"${IdGenerator.uuid}-${count}",
    RequestAttrKey.Cookies               -> Cell(Cookies(_cookies.toSeq.flatMap { case (_, cookies) =>
      cookies.map {
        case cookie: io.netty.handler.codec.http.cookie.DefaultCookie => {
          play.api.mvc.Cookie(
            name = cookie.name(),
            value = cookie.value(),
            maxAge = Option(cookie.maxAge()).map(_.toInt),
            path = Option(cookie.path()).filter(_.nonEmpty).getOrElse("/"),
            domain = Option(cookie.domain()).filter(_.nonEmpty),
            secure = cookie.isSecure,
            httpOnly = cookie.isHttpOnly,
            sameSite = Option(cookie.sameSite()).map {
              case e if e == io.netty.handler.codec.http.cookie.CookieHeaderNames.SameSite.None   =>
                play.api.mvc.Cookie.SameSite.None
              case e if e == io.netty.handler.codec.http.cookie.CookieHeaderNames.SameSite.Strict =>
                play.api.mvc.Cookie.SameSite.Strict
              case e if e == io.netty.handler.codec.http.cookie.CookieHeaderNames.SameSite.Lax    =>
                play.api.mvc.Cookie.SameSite.Lax
              case _                                                                              => play.api.mvc.Cookie.SameSite.None
            }
          )
        }
        case cookie                                                   => {
          play.api.mvc.Cookie(
            name = cookie.name(),
            value = cookie.value(),
            maxAge = Option(cookie.maxAge()).map(_.toInt),
            path = Option(cookie.path()).filter(_.nonEmpty).getOrElse("/"),
            domain = Option(cookie.domain()).filter(_.nonEmpty),
            secure = cookie.isSecure,
            httpOnly = cookie.isHttpOnly,
            sameSite = None
          )
        }
      }
    }))
  )
  lazy val method: String               = req.method().toString
  lazy val version: String              = req.protocolVersion().toString
  lazy val headers: Headers             = Headers(
    (req.headers().entries().asScala.map(e => (e.getKey, e.getValue)) ++ sessionOpt.map(s =>
      ("Tls-Session-Info", s.toString)
    )): _*
  )
  lazy val connection: RemoteConnection = new NettyRemoteConnection(req, ctx, secure, sessionOpt)
  lazy val target: RequestTarget        = new NettyRequestTarget(req)
}

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////