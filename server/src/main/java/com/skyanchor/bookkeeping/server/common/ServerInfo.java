package com.skyanchor.bookkeeping.server.common;

/**
 * 运行时服务器版本信息（基线第 8/35 章：客户端可感知，但 UI 不依赖具体版本号
 * 做业务判断，兼容性仍由 API Version / Sync Protocol Version 控制）。
 * 与 server/build.gradle.kts 的 version 保持一致。
 */
public final class ServerInfo {

    public static final String SERVER_VERSION = "3.2.0";

    private ServerInfo() {
    }
}
