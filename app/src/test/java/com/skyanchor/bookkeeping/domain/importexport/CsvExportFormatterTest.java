package com.skyanchor.bookkeeping.domain.importexport;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.skyanchor.bookkeeping.data.entity.CategoryEntity;
import com.skyanchor.bookkeeping.data.entity.TransactionExport;
import com.skyanchor.bookkeeping.util.DateUtil;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * CSV 导出格式化契约测试（V2 新增，开发计划 Phase 5e）。
 *
 * <p>{@link CsvFormatter} 是导出 / 导入共用的「格式契约唯一真值」，这里锁定三件最关键的事：
 * <ol>
 *   <li><b>列顺序</b>：表头严格等于计划规定的 11 列，顺序不能漂移；</li>
 *   <li><b>金额格式</b>：以「元」两位小数输出、无千分位逗号（否则逗号会污染 CSV 字段）；</li>
 *   <li><b>RFC4180 转义</b>：含逗号 / 引号 / 换行的字段加引号且内部引号翻倍，纯字段不加引号。</li>
 * </ol>
 * 全部为纯 Java 断言，无需 Android 运行时。
 */
public class CsvExportFormatterTest {

    private static final long MAY_15 = DateUtil.dayMillisOf(2024, 5, 15);
    private static final long MAY_16 = DateUtil.dayMillisOf(2024, 5, 16);
    private static final long JAN_1 = DateUtil.dayMillisOf(2024, 1, 1);

    // ------------------------------------------------------------------
    // 列顺序 / 表头
    // ------------------------------------------------------------------

    @Test
    public void headerArray_matchesCanonicalColumnConstants() {
        assertArrayEquals(new String[]{
                CsvFormatter.COL_ID, CsvFormatter.COL_TYPE, CsvFormatter.COL_AMOUNT,
                CsvFormatter.COL_CATEGORY, CsvFormatter.COL_ACCOUNT, CsvFormatter.COL_TRANSFER_ACCOUNT,
                CsvFormatter.COL_DATE, CsvFormatter.COL_TIME, CsvFormatter.COL_NOTE,
                CsvFormatter.COL_CREATED, CsvFormatter.COL_UPDATED
        }, CsvFormatter.HEADER);
    }

    @Test
    public void headerRow_isExactPlanColumnOrder() {
        // 计划规定列顺序：交易ID,类型,金额(元),分类,账户,转入账户,日期,时间,备注,创建时间,更新时间。
        assertEquals("交易ID,类型,金额(元),分类,账户,转入账户,"
                        + "日期(yyyy-MM-dd),时间(HH:mm),备注,创建时间,更新时间",
                CsvFormatter.headerRow());
    }

    // ------------------------------------------------------------------
    // 金额格式
    // ------------------------------------------------------------------

    @Test
    public void amountText_twoDecimalsNoSymbolNoGrouping() {
        assertEquals("35.80", CsvFormatter.amountText(3580L));
        assertEquals("1.00", CsvFormatter.amountText(100L));
        assertEquals("0.05", CsvFormatter.amountText(5L));
        assertEquals("0.00", CsvFormatter.amountText(0L));
        // 关键：大额也不得出现千分位逗号，否则字段会被逗号切碎。
        assertEquals("1280.00", CsvFormatter.amountText(128000L));
        assertEquals("1000000.00", CsvFormatter.amountText(100000000L));
    }

    // ------------------------------------------------------------------
    // 日期 / 时间戳
    // ------------------------------------------------------------------

    @Test
    public void formatDate_zeroPaddedIso() {
        assertEquals("2024-05-15", CsvFormatter.formatDate(MAY_15));
        assertEquals("2024-01-01", CsvFormatter.formatDate(JAN_1));
    }

    @Test
    public void formatTimestamp_zeroIsEmptyOtherwiseFullDateTime() {
        assertEquals("", CsvFormatter.formatTimestamp(0L));
        assertEquals("2024-05-15 00:00:00", CsvFormatter.formatTimestamp(MAY_15));
    }

    // ------------------------------------------------------------------
    // 类型词表往返
    // ------------------------------------------------------------------

    @Test
    public void typeLabel_mapsConstantsToChinese() {
        assertEquals("支出", CsvFormatter.typeLabel(CategoryEntity.TYPE_EXPENSE));
        assertEquals("收入", CsvFormatter.typeLabel(CategoryEntity.TYPE_INCOME));
        assertEquals("转账", CsvFormatter.typeLabel(CategoryEntity.TYPE_TRANSFER));
        assertEquals("", CsvFormatter.typeLabel(99));
    }

    @Test
    public void typeFromText_acceptsLabelAndNumericCode_rejectsGarbage() {
        assertEquals(CategoryEntity.TYPE_EXPENSE, CsvFormatter.typeFromText("支出"));
        assertEquals(CategoryEntity.TYPE_INCOME, CsvFormatter.typeFromText("收入"));
        assertEquals(CategoryEntity.TYPE_TRANSFER, CsvFormatter.typeFromText("转账"));
        assertEquals(CategoryEntity.TYPE_EXPENSE, CsvFormatter.typeFromText("1"));
        assertEquals(CategoryEntity.TYPE_INCOME, CsvFormatter.typeFromText("2"));
        assertEquals(CategoryEntity.TYPE_TRANSFER, CsvFormatter.typeFromText("3"));
        assertEquals(CategoryEntity.TYPE_EXPENSE, CsvFormatter.typeFromText("  支出  "));
        assertEquals(-1, CsvFormatter.typeFromText("bogus"));
        assertEquals(-1, CsvFormatter.typeFromText(""));
        assertEquals(-1, CsvFormatter.typeFromText(null));
    }

    @Test
    public void typeLabel_andTypeFromText_roundTrip() {
        for (int type : new int[]{CategoryEntity.TYPE_EXPENSE, CategoryEntity.TYPE_INCOME,
                CategoryEntity.TYPE_TRANSFER}) {
            assertEquals(type, CsvFormatter.typeFromText(CsvFormatter.typeLabel(type)));
        }
    }

    // ------------------------------------------------------------------
    // RFC4180 转义
    // ------------------------------------------------------------------

    @Test
    public void escape_nullAndPlainAreUnquoted() {
        assertEquals("", CsvFormatter.escape(null));
        assertEquals("", CsvFormatter.escape(""));
        assertEquals("餐饮", CsvFormatter.escape("餐饮"));
    }

    @Test
    public void escape_wrapsFieldContainingComma() {
        assertEquals("\"打车,加班\"", CsvFormatter.escape("打车,加班"));
    }

    @Test
    public void escape_doublesInternalQuotesAndWraps() {
        assertEquals("\"say \"\"hi\"\"\"", CsvFormatter.escape("say \"hi\""));
    }

    @Test
    public void escape_wrapsFieldContainingNewline() {
        assertEquals("\"line\nbreak\"", CsvFormatter.escape("line\nbreak"));
        assertEquals("\"line\rbreak\"", CsvFormatter.escape("line\rbreak"));
    }

    // ------------------------------------------------------------------
    // 数据行
    // ------------------------------------------------------------------

    @Test
    public void dataRow_expense_putsCategoryAndAccount_transferAccountEmpty() {
        assertEquals("1,支出,35.80,餐饮,现金,,2024-05-15,12:30,午餐,,",
                CsvFormatter.dataRow(expense()));
    }

    @Test
    public void dataRow_transfer_omitsCategory_splitsTwoAccounts() {
        TransactionExport transfer = new TransactionExport();
        transfer.id = 2L;
        transfer.type = CategoryEntity.TYPE_TRANSFER;
        transfer.amount = 10000L;
        transfer.categoryId = 0L;
        transfer.categoryName = null;
        transfer.accountId = 20L;
        transfer.accountName = "现金";
        transfer.transferAccountId = 30L;
        transfer.transferAccountName = "微信";
        transfer.date = MAY_16;
        transfer.time = "09:00";
        transfer.note = null;
        transfer.createdAt = 0L;
        transfer.updatedAt = 0L;

        assertEquals("2,转账,100.00,,现金,微信,2024-05-16,09:00,,,",
                CsvFormatter.dataRow(transfer));
    }

    @Test
    public void dataRow_escapesNoteContainingComma() {
        TransactionExport row = expense();
        row.note = "打车,加班";
        assertEquals("1,支出,35.80,餐饮,现金,,2024-05-15,12:30,\"打车,加班\",,",
                CsvFormatter.dataRow(row));
    }

    // ------------------------------------------------------------------
    // 整篇 CSV
    // ------------------------------------------------------------------

    @Test
    public void toCsv_startsWithBom_headerThenRows_crlfSeparated_noTrailingNewline() {
        List<TransactionExport> rows = new ArrayList<>();
        rows.add(expense());
        String csv = CsvFormatter.toCsv(rows);

        assertEquals(CsvFormatter.BOM, csv.charAt(0));
        assertFalse("末尾不应带多余换行", csv.endsWith(CsvFormatter.LINE_END));

        String[] lines = csv.substring(1).split("\r\n", -1);
        assertEquals(2, lines.length);
        assertEquals(CsvFormatter.headerRow(), lines[0]);
        assertEquals(CsvFormatter.dataRow(expense()), lines[1]);
    }

    @Test
    public void toCsv_emptyOrNull_isBomPlusHeaderOnly() {
        String headerOnly = CsvFormatter.BOM + CsvFormatter.headerRow();
        assertEquals(headerOnly, CsvFormatter.toCsv(null));
        assertEquals(headerOnly, CsvFormatter.toCsv(Collections.<TransactionExport>emptyList()));
    }

    private static TransactionExport expense() {
        TransactionExport row = new TransactionExport();
        row.id = 1L;
        row.type = CategoryEntity.TYPE_EXPENSE;
        row.amount = 3580L;
        row.categoryId = 10L;
        row.categoryName = "餐饮";
        row.accountId = 20L;
        row.accountName = "现金";
        row.transferAccountId = null;
        row.transferAccountName = null;
        row.date = MAY_15;
        row.time = "12:30";
        row.note = "午餐";
        row.createdAt = 0L;
        row.updatedAt = 0L;
        return row;
    }
}
