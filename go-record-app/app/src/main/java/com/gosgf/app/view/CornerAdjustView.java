package com.gosgf.app.view;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * 角点调整视图：在图片上显示棋盘四角(TL→TR→BR→BL)并允许拖拽调整。
 * 与 CropView 不同，四角不强制矩形约束 —— 允许任意四边形，
 * 适配拍照透视变形(棋盘在照片中不是正四边形)。
 * {@link #getCorners()} 返回原图像素坐标的四角。
 */
public class CornerAdjustView extends View {
    private static final float HANDLE_RADIUS = 28f;
    private static final float TOUCH_TOLERANCE = 60f;
    /** 手柄往内(四边形中心方向)偏移的距离, 避免手指挡住棋盘边线 */
    private static final float HANDLE_OFFSET = 96f;

    private Bitmap bitmap;
    private final Matrix displayMatrix = new Matrix();
    private final Matrix invertMatrix = new Matrix();
    private int viewW, viewH;

    /** 四角(屏幕坐标): TL, TR, BR, BL — 实际角点位置, 边框/遮罩/H均以此为准 */
    private final PointF[] handles = new PointF[4];
    private int draggingHandle = -1; // 0~3=角, -1=无, -2=整体拖拽
    private float lastTouchX, lastTouchY;
    private boolean cornersReady = false;

    private final Paint maskPaint = new Paint();
    private final Paint borderPaint = new Paint();
    private final Paint handlePaint = new Paint();
    private final Paint labelPaint = new Paint();
    private final Paint linkPaint = new Paint();

    public CornerAdjustView(Context context) {
        this(context, null);
    }

    public CornerAdjustView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CornerAdjustView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init();
    }

    private void init() {
        maskPaint.setColor(Color.argb(80, 0, 0, 0));
        maskPaint.setStyle(Paint.Style.FILL);

        borderPaint.setColor(Color.parseColor("#4CAF50"));
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(3f);
        borderPaint.setAntiAlias(true);

        handlePaint.setColor(Color.argb(120, 255, 255, 255));
        handlePaint.setStyle(Paint.Style.FILL);
        handlePaint.setAntiAlias(true);

        labelPaint.setColor(Color.WHITE);
        labelPaint.setTextSize(28f);
        labelPaint.setAntiAlias(true);
        labelPaint.setTextAlign(Paint.Align.CENTER);

        // 手柄与实际角点的连接线(虚线)
        linkPaint.setColor(Color.parseColor("#4CAF50"));
        linkPaint.setStyle(Paint.Style.STROKE);
        linkPaint.setStrokeWidth(2f);
        linkPaint.setAntiAlias(true);
        linkPaint.setPathEffect(new android.graphics.DashPathEffect(new float[]{10f, 6f}, 0));

        for (int i = 0; i < 4; i++) handles[i] = new PointF(0, 0);
    }

    /** 计算四边形中心 */
    private float[] getCenter() {
        float cx = 0, cy = 0;
        for (PointF p : handles) { cx += p.x; cy += p.y; }
        return new float[]{cx / 4f, cy / 4f};
    }

    /** 手柄显示位置: 实际角点往中心方向偏移 HANDLE_OFFSET */
    private float[] getHandlePos(int i) {
        float[] c = getCenter();
        float dx = c[0] - handles[i].x;
        float dy = c[1] - handles[i].y;
        float len = (float) Math.hypot(dx, dy);
        if (len <= 0) return new float[]{handles[i].x, handles[i].y};
        return new float[]{
            handles[i].x + dx / len * HANDLE_OFFSET,
            handles[i].y + dy / len * HANDLE_OFFSET};
    }

    public void setBitmap(Bitmap bmp) {
        if (bmp == null) return;
        this.bitmap = bmp;
        if (viewW > 0 && viewH > 0) {
            setupDisplayMatrix();
            if (!cornersReady) initDefaultCorners();
        }
        invalidate();
    }

    /** 设置自动检测到的四角(原图像素坐标), 内部映射到屏幕坐标显示。 */
    public void setCorners(float[][] corners) {
        if (bitmap == null || corners == null || corners.length < 4) return;
        float[] pts = new float[8];
        for (int i = 0; i < 4; i++) {
            pts[i * 2] = corners[i][0];
            pts[i * 2 + 1] = corners[i][1];
        }
        displayMatrix.mapPoints(pts);
        for (int i = 0; i < 4; i++) {
            handles[i].set(pts[i * 2], pts[i * 2 + 1]);
        }
        cornersReady = true;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        viewW = w;
        viewH = h;
        if (bitmap != null) {
            setupDisplayMatrix();
            if (!cornersReady) initDefaultCorners();
            // cornersReady=true 时保持现有 handles(角点已基于当前 displayMatrix 设置)
        }
    }

    private void setupDisplayMatrix() {
        if (bitmap == null || viewW <= 0 || viewH <= 0) return;
        float scale = Math.min(
                (float) viewW / bitmap.getWidth(),
                (float) viewH / bitmap.getHeight());
        float dx = (viewW - bitmap.getWidth() * scale) / 2f;
        float dy = (viewH - bitmap.getHeight() * scale) / 2f;
        displayMatrix.reset();
        displayMatrix.setScale(scale, scale);
        displayMatrix.postTranslate(dx, dy);
        displayMatrix.invert(invertMatrix);
    }

    /** 默认四角: 图片显示区域四角(内缩 5%) */
    private void initDefaultCorners() {
        if (bitmap == null || viewW <= 0 || viewH <= 0) return;
        float[] c = {0, 0, bitmap.getWidth(), bitmap.getHeight()};
        displayMatrix.mapPoints(c);
        float left = c[0], top = c[1], right = c[2], bottom = c[3];
        float insetX = (right - left) * 0.05f;
        float insetY = (bottom - top) * 0.05f;
        left += insetX; top += insetY; right -= insetX; bottom -= insetY;
        handles[0].set(left, top);
        handles[1].set(right, top);
        handles[2].set(right, bottom);
        handles[3].set(left, bottom);
        cornersReady = true;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bitmap == null) return;

        canvas.drawBitmap(bitmap, displayMatrix, null);

        // 半透明遮罩(四边形外)
        Path maskPath = new Path();
        maskPath.addRect(0, 0, viewW, viewH, Path.Direction.CW);
        Path inner = new Path();
        inner.moveTo(handles[0].x, handles[0].y);
        inner.lineTo(handles[1].x, handles[1].y);
        inner.lineTo(handles[2].x, handles[2].y);
        inner.lineTo(handles[3].x, handles[3].y);
        inner.close();
        maskPath.op(inner, Path.Op.DIFFERENCE);
        canvas.drawPath(maskPath, maskPaint);

        // 四边形边框(实际角点连线)
        canvas.drawLine(handles[0].x, handles[0].y, handles[1].x, handles[1].y, borderPaint);
        canvas.drawLine(handles[1].x, handles[1].y, handles[2].x, handles[2].y, borderPaint);
        canvas.drawLine(handles[2].x, handles[2].y, handles[3].x, handles[3].y, borderPaint);
        canvas.drawLine(handles[3].x, handles[3].y, handles[0].x, handles[0].y, borderPaint);

        // 对角线(辅助对齐)
        Paint diag = new Paint(borderPaint);
        diag.setColor(Color.argb(80, 76, 175, 80));
        diag.setStrokeWidth(1f);
        canvas.drawLine(handles[0].x, handles[0].y, handles[2].x, handles[2].y, diag);
        canvas.drawLine(handles[1].x, handles[1].y, handles[3].x, handles[3].y, diag);

        // 每个角: 虚线连接(实际角点 → 偏移手柄)
        String[] labels = {"TL", "TR", "BR", "BL"};
        for (int i = 0; i < 4; i++) {
            float[] hp = getHandlePos(i);
            // 虚线连接(实际角点 → 手柄)
            canvas.drawLine(handles[i].x, handles[i].y, hp[0], hp[1], linkPaint);
            // 偏移手柄(白色半透明大圆 + 标签)
            canvas.drawCircle(hp[0], hp[1], HANDLE_RADIUS, handlePaint);
            canvas.drawText(labels[i], hp[0], hp[1] + 10f, labelPaint);
        }
    }

    /** 返回原图像素坐标的四角(顺序 TL→TR→BR→BL) */
    public float[][] getCorners() {
        if (bitmap == null) return null;
        float[] pts = new float[8];
        for (int i = 0; i < 4; i++) {
            pts[i * 2] = handles[i].x;
            pts[i * 2 + 1] = handles[i].y;
        }
        invertMatrix.mapPoints(pts);
        float[][] out = new float[4][2];
        for (int i = 0; i < 4; i++) {
            out[i][0] = pts[i * 2];
            out[i][1] = pts[i * 2 + 1];
        }
        return out;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                draggingHandle = findHandle(x, y);
                lastTouchX = x;
                lastTouchY = y;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (draggingHandle >= 0 && draggingHandle < 4) {
                    // delta 方式: 手柄跟随手指移动, 实际角点同步偏移
                    float dx = x - lastTouchX;
                    float dy = y - lastTouchY;
                    handles[draggingHandle].x = clamp(handles[draggingHandle].x + dx, 0, viewW);
                    handles[draggingHandle].y = clamp(handles[draggingHandle].y + dy, 0, viewH);
                    lastTouchX = x;
                    lastTouchY = y;
                    invalidate();
                } else if (draggingHandle == -2) {
                    float dx = x - lastTouchX;
                    float dy = y - lastTouchY;
                    moveAll(dx, dy);
                    lastTouchX = x;
                    lastTouchY = y;
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                draggingHandle = -1;
                return true;
        }
        return false;
    }

    private float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private void moveAll(float dx, float dy) {
        float minX = Float.MAX_VALUE, maxX = Float.MIN_VALUE;
        float minY = Float.MAX_VALUE, maxY = Float.MIN_VALUE;
        for (PointF p : handles) {
            minX = Math.min(minX, p.x);
            maxX = Math.max(maxX, p.x);
            minY = Math.min(minY, p.y);
            maxY = Math.max(maxY, p.y);
        }
        if (minX + dx < 0) dx = -minX;
        if (maxX + dx > viewW) dx = viewW - maxX;
        if (minY + dy < 0) dy = -minY;
        if (maxY + dy > viewH) dy = viewH - maxY;
        for (PointF p : handles) {
            p.x += dx;
            p.y += dy;
        }
    }

    private int findHandle(float x, float y) {
        // 用偏移后的手柄位置检测触摸
        for (int i = 0; i < 4; i++) {
            float[] hp = getHandlePos(i);
            if (Math.hypot(x - hp[0], y - hp[1]) < TOUCH_TOLERANCE) {
                return i;
            }
        }
        // 四边形内 → 整体拖拽
        if (pointInQuad(x, y)) return -2;
        return -1;
    }

    private boolean pointInQuad(float x, float y) {
        PointF[] p = handles;
        boolean[] signs = new boolean[4];
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            float cross = (p[j].x - p[i].x) * (y - p[i].y) - (p[j].y - p[i].y) * (x - p[i].x);
            signs[i] = cross >= 0;
        }
        return signs[0] == signs[1] && signs[1] == signs[2] && signs[2] == signs[3];
    }
}
