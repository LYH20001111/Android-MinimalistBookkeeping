package com.skyanchor.bookkeeping.ui.category;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.tabs.TabLayout;
import com.skyanchor.bookkeeping.BookkeepingApp;
import com.skyanchor.bookkeeping.R;
import com.skyanchor.bookkeeping.data.entity.CategoryEntity;
import com.skyanchor.bookkeeping.data.model.DeleteCategoryResult;
import com.skyanchor.bookkeeping.data.repository.BookkeepingRepository;
import com.skyanchor.bookkeeping.databinding.ActivityCategoryManageBinding;
import com.skyanchor.bookkeeping.databinding.DialogCategoryEditBinding;
import com.skyanchor.bookkeeping.ui.adapter.CategoryAdapter;
import com.skyanchor.bookkeeping.ui.adapter.EmojiAdapter;
import com.skyanchor.bookkeeping.util.InsetsUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 分类管理（V1 基线第 6 章）。
 *
 * <p>支出与收入分类分开维护，各自按 sortOrder 升序；支持新增、编辑、删除与上下移排序。
 * 删除守卫由仓库层强制执行：已被账单使用的分类不允许删除，避免历史统计断裂。
 */
public class CategoryManageActivity extends AppCompatActivity {

    private static final String STATE_TYPE = "state_type";

    private ActivityCategoryManageBinding binding;
    private BookkeepingRepository repository;
    private CategoryAdapter adapter;

    private int currentType = CategoryEntity.TYPE_EXPENSE;

    /** 最近一次全量分类，切 Tab 时本地过滤即可，不必重新查库。 */
    @NonNull
    private List<CategoryEntity> allCategories = new ArrayList<>();

    /** 程序化选中 Tab 时置位，避免监听器再改一次 currentType。 */
    private boolean updatingTabs;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityCategoryManageBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        InsetsUtil.applySystemBarsPadding(binding.categoryRoot);
        InsetsUtil.syncSystemBarAppearance(this);

        repository = BookkeepingApp.get(this).getRepository();
        binding.toolbar.setNavigationOnClickListener(v -> finish());

        adapter = new CategoryAdapter(new CategoryAdapter.Listener() {
            @Override
            public void onMoveUp(@NonNull CategoryEntity category) {
                repository.moveCategory(category.id, CategoryAdapter.DIRECTION_UP, null);
            }

            @Override
            public void onMoveDown(@NonNull CategoryEntity category) {
                repository.moveCategory(category.id, CategoryAdapter.DIRECTION_DOWN, null);
            }

            @Override
            public void onEdit(@NonNull CategoryEntity category) {
                showEditDialog(category);
            }

            @Override
            public void onDelete(@NonNull CategoryEntity category) {
                confirmDelete(category);
            }
        });
        binding.categoryList.setLayoutManager(new LinearLayoutManager(this));
        binding.categoryList.setAdapter(adapter);

        binding.addCategoryButton.setOnClickListener(v -> showEditDialog(null));
        binding.categoryTabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                if (updatingTabs) {
                    return;
                }
                currentType = typeOfTab(tab.getPosition());
                renderList();
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });

        if (savedInstanceState != null) {
            currentType = savedInstanceState.getInt(STATE_TYPE, CategoryEntity.TYPE_EXPENSE);
        }
        selectTab(currentType);
        repository.observeAllCategories().observe(this, this::onCategoriesChanged);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_TYPE, currentType);
    }

    private void onCategoriesChanged(@Nullable List<CategoryEntity> categories) {
        allCategories = categories == null ? new ArrayList<>() : categories;
        renderList();
    }

    /** observeAll 已按 type、sortOrder 排好序，这里按当前 Tab 过滤即可保持顺序。 */
    private void renderList() {
        List<CategoryEntity> visible = new ArrayList<>();
        for (CategoryEntity category : allCategories) {
            if (category.type == currentType) {
                visible.add(category);
            }
        }
        adapter.submitList(visible);
        binding.categoryEmpty.setVisibility(visible.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void selectTab(int type) {
        updatingTabs = true;
        TabLayout.Tab tab = binding.categoryTabs.getTabAt(tabIndexOf(type));
        if (tab != null) {
            binding.categoryTabs.selectTab(tab);
        }
        updatingTabs = false;
    }

    private static int tabIndexOf(int type) {
        return type == CategoryEntity.TYPE_INCOME ? 1 : 0;
    }

    private static int typeOfTab(int position) {
        return position == 1 ? CategoryEntity.TYPE_INCOME : CategoryEntity.TYPE_EXPENSE;
    }

    // ------------------------------------------------------------------
    // 新增 / 编辑
    // ------------------------------------------------------------------

    /**
     * 新增与编辑共用同一个弹窗。
     *
     * <p>确定按钮改为手动关闭，这样名称为空时可以留在弹窗内显示错误提示，
     * 而不是关掉之后什么反馈都没有。
     */
    private void showEditDialog(@Nullable CategoryEntity existing) {
        DialogCategoryEditBinding dialogBinding =
                DialogCategoryEditBinding.inflate(getLayoutInflater());
        EmojiAdapter emojiAdapter = new EmojiAdapter();
        emojiAdapter.setSelected(existing == null ? EmojiPresets.first() : existing.icon);
        dialogBinding.emojiGrid.setLayoutManager(new GridLayoutManager(this,
                getResources().getInteger(R.integer.emoji_grid_span)));
        dialogBinding.emojiGrid.setAdapter(emojiAdapter);

        if (existing != null) {
            dialogBinding.nameInput.setText(existing.name);
        }
        dialogBinding.nameInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                dialogBinding.nameLayout.setError(null);
            }
        });

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(existing == null ? R.string.category_add : R.string.category_edit_title)
                .setView(dialogBinding.getRoot())
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_save, null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            Editable editable = dialogBinding.nameInput.getText();
            String name = editable == null ? "" : editable.toString().trim();
            if (name.isEmpty()) {
                dialogBinding.nameLayout.setError(getString(R.string.category_name_error));
                return;
            }
            saveCategory(existing, name, emojiAdapter.getSelected());
            dialog.dismiss();
        });
    }

    private void saveCategory(@Nullable CategoryEntity existing, @NonNull String name,
                              @NonNull String icon) {
        CategoryEntity entity = new CategoryEntity();
        if (existing != null) {
            // sortOrder 与 isDefault 不在编辑范围内，原样带回，避免 @Update 整行覆盖时被清零
            entity.id = existing.id;
            entity.type = existing.type;
            entity.sortOrder = existing.sortOrder;
            entity.isDefault = existing.isDefault;
        } else {
            entity.type = currentType;
        }
        entity.name = name;
        entity.icon = icon;
        repository.saveCategory(entity, id ->
                Toast.makeText(this, R.string.category_saved, Toast.LENGTH_SHORT).show());
    }

    // ------------------------------------------------------------------
    // 删除（必须二次确认，且受引用守卫约束）
    // ------------------------------------------------------------------

    private void confirmDelete(@NonNull CategoryEntity category) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.category_delete_title, category.name))
                .setMessage(R.string.category_delete_message)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_delete, (dialog, which) ->
                        repository.deleteCategory(category.id,
                                result -> onDeleteResult(category, result)))
                .show();
    }

    private void onDeleteResult(@NonNull CategoryEntity category,
                                @Nullable DeleteCategoryResult result) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (result == null) {
            return;
        }
        if (result.success) {
            Toast.makeText(this, R.string.category_deleted, Toast.LENGTH_SHORT).show();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.category_delete_blocked_title)
                .setMessage(getString(R.string.category_delete_blocked_message,
                        category.name, result.usedCount))
                .setPositiveButton(R.string.action_confirm, null)
                .show();
    }
}
