package com.skyanchor.bookkeeping.ui.auth;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.skyanchor.bookkeeping.R;
import com.skyanchor.bookkeeping.data.remote.ApiException;
import com.skyanchor.bookkeeping.databinding.ActivityRegisterBinding;
import com.skyanchor.bookkeeping.util.Callback;
import com.skyanchor.bookkeeping.util.InsetsUtil;

/**
 * 注册页（基线第 33 章）：邮箱 + 密码 + 确认密码。
 * 注册成功后展示「验证邮件已发送」，提供重发验证邮件与返回登录（基线 5.1）。
 */
public class RegisterActivity extends AppCompatActivity {

    private ActivityRegisterBinding binding;
    private AuthViewModel viewModel;
    private String registeredEmail;

    public static Intent newIntent(@NonNull android.content.Context context) {
        return new Intent(context, RegisterActivity.class);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityRegisterBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        InsetsUtil.applyTopAndHorizontalPadding(binding.registerRoot);
        InsetsUtil.syncSystemBarAppearance(this);

        viewModel = new ViewModelProvider(this).get(AuthViewModel.class);

        binding.registerButton.setOnClickListener(v -> attemptRegister());
        binding.resendButton.setOnClickListener(v -> resendVerification());
        binding.backToLoginButton.setOnClickListener(v -> finish());

        observe();
    }

    private void observe() {
        viewModel.busy().observe(this, busy -> {
            boolean running = Boolean.TRUE.equals(busy);
            binding.registerButton.setEnabled(!running);
            binding.registerProgress.setVisibility(running ? View.VISIBLE : View.GONE);
        });
    }

    private void attemptRegister() {
        String email = text(binding.emailInput);
        String password = text(binding.passwordInput);
        String confirm = text(binding.confirmPasswordInput);
        if (email.isEmpty() || password.isEmpty() || confirm.isEmpty()) {
            showError(getString(R.string.auth_error_empty));
            return;
        }
        if (!password.equals(confirm)) {
            showError(getString(R.string.auth_error_password_mismatch));
            return;
        }
        showError("");
        viewModel.register(email, password, new Callback<Boolean>() {
            @Override
            public void onResult(Boolean result) {
                registeredEmail = email;
                binding.registerForm.setVisibility(View.GONE);
                binding.verifyNotice.setVisibility(View.VISIBLE);
            }

            @Override
            public void onError(@NonNull Exception e) {
                showError(e.getMessage());
            }
        });
    }

    /** 错误提示：有内容即显示（控件默认 gone，直接 setText 不会出现）。 */
    private void showError(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            binding.registerError.setVisibility(View.GONE);
        } else {
            binding.registerError.setText(text);
            binding.registerError.setVisibility(View.VISIBLE);
        }
    }

    private void resendVerification() {
        if (registeredEmail == null) {
            return;
        }
        binding.resendButton.setEnabled(false);
        viewModel.resendVerification(registeredEmail, new Callback<Boolean>() {
            @Override
            public void onResult(Boolean result) {
                binding.resendButton.setEnabled(true);
                binding.verifyResendHint.setText(R.string.auth_verify_resent);
            }

            @Override
            public void onError(@NonNull Exception e) {
                binding.resendButton.setEnabled(true);
                if (!(e instanceof ApiException)) {
                    binding.verifyResendHint.setText(e.getMessage());
                }
            }
        });
    }

    private static String text(@Nullable com.google.android.material.textfield.TextInputEditText input) {
        return input == null || input.getText() == null ? "" : input.getText().toString().trim();
    }

    @Override
    protected void onDestroy() {
        binding = null;
        super.onDestroy();
    }
}
