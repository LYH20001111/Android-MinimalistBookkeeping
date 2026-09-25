package com.skyanchor.bookkeeping.ai;

import androidx.annotation.NonNull;

/**
 * 智能记账管线异常。只区分四类用户可感知的失败（V5 基线第 35 章要求
 * 不向用户展示技术错误码），文案翻译交给 UI 层。
 *
 * <p>设计为运行时异常：解析管线各层（解析器 / 仓库）都把「识别不完整」
 * 当常态降级处理，不强制调用方逐层声明。
 */
public class AiException extends RuntimeException {

    public enum Kind {
        /** 网络不可用 / 请求超时 / 服务端错误。 */
        NETWORK,
        /** 云端服务未配置（Base URL 或 API Key 缺失）。 */
        NOT_CONFIGURED,
        /** 拿到了内容但解析不出可用账单（无金额、输出不合法等）。 */
        PARSE_FAILED,
        /** 图片本身无法识别（模糊、损坏、无文字）。 */
        IMAGE_UNREADABLE
    }

    @NonNull
    public final Kind kind;

    public AiException(@NonNull Kind kind, @NonNull String message) {
        super(message);
        this.kind = kind;
    }

    public AiException(@NonNull Kind kind, @NonNull String message, @NonNull Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }
}
