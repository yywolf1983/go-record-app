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
}
