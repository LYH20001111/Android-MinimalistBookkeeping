package com.skyanchor.bookkeeping.ui.smart;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;

import com.skyanchor.bookkeeping.BookkeepingApp;
import com.skyanchor.bookkeeping.data.entity.AccountEntity;
import com.skyanchor.bookkeeping.data.entity.CategoryEntity;
import com.skyanchor.bookkeeping.data.entity.TransactionEntity;
import com.skyanchor.bookkeeping.data.repository.BookkeepingRepository;
import com.skyanchor.bookkeeping.util.Callback;

import java.util.ArrayList;
import java.util.List;

/**
 * 账单确认页 ViewModel（V5）。
 *
 * <p>与 {@code TransactionEditViewModel} 同构：交易类型驱动分类列表切换；
 * 差别是确认页没有转账与退款草稿，类型只有支出 / 收入两种，
 * 账户候选固定为未归档账户（草稿不携带账户，必须现选）。
 */
public class ConfirmBillViewModel extends AndroidViewModel {

    private final BookkeepingRepository repository;
    private final MutableLiveData<Integer> type =
            new MutableLiveData<>(CategoryEntity.TYPE_EXPENSE);
    private final LiveData<List<CategoryEntity>> categories;
    private final LiveData<List<AccountEntity>> accounts;

    public ConfirmBillViewModel(@NonNull Application application) {
        super(application);
        this.repository = BookkeepingApp.get(application).getRepository();
        this.categories = Transformations.switchMap(type, repository::observeCategories);
        this.accounts = Transformations.map(repository.observeAccounts(),
                ConfirmBillViewModel::filterActive);
    }

    /** 确认页只允许落到未归档账户；草稿不引用旧账户，无需保留归档项。 */
    @NonNull
    private static List<AccountEntity> filterActive(@Nullable List<AccountEntity> all) {
        List<AccountEntity> active = new ArrayList<>();
        if (all != null) {
            for (AccountEntity account : all) {
                if (!account.isArchived) {
                    active.add(account);
                }
            }
        }
        return active;
    }

    public LiveData<Integer> getType() {
        return type;
    }

    /** 当前类型下的分类，按 sortOrder 升序。 */
    public LiveData<List<CategoryEntity>> getCategories() {
        return categories;
    }

    /** 未归档账户，按 sort_order 升序。 */
    public LiveData<List<AccountEntity>> getAccounts() {
        return accounts;
    }

    public void selectType(int transactionType) {
        Integer current = type.getValue();
        if (current == null || current != transactionType) {
            type.setValue(transactionType);
        }
    }

    /** 落库走 {@code BookkeepingRepository.saveTransaction}，与手动记账同一写入路径。 */
    public void save(@NonNull TransactionEntity entity, @Nullable Callback<Long> callback) {
        repository.saveTransaction(entity, callback);
    }
}
