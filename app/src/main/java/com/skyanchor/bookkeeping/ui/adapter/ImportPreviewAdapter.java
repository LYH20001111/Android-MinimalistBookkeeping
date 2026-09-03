package com.skyanchor.bookkeeping.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.skyanchor.bookkeeping.R;
import com.skyanchor.bookkeeping.data.model.ImportRowResult;
import com.skyanchor.bookkeeping.databinding.ItemImportPreviewBinding;

import java.util.ArrayList;
import java.util.List;

/**
 * 导入预览列表（V2 新增，开发计划 Phase 5）。
 *
 * <p>一次性快照列表：解析完成后整表提交，不再增量变化，故用简单 {@link RecyclerView.Adapter}
 * 而非 {@code ListAdapter + DiffUtil}。每行展示状态标签（有效 / 重复跳过 / 错误，按语义色着色）、
 * 数据摘要、错误 / 跳过原因（本地化自 domain 的语义码）与 CSV 行号。
 */
public class ImportPreviewAdapter extends RecyclerView.Adapter<ImportPreviewAdapter.ViewHolder> {

    private final List<ImportRowResult> items = new ArrayList<>();

    /** 整表提交预览结果。 */
    public void submit(@Nullable List<ImportRowResult> rows) {
        items.clear();
        if (rows != null) {
            items.addAll(rows);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(ItemImportPreviewBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(items.get(position));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    final class ViewHolder extends RecyclerView.ViewHolder {

        private final ItemImportPreviewBinding binding;

        ViewHolder(@NonNull ItemImportPreviewBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(@NonNull ImportRowResult row) {
            int color = ContextCompat.getColor(itemView.getContext(), statusColor(row.status));
            binding.previewStatus.setText(statusLabel(row.status));
            binding.previewStatus.setTextColor(color);
            binding.previewSummary.setText(row.summary);
            binding.previewLine.setText(
                    itemView.getContext().getString(R.string.import_line_format, row.lineNumber));

            if (row.reason == ImportRowResult.Reason.NONE) {
                binding.previewReason.setVisibility(android.view.View.GONE);
            } else {
                binding.previewReason.setVisibility(android.view.View.VISIBLE);
                binding.previewReason.setText(reasonLabel(row.reason));
                binding.previewReason.setTextColor(color);
            }
        }
    }

    private static int statusLabel(@NonNull ImportRowResult.Status status) {
        switch (status) {
            case VALID:
                return R.string.import_status_valid;
            case DUPLICATE:
                return R.string.import_status_duplicate;
            case ERROR:
            default:
                return R.string.import_status_error;
        }
    }

    private static int statusColor(@NonNull ImportRowResult.Status status) {
        switch (status) {
            case VALID:
                return R.color.success;
            case DUPLICATE:
                return R.color.warning;
            case ERROR:
            default:
                return R.color.danger;
        }
    }

    /** domain 只给语义码，文案在 UI 层本地化。 */
    private static int reasonLabel(@NonNull ImportRowResult.Reason reason) {
        switch (reason) {
            case DUPLICATE:
                return R.string.import_reason_duplicate;
            case MALFORMED_ROW:
                return R.string.import_reason_malformed;
            case TYPE_INVALID:
                return R.string.import_reason_type_invalid;
            case AMOUNT_INVALID:
                return R.string.import_reason_amount_invalid;
            case DATE_INVALID:
                return R.string.import_reason_date_invalid;
            case CATEGORY_MISSING:
                return R.string.import_reason_category_missing;
            case ACCOUNT_MISSING:
                return R.string.import_reason_account_missing;
            case TRANSFER_INVALID:
                return R.string.import_reason_transfer_invalid;
            case NONE:
            default:
                return R.string.import_reason_malformed;
        }
    }
}
