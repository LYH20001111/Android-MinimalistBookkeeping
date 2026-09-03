package com.skyanchor.bookkeeping.domain.budget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.skyanchor.bookkeeping.data.entity.BudgetEntity;
import com.skyanchor.bookkeeping.data.entity.CategoryEntity;
import com.skyanchor.bookkeeping.data.model.BudgetState;
import com.skyanchor.bookkeeping.data.model.CategoryBudgetState;
import com.skyanchor.bookkeeping.data.model.CategoryStat;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 分类预算计算单元测试（V2 开发计划 Phase 6）。
 *
 * <p>覆盖：分类使用率、80% / 100% 阈值边界、总预算 V1 兼容、总预算哨兵 0 的隔离、
 * 以及两条组装路径（设置页全量分类顺序 / 图表页完成度过滤与排序）。
 * 数据库层 (year, month, category_id) 唯一索引的哨兵唯一性由 androidTest（Phase 10 MigrationTest）覆盖。
 */
public class BudgetCategoryTest {

    private static final long CAT_FOOD = 1L;
    private static final long CAT_TRAFFIC = 2L;
    private static final long CAT_SHOPPING = 3L;
    private static final long CAT_RENT = 4L;
    private static final long CAT_SALARY = 11L;

    // ------------------------------------------------------------------
    // 造数工具
    // ------------------------------------------------------------------

    private static CategoryEntity category(long id, String name, int type, int sortOrder) {
        CategoryEntity entity = new CategoryEntity(name, "💰", type, sortOrder, true);
        entity.id = id;
        return entity;
    }

    private static CategoryEntity expenseCategory(long id, String name, int sortOrder) {
        return category(id, name, CategoryEntity.TYPE_EXPENSE, sortOrder);
    }

    private static BudgetEntity budget(int categoryId, long amount) {
        BudgetEntity entity = new BudgetEntity();
        entity.year = 2026;
        entity.month = 9;
        entity.categoryId = categoryId;
        entity.amount = amount;
        return entity;
    }

    private static CategoryStat stat(long categoryId, String name, long amount) {
        return new CategoryStat(categoryId, name, "💰", amount, 0, 0xFF000000);
    }

    private static void assertState(BudgetState state, boolean hasBudget, long budgetAmount,
                                    long used, long remaining, int percentX10, long overAmount,
                                    int status) {
        assertEquals(hasBudget, state.hasBudget);
        assertEquals(budgetAmount, state.budgetAmount);
        assertEquals(used, state.used);
        assertEquals(remaining, state.remaining);
        assertEquals(percentX10, state.percentX10);
        assertEquals(overAmount, state.overAmount);
        assertEquals(status, state.status);
    }

    // ------------------------------------------------------------------
    // 总预算：V1 兼容
    // ------------------------------------------------------------------

    /** 总预算入口必须与 {@code BudgetState.of} 完全同规则，V1 的「月总预算」语义不变。 */
    @Test
    public void totalBudget_keepsV1Rules() {
        assertState(CalculateBudgetUseCase.total(200_000L, 150_000L),
                true, 200_000L, 150_000L, 50_000L, 750, 0L, BudgetState.STATUS_NORMAL);

        // 预算未设置（<= 0）时必须回落到与 V1 相同的 NOT_SET 单例
        assertSame(BudgetState.NOT_SET, CalculateBudgetUseCase.total(0L, 999L));
        assertSame(BudgetState.NOT_SET, CalculateBudgetUseCase.total(-100L, 999L));
    }

    // ------------------------------------------------------------------
    // 分类预算：使用率与阈值边界
    // ------------------------------------------------------------------

    @Test
    public void categoryBudget_usageRate() {
        BudgetState half = CalculateBudgetUseCase.category(50_000L, 25_000L);
        assertState(half, true, 50_000L, 25_000L, 25_000L, 500, 0L, BudgetState.STATUS_NORMAL);
        assertEquals("50%", half.percentText());

        // 该分类本月没有支出：0% 且正常
        BudgetState zero = CalculateBudgetUseCase.category(50_000L, 0L);
        assertState(zero, true, 50_000L, 0L, 50_000L, 0, 0L, BudgetState.STATUS_NORMAL);

        // 未设置分类预算（<= 0）：NOT_SET，不能把已消费误报成 0% 使用率
        assertSame(BudgetState.NOT_SET, CalculateBudgetUseCase.category(0L, 12_345L));
    }

    /** 80% 进入「接近预算」，100% 仍属「接近」，严格大于 100% 才是「超支」。 */
    @Test
    public void categoryBudget_thresholdBoundaries() {
        // 79.9% → 正常
        assertEquals(BudgetState.STATUS_NORMAL,
                CalculateBudgetUseCase.category(100_000L, 79_900L).status);
        // 恰好 80% → 接近
        assertEquals(BudgetState.STATUS_WARNING,
                CalculateBudgetUseCase.category(100_000L, 80_000L).status);
        // 恰好 100% → 接近（未超出）
        assertEquals(BudgetState.STATUS_WARNING,
                CalculateBudgetUseCase.category(100_000L, 100_000L).status);
        // 100.1% → 超支，且给出超出金额
        BudgetState over = CalculateBudgetUseCase.category(100_000L, 100_100L);
        assertState(over, true, 100_000L, 100_100L, -100L, 1001, 100L, BudgetState.STATUS_OVER);
        // 进度条比例封顶 1，超支部分只由文字说明
        assertEquals(1f, over.progressRatio(), 0f);
    }

    // ------------------------------------------------------------------
    // 组装：预算设置页（全量分类顺序）
    // ------------------------------------------------------------------

    @Test
    public void assembleForManage_keepsCategoryOrderAndIncludesUnset() {
        List<CategoryEntity> categories = Arrays.asList(
                expenseCategory(CAT_FOOD, "餐饮", 1),
                expenseCategory(CAT_TRAFFIC, "交通", 2),
                expenseCategory(CAT_SHOPPING, "购物", 3),
                expenseCategory(CAT_RENT, "房租", 4),
                category(CAT_SALARY, "工资", CategoryEntity.TYPE_INCOME, 5));

        // 哨兵 0 是总预算，不属于任何分类行；categoryId=99 指向已删除分类，必须跳过
        List<BudgetEntity> budgets = Arrays.asList(
                budget(BudgetEntity.CATEGORY_TOTAL, 200_000L),
                budget(2, 100_000L),
                budget(3, 50_000L),
                budget(99, 10_000L));

        List<CategoryStat> stats = Arrays.asList(
                stat(CAT_FOOD, "餐饮", 30_000L),
                stat(CAT_SHOPPING, "购物", 60_000L));

        List<CategoryBudgetState> rows =
                CalculateBudgetUseCase.assembleForManage(categories, budgets, stats);

        // 收入分类不出现；预算设置页要列出全部支出分类（含未设置预算的），便于展示已消费并引导设置
        assertEquals(4, rows.size());
        assertEquals(CAT_FOOD, rows.get(0).categoryId);
        assertEquals(CAT_TRAFFIC, rows.get(1).categoryId);
        assertEquals(CAT_SHOPPING, rows.get(2).categoryId);
        assertEquals(CAT_RENT, rows.get(3).categoryId);

        // 餐饮：未设置预算，但已消费金额必须如实展示
        CategoryBudgetState food = rows.get(0);
        assertFalse(food.hasBudget());
        assertSame(BudgetState.NOT_SET, food.state);
        assertEquals(30_000L, food.used);

        // 交通：设置了预算但本月还没有该分类支出 → 0%
        CategoryBudgetState traffic = rows.get(1);
        assertTrue(traffic.hasBudget());
        assertEquals(0L, traffic.used);
        assertEquals(BudgetState.STATUS_NORMAL, traffic.state.status);

        // 购物：60_000 / 50_000 → 120%，超支
        CategoryBudgetState shopping = rows.get(2);
        assertEquals(1200, shopping.state.percentX10);
        assertEquals(BudgetState.STATUS_OVER, shopping.state.status);
        assertEquals(10_000L, shopping.state.overAmount);
    }

    /** 分类全集未加载完 / 为空时不产出残缺快照；输入为 null 也要安全返回空列表。 */
    @Test
    public void assembleForManage_isEmptyUntilCategoriesArrive() {
        List<BudgetEntity> budgets = Collections.singletonList(budget(1, 100_000L));
        List<CategoryStat> stats = Collections.singletonList(stat(CAT_FOOD, "餐饮", 1L));

        assertTrue(CalculateBudgetUseCase.assembleForManage(null, budgets, stats).isEmpty());
        assertTrue(CalculateBudgetUseCase.assembleForManage(
                Collections.<CategoryEntity>emptyList(), budgets, stats).isEmpty());
    }

    // ------------------------------------------------------------------
    // 组装：图表页「预算完成度」（过滤 + 按严重度排序）
    // ------------------------------------------------------------------

    @Test
    public void assembleForOverview_filtersUnsetAndSortsBySeverity() {
        List<CategoryEntity> categories = Arrays.asList(
                expenseCategory(CAT_FOOD, "餐饮", 1),
                expenseCategory(CAT_TRAFFIC, "交通", 2),
                expenseCategory(CAT_SHOPPING, "购物", 3),
                expenseCategory(CAT_RENT, "房租", 4));

        List<BudgetEntity> budgets = Arrays.asList(
                budget(2, 100_000L),   // 交通 80% → 接近
                budget(3, 50_000L),    // 购物 120% → 超支
                budget(4, 20_000L));   // 房租本月没有支出 → 0% 正常

        List<CategoryStat> stats = Arrays.asList(
                stat(CAT_FOOD, "餐饮", 30_000L),      // 未设置预算 → 不进完成度
                stat(CAT_TRAFFIC, "交通", 80_000L),
                stat(CAT_SHOPPING, "购物", 60_000L));

        List<CategoryBudgetState> rows =
                CalculateBudgetUseCase.assembleForOverview(categories, budgets, stats);

        // 超支在前、接近次之、正常最后；未设置预算的餐饮被过滤
        assertEquals(3, rows.size());
        assertEquals(CAT_SHOPPING, rows.get(0).categoryId);
        assertEquals(CAT_TRAFFIC, rows.get(1).categoryId);
        assertEquals(CAT_RENT, rows.get(2).categoryId);
        assertEquals(0L, rows.get(2).used);
    }

    /** 同状态且同使用率时按 categoryId 升序，保证顺序稳定可复现。 */
    @Test
    public void assembleForOverview_tieBreaksByCategoryId() {
        List<CategoryEntity> categories = Arrays.asList(
                expenseCategory(CAT_TRAFFIC, "交通", 1),
                expenseCategory(CAT_FOOD, "餐饮", 2));
        List<BudgetEntity> budgets = Arrays.asList(
                budget(2, 100_000L), budget(1, 100_000L));
        List<CategoryStat> stats = Arrays.asList(
                stat(CAT_TRAFFIC, "交通", 10_000L), stat(CAT_FOOD, "餐饮", 10_000L));

        List<CategoryBudgetState> rows =
                CalculateBudgetUseCase.assembleForOverview(categories, budgets, stats);

        assertEquals(2, rows.size());
        assertEquals(CAT_FOOD, rows.get(0).categoryId);
        assertEquals(CAT_TRAFFIC, rows.get(1).categoryId);
    }
}
