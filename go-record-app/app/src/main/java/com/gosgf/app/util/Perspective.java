package com.gosgf.app.util;

/**
 * 透视变换数学工具：4 点单应性矩阵 + 应用 + 3x3 矩阵求逆。
 * 1:1 移植自 Kaya 项目 packages/board-recognition/src/perspective.ts，
 * 用于把检测到的棋盘 4 角映射到 [0,1]x[0,1] 单位正方形，进而投影到 19x19 网格。
 *
 * 全部纯 Java 实现，不依赖 OpenCV，符合"简单集成/移除"原则。
 */
public final class Perspective {

    private Perspective() {}

    /** 8x8 线性方程组 A·h = b 的高斯消元（带部分主元）。返回 null 表示奇异。 */
    private static float[] solveLinear(float[][] A, float[] b) {
        int n = A.length;
        // 增广矩阵 M[n][n+1]
        float[][] M = new float[n][n + 1];
        for (int i = 0; i < n; i++) {
            System.arraycopy(A[i], 0, M[i], 0, n);
            M[i][n] = b[i];
        }
        for (int col = 0; col < n; col++) {
            // 部分主元
            int maxRow = col;
            for (int row = col + 1; row < n; row++) {
                if (Math.abs(M[row][col]) > Math.abs(M[maxRow][col])) maxRow = row;
            }
            float[] tmp = M[col]; M[col] = M[maxRow]; M[maxRow] = tmp;
            if (Math.abs(M[col][col]) < 1e-12f) return null;
            // 消元
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

    /**
     * 计算 3x3 单应性矩阵 H（行优先 9 元素）把 src[4] → dst[4]。
     * 失败返回 null。
     * 每对点 (sx,sy) → (dx,dy) 给出 2 个方程：
     *   sx·h0 + sy·h1 + h2 - dx·sx·h6 - dx·sy·h7 = dx
     *   sx·h3 + sy·h4 + h5 - dy·sx·h6 - dy·sy·h7 = dy
     * (h8 = 1)
     */
    public static float[] computeHomography(float[][] src, float[][] dst) {
        float[][] A = new float[8][8];
        float[] b = new float[8];
        for (int i = 0; i < 4; i++) {
            float sx = src[i][0], sy = src[i][1];
            float dx = dst[i][0], dy = dst[i][1];
            A[2 * i]    = new float[]{sx, sy, 1, 0, 0, 0, -dx * sx, -dx * sy};
            b[2 * i]    = dx;
            A[2 * i + 1]= new float[]{0, 0, 0, sx, sy, 1, -dy * sx, -dy * sy};
            b[2 * i + 1]= dy;
        }
        float[] h = solveLinear(A, b);
        if (h == null) return null;
        return new float[]{h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7], 1f};
    }

    /** 应用单应性 H 到点 (x,y) → (x',y')。 */
    public static float[] applyHomography(float[] H, float x, float y) {
        float w = H[6] * x + H[7] * y + H[8];
        if (Math.abs(w) < 1e-10f) return new float[]{x, y};
        return new float[]{(H[0] * x + H[1] * y + H[2]) / w,
                          (H[3] * x + H[4] * y + H[5]) / w};
    }

    /**
     * 用 N≥4 对点最小二乘拟合单应性矩阵（超定 DLT）。
     * 内部用 double 计算并做坐标归一化，保证手机像素尺度下的数值稳定。
     * 返回行优先 9 元素（h8=1）；失败（奇异/退化）返回 null。
     * 典型用途：手动四角只给粗范围，用检测到的棋子中心 ↔ 最近网格点做点对，
     * 反推精确 H 矩阵（棋子检测比手指拖拽可靠得多）。
     */
    public static float[] fitHomography(float[][] src, float[][] dst, int n) {
        if (n < 4) return null;
        // 坐标归一化（DGT 标准做法：平移+缩放，避免正规方程病态）
        double csx = 0, csy = 0, cdx = 0, cdy = 0;
        for (int i = 0; i < n; i++) {
            csx += src[i][0]; csy += src[i][1]; cdx += dst[i][0]; cdy += dst[i][1];
        }
        csx /= n; csy /= n; cdx /= n; cdy /= n;
        double ss = 0, sd = 0;
        for (int i = 0; i < n; i++) {
            ss += Math.hypot(src[i][0] - csx, src[i][1] - csy);
            sd += Math.hypot(dst[i][0] - cdx, dst[i][1] - cdy);
        }
        ss = ss / n; sd = sd / n;
        if (ss < 1e-6) ss = 1.0;
        if (sd < 1e-6) sd = 1.0;
        double s1 = Math.sqrt(2) / ss, s2 = Math.sqrt(2) / sd;
        // 每个点对 2 个方程：A(2n x 8) h = b
        double[][] A = new double[2 * n][8];
        double[] b = new double[2 * n];
        for (int i = 0; i < n; i++) {
            double x = (src[i][0] - csx) * s1, y = (src[i][1] - csy) * s1;
            double u = (dst[i][0] - cdx) * s2, v = (dst[i][1] - cdy) * s2;
            A[2 * i]     = new double[]{x, y, 1, 0, 0, 0, -u * x, -u * y};
            b[2 * i]     = u;
            A[2 * i + 1] = new double[]{0, 0, 0, x, y, 1, -v * x, -v * y};
            b[2 * i + 1] = v;
        }
        // 正规方程 A^T A h = A^T b（8x8 double）
        double[][] AtA = new double[8][8];
        double[] Atb = new double[8];
        for (int i = 0; i < 2 * n; i++) {
            for (int r = 0; r < 8; r++) {
                Atb[r] += A[i][r] * b[i];
                for (int c = 0; c < 8; c++) AtA[r][c] += A[i][r] * A[i][c];
            }
        }
        double[] hn = solveLinearD(AtA, Atb);
        if (hn == null) return null;
        // 还原：H = T_dst^{-1} * Hn * T_src
        double[][] Ts = {{s1, 0, -s1 * csx}, {0, s1, -s1 * csy}, {0, 0, 1}};
        double[][] Ti = {{1 / s2, 0, cdx}, {0, 1 / s2, cdy}, {0, 0, 1}};
        double[][] Hn = {{hn[0], hn[1], hn[2]}, {hn[3], hn[4], hn[5]}, {hn[6], hn[7], 1}};
        double[][] M = matMul3(matMul3(Ti, Hn), Ts);
        float[] h = new float[9];
        for (int r = 0; r < 3; r++)
            for (int c = 0; c < 3; c++) h[r * 3 + c] = (float) M[r][c];
        return h;
    }

    /** 3x3 单应性矩阵求逆（代数余子式法）。返回 null 表示奇异。 */
    public static float[] invertH(float[] H) {
        float a = H[0], b = H[1], c = H[2];
        float d = H[3], e = H[4], f = H[5];
        float g = H[6], h = H[7], i = H[8];
        float det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
        if (Math.abs(det) < 1e-10f) return null;
        float inv = 1f / det;
        return new float[]{
            (e * i - f * h) * inv, (c * h - b * i) * inv, (b * f - c * e) * inv,
            (f * g - d * i) * inv, (a * i - c * g) * inv, (c * d - a * f) * inv,
            (d * h - e * g) * inv, (b * g - a * h) * inv, (a * e - b * d) * inv
        };
    }

    /** 8x8 高斯消元（double 版，带部分主元）。返回 null 表示奇异。 */
    private static double[] solveLinearD(double[][] A, double[] b) {
        int n = A.length;
        double[][] M = new double[n][n + 1];
        for (int i = 0; i < n; i++) {
            System.arraycopy(A[i], 0, M[i], 0, n);
            M[i][n] = b[i];
        }
        for (int col = 0; col < n; col++) {
            int maxRow = col;
            for (int row = col + 1; row < n; row++) {
                if (Math.abs(M[row][col]) > Math.abs(M[maxRow][col])) maxRow = row;
            }
            double[] tmp = M[col]; M[col] = M[maxRow]; M[maxRow] = tmp;
            if (Math.abs(M[col][col]) < 1e-12) return null;
            for (int row = 0; row < n; row++) {
                if (row == col) continue;
                double f = M[row][col] / M[col][col];
                for (int k = col; k <= n; k++) M[row][k] -= f * M[col][k];
            }
        }
        double[] x = new double[n];
        for (int i = 0; i < n; i++) x[i] = M[i][n] / M[i][i];
        return x;
    }

    /** 3x3 矩阵乘法。 */
    private static double[][] matMul3(double[][] A, double[][] B) {
        double[][] C = new double[3][3];
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++) {
                double s = 0;
                for (int k = 0; k < 3; k++) s += A[i][k] * B[k][j];
                C[i][j] = s;
            }
        return C;
    }
}
