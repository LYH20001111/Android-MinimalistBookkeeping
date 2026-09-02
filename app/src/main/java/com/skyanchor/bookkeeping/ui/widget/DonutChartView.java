package com.skyanchor.bookkeeping.ui.widget;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.skyanchor.bookkeeping.R;
import com.skyanchor.bookkeeping.data.model.CategoryStat;
import com.skyanchor.bookkeeping.util.AmountUtil;

import java.util.Collections;
import java.util.List;

/**
 * 消费分类占比环形图，纯 Canvas 自绘。
 *
 * <p>每段弧的角度由 {@link CategoryStat#percentX10}（千分比整数）换算而来，
 * 因此各段之和严格等于 360°，不会因为浮点累计误差留下缝隙或重叠。
 * 颜色取自 {@link com.skyanchor.bookkeeping.util.CategoryColors}，同一分类跨周期颜色一致。
 *
 * <p>空数据时绘制一条 divider 色的完整圆环作为占位，圆心金额显示 ¥0.00。
 */
public class DonutChartView extends View {

    /** 圆心金额文字的最小可读尺寸系数，低于该值不再继续缩小。 */
    private static final float MIN_AMOUNT_TEXT_RATIO = 0.5f;

    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint amountPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint captionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcRect = new RectF();

    private final float ringWidth;
    private final float segmentGap;
    private final float captionGap;
    private final float amountTextSize;
    private final float captionTextSize;

    @NonNull
    private List<CategoryStat> stats = Collections.emptyList();

    @NonNull
    private String centerCaption = "";

    private long total;

    public DonutChartView(@NonNull Context context) {
        this(context, null);
    }

    public DonutChartView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public DonutChartView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        Resources resources = context.getResources();
        ringWidth = resources.getDimension(R.dimen.donut_ring_width);
        segmentGap = resources.getDimension(R.dimen.spacing_xxs);
        captionGap = resources.getDimension(R.dimen.spacing_xs);
        amountTextSize = resources.getDimension(R.dimen.font_section_title);
        captionTextSize = resources.getDimension(R.dimen.font_caption);

        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(ringWidth);
        ringPaint.setStrokeCap(Paint.Cap.BUTT);

        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeWidth(ringWidth);
        trackPaint.setColor(ContextCompat.getColor(context, R.color.divider));

        amountPaint.setTextAlign(Paint.Align.CENTER);
        amountPaint.setTextSize(amountTextSize);
        amountPaint.setFakeBoldText(true);
        amountPaint.setColor(ContextCompat.getColor(context, R.color.text_primary));

        captionPaint.setTextAlign(Paint.Align.CENTER);
        captionPaint.setTextSize(captionTextSize);
        captionPaint.setColor(ContextCompat.getColor(context, R.color.text_tertiary));
    }

    /** 设置分类占比数据，圆心总额为各项金额之和。 */
    public void setData(@Nullable List<CategoryStat> data) {
        List<CategoryStat> next = data == null ? Collections.<CategoryStat>emptyList() : data;
        this.stats = next;
        long sum = 0L;
        for (CategoryStat stat : next) {
            sum += stat.amount;
        }
        this.total = sum;
        invalidate();
    }

    /** 圆心第二行文案，例如「总支出」。由界面层传入，控件内不硬编码任何字符串。 */
    public void setCenterCaption(@Nullable String caption) {
        this.centerCaption = caption == null ? "" : caption;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desired = (int) getResources().getDimension(R.dimen.chart_donut_size);
        setMeasuredDimension(resolveSize(desired, widthMeasureSpec),
                resolveSize(desired, heightMeasureSpec));
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        float contentWidth = getWidth() - getPaddingLeft() - getPaddingRight();
        float contentHeight = getHeight() - getPaddingTop() - getPaddingBottom();
        float size = Math.min(contentWidth, contentHeight);
        if (size <= ringWidth) {
            return;
        }

        float centerX = getPaddingLeft() + contentWidth / 2f;
        float centerY = getPaddingTop() + contentHeight / 2f;
        // drawArc 的矩形是描边中心线所在的圆，因此内缩半个环宽
        float radius = (size - ringWidth) / 2f;
        arcRect.set(centerX - radius, centerY - radius, centerX + radius, centerY + radius);

        if (stats.isEmpty() || total <= 0L) {
            canvas.drawArc(arcRect, 0f, 360f, false, trackPaint);
        } else {
            drawSegments(canvas, radius);
        }
        drawCenterText(canvas, centerX, centerY, radius);
    }

    private void drawSegments(@NonNull Canvas canvas, float radius) {
        // 段间 2dp 间隙换算成角度；只有一段时不留间隙，否则整圆会被切出一个缺口
        float gapDegrees = stats.size() > 1 ? (float) Math.toDegrees(segmentGap / radius) : 0f;
        float start = -90f;
        for (CategoryStat stat : stats) {
            float sweep = stat.percentX10 * 360f / 1000f;
            if (sweep <= 0f) {
                continue;
            }
            float drawn = sweep - gapDegrees;
            if (drawn <= 0f) {
                drawn = sweep;
            }
            ringPaint.setColor(stat.color);
            canvas.drawArc(arcRect, start + gapDegrees / 2f, drawn, false, ringPaint);
            start += sweep;
        }
    }

    /** 圆心两行文字：总额 + 说明文案，整体垂直居中；金额过长时按比例缩小字号。 */
    private void drawCenterText(@NonNull Canvas canvas, float centerX, float centerY, float radius) {
        String amount = AmountUtil.format(total);
        float innerDiameter = (radius - ringWidth / 2f) * 2f * 0.86f;

        amountPaint.setTextSize(amountTextSize);
        float minSize = amountTextSize * MIN_AMOUNT_TEXT_RATIO;
        while (amountPaint.measureText(amount) > innerDiameter
                && amountPaint.getTextSize() > minSize) {
            amountPaint.setTextSize(amountPaint.getTextSize() * 0.9f);
        }

        float amountHeight = amountPaint.getTextSize();
        float blockHeight = centerCaption.isEmpty()
                ? amountHeight : amountHeight + captionGap + captionTextSize;
        float top = centerY - blockHeight / 2f;
        canvas.drawText(amount, centerX, top - amountPaint.ascent(), amountPaint);
        if (!centerCaption.isEmpty()) {
            canvas.drawText(centerCaption, centerX,
                    top + amountHeight + captionGap - captionPaint.ascent(), captionPaint);
        }
    }
}
