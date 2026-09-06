package com.skyanchor.bookkeeping.ui.recurring;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.skyanchor.bookkeeping.R;
import com.skyanchor.bookkeeping.data.entity.AccountEntity;
import com.skyanchor.bookkeeping.data.entity.CategoryEntity;
import com.skyanchor.bookkeeping.data.entity.RecurringTransactionEntity;
import com.skyanchor.bookkeeping.data.model.RecurringDue;
import com.skyanchor.bookkeeping.databinding.ActivityRecurringManageBinding;
import com.skyanchor.bookkeeping.databinding.DialogRecurringEditBinding;
import com.skyanchor.bookkeeping.ui.adapter.RecurringAdapter;
import com.skyanchor.bookkeeping.util.AccountTypes;
import com.skyanchor.bookkeeping.util.AmountUtil;
import com.skyanchor.bookkeeping.util.DateUtil;
import com.skyanchor.bookkeeping.util.InsetsUtil;

import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 周期账单管理（V2 新增，开发计划 Phase 8）。
 *
 * <p>复用 {@code AccountManageActivity} 的列表 / 新增 / 编辑 / 删除交互范式：行内编辑 + 删除，
 * 新增与编辑共用一个弹窗（确定按钮手动关闭，校验错误就地留在弹窗内）。
 * 顶部「待记账」卡片只在有到期规则时出现：展示累积期数，一键确认即按期写账并
 * 幂等推进 next_run_date（见 {@code GenerateRecurringTransactionsUseCase} 与仓库层）。
 */
public class RecurringManageActivity extends AppCompatActivity {

    private ActivityRecurringManageBinding binding;
    private RecurringViewModel viewModel;
    private RecurringAdapter adapter;
    /** 弹窗打开时可用的最新分类 / 账户快照，进页面即开始观察，避免弹窗拿到空列表。 */
    private List<CategoryEntity> expenseCategories = Collections.emptyList();
    private List<CategoryEntity> incomeCategories = Collections.emptyList();
    private List<AccountEntity> activeAccounts = Collections.emptyList();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityRecurringManageBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        InsetsUtil.applySystemBarsPadding(binding.recurringRoot);
        InsetsUtil.syncSystemBarAppearance(this);

        viewModel = new ViewModelProvider(this).get(RecurringViewModel.class);
        binding.toolbar.setNavigationOnClickListener(v -> finish());

        adapter = new RecurringAdapter(new RecurringAdapter.Listener() {
            @Override
            public void onEdit(@NonNull RecurringTransactionEntity rule) {
                showEditDialog(rule);
            }

            @Override
            public void onDelete(@NonNull RecurringTransactionEntity rule) {
                confirmDelete(rule);
            }
        });
        binding.recurringList.setLayoutManager(new LinearLayoutManager(this));
        binding.recurringList.setAdapter(adapter);

        binding.addRecurringButton.setOnClickListener(v -> showEditDialog(null));
        binding.dueConfirmButton.setOnClickListener(v -> confirmDue());

        viewModel.getRecurring().observe(this, this::onRecurringChanged);
        viewModel.getDues().observe(this, this::onDuesChanged);
        // Room 的 LiveData 只有被观察才会执行查询：页面级保持观察，弹窗打开时快照才有数据
        viewModel.getExpenseCategories().observe(this,
                list -> expenseCategories = snapshot(list));
        viewModel.getIncomeCategories().observe(this,
                list -> incomeCategories = snapshot(list));
        viewModel.getActiveAccounts().observe(this,
                list -> activeAccounts = snapshotAccounts(list));
    }

    private void onRecurringChanged(@Nullable List<RecurringTransactionEntity> rules) {
        List<RecurringTransactionEntity> list =
                rules == null ? Collections.emptyList() : rules;
        adapter.submitList(list);
        binding.recurringEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void onDuesChanged(@Nullable List<RecurringDue> dues) {
        List<RecurringDue> list = dues == null ? Collections.emptyList() : dues;
        if (list.isEmpty()) {
            binding.dueCard.setVisibility(View.GONE);
            return;
        }
        int occurrences = 0;
        StringBuilder summary = new StringBuilder();
        int shown = Math.min(list.size(), 3);
        for (int i = 0; i < list.size(); i++) {
            occurrences += list.get(i).occurrenceCount;
            if (i < shown) {
                if (summary.length() > 0) {
                    summary.append('\n');
                }
                summary.append('·').append(' ').append(list.get(i).name)
                        .append(" ×").append(list.get(i).occurrenceCount);
            }
        }
        binding.dueCard.setVisibility(View.VISIBLE);
        binding.dueSummary.setText(getString(R.string.recurring_due_count_format, occurrences)
                + (summary.length() > 0 ? "\n" + summary : ""));
    }

    /** 一键确认全部到期规则，完成后由 LiveData 自动清空待记账卡片。 */
    private void confirmDue() {
        viewModel.confirmDue(created -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            Toast.makeText(this, getString(R.string.recurring_due_confirmed_toast, created),
                    Toast.LENGTH_SHORT).show();
        });
    }

    // ------------------------------------------------------------------
    // 删除（二次确认；已生成的账单不受影响）
    // ------------------------------------------------------------------

    private void confirmDelete(@NonNull RecurringTransactionEntity rule) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.recurring_delete_title, rule.name))
                .setMessage(R.string.recurring_delete_message)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_delete, (dialog, which) ->
                        viewModel.delete(rule.id, ok -> {
                            if (isFinishing() || isDestroyed()) {
                                return;
                            }
                            Toast.makeText(this, R.string.recurring_deleted,
                                    Toast.LENGTH_SHORT).show();
                        }))
                .show();
    }

    // ------------------------------------------------------------------
    // 新增 / 编辑（共用弹窗）
    // ------------------------------------------------------------------

    /**
     * 新增与编辑共用同一个弹窗。确定按钮手动关闭：名称、金额、分类、账户、间隔、
     * 结束日期任一非法时就地显示错误并留在弹窗内。停用开关仅编辑既有规则时出现。
     */
    private void showEditDialog(@Nullable RecurringTransactionEntity existing) {
        DialogRecurringEditBinding dialogBinding =
                DialogRecurringEditBinding.inflate(getLayoutInflater());

        String[] typeLabels = {getString(R.string.recurring_type_expense),
                getString(R.string.recurring_type_income)};
        String[] frequencyLabels = {getString(R.string.recurring_frequency_daily),
                getString(R.string.recurring_frequency_weekly),
                getString(R.string.recurring_frequency_monthly),
                getString(R.string.recurring_frequency_yearly)};

        // 用 length-1 数组在 lambda 之间承载可变选择状态
        final int[] selectedType = {existing == null ? CategoryEntity.TYPE_EXPENSE : existing.type};
        final int[] selectedFrequency = {existing == null
                ? RecurringTransactionEntity.FREQUENCY_MONTHLY : existing.frequency};
        final long[] startDate = {existing == null
                ? DateUtil.today() : DateUtil.startOfDay(existing.startDate)};
        final long[] endDate = {existing == null || existing.endDate == 0L
                ? 0L : DateUtil.startOfDay(existing.endDate)};

        SimpleDateFormat format = new SimpleDateFormat(
                getString(R.string.recurring_date_format), Locale.getDefault());
        CategoryPicker categoryPicker = new CategoryPicker(dialogBinding,
                expenseCategories, incomeCategories);
        AccountPicker accountPicker = new AccountPicker(dialogBinding, activeAccounts);

        dialogBinding.typeInput.setSimpleItems(typeLabels);
        dialogBinding.frequencyInput.setSimpleItems(frequencyLabels);

        dialogBinding.typeInput.setText(typeLabels[selectedType[0] == CategoryEntity.TYPE_INCOME
                ? 1 : 0], false);
        dialogBinding.typeInput.setOnItemClickListener((parent, view, position, id) -> {
            selectedType[0] = position == 1
                    ? CategoryEntity.TYPE_INCOME : CategoryEntity.TYPE_EXPENSE;
            categoryPicker.showType(selectedType[0]);
        });

        dialogBinding.frequencyInput.setText(frequencyLabel(frequencyLabels,
                selectedFrequency[0]), false);
        dialogBinding.frequencyInput.setOnItemClickListener((parent, view, position, id) ->
                selectedFrequency[0] = frequencyOf(position));

        categoryPicker.showType(selectedType[0]);
        if (existing != null) {
            categoryPicker.select(existing.categoryId);
            accountPicker.select(existing.accountId);
        }

        // 弹窗存活期间分类 / 账户数据到达或变化时原地刷新选项（保留已选 id），
        // 弹窗关闭即解除观察，避免继续触碰已废弃的绑定。
        Observer<List<CategoryEntity>> expenseObserver =
                list -> categoryPicker.setCategories(CategoryEntity.TYPE_EXPENSE, snapshot(list));
        Observer<List<CategoryEntity>> incomeObserver =
                list -> categoryPicker.setCategories(CategoryEntity.TYPE_INCOME, snapshot(list));
        Observer<List<AccountEntity>> accountObserver =
                list -> accountPicker.setAccounts(snapshotAccounts(list));
        viewModel.getExpenseCategories().observe(this, expenseObserver);
        viewModel.getIncomeCategories().observe(this, incomeObserver);
        viewModel.getActiveAccounts().observe(this, accountObserver);

        dialogBinding.startDateInput.setText(format.format(new Date(startDate[0])));
        dialogBinding.startDateInput.setOnClickListener(v ->
                pickDate(startDate[0], picked -> {
                    startDate[0] = picked;
                    dialogBinding.startDateInput.setText(format.format(new Date(picked)));
                    dialogBinding.startDateLayout.setError(null);
                }));

        boolean noEndDate = endDate[0] == 0L;
        dialogBinding.noEndDateSwitch.setChecked(noEndDate);
        dialogBinding.endDateLayout.setEnabled(!noEndDate);
        if (!noEndDate) {
            dialogBinding.endDateInput.setText(format.format(new Date(endDate[0])));
        }
        dialogBinding.noEndDateSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            dialogBinding.endDateLayout.setEnabled(!isChecked);
            dialogBinding.endDateLayout.setError(null);
            if (isChecked) {
                dialogBinding.endDateInput.setText("");
                endDate[0] = 0L;
            } else if (endDate[0] == 0L) {
                dialogBinding.endDateInput.setText("");
            } else {
                dialogBinding.endDateInput.setText(format.format(new Date(endDate[0])));
            }
        });
        dialogBinding.endDateInput.setOnClickListener(v ->
                pickDate(endDate[0] == 0L ? startDate[0] : endDate[0], picked -> {
                    endDate[0] = picked;
                    dialogBinding.endDateInput.setText(format.format(new Date(picked)));
                    dialogBinding.endDateLayout.setError(null);
                }));

        if (existing != null) {
            dialogBinding.nameInput.setText(existing.name);
            dialogBinding.amountInput.setText(AmountUtil.toInputText(existing.amount));
            dialogBinding.intervalInput.setText(String.valueOf(Math.max(1, existing.interval)));
            if (existing.note != null) {
                dialogBinding.noteInput.setText(existing.note);
            }
            dialogBinding.enabledSwitch.setVisibility(View.VISIBLE);
            dialogBinding.enabledSwitch.setChecked(existing.isEnabled);
        }

        ClearErrorWatcher.attach(dialogBinding.nameLayout, dialogBinding.amountLayout,
                dialogBinding.categoryLayout, dialogBinding.accountLayout,
                dialogBinding.intervalLayout, dialogBinding.startDateLayout,
                dialogBinding.endDateLayout);

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(existing == null ? R.string.recurring_add : R.string.recurring_edit_title)
                .setView(dialogBinding.getRoot())
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_save, null)
                .create();
        dialog.setOnDismissListener(d -> {
            viewModel.getExpenseCategories().removeObserver(expenseObserver);
            viewModel.getIncomeCategories().removeObserver(incomeObserver);
            viewModel.getActiveAccounts().removeObserver(accountObserver);
        });
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String error = collectAndSave(existing, dialogBinding, selectedType,
                    selectedFrequency, startDate, endDate, categoryPicker, accountPicker);
            if (error != null) {
                return; // 错误已写进对应输入框，留在弹窗内
            }
            dialog.dismiss();
        });
    }

    /**
     * 校验并保存。返回 null 表示校验通过且已提交；否则返回占位错误并已把
     * 具体错误写到对应输入框。
     */
    @Nullable
    private String collectAndSave(@Nullable RecurringTransactionEntity existing,
                                  DialogRecurringEditBinding dialogBinding, int[] selectedType,
                                  int[] selectedFrequency, long[] startDate, long[] endDate,
                                  CategoryPicker categoryPicker, AccountPicker accountPicker) {
        Editable nameEditable = dialogBinding.nameInput.getText();
        String name = nameEditable == null ? "" : nameEditable.toString().trim();
        if (name.isEmpty()) {
            dialogBinding.nameLayout.setError(getString(R.string.recurring_name_error));
            return "name";
        }
        Editable amountEditable = dialogBinding.amountInput.getText();
        String amountText = amountEditable == null ? "" : amountEditable.toString().trim();
        long amount = amountText.isEmpty() ? 0L : AmountUtil.parseToCents(amountText);
        if (amount <= 0L || amount == AmountUtil.INVALID) {
            dialogBinding.amountLayout.setError(getString(R.string.recurring_amount_error));
            return "amount";
        }
        Long categoryId = categoryPicker.selectedId();
        if (categoryId == null) {
            dialogBinding.categoryLayout.setError(getString(R.string.recurring_category_error));
            return "category";
        }
        Long accountId = accountPicker.selectedId();
        if (accountId == null) {
            dialogBinding.accountLayout.setError(getString(R.string.recurring_account_error));
            return "account";
        }
        Editable intervalEditable = dialogBinding.intervalInput.getText();
        String intervalText = intervalEditable == null ? "" : intervalEditable.toString().trim();
        int interval = 1;
        if (!intervalText.isEmpty()) {
            try {
                interval = Integer.parseInt(intervalText);
            } catch (NumberFormatException e) {
                interval = 0;
            }
        }
        if (interval < 1) {
            dialogBinding.intervalLayout.setError(getString(R.string.recurring_interval_error));
            return "interval";
        }
        if (!dialogBinding.noEndDateSwitch.isChecked()) {
            if (endDate[0] == 0L) {
                dialogBinding.endDateLayout.setError(getString(R.string.recurring_end_before_start_error));
                return "endDate";
            }
            if (endDate[0] < startDate[0]) {
                dialogBinding.endDateLayout.setError(
                        getString(R.string.recurring_end_before_start_error));
                return "endDate";
            }
        }

        RecurringTransactionEntity entity = new RecurringTransactionEntity();
        if (existing != null) {
            entity.id = existing.id;
        }
        entity.name = name;
        entity.type = selectedType[0];
        entity.amount = amount;
        entity.categoryId = categoryId;
        entity.accountId = accountId;
        entity.frequency = selectedFrequency[0];
        entity.interval = interval;
        entity.startDate = startDate[0];
        entity.endDate = dialogBinding.noEndDateSwitch.isChecked() ? 0L : endDate[0];
        entity.isEnabled = existing == null || dialogBinding.enabledSwitch.isChecked();
        Editable noteEditable = dialogBinding.noteInput.getText();
        String note = noteEditable == null ? "" : noteEditable.toString().trim();
        entity.note = note.isEmpty() ? null : note;

        viewModel.save(entity, id -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            Toast.makeText(this, R.string.recurring_saved, Toast.LENGTH_SHORT).show();
        });
        return null;
    }

    // ------------------------------------------------------------------
    // 弹窗内的小工具
    // ------------------------------------------------------------------

    private static String frequencyLabel(String[] labels, int frequency) {
        switch (frequency) {
            case RecurringTransactionEntity.FREQUENCY_WEEKLY:
                return labels[1];
            case RecurringTransactionEntity.FREQUENCY_MONTHLY:
                return labels[2];
            case RecurringTransactionEntity.FREQUENCY_YEARLY:
                return labels[3];
            case RecurringTransactionEntity.FREQUENCY_DAILY:
            default:
                return labels[0];
        }
    }

    private static int frequencyOf(int position) {
        switch (position) {
            case 1:
                return RecurringTransactionEntity.FREQUENCY_WEEKLY;
            case 2:
                return RecurringTransactionEntity.FREQUENCY_MONTHLY;
            case 3:
                return RecurringTransactionEntity.FREQUENCY_YEARLY;
            case 0:
            default:
                return RecurringTransactionEntity.FREQUENCY_DAILY;
        }
    }

    /** 弹 MaterialDatePicker；millis 走 UTC 桥接，避免东八区少一天。 */
    private void pickDate(long currentDayMillis,
                          com.skyanchor.bookkeeping.util.Callback<Long> onPicked) {
        MaterialDatePicker<Long> picker = MaterialDatePicker.Builder.datePicker()
                .setSelection(DateUtil.toUtcDayMillis(currentDayMillis))
                .setTitleText(R.string.recurring_start_date_hint)
                .build();
        picker.addOnPositiveButtonClickListener(utcMillis ->
                onPicked.onResult(DateUtil.fromUtcDayMillis(utcMillis)));
        picker.show(getSupportFragmentManager(), "recurring_date_picker");
    }

    private static <T> List<T> snapshot(@Nullable List<T> list) {
        return list == null ? Collections.emptyList() : list;
    }

    private static List<AccountEntity> snapshotAccounts(@Nullable List<AccountEntity> accounts) {
        return accounts == null ? Collections.emptyList() : accounts;
    }

    /**
     * 分类下拉的状态封装：按类型重建选项、按 id 回选。选项列表由观察者随时刷新，
     * 刷新时保留已选 id，因此数据晚于弹窗到达也不会丢失编辑中的选择。
     */
    private static final class CategoryPicker {

        private final DialogRecurringEditBinding binding;
        private List<CategoryEntity> expenseCategories;
        private List<CategoryEntity> incomeCategories;
        private int currentType;
        private List<CategoryEntity> current;
        @Nullable
        private Long selectedId = null;

        CategoryPicker(DialogRecurringEditBinding binding,
                       List<CategoryEntity> expenseCategories,
                       List<CategoryEntity> incomeCategories) {
            this.binding = binding;
            this.expenseCategories = expenseCategories;
            this.incomeCategories = incomeCategories;
            this.currentType = CategoryEntity.TYPE_EXPENSE;
            this.current = expenseCategories;
            binding.categoryInput.setOnItemClickListener((parent, view, position, id) -> {
                if (position >= 0 && position < current.size()) {
                    selectedId = current.get(position).id;
                    binding.categoryLayout.setError(null);
                }
            });
        }

        /** 某一类型的新数据到达：仅当正是当前展示的类型时重建选项。 */
        void setCategories(int type, List<CategoryEntity> categories) {
            if (type == CategoryEntity.TYPE_INCOME) {
                incomeCategories = categories;
            } else {
                expenseCategories = categories;
            }
            if (type == currentType) {
                current = categories;
                refresh();
            }
        }

        /** 切换类型：清空已选并按新类型重建选项。 */
        void showType(int type) {
            currentType = type;
            current = type == CategoryEntity.TYPE_INCOME ? incomeCategories : expenseCategories;
            selectedId = null;
            refresh();
        }

        void select(@Nullable Long categoryId) {
            selectedId = categoryId;
            applySelection();
        }

        /** 已选 id 必须仍存在于当前类型列表中，已删除的分类视为未选。 */
        @Nullable
        Long selectedId() {
            if (selectedId == null) {
                return null;
            }
            for (CategoryEntity category : current) {
                if (category.id == selectedId) {
                    return selectedId;
                }
            }
            return null;
        }

        private void refresh() {
            String[] labels = new String[current.size()];
            for (int i = 0; i < current.size(); i++) {
                labels[i] = label(current.get(i));
            }
            binding.categoryInput.setSimpleItems(labels);
            applySelection();
        }

        private void applySelection() {
            if (selectedId != null) {
                for (int i = 0; i < current.size(); i++) {
                    if (current.get(i).id == selectedId) {
                        binding.categoryInput.setText(label(current.get(i)), false);
                        return;
                    }
                }
            }
            binding.categoryInput.setText("", false);
        }

        /** 「emoji + 名称」单行展示，与回收站等列表一致；旧数据无图标时只显示名称。 */
        private static String label(CategoryEntity category) {
            return category.icon.isEmpty() ? category.name
                    : category.icon + " " + category.name;
        }
    }

    /** 账户下拉的状态封装：账户列表由观察者随时刷新，刷新时保留已选。 */
    private static final class AccountPicker {

        private final DialogRecurringEditBinding binding;
        private List<AccountEntity> accounts;
        @Nullable
        private Long selectedId = null;

        AccountPicker(DialogRecurringEditBinding binding, List<AccountEntity> accounts) {
            this.binding = binding;
            this.accounts = accounts;
            binding.accountInput.setOnItemClickListener((parent, view, position, id) -> {
                if (position >= 0 && position < accounts.size()) {
                    selectedId = accounts.get(position).id;
                    binding.accountLayout.setError(null);
                }
            });
            refresh();
        }

        void setAccounts(List<AccountEntity> accounts) {
            this.accounts = accounts;
            refresh();
        }

        void select(@Nullable Long accountId) {
            selectedId = accountId;
            applySelection();
        }

        /** 已选 id 必须仍存在于活跃账户列表中，已归档 / 删除的账户视为未选。 */
        @Nullable
        Long selectedId() {
            if (selectedId == null) {
                return null;
            }
            for (AccountEntity account : accounts) {
                if (account.id == selectedId) {
                    return selectedId;
                }
            }
            return null;
        }

        private void refresh() {
            String[] labels = new String[accounts.size()];
            for (int i = 0; i < accounts.size(); i++) {
                labels[i] = label(accounts.get(i));
            }
            binding.accountInput.setSimpleItems(labels);
            applySelection();
        }

        private void applySelection() {
            if (selectedId != null) {
                for (int i = 0; i < accounts.size(); i++) {
                    if (accounts.get(i).id == selectedId) {
                        binding.accountInput.setText(label(accounts.get(i)), false);
                        return;
                    }
                }
            }
            binding.accountInput.setText("", false);
        }

        /** 「emoji + 名称」单行展示，与记账页账户下拉一致。 */
        private static String label(AccountEntity account) {
            return AccountTypes.emoji(account.type) + " " + account.name;
        }
    }

    /** 输入变化即清除对应输入框的错误提示，与账户编辑弹窗的手感一致。 */
    private static final class ClearErrorWatcher implements TextWatcher {

        private final com.google.android.material.textfield.TextInputLayout[] layouts;

        private ClearErrorWatcher(com.google.android.material.textfield.TextInputLayout[] layouts) {
            this.layouts = layouts;
        }

        static void attach(com.google.android.material.textfield.TextInputLayout... layouts) {
            ClearErrorWatcher watcher = new ClearErrorWatcher(layouts);
            for (com.google.android.material.textfield.TextInputLayout layout : layouts) {
                layout.getEditText().addTextChangedListener(watcher);
            }
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }

        @Override
        public void afterTextChanged(Editable s) {
            for (com.google.android.material.textfield.TextInputLayout layout : layouts) {
                layout.setError(null);
            }
        }
    }
}
