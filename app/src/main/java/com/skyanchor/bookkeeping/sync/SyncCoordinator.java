package com.skyanchor.bookkeeping.sync;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.skyanchor.bookkeeping.data.database.AppDatabase;
import com.skyanchor.bookkeeping.data.entity.AccountEntity;
import com.skyanchor.bookkeeping.data.entity.BudgetEntity;
import com.skyanchor.bookkeeping.data.entity.CategoryEntity;
import com.skyanchor.bookkeeping.data.entity.LedgerEntity;
import com.skyanchor.bookkeeping.data.entity.RecurringTransactionEntity;
import com.skyanchor.bookkeeping.data.entity.SyncChangeQueueEntity;
import com.skyanchor.bookkeeping.data.entity.SyncCursorEntity;
import com.skyanchor.bookkeeping.data.entity.SyncEntityTypes;
import com.skyanchor.bookkeeping.data.entity.SyncEventEntity;
import com.skyanchor.bookkeeping.data.entity.SyncStateEntity;
import com.skyanchor.bookkeeping.data.entity.TransactionEntity;
import com.skyanchor.bookkeeping.data.remote.ApiClient;
import com.skyanchor.bookkeeping.data.remote.ApiDtos;
import com.skyanchor.bookkeeping.data.remote.ApiException;
import com.skyanchor.bookkeeping.data.remote.ApiService;
import com.skyanchor.bookkeeping.data.remote.TokenStore;
import com.skyanchor.bookkeeping.data.repository.BookkeepingRepository;
import com.skyanchor.bookkeeping.util.Callback;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import retrofit2.Response;

/**
 * 同步协调器（基线第 12、22 章）：同一设备同一时刻只允许一个同步流程；
 * 标准流程 = 先 Push 后 Pull；同步期间允许继续记账，旧快照不覆盖新本地修改。
 *
 * <p>线程模型：所有数据库操作经 {@link BookkeepingRepository#runOnIo}（单线程），
 * 与业务写路径天然串行——ack 判断与队列清理之间不可能插入新的业务写，
 * 这是「双重护栏」成立的前提。
 *
 * <p>失败语义：网络/服务器问题 → 状态置 WAITING_RETRY / SERVER_UNAVAILABLE，
 * 由 SyncScheduler 按指数退避调度；Refresh Token 失效 → AUTH_REQUIRED，
 * 本地功能与队列完全不受影响（基线第 24 章）。
 */
public class SyncCoordinator {

    /** 同步状态机（基线第 23.1 章）。 */
    public enum Status {
        IDLE, SYNCING, WAITING_NETWORK, WAITING_RETRY,
        AUTH_REQUIRED, SERVER_UNAVAILABLE, SUCCESS, ERROR
    }

    private static final int PUSH_BATCH = 200;
    private static final int PULL_LIMIT = 200;
    private static final int MAX_PULL_PAGES = 50;

    private final AppDatabase database;
    private final BookkeepingRepository repository;
    private final ApiClient apiClient;
    private final TokenStore tokenStore;
    private final SyncEnqueuer enqueuer;

    private final MutableLiveData<Status> status = new MutableLiveData<>(Status.IDLE);
    /** 成员关系变化通知（V3.2 基线第 25 章：被移出账本 / 角色变化，下次同步后提示）。 */
    private final MutableLiveData<String> notice = new MutableLiveData<>();
    private volatile boolean running;
    private volatile boolean pendingAgain;
    private volatile int failedRounds;
    private volatile String lastError;

    /** 一轮同步的计数器（诊断用，V3.1 基线第 23/24 章）。 */
    private static final class RoundCounters {
        int pushed;
        int pulled;
        int conflicts;
    }

    public SyncCoordinator(@NonNull AppDatabase database,
                           @NonNull BookkeepingRepository repository,
                           @NonNull ApiClient apiClient,
                           @NonNull TokenStore tokenStore,
                           @NonNull SyncEnqueuer enqueuer) {
        this.database = database;
        this.repository = repository;
        this.apiClient = apiClient;
        this.tokenStore = tokenStore;
        this.enqueuer = enqueuer;
    }

    // ===== 对外状态 =====

    @NonNull
    public LiveData<Status> observeStatus() {
        return status;
    }

    /** 成员关系变化通知（一次性事件流，UI 弹提示或横幅）。 */
    @NonNull
    public LiveData<String> observeNotice() {
        return notice;
    }

    private void postNotice(@NonNull String message) {
        notice.postValue(message);
    }

    @NonNull
    public LiveData<Integer> observePendingCount() {
        return database.syncChangeQueueDao().observePendingCount();
    }

    @NonNull
    public LiveData<SyncStateEntity> observeState() {
        return database.syncStateDao().observe();
    }

    @Nullable
    public Status currentStatus() {
        return status.getValue();
    }

    public boolean isSyncing() {
        return running;
    }

    public boolean isSyncEnabled() {
        SyncStateEntity state = database.syncStateDao().get();
        return state != null && state.syncEnabled;
    }

    public boolean hasBootstrapCompleted() {
        String email = tokenStore.getAccountEmail();
        if (email == null) {
            return false;
        }
        // V3.2：任一账本存在游标即代表已完成首次同步（游标已按账本拆分）
        return database.syncCursorDao().countForEmail(email) > 0;
    }

    // ===== 开关（基线第 7 章） =====

    /** 打开 / 关闭云端同步。关闭 = 停触发，不清数据、不退登录。 */
    public void setSyncEnabled(boolean enabled) {
        repository.runOnIo(() -> {
            SyncStateEntity state = requireState();
            state.syncEnabled = enabled;
            state.status = Status.IDLE.name();
            database.syncStateDao().upsert(state);
            postStatus(Status.IDLE);
            if (enabled && hasBootstrapCompleted()) {
                // 已完成首次同步的账号：打开即进入增量同步（未完成的走同步中心确认流程）
                notifyReady();
            }
        });
    }

    /** 用户确认“服务器已恢复”横幅：清除提示位（recovered_at 置 0）。 */
    public void dismissRecoveredNotice() {
        repository.runOnIo(() -> {
            SyncStateEntity state = requireState();
            if (state.recoveredAt != 0) {
                state.recoveredAt = 0;
                database.syncStateDao().upsert(state);
            }
        });
    }

    /** 开启同步前的初始化检查（基线 7.2）：是否已登录 / 邮箱已验证 / 服务器已配置。 */
    public boolean preflightReady() {
        return tokenStore.isLoggedIn() && tokenStore.isEmailVerified()
                && apiClient.api() != null;
    }

    /** 主动探测服务器是否可达并更新持久化状态（保存 URL 后调用，基线第 2 章）。 */
    public void checkServerStatus() {
        repository.runOnIo(() -> {
            ApiService api = apiClient.api();
            Status newStatus;
            if (api == null) {
                newStatus = Status.SERVER_UNAVAILABLE;
            } else {
                try {
                    Response<ApiDtos.StatusResponse> response = api.status().execute();
                    newStatus = response.isSuccessful() ? Status.IDLE : Status.SERVER_UNAVAILABLE;
                } catch (IOException e) {
                    newStatus = Status.SERVER_UNAVAILABLE;
                }
            }
            postStatus(newStatus);
            SyncStateEntity state = requireState();
            state.status = newStatus.name();
            database.syncStateDao().upsert(state);
        });
    }

    // ===== 触发入口 =====

    /** 各触发源统一入口；运行中只置 pendingAgain，不并发创建任务（基线 12.1）。 */
    public void requestSync(boolean manual) {
        if (running) {
            pendingAgain = true;
            return;
        }
        if (manual) {
            failedRounds = 0; // 手动同步不受退避限制（基线 10.3）
        }
        repository.runOnIo(this::runSyncLoop);
    }

    /** 首次同步：确认后全量 Push + 全量 Pull（基线第 8、25 章）。 */
    public void confirmBootstrap(@NonNull Callback<Boolean> callback) {
        repository.runOnIo(() -> {
            try {
                // 记录本地账本绑定的云同步账号（V3.1 基线第 30 章，防隐式串账）
                String boundEmail = tokenStore.getAccountEmail();
                SyncStateEntity bound = requireState();
                if (bound.boundAccountEmail == null
                        || !bound.boundAccountEmail.equals(boundEmail)) {
                    bound.boundAccountEmail = boundEmail;
                    database.syncStateDao().upsert(bound);
                }
                repairSyncIds();
                mergeDuplicateRows();
                enqueueEverythingForBootstrap();
                runSyncLoop();
                postMainCallback(callback, hasBootstrapCompleted());
            } catch (Exception e) {
                postMainError(callback, e);
            }
        });
    }

    // ===== 同步主流程 =====

    private void runSyncLoop() {
        if (running) {
            pendingAgain = true;
            return;
        }
        if (!isSyncEnabled() || !tokenStore.isLoggedIn()) {
            postStatus(Status.IDLE);
            return;
        }
        ApiService api = apiClient.api();
        if (api == null) {
            postStatus(Status.SERVER_UNAVAILABLE);
            persistState(Status.SERVER_UNAVAILABLE, null, 0);
            return;
        }
        running = true;
        pendingAgain = false;
        postStatus(Status.SYNCING);
        long startedAt = System.currentTimeMillis();
        RoundCounters counters = new RoundCounters();
        try {
            repairSyncIds();
            mergeDuplicateRows();
            // V3.2：先对账成员关系（新账本 / 角色变化 / 被移出），再 Push、按账本逐个 Pull
            reconcileLedgers(api);
            counters.conflicts = pushPending(api, counters);
            pullAllLedgers(api, counters);
            failedRounds = 0;
            lastError = null;
            finishRound(Status.SUCCESS, counters, startedAt);
        } catch (ApiException e) {
            lastError = e.getMessage();
            recordEvent(startedAt, status.getValue(), counters, e.getMessage());
            if (e.isAuthRequired()) {
                // Refresh Token 已失效：本地功能照常，重新登录后恢复同步（基线 24.3）
                postStatus(Status.AUTH_REQUIRED);
                persistState(Status.AUTH_REQUIRED, e.getMessage(), counters.conflicts);
            } else if ("USER_DISABLED".equals(e.code)) {
                // 账号被服务器禁用：明确语义，不映射为网络异常（V3.2 基线 16.1）
                failedRounds++;
                postStatus(Status.ERROR);
                persistState(Status.ERROR, e.getMessage(), counters.conflicts);
            } else if (e.isNetworkLevel()) {
                failedRounds++;
                postStatus(Status.SERVER_UNAVAILABLE);
                persistState(Status.SERVER_UNAVAILABLE, e.getMessage(), counters.conflicts);
                retryLater();
            } else {
                failedRounds++;
                postStatus(Status.WAITING_RETRY);
                persistState(Status.WAITING_RETRY, e.getMessage(), counters.conflicts);
                retryLater();
            }
        } catch (Exception e) {
            lastError = e.getMessage();
            failedRounds++;
            postStatus(Status.ERROR);
            recordEvent(startedAt, Status.ERROR, counters, e.getMessage());
            persistState(Status.ERROR, e.getMessage(), counters.conflicts);
            retryLater();
        } finally {
            running = false;
        }
        // 同步过程中产生了新变更：继续下一轮（基线 22.12）
        if (pendingAgain && isSyncEnabled()) {
            pendingAgain = false;
            repository.runOnIo(this::runSyncLoop);
        }
    }

    /** Push 全部到期队列项，返回冲突条数（基线第 22 章 3-7 步）。 */
    private int pushPending(ApiService api, RoundCounters counters)
            throws ApiException, IOException {
        int conflictCount = 0;
        while (true) {
            List<SyncChangeQueueEntity> due = database.syncChangeQueueDao()
                    .takeDue(System.currentTimeMillis(), PUSH_BATCH);
            List<SyncChangeQueueEntity> batch = dedupe(due);
            if (batch.isEmpty()) {
                break;
            }
            Map<String, PushSnapshot> snapshots = new LinkedHashMap<>();
            List<ApiDtos.PushItem> items = new ArrayList<>();
            for (SyncChangeQueueEntity entry : batch) {
                ApiDtos.PushItem item = buildPushItem(entry, snapshots);
                if (item != null) {
                    items.add(item);
                }
            }
            if (items.isEmpty()) {
                break;
            }
            Response<ApiDtos.PushResponse> response = api.push(new ApiDtos.PushRequest(items)).execute();
            if (!response.isSuccessful() || response.body() == null) {
                throw ApiClient.toApiError(response);
            }
            handleRecoveryEpoch(response.body().recoveryEpoch);
            conflictCount += applyPushResults(response.body(), snapshots, counters);
            if (due.size() < PUSH_BATCH && batch.size() < PUSH_BATCH) {
                break;
            }
        }
        return conflictCount;
    }

    /** 队列按 (entityType, syncId) 去重，保留最早一条（其 created_at 代表变更起点）。 */
    @NonNull
    private static List<SyncChangeQueueEntity> dedupe(@NonNull List<SyncChangeQueueEntity> due) {
        Map<String, SyncChangeQueueEntity> unique = new LinkedHashMap<>();
        for (SyncChangeQueueEntity entry : due) {
            unique.putIfAbsent(entry.entityType + "|" + entry.syncId, entry);
        }
        List<SyncChangeQueueEntity> list = new ArrayList<>(unique.values());
        list.sort(Comparator.comparingInt(a -> SyncPayloadMapper.orderOf(a.entityType)));
        return list;
    }

    /** 账本本地行 id → 账本 syncId（推送项寻址依据，基线第 10.1 章）。 */
    @Nullable
    private String ledgerSyncIdOf(long ledgerRowId) {
        LedgerEntity ledger = database.ledgerDao().getById(ledgerRowId);
        return ledger != null ? ledger.syncId : null;
    }

    /**
     * 用实体当前状态构建推送项（快照在发送前重读，基线 12.2）。
     * 实体已被物理清除且从未同步 → 丢弃队列项；账本行丢失（无法寻址）同样丢弃。
     */
    @Nullable
    private ApiDtos.PushItem buildPushItem(@NonNull SyncChangeQueueEntity entry,
                                           @NonNull Map<String, PushSnapshot> snapshots) {
        String syncId = entry.syncId;
        switch (entry.entityType) {
            case SyncEntityTypes.LEDGER: {
                LedgerEntity entity = database.ledgerDao().getBySyncId(syncId);
                if (entity == null) {
                    database.syncChangeQueueDao().clearFor(entry.entityType, syncId);
                    return null;
                }
                ApiDtos.SyncPayload payload = SyncPayloadMapper.toPayload(entity);
                snapshots.put(key(entry), new PushSnapshot(entry.entityType, syncId,
                        entity.version, payload, entity.isDeleted, 0L));
                return new ApiDtos.PushItem(entry.entityType, syncId,
                        entity.isDeleted ? SyncEntityTypes.OP_DELETE : SyncEntityTypes.OP_UPSERT,
                        entity.version, syncId, payload);
            }
            case SyncEntityTypes.CATEGORY: {
                CategoryEntity entity = database.categoryDao().getBySyncId(syncId);
                if (entity == null) {
                    return dropOrDelete(entry, snapshots);
                }
                String ledgerSyncId = ledgerSyncIdOf(entity.ledgerId);
                if (ledgerSyncId == null) {
                    database.syncChangeQueueDao().clearFor(entry.entityType, syncId);
                    return null;
                }
                SyncPayloadMapper.ensureSyncId(entity);
                ApiDtos.SyncPayload payload = SyncPayloadMapper.toPayload(entity, database);
                snapshots.put(key(entry), new PushSnapshot(entry.entityType, syncId,
                        entity.version, payload, entity.isDeleted, entity.ledgerId));
                return new ApiDtos.PushItem(entry.entityType, syncId,
                        entity.isDeleted ? SyncEntityTypes.OP_DELETE : SyncEntityTypes.OP_UPSERT,
                        entity.version, ledgerSyncId, payload);
            }
            case SyncEntityTypes.ACCOUNT: {
                AccountEntity entity = database.accountDao().getBySyncId(syncId);
                if (entity == null) {
                    return dropOrDelete(entry, snapshots);
                }
                String ledgerSyncId = ledgerSyncIdOf(entity.ledgerId);
                if (ledgerSyncId == null) {
                    database.syncChangeQueueDao().clearFor(entry.entityType, syncId);
                    return null;
                }
                ApiDtos.SyncPayload payload = SyncPayloadMapper.toPayload(entity, database);
                snapshots.put(key(entry), new PushSnapshot(entry.entityType, syncId,
                        entity.version, payload, entity.isDeleted, entity.ledgerId));
                return new ApiDtos.PushItem(entry.entityType, syncId,
                        entity.isDeleted ? SyncEntityTypes.OP_DELETE : SyncEntityTypes.OP_UPSERT,
                        entity.version, ledgerSyncId, payload);
            }
            case SyncEntityTypes.TRANSACTION: {
                TransactionEntity entity = database.transactionDao().getBySyncId(syncId);
                if (entity == null) {
                    return dropOrDelete(entry, snapshots);
                }
                String ledgerSyncId = ledgerSyncIdOf(entity.ledgerId);
                if (ledgerSyncId == null) {
                    database.syncChangeQueueDao().clearFor(entry.entityType, syncId);
                    return null;
                }
                ApiDtos.SyncPayload payload = SyncPayloadMapper.toPayload(entity, database);
                snapshots.put(key(entry), new PushSnapshot(entry.entityType, syncId,
                        entity.version, payload, entity.isDeleted, entity.ledgerId));
                return new ApiDtos.PushItem(entry.entityType, syncId,
                        entity.isDeleted ? SyncEntityTypes.OP_DELETE : SyncEntityTypes.OP_UPSERT,
                        entity.version, ledgerSyncId, payload);
            }
            case SyncEntityTypes.BUDGET: {
                // 队列中没有本地 id，按 syncId 全表定位
                BudgetEntity entity = findBudgetBySyncId(syncId);
                if (entity == null) {
                    return dropOrDelete(entry, snapshots);
                }
                String ledgerSyncId = ledgerSyncIdOf(entity.ledgerId);
                if (ledgerSyncId == null) {
                    database.syncChangeQueueDao().clearFor(entry.entityType, syncId);
                    return null;
                }
                ApiDtos.SyncPayload payload = SyncPayloadMapper.toPayload(entity, database);
                snapshots.put(key(entry), new PushSnapshot(entry.entityType, syncId,
                        entity.version, payload, entity.isDeleted, entity.ledgerId));
                return new ApiDtos.PushItem(entry.entityType, syncId,
                        entity.isDeleted ? SyncEntityTypes.OP_DELETE : SyncEntityTypes.OP_UPSERT,
                        entity.version, ledgerSyncId, payload);
            }
            case SyncEntityTypes.RECURRING: {
                RecurringTransactionEntity entity =
                        database.recurringTransactionDao().getBySyncId(syncId);
                if (entity == null) {
                    return dropOrDelete(entry, snapshots);
                }
                String ledgerSyncId = ledgerSyncIdOf(entity.ledgerId);
                if (ledgerSyncId == null) {
                    database.syncChangeQueueDao().clearFor(entry.entityType, syncId);
                    return null;
                }
                ApiDtos.SyncPayload payload = SyncPayloadMapper.toPayload(entity, database);
                snapshots.put(key(entry), new PushSnapshot(entry.entityType, syncId,
                        entity.version, payload, entity.isDeleted, entity.ledgerId));
                return new ApiDtos.PushItem(entry.entityType, syncId,
                        entity.isDeleted ? SyncEntityTypes.OP_DELETE : SyncEntityTypes.OP_UPSERT,
                        entity.version, ledgerSyncId, payload);
            }
            default:
                database.syncChangeQueueDao().clearFor(entry.entityType, syncId);
                return null;
        }
    }

    /**
     * 实体已被物理清除：无法解析账本归属（无寻址依据），直接丢弃队列项。
     * 服务器侧数据不受影响；物理清除只发生在本地清空 / 本地恢复等运维场景。
     */
    @Nullable
    private ApiDtos.PushItem dropOrDelete(@NonNull SyncChangeQueueEntity entry,
                                          @NonNull Map<String, PushSnapshot> snapshots) {
        database.syncChangeQueueDao().clearFor(entry.entityType, entry.syncId);
        return null;
    }

    /**
     * 应用 Push 结果（ack 双重护栏，开发计划备注 6）：
     * 仅当本地版本仍等于推送时的 baseVersion 且内容未变时，才回写服务器版本并清队列；
     * 否则丢弃 ack（服务器已接受的内容是合法历史版本），本地最新状态留给下一轮。
     */
    private int applyPushResults(@NonNull ApiDtos.PushResponse response,
                                 @NonNull Map<String, PushSnapshot> snapshots,
                                 @NonNull RoundCounters counters) {
        int[] conflicts = {0};
        database.runInTransaction(() -> {
            for (ApiDtos.PushResultItem result : response.results) {
                PushSnapshot snapshot = snapshots.get(result.entityType + "|" + result.syncId);
                if (snapshot == null) {
                    continue;
                }
                if (result.accepted && result.mergedInto != null
                        && !result.mergedInto.equals(result.syncId)) {
                    // 重名合并：本地行身份重映射到服务器已有实体（开发计划完成备注 14）
                    counters.pushed++;
                    applyMergedInto(result);
                } else if (result.accepted) {
                    if (result.conflicted) {
                        conflicts[0]++;
                    }
                    counters.pushed++;
                    ackSnapshot(snapshot, result);
                } else if ("CONFLICT_SERVER_WON".equals(result.errorCode)
                        && result.payload != null) {
                    // 服务器版本胜出（罕见：同毫秒边界）：接受服务器最终状态
                    conflicts[0]++;
                    applyServerPayload(result.entityType, result.syncId, result.payload,
                            result.version, result.serverReceivedAt, snapshot.ledgerRowId);
                    database.syncChangeQueueDao().clearFor(result.entityType, result.syncId);
                } else if (result.errorCode != null) {
                    // 逐项失败（如悬挂引用）：按退避留在队列，等引用方同步后再试
                    long nextRetry = System.currentTimeMillis()
                            + RetryPolicy.delayFor(1);
                    database.syncChangeQueueDao().markFailed(result.entityType, result.syncId,
                            1, result.errorCode, nextRetry);
                }
            }
        });
        return conflicts[0];
    }

    /** ack 护栏：版本一致 + 内容一致才落版本号（比较用载荷重新生成后做字段级相等）。 */
    private void ackSnapshot(@NonNull PushSnapshot snapshot,
                             @NonNull ApiDtos.PushResultItem result) {
        String syncId = snapshot.syncId;
        boolean unchanged;
        String op = snapshot.isDeleted ? SyncEntityTypes.OP_DELETE : SyncEntityTypes.OP_UPSERT;
        switch (snapshot.entityType) {
            case SyncEntityTypes.LEDGER: {
                LedgerEntity current = database.ledgerDao().getBySyncId(syncId);
                unchanged = current != null && current.version == snapshot.baseVersion
                        && current.isDeleted == snapshot.isDeleted
                        && payloadEquals(SyncPayloadMapper.toPayload(current), snapshot.payload);
                if (unchanged) {
                    current.version = result.version;
                    current.serverReceivedAt = result.serverReceivedAt;
                    database.ledgerDao().update(current);
                }
                break;
            }
            case SyncEntityTypes.CATEGORY: {
                CategoryEntity current = database.categoryDao().getBySyncId(syncId);
                unchanged = current != null && current.version == snapshot.baseVersion
                        && current.isDeleted == snapshot.isDeleted
                        && payloadEquals(SyncPayloadMapper.toPayload(current, database),
                        snapshot.payload);
                if (unchanged) {
                    current.version = result.version;
                    current.serverReceivedAt = result.serverReceivedAt;
                    database.categoryDao().update(current);
                }
                break;
            }
            case SyncEntityTypes.ACCOUNT: {
                AccountEntity current = database.accountDao().getBySyncId(syncId);
                unchanged = current != null && current.version == snapshot.baseVersion
                        && current.isDeleted == snapshot.isDeleted
                        && payloadEquals(SyncPayloadMapper.toPayload(current, database),
                        snapshot.payload);
                if (unchanged) {
                    current.version = result.version;
                    current.serverReceivedAt = result.serverReceivedAt;
                    database.accountDao().update(current);
                }
                break;
            }
            case SyncEntityTypes.TRANSACTION: {
                TransactionEntity current = database.transactionDao().getBySyncId(syncId);
                unchanged = current != null && current.version == snapshot.baseVersion
                        && current.isDeleted == snapshot.isDeleted
                        && payloadEquals(SyncPayloadMapper.toPayload(current, database),
                        snapshot.payload);
                if (unchanged) {
                    current.version = result.version;
                    current.serverReceivedAt = result.serverReceivedAt;
                    database.transactionDao().update(current);
                }
                break;
            }
            case SyncEntityTypes.BUDGET: {
                BudgetEntity current = findBudgetBySyncId(syncId);
                unchanged = current != null && current.version == snapshot.baseVersion
                        && current.isDeleted == snapshot.isDeleted
                        && payloadEquals(SyncPayloadMapper.toPayload(current, database),
                        snapshot.payload);
                if (unchanged) {
                    current.version = result.version;
                    current.serverReceivedAt = result.serverReceivedAt;
                    database.budgetDao().upsert(current);
                }
                break;
            }
            default: {
                RecurringTransactionEntity current =
                        database.recurringTransactionDao().getBySyncId(syncId);
                unchanged = current != null && current.version == snapshot.baseVersion
                        && current.isDeleted == snapshot.isDeleted
                        && payloadEquals(SyncPayloadMapper.toPayload(current, database),
                        snapshot.payload);
                if (unchanged) {
                    current.version = result.version;
                    current.serverReceivedAt = result.serverReceivedAt;
                    database.recurringTransactionDao().update(current);
                }
                break;
            }
        }
        if (unchanged) {
            database.syncChangeQueueDao().clearFor(snapshot.entityType, syncId);
        }
        // 未通过护栏：保留队列行，下一轮以最新状态重推（旧快照绝不覆盖本地，基线 12.2）
    }

    // ===== 账本对账 + Pull（V3.2 基线第 10、25 章） =====

    /**
     * 成员关系对账：以 sync/status 的 ledgerMemberships 为权威，插入新账本、
     * 更新角色、感知被移出（本地标记 REMOVED 并切走当前账本）。
     * 邀请的接受走 REST（我的邀请页），接受后的账本经这里落到本地。
     */
    private void reconcileLedgers(ApiService api) throws ApiException, IOException {
        Response<ApiDtos.StatusResponse> response = api.status().execute();
        if (!response.isSuccessful() || response.body() == null) {
            throw ApiClient.toApiError(response);
        }
        ApiDtos.StatusResponse statusResponse = response.body();
        handleRecoveryEpoch(statusResponse.recoveryEpoch);
        long now = System.currentTimeMillis();
        if (statusResponse.ledgerMemberships != null) {
            database.runInTransaction(() -> {
                for (ApiDtos.LedgerMembershipSummary summary : statusResponse.ledgerMemberships) {
                    reconcileMembership(summary, now);
                }
            });
        }
        // V3.1 → V3.2 升级遗留：空键游标挂到默认账本名下（默认账本身份确立后一次即可）
        String email = tokenStore.getAccountEmail();
        if (email != null) {
            SyncCursorEntity legacy = database.syncCursorDao().find(email, "");
            LedgerEntity defaultLedger = database.ledgerDao().getDefaultLedger();
            if (legacy != null && defaultLedger != null
                    && defaultLedger.syncId != null && !defaultLedger.syncId.isEmpty()
                    && !defaultLedger.syncId.equals("")) {
                database.syncCursorDao().renameKey(email, "", defaultLedger.syncId, now);
            }
        }
    }

    /** 单条成员关系对账（须处于 DB 事务内）。 */
    private void reconcileMembership(@NonNull ApiDtos.LedgerMembershipSummary summary,
                                     long now) {
        LedgerEntity local = database.ledgerDao().getBySyncId(summary.ledgerSyncId);
        boolean removed = "REMOVED".equals(summary.membershipStatus);
        if (local == null) {
            if (removed) {
                return; // 与本机无关的旧成员关系
            }
            LedgerEntity created = new LedgerEntity();
            created.syncId = summary.ledgerSyncId;
            created.name = summary.name != null ? summary.name : "";
            created.description = summary.description != null ? summary.description : "";
            created.currency = summary.currency != null ? summary.currency : "CNY";
            created.role = summary.role != null ? summary.role : LedgerEntity.ROLE_VIEWER;
            created.ownerUserId = summary.ownerUserId;
            created.isDefault = summary.isDefault;
            created.isArchived = summary.isArchived;
            created.isDeleted = summary.isDeleted;
            created.version = summary.version;
            created.serverReceivedAt = now;
            created.createdAt = now;
            database.ledgerDao().insert(created);
            postNotice("已加入账本「" + created.name + "」");
            return;
        }
        if (removed && !LedgerEntity.ROLE_REMOVED.equals(local.role)) {
            database.ledgerDao().markRemoved(local.syncId, now);
            postNotice("你已被移出账本「" + local.name + "」");
            switchAwayIfCurrent(local.id);
        } else if (!removed && !LedgerEntity.ROLE_REMOVED.equals(local.role)
                && summary.role != null && !summary.role.equals(local.role)) {
            local.role = summary.role;
            database.ledgerDao().update(local);
            postNotice("你在账本「" + local.name + "」中的角色变为 "
                    + roleLabel(summary.role));
        }
    }

    private void switchAwayIfCurrent(long ledgerRowId) {
        Long current = database.ledgerDao().getCurrentId();
        if (current != null && current == ledgerRowId) {
            List<LedgerEntity> remaining = database.ledgerDao().getActive();
            if (!remaining.isEmpty()) {
                database.ledgerDao().setCurrent(remaining.get(0).id);
            }
        }
    }

    @NonNull
    private static String roleLabel(@NonNull String role) {
        switch (role) {
            case LedgerEntity.ROLE_OWNER: return "所有者";
            case LedgerEntity.ROLE_ADMIN: return "管理员";
            case LedgerEntity.ROLE_MEMBER: return "成员";
            case LedgerEntity.ROLE_VIEWER: return "观察者";
            default: return role;
        }
    }

    /** 逐账本拉取：每个账本独立游标（基线第 10.2 章：账号 + 账本 + cursor）。 */
    private void pullAllLedgers(ApiService api, RoundCounters counters)
            throws ApiException, IOException {
        String email = tokenStore.getAccountEmail();
        if (email == null) {
            return;
        }
        for (LedgerEntity ledger : database.ledgerDao().getActive()) {
            try {
                pullLedger(api, email, ledger, counters);
            } catch (ApiException e) {
                if ("LEDGER_ACCESS_DENIED".equals(e.code)
                        || "LEDGER_NOT_FOUND".equals(e.code)) {
                    // 已被移出 / 账本不可达：本地隐藏并切走，其余账本继续同步
                    database.runInTransaction(() -> {
                        database.ledgerDao().markRemoved(ledger.syncId,
                                System.currentTimeMillis());
                        switchAwayIfCurrent(ledger.id);
                    });
                    postNotice("你已被移出账本「" + ledger.name + "」");
                    continue;
                }
                throw e;
            }
        }
    }

    private void pullLedger(@NonNull ApiService api, @NonNull String email,
                            @NonNull LedgerEntity ledger, @NonNull RoundCounters counters)
            throws ApiException, IOException {
        SyncCursorEntity cursorRow = database.syncCursorDao().find(email, ledger.syncId);
        long cursor = cursorRow != null ? cursorRow.lastChangeId : 0;
        long latestChangeId = cursor;
        List<ApiDtos.ChangeItem> deferred = new ArrayList<>();
        long minUnresolved = -1;
        int pages = 0;
        boolean hasMore = true;
        while (hasMore && pages < MAX_PULL_PAGES) {
            Response<ApiDtos.PullResponse> response =
                    api.pull(new ApiDtos.PullRequest(ledger.syncId, cursor, PULL_LIMIT)).execute();
            if (!response.isSuccessful() || response.body() == null) {
                throw ApiClient.toApiError(response);
            }
            ApiDtos.PullResponse pull = response.body();
            pages++;
            hasMore = pull.hasMore;
            handleRecoveryEpoch(pull.recoveryEpoch);
            for (ApiDtos.ChangeItem change : pull.changes) {
                latestChangeId = Math.max(latestChangeId, change.changeId);
                boolean applied = applyServerChange(change, ledger.id);
                if (applied) {
                    counters.pulled++;
                } else {
                    deferred.add(change);
                    long id = change.changeId;
                    minUnresolved = minUnresolved < 0 ? id : Math.min(minUnresolved, id);
                }
            }
            cursor = pull.lastChangeId;
            if (pull.changes.isEmpty()) {
                break;
            }
        }
        // 二次尝试暂存项（引用方可能在本轮后续页面里已到位）
        List<ApiDtos.ChangeItem> stillUnresolved = new ArrayList<>();
        for (ApiDtos.ChangeItem change : deferred) {
            if (!applyServerChange(change, ledger.id)) {
                stillUnresolved.add(change);
            }
        }
        long persisted = stillUnresolved.isEmpty()
                ? latestChangeId
                : Math.min(latestChangeId, minUnresolved - 1);
        // 游标只前进不后退：悬挂引用会把有效水位压回，待引用方同步后自然推进
        SyncCursorEntity current = database.syncCursorDao().find(email, ledger.syncId);
        long finalCursor = Math.max(persisted, current != null ? current.lastChangeId : 0);
        SyncCursorEntity row = new SyncCursorEntity(email, ledger.syncId);
        row.lastChangeId = finalCursor;
        row.updatedAt = System.currentTimeMillis();
        database.syncCursorDao().upsert(row);
    }

    /**
     * 应用一条服务器变更。返回 false 表示引用未就绪，需暂存重试。
     * 仅当服务器 version &gt; 本地 version 时应用（本机 Push 回显自然跳过）。
     * {@code ledgerRowId} 是变更所属账本的本地行 id，新落地行的账本归属依据。
     */
    private boolean applyServerChange(@NonNull ApiDtos.ChangeItem change, long ledgerRowId) {
        boolean[] applied = {false};
        database.runInTransaction(() -> {
            applied[0] = applyServerPayload(change.entityType, change.syncId,
                    change.payload, change.version, change.serverReceivedAt, ledgerRowId);
        });
        return applied[0];
    }

    private boolean applyServerPayload(@NonNull String entityType, @NonNull String syncId,
                                       @Nullable ApiDtos.SyncPayload payload,
                                       long serverVersion, long serverReceivedAt,
                                       long ledgerRowId) {
        if (payload == null) {
            return true; // DELETE 无载荷：对未知 syncId 无操作
        }
        boolean isDelete = payload.isDeleted != null && payload.isDeleted;
        switch (entityType) {
            case SyncEntityTypes.LEDGER: {
                LedgerEntity local = database.ledgerDao().getBySyncId(syncId);
                if (local == null) {
                    if (isDelete) {
                        return true; // 本地没有、云端已删：无需建墓碑
                    }
                    LedgerEntity created = new LedgerEntity();
                    created.syncId = syncId;
                    created.createdAt = System.currentTimeMillis();
                    applyLedgerFields(created, payload);
                    created.version = serverVersion;
                    created.serverReceivedAt = serverReceivedAt;
                    database.ledgerDao().insert(created);
                    return true;
                }
                if (serverVersion <= local.version) {
                    return true; // 回显 / 过时变更
                }
                boolean wasDeleted = local.isDeleted;
                applyLedgerFields(local, payload);
                local.version = serverVersion;
                local.serverReceivedAt = serverReceivedAt;
                database.ledgerDao().update(local);
                if (local.isDeleted && !wasDeleted) {
                    postNotice("账本「" + local.name + "」已被所有者删除");
                    switchAwayIfCurrent(local.id);
                }
                return true;
            }
            case SyncEntityTypes.CATEGORY: {
                CategoryEntity local = database.categoryDao().getBySyncId(syncId);
                if (local == null) {
                    if (isDelete) {
                        return true; // 本地没有、云端已删：无需建墓碑
                    }
                    CategoryEntity created = new CategoryEntity();
                    created.syncId = syncId;
                    created.ledgerId = ledgerRowId;
                    applyCategoryFields(created, payload);
                    created.version = serverVersion;
                    created.serverReceivedAt = serverReceivedAt;
                    database.categoryDao().insert(created);
                    return true;
                }
                if (serverVersion <= local.version) {
                    return true; // 回显 / 过时变更
                }
                applyCategoryFields(local, payload);
                local.version = serverVersion;
                local.serverReceivedAt = serverReceivedAt;
                database.categoryDao().update(local);
                return true;
            }
            case SyncEntityTypes.ACCOUNT: {
                AccountEntity local = database.accountDao().getBySyncId(syncId);
                if (local == null) {
                    if (isDelete) {
                        return true;
                    }
                    AccountEntity created = new AccountEntity();
                    created.syncId = syncId;
                    created.ledgerId = ledgerRowId;
                    created.createdAt = System.currentTimeMillis();
                    applyAccountFields(created, payload);
                    created.version = serverVersion;
                    created.serverReceivedAt = serverReceivedAt;
                    database.accountDao().insert(created);
                    return true;
                }
                if (serverVersion <= local.version) {
                    return true;
                }
                applyAccountFields(local, payload);
                local.version = serverVersion;
                local.serverReceivedAt = serverReceivedAt;
                database.accountDao().update(local);
                return true;
            }
            case SyncEntityTypes.TRANSACTION: {
                TransactionEntity local = database.transactionDao().getBySyncId(syncId);
                Long categoryId = SyncPayloadMapper.localCategoryId(database, payload.categorySyncId);
                Long accountId = SyncPayloadMapper.localAccountId(database, payload.accountSyncId);
                Long transferAccountId = SyncPayloadMapper.localAccountId(database,
                        payload.transferAccountSyncId);
                if ((payload.categorySyncId != null && categoryId == null)
                        || (payload.accountSyncId != null && accountId == null)
                        || (payload.transferAccountSyncId != null && transferAccountId == null)) {
                    return false; // 引用未就绪，暂存
                }
                if (local == null) {
                    if (isDelete) {
                        return true;
                    }
                    TransactionEntity created = new TransactionEntity();
                    created.syncId = syncId;
                    created.ledgerId = ledgerRowId;
                    applyTransactionFields(created, payload, categoryId, accountId,
                            transferAccountId);
                    created.version = serverVersion;
                    created.serverReceivedAt = serverReceivedAt;
                    database.transactionDao().insert(created);
                    return true;
                }
                if (serverVersion <= local.version) {
                    return true;
                }
                applyTransactionFields(local, payload, categoryId, accountId, transferAccountId);
                local.version = serverVersion;
                local.serverReceivedAt = serverReceivedAt;
                database.transactionDao().update(local);
                return true;
            }
            case SyncEntityTypes.BUDGET: {
                BudgetEntity local = findBudgetBySyncId(syncId);
                Long categoryId = payload.categorySyncId == null || payload.categorySyncId.isEmpty()
                        ? 0L : SyncPayloadMapper.localCategoryId(database, payload.categorySyncId);
                if (categoryId == null) {
                    return false; // 分类引用未就绪
                }
                if (local == null) {
                    if (isDelete) {
                        return true;
                    }
                    BudgetEntity created = new BudgetEntity();
                    created.syncId = syncId;
                    created.ledgerId = ledgerRowId;
                    applyBudgetFields(created, payload, categoryId);
                    created.version = serverVersion;
                    created.serverReceivedAt = serverReceivedAt;
                    database.budgetDao().upsert(created);
                    return true;
                }
                if (serverVersion <= local.version) {
                    return true;
                }
                applyBudgetFields(local, payload, categoryId);
                local.version = serverVersion;
                local.serverReceivedAt = serverReceivedAt;
                database.budgetDao().upsert(local);
                return true;
            }
            case SyncEntityTypes.RECURRING: {
                RecurringTransactionEntity local =
                        database.recurringTransactionDao().getBySyncId(syncId);
                Long categoryId = SyncPayloadMapper.localCategoryId(database, payload.categorySyncId);
                Long accountId = SyncPayloadMapper.localAccountId(database, payload.accountSyncId);
                if ((payload.categorySyncId != null && categoryId == null)
                        || (payload.accountSyncId != null && accountId == null)) {
                    return false;
                }
                if (local == null) {
                    if (isDelete) {
                        return true;
                    }
                    RecurringTransactionEntity created = new RecurringTransactionEntity();
                    created.syncId = syncId;
                    created.ledgerId = ledgerRowId;
                    created.createdAt = System.currentTimeMillis();
                    applyRecurringFields(created, payload, categoryId, accountId);
                    created.version = serverVersion;
                    created.serverReceivedAt = serverReceivedAt;
                    database.recurringTransactionDao().insert(created);
                    return true;
                }
                if (serverVersion <= local.version) {
                    return true;
                }
                applyRecurringFields(local, payload, categoryId, accountId);
                local.version = serverVersion;
                local.serverReceivedAt = serverReceivedAt;
                database.recurringTransactionDao().update(local);
                return true;
            }
            default:
                return true;
        }
    }

    // ===== 字段应用（Pull 侧） =====

    /** 账本字段应用（Pull 侧）：is_current 是本地状态、不入协议；ownerUserId 仅服务端下发。 */
    private void applyLedgerFields(LedgerEntity entity, ApiDtos.SyncPayload payload) {
        entity.name = payload.name != null ? payload.name : entity.name;
        entity.description = payload.description != null ? payload.description : entity.description;
        entity.currency = payload.currency != null ? payload.currency : entity.currency;
        entity.isArchived = payload.isArchived != null
                ? payload.isArchived : entity.isArchived;
        entity.isDefault = payload.isDefault != null ? payload.isDefault : entity.isDefault;
        entity.ownerUserId = payload.ownerUserId != null ? payload.ownerUserId : entity.ownerUserId;
        entity.clientUpdatedAt = payload.clientUpdatedAt != null
                ? payload.clientUpdatedAt : entity.clientUpdatedAt;
        entity.isDeleted = payload.isDeleted != null && payload.isDeleted;
        entity.deletedAt = payload.deletedAt;
    }

    private void applyCategoryFields(CategoryEntity entity, ApiDtos.SyncPayload payload) {
        entity.name = payload.name != null ? payload.name : entity.name;
        entity.icon = payload.icon != null ? payload.icon : entity.icon;
        entity.type = payload.type != null ? payload.type : entity.type;
        entity.sortOrder = payload.sortOrder != null ? payload.sortOrder : entity.sortOrder;
        entity.isDefault = payload.isDefault != null ? payload.isDefault : entity.isDefault;
        entity.isDeleted = payload.isDeleted != null && payload.isDeleted;
        entity.deletedAt = payload.deletedAt;
    }

    private void applyAccountFields(AccountEntity entity, ApiDtos.SyncPayload payload) {
        entity.name = payload.name != null ? payload.name : entity.name;
        entity.type = payload.type != null ? payload.type : entity.type;
        entity.initialBalance = payload.initialBalance != null
                ? payload.initialBalance : entity.initialBalance;
        entity.isCredit = payload.isCredit != null ? payload.isCredit : entity.isCredit;
        entity.sortOrder = payload.sortOrder != null ? payload.sortOrder : entity.sortOrder;
        entity.isArchived = payload.isArchived != null ? payload.isArchived : entity.isArchived;
        entity.isDeleted = payload.isDeleted != null && payload.isDeleted;
        entity.deletedAt = payload.deletedAt;
        // balance 缓存由 Pull 结束后的统一重算对齐（真值在交易）
    }

    private void applyTransactionFields(TransactionEntity entity, ApiDtos.SyncPayload payload,
                                        Long categoryId, Long accountId, Long transferAccountId) {
        entity.type = payload.type != null ? payload.type : entity.type;
        entity.amount = payload.amount != null ? payload.amount : entity.amount;
        entity.categoryId = categoryId;
        entity.accountId = accountId;
        entity.transferAccountId = transferAccountId;
        entity.date = payload.date != null ? payload.date : entity.date;
        entity.time = payload.time != null ? payload.time : entity.time;
        entity.note = payload.note;
        entity.createdAt = payload.clientCreatedAt != null
                ? payload.clientCreatedAt : entity.createdAt;
        entity.updatedAt = payload.clientUpdatedAt != null
                ? payload.clientUpdatedAt : entity.updatedAt;
        entity.isDeleted = payload.isDeleted != null && payload.isDeleted;
        entity.deletedAt = payload.deletedAt;
    }

    private void applyBudgetFields(BudgetEntity entity, ApiDtos.SyncPayload payload,
                                   long categoryId) {
        entity.year = payload.year != null ? payload.year : entity.year;
        entity.month = payload.month != null ? payload.month : entity.month;
        entity.categoryId = (int) categoryId;
        entity.amount = payload.amount != null ? payload.amount : entity.amount;
        entity.updatedAt = payload.clientUpdatedAt != null
                ? payload.clientUpdatedAt : entity.updatedAt;
        entity.isDeleted = payload.isDeleted != null && payload.isDeleted;
        entity.deletedAt = payload.deletedAt;
    }

    private void applyRecurringFields(RecurringTransactionEntity entity,
                                      ApiDtos.SyncPayload payload,
                                      Long categoryId, Long accountId) {
        entity.name = payload.name != null ? payload.name : entity.name;
        entity.type = payload.type != null ? payload.type : entity.type;
        entity.amount = payload.amount != null ? payload.amount : entity.amount;
        entity.categoryId = categoryId;
        entity.accountId = accountId;
        entity.frequency = payload.frequency != null ? payload.frequency : entity.frequency;
        entity.interval = payload.repeatInterval != null
                ? payload.repeatInterval : entity.interval;
        entity.startDate = payload.startDate != null ? payload.startDate : entity.startDate;
        entity.endDate = payload.endDate != null ? payload.endDate : entity.endDate;
        entity.nextRunDate = payload.nextRunDate != null
                ? payload.nextRunDate : entity.nextRunDate;
        entity.anchorDayOfMonth = payload.anchorDayOfMonth != null
                ? payload.anchorDayOfMonth : entity.anchorDayOfMonth;
        entity.isEnabled = payload.isEnabled != null ? payload.isEnabled : entity.isEnabled;
        entity.note = payload.note;
        entity.updatedAt = payload.clientUpdatedAt != null
                ? payload.clientUpdatedAt : entity.updatedAt;
        entity.isDeleted = payload.isDeleted != null && payload.isDeleted;
        entity.deletedAt = payload.deletedAt;
    }

    // ===== 重名合并（多设备各自初始化同名默认分类/账户的收敛，完成备注 14） =====

    /**
     * Push 应答 mergedInto：服务器按业务键把本条并入已有实体。
     * 本地没有目标实体 → 改写本地 syncId 并采纳服务器版本；
     * 本地已有目标实体（两行都在）→ 引用改指向后物理删除本行
     * （mergedInto 只出现在创建路径，本行从未上云，物理删除安全）。
     */
    private void applyMergedInto(@NonNull ApiDtos.PushResultItem result) {
        String incoming = result.syncId;
        String target = result.mergedInto;
        long now = System.currentTimeMillis();
        switch (result.entityType) {
            case SyncEntityTypes.LEDGER: {
                // V3.2 默认账本 claim 合并（V3.1 → V3.2 升级链路，基线第 5 章）：
                // 本地默认账本并入服务端回填的「我的账本」，业务数据整体迁移，不换 syncId。
                applyLedgerMergedInto(result, incoming, target, now);
                break;
            }
            case SyncEntityTypes.CATEGORY: {
                CategoryEntity local = database.categoryDao().getBySyncId(incoming);
                if (local == null) {
                    database.syncChangeQueueDao().clearFor(result.entityType, incoming);
                    return;
                }
                CategoryEntity twin = database.categoryDao().getBySyncId(target);
                if (twin == null) {
                    local.syncId = target;
                    local.version = result.version;
                    local.serverReceivedAt = result.serverReceivedAt;
                    database.categoryDao().update(local);
                } else {
                    database.transactionDao().repointCategory(local.id, twin.id, now);
                    database.recurringTransactionDao().repointCategory(local.id, twin.id, now);
                    moveBudgetsOffCategory(local.id, twin.id, now);
                    database.categoryDao().deleteById(local.id);
                }
                database.syncChangeQueueDao().clearFor(result.entityType, incoming);
                break;
            }
            case SyncEntityTypes.ACCOUNT: {
                AccountEntity local = database.accountDao().getBySyncId(incoming);
                if (local == null) {
                    database.syncChangeQueueDao().clearFor(result.entityType, incoming);
                    return;
                }
                AccountEntity twin = database.accountDao().getBySyncId(target);
                if (twin == null) {
                    local.syncId = target;
                    local.version = result.version;
                    local.serverReceivedAt = result.serverReceivedAt;
                    database.accountDao().update(local);
                } else {
                    database.transactionDao().repointAccount(local.id, twin.id, now);
                    database.transactionDao().repointTransferAccount(local.id, twin.id, now);
                    database.recurringTransactionDao().repointAccount(local.id, twin.id, now);
                    database.accountDao().deleteById(local.id);
                }
                database.syncChangeQueueDao().clearFor(result.entityType, incoming);
                break;
            }
            case SyncEntityTypes.BUDGET: {
                BudgetEntity local = findBudgetBySyncId(incoming);
                if (local == null) {
                    database.syncChangeQueueDao().clearFor(result.entityType, incoming);
                    return;
                }
                BudgetEntity twin = findBudgetBySyncId(target);
                if (twin == null) {
                    local.syncId = target;
                    local.version = result.version;
                    local.serverReceivedAt = result.serverReceivedAt;
                    database.budgetDao().upsert(local);
                } else {
                    // 预算没有被引用的行，物理删除重复行即可
                    database.budgetDao().deleteById(local.id);
                }
                database.syncChangeQueueDao().clearFor(result.entityType, incoming);
                break;
            }
            default:
                database.syncChangeQueueDao().clearFor(result.entityType, incoming);
                break;
        }
    }

    /**
     * 账本身份合并（默认账本 claim 被 mergedInto，基线第 5.1/5.2 章）：
     * 本地没有目标行 → 改写本地账本 syncId 采纳服务器身份，游标键随迁；
     * 两行都在（状态对账已插入服务端回填账本）→ 全部业务行迁移 ledger_id 后删除本地空壳，
     * 业务 sync_id 一律保持不变（基线第 5.2 章：迁移不得重生成 syncId）。
     */
    private void applyLedgerMergedInto(@NonNull ApiDtos.PushResultItem result,
                                       @NonNull String incoming, @NonNull String target,
                                       long now) {
        LedgerEntity local = database.ledgerDao().getBySyncId(incoming);
        if (local == null) {
            database.syncChangeQueueDao().clearFor(SyncEntityTypes.LEDGER, incoming);
            return;
        }
        boolean wasCurrent = local.isCurrent;
        LedgerEntity twin = database.ledgerDao().getBySyncId(target);
        String email = tokenStore.getAccountEmail();
        if (twin == null) {
            String oldSyncId = local.syncId;
            local.syncId = target;
            if (result.payload != null) {
                applyLedgerFields(local, result.payload);
            }
            local.version = result.version;
            local.serverReceivedAt = result.serverReceivedAt;
            database.ledgerDao().update(local);
            if (email != null) {
                database.syncCursorDao().renameKey(email, oldSyncId, target, now);
            }
        } else {
            database.transactionDao().repointLedger(local.id, twin.id);
            database.categoryDao().repointLedger(local.id, twin.id);
            database.accountDao().repointLedger(local.id, twin.id);
            database.budgetDao().repointLedger(local.id, twin.id);
            database.recurringTransactionDao().repointLedger(local.id, twin.id);
            database.ledgerDao().deleteById(local.id);
            if (wasCurrent) {
                database.ledgerDao().setCurrent(twin.id);
            }
            if (email != null) {
                database.syncCursorDao().renameKey(email, "", target, now);
                database.syncCursorDao().renameKey(email, incoming, target, now);
            }
        }
        database.syncChangeQueueDao().clearFor(SyncEntityTypes.LEDGER, incoming);
    }

    /** 把引用 loser 分类的预算改指向 keeper；撞唯一键时保留既有行、退役移动行。 */
    private void moveBudgetsOffCategory(long loserId, long keeperId, long now) {
        for (BudgetEntity budget : database.budgetDao().getActiveByCategoryId(loserId)) {
            BudgetEntity existing = database.budgetDao().getActive(budget.year, budget.month,
                    (int) keeperId);
            if (existing != null && existing.id != budget.id) {
                retireBudget(budget, now);
            } else {
                budget.categoryId = (int) keeperId;
                budget.updatedAt = now;
                database.budgetDao().upsert(budget);
            }
        }
    }

    /** 预算行退役：从未上云的物理删除，其余软删并入队 DELETE 传播。 */
    private void retireBudget(@NonNull BudgetEntity budget, long now) {
        if (budget.version == 0) {
            database.budgetDao().deleteById(budget.id);
            database.syncChangeQueueDao().clearFor(SyncEntityTypes.BUDGET, budget.syncId);
        } else {
            budget.isDeleted = true;
            budget.updatedAt = now;
            database.budgetDao().upsert(budget);
            enqueueDelete(SyncEntityTypes.BUDGET, budget.syncId);
        }
    }

    /** 去重的保留者选择：组内 syncId 最小者——所有设备对同一组必然选中同一行。 */
    @Nullable
    private static <T extends Object> T pickKeeper(@NonNull List<T> group,
                                                   @NonNull java.util.function.Function<T, String> syncId) {
        T keeper = null;
        for (T candidate : group) {
            if (keeper == null || syncId.apply(candidate)
                    .compareTo(syncId.apply(keeper)) < 0) {
                keeper = candidate;
            }
        }
        return keeper;
    }

    /**
     * 本地重名去重：分类按（类型，名称）、账户按名称、预算按（年，月，分类）分组，
     * 每组保留 syncId 最小者，其余行引用改指向后退役（软删并入队 DELETE 传播）。
     * 已被历史污染的多设备数据会在各自下一轮同步自动收敛到每名一条。
     */
    private void mergeDuplicateRows() {
        repository.runInIoTransaction(() -> {
            long now = System.currentTimeMillis();
            mergeDuplicateAccounts(now);
            mergeDuplicateCategories(now);
            mergeDuplicateBudgets(now);
        });
    }

    private void mergeDuplicateAccounts(long now) {
        Map<String, List<AccountEntity>> groups = new LinkedHashMap<>();
        for (AccountEntity entity : database.accountDao().getAllIncludingDeleted()) {
            if (entity.isDeleted || entity.syncId == null || entity.syncId.isEmpty()) {
                continue;
            }
            String key = entity.name == null ? "" : entity.name.trim();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(entity);
        }
        for (List<AccountEntity> group : groups.values()) {
            if (group.size() < 2) {
                continue;
            }
            AccountEntity keeper = pickKeeper(group, a -> a.syncId);
            for (AccountEntity loser : group) {
                if (loser == keeper) {
                    continue;
                }
                database.transactionDao().repointAccount(loser.id, keeper.id, now);
                database.transactionDao().repointTransferAccount(loser.id, keeper.id, now);
                database.recurringTransactionDao().repointAccount(loser.id, keeper.id, now);
                retireAccount(loser, now);
            }
        }
    }

    private void retireAccount(@NonNull AccountEntity loser, long now) {
        if (loser.version == 0) {
            database.accountDao().deleteById(loser.id);
            database.syncChangeQueueDao().clearFor(SyncEntityTypes.ACCOUNT, loser.syncId);
        } else {
            loser.isDeleted = true;
            loser.updatedAt = now;
            database.accountDao().update(loser);
            enqueueDelete(SyncEntityTypes.ACCOUNT, loser.syncId);
        }
    }

    private void mergeDuplicateCategories(long now) {
        Map<String, List<CategoryEntity>> groups = new LinkedHashMap<>();
        for (CategoryEntity entity : database.categoryDao().getAllIncludingDeleted()) {
            if (entity.isDeleted || entity.syncId == null || entity.syncId.isEmpty()) {
                continue;
            }
            String key = entity.type + "|" + (entity.name == null ? "" : entity.name.trim());
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(entity);
        }
        for (List<CategoryEntity> group : groups.values()) {
            if (group.size() < 2) {
                continue;
            }
            CategoryEntity keeper = pickKeeper(group, c -> c.syncId);
            for (CategoryEntity loser : group) {
                if (loser == keeper) {
                    continue;
                }
                database.transactionDao().repointCategory(loser.id, keeper.id, now);
                database.recurringTransactionDao().repointCategory(loser.id, keeper.id, now);
                moveBudgetsOffCategory(loser.id, keeper.id, now);
                retireCategory(loser, now);
            }
        }
    }

    private void retireCategory(@NonNull CategoryEntity loser, long now) {
        if (loser.version == 0) {
            database.categoryDao().deleteById(loser.id);
            database.syncChangeQueueDao().clearFor(SyncEntityTypes.CATEGORY, loser.syncId);
        } else {
            loser.isDeleted = true;
            database.categoryDao().update(loser);
            enqueueDelete(SyncEntityTypes.CATEGORY, loser.syncId);
        }
    }

    private void mergeDuplicateBudgets(long now) {
        Map<String, List<BudgetEntity>> groups = new LinkedHashMap<>();
        for (BudgetEntity entity : database.budgetDao().getAllIncludingDeleted()) {
            if (entity.isDeleted || entity.syncId == null || entity.syncId.isEmpty()) {
                continue;
            }
            String key = entity.year + "|" + entity.month + "|" + entity.categoryId;
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(entity);
        }
        for (List<BudgetEntity> group : groups.values()) {
            if (group.size() < 2) {
                continue;
            }
            BudgetEntity keeper = pickKeeper(group, b -> b.syncId);
            for (BudgetEntity loser : group) {
                if (loser == keeper) {
                    continue;
                }
                retireBudget(loser, now);
            }
        }
    }

    private void enqueueDelete(@NonNull String entityType, @NonNull String syncId) {
        if (!syncId.isEmpty()) {
            enqueuer.enqueue(entityType, syncId, SyncEntityTypes.OP_DELETE, 0);
        }
    }

    // ===== 首次同步（基线第 8、25 章） =====

    /** 为存量空 syncId 行补 UUID（种子数据 / 旧迁移数据都要能上云）。 */
    private void repairSyncIds() {
        database.runInTransaction(() -> {
            for (CategoryEntity entity : database.categoryDao().getAllIncludingDeleted()) {
                if (entity.syncId == null || entity.syncId.isEmpty()) {
                    SyncPayloadMapper.ensureSyncId(entity);
                    database.categoryDao().update(entity);
                }
            }
            for (AccountEntity entity : database.accountDao().getAllIncludingDeleted()) {
                if (entity.syncId == null || entity.syncId.isEmpty()) {
                    SyncPayloadMapper.ensureSyncId(entity);
                    database.accountDao().update(entity);
                }
            }
            for (TransactionEntity entity : database.transactionDao()
                    .getAllEntitiesIncludingDeleted()) {
                if (entity.syncId == null || entity.syncId.isEmpty()) {
                    SyncPayloadMapper.ensureSyncId(entity);
                    database.transactionDao().update(entity);
                }
            }
            for (BudgetEntity entity : database.budgetDao().getAllIncludingDeleted()) {
                if (entity.syncId == null || entity.syncId.isEmpty()) {
                    SyncPayloadMapper.ensureSyncId(entity);
                    database.budgetDao().upsert(entity);
                }
            }
            for (RecurringTransactionEntity entity : database.recurringTransactionDao()
                    .getAllIncludingDeleted()) {
                if (entity.syncId == null || entity.syncId.isEmpty()) {
                    SyncPayloadMapper.ensureSyncId(entity);
                    database.recurringTransactionDao().update(entity);
                }
            }
        });
    }

    /** bootstrap 确认后：全量入队（UPSERT / DELETE 按软删态），走常规 Push 收敛。 */
    private void enqueueEverythingForBootstrap() {
        database.runInTransaction(() -> {
            // V3.2：账本本身也要上云（本地新建账本 / 默认账本 claim / 本地删除墓碑）
            for (LedgerEntity ledger : database.ledgerDao().getAllIncludingDeleted()) {
                if (ledger.syncId == null || ledger.syncId.isEmpty()) {
                    continue;
                }
                database.syncChangeQueueDao().upsert(queueRow(SyncEntityTypes.LEDGER,
                        ledger.syncId, ledger.isDeleted));
            }
            for (CategoryEntity entity : database.categoryDao().getAllIncludingDeleted()) {
                database.syncChangeQueueDao().upsert(queueRow(SyncEntityTypes.CATEGORY,
                        entity.syncId, entity.isDeleted));
            }
            for (AccountEntity entity : database.accountDao().getAllIncludingDeleted()) {
                database.syncChangeQueueDao().upsert(queueRow(SyncEntityTypes.ACCOUNT,
                        entity.syncId, entity.isDeleted));
            }
            for (TransactionEntity entity : database.transactionDao()
                    .getAllEntitiesIncludingDeleted()) {
                database.syncChangeQueueDao().upsert(queueRow(SyncEntityTypes.TRANSACTION,
                        entity.syncId, entity.isDeleted));
            }
            for (BudgetEntity entity : database.budgetDao().getAllIncludingDeleted()) {
                database.syncChangeQueueDao().upsert(queueRow(SyncEntityTypes.BUDGET,
                        entity.syncId, entity.isDeleted));
            }
            for (RecurringTransactionEntity entity : database.recurringTransactionDao()
                    .getAllIncludingDeleted()) {
                database.syncChangeQueueDao().upsert(queueRow(SyncEntityTypes.RECURRING,
                        entity.syncId, entity.isDeleted));
            }
        });
    }

    private static SyncChangeQueueEntity queueRow(String entityType, String syncId,
                                                  boolean deleted) {
        SyncChangeQueueEntity row = new SyncChangeQueueEntity();
        row.entityType = entityType;
        row.syncId = syncId;
        row.operation = deleted ? SyncEntityTypes.OP_DELETE : SyncEntityTypes.OP_UPSERT;
        row.baseVersion = 0;
        row.createdAt = System.currentTimeMillis();
        row.nextRetryAt = 0;
        return row;
    }

    /** 本地数据统计（首次同步确认页用，基线 8.1）：账单/账户/分类/预算/周期。 */
    public void loadLocalCounts(@NonNull Callback<int[]> callback) {
        repository.runOnIo(() -> {
            int[] counts = {
                    database.transactionDao().countAll(),
                    database.accountDao().countAll(),
                    database.categoryDao().countAll(),
                    database.budgetDao().countAll(),
                    database.recurringTransactionDao().countAll()
            };
            postMainCallbackValue(callback, counts);
        });
    }

    // ===== 工具 =====

    private void retryLater() {
        SyncScheduler.scheduleRetry(RetryPolicy.delayFor(failedRounds));
    }

    private void finishRound(Status success, RoundCounters counters, long startedAt) {
        postStatus(success);
        persistState(success, null, counters.conflicts);
        recordEvent(startedAt, success, counters, null);
        // Pull 后统一重算账户余额缓存（继承 V2「缓存不是唯一真值」）
        repository.runOnIo(repository::validateAccountBalancesInternal);
    }

    private void persistState(Status statusName, @Nullable String error, int conflicts) {
        SyncStateEntity state = requireState();
        state.status = statusName.name();
        state.lastError = error;
        state.conflictCount = conflicts;
        if (statusName == Status.SUCCESS) {
            long now = System.currentTimeMillis();
            state.lastSyncAt = now;
            state.lastPushAt = now;
            state.lastPullAt = now;
        }
        database.syncStateDao().upsert(state);
    }

    /** 记录一轮同步摘要事件（V3.1 基线第 25 章），裁剪保留最近 50 条。 */
    private void recordEvent(long startedAt, @Nullable Status result,
                             @NonNull RoundCounters counters, @Nullable String error) {
        try {
            SyncEventEntity event = new SyncEventEntity();
            long now = System.currentTimeMillis();
            event.startedAt = startedAt;
            event.finishedAt = now;
            event.result = result != null ? result.name() : Status.ERROR.name();
            event.pushCount = counters.pushed;
            event.pullCount = counters.pulled;
            event.conflictCount = counters.conflicts;
            event.durationMs = Math.max(0, now - startedAt);
            event.errorMessage = error;
            database.runInTransaction(() -> {
                database.syncEventDao().insert(event);
                database.syncEventDao().trimToLimit();
            });
        } catch (Exception e) {
            // 诊断记录失败不影响同步主流程
        }
    }

    /**
     * 服务器恢复代际检测（V3.1 决策 2，基线第 16 章）：
     * epoch 变化 = 服务器恢复过备份。此时旧的 change_id 游标失去意义
     * （变更日志按业务行重建、id 重新从 1 起），必须重置游标并全量重推本地状态：
     * Pull 侧按「只应用更高版本」幂等，Push 侧 LWW 收敛，各设备最终一致。
     */
    private void handleRecoveryEpoch(long epoch) {
        if (epoch <= 0) {
            return;
        }
        SyncStateEntity state = requireState();
        if (state.recoveryEpoch == epoch) {
            return;
        }
        // V3.2：游标已按账本拆分，恢复后代际变化需要清空全部账本游标重新拉取
        database.syncCursorDao().clearAll();
        state.recoveryEpoch = epoch;
        state.recoveredAt = System.currentTimeMillis();
        database.syncStateDao().upsert(state);
        // 全量重推：让服务器收敛到各设备持有的最新状态（含墓碑）
        enqueueEverythingForBootstrap();
    }

    @NonNull
    private SyncStateEntity requireState() {
        SyncStateEntity state = database.syncStateDao().get();
        if (state == null) {
            state = new SyncStateEntity();
            state.id = SyncStateEntity.SINGLETON_ID;
        }
        return state;
    }

    private void notifyReady() {
        SyncScheduler.requestSyncNow();
    }

    private void postStatus(Status value) {
        status.postValue(value);
    }

    private void postMainCallback(@NonNull Callback<Boolean> callback, boolean value) {
        android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        handler.post(() -> callback.onResult(value));
    }

    private void postMainError(@NonNull Callback<?> callback, @NonNull Exception e) {
        android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        handler.post(() -> callback.onError(e));
    }

    private <T> void postMainCallbackValue(@NonNull Callback<T> callback, T value) {
        android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        handler.post(() -> callback.onResult(value));
    }

    @NonNull
    private static String key(@NonNull SyncChangeQueueEntity entry) {
        return entry.entityType + "|" + entry.syncId;
    }

    private static boolean payloadEquals(@Nullable ApiDtos.SyncPayload a,
                                         @Nullable ApiDtos.SyncPayload b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return java.util.Objects.equals(a.name, b.name)
                && java.util.Objects.equals(a.icon, b.icon)
                && java.util.Objects.equals(a.type, b.type)
                && java.util.Objects.equals(a.sortOrder, b.sortOrder)
                && java.util.Objects.equals(a.isDefault, b.isDefault)
                && java.util.Objects.equals(a.initialBalance, b.initialBalance)
                && java.util.Objects.equals(a.balance, b.balance)
                && java.util.Objects.equals(a.isCredit, b.isCredit)
                && java.util.Objects.equals(a.isArchived, b.isArchived)
                && java.util.Objects.equals(a.amount, b.amount)
                && java.util.Objects.equals(a.categorySyncId, b.categorySyncId)
                && java.util.Objects.equals(a.accountSyncId, b.accountSyncId)
                && java.util.Objects.equals(a.transferAccountSyncId, b.transferAccountSyncId)
                && java.util.Objects.equals(a.date, b.date)
                && java.util.Objects.equals(a.time, b.time)
                && java.util.Objects.equals(a.note, b.note)
                && java.util.Objects.equals(a.clientCreatedAt, b.clientCreatedAt)
                && java.util.Objects.equals(a.year, b.year)
                && java.util.Objects.equals(a.month, b.month)
                && java.util.Objects.equals(a.frequency, b.frequency)
                && java.util.Objects.equals(a.repeatInterval, b.repeatInterval)
                && java.util.Objects.equals(a.startDate, b.startDate)
                && java.util.Objects.equals(a.endDate, b.endDate)
                && java.util.Objects.equals(a.nextRunDate, b.nextRunDate)
                && java.util.Objects.equals(a.anchorDayOfMonth, b.anchorDayOfMonth)
                && java.util.Objects.equals(a.isEnabled, b.isEnabled)
                && java.util.Objects.equals(a.description, b.description)
                && java.util.Objects.equals(a.currency, b.currency)
                && java.util.Objects.equals(a.ownerUserId, b.ownerUserId)
                && java.util.Objects.equals(a.clientUpdatedAt, b.clientUpdatedAt)
                && java.util.Objects.equals(a.isDeleted, b.isDeleted)
                && java.util.Objects.equals(a.deletedAt, b.deletedAt);
    }

    private BudgetEntity findBudgetBySyncId(String syncId) {
        for (BudgetEntity entity : database.budgetDao().getAllIncludingDeleted()) {
            if (syncId.equals(entity.syncId)) {
                return entity;
            }
        }
        return null;
    }

    /** Push 快照：发送时刻的实体版本与内容（ack 护栏依据）；ledgerRowId 供服务器胜出时落地。 */
    private static final class PushSnapshot {
        final String entityType;
        final String syncId;
        final long baseVersion;
        final ApiDtos.SyncPayload payload;
        final boolean isDeleted;
        final long ledgerRowId;

        PushSnapshot(String entityType, String syncId, long baseVersion,
                     ApiDtos.SyncPayload payload, boolean isDeleted, long ledgerRowId) {
            this.entityType = entityType;
            this.syncId = syncId;
            this.baseVersion = baseVersion;
            this.payload = payload;
            this.isDeleted = isDeleted;
            this.ledgerRowId = ledgerRowId;
        }
    }
}
