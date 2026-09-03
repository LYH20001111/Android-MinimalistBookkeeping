package com.skyanchor.bookkeeping.domain.importexport;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.skyanchor.bookkeeping.data.entity.CategoryEntity;
import com.skyanchor.bookkeeping.data.entity.TransactionEntity;
import com.skyanchor.bookkeeping.data.model.ImportPreview;
import com.skyanchor.bookkeeping.data.model.ImportRowResult;
import com.skyanchor.bookkeeping.util.AmountUtil;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * CSV 导入逐行分类器（V2 新增，开发计划 Phase 5）。
 *
 * <p>纯函数 {@link #classify}：CSV 文本 + {@link ImportContext} → {@link ImportPreview}。
 * 不触碰任何 Android / Room 运行时，故 {@code CsvImportParserTest} 可在 JVM 上直接锁定验收契约：
 * 正常行、缺字段、金额非法、日期非法、疑似重复、转账缺账户 / 同账户。
 *
 * <p>校验顺序即错误优先级：类型 → 金额 → 日期 → 分类 / 账户 → 去重。任何一步不过即判为
 * {@link ImportRowResult.Status#ERROR} 并带原因码，绝不写入；仅完全合法的行才参与去重与插入。
 *
 * <p>账户口径：支出 / 收入的账户可空（兼容早于账户体系、{@code account_id=NULL} 的历史账单回流），
 * 但一旦填了名称就必须能解析到既有账户，否则判错；转账则强制两个不同且存在的账户。
 */
public final class ImportRowClassifier {

    private ImportRowClassifier() {
    }

    /**
     * 解析并分类整份 CSV。
     *
     * <p>空文本或缺少必填列（类型 / 金额 / 日期）→ {@link ImportPreview#INVALID_HEADER}；
     * 表头合法但无数据行 → 空预览（{@code headerValid=true}）；其余逐行分类，跳过全空白行。
     */
    @NonNull
    public static ImportPreview classify(@Nullable String csvText, @NonNull ImportContext context) {
        List<List<String>> rows = CsvParser.parse(csvText);
        if (rows.isEmpty()) {
            return ImportPreview.INVALID_HEADER;
        }
        CsvParser.Columns columns = CsvParser.readColumns(rows.get(0));
        if (!columns.hasRequired()) {
            return ImportPreview.INVALID_HEADER;
        }
        List<ImportRowResult> results = new ArrayList<>();
        Set<String> seenInBatch = new HashSet<>();
        for (int r = 1; r < rows.size(); r++) {
            List<String> cells = rows.get(r);
            if (CsvParser.isBlankRow(cells)) {
                continue;
            }
            results.add(classifyRow(r + 1, cells, columns, context, seenInBatch));
        }
        return ImportPreview.of(results);
    }

    @NonNull
    private static ImportRowResult classifyRow(int lineNumber, @NonNull List<String> cells,
                                               @NonNull CsvParser.Columns columns,
                                               @NonNull ImportContext context,
                                               @NonNull Set<String> seenInBatch) {
        String typeText = CsvParser.cell(cells, columns.type);
        String amountText = CsvParser.cell(cells, columns.amount);
        String dateText = CsvParser.cell(cells, columns.date);
        String categoryText = CsvParser.cell(cells, columns.category);
        String accountText = CsvParser.cell(cells, columns.account);
        String transferAccountText = CsvParser.cell(cells, columns.transferAccount);
        String noteText = CsvParser.cell(cells, columns.note);
        String timeText = CsvParser.cell(cells, columns.time);
        String createdText = CsvParser.cell(cells, columns.created);
        String updatedText = CsvParser.cell(cells, columns.updated);

        // 1. 类型
        int type = CsvFormatter.typeFromText(typeText);
        if (type < 0) {
            return ImportRowResult.error(lineNumber, ImportRowResult.Reason.TYPE_INVALID,
                    rawSummary(typeText, categoryText, accountText, amountText, dateText));
        }
        // 2. 金额：可解析且 > 0
        long amount = AmountUtil.parseToCents(amountText);
        if (amount == AmountUtil.INVALID || amount <= 0L) {
            return ImportRowResult.error(lineNumber, ImportRowResult.Reason.AMOUNT_INVALID,
                    rawSummary(typeText, categoryText, accountText, amountText, dateText));
        }
        // 3. 日期：可解析
        long date = CsvParser.parseDate(dateText);
        if (date == CsvParser.INVALID_DATE) {
            return ImportRowResult.error(lineNumber, ImportRowResult.Reason.DATE_INVALID,
                    rawSummary(typeText, categoryText, accountText, amountText, dateText));
        }

        String time = CsvParser.parseTime(timeText);
        String note = normalizeNote(noteText);

        // 4. 分类 / 账户
        Long categoryId = null;
        Long accountId = null;
        Long transferAccountId = null;
        if (type == CategoryEntity.TYPE_TRANSFER) {
            if (isBlank(accountText) || isBlank(transferAccountText)) {
                return ImportRowResult.error(lineNumber, ImportRowResult.Reason.TRANSFER_INVALID,
                        transferSummary(accountText, transferAccountText, amount, date));
            }
            Long from = context.accountId(accountText.trim());
            Long to = context.accountId(transferAccountText.trim());
            if (from == null || to == null) {
                return ImportRowResult.error(lineNumber, ImportRowResult.Reason.ACCOUNT_MISSING,
                        transferSummary(accountText, transferAccountText, amount, date));
            }
            if (from.equals(to)) {
                return ImportRowResult.error(lineNumber, ImportRowResult.Reason.TRANSFER_INVALID,
                        transferSummary(accountText, transferAccountText, amount, date));
            }
            accountId = from;
            transferAccountId = to;
        } else {
            if (isBlank(categoryText)) {
                return ImportRowResult.error(lineNumber, ImportRowResult.Reason.CATEGORY_MISSING,
                        rawSummary(typeText, categoryText, accountText, amountText, dateText));
            }
            Long resolvedCategory = context.categoryId(type, categoryText.trim());
            if (resolvedCategory == null) {
                return ImportRowResult.error(lineNumber, ImportRowResult.Reason.CATEGORY_MISSING,
                        rawSummary(typeText, categoryText, accountText, amountText, dateText));
            }
            categoryId = resolvedCategory;
            // 账户可空（历史账单回流）；一旦填写必须存在。
            if (!isBlank(accountText)) {
                Long resolvedAccount = context.accountId(accountText.trim());
                if (resolvedAccount == null) {
                    return ImportRowResult.error(lineNumber, ImportRowResult.Reason.ACCOUNT_MISSING,
                            rawSummary(typeText, categoryText, accountText, amountText, dateText));
                }
                accountId = resolvedAccount;
            }
        }

        // 5. 组装实体
        TransactionEntity entity = new TransactionEntity();
        entity.type = type;
        entity.amount = amount;
        entity.categoryId = categoryId;
        entity.accountId = accountId;
        entity.transferAccountId = transferAccountId;
        entity.date = date;
        entity.time = time;
        entity.note = note;
        entity.createdAt = CsvParser.parseTimestamp(createdText);
        entity.updatedAt = CsvParser.parseTimestamp(updatedText);

        String summary = displaySummary(type, categoryText, accountText, transferAccountText,
                amount, date);

        // 6. 去重：库中既有 或 本批次已收
        String fingerprint = ImportContext.fingerprint(type, date, time, amount,
                categoryId, accountId, transferAccountId, note);
        if (context.isDuplicate(fingerprint) || !seenInBatch.add(fingerprint)) {
            return ImportRowResult.duplicate(lineNumber, entity, summary);
        }
        return ImportRowResult.valid(lineNumber, entity, summary);
    }

    // ------------------------------------------------------------------
    // 展示串（与语言无关的数据渲染，供预览列表直接使用）
    // ------------------------------------------------------------------

    @NonNull
    private static String displaySummary(int type, @Nullable String categoryText,
                                         @Nullable String accountText,
                                         @Nullable String transferAccountText,
                                         long amount, long date) {
        String who;
        if (type == CategoryEntity.TYPE_TRANSFER) {
            who = trimToEmpty(accountText) + " → " + trimToEmpty(transferAccountText);
        } else {
            who = trimToEmpty(categoryText);
        }
        return CsvFormatter.typeLabel(type) + " · " + who + " · "
                + AmountUtil.format(amount) + " · " + CsvFormatter.formatDate(date);
    }

    @NonNull
    private static String transferSummary(@Nullable String accountText,
                                          @Nullable String transferAccountText,
                                          long amount, long date) {
        return CsvFormatter.TYPE_TRANSFER_LABEL + " · " + trimToEmpty(accountText) + " → "
                + trimToEmpty(transferAccountText) + " · " + AmountUtil.format(amount) + " · "
                + CsvFormatter.formatDate(date);
    }

    /** 错误行的兜底展示：直接用原始单元格文本拼一条，尽量给用户可辨认的线索。 */
    @NonNull
    private static String rawSummary(@Nullable String typeText, @Nullable String categoryText,
                                     @Nullable String accountText, @Nullable String amountText,
                                     @Nullable String dateText) {
        String who = !isBlank(categoryText) ? categoryText : accountText;
        return trimToEmpty(typeText) + " · " + trimToEmpty(who) + " · "
                + trimToEmpty(amountText) + " · " + trimToEmpty(dateText);
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    @Nullable
    private static String normalizeNote(@Nullable String noteText) {
        if (noteText == null) {
            return null;
        }
        String trimmed = noteText.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean isBlank(@Nullable String text) {
        return text == null || text.trim().isEmpty();
    }

    @NonNull
    private static String trimToEmpty(@Nullable String text) {
        return text == null ? "" : text.trim();
    }
}
