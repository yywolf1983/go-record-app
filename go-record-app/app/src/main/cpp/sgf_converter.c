#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ctype.h>
#include "sgf_converter.h"

/* 初始化棋盘 */
void init_board(GoBoard* board) {
    if (!board) return;

    for (int i = 0; i < BOARD_SIZE; i++) {
        for (int j = 0; j < BOARD_SIZE; j++) {
            board->board[i][j] = EMPTY;
        }
    }
    board->move_count = 0;
    board->black_player[0] = '\0';
    board->white_player[0] = '\0';
    board->result[0] = '\0';
    board->date[0] = '\0';
    board->handicap = 0;
}

/* 放置棋子 */
int place_stone(GoBoard* board, int x, int y, int player) {
    if (!board || x < 0 || x >= BOARD_SIZE || y < 0 || y >= BOARD_SIZE) {
        return 0;
    }

    if (board->board[x][y] != EMPTY) {
        return 0;
    }

    board->board[x][y] = player;

    /* 记录走子 */
    if (board->move_count < MAX_MOVE_HISTORY) {
        board->move_history[board->move_count][0] = x;
        board->move_history[board->move_count][1] = y;
        board->move_history[board->move_count][2] = player;
        board->move_count++;
    }

    return 1;
}

/* 解析顶点坐标 */
int* parse_vertex(const char* vertex) {
    static int coord[2];
    if (!vertex || strlen(vertex) < 2) {
        coord[0] = -1;
        coord[1] = -1;
        return coord;
    }

    char x_char = (char)tolower((unsigned char)vertex[0]);
    char y_char = (char)tolower((unsigned char)vertex[1]);

    coord[0] = x_char - 'a';
    coord[1] = y_char - 'a';

    if (coord[0] < 0 || coord[0] >= BOARD_SIZE ||
        coord[1] < 0 || coord[1] >= BOARD_SIZE) {
        coord[0] = -1;
        coord[1] = -1;
    }

    return coord;
}

/* 生成顶点坐标字符串 */
char* generate_vertex(int x, int y) {
    static char vertex[MAX_VERTEX_LEN];
    if (x < 0 || x >= BOARD_SIZE || y < 0 || y >= BOARD_SIZE) {
        vertex[0] = '\0';
        return vertex;
    }

    vertex[0] = 'a' + x;
    vertex[1] = 'a' + y;
    vertex[2] = '\0';

    return vertex;
}

/* 从SGF节点加载走子 */
void load_moves_from_node(GoBoard* board, SGFNode* node) {
    if (!board || !node) return;

    /* 处理当前节点的走子 */
    for (int i = 0; i < node->data_count; i++) {
        if (!node->data[i]) continue;

        char* property = node->data[i];
        char key[SGF_MAX_KEY_LEN];
        char value[SGF_MAX_VALUE_LEN];

        if (sscanf(property, "%9[^=]=%1023s", key, value) == 2) {
            if (strcmp(key, "B") == 0) {
                int* coord = parse_vertex(value);
                if (coord[0] != -1 && coord[1] != -1) {
                    place_stone(board, coord[0], coord[1], BLACK);
                }
            } else if (strcmp(key, "W") == 0) {
                int* coord = parse_vertex(value);
                if (coord[0] != -1 && coord[1] != -1) {
                    place_stone(board, coord[0], coord[1], WHITE);
                }
            }
        }
    }

    /* 递归处理第一个子节点（主分支） */
    if (node->children_count > 0) {
        load_moves_from_node(board, node->children[0]);
    }
}

/* 处理节点属性 */
void process_node_properties(GoBoard* board, SGFNode* node) {
    if (!board || !node) return;

    for (int i = 0; i < node->data_count; i++) {
        if (!node->data[i]) continue;

        char* property = node->data[i];
        char key[SGF_MAX_KEY_LEN];
        char value[SGF_MAX_VALUE_LEN];

        if (sscanf(property, "%9[^=]=%1023s", key, value) == 2) {
            if (strcmp(key, "PB") == 0) {
                snprintf(board->black_player, MAX_NAME_LEN, "%s", value);
            } else if (strcmp(key, "PW") == 0) {
                snprintf(board->white_player, MAX_NAME_LEN, "%s", value);
            } else if (strcmp(key, "RE") == 0) {
                snprintf(board->result, MAX_NAME_LEN, "%s", value);
            } else if (strcmp(key, "DT") == 0) {
                snprintf(board->date, MAX_NAME_LEN, "%s", value);
            } else if (strcmp(key, "HA") == 0) {
                board->handicap = atoi(value);
            }
        }
    }
}

/* 从SGF加载到棋盘 */
void load_from_sgf(GoBoard* board, SGFNode* root) {
    if (!board || !root) return;

    init_board(board);
    process_node_properties(board, root);
    load_moves_from_node(board, root);
}

/* 转换棋盘到SGF节点 */
SGFNode* convert_to_sgf(GoBoard* board) {
    if (!board) return NULL;

    SGFNode* root = create_node();
    if (!root) return NULL;

    /* 添加游戏信息 */
    add_property(root, "GM", "1");
    add_property(root, "FF", "4");
    add_property(root, "SZ", "19");

    /* 添加玩家信息 */
    if (strlen(board->black_player) > 0) {
        add_property(root, "PB", board->black_player);
    }
    if (strlen(board->white_player) > 0) {
        add_property(root, "PW", board->white_player);
    }
    if (strlen(board->result) > 0) {
        add_property(root, "RE", board->result);
    }
    if (strlen(board->date) > 0) {
        add_property(root, "DT", board->date);
    }

    /* 添加让子信息 */
    char handicap_str[12];
    snprintf(handicap_str, sizeof(handicap_str), "%d", board->handicap);
    add_property(root, "HA", handicap_str);

    /* 构建游戏树 */
    SGFNode* current_node = root;
    for (int i = 0; i < board->move_count; i++) {
        int x = board->move_history[i][0];
        int y = board->move_history[i][1];
        int player = board->move_history[i][2];

        SGFNode* move_node = create_node();
        if (!move_node) continue;

        char* vertex = generate_vertex(x, y);
        if (player == BLACK) {
            add_property(move_node, "B", vertex);
        } else if (player == WHITE) {
            add_property(move_node, "W", vertex);
        }

        add_child(current_node, move_node);
        current_node = move_node;
    }

    return root;
}

/* 生成SGF字符串（安全版本） */
char* generate_sgf_string(SGFNode* root) {
    if (!root) return NULL;

    static char sgf_str[MAX_GENERATE_SGF_LEN];
    int remaining = MAX_GENERATE_SGF_LEN - 1;
    int pos = 0;

    sgf_str[0] = '\0';

    pos += snprintf(sgf_str + pos, remaining - pos, "(");
    remaining = MAX_GENERATE_SGF_LEN - 1 - pos;

    /* 添加根节点属性 */
    for (int i = 0; i < root->data_count && remaining > 0; i++) {
        if (!root->data[i]) continue;

        char* property = root->data[i];
        char key[SGF_MAX_KEY_LEN];
        char value[SGF_MAX_VALUE_LEN];

        if (sscanf(property, "%9[^=]=%1023s", key, value) == 2) {
            pos += snprintf(sgf_str + pos, remaining - pos, ";%s[%s]", key, value);
            remaining = MAX_GENERATE_SGF_LEN - 1 - pos;
        }
    }

    /* 添加子节点 */
    for (int i = 0; i < root->children_count && remaining > 0; i++) {
        SGFNode* child = root->children[i];
        if (!child) continue;

        for (int j = 0; j < child->data_count && remaining > 0; j++) {
            if (!child->data[j]) continue;

            char* property = child->data[j];
            char key[SGF_MAX_KEY_LEN];
            char value[SGF_MAX_VALUE_LEN];

            if (sscanf(property, "%9[^=]=%1023s", key, value) == 2) {
                pos += snprintf(sgf_str + pos, remaining - pos, ";%s[%s]", key, value);
                remaining = MAX_GENERATE_SGF_LEN - 1 - pos;
            }
        }
    }

    if (remaining > 0) {
        pos += snprintf(sgf_str + pos, remaining - pos, ")");
    }

    return sgf_str;
}
