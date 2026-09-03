package com.skyanchor.bookkeeping.data.database;

import com.skyanchor.bookkeeping.data.entity.AccountEntity;
import com.skyanchor.bookkeeping.data.entity.CategoryEntity;
import com.skyanchor.bookkeeping.data.entity.UserSettingsEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * 首次启动写入的系统默认数据（V1 基线第 6 章、10.1 节）。
 *
 * <p>建库回调与「清空所有本地数据」后的重置共用这一份定义，避免两处不一致。
 */
public final class DefaultData {

    /** 支出默认分类：名称与图标一一对应。 */
    private static final String[][] EXPENSE_CATEGORIES = {
            {"餐饮", "\uD83C\uDF5A"},
            {"交通", "\uD83D\uDE87"},
            {"购物", "\uD83D\uDED2"},
            {"娱乐", "\uD83C\uDFAE"},
            {"住房", "\uD83C\uDFE0"},
            {"通讯", "\uD83D\uDCF1"},
            {"医疗", "\uD83D\uDC8A"},
            {"教育", "\uD83D\uDCDA"},
            {"旅行", "\u2708\uFE0F"},
            {"其他", "\uD83D\uDCB0"},
    };

    /** 收入默认分类。 */
    private static final String[][] INCOME_CATEGORIES = {
            {"工资", "\uD83D\uDCBC"},
            {"奖金", "\uD83C\uDF81"},
            {"兼职", "\uD83D\uDCBB"},
            {"投资", "\uD83D\uDCC8"},
            {"红包", "\uD83E\uDDE7"},
            {"其他", "\uD83D\uDCB0"},
    };

    /**
     * 默认账户：名称 + 类型 + 是否信用账户（V2 新增）。
     * 初始余额均为 0，sortOrder 按定义顺序从 1 开始。
     */
    private static final Object[][] DEFAULT_ACCOUNTS = {
            {"现金", AccountEntity.TYPE_CASH, Boolean.FALSE},
            {"微信", AccountEntity.TYPE_WECHAT, Boolean.FALSE},
            {"支付宝", AccountEntity.TYPE_ALIPAY, Boolean.FALSE},
            {"储蓄卡", AccountEntity.TYPE_DEBIT, Boolean.FALSE},
            {"信用卡", AccountEntity.TYPE_CREDIT, Boolean.TRUE},
            {"其他", AccountEntity.TYPE_OTHER, Boolean.FALSE},
    };

    private DefaultData() {
    }

    /** 构造全部系统默认分类，sortOrder 按定义顺序从 1 开始。 */
    public static List<CategoryEntity> defaultCategories() {
        List<CategoryEntity> list = new ArrayList<>();
        append(list, EXPENSE_CATEGORIES, CategoryEntity.TYPE_EXPENSE);
        append(list, INCOME_CATEGORIES, CategoryEntity.TYPE_INCOME);
        return list;
    }

    private static void append(List<CategoryEntity> target, String[][] source, int type) {
        for (int i = 0; i < source.length; i++) {
            target.add(new CategoryEntity(source[i][0], source[i][1], type, i + 1, true));
        }
    }

    /**
     * 构造全部系统默认账户（V2），初始余额 0，sortOrder 从 1 开始。
     * 建库回调与「清空所有本地数据」后的重置共用这一份定义。
     */
    public static List<AccountEntity> defaultAccounts() {
        List<AccountEntity> list = new ArrayList<>();
        for (int i = 0; i < DEFAULT_ACCOUNTS.length; i++) {
            Object[] row = DEFAULT_ACCOUNTS[i];
            String name = (String) row[0];
            int type = (Integer) row[1];
            boolean isCredit = (Boolean) row[2];
            list.add(new AccountEntity(name, type, 0L, isCredit, i + 1));
        }
        return list;
    }

    /** 构造默认的本地设置单例。 */
    public static UserSettingsEntity defaultSettings(long now) {
        UserSettingsEntity settings = new UserSettingsEntity();
        settings.id = UserSettingsEntity.SINGLETON_ID;
        settings.theme = UserSettingsEntity.THEME_LIGHT;
        settings.firstLaunch = true;
        settings.createdAt = now;
        settings.updatedAt = now;
        return settings;
    }
}
