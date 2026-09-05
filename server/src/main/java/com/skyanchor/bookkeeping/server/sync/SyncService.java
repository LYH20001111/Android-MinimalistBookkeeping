package com.skyanchor.bookkeeping.server.sync;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skyanchor.bookkeeping.server.auth.AuthUser;
import com.skyanchor.bookkeeping.server.auth.repo.UserRepository;
import com.skyanchor.bookkeeping.server.common.ApiException;
import com.skyanchor.bookkeeping.server.common.HashUtil;
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
import com.skyanchor.bookkeeping.server.sync.dto.SyncDtos.Counts;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * 双向增量同步服务（基线第 22 章）。
 *
 * <p>Push：baseVersion 乐观并发控制 → 无冲突直接 version+1；有冲突走 LWW 裁决
 * （接收序，见 {@link Lww}）并写 conflict log。每个实体写一条 sync_changes。
 * 处理顺序固定为 分类 → 账户 → 交易/预算/周期，降低悬挂引用概率。
 * <p>Pull：游标增量，只返回每个 syncId 的最新变更，载荷取业务行当前状态。
 * <p>用户数据严格按 JWT 的 userId 隔离（基线第 31 章）。
 */
@Service
public class SyncService {

    private static final Logger log = LoggerFactory.getLogger(SyncService.class);

    private static final int MAX_PULL_LIMIT = 500;
    private static final String ERR_MISSING_REFERENCE = "MISSING_REFERENCE";
    private static final String ERR_VALIDATION = "VALIDATION_ERROR";

    private static final List<String> PUSH_ORDER = List.of(
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
        this.objectMapper = objectMapper;
    }

    // ===== Push =====

    @Transactional
    public PushResponse push(AuthUser user, PushRequest request) {
        List<PushResultItem> results = new ArrayList<>();
        List<PushItem> items = request.changes() == null ? List.of() : request.changes();
        List<PushItem> ordered = new ArrayList<>(items);
        ordered.sort(Comparator.comparingInt(i -> {
            int idx = PUSH_ORDER.indexOf(i.entityType());
            return idx < 0 ? PUSH_ORDER.size() : idx;
        }));

        for (PushItem item : ordered) {
            results.add(applyItem(user, item));
        }
        log.info("sync push user={} device={} items={} conflicts={}", user.userId(),
                maskDeviceId(user.deviceId()), items.size(),
                results.stream().filter(PushResultItem::conflicted).count());
        return new PushResponse(results, System.currentTimeMillis());
    }

    private PushResultItem applyItem(AuthUser user, PushItem item) {
        long userId = user.userId();
        try {
            validateItem(item);
        } catch (ApiException e) {
            return rejected(item, ERR_VALIDATION);
        }
        Instant now = Instant.now();
        return switch (item.entityType()) {
            case SyncDtos.ENTITY_CATEGORY -> applyCategory(user, item, now);
            case SyncDtos.ENTITY_ACCOUNT -> applyAccount(user, item, now);
            case SyncDtos.ENTITY_TRANSACTION -> applyTransaction(user, item, now);
            case SyncDtos.ENTITY_BUDGET -> applyBudget(user, item, now);
            case SyncDtos.ENTITY_RECURRING -> applyRecurring(user, item, now);
            default -> rejected(item, ERR_VALIDATION);
        };
    }

    private PushResultItem applyCategory(AuthUser user, PushItem item, Instant now) {
        CategoryRow row = categoryRepository.findByUserIdAndSyncId(user.userId(), item.syncId())
                .orElse(null);
        if (row == null) {
            if (SyncChangeRow.OP_DELETE.equals(item.operation())) {
                return noop(item, now);
            }
            // 多设备重名合并（开发计划完成备注 14）：两台设备各自初始化的默认分类
            // 名字相同但 syncId 不同，直接新建会产生重复。同用户已有同名同类型
            // 分类时不再新建，返回 mergedInto 让客户端把本地行身份重映射到已有行。
            CategoryRow twin = findCategoryTwin(user.userId(), item.payload());
            if (twin != null) {
                return new PushResultItem(item.entityType(), item.syncId(), true, false,
                        twin.getVersion(), twin.getServerReceivedAt().toEpochMilli(),
                        toPayload(SyncDtos.ENTITY_CATEGORY, twin), null, twin.getSyncId());
            }
            CategoryRow created = new CategoryRow();
            created.setUserId(user.userId());
            created.setSyncId(item.syncId());
            created.setCreatedAt(now);
            applyCategoryFields(created, item.payload());
            return finishCreate(user, item, created, now, categoryRepository::save);
        }
        Lww.Decision decision = decide(user, item, row, now);
        if (decision == Lww.Decision.CONFLICT_SERVER_WINS) {
            return serverWon(item, row, now);
        }
        applyDeleteOrFields(row, item, () -> applyCategoryFields(row, item.payload()));
        return finishUpdate(user, item, row, now,
                decision == Lww.Decision.CONFLICT_INCOMING_WINS, categoryRepository::save);
    }

    private PushResultItem applyAccount(AuthUser user, PushItem item, Instant now) {
        AccountRow row = accountRepository.findByUserIdAndSyncId(user.userId(), item.syncId())
                .orElse(null);
        if (row == null) {
            if (SyncChangeRow.OP_DELETE.equals(item.operation())) {
                return noop(item, now);
            }
            // 同名账户合并（同上）
            AccountRow twin = findAccountTwin(user.userId(), item.payload());
            if (twin != null) {
                return new PushResultItem(item.entityType(), item.syncId(), true, false,
                        twin.getVersion(), twin.getServerReceivedAt().toEpochMilli(),
                        toPayload(SyncDtos.ENTITY_ACCOUNT, twin), null, twin.getSyncId());
            }
            AccountRow created = new AccountRow();
            created.setUserId(user.userId());
            created.setSyncId(item.syncId());
            created.setCreatedAt(now);
            applyAccountFields(created, item.payload());
            return finishCreate(user, item, created, now, accountRepository::save);
        }
        Lww.Decision decision = decide(user, item, row, now);
        if (decision == Lww.Decision.CONFLICT_SERVER_WINS) {
            return serverWon(item, row, now);
        }
        applyDeleteOrFields(row, item, () -> applyAccountFields(row, item.payload()));
        return finishUpdate(user, item, row, now,
                decision == Lww.Decision.CONFLICT_INCOMING_WINS, accountRepository::save);
    }

    private PushResultItem applyTransaction(AuthUser user, PushItem item, Instant now) {
        TransactionRow row = transactionRepository.findByUserIdAndSyncId(user.userId(), item.syncId())
                .orElse(null);
        if (row == null) {
            if (SyncChangeRow.OP_DELETE.equals(item.operation())) {
                return noop(item, now);
            }
            String refError = validateTransactionRefs(user.userId(), item.payload());
            if (refError != null) {
                return rejected(item, refError);
            }
            TransactionRow created = new TransactionRow();
            created.setUserId(user.userId());
            created.setSyncId(item.syncId());
            created.setCreatedAt(now);
            applyTransactionFields(created, item.payload());
            return finishCreate(user, item, created, now, transactionRepository::save);
        }
        Lww.Decision decision = decide(user, item, row, now);
        if (decision == Lww.Decision.CONFLICT_SERVER_WINS) {
            return serverWon(item, row, now);
        }
        String refError = validateTransactionRefs(user.userId(), item.payload());
        if (refError != null) {
            return rejected(item, refError);
        }
        applyDeleteOrFields(row, item, () -> applyTransactionFields(row, item.payload()));
        return finishUpdate(user, item, row, now,
                decision == Lww.Decision.CONFLICT_INCOMING_WINS, transactionRepository::save);
    }

    private PushResultItem applyBudget(AuthUser user, PushItem item, Instant now) {
        BudgetRow row = budgetRepository.findByUserIdAndSyncId(user.userId(), item.syncId())
                .orElse(null);
        if (row == null) {
            if (SyncChangeRow.OP_DELETE.equals(item.operation())) {
                return noop(item, now);
            }
            String refError = validateCategoryRef(user.userId(), item.payload().categorySyncId);
            if (refError != null) {
                return rejected(item, refError);
            }
            // 同（年，月，分类）预算合并（同上）
            BudgetRow twin = findBudgetTwin(user.userId(), item.payload());
            if (twin != null) {
                return new PushResultItem(item.entityType(), item.syncId(), true, false,
                        twin.getVersion(), twin.getServerReceivedAt().toEpochMilli(),
                        toPayload(SyncDtos.ENTITY_BUDGET, twin), null, twin.getSyncId());
            }
            BudgetRow created = new BudgetRow();
            created.setUserId(user.userId());
            created.setSyncId(item.syncId());
            created.setCreatedAt(now);
            applyBudgetFields(created, item.payload());
            return finishCreate(user, item, created, now, budgetRepository::save);
        }
        Lww.Decision decision = decide(user, item, row, now);
        if (decision == Lww.Decision.CONFLICT_SERVER_WINS) {
            return serverWon(item, row, now);
        }
        String refError = validateCategoryRef(user.userId(), item.payload().categorySyncId);
        if (refError != null) {
            return rejected(item, refError);
        }
        applyDeleteOrFields(row, item, () -> applyBudgetFields(row, item.payload()));
        return finishUpdate(user, item, row, now,
                decision == Lww.Decision.CONFLICT_INCOMING_WINS, budgetRepository::save);
    }

    private PushResultItem applyRecurring(AuthUser user, PushItem item, Instant now) {
        RecurringRow row = recurringRepository.findByUserIdAndSyncId(user.userId(), item.syncId())
                .orElse(null);
        if (row == null) {
            if (SyncChangeRow.OP_DELETE.equals(item.operation())) {
                return noop(item, now);
            }
            String refError = validateRecurringRefs(user.userId(), item.payload());
            if (refError != null) {
                return rejected(item, refError);
            }
            RecurringRow created = new RecurringRow();
            created.setUserId(user.userId());
            created.setSyncId(item.syncId());
            created.setCreatedAt(now);
            applyRecurringFields(created, item.payload());
            return finishCreate(user, item, created, now, recurringRepository::save);
        }
        Lww.Decision decision = decide(user, item, row, now);
        if (decision == Lww.Decision.CONFLICT_SERVER_WINS) {
            return serverWon(item, row, now);
        }
        String refError = validateRecurringRefs(user.userId(), item.payload());
        if (refError != null) {
            return rejected(item, refError);
        }
        applyDeleteOrFields(row, item, () -> applyRecurringFields(row, item.payload()));
        return finishUpdate(user, item, row, now,
                decision == Lww.Decision.CONFLICT_INCOMING_WINS, recurringRepository::save);
    }

    // ===== Push 公共步骤 =====

    /** 同用户下同名（忽略大小写与首尾空格）同类型的有效分类；入参不完整时视为无孪生。 */
    private CategoryRow findCategoryTwin(Long userId, SyncPayload payload) {
        if (payload == null || payload.name == null || payload.name.isBlank()
                || payload.type == null) {
            return null;
        }
        return categoryRepository
                .findByUserIdAndTypeAndNameIgnoreCase(userId, payload.type, payload.name.trim())
                .orElse(null);
    }

    /** 同用户下同名账户。 */
    private AccountRow findAccountTwin(Long userId, SyncPayload payload) {
        if (payload == null || payload.name == null || payload.name.isBlank()) {
            return null;
        }
        return accountRepository.findByUserIdAndNameIgnoreCase(userId, payload.name.trim())
                .orElse(null);
    }

    /** 同用户同（年，月，分类）预算；总预算哨兵在存储层是空串。 */
    private BudgetRow findBudgetTwin(Long userId, SyncPayload payload) {
        if (payload == null || payload.year == null || payload.month == null) {
            return null;
        }
        String categorySyncId = payload.categorySyncId == null ? "" : payload.categorySyncId;
        return budgetRepository
                .findByUserIdAndYearAndMonthAndCategorySyncId(userId, payload.year,
                        payload.month, categorySyncId)
                .orElse(null);
    }

    /** DELETE 只置软删位（payload 允许为 null）；UPSERT 应用业务字段。 */
    private void applyDeleteOrFields(SyncRow row, PushItem item, Runnable fieldApplier) {
        if (SyncChangeRow.OP_DELETE.equals(item.operation())) {
            row.setDeleted(true);
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
        writeChangeLog(user.userId(), item, row, now);
        return accepted(item, 1, now, toPayload(item.entityType(), row), null);
    }

    private <T extends SyncRow> PushResultItem finishUpdate(AuthUser user, PushItem item, T row,
                                                            Instant now, boolean conflicted,
                                                            Function<T, T> saver) {
        if (conflicted) {
            writeConflictLog(user, item, row, now);
        }
        row.setVersion(row.getVersion() + 1);
        row.setServerReceivedAt(now);
        saver.apply(row);
        writeChangeLog(user.userId(), item, row, now);
        return new PushResultItem(item.entityType(), item.syncId(), true, conflicted,
                row.getVersion(), now.toEpochMilli(), toPayload(item.entityType(), row), null, null);
    }

    /** DELETE 未知 syncId：幂等 no-op 成功（该身份从未上云，其他设备不可能持有）。 */
    private PushResultItem noop(PushItem item, Instant now) {
        SyncPayload payload = new SyncPayload();
        payload.isDeleted = true;
        return accepted(item, 0, now, payload, null);
    }

    private PushResultItem serverWon(PushItem item, SyncRow row, Instant now) {
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

    private void writeChangeLog(Long userId, PushItem item, SyncRow row, Instant now) {
        SyncChangeRow change = new SyncChangeRow();
        change.setUserId(userId);
        change.setEntityType(item.entityType());
        change.setSyncId(item.syncId());
        change.setVersion(row.getVersion());
        change.setOperation(item.operation());
        change.setServerReceivedAt(now);
        changeRepository.save(change);
    }

    private void writeConflictLog(AuthUser user, PushItem item, SyncRow row, Instant now) {
        ConflictLogRow conflict = new ConflictLogRow();
        conflict.setUserId(user.userId());
        conflict.setEntityType(item.entityType());
        conflict.setSyncId(item.syncId());
        conflict.setClientDeviceId(user.deviceId() == null ? "" : user.deviceId());
        conflict.setBaseVersion(item.baseVersion());
        conflict.setServerVersion(row.getVersion());
        conflict.setClientPayloadDigest(digest(item.payload()));
        conflict.setServerPayloadDigest(digest(toPayload(item.entityType(), row)));
        conflict.setWinner(ConflictLogRow.WINNER_CLIENT);
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
    }

    // ===== 引用校验（服务层，不建跨表外键；软删行仍可被引用） =====

    private String validateTransactionRefs(Long userId, SyncPayload payload) {
        String err = validateCategoryRef(userId, payload.categorySyncId);
        if (err != null) {
            return err;
        }
        err = validateAccountRef(userId, payload.accountSyncId);
        if (err != null) {
            return err;
        }
        return validateAccountRef(userId, payload.transferAccountSyncId);
    }

    private String validateRecurringRefs(Long userId, SyncPayload payload) {
        String err = validateCategoryRef(userId, payload.categorySyncId);
        if (err != null) {
            return err;
        }
        return validateAccountRef(userId, payload.accountSyncId);
    }

    private String validateCategoryRef(Long userId, String categorySyncId) {
        if (categorySyncId == null || categorySyncId.isBlank()) {
            return null;
        }
        return categoryRepository.findByUserIdAndSyncId(userId, categorySyncId).isPresent()
                ? null : ERR_MISSING_REFERENCE;
    }

    private String validateAccountRef(Long userId, String accountSyncId) {
        if (accountSyncId == null || accountSyncId.isBlank()) {
            return null;
        }
        return accountRepository.findByUserIdAndSyncId(userId, accountSyncId).isPresent()
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
    }

    private void applyBudgetFields(BudgetRow row, SyncPayload payload) {
        row.setYear(requireInt(payload.year, "year"));
        row.setMonth(requireInt(payload.month, "month"));
        row.setCategorySyncId(payload.categorySyncId == null ? "" : payload.categorySyncId);
        row.setAmount(orZero(payload.amount));
        row.setClientUpdatedAt(orZero(payload.clientUpdatedAt));
        row.setDeleted(payload.isDeleted != null && payload.isDeleted);
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
    }

    // ===== 载荷组装 =====

    private SyncPayload toPayload(String entityType, SyncRow row) {
        SyncPayload payload = new SyncPayload();
        payload.clientUpdatedAt = row.getClientUpdatedAt();
        payload.isDeleted = row.isDeleted();
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
        long cursor = Math.max(0, request.sinceChangeId());
        int limit = request.limit() <= 0 || request.limit() > MAX_PULL_LIMIT
                ? MAX_PULL_LIMIT : request.limit();
        List<SyncChangeRow> changes = changeRepository.findLatestChangesAfter(user.userId(), cursor);
        boolean hasMore = changes.size() > limit;
        List<SyncChangeRow> page = hasMore ? changes.subList(0, limit) : changes;

        List<ChangeItem> items = new ArrayList<>();
        long lastChangeId = cursor;
        for (SyncChangeRow change : page) {
            SyncRow row = findRow(user.userId(), change.getEntityType(), change.getSyncId());
            if (row == null) {
                // 行已被清理（如账号数据重置）：跳过该变更，游标自然越过
                lastChangeId = change.getId();
                continue;
            }
            lastChangeId = change.getId();
            items.add(new ChangeItem(change.getId(), change.getEntityType(), change.getSyncId(),
                    change.getOperation(), row.getVersion(),
                    row.getServerReceivedAt().toEpochMilli(),
                    toPayload(change.getEntityType(), row)));
        }
        return new PullResponse(items, lastChangeId, hasMore, System.currentTimeMillis());
    }

    private SyncRow findRow(Long userId, String entityType, String syncId) {
        return switch (entityType) {
            case SyncDtos.ENTITY_CATEGORY ->
                    categoryRepository.findByUserIdAndSyncId(userId, syncId).orElse(null);
            case SyncDtos.ENTITY_ACCOUNT ->
                    accountRepository.findByUserIdAndSyncId(userId, syncId).orElse(null);
            case SyncDtos.ENTITY_TRANSACTION ->
                    transactionRepository.findByUserIdAndSyncId(userId, syncId).orElse(null);
            case SyncDtos.ENTITY_BUDGET ->
                    budgetRepository.findByUserIdAndSyncId(userId, syncId).orElse(null);
            case SyncDtos.ENTITY_RECURRING ->
                    recurringRepository.findByUserIdAndSyncId(userId, syncId).orElse(null);
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
        return new StatusResponse(System.currentTimeMillis(), verified);
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
