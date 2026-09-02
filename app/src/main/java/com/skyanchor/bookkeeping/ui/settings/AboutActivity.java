package com.skyanchor.bookkeeping.ui.settings;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.skyanchor.bookkeeping.BuildConfig;
import com.skyanchor.bookkeeping.R;
import com.skyanchor.bookkeeping.databinding.ActivityAboutBinding;
import com.skyanchor.bookkeeping.util.InsetsUtil;

/**
 * 关于 App（V1 基线第 9 章）。
 *
 * <p>版本号、产品闭环说明，以及用户协议与隐私说明两个入口。
 * 协议与隐私均为本地静态文本，不含任何外链跳转——V1 不申请网络权限。
 */
public class AboutActivity extends AppCompatActivity {

    private ActivityAboutBinding binding;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityAboutBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        InsetsUtil.applySystemBarsPadding(binding.aboutRoot);
        InsetsUtil.syncSystemBarAppearance(this);

        binding.toolbar.setNavigationOnClickListener(v -> finish());
        binding.aboutVersion.setText(
                getString(R.string.about_version_format, BuildConfig.VERSION_NAME));
        binding.aboutAgreement.setOnClickListener(v ->
                showText(R.string.about_agreement, R.string.about_agreement_content));
        binding.aboutPrivacy.setOnClickListener(v ->
                showText(R.string.about_privacy, R.string.about_privacy_content));
    }

    private void showText(@StringRes int titleRes, @StringRes int messageRes) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(titleRes)
                .setMessage(messageRes)
                .setPositiveButton(R.string.action_confirm, null)
                .show();
    }
}
