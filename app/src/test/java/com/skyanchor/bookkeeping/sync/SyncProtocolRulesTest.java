package com.skyanchor.bookkeeping.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.skyanchor.bookkeeping.data.entity.SyncEntityTypes;
import com.skyanchor.bookkeeping.data.remote.ApiDtos;

import org.junit.Test;

/**
 * 同步协议纯规则单测（Sync Protocol Version 1）：
 * 实体处理顺序（引用先于被引用）与预算总预算哨兵的协议映射。
 */
public class SyncProtocolRulesTest {

    @Test
    public void pushOrder_referencesBeforeReferenced() {
        // 分类 → 账户 → 交易 → 预算 → 周期（开发计划备注 2）
        assertEquals(0, SyncPayloadMapper.orderOf(SyncEntityTypes.CATEGORY));
        assertEquals(1, SyncPayloadMapper.orderOf(SyncEntityTypes.ACCOUNT));
        assertEquals(2, SyncPayloadMapper.orderOf(SyncEntityTypes.TRANSACTION));
        assertEquals(3, SyncPayloadMapper.orderOf(SyncEntityTypes.BUDGET));
        assertEquals(4, SyncPayloadMapper.orderOf(SyncEntityTypes.RECURRING));
        // 未知类型兜底排最后
        assertEquals(5, SyncPayloadMapper.orderOf("UNKNOWN"));
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
