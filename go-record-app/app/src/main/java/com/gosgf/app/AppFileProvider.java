package com.gosgf.app;

import androidx.core.content.FileProvider;

/**
 * 自定义 FileProvider 子类, 仅用于让本工程的 {@code <provider>} 与
 * registration-lib.aar 中同名的 androidx.core.content.FileProvider 在
 * Manifest 合并时拥有不同的 android:name, 从而作为两个独立 provider 并存,
 * 各自的 authorities/file_paths 互不干扰。无需任何额外实现。
 */
public class AppFileProvider extends FileProvider {
}
