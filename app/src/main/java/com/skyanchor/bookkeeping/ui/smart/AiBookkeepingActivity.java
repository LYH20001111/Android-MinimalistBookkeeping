package com.skyanchor.bookkeeping.ui.smart;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

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
 * 语音输入为 P1，预留同一解析管线。
 */
public class AiBookkeepingActivity extends AppCompatActivity {

    private ActivityAiBookkeepingBinding binding;
    private AiBookkeepingViewModel viewModel;

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

        viewModel.getStatus().observe(this, this::renderStatus);
        viewModel.getError().observe(this, this::showError);

        // 分类快照：解析需要把分类名对齐到真实 categoryId，取自 LiveData 最新值。
        BookkeepingRepository repository = BookkeepingApp.get(this).getRepository();
        repository.observeAllCategories().observe(this, list ->
                categories = list == null ? new ArrayList<>() : list);
    }

    private void parse() {
        viewModel.parse(binding.aiInput.getText() == null
                ? "" : binding.aiInput.getText().toString(), categories);
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
