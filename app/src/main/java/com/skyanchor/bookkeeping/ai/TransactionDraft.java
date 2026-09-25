package com.skyanchor.bookkeeping.ai;

import androidx.annotation.Nullable;

import com.skyanchor.bookkeeping.data.entity.CategoryEntity;

/**
 * 智能记账统一账单草稿（V5 基线第 22、23 章）。
 *
 * <p>扫描账单（OCR）与 AI 文本记账两条管线最终都产出本对象，经
 * {@link DraftValidator} 归一化后交给确认页，用户确认才写入正式账单。
 * 置信度与来源只存在于草稿中，不落库（V5.0 不修改正式账单表）。
 */
public class TransactionDraft {

    /** 来源：用户手动填写（基线里仅作占位，智能管线不会产生）。 */
    public static final String SOURCE_MANUAL = "MANUAL";
    /** 来源：扫描账单 OCR。 */
    public static final String SOURCE_OCR = "OCR";
    /** 来源：AI 文本记账。 */
    public static final String SOURCE_AI = "AI";

    /** 交易类型，取值同 {@link CategoryEntity#TYPE_EXPENSE} / TYPE_INCOME；默认支出。 */
    public int type = CategoryEntity.TYPE_EXPENSE;

    /** 金额，单位：分；0 表示未识别到（交给用户填写，禁止臆造）。 */
    public long amount;

    /** 匹配到的分类 id；0 表示未匹配，由确认页落到默认分类。 */
    public long categoryId;

    /** 业务日期（当天 00:00 的 epoch millis）；0 表示未给出，由校验层补当天。 */
    public long date;

    /** 「HH:mm」；null 表示未给出，由校验层补当前时间。 */
    @Nullable
    public String time;

    /** 备注。 */
    @Nullable
    public String note;

    /** 识别出的商家（折入备注保存，见确认页）。 */
    @Nullable
    public String merchant;

    /** 识别来源：{@link #SOURCE_OCR} / {@link #SOURCE_AI}。 */
    public String source = SOURCE_AI;

    /**
     * 整体置信度 0~1000（基线第 23 章）。仅用于确认页顶部提示分档，
     * 不向用户展示数字。
     */
    public int confidence = CONFIDENCE_LOW;

    /** 金额字段置信度 0~1000。 */
    public int amountConfidence = CONFIDENCE_LOW;

    /** 分类字段置信度 0~1000。 */
    public int categoryConfidence = CONFIDENCE_LOW;

    /** 高置信度：正常展示。 */
    public static final int CONFIDENCE_HIGH = 950;
    /** 中置信度：字段旁轻提示「请确认」。 */
    public static final int CONFIDENCE_MEDIUM = 700;
    /** 低置信度：提示「未能准确识别，请手动填写」。 */
    public static final int CONFIDENCE_LOW = 450;

    /** 金额是否已识别（确认页据此决定金额输入框是否亮「请填写」警示）。 */
    public boolean hasAmount() {
        return amount > 0L;
    }
}
