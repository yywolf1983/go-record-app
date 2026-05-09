#ifndef SGF_CONVERTER_H
#define SGF_CONVERTER_H

#include "sgf_parser.h"

/* ===== 常量定义 ===== */
#define BOARD_SIZE          19
#define MAX_MOVE_HISTORY    2000    /* 最大记录步数（原1000，扩大以支持复杂棋谱） */
#define MAX_NAME_LEN        100     /* 玩家名字最大长度 */
#define MAX_VERTEX_LEN      3       /* SGF顶点坐标长度 "xx\0" */
#define MAX_GENERATE_SGF_LEN 4096   /* 生成SGF字符串最大长度 */

#define EMPTY 0
#define BLACK 1
#define WHITE 2

/* 棋盘结构 */
typedef struct GoBoard {
    int board[BOARD_SIZE][BOARD_SIZE];
    int move_history[MAX_MOVE_HISTORY][3]; /* [x, y, player] */
    int move_count;
    char black_player[MAX_NAME_LEN];
    char white_player[MAX_NAME_LEN];
    char result[MAX_NAME_LEN];
    char date[MAX_NAME_LEN];
    int handicap;
} GoBoard;

/* 初始化棋盘 */
void init_board(GoBoard* board);

/* 放置棋子 */
int place_stone(GoBoard* board, int x, int y, int player);

/* 解析顶点坐标（返回静态数组指针，调用者需立即使用或拷贝） */
int* parse_vertex(const char* vertex);

/* 生成顶点坐标字符串（返回静态缓冲区，调用者需立即使用或拷贝） */
char* generate_vertex(int x, int y);

/* 从SGF节点加载走子 */
void load_moves_from_node(GoBoard* board, SGFNode* node);

/* 处理节点属性 */
void process_node_properties(GoBoard* board, SGFNode* node);

/* 从SGF加载到棋盘 */
void load_from_sgf(GoBoard* board, SGFNode* root);

/* 转换棋盘到SGF节点 */
SGFNode* convert_to_sgf(GoBoard* board);

/* 生成SGF字符串（返回静态缓冲区，调用者需立即使用或拷贝） */
char* generate_sgf_string(SGFNode* root);

#endif /* SGF_CONVERTER_H */
