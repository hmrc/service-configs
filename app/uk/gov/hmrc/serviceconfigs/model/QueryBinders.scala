/*
 * Copyright 2023 HM Revenue & Customs
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

package uk.gov.hmrc.serviceconfigs.model

import play.api.mvc.{PathBindable, QueryStringBindable}

object QueryBinders {

  implicit def filterTypeBindable(implicit strBinder: QueryStringBindable[String]): QueryStringBindable[FilterType] =
    new QueryStringBindable[FilterType] {
      override def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, FilterType]] =
        strBinder.bind(key, params)
          .map(_.flatMap(s =>
            FilterType.parse(s).toRight(s"Invalid FilterType '$s'")
          ))

      override def unbind(key: String, value: FilterType): String =
        strBinder.unbind(key, value.asString)
    }

  implicit def environmentBindable(implicit strBinder: QueryStringBindable[String]): QueryStringBindable[Environment] =
    new QueryStringBindable[Environment] {
      override def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, Environment]] =
        strBinder.bind(key, params)
          .map(_.flatMap(s =>
            Environment.parse(s).toRight(s"Invalid Environment '$s'")
          ))

      override def unbind(key: String, value: Environment): String =
        strBinder.unbind(key, value.asString)
    }

  implicit def environmentParamBindable(implicit strBinder: PathBindable[String]): PathBindable[Environment] =
    new PathBindable[Environment] {
      override def bind(key: String, value: String): Either[String, Environment] =
        Environment.parse(value) match {
          case None      => Left(s"Invalid Environment $value")
          case Some(env) => Right(env)
        }

      override def unbind(key: String, value: Environment): String =
        strBinder.unbind(key, value.asString)
    }

  implicit def serviceTypeBindable(implicit strBinder: QueryStringBindable[String]): QueryStringBindable[ServiceType] =
    new QueryStringBindable[ServiceType] {
      override def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, ServiceType]] =
        strBinder.bind(key, params)
          .map(_.flatMap(s =>
            ServiceType.parse(s).toRight(s"Invalid ServiceType '$s'")
          ))

      override def unbind(key: String, value: ServiceType): String =
        strBinder.unbind(key, value.asString)
    }

  implicit def serviceNameBindable(implicit strBinder: QueryStringBindable[String]): QueryStringBindable[ServiceName] =
    strBinder.transform(ServiceName.apply, _.asString)

  implicit def tagBindable(implicit strBinder: QueryStringBindable[String]): QueryStringBindable[Tag] =
    strBinder.transform(Tag.apply, _.asString)

  implicit def teamNameBindable(implicit strBinder: QueryStringBindable[String]): QueryStringBindable[TeamName] =
    strBinder.transform(TeamName.apply, _.asString)
}
