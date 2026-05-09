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
        List<GoBoard.Position> positions = getHandicapPositions(handicapCount);
        for (GoBoard.Position pos : positions) {
            board[pos.y][pos.x] = GoBoard.BLACK;
        }
    }

    /**
     * 让子棋位置计算（星位点）
     */
    public static List<GoBoard.Position> getHandicapPositions(int handicapCount) {
        List<GoBoard.Position> positions = new ArrayList<>();
        switch (handicapCount) {
            case 1:
                positions.add(new GoBoard.Position(9, 9));
                break;
            case 2:
                positions.add(new GoBoard.Position(3, 3));
                positions.add(new GoBoard.Position(15, 15));
                break;
            case 3:
                positions.add(new GoBoard.Position(3, 3));
                positions.add(new GoBoard.Position(15, 15));
                positions.add(new GoBoard.Position(3, 15));
                break;
            case 4:
                positions.add(new GoBoard.Position(3, 3));
                positions.add(new GoBoard.Position(15, 15));
                positions.add(new GoBoard.Position(3, 15));
                positions.add(new GoBoard.Position(15, 3));
                break;
            case 5:
                positions.add(new GoBoard.Position(3, 3));
                positions.add(new GoBoard.Position(15, 15));
                positions.add(new GoBoard.Position(3, 15));
                positions.add(new GoBoard.Position(15, 3));
                positions.add(new GoBoard.Position(9, 9));
                break;
            case 6:
                positions.add(new GoBoard.Position(3, 3));
                positions.add(new GoBoard.Position(15, 15));
                positions.add(new GoBoard.Position(3, 15));
                positions.add(new GoBoard.Position(15, 3));
                positions.add(new GoBoard.Position(3, 9));
                positions.add(new GoBoard.Position(15, 9));
                break;
            case 7:
                positions.add(new GoBoard.Position(3, 3));
                positions.add(new GoBoard.Position(15, 15));
                positions.add(new GoBoard.Position(3, 15));
                positions.add(new GoBoard.Position(15, 3));
                positions.add(new GoBoard.Position(3, 9));
                positions.add(new GoBoard.Position(15, 9));
                positions.add(new GoBoard.Position(9, 9));
                break;
            case 8:
                positions.add(new GoBoard.Position(3, 3));
                positions.add(new GoBoard.Position(15, 15));
                positions.add(new GoBoard.Position(3, 15));
                positions.add(new GoBoard.Position(15, 3));
                positions.add(new GoBoard.Position(3, 9));
                positions.add(new GoBoard.Position(15, 9));
                positions.add(new GoBoard.Position(9, 3));
                positions.add(new GoBoard.Position(9, 15));
                break;
            case 9:
                positions.add(new GoBoard.Position(3, 3));
                positions.add(new GoBoard.Position(15, 15));
                positions.add(new GoBoard.Position(3, 15));
                positions.add(new GoBoard.Position(15, 3));
                positions.add(new GoBoard.Position(3, 9));
                positions.add(new GoBoard.Position(15, 9));
                positions.add(new GoBoard.Position(9, 3));
                positions.add(new GoBoard.Position(9, 15));
                positions.add(new GoBoard.Position(9, 9));
                break;
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
        blackHandicapStones.add(new GoBoard.Position(x, y));
        board[y][x] = GoBoard.BLACK;
    }

    public void addWhiteHandicapStone(int x, int y) {
        whiteHandicapStones.add(new GoBoard.Position(x, y));
        board[y][x] = GoBoard.WHITE;
    }

    public void clearHandicapStones() {
        blackHandicapStones.clear();
        whiteHandicapStones.clear();
    }

    /**
     * 移除指定位置的棋子（用于摆子模式）
     */
    public void removeHandicapStone(int x, int y) {
        GoBoard.Position blackToRemove = null;
        for (GoBoard.Position pos : blackHandicapStones) {
            if (pos.x == x && pos.y == y) {
                blackToRemove = pos;
                break;
            }
        }
        if (blackToRemove != null) {
            blackHandicapStones.remove(blackToRemove);
            board[y][x] = GoBoard.EMPTY;
            return;
        }

        GoBoard.Position whiteToRemove = null;
        for (GoBoard.Position pos : whiteHandicapStones) {
            if (pos.x == x && pos.y == y) {
                whiteToRemove = pos;
                break;
            }
        }
        if (whiteToRemove != null) {
            whiteHandicapStones.remove(whiteToRemove);
            board[y][x] = GoBoard.EMPTY;
            return;
        }

        if (board[y][x] != GoBoard.EMPTY) {
            board[y][x] = GoBoard.EMPTY;
        }
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
