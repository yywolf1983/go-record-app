#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ctype.h>
#include "sgf_parser.h"

/* 创建新节点 */
SGFNode* create_node() {
    SGFNode* node = (SGFNode*)malloc(sizeof(SGFNode));
    if (node) {
        node->id = NULL;
        node->data_count = 0;
        node->children = NULL;
        node->children_count = 0;
        node->parent = NULL;
        for (int i = 0; i < SGF_MAX_DATA_COUNT; i++) {
            node->data[i] = NULL;
        }
    }
    return node;
}

/* 添加属性（安全版本） */
void add_property(SGFNode* node, const char* key, const char* value) {
    if (!node || !key || !value) return;
    if (node->data_count >= SGF_MAX_DATA_COUNT) return;

    size_t key_len = strlen(key);
    size_t value_len = strlen(value);

    /* 防止键值过长 */
    if (key_len >= SGF_MAX_KEY_LEN || value_len >= SGF_MAX_VALUE_LEN) return;

    size_t prop_len = key_len + 1 + value_len + 1; /* "key=value\0" */
    char* property = (char*)malloc(prop_len);
    if (property) {
        snprintf(property, prop_len, "%s=%s", key, value);
        node->data[node->data_count++] = property;
    }
}

/* 添加子节点（安全版本，检查 realloc 失败） */
void add_child(SGFNode* parent, SGFNode* child) {
    if (!parent || !child) return;

    child->parent = parent;

    SGFNode** new_children = (SGFNode**)realloc(
        parent->children,
        sizeof(SGFNode*) * (parent->children_count + 1)
    );
    if (!new_children) {
        /* realloc 失败，原指针 parent->children 仍然有效，不丢失 */
        return;
    }
    parent->children = new_children;
    parent->children[parent->children_count++] = child;
}

/* 释放节点（递归释放所有子节点和属性） */
void free_node(SGFNode* node) {
    if (!node) return;

    if (node->id) {
        free(node->id);
        node->id = NULL;
    }
    for (int i = 0; i < node->data_count; i++) {
        if (node->data[i]) {
            free(node->data[i]);
            node->data[i] = NULL;
        }
    }
    for (int i = 0; i < node->children_count; i++) {
        free_node(node->children[i]);
    }
    if (node->children) {
        free(node->children);
        node->children = NULL;
    }
    node->children_count = 0;
    node->data_count = 0;
    free(node);
}

/* 跳过空白字符 */
int skip_whitespace(const char* content, int index) {
    while (content[index] && isspace((unsigned char)content[index])) {
        index++;
    }
    return index;
}

/* 解析属性值（安全版本） */
int parse_property_value(const char* content, int index, char* value, int max_len) {
    int len = 0;

    while (content[index] && content[index] != ']') {
        if (len >= max_len - 1) {
            break;
        }
        if (content[index] == '\\' && content[index + 1]) {
            /* 处理转义字符 */
            value[len++] = content[index + 1];
            index += 2;
        } else {
            value[len++] = content[index++];
        }
    }

    value[len] = '\0';
    if (content[index] == ']') {
        index++;
    }

    return index;
}

/* 解析节点 */
int parse_node(const char* content, int index, SGFNode* node) {
    while (content[index]) {
        char c = content[index];

        if (c == '(' || c == ')' || c == ';') {
            break;
        } else if (isspace((unsigned char)c)) {
            index = skip_whitespace(content, index);
        } else if (isalpha((unsigned char)c)) {
            /* 解析属性键 */
            char key[SGF_MAX_KEY_LEN];
            int key_len = 0;
            while (content[index] && isalpha((unsigned char)content[index])) {
                if (key_len >= SGF_MAX_KEY_LEN - 1) {
                    break;
                }
                key[key_len++] = content[index++];
            }
            key[key_len] = '\0';

            /* 解析属性值 */
            while (content[index] && content[index] == '[') {
                index++;
                char value[SGF_MAX_VALUE_LEN];
                index = parse_property_value(content, index, value, SGF_MAX_VALUE_LEN);
                add_property(node, key, value);
            }
        } else {
            break;
        }
    }

    return index;
}

/* 解析SGF树 */
int parse_tree(const char* content, int index, SGFNode* parent) {
    SGFNode* current_node = NULL;

    while (content[index]) {
        char c = content[index];

        if (c == '(') {
            /* 开始新分支 */
            index++;
            index = skip_whitespace(content, index);

            if (content[index] == ';') {
                SGFNode* branch_node = create_node();
                if (!branch_node) return index;

                index = parse_node(content, index + 1, branch_node);

                if (current_node) {
                    add_child(current_node, branch_node);
                } else {
                    add_child(parent, branch_node);
                }

                index = parse_tree(content, index, branch_node);
            }
        } else if (c == ')') {
            /* 结束当前分支 */
            return index + 1;
        } else if (c == ';') {
            /* 开始新节点 */
            SGFNode* node = create_node();
            if (!node) return index;

            add_child(parent, node);
            current_node = node;
            index = parse_node(content, index + 1, node);
        } else if (isspace((unsigned char)c)) {
            /* 跳过空白字符 */
            index = skip_whitespace(content, index);
        } else {
            break;
        }
    }

    return index;
}

/* 解析SGF内容 */
SGFNode* parse_sgf(const char* content) {
    if (!content || strlen(content) == 0) {
        return NULL;
    }

    /* 跳过BOM */
    if ((unsigned char)content[0] == 0xEF &&
        (unsigned char)content[1] == 0xBB &&
        (unsigned char)content[2] == 0xBF) {
        content += 3;
    }

    /* 跳过空白字符 */
    int index = skip_whitespace(content, 0);

    if (content[index] != '(') {
        return NULL;
    }

    index++;
    SGFNode* root = create_node();
    if (!root) return NULL;

    index = parse_tree(content, index, root);

    /* 处理根节点结构：将第一个子节点的属性合并到根节点 */
    if (root->children_count == 1) {
        SGFNode* first_child = root->children[0];
        if (first_child && first_child->data_count > 0) {
            /* 合并属性到根节点 */
            for (int i = 0; i < first_child->data_count; i++) {
                if (first_child->data[i] && root->data_count < SGF_MAX_DATA_COUNT) {
                    char* property = (char*)malloc(strlen(first_child->data[i]) + 1);
                    if (property) {
                        strcpy(property, first_child->data[i]);
                        root->data[root->data_count++] = property;
                    }
                }
            }

            /* 收集第一个子节点的子节点 */
            SGFNode** saved_children = NULL;
            int saved_count = first_child->children_count;
            if (saved_count > 0) {
                saved_children = (SGFNode**)malloc(sizeof(SGFNode*) * saved_count);
                if (saved_children) {
                    for (int i = 0; i < saved_count; i++) {
                        saved_children[i] = first_child->children[i];
                    }
                }
            }

            /* 断开 first_child 与子节点的关联，防止 free_node 递归释放它们 */
            first_child->children = NULL;
            first_child->children_count = 0;
            free_node(first_child);

            /* 重置 root 的 children 数组 */
            free(root->children);
            root->children = NULL;
            root->children_count = 0;

            /* 将保存的子节点重新挂到 root 下 */
            if (saved_children) {
                for (int i = 0; i < saved_count; i++) {
                    add_child(root, saved_children[i]);
                }
                free(saved_children);
            }
        }
    }

    return root;
}

/* 打印节点（调试用） */
void print_node(SGFNode* node, int indent) {
    if (!node) return;

    for (int i = 0; i < indent; i++) {
        printf("  ");
    }

    printf("Node");
    if (node->id) {
        printf(" (id: %s)", node->id);
    }
    printf(" (%d children)\n", node->children_count);

    for (int i = 0; i < node->data_count; i++) {
        if (node->data[i]) {
            for (int j = 0; j < indent + 1; j++) {
                printf("  ");
            }
            printf("%s\n", node->data[i]);
        }
    }

    for (int i = 0; i < node->children_count; i++) {
        print_node(node->children[i], indent + 1);
    }
}
