package com.skyanchor.bookkeeping.ui.account;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;

import com.skyanchor.bookkeeping.BookkeepingApp;
import com.skyanchor.bookkeeping.data.entity.AccountEntity;
import com.skyanchor.bookkeeping.data.model.AccountBalance;
import com.skyanchor.bookkeeping.data.model.DeleteAccountResult;
import com.skyanchor.bookkeeping.data.repository.BookkeepingRepository;
import com.skyanchor.bookkeeping.util.Callback;

import java.util.List;

/**
 * 账户管理页 ViewModel（V2 新增）。
 *
 * <p>只做状态编排、不写业务规则：账户列表来自仓库的联表重算投影
 * {@code observeAccountBalances()}，余额随账单变化自动刷新且等于「初始 + 收 - 支 + 转入 - 转出」
 * 的唯一真值；保存 / 归档 / 删除全部委托 {@link BookkeepingRepository}，删除守卫同样落在仓库层，
 * 与分类管理页保持一致的分层约定。
 */
public class AccountViewModel extends AndroidViewModel {

    private final BookkeepingRepository repository;
    private final LiveData<List<AccountBalance>> accounts;

    /** V2.1：未归属历史账单（V1 迁移数据）数量，供进入账户管理时的归属提示。 */
    private final LiveData<Integer> unassignedCount;

    public AccountViewModel(@NonNull Application application) {
        super(application);
        this.repository = BookkeepingApp.get(application).getRepository();
        this.accounts = repository.observeAccountBalances();
        this.unassignedCount = repository.observeUnassignedCount();
    }

    /** 全部账户（含已归档）余额投影，按 sort_order 升序。 */
    public LiveData<List<AccountBalance>> getAccounts() {
        return accounts;
    }

    /** 未归属历史账单数量（account_id IS NULL），归零后提示不再出现。 */
    public LiveData<Integer> getUnassignedCount() {
        return unassignedCount;
    }

    /** V2.1：把全部未归属历史账单批量归属到指定账户并重算余额，回调返回归属笔数。 */
    public void assignUnassigned(long accountId, @Nullable Callback<Integer> callback) {
        repository.assignUnassignedTransactions(accountId, callback);
    }

    public void save(@NonNull AccountEntity entity, @Nullable Callback<Long> callback) {
        repository.saveAccount(entity, callback);
    }

    /** 归档 / 取消归档账户（不物理删除），被账单引用的账户只能走归档。 */
    public void setArchived(long id, boolean archived, @Nullable Callback<Boolean> callback) {
        repository.setAccountArchived(id, archived, callback);
    }

    public void delete(long id, @Nullable Callback<DeleteAccountResult> callback) {
        repository.deleteAccount(id, callback);
    }

    /** 上移 / 下移账户排序（P2 打磨，direction -1 上移 / 1 下移）。 */
    public void move(long id, int direction, @Nullable Callback<Boolean> callback) {
        repository.moveAccount(id, direction, callback);
    }
}
