package com.gosgf.app.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 标记管理器 - 管理棋盘上的各种标记（圆圈、叉号、方块、三角形）
 * 从 GoBoard 中提取，职责单一
 */
public class MarkManager {

    private final Set<GoBoard.Position> marks = new HashSet<>();
    private final Set<GoBoard.Position> crossMarks = new HashSet<>();
    private final Set<GoBoard.Position> squareMarks = new HashSet<>();
    private final Set<GoBoard.Position> triangleMarks = new HashSet<>();

    // ==================== 通用辅助 ====================

    private boolean containsPosition(Set<GoBoard.Position> set, int x, int y) {
        return set.contains(new GoBoard.Position(x, y));
    }

    private boolean containsPosition(List<GoBoard.Position> list, int x, int y) {
        for (GoBoard.Position pos : list) {
            if (pos.x == x && pos.y == y) {
                return true;
            }
        }
        return false;
    }

    private boolean removePosition(Set<GoBoard.Position> set, int x, int y) {
        return set.remove(new GoBoard.Position(x, y));
    }

    private boolean removePosition(List<GoBoard.Position> list, int x, int y) {
        return list.removeIf(pos -> pos.x == x && pos.y == y);
    }

    // ==================== 圆圈标记 ====================

    public void addMark(int x, int y) {
        if (!containsPosition(marks, x, y)) {
            marks.add(new GoBoard.Position(x, y));
        }
    }

    public void removeMark(int x, int y) {
        removePosition(marks, x, y);
    }

    public List<GoBoard.Position> getMarks() {
        return new ArrayList<>(marks);
    }

    // ==================== 叉号标记 ====================

    public void addCrossMark(int x, int y) {
        if (!containsPosition(crossMarks, x, y)) {
            crossMarks.add(new GoBoard.Position(x, y));
        }
    }

    public void removeCrossMark(int x, int y) {
        removePosition(crossMarks, x, y);
    }

    public List<GoBoard.Position> getCrossMarks() {
        return new ArrayList<>(crossMarks);
    }

    // ==================== 方块标记 ====================

    public void addSquareMark(int x, int y) {
        if (!containsPosition(squareMarks, x, y)) {
            squareMarks.add(new GoBoard.Position(x, y));
        }
    }

    public void removeSquareMark(int x, int y) {
        removePosition(squareMarks, x, y);
    }

    public List<GoBoard.Position> getSquareMarks() {
        return new ArrayList<>(squareMarks);
    }

    // ==================== 三角形标记 ====================

    public void addTriangleMark(int x, int y) {
        if (!containsPosition(triangleMarks, x, y)) {
            triangleMarks.add(new GoBoard.Position(x, y));
        }
    }

    public void removeTriangleMark(int x, int y) {
        removePosition(triangleMarks, x, y);
    }

    public List<GoBoard.Position> getTriangleMarks() {
        return new ArrayList<>(triangleMarks);
    }

    // ==================== 批量操作 ====================

    public void clearMarks() {
        marks.clear();
        crossMarks.clear();
        squareMarks.clear();
        triangleMarks.clear();
    }
}
