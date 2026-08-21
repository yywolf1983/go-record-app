package com.gosgf.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.gosgf.app.util.RecognitionSettings;
import com.gosgf.app.view.CornerAdjustView;

import java.io.IOException;
import java.io.InputStream;

/**
 * 角点调整 Activity(前置流程): 加载原图 → 自动检测棋盘四角 →
 * 标在图片上让用户手动拖拽调整 → 确认后保存图片并返回调整后的四角。
 *
 * 角点调整本身就界定了棋盘区域(四角围成的四边形), {@link com.gosgf.app.util.MokuRecognizer#recognizeWithCorners}
 * 内部用这四角做 homography 透视校正, 因此不再需要单独的裁剪环节。
 *
 * 后续识别流程不变: MainActivity 拿到四角后调用 recognizeWithCorners。
 */
public class CornerAdjustActivity extends AppCompatActivity {
    private static final String TAG = "CornerAdjustActivity";

    public static final String EXTRA_INPUT_URI = "input_uri";
    /** 返回: 保存的图片路径(已含 EXIF 旋转, 与角点坐标系一致) */
    public static final String EXTRA_IMAGE_PATH = "image_path";
    /** 返回: 四角 x 坐标(原图像素), 顺序 TL→TR→BR→BL */
    public static final String EXTRA_CORNER_XS = "corner_xs";
    /** 返回: 四角 y 坐标(原图像素), 顺序 TL→TR→BR→BL */
    public static final String EXTRA_CORNER_YS = "corner_ys";

    private CornerAdjustView cornerAdjustView;
    private ProgressBar progressBar;
    private TextView tvHint;
    private Uri inputUri;
    private Bitmap bitmap;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_corner_adjust);

        cornerAdjustView = findViewById(R.id.cornerAdjustView);
        progressBar = findViewById(R.id.progressBar);
        tvHint = findViewById(R.id.tvHint);
        Button btnCancel = findViewById(R.id.btnCancelCorner);
        Button btnConfirm = findViewById(R.id.btnConfirmCorner);

        inputUri = getIntent().getParcelableExtra(EXTRA_INPUT_URI);
        if (inputUri == null) {
            Toast.makeText(this, "无法加载图片", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // 异步加载图片(流式解码 + EXIF 旋转, 与 MainActivity.loadBitmapFromUri 对齐)
        new Thread(() -> {
            Bitmap bmp = loadBitmapWithExif(inputUri);
            if (bmp == null) {
                runOnUiThread(() -> {
                    Toast.makeText(this, "无法读取图片", Toast.LENGTH_SHORT).show();
                    finish();
                });
                return;
            }
            // 内存保护: 缩放到最长边 1280(角点调整显示用, 不影响识别精度)
            int maxEdge = 1280;
            int w = bmp.getWidth();
            int h = bmp.getHeight();
            if (Math.max(w, h) > maxEdge) {
                float scale = (float) maxEdge / Math.max(w, h);
                Bitmap scaled = Bitmap.createScaledBitmap(bmp,
                        Math.round(w * scale), Math.round(h * scale), true);
                bmp.recycle();
                bmp = scaled;
            }
            bitmap = bmp;
            runOnUiThread(() -> {
                cornerAdjustView.setBitmap(bitmap);
                // 图片就绪后异步检测角点
                detectCornersAsync();
            });
        }).start();

        btnCancel.setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });

        btnConfirm.setOnClickListener(v -> {
            float[][] corners = cornerAdjustView.getCorners();
            if (corners == null) {
                Toast.makeText(this, "角点无效", Toast.LENGTH_SHORT).show();
                return;
            }
            // 保存当前 bitmap 到临时文件, 保证角点坐标与识别时加载的 bitmap 尺寸一致
            String outPath = new java.io.File(getCacheDir(),
                    "corner_adjusted_" + System.currentTimeMillis() + ".jpg").getAbsolutePath();
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(outPath)) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, fos);
            } catch (IOException e) {
                Log.e(TAG, "保存角点调整图失败", e);
                Toast.makeText(this, "保存失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                return;
            }
            float[] xs = new float[4];
            float[] ys = new float[4];
            for (int i = 0; i < 4; i++) {
                xs[i] = corners[i][0];
                ys[i] = corners[i][1];
            }
            android.content.Intent result = new android.content.Intent();
            result.putExtra(EXTRA_IMAGE_PATH, outPath);
            result.putExtra(EXTRA_CORNER_XS, xs);
            result.putExtra(EXTRA_CORNER_YS, ys);
            setResult(RESULT_OK, result);
            finish();
        });
    }

    /**
     * 流式解码 + EXIF 旋转(与 MainActivity.loadBitmapFromUri 一致)。
     * 关键: 必须应用 EXIF 方向, 否则竖拍照片方向错误, 角点与识别坐标系不一致。
     */
    private Bitmap loadBitmapWithExif(Uri uri) {
        // 第一遍: 只取尺寸
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        try (InputStream in1 = getContentResolver().openInputStream(uri)) {
            if (in1 == null) return null;
            BitmapFactory.decodeStream(in1, null, opts);
        } catch (Exception e) {
            Log.w(TAG, "decode bounds 失败: " + e.getMessage());
            return null;
        }

        // 缩放到最长边 1280(inSampleSize)
        int maxEdge = 1280;
        int sample = 1;
        while (opts.outWidth / sample > maxEdge || opts.outHeight / sample > maxEdge) {
            sample *= 2;
        }
        // 第二遍: 真解码
        BitmapFactory.Options dec = new BitmapFactory.Options();
        dec.inSampleSize = sample;
        dec.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bmp;
        try (InputStream in2 = getContentResolver().openInputStream(uri)) {
            if (in2 == null) return null;
            bmp = BitmapFactory.decodeStream(in2, null, dec);
        } catch (Exception e) {
            Log.w(TAG, "decode 失败: " + e.getMessage());
            return null;
        }
        if (bmp == null) return null;

        // EXIF 方向旋转
        int rotation = 0;
        try (InputStream exifIn = getContentResolver().openInputStream(uri)) {
            if (exifIn != null) {
                android.media.ExifInterface exif = new android.media.ExifInterface(exifIn);
                switch (exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,
                        android.media.ExifInterface.ORIENTATION_NORMAL)) {
                    case android.media.ExifInterface.ORIENTATION_ROTATE_90:
                        rotation = 90; break;
                    case android.media.ExifInterface.ORIENTATION_ROTATE_180:
                        rotation = 180; break;
                    case android.media.ExifInterface.ORIENTATION_ROTATE_270:
                        rotation = 270; break;
                    default: break;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "EXIF 读取失败(按无旋转处理): " + e.getMessage());
        }
        if (rotation != 0) {
            Matrix matrix = new Matrix();
            matrix.postRotate(rotation);
            Bitmap rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), matrix, true);
            bmp.recycle();
            bmp = rotated;
        }
        return bmp;
    }

    /** 异步加载模型 + 检测角点 */
    private void detectCornersAsync() {
        progressBar.setVisibility(View.VISIBLE);
        tvHint.setVisibility(View.GONE);
        new Thread(() -> {
            float[][] corners = null;
            Exception err = null;
            try {
                com.gosgf.app.util.MokuRecognizer recognizer = ensureMokuLoaded();
                if (recognizer != null && recognizer.isReady()) {
                    corners = recognizer.detectCorners(bitmap,
                            RecognitionSettings.load(this));
                }
            } catch (Exception e) {
                err = e;
                Log.e(TAG, "角点检测失败: " + e.getMessage(), e);
            }
            final float[][] finalCorners = corners;
            final Exception finalErr = err;
            runOnUiThread(() -> {
                progressBar.setVisibility(View.GONE);
                tvHint.setVisibility(View.VISIBLE);
                if (finalCorners != null) {
                    cornerAdjustView.setCorners(finalCorners);
                    Log.i(TAG, "角点检测完成: " + cornersToString(finalCorners));
                } else {
                    // 检测失败: 用默认角点, 用户手动调整
                    Log.w(TAG, "角点检测失败, 使用默认角点: "
                            + (finalErr != null ? finalErr.getMessage() : "unknown"));
                    Toast.makeText(this, "自动检测失败, 请手动调整四角",
                            Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }

    /** 加载 ONNX 模型(从 assets 复制到 cache 后初始化) */
    private com.gosgf.app.util.MokuRecognizer ensureMokuLoaded() {
        try {
            java.io.File cacheFile = new java.io.File(getCacheDir(), "moku.onnx");
            if (!cacheFile.exists() || cacheFile.length() == 0) {
                try (java.io.InputStream is = getAssets().open("moku.onnx");
                     java.io.FileOutputStream fos = new java.io.FileOutputStream(cacheFile)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
                }
                Log.i(TAG, "ONNX 模型已复制到 cache: " + cacheFile.length() + " bytes");
            }
            com.gosgf.app.util.MokuRecognizer recognizer =
                    new com.gosgf.app.util.MokuRecognizer();
            recognizer.init(cacheFile.getAbsolutePath());
            Log.i(TAG, "MokuRecognizer 初始化完成");
            return recognizer;
        } catch (Exception e) {
            Log.e(TAG, "ensureMokuLoaded 失败: " + e.getMessage(), e);
            return null;
        }
    }

    private static String cornersToString(float[][] c) {
        if (c == null) return "null";
        StringBuilder sb = new StringBuilder();
        String[] names = {"TL", "TR", "BR", "BL"};
        for (int i = 0; i < 4; i++) {
            sb.append(names[i]).append("(")
              .append((int) c[i][0]).append(",").append((int) c[i][1]).append(") ");
        }
        return sb.toString();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (bitmap != null && !bitmap.isRecycled()) {
            bitmap.recycle();
            bitmap = null;
        }
    }
}
