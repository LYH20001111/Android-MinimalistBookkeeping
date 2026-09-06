package com.skyanchor.bookkeeping.server.sync;

import com.skyanchor.bookkeeping.server.auth.AuthUser;
import com.skyanchor.bookkeeping.server.auth.domain.UserEntity;
import com.skyanchor.bookkeeping.server.auth.repo.UserRepository;
import com.skyanchor.bookkeeping.server.common.ApiException;
import com.skyanchor.bookkeeping.server.common.SyncWriteBarrier;
import com.skyanchor.bookkeeping.server.sync.dto.SyncDtos.BootstrapSummaryResponse;
import com.skyanchor.bookkeeping.server.sync.dto.SyncDtos.ConflictsResponse;
import com.skyanchor.bookkeeping.server.sync.dto.SyncDtos.PullRequest;
import com.skyanchor.bookkeeping.server.sync.dto.SyncDtos.PullResponse;
import com.skyanchor.bookkeeping.server.sync.dto.SyncDtos.PushRequest;
import com.skyanchor.bookkeeping.server.sync.dto.SyncDtos.PushResponse;
import com.skyanchor.bookkeeping.server.sync.dto.SyncDtos.StatusResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 同步 API（基线第 29 章）。所有接口要求：已登录 + 邮箱已验证。
 * 邮箱未验证不影响登录与本地记账，仅关闭云端同步能力（基线第 24 章）。
 * 写屏障在控制器层获取（事务边界之外）：Push/Pull 与服务器恢复互斥。
 */
@RestController
@RequestMapping("/api/v1/sync")
public class SyncController {

    private final SyncService syncService;
    private final UserRepository userRepository;
    private final SyncWriteBarrier writeBarrier;

    public SyncController(SyncService syncService, UserRepository userRepository,
                          SyncWriteBarrier writeBarrier) {
        this.syncService = syncService;
        this.userRepository = userRepository;
        this.writeBarrier = writeBarrier;
    }

    @PostMapping("/changes/push")
    public PushResponse push(@Valid @RequestBody PushRequest request) {
        return writeBarrier.read(() -> syncService.push(requireVerified(), request));
    }

    @PostMapping("/changes/pull")
    public PullResponse pull(@Valid @RequestBody PullRequest request) {
        return writeBarrier.read(() -> syncService.pull(requireVerified(), request));
    }

    @GetMapping("/bootstrap/summary")
    public BootstrapSummaryResponse bootstrapSummary() {
        return writeBarrier.read(() -> syncService.bootstrapSummary(requireVerified()));
    }

    @GetMapping("/status")
    public StatusResponse status() {
        return syncService.status(requireVerified());
    }

    /** 冲突历史：最近 N 条冲突审计摘要（limit 1~100，默认 50）。 */
    @GetMapping("/conflicts")
    public ConflictsResponse conflicts(@RequestParam(name = "limit", defaultValue = "50") int limit) {
        return syncService.conflicts(AuthUser.current(), limit);
    }

    /** 云端同步要求邮箱已验证（基线 5.1：验证后才开通正常云同步能力）。 */
    private AuthUser requireVerified() {
        AuthUser user = AuthUser.current();
        UserEntity entity = userRepository.findById(user.userId())
                .filter(u -> u.getDeletedAt() == null)
                .orElseThrow(() -> ApiException.unauthorized("账号不存在"));
        if (!entity.isEmailVerified()) {
            throw ApiException.forbidden("EMAIL_NOT_VERIFIED", "请先完成邮箱验证");
        }
        return user;
    }
}
