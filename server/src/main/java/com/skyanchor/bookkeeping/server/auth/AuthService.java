package com.skyanchor.bookkeeping.server.auth;

import com.skyanchor.bookkeeping.server.auth.domain.DeviceEntity;
import com.skyanchor.bookkeeping.server.auth.domain.EmailVerificationTokenEntity;
import com.skyanchor.bookkeeping.server.auth.domain.RefreshTokenEntity;
import com.skyanchor.bookkeeping.server.auth.domain.UserEntity;
import com.skyanchor.bookkeeping.server.auth.dto.AuthDtos.AuthResponse;
import com.skyanchor.bookkeeping.server.auth.dto.AuthDtos.DeleteAccountRequest;
import com.skyanchor.bookkeeping.server.auth.dto.AuthDtos.DeviceDto;
import com.skyanchor.bookkeeping.server.auth.dto.AuthDtos.DeviceInfo;
import com.skyanchor.bookkeeping.server.auth.dto.AuthDtos.LoginRequest;
import com.skyanchor.bookkeeping.server.auth.dto.AuthDtos.RefreshResponse;
import com.skyanchor.bookkeeping.server.auth.dto.AuthDtos.RegisterRequest;
import com.skyanchor.bookkeeping.server.auth.repo.DeviceRepository;
import com.skyanchor.bookkeeping.server.auth.repo.EmailVerificationTokenRepository;
import com.skyanchor.bookkeeping.server.auth.repo.RefreshTokenRepository;
import com.skyanchor.bookkeeping.server.auth.repo.UserRepository;
import com.skyanchor.bookkeeping.server.common.ApiException;
import com.skyanchor.bookkeeping.server.common.HashUtil;
import com.skyanchor.bookkeeping.server.config.AppProperties;
import com.skyanchor.bookkeeping.server.sync.repo.SyncRowRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 认证服务：注册 / 邮箱验证 / 登录 / 刷新 / 退出 / 设备管理 / 注销。
 *
 * <p>安全规则：密码 BCrypt；refresh token 哈希落库 + 旋转；设备吊销即断会话；
 * 注销账号 = 删云端业务数据 + 匿名化邮箱 + 撤销全部设备。
 * 登录失败统一「邮箱或密码错误」，不泄露账号是否存在（基线第 33 章）。
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final EmailVerificationTokenRepository verificationTokenRepository;
    private final DeviceRepository deviceRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final SyncRowRepository syncRowRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final MailService mailService;
    private final AppProperties properties;

    public AuthService(UserRepository userRepository,
                       EmailVerificationTokenRepository verificationTokenRepository,
                       DeviceRepository deviceRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       SyncRowRepository syncRowRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       MailService mailService,
                       AppProperties properties) {
        this.userRepository = userRepository;
        this.verificationTokenRepository = verificationTokenRepository;
        this.deviceRepository = deviceRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.syncRowRepository = syncRowRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.mailService = mailService;
        this.properties = properties;
    }

    // ===== 注册 / 验证 =====

    @Transactional
    public void register(RegisterRequest request) {
        String email = request.email().trim().toLowerCase();
        if (userRepository.findByEmailIgnoreCase(email).isPresent()) {
            throw ApiException.conflict("该邮箱已注册");
        }
        Instant now = Instant.now();
        UserEntity user = new UserEntity();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setEmailVerified(false);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        userRepository.save(user);

        issueVerificationToken(user);
    }

    @Transactional
    public void resendVerification(String email) {
        UserEntity user = requireUserByEmail(email);
        if (user.isEmailVerified()) {
            return;
        }
        issueVerificationToken(user);
    }

    /** 验证邮件链接落地页：单次使用、24h 有效，成功后用户邮箱标记已验证。 */
    @Transactional
    public boolean verifyEmail(String token) {
        EmailVerificationTokenEntity entity = verificationTokenRepository
                .findByTokenHash(HashUtil.sha256Hex(token))
                .orElse(null);
        if (entity == null || entity.getUsedAt() != null
                || entity.getExpiresAt().isBefore(Instant.now())) {
            return false;
        }
        entity.setUsedAt(Instant.now());
        userRepository.findById(entity.getUserId()).ifPresent(user -> {
            user.setEmailVerified(true);
            user.setUpdatedAt(Instant.now());
        });
        return true;
    }

    private void issueVerificationToken(UserEntity user) {
        String token = HashUtil.randomToken();
        Instant now = Instant.now();
        EmailVerificationTokenEntity entity = new EmailVerificationTokenEntity();
        entity.setUserId(user.getId());
        entity.setTokenHash(HashUtil.sha256Hex(token));
        entity.setExpiresAt(now.plus(Duration.ofHours(properties.getVerifyTtlHours())));
        entity.setCreatedAt(now);
        verificationTokenRepository.save(entity);
        mailService.sendVerificationEmail(user.getEmail(), token);
    }

    // ===== 登录 / 刷新 / 退出 =====

    @Transactional
    public AuthResponse login(LoginRequest request) {
        String email = request.email().trim().toLowerCase();
        UserEntity user = userRepository.findByEmailIgnoreCase(email)
                .filter(u -> u.getDeletedAt() == null)
                .orElse(null);
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw ApiException.unauthorized("邮箱或密码错误");
        }

        DeviceEntity device = upsertDevice(user.getId(), request.device());
        String refreshToken = issueRefreshToken(user.getId(), device.getId());
        String accessToken = jwtService.issue(user.getId(), user.getEmail(),
                device.getDeviceId(), device.getId());
        return new AuthResponse(accessToken, refreshToken, user.getEmail(),
                user.isEmailVerified(), toDto(device, true));
    }

    /** Refresh Token 旋转：旧 token 单次有效，成功后发新 token（基线 5.3）。 */
    @Transactional
    public RefreshResponse refresh(String refreshToken) {
        RefreshTokenEntity stored = refreshTokenRepository
                .findByTokenHash(HashUtil.sha256Hex(refreshToken))
                .orElseThrow(() -> ApiException.unauthorized("登录状态已失效，请重新登录"));
        if (stored.getRevokedAt() != null || stored.getExpiresAt().isBefore(Instant.now())) {
            throw ApiException.unauthorized("登录状态已失效，请重新登录");
        }
        UserEntity user = userRepository.findById(stored.getUserId())
                .filter(u -> u.getDeletedAt() == null)
                .orElseThrow(() -> ApiException.unauthorized("登录状态已失效，请重新登录"));

        stored.setRevokedAt(Instant.now());
        refreshTokenRepository.save(stored);

        DeviceEntity device = deviceRepository.findById(stored.getDeviceRowId())
                .orElseThrow(() -> ApiException.unauthorized("登录状态已失效，请重新登录"));
        if (device.getRevokedAt() != null) {
            throw ApiException.unauthorized("该设备已被退出登录");
        }
        device.setLastSeenAt(Instant.now());
        deviceRepository.save(device);

        String newToken = issueRefreshToken(user.getId(), device.getId());
        String accessToken = jwtService.issue(user.getId(), user.getEmail(),
                device.getDeviceId(), device.getId());
        return new RefreshResponse(accessToken, newToken,
                Instant.now().plus(Duration.ofMinutes(properties.getJwtTtlMinutes())).toEpochMilli());
    }

    @Transactional
    public void logout(long userId, long deviceRowId) {
        revokeDeviceTokens(deviceRowId);
    }

    /** 退出全部设备：吊销所有 refresh token + 设备行（云端数据不删除，基线 32.2）。 */
    @Transactional
    public void logoutAll(long userId) {
        List<DeviceEntity> devices = deviceRepository.findAllByUserIdOrderByCreatedAtAsc(userId);
        Instant now = Instant.now();
        for (DeviceEntity device : devices) {
            if (device.getRevokedAt() == null) {
                device.setRevokedAt(now);
                deviceRepository.save(device);
            }
        }
        for (RefreshTokenEntity token : refreshTokenRepository.findAllByUserId(userId)) {
            if (token.getRevokedAt() == null) {
                token.setRevokedAt(now);
                refreshTokenRepository.save(token);
            }
        }
    }

    // ===== 设备管理 =====

    @Transactional
    public List<DeviceDto> listDevices(long userId, String currentDeviceId) {
        List<DeviceDto> result = new ArrayList<>();
        for (DeviceEntity device : deviceRepository.findAllByUserIdOrderByCreatedAtAsc(userId)) {
            result.add(toDto(device, device.getDeviceId().equals(currentDeviceId)));
        }
        return result;
    }

    @Transactional
    public void revokeDevice(long userId, long deviceRowId) {
        DeviceEntity device = deviceRepository.findById(deviceRowId)
                .filter(d -> d.getUserId().equals(userId))
                .orElseThrow(() -> ApiException.badRequest("设备不存在"));
        device.setRevokedAt(Instant.now());
        deviceRepository.save(device);
        revokeDeviceTokens(deviceRowId);
    }

    // ===== 账号注销（基线 32.3：二次确认在客户端，服务端身份复核） =====

    @Transactional
    public void deleteAccount(long userId, DeleteAccountRequest request) {
        UserEntity user = userRepository.findById(userId)
                .filter(u -> u.getDeletedAt() == null)
                .orElseThrow(() -> ApiException.unauthorized("账号不存在"));
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw ApiException.unauthorized("密码不正确");
        }
        // 云端数据删除：全部业务行 + 变更日志 + 冲突日志
        syncRowRepository.deleteAllUserData(userId);
        logoutAll(userId);
        // 匿名化并保留行（释放邮箱唯一键；审计上保留占位）
        user.setEmail("deleted-" + UUID.randomUUID() + "@deleted.local");
        user.setPasswordHash(passwordEncoder.encode(HashUtil.randomToken()));
        user.setDeletedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);
        log.info("账号已注销并匿名化（userId={}）", userId);
    }

    // ===== 内部 =====

    private UserEntity requireUserByEmail(String email) {
        return userRepository.findByEmailIgnoreCase(email.trim().toLowerCase())
                .filter(u -> u.getDeletedAt() == null)
                .orElseThrow(() -> ApiException.badRequest("账号不存在"));
    }

    private DeviceEntity upsertDevice(Long userId, DeviceInfo info) {
        String deviceId = info != null && info.deviceId() != null && !info.deviceId().isBlank()
                ? info.deviceId() : UUID.randomUUID().toString();
        Instant now = Instant.now();
        DeviceEntity device = deviceRepository.findByUserIdAndDeviceId(userId, deviceId)
                .orElseGet(() -> {
                    DeviceEntity created = new DeviceEntity();
                    created.setUserId(userId);
                    created.setDeviceId(deviceId);
                    created.setCreatedAt(now);
                    return created;
                });
        device.setDeviceName(info != null && info.deviceName() != null ? info.deviceName() : "");
        device.setPlatform(info != null && info.platform() != null ? info.platform() : "");
        device.setAppVersion(info != null && info.appVersion() != null ? info.appVersion() : "");
        device.setLastSeenAt(now);
        device.setRevokedAt(null);
        return deviceRepository.save(device);
    }

    private String issueRefreshToken(Long userId, Long deviceRowId) {
        String token = HashUtil.randomToken();
        Instant now = Instant.now();
        RefreshTokenEntity entity = new RefreshTokenEntity();
        entity.setUserId(userId);
        entity.setDeviceRowId(deviceRowId);
        entity.setTokenHash(HashUtil.sha256Hex(token));
        entity.setExpiresAt(now.plus(Duration.ofDays(properties.getRefreshTtlDays())));
        entity.setCreatedAt(now);
        refreshTokenRepository.save(entity);
        return token;
    }

    private void revokeDeviceTokens(long deviceRowId) {
        Instant now = Instant.now();
        for (RefreshTokenEntity token : refreshTokenRepository.findAllByDeviceRowId(deviceRowId)) {
            if (token.getRevokedAt() == null) {
                token.setRevokedAt(now);
                refreshTokenRepository.save(token);
            }
        }
        deviceRepository.findById(deviceRowId).ifPresent(device -> {
            if (device.getRevokedAt() == null) {
                device.setRevokedAt(now);
                deviceRepository.save(device);
            }
        });
    }

    private DeviceDto toDto(DeviceEntity device, boolean current) {
        return new DeviceDto(device.getId(), device.getDeviceId(), device.getDeviceName(),
                device.getPlatform(), device.getAppVersion(),
                device.getLastSeenAt().toEpochMilli(), device.getCreatedAt().toEpochMilli(),
                device.getRevokedAt() != null, current);
    }
}
