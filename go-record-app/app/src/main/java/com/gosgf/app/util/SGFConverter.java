package com.gosgf.app.util;

import com.gosgf.app.model.GoBoard;
import com.gosgf.app.model.GoBoard.Move;
import com.gosgf.app.model.GoBoard.Position;

import java.util.ArrayList;
import java.util.List;

public class SGFConverter {
    static {
        try {
            System.loadLibrary("gosgf");
        } catch (UnsatisfiedLinkError e) {
            // 本地库加载失败，使用 Java 实现
            System.out.println("Local library not found, using Java implementation");
        }
    }
    // 本地方法声明
    private static native boolean nativeLoadFromSGF();
    private static native int[][] nativeGetBoardState();
    private static native int[][] nativeGetMoveHistory();
    private static native String[] nativeGetGameInfo();
    private static final String PROP_GAME_TYPE = "GM";
    private static final String PROP_FILE_FORMAT = "FF";
    private static final String PROP_BOARD_SIZE = "SZ";
    private static final String PROP_BLACK_PLAYER = "PB";
    private static final String PROP_WHITE_PLAYER = "PW";
    private static final String PROP_RESULT = "RE";
    private static final String PROP_DATE = "DT";
    private static final String PROP_HANDICAP = "HA";
    private static final String PROP_BLACK_STONES = "AB";
    private static final String PROP_WHITE_STONES = "AW";
    private static final String PROP_BLACK_MOVE = "B";
    private static final String PROP_WHITE_MOVE = "W";
    private static final String PROP_COMMENT = "C";
    private static final String PROP_CIRCLE = "CR";
    private static final String PROP_CROSS = "MA";
    private static final String PROP_SQUARE = "SQ";
    private static final String PROP_TRIANGLE = "TR";
    private static final String PROP_LABEL = "LB";
    private static final String PROP_ARROW = "AR";
    private static final String PROP_LINE = "LN";
    
    public static SGFParser.Node convertToSGF(GoBoard board) {
        SGFParser.Node root = new SGFParser.Node();

        // 添加游戏信息
        root.addProperty(PROP_GAME_TYPE, "1"); // 1 表示围棋
        root.addProperty(PROP_FILE_FORMAT, "4"); // FF[4] 标准
        root.addProperty(PROP_BOARD_SIZE, "19"); // 19x19 棋盘

        // 添加玩家信息
        if (board.getBlackPlayer() != null && !board.getBlackPlayer().isEmpty()) {
            root.addProperty(PROP_BLACK_PLAYER, board.getBlackPlayer());
        }
        if (board.getWhitePlayer() != null && !board.getWhitePlayer().isEmpty()) {
            root.addProperty(PROP_WHITE_PLAYER, board.getWhitePlayer());
        }
        if (board.getResult() != null && !board.getResult().isEmpty()) {
            root.addProperty(PROP_RESULT, board.getResult());
        }
        if (board.getDate() != null && !board.getDate().isEmpty()) {
            root.addProperty(PROP_DATE, board.getDate());
        }

        // 添加让子信息
        int handicap = board.getHandicap();
        if (handicap > 0) {
            root.addProperty(PROP_HANDICAP, String.valueOf(handicap));
        }

        // 添加座子信息（AB/AW属性）
        addHandicapStonesToNode(board, root);

        // 如果有游戏树，转换为SGF节点树
        GoBoard.SGFNode gameTreeRoot = board.getGameTreeRoot();
        if (gameTreeRoot != null) {
            // 将游戏树转换为SGF节点树
            convertGameTreeToSGF(gameTreeRoot, root);
        } else {
            // 没有游戏树，使用moveHistory构建简单的线性序列
            buildTreeFromMoveHistory(board, root);
        }

        return root;
    }

    /**
     * 将座子信息添加到SGF节点
     * 只有设置了让子数(handicap > 0)时才保存座子
     */
    private static void addHandicapStonesToNode(GoBoard board, SGFParser.Node node) {
        // 只有当有座子时才保存
        boolean hasBlackHandicap = board.getHandicap() > 0 || !board.getBlackHandicapStones().isEmpty();
        boolean hasWhiteHandicap = !board.getWhiteHandicapStones().isEmpty();
        if (!hasBlackHandicap && !hasWhiteHandicap) {
            return; // 没有座子
        }

        // 只保存黑棋座子列表中的棋子
        for (GoBoard.Position pos : board.getBlackHandicapStones()) {
            String vertex = boardToVertex(pos.x, pos.y);
            node.addProperty(PROP_BLACK_STONES, vertex);
        }

        // 只保存白棋座子列表中的棋子
        for (GoBoard.Position pos : board.getWhiteHandicapStones()) {
            String vertex = boardToVertex(pos.x, pos.y);
            node.addProperty(PROP_WHITE_STONES, vertex);
        }
    }

    /**
     * 将GoBoard游戏树转换为SGF节点树
     */
    private static void convertGameTreeToSGF(GoBoard.SGFNode gameNode, SGFParser.Node sgfNode) {
        // 递归处理所有子节点
        for (GoBoard.SGFNode gameChild : gameNode.children) {
            SGFParser.Node sgfChild = new SGFParser.Node();

            // 添加走子属性
            if (gameChild.move != null) {
                String vertex = boardToVertex(gameChild.move.x, gameChild.move.y);
                if (gameChild.move.player == GoBoard.BLACK) {
                    sgfChild.addProperty(PROP_BLACK_MOVE, vertex);
                } else {
                    sgfChild.addProperty(PROP_WHITE_MOVE, vertex);
                }
            }

            // 添加注释
            if (gameChild.comment != null && !gameChild.comment.isEmpty()) {
                sgfChild.addProperty(PROP_COMMENT, gameChild.comment);
            }

            // 添加其他属性
            if (gameChild.properties != null) {
                for (java.util.Map.Entry<String, java.util.List<String>> entry : gameChild.properties.entrySet()) {
                    String key = entry.getKey();
                    // 跳过已经处理的走子属性
                    if (!key.equals(PROP_BLACK_MOVE) && !key.equals(PROP_WHITE_MOVE)) {
                        for (String value : entry.getValue()) {
                            sgfChild.addProperty(key, value);
                        }
                    }
                }
            }

            sgfNode.addChild(sgfChild);

            // 递归处理子节点
            convertGameTreeToSGF(gameChild, sgfChild);
        }
    }

    /**
     * 从moveHistory构建简单的线性SGF树
     */
    private static void buildTreeFromMoveHistory(GoBoard board, SGFParser.Node root) {
        java.util.List<GoBoard.Move> moveHistory = board.getMoveHistory();
        SGFParser.Node currentNode = root;

        for (GoBoard.Move move : moveHistory) {
            SGFParser.Node moveNode = new SGFParser.Node();
            String vertex = boardToVertex(move.x, move.y);

            if (move.player == GoBoard.BLACK) {
                moveNode.addProperty(PROP_BLACK_MOVE, vertex);
            } else {
                moveNode.addProperty(PROP_WHITE_MOVE, vertex);
            }

            currentNode.addChild(moveNode);
            currentNode = moveNode;
        }
    }
    
    public static void loadFromSGF(GoBoard board, SGFParser.Node root) {
        // 重置棋盘
        board.newGame();

        // 处理根节点属性
        processNodeProperties(board, root);

        // 检查是否有子节点(如果没有,可能是C实现只返回了根节点属性)
        if (root.children.isEmpty()) {
            // 尝试使用C实现的走子历史
            try {
                // 调用本地方法获取走子历史
                int[][] moveHistory = nativeGetMoveHistory();
                if (moveHistory != null && moveHistory.length > 0) {
                    // 加载走子历史
                    for (int[] moveData : moveHistory) {
                        if (moveData.length == 3) {
                            int x = moveData[0];
                            int y = moveData[1];
                            int player = moveData[2];
                            board.placeStone(x, y);
                        }
                    }
                }
            } catch (UnsatisfiedLinkError e) {
                // 本地库调用失败,忽略
            }
        } else {
            // 构建游戏树结构（使用所有分支）
            System.out.println("开始构建游戏树,根节点子节点数: " + root.children.size());

            if (root.children.isEmpty()) {
                System.out.println("警告: 根节点没有子节点");
                return;
            }

            System.out.println("使用所有分支（共 " + root.children.size() + " 个分支）");

            // 构建游戏树
            GoBoard.SGFNode gameTree = buildGameTree(root, null);

            System.out.println("游戏树构建完成");

            // 使用新的统一接口设置游戏树
            board.setGameTreeRoot(gameTree);

            System.out.println("游戏树已设置到board");
        }
    }

    // 构建 GoBoard 游戏树结构
    private static GoBoard.SGFNode buildGameTree(SGFParser.Node sgfNode, GoBoard.SGFNode parent) {
        GoBoard.SGFNode node = new GoBoard.SGFNode(parent);

        // 复制属性
        if (sgfNode.properties != null) {
            node.properties.putAll(sgfNode.properties);
        }

        // 读取注释
        if (sgfNode.properties.containsKey(PROP_COMMENT)) {
            node.comment = sgfNode.properties.get(PROP_COMMENT).get(0);
        }

        // 走子信息
        Move move = getMoveFromNode(sgfNode);
        if (move != null) {
            node.move = move;
        }

        // 递归处理子节点（所有分支）
        if (sgfNode.children != null && !sgfNode.children.isEmpty()) {
            for (int i = 0; i < sgfNode.children.size(); i++) {
                SGFParser.Node sgfChild = sgfNode.children.get(i);
                GoBoard.SGFNode child = buildGameTree(sgfChild, node);
                child.index = i;
                node.children.add(child);
            }
        }

        return node;
    }
    
    // 从节点获取走子
    private static GoBoard.Move getMoveFromNode(SGFParser.Node node) {
        if (node == null) {
            return null;
        }
        
        if (node.properties.containsKey(PROP_BLACK_MOVE)) {
            String vertex = node.properties.get(PROP_BLACK_MOVE).get(0);
            int[] coord = SGFParser.parseVertex(vertex);
            if (coord[0] != -1 && coord[1] != -1) {
                return new GoBoard.Move(coord[0], coord[1], GoBoard.BLACK);
            }
        } else if (node.properties.containsKey(PROP_WHITE_MOVE)) {
            String vertex = node.properties.get(PROP_WHITE_MOVE).get(0);
            int[] coord = SGFParser.parseVertex(vertex);
            if (coord[0] != -1 && coord[1] != -1) {
                return new GoBoard.Move(coord[0], coord[1], GoBoard.WHITE);
            }
        }
        
        return null;
    }
    
    // 以下方法已废弃，新版本使用统一的游戏树结构，不再需要单独处理分支
    
    // 处理节点属性
    private static void processNodeProperties(GoBoard board, SGFParser.Node node) {
        if (board == null || node == null) {
            return;
        }

        // 加载游戏信息
        if (node.properties.containsKey(PROP_BLACK_PLAYER)) {
            board.setBlackPlayer(node.properties.get(PROP_BLACK_PLAYER).get(0));
        }
        if (node.properties.containsKey(PROP_WHITE_PLAYER)) {
            board.setWhitePlayer(node.properties.get(PROP_WHITE_PLAYER).get(0));
        }
        if (node.properties.containsKey(PROP_RESULT)) {
            board.setResult(node.properties.get(PROP_RESULT).get(0));
        }
        if (node.properties.containsKey(PROP_DATE)) {
            board.setDate(node.properties.get(PROP_DATE).get(0));
        }

        // 加载让子信息
        if (node.properties.containsKey(PROP_HANDICAP)) {
            try {
                int handicap = Integer.parseInt(node.properties.get(PROP_HANDICAP).get(0));
                board.setHandicap(handicap);
                board.setupHandicap(handicap);
            } catch (NumberFormatException e) {
                // 忽略无效的让子数
            }
        }

        // 清除现有座子
        board.clearHandicapStones();

        // 加载初始棋子位置（AB/AW属性）
        if (node.properties.containsKey(PROP_BLACK_STONES)) {
            for (String vertexList : node.properties.get(PROP_BLACK_STONES)) {
                List<int[]> coords = SGFParser.parseCompressedVertices(vertexList);
                for (int[] coord : coords) {
                    if (coord[0] != -1 && coord[1] != -1) {
                        board.addBlackHandicapStone(coord[0], coord[1]);
                    }
                }
            }
        }
        if (node.properties.containsKey(PROP_WHITE_STONES)) {
            for (String vertexList : node.properties.get(PROP_WHITE_STONES)) {
                List<int[]> coords = SGFParser.parseCompressedVertices(vertexList);
                for (int[] coord : coords) {
                    if (coord[0] != -1 && coord[1] != -1) {
                        board.addWhiteHandicapStone(coord[0], coord[1]);
                    }
                }
            }
        }
    }
    

    
    public static String convertBoardToSGFString(GoBoard board) {
        SGFParser.Node root = convertToSGF(board);
        return SGFParser.generate(root);
    }
    
    public static void loadBoardFromSGFString(GoBoard board, String sgfString) throws SGFParser.SGFParseException {
        SGFParser.Node root = SGFParser.parse(sgfString);
        loadFromSGF(board, root);
    }
    
    // 辅助方法：将棋盘坐标转换为SGF顶点表示
    public static String boardToVertex(int x, int y) {
        return SGFParser.generateVertex(x, y);
    }
    
    // 辅助方法：将SGF顶点表示转换为棋盘坐标
    public static int[] vertexToBoard(String vertex) {
        return SGFParser.parseVertex(vertex);
    }
    
    // 辅助方法：处理标记
    public static void addMarkToNode(SGFParser.Node node, String markType, List<Position> positions) {
        if (positions.isEmpty()) {
            return;
        }
        
        StringBuilder sb = new StringBuilder();
        for (Position pos : positions) {
            if (sb.length() > 0) {
                sb.append(" ");
            }
            sb.append(boardToVertex(pos.x, pos.y));
        }
        
        node.addProperty(markType, sb.toString());
    }
    
    // 辅助方法：处理标签
    public static void addLabelToNode(SGFParser.Node node, Position position, String label) {
        String vertex = boardToVertex(position.x, position.y);
        node.addProperty(PROP_LABEL, vertex + ":" + label);
    }
    
    // 辅助方法：从节点中提取标记
    public static List<Position> getMarksFromNode(SGFParser.Node node, String markType) {
        List<Position> positions = new ArrayList<>();
        
        if (node.properties.containsKey(markType)) {
            for (String value : node.properties.get(markType)) {
                List<int[]> coords = SGFParser.parseCompressedVertices(value);
                for (int[] coord : coords) {
                    if (coord[0] != -1 && coord[1] != -1) {
                        positions.add(new Position(coord[0], coord[1]));
                    }
                }
            }
        }
        
        return positions;
    }
}