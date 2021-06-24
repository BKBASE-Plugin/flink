package org.apache.flink.table.runtime.stream.sql;

import org.apache.flink.api.common.typeinfo.BasicTypeInfo;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.typeutils.RowTypeInfo;
import org.apache.flink.streaming.api.TimeCharacteristic;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.AssignerWithPeriodicWatermarks;
import org.apache.flink.streaming.api.watermark.Watermark;
import org.apache.flink.table.api.Table;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.table.api.java.StreamTableEnvironment;
import org.apache.flink.table.runtime.utils.StreamITCase;
import org.apache.flink.test.util.AbstractTestBase;
import org.apache.flink.types.Row;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Copyright © 2012-2018 Tencent BlueKing.
 * All Rights Reserved.
 * 蓝鲸智云 版权所有
 */
public class UdfBkdataSumSqlTest extends AbstractTestBase {

	@Test
	public void testLastReturnTypeAndResult() throws Exception {
		StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
		env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime);
		env.setParallelism(1);
		StreamTableEnvironment tableEnv = TableEnvironment.getTableEnvironment(env);
		StreamITCase.clear();

		List<Row> data = new ArrayList<>();
		data.add(Row.of(System.currentTimeMillis(), Integer.MAX_VALUE, new Long("1"), 1.5F, 1.3D));
		data.add(Row.of(System.currentTimeMillis(), null, new Long("2"), 2.5F, 2.3D));
		data.add(Row.of(System.currentTimeMillis(), 5, new Long("3"), 3.5F, 3.3D));

		TypeInformation<?>[] types = {
			BasicTypeInfo.LONG_TYPE_INFO, //eventTime
			BasicTypeInfo.INT_TYPE_INFO, // v_int
			BasicTypeInfo.LONG_TYPE_INFO, // v_long
			BasicTypeInfo.FLOAT_TYPE_INFO, // v_float
			BasicTypeInfo.DOUBLE_TYPE_INFO, // v_double
		};
		String[] names = {"eventTime", "v_int", "v_long", "v_float", "v_double"};

		RowTypeInfo typeInfo = new RowTypeInfo(types, names);

		DataStream<Row> ds = env.fromCollection(data).returns(typeInfo).assignTimestampsAndWatermarks(new AssignerWithPeriodicWatermarks<Row>() {

			private static final long serialVersionUID = -1L;

			private long currentTimestamp = Long.MIN_VALUE;

			@Override
			public long extractTimestamp(Row element, long previousElementTimestamp) {
				this.currentTimestamp = (Long) element.getField(0);
				return (Long) element.getField(0);
			}

			@Override
			public Watermark getCurrentWatermark() {
				return new Watermark(currentTimestamp == Long.MIN_VALUE ? Long.MIN_VALUE : currentTimestamp - 10000);
			}
		});

		Table in = tableEnv.fromDataStream(ds, Arrays.stream(names).collect(Collectors.joining(",")) + ",rowtime.rowtime");
		tableEnv.registerTable("MyTableRow", in);

		String sqlQuery = "SELECT  " +
			"bkdata_sum(v_int) as v_int, " +
			"bkdata_sum(v_long) as v_long, " +
			"bkdata_sum(v_float) as v_float, " +
			"bkdata_sum(v_double) as v_double " +
			" FROM MyTableRow group by TUMBLE(rowtime, INTERVAL '1' DAY)";
		Table result = tableEnv.sqlQuery(sqlQuery);

		Assert.assertEquals(BasicTypeInfo.LONG_TYPE_INFO, result.getSchema().getFieldType("v_int").get());
		Assert.assertEquals(BasicTypeInfo.LONG_TYPE_INFO, result.getSchema().getFieldType("v_long").get());
		Assert.assertEquals(BasicTypeInfo.DOUBLE_TYPE_INFO, result.getSchema().getFieldType("v_float").get());
		Assert.assertEquals(BasicTypeInfo.DOUBLE_TYPE_INFO, result.getSchema().getFieldType("v_double").get());

		DataStream<Row> resultSet = tableEnv.toAppendStream(result, Row.class);
		resultSet.print();
		resultSet.addSink(new StreamITCase.StringSink<Row>());
		env.execute();

		List<String> expected = new ArrayList<>();
		expected.add("2147483652,6,7.5,6.8999999999999995");

		StreamITCase.compareWithList(expected);
	}

}
