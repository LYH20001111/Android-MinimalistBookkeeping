package com.skyanchor.bookkeeping.domain.budget;

import com.skyanchor.bookkeeping.data.entity.BudgetEntity;
import com.skyanchor.bookkeeping.data.entity.CategoryEntity;
import com.skyanchor.bookkeeping.data.model.BudgetState;
import com.skyanchor.bookkeeping.data.model.CategoryBudgetState;
import com.skyanchor.bookkeeping.data.model.CategoryStat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 预算计算用例（V2 新增，开发计划 Phase 6）。
 *
 * <p>总预算 = 本月总支出 / 总预算；分类预算 = 该分类本月支出 / 该分类预算。
 * 两者状态都沿用 {@link BudgetState} 的阈值（&lt;80% 正常、80%~100% 接近、&gt;100% 超支），
 * 因此预算设置页与图表页预算卡片必然同规则。
 *
 * <p>全部为与框架无关的纯函数，可在 JVM 单元测试中直接验证；
 * LiveData 装配（交易 / 分类 / 预算三路同源刷新）由各 ViewModel 完成。
 * 分类预算只做提醒与分析，不反向限制记账。
 */
public final class CalculateBudgetUseCase {

    private CalculateBudgetUseCase() {
    }

    /**
     * 总预算状态（分）。与 {@link BudgetState#of} 同一实现，保留独立入口是为了让
     * 「总预算」在调用点有明确语义，且未来规则分化时有唯一的修改位置。
     *
     * @param budgetAmount 总预算（分），小于等于 0 视为未设置
     * @param totalExpense 本月总支出（分）
     */
    public static BudgetState total(long budgetAmount, long totalExpense) {
        return BudgetState.of(budgetAmount, totalExpense);
    }

    /**
     * 分类预算状态（分）。与总预算共用同一套阈值与状态色。
     *
     * @param categoryBudget  该分类预算（分），小于等于 0 视为未设置
     * @param categoryExpense 该分类本月支出（分）
     */
    public static BudgetState category(long categoryBudget, long categoryExpense) {
        return BudgetState.of(categoryBudget, categoryExpense);
    }

    /**
     * 组装「预算设置页」的分类预算行：支出分类全集 × 分类预算 × 分类支出。
     *
     * <p>顺序保持分类列表（sort_order）顺序，便于在固定位置编辑；未设置预算的分类
     * 也会出现（预算额 0、状态 NOT_SET），用于展示已消费金额并引导设置。
     * {@code categoryId = 0} 的总预算哨兵被排除；预算指向已删除分类的陈旧数据被跳过
     * （分类删除时仓库层会连带清理，这里兜底防御）。
     *
     * @param categories     支出分类全集（sort_order 升序），null 视为还没加载完
     * @param categoryBudgets 该月的分类预算（category_id &gt;= 1）
     * @param expenseStats   本月支出按分类的统计（{@code StatisticsCalculator.categoryBreakdown}）
     */
    public static List<CategoryBudgetState> assembleForManage(
            List<CategoryEntity> categories, List<BudgetEntity> categoryBudgets,
            List<CategoryStat> expenseStats) {
        if (categories == null || categories.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, Long> budgetByCategory = budgetAmountsByCategory(categoryBudgets);
        Map<Long, CategoryStat> statByCategory = statsByCategory(expenseStats);

        List<CategoryBudgetState> rows = new ArrayList<>(categories.size());
        for (CategoryEntity category : categories) {
            if (category == null || category.type != CategoryEntity.TYPE_EXPENSE) {
                continue;
            }
            Long budgetAmount = budgetByCategory.get(category.id);
            long budget = budgetAmount == null ? 0L : budgetAmount;
            CategoryStat stat = statByCategory.get(category.id);
            long used = stat == null ? 0L : stat.amount;
            rows.add(new CategoryBudgetState(category.id, category.name, category.icon,
                    budget, used, category(budget, used)));
        }
        return rows;
    }

    /**
     * 组装「预算完成度」行：仅保留已设置预算的分类，按状态严重度（超支 → 接近 → 正常）
     * 与使用率降序排列，让最需要关注的分类排在最前。
     *
     * @param categories      支出分类全集，null 视为还没加载完
     * @param categoryBudgets 该月的分类预算（category_id &gt;= 1）
     * @param expenseStats    本月支出按分类的统计
     */
    public static List<CategoryBudgetState> assembleForOverview(
            List<CategoryEntity> categories, List<BudgetEntity> categoryBudgets,
            List<CategoryStat> expenseStats) {
        List<CategoryBudgetState> all = assembleForManage(categories, categoryBudgets, expenseStats);
        List<CategoryBudgetState> rows = new ArrayList<>(all.size());
        for (CategoryBudgetState row : all) {
            if (row.hasBudget()) {
                rows.add(row);
            }
        }
        Collections.sort(rows, new Comparator<CategoryBudgetState>() {
            @Override
            public int compare(CategoryBudgetState left, CategoryBudgetState right) {
                // 状态常量数值越大越严重：OVER(2) > WARNING(1) > NORMAL(0)
                int byStatus = Integer.compare(right.state.status, left.state.status);
                if (byStatus != 0) {
                    return byStatus;
                }
                int byUsage = Integer.compare(right.state.percentX10, left.state.percentX10);
                return byUsage != 0 ? byUsage : Long.compare(left.categoryId, right.categoryId);
            }
        });
        return rows;
    }

    /** 分类预算按 categoryId 归集；总预算哨兵 0 与异常的负 id 一并排除。 */
    private static Map<Long, Long> budgetAmountsByCategory(List<BudgetEntity> categoryBudgets) {
        Map<Long, Long> result = new HashMap<>();
        if (categoryBudgets == null) {
            return result;
        }
        for (BudgetEntity budget : categoryBudgets) {
            if (budget == null || budget.categoryId <= BudgetEntity.CATEGORY_TOTAL) {
                continue;
            }
            result.put((long) budget.categoryId, budget.amount);
        }
        return result;
    }

    /** 分类支出按 categoryId 归集。 */
    private static Map<Long, CategoryStat> statsByCategory(List<CategoryStat> expenseStats) {
        Map<Long, CategoryStat> result = new HashMap<>();
        if (expenseStats == null) {
            return result;
        }
        for (CategoryStat stat : expenseStats) {
            if (stat != null) {
                result.put(stat.categoryId, stat);
            }
        }
        return result;
    }
}
