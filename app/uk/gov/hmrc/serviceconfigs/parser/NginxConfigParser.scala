/*
 * Copyright 2022 HM Revenue & Customs
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
import uk.gov.hmrc.serviceconfigs.model.{FrontendRoute, ShutterSwitch}

import scala.util.parsing.combinator.{Parsers, RegexParsers}
import scala.util.parsing.input.{NoPosition, Position, Reader}


class NginxConfigParser @Inject()(nginxConfig: NginxConfig) extends FrontendRouteParser {
  val shutterConfig = nginxConfig.shutterConfig

  override def parseConfig(config: String): Either[String, List[FrontendRoute]] =
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
      val reader = new NginxTokenReader(tokens)
      configFile(reader) match {
        case Success(result, _) => Right(result.flatMap {
          case l: LOCATION => locToRoute(l)
          case _           => None
        })
        case Failure(msg, _)    => Left(s"Failed to parse nginx config: $msg")
        case Error(msg, _)      => Left(s"Error while to parsing nginx config: $msg")
      }
    }

    def extractShutterSwitch(switchFile: String, ifBlock: IFBLOCK): ShutterSwitch = {
      ifBlock.body match {
        case List(ERROR_PAGE(_, errorPage), RETURN(retCode, url)) => ShutterSwitch(switchFile, Some(retCode), Some(errorPage), url)
        case List(REWRITE(rule), ERROR_PAGE(_, errorPage), RETURN(retCode, _)) => ShutterSwitch(switchFile, Some(retCode), Some(errorPage), Some(rule))
        case List(REWRITE(rule)) => ShutterSwitch(switchFile, None, None, Some(rule))
        case List(RETURN(code, url)) => ShutterSwitch(switchFile, Some(code), None, url)
        case _ => ShutterSwitch(switchFile, None, None, None)
      }
    }

    def extractKillSwitch(ifblocks: List[IFBLOCK]): Option[ShutterSwitch] = {
      val maybeKillswitches = ifblocks.map(ib =>
        if (ib.predicate.contains(shutterConfig.shutterKillswitchPath)) Some(extractShutterSwitch(shutterConfig.shutterKillswitchPath, ib)) else None
      )
      maybeKillswitches.collectFirst { case Some(s) => s }
    }

    def extractShutterSwitch(ifblocks: List[IFBLOCK]): Option[ShutterSwitch] = {
      val p = """\(-f(""" + shutterConfig.shutterServiceSwitchPathPrefix.trim + """[a-zA-Z0-9_-]+)\)"""

      val maybeShutterSwitches = ifblocks.filterNot(_.predicate.contains(shutterConfig.shutterKillswitchPath)).map(ib =>
        p.r.findFirstMatchIn(ib.predicate).flatMap(switch => Some(extractShutterSwitch(switch.group(1), ib)))
      )
      maybeShutterSwitches.collectFirst { case Some(s) => s }
    }

    def extractMarkerComments(loc: LOCATION): Set[String] = {
      //comments that start with #! which are processed by other services, e.g. #!NOT_SHUTTERABLE
      loc.body.collect {
        case c: MARKER_COMMENT => c.comment
      }.toSet
    }

    def locToRoute(loc: LOCATION): Option[FrontendRoute] = {
      val ifs = loc.body.collect { case ib: IFBLOCK => ib }

      val shutterKillswitch = extractKillSwitch(ifs)
      val shutterServiceSwitch = extractShutterSwitch(ifs)
      val markerComments = extractMarkerComments(loc)

      loc.body.collectFirst { case pp: PROXY_PASS => pp.url }.map(
        proxy => FrontendRoute(frontendPath = loc.path, backendPath = proxy, isRegex = loc.regex,
          markerComments = markerComments, shutterKillswitch = shutterKillswitch, shutterServiceSwitch = shutterServiceSwitch)
      )
    }


    sealed trait NGINX_AST

    case class LOCATION(path: String, body: List[NGINX_AST], regex: Boolean = false) extends NGINX_AST

    case class IFBLOCK(predicate: String, body: List[NGINX_AST]) extends NGINX_AST

    trait PARAM extends NGINX_AST

    case class ERROR_PAGE(code: Int, path: String) extends PARAM

    case class REWRITE(rule: String) extends PARAM

    case class PROXY_PASS(url: String) extends PARAM

    case class RETURN(code: Int, url: Option[String]) extends PARAM

    case class MARKER_COMMENT(comment: String) extends PARAM

    case class OTHER_PARAM(key: String, params: String*) extends PARAM

    case class COMMENT_LINE() extends PARAM

    def keyword: Parser[KEYWORD] =
      accept("keyword", { case lit@KEYWORD(_) => lit })

    def value: Parser[VALUE] =
      accept("value", { case v@VALUE(_) => v })

    def comment: Parser[COMMENT] =
      accept("comment", { case c@COMMENT(_) => c })

    def parameter1: Parser[PARAM] = (keyword ~ rep1(value) <~ SEMICOLON()) ^^ {
      case KEYWORD("proxy_pass") ~ List(v)          => PROXY_PASS(v.value)
      case KEYWORD("return")     ~ List(code, url)  => RETURN(code.value.toInt, Some(url.value))
      case KEYWORD("return")     ~ List(code)       => RETURN(code.value.toInt, None)
      case KEYWORD("error_page") ~ List(code, page) => ERROR_PAGE(code.value.toInt, page.value)
      case KEYWORD("rewrite")    ~ v                => REWRITE(v.map(_.value).mkString(" "))
      case kw                    ~ List(v)          => OTHER_PARAM(kw.keyword, v.value)
      case kw                    ~ _                => OTHER_PARAM(kw.keyword)
    }

    def parameter2: Parser[PARAM] = (keyword ~ keyword ~ rep(value) <~ SEMICOLON()) ^^ {
      case kw ~ p1 ~ List(v) => OTHER_PARAM(kw.keyword, p1.keyword ++ v.value)
    }

    def paramaterViaComment: Parser[PARAM] = comment ^^ {
      case c if c.comment.startsWith("#!") => MARKER_COMMENT(c.comment.stripPrefix("#!").takeWhile(_ != ' '))
      case _ => COMMENT_LINE()
    }

    def parameter: Parser[PARAM] = parameter1 | parameter2 | paramaterViaComment

    def block: Parser[List[NGINX_AST]] = rep1(parameter | context)

    def context: Parser[NGINX_AST] = (keyword ~ rep(value) ~ OPEN_BRACKET() ~ block ~ CLOSE_BRACKET()) ^^ {
      case KEYWORD("location") ~ List(v)    ~ _ ~ b ~ _ => LOCATION(v.value, b)
      case KEYWORD("location") ~ List(_, v) ~ _ ~ b ~ _ => LOCATION(v.value, b, regex = true)
      case KEYWORD("if")       ~ cond       ~ _ ~ b ~ _ => IFBLOCK(cond.map(_.value).mkString, b)
    }

    def configFile: NginxTokenParser.Parser[List[NGINX_AST]] = phrase(rep1(context | parameter))
  }


}

object Nginx {

  sealed trait NginxToken

  case class OPEN_BRACKET() extends NginxToken

  case class CLOSE_BRACKET() extends NginxToken

  case class SEMICOLON() extends NginxToken

  case class COMMENT(comment: String) extends NginxToken

  case class KEYWORD(keyword: String) extends NginxToken

  case class VALUE(value: String) extends NginxToken

}


object NginxLexer extends RegexParsers {

  import Nginx._

  override def skipWhitespace: Boolean = true

  def openBracket: Parser[OPEN_BRACKET] = "{" ^^ { _ => OPEN_BRACKET() }

  def closeBracket: Parser[CLOSE_BRACKET] = "}" ^^ { _ => CLOSE_BRACKET() }

  def semicolon: Parser[SEMICOLON] = ";" ^^ { _ => SEMICOLON() }

  def comment: Parser[COMMENT] = "#.+".r ^^ { c => COMMENT(c.trim) }

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
    phrase(rep1(openBracket | closeBracket | semicolon | comment | keyword | value))
  }

  def apply(config: String): Seq[NginxToken] = {
    parse(tokens, config).get
  }
}

