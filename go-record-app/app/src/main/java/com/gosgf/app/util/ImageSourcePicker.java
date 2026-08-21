package com.gosgf.app.util;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.MediaStore;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 图片来源选择器：相机拍照 + 相册选图。
 *
 * 调用方使用 ActivityResultLauncher<Intent> 接收回调,
 * 然后通过 resolvePickedUri(intent) 取得图片 Uri。
 *
 * 相机拍照返回的 Uri 直接是构造时创建的临时文件 Uri (mimeType image/jpeg)。
 * 相册选图返回的 Uri 通过 intent.getData() 获取,需要持久化读权限。
 *
 * 移除只需：删本类 + 删调用方相关 launcher/按钮。
 */
public final class ImageSourcePicker {

    public static final String AUTHORITY_SUFFIX = ".fileprovider";
    public static final String CAMERA_DIR = "capture_images";

    /** 调起相机拍照。输出到 cacheDir/capture_images/ 目录下的 JPEG 文件,返回该文件 Uri。 */
    public static Uri createCameraIntent(Context ctx, Intent outIntent, String fileName) {
        File dir = new File(ctx.getCacheDir(), CAMERA_DIR);
        if (!dir.exists()) dir.mkdirs();
        File outFile = new File(dir, fileName + ".jpg");
        Uri photoUri = FileProvider.getUriForFile(ctx,
                ctx.getPackageName() + AUTHORITY_SUFFIX, outFile);
        outIntent.setAction(MediaStore.ACTION_IMAGE_CAPTURE);
        outIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri);
        outIntent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        return photoUri;
    }

    /** 调起相册选图。 */
    public static void createGalleryIntent(Intent outIntent) {
        outIntent.setAction(Intent.ACTION_GET_CONTENT);
        outIntent.setType("image/*");
        outIntent.addCategory(Intent.CATEGORY_OPENABLE);
    }

    /**
     * 解析 ActivityResult 返回的 Intent 取得图片 Uri。
     */
    public static Uri resolvePickedUri(Intent data) {
        if (data == null) return null;
        if (data.getData() != null) return data.getData();
        // 某些相册应用返回 ClipData
        if (data.getClipData() != null && data.getClipData().getItemCount() > 0) {
            return data.getClipData().getItemAt(0).getUri();
        }
        return null;
    }

    /** 把 Uri 内容拷贝到目标输出流(便于做后续处理或转存)。 */
    public static void copyUriToStream(Context ctx, Uri src, OutputStream out)
            throws IOException {
        try (InputStream in = ctx.getContentResolver().openInputStream(src)) {
            if (in == null) throw new IOException("无法打开 Uri 输入流: " + src);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private ImageSourcePicker() {}
}
