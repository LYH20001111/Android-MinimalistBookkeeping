package com.skyanchor.bookkeeping.util;

import androidx.annotation.NonNull;

/**
 * 异步写入完成后的主线程回调。
 *
 * @param <T> 结果类型
 */
public interface Callback<T> {

    void onResult(T result);

    /**
     * V3：失败回调。默认实现回退为 onResult(null)，保持 V1/V2 既有调用点的
     * 「失败即空结果」语义不变；需要区分错误的场景（认证、同步）覆写本方法。
     */
    default void onError(@NonNull Exception e) {
        onResult(null);
    }
}
