package com.skyanchor.bookkeeping.ui.smart;

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
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;
import com.skyanchor.bookkeeping.R;
import com.skyanchor.bookkeeping.ai.DraftValidator;
import com.skyanchor.bookkeeping.ai.TransactionDraft;
import com.skyanchor.bookkeeping.data.entity.AccountEntity;
import com.skyanchor.bookkeeping.data.entity.CategoryEntity;
import com.skyanchor.bookkeeping.data.entity.TransactionEntity;
import com.skyanchor.bookkeeping.databinding.ActivityConfirmBillBinding;
import com.skyanchor.bookkeeping.ui.adapter.CategoryGridAdapter;
import com.skyanchor.bookkeeping.util.AccountTypes;
import com.skyanchor.bookkeeping.util.AmountUtil;
import com.skyanchor.bookkeeping.util.DateLabels;
import com.skyanchor.bookkeeping.util.DateUtil;
import com.skyanchor.bookkeeping.util.InsetsUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 账单确认页（V5）。扫描账单 / AI 智能记账的识别结果在这里逐字段确认、
 * 修改后才能写入正式账单——识别结果不能直接入账（基线第 7、16 章）。
 *
 * <p>页面结构与「记一笔」同构（类型 / 金额 / 分类 / 账户 / 日期 / 时间 / 备注），
 * 但只含支出 / 收入；「重新识别」直接返回来源页，用户可重扫或改描述。
 */
public class ConfirmBillActivity extends AppCompatActivity {

    private static final String TAG_DATE_PICKER = "confirm_date_picker";
    private static final String TAG_TIME_PICKER = "confirm_time_picker";

    private static final String STATE_DATE = "confirm_state_date";
    private static final String STATE_HOUR = "confirm_state_hour";
    private static final String STATE_MINUTE = "confirm_state_minute";
    private static final String STATE_CATEGORY_ID = "confirm_state_category_id";
    private static final String STATE_ACCOUNT_ID = "confirm_state_account_id";

    public static final String EXTRA_SOURCE = "extra_confirm_source";
    public static final String EXTRA_TYPE = "extra_confirm_type";
    public static final String EXTRA_AMOUNT = "extra_confirm_amount";
    public static final String EXTRA_DATE = "extra_confirm_date";
    public static final String EXTRA_TIME = "extra_confirm_time";
    public static final String EXTRA_NOTE = "extra_confirm_note";
    public static final String EXTRA_MERCHANT = "extra_confirm_merchant";
    public static final String EXTRA_CATEGORY = "extra_confirm_category";
    public static final String EXTRA_AMOUNT_ATTENTION = "extra_confirm_amount_attention";
    public static final String EXTRA_CATEGORY_ATTENTION = "extra_confirm_category_attention";
    public static final String EXTRA_OVERALL_LOW = "extra_confirm_overall_low";

    /** 来源页等待确认结果的启动器 key：确认成功后逐级 finish 回记录页。 */
    public static final int REQUEST_CONFIRM = 51;

    private ActivityConfirmBillBinding binding;
    private ConfirmBillViewModel viewModel;
    private CategoryGridAdapter categoryAdapter;

    private String source;
    private long draftCategoryId;
    private long selectedDate;
    private int selectedHour;
    private int selectedMinute;
    private long selectedCategoryId;
    private long selectedAccountId;

    @NonNull
    private List<AccountEntity> accountList = new ArrayList<>();
    private boolean accountsEmpty;
    private boolean categoriesEmpty;

    /** 分类候选是否已按草稿偏好初始化过（只在首次列表到达时应用）。 */
    private boolean categoryApplied;

    /** 程序化切换类型开关时置位，防止监听器把草稿推荐的分类清掉。 */
    private boolean updatingTypeUi;

    /**
     * 构造启动 Intent。来源页（扫描 / AI 记账）必须以 for-result 方式启动本页：
     * 确认落库后本页 setResult(RESULT_OK)，结果逐级回传直至关闭「记一笔」页。
     */
    public static Intent newIntent(@NonNull Context context, @NonNull TransactionDraft draft) {
        Intent intent = new Intent(context, ConfirmBillActivity.class);
        intent.putExtra(EXTRA_SOURCE, draft.source);
        intent.putExtra(EXTRA_TYPE, draft.type);
        intent.putExtra(EXTRA_AMOUNT, draft.amount);
        intent.putExtra(EXTRA_DATE, draft.date);
        intent.putExtra(EXTRA_TIME, draft.time);
        intent.putExtra(EXTRA_NOTE, draft.note);
        intent.putExtra(EXTRA_MERCHANT, draft.merchant);
        intent.putExtra(EXTRA_CATEGORY, draft.categoryId);
        intent.putExtra(EXTRA_AMOUNT_ATTENTION, DraftValidator.needsAmountAttention(draft));
        intent.putExtra(EXTRA_CATEGORY_ATTENTION, DraftValidator.needsCategoryAttention(draft));
        intent.putExtra(EXTRA_OVERALL_LOW, draft.confidence < TransactionDraft.CONFIDENCE_MEDIUM);
        return intent;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityConfirmBillBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        InsetsUtil.applyTopAndHorizontalPadding(binding.confirmRoot);
        InsetsUtil.applyImeBottomPadding(binding.confirmScroll);
        InsetsUtil.syncSystemBarAppearance(this);

        viewModel = new ViewModelProvider(this).get(ConfirmBillViewModel.class);
        source = getIntent().getStringExtra(EXTRA_SOURCE) == null
                ? TransactionDraft.SOURCE_AI : getIntent().getStringExtra(EXTRA_SOURCE);

        categoryAdapter = new CategoryGridAdapter(category -> selectedCategoryId = category.id);
        binding.categoryGrid.setLayoutManager(new GridLayoutManager(this,
                getResources().getInteger(R.integer.category_grid_span)));
        binding.categoryGrid.setAdapter(categoryAdapter);

        binding.toolbar.setNavigationOnClickListener(v -> finish());
        renderBanner();

        restoreState(savedInstanceState);
        renderDate();
        renderTime();

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
            // 换类型后原分类不再合法，清空后由 applyDefaultCategorySelection 落到首项。
            selectedCategoryId = 0L;
            categoryApplied = true;
            categoryAdapter.setSelectedId(0L);
            viewModel.selectType(checkedId == R.id.typeIncome
                    ? CategoryEntity.TYPE_INCOME : CategoryEntity.TYPE_EXPENSE);
        });

        binding.accountInput.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < accountList.size()) {
                selectedAccountId = accountList.get(position).id;
            }
        });

        binding.dateButton.setOnClickListener(v -> showDatePicker());
        binding.timeButton.setOnClickListener(v -> showTimePicker());
        binding.confirmButton.setOnClickListener(v -> save());
        binding.rescanButton.setOnClickListener(v -> finish());

        viewModel.getType().observe(this, type -> renderType(
                type == null ? CategoryEntity.TYPE_EXPENSE : type));
        viewModel.getCategories().observe(this, this::onCategoriesChanged);
        viewModel.getAccounts().observe(this, this::onAccountsChanged);
        reattachPickerListeners();
    }

    private void restoreState(@Nullable Bundle savedInstanceState) {
        int type = getIntent().getIntExtra(EXTRA_TYPE, CategoryEntity.TYPE_EXPENSE);
        long amount = getIntent().getLongExtra(EXTRA_AMOUNT, 0L);
        long date = getIntent().getLongExtra(EXTRA_DATE, 0L);
        String time = getIntent().getStringExtra(EXTRA_TIME);
        String note = getIntent().getStringExtra(EXTRA_NOTE);
        String merchant = getIntent().getStringExtra(EXTRA_MERCHANT);
        draftCategoryId = 0L;
        selectedDate = date > 0L ? DateUtil.startOfDay(date) : DateUtil.today();
        long now = System.currentTimeMillis();
        selectedHour = DateUtil.hourOf(now);
        selectedMinute = DateUtil.minuteOf(now);

        if (amount > 0L) {
            binding.amountInput.setText(AmountUtil.toInputText(amount));
        }
        // 商家折入备注一起确认（开发计划 §3 决策 3）。
        String mergedNote = DraftValidator.mergeMerchantIntoNote(merchant, note);
        if (mergedNote != null) {
            binding.noteInput.setText(mergedNote);
        }
        if (getIntent().getBooleanExtra(EXTRA_AMOUNT_ATTENTION, false)) {
            binding.amountLayout.setHelperText(getString(R.string.confirm_amount_attention));
            binding.amountLayout.setHelperTextEnabled(true);
            binding.amountInput.requestFocus();
        }
        if (getIntent().getBooleanExtra(EXTRA_CATEGORY_ATTENTION, false)) {
            binding.categoryTitle.setText(getString(R.string.edit_category_title)
                    + "（" + getString(R.string.confirm_category_attention) + "）");
        }

        if (savedInstanceState != null) {
            selectedDate = savedInstanceState.getLong(STATE_DATE, selectedDate);
            selectedHour = savedInstanceState.getInt(STATE_HOUR, selectedHour);
            selectedMinute = savedInstanceState.getInt(STATE_MINUTE, selectedMinute);
            selectedCategoryId = savedInstanceState.getLong(STATE_CATEGORY_ID, 0L);
            selectedAccountId = savedInstanceState.getLong(STATE_ACCOUNT_ID, 0L);
            categoryApplied = true;
        } else {
            draftCategoryId = getIntent().getLongExtra(EXTRA_CATEGORY, 0L);
        }
        viewModel.selectType(type);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putLong(STATE_DATE, selectedDate);
        outState.putInt(STATE_HOUR, selectedHour);
        outState.putInt(STATE_MINUTE, selectedMinute);
        outState.putLong(STATE_CATEGORY_ID, selectedCategoryId);
        outState.putLong(STATE_ACCOUNT_ID, selectedAccountId);
    }

    // ------------------------------------------------------------------
    // 渲染
    // ------------------------------------------------------------------

    private void renderBanner() {
        binding.confirmBannerText.setText(
                TransactionDraft.SOURCE_OCR.equals(source)
                        ? R.string.confirm_banner_ocr : R.string.confirm_banner_ai);
        if (getIntent().getBooleanExtra(EXTRA_OVERALL_LOW, false)) {
            binding.confirmBannerText.append("\n");
            binding.confirmBannerText.append(getString(R.string.confirm_banner_low));
        }
    }

    private void renderType(int type) {
        updatingTypeUi = true;
        binding.typeGroup.check(type == CategoryEntity.TYPE_INCOME
                ? R.id.typeIncome : R.id.typeExpense);
        updatingTypeUi = false;
    }

    private void renderDate() {
        binding.dateButton.setText(DateLabels.businessDateLabel(this, selectedDate));
    }

    private void renderTime() {
        binding.timeButton.setText(DateUtil.formatHourMinute(selectedHour, selectedMinute));
    }

    private void updateAmountPreview() {
        long cents = AmountUtil.parseToCents(textOf(binding.amountInput.getText()));
        binding.amountPreview.setText(AmountUtil.format(cents == AmountUtil.INVALID ? 0L : cents));
    }

    // ------------------------------------------------------------------
    // 分类与账户
    // ------------------------------------------------------------------

    private void onCategoriesChanged(@Nullable List<CategoryEntity> categories) {
        categoryAdapter.submitList(categories);
        categoriesEmpty = categories == null || categories.isEmpty();
        applyDefaultCategorySelection(categories);
        binding.categoryEmpty.setVisibility(categoriesEmpty ? View.VISIBLE : View.GONE);
        binding.categoryGrid.setVisibility(categoriesEmpty ? View.GONE : View.VISIBLE);
    }

    /** 首次到达时优先恢复草稿推荐分类，其后当前选中失效则落到首项。 */
    private void applyDefaultCategorySelection(@Nullable List<CategoryEntity> categories) {
        if (categories == null || categories.isEmpty()) {
            selectedCategoryId = 0L;
            return;
        }
        if (!categoryApplied && draftCategoryId > 0L) {
            for (CategoryEntity category : categories) {
                if (category.id == draftCategoryId) {
                    selectedCategoryId = draftCategoryId;
                    break;
                }
            }
            categoryApplied = true;
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

    private void onAccountsChanged(@Nullable List<AccountEntity> accounts) {
        accountList = accounts == null ? new ArrayList<>() : accounts;
        accountsEmpty = accountList.isEmpty();
        if (accountsEmpty) {
            binding.accountEmpty.setVisibility(View.VISIBLE);
            binding.accountLayout.setVisibility(View.GONE);
            return;
        }
        binding.accountEmpty.setVisibility(View.GONE);
        binding.accountLayout.setVisibility(View.VISIBLE);
        String[] labels = new String[accountList.size()];
        for (int i = 0; i < accountList.size(); i++) {
            AccountEntity account = accountList.get(i);
            labels[i] = AccountTypes.emoji(account.type) + " " + account.name;
        }
        binding.accountInput.setSimpleItems(labels);
        boolean stillValid = false;
        for (AccountEntity account : accountList) {
            if (account.id == selectedAccountId) {
                stillValid = true;
                break;
            }
        }
        if (!stillValid) {
            selectedAccountId = accountList.get(0).id;
        }
        binding.accountInput.setText(accountLabel(selectedAccountId), false);
    }

    @NonNull
    private String accountLabel(long accountId) {
        for (AccountEntity account : accountList) {
            if (account.id == accountId) {
                return AccountTypes.emoji(account.type) + " " + account.name;
            }
        }
        return "";
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

    /** 选择器是 DialogFragment，旋转后会丢监听器，与「记一笔」同款重挂逻辑。 */
    @SuppressWarnings("unchecked")
    private void reattachPickerListeners() {
        Fragment datePicker = getSupportFragmentManager().findFragmentByTag(TAG_DATE_PICKER);
        if (datePicker instanceof MaterialDatePicker) {
            ((MaterialDatePicker<Long>) datePicker).addOnPositiveButtonClickListener(
                    this::onDatePicked);
        }
        Fragment timePicker = getSupportFragmentManager().findFragmentByTag(TAG_TIME_PICKER);
        if (timePicker instanceof MaterialTimePicker) {
            MaterialTimePicker picker = (MaterialTimePicker) timePicker;
            picker.addOnPositiveButtonClickListener(v -> onTimePicked(picker));
        }
    }

    // ------------------------------------------------------------------
    // 保存
    // ------------------------------------------------------------------

    private void save() {
        long cents = AmountUtil.parseToCents(textOf(binding.amountInput.getText()));
        if (cents <= 0L) {
            binding.amountLayout.setError(getString(R.string.edit_error_amount));
            binding.amountInput.requestFocus();
            return;
        }
        if (categoriesEmpty) {
            Snackbar.make(binding.confirmRoot, R.string.edit_error_category,
                    Snackbar.LENGTH_SHORT).show();
            return;
        }
        if (selectedCategoryId == 0L) {
            Snackbar.make(binding.confirmRoot, R.string.edit_error_category,
                    Snackbar.LENGTH_SHORT).show();
            return;
        }
        if (accountsEmpty || selectedAccountId == 0L) {
            Snackbar.make(binding.confirmRoot, R.string.edit_error_account,
                    Snackbar.LENGTH_SHORT).show();
            return;
        }

        TransactionEntity entity = new TransactionEntity();
        entity.type = viewModel.getType().getValue() == null
                ? CategoryEntity.TYPE_EXPENSE : viewModel.getType().getValue();
        entity.amount = cents;
        entity.categoryId = selectedCategoryId;
        entity.accountId = selectedAccountId;
        entity.transferAccountId = null;
        entity.date = selectedDate;
        entity.time = DateUtil.formatHourMinute(selectedHour, selectedMinute);
        String note = textOf(binding.noteInput.getText()).trim();
        entity.note = note.isEmpty() ? null : note;

        binding.confirmButton.setEnabled(false);
        viewModel.save(entity, id -> {
            if (id == null) {
                binding.confirmButton.setEnabled(true);
                Toast.makeText(this, R.string.edit_save_failed, Toast.LENGTH_SHORT).show();
                return;
            }
            // 已确认落库：结果逐级带回（Scan/AI 页与记一笔页据此关闭）。
            Toast.makeText(this, R.string.confirm_saved, Toast.LENGTH_SHORT).show();
            setResult(RESULT_OK);
            finish();
        });
    }

    @NonNull
    private static String textOf(@Nullable Editable editable) {
        return editable == null ? "" : editable.toString();
    }
}
