package com.skyanchor.bookkeeping.ui.record;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;

import com.skyanchor.bookkeeping.BookkeepingApp;
import com.skyanchor.bookkeeping.data.entity.CategoryEntity;
import com.skyanchor.bookkeeping.data.entity.TransactionEntity;
import com.skyanchor.bookkeeping.data.entity.TransactionItem;
import com.skyanchor.bookkeeping.data.repository.BookkeepingRepository;
import com.skyanchor.bookkeeping.util.Callback;

import java.util.List;

/**
 * 记一笔 / 编辑账单页 ViewModel。
 *
 * <p>表单的临时输入（金额文本、备注、时间）留在 Activity，因为只有配置变更时才需要恢复，
 * 而 Android 会自行保存 EditText 的内容；这里只托管两类跨配置变更必须存活的状态：
 * 交易类型与由此派生的分类列表。
 */
public class TransactionEditViewModel extends AndroidViewModel {

    private final BookkeepingRepository repository;
    private final MutableLiveData<Integer> type;
    private final LiveData<List<CategoryEntity>> categories;

    /** 编辑模式下从库里读出的原始账单，跨配置变更存活，避免重复回读与反复覆盖表单。 */
    private final MutableLiveData<TransactionItem> source = new MutableLiveData<>();

    public TransactionEditViewModel(@NonNull Application application) {
        super(application);
        this.repository = BookkeepingApp.get(application).getRepository();
        this.type = new MutableLiveData<>(CategoryEntity.TYPE_EXPENSE);
        this.categories = Transformations.switchMap(type, repository::observeCategories);
    }

    public LiveData<Integer> getType() {
        return type;
    }

    /** 当前类型下的分类，按 sortOrder 升序。 */
    public LiveData<List<CategoryEntity>> getCategories() {
        return categories;
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
}
