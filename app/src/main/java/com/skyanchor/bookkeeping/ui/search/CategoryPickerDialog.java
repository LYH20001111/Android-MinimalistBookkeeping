package com.skyanchor.bookkeeping.ui.search;

import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.GridLayoutManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.skyanchor.bookkeeping.R;
import com.skyanchor.bookkeeping.data.entity.CategoryEntity;
import com.skyanchor.bookkeeping.data.model.SearchFilter;
import com.skyanchor.bookkeeping.databinding.DialogCategoryPickerBinding;
import com.skyanchor.bookkeeping.ui.adapter.PickerCategoryGridAdapter;
import com.skyanchor.bookkeeping.ui.category.CategoryManageActivity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 搜索分类选择器弹窗（V2.1 Phase 1，基线第 5 章）。
 *
 * <p>图标网格（3/4 列按屏幕宽度自适应）+ 「全部分类」伪项（id={@link SearchFilter#NO_CATEGORY}）
 * + 可选「最近使用」快捷区。选中后回调一次并关闭；分类为空时展示空态与「去分类管理」入口。
 */
public final class CategoryPickerDialog {

    /** 「全部分类」伪分类使用的中性图标。 */
    private static final String PSEUDO_ALL_ICON = "🗂";

    public interface Listener {
        /** @param category 选中分类；「全部分类」时为 id=0 的伪分类实体。 */
        void onCategoryPicked(@NonNull CategoryEntity category);
    }

    private CategoryPickerDialog() {
    }

    /**
     * @param categories 全量候选（支出 + 收入）
     * @param selectedId 当前选中分类 id（0 = 未选具体分类）
     * @param recentIds  最近使用 id（最新在前；允许含已不存在的 id，会被过滤）
     */
    public static void show(@NonNull Context context, @NonNull List<CategoryEntity> categories,
                            long selectedId, @NonNull long[] recentIds,
                            @NonNull Listener listener) {
        DialogCategoryPickerBinding binding = DialogCategoryPickerBinding.inflate(
                LayoutInflater.from(context));

        if (categories.isEmpty()) {
            binding.pickerGrid.setVisibility(View.GONE);
            binding.pickerEmpty.setVisibility(View.VISIBLE);
            binding.pickerGoManage.setOnClickListener(v ->
                    context.startActivity(new Intent(context, CategoryManageActivity.class)));
            new MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.picker_category_title)
                    .setView(binding.getRoot())
                    .setNegativeButton(R.string.action_cancel, null)
                    .show();
            return;
        }

        // 先 create() 拿到 dialog，再把它注入适配器的点击回调：选中 → 回调 + 关闭
        AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.picker_category_title)
                .setView(binding.getRoot())
                .setNegativeButton(R.string.action_cancel, null)
                .create();
        PickerCategoryGridAdapter adapter = new PickerCategoryGridAdapter(selectedId,
                category -> {
                    listener.onCategoryPicked(category);
                    dialog.dismiss();
                });
        int spanCount = spanCount(context);
        GridLayoutManager layoutManager = new GridLayoutManager(context, spanCount);
        // 「最近使用」等区块标题行独占整行，分类项各占一列
        layoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return adapter.getItemViewType(position)
                        == PickerCategoryGridAdapter.Row.TYPE_HEADER ? spanCount : 1;
            }
        });
        binding.pickerGrid.setLayoutManager(layoutManager);
        binding.pickerGrid.setAdapter(adapter);
        adapter.submitList(buildRows(context, categories, recentIds));
        dialog.show();
    }

    /** 组装行列表：可选「最近使用」区 + 「全部分类」伪项 + 全量分类。 */
    @NonNull
    static List<PickerCategoryGridAdapter.Row> buildRows(@NonNull Context context,
                                                         @NonNull List<CategoryEntity> categories,
                                                         @NonNull long[] recentIds) {
        List<PickerCategoryGridAdapter.Row> rows = new ArrayList<>();
        List<CategoryEntity> recent = resolveRecent(categories, recentIds);
        if (!recent.isEmpty()) {
            rows.add(PickerCategoryGridAdapter.Row.header(
                    context.getString(R.string.picker_recent_section)));
            for (CategoryEntity category : recent) {
                rows.add(PickerCategoryGridAdapter.Row.of(category));
            }
        }
        CategoryEntity all = new CategoryEntity(
                context.getString(R.string.search_all_categories), PSEUDO_ALL_ICON,
                CategoryEntity.TYPE_EXPENSE, 0, false);
        rows.add(PickerCategoryGridAdapter.Row.of(all));
        for (CategoryEntity category : categories) {
            rows.add(PickerCategoryGridAdapter.Row.of(category));
        }
        return rows;
    }

    /** 最近使用 id 映射回现存的分类实体，保持 recency 顺序、去重、过滤失效 id。 */
    @NonNull
    static List<CategoryEntity> resolveRecent(@NonNull List<CategoryEntity> categories,
                                              @NonNull long[] recentIds) {
        if (recentIds.length == 0) {
            return new ArrayList<>();
        }
        Map<Long, CategoryEntity> byId = new LinkedHashMap<>();
        for (CategoryEntity category : categories) {
            byId.put(category.id, category);
        }
        List<CategoryEntity> recent = new ArrayList<>();
        for (long id : recentIds) {
            CategoryEntity category = byId.get(id);
            if (category != null && !recent.contains(category)) {
                recent.add(category);
            }
        }
        return recent;
    }

    /** 3/4 列自适应：按屏幕宽度 dp 判断，窄屏 3 列、宽屏 4 列（基线 5.3 交互要求 1）。 */
    static int spanCount(@NonNull Context context) {
        float widthDp = context.getResources().getDisplayMetrics().widthPixels
                / context.getResources().getDisplayMetrics().density;
        return widthDp >= 380f ? 4 : 3;
    }
}
