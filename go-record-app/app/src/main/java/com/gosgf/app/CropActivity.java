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

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * 自定义裁剪界面：用户在图片上圈定棋盘大致方位（拖动/四角缩放选框），
 * 点「确定」后把选定区域裁剪为图片，以临时 Uri 通过 setResult 回传。
 * 识别侧默认按 19 路棋盘处理。
 */
public class CropActivity extends Activity {

    public static final String EXTRA_IMAGE_URI = "extra_image_uri";
    public static final String EXTRA_CROP_URI = "extra_crop_uri";
    private static final String TAG = "CropActivity";
    private static final int MAX_DIM = 2560; // 解码上限,避免 OOM

    private ImageView imageView;
    private CropOverlayView overlay;
    private Uri sourceUri;
    private Bitmap fullBitmap; // 受控尺寸的解码图(用于裁剪)

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_crop);

        imageView = findViewById(R.id.cropImageView);
        overlay = findViewById(R.id.cropOverlay);
        Button btnOk = findViewById(R.id.cropBtnOk);
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

        btnOk.setOnClickListener(v -> doCrop());
        btnCancel.setOnClickListener(v -> finish());
    }

    private void initOverlayRect() {
        // 计算 fitCenter 下 drawable 在 ImageView 中的显示矩形
        RectF rect = computeImageRect();
        overlay.setImageRect(rect, fullBitmap.getWidth(), fullBitmap.getHeight());
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
