package com.gosgf.app.util;

import com.gosgf.app.model.GoBoard;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class SGFConverterTest {

    @Test
    public void testConvertToSGF() {
        // 测试将GoBoard转换为SGF格式
        GoBoard board = new GoBoard();
        board.setBlackPlayer("Black");
        board.setWhitePlayer("White");
        board.setResult("B+3.5");
        board.setDate("2024-01-01");
        board.setHandicap(0);
        
        // 落子
        board.placeStone(3, 3); // B
        board.placeStone(3, 15); // W
        board.placeStone(15, 3); // B
        board.placeStone(15, 15); // W
        
        // 转换为SGF
        SGFParser.Node root = SGFConverter.convertToSGF(board);
        
        // 检查根节点属性
        assertEquals("Game type should be 1", "1", root.properties.get("GM").get(0));
        assertEquals("File format should be 4", "4", root.properties.get("FF").get(0));
        assertEquals("Board size should be 19", "19", root.properties.get("SZ").get(0));
        assertEquals("Black player should be Black", "Black", root.properties.get("PB").get(0));
        assertEquals("White player should be White", "White", root.properties.get("PW").get(0));
        assertEquals("Result should be B+3.5", "B+3.5", root.properties.get("RE").get(0));
        assertEquals("Date should be 2024-01-01", "2024-01-01", root.properties.get("DT").get(0));
        assertEquals("Handicap should be 0", "0", root.properties.get("HA").get(0));
        
        // 检查走子记录
        assertEquals("Root should have 1 child", 1, root.children.size());
        SGFParser.Node node1 = root.children.get(0);
        assertEquals("First node should have B property", "dd", node1.properties.get("B").get(0)); // 3,3 -> dd
        
        assertEquals("First node should have 1 child", 1, node1.children.size());
        SGFParser.Node node2 = node1.children.get(0);
        assertEquals("Second node should have W property", "dp", node2.properties.get("W").get(0)); // 3,15 -> dp
        
        assertEquals("Second node should have 1 child", 1, node2.children.size());
        SGFParser.Node node3 = node2.children.get(0);
        assertEquals("Third node should have B property", "pd", node3.properties.get("B").get(0)); // 15,3 -> pd
        
        assertEquals("Third node should have 1 child", 1, node3.children.size());
        SGFParser.Node node4 = node3.children.get(0);
        assertEquals("Fourth node should have W property", "pp", node4.properties.get("W").get(0)); // 15,15 -> pp
    }

    @Test
    public void testLoadFromSGF() throws SGFParser.SGFParseException {
        // 测试从SGF格式加载到GoBoard
        String sgf = "(;GM[1]FF[4]SZ[19]PB[Black]PW[White]RE[B+3.5]DT[2024-01-01]HA[0];B[dd];W[dp];B[pd];W[pp])";
        
        GoBoard board = new GoBoard();
        SGFConverter.loadBoardFromSGFString(board, sgf);
        
        // 检查游戏信息
        assertEquals("Black player should be Black", "Black", board.getBlackPlayer());
        assertEquals("White player should be White", "White", board.getWhitePlayer());
        assertEquals("Result should be B+3.5", "B+3.5", board.getResult());
        assertEquals("Date should be 2024-01-01", "2024-01-01", board.getDate());
        assertEquals("Handicap should be 0", 0, board.getHandicap());
        
        // 检查走子记录
        assertEquals("Move history should have 4 moves", 4, board.getMoveHistory().size());
        
        // 检查棋盘状态
        assertEquals("Board should have black stone at (3,3)", GoBoard.BLACK, board.getBoard()[3][3]);
        assertEquals("Board should have white stone at (3,15)", GoBoard.WHITE, board.getBoard()[15][3]);
        assertEquals("Board should have black stone at (15,3)", GoBoard.BLACK, board.getBoard()[3][15]);
        assertEquals("Board should have white stone at (15,15)", GoBoard.WHITE, board.getBoard()[15][15]);
    }

    @Test
    public void testHandicapSetup() {
        // 测试让子设置
        GoBoard board = new GoBoard();
        board.setupHandicap(4);
        
        // 转换为SGF
        SGFParser.Node root = SGFConverter.convertToSGF(board);
        
        // 检查让子信息
        assertEquals("Handicap should be 4", "4", root.properties.get("HA").get(0));
        assertTrue("Should have AB property for black stones", root.properties.containsKey("AB"));
        
        // 检查让子位置
        List<String> abValues = root.properties.get("AB");
        assertTrue("Should contain handicap stone at dd", abValues.contains("dd")); // 3,3
        assertTrue("Should contain handicap stone at pp", abValues.contains("pp")); // 15,15
        assertTrue("Should contain handicap stone at dp", abValues.contains("dp")); // 3,15
        assertTrue("Should contain handicap stone at pd", abValues.contains("pd")); // 15,3
    }

    @Test
    public void testBoardToVertex() {
        // 测试棋盘坐标转换为SGF顶点
        assertEquals("Coord [0,0] should be aa", "aa", SGFConverter.boardToVertex(0, 0));
        assertEquals("Coord [3,3] should be dd", "dd", SGFConverter.boardToVertex(3, 3));
        assertEquals("Coord [15,3] should be pd", "pd", SGFConverter.boardToVertex(15, 3));
        assertEquals("Coord [15,15] should be pp", "pp", SGFConverter.boardToVertex(15, 15));
    }

    @Test
    public void testVertexToBoard() {
        // 测试SGF顶点转换为棋盘坐标
        assertArrayEquals("Vertex aa should be [0,0]", new int[]{0, 0}, SGFConverter.vertexToBoard("aa"));
        assertArrayEquals("Vertex dd should be [3,3]", new int[]{3, 3}, SGFConverter.vertexToBoard("dd"));
        assertArrayEquals("Vertex pd should be [15,3]", new int[]{15, 3}, SGFConverter.vertexToBoard("pd"));
        assertArrayEquals("Vertex pp should be [15,15]", new int[]{15, 15}, SGFConverter.vertexToBoard("pp"));
    }
}
