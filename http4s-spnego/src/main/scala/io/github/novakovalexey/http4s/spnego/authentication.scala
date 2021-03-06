package io.github.novakovalexey.http4s.spnego

import java.io.IOException
import java.security.{PrivilegedAction, PrivilegedActionException, PrivilegedExceptionAction}
import java.util.Collections

import cats.Monad
import cats.data.{Kleisli, OptionT}
import cats.implicits._
import cats.syntax.either._
import com.typesafe.scalalogging.LazyLogging
import io.github.novakovalexey.http4s.spnego.SpnegoAuthenticator._
import javax.security.auth.Subject
import javax.security.auth.kerberos.KerberosPrincipal
import javax.security.auth.login.{Configuration, LoginContext}
import org.http4s._
import org.http4s.server.AuthMiddleware
import org.ietf.jgss.{GSSCredential, GSSManager}

import scala.io.Codec
import scala.util.{Failure, Success, Try}

object Spnego {

  def apply[F[_]: Monad](cfg: SpnegoConfig): Spnego[F] =
    new Spnego[F](cfg)
}

class Spnego[F[_]: Monad](cfg: SpnegoConfig) extends LazyLogging {
  logger.info(s"Configuration:\n ${cfg.show}")

  private val secret = Codec.toUTF8(cfg.signatureSecret)
  private[spnego] val tokens = new Tokens(cfg.tokenValidity.toMillis, secret)
  private[spnego] val authenticator = new SpnegoAuthenticator(cfg, tokens)

  val authToken: Kleisli[F, Request[F], Either[Rejection, Token]] =
    Kleisli(request => authenticator.apply(request.headers).pure[F])

  private val onFailure: AuthedRoutes[Rejection, F] =
    Kleisli { req =>
      val (e, h) = req.authInfo match {
        case AuthenticationFailedRejection(r, h) => (reasonToString(r), Seq(h))
        case MalformedHeaderRejection(name, msg, cause) =>
          cause.foreach(t => logger.error("MalformedHeaderRejection", t))
          (s"Failed to parse '$name' value, because of $msg", Seq.empty)
        case ServerErrorRejection(e) => (s"server error: ${e.getMessage}", Seq.empty)
        case UnexpectedErrorRejection(e) => (s"unexpected error: ${e.getMessage}", Seq.empty)
      }
      val res = Response[F](Status.Unauthorized).putHeaders(h: _*).withEntity(e)
      OptionT.liftF(res.pure[F])
    }

  def apply(service: AuthedRoutes[Token, F]): HttpRoutes[F] =
    middleware.apply(service)

  def middleware(onFailure: AuthedRoutes[Rejection, F]): AuthMiddleware[F, Token] =
    AuthMiddleware(authToken, onFailure)

  val middleware: AuthMiddleware[F, Token] = AuthMiddleware(authToken, onFailure)

  def signCookie(token: Token): ResponseCookie = {
    val content = tokens.serialize(token)
    ResponseCookie(cfg.cookieName, content, domain = cfg.domain, path = cfg.path)
  }
}

private[spnego] object SpnegoAuthenticator {
  private[spnego] val Negotiate = "Negotiate"
  private[spnego] val Authenticate = "WWW-Authenticate"

  private[spnego] def reasonToString: RejectionReason => String = {
    case CredentialsRejected => "Credentials rejected"
    case CredentialsMissing => "Credentials are missing"
  }
}

private[spnego] class SpnegoAuthenticator(cfg: SpnegoConfig, tokens: Tokens) extends LazyLogging {

  private val subject = new Subject(
    false,
    Collections.singleton(new KerberosPrincipal(cfg.principal)),
    Collections.emptySet(),
    Collections.emptySet()
  )

  private val (entryName, kerberosConfiguration) =
    cfg.jaasConfig match {
      case Some(c) => ("", KerberosConfiguration(cfg.principal, c))
      case None => (SpnegoConfig.JaasConfigEntryName, null.asInstanceOf[Configuration])
    }

  private val noCallback = null
  private val loginContext =
    Try(new LoginContext(entryName, subject, noCallback, kerberosConfiguration))
      .fold(
        e =>
          throw new RuntimeException(
            "In case of JAAS file is used, please check that java.security.auth.login.config Java property is set",
            e
        ),
        identity
      )

  loginContext.login()

  private[spnego] def apply(hs: Headers): Either[Rejection, Token] =
    cookieToken(hs).orElse(kerberosNegotiate(hs)).getOrElse(initiateNegotiations)

  private val gssManager = Subject.doAs(loginContext.getSubject, new PrivilegedAction[GSSManager] {
    override def run: GSSManager = GSSManager.getInstance
  })

  private def cookieToken(hs: Headers): Option[Either[Rejection, Token]] = {
    for {
      c <- headers.Cookie
        .from(hs)
        .collect { case h => h.values.find(_.name == cfg.cookieName) }
        .flatten

      _ = logger.debug("cookie found")

      t <- Some(
        tokens
          .parse(c.content)
          .leftMap(e => MalformedHeaderRejection(s"Cookie: ${cfg.cookieName}", e.message, None))
      )

      res <- t match {
        case Right(token) =>
          if (token.expired) {
            logger.debug("SPNEGO token inside cookie expired")
            None
          } else {
            logger.debug("SPNEGO token inside cookie not expired")
            Some(Right(token))
          }
        case Left(e) => Some(Left(e))
      }

    } yield res
  }

  private def clientToken(hs: Headers): Option[Array[Byte]] =
    headers.Authorization.from(hs).filter(_.value.startsWith(Negotiate)).map { authHeader =>
      logger.debug("authorization header found")
      Base64Util.decode(authHeader.value.substring(Negotiate.length).trim)
    }

  private def kerberosNegotiate(hs: Headers): Option[Either[Rejection, Token]] = clientToken(hs).map(kerberosCore)

  private def challengeHeader(maybeServerToken: Option[Array[Byte]] = None): Header = {
    val scheme = Negotiate + maybeServerToken.map(" " + Base64Util.encode(_)).getOrElse("")
    Header(Authenticate, scheme)
  }

  private def kerberosCore(clientToken: Array[Byte]): Either[Rejection, Token] = {
    Try {
      val (maybeServerToken, maybeToken) = kerberosAcceptToken(clientToken)
      logger.debug(s"serverToken '${maybeServerToken.map(Base64Util.encode)}' token '$maybeToken'")

      maybeToken.map { token =>
        logger.debug("received new token")
        Right(token)
      }.getOrElse {
        logger.debug("no token received, but if there is a serverToken, then negotiations are ongoing")
        Left(AuthenticationFailedRejection(CredentialsMissing, challengeHeader(maybeServerToken)))
      }
    } match {
      case Success(v) => v
      case Failure(e: PrivilegedActionException) =>
        e.getException match {
          case e: IOException =>
            logger.error("server error", e)
            Left(ServerErrorRejection(e))
          case _ =>
            logger.error("negotiation failed", e)
            Left(AuthenticationFailedRejection(CredentialsRejected, challengeHeader()))
        }
      case Failure(e) =>
        logger.error("unexpected error", e)
        Left(UnexpectedErrorRejection(e))
    }
  }

  private[spnego] def kerberosAcceptToken(clientToken: Array[Byte]): (Option[Array[Byte]], Option[Token]) = {
    Subject.doAs(
      loginContext.getSubject,
      new PrivilegedExceptionAction[(Option[Array[Byte]], Option[Token])] {
        override def run: (Option[Array[Byte]], Option[Token]) = {
          val defaultAcceptor: GSSCredential = null
          val gssContext = gssManager.createContext(defaultAcceptor)
          Try {
            (
              Option(gssContext.acceptSecContext(clientToken, 0, clientToken.length)),
              if (gssContext.isEstablished) Some(tokens.create(gssContext.getSrcName.toString)) else None
            )
          } match {
            case Success(r) =>
              gssContext.dispose()
              r
            case Failure(e) =>
              logger.error("error in establishing security context", e)
              throw e
          }
        }
      }
    )
  }

  private def initiateNegotiations: Either[Rejection, Token] = {
    logger.debug("no negotiation header found, initiating negotiations")
    Left(AuthenticationFailedRejection(CredentialsMissing, challengeHeader()))
  }
}
