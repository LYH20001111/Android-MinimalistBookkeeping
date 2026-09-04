package com.skyanchor.bookkeeping.ui.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.view.View;
import android.widget.FrameLayout;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.skyanchor.bookkeeping.R;
import com.skyanchor.bookkeeping.data.entity.AccountEntity;
import com.skyanchor.bookkeeping.data.model.SearchFilter;
import com.skyanchor.bookkeeping.data.entity.CategoryEntity;
import com.skyanchor.bookkeeping.databinding.DialogAccountPickerBinding;
import com.skyanchor.bookkeeping.databinding.DialogCategoryPickerBinding;
import com.skyanchor.bookkeeping.ui.adapter.PickerAccountAdapter;
import com.skyanchor.bookkeeping.ui.adapter.PickerCategoryGridAdapter;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.List;

/**
 * 搜索页选择器的冒烟回归（V2.1 Phase 1）。
 *
 * <p>背景一：V2.1 把搜索页分类 / 账户字段从 ExposedDropdownMenu 样式改为
 * 「点击弹图标选择器」，但该 Material 样式在运行时强制要求子控件是
 * {@code AutoCompleteTextView}，混用会直接 {@code InflateException} 崩溃。
 * 背景二：行模型含「最近使用」区块标题行，两种行类型必须各自持有 ViewHolder，
 * 标题行误绑到内容行会抛 {@code IllegalStateException}。
 * 这里在真机锁定：页面能启动渲染、弹窗布局能加载、标题行 + 内容行能经
 * {@code onBindViewHolder} 正常绑定、带「最近使用」的弹窗能完整展示。
 */
@RunWith(AndroidJUnit4.class)
public class SearchActivitySmokeTest {

    /** 测试用分类 / 账户 id：指向不存在的记录也合法（选择器会过滤失效的最近使用）。 */
    private static final long FOOD_ID = 7001L;
    private static final long WECHAT_ID = 7002L;

    private static CategoryEntity foodCategory() {
        CategoryEntity food = new CategoryEntity("餐饮", "🍜", CategoryEntity.TYPE_EXPENSE, 1,
                false);
        food.id = FOOD_ID;
        return food;
    }

    private static AccountEntity wechatAccount() {
        AccountEntity wechat = new AccountEntity("微信", AccountEntity.TYPE_WECHAT, 0L, false, 1);
        wechat.id = WECHAT_ID;
        return wechat;
    }

    @Test
    public void searchActivity_launchesAndRenders() {
        try (ActivityScenario<SearchActivity> scenario =
                ActivityScenario.launch(SearchActivity.class)) {
            scenario.onActivity(activity -> {
                assertNotNull(activity.getWindow());
                // 关键控件存在且可见
                assertNotNull(activity.findViewById(R.id.categoryInput));
                assertNotNull(activity.findViewById(R.id.accountInput));
                assertNotNull(activity.findViewById(R.id.searchList));
            });
        }
    }

    @Test
    public void pickerDialogLayouts_inflate() {
        // 弹窗在生产代码里用 Activity 的 LayoutInflater 加载（携带 Material 主题），
        // 因此这里也必须用 Activity context；Application context 没有 Material 主题，
        // 加载 MaterialButton 会抛 IllegalArgumentException（与生产路径无关的测试假象）。
        try (ActivityScenario<SearchActivity> scenario =
                ActivityScenario.launch(SearchActivity.class)) {
            scenario.onActivity(activity -> {
                DialogCategoryPickerBinding category = DialogCategoryPickerBinding.inflate(
                        activity.getLayoutInflater());
                assertNotNull(category.pickerGrid);
                assertNotNull(category.pickerEmpty);

                DialogAccountPickerBinding account = DialogAccountPickerBinding.inflate(
                        activity.getLayoutInflater());
                assertNotNull(account.pickerList);
                assertNotNull(account.pickerEmpty);
            });
        }
    }

    /** 两个筛选字段的布局必须是「普通样式 + 只读输入框」，不能回退成 Dropdown + EditText 组合。 */
    @Test
    public void searchFilterFields_areClickableReadOnly() {
        try (ActivityScenario<SearchActivity> scenario =
                ActivityScenario.launch(SearchActivity.class)) {
            scenario.onActivity(activity -> {
                View category = activity.findViewById(R.id.categoryInput);
                View account = activity.findViewById(R.id.accountInput);
                // 可点击（接弹窗）、不获取焦点（不弹键盘）：EditText 本身保持 enabled
                org.junit.Assert.assertTrue(category.isClickable());
                org.junit.Assert.assertFalse(category.hasFocusable());
                org.junit.Assert.assertTrue(account.isClickable());
                org.junit.Assert.assertFalse(account.hasFocusable());
            });
        }
    }

    /**
     * 崩溃回归：带「最近使用」时行列表首行是区块标题（category / account 均为 null），
     * 两种行类型各自持有 ViewHolder，标题行必须能经 {@code onBindViewHolder} 正常绑定。
     */
    @Test
    public void pickerAdapters_bindHeaderAndItemRows() {
        try (ActivityScenario<SearchActivity> scenario =
                ActivityScenario.launch(SearchActivity.class)) {
            scenario.onActivity(activity -> {
                FrameLayout parent = new FrameLayout(activity);

                // 分类：[「最近使用」标题, 餐饮（快捷入口）, 全部分类（伪项）, 餐饮（完整列表）]
                // 最近使用只是快捷入口，不改变完整分类排序（基线 5.5），故列表仍含该分类
                List<PickerCategoryGridAdapter.Row> rows = CategoryPickerDialog.buildRows(
                        activity, Collections.singletonList(foodCategory()),
                        new long[]{FOOD_ID});
                assertEquals(4, rows.size());
                assertEquals(PickerCategoryGridAdapter.Row.TYPE_HEADER, rows.get(0).type);
                assertEquals(FOOD_ID, rows.get(1).category.id);
                assertEquals(SearchFilter.NO_CATEGORY, rows.get(2).category.id);
                PickerCategoryGridAdapter categoryAdapter = new PickerCategoryGridAdapter(0L,
                        c -> { });
                categoryAdapter.submitList(rows);
                PickerCategoryGridAdapter.ViewHolder header =
                        categoryAdapter.onCreateViewHolder(parent,
                                categoryAdapter.getItemViewType(0));
                categoryAdapter.onBindViewHolder(header, 0);
                PickerCategoryGridAdapter.ViewHolder item =
                        categoryAdapter.onCreateViewHolder(parent,
                                categoryAdapter.getItemViewType(1));
                categoryAdapter.onBindViewHolder(item, 1);

                // 账户：[「最近使用」标题, 微信, 全部账户（伪项）]
                List<PickerAccountAdapter.Row> accountRows = AccountPickerDialog.buildRows(
                        activity, Collections.singletonList(wechatAccount()),
                        new long[]{WECHAT_ID});
                assertEquals(4, accountRows.size());
                assertEquals(PickerAccountAdapter.Row.TYPE_HEADER, accountRows.get(0).type);
                PickerAccountAdapter accountAdapter = new PickerAccountAdapter(0L, a -> { });
                accountAdapter.submitList(accountRows);
                PickerAccountAdapter.ViewHolder accountHeader =
                        accountAdapter.onCreateViewHolder(parent,
                                accountAdapter.getItemViewType(0));
                accountAdapter.onBindViewHolder(accountHeader, 0);
                PickerAccountAdapter.ViewHolder accountItem =
                        accountAdapter.onCreateViewHolder(parent,
                                accountAdapter.getItemViewType(1));
                accountAdapter.onBindViewHolder(accountItem, 1);
            });
        }
    }

    /** 端到端：记录最近使用后弹窗真实展示（标题行在真实布局流程中绑定，不再崩溃）。 */
    @Test
    public void pickerDialogs_showWithRecentSection() {
        try (ActivityScenario<SearchActivity> scenario =
                ActivityScenario.launch(SearchActivity.class)) {
            scenario.onActivity(activity -> {
                RecentFilterStore.record(activity, RecentFilterStore.SCOPE_CATEGORY, FOOD_ID);
                RecentFilterStore.record(activity, RecentFilterStore.SCOPE_ACCOUNT, WECHAT_ID);
                CategoryPickerDialog.show(activity,
                        Collections.singletonList(foodCategory()), 0L,
                        RecentFilterStore.recentIds(activity, RecentFilterStore.SCOPE_CATEGORY),
                        c -> { });
                AccountPickerDialog.show(activity,
                        Collections.singletonList(wechatAccount()), 0L,
                        RecentFilterStore.recentIds(activity, RecentFilterStore.SCOPE_ACCOUNT),
                        a -> { });
            });
            // 等 UI 空闲：让两个弹窗完成布局与 RecyclerView 绑定（标题行在此绑定）
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        }
    }
}
