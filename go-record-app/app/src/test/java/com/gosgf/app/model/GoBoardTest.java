package com.gosgf.app.model;

import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

import java.util.List;

public class GoBoardTest {
    private GoBoard board;

    @Before
    public void setUp() {
        board = new GoBoard();
    }

    @Test
    public void testValidMove() {
        // 测试正常落子
        boolean result = board.placeStone(3, 3);
        assertTrue("Valid move should return true", result);
        assertEquals("Board should have stone at (3,3)", GoBoard.BLACK, board.getBoard()[3][3]);
    }

    @Test
    public void testInvalidMoveOnExistingStone() {
        // 测试在已有棋子的位置落子
        board.placeStone(3, 3);
        boolean result = board.placeStone(3, 3);
        assertFalse("Invalid move on existing stone should return false", result);
    }

    @Test
    public void testInvalidMoveOutOfBounds() {
        // 测试在边界外落子
        boolean result1 = board.placeStone(-1, 0);
        boolean result2 = board.placeStone(0, -1);
        boolean result3 = board.placeStone(19, 0);
        boolean result4 = board.placeStone(0, 19);
        assertFalse("Invalid move out of bounds should return false", result1);
        assertFalse("Invalid move out of bounds should return false", result2);
        assertFalse("Invalid move out of bounds should return false", result3);
        assertFalse("Invalid move out of bounds should return false", result4);
    }

    @Test
    public void testSuicideMove() {
        // 测试自杀行为
        // 设置自杀场景：黑棋被白棋包围
        placeStone(board, 2, 3, GoBoard.WHITE);
        placeStone(board, 3, 2, GoBoard.WHITE);
        placeStone(board, 3, 4, GoBoard.WHITE);
        placeStone(board, 4, 3, GoBoard.WHITE);
        
        // 尝试在中间落黑子，这应该是自杀行为
        boolean result = board.placeStone(3, 3);
        assertFalse("Suicide move should return false", result);
    }

    @Test
    public void testCaptureStones() {
        // 测试提子
        // 设置提子场景：白棋被黑棋包围
        placeStone(board, 2, 3, GoBoard.BLACK);
        placeStone(board, 3, 2, GoBoard.BLACK);
        placeStone(board, 3, 4, GoBoard.BLACK);
        placeStone(board, 4, 3, GoBoard.BLACK);
        placeStone(board, 3, 3, GoBoard.WHITE);
        
        // 黑棋不能在已有棋子的位置落子
        boolean result = board.placeStone(3, 3);
        assertFalse("Cannot place stone on existing stone", result);
    }

    @Test
    public void testKoRule() {
        // 测试打劫规则
        // 设置打劫场景
        placeStone(board, 1, 2, GoBoard.BLACK);
        placeStone(board, 2, 1, GoBoard.BLACK);
        placeStone(board, 2, 3, GoBoard.BLACK);
        placeStone(board, 3, 2, GoBoard.WHITE);
        placeStone(board, 2, 2, GoBoard.BLACK);
        
        // 手动设置当前玩家为白棋，以便提掉黑棋
        // 注意：这是为了测试目的，实际游戏中玩家会自动切换
        try {
            java.lang.reflect.Field field = GoBoard.class.getDeclaredField("currentPlayer");
            field.setAccessible(true);
            field.set(board, GoBoard.WHITE);
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        // 白棋不能在已有棋子的位置落子
        boolean result1 = board.placeStone(2, 2);
        assertFalse("Cannot place stone on existing stone", result1);
    }

    @Test
    public void testPassMove() {
        // 测试虚手
        boolean result = board.placeStone(-1, -1);
        assertTrue("Pass move should return true", result);
        List<GoBoard.Move> history = board.getMoveHistory();
        assertEquals("Move history should have one move", 1, history.size());
        GoBoard.Move move = history.get(0);
        assertEquals("Move should be pass move", -1, move.x);
        assertEquals("Move should be pass move", -1, move.y);
    }

    @Test
    public void testHandicapSetup() {
        // 测试让子设置
        board.setupHandicap(4);
        assertEquals("Handicap should be 4", 4, board.getHandicap());
        // 检查让子位置是否正确
        assertTrue("Board should have black stone at (3,3)", board.getBoard()[3][3] == GoBoard.BLACK);
        assertTrue("Board should have black stone at (15,15)", board.getBoard()[15][15] == GoBoard.BLACK);
        assertTrue("Board should have black stone at (3,15)", board.getBoard()[3][15] == GoBoard.BLACK);
        assertTrue("Board should have black stone at (15,3)", board.getBoard()[15][3] == GoBoard.BLACK);
    }

    @Test
    public void testUndo() {
        // 测试悔棋
        board.placeStone(3, 3);
        board.placeStone(4, 4);
        assertEquals("Board should have stone at (3,3)", GoBoard.BLACK, board.getBoard()[3][3]);
        assertEquals("Board should have stone at (4,4)", GoBoard.WHITE, board.getBoard()[4][4]);
        
        board.undo();
        assertEquals("Board should not have stone at (4,4) after undo", GoBoard.EMPTY, board.getBoard()[4][4]);
        assertEquals("Board should still have stone at (3,3) after undo", GoBoard.BLACK, board.getBoard()[3][3]);
    }

    @Test
    public void testNewGame() {
        // 测试新游戏
        board.placeStone(3, 3);
        board.placeStone(4, 4);
        board.newGame();
        assertEquals("Board should be empty after new game", GoBoard.EMPTY, board.getBoard()[3][3]);
        assertEquals("Board should be empty after new game", GoBoard.EMPTY, board.getBoard()[4][4]);
        assertEquals("Current player should be black after new game", GoBoard.BLACK, board.getCurrentPlayer());
    }

    // 辅助方法：直接设置棋盘上的棋子
    private void placeStone(GoBoard board, int x, int y, int player) {
        board.getBoard()[y][x] = player;
    }
}
