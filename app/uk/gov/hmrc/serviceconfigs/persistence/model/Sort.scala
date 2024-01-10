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

package uk.gov.hmrc.serviceconfigs.persistence.model

import org.mongodb.scala.bson.conversions.Bson
import org.mongodb.scala.model.Indexes._
import uk.gov.hmrc.serviceconfigs.persistence.model.Sort.Field.{ApplicationName, EstimatedCost}
import uk.gov.hmrc.serviceconfigs.persistence.model.Sort.Order.{Ascending, Descending}
import uk.gov.hmrc.serviceconfigs.persistence.model.Sort.{Field, Order}

sealed trait Sort {
  val field: Field
  val order: Order
  lazy val convertParams: String = this.toString
  def toBson: Bson               = order.sortBy(field.bsonField)
  override def toString: String  = s"$field.$order"
}

object Sort {

  sealed trait Order {
    def sortBy(fields: String*): Bson
  }

  object Order {

    case object Ascending extends Order {
      override def sortBy(fields: String*): Bson = ascending(fields: _*)
      override def toString: String              = "asc"
    }

    case object Descending extends Order {
      override def sortBy(fields: String*): Bson = descending(fields: _*)
      override def toString: String              = "dsc"
    }
  }

  sealed trait Field {
    val bsonField: String = this.toString
  }

  object Field {

    case object EstimatedCost extends Field {
      override val bsonField: String = "count"
      override def toString: String = "estimatedCost"
    }

    case object ApplicationName extends Field {

      override val bsonField: String = "_id"
      override def toString: String = "applicationName"
    }
  }


  sealed trait ApplicationNameSort extends Sort {
    override val field: Field = ApplicationName
  }

  sealed trait EstimatedCostSort extends Sort {
    override val field: Field = EstimatedCost
  }


  case object SortByApplicationNameAsc extends ApplicationNameSort {
    override val order: Order = Ascending
  }

  case object SortByApplicationNameDes extends ApplicationNameSort {
    override val order: Order = Descending
  }

  case object SortByEstimatedCostAsc extends EstimatedCostSort {
    override val order: Order = Ascending
  }

  case object SortByEstimatedCostDes extends EstimatedCostSort {
    override val order: Order = Descending
  }

  def apply(sortParams: Option[String]): Sort = sortParams match {
    case Some(SortByApplicationNameAsc.convertParams) => SortByApplicationNameAsc
    case Some(SortByApplicationNameDes.convertParams) => SortByApplicationNameDes
    case Some(SortByEstimatedCostAsc.convertParams)   => SortByEstimatedCostAsc
    case Some(SortByEstimatedCostDes.convertParams)   => SortByEstimatedCostDes
    case _                                            => SortByApplicationNameAsc
  }
}
