package com.skyanchor.bookkeeping.server.backup;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 备份保留策略纯函数测试（V3.1 基线第 13 章：7 日 / 4 周 / 12 月）。 */
class BackupRetentionPolicyTest {

    private static final long DAY = 24L * 60 * 60 * 1000L;
    /** 锚定在“第 13 天 + 3 小时”，距离日 / 周 / 月桶边界都有足够余量，保证分桶确定。 */
    private static final long NOW = 13L * DAY + 3L * 60 * 60 * 1000L;

    private static BackupRetentionPolicy.BackupRef ref(String name, long createdAt) {
        return new BackupRetentionPolicy.BackupRef(name, createdAt);
    }

    @Test
    void empty_input_keeps_nothing() {
        assertTrue(BackupRetentionPolicy.computeKeepSet(List.of()).isEmpty());
    }

    @Test
    void newest_backup_is_always_kept() {
        Set<String> keep = BackupRetentionPolicy.computeKeepSet(List.of(
                ref("a.json", NOW), ref("b.json", NOW + 1000L)));
        // 两份在同一日桶内，日桶只保留最新一份
        assertEquals(Set.of("b.json"), keep);
    }

    @Test
    void keeps_seven_daily_plus_weekly_representatives() {
        var backups = new java.util.ArrayList<BackupRetentionPolicy.BackupRef>();
        for (int i = 0; i < 10; i++) {
            backups.add(ref("daily-" + i + ".json", NOW - i * DAY));
        }
        Set<String> keep = BackupRetentionPolicy.computeKeepSet(backups);
        // 最近 7 个日桶保留；第 8 天（i=7）作为上一周的周代表继续保留；
        // i=8、i=9 与 i=7 同属一个周桶且不是该桶最新 → 清理
        for (int i = 0; i <= 7; i++) {
            assertTrue(keep.contains("daily-" + i + ".json"), "应保留 daily-" + i);
        }
        assertFalse(keep.contains("daily-8.json"));
        assertFalse(keep.contains("daily-9.json"));
        assertEquals(8, keep.size());
    }

    @Test
    void same_day_multiple_backups_keep_only_latest() {
        long dayStart = 100L * DAY;
        Set<String> keep = BackupRetentionPolicy.computeKeepSet(List.of(
                ref("morning.json", dayStart),
                ref("noon.json", dayStart + 5L * 60 * 60 * 1000L),
                ref("night.json", dayStart + 20L * 60 * 60 * 1000L)));
        assertEquals(Set.of("night.json"), keep);
        assertFalse(keep.contains("morning.json"));
        assertFalse(keep.contains("noon.json"));
    }

    @Test
    void old_months_are_kept_one_per_month_up_to_limit() {
        var backups = new java.util.ArrayList<BackupRetentionPolicy.BackupRef>();
        // 13 个月，每月一份 → 月桶 13 个 > 12，最老的一个应被清理
        for (int i = 0; i < 13; i++) {
            backups.add(ref("month-" + i + ".json", NOW - i * 30L * DAY));
        }
        Set<String> keep = BackupRetentionPolicy.computeKeepSet(backups);
        assertEquals(12, keep.size());
        assertTrue(keep.contains("month-0.json"));
        assertFalse(keep.contains("month-12.json"));
    }
}
