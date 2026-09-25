package com.skyanchor.bookkeeping.ui.smart;

import android.app.Application;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;

import com.skyanchor.bookkeeping.BookkeepingApp;
import com.skyanchor.bookkeeping.ai.AiException;
import com.skyanchor.bookkeeping.ai.AiRepository;
import com.skyanchor.bookkeeping.ai.TransactionDraft;
import com.skyanchor.bookkeeping.data.entity.CategoryEntity;
import com.skyanchor.bookkeeping.util.Callback;

import java.util.List;

/**
 * 扫描账单页 ViewModel（V5 基线第 29 章）：托管图片识别状态机，
 * 配置变更期间识别不中断、结果不丢失。
 */
public class ScanBillViewModel extends AndroidViewModel {

    public enum Status { IDLE, PROCESSING, SUCCESS, ERROR }

    private final AiRepository aiRepository;

    private final MutableLiveData<Status> status = new MutableLiveData<>(Status.IDLE);
    private final MutableLiveData<TransactionDraft> draft = new MutableLiveData<>();
    private final MutableLiveData<AiException> error = new MutableLiveData<>();

    /** 最近一次待识别的图片，供「重新尝试」复用。 */
    @Nullable
    private Uri lastImageUri;

    public ScanBillViewModel(@NonNull Application application) {
        super(application);
        this.aiRepository = BookkeepingApp.get(application).getAiRepository();
    }

    public MutableLiveData<Status> getStatus() {
        return status;
    }

    public MutableLiveData<TransactionDraft> getDraft() {
        return draft;
    }

    public MutableLiveData<AiException> getError() {
        return error;
    }

    /** 开始识别一张账单图片（拍照 / 相册均可）。 */
    public void process(@NonNull Uri imageUri, @NonNull List<CategoryEntity> categories) {
        lastImageUri = imageUri;
        status.setValue(Status.PROCESSING);
        aiRepository.recognizeBill(imageUri, categories, new Callback<TransactionDraft>() {
            @Override
            public void onResult(@Nullable TransactionDraft result) {
                draft.setValue(result);
                status.setValue(Status.SUCCESS);
            }

            @Override
            public void onError(@NonNull Exception e) {
                error.setValue(e instanceof AiException
                        ? (AiException) e
                        : new AiException(AiException.Kind.PARSE_FAILED, "识别失败", e));
                status.setValue(Status.ERROR);
            }
        });
    }

    /** 用上一次的图片重试（图片文件仍在缓存中时有效）。 */
    public void retry(@NonNull List<CategoryEntity> categories) {
        Uri imageUri = lastImageUri;
        if (imageUri != null) {
            process(imageUri, categories);
        }
    }

    /**
     * 导航去确认页时取走草稿并复位状态机，避免旋转屏幕后重复跳转。
     */
    @Nullable
    public TransactionDraft consumeDraft() {
        TransactionDraft value = draft.getValue();
        draft.setValue(null);
        status.setValue(Status.IDLE);
        return value;
    }

    /** 错误弹窗关闭后回到初始态，等待用户重新拍摄 / 选图。 */
    public void reset() {
        status.setValue(Status.IDLE);
    }

    /** 弹窗展示过后清掉错误，避免旋转屏幕重建时重复弹窗。 */
    public void consumeError() {
        error.setValue(null);
    }
}
