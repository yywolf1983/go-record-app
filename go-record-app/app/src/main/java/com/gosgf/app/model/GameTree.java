package com.gosgf.app.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 游戏树管理器 - 管理SGF游戏树结构、分支导航、节点跳转
 * 从 GoBoard 中提取，职责单一。使用 GoBoard.SGFNode / Move / Position 等类型。
 */
public class GameTree {

    private GoBoard.SGFNode root;
    private GoBoard.SGFNode currentNode;

    // ==================== 根节点 / 当前节点 ====================

    public GoBoard.SGFNode getRoot() {
        return root;
    }

    public void setRoot(GoBoard.SGFNode root) {
        this.root = root;
        this.currentNode = root;
    }

    public GoBoard.SGFNode getCurrentNode() {
        return currentNode;
    }

    public void setCurrentNode(GoBoard.SGFNode node) {
        this.currentNode = node;
    }

    public boolean hasTree() {
        return root != null;
    }

    // ==================== 分支操作 ====================

    /**
     * 获取当前节点的所有子分支
     */
    public List<GoBoard.Move> getBranchMoves() {
        List<GoBoard.Move> branchMoves = new ArrayList<>();
        if (currentNode == null) return branchMoves;

        for (GoBoard.SGFNode child : currentNode.children) {
            if (child.move != null && child.move.x != -1 && child.move.y != -1) {
                branchMoves.add(child.move);
            }
        }
        return branchMoves;
    }

    /**
     * 选择并切换到指定分支
     */
    public boolean selectBranchMove(GoBoard.Move branchMove) {
        if (currentNode == null || branchMove == null) return false;

        for (GoBoard.SGFNode child : currentNode.children) {
            if (child.move != null &&
                child.move.x == branchMove.x &&
                child.move.y == branchMove.y &&
                child.move.player == branchMove.player) {
                currentNode = child;
                return true;
            }
        }
        return false;
    }

    /**
     * 将走子添加到游戏树。若子节点中已有相同走子则复用。
     */
    public void addMove(GoBoard.Move move) {
        if (currentNode == null) return;

        for (GoBoard.SGFNode child : currentNode.children) {
            if (child.move != null &&
                child.move.x == move.x &&
                child.move.y == move.y &&
                child.move.player == move.player) {
                currentNode = child;
                return;
            }
        }

        GoBoard.SGFNode newNode = new GoBoard.SGFNode(currentNode);
        newNode.move = move;
        currentNode.children.add(newNode);
        currentNode = newNode;
    }

    // ==================== 节点跳转 ====================

    /**
     * 跳转到指定节点
     */
    public boolean jumpToNode(GoBoard.SGFNode targetNode) {
        if (targetNode == null || root == null) return false;

        List<GoBoard.SGFNode> path = findPath(root, targetNode);
        if (path == null) return false;

        currentNode = targetNode;
        return true;
    }

    /**
     * 查找从 from 到 target 的路径
     */
    public List<GoBoard.SGFNode> findPath(GoBoard.SGFNode from, GoBoard.SGFNode target) {
        if (from == target) {
            List<GoBoard.SGFNode> path = new ArrayList<>();
            path.add(from);
            return path;
        }
        for (GoBoard.SGFNode child : from.children) {
            List<GoBoard.SGFNode> result = findPath(child, target);
            if (result != null) {
                result.add(0, from);
                return result;
            }
        }
        return null;
    }

    // ==================== 步数统计 ====================

    /**
     * 从当前节点沿 parent 回到 root 可走的步数（上一步步数上限）
     */
    public int getStepsBackward() {
        if (currentNode == null || root == null) return 0;
        int steps = 0;
        GoBoard.SGFNode node = currentNode;
        while (node.parent != null) {
            steps++;
            node = node.parent;
        }
        return steps;
    }

    /**
     * 从当前节点沿 children.get(0) 走到末端可走的步数（下一步步数上限）
     */
    public int getStepsForward() {
        if (currentNode == null) return 0;
        int steps = 0;
        GoBoard.SGFNode node = currentNode;
        while (!node.children.isEmpty()) {
            steps++;
            node = node.children.get(0);
        }
        return steps;
    }

    /**
     * 获取当前分支路径：从根到当前节点的 child index 序列
     */
    private List<Integer> getBranchPath() {
        List<Integer> path = new ArrayList<>();
        if (currentNode == null) return path;
        GoBoard.SGFNode node = currentNode;
        while (node.parent != null) {
            int idx = node.parent.children.indexOf(node);
            path.add(0, idx);
            node = node.parent;
        }
        return path;
    }

    /**
     * 从根节点沿当前分支路径 + children[0] 走到末端的总步数
     */
    public int getTotalMoves() {
        if (root == null) return 0;
        List<Integer> branchPath = getBranchPath();
        int steps = 0;
        GoBoard.SGFNode node = root;
        int pathIdx = 0;
        while (!node.children.isEmpty()) {
            int childIdx = (pathIdx < branchPath.size()) ? branchPath.get(pathIdx) : 0;
            if (childIdx >= node.children.size()) break;
            node = node.children.get(childIdx);
            pathIdx++;
            if (node.move != null) steps++;
        }
        return steps;
    }

    /**
     * 跳转到第 stepIndex 步（0=根节点），沿当前分支路径前进
     * @return 是否成功
     */
    public boolean goToStep(int stepIndex) {
        if (root == null) return false;
        List<Integer> branchPath = getBranchPath();
        currentNode = root;
        for (int i = 0; i < stepIndex; i++) {
            int childIdx = (i < branchPath.size()) ? branchPath.get(i) : 0;
            if (currentNode.children.isEmpty() || childIdx >= currentNode.children.size()) {
                return false;
            }
            currentNode = currentNode.children.get(childIdx);
        }
        return true;
    }

    // ==================== 树遍历 ====================

    /**
     * 获取从根节点到当前节点路径上的所有 Move
     */
    public List<GoBoard.Move> collectPathMoves() {
        List<GoBoard.Move> moves = new ArrayList<>();
        if (root != null) {
            collectPathMovesRecursive(root, moves);
        }
        return moves;
    }

    private void collectPathMovesRecursive(GoBoard.SGFNode node, List<GoBoard.Move> moves) {
        if (node == null) return;
        if (node.move != null) {
            moves.add(node.move);
        }
        if (node == currentNode) return;
        if (!node.children.isEmpty()) {
            collectPathMovesRecursive(node.children.get(0), moves);
        }
    }

    /**
     * 获取完整树结构（扁平化列表）
     */
    public List<GoBoard.TreeNodeInfo> getFullTree() {
        List<GoBoard.TreeNodeInfo> treeNodes = new ArrayList<>();
        if (root != null) {
            collectFullTree(root, treeNodes, 0);
        }
        return treeNodes;
    }

    private void collectFullTree(GoBoard.SGFNode node, List<GoBoard.TreeNodeInfo> nodes, int depth) {
        if (node == null) return;

        if (node.move != null && node.move.x >= 0 && node.move.y >= 0) {
            boolean isCurrent = (node == currentNode);
            boolean hasBranches = node.children.size() > 1;
            int branchIndex = (node.parent != null) ? node.parent.children.indexOf(node) : 0;
            int branchCount = (node.parent != null) ? node.parent.children.size() : 1;
            nodes.add(new GoBoard.TreeNodeInfo(node, depth, hasBranches, isCurrent, branchIndex, branchCount));
        }

        for (GoBoard.SGFNode child : node.children) {
            collectFullTree(child, nodes, depth + 1);
        }
    }

    /**
     * 计算从根节点到目标节点的步数
     */
    public int countMovesToNode(GoBoard.SGFNode target) {
        return countMovesRecursive(root, target, 0);
    }

    private int countMovesRecursive(GoBoard.SGFNode node, GoBoard.SGFNode target, int count) {
        if (node == target) return count;
        for (GoBoard.SGFNode child : node.children) {
            int nextCount = count;
            if (child.move != null) nextCount++;
            int result = countMovesRecursive(child, target, nextCount);
            if (result >= 0) return result;
        }
        return -1;
    }

    // ==================== 分支删除 ====================

    /**
     * 删除分支节点
     */
    public boolean deleteBranch(GoBoard.Move branchMove) {
        if (root == null || currentNode == null) return false;

        DeleteResult result = findNodeAndParent(null, root, branchMove);
        if (result == null || result.parent == null) return false;

        GoBoard.SGFNode parentNode = result.parent;
        GoBoard.SGFNode targetNode = result.node;

        if (targetNode == currentNode || isAncestorOf(targetNode)) {
            currentNode = parentNode;
        }

        parentNode.children.remove(targetNode);
        return true;
    }

    private static class DeleteResult {
        GoBoard.SGFNode parent;
        GoBoard.SGFNode node;
        DeleteResult(GoBoard.SGFNode parent, GoBoard.SGFNode node) {
            this.parent = parent;
            this.node = node;
        }
    }

    private DeleteResult findNodeAndParent(GoBoard.SGFNode parent, GoBoard.SGFNode node, GoBoard.Move branchMove) {
        for (GoBoard.SGFNode child : node.children) {
            if (child.move != null &&
                child.move.x == branchMove.x &&
                child.move.y == branchMove.y &&
                child.move.player == branchMove.player) {
                return new DeleteResult(node, child);
            }
            DeleteResult found = findNodeAndParent(child, child, branchMove);
            if (found != null) return found;
        }
        return null;
    }

    private boolean isAncestorOf(GoBoard.SGFNode node) {
        GoBoard.SGFNode temp = currentNode;
        while (temp != null) {
            if (temp == node) return true;
            temp = temp.parent;
        }
        return false;
    }

    public boolean isAncestorOfCurrent(GoBoard.SGFNode node) {
        return isAncestorOf(node);
    }

    // ==================== 注释 ====================

    public String getCurrentComment() {
        if (currentNode != null && currentNode.comment != null) {
            return currentNode.comment;
        }
        return "";
    }

    public void setCurrentComment(String comment) {
        if (currentNode != null) {
            currentNode.comment = comment;
        }
    }
}
