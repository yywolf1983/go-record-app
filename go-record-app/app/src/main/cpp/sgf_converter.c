#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "sgf_parser.h"
#include <ctype.h>

// 棋盘结构
typedef struct GoBoard {
    int board[19][19];
    int move_history[1000][3]; // [x, y, player]
    int move_count;
    char black_player[100];
    char white_player[100];
    char result[100];
    char date[100];
    int handicap;
} GoBoard;

// 常量定义
#define EMPTY 0
#define BLACK 1
#define WHITE 2

// 初始化棋盘
void init_board(GoBoard* board) {
    if (board) {
        for (int i = 0; i < 19; i++) {
            for (int j = 0; j < 19; j++) {
                board->board[i][j] = EMPTY;
            }
        }
        board->move_count = 0;
        strcpy(board->black_player, "");
        strcpy(board->white_player, "");
        strcpy(board->result, "");
        strcpy(board->date, "");
        board->handicap = 0;
    }
}

// 放置棋子
int place_stone(GoBoard* board, int x, int y, int player) {
    if (!board || x < 0 || x >= 19 || y < 0 || y >= 19) {
        return 0;
    }
    
    if (board->board[x][y] != EMPTY) {
        return 0;
    }
    
    board->board[x][y] = player;
    
    // 记录走子
    if (board->move_count < 1000) {
        board->move_history[board->move_count][0] = x;
        board->move_history[board->move_count][1] = y;
        board->move_history[board->move_count][2] = player;
        board->move_count++;
    }
    
    return 1;
}

// 解析顶点坐标
int* parse_vertex(const char* vertex) {
    static int coord[2];
    if (!vertex || strlen(vertex) < 2) {
        coord[0] = -1;
        coord[1] = -1;
        return coord;
    }
    
    char x_char = tolower(vertex[0]);
    char y_char = tolower(vertex[1]);
    
    coord[0] = x_char - 'a';
    coord[1] = y_char - 'a';
    
    if (coord[0] < 0 || coord[0] >= 19 || coord[1] < 0 || coord[1] >= 19) {
        coord[0] = -1;
        coord[1] = -1;
    }
    
    return coord;
}

// 生成顶点坐标
char* generate_vertex(int x, int y) {
    static char vertex[3];
    if (x < 0 || x >= 19 || y < 0 || y >= 19) {
        vertex[0] = '\0';
        return vertex;
    }
    
    vertex[0] = 'a' + x;
    vertex[1] = 'a' + y;
    vertex[2] = '\0';
    
    return vertex;
}

// 从SGF节点加载走子
void load_moves_from_node(GoBoard* board, SGFNode* node) {
    if (!board || !node) {
        return;
    }
    
    // 处理当前节点的走子
    for (int i = 0; i < node->data_count; i++) {
        if (node->data[i]) {
            char* property = node->data[i];
            char key[10];
            char value[100];
            
            if (sscanf(property, "%[^=]=%s", key, value) == 2) {
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
    }
    
    // 递归处理第一个子节点（主分支）
    if (node->children_count > 0) {
        load_moves_from_node(board, node->children[0]);
    }
}

// 处理节点属性
void process_node_properties(GoBoard* board, SGFNode* node) {
    if (!board || !node) {
        return;
    }
    
    for (int i = 0; i < node->data_count; i++) {
        if (node->data[i]) {
            char* property = node->data[i];
            char key[10];
            char value[100];
            
            if (sscanf(property, "%[^=]=%s", key, value) == 2) {
                if (strcmp(key, "PB") == 0) {
                    strcpy(board->black_player, value);
                } else if (strcmp(key, "PW") == 0) {
                    strcpy(board->white_player, value);
                } else if (strcmp(key, "RE") == 0) {
                    strcpy(board->result, value);
                } else if (strcmp(key, "DT") == 0) {
                    strcpy(board->date, value);
                } else if (strcmp(key, "HA") == 0) {
                    board->handicap = atoi(value);
                }
            }
        }
    }
}

// 从SGF加载到棋盘
void load_from_sgf(GoBoard* board, SGFNode* root) {
    if (!board || !root) {
        return;
    }
    
    // 重置棋盘
    init_board(board);
    
    // 处理根节点属性
    process_node_properties(board, root);
    
    // 加载走子记录
    load_moves_from_node(board, root);
}

// 转换棋盘到SGF节点
SGFNode* convert_to_sgf(GoBoard* board) {
    if (!board) {
        return NULL;
    }
    
    SGFNode* root = create_node();
    if (!root) {
        return NULL;
    }
    
    // 添加游戏信息
    add_property(root, "GM", "1");
    add_property(root, "FF", "4");
    add_property(root, "SZ", "19");
    
    // 添加玩家信息
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
    
    // 添加让子信息
    char handicap_str[10];
    sprintf(handicap_str, "%d", board->handicap);
    add_property(root, "HA", handicap_str);
    
    // 构建游戏树
    SGFNode* current_node = root;
    for (int i = 0; i < board->move_count; i++) {
        int x = board->move_history[i][0];
        int y = board->move_history[i][1];
        int player = board->move_history[i][2];
        
        SGFNode* move_node = create_node();
        if (move_node) {
            char* vertex = generate_vertex(x, y);
            if (player == BLACK) {
                add_property(move_node, "B", vertex);
            } else if (player == WHITE) {
                add_property(move_node, "W", vertex);
            }
            
            add_child(current_node, move_node);
            current_node = move_node;
        }
    }
    
    return root;
}

// 生成SGF字符串
char* generate_sgf_string(SGFNode* root) {
    if (!root) {
        return NULL;
    }
    
    // 简化实现，返回基本信息
    static char sgf_str[1000];
    sgf_str[0] = '\0';
    
    strcat(sgf_str, "(");
    
    // 添加根节点属性
    for (int i = 0; i < root->data_count; i++) {
        if (root->data[i]) {
            char* property = root->data[i];
            char key[10];
            char value[100];
            
            if (sscanf(property, "%[^=]=%s", key, value) == 2) {
                strcat(sgf_str, ";");
                strcat(sgf_str, key);
                strcat(sgf_str, "[");
                strcat(sgf_str, value);
                strcat(sgf_str, "]");
            }
        }
    }
    
    // 添加子节点
    for (int i = 0; i < root->children_count; i++) {
        SGFNode* child = root->children[i];
        if (child) {
            for (int j = 0; j < child->data_count; j++) {
                if (child->data[j]) {
                    char* property = child->data[j];
                    char key[10];
                    char value[100];
                    
                    if (sscanf(property, "%[^=]=%s", key, value) == 2) {
                        strcat(sgf_str, ";");
                        strcat(sgf_str, key);
                        strcat(sgf_str, "[");
                        strcat(sgf_str, value);
                        strcat(sgf_str, "]");
                    }
                }
            }
        }
    }
    
    strcat(sgf_str, ")");
    
    return sgf_str;
}
