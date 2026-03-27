#ifndef SGF_CONVERTER_H
#define SGF_CONVERTER_H

#include "sgf_parser.h"

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
void init_board(GoBoard* board);

// 放置棋子
int place_stone(GoBoard* board, int x, int y, int player);

// 解析顶点坐标
int* parse_vertex(const char* vertex);

// 生成顶点坐标
char* generate_vertex(int x, int y);

// 从SGF节点加载走子
void load_moves_from_node(GoBoard* board, SGFNode* node);

// 处理节点属性
void process_node_properties(GoBoard* board, SGFNode* node);

// 从SGF加载到棋盘
void load_from_sgf(GoBoard* board, SGFNode* root);

// 转换棋盘到SGF节点
SGFNode* convert_to_sgf(GoBoard* board);

// 生成SGF字符串
char* generate_sgf_string(SGFNode* root);

#endif // SGF_CONVERTER_H