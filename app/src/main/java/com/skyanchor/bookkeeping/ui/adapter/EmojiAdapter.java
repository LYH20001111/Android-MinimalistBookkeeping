package com.skyanchor.bookkeeping.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.skyanchor.bookkeeping.databinding.ItemEmojiBinding;
import com.skyanchor.bookkeeping.ui.category.EmojiPresets;

import java.util.Arrays;
import java.util.List;

/**
 * 分类编辑弹窗里的 emoji 单选网格。
 *
 * <p>候选项是固定的预设列表，不需要 DiffUtil；选中态由适配器内部维护，
 * 切换时只刷新「旧选中」与「新选中」两格。
 */
public class EmojiAdapter extends RecyclerView.Adapter<EmojiAdapter.ViewHolder> {

    private final List<String> emojis = Arrays.asList(EmojiPresets.all());

    @NonNull
    private String selected = EmojiPresets.first();

    /** 设置选中图标；传入的图标不在预设列表中时原样保留，保证编辑老数据不丢图标。 */
    public void setSelected(@NonNull String emoji) {
        if (selected.equals(emoji)) {
            return;
        }
        String previous = selected;
        selected = emoji;
        int previousIndex = emojis.indexOf(previous);
        int nextIndex = emojis.indexOf(emoji);
        if (previousIndex >= 0) {
            notifyItemChanged(previousIndex);
        }
        if (nextIndex >= 0) {
            notifyItemChanged(nextIndex);
        }
    }

    @NonNull
    public String getSelected() {
        return selected;
    }

    @Override
    public int getItemCount() {
        return emojis.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(ItemEmojiBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(emojis.get(position));
    }

    final class ViewHolder extends RecyclerView.ViewHolder {

        private final ItemEmojiBinding binding;

        ViewHolder(@NonNull ItemEmojiBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(@NonNull String emoji) {
            binding.getRoot().setSelected(emoji.equals(selected));
            binding.emojiText.setText(emoji);
            binding.getRoot().setOnClickListener(v -> setSelected(emoji));
        }
    }
}
