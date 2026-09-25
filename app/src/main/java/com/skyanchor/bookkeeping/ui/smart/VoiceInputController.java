package com.skyanchor.bookkeeping.ui.smart;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.skyanchor.bookkeeping.R;

import java.util.ArrayList;

/**
 * 语音听写控制器（V5 AI 智能记账）：封装系统语音识别（SpeechRecognizer），
 * 把用户说的话以「实时部分结果 + 最终结果」回给页面填入输入框。
 * 只负责「语音 → 文字」，文字仍走同一 AI 解析管线。
 *
 * <p>SpeechRecognizer 要求在主线程创建与调用；页面 onDestroy 前必须调 {@link #destroy()}。
 * 设备没有语音识别服务时 {@link #isRecognitionAvailable()} 为 false，页面应隐藏麦克风。
 * 每次会话都重建识别器——部分厂商引擎在出错后实例不可复用。
 */
public class VoiceInputController implements RecognitionListener {

    /** 页面侧回调：所有回调都在主线程。 */
    public interface Listener {
        /** 开始聆听（已拿到权限、识别器已启动）。 */
        void onVoiceListeningStarted();

        /** 本次聆听结束（正常结束、出错或页面主动停止）。 */
        void onVoiceListeningEnded();

        /** 边说边出的中间结果（可能为空串，页面按增量拼到已有文字后面）。 */
        void onVoicePartial(@NonNull String dictated);

        /** 本次说话的最终识别结果。 */
        void onVoiceFinal(@NonNull String dictated);

        /** 识别失败，参数为可直接展示的友好文案资源。 */
        void onVoiceError(int messageRes);
    }

    @NonNull
    private final Context context;
    @NonNull
    private final Listener listener;

    @Nullable
    private SpeechRecognizer recognizer;
    private boolean listening;

    public VoiceInputController(@NonNull Context context, @NonNull Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    /** 设备是否提供系统语音识别服务（无则隐藏麦克风，降级为手输）。 */
    public boolean isRecognitionAvailable() {
        return SpeechRecognizer.isRecognitionAvailable(context);
    }

    public boolean isListening() {
        return listening;
    }

    public boolean hasMicPermission() {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** 开始一次听写会话；未在监听且权限齐备时才生效。 */
    public void startListening() {
        if (listening || !isRecognitionAvailable() || !hasMicPermission()) {
            return;
        }
        if (recognizer != null) {
            // 先摘掉旧监听再销毁，避免销毁过程触发 ERROR 回调打扰本次会话
            recognizer.setRecognitionListener(null);
            recognizer.destroy();
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(context);
        recognizer.setRecognitionListener(this);

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN");
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);

        listening = true;
        listener.onVoiceListeningStarted();
        recognizer.startListening(intent);
    }

    /** 结束本次聆听；引擎随后回吐的最终结果仍会经 onVoiceFinal 提交。 */
    public void stopListening() {
        if (!listening) {
            return;
        }
        listening = false;
        if (recognizer != null) {
            recognizer.stopListening();
        }
        listener.onVoiceListeningEnded();
    }

    /** 释放识别器（页面 onDestroy / onStop）。 */
    public void destroy() {
        if (recognizer != null) {
            recognizer.setRecognitionListener(null);
            recognizer.destroy();
            recognizer = null;
        }
        listening = false;
    }

    @Override
    public void onReadyForSpeech(@Nullable Bundle params) {
    }

    @Override
    public void onBeginningOfSpeech() {
    }

    @Override
    public void onRmsChanged(float rmsdB) {
    }

    @Override
    public void onBufferReceived(@Nullable byte[] buffer) {
    }

    @Override
    public void onEndOfSpeech() {
    }

    @Override
    public void onPartialResults(@Nullable Bundle partialResults) {
        String text = firstResult(partialResults);
        if (!TextUtils.isEmpty(text)) {
            listener.onVoicePartial(text.trim());
        }
    }

    @Override
    public void onResults(@Nullable Bundle results) {
        listening = false;
        listener.onVoiceListeningEnded();
        String text = firstResult(results);
        if (TextUtils.isEmpty(text)) {
            listener.onVoiceError(R.string.ai_voice_no_match);
        } else {
            listener.onVoiceFinal(text.trim());
        }
    }

    @Override
    public void onError(int error) {
        listening = false;
        listener.onVoiceListeningEnded();
        listener.onVoiceError(mapError(error));
    }

    @Override
    public void onEvent(int eventType, @Nullable Bundle params) {
    }

    @Nullable
    private String firstResult(@Nullable Bundle bundle) {
        if (bundle == null) {
            return null;
        }
        ArrayList<String> results =
                bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return results == null || results.isEmpty() ? null : results.get(0);
    }

    /** 统一映射成页面风格的友好文案（不出现技术术语）。 */
    private int mapError(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_NO_MATCH:
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return R.string.ai_voice_no_match;
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                return R.string.ai_voice_error_network;
            case SpeechRecognizer.ERROR_AUDIO:
                return R.string.ai_voice_error_audio;
            default: // ERROR_CLIENT / ERROR_RECOGNIZER_BUSY / ERROR_INSUFFICIENT_PERMISSIONS 等
                return R.string.ai_voice_error_generic;
        }
    }
}
