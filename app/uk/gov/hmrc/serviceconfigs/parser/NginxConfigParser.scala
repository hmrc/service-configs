/*
 * Copyright 2019 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.serviceconfigs.parser

import javax.inject.Inject
import uk.gov.hmrc.serviceconfigs.config.NginxConfig
import uk.gov.hmrc.serviceconfigs.model.{FrontendRoute, ShutterKillswitch, ShutterServiceSwitch}

import scala.util.parsing.combinator.{Parsers, RegexParsers}
import scala.util.parsing.input.{NoPosition, Position, Reader}


class NginxConfigParser @Inject() (nginxConfig: NginxConfig) extends FrontendRouteParser {
  val shutterConfig = nginxConfig.shutterConfig

  override def parseConfig(config: String): Either[String, Seq[FrontendRoute]] =
    NginxTokenParser(NginxLexer(config))

  object NginxTokenParser extends Parsers {

    import Nginx._

    override type Elem = NginxToken

    class NginxTokenReader(tokens: Seq[NginxToken]) extends Reader[NginxToken] {
      override def first: NginxToken = tokens.head

      override def rest: Reader[NginxToken] = new NginxTokenReader(tokens.tail)

      override def pos: Position = NoPosition

      override def atEnd: Boolean = tokens.isEmpty
    }


    def apply(tokens: Seq[NginxToken]): Either[String, List[FrontendRoute]] = {
      val reader = new NginxTokenReader(tokens.filterNot(_.isInstanceOf[COMMENT]))
      configFile(reader) match {
        case Success(result, _) => Right(result.flatMap {
          case l: LOCATION => locToRoute(l)
          case _           => None
        })
        case Failure(msg, _)    => Left(s"Failed to parse nginx config: $msg")
        case Error(msg, _)      => Left(s"Error while to parsing nginx config: $msg")
      }
    }

    def extractKillSwitch(ifblocks: List[IFBLOCK]): Option[ShutterKillswitch] = {
      val maybeKillswitches = ifblocks.map(ib =>
        if (ib.predicate.contains(shutterConfig.shutterKillswitchPath)) ib.body match {
          case List(RETURN(code)) => Some(ShutterKillswitch(code))
          case _ => None
        } else None
      )
      maybeKillswitches.collectFirst { case Some(s) => s }
    }

    def extractShutterSwitch(ifblocks: List[IFBLOCK]): Option[ShutterServiceSwitch] = {
      val p = """\(-f(""" + shutterConfig.shutterServiceSwitchPathPrefix.trim + """[a-zA-Z0-9_-]+)\)"""

      val maybeShutterSwitches = ifblocks.map(ib =>
        p.r.findFirstMatchIn(ib.predicate).flatMap(switch => ib.body match {
          case List(ERROR_PAGE(_, errorPage), RETURN(retCode)) => Some(ShutterServiceSwitch(retCode, switch.group(1), errorPage))
          case _ => None
        })
      )
      maybeShutterSwitches.collectFirst { case Some(s) => s }
    }

    def locToRoute(loc: LOCATION): Option[FrontendRoute] = {
      val ifs = loc.body.filter(_.isInstanceOf[IFBLOCK]).map(_.asInstanceOf[IFBLOCK])

      val shutterKillswitch = extractKillSwitch(ifs)
      val shutterServiceSwitch = extractShutterSwitch(ifs)

      loc.body.find(_.isInstanceOf[PROXY_PASS]).map(_.asInstanceOf[PROXY_PASS].url).map(
        proxy => FrontendRoute(frontendPath = loc.path, backendPath = proxy, isRegex = loc.regex,
          shutterKillswitch = shutterKillswitch, shutterServiceSwitch = shutterServiceSwitch)
      )
    }


    sealed trait NGINX_AST

    case class LOCATION(path: String, body: List[NGINX_AST], regex: Boolean = false) extends NGINX_AST

    case class IFBLOCK(predicate: String, body: List[NGINX_AST]) extends NGINX_AST

    trait PARAM extends NGINX_AST

    case class ERROR_PAGE(code: Int, path: String) extends PARAM

    case class PROXY_PASS(url: String) extends PARAM

    case class RETURN(code: Int) extends PARAM

    case class OTHER_PARAM(key: String, params: String*) extends PARAM

    case class COMMENT_LINE() extends NGINX_AST

    def keyword: Parser[KEYWORD] =
      accept("keyword", { case lit@KEYWORD(_) => lit })

    def value: Parser[VALUE] =
      accept("value", { case v@VALUE(_) => v })

    def parameter1: Parser[PARAM] = (keyword ~ rep1(value) <~ SEMICOLON()) ^^ {
      case KEYWORD("proxy_pass") ~ List(v) => PROXY_PASS(v.v)
      case KEYWORD("return") ~ List(v) => RETURN(v.v.toInt)
      case KEYWORD("error_page") ~ List(code, page) => ERROR_PAGE(code.v.toInt, page.v)
      case kw ~ List(v) => OTHER_PARAM(kw.v, v.v)
      case kw ~ _ => OTHER_PARAM(kw.v)
    }

    def parameter2: Parser[PARAM] = (keyword ~ keyword ~ rep(value) <~ SEMICOLON()) ^^ {
      case kw ~ p1 ~ List(v) => OTHER_PARAM(kw.v, p1.v ++ v.v)
    }

    def parameter: Parser[PARAM] = parameter1 | parameter2

    def block: Parser[List[NGINX_AST]] = rep1(parameter | context)

    def context: Parser[NGINX_AST] = (keyword ~ rep(value) ~ OPEN_BRACKET() ~ block ~ CLOSE_BRACKET()) ^^ {
      case KEYWORD("location") ~  List(v) ~ _ ~ b ~ _ => LOCATION(v.v, b)
      case KEYWORD("location") ~  List(_, v) ~ _ ~ b ~ _ => LOCATION(v.v, b, regex = true)
      case KEYWORD("if") ~ cond ~ _ ~ b ~ _ => IFBLOCK(cond.map(_.v).mkString, b)
    }


    def configFile: NginxTokenParser.Parser[List[NGINX_AST]] = phrase(rep1(context | parameter))
  }


}

object Nginx {

  sealed trait NginxToken

  case class OPEN_BRACKET() extends NginxToken

  case class CLOSE_BRACKET() extends NginxToken

  case class SEMICOLON() extends NginxToken

  case class COMMENT() extends NginxToken

  case class KEYWORD(v: String) extends NginxToken

  case class VALUE(v: String) extends NginxToken
}


object NginxLexer extends RegexParsers {

  import Nginx._

  override def skipWhitespace: Boolean = true

  def openBracket: Parser[OPEN_BRACKET] = "{" ^^ { _ => OPEN_BRACKET() }

  def closeBracket: Parser[CLOSE_BRACKET] = "}" ^^ { _ => CLOSE_BRACKET() }

  def semicolon: Parser[SEMICOLON] = ";" ^^ { _ => SEMICOLON() }

  def comment: Parser[COMMENT] = "#.+".r ^^ { _ => COMMENT() }

  def unquotedValue: Parser[VALUE] = """[^{}; ]+""".r ^^ (k => VALUE(k.trim))

  def url: Parser[VALUE] = """https?:\/\/(www\.)?[-a-zA-Z0-9@:%._\+~#=]{2,256}\.[a-z]{2,6}\b([-a-zA-Z0-9@:%_\+.~#?&//=]*)""".r ^^ (k => VALUE(k.trim))

  def doubleQuotedValue: Parser[VALUE] =
    """\"[^\"]+\"""".r ^^ (k => VALUE(k.trim))

  def singleQuotedValue: Parser[VALUE] =
    """'[^']+'""".r ^^ (k => VALUE(k.trim))

  def quotedValue: Parser[VALUE] = singleQuotedValue | doubleQuotedValue

  def value: Parser[VALUE] = url | quotedValue | unquotedValue

  def keyword: Parser[KEYWORD] = """[a-zA-Z_][a-zA-Z0-9_]* """.r ^^ (k => KEYWORD(k.trim))

  def tokens: Parser[List[NginxToken]] = {
    phrase(rep1(openBracket | closeBracket | semicolon | comment | keyword | value ))
  }

  def apply(config: String): Seq[NginxToken] = {
    parse(tokens, config).get
  }
}

