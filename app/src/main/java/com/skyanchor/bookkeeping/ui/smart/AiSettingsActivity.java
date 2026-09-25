package com.skyanchor.bookkeeping.ui.smart;

import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.skyanchor.bookkeeping.BookkeepingApp;
import com.skyanchor.bookkeeping.R;
import com.skyanchor.bookkeeping.ai.AiConfigStore;
import com.skyanchor.bookkeeping.databinding.ActivityAiSettingsBinding;
import com.skyanchor.bookkeeping.util.InsetsUtil;

/**
 * AI 服务设置页（V5）：OpenAI 兼容接口的地址 / 密钥 / 模型名。
 * 全部可空——未配置时智能记账使用内置离线识别，手动记账完全不受影响。
 */
public class AiSettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityAiSettingsBinding binding =
                ActivityAiSettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        InsetsUtil.applyTopAndHorizontalPadding(binding.settingsRoot);
        InsetsUtil.syncSystemBarAppearance(this);

        AiConfigStore configStore = BookkeepingApp.get(this).getAiRepository().getConfigStore();

        binding.toolbar.setNavigationOnClickListener(v -> finish());
        binding.baseUrlInput.setText(configStore.getBaseUrl());
        binding.baseUrlInput.setHint(getString(R.string.ai_settings_base_url_hint));
        binding.apiKeyInput.setText(configStore.getApiKey());
        binding.modelInput.setText(configStore.getModel());
        binding.modelInput.setHint(getString(R.string.ai_settings_model_hint));

        binding.saveButton.setOnClickListener(v -> {
            String baseUrl = textOf(binding.baseUrlInput.getText());
            String apiKey = textOf(binding.apiKeyInput.getText());
            String model = textOf(binding.modelInput.getText());
            if (!baseUrl.isEmpty() && !baseUrl.startsWith("http://")
                    && !baseUrl.startsWith("https://")) {
                binding.baseUrlLayout.setError(getString(R.string.ai_settings_error_base_url));
                return;
            }
            if (baseUrl.isEmpty() && !apiKey.isEmpty()
                    || !baseUrl.isEmpty() && apiKey.isEmpty()) {
                // 地址与密钥要么都留空（纯离线），要么成对出现。
                binding.apiKeyLayout.setError(getString(R.string.ai_settings_error_api_key));
                return;
            }
            binding.baseUrlLayout.setError(null);
            binding.apiKeyLayout.setError(null);
            configStore.setBaseUrl(baseUrl);
            configStore.setApiKey(apiKey);
            configStore.setModel(model);
            Toast.makeText(this, R.string.ai_settings_saved, Toast.LENGTH_SHORT).show();
            finish();
        });
    }

    @NonNull
    private static String textOf(@Nullable CharSequence text) {
        return text == null ? "" : text.toString().trim();
    }
}
