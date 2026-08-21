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
    public static final int DEFAULT_BOARD_SIZE = 19;
    private static final int SERIALIZE_VERSION = 1;
    private int boardSize = DEFAULT_BOARD_SIZE;

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
    private int firstPlayer = BLACK; // 先手方（默认黑先），用于重建棋盘时正确计算当前玩家
    private List<Move> moveHistory;
    private Stack<List<Move>> variations;
    private Move lastMove;
    private Move koMove;
    private int currentMoveIndex;
    // Superko（全局同形禁着）：当前路径上所有已出现过的局面 hash（棋盘状态 + 轮到谁走）
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
        this(DEFAULT_BOARD_SIZE);
    }

    public GoBoard(int size) {
        this.boardSize = size;
        initializeBoard();
    }

    public int getBoardSize() { return boardSize; }

    private void initializeBoard() {
        board = new int[boardSize][boardSize];
        currentPlayer = BLACK;
        firstPlayer = BLACK;
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
        newGame(boardSize);
    }

    public void newGame(int size) {
        this.boardSize = size;
        initializeBoard();
        gameTree.setRoot(new SGFNode(null));
        handicapMgr.applyHandicapStones();
        firstPlayer = (handicapMgr.getHandicap() > 0) ? WHITE : BLACK;
        currentPlayer = firstPlayer;
    }

    // ==================== 核心落子逻辑 ====================

    /**
     * 主落子入口（含边界检查、打劫、自杀检查）
     */
    public boolean placeStone(int x, int y) {
        if (x == -1 && y == -1) return placePassStone();

        if (x < 0 || x >= boardSize || y < 0 || y >= boardSize) {
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

            // 单劫（ko）禁着点：本方在 (x,y) 落子恰好提掉对方 1 子，
            // 禁着点应是“被提子的位置”，对方下一手不能立即回提该点。
            int opponent = (currentPlayer == BLACK) ? WHITE : BLACK;
            koMove = (capturedStones.size() == 1)
                    ? new Move(capturedStones.get(0).x, capturedStones.get(0).y, opponent)
                    : null;

            // 检测通过后才切换玩家并写入游戏树
            switchPlayer();
            if (gameTree.getCurrentNode() != null) {
                gameTree.addMove(move);
            }

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

        if (x < 0 || x >= boardSize || y < 0 || y >= boardSize) return false;

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
            int opp = (currentPlayer == BLACK) ? WHITE : BLACK;
            koMove = (capturedStones.size() == 1)
                    ? new Move(capturedStones.get(0).x, capturedStones.get(0).y, opp)
                    : null;
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

    public void syncBoardToHandicap() {
        handicapMgr.syncFromBoard();
    }

    /** 摆子完成后：移除所有无气死子，并重新同步座子列表 */
    public int cleanupDeadStonesAfterSetup() {
        int removed = BoardLogic.removeAllDeadStones(board);
        if (removed > 0) {
            handicapMgr.syncFromBoard();
        }
        return removed;
    }

    // ==================== 游戏树重建 ====================

    /**
     * 从游戏树重建盘面状态（从根节点到当前节点）
     */
    void rebuildBoardFromTree() {
        // === 统一走 currentNode 的父链获取完整路径（不再用 children[0]） ===
        SGFNode currentNode = gameTree.getCurrentNode();
        if (currentNode == null) {
            resetBoard();
            applyHandicapStones();
            moveHistory.clear();
            currentPlayer = firstPlayer;
            koMove = null;
            lastMove = null;
            currentMoveIndex = -1;
            return;
        }

        List<SGFNode> path = new ArrayList<>();
        SGFNode tempNode = currentNode;
        while (tempNode != null) {
            path.add(0, tempNode);
            tempNode = tempNode.parent;
        }
        // path[0] 是 root

        // 构建 moveHistory（按实际路径）
        moveHistory.clear();
        for (SGFNode n : path) {
            if (n.move != null) {
                if (n.move.x != -1 && n.move.y != -1) {
                    moveHistory.add(new Move(n.move.x, n.move.y, n.move.player));
                } else if (n.move.x == -1 && n.move.y == -1) {
                    moveHistory.add(new Move(-1, -1, n.move.player));
                }
            }
        }

        // 在棋盘上重放实际路径
        resetBoard();
        applyHandicapStones();
        currentPlayer = firstPlayer;

        String savedErrorMessage = lastErrorMessage;
        lastErrorMessage = "";

        for (SGFNode pathNode : path) {
            if (pathNode.move != null && pathNode.move.x != -1 && pathNode.move.y != -1) {
                currentPlayer = pathNode.move.player;
                board[pathNode.move.y][pathNode.move.x] = currentPlayer;
                // 重放时记录提子数，用于重建 koMove（与 placeStone 保持一致）
                List<Position> caps = BoardLogic.captureStones(board, pathNode.move.x, pathNode.move.y, currentPlayer);
                switchPlayer();
                // 重建 koMove：仅当这一步恰好提掉对方 1 子（单劫），
                // 禁着点 = 被提子的位置，nextPlayer(对手) 不能立即回提该点。
                if (caps != null && caps.size() == 1) {
                    int nextPlayer = (currentPlayer == BLACK) ? WHITE : BLACK; // switchPlayer 前 currentPlayer 是落子方
                    koMove = new Move(caps.get(0).x, caps.get(0).y, nextPlayer);
                } else {
                    koMove = null;
                }
            } else if (pathNode.move != null && pathNode.move.x == -1 && pathNode.move.y == -1) {
                currentPlayer = pathNode.move.player;
                switchPlayer();
                koMove = null;
            }
        }

        lastErrorMessage = savedErrorMessage;
        currentMoveIndex = moveHistory.size() - 1;
        lastMove = (currentMoveIndex >= 0) ? moveHistory.get(currentMoveIndex) : null;
        // 注：koMove 已在上面重放循环中按最后一步重建；若路径无任何着法则保持 null
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
    public int getFirstPlayer() { return firstPlayer; }
    public void setFirstPlayer(int player) {
        if (player == BLACK || player == WHITE) this.firstPlayer = player;
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

    public GameTree getGameTree() {
        return gameTree;
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

    public int getStepsBackward() {
        return gameTree.getStepsBackward();
    }

    public int getStepsForward() {
        return gameTree.getStepsForward();
    }

    public int getTotalMoves() {
        return gameTree.getTotalMoves();
    }

    /** 一步跳到第 stepIndex 步（0=初始局面），只 rebuild 一次 */
    public boolean goToStep(int stepIndex) {
        if (gameTree.goToStep(stepIndex)) {
            rebuildBoardFromTree();
            return true;
        }
        return false;
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
            currentPlayer = firstPlayer;
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

    public int countMovesToNode(SGFNode node) {
        if (!gameTree.hasTree() || node == null) return -1;
        return gameTree.countMovesToNode(node);
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
        } else {
            scoreEstimator.setBoard(board);
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

    /**
     * 返回当前棋盘的副本，并把用户标记的死子（黑/白）挖掉（置为 EMPTY）。
     * 用于把「用户已判定死亡」的棋子告知 KataGo：引擎分析的 rootScoreLead
     * 会基于「死子已提走」的真实局面，从而尊重用户在棋盘上标的死子，而不依赖启发式。
     */
    public int[][] getBoardWithDeadStonesRemoved() {
        ensureScoreEstimator();
        int size = board.length;
        int[][] result = new int[size][];
        for (int y = 0; y < size; y++) {
            result[y] = board[y].clone();
        }
        for (Position p : scoreEstimator.getDeadBlackStones()) {
            if (p.y >= 0 && p.y < size && p.x >= 0 && p.x < size) {
                result[p.y][p.x] = EMPTY;
            }
        }
        for (Position p : scoreEstimator.getDeadWhiteStones()) {
            if (p.y >= 0 && p.y < size && p.x >= 0 && p.x < size) {
                result[p.y][p.x] = EMPTY;
            }
        }
        return result;
    }
    public List<Position> detectDeadStones(int player) { ensureScoreEstimator(); return scoreEstimator.detectDeadStones(player); }

    public void setKomi(float komi) { ensureScoreEstimator(); scoreEstimator.setKomi(komi); }
    public float getKomi() { ensureScoreEstimator(); return scoreEstimator.getKomi(); }

    public List<Position> getBlackTerritoryPositions() { ensureScoreEstimator(); return scoreEstimator.getBlackTerritoryPositions(); }
    public List<Position> getWhiteTerritoryPositions() { ensureScoreEstimator(); return scoreEstimator.getWhiteTerritoryPositions(); }
    /** 仅小计目块确定围空（面积≤阈值），作为确定死目实心点；大空不在此列 */
    public List<Position> getBlackTerritorySmallPositions() { ensureScoreEstimator(); return scoreEstimator.getBlackTerritorySmallPositions(); }
    public List<Position> getWhiteTerritorySmallPositions() { ensureScoreEstimator(); return scoreEstimator.getWhiteTerritorySmallPositions(); }
    /** 势力范围估算目（黑/白），供弹窗拆分展示 */
    public int getInfluenceBlackPoints() { ensureScoreEstimator(); return scoreEstimator.getInfluenceBlackPoints(); }
    public int getInfluenceWhitePoints() { ensureScoreEstimator(); return scoreEstimator.getInfluenceWhitePoints(); }
    public int getDeadBlackTerritory() { ensureScoreEstimator(); return scoreEstimator.getDeadBlackTerritory(); }
    public int getDeadWhiteTerritory() { ensureScoreEstimator(); return scoreEstimator.getDeadWhiteTerritory(); }
    public List<Position> getBlackPotentialPositions() { ensureScoreEstimator(); return scoreEstimator.getBlackPotentialPositions(); }
    public List<Position> getWhitePotentialPositions() { ensureScoreEstimator(); return scoreEstimator.getWhitePotentialPositions(); }
    public List<ScoreEstimator.InfluencePoint> getAllInfluencePoints() { ensureScoreEstimator(); return scoreEstimator.getAllInfluencePoints(); }

    public float getEstimatedScoreDifference() { ensureScoreEstimator(); return scoreEstimator.getEstimatedScoreDifference(); }
    public float getEstimatedBlackScore() { ensureScoreEstimator(); return scoreEstimator.getEstimatedBlackScore(); }
    public float getEstimatedWhiteScore() { ensureScoreEstimator(); return scoreEstimator.getEstimatedWhiteScore(); }
    public String getEstimatedScoreResult() { ensureScoreEstimator(); return scoreEstimator.getEstimatedScoreResult(); }
    public List<Position> getEstimatedDeadBlackStones() { ensureScoreEstimator(); return scoreEstimator.getEstimatedDeadBlackStones(); }
    public List<Position> getEstimatedDeadWhiteStones() { ensureScoreEstimator(); return scoreEstimator.getEstimatedDeadWhiteStones(); }

    // 势力范围（近距离检测，距离 ≤ 3）
    public float getBlackInfluenceValue() { ensureScoreEstimator(); return scoreEstimator.getBlackInfluenceValue(); }
    public float getWhiteInfluenceValue() { ensureScoreEstimator(); return scoreEstimator.getWhiteInfluenceValue(); }
    public List<Position> getBlackInfluencePositions() { ensureScoreEstimator(); return scoreEstimator.getBlackInfluencePositions(); }
    public List<Position> getWhiteInfluencePositions() { ensureScoreEstimator(); return scoreEstimator.getWhiteInfluencePositions(); }
    public float getInfluenceAt(int x, int y) { ensureScoreEstimator(); return scoreEstimator.getInfluenceAt(x, y); }
    /** 空点势力强度（0~1），供棋盘按强弱渲染势力范围 */
    public float getInfluenceStrengthAt(int x, int y) { ensureScoreEstimator(); return scoreEstimator.getPotentialStrengthAt(x, y); }
    public float getEstimatedScoreDifferenceWithInfluence() { ensureScoreEstimator(); return scoreEstimator.getEstimatedScoreDifference(); }
    public String getEstimatedScoreResultWithInfluence() { ensureScoreEstimator(); return scoreEstimator.getEstimatedScoreResult(); }

    // ==================== BoardSerializer 委托 ====================

    public String serialize() {
        int currentStep = getCurrentMoveIndex();
        if (currentStep < 0) currentStep = moveHistory.size();
        String treeBlob = serializeGameTree();
        return BoardSerializer.serialize(board, currentPlayer, moveHistory,
                handicapMgr.getHandicap(), handicapMgr.getBlackHandicapStones(),
                handicapMgr.getWhiteHandicapStones(), blackPlayer, whitePlayer, result, date, currentStep, treeBlob);
    }

    /**
     * 序列化完整游戏树（含变着分支、注释、光标节点 id）。
     * 格式：T|<节点数>|<光标节点id>|<节点>;...
     * 节点：id,父id,兄弟序号,x,y,player,<注释长度>,<转义后注释>
     */
    private String serializeGameTree() {
        if (gameTree == null || gameTree.getRoot() == null) return "";
        java.util.Map<GoBoard.SGFNode, Integer> idMap = new java.util.HashMap<>();
        java.util.List<GoBoard.SGFNode> order = new java.util.ArrayList<>();
        int[] counter = {0};
        assignTreeIds(gameTree.getRoot(), idMap, order, counter);

        int currentId = -1;
        GoBoard.SGFNode cur = gameTree.getCurrentNode();
        if (cur != null && idMap.containsKey(cur)) currentId = idMap.get(cur);

        StringBuilder sb = new StringBuilder();
        sb.append("T|").append(order.size()).append("|").append(currentId).append("|");
        for (GoBoard.SGFNode n : order) {
            int id = idMap.get(n);
            int pid = (n.parent != null) ? idMap.get(n.parent) : -1;
            int branch = (n.parent != null) ? n.parent.children.indexOf(n) : 0;
            int x = (n.move != null) ? n.move.x : -1;
            int y = (n.move != null) ? n.move.y : -1;
            int player = (n.move != null) ? n.move.player : 0;
            String comment = (n.comment != null) ? n.comment : "";
            sb.append(id).append(',').append(pid).append(',').append(branch).append(',')
              .append(x).append(',').append(y).append(',').append(player).append(',')
              .append(comment.length()).append(',')
              .append(BoardSerializer.escapeString(comment)).append(';');
        }
        return sb.toString();
    }

    private void assignTreeIds(GoBoard.SGFNode node,
                               java.util.Map<GoBoard.SGFNode, Integer> idMap,
                               java.util.List<GoBoard.SGFNode> order,
                               int[] counter) {
        idMap.put(node, counter[0]++);
        order.add(node);
        for (GoBoard.SGFNode c : node.children) assignTreeIds(c, idMap, order, counter);
    }

    /**
     * 从序列化片段重建完整游戏树（含变着分支、注释），并把光标定位到原节点。
     * @return 是否成功重建
     */
    private boolean applyGameTreeBlob(String blob, int fallbackStep) {
        try {
            String[] sections = blob.split("\\|", 4);
            if (sections.length < 4) return false;
            int count = Integer.parseInt(sections[1]);
            int currentId = Integer.parseInt(sections[2]);
            String nodesStr = sections[3];

            java.util.Map<Integer, GoBoard.SGFNode> byId = new java.util.HashMap<>();
            java.util.Map<Integer, Integer> pidOf = new java.util.HashMap<>();
            java.util.Map<Integer, Integer> branchOf = new java.util.HashMap<>();

            for (String ns : nodesStr.split(";")) {
                if (ns.isEmpty()) continue;
                String[] f = ns.split(",", 7);   // 前7段：id,pid,branch,x,y,player,clen+comment
                if (f.length < 7) continue;
                int id = Integer.parseInt(f[0]);
                int pid = Integer.parseInt(f[1]);
                int branch = Integer.parseInt(f[2]);
                int x = Integer.parseInt(f[3]);
                int y = Integer.parseInt(f[4]);
                int player = Integer.parseInt(f[5]);
                int comma = f[6].indexOf(',');
                if (comma < 0) continue;
                int clen = Integer.parseInt(f[6].substring(0, comma));
                String commentRaw = f[6].substring(comma + 1);
                String comment = BoardSerializer.unescapeString(commentRaw);
                if (comment.length() > clen) comment = comment.substring(0, clen);

                GoBoard.SGFNode n = new GoBoard.SGFNode(null);
                if (x >= 0 && y >= 0 && player != 0) {
                    n.move = new GoBoard.Move(x, y, player);
                }
                n.comment = comment;
                byId.put(id, n);
                pidOf.put(id, pid);
                branchOf.put(id, branch);
            }
            if (!byId.containsKey(0)) return false;

            // 按兄弟序号挂到父节点（先占位，后紧凑化去 null）
            for (Integer id : byId.keySet()) {
                GoBoard.SGFNode n = byId.get(id);
                int pid = pidOf.get(id);
                if (pid >= 0 && byId.containsKey(pid)) {
                    GoBoard.SGFNode p = byId.get(pid);
                    int branch = branchOf.get(id);
                    while (p.children.size() <= branch) p.children.add(null);
                    p.children.set(branch, n);
                    n.parent = p;
                }
            }

            // 紧凑化：每个父节点的 children 按 branch 升序重排，去掉 null 占位，
            // 保证 children.get(0) 始终指向主分支（branch=0），后续遍历不会因 null 中断
            for (GoBoard.SGFNode p : byId.values()) {
                if (p.children.isEmpty()) continue;
                java.util.List<GoBoard.SGFNode> real = new java.util.ArrayList<>();
                for (GoBoard.SGFNode c : p.children) {
                    if (c != null) real.add(c);
                }
                // 按 branch 序号升序排序，确保主分支位于 index 0
                real.sort((a, b) -> branchOfNode(branchOf, a, byId) - branchOfNode(branchOf, b, byId));
                p.children.clear();
                p.children.addAll(real);
            }

            gameTree.setRoot(byId.get(0));

            if (currentId >= 0 && byId.containsKey(currentId)) {
                gameTree.setCurrentNode(byId.get(currentId));
            } else {
                // 光标 id 缺失时退回到按步数定位
                int step = fallbackStep;
                if (step > 0) {
                    int maxStep = countMovesToNode(lastLeaf(byId.get(0)));
                    if (step > maxStep) step = maxStep;
                    gameTree.setCurrentNode(byId.get(0));
                    for (int i = 0; i < step; i++) {
                        if (gameTree.getCurrentNode().children.isEmpty()) break;
                        gameTree.setCurrentNode(gameTree.getCurrentNode().children.get(0));
                    }
                } else {
                    gameTree.setCurrentNode(byId.get(0));
                }
            }

            rebuildBoardFromTree();

            // 重建主分支 moveHistory（children[0] 链）
            moveHistory.clear();
            moveHistory.addAll(gameTree.collectPathMoves());
            currentMoveIndex = moveHistory.size() - 1;
            lastMove = (currentMoveIndex >= 0) ? moveHistory.get(currentMoveIndex) : null;
                koMove = null;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private GoBoard.SGFNode lastLeaf(GoBoard.SGFNode node) {
        while (node != null && !node.children.isEmpty()
                && node.children.get(0) != null) {
            node = node.children.get(0);
        }
        return node;
    }

    /** 通过 byId 反查节点 id，再从 branchOf 取其兄弟序号（用于紧凑化排序） */
    private int branchOfNode(java.util.Map<Integer, Integer> branchOf,
                             GoBoard.SGFNode node,
                             java.util.Map<Integer, GoBoard.SGFNode> byId) {
        for (java.util.Map.Entry<Integer, GoBoard.SGFNode> e : byId.entrySet()) {
            if (e.getValue() == node) {
                Integer b = branchOf.get(e.getKey());
                return (b != null) ? b : 0;
            }
        }
        return 0;
    }

    public void deserialize(String s) {
        BoardSerializer.DeserializeResult state = BoardSerializer.deserialize(s);
        if (state == null || !state.success) { newGame(); return; }

        // 按存档尺寸同步 boardSize(版本 2+ 带 size, 旧存档默认 19)
        this.boardSize = state.boardSize;
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

        // 优先用完整游戏树片段重建（含变着分支，光标精确恢复）
        if (state.gameTreeBlob != null && state.gameTreeBlob.startsWith("T|")
                && applyGameTreeBlob(state.gameTreeBlob, state.currentStep)) {
            // 已在 applyGameTreeBlob 内完成树重建与光标定位
        } else if (!moveHistory.isEmpty()) {
            // 旧格式 / 无树片段：线性重建到末尾，再定位光标
            buildGameTreeFromHistory();
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
            int step = state.currentStep;
            if (step > 0) {
                int maxStep = moveHistory.size();
                if (step > maxStep) step = maxStep;
                gameTree.goToStep(step);
            }
            rebuildBoardFromTree();
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
