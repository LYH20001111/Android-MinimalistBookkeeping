package com.skyanchor.bookkeeping.ui.record;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.NumberPicker;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;
import androidx.recyclerview.widget.GridLayoutManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.skyanchor.bookkeeping.BookkeepingApp;
import com.skyanchor.bookkeeping.R;
import com.skyanchor.bookkeeping.data.model.DailySummary;
import com.skyanchor.bookkeeping.data.model.DateRange;
import com.skyanchor.bookkeeping.data.repository.BookkeepingRepository;
import com.skyanchor.bookkeeping.databinding.DialogCalendarSummaryBinding;
import com.skyanchor.bookkeeping.util.DateLabels;
import com.skyanchor.bookkeeping.util.DateUtil;

import java.util.List;

/**
 * 带每日收支摘要的日历选择弹窗（V1.1 目标 A）。
 *
 * <p>替代原生 {@code MaterialDatePicker}：后者无法在日期格下方渲染金额摘要。这里用
 * {@link GridLayoutManager} 7 列网格 + {@link CalendarDayAdapter} 呈现整月，并对当月
 * 每一天显示支出/收入合计（无流水不显示金额）。
 *
 * <p>数据只查询「当前展示月」的聚合摘要（{@code observeDailySummaries}），不加载全量明细；
 * 切换月份时通过 {@link Transformations#switchMap} 重新订阅，天然避免旧月份数据残留。
 *
 * <p>选中结果通过 FragmentResult API 回传给宿主（{@link RecordFragment}）：
 * {@code setFragmentResult(REQUEST_DATE_SELECTED, bundle(dayMillis))}。V2 Risk B：
 * 相比匿名回调接口，FragmentResult 由 FragmentManager 托管，旋转/进程重建后不会丢失。
 */
public class CalendarSummaryDialog extends DialogFragment {

    private static final String ARG_SELECTED_DATE = "arg_selected_date";

    /** FragmentResult 的请求键，宿主用同一键监听。 */
    public static final String REQUEST_DATE_SELECTED = "calendar_date_selected";

    /** FragmentResult Bundle 中存放选中日期（当天 00:00 millis）的键。 */
    public static final String RESULT_KEY_DAY_MILLIS = "day_millis";

    /** 日历网格列数：一周七天，周一为首列。 */
    private static final int SPAN_COUNT = 7;

    /** 弹窗宽度占屏幕宽度的比例，保证每格有足够空间显示金额。 */
    private static final float DIALOG_WIDTH_RATIO = 0.92f;

    /** 日期确认回调。 */
    public interface OnDateSelectedListener {
        /** @param dayMillis 选中日期当天 00:00 的 epoch millis */
        void onDateSelected(long dayMillis);
    }

    private DialogCalendarSummaryBinding binding;
    private CalendarDayAdapter adapter;

    /** 当前选中日期（当天 00:00 millis）。 */
    private MutableLiveData<Long> selectedDate;

    /** 当前展示月份的锚点（该月任意一天 millis），驱动摘要查询与月份标题。 */
    private MutableLiveData<Long> visibleMonthAnchor;

    /** 当前展示月的每日摘要缓存；月份切换后立即失效，等待新查询回填。 */
    @Nullable
    private List<DailySummary> latestSummaries;

    public static CalendarSummaryDialog newInstance(long selectedDate) {
        CalendarSummaryDialog dialog = new CalendarSummaryDialog();
        Bundle args = new Bundle();
        args.putLong(ARG_SELECTED_DATE, selectedDate);
        dialog.setArguments(args);
        return dialog;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        long initial = getArguments() != null
                ? getArguments().getLong(ARG_SELECTED_DATE, DateUtil.today())
                : DateUtil.today();
        initial = DateUtil.startOfDay(initial);
        selectedDate = new MutableLiveData<>(initial);
        visibleMonthAnchor = new MutableLiveData<>(initial);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = DialogCalendarSummaryBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        adapter = new CalendarDayAdapter(this::onDayClick);
        binding.calendarGrid.setLayoutManager(new GridLayoutManager(requireContext(), SPAN_COUNT));
        binding.calendarGrid.setAdapter(adapter);

        binding.monthTitle.setOnClickListener(v -> showYearMonthPicker());
        binding.prevMonthButton.setOnClickListener(v -> shiftMonth(-1));
        binding.nextMonthButton.setOnClickListener(v -> shiftMonth(1));
        binding.confirmButton.setOnClickListener(v -> onConfirm());

        BookkeepingRepository repository = BookkeepingApp.get(requireContext()).getRepository();
        LiveData<List<DailySummary>> summaries =
                Transformations.switchMap(visibleMonthAnchor, anchor -> {
                    DateRange range = DateUtil.ofMonthOf(anchor);
                    return repository.observeDailySummaries(range.start, range.end);
                });

        visibleMonthAnchor.observe(getViewLifecycleOwner(), anchor -> {
            if (anchor == null) {
                return;
            }
            binding.monthTitle.setText(DateLabels.monthTitle(requireContext(),
                    DateUtil.yearOf(anchor), DateUtil.monthOf(anchor)));
            // 旧月份的摘要与新月份不匹配，先清空避免错位显示，待新查询回填。
            latestSummaries = null;
            renderGrid();
        });

        summaries.observe(getViewLifecycleOwner(), list -> {
            latestSummaries = list;
            renderGrid();
        });

        selectedDate.observe(getViewLifecycleOwner(), day -> {
            if (day != null) {
                adapter.setSelectedDate(day);
            }
        });
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
            params.width = (int) (screenWidth * DIALOG_WIDTH_RATIO);
            window.setAttributes(params);
        }
    }

    @Override
    public void onDestroyView() {
        binding.calendarGrid.setAdapter(null);
        binding = null;
        super.onDestroyView();
    }

    /** 用当前展示月份、最新摘要与选中日期重建整月网格。 */
    private void renderGrid() {
        Long anchor = visibleMonthAnchor.getValue();
        Long selected = selectedDate.getValue();
        if (anchor == null || selected == null) {
            return;
        }
        adapter.submitMonth(DateUtil.yearOf(anchor), DateUtil.monthOf(anchor),
                latestSummaries, selected);
    }

    private void onDayClick(long dayMillis) {
        selectedDate.setValue(dayMillis);
        // 点击相邻月份的补齐格时跟随跳转到该月，保证摘要与选中态一致。
        Long anchor = visibleMonthAnchor.getValue();
        if (anchor == null
                || DateUtil.yearOf(anchor) != DateUtil.yearOf(dayMillis)
                || DateUtil.monthOf(anchor) != DateUtil.monthOf(dayMillis)) {
            visibleMonthAnchor.setValue(dayMillis);
        }
    }

    /** 弹出年月选择器，快速跳转。 */
    private void showYearMonthPicker() {
        Long anchor = visibleMonthAnchor.getValue();
        long base = anchor != null ? anchor : DateUtil.today();

        int currentYear  = DateUtil.yearOf(base);
        int currentMonth = DateUtil.monthOf(base); // 1-12

        // 用 Android 原生的 NumberPicker 拼一个年月选择对话框
        Context context = requireContext();
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.HORIZONTAL);
        container.setPadding(
                (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24, getResources().getDisplayMetrics()),
                (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16, getResources().getDisplayMetrics()),
                (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24, getResources().getDisplayMetrics()),
                (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16, getResources().getDisplayMetrics())
        );

        NumberPicker yearPicker = new NumberPicker(context);
        yearPicker.setMinValue(currentYear - 50);   // 支持往前 50 年
        yearPicker.setMaxValue(currentYear + 10);   // 支持往后 10 年
        yearPicker.setValue(currentYear);
        yearPicker.setWrapSelectorWheel(false);

        NumberPicker monthPicker = new NumberPicker(context);
        monthPicker.setMinValue(1);
        monthPicker.setMaxValue(12);
        monthPicker.setValue(currentMonth);
        monthPicker.setDisplayedValues(
                new String[]{"1月","2月","3月","4月","5月","6月","7月","8月","9月","10月","11月","12月"}
        );
        monthPicker.setWrapSelectorWheel(true);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(8, 0, 8, 0);
        container.addView(yearPicker, lp);
        container.addView(monthPicker, lp);

        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.calendar_select_year_month)  // 你补充一下这个字符串资源
                .setView(container)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_confirm, (dialog, which) -> {
                    int year  = yearPicker.getValue();
                    int month = monthPicker.getValue();
                    long firstDay = DateUtil.dayMillisOf(year, month, 1);
                    visibleMonthAnchor.setValue(firstDay);
                })
                .show();
    }

    /** 以当月 1 号为基准平移月份，规避 31 号 +1 月落到次月 3 号等边界问题。 */
    private void shiftMonth(int delta) {
        Long anchor = visibleMonthAnchor.getValue();
        long base = anchor != null ? anchor : DateUtil.today();
        long firstDay = DateUtil.dayMillisOf(DateUtil.yearOf(base), DateUtil.monthOf(base), 1);
        DateRange shifted = DateUtil.shift(DateUtil.ofMonthOf(firstDay), delta);
        visibleMonthAnchor.setValue(shifted.start);
    }

    /** 日期确认回调接口已移除，改用 FragmentResult（V2 Risk B）。 */
    private void onConfirm() {
        Long selected = selectedDate.getValue();
        if (selected != null) {
            Bundle result = new Bundle();
            result.putLong(RESULT_KEY_DAY_MILLIS, selected);
            // 弹窗挂在宿主的 childFragmentManager 上，结果回传到同一个 FragmentManager。
            getParentFragmentManager().setFragmentResult(REQUEST_DATE_SELECTED, result);
        }
        dismiss();
    }
}
