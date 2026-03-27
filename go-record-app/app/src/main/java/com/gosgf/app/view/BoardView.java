package com.gosgf.app.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.gosgf.app.model.GoBoard;

public class BoardView extends View {
    private static final int BOARD_SIZE = 19;
    private static final int MARGIN = 50;
    private static final int STAR_SIZE = 8;
    
    private GoBoard board;
    private float cellSize;
    private float boardWidth;
    private float boardHeight;
    private Paint boardPaint;
    private Paint linePaint;
    private Paint blackStonePaint;
    private Paint whiteStonePaint;
    private Paint starPaint;
    private Paint lastMovePaint;
    private Paint markPaint;
    private Paint branchStonePaint; // 分支虚影画笔

    private OnBoardTouchListener touchListener;
    private OnBranchSelectListener branchSelectListener;
    private OnBranchDeleteListener branchDeleteListener;

    // 摆子模式
    private boolean isPlaceMode = false;
    private int lastPlaceX = -1; // 上一次摆子的位置
    private int lastPlaceY = -1;

    // 虚影位置缓存
    private java.util.Map<String, GoBoard.Move> branchPositions;
    private java.util.Set<String> selectedBranchPositions; // 已选择的分支位置

    // 长按检测
    private long touchDownTime;
    private int touchDownX, touchDownY;
    private static final long LONG_PRESS_THRESHOLD = 300; // 长按阈值 300ms

    public interface OnBoardTouchListener {
        void onBoardTouch(int x, int y);
    }

    public interface OnBranchSelectListener {
        void onBranchSelect(GoBoard.Move branchMove);
    }

    public interface OnBranchDeleteListener {
        void onBranchDelete(GoBoard.Move branchMove);
    }
    
    public BoardView(Context context) {
        super(context);
        init();
    }
    
    public BoardView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }
    
    public BoardView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }
    
    private void init() {
        // 初始化画笔
        boardPaint = new Paint();
        boardPaint.setColor(getResources().getColor(android.R.color.holo_orange_light));
        boardPaint.setStyle(Paint.Style.FILL);
        
        linePaint = new Paint();
        linePaint.setColor(getResources().getColor(android.R.color.darker_gray));
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(2);
        
        blackStonePaint = new Paint();
        blackStonePaint.setColor(Color.BLACK);
        blackStonePaint.setStyle(Paint.Style.FILL);
        
        whiteStonePaint = new Paint();
        whiteStonePaint.setColor(Color.WHITE);
        whiteStonePaint.setStyle(Paint.Style.FILL);
        
        starPaint = new Paint();
        starPaint.setColor(Color.BLACK);
        starPaint.setStyle(Paint.Style.FILL);
        
        lastMovePaint = new Paint();
        lastMovePaint.setColor(Color.RED);
        lastMovePaint.setStyle(Paint.Style.STROKE);
        lastMovePaint.setStrokeWidth(3);
        
        markPaint = new Paint();
        markPaint.setColor(Color.RED);
        markPaint.setStyle(Paint.Style.STROKE);
        markPaint.setStrokeWidth(2);

        branchStonePaint = new Paint();
        branchStonePaint.setStyle(Paint.Style.FILL);
        branchStonePaint.setAlpha(100); // 半透明

        branchPositions = new java.util.HashMap<>();
        selectedBranchPositions = new java.util.HashSet<>();
    }
    
    public void setBoard(GoBoard board) {
        this.board = board;
        invalidate();
    }
    
    public void setOnBoardTouchListener(OnBoardTouchListener listener) {
        this.touchListener = listener;
    }

    public void setOnBranchSelectListener(OnBranchSelectListener listener) {
        this.branchSelectListener = listener;
    }

    public void setOnBranchDeleteListener(OnBranchDeleteListener listener) {
        this.branchDeleteListener = listener;
    }

    // 设置已选择的分支位置
    public void setSelectedBranchPosition(String positionKey) {
        selectedBranchPositions.clear();
        selectedBranchPositions.add(positionKey);
        invalidate();
    }
    
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        int size = Math.min(width, height);

        setMeasuredDimension(size, size);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);

        // 使用实际可用尺寸,减去适当的边距
        int size = Math.min(w, h);
        int margin = Math.max(MARGIN, Math.min(w, h) / 40); // 动态边距
        boardWidth = size - 2 * margin;
        boardHeight = size - 2 * margin;
        cellSize = boardWidth / (BOARD_SIZE - 1);
    }
    
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        if (board == null) {
            return;
        }
        
        // 绘制棋盘背景
        canvas.drawRect(MARGIN, MARGIN, MARGIN + boardWidth, MARGIN + boardHeight, boardPaint);
        
        // 绘制棋盘线条
        drawBoardLines(canvas);
        
        // 绘制星位点
        drawStarPoints(canvas);

        // 绘制棋子
        drawStones(canvas);

        // 绘制分支虚影
        drawBranchStones(canvas);

        // 绘制最后一步
        drawLastMove(canvas);

        // 绘制坐标
        drawCoordinates(canvas);
    }
    
    private void drawBoardLines(Canvas canvas) {
        // 绘制横线
        for (int i = 0; i < BOARD_SIZE; i++) {
            float y = MARGIN + i * cellSize;
            canvas.drawLine(MARGIN, y, MARGIN + boardWidth, y, linePaint);
        }
        
        // 绘制竖线
        for (int i = 0; i < BOARD_SIZE; i++) {
            float x = MARGIN + i * cellSize;
            canvas.drawLine(x, MARGIN, x, MARGIN + boardHeight, linePaint);
        }
    }
    
    private void drawStarPoints(Canvas canvas) {
        // 星位点位置
        int[] starPoints = {3, 9, 15};

        for (int x : starPoints) {
            for (int y : starPoints) {
                float px = MARGIN + x * cellSize;
                float py = MARGIN + y * cellSize;
                canvas.drawCircle(px, py, STAR_SIZE, starPaint);
            }
        }
    }

    private void drawCoordinates(Canvas canvas) {
        // 坐标字母和数字
        String[] letters = {"A", "B", "C", "D", "E", "F", "G", "H", "J", "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T"};
        String[] numbers = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16", "17", "18", "19"};

        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setTextSize(cellSize * 0.6f);
        textPaint.setTextAlign(Paint.Align.CENTER);

        // 绘制顶部字母坐标
        for (int i = 0; i < BOARD_SIZE; i++) {
            float x = MARGIN + i * cellSize;
            float y = MARGIN - cellSize * 0.3f;
            textPaint.setColor(Color.BLACK);
            canvas.drawText(letters[i], x, y, textPaint);
        }

        // 绘制左侧数字坐标
        for (int i = 0; i < BOARD_SIZE; i++) {
            float x = MARGIN - cellSize * 0.5f;
            float y = MARGIN + i * cellSize + cellSize * 0.2f;
            textPaint.setColor(Color.BLACK);
            canvas.drawText(numbers[i], x, y, textPaint);
        }

        // 绘制右侧数字坐标
        for (int i = 0; i < BOARD_SIZE; i++) {
            float x = MARGIN + boardWidth + cellSize * 0.5f;
            float y = MARGIN + i * cellSize + cellSize * 0.2f;
            textPaint.setColor(Color.BLACK);
            canvas.drawText(numbers[i], x, y, textPaint);
        }

        // 绘制底部字母坐标
        for (int i = 0; i < BOARD_SIZE; i++) {
            float x = MARGIN + i * cellSize;
            float y = MARGIN + boardHeight + cellSize * 0.7f;
            textPaint.setColor(Color.BLACK);
            canvas.drawText(letters[i], x, y, textPaint);
        }
    }

    private void drawStones(Canvas canvas) {
        int[][] boardState = board.getBoard();

        for (int y = 0; y < BOARD_SIZE; y++) {
            for (int x = 0; x < BOARD_SIZE; x++) {
                int stone = boardState[y][x];
                if (stone != GoBoard.EMPTY) {
                    float px = MARGIN + x * cellSize;
                    float py = MARGIN + y * cellSize;
                    float radius = cellSize / 2 - 2.5f;

                    if (stone == GoBoard.BLACK) {
                        // 绘制黑子阴影
                        Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                        shadowPaint.setColor(0x40000000);
                        canvas.drawCircle(px + 2, py + 2, radius, shadowPaint);
                        // 绘制黑子
                        canvas.drawCircle(px, py, radius, blackStonePaint);
                        // 绘制高光
                        Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                        highlightPaint.setColor(0x80FFFFFF);
                        highlightPaint.setStyle(Paint.Style.FILL);
                        canvas.drawCircle(px - radius * 0.3f, py - radius * 0.3f, radius * 0.2f, highlightPaint);
                    } else if (stone == GoBoard.WHITE) {
                        // 绘制白子阴影
                        Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                        shadowPaint.setColor(0x40000000);
                        canvas.drawCircle(px + 2, py + 2, radius, shadowPaint);
                        // 绘制白子
                        canvas.drawCircle(px, py, radius, whiteStonePaint);
                        // 绘制白子边框
                        Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                        borderPaint.setColor(0xFF5D4037);
                        borderPaint.setStyle(Paint.Style.STROKE);
                        borderPaint.setStrokeWidth(1.5f);
                        canvas.drawCircle(px, py, radius, borderPaint);
                        // 绘制高光
                        Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                        highlightPaint.setColor(0xFFFFFFFF);
                        highlightPaint.setStyle(Paint.Style.FILL);
                        canvas.drawCircle(px - radius * 0.3f, py - radius * 0.3f, radius * 0.25f, highlightPaint);
                    }
                }
            }
        }
    }
    
    private void drawLastMove(Canvas canvas) {
        GoBoard.Move lastMove = board.getLastMove();
        if (lastMove != null && lastMove.x != -1 && lastMove.y != -1) {
            float px = MARGIN + lastMove.x * cellSize;
            float py = MARGIN + lastMove.y * cellSize;
            float radius = cellSize / 2;

            canvas.drawCircle(px, py, radius, lastMovePaint);
        }
    }

    private void drawBranchStones(Canvas canvas) {
        java.util.List<GoBoard.Move> branchMoves = board.getBranchMoves();

        // 清空位置缓存,但保留已选择的分支位置
        branchPositions.clear();

        // 使用索引作为分支编号
        for (int i = 0; i < branchMoves.size(); i++) {
            GoBoard.Move move = branchMoves.get(i);
            if (move.x == -1 || move.y == -1) {
                continue; // 跳过虚手
            }

            float px = MARGIN + move.x * cellSize;
            float py = MARGIN + move.y * cellSize;
            float radius = cellSize / 2 - 2.5f;
            String key = move.x + "," + move.y;
            int branchNumber = i + 1; // 分支编号从1开始

            // 检查是否是已选择的分支
            boolean isSelected = selectedBranchPositions.contains(key);
            if (isSelected) {
                // 绘制已选择分支的标记(外圈)
                Paint selectedPaint = new Paint();
                selectedPaint.setColor(Color.GREEN);
                selectedPaint.setStyle(Paint.Style.STROKE);
                selectedPaint.setStrokeWidth(4);
                canvas.drawCircle(px, py, radius + 3, selectedPaint);
            }

            // 根据棋子颜色设置虚影颜色
            if (move.player == GoBoard.BLACK) {
                branchStonePaint.setColor(Color.BLACK);
                branchStonePaint.setAlpha(isSelected ? 120 : 80); // 已选择的更不透明
            } else {
                branchStonePaint.setColor(Color.WHITE);
                branchStonePaint.setAlpha(isSelected ? 150 : 120);
            }

            canvas.drawCircle(px, py, radius, branchStonePaint);

            // 绘制分支编号
            Paint numberPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            numberPaint.setTextAlign(Paint.Align.CENTER);
            numberPaint.setTextSize(radius * 1.2f);
            // 根据棋子颜色选择文字颜色（黑子用白字，白子用黑字）
            if (move.player == GoBoard.BLACK) {
                numberPaint.setColor(Color.WHITE);
            } else {
                numberPaint.setColor(Color.BLACK);
            }

            // 计算文字基线位置，使其垂直居中
            Paint.FontMetrics fontMetrics = numberPaint.getFontMetrics();
            float textOffset = (fontMetrics.descent - fontMetrics.ascent) / 2 - fontMetrics.descent;
            canvas.drawText(String.valueOf(branchNumber), px, py + textOffset, numberPaint);

            // 记录虚影位置和对应的走法
            branchPositions.put(key, move);
        }
    }
    
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getAction();

        if (action == MotionEvent.ACTION_DOWN) {
            float x = event.getX();
            float y = event.getY();

            // 转换为棋盘坐标
            int boardX = (int) ((x - MARGIN + cellSize / 2) / cellSize);
            int boardY = (int) ((y - MARGIN + cellSize / 2) / cellSize);

            // 检查坐标是否在棋盘范围内
            if (boardX >= 0 && boardX < BOARD_SIZE && boardY >= 0 && boardY < BOARD_SIZE) {
                touchDownTime = System.currentTimeMillis();
                touchDownX = boardX;
                touchDownY = boardY;

                // 检查是否点击了分支虚影
                String key = boardX + "," + boardY;
                if (branchPositions.containsKey(key)) {
                    GoBoard.Move branchMove = branchPositions.get(key);
                    System.out.println("点击了分支虚影: " + branchMove.x + "," + branchMove.y);

                    if (branchSelectListener != null) {
                        branchSelectListener.onBranchSelect(branchMove);
                    }
                    return true;
                }

                // 如果在摆子模式下
                if (isPlaceMode) {
                    int currentStone = board.getBoard()[boardY][boardX];
                    boolean isSamePosition = (boardX == lastPlaceX && boardY == lastPlaceY);

                    if (isSamePosition) {
                        // 同一位置点击：黑棋→白棋→删除→黑棋 循环
                        if (currentStone == GoBoard.BLACK) {
                            // 黑棋 → 换成白棋
                            board.removeHandicapStone(boardX, boardY);
                            board.addWhiteHandicapStone(boardX, boardY);
                        } else if (currentStone == GoBoard.WHITE) {
                            // 白棋 → 删除
                            board.removeHandicapStone(boardX, boardY);
                            lastPlaceX = -1; // 重置位置
                            lastPlaceY = -1;
                        }
                    } else {
                        // 不同位置：放置黑棋
                        board.addBlackHandicapStone(boardX, boardY);
                        lastPlaceX = boardX;
                        lastPlaceY = boardY;
                    }
                    refresh();
                    return true;
                }

                // 如果点击的不是分支虚影，直接落子（创建新分支）
                // 无论是否有其他分支，都允许用户创建新分支
                if (touchListener != null) {
                    touchListener.onBoardTouch(boardX, boardY);
                }
                return true;
            }
        } else if (action == MotionEvent.ACTION_UP) {
            // 检查是否是长按
            long touchDuration = System.currentTimeMillis() - touchDownTime;
            if (touchDuration >= LONG_PRESS_THRESHOLD) {
                String key = touchDownX + "," + touchDownY;
                if (branchPositions.containsKey(key)) {
                    GoBoard.Move branchMove = branchPositions.get(key);
                    System.out.println("长按删除分支: " + branchMove.x + "," + branchMove.y);

                    if (branchDeleteListener != null) {
                        branchDeleteListener.onBranchDelete(branchMove);
                    }
                    return true;
                }
            }
        }

        return super.onTouchEvent(event);
    }
    
    // 辅助方法：将屏幕坐标转换为棋盘坐标
    public int[] screenToBoard(float x, float y) {
        int boardX = (int) ((x - MARGIN + cellSize / 2) / cellSize);
        int boardY = (int) ((y - MARGIN + cellSize / 2) / cellSize);
        
        if (boardX >= 0 && boardX < BOARD_SIZE && boardY >= 0 && boardY < BOARD_SIZE) {
            return new int[]{boardX, boardY};
        }
        return null;
    }
    
    // 辅助方法：将棋盘坐标转换为屏幕坐标
    public float[] boardToScreen(int x, int y) {
        float screenX = MARGIN + x * cellSize;
        float screenY = MARGIN + y * cellSize;
        return new float[]{screenX, screenY};
    }

    // 设置摆子模式
    public void setPlaceMode(boolean enabled) {
        this.isPlaceMode = enabled;
    }

    // 获取摆子模式状态
    public boolean isPlaceMode() {
        return isPlaceMode;
    }

    // 重绘棋盘
    public void refresh() {
        invalidate();
    }
}