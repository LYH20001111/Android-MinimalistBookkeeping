package com.skyanchor.bookkeeping.data.model;

import androidx.annotation.NonNull;

import com.skyanchor.bookkeeping.data.entity.TransactionEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * CSV 导入预览（V2 新增，开发计划 Phase 5）。
 *
 * <p>把整个文件的解析结果分成三桶计数：可导入 {@link #validCount}、疑似重复跳过
 * {@link #duplicateCount}、字段非法错误 {@link #errorCount}，供「用户确认 → 批量插入 → 结果报告」
 * 的流程使用。用户在预览页确认后，只把 {@link #validEntities()} 交给仓库在单事务内批量写入。
 *
 * <p>{@link #headerValid} 为 false 表示表头缺少必填列（类型 / 金额 / 日期），此时 {@link #rows}
 * 为空，UI 直接提示「文件格式不正确」，不进入逐行预览。
 */
public final class ImportPreview {

    /** 表头缺少必填列时的哨兵预览。 */
    public static final ImportPreview INVALID_HEADER =
            new ImportPreview(Collections.<ImportRowResult>emptyList(), false);

    @NonNull
    public final List<ImportRowResult> rows;

    /** 表头是否含全部必填列。 */
    public final boolean headerValid;

    public final int validCount;
    public final int duplicateCount;
    public final int errorCount;

    private ImportPreview(@NonNull List<ImportRowResult> rows, boolean headerValid) {
        this.rows = rows;
        this.headerValid = headerValid;
        int valid = 0;
        int duplicate = 0;
        int error = 0;
        for (ImportRowResult row : rows) {
            switch (row.status) {
                case VALID:
                    valid++;
                    break;
                case DUPLICATE:
                    duplicate++;
                    break;
                case ERROR:
                default:
                    error++;
                    break;
            }
        }
        this.validCount = valid;
        this.duplicateCount = duplicate;
        this.errorCount = error;
    }

    /** 由逐行结果构建预览，表头视为合法（非法表头走 {@link #INVALID_HEADER}）。 */
    @NonNull
    public static ImportPreview of(@NonNull List<ImportRowResult> rows) {
        return new ImportPreview(rows, true);
    }

    public boolean hasValid() {
        return validCount > 0;
    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }

    public int totalCount() {
        return rows.size();
    }

    /** 抽出全部可导入实体，供仓库在单事务内批量插入。 */
    @NonNull
    public List<TransactionEntity> validEntities() {
        List<TransactionEntity> entities = new ArrayList<>(validCount);
        for (ImportRowResult row : rows) {
            if (row.status == ImportRowResult.Status.VALID && row.entity != null) {
                entities.add(row.entity);
            }
        }
        return entities;
    }

    /**
     * 提交用实体（V2.1 基线第 17 章）：全部有效行 + 用户选择「保留」的疑似重复行。
     * 疑似重复默认跳过（{@link ImportRowResult#keep} 默认 false），不能静默写入也不能静默丢弃。
     */
    @NonNull
    public List<TransactionEntity> commitEntities() {
        List<TransactionEntity> entities = new ArrayList<>(validCount);
        for (ImportRowResult row : rows) {
            if (row.entity == null) {
                continue;
            }
            boolean committable = row.status == ImportRowResult.Status.VALID
                    || (row.status == ImportRowResult.Status.DUPLICATE && row.keep);
            if (committable) {
                entities.add(row.entity);
            }
        }
        return entities;
    }

    /** 用户选择「保留」的疑似重复行数。 */
    public int keptDuplicateCount() {
        int kept = 0;
        for (ImportRowResult row : rows) {
            if (row.status == ImportRowResult.Status.DUPLICATE && row.keep) {
                kept++;
            }
        }
        return kept;
    }

    /** 仍然跳过的疑似重复行数。 */
    public int skippedDuplicateCount() {
        return duplicateCount - keptDuplicateCount();
    }

    /** 是否有可提交内容（有效行或保留的疑似重复行）。 */
    public boolean hasCommittable() {
        return validCount > 0 || keptDuplicateCount() > 0;
    }
}
