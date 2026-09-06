package com.skyanchor.bookkeeping.ui.sync;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.skyanchor.bookkeeping.BookkeepingApp;
import com.skyanchor.bookkeeping.R;
import com.skyanchor.bookkeeping.data.database.AppDatabase;
import com.skyanchor.bookkeeping.data.entity.AccountEntity;
import com.skyanchor.bookkeeping.data.entity.CategoryEntity;
import com.skyanchor.bookkeeping.data.entity.RecurringTransactionEntity;
import com.skyanchor.bookkeeping.data.entity.TransactionEntity;
import com.skyanchor.bookkeeping.data.remote.ApiDtos;
import com.skyanchor.bookkeeping.data.repository.ServerRepository;

import java.util.ArrayList;
import java.util.List;

/**
 * 冲突历史 ViewModel（V3.1 基线第 26/27 章）。
 * 服务端 conflict_logs 只存摘要，这里按 syncId 反查本地行，补出「名称 / 金额」
 * 便于辨认；本地查不到时只显示实体类型。仍然不弹窗、不阻断（自动收敛 + 事后查看）。
 */
public class ConflictHistoryViewModel extends AndroidViewModel {

    /** 列表行：服务端冲突摘要 + 本地反查出的展示名。 */
    public static final class Row {
        public final ApiDtos.ConflictItem item;
        public final String entityLabel;

        Row(@NonNull ApiDtos.ConflictItem item, @NonNull String entityLabel) {
            this.item = item;
            this.entityLabel = entityLabel;
        }
    }

    private final ServerRepository serverRepository;
    private final AppDatabase database;

    private final MutableLiveData<List<Row>> rows = new MutableLiveData<>();
    private final MutableLiveData<Boolean> busy = new MutableLiveData<>(false);
    private final MutableLiveData<String> error = new MutableLiveData<>();

    public ConflictHistoryViewModel(@NonNull Application application) {
        super(application);
        serverRepository = BookkeepingApp.get(application).getServerRepository();
        database = AppDatabase.getInstance(application);
    }

    public LiveData<List<Row>> rows() {
        return rows;
    }

    public LiveData<Boolean> busy() {
        return busy;
    }

    public LiveData<String> error() {
        return error;
    }

    public void load() {
        if (Boolean.TRUE.equals(busy.getValue())) {
            return;
        }
        busy.setValue(true);
        serverRepository.getConflicts(50, new ServerRepositoryCallback());
    }

    private class ServerRepositoryCallback
            implements com.skyanchor.bookkeeping.util.Callback<List<ApiDtos.ConflictItem>> {
        @Override
        public void onResult(List<ApiDtos.ConflictItem> items) {
            if (items == null) {
                busy.postValue(false);
                rows.postValue(new ArrayList<>());
                return;
            }
            // 反查本地行需要读库：切到数据库查询执行器，完成后再回主线程
            database.getQueryExecutor().execute(() -> {
                List<Row> resolved = new ArrayList<>();
                for (ApiDtos.ConflictItem item : items) {
                    resolved.add(new Row(item, describe(item)));
                }
                rows.postValue(resolved);
                busy.postValue(false);
            });
        }

        @Override
        public void onError(@NonNull Exception e) {
            error.postValue(e.getMessage());
            busy.postValue(false);
        }
    }

    /** 按 syncId 反查本地行得到人类可读摘要；查不到退化为实体类型名。 */
    private String describe(@NonNull ApiDtos.ConflictItem item) {
        String type = item.entityType;
        String label;
        switch (type) {
            case "CATEGORY": {
                CategoryEntity entity = database.categoryDao().getBySyncId(item.syncId);
                label = entity != null
                        ? entity.icon + " " + entity.name : localizedEntity(R.string.conflict_entity_category);
                break;
            }
            case "ACCOUNT": {
                AccountEntity entity = database.accountDao().getBySyncId(item.syncId);
                label = entity != null ? entity.name
                        : localizedEntity(R.string.conflict_entity_account);
                break;
            }
            case "TRANSACTION": {
                TransactionEntity entity = database.transactionDao()
                        .getEntityBySyncIdAnyState(item.syncId);
                if (entity != null) {
                    String amountLabel = entity.type == CategoryEntity.TYPE_TRANSFER
                            ? localizedEntity(R.string.edit_type_transfer)
                            : (entity.type == CategoryEntity.TYPE_INCOME
                            ? localizedEntity(R.string.edit_type_income)
                            : localizedEntity(R.string.edit_type_expense))
                            + " " + com.skyanchor.bookkeeping.util.AmountUtil
                            .format(entity.amount);
                    label = amountLabel;
                } else {
                    label = localizedEntity(R.string.conflict_entity_transaction);
                }
                break;
            }
            case "BUDGET":
                label = localizedEntity(R.string.conflict_entity_budget);
                break;
            case "RECURRING": {
                RecurringTransactionEntity entity = database.recurringTransactionDao()
                        .getBySyncId(item.syncId);
                label = entity != null ? entity.name
                        : localizedEntity(R.string.conflict_entity_recurring);
                break;
            }
            default:
                label = localizedEntity(R.string.conflict_entity_unknown);
                break;
        }
        return label;
    }

    private String localizedEntity(int res) {
        return getApplication().getString(res);
    }
}
