package com.skyanchor.bookkeeping.server.common;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 版本协商过滤器的错误响应回归测试：中文错误消息必须以 UTF-8 写出。
 * 修复前 Content-Type 未带 charset，Servlet 默认 ISO-8859-1 把消息写成问号，
 * 客户端会看到「??????? App ???」这类乱码。
 */
class ApiVersionFilterTest {

    @Test
    void versionMismatch_message_isUtf8Encoded() throws Exception {
        ApiVersionFilter filter = new ApiVersionFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/sync/status");
        request.setServletPath("/api/v1/sync/status");
        // 模拟 V3.1 旧客户端：协议版本头还是 1
        request.addHeader("X-Api-Version", "1");
        request.addHeader("X-Sync-Protocol-Version", "1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            throw new IllegalStateException("版本不匹配时不应放行");
        });

        assertEquals(400, response.getStatus());
        assertEquals("UTF-8", response.getCharacterEncoding());
        String body = response.getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains("VERSION_MISMATCH"), body);
        assertTrue(body.contains("版本不匹配"), body);
    }

    @Test
    void matchingHeaders_passThrough() throws Exception {
        ApiVersionFilter filter = new ApiVersionFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/sync/status");
        request.setServletPath("/api/v1/sync/status");
        request.addHeader("X-Api-Version", String.valueOf(ApiVersionFilter.API_VERSION));
        request.addHeader("X-Sync-Protocol-Version",
                String.valueOf(ApiVersionFilter.SYNC_PROTOCOL_VERSION));
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] passed = {false};

        filter.doFilter(request, response, (req, res) -> passed[0] = true);

        assertTrue(passed[0]);
        assertEquals(200, response.getStatus());
    }
}
