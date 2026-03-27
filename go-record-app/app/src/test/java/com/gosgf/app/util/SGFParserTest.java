package com.gosgf.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

import java.util.List;

public class SGFParserTest {

    @Test
    public void testParseSimpleSGF() throws SGFParser.SGFParseException {
        // 测试解析简单的SGF文件
        String sgf = "(;GM[1]FF[4]SZ[19]PB[Black]PW[White];B[aa];W[bb])";
        SGFParser.Node root = SGFParser.parse(sgf);
        
        // 检查根节点属性
        assertEquals("Game type should be 1", "1", root.properties.get("GM").get(0));
        assertEquals("File format should be 4", "4", root.properties.get("FF").get(0));
        assertEquals("Board size should be 19", "19", root.properties.get("SZ").get(0));
        assertEquals("Black player should be Black", "Black", root.properties.get("PB").get(0));
        assertEquals("White player should be White", "White", root.properties.get("PW").get(0));
        
        // 检查子节点
        assertEquals("Root should have 1 child", 1, root.children.size());
        SGFParser.Node node1 = root.children.get(0);
        assertEquals("First node should have B property", "aa", node1.properties.get("B").get(0));
        
        assertEquals("First node should have 1 child", 1, node1.children.size());
        SGFParser.Node node2 = node1.children.get(0);
        assertEquals("Second node should have W property", "bb", node2.properties.get("W").get(0));
    }

    @Test
    public void testParseSGFWithVariations() throws SGFParser.SGFParseException {
        // 测试解析包含变体的SGF文件
        String sgf = "(;GM[1]SZ[19];B[aa](;W[bb];B[cc])(;W[dd];B[ee]))";
        SGFParser.Node root = SGFParser.parse(sgf);
        
        // 检查根节点
        assertEquals("Game type should be 1", "1", root.properties.get("GM").get(0));
        assertEquals("Board size should be 19", "19", root.properties.get("SZ").get(0));
        
        // 检查第一个节点（黑棋落子）
        assertEquals("Root should have 1 child", 1, root.children.size());
        SGFParser.Node node1 = root.children.get(0);
        assertEquals("First node should have B property", "aa", node1.properties.get("B").get(0));
        
        // 检查变体
        assertEquals("First node should have 2 children (variations)", 2, node1.children.size());
        
        // 检查第一个变体
        SGFParser.Node var1 = node1.children.get(0);
        assertEquals("First variation should have W property", "bb", var1.properties.get("W").get(0));
        
        // 检查第二个变体
        SGFParser.Node var2 = node1.children.get(1);
        assertEquals("Second variation should have W property", "dd", var2.properties.get("W").get(0));
    }

    @Test
    public void testGenerateSGF() {
        // 测试生成SGF文件
        SGFParser.Node root = new SGFParser.Node();
        root.addProperty("GM", "1");
        root.addProperty("FF", "4");
        root.addProperty("SZ", "19");
        root.addProperty("PB", "Black");
        root.addProperty("PW", "White");
        
        SGFParser.Node node1 = new SGFParser.Node();
        node1.addProperty("B", "aa");
        root.addChild(node1);
        
        SGFParser.Node node2 = new SGFParser.Node();
        node2.addProperty("W", "bb");
        node1.addChild(node2);
        
        String sgf = SGFParser.generate(root);
        assertNotNull("Generated SGF should not be null", sgf);
        assertTrue("Generated SGF should contain game info", sgf.contains("GM[1]"));
        assertTrue("Generated SGF should contain move info", sgf.contains("B[aa]"));
        assertTrue("Generated SGF should contain move info", sgf.contains("W[bb]"));
    }

    @Test
    public void testParseVertex() {
        // 测试解析顶点坐标
        int[] coord1 = SGFParser.parseVertex("aa");
        assertArrayEquals("Vertex aa should be [0,0]", new int[]{0, 0}, coord1);
        
        int[] coord2 = SGFParser.parseVertex("pd");
        assertArrayEquals("Vertex pd should be [15,3]", new int[]{15, 3}, coord2);
        
        // 测试超出范围的坐标，应该返回[-1, -1]
        int[] coord3 = SGFParser.parseVertex("tt");
        assertArrayEquals("Vertex tt should be [-1,-1] (out of range)", new int[]{-1, -1}, coord3);
    }

    @Test
    public void testGenerateVertex() {
        // 测试生成顶点坐标
        assertEquals("Coord [0,0] should be aa", "aa", SGFParser.generateVertex(0, 0));
        assertEquals("Coord [15,3] should be pd", "pd", SGFParser.generateVertex(15, 3));
        // 测试超出范围的坐标，应该返回空字符串
        assertEquals("Coord [19,19] should be empty string (out of range)", "", SGFParser.generateVertex(19, 19));
    }

    @Test
    public void testParseCompressedVertices() {
        // 测试解析压缩的顶点列表
        List<int[]> vertices = SGFParser.parseCompressedVertices("aa bb pd");
        assertEquals("Should parse 3 vertices", 3, vertices.size());
        assertArrayEquals("First vertex should be [0,0]", new int[]{0, 0}, vertices.get(0));
        assertArrayEquals("Second vertex should be [1,1]", new int[]{1, 1}, vertices.get(1));
        assertArrayEquals("Third vertex should be [15,3]", new int[]{15, 3}, vertices.get(2));
    }

    @Test
    public void testParseCompressedVerticesWithRange() {
        // 测试解析包含范围的压缩顶点列表
        List<int[]> vertices = SGFParser.parseCompressedVertices("aa:ac");
        assertEquals("Should parse 9 vertices in range", 9, vertices.size());
        // 检查第一个顶点
        assertArrayEquals("First vertex should be [0,0]", new int[]{0, 0}, vertices.get(0));
        // 检查最后一个顶点
        assertArrayEquals("Last vertex should be [2,2]", new int[]{2, 2}, vertices.get(8));
    }
}
