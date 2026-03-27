package com.gosgf.app.view;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.gosgf.app.R;
import com.gosgf.app.model.GoBoard;

public class GameInfoView extends LinearLayout {
    private TextView moveCountTextView;

    public GameInfoView(Context context) {
        super(context);
        init();
    }

    public GameInfoView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public GameInfoView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        LayoutInflater.from(getContext()).inflate(R.layout.view_game_info, this, true);
        moveCountTextView = findViewById(R.id.move_count_text);
    }
    
    public void updateGameInfo(GoBoard board) {
        System.out.println("updateGameInfo被调用");
        if (board == null) {
            System.out.println("board为null,直接返回");
            return;
        }

        // 更新步数信息和当前执棋方
        try {
            java.lang.reflect.Field currentMoveIndexField = GoBoard.class.getDeclaredField("currentMoveIndex");
            currentMoveIndexField.setAccessible(true);
            int currentIndex = (int) currentMoveIndexField.get(board);
            int totalMoves = board.getMoveHistory().size();

            System.out.println("currentIndex=" + currentIndex + ", totalMoves=" + totalMoves);

            // 确定当前执棋方
            String currentPlayerText = "";
            if (totalMoves == 0) {
                // 没有任何棋步，显示黑方先手
                currentPlayerText = " 黑方";
            } else if (currentIndex < 0) {
                // 还没走任何一步，显示黑方先手
                currentPlayerText = " 黑方";
            } else if (currentIndex >= totalMoves - 1) {
                // 已经是最后一步了，显示下一步要落子的颜色
                // 最后一步如果是黑方，下一步是白方；最后一步如果是白方，下一步是黑方
                // moveHistory中第i步: i为偶数是黑方，i为奇数是白方
                int lastIndex = totalMoves - 1;
                if (lastIndex % 2 == 0) {
                    // 最后一步是黑方，下一步是白方
                    currentPlayerText = " 白方";
                } else {
                    // 最后一步是白方，下一步是黑方
                    currentPlayerText = " 黑方";
                }
            } else {
                // 还在浏览中间步骤，显示下一步要落子的颜色
                int displayedMoveIndex = currentIndex; // 当前显示的是第displayedMoveIndex+1步
                // 当前显示的如果是第n步（displayedMoveIndex），下一步是第n+1步
                // 如果第n步是黑方（displayedMoveIndex为偶数），下一步是白方
                // 如果第n步是白方（displayedMoveIndex为奇数），下一步是黑方
                if (displayedMoveIndex % 2 == 0) {
                    currentPlayerText = " 白方";
                } else {
                    currentPlayerText = " 黑方";
                }
                System.out.println("displayedMoveIndex=" + displayedMoveIndex + " → " + currentPlayerText);
            }

            String displayText = "步数: " + (currentIndex + 1) + " / " + totalMoves + currentPlayerText;
            System.out.println("设置文本: " + displayText);
            moveCountTextView.setText(displayText);
        } catch (Exception e) {
            e.printStackTrace();
            moveCountTextView.setText("步数: 0 / 0 黑方");
        }
    }
    
    private String getCurrentDate() {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd");
        return sdf.format(new java.util.Date());
    }
    
    public void setOnInfoClickListener(OnClickListener listener) {
        // 可以为各个信息项设置点击监听器
        moveCountTextView.setOnClickListener(listener);
    }
}