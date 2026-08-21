package com.gosgf.app.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * 自由四角校正选框：四个角可独立拖拽到任意位置（任意四边形，不必是正方形/矩形），
 * 用于把透视变形的棋盘四角手动对准真实棋盘的四个角。
 * 显示透视网格辅助线（19 路）帮助对齐；角坐标基于本 View 的本地像素坐标系，
 * 由调用方负责映射回原图像素坐标。
 */
public class QuadOverlayView extends View {

    // 图片在 View 中的显示矩形（fitCenter 后的 drawable 区域，View 本地坐标）
    private final RectF imageRect = new RectF();
    private int imageWidth = 1;   // 原图宽（用于映射）
    private int imageHeight = 1;  // 原图高

    // 四角（View 本地坐标）: [0]=TL [1]=TR [2]=BR [3]=BL
    private final float[][] corners = new float[4][2];

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

    public QuadOverlayView(Context context) {
        super(context);
        initPaints();
    }

    public QuadOverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        initPaints();
    }

    private void initPaints() {
        dimPaint.setColor(Color.argb(140, 0, 0, 0));
        framePaint.setColor(Color.parseColor("#F59E0B")); // 琥珀色边框(与裁剪的青绿区分)
        framePaint.setStyle(Paint.Style.STROKE);
        framePaint.setStrokeWidth(3f);
        linePaint.setColor(Color.argb(120, 255, 255, 255));
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(1f);
        handlePaint.setColor(Color.parseColor("#F59E0B"));
        handlePaint.setStyle(Paint.Style.FILL);
        handleStrokePaint.setColor(Color.WHITE);
        handleStrokePaint.setStyle(Paint.Style.STROKE);
        handleStrokePaint.setStrokeWidth(3f);
    }

    /** 设置图片显示矩形与原图尺寸，并初始化居中四边形（正方形，边长取显示矩形的 80%）。 */
    public void setImageRect(RectF rect, int imgW, int imgH) {
        imageRect.set(rect);
        imageWidth = Math.max(1, imgW);
        imageHeight = Math.max(1, imgH);
        float side = Math.min(imageRect.width(), imageRect.height()) * 0.8f;
        float cx = imageRect.centerX();
        float cy = imageRect.centerY();
        corners[0][0] = cx - side / 2f; corners[0][1] = cy - side / 2f; // TL
        corners[1][0] = cx + side / 2f; corners[1][1] = cy - side / 2f; // TR
        corners[2][0] = cx + side / 2f; corners[2][1] = cy + side / 2f; // BR
        corners[3][0] = cx - side / 2f; corners[3][1] = cy + side / 2f; // BL
        clampCorners();
        invalidate();
    }

    /** 从原图像素坐标设置四角（初始值，例如上次识别自动检测到的角点）。 */
    public void setCornersFromImage(float[][] imgCorners, int imgW, int imgH) {
        if (imgCorners == null || imgCorners.length < 4) return;
        float scale = imageRect.width() / imageWidth; // fitCenter 等比
        if (scale <= 0) scale = 1f;
        for (int i = 0; i < 4; i++) {
            corners[i][0] = imageRect.left + imgCorners[i][0] * scale;
            corners[i][1] = imageRect.top + imgCorners[i][1] * scale;
        }
        clampCorners();
        invalidate();
    }

    /** 把当前四角映射回原图像素坐标，顺序 TL→TR→BR→BL。 */
    public float[][] getCornersInImage() {
        float scale = imageRect.width() / imageWidth; // fitCenter 等比
        if (scale <= 0) scale = 1f;
        float[][] out = new float[4][2];
        for (int i = 0; i < 4; i++) {
            float x = (corners[i][0] - imageRect.left) / scale;
            float y = (corners[i][1] - imageRect.top) / scale;
            out[i][0] = Math.max(0f, Math.min((float) imageWidth, x));
            out[i][1] = Math.max(0f, Math.min((float) imageHeight, y));
        }
        return out;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (imageRect.isEmpty()) return;
        // 仅暗化四边形外部
        drawDimOutside(canvas);

        // 四边形边框
        Path quad = new Path();
        quad.moveTo(corners[0][0], corners[0][1]);
        quad.lineTo(corners[1][0], corners[1][1]);
        quad.lineTo(corners[2][0], corners[2][1]);
        quad.lineTo(corners[3][0], corners[3][1]);
        quad.close();
        canvas.drawPath(quad, framePaint);

        // 透视网格辅助线:四边按 19 等分插值,连接对应分点
        for (int i = 1; i < GRID; i++) {
            float t = i / (float) GRID;
            // 竖线: 上边 t → 下边 t
            canvas.drawLine(
                    lerp(corners[0][0], corners[1][0], t), lerp(corners[0][1], corners[1][1], t),
                    lerp(corners[3][0], corners[2][0], t), lerp(corners[3][1], corners[2][1], t),
                    linePaint);
            // 横线: 左边 t → 右边 t
            canvas.drawLine(
                    lerp(corners[0][0], corners[3][0], t), lerp(corners[0][1], corners[3][1], t),
                    lerp(corners[1][0], corners[2][0], t), lerp(corners[1][1], corners[2][1], t),
                    linePaint);
        }

        // 四角手柄
        for (int i = 0; i < 4; i++) {
            drawHandle(canvas, corners[i][0], corners[i][1]);
        }
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private void drawDimOutside(Canvas canvas) {
        // 用反奇偶填充:整个 imageRect 挖掉四边形区域
        Path p = new Path();
        p.setFillType(Path.FillType.INVERSE_EVEN_ODD);
        p.addRect(imageRect, Path.Direction.CW);
        Path quad = new Path();
        quad.moveTo(corners[0][0], corners[0][1]);
        quad.lineTo(corners[1][0], corners[1][1]);
        quad.lineTo(corners[2][0], corners[2][1]);
        quad.lineTo(corners[3][0], corners[3][1]);
        quad.close();
        p.addPath(quad);
        canvas.drawPath(p, dimPaint);
    }

    private void drawHandle(Canvas canvas, float x, float y) {
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
                    for (int i = 0; i < 4; i++) {
                        corners[i][0] += dx;
                        corners[i][1] += dy;
                    }
                } else {
                    // 单个角自由拖拽(任意四边形,不做等边约束)
                    int idx = modeToIndex(mode);
                    if (idx >= 0) {
                        corners[idx][0] = x;
                        corners[idx][1] = y;
                    }
                }
                clampCorners();
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mode = DragMode.NONE;
                return true;
        }
        return super.onTouchEvent(event);
    }

    private int modeToIndex(DragMode m) {
        switch (m) {
            case TL: return 0;
            case TR: return 1;
            case BR: return 2;
            case BL: return 3;
            default: return -1;
        }
    }

    private DragMode hitTest(float x, float y) {
        if (near(corners[0][0], corners[0][1], x, y)) return DragMode.TL;
        if (near(corners[1][0], corners[1][1], x, y)) return DragMode.TR;
        if (near(corners[2][0], corners[2][1], x, y)) return DragMode.BR;
        if (near(corners[3][0], corners[3][1], x, y)) return DragMode.BL;
        if (contains(x, y)) return DragMode.MOVE;
        return DragMode.NONE;
    }

    private boolean contains(float x, float y) {
        // 点与四边形关系(凸四边形时有效):同向叉积
        boolean sign = false;
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            float ax = corners[j][0] - corners[i][0];
            float ay = corners[j][1] - corners[i][1];
            float bx = x - corners[i][0];
            float by = y - corners[i][1];
            float cross = ax * by - ay * bx;
            if (i == 0) sign = cross > 0;
            else if ((cross > 0) != sign) return false;
        }
        return true;
    }

    private boolean near(float px, float py, float x, float y) {
        return Math.hypot(px - x, py - y) <= HANDLE_RADIUS;
    }

    /** 约束四角在图片显示矩形内，并保持四边形最小尺寸。 */
    private void clampCorners() {
        for (int i = 0; i < 4; i++) {
            if (corners[i][0] < imageRect.left) corners[i][0] = imageRect.left;
            if (corners[i][0] > imageRect.right) corners[i][0] = imageRect.right;
            if (corners[i][1] < imageRect.top) corners[i][1] = imageRect.top;
            if (corners[i][1] > imageRect.bottom) corners[i][1] = imageRect.bottom;
        }
    }
}
