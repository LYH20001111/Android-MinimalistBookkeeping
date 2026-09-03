package com.skyanchor.bookkeeping.domain.importexport;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.skyanchor.bookkeeping.data.entity.CategoryEntity;
import com.skyanchor.bookkeeping.data.entity.TransactionExport;
import com.skyanchor.bookkeeping.util.AmountUtil;
import com.skyanchor.bookkeeping.util.DateUtil;

import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * CSV 导出格式化器与「格式契约」的唯一真值来源（V2 新增，开发计划 Phase 5）。
 *
 * <p>本类只依赖纯 Java（{@link AmountUtil} / {@link DateUtil} / {@link Calendar}），不含任何
 * Android 框架调用，因此可在 JVM 单元测试里直接验证金额格式、RFC4180 转义与列顺序。
 *
 * <p>导出列固定为：
 * {@code 交易ID,类型,金额(元),分类,账户,转入账户,日期(yyyy-MM-dd),时间(HH:mm),备注,创建时间,更新时间}。
 * 金额以「元」两位小数输出（无千分位、无货币符号，避免逗号污染字段）；日期本地 {@code yyyy-MM-dd}；
 * 创建 / 更新时间 {@code yyyy-MM-dd HH:mm:ss}。文件以 UTF-8 BOM 起头，行分隔用 CRLF，
 * 含逗号 / 引号 / 换行的字段按 RFC4180 加引号并把内部引号翻倍。
 *
 * <p>类型词表（{@code 支出 / 收入 / 转账}，兼容数字 {@code 1 / 2 / 3}）在此集中定义，
 * 导入侧 {@link CsvParser} 与 {@code ImportRowClassifier} 复用，保证导出→导入可无损往返。
 */
public final class CsvFormatter {

    /** UTF-8 BOM，便于 Excel 正确识别中文编码。 */
    public static final char BOM = '\uFEFF';

    /** 字段分隔符。 */
    public static final String SEPARATOR = ",";

    /** RFC4180 行分隔符（CRLF）。 */
    public static final String LINE_END = "\r\n";

    // 列名（表头），顺序即导出列顺序。
    public static final String COL_ID = "交易ID";
    public static final String COL_TYPE = "类型";
    public static final String COL_AMOUNT = "金额(元)";
    public static final String COL_CATEGORY = "分类";
    public static final String COL_ACCOUNT = "账户";
    public static final String COL_TRANSFER_ACCOUNT = "转入账户";
    public static final String COL_DATE = "日期(yyyy-MM-dd)";
    public static final String COL_TIME = "时间(HH:mm)";
    public static final String COL_NOTE = "备注";
    public static final String COL_CREATED = "创建时间";
    public static final String COL_UPDATED = "更新时间";

    /** 表头列，顺序即导出列顺序。 */
    public static final String[] HEADER = {
            COL_ID, COL_TYPE, COL_AMOUNT, COL_CATEGORY, COL_ACCOUNT, COL_TRANSFER_ACCOUNT,
            COL_DATE, COL_TIME, COL_NOTE, COL_CREATED, COL_UPDATED
    };

    // 类型词表：中文标签是导出用的规范 token，导入同时兼容数字码。
    public static final String TYPE_EXPENSE_LABEL = "支出";
    public static final String TYPE_INCOME_LABEL = "收入";
    public static final String TYPE_TRANSFER_LABEL = "转账";

    private CsvFormatter() {
    }

    /** 交易类型 → 规范中文标签，未知类型回落为空串。 */
    @NonNull
    public static String typeLabel(int type) {
        switch (type) {
            case CategoryEntity.TYPE_EXPENSE:
                return TYPE_EXPENSE_LABEL;
            case CategoryEntity.TYPE_INCOME:
                return TYPE_INCOME_LABEL;
            case CategoryEntity.TYPE_TRANSFER:
                return TYPE_TRANSFER_LABEL;
            default:
                return "";
        }
    }

    /**
     * 文本 → 交易类型常量。兼容中文标签与数字码（{@code 1/2/3}）；无法识别返回 -1。
     */
    public static int typeFromText(@Nullable String text) {
        if (text == null) {
            return -1;
        }
        String value = text.trim();
        if (TYPE_EXPENSE_LABEL.equals(value) || "1".equals(value)) {
            return CategoryEntity.TYPE_EXPENSE;
        }
        if (TYPE_INCOME_LABEL.equals(value) || "2".equals(value)) {
            return CategoryEntity.TYPE_INCOME;
        }
        if (TYPE_TRANSFER_LABEL.equals(value) || "3".equals(value)) {
            return CategoryEntity.TYPE_TRANSFER;
        }
        return -1;
    }

    /** 业务日期（本地当天 00:00 millis）→ {@code yyyy-MM-dd}。 */
    @NonNull
    public static String formatDate(long dayMillis) {
        return String.format(Locale.US, "%04d-%02d-%02d",
                DateUtil.yearOf(dayMillis), DateUtil.monthOf(dayMillis),
                DateUtil.dayOfMonthOf(dayMillis));
    }

    /** 时间戳（millis）→ {@code yyyy-MM-dd HH:mm:ss}；0 视为「无」输出空串。 */
    @NonNull
    public static String formatTimestamp(long millis) {
        if (millis == 0L) {
            return "";
        }
        Calendar calendar = DateUtil.calendar(millis);
        return String.format(Locale.US, "%04d-%02d-%02d %02d:%02d:%02d",
                calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH), calendar.get(Calendar.HOUR_OF_DAY),
                calendar.get(Calendar.MINUTE), calendar.get(Calendar.SECOND));
    }

    /** 金额（分）→ 元两位小数文本，无符号无千分位，便于 CSV 往返。 */
    @NonNull
    public static String amountText(long cents) {
        return AmountUtil.toInputText(cents);
    }

    /**
     * RFC4180 字段转义：含逗号 / 双引号 / CR / LF 时用双引号包裹，内部双引号翻倍；
     * null 输出空串。不含特殊字符的字段原样返回（不加引号），保持文件整洁。
     */
    @NonNull
    public static String escape(@Nullable String field) {
        if (field == null) {
            return "";
        }
        boolean needQuote = field.indexOf(',') >= 0 || field.indexOf('"') >= 0
                || field.indexOf('\n') >= 0 || field.indexOf('\r') >= 0;
        if (!needQuote) {
            return field;
        }
        StringBuilder sb = new StringBuilder(field.length() + 2);
        sb.append('"');
        for (int i = 0; i < field.length(); i++) {
            char c = field.charAt(i);
            if (c == '"') {
                sb.append('"').append('"');
            } else {
                sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /** 表头行（不含 BOM，不含行尾）。 */
    @NonNull
    public static String headerRow() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < HEADER.length; i++) {
            if (i > 0) {
                sb.append(SEPARATOR);
            }
            sb.append(escape(HEADER[i]));
        }
        return sb.toString();
    }

    /** 单条账单 → 数据行（不含行尾）。转账不输出分类，转出 / 转入账户分列。 */
    @NonNull
    public static String dataRow(@NonNull TransactionExport row) {
        boolean transfer = row.type == CategoryEntity.TYPE_TRANSFER;
        StringBuilder sb = new StringBuilder();
        appendField(sb, Long.toString(row.id));
        appendField(sb, typeLabel(row.type));
        appendField(sb, amountText(row.amount));
        appendField(sb, transfer ? "" : row.displayCategoryName());
        appendField(sb, row.displayAccountName());
        appendField(sb, transfer ? row.displayTransferAccountName() : "");
        appendField(sb, formatDate(row.date));
        appendField(sb, row.time);
        appendField(sb, row.displayNote());
        appendField(sb, formatTimestamp(row.createdAt));
        appendField(sb, formatTimestamp(row.updatedAt));
        return sb.toString();
    }

    private static void appendField(@NonNull StringBuilder sb, @Nullable String value) {
        if (sb.length() > 0) {
            sb.append(SEPARATOR);
        }
        sb.append(escape(value));
    }

    /**
     * 全量账单 → 完整 CSV 文本：BOM + 表头 + 每条数据行，行间以 CRLF 分隔，末尾不带多余换行。
     */
    @NonNull
    public static String toCsv(@Nullable List<TransactionExport> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append(BOM).append(headerRow());
        if (rows != null) {
            for (TransactionExport row : rows) {
                sb.append(LINE_END).append(dataRow(row));
            }
        }
        return sb.toString();
    }
}
