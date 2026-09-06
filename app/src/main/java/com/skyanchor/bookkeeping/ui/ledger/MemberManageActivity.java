package com.skyanchor.bookkeeping.ui.ledger;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.textfield.TextInputEditText;
import com.skyanchor.bookkeeping.BookkeepingApp;
import com.skyanchor.bookkeeping.R;
import com.skyanchor.bookkeeping.data.remote.ApiDtos;
import com.skyanchor.bookkeeping.data.repository.LedgerRepository;
import com.skyanchor.bookkeeping.databinding.ActivityMemberManageBinding;
import com.skyanchor.bookkeeping.sync.SyncCoordinator;

import java.util.ArrayList;
import java.util.List;

/**
 * 成员管理（V3.2 基线第 8、9 章）：成员列表、邀请、移除与角色调整。
 * 页面仅做入口与角色门控（UI 隐藏），服务端对每个操作做最终裁决（原则 2）。
 */
public class MemberManageActivity extends AppCompatActivity {

    private static final String EXTRA_LEDGER_SYNC_ID = "ledgerSyncId";
    private static final String EXTRA_LEDGER_NAME = "ledgerName";
    private static final String EXTRA_MY_ROLE = "myRole";

    private ActivityMemberManageBinding binding;
    private LedgerRepository ledgerRepository;
    private SyncCoordinator syncCoordinator;
    private MemberAdapter adapter;
    private final List<ApiDtos.MemberItem> members = new ArrayList<>();

    private String ledgerSyncId;
    private String ledgerName;
    private String myRole;

    public static void start(@NonNull Context context, @NonNull String ledgerSyncId,
                             @NonNull String ledgerName, @NonNull String myRole) {
        Intent intent = new Intent(context, MemberManageActivity.class);
        intent.putExtra(EXTRA_LEDGER_SYNC_ID, ledgerSyncId);
        intent.putExtra(EXTRA_LEDGER_NAME, ledgerName);
        intent.putExtra(EXTRA_MY_ROLE, myRole);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMemberManageBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setSupportActionBar(binding.toolbar);
        binding.toolbar.setNavigationOnClickListener(v -> finish());

        ledgerSyncId = getIntent().getStringExtra(EXTRA_LEDGER_SYNC_ID);
        ledgerName = getIntent().getStringExtra(EXTRA_LEDGER_NAME);
        myRole = getIntent().getStringExtra(EXTRA_MY_ROLE);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            setTitle(getString(R.string.member_manage_title, ledgerName));
        }

        ledgerRepository = new LedgerRepository(BookkeepingApp.get(this).getApiClient());
        syncCoordinator = BookkeepingApp.get(this).getSyncCoordinator();

        boolean canManage = roleRank(myRole) >= roleRank(com.skyanchor.bookkeeping.data.entity.LedgerEntity.ROLE_ADMIN);
        binding.fabInvite.setVisibility(canManage ? View.VISIBLE : View.GONE);
        binding.fabInvite.setOnClickListener(v -> showInviteDialog());

        adapter = new MemberAdapter();
        binding.recycler.setLayoutManager(new LinearLayoutManager(this));
        binding.recycler.setAdapter(adapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        ledgerRepository.listMembers(ledgerSyncId, new com.skyanchor.bookkeeping.util.Callback<>() {
            @Override
            public void onResult(ApiDtos.MembersResponse response) {
                members.clear();
                if (response != null && response.members != null) {
                    members.addAll(response.members);
                }
                adapter.notifyDataSetChanged();
            }

            @Override
            public void onError(@NonNull Exception e) {
                Toast.makeText(MemberManageActivity.this,
                        getString(R.string.member_load_failed, e.getMessage()),
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    private void showInviteDialog() {
        View view = getLayoutInflater().inflate(R.layout.dialog_member_invite, null, false);
        TextInputEditText emailInput = view.findViewById(R.id.inputEmail);
        new AlertDialog.Builder(this)
                .setTitle(R.string.member_invite_title)
                .setView(view)
                .setPositiveButton(R.string.member_invite_send, (dialog, which) -> {
                    String email = emailInput.getText() == null
                            ? "" : emailInput.getText().toString().trim();
                    if (email.isEmpty()) {
                        return;
                    }
                    // 默认授予 MEMBER（基线第 9 章默认角色）；细粒度角色调整在成员行操作里
                    ledgerRepository.invite(ledgerSyncId, email, "MEMBER",
                            new com.skyanchor.bookkeeping.util.Callback<>() {
                                @Override
                                public void onResult(ApiDtos.InvitationItem item) {
                                    Toast.makeText(MemberManageActivity.this,
                                            getString(R.string.member_invite_sent, email),
                                            Toast.LENGTH_SHORT).show();
                                }

                                @Override
                                public void onError(@NonNull Exception e) {
                                    Toast.makeText(MemberManageActivity.this,
                                            getString(R.string.member_op_failed,
                                                    e.getMessage()),
                                            Toast.LENGTH_LONG).show();
                                }
                            });
                })
                .setNegativeButton(R.string.common_cancel, null)
                .show();
    }

    private void showMemberOptions(@NonNull ApiDtos.MemberItem member) {
        boolean isSelf = false;
        boolean canManage = roleRank(myRole) >= roleRank(
                com.skyanchor.bookkeeping.data.entity.LedgerEntity.ROLE_ADMIN);
        if (!canManage || "OWNER".equals(member.role)) {
            return;
        }
        List<String> options = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        options.add(getString(R.string.member_action_change_role));
        actions.add(() -> showRoleDialog(member));
        options.add(getString(R.string.member_action_remove));
        actions.add(() -> confirmRemove(member));
        new AlertDialog.Builder(this)
                .setTitle(member.email)
                .setItems(options.toArray(new String[0]), (dialog, which) ->
                        actions.get(which).run())
                .show();
    }

    private void showRoleDialog(@NonNull ApiDtos.MemberItem member) {
        // ADMIN 只能授予 MEMBER/VIEWER；授予 ADMIN 属于 OWNER（服务端最终裁决）
        String[] roles = myRole != null && "OWNER".equals(myRole)
                ? new String[]{"ADMIN", "MEMBER", "VIEWER"}
                : new String[]{"MEMBER", "VIEWER"};
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.member_change_role_title, member.email))
                .setItems(roles, (dialog, which) -> ledgerRepository.updateMemberRole(
                        ledgerSyncId, member.userId, roles[which],
                        new com.skyanchor.bookkeeping.util.Callback<>() {
                            @Override
                            public void onResult(ApiDtos.SimpleResponse response) {
                                reload();
                            }

                            @Override
                            public void onError(@NonNull Exception e) {
                                Toast.makeText(MemberManageActivity.this,
                                        getString(R.string.member_op_failed, e.getMessage()),
                                        Toast.LENGTH_LONG).show();
                            }
                        }))
                .setNegativeButton(R.string.common_cancel, null)
                .show();
    }

    private void confirmRemove(@NonNull ApiDtos.MemberItem member) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.member_remove_title)
                .setMessage(getString(R.string.member_remove_message, member.email))
                .setPositiveButton(R.string.member_remove_confirm, (dialog, which) ->
                        ledgerRepository.removeMember(ledgerSyncId, member.userId,
                                new com.skyanchor.bookkeeping.util.Callback<>() {
                                    @Override
                                    public void onResult(ApiDtos.SimpleResponse response) {
                                        reload();
                                    }

                                    @Override
                                    public void onError(@NonNull Exception e) {
                                        Toast.makeText(MemberManageActivity.this,
                                                getString(R.string.member_op_failed,
                                                        e.getMessage()),
                                                Toast.LENGTH_LONG).show();
                                    }
                                }))
                .setNegativeButton(R.string.common_cancel, null)
                .show();
    }

    private static int roleRank(String role) {
        if (role == null) {
            return -1;
        }
        switch (role) {
            case "OWNER": return 3;
            case "ADMIN": return 2;
            case "MEMBER": return 1;
            case "VIEWER": return 0;
            default: return -1;
        }
    }

    private final class MemberAdapter extends RecyclerView.Adapter<MemberViewHolder> {

        @NonNull
        @Override
        public MemberViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_member_row, parent, false);
            return new MemberViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull MemberViewHolder holder, int position) {
            holder.bind(members.get(position));
        }

        @Override
        public int getItemCount() {
            return members.size();
        }
    }

    private final class MemberViewHolder extends RecyclerView.ViewHolder {

        private final TextView email;
        private final TextView role;

        MemberViewHolder(@NonNull View itemView) {
            super(itemView);
            email = itemView.findViewById(R.id.memberEmail);
            role = itemView.findViewById(R.id.memberRole);
        }

        void bind(@NonNull ApiDtos.MemberItem member) {
            email.setText(member.email);
            String label;
            switch (member.role) {
                case "OWNER": label = getString(R.string.role_owner); break;
                case "ADMIN": label = getString(R.string.role_admin); break;
                case "MEMBER": label = getString(R.string.role_member); break;
                case "VIEWER": label = getString(R.string.role_viewer); break;
                default: label = member.role;
            }
            role.setText(label);
            itemView.setOnClickListener(v -> showMemberOptions(member));
        }
    }
}
