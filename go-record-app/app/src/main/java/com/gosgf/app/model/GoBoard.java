package com.gosgf.app.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

public class GoBoard {
    private static final int BOARD_SIZE = 19;
    public static final int EMPTY = 0;
    public static final int BLACK = 1;
    public static final int WHITE = 2;
    
    private int[][] board;
    private int currentPlayer;
    private List<Move> moveHistory;
    private Stack<List<Move>> variations;
    private Move lastMove;
    private Move koMove;
    private String blackPlayer;
    private String whitePlayer;
    private String result;
    private String date;
    private int handicap;
    private Stack<Move> undoStack;
    private int currentMoveIndex;
    private List<Position> blackHandicapStones;
    private List<Position> whiteHandicapStones;
    private List<Position> marks; // 标记位置列表（圆圈）
    private List<Position> crossMarks; // 叉号标记
    private List<Position> squareMarks; // 方块标记
    private List<Position> triangleMarks; // 三角形标记

    // SGF 节点数据结构（游戏树）
    public static class SGFNode {
        public Move move;
        public String comment; // 节点注释
        public java.util.Map<String, java.util.List<String>> properties;
        public java.util.List<SGFNode> children;
        public SGFNode parent;
        public int index; // 在父节点的children中的索引

        public SGFNode(SGFNode parent) {
            this.parent = parent;
            this.properties = new java.util.HashMap<>();
            this.children = new java.util.ArrayList<>();
            this.comment = "";
        }
    }

    // 游戏树的根节点
    private SGFNode gameTreeRoot;

    // 当前浏览的节点
    private SGFNode currentNode;
    
    public GoBoard() {
        initializeBoard();
    }
    
    private void initializeBoard() {
        board = new int[BOARD_SIZE][BOARD_SIZE];
        currentPlayer = BLACK;
        moveHistory = new ArrayList<>();
        variations = new Stack<>();
        undoStack = new Stack<>();
        currentMoveIndex = -1;
        lastMove = null;
        koMove = null;
        blackPlayer = "黑方";
        whitePlayer = "白方";
        result = "";
        date = "";
        handicap = 0;
        blackHandicapStones = new ArrayList<>();
        whiteHandicapStones = new ArrayList<>();
        marks = new ArrayList<>();
        crossMarks = new ArrayList<>();
        squareMarks = new ArrayList<>();
        triangleMarks = new ArrayList<>();
        gameTreeRoot = null;
        currentNode = null;
    }
    
    // ==================== 游戏树操作 ====================
    
    /**
     * 设置游戏树根节点
     */
    public void setGameTreeRoot(SGFNode root) {
        System.out.println("=== setGameTreeRoot ===");
        System.out.println("根节点有 " + root.children.size() + " 个子节点");

        this.gameTreeRoot = root;

        // 根节点通常不包含走子，currentNode 应该设置为根节点
        // 用户需要选择一个分支后，currentNode 才会移动到该分支的第一手
        this.currentNode = root;

        System.out.println("currentNode已设置到根节点");
        System.out.println("根节点子节点有走子的情况:");
        for (int i = 0; i < Math.min(5, root.children.size()); i++) {
            SGFNode child = root.children.get(i);
            if (child.move != null && child.move.x != -1 && child.move.y != -1) {
                System.out.println("  子节点 #" + i + ": " + child.move.x + "," + child.move.y +
                    (child.move.player == BLACK ? " 黑" : " 白"));
            }
        }
        if (root.children.size() > 5) {
            System.out.println("  ... (共 " + root.children.size() + " 个子节点)");
        }
        System.out.println("====================");

        rebuildBoardFromTree();
    }
    
    /**
     * 获取游戏树根节点
     */
    public SGFNode getGameTreeRoot() {
        return gameTreeRoot;
    }
    
    /**
     * 获取当前节点
     */
    public SGFNode getCurrentNode() {
        return currentNode;
    }
    
    /**
     * 设置当前节点
     */
    public void setCurrentNode(SGFNode node) {
        this.currentNode = node;
        rebuildBoardFromTree();
    }
    
    /**
     * 从游戏树重建盘面状态（从根节点到当前节点）
     */
    private void rebuildBoardFromTree() {
        System.out.println("rebuildBoardFromTree 开始");

        // 清空棋盘
        resetBoard();
        applyHandicapStones();

        // 收集从根节点到当前节点的所有走子
        java.util.List<SGFNode> path = new java.util.ArrayList<>();
        SGFNode tempNode = currentNode;

        // 从当前节点向上遍历到根节点（不包含根节点，因为根节点没有走子）
        while (tempNode != null && tempNode != gameTreeRoot) {
            path.add(0, tempNode);
            tempNode = tempNode.parent;
        }

        System.out.println("从根节点到当前节点的路径长度: " + path.size());

        // 应用走子
        moveHistory.clear();
        for (SGFNode pathNode : path) {
            if (pathNode.move != null) {
                if (pathNode.move.x != -1 && pathNode.move.y != -1) {
                    board[pathNode.move.y][pathNode.move.x] = pathNode.move.player;
                    moveHistory.add(pathNode.move);
                    System.out.println("应用走子: " + pathNode.move.x + "," + pathNode.move.y + " " +
                        (pathNode.move.player == BLACK ? "黑" : "白"));
                }
            }
        }

        currentMoveIndex = moveHistory.size() - 1;
        lastMove = moveHistory.isEmpty() ? null : moveHistory.get(currentMoveIndex);

        // 清除打劫标记，允许在原打劫点落子
        koMove = null;

        // 根据 actualMoveCount 和 handicap 判断 currentPlayer
        int actualMoveCount = 0;
        for (Move move : moveHistory) {
            if (move.x != -1 && move.y != -1) {
                actualMoveCount++;
            }
        }

        // 让子棋(handicap > 0): 白先手；普通棋局: 黑先手
        if (handicap > 0) {
            currentPlayer = (actualMoveCount % 2 == 0) ? WHITE : BLACK;
        } else {
            currentPlayer = (actualMoveCount % 2 == 0) ? BLACK : WHITE;
        }

        System.out.println("rebuildBoardFromTree 完成, currentMoveIndex=" + currentMoveIndex +
            ", currentPlayer=" + (currentPlayer == BLACK ? "黑" : "白") +
            ", moveHistory.size()=" + moveHistory.size() +
            ", actualMoveCount=" + actualMoveCount);
    }
    
    /**
     * 上一步：移动到父节点
     */
    public boolean previousMove() {
        System.out.println("=== previousMove ===");
        System.out.println("gameTreeRoot: " + (gameTreeRoot != null));
        System.out.println("currentNode: " + (currentNode != null ? "exists" : "null"));
        if (currentNode != null) {
            System.out.println("currentNode.move: " + (currentNode.move != null ?
                currentNode.move.x + "," + currentNode.move.y : "null"));
            System.out.println("currentNode.children.size: " + currentNode.children.size());
            System.out.println("currentNode == gameTreeRoot: " + (currentNode == gameTreeRoot));
        }

        if (gameTreeRoot == null) {
            // 没有加载游戏树，从当前的moveHistory构建游戏树
            buildGameTreeFromHistory();
            if (gameTreeRoot == null) {
                // 如果构建失败，使用旧方式回退
                undo();
                return true;
            }
            // 游戏树已构建，继续使用游戏树方式回退
        }

        if (currentNode == null) {
            // 当前节点为空，设置为根节点
            currentNode = gameTreeRoot;
            rebuildBoardFromTree();
            return true;
        }

        if (currentNode == gameTreeRoot) {
            // 已经在根节点，刷新棋盘以显示分支虚影
            rebuildBoardFromTree();
            return true;
        }

        // 移动到父节点
        currentNode = currentNode.parent;
        System.out.println("返回父节点后的状态:");
        System.out.println("currentNode.move: " + (currentNode.move != null ?
            currentNode.move.x + "," + currentNode.move.y : "null"));
        System.out.println("currentNode.children.size: " + currentNode.children.size());
        rebuildBoardFromTree();
        return true;
    }

    /**
     * 从当前的moveHistory构建游戏树
     */
    private void buildGameTreeFromHistory() {
        if (moveHistory.isEmpty()) {
            return;
        }

        System.out.println("=== buildGameTreeFromHistory ===");
        System.out.println("moveHistory.size: " + moveHistory.size());

        // 创建根节点
        gameTreeRoot = new SGFNode(null);
        currentNode = gameTreeRoot;

        // 逐个添加走子到游戏树
        for (Move move : moveHistory) {
            SGFNode newNode = new SGFNode(currentNode);
            newNode.move = move;
            currentNode.children.add(newNode);
            currentNode = newNode;
            System.out.println("添加走子到游戏树: " + move.x + "," + move.y + " " +
                (move.player == BLACK ? "黑" : "白"));
        }

        // 将currentNode设置回最后一个节点
        // rebuildBoardFromTree会重新设置currentMoveIndex和currentPlayer

        System.out.println("游戏树构建完成，根节点有 " + gameTreeRoot.children.size() + " 个子节点");
        System.out.println("====================");
    }

    /**
     * 下一步：移动到第一个子节点
     */
    public boolean nextMove() {
        System.out.println("=== nextMove ===");
        System.out.println("currentNode: " + (currentNode != null ? "exists" : "null"));
        if (currentNode != null) {
            System.out.println("currentNode.children.size: " + currentNode.children.size());
        }

        if (currentNode == null || currentNode.children.isEmpty()) {
            System.out.println("已经到达最后一步");
            return false;
        }
        
        currentNode = currentNode.children.get(0);
        rebuildBoardFromTree();
        return true;
    }
    
    /**
     * 获取当前步骤的所有分支（返回所有颜色的分支）
     */
    public List<Move> getBranchMoves() {
        List<Move> branchMoves = new ArrayList<>();

        if (currentNode == null) {
            System.out.println("getBranchMoves: 当前节点为空，没有分支");
            return branchMoves;
        }

        System.out.println("=== getBranchMoves 调试 ===");
        System.out.println("当前节点有 " + currentNode.children.size() + " 个子节点");
        System.out.println("currentPlayer=" + (currentPlayer == BLACK ? "黑" : "白"));

        // 打印当前节点信息
        if (currentNode.move != null) {
            System.out.println("当前节点走子: " + currentNode.move.x + "," + currentNode.move.y + " " +
                (currentNode.move.player == BLACK ? "黑" : "白"));
        } else {
            System.out.println("当前节点无走子（根节点）");
        }

        // 遍历所有子节点，返回所有分支（不限于当前颜色）
        for (int i = 0; i < currentNode.children.size(); i++) {
            SGFNode childNode = currentNode.children.get(i);
            if (childNode.move != null && childNode.move.x != -1 && childNode.move.y != -1) {
                branchMoves.add(childNode.move);
                System.out.println("  添加分支" + i + ": " + childNode.move.x + "," + childNode.move.y + " " +
                    (childNode.move.player == BLACK ? "黑" : "白") + " (有 " + childNode.children.size() + " 个子节点)");
            }
        }

        System.out.println("总共返回: " + branchMoves.size() + " 个分支（显示所有颜色）");
        System.out.println("======================");
        return branchMoves;
    }
    
    /**
     * 选择并切换到指定分支
     */
    public boolean selectBranchMove(Move branchMove) {
        System.out.println("selectBranchMove 被调用: " + branchMove.x + "," + branchMove.y);

        if (currentNode == null) {
            System.out.println("当前节点为空，无法选择分支");
            return false;
        }

        // 在当前节点的子节点中查找匹配的分支
        for (int i = 0; i < currentNode.children.size(); i++) {
            SGFNode childNode = currentNode.children.get(i);
            if (childNode.move != null && 
                childNode.move.x == branchMove.x && 
                childNode.move.y == branchMove.y) {
                
                System.out.println("找到匹配的分支: 子节点" + i);
                
                // 切换到该子节点
                currentNode = childNode;
                rebuildBoardFromTree();
                
                System.out.println("切换到分支完成");
                return true;
            }
        }

        System.out.println("未找到匹配的分支");
        return false;
    }
    
    // ==================== 打谱操作 ====================
    
    /**
     * 打谱时落子：在当前节点后创建新节点
     */
    public boolean placeStone(int x, int y) {
        System.out.println("=== placeStone ===");
        System.out.println("x=" + x + ", y=" + y);
        System.out.println("currentPlayer=" + (currentPlayer == BLACK ? "黑" : "白"));
        System.out.println("koMove: " + (koMove != null ? koMove.x + "," + koMove.y : "null"));

        // 虚手处理
        if (x == -1 && y == -1) {
            return placePassStone();
        }

        // 边界检查
        if (x < 0 || x >= BOARD_SIZE || y < 0 || y >= BOARD_SIZE) {
            System.out.println("边界检查失败");
            return false;
        }

        // 检查是否是打劫
        if (koMove != null && koMove.x == x && koMove.y == y) {
            System.out.println("打劫检查失败");
            return false;
        }

        // 检查是否有棋子
        if (board[y][x] != EMPTY) {
            System.out.println("位置已有棋子");
            return false;
        }

        // 模拟落子
        int[][] tempBoard = copyBoard(board);
        tempBoard[y][x] = currentPlayer;

        // 检查是否自杀
        if (hasLiberty(tempBoard, x, y, currentPlayer)) {
            // 提子
            List<Position> capturedStones = captureStones(tempBoard, x, y);

            // 再次检查是否自杀（提子后）
            if (hasLiberty(tempBoard, x, y, currentPlayer)) {
                // 执行落子
                board = tempBoard;
                Move move = new Move(x, y, currentPlayer, capturedStones);
                moveHistory.add(move);
                lastMove = move;
                currentMoveIndex = moveHistory.size() - 1;

                // 检查是否形成打劫
                if (capturedStones.size() == 1) {
                    koMove = new Move(x, y, currentPlayer);
                } else {
                    koMove = null;
                }

                // 如果有游戏树，创建或选择节点（只创建一次）
                if (currentNode != null) {
                    addMoveToGameTree(move);
                }

                switchPlayer();
                return true;
            }
        }

        return false;
    }

    /**
     * 虚手处理
     */
    private boolean placePassStone() {
        Move passMove = new Move(-1, -1, currentPlayer);
        moveHistory.add(passMove);
        lastMove = passMove;
        currentMoveIndex = moveHistory.size() - 1;
        koMove = null;

        // 如果有游戏树，创建或选择节点
        if (currentNode != null) {
            addMoveToGameTree(passMove);
        }

        switchPlayer();
        return true;
    }

    /**
     * 将走子添加到游戏树（统一处理分支创建逻辑）
     * 如果子节点中已存在相同走子，则切换到该节点
     * 否则创建新节点
     */
    private void addMoveToGameTree(Move move) {
        if (currentNode == null) {
            return;
        }

        // 在现有子节点中查找匹配的走子
        for (SGFNode child : currentNode.children) {
            if (child.move != null &&
                child.move.x == move.x &&
                child.move.y == move.y &&
                child.move.player == move.player) {
                // 找到匹配的节点，直接切换到该节点
                currentNode = child;
                System.out.println("切换到现有节点: " + move.x + "," + move.y + " " +
                    (move.player == BLACK ? "黑" : "白"));
                return;
            }
        }

        // 没有找到匹配的节点，创建新节点
        System.out.println("创建新节点: " + move.x + "," + move.y + " " +
            (move.player == BLACK ? "黑" : "白"));

        SGFNode newNode = new SGFNode(currentNode);
        newNode.move = move;
        currentNode.children.add(newNode);
        currentNode = newNode;

        if (currentNode.parent.children.size() > 1) {
            System.out.println("新分支已创建，当前节点有 " +
                currentNode.parent.children.size() + " 个兄弟节点");
        }
    }
    
    /**
     * 重置到起始位置
     */
    public void resetToStart() {
        if (gameTreeRoot != null) {
            currentNode = gameTreeRoot;
            rebuildBoardFromTree();
            System.out.println("resetToStart: currentNode已重置到根节点");
        } else {
            currentMoveIndex = -1;
            resetBoard();
            applyHandicapStones();
            lastMove = null;
            currentPlayer = (handicap > 0) ? WHITE : BLACK;
            koMove = null;
        }
    }
    
    // ==================== 基础游戏逻辑 ====================
    
    private boolean hasLiberty(int[][] board, int x, int y, int player) {
        boolean[][] visited = new boolean[BOARD_SIZE][BOARD_SIZE];
        return checkLiberty(board, x, y, player, visited);
    }
    
    private boolean checkLiberty(int[][] board, int x, int y, int player, boolean[][] visited) {
        if (x < 0 || x >= BOARD_SIZE || y < 0 || y >= BOARD_SIZE) {
            return false;
        }
        
        if (visited[y][x]) {
            return false;
        }
        
        visited[y][x] = true;
        
        if (board[y][x] == EMPTY) {
            return true;
        }
        
        if (board[y][x] != player) {
            return false;
        }
        
        // 检查四个方向
        return checkLiberty(board, x + 1, y, player, visited) ||
               checkLiberty(board, x - 1, y, player, visited) ||
               checkLiberty(board, x, y + 1, player, visited) ||
               checkLiberty(board, x, y - 1, player, visited);
    }
    
    private List<Position> captureStones(int[][] board, int x, int y) {
        List<Position> capturedStones = new ArrayList<>();
        int opponent = currentPlayer == BLACK ? WHITE : BLACK;
        
        // 检查四个方向的对手棋子
        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] dir : directions) {
            int nx = x + dir[0];
            int ny = y + dir[1];
            
            if (nx >= 0 && nx < BOARD_SIZE && ny >= 0 && ny < BOARD_SIZE && 
                board[ny][nx] == opponent) {
                
                List<Position> group = getGroup(board, nx, ny, opponent);
                if (!hasLiberty(board, nx, ny, opponent)) {
                    // 提子
                    for (Position pos : group) {
                        board[pos.y][pos.x] = EMPTY;
                        capturedStones.add(pos);
                    }
                }
            }
        }
        
        return capturedStones;
    }
    
    private List<Position> getGroup(int[][] board, int x, int y, int player) {
        List<Position> group = new ArrayList<>();
        boolean[][] visited = new boolean[BOARD_SIZE][BOARD_SIZE];
        collectGroup(board, x, y, player, group, visited);
        return group;
    }
    
    private void collectGroup(int[][] board, int x, int y, int player, List<Position> group, boolean[][] visited) {
        if (x < 0 || x >= BOARD_SIZE || y < 0 || y >= BOARD_SIZE) {
            return;
        }
        
        if (visited[y][x] || board[y][x] != player) {
            return;
        }
        
        visited[y][x] = true;
        group.add(new Position(x, y));
        
        // 检查四个方向
        collectGroup(board, x + 1, y, player, group, visited);
        collectGroup(board, x - 1, y, player, group, visited);
        collectGroup(board, x, y + 1, player, group, visited);
        collectGroup(board, x, y - 1, player, group, visited);
    }
    
    private void switchPlayer() {
        currentPlayer = currentPlayer == BLACK ? WHITE : BLACK;
    }
    
    private int[][] copyBoard(int[][] original) {
        int[][] copy = new int[BOARD_SIZE][BOARD_SIZE];
        for (int y = 0; y < BOARD_SIZE; y++) {
            System.arraycopy(original[y], 0, copy[y], 0, BOARD_SIZE);
        }
        return copy;
    }
    
    public void resetBoard() {
        for (int y = 0; y < BOARD_SIZE; y++) {
            for (int x = 0; x < BOARD_SIZE; x++) {
                board[y][x] = EMPTY;
            }
        }
    }
    
    public void applyHandicapStones() {
        // 应用黑棋座子
        for (Position pos : blackHandicapStones) {
            board[pos.y][pos.x] = BLACK;
        }
        // 应用白棋座子
        for (Position pos : whiteHandicapStones) {
            board[pos.y][pos.x] = WHITE;
        }
    }
    
    public void addBlackHandicapStone(int x, int y) {
        blackHandicapStones.add(new Position(x, y));
        board[y][x] = BLACK;
    }
    
    public void addWhiteHandicapStone(int x, int y) {
        whiteHandicapStones.add(new Position(x, y));
        board[y][x] = WHITE;
    }
    
    public void clearHandicapStones() {
        blackHandicapStones.clear();
        whiteHandicapStones.clear();
    }

    /**
     * 移除指定位置的棋子（用于摆子模式）
     */
    public void removeHandicapStone(int x, int y) {
        // 检查黑棋列表
        Position blackToRemove = null;
        for (Position pos : blackHandicapStones) {
            if (pos.x == x && pos.y == y) {
                blackToRemove = pos;
                break;
            }
        }
        if (blackToRemove != null) {
            blackHandicapStones.remove(blackToRemove);
            board[y][x] = EMPTY;
            return;
        }

        // 检查白棋列表
        Position whiteToRemove = null;
        for (Position pos : whiteHandicapStones) {
            if (pos.x == x && pos.y == y) {
                whiteToRemove = pos;
                break;
            }
        }
        if (whiteToRemove != null) {
            whiteHandicapStones.remove(whiteToRemove);
            board[y][x] = EMPTY;
        }
    }

    public void setupHandicap(int handicapCount) {
        if (handicapCount < 1 || handicapCount > 9) {
            return;
        }
        
        handicap = handicapCount;
        List<Position> handicapPositions = getHandicapPositions(handicapCount);
        
        for (Position pos : handicapPositions) {
            board[pos.y][pos.x] = BLACK;
        }
        
        // 让子后白方先行
        currentPlayer = WHITE;
    }
    
    private List<Position> getHandicapPositions(int handicapCount) {
        List<Position> positions = new ArrayList<>();
        
        // 星位点坐标
        int[] starPoints = {3, 9, 15};
        
        switch (handicapCount) {
            case 1:
                positions.add(new Position(9, 9)); // 天元
                break;
            case 2:
                positions.add(new Position(3, 3));
                positions.add(new Position(15, 15));
                break;
            case 3:
                positions.add(new Position(3, 3));
                positions.add(new Position(15, 15));
                positions.add(new Position(3, 15));
                break;
            case 4:
                positions.add(new Position(3, 3));
                positions.add(new Position(15, 15));
                positions.add(new Position(3, 15));
                positions.add(new Position(15, 3));
                break;
            case 5:
                positions.add(new Position(3, 3));
                positions.add(new Position(15, 15));
                positions.add(new Position(3, 15));
                positions.add(new Position(15, 3));
                positions.add(new Position(9, 9));
                break;
            case 6:
                positions.add(new Position(3, 3));
                positions.add(new Position(15, 15));
                positions.add(new Position(3, 15));
                positions.add(new Position(15, 3));
                positions.add(new Position(3, 9));
                positions.add(new Position(15, 9));
                break;
            case 7:
                positions.add(new Position(3, 3));
                positions.add(new Position(15, 15));
                positions.add(new Position(3, 15));
                positions.add(new Position(15, 3));
                positions.add(new Position(3, 9));
                positions.add(new Position(15, 9));
                positions.add(new Position(9, 9));
                break;
            case 8:
                positions.add(new Position(3, 3));
                positions.add(new Position(15, 15));
                positions.add(new Position(3, 15));
                positions.add(new Position(15, 3));
                positions.add(new Position(3, 9));
                positions.add(new Position(15, 9));
                positions.add(new Position(9, 3));
                positions.add(new Position(9, 15));
                break;
            case 9:
                positions.add(new Position(3, 3));
                positions.add(new Position(15, 15));
                positions.add(new Position(3, 15));
                positions.add(new Position(15, 3));
                positions.add(new Position(3, 9));
                positions.add(new Position(15, 9));
                positions.add(new Position(9, 3));
                positions.add(new Position(9, 15));
                positions.add(new Position(9, 9));
                break;
        }
        
        return positions;
    }
    
    public void newGame() {
        System.out.println("=== newGame 被调用 ===");
        initializeBoard();
        // 初始化游戏树根节点（支持分支功能）
        gameTreeRoot = new SGFNode(null);
        currentNode = gameTreeRoot;
        System.out.println("newGame: 已初始化游戏树根节点");
        System.out.println("gameTreeRoot=" + (gameTreeRoot != null));
        System.out.println("currentNode=" + (currentNode != null));
        // 应用让子
        applyHandicapStones();
        // 设置正确的执黑/执白
        currentPlayer = (handicap > 0) ? WHITE : BLACK;
        System.out.println("=== newGame 完成 ===");
    }
    
    // ==================== Getters and Setters ====================
    
    public int[][] getBoard() {
        return board;
    }
    
    public int getCurrentPlayer() {
        return currentPlayer;
    }

    public void setCurrentPlayer(int player) {
        if (player == BLACK || player == WHITE) {
            this.currentPlayer = player;
        }
    }

    public List<Move> getMoveHistory() {
        return moveHistory;
    }

    public void clearMoveHistory() {
        moveHistory.clear();
        currentMoveIndex = -1;
        lastMove = null;
    }

    public int getCurrentMoveIndex() {
        return currentMoveIndex;
    }

    public Move getLastMove() {
        return lastMove;
    }
    
    public String getBlackPlayer() {
        return blackPlayer;
    }
    
    public void setBlackPlayer(String blackPlayer) {
        this.blackPlayer = blackPlayer;
    }
    
    public String getWhitePlayer() {
        return whitePlayer;
    }
    
    public void setWhitePlayer(String whitePlayer) {
        this.whitePlayer = whitePlayer;
    }
    
    public String getResult() {
        return result;
    }
    
    public void setResult(String result) {
        this.result = result;
    }
    
    public String getDate() {
        return date;
    }
    
    public void setDate(String date) {
        this.date = date;
    }

    public int getHandicap() {
        return handicap;
    }

    public void setHandicap(int handicap) {
        this.handicap = handicap;
    }

    /**
     * 获取黑棋让子列表
     */
    public List<Position> getBlackHandicapStones() {
        return blackHandicapStones;
    }

    /**
     * 获取白棋让子列表
     */
    public List<Position> getWhiteHandicapStones() {
        return whiteHandicapStones;
    }

    /**
     * 添加标记
     */
    public void addMark(int x, int y) {
        // 检查是否已存在
        for (Position pos : marks) {
            if (pos.x == x && pos.y == y) {
                return; // 已存在，不重复添加
            }
        }
        marks.add(new Position(x, y));
    }

    /**
     * 移除标记
     */
    public void removeMark(int x, int y) {
        Position toRemove = null;
        for (Position pos : marks) {
            if (pos.x == x && pos.y == y) {
                toRemove = pos;
                break;
            }
        }
        if (toRemove != null) {
            marks.remove(toRemove);
        }
    }

    /**
     * 获取标记列表
     */
    public List<Position> getMarks() {
        return marks;
    }

    /**
     * 添加叉号标记
     */
    public void addCrossMark(int x, int y) {
        for (Position pos : crossMarks) {
            if (pos.x == x && pos.y == y) return;
        }
        crossMarks.add(new Position(x, y));
    }

    /**
     * 移除叉号标记
     */
    public void removeCrossMark(int x, int y) {
        Position toRemove = null;
        for (Position pos : crossMarks) {
            if (pos.x == x && pos.y == y) {
                toRemove = pos;
                break;
            }
        }
        if (toRemove != null) crossMarks.remove(toRemove);
    }

    public List<Position> getCrossMarks() { return crossMarks; }

    /**
     * 添加方块标记
     */
    public void addSquareMark(int x, int y) {
        for (Position pos : squareMarks) {
            if (pos.x == x && pos.y == y) return;
        }
        squareMarks.add(new Position(x, y));
    }

    /**
     * 移除方块标记
     */
    public void removeSquareMark(int x, int y) {
        Position toRemove = null;
        for (Position pos : squareMarks) {
            if (pos.x == x && pos.y == y) {
                toRemove = pos;
                break;
            }
        }
        if (toRemove != null) squareMarks.remove(toRemove);
    }

    public List<Position> getSquareMarks() { return squareMarks; }

    /**
     * 添加三角形标记
     */
    public void addTriangleMark(int x, int y) {
        for (Position pos : triangleMarks) {
            if (pos.x == x && pos.y == y) return;
        }
        triangleMarks.add(new Position(x, y));
    }

    /**
     * 移除三角形标记
     */
    public void removeTriangleMark(int x, int y) {
        Position toRemove = null;
        for (Position pos : triangleMarks) {
            if (pos.x == x && pos.y == y) {
                toRemove = pos;
                break;
            }
        }
        if (toRemove != null) triangleMarks.remove(toRemove);
    }

    public List<Position> getTriangleMarks() { return triangleMarks; }

    /**
     * 清除所有标记
     */
    public void clearMarks() {
        marks.clear();
        crossMarks.clear();
        squareMarks.clear();
        triangleMarks.clear();
    }

    /**
     * 获取当前节点的注释
     */
    public String getCurrentComment() {
        if (currentNode != null && currentNode.comment != null) {
            return currentNode.comment;
        }
        return "";
    }

    /**
     * 设置当前节点的注释
     */
    public void setCurrentComment(String comment) {
        if (currentNode != null) {
            currentNode.comment = comment;
            System.out.println("已设置注释: " + comment);
        }
    }

    /**
     * 删除指定的分支节点
     */
    public boolean deleteBranch(Move branchMove) {
        System.out.println("=== deleteBranch ===");
        System.out.println("gameTreeRoot: " + (gameTreeRoot != null));
        System.out.println("currentNode: " + (currentNode != null));
        if (currentNode != null) {
            System.out.println("currentNode.move: " + (currentNode.move != null ?
                currentNode.move.x + "," + currentNode.move.y : "null"));
        }

        if (gameTreeRoot == null) {
            System.out.println("gameTreeRoot为空，无法删除分支");
            return false;
        }

        if (currentNode == null) {
            System.out.println("currentNode为空，无法删除分支");
            return false;
        }

        // 在游戏树中查找要删除的节点及其父节点
        System.out.println("开始查找分支: " + branchMove.x + "," + branchMove.y + " player=" + branchMove.player);
        DeleteResult result = findNodeAndParent(null, gameTreeRoot, branchMove);
        if (result == null || result.parent == null) {
            System.out.println("未找到要删除的分支");
            // 打印游戏树结构
            printTreeStructure(gameTreeRoot, 0);
            return false;
        }

        SGFNode parentNode = result.parent;
        SGFNode targetNode = result.node;

        System.out.println("找到要删除的节点: " + branchMove.x + "," + branchMove.y);
        System.out.println("父节点有 " + parentNode.children.size() + " 个子节点");

        // 如果删除的是当前节点，先移动到父节点
        if (targetNode == currentNode) {
            System.out.println("删除的是当前节点，移动到父节点");
            currentNode = parentNode;
            rebuildBoardFromTree();
        } else if (isAncestorOfCurrent(targetNode)) {
            // 如果删除的是当前节点的祖先节点，移动到父节点
            System.out.println("删除的是当前节点的祖先节点");
            currentNode = parentNode;
            rebuildBoardFromTree();
        }

        // 从父节点中删除子节点
        parentNode.children.remove(targetNode);
        System.out.println("分支已删除: " + branchMove.x + "," + branchMove.y);
        System.out.println("====================");
        return true;
    }

    /**
     * 查找节点及其父节点的结果类
     */
    private static class DeleteResult {
        SGFNode parent;
        SGFNode node;

        DeleteResult(SGFNode parent, SGFNode node) {
            this.parent = parent;
            this.node = node;
        }
    }

    /**
     * 在游戏树中查找指定节点及其父节点
     */
    private DeleteResult findNodeAndParent(SGFNode parent, SGFNode node, Move branchMove) {
        System.out.println("findNodeAndParent: 检查节点 " + (node.move != null ? node.move.x + "," + node.move.y : "null"));
        System.out.println("  查找目标: " + branchMove.x + "," + branchMove.y + " player=" + branchMove.player);
        System.out.println("  该节点有 " + node.children.size() + " 个子节点");

        for (SGFNode child : node.children) {
            System.out.println("  检查子节点: " + (child.move != null ? child.move.x + "," + child.move.y + " player=" + child.move.player : "null"));

            if (child.move != null &&
                child.move.x == branchMove.x &&
                child.move.y == branchMove.y &&
                child.move.player == branchMove.player) {
                System.out.println("  找到匹配！");
                return new DeleteResult(node, child);
            }
            // 递归搜索子节点
            DeleteResult found = findNodeAndParent(child, child, branchMove);
            if (found != null) {
                return found;
            }
        }
        System.out.println("  未找到");
        return null;
    }

    /**
     * 检查node是否是currentNode的祖先节点
     */
    /**
     * 打印游戏树结构（用于调试）
     */
    private void printTreeStructure(SGFNode node, int depth) {
        String indent = "";
        for (int i = 0; i < depth; i++) indent += "  ";

        String moveInfo = "根节点";
        if (node.move != null) {
            moveInfo = node.move.x + "," + node.move.y + " " + (node.move.player == BLACK ? "黑" : "白");
        }
        System.out.println(indent + "节点: " + moveInfo + " (" + node.children.size() + "个子节点)");

        for (SGFNode child : node.children) {
            printTreeStructure(child, depth + 1);
        }
    }

    private boolean isAncestorOfCurrent(SGFNode node) {
        SGFNode temp = currentNode;
        while (temp != null) {
            if (temp == node) {
                return true;
            }
            temp = temp.parent;
        }
        return false;
    }

    /**
     * 悔棋：撤销上一步
     */
    public void undo() {
        if (gameTreeRoot != null && currentNode != null && currentNode.parent != null) {
            // 使用游戏树撤销
            currentNode = currentNode.parent;
            rebuildBoardFromTree();
        } else if (currentMoveIndex >= 0 && !moveHistory.isEmpty()) {
            // 使用 moveHistory 撤销
            Move lastMove = moveHistory.get(currentMoveIndex);

            // 撤销提子
            if (lastMove.capturedStones != null) {
                int opponent = lastMove.player == BLACK ? WHITE : BLACK;
                for (Position pos : lastMove.capturedStones) {
                    board[pos.y][pos.x] = opponent;
                }
            }

            // 撤销落子
            if (lastMove.x >= 0 && lastMove.y >= 0) {
                board[lastMove.y][lastMove.x] = EMPTY;
            }

            currentMoveIndex--;
            currentPlayer = lastMove.player;

            // 更新最后一步
            if (currentMoveIndex >= 0) {
                this.lastMove = moveHistory.get(currentMoveIndex);
            } else {
                this.lastMove = null;
            }

            // 清除打劫标记
            koMove = null;
        }
    }

    // ==================== 内部类 ====================
    
    public static class Move {
        public int x;
        public int y;
        public int player;
        public List<Position> capturedStones;
        
        public Move(int x, int y, int player) {
            this.x = x;
            this.y = y;
            this.player = player;
            this.capturedStones = new ArrayList<>();
        }
        
        public Move(int x, int y, int player, List<Position> capturedStones) {
            this.x = x;
            this.y = y;
            this.player = player;
            this.capturedStones = capturedStones;
        }
        
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            Move other = (Move) obj;
            return x == other.x && y == other.y && player == other.player;
        }
        
        @Override
        public int hashCode() {
            return x * 100 + y * 10 + player;
        }
    }
    
    public static class Position {
        public int x;
        public int y;
        
        public Position(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }
}
