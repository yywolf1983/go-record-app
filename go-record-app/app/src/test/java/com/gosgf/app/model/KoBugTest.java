package com.gosgf.app.model;

import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

import java.lang.reflect.Field;

public class KoBugTest {
    private GoBoard board;

    @Before
    public void setUp() {
        board = new GoBoard();
    }

    private String koInfo() throws Exception {
        Field f = GoBoard.class.getDeclaredField("koMove");
        f.setAccessible(true);
        Object m = f.get(board);
        return m == null ? "null" : m.toString();
    }

    /**
     * 构造一个“真正会提子”的单劫：
     * 白(2,3) 四周除 (2,2) 外均为黑/边界，黑落 (2,2) 提掉白(2,3) 形成劫；
     * 此后白立即回提 (2,3) 必须被 koMove 拦截。
     * 验证 koMove 记录的是“被提子位置（对手的劫禁着点）”而非“本方落子点”。
     */
    @Test
    public void testKoRetryBlocked() throws Exception {
        // 1 黑(2,4)
        board.placeStone(2, 4);
        // 2 白(2,3)
        board.placeStone(2, 3);
        // 3 黑(1,3)
        board.placeStone(1, 3);
        // 4 白(1,2) 占位（不影响劫形）
        board.placeStone(1, 2);
        // 5 黑(3,3)
        board.placeStone(3, 3);
        // 6 白(3,2) 占位
        board.placeStone(3, 2);
        // 7 黑(2,2) 提掉白(2,3) 形成劫
        assertTrue("黑应在 (2,2) 提子形成劫", board.placeStone(2, 2));
        System.out.println("DEBUG koMove after capture = " + koInfo());

        // 8 白立即回提 (2,3) 应被普通劫规则拦截
        boolean retry = board.placeStone(2, 3);
        System.out.println("DEBUG retry result=" + retry + " err=" + board.getLastErrorMessage());
        assertFalse("打劫不能立即回提 (2,3) 应被拦", retry);
        assertEquals("应提示打劫禁着", "此处为打劫，不能立即回提", board.getLastErrorMessage());
    }
}
