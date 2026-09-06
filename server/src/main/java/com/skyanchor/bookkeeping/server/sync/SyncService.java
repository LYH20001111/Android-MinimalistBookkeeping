package com.skyanchor.bookkeeping.server.sync;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skyanchor.bookkeeping.server.auth.AuthUser;
import com.skyanchor.bookkeeping.server.auth.repo.UserRepository;
import com.skyanchor.bookkeeping.server.common.ApiException;
import com.skyanchor.bookkeeping.server.common.HashUtil;
import com.skyanchor.bookkeeping.server.common.ServerInfo;
import com.skyanchor.bookkeeping.server.common.ServerMeta;
import com.skyanchor.bookkeeping.server.common.ServerMetaRepository;
import com.skyanchor.bookkeeping.server.ledger.domain.LedgerMemberRow;
import com.skyanchor.bookkeeping.server.ledger.domain.LedgerRow;
import com.skyanchor.bookkeeping.server.ledger.repo.LedgerMemberRowRepository;
import com.skyanchor.bookkeeping.server.ledger.repo.LedgerRowRepository;
import com.skyanchor.bookkeeping.server.sync.domain.AccountRow;
import com.skyanchor.bookkeeping.server.sync.domain.BudgetRow;
import com.skyanchor.bookkeeping.server.sync.domain.CategoryRow;
import com.skyanchor.bookkeeping.server.sync.domain.ConflictLogRow;
import com.skyanchor.bookkeeping.server.sync.domain.RecurringRow;
import com.skyanchor.bookkeeping.server.sync.domain.SyncChangeRow;
import com.skyanchor.bookkeeping.server.sync.domain.SyncRow;
import com.skyanchor.bookkeeping.server.sync.domain.TransactionRow;
import com.skyanchor.bookkeeping.server.sync.dto.SyncDtos;
import com.skyanchor.bookkeeping.server.sync.dto.SyncDtos.BootstrapSummaryResponse;
import com.skyanchor.bookkeeping.server.sync.dto.SyncDtos.ChangeItem;
import com.skyanchor.bookkeeping.server.sync.dto.SyncDtos.ConflictItem;
import com.skyanchor.bookkeeping.server.sync.dto.SyncDtos.ConflictsResponse;
import com.skyanchor.bookkeeping.server.sync.dto.SyncDtos.Counts;
import com.skyanchor.bookkeeping.server.sync.dto.SyncDtos.LedgerMembershipSummary;
import com.skyanchor.bookkeeping.server.sync.dto.SyncDtos.PullRequest;
import com.skyanchor.bookkeeping.server.sync.dto.SyncDtos.PullResponse;
import com.skyanchor.bookkeeping.server.sync.dto.SyncDtos.PushItem;
import com.skyanchor.bookkeeping.server.sync.dto.SyncDtos.PushRequest;
import com.skyanchor.bookkeeping.server.sync.dto.SyncDtos.PushResponse;
import com.skyanchor.bookkeeping.server.sync.dto.SyncDtos.PushResultItem;
import com.skyanchor.bookkeeping.server.sync.dto.SyncDtos.StatusResponse;
import com.skyanchor.bookkeeping.server.sync.repo.AccountRowRepository;
import com.skyanchor.bookkeeping.server.sync.repo.BudgetRowRepository;
import com.skyanchor.bookkeeping.server.sync.repo.CategoryRowRepository;
import com.skyanchor.bookkeeping.server.sync.repo.ConflictLogRepository;
import com.skyanchor.bookkeeping.server.sync.repo.RecurringRowRepository;
import com.skyanchor.bookkeeping.server.sync.repo.SyncChangeRepository;
import com.skyanchor.bookkeeping.server.sync.repo.SyncRowRepository;
import com.skyanchor.bookkeeping.server.sync.repo.TransactionRowRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * 双向增量同步服务（基线第 22 章；V3.2 升级为账本级隔离，基线第 10 章）。
 *
 * <p>Push：每个变更显式携带 ledgerId（账本 syncId），服务端依次校验
 * token → 用户可用 → 账本存在且未删 → 用户是成员 → 角色允许该操作，
 * 再做 baseVersion 乐观并发控制 → 无冲突直接 version+1；有冲突走 LWW 裁决
 * （接收序，见 {@link Lww}）并写 conflict log。每个实体写一条 sync_changes。
 * 处理顺序固定为 账本 → 分类 → 账户 → 交易/预算/周期，降低悬挂引用概率。
 * <p>Pull：请求必须携带 ledgerId，只返回该账本的变更（服务端隔离，禁客户端自滤）；
 * 同一账本的所有成员共享变更流，游标 = 账号 + 账本 + changeId。
 * <p>LEDGER 实体：创建 = 发起人自封 OWNER 并初始化一次默认分类/账户；
 * isDefault=true 且用户已有默认账本 → mergedInto 合并（V3.1 → V3.2 升级链路）；
 * 改名/归档需 ADMIN+，删除仅 OWNER。
 */
@Service
public class SyncService {

    private static final Logger log = LoggerFactory.getLogger(SyncService.class);

    private static final int MAX_PULL_LIMIT = 500;
    private static final String ERR_MISSING_REFERENCE = "MISSING_REFERENCE";
    private static final String ERR_VALIDATION = "VALIDATION_ERROR";
    private static final String ERR_LEDGER_NOT_FOUND = "LEDGER_NOT_FOUND";
    private static final String ERR_LEDGER_ACCESS_DENIED = "LEDGER_ACCESS_DENIED";
    private static final String ERR_LEDGER_ROLE_REQUIRED = "LEDGER_ROLE_REQUIRED";
    private static final String ERR_LEDGER_DELETED = "LEDGER_DELETED";

    private static final List<String> PUSH_ORDER = List.of(
            SyncDtos.ENTITY_LEDGER,
            SyncDtos.ENTITY_CATEGORY, SyncDtos.ENTITY_ACCOUNT, SyncDtos.ENTITY_TRANSACTION,
            SyncDtos.ENTITY_BUDGET, SyncDtos.ENTITY_RECURRING);

    private final CategoryRowRepository categoryRepository;
    private final AccountRowRepository accountRepository;
    private final TransactionRowRepository transactionRepository;
    private final BudgetRowRepository budgetRepository;
    private final RecurringRowRepository recurringRepository;
    private final SyncChangeRepository changeRepository;
    private final ConflictLogRepository conflictRepository;
    private final SyncRowRepository syncRowRepository;
    private final UserRepository userRepository;
    private final LedgerRowRepository ledgerRepository;
    private final LedgerMemberRowRepository ledgerMemberRepository;
    private final ServerMetaRepository serverMetaRepository;
    private final ObjectMapper objectMapper;

    public SyncService(CategoryRowRepository categoryRepository,
                       AccountRowRepository accountRepository,
                       TransactionRowRepository transactionRepository,
                       BudgetRowRepository budgetRepository,
                       RecurringRowRepository recurringRepository,
                       SyncChangeRepository changeRepository,
                       ConflictLogRepository conflictRepository,
                       SyncRowRepository syncRowRepository,
                       UserRepository userRepository,
                       LedgerRowRepository ledgerRepository,
                       LedgerMemberRowRepository ledgerMemberRepository,
                       ServerMetaRepository serverMetaRepository,
                       ObjectMapper objectMapper) {
        this.categoryRepository = categoryRepository;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.budgetRepository = budgetRepository;
        this.recurringRepository = recurringRepository;
        this.changeRepository = changeRepository;
        this.conflictRepository = conflictRepository;
        this.syncRowRepository = syncRowRepository;
        this.userRepository = userRepository;
        this.ledgerRepository = ledgerRepository;
        this.ledgerMemberRepository = ledgerMemberRepository;
        this.serverMetaRepository = serverMetaRepository;
        this.objectMapper = objectMapper;
    }

    // ===== Push =====

    /** 单次 Push 内的账本访问上下文缓存：同一批变更通常落在同一账本。 */
    private record LedgerAccess(LedgerRow ledger, LedgerMemberRow membership, String denial) {
    }

    @Transactional
    public PushResponse push(AuthUser user, PushRequest request) {
        List<PushResultItem> results = new ArrayList<>();
        List<PushItem> items = request.changes() == null ? List.of() : request.changes();
        List<PushItem> ordered = new ArrayList<>(items);
        ordered.sort(Comparator.comparingInt(i -> {
            int idx = PUSH_ORDER.indexOf(i.entityType());
            return idx < 0 ? PUSH_ORDER.size() : idx;
        }));

        Map<String, LedgerAccess> accessCache = new HashMap<>();
        for (PushItem item : ordered) {
            results.add(applyItem(user, item, accessCache));
        }
        log.info("sync push user={} device={} items={} conflicts={}", user.userId(),
                maskDeviceId(user.deviceId()), items.size(),
                results.stream().filter(PushResultItem::conflicted).count());
        return new PushResponse(results, System.currentTimeMillis(), recoveryEpoch());
    }

    private PushResultItem applyItem(AuthUser user, PushItem item,
                                     Map<String, LedgerAccess> accessCache) {
        long userId = user.userId();
        try {
            validateItem(item);
        } catch (ApiException e) {
            return rejected(item, ERR_VALIDATION);
        }
        Instant now = Instant.now();
        if (SyncDtos.ENTITY_LEDGER.equals(item.entityType())) {
            // LEDGER 自带鉴权语义（创建自封 OWNER / 修改按目标账本角色），不走预解析
            return applyLedger(user, item, now);
        }
        LedgerAccess access = accessCache.computeIfAbsent(item.ledgerId(),
                id -> resolveAccess(userId, id));
        if (access.denial() != null) {
            return rejected(item, access.denial());
        }
        if (!access.membership().atLeast(LedgerMemberRow.ROLE_MEMBER)) {
            return rejected(item, ERR_LEDGER_ROLE_REQUIRED);
        }
        if (access.ledger().isDeleted()) {
            return rejected(item, ERR_LEDGER_DELETED);
        }
        return switch (item.entityType()) {
            case SyncDtos.ENTITY_CATEGORY -> applyCategory(user, item, access, now);
            case SyncDtos.ENTITY_ACCOUNT -> applyAccount(user, item, access, now);
            case SyncDtos.ENTITY_TRANSACTION -> applyTransaction(user, item, access, now);
            case SyncDtos.ENTITY_BUDGET -> applyBudget(user, item, access, now);
            case SyncDtos.ENTITY_RECURRING -> applyRecurring(user, item, access, now);
            default -> rejected(item, ERR_VALIDATION);
        };
    }

    /** Push 逐项账本鉴权（基线 10.1）：存在 → 成员（ACTIVE）→ 未删除。 */
    private LedgerAccess resolveAccess(long userId, String ledgerSyncId) {
        if (ledgerSyncId == null || ledgerSyncId.isBlank()) {
            return new LedgerAccess(null, null, ERR_LEDGER_NOT_FOUND);
        }
        LedgerRow ledger = ledgerRepository.findBySyncId(ledgerSyncId).orElse(null);
        if (ledger == null) {
            return new LedgerAccess(null, null, ERR_LEDGER_NOT_FOUND);
        }
        LedgerMemberRow membership = ledgerMemberRepository
                .findByLedgerIdAndUserId(ledger.getId(), userId).orElse(null);
        if (membership == null || !LedgerMemberRow.STATUS_ACTIVE.equals(membership.getStatus())) {
            return new LedgerAccess(ledger, membership, ERR_LEDGER_ACCESS_DENIED);
        }
        if (ledger.isDeleted()) {
            return new LedgerAccess(ledger, membership, ERR_LEDGER_DELETED);
        }
        return new LedgerAccess(ledger, membership, null);
    }

    private PushResultItem applyCategory(AuthUser user, PushItem item, LedgerAccess access,
                                         Instant now) {
        long ledgerId = access.ledger().getId();
        CategoryRow row = categoryRepository.findByLedgerIdAndSyncId(ledgerId, item.syncId())
                .orElse(null);
        if (row == null) {
            if (SyncChangeRow.OP_DELETE.equals(item.operation())) {
                return noop(item, now);
            }
            // 多设备/多成员重名合并（基线第 27 章）：两台设备各自初始化的默认分类
            // 名字相同但 syncId 不同，直接新建会产生重复。同账本已有同名同类型
            // 分类时不再新建，返回 mergedInto 让客户端把本地行身份重映射到已有行。
            CategoryRow twin = findCategoryTwin(ledgerId, item.payload());
            if (twin != null) {
                return new PushResultItem(item.entityType(), item.syncId(), true, false,
                        twin.getVersion(), twin.getServerReceivedAt().toEpochMilli(),
                        toPayload(SyncDtos.ENTITY_CATEGORY, twin), null, twin.getSyncId());
            }
            CategoryRow created = new CategoryRow();
            created.setUserId(user.userId());
            created.setLedgerId(ledgerId);
            created.setSyncId(item.syncId());
            created.setCreatedAt(now);
            applyCategoryFields(created, item.payload());
            return finishCreate(user, item, created, now, categoryRepository::save);
        }
        Lww.Decision decision = decide(user, item, row, now);
        if (decision == Lww.Decision.CONFLICT_SERVER_WINS) {
            return serverWon(user, item, row, now);
        }
        applyDeleteOrFields(row, item, () -> applyCategoryFields(row, item.payload()), now);
        return finishUpdate(user, item, row, now,
                decision == Lww.Decision.CONFLICT_INCOMING_WINS, categoryRepository::save);
    }

    private PushResultItem applyAccount(AuthUser user, PushItem item, LedgerAccess access,
                                        Instant now) {
        long ledgerId = access.ledger().getId();
        AccountRow row = accountRepository.findByLedgerIdAndSyncId(ledgerId, item.syncId())
                .orElse(null);
        if (row == null) {
            if (SyncChangeRow.OP_DELETE.equals(item.operation())) {
                return noop(item, now);
            }
            // 同名账户合并（同上）
            AccountRow twin = findAccountTwin(ledgerId, item.payload());
            if (twin != null) {
                return new PushResultItem(item.entityType(), item.syncId(), true, false,
                        twin.getVersion(), twin.getServerReceivedAt().toEpochMilli(),
                        toPayload(SyncDtos.ENTITY_ACCOUNT, twin), null, twin.getSyncId());
            }
            AccountRow created = new AccountRow();
            created.setUserId(user.userId());
            created.setLedgerId(ledgerId);
            created.setSyncId(item.syncId());
            created.setCreatedAt(now);
            applyAccountFields(created, item.payload());
            return finishCreate(user, item, created, now, accountRepository::save);
        }
        Lww.Decision decision = decide(user, item, row, now);
        if (decision == Lww.Decision.CONFLICT_SERVER_WINS) {
            return serverWon(user, item, row, now);
        }
        applyDeleteOrFields(row, item, () -> applyAccountFields(row, item.payload()), now);
        return finishUpdate(user, item, row, now,
                decision == Lww.Decision.CONFLICT_INCOMING_WINS, accountRepository::save);
    }

    private PushResultItem applyTransaction(AuthUser user, PushItem item, LedgerAccess access,
                                            Instant now) {
        long ledgerId = access.ledger().getId();
        TransactionRow row = transactionRepository.findByLedgerIdAndSyncId(ledgerId, item.syncId())
                .orElse(null);
        if (row == null) {
            if (SyncChangeRow.OP_DELETE.equals(item.operation())) {
                return noop(item, now);
            }
            String refError = validateTransactionRefs(ledgerId, item.payload());
            if (refError != null) {
                return rejected(item, refError);
            }
            TransactionRow created = new TransactionRow();
            created.setUserId(user.userId());
            created.setLedgerId(ledgerId);
            created.setSyncId(item.syncId());
            created.setCreatedAt(now);
            applyTransactionFields(created, item.payload());
            return finishCreate(user, item, created, now, transactionRepository::save);
        }
        Lww.Decision decision = decide(user, item, row, now);
        if (decision == Lww.Decision.CONFLICT_SERVER_WINS) {
            return serverWon(user, item, row, now);
        }
        // DELETE 允许空载荷（软删墓碑），引用校验只针对 UPSERT
        String refError = item.payload() == null ? null
                : validateTransactionRefs(ledgerId, item.payload());
        if (refError != null) {
            return rejected(item, refError);
        }
        applyDeleteOrFields(row, item, () -> applyTransactionFields(row, item.payload()), now);
        return finishUpdate(user, item, row, now,
                decision == Lww.Decision.CONFLICT_INCOMING_WINS, transactionRepository::save);
    }

    private PushResultItem applyBudget(AuthUser user, PushItem item, LedgerAccess access,
                                       Instant now) {
        long ledgerId = access.ledger().getId();
        BudgetRow row = budgetRepository.findByLedgerIdAndSyncId(ledgerId, item.syncId())
                .orElse(null);
        if (row == null) {
            if (SyncChangeRow.OP_DELETE.equals(item.operation())) {
                return noop(item, now);
            }
            String refError = validateCategoryRef(ledgerId, item.payload().categorySyncId);
            if (refError != null) {
                return rejected(item, refError);
            }
            // 同（年，月，分类）预算合并（同上；主键语义 = ledger + category + 年 + 月）
            BudgetRow twin = findBudgetTwin(ledgerId, item.payload());
            if (twin != null) {
                return new PushResultItem(item.entityType(), item.syncId(), true, false,
                        twin.getVersion(), twin.getServerReceivedAt().toEpochMilli(),
                        toPayload(SyncDtos.ENTITY_BUDGET, twin), null, twin.getSyncId());
            }
            BudgetRow created = new BudgetRow();
            created.setUserId(user.userId());
            created.setLedgerId(ledgerId);
            created.setSyncId(item.syncId());
            created.setCreatedAt(now);
            applyBudgetFields(created, item.payload());
            return finishCreate(user, item, created, now, budgetRepository::save);
        }
        Lww.Decision decision = decide(user, item, row, now);
        if (decision == Lww.Decision.CONFLICT_SERVER_WINS) {
            return serverWon(user, item, row, now);
        }
        String refError = item.payload() == null ? null
                : validateCategoryRef(ledgerId, item.payload().categorySyncId);
        if (refError != null) {
            return rejected(item, refError);
        }
        applyDeleteOrFields(row, item, () -> applyBudgetFields(row, item.payload()), now);
        return finishUpdate(user, item, row, now,
                decision == Lww.Decision.CONFLICT_INCOMING_WINS, budgetRepository::save);
    }

    private PushResultItem applyRecurring(AuthUser user, PushItem item, LedgerAccess access,
                                          Instant now) {
        long ledgerId = access.ledger().getId();
        RecurringRow row = recurringRepository.findByLedgerIdAndSyncId(ledgerId, item.syncId())
                .orElse(null);
        if (row == null) {
            if (SyncChangeRow.OP_DELETE.equals(item.operation())) {
                return noop(item, now);
            }
            String refError = validateRecurringRefs(ledgerId, item.payload());
            if (refError != null) {
                return rejected(item, refError);
            }
            RecurringRow created = new RecurringRow();
            created.setUserId(user.userId());
            created.setLedgerId(ledgerId);
            created.setSyncId(item.syncId());
            created.setCreatedAt(now);
            applyRecurringFields(created, item.payload());
            return finishCreate(user, item, created, now, recurringRepository::save);
        }
        Lww.Decision decision = decide(user, item, row, now);
        if (decision == Lww.Decision.CONFLICT_SERVER_WINS) {
            return serverWon(user, item, row, now);
        }
        String refError = item.payload() == null ? null
                : validateRecurringRefs(ledgerId, item.payload());
        if (refError != null) {
            return rejected(item, refError);
        }
        applyDeleteOrFields(row, item, () -> applyRecurringFields(row, item.payload()), now);
        return finishUpdate(user, item, row, now,
                decision == Lww.Decision.CONFLICT_INCOMING_WINS, recurringRepository::save);
    }

    // ===== LEDGER 实体（V3.2）：账本自身的创建/改名/删除走同步通道 =====

    private PushResultItem applyLedger(AuthUser user, PushItem item, Instant now) {
        LedgerRow row = ledgerRepository.findBySyncId(item.syncId()).orElse(null);
        if (row == null) {
            if (SyncChangeRow.OP_DELETE.equals(item.operation())) {
                return noop(item, now);
            }
            SyncPayload payload = item.payload();
            if (payload == null || payload.name == null || payload.name.isBlank()) {
                return rejected(item, ERR_VALIDATION);
            }
            return createLedger(user, item, payload, now);
        }
        LedgerMemberRow membership = ledgerMemberRepository
                .findByLedgerIdAndUserId(row.getId(), user.userId()).orElse(null);
        if (membership == null || !LedgerMemberRow.STATUS_ACTIVE.equals(membership.getStatus())) {
            return rejected(item, ERR_LEDGER_ACCESS_DENIED);
        }
        boolean tombstone = SyncChangeRow.OP_DELETE.equals(item.operation())
                || (item.payload() != null && Boolean.TRUE.equals(item.payload().isDeleted));
        Lww.Decision decision = Lww.resolve(item.baseVersion(), row.getVersion(),
                now.toEpochMilli(), row.getServerReceivedAt().toEpochMilli());
        if (decision == Lww.Decision.CONFLICT_SERVER_WINS) {
            return new PushResultItem(item.entityType(), item.syncId(), false, true,
                    row.getVersion(), row.getServerReceivedAt().toEpochMilli(),
                    toLedgerPayload(row), "CONFLICT_SERVER_WON", null);
        }
        if (tombstone) {
            // 删除账本仅 OWNER（基线 12.2）；影响所有成员，客户端拉到墓碑后自行切换
            if (!LedgerMemberRow.ROLE_OWNER.equals(membership.getRole())) {
                return rejected(item, ERR_LEDGER_ROLE_REQUIRED);
            }
            row.setDeleted(true);
            row.setDeletedAt(item.payload() != null && item.payload().deletedAt != null
                    ? item.payload().deletedAt : now.toEpochMilli());
        } else {
            // 改名/描述/币种/归档 = 账本设置，ADMIN 及以上（基线第 9 章）
            if (!membership.atLeast(LedgerMemberRow.ROLE_ADMIN)) {
                return rejected(item, ERR_LEDGER_ROLE_REQUIRED);
            }
            if (row.isDeleted()) {
                // 已删账本不允许经同步复活，恢复走 REST（仅 OWNER）
                return rejected(item, ERR_LEDGER_DELETED);
            }
            applyLedgerFields(row, item.payload());
        }
        row.setVersion(row.getVersion() + 1);
        row.setServerReceivedAt(now);
        ledgerRepository.save(row);
        writeLedgerChangeLog(row, item.operation(), user.userId(), now);
        return new PushResultItem(item.entityType(), item.syncId(), true,
                decision == Lww.Decision.CONFLICT_INCOMING_WINS,
                row.getVersion(), now.toEpochMilli(), toLedgerPayload(row), null, null);
    }

    private PushResultItem createLedger(AuthUser user, PushItem item, SyncPayload payload,
                                        Instant now) {
        boolean claimingDefault = Boolean.TRUE.equals(payload.isDefault);
        if (claimingDefault) {
            // V3.1 → V3.2 升级链路：用户已有迁移回填的默认账本时，把本地默认账本
            // 的身份合并过去（mergedInto），客户端整体迁移 ledger_id，不新建、不重播种。
            LedgerRow existingDefault = ledgerRepository
                    .findByUserIdAndDefaultLedgerTrueAndDeletedFalse(user.userId()).orElse(null);
            if (existingDefault != null) {
                return new PushResultItem(item.entityType(), item.syncId(), true, false,
                        existingDefault.getVersion(),
                        existingDefault.getServerReceivedAt().toEpochMilli(),
                        toLedgerPayload(existingDefault), null, existingDefault.getSyncId());
            }
        }
        LedgerRow created = new LedgerRow();
        created.setUserId(user.userId());
        created.setSyncId(item.syncId());
        created.setName(payload.name.trim());
        created.setDescription(orEmpty(payload.description));
        created.setCurrency(orEmpty(payload.currency).isBlank()
                ? "CNY" : payload.currency.trim());
        created.setDefaultLedger(claimingDefault);
        created.setClientUpdatedAt(orZero(payload.clientUpdatedAt));
        created.setCreatedAt(now);
        created.setVersion(1);
        created.setServerReceivedAt(now);
        ledgerRepository.save(created);
        upsertOwnerMembership(created, user.userId(), now);
        writeLedgerChangeLog(created, item.operation(), user.userId(), now);
        // 服务端初始化一次默认分类/账户（基线第 27 章），客户端本地种子经 mergedInto 去重
        seedLedgerDefaults(created, user.userId(), now);
        return new PushResultItem(item.entityType(), item.syncId(), true, false,
                created.getVersion(), now.toEpochMilli(), toLedgerPayload(created), null, null);
    }

    private void upsertOwnerMembership(LedgerRow ledger, long userId, Instant now) {
        LedgerMemberRow membership = ledgerMemberRepository
                .findByLedgerIdAndUserId(ledger.getId(), userId).orElse(null);
        if (membership != null) {
            return;
        }
        membership = new LedgerMemberRow();
        membership.setLedgerId(ledger.getId());
        membership.setUserId(userId);
        membership.setRole(LedgerMemberRow.ROLE_OWNER);
        membership.setStatus(LedgerMemberRow.STATUS_ACTIVE);
        membership.setAcceptedAt(now);
        membership.setCreatedAt(now);
        membership.setUpdatedAt(now);
        ledgerMemberRepository.save(membership);
    }

    /** 新账本默认数据：与客户端 DefaultData 完全同名同类型，本地种子推送时经 twin-merge 收敛。 */
    private void seedLedgerDefaults(LedgerRow ledger, long ownerId, Instant now) {
        String[][] expense = {
                {"餐饮", "🍚"}, {"交通", "🚇"}, {"购物", "🛍"}, {"娱乐", "🎮"},
                {"住房", "🏠"}, {"通讯", "📱"}, {"医疗", "💊"}, {"教育", "📚"},
                {"旅行", "✈️"}, {"其他", "💰"}};
        String[][] income = {
                {"工资", "💼"}, {"奖金", "🎁"}, {"兼职", "💻"}, {"投资", "📈"},
                {"红包", "🧧"}, {"其他", "💰"}};
        for (int i = 0; i < expense.length; i++) {
            seedCategory(ledger, ownerId, expense[i][0], expense[i][1], 1, i + 1, now);
        }
        for (int i = 0; i < income.length; i++) {
            seedCategory(ledger, ownerId, income[i][0], income[i][1], 2, i + 1, now);
        }
        Object[][] accounts = {
                {"现金", 1, false}, {"微信", 2, false}, {"支付宝", 3, false},
                {"储蓄卡", 4, false}, {"信用卡", 5, true}, {"其他", 6, false}};
        for (int i = 0; i < accounts.length; i++) {
            AccountRow account = new AccountRow();
            account.setUserId(ownerId);
            account.setLedgerId(ledger.getId());
            account.setSyncId(UUID.randomUUID().toString());
            account.setName((String) accounts[i][0]);
            account.setType((Integer) accounts[i][1]);
            account.setInitialBalance(0);
            account.setBalance(0);
            account.setCredit((Boolean) accounts[i][2]);
            account.setSortOrder(i + 1);
            account.setCreatedAt(now);
            account.setVersion(1);
            account.setServerReceivedAt(now);
            accountRepository.save(account);
            writeSeededChangeLog(ledger.getId(), ownerId, SyncDtos.ENTITY_ACCOUNT,
                    account.getSyncId(), account.getVersion(), now);
        }
    }

    private void seedCategory(LedgerRow ledger, long ownerId, String name, String icon, int type,
                              int sortOrder, Instant now) {
        CategoryRow category = new CategoryRow();
        category.setUserId(ownerId);
        category.setLedgerId(ledger.getId());
        category.setSyncId(UUID.randomUUID().toString());
        category.setName(name);
        category.setIcon(icon);
        category.setType(type);
        category.setSortOrder(sortOrder);
        category.setDefault(true);
        category.setCreatedAt(now);
        category.setVersion(1);
        category.setServerReceivedAt(now);
        categoryRepository.save(category);
        writeSeededChangeLog(ledger.getId(), ownerId, SyncDtos.ENTITY_CATEGORY,
                category.getSyncId(), category.getVersion(), now);
    }

    /** 服务端种子数据没有对应的 PushItem，直接按 UPSERT 写变更日志供成员拉取。 */
    private void writeSeededChangeLog(Long ledgerId, Long userId, String entityType,
                                      String syncId, long version, Instant now) {
        SyncChangeRow change = new SyncChangeRow();
        change.setLedgerId(ledgerId);
        change.setUserId(userId);
        change.setEntityType(entityType);
        change.setSyncId(syncId);
        change.setVersion(version);
        change.setOperation(SyncChangeRow.OP_UPSERT);
        change.setServerReceivedAt(now);
        changeRepository.save(change);
    }

    private void writeLedgerChangeLog(LedgerRow ledger, String operation, Long userId,
                                      Instant now) {
        writeSeededChangeLog(ledger.getId(), userId, SyncDtos.ENTITY_LEDGER,
                ledger.getSyncId(), ledger.getVersion(), now);
    }

    private void applyLedgerFields(LedgerRow row, SyncPayload payload) {
        if (payload.name != null && !payload.name.isBlank()) {
            row.setName(payload.name.trim());
        }
        if (payload.description != null) {
            row.setDescription(payload.description);
        }
        if (payload.currency != null && !payload.currency.isBlank()) {
            row.setCurrency(payload.currency.trim());
        }
        row.setArchived(Boolean.TRUE.equals(payload.isArchived));
        row.setClientUpdatedAt(orZero(payload.clientUpdatedAt));
    }

    private SyncPayload toLedgerPayload(LedgerRow row) {
        SyncPayload payload = new SyncPayload();
        payload.name = row.getName();
        payload.description = row.getDescription();
        payload.currency = row.getCurrency();
        payload.isArchived = row.isArchived();
        payload.isDefault = row.isDefaultLedger();
        payload.ownerUserId = row.getUserId();
        payload.clientUpdatedAt = row.getClientUpdatedAt();
        payload.isDeleted = row.isDeleted();
        payload.deletedAt = row.getDeletedAt();
        return payload;
    }

    // ===== Push 公共步骤 =====

    /** 同账本下同名（忽略大小写与首尾空格）同类型的有效分类；入参不完整时视为无孪生。 */
    private CategoryRow findCategoryTwin(Long ledgerId, SyncPayload payload) {
        if (payload == null || payload.name == null || payload.name.isBlank()
                || payload.type == null) {
            return null;
        }
        return categoryRepository
                .findByLedgerIdAndTypeAndNameIgnoreCase(ledgerId, payload.type, payload.name.trim())
                .orElse(null);
    }

    /** 同账本下同名账户。 */
    private AccountRow findAccountTwin(Long ledgerId, SyncPayload payload) {
        if (payload == null || payload.name == null || payload.name.isBlank()) {
            return null;
        }
        return accountRepository.findByLedgerIdAndNameIgnoreCase(ledgerId, payload.name.trim())
                .orElse(null);
    }

    /** 同账本同（年，月，分类）预算；总预算哨兵在存储层是空串。 */
    private BudgetRow findBudgetTwin(Long ledgerId, SyncPayload payload) {
        if (payload == null || payload.year == null || payload.month == null) {
            return null;
        }
        String categorySyncId = payload.categorySyncId == null ? "" : payload.categorySyncId;
        return budgetRepository
                .findByLedgerIdAndYearAndMonthAndCategorySyncId(ledgerId, payload.year,
                        payload.month, categorySyncId)
                .orElse(null);
    }

    /** DELETE 只置软删位（payload 允许为 null）；UPSERT 应用业务字段。 */
    private void applyDeleteOrFields(SyncRow row, PushItem item, Runnable fieldApplier,
                                     Instant now) {
        if (SyncChangeRow.OP_DELETE.equals(item.operation())) {
            row.setDeleted(true);
            row.setDeletedAt(item.payload() != null && item.payload().deletedAt != null
                    ? item.payload().deletedAt : now.toEpochMilli());
            row.setClientUpdatedAt(item.payload() != null && item.payload().clientUpdatedAt != null
                    ? item.payload().clientUpdatedAt : 0);
        } else {
            fieldApplier.run();
        }
    }


    private Lww.Decision decide(AuthUser user, PushItem item, SyncRow row, Instant now) {
        return Lww.resolve(item.baseVersion(), row.getVersion(), now.toEpochMilli(),
                row.getServerReceivedAt().toEpochMilli());
    }

    /** 接受变更：写业务行 + 版本 +1 + 变更日志；冲突时补 conflict log（败者摘要留存）。 */
    private <T extends SyncRow> PushResultItem finishCreate(AuthUser user, PushItem item, T row,
                                                            Instant now,
                                                            Function<T, T> saver) {
        row.setVersion(1);
        row.setServerReceivedAt(now);
        saver.apply(row);
        writeChangeLog(row.getLedgerId(), user.userId(), item, row, now);
        return accepted(item, 1, now, toPayload(item.entityType(), row), null);
    }

    private <T extends SyncRow> PushResultItem finishUpdate(AuthUser user, PushItem item, T row,
                                                            Instant now, boolean conflicted,
                                                            Function<T, T> saver) {
        if (conflicted) {
            writeConflictLog(user, item, row, now, ConflictLogRow.WINNER_CLIENT);
        }
        row.setVersion(row.getVersion() + 1);
        row.setServerReceivedAt(now);
        saver.apply(row);
        writeChangeLog(row.getLedgerId(), user.userId(), item, row, now);
        return new PushResultItem(item.entityType(), item.syncId(), true, conflicted,
                row.getVersion(), now.toEpochMilli(), toPayload(item.entityType(), row), null, null);
    }

    /** DELETE 未知 syncId：幂等 no-op 成功（该身份从未上云，其他设备不可能持有）。 */
    private PushResultItem noop(PushItem item, Instant now) {
        SyncPayload payload = new SyncPayload();
        payload.isDeleted = true;
        return accepted(item, 0, now, payload, null);
    }

    /** 服务器当前版本胜出：传入写入被拒，同样补写冲突审计（winner=SERVER）。 */
    private PushResultItem serverWon(AuthUser user, PushItem item, SyncRow row, Instant now) {
        writeConflictLog(user, item, row, now, ConflictLogRow.WINNER_SERVER);
        return new PushResultItem(item.entityType(), item.syncId(), false, true,
                row.getVersion(), row.getServerReceivedAt().toEpochMilli(),
                toPayload(itemEntityType(item), row), "CONFLICT_SERVER_WON", null);
    }

    private PushResultItem rejected(PushItem item, String errorCode) {
        return new PushResultItem(item.entityType(), item.syncId(), false, false,
                0, 0, null, errorCode, null);
    }

    private PushResultItem accepted(PushItem item, long version, Instant receivedAt,
                                    SyncPayload payload, String errorCode) {
        return new PushResultItem(item.entityType(), item.syncId(), true, false,
                version, receivedAt.toEpochMilli(), payload, errorCode, null);
    }

    private void writeChangeLog(Long ledgerId, Long userId, PushItem item, SyncRow row,
                                Instant now) {
        SyncChangeRow change = new SyncChangeRow();
        change.setLedgerId(ledgerId);
        change.setUserId(userId);
        change.setEntityType(item.entityType());
        change.setSyncId(item.syncId());
        change.setVersion(row.getVersion());
        change.setOperation(item.operation());
        change.setServerReceivedAt(now);
        changeRepository.save(change);
    }

    private void writeConflictLog(AuthUser user, PushItem item, SyncRow row, Instant now,
                                  String winner) {
        ConflictLogRow conflict = new ConflictLogRow();
        conflict.setUserId(user.userId());
        conflict.setEntityType(item.entityType());
        conflict.setSyncId(item.syncId());
        conflict.setClientDeviceId(user.deviceId() == null ? "" : user.deviceId());
        conflict.setBaseVersion(item.baseVersion());
        conflict.setServerVersion(row.getVersion());
        conflict.setClientPayloadDigest(digest(item.payload()));
        conflict.setServerPayloadDigest(digest(toPayload(item.entityType(), row)));
        conflict.setWinner(winner);
        conflict.setCreatedAt(now);
        conflictRepository.save(conflict);
    }

    private String digest(SyncPayload payload) {
        try {
            return HashUtil.sha256Hex(objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            return "digest-error";
        }
    }

    private void validateItem(PushItem item) {
        if (item == null || item.syncId() == null || item.syncId().isBlank()) {
            throw ApiException.badRequest("syncId 不能为空");
        }
        if (!SyncChangeRow.OP_UPSERT.equals(item.operation())
                && !SyncChangeRow.OP_DELETE.equals(item.operation())) {
            throw ApiException.badRequest("未知 operation: " + item.operation());
        }
        if (SyncChangeRow.OP_UPSERT.equals(item.operation()) && item.payload() == null) {
            throw ApiException.badRequest("UPSERT 缺少 payload");
        }
        if (!SyncDtos.ENTITY_LEDGER.equals(item.entityType())
                && (item.ledgerId() == null || item.ledgerId().isBlank())) {
            // 业务变更必须声明所属账本（基线 10.1），账本自身的变更除外
            throw ApiException.badRequest("ledgerId 不能为空");
        }
    }

    // ===== 引用校验（服务层，不建跨表外键；软删行仍可被引用；引用必须同账本） =====

    private String validateTransactionRefs(Long ledgerId, SyncPayload payload) {
        String err = validateCategoryRef(ledgerId, payload.categorySyncId);
        if (err != null) {
            return err;
        }
        err = validateAccountRef(ledgerId, payload.accountSyncId);
        if (err != null) {
            return err;
        }
        return validateAccountRef(ledgerId, payload.transferAccountSyncId);
    }

    private String validateRecurringRefs(Long ledgerId, SyncPayload payload) {
        String err = validateCategoryRef(ledgerId, payload.categorySyncId);
        if (err != null) {
            return err;
        }
        return validateAccountRef(ledgerId, payload.accountSyncId);
    }

    private String validateCategoryRef(Long ledgerId, String categorySyncId) {
        if (categorySyncId == null || categorySyncId.isBlank()) {
            return null;
        }
        return categoryRepository.findByLedgerIdAndSyncId(ledgerId, categorySyncId).isPresent()
                ? null : ERR_MISSING_REFERENCE;
    }

    private String validateAccountRef(Long ledgerId, String accountSyncId) {
        if (accountSyncId == null || accountSyncId.isBlank()) {
            return null;
        }
        return accountRepository.findByLedgerIdAndSyncId(ledgerId, accountSyncId).isPresent()
                ? null : ERR_MISSING_REFERENCE;
    }

    // ===== 字段应用（软删由 operation 决定；金额/时间原样存储，不解释业务口径） =====

    private void applyCategoryFields(CategoryRow row, SyncPayload payload) {
        row.setName(require(payload.name, "name"));
        row.setIcon(orEmpty(payload.icon));
        row.setType(requireInt(payload.type, "type"));
        row.setSortOrder(payload.sortOrder == null ? 0 : payload.sortOrder);
        row.setDefault(Boolean.TRUE.equals(payload.isDefault));
        row.setClientUpdatedAt(orZero(payload.clientUpdatedAt));
        row.setDeleted(payload.isDeleted != null && payload.isDeleted);
        row.setDeletedAt(payload.deletedAt);
    }

    private void applyAccountFields(AccountRow row, SyncPayload payload) {
        row.setName(require(payload.name, "name"));
        row.setType(requireInt(payload.type, "type"));
        row.setInitialBalance(orZero(payload.initialBalance));
        row.setBalance(orZero(payload.balance));
        row.setCredit(Boolean.TRUE.equals(payload.isCredit));
        row.setSortOrder(payload.sortOrder == null ? 0 : payload.sortOrder);
        row.setArchived(Boolean.TRUE.equals(payload.isArchived));
        row.setClientUpdatedAt(orZero(payload.clientUpdatedAt));
        row.setDeleted(payload.isDeleted != null && payload.isDeleted);
        row.setDeletedAt(payload.deletedAt);
    }

    private void applyTransactionFields(TransactionRow row, SyncPayload payload) {
        row.setType(requireInt(payload.type, "type"));
        row.setAmount(orZero(payload.amount));
        row.setDate(orZero(payload.date));
        row.setTime(payload.time == null ? "00:00" : payload.time);
        row.setNote(payload.note);
        row.setCategorySyncId(blankToNull(payload.categorySyncId));
        row.setAccountSyncId(blankToNull(payload.accountSyncId));
        row.setTransferAccountSyncId(blankToNull(payload.transferAccountSyncId));
        row.setClientCreatedAt(orZero(payload.clientCreatedAt));
        row.setClientUpdatedAt(orZero(payload.clientUpdatedAt));
        row.setDeleted(payload.isDeleted != null && payload.isDeleted);
        row.setDeletedAt(payload.deletedAt);
    }

    private void applyBudgetFields(BudgetRow row, SyncPayload payload) {
        row.setYear(requireInt(payload.year, "year"));
        row.setMonth(requireInt(payload.month, "month"));
        row.setCategorySyncId(payload.categorySyncId == null ? "" : payload.categorySyncId);
        row.setAmount(orZero(payload.amount));
        row.setClientUpdatedAt(orZero(payload.clientUpdatedAt));
        row.setDeleted(payload.isDeleted != null && payload.isDeleted);
        row.setDeletedAt(payload.deletedAt);
    }

    private void applyRecurringFields(RecurringRow row, SyncPayload payload) {
        row.setName(require(payload.name, "name"));
        row.setType(requireInt(payload.type, "type"));
        row.setAmount(orZero(payload.amount));
        row.setCategorySyncId(blankToNull(payload.categorySyncId));
        row.setAccountSyncId(blankToNull(payload.accountSyncId));
        row.setFrequency(requireInt(payload.frequency, "frequency"));
        row.setRepeatInterval(payload.repeatInterval == null ? 1 : payload.repeatInterval);
        row.setStartDate(orZero(payload.startDate));
        row.setEndDate(orZero(payload.endDate));
        row.setNextRunDate(orZero(payload.nextRunDate));
        row.setAnchorDayOfMonth(payload.anchorDayOfMonth == null ? 0 : payload.anchorDayOfMonth);
        row.setEnabled(payload.isEnabled == null || payload.isEnabled);
        row.setNote(payload.note);
        row.setClientUpdatedAt(orZero(payload.clientUpdatedAt));
        row.setDeleted(payload.isDeleted != null && payload.isDeleted);
        row.setDeletedAt(payload.deletedAt);
    }

    // ===== 载荷组装 =====

    private SyncPayload toPayload(String entityType, SyncRow row) {
        SyncPayload payload = new SyncPayload();
        payload.clientUpdatedAt = row.getClientUpdatedAt();
        payload.isDeleted = row.isDeleted();
        payload.deletedAt = row.getDeletedAt();
        if (row instanceof CategoryRow category) {
            payload.name = category.getName();
            payload.icon = category.getIcon();
            payload.type = category.getType();
            payload.sortOrder = category.getSortOrder();
            payload.isDefault = category.isDefault();
        } else if (row instanceof AccountRow account) {
            payload.name = account.getName();
            payload.type = account.getType();
            payload.initialBalance = account.getInitialBalance();
            payload.balance = account.getBalance();
            payload.isCredit = account.isCredit();
            payload.sortOrder = account.getSortOrder();
            payload.isArchived = account.isArchived();
        } else if (row instanceof TransactionRow transaction) {
            payload.type = transaction.getType();
            payload.amount = transaction.getAmount();
            payload.date = transaction.getDate();
            payload.time = transaction.getTime();
            payload.note = transaction.getNote();
            payload.categorySyncId = transaction.getCategorySyncId();
            payload.accountSyncId = transaction.getAccountSyncId();
            payload.transferAccountSyncId = transaction.getTransferAccountSyncId();
            payload.clientCreatedAt = transaction.getClientCreatedAt();
        } else if (row instanceof BudgetRow budget) {
            payload.year = budget.getYear();
            payload.month = budget.getMonth();
            payload.categorySyncId = "".equals(budget.getCategorySyncId()) ? null
                    : budget.getCategorySyncId();
            payload.amount = budget.getAmount();
        } else if (row instanceof RecurringRow recurring) {
            payload.name = recurring.getName();
            payload.type = recurring.getType();
            payload.amount = recurring.getAmount();
            payload.categorySyncId = recurring.getCategorySyncId();
            payload.accountSyncId = recurring.getAccountSyncId();
            payload.frequency = recurring.getFrequency();
            payload.repeatInterval = recurring.getRepeatInterval();
            payload.startDate = recurring.getStartDate();
            payload.endDate = recurring.getEndDate();
            payload.nextRunDate = recurring.getNextRunDate();
            payload.anchorDayOfMonth = recurring.getAnchorDayOfMonth();
            payload.isEnabled = recurring.isEnabled();
            payload.note = recurring.getNote();
        }
        return payload;
    }

    private String itemEntityType(PushItem item) {
        return item.entityType();
    }

    // ===== Pull / Bootstrap / Status =====

    @Transactional(readOnly = true)
    public PullResponse pull(AuthUser user, PullRequest request) {
        // Pull 必须落在具体账本上（基线 10.3）：服务端隔离，禁客户端自滤。
        // 已删除账本仍可拉取（成员需要看到墓碑并切换）。
        LedgerRow ledger = ledgerRepository.findBySyncId(
                        request.ledgerId() == null ? "" : request.ledgerId())
                .orElseThrow(() -> ApiException.forbidden(ERR_LEDGER_NOT_FOUND, "账本不存在"));
        LedgerMemberRow membership = ledgerMemberRepository
                .findByLedgerIdAndUserId(ledger.getId(), user.userId())
                .filter(m -> LedgerMemberRow.STATUS_ACTIVE.equals(m.getStatus()))
                .orElseThrow(() -> ApiException.forbidden(ERR_LEDGER_ACCESS_DENIED,
                        "你不是该账本成员"));
        long cursor = Math.max(0, request.sinceChangeId());
        int limit = request.limit() <= 0 || request.limit() > MAX_PULL_LIMIT
                ? MAX_PULL_LIMIT : request.limit();
        List<SyncChangeRow> changes =
                changeRepository.findLatestChangesAfter(ledger.getId(), cursor);
        boolean hasMore = changes.size() > limit;
        List<SyncChangeRow> page = hasMore ? changes.subList(0, limit) : changes;

        List<ChangeItem> items = new ArrayList<>();
        long lastChangeId = cursor;
        for (SyncChangeRow change : page) {
            SyncRow row = SyncDtos.ENTITY_LEDGER.equals(change.getEntityType())
                    ? null : findRow(ledger.getId(), change.getEntityType(), change.getSyncId());
            if (!SyncDtos.ENTITY_LEDGER.equals(change.getEntityType()) && row == null) {
                // 行已被清理（如账号数据重置）：跳过该变更，游标自然越过
                lastChangeId = change.getId();
                continue;
            }
            lastChangeId = change.getId();
            if (SyncDtos.ENTITY_LEDGER.equals(change.getEntityType())) {
                if (!ledger.getSyncId().equals(change.getSyncId())) {
                    continue; // 不属于请求账本的 LEDGER 变更，越过
                }
                items.add(new ChangeItem(change.getId(), change.getEntityType(),
                        change.getSyncId(), change.getOperation(), ledger.getVersion(),
                        ledger.getServerReceivedAt().toEpochMilli(), toLedgerPayload(ledger)));
            } else {
                items.add(new ChangeItem(change.getId(), change.getEntityType(),
                        change.getSyncId(), change.getOperation(), row.getVersion(),
                        row.getServerReceivedAt().toEpochMilli(),
                        toPayload(change.getEntityType(), row)));
            }
        }
        return new PullResponse(items, lastChangeId, hasMore, System.currentTimeMillis(),
                recoveryEpoch());
    }

    private SyncRow findRow(Long ledgerId, String entityType, String syncId) {
        return switch (entityType) {
            case SyncDtos.ENTITY_CATEGORY ->
                    categoryRepository.findByLedgerIdAndSyncId(ledgerId, syncId).orElse(null);
            case SyncDtos.ENTITY_ACCOUNT ->
                    accountRepository.findByLedgerIdAndSyncId(ledgerId, syncId).orElse(null);
            case SyncDtos.ENTITY_TRANSACTION ->
                    transactionRepository.findByLedgerIdAndSyncId(ledgerId, syncId).orElse(null);
            case SyncDtos.ENTITY_BUDGET ->
                    budgetRepository.findByLedgerIdAndSyncId(ledgerId, syncId).orElse(null);
            case SyncDtos.ENTITY_RECURRING ->
                    recurringRepository.findByLedgerIdAndSyncId(ledgerId, syncId).orElse(null);
            default -> null;
        };
    }

    @Transactional(readOnly = true)
    public BootstrapSummaryResponse bootstrapSummary(AuthUser user) {
        Counts counts = toCounts(syncRowRepository.counts(user.userId()));
        return new BootstrapSummaryResponse(syncRowRepository.hasAnyData(user.userId()),
                counts, System.currentTimeMillis());
    }

    @Transactional(readOnly = true)
    public StatusResponse status(AuthUser user) {
        boolean verified = userRepository.findById(user.userId())
                .map(u -> u.isEmailVerified())
                .orElse(false);
        // 成员关系摘要（基线第 25 章）：客户端据此对账本地账本表，
        // 发现新账本 / 角色变化 / 被移出，并在下次同步后提示。
        List<LedgerMembershipSummary> memberships = new ArrayList<>();
        for (LedgerMemberRow member : ledgerMemberRepository.findAllByUserId(user.userId())) {
            if (!LedgerMemberRow.STATUS_ACTIVE.equals(member.getStatus())
                    && !LedgerMemberRow.STATUS_REMOVED.equals(member.getStatus())) {
                continue;
            }
            ledgerRepository.findById(member.getLedgerId())
                    .ifPresent(ledger -> memberships.add(new LedgerMembershipSummary(
                            ledger.getSyncId(), ledger.getName(), ledger.getDescription(),
                            ledger.getCurrency(), ledger.getUserId(), ledger.isDefaultLedger(),
                            ledger.isArchived(), ledger.isDeleted(), member.getRole(),
                            member.getStatus(), ledger.getVersion())));
        }
        return new StatusResponse(System.currentTimeMillis(), verified,
                ServerInfo.SERVER_VERSION, recoveryEpoch(), memberships);
    }

    /** 冲突历史（基线第 26 章）：最近 N 条冲突审计摘要，自动收敛、事后可查。 */
    @Transactional(readOnly = true)
    public ConflictsResponse conflicts(AuthUser user, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        List<ConflictItem> items = new ArrayList<>();
        for (ConflictLogRow row : conflictRepository
                .findByUserIdOrderByIdDesc(user.userId(), PageRequest.of(0, safeLimit))) {
            items.add(new ConflictItem(row.getId(), row.getEntityType(), row.getSyncId(),
                    row.getClientDeviceId(), row.getBaseVersion(), row.getServerVersion(),
                    row.getWinner(), row.getCreatedAt().toEpochMilli()));
        }
        return new ConflictsResponse(items, System.currentTimeMillis());
    }

    /** 服务器恢复代际：恢复备份时 +1，客户端据此重置游标重新收敛（基线第 16 章）。 */
    public long recoveryEpoch() {
        return serverMetaRepository.findById(ServerMeta.KEY_RECOVERY_EPOCH)
                .map(meta -> {
                    try {
                        return Long.parseLong(meta.getValue());
                    } catch (NumberFormatException e) {
                        return 0L;
                    }
                })
                .orElse(0L);
    }

    private Counts toCounts(SyncRowRepository.Counts counts) {
        return new Counts(counts.category(), counts.account(), counts.transaction(),
                counts.budget(), counts.recurring());
    }

    // ===== 工具 =====

    private String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(field + " 不能为空");
        }
        return value;
    }

    private int requireInt(Integer value, String field) {
        if (value == null) {
            throw ApiException.badRequest(field + " 不能为空");
        }
        return value;
    }

    private String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private long orZero(Long value) {
        return value == null ? 0 : value;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String maskDeviceId(String deviceId) {
        if (deviceId == null || deviceId.length() < 8) {
            return "***";
        }
        return deviceId.substring(0, 8) + "***";
    }
}
