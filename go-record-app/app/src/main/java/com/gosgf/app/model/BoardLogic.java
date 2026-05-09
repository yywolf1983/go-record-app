package com.gosgf.app.model;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * 棋盘核心算法：气、提子、落子重建
 * 从 GoBoard.java 拆分，负责纯棋盘状态计算逻辑
 */
public class BoardLogic {

    public static final int BOARD_SIZE = 19;
    public static final int EMPTY = 0;
    public static final int BLACK = 1;
    public static final int WHITE = 2;

    /**
     * 检查棋子是否有气（BFS）
     */
    public static boolean hasLiberty(int[][] board, int x, int y, int player) {
        boolean[][] visited = new boolean[BOARD_SIZE][BOARD_SIZE];
        return checkLiberty(board, x, y, player, visited);
    }

    private static boolean checkLiberty(int[][] board, int startX, int startY, int player, boolean[][] visited) {
        LinkedList<int[]> queue = new LinkedList<>();
        queue.add(new int[]{startX, startY});
        visited[startY][startX] = true;

        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

        while (!queue.isEmpty()) {
            int[] pos = queue.poll();
            int x = pos[0], y = pos[1];

            for (int[] dir : directions) {
                int nx = x + dir[0], ny = y + dir[1];
                if (nx < 0 || nx >= BOARD_SIZE || ny < 0 || ny >= BOARD_SIZE) continue;
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
        int opponent = currentPlayer == BLACK ? WHITE : BLACK;

        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] dir : directions) {
            int nx = x + dir[0];
            int ny = y + dir[1];

            if (nx >= 0 && nx < BOARD_SIZE && ny >= 0 && ny < BOARD_SIZE &&
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
        boolean[][] visited = new boolean[BOARD_SIZE][BOARD_SIZE];
        collectGroup(board, x, y, player, group, visited);
        return group;
    }

    private static void collectGroup(int[][] board, int startX, int startY, int player,
                                      List<GoBoard.Position> group, boolean[][] visited) {
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
                if (nx < 0 || nx >= BOARD_SIZE || ny < 0 || ny >= BOARD_SIZE) continue;
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
        int[][] copy = new int[BOARD_SIZE][BOARD_SIZE];
        for (int y = 0; y < BOARD_SIZE; y++) {
            System.arraycopy(original[y], 0, copy[y], 0, BOARD_SIZE);
        }
        return copy;
    }

    /**
     * 清空棋盘
     */
    public static void resetBoard(int[][] board) {
        for (int y = 0; y < BOARD_SIZE; y++) {
            for (int x = 0; x < BOARD_SIZE; x++) {
                board[y][x] = EMPTY;
            }
        }
    }
}
