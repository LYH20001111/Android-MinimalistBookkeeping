package com.skyanchor.bookkeeping.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.skyanchor.bookkeeping.data.remote.ApiException;

import org.junit.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

/**
 * 连接错误文案映射单测（V3.1 基线第 9 章）：底层异常不外露，转成用户可理解文案。
 */
public class ConnectionErrorMapperTest {

    @Test
    public void networkExceptions_mapToFriendlySummary() {
        // 无法解析主机 → 地址问题
        String dns = ConnectionErrorMapper.summarize(new UnknownHostException("api"));
        assertTrue(dns.contains("地址"));

        // 连接被拒 → 服务器未运行
        String refused = ConnectionErrorMapper.summarize(
                new java.net.ConnectException("Connection refused"));
        assertTrue(refused.contains("没有运行"));

        // 超时 → 超时提示
        String timeout = ConnectionErrorMapper.summarize(
                new SocketTimeoutException("timeout"));
        assertTrue(timeout.contains("超时"));

        // 一般 IO → 网络检查提示
        String io = ConnectionErrorMapper.summarize(new IOException("socket closed"));
        assertTrue(io.contains("网络"));
    }

    @Test
    public void unreachableReasons_listAllFourCauses() {
        String reasons = ConnectionErrorMapper.unreachableReasons();
        assertTrue(reasons.contains("服务器"));
        assertTrue(reasons.contains("地址"));
        assertTrue(reasons.contains("同一网络"));
        assertTrue(reasons.contains("防火墙"));
    }

    @Test
    public void isUnreachable_coversNetworkAndApiErrors() {
        assertTrue(ConnectionErrorMapper.isUnreachable(new IOException("offline")));
        // 业务类错误码（如鉴权失效）不属于“服务器不可达”
        assertFalse(ConnectionErrorMapper.isUnreachable(
                new ApiException(ApiException.AUTH_REQUIRED, 401, "expired")));
        assertFalse(ConnectionErrorMapper.isUnreachable(null));
    }

    @Test
    public void isSecure_onlyForHttps() {
        assertTrue(ConnectionErrorMapper.isSecure("https://home.example.com"));
        assertFalse(ConnectionErrorMapper.isSecure("http://192.168.0.2:8080"));
        assertFalse(ConnectionErrorMapper.isSecure(null));
    }

    @Test
    public void humanBytes_formatsSizes() {
        assertEquals("0 B", ConnectionErrorMapper.humanBytes(0));
        assertEquals("512 B", ConnectionErrorMapper.humanBytes(512));
        assertEquals("1.0 KB", ConnectionErrorMapper.humanBytes(1024));
        assertEquals("1.5 GB", ConnectionErrorMapper.humanBytes(1610612736L));
    }
}
