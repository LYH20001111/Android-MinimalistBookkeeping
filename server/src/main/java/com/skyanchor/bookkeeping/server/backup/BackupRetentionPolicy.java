package com.skyanchor.bookkeeping.server.backup;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 备份保留策略（V3.1 基线第 13 章）：最近 7 天每天一份 + 最近 4 周每周一份
 * + 最近 12 个月每月一份。纯函数实现，便于单测。
 */
public final class BackupRetentionPolicy {

    public static final int KEEP_DAILY = 7;
    public static final int KEEP_WEEKLY = 4;
    public static final int KEEP_MONTHLY = 12;

    private BackupRetentionPolicy() {
    }

    /** 一份备份的极简信息：文件名与创建时间（epoch millis）。 */
    public record BackupRef(String name, long createdAt) {
    }

    /**
     * 计算应当保留的备份集合。同一天/周/月有多份时只保留最新一份参与分桶；
     * 桶内按时间从新到旧保留前 N 个桶。永远保留全局最新一份。
     */
    public static Set<String> computeKeepSet(List<BackupRef> backups) {
        Set<String> keep = new HashSet<>();
        if (backups == null || backups.isEmpty()) {
            return keep;
        }
        List<BackupRef> sorted = new ArrayList<>(backups);
        sorted.sort(Comparator.comparingLong(BackupRef::createdAt).reversed());
        // 全局最新一份无论如何保留
        keep.add(sorted.get(0).name());

        keepNewestPerBucket(keep, sorted, KEEP_DAILY, DAY);
        keepNewestPerBucket(keep, sorted, KEEP_WEEKLY, WEEK);
        keepNewestPerBucket(keep, sorted, KEEP_MONTHLY, MONTH);
        return keep;
    }

    private static final long DAY = 24L * 60L * 60L * 1000L;
    private static final long WEEK = 7L * DAY;
    /** 30 天近似一个月：保留策略只需稳定分桶，不追求日历精度。 */
    private static final long MONTH = 30L * DAY;

    private static void keepNewestPerBucket(Set<String> keep, List<BackupRef> sortedNewestFirst,
                                            int limit, long bucketSize) {
        Map<Long, String> bucketToName = new LinkedHashMap<>();
        for (BackupRef backup : sortedNewestFirst) {
            long bucket = Math.floorDiv(backup.createdAt(), bucketSize);
            bucketToName.putIfAbsent(bucket, backup.name());
            if (bucketToName.size() >= limit) {
                break;
            }
        }
        keep.addAll(bucketToName.values());
    }
}
