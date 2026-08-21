package com.gosgf.app.model;

import java.util.List;

/**
 * 棋盘序列化器 - 负责棋局状态的保存与恢复
 * 从 GoBoard 中提取，职责单一
 */
public class BoardSerializer {

    /** 版本历史:
     *  0: 无版本号, 旧格式, 固定 19 路
     *  1: 版本号在前, 固定 19 路, 12 字段
     *  2: 版本号后加 boardSize 字段(13 字段), 棋盘数据按实际 size 读写 (2026-08) */
    private static final int SERIALIZE_VERSION = 2;
    private static final int FALLBACK_SIZE = 19;

    /**
     * 序列化棋局状态。board 的行长按 board.length 决定棋盘大小。
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
            String date,
            int currentStep,
            String gameTreeBlob) {

        StringBuilder sb = new StringBuilder();
        int size = (board != null && board.length > 0) ? board.length : FALLBACK_SIZE;

        // 版本号
        sb.append(SERIALIZE_VERSION).append("|");
        // 版本 2+: 棋盘大小
        sb.append(size).append("|");

        // 棋盘状态: 按实际 size 写入
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
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
        sb.append(escapeString(date != null ? date : "")).append("|");

        // 当前步数（光标）：切后台/退出后恢复走到第几手
        sb.append(currentStep).append("|");

        // 完整游戏树（含变着分支/注释/光标节点 id）；旧存档为空
        // blob 自身包含 | , ; 分隔符，必须整体转义，否则 split("\\|") 会截断树片段
        sb.append(escapeString(gameTreeBlob != null ? gameTreeBlob : ""));

        return sb.toString();
    }

    // 注意：外层分隔符为 '|' 和 ';'，所以转义后【不能残留】这些字符本身，
    // 否则 deserialize 的 split("\\|") / split(";") 会把字段（尤其是游戏树 blob）
    // 错误切开。因此 | ; , 都转成不含竖线/分号的占位（\V \S \C）。
    static String escapeString(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                  .replace("|", "\\V")
                  .replace(";", "\\S")
                  .replace(",", "\\C");
    }

    static String unescapeString(String str) {
        if (str == null) return "";
        return str.replace("\\C", ",")
                  .replace("\\S", ";")
                  .replace("\\V", "|")
                  .replace("\\\\", "\\");
    }

    /**
     * 反序列化结果
     */
    public static class DeserializeResult {
        public int[][] board;
        public int boardSize = FALLBACK_SIZE;
        public int currentPlayer;
        public int currentStep = 0;
        public String gameTreeBlob = "";   // 完整游戏树片段（可能为空=旧格式）
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

        // 版本 2: 版本号后是 boardSize
        int size = FALLBACK_SIZE;
        if (version >= 2) {
            if (parts.length < offset + 1) return result;
            try {
                size = Integer.parseInt(parts[offset]);
            } catch (NumberFormatException e) { size = FALLBACK_SIZE; }
            if (size < 2) size = FALLBACK_SIZE;
            offset++;
        }
        result.boardSize = size;

        int requiredParts = offset + 12;
        if (parts.length < requiredParts) return result;

        try {
            result.board = new int[size][size];

            // 棋盘状态
            String boardStr = parts[offset];
            if (boardStr.length() == size * size) {
                for (int y = 0; y < size; y++) {
                    for (int x = 0; x < size; x++) {
                        int idx = y * size + x;
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

            // 当前步数（光标）
            result.currentStep = 0;
            if (!parts[offset + 10].isEmpty()) {
                try {
                    result.currentStep = Integer.parseInt(parts[offset + 10]);
                } catch (NumberFormatException ignored) {
                }
            }

            // 完整游戏树片段（可能为空=旧格式）；需先 unescape 还原内部 | , ; 分隔符
            result.gameTreeBlob = (offset + 11 < parts.length) ? unescapeString(parts[offset + 11]) : "";

            result.success = true;

        } catch (Exception e) {
            android.util.Log.e("BoardSerializer", "deserialize failed", e);
            result.success = false;
        }

        return result;
    }
}
