/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.flink.table.expressions

import org.apache.calcite.rex.RexNode
import org.apache.calcite.sql.SqlAggFunction
import org.apache.calcite.sql.fun._
import org.apache.calcite.tools.RelBuilder
import org.apache.calcite.tools.RelBuilder.AggCall
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.table.functions.AggregateFunction
import org.apache.flink.table.functions.utils.AggSqlFunction
import org.apache.flink.table.typeutils.TypeCheckUtils
import org.apache.flink.api.common.typeinfo.BasicTypeInfo
import org.apache.flink.api.java.typeutils.MultisetTypeInfo
import org.apache.flink.table.calcite.FlinkTypeFactory
import org.apache.flink.table.functions.utils.UserDefinedFunctionUtils._
import org.apache.flink.table.validate.{BasicOperatorTable, ValidationFailure, ValidationResult, ValidationSuccess}

abstract sealed class Aggregation extends Expression {

  override def toString = s"Aggregate"

  override private[flink] def toRexNode(implicit relBuilder: RelBuilder): RexNode =
    throw new UnsupportedOperationException("Aggregate cannot be transformed to RexNode")

  /**
    * Convert Aggregate to its counterpart in Calcite, i.e. AggCall
    */
  private[flink] def toAggCall(
      name: String,
      isDistinct: Boolean = false
  )(implicit relBuilder: RelBuilder): AggCall

  /**
    * Returns the SqlAggFunction for this Aggregation.
    */
  private[flink] def getSqlAggFunction()(implicit relBuilder: RelBuilder): SqlAggFunction

}

case class DistinctAgg(child: Expression) extends Aggregation {

  def distinct: Expression = DistinctAgg(child)

  override private[flink] def resultType: TypeInformation[_] = child.resultType

  override private[flink] def validateInput(): ValidationResult = {
    super.validateInput()
    child match {
      case agg: Aggregation =>
        child.validateInput()
      case _ =>
        ValidationFailure(s"Distinct modifier cannot be applied to $child! " +
          s"It can only be applied to an aggregation expression, for example, " +
          s"'a.count.distinct which is equivalent with COUNT(DISTINCT a).")
    }
  }

  override private[flink] def toAggCall(
      name: String, isDistinct: Boolean = true)(implicit relBuilder: RelBuilder) = {
    child.asInstanceOf[Aggregation].toAggCall(name, isDistinct = true)
  }

  override private[flink] def getSqlAggFunction()(implicit relBuilder: RelBuilder) = {
    child.asInstanceOf[Aggregation].getSqlAggFunction()
  }

  override private[flink] def children = Seq(child)
}

case class Sum(child: Expression) extends Aggregation {
  override private[flink] def children: Seq[Expression] = Seq(child)
  override def toString = s"sum($child)"

  override private[flink] def toAggCall(
      name: String, isDistinct: Boolean = false)(implicit relBuilder: RelBuilder): AggCall = {
    relBuilder.aggregateCall(
      SqlStdOperatorTable.SUM,
      isDistinct,
      false,
      null,
      name,
      child.toRexNode)
  }

  override private[flink] def resultType = child.resultType

  override private[flink] def validateInput() =
    TypeCheckUtils.assertNumericExpr(child.resultType, "sum")

  override private[flink] def getSqlAggFunction()(implicit relBuilder: RelBuilder) = {
    val returnType = relBuilder
      .getTypeFactory.asInstanceOf[FlinkTypeFactory]
      .createTypeFromTypeInfo(resultType, isNullable = true)
    new SqlSumAggFunction(returnType)
  }
}

case class Sum0(child: Expression) extends Aggregation {
  override private[flink] def children: Seq[Expression] = Seq(child)
  override def toString = s"sum0($child)"

  override private[flink] def toAggCall(
      name: String, isDistinct: Boolean = false)(implicit relBuilder: RelBuilder): AggCall = {
    relBuilder.aggregateCall(
      SqlStdOperatorTable.SUM0,
      isDistinct,
      false,
      null,
      name,
      child.toRexNode)
  }

  override private[flink] def resultType = child.resultType

  override private[flink] def validateInput() =
    TypeCheckUtils.assertNumericExpr(child.resultType, "sum0")

  override private[flink] def getSqlAggFunction()(implicit relBuilder: RelBuilder) =
    SqlStdOperatorTable.SUM0
}

case class Min(child: Expression) extends Aggregation {
  override private[flink] def children: Seq[Expression] = Seq(child)
  override def toString = s"min($child)"

  override private[flink] def toAggCall(
      name: String, isDistinct: Boolean = false)(implicit relBuilder: RelBuilder): AggCall = {
    relBuilder.aggregateCall(
      SqlStdOperatorTable.MIN,
      isDistinct,
      false,
      null,
      name,
      child.toRexNode)
  }

  override private[flink] def resultType = child.resultType

  override private[flink] def validateInput() =
    TypeCheckUtils.assertOrderableExpr(child.resultType, "min")

  override private[flink] def getSqlAggFunction()(implicit relBuilder: RelBuilder) = {
    SqlStdOperatorTable.MIN
  }
}

case class Max(child: Expression) extends Aggregation {
  override private[flink] def children: Seq[Expression] = Seq(child)
  override def toString = s"max($child)"

  override private[flink] def toAggCall(
      name: String, isDistinct: Boolean = false)(implicit relBuilder: RelBuilder): AggCall = {
    relBuilder.aggregateCall(
      SqlStdOperatorTable.MAX,
      isDistinct,
      false,
      null,
      name,
      child.toRexNode)
  }

  override private[flink] def resultType = child.resultType

  override private[flink] def validateInput() =
    TypeCheckUtils.assertOrderableExpr(child.resultType, "max")

  override private[flink] def getSqlAggFunction()(implicit relBuilder: RelBuilder) = {
    SqlStdOperatorTable.MAX
  }
}

case class Count(child: Expression) extends Aggregation {
  override private[flink] def children: Seq[Expression] = Seq(child)
  override def toString = s"count($child)"

  override private[flink] def toAggCall(
      name: String, isDistinct: Boolean = false)(implicit relBuilder: RelBuilder): AggCall = {
    relBuilder.aggregateCall(
      SqlStdOperatorTable.COUNT,
      isDistinct,
      false,
      null,
      name,
      child.toRexNode)
  }

  override private[flink] def resultType = BasicTypeInfo.LONG_TYPE_INFO

  override private[flink] def getSqlAggFunction()(implicit relBuilder: RelBuilder) = {
    SqlStdOperatorTable.COUNT
  }
}

case class Avg(child: Expression) extends Aggregation {
  override private[flink] def children: Seq[Expression] = Seq(child)
  override def toString = s"avg($child)"

  override private[flink] def toAggCall(
      name: String, isDistinct: Boolean = false)(implicit relBuilder: RelBuilder): AggCall = {
    relBuilder.aggregateCall(
      SqlStdOperatorTable.AVG,
      isDistinct,
      false,
      null,
      name,
      child.toRexNode)
  }

  override private[flink] def resultType = child.resultType

  override private[flink] def validateInput() =
    TypeCheckUtils.assertNumericExpr(child.resultType, "avg")

  override private[flink] def getSqlAggFunction()(implicit relBuilder: RelBuilder) = {
    SqlStdOperatorTable.AVG
  }
}

/**
  *  bkdata_last
  * @param child
  */
case class BkDataLast(child: Expression) extends Aggregation {
  override private[flink] def children: Seq[Expression] = Seq(child)
  override def toString = s"bkdata_last($child)"

  override private[flink] def toAggCall(name: String, isDistinct: Boolean = false)
                                       (implicit relBuilder: RelBuilder): AggCall = {
    relBuilder.aggregateCall(
      BasicOperatorTable.BKDATA_LAST, false, false, null, name, child.toRexNode)
  }

  override private[flink] def resultType = child.resultType

//  override private[flink] def validateInput() =
//    TypeCheckUtils.assertOrderableExpr(child.resultType, "bkdata_last")

  override private[flink] def getSqlAggFunction()(implicit relBuilder: RelBuilder) = {
    BasicOperatorTable.BKDATA_LAST
  }
}

/**
  *  bkdata_first
  * @param child
  */
case class BkDataFirst(child: Expression) extends Aggregation {
  override private[flink] def children: Seq[Expression] = Seq(child)
  override def toString = s"bkdata_first($child)"

  override private[flink] def toAggCall(name: String, isDistinct: Boolean = false)
                                       (implicit relBuilder: RelBuilder): AggCall = {
    relBuilder.aggregateCall(
      BasicOperatorTable.BKDATA_FIRST, false, false, null, name, child.toRexNode)
  }

  override private[flink] def resultType = child.resultType

//  override private[flink] def validateInput() =
//    TypeCheckUtils.assertOrderableExpr(child.resultType, "bkdata_first")

  override private[flink] def getSqlAggFunction()(implicit relBuilder: RelBuilder) = {
    BasicOperatorTable.BKDATA_FIRST
  }
}

/**
  *  20190305 add bkdata_sum
  *  bkdata_sum
  * @param child
  */
case class BkDataSum(child: Expression) extends Aggregation {
  override private[flink] def children: Seq[Expression] = Seq(child)
  override def toString = s"bkdata_sum($child)"

  override private[flink] def toAggCall(name: String, isDistinct: Boolean = false)
                                       (implicit relBuilder: RelBuilder): AggCall = {
    relBuilder.aggregateCall(
      BasicOperatorTable.BKDATA_SUM, isDistinct, false, null, name, child.toRexNode)
  }

  override private[flink] def resultType = child.resultType match {
    case BasicTypeInfo.INT_TYPE_INFO | BasicTypeInfo.LONG_TYPE_INFO =>
      BasicTypeInfo.LONG_TYPE_INFO
    case BasicTypeInfo.FLOAT_TYPE_INFO | BasicTypeInfo.DOUBLE_TYPE_INFO =>
      BasicTypeInfo.DOUBLE_TYPE_INFO
    case _ => child.resultType
  }

  override private[flink] def validateInput() =
    TypeCheckUtils.assertNumericExpr(child.resultType, "bkdata_sum")

  override private[flink] def getSqlAggFunction()(implicit relBuilder: RelBuilder) = {
    BasicOperatorTable.BKDATA_SUM
  }
}


/**
  * Returns a multiset aggregates.
  */
case class Collect(child: Expression) extends Aggregation  {

  override private[flink] def children: Seq[Expression] = Seq(child)

  override private[flink] def resultType: TypeInformation[_] =
    MultisetTypeInfo.getInfoFor(child.resultType)

  override def toString: String = s"collect($child)"

  override private[flink] def toAggCall(
      name: String, isDistinct: Boolean = false)(implicit relBuilder: RelBuilder): AggCall = {
    relBuilder.aggregateCall(
      SqlStdOperatorTable.COLLECT,
      isDistinct,
      false,
      null,
      name,
      child.toRexNode)
  }

  override private[flink] def getSqlAggFunction()(implicit relBuilder: RelBuilder) = {
    SqlStdOperatorTable.COLLECT
  }
}

case class StddevPop(child: Expression) extends Aggregation {
  override private[flink] def children: Seq[Expression] = Seq(child)
  override def toString = s"stddev_pop($child)"

  override private[flink] def toAggCall(
      name: String, isDistinct: Boolean = false)(implicit relBuilder: RelBuilder): AggCall = {
    relBuilder.aggregateCall(
      SqlStdOperatorTable.STDDEV_POP,
      isDistinct,
      false,
      null,
      name,
      child.toRexNode)
  }

  override private[flink] def resultType = child.resultType

  override private[flink] def validateInput() =
    TypeCheckUtils.assertNumericExpr(child.resultType, "stddev_pop")

  override private[flink] def getSqlAggFunction()(implicit relBuilder: RelBuilder) =
    SqlStdOperatorTable.STDDEV_POP
}

case class StddevSamp(child: Expression) extends Aggregation {
  override private[flink] def children: Seq[Expression] = Seq(child)
  override def toString = s"stddev_samp($child)"

  override private[flink] def toAggCall(
      name: String, isDistinct: Boolean = false)(implicit relBuilder: RelBuilder): AggCall = {
    relBuilder.aggregateCall(
      SqlStdOperatorTable.STDDEV_SAMP,
      isDistinct,
      false,
      null,
      name,
      child.toRexNode)
  }

  override private[flink] def resultType = child.resultType

  override private[flink] def validateInput() =
    TypeCheckUtils.assertNumericExpr(child.resultType, "stddev_samp")

  override private[flink] def getSqlAggFunction()(implicit relBuilder: RelBuilder) =
    SqlStdOperatorTable.STDDEV_SAMP
}

case class VarPop(child: Expression) extends Aggregation {
  override private[flink] def children: Seq[Expression] = Seq(child)
  override def toString = s"var_pop($child)"

  override private[flink] def toAggCall(
      name: String, isDistinct: Boolean = false)(implicit relBuilder: RelBuilder): AggCall = {
    relBuilder.aggregateCall(
      SqlStdOperatorTable.VAR_POP,
      isDistinct,
      false,
      null,
      name,
      child.toRexNode)
  }

  override private[flink] def resultType = child.resultType

  override private[flink] def validateInput() =
    TypeCheckUtils.assertNumericExpr(child.resultType, "var_pop")

  override private[flink] def getSqlAggFunction()(implicit relBuilder: RelBuilder) =
    SqlStdOperatorTable.VAR_POP
}

case class VarSamp(child: Expression) extends Aggregation {
  override private[flink] def children: Seq[Expression] = Seq(child)
  override def toString = s"var_samp($child)"

  override private[flink] def toAggCall(
      name: String, isDistinct: Boolean = false)(implicit relBuilder: RelBuilder): AggCall = {
    relBuilder.aggregateCall(
      SqlStdOperatorTable.VAR_SAMP,
      isDistinct,
      false,
      null,
      name,
      child.toRexNode)
  }

  override private[flink] def resultType = child.resultType

  override private[flink] def validateInput() =
    TypeCheckUtils.assertNumericExpr(child.resultType, "var_samp")

  override private[flink] def getSqlAggFunction()(implicit relBuilder: RelBuilder) =
    SqlStdOperatorTable.VAR_SAMP
}

case class AggFunctionCall(
    aggregateFunction: AggregateFunction[_, _],
    resultTypeInfo: TypeInformation[_],
    accTypeInfo: TypeInformation[_],
    args: Seq[Expression])
  extends Aggregation {

  override private[flink] def children: Seq[Expression] = args

  override def resultType: TypeInformation[_] = resultTypeInfo

  override def validateInput(): ValidationResult = {
    val signature = children.map(_.resultType)
    // look for a signature that matches the input types
    val foundSignature = getAccumulateMethodSignature(aggregateFunction, signature)
    if (foundSignature.isEmpty) {
      ValidationFailure(s"Given parameters do not match any signature. \n" +
                          s"Actual: ${signatureToString(signature)} \n" +
                          s"Expected: ${
                            getMethodSignatures(aggregateFunction, "accumulate")
                              .map(_.drop(1))
                              .map(signatureToString)
                              .mkString(", ")}")
    } else {
      ValidationSuccess
    }
  }

  override def toString: String = s"${aggregateFunction.getClass.getSimpleName}($args)"

  override def toAggCall(
      name: String, isDistinct: Boolean = false)(implicit relBuilder: RelBuilder): AggCall = {
    relBuilder.aggregateCall(
      this.getSqlAggFunction(),
      isDistinct,
      false,
      null,
      name,
      args.map(_.toRexNode): _*)
  }

  override private[flink] def getSqlAggFunction()(implicit relBuilder: RelBuilder) = {
    val typeFactory = relBuilder.getTypeFactory.asInstanceOf[FlinkTypeFactory]
    AggSqlFunction(
      aggregateFunction.functionIdentifier,
      aggregateFunction.toString,
      aggregateFunction,
      resultType,
      accTypeInfo,
      typeFactory,
      aggregateFunction.requiresOver)
  }

  override private[flink] def toRexNode(implicit relBuilder: RelBuilder): RexNode = {
    relBuilder.call(this.getSqlAggFunction(), args.map(_.toRexNode): _*)
  }
}
