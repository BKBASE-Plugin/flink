package org.apache.flink.table.windowing.assigners;

import org.apache.flink.api.common.ExecutionConfig;
import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.windowing.assigners.WindowAssigner;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.streaming.api.windowing.triggers.EventTimeTrigger;
import org.apache.flink.streaming.api.windowing.triggers.Trigger;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Copyright © 2012-2019 Tencent BlueKing.
 * All Rights Reserved.
 * 蓝鲸智云 版权所有
 */
public class AccumulateEventTimeWindows extends WindowAssigner<Object, TimeWindow> {
	private static final long serialVersionUID = 1L;

	private final long size;

	private final long slide;

	/**
	 * 时区偏移.
	 */
	private final long offset;

	protected AccumulateEventTimeWindows(long size, long slide, long offset) {
		if (Math.abs(offset) > Time.hours(12).toMilliseconds() || size <= 0) {
			throw new IllegalArgumentException("AccumulateEventTimeWindows parameters must satisfy |offset| < 12 * 3600 * 1000 and windowSize > 0");
		}

		this.size = size;
		this.slide = slide;
		this.offset = offset;
	}

	@Override
	public Collection<TimeWindow> assignWindows(Object element, long timestamp, WindowAssignerContext context) {
		if (timestamp > Long.MIN_VALUE) {
			List<TimeWindow> windows = new ArrayList<>((int) (size / slide));
			long windowStart = TimeWindow.getWindowStartWithOffset(timestamp, -offset, size);
			for (int i = (int) ((timestamp - windowStart) / slide) + 1; i <= (int) (size / slide); i++) {
				windows.add(new TimeWindow(windowStart, windowStart + i * slide));
			}
			return windows;
		} else {
			throw new RuntimeException("Record has Long.MIN_VALUE timestamp (= no timestamp marker). " +
				"Is the time characteristic set to 'ProcessingTime', or did you forget to call " +
				"'DataStream.assignTimestampsAndWatermarks(...)'?");
		}
	}

	@Override
	public Trigger<Object, TimeWindow> getDefaultTrigger(StreamExecutionEnvironment env) {
		return EventTimeTrigger.create();
	}

	public static AccumulateEventTimeWindows of(Time size, Time slide, Time offset) {
		return new AccumulateEventTimeWindows(size.toMilliseconds(), slide.toMilliseconds(), offset.toMilliseconds());
	}

	@Override
	public String toString() {
		return "AccumulateEventTimeWindows(" + size + ", " + slide + ", " + offset + ")";
	}

	@Override
	public TypeSerializer<TimeWindow> getWindowSerializer(ExecutionConfig executionConfig) {
		return new TimeWindow.Serializer();
	}

	@Override
	public boolean isEventTime() {
		return true;
	}
}
