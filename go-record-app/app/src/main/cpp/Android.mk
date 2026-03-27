LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE    := gosgf
LOCAL_SRC_FILES := sgf_parser.c sgf_converter.c native_interface.c
LOCAL_LDLIBS    := -llog

include $(BUILD_SHARED_LIBRARY)
