package com.gosgf.app.util;

import android.graphics.Bitmap;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/**
 * Moku 围棋棋盘识别器：基于 kaya-go/moku-v3 RT-DETR ONNX 模型。
 *
 * 算法 1:1 移植自 Kaya 项目 packages/board-recognition/src/{moku-detector,moku-postprocess,
 * perspective,corners}.ts，全部纯 Java 实现，不依赖 OpenCV。
 *
 * 流程：
 *   Bitmap → 预处理(双线性 resize 到 640×640, /255 归一化) → ONNX 推理
 *         → 后处理(logits sigmoid + argmax, pred_boxes 解码)
 *         → 棋盘 4 角去重/补全/排序(TL→TR→BR→BL)
 *         → 单应性变换映射棋子到 19×19 网格
 *         → 返回 int[19][19] (0=空, 1=黑, 2=白)
 *
 * 移除只需：删 build.gradle 的 onnxruntime-android 依赖 + 删本类 + 删 assets/moku.onnx。
 */
public class MokuRecognizer {
    private static final String TAG = "MokuRecognizer";

    // 模型常量
    public static final int INPUT_SIZE = 640;
    public static final int NUM_QUERIES = 300;
    public static final int NUM_CLASSES = 3;
    public static final int CLASS_BLACK_STONE = 0;
    public static final int CLASS_WHITE_STONE = 1;
    public static final int CLASS_BOARD_CORNER = 2;
    public static final float DEFAULT_THRESHOLD = 0.150f;
    // 全空重试阈值:高阈值漏检(一个棋子都没识别到)时,用较低阈值重跑一次保召回
    // (低阈值可能引入少量误检,但相比"完全识别不到"更可接受,且用户可手动摆子修正)
    private static final float LOW_RETRY_THRESHOLD = 0.050f;
    // 类别感知阈值:黑棋对比度通常更高,可用略高阈值过滤误检;
    // 白棋在木色棋盘上对比度较低,维持较低阈值保证召回
    private static final float BLACK_STONE_THRESHOLD_BIAS = +0.003f;
    private static final float WHITE_STONE_THRESHOLD_BIAS = -0.020f;
    private static final float CORNER_MIN_THRESHOLD = 0.005f;
    // WBF (Weighted Boxes Fusion) 参数:替代 NMS,对 TTA 多通道检测框加权平均
    private static final float WBF_IOU_THRESHOLD = 0.55f;
    private static final int BOARD_SIZE = 19;

    // 与 GoBoard/BoardLogic 内部一致：0=空, 1=黑, 2=白
    public static final int EMPTY = 0;
    public static final int BLACK = 1;
    public static final int WHITE = 2;

    private final OrtEnvironment env;
    private OrtSession session;

    // 当前识别使用的可调设置(从设置页读取,避免写死常量)
    private RecognitionSettings settings = new RecognitionSettings();

    /** 识别结果：19×19 棋盘矩阵 + 调试信息。 */
    public static class RecognitionResult {
        public int[][] board;          // [BOARD_SIZE][BOARD_SIZE], 0=空/1=黑/2=白
        public boolean cornersDetected;
        public int blackCount;
        public int whiteCount;
        public String message;
        /** 使用的棋盘四角(原图像素坐标, 顺序 TL→TR→BR→BL), 自动检测或手动注入。 */
        public float[][] corners;
    }

    public MokuRecognizer() {
        env = OrtEnvironment.getEnvironment();
    }

    /** 加载 ONNX 模型。modelBytes 为 model.onnx 文件的字节数组。 */
    public void init(byte[] modelBytes) throws Exception {
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        // CPU 即可，避免复杂 GPU 初始化。RT-DETR-r18vd 在中端 Android 上推理 ~1s。
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        session = env.createSession(modelBytes, opts);
        Log.i(TAG, "Moku 模型加载完成(byte[]), 输入: " + session.getInputNames()
                + " 输出: " + session.getOutputNames());
    }

    /**
     * 用文件路径加载 ONNX 模型（推荐用法）。
     * 相比 init(byte[])，避免把 77MB 模型一次性读进 Java 堆,
     * ONNX Runtime native 层直接 mmap 文件,堆占用 ~0。
     */
    public void init(String modelPath) throws Exception {
        long t0 = System.currentTimeMillis();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        session = env.createSession(modelPath, opts);
        long t1 = System.currentTimeMillis();
        Log.i(TAG, "Moku 模型加载完成(path=" + modelPath + ") 耗时" + (t1 - t0) + "ms"
                + ", 输入: " + session.getInputNames()
                + ", 输出: " + session.getOutputNames());
    }

    public boolean isReady() {
        return session != null;
    }

    /** 释放 ONNX session 与 native 资源。 */
    public void close() {
        if (session != null) {
            try { session.close(); } catch (ai.onnxruntime.OrtException ignored) {}
            session = null;
        }
    }

    /**
     * 识别一张围棋棋盘图片。
     *
     * @param bitmap 输入图像（任意尺寸，会被内部 resize 到 640×640）
     * @return 19×19 棋盘矩阵结果
     */
    public RecognitionResult recognize(Bitmap bitmap) throws Exception {
        return recognize(bitmap, DEFAULT_THRESHOLD, new RecognitionSettings());
    }

    public RecognitionResult recognize(Bitmap bitmap, float threshold) throws Exception {
        return recognize(bitmap, threshold, new RecognitionSettings());
    }

    /** 使用自定义识别设置(来自设置页)进行识别。 */
    public RecognitionResult recognize(Bitmap bitmap, RecognitionSettings rs) throws Exception {
        return recognize(bitmap, DEFAULT_THRESHOLD, rs);
    }

    /**
     * @param alreadyCropped true 表示传入图已经是"棋盘区域"(用户手动裁剪/系统裁剪得到),
     *                       此时跳过阶段 2 的"按检测棋子自动再裁剪"——因为该步骤基于模型检测到的
     *                       棋子算 bbox,若边角未被检测到,会自动裁剪框会比真实棋盘小,反而把
     *                       最外圈交叉点切到图外导致边角缺失。手动裁剪图直接用整图 TTA 识别即可,
     *                       棋盘四边都在图内,边角更完整。
     */
    public RecognitionResult recognize(Bitmap bitmap, float threshold,
                                        boolean alreadyCropped) throws Exception {
        return recognize(bitmap, threshold, alreadyCropped, new RecognitionSettings());
    }

    public RecognitionResult recognize(Bitmap bitmap, float threshold,
                                        RecognitionSettings rs) throws Exception {
        return recognize(bitmap, threshold, false, rs);
    }

    public RecognitionResult recognize(Bitmap bitmap, float threshold,
                                        boolean alreadyCropped,
                                        RecognitionSettings rs) throws Exception {
        if (session == null) throw new IllegalStateException("MokuRecognizer 未初始化");
        this.settings = (rs != null) ? rs : new RecognitionSettings();
        final Bitmap rawBitmap = bitmap; // 保存原始图,供低阈值重试时使用

        int srcW = bitmap.getWidth();
        int srcH = bitmap.getHeight();
        Log.i(TAG, "=== 识别开始 === src=" + srcW + "x" + srcH
                + ", threshold=" + threshold);

        // 内存保护:仅在尺寸极大时轻度缩放,避免 OOM。
        // 注意:这不是识别压缩——识别精度靠"按棋盘区域自动裁剪"保证,
        // 预缩放仅用于让阶段1的 bbox 检测能在合理分辨率上进行。
        final int MAX_DIM = 2560;
        if (Math.max(srcW, srcH) > MAX_DIM) {
            float ds = (float) MAX_DIM / Math.max(srcW, srcH);
            int nw = Math.round(srcW * ds);
            int nh = Math.round(srcH * ds);
            bitmap = Bitmap.createScaledBitmap(bitmap, nw, nh, true);
            srcW = nw;
            srcH = nh;
            Log.d(TAG, "大图内存保护缩放: → " + srcW + "x" + srcH);
        }

        // 图像增强:对比度拉伸,改善光照不均/低对比度
        bitmap = enhanceContrast(bitmap);
        srcW = bitmap.getWidth();
        srcH = bitmap.getHeight();

        // === 阶段 1: 粗推理, 用棋子 bbox 自动定位棋盘 ===
        long t1 = System.currentTimeMillis();
        PreprocessResult pp1 = preprocess(bitmap, srcW, srcH);
        float[][] out1 = runInference(pp1.input);
        Log.d(TAG, "阶段 1 推理耗时" + (System.currentTimeMillis() - t1) + "ms"
                + String.format(", letterbox: scale=%.3f pad=(%d,%d) new=%dx%d",
                        pp1.lb.scale, pp1.lb.padX, pp1.lb.padY, pp1.lb.newW, pp1.lb.newH));

        // 计算棋子 bbox (粗略, 只用于裁剪定位) - 用 letterbox 坐标映射
        float[] bbox = computeStonesBBox(out1[0], out1[1], srcW, srcH, threshold, pp1.lb);

        // 手动裁剪的图:已经是棋盘区域,直接整图 TTA 识别,跳过阶段 2 自动再裁剪。
        // (阶段 2 的"按检测棋子自动再裁剪"会把未被检测到的边角切掉 → 边角缺失)
        if (alreadyCropped) {
            Log.i(TAG, "手动裁剪图:跳过阶段2自动再裁剪, 直接整图 TTA 识别(保边角)");
            RecognitionResult rr = postprocessWithTTA(out1, bitmap, pp1, srcW, srcH, threshold,
                    true, rs);
            return maybeRetry(rawBitmap, rr, threshold, rs, null);
        }

        // 棋子 bbox 覆盖范围足够小 (棋盘只占图像一部分) → 自动裁剪 + 重新推理
        // 否则直接用阶段 1 结果做完整后处理
        if (bbox == null) {
            Log.i(TAG, "阶段 1 未检测到棋子, 直接后处理+TTA");
            RecognitionResult r1 = postprocessWithTTA(out1, bitmap, pp1, srcW, srcH, threshold, rs);
            return maybeRetry(rawBitmap, r1, threshold, rs, null);
        }

        float minX = bbox[0], minY = bbox[1], maxX = bbox[2], maxY = bbox[3];
        float bw = maxX - minX, bh = maxY - minY;
        float bboxArea = bw * bh;
        float imgArea = srcW * srcH;
        float coverage = bboxArea / imgArea;
        Log.i(TAG, String.format("阶段 1 棋子 bbox: (%.0f,%.0f)-(%.0f,%.0f) 覆盖=%.1f%%",
                minX, minY, maxX, maxY, coverage * 100));

        // 始终按棋盘 bbox 自动裁剪(而不是把整图折叠压缩成正方形再识别):
        // 满屏/竖图拍的棋盘若直接 letterbox 到 640 会被横向压缩变形,
        // 因此无论覆盖率多少,只要定位到棋盘就裁剪出棋盘区域再做精细识别。
        // 多棋子占不满整图(带桌面背景)时裁剪掉背景,棋盘在 640 里占比更大、更清晰。

        // === 阶段 2: 用 bbox 裁剪原图 + 重新推理 (类似手动裁剪, 但全自动) ===
        float pad = Math.max(bw, bh) / 18f * 5f;  // 外扩约 5 个格子,确保边角棋子不被裁掉
        int cropX = Math.max(0, (int) (minX - pad));
        int cropY = Math.max(0, (int) (minY - pad));
        int cropRight = Math.min(srcW, (int) (maxX + pad));
        int cropBottom = Math.min(srcH, (int) (maxY + pad));
        int cropW = cropRight - cropX;
        int cropH = cropBottom - cropY;

        // 退化保护:bbox 误检导致裁剪尺寸过小(≤0)时,回退到整图识别,避免崩溃
        if (cropW <= 0 || cropH <= 0 || cropW * cropH < (srcW * srcH) / 100) {
            Log.w(TAG, "棋盘 bbox 退化(裁剪尺寸 " + cropW + "x" + cropH
                    + "), 回退整图识别");
            return postprocessWithTTA(out1, bitmap, pp1, srcW, srcH, threshold, rs);
        }

        Log.i(TAG, "自动裁剪: pad=" + pad + " → 区域(" + cropX + "," + cropY + ")-("
                + cropRight + "," + cropBottom + ") 尺寸" + cropW + "x" + cropH);

        Bitmap cropped = Bitmap.createBitmap(bitmap, cropX, cropY, cropW, cropH);

        long t2 = System.currentTimeMillis();
        PreprocessResult pp2 = preprocess(cropped, cropW, cropH);
        float[][] out2 = runInference(pp2.input);
        Log.d(TAG, "阶段 2 推理耗时" + (System.currentTimeMillis() - t2) + "ms"
                + " (裁剪后 " + cropW + "x" + cropH + ")"
                + String.format(", letterbox: scale=%.3f pad=(%d,%d)",
                        pp2.lb.scale, pp2.lb.padX, pp2.lb.padY));

        RecognitionResult result = postprocessWithTTA(out2, cropped, pp2, cropW, cropH, threshold, rs);
        Log.i(TAG, "两阶段识别完成 (自动裁剪 " + cropW + "x" + cropH + " + TTA)");
        return maybeRetry(rawBitmap, result, threshold, rs, null);
    }

    /**
     * 手动指定棋盘四角识别(透视校正):
     * 用户拖拽四角对准棋盘四个角后,跳过自动角点检测,直接用给定四角计算 H 矩阵。
     * 适用于透视变形(照片里棋盘不是正四边形)的拍照。
     *
     * @param corners 棋盘四角(原图像素坐标, 顺序 TL→TR→BR→BL)
     */
    public RecognitionResult recognizeWithCorners(Bitmap bitmap, float threshold,
                                                  float[][] corners,
                                                  RecognitionSettings rs) throws Exception {
        if (session == null) throw new IllegalStateException("MokuRecognizer 未初始化");
        this.settings = (rs != null) ? rs : new RecognitionSettings();
        final Bitmap rawBitmap = bitmap; // 低阈值重试用原始图

        int srcW = bitmap.getWidth();
        int srcH = bitmap.getHeight();
        Log.i(TAG, "=== 识别开始(手动四角) === src=" + srcW + "x" + srcH
                + ", threshold=" + threshold);

        // 内存保护缩放(与 recognize 一致)。若发生缩放,注入角点需按同一比例映射。
        float scale = 1f;
        final int MAX_DIM = 2560;
        if (Math.max(srcW, srcH) > MAX_DIM) {
            float ds = (float) MAX_DIM / Math.max(srcW, srcH);
            int nw = Math.round(srcW * ds);
            int nh = Math.round(srcH * ds);
            bitmap = Bitmap.createScaledBitmap(bitmap, nw, nh, true);
            srcW = nw;
            srcH = nh;
            scale = ds;
            Log.d(TAG, "大图内存保护缩放: → " + srcW + "x" + srcH);
        }

        // 图像增强:对比度拉伸(不改尺寸),改善光照不均/低对比度
        bitmap = enhanceContrast(bitmap);
        srcW = bitmap.getWidth();
        srcH = bitmap.getHeight();

        // 把用户角点映射到(可能被缩放的)识别图坐标系
        float[][] scaledCorners = new float[4][2];
        for (int i = 0; i < 4; i++) {
            scaledCorners[i][0] = corners[i][0] * scale;
            scaledCorners[i][1] = corners[i][1] * scale;
        }
        Log.d(TAG, "手动四角注入: "
                + String.format("TL(%.0f,%.0f) TR(%.0f,%.0f) BR(%.0f,%.0f) BL(%.0f,%.0f)",
                    scaledCorners[0][0], scaledCorners[0][1],
                    scaledCorners[1][0], scaledCorners[1][1],
                    scaledCorners[2][0], scaledCorners[2][1],
                    scaledCorners[3][0], scaledCorners[3][1]));

        // 整图 TTA + 注入角点(用户已指定棋盘范围,不做阶段 2 自动再裁剪)
        PreprocessResult pp1 = preprocess(bitmap, srcW, srcH);
        float[][] out1 = runInference(pp1.input);
        RecognitionResult rr = postprocessWithTTA(out1, bitmap, pp1, srcW, srcH, threshold,
                false, rs, scaledCorners);
        // 重试传原始角点(未缩放):重试路径内部会再做一次同比例缩放,避免二次缩放错位
        return maybeRetry(rawBitmap, rr, threshold, rs, corners);
    }

    /**
     * 全空重试:高阈值(抗误检)漏检导致一个棋子都没识别到时,
     * 用较低阈值(保召回)对原始图重新识别一次。低阈值结果不再重试,避免死循环。
     * @param extCorners 手动注入的四角(可能为 null=自动检测),重试时保持一致。
     */
    private RecognitionResult maybeRetry(Bitmap rawBitmap,
                                         RecognitionResult result, float threshold,
                                         RecognitionSettings rs,
                                         float[][] extCorners) {
        if (threshold > LOW_RETRY_THRESHOLD
                && result != null
                && result.blackCount + result.whiteCount == 0) {
            Log.w(TAG, "高阈值(" + threshold + ")未识别到任何棋子, 用低阈值("
                    + LOW_RETRY_THRESHOLD + ")重试");
            try {
                if (extCorners != null) {
                    return recognizeWithCorners(rawBitmap, LOW_RETRY_THRESHOLD, extCorners, rs);
                }
                return recognize(rawBitmap, LOW_RETRY_THRESHOLD, rs);
            } catch (Exception e) {
                Log.e(TAG, "低阈值重试失败, 返回原空结果", e);
            }
        }
        return result;
    }

    /** 单次 ONNX 推理,返回 [logits, predBoxes]。 */
    private float[][] runInference(float[] input) throws Exception {
        long[] shape = {1L, 3L, (long) INPUT_SIZE, (long) INPUT_SIZE};
        java.nio.FloatBuffer inputBuf = java.nio.FloatBuffer.wrap(input);
        OnnxTensor inputTensor = OnnxTensor.createTensor(env, inputBuf, shape);
        try {
            Map<String, OnnxTensor> feeds = new HashMap<>();
            feeds.put("pixel_values", inputTensor);
            try (OrtSession.Result result = session.run(feeds)) {
                Object logitsVal = result.get("logits").get().getValue();
                Object boxesVal = result.get("pred_boxes").get().getValue();
                float[] logits = flattenFloatArray(logitsVal, NUM_QUERIES * NUM_CLASSES);
                float[] predBoxes = flattenFloatArray(boxesVal, NUM_QUERIES * 4);
                Log.d(TAG, "ONNX 推理: logits=" + logitsVal.getClass().getSimpleName()
                        + "→len=" + logits.length + ", predBoxes=" + boxesVal.getClass().getSimpleName()
                        + "→len=" + predBoxes.length);
                return new float[][]{logits, predBoxes};
            }
        } finally {
            inputTensor.close();
        }
    }

    /** 水平翻转 Bitmap (用于 TTA)。 */
    private static Bitmap flipHorizontal(Bitmap src) {
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.preScale(-1, 1);
        return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
    }

    /** 垂直翻转 Bitmap (用于 TTA)。 */
    private static Bitmap flipVertical(Bitmap src) {
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.preScale(1, -1);
        return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
    }

    /**
     * 4 路 TTA (Test Time Augmentation): 原图 + 水平翻转 + 垂直翻转 + 180°旋转,
     * 合并 4 组检测结果 (1200 query) 后 postprocess。
     * 利用棋盘 4 重对称性,让 4 个视角互相投票补全漏检。
     *   水平翻转: cx → 1-cx
     *   垂直翻转: cy → 1-cy
     *   180°旋转: cx → 1-cx, cy → 1-cy
     */
    private RecognitionResult postprocessWithTTA(float[][] out, Bitmap bmp,
                                                  PreprocessResult pp, int imgW, int imgH,
                                                  float threshold,
                                                  RecognitionSettings rs) throws Exception {
        return postprocessWithTTA(out, bmp, pp, imgW, imgH, threshold, false, rs, null);
    }

    private RecognitionResult postprocessWithTTA(float[][] out, Bitmap bmp,
                                                  PreprocessResult pp, int imgW, int imgH,
                                                  float threshold,
                                                  boolean uniformGrid,
                                                  RecognitionSettings rs) throws Exception {
        return postprocessWithTTA(out, bmp, pp, imgW, imgH, threshold, uniformGrid, rs, null);
    }

    /** @param externalCorners 手动注入的棋盘四角(原图像素, TL→TR→BR→BL), 非 null 时跳过自动角点检测。 */
    private RecognitionResult postprocessWithTTA(float[][] out, Bitmap bmp,
                                                  PreprocessResult pp, int imgW, int imgH,
                                                  float threshold,
                                                  boolean uniformGrid,
                                                  RecognitionSettings rs,
                                                  float[][] externalCorners) throws Exception {
        long tStart = System.currentTimeMillis();
        int nLogit = NUM_QUERIES * NUM_CLASSES;
        int nBox = NUM_QUERIES * 4;
        final int NUM_VARIANTS = 4;

        // 变体 1: 原始 (已由 caller 完成,放在 index 0)
        float[][] allLogits = new float[NUM_VARIANTS][];
        float[][] allBoxes  = new float[NUM_VARIANTS][];
        allLogits[0] = out[0];
        allBoxes[0]  = out[1].clone();

        // 变体 2: 水平翻转 (H)
        long t = System.currentTimeMillis();
        Bitmap bmpH = flipHorizontal(bmp);
        PreprocessResult ppH = preprocess(bmpH, imgW, imgH);
        float[][] outH = runInference(ppH.input);
        allLogits[1] = outH[0];
        allBoxes[1] = outH[1].clone();
        for (int i = 0; i < NUM_QUERIES; i++) {
            allBoxes[1][i * 4] = 1.0f - allBoxes[1][i * 4]; // cx 镜像
        }
        Log.d(TAG, "TTA-H 耗时" + (System.currentTimeMillis() - t) + "ms");

        // 变体 3: 垂直翻转 (V)
        t = System.currentTimeMillis();
        Bitmap bmpV = flipVertical(bmp);
        PreprocessResult ppV = preprocess(bmpV, imgW, imgH);
        float[][] outV = runInference(ppV.input);
        allLogits[2] = outV[0];
        allBoxes[2] = outV[1].clone();
        for (int i = 0; i < NUM_QUERIES; i++) {
            allBoxes[2][i * 4 + 1] = 1.0f - allBoxes[2][i * 4 + 1]; // cy 镜像
        }
        Log.d(TAG, "TTA-V 耗时" + (System.currentTimeMillis() - t) + "ms");

        // 变体 4: 180° 旋转 (H+V 组合)
        t = System.currentTimeMillis();
        Bitmap bmpR = flipVertical(bmpH); // reuse the already-flipped-H bitmap
        PreprocessResult ppR = preprocess(bmpR, imgW, imgH);
        float[][] outR = runInference(ppR.input);
        allLogits[3] = outR[0];
        allBoxes[3] = outR[1].clone();
        for (int i = 0; i < NUM_QUERIES; i++) {
            allBoxes[3][i * 4]     = 1.0f - allBoxes[3][i * 4];     // cx 镜像(H)
            allBoxes[3][i * 4 + 1] = 1.0f - allBoxes[3][i * 4 + 1]; // cy 镜像(V)
        }
        Log.d(TAG, "TTA-R(180) 耗时" + (System.currentTimeMillis() - t) + "ms");

        // 合并 4 路
        float[] combinedLogits = new float[nLogit * NUM_VARIANTS];
        float[] combinedBoxes  = new float[nBox * NUM_VARIANTS];
        for (int v = 0; v < NUM_VARIANTS; v++) {
            System.arraycopy(allLogits[v], 0, combinedLogits, v * nLogit, nLogit);
            System.arraycopy(allBoxes[v],  0, combinedBoxes,  v * nBox,   nBox);
        }

        Log.i(TAG, "4 路 TTA 完成: 4×" + NUM_QUERIES + " = " + (NUM_QUERIES * NUM_VARIANTS)
                + " query 合并, 总耗时" + (System.currentTimeMillis() - tStart) + "ms");
        return postprocess(combinedLogits, combinedBoxes, imgW, imgH, threshold, bmp, pp.lb,
                NUM_QUERIES * NUM_VARIANTS, uniformGrid, rs, externalCorners);
    }

    /**
     * 阶段 1 用:从推理输出计算棋子 bbox (粗略, 用于自动裁剪定位)。
     * pred_boxes 为 [0,1] 归一化到 INPUT_SIZE×INPUT_SIZE (含 letterbox padding),
     * 需用 letterbox 信息映射回原图坐标: orig = (n*INPUT_SIZE - pad) / scale。
     * <p>
     * 抗误检要点:
     * 1. 使用与 postprocess 一致的类别感知阈值过滤,避免把低分背景纹理纳入;
     * 2. 包围盒用稳健统计(剔除最外 5% 离群点)计算,防止个别误检把 bbox 撑大、
     *    把棋盘周围的错误也裁进精简识别区域。
     */
    private float[] computeStonesBBox(float[] logits, float[] predBoxes,
                                       int imgW, int imgH, float threshold,
                                       LetterboxInfo lb) {
        List<Float> xs = new ArrayList<>();
        List<Float> ys = new ArrayList<>();
        for (int i = 0; i < NUM_QUERIES; i++) {
            int logitBase = i * NUM_CLASSES;
            int bestClass = 0;
            float bestScore = logits[logitBase];
            for (int c = 1; c < NUM_CLASSES; c++) {
                if (logits[logitBase + c] > bestScore) {
                    bestScore = logits[logitBase + c];
                    bestClass = c;
                }
            }
            bestScore = sigmoid(bestScore);
            if (bestClass == CLASS_BOARD_CORNER) continue;
            // 类别感知阈值:与 postprocess 保持一致,过滤低分误检
            float minScore = (bestClass == CLASS_BLACK_STONE)
                    ? threshold + BLACK_STONE_THRESHOLD_BIAS
                    : threshold + WHITE_STONE_THRESHOLD_BIAS;
            if (bestScore < minScore) continue;
            int boxBase = i * 4;
            // letterbox 坐标逆映射: 640 归一化 → 去掉 padding → 还原原图比例
            float cx = (predBoxes[boxBase] * INPUT_SIZE - lb.padX) / lb.scale;
            float cy = (predBoxes[boxBase + 1] * INPUT_SIZE - lb.padY) / lb.scale;
            // 落在 padding 区的误检会映射到图像外,夹到边界内供裁剪定位用
            cx = Math.max(0, Math.min(cx, imgW - 1));
            cy = Math.max(0, Math.min(cy, imgH - 1));
            xs.add(cx);
            ys.add(cy);
        }
        int count = xs.size();
        Log.i(TAG, "阶段 1 检测到 " + count + " 个棋子 (类别感知阈值过滤, 用于自动裁剪)");
        if (count == 0) return null;

        // 稳健包围盒:各方向剔除最外 5% 离群点,避免单个误检撑大 bbox
        Collections.sort(xs);
        Collections.sort(ys);
        int trim = Math.max(0, (int) (count * 0.05f));
        int lo = trim, hi = count - 1 - trim;
        if (hi <= lo) { lo = 0; hi = count - 1; } // 点数过少时不剔除
        float minX = xs.get(lo);
        float maxX = xs.get(hi);
        float minY = ys.get(lo);
        float maxY = ys.get(hi);
        return new float[]{minX, minY, maxX, maxY};
    }

    /**
     * 把 ONNX Runtime 返回的多维 float 数组(可能 1D/2D/3D)拍平成一维 float[]。
     * 不同 shape 返回不同维度: [N] → float[], [1,N,M] → float[][][] 等。
     * 按行优先顺序展开,长度截断到 expected。
     */
    private static float[] flattenFloatArray(Object val, int expected) {
        if (val instanceof float[]) {
            float[] arr = (float[]) val;
            if (arr.length == expected) return arr;
            float[] out = new float[Math.min(expected, arr.length)];
            System.arraycopy(arr, 0, out, 0, out.length);
            return out;
        }
        float[] out = new float[expected];
        int[] idx = {0};
        recurseFlatten(val, out, idx);
        return out;
    }

    private static void recurseFlatten(Object val, float[] out, int[] idx) {
        if (val instanceof float[]) {
            for (float v : (float[]) val) {
                if (idx[0] >= out.length) return;
                out[idx[0]++] = v;
            }
        } else if (val instanceof Object[]) {
            for (Object o : (Object[]) val) {
                if (idx[0] >= out.length) return;
                recurseFlatten(o, out, idx);
            }
        }
    }

    // ==================== 预处理 ====================

    /** Letterbox 信息:保持长宽比 resize 后的缩放比例和 padding。 */
    private static class LetterboxInfo {
        final float scale;
        final int padX, padY;
        final int newW, newH;
        LetterboxInfo(float scale, int padX, int padY, int newW, int newH) {
            this.scale = scale;
            this.padX = padX;
            this.padY = padY;
            this.newW = newW;
            this.newH = newH;
        }
    }

    /** 预处理结果:包含输入张量和 letterbox 信息。 */
    private static class PreprocessResult {
        final float[] input;
        final LetterboxInfo lb;
        PreprocessResult(float[] input, LetterboxInfo lb) {
            this.input = input;
            this.lb = lb;
        }
    }

    /**
     * 对比度增强:直方图 1%-99% 分位拉伸,改善光照不均/低对比度图像。
     * 按亮度(luminance)计算分位,统一增益应用到 RGB 三通道,避免色偏。
     * 对比度已足够 (hi-lo > 200) 时跳过,避免无谓处理。
     */
    private static Bitmap enhanceContrast(Bitmap src) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] pixels = new int[w * h];
        src.getPixels(pixels, 0, w, 0, 0, w, h);

        int[] hist = new int[256];
        for (int px : pixels) {
            int r = (px >> 16) & 0xFF;
            int g = (px >> 8) & 0xFF;
            int b = px & 0xFF;
            int lum = (int) (0.299f * r + 0.587f * g + 0.114f * b);
            hist[lum]++;
        }

        int total = w * h;
        int loCount = (int) (total * 0.01f);
        int hiCount = (int) (total * 0.01f);
        int lo = 0, hi = 255;
        int cum = 0;
        for (int i = 0; i < 256; i++) {
            cum += hist[i];
            if (cum >= loCount) { lo = i; break; }
        }
        cum = 0;
        for (int i = 255; i >= 0; i--) {
            cum += hist[i];
            if (cum >= hiCount) { hi = i; break; }
        }
        if (hi - lo < 10) return src;
        if (hi - lo > 200) return src;

        float gain = 255f / (hi - lo);
        for (int i = 0; i < pixels.length; i++) {
            int px = pixels[i];
            int a = (px >> 24) & 0xFF;
            int r = (px >> 16) & 0xFF;
            int g = (px >> 8) & 0xFF;
            int b = px & 0xFF;
            r = Math.max(0, Math.min(255, Math.round((r - lo) * gain)));
            g = Math.max(0, Math.min(255, Math.round((g - lo) * gain)));
            b = Math.max(0, Math.min(255, Math.round((b - lo) * gain)));
            pixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
        Bitmap result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        result.setPixels(pixels, 0, w, 0, 0, w, h);
        Log.d(TAG, String.format("对比度增强: 拉伸 [%d,%d] → [0,255] gain=%.2f", lo, hi, gain));
        return result;
    }

    /**
     * Letterbox 预处理:保持长宽比 resize 到 INPUT_SIZE×INPUT_SIZE,空白处填 0 (黑色)。
     * 输出 CHW float[3 * 640 * 640],值域 [0,1]。
     * 避免直接拉伸导致长宽比扭曲 (如 720x1600 直接 resize 到 640x640 会严重压扁)。
     */
    private static PreprocessResult preprocess(Bitmap bmp, int srcW, int srcH) {
        return preprocess(bmp, srcW, srcH, 1.0f);
    }

    /**
     * @param scaleBoost 缩放增强系数 (TTA 用)。1.0=原样; <1 在原图基础上再缩小 (棋盘占图比例变化,
     *                  有助于模型在不同尺度下召回弱对比子); 内部对原图做 center-crop 后再 letterbox。
     */
    private static PreprocessResult preprocess(Bitmap bmp, int srcW, int srcH, float scaleBoost) {
        if (scaleBoost != 1.0f) {
            // center-crop 到 scaleBoost 比例 (如 0.9 → 裁掉周边 5%), 等效于"放大棋盘"
            int cw = Math.max(1, Math.round(srcW * scaleBoost));
            int ch = Math.max(1, Math.round(srcH * scaleBoost));
            int ox = (srcW - cw) / 2;
            int oy = (srcH - ch) / 2;
            bmp = Bitmap.createBitmap(bmp, ox, oy, cw, ch);
            srcW = cw;
            srcH = ch;
        }
        float scale = Math.min((float) INPUT_SIZE / srcW, (float) INPUT_SIZE / srcH);
        int newW = Math.max(1, Math.round(srcW * scale));
        int newH = Math.max(1, Math.round(srcH * scale));
        int padX = (INPUT_SIZE - newW) / 2;
        int padY = (INPUT_SIZE - newH) / 2;
        LetterboxInfo lb = new LetterboxInfo(scale, padX, padY, newW, newH);

        float[] buf = new float[3 * INPUT_SIZE * INPUT_SIZE];
        // buf 默认全 0 (黑色 padding),只填充实际内容区域
        int[] pixels = new int[srcW * srcH];
        bmp.getPixels(pixels, 0, srcW, 0, 0, srcW, srcH);

        // 双线性 resize 到 newW×newH,放到 (padX, padY) 位置
        for (int y = 0; y < newH; y++) {
            for (int x = 0; x < newW; x++) {
                float srcX = (x + 0.5f) / scale - 0.5f;
                float srcY = (y + 0.5f) / scale - 0.5f;
                int x0 = Math.max(0, (int) Math.floor(srcX));
                int y0 = Math.max(0, (int) Math.floor(srcY));
                int x1 = Math.min(x0 + 1, srcW - 1);
                int y1 = Math.min(y0 + 1, srcH - 1);
                float fx = srcX - (float) Math.floor(srcX);
                float fy = srcY - (float) Math.floor(srcY);

                int i00 = y0 * srcW + x0;
                int i10 = y0 * srcW + x1;
                int i01 = y1 * srcW + x0;
                int i11 = y1 * srcW + x1;
                int p00 = pixels[i00], p10 = pixels[i10], p01 = pixels[i01], p11 = pixels[i11];

                int outX = padX + x;
                int outY = padY + y;
                for (int c = 0; c < 3; c++) {
                    int shift = 16 - 8 * c; // R=16, G=8, B=0
                    int mask = 0xFF << shift;
                    float v00 = (p00 & mask) >>> shift;
                    float v10 = (p10 & mask) >>> shift;
                    float v01 = (p01 & mask) >>> shift;
                    float v11 = (p11 & mask) >>> shift;
                    float val = v00 * (1 - fx) * (1 - fy)
                              + v10 * fx * (1 - fy)
                              + v01 * (1 - fx) * fy
                              + v11 * fx * fy;
                    buf[c * INPUT_SIZE * INPUT_SIZE + outY * INPUT_SIZE + outX] = val / 255f;
                }
            }
        }
        return new PreprocessResult(buf, lb);
    }

    // ==================== 后处理 ====================

    private static RecognitionResult postprocess(float[] logits, float[] predBoxes,
                                                  int imgW, int imgH, float threshold,
                                                  android.graphics.Bitmap srcBmp,
                                                  LetterboxInfo lb,
                                                  RecognitionSettings rs) {
        return postprocess(logits, predBoxes, imgW, imgH, threshold, srcBmp, lb,
                NUM_QUERIES, false, rs, null);
    }

    private static RecognitionResult postprocess(float[] logits, float[] predBoxes,
                                                  int imgW, int imgH, float threshold,
                                                  android.graphics.Bitmap srcBmp,
                                                  LetterboxInfo lb, int numQueries,
                                                  RecognitionSettings rs) {
        return postprocess(logits, predBoxes, imgW, imgH, threshold, srcBmp, lb,
                numQueries, false, rs, null);
    }

    private static RecognitionResult postprocess(float[] logits, float[] predBoxes,
                                                  int imgW, int imgH, float threshold,
                                                  android.graphics.Bitmap srcBmp,
                                                  LetterboxInfo lb, int numQueries,
                                                  boolean uniformGrid,
                                                  RecognitionSettings rs) {
        return postprocess(logits, predBoxes, imgW, imgH, threshold, srcBmp, lb,
                numQueries, uniformGrid, rs, null);
    }

    /** @param externalCorners 手动注入的棋盘四角(原图像素, TL→TR→BR→BL), 非 null 时跳过自动角点检测。 */
    private static RecognitionResult postprocess(float[] logits, float[] predBoxes,
                                                  int imgW, int imgH, float threshold,
                                                  android.graphics.Bitmap srcBmp,
                                                  LetterboxInfo lb, int numQueries,
                                                  boolean uniformGrid,
                                                  RecognitionSettings rs,
                                                  float[][] externalCorners) {
        List<Detection> stones = new ArrayList<>();
        List<Detection> cornerCandidates = new ArrayList<>();
        // 临界棋子:score 在 [threshold*0.5, threshold) 之间,差一点就过阈值
        // 收集这些棋子并打印,方便定位漏检原因
        List<Detection> borderline = new ArrayList<>();
        // score 分布统计(0-0.02, 0.02-0.05, 0.05-0.1, 0.1-0.3, 0.3+)
        int[] scoreBuckets = new int[5];

        // 解码 query：每个 query 输出一个物体（RT-DETR 约定）
        // TTA 合并时 numQueries=600 (原始300 + 翻转300)
        // 同时统计每类 top3 分数,便于排查阈值是否过松/过紧
        float[] topBlack = {0,0,0}, topWhite = {0,0,0}, topCorner = {0,0,0};
        for (int q = 0; q < numQueries; q++) {
            int logitBase = q * NUM_CLASSES;
            int boxBase = q * 4;
            int bestClass = 0;
            float bestScore = Float.NEGATIVE_INFINITY;
            for (int c = 0; c < NUM_CLASSES; c++) {
                float s = sigmoid(logits[logitBase + c]);
                if (s > bestScore) {
                    bestScore = s;
                    bestClass = c;
                }
            }
            // 角点用低阈值;棋子用类别感知阈值:
            //   黑子对比度高 → 阈值稍高 过滤误检; 白子对比度低 → 阈值稍低 保召回
            float minScore;
            if (bestClass == CLASS_BOARD_CORNER) {
                minScore = CORNER_MIN_THRESHOLD;
            } else if (bestClass == CLASS_BLACK_STONE) {
                minScore = threshold + BLACK_STONE_THRESHOLD_BIAS;
            } else {
                minScore = threshold + WHITE_STONE_THRESHOLD_BIAS;
            }
            // 收集 top3 分数 (无论是否过阈值)
            if (bestClass == CLASS_BLACK_STONE) updateTop(topBlack, bestScore);
            else if (bestClass == CLASS_WHITE_STONE) updateTop(topWhite, bestScore);
            else updateTop(topCorner, bestScore);

            // score 分布统计(只统计棋子,不统计角点)
            if (bestClass != CLASS_BOARD_CORNER) {
                if (bestScore < 0.02f) scoreBuckets[0]++;
                else if (bestScore < 0.05f) scoreBuckets[1]++;
                else if (bestScore < 0.1f) scoreBuckets[2]++;
                else if (bestScore < 0.3f) scoreBuckets[3]++;
                else scoreBuckets[4]++;
            }

            // 临界棋子收集 (棋子类,score 在 [threshold*0.5, threshold) 之间)
            if (bestClass != CLASS_BOARD_CORNER
                    && bestScore >= threshold * 0.5f
                    && bestScore < minScore) {
                float cx = (predBoxes[boxBase] * INPUT_SIZE - lb.padX) / lb.scale;
                float cy = (predBoxes[boxBase + 1] * INPUT_SIZE - lb.padY) / lb.scale;
                float bw = predBoxes[boxBase + 2] * INPUT_SIZE / lb.scale;
                float bh = predBoxes[boxBase + 3] * INPUT_SIZE / lb.scale;
                borderline.add(new Detection(cx, cy, bw, bh, bestClass, bestScore));
            }

            if (bestScore < minScore) continue;
            // pred_boxes 为 [cx, cy, w, h] 归一化到 [0,1] (相对含 padding 的 640×640)
            // letterbox 逆映射回原图坐标
            float cx = (predBoxes[boxBase] * INPUT_SIZE - lb.padX) / lb.scale;
            float cy = (predBoxes[boxBase + 1] * INPUT_SIZE - lb.padY) / lb.scale;
            float bw = predBoxes[boxBase + 2] * INPUT_SIZE / lb.scale;
            float bh = predBoxes[boxBase + 3] * INPUT_SIZE / lb.scale;
            Detection det = new Detection(cx, cy, bw, bh, bestClass, bestScore);
            if (bestClass == CLASS_BOARD_CORNER) cornerCandidates.add(det);
            else stones.add(det);
        }
        Log.i(TAG, "解码 " + numQueries + " query: 候选黑" + countClass(stones, CLASS_BLACK_STONE)
                + " 候选白" + countClass(stones, CLASS_WHITE_STONE)
                + " 候选角" + cornerCandidates.size()
                + String.format(" | top3黑=%.3f/%.3f/%.3f top3白=%.3f/%.3f/%.3f top3角=%.3f/%.3f/%.3f"
                        + " (角阈值=" + CORNER_MIN_THRESHOLD + " 棋子阈值=" + threshold + ")",
                        topBlack[0], topBlack[1], topBlack[2],
                        topWhite[0], topWhite[1], topWhite[2],
                        topCorner[0], topCorner[1], topCorner[2]));
        // score 分布日志
        Log.i(TAG, String.format("score 分布: [<0.02]=%d [0.02-0.05)=%d [0.05-0.1)=%d "
                        + "[0.1-0.3)=%d [0.3+]=%d",
                scoreBuckets[0], scoreBuckets[1], scoreBuckets[2],
                scoreBuckets[3], scoreBuckets[4]));
        // 临界棋子日志(差一点就过阈值,可能是漏检的源头)
        if (!borderline.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("临界棋子(%d 个,score 在 [%.3f, %.3f) 之间,差一点过阈值):",
                    borderline.size(), threshold * 0.5f, threshold));
            for (Detection d : borderline) {
                String color = d.classId == CLASS_BLACK_STONE ? "黑" : "白";
                sb.append(String.format("\n  %s(%.0f,%.0f) s=%.3f", color, d.cx, d.cy, d.score));
            }
            Log.w(TAG, sb.toString());
        }

        // 多查询 (TTA) 用 WBF 加权框融合:重叠框按置信度加权平均取中心,
        // 相比 NMS 硬丢弃低分框,定位更准,网格偏差更小,过检率更低
        int beforeDedup = stones.size();
        stones = applyWBF(stones, WBF_IOU_THRESHOLD);
        Log.i(TAG, "WBF: " + beforeDedup + " → " + stones.size()
                + " (IoU>" + WBF_IOU_THRESHOLD + " 加权融合), 候选黑"
                + countClass(stones, CLASS_BLACK_STONE)
                + " 候选白" + countClass(stones, CLASS_WHITE_STONE));

        // 角点按置信度降序排序
        cornerCandidates.sort((a, b) -> Float.compare(b.score, a.score));

        // 去重：距离 < 5% 图像对角线的角点视为重复，保留高分
        float dedupeMinDist = (float) Math.hypot(imgW, imgH) * 0.05f;
        for (int i = 0; i < cornerCandidates.size(); i++) {
            for (int j = i + 1; j < cornerCandidates.size(); ) {
                Detection a = cornerCandidates.get(i);
                Detection b = cornerCandidates.get(j);
                float d = (float) Math.hypot(a.cx - b.cx, a.cy - b.cy);
                if (d < dedupeMinDist) {
                    cornerCandidates.remove(j);
                } else {
                    j++;
                }
            }
        }
        StringBuilder cornerSb = new StringBuilder();
        for (int i = 0; i < cornerCandidates.size() && i < 8; i++) {
            Detection c = cornerCandidates.get(i);
            cornerSb.append(String.format("(%.0f,%.0f,s=%.3f) ", c.cx, c.cy, c.score));
        }
        Log.d(TAG, "角点去重后剩 " + cornerCandidates.size() + " 个: " + cornerSb);

        RecognitionResult out = new RecognitionResult();
        out.board = new int[BOARD_SIZE][BOARD_SIZE]; // 默认 0=空

        // ===== 均匀网格模式(用户手动裁剪的棋盘) =====
        // 不依赖模型角点检测:角点是围棋识别整盘错位的主要根因(角点定位失败→H 矩阵错→
        // 19×19 网格整体错乱)。用户已手动裁剪出"近似矩形的 19 路棋盘",直接假设为均匀网格:
        // 用检测到的棋子 bbox 外扩锚定 19 路交叉点,然后在每个交叉点位置识别有没有子/什么颜色。
        if (uniformGrid) {
            float[][] corners = fitUniformGridCorners(stones, imgW, imgH);
            Log.i(TAG, "均匀网格模式(手动裁剪):跳过角点检测, 用棋子bbox外扩锚定19路网格 "
                    + String.format("TL(%.0f,%.0f) TR(%.0f,%.0f) BR(%.0f,%.0f) BL(%.0f,%.0f)",
                        corners[0][0], corners[0][1], corners[1][0], corners[1][1],
                        corners[2][0], corners[2][1], corners[3][0], corners[3][1]));
            verifyStonesColor(stones, srcBmp, rs);
            mapStonesToGrid(stones, corners, out.board, imgW, imgH, rs); // uniformGrid 已是 fit,无需再降级
            int recovered =             recoverMissedStones(srcBmp, corners, out.board, rs);
            if (recovered > 0) Log.i(TAG, "漏检恢复(均匀网格): 补回 " + recovered + " 个棋子");
            int reviewU = fullBoardReview(srcBmp, corners, out.board, rs);
            if (reviewU > 0) Log.i(TAG, "全棋盘复核(均匀网格): 改动 " + reviewU + " 格");
            out.corners = corners;
            out.cornersDetected = true;
            out.blackCount = countColor(out.board, BLACK);
            out.whiteCount = countColor(out.board, WHITE);
            out.message = "手动裁剪:均匀网格识别";
            return out;
        }

        // 角点不足 2 个：使用图像边缘 5% 内缩作为 fallback，且放弃棋子识别
        // (手动注入角点时跳过此 fallback)
        if (externalCorners == null && cornerCandidates.size() < 2) {
            float[][] corners = insetImageCorners(imgW, imgH, 0.05f);
            Log.w(TAG, "角点不足 2 个, 使用图像内缩 fallback: "
                    + corners[0][0] + "," + corners[0][1] + " ...");
            verifyStonesColor(stones, srcBmp, rs);
            boolean badGeo = mapStonesToGrid(stones, corners, out.board, imgW, imgH, rs);
            if (badGeo && stones.size() >= 4) {
                Log.w(TAG, "→ 角不足路径几何异常, 降级棋子 bbox 拟合网格重映射");
                corners = fitUniformGridCorners(stones, imgW, imgH);
                out.board = new int[BOARD_SIZE][BOARD_SIZE];
                mapStonesToGrid(stones, corners, out.board, imgW, imgH, rs);
            }
            // 模型漏检恢复: 反投影每个空网格交点回图像,采样像素确认
            int recovered = recoverMissedStones(srcBmp, corners, out.board, rs);
            if (recovered > 0) Log.i(TAG, "漏检恢复(角不足路径): 补回 " + recovered + " 个棋子");
            int reviewF = fullBoardReview(srcBmp, corners, out.board, rs);
            if (reviewF > 0) Log.i(TAG, "全棋盘复核(角不足路径): 改动 " + reviewF + " 格");
            out.corners = corners;
            out.cornersDetected = false;
            out.blackCount = countColor(out.board, BLACK);
            out.whiteCount = countColor(out.board, WHITE);
            out.message = "未检测到棋盘角点,使用图像边角作为近似(精度可能下降)";
            return out;
        }

        float[][] corners;
        if (externalCorners != null) {
            // 手动注入四角(用户拖动校正):信任用户指定范围,跳过自动检测与覆盖/退化降级
            corners = externalCorners;
            Log.i(TAG, "手动注入 4 角(用户校正): "
                    + String.format("TL(%.0f,%.0f) TR(%.0f,%.0f) BR(%.0f,%.0f) BL(%.0f,%.0f)",
                        corners[0][0], corners[0][1], corners[1][0], corners[1][1],
                        corners[2][0], corners[2][1], corners[3][0], corners[3][1]));
        } else {
            // 2 角:推断另外 2 个 (对角或邻边假设)
            // 3 角:平行四边形补全
            // ≥4 角:取 top 4
            float[][] top4 = pickTop4Corners(cornerCandidates, imgW, imgH);
            Log.d(TAG, "pickTop4Corners(输入" + cornerCandidates.size() + "角) → "
                    + String.format("(%.0f,%.0f)-(%.0f,%.0f)-(%.0f,%.0f)-(%.0f,%.0f)",
                        top4[0][0], top4[0][1], top4[1][0], top4[1][1],
                        top4[2][0], top4[2][1], top4[3][0], top4[3][1]));

            // 顺时针排序 TL→TR→BR→BL
            corners = orderCorners(top4);
            Log.d(TAG, "orderCorners TL→TR→BR→BL: "
                    + String.format("TL(%.0f,%.0f) TR(%.0f,%.0f) BR(%.0f,%.0f) BL(%.0f,%.0f)",
                        corners[0][0], corners[0][1], corners[1][0], corners[1][1],
                        corners[2][0], corners[2][1], corners[3][0], corners[3][1]));

            // 4 角覆盖范围检查:4 角 bbox 面积应该至少占图像 30%
            // (棋盘正常应占画面大部分;若 4 角挤在小区域,说明模型把棋盘内部点
            //  当成 corner,H 矩阵只覆盖小区域,外围棋子会全部越界)
            float covRatio = computeCornerCoverageRatio(corners, imgW, imgH);
            Log.d(TAG, String.format("4 角覆盖范围: bbox 占图像 %.1f%% (阈值 30%%)", covRatio * 100));

            // 4 角使用策略(模型检测到的真实 4 角优先,保证边角精度;仅在不可信时回退):
            // 1. 覆盖充足(≥70%) 且 非退化(无重合/非共线/近似矩形):用模型 4 角
            // 2. 退化 或 覆盖不足(<30%) 或 覆盖足但退化:降级用棋子 bbox 拟合(数据驱动网格)
            boolean degenerate = areCornersDegenerate(corners, imgW, imgH);
            if (covRatio >= 0.70f && !degenerate) {
                Log.d(TAG, String.format("4 角可信(覆盖%.1f%% 且非退化), 使用模型检测的真实 4 角",
                        covRatio * 100));
                // 保留 orderCorners(top4) 的结果
            } else {
                if (degenerate) {
                    Log.w(TAG, "4 角退化(重合/共线/非矩形), 降级为棋子 bbox 拟合网格(H不再算,保住已检棋子)");
                } else {
                    Log.w(TAG, String.format("4 角覆盖范围过小(%.1f%% < 30%%), 降级为棋子 bbox 拟合网格",
                            covRatio * 100));
                }
                corners = fitUniformGridCorners(stones, imgW, imgH);
            }
            Log.d(TAG, "最终使用 4 角: "
                    + String.format("TL(%.0f,%.0f) TR(%.0f,%.0f) BR(%.0f,%.0f) BL(%.0f,%.0f)",
                        corners[0][0], corners[0][1], corners[1][0], corners[1][1],
                        corners[2][0], corners[2][1], corners[3][0], corners[3][1]));
        }

        // 颜色二次验证:采样棋子中心区域像素,验证模型黑白判断
        // 修正明显的颜色错误(模型把黑当白或反之)
        verifyStonesColor(stones, srcBmp, rs);

        // 棋子映射到 19×19 网格
        boolean badGeo = mapStonesToGrid(stones, corners, out.board, imgW, imgH, rs);
        // 几何异常(过多棋子偏差过大/冲突,说明角点/H 不准):降级到棋子 bbox 拟合网格重映射
        if (badGeo && stones.size() >= 4) {
            Log.w(TAG, "→ 主路径几何异常, 降级用棋子 bbox 拟合网格重映射(避免角点不准导致整盘错)");
            corners = fitUniformGridCorners(stones, imgW, imgH);
            out.board = new int[BOARD_SIZE][BOARD_SIZE];
            mapStonesToGrid(stones, corners, out.board, imgW, imgH, rs);
        }

        // 模型漏检恢复: 反投影每个空网格交点回图像,采样像素确认
        int recovered = recoverMissedStones(srcBmp, corners, out.board, rs);
        if (recovered > 0) {
            Log.i(TAG, "漏检恢复(主路径): 补回 " + recovered + " 个棋子");
        }
        int reviewM = fullBoardReview(srcBmp, corners, out.board, rs);
        if (reviewM > 0) Log.i(TAG, "全棋盘复核(主路径): 改动 " + reviewM + " 格");

        out.corners = corners;
        out.cornersDetected = true;
        out.blackCount = countColor(out.board, BLACK);
        out.whiteCount = countColor(out.board, WHITE);
        out.message = String.format("识别完成: 黑%d 白%d", out.blackCount, out.whiteCount);
        Log.i(TAG, "=== 识别结束 === " + out.message
                + " | 输入棋子" + stones.size() + "个 (黑" + countClass(stones, CLASS_BLACK_STONE)
                + "+白" + countClass(stones, CLASS_WHITE_STONE) + ")"
                + " (漏检恢复" + recovered + ")"
                + " → 19×19 网格 黑" + out.blackCount + " 白" + out.whiteCount);
        return out;
    }

    // ==================== 角点补全/排序 ====================

    private static float[][] pickTop4Corners(List<Detection> cornerCandidates, int imgW, int imgH) {
        if (cornerCandidates.size() == 2) {
            // 推断另外 2 个角:对角/邻边两种假设,挑最合理的
            float[] p1 = {cornerCandidates.get(0).cx, cornerCandidates.get(0).cy};
            float[] p2 = {cornerCandidates.get(1).cx, cornerCandidates.get(1).cy};
            float mx = (p1[0] + p2[0]) / 2f, my = (p1[1] + p2[1]) / 2f;
            float dx = p2[0] - p1[0], dy = p2[1] - p1[1];
            // 假设1:对角线,旋转半对角 90° 得另两个对角端点
            float hdx = dx / 2f, hdy = dy / 2f;
            float[][] cand1 = {p1, new float[]{mx + hdy, my - hdx}, p2,
                    new float[]{mx - hdy, my + hdx}};
            // 假设2:邻边,垂直等长 (左/右各一种)
            float[][] cand2 = {p1, p2, new float[]{p2[0] - dy, p2[1] + dx},
                    new float[]{p1[0] - dy, p1[1] + dx}};
            float[][] cand3 = {p1, p2, new float[]{p2[0] + dy, p2[1] - dx},
                    new float[]{p1[0] + dy, p1[1] - dx}};
            // 评分:角点在图像内越多越好,其次越靠内越好
            float[][][] cands = {cand1, cand2, cand3};
            float bestScore = Float.NEGATIVE_INFINITY;
            float[][] bestQuad = cand1;
            for (float[][] q : cands) {
                int inside = 0;
                float marginSum = 0;
                for (float[] pt : q) {
                    float mx2 = Math.min(pt[0], imgW - pt[0]);
                    float my2 = Math.min(pt[1], imgH - pt[1]);
                    if (mx2 >= 0 && my2 >= 0) {
                        inside++;
                        marginSum += mx2 + my2;
                    } else {
                        marginSum += mx2 + my2; // 负值
                    }
                }
                float score = inside * 1e6f + marginSum;
                if (score > bestScore) {
                    bestScore = score;
                    bestQuad = q;
                }
            }
            return bestQuad;
        } else if (cornerCandidates.size() == 3) {
            // 平行四边形补全第 4 个角:尝试 3 种对角顶点选择,挑最接近矩形的
            float[][] pts = {
                {cornerCandidates.get(0).cx, cornerCandidates.get(0).cy},
                {cornerCandidates.get(1).cx, cornerCandidates.get(1).cy},
                {cornerCandidates.get(2).cx, cornerCandidates.get(2).cy}
            };
            float bestScore = Float.POSITIVE_INFINITY;
            float[] bestP4 = null;
            int bestDiagIdx = 0;
            for (int diag = 0; diag < 3; diag++) {
                float[] a = pts[diag];
                float[] b = pts[(diag + 1) % 3];
                float[] c = pts[(diag + 2) % 3];
                float[] p4 = {b[0] + c[0] - a[0], b[1] + c[1] - a[1]};
                float d1 = (float) Math.hypot(a[0] - p4[0], a[1] - p4[1]);
                float d2 = (float) Math.hypot(b[0] - c[0], b[1] - c[1]);
                float score = Math.abs(d1 - d2);
                if (score < bestScore) {
                    bestScore = score;
                    bestP4 = p4;
                    bestDiagIdx = diag;
                }
            }
            // 返回顺序 [a, b, c, p4]
            return new float[][]{pts[bestDiagIdx],
                    pts[(bestDiagIdx + 1) % 3],
                    pts[(bestDiagIdx + 2) % 3], bestP4};
        } else {
            // ≥4 个:取 top 4
            float[][] pts = new float[4][2];
            for (int i = 0; i < 4; i++) {
                pts[i][0] = cornerCandidates.get(i).cx;
                pts[i][1] = cornerCandidates.get(i).cy;
            }
            return pts;
        }
    }

    /**
     * 顺时针排序角点 TL→TR→BR→BL。
     * 用 x+y / x-y 排序,不依赖中心点位置(更稳健,噪声角点不影响)。
     *   TL = argmin(x+y)        // 左上:x 小 y 小
     *   BR = argmax(x+y)        // 右下:x 大 y 大
     *   TR = argmax(x-y)        // 右上:x 大 y 小
     *   BL = argmin(x-y)        // 左下:x 小 y 大
     */
    private static float[][] orderCorners(float[][] pts) {
        if (pts.length != 4) return pts;
        int tl = 0, br = 0, tr = 0, bl = 0;
        float minSum = Float.MAX_VALUE, maxSum = -Float.MAX_VALUE;
        float maxDiff = -Float.MAX_VALUE, minDiff = Float.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            float sum = pts[i][0] + pts[i][1];
            float diff = pts[i][0] - pts[i][1];
            if (sum < minSum) { minSum = sum; tl = i; }
            if (sum > maxSum) { maxSum = sum; br = i; }
            if (diff > maxDiff) { maxDiff = diff; tr = i; }
            if (diff < minDiff) { minDiff = diff; bl = i; }
        }
        return new float[][]{ pts[tl], pts[tr], pts[br], pts[bl] };
    }

    /**
     * 4 角退化检查:以下任一情况视为退化,应降级到棋子 bbox 兜底(而非用坏角点算 H):
     *   1. bbox 面积 < 图像 2% (4 角挤成一团);
     *   2. 任意两角过近 (< 短边 5%) —— 模型把同一点重复当角(BL=TL 这类);
     *   3. 4 角近似共线 (四点构成多边形面积 ≈ 0) —— H 矩阵奇异;
     *   4. 两组对边长度差异过大 (> 3 倍) —— 非近似矩形,角点选错。
     */
    private static boolean areCornersDegenerate(float[][] corners, int w, int h) {
        if (computeCornerCoverageRatio(corners, w, h) < 0.02f) return true;
        float shortSide = Math.min(w, h);
        // 任意两角过近
        for (int i = 0; i < 4; i++) {
            for (int j = i + 1; j < 4; j++) {
                float d = (float) Math.hypot(corners[i][0] - corners[j][0],
                        corners[i][1] - corners[j][1]);
                if (d < shortSide * 0.05f) return true;
            }
        }
        // 4 角近似共线:用鞋带公式算四边形面积,过小即退化 (面积阈值 = 短边^2 的 1%)
        float area = shoeLaceArea(corners);
        if (area < shortSide * shortSide * 0.01f) return true;
        // 对边长度差异过大
        float[] edge = new float[4];
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            edge[i] = (float) Math.hypot(corners[i][0] - corners[j][0],
                    corners[i][1] - corners[j][1]);
        }
        float diag1 = Math.max(edge[0], edge[2]); // 对边0-2
        float diag2 = Math.max(edge[1], edge[3]); // 对边1-3
        float minD = Math.min(edge[0], edge[2]);
        float minD2 = Math.min(edge[1], edge[3]);
        if (minD > 0 && diag1 / minD > 3f) return true;
        if (minD2 > 0 && diag2 / minD2 > 3f) return true;
        return false;
    }

    /** 鞋带公式计算 4 角多边形有向面积绝对值。 */
    private static float shoeLaceArea(float[][] p) {
        float s = 0;
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            s += p[i][0] * p[j][1] - p[j][0] * p[i][1];
        }
        return Math.abs(s) / 2f;
    }

    /** 4 角 bbox 面积 / 图像面积,用于检测 4 角是否覆盖了合理范围。 */
    private static float computeCornerCoverageRatio(float[][] corners, int w, int h) {
        float minX = Float.MAX_VALUE, maxX = Float.MIN_VALUE;
        float minY = Float.MAX_VALUE, maxY = Float.MIN_VALUE;
        for (float[] c : corners) {
            if (c[0] < minX) minX = c[0];
            if (c[0] > maxX) maxX = c[0];
            if (c[1] < minY) minY = c[1];
            if (c[1] > maxY) maxY = c[1];
        }
        float area = (maxX - minX) * (maxY - minY);
        return area / (w * h);
    }

    /** 图像边缘向内缩 fraction 比例作为角点 fallback。 */
    private static float[][] insetImageCorners(int w, int h, float fraction) {
        float m = Math.min(w, h) * fraction;
        return new float[][]{
            {m, m},
            {w - 1 - m, m},
            {w - 1 - m, h - 1 - m},
            {m, h - 1 - m}
        };
    }

    /**
     * 用棋子坐标的 bbox 外扩作为 4 角 fallback。
     * 比 insetImageCorners 精准:棋子分布范围反映棋盘真实位置,
     * 不依赖整个图像(棋盘可能只占画面中央)。
     *
     * 外扩 padding 取长边的 1/18 (即一个格子的距离),
     * 因为棋盘边缘 1~2 个交叉点可能没棋子(尤其是空角)。
     */
    private static float[][] computeStonesBBoxCorners(List<Detection> stones, int imgW, int imgH) {
        if (stones.isEmpty()) return insetImageCorners(imgW, imgH, 0.05f);
        float minX = Float.MAX_VALUE, maxX = Float.MIN_VALUE;
        float minY = Float.MAX_VALUE, maxY = Float.MIN_VALUE;
        for (Detection d : stones) {
            if (d.cx < minX) minX = d.cx;
            if (d.cx > maxX) maxX = d.cx;
            if (d.cy < minY) minY = d.cy;
            if (d.cy > maxY) maxY = d.cy;
        }
        float w = maxX - minX;
        float h = maxY - minY;
        // 一个格子的距离 = 长边 / 18 (棋盘 19x19 = 18 个间隔)
        // 外扩一个格子,因为棋盘 4 角可能没棋子(空角常见)
        float pad = Math.max(w, h) / 18f;
        minX = Math.max(0, minX - pad);
        minY = Math.max(0, minY - pad);
        maxX = Math.min(imgW - 1, maxX + pad);
        maxY = Math.min(imgH - 1, maxY + pad);
        Log.d(TAG, String.format("棋子 bbox 外扩 fallback: bbox=(%.0f,%.0f)-(%.0f,%.0f) +pad=%.0f → "
                + "TL(%.0f,%.0f) BR(%.0f,%.0f)",
                minX + pad, minY + pad, maxX - pad, maxY - pad, pad, minX, minY, maxX, maxY));
        return new float[][]{
            {minX, minY},   // TL
            {maxX, minY},   // TR
            {maxX, maxY},   // BR
            {minX, maxY}    // BL
        };
    }

    /**
     * 数据驱动网格拟合(手动裁剪 / uniformGrid 模式用)。
     * 比单纯 bbox 外扩更准:用所有检测到的棋子投票最近交叉点,最小二乘拟合最优仿射角点,
     * 使网格贴合实际棋子布局(轻微透视/倾斜也能对齐),天然免疫"空角/边角无子"。
     *
     * 步骤:
     *   1. 用 bbox 外扩得到初始 4 角,反投影出 19×19 初始交叉点坐标;
     *   2. 每个检测子投票到最近交叉点 (c,r),累加坐标均值;
     *   3. 缺失的行/列(无子)用相邻行列线性插值补全;
     *   4. 以 (c/18, r/18) 为自变量、(meanX, meanY) 为因变量,最小二乘拟合仿射:
     *        x = a + b*u + c0*v ,  y = d + e*u + f*v   (u=c/18, v=r/18)
     *      解出 6 参数后重建 4 角(保持 applyHomography 接口不变)。
     */
    private static float[][] fitUniformGridCorners(List<Detection> stones, int imgW, int imgH) {
        float[][] init = computeStonesBBoxCorners(stones, imgW, imgH);
        if (stones.size() < 4) {
            Log.d(TAG, "数据驱动拟合: 棋子<4, 退化为 bbox 外扩角点");
            return init;
        }
        // 初始网格:由 init 角点反投影出 19×19 交叉点理论坐标
        float[][] dst = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
        float[] H = Perspective.computeHomography(init, dst);
        if (H == null) return init;
        float[][] gx = new float[BOARD_SIZE][BOARD_SIZE]; // 初始交叉点 x
        float[][] gy = new float[BOARD_SIZE][BOARD_SIZE];
        for (int r = 0; r < BOARD_SIZE; r++) {
            for (int c = 0; c < BOARD_SIZE; c++) {
                float u = c / 18f, v = r / 18f;
                float ix = init[0][0] + u * (init[1][0] - init[0][0]) + v * (init[3][0] - init[0][0]);
                float iy = init[0][1] + u * (init[1][1] - init[0][1]) + v * (init[3][1] - init[0][1]);
                gx[c][r] = ix;
                gy[c][r] = iy;
            }
        }
        // 投票:每格累加坐标、计数
        double[] sumX = new double[BOARD_SIZE * BOARD_SIZE];
        double[] sumY = new double[BOARD_SIZE * BOARD_SIZE];
        int[] cnt = new int[BOARD_SIZE * BOARD_SIZE];
        for (Detection d : stones) {
            // 找最近初始交叉点
            int bestC = 0, bestR = 0;
            double bestD = Double.MAX_VALUE;
            for (int r = 0; r < BOARD_SIZE; r++) {
                for (int c = 0; c < BOARD_SIZE; c++) {
                    double dx = d.cx - gx[c][r];
                    double dy = d.cy - gy[c][r];
                    double dist = dx * dx + dy * dy;
                    if (dist < bestD) { bestD = dist; bestC = c; bestR = r; }
                }
            }
            int idx = bestR * BOARD_SIZE + bestC;
            sumX[idx] += d.cx;
            sumY[idx] += d.cy;
            cnt[idx]++;
        }
        // 列均值 / 行均值(仅统计有票的点)
        double[] colX = new double[BOARD_SIZE];
        int[] colN = new int[BOARD_SIZE];
        double[] rowY = new double[BOARD_SIZE];
        int[] rowN = new int[BOARD_SIZE];
        for (int r = 0; r < BOARD_SIZE; r++) {
            for (int c = 0; c < BOARD_SIZE; c++) {
                int idx = r * BOARD_SIZE + c;
                if (cnt[idx] > 0) {
                    double mx = sumX[idx] / cnt[idx];
                    double my = sumY[idx] / cnt[idx];
                    colX[c] += mx; colN[c]++;
                    rowY[r] += my; rowN[r]++;
                }
            }
        }
        for (int c = 0; c < BOARD_SIZE; c++) if (colN[c] > 0) colX[c] /= colN[c];
        for (int r = 0; r < BOARD_SIZE; r++) if (rowN[r] > 0) rowY[r] /= rowN[r];
        // 用真实棋子间距(相邻有子列/行的平均距离)作为外推步长,避免外框边距用错
        double avgCellX = computeAvgStep(colX, colN);
        double avgCellY = computeAvgStep(rowY, rowN);
        if (avgCellX <= 0) avgCellX = 1.0;
        if (avgCellY <= 0) avgCellY = 1.0;
        // 缺失列/行补全:中间插值,两端用真实格距外推(空角/空边不再把外框拉偏)
        interpolate(colX, colN, avgCellX);
        interpolate(rowY, rowN, avgCellY);

        // 最小二乘拟合仿射: x = a + b*u + c0*v ; y = d + e*u + f*v
        // 正规方程 A^T A p = A^T b, A 每行 [1, u, v]
        double[][] ATA_x = new double[3][3];
        double[] ATb_x = new double[3];
        double[][] ATA_y = new double[3][3];
        double[] ATb_y = new double[3];
        for (int r = 0; r < BOARD_SIZE; r++) {
            for (int c = 0; c < BOARD_SIZE; c++) {
                int idx = r * BOARD_SIZE + c;
                if (cnt[idx] == 0) continue; // 仅用有票点拟合
                double u = c / 18.0, v = r / 18.0;
                double a = 1, b = u, cc = v;
                ATA_x[0][0] += a*a; ATA_x[0][1] += a*b; ATA_x[0][2] += a*cc;
                ATA_x[1][0] += b*a; ATA_x[1][1] += b*b; ATA_x[1][2] += b*cc;
                ATA_x[2][0] += cc*a; ATA_x[2][1] += cc*b; ATA_x[2][2] += cc*cc;
                ATb_x[0] += a * colX[c]; ATb_x[1] += b * colX[c]; ATb_x[2] += cc * colX[c];
                ATA_y[0][0] += a*a; ATA_y[0][1] += a*b; ATA_y[0][2] += a*cc;
                ATA_y[1][0] += b*a; ATA_y[1][1] += b*b; ATA_y[1][2] += b*cc;
                ATA_y[2][0] += cc*a; ATA_y[2][1] += cc*b; ATA_y[2][2] += cc*cc;
                ATb_y[0] += a * rowY[r]; ATb_y[1] += b * rowY[r]; ATb_y[2] += cc * rowY[r];
            }
        }
        double[] px = solve3x3(ATA_x, ATb_x);
        double[] py = solve3x3(ATA_y, ATb_y);
        if (px == null || py == null) {
            Log.w(TAG, "数据驱动拟合: 3x3 求解失败, 退化为 bbox 外扩角点");
            return init;
        }
        // 重建 4 角 (u,v) ∈ {(0,0)TL,(1,0)TR,(0,1)BL,(1,1)BR}
        float[][] corners = new float[4][2];
        corners[0] = new float[]{(float)(px[0]), (float)(py[0])};                       // TL (0,0)
        corners[1] = new float[]{(float)(px[0]+px[1]), (float)(py[0]+py[1])};           // TR (1,0)
        corners[3] = new float[]{(float)(px[0]+px[2]), (float)(py[0]+py[2])};           // BL (0,1)
        corners[2] = new float[]{(float)(px[0]+px[1]+px[2]), (float)(py[0]+py[1]+py[2])}; // BR (1,1)
        Log.i(TAG, "数据驱动网格拟合: 用 " + stones.size() + " 子最小二乘校正角点(抗轻微透视/倾斜)");
        return corners;
    }

    /**
     * 对含缺失(count==0 表示无子)的行列坐标数组做补全。
     * 中间缺失段:线性插值;
     * 两端缺失(如空角/空边):用相邻有子点的真实格距外推(每向外 1 格 ±cell),
     * 而不 clamp 到有子点(否则外框会被"拉到"最外有子点,导致整盘偏移/缩小)。
     */
    private static void interpolate(double[] vals, int[] cnt, double cell) {
        int n = vals.length;
        int first = -1, last = -1;
        for (int i = 0; i < n; i++) { if (cnt[i] > 0) { if (first < 0) first = i; last = i; } }
        if (first < 0) return;
        // 左端外推:vals[0..first-1] = vals[first] - cell*(first - i)
        for (int i = first - 1; i >= 0; i--) vals[i] = vals[i + 1] - cell;
        // 右端外推:vals[last+1..n-1] = vals[last] + cell*(i - last)
        for (int i = last + 1; i < n; i++) vals[i] = vals[i - 1] + cell;
        // 中间缺失段线性插值
        int i = 0;
        while (i < n) {
            if (cnt[i] == 0) {
                int s = i;
                while (i < n && cnt[i] == 0) i++;
                int e = i; // e 是下一个有效点
                if (s > 0 && e < n) {
                    double v0 = vals[s - 1], v1 = vals[e];
                    int span = e - (s - 1);
                    for (int k = s; k < e; k++) {
                        vals[k] = v0 + (v1 - v0) * (k - (s - 1)) / span;
                    }
                }
            } else i++;
        }
    }

    /** 计算相邻有子点的平均间距(真实格距),用于端点外推步长。无相邻有子点返回 0。 */
    private static double computeAvgStep(double[] vals, int[] cnt) {
        double sum = 0; int n = 0;
        int prev = -1;
        for (int i = 0; i < vals.length; i++) {
            if (cnt[i] > 0) {
                if (prev >= 0) { sum += Math.abs(vals[i] - vals[prev]); n++; }
                prev = i;
            }
        }
        return n > 0 ? sum / n : 0;
    }

    /** 解 3x3 线性方程组 (高斯消元), 返回 null 表示奇异。 */
    private static double[] solve3x3(double[][] A, double[] b) {
        double[][] M = new double[3][4];
        for (int i = 0; i < 3; i++) {
            System.arraycopy(A[i], 0, M[i], 0, 3);
            M[i][3] = b[i];
        }
        for (int col = 0; col < 3; col++) {
            int piv = col;
            for (int r = col + 1; r < 3; r++) {
                if (Math.abs(M[r][col]) > Math.abs(M[piv][col])) piv = r;
            }
            if (Math.abs(M[piv][col]) < 1e-9) return null;
            double[] tmp = M[col]; M[col] = M[piv]; M[piv] = tmp;
            double d = M[col][col];
            for (int j = col; j < 4; j++) M[col][j] /= d;
            for (int r = 0; r < 3; r++) {
                if (r != col) {
                    double f = M[r][col];
                    for (int j = col; j < 4; j++) M[r][j] -= f * M[col][j];
                }
            }
        }
        return new double[]{M[0][3], M[1][3], M[2][3]};
    }

    // ==================== 棋子映射到网格 ====================

    /**
     * 把检测到的棋子坐标通过单应性变换映射到 19×19 网格。
     * 4 角 → [0,0],[1,0],[1,1],[0,1] 单位正方形。
     * 棋子 → (rx,ry) ∈ [0,1] → 网格 (col,row) = round(rx*18, round(ry*18))。
     * 多棋子落在同一格:按 score 降序,高分胜出。
     */
    private static boolean mapStonesToGrid(List<Detection> stones, float[][] corners, int[][] board,
                                         RecognitionSettings rs) {
        // 兼容性签名:无图像尺寸时无法降级重试,直接走原逻辑
        return mapStonesToGrid(stones, corners, board, -1, -1, rs);
    }

    /**
     * @return true 表示几何疑似异常(过多棋子偏差过大/冲突,可能角点不准),建议调用方降级到
     *             棋子 bbox 拟合网格重试;false 表示映射质量正常。
     */
    private static boolean mapStonesToGrid(List<Detection> stones, float[][] corners, int[][] board,
                                         int imgW, int imgH,
                                         RecognitionSettings rs) {
        float[][] dst = {
            {0, 0}, {1, 0}, {1, 1}, {0, 1}
        };
        float[] H = Perspective.computeHomography(corners, dst);
        if (H == null) {
            Log.w(TAG, "单应性矩阵计算失败(4 角共线或退化)");
            if (imgW > 0 && stones.size() >= 4) {
                Log.w(TAG, "→ 降级用棋子 bbox 拟合网格重试(保住已检棋子)");
                corners = fitUniformGridCorners(stones, imgW, imgH);
                H = Perspective.computeHomography(corners, dst);
            }
            if (H == null) {
                Log.w(TAG, "重试仍失败, 不映射棋子");
                return true;
            }
        }
        Log.d(TAG, "单应性矩阵 H: "
                + String.format("[%.4f %.4f %.4f; %.4f %.4f %.4f; %.4f %.4f %.4f]",
                    H[0], H[1], H[2], H[3], H[4], H[5], H[6], H[7], H[8]));
        // Sanity check: 把 4 角本身映射回去,应该接近 (0,0)(1,0)(1,1)(0,1)
        // 误差大说明 H 矩阵错误(4 角顺序错乱 / 位置不合理)
        for (int i = 0; i < 4; i++) {
            float[] r = Perspective.applyHomography(H, corners[i][0], corners[i][1]);
            float ex = r[0] - dst[i][0];
            float ey = r[1] - dst[i][1];
            Log.d(TAG, String.format("H 验证 corner[%d](%.0f,%.0f) → r(%.3f,%.3f) 期望(%.0f,%.0f) 误差(%.3f,%.3f)",
                    i, corners[i][0], corners[i][1], r[0], r[1], dst[i][0], dst[i][1], ex, ey));
            if (Math.abs(ex) > 0.1f || Math.abs(ey) > 0.1f) {
                Log.w(TAG, "H 矩阵 sanity check 失败: corner[" + i + "] 映射误差过大, H 不可用");
                if (imgW > 0 && stones.size() >= 4) {
                    Log.w(TAG, "→ 降级用棋子 bbox 拟合网格重试(保住已检棋子)");
                    corners = fitUniformGridCorners(stones, imgW, imgH);
                    H = Perspective.computeHomography(corners, dst);
                    if (H != null) break; // 重试成功,跳出 sanity 校验
                }
                return true; // 不映射棋子,留空棋盘
            }
        }
        // 按 score 降序
        stones.sort((a, b) -> Float.compare(b.score, a.score));
        Set<Long> occupied = new HashSet<>();
        int droppedOutOfRange = 0, droppedConflict = 0, droppedFarFromGrid = 0;
        // 偏差分布统计:0~0.1, 0.1~0.25, 0.25~0.5, 0.5+
        int[] devBuckets = new int[4];
        StringBuilder mapLog = new StringBuilder();
        // 距离过滤阈值:偏离网格交叉点过大的棋子视为可疑,丢弃。
        // 原 0.5 偏小,四角/H 不准时整盘网格会系统性偏移半格,导致真子(尤其最底/最右行)
        // 偏差达 0.5+ 被误丢 → 表现为"少一行"。放宽到 0.72 可在保留误检过滤的同时,
        // 把因网格整体偏移而偏差 0.5~0.72 的真子救回。现由设置项 maxDeviation 控制。
        final float MAX_DEVIATION = rs.maxDeviation;
        // 记录棋子归一化坐标的范围,用于检测 H 矩阵导致的整盘横向/纵向溢出
        float rxMin = 1f, rxMax = 0f, ryMin = 1f, ryMax = 0f;
        for (Detection d : stones) {
            float[] r = Perspective.applyHomography(H, d.cx, d.cy);
            float gridX = r[0] * (BOARD_SIZE - 1);
            float gridY = r[1] * (BOARD_SIZE - 1);
            if (r[0] < rxMin) rxMin = r[0];
            if (r[0] > rxMax) rxMax = r[0];
            if (r[1] < ryMin) ryMin = r[1];
            if (r[1] > ryMax) ryMax = r[1];
            int col = Math.round(gridX);
            int row = Math.round(gridY);
            float dx = gridX - col;
            float dy = gridY - row;
            float dev = (float) Math.sqrt(dx * dx + dy * dy);
            // 偏差分布统计
            if (dev < 0.1f) devBuckets[0]++;
            else if (dev < 0.25f) devBuckets[1]++;
            else if (dev < 0.5f) devBuckets[2]++;
            else devBuckets[3]++;
            String kind = (d.classId == CLASS_BLACK_STONE) ? "黑" : "白";
            String coord = String.format("(%.0f,%.0f)→r(%.3f,%.3f)→网格(%d,%d) 偏差=%.2f",
                    d.cx, d.cy, r[0], r[1], col, row, dev);
            if (col < 0 || col >= BOARD_SIZE || row < 0 || row >= BOARD_SIZE) {
                droppedOutOfRange++;
                mapLog.append("-").append(kind).append(coord).append(" 越界\n");
                continue;
            }
            // 距离过滤:偏离网格点太远的棋子视为误检,丢弃
            if (dev > MAX_DEVIATION) {
                droppedFarFromGrid++;
                mapLog.append("-").append(kind).append(coord).append(" 偏差过大\n");
                continue;
            }
            long key = (long) col * BOARD_SIZE + row;
            if (occupied.contains(key)) {
                droppedConflict++;
                mapLog.append("-").append(kind).append(coord).append(" 冲突\n");
                continue;
            }
            occupied.add(key);
            board[row][col] = (d.classId == CLASS_BLACK_STONE) ? BLACK : WHITE;
            mapLog.append("+").append(kind).append(coord)
                    .append(String.format(" s=%.3f\n", d.score));
        }
        Log.d(TAG, "棋子映射:\n" + mapLog
                + "合计: 落子" + occupied.size() + " 越界" + droppedOutOfRange
                + " 偏差过大" + droppedFarFromGrid + " 冲突" + droppedConflict);
        Log.i(TAG, String.format("偏差分布: [<0.1]=%d [0.1-0.25)=%d [0.25-0.5)=%d [0.5+]=%d",
                devBuckets[0], devBuckets[1], devBuckets[2], devBuckets[3]));
        // 几何异常判定:过多棋子偏差过大/冲突 → 角点/H 不准,建议降级到棋子 bbox 拟合
        boolean badGeometry = (droppedFarFromGrid + droppedConflict) > stones.size() * 0.10f;
        // 整盘溢出检测:若棋子归一化坐标整体越出 [0,1](四角把棋盘画大/画偏),
        // 说明 H 矩阵不准(典型:最右列 rx>1、最底行 ry>1),整行/整列会被系统性偏移丢失。
        // 余量 overflowMargin 给正常透视一点余量;超过则降级到数据驱动的 fitUniformGridCorners。
        float m = rs.overflowMargin;
        boolean overflow = rxMax > 1f + m || rxMin < -m || ryMax > 1f + m || ryMin < -m;
        if (overflow) {
            Log.w(TAG, String.format("几何疑似异常: 整盘溢出 rx[%.3f,%.3f] ry[%.3f,%.3f] 超界, 可能四角不准 → 降级棋子bbox拟合",
                    rxMin, rxMax, ryMin, ryMax));
            badGeometry = true;
        }
        if (badGeometry && !overflow) {
            Log.w(TAG, String.format("几何疑似异常: 偏差过大%d + 冲突%d 占比 %.0f%% > 10%%, 建议降级棋子bbox拟合",
                    droppedFarFromGrid, droppedConflict,
                    (droppedFarFromGrid + droppedConflict) * 100f / stones.size()));
        }
        return badGeometry;
    }

    /**
     * 增强颜色二次验证:
     *  - 棋子采样使用内缩半径(避开棋子边缘阴影/反光高光带)
     *  - 棋盘背景采样 16 方向 2× 半径,远离棋子阴影
     *  - 使用裁剪均值(去掉 top/bottom 10% 极值)抗噪
     *  - 自适应阈值:根据局部对比度(|d|/std)决策,比固定阈值更稳
     */
    private static void verifyStonesColor(List<Detection> stones,
                                          android.graphics.Bitmap bmp,
                                          RecognitionSettings rs) {
        if (bmp == null || stones.isEmpty()) return;
        final int w = bmp.getWidth();
        final int h = bmp.getHeight();
        int corrected = 0;

        // 预取整幅图像像素数组(避免 getPixel 反复 JNI 调用)
        int[] pix = new int[w * h];
        try { bmp.getPixels(pix, 0, w, 0, 0, w, h); }
        catch (Exception e) { pix = null; }

        for (Detection d : stones) {
            int cx = Math.round(d.cx);
            int cy = Math.round(d.cy);
            if (cx < 8 || cx >= w - 8 || cy < 8 || cy >= h - 8) continue;

            int stoneR = Math.max(4, (int) (Math.min(d.w, d.h) * 0.35f));

            // ===== 棋子亮度:内缩半径 40% 的正方形采样 =====
            // 只采真正的中心区域 (5x5 以内),避开棋子阴影外沿和反光高光边
            int innerR = Math.max(2, Math.min(stoneR / 2, 5));
            java.util.List<Float> sVals = new java.util.ArrayList<>(innerR * innerR * 4);
            for (int y = cy - innerR; y <= cy + innerR; y++) {
                for (int x = cx - innerR; x <= cx + innerR; x++) {
                    if (x < 0 || x >= w || y < 0 || y >= h) continue;
                    int px = pix != null ? pix[y * w + x] : bmp.getPixel(x, y);
                    float lum = lumOf(px);
                    sVals.add(lum);
                }
            }
            if (sVals.isEmpty()) continue;
            float stoneLum = trimmedMean(sVals); // 去掉 10% 极端值

            // ===== 棋盘背景亮度:16 方向 2× stoneR 环形采样 =====
            // 放在棋子 2 倍半径外(避免阴影覆盖),采样半径=背景一个格子内的棋盘木色
            int ringDist = Math.max(8, Math.round(stoneR * 2.0f));
            final int NUM_DIRS = 16;
            java.util.List<Float> bVals = new java.util.ArrayList<>(NUM_DIRS);
            for (int k = 0; k < NUM_DIRS; k++) {
                double ang = k * 2.0 * Math.PI / NUM_DIRS;
                int rx = (int) Math.round(cx + Math.cos(ang) * ringDist);
                int ry = (int) Math.round(cy + Math.sin(ang) * ringDist);
                if (rx < 2 || rx >= w - 2 || ry < 2 || ry >= h - 2) continue;
                // 每个方向采 3x3 小块再取平均(抗木纹纹理噪声)
                float bl = 0; int bc = 0;
                for (int dy2 = -1; dy2 <= 1; dy2++) {
                    for (int dx2 = -1; dx2 <= 1; dx2++) {
                        int px = pix != null ? pix[(ry + dy2) * w + (rx + dx2)]
                                             : bmp.getPixel(rx + dx2, ry + dy2);
                        bl += lumOf(px); bc++;
                    }
                }
                if (bc > 0) bVals.add(bl / bc);
            }
            if (bVals.size() < 4) continue;
            float boardLum = trimmedMean(bVals);

            // ===== 自适应阈值决策 =====
            // 对比度:用背景亮度标准差(木纹变化程度)估计
            float bgStd = stdDev(bVals, boardLum);
            // 归一化决策边界:棋子比棋盘暗/亮 n 倍背景标准差
            //   |diff| > k * max(std, 8), k 由设置 boundaryK 控制
            float k = rs.boundaryK;
            float boundary = Math.max(8f, k * bgStd);
            // 最小/最大绝对门限(极端光照下兜底 / 防止低噪声场景过于敏感),由设置控制
            boundary = Math.max(boundary, rs.boundaryMin);
            boundary = Math.min(boundary, rs.boundaryMax);

            float diff = stoneLum - boardLum;
            int newClass = d.classId;
            // 相对判断优先(自适应)
            if (diff < -boundary) newClass = CLASS_BLACK_STONE;
            else if (diff > boundary) newClass = CLASS_WHITE_STONE;
            // 绝对亮度兜底(极端亮/暗直接判),由设置 absBlackLum/absWhiteLum 控制
            else if (stoneLum < rs.absBlackLum) newClass = CLASS_BLACK_STONE;
            else if (stoneLum > rs.absWhiteLum) newClass = CLASS_WHITE_STONE;

            if (newClass != d.classId) {
                if (corrected < 10) { // 只打前 10 条日志避免刷屏
                    Log.d(TAG, String.format("颜色修正: (%.0f,%.0f) 模型=%s "
                                    + "石=%.0f 盘=%.0f 差=%.0f σbg=%.0f θ=%.0f → %s",
                            d.cx, d.cy,
                            d.classId == CLASS_BLACK_STONE ? "黑" : "白",
                            stoneLum, boardLum, diff, bgStd, boundary,
                            newClass == CLASS_BLACK_STONE ? "黑" : "白"));
                }
                d.classId = newClass;
                corrected++;
            }
        }
        if (corrected > 0) {
            Log.i(TAG, "增强颜色验证修正 " + corrected + " 个棋子");
        }
    }

    private static float lumOf(int px) {
        int r = (px >> 16) & 0xFF;
        int g = (px >> 8) & 0xFF;
        int b = px & 0xFF;
        return 0.299f * r + 0.587f * g + 0.114f * b;
    }

    /** 裁剪均值:去掉最高/最低各 10% 后求平均,抗极端值(高光/阴影像素)。 */
    private static float trimmedMean(java.util.List<Float> vals) {
        if (vals.isEmpty()) return 0;
        int n = vals.size();
        java.util.Collections.sort(vals);
        int lo = (int) Math.ceil(n * 0.10);
        int hi = n - (int) Math.ceil(n * 0.10);
        if (hi <= lo) return vals.get(n / 2); // 退化到中值
        float sum = 0; int c = 0;
        for (int i = lo; i < hi; i++) { sum += vals.get(i); c++; }
        return c > 0 ? sum / c : vals.get(n / 2);
    }

    private static float stdDev(java.util.List<Float> vals, float mean) {
        if (vals.size() < 2) return 0;
        float s = 0;
        for (float v : vals) { float d = v - mean; s += d * d; }
        return (float) Math.sqrt(s / (vals.size() - 1));
    }

    // ==================== 工具 ====================

    private static float sigmoid(float x) {
        return 1f / (1f + (float) Math.exp(-x));
    }

    private static int countColor(int[][] board, int color) {
        int n = 0;
        for (int[] row : board) for (int v : row) if (v == color) n++;
        return n;
    }

    /** 统计 stones 列表中某类棋子的数量。 */
    private static int countClass(List<Detection> stones, int classId) {
        int n = 0;
        for (Detection d : stones) if (d.classId == classId) n++;
        return n;
    }

    /** 维护 top3 分数(降序)的轻量数组更新。top 长度必须 >= 3。 */
    private static void updateTop(float[] top, float score) {
        if (score <= top[2]) return;
        // 插入到合适位置,保持降序
        if (score > top[0]) {
            top[2] = top[1]; top[1] = top[0]; top[0] = score;
        } else if (score > top[1]) {
            top[2] = top[1]; top[1] = score;
        } else {
            top[2] = score;
        }
    }

    /**
     * 非极大值抑制 (NMS):按 score 降序排列,依次保留高分检测,
     * 移除与已保留检测 IoU > iouThreshold 的低分检测。
     * 跨类别执行 (黑白棋子不会在同一位置重叠,故全局 NMS 更合理)。
     */
    private static List<Detection> applyNMS(List<Detection> dets, float iouThreshold) {
        if (dets.size() <= 1) return new ArrayList<>(dets);
        List<Detection> sorted = new ArrayList<>(dets);
        sorted.sort((a, b) -> Float.compare(b.score, a.score));
        List<Detection> kept = new ArrayList<>();
        boolean[] suppressed = new boolean[sorted.size()];
        for (int i = 0; i < sorted.size(); i++) {
            if (suppressed[i]) continue;
            kept.add(sorted.get(i));
            for (int j = i + 1; j < sorted.size(); j++) {
                if (suppressed[j]) continue;
                if (sorted.get(i).iou(sorted.get(j)) > iouThreshold) {
                    suppressed[j] = true;
                }
            }
        }
        return kept;
    }

    /**
     * 加权框融合 (Weighted Boxes Fusion, WBF / ZFTurbo):
     * 对 TTA 多路产生的重叠检测框,不硬丢弃低分框,而是按置信度加权平均取中心和尺寸。
     * 相比 NMS:中心定位更准 (多视角投票),网格偏差更小 → 更多棋子通过 MAX_DEVIATION 过滤。
     *
     * 算法:
     *   1. 按 score 降序遍历
     *   2. 每个检测与所有 cluster 的 "代表框" 算 IoU;匹配到第一个 IoU>阈值的 cluster
     *   3. 加入 cluster 后更新 cluster 代表框 = 当前内部加权均值(online 更新)
     *   4. 每个 cluster 产出 1 个融合检测
     */
    private static List<Detection> applyWBF(List<Detection> dets, float iouThreshold) {
        int N = dets.size();
        if (N <= 1) return new ArrayList<>(dets);

        // 按 score 降序
        List<Detection> sorted = new ArrayList<>(dets);
        sorted.sort((a, b) -> Float.compare(b.score, a.score));

        // 每个 cluster:存 det 列表 + 运行时加权中心/尺寸(online 融合代表框)
        List<List<Detection>> clusters = new ArrayList<>();
        // 每个 cluster 的 "加权和 / 总权重",用作代表框做 IoU 匹配
        List<float[]> clusterRep = new ArrayList<>(); // [wCx, wCy, wW, wH, wSum]
        // 每个 cluster 的 class 加权票数: [sumBlackScore, sumWhiteScore]
        List<float[]> classVote = new ArrayList<>();

        for (Detection d : sorted) {
            int bestIdx = -1;
            float bestIoU = 0;
            for (int i = 0; i < clusters.size(); i++) {
                float[] rep = clusterRep.get(i);
                float ws = rep[4];
                if (ws <= 0) continue;
                float rcx = rep[0] / ws, rcy = rep[1] / ws;
                float rw  = rep[2] / ws, rh  = rep[3] / ws;
                // 构造虚拟 Detection 算 IoU
                float iou = iouOf(rcx, rcy, rw, rh, d.cx, d.cy, d.w, d.h);
                if (iou > bestIoU && iou > iouThreshold) {
                    bestIoU = iou;
                    bestIdx = i;
                }
            }
            if (bestIdx < 0) {
                // 新 cluster
                List<Detection> cl = new ArrayList<>();
                cl.add(d);
                clusters.add(cl);
                clusterRep.add(new float[]{
                        d.cx * d.score, d.cy * d.score,
                        d.w * d.score, d.h * d.score,
                        d.score
                });
                float[] votes = new float[2];
                votes[d.classId == CLASS_BLACK_STONE ? 0 : 1] = d.score;
                classVote.add(votes);
            } else {
                clusters.get(bestIdx).add(d);
                float[] rep = clusterRep.get(bestIdx);
                rep[0] += d.cx * d.score;
                rep[1] += d.cy * d.score;
                rep[2] += d.w * d.score;
                rep[3] += d.h * d.score;
                rep[4] += d.score;
                float[] v = classVote.get(bestIdx);
                v[d.classId == CLASS_BLACK_STONE ? 0 : 1] += d.score;
            }
        }

        // 每个 cluster 产出 1 个融合 Detection
        List<Detection> out = new ArrayList<>(clusters.size());
        for (int i = 0; i < clusters.size(); i++) {
            List<Detection> cl = clusters.get(i);
            if (cl.isEmpty()) continue;
            float[] rep = clusterRep.get(i);
            float ws = rep[4];
            float cx = rep[0] / ws, cy = rep[1] / ws;
            float w  = rep[2] / ws, h  = rep[3] / ws;
            // class:以加权和高的类为准 (黑+0 vs 白+1)
            float[] v = classVote.get(i);
            int cls = (v[0] >= v[1]) ? CLASS_BLACK_STONE : CLASS_WHITE_STONE;
            // 融合后的 score:原 WBF 取平均,这里取 max(保序) + 融合增益 10%
            float maxSc = 0; float sumSc = 0;
            for (Detection x : cl) {
                if (x.score > maxSc) maxSc = x.score;
                sumSc += x.score;
            }
            int t = cl.size();
            float avg = sumSc / t;
            // 融合 score:折中策略,既保留 max 的判别性,又体现多视角一致度
            float fusedScore = Math.min(1.0f, 0.75f * maxSc + 0.35f * avg * Math.min(t, 4) / 4f);
            out.add(new Detection(cx, cy, w, h, cls, fusedScore));
        }
        return out;
    }

    private static float iouOf(float acx, float acy, float aw, float ah,
                               float bcx, float bcy, float bw, float bh) {
        float x1 = Math.max(acx - aw / 2f, bcx - bw / 2f);
        float y1 = Math.max(acy - ah / 2f, bcy - bh / 2f);
        float x2 = Math.min(acx + aw / 2f, bcx + bw / 2f);
        float y2 = Math.min(acy + ah / 2f, bcy + bh / 2f);
        float iw = Math.max(0, x2 - x1), ih = Math.max(0, y2 - y1);
        float inter = iw * ih;
        float union = aw * ah + bw * bh - inter;
        return union > 0 ? inter / union : 0;
    }

    // ==================== 漏检恢复:网格反投影 + 像素验证 ====================

    /**
     * 模型漏检恢复。思路:
     *   棋盘 4 角 → 单应性矩阵 H (图像→网格[0,1])
     *   求 H⁻¹ (网格→图像),把 19×19 每个空位的 (col/18, row/18) 投影回原图位置,
     *   用与 verifyStonesColor 完全一致的"棋子vs背景"亮度逻辑验证是否真有棋子。
     *   为减少误检,只在空位"邻域有 ≥2 个已识别棋子"(位于有棋的局部区域)时触发。
     *
     * 返回补回的棋子总数。
     */
    private static int recoverMissedStones(android.graphics.Bitmap bmp,
                                           float[][] corners, int[][] board,
                                           RecognitionSettings rs) {
        if (bmp == null) return 0;
        final int w = bmp.getWidth(), h = bmp.getHeight();

        // H:图像 (x,y) → 网格归一化 (rx,ry)
        float[][] dst = {{0,0},{1,0},{1,1},{0,1}};
        float[] H = Perspective.computeHomography(corners, dst);
        if (H == null) return 0;
        float[] Hinv = invert3x3(H);
        if (Hinv == null) return 0;

        // 一次性取整图像素,避免 getPixel JNI 开销
        int[] pix = new int[w * h];
        try { bmp.getPixels(pix, 0, w, 0, 0, w, h); }
        catch (Exception e) { pix = null; }

        // 估算一个"格子像素尺寸"用于决定采样半径:
        //   TL-TR 距离 / 18 ≈ 每格像素边长
        float cellPx = (float) Math.hypot(
                corners[1][0] - corners[0][0], corners[1][1] - corners[0][1]) / 18f;
        float cellPx2 = (float) Math.hypot(
                corners[2][0] - corners[3][0], corners[2][1] - corners[3][1]) / 18f;
        cellPx = (cellPx + cellPx2) * 0.5f;
        if (cellPx < 6) return 0; // 像素过低不可靠

        // 预统计棋盘上的棋子数,用于决定扫描稀疏度
        int stoneR = Math.max(4, (int) (cellPx * 0.35f));
        int ringDist = Math.max(8, Math.round(stoneR * 2.0f));
        // 边界安全距离:保证采样环完整落在图内,边角网格点若太贴近图像边缘则跳过
        // (judgeStoneAt 对越界采样点会安全跳过,但样本过少时统计不可靠,宁可不补也不误补)
        int pad = ringDist + 4;

        int recovered = 0;
        StringBuilder detailLog = new StringBuilder();

        // 已落子数:若棋盘已识别足够多子(说明确实是真实棋盘、几何基本可信),
        // 则对"孤立空位"(四周无已识别子,如整行漏检)也尝试像素验证补救,
        // 避免某行/某列因模型整行漏检而整行消失。强边界(strongBoundary)已防误补。
        int placed = 0;
        for (int r = 0; r < BOARD_SIZE; r++)
            for (int c = 0; c < BOARD_SIZE; c++)
                if (board[r][c] != EMPTY) placed++;
        // 已落子数达到 minPlaced(默认 12)才允许补救孤立空位(含整行漏检),
        // 避免空盘/接近空盘误补。阈值由设置项 minPlaced 控制。
        boolean allowLonely = placed >= rs.minPlaced;

        for (int r = 0; r < BOARD_SIZE; r++) {
            for (int c = 0; c < BOARD_SIZE; c++) {
                if (board[r][c] != EMPTY) continue;

                // 邻域条件:Manhattan ≤2 内至少 1 颗已识别棋子才尝试恢复。
                // (边角棋子周围常不足 2 子,此前 neigh<2 的门槛导致边角漏检无法补救;
                //  放宽到 ≥1 可在不显著增加误补的前提下补回边角漏子)
                int neigh = 0;
                for (int dr = -2; dr <= 2; dr++) {
                    for (int dc = -2; dc <= 2; dc++) {
                        if (dr == 0 && dc == 0) continue;
                        int nr = r + dr, nc = c + dc;
                        if (nr < 0 || nr >= BOARD_SIZE || nc < 0 || nc >= BOARD_SIZE) continue;
                        if (Math.abs(dr) + Math.abs(dc) > 2) continue;
                        if (board[nr][nc] != EMPTY) neigh++;
                    }
                }
                // 孤立空位(neigh<1):仅当棋盘已识别足够多子时才补救,避免空盘误补
                if (neigh < 1 && !allowLonely) continue;

                // H⁻¹: 归一化网格 (c/18, r/18) → 图像 (x,y)
                float rx = (float) c / (BOARD_SIZE - 1);
                float ry = (float) r / (BOARD_SIZE - 1);
                float[] imgPt = Perspective.applyHomography(Hinv, rx, ry);
                int ix = Math.round(imgPt[0]);
                int iy = Math.round(imgPt[1]);
                if (ix < pad || ix >= w - pad || iy < pad || iy >= h - pad) continue;

                // 与 verifyStonesColor 同逻辑判断黑/白
                int judged = judgeStoneAt(ix, iy, stoneR, ringDist, w, h, pix, rs);
                if (judged == EMPTY) continue;

                board[r][c] = judged;
                recovered++;
                if (detailLog.length() < 512) {
                    detailLog.append(String.format(" (%d,%d)img=(%d,%d)→%s",
                            c, r, ix, iy, judged == BLACK ? "黑" : "白"));
                }
            }
        }
        if (recovered > 0) {
            Log.d(TAG, "漏检恢复明细:" + detailLog);
        }
        return recovered;
    }

    /** 给定图像位置,用"棋子中心 vs 16方向背景环"亮度对比判断此处 EMPTY/BLACK/WHITE。 */
    private static int judgeStoneAt(int cx, int cy, int stoneR, int ringDist,
                                    int w, int h, int[] pix,
                                    RecognitionSettings rs) {
        // 1) 棋子中心:内缩半径 40% 方形
        int innerR = Math.max(2, Math.min(stoneR / 2, 5));
        java.util.List<Float> sVals = new java.util.ArrayList<>(innerR * innerR * 4);
        for (int y = cy - innerR; y <= cy + innerR; y++) {
            for (int x = cx - innerR; x <= cx + innerR; x++) {
                if (x < 0 || x >= w || y < 0 || y >= h) continue;
                int px = pix != null ? pix[y * w + x] : 0;
                sVals.add(lumOf(px));
            }
        }
        if (sVals.size() < 9) return EMPTY;
        float stoneLum = trimmedMean(sVals);

        // 2) 背景环:16 方向,每方向 3x3 平均
        final int NUM_DIRS = 16;
        java.util.List<Float> bVals = new java.util.ArrayList<>(NUM_DIRS);
        for (int k = 0; k < NUM_DIRS; k++) {
            double ang = k * 2.0 * Math.PI / NUM_DIRS;
            int rx = (int) Math.round(cx + Math.cos(ang) * ringDist);
            int ry = (int) Math.round(cy + Math.sin(ang) * ringDist);
            if (rx < 2 || rx >= w - 2 || ry < 2 || ry >= h - 2) continue;
            float bl = 0; int bc = 0;
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int px = pix != null ? pix[(ry + dy) * w + (rx + dx)] : 0;
                    bl += lumOf(px); bc++;
                }
            }
            if (bc > 0) bVals.add(bl / bc);
        }
        if (bVals.size() < 6) return EMPTY;
        float boardLum = trimmedMean(bVals);
        float bgStd = stdDev(bVals, boardLum);

        // 3) 决策:阈值比 verifyStonesColor 更保守(宁漏不误检),由设置控制
        float boundary = Math.max(rs.boundaryBase, rs.boundaryK * bgStd);
        boundary = Math.max(boundary, rs.boundaryMin);     // 绝对最小门限(默认 26,更严)
        boundary = Math.min(boundary, rs.boundaryMax);

        float diff = stoneLum - boardLum;
        float strongBoundary = boundary * rs.strongBoundaryFactor;
        if (diff < -strongBoundary) return BLACK;                 // 比棋盘暗 → 黑
        if (diff > strongBoundary) return WHITE;                 // 比棋盘亮 → 白
        if (stoneLum < rs.absBlackLum) return BLACK;                    // 绝对黑兜底
        if (stoneLum > rs.absWhiteLum) return WHITE;                   // 绝对白兜底
        return EMPTY;                                       // 拿不准就空
    }

    /**
     * 与 judgeStoneAt 同逻辑的"强信号"版本:返回 {state, confident}。
     *   state    ∈ {EMPTY, BLACK, WHITE}
     *   confident=1 表示判定基于"强边界"(|diff| ≥ strongBoundary)或绝对黑/白兜底,
     *             即高置信;confident=0 表示"弱信号"(边界内但靠绝对亮度兜底)或拿不准。
     *
     * 全棋盘逐点复核用它区分"确定有子"与"弱信号",避免用弱信号覆盖/修正模型结果。
     */
    private static int[] judgeStoneConfident(int cx, int cy, int stoneR, int ringDist,
                                             int w, int h, int[] pix,
                                             RecognitionSettings rs) {
        int innerR = Math.max(2, Math.min(stoneR / 2, 5));
        java.util.List<Float> sVals = new java.util.ArrayList<>(innerR * innerR * 4);
        for (int y = cy - innerR; y <= cy + innerR; y++) {
            for (int x = cx - innerR; x <= cx + innerR; x++) {
                if (x < 0 || x >= w || y < 0 || y >= h) continue;
                int px = pix != null ? pix[y * w + x] : 0;
                sVals.add(lumOf(px));
            }
        }
        if (sVals.size() < 9) return new int[]{EMPTY, 0};
        float stoneLum = trimmedMean(sVals);

        final int NUM_DIRS = 16;
        java.util.List<Float> bVals = new java.util.ArrayList<>(NUM_DIRS);
        for (int k = 0; k < NUM_DIRS; k++) {
            double ang = k * 2.0 * Math.PI / NUM_DIRS;
            int rx = (int) Math.round(cx + Math.cos(ang) * ringDist);
            int ry = (int) Math.round(cy + Math.sin(ang) * ringDist);
            if (rx < 2 || rx >= w - 2 || ry < 2 || ry >= h - 2) continue;
            float bl = 0; int bc = 0;
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int px = pix != null ? pix[(ry + dy) * w + (rx + dx)] : 0;
                    bl += lumOf(px); bc++;
                }
            }
            if (bc > 0) bVals.add(bl / bc);
        }
        if (bVals.size() < 6) return new int[]{EMPTY, 0};
        float boardLum = trimmedMean(bVals);
        float bgStd = stdDev(bVals, boardLum);

        float boundary = Math.max(rs.boundaryBase, rs.boundaryK * bgStd);
        boundary = Math.max(boundary, rs.boundaryMin);
        boundary = Math.min(boundary, rs.boundaryMax);

        float diff = stoneLum - boardLum;
        float strongBoundary = boundary * rs.strongBoundaryFactor;
        if (diff < -strongBoundary) return new int[]{BLACK, 1};
        if (diff > strongBoundary) return new int[]{WHITE, 1};
        if (stoneLum < rs.absBlackLum)       return new int[]{BLACK, 1};   // 绝对黑兜底(高置信)
        if (stoneLum > rs.absWhiteLum)      return new int[]{WHITE, 1};   // 绝对白兜底(高置信)
        return new int[]{EMPTY, 0};                            // 拿不准就空
    }

    /**
     * 【全棋盘逐交叉点独立复核 —— "逐格分类范式"的像素实现】
     *
     * 思路:在已求解的四角网格(H 矩阵)下,对 19×19 全部 361 个交叉点,在反投影回图像的
     * 对应位置**逐个独立**判定 空/黑/白;再把"独立判定"与"检测+映射"的结果融合。
     *
     * 为什么能根治"少一行/四边缺失/整行漏检":
     *   - 现有流程的识别完全依赖模型对某行/某边的检测召回;一旦模型整行没检出,
     *     该行既无检测结果、recover 又可能因孤立被跳过 → 整行消失。
     *   - 本模块不依赖检测召回,每一格都独立做像素判定,从机制上杜绝整行漏检。
     *
     * 融合规则(只增信、不乱改,避免弱信号误伤):
     *   - board 当前为空 且 独立判定"强信号有子"(confident=1) → 补入(覆盖整行漏检/四边缺)
     *   - board 当前有子 且 独立判定"强相反"(confident=1 且异色) → 修正(去误检)
     *   - 其余(弱信号/一致/拿不准) → 保留模型结果,不动
     *
     * 仅在已识别棋子数 ≥ MIN_PLACED(确认真棋盘)时启用,空盘/无子图不触发,防误补。
     *
     * @return 实际改动(补入+修正)的格子数
     */
    private static int fullBoardReview(Bitmap bmp, float[][] corners, int[][] board,
                                       RecognitionSettings rs) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        if (w <= 0 || h <= 0) return 0;

        // 统计已放置数,确认真棋盘才启用全棋盘复核(阈值由设置 minPlaced 控制)
        int placed = 0;
        for (int r = 0; r < BOARD_SIZE; r++)
            for (int c = 0; c < BOARD_SIZE; c++)
                if (board[r][c] != EMPTY) placed++;
        if (placed < rs.minPlaced) return 0;

        // 与 recoverMissedStones 一致:H 将"图像(x,y)"映射到"归一化网格(0..1)"
        float[][] dst = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
        float[] H = Perspective.computeHomography(corners, dst);
        if (H == null) return 0;

        int[] pix = new int[w * h];
        bmp.getPixels(pix, 0, w, 0, 0, w, h);

        // 计算相邻交叉点间距(像素),用于确定采样半径 stoneR 与环距 ringDist。
        // 归一化坐标:第 c 列 = c/(N-1),第 r 行 = r/(N-1)
        float[] p00 = Perspective.applyHomography(H, 0f, 0f);
        float[] p10 = Perspective.applyHomography(H, 1f / (BOARD_SIZE - 1), 0f);
        double spacing = Math.hypot(p10[0] - p00[0], p10[1] - p00[1]);
        double stoneR = Math.max(4, spacing * 0.42);
        double ringDist = Math.max(4, spacing * 0.60);

        int changed = 0, added = 0, corrected = 0;
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < BOARD_SIZE; r++) {
            for (int c = 0; c < BOARD_SIZE; c++) {
                float rx = (float) c / (BOARD_SIZE - 1);
                float ry = (float) r / (BOARD_SIZE - 1);
                float[] ip = Perspective.applyHomography(H, rx, ry);
                int cx = (int) Math.round(ip[0]);
                int cy = (int) Math.round(ip[1]);
                if (cx < 2 || cx >= w - 2 || cy < 2 || cy >= h - 2) continue;

                int cur = board[r][c];
                int[] j = judgeStoneConfident(cx, cy, (int) stoneR, (int) ringDist, w, h, pix, rs);
                int state = j[0], conf = j[1];

                if (cur == EMPTY) {
                    if (state != EMPTY && conf == 1) {
                        board[r][c] = state;
                        added++; changed++;
                        if (sb.length() < 200)
                            sb.append(String.format(" 补[%d,%d]=%s ", r, c, state == BLACK ? "黑" : "白"));
                    }
                } else {
                    if (state != EMPTY && state != cur && conf == 1) {
                        board[r][c] = state;
                        corrected++; changed++;
                        if (sb.length() < 200)
                            sb.append(String.format(" 改[%d,%d]→%s ", r, c, state == BLACK ? "黑" : "白"));
                    }
                }
            }
        }
        if (changed > 0)
            Log.i(TAG, "全棋盘逐点复核: 改动 " + changed + " 格 (补 " + added + ", 修正误检 " + corrected + ")"
                    + sb);
        return changed;
    }

    /** 求 3×3 矩阵的逆。退化返回 null。 */
    private static float[] invert3x3(float[] m) {
        // m[0..8] = [a b c; d e f; g h i]
        float a = m[0], b = m[1], c = m[2];
        float d = m[3], e = m[4], f = m[5];
        float g = m[6], h = m[7], i = m[8];
        float A =  (e * i - f * h);
        float B = -(d * i - f * g);
        float C =  (d * h - e * g);
        float D = -(b * i - c * h);
        float E =  (a * i - c * g);
        float F = -(a * h - b * g);
        float G =  (b * f - c * e);
        float H = -(a * f - c * d);
        float I =  (a * e - b * d);
        float det = a * A + b * B + c * C;
        if (Math.abs(det) < 1e-9f) return null;
        float invDet = 1f / det;
        return new float[]{
                A * invDet, D * invDet, G * invDet,
                B * invDet, E * invDet, H * invDet,
                C * invDet, F * invDet, I * invDet
        };
    }

    /** 单个检测结果。 */
    private static final class Detection {
        final float cx, cy;
        final float w, h;  // bbox 宽高(原图坐标),用于 NMS
        int classId;  // 颜色验证时会修改,去掉 final
        final float score;
        Detection(float cx, float cy, float w, float h, int classId, float score) {
            this.cx = cx; this.cy = cy; this.w = w; this.h = h;
            this.classId = classId; this.score = score;
        }

        float iou(Detection other) {
            float x1 = Math.max(cx - w / 2f, other.cx - other.w / 2f);
            float y1 = Math.max(cy - h / 2f, other.cy - other.h / 2f);
            float x2 = Math.min(cx + w / 2f, other.cx + other.w / 2f);
            float y2 = Math.min(cy + h / 2f, other.cy + other.h / 2f);
            float iw = Math.max(0, x2 - x1);
            float ih = Math.max(0, y2 - y1);
            float inter = iw * ih;
            float union = w * h + other.w * other.h - inter;
            return union > 0 ? inter / union : 0;
        }
    }
}
