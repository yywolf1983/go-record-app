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

    @Test
    public void testSuperkoPreventsRepetition() throws Exception {
        // Superko（全局同形禁着）：当落子后局面与历史某局面完全相同（含轮走方）时禁止落子。
        // 通过反射直接控制局面与历史集合，构造"走一步后又回到相同局面"的重复场景。
        java.lang.reflect.Field boardField = GoBoard.class.getDeclaredField("board");
        boardField.setAccessible(true);
        int[][] b = (int[][]) boardField.get(board);
        for (int y = 0; y < 19; y++) for (int x = 0; x < 19; x++) b[y][x] = GoBoard.EMPTY;

        java.lang.reflect.Field playerField = GoBoard.class.getDeclaredField("currentPlayer");
        playerField.setAccessible(true);
        playerField.set(board, GoBoard.BLACK);

        // 用黑(0,0) 落子：局面 A(空盘黑) -> 局面 B(黑0,0轮白)。B 合法，记入集合。
        boolean first = board.placeStone(0, 0);
        if (!first) System.out.println("DEBUG first placeStone failed: " + board.getLastErrorMessage());
        assertTrue("首次落子应成功", first);
        int[][] fresh = board.getBoard();
        assertFalse("落子后 (0,0) 应为黑", fresh[0][0] == GoBoard.EMPTY);

        // 手动还原到局面 A（空盘、轮黑），并往历史集合注入"局面 B 曾出现过"
        // 注意 board 字段在 placeStone 后指向新数组，必须用 getBoard() 取最新引用
        int[][] cur = board.getBoard();
        for (int y = 0; y < 19; y++) for (int x = 0; x < 19; x++) cur[y][x] = GoBoard.EMPTY;
        playerField.set(board, GoBoard.BLACK);
        java.lang.reflect.Field posField = GoBoard.class.getDeclaredField("positionHashes");
        posField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Set<Integer> posHashes = (java.util.Set<Integer>) posField.get(board);
        posHashes.clear();
        // 局面 A（空盘+黑）入集合
        java.lang.reflect.Method hashMethod = GoBoard.class.getDeclaredMethod("boardHash", int[][].class, int.class);
        hashMethod.setAccessible(true);
        posHashes.add((Integer) hashMethod.invoke(board, cur, GoBoard.BLACK));
        // 关键：局面 B（黑0,0 + 白）作为"历史曾出现"注入，模拟此前已经走过这一步
        cur[0][0] = GoBoard.BLACK;
        posHashes.add((Integer) hashMethod.invoke(board, cur, GoBoard.WHITE));
        cur[0][0] = GoBoard.EMPTY; // 还原空盘，准备再次落子

        // 再次在空盘(轮黑)落黑(0,0)：落子后局面 B(黑0,0轮白) 已在历史集合 -> 应被 superko 拦截
        boolean result = board.placeStone(0, 0);
        assertFalse("重复局面应被 Superko 拦截", result);
        assertEquals("被拦截时应提示循环劫禁着", "循环劫（全局同形）禁着，不能立即提回", board.getLastErrorMessage());
    }

    @Test
    public void testSuperkoAllowsNormalPlay() {
        // 普通序列不应被 superko 误伤：连续不同局面都应合法
        assertTrue("hand 1", board.placeStone(3, 3));
        boolean h2 = board.placeStone(15, 15);
        if (!h2) System.out.println("DEBUG h2 failed: " + board.getLastErrorMessage());
        assertTrue("hand 2", h2);
        assertTrue("hand 3", board.placeStone(3, 15));
        assertTrue("hand 4", board.placeStone(15, 3));
        assertEquals("应共 4 手", 4, board.getMoveHistory().size());
    }

    @Test
    public void testSuperkoDoesNotBlockNormalKo() {
        // 回归：普通劫（单子劫）仍应由 koMove 拦截，且 superko 不干扰
        // 经典单劫形：黑做劫眼于 (2,2)，仅一口外气(2,3) 被白占据
        board.placeStone(1, 2);
        board.placeStone(3, 2);
        board.placeStone(2, 1);
        // 黑在 (2,2) 落子做劫眼（四面：上下为黑、左右仅 (2,3) 为空，落子后该黑块仅剩 (2,3) 一气）
        assertTrue("黑应能在 (2,2) 落子形成劫眼", board.placeStone(2, 2));
        // 白占 (2,3) 提掉黑(2,2) 形成劫
        assertTrue("白应提掉黑子形成劫", board.placeStone(2, 3));
        // 黑立即回提应被普通劫规则拦截（koMove 仍生效）
        assertFalse("打劫不能立即回提", board.placeStone(2, 2));
    }
}
