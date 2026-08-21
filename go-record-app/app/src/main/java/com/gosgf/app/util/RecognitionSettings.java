package com.gosgf.app.util;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 识别参数设置。所有字段都有"以当前(2026-08)线上为准"的默认值,
 * 用户可在"识别设置"页自行修改后持久化到 SharedPreferences。
 *
 * 每个字段下方的注释写明了"修改原因",供用户在设置页对照参考。
 *
 * 注意:模型本身不重训,本设置只影响几何校正、阈值与逐点复核策略。
 */
public class RecognitionSettings {

    private static final String PREF_NAME = "recognition_settings";

    // ===== 默认值(2026-08-21 调整:治"竖向少一行 + 右侧假子") =====

    /** 棋子偏离交叉点超过多少格视为可疑丢弃。
     *  改大:救回因四角/H 不准而整盘偏移的真子(尤其最底/最右行,表现为"少一行");
     *  改小:更抗误检。默认 0.80(2026-08-21 由 0.72 放宽:整盘网格偏移时底部真子
     *        偏差可达 0.5~0.8,0.72 会把它们当误检丢掉 → 少一行)。 */
    public static final float DEF_MAX_DEVIATION = 0.80f;

    /** 整盘溢出判定余量。棋子归一化坐标超出 [0-margin, 1+margin] 即认为四角把棋盘画大/画偏,
     *  触发降级到"棋子 bbox 拟合网格"。默认 0.05(2026-08-21 由 0.03 放宽:
     *  轻微透视下底行 ry≈1.02 属正常,0.03 会误触发降级;而 bbox 拟合在棋子不全时
     *  会把网格纵向压缩,反而导致少一行)。 */
    public static final float DEF_OVERFLOW_MARGIN = 0.05f;

    /** 像素判定基础阈值下限。
     *  2026-08-21 由 26 提到 30:抑制"中心采到棋盘木色、环越出棋盘采到暗色桌面"
     *  这类中等亮度差(约 30~45)在右边缘交叉点上的误补(右侧假子)。 */
    public static final float DEF_BOUNDARY_MIN = 30f;
    /** 像素判定基础阈值上限。2026-08-21 由 55 提到 58,配合下限提升保持自适应空间。 */
    public static final float DEF_BOUNDARY_MAX = 58f;
    /** 像素判定基础阈值基准值(会与 2.5*背景标准差 取大)。 */
    public static final float DEF_BOUNDARY_BASE = 12f;
    /** 背景标准差放大系数(用于自适应阈值)。 */
    public static final float DEF_BOUNDARY_K = 2.5f;

    /** 强信号倍数:判定"确定有子"需 |diff| ≥ boundary * 该倍数,或落入绝对亮度兜底。
     *  改大:更保守、误补更少但可能漏;改小:更易判有子。
     *  默认 1.5(2026-08-21 由 1.3 提高:漏检补盲/全棋盘复核的补入门槛更高,
     *  右侧边缘"环越出棋盘采到暗背景"造成的假子被过滤;真子亮度差通常远超阈值,不受影响)。 */
    public static final float DEF_STRONG_BOUNDARY_FACTOR = 1.5f;

    /** 确认"真棋盘"所需的最少已识别棋子数。低于此不启用逐点复核/孤立补盲,防空盘误补。
     *  默认 10(2026-08-21 由 12 降低:更早启用全棋盘逐点复核,模型整行漏检的底行
     *  能通过逐点像素判定补回 → 治"竖向少一行")。 */
    public static final int DEF_MIN_PLACED = 10;

    /** 绝对黑兜底亮度(<该值直接判黑)。 */
    public static final int DEF_ABS_BLACK_LUM = 50;
    /** 绝对白兜底亮度(>该值直接判白)。
     *  默认 225(2026-08-21 由 210 提高:棋盘外亮色桌面/纸面亮度常在 210~230,
     *  210 会把它们直接判白 → 右侧假子;真白子亮度通常 >240,不受影响)。 */
    public static final int DEF_ABS_WHITE_LUM = 225;

    // ===== 字段 =====
    public float maxDeviation;
    public float overflowMargin;
    public float boundaryMin;
    public float boundaryMax;
    public float boundaryBase;
    public float boundaryK;
    public float strongBoundaryFactor;
    public int minPlaced;
    public int absBlackLum;
    public int absWhiteLum;

    public RecognitionSettings() {
        this.maxDeviation = DEF_MAX_DEVIATION;
        this.overflowMargin = DEF_OVERFLOW_MARGIN;
        this.boundaryMin = DEF_BOUNDARY_MIN;
        this.boundaryMax = DEF_BOUNDARY_MAX;
        this.boundaryBase = DEF_BOUNDARY_BASE;
        this.boundaryK = DEF_BOUNDARY_K;
        this.strongBoundaryFactor = DEF_STRONG_BOUNDARY_FACTOR;
        this.minPlaced = DEF_MIN_PLACED;
        this.absBlackLum = DEF_ABS_BLACK_LUM;
        this.absWhiteLum = DEF_ABS_WHITE_LUM;
    }

    /** 从 SharedPreferences 读取(缺省用默认值)。 */
    public static RecognitionSettings load(Context ctx) {
        RecognitionSettings s = new RecognitionSettings();
        SharedPreferences p = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        s.maxDeviation = p.getFloat("maxDeviation", DEF_MAX_DEVIATION);
        s.overflowMargin = p.getFloat("overflowMargin", DEF_OVERFLOW_MARGIN);
        s.boundaryMin = p.getFloat("boundaryMin", DEF_BOUNDARY_MIN);
        s.boundaryMax = p.getFloat("boundaryMax", DEF_BOUNDARY_MAX);
        s.boundaryBase = p.getFloat("boundaryBase", DEF_BOUNDARY_BASE);
        s.boundaryK = p.getFloat("boundaryK", DEF_BOUNDARY_K);
        s.strongBoundaryFactor = p.getFloat("strongBoundaryFactor", DEF_STRONG_BOUNDARY_FACTOR);
        s.minPlaced = p.getInt("minPlaced", DEF_MIN_PLACED);
        s.absBlackLum = p.getInt("absBlackLum", DEF_ABS_BLACK_LUM);
        s.absWhiteLum = p.getInt("absWhiteLum", DEF_ABS_WHITE_LUM);
        return s;
    }

    /** 持久化到 SharedPreferences。 */
    public void save(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor e = p.edit();
        e.putFloat("maxDeviation", maxDeviation);
        e.putFloat("overflowMargin", overflowMargin);
        e.putFloat("boundaryMin", boundaryMin);
        e.putFloat("boundaryMax", boundaryMax);
        e.putFloat("boundaryBase", boundaryBase);
        e.putFloat("boundaryK", boundaryK);
        e.putFloat("strongBoundaryFactor", strongBoundaryFactor);
        e.putInt("minPlaced", minPlaced);
        e.putInt("absBlackLum", absBlackLum);
        e.putInt("absWhiteLum", absWhiteLum);
        e.apply();
    }

    /** 恢复默认。 */
    public void resetToDefault() {
        maxDeviation = DEF_MAX_DEVIATION;
        overflowMargin = DEF_OVERFLOW_MARGIN;
        boundaryMin = DEF_BOUNDARY_MIN;
        boundaryMax = DEF_BOUNDARY_MAX;
        boundaryBase = DEF_BOUNDARY_BASE;
        boundaryK = DEF_BOUNDARY_K;
        strongBoundaryFactor = DEF_STRONG_BOUNDARY_FACTOR;
        minPlaced = DEF_MIN_PLACED;
        absBlackLum = DEF_ABS_BLACK_LUM;
        absWhiteLum = DEF_ABS_WHITE_LUM;
    }
}
