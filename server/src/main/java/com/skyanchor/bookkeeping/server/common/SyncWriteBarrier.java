package com.skyanchor.bookkeeping.server.common;

import org.springframework.stereotype.Component;

import java.util.function.Supplier;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 同步写屏障：服务器恢复（清库重建）期间必须独占，普通读写（Push/Pull/
 * 备份导出/注销账号）共享。锁在控制器层、事务边界之外获取，
 * 保证事务的提交/回滚全部发生在持锁区间内（避免提交落在恢复窗口里）。
 */
@Component
public class SyncWriteBarrier {

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public <T> T read(Supplier<T> action) {
        lock.readLock().lock();
        try {
            return action.get();
        } finally {
            lock.readLock().unlock();
        }
    }

    public <T> T write(Supplier<T> action) {
        lock.writeLock().lock();
        try {
            return action.get();
        } finally {
            lock.writeLock().unlock();
        }
    }
}
