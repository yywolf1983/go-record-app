package com.gosgf.app.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 让子管理：让子设置、位置计算、座子增删
 * 从 GoBoard.java 拆分
 */
public class HandicapManager {

    private static final int MIN_HANDICAP = 1;
    private static final int MAX_HANDICAP = 9;

    // 标准星位布局，按下标=让子数（1~9）存放坐标 [x, y]
    private static final int[][][] HANDICAP_LAYOUT = {
            null,                                                       // 0（占位，不使用）
            {{9, 9}},                                                  // 1 子：天元
            {{3, 3}, {15, 15}},                                        // 2 子
            {{3, 3}, {15, 15}, {3, 15}},                               // 3 子
            {{3, 3}, {15, 15}, {3, 15}, {15, 3}},                      // 4 子
            {{3, 3}, {15, 15}, {3, 15}, {15, 3}, {9, 9}},              // 5 子
            {{3, 3}, {15, 15}, {3, 15}, {15, 3}, {3, 9}, {15, 9}},    // 6 子
            {{3, 3}, {15, 15}, {3, 15}, {15, 3}, {3, 9}, {15, 9}, {9, 9}}, // 7 子
            {{3, 3}, {15, 15}, {3, 15}, {15, 3}, {3, 9}, {15, 9}, {9, 3}, {9, 15}}, // 8 子
            {{3, 3}, {15, 15}, {3, 15}, {15, 3}, {3, 9}, {15, 9}, {9, 3}, {9, 15}, {9, 9}} // 9 子
    };

    private int[][] board;
    private int handicap;
    private final List<GoBoard.Position> blackHandicapStones = new ArrayList<>();
    private final List<GoBoard.Position> whiteHandicapStones = new ArrayList<>();

    public HandicapManager(int[][] board) {
        this.board = board;
        this.handicap = 0;
    }

    /**
     * 设置让子数并放置座子
     */
    public void setupHandicap(int handicapCount) {
        if (handicapCount < MIN_HANDICAP || handicapCount > MAX_HANDICAP) {
            return;
        }
        handicap = handicapCount;
        clearHandicapStones();
        List<GoBoard.Position> positions = getHandicapPositions(handicapCount);
        for (GoBoard.Position pos : positions) {
            addBlackHandicapStone(pos.x, pos.y);
        }
    }

    /**
     * 让子棋位置计算（星位点），由静态布局表查表得出
     */
    public static List<GoBoard.Position> getHandicapPositions(int handicapCount) {
        List<GoBoard.Position> positions = new ArrayList<>();
        if (handicapCount >= MIN_HANDICAP && handicapCount <= MAX_HANDICAP) {
            for (int[] p : HANDICAP_LAYOUT[handicapCount]) {
                positions.add(new GoBoard.Position(p[0], p[1]));
            }
        }
        return positions;
    }

    /**
     * 将所有让子座子应用到棋盘
     */
    public void applyHandicapStones() {
        for (GoBoard.Position pos : blackHandicapStones) {
            board[pos.y][pos.x] = GoBoard.BLACK;
        }
        for (GoBoard.Position pos : whiteHandicapStones) {
            board[pos.y][pos.x] = GoBoard.WHITE;
        }
    }

    public void addBlackHandicapStone(int x, int y) {
        addHandicapStone(x, y, GoBoard.BLACK);
    }

    public void addWhiteHandicapStone(int x, int y) {
        addHandicapStone(x, y, GoBoard.WHITE);
    }

    /**
     * 通用：在 (x,y) 放置一颗座子。会先清理该处已有的任意座子（避免
     * syncFromBoard 造成的重复），再提掉相邻无气的对方座子。
     */
    private void addHandicapStone(int x, int y, int color) {
        if (!isValid(x, y)) return;

        int opponent = (color == GoBoard.BLACK) ? GoBoard.WHITE : GoBoard.BLACK;
        GoBoard.Position target = new GoBoard.Position(x, y);

        // 先清理该位置已有的任意座子
        blackHandicapStones.remove(target);
        whiteHandicapStones.remove(target);

        // 放在棋盘上
        board[y][x] = color;

        // 提掉相邻无气的对方座子，并从对方座子列表中移除
        List<GoBoard.Position> captured = BoardLogic.captureStones(board, x, y, color);
        List<GoBoard.Position> opponentStones =
                (color == GoBoard.BLACK) ? whiteHandicapStones : blackHandicapStones;
        opponentStones.removeAll(captured);

        // 加入本方座子列表
        (color == GoBoard.BLACK ? blackHandicapStones : whiteHandicapStones).add(target);
    }

    public void clearHandicapStones() {
        blackHandicapStones.clear();
        whiteHandicapStones.clear();
    }

    /**
     * 从当前 board 数组同步所有棋子到座子列表（进入摆子模式时调用）
     */
    public void syncFromBoard() {
        blackHandicapStones.clear();
        whiteHandicapStones.clear();
        for (int y = 0; y < board.length; y++) {
            for (int x = 0; x < board[y].length; x++) {
                if (board[y][x] == GoBoard.BLACK) {
                    blackHandicapStones.add(new GoBoard.Position(x, y));
                } else if (board[y][x] == GoBoard.WHITE) {
                    whiteHandicapStones.add(new GoBoard.Position(x, y));
                }
            }
        }
    }

    public void removeHandicapStone(int x, int y) {
        if (!isValid(x, y)) return;
        GoBoard.Position target = new GoBoard.Position(x, y);
        blackHandicapStones.remove(target);
        whiteHandicapStones.remove(target);
        board[y][x] = GoBoard.EMPTY;
    }

    /**
     * 坐标是否在棋盘范围内
     */
    private boolean isValid(int x, int y) {
        return board != null && y >= 0 && y < board.length
                && x >= 0 && x < board[y].length;
    }

    public int getHandicap() {
        return handicap;
    }

    public void setHandicap(int handicap) {
        this.handicap = handicap;
    }

    public List<GoBoard.Position> getBlackHandicapStones() {
        return blackHandicapStones;
    }

    public List<GoBoard.Position> getWhiteHandicapStones() {
        return whiteHandicapStones;
    }

    public void setBoard(int[][] board) {
        this.board = board;
    }
}
