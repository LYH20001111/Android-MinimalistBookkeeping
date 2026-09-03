package com.skyanchor.bookkeeping.ui.chart;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.fragment.app.DialogFragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;

import com.skyanchor.bookkeeping.R;
import com.skyanchor.bookkeeping.data.model.PeriodOption;
import com.skyanchor.bookkeeping.data.model.PeriodType;
import com.skyanchor.bookkeeping.databinding.DialogPeriodPickerBinding;
import com.skyanchor.bookkeeping.util.DateUtil;

import java.util.List;

/**
 * 周/月/年周期选择弹窗（V1.1 目标 C）。
 *
 * <p>由图表页点击周期导航中间区域打开，直接跳到任意远期周期，替代「一直点下一期」的笨办法。
 * 选项数据来自宿主 {@link ChartViewModel}（与图表同源，不重复查库）；点击某周期即调用
 * {@link ChartViewModel#selectAnchorDate}，图表通过共享的 {@code uiState} 自动刷新，
 * 因此无需额外回调接口。
 *
 * <p>ViewModel 取自 {@code requireParentFragment()}，因为本弹窗由 {@code ChartFragment}
 * 的 {@code childFragmentManager} 展示，父子共享同一 ViewModelStore。
 */
public class PeriodPickerDialog extends DialogFragment {

    private static final String ARG_PERIOD_TYPE = "arg_period_type";
    private static final String ARG_CURRENT_ANCHOR = "arg_current_anchor";

    /** 周/月选项两列，年选项三列（年份文案短，可排更密）。 */
    private static final int SPAN_COUNT_WEEK_MONTH = 2;
    private static final int SPAN_COUNT_YEAR = 2; //原来是3，也改成2

    private static final float DIALOG_WIDTH_RATIO = 0.9f;
    private static final float DIALOG_HEIGHT_RATIO = 0.75f;

    private DialogPeriodPickerBinding binding;
    private ChartViewModel viewModel;
    private PeriodOptionAdapter adapter;

    private PeriodType periodType = PeriodType.MONTH;

    /** 当前所在周期首日 millis，用于高亮与打开时定位。 */
    private long selectedStart;

    public static PeriodPickerDialog newInstance(@NonNull PeriodType type, long currentAnchor) {
        PeriodPickerDialog dialog = new PeriodPickerDialog();
        Bundle args = new Bundle();
        args.putString(ARG_PERIOD_TYPE, type.name());
        args.putLong(ARG_CURRENT_ANCHOR, currentAnchor);
        dialog.setArguments(args);
        return dialog;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle args = getArguments();
        String typeName = args != null ? args.getString(ARG_PERIOD_TYPE) : null;
        if (typeName != null) {
            try {
                periodType = PeriodType.valueOf(typeName);
            } catch (IllegalArgumentException ignored) {
                periodType = PeriodType.MONTH;
            }
        }
        long anchor = args != null ? args.getLong(ARG_CURRENT_ANCHOR, DateUtil.today())
                : DateUtil.today();
        selectedStart = DateUtil.rangeOf(periodType, anchor).start;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = DialogPeriodPickerBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(requireParentFragment()).get(ChartViewModel.class);

        binding.dialogTitle.setText(titleResOf(periodType));
        int spanCount = periodType == PeriodType.YEAR ? SPAN_COUNT_YEAR : SPAN_COUNT_WEEK_MONTH;
        binding.periodGrid.setLayoutManager(new GridLayoutManager(requireContext(), spanCount));

        adapter = new PeriodOptionAdapter(selectedStart, this::onOptionClick);
        binding.periodGrid.setAdapter(adapter);

        // 添加间距装饰器，间距大小可从资源获取（例如 4dp）
        int spacing = getResources().getDimensionPixelSize(R.dimen.spacing_xs);
        binding.periodGrid.addItemDecoration(new GridSpacingItemDecoration(spanCount, spacing, true));

        viewModel.getPeriodOptions(periodType).observe(getViewLifecycleOwner(), this::onOptions);
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog == null) {
            return;
        }
        Window window = dialog.getWindow();
        if (window != null) {
            WindowManager.LayoutParams params = window.getAttributes();
            int screenWidth = getResources().getDisplayMetrics().widthPixels;
            int screenHeight = getResources().getDisplayMetrics().heightPixels;
            params.width = (int) (screenWidth * DIALOG_WIDTH_RATIO);
            params.height = (int) (screenHeight * DIALOG_HEIGHT_RATIO);
            window.setAttributes(params);
        }
    }

    @Override
    public void onDestroyView() {
        binding.periodGrid.setAdapter(null);
        binding = null;
        super.onDestroyView();
    }

    private void onOptions(@Nullable List<PeriodOption> options) {
        if (binding == null) {
            return;
        }
        // 提交完成后滚动到当前周期，保证打开即定位到用户所在周期。
        adapter.submitList(options, () -> {
            if (binding == null || options == null) {
                return;
            }
            int index = indexOfStart(options, selectedStart);
            if (index >= 0) {
                binding.periodGrid.scrollToPosition(index);
            }
        });
    }

    private void onOptionClick(@NonNull PeriodOption option) {
        viewModel.selectAnchorDate(option.start);
        dismiss();
    }

    private static int indexOfStart(@NonNull List<PeriodOption> options, long start) {
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).start == start) {
                return i;
            }
        }
        return -1;
    }

    @StringRes
    private static int titleResOf(@NonNull PeriodType type) {
        switch (type) {
            case WEEK:
                return R.string.period_picker_title_week;
            case YEAR:
                return R.string.period_picker_title_year;
            case MONTH:
            default:
                return R.string.period_picker_title_month;
        }
    }
}
