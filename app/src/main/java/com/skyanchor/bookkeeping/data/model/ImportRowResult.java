package com.skyanchor.bookkeeping.data.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.skyanchor.bookkeeping.data.entity.TransactionEntity;

/**
 * CSV 导入的单行结果（V2 新增，开发计划 Phase 5）。
 *
 * <p>一行 CSV 经解析与校验后落入三种状态之一：
 * <ul>
 *   <li>{@link Status#VALID}：可导入，携带已解析好的 {@link TransactionEntity}；</li>
 *   <li>{@link Status#DUPLICATE}：疑似重复（同日期+时间+金额+类型+分类+账户+备注），计入「跳过」，
 *       杜绝静默重复写入；</li>
 *   <li>{@link Status#ERROR}：字段非法（金额/类型/日期不可解析、分类或账户不存在、转账双账户缺失或相同），
 *       计入「错误」，绝不写入。</li>
 * </ul>
 *
 * <p>{@link #summary} 是与语言无关的数据渲染串（如「支出 · 餐饮 · ¥35.00 · 2024-05-15」），
 * 由分类器在解析时就地生成，供预览列表直接展示；{@link #reason} 是错误 / 跳过的语义码，
 * 由 UI 层映射为本地化文案，domain 不持有 Android 字符串资源，保证可在 JVM 单测中直接验证。
 */
public final class ImportRowResult {

    /** 行状态。 */
    public enum Status {
        /** 可导入。 */
        VALID,
        /** 疑似重复，跳过。 */
        DUPLICATE,
        /** 字段非法，错误。 */
        ERROR
    }

    /** 错误 / 跳过原因码，UI 层据此映射本地化文案。 */
    public enum Reason {
        /** 无（VALID 行）。 */
        NONE,
        /** 疑似重复。 */
        DUPLICATE,
        /** 列数不足或缺少必填字段。 */
        MALFORMED_ROW,
        /** 类型不是支出 / 收入 / 转账。 */
        TYPE_INVALID,
        /** 金额不可解析或 <= 0。 */
        AMOUNT_INVALID,
        /** 日期不可解析为 yyyy-MM-dd。 */
        DATE_INVALID,
        /** 分类名称在本类型下不存在。 */
        CATEGORY_MISSING,
        /** 账户名称不存在。 */
        ACCOUNT_MISSING,
        /** 转账缺双账户或转出=转入。 */
        TRANSFER_INVALID
    }

    /** CSV 中的行号，1-based，表头为第 1 行。 */
    public final int lineNumber;

    @NonNull
    public final Status status;

    @NonNull
    public final Reason reason;

    /** 已解析的实体；仅 VALID / DUPLICATE 非空，ERROR 行可能为 null。 */
    @Nullable
    public final TransactionEntity entity;

    /** 与语言无关的数据渲染串，供预览列表展示。 */
    @NonNull
    public final String summary;

    private ImportRowResult(int lineNumber, @NonNull Status status, @NonNull Reason reason,
                            @Nullable TransactionEntity entity, @NonNull String summary) {
        this.lineNumber = lineNumber;
        this.status = status;
        this.reason = reason;
        this.entity = entity;
        this.summary = summary;
    }

    @NonNull
    public static ImportRowResult valid(int lineNumber, @NonNull TransactionEntity entity,
                                        @NonNull String summary) {
        return new ImportRowResult(lineNumber, Status.VALID, Reason.NONE, entity, summary);
    }

    @NonNull
    public static ImportRowResult duplicate(int lineNumber, @Nullable TransactionEntity entity,
                                            @NonNull String summary) {
        return new ImportRowResult(lineNumber, Status.DUPLICATE, Reason.DUPLICATE, entity, summary);
    }

    @NonNull
    public static ImportRowResult error(int lineNumber, @NonNull Reason reason,
                                        @NonNull String summary) {
        return new ImportRowResult(lineNumber, Status.ERROR, reason, null, summary);
    }

    public boolean isValid() {
        return status == Status.VALID;
    }
}
