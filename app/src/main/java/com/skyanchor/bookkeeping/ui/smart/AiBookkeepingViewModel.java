package com.skyanchor.bookkeeping.ui.smart;

import android.app.Application;
import android.text.TextUtils;

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
 * AI 智能记账页 ViewModel（V5 基线第 29 章）：一句话描述 → 解析状态机。
 * 语音（P1）后续接入时，把 STT 结果并入 {@link #parse} 的入参即可复用整条管线。
 */
public class AiBookkeepingViewModel extends AndroidViewModel {

    public enum Status { IDLE, PARSING, SUCCESS, ERROR }

    private final AiRepository aiRepository;

    private final MutableLiveData<Status> status = new MutableLiveData<>(Status.IDLE);
    private final MutableLiveData<TransactionDraft> draft = new MutableLiveData<>();
    private final MutableLiveData<AiException> error = new MutableLiveData<>();

    public AiBookkeepingViewModel(@NonNull Application application) {
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

    /** 解析一句话描述。文本为空直接报错，不发起解析。 */
    public void parse(@Nullable String text, @NonNull List<CategoryEntity> categories) {
        if (TextUtils.isEmpty(text == null ? null : text.trim())) {
            error.setValue(new AiException(AiException.Kind.PARSE_FAILED, "输入为空"));
            status.setValue(Status.ERROR);
            return;
        }
        status.setValue(Status.PARSING);
        aiRepository.parseText(text.trim(), categories, new Callback<TransactionDraft>() {
            @Override
            public void onResult(@Nullable TransactionDraft result) {
                draft.setValue(result);
                status.setValue(Status.SUCCESS);
            }

            @Override
            public void onError(@NonNull Exception e) {
                error.setValue(e instanceof AiException
                        ? (AiException) e
                        : new AiException(AiException.Kind.PARSE_FAILED, "解析失败", e));
                status.setValue(Status.ERROR);
            }
        });
    }

    /** 导航去确认页时取走草稿并复位，避免旋转屏幕后重复跳转。 */
    @Nullable
    public TransactionDraft consumeDraft() {
        TransactionDraft value = draft.getValue();
        draft.setValue(null);
        status.setValue(Status.IDLE);
        return value;
    }

    /** 错误提示被用户看到后回到初始态。 */
    public void reset() {
        status.setValue(Status.IDLE);
    }
}
