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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.skyanchor.bookkeeping.BookkeepingApp;
import com.skyanchor.bookkeeping.R;
import com.skyanchor.bookkeeping.data.remote.ApiDtos;
import com.skyanchor.bookkeeping.data.repository.LedgerRepository;
import com.skyanchor.bookkeeping.databinding.ActivityInvitationBinding;
import com.skyanchor.bookkeeping.sync.SyncCoordinator;
import com.skyanchor.bookkeeping.util.InsetsUtil;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 我的邀请（V3.2 基线第 8.1 章）：受邀用户在这里查看并接受 / 拒绝共享账本邀请。
 * 接受成功后立即触发一次同步，把共享账本与既有数据拉到本地。
 */
public class InvitationActivity extends AppCompatActivity {

    private ActivityInvitationBinding binding;
    private LedgerRepository ledgerRepository;
    private SyncCoordinator syncCoordinator;
    private InvitationAdapter adapter;
    private final List<ApiDtos.InvitationItem> invitations = new ArrayList<>();
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityInvitationBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        InsetsUtil.applySystemBarsPadding(binding.getRoot());
        InsetsUtil.syncSystemBarAppearance(this);

        binding.toolbar.setNavigationOnClickListener(v -> finish());

        ledgerRepository = new LedgerRepository(BookkeepingApp.get(this).getApiClient());
        syncCoordinator = BookkeepingApp.get(this).getSyncCoordinator();

        adapter = new InvitationAdapter();
        binding.recycler.setLayoutManager(new LinearLayoutManager(this));
        binding.recycler.setAdapter(adapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        ledgerRepository.myInvitations(new com.skyanchor.bookkeeping.util.Callback<>() {
            @Override
            public void onResult(ApiDtos.InvitationsResponse response) {
                invitations.clear();
                if (response != null && response.invitations != null) {
                    invitations.addAll(response.invitations);
                }
                binding.emptyView.setVisibility(invitations.isEmpty()
                        ? View.VISIBLE : View.GONE);
                adapter.notifyDataSetChanged();
            }

            @Override
            public void onError(@NonNull Exception e) {
                binding.emptyView.setVisibility(View.VISIBLE);
                Toast.makeText(InvitationActivity.this,
                        getString(R.string.member_load_failed, e.getMessage()),
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    private void accept(@NonNull ApiDtos.InvitationItem item) {
        ledgerRepository.acceptInvitation(item.invitationId,
                new com.skyanchor.bookkeeping.util.Callback<>() {
                    @Override
                    public void onResult(ApiDtos.AcceptInvitationResponse response) {
                        Toast.makeText(InvitationActivity.this,
                                getString(R.string.invitation_accepted, response.ledgerName),
                                Toast.LENGTH_SHORT).show();
                        // 接受后立即同步：把共享账本与存量数据拉到本地
                        syncCoordinator.requestSync(true);
                        reload();
                    }

                    @Override
                    public void onError(@NonNull Exception e) {
                        Toast.makeText(InvitationActivity.this,
                                getString(R.string.member_op_failed, e.getMessage()),
                                Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void decline(@NonNull ApiDtos.InvitationItem item) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.invitation_decline_title)
                .setMessage(getString(R.string.invitation_decline_message, item.ledgerName))
                .setPositiveButton(R.string.invitation_decline_confirm, (dialog, which) ->
                        ledgerRepository.declineInvitation(item.invitationId,
                                new com.skyanchor.bookkeeping.util.Callback<ApiDtos.SimpleResponse>() {
                                    @Override
                                    public void onResult(ApiDtos.SimpleResponse response) {
                                        reload();
                                    }
                                }))
                .setNegativeButton(R.string.common_cancel, null)
                .show();
    }

    private final class InvitationAdapter extends RecyclerView.Adapter<InvitationViewHolder> {

        @NonNull
        @Override
        public InvitationViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_invitation_row, parent, false);
            return new InvitationViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull InvitationViewHolder holder, int position) {
            holder.bind(invitations.get(position));
        }

        @Override
        public int getItemCount() {
            return invitations.size();
        }
    }

    private final class InvitationViewHolder extends RecyclerView.ViewHolder {

        private final TextView title;
        private final TextView subtitle;
        private final View acceptButton;
        private final View declineButton;

        InvitationViewHolder(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.invitationTitle);
            subtitle = itemView.findViewById(R.id.invitationSubtitle);
            acceptButton = itemView.findViewById(R.id.btnAccept);
            declineButton = itemView.findViewById(R.id.btnDecline);
        }

        void bind(@NonNull ApiDtos.InvitationItem item) {
            title.setText(getString(R.string.invitation_item_title, item.ledgerName));
            String roleLabel;
            switch (item.role) {
                case "ADMIN": roleLabel = getString(R.string.role_admin); break;
                case "VIEWER": roleLabel = getString(R.string.role_viewer); break;
                default: roleLabel = getString(R.string.role_member);
            }
            subtitle.setText(getString(R.string.invitation_item_subtitle,
                    item.inviterEmail, roleLabel,
                    dateFormat.format(new Date(item.expiresAt))));
            acceptButton.setOnClickListener(v -> accept(item));
            declineButton.setOnClickListener(v -> decline(item));
        }
    }
}
