package com.skyanchor.bookkeeping.ui.ledger;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.textfield.TextInputEditText;
import com.skyanchor.bookkeeping.BookkeepingApp;
import com.skyanchor.bookkeeping.R;
import com.skyanchor.bookkeeping.data.entity.LedgerEntity;
import com.skyanchor.bookkeeping.data.remote.ApiException;
import com.skyanchor.bookkeeping.data.repository.LedgerRepository;
import com.skyanchor.bookkeeping.databinding.ActivityLedgerManageBinding;
import com.skyanchor.bookkeeping.sync.SyncCoordinator;
import com.skyanchor.bookkeeping.util.InsetsUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 账本管理（V3.2 基线第 6 章）：账本列表、新建、切换、重命名、删除与账本回收站；
 * 成员管理入口在本页按角色展示。所有破坏性操作（删除）二次确认。
 */
public class LedgerManageActivity extends AppCompatActivity {

    private ActivityLedgerManageBinding binding;
    private LedgerAdapter adapter;
    private LedgerRepository ledgerRepository;
    private SyncCoordinator syncCoordinator;
    private com.skyanchor.bookkeeping.data.repository.BookkeepingRepository repository;
    private final List<LedgerEntity> ledgers = new ArrayList<>();
    private final List<LedgerEntity> deletedLedgers = new ArrayList<>();

    public static void start(@NonNull android.content.Context context) {
        context.startActivity(new android.content.Intent(context, LedgerManageActivity.class));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityLedgerManageBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        InsetsUtil.applySystemBarsPadding(binding.getRoot());
        InsetsUtil.syncSystemBarAppearance(this);

        binding.toolbar.setNavigationOnClickListener(v -> finish());

        BookkeepingApp app = BookkeepingApp.get(this);
        repository = app.getRepository();
        ledgerRepository = new LedgerRepository(app.getApiClient());
        syncCoordinator = app.getSyncCoordinator();

        adapter = new LedgerAdapter();
        binding.recycler.setLayoutManager(new LinearLayoutManager(this));
        binding.recycler.setAdapter(adapter);

        binding.fabAdd.setOnClickListener(v -> showCreateDialog());
        binding.btnRecycleBin.setOnClickListener(v -> showRecycleBinDialog());

        repository.observeLedgers().observe(this, list -> {
            ledgers.clear();
            if (list != null) {
                ledgers.addAll(list);
            }
            adapter.notifyDataSetChanged();
        });
        repository.observeLedgerRecycleBin().observe(this, list -> {
            deletedLedgers.clear();
            if (list != null) {
                deletedLedgers.addAll(list);
            }
        });
    }

    // ===== 新建 / 重命名 =====

    private void showCreateDialog() {
        View view = getLayoutInflater().inflate(R.layout.dialog_ledger_edit, null, false);
        TextInputEditText nameInput = view.findViewById(R.id.inputName);
        TextInputEditText descInput = view.findViewById(R.id.inputDescription);
        new AlertDialog.Builder(this)
                .setTitle(R.string.ledger_create_title)
                .setView(view)
                .setPositiveButton(R.string.common_ok, (dialog, which) -> {
                    String name = nameInput.getText() == null ? "" : nameInput.getText().toString();
                    String desc = descInput.getText() == null ? "" : descInput.getText().toString();
                    if (name.trim().isEmpty()) {
                        Toast.makeText(this, R.string.ledger_name_required, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    // V3.2 冻结决策 1：单账本单主币种，默认 CNY，暂不开放选择
                    repository.createLedger(name, desc, "CNY", result ->
                            Toast.makeText(this, R.string.ledger_created, Toast.LENGTH_SHORT).show());
                })
                .setNegativeButton(R.string.common_cancel, null)
                .show();
    }

    private void showRenameDialog(@NonNull LedgerEntity ledger) {
        View view = getLayoutInflater().inflate(R.layout.dialog_ledger_edit, null, false);
        TextInputEditText nameInput = view.findViewById(R.id.inputName);
        nameInput.setText(ledger.name);
        view.findViewById(R.id.descriptionContainer).setVisibility(View.GONE);
        new AlertDialog.Builder(this)
                .setTitle(R.string.ledger_rename_title)
                .setView(view)
                .setPositiveButton(R.string.common_ok, (dialog, which) -> {
                    String name = nameInput.getText() == null ? "" : nameInput.getText().toString();
                    if (name.trim().isEmpty()) {
                        return;
                    }
                    repository.renameLedger(ledger.id, name, result -> { });
                })
                .setNegativeButton(R.string.common_cancel, null)
                .show();
    }

    // ===== 行操作 =====

    private void showRowOptions(@NonNull LedgerEntity ledger) {
        boolean removed = LedgerEntity.ROLE_REMOVED.equals(ledger.role);
        boolean canManage = LedgerEntity.ROLE_OWNER.equals(ledger.role)
                || LedgerEntity.ROLE_ADMIN.equals(ledger.role);
        List<String> options = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (!ledger.isCurrent && !removed && !ledger.isDeleted) {
            options.add(getString(R.string.ledger_action_switch));
            actions.add(() -> repository.switchLedger(ledger.id, r -> { }));
        }
        if (!removed && !ledger.isDeleted) {
            options.add(getString(R.string.ledger_action_members));
            actions.add(() -> MemberManageActivity.start(this, ledger.syncId, ledger.name,
                    ledger.role));
        }
        if (canManage && !ledger.isDeleted) {
            options.add(getString(R.string.ledger_action_rename));
            actions.add(() -> showRenameDialog(ledger));
        }
        if (LedgerEntity.ROLE_OWNER.equals(ledger.role) && !ledger.isDefault && !ledger.isDeleted) {
            options.add(getString(R.string.ledger_action_delete));
            actions.add(() -> confirmDeleteLedger(ledger));
        }
        if (options.isEmpty()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(ledger.name)
                .setItems(options.toArray(new String[0]), (dialog, which) ->
                        actions.get(which).run())
                .show();
    }

    private void confirmDeleteLedger(@NonNull LedgerEntity ledger) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.ledger_delete_title)
                .setMessage(getString(R.string.ledger_delete_message, ledger.name))
                .setPositiveButton(R.string.ledger_delete_confirm, (dialog, which) ->
                        repository.deleteLedger(ledger.id, r -> { }))
                .setNegativeButton(R.string.common_cancel, null)
                .show();
    }

    // ===== 账本回收站（恢复走 REST，仅服务端 OWNER） =====

    private void showRecycleBinDialog() {
        if (deletedLedgers.isEmpty()) {
            Toast.makeText(this, R.string.ledger_recycle_bin_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = new String[deletedLedgers.size()];
        for (int i = 0; i < deletedLedgers.size(); i++) {
            names[i] = deletedLedgers.get(i).name;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.ledger_recycle_bin_title)
                .setItems(names, (dialog, which) -> ledgerRepository.restoreLedger(
                        deletedLedgers.get(which).syncId,
                        new CallbackAdapter<>(ok -> {
                            Toast.makeText(this, ok
                                            ? R.string.ledger_restored
                                            : R.string.ledger_restore_failed,
                                    Toast.LENGTH_SHORT).show();
                            syncCoordinator.requestSync(true);
                        })))
                .setNegativeButton(R.string.common_cancel, null)
                .show();
    }

    /** REST 回调到布尔结果的简单适配（成功 = 非 null）。 */
    private static final class CallbackAdapter<T> implements com.skyanchor.bookkeeping.util.Callback<T> {
        interface Bool { void call(boolean ok); }

        private final Bool target;

        CallbackAdapter(Bool target) {
            this.target = target;
        }

        @Override
        public void onResult(T result) {
            target.call(result != null);
        }

        @Override
        public void onError(@NonNull Exception e) {
            target.call(false);
        }
    }

    // ===== 列表 =====

    private final class LedgerAdapter extends RecyclerView.Adapter<LedgerViewHolder> {

        @NonNull
        @Override
        public LedgerViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_ledger_row, parent, false);
            return new LedgerViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull LedgerViewHolder holder, int position) {
            holder.bind(ledgers.get(position));
        }

        @Override
        public int getItemCount() {
            return ledgers.size();
        }
    }

    private final class LedgerViewHolder extends RecyclerView.ViewHolder {

        private final TextView name;
        private final TextView subtitle;

        LedgerViewHolder(@NonNull View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.ledgerName);
            subtitle = itemView.findViewById(R.id.ledgerSubtitle);
        }

        void bind(@NonNull LedgerEntity ledger) {
            name.setText(ledger.name);
            StringBuilder builder = new StringBuilder(roleLabel(ledger.role));
            if (ledger.isCurrent) {
                builder.append(" · ").append(getString(R.string.ledger_current));
            }
            if (LedgerEntity.ROLE_REMOVED.equals(ledger.role)) {
                builder.append(" · ").append(getString(R.string.ledger_removed_hint));
            }
            subtitle.setText(builder);
            itemView.setOnClickListener(v -> showRowOptions(ledger));
        }
    }

    @NonNull
    private static String roleLabel(@NonNull String role) {
        switch (role) {
            case LedgerEntity.ROLE_OWNER: return "所有者";
            case LedgerEntity.ROLE_ADMIN: return "管理员";
            case LedgerEntity.ROLE_MEMBER: return "成员";
            case LedgerEntity.ROLE_VIEWER: return "观察者";
            default: return "已被移出";
        }
    }
}
