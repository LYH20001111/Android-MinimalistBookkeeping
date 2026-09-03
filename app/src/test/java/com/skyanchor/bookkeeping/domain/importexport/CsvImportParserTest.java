package com.skyanchor.bookkeeping.domain.importexport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.skyanchor.bookkeeping.data.entity.AccountEntity;
import com.skyanchor.bookkeeping.data.entity.CategoryEntity;
import com.skyanchor.bookkeeping.data.entity.TransactionEntity;
import com.skyanchor.bookkeeping.data.entity.TransactionExport;
import com.skyanchor.bookkeeping.data.model.ImportPreview;
import com.skyanchor.bookkeeping.data.model.ImportRowResult;
import com.skyanchor.bookkeeping.util.DateUtil;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

/**
 * CSV 导入解析与逐行校验测试（V2 新增，开发计划 Phase 5e）。
 *
 * <p>分两层锁定验收契约：
 * <ol>
 *   <li>{@link CsvParser}：RFC4180 解析（引号 / 逗号 / 换行 / BOM / 行尾）、表头列映射、
 *       日期严格校验（拒绝 2 月 30 日）、时间与时间戳宽松解析；</li>
 *   <li>{@link ImportRowClassifier#classify} 完整流水线：正常行、缺字段、金额非法、日期非法、
 *       类型非法、分类 / 账户不存在、转账缺账户 / 同账户、库内与文件内去重、空行跳过，
 *       以及「导出→导入」无损往返。</li>
 * </ol>
 * 全部纯 Java，无需 Android 运行时。
 */
public class CsvImportParserTest {

    private static final long MAY_15 = DateUtil.dayMillisOf(2024, 5, 15);
    private static final long MAY_16 = DateUtil.dayMillisOf(2024, 5, 16);

    // ==================================================================
    // CsvParser：文本解析
    // ==================================================================

    @Test
    public void parse_splitsRowsAndFields_stripsBom() {
        List<List<String>> rows = CsvParser.parse("\uFEFFa,b\r\nc,d");
        assertEquals(2, rows.size());
        assertEquals(Arrays.asList("a", "b"), rows.get(0));
        assertEquals(Arrays.asList("c", "d"), rows.get(1));
    }

    @Test
    public void parse_handlesQuotedCommaAndEscapedQuotes() {
        List<List<String>> rows = CsvParser.parse("\"a,b\",\"say \"\"hi\"\"\"");
        assertEquals(1, rows.size());
        assertEquals(Arrays.asList("a,b", "say \"hi\""), rows.get(0));
    }

    @Test
    public void parse_keepsQuotedNewlineInSingleField() {
        List<List<String>> rows = CsvParser.parse("\"line1\nline2\",tail");
        assertEquals(1, rows.size());
        assertEquals(Arrays.asList("line1\nline2", "tail"), rows.get(0));
    }

    @Test
    public void parse_trailingNewlineProducesNoPhantomRow() {
        assertEquals(1, CsvParser.parse("a,b\r\n").size());
        assertEquals(2, CsvParser.parse("a,b\r\nc,d\r\n").size());
    }

    @Test
    public void parse_supportsLfOnly() {
        assertEquals(2, CsvParser.parse("a,b\nc,d").size());
    }

    @Test
    public void parse_emptyOrNull() {
        assertTrue(CsvParser.parse(null).isEmpty());
        assertTrue(CsvParser.parse("").isEmpty());
    }

    // ==================================================================
    // CsvParser：表头列映射
    // ==================================================================

    @Test
    public void readColumns_mapsCanonicalHeader() {
        CsvParser.Columns columns = CsvParser.readColumns(Arrays.asList(CsvFormatter.HEADER));
        assertTrue(columns.hasRequired());
        assertEquals(0, columns.id);
        assertEquals(1, columns.type);
        assertEquals(2, columns.amount);
        assertEquals(3, columns.category);
        assertEquals(4, columns.account);
        assertEquals(5, columns.transferAccount);
        assertEquals(6, columns.date);
        assertEquals(7, columns.time);
        assertEquals(8, columns.note);
        assertEquals(9, columns.created);
        assertEquals(10, columns.updated);
    }

    @Test
    public void readColumns_toleratesManuallyEditedHeader() {
        // 用户把「金额(元)」写成「金额」、「日期(yyyy-MM-dd)」写成「日期」也应识别。
        CsvParser.Columns columns = CsvParser.readColumns(Arrays.asList("类型", "金额", "日期"));
        assertTrue(columns.hasRequired());
        assertEquals(0, columns.type);
        assertEquals(1, columns.amount);
        assertEquals(2, columns.date);
    }

    @Test
    public void readColumns_missingRequiredColumns() {
        CsvParser.Columns columns = CsvParser.readColumns(Arrays.asList("备注", "其他"));
        assertFalse(columns.hasRequired());
        assertEquals(-1, columns.type);
        assertEquals(-1, columns.amount);
        assertEquals(-1, columns.date);
    }

    @Test
    public void readColumns_nullSafe() {
        assertFalse(CsvParser.readColumns(null).hasRequired());
    }

    // ==================================================================
    // CsvParser：日期 / 时间 / 时间戳
    // ==================================================================

    @Test
    public void parseDate_valid() {
        assertEquals(MAY_15, CsvParser.parseDate("2024-05-15"));
    }

    @Test
    public void parseDate_takesLeadingTenChars() {
        assertEquals(MAY_15, CsvParser.parseDate("2024-05-15 12:30:00"));
    }

    @Test
    public void parseDate_rejectsImpossibleAndGarbage() {
        assertEquals(CsvParser.INVALID_DATE, CsvParser.parseDate("2024-02-30"));
        assertEquals(CsvParser.INVALID_DATE, CsvParser.parseDate("2024-13-01"));
        assertEquals(CsvParser.INVALID_DATE, CsvParser.parseDate("not-a-date"));
        assertEquals(CsvParser.INVALID_DATE, CsvParser.parseDate(""));
        assertEquals(CsvParser.INVALID_DATE, CsvParser.parseDate(null));
    }

    @Test
    public void parseTime_isLenient() {
        assertEquals("12:30", CsvParser.parseTime("12:30"));
        assertEquals("09:05", CsvParser.parseTime("9:5"));
        assertEquals(CsvParser.DEFAULT_TIME, CsvParser.parseTime("99:99"));
        assertEquals(CsvParser.DEFAULT_TIME, CsvParser.parseTime("abc"));
        assertEquals(CsvParser.DEFAULT_TIME, CsvParser.parseTime(""));
        assertEquals(CsvParser.DEFAULT_TIME, CsvParser.parseTime(null));
    }

    @Test
    public void parseTimestamp_emptyOrInvalidIsZero() {
        assertEquals(0L, CsvParser.parseTimestamp(""));
        assertEquals(0L, CsvParser.parseTimestamp(null));
        assertEquals(0L, CsvParser.parseTimestamp("abc"));
    }

    @Test
    public void parseTimestamp_dateOnlyIsMidnight() {
        assertEquals(MAY_15, CsvParser.parseTimestamp("2024-05-15"));
    }

    @Test
    public void parseTimestamp_parsesTimeOfDay() {
        long ts = CsvParser.parseTimestamp("2024-05-15 13:45:30");
        Calendar c = DateUtil.calendar(ts);
        assertEquals(2024, c.get(Calendar.YEAR));
        assertEquals(5, c.get(Calendar.MONTH) + 1);
        assertEquals(15, c.get(Calendar.DAY_OF_MONTH));
        assertEquals(13, c.get(Calendar.HOUR_OF_DAY));
        assertEquals(45, c.get(Calendar.MINUTE));
        assertEquals(30, c.get(Calendar.SECOND));
    }

    // ==================================================================
    // CsvParser：小工具
    // ==================================================================

    @Test
    public void isBlankRow_detectsAllWhitespace() {
        assertTrue(CsvParser.isBlankRow(null));
        assertTrue(CsvParser.isBlankRow(Collections.<String>emptyList()));
        assertTrue(CsvParser.isBlankRow(Arrays.asList("", "  ", "\t")));
        assertFalse(CsvParser.isBlankRow(Arrays.asList("", "a")));
    }

    @Test
    public void cell_returnsNullOutOfRange() {
        List<String> cells = Arrays.asList("a", "b");
        assertEquals("a", CsvParser.cell(cells, 0));
        assertNull(CsvParser.cell(cells, -1));
        assertNull(CsvParser.cell(cells, 5));
        assertNull(CsvParser.cell(null, 0));
    }

    // ==================================================================
    // ImportRowClassifier：表头 / 空文件
    // ==================================================================

    @Test
    public void classify_invalidHeaderReturnsSentinel() {
        ImportContext ctx = context();
        assertSame(ImportPreview.INVALID_HEADER, ImportRowClassifier.classify(null, ctx));
        assertSame(ImportPreview.INVALID_HEADER, ImportRowClassifier.classify("", ctx));
        assertSame(ImportPreview.INVALID_HEADER,
                ImportRowClassifier.classify("备注,其他\r\nx,y", ctx));
        assertFalse(ImportRowClassifier.classify("备注,其他", ctx).headerValid);
    }

    @Test
    public void classify_headerOnlyIsEmptyButValidHeader() {
        ImportPreview preview = ImportRowClassifier.classify(CsvFormatter.headerRow(), context());
        assertTrue(preview.headerValid);
        assertTrue(preview.isEmpty());
        assertEquals(0, preview.validCount);
        assertFalse(preview.hasValid());
    }

    // ==================================================================
    // ImportRowClassifier：正常行
    // ==================================================================

    @Test
    public void classify_validExpenseProducesEntity() {
        String csv = csvWithHeader(
                row("支出", "35.80", "餐饮", "现金", "", "2024-05-15", "12:30", "午餐"));
        ImportPreview preview = ImportRowClassifier.classify(csv, context());

        assertEquals(1, preview.validCount);
        assertEquals(0, preview.duplicateCount);
        assertEquals(0, preview.errorCount);

        TransactionEntity entity = preview.validEntities().get(0);
        assertEquals(CategoryEntity.TYPE_EXPENSE, entity.type);
        assertEquals(3580L, entity.amount);
        assertEquals(Long.valueOf(10L), entity.categoryId);
        assertEquals(Long.valueOf(20L), entity.accountId);
        assertNull(entity.transferAccountId);
        assertEquals(MAY_15, entity.date);
        assertEquals("12:30", entity.time);
        assertEquals("午餐", entity.note);
    }

    @Test
    public void classify_expenseAccountIsOptional() {
        // 早于账户体系、account_id 为空的历史账单应可回流。
        String csv = csvWithHeader(
                row("支出", "35.80", "餐饮", "", "", "2024-05-15", "12:30", ""));
        ImportPreview preview = ImportRowClassifier.classify(csv, context());
        assertEquals(1, preview.validCount);
        assertNull(preview.validEntities().get(0).accountId);
    }

    @Test
    public void classify_lineNumberCountsHeaderAsFirstRow() {
        String csv = csvWithHeader(
                row("支出", "35.80", "餐饮", "现金", "", "2024-05-15", "12:30", ""),
                row("支出", "12.00", "餐饮", "现金", "", "2024-05-16", "12:30", ""));
        ImportPreview preview = ImportRowClassifier.classify(csv, context());
        assertEquals(2, preview.rows.get(0).lineNumber);
        assertEquals(3, preview.rows.get(1).lineNumber);
    }

    // ==================================================================
    // ImportRowClassifier：错误优先级
    // ==================================================================

    @Test
    public void classify_typeInvalid() {
        ImportRowResult row = firstRow(
                row("bogus", "35.80", "餐饮", "现金", "", "2024-05-15", "12:30", ""));
        assertEquals(ImportRowResult.Status.ERROR, row.status);
        assertEquals(ImportRowResult.Reason.TYPE_INVALID, row.reason);
        assertNull(row.entity);
    }

    @Test
    public void classify_amountInvalid() {
        assertEquals(ImportRowResult.Reason.AMOUNT_INVALID, reasonOf(
                row("支出", "abc", "餐饮", "现金", "", "2024-05-15", "12:30", "")));
        assertEquals(ImportRowResult.Reason.AMOUNT_INVALID, reasonOf(
                row("支出", "-5", "餐饮", "现金", "", "2024-05-15", "12:30", "")));
        assertEquals(ImportRowResult.Reason.AMOUNT_INVALID, reasonOf(
                row("支出", "0", "餐饮", "现金", "", "2024-05-15", "12:30", "")));
    }

    @Test
    public void classify_dateInvalid() {
        assertEquals(ImportRowResult.Reason.DATE_INVALID, reasonOf(
                row("支出", "35.80", "餐饮", "现金", "", "2024-02-30", "12:30", "")));
        assertEquals(ImportRowResult.Reason.DATE_INVALID, reasonOf(
                row("支出", "35.80", "餐饮", "现金", "", "not-a-date", "12:30", "")));
    }

    @Test
    public void classify_categoryMissing() {
        // 分类名不存在
        assertEquals(ImportRowResult.Reason.CATEGORY_MISSING, reasonOf(
                row("支出", "35.80", "不存在", "现金", "", "2024-05-15", "12:30", "")));
        // 分类为空
        assertEquals(ImportRowResult.Reason.CATEGORY_MISSING, reasonOf(
                row("支出", "35.80", "", "现金", "", "2024-05-15", "12:30", "")));
        // 「工资」是收入分类，用在支出行上按类型隔离，仍判缺失
        assertEquals(ImportRowResult.Reason.CATEGORY_MISSING, reasonOf(
                row("支出", "35.80", "工资", "现金", "", "2024-05-15", "12:30", "")));
    }

    @Test
    public void classify_accountMissing() {
        assertEquals(ImportRowResult.Reason.ACCOUNT_MISSING, reasonOf(
                row("支出", "35.80", "餐饮", "不存在", "", "2024-05-15", "12:30", "")));
    }

    // ==================================================================
    // ImportRowClassifier：转账
    // ==================================================================

    @Test
    public void classify_transferValid() {
        String csv = csvWithHeader(
                row("转账", "100.00", "", "现金", "微信", "2024-05-16", "09:00", ""));
        ImportPreview preview = ImportRowClassifier.classify(csv, context());
        assertEquals(1, preview.validCount);

        TransactionEntity entity = preview.validEntities().get(0);
        assertEquals(CategoryEntity.TYPE_TRANSFER, entity.type);
        assertEquals(Long.valueOf(20L), entity.accountId);
        assertEquals(Long.valueOf(30L), entity.transferAccountId);
        assertNull(entity.categoryId);
        assertEquals(10000L, entity.amount);
    }

    @Test
    public void classify_transferMissingAccount() {
        // 转入账户为空
        assertEquals(ImportRowResult.Reason.TRANSFER_INVALID, reasonOf(
                row("转账", "100.00", "", "现金", "", "2024-05-16", "09:00", "")));
        // 转出账户为空
        assertEquals(ImportRowResult.Reason.TRANSFER_INVALID, reasonOf(
                row("转账", "100.00", "", "", "微信", "2024-05-16", "09:00", "")));
    }

    @Test
    public void classify_transferAccountNotExist() {
        assertEquals(ImportRowResult.Reason.ACCOUNT_MISSING, reasonOf(
                row("转账", "100.00", "", "现金", "不存在", "2024-05-16", "09:00", "")));
    }

    @Test
    public void classify_transferSameAccount() {
        assertEquals(ImportRowResult.Reason.TRANSFER_INVALID, reasonOf(
                row("转账", "100.00", "", "现金", "现金", "2024-05-16", "09:00", "")));
    }

    // ==================================================================
    // ImportRowClassifier：去重
    // ==================================================================

    @Test
    public void classify_duplicateAgainstExistingIsSkipped() {
        TransactionEntity existing = new TransactionEntity();
        existing.type = CategoryEntity.TYPE_EXPENSE;
        existing.amount = 3580L;
        existing.categoryId = 10L;
        existing.accountId = 20L;
        existing.transferAccountId = null;
        existing.date = MAY_15;
        existing.time = "12:30";
        existing.note = "午餐";

        String csv = csvWithHeader(
                row("支出", "35.80", "餐饮", "现金", "", "2024-05-15", "12:30", "午餐"));
        ImportPreview preview = ImportRowClassifier.classify(csv, context(existing));

        assertEquals(0, preview.validCount);
        assertEquals(1, preview.duplicateCount);
        assertFalse(preview.hasValid());
        assertEquals(ImportRowResult.Status.DUPLICATE, preview.rows.get(0).status);
        assertEquals(ImportRowResult.Reason.DUPLICATE, preview.rows.get(0).reason);
        assertTrue(preview.validEntities().isEmpty());
    }

    @Test
    public void classify_duplicateWithinFile_secondRowSkipped() {
        String dup = row("支出", "35.80", "餐饮", "现金", "", "2024-05-15", "12:30", "午餐");
        ImportPreview preview = ImportRowClassifier.classify(csvWithHeader(dup, dup), context());

        assertEquals(1, preview.validCount);
        assertEquals(1, preview.duplicateCount);
        assertEquals(ImportRowResult.Status.VALID, preview.rows.get(0).status);
        assertEquals(ImportRowResult.Status.DUPLICATE, preview.rows.get(1).status);
        assertEquals(1, preview.validEntities().size());
    }

    @Test
    public void classify_blankRowIsSkipped() {
        String csv = csvWithHeader(
                row("支出", "35.80", "餐饮", "现金", "", "2024-05-15", "12:30", ""),
                ",,,,,,,,,,",
                row("支出", "12.00", "餐饮", "现金", "", "2024-05-16", "12:30", ""));
        ImportPreview preview = ImportRowClassifier.classify(csv, context());
        assertEquals(2, preview.totalCount());
        assertEquals(2, preview.validCount);
    }

    // ==================================================================
    // 导出 → 导入 无损往返
    // ==================================================================

    @Test
    public void classify_roundTripFromExportIsLossless() {
        List<TransactionExport> exports = new ArrayList<>();
        exports.add(exportExpense());
        exports.add(exportTransfer());
        String csvText = CsvFormatter.toCsv(exports);

        ImportPreview preview = ImportRowClassifier.classify(csvText, context());
        assertEquals(0, preview.errorCount);
        assertEquals(0, preview.duplicateCount);
        assertEquals(2, preview.validCount);

        TransactionEntity expense = preview.validEntities().get(0);
        assertEquals(CategoryEntity.TYPE_EXPENSE, expense.type);
        assertEquals(3580L, expense.amount);
        assertEquals(Long.valueOf(10L), expense.categoryId);
        assertEquals(Long.valueOf(20L), expense.accountId);
        assertEquals(MAY_15, expense.date);
        // 带逗号的备注经转义后应无损还原。
        assertEquals("午餐, 加班", expense.note);

        TransactionEntity transfer = preview.validEntities().get(1);
        assertEquals(CategoryEntity.TYPE_TRANSFER, transfer.type);
        assertEquals(Long.valueOf(20L), transfer.accountId);
        assertEquals(Long.valueOf(30L), transfer.transferAccountId);
        assertNull(transfer.categoryId);
    }

    // ==================================================================
    // 测试脚手架
    // ==================================================================

    /** 单行分类，返回第一行结果（表头合法、行非空，必定走到 classifyRow）。 */
    private static ImportRowResult firstRow(String dataRow) {
        ImportPreview preview = ImportRowClassifier.classify(csvWithHeader(dataRow), context());
        return preview.rows.get(0);
    }

    private static ImportRowResult.Reason reasonOf(String dataRow) {
        return firstRow(dataRow).reason;
    }

    /** 用规范表头拼一份 CSV，行间 CRLF。 */
    private static String csvWithHeader(String... dataRows) {
        StringBuilder sb = new StringBuilder(CsvFormatter.headerRow());
        for (String row : dataRows) {
            sb.append("\r\n").append(row);
        }
        return sb.toString();
    }

    /** 按规范 11 列顺序拼一条数据行（id 固定 0、创建 / 更新时间留空）。 */
    private static String row(String type, String amount, String category, String account,
                              String transferAccount, String date, String time, String note) {
        return String.join(",", "0", type, amount, category, account, transferAccount,
                date, time, note, "", "");
    }

    /** 分类：餐饮(支出,10) / 工资(收入,11)；账户：现金(20) / 微信(30)；existing 为库中既有交易。 */
    private static ImportContext context(TransactionEntity... existing) {
        List<CategoryEntity> categories = new ArrayList<>();
        categories.add(category(10L, "餐饮", CategoryEntity.TYPE_EXPENSE));
        categories.add(category(11L, "工资", CategoryEntity.TYPE_INCOME));
        List<AccountEntity> accounts = new ArrayList<>();
        accounts.add(account(20L, "现金"));
        accounts.add(account(30L, "微信"));
        return new ImportContext(categories, accounts, Arrays.asList(existing));
    }

    private static CategoryEntity category(long id, String name, int type) {
        CategoryEntity entity = new CategoryEntity();
        entity.id = id;
        entity.name = name;
        entity.type = type;
        return entity;
    }

    private static AccountEntity account(long id, String name) {
        AccountEntity entity = new AccountEntity();
        entity.id = id;
        entity.name = name;
        return entity;
    }

    private static TransactionExport exportExpense() {
        TransactionExport e = new TransactionExport();
        e.id = 1L;
        e.type = CategoryEntity.TYPE_EXPENSE;
        e.amount = 3580L;
        e.categoryId = 10L;
        e.categoryName = "餐饮";
        e.accountId = 20L;
        e.accountName = "现金";
        e.transferAccountId = null;
        e.transferAccountName = null;
        e.date = MAY_15;
        e.time = "12:30";
        e.note = "午餐, 加班";
        e.createdAt = MAY_15;
        e.updatedAt = MAY_15;
        return e;
    }

    private static TransactionExport exportTransfer() {
        TransactionExport e = new TransactionExport();
        e.id = 2L;
        e.type = CategoryEntity.TYPE_TRANSFER;
        e.amount = 10000L;
        e.categoryId = 0L;
        e.categoryName = null;
        e.accountId = 20L;
        e.accountName = "现金";
        e.transferAccountId = 30L;
        e.transferAccountName = "微信";
        e.date = MAY_16;
        e.time = "09:00";
        e.note = null;
        e.createdAt = 0L;
        e.updatedAt = 0L;
        return e;
    }
}
