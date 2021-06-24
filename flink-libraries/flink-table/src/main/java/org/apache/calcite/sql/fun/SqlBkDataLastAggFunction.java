package org.apache.calcite.sql.fun;

import com.google.common.collect.ImmutableList;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.sql.SqlAggFunction;
import org.apache.calcite.sql.SqlFunctionCategory;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.type.OperandTypes;
import org.apache.calcite.sql.type.ReturnTypes;
import org.apache.calcite.sql.type.SqlOperandTypeInference;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.flink.util.Preconditions;

import java.util.List;

/**
 * Copyright © 2012-2018 Tencent BlueKing.
 * All Rights Reserved.
 * 蓝鲸智云 版权所有
 */
public class SqlBkDataLastAggFunction extends SqlAggFunction {

	public SqlBkDataLastAggFunction(String functionName, SqlKind kind) {
		super(functionName, (SqlIdentifier)null, kind, ReturnTypes.ARG0_NULLABLE_IF_EMPTY, (SqlOperandTypeInference)null, OperandTypes.ANY, SqlFunctionCategory.SYSTEM, false, true);
		Preconditions.checkArgument(kind == SqlKind.OTHER_FUNCTION);
	}

	public List<RelDataType> getParameterTypes(RelDataTypeFactory typeFactory) {
		return ImmutableList.of(typeFactory.createTypeWithNullability(typeFactory.createSqlType(SqlTypeName.ANY), true));
	}

	public RelDataType getReturnType(RelDataTypeFactory typeFactory) {
		return typeFactory.createTypeWithNullability(typeFactory.createSqlType(SqlTypeName.ANY), true);
	}

}
