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
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/**
 * Moku 围棋棋盘识别器：支持 RT-DETR 与 YOLOv8 两种 ONNX 检测模型。
 *
 * 算法 1:1 移植自 Kaya 项目 packages/board-recognition/src/{moku-detector,moku-postprocess,
 * perspective,corners}.ts，全部纯 Java 实现，不依赖 OpenCV。
 *
 * 模型自动识别（运行时按输出名/形状分流）：
 *   - RT-DETR (moku-v3): 输入 pixel_values, 双输出 logits + pred_boxes, 300 query
 *   - YOLOv8: 输入 images(动态获取), 单输出 [1,C,N] 或 [1,N,C], C=4+nc,
 *     由 decodeYolov8 转成与 RT-DETR 相同的 query-major 布局, 下游逻辑完全复用。
 *     类别顺序约定与训练一致: 0=黑子, 1=白子, 2=棋盘角点。
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
    public static final float DEFAULT_THRESHOLD = 0.035f;  // 略低于 Kaya 默认 0.05, 召回更多临界棋子
    // 全空重试阈值:高阈值漏检(一个棋子都没识别到)时,用较低阈值重跑一次保召回
    // (低阈值可能引入少量误检,但相比"完全识别不到"更可接受,且用户可手动摆子修正)
    private static final float LOW_RETRY_THRESHOLD = 0.015f;
    // 类别感知阈值:黑棋对比度通常更高,可用略高阈值过滤误检;
    // 白棋在木色棋盘上对比度较低,维持较低阈值保证召回
    // 注: 早期曾用黑白类别感知阈值(黑 +0.003 / 白 -0.020), 与 Kaya 不一致, 已移除
    private static final float CORNER_MIN_THRESHOLD = 0.005f;
    // YOLOv8 输出候选保留阈值:8400 个 anchor 大量低分,转换时先按此过滤,
    // 避免下游 WBF O(n²) 在噪声上退化。此阈值只做粗过滤,细过滤仍由类别感知阈值完成
    private static final float YOLO_KEEP_MIN = 0.002f;
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
        /** 模型实际检测到的棋盘角点数(去重后), 用于裁剪交叉校验等可信度判断。 */
        public int cornerCount;
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
        return recognize(bitmap, DEFAULT_THRESHOLD, false, new RecognitionSettings());
    }

    public RecognitionResult recognize(Bitmap bitmap, float threshold) throws Exception {
        return recognize(bitmap, threshold, new RecognitionSettings());
    }

    /** 使用自定义识别设置(来自设置页)进行识别。 */
    public RecognitionResult recognize(Bitmap bitmap, RecognitionSettings rs) throws Exception {
        return recognize(bitmap, rs.threshold, false, rs);
    }

    /**
     * 完全参照 Kaya moku-detector.detect(): 单次推理 → postprocess。
     * 不做两阶段自动裁剪、不做 TTA 多路融合 —— 与 Kaya 桌面端/Web 端识别路径一致,
     * 避免裁剪误判/多路融合引入的整盘错误。
     *
     * @param alreadyCropped 兼容参数(手动裁剪图)。识别流程与未裁剪完全一致:
     *                       Kaya 对任何输入图统一走 preprocess → inference → postprocess,
     *                       裁剪只影响输入图本身,不改变识别逻辑。
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
        Log.i(TAG, "=== 识别开始(kaya 单次推理流程) === src=" + srcW + "x" + srcH
                + ", threshold=" + threshold);

        // 内存保护:仅在尺寸极大时轻度缩放,避免 OOM(与识别逻辑无关)
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

        // 非正方形图不做裁剪 — 居中裁剪会丢失棋盘边缘信息导致整体偏移。
        // 改为 letterbox: preprocess 内部统一缩放 + 灰色填充到 640×640,
        // 保持完整图像不变形, 棋盘位置信息完整保留。

        // preprocess(拉伸 640, /255) → inference → postprocess, 与 Kaya moku-detector.detect 一致
        long t1 = System.currentTimeMillis();
        PreprocessResult pp = preprocess(bitmap, srcW, srcH);
        float[][] out = runInference(pp.input);
        Log.d(TAG, "推理耗时" + (System.currentTimeMillis() - t1) + "ms"
                + String.format(", 拉伸: scaleX=%.3f scaleY=%.3f new=%dx%d",
                        pp.lb.scaleX, pp.lb.scaleY, pp.lb.newW, pp.lb.newH));

        RecognitionResult rr = postprocessWithTTA(out, bitmap, pp, srcW, srcH, threshold,
                false, rs);
        return maybeRetry(rawBitmap, rr, threshold, rs, null);
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

        // 角点精校准统一在 postprocess 主路径执行(那里有模型角点检测结果):
        // 模型自带角点吸附优先,Harris 图像特征兜底。
        PreprocessResult pp1 = preprocess(bitmap, srcW, srcH);
        float[][] out1 = runInference(pp1.input);
        RecognitionResult rr = postprocessWithTTA(out1, bitmap, pp1, srcW, srcH, threshold,
                false, rs, scaledCorners);
        // 重试传原始角点(未缩放):重试路径内部会再做一次同比例缩放,避免二次缩放错位
        return maybeRetry(rawBitmap, rr, threshold, rs, corners);
    }

    /**
     * 仅检测棋盘四角(前置流程用): 自动推理并返回检测到的四角(原图像素坐标, TL→TR→BR→BL)。
     * 内部走与 {@link #recognize(Bitmap, RecognitionSettings)} 完全一致的推理路径,
     * 保证前置检测到的角点与识别时自动检测的角点逻辑相同。
     * 角点不足时返回图像内缩兜底角点(与 Kaya 一致), 由用户手动调整。
     */
    public float[][] detectCorners(Bitmap bitmap, RecognitionSettings rs) throws Exception {
        RecognitionResult r = recognize(bitmap, rs);
        return r.corners;
    }

    /**
     * 手动注入角点吸附:在每个角点附近 ±SEARCH_R 像素窗口内,
     * 用"棋盘角点"特征(两条暗线交叉)评分找最优位置,弥补手指拖拽的定位误差。
     * 评分 = Harris 角点响应 × 中心暗度权重(棋盘线是暗的,棋子边缘中心亮会被削弱)。
     * 找不到强特征时保持原位置(不激进修改)。
     */
    private static float[][] refineCornersToGrid(Bitmap bmp, float[][] corners, boolean[] locked) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        if (w <= 0 || h <= 0) return corners;
        float[] gray = new float[w * h];
        int[] pix = new int[w * h];
        bmp.getPixels(pix, 0, w, 0, 0, w, h);
        for (int i = 0; i < pix.length; i++) {
            int p = pix[i];
            gray[i] = ((p >> 16) & 0xFF) * 0.299f
                    + ((p >> 8) & 0xFF) * 0.587f
                    + (p & 0xFF) * 0.114f;
        }
        final int SEARCH_R = 24; // 搜索半径
        final int NBR = 5;       // Harris 结构张量邻域半径
        float[][] out = new float[4][2];
        for (int c = 0; c < 4; c++) {
            int cx = Math.round(corners[c][0]);
            int cy = Math.round(corners[c][1]);
            out[c][0] = corners[c][0];
            out[c][1] = corners[c][1];
            // 已被模型再校准吸附的角点:模型定位更准,不再用 Harris 图像特征拉偏
            if (locked != null && locked[c]) {
                Log.d(TAG, String.format("角点%d 已由模型吸附, 跳过 Harris", c));
                continue;
            }
            float bestScore = -Float.MAX_VALUE;
            int bx = cx, by = cy;
            for (int dy = -SEARCH_R; dy <= SEARCH_R; dy++) {
                for (int dx = -SEARCH_R; dx <= SEARCH_R; dx++) {
                    int x = cx + dx;
                    int y = cy + dy;
                    if (x < NBR || x >= w - NBR - 1 || y < NBR || y >= h - NBR - 1) continue;
                    float ixx = 0, iyy = 0, ixy = 0;
                    for (int yy = -NBR; yy <= NBR; yy++) {
                        for (int xx = -NBR; xx <= NBR; xx++) {
                            int x0 = x + xx;
                            int y0 = y + yy;
                            float gx = gray[y0 * w + Math.min(w - 1, x0 + 1)]
                                     - gray[y0 * w + Math.max(0, x0 - 1)];
                            float gy = gray[Math.min(h - 1, y0 + 1) * w + x0]
                                     - gray[Math.max(0, y0 - 1) * w + x0];
                            ixx += gx * gx;
                            iyy += gy * gy;
                            ixy += gx * gy;
                        }
                    }
                    float det = ixx * iyy - ixy * ixy;
                    float trace = ixx + iyy;
                    float harris = det - 0.06f * trace * trace;
                    // 棋盘角交叉处中心像素通常是暗的(线交叉);棋子边缘中心不暗,降低误吸
                    float centerDark = 255f - gray[y * w + x];
                    float score = harris * (0.5f + centerDark / 255f);
                    if (score > bestScore) {
                        bestScore = score;
                        bx = x;
                        by = y;
                    }
                }
            }
            // 只在找到显著特征时才替换(避免背景杂乱区域被吸到无关位置)
            if (bestScore > 50f && (Math.abs(bx - cx) + Math.abs(by - cy)) > 1) {
                out[c][0] = bx;
                out[c][1] = by;
                Log.d(TAG, String.format("角点%d 吸附: (%d,%d) → (%d,%d), score=%.1f",
                        c, cx, cy, bx, by, bestScore));
            } else {
                Log.d(TAG, String.format("角点%d 保持原位: (%d,%d), score=%.1f",
                        c, cx, cy, bestScore));
            }
        }
        return out;
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

    /** 单次 ONNX 推理,返回 [logits, predBoxes] (RT-DETR 兼容的 query-major 布局)。
     *  支持两种模型架构:
     *   - RT-DETR (moku-v3): 双输出 logits + pred_boxes, 300 query
     *   - YOLOv8: 单输出 [1,C,N] 或 [1,N,C], C=4+nc, 由 decodeYolov8 转成 query 布局 */
    private float[][] runInference(float[] input) throws Exception {
        long[] shape = {1L, 3L, (long) INPUT_SIZE, (long) INPUT_SIZE};
        java.nio.FloatBuffer inputBuf = java.nio.FloatBuffer.wrap(input);
        OnnxTensor inputTensor = OnnxTensor.createTensor(env, inputBuf, shape);
        try {
            Map<String, OnnxTensor> feeds = new HashMap<>();
            feeds.put(getInputName(), inputTensor);
            try (OrtSession.Result result = session.run(feeds)) {
                Set<String> outNames = session.getOutputNames();
                if (outNames.contains("logits") && outNames.contains("pred_boxes")) {
                    // RT-DETR: 双输出
                    Object logitsVal = result.get("logits").get().getValue();
                    Object boxesVal = result.get("pred_boxes").get().getValue();
                    float[] logits = flattenFloatArray(logitsVal, NUM_QUERIES * NUM_CLASSES);
                    float[] predBoxes = flattenFloatArray(boxesVal, NUM_QUERIES * 4);
                    Log.d(TAG, "RT-DETR 推理: " + logitsVal.getClass().getSimpleName()
                            + "→len=" + logits.length + ", " + boxesVal.getClass().getSimpleName()
                            + "→len=" + predBoxes.length);
                    return new float[][]{logits, predBoxes};
                }
                // YOLOv8: 单输出, 转成 RT-DETR 兼容 query 布局
                String outName = outNames.iterator().next();
                OnnxValue ov = result.get(outName).get();
                long[] outShape = ((OnnxTensor) ov).getInfo().getShape();
                float[] raw = flattenFloatArray(ov.getValue(), (int) totalLen(outShape));
                float[][] det = decodeYolov8(raw, outShape);
                if (det == null) {
                    throw new IllegalStateException("YOLOv8 输出解析失败: shape="
                            + java.util.Arrays.toString(outShape));
                }
                Log.i(TAG, "YOLOv8 推理: shape=" + java.util.Arrays.toString(outShape)
                        + " → " + (det[0].length / NUM_CLASSES) + " query");
                return det;
            }
        } finally {
            inputTensor.close();
        }
    }

    /** 当前模型的输入名(RT-DETR 为 pixel_values, YOLOv8 通常为 images),动态获取避免硬编码。 */
    private String getInputName() {
        return session.getInputNames().iterator().next();
    }

    private static long totalLen(long[] shape) {
        long t = 1;
        for (long s : shape) t *= s;
        return t;
    }

    /**
     * 把 YOLOv8 单输出解码为 RT-DETR 兼容的 query-major logits/boxes。
     * YOLOv8 输出布局: [1, C, N] (channels-first, 官方导出默认) 或 [1, N, C],
     *   - 前 4 通道: cx, cy, w, h —— letterbox 后输入图内的像素坐标
     *   - 后 nc 通道: 各类别分数 (ultralytics 导出默认已含 sigmoid)
     * 转换规则:
     *   - 每个 anchor 求 argmax 类别与分数, 分数 < YOLO_KEEP_MIN 直接丢弃
     *     (8400 anchor 大量低分, 丢弃避免下游 WBF O(n²) 在噪声上退化)
     *   - logits 只填 argmax 类为 logit = ln(p/(1-p)), 其余填 -100,
     *     使下游 sigmoid(logit) == p 且 argmax 保持一致 (下游复用 RT-DETR 解码)
     *   - boxes 归一化到 [0,1] (除以 INPUT_SIZE), 与 RT-DETR pred_boxes 一致,
     *     TTA 镜像(1-cx)与 letterbox 逆映射全部复用
     */
    private static float[][] decodeYolov8(float[] raw, long[] shape) {
        if (shape.length < 3) return null;
        long d1 = shape[1], d2 = shape[2];
        if (d1 <= 0 || d2 <= 0) return null;
        // 判定布局: 通道维 C = 4 + nc。channels-first [1,C,N] 时 C 较小(7~64)而 N 大(8400)
        boolean colMajor; // true = [1,C,N] (通道在前)
        int C, N;
        if (d1 >= 5 && d1 <= 64 && d2 > d1) { C = (int) d1; N = (int) d2; colMajor = true; }
        else if (d2 >= 5 && d2 <= 64 && d1 > d2) { C = (int) d2; N = (int) d1; colMajor = false; }
        else return null;
        int nc = C - 4;
        if (nc < 1) return null;
        if (raw.length < C * N) return null;
        // 探测是否已 sigmoid: 采样若干 class 分数, 出现绝对值 >1.01 则需 sigmoid
        boolean needsSigmoid = false;
        outer:
        for (int k = 0; k < Math.min(N, 300); k++) {
            for (int c = 0; c < nc; c++) {
                float v = colMajor ? raw[c * N + k] : raw[k * C + 4 + c];
                if (v > 1.01f || v < -0.01f) { needsSigmoid = true; break outer; }
            }
        }
        List<Float> l = new ArrayList<>();
        List<Float> b = new ArrayList<>();
        for (int k = 0; k < N; k++) {
            float cx = colMajor ? raw[0 * N + k] : raw[k * C];
            float cy = colMajor ? raw[1 * N + k] : raw[k * C + 1];
            float w  = colMajor ? raw[2 * N + k] : raw[k * C + 2];
            float h  = colMajor ? raw[3 * N + k] : raw[k * C + 3];
            float best = -1f; int bestC = -1;
            for (int c = 0; c < nc; c++) {
                float s = colMajor ? raw[(4 + c) * N + k] : raw[k * C + 4 + c];
                if (needsSigmoid) s = sigmoid(s);
                if (s > best) { best = s; bestC = c; }
            }
            if (bestC < 0 || best < YOLO_KEEP_MIN) continue;
            if (bestC >= NUM_CLASSES) continue; // 类别超出当前 3 类约定(黑/白/角点)时跳过
            float p = Math.max(1e-6f, Math.min(1f - 1e-6f, best));
            float logit = (float) Math.log(p / (1f - p));
            for (int c = 0; c < NUM_CLASSES; c++) {
                l.add(bestC == c ? logit : -100f);
            }
            b.add(cx / INPUT_SIZE);
            b.add(cy / INPUT_SIZE);
            b.add(w / INPUT_SIZE);
            b.add(h / INPUT_SIZE);
        }
        if (l.isEmpty()) return null;
        float[] outL = new float[l.size()];
        float[] outB = new float[b.size()];
        for (int i = 0; i < l.size(); i++) outL[i] = l.get(i);
        for (int i = 0; i < b.size(); i++) outB[i] = b.get(i);
        return new float[][]{outL, outB};
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
        // 完全参照 Kaya moku-detector.detect(): 单次推理, 不做 TTA 多路融合。
        // 直接对本次推理输出做 postprocess(解码 → 角点 → H → 网格映射)。
        return postprocess(out[0], out[1], imgW, imgH, threshold, bmp, pp.lb,
                out[0].length / NUM_CLASSES, uniformGrid, rs, externalCorners);
    }

    /**
     * 阶段 1 用:从推理输出提取棋盘角点候选(class=2)。
     * 角点是最可靠的棋盘位置信号(低阈值 CORNER_MIN_THRESHOLD,与 postprocess 口径一致),
     * 供阶段 2 做透视拉正裁剪(棋盘四角→正方形,背景完全裁掉)。
     * 返回按置信度降序、就近去重后的候选(至少 2 个才可能有意义)。
     */
    private static List<Detection> extractCornerCandidates(float[] logits, float[] predBoxes,
                                                           LetterboxInfo lb,
                                                           int imgW, int imgH) {
        List<Detection> corners = new ArrayList<>();
        int nq = logits.length / NUM_CLASSES;
        for (int i = 0; i < nq; i++) {
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
            if (bestClass != CLASS_BOARD_CORNER || bestScore < CORNER_MIN_THRESHOLD) continue;
            int boxBase = i * 4;
            float cx = (predBoxes[boxBase] * INPUT_SIZE - lb.padX) / lb.scaleX;
            float cy = (predBoxes[boxBase + 1] * INPUT_SIZE - lb.padY) / lb.scaleY;
            corners.add(new Detection(cx, cy, 0f, 0f, CLASS_BOARD_CORNER, bestScore));
        }
        if (corners.size() < 2) return corners;
        // 置信度降序 + 就近去重(模型可能在同一个角附近检出多个)
        corners.sort((a, b) -> Float.compare(b.score, a.score));
        List<Detection> dedup = new ArrayList<>();
        float minDist = Math.max(imgW, imgH) * 0.05f;
        for (Detection d : corners) {
            boolean dup = false;
            for (Detection k : dedup) {
                if (Math.hypot(d.cx - k.cx, d.cy - k.cy) < minDist) {
                    dup = true;
                    break;
                }
            }
            if (!dup) dedup.add(d);
        }
        return dedup;
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
        List<Float> cxs = new ArrayList<>(); // 角点:棋盘位置最可靠信号,裁剪定位一并使用
        List<Float> cys = new ArrayList<>();
        int nq = logits.length / NUM_CLASSES; // query 数动态(RT-DETR=300, YOLOv8=过滤后候选数)
        for (int i = 0; i < nq; i++) {
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
            int boxBase = i * 4;
            // letterbox 坐标逆映射: 640 归一化 → 去掉 padding → 还原原图比例
            float cx = (predBoxes[boxBase] * INPUT_SIZE - lb.padX) / lb.scaleX;
            float cy = (predBoxes[boxBase + 1] * INPUT_SIZE - lb.padY) / lb.scaleY;
            // 落在 padding 区的误检会映射到图像外,夹到边界内供裁剪定位用
            cx = Math.max(0, Math.min(cx, imgW - 1));
            cy = Math.max(0, Math.min(cy, imgH - 1));
            if (bestClass == CLASS_BOARD_CORNER) {
                // 角点用低阈值(与 postprocess 一致),直接作为棋盘区域信号
                if (bestScore >= CORNER_MIN_THRESHOLD) {
                    cxs.add(cx);
                    cys.add(cy);
                }
                continue;
            }
            // 统一阈值:与 postprocess/Kaya decode 一致,黑白子不区分
            if (bestScore < threshold) continue;
            xs.add(cx);
            ys.add(cy);
        }
        int count = xs.size();
        int cornerCount = cxs.size();
        Log.i(TAG, "阶段 1 检测: 棋子 " + count + " 个, 角点 " + cornerCount
                + " 个 (用于自动裁剪定位)");
        if (count == 0 && cornerCount == 0) return null;

        // 棋子包围盒:各方向剔除最外 5% 离群点,避免个别误检撑大 bbox
        float[] stoneBox = null;
        if (count > 0) {
            Collections.sort(xs);
            Collections.sort(ys);
            int trim = Math.max(0, (int) (count * 0.05f));
            int lo = trim, hi = count - 1 - trim;
            if (hi <= lo) { lo = 0; hi = count - 1; } // 点数过少时不剔除
            stoneBox = new float[]{xs.get(lo), ys.get(lo), xs.get(hi), ys.get(hi)};
        }
        // 角点包围盒:角点贴近棋盘四角,外接矩形即为棋盘区域
        float[] cornerBox = null;
        if (cornerCount > 0) {
            Collections.sort(cxs);
            Collections.sort(cys);
            cornerBox = new float[]{cxs.get(0), cys.get(0),
                    cxs.get(cornerCount - 1), cys.get(cornerCount - 1)};
        }
        // 并集:角点撑开棋子漏掉的棋盘边,棋子补上角点漏检的边
        if (stoneBox != null && cornerBox != null) {
            return new float[]{
                    Math.min(stoneBox[0], cornerBox[0]),
                    Math.min(stoneBox[1], cornerBox[1]),
                    Math.max(stoneBox[2], cornerBox[2]),
                    Math.max(stoneBox[3], cornerBox[3])};
        }
        return (stoneBox != null) ? stoneBox : cornerBox;
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

    /**
     * Resize 信息:直接拉伸到 INPUT_SIZE×INPUT_SIZE 的缩放比例(x/y 独立, 无 padding),
     * 与 moku-v3 训练预处理一致 (RTDetrImageProcessor do_resize=true, size=640×640,
     * do_pad=false; Kaya board-recognition 同样直接拉伸)。
     */
    private static class LetterboxInfo {
        final float scaleX, scaleY;
        final int padX, padY;
        final int newW, newH;
        LetterboxInfo(float scaleX, float scaleY, int padX, int padY, int newW, int newH) {
            this.scaleX = scaleX;
            this.scaleY = scaleY;
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
     * 预处理:直接拉伸到 INPUT_SIZE×INPUT_SIZE (与 moku-v3 模型训练配置一致——
     * RTDetrImageProcessor do_resize=true, size=640×640, do_pad=false, resample=bilinear;
     * Kaya board-recognition 的 preprocess 同样直接拉伸, 无 letterbox/pad)。
     * 输出 CHW float[3 * 640 * 640],值域 [0,1]。
     */
    private static PreprocessResult preprocess(Bitmap bmp, int srcW, int srcH) {
        return preprocess(bmp, srcW, srcH, 1.0f);
    }

    /**
     * @param scaleBoost 缩放增强系数 (TTA 用)。1.0=原样; <1 在原图基础上 center-crop 再拉伸
     *                  (棋盘占图比例变化, 有助于模型在不同尺度下召回弱对比子)。
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
        // 直接拉伸: x/y 独立缩放, 无 padding (与训练预处理一致)
        float scaleX = (float) INPUT_SIZE / srcW;
        float scaleY = (float) INPUT_SIZE / srcH;
        LetterboxInfo lb = new LetterboxInfo(scaleX, scaleY, 0, 0, INPUT_SIZE, INPUT_SIZE);

        float[] buf = new float[3 * INPUT_SIZE * INPUT_SIZE];
        int[] pixels = new int[srcW * srcH];
        bmp.getPixels(pixels, 0, srcW, 0, 0, srcW, srcH);

        // 双线性拉伸到 640×640 (坐标映射与 Kaya preprocess 完全一致)
        for (int y = 0; y < INPUT_SIZE; y++) {
            for (int x = 0; x < INPUT_SIZE; x++) {
                float srcX = (x + 0.5f) * ((float) srcW / INPUT_SIZE) - 0.5f;
                float srcY = (y + 0.5f) * ((float) srcH / INPUT_SIZE) - 0.5f;
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
                    buf[c * INPUT_SIZE * INPUT_SIZE + y * INPUT_SIZE + x] = val / 255f;
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
                logits.length / NUM_CLASSES, false, rs, null);
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

        // 解码 query：每个 query 输出一个物体（RT-DETR 约定; YOLOv8 已在 decodeYolov8
        // 转成相同布局, query 数为解码过滤后的候选数）。query 数由 logits 长度推导,
        // 兼容 RT-DETR(300) 与 YOLOv8(动态), TTA 合并后为各变体之和。
        int nq = logits.length / NUM_CLASSES;
        // 同时统计每类 top3 分数,便于排查阈值是否过松/过紧
        float[] topBlack = {0,0,0}, topWhite = {0,0,0}, topCorner = {0,0,0};
        for (int q = 0; q < nq; q++) {
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
            // 完全参照 Kaya decode: 角点用 CORNER_MIN_THRESHOLD, 棋子统一用 threshold
            // (Kaya 对黑白子一视同仁, 无类别感知阈值; 之前 Android 用黑白不同阈值
            //  导致白子误检多于 Kaya、黑子漏检多于 Kaya, 与 Kaya 结果不一致)
            float minScore = (bestClass == CLASS_BOARD_CORNER)
                    ? CORNER_MIN_THRESHOLD : threshold;
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
                float cx = (predBoxes[boxBase] * INPUT_SIZE - lb.padX) / lb.scaleX;
                float cy = (predBoxes[boxBase + 1] * INPUT_SIZE - lb.padY) / lb.scaleY;
                float bw = predBoxes[boxBase + 2] * INPUT_SIZE / lb.scaleX;
                float bh = predBoxes[boxBase + 3] * INPUT_SIZE / lb.scaleY;
                borderline.add(new Detection(cx, cy, bw, bh, bestClass, bestScore));
            }

            if (bestScore < minScore) continue;
            // pred_boxes 为 [cx, cy, w, h] 归一化到 [0,1] (相对含 padding 的 640×640)
            // letterbox 逆映射回原图坐标
            float cx = (predBoxes[boxBase] * INPUT_SIZE - lb.padX) / lb.scaleX;
            float cy = (predBoxes[boxBase + 1] * INPUT_SIZE - lb.padY) / lb.scaleY;
            float bw = predBoxes[boxBase + 2] * INPUT_SIZE / lb.scaleX;
            float bh = predBoxes[boxBase + 3] * INPUT_SIZE / lb.scaleY;
            Detection det = new Detection(cx, cy, bw, bh, bestClass, bestScore);
            if (bestClass == CLASS_BOARD_CORNER) cornerCandidates.add(det);
            else stones.add(det);
        }
        Log.i(TAG, "解码 " + nq + " query: 候选黑" + countClass(stones, CLASS_BLACK_STONE)
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

        // 完全参照 Kaya: 棋子不做 WBF/NMS/去重 —— 直接全量进入网格映射,
        // 同位置冲突由 mapStonesToGrid 按 score 抢占处理(Kaya 语义)。
        // 候选黑/白统计
        Log.i(TAG, "解码候选: 黑" + countClass(stones, CLASS_BLACK_STONE)
                + " 白" + countClass(stones, CLASS_WHITE_STONE) + " 共" + stones.size() + "个");

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

        // ===== 完全参照 Kaya moku-postprocess.ts =====
        // 角点处理链: <2 角 → 图像内缩兜底(空棋盘); 2 角/3 角补全; ≥4 角取 top4;
        // orderCorners 顺时针排序; 退化(bbox<2%)→ 图像内缩; 塌缩(两角<对角线5%)→ 图像内缩;
        // 然后 H 单应 → 网格 round → 越界丢 → 冲突按 score 抢占。
        // 不做 Android 特有的覆盖率降级/棋子网格拟合/漏检恢复/全盘复核 —— 与 Kaya 行为一致。

        // 角点不足 2 个: 使用图像边缘 5% 内缩兜底, 放弃棋子识别 (Kaya 语义)
        if (externalCorners == null && cornerCandidates.size() < 2) {
            float[][] corners = insetImageCorners(imgW, imgH, 0.05f);
            Log.w(TAG, "角点不足 2 个, 使用图像内缩兜底且不映射棋子(与 Kaya 一致): "
                    + corners[0][0] + "," + corners[0][1] + " ...");
            out.corners = corners;
            out.cornersDetected = false;
            out.cornerCount = cornerCandidates.size();
            out.blackCount = 0;
            out.whiteCount = 0;
            out.message = "未检测到棋盘角点";
            return out;
        }

        float[][] corners;
        if (externalCorners != null) {
            // 手动注入四角(用户拖动校正): 用户拖的角即权威, 直接用于 H 计算。
            // 仅做 Harris 图像特征局部精修(±24px 窗口内找棋盘角特征, 无显著特征则保持原位)。
            boolean[] snapped = new boolean[4];
            corners = refineCornersToGrid(srcBmp, externalCorners, snapped);
            Log.i(TAG, "手动注入 4 角(Harris 精修后): "
                    + String.format("TL(%.0f,%.0f) TR(%.0f,%.0f) BR(%.0f,%.0f) BL(%.0f,%.0f)",
                        corners[0][0], corners[0][1], corners[1][0], corners[1][1],
                        corners[2][0], corners[2][1], corners[3][0], corners[3][1]));
        } else {
            // 优先用棋子位置推断角点 (模型检测棋子比角点更可靠, 尤其在非正方形拉伸图上)
            float[][] stoneCorners = inferCornersFromStones(stones, imgW, imgH);
            if (stoneCorners != null) {
                corners = stoneCorners;
                Log.i(TAG, "棋子推断角点(主路径): "
                    + String.format("TL(%.0f,%.0f) TR(%.0f,%.0f) BR(%.0f,%.0f) BL(%.0f,%.0f)",
                        corners[0][0], corners[0][1], corners[1][0], corners[1][1],
                        corners[2][0], corners[2][1], corners[3][0], corners[3][1]));
            } else {
                // 后备: 模型角点检测
                // 2 角:推断另外 2 个 (对角或邻边假设); 3 角:平行四边形补全; ≥4 角:取 top 4
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

                // 退化检查(与 Kaya areCornersDegenerate 一致: bbox 面积 < 图像 2%)→ 图像内缩兜底
                if (areCornersDegenerate(corners, imgW, imgH)) {
                    Log.w(TAG, "4 角退化(bbox 面积 < 2%), 使用图像内缩兜底(与 Kaya 一致)");
                    corners = insetImageCorners(imgW, imgH, 0.05f);
                }
                // 塌缩展开(与 Kaya spreadCollapsedCorners 一致: 任意两角距离 < 对角线 5%)→ 图像内缩兜底
                corners = spreadCollapsedCorners(corners, imgW, imgH);
                // 角点边界钳制(对应 Kaya UI 层 fixResultCorners.clampCorners):
                // 允许角点略超图像 25% 溢出区, 防止检测噪声产生极端角点导致 H 畸形
                corners = clampCorners(corners, imgW, imgH);
                Log.d(TAG, "最终使用 4 角(角点检测): "
                        + String.format("TL(%.0f,%.0f) TR(%.0f,%.0f) BR(%.0f,%.0f) BL(%.0f,%.0f)",
                            corners[0][0], corners[0][1], corners[1][0], corners[1][1],
                            corners[2][0], corners[2][1], corners[3][0], corners[3][1]));
            }
        }

        // 棋子映射到 19×19 网格 (Kaya mapStonesToGrid: H→round→越界丢→冲突按 score 抢占)
        mapStonesToGrid(stones, corners, out.board, imgW, imgH, rs, false);

        out.corners = corners;
        out.cornersDetected = true;
        out.cornerCount = cornerCandidates.size();
        out.blackCount = countColor(out.board, BLACK);
        out.whiteCount = countColor(out.board, WHITE);
        out.message = String.format("识别完成: 黑%d 白%d", out.blackCount, out.whiteCount);
        Log.i(TAG, "=== 识别结束 === " + out.message
                + " | 输入棋子" + stones.size() + "个 (黑" + countClass(stones, CLASS_BLACK_STONE)
                + "+白" + countClass(stones, CLASS_WHITE_STONE) + ")"
                + " → 19×19 网格 黑" + out.blackCount + " 白" + out.whiteCount);
        return out;
    }

    // ==================== 角点补全/排序 ====================

    private static float[][] pickTop4Corners(List<Detection> cornerCandidates, int imgW, int imgH) {
        // 过滤图像四角假阳性: x 和 y 同时在边缘 5% 内的是图像角落, 不是棋盘角点
        // (如 720x1600 竖图中 (16,66) 是图像左上角, 不是棋盘 TL)
        if (cornerCandidates.size() > 4) {
            float edgeX = imgW * 0.05f;
            float edgeY = imgH * 0.05f;
            List<Detection> filtered = new ArrayList<>();
            for (Detection d : cornerCandidates) {
                boolean nearCornerX = (d.cx < edgeX || d.cx > imgW - edgeX);
                boolean nearCornerY = (d.cy < edgeY || d.cy > imgH - edgeY);
                if (!(nearCornerX && nearCornerY)) {
                    filtered.add(d);
                }
            }
            if (filtered.size() >= 4) {
                Log.d(TAG, "过滤图像角落假阳性: " + cornerCandidates.size() + " → " + filtered.size());
                cornerCandidates = filtered;
            }
        }

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
            // ≥4 个:贪心无放回极值点法选四角
            // 先 TL=min(x+y), 再 BR=max(x+y), 再 TR=max(x-y), 最后 BL=剩余
            // (无放回避免同一点被选为两个角色导致塌缩)
            List<Detection> remaining = new ArrayList<>(cornerCandidates);

            // TL = min(x+y)
            int tlIdx = 0; float minSum = Float.MAX_VALUE;
            for (int i = 0; i < remaining.size(); i++) {
                float s = remaining.get(i).cx + remaining.get(i).cy;
                if (s < minSum) { minSum = s; tlIdx = i; }
            }
            float[] tl = {remaining.get(tlIdx).cx, remaining.get(tlIdx).cy};
            remaining.remove(tlIdx);

            // BR = max(x+y) from remaining
            int brIdx = 0; float maxSum = -Float.MAX_VALUE;
            for (int i = 0; i < remaining.size(); i++) {
                float s = remaining.get(i).cx + remaining.get(i).cy;
                if (s > maxSum) { maxSum = s; brIdx = i; }
            }
            float[] br = {remaining.get(brIdx).cx, remaining.get(brIdx).cy};
            remaining.remove(brIdx);

            // TR = max(x-y) from remaining
            int trIdx = 0; float maxDiff = -Float.MAX_VALUE;
            for (int i = 0; i < remaining.size(); i++) {
                float d = remaining.get(i).cx - remaining.get(i).cy;
                if (d > maxDiff) { maxDiff = d; trIdx = i; }
            }
            float[] tr = {remaining.get(trIdx).cx, remaining.get(trIdx).cy};
            remaining.remove(trIdx);

            // BL = last remaining
            float[] bl = {remaining.get(0).cx, remaining.get(0).cy};

            return new float[][]{tl, tr, br, bl};
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
        // 贪心无放回: 先 TL=min(x+y), 再 BR=max(x+y), 再 TR=max(x-y), 最后 BL=剩余
        // (无放回避免同一点被分配为两个角色导致塌缩)
        List<Integer> remaining = new ArrayList<>();
        for (int i = 0; i < 4; i++) remaining.add(i);

        // TL = min(x+y)
        int tl = remaining.get(0); float minSum = Float.MAX_VALUE;
        for (int idx : remaining) {
            float s = pts[idx][0] + pts[idx][1];
            if (s < minSum) { minSum = s; tl = idx; }
        }
        remaining.remove(Integer.valueOf(tl));

        // BR = max(x+y) from remaining
        int br = remaining.get(0); float maxSum = -Float.MAX_VALUE;
        for (int idx : remaining) {
            float s = pts[idx][0] + pts[idx][1];
            if (s > maxSum) { maxSum = s; br = idx; }
        }
        remaining.remove(Integer.valueOf(br));

        // TR = max(x-y) from remaining
        int tr = remaining.get(0); float maxDiff = -Float.MAX_VALUE;
        for (int idx : remaining) {
            float d = pts[idx][0] - pts[idx][1];
            if (d > maxDiff) { maxDiff = d; tr = idx; }
        }
        remaining.remove(Integer.valueOf(tr));

        // BL = last remaining
        int bl = remaining.get(0);

        return new float[][]{ pts[tl], pts[tr], pts[br], pts[bl] };
    }

    /**
     * 4 角退化检查 —— 完全参照 Kaya areCornersDegenerate:
     * 仅一条:4 角 bbox 面积 < 图像 2% (4 角挤成一团, 视为不可用)。
     * 塌缩(两角过近)由独立的 spreadCollapsedCorners 处理, 与 Kaya 结构一致。
     */
    private static boolean areCornersDegenerate(float[][] corners, int w, int h) {
        return computeCornerCoverageRatio(corners, w, h) < 0.02f;
    }

    /**
     * 塌缩角点展开 —— 完全参照 Kaya corners.ts spreadCollapsedCorners:
     * 4 角排好后, 若任意两角距离 < 图像对角线 5%, 说明角点挤在一起(模型把
     * 同一个角重复检出, 或 2 角推断出了重合角), 用图像内缩 5% 角兜底。
     */
    private static float[][] spreadCollapsedCorners(float[][] corners, int w, int h) {
        float minDist = (float) Math.hypot(w, h) * 0.05f;
        for (int i = 0; i < 4; i++) {
            for (int j = i + 1; j < 4; j++) {
                float d = (float) Math.hypot(corners[i][0] - corners[j][0],
                        corners[i][1] - corners[j][1]);
                if (d < minDist) {
                    Log.w(TAG, "角点塌缩(两角距离 " + String.format("%.1fpx < 对角线5%% %.1fpx)", d, minDist)
                            + ", 使用图像内缩兜底(与 Kaya spreadCollapsedCorners 一致)");
                    return insetImageCorners(w, h, 0.05f);
                }
            }
        }
        return corners;
    }

    /**
     * 角点边界钳制 —— 完全参照 Kaya UI 层 clampCorners(CORNER_OVERFLOW_FRACTION=0.25):
     * 允许角点超出图像边缘 25% 的溢出区(棋盘延伸到照片外时角点本来就会在图外),
     * 超出则钳回,防止检测噪声产生极端角点导致 H 矩阵畸形。
     */
    private static float[][] clampCorners(float[][] corners, int w, int h) {
        float overX = w * 0.25f;
        float overY = h * 0.25f;
        float minX = -overX, maxX = (w - 1) + overX;
        float minY = -overY, maxY = (h - 1) + overY;
        float[][] out = new float[4][2];
        boolean changed = false;
        for (int i = 0; i < 4; i++) {
            out[i][0] = Math.max(minX, Math.min(maxX, corners[i][0]));
            out[i][1] = Math.max(minY, Math.min(maxY, corners[i][1]));
            if (out[i][0] != corners[i][0] || out[i][1] != corners[i][1]) changed = true;
        }
        if (changed) {
            Log.w(TAG, "角点越界, 钳制到溢出区(±25%): "
                    + String.format("TL(%.0f,%.0f) TR(%.0f,%.0f) BR(%.0f,%.0f) BL(%.0f,%.0f)",
                        out[0][0], out[0][1], out[1][0], out[1][1],
                        out[2][0], out[2][1], out[3][0], out[3][1]));
        }
        return out;
    }

    /**
     * 用棋子位置推断棋盘四角。
     * 原理: 棋子位于 19×19 网格交叉点上, 同一行/列的棋子 x/y 坐标接近。
     * 1. 聚类 x 坐标 → 网格列, 聚类 y 坐标 → 网格行
     * 2. 估计网格间距 = 连续聚类中心差值的中位数
     * 3. 用间距给每个聚类分配网格索引 (从 0 开始)
     * 4. 线性回归 pos = a + b × index
     * 5. 外推到 index 0 和 18 得到四角坐标
     */
    private static float[][] inferCornersFromStones(List<Detection> stones, int imgW, int imgH) {
        if (stones == null || stones.size() < 6) return null;

        float[] xs = new float[stones.size()];
        float[] ys = new float[stones.size()];
        for (int i = 0; i < stones.size(); i++) {
            xs[i] = stones.get(i).cx;
            ys[i] = stones.get(i).cy;
        }
        java.util.Arrays.sort(xs);
        java.util.Arrays.sort(ys);

        // 自适应聚类阈值: 先用较小阈值聚类, 估计网格间距, 再用间距的 40% 重新聚类
        float initThreshold = Math.min(imgW, imgH) * 0.02f;
        List<Float> xClusters0 = cluster1D(xs, initThreshold);
        List<Float> yClusters0 = cluster1D(ys, initThreshold);

        float gridSpacing = estimateGridSpacing(xClusters0, yClusters0);
        float threshold = gridSpacing * 0.4f;
        Log.d(TAG, "棋子推断角点: 初始阈值=" + initThreshold + " 估计网格间距=" + gridSpacing
                + " 最终阈值=" + threshold);

        List<Float> xClusters = cluster1D(xs, threshold);
        List<Float> yClusters = cluster1D(ys, threshold);

        Log.d(TAG, "棋子推断角点: xClusters=" + xClusters.size() + " yClusters=" + yClusters.size()
                + " (棋子 " + stones.size() + " 个)");

        if (xClusters.size() < 3 || yClusters.size() < 3) return null;

        float[] xFit = fitGridLine(xClusters);
        float[] yFit = fitGridLine(yClusters);
        if (xFit == null || yFit == null) return null;

        float x0 = xFit[0];
        float x18 = xFit[0] + xFit[1] * 18;
        float y0 = yFit[0];
        float y18 = yFit[0] + yFit[1] * 18;

        // 验证: 推断的棋盘尺寸应在合理范围内 (不能比图像大太多或太小)
        float boardW = x18 - x0;
        float boardH = y18 - y0;
        if (boardW < imgW * 0.2f || boardW > imgW * 1.5f
                || boardH < imgH * 0.2f || boardH > imgH * 1.5f) {
            Log.w(TAG, "棋子推断角点不合理: boardW=" + boardW + " boardH=" + boardH
                    + " imgW=" + imgW + " imgH=" + imgH + ", 放弃");
            return null;
        }

        return new float[][]{
            {x0, y0},    // TL
            {x18, y0},   // TR
            {x18, y18},  // BR
            {x0, y18}    // BL
        };
    }

    /** 从初始聚类估计网格间距: 取 x/y 聚类差值中位数的较小值。 */
    private static float estimateGridSpacing(List<Float> xClusters, List<Float> yClusters) {
        float xGap = medianGap(xClusters);
        float yGap = medianGap(yClusters);
        float gap = Math.min(xGap, yGap);
        if (gap <= 0) gap = Math.min(xGap, yGap);
        if (gap <= 0) gap = 30f; // 兜底
        return gap;
    }

    private static float medianGap(List<Float> clusters) {
        if (clusters.size() < 2) return 0;
        float[] diffs = new float[clusters.size() - 1];
        for (int i = 0; i < diffs.length; i++) {
            diffs[i] = clusters.get(i + 1) - clusters.get(i);
        }
        java.util.Arrays.sort(diffs);
        return diffs[diffs.length / 2];
    }

    /** 将排序后的坐标聚类, 返回每个聚类的中心。 */
    private static List<Float> cluster1D(float[] sorted, float threshold) {
        List<Float> clusters = new ArrayList<>();
        float sum = sorted[0];
        int count = 1;
        for (int i = 1; i < sorted.length; i++) {
            if (sorted[i] - sorted[i - 1] < threshold) {
                sum += sorted[i];
                count++;
            } else {
                clusters.add(sum / count);
                sum = sorted[i];
                count = 1;
            }
        }
        clusters.add(sum / count);
        return clusters;
    }

    /**
     * 线性拟合网格线: pos = a + b × index。
     * 用中位数差值作为间距, 给每个聚类分配索引, 然后最小二乘拟合。
     */
    private static float[] fitGridLine(List<Float> clusters) {
        int n = clusters.size();
        if (n < 2) return null;

        float[] diffs = new float[n - 1];
        for (int i = 0; i < n - 1; i++) {
            diffs[i] = clusters.get(i + 1) - clusters.get(i);
        }
        java.util.Arrays.sort(diffs);
        float spacing = diffs[diffs.length / 2];
        if (spacing <= 0) return null;

        int[] indices = new int[n];
        indices[0] = 0;
        for (int i = 1; i < n; i++) {
            indices[i] = Math.round((clusters.get(i) - clusters.get(0)) / spacing);
        }

        float sumX = 0, sumY = 0, sumXY = 0, sumXX = 0;
        for (int i = 0; i < n; i++) {
            sumX += indices[i];
            sumY += clusters.get(i);
            sumXY += indices[i] * clusters.get(i);
            sumXX += (float) indices[i] * indices[i];
        }
        float denom = n * sumXX - sumX * sumX;
        if (Math.abs(denom) < 1e-6f) return null;
        float b = (n * sumXY - sumX * sumY) / denom;
        float a = (sumY - b * sumX) / n;

        Log.d(TAG, "  fitGridLine: " + n + " clusters, spacing=" + spacing
                + String.format(", a=%.1f b=%.1f → [0]=%.1f [18]=%.1f", a, b, a, a + b * 18));
        return new float[]{a, b};
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
        // 兼容性签名:默认启用棋子拟合再校准(refineWithStones=true)
        return mapStonesToGrid(stones, corners, board, imgW, imgH, rs, true);
    }

    /**
     * @param refineWithStones true 时用检测到的棋子做"网格拟合再校准":
     *                         手动四角只提供粗范围,棋子检测(模型)是亚像素可靠的,
     *                         以棋子中心↔最近网格点为点对,超定最小二乘反推精确 H,
     *                         把手指拖拽误差系统性消除(比局部 Harris 吸附强得多)。
     */
    private static boolean mapStonesToGrid(List<Detection> stones, float[][] corners, int[][] board,
                                         int imgW, int imgH,
                                         RecognitionSettings rs, boolean refineWithStones) {
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
        if (refineWithStones && stones.size() >= 4) {
            StringBuilder rb = new StringBuilder();
            float[] H2 = refineHWithStones(stones, H, rs, rb);
            Log.i(TAG, "棋子网格拟合再校准: " + rb);
            if (H2 != null) {
                H = H2;
                // 精 H 折算回 4 角(标准网格角逆映射回图像),供下游 recoverMissedStones/
                // fullBoardReview 使用一致网格,避免它们用粗角点反投影误补错位子
                float[] Hi = Perspective.invertH(H);
                if (Hi != null) {
                    for (int i = 0; i < 4; i++) {
                        float[] p = Perspective.applyHomography(Hi, dst[i][0], dst[i][1]);
                        corners[i][0] = p[0];
                        corners[i][1] = p[1];
                    }
                    Log.i(TAG, String.format("精 H 折算 4 角: TL(%.0f,%.0f) TR(%.0f,%.0f)"
                                    + " BR(%.0f,%.0f) BL(%.0f,%.0f)",
                            corners[0][0], corners[0][1], corners[1][0], corners[1][1],
                            corners[2][0], corners[2][1], corners[3][0], corners[3][1]));
                }
            }
        }
        Log.d(TAG, "单应性矩阵 H: "
                + String.format("[%.4f %.4f %.4f; %.4f %.4f %.4f; %.4f %.4f %.4f]",
                    H[0], H[1], H[2], H[3], H[4], H[5], H[6], H[7], H[8]));
        // Sanity check: 把 4 角本身映射回去,应该接近 (0,0)(1,0)(1,1)(0,1)
        // 误差大说明 H 矩阵错误(4 角顺序错乱 / 位置不合理)。
        // 注入路径(refineWithStones)拟合 H 由棋子驱动,边界可轻微外扩,不做此校验。
        if (refineWithStones) {
            Log.d(TAG, "注入角点路径: H 由棋子拟合驱动,跳过 4 角 sanity 校验");
        } else {
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
            // 完全参照 Kaya mapStonesToGrid: 不做偏差距离过滤,
            // 只要 round 后落在网格内且无冲突即放置(Kaya 语义)。
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
     * 棋子网格拟合再校准（手动四角路径）:
     * 注入角点提供粗范围,初始 H 把棋子映射到网格后,以"棋子中心 ↔ 最近网格点"为点对,
     * 超定最小二乘拟合更精确的 H,并迭代剔除离群点。返回 null 表示未改善(保持原 H)。
     */
    private static float[] refineHWithStones(List<Detection> stones, float[] H0,
                                             RecognitionSettings rs, StringBuilder log) {
        float[] H = H0;
        float bestDev = meanGridDev(stones, H);
        float maxDev = rs.maxDeviation; // 离群剔除阈值
        log.append(String.format("初始平均偏差=%.3f", bestDev));
        // 保护:初始偏差过大(>0.4 格)说明注入角点严重失准/自交,H 不可信,
        // 棋子映射关系混乱,拟合会收敛到错误局部,此时保持注入角点不拟合
        if (bestDev > 0.4f) {
            log.append(" 过大,H 不可信,放弃拟合");
            return null;
        }
        // 角点漂移保护:拟合点对全部来自棋盘内部棋子(col/row 在 1..17),四角外推不受约束;
        // 棋子稀疏/偏居一侧时,最小二乘可能在"棋盘中心对齐"的同时把四角漂出棋盘外。
        // 以粗 H 折算的四角为锚,精 H 任一角相对锚点漂移 > 对角线 12%(下限 24px) → 放弃
        // 该拟合,防止网格外扩把棋盘外背景当成有效区域(全盘复核会在那里补出假子)。
        float[][] dst = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
        float[] H0inv = Perspective.invertH(H0);
        float[][] anchor = new float[4][2]; // 注入四角(图像坐标)
        float diag = 0f;
        if (H0inv != null) {
            for (int i = 0; i < 4; i++) {
                float[] p = Perspective.applyHomography(H0inv, dst[i][0], dst[i][1]);
                anchor[i][0] = p[0];
                anchor[i][1] = p[1];
            }
            diag = Math.max(
                    (float) Math.hypot(anchor[1][0] - anchor[0][0], anchor[1][1] - anchor[0][1]),
                    (float) Math.hypot(anchor[2][0] - anchor[1][0], anchor[2][1] - anchor[1][1]));
        }
        float driftLimit = Math.max(diag * 0.12f, 24f);
        for (int iter = 0; iter < 3; iter++) {
            List<float[]> pairs = new ArrayList<>();
            for (Detection d : stones) {
                float[] r = Perspective.applyHomography(H, d.cx, d.cy);
                float gx = r[0] * (BOARD_SIZE - 1), gy = r[1] * (BOARD_SIZE - 1);
                int col = Math.round(gx), row = Math.round(gy);
                // 棋盘边缘(0 / BOARD-1)不含内部交叉点,不参与拟合
                if (col <= 0 || col >= BOARD_SIZE - 1 || row <= 0 || row >= BOARD_SIZE - 1) continue;
                float dx = gx - col, dy = gy - row;
                if (Math.sqrt(dx * dx + dy * dy) > maxDev) continue; // 离群剔除
                pairs.add(new float[]{d.cx, d.cy,
                        col / (float) (BOARD_SIZE - 1), row / (float) (BOARD_SIZE - 1)});
            }
            if (pairs.size() < 4) {
                log.append(String.format(" | 点对不足(%d),停止", pairs.size()));
                break;
            }
            float[][] src = new float[pairs.size()][2];
            float[][] dstP = new float[pairs.size()][2];
            for (int i = 0; i < pairs.size(); i++) {
                src[i][0] = pairs.get(i)[0];
                src[i][1] = pairs.get(i)[1];
                dstP[i][0] = pairs.get(i)[2];
                dstP[i][1] = pairs.get(i)[3];
            }
            float[] Hn = Perspective.fitHomography(src, dstP, src.length);
            if (Hn == null) {
                log.append(" | 最小二乘失败,停止");
                break;
            }
            // 四角漂移保护:精 H 折算四角若相对注入四角漂移超限 → 回退,防止外扩
            float[] HnInv = Perspective.invertH(Hn);
            if (HnInv != null) {
                float maxDrift = 0f;
                for (int i = 0; i < 4; i++) {
                    float[] p = Perspective.applyHomography(HnInv, dst[i][0], dst[i][1]);
                    maxDrift = Math.max(maxDrift,
                            (float) Math.hypot(p[0] - anchor[i][0], p[1] - anchor[i][1]));
                }
                if (maxDrift > driftLimit) {
                    log.append(String.format(" | 第%d轮四角漂移%.0fpx超限(≤%.0f),回退",
                            iter + 1, maxDrift, driftLimit));
                    break;
                }
            }
            float dev = meanGridDev(stones, Hn);
            log.append(String.format(" | 第%d轮: 点对%d 偏差%.3f", iter + 1, pairs.size(), dev));
            if (dev > bestDev + 0.02f) {
                log.append(" 未改善,回退");
                break;
            }
            H = Hn;
            if (Math.abs(dev - bestDev) < 0.005f) {
                bestDev = dev;
                log.append(" 收敛");
                break;
            }
            bestDev = dev;
        }
        return H;
    }

    /** 棋子映射到网格的平均偏差(归一化格距)。 */
    private static float meanGridDev(List<Detection> stones, float[] H) {
        float sum = 0f;
        int n = 0;
        for (Detection d : stones) {
            float[] r = Perspective.applyHomography(H, d.cx, d.cy);
            float gx = r[0] * (BOARD_SIZE - 1), gy = r[1] * (BOARD_SIZE - 1);
            float dx = gx - Math.round(gx), dy = gy - Math.round(gy);
            sum += Math.sqrt(dx * dx + dy * dy);
            n++;
        }
        return n > 0 ? sum / n : Float.MAX_VALUE;
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
                // 棋盘外无效区域过滤:反投影点必须落在 4 角四边形内。
                // 网格若外扩/偏出棋盘,该点已在棋盘外(桌面/背景),严禁在此补子
                if (!pointInQuad(ix, iy, corners)) continue;

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
        // 背景环一致性拦截:16 方向亮度离散异常大 → 环已跨过棋盘边界/落在背景杂物上,
        // "中心 vs 环"对比不可信 → 拿不准就空,防棋盘外无效区域被补子(宁漏勿误)
        if (bgStd > 34f) return EMPTY;

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
        // 背景环一致性拦截:环跨棋盘边界/落在背景杂物上时离散异常大,判定不可信
        if (bgStd > 34f) return new int[]{EMPTY, 0};

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
                // 棋盘外无效区域过滤:反投影点须在 4 角四边形内,防止网格外扩误补假子
                if (!pointInQuad(cx, cy, corners)) continue;

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

    /**
     * 点是否在凸四边形(顺时针 TL→TR→BR→BL)内部。对每条边做叉积,须全在同侧。
     * 用于过滤"棋盘外无效区域":网格外扩时反投影点落在棋盘外,严禁在那里补子。
     */
    private static boolean pointInQuad(float px, float py, float[][] q) {
        boolean neg = false, pos = false;
        for (int i = 0; i < 4; i++) {
            float[] a = q[i], b = q[(i + 1) % 4];
            float cross = (b[0] - a[0]) * (py - a[1]) - (b[1] - a[1]) * (px - a[0]);
            if (cross > 1e-6f) pos = true;
            else if (cross < -1e-6f) neg = true;
            if (pos && neg) return false; // 落在边的两侧 → 四边形外
        }
        return true;
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
