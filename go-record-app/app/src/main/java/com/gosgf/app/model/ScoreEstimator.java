package com.gosgf.app.model;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

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

    private final int[][] board;
    private final List<GoBoard.Position> deadBlackStones = new ArrayList<>();
    private final List<GoBoard.Position> deadWhiteStones = new ArrayList<>();
    private float komi = DEFAULT_KOMI;

    public ScoreEstimator(int[][] board) {
        this.board = board;
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
        return countTerritory(BLACK);
    }

    public int countWhiteTerritory() {
        return countTerritory(WHITE);
    }

    private int countTerritory(int player) {
        if (board == null) return 0;
        int territory = 0;
        boolean[][] visited = new boolean[BOARD_SIZE][BOARD_SIZE];

        for (int y = 0; y < BOARD_SIZE; y++) {
            for (int x = 0; x < BOARD_SIZE; x++) {
                if (board[y][x] == EMPTY && !visited[y][x]) {
                    List<GoBoard.Position> region = new ArrayList<>();
                    int owner = getRegionOwner(x, y, visited, region);
                    if (owner == player) {
                        territory += region.size();
                    }
                }
            }
        }
        return territory;
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
        if (board == null) return "棋盘未初始化";

        int blackStones = countBlackStones();
        int blackTerritory = countBlackTerritory();
        int blackTotal = blackStones + blackTerritory;

        int whiteStones = countWhiteStones();
        int whiteTerritory = countWhiteTerritory();
        int whiteTotal = whiteStones + whiteTerritory;

        float diff = blackTotal - (whiteTotal + komi);

        StringBuilder sb = new StringBuilder();
        sb.append("黑棋：").append(blackTotal)
          .append("子（棋子").append(blackStones)
          .append(" + 围空").append(blackTerritory).append(")\n");
        sb.append("白棋：").append(whiteTotal)
          .append("子（棋子").append(whiteStones)
          .append(" + 围空").append(whiteTerritory).append("）\n");
        sb.append("贴目：").append(komi).append("\n");

        if (diff > 0) {
            sb.append("黑棋领先 ").append(String.format("%.1f", diff)).append(" 子");
        } else if (diff < 0) {
            sb.append("白棋领先 ").append(String.format("%.1f", Math.abs(diff))).append(" 子");
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
        }
    }

    public void addDeadWhiteStone(int x, int y) {
        GoBoard.Position pos = new GoBoard.Position(x, y);
        if (board[y][x] == WHITE && !deadWhiteStones.contains(pos)) {
            deadWhiteStones.add(pos);
        }
    }

    public void removeDeadStone(int x, int y) {
        deadBlackStones.removeIf(p -> p.x == x && p.y == y);
        deadWhiteStones.removeIf(p -> p.x == x && p.y == y);
    }

    public void clearDeadStones() {
        deadBlackStones.clear();
        deadWhiteStones.clear();
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
}
