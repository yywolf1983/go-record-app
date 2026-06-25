package com.gosgf.app.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

/**
 * 胜负估算器 - 统计棋子、围空、计算胜负
 * 从 GoBoard 中提取，职责单一
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

    // 缓存相关
    private int lastBoardHash = 0;
    private int cachedBlackTerritory = 0;
    private int cachedWhiteTerritory = 0;
    private int cachedBlackPotential = 0;
    private int cachedWhitePotential = 0;
    private List<GoBoard.Position> cachedBlackTerritoryPositions;
    private List<GoBoard.Position> cachedWhiteTerritoryPositions;
    private List<GoBoard.Position> cachedBlackPotentialPositions;
    private List<GoBoard.Position> cachedWhitePotentialPositions;

    // 智能估算相关
    private List<GoBoard.Position> cachedDeadBlackByEstimator;
    private List<GoBoard.Position> cachedDeadWhiteByEstimator;
    private float cachedEstimatedBlackScore = 0;
    private float cachedEstimatedWhiteScore = 0;

    // 势力范围相关（参考GNU Go/Explorer算法）
    private float[][] blackInfluence;
    private float[][] whiteInfluence;
    private List<GoBoard.Position> cachedBlackInfluencePositions;
    private List<GoBoard.Position> cachedWhiteInfluencePositions;
    private float cachedBlackInfluenceValue = 0;
    private float cachedWhiteInfluenceValue = 0;
    
    private static final int MAX_INFLUENCE_DISTANCE = 8;
    private static final float INFLUENCE_DECAY = 0.75f;

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
        cachedBlackPotential = 0;
        cachedWhitePotential = 0;
        cachedBlackTerritoryPositions = null;
        cachedWhiteTerritoryPositions = null;
        cachedBlackPotentialPositions = null;
        cachedWhitePotentialPositions = null;
        cachedDeadBlackByEstimator = null;
        cachedDeadWhiteByEstimator = null;
        cachedEstimatedBlackScore = 0;
        cachedEstimatedWhiteScore = 0;
        blackInfluence = null;
        whiteInfluence = null;
        cachedBlackInfluencePositions = null;
        cachedWhiteInfluencePositions = null;
        cachedBlackInfluenceValue = 0;
        cachedWhiteInfluenceValue = 0;
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

    private void ensureTerritoryCalculated() {
        int currentHash = computeBoardHash();
        if (lastBoardHash == currentHash && cachedBlackTerritoryPositions != null) {
            return;
        }

        lastBoardHash = currentHash;
        cachedBlackTerritoryPositions = new ArrayList<>();
        cachedWhiteTerritoryPositions = new ArrayList<>();
        cachedBlackPotentialPositions = new ArrayList<>();
        cachedWhitePotentialPositions = new ArrayList<>();

        boolean[][] visited = new boolean[BOARD_SIZE][BOARD_SIZE];

        for (int y = 0; y < BOARD_SIZE; y++) {
            for (int x = 0; x < BOARD_SIZE; x++) {
                if (board[y][x] == EMPTY && !visited[y][x]) {
                    List<GoBoard.Position> region = new ArrayList<>();
                    RegionResult result = analyzeRegion(x, y, visited, region);
                    if (result.isCertain) {
                        if (result.owner == BLACK) {
                            cachedBlackTerritoryPositions.addAll(region);
                        } else if (result.owner == WHITE) {
                            cachedWhiteTerritoryPositions.addAll(region);
                        }
                    } else {
                        if (result.owner == BLACK) {
                            cachedBlackPotentialPositions.addAll(region);
                        } else if (result.owner == WHITE) {
                            cachedWhitePotentialPositions.addAll(region);
                        }
                    }
                }
            }
        }

        cachedBlackTerritory = cachedBlackTerritoryPositions.size();
        cachedWhiteTerritory = cachedWhiteTerritoryPositions.size();
        cachedBlackPotential = cachedBlackPotentialPositions.size();
        cachedWhitePotential = cachedWhitePotentialPositions.size();
    }

    private static class RegionResult {
        int owner;
        boolean isCertain;

        RegionResult(int owner, boolean isCertain) {
            this.owner = owner;
            this.isCertain = isCertain;
        }
    }

    private RegionResult analyzeRegion(int startX, int startY, boolean[][] visited, List<GoBoard.Position> region) {
        LinkedList<GoBoard.Position> queue = new LinkedList<>();
        queue.add(new GoBoard.Position(startX, startY));
        visited[startY][startX] = true;
        region.add(new GoBoard.Position(startX, startY));

        int blackNeighbors = 0;
        int whiteNeighbors = 0;
        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

        while (!queue.isEmpty()) {
            GoBoard.Position pos = queue.poll();
            for (int[] dir : directions) {
                int nx = pos.x + dir[0];
                int ny = pos.y + dir[1];
                if (nx >= 0 && nx < BOARD_SIZE && ny >= 0 && ny < BOARD_SIZE) {
                    int stone = board[ny][nx];
                    if (stone == EMPTY && !visited[ny][nx]) {
                        visited[ny][nx] = true;
                        queue.add(new GoBoard.Position(nx, ny));
                        region.add(new GoBoard.Position(nx, ny));
                    } else if (stone == BLACK) {
                        blackNeighbors++;
                    } else if (stone == WHITE) {
                        whiteNeighbors++;
                    }
                }
            }
        }

        if (blackNeighbors > 0 && whiteNeighbors == 0) {
            return new RegionResult(BLACK, true);
        }
        if (whiteNeighbors > 0 && blackNeighbors == 0) {
            return new RegionResult(WHITE, true);
        }

        if (blackNeighbors > 0 || whiteNeighbors > 0) {
            int owner = determineOwnerByProximity(region);
            if (owner != 0) {
                return new RegionResult(owner, false);
            }
        }

        return new RegionResult(0, false);
    }

    private int determineOwnerByProximity(List<GoBoard.Position> region) {
        int blackTotalDist = 0;
        int whiteTotalDist = 0;
        int count = 0;

        for (GoBoard.Position pos : region) {
            int x = pos.x;
            int y = pos.y;
            
            int blackDist = findNearestStone(x, y, BLACK);
            int whiteDist = findNearestStone(x, y, WHITE);

            if (blackDist == Integer.MAX_VALUE || whiteDist == Integer.MAX_VALUE) {
                continue;
            }

            blackTotalDist += blackDist;
            whiteTotalDist += whiteDist;
            count++;
        }

        if (count == 0) return 0;

        float blackAvgDist = (float) blackTotalDist / count;
        float whiteAvgDist = (float) whiteTotalDist / count;

        float distDiff = whiteAvgDist - blackAvgDist;
        
        if (distDiff > 1.0f) return BLACK;
        if (distDiff < -1.0f) return WHITE;

        return 0;
    }

    private int countGroupLiberties(int x, int y, int player, boolean[][] visited) {
        if (x < 0 || x >= BOARD_SIZE || y < 0 || y >= BOARD_SIZE) return 0;
        if (visited[y][x]) return 0;
        
        visited[y][x] = true;
        
        if (board[y][x] == EMPTY) return 1;
        if (board[y][x] != player) return 0;

        int liberties = 0;
        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] dir : directions) {
            liberties += countGroupLiberties(x + dir[0], y + dir[1], player, visited);
        }
        return liberties;
    }

    private void markGroupVisited(int x, int y, int player, boolean[][] visited) {
        if (x < 0 || x >= BOARD_SIZE || y < 0 || y >= BOARD_SIZE) return;
        if (visited[y][x] || board[y][x] != player) return;

        visited[y][x] = true;
        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] dir : directions) {
            markGroupVisited(x + dir[0], y + dir[1], player, visited);
        }
    }

    private int findNearestStone(int x, int y, int player) {
        for (int dist = 1; dist <= 10; dist++) {
            for (int dx = -dist; dx <= dist; dx++) {
                for (int dy = -dist; dy <= dist; dy++) {
                    if (Math.abs(dx) + Math.abs(dy) == dist) {
                        int nx = x + dx;
                        int ny = y + dy;
                        if (nx >= 0 && nx < BOARD_SIZE && ny >= 0 && ny < BOARD_SIZE) {
                            if (board[ny][nx] == player) {
                                return dist;
                            }
                        }
                    }
                }
            }
        }
        return Integer.MAX_VALUE;
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

    public int countBlackPotential() {
        ensureTerritoryCalculated();
        return cachedBlackPotential;
    }

    public int countWhitePotential() {
        ensureTerritoryCalculated();
        return cachedWhitePotential;
    }

    public List<GoBoard.Position> getBlackTerritoryPositions() {
        ensureTerritoryCalculated();
        return new ArrayList<>(cachedBlackTerritoryPositions);
    }

    public List<GoBoard.Position> getWhiteTerritoryPositions() {
        ensureTerritoryCalculated();
        return new ArrayList<>(cachedWhiteTerritoryPositions);
    }

    public List<GoBoard.Position> getBlackPotentialPositions() {
        ensureTerritoryCalculated();
        return new ArrayList<>(cachedBlackPotentialPositions);
    }

    public List<GoBoard.Position> getWhitePotentialPositions() {
        ensureTerritoryCalculated();
        return new ArrayList<>(cachedWhitePotentialPositions);
    }

    /**
     * 获取空区域的归属（BFS填充）
     * @return BLACK, WHITE, 或 0（双方共有）
     */
    private int getRegionOwner(int startX, int startY, boolean[][] visited, List<GoBoard.Position> region) {
        LinkedList<GoBoard.Position> queue = new LinkedList<>();
        queue.add(new GoBoard.Position(startX, startY));
        visited[startY][startX] = true;
        region.add(new GoBoard.Position(startX, startY));

        int blackBorder = 0;
        int whiteBorder = 0;
        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

        while (!queue.isEmpty()) {
            GoBoard.Position pos = queue.poll();
            for (int[] dir : directions) {
                int nx = pos.x + dir[0];
                int ny = pos.y + dir[1];
                if (nx >= 0 && nx < BOARD_SIZE && ny >= 0 && ny < BOARD_SIZE) {
                    int stone = board[ny][nx];
                    if (stone == EMPTY && !visited[ny][nx]) {
                        visited[ny][nx] = true;
                        queue.add(new GoBoard.Position(nx, ny));
                        region.add(new GoBoard.Position(nx, ny));
                    } else if (stone == BLACK) {
                        blackBorder++;
                    } else if (stone == WHITE) {
                        whiteBorder++;
                    }
                }
            }
        }

        if (blackBorder > 0 && whiteBorder == 0) return BLACK;
        if (whiteBorder > 0 && blackBorder == 0) return WHITE;
        return 0;
    }

    // ==================== 胜负计算 ====================

    /**
     * @return 负数表示黑棋领先，正数表示白棋领先
     */
    public float calculateScore() {
        if (board == null) return 0;
        int blackTotal = countBlackStones() + countBlackTerritory();
        int whiteTotal = countWhiteStones() + countWhiteTerritory();
        return blackTotal - (whiteTotal + komi);
    }

    /**
     * 获取胜负估算结果字符串
     */
    public String getScoreResult() {
        ensureEstimationCalculated();

        float diff = cachedEstimatedBlackScore - cachedEstimatedWhiteScore;

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("黑 %.1f 目", cachedEstimatedBlackScore));
        sb.append(String.format(" 白 %.1f 目", cachedEstimatedWhiteScore));
        sb.append("\n");

        int blackStones = countBlackStones();
        int whiteStones = countWhiteStones();
        int blackTerritory = countBlackTerritory();
        int whiteTerritory = countWhiteTerritory();
        float blackInfluence = getBlackInfluenceValue();
        float whiteInfluence = getWhiteInfluenceValue();

        sb.append(String.format("棋子: 黑%d 白%d\n", blackStones, whiteStones));
        sb.append(String.format("围空: 黑%d 白%d\n", blackTerritory, whiteTerritory));
        sb.append(String.format("势力: 黑%.1f 白%.1f\n", blackInfluence, whiteInfluence));
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
     * 自动检测死子（基于无气）
     */
    public List<GoBoard.Position> detectDeadStones(int player) {
        if (board == null) return new ArrayList<>();
        List<GoBoard.Position> deadStones = new ArrayList<>();
        boolean[][] visited = new boolean[BOARD_SIZE][BOARD_SIZE];

        for (int y = 0; y < BOARD_SIZE; y++) {
            for (int x = 0; x < BOARD_SIZE; x++) {
                if (board[y][x] == player && !visited[y][x]) {
                    if (!hasLiberty(x, y, player, visited)) {
                        deadStones.add(new GoBoard.Position(x, y));
                    }
                }
            }
        }
        return deadStones;
    }

    private boolean hasLiberty(int startX, int startY, int player, boolean[][] visited) {
        LinkedList<int[]> queue = new LinkedList<>();
        queue.add(new int[]{startX, startY});
        visited[startY][startX] = true;
        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

        while (!queue.isEmpty()) {
            int[] pos = queue.poll();
            for (int[] dir : directions) {
                int nx = pos[0] + dir[0], ny = pos[1] + dir[1];
                if (nx < 0 || nx >= BOARD_SIZE || ny < 0 || ny >= BOARD_SIZE) continue;
                if (visited[ny][nx]) continue;
                visited[ny][nx] = true;
                if (board[ny][nx] == EMPTY) return true;
                if (board[ny][nx] == player) queue.add(new int[]{nx, ny});
            }
        }
        return false;
    }

    // ==================== 智能估算（类似野狐）====================

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
        if (group.size() <= 1) {
            return isSingleStoneDead(group.get(0), player);
        }

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

    private boolean isSingleStoneDead(GoBoard.Position pos, int player) {
        int opponent = (player == BLACK) ? WHITE : BLACK;
        int emptyCount = 0;
        int opponentCount = 0;

        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] dir : directions) {
            int nx = pos.x + dir[0], ny = pos.y + dir[1];
            if (nx < 0 || nx >= BOARD_SIZE || ny < 0 || ny >= BOARD_SIZE) continue;
            if (board[ny][nx] == EMPTY) emptyCount++;
            else if (board[ny][nx] == opponent) opponentCount++;
        }

        return emptyCount == 0 || (emptyCount == 1 && opponentCount >= 3);
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

    private void calculateEstimatedScore() {
        ensureInfluenceCalculated();

        int blackStones = countBlackStones();
        int whiteStones = countWhiteStones();

        int blackTerritory = countBlackTerritory();
        int whiteTerritory = countWhiteTerritory();

        int estimatedDeadBlack = cachedDeadBlackByEstimator.size();
        int estimatedDeadWhite = cachedDeadWhiteByEstimator.size();

        float blackEffectiveStones = blackStones - estimatedDeadBlack + estimatedDeadWhite;
        float whiteEffectiveStones = whiteStones - estimatedDeadWhite + estimatedDeadBlack;

        float blackInfluenceScore = cachedBlackInfluenceValue * 0.8f;
        float whiteInfluenceScore = cachedWhiteInfluenceValue * 0.8f;

        cachedEstimatedBlackScore = blackEffectiveStones + blackTerritory + blackInfluenceScore;
        cachedEstimatedWhiteScore = whiteEffectiveStones + whiteTerritory + whiteInfluenceScore + komi;
    }

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

    // ==================== 势力范围计算（类似野狐）====================

    private void ensureInfluenceCalculated() {
        if (blackInfluence != null) return;

        blackInfluence = new float[BOARD_SIZE][BOARD_SIZE];
        whiteInfluence = new float[BOARD_SIZE][BOARD_SIZE];

        for (int y = 0; y < BOARD_SIZE; y++) {
            for (int x = 0; x < BOARD_SIZE; x++) {
                if (board[y][x] == EMPTY) {
                    float[] scores = calculateInfluenceScore(x, y);
                    blackInfluence[y][x] = scores[0];
                    whiteInfluence[y][x] = scores[1];
                }
            }
        }

        updateInfluencePositions();
        calculateInfluenceValues();
    }

    private float[] calculateInfluenceScore(int x, int y) {
        float blackScore = 0;
        float whiteScore = 0;

        for (int dy = -6; dy <= 6; dy++) {
            for (int dx = -6; dx <= 6; dx++) {
                int nx = x + dx;
                int ny = y + dy;
                if (nx < 0 || nx >= BOARD_SIZE || ny < 0 || ny >= BOARD_SIZE) continue;
                
                if (board[ny][nx] != EMPTY) {
                    int dist = Math.abs(dx) + Math.abs(dy);
                    if (dist == 0) continue;
                    if (dist > 6) continue;

                    float influence = getInfluenceAtDistance(dist, nx, ny);

                    if (board[ny][nx] == BLACK) {
                        blackScore += influence;
                    } else {
                        whiteScore += influence;
                    }
                }
            }
        }

        return new float[]{blackScore, whiteScore};
    }

    private float getInfluenceAtDistance(int dist, int stoneX, int stoneY) {
        float distanceFactor;
        switch (dist) {
            case 1: distanceFactor = 1.0f; break;
            case 2: distanceFactor = 0.8f; break;
            case 3: distanceFactor = 0.6f; break;
            case 4: distanceFactor = 0.4f; break;
            case 5: distanceFactor = 0.25f; break;
            case 6: distanceFactor = 0.12f; break;
            default: distanceFactor = 0.0f;
        }

        float edgeFactor = getEdgeFactor(stoneX, stoneY);
        return distanceFactor * edgeFactor;
    }

    private float getEdgeFactor(int x, int y) {
        int distToEdge = Math.min(x, BOARD_SIZE - 1 - x);
        distToEdge = Math.min(distToEdge, Math.min(y, BOARD_SIZE - 1 - y));

        if (distToEdge == 0) return 1.6f;
        if (distToEdge == 1) return 1.4f;
        if (distToEdge == 2) return 1.2f;
        return 1.0f;
    }

    private void updateInfluencePositions() {
        cachedBlackInfluencePositions = new ArrayList<>();
        cachedWhiteInfluencePositions = new ArrayList<>();

        for (int y = 0; y < BOARD_SIZE; y++) {
            for (int x = 0; x < BOARD_SIZE; x++) {
                if (board[y][x] == EMPTY) {
                    float black = blackInfluence[y][x];
                    float white = whiteInfluence[y][x];

                    if (black > 0.05f && black > white * 1.15f) {
                        cachedBlackInfluencePositions.add(new GoBoard.Position(x, y));
                    } else if (white > 0.05f && white > black * 1.15f) {
                        cachedWhiteInfluencePositions.add(new GoBoard.Position(x, y));
                    }
                }
            }
        }
    }

    private void calculateInfluenceValues() {
        cachedBlackInfluenceValue = 0;
        cachedWhiteInfluenceValue = 0;

        for (int y = 0; y < BOARD_SIZE; y++) {
            for (int x = 0; x < BOARD_SIZE; x++) {
                if (board[y][x] == EMPTY) {
                    float black = blackInfluence[y][x];
                    float white = whiteInfluence[y][x];

                    if (black > 0.05f) {
                        float dominance = black / (black + white + 0.0001f);
                        if (dominance > 0.5f) {
                            cachedBlackInfluenceValue += (dominance - 0.5f) * 2 * getPositionValue(x, y);
                        }
                    }
                    if (white > 0.05f) {
                        float dominance = white / (black + white + 0.0001f);
                        if (dominance > 0.5f) {
                            cachedWhiteInfluenceValue += (dominance - 0.5f) * 2 * getPositionValue(x, y);
                        }
                    }
                }
            }
        }
    }

    private float getPositionValue(int x, int y) {
        int edgeDist = Math.min(x, BOARD_SIZE - 1 - x);
        edgeDist = Math.min(edgeDist, Math.min(y, BOARD_SIZE - 1 - y));

        if (edgeDist == 0) return 1.5f;
        if (edgeDist == 1) return 1.3f;
        if (edgeDist == 2) return 1.2f;
        if (edgeDist <= 4) return 1.1f;
        return 1.0f;
    }

    public float getBlackInfluenceValue() {
        ensureInfluenceCalculated();
        return cachedBlackInfluenceValue;
    }

    public float getWhiteInfluenceValue() {
        ensureInfluenceCalculated();
        return cachedWhiteInfluenceValue;
    }

    public List<GoBoard.Position> getBlackInfluencePositions() {
        ensureInfluenceCalculated();
        return new ArrayList<>(cachedBlackInfluencePositions);
    }

    public List<GoBoard.Position> getWhiteInfluencePositions() {
        ensureInfluenceCalculated();
        return new ArrayList<>(cachedWhiteInfluencePositions);
    }

    public float getInfluenceAt(int x, int y) {
        ensureInfluenceCalculated();
        if (x < 0 || x >= BOARD_SIZE || y < 0 || y >= BOARD_SIZE) return 0;
        return blackInfluence[y][x] - whiteInfluence[y][x];
    }

    public String debugInfluenceInfo() {
        ensureInfluenceCalculated();
        StringBuilder sb = new StringBuilder();
        
        int blackInfluenceCount = cachedBlackInfluencePositions.size();
        int whiteInfluenceCount = cachedWhiteInfluencePositions.size();
        
        sb.append("势力范围统计:\n");
        sb.append("黑方势力点数: ").append(blackInfluenceCount).append("\n");
        sb.append("白方势力点数: ").append(whiteInfluenceCount).append("\n");
        sb.append("黑方势力价值: ").append(String.format("%.2f", cachedBlackInfluenceValue)).append("\n");
        sb.append("白方势力价值: ").append(String.format("%.2f", cachedWhiteInfluenceValue)).append("\n");
        
        sb.append("\n示例位置影响力:\n");
        int[][] examples = {{3, 3}, {9, 9}, {15, 3}};
        for (int[] pos : examples) {
            int x = pos[0], y = pos[1];
            if (board[y][x] == EMPTY) {
                sb.append(String.format("(%d,%d): 黑=%.3f 白=%.3f\n", 
                    x, y, blackInfluence[y][x], whiteInfluence[y][x]));
            }
        }
        
        return sb.toString();
    }

    // ==================== 综合估算（整合势力范围）====================

    private void calculateEstimatedScoreWithInfluence() {
        ensureInfluenceCalculated();

        int blackStones = countBlackStones();
        int whiteStones = countWhiteStones();

        int blackTerritory = countBlackTerritory();
        int whiteTerritory = countWhiteTerritory();

        int estimatedDeadBlack = cachedDeadBlackByEstimator != null ? cachedDeadBlackByEstimator.size() : 0;
        int estimatedDeadWhite = cachedDeadWhiteByEstimator != null ? cachedDeadWhiteByEstimator.size() : 0;

        float blackEffectiveStones = blackStones - estimatedDeadBlack + estimatedDeadWhite;
        float whiteEffectiveStones = whiteStones - estimatedDeadWhite + estimatedDeadBlack;

        float blackInfluenceScore = cachedBlackInfluenceValue * 0.7f;
        float whiteInfluenceScore = cachedWhiteInfluenceValue * 0.7f;

        cachedEstimatedBlackScore = blackEffectiveStones + blackTerritory + blackInfluenceScore;
        cachedEstimatedWhiteScore = whiteEffectiveStones + whiteTerritory + whiteInfluenceScore + komi;
    }

    public float getEstimatedScoreDifferenceWithInfluence() {
        ensureEstimationCalculated();
        calculateEstimatedScoreWithInfluence();
        return cachedEstimatedBlackScore - cachedEstimatedWhiteScore;
    }

    public String getEstimatedScoreResultWithInfluence() {
        ensureEstimationCalculated();
        calculateEstimatedScoreWithInfluence();

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
}
