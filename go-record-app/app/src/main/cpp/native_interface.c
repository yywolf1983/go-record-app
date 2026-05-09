#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "sgf_converter.h"
#include "sgf_parser.h"

/* ===== 编码路径深度限制 ===== */
#define MAX_PATH_DEPTH  2
#define PATH_BASE       1000

/* 辅助函数声明 */
static SGFNode* get_node_by_path(SGFNode* root, int path);

/* ===== JNI方法：初始化解析器实例 ===== */
/*
 * 实例指针通过 Java 的 long 字段传递，替代全局变量。
 * Java 端需要声明: private long nativeHandle;
 */

/* 获取 native handle，存入 Java 对象的 nativeHandle 字段 */
static long get_handle(JNIEnv* env, jobject obj) {
    jclass cls = (*env)->GetObjectClass(env, obj);
    jfieldID fid = (*env)->GetFieldID(env, cls, "nativeHandle", "J");
    if (!fid) {
        /* 字段不存在时回退：使用全局变量方式（兼容旧代码） */
        return 0;
    }
    long handle = (long)(*env)->GetLongField(env, obj, fid);
    (*env)->DeleteLocalRef(env, cls);
    return handle;
}

static void set_handle(JNIEnv* env, jobject obj, long handle) {
    jclass cls = (*env)->GetObjectClass(env, obj);
    jfieldID fid = (*env)->GetFieldID(env, cls, "nativeHandle", "J");
    if (fid) {
        (*env)->SetLongField(env, obj, fid, (jlong)handle);
    }
    (*env)->DeleteLocalRef(env, cls);
}

/* 全局回退变量（兼容没有 nativeHandle 字段的旧 Java 类） */
static SGFNode* fallback_root = NULL;
static GoBoard* fallback_board = NULL;

typedef struct {
    SGFNode* root;
    GoBoard* board;
} NativeContext;

/* 获取或创建上下文 */
static NativeContext* get_context(JNIEnv* env, jobject obj) {
    long handle = get_handle(env, obj);
    if (handle) {
        return (NativeContext*)handle;
    }
    /* 回退到全局变量 */
    return NULL;
}

/* 初始化全局棋盘（兼容旧代码） */
static void init_global_board() {
    if (!fallback_board) {
        fallback_board = (GoBoard*)malloc(sizeof(GoBoard));
        if (fallback_board) {
            init_board(fallback_board);
        }
    }
}

/* 释放全局资源（兼容旧代码） */
static void free_global_resources() {
    if (fallback_root) {
        free_node(fallback_root);
        fallback_root = NULL;
    }
    if (fallback_board) {
        free(fallback_board);
        fallback_board = NULL;
    }
}

/* 安全释放 JNI 局部引用 */
static void safe_delete_local_ref(JNIEnv* env, jobject ref) {
    if (ref) {
        (*env)->DeleteLocalRef(env, ref);
    }
}

/* ===== JNI方法：创建/销毁实例 ===== */
JNIEXPORT jlong JNICALL Java_com_gosgf_app_util_SGFParser_nativeInit
(JNIEnv* env, jobject obj) {
    NativeContext* ctx = (NativeContext*)malloc(sizeof(NativeContext));
    if (!ctx) return 0;
    ctx->root = NULL;
    ctx->board = NULL;
    return (jlong)(long)ctx;
}

JNIEXPORT void JNICALL Java_com_gosgf_app_util_SGFParser_nativeDestroy
(JNIEnv* env, jobject obj) {
    NativeContext* ctx = get_context(env, obj);
    if (!ctx) return;
    if (ctx->root) {
        free_node(ctx->root);
        ctx->root = NULL;
    }
    if (ctx->board) {
        free(ctx->board);
        ctx->board = NULL;
    }
    free(ctx);
    set_handle(env, obj, 0);
}

/* ===== JNI方法：解析SGF内容 ===== */
JNIEXPORT jboolean JNICALL Java_com_gosgf_app_util_SGFParser_nativeParse
(JNIEnv* env, jobject obj, jstring sgfContent) {
    if (!sgfContent) {
        return JNI_FALSE;
    }

    NativeContext* ctx = get_context(env, obj);
    SGFNode** root_ptr;

    if (ctx) {
        root_ptr = &ctx->root;
    } else {
        /* 兼容旧代码 */
        root_ptr = &fallback_root;
        if (*root_ptr) {
            free_node(*root_ptr);
            *root_ptr = NULL;
        }
    }

    /* 释放之前的资源 */
    if (*root_ptr) {
        free_node(*root_ptr);
        *root_ptr = NULL;
    }

    /* 获取SGF内容 */
    const char* content = (*env)->GetStringUTFChars(env, sgfContent, NULL);
    if (!content) {
        return JNI_FALSE;
    }

    /* 解析SGF */
    *root_ptr = parse_sgf(content);

    /* 释放字符串 */
    (*env)->ReleaseStringUTFChars(env, sgfContent, content);

    return *root_ptr ? JNI_TRUE : JNI_FALSE;
}

/* ===== JNI方法：获取根节点属性 ===== */
JNIEXPORT jobjectArray JNICALL Java_com_gosgf_app_util_SGFParser_nativeGetRootProperties
(JNIEnv* env, jobject obj) {
    NativeContext* ctx = get_context(env, obj);
    SGFNode* root = ctx ? ctx->root : fallback_root;
    if (!root) {
        return NULL;
    }

    jclass stringClass = (*env)->FindClass(env, "java/lang/String");
    if (!stringClass) return NULL;

    jobjectArray properties = (*env)->NewObjectArray(env, root->data_count, stringClass, NULL);
    if (!properties) return NULL;

    for (int i = 0; i < root->data_count; i++) {
        if (root->data[i]) {
            jstring property = (*env)->NewStringUTF(env, root->data[i]);
            if (property) {
                (*env)->SetObjectArrayElement(env, properties, i, property);
                (*env)->DeleteLocalRef(env, property);
            }
        }
    }

    return properties;
}

/* ===== JNI方法：获取子节点数量 ===== */
JNIEXPORT jint JNICALL Java_com_gosgf_app_util_SGFParser_nativeGetChildrenCount
(JNIEnv* env, jobject obj) {
    NativeContext* ctx = get_context(env, obj);
    SGFNode* root = ctx ? ctx->root : fallback_root;
    if (!root) return 0;
    return root->children_count;
}

/* ===== JNI方法：获取子节点属性 ===== */
JNIEXPORT jobjectArray JNICALL Java_com_gosgf_app_util_SGFParser_nativeGetChildProperties
(JNIEnv* env, jobject obj, jint childIndex) {
    NativeContext* ctx = get_context(env, obj);
    SGFNode* root = ctx ? ctx->root : fallback_root;
    SGFNode* node = NULL;

    if (childIndex < PATH_BASE) {
        /* 直接子节点 */
        if (!root || childIndex < 0 || childIndex >= root->children_count) {
            return NULL;
        }
        node = root->children[childIndex];
    } else {
        /* 子节点的子节点（编码路径） */
        node = get_node_by_path(root, childIndex);
    }

    if (!node) return NULL;

    jclass stringClass = (*env)->FindClass(env, "java/lang/String");
    if (!stringClass) return NULL;

    jobjectArray properties = (*env)->NewObjectArray(env, node->data_count, stringClass, NULL);
    if (!properties) return NULL;

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

/* ===== JNI方法：获取子节点的子节点数量 ===== */
JNIEXPORT jint JNICALL Java_com_gosgf_app_util_SGFParser_nativeGetChildChildrenCount
(JNIEnv* env, jobject obj, jint childIndex) {
    NativeContext* ctx = get_context(env, obj);
    SGFNode* root = ctx ? ctx->root : fallback_root;
    SGFNode* node = NULL;

    if (childIndex < PATH_BASE) {
        if (!root || childIndex < 0 || childIndex >= root->children_count) {
            return 0;
        }
        node = root->children[childIndex];
    } else {
        node = get_node_by_path(root, childIndex);
    }

    if (!node) return 0;
    return node->children_count;
}

/* 辅助函数：根据编码索引路径获取节点
 * 编码规则：childIndex * PATH_BASE + grandChildIndex
 * 例如：1002 = 第1个子节点的第2个孙节点
 */
static SGFNode* get_node_by_path(SGFNode* root, int path) {
    if (!root) return NULL;

    int childIndex = path / PATH_BASE;
    int grandChildIndex = path % PATH_BASE;

    if (childIndex < 0 || childIndex >= root->children_count) {
        return NULL;
    }

    SGFNode* child = root->children[childIndex];
    if (!child) return NULL;

    if (grandChildIndex < 0 || grandChildIndex >= child->children_count) {
        return NULL;
    }

    return child->children[grandChildIndex];
}

/* ===== JNI方法：从SGF加载到棋盘 ===== */
JNIEXPORT jboolean JNICALL Java_com_gosgf_app_util_SGFConverter_nativeLoadFromSGF
(JNIEnv* env, jobject obj) {
    NativeContext* ctx = get_context(env, obj);
    SGFNode* root = ctx ? ctx->root : fallback_root;
    GoBoard** board_ptr;

    if (ctx) {
        board_ptr = &ctx->board;
    } else {
        board_ptr = &fallback_board;
        init_global_board();
    }

    if (!root) return JNI_FALSE;
    if (!*board_ptr) {
        *board_ptr = (GoBoard*)malloc(sizeof(GoBoard));
        if (*board_ptr) init_board(*board_ptr);
    }
    if (!*board_ptr) return JNI_FALSE;

    load_from_sgf(*board_ptr, root);
    return JNI_TRUE;
}

/* ===== JNI方法：获取棋盘状态 ===== */
JNIEXPORT jobjectArray JNICALL Java_com_gosgf_app_util_SGFConverter_nativeGetBoardState
(JNIEnv* env, jobject obj) {
    NativeContext* ctx = get_context(env, obj);
    GoBoard* board = ctx ? ctx->board : fallback_board;
    if (!board) return NULL;

    jclass intArrayClass = (*env)->FindClass(env, "[I");
    if (!intArrayClass) return NULL;

    jobjectArray boardState = (*env)->NewObjectArray(env, BOARD_SIZE, intArrayClass, NULL);
    if (!boardState) return NULL;

    for (int i = 0; i < BOARD_SIZE; i++) {
        jintArray row = (*env)->NewIntArray(env, BOARD_SIZE);
        if (!row) continue;

        jint rowData[BOARD_SIZE];
        for (int j = 0; j < BOARD_SIZE; j++) {
            rowData[j] = board->board[i][j];
        }
        (*env)->SetIntArrayRegion(env, row, 0, BOARD_SIZE, rowData);
        (*env)->SetObjectArrayElement(env, boardState, i, row);
        (*env)->DeleteLocalRef(env, row);
    }

    return boardState;
}

/* ===== JNI方法：获取走子历史 ===== */
JNIEXPORT jobjectArray JNICALL Java_com_gosgf_app_util_SGFConverter_nativeGetMoveHistory
(JNIEnv* env, jobject obj) {
    NativeContext* ctx = get_context(env, obj);
    GoBoard* board = ctx ? ctx->board : fallback_board;
    if (!board) return NULL;

    jclass intArrayClass = (*env)->FindClass(env, "[I");
    if (!intArrayClass) return NULL;

    jobjectArray moveHistory = (*env)->NewObjectArray(env, board->move_count, intArrayClass, NULL);
    if (!moveHistory) return NULL;

    for (int i = 0; i < board->move_count; i++) {
        jintArray move = (*env)->NewIntArray(env, 3);
        if (!move) continue;

        jint moveData[3];
        moveData[0] = board->move_history[i][0];
        moveData[1] = board->move_history[i][1];
        moveData[2] = board->move_history[i][2];
        (*env)->SetIntArrayRegion(env, move, 0, 3, moveData);
        (*env)->SetObjectArrayElement(env, moveHistory, i, move);
        (*env)->DeleteLocalRef(env, move);
    }

    return moveHistory;
}

/* ===== JNI方法：获取游戏信息 ===== */
JNIEXPORT jobjectArray JNICALL Java_com_gosgf_app_util_SGFConverter_nativeGetGameInfo
(JNIEnv* env, jobject obj) {
    NativeContext* ctx = get_context(env, obj);
    GoBoard* board = ctx ? ctx->board : fallback_board;
    if (!board) return NULL;

    jclass stringClass = (*env)->FindClass(env, "java/lang/String");
    if (!stringClass) return NULL;

    jobjectArray gameInfo = (*env)->NewObjectArray(env, 5, stringClass, NULL);
    if (!gameInfo) return NULL;

    jstring blackPlayer = (*env)->NewStringUTF(env, board->black_player);
    jstring whitePlayer = (*env)->NewStringUTF(env, board->white_player);
    jstring result = (*env)->NewStringUTF(env, board->result);
    jstring date = (*env)->NewStringUTF(env, board->date);

    char handicapStr[12];
    snprintf(handicapStr, sizeof(handicapStr), "%d", board->handicap);
    jstring handicap = (*env)->NewStringUTF(env, handicapStr);

    if (blackPlayer) (*env)->SetObjectArrayElement(env, gameInfo, 0, blackPlayer);
    if (whitePlayer) (*env)->SetObjectArrayElement(env, gameInfo, 1, whitePlayer);
    if (result)      (*env)->SetObjectArrayElement(env, gameInfo, 2, result);
    if (date)        (*env)->SetObjectArrayElement(env, gameInfo, 3, date);
    if (handicap)    (*env)->SetObjectArrayElement(env, gameInfo, 4, handicap);

    /* 释放局部引用 */
    safe_delete_local_ref(env, blackPlayer);
    safe_delete_local_ref(env, whitePlayer);
    safe_delete_local_ref(env, result);
    safe_delete_local_ref(env, date);
    safe_delete_local_ref(env, handicap);

    return gameInfo;
}

/* ===== JNI方法：释放资源 ===== */
JNIEXPORT void JNICALL Java_com_gosgf_app_util_SGFParser_nativeFreeResources
(JNIEnv* env, jobject obj) {
    /* 优先使用实例上下文 */
    NativeContext* ctx = get_context(env, obj);
    if (ctx) {
        if (ctx->root) {
            free_node(ctx->root);
            ctx->root = NULL;
        }
        if (ctx->board) {
            free(ctx->board);
            ctx->board = NULL;
        }
        free(ctx);
        set_handle(env, obj, 0);
        return;
    }
    /* 兼容旧代码：回退到全局变量 */
    free_global_resources();
}
