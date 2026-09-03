package com.skyanchor.bookkeeping.domain.importexport;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.skyanchor.bookkeeping.util.DateUtil;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * CSV 解析器（V2 新增，开发计划 Phase 5）。纯 Java，无 Android 依赖，可在 JVM 单测直接验证。
 *
 * <p>职责分三层：
 * <ol>
 *   <li>{@link #parse}：RFC4180 文本 → 二维字段表，正确处理引号包裹、引号内逗号 / 换行、
 *       双引号转义（{@code ""}）与 CRLF / LF 行尾，并剥除起头 BOM；</li>
 *   <li>{@link #readColumns}：表头单元格 → 各规范列的下标（按列名前缀匹配，容忍 {@code (元)}
 *       之类的括注与手工微调），缺失列为 -1；</li>
 *   <li>{@link #parseDate} / {@link #parseTime} / {@link #parseTimestamp}：把单元格文本解析回
 *       存储口径（业务日期 millis、{@code HH:mm}、时间戳 millis），非法时返回哨兵值交由上层判定。</li>
 * </ol>
 *
 * <p>类型词表与列名常量复用 {@link CsvFormatter}，保证导出 / 导入是同一份格式契约。
 */
public final class CsvParser {

    /** {@link #parseDate} 失败哨兵。 */
    public static final long INVALID_DATE = -1L;

    /** 业务时间缺省值。 */
    public static final String DEFAULT_TIME = "00:00";

    private CsvParser() {
    }

    /**
     * RFC4180 解析：CSV 文本 → 行列表（每行是字段列表，含表头行）。
     *
     * <p>起头 BOM 会被剥除；结尾的行分隔符不会产生多余空行；引号内的逗号 / 换行按字面保留，
     * 连续两个双引号还原为一个。空文本返回空列表。
     */
    @NonNull
    public static List<List<String>> parse(@Nullable String text) {
        List<List<String>> rows = new ArrayList<>();
        if (text == null) {
            return rows;
        }
        String source = text;
        if (!source.isEmpty() && source.charAt(0) == CsvFormatter.BOM) {
            source = source.substring(1);
        }
        List<String> current = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < n && source.charAt(i + 1) == '"') {
                        field.append('"');
                        i += 2;
                    } else {
                        inQuotes = false;
                        i++;
                    }
                } else {
                    field.append(c);
                    i++;
                }
                continue;
            }
            if (c == '"') {
                inQuotes = true;
                i++;
            } else if (c == ',') {
                current.add(field.toString());
                field.setLength(0);
                i++;
            } else if (c == '\r') {
                if (i + 1 < n && source.charAt(i + 1) == '\n') {
                    i += 2;
                } else {
                    i++;
                }
                current = endRow(rows, current, field);
            } else if (c == '\n') {
                i++;
                current = endRow(rows, current, field);
            } else {
                field.append(c);
                i++;
            }
        }
        // 收尾：仅当仍有未落行的字段时才补一行，避免结尾换行产生幻影空行。
        if (field.length() > 0 || !current.isEmpty()) {
            current.add(field.toString());
            rows.add(current);
        }
        return rows;
    }

    /**
     * 收尾当前行：把最后的字段落入 {@code current}、整行加入 {@code rows}，并返回一个全新的空列表
     * 承接下一行。<b>必须用返回值重新赋值调用方的 {@code current}</b>，否则各行会共享同一个可变
     * 列表，导致字段跨行累加、行尾产生幻影行。
     */
    @NonNull
    private static List<String> endRow(@NonNull List<List<String>> rows,
                                       @NonNull List<String> current,
                                       @NonNull StringBuilder field) {
        current.add(field.toString());
        field.setLength(0);
        rows.add(current);
        return new ArrayList<>();
    }

    /**
     * 表头单元格 → 列下标映射。按列名前缀匹配（先具体后宽泛），未命中的列为 -1。
     */
    @NonNull
    public static Columns readColumns(@Nullable List<String> headerRow) {
        Columns columns = new Columns();
        if (headerRow == null) {
            return columns;
        }
        for (int i = 0; i < headerRow.size(); i++) {
            String raw = headerRow.get(i);
            if (raw == null) {
                continue;
            }
            String cell = raw.trim();
            // 首列可能残留 BOM（parse 已剥除，这里再防御一次）。
            if (!cell.isEmpty() && cell.charAt(0) == CsvFormatter.BOM) {
                cell = cell.substring(1).trim();
            }
            if (cell.isEmpty()) {
                continue;
            }
            if (columns.id < 0 && startsWith(cell, CsvFormatter.COL_ID)) {
                columns.id = i;
            } else if (columns.type < 0 && startsWith(cell, CsvFormatter.COL_TYPE)) {
                columns.type = i;
            } else if (columns.amount < 0 && startsWith(cell, CsvFormatter.COL_AMOUNT)) {
                columns.amount = i;
            } else if (columns.category < 0 && startsWith(cell, CsvFormatter.COL_CATEGORY)) {
                columns.category = i;
            } else if (columns.transferAccount < 0
                    && startsWith(cell, CsvFormatter.COL_TRANSFER_ACCOUNT)) {
                columns.transferAccount = i;
            } else if (columns.account < 0 && startsWith(cell, CsvFormatter.COL_ACCOUNT)) {
                columns.account = i;
            } else if (columns.created < 0 && startsWith(cell, CsvFormatter.COL_CREATED)) {
                columns.created = i;
            } else if (columns.updated < 0 && startsWith(cell, CsvFormatter.COL_UPDATED)) {
                columns.updated = i;
            } else if (columns.date < 0 && startsWith(cell, CsvFormatter.COL_DATE)) {
                columns.date = i;
            } else if (columns.time < 0 && startsWith(cell, CsvFormatter.COL_TIME)) {
                columns.time = i;
            } else if (columns.note < 0 && startsWith(cell, CsvFormatter.COL_NOTE)) {
                columns.note = i;
            }
        }
        return columns;
    }

    /** 列名前缀匹配：把规范列名去掉括注后比较，容忍「金额」「金额(元)」「金额（元）」等写法。 */
    private static boolean startsWith(@NonNull String cell, @NonNull String canonical) {
        if (cell.startsWith(canonical)) {
            return true;
        }
        String base = stripParenthesis(canonical);
        return !base.isEmpty() && cell.startsWith(base);
    }

    /** 去掉列名里第一个半角 / 全角左括号及其后内容，得到基名（如「金额(元)」→「金额」）。 */
    @NonNull
    private static String stripParenthesis(@NonNull String name) {
        int index = name.indexOf('(');
        if (index < 0) {
            index = name.indexOf('（');
        }
        return index < 0 ? name : name.substring(0, index);
    }

    /**
     * 解析业务日期 {@code yyyy-MM-dd}（可含多余后缀，仅取前 10 位）→ 本地当天 00:00 millis。
     * 非法或不可能存在的日期（如 2 月 30 日）返回 {@link #INVALID_DATE}。
     */
    public static long parseDate(@Nullable String text) {
        if (text == null) {
            return INVALID_DATE;
        }
        String value = text.trim();
        if (value.length() < 10) {
            return INVALID_DATE;
        }
        String datePart = value.substring(0, 10);
        String[] parts = datePart.split("-");
        if (parts.length != 3) {
            return INVALID_DATE;
        }
        try {
            int year = Integer.parseInt(parts[0].trim());
            int month = Integer.parseInt(parts[1].trim());
            int day = Integer.parseInt(parts[2].trim());
            if (year < 1000 || month < 1 || month > 12 || day < 1 || day > 31) {
                return INVALID_DATE;
            }
            long millis = DateUtil.dayMillisOf(year, month, day);
            // 往返校验：夹取到合法日期后应能还原出同样的年月日，否则原日期不存在。
            if (DateUtil.yearOf(millis) != year || DateUtil.monthOf(millis) != month
                    || DateUtil.dayOfMonthOf(millis) != day) {
                return INVALID_DATE;
            }
            return millis;
        } catch (NumberFormatException e) {
            return INVALID_DATE;
        }
    }

    /**
     * 解析业务时间 {@code HH:mm} → 规范 {@code HH:mm} 文本。宽松处理：缺失 / 非法 / 越界一律
     * 回落 {@link #DEFAULT_TIME}，不因时间格式打断整行导入。
     */
    @NonNull
    public static String parseTime(@Nullable String text) {
        if (text == null) {
            return DEFAULT_TIME;
        }
        String value = text.trim();
        if (value.isEmpty()) {
            return DEFAULT_TIME;
        }
        String[] parts = value.split(":");
        try {
            int hour = Integer.parseInt(parts[0].trim());
            int minute = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 0;
            if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
                return DEFAULT_TIME;
            }
            return DateUtil.formatHourMinute(hour, minute);
        } catch (NumberFormatException e) {
            return DEFAULT_TIME;
        }
    }

    /**
     * 解析时间戳 {@code yyyy-MM-dd HH:mm:ss}（秒 / 分可缺省）→ millis。
     * 空或日期非法返回 0，交由仓库在插入时回落为当前时间。
     */
    public static long parseTimestamp(@Nullable String text) {
        if (text == null) {
            return 0L;
        }
        String value = text.trim();
        if (value.isEmpty()) {
            return 0L;
        }
        long day = parseDate(value);
        if (day == INVALID_DATE) {
            return 0L;
        }
        int space = value.indexOf(' ');
        if (space < 0) {
            return day;
        }
        String timePart = value.substring(space + 1).trim();
        String[] parts = timePart.split(":");
        try {
            int hour = parts.length > 0 ? Integer.parseInt(parts[0].trim()) : 0;
            int minute = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 0;
            int second = parts.length > 2 ? Integer.parseInt(parts[2].trim()) : 0;
            Calendar calendar = DateUtil.calendar(day);
            calendar.set(Calendar.HOUR_OF_DAY, hour);
            calendar.set(Calendar.MINUTE, minute);
            calendar.set(Calendar.SECOND, second);
            calendar.set(Calendar.MILLISECOND, 0);
            return calendar.getTimeInMillis();
        } catch (NumberFormatException e) {
            return day;
        }
    }

    /** 取某列的单元格文本，下标越界或为 -1 时返回 null。 */
    @Nullable
    public static String cell(@Nullable List<String> cells, int index) {
        if (cells == null || index < 0 || index >= cells.size()) {
            return null;
        }
        return cells.get(index);
    }

    /** 整行是否全为空白（用于跳过空行）。 */
    public static boolean isBlankRow(@Nullable List<String> cells) {
        if (cells == null || cells.isEmpty()) {
            return true;
        }
        for (String cell : cells) {
            if (cell != null && !cell.trim().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 表头列下标映射，缺失列为 -1。{@link #hasRequired()} 校验导入必需的三列（类型 / 金额 / 日期）。
     */
    public static final class Columns {
        public int id = -1;
        public int type = -1;
        public int amount = -1;
        public int category = -1;
        public int account = -1;
        public int transferAccount = -1;
        public int date = -1;
        public int time = -1;
        public int note = -1;
        public int created = -1;
        public int updated = -1;

        /** 类型 / 金额 / 日期是导入的最低要求，缺一即视为文件格式不正确。 */
        public boolean hasRequired() {
            return type >= 0 && amount >= 0 && date >= 0;
        }
    }
}
