package com.gosgf.app.engine;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.gosgf.app.model.GoBoard;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * KataGo 引擎封装（完全 JNI / lib 方式）。
 *
 * - 引擎核心与自写的 JNI 封装一起由 NDK 编译进 libkatago.so（随 APK 打包）。
 *   CMake 构建见 KataGo/cpp/CMakeLists_library.txt（USE_BACKEND=OPENCL，调用手机 GPU）。
 * - OPENCL 后端需 libOpenCL.so：已从设备提取真实库放入 app/src/main/jniLibs/，
 *   由 Gradle 打包进 APK 以满足 libkatago.so 的动态链接依赖。
 * - 仅模型权重(.bin.gz)与配置文件(gtp_android.cfg)由外部/资产提供，
 *   启动时把绝对路径传给 native initialize。
 * - native 侧直接调用 KataGo 的 AsyncBot + Search::getAnalysisJson，返回与官方
 *   analysis 协议完全一致（rootInfo / moveInfos）的 JSON，analyze 端无需特殊处理。
 */
public class KataGoEngine {

    private static final String TAG = "KataGoEngine";

    private static final String CFG_ASSET = "gtp_android.cfg";

    // KataGo GTP 列字母表（大写、跳过 'I'，对应 x = 0..18）
    private static final String KATA_GTP_COLS = "ABCDEFGHJKLMNOPQRST";

    private static boolean libLoaded = false;

    /** 延迟加载 native 库；失败时返回错误而非崩溃（如设备缺失 libOpenCL.so） */
    private static String ensureLibLoaded() {
        if (libLoaded) return null;
        try {
            System.loadLibrary("katago");
            libLoaded = true;
            return null;
        } catch (UnsatisfiedLinkError e) {
            return "加载引擎库失败：" + e.getMessage()
                    + "\n（可能设备缺少 OpenCL 支持；可在设置中确认或改用 EIGEN 后端重新编译）";
        }
    }

    /** 单个候选着法分析结果 */
    public static class AnalysisMove {
        public int x;            // 棋盘列 0-18
        public int y;            // 棋盘行 0-18
        public int player;       // GoBoard.BLACK / WHITE
        public int order;        // 候选排序（0=最佳）
        public int visits;       // 访问次数
        public double winrate;   // 该方胜率 0-1
        public double scoreLead; // 领先目数（正=该方领先）

        public AnalysisMove(int x, int y, int player, int order, int visits, double winrate, double scoreLead) {
            this.x = x;
            this.y = y;
            this.player = player;
            this.order = order;
            this.visits = visits;
            this.winrate = winrate;
            this.scoreLead = scoreLead;
        }
    }

    /** 一次分析的整体结果 */
    public static class AnalysisResult {
        public double rootWinrate;   // 当前行棋方的胜率 0-1
        public double rootScoreLead; // 当前行棋方领先目数
        public List<AnalysisMove> moves = new ArrayList<>();
    }

    // ---- native 方法（实现见 KataGo/cpp/katago_jni.cpp）----
    /** 加载模型与配置，返回 0 成功，非 0 失败 */
    private static native int initialize(String modelPath, String configPath);
    /** 获取上一次 native 调用的错误信息 */
    private static native String getLastError();
    /** 分析一个局面，requestJson 为 KataGo analysis 协议格式；返回分析 JSON 字符串 */
    private static native String analyzePosition(String requestJson);
    /** 释放引擎 */
    private static native void close();

    private File modelFile;
    private File cfgFile;
    private boolean initialized = false;
    private int idCounter = 0;

    /**
     * 准备引擎。
     * 模型权重由调用方从任意目录提供（modelPath，应用私有目录内的副本），
     * 不再内置在 APK assets 中；配置文件(cfg)仍从 assets 解压到应用私有目录。
     * @param context   用于解压 cfg
     * @param modelPath 用户选定的模型文件绝对路径
     * @return 错误信息；null 表示准备就绪。
     */
    public String prepare(Context context, String modelPath) {
        String loadErr = ensureLibLoaded();
        if (loadErr != null) return loadErr;
        if (modelPath == null || modelPath.isEmpty()) {
            return "尚未选择模型文件，请在 KataGo 设置中选择（可放在任意目录）";
        }
        modelFile = new File(modelPath);
        if (!modelFile.exists() || !modelFile.canRead()) {
            return "模型文件不可读：" + modelPath;
        }

        cfgFile = extractAsset(context, CFG_ASSET, "gtp_android.cfg");
        if (cfgFile == null) return "配置文件缺失，请确认已放入 assets/" + CFG_ASSET;
        rewriteConfigModel(cfgFile, modelFile.getAbsolutePath());

        // 打印正在加载的模型（文件名 + 绝对路径），便于核对从配置加载的文件是否正确
        Log.i(TAG, "准备从配置加载模型: fileName=" + modelFile.getName()
                + " absPath=" + modelFile.getAbsolutePath()
                + " cfg=" + cfgFile.getAbsolutePath());

        int ret = initialize(modelFile.getAbsolutePath(), cfgFile.getAbsolutePath());
        if (ret != 0) {
            String err = getLastError();
            // 完整打印 native 错误原文到 logcat，便于用 adb logcat 查看根因
            // （如 "newer model version than this katago supports" 等版本不匹配提示）
            Log.e(TAG, "initialize 失败 (ret=" + ret + ") getLastError=" + err);
            return "引擎初始化失败：\n" + (err != null ? err : "(无错误详情，ret=" + ret + ")");
        }
        initialized = true;
        return null;
    }

    /**
     * 分析当前局面。
     *
     * 直接把当前真实棋盘（boardState[y][x]，y=0 在顶部，与 BoardView 一致）发给引擎，
     * 由引擎端 parseBoard 重建，避免依赖走子序列历史重建导致坐标错位（表现为推荐点
     * 落在已有棋子上）。坐标为 KataGo GTP 风格：列字母跳过 'I' 大写，行号从底边 1 起。
     *
     * @param boardState 当前棋盘，boardState[y][x]，非 EMPTY 处为棋子
     * @param boardSize  棋盘大小（通常 19）
     * @param maxVisits  最大访问次数
     * @param nextPlayer 当前行棋方（GoBoard.BLACK / GoBoard.WHITE）
     * @return 分析结果
     */
    public synchronized AnalysisResult analyze(int[][] boardState, int boardSize, int maxVisits,
                                                 int nextPlayer, double komi, int numThreads)
            throws IOException, JSONException {
        return analyze(boardState, boardSize, maxVisits, nextPlayer, komi, numThreads, true, false);
    }

    /**
     * 分析当前局面。
     * @param includePolicy 是否请求策略输出。估算场景关闭可显著减少引擎计算量（秒出）。
     */
    public synchronized AnalysisResult analyze(int[][] boardState, int boardSize, int maxVisits,
                                                 int nextPlayer, double komi, int numThreads,
                                                 boolean includePolicy)
            throws IOException, JSONException {
        return analyze(boardState, boardSize, maxVisits, nextPlayer, komi, numThreads, includePolicy, false);
    }

    /**
     * 分析当前局面。
     * @param includePolicy 是否请求策略输出。估算场景关闭可显著减少引擎计算量（秒出）。
     * @param tsumeMode 死活模式: true → dynamicPlayoutDoublingAdvantageCapPerOppLead = 0.0,
     *                  不随优势降低搜索量, 死活/官子算得更清。false → 普通对局默认。
     */
    public synchronized AnalysisResult analyze(int[][] boardState, int boardSize, int maxVisits,
                                                 int nextPlayer, double komi, int numThreads,
                                                 boolean includePolicy, boolean tsumeMode)
            throws IOException, JSONException {

        if (!initialized) throw new IOException("引擎尚未初始化，请先调用 prepare()");

        JSONObject req = new JSONObject();
        req.put("id", ++idCounter);
        req.put("rules", "chinese");
        req.put("boardXSize", boardSize);
        req.put("boardYSize", boardSize);
        req.put("maxVisits", maxVisits);
        req.put("numThreads", numThreads);
        req.put("komi", komi);
        req.put("includePolicy", includePolicy);
        req.put("includeOwnership", false);
        req.put("initialPlayer", nextPlayer == GoBoard.WHITE ? "W" : "B");

        // 死活模式: 不随优势减少每步展开的 playout 数量，避免大优势下因展开不足而漏死活、漏官子
        if (tsumeMode) {
            req.put("dynamicPlayoutDoublingAdvantageCapPerOppLead", 0.0);
        }

        // 直接发送当前棋盘所有棋子，让引擎端 parseBoard 精确重建局面
        StringBuilder stones = new StringBuilder();
        for (int y = 0; y < boardSize; y++) {
            for (int x = 0; x < boardSize; x++) {
                int v = boardState[y][x];
                if (v == GoBoard.EMPTY) continue;
                char col = KATA_GTP_COLS.charAt(x);
                int row = boardSize - y; // GoBoard y=0 在顶 → KataGo 行号从底起
                String color = (v == GoBoard.BLACK) ? "B" : "W";
                if (stones.length() > 0) stones.append(" / ");
                stones.append(color).append(" ").append(col).append(row);
            }
        }
        req.put("initialStones", stones.toString());

        String respStr = analyzePosition(req.toString());
        if (respStr == null) throw new IOException("引擎无响应");
        JSONObject resp = new JSONObject(respStr);
        if (resp.has("error")) {
            throw new IOException("引擎分析错误：" + resp.optString("error", "unknown"));
        }
        return parseAnalysis(resp, nextPlayer, boardSize);
    }

    private AnalysisResult parseAnalysis(JSONObject resp, int whoToMove, int boardSize)
            throws JSONException {
        AnalysisResult result = new AnalysisResult();
        JSONObject rootInfo = resp.optJSONObject("rootInfo");
        if (rootInfo != null) {
            result.rootWinrate = rootInfo.optDouble("winrate", 0.5);
            result.rootScoreLead = rootInfo.optDouble("scoreLead", 0.0);
        }
        JSONArray turnInfos = resp.optJSONArray("turnInfos");
        JSONArray moveInfos = null;
        if (turnInfos != null && turnInfos.length() > 0) {
            JSONObject turn0 = turnInfos.getJSONObject(0);
            moveInfos = turn0.optJSONArray("moveInfos");
        }
        if (moveInfos == null) {
            moveInfos = resp.optJSONArray("moveInfos");
        }
        if (moveInfos != null) {
            for (int i = 0; i < moveInfos.length(); i++) {
                JSONObject m = moveInfos.getJSONObject(i);
                String loc = m.optString("move", "pass");
                int x, y;
                if ("pass".equalsIgnoreCase(loc)) {
                    x = -1;
                    y = -1;
                } else {
                    if (loc.length() < 2) continue;
                    char kataCol = loc.charAt(0);
                    int xIdx = KATA_GTP_COLS.indexOf(kataCol);
                    if (xIdx < 0) continue;
                    x = xIdx;
                    int kataRow;
                    try {
                        kataRow = Integer.parseInt(loc.substring(1));
                    } catch (NumberFormatException e) {
                        continue;
                    }
                    y = boardSize - kataRow; // 翻转回 GoBoard（y=0 在顶）
                    if (x < 0 || x >= boardSize || y < 0 || y >= boardSize) continue;
                }
                int order = m.optInt("order", i);
                int visits = m.optInt("visits", 0);
                double winrate = m.optDouble("winrate", 0.5);
                double scoreLead = m.optDouble("scoreLead", 0.0);
                result.moves.add(new AnalysisMove(x, y, whoToMove, order, visits, winrate, scoreLead));
            }
        }
        Log.i(TAG, "analyze 完成: rootWinrate=" + result.rootWinrate
                + " scoreLead=" + result.rootScoreLead
                + " candidates=" + result.moves.size());
        return result;
    }

    public void closeEngine() {
        try {
            close();
        } catch (Exception ignored) {
        }
        initialized = false;
    }

    // ==================== assets 解压 ====================

    private File extractAsset(Context context, String assetName, String outName) {
        File out = new File(context.getCacheDir(), outName);
        try {
            if (out.exists() && out.length() > 0) return out;
        } catch (Exception ignored) {
        }
        try (InputStream in = context.getAssets().open(assetName);
             FileOutputStream fos = new FileOutputStream(out)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
            out.setReadable(true);
            return out;
        } catch (IOException e) {
            Log.e(TAG, "解压 assets 失败: " + assetName, e);
            return null;
        }
    }

    /** 把配置文件中的 model= 行重写为指定绝对路径 */
    private void rewriteConfigModel(File cfgFile, String modelAbsPath) {
        Log.i(TAG, "重写配置 model= 为: " + modelAbsPath + " (cfg=" + cfgFile.getAbsolutePath() + ")");
        File tmp = new File(cfgFile.getAbsolutePath() + ".tmp");
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(cfgFile));
             FileOutputStream fos = new FileOutputStream(tmp)) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                if (line.trim().startsWith("model") && line.contains("=")) {
                    sb.append("model = ").append(modelAbsPath).append("\n");
                } else {
                    sb.append(line).append("\n");
                }
            }
            fos.write(sb.toString().getBytes("UTF-8"));
            if (!tmp.renameTo(cfgFile)) {
                cfgFile.delete();
                tmp.renameTo(cfgFile);
            }
        } catch (IOException e) {
            Log.e(TAG, "重写配置 model 路径失败", e);
        }
    }
}
