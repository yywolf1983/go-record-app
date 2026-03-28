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
    private OnMarkPlaceListener markPlaceListener;

    // 摆子模式
    private boolean isPlaceMode = false;
    private int lastPlaceX = -1; // 上一次摆子的位置
    private int lastPlaceY = -1;

    // 标记模式
    private boolean isMarkMode = false;

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

    public interface OnMarkPlaceListener {
        void onMarkPlace(int x, int y);
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

    public void setOnMarkPlaceListener(OnMarkPlaceListener listener) {
        this.markPlaceListener = listener;
    }

    // 设置标记模式
    public void setMarkMode(boolean enabled) {
        this.isMarkMode = enabled;
        if (enabled) {
            isPlaceMode = false; // 关闭摆子模式
        }
    }

    // 获取标记模式状态
    public boolean isMarkMode() {
        return isMarkMode;
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

        // 使用实际可用尺寸，确保棋盘完全显示
        int size = Math.min(w, h);
        // 增加边距以确保坐标完全显示
        int margin = Math.max(MARGIN, Math.min(w, h) / 30); // 增大边距
        boardWidth = size - 2 * margin;
        boardHeight = size - 2 * margin;
        cellSize = boardWidth / (BOARD_SIZE - 1);

        // 棋盘整体向右偏移，给左侧坐标留出空间
        int offset = (int)(cellSize * 0.3f); // 增加偏移量
        marginLeft = margin + offset;

        // 棋盘向下偏移，给顶部坐标留出更多空间
        int offsetTop = (int)(cellSize * 0.1f); // 减少向上偏移
        marginTop = margin + offsetTop;
    }

    // 棋盘左边距（用于偏移棋盘位置）
    private int marginLeft = MARGIN;
    // 棋盘上边距（用于向上偏移）
    private int marginTop = MARGIN;
    
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        if (board == null) {
            return;
        }
        
        // 重新绘制整个棋盘
        // 上下外延均衡
        int topOuter = 30;

        // 绘制整个背景（橙色外延）
        Paint outerPaint = new Paint();
        outerPaint.setColor(getResources().getColor(android.R.color.holo_orange_light));
        outerPaint.setStyle(Paint.Style.FILL);
        canvas.drawRect(0, 0, getWidth(), getHeight(), outerPaint);

        // 棋盘背景（木纹色）
        // 棋盘顶部 = 上方外延
        float boardTop = topOuter;
        // 棋盘底部 = 上方外延 + 棋盘高度
        float boardBottom = topOuter + boardHeight;

        // 绘制棋盘背景
        canvas.drawRect(marginLeft, marginTop, marginLeft + boardWidth, marginTop + boardHeight, boardPaint);

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

        // 绘制标记
        drawMarks(canvas);

        // 绘制坐标
        drawCoordinates(canvas);
    }
    
    private void drawBoardLines(Canvas canvas) {
        // 绘制横线
        for (int i = 0; i < BOARD_SIZE; i++) {
            float y = marginTop + i * cellSize;
            canvas.drawLine(marginLeft, y, marginLeft + boardWidth, y, linePaint);
        }

        // 绘制竖线
        for (int i = 0; i < BOARD_SIZE; i++) {
            float x = marginLeft + i * cellSize;
            canvas.drawLine(x, marginTop, x, marginTop + boardHeight, linePaint);
        }
    }
    
    private void drawStarPoints(Canvas canvas) {
        // 星位点位置
        int[] starPoints = {3, 9, 15};

        for (int x : starPoints) {
            for (int y : starPoints) {
                float px = marginLeft + x * cellSize;
                float py = marginTop + y * cellSize;
                canvas.drawCircle(px, py, STAR_SIZE, starPaint);
            }
        }
    }

    private void drawCoordinates(Canvas canvas) {
        // 坐标字母和数字
        String[] letters = {"A", "B", "C", "D", "E", "F", "G", "H", "J", "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T"};
        String[] numbers = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16", "17", "18", "19"};

        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setTextSize(cellSize * 0.5f);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setColor(Color.BLACK);

        float offset = cellSize * 0.6f; // 与格子保持半个棋子以上距离

        // 只绘制顶部字母坐标（固定位置，不随棋盘移动）
        for (int i = 0; i < BOARD_SIZE; i++) {
            float x = marginLeft + i * cellSize;
            float y = marginTop - offset;
            canvas.drawText(letters[i], x, y, textPaint);
        }

        // 只绘制左侧数字坐标（固定位置，不随棋盘移动）
        for (int i = 0; i < BOARD_SIZE; i++) {
            float x = marginLeft - offset - cellSize * 0.2f; // 向左移动一些
            float y = marginTop + i * cellSize + cellSize * 0.15f;
            canvas.drawText(numbers[i], x, y, textPaint);
        }
    }

    private void drawStones(Canvas canvas) {
        int[][] boardState = board.getBoard();

        for (int y = 0; y < BOARD_SIZE; y++) {
            for (int x = 0; x < BOARD_SIZE; x++) {
                int stone = boardState[y][x];
                if (stone != GoBoard.EMPTY) {
                    float px = marginLeft + x * cellSize;
                    float py = marginTop + y * cellSize;
                    float radius = cellSize / 2 - 2f; // 增大棋子大小

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
            float px = marginLeft + lastMove.x * cellSize;
            float py = marginTop + lastMove.y * cellSize;
            float radius = cellSize / 2;

            canvas.drawCircle(px, py, radius, lastMovePaint);
        }
    }

    private void drawMarks(Canvas canvas) {
        float radius = cellSize / 3;
        float markerSize = cellSize / 2.5f;

        Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        circlePaint.setColor(Color.RED);
        circlePaint.setStyle(Paint.Style.STROKE);
        circlePaint.setStrokeWidth(3);

        Paint crossPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        crossPaint.setColor(Color.RED);
        crossPaint.setStyle(Paint.Style.STROKE);
        crossPaint.setStrokeWidth(3);

        Paint squarePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        squarePaint.setColor(Color.RED);
        squarePaint.setStyle(Paint.Style.STROKE);
        squarePaint.setStrokeWidth(3);

        Paint trianglePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        trianglePaint.setColor(Color.RED);
        trianglePaint.setStyle(Paint.Style.STROKE);
        trianglePaint.setStrokeWidth(3);

        // 绘制圆圈标记 (CR)
        java.util.List<GoBoard.Position> marks = board.getMarks();
        if (marks != null) {
            for (GoBoard.Position pos : marks) {
                float px = marginLeft + pos.x * cellSize;
                float py = marginTop + pos.y * cellSize;
                canvas.drawCircle(px, py, radius, circlePaint);
            }
        }

        // 绘制叉号标记 (MA)
        java.util.List<GoBoard.Position> crossMarks = board.getCrossMarks();
        if (crossMarks != null) {
            for (GoBoard.Position pos : crossMarks) {
                float px = marginLeft + pos.x * cellSize;
                float py = marginTop + pos.y * cellSize;
                float s = markerSize / 2;
                canvas.drawLine(px - s, py - s, px + s, py + s, crossPaint);
                canvas.drawLine(px + s, py - s, px - s, py + s, crossPaint);
            }
        }

        // 绘制方块标记 (SQ)
        java.util.List<GoBoard.Position> squareMarks = board.getSquareMarks();
        if (squareMarks != null) {
            for (GoBoard.Position pos : squareMarks) {
                float px = marginLeft + pos.x * cellSize;
                float py = marginTop + pos.y * cellSize;
                float s = markerSize / 2;
                canvas.drawRect(px - s, py - s, px + s, py + s, squarePaint);
            }
        }

        // 绘制三角形标记 (TR)
        java.util.List<GoBoard.Position> triangleMarks = board.getTriangleMarks();
        if (triangleMarks != null) {
            for (GoBoard.Position pos : triangleMarks) {
                float px = marginLeft + pos.x * cellSize;
                float py = marginTop + pos.y * cellSize;
                float s = markerSize / 2;
                android.graphics.Path path = new android.graphics.Path();
                path.moveTo(px, py - s);
                path.lineTo(px - s, py + s);
                path.lineTo(px + s, py + s);
                path.close();
                canvas.drawPath(path, trianglePaint);
            }
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

            float px = marginLeft + move.x * cellSize;
            float py = marginTop + move.y * cellSize;
            float radius = cellSize / 2 - 4f;
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
            int boardX = (int) ((x - marginLeft + cellSize / 2) / cellSize);
            int boardY = (int) ((y - marginTop + cellSize / 2) / cellSize);

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

                // 如果在标记模式下
                if (isMarkMode) {
                    if (markPlaceListener != null) {
                        markPlaceListener.onMarkPlace(boardX, boardY);
                    }
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
        int boardX = (int) ((x - marginLeft + cellSize / 2) / cellSize);
        int boardY = (int) ((y - MARGIN + cellSize / 2) / cellSize);

        if (boardX >= 0 && boardX < BOARD_SIZE && boardY >= 0 && boardY < BOARD_SIZE) {
            return new int[]{boardX, boardY};
        }
        return null;
    }

    // 辅助方法：将棋盘坐标转换为屏幕坐标
    public float[] boardToScreen(int x, int y) {
        float screenX = marginLeft + x * cellSize;
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