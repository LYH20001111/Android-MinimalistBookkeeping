package com.skyanchor.bookkeeping.ui.record;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupWindow;
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
import com.skyanchor.bookkeeping.domain.refund.RefundPolicy;
import com.skyanchor.bookkeeping.domain.transaction.TransferValidator;
import com.skyanchor.bookkeeping.ui.adapter.CategoryGridAdapter;
import com.skyanchor.bookkeeping.util.AccountTypes;
import com.skyanchor.bookkeeping.util.AmountUtil;
import com.skyanchor.bookkeeping.util.DateLabels;
import com.skyanchor.bookkeeping.util.DateUtil;
import com.skyanchor.bookkeeping.util.InsetsUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

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

    /** 编辑模式下从库里读出的原始账单快照，变更检测的比对基准；新增模式恒为 null。 */
    @Nullable
    private TransactionItem sourceItem;

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

        categoryAdapter = new CategoryGridAdapter(category -> {
            selectedCategoryId = category.id;
            updateChangeIndicator();
        });
        binding.categoryGrid.setLayoutManager(new GridLayoutManager(this,
                getResources().getInteger(R.integer.category_grid_span)));
        binding.categoryGrid.setAdapter(categoryAdapter);

        binding.toolbar.setTitle(transactionId == 0L
                ? R.string.edit_title_new : R.string.edit_title_edit);
        binding.toolbar.setNavigationOnClickListener(v -> finish());
        // V3.3 / V4.0：编辑模式下右上角挂三点按钮（新增模式既无历史可看也无可退账单，不挂）。
        // V4.2：按钮点击改弹自绘弹层，替代系统溢出菜单（溢出弹层行宽被主题拉宽，尾部空白大）。
        if (transactionId != 0L) {
            binding.toolbar.inflateMenu(R.menu.menu_transaction_edit);
            binding.toolbar.setOnMenuItemClickListener(item -> {
                if (item.getItemId() == R.id.action_more) {
                    showEditMenuPopup();
                    return true;
                }
                return false;
            });
        }

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
                updateChangeIndicator();
            }
        });

        binding.noteInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                updateChangeIndicator();
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
            updateChangeIndicator();
        });

        binding.accountInput.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < accountList.size()) {
                selectedAccountId = accountList.get(position).id;
                updateChangeIndicator();
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
                updateChangeIndicator();
            }
        });
        binding.transferToInput.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < accountList.size()) {
                selectedToAccountId = accountList.get(position).id;
                updateChangeIndicator();
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
            viewModel.getDraftRefunds().observe(this, drafts -> {
                renderAmountDetail(sourceItem);
                updateChangeIndicator();
            });
            viewModel.loadTransaction(transactionId);
        }

        reattachPickerListeners();
    }

    /**
     * 编辑页「更多」自绘弹层（popup_transaction_edit_menu.xml）。
     *
     * <p>不用系统溢出菜单：appcompat 会把弹层行拉到主题的首选条目宽度，
     * 短中文标题后面剩一大片空白；自绘弹层宽度随内容收窄。
     * 圆角、描边、阴影由根节点 MaterialCardView 负责，PopupWindow 侧只给透明背景
     * （outsideTouchable 与阴影生效的前提）。
     */
    private void showEditMenuPopup() {
        @Nullable View anchor = binding.toolbar.findViewById(R.id.action_more);
        PopupWindow popup = new PopupWindow(
                getLayoutInflater().inflate(R.layout.popup_transaction_edit_menu, null),
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);

        View content = popup.getContentView();
        content.findViewById(R.id.action_refund).setOnClickListener(v -> {
            popup.dismiss();
            showRefundSheet();
        });
        content.findViewById(R.id.action_refund_history).setOnClickListener(v -> {
            popup.dismiss();
            RefundRecordActivity.start(this, transactionId);
        });
        content.findViewById(R.id.action_edit_history).setOnClickListener(v -> {
            popup.dismiss();
            TransactionHistoryActivity.start(this, transactionId);
        });
        // END 对齐让弹层右缘贴三点按钮右缘，避免靠屏幕右边时超出可视区
        popup.showAsDropDown(anchor == null ? binding.toolbar : anchor, 0, 0, Gravity.END);
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

    /**
     * 从退款记录页返回后刷新可退额度与金额明细。
     *
     * <p>待编辑账单是按需回读的快照、不是 LiveData 订阅，子页面里改退款状态不会自动通知本页，
     * 不回读就会一直显示旧额度（看起来像「改了没生效」）。回读只更新额度展示：
     * {@link #onSourceLoaded} 已被 {@code formApplied} 短路，不会覆盖用户正在编辑的表单。
     */
    @Override
    protected void onResume() {
        super.onResume();
        if (transactionId != 0L) {
            viewModel.reloadSource(transactionId);
        }
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
        // 默认账户可能纠正失效选择，重算一次变更态。
        updateChangeIndicator();
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
        updateChangeIndicator();
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
        sourceItem = item;
        // 明细要在 formApplied 短路之前刷新：退款后回读快照只需更新额度展示。
        renderAmountDetail(item);
        deleteMessage = getString(R.string.record_delete_message,
                item.displayIcon() + item.displayName(),
                AmountUtil.formatSigned(item.amount, item.isIncome()));
        binding.deleteButton.setVisibility(View.VISIBLE);
        if (formApplied) {
            // 重建 Activity：表单文本已由系统恢复，只补比对基准并重算变更态。
            updateChangeIndicator();
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
        // 初始态应无变更；若默认选中纠正了失效分类 / 账户，小圆点会如实亮起。
        updateChangeIndicator();
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
    // 退款（V4.0）
    // ------------------------------------------------------------------

    /**
     * 弹出退款表单：只允许对已落库的支出账单发起。
     *
     * <p>V4.1：确认退款不再直接写库，只追加一条草稿（见
     * {@link TransactionEditViewModel#getDraftRefunds()}），保存账单时才逐笔落库。
     * 因此这里的可退额度既不能只算库里的累计（会重复退），也不能算成已生效（会误显示角标）。
     * 额度基数取表单当前金额——那才是保存后真正生效的原额。
     */
    private void showRefundSheet() {
        TransactionItem source = sourceItem;
        if (source == null || source.type != CategoryEntity.TYPE_EXPENSE) {
            Snackbar.make(binding.editRoot, R.string.edit_refund_unsupported,
                    Snackbar.LENGTH_SHORT).show();
            return;
        }
        long baseAmount = AmountUtil.parseToCents(textOf(binding.amountInput.getText()));
        if (baseAmount <= 0L) {
            baseAmount = source.amount;
        }
        long quota = RefundPolicy.remainingQuota(
                baseAmount,
                source.refundedAmount + viewModel.draftReceivedTotal(),
                source.pendingRefundAmount + viewModel.draftPendingTotal());
        if (quota <= 0L) {
            Snackbar.make(binding.editRoot, R.string.refund_error_exhausted,
                    Snackbar.LENGTH_SHORT).show();
            return;
        }
        new RefundSheetDialog(this, baseAmount, quota,
                (amountCents, reason, toReceived) -> {
                    viewModel.addDraftRefund(amountCents, reason, toReceived);
                    Toast.makeText(this, R.string.refund_draft_added,
                            Toast.LENGTH_SHORT).show();
                }).show();
    }

    /**
     * 展示「原始金额 / 已退款 / 待退款 / 实际支付」。
     *
     * <p>V4.1：尚未落库的退款草稿直接并入已退 / 待退两行，不单列提示——明细口径与
     * 保存后完全一致，用户不必知道哪一笔还在草稿里，也就不会出现保存前后的第二次落差。
     */
    private void renderAmountDetail(@Nullable TransactionItem item) {
        boolean hasRefund = item != null && (item.hasRefund() || !viewModel.currentDrafts()
                .isEmpty());
        binding.amountDetail.setVisibility(hasRefund ? View.VISIBLE : View.GONE);
        if (!hasRefund) {
            return;
        }
        long refunded = item.refundedAmount + viewModel.draftReceivedTotal();
        long pending = item.pendingRefundAmount + viewModel.draftPendingTotal();
        binding.amountDetailOrigin.setText(AmountUtil.format(item.amount));
        binding.amountDetailRefundedRow.setVisibility(refunded > 0L ? View.VISIBLE : View.GONE);
        if (refunded > 0L) {
            binding.amountDetailRefunded.setText(AmountUtil.format(-refunded));
        }
        binding.amountDetailPendingRow.setVisibility(pending > 0L ? View.VISIBLE : View.GONE);
        if (pending > 0L) {
            binding.amountDetailPending.setText(AmountUtil.format(pending));
        }
        // 与 TransactionItem.netAmount() 同样的非负钳位：草稿并入后也不该显示成负数。
        binding.amountDetailNet.setText(AmountUtil.format(Math.max(0L, item.amount - refunded)));
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
        // V4.0：下调金额不得低于已发生的退款累计（含待到账），否则这些退款无源可退。
        // V4.1：草稿退款也算在内——它保存后就会变成真正的退款，此时不挡就会存进超退状态。
        TransactionItem source = sourceItem;
        long committedRefunded = source == null ? 0L : source.refundedAmount;
        long committedPending = source == null ? 0L : source.pendingRefundAmount;
        long refundedTotal = committedRefunded + viewModel.draftReceivedTotal();
        long pendingTotal = committedPending + viewModel.draftPendingTotal();
        if (source != null && !RefundPolicy.isAmountEditAllowed(cents, refundedTotal, pendingTotal)) {
            binding.amountLayout.setError(getString(R.string.edit_error_amount_below_refund,
                    AmountUtil.format(refundedTotal + pendingTotal)));
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

        // V3.4：编辑已有账单且有字段变更时，先弹窗列出「旧值 → 新值」再落库，
        // 防误触保存；无变更或新增模式直接保存。
        List<String> changes = computeChanges();
        if (!changes.isEmpty()) {
            showChangeConfirmDialog(entity, changes);
            return;
        }
        performSave(entity);
    }

    /** 保存前确认弹窗：逐条列出变更明细，确认后才真正写库。 */
    private void showChangeConfirmDialog(@NonNull TransactionEntity entity,
                                         @NonNull List<String> changes) {
        List<String> lines = new ArrayList<>(changes);
        // 草稿退款也会在这一次保存里落库，不并列出来就成了「弹窗说只改备注、实际还退了钱」。
        List<TransactionEditViewModel.DraftRefund> drafts = viewModel.currentDrafts();
        if (!drafts.isEmpty()) {
            lines.add(getString(R.string.edit_amount_draft,
                    AmountUtil.format(viewModel.draftReceivedTotal()
                            + viewModel.draftPendingTotal()), drafts.size()));
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.edit_change_confirm_title)
                .setMessage(TextUtils.join("\n", lines))
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_save, (dialog, which) -> performSave(entity))
                .show();
    }

    private void performSave(@NonNull TransactionEntity entity) {
        binding.saveButton.setEnabled(false);
        List<TransactionEditViewModel.DraftRefund> drafts = viewModel.currentDrafts();
        viewModel.save(entity, id -> commitDrafts(id == null ? 0L : id, drafts, 0, 0));
    }

    /**
     * 账单落库后按顺序提交退款草稿，全部处理完再结束页面。
     *
     * <p>必须串行：每笔退款都要看到前一笔已写入的累计才能复核额度，并行提交会让超出原额
     * 的草稿蒙混过关（仓库层是同一事务内的真值累加，串行才把它真正用上）。
     * 被拒的草稿不重试，末尾统一提示，避免静默丢钱。
     */
    private void commitDrafts(long savedId,
                              @NonNull List<TransactionEditViewModel.DraftRefund> drafts,
                              int index, int failed) {
        if (index >= drafts.size()) {
            viewModel.clearDraftRefunds();
            Toast.makeText(this, failed == 0
                    ? R.string.edit_saved : R.string.edit_saved_refund_failed,
                    Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        TransactionEditViewModel.DraftRefund draft = drafts.get(index);
        viewModel.requestRefund(savedId, draft.amount, draft.reason, draft.toReceived,
                accepted -> commitDrafts(savedId, drafts, index + 1,
                        accepted == null || !accepted ? failed + 1 : failed));
    }

    // ------------------------------------------------------------------
    // 变更检测（V3.4）
    //
    // 与仓库侧编辑日志（buildUpdateDetail）同一套字段与「字段：旧值 → 新值」格式，
    // 弹窗内容即落库日志的预览。仅编辑模式生效，新增模式恒为无变更。
    // ------------------------------------------------------------------

    /** 按保存按钮上的「内容已修改」小圆点亮 / 灭；待保存的退款草稿同样算未保存的改动。 */
    private void updateChangeIndicator() {
        boolean changed = !computeChanges().isEmpty() || !viewModel.currentDrafts().isEmpty();
        binding.saveChangeDot.setVisibility(changed ? View.VISIBLE : View.GONE);
    }

    /**
     * 对比当前表单与原账单快照，返回逐字段的「字段：旧值 → 新值」变更行；
     * 新增模式、快照未就绪或没有任何变化时返回空列表。
     */
    @NonNull
    private List<String> computeChanges() {
        List<String> changes = new ArrayList<>();
        TransactionItem source = sourceItem;
        if (transactionId == 0L || source == null || !formApplied) {
            return changes;
        }
        Integer typeValue = viewModel.getType().getValue();
        int type = typeValue == null ? CategoryEntity.TYPE_EXPENSE : typeValue;
        long cents = AmountUtil.parseToCents(textOf(binding.amountInput.getText()));
        boolean transfer = type == CategoryEntity.TYPE_TRANSFER;
        boolean oldTransfer = source.isTransfer();

        if (source.type != type) {
            changes.add(changeLine("类型", typeLabel(source.type), typeLabel(type)));
        }
        // 金额非法（<= 0）时保存会被校验拦下，不产生误导性的变更行。
        if (cents > 0L && source.amount != cents) {
            changes.add(changeLine("金额",
                    AmountUtil.format(source.amount), AmountUtil.format(cents)));
        }
        // 分类：转账双方都视为「无」（TransactionItem 的 categoryId 经 COALESCE 归 0）。
        Long oldCategoryId = oldTransfer || source.categoryId == 0L ? null : source.categoryId;
        Long newCategoryId = transfer ? null : selectedCategoryId;
        if (!Objects.equals(oldCategoryId, newCategoryId)) {
            changes.add(changeLine("分类",
                    categoryLabelOf(oldCategoryId, source), selectedCategoryLabel()));
        }
        // 账户：转账语义下叫「转出账户」，纯支出 / 收入叫「账户」，与编辑日志一致。
        Long oldAccountId = source.accountId;
        Long newAccountId = transfer ? selectedFromAccountId : selectedAccountId;
        if (!Objects.equals(oldAccountId, zeroToNull(newAccountId))) {
            String field = oldTransfer || transfer ? "转出账户" : "账户";
            changes.add(changeLine(field,
                    accountChangeLabel(oldAccountId, source.accountName),
                    accountChangeLabel(newAccountId, null)));
        }
        Long oldTransferAccountId = source.transferAccountId;
        Long newTransferAccountId = transfer ? selectedToAccountId : null;
        if (!Objects.equals(oldTransferAccountId, zeroToNull(newTransferAccountId))) {
            changes.add(changeLine("转入账户",
                    accountChangeLabel(oldTransferAccountId, source.transferAccountName),
                    accountChangeLabel(newTransferAccountId, null)));
        }
        if (source.date != selectedDate) {
            changes.add(changeLine("日期",
                    dayLogLabel(source.date), dayLogLabel(selectedDate)));
        }
        String newTime = DateUtil.formatHourMinute(selectedHour, selectedMinute);
        if (!Objects.equals(source.time, newTime)) {
            changes.add(changeLine("时间", source.time, newTime));
        }
        String oldNote = source.note == null ? "" : source.note;
        String newNote = textOf(binding.noteInput.getText()).trim();
        if (!oldNote.equals(newNote)) {
            changes.add(changeLine("备注", noteLabel(oldNote), noteLabel(newNote)));
        }
        return changes;
    }

    /** 追加一行「字段：旧值 → 新值」。 */
    @NonNull
    private static String changeLine(@NonNull String field,
                                     @NonNull String from, @NonNull String to) {
        return field + "：" + from + " → " + to;
    }

    @NonNull
    private String typeLabel(int type) {
        if (type == CategoryEntity.TYPE_INCOME) {
            return getString(R.string.edit_type_income);
        }
        if (type == CategoryEntity.TYPE_TRANSFER) {
            return getString(R.string.edit_type_transfer);
        }
        return getString(R.string.edit_type_expense);
    }

    /** 旧分类展示名（icon + 名称），复用联表快照里的名称；无分类时为「无」。 */
    @NonNull
    private static String categoryLabelOf(@Nullable Long categoryId,
                                          @NonNull TransactionItem source) {
        if (categoryId == null || categoryId == 0L || source.displayName().isEmpty()) {
            return "无";
        }
        return source.displayIcon() + " " + source.displayName();
    }

    /** 新分类展示名，取自当前网格选中项。 */
    @NonNull
    private String selectedCategoryLabel() {
        CategoryEntity category = categoryAdapter.getSelectedCategory();
        return category == null ? "无" : category.icon + " " + category.name;
    }

    /** 账户展示名：优先用当前候选列表标签（含已归档徽标），查不到时退回联表快照名。 */
    @NonNull
    private String accountChangeLabel(@Nullable Long accountId, @Nullable String fallbackName) {
        if (accountId == null || accountId == 0L) {
            return "无";
        }
        String label = labelOf(accountId);
        if (!label.isEmpty()) {
            return label;
        }
        return fallbackName == null || fallbackName.isEmpty() ? "无" : fallbackName;
    }

    /** 业务日期展示为 yyyy-MM-dd，与编辑日志的 dayLogLabel 风格一致。 */
    @NonNull
    private static String dayLogLabel(long dayMillis) {
        return String.format(Locale.US, "%04d-%02d-%02d",
                DateUtil.yearOf(dayMillis), DateUtil.monthOf(dayMillis),
                DateUtil.dayOfMonthOf(dayMillis));
    }

    @NonNull
    private static String noteLabel(@Nullable String note) {
        return note == null || note.isEmpty() ? "（无）" : note;
    }

    /** 0L 与 null 语义等价（未选），统一成 null 便于比较。 */
    @Nullable
    private static Long zeroToNull(@Nullable Long value) {
        return value == null || value == 0L ? null : value;
    }

    @NonNull
    private static String textOf(@Nullable Editable editable) {
        return editable == null ? "" : editable.toString();
    }
}
