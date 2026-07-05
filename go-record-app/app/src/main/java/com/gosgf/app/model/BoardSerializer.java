package com.gosgf.app.model;

import java.util.List;

/**
 * 棋盘序列化器 - 负责棋局状态的保存与恢复
 * 从 GoBoard 中提取，职责单一
 */
public class BoardSerializer {

    private static final int BOARD_SIZE = 19;
    private static final int SERIALIZE_VERSION = 1;

    /**
     * 序列化棋局状态
     */
    public static String serialize(
            int[][] board,
            int currentPlayer,
            List<GoBoard.Move> moveHistory,
            int handicap,
            List<GoBoard.Position> blackHandicapStones,
            List<GoBoard.Position> whiteHandicapStones,
            String blackPlayer,
            String whitePlayer,
            String result,
            String date) {

        StringBuilder sb = new StringBuilder();

        // 版本号
        sb.append(SERIALIZE_VERSION).append("|");

        // 棋盘状态
        for (int y = 0; y < BOARD_SIZE; y++) {
            for (int x = 0; x < BOARD_SIZE; x++) {
                sb.append(board[y][x]);
            }
        }
        sb.append("|");

        // 当前玩家
        sb.append(currentPlayer).append("|");

        // 落子历史
        for (GoBoard.Move move : moveHistory) {
            sb.append(move.x).append(",").append(move.y).append(",").append(move.player).append(";");
        }
        sb.append("|");

        // 让子数
        sb.append(handicap).append("|");

        // 黑棋让子
        for (GoBoard.Position pos : blackHandicapStones) {
            sb.append(pos.x).append(",").append(pos.y).append(";");
        }
        sb.append("|");

        // 白棋让子
        for (GoBoard.Position pos : whiteHandicapStones) {
            sb.append(pos.x).append(",").append(pos.y).append(";");
        }
        sb.append("|");

        // 游戏信息
        sb.append(escapeString(blackPlayer != null ? blackPlayer : "")).append("|");
        sb.append(escapeString(whitePlayer != null ? whitePlayer : "")).append("|");
        sb.append(escapeString(result != null ? result : "")).append("|");
        sb.append(escapeString(date != null ? date : ""));

        return sb.toString();
    }

    private static String escapeString(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                  .replace("|", "\\|")
                  .replace(";", "\\;")
                  .replace(",", "\\,");
    }

    private static String unescapeString(String str) {
        if (str == null) return "";
        return str.replace("\\,", ",")
                  .replace("\\;", ";")
                  .replace("\\|", "|")
                  .replace("\\\\", "\\");
    }

    /**
     * 反序列化结果
     */
    public static class DeserializeResult {
        public int[][] board;
        public int currentPlayer;
        public java.util.List<GoBoard.Move> moveHistory;
        public int handicap;
        public java.util.List<GoBoard.Position> blackHandicapStones;
        public java.util.List<GoBoard.Position> whiteHandicapStones;
        public String blackPlayer;
        public String whitePlayer;
        public String result;
        public String date;
        public boolean success;
    }

    /**
     * 反序列化棋局状态
     * @return DeserializeResult，success=false 表示解析失败
     */
    public static DeserializeResult deserialize(String serialized) {
        DeserializeResult result = new DeserializeResult();
        result.success = false;

        if (serialized == null || serialized.isEmpty()) return result;

        String[] parts = serialized.split("\\|", -1);

        int version = 0;
        int offset = 0;

        // 检测版本号
        try {
            version = Integer.parseInt(parts[0]);
            offset = 1;
        } catch (NumberFormatException e) {
            // 旧格式无版本号
            version = 0;
            offset = 0;
        }

        int requiredParts = offset + 10;
        if (parts.length < requiredParts) return result;

        try {
            result.board = new int[BOARD_SIZE][BOARD_SIZE];

            // 棋盘状态
            String boardStr = parts[offset];
            if (boardStr.length() == BOARD_SIZE * BOARD_SIZE) {
                for (int y = 0; y < BOARD_SIZE; y++) {
                    for (int x = 0; x < BOARD_SIZE; x++) {
                        int idx = y * BOARD_SIZE + x;
                        result.board[y][x] = Character.getNumericValue(boardStr.charAt(idx));
                    }
                }
            }

            // 当前玩家
            if (!parts[offset + 1].isEmpty()) {
                result.currentPlayer = Integer.parseInt(parts[offset + 1]);
            }

            // 落子历史
            result.moveHistory = new java.util.ArrayList<>();
            if (!parts[offset + 2].isEmpty()) {
                String[] moves = parts[offset + 2].split(";");
                for (String moveStr : moves) {
                    if (!moveStr.isEmpty()) {
                        String[] moveParts = moveStr.split(",");
                        if (moveParts.length == 3) {
                            int x = Integer.parseInt(moveParts[0]);
                            int y = Integer.parseInt(moveParts[1]);
                            int player = Integer.parseInt(moveParts[2]);
                            result.moveHistory.add(new GoBoard.Move(x, y, player));
                        }
                    }
                }
            }

            // 让子数
            if (!parts[offset + 3].isEmpty()) {
                result.handicap = Integer.parseInt(parts[offset + 3]);
            }

            // 黑棋让子
            result.blackHandicapStones = new java.util.ArrayList<>();
            if (!parts[offset + 4].isEmpty()) {
                String[] stones = parts[offset + 4].split(";");
                for (String stoneStr : stones) {
                    if (!stoneStr.isEmpty()) {
                        String[] stoneParts = stoneStr.split(",");
                        if (stoneParts.length == 2) {
                            result.blackHandicapStones.add(
                                new GoBoard.Position(Integer.parseInt(stoneParts[0]), Integer.parseInt(stoneParts[1])));
                        }
                    }
                }
            }

            // 白棋让子
            result.whiteHandicapStones = new java.util.ArrayList<>();
            if (!parts[offset + 5].isEmpty()) {
                String[] stones = parts[offset + 5].split(";");
                for (String stoneStr : stones) {
                    if (!stoneStr.isEmpty()) {
                        String[] stoneParts = stoneStr.split(",");
                        if (stoneParts.length == 2) {
                            result.whiteHandicapStones.add(
                                new GoBoard.Position(Integer.parseInt(stoneParts[0]), Integer.parseInt(stoneParts[1])));
                        }
                    }
                }
            }

            // 游戏信息
            result.blackPlayer = parts[offset + 6].isEmpty() ? "黑方" : unescapeString(parts[offset + 6]);
            result.whitePlayer = parts[offset + 7].isEmpty() ? "白方" : unescapeString(parts[offset + 7]);
            result.result = unescapeString(parts[offset + 8]);
            result.date = unescapeString(parts[offset + 9]);
            result.success = true;

        } catch (Exception e) {
            android.util.Log.e("BoardSerializer", "deserialize failed", e);
            result.success = false;
        }

        return result;
    }
}
