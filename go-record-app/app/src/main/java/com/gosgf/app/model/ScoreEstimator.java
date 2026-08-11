package com.gosgf.app.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

/**
 * 胜负估算器 - 按照围棋规则：棋子 + 围空 + 贴目
 *
 * 两层显示：
 * 1. 确定围空（BFS Flood-Fill）：空交叉点的连通区域，只与一种颜色的棋子相邻 → 该方确定领地
 * 2. 势力范围（近距离检测）：空交叉点距离 ≤ 3 内只有一种颜色的棋子 → 该方势力范围
 */
public class ScoreEstimator {

    private static final int BOARD_SIZE = 19;
    private static final float DEFAULT_KOMI = 6.5f;

    public static final int EMPTY = 0;
    public static final int BLACK = 1;
    public static final int WHITE = 2;

    private int[][] board;
    private final List<GoBoard.Position> deadBlackStones = new ArrayList<>();
    private final List<GoBoard.Position> deadWhiteStones = new ArrayList<>();
    private float komi = DEFAULT_KOMI;

    // ==================== 围空缓存 ====================

    private int lastBoardHash = 0;
    private int cachedBlackTerritory = 0;
    private int cachedWhiteTerritory = 0;
    private List<GoBoard.Position> cachedBlackTerritoryPositions;
    private List<GoBoard.Position> cachedWhiteTerritoryPositions;
    /** 仅「小目块」(面积≤阈值)，作为确定死目实心点显示；大块空不在此列，改由势力范围呈现 */
    private List<GoBoard.Position> cachedBlackTerritorySmall;
    private List<GoBoard.Position> cachedWhiteTerritorySmall;

    // ==================== 势力范围缓存 ====================

    private List<GoBoard.Position> cachedBlackPotentialPositions;
    private List<GoBoard.Position> cachedWhitePotentialPositions;
    /** 全部势力点（含中立争议区），供棋盘逐点覆盖绘制 */
    private List<InfluencePoint> cachedAllInfluencePoints;
    /** 势力强度（归属确定性，0~1），与上面两个列表对应的空点索引一致；其他点为 0 */
    private float[][] cachedPotentialStrength;

    // ==================== 死子估算缓存 ====================

    /** 势力范围估算目（不含子/死子调整），用于弹窗拆分展示 */
    private int cachedInfluenceBlack = 0;
    private int cachedInfluenceWhite = 0;

    private List<GoBoard.Position> cachedDeadBlackByEstimator;
    private List<GoBoard.Position> cachedDeadWhiteByEstimator;
    private float cachedEstimatedBlackScore = 0;
    private float cachedEstimatedWhiteScore = 0;

    public ScoreEstimator(int[][] board) {
        this.board = board;
    }

    public void setBoard(int[][] board) {
        this.board = board;
        invalidateCache();
    }

    private void invalidateCache() {
        lastBoardHash = 0;
        cachedBlackTerritory = 0;
        cachedWhiteTerritory = 0;
        cachedBlackTerritoryPositions = null;
        cachedWhiteTerritoryPositions = null;
        cachedBlackPotentialPositions = null;
        cachedWhitePotentialPositions = null;
        cachedAllInfluencePoints = null;
        cachedDeadBlackByEstimator = null;
        cachedDeadWhiteByEstimator = null;
        cachedEstimatedBlackScore = 0;
        cachedEstimatedWhiteScore = 0;
    }

    private int computeBoardHash() {
        int hash = 0;
        for (int y = 0; y < BOARD_SIZE; y++) {
            for (int x = 0; x < BOARD_SIZE; x++) {
                hash = hash * 31 + board[y][x];
            }
        }
        return hash;
    }

    // ==================== 确定围空（BFS Flood-Fill） ====================

    /** 单色围空块面积超过该阈值即视为「大空」，不作为「确定死目」实心显示，
     *  改由势力范围（强弱点）呈现，避免估算时「完全算死目」而失去势力分布。 */
    private static final int LARGE_TERRITORY_THRESHOLD = 10;

    private void ensureTerritoryCalculated() {
        int currentHash = computeBoardHash();
        if (lastBoardHash == currentHash && cachedBlackTerritoryPositions != null) {
            return;
        }

        lastBoardHash = currentHash;
        cachedBlackTerritoryPositions = new ArrayList<>();
        cachedWhiteTerritoryPositions = new ArrayList<>();
        cachedBlackTerritorySmall = new ArrayList<>();
        cachedWhiteTerritorySmall = new ArrayList<>();

        boolean[][] visited = new boolean[BOARD_SIZE][BOARD_SIZE];

        for (int y = 0; y < BOARD_SIZE; y++) {
            for (int x = 0; x < BOARD_SIZE; x++) {
                if (board[y][x] == EMPTY && !visited[y][x]) {
                    List<GoBoard.Position> region = new ArrayList<>();
                    int owner = floodFillEmptyRegion(x, y, visited, region);

                    if (owner == BLACK) {
                        cachedBlackTerritoryPositions.addAll(region);
                        // 小块才是「确定死目」，大块归势力范围
                        if (region.size() <= LARGE_TERRITORY_THRESHOLD) {
                            cachedBlackTerritorySmall.addAll(region);
                        }
                    } else if (owner == WHITE) {
                        cachedWhiteTerritoryPositions.addAll(region);
                        if (region.size() <= LARGE_TERRITORY_THRESHOLD) {
                            cachedWhiteTerritorySmall.addAll(region);
                        }
                    }
                }
            }
        }

        cachedBlackTerritory = cachedBlackTerritoryPositions.size();
        cachedWhiteTerritory = cachedWhiteTerritoryPositions.size();
    }

    /**
     * BFS 从 (startX, startY) 出发，收集连通的空交叉点区域，
     * 判断该区域被哪种颜色的棋子完全包围。
     *
     * @return BLACK / WHITE / 0（中立）
     */
    private int floodFillEmptyRegion(int startX, int startY, boolean[][] visited,
                                      List<GoBoard.Position> region) {
        LinkedList<GoBoard.Position> queue = new LinkedList<>();
        queue.add(new GoBoard.Position(startX, startY));
        visited[startY][startX] = true;
        region.add(new GoBoard.Position(startX, startY));

        boolean touchesBlack = false;
        boolean touchesWhite = false;

        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

        while (!queue.isEmpty()) {
            GoBoard.Position pos = queue.poll();
            for (int[] dir : directions) {
                int nx = pos.x + dir[0];
                int ny = pos.y + dir[1];
                if (nx < 0 || nx >= BOARD_SIZE || ny < 0 || ny >= BOARD_SIZE) continue;

                if (board[ny][nx] == EMPTY && !visited[ny][nx]) {
                    visited[ny][nx] = true;
                    GoBoard.Position next = new GoBoard.Position(nx, ny);
                    queue.add(next);
                    region.add(next);
                } else if (board[ny][nx] == BLACK) {
                    touchesBlack = true;
                } else if (board[ny][nx] == WHITE) {
                    touchesWhite = true;
                }
            }
        }

        // 围棋规则：只被一种颜色包围 → 该方的目；双方接触 → 中立
        if (touchesBlack && !touchesWhite) return BLACK;
        if (touchesWhite && !touchesBlack) return WHITE;
        return 0;
    }

    // ==================== 势力范围（平方反比势力归属，覆盖全棋盘） ====================

    private void ensureInfluenceCalculated() {
        // 复用围空缓存的哈希
        int currentHash = computeBoardHash();
        if (lastBoardHash == currentHash && cachedBlackPotentialPositions != null) {
            return;
        }

        lastBoardHash = currentHash;
        cachedBlackPotentialPositions = new ArrayList<>();
        cachedWhitePotentialPositions = new ArrayList<>();
        cachedPotentialStrength = new float[BOARD_SIZE][BOARD_SIZE];

        // 确保围空也算过了（势力要排除已确认围空的点）
        if (cachedBlackTerritoryPositions == null) {
            ensureTerritoryCalculated();
        }

        // 仅排除「小目块」确定围空；大块单色围空保留为势力范围（避免完全算死目）
        Set<GoBoard.Position> territorySet = new HashSet<>();
        if (cachedBlackTerritorySmall != null) territorySet.addAll(cachedBlackTerritorySmall);
        if (cachedWhiteTerritorySmall != null) territorySet.addAll(cachedWhiteTerritorySmall);

        for (int y = 0; y < BOARD_SIZE; y++) {
            for (int x = 0; x < BOARD_SIZE; x++) {
                if (board[y][x] != EMPTY) continue;

                GoBoard.Position p = new GoBoard.Position(x, y);
                // 已经是确认围空 → 跳过（由围空点单独渲染）
                if (territorySet.contains(p)) continue;

                int owner = potentialOwner(x, y);
                if (owner == BLACK) {
                    cachedBlackPotentialPositions.add(p);
                } else if (owner == WHITE) {
                    cachedWhitePotentialPositions.add(p);
                }
                // 记录归属确定性（0~1），供渲染强度使用
                cachedPotentialStrength[y][x] = potentialStrength(x, y);
            }
        }
    }

    /**
     * 对空交叉点 (x,y) 用「平方反比势力值」判定归属。
     * 只要有一方势力更强即判归该方——不做「争议中立过渡带」，避免势力范围被过度保守地丢弃。
     * 仅当双方都完全够不着该点（势力均为 0）时才判为中立（这种点本就无归属）。
     */
    private int potentialOwner(int x, int y) {
        float bI = influenceValue(x, y, BLACK);
        float wI = influenceValue(x, y, WHITE);
        if (bI <= 0 && wI <= 0) return 0;          // 双方都够不着 → 中立
        if (bI >= wI) return BLACK;                 // 黑更强（含相等）归黑
        return WHITE;                              // 白更强归白
    }

    /**
     * 势力强度（归属确定性，0~1）：极强处接近 1，越靠近争议边界越接近 0。
     * 用于棋盘渲染时按强弱调节点的透明度/大小，使势力范围视觉更准确。
     */
    private float potentialStrength(int x, int y) {
        float bI = influenceValue(x, y, BLACK);
        float wI = influenceValue(x, y, WHITE);
        if (bI <= 0 && wI <= 0) return 0;
        float strong = Math.max(bI, wI);
        float weak = Math.min(bI, wI);
        if (strong <= 0) return 0;
        return 1.0f - weak / strong;
    }

    /** 取空点 (x,y) 的势力强度（0~1）；非势力点返回 0 */
    public float getPotentialStrengthAt(int x, int y) {
        ensureInfluenceCalculated();
        if (cachedPotentialStrength == null) return 0;
        if (x < 0 || x >= BOARD_SIZE || y < 0 || y >= BOARD_SIZE) return 0;
        return cachedPotentialStrength[y][x];
    }

    // ==================== 贴目 ====================

    public void setKomi(float komi) {
        this.komi = komi;
    }

    public float getKomi() {
        return komi;
    }

    // ==================== 棋子计数 ====================

    public int countBlackStones() {
        if (board == null) return 0;
        int count = 0;
        for (int y = 0; y < BOARD_SIZE; y++) {
            for (int x = 0; x < BOARD_SIZE; x++) {
                if (board[y][x] == BLACK) count++;
            }
        }
        count -= deadBlackStones.size();
        return Math.max(0, count);
    }

    public int countWhiteStones() {
        if (board == null) return 0;
        int count = 0;
        for (int y = 0; y < BOARD_SIZE; y++) {
            for (int x = 0; x < BOARD_SIZE; x++) {
                if (board[y][x] == WHITE) count++;
            }
        }
        count -= deadWhiteStones.size();
        return Math.max(0, count);
    }

    // ==================== 围空统计 ====================

    public int countBlackTerritory() {
        ensureTerritoryCalculated();
        return cachedBlackTerritory;
    }

    public int countWhiteTerritory() {
        ensureTerritoryCalculated();
        return cachedWhiteTerritory;
    }

    public List<GoBoard.Position> getBlackTerritoryPositions() {
        ensureTerritoryCalculated();
        return new ArrayList<>(cachedBlackTerritoryPositions);
    }

    public List<GoBoard.Position> getWhiteTerritoryPositions() {
        ensureTerritoryCalculated();
        return new ArrayList<>(cachedWhiteTerritoryPositions);
    }

    /** 仅「小目块」确定围空（面积≤阈值），作为确定死目实心点显示；大空不含在内 */
    public List<GoBoard.Position> getBlackTerritorySmallPositions() {
        ensureTerritoryCalculated();
        return new ArrayList<>(cachedBlackTerritorySmall);
    }

    public List<GoBoard.Position> getWhiteTerritorySmallPositions() {
        ensureTerritoryCalculated();
        return new ArrayList<>(cachedWhiteTerritorySmall);
    }

    // ==================== 势力范围（近距离检测） ====================

    public List<GoBoard.Position> getBlackPotentialPositions() {
        ensureInfluenceCalculated();
        return new ArrayList<>(cachedBlackPotentialPositions);
    }

    public List<GoBoard.Position> getWhitePotentialPositions() {
        ensureInfluenceCalculated();
        return new ArrayList<>(cachedWhitePotentialPositions);
    }

    /**
     * 势力点（含中立争议区），供棋盘逐点绘制覆盖全势力范围。
     * owner：BLACK / WHITE / 0（中立）；strength：归属确定性 0~1（中立点取双方接近度，仍按强弱近似）。
     * 注意：小目块确定围空已单独画实心点，此处不再包含（避免与确定目重叠）。
     */
    public List<InfluencePoint> getAllInfluencePoints() {
        ensureInfluenceCalculated();
        if (cachedAllInfluencePoints != null) return new ArrayList<>(cachedAllInfluencePoints);

        cachedAllInfluencePoints = new ArrayList<>();
        Set<GoBoard.Position> smallSet = new HashSet<>();
        if (cachedBlackTerritorySmall != null) smallSet.addAll(cachedBlackTerritorySmall);
        if (cachedWhiteTerritorySmall != null) smallSet.addAll(cachedWhiteTerritorySmall);

        for (int y = 0; y < BOARD_SIZE; y++) {
            for (int x = 0; x < BOARD_SIZE; x++) {
                if (board[y][x] != EMPTY) continue;
                GoBoard.Position p = new GoBoard.Position(x, y);
                if (smallSet.contains(p)) continue; // 小目块由确定围空单独绘制
                int owner = potentialOwner(x, y);
                float strength = potentialStrength(x, y);
                cachedAllInfluencePoints.add(new InfluencePoint(x, y, owner, strength));
            }
        }
        return new ArrayList<>(cachedAllInfluencePoints);
    }

    /** 单个势力点：坐标 + 归属方(1黑/2白/0中立) + 强度(0~1) */
    public static class InfluencePoint {
        public final int x;
        public final int y;
        public final int owner;
        public final float strength;
        public InfluencePoint(int x, int y, int owner, float strength) {
            this.x = x; this.y = y; this.owner = owner; this.strength = strength;
        }
    }

    // ==================== 旧影响力 API 保持兼容 ====================

    /** @deprecated 使用 getBlackPotentialPositions() / getWhitePotentialPositions() */
    public List<GoBoard.Position> getBlackInfluencePositions() {
        return getBlackPotentialPositions();
    }

    /** @deprecated 使用 getBlackPotentialPositions() / getWhitePotentialPositions() */
    public List<GoBoard.Position> getWhiteInfluencePositions() {
        return getWhitePotentialPositions();
    }

    public float getBlackInfluenceValue() {
        ensureInfluenceCalculated();
        return cachedBlackPotentialPositions.size();
    }

    public float getWhiteInfluenceValue() {
        ensureInfluenceCalculated();
        return cachedWhitePotentialPositions.size();
    }

    public float getInfluenceAt(int x, int y) {
        ensureInfluenceCalculated();
        GoBoard.Position p = new GoBoard.Position(x, y);
        if (cachedBlackPotentialPositions.contains(p)) return 1;
        if (cachedWhitePotentialPositions.contains(p)) return -1;

        // 也查围空
        ensureTerritoryCalculated();
        if (cachedBlackTerritoryPositions.contains(p)) return 1;
        if (cachedWhiteTerritoryPositions.contains(p)) return -1;
        return 0;
    }

    // ==================== 胜负计算（棋子 + 围空 + 贴目） ====================

    /**
     * 标准围棋数目：黑方 = 黑棋 + 黑围空，白方 = 白棋 + 白围空 + 贴目
     * @return 正数 = 黑方领先，负数 = 白方领先
     */
    public float calculateScore() {
        if (board == null) return 0;
        int blackTotal = countBlackStones() + countBlackTerritory();
        int whiteTotal = countWhiteStones() + countWhiteTerritory();
        return blackTotal - (whiteTotal + komi);
    }

    /**
     * 面积法估算：确定围空(记1目) + 争议/边界点按双方势力归一化分配 + 自动死子扣除
     * @return {黑分, 白分}
     */
    private float[] computeAreaScore() {
        ensureEstimationCalculated();   // 围空 / 势力 / 死子缓存一并就绪

        int autoDeadBlack = cachedDeadBlackByEstimator != null ? cachedDeadBlackByEstimator.size() : 0;
        int autoDeadWhite = cachedDeadWhiteByEstimator != null ? cachedDeadWhiteByEstimator.size() : 0;

        float black = Math.max(0, countBlackStones() - autoDeadBlack);
        float white = Math.max(0, countWhiteStones() - autoDeadWhite) + komi;

        // 确定围空（完全包围的空白连通块）→ 每点记 1 目
        boolean[] blackTerr = new boolean[BOARD_SIZE * BOARD_SIZE];
        boolean[] whiteTerr = new boolean[BOARD_SIZE * BOARD_SIZE];
        if (cachedBlackTerritoryPositions != null) {
            for (GoBoard.Position p : cachedBlackTerritoryPositions) {
                blackTerr[p.y * BOARD_SIZE + p.x] = true;
                black += 1;
            }
        }
        if (cachedWhiteTerritoryPositions != null) {
            for (GoBoard.Position p : cachedWhiteTerritoryPositions) {
                whiteTerr[p.y * BOARD_SIZE + p.x] = true;
                white += 1;
            }
        }

        // 争议/边界空白点：按双方「连续势力值」归一化分配（谁近谁多）
        for (int y = 0; y < BOARD_SIZE; y++) {
            for (int x = 0; x < BOARD_SIZE; x++) {
                int idx = y * BOARD_SIZE + x;
                if (blackTerr[idx] || whiteTerr[idx]) continue;   // 已确定围空不再重复计
                if (board[y][x] != EMPTY) continue;
                float bI = influenceValue(x, y, BLACK);
                float wI = influenceValue(x, y, WHITE);
                if (bI <= 0 && wI <= 0) continue;                 // 双方都够不着 → 中立不计
                float total = bI + wI;
                black += bI / total;
                white += wI / total;
            }
        }
        return new float[]{black, white};
    }

    /**
     * 连续势力值：点 (x,y) 处某颜色的「距离加权势力」。
     * 对所有该色棋子求和，近处棋子贡献大、远处快速衰减：
     *   weight = 1/(d+1) + 0.5/(d²+1)
     * 比纯平方反比更「果断」——近距棋子的主导权更强，远端双方趋近 0 的模糊中立区被压缩，
     * 从而让势力归属更明确、估算不再过于保守（更接近真实围空）。
     * 用于争议点归一化分配与势力归属判定。
     */
    private float influenceValue(int x, int y, int player) {
        float sum = 0;
        for (int yy = 0; yy < BOARD_SIZE; yy++) {
            for (int xx = 0; xx < BOARD_SIZE; xx++) {
                if (board[yy][xx] != player) continue;
                int dx = xx - x;
                int dy = yy - y;
                int d = (int) Math.sqrt(dx * dx + dy * dy);
                float w = 1.0f / (d + 1) + 0.5f / (dx * dx + dy * dy + 1);
                sum += w;
            }
        }
        return sum;
    }

    /**
     * 获取目数详细信息（确定目数 + 势力/死子估算）
     */
    public String getScoreResult() {
        float[] area = computeAreaScore();
        float blackEst = area[0];
        float whiteEst = area[1];

        int blackStones = countBlackStones();
        int whiteStones = countWhiteStones();
        int blackTerritory = countBlackTerritory();
        int whiteTerritory = countWhiteTerritory();
        int blackConfirmed = blackStones + blackTerritory;
        int whiteConfirmed = whiteStones + whiteTerritory;

        StringBuilder sb = new StringBuilder();
        sb.append("【确定目数】\n");
        sb.append("黑：").append(blackStones).append(" 子 + ").append(blackTerritory).append(" 目\n");
        sb.append("白：").append(whiteStones).append(" 子 + ").append(whiteTerritory).append(" 目 + 贴目 ").append(komi).append("\n");
        int cd = blackConfirmed - whiteConfirmed;
        sb.append("→ 黑 ").append(cd >= 0 ? "+" : "").append(cd).append(" 目\n\n");

        sb.append("【含势力 / 死子估算】\n");
        sb.append("黑：约 ").append(String.format("%.1f", blackEst)).append(" 目\n");
        sb.append("白：约 ").append(String.format("%.1f", whiteEst)).append(" 目\n");
        float ed = blackEst - whiteEst;
        if (ed > 0.05f) {
            sb.append("黑棋领先约 ").append(String.format("%.1f", ed)).append(" 目");
        } else if (ed < -0.05f) {
            sb.append("白棋领先约 ").append(String.format("%.1f", -ed)).append(" 目");
        } else {
            sb.append("双方基本持平");
        }
        sb.append("\n\n提示：先「标记死子」可让估算更准");
        return sb.toString();
    }

    // ==================== 死子管理 ====================

    public void addDeadBlackStone(int x, int y) {
        GoBoard.Position pos = new GoBoard.Position(x, y);
        if (board[y][x] == BLACK && !deadBlackStones.contains(pos)) {
            deadBlackStones.add(pos);
            invalidateCache();
        }
    }

    public void addDeadWhiteStone(int x, int y) {
        GoBoard.Position pos = new GoBoard.Position(x, y);
        if (board[y][x] == WHITE && !deadWhiteStones.contains(pos)) {
            deadWhiteStones.add(pos);
            invalidateCache();
        }
    }

    public void removeDeadStone(int x, int y) {
        boolean changed = deadBlackStones.removeIf(p -> p.x == x && p.y == y);
        changed |= deadWhiteStones.removeIf(p -> p.x == x && p.y == y);
        if (changed) {
            invalidateCache();
        }
    }

    public void clearDeadStones() {
        deadBlackStones.clear();
        deadWhiteStones.clear();
        invalidateCache();
    }

    public List<GoBoard.Position> getDeadBlackStones() {
        return new ArrayList<>(deadBlackStones);
    }

    public List<GoBoard.Position> getDeadWhiteStones() {
        return new ArrayList<>(deadWhiteStones);
    }

    /**
     * 基于无气检测死子（用于座子后的自动提子）
     */
    public List<GoBoard.Position> detectDeadStones(int player) {
        if (board == null) return new ArrayList<>();
        List<GoBoard.Position> deadStones = new ArrayList<>();
        boolean[][] visited = new boolean[BOARD_SIZE][BOARD_SIZE];

        for (int y = 0; y < BOARD_SIZE; y++) {
            for (int x = 0; x < BOARD_SIZE; x++) {
                if (board[y][x] == player && !visited[y][x]) {
                    List<GoBoard.Position> group = new ArrayList<>();
                    collectGroup(x, y, player, visited, group);
                    if (!groupHasLiberty(group, player)) {
                        deadStones.addAll(group);
                    }
                }
            }
        }
        return deadStones;
    }

    private boolean groupHasLiberty(List<GoBoard.Position> group, int player) {
        boolean[][] visited = new boolean[BOARD_SIZE][BOARD_SIZE];
        for (GoBoard.Position pos : group) {
            visited[pos.y][pos.x] = true;
        }
        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (GoBoard.Position pos : group) {
            for (int[] dir : directions) {
                int nx = pos.x + dir[0], ny = pos.y + dir[1];
                if (nx >= 0 && nx < BOARD_SIZE && ny >= 0 && ny < BOARD_SIZE) {
                    if (!visited[ny][nx] && board[ny][nx] == EMPTY) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // ==================== 智能死子检测 ====================

    private void ensureEstimationCalculated() {
        ensureTerritoryCalculated();

        if (cachedDeadBlackByEstimator != null) return;

        cachedDeadBlackByEstimator = new ArrayList<>();
        cachedDeadWhiteByEstimator = new ArrayList<>();

        List<GoBoard.Position> allDeadBlack = detectDeadStonesByCapture(BLACK);
        List<GoBoard.Position> allDeadWhite = detectDeadStonesByCapture(WHITE);

        for (GoBoard.Position pos : allDeadBlack) {
            if (!deadBlackStones.contains(pos)) {
                cachedDeadBlackByEstimator.add(pos);
            }
        }
        for (GoBoard.Position pos : allDeadWhite) {
            if (!deadWhiteStones.contains(pos)) {
                cachedDeadWhiteByEstimator.add(pos);
            }
        }

        calculateEstimatedScore();
    }

    private List<GoBoard.Position> detectDeadStonesByCapture(int player) {
        List<GoBoard.Position> deadStones = new ArrayList<>();
        boolean[][] visited = new boolean[BOARD_SIZE][BOARD_SIZE];

        for (int y = 0; y < BOARD_SIZE; y++) {
            for (int x = 0; x < BOARD_SIZE; x++) {
                if (board[y][x] == player && !visited[y][x]) {
                    List<GoBoard.Position> group = new ArrayList<>();
                    collectGroup(x, y, player, visited, group);
                    if (isGroupDead(group, player)) {
                        deadStones.addAll(group);
                    }
                }
            }
        }
        return deadStones;
    }

    private void collectGroup(int startX, int startY, int player, boolean[][] visited,
                              List<GoBoard.Position> group) {
        LinkedList<GoBoard.Position> queue = new LinkedList<>();
        queue.add(new GoBoard.Position(startX, startY));
        visited[startY][startX] = true;
        group.add(new GoBoard.Position(startX, startY));

        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

        while (!queue.isEmpty()) {
            GoBoard.Position pos = queue.poll();
            for (int[] dir : directions) {
                int nx = pos.x + dir[0], ny = pos.y + dir[1];
                if (nx < 0 || nx >= BOARD_SIZE || ny < 0 || ny >= BOARD_SIZE) continue;
                if (visited[ny][nx] || board[ny][nx] != player) continue;
                visited[ny][nx] = true;
                group.add(new GoBoard.Position(nx, ny));
                queue.add(new GoBoard.Position(nx, ny));
            }
        }
    }

    private boolean isGroupDead(List<GoBoard.Position> group, int player) {
        // 有真眼的群视为活棋，避免误杀活形（典型如两眼活、做眼求活）
        if (groupHasEye(group, player)) return false;

        int liberties = countLiberties(group);
        if (liberties == 0) return true;
        if (liberties == 1 && group.size() <= 3) return true;

        int opponent = (player == BLACK) ? WHITE : BLACK;
        int surroundingOpponent = countSurroundingOpponent(group, opponent);
        int surroundingEmpty = countSurroundingEmpty(group);

        if (surroundingEmpty == 0 && surroundingOpponent >= 4) {
            return true;
        }
        if (group.size() <= 4 && liberties <= 2 && surroundingOpponent >= 3) {
            return true;
        }
        if (group.size() <= 6 && liberties == 1) {
            return true;
        }
        return false;
    }

    /** 群内是否含至少一个真眼（四邻无对方子，且至少一对角为己方/墙） */
    private boolean groupHasEye(List<GoBoard.Position> group, int player) {
        Set<GoBoard.Position> set = new HashSet<>(group);
        int[][] dirs = {{0, 1}, {0, -1}, {1, 0}, {-1, 0}};
        int[] ddx = {-1, 1, 1, -1};
        int[] ddy = {-1, -1, 1, 1};
        for (GoBoard.Position pos : group) {
            // 自身四周需无对方子
            boolean eyeLike = true;
            for (int[] d : dirs) {
                int nx = pos.x + d[0], ny = pos.y + d[1];
                if (nx < 0 || nx >= BOARD_SIZE || ny < 0 || ny >= BOARD_SIZE) continue;
                if (board[ny][nx] != player && board[ny][nx] != EMPTY) {
                    eyeLike = false;
                    break;
                }
            }
            if (!eyeLike) continue;
            // 对角至少一个为己方或墙
            boolean okDiag = false;
            for (int i = 0; i < 4; i++) {
                int nx = pos.x + ddx[i], ny = pos.y + ddy[i];
                if (nx < 0 || nx >= BOARD_SIZE || ny < 0 || ny >= BOARD_SIZE) { okDiag = true; break; }
                if (board[ny][nx] == player) { okDiag = true; break; }
            }
            if (okDiag) return true;
        }
        return false;
    }

    private int countLiberties(List<GoBoard.Position> group) {
        Set<GoBoard.Position> liberties = new HashSet<>();
        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

        for (GoBoard.Position pos : group) {
            for (int[] dir : directions) {
                int nx = pos.x + dir[0], ny = pos.y + dir[1];
                if (nx >= 0 && nx < BOARD_SIZE && ny >= 0 && ny < BOARD_SIZE) {
                    if (board[ny][nx] == EMPTY) {
                        liberties.add(new GoBoard.Position(nx, ny));
                    }
                }
            }
        }
        return liberties.size();
    }

    private int countSurroundingOpponent(List<GoBoard.Position> group, int opponent) {
        Set<GoBoard.Position> opponentSet = new HashSet<>();
        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

        for (GoBoard.Position pos : group) {
            for (int[] dir : directions) {
                int nx = pos.x + dir[0], ny = pos.y + dir[1];
                if (nx >= 0 && nx < BOARD_SIZE && ny >= 0 && ny < BOARD_SIZE) {
                    if (board[ny][nx] == opponent) {
                        opponentSet.add(new GoBoard.Position(nx, ny));
                    }
                }
            }
        }
        return opponentSet.size();
    }

    private int countSurroundingEmpty(List<GoBoard.Position> group) {
        Set<GoBoard.Position> emptySet = new HashSet<>();
        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

        for (GoBoard.Position pos : group) {
            for (int[] dir : directions) {
                int nx = pos.x + dir[0], ny = pos.y + dir[1];
                if (nx >= 0 && nx < BOARD_SIZE && ny >= 0 && ny < BOARD_SIZE) {
                    if (board[ny][nx] == EMPTY) {
                        emptySet.add(new GoBoard.Position(nx, ny));
                    }
                }
            }
        }
        return emptySet.size();
    }

    /**
     * 估算目数 = 活子 + 确定死目(小目块) + 势力范围估算目 - 被提死子（+ 贴目给白方）。
     * 把「势力范围」也计入主数字：大块单色空与争议区不再算死定目，而是按平方反比势力估算归属。
     */
    private void calculateEstimatedScore() {
        int blackStones = countBlackStones();
        int whiteStones = countWhiteStones();
        ensureTerritoryCalculated();

        // 确定死目：仅小目块（面积≤阈值）
        int blackTerritory = cachedBlackTerritorySmall.size();
        int whiteTerritory = cachedWhiteTerritorySmall.size();

        // 势力范围估算目：除小目块外的空点按势力强弱归属
        int[] influence = computeInfluenceTerritory();

        int estimatedDeadBlack = cachedDeadBlackByEstimator.size();
        int estimatedDeadWhite = cachedDeadWhiteByEstimator.size();

        cachedInfluenceBlack = influence[0];
        cachedInfluenceWhite = influence[1];
        cachedEstimatedBlackScore = blackStones - estimatedDeadBlack + estimatedDeadWhite
                + blackTerritory + influence[0];
        cachedEstimatedWhiteScore = whiteStones - estimatedDeadWhite + estimatedDeadBlack
                + whiteTerritory + influence[1] + komi;
    }

    /**
     * 统计势力范围估算目：遍历所有空点，排除「小目块」确定围空，其余按平方反比势力归属，
     * 返回 [blackInfluence, whiteInfluence]。中立（争议）点不计入任一方。
     */
    private int[] computeInfluenceTerritory() {
        Set<GoBoard.Position> smallSet = new HashSet<>();
        if (cachedBlackTerritorySmall != null) smallSet.addAll(cachedBlackTerritorySmall);
        if (cachedWhiteTerritorySmall != null) smallSet.addAll(cachedWhiteTerritorySmall);

        int blackInf = 0, whiteInf = 0;
        for (int y = 0; y < BOARD_SIZE; y++) {
            for (int x = 0; x < BOARD_SIZE; x++) {
                if (board[y][x] != EMPTY) continue;
                GoBoard.Position p = new GoBoard.Position(x, y);
                if (smallSet.contains(p)) continue; // 小目块已算确定目
                int owner = potentialOwner(x, y);
                if (owner == BLACK) blackInf++;
                else if (owner == WHITE) whiteInf++;
            }
        }
        return new int[]{blackInf, whiteInf};
    }

    // ==================== 估算结果 API ====================

    public float getEstimatedScoreDifference() {
        ensureEstimationCalculated();
        return cachedEstimatedBlackScore - cachedEstimatedWhiteScore;
    }

    public float getEstimatedBlackScore() {
        ensureEstimationCalculated();
        return cachedEstimatedBlackScore;
    }

    /** 势力范围估算目（黑/白，不含子与死子调整），供弹窗拆分展示 */
    public int getInfluenceBlackPoints() {
        ensureEstimationCalculated();
        return cachedInfluenceBlack;
    }

    public int getInfluenceWhitePoints() {
        ensureEstimationCalculated();
        return cachedInfluenceWhite;
    }

    /** 确定死目（小目块）目数 */
    public int getDeadBlackTerritory() {
        ensureTerritoryCalculated();
        return cachedBlackTerritorySmall.size();
    }

    public int getDeadWhiteTerritory() {
        ensureTerritoryCalculated();
        return cachedWhiteTerritorySmall.size();
    }

    public float getEstimatedWhiteScore() {
        ensureEstimationCalculated();
        return cachedEstimatedWhiteScore;
    }

    public String getEstimatedScoreResult() {
        ensureEstimationCalculated();

        float diff = cachedEstimatedBlackScore - cachedEstimatedWhiteScore;

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("黑 %.1f 目", cachedEstimatedBlackScore));
        sb.append(String.format(" 白 %.1f 目", cachedEstimatedWhiteScore));

        if (diff > 0) {
            sb.append(String.format(" (黑领先 %.1f 目)", diff));
        } else if (diff < 0) {
            sb.append(String.format(" (白领先 %.1f 目)", Math.abs(diff)));
        } else {
            sb.append(" (势均力敌)");
        }

        return sb.toString();
    }

    public List<GoBoard.Position> getEstimatedDeadBlackStones() {
        ensureEstimationCalculated();
        return new ArrayList<>(cachedDeadBlackByEstimator);
    }

    public List<GoBoard.Position> getEstimatedDeadWhiteStones() {
        ensureEstimationCalculated();
        return new ArrayList<>(cachedDeadWhiteByEstimator);
    }

    public float getEstimatedScoreDifferenceWithInfluence() {
        return getEstimatedScoreDifference();
    }

    public String getEstimatedScoreResultWithInfluence() {
        return getEstimatedScoreResult();
    }
}
