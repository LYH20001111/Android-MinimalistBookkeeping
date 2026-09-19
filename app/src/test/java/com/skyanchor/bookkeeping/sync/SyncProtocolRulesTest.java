package com.skyanchor.bookkeeping.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.skyanchor.bookkeeping.data.entity.SyncEntityTypes;
import com.skyanchor.bookkeeping.data.remote.ApiDtos;

import org.junit.Test;

/**
 * 同步协议纯规则单测（Sync Protocol Version 3）：
 * 实体处理顺序（引用先于被引用）与预算总预算哨兵的协议映射。
 */
public class SyncProtocolRulesTest {

    @Test
    public void pushOrder_referencesBeforeReferenced() {
        // 账本 → 分类 → 账户 → 交易 → 退款 → 预算 → 周期（开发计划备注 2）
        assertEquals(-1, SyncPayloadMapper.orderOf(SyncEntityTypes.LEDGER));
        assertEquals(0, SyncPayloadMapper.orderOf(SyncEntityTypes.CATEGORY));
        assertEquals(1, SyncPayloadMapper.orderOf(SyncEntityTypes.ACCOUNT));
        assertEquals(2, SyncPayloadMapper.orderOf(SyncEntityTypes.TRANSACTION));
        assertEquals(3, SyncPayloadMapper.orderOf(SyncEntityTypes.REFUND));
        assertEquals(4, SyncPayloadMapper.orderOf(SyncEntityTypes.BUDGET));
        assertEquals(5, SyncPayloadMapper.orderOf(SyncEntityTypes.RECURRING));
        // 未知类型兜底排最后
        assertEquals(6, SyncPayloadMapper.orderOf("UNKNOWN"));
    }

    /**
     * V4.0：退款必须紧随其父账单。同一批次里账单先落库，退款才不会拿到
     * MISSING_REFERENCE 反复退避；位置一旦漂移就会被预算等下游实体插队。
     */
    @Test
    public void refund_isOrderedImmediatelyAfterItsParentTransaction() {
        assertEquals(SyncPayloadMapper.orderOf(SyncEntityTypes.TRANSACTION) + 1,
                SyncPayloadMapper.orderOf(SyncEntityTypes.REFUND));
        assertTrue(SyncPayloadMapper.orderOf(SyncEntityTypes.REFUND)
                < SyncPayloadMapper.orderOf(SyncEntityTypes.BUDGET));
    }

    @Test
    public void budgetTotalSentinel_mapsToNullInProtocol() {
        // 本地 categoryId=0（总预算哨兵）↔ 协议 null（基线 38 章 category_id=0 语义）
        assertNull(ApiDtos.budgetCategoryRef(0, "some-sync-id"));
    }

    @Test
    public void budgetCategoryBudget_keepsCategorySyncId() {
        // 分类预算保留其分类的跨设备身份
        assertEquals("category-uuid",
                ApiDtos.budgetCategoryRef(3, "category-uuid"));
    }
}
