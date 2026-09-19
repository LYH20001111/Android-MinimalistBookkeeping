package com.skyanchor.bookkeeping.ui.record;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.skyanchor.bookkeeping.BookkeepingApp;
import com.skyanchor.bookkeeping.data.entity.RefundRecordEntity;
import com.skyanchor.bookkeeping.data.repository.BookkeepingRepository;
import com.skyanchor.bookkeeping.util.Callback;

import java.util.List;

/**
 * 退款记录页 ViewModel（V4.0）：观察一笔账单的退款流水，并代理「标记到账 / 撤销」两个写操作。
 *
 * <p>流水与页面上的两个累计都来自 {@code refund_record} 本身，账单行上的缓存列只用于
 * 列表角标；因此本页撤销或到账后无需重新读取账单即可刷新。
 */
public class RefundRecordViewModel extends AndroidViewModel {

    private final BookkeepingRepository repository;
    private final MutableLiveData<Long> originalAmount = new MutableLiveData<>();

    public RefundRecordViewModel(@NonNull Application application) {
        super(application);
        repository = BookkeepingApp.get(application).getRepository();
    }

    public LiveData<List<RefundRecordEntity>> observeRefunds(long transactionId) {
        return repository.observeRefunds(transactionId);
    }

    /** 账单原额，用于页首汇总；账单已被彻底清理时保持未赋值，界面隐藏汇总行。 */
    public LiveData<Long> originalAmount() {
        return originalAmount;
    }

    public void loadOriginalAmount(long transactionId) {
        repository.loadTransaction(transactionId, item -> {
            if (item != null) {
                originalAmount.setValue(item.amount);
            }
        });
    }

    public void markReceived(long refundId, @NonNull Callback<Boolean> callback) {
        repository.markRefundReceived(refundId, callback);
    }

    /** 修改一笔已有退款（金额 / 原因 / 到账状态），回调告知是否被受理。 */
    public void updateRefund(long refundId, long amountCents, @Nullable String reason,
                             boolean toReceived, @NonNull Callback<Boolean> callback) {
        repository.updateRefund(refundId, amountCents, reason, toReceived, callback);
    }

    public void cancel(long refundId, @NonNull Callback<Boolean> callback) {
        repository.cancelRefund(refundId, callback);
    }
}
