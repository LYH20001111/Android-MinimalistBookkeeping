package com.skyanchor.bookkeeping.ui.search;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.skyanchor.bookkeeping.R;
import com.skyanchor.bookkeeping.data.entity.AccountEntity;
import com.skyanchor.bookkeeping.data.model.SearchFilter;
import com.skyanchor.bookkeeping.databinding.DialogAccountPickerBinding;
import com.skyanchor.bookkeeping.ui.adapter.PickerAccountAdapter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 搜索账户选择器弹窗（V2.1 Phase 1，基线 5.4）。
 *
 * <p>「图标 + 账户名 + 账户类型」列表，不展示余额；含已归档账户（历史账单可能落在
 * 归档账户上，仍需可筛）。首项「全部账户」伪项（id={@link SearchFilter#NO_ACCOUNT}），
 * 可选「最近使用」快捷区。选中后回调一次并关闭。
 */
public final class AccountPickerDialog {

    private AccountPickerDialog() {
    }

    public interface Listener {
        /** @param account 选中账户；「全部账户」时为 id=0 的伪账户实体。 */
        void onAccountPicked(@NonNull AccountEntity account);
    }

    /**
     * @param accounts   全量候选（含已归档）
     * @param selectedId 当前选中账户 id（0 = 未选具体账户）
     * @param recentIds  最近使用 id（最新在前；允许含已不存在的 id，会被过滤）
     */
    public static void show(@NonNull Context context, @NonNull List<AccountEntity> accounts,
                            long selectedId, @NonNull long[] recentIds,
                            @NonNull Listener listener) {
        DialogAccountPickerBinding binding = DialogAccountPickerBinding.inflate(
                LayoutInflater.from(context));

        if (accounts.isEmpty()) {
            binding.pickerList.setVisibility(View.GONE);
            binding.pickerEmpty.setVisibility(View.VISIBLE);
            new MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.picker_account_title)
                    .setView(binding.getRoot())
                    .setNegativeButton(R.string.action_cancel, null)
                    .show();
            return;
        }

        // 先 create() 拿到 dialog，再把它注入适配器的点击回调：选中 → 回调 + 关闭
        AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.picker_account_title)
                .setView(binding.getRoot())
                .setNegativeButton(R.string.action_cancel, null)
                .create();
        PickerAccountAdapter adapter = new PickerAccountAdapter(selectedId, account -> {
            listener.onAccountPicked(account);
            dialog.dismiss();
        });
        binding.pickerList.setLayoutManager(new LinearLayoutManager(context));
        binding.pickerList.setAdapter(adapter);
        adapter.submitList(buildRows(context, accounts, recentIds));
        dialog.show();
    }

    /** 组装行列表：可选「最近使用」区 + 「全部账户」伪项 + 全量账户。 */
    @NonNull
    static List<PickerAccountAdapter.Row> buildRows(@NonNull Context context,
                                                    @NonNull List<AccountEntity> accounts,
                                                    @NonNull long[] recentIds) {
        List<PickerAccountAdapter.Row> rows = new ArrayList<>();
        List<AccountEntity> recent = resolveRecent(accounts, recentIds);
        if (!recent.isEmpty()) {
            rows.add(PickerAccountAdapter.Row.header(
                    context.getString(R.string.picker_recent_section)));
            for (AccountEntity account : recent) {
                rows.add(PickerAccountAdapter.Row.of(account));
            }
        }
        AccountEntity all = new AccountEntity();
        all.id = SearchFilter.NO_ACCOUNT;
        all.name = context.getString(R.string.search_all_accounts);
        rows.add(PickerAccountAdapter.Row.of(all));
        for (AccountEntity account : accounts) {
            rows.add(PickerAccountAdapter.Row.of(account));
        }
        return rows;
    }

    /** 最近使用 id 映射回现存的账户实体，保持 recency 顺序、去重、过滤失效 id。 */
    @NonNull
    static List<AccountEntity> resolveRecent(@NonNull List<AccountEntity> accounts,
                                             @NonNull long[] recentIds) {
        if (recentIds.length == 0) {
            return new ArrayList<>();
        }
        Map<Long, AccountEntity> byId = new LinkedHashMap<>();
        for (AccountEntity account : accounts) {
            byId.put(account.id, account);
        }
        List<AccountEntity> recent = new ArrayList<>();
        for (long id : recentIds) {
            AccountEntity account = byId.get(id);
            if (account != null && !recent.contains(account)) {
                recent.add(account);
            }
        }
        return recent;
    }
}
