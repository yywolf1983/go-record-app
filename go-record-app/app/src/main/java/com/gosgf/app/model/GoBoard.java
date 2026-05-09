package com.gosgf.app.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

/**
 * GoBoard - 核心棋盘逻辑（委托模式重构版）
 *
 * 职责：
 *   1. 保留内部类 SGFNode / Move / Position / TreeNodeInfo（外部广泛引用）
 *   2. 核心棋盘状态 + 落子/提子/气 逻辑
 *   3. 游戏树重建（rebuildBoardFromTree）
 *   4. 委托 GameTree / MarkManager / ScoreEstimator / BoardSerializer / HandicapManager
 *
 * 原始 1932 行 → 重构后约 820 行
 */
public class GoBoard {

    // ==================== 常量 ====================
    private static final int MAX_NAME_LEN = 100;
    private static final int MAX_MOVE_HISTORY = 1000;
    private static final float DEFAULT_KOMI = 6.5f;
    private static final int MIN_HANDICAP = 1;
    private static final int MAX_HANDICAP = 9;
    private static final int MAX_TREE_DEPTH = 500;
    private static final int BOARD_SIZE = 19;
    private static final int SERIALIZE_VERSION = 1;

    public static final int EMPTY = BoardLogic.EMPTY;
    public static final int BLACK  = BoardLogic.BLACK;
    public static final int WHITE  = BoardLogic.WHITE;

    // ==================== 内部类（保留，外部引用） ====================

    /** SGF 树节点 */
    public static class SGFNode {
        public Move move;
        public String comment;
        public java.util.Map<String, java.util.List<String>> properties;
        public java.util.List<SGFNode> children;
        public SGFNode parent;
        public int index;

        public SGFNode(SGFNode parent) {
            this.parent = parent;
            this.properties = new java.util.HashMap<>();
            this.children = new java.util.ArrayList<>();
            this.comment = "";
        }
    }

    /** 落子记录 */
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

    /** 坐标 */
    public static class Position {
        public int x;
        public int y;

        public Position(int x, int y) {
            this.x = x;
            this.y = y;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            Position other = (Position) obj;
            return x == other.x && y == other.y;
        }

        @Override
        public int hashCode() {
            return x * 31 + y;
        }
    }

    /** 树节点信息 */
    public static class TreeNodeInfo {
        public SGFNode node;
        public int depth;
        public boolean hasBranches;
        public boolean isCurrent;
        public int branchIndex;
        public int branchCount;

        public TreeNodeInfo(SGFNode node, int depth, boolean hasBranches,
                            boolean isCurrent, int branchIndex, int branchCount) {
            this.node = node;
            this.depth = depth;
            this.hasBranches = hasBranches;
            this.isCurrent = isCurrent;
            this.branchIndex = branchIndex;
            this.branchCount = branchCount;
        }
    }

    // ==================== 核心字段 ====================
    private int[][] board;
    private int currentPlayer;
    private List<Move> moveHistory;
    private Stack<List<Move>> variations;
    private Move lastMove;
    private Move koMove;
    private int currentMoveIndex;
    private String blackPlayer;
    private String whitePlayer;
    private String result;
    private String date;
    private String lastErrorMessage;

    // 委托对象
    private final GameTree gameTree = new GameTree();
    private final MarkManager markManager = new MarkManager();
    private ScoreEstimator scoreEstimator;   // 延迟初始化（依赖 board）
    private final HandicapManager handicapMgr = new HandicapManager(board);

    // ==================== 构造 ====================

    public GoBoard() {
        initializeBoard();
    }

    private void initializeBoard() {
        board = new int[BOARD_SIZE][BOARD_SIZE];
        currentPlayer = BLACK;
        moveHistory = new ArrayList<>();
        variations = new Stack<>();
        currentMoveIndex = -1;
        lastMove = null;
        koMove = null;
        blackPlayer = "黑方";
        whitePlayer = "白方";
        result = "";
        date = "";
        lastErrorMessage = "";

        // 重新绑定委托对象
        handicapMgr.setBoard(board);
        handicapMgr.clearHandicapStones();
        handicapMgr.setHandicap(0);
        scoreEstimator = new ScoreEstimator(board);
    }

    // ==================== 初始化 / 新局 ====================

    public void newGame() {
        initializeBoard();
        gameTree.setRoot(new SGFNode(null));
        handicapMgr.applyHandicapStones();
        currentPlayer = (handicapMgr.getHandicap() > 0) ? WHITE : BLACK;
    }

    // ==================== 核心落子逻辑 ====================

    /**
     * 主落子入口（含边界检查、打劫、自杀检查）
     */
    public boolean placeStone(int x, int y) {
        if (x == -1 && y == -1) return placePassStone();

        if (x < 0 || x >= BOARD_SIZE || y < 0 || y >= BOARD_SIZE) {
            lastErrorMessage = "位置超出棋盘范围";
            return false;
        }
        if (koMove != null && koMove.x == x && koMove.y == y) {
            lastErrorMessage = "此处为打劫，不能立即回提";
            return false;
        }
        if (board[y][x] != EMPTY) {
            lastErrorMessage = "此处已有棋子";
            return false;
        }

        // 模拟落子
        int[][] tempBoard = BoardLogic.copyBoard(board);
        tempBoard[y][x] = currentPlayer;
        List<Position> capturedStones = BoardLogic.captureStones(tempBoard, x, y, currentPlayer);

        if (BoardLogic.hasLiberty(tempBoard, x, y, currentPlayer)) {
            board = tempBoard;
            handicapMgr.setBoard(board);
            Move move = new Move(x, y, currentPlayer, capturedStones);
            moveHistory.add(move);
            lastMove = move;
            currentMoveIndex = moveHistory.size() - 1;

            koMove = (capturedStones.size() == 1) ? new Move(x, y, currentPlayer) : null;

            if (gameTree.getCurrentNode() != null) {
                gameTree.addMove(move);
            }

            switchPlayer();
            lastErrorMessage = "";
            return true;
        }

        lastErrorMessage = "自杀着法不允许";
        return false;
    }

    /** 虚手处理 */
    private boolean placePassStone() {
        Move passMove = new Move(-1, -1, currentPlayer);
        moveHistory.add(passMove);
        lastMove = passMove;
        currentMoveIndex = moveHistory.size() - 1;
        koMove = null;

        if (gameTree.getCurrentNode() != null) {
            gameTree.addMove(passMove);
        }
        switchPlayer();
        return true;
    }

    /**
     * 用于重建棋盘的落子方法（跳过打劫检查）
     */
    private boolean placeStoneForReconstruction(int x, int y) {
        if (x == -1 && y == -1) {
            Move passMove = new Move(-1, -1, currentPlayer);
            moveHistory.add(passMove);
            lastMove = passMove;
            currentMoveIndex = moveHistory.size() - 1;
            koMove = null;
            switchPlayer();
            return true;
        }

        if (x < 0 || x >= BOARD_SIZE || y < 0 || y >= BOARD_SIZE) return false;

        int[][] tempBoard = BoardLogic.copyBoard(board);
        tempBoard[y][x] = currentPlayer;
        List<Position> capturedStones = BoardLogic.captureStones(tempBoard, x, y, currentPlayer);

        if (BoardLogic.hasLiberty(tempBoard, x, y, currentPlayer)) {
            board = tempBoard;
            handicapMgr.setBoard(board);
            Move move = new Move(x, y, currentPlayer, capturedStones);
            moveHistory.add(move);
            lastMove = move;
            currentMoveIndex = moveHistory.size() - 1;
            koMove = (capturedStones.size() == 1) ? new Move(x, y, currentPlayer) : null;
            switchPlayer();
            return true;
        }
        return false;
    }

    // ==================== 棋盘辅助 ====================

    private void switchPlayer() {
        currentPlayer = (currentPlayer == BLACK) ? WHITE : BLACK;
    }

    private int[][] copyBoard(int[][] original) {
        return BoardLogic.copyBoard(original);
    }

    public void resetBoard() {
        BoardLogic.resetBoard(board);
    }

    // ==================== 悔棋 ====================

    public void undo() {
        if (gameTree.hasTree() && gameTree.getCurrentNode() != null
                && gameTree.getCurrentNode().parent != null) {
            gameTree.setCurrentNode(gameTree.getCurrentNode().parent);
            rebuildBoardFromTree();
        } else if (currentMoveIndex >= 0 && !moveHistory.isEmpty()) {
            Move prev = moveHistory.get(currentMoveIndex);
            if (prev.capturedStones != null) {
                int opponent = (prev.player == BLACK) ? WHITE : BLACK;
                for (Position pos : prev.capturedStones) {
                    board[pos.y][pos.x] = opponent;
                }
            }
            if (prev.x >= 0 && prev.y >= 0) {
                board[prev.y][prev.x] = EMPTY;
            }
            currentMoveIndex--;
            currentPlayer = prev.player;
            lastMove = (currentMoveIndex >= 0) ? moveHistory.get(currentMoveIndex) : null;
            koMove = null;
        }
    }

    // ==================== 让子委托 ====================

    public void setupHandicap(int handicapCount) {
        handicapMgr.setupHandicap(handicapCount);
    }

    public List<Position> getHandicapPositions(int handicapCount) {
        return HandicapManager.getHandicapPositions(handicapCount);
    }

    public void applyHandicapStones() {
        handicapMgr.applyHandicapStones();
    }

    public void addBlackHandicapStone(int x, int y) {
        handicapMgr.addBlackHandicapStone(x, y);
    }

    public void addWhiteHandicapStone(int x, int y) {
        handicapMgr.addWhiteHandicapStone(x, y);
    }

    public void clearHandicapStones() {
        handicapMgr.clearHandicapStones();
    }

    public void removeHandicapStone(int x, int y) {
        handicapMgr.removeHandicapStone(x, y);
    }

    // ==================== 游戏树重建 ====================

    /**
     * 从游戏树重建盘面状态（从根节点到当前节点）
     */
    void rebuildBoardFromTree() {
        resetBoard();
        applyHandicapStones();

        List<SGFNode> path = new ArrayList<>();
        SGFNode tempNode = gameTree.getCurrentNode();
        while (tempNode != null && tempNode != gameTree.getRoot()) {
            path.add(0, tempNode);
            tempNode = tempNode.parent;
        }

        moveHistory.clear();
        int savedCurrentPlayer = currentPlayer;
        currentPlayer = BLACK;
        if (handicapMgr.getHandicap() > 0) currentPlayer = WHITE;

        String savedErrorMessage = lastErrorMessage;
        lastErrorMessage = "";
        koMove = null;

        for (SGFNode pathNode : path) {
            if (pathNode.move != null && pathNode.move.x != -1 && pathNode.move.y != -1) {
                currentPlayer = pathNode.move.player;
                boolean success = placeStoneForReconstruction(pathNode.move.x, pathNode.move.y);
                if (!success) {
                    board[pathNode.move.y][pathNode.move.x] = pathNode.move.player;
                    moveHistory.add(pathNode.move);
                    switchPlayer();
                }
            }
        }

        currentPlayer = savedCurrentPlayer;
        lastErrorMessage = savedErrorMessage;
        currentMoveIndex = moveHistory.size() - 1;
        lastMove = moveHistory.isEmpty() ? null : moveHistory.get(currentMoveIndex);
        koMove = null;

        int actualMoveCount = 0;
        for (Move m : moveHistory) {
            if (m.x != -1 && m.y != -1) actualMoveCount++;
        }
        if (handicapMgr.getHandicap() > 0) {
            currentPlayer = (actualMoveCount % 2 == 0) ? WHITE : BLACK;
        } else {
            currentPlayer = (actualMoveCount % 2 == 0) ? BLACK : WHITE;
        }
    }

    /**
     * 从当前 moveHistory 构建游戏树
     */
    private void buildGameTreeFromHistory() {
        if (moveHistory.isEmpty()) return;

        SGFNode root = new SGFNode(null);
        gameTree.setRoot(root);

        for (Move move : moveHistory) {
            SGFNode newNode = new SGFNode(gameTree.getCurrentNode());
            newNode.move = move;
            gameTree.getCurrentNode().children.add(newNode);
            gameTree.setCurrentNode(newNode);
        }
    }

    // ==================== 错误消息 ====================

    public String getLastErrorMessage() {
        return lastErrorMessage != null ? lastErrorMessage : "";
    }

    public void clearErrorMessage() {
        lastErrorMessage = "";
    }

    // ==================== Getters ====================

    public int[][] getBoard() { return board; }
    public int getCurrentPlayer() { return currentPlayer; }
    public List<Move> getMoveHistory() { return moveHistory; }
    public Move getLastMove() { return lastMove; }
    public String getBlackPlayer() { return blackPlayer; }
    public String getWhitePlayer() { return whitePlayer; }
    public String getResult() { return result; }
    public String getDate() { return date; }
    public int getHandicap() { return handicapMgr.getHandicap(); }
    public List<Position> getBlackHandicapStones() { return handicapMgr.getBlackHandicapStones(); }
    public List<Position> getWhiteHandicapStones() { return handicapMgr.getWhiteHandicapStones(); }

    // ==================== Setters ====================

    public void setCurrentPlayer(int player) {
        if (player == BLACK || player == WHITE) this.currentPlayer = player;
    }
    public void clearMoveHistory() {
        moveHistory.clear();
        currentMoveIndex = -1;
        lastMove = null;
    }
    public void setBlackPlayer(String v) { this.blackPlayer = v; }
    public void setWhitePlayer(String v) { this.whitePlayer = v; }
    public void setResult(String v) { this.result = v; }
    public void setDate(String v) { this.date = v; }
    public void setHandicap(int v) { handicapMgr.setHandicap(v); }

    // ===================================================================
    //  第二部分：委托方法壳
    // ===================================================================

    // ==================== GameTree 委托 ====================

    public void setGameTreeRoot(SGFNode root) {
        gameTree.setRoot(root);
        rebuildBoardFromTree();
    }

    public SGFNode getGameTreeRoot() {
        return gameTree.getRoot();
    }

    public SGFNode getCurrentNode() {
        return gameTree.getCurrentNode();
    }

    public void setCurrentNode(SGFNode node) {
        gameTree.setCurrentNode(node);
        rebuildBoardFromTree();
    }

    public boolean previousMove() {
        if (gameTree.getRoot() == null) {
            buildGameTreeFromHistory();
            if (gameTree.getRoot() == null) { undo(); return true; }
        }
        if (gameTree.getCurrentNode() == null) {
            gameTree.setCurrentNode(gameTree.getRoot());
            rebuildBoardFromTree();
            return true;
        }
        if (gameTree.getCurrentNode() == gameTree.getRoot()) {
            rebuildBoardFromTree();
            return true;
        }
        gameTree.setCurrentNode(gameTree.getCurrentNode().parent);
        rebuildBoardFromTree();
        return true;
    }

    public boolean nextMove() {
        if (gameTree.getCurrentNode() == null || gameTree.getCurrentNode().children.isEmpty()) {
            return false;
        }
        gameTree.setCurrentNode(gameTree.getCurrentNode().children.get(0));
        rebuildBoardFromTree();
        return true;
    }

    public List<Move> getBranchMoves() {
        return gameTree.getBranchMoves();
    }

    public boolean selectBranchMove(Move branchMove) {
        if (gameTree.selectBranchMove(branchMove)) {
            rebuildBoardFromTree();
            return true;
        }
        return false;
    }

    public boolean jumpToNode(SGFNode targetNode) {
        if (targetNode == null || gameTree.getRoot() == null) return false;
        if (gameTree.findPath(gameTree.getRoot(), targetNode) == null) return false;
        resetToStart();
        gameTree.setCurrentNode(targetNode);
        rebuildBoardFromTree();
        return true;
    }

    public boolean jumpToMove(int moveIndex) {
        List<Move> allMoves = getAllMoves();
        if (moveIndex < 0 || moveIndex >= allMoves.size()) return false;

        int currentIndex = getCurrentMoveIndex();
        if (currentIndex < 0) currentIndex = -1;
        if (moveIndex == currentIndex) return true;

        if (moveIndex > currentIndex) {
            for (int i = currentIndex + 1; i <= moveIndex; i++) {
                if (!nextMove()) return false;
            }
        } else {
            for (int i = currentIndex; i > moveIndex; i--) {
                if (!previousMove()) return false;
            }
        }
        return true;
    }

    public void resetToStart() {
        if (gameTree.hasTree()) {
            gameTree.setCurrentNode(gameTree.getRoot());
            rebuildBoardFromTree();
        } else {
            currentMoveIndex = -1;
            resetBoard();
            applyHandicapStones();
            lastMove = null;
            currentPlayer = (handicapMgr.getHandicap() > 0) ? WHITE : BLACK;
            koMove = null;
        }
    }

    public boolean deleteBranch(Move branchMove) {
        if (gameTree.deleteBranch(branchMove)) {
            rebuildBoardFromTree();
            return true;
        }
        return false;
    }

    public String getCurrentComment() {
        return gameTree.getCurrentComment();
    }

    public void setCurrentComment(String comment) {
        gameTree.setCurrentComment(comment);
    }

    public List<Move> getAllMoves() {
        return gameTree.collectPathMoves();
    }

    public List<TreeNodeInfo> getFullTree() {
        return gameTree.getFullTree();
    }

    public int getCurrentMoveIndex() {
        if (!gameTree.hasTree() || gameTree.getCurrentNode() == null) return -1;
        return gameTree.countMovesToNode(gameTree.getCurrentNode());
    }

    public List<SGFNode> findPath(SGFNode from, SGFNode to) {
        return gameTree.findPath(from, to);
    }

    // ==================== MarkManager 委托 ====================

    public void addMark(int x, int y) { markManager.addMark(x, y); }
    public void removeMark(int x, int y) { markManager.removeMark(x, y); }
    public List<Position> getMarks() { return markManager.getMarks(); }

    public void addCrossMark(int x, int y) { markManager.addCrossMark(x, y); }
    public void removeCrossMark(int x, int y) { markManager.removeCrossMark(x, y); }
    public List<Position> getCrossMarks() { return markManager.getCrossMarks(); }

    public void addSquareMark(int x, int y) { markManager.addSquareMark(x, y); }
    public void removeSquareMark(int x, int y) { markManager.removeSquareMark(x, y); }
    public List<Position> getSquareMarks() { return markManager.getSquareMarks(); }

    public void addTriangleMark(int x, int y) { markManager.addTriangleMark(x, y); }
    public void removeTriangleMark(int x, int y) { markManager.removeTriangleMark(x, y); }
    public List<Position> getTriangleMarks() { return markManager.getTriangleMarks(); }

    public void clearMarks() { markManager.clearMarks(); }

    // ==================== ScoreEstimator 委托 ====================

    private void ensureScoreEstimator() {
        if (scoreEstimator == null) {
            scoreEstimator = new ScoreEstimator(board);
        }
    }

    public int countBlackStones() { ensureScoreEstimator(); return scoreEstimator.countBlackStones(); }
    public int countWhiteStones() { ensureScoreEstimator(); return scoreEstimator.countWhiteStones(); }
    public int countBlackTerritory() { ensureScoreEstimator(); return scoreEstimator.countBlackTerritory(); }
    public int countWhiteTerritory() { ensureScoreEstimator(); return scoreEstimator.countWhiteTerritory(); }
    public float calculateScore() { ensureScoreEstimator(); return scoreEstimator.calculateScore(); }
    public String getScoreResult() { ensureScoreEstimator(); return scoreEstimator.getScoreResult(); }

    public void addDeadBlackStone(int x, int y) { ensureScoreEstimator(); scoreEstimator.addDeadBlackStone(x, y); }
    public void addDeadWhiteStone(int x, int y) { ensureScoreEstimator(); scoreEstimator.addDeadWhiteStone(x, y); }
    public void removeDeadStone(int x, int y) { ensureScoreEstimator(); scoreEstimator.removeDeadStone(x, y); }
    public void clearDeadStones() { ensureScoreEstimator(); scoreEstimator.clearDeadStones(); }

    public List<Position> getDeadBlackStones() { ensureScoreEstimator(); return scoreEstimator.getDeadBlackStones(); }
    public List<Position> getDeadWhiteStones() { ensureScoreEstimator(); return scoreEstimator.getDeadWhiteStones(); }
    public List<Position> detectDeadStones(int player) { ensureScoreEstimator(); return scoreEstimator.detectDeadStones(player); }

    public void setKomi(float komi) { ensureScoreEstimator(); scoreEstimator.setKomi(komi); }
    public float getKomi() { ensureScoreEstimator(); return scoreEstimator.getKomi(); }

    // ==================== BoardSerializer 委托 ====================

    public String serialize() {
        return BoardSerializer.serialize(board, currentPlayer, moveHistory,
                handicapMgr.getHandicap(), handicapMgr.getBlackHandicapStones(),
                handicapMgr.getWhiteHandicapStones(), blackPlayer, whitePlayer, result, date);
    }

    public void deserialize(String s) {
        BoardSerializer.DeserializeResult state = BoardSerializer.deserialize(s);
        if (state == null || !state.success) { newGame(); return; }

        board = state.board;
        currentPlayer = state.currentPlayer;
        moveHistory = state.moveHistory;
        handicapMgr.setBoard(board);
        handicapMgr.setHandicap(state.handicap);
        handicapMgr.clearHandicapStones();
        for (Position p : state.blackHandicapStones) handicapMgr.addBlackHandicapStone(p.x, p.y);
        for (Position p : state.whiteHandicapStones) handicapMgr.addWhiteHandicapStone(p.x, p.y);
        blackPlayer = state.blackPlayer;
        whitePlayer = state.whitePlayer;
        result = state.result;
        date = state.date;

        currentMoveIndex = moveHistory.size() - 1;
        lastMove = (currentMoveIndex >= 0) ? moveHistory.get(currentMoveIndex) : null;
        koMove = null;

        // 重建游戏树
        if (!moveHistory.isEmpty()) {
            buildGameTreeFromHistory();
            // 沿路径前进到末尾
            gameTree.setCurrentNode(gameTree.getRoot());
            for (Move m : moveHistory) {
                for (SGFNode child : gameTree.getCurrentNode().children) {
                    if (child.move != null && child.move.x == m.x
                            && child.move.y == m.y && child.move.player == m.player) {
                        gameTree.setCurrentNode(child);
                        break;
                    }
                }
            }
        } else {
            gameTree.setRoot(new SGFNode(null));
        }

        // 更新 ScoreEstimator
        scoreEstimator = new ScoreEstimator(board);
    }

    public String serializeSimple() {
        return serialize();
    }
}
