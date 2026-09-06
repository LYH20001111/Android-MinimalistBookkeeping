package com.skyanchor.bookkeeping.server.auth;

import com.skyanchor.bookkeeping.server.auth.dto.AuthDtos.AuthResponse;
import com.skyanchor.bookkeeping.server.auth.dto.AuthDtos.DeleteAccountRequest;
import com.skyanchor.bookkeeping.server.auth.dto.AuthDtos.DeviceDto;
import com.skyanchor.bookkeeping.server.auth.dto.AuthDtos.LoginRequest;
import com.skyanchor.bookkeeping.server.auth.dto.AuthDtos.RefreshRequest;
import com.skyanchor.bookkeeping.server.auth.dto.AuthDtos.RefreshResponse;
import com.skyanchor.bookkeeping.server.auth.dto.AuthDtos.RegisterRequest;
import com.skyanchor.bookkeeping.server.auth.dto.AuthDtos.SimpleResponse;
import com.skyanchor.bookkeeping.server.common.ApiException;
import com.skyanchor.bookkeeping.server.common.SyncWriteBarrier;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 认证 / 设备 / 注销 API（API Version 1）。 */
@RestController
@RequestMapping("/api/v1")
public class AuthController {

    private final AuthService authService;
    private final SyncWriteBarrier writeBarrier;

    public AuthController(AuthService authService, SyncWriteBarrier writeBarrier) {
        this.authService = authService;
        this.writeBarrier = writeBarrier;
    }

    @PostMapping("/auth/register")
    public SimpleResponse register(@Valid @RequestBody RegisterRequest request) {
        authService.register(request);
        return new SimpleResponse(true, "验证邮件已发送，请完成邮箱验证");
    }

    /** 邮件中的验证链接落地页（浏览器打开），返回极简 HTML 结果页。 */
    @GetMapping(value = "/auth/verify-email", produces = MediaType.TEXT_HTML_VALUE)
    public String verifyEmail(@RequestParam("token") String token) {
        boolean ok = authService.verifyEmail(token);
        String message = ok
                ? "邮箱验证成功，请回到 App 继续使用云同步。"
                : "验证链接无效或已过期，请在 App 内重新发送验证邮件。";
        return """
                <!DOCTYPE html>
                <html lang="zh-CN"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>极简记账 · 邮箱验证</title></head>
                <body style="font-family:sans-serif;max-width:32rem;margin:4rem auto;padding:0 1rem;">
                <h2>极简记账</h2><p>%s</p></body></html>""".formatted(message);
    }

    @PostMapping("/auth/resend-verification")
    public SimpleResponse resendVerification(@RequestBody LoginRequest request) {
        authService.resendVerification(request.email());
        return new SimpleResponse(true, "验证邮件已重新发送");
    }

    @PostMapping("/auth/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/auth/refresh")
    public RefreshResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request.refreshToken());
    }

    @PostMapping("/auth/logout")
    public SimpleResponse logout() {
        authService.logout(AuthUser.current().userId(), AuthUser.current().deviceRowId());
        return new SimpleResponse(true, "已退出当前设备");
    }

    @PostMapping("/auth/logout-all")
    public SimpleResponse logoutAll() {
        authService.logoutAll(AuthUser.current().userId());
        return new SimpleResponse(true, "已退出全部设备");
    }

    @GetMapping("/devices")
    public List<DeviceDto> devices() {
        return authService.listDevices(AuthUser.current().userId(),
                AuthUser.current().deviceId());
    }

    @PostMapping("/devices/{id}/revoke")
    public SimpleResponse revokeDevice(@PathVariable("id") long deviceId) {
        authService.revokeDevice(AuthUser.current().userId(), deviceId);
        return new SimpleResponse(true, "设备已退出");
    }

    /** 账号注销：客户端已做二次确认，服务端再次校验密码。清库期间与服务器恢复互斥。 */
    @DeleteMapping("/account")
    public SimpleResponse deleteAccount(@Valid @RequestBody DeleteAccountRequest request) {
        writeBarrier.write(() -> {
            authService.deleteAccount(AuthUser.current().userId(), request);
            return null;
        });
        return new SimpleResponse(true, "账号已注销，云端数据已删除");
    }
}
