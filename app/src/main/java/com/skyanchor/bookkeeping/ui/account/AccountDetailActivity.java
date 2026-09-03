package com.skyanchor.bookkeeping.ui.account;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.skyanchor.bookkeeping.R;
import com.skyanchor.bookkeeping.data.entity.AccountEntity;
import com.skyanchor.bookkeeping.data.entity.TransactionItem;
import com.skyanchor.bookkeeping.data.model.RecordListItem;
import com.skyanchor.bookkeeping.databinding.ActivityAccountDetailBinding;
import com.skyanchor.bookkeeping.ui.adapter.TransactionListAdapter;
import com.skyanchor.bookkeeping.ui.record.TransactionEditActivity;
import com.skyanchor.bookkeeping.util.AmountUtil;
import com.skyanchor.bookkeeping.util.InsetsUtil;

import java.util.Collections;
import java.util.List;

/**
 * 账户流水详情（V2 新增，开发计划 Phase 9）。
 *
 * <p>顶部展示账户当前余额（联表重算投影，随账单变化自动刷新），下方是该账户的
 * 全部流水（含转出 / 转入），行布局与记录页共用 {@link TransactionListAdapter}，
 * 转账行自然显示「转出账户 → 转入账户」与中性色。点击流水行进入编辑，编辑后
 * 余额与列表同源刷新。账户由账户管理页点击进入。
 */
public class AccountDetailActivity extends AppCompatActivity {

    /** 目标账户 id（必填 extra）。 */
    public static final String EXTRA_ACCOUNT_ID = "extra_account_id";

    private ActivityAccountDetailBinding binding;
    private AccountDetailViewModel viewModel;
    private TransactionListAdapter adapter;

    @NonNull
    public static Intent newIntent(@NonNull Context context, long accountId) {
        Intent intent = new Intent(context, AccountDetailActivity.class);
        intent.putExtra(EXTRA_ACCOUNT_ID, accountId);
        return intent;
    }

    public static void start(@NonNull Context context, long accountId) {
        context.startActivity(newIntent(context, accountId));
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityAccountDetailBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        InsetsUtil.applySystemBarsPadding(binding.accountDetailRoot);
        InsetsUtil.syncSystemBarAppearance(this);

        binding.toolbar.setNavigationOnClickListener(v -> finish());

        adapter = new TransactionListAdapter(new TransactionListAdapter.Listener() {
            @Override
            public void onTransactionClick(@NonNull TransactionItem item) {
                TransactionEditActivity.startEdit(AccountDetailActivity.this, item.id);
            }

            @Override
            public void onTransactionLongClick(@NonNull TransactionItem item) {
                // 详情页不做删除动作，保持只读浏览语义；编辑页内可完成删除。
            }
        });
        binding.transactionList.setLayoutManager(new LinearLayoutManager(this));
        binding.transactionList.setAdapter(adapter);

        viewModel = new ViewModelProvider(this).get(AccountDetailViewModel.class);
        viewModel.getAccount().observe(this, this::renderAccount);
        viewModel.getRows().observe(this, this::renderRows);

        viewModel.load(resolveAccountId());
    }

    private long resolveAccountId() {
        return getIntent().getLongExtra(EXTRA_ACCOUNT_ID, 0L);
    }

    private void renderAccount(@Nullable AccountEntity account) {
        if (account == null) {
            return;
        }
        binding.accountNameValue.setText(account.name);
        binding.accountArchivedBadge.setVisibility(
                account.isArchived ? View.VISIBLE : View.GONE);
        binding.accountBalanceValue.setText(AmountUtil.format(account.balance));
        // 余额可正可负：负数（信用卡欠款等）用 danger 语义色，与账户管理页同规则。
        binding.accountBalanceValue.setTextColor(getColor(
                account.balance < 0L ? R.color.danger : R.color.text_primary));
    }

    private void renderRows(@Nullable List<RecordListItem> rows) {
        List<RecordListItem> list = rows == null ? Collections.emptyList() : rows;
        adapter.submitList(list);
        binding.transactionEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
    }
}
