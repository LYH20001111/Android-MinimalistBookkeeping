package com.skyanchor.bookkeeping;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.skyanchor.bookkeeping.databinding.ActivityMainBinding;
import com.skyanchor.bookkeeping.ui.chart.ChartFragment;
import com.skyanchor.bookkeeping.ui.mine.MineFragment;
import com.skyanchor.bookkeeping.ui.record.RecordFragment;
import com.skyanchor.bookkeeping.util.InsetsUtil;

/**
 * 主框架：三 Tab 底部导航（记录 / 图表 / 我的）。
 *
 * <p>Fragment 用 {@code add / hide / show} 而不是 {@code replace}，这样切 Tab 时
 * 各页面的滚动位置、已选周期、已选业务日期都不会丢失（V1 基线第 15 章）。
 * 未访问过的 Tab 延迟创建，冷启动只付记录页的代价。
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG_RECORD = "record";
    private static final String TAG_CHART = "chart";
    private static final String TAG_MINE = "mine";

    /** 遍历顺序与底部导航一致。 */
    private static final String[] TAB_TAGS = {TAG_RECORD, TAG_CHART, TAG_MINE};

    private static final String STATE_SELECTED_TAB = "state_selected_tab";

    private ActivityMainBinding binding;
    private String selectedTag = TAG_RECORD;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        InsetsUtil.applyTopAndHorizontalPadding(binding.mainRoot);
        InsetsUtil.applyBottomPadding(binding.bottomNav);
        InsetsUtil.syncSystemBarAppearance(this);

        binding.bottomNav.setOnItemSelectedListener(item -> {
            selectTab(tagOf(item.getItemId()));
            return true;
        });

        String restored = savedInstanceState == null
                ? null : savedInstanceState.getString(STATE_SELECTED_TAB);
        selectedTag = restored == null ? TAG_RECORD : restored;

        // 先恢复 Fragment 可见性，再同步导航选中态；setSelectedItemId 会回调监听器，
        // 但 selectTab 对同一 tag 是幂等的，不会产生多余事务。
        selectTab(selectedTag);
        binding.bottomNav.setSelectedItemId(itemIdOf(selectedTag));
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_SELECTED_TAB, selectedTag);
    }

    /** 显示目标 Tab，隐藏其余已创建的 Tab。 */
    private void selectTab(@NonNull String tag) {
        FragmentManager fragmentManager = getSupportFragmentManager();
        FragmentTransaction transaction = fragmentManager.beginTransaction();
        transaction.setReorderingAllowed(true);

        boolean changed = false;
        for (String candidate : TAB_TAGS) {
            Fragment fragment = fragmentManager.findFragmentByTag(candidate);
            if (fragment == null) {
                if (!candidate.equals(tag)) {
                    continue;
                }
                fragment = newFragment(candidate);
                transaction.add(R.id.fragmentHost, fragment, candidate);
                changed = true;
            }
            if (candidate.equals(tag)) {
                if (fragment.isHidden()) {
                    transaction.show(fragment);
                    changed = true;
                }
            } else if (!fragment.isHidden()) {
                transaction.hide(fragment);
                changed = true;
            }
        }

        if (changed) {
            transaction.commit();
        }
        selectedTag = tag;
    }

    @NonNull
    private static Fragment newFragment(@NonNull String tag) {
        switch (tag) {
            case TAG_CHART:
                return ChartFragment.newInstance();
            case TAG_MINE:
                return MineFragment.newInstance();
            case TAG_RECORD:
            default:
                return RecordFragment.newInstance();
        }
    }

    @NonNull
    private static String tagOf(int itemId) {
        if (itemId == R.id.tab_chart) {
            return TAG_CHART;
        }
        if (itemId == R.id.tab_mine) {
            return TAG_MINE;
        }
        return TAG_RECORD;
    }

    private static int itemIdOf(@NonNull String tag) {
        switch (tag) {
            case TAG_CHART:
                return R.id.tab_chart;
            case TAG_MINE:
                return R.id.tab_mine;
            case TAG_RECORD:
            default:
                return R.id.tab_record;
        }
    }
}
