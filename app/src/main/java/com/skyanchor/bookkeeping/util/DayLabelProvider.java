package com.skyanchor.bookkeeping.util;

/**
 * 日期分组标签的来源。
 *
 * <p>{@link StatisticsCalculator} 是纯函数，不持有 Context，因此把「今天/昨天/9月2日」
 * 这类需要 string 资源的文案生成交给实现方注入，既保留了单元测试的可运行性，
 * 也避免在数据层硬编码中文。
 */
public interface DayLabelProvider {

    /**
     * @param dayMillis 当天 00:00 的 epoch millis
     * @return 分组标题文案
     */
    String label(long dayMillis);
}
