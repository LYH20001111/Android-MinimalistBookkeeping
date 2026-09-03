package com.skyanchor.bookkeeping.util;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import com.skyanchor.bookkeeping.R;
import com.skyanchor.bookkeeping.data.entity.AccountEntity;

/**
 * 账户类型的展示映射（V2 新增）。
 *
 * <p>把 {@link AccountEntity} 的 TYPE_* 常量集中翻译成「本地化标签 + emoji 图标」，
 * 供账户管理列表、账户编辑弹窗的类型下拉、图表页账户资金卡片复用，
 * 避免多处各写一份 switch 造成不一致。
 */
public final class AccountTypes {

    private AccountTypes() {
    }

    /** 全部账户类型，按选择器展示顺序（现金 → 微信 → 支付宝 → 储蓄卡 → 信用卡 → 其他）。 */
    public static final int[] ALL = {
            AccountEntity.TYPE_CASH,
            AccountEntity.TYPE_WECHAT,
            AccountEntity.TYPE_ALIPAY,
            AccountEntity.TYPE_DEBIT,
            AccountEntity.TYPE_CREDIT,
            AccountEntity.TYPE_OTHER,
    };

    /** 类型对应的本地化标签资源。未知类型回落到「现金」。 */
    @StringRes
    public static int labelRes(int type) {
        switch (type) {
            case AccountEntity.TYPE_WECHAT:
                return R.string.account_type_wechat;
            case AccountEntity.TYPE_ALIPAY:
                return R.string.account_type_alipay;
            case AccountEntity.TYPE_DEBIT:
                return R.string.account_type_debit;
            case AccountEntity.TYPE_CREDIT:
                return R.string.account_type_credit;
            case AccountEntity.TYPE_OTHER:
                return R.string.account_type_other;
            case AccountEntity.TYPE_CASH:
            default:
                return R.string.account_type_cash;
        }
    }

    /** 类型对应的 emoji 图标，用于账户行 / 卡片的圆形图标底。 */
    @NonNull
    public static String emoji(int type) {
        switch (type) {
            case AccountEntity.TYPE_WECHAT:
                return "\uD83D\uDCAC"; // 💬
            case AccountEntity.TYPE_ALIPAY:
                return "\uD83D\uDD37"; // 🔷
            case AccountEntity.TYPE_DEBIT:
                return "\uD83C\uDFE6"; // 🏦
            case AccountEntity.TYPE_CREDIT:
                return "\uD83D\uDCB3"; // 💳
            case AccountEntity.TYPE_OTHER:
                return "\uD83D\uDC5B"; // 👛
            case AccountEntity.TYPE_CASH:
            default:
                return "\uD83D\uDCB5"; // 💵
        }
    }

    /** 是否为信用账户：类型选到「信用卡」即视为信用账户。 */
    public static boolean isCredit(int type) {
        return type == AccountEntity.TYPE_CREDIT;
    }
}
