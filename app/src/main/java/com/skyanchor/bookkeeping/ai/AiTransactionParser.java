package com.skyanchor.bookkeeping.ai;

import androidx.annotation.NonNull;

import com.skyanchor.bookkeeping.data.entity.CategoryEntity;
import com.skyanchor.bookkeeping.util.Callback;

import java.util.List;

/**
 * 账单文本解析接口（V5 基线第 48 章）。
 *
 * <p>输入是「OCR 文本或用户的一句话描述」+ 当前账本的分类候选，
 * 输出是 {@link TransactionDraft}。实现方负责异步执行并在主线程回调。
 */
public interface AiTransactionParser {

    /**
     * 解析文本为账单草稿。
     *
     * @param text       用户描述或 OCR 全文
     * @param categories 当前账本的全部分类（含支出与收入），用于把识别出的
     *                   分类名对齐到真实 categoryId
     * @param callback   主线程回调；成功给出草稿（金额可能仍缺失），
     *                   失败给 {@link AiException}
     */
    void parse(@NonNull String text,
               @NonNull List<CategoryEntity> categories,
               @NonNull Callback<TransactionDraft> callback);
}
