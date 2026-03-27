#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ctype.h>

// SGF节点结构
typedef struct SGFNode {
    char* id;
    char* data[100]; // 简化实现，存储属性键值对
    int data_count;
    struct SGFNode** children;
    int children_count;
    struct SGFNode* parent;
} SGFNode;

// 创建新节点
SGFNode* create_node() {
    SGFNode* node = (SGFNode*)malloc(sizeof(SGFNode));
    if (node) {
        node->id = NULL;
        node->data_count = 0;
        node->children = NULL;
        node->children_count = 0;
        node->parent = NULL;
        for (int i = 0; i < 100; i++) {
            node->data[i] = NULL;
        }
    }
    return node;
}

// 添加属性
void add_property(SGFNode* node, const char* key, const char* value) {
    if (node->data_count < 100) {
        char* property = (char*)malloc(strlen(key) + strlen(value) + 2);
        if (property) {
            sprintf(property, "%s=%s", key, value);
            node->data[node->data_count++] = property;
        }
    }
}

// 添加子节点
void add_child(SGFNode* parent, SGFNode* child) {
    if (child) {
        child->parent = parent;
        parent->children = (SGFNode**)realloc(parent->children, sizeof(SGFNode*) * (parent->children_count + 1));
        if (parent->children) {
            parent->children[parent->children_count++] = child;
        }
    }
}

// 释放节点
void free_node(SGFNode* node) {
    if (node) {
        if (node->id) {
            free(node->id);
        }
        for (int i = 0; i < node->data_count; i++) {
            if (node->data[i]) {
                free(node->data[i]);
            }
        }
        for (int i = 0; i < node->children_count; i++) {
            free_node(node->children[i]);
        }
        if (node->children) {
            free(node->children);
        }
        free(node);
    }
}

// 跳过空白字符
int skip_whitespace(const char* content, int index) {
    while (content[index] && isspace(content[index])) {
        index++;
    }
    return index;
}

// 解析属性值
int parse_property_value(const char* content, int index, char* value, int max_len) {
    int start = index;
    int len = 0;
    
    while (content[index] && content[index] != ']') {
        if (content[index] == '\\' && content[index + 1]) {
            // 处理转义字符
            value[len++] = content[index + 1];
            index += 2;
        } else {
            value[len++] = content[index++];
        }
        if (len >= max_len - 1) {
            break;
        }
    }
    
    value[len] = '\0';
    if (content[index] == ']') {
        index++;
    }
    
    return index;
}

// 解析节点
int parse_node(const char* content, int index, SGFNode* node) {
    while (content[index]) {
        char c = content[index];
        
        if (c == '(' || c == ')' || c == ';') {
            break;
        } else if (isspace(c)) {
            index = skip_whitespace(content, index);
        } else if (isalpha(c)) {
            // 解析属性键
            char key[10];
            int key_len = 0;
            while (content[index] && isalpha(content[index])) {
                key[key_len++] = content[index++];
                if (key_len >= 9) {
                    break;
                }
            }
            key[key_len] = '\0';
            
            // 解析属性值
            while (content[index] && content[index] == '[') {
                index++;
                char value[100];
                index = parse_property_value(content, index, value, 100);
                add_property(node, key, value);
            }
        } else {
            break;
        }
    }
    
    return index;
}

// 解析SGF树
int parse_tree(const char* content, int index, SGFNode* parent) {
    SGFNode* current_node = NULL;
    
    while (content[index]) {
        char c = content[index];
        
        if (c == '(') {
            // 开始新分支
            index++;
            index = skip_whitespace(content, index);
            
            if (content[index] == ';') {
                SGFNode* branch_node = create_node();
                index = parse_node(content, index + 1, branch_node);
                
                if (current_node) {
                    add_child(current_node, branch_node);
                } else {
                    add_child(parent, branch_node);
                }
                
                index = parse_tree(content, index, branch_node);
            }
        } else if (c == ')') {
            // 结束当前分支
            return index + 1;
        } else if (c == ';') {
            // 开始新节点
            SGFNode* node = create_node();
            add_child(parent, node);
            current_node = node;
            index = parse_node(content, index + 1, node);
        } else if (isspace(c)) {
            // 跳过空白字符
            index = skip_whitespace(content, index);
        } else {
            break;
        }
    }
    
    return index;
}

// 解析SGF内容
SGFNode* parse_sgf(const char* content) {
    if (!content || strlen(content) == 0) {
        return NULL;
    }
    
    // 跳过BOM
    if (content[0] == 0xEF && content[1] == 0xBB && content[2] == 0xBF) {
        content += 3;
    }
    
    // 跳过空白字符
    int index = skip_whitespace(content, 0);
    
    if (content[index] != '(') {
        return NULL;
    }
    
    index++;
    SGFNode* root = create_node();
    if (root) {
        index = parse_tree(content, index, root);
        
        // 处理根节点结构
        if (root->children_count == 1) {
            SGFNode* first_child = root->children[0];
            if (first_child->data_count > 0) {
                // 合并属性到根节点
                for (int i = 0; i < first_child->data_count; i++) {
                    if (first_child->data[i]) {
                        // 简化实现，直接复制
                        char* property = (char*)malloc(strlen(first_child->data[i]) + 1);
                        if (property) {
                            strcpy(property, first_child->data[i]);
                            if (root->data_count < 100) {
                                root->data[root->data_count++] = property;
                            } else {
                                free(property);
                            }
                        }
                    }
                }
                
                // 调整子节点
                if (first_child->children_count > 0) {
                    for (int i = 0; i < first_child->children_count; i++) {
                        add_child(root, first_child->children[i]);
                    }
                }
                
                // 释放第一个子节点
                first_child->children = NULL;
                first_child->children_count = 0;
                free_node(first_child);
                root->children[0] = NULL;
                root->children_count = 0;
                
                // 重新添加子节点
                for (int i = 0; i < root->children_count; i++) {
                    if (root->children[i]) {
                        SGFNode* child = root->children[i];
                        root->children[i] = NULL;
                        add_child(root, child);
                    }
                }
            }
        }
    }
    
    return root;
}

// 打印节点（调试用）
void print_node(SGFNode* node, int indent) {
    if (!node) {
        return;
    }
    
    for (int i = 0; i < indent; i++) {
        printf("  ");
    }
    
    printf("Node");
    if (node->id) {
        printf(" (id: %s)", node->id);
    }
    printf("\n");
    
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
