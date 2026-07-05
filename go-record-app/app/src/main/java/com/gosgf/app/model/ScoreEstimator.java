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

    // 势力检测最大距离（比之前的 6 小，避免过大）
    private static final int INFLUENCE_MAX_DIST = 3;

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

    // ==================== 势力范围缓存 ====================

    private List<GoBoard.Position> cachedBlackPotentialPositions;
    private List<GoBoard.Position> cachedWhitePotentialPositions;

    // ==================== 死子估算缓存 ====================

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

    private void ensureTerritoryCalculated() {
        int currentHash = computeBoardHash();
        if (lastBoardHash == currentHash && cachedBlackTerritoryPositions != null) {
            return;
        }

        lastBoardHash = currentHash;
        cachedBlackTerritoryPositions = new ArrayList<>();
        cachedWhiteTerritoryPositions = new ArrayList<>();

        boolean[][] visited = new boolean[BOARD_SIZE][BOARD_SIZE];

        for (int y = 0; y < BOARD_SIZE; y++) {
            for (int x = 0; x < BOARD_SIZE; x++) {
                if (board[y][x] == EMPTY && !visited[y][x]) {
                    List<GoBoard.Position> region = new ArrayList<>();
                    int owner = floodFillEmptyRegion(x, y, visited, region);

                    if (owner == BLACK) {
                        cachedBlackTerritoryPositions.addAll(region);
                    } else if (owner == WHITE) {
                        cachedWhiteTerritoryPositions.addAll(region);
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

    // ==================== 势力范围（近距离检测，距离 ≤ INFLUENCE_MAX_DIST） ====================

    private void ensureInfluenceCalculated() {
        // 复用围空缓存的哈希
        int currentHash = computeBoardHash();
        if (lastBoardHash == currentHash && cachedBlackPotentialPositions != null) {
            return;
        }

        lastBoardHash = currentHash;
        cachedBlackPotentialPositions = new ArrayList<>();
        cachedWhitePotentialPositions = new ArrayList<>();

        // 确保围空也算过了（势力要排除已确认围空的点）
        if (cachedBlackTerritoryPositions == null) {
            ensureTerritoryCalculated();
        }

        // 构建围空点集合用于快速排除
        Set<GoBoard.Position> territorySet = new HashSet<>();
        territorySet.addAll(cachedBlackTerritoryPositions);
        territorySet.addAll(cachedWhiteTerritoryPositions);

        for (int y = 0; y < BOARD_SIZE; y++) {
            for (int x = 0; x < BOARD_SIZE; x++) {
                if (board[y][x] != EMPTY) continue;

                GoBoard.Position p = new GoBoard.Position(x, y);
                // 已经是确认围空 → 跳过
                if (territorySet.contains(p)) continue;

                int owner = proximityOwner(x, y);
                if (owner == BLACK) {
                    cachedBlackPotentialPositions.add(p);
                } else if (owner == WHITE) {
                    cachedWhitePotentialPositions.add(p);
                }
            }
        }
    }

    /**
     * 对空交叉点 (x,y) 做 BFS，找出距离 ≤ INFLUENCE_MAX_DIST 内最近的棋子颜色。
     * 如果在范围内只有一种颜色的棋子 → 返回该颜色，否则返回 0（中立）。
     */
    private int proximityOwner(int startX, int startY) {
        boolean[][] visited = new boolean[BOARD_SIZE][BOARD_SIZE];
        LinkedList<int[]> queue = new LinkedList<>();  // [x, y, dist]
        queue.add(new int[]{startX, startY, 0});
        visited[startY][startX] = true;

        boolean foundBlack = false;
        boolean foundWhite = false;

        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

        while (!queue.isEmpty()) {
            int[] cur = queue.poll();
            int dist = cur[2];

            // 超过最大距离 → 只在此层内寻找
            if (dist >= INFLUENCE_MAX_DIST) continue;

            for (int[] dir : directions) {
                int nx = cur[0] + dir[0];
                int ny = cur[1] + dir[1];
                if (nx < 0 || nx >= BOARD_SIZE || ny < 0 || ny >= BOARD_SIZE) continue;
                if (visited[ny][nx]) continue;

                if (board[ny][nx] == EMPTY) {
                    visited[ny][nx] = true;
                    queue.add(new int[]{nx, ny, dist + 1});
                } else if (board[ny][nx] == BLACK) {
                    visited[ny][nx] = true;
                    foundBlack = true;
                } else if (board[ny][nx] == WHITE) {
                    visited[ny][nx] = true;
                    foundWhite = true;
                }
            }
        }

        // 在范围内只接触一种颜色 → 该方势力
        if (foundBlack && !foundWhite) return BLACK;
        if (foundWhite && !foundBlack) return WHITE;
        return 0;
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

    // ==================== 势力范围（近距离检测） ====================

    public List<GoBoard.Position> getBlackPotentialPositions() {
        ensureInfluenceCalculated();
        return new ArrayList<>(cachedBlackPotentialPositions);
    }

    public List<GoBoard.Position> getWhitePotentialPositions() {
        ensureInfluenceCalculated();
        return new ArrayList<>(cachedWhitePotentialPositions);
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
     * 获取目数详细信息
     */
    public String getScoreResult() {
        ensureEstimationCalculated();

        int blackStones = countBlackStones();
        int whiteStones = countWhiteStones();
        int blackTerritory = countBlackTerritory();
        int whiteTerritory = countWhiteTerritory();
        float blackTotal = blackStones + blackTerritory;
        float whiteTotal = whiteStones + whiteTerritory + komi;
        float diff = blackTotal - whiteTotal;

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("黑 %.1f 目", blackTotal));
        sb.append(String.format(" 白 %.1f 目", whiteTotal));
        sb.append("\n");
        sb.append(String.format("棋子: 黑%d 白%d\n", blackStones, whiteStones));
        sb.append(String.format("围空: 黑%d 白%d\n", blackTerritory, whiteTerritory));
        sb.append("贴目: ").append(komi).append("\n");

        if (diff > 0) {
            sb.append(String.format("黑棋领先 %.1f 目", diff));
        } else if (diff < 0) {
            sb.append(String.format("白棋领先 %.1f 目", Math.abs(diff)));
        } else {
            sb.append("局势持平");
        }
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
     * 估算目数 = 活子 + 围空 - 被提死子（+ 贴目给白方）
     */
    private void calculateEstimatedScore() {
        int blackStones = countBlackStones();
        int whiteStones = countWhiteStones();

        int blackTerritory = countBlackTerritory();
        int whiteTerritory = countWhiteTerritory();

        int estimatedDeadBlack = cachedDeadBlackByEstimator.size();
        int estimatedDeadWhite = cachedDeadWhiteByEstimator.size();

        cachedEstimatedBlackScore = blackStones - estimatedDeadBlack + estimatedDeadWhite + blackTerritory;
        cachedEstimatedWhiteScore = whiteStones - estimatedDeadWhite + estimatedDeadBlack + whiteTerritory + komi;
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
