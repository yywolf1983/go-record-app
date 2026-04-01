package com.gosgf.app;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.AdapterView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ArrayAdapter;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.gosgf.app.model.GoBoard;
import com.gosgf.app.model.GoBoard.Move;
import com.gosgf.app.util.SGFConverter;
import com.gosgf.app.util.SGFParser;
import com.gosgf.app.view.BoardView;
import com.gosgf.app.view.GameInfoView;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;

    public class MainActivity extends AppCompatActivity {
    private GoBoard board;
    private BoardView boardView;
    private ScrollView commentScrollView;
    private TextView commentText;
    private TextView moveCountText; // 步数显示
    private BoardView.OnBranchSelectListener branchSelectListener;
    private BoardView.OnBranchDeleteListener branchDeleteListener;
    
    private Button btnNew;
    private Button btnLoad;
    private Button btnSave;
    private Button btnToStart;
    private Button btnPrevious;
    private Button btnNext;
    private Button btnPass;
    private Button btnComment;
    private Button btnMark;
    private Button btnPlace;
    private Button btnDeleteBranch;
    private Button btnScore;

    // 摆子模式状态
    private boolean isPlaceMode = false;

    // Activity Result Launchers
    private ActivityResultLauncher<Intent> loadFileLauncher;
    private ActivityResultLauncher<Intent> saveFileLauncher;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 隐藏标题栏
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }

        // 尝试从保存的状态恢复棋局
        if (savedInstanceState != null) {
            String savedBoardState = savedInstanceState.getString("board_state", null);
            if (savedBoardState != null) {
                board = new GoBoard();
                board.deserialize(savedBoardState);
            } else {
                board = new GoBoard();
            }
        } else {
            // 尝试从SharedPreferences恢复上次的棋局
            String savedState = getPreferences(Context.MODE_PRIVATE).getString("last_game_state", null);
            if (savedState != null) {
                board = new GoBoard();
                board.deserialize(savedState);
            } else {
                board = new GoBoard();
            }
        }

        // 初始化视图
        boardView = findViewById(R.id.boardView);
        boardView.setBoard(board);
        boardView.setOnBoardTouchListener(this::onBoardTouch);

        // 初始化注释显示
        commentScrollView = findViewById(R.id.commentScrollView);
        commentText = findViewById(R.id.commentText);
        moveCountText = findViewById(R.id.moveCountText);
        updateCommentDisplay();

        // 初始化分支选择监听器
        branchSelectListener = branchMove -> {
            boolean success = board.selectBranchMove(branchMove);
            if (success) {
                String positionKey = branchMove.x + "," + branchMove.y;
                boardView.setSelectedBranchPosition(positionKey);
                boardView.refresh();
                updateCommentDisplay();
                Toast.makeText(MainActivity.this, "已选择分支", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(MainActivity.this, "选择分支失败", Toast.LENGTH_SHORT).show();
            }
        };

        // 初始化分支删除监听器
        branchDeleteListener = branchMove -> {
            // 转换坐标为棋盘坐标（如D4）
            String coordinate = convertToCoordinate(branchMove.x, branchMove.y);
            String playerName = branchMove.player == GoBoard.BLACK ? "黑" : "白";

            new AlertDialog.Builder(MainActivity.this)
                .setTitle("删除分支")
                .setMessage("确定要删除 " + playerName + "手 " + coordinate + " 的分支吗？")
                .setPositiveButton("删除", (dialog, which) -> {
                    boolean success = board.deleteBranch(branchMove);
                    if (success) {
                        boardView.refresh();
                        updateCommentDisplay();
                        Toast.makeText(MainActivity.this, "分支已删除", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(MainActivity.this, "删除分支失败", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
        };

        boardView.setOnBranchSelectListener(branchSelectListener);
        boardView.setOnBranchDeleteListener(branchDeleteListener);

        // 初始化Activity Result Launchers
        initActivityResultLaunchers();
        
        // 初始化按钮
        initButtons();
    }
    
    private void initActivityResultLaunchers() {
        // 初始化加载文件的Launcher
        loadFileLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    Intent data = result.getData();
                    if (data != null && data.getData() != null) {
                        loadFile(data.getData());
                    }
                }
            }
        );
        
        // 初始化保存文件的Launcher
        saveFileLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    Intent data = result.getData();
                    if (data != null && data.getData() != null) {
                        saveFile(data.getData());
                    }
                }
            }
        );
    }
    
    private void initButtons() {
        // 工具栏按钮
        btnNew = findViewById(R.id.btn_new);
        btnLoad = findViewById(R.id.btn_load);
        btnSave = findViewById(R.id.btn_save);

        // 导航栏按钮
        btnToStart = findViewById(R.id.btn_to_start);
        btnPrevious = findViewById(R.id.btn_previous);
        btnNext = findViewById(R.id.btn_next);
        btnPass = findViewById(R.id.btn_pass);

        // 功能栏按钮
        btnComment = findViewById(R.id.btn_comment);
        btnMark = findViewById(R.id.btn_mark);
        btnPlace = findViewById(R.id.btn_place);
        btnDeleteBranch = findViewById(R.id.btn_delete_branch);
        btnScore = findViewById(R.id.btn_score);

        // 设置点击监听器
        btnNew.setOnClickListener(v -> onNewGame());
        btnLoad.setOnClickListener(v -> onLoadGame());
        btnSave.setOnClickListener(v -> onSaveGame());

        btnToStart.setOnClickListener(v -> onToStart());
        btnPrevious.setOnClickListener(v -> onPrevious());
        btnNext.setOnClickListener(v -> onNext());
        btnPass.setOnClickListener(v -> onPass());

        btnComment.setOnClickListener(v -> onComment());
        btnMark.setOnClickListener(v -> onMark());
        btnPlace.setOnClickListener(v -> onPlace());
        btnDeleteBranch.setOnClickListener(v -> onDeleteBranch());
        btnScore.setOnClickListener(v -> onScore());
    }
    
    private void onBoardTouch(int x, int y) {
        boolean success = board.placeStone(x, y);
        if (success) {
            boardView.refresh();
            updateCommentDisplay();
        } else {
            // 显示错误提示
            String errorMessage = board.getLastErrorMessage();
            if (errorMessage != null && !errorMessage.isEmpty()) {
                Toast.makeText(this, errorMessage, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "此处不能落子", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void onNewGame() {
        board.newGame();
        boardView.refresh();
        updateCommentDisplay();
        // 清除保存的游戏状态
        clearSavedGameState();
    }

    /**
     * 更新注释和步数显示
     */
    private void updateCommentDisplay() {
        // 更新步数显示
        int currentIndex = board.getCurrentMoveIndex();
        int totalMoves = board.getMoveHistory().size();
        String player = board.getCurrentPlayer() == GoBoard.BLACK ? "黑方" : "白方";
        moveCountText.setText("步数: " + (currentIndex + 1) + " / " + totalMoves + " " + player);

        // 更新注释显示
        String comment = board.getCurrentComment();
        if (comment != null && !comment.isEmpty()) {
            commentText.setText(comment);
        } else {
            commentText.setText("");
        }
    }

    private void onPlace() {
        // 切换摆子模式
        isPlaceMode = !isPlaceMode;
        boardView.setPlaceMode(isPlaceMode);
        if (isPlaceMode) {
            btnPlace.setText("完成摆子");
            Toast.makeText(this, "点击棋盘摆子（黑棋）", Toast.LENGTH_SHORT).show();
        } else {
            // 完成摆子
            int blackCount = board.getBlackHandicapStones().size();
            int whiteCount = board.getWhiteHandicapStones().size();
            int total = blackCount + whiteCount;
            board.setHandicap(blackCount); // 保存黑棋数作为handicap
            btnPlace.setText("摆子");

            // 保存当前座子（用于后面恢复）
            java.util.List<GoBoard.Position> savedBlackStones = new java.util.ArrayList<>(board.getBlackHandicapStones());
            java.util.List<GoBoard.Position> savedWhiteStones = new java.util.ArrayList<>(board.getWhiteHandicapStones());

            // 重新开始游戏（清除之前的走子）
            board.newGame();

            // 恢复座子
            board.clearHandicapStones();
            for (GoBoard.Position pos : savedBlackStones) {
                board.addBlackHandicapStone(pos.x, pos.y);
            }
            for (GoBoard.Position pos : savedWhiteStones) {
                board.addWhiteHandicapStone(pos.x, pos.y);
            }
            board.applyHandicapStones();

            // 弹出选择对话框
            String[] playerOptions = {"黑方先手", "白方先手"};
            new AlertDialog.Builder(this)
                .setTitle("选择下一步")
                .setItems(playerOptions, (dialog, which) -> {
                    if (which == 0) {
                        board.setCurrentPlayer(GoBoard.BLACK);
                        Toast.makeText(this, "已设置 " + blackCount + "黑 " + whiteCount + "白 共" + total + "子，黑方先手", Toast.LENGTH_SHORT).show();
                    } else {
                        board.setCurrentPlayer(GoBoard.WHITE);
                        Toast.makeText(this, "已设置 " + blackCount + "黑 " + whiteCount + "白 共" + total + "子，白方先手", Toast.LENGTH_SHORT).show();
                    }
                    boardView.refresh();
                    updateCommentDisplay();
                })
                .show();
        }
    }

    private void onLoadGame() {
        // 请求文件访问权限
        requestStoragePermission();
    }
    
    private void requestStoragePermission() {
        // 对于Android 13+，不需要存储权限，使用ACTION_OPEN_DOCUMENT即可
        // 直接打开文件选择器
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        // 设置多种MIME类型，确保能够识别.sgf文件
        String[] mimeTypes = {"text/plain", "application/x-go-sgf", "application/sgf", "text/sgf"};
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        intent.setType("*/*"); // 设置为通配符，确保能够看到所有文件
        // 添加文件扩展名过滤器
        intent.putExtra(Intent.EXTRA_TITLE, "选择SGF文件");
        loadFileLauncher.launch(intent);
    }

    private void onSaveGame() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        // 设置为SGF文件类型
        intent.setType("application/x-go-sgf");
        // 添加其他可能的MIME类型
        String[] mimeTypes = {"application/x-go-sgf", "application/sgf", "text/sgf", "text/plain"};
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        // 确保默认文件名为game.sgf
        intent.putExtra(Intent.EXTRA_TITLE, "game.sgf");
        saveFileLauncher.launch(intent);
    }
    
    private void onSettings() {
        // 打开设置界面（暂未实现）
    }

    private void onToStart() {
        // 显示步数选择对话框
        showJumpDialog();
    }

    /**
     * 显示跳转步数选择对话框 - 两级选择：先选分支，再选步数
     */
    private void showJumpDialog() {
        List<GoBoard.TreeNodeInfo> treeNodes = board.getFullTree();
        if (treeNodes.isEmpty()) {
            Toast.makeText(this, "没有要跳转的步数", Toast.LENGTH_SHORT).show();
            return;
        }

        // 查找所有有分支的节点
        List<GoBoard.TreeNodeInfo> branchPoints = new ArrayList<>();
        for (GoBoard.TreeNodeInfo info : treeNodes) {
            if (info.hasBranches) {
                branchPoints.add(info);
            }
        }

        if (branchPoints.isEmpty()) {
            // 没有分支，直接显示步数列表
            showStepListDialog(treeNodes, -1);
            return;
        }

        // 检查当前节点是否在分支中
        GoBoard.SGFNode currentNode = board.getCurrentNode();
        int currentBranchIndex = -1;
        for (int i = 0; i < branchPoints.size(); i++) {
            GoBoard.TreeNodeInfo info = branchPoints.get(i);
            // 检查当前节点是否是该分支点的子节点（深度更深）
            GoBoard.SGFNode temp = currentNode;
            while (temp != null && temp.parent != null) {
                if (temp.parent == info.node) {
                    currentBranchIndex = i;
                    break;
                }
                temp = temp.parent;
            }
            if (currentBranchIndex >= 0) break;
        }

        if (currentBranchIndex >= 0) {
            // 当前在分支中，直接显示该分支后的步数列表
            showStepListDialog(treeNodes, currentBranchIndex);
        } else {
            // 不在分支中，显示分支选择对话框
            showBranchListDialog(treeNodes, branchPoints);
        }
    }

    /**
     * 显示分支选择对话框
     */
    private void showBranchListDialog(List<GoBoard.TreeNodeInfo> treeNodes, List<GoBoard.TreeNodeInfo> branchPoints) {
        // 构建分支列表
        String[] branchItems = new String[branchPoints.size() + 1]; // +1 for "所有步数" option

        branchItems[0] = "📋 所有步数";

        for (int i = 0; i < branchPoints.size(); i++) {
            GoBoard.TreeNodeInfo info = branchPoints.get(i);
            GoBoard.Move move = info.node.move;
            String coordinate = convertToCoordinate(move.x, move.y);
            String playerText = move.player == GoBoard.BLACK ? "黑" : "白";

            branchItems[i + 1] = playerText + " " + coordinate
                    + " [" + info.node.children.size() + "分支]";
        }

        // 显示分支选择对话框
        new AlertDialog.Builder(this)
                .setTitle("选择分支 (" + branchPoints.size() + "处)")
                .setItems(branchItems, (dialog, which) -> {
                    if (which == 0) {
                        // 选择所有步数
                        showStepListDialog(treeNodes, -1);
                    } else {
                        // 选择特定分支点
                        showStepListDialog(treeNodes, which - 1);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 显示步数列表对话框
     * @param treeNodes 所有节点
     * @param branchIndex 分支索引，-1表示全部，>=0表示只显示该分支点后的步数
     */
    private void showStepListDialog(List<GoBoard.TreeNodeInfo> treeNodes, int branchIndex) {
        // 过滤节点
        List<GoBoard.TreeNodeInfo> filteredNodes = new ArrayList<>();
        int startIndex = 0;

        if (branchIndex >= 0) {
            // 找到该分支点之后的节点
            for (int i = 0; i < treeNodes.size(); i++) {
                GoBoard.TreeNodeInfo info = treeNodes.get(i);
                if (info.hasBranches) {
                    // 这是一个分支点
                    if (branchIndex > 0) {
                        branchIndex--;
                        continue;
                    }
                    if (branchIndex == 0) {
                        startIndex = i;
                        branchIndex = -2; // 标记已找到
                        // 添加这个分支点本身
                        filteredNodes.add(info);
                        continue;
                    }
                }
                if (branchIndex == -2) {
                    filteredNodes.add(info);
                }
            }
        } else {
            // 全部节点
            filteredNodes = new ArrayList<>(treeNodes);
        }

        if (filteredNodes.isEmpty()) {
            Toast.makeText(this, "该分支没有步数", Toast.LENGTH_SHORT).show();
            return;
        }

        // 构建步数列表
        String[] moveItems = new String[filteredNodes.size()];
        final List<GoBoard.TreeNodeInfo> finalFilteredNodes = filteredNodes;
        int currentPosition = -1;

        for (int i = 0; i < filteredNodes.size(); i++) {
            GoBoard.TreeNodeInfo info = filteredNodes.get(i);
            GoBoard.Move move = info.node.move;
            String coordinate = convertToCoordinate(move.x, move.y);
            String playerText = move.player == GoBoard.BLACK ? "黑" : "白";

            StringBuilder display = new StringBuilder();
            display.append(i + 1).append(". ");
            display.append(playerText).append(" ").append(coordinate);

            // 显示分支数
            if (info.hasBranches) {
                display.append(" [").append(info.node.children.size()).append("分支]");
            }

            // 当前节点标记
            if (info.isCurrent) {
                display.append(" ◀");
                currentPosition = i;
            }

            moveItems[i] = display.toString();
        }

        // 创建布局
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(20, 20, 20, 20);

        // 标题
        TextView title = new TextView(this);
        String titleText = "选择步数 (共" + filteredNodes.size() + "步)";
        if (currentPosition >= 0) {
            titleText += " - 当前第" + (startIndex + currentPosition + 1) + "步";
        }
        title.setText(titleText);
        title.setTextSize(16);
        title.setTextColor(0xFF333333);
        title.setPadding(0, 0, 0, 10);
        layout.addView(title);

        // 定位按钮
        Button btnGoToCurrent = new Button(this);
        btnGoToCurrent.setText(currentPosition >= 0 ? "📍 定位当前" : "无当前步");
        btnGoToCurrent.setEnabled(currentPosition >= 0);
        layout.addView(btnGoToCurrent);

        // 列表视图
        ListView listView = new ListView(this);
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        listView.setLayoutParams(listParams);
        listView.setScrollingCacheEnabled(false);
        listView.setDividerHeight(1);

        // 设置适配器
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
            android.R.layout.simple_list_item_1, moveItems);
        listView.setAdapter(adapter);

        // 点击事件
        final int globalOffset = startIndex;
        final int finalCurrentPosition = currentPosition;
        listView.setOnItemClickListener((parent, view, position, id) -> {
            GoBoard.TreeNodeInfo targetInfo = finalFilteredNodes.get(position);
            boolean success = board.jumpToNode(targetInfo.node);
            if (success) {
                boardView.refresh();
                updateCommentDisplay();
                Toast.makeText(this, "已跳转到第" + (globalOffset + position + 1) + "步", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(MainActivity.this, "跳转失败", Toast.LENGTH_SHORT).show();
            }
        });

        // 定位按钮点击事件
        btnGoToCurrent.setOnClickListener(v -> {
            if (finalCurrentPosition >= 0) {
                listView.setSelection(finalCurrentPosition);
            }
        });

        // 自动定位到当前步
        if (finalCurrentPosition >= 0) {
            listView.post(() -> listView.setSelection(finalCurrentPosition));
        }

        layout.addView(listView);

        // 创建对话框
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(layout)
                .setNegativeButton("返回", (d, w) -> showJumpDialog())
                .setPositiveButton("取消", null)
                .create();

        // 点击事件 - 跳转后关闭对话框
        listView.setOnItemClickListener((parent, view, position, id) -> {
            GoBoard.TreeNodeInfo targetInfo = finalFilteredNodes.get(position);
            boolean success = board.jumpToNode(targetInfo.node);
            if (success) {
                boardView.refresh();
                updateCommentDisplay();
                Toast.makeText(this, "已跳转到第" + (globalOffset + position + 1) + "步", Toast.LENGTH_SHORT).show();
                dialog.dismiss(); // 关闭对话框
            } else {
                Toast.makeText(MainActivity.this, "跳转失败", Toast.LENGTH_SHORT).show();
            }
        });

        dialog.show();
    }

    /**
     * 构建树状前缀
     */
    private String buildTreePrefix(List<GoBoard.TreeNodeInfo> nodes, int currentIndex) {
        GoBoard.TreeNodeInfo current = nodes.get(currentIndex);

        if (current.depth == 0) {
            return "";
        }

        // 找到所有祖先节点
        StringBuilder prefix = new StringBuilder();
        int depth = current.depth;

        // 从根到当前节点的前一个深度，构建前缀
        for (int d = 0; d < depth - 1; d++) {
            // 找到当前深度的最后一个节点
            int lastIndexAtDepth = -1;
            for (int i = 0; i < currentIndex; i++) {
                if (nodes.get(i).depth == d) {
                    lastIndexAtDepth = i;
                }
            }

            if (lastIndexAtDepth >= 0) {
                GoBoard.TreeNodeInfo nodeAtDepth = nodes.get(lastIndexAtDepth);
                // 检查这个节点后面是否还有同级的兄弟
                boolean hasNextSibling = false;
                for (int i = lastIndexAtDepth + 1; i < currentIndex; i++) {
                    if (nodes.get(i).depth == d) {
                        hasNextSibling = true;
                        break;
                    }
                }

                if (hasNextSibling) {
                    prefix.append("│ ");
                } else {
                    prefix.append("  ");
                }
            }
        }

        // 当前层级的连接符
        // 检查是否是最后一个子节点
        boolean isLast = true;
        for (int i = currentIndex + 1; i < nodes.size(); i++) {
            if (nodes.get(i).depth == current.depth) {
                isLast = false;
                break;
            }
            if (nodes.get(i).depth < current.depth) {
                break;
            }
        }

        if (isLast) {
            prefix.append("└─");
        } else {
            prefix.append("├─");
        }

        return prefix.toString();
    }

    private void onPrevious() {
        // 上一步
        boolean success = board.previousMove();
        if (success) {
            boardView.refresh();
            updateCommentDisplay();
        }
    }

    private void onNext() {
        // 下一步
        boolean success = board.nextMove();
        if (success) {
            boardView.refresh();
            updateCommentDisplay();
        }
    }

    private void onPass() {
        // 虚手
        board.placeStone(-1, -1);
        boardView.refresh();
        updateCommentDisplay();
    }

    /**
     * 将棋盘坐标(x,y)转换为标准SGF坐标（如D4）
     * x: 0-18 -> A-T (跳过I)
     * y: 0-18 -> 1-19
     */
    private String convertToCoordinate(int x, int y) {
        String[] letters = {"A", "B", "C", "D", "E", "F", "G", "H", "J", "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T"};
        if (x < 0 || x >= 19 || y < 0 || y >= 19) {
            return "?";
        }
        return letters[x] + (y + 1);
    }

    private void onComment() {
        // 获取当前注释
        String currentComment = board.getCurrentComment();

        // 创建编辑对话框
        EditText editText = new EditText(this);
        editText.setText(currentComment);
        editText.setHint("输入注释...");
        editText.setMinLines(6); // 增大注释框

        new AlertDialog.Builder(this)
            .setTitle("编辑注释")
            .setView(editText)
            .setPositiveButton("保存", (dialog, which) -> {
                String newComment = editText.getText().toString();
                board.setCurrentComment(newComment);
                Toast.makeText(this, "注释已保存", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("取消", null)
            .show();
    }

    // 标记模式
    private boolean isMarkMode = false;
    private int currentMarkType = 0; // 0=圆圈, 1=叉号, 2=方块, 3=三角形
    private BoardView.OnMarkPlaceListener markPlaceListener;

    private void onMark() {
        if (isMarkMode) {
            // 退出标记模式
            isMarkMode = false;
            btnMark.setText("标记");
            boardView.refresh();
            return;
        }

        // 弹出标记类型选择
        String[] markTypes = {"圆圈 ○", "叉号 ✕", "方块 □", "三角形 △"};
        new AlertDialog.Builder(this)
            .setTitle("选择标记类型")
            .setItems(markTypes, (dialog, which) -> {
                currentMarkType = which;
                isMarkMode = true;
                boardView.setMarkMode(true);

                // 关闭摆子模式
                isPlaceMode = false;
                boardView.setPlaceMode(false);
                btnPlace.setText("摆子");

                btnMark.setText("完成标记");

                // 设置标记放置监听器
                markPlaceListener = (x, y) -> {
                    boolean found = false;
                    // 检查所有类型的标记
                    if (currentMarkType == 0) {
                        for (GoBoard.Position pos : board.getMarks()) {
                            if (pos.x == x && pos.y == y) { board.removeMark(x, y); found = true; break; }
                        }
                        if (!found) board.addMark(x, y);
                    } else if (currentMarkType == 1) {
                        for (GoBoard.Position pos : board.getCrossMarks()) {
                            if (pos.x == x && pos.y == y) { board.removeCrossMark(x, y); found = true; break; }
                        }
                        if (!found) board.addCrossMark(x, y);
                    } else if (currentMarkType == 2) {
                        for (GoBoard.Position pos : board.getSquareMarks()) {
                            if (pos.x == x && pos.y == y) { board.removeSquareMark(x, y); found = true; break; }
                        }
                        if (!found) board.addSquareMark(x, y);
                    } else if (currentMarkType == 3) {
                        for (GoBoard.Position pos : board.getTriangleMarks()) {
                            if (pos.x == x && pos.y == y) { board.removeTriangleMark(x, y); found = true; break; }
                        }
                        if (!found) board.addTriangleMark(x, y);
                    }
                    boardView.refresh();
                };
                boardView.setOnMarkPlaceListener(markPlaceListener);
                boardView.refresh();
            })
            .show();
    }
    
    private void onUndo() {
        System.out.println("onUndo 被点击");
        // 悔棋
        board.undo();
        boardView.refresh();
        updateCommentDisplay();
    }

    private void onDeleteBranch() {
        try {
            // 获取当前位置的分支
            List<Move> branchMoves = board.getBranchMoves();

            if (branchMoves == null || branchMoves.isEmpty()) {
                Toast.makeText(this, "当前位置没有分支", Toast.LENGTH_SHORT).show();
                return;
            }

            // 构建分支列表（带棋盘坐标）
            String[] branchItems = new String[branchMoves.size()];
            for (int i = 0; i < branchMoves.size(); i++) {
                Move move = branchMoves.get(i);
                String coordinate = convertToCoordinate(move.x, move.y);
                String playerName = move.player == GoBoard.BLACK ? "黑" : "白";
                branchItems[i] = "分支" + (i + 1) + ": " + playerName + "手 " + coordinate;
            }

            new AlertDialog.Builder(this)
                .setTitle("删除分支 (选择要删除的分支)")
                .setItems(branchItems, (dialog, which) -> {
                    try {
                        Move selectedMove = branchMoves.get(which);
                        if (selectedMove == null) {
                            Toast.makeText(MainActivity.this, "选择无效", Toast.LENGTH_SHORT).show();
                            return;
                        }

                        String coordinate = convertToCoordinate(selectedMove.x, selectedMove.y);

                        // 确认删除
                        new AlertDialog.Builder(MainActivity.this)
                            .setTitle("确认删除")
                            .setMessage("确定要删除这个分支吗？\n" +
                                (selectedMove.player == GoBoard.BLACK ? "黑" : "白") + "手 " + coordinate)
                            .setPositiveButton("删除", (d, w) -> {
                                try {
                                    boolean success = board.deleteBranch(selectedMove);
                                    if (success) {
                                        boardView.refresh();
                                        updateCommentDisplay();
                                        Toast.makeText(MainActivity.this, "分支已删除", Toast.LENGTH_SHORT).show();
                                    } else {
                                        Toast.makeText(MainActivity.this, "删除分支失败", Toast.LENGTH_SHORT).show();
                                    }
                                } catch (Exception e) {
                                    e.printStackTrace();
                                    Toast.makeText(MainActivity.this, "删除时出错: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                                }
                            })
                            .setNegativeButton("取消", null)
                            .show();
                    } catch (Exception e) {
                        e.printStackTrace();
                        Toast.makeText(MainActivity.this, "选择分支时出错: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "获取分支列表时出错: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 估算当前局面胜负
     */
    private void onScore() {
        // 获取胜负估算结果
        String scoreResult = board.getScoreResult();
        
        // 显示估算结果对话框
        new AlertDialog.Builder(this)
            .setTitle("胜负估算")
            .setMessage(scoreResult)
            .setPositiveButton("标记死子", (dialog, which) -> {
                // 弹出子力选择对话框
                showDeadStoneDialog();
            })
            .setNegativeButton("关闭", null)
            .setNeutralButton("清除标记", (dialog, which) -> {
                board.clearDeadStones();
                Toast.makeText(this, "已清除死子标记", Toast.LENGTH_SHORT).show();
                boardView.refresh();
            })
            .show();
    }

    /**
     * 显示标记死子对话框
     */
    private void showDeadStoneDialog() {
        String[] options = {"标记黑棋死子", "标记白棋死子"};
        
        new AlertDialog.Builder(this)
            .setTitle("选择标记")
            .setItems(options, (dialog, which) -> {
                if (which == 0) {
                    // 标记黑棋死子
                    Toast.makeText(this, "点击黑棋标记为死子", Toast.LENGTH_SHORT).show();
                    boardView.setDeadStoneMarkMode(true, GoBoard.BLACK);
                } else {
                    // 标记白棋死子
                    Toast.makeText(this, "点击白棋标记为死子", Toast.LENGTH_SHORT).show();
                    boardView.setDeadStoneMarkMode(true, GoBoard.WHITE);
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }


    
    private void loadFile(Uri uri) {
        // 显示文件路径
        String filePath = uri.toString();
        String fileName = uri.getLastPathSegment();
        Toast.makeText(this, "正在加载文件：" + fileName, Toast.LENGTH_LONG).show();
        
        // 检查权限
        InputStream inputStream = null;
        BufferedReader reader = null;
        try {
            // 尝试打开文件
            try {
                inputStream = getContentResolver().openInputStream(uri);
            } catch (Exception e) {
                Toast.makeText(this, "无法打开文件：" + e.getMessage(), Toast.LENGTH_LONG).show();
                e.printStackTrace();
                return;
            }
            
            if (inputStream == null) {
                Toast.makeText(this, "无法打开文件：输入流为空", Toast.LENGTH_SHORT).show();
                return;
            }
            
            // 尝试使用UTF-8编码读取文件
            try {
                reader = new BufferedReader(new InputStreamReader(inputStream, "UTF-8"));
            } catch (Exception e) {
                Toast.makeText(this, "无法创建读取器：" + e.getMessage(), Toast.LENGTH_LONG).show();
                e.printStackTrace();
                return;
            }
            
            StringBuilder stringBuilder = new StringBuilder();
            String line;
            int lineCount = 0;
            long fileSize = 0;
            
            try {
                while ((line = reader.readLine()) != null) {
                    stringBuilder.append(line);
                    stringBuilder.append('\n'); // 保留换行符
                    lineCount++;
                    fileSize += line.length() + 1; // 加上换行符
                }
            } catch (Exception e) {
                Toast.makeText(this, "读取文件时出错：" + e.getMessage(), Toast.LENGTH_LONG).show();
                e.printStackTrace();
                return;
            }
            
            String sgfContent = stringBuilder.toString();
            if (sgfContent.isEmpty()) {
                Toast.makeText(this, "无法打开文件：文件为空", Toast.LENGTH_SHORT).show();
                return;
            }
            
            // 调试：打印SGF内容的前100个字符
            System.out.println("=== SGF 内容 ===");
            System.out.println("长度: " + sgfContent.length());
            System.out.println("前100字符: " + (sgfContent.length() > 100 ? sgfContent.substring(0, 100) : sgfContent));
            System.out.println("===============");
            
            // 解析SGF文件
            try {
                SGFConverter.loadBoardFromSGFString(board, sgfContent);

                boardView.refresh();
                updateCommentDisplay();
            } catch (SGFParser.SGFParseException e) {
                e.printStackTrace();
                Toast.makeText(this, "无法解析SGF文件：" + e.getMessage(), Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                e.printStackTrace();
                Toast.makeText(this, "解析SGF文件时出错：" + e.getMessage(), Toast.LENGTH_LONG).show();
                // 显示堆栈跟踪信息
                StringBuilder errorMsg = new StringBuilder();
                for (StackTraceElement element : e.getStackTrace()) {
                    errorMsg.append(element.toString()).append("\n");
                }
                System.out.println("Error Stack Trace: " + errorMsg.toString());
            }
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "加载失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        } finally {
            // 确保关闭所有流
            try {
                if (reader != null) {
                    reader.close();
                }
                if (inputStream != null) {
                    inputStream.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
    
    private void saveFile(Uri uri) {
        try {
            FileOutputStream outputStream = (FileOutputStream) getContentResolver().openOutputStream(uri);
            OutputStreamWriter writer = new OutputStreamWriter(outputStream);
            
            // 生成SGF字符串
            String sgfString = SGFConverter.convertBoardToSGFString(board);
            writer.write(sgfString);
            
            writer.close();
            outputStream.close();
            
            Toast.makeText(this, getString(R.string.save_success), Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(this, getString(R.string.save_failed), Toast.LENGTH_SHORT).show();
        }
    }
    
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        // 保存当前棋局状态
        if (board != null) {
            String boardState = board.serialize();
            outState.putString("board_state", boardState);
        }
    }
    
    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        // 恢复棋局状态
        if (savedInstanceState != null && board != null) {
            String savedBoardState = savedInstanceState.getString("board_state", null);
            if (savedBoardState != null) {
                board.deserialize(savedBoardState);
                boardView.refresh();
                updateCommentDisplay();
            }
        }
    }
    
    @Override
    protected void onPause() {
        super.onPause();
        // 保存棋局到SharedPreferences，以便应用被杀死后也能恢复
        saveGameStateToPreferences();
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        // 刷新显示
        if (boardView != null) {
            boardView.refresh();
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 保存棋局状态
        saveGameStateToPreferences();
    }
    
    /**
     * 保存棋局状态到SharedPreferences
     */
    private void saveGameStateToPreferences() {
        if (board != null) {
            String boardState = board.serialize();
            getPreferences(Context.MODE_PRIVATE).edit()
                .putString("last_game_state", boardState)
                .apply();
        }
    }
    
    /**
     * 清除保存的棋局状态
     */
    private void clearSavedGameState() {
        getPreferences(Context.MODE_PRIVATE).edit()
            .remove("last_game_state")
            .apply();
    }
}