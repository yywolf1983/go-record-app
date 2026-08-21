package com.gosgf.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.gosgf.app.view.CropView;

import java.io.IOException;
import java.io.InputStream;

/**
 * 手动裁剪 Activity: 用户拖拽四角框选棋盘区域, 确认后裁剪并保存到临时文件。
 * 返回裁剪后图片路径。
 */
public class CropActivity extends AppCompatActivity {
    private static final String TAG = "CropActivity";

    public static final String EXTRA_INPUT_URI = "input_uri";
    public static final String EXTRA_OUTPUT_PATH = "output_path";

    private CropView cropView;
    private Uri inputUri;
    private float currentRotation = 0f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_crop);

        cropView = findViewById(R.id.cropView);
        Button btnRotate = findViewById(R.id.btnRotate);
        Button btnConfirm = findViewById(R.id.btnConfirmCrop);
        Button btnCancel = findViewById(R.id.btnCancelCrop);

        inputUri = getIntent().getParcelableExtra(EXTRA_INPUT_URI);
        if (inputUri == null) {
            Toast.makeText(this, "无法加载图片", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        loadImage(inputUri, currentRotation);

        btnRotate.setOnClickListener(v -> {
            currentRotation = (currentRotation + 90f) % 360f;
            loadImage(inputUri, currentRotation);
        });

        btnConfirm.setOnClickListener(v -> {
            android.graphics.Rect cropRect = cropView.getCropRect();
            if (cropRect == null) {
                Toast.makeText(this, "裁剪区域无效", Toast.LENGTH_SHORT).show();
                return;
            }
            // 直接用 CropView 中显示的 bitmap 裁剪 (坐标已匹配)
            Bitmap cropped = cropView.cropBitmap();
            if (cropped == null) {
                Toast.makeText(this, "裁剪失败", Toast.LENGTH_SHORT).show();
                return;
            }

            // 保存到临时文件
            java.io.File outFile = new java.io.File(getCacheDir(), "cropped_" + System.currentTimeMillis() + ".jpg");
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(outFile)) {
                cropped.compress(Bitmap.CompressFormat.JPEG, 95, fos);
            } catch (IOException e) {
                Log.e(TAG, "保存裁剪图失败", e);
                Toast.makeText(this, "保存失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                return;
            }
            cropped.recycle();

            Intent result = new Intent();
            result.putExtra(EXTRA_OUTPUT_PATH, outFile.getAbsolutePath());
            setResult(RESULT_OK, result);
            finish();
        });

        btnCancel.setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });
    }

    private void loadImage(Uri uri, float rotation) {
        new Thread(() -> {
            Bitmap bmp = loadFullBitmap(uri, rotation);
            if (bmp == null) {
                runOnUiThread(() -> {
                    Toast.makeText(this, "无法加载图片", Toast.LENGTH_SHORT).show();
                    finish();
                });
                return;
            }
            // 缩放到适合屏幕的大小 (最长边 1280)
            int maxEdge = 1280;
            int w = bmp.getWidth();
            int h = bmp.getHeight();
            if (Math.max(w, h) > maxEdge) {
                float scale = (float) maxEdge / Math.max(w, h);
                int nw = Math.round(w * scale);
                int nh = Math.round(h * scale);
                Bitmap scaled = Bitmap.createScaledBitmap(bmp, nw, nh, true);
                bmp.recycle();
                bmp = scaled;
            }
            final Bitmap finalBmp = bmp;
            runOnUiThread(() -> cropView.setBitmap(finalBmp));
        }).start();
    }

    /** 全分辨率加载, 支持 EXIF 旋转 */
    private Bitmap loadFullBitmap(Uri uri, float rotation) {
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bmp = BitmapFactory.decodeStream(is, null, opts);
            if (bmp == null) return null;

            // EXIF 旋转
            if (rotation != 0) {
                Matrix matrix = new Matrix();
                matrix.postRotate(rotation);
                Bitmap rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), matrix, true);
                bmp.recycle();
                bmp = rotated;
            }
            return bmp;
        } catch (IOException e) {
            Log.e(TAG, "loadFullBitmap 失败", e);
            return null;
        }
    }
}
