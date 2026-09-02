package com.skyanchor.bookkeeping.util;

/**
 * 异步写入完成后的主线程回调。
 *
 * @param <T> 结果类型
 */
public interface Callback<T> {

    void onResult(T result);
}
