package com.gosgf.app;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.Uri;
import android.widget.FrameLayout;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.content.SharedPreferences;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.gosgf.app.engine.KataGoEngine;
import com.gosgf.app.engine.KataGoEngine.AnalysisResult;
import com.gosgf.app.model.GoBoard;
import com.gosgf.app.model.GoBoard.Move;
import com.gosgf.app.util.SGFConverter;
import com.gosgf.app.util.SGFParser;
import com.gosgf.app.view.BoardView;
import com.gosgf.app.view.BoardView.AnalysisMark;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

    public class MainActivity extends AppCompatActivity {
    private GoBoard board;
    private BoardView boardView;
    private ScrollView commentScrollView;
    private TextView commentText;
    private TextView moveCountText; // 步数显示
    private BoardView.OnBranchSelectListener branchSelectListener;
    private BoardView.OnBranchDeleteListener branchDeleteListener;
    
    private ImageButton btnNew;
    private ImageButton btnLoad;
    private ImageButton btnSave;
    private ImageButton btnPrevious;
    private ImageButton btnNext;
    private ImageButton btnPass;
    private ImageButton btnComment;
    private ImageButton btnMark;
    private ImageButton btnPlace;
    private ImageButton btnDeleteBranch;
    private ImageButton btnScore;
    private ImageButton btnShowNumbers;
    private Button btnEngineAnalyze;

    // 摆子模式状态
    private boolean isPlaceMode = false;
    private TextView btnPlaceLabel;
    private java.util.List<View> toggleButtons; // 摆子时禁用的按钮

    // Activity Result Launchers
    private ActivityResultLauncher<Intent> loadFileLauncher;
    private ActivityResultLauncher<Intent> saveFileLauncher;
    private ActivityResultLauncher<Intent> modelPickerLauncher;

    // KataGo 引擎（随包内置）
    private KataGoEngine katagoEngine;
    private boolean enginePrepared = false;
    private Thread analysisThread = null; // 当前分析后台线程，用于生命周期安全回收

    // 估算：后台低算力校正用（与实时分析共用引擎，引擎忙则跳过校正）
    private int estimateToken = 0; // 每次估算+1，取代旧校正结果
    private AlertDialog lastEstimateDialog = null;
    private TextView lastEstimateLeadView = null;
    private final Handler scoreEstimateHandler = new Handler(Looper.getMainLooper());

    // 最近一次引擎分析结果（以引擎计算、相对“当前行棋方”为准）
    private double lastWinrate = -1;   // <0 表示尚无结果
    private double lastScoreLead = 0;  // 当前行棋方领先子数（负=落后）

    // Sabaki 风格：实时分析开关（开启后在棋盘上持续显示最优几手）
    private boolean liveAnalysis = false;
    // 全自动分析：每步落子/导航后立即自动分析（无需点击分析按钮）
    private boolean autoAnalyze = false;
    private boolean engineBusy = false;
    private int analysisToken = 0; // 每次分析+1，棋盘变化时取消旧分析使其结果作废
    private ValueAnimator liveBtnAnim;
    private Runnable liveTextRunnable;
    private int liveDot = 0;
    private final Handler liveTextHandler = new Handler(Looper.getMainLooper());

    // KataGo 设置（长按「实时分析」按钮弹出设置页，改动后下一次分析自动生效）
    private SharedPreferences katagoPrefs;
    private static final String PREF_MAX_VISITS = "katago_max_visits"; // 0..4 → 映射访问次数
    private static final String PREF_TOP_N = "katago_top_n";
    private static final String PREF_KOMI = "katago_komi";
    private static final String PREF_THREADS = "katago_threads";
    private static final String PREF_MODEL_PATH = "katago_model_path"; // 用户选定的模型绝对路径
    private static final String PREF_AUTO_ANALYZE = "katago_auto_analyze"; // 全自动分析开关
    // 分析强度档位 → 实际 maxVisits（EIGEN 纯 CPU 下档位越低越快；AGM H6 等低端机用低档）
    private static final int[] STRENGTH_VISITS = {50, 100, 200, 400, 800};
    private static final String[] KOMI_VALUES = {"7.5", "6.5", "0.5", "5.5", "0.0"};
    private static final Integer[] THREAD_VALUES = {1, 2, 4, 6, 8};
    
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
        // 恢复“显示步数”开关状态
        boolean showNumbers = getPreferences(Context.MODE_PRIVATE).getBoolean("show_move_numbers", false);
        if (showNumbers) boardView.toggleMoveNumbers();
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

        // 模型文件选择器：从任意目录选择 .bin.gz 模型（KataGo 权重不再内置 APK）
        modelPickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    Intent data = result.getData();
                    if (data != null && data.getData() != null) {
                        importModelFile(data.getData());
                    }
                }
            }
        );
    }

    /** 从用户选择的 URI 复制模型到应用私有目录，并保存绝对路径 */
    private void importModelFile(android.net.Uri uri) {
        try {
            // 持久化权限，便于后续访问（即使应用重启）
            final int takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
            try {
                getContentResolver().takePersistableUriPermission(uri, takeFlags);
            } catch (SecurityException ignored) {}

            // 目标文件名保留源文件原名（不再统一重命名为 katago_model.*），
            // 但必须保证以 .gz 结尾，否则 KataGo 不会按 gzip 解压而加载失败。
            // 优先 DISPLAY_NAME → 其次 getLastPathSegment（可能是文档 ID 如 msf:xxx）
            String srcName = queryUriDisplayName(uri);
            if (srcName.isEmpty() && uri != null && uri.getLastPathSegment() != null) {
                srcName = uri.getLastPathSegment();
                int slash = Math.max(srcName.lastIndexOf('/'), srcName.lastIndexOf('\\'));
                if (slash >= 0) srcName = srcName.substring(slash + 1);
            }
            if (srcName.isEmpty()) {
                srcName = "katago_model_" + System.currentTimeMillis();
            }
            // 去掉可能混入的路径分隔符
            int slash = Math.max(srcName.lastIndexOf('/'), srcName.lastIndexOf('\\'));
            if (slash >= 0) srcName = srcName.substring(slash + 1);
            // 强制保证 .gz 后缀（KataGo 按扩展名判断是否 gzip；msf:xxx 这类无后缀名必须补）
            if (!srcName.toLowerCase().endsWith(".gz")) {
                // 若原名已带 .txt/.bin 等子类型，升级成 .txt.gz/.bin.gz；否则补 .bin.gz
                srcName = srcName + ".gz";
            }
            File dest = new File(getFilesDir(), srcName);
            try (java.io.InputStream in = getContentResolver().openInputStream(uri);
                 java.io.FileOutputStream fos = new java.io.FileOutputStream(dest)) {
                if (in == null) throw new java.io.IOException("无法打开模型文件");
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
            }
            katagoPrefs.edit().putString(PREF_MODEL_PATH, dest.getAbsolutePath()).apply();
            Log_e("已复制模型到内部: " + dest.getAbsolutePath(), null);
            // 直接在原 Toast 上显示真实文件名，确保用户立刻看到原名
            Toast.makeText(this, "已导入模型：" + dest.getName(), Toast.LENGTH_SHORT).show();
            // 模型变更：先停止可能正在进行的分析并等待线程结束，避免 close 与
            // analyze 并发访问同一 native 对象导致进程崩溃（native 崩溃 Java 层无法捕获）
            stopAnalysisAndWait();
            if (katagoEngine != null) katagoEngine.closeEngine();
            katagoEngine = null;
            enginePrepared = false;
            // 若分析已开启，立即用新模型重分析
            if (liveAnalysis) runAnalysis();
            // 刷新设置页上的路径显示
            if (modelPathText != null) {
                modelPathText.setText(dest.getName());
            }
        } catch (Exception e) {
            Log_e("导入模型失败", e);
            Toast.makeText(this, "导入模型失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private TextView modelPathText; // 设置页中显示当前模型路径的 TextView

    /** 从 DocumentProvider URI 查询文件显示名（部分文件管理器 getLastPathSegment 取不到时用） */
    private String queryUriDisplayName(android.net.Uri uri) {
        if (uri == null) return "";
        String name = "";
        try (android.database.Cursor c = getContentResolver()
                .query(uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME},
                        null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = c.getString(idx);
            }
        } catch (Exception ignored) {}
        return name != null ? name : "";
    }
    
    private void initButtons() {
        // 工具栏按钮
        btnNew = findViewById(R.id.btn_new);
        btnLoad = findViewById(R.id.btn_load);
        btnSave = findViewById(R.id.btn_save);

        // 导航栏按钮
        btnPrevious = findViewById(R.id.btn_previous);
        btnNext = findViewById(R.id.btn_next);
        btnPass = findViewById(R.id.btn_pass);

        // 功能栏按钮
        btnComment = findViewById(R.id.btn_comment);
        btnMark = findViewById(R.id.btn_mark);
        btnPlace = findViewById(R.id.btn_place);
        btnDeleteBranch = findViewById(R.id.btn_delete_branch);
        btnScore = findViewById(R.id.btn_score);
        btnShowNumbers = findViewById(R.id.btn_show_numbers);

        // 摆子模式标签
        btnPlaceLabel = findViewById(R.id.btn_place_label);

        // 摆子时需要禁用的按钮列表（排除 btnPlace 本身）
        toggleButtons = java.util.Arrays.asList(
            btnNew, btnLoad, btnSave, btnShowNumbers, btnScore,
            btnMark, btnPass, btnComment, btnDeleteBranch,
            btnPrevious, btnNext
        );

        // 设置点击监听器
        btnNew.setOnClickListener(v -> onNewGame());
        btnLoad.setOnClickListener(v -> onLoadGame());
        btnSave.setOnClickListener(v -> onSaveGame());

        btnPrevious.setOnClickListener(v -> onPrevious());
        btnPrevious.setOnLongClickListener(v -> { onPreviousMultiStep(); return true; });
        btnNext.setOnClickListener(v -> onNext());
        btnNext.setOnLongClickListener(v -> { onNextMultiStep(); return true; });
        btnPass.setOnClickListener(v -> onPass());

        btnComment.setOnClickListener(v -> onComment());
        btnMark.setOnClickListener(v -> onMark());
        btnPlace.setOnClickListener(v -> onPlace());
        btnDeleteBranch.setOnClickListener(v -> onDeleteBranch());
        btnScore.setOnClickListener(v -> onScore());
        btnShowNumbers.setOnClickListener(v -> onShowNumbers());

        // KataGo 引擎按钮（实时分析开关，开启后首次自动在后台初始化引擎）
        btnEngineAnalyze = findViewById(R.id.btn_engine_analyze);
        // 点击：Sabaki 风格的实时分析开关；长按：弹出 KataGo 设置页
        btnEngineAnalyze.setOnClickListener(v -> onAnalyzeButtonClick());
        btnEngineAnalyze.setOnLongClickListener(v -> { showKataGoSettings(); return true; });

        katagoPrefs = getSharedPreferences("katago_settings", MODE_PRIVATE);
        autoAnalyze = katagoPrefs.getBoolean(PREF_AUTO_ANALYZE, false);
        // 同步设置中的贴目到棋盘，确保估算口径与 KataGo 引擎一致
        board.setKomi((float) Double.parseDouble(katagoPrefs.getString(PREF_KOMI, "7.5")));
    }

    /**
     * 分析按钮单击：开启实时分析（若未开启）或直接重新分析当前局面（若已开启）。
     * 注意：点击按钮不会关闭分析——关闭只发生在落子/棋局变化时（见 cancelLiveAnalysis）。
     * 这样分析结束后用户未落子时，点一次即可重新分析，无需两次点击。
     */
    private void onAnalyzeButtonClick() {
        liveAnalysis = true;
        Toast.makeText(this, R.string.engine_analyzing, Toast.LENGTH_SHORT).show();
        runAnalysis();
    }

    /** 取消分析：清除棋盘标记并复位按钮（棋盘变化后调用，确保提示消失） */
    private void cancelLiveAnalysis() {
        // 无条件中止：使进行中的旧分析作废，避免旧结果回写新棋盘
        analysisToken++;
        liveAnalysis = false;
        stopLiveAnim();
        // 棋局已变化，旧分析结果失效，清除步数行末的胜率/优子
        lastWinrate = -1;
        lastScoreLead = 0;
        // 同步刷新步数行，去掉已失效的胜率/优子后缀（避免递归调用 updateCommentDisplay）
        if (moveCountText != null) {
            int cur = board.getCurrentMoveIndex();
            String pl = board.getCurrentPlayer() == GoBoard.BLACK ? "黑方" : "白方";
            moveCountText.setText("步数: " + cur + " " + pl);
        }
        if (btnEngineAnalyze != null) btnEngineAnalyze.setText(R.string.menu_engine_live_off);
        boardView.clearAnalysisMarks();
        boardView.refresh();
    }

    /** 长按「实时分析」弹出 KataGo 设置页（仅主要设置项） */
    private void showKataGoSettings() {
        View view = getLayoutInflater().inflate(R.layout.dialog_katago_settings, null);

        SeekBar seekStrength = view.findViewById(R.id.seek_strength);
        TextView tvStrength = view.findViewById(R.id.tv_strength);
        SeekBar seekTopN = view.findViewById(R.id.seek_topn);
        TextView tvTopN = view.findViewById(R.id.tv_topn);
        Spinner spinnerKomi = view.findViewById(R.id.spinner_komi);
        Spinner spinnerThreads = view.findViewById(R.id.spinner_threads);

        // 模型文件（从任意目录选择，不再内置 APK）
        TextView tvModelPath = view.findViewById(R.id.tv_model_path);
        modelPathText = tvModelPath;
        String curModel = katagoPrefs.getString(PREF_MODEL_PATH, "");
        if (curModel.isEmpty()) {
            tvModelPath.setText(R.string.katago_model_not_set);
        } else {
            tvModelPath.setText(new File(curModel).getName());
        }
        view.findViewById(R.id.btn_model_select).setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            // 模型可能是 .bin.gz 或 .txt.gz 等多种 gzip 格式，用 */* 显示所有文件，
            // 避免系统按扩展名/MIME 过滤掉 .txt.gz 这类文件。
            intent.setType("*/*");
            modelPickerLauncher.launch(intent);
        });

        // 分析强度（默认低档 100 visits：EIGEN 纯 CPU 下保证 AGM H6 等低端机可秒级返回）
        int strengthIdx = katagoPrefs.getInt(PREF_MAX_VISITS, 1);
        seekStrength.setProgress(strengthIdx);
        tvStrength.setText(String.valueOf(STRENGTH_VISITS[strengthIdx]));
        seekStrength.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                tvStrength.setText(String.valueOf(STRENGTH_VISITS[p]));
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        // 显示步数
        int topN = katagoPrefs.getInt(PREF_TOP_N, 5);
        seekTopN.setProgress(topN);
        tvTopN.setText(String.valueOf(topN));
        seekTopN.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                tvTopN.setText(String.valueOf(p));
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        // 贴目
        ArrayAdapter<String> komiAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, KOMI_VALUES);
        komiAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerKomi.setAdapter(komiAdapter);
        String curKomi = katagoPrefs.getString(PREF_KOMI, "7.5");
        for (int i = 0; i < KOMI_VALUES.length; i++) {
            if (KOMI_VALUES[i].equals(curKomi)) { spinnerKomi.setSelection(i); break; }
        }

        // 线程数
        ArrayAdapter<Integer> threadAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, THREAD_VALUES);
        threadAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerThreads.setAdapter(threadAdapter);
        int curThreads = katagoPrefs.getInt(PREF_THREADS, 4);
        for (int i = 0; i < THREAD_VALUES.length; i++) {
            if (THREAD_VALUES[i] == curThreads) { spinnerThreads.setSelection(i); break; }
        }

        // 全自动分析开关
        Switch switchAutoAnalyze = view.findViewById(R.id.switch_auto_analyze);
        switchAutoAnalyze.setChecked(katagoPrefs.getBoolean(PREF_AUTO_ANALYZE, false));

        new AlertDialog.Builder(this)
                .setView(view)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    boolean auto = switchAutoAnalyze.isChecked();
                    katagoPrefs.edit()
                            .putInt(PREF_MAX_VISITS, seekStrength.getProgress())
                            .putInt(PREF_TOP_N, seekTopN.getProgress())
                            .putString(PREF_KOMI, KOMI_VALUES[spinnerKomi.getSelectedItemPosition()])
                            .putInt(PREF_THREADS, THREAD_VALUES[spinnerThreads.getSelectedItemPosition()])
                            .putBoolean(PREF_AUTO_ANALYZE, auto)
                            .apply();
                    autoAnalyze = auto;
                    // 立即把设置中的贴目同步到棋盘，使估算口径随之更新
                    board.setKomi((float) Double.parseDouble(katagoPrefs.getString(PREF_KOMI, "7.5")));
                    // 开启全自动分析后立即分析当前局面；关闭则按既有逻辑（若手动分析开启则重分析）
                    if (autoAnalyze) {
                        liveAnalysis = true;
                        runAnalysis();
                    } else if (liveAnalysis) {
                        runAnalysis();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 分析进行时按钮变色 + "分析中..." 文字动画 */
    private void startLiveAnim() {
        if (liveBtnAnim != null) return; // 已在动画中
        // 只有后台分析进程真正在运行时才启动动画（否则不显示"分析中"）
        if (!engineBusy) return;
        // 文字动画：分析中 / 分析中. / 分析中.. / 分析中...
        liveDot = 0;
        liveTextRunnable = new Runnable() {
            @Override
            public void run() {
                if (!engineBusy) return; // 后台进程不在运行则停止动画
                liveDot = (liveDot + 1) % 4;
                StringBuilder s = new StringBuilder("分析中");
                for (int i = 0; i < liveDot; i++) s.append(".");
                if (btnEngineAnalyze != null) btnEngineAnalyze.setText(s.toString());
                liveTextHandler.postDelayed(this, 400);
            }
        };
        liveTextHandler.post(liveTextRunnable);
        // 背景色在橙色高亮与绿色之间呼吸脉冲
        int colorHot = Color.parseColor("#FF7A1A");
        int colorIdle = Color.parseColor("#2E7D32");
        liveBtnAnim = ValueAnimator.ofObject(new ArgbEvaluator(), colorHot, colorIdle);
        liveBtnAnim.setDuration(700);
        liveBtnAnim.setRepeatMode(ValueAnimator.REVERSE);
        liveBtnAnim.setRepeatCount(ValueAnimator.INFINITE);
        liveBtnAnim.addUpdateListener(a -> {
            if (btnEngineAnalyze != null)
                btnEngineAnalyze.setBackgroundTintList(
                        ColorStateList.valueOf((int) a.getAnimatedValue()));
        });
        liveBtnAnim.start();
    }

    /** 停止动画，恢复静态绿色按钮（必须在主线程执行，因涉及 View 动画） */
    private void stopLiveAnim() {
        liveTextHandler.removeCallbacks(liveTextRunnable);
        liveTextRunnable = null;
        runOnUiThread(() -> {
            if (liveBtnAnim != null) {
                liveBtnAnim.cancel();
                liveBtnAnim = null;
            }
            if (btnEngineAnalyze == null) return; // 按钮尚未初始化（如 onCreate 早期）
            btnEngineAnalyze.setBackgroundTintList(null);
            // 后台进程已停止：恢复为普通静态文案，不再显示"分析中"
            btnEngineAnalyze.setText(R.string.menu_engine_live_off);
        });
    }

    /**
     * Sabaki 风格：后台分析当前局面，仅在棋盘上用圆圈+胜率标出最优几手，不弹窗。
     * 落子 / 上一步 / 下一步 / 加载棋谱后都会自动调用，实现"不断给出最优步子"。
     */
    private void runAnalysis() {
        if (!liveAnalysis) {
            return;
        }
        if (engineBusy) {
            // 引擎忙时忽略本次请求（不自动排队，需用户再次点击）
            return;
        }
        if (board == null || board.getGameTree() == null) return;

        engineBusy = true;
        startLiveAnim();
        final int myToken = ++analysisToken; // 本次分析令牌，用于棋盘变化时丢弃旧结果

        analysisThread = new Thread(() -> {
            List<AnalysisMark> marks = null;
            boolean activityFinishing = isFinishing();
            try {
                if (katagoEngine == null) katagoEngine = new KataGoEngine();
                if (!enginePrepared) {
                    String modelPath = katagoPrefs.getString(PREF_MODEL_PATH, "");
                    String err = katagoEngine.prepare(this, modelPath);
                    if (err != null) {
                        Log_e("prepare 失败: " + err, null);
                        final String finalErr = err;
                        runOnUiThread(() -> Toast.makeText(this,
                                "引擎准备失败（完整错误见 logcat）：\n" + finalErr,
                                Toast.LENGTH_LONG).show());
                        return;
                    }
                    enginePrepared = true;
                }

                // 取当前真实棋盘状态发给引擎（不挖死子：死活完全由 KataGo 判定，全自动）。
                int[][] boardState = board.getBoard();
                int boardSize = boardState.length; // 棋盘边长（通常 19）
                int who = board.getCurrentPlayer();

                // 读取 KataGo 设置（长按按钮设置，下一次分析自动生效）
                int strengthIdx = katagoPrefs.getInt(PREF_MAX_VISITS, 1);
                // 自动/实时分析降档以加快结果返回（最多 100 visits）
                if (strengthIdx > 1) strengthIdx = 1;
                int maxVisits = STRENGTH_VISITS[strengthIdx];
                int threads = katagoPrefs.getInt(PREF_THREADS, 4);
                int topN = katagoPrefs.getInt(PREF_TOP_N, 5);
                boolean includePolicy = true;
                double komi = Double.parseDouble(katagoPrefs.getString(PREF_KOMI, "7.5"));
                // 同步设置中的贴目到棋盘（ScoreEstimator），使落子瞬间的启发式兜底
                // 与 KataGo 引擎使用同一贴目，避免覆盖时因贴目不一致而跳变。
                board.setKomi((float) komi);

                AnalysisResult result = katagoEngine.analyze(boardState, boardSize, maxVisits, who, komi, threads, includePolicy);

                // 保存引擎计算结果（以引擎为准，相对当前行棋方），用于步数行末显示
                lastWinrate = result.rootWinrate;
                lastScoreLead = result.rootScoreLead;

                // 只取最优 N 手，像 Sabaki 一样只在棋盘上给出最优的几个步子
                marks = new ArrayList<>();
                int top = Math.min(topN, result.moves.size());
                for (int i = 0; i < top; i++) {
                    KataGoEngine.AnalysisMove m = result.moves.get(i);
                    marks.add(new AnalysisMark(m.x, m.y, m.winrate, m.order));
                }
            } catch (Exception e) {
                Log_e("KataGo分析异常", e);
            } finally {
                engineBusy = false;
                activityFinishing = isFinishing();
                // 后台进程结束，无条件停止动画（不显示"分析中"）
                stopLiveAnim();
                // 若期间棋盘已变化（令牌不一致），丢弃旧局面的结果，保持提示消失
                final List<AnalysisMark> finalMarks = marks;
                if (myToken == analysisToken) {
                    runOnUiThread(() -> {
                        if (finalMarks != null) boardView.setAnalysisMarks(finalMarks);
                        else boardView.clearAnalysisMarks();
                        // 刷新步数行末的胜率/优子（以引擎结果为准）
                        int cur = board.getCurrentMoveIndex();
                        String pl = board.getCurrentPlayer() == GoBoard.BLACK ? "黑方" : "白方";
                        moveCountText.setText("步数: " + cur + " " + pl + buildAnalysisSuffix());
                    });
                }
                // 若 Activity 正在销毁，等分析线程结束后关闭 native 引擎，
                // 避免 Activity 已销毁但 native 仍在跑 / close 与 analyze 并发导致进程崩溃
                if (activityFinishing && katagoEngine != null) {
                    katagoEngine.closeEngine();
                    katagoEngine = null;
                    enginePrepared = false;
                }
                if (Thread.currentThread() == analysisThread) analysisThread = null;
            }
        });
        analysisThread.start();
    }

    /** 等待后台分析线程自然结束（最多 3s），避免与 closeEngine 并发。不改 liveAnalysis 开关本身。 */
    private void stopAnalysisAndWait() {
        Thread t = analysisThread;
        if (t != null && t.isAlive()) {
            try {
                t.join(3000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        analysisThread = null;
        engineBusy = false;
    }

    private void Log_e(String msg, Exception e) {
        android.util.Log.e("MainActivity", msg, e);
    }

    private void onBoardTouch(int x, int y) {
        boardView.setTerritoryMode(false);
        boolean success = board.placeStone(x, y);
        if (success) {
            boardView.refresh();
            updateCommentDisplay();
        } else {
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

    /** 以引擎计算结果为准，拼接“胜率/优子”后缀到步数行末（相对当前行棋方）。
     *  若引擎结果尚不可用（未分析/分析中），则提示“计算中…”，避免显示一个会被覆盖的粗糙估值误导用户。 */
    private String buildAnalysisSuffix() {
        if (lastWinrate >= 0) {
            double winPct = lastWinrate * 100.0;
            if (lastScoreLead >= 0) {
                return String.format(Locale.US, " | 胜率%.1f%% 优%.2f子", winPct, lastScoreLead);
            } else {
                return String.format(Locale.US, " | 胜率%.1f%% 差%.2f子", winPct, -lastScoreLead);
            }
        }
        // 兜底：引擎结果未回来前，统一提示“计算中…”，不在界面上呈现未定估值
        return " | 计算中…";
    }

    /**
     * 更新注释和步数显示
     */
    private void updateCommentDisplay() {
        // 更新步数显示
        int currentIndex = board.getCurrentMoveIndex();
        int totalMoves = board.getMoveHistory().size();
        String player = board.getCurrentPlayer() == GoBoard.BLACK ? "黑方" : "白方";
        moveCountText.setText("步数: " + currentIndex + " " + player + buildAnalysisSuffix());

        // 更新注释显示
        String comment = board.getCurrentComment();
        if (comment != null && !comment.isEmpty()) {
            commentText.setText(comment);
        } else {
            commentText.setText("");
        }

        // 局面已变化：若开启全自动分析，则立即对当前局面重新分析；
        // 否则清除旧标记并复位（需用户再次点击分析按钮）
        if (autoAnalyze && !isFinishing()) {
            liveAnalysis = true;
            // 局面已变，立即清除棋盘上一步的旧标记，避免残留，等新分析回来再绘制
            boardView.clearAnalysisMarks();
            // 若上一步分析仍在运行（engineBusy=true），runAnalysis 会直接丢弃本次请求，
            // 导致旧提示被 stopLiveAnim 清掉后再也不出现。这里先等待旧分析结束
            // （其 finally 因 token 不匹配不会重绘旧局面），再启动新的分析，提示即可连续显示。
            if (engineBusy) {
                stopAnalysisAndWait();
            }
            runAnalysis();
        } else {
            cancelLiveAnalysis();
        }
    }

    private void onPlace() {
        // 切换摆子模式
        isPlaceMode = !isPlaceMode;
        boardView.setPlaceMode(isPlaceMode);
        if (isPlaceMode) {
            // === 进入摆子模式 ===
            // 把当前棋盘状态同步到座子列表，以当前局面为基础修改
            board.syncBoardToHandicap();
            // 禁用其他按钮，改变摆子按钮外观
            setButtonsEnabledForSetup(false);
            btnPlaceLabel.setText("完成");
            btnPlace.setBackgroundResource(R.drawable.btn_primary);
            Toast.makeText(this, "在当前局面上摆子（黑棋）", Toast.LENGTH_SHORT).show();
        } else {
            // === 完成摆子 ===
            // 恢复按钮外观
            setButtonsEnabledForSetup(true);
            btnPlaceLabel.setText("摆子");
            btnPlace.setBackgroundResource(R.drawable.btn_secondary);

            // 保存当前座子（必须在后台线程之前因为board会被newGame重置）
            final java.util.List<GoBoard.Position> savedBlackStones = new java.util.ArrayList<>(board.getBlackHandicapStones());
            final java.util.List<GoBoard.Position> savedWhiteStones = new java.util.ArrayList<>(board.getWhiteHandicapStones());

            Toast.makeText(this, "处理中...", Toast.LENGTH_SHORT).show();

            // 耗时操作放到后台线程，避免主线程 ANR
            new Thread(() -> {
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

                // 自动提掉死子
                int deadCount = board.cleanupDeadStonesAfterSetup();

                // 最终统计
                int blackCount = board.getBlackHandicapStones().size();
                int whiteCount = board.getWhiteHandicapStones().size();
                int total = blackCount + whiteCount;
                // 注意：摆子不应等同于“让子数(handiap)”。handicap 表示标准让子布局，
                // 若按黑子数量设置，保存的 SGF 会带上错误的 HA，重新加载时 setupHandicap
                // 会按标准星位再放一批黑子（四角），导致多出棋子。摆子仅依赖座子列表(AB/AW)还原。
                board.setHandicap(0);

                String deadInfo = deadCount > 0 ? "，已自动提" + deadCount + "死子" : "";

                // 回到主线程弹对话框
                new Handler(Looper.getMainLooper()).post(() -> {
                    String[] playerOptions = {"黑方先手", "白方先手"};
                    new AlertDialog.Builder(MainActivity.this)
                        .setTitle("选择下一步")
                        .setItems(playerOptions, (dialog, which) -> {
                            if (which == 0) {
                                board.setCurrentPlayer(GoBoard.BLACK);
                                Toast.makeText(MainActivity.this, "已设置 " + blackCount + "黑 " + whiteCount + "白 共" + total + "子，黑方先手" + deadInfo, Toast.LENGTH_SHORT).show();
                            } else {
                                board.setCurrentPlayer(GoBoard.WHITE);
                                Toast.makeText(MainActivity.this, "已设置 " + blackCount + "黑 " + whiteCount + "白 共" + total + "子，白方先手" + deadInfo, Toast.LENGTH_SHORT).show();
                            }
                            boardView.refresh();
                            updateCommentDisplay();
                        })
                        .show();
                });
            }).start();
        }
    }

    /** 摆子模式下禁用/启用其他按钮 */
    private void setButtonsEnabledForSetup(boolean enabled) {
        float alpha = enabled ? 1.0f : 0.4f;
        for (View btn : toggleButtons) {
            btn.setEnabled(enabled);
            btn.setAlpha(alpha);
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

    private void onShowNumbers() {
        boardView.toggleMoveNumbers();
    }

    private void onPrevious() {
        boardView.setTerritoryMode(false);
        boolean success = board.previousMove();
        if (success) {
            boardView.refresh();
            updateCommentDisplay();
        }
    }

    private void onNext() {
        boardView.setTerritoryMode(false);
        boolean success = board.nextMove();
        if (success) {
            boardView.refresh();
            updateCommentDisplay();
        }
    }

    /** 长按上一步：跳转到绝对步数 */
    private void onPreviousMultiStep() {
        showJumpToStepDialog();
    }

    /** 长按下一步：跳转到绝对步数 */
    private void onNextMultiStep() {
        showJumpToStepDialog();
    }

    /**
     * 绝对步数跳转对话框
     * 输入目标步数（0=初始局面），直接跳到该步
     */
    private void showJumpToStepDialog() {
        int totalSteps = board.getTotalMoves();
        if (totalSteps <= 0) {
            Toast.makeText(this, "棋局没有走子，无法跳转", Toast.LENGTH_SHORT).show();
            return;
        }

        int currentStep = board.getCurrentMoveIndex();

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 30, 40, 10);

        TextView infoText = new TextView(this);
        infoText.setText("当前第 " + currentStep + " 步，共 " + totalSteps + " 步");
        infoText.setTextSize(15);
        infoText.setTextColor(0xFF333333);
        infoText.setPadding(0, 0, 0, 16);
        layout.addView(infoText);

        EditText input = new EditText(this);
        input.setHint("输入目标步数（0-" + totalSteps + "）");
        input.setTextSize(16);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setPadding(12, 10, 12, 10);
        input.setBackgroundResource(android.R.drawable.edit_text);
        layout.addView(input);

        new AlertDialog.Builder(this)
            .setTitle("跳转到指定步数")
            .setView(layout)
            .setPositiveButton("跳转", (dialog, which) -> {
                String text = input.getText().toString().trim();
                if (text.isEmpty()) {
                    Toast.makeText(MainActivity.this, "请输入步数", Toast.LENGTH_SHORT).show();
                    return;
                }
                int targetStep;
                try {
                    targetStep = Integer.parseInt(text);
                } catch (NumberFormatException e) {
                    Toast.makeText(MainActivity.this, "请输入有效数字", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (targetStep < 0 || targetStep > totalSteps) {
                    Toast.makeText(MainActivity.this, "步数超出范围（0-" + totalSteps + "）", Toast.LENGTH_SHORT).show();
                    return;
                }
                boardView.setTerritoryMode(false);
                final int target = targetStep;
                Toast.makeText(MainActivity.this, "跳转中...", Toast.LENGTH_SHORT).show();
                new Thread(() -> {
                    boolean ok = board.goToStep(target);
                    new Handler(Looper.getMainLooper()).post(() -> {
                        boardView.refresh();
                        updateCommentDisplay();
                        if (ok) {
                            Toast.makeText(MainActivity.this, "已跳转到第 " + target + " 步", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(MainActivity.this, "跳转失败", Toast.LENGTH_SHORT).show();
                        }
                    });
                }).start();
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void onPass() {
        boardView.setTerritoryMode(false);
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
        boardView.setTerritoryMode(false);
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
     * 估算当前局面胜负 —— 参照 Sabaki 的快速估算：纯启发式（影响图 + 死活判定），
     * 毫秒级出结果，不依赖 KataGo 引擎，因此总能「秒出」。
     * 结果以相对当前行棋方的目差展示（与自动分析的口径一致）。
     */
    /**
     * 估算：毫秒级纯启发式秒出弹窗，同时后台用极低 visits 的 KataGo scoring 校正
     * 「领先目数」并自动刷新弹窗。整体仍属「估算」（非精算），校正限时 1.5s。
     */
    private void onScore() {
        boardView.setTerritoryMode(true);

        // 同步设置中的贴目到棋盘（启发式估算使用同一贴目，避免口径不一致）
        double komi = Double.parseDouble(katagoPrefs.getString(PREF_KOMI, "7.5"));
        board.setKomi((float) komi);

        // 估算目数口径：子 + 确定死目(小目块) + 势力范围估算目（大空/争议区按势力归属）
        int bs = board.countBlackStones();
        int bDead = board.getDeadBlackTerritory();   // 确定死目
        int bInf = board.getInfluenceBlackPoints();  // 势力范围估算目
        int ws = board.countWhiteStones();
        int wDead = board.getDeadWhiteTerritory();
        int wInf = board.getInfluenceWhitePoints();
        int blackTotal = bs + bDead + bInf;          // 黑合计（含势力，不含贴目）
        int whiteTotal = ws + wDead + wInf;          // 白合计（含势力，不含贴目）

        // 面积法（已含势力范围）所得相对黑方目差（含贴目）
        float estDiffBlack = board.getEstimatedScoreDifference();
        final int estLeadBlack = (int) Math.round(estDiffBlack);
        final boolean blackToMove = board.getCurrentPlayer() == GoBoard.BLACK;

        // 立即用启发式填充弹窗（秒出，直白列出黑/白/贴目 + 结论）
        View view = getLayoutInflater().inflate(R.layout.dialog_score_estimate, null);

        ((TextView) view.findViewById(R.id.tv_black)).setText(
                String.format(Locale.US, "黑方　%d 目", blackTotal));
        ((TextView) view.findViewById(R.id.tv_white)).setText(
                String.format(Locale.US, "白方　%d 目", whiteTotal));
        ((TextView) view.findViewById(R.id.tv_komi)).setText(
                String.format(Locale.US, "黑方贴目　%.1f（黑 − 贴目 对比 白）", komi));

        final TextView tvLead = view.findViewById(R.id.tv_lead);
        if (estLeadBlack > 0) {
            tvLead.setText(String.format(Locale.US, "黑领先 %d 目", estLeadBlack));
        } else if (estLeadBlack < 0) {
            tvLead.setText(String.format(Locale.US, "白领先 %d 目", -estLeadBlack));
        } else {
            tvLead.setText("双方均势");
        }

        final AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("胜负估算")
            .setView(view)
            .setPositiveButton("确定", null)
            .show();
        lastEstimateDialog = dialog;
        lastEstimateLeadView = tvLead;

        // 后台用低 visits KataGo 校正（≤1.5s），回来自动刷新领先目数
        runEstimateRefresh(komi, blackToMove);
    }

    /**
     * 后台引擎估算校正：用比实时分析更高（受 2 秒预算封顶）的 visits 跑一次 KataGo scoring，
     * 回来刷新弹窗「领先目数」为引擎值（标注"引擎估算"）。仍为估算、不弹精算窗。
     * 引擎忙时短等（最多 ~500ms）以争取计算机会；拿不到则保留启发式结果。
     */
    private void runEstimateRefresh(final double komi, final boolean blackToMove) {
        if (katagoEngine == null || !enginePrepared) return;
        final int token = ++estimateToken;
        final int[][] snapshot = board.getBoard();
        final int boardSize = snapshot.length;
        final int nextPlayer = blackToMove ? GoBoard.BLACK : GoBoard.WHITE;
        final int threads = katagoPrefs.getInt(PREF_THREADS, 4);
        final int strengthIdx = katagoPrefs.getInt(PREF_MAX_VISITS, 1);
        // visits 比实时分析（≤100）更高，但封顶 200 以确保 2 秒内返回
        final int estVisits = Math.min(STRENGTH_VISITS[strengthIdx] * 3, 200);

        new Thread(() -> {
            // 引擎忙时短等，争取一次真正的计算机会（不阻塞 UI）
            long waited = 0;
            while (engineBusy && waited < 500) {
                try { Thread.sleep(50); waited += 50; } catch (InterruptedException ignored) { break; }
            }
            final double[] correction = {-1}; // -1 表示不可用
            try {
                if (engineBusy) return; // 仍忙，保留启发式结果
                KataGoEngine.AnalysisResult r = katagoEngine.analyze(
                        snapshot, boardSize, estVisits, nextPlayer, komi, threads, false);
                if (r != null) {
                    // rootScoreLead 为「当前行棋方」领先目数，折算为相对黑方
                    correction[0] = blackToMove ? r.rootScoreLead : -r.rootScoreLead;
                }
            } catch (Exception e) {
                // 校正失败：保留启发式结果，不影响估算弹窗
            }
            final double corrected = correction[0];
            if (token != estimateToken) return; // 已被新的估算取代
            scoreEstimateHandler.post(() -> {
                if (token != estimateToken) return;
                if (lastEstimateDialog == null || !lastEstimateDialog.isShowing()) return;
                if (lastEstimateLeadView == null) return;
                if (corrected > -0.5) { // 取到有效校正
                    int leadBlack = (int) Math.round(corrected);
                    String txt;
                    if (leadBlack > 0) {
                        txt = String.format(Locale.US, "黑领先 %d 目", leadBlack);
                    } else if (leadBlack < 0) {
                        txt = String.format(Locale.US, "白领先 %d 目", -leadBlack);
                    } else {
                        txt = "双方均势";
                    }
                    lastEstimateLeadView.setText(txt);
                }
            });
        }, "ScoreEstimateRefresh").start();

        // 1.8s 后强制失效本次校正（即使引擎未回，也已用启发式秒出，不影响体验）
        scoreEstimateHandler.postDelayed(() -> {
            if (token == estimateToken) estimateToken = -1;
        }, 1800);
    }

    private void loadFile(Uri uri) {
        // 显示文件路径
        String filePath = uri.toString();
        String fileName = uri.getLastPathSegment();
        
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
        // 保存“显示步数”开关状态
        getPreferences(Context.MODE_PRIVATE).edit()
            .putBoolean("show_move_numbers", boardView.isShowMoveNumbers())
            .apply();
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
        // 停止分析线程并在退出前关闭 native 引擎，避免 Activity 销毁后 native 仍在跑
        // 或 close 与分析并发导致进程崩溃
        stopAnalysisAndWait();
        if (katagoEngine != null) {
            katagoEngine.closeEngine();
            katagoEngine = null;
        }
        enginePrepared = false;
        // 取消可能仍在排队的估算校正，避免 Activity 销毁后刷新已释放的弹窗
        scoreEstimateHandler.removeCallbacksAndMessages(null);
        estimateToken = -1;
        lastEstimateDialog = null;
        lastEstimateLeadView = null;
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