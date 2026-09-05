package com.skyanchor.bookkeeping.ui.auth;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.skyanchor.bookkeeping.databinding.ActivityLoginBinding;
import com.skyanchor.bookkeeping.util.InsetsUtil;

/**
 * 登录页（基线第 33 章）：邮箱 + 密码；入口到注册页。
 * 登录失败提示用户可理解的文案，不泄露账号是否存在。
 */
public class LoginActivity extends AppCompatActivity {

    private ActivityLoginBinding binding;
    private AuthViewModel viewModel;

    public static Intent newIntent(@NonNull android.content.Context context) {
        return new Intent(context, LoginActivity.class);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityLoginBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        InsetsUtil.applyTopAndHorizontalPadding(binding.loginRoot);
        InsetsUtil.syncSystemBarAppearance(this);

        viewModel = new ViewModelProvider(this).get(AuthViewModel.class);
        if (viewModel.isLoggedIn()) {
            finishWithOk();
            return;
        }

        binding.loginButton.setOnClickListener(v -> attemptLogin());
        binding.goRegisterButton.setOnClickListener(v ->
                startActivity(RegisterActivity.newIntent(this)));
        binding.forgotPasswordText.setOnClickListener(v -> showForgotHint());

        observe();
    }

    private void observe() {
        viewModel.busy().observe(this, busy -> {
            boolean running = Boolean.TRUE.equals(busy);
            binding.loginButton.setEnabled(!running);
            binding.loginProgress.setVisibility(running ? View.VISIBLE : View.GONE);
        });
    }

    private void attemptLogin() {
        String email = binding.emailInput.getText() == null
                ? "" : binding.emailInput.getText().toString().trim();
        String password = binding.passwordInput.getText() == null
                ? "" : binding.passwordInput.getText().toString();
        if (email.isEmpty() || password.isEmpty()) {
            showError(getString(com.skyanchor.bookkeeping.R.string.auth_error_empty));
            return;
        }
        showError("");
        viewModel.login(email, password,
                new com.skyanchor.bookkeeping.util.Callback<com.skyanchor.bookkeeping.data.remote.ApiDtos.AuthResponse>() {
                    @Override
                    public void onResult(com.skyanchor.bookkeeping.data.remote.ApiDtos.AuthResponse response) {
                        finishWithOk();
                    }

                    @Override
                    public void onError(@NonNull Exception e) {
                        showError(e.getMessage());
                    }
                });
    }

    private void finishWithOk() {
        setResult(RESULT_OK);
        finish();
    }

    /** 错误提示：有内容即显示（控件默认 gone，直接 setText 不会出现）。 */
    private void showError(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            binding.loginError.setVisibility(View.GONE);
        } else {
            binding.loginError.setText(text);
            binding.loginError.setVisibility(View.VISIBLE);
        }
    }

    private void showForgotHint() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(com.skyanchor.bookkeeping.R.string.auth_forgot_title)
                .setMessage(com.skyanchor.bookkeeping.R.string.auth_forgot_message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    @Override
    protected void onDestroy() {
        binding = null;
        super.onDestroy();
    }
}
