#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "sgf_converter.h"
#include "sgf_parser.h"

// 全局变量，存储SGF节点指针
static SGFNode* global_root = NULL;
static GoBoard* global_board = NULL;

// 辅助函数声明
SGFNode* get_node_by_path(int path);

// 初始化全局棋盘
void init_global_board() {
    if (!global_board) {
        global_board = (GoBoard*)malloc(sizeof(GoBoard));
        if (global_board) {
            init_board(global_board);
        }
    }
}

// 释放全局资源
void free_global_resources() {
    if (global_root) {
        free_node(global_root);
        global_root = NULL;
    }
    if (global_board) {
        free(global_board);
        global_board = NULL;
    }
}

// JNI方法：解析SGF内容
JNIEXPORT jboolean JNICALL Java_com_gosgf_app_util_SGFParser_nativeParse
(JNIEnv* env, jobject obj, jstring sgfContent) {
    if (!sgfContent) {
        return JNI_FALSE;
    }
    
    // 释放之前的资源
    if (global_root) {
        free_node(global_root);
        global_root = NULL;
    }
    
    // 获取SGF内容
    const char* content = (*env)->GetStringUTFChars(env, sgfContent, NULL);
    if (!content) {
        return JNI_FALSE;
    }
    
    // 解析SGF
    global_root = parse_sgf(content);
    
    // 释放字符串
    (*env)->ReleaseStringUTFChars(env, sgfContent, content);
    
    return global_root ? JNI_TRUE : JNI_FALSE;
}

// JNI方法：获取根节点属性
JNIEXPORT jobjectArray JNICALL Java_com_gosgf_app_util_SGFParser_nativeGetRootProperties
(JNIEnv* env, jobject obj) {
    if (!global_root) {
        return NULL;
    }
    
    // 创建属性数组
    jclass stringClass = (*env)->FindClass(env, "java/lang/String");
    if (!stringClass) {
        return NULL;
    }
    
    jobjectArray properties = (*env)->NewObjectArray(env, global_root->data_count, stringClass, NULL);
    if (!properties) {
        return NULL;
    }
    
    // 填充属性
    for (int i = 0; i < global_root->data_count; i++) {
        if (global_root->data[i]) {
            jstring property = (*env)->NewStringUTF(env, global_root->data[i]);
            if (property) {
                (*env)->SetObjectArrayElement(env, properties, i, property);
                (*env)->DeleteLocalRef(env, property);
            }
        }
    }
    
    return properties;
}

// JNI方法：获取子节点数量
JNIEXPORT jint JNICALL Java_com_gosgf_app_util_SGFParser_nativeGetChildrenCount
(JNIEnv* env, jobject obj) {
    if (!global_root) {
        return 0;
    }
    return global_root->children_count;
}

// JNI方法：获取子节点属性
JNIEXPORT jobjectArray JNICALL Java_com_gosgf_app_util_SGFParser_nativeGetChildProperties
(JNIEnv* env, jobject obj, jint childIndex) {
    SGFNode* node = NULL;
    
    if (childIndex < 1000) {
        // 直接子节点
        if (!global_root || childIndex < 0 || childIndex >= global_root->children_count) {
            return NULL;
        }
        node = global_root->children[childIndex];
    } else {
        // 子节点的子节点
        node = get_node_by_path(childIndex);
    }
    
    if (!node) {
        return NULL;
    }
    
    // 创建属性数组
    jclass stringClass = (*env)->FindClass(env, "java/lang/String");
    if (!stringClass) {
        return NULL;
    }
    
    jobjectArray properties = (*env)->NewObjectArray(env, node->data_count, stringClass, NULL);
    if (!properties) {
        return NULL;
    }
    
    // 填充属性
    for (int i = 0; i < node->data_count; i++) {
        if (node->data[i]) {
            jstring property = (*env)->NewStringUTF(env, node->data[i]);
            if (property) {
                (*env)->SetObjectArrayElement(env, properties, i, property);
                (*env)->DeleteLocalRef(env, property);
            }
        }
    }
    
    return properties;
}

// JNI方法：获取子节点的子节点数量
JNIEXPORT jint JNICALL Java_com_gosgf_app_util_SGFParser_nativeGetChildChildrenCount
(JNIEnv* env, jobject obj, jint childIndex) {
    SGFNode* node = NULL;
    
    if (childIndex < 1000) {
        // 直接子节点
        if (!global_root || childIndex < 0 || childIndex >= global_root->children_count) {
            return 0;
        }
        node = global_root->children[childIndex];
    } else {
        // 子节点的子节点
        node = get_node_by_path(childIndex);
    }
    
    if (!node) {
        return 0;
    }
    
    return node->children_count;
}

// 辅助函数：根据索引路径获取节点
SGFNode* get_node_by_path(int path) {
    if (!global_root) {
        return NULL;
    }
    
    // 简单的路径解析：假设path是一个编码的索引，例如1002表示第一个子节点的第二个子节点
    int childIndex = path / 1000;
    int grandChildIndex = path % 1000;
    
    if (childIndex < 0 || childIndex >= global_root->children_count) {
        return NULL;
    }
    
    SGFNode* child = global_root->children[childIndex];
    if (!child) {
        return NULL;
    }
    
    if (grandChildIndex < 0 || grandChildIndex >= child->children_count) {
        return NULL;
    }
    
    return child->children[grandChildIndex];
}

// JNI方法：从SGF加载到棋盘
JNIEXPORT jboolean JNICALL Java_com_gosgf_app_util_SGFConverter_nativeLoadFromSGF
(JNIEnv* env, jobject obj) {
    if (!global_root) {
        return JNI_FALSE;
    }
    
    init_global_board();
    if (!global_board) {
        return JNI_FALSE;
    }
    
    load_from_sgf(global_board, global_root);
    return JNI_TRUE;
}

// JNI方法：获取棋盘状态
JNIEXPORT jobjectArray JNICALL Java_com_gosgf_app_util_SGFConverter_nativeGetBoardState
(JNIEnv* env, jobject obj) {
    if (!global_board) {
        return NULL;
    }
    
    // 创建棋盘状态数组
    jclass intArrayClass = (*env)->FindClass(env, "[I");
    if (!intArrayClass) {
        return NULL;
    }
    
    jobjectArray boardState = (*env)->NewObjectArray(env, 19, intArrayClass, NULL);
    if (!boardState) {
        return NULL;
    }
    
    // 填充棋盘状态
    for (int i = 0; i < 19; i++) {
        jintArray row = (*env)->NewIntArray(env, 19);
        if (row) {
            jint rowData[19];
            for (int j = 0; j < 19; j++) {
                rowData[j] = global_board->board[i][j];
            }
            (*env)->SetIntArrayRegion(env, row, 0, 19, rowData);
            (*env)->SetObjectArrayElement(env, boardState, i, row);
            (*env)->DeleteLocalRef(env, row);
        }
    }
    
    return boardState;
}

// JNI方法：获取走子历史
JNIEXPORT jobjectArray JNICALL Java_com_gosgf_app_util_SGFConverter_nativeGetMoveHistory
(JNIEnv* env, jobject obj) {
    if (!global_board) {
        return NULL;
    }
    
    // 创建走子历史数组
    jclass intArrayClass = (*env)->FindClass(env, "[I");
    if (!intArrayClass) {
        return NULL;
    }
    
    jobjectArray moveHistory = (*env)->NewObjectArray(env, global_board->move_count, intArrayClass, NULL);
    if (!moveHistory) {
        return NULL;
    }
    
    // 填充走子历史
    for (int i = 0; i < global_board->move_count; i++) {
        jintArray move = (*env)->NewIntArray(env, 3);
        if (move) {
            jint moveData[3];
            moveData[0] = global_board->move_history[i][0];
            moveData[1] = global_board->move_history[i][1];
            moveData[2] = global_board->move_history[i][2];
            (*env)->SetIntArrayRegion(env, move, 0, 3, moveData);
            (*env)->SetObjectArrayElement(env, moveHistory, i, move);
            (*env)->DeleteLocalRef(env, move);
        }
    }
    
    return moveHistory;
}

// JNI方法：获取游戏信息
JNIEXPORT jobjectArray JNICALL Java_com_gosgf_app_util_SGFConverter_nativeGetGameInfo
(JNIEnv* env, jobject obj) {
    if (!global_board) {
        return NULL;
    }
    
    // 创建游戏信息数组
    jclass stringClass = (*env)->FindClass(env, "java/lang/String");
    if (!stringClass) {
        return NULL;
    }
    
    jobjectArray gameInfo = (*env)->NewObjectArray(env, 5, stringClass, NULL);
    if (!gameInfo) {
        return NULL;
    }
    
    // 填充游戏信息
    jstring blackPlayer = (*env)->NewStringUTF(env, global_board->black_player);
    jstring whitePlayer = (*env)->NewStringUTF(env, global_board->white_player);
    jstring result = (*env)->NewStringUTF(env, global_board->result);
    jstring date = (*env)->NewStringUTF(env, global_board->date);
    
    char handicapStr[10];
    sprintf(handicapStr, "%d", global_board->handicap);
    jstring handicap = (*env)->NewStringUTF(env, handicapStr);
    
    if (blackPlayer) (*env)->SetObjectArrayElement(env, gameInfo, 0, blackPlayer);
    if (whitePlayer) (*env)->SetObjectArrayElement(env, gameInfo, 1, whitePlayer);
    if (result) (*env)->SetObjectArrayElement(env, gameInfo, 2, result);
    if (date) (*env)->SetObjectArrayElement(env, gameInfo, 3, date);
    if (handicap) (*env)->SetObjectArrayElement(env, gameInfo, 4, handicap);
    
    // 释放局部引用
    if (blackPlayer) (*env)->DeleteLocalRef(env, blackPlayer);
    if (whitePlayer) (*env)->DeleteLocalRef(env, whitePlayer);
    if (result) (*env)->DeleteLocalRef(env, result);
    if (date) (*env)->DeleteLocalRef(env, date);
    if (handicap) (*env)->DeleteLocalRef(env, handicap);
    
    return gameInfo;
}

// JNI方法：释放资源
JNIEXPORT void JNICALL Java_com_gosgf_app_util_SGFParser_nativeFreeResources
(JNIEnv* env, jobject obj) {
    free_global_resources();
}
