package com.gosgf.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.gosgf.app.view.CropOverlayView;
import com.gosgf.app.view.QuadOverlayView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * 自定义裁剪界面：两种模式——
 * 1. 正方形裁剪:圈定棋盘大致方位,点「确定」把区域裁剪为图片回传(常规流程)。
 * 2. 四角校正:四个角可拖到任意位置(任意四边形),对准透视变形棋盘的四个角,
 *    点「校正并识别」把原图 + 四角坐标回传,识别侧用该四角做透视校正。
 * 识别侧默认按 19 路棋盘处理。
 */
public class CropActivity extends Activity {

    public static final String EXTRA_IMAGE_URI = "extra_image_uri";
    public static final String EXTRA_CROP_URI = "extra_crop_uri";
    /** 四角校正模式:回传的角点坐标 float[8] = TLx,TLy,TRx,TRy,BRx,BRy,BLx,BLy。
     *  坐标为归一化(0~1, 相对原图),因两侧解码尺寸可能不同(2560 vs 1600),用比例传递最稳。 */
    public static final String EXTRA_CORNERS = "extra_corners";
    private static final String TAG = "CropActivity";
    private static final int MAX_DIM = 2560; // 解码上限,避免 OOM

    private ImageView imageView;
    private CropOverlayView overlay;
    private QuadOverlayView quadOverlay;
    private Button btnMode;
    private Button btnOk;
    private boolean quadMode = false; // false=正方形裁剪, true=四角校正
    private Uri sourceUri;
    private Bitmap fullBitmap; // 受控尺寸的解码图(用于裁剪)

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_crop);

        imageView = findViewById(R.id.cropImageView);
        overlay = findViewById(R.id.cropOverlay);
        quadOverlay = findViewById(R.id.quadOverlay);
        btnMode = findViewById(R.id.cropBtnMode);
        btnOk = findViewById(R.id.cropBtnOk);
        Button btnCancel = findViewById(R.id.cropBtnCancel);

        sourceUri = getIntent().getParcelableExtra(EXTRA_IMAGE_URI);
        if (sourceUri == null) {
            Toast.makeText(this, "图片为空", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        fullBitmap = decodeControlled(sourceUri);
        if (fullBitmap == null) {
            Toast.makeText(this, "无法读取图片", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        imageView.setImageBitmap(fullBitmap);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);

        // 等布局完成后再计算 drawable 显示矩形,初始化选框
        imageView.post(() -> initOverlayRect());

        btnMode.setOnClickListener(v -> toggleMode());
        btnOk.setOnClickListener(v -> doCrop());
        btnCancel.setOnClickListener(v -> finish());
    }

    private void initOverlayRect() {
        // 计算 fitCenter 下 drawable 在 ImageView 中的显示矩形
        RectF rect = computeImageRect();
        overlay.setImageRect(rect, fullBitmap.getWidth(), fullBitmap.getHeight());
        quadOverlay.setImageRect(rect, fullBitmap.getWidth(), fullBitmap.getHeight());
    }

    /** 切换 正方形裁剪 ↔ 四角校正 模式。 */
    private void toggleMode() {
        quadMode = !quadMode;
        overlay.setVisibility(quadMode ? View.GONE : View.VISIBLE);
        quadOverlay.setVisibility(quadMode ? View.VISIBLE : View.GONE);
        btnMode.setText(quadMode ? "正方形裁剪" : "四角校正");
        btnOk.setText(quadMode ? "校正并识别" : "确定裁剪");
        btnOk.setBackgroundTintList(quadMode
                ? android.content.res.ColorStateList.valueOf(0xFFF59E0B)
                : android.content.res.ColorStateList.valueOf(0xFF2DD4BF));
    }

    /** 计算 fitCenter 后 drawable 在 ImageView 中的实际显示矩形（ImageView 本地坐标）。 */
    private RectF computeImageRect() {
        int ivW = imageView.getWidth();
        int ivH = imageView.getHeight();
        int bw = fullBitmap.getWidth();
        int bh = fullBitmap.getHeight();
        float scale = Math.min((float) ivW / bw, (float) ivH / bh);
        float dispW = bw * scale;
        float dispH = bh * scale;
        float left = (ivW - dispW) / 2f;
        float top = (ivH - dispH) / 2f;
        return new RectF(left, top, left + dispW, top + dispH);
    }

    /** 解码受控尺寸的完整 Bitmap（仅用于裁剪，识别仍用原流程）。 */
    private Bitmap decodeControlled(Uri uri) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, opts);
            }
            int bw = opts.outWidth, bh = opts.outHeight;
            int sample = 1;
            while (Math.max(bw, bh) / sample > MAX_DIM) sample *= 2;
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = sample;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                return BitmapFactory.decodeStream(in, null, opts);
            }
        } catch (Exception e) {
            Log.e(TAG, "decodeControlled 失败", e);
            return null;
        }
    }

    private void doCrop() {
        if (quadMode) {
            doQuadAdjust();
            return;
        }
        int[] r = overlay.getCropRectInImage();
        int left = r[0], top = r[1], right = r[2], bottom = r[3];
        int w = right - left;
        int h = bottom - top;
        if (w <= 0 || h <= 0) {
            Toast.makeText(this, "请先框选棋盘区域", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            // 受控解码图上的裁剪坐标（fullBitmap 与原图同比例，直接用它裁剪即可）
            Bitmap cropped = Bitmap.createBitmap(fullBitmap,
                    clamp(left, fullBitmap.getWidth()), clamp(top, fullBitmap.getHeight()),
                    clampSize(w, left, fullBitmap.getWidth()),
                    clampSize(h, top, fullBitmap.getHeight()));

            File dir = new File(getCacheDir(), "capture_images");
            if (!dir.exists()) dir.mkdirs();
            File outFile = new File(dir, "board_" + System.currentTimeMillis() + "_crop.jpg");
            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                cropped.compress(Bitmap.CompressFormat.JPEG, 95, fos);
            }
            Uri cropUri = FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", outFile);
            Intent data = new Intent();
            data.putExtra(EXTRA_CROP_URI, cropUri.toString());
            setResult(RESULT_OK, data);
            finish();
        } catch (Exception e) {
            Log.e(TAG, "doCrop 失败", e);
            Toast.makeText(this, "裁剪失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 四角校正:不裁剪图片,回传 原图 uri + 用户拖拽的四角(原图像素, TL→TR→BR→BL)。
     * 识别侧用该四角做透视校正,应对拍照透视变形(棋盘不是正四边形)。
     */
    private void doQuadAdjust() {
        try {
            float[][] c = quadOverlay.getCornersInImage();
            float[] flat = new float[8];
            // 归一化到 0~1:识别侧解码尺寸可能与这里不同(2560 vs 1600),用比例传递
            float iw = fullBitmap.getWidth();
            float ih = fullBitmap.getHeight();
            for (int i = 0; i < 4; i++) {
                flat[i * 2] = c[i][0] / iw;
                flat[i * 2 + 1] = c[i][1] / ih;
            }
            Intent data = new Intent();
            data.putExtra(EXTRA_IMAGE_URI, sourceUri.toString());
            data.putExtra(EXTRA_CORNERS, flat);
            setResult(RESULT_OK, data);
            finish();
        } catch (Exception e) {
            Log.e(TAG, "doQuadAdjust 失败", e);
            Toast.makeText(this, "校正失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private static int clamp(int v, int max) {
        return Math.max(0, Math.min(v, max - 1));
    }

    private static int clampSize(int size, int origin, int max) {
        return Math.max(1, Math.min(size, max - origin));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (fullBitmap != null && !fullBitmap.isRecycled()) fullBitmap.recycle();
    }
}
