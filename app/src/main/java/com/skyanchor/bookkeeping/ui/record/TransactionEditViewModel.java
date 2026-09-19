package com.skyanchor.bookkeeping.ui.record;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;

import com.skyanchor.bookkeeping.BookkeepingApp;
import com.skyanchor.bookkeeping.data.entity.AccountEntity;
import com.skyanchor.bookkeeping.data.entity.CategoryEntity;
import com.skyanchor.bookkeeping.data.entity.TransactionEntity;
import com.skyanchor.bookkeeping.data.entity.TransactionItem;
import com.skyanchor.bookkeeping.data.repository.BookkeepingRepository;
import com.skyanchor.bookkeeping.util.Callback;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 记一笔 / 编辑账单页 ViewModel。
 *
 * <p>表单的临时输入（金额文本、备注、时间）留在 Activity，因为只有配置变更时才需要恢复，
 * 而 Android 会自行保存 EditText 的内容；这里只托管两类跨配置变更必须存活的状态：
 * 交易类型与由此派生的分类列表。
 *
 * <p>V2.1（基线第 26 章）：账户候选 = 未归档账户 + 正在编辑的账单原引用的账户
 * （即便已归档）。这样编辑引用了已归档账户的历史账单时，账户不会被悄悄重置成第一个账户，
 * 历史数据语义得以保留；除此之外的已归档账户仍不出现在新增账单的选择器里。
 */
public class TransactionEditViewModel extends AndroidViewModel {

    private final BookkeepingRepository repository;
    private final MutableLiveData<Integer> type;
    private final LiveData<List<CategoryEntity>> categories;

    /** 账户候选：活跃账户 + 编辑中原账单引用的已归档账户。 */
    private final LiveData<List<AccountEntity>> accounts;

    /** 编辑模式下从库里读出的原始账单，跨配置变更存活，避免重复回读与反复覆盖表单。 */
    private final MutableLiveData<TransactionItem> source = new MutableLiveData<>();

    /**
     * V4.1：待保存的退款草稿。点「确认退款」只写这里，账单保存成功后才逐笔落库；
     * 未保存就返回即随之丢弃。放在 ViewModel 是为了旋转屏幕等配置变更不丢草稿。
     */
    private final MutableLiveData<List<DraftRefund>> draftRefunds =
            new MutableLiveData<>(Collections.emptyList());

    /** 一条尚未落库的退款：与 {@code requestRefund} 的三个入参一一对应。 */
    public static final class DraftRefund {
        public final long amount;
        @Nullable
        public final String reason;
        public final boolean toReceived;

        DraftRefund(long amount, @Nullable String reason, boolean toReceived) {
            this.amount = amount;
            this.reason = reason;
            this.toReceived = toReceived;
        }
    }

    public TransactionEditViewModel(@NonNull Application application) {
        super(application);
        this.repository = BookkeepingApp.get(application).getRepository();
        this.type = new MutableLiveData<>(CategoryEntity.TYPE_EXPENSE);
        this.categories = Transformations.switchMap(type, repository::observeCategories);

        MediatorLiveData<List<AccountEntity>> merged = new MediatorLiveData<>();
        LiveData<List<AccountEntity>> allAccounts = repository.observeAccounts();
        Runnable merge = () ->
                merged.setValue(mergeAccountCandidates(allAccounts.getValue(), source.getValue()));
        merged.addSource(allAccounts, v -> merge.run());
        merged.addSource(source, v -> merge.run());
        this.accounts = merged;
    }

    /**
     * 合并账户候选：默认只留未归档账户；编辑时额外保留原账单引用的账户（含已归档），
     * 其余已归档账户仍然隐藏。保持 sort_order 排序（{@code observeAccounts} 已排好）。
     */
    @NonNull
    private static List<AccountEntity> mergeAccountCandidates(
            @Nullable List<AccountEntity> all, @Nullable TransactionItem source) {
        List<AccountEntity> result = new ArrayList<>();
        if (all == null) {
            return result;
        }
        long keepSingleId = source == null || source.accountId == null ? -1L : source.accountId;
        long keepTransferId =
                source == null || source.transferAccountId == null ? -1L : source.transferAccountId;
        for (AccountEntity account : all) {
            boolean referenced = account.id == keepSingleId || account.id == keepTransferId;
            if (!account.isArchived || referenced) {
                result.add(account);
            }
        }
        return result;
    }

    public LiveData<Integer> getType() {
        return type;
    }

    /** 当前类型下的分类，按 sortOrder 升序。 */
    public LiveData<List<CategoryEntity>> getCategories() {
        return categories;
    }

    /** 账户候选（活跃 + 编辑中原账户），按 sort_order 升序，供账户选择器。 */
    public LiveData<List<AccountEntity>> getAccounts() {
        return accounts;
    }

    /** 正在编辑的原始账单；新增模式下永远为 null。 */
    public LiveData<TransactionItem> getSource() {
        return source;
    }

    public void selectType(int transactionType) {
        Integer current = type.getValue();
        if (current == null || current != transactionType) {
            type.setValue(transactionType);
        }
    }

    /** 读取待编辑账单，已加载过则直接命中缓存，保证重建 Activity 时幂等。 */
    public void loadTransaction(long id) {
        TransactionItem loaded = source.getValue();
        if (loaded != null && loaded.id == id) {
            return;
        }
        repository.loadTransaction(id, item -> {
            if (item != null) {
                source.setValue(item);
            }
        });
    }

    public void save(@NonNull TransactionEntity entity, @Nullable Callback<Long> callback) {
        repository.saveTransaction(entity, callback);
    }

    public void delete(long id, @Nullable Callback<Boolean> callback) {
        repository.deleteTransaction(id, callback);
    }

    /** V4.0：发起一笔退款，回调告知是否受理（额度不足 / 非支出返回 false）。 */
    public void requestRefund(long transactionId, long amountCents, @Nullable String reason,
                              boolean toReceived, @Nullable Callback<Boolean> callback) {
        repository.requestRefund(transactionId, amountCents, reason, toReceived, callback);
    }

    /** 退款生效后强制回读原账单，刷新可退额度与金额明细（{@link #loadTransaction} 会短路缓存）。 */
    public void reloadSource(long id) {
        repository.loadTransaction(id, item -> {
            if (item != null) {
                source.setValue(item);
            }
        });
    }

    // ------------------------------------------------------------------
    // V4.1：退款草稿（确认退款只入草稿，保存账单时才落库）
    // ------------------------------------------------------------------

    public LiveData<List<DraftRefund>> getDraftRefunds() {
        return draftRefunds;
    }

    /** 当前草稿快照，供额度计算与保存时提交（不返回可变引用）。 */
    @NonNull
    public List<DraftRefund> currentDrafts() {
        List<DraftRefund> value = draftRefunds.getValue();
        return value == null ? Collections.emptyList() : new ArrayList<>(value);
    }

    public void addDraftRefund(long amountCents, @Nullable String reason, boolean toReceived) {
        List<DraftRefund> next = currentDrafts();
        next.add(new DraftRefund(amountCents, reason, toReceived));
        draftRefunds.setValue(next);
    }

    public void clearDraftRefunds() {
        if (!currentDrafts().isEmpty()) {
            draftRefunds.setValue(Collections.emptyList());
        }
    }

    /** 草稿中「已到账」部分的合计，参与净额与额度（与真正落库后的口径一致）。 */
    public long draftReceivedTotal() {
        return draftTotal(true);
    }

    /** 草稿中「待到账」部分的合计，只占额度、不冲减净额。 */
    public long draftPendingTotal() {
        return draftTotal(false);
    }

    private long draftTotal(boolean received) {
        long sum = 0L;
        List<DraftRefund> value = draftRefunds.getValue();
        if (value != null) {
            for (DraftRefund draft : value) {
                if (draft.toReceived == received) {
                    sum += draft.amount;
                }
            }
        }
        return sum;
    }
}
