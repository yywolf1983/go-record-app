package com.gosgf.app.view;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * 手动裁剪视图：显示图片，用户可拖拽四角调整裁剪区域。
 * getCropRect() 返回原图像素坐标的裁剪矩形。
 */
public class CropView extends View {
    private static final String TAG = "CropView";
    private static final float HANDLE_RADIUS = 30f;
    private static final float TOUCH_TOLERANCE = 60f;
    private static final float MIN_CROP_SIZE = 80f;

    private Bitmap bitmap;
    private Matrix displayMatrix = new Matrix();
    private Matrix invertMatrix = new Matrix();
    private int viewW, viewH;

    // 裁剪框四角 (屏幕坐标): TL, TR, BR, BL
    private final PointF[] handles = new PointF[4];
    private int draggingHandle = -1; // 0~3=角, -1=无, -2=整体拖拽
    private float lastTouchX, lastTouchY;

    private final Paint maskPaint = new Paint();
    private final Paint borderPaint = new Paint();
    private final Paint handlePaint = new Paint();

    public CropView(Context context) {
        this(context, null);
    }

    public CropView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CropView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init();
    }

    private void init() {
        maskPaint.setColor(Color.argb(80, 0, 0, 0));
        maskPaint.setStyle(Paint.Style.FILL);

        borderPaint.setColor(Color.WHITE);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(3f);

        handlePaint.setColor(Color.argb(120, 255, 255, 255));
        handlePaint.setStyle(Paint.Style.FILL);
        handlePaint.setAntiAlias(true);

        // 初始化 handles 避免 NPE
        for (int i = 0; i < 4; i++) handles[i] = new PointF(0, 0);
    }

    public void setBitmap(Bitmap bmp) {
        if (bmp == null) return;
        this.bitmap = bmp;
        if (viewW > 0 && viewH > 0) {
            // View 已有尺寸, 直接设置矩阵和默认裁剪框
            setupDisplayMatrix();
            initDefaultCrop();
        }
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        viewW = w;
        viewH = h;
        if (bitmap != null) {
            setupDisplayMatrix();
            initDefaultCrop();
        }
    }

    /** fitCenter 缩放 + 居中 */
    private void setupDisplayMatrix() {
        if (bitmap == null || viewW <= 0 || viewH <= 0) return;
        float scale = Math.min(
                (float) viewW / bitmap.getWidth(),
                (float) viewH / bitmap.getHeight());
        float dx = (viewW - bitmap.getWidth() * scale) / 2f;
        float dy = (viewH - bitmap.getHeight() * scale) / 2f;
        displayMatrix = new Matrix();
        displayMatrix.setScale(scale, scale);
        displayMatrix.postTranslate(dx, dy);
        displayMatrix.invert(invertMatrix);
    }

    /** 初始化默认裁剪框: 图片实际显示区域的 90% */
    private void initDefaultCrop() {
        if (bitmap == null || viewW <= 0 || viewH <= 0) return;
        // 图片在屏幕上的显示矩形
        float[] corners = {0, 0, bitmap.getWidth(), bitmap.getHeight()};
        displayMatrix.mapPoints(corners);
        float left = corners[0], top = corners[1], right = corners[2], bottom = corners[3];
        // 内缩 5%
        float insetX = (right - left) * 0.05f;
        float insetY = (bottom - top) * 0.05f;
        left += insetX; top += insetY; right -= insetX; bottom -= insetY;

        handles[0] = new PointF(left, top);       // TL
        handles[1] = new PointF(right, top);      // TR
        handles[2] = new PointF(right, bottom);   // BR
        handles[3] = new PointF(left, bottom);    // BL
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bitmap == null) return;

        // 绘制图片
        canvas.drawBitmap(bitmap, displayMatrix, null);

        // 绘制半透明遮罩 (裁剪框外)
        RectF crop = getCropRectScreen();
        // 四个矩形: 上/下/左/右
        canvas.drawRect(0, 0, viewW, crop.top, maskPaint);
        canvas.drawRect(0, crop.bottom, viewW, viewH, maskPaint);
        canvas.drawRect(0, crop.top, crop.left, crop.bottom, maskPaint);
        canvas.drawRect(crop.right, crop.top, viewW, crop.bottom, maskPaint);

        // 绘制裁剪框边框
        canvas.drawRect(crop, borderPaint);

        // 绘制四角手柄
        for (PointF p : handles) {
            canvas.drawCircle(p.x, p.y, HANDLE_RADIUS, handlePaint);
        }
    }

    /** 返回屏幕坐标的裁剪矩形 */
    private RectF getCropRectScreen() {
        float left = Math.min(Math.min(handles[0].x, handles[1].x), Math.min(handles[2].x, handles[3].x));
        float top = Math.min(Math.min(handles[0].y, handles[1].y), Math.min(handles[2].y, handles[3].y));
        float right = Math.max(Math.max(handles[0].x, handles[1].x), Math.max(handles[2].x, handles[3].x));
        float bottom = Math.max(Math.max(handles[0].y, handles[1].y), Math.max(handles[2].y, handles[3].y));
        return new RectF(left, top, right, bottom);
    }

    /** 返回原图像素坐标的裁剪矩形 */
    public Rect getCropRect() {
        if (bitmap == null) return null;
        float[] pts = new float[8];
        for (int i = 0; i < 4; i++) {
            pts[i * 2] = handles[i].x;
            pts[i * 2 + 1] = handles[i].y;
        }
        invertMatrix.mapPoints(pts);
        float left = Float.MAX_VALUE, top = Float.MAX_VALUE;
        float right = Float.MIN_VALUE, bottom = Float.MIN_VALUE;
        for (int i = 0; i < 4; i++) {
            left = Math.min(left, pts[i * 2]);
            top = Math.min(top, pts[i * 2 + 1]);
            right = Math.max(right, pts[i * 2]);
            bottom = Math.max(bottom, pts[i * 2 + 1]);
        }
        // 裁剪到原图范围
        left = Math.max(0, left);
        top = Math.max(0, top);
        right = Math.min(bitmap.getWidth(), right);
        bottom = Math.min(bitmap.getHeight(), bottom);
        if (right - left < 10 || bottom - top < 10) return null;
        return new Rect(
                Math.round(left), Math.round(top),
                Math.round(right), Math.round(bottom));
    }

    /** 直接从显示的 bitmap 裁剪 (坐标已匹配) */
    public Bitmap cropBitmap() {
        Rect rect = getCropRect();
        if (bitmap == null || rect == null) return null;
        return Bitmap.createBitmap(bitmap,
                rect.left, rect.top,
                rect.width(), rect.height());
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
                    // 拖拽角点: 保持矩形约束 (TL/TR/BR/BL 各自只能影响对应的边)
                    moveHandle(draggingHandle, x, y);
                    invalidate();
                } else if (draggingHandle == -2) {
                    // 整体拖拽
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
        // 计算移动后的边界
        float minX = Float.MAX_VALUE, maxX = Float.MIN_VALUE;
        float minY = Float.MAX_VALUE, maxY = Float.MIN_VALUE;
        for (PointF p : handles) {
            minX = Math.min(minX, p.x);
            maxX = Math.max(maxX, p.x);
            minY = Math.min(minY, p.y);
            maxY = Math.max(maxY, p.y);
        }
        // 限制不超出视图
        if (minX + dx < 0) dx = -minX;
        if (maxX + dx > viewW) dx = viewW - maxX;
        if (minY + dy < 0) dy = -minY;
        if (maxY + dy > viewH) dy = viewH - maxY;
        for (PointF p : handles) {
            p.x += dx;
            p.y += dy;
        }
    }

    /**
     * 拖拽角点: 保持矩形约束。
     * TL(0) 影响 left+top, TR(1) 影响 right+top, BR(2) 影响 right+bottom, BL(3) 影响 left+bottom
     */
    private void moveHandle(int handle, float x, float y) {
        x = clamp(x, 0, viewW);
        y = clamp(y, 0, viewH);

        // 当前矩形的 left/right/top/bottom
        float left = Math.min(handles[0].x, handles[3].x);
        float right = Math.max(handles[1].x, handles[2].x);
        float top = Math.min(handles[0].y, handles[1].y);
        float bottom = Math.max(handles[2].y, handles[3].y);

        switch (handle) {
            case 0: // TL
                left = Math.min(x, right - MIN_CROP_SIZE);
                top = Math.min(y, bottom - MIN_CROP_SIZE);
                break;
            case 1: // TR
                right = Math.max(x, left + MIN_CROP_SIZE);
                top = Math.min(y, bottom - MIN_CROP_SIZE);
                break;
            case 2: // BR
                right = Math.max(x, left + MIN_CROP_SIZE);
                bottom = Math.max(y, top + MIN_CROP_SIZE);
                break;
            case 3: // BL
                left = Math.min(x, right - MIN_CROP_SIZE);
                bottom = Math.max(y, top + MIN_CROP_SIZE);
                break;
        }

        // 更新四角, 保持矩形
        handles[0].set(left, top);      // TL
        handles[1].set(right, top);     // TR
        handles[2].set(right, bottom);  // BR
        handles[3].set(left, bottom);   // BL
    }

    private int findHandle(float x, float y) {
        // 先检查角点
        for (int i = 0; i < 4; i++) {
            if (Math.hypot(x - handles[i].x, y - handles[i].y) < TOUCH_TOLERANCE) {
                return i;
            }
        }
        // 再检查是否在裁剪框内 (整体拖拽)
        RectF crop = getCropRectScreen();
        if (crop.contains(x, y)) {
            return -2;
        }
        return -1;
    }
}
