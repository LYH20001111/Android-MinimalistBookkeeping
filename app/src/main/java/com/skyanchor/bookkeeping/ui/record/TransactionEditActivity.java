package com.skyanchor.bookkeeping.ui.record;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;

import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;
import com.skyanchor.bookkeeping.R;
import com.skyanchor.bookkeeping.data.entity.AccountEntity;
import com.skyanchor.bookkeeping.data.entity.CategoryEntity;
import com.skyanchor.bookkeeping.data.entity.TransactionEntity;
import com.skyanchor.bookkeeping.data.entity.TransactionItem;
import com.skyanchor.bookkeeping.databinding.ActivityTransactionEditBinding;
import com.skyanchor.bookkeeping.domain.transaction.TransferValidator;
import com.skyanchor.bookkeeping.ui.adapter.CategoryGridAdapter;
import com.skyanchor.bookkeeping.util.AccountTypes;
import com.skyanchor.bookkeeping.util.AmountUtil;
import com.skyanchor.bookkeeping.util.DateLabels;
import com.skyanchor.bookkeeping.util.DateUtil;
import com.skyanchor.bookkeeping.util.InsetsUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 记一笔 / 编辑账单。新增与编辑复用同一个 Activity，靠 {@link #EXTRA_TRANSACTION_ID} 区分。
 *
 * <p>保存与删除都只写库，不做任何界面回填：记录页与图表页通过 LiveData 观察同一数据源，
 * 返回后自动刷新，满足基线第 11 章「统计一致性」。
 */
public class TransactionEditActivity extends AppCompatActivity {

    /** 待编辑账单的 id；缺省表示新增。 */
    public static final String EXTRA_TRANSACTION_ID = "extra_transaction_id";

    /** 新增时的默认业务日期（本地当天 00:00 的 millis）。 */
    public static final String EXTRA_BUSINESS_DATE = "extra_business_date";

    private static final String TAG_DATE_PICKER = "date_picker";
    private static final String TAG_TIME_PICKER = "time_picker";

    private static final String STATE_DATE = "state_date";
    private static final String STATE_HOUR = "state_hour";
    private static final String STATE_MINUTE = "state_minute";
    private static final String STATE_CATEGORY_ID = "state_category_id";
    private static final String STATE_ACCOUNT_ID = "state_account_id";
    private static final String STATE_FROM_ACCOUNT_ID = "state_from_account_id";
    private static final String STATE_TO_ACCOUNT_ID = "state_to_account_id";
    private static final String STATE_TYPE = "state_type";

    private ActivityTransactionEditBinding binding;
    private TransactionEditViewModel viewModel;
    private CategoryGridAdapter categoryAdapter;

    /** 0 表示新增。 */
    private long transactionId;
    private long selectedDate;
    private int selectedHour;
    private int selectedMinute;
    private long selectedCategoryId;
    private long selectedAccountId;

    /** 转账：转出账户与转入账户（V2 新增，两账户必填且不同）。 */
    private long selectedFromAccountId;
    private long selectedToAccountId;

    /** 最近一次活跃账户列表，下拉 position → 账户 id 靠它映射。 */
    @NonNull
    private List<AccountEntity> accountList = new ArrayList<>();

    /** 当前是否没有任何账户 / 分类，用于统一控制表单区块可见性。 */
    private boolean accountsEmpty;
    private boolean categoriesEmpty;

    /** 表单是否已回灌过，避免重建 Activity 时覆盖用户正在编辑的内容。 */
    private boolean formApplied;

    /** 程序化切换类型开关时置位，防止监听器把已选分类清掉。 */
    private boolean updatingTypeUi;

    @Nullable
    private String deleteMessage;

    public static void startAdd(@NonNull Context context, long businessDate) {
        Intent intent = new Intent(context, TransactionEditActivity.class);
        intent.putExtra(EXTRA_BUSINESS_DATE, businessDate);
        context.startActivity(intent);
    }

    public static void startEdit(@NonNull Context context, long transactionId) {
        Intent intent = new Intent(context, TransactionEditActivity.class);
        intent.putExtra(EXTRA_TRANSACTION_ID, transactionId);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityTransactionEditBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        InsetsUtil.applyTopAndHorizontalPadding(binding.editRoot);
        InsetsUtil.applyImeBottomPadding(binding.editScroll);
        InsetsUtil.syncSystemBarAppearance(this);

        viewModel = new ViewModelProvider(this).get(TransactionEditViewModel.class);
        transactionId = getIntent().getLongExtra(EXTRA_TRANSACTION_ID, 0L);

        categoryAdapter = new CategoryGridAdapter(category -> selectedCategoryId = category.id);
        binding.categoryGrid.setLayoutManager(new GridLayoutManager(this,
                getResources().getInteger(R.integer.category_grid_span)));
        binding.categoryGrid.setAdapter(categoryAdapter);

        binding.toolbar.setTitle(transactionId == 0L
                ? R.string.edit_title_new : R.string.edit_title_edit);
        binding.toolbar.setNavigationOnClickListener(v -> finish());

        restoreFormState(savedInstanceState);
        renderDate();
        renderTime();
        updateAmountPreview();

        binding.amountInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                binding.amountLayout.setError(null);
                updateAmountPreview();
            }
        });

        binding.typeGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (updatingTypeUi || !isChecked) {
                return;
            }
            // 换类型后原分类不再合法，清空选中项，等新分类列表到达时自动落到首项。
            selectedCategoryId = 0L;
            categoryAdapter.setSelectedId(0L);
            viewModel.selectType(typeOfButton(checkedId));
        });

        binding.accountInput.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < accountList.size()) {
                selectedAccountId = accountList.get(position).id;
            }
        });
        binding.transferFromInput.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < accountList.size()) {
                selectedFromAccountId = accountList.get(position).id;
                // 转出改动后若与转入撞成同一账户，清空转入待用户重选。
                if (selectedToAccountId == selectedFromAccountId) {
                    selectedToAccountId = 0L;
                }
                renderTransferSelection();
            }
        });
        binding.transferToInput.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < accountList.size()) {
                selectedToAccountId = accountList.get(position).id;
            }
        });

        binding.dateButton.setOnClickListener(v -> showDatePicker());
        binding.timeButton.setOnClickListener(v -> showTimePicker());
        binding.saveButton.setOnClickListener(v -> save());

        viewModel.getType().observe(this, type -> renderType(
                type == null ? CategoryEntity.TYPE_EXPENSE : type));
        viewModel.getCategories().observe(this, this::onCategoriesChanged);
        viewModel.getAccounts().observe(this, this::onAccountsChanged);

        if (transactionId != 0L) {
            binding.deleteButton.setOnClickListener(v -> showDeleteDialog());
            viewModel.getSource().observe(this, this::onSourceLoaded);
            viewModel.loadTransaction(transactionId);
        }

        reattachPickerListeners();
    }

    /**
     * 恢复表单状态。金额与备注文本由系统按控件 id 自动恢复，这里只处理
     * 非控件状态：日期、时间、分类与类型。
     */
    private void restoreFormState(@Nullable Bundle savedInstanceState) {
        if (savedInstanceState != null) {
            selectedDate = savedInstanceState.getLong(STATE_DATE, DateUtil.today());
            selectedHour = savedInstanceState.getInt(STATE_HOUR, 0);
            selectedMinute = savedInstanceState.getInt(STATE_MINUTE, 0);
            selectedCategoryId = savedInstanceState.getLong(STATE_CATEGORY_ID, 0L);
            selectedAccountId = savedInstanceState.getLong(STATE_ACCOUNT_ID, 0L);
            selectedFromAccountId = savedInstanceState.getLong(STATE_FROM_ACCOUNT_ID, 0L);
            selectedToAccountId = savedInstanceState.getLong(STATE_TO_ACCOUNT_ID, 0L);
            viewModel.selectType(savedInstanceState.getInt(STATE_TYPE, CategoryEntity.TYPE_EXPENSE));
            // 编辑模式下的表单文本已被系统恢复，不能再让回读结果覆盖一遍。
            formApplied = transactionId != 0L;
            return;
        }
        long businessDate = getIntent().getLongExtra(EXTRA_BUSINESS_DATE, 0L);
        selectedDate = businessDate > 0L ? DateUtil.startOfDay(businessDate) : DateUtil.today();
        long now = System.currentTimeMillis();
        selectedHour = DateUtil.hourOf(now);
        selectedMinute = DateUtil.minuteOf(now);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putLong(STATE_DATE, selectedDate);
        outState.putInt(STATE_HOUR, selectedHour);
        outState.putInt(STATE_MINUTE, selectedMinute);
        outState.putLong(STATE_CATEGORY_ID, selectedCategoryId);
        outState.putLong(STATE_ACCOUNT_ID, selectedAccountId);
        outState.putLong(STATE_FROM_ACCOUNT_ID, selectedFromAccountId);
        outState.putLong(STATE_TO_ACCOUNT_ID, selectedToAccountId);
        Integer type = viewModel.getType().getValue();
        outState.putInt(STATE_TYPE, type == null ? CategoryEntity.TYPE_EXPENSE : type);
    }

    // ------------------------------------------------------------------
    // 分类
    // ------------------------------------------------------------------

    private void onCategoriesChanged(@Nullable List<CategoryEntity> categories) {
        categoryAdapter.submitList(categories);
        categoriesEmpty = categories == null || categories.isEmpty();
        applyDefaultSelection(categories);
        renderFormVisibility();
    }

    /** 当前选中项失效时，落到 sortOrder 最小的分类，对应基线「默认选中常用分类」。 */
    private void applyDefaultSelection(@Nullable List<CategoryEntity> categories) {
        if (categories == null || categories.isEmpty()) {
            selectedCategoryId = 0L;
            return;
        }
        boolean stillValid = false;
        for (CategoryEntity category : categories) {
            if (category.id == selectedCategoryId) {
                stillValid = true;
                break;
            }
        }
        if (!stillValid) {
            selectedCategoryId = categories.get(0).id;
        }
        categoryAdapter.setSelectedId(selectedCategoryId);
    }

    // ------------------------------------------------------------------
    // 账户（V2：支出 / 收入必选落账账户；转账需转出 + 转入两账户）
    // ------------------------------------------------------------------

    private void onAccountsChanged(@Nullable List<AccountEntity> accounts) {
        accountList = accounts == null ? new ArrayList<>() : accounts;
        accountsEmpty = accountList.isEmpty();
        if (accountsEmpty) {
            renderFormVisibility();
            return;
        }
        String[] labels = new String[accountList.size()];
        for (int i = 0; i < accountList.size(); i++) {
            labels[i] = accountLabel(accountList.get(i));
        }
        // 三个下拉共用同一份活跃账户标签：单账户（支出 / 收入）+ 转出 + 转入。
        binding.accountInput.setSimpleItems(labels);
        binding.transferFromInput.setSimpleItems(labels);
        binding.transferToInput.setSimpleItems(labels);

        applyDefaultAccountSelection();
        applyDefaultTransferSelection();
        renderAccountSelection();
        renderTransferSelection();
        renderFormVisibility();
    }

    /**
     * 当前选中账户失效（新增未选 / 编辑历史账单 account_id 为 NULL）时，
     * 落到首个账户，保证「账户必填」且下拉始终有可选项。
     * 原账户已归档的情形由 ViewModel 的候选合并规则保住（V2.1），不会走到这里。
     */
    private void applyDefaultAccountSelection() {
        if (!containsAccount(selectedAccountId)) {
            selectedAccountId = accountList.get(0).id;
        }
    }

    /**
     * 转账默认：转出落到首个账户，转入落到首个「与转出不同」的账户；
     * 只有一个账户时转入保持未选（0），保存时由校验拦下并提示。
     */
    private void applyDefaultTransferSelection() {
        if (!containsAccount(selectedFromAccountId)) {
            selectedFromAccountId = accountList.get(0).id;
        }
        if (!containsAccount(selectedToAccountId) || selectedToAccountId == selectedFromAccountId) {
            selectedToAccountId = 0L;
            for (AccountEntity account : accountList) {
                if (account.id != selectedFromAccountId) {
                    selectedToAccountId = account.id;
                    break;
                }
            }
        }
    }

    private boolean containsAccount(long accountId) {
        for (AccountEntity account : accountList) {
            if (account.id == accountId) {
                return true;
            }
        }
        return false;
    }

    private void renderAccountSelection() {
        binding.accountInput.setText(labelOf(selectedAccountId), false);
    }

    private void renderTransferSelection() {
        binding.transferFromInput.setText(labelOf(selectedFromAccountId), false);
        binding.transferToInput.setText(labelOf(selectedToAccountId), false);
    }

    @NonNull
    private String labelOf(long accountId) {
        for (AccountEntity account : accountList) {
            if (account.id == accountId) {
                return accountLabel(account);
            }
        }
        return "";
    }

    /**
     * 账户下拉标签。已归档账户（仅编辑旧账单时进入候选，见 ViewModel 的合并规则）
     * 带「已归档」徽标，明示当前选择而不是悄悄保留（V2.1 基线第 26 章）。
     */
    @NonNull
    private static String accountLabel(@NonNull AccountEntity account) {
        String label = AccountTypes.emoji(account.type) + " " + account.name;
        return account.isArchived ? label + "（已归档）" : label;
    }

    // ------------------------------------------------------------------
    // 表单区块可见性与类型映射
    // ------------------------------------------------------------------

    /**
     * 按当前交易类型切换表单区块：
     * 支出 / 收入显示分类区与单账户选择；转账隐藏分类区、显示转出 / 转入两账户。
     * 没有任何账户时统一显示「先去创建账户」提示并隐藏所有账户选择器。
     */
    private void renderFormVisibility() {
        Integer typeValue = viewModel.getType().getValue();
        boolean transfer = typeValue != null && typeValue == CategoryEntity.TYPE_TRANSFER;

        boolean showCategory = !transfer && !categoriesEmpty;
        binding.categoryTitle.setVisibility(showCategory ? View.VISIBLE : View.GONE);
        binding.categoryGrid.setVisibility(showCategory ? View.VISIBLE : View.GONE);
        binding.categoryEmpty.setVisibility(!transfer && categoriesEmpty ? View.VISIBLE : View.GONE);

        binding.accountEmpty.setVisibility(accountsEmpty ? View.VISIBLE : View.GONE);
        binding.accountLayout.setVisibility(!transfer && !accountsEmpty ? View.VISIBLE : View.GONE);
        binding.transferSection.setVisibility(transfer && !accountsEmpty ? View.VISIBLE : View.GONE);
    }

    /** 类型 → 分段按钮 id。 */
    private static int typeButtonId(int type) {
        if (type == CategoryEntity.TYPE_INCOME) {
            return R.id.typeIncome;
        }
        if (type == CategoryEntity.TYPE_TRANSFER) {
            return R.id.typeTransfer;
        }
        return R.id.typeExpense;
    }

    /** 分段按钮 id → 类型。 */
    private static int typeOfButton(int checkedId) {
        if (checkedId == R.id.typeIncome) {
            return CategoryEntity.TYPE_INCOME;
        }
        if (checkedId == R.id.typeTransfer) {
            return CategoryEntity.TYPE_TRANSFER;
        }
        return CategoryEntity.TYPE_EXPENSE;
    }

    // ------------------------------------------------------------------
    // 表单渲染
    // ------------------------------------------------------------------

    private void renderType(int type) {
        updatingTypeUi = true;
        binding.typeGroup.check(typeButtonId(type));
        updatingTypeUi = false;
        renderFormVisibility();
    }

    private void renderDate() {
        binding.dateButton.setText(DateLabels.fullDayLabel(this, selectedDate));
    }

    private void renderTime() {
        binding.timeButton.setText(DateUtil.formatHourMinute(selectedHour, selectedMinute));
    }

    /** 输入 35 展示 ¥35.00、输入 35.8 展示 ¥35.80（基线 5.3）。 */
    private void updateAmountPreview() {
        long cents = AmountUtil.parseToCents(textOf(binding.amountInput.getText()));
        binding.amountPreview.setText(AmountUtil.format(cents == AmountUtil.INVALID ? 0L : cents));
    }

    // ------------------------------------------------------------------
    // 日期 / 时间选择器
    // ------------------------------------------------------------------

    private void showDatePicker() {
        MaterialDatePicker<Long> picker = MaterialDatePicker.Builder.datePicker()
                .setTitleText(R.string.edit_date_title)
                .setSelection(DateUtil.toUtcDayMillis(selectedDate))
                .build();
        picker.addOnPositiveButtonClickListener(this::onDatePicked);
        picker.show(getSupportFragmentManager(), TAG_DATE_PICKER);
    }

    private void onDatePicked(@Nullable Long selection) {
        if (selection == null) {
            return;
        }
        selectedDate = DateUtil.fromUtcDayMillis(selection);
        renderDate();
    }

    private void showTimePicker() {
        MaterialTimePicker picker = new MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_24H)
                .setHour(selectedHour)
                .setMinute(selectedMinute)
                .setTitleText(R.string.edit_time_title)
                .build();
        picker.addOnPositiveButtonClickListener(v -> onTimePicked(picker));
        picker.show(getSupportFragmentManager(), TAG_TIME_PICKER);
    }

    private void onTimePicked(@NonNull MaterialTimePicker picker) {
        selectedHour = picker.getHour();
        selectedMinute = picker.getMinute();
        renderTime();
    }

    /**
     * 选择器是 DialogFragment，旋转后会被系统重建并丢掉监听器，这里重新挂上，
     * 否则「旋转屏幕 → 选日期」会静默无效。
     */
    @SuppressWarnings("unchecked")
    private void reattachPickerListeners() {
        Fragment datePicker = getSupportFragmentManager().findFragmentByTag(TAG_DATE_PICKER);
        if (datePicker instanceof MaterialDatePicker) {
            ((MaterialDatePicker<Long>) datePicker).addOnPositiveButtonClickListener(this::onDatePicked);
        }
        Fragment timePicker = getSupportFragmentManager().findFragmentByTag(TAG_TIME_PICKER);
        if (timePicker instanceof MaterialTimePicker) {
            MaterialTimePicker picker = (MaterialTimePicker) timePicker;
            picker.addOnPositiveButtonClickListener(v -> onTimePicked(picker));
        }
    }

    // ------------------------------------------------------------------
    // 编辑模式回填与删除
    // ------------------------------------------------------------------

    private void onSourceLoaded(@Nullable TransactionItem item) {
        if (item == null) {
            return;
        }
        deleteMessage = getString(R.string.record_delete_message,
                item.displayIcon() + item.displayName(),
                AmountUtil.formatSigned(item.amount, item.isIncome()));
        binding.deleteButton.setVisibility(View.VISIBLE);
        if (formApplied) {
            return;
        }
        formApplied = true;
        // 先切类型再回填分类，否则分类列表会按默认类型加载。
        viewModel.selectType(item.type);
        selectedDate = item.date;
        selectedHour = DateUtil.hourOfTime(item.time);
        selectedMinute = DateUtil.minuteOfTime(item.time);
        selectedCategoryId = item.categoryId;
        categoryAdapter.setSelectedId(selectedCategoryId);
        selectedAccountId = item.accountId == null ? 0L : item.accountId;
        // 转账：accountId=转出、transferAccountId=转入；支出 / 收入只用 accountId。
        selectedFromAccountId = item.accountId == null ? 0L : item.accountId;
        selectedToAccountId = item.transferAccountId == null ? 0L : item.transferAccountId;
        binding.amountInput.setText(AmountUtil.toInputText(item.amount));
        binding.noteInput.setText(item.note == null ? "" : item.note);
        renderDate();
        renderTime();
        renderAccountSelection();
        renderTransferSelection();
        updateAmountPreview();
    }

    private void showDeleteDialog() {
        if (deleteMessage == null) {
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.record_delete_title)
                .setMessage(deleteMessage)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_delete, (dialog, which) -> delete())
                .show();
    }

    private void delete() {
        binding.deleteButton.setEnabled(false);
        viewModel.delete(transactionId, deleted -> {
            Toast.makeText(this, R.string.record_deleted, Toast.LENGTH_SHORT).show();
            finish();
        });
    }

    // ------------------------------------------------------------------
    // 保存
    // ------------------------------------------------------------------

    private void save() {
        long cents = AmountUtil.parseToCents(textOf(binding.amountInput.getText()));
        // INVALID 为 -1，与「金额必须大于 0」共用同一条校验分支。
        if (cents <= 0L) {
            binding.amountLayout.setError(getString(R.string.edit_error_amount));
            binding.amountInput.requestFocus();
            return;
        }
        Integer typeValue = viewModel.getType().getValue();
        int type = typeValue == null ? CategoryEntity.TYPE_EXPENSE : typeValue;

        TransactionEntity entity = new TransactionEntity();
        entity.id = transactionId;
        entity.type = type;
        entity.amount = cents;
        entity.date = selectedDate;
        entity.time = DateUtil.formatHourMinute(selectedHour, selectedMinute);
        String note = textOf(binding.noteInput.getText()).trim();
        entity.note = note.isEmpty() ? null : note;

        if (type == CategoryEntity.TYPE_TRANSFER) {
            // 转账：两账户必填且不同，不归属任何分类（category_id 写 NULL）。
            if (!TransferValidator.isValid(selectedFromAccountId, selectedToAccountId)) {
                int message = TransferValidator.isSameAccount(selectedFromAccountId, selectedToAccountId)
                        ? R.string.edit_error_transfer_same : R.string.edit_error_account;
                Snackbar.make(binding.editRoot, message, Snackbar.LENGTH_SHORT).show();
                return;
            }
            entity.categoryId = null;
            entity.accountId = selectedFromAccountId;
            entity.transferAccountId = selectedToAccountId;
        } else {
            if (selectedCategoryId == 0L) {
                Snackbar.make(binding.editRoot, R.string.edit_error_category, Snackbar.LENGTH_SHORT).show();
                return;
            }
            if (selectedAccountId == 0L) {
                Snackbar.make(binding.editRoot, R.string.edit_error_account, Snackbar.LENGTH_SHORT).show();
                return;
            }
            entity.categoryId = selectedCategoryId;
            entity.accountId = selectedAccountId;
            entity.transferAccountId = null;
        }

        binding.saveButton.setEnabled(false);
        viewModel.save(entity, id -> {
            Toast.makeText(this, R.string.edit_saved, Toast.LENGTH_SHORT).show();
            finish();
        });
    }

    @NonNull
    private static String textOf(@Nullable Editable editable) {
        return editable == null ? "" : editable.toString();
    }
}
