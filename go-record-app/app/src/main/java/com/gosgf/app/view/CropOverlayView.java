package com.gosgf.app.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * 自定义裁剪选框：用户可拖动整框、拖拽四角缩放（保持正方形，围棋棋盘为正方形）。
 * 选框坐标基于本 View 的本地像素坐标系；CropActivity 负责把它映射回原图像素坐标。
 */
public class CropOverlayView extends View {

    // 图片在 View 中的显示矩形（fitCenter 后的 drawable 区域，View 本地坐标）
    private final RectF imageRect = new RectF();
    private int imageWidth = 1;   // 原图宽（用于映射）
    private int imageHeight = 1;  // 原图高

    // 当前选框（View 本地坐标）
    private final RectF cropRect = new RectF();

    private final Paint dimPaint = new Paint();
    private final Paint framePaint = new Paint();
    private final Paint linePaint = new Paint();
    private final Paint handlePaint = new Paint();
    private final Paint handleStrokePaint = new Paint();

    private static final float HANDLE_RADIUS = 30f; // 角手柄命中半径(便于触摸)
    private static final float HANDLE_DRAW_R = 22f;  // 角手柄绘制半径(更大更醒目)
    private static final int GRID = 19;             // 网格辅助线（与 19 路一致）

    // 触摸状态
    private enum DragMode { NONE, MOVE, TL, TR, BR, BL }
    private DragMode mode = DragMode.NONE;
    private float lastX, lastY;

    public CropOverlayView(Context context) {
        super(context);
        initPaints();
    }

    public CropOverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        initPaints();
    }

    private void initPaints() {
        dimPaint.setColor(Color.argb(140, 0, 0, 0));
        framePaint.setColor(Color.parseColor("#2DD4BF")); // 青绿边框
        framePaint.setStyle(Paint.Style.STROKE);
        framePaint.setStrokeWidth(3f);
        linePaint.setColor(Color.argb(120, 255, 255, 255));
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(1f);
        handlePaint.setColor(Color.parseColor("#2DD4BF"));
        handlePaint.setStyle(Paint.Style.FILL);
        handleStrokePaint.setColor(Color.WHITE);
        handleStrokePaint.setStyle(Paint.Style.STROKE);
        handleStrokePaint.setStrokeWidth(3f);
    }

    /** 设置图片显示矩形与原图尺寸，并初始化一个居中正方形选框。 */
    public void setImageRect(RectF rect, int imgW, int imgH) {
        imageRect.set(rect);
        imageWidth = Math.max(1, imgW);
        imageHeight = Math.max(1, imgH);
        // 初始选框：居中正方形，边长取显示矩形的 80%
        float side = Math.min(imageRect.width(), imageRect.height()) * 0.8f;
        float cx = imageRect.centerX();
        float cy = imageRect.centerY();
        cropRect.set(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f);
        clampCrop();
        invalidate();
    }

    /** 把当前选框映射回原图像素坐标，返回 [left, top, right, bottom]（int）。 */
    public int[] getCropRectInImage() {
        float scale = imageRect.width() / imageWidth; // fitCenter 等比
        if (scale <= 0) scale = 1f;
        int left = Math.round((cropRect.left - imageRect.left) / scale);
        int top = Math.round((cropRect.top - imageRect.top) / scale);
        int right = Math.round((cropRect.right - imageRect.left) / scale);
        int bottom = Math.round((cropRect.bottom - imageRect.top) / scale);
        left = Math.max(0, Math.min(left, imageWidth));
        top = Math.max(0, Math.min(top, imageHeight));
        right = Math.max(0, Math.min(right, imageWidth));
        bottom = Math.max(0, Math.min(bottom, imageHeight));
        return new int[]{left, top, right, bottom};
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (imageRect.isEmpty()) return;
        // 仅暗化选框外部 (四块矩形)，框内保持图片清晰可见
        drawDimOutside(canvas);

        // 选框边框
        canvas.drawRect(cropRect, framePaint);
        // 网格辅助线
        float w = cropRect.width() / GRID;
        float h = cropRect.height() / GRID;
        for (int i = 1; i < GRID; i++) {
            float x = cropRect.left + w * i;
            float y = cropRect.top + h * i;
            canvas.drawLine(x, cropRect.top, x, cropRect.bottom, linePaint);
            canvas.drawLine(cropRect.left, y, cropRect.right, y, linePaint);
        }
        // 四角手柄
        drawHandle(canvas, cropRect.left, cropRect.top);
        drawHandle(canvas, cropRect.right, cropRect.top);
        drawHandle(canvas, cropRect.right, cropRect.bottom);
        drawHandle(canvas, cropRect.left, cropRect.bottom);
    }

    private void drawDimOutside(Canvas canvas) {
        // 在选框外区域叠加暗化（四块矩形）
        canvas.drawRect(imageRect.left, imageRect.top, imageRect.right, cropRect.top, dimPaint);
        canvas.drawRect(imageRect.left, cropRect.bottom, imageRect.right, imageRect.bottom, dimPaint);
        canvas.drawRect(imageRect.left, cropRect.top, cropRect.left, cropRect.bottom, dimPaint);
        canvas.drawRect(cropRect.right, cropRect.top, imageRect.right, cropRect.bottom, dimPaint);
    }

    private void drawHandle(Canvas canvas, float x, float y) {
        // 白色描边 + 青绿填充,在浅色棋盘上也清晰可见
        canvas.drawCircle(x, y, HANDLE_DRAW_R + 3f, handleStrokePaint);
        canvas.drawCircle(x, y, HANDLE_DRAW_R, handlePaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                mode = hitTest(x, y);
                lastX = x;
                lastY = y;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (mode == DragMode.NONE) return true;
                float dx = x - lastX;
                float dy = y - lastY;
                lastX = x;
                lastY = y;
                if (mode == DragMode.MOVE) {
                    cropRect.offset(dx, dy);
                } else {
                    resizeFromCorner(mode, x, y);
                }
                clampCrop();
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mode = DragMode.NONE;
                return true;
        }
        return super.onTouchEvent(event);
    }

    private DragMode hitTest(float x, float y) {
        if (near(cropRect.left, cropRect.top, x, y)) return DragMode.TL;
        if (near(cropRect.right, cropRect.top, x, y)) return DragMode.TR;
        if (near(cropRect.right, cropRect.bottom, x, y)) return DragMode.BR;
        if (near(cropRect.left, cropRect.bottom, x, y)) return DragMode.BL;
        if (cropRect.contains(x, y)) return DragMode.MOVE;
        return DragMode.NONE;
    }

    private boolean near(float px, float py, float x, float y) {
        return Math.hypot(px - x, py - y) <= HANDLE_RADIUS;
    }

    /** 拖拽某个角：保持正方形，以对角的角固定，边长为到触摸点的距离。 */
    private void resizeFromCorner(DragMode corner, float x, float y) {
        float fixedX, fixedY;
        switch (corner) {
            case TL: fixedX = cropRect.right; fixedY = cropRect.bottom; break;
            case TR: fixedX = cropRect.left; fixedY = cropRect.bottom; break;
            case BR: fixedX = cropRect.left; fixedY = cropRect.top; break;
            case BL: fixedX = cropRect.right; fixedY = cropRect.top; break;
            default: return;
        }
        // 取触摸点到固定点中较小边，保证正方形（围棋棋盘为正方形）
        float side = Math.min(Math.abs(x - fixedX), Math.abs(y - fixedY));
        side = Math.max(side, 40f); // 最小边长
        // 依据固定点方位重建正方形
        float left, top, right, bottom;
        if (fixedX <= x) { left = fixedX; right = fixedX + side; }
        else { right = fixedX; left = fixedX - side; }
        if (fixedY <= y) { top = fixedY; bottom = fixedY + side; }
        else { bottom = fixedY; top = fixedY - side; }
        cropRect.set(left, top, right, bottom);
    }

    /** 约束选框在图片显示矩形内，并保持不超出。 */
    private void clampCrop() {
        if (cropRect.left < imageRect.left) cropRect.left = imageRect.left;
        if (cropRect.top < imageRect.top) cropRect.top = imageRect.top;
        if (cropRect.right > imageRect.right) cropRect.right = imageRect.right;
        if (cropRect.bottom > imageRect.bottom) cropRect.bottom = imageRect.bottom;
        float w = cropRect.width();
        float h = cropRect.height();
        if (w < 40f || h < 40f) {
            // 太小则保持最小尺寸并居中于当前位置
            float cx = cropRect.centerX();
            float cy = cropRect.centerY();
            float s = 40f;
            cropRect.set(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2);
        }
        // 再次夹回图片范围
        if (cropRect.left < imageRect.left) cropRect.left = imageRect.left;
        if (cropRect.top < imageRect.top) cropRect.top = imageRect.top;
        if (cropRect.right > imageRect.right) cropRect.right = imageRect.right;
        if (cropRect.bottom > imageRect.bottom) cropRect.bottom = imageRect.bottom;
    }
}
