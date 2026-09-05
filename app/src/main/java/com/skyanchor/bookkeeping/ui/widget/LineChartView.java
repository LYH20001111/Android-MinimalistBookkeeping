package com.skyanchor.bookkeeping.ui.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.skyanchor.bookkeeping.R;
import com.skyanchor.bookkeeping.data.model.TrendPoint;
import com.skyanchor.bookkeeping.util.AmountUtil;
import com.skyanchor.bookkeeping.util.ChartAxisLabels;
import com.skyanchor.bookkeeping.util.CategoryColors;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 支出趋势折线图，纯 Canvas 自绘，不引入任何第三方图表库。
 *
 * <p>绘制内容：4 条横向网格线 + Y 轴金额刻度（{@link AmountUtil#abbreviate}）+ X 轴标签
 * + 主色折线与 12%→0% 渐变填充 + 数据点 + 最高点数值气泡；触摸时改为显示竖直辅助线与对应点数值。
 *
 * <p>V2.1 Phase 2（基线第 7–8 章）：数据点与 X 轴标签解耦——数据点永远完整，标签由
 * {@link ChartAxisLabels} 按绘图区宽度自适应降采样，首末标签分别按绘图区左右缘对齐，
 * 绘图区另设左右安全边距，保证首末数据点与标签都不贴边、不裁切。宽度不足时优先减少
 * 标签而不是缩小字体；「最后一天」标签强制显示。
 *
 * <p>金额只参与「取值 → 刻度」的整数运算，float 仅用于像素坐标，不做任何金额加减。
 * 全 0 数据时只画基线与提示文案，绝不崩溃或留白。
 */
public class LineChartView extends View {

    /** 月视图 X 轴首选标签位置：1 / 5 / 10 / 15 / 20 / 25 日，末日另外强制补上。 */
    private static final int[] MONTH_LABEL_OFFSETS = {0, 4, 9, 14, 19, 24};

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint guidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubbleTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint emptyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path linePath = new Path();
    private final Path fillPath = new Path();
    private final RectF chartRect = new RectF();
    private final RectF bubbleRect = new RectF();

    private final int gridLines;
    private final int denseLabelLimit;
    private final int fillAlpha;
    private final int animDuration;
    private final float lineStroke;
    private final float gridStroke;
    private final float dotRadius;
    private final float labelGap;
    private final float bubbleRadius;
    /** V2.1：绘图区左右安全边距（首末数据点不贴容器边缘）。 */
    private final float edgePadding;
    /** V2.1：相邻 X 轴标签的最小像素间距，不足时降采样而不是缩小字体。 */
    private final float minLabelGap;

    @NonNull
    private List<TrendPoint> points = Collections.emptyList();

    @NonNull
    private int[] labelIndices = new int[0];

    /** 标签缓存的键：数据点数量 + 绘图区宽度，任一变化即重算（setData 也会主动失效）。 */
    private int labelKeyCount = -1;
    private float labelKeyWidth = -1f;

    @NonNull
    private String emptyText = "";

    /** Y 轴单格刻度（分）与轴顶值（分），全部为整数。 */
    private long axisStep = 100L;
    private long axisMax = 300L;

    private int maxValueIndex = -1;
    private int todayIndex = -1;
    private int hoverIndex = -1;
    private float animProgress = 1f;

    @Nullable
    private ValueAnimator animator;

    @Nullable
    private LinearGradient fillShader;
    private float shaderTop = Float.NaN;
    private float shaderBottom = Float.NaN;

    public LineChartView(@NonNull Context context) {
        this(context, null);
    }

    public LineChartView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public LineChartView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        Resources resources = context.getResources();
        gridLines = Math.max(2, resources.getInteger(R.integer.chart_grid_lines));
        denseLabelLimit = Math.max(2, resources.getInteger(R.integer.chart_dense_label_limit));
        fillAlpha = resources.getInteger(R.integer.chart_fill_alpha);
        animDuration = resources.getInteger(R.integer.chart_anim_duration);
        lineStroke = resources.getDimension(R.dimen.chart_stroke_width);
        gridStroke = resources.getDimension(R.dimen.stroke_width);
        dotRadius = resources.getDimension(R.dimen.chart_dot_radius);
        labelGap = resources.getDimension(R.dimen.spacing_xs);
        bubbleRadius = resources.getDimension(R.dimen.radius_sm);
        edgePadding = resources.getDimension(R.dimen.chart_edge_padding);
        minLabelGap = resources.getDimension(R.dimen.chart_label_min_gap);

        int primary = ContextCompat.getColor(context, R.color.primary);
        int divider = ContextCompat.getColor(context, R.color.divider);
        int tertiary = ContextCompat.getColor(context, R.color.text_tertiary);

        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(gridStroke);
        gridPaint.setColor(divider);

        axisPaint.setColor(tertiary);
        axisPaint.setTextSize(resources.getDimension(R.dimen.font_caption));

        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(lineStroke);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setColor(primary);

        fillPaint.setStyle(Paint.Style.FILL);

        dotPaint.setStyle(Paint.Style.FILL);
        dotPaint.setColor(primary);

        guidePaint.setStyle(Paint.Style.STROKE);
        guidePaint.setStrokeWidth(gridStroke);
        guidePaint.setColor(tertiary);

        bubblePaint.setStyle(Paint.Style.FILL);
        bubblePaint.setColor(primary);

        bubbleTextPaint.setColor(ContextCompat.getColor(context, R.color.text_on_primary));
        bubbleTextPaint.setTextSize(resources.getDimension(R.dimen.font_caption));
        bubbleTextPaint.setTextAlign(Paint.Align.CENTER);
        bubbleTextPaint.setFakeBoldText(true);

        emptyPaint.setColor(tertiary);
        emptyPaint.setTextSize(resources.getDimension(R.dimen.font_secondary));
        emptyPaint.setTextAlign(Paint.Align.CENTER);
    }

    /**
     * 设置趋势数据，会重算 Y 轴刻度并播放一次 250ms 的生长动画。
     *
     * @param defaultIndex 默认显示数值气泡的数据点索引（通常为「今天」），
     *                     -1 表示不指定、回退到最大值点
     */
    public void setData(@Nullable List<TrendPoint> data, int defaultIndex) {
        List<TrendPoint> next = data == null ? Collections.<TrendPoint>emptyList() : data;
        this.points = next;

        long max = 0L;
        int maxIndex = -1;
        for (int i = 0; i < next.size(); i++) {
            long value = next.get(i).value;
            if (value > max) {
                max = value;
                maxIndex = i;
            }
        }
        this.maxValueIndex = maxIndex;
        this.todayIndex = defaultIndex >= 0 && defaultIndex < next.size() ? defaultIndex : -1;
        this.axisStep = niceStep(max, gridLines - 1);
        this.axisMax = axisStep * (gridLines - 1);
        // 标签在 onDraw 拿到绘图区宽度后按 ChartAxisLabels 重算（与点数解耦）
        this.labelKeyCount = -1;
        this.hoverIndex = -1;
        startAnimation();
    }

    /** 无趋势数据时绘制在图表中央的提示文案。 */
    public void setEmptyText(@Nullable String text) {
        this.emptyText = text == null ? "" : text;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desiredHeight = (int) getResources().getDimension(R.dimen.chart_line_height);
        setMeasuredDimension(resolveSize(getSuggestedMinimumWidth(), widthMeasureSpec),
                resolveSize(desiredHeight, heightMeasureSpec));
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        if (!computeChartRect(width, height)) {
            return;
        }
        refreshLabelIndices();

        drawGrid(canvas);

        if (points.isEmpty() || axisMax <= 0L) {
            drawNoData(canvas);
            return;
        }

        drawSeries(canvas);
        drawXLabels(canvas);
        if (hoverIndex >= 0 && hoverIndex < points.size()) {
            drawHover(canvas);
        } else if (todayIndex >= 0) {
            // 默认点（今天）即使金额为 0 也显示气泡，方便用户确认当日支出
            drawValueBubble(canvas, todayIndex, true);
        } else if (maxValueIndex >= 0) {
            drawValueBubble(canvas, maxValueIndex, false);
        }
    }

    /**
     * 留出 Y 轴刻度与 X 轴标签的位置后，得到实际绘图区；并按基线第 8 章在左右两侧
     * 各加一道安全边距，保证第一 / 最后数据点不贴容器边缘。
     */
    private boolean computeChartRect(int width, int height) {
        float yAxisWidth = measureYAxisWidth();
        float xLabelHeight = axisPaint.getTextSize() + labelGap;
        float topReserve = bubbleTextPaint.getTextSize() + labelGap * 4f;
        chartRect.set(getPaddingLeft() + yAxisWidth + labelGap + edgePadding,
                getPaddingTop() + topReserve,
                width - getPaddingRight() - edgePadding,
                height - getPaddingBottom() - xLabelHeight);
        return chartRect.width() > 0f && chartRect.height() > 0f;
    }

    /** 按当前数据与绘图区宽度重算 X 轴标签（键未变时直接复用缓存，动画期间零分配）。 */
    private void refreshLabelIndices() {
        int count = points.size();
        float available = chartRect.width();
        if (count == 0 || (labelKeyCount == count && labelKeyWidth == available)) {
            return;
        }
        labelKeyCount = count;
        labelKeyWidth = available;
        float[] widths = new float[count];
        for (int i = 0; i < count; i++) {
            widths[i] = axisPaint.measureText(points.get(i).label);
        }
        labelIndices = ChartAxisLabels.select(count, widths, available, minLabelGap,
                count <= denseLabelLimit ? allIndices(count) : monthPreferred(count));
    }

    @NonNull
    private static int[] allIndices(int count) {
        int[] all = new int[count];
        for (int i = 0; i < count; i++) {
            all[i] = i;
        }
        return all;
    }

    /** 月视图首选标签：1/5/10/15/20/25 + 末日；放不下由 ChartAxisLabels 贪心降采样。 */
    @NonNull
    private static int[] monthPreferred(int count) {
        List<Integer> kept = new ArrayList<>(MONTH_LABEL_OFFSETS.length + 1);
        for (int offset : MONTH_LABEL_OFFSETS) {
            if (offset < count) {
                kept.add(offset);
            }
        }
        int last = count - 1;
        if (!kept.contains(last)) {
            kept.add(last);
        }
        int[] indices = new int[kept.size()];
        for (int i = 0; i < indices.length; i++) {
            indices[i] = kept.get(i);
        }
        return indices;
    }

    private float measureYAxisWidth() {
        axisPaint.setTextAlign(Paint.Align.RIGHT);
        float max = 0f;
        for (int i = 0; i < gridLines; i++) {
            float textWidth = axisPaint.measureText(AmountUtil.abbreviate(axisStep * i));
            if (textWidth > max) {
                max = textWidth;
            }
        }
        return max;
    }

    private void drawGrid(@NonNull Canvas canvas) {
        int intervals = gridLines - 1;
        axisPaint.setTextAlign(Paint.Align.RIGHT);
        float textOffset = (axisPaint.ascent() + axisPaint.descent()) / 2f;
        for (int i = 0; i < gridLines; i++) {
            float y = chartRect.bottom - chartRect.height() * i / intervals;
            canvas.drawLine(chartRect.left, y, chartRect.right, y, gridPaint);
            canvas.drawText(AmountUtil.abbreviate(axisStep * i),
                    chartRect.left - labelGap, y - textOffset, axisPaint);
        }
    }

    private void drawSeries(@NonNull Canvas canvas) {
        int count = points.size();
        linePath.reset();
        fillPath.reset();

        float firstX = xAt(0);
        float firstY = yAt(points.get(0).value);
        linePath.moveTo(firstX, firstY);
        fillPath.moveTo(firstX, chartRect.bottom);
        fillPath.lineTo(firstX, firstY);
        for (int i = 1; i < count; i++) {
            float x = xAt(i);
            float y = yAt(points.get(i).value);
            linePath.lineTo(x, y);
            fillPath.lineTo(x, y);
        }
        fillPath.lineTo(xAt(count - 1), chartRect.bottom);
        fillPath.close();

        canvas.drawPath(fillPath, ensureFillShader());
        canvas.drawPath(linePath, linePaint);
        for (int i = 0; i < count; i++) {
            canvas.drawCircle(xAt(i), yAt(points.get(i).value), dotRadius, dotPaint);
        }
    }

    /** 渐变只在绘图区高度变化时重建，避免动画期间每帧分配对象。 */
    @NonNull
    private Paint ensureFillShader() {
        if (fillShader == null || shaderTop != chartRect.top || shaderBottom != chartRect.bottom) {
            int lineColor = linePaint.getColor();
            fillShader = new LinearGradient(0f, chartRect.top, 0f, chartRect.bottom,
                    CategoryColors.withAlpha(lineColor, fillAlpha),
                    CategoryColors.withAlpha(lineColor, 0),
                    Shader.TileMode.CLAMP);
            shaderTop = chartRect.top;
            shaderBottom = chartRect.bottom;
            fillPaint.setShader(fillShader);
        }
        return fillPaint;
    }

    /**
     * X 轴标签：首标签左对齐于绘图区左缘、末标签右对齐于右缘（与 ChartAxisLabels 的
     * 几何约定一致），中间标签居中于数据点——两端不裁切、间距由选择算法保证。
     */
    private void drawXLabels(@NonNull Canvas canvas) {
        float baseline = chartRect.bottom + labelGap - axisPaint.ascent();
        for (int index : labelIndices) {
            if (index < 0 || index >= points.size()) {
                continue;
            }
            String label = points.get(index).label;
            if (index == 0 && points.size() > 1) {
                axisPaint.setTextAlign(Paint.Align.LEFT);
                canvas.drawText(label, chartRect.left, baseline, axisPaint);
            } else if (index == points.size() - 1 && points.size() > 1) {
                axisPaint.setTextAlign(Paint.Align.RIGHT);
                canvas.drawText(label, chartRect.right, baseline, axisPaint);
            } else {
                axisPaint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText(label, xAt(index), baseline, axisPaint);
            }
        }
        axisPaint.setTextAlign(Paint.Align.CENTER);
    }

    private void drawHover(@NonNull Canvas canvas) {
        float x = xAt(hoverIndex);
        canvas.drawLine(x, chartRect.top, x, chartRect.bottom, guidePaint);
        canvas.drawCircle(x, yAt(points.get(hoverIndex).value), dotRadius * 1.6f, dotPaint);
        drawValueBubble(canvas, hoverIndex, true);
    }

    /**
     * 在指定点上方画一个主色数值气泡，并夹在绘图区内避免被裁切。
     *
     * @param allowZero 为 true 时金额为 0 也绘制气泡（默认点与触摸点），
     *                  为 false 时金额为 0 跳过（最大值回退点）
     */
    private void drawValueBubble(@NonNull Canvas canvas, int index, boolean allowZero) {
        if (index < 0 || index >= points.size()) {
            return;
        }
        if (points.get(index).value <= 0L && !allowZero) {
            return;
        }
        String text = AmountUtil.abbreviate(points.get(index).value);
        float halfWidth = bubbleTextPaint.measureText(text) / 2f + labelGap * 2f;
        float bubbleHeight = bubbleTextPaint.getTextSize() + labelGap * 3f;

        float left = xAt(index) - halfWidth;
        float right = xAt(index) + halfWidth;
        float overflowLeft = chartRect.left - left;
        if (overflowLeft > 0f) {
            left += overflowLeft;
            right += overflowLeft;
        }
        float overflowRight = right - chartRect.right;
        if (overflowRight > 0f) {
            left -= overflowRight;
            right -= overflowRight;
        }

        float bottom = yAt(points.get(index).value) - dotRadius - labelGap;
        float top = bottom - bubbleHeight;
        if (top < chartRect.top - bubbleTextPaint.getTextSize() * 2f) {
            // 点太靠上时把气泡挪到点下方
            top = yAt(points.get(index).value) + dotRadius + labelGap;
            bottom = top + bubbleHeight;
        }

        bubbleRect.set(left, top, right, bottom);
        canvas.drawRoundRect(bubbleRect, bubbleRadius, bubbleRadius, bubblePaint);
        float textOffset = (bubbleTextPaint.ascent() + bubbleTextPaint.descent()) / 2f;
        canvas.drawText(text, bubbleRect.centerX(), bubbleRect.centerY() - textOffset,
                bubbleTextPaint);
    }

    private void drawNoData(@NonNull Canvas canvas) {
        if (emptyText.isEmpty()) {
            return;
        }
        float textOffset = (emptyPaint.ascent() + emptyPaint.descent()) / 2f;
        canvas.drawText(emptyText, chartRect.centerX(), chartRect.centerY() - textOffset, emptyPaint);
    }

    private float xAt(int index) {
        int count = points.size();
        if (count <= 1) {
            return chartRect.centerX();
        }
        return chartRect.left + chartRect.width() * index / (count - 1);
    }

    private float yAt(long value) {
        if (axisMax <= 0L) {
            return chartRect.bottom;
        }
        float ratio = (float) value / (float) axisMax * animProgress;
        return chartRect.bottom - chartRect.height() * ratio;
    }

    // ------------------------------------------------------------------
    // 触摸：竖直辅助线 + 对应点数值
    // ------------------------------------------------------------------

    @Override
    public boolean onTouchEvent(@NonNull MotionEvent event) {
        if (points.isEmpty() || axisMax <= 0L) {
            return super.onTouchEvent(event);
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // 图表位于 ScrollView 内，按下时先禁止父容器拦截，否则滑动会被当成翻页
                requestParentDisallowIntercept(true);
                updateHover(event.getX());
                return true;
            case MotionEvent.ACTION_MOVE:
                updateHover(event.getX());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                requestParentDisallowIntercept(false);
                hoverIndex = -1;
                invalidate();
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private void requestParentDisallowIntercept(boolean disallow) {
        if (getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(disallow);
        }
    }

    private void updateHover(float x) {
        int count = points.size();
        if (count <= 0 || chartRect.width() <= 0f) {
            return;
        }
        int index = Math.round((x - chartRect.left) / chartRect.width() * (count - 1));
        index = Math.max(0, Math.min(count - 1, index));
        if (index != hoverIndex) {
            hoverIndex = index;
            invalidate();
        }
    }

    // ------------------------------------------------------------------
    // 动画
    // ------------------------------------------------------------------

    private void startAnimation() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        if (animDuration <= 0 || !isAttachedToWindow()) {
            animProgress = 1f;
            invalidate();
            return;
        }
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(animDuration);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(animation -> {
            animProgress = (float) animation.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    // ------------------------------------------------------------------
    // 刻度计算：全部整数运算，不产生浮点金额
    // ------------------------------------------------------------------

    /**
     * 取一个「好看」的 Y 轴单格刻度：把 {@code maxValue / intervals} 归一到 1/2/5×10ⁿ，
     * 并保证 {@code step × intervals} 一定不小于数据最大值。
     */
    static long niceStep(long maxValue, int intervals) {
        if (maxValue <= 0L || intervals <= 0) {
            return 100L;
        }
        long rough = maxValue / intervals;
        if (rough <= 0L) {
            rough = 1L;
        }
        long magnitude = 1L;
        while (magnitude * 10L <= rough) {
            magnitude *= 10L;
        }
        long normalized = rough / magnitude;
        long nice;
        if (normalized <= 1L) {
            nice = 1L;
        } else if (normalized <= 2L) {
            nice = 2L;
        } else if (normalized <= 5L) {
            nice = 5L;
        } else {
            nice = 10L;
        }
        long step = nice * magnitude;
        // Y 轴刻度以「元」展示，小于 ¥1 的刻度会全部渲染成 ¥0，因此下限取 100 分。
        if (step < 100L) {
            step = 100L;
        }
        while (step * intervals < maxValue) {
            step *= 2L;
        }
        return step;
    }
}
