package com.skyanchor.bookkeeping.ui.account;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.skyanchor.bookkeeping.R;
import com.skyanchor.bookkeeping.data.entity.AccountEntity;
import com.skyanchor.bookkeeping.data.model.AccountBalance;
import com.skyanchor.bookkeeping.data.model.DeleteAccountResult;
import com.skyanchor.bookkeeping.databinding.ActivityAccountManageBinding;
import com.skyanchor.bookkeeping.databinding.DialogAccountEditBinding;
import com.skyanchor.bookkeeping.ui.adapter.AccountAdapter;
import com.skyanchor.bookkeeping.util.AccountTypes;
import com.skyanchor.bookkeeping.util.AmountUtil;
import com.skyanchor.bookkeeping.util.InsetsUtil;

import java.util.Collections;
import java.util.List;

/**
 * 账户管理（V2 新增，基线第 4 章账户体系）。
 *
 * <p>复用 {@code CategoryManageActivity} 的列表 / 新增 / 编辑 / 删除守卫交互范式：
 * 列表数据源是仓库的联表重算投影，余额随账单变化自动刷新且可正可负；
 * 新增 / 编辑共用一个弹窗，确定按钮手动关闭以便就地把校验错误留在弹窗内；
 * 点击行进入账户流水详情（V2 Phase 9），行内还有上移 / 下移排序与编辑 / 删除动作。
 *
 * <p>删除守卫由仓库层强制执行：已被账单（含转出 / 转入）引用的账户不允许物理删除，
 * 只能改为归档，避免历史资金流水断裂。
 */
public class AccountManageActivity extends AppCompatActivity {

    private ActivityAccountManageBinding binding;
    private AccountViewModel viewModel;
    private AccountAdapter adapter;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityAccountManageBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        InsetsUtil.applySystemBarsPadding(binding.accountRoot);
        InsetsUtil.syncSystemBarAppearance(this);

        viewModel = new ViewModelProvider(this).get(AccountViewModel.class);
        binding.toolbar.setNavigationOnClickListener(v -> finish());

        adapter = new AccountAdapter(new AccountAdapter.Listener() {
            @Override
            public void onOpen(@NonNull AccountBalance account) {
                AccountDetailActivity.start(AccountManageActivity.this, account.id);
            }

            @Override
            public void onEdit(@NonNull AccountBalance account) {
                showEditDialog(account);
            }

            @Override
            public void onDelete(@NonNull AccountBalance account) {
                confirmDelete(account);
            }

            @Override
            public void onMoveUp(@NonNull AccountBalance account) {
                viewModel.move(account.id, -1, null);
            }

            @Override
            public void onMoveDown(@NonNull AccountBalance account) {
                viewModel.move(account.id, 1, null);
            }
        });
        binding.accountList.setLayoutManager(new LinearLayoutManager(this));
        binding.accountList.setAdapter(adapter);

        binding.addAccountButton.setOnClickListener(v -> showEditDialog(null));
        viewModel.getAccounts().observe(this, this::onAccountsChanged);
    }

    private void onAccountsChanged(@Nullable List<AccountBalance> accounts) {
        List<AccountBalance> list = accounts == null ? Collections.emptyList() : accounts;
        adapter.submitList(list);
        binding.accountEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------------
    // 新增 / 编辑
    // ------------------------------------------------------------------

    /**
     * 新增与编辑共用同一个弹窗。
     *
     * <p>确定按钮改为手动关闭：名称为空或初始余额非法时就地显示错误、留在弹窗内，
     * 而不是关掉之后没有任何反馈。归档开关仅在编辑既有账户时出现。
     */
    private void showEditDialog(@Nullable AccountBalance existing) {
        DialogAccountEditBinding dialogBinding =
                DialogAccountEditBinding.inflate(getLayoutInflater());

        // 类型下拉：ALL 与 account_type_labels 同序，按 position 回取类型常量。
        String[] labels = getResources().getStringArray(R.array.account_type_labels);
        int initialType = existing == null ? AccountEntity.TYPE_CASH : existing.type;
        final int[] selectedType = {initialType};
        dialogBinding.typeInput.setText(labels[typeIndex(initialType)], false);
        dialogBinding.typeInput.setOnItemClickListener((parent, view, position, id) ->
                selectedType[0] = AccountTypes.ALL[position]);

        if (existing != null) {
            dialogBinding.nameInput.setText(existing.name);
            // toInputText 会剥离负号，负初始余额（信用卡欠款）需在此手动补回。
            dialogBinding.balanceInput.setText(signedInputText(existing.initialBalance));
            dialogBinding.archiveSwitch.setVisibility(View.VISIBLE);
            dialogBinding.archiveSwitch.setChecked(existing.isArchived);
        }

        dialogBinding.nameInput.addTextChangedListener(new ClearErrorWatcher(dialogBinding.nameLayout));
        dialogBinding.balanceInput.addTextChangedListener(
                new ClearErrorWatcher(dialogBinding.balanceLayout));

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(existing == null ? R.string.account_add : R.string.account_edit_title)
                .setView(dialogBinding.getRoot())
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_save, null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            Editable nameEditable = dialogBinding.nameInput.getText();
            String name = nameEditable == null ? "" : nameEditable.toString().trim();
            if (name.isEmpty()) {
                dialogBinding.nameLayout.setError(getString(R.string.account_name_error));
                return;
            }
            Editable balanceEditable = dialogBinding.balanceInput.getText();
            String balanceText = balanceEditable == null ? "" : balanceEditable.toString().trim();
            long initialBalance = 0L;
            if (!balanceText.isEmpty()) {
                initialBalance = AmountUtil.parseToCentsSigned(balanceText);
                if (initialBalance == AmountUtil.INVALID) {
                    dialogBinding.balanceLayout.setError(
                            getString(R.string.account_initial_balance_error));
                    return;
                }
            }
            submit(existing, name, selectedType[0], initialBalance,
                    dialogBinding.archiveSwitch.isChecked());
            dialog.dismiss();
        });
    }

    private void submit(@Nullable AccountBalance existing, @NonNull String name, int type,
                        long initialBalance, boolean archived) {
        AccountEntity entity = new AccountEntity();
        if (existing != null) {
            // sortOrder 不在编辑范围内，原样带回；createdAt / isArchived 由仓库层按库中值对齐。
            entity.id = existing.id;
            entity.sortOrder = existing.sortOrder;
        }
        entity.name = name;
        entity.type = type;
        entity.isCredit = AccountTypes.isCredit(type);
        entity.initialBalance = initialBalance;

        viewModel.save(entity, id -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            boolean archiveChanged = existing != null && archived != existing.isArchived;
            if (archiveChanged) {
                viewModel.setArchived(existing.id, archived, ok -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    Toast.makeText(this, archived
                                    ? R.string.account_archived_toast
                                    : R.string.account_unarchived_toast,
                            Toast.LENGTH_SHORT).show();
                });
            } else {
                Toast.makeText(this, R.string.account_saved, Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ------------------------------------------------------------------
    // 删除（必须二次确认，且受引用守卫约束）
    // ------------------------------------------------------------------

    private void confirmDelete(@NonNull AccountBalance account) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.account_delete_title, account.name))
                .setMessage(R.string.account_delete_message)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_delete, (dialog, which) ->
                        viewModel.delete(account.id, result -> onDeleteResult(account, result)))
                .show();
    }

    private void onDeleteResult(@NonNull AccountBalance account,
                                @Nullable DeleteAccountResult result) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (result == null) {
            return;
        }
        if (result.success) {
            Toast.makeText(this, R.string.account_deleted, Toast.LENGTH_SHORT).show();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.account_delete_blocked_title)
                .setMessage(getString(R.string.account_delete_blocked_message,
                        account.name, result.usedCount))
                .setPositiveButton(R.string.action_confirm, null)
                .show();
    }

    /** 类型常量在 {@link AccountTypes#ALL} 中的下标，未知类型回落到 0（现金）。 */
    private static int typeIndex(int type) {
        for (int i = 0; i < AccountTypes.ALL.length; i++) {
            if (AccountTypes.ALL[i] == type) {
                return i;
            }
        }
        return 0;
    }

    /** 分转输入框文本，负数补回负号（toInputText 用 abs 会丢符号）。 */
    @NonNull
    private static String signedInputText(long cents) {
        String text = AmountUtil.toInputText(cents);
        return cents < 0L ? "-" + text : text;
    }

    /** 输入变化即清除对应输入框的错误提示，与分类编辑弹窗的手感一致。 */
    private static final class ClearErrorWatcher implements TextWatcher {

        private final com.google.android.material.textfield.TextInputLayout layout;

        ClearErrorWatcher(com.google.android.material.textfield.TextInputLayout layout) {
            this.layout = layout;
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }

        @Override
        public void afterTextChanged(Editable s) {
            layout.setError(null);
        }
    }
}
