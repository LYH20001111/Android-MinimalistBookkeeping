package com.skyanchor.bookkeeping.server.sync;

/**
 * LWW 冲突裁决（基线第 15、16 章，冻结策略的显式表达）：
 *
 * <p>1. {@code baseVersion == serverVersion}：无冲突，写入并 version+1。
 * <p>2. {@code baseVersion != serverVersion}：并发修改，进入冲突流程。
 *     最终裁决依据 = 服务器接收顺序（服务器时间是逻辑时钟，客户端系统时间
 *     与客户端 updated_at 一律不参与）。Push 由服务器串行处理，后接收的写入
 *     即「最后修改」，胜出并 version+1；被覆盖的服务器侧版本完整记入 conflict log。
 *     即「不允许旧版本静默覆盖」——覆盖必须显式经过裁决并留下审计记录。
 * <p>3. 同一毫秒内到达（理论边界）：以服务器已存 version 更高者胜（保守保护已有数据）。
 *
 * <p>DELETE 与 UPSERT 同一规则：后到服务器的删除胜出（软删传播），
 * 或后到的修改胜出（复活），均由 conflict log 审计。
 */
public final class Lww {

    public enum Decision {
        /** 无冲突，正常接受。 */
        ACCEPT,
        /** 冲突，但传入写入胜出（覆盖服务器当前版本）。 */
        CONFLICT_INCOMING_WINS,
        /** 冲突，服务器当前版本胜出（传入写入被拒绝）。 */
        CONFLICT_SERVER_WINS
    }

    private Lww() {
    }

    public static Decision resolve(long baseVersion, long serverVersion,
                                   long incomingReceivedAt, long serverStoredReceivedAt) {
        if (baseVersion == serverVersion) {
            return Decision.ACCEPT;
        }
        if (incomingReceivedAt > serverStoredReceivedAt) {
            return Decision.CONFLICT_INCOMING_WINS;
        }
        if (incomingReceivedAt < serverStoredReceivedAt) {
            return Decision.CONFLICT_SERVER_WINS;
        }
        return serverVersion > baseVersion
                ? Decision.CONFLICT_SERVER_WINS : Decision.CONFLICT_INCOMING_WINS;
    }
}
