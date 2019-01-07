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

import scala.util.parsing.combinator.RegexParsers

sealed trait MatchResult

case class ParserFrontendRoute(path: String, proxy: String) extends MatchResult

case class NoFrontendRoute(err: String) extends MatchResult

case class Comment(text: String)

trait FrontendRoutersParser {
  def parseConfig(config: String): Seq[ParserFrontendRoute]
}

class NginxConfigParser extends RegexParsers with FrontendRoutersParser {

  sealed trait NginxToken

  case class ProxyPassToken() extends NginxToken

  case class LocationToken() extends NginxToken

  case class IfToken() extends NginxToken

  case class Url(url: String) extends NginxToken

  case class Path(path: String) extends NginxToken

  case class EOL() extends NginxToken

  case class ContentStart() extends NginxToken

  case class ContentEnd() extends NginxToken

  case class ContentName(name: String) extends NginxToken

  case class Location(url: String)

  case class ProxyPass(url: String)

  case class Param(name: String, args: List[String])


  def contextId: Parser[NginxToken] = ("location" | "if") ^^ {
    case "location" => LocationToken()
    case "if" => IfToken()
  }

  def contextStart: Parser[ContentStart] = "{" ^^ {
    case "{" => ContentStart()
  }

  def contextEnd: Parser[ContentEnd] = "}" ^^ {
    case "}" => ContentEnd()
  }

  def text: Parser[String] = """[\w|\d|\/|\_|\(|\)|-|:|\.|\-|'|,=\$]+""".r

  def comment: Parser[Comment] = """#.+""".r ^^ { t => Comment(t) }

  def contextParam: Parser[String] = """[^\s{]+""".r

  def paramName: Parser[String] = text

  def paramValue: Parser[String] = """[^\s;{]+""".r

  def paramEnd: Parser[EOL] = ";" ^^ { _ => EOL() }

  def param: Parser[Param] = paramName ~ rep(paramValue) <~ paramEnd ^^ {
    case name ~ args => Param(name, args)
  }

  /**
    * Drop all items that aren't the proxy_pass param
    */
  def contextBody: Parser[Option[Param]] = rep(param | context | comment) ^^ {
    b => b.filter(_.isInstanceOf[Param]).map(_.asInstanceOf[Param]).find(_.name == "proxy_pass")
  }

  def contextHeader = contextId ~ rep(contextParam) <~ contextStart ^^ {
    case LocationToken() ~ List(path) => Some(Location(path))
    case LocationToken() ~ List(_, path) => Some(Location(path))
    case _ => None
  }

  def context =
    contextHeader ~ contextBody <~ contextEnd ^^ {
      case Some(Location(p)) ~ Some(Param("proxy_pass", u)) => ParserFrontendRoute(p, u.head)
      case x => NoFrontendRoute(x.toString())
    }


  def nginxConfig: Parser[Seq[ParserFrontendRoute]] = phrase(rep(comment | context)) ^^ {
    seq => seq.filter(_.isInstanceOf[ParserFrontendRoute]).map(_.asInstanceOf[ParserFrontendRoute])
  }

  override def skipWhitespace: Boolean = true

  def parseConfig(config: String): Seq[ParserFrontendRoute] =
    parse(nginxConfig, config).getOrElse(Seq.empty[ParserFrontendRoute])

}
