package com.skyanchor.bookkeeping.ui.chart;

import android.graphics.Rect;
import android.view.View;

import androidx.recyclerview.widget.RecyclerView;

/**
 * 间距装饰器
 *
 * <p>要让 RecyclerView 中的网格项（Grid item）之间上下左右都产生间距，不能通过纯 XML 属性直接实现，
 * 因为 RecyclerView 本身没有类似 gridSpacing 的布局属性。推荐通过 ItemDecoration 在代码中统一控制间距，
 * 这样既灵活又不会影响其他布局逻辑
 */
public class GridSpacingItemDecoration extends RecyclerView.ItemDecoration {
    private int spanCount;      // 网格列数
    private int spacing;        // 间距（像素）
    private boolean includeEdge; // 是否包含边缘间距

    public GridSpacingItemDecoration(int spanCount, int spacing, boolean includeEdge) {
        this.spanCount = spanCount;
        this.spacing = spacing;
        this.includeEdge = includeEdge;
    }

    @Override
    public void getItemOffsets(Rect outRect, View view, RecyclerView parent, RecyclerView.State state) {
        int position = parent.getChildAdapterPosition(view);
        int column = position % spanCount;

        if (includeEdge) {
            // 所有位置都有间距，边缘位置只给一半，内部位置给全间距
            outRect.left = spacing - column * spacing / spanCount;
            outRect.right = (column + 1) * spacing / spanCount;
            if (position < spanCount) {
                outRect.top = spacing;
            }
            outRect.bottom = spacing;
        } else {
            // 仅内部有间距，边缘无间距
            outRect.left = column * spacing / spanCount;
            outRect.right = spacing - (column + 1) * spacing / spanCount;
            if (position >= spanCount) {
                outRect.top = spacing;
            }
        }
    }
}