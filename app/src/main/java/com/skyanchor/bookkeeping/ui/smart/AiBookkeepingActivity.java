package com.skyanchor.bookkeeping.ui.smart;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.textfield.TextInputLayout;
import com.skyanchor.bookkeeping.R;
import com.skyanchor.bookkeeping.ai.AiException;
import com.skyanchor.bookkeeping.ai.TransactionDraft;
import com.skyanchor.bookkeeping.data.entity.CategoryEntity;
import com.skyanchor.bookkeeping.data.repository.BookkeepingRepository;
import com.skyanchor.bookkeeping.BookkeepingApp;
import com.skyanchor.bookkeeping.databinding.ActivityAiBookkeepingBinding;
import com.skyanchor.bookkeeping.util.InsetsUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 智能记账页（V5）。用户用一句话描述消费 → 解析为账单草稿 → 确认页。
 *
 * <p>解析失败时页内给出提示与「手动填写」出路（基线第 35 章），
 * 不使用任何技术错误文案；重新识别直接再点「开始识别」即可。
 * 输入方式有手输与语音听写（输入框尾部麦克风，系统语音识别实时上屏）两种，
 * 识别出的文字走同一解析管线。
 */
public class AiBookkeepingActivity extends AppCompatActivity
        implements VoiceInputController.Listener {

    private ActivityAiBookkeepingBinding binding;
    private AiBookkeepingViewModel viewModel;
    private VoiceInputController voiceInput;

    /** 听写中麦克风的呼吸动画，结束时取消并恢复透明度。 */
    @Nullable
    private ObjectAnimator micPulse;

    /** 本次语音会话开始时输入框已有内容：识别文字增量拼在其后。 */
    private String voiceBaseText = "";

    /** 分类快照：解析需要把分类名对齐到真实 categoryId。 */
    @NonNull
    private List<CategoryEntity> categories = new ArrayList<>();

    /** 确认页结果：确认落库 OK 后本页也带 OK 关闭，逐级回到记录页。 */
    private final ActivityResultLauncher<Intent> confirmLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK) {
                    setResult(RESULT_OK);
                    finish();
                }
            });

    /** 麦克风权限：允许后自动开始听写，拒绝给友好提示。 */
    private final ActivityResultLauncher<String> micPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    voiceInput.startListening();
                } else {
                    Toast.makeText(this, R.string.ai_voice_no_permission,
                            Toast.LENGTH_SHORT).show();
                }
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityAiBookkeepingBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        InsetsUtil.applyTopAndHorizontalPadding(binding.aiRoot);
        InsetsUtil.applyImeBottomPadding(binding.aiScroll);
        InsetsUtil.syncSystemBarAppearance(this);

        viewModel = new ViewModelProvider(this).get(AiBookkeepingViewModel.class);

        binding.toolbar.setNavigationOnClickListener(v -> finish());
        binding.example1.setOnClickListener(v ->
                binding.aiInput.setText(getString(R.string.ai_example_1)));
        binding.example2.setOnClickListener(v ->
                binding.aiInput.setText(getString(R.string.ai_example_2)));
        binding.example3.setOnClickListener(v ->
                binding.aiInput.setText(getString(R.string.ai_example_3)));
        binding.parseButton.setOnClickListener(v -> parse());
        binding.aiFillManuallyButton.setOnClickListener(v -> finish());

        voiceInput = new VoiceInputController(this, this);
        if (voiceInput.isRecognitionAvailable()) {
            binding.aiInputLayout.setEndIconOnClickListener(v -> onMicClick());
        } else {
            // 设备没有语音识别服务：隐藏麦克风，自然降级为手输
            binding.aiInputLayout.setEndIconVisible(false);
        }

        viewModel.getStatus().observe(this, this::renderStatus);
        viewModel.getError().observe(this, this::showError);

        // 分类快照：解析需要把分类名对齐到真实 categoryId，取自 LiveData 最新值。
        BookkeepingRepository repository = BookkeepingApp.get(this).getRepository();
        repository.observeAllCategories().observe(this, list ->
                categories = list == null ? new ArrayList<>() : list);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // 离开页面即停止聆听并释放识别器，回到页面按需重建
        voiceInput.stopListening();
        voiceInput.destroy();
    }

    @Override
    protected void onDestroy() {
        voiceInput.destroy();
        super.onDestroy();
    }

    private void onMicClick() {
        if (voiceInput.isListening()) {
            voiceInput.stopListening();
            return;
        }
        if (!voiceInput.hasMicPermission()) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
            return;
        }
        voiceInput.startListening();
    }

    // —— VoiceInputController.Listener：语音 → 文字 → 输入框 ——

    @Override
    public void onVoiceListeningStarted() {
        voiceBaseText = currentInputText();
        renderVoiceUi(true);
    }

    @Override
    public void onVoiceListeningEnded() {
        renderVoiceUi(false);
    }

    @Override
    public void onVoicePartial(@NonNull String dictated) {
        applyDictatedText(dictated);
    }

    @Override
    public void onVoiceFinal(@NonNull String dictated) {
        applyDictatedText(dictated);
    }

    @Override
    public void onVoiceError(int messageRes) {
        Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).show();
    }

    /** 识别文字拼在会话开始前的内容后面（多段听写以空格分隔），光标移到末尾。 */
    private void applyDictatedText(@NonNull String dictated) {
        StringBuilder text = new StringBuilder(voiceBaseText);
        if (text.length() > 0 && dictated.length() > 0) {
            text.append(' ');
        }
        text.append(dictated);
        binding.aiInput.setText(text.toString());
        binding.aiInput.setSelection(text.length());
    }

    private void renderVoiceUi(boolean listening) {
        binding.aiInputLayout.setHelperTextEnabled(listening);
        binding.aiInputLayout.setHelperText(
                listening ? getString(R.string.ai_voice_listening) : null);
        binding.aiInputLayout.setEndIconTintList(ColorStateList.valueOf(getColor(
                listening ? R.color.primary : R.color.text_secondary)));
        if (listening) {
            startMicPulse();
        } else {
            stopMicPulse();
        }
    }

    private void startMicPulse() {
        stopMicPulse();
        View endIcon = endIconView();
        if (endIcon == null) {
            return;
        }
        micPulse = ObjectAnimator.ofFloat(endIcon, View.ALPHA, 1f, 0.3f);
        micPulse.setDuration(700);
        micPulse.setRepeatCount(ValueAnimator.INFINITE);
        micPulse.setRepeatMode(ValueAnimator.REVERSE);
        micPulse.start();
    }

    private void stopMicPulse() {
        if (micPulse != null) {
            micPulse.cancel();
            micPulse = null;
        }
        View endIcon = endIconView();
        if (endIcon != null) {
            endIcon.setAlpha(1f);
        }
    }

    /** 输入框尾部图标视图（Material 内部布局），供听写呼吸动画使用。 */
    @Nullable
    private View endIconView() {
        return binding.aiInputLayout.findViewById(
                com.google.android.material.R.id.text_input_end_icon);
    }

    private String currentInputText() {
        return binding.aiInput.getText() == null
                ? "" : binding.aiInput.getText().toString();
    }

    private void parse() {
        // 听写未结束时点识别：先停聆听再解析，避免识别结果与解析互相打断
        voiceInput.stopListening();
        viewModel.parse(currentInputText(), categories);
    }

    private void renderStatus(@Nullable AiBookkeepingViewModel.Status status) {
        boolean parsing = status == AiBookkeepingViewModel.Status.PARSING;
        binding.parseButton.setEnabled(!parsing);
        binding.parseButton.setText(parsing ? R.string.ai_parsing : R.string.ai_parse_button);
        binding.aiErrorText.setVisibility(
                status == AiBookkeepingViewModel.Status.ERROR ? View.VISIBLE : View.GONE);
        binding.aiFillManuallyButton.setVisibility(
                status == AiBookkeepingViewModel.Status.ERROR ? View.VISIBLE : View.GONE);

        if (status == AiBookkeepingViewModel.Status.SUCCESS) {
            TransactionDraft draft = viewModel.consumeDraft();
            if (draft != null) {
                confirmLauncher.launch(ConfirmBillActivity.newIntent(this, draft));
            }
        }
    }

    private void showError(@Nullable AiException error) {
        if (error == null) {
            return;
        }
        int message = error.kind == AiException.Kind.NETWORK
                ? R.string.ai_error_network : R.string.ai_error_parse;
        binding.aiErrorText.setText(message);
    }
}
