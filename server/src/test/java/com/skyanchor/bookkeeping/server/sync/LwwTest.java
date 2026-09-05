package com.skyanchor.bookkeeping.server.sync;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * LWW 裁决规则（基线第 15、16 章）单元测试：
 * 接收序为最终依据，客户端时间不参与；baseVersion 相等即无冲突。
 */
class LwwTest {

    private static final long T1 = 1_000L;
    private static final long T2 = 2_000L;

    @Test
    void equalBaseVersion_isPlainAccept() {
        assertEquals(Lww.Decision.ACCEPT,
                Lww.resolve(5, 5, T2, T1));
    }

    @Test
    void staleBase_incomingLaterWins() {
        // 设备 B 基于旧版本修改，但其写入后于服务器当前版本到达 → B 胜出并覆盖
        assertEquals(Lww.Decision.CONFLICT_INCOMING_WINS,
                Lww.resolve(5, 6, T2, T1));
    }

    @Test
    void sameInstant_serverVersionWins() {
        // 同一毫秒到达（同批次重复 syncId）：服务器已存版本更高 → 保守保留服务器数据
        assertEquals(Lww.Decision.CONFLICT_SERVER_WINS,
                Lww.resolve(5, 6, T1, T1));
        assertEquals(Lww.Decision.CONFLICT_INCOMING_WINS,
                Lww.resolve(6, 5, T1, T1));
    }

    @Test
    void incomingOlder_serverWins() {
        // 理论边界（服务器时钟回拨）：传入时间更早 → 服务器当前版本胜出
        assertEquals(Lww.Decision.CONFLICT_SERVER_WINS,
                Lww.resolve(5, 6, T1, T2));
    }
}
