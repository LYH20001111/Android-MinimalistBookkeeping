package com.skyanchor.bookkeeping.ui.recurring;

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
import com.skyanchor.bookkeeping.data.entity.RecurringTransactionEntity;
import com.skyanchor.bookkeeping.data.model.RecurringDue;
import com.skyanchor.bookkeeping.data.repository.BookkeepingRepository;
import com.skyanchor.bookkeeping.domain.recurring.GenerateRecurringTransactionsUseCase;
import com.skyanchor.bookkeeping.util.Callback;
import com.skyanchor.bookkeeping.util.DateUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 周期账单页 ViewModel（V2 新增，开发计划 Phase 8）。
 *
 * <p>列表与「待记账」都直接派生自仓库 LiveData：规则、到期规则、分类、账户全部同源，
 * 一键记账写入交易后到期列表自动清空。{@link RecurringDue} 的期数由
 * {@link GenerateRecurringTransactionsUseCase#collectDueDates} 在 Java 侧计算，
 * 因此「有 N 笔待记账」的 N 是真实期数（含 App 关闭期间的补生成），不是规则条数。
 * {@code today} 取 ViewModel 创建当天，页面存活期内一致。
 */
public class RecurringViewModel extends AndroidViewModel {

    private final BookkeepingRepository repository;
    private final long today;

    private final LiveData<List<RecurringTransactionEntity>> recurring;
    private final LiveData<List<RecurringDue>> dues;
    private final LiveData<Integer> dueCount;
    private final LiveData<List<CategoryEntity>> expenseCategories;
    private final LiveData<List<CategoryEntity>> incomeCategories;
    private final LiveData<List<AccountEntity>> activeAccounts;
    private final MutableLiveData<Boolean> confirming = new MutableLiveData<>(false);

    public RecurringViewModel(@NonNull Application application) {
        super(application);
        this.repository = BookkeepingApp.get(application).getRepository();
        this.today = DateUtil.today();
        this.recurring = repository.observeRecurring();
        this.dues = Transformations.map(repository.observeDueRecurring(today), this::toDues);
        this.dueCount = Transformations.map(dues, RecurringViewModel::sumOccurrences);
        this.expenseCategories = repository.observeCategories(CategoryEntity.TYPE_EXPENSE);
        this.incomeCategories = repository.observeCategories(CategoryEntity.TYPE_INCOME);
        this.activeAccounts = repository.observeActiveAccounts();
    }

    public LiveData<List<RecurringTransactionEntity>> getRecurring() {
        return recurring;
    }

    /** 待确认列表：每条启用的到期规则一行，含累积期数。 */
    public LiveData<List<RecurringDue>> getDues() {
        return dues;
    }

    /** 待记账期数合计（所有到期规则的 occurrence 之和）。 */
    public LiveData<Integer> getDueCount() {
        return dueCount;
    }

    public LiveData<List<CategoryEntity>> getExpenseCategories() {
        return expenseCategories;
    }

    public LiveData<List<CategoryEntity>> getIncomeCategories() {
        return incomeCategories;
    }

    public LiveData<List<AccountEntity>> getActiveAccounts() {
        return activeAccounts;
    }

    public LiveData<Boolean> isConfirming() {
        return confirming;
    }

    /** 一键确认全部到期规则：写交易 + 幂等推进 next_run_date + 重算受影响账户余额。 */
    public void confirmDue(@Nullable Callback<Integer> callback) {
        if (Boolean.TRUE.equals(confirming.getValue())) {
            return;
        }
        confirming.setValue(true);
        repository.confirmDueRecurring(today, created -> {
            confirming.setValue(false);
            if (callback != null) {
                callback.onResult(created);
            }
        });
    }

    public void save(@NonNull RecurringTransactionEntity entity,
                     @Nullable Callback<Long> callback) {
        repository.saveRecurring(entity, callback);
    }

    public void delete(long id, @Nullable Callback<Boolean> callback) {
        repository.deleteRecurring(id, callback);
    }

    /** 到期规则 → 待确认行；正常应至少有一期，防御性地跳过空结果。 */
    @NonNull
    private List<RecurringDue> toDues(@Nullable List<RecurringTransactionEntity> rules) {
        List<RecurringDue> result = new ArrayList<>();
        if (rules == null) {
            return result;
        }
        for (RecurringTransactionEntity rule : rules) {
            List<Long> dates = GenerateRecurringTransactionsUseCase.collectDueDates(
                    rule.nextRunDate, today, rule.endDate, rule.frequency, rule.interval);
            if (dates.isEmpty()) {
                continue;
            }
            result.add(new RecurringDue(rule.id, rule.name, rule.type, rule.amount,
                    dates.get(0), dates.size()));
        }
        return result;
    }

    private static int sumOccurrences(@Nullable List<RecurringDue> dues) {
        int sum = 0;
        if (dues != null) {
            for (RecurringDue due : dues) {
                sum += due.occurrenceCount;
            }
        }
        return sum;
    }
}
