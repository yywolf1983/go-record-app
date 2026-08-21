package com.gosgf.app.model;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * 棋盘核心算法：气、提子、落子重建
 * 从 GoBoard.java 拆分，负责纯棋盘状态计算逻辑。
 * 所有逻辑从 board.length 取实际棋盘大小，支持任意 N 路棋盘。
 */
public class BoardLogic {

    public static final int EMPTY = 0;
    public static final int BLACK = 1;
    public static final int WHITE = 2;

    private static int size(int[][] board) {
        return (board != null) ? board.length : 0;
    }

    /**
     * 检查棋子是否有气（BFS）
     */
    public static boolean hasLiberty(int[][] board, int x, int y, int player) {
        int n = size(board);
        if (n <= 0) return false;
        boolean[][] visited = new boolean[n][n];
        return checkLiberty(board, x, y, player, visited, n);
    }

    private static boolean checkLiberty(int[][] board, int startX, int startY, int player,
                                        boolean[][] visited, int n) {
        if (startX < 0 || startX >= n || startY < 0 || startY >= n) return false;
        LinkedList<int[]> queue = new LinkedList<>();
        queue.add(new int[]{startX, startY});
        visited[startY][startX] = true;

        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

        while (!queue.isEmpty()) {
            int[] pos = queue.poll();
            int x = pos[0], y = pos[1];

            for (int[] dir : directions) {
                int nx = x + dir[0], ny = y + dir[1];
                if (nx < 0 || nx >= n || ny < 0 || ny >= n) continue;
                if (visited[ny][nx]) continue;

                visited[ny][nx] = true;

                if (board[ny][nx] == EMPTY) {
                    return true; // 找到气
                }
                if (board[ny][nx] == player) {
                    queue.add(new int[]{nx, ny});
                }
            }
        }
        return false; // 无气
    }

    /**
     * 提掉被包围的对手棋子
     * @return 被提的棋子位置列表
     */
    public static List<GoBoard.Position> captureStones(int[][] board, int x, int y, int currentPlayer) {
        List<GoBoard.Position> capturedStones = new ArrayList<>();
        int n = size(board);
        if (n <= 0) return capturedStones;
        int opponent = currentPlayer == BLACK ? WHITE : BLACK;

        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] dir : directions) {
            int nx = x + dir[0];
            int ny = y + dir[1];

            if (nx >= 0 && nx < n && ny >= 0 && ny < n &&
                board[ny][nx] == opponent) {

                List<GoBoard.Position> group = getGroup(board, nx, ny, opponent);
                if (!hasLiberty(board, nx, ny, opponent)) {
                    for (GoBoard.Position pos : group) {
                        board[pos.y][pos.x] = EMPTY;
                        capturedStones.add(pos);
                    }
                }
            }
        }

        return capturedStones;
    }

    /**
     * 获取指定位置棋子所在的连通块
     */
    public static List<GoBoard.Position> getGroup(int[][] board, int x, int y, int player) {
        List<GoBoard.Position> group = new ArrayList<>();
        int n = size(board);
        if (n <= 0 || x < 0 || x >= n || y < 0 || y >= n) return group;
        boolean[][] visited = new boolean[n][n];
        collectGroup(board, x, y, player, group, visited, n);
        return group;
    }

    private static void collectGroup(int[][] board, int startX, int startY, int player,
                                      List<GoBoard.Position> group, boolean[][] visited, int n) {
        LinkedList<int[]> queue = new LinkedList<>();
        queue.add(new int[]{startX, startY});
        visited[startY][startX] = true;
        group.add(new GoBoard.Position(startX, startY));

        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

        while (!queue.isEmpty()) {
            int[] pos = queue.poll();
            int x = pos[0], y = pos[1];

            for (int[] dir : directions) {
                int nx = x + dir[0], ny = y + dir[1];
                if (nx < 0 || nx >= n || ny < 0 || ny >= n) continue;
                if (visited[ny][nx] || board[ny][nx] != player) continue;

                visited[ny][nx] = true;
                group.add(new GoBoard.Position(nx, ny));
                queue.add(new int[]{nx, ny});
            }
        }
    }

    /**
     * 复制棋盘
     */
    public static int[][] copyBoard(int[][] original) {
        int n = size(original);
        if (n <= 0) return new int[0][0];
        int[][] copy = new int[n][n];
        for (int y = 0; y < n; y++) {
            int rowLen = Math.min(original[y].length, n);
            System.arraycopy(original[y], 0, copy[y], 0, rowLen);
        }
        return copy;
    }

    /**
     * 清空棋盘
     */
    public static void resetBoard(int[][] board) {
        int n = size(board);
        for (int y = 0; y < n; y++) {
            int rowLen = Math.min(board[y].length, n);
            for (int x = 0; x < rowLen; x++) {
                board[y][x] = EMPTY;
            }
        }
    }

    /**
     * 移除盘面上所有无气的死子（摆子完成后调用）
     * @return 被移除的棋子数量
     */
    public static int removeAllDeadStones(int[][] board) {
        int n = size(board);
        if (n <= 0) return 0;
        boolean[][] visited = new boolean[n][n];
        int removed = 0;

        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                if (board[y][x] != EMPTY && !visited[y][x]) {
                    int player = board[y][x];
                    List<GoBoard.Position> group = getGroup(board, x, y, player);
                    for (GoBoard.Position p : group) {
                        if (p.y >= 0 && p.y < n && p.x >= 0 && p.x < n)
                            visited[p.y][p.x] = true;
                    }
                    if (!hasLiberty(board, x, y, player)) {
                        for (GoBoard.Position p : group) {
                            if (p.y >= 0 && p.y < n && p.x >= 0 && p.x < n) {
                                board[p.y][p.x] = EMPTY;
                                removed++;
                            }
                        }
                    }
                }
            }
        }
        return removed;
    }
}
