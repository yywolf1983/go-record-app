#ifndef SGF_PARSER_H
#define SGF_PARSER_H

/* ===== 安全常量定义 ===== */
#define SGF_MAX_DATA_COUNT  100   /* 单节点最大属性数 */
#define SGF_MAX_KEY_LEN     10    /* 属性键最大长度(含'\0') */
#define SGF_MAX_VALUE_LEN   1024  /* 属性值最大长度(含'\0') */
#define SGF_MAX_PROPERTY_LEN (SGF_MAX_KEY_LEN + SGF_MAX_VALUE_LEN + 2) /* "key=value\0" */

/* SGF节点结构 */
typedef struct SGFNode {
    char* id;
    char* data[SGF_MAX_DATA_COUNT]; /* 存储属性键值对 */
    int data_count;
    struct SGFNode** children;
    int children_count;
    struct SGFNode* parent;
} SGFNode;

/* 创建新节点 */
SGFNode* create_node();

/* 添加属性 */
void add_property(SGFNode* node, const char* key, const char* value);

/* 添加子节点 */
void add_child(SGFNode* parent, SGFNode* child);

/* 释放节点 */
void free_node(SGFNode* node);

/* 跳过空白字符 */
int skip_whitespace(const char* content, int index);

/* 解析属性值 */
int parse_property_value(const char* content, int index, char* value, int max_len);

/* 解析节点 */
int parse_node(const char* content, int index, SGFNode* node);

/* 解析SGF树 */
int parse_tree(const char* content, int index, SGFNode* parent);

/* 解析SGF内容 */
SGFNode* parse_sgf(const char* content);

/* 打印节点（调试用） */
void print_node(SGFNode* node, int indent);

#endif /* SGF_PARSER_H */
