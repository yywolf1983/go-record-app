package com.gosgf.app.util;

import android.graphics.Bitmap;
import android.util.Log;

import java.util.Arrays;

/**
 * 传统 CV 围棋棋盘识别器，1:1 移植自 Kaya packages/board-recognition/src/
 * {index, corners, perspective, image, stones}.ts 的 recognizeBoard 流程。
 *
 * 流程:
 *   1. boardMask (饱和度+亮度) → 找棋盘边界
 *   2. findBoardQuadrilateral (极值点法 min(x+y)/max(x-y) 等)
 *   3. warpPerspective (homography + 双线性插值, 棋盘拉正)
 *   4. toGrayscale
 *   5. estimateGridInWarped (6% 内缩)
 *   6. classifyIntersections (圆盘采样亮度 → 局部相对亮度 → k-means 3 聚类)
 *
 * 不依赖 ONNX 模型, 纯 Java 像素操作, 对光照/透视变形自适应能力强。
 */
public class BoardRecognizer {
    private static final String TAG = "BoardRecognizer";
    public static final int BOARD_SIZE = 19;
    public static final int EMPTY = 0;
    public static final int BLACK = 1;
    public static final int WHITE = 2;
    private static final int OUTPUT_SIZE = 800;
    private static final float BOARD_INSET = 0.06f;

    /** 识别结果 (复用 MokuRecognizer.RecognitionResult 的字段结构) */
    public static class RecognitionResult {
        public int[][] board;
        public boolean cornersDetected;
        public int cornerCount;
        public int blackCount;
        public int whiteCount;
        public String message;
        public float[][] corners;       // TL→TR→BR→BL 原图像素坐标
        public Bitmap warpedImage;      // 透视校正后的正方形图
    }

    /**
     * 识别棋盘 (全自动, 对应 Kaya recognizeBoard)。
     *
     * @param bitmap 输入图像 (任意尺寸)
     * @return 19×19 棋盘矩阵
     */
    public RecognitionResult recognize(Bitmap bitmap) {
        int srcW = bitmap.getWidth();
        int srcH = bitmap.getHeight();
        Log.i(TAG, "=== CV 识别开始 === src=" + srcW + "x" + srcH);

        // 内存保护: 超大图缩到 1200 做角点检测 (Kaya resize 到 600)
        int cornerMaxDim = 1200;
        float cornerScale = 1f;
        Bitmap cornerBmp = bitmap;
        if (Math.max(srcW, srcH) > cornerMaxDim) {
            cornerScale = Math.min((float) cornerMaxDim / srcW, (float) cornerMaxDim / srcH);
            int nw = Math.round(srcW * cornerScale);
            int nh = Math.round(srcH * cornerScale);
            cornerBmp = Bitmap.createScaledBitmap(bitmap, nw, nh, true);
        }
        int cw = cornerBmp.getWidth();
        int ch = cornerBmp.getHeight();

        // Step 1: boardMask + findBoardCorners
        // 多轮尝试: 默认参数 → 更严格的饱和度/亮度 → 更大膨胀半径
        float[][] smallCorners = null;
        float[][] maskParams = {
            {0.10f, 235f, 35f, 5f},   // 默认: sat>0.10, 35<gray<235, dilate=5
            {0.20f, 225f, 45f, 3f},   // 严格: sat>0.20, 45<gray<225, dilate=3
            {0.30f, 220f, 50f, 3f},   // 更严格
            {0.15f, 230f, 40f, 8f},    // 大膨胀: 填充更多棋子孔洞
        };
        for (int pi = 0; pi < maskParams.length && smallCorners == null; pi++) {
            float satT = maskParams[pi][0];
            float brightMax = maskParams[pi][1];
            float brightMin = maskParams[pi][2];
            int dilateR = (int) maskParams[pi][3];

            int[] cornerPixels = new int[cw * ch];
            cornerBmp.getPixels(cornerPixels, 0, cw, 0, 0, cw, ch);
            float[] cornerGray = toGrayscale(cornerPixels, cw, ch);
            float[] cornerSat = computeSaturation(cornerPixels, cw, ch);
            byte[] mask = boardMask(cornerSat, cornerGray, cw, ch, satT, brightMax, brightMin, dilateR);

            int maskCount = 0;
            for (byte b : mask) if (b == 1) maskCount++;
            Log.i(TAG, "boardMask 轮" + pi + ": sat>" + satT + " " + brightMin + "<gray<" + brightMax
                    + " dilate=" + dilateR + " → " + maskCount + "/" + (cw * ch)
                    + " = " + String.format("%.1f%%", 100f * maskCount / (cw * ch)));

            float[][] cand = findBoardQuadrilateral(mask, cw, ch);
            if (cand != null) {
                float w1 = (float) Math.hypot(cand[1][0] - cand[0][0], cand[1][1] - cand[0][1]);
                float h1 = (float) Math.hypot(cand[3][0] - cand[0][0], cand[3][1] - cand[0][1]);
                float w2 = (float) Math.hypot(cand[2][0] - cand[3][0], cand[2][1] - cand[3][1]);
                float h2 = (float) Math.hypot(cand[2][0] - cand[1][0], cand[2][1] - cand[1][1]);
                float aspectWH = (w1 + w2) / (h1 + h2);
                Log.i(TAG, "  宽高比=" + String.format("%.2f", aspectWH) + " (w=" + (w1+w2)/2 + " h=" + (h1+h2)/2 + ")");
                if (aspectWH > 0.6f && aspectWH < 1.7f) {
                    smallCorners = cand;
                    Log.i(TAG, "  ✓ 通过宽高比检查");
                } else {
                    Log.w(TAG, "  ✗ 宽高比不通过, 继续下一轮");
                }
            }
        }

        // 积分图正方形检测: 如果极值点法全部失败 (背景与棋盘颜色相近),
        // 用积分图在 mask 中找 mask 密度最高的正方形区域作为棋盘位置
        if (smallCorners == null) {
            Log.i(TAG, "极值点法失败, 尝试积分图正方形检测...");
            smallCorners = findBoardByIntegral(cornerBmp, cw, ch);
        }

        if (cornerBmp != bitmap) cornerBmp.recycle();

        RecognitionResult out = new RecognitionResult();
        out.board = new int[BOARD_SIZE][BOARD_SIZE];

        float[][] corners;
        if (smallCorners != null) {
            // 缩放回原图坐标
            corners = new float[4][2];
            for (int i = 0; i < 4; i++) {
                corners[i][0] = smallCorners[i][0] / cornerScale;
                corners[i][1] = smallCorners[i][1] / cornerScale;
            }
            out.cornersDetected = true;
            out.cornerCount = 4;
            Log.i(TAG, "角点检测成功: TL(" + corners[0][0] + "," + corners[0][1]
                    + ") TR(" + corners[1][0] + "," + corners[1][1]
                    + ") BR(" + corners[2][0] + "," + corners[2][1]
                    + ") BL(" + corners[3][0] + "," + corners[3][1] + ")");
        } else {
            // 回退: 图像中心区域取最大正方形
            int minDim = Math.min(srcW, srcH);
            int ox = (srcW - minDim) / 2;
            int oy = (srcH - minDim) / 2;
            corners = new float[][]{
                    {ox, oy}, {ox + minDim - 1, oy}, {ox + minDim - 1, oy + minDim - 1}, {ox, oy + minDim - 1}};
            out.cornersDetected = false;
            out.cornerCount = 0;
            Log.w(TAG, "角点检测全部失败, 使用图像中心正方形回退: " + minDim + "x" + minDim);
        }

        out.corners = corners;

        // Step 2: warpPerspective (透视校正, 800×800 正方形)
        Bitmap warped = warpPerspective(bitmap, corners, OUTPUT_SIZE);
        out.warpedImage = warped;

        // Step 3: toGrayscale on warped image
        int[] warpedPixels = new int[OUTPUT_SIZE * OUTPUT_SIZE];
        warped.getPixels(warpedPixels, 0, OUTPUT_SIZE, 0, 0, OUTPUT_SIZE, OUTPUT_SIZE);
        float[] warpedGray = toGrayscale(warpedPixels, OUTPUT_SIZE, OUTPUT_SIZE);

        // Step 4: estimateGridInWarped (与 kaya 一致: 角点检测成功时用 6% 内缩, 失败时不传)
        float[][] gridCorners = null;
        if (out.cornersDetected) {
            float margin = OUTPUT_SIZE * BOARD_INSET;
            gridCorners = new float[][]{
                    {margin, margin},
                    {OUTPUT_SIZE - margin, margin},
                    {OUTPUT_SIZE - margin, OUTPUT_SIZE - margin},
                    {margin, OUTPUT_SIZE - margin}
            };
        }

        // Step 5: classifyIntersections (亮度采样 → 局部相对 → k-means)
        int[][] board = classifyIntersections(warpedGray, OUTPUT_SIZE, BOARD_SIZE, gridCorners);
        out.board = board;
        out.blackCount = countColor(board, BLACK);
        out.whiteCount = countColor(board, WHITE);
        out.message = "CV 识别完成: 黑" + out.blackCount + " 白" + out.whiteCount;

        Log.i(TAG, "=== CV 识别结束 === " + out.message);
        return out;
    }

    /**
     * 用用户修正后的角点重新识别 (对应 Kaya reclassifyWithCorners)。
     * 重新 warp + 重新分类, 不需要重新检测角点。
     */
    public RecognitionResult reclassifyWithCorners(Bitmap bitmap, float[][] corners) {
        int srcW = bitmap.getWidth();
        int srcH = bitmap.getHeight();
        Log.i(TAG, "=== 重新分类(用户角点) ===");

        RecognitionResult out = new RecognitionResult();
        out.board = new int[BOARD_SIZE][BOARD_SIZE];
        out.corners = corners;
        out.cornersDetected = true;
        out.cornerCount = 4;

        Bitmap warped = warpPerspective(bitmap, corners, OUTPUT_SIZE);
        out.warpedImage = warped;

        int[] warpedPixels = new int[OUTPUT_SIZE * OUTPUT_SIZE];
        warped.getPixels(warpedPixels, 0, OUTPUT_SIZE, 0, 0, OUTPUT_SIZE, OUTPUT_SIZE);
        float[] warpedGray = toGrayscale(warpedPixels, OUTPUT_SIZE, OUTPUT_SIZE);

        // reclassifyWithCorners 始终用 estimateGridInWarped (与 kaya 一致)
        float margin = OUTPUT_SIZE * BOARD_INSET;
        float[][] gridCorners = {
                {margin, margin},
                {OUTPUT_SIZE - margin, margin},
                {OUTPUT_SIZE - margin, OUTPUT_SIZE - margin},
                {margin, OUTPUT_SIZE - margin}
        };

        out.board = classifyIntersections(warpedGray, OUTPUT_SIZE, BOARD_SIZE, gridCorners);
        out.blackCount = countColor(out.board, BLACK);
        out.whiteCount = countColor(out.board, WHITE);
        out.message = "重新分类完成: 黑" + out.blackCount + " 白" + out.whiteCount;
        Log.i(TAG, "=== 重新分类结束 === " + out.message);
        return out;
    }

    // ==================== 灰度 & 饱和度 ====================

    /** RGBA int[] → 灰度 float[] (0-255), 系数与 Kaya toGrayscale 一致 */
    private static float[] toGrayscale(int[] pixels, int w, int h) {
        float[] gray = new float[w * h];
        for (int i = 0; i < w * h; i++) {
            int p = pixels[i];
            int r = (p >> 16) & 0xFF;
            int g = (p >> 8) & 0xFF;
            int b = p & 0xFF;
            gray[i] = 0.299f * r + 0.587f * g + 0.114f * b;
        }
        return gray;
    }

    /** 计算每个像素的饱和度 = (max-min)/max, 与 Kaya computeSaturation 一致 */
    private static float[] computeSaturation(int[] pixels, int w, int h) {
        float[] sat = new float[w * h];
        for (int i = 0; i < w * h; i++) {
            int p = pixels[i];
            int r = (p >> 16) & 0xFF;
            int g = (p >> 8) & 0xFF;
            int b = p & 0xFF;
            int mx = Math.max(r, Math.max(g, b));
            int mn = Math.min(r, Math.min(g, b));
            sat[i] = mx > 0 ? (float) (mx - mn) / mx : 0;
        }
        return sat;
    }

    // ==================== 棋盘 mask ====================

    /**
     * 棋盘区域二值 mask (饱和度+亮度), 膨胀填充棋子孔洞。
     * 对应 Kaya boardMask: sat>0.1, 35<gray<235, dilateRadius=5
     */
    private static byte[] boardMask(float[] sat, float[] gray, int w, int h,
                                     float satThreshold, float brightMax, float brightMin,
                                     int dilateRadius) {
        byte[] mask = new byte[w * h];
        for (int i = 0; i < w * h; i++) {
            mask[i] = (sat[i] > satThreshold && gray[i] < brightMax && gray[i] > brightMin)
                    ? (byte) 1 : (byte) 0;
        }
        // 膨胀
        if (dilateRadius > 0) {
            byte[] dilated = new byte[w * h];
            int r = dilateRadius;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if (mask[y * w + x] == 1) {
                        int y0 = Math.max(0, y - r), y1 = Math.min(h - 1, y + r);
                        int x0 = Math.max(0, x - r), x1 = Math.min(w - 1, x + r);
                        for (int yy = y0; yy <= y1; yy++)
                            for (int xx = x0; xx <= x1; xx++)
                                dilated[yy * w + xx] = 1;
                    }
                }
            }
            return dilated;
        }
        return mask;
    }

    // ==================== 角点检测 ====================

    /**
     * 从 mask 边界像素用极值点法找四角 (对应 Kaya findBoardQuadrilateral)。
     * TL=min(x+y), BR=max(x+y), TR=max(x-y), BL=min(x-y)
     */
    private static float[][] findBoardQuadrilateral(byte[] mask, int w, int h) {
        float tlScore = Float.MAX_VALUE, trScore = -Float.MAX_VALUE;
        float brScore = -Float.MAX_VALUE, blScore = Float.MAX_VALUE;
        float tlx = 0, tly = 0, trx = 0, try_ = 0, brx = 0, bry = 0, blx = 0, bly = 0;
        int boundaryCount = 0;

        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                if (mask[y * w + x] == 0) continue;
                // 内部像素 (四邻域全为 1) 跳过, 只处理边界
                if (mask[(y - 1) * w + x] == 1 && mask[(y + 1) * w + x] == 1
                        && mask[y * w + (x - 1)] == 1 && mask[y * w + (x + 1)] == 1) continue;

                boundaryCount++;
                float sum = x + y;
                float diff = x - y;
                if (sum < tlScore) { tlScore = sum; tlx = x; tly = y; }
                if (sum > brScore) { brScore = sum; brx = x; bry = y; }
                if (diff > trScore) { trScore = diff; trx = x; try_ = y; }
                if (diff < blScore) { blScore = diff; blx = x; bly = y; }
            }
        }

        if (boundaryCount < 20) {
            Log.w(TAG, "findBoardQuadrilateral: 边界像素不足 " + boundaryCount);
            return null;
        }

        // 面积检查 (≥5% 图像面积)
        float area = Math.abs((trx - tlx) * (bry - tly) - (brx - tlx) * (try_ - tly)) / 2f
                + Math.abs((brx - tlx) * (bly - tly) - (blx - tlx) * (bry - tly)) / 2f;
        Log.i(TAG, "findBoardQuadrilateral: boundary=" + boundaryCount
                + " area=" + area + " (" + (100f * area / (w * h)) + "%)");
        if (area < w * h * 0.05f) {
            Log.w(TAG, "findBoardQuadrilateral: 面积不足 " + area + " < " + (w * h * 0.05f));
            return null;
        }

        float[][] pts = {{tlx, tly}, {trx, try_}, {brx, bry}, {blx, bly}};
        return orderCorners(pts);
    }

    /**
     * 方差积分图检测: 当极值点法因背景干扰失败时使用。
     *
     * 原理: 棋盘区域有黑白棋子+网格线, 灰度方差远高于平坦背景。
     * 用两个积分图 (sum 和 sumSq) O(1) 计算任意正方形区域的方差,
     * 从大到小找第一个方差显著高于全图平均的正方形 = 棋盘位置。
     */
    private static float[][] findBoardByIntegral(Bitmap bmp, int w, int h) {
        int[] pixels = new int[w * h];
        bmp.getPixels(pixels, 0, w, 0, 0, w, h);
        float[] gray = toGrayscale(pixels, w, h);

        // 双积分图: sum[y][x] = Σgray, sumSq[y][x] = Σgray²
        int iw = w + 1;
        double[] sumI = new double[iw * (h + 1)];
        double[] sumSqI = new double[iw * (h + 1)];
        for (int y = 0; y < h; y++) {
            double rowSum = 0, rowSumSq = 0;
            for (int x = 0; x < w; x++) {
                double v = gray[y * w + x];
                rowSum += v;
                rowSumSq += v * v;
                int idx1 = (y + 1) * iw + (x + 1);
                sumI[idx1] = sumI[y * iw + (x + 1)] + rowSum;
                sumSqI[idx1] = sumSqI[y * iw + (x + 1)] + rowSumSq;
            }
        }

        // 全图方差 (基准)
        double totalArea = w * h;
        double totalSum = sumI[h * iw + w];
        double totalSumSq = sumSqI[h * iw + w];
        double globalVar = totalSumSq / totalArea - (totalSum / totalArea) * (totalSum / totalArea);
        float globalStd = (float) Math.sqrt(Math.max(0, globalVar));
        Log.i(TAG, "方差积分图: 全图灰度均值=" + String.format("%.1f", totalSum / totalArea)
                + " 标准差=" + String.format("%.1f", globalStd));

        // 搜索: 从大到小找第一个 std > 全图 std × 1.3 的正方形 (面积优先)
        int minSize = Math.min(w, h) / 4;
        int maxSize = Math.min(w, h);
        int bestSize = 0, bestX = 0, bestY = 0;
        float bestStd = 0;
        float threshold = globalStd * 1.3f;

        outer:
        for (int s = maxSize; s >= minSize; s -= 4) {
            double area = (double) s * s;
            for (int y = 0; y + s <= h; y += 3) {
                for (int x = 0; x + s <= w; x += 3) {
                    double sSum = sumI[(y + s) * iw + (x + s)]
                            - sumI[y * iw + (x + s)]
                            - sumI[(y + s) * iw + x]
                            + sumI[y * iw + x];
                    double sSumSq = sumSqI[(y + s) * iw + (x + s)]
                            - sumSqI[y * iw + (x + s)]
                            - sumSqI[(y + s) * iw + x]
                            + sumSqI[y * iw + x];
                    double mean = sSum / area;
                    double var = sSumSq / area - mean * mean;
                    float std = (float) Math.sqrt(Math.max(0, var));
                    if (std > threshold) {
                        bestStd = std;
                        bestSize = s;
                        bestX = x;
                        bestY = y;
                        break outer;
                    }
                }
            }
        }

        if (bestSize == 0) {
            Log.w(TAG, "方差积分图: 未找到高方差正方形区域");
            return null;
        }

        Log.i(TAG, "方差积分图: 最佳正方形 " + bestSize + "x" + bestSize
                + " at (" + bestX + "," + bestY + ")"
                + " std=" + String.format("%.1f", bestStd)
                + " (全图=" + String.format("%.1f", globalStd) + ")");

        // 在找到的正方形区域内用 mask 极值点法精修四角
        float[] sat = computeSaturation(pixels, w, h);
        byte[] mask = boardMask(sat, gray, w, h, 0.10f, 235f, 35f, 5);

        byte[] subMask = new byte[bestSize * bestSize];
        for (int y = 0; y < bestSize; y++) {
            System.arraycopy(mask, (bestY + y) * w + bestX, subMask, y * bestSize, bestSize);
        }

        float[][] subCorners = findBoardQuadrilateral(subMask, bestSize, bestSize);
        if (subCorners == null) {
            subCorners = new float[][]{
                    {0, 0}, {bestSize - 1, 0}, {bestSize - 1, bestSize - 1}, {0, bestSize - 1}};
            Log.i(TAG, "  子区域极值点失败, 使用正方形四角");
        }

        for (int i = 0; i < 4; i++) {
            subCorners[i][0] += bestX;
            subCorners[i][1] += bestY;
        }

        Log.i(TAG, "  精修四角: TL(" + subCorners[0][0] + "," + subCorners[0][1]
                + ") TR(" + subCorners[1][0] + "," + subCorners[1][1]
                + ") BR(" + subCorners[2][0] + "," + subCorners[2][1]
                + ") BL(" + subCorners[3][0] + "," + subCorners[3][1] + ")");
        return subCorners;
    }

    /**
     * 顺时针排序 TL→TR→BR→BL (对应 Kaya orderCorners)。
     * 用 atan2 极角排序, 然后旋转使 x+y 最小的点 (TL) 在首位。
     */
    private static float[][] orderCorners(float[][] pts) {
        float cx = 0, cy = 0;
        for (float[] p : pts) { cx += p[0]; cy += p[1]; }
        cx /= pts.length; cy /= pts.length;

        // 按极角排序
        Float[] angles = new Float[pts.length];
        Integer[] indices = new Integer[pts.length];
        for (int i = 0; i < pts.length; i++) {
            angles[i] = (float) Math.atan2(pts[i][1] - cy, pts[i][0] - cx);
            indices[i] = i;
        }
        final Float[] ang = angles;
        Arrays.sort(indices, (a, b) -> Float.compare(ang[a], ang[b]));

        // 找 TL (x+y 最小)
        int tlIdx = 0;
        float minSum = Float.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            int idx = indices[i];
            float s = pts[idx][0] + pts[idx][1];
            if (s < minSum) { minSum = s; tlIdx = i; }
        }

        float[][] result = new float[4][2];
        for (int i = 0; i < 4; i++) {
            result[i] = pts[indices[(tlIdx + i) % 4]];
        }
        return result;
    }

    // ==================== 透视校正 ====================

    /**
     * 透视校正: 把 corners[TL,TR,BR,BL] 映射到 outSize×outSize 正方形。
     * 逆映射 + 双线性插值 (对应 Kaya warpPerspective)。
     */
    private static Bitmap warpPerspective(Bitmap src, float[][] corners, int outSize) {
        float[][] dst = {{0, 0}, {outSize - 1, 0}, {outSize - 1, outSize - 1}, {0, outSize - 1}};
        float[] H = computeHomography(corners, dst);
        if (H == null) {
            Log.w(TAG, "homography 计算失败, 直接缩放");
            return Bitmap.createScaledBitmap(src, outSize, outSize, true);
        }
        float[] Hinv = invertMatrix3(H);
        if (Hinv == null) {
            return Bitmap.createScaledBitmap(src, outSize, outSize, true);
        }

        int srcW = src.getWidth();
        int srcH = src.getHeight();
        int[] srcPixels = new int[srcW * srcH];
        src.getPixels(srcPixels, 0, srcW, 0, 0, srcW, srcH);

        int[] outPixels = new int[outSize * outSize];
        for (int oy = 0; oy < outSize; oy++) {
            for (int ox = 0; ox < outSize; ox++) {
                // 逆映射: output (ox,oy) → source (sx,sy)
                float w = Hinv[6] * ox + Hinv[7] * oy + Hinv[8];
                float sx, sy;
                if (Math.abs(w) < 1e-10f) {
                    sx = ox; sy = oy;
                } else {
                    sx = (Hinv[0] * ox + Hinv[1] * oy + Hinv[2]) / w;
                    sy = (Hinv[3] * ox + Hinv[4] * oy + Hinv[5]) / w;
                }

                int x0 = (int) Math.floor(sx);
                int y0 = (int) Math.floor(sy);
                int x1 = x0 + 1;
                int y1 = y0 + 1;
                float dx = sx - x0;
                float dy = sy - y0;

                if (x0 < 0 || y0 < 0 || x1 >= srcW || y1 >= srcH) {
                    int cx = Math.max(0, Math.min(srcW - 1, Math.round(sx)));
                    int cy = Math.max(0, Math.min(srcH - 1, Math.round(sy)));
                    outPixels[oy * outSize + ox] = srcPixels[cy * srcW + cx] | 0xFF000000;
                    continue;
                }

                int p00 = srcPixels[y0 * srcW + x0];
                int p10 = srcPixels[y0 * srcW + x1];
                int p01 = srcPixels[y1 * srcW + x0];
                int p11 = srcPixels[y1 * srcW + x1];

                int r = bilinear(p00, p10, p01, p11, 16, dx, dy);
                int g = bilinear(p00, p10, p01, p11, 8, dx, dy);
                int b = bilinear(p00, p10, p01, p11, 0, dx, dy);
                outPixels[oy * outSize + ox] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        return Bitmap.createBitmap(outPixels, outSize, outSize, Bitmap.Config.ARGB_8888);
    }

    private static int bilinear(int p00, int p10, int p01, int p11, int shift, float dx, float dy) {
        int v00 = (p00 >> shift) & 0xFF;
        int v10 = (p10 >> shift) & 0xFF;
        int v01 = (p01 >> shift) & 0xFF;
        int v11 = (p11 >> shift) & 0xFF;
        float val = v00 * (1 - dx) * (1 - dy) + v10 * dx * (1 - dy)
                + v01 * (1 - dx) * dy + v11 * dx * dy;
        return Math.max(0, Math.min(255, Math.round(val)));
    }

    /**
     * 计算 3×3 homography (4点 DLT), 对应 Kaya computeHomography。
     * src[4] → dst[4], 返回 9 元素 row-major (h8=1)。
     */
    private static float[] computeHomography(float[][] src, float[][] dst) {
        float[][] A = new float[8][8];
        float[] b = new float[8];
        for (int i = 0; i < 4; i++) {
            float sx = src[i][0], sy = src[i][1];
            float dx = dst[i][0], dy = dst[i][1];
            A[2 * i][0] = sx; A[2 * i][1] = sy; A[2 * i][2] = 1;
            A[2 * i][3] = 0; A[2 * i][4] = 0; A[2 * i][5] = 0;
            A[2 * i][6] = -dx * sx; A[2 * i][7] = -dx * sy;
            b[2 * i] = dx;
            A[2 * i + 1][0] = 0; A[2 * i + 1][1] = 0; A[2 * i + 1][2] = 0;
            A[2 * i + 1][3] = sx; A[2 * i + 1][4] = sy; A[2 * i + 1][5] = 1;
            A[2 * i + 1][6] = -dy * sx; A[2 * i + 1][7] = -dy * sy;
            b[2 * i + 1] = dy;
        }
        float[] h = solveLinear8(A, b);
        if (h == null) return null;
        return new float[]{h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7], 1};
    }

    /** 8×8 线性求解 (高斯消元+部分主元), 对应 Kaya solveLinear */
    private static float[] solveLinear8(float[][] A, float[] b) {
        int n = 8;
        float[][] M = new float[n][n + 1];
        for (int i = 0; i < n; i++) {
            System.arraycopy(A[i], 0, M[i], 0, n);
            M[i][n] = b[i];
        }
        for (int col = 0; col < n; col++) {
            int maxRow = col;
            for (int row = col + 1; row < n; row++) {
                if (Math.abs(M[row][col]) > Math.abs(M[maxRow][col])) maxRow = row;
            }
            float[] tmp = M[col]; M[col] = M[maxRow]; M[maxRow] = tmp;
            if (Math.abs(M[col][col]) < 1e-12f) return null;
            for (int row = 0; row < n; row++) {
                if (row == col) continue;
                float f = M[row][col] / M[col][col];
                for (int k = col; k <= n; k++) M[row][k] -= f * M[col][k];
            }
        }
        float[] x = new float[n];
        for (int i = 0; i < n; i++) x[i] = M[i][n] / M[i][i];
        return x;
    }

    /** 3×3 矩阵求逆, 对应 Kaya invertMatrix3 */
    private static float[] invertMatrix3(float[] m) {
        float det = m[0] * (m[4] * m[8] - m[5] * m[7])
                - m[1] * (m[3] * m[8] - m[5] * m[6])
                + m[2] * (m[3] * m[7] - m[4] * m[6]);
        if (Math.abs(det) < 1e-12f) return null;
        float inv = 1 / det;
        return new float[]{
                (m[4] * m[8] - m[5] * m[7]) * inv,
                (m[2] * m[7] - m[1] * m[8]) * inv,
                (m[1] * m[5] - m[2] * m[4]) * inv,
                (m[5] * m[6] - m[3] * m[8]) * inv,
                (m[0] * m[8] - m[2] * m[6]) * inv,
                (m[2] * m[3] - m[0] * m[5]) * inv,
                (m[3] * m[7] - m[4] * m[6]) * inv,
                (m[1] * m[6] - m[0] * m[7]) * inv,
                (m[0] * m[4] - m[1] * m[3]) * inv
        };
    }

    // ==================== 棋子分类 (k-means) ====================

    /**
     * 对 19×19 交叉点进行黑白空分类 (对应 Kaya classifyIntersections)。
     *
     * 1. 在每个交叉点圆盘采样平均亮度
     * 2. 计算局部相对亮度 (减去 ±3 邻域中位数)
     * 3. k-means 3 聚类 (黑/棋盘/白)
     * 4. 按聚类中心和边界分类, 方差过高时更保守
     */
    private static int[][] classifyIntersections(float[] gray, int imgSize, int boardSize,
                                                   float[][] gridCorners) {
        // 与 kaya sampleGrid 一致: gridCorners 为 null 时用默认均匀网格
        float cellSize;
        if (gridCorners != null) {
            float[] tl = gridCorners[0], tr = gridCorners[1], bl = gridCorners[3];
            float gridW = (float) Math.hypot(tr[0] - tl[0], tr[1] - tl[1]);
            float gridH = (float) Math.hypot(bl[0] - tl[0], bl[1] - tl[1]);
            cellSize = (gridW + gridH) / (2 * (boardSize - 1));
        } else {
            cellSize = (float) (imgSize - 1) / (boardSize - 1);
        }
        float discRadius = cellSize * 0.35f;

        int N = boardSize * boardSize;
        float[] brightness = new float[N];
        float[] variances = new float[N];

        // 采样每个交叉点
        for (int row = 0; row < boardSize; row++) {
            for (int col = 0; col < boardSize; col++) {
                float cx, cy;
                if (gridCorners != null) {
                    float[] tl = gridCorners[0], tr = gridCorners[1], br = gridCorners[2], bl = gridCorners[3];
                    float u = (float) col / (boardSize - 1);
                    float v = (float) row / (boardSize - 1);
                    cx = (1 - u) * (1 - v) * tl[0] + u * (1 - v) * tr[0]
                            + u * v * br[0] + (1 - u) * v * bl[0];
                    cy = (1 - u) * (1 - v) * tl[1] + u * (1 - v) * tr[1]
                            + u * v * br[1] + (1 - u) * v * bl[1];
                } else {
                    cx = col * cellSize;
                    cy = row * cellSize;
                }
                int idx = row * boardSize + col;
                brightness[idx] = sampleDiscMean(gray, cx, cy, discRadius, imgSize);
                variances[idx] = sampleDiscStd(gray, cx, cy, discRadius, imgSize);
            }
        }

        // 局部相对亮度: 减去 ±3 邻域中位数
        int RING = 3;
        float[] relative = new float[N];
        float[] neighborBuf = new float[(2 * RING + 1) * (2 * RING + 1) - 1];
        for (int row = 0; row < boardSize; row++) {
            for (int col = 0; col < boardSize; col++) {
                int cnt = 0;
                for (int dr = -RING; dr <= RING; dr++) {
                    for (int dc = -RING; dc <= RING; dc++) {
                        if (dr == 0 && dc == 0) continue;
                        int nr = row + dr, nc = col + dc;
                        if (nr >= 0 && nr < boardSize && nc >= 0 && nc < boardSize) {
                            neighborBuf[cnt++] = brightness[nr * boardSize + nc];
                        }
                    }
                }
                Arrays.sort(neighborBuf, 0, cnt);
                float localMedian = neighborBuf[cnt / 2];
                relative[row * boardSize + col] = brightness[row * boardSize + col] - localMedian;
            }
        }

        // k-means 3 聚类
        float[] relValues = Arrays.copyOf(relative, N);
        float[] centroids = kmeans3(relValues);
        float blackC = centroids[0];
        float boardC = centroids[1];
        float whiteC = centroids[2];

        float blackBoundary = (blackC + boardC) / 2;
        float whiteBoundary = (boardC + whiteC) / 2;
        float totalSpread = whiteC - blackC;
        float MIN_SPREAD = 5;
        boolean hasBlack = totalSpread > MIN_SPREAD && (boardC - blackC) > totalSpread * 0.15f;
        boolean hasWhite = totalSpread > MIN_SPREAD && (whiteC - boardC) > totalSpread * 0.15f;

        // 方差中位数
        float[] sortedVar = Arrays.copyOf(variances, N);
        Arrays.sort(sortedVar);
        float medianVar = sortedVar[N / 2];

        // 诊断日志
        Log.i(TAG, "classifyIntersections: cellSize=" + cellSize + " discRadius=" + discRadius);
        Log.i(TAG, "  k-means: blackC=" + String.format("%.1f", blackC)
                + " boardC=" + String.format("%.1f", boardC)
                + " whiteC=" + String.format("%.1f", whiteC));
        Log.i(TAG, "  spread=" + String.format("%.1f", totalSpread)
                + " hasBlack=" + hasBlack + " hasWhite=" + hasWhite);
        Log.i(TAG, "  blackBoundary=" + String.format("%.1f", blackBoundary)
                + " whiteBoundary=" + String.format("%.1f", whiteBoundary)
                + " medianVar=" + String.format("%.1f", medianVar));
        // 打印亮度分布采样 (9 个关键位置)
        Log.i(TAG, "  brightness TL(0,0)=" + String.format("%.1f", brightness[0])
                + " T(0,9)=" + String.format("%.1f", brightness[9])
                + " TR(0,18)=" + String.format("%.1f", brightness[18])
                + " C(9,9)=" + String.format("%.1f", brightness[9 * 19 + 9])
                + " BL(18,0)=" + String.format("%.1f", brightness[18 * 19])
                + " BR(18,18)=" + String.format("%.1f", brightness[18 * 19 + 18]));
        Log.i(TAG, "  relative TL(0,0)=" + String.format("%.1f", relative[0])
                + " C(9,9)=" + String.format("%.1f", relative[9 * 19 + 9])
                + " BR(18,18)=" + String.format("%.1f", relative[18 * 19 + 18]));

        int[][] board = new int[boardSize][boardSize];
        for (int row = 0; row < boardSize; row++) {
            for (int col = 0; col < boardSize; col++) {
                int idx = row * boardSize + col;
                float r = relative[idx];
                boolean highVar = variances[idx] > medianVar * 3;
                boolean isEdge = row == 0 || row == boardSize - 1
                        || col == 0 || col == boardSize - 1;
                float margin = isEdge ? totalSpread * 0.1f : 0;

                if (hasBlack && r < blackBoundary - margin) {
                    if (!highVar || r < blackC * 0.5f) {
                        board[row][col] = BLACK;
                    }
                } else if (hasWhite && r > whiteBoundary + margin) {
                    if (!highVar || r > whiteC * 0.5f) {
                        board[row][col] = WHITE;
                    }
                }
            }
        }
        return board;
    }

    /** 圆盘内平均亮度 */
    private static float sampleDiscMean(float[] gray, float cx, float cy, float radius, int w) {
        float r2 = radius * radius;
        int x0 = Math.max(0, (int) Math.ceil(cx - radius));
        int x1 = Math.min(w - 1, (int) Math.floor(cx + radius));
        int y0 = Math.max(0, (int) Math.ceil(cy - radius));
        int y1 = Math.min(w - 1, (int) Math.floor(cy + radius));
        float sum = 0;
        int count = 0;
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                float dx = x - cx, dy = y - cy;
                if (dx * dx + dy * dy <= r2) {
                    sum += gray[y * w + x];
                    count++;
                }
            }
        }
        return count > 0 ? sum / count : 0;
    }

    /** 圆盘内标准差 */
    private static float sampleDiscStd(float[] gray, float cx, float cy, float radius, int w) {
        float r2 = radius * radius;
        int x0 = Math.max(0, (int) Math.ceil(cx - radius));
        int x1 = Math.min(w - 1, (int) Math.floor(cx + radius));
        int y0 = Math.max(0, (int) Math.ceil(cy - radius));
        int y1 = Math.min(w - 1, (int) Math.floor(cy + radius));
        float sum = 0, sumSq = 0;
        int count = 0;
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                float dx = x - cx, dy = y - cy;
                if (dx * dx + dy * dy <= r2) {
                    float v = gray[y * w + x];
                    sum += v;
                    sumSq += v * v;
                    count++;
                }
            }
        }
        if (count < 2) return 0;
        float mean = sum / count;
        return (float) Math.sqrt(Math.max(0, sumSq / count - mean * mean));
    }

    /**
     * 1-D k-means 3 聚类, 返回排序后的 [lowest, middle, highest]。
     * 对应 Kaya kmeans3。
     */
    private static float[] kmeans3(float[] values) {
        if (values.length < 3) return new float[]{0, 0, 0};
        float[] sorted = Arrays.copyOf(values, values.length);
        Arrays.sort(sorted);
        float c0 = sorted[(int) (sorted.length * 0.1)];
        float c1 = sorted[(int) (sorted.length * 0.5)];
        float c2 = sorted[(int) (sorted.length * 0.9)];

        for (int iter = 0; iter < 20; iter++) {
            float s0 = 0, s1 = 0, s2 = 0;
            int n0 = 0, n1 = 0, n2 = 0;
            for (float v : values) {
                float d0 = Math.abs(v - c0);
                float d1 = Math.abs(v - c1);
                float d2 = Math.abs(v - c2);
                if (d0 <= d1 && d0 <= d2) { s0 += v; n0++; }
                else if (d1 <= d2) { s1 += v; n1++; }
                else { s2 += v; n2++; }
            }
            float newC0 = n0 > 0 ? s0 / n0 : c0;
            float newC1 = n1 > 0 ? s1 / n1 : c1;
            float newC2 = n2 > 0 ? s2 / n2 : c2;
            if (Math.abs(newC0 - c0) + Math.abs(newC1 - c1) + Math.abs(newC2 - c2) < 0.5f) break;
            c0 = newC0; c1 = newC1; c2 = newC2;
        }
        float[] cs = {c0, c1, c2};
        Arrays.sort(cs);
        return cs;
    }

    private static int countColor(int[][] board, int color) {
        int count = 0;
        for (int[] row : board)
            for (int v : row)
                if (v == color) count++;
        return count;
    }
}
