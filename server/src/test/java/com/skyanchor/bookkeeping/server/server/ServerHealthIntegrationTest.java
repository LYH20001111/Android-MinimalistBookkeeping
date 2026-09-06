package com.skyanchor.bookkeeping.server.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 服务器健康检查与管理页可达性（V3.1 基线第 8/10 章，目标 A：服务器可用）：
 * 健康端点公开、字段完整；同步接口仍然要求登录；管理页静态资源可匿名访问。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ServerHealthIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void health_is_public_and_complete() throws Exception {
        mvc.perform(get("/api/v1/server/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.serverVersion").value("3.2.0"))
                .andExpect(jsonPath("$.apiVersion").value(2))
                .andExpect(jsonPath("$.syncProtocolVersion").value(2))
                .andExpect(jsonPath("$.database").value("UP"))
                .andExpect(jsonPath("$.storage.status").exists())
                .andExpect(jsonPath("$.recoveryEpoch").isNumber())
                .andExpect(jsonPath("$.serverTime").isNumber());
    }

    @Test
    void health_without_version_headers_still_ok() throws Exception {
        // /server/health 面向浏览器与 curl，豁免 X-Api-Version 头（与同步协议头要求区分）
        mvc.perform(get("/api/v1/server/health").header("X-Api-Version", "9"))
                .andExpect(status().isOk());
    }

    @Test
    void sync_status_requires_auth() throws Exception {
        mvc.perform(get("/api/v1/sync/status"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void admin_page_is_served_without_auth() throws Exception {
        mvc.perform(get("/admin"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(get("/admin/index.html"))
                .andExpect(status().isOk());
    }
}
