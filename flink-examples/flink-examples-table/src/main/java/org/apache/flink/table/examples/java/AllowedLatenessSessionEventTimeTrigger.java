package org.apache.flink.table.examples.java;

import org.apache.flink.streaming.api.windowing.triggers.Trigger;
import org.apache.flink.streaming.api.windowing.triggers.TriggerResult;
import org.apache.flink.streaming.api.windowing.windows.Window;
import org.apache.flink.table.runtime.types.CRow;

/**
 * Copyright © 2012-2019 Tencent BlueKing.
 * All Rights Reserved.
 * 蓝鲸智云 版权所有
 */
public class AllowedLatenessSessionEventTimeTrigger extends Trigger<CRow, Window> {

	private static final long serialVersionUID = 1L;

	private AllowedLatenessSessionEventTimeTrigger() {}

	@Override
	public TriggerResult onElement(CRow element, long timestamp, Window window, TriggerContext ctx) throws Exception {
		if (window.maxTimestamp() <= ctx.getCurrentWatermark()) {
			// if the watermark is already past the window fire immediately
			return TriggerResult.FIRE_AND_PURGE;
		} else {
			ctx.registerEventTimeTimer(window.maxTimestamp());
			return TriggerResult.CONTINUE;
		}
	}

	@Override
	public TriggerResult onProcessingTime(long time, Window window, TriggerContext ctx) throws Exception {
		return TriggerResult.CONTINUE;
	}

	@Override
	public TriggerResult onEventTime(long time, Window window, TriggerContext ctx) throws Exception {
		return time == window.maxTimestamp() ?
			TriggerResult.FIRE_AND_PURGE :
			TriggerResult.CONTINUE;
	}

	@Override
	public void clear(Window window, TriggerContext ctx) throws Exception {
		ctx.deleteEventTimeTimer(window.maxTimestamp());
	}

	@Override
	public boolean canMerge() {
		return true;
	}

	@Override
	public void onMerge(Window window,
						OnMergeContext ctx) {
		// only register a timer if the watermark is not yet past the end of the merged window
		// this is in line with the logic in onElement(). If the watermark is past the end of
		// the window onElement() will fire and setting a timer here would fire the window twice.
		long windowMaxTimestamp = window.maxTimestamp();
		if (windowMaxTimestamp > ctx.getCurrentWatermark()) {
			ctx.registerEventTimeTimer(windowMaxTimestamp);
		}
	}

	@Override
	public String toString() {
		return "EventTimeTrigger()";
	}

	/**
	 * Creates an event-time trigger that fires once the watermark passes the end of the window.
	 *
	 * <p>Once the trigger fires all elements are discarded. Elements that arrive late immediately
	 * trigger window evaluation with just this one element.
	 */
	public static AllowedLatenessSessionEventTimeTrigger create() {
		return new AllowedLatenessSessionEventTimeTrigger();
	}
}
