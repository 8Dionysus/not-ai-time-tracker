LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := tracker_copy_io
LOCAL_SRC_FILES := private_copy_io.c
LOCAL_CFLAGS := -std=c11 -Wall -Wextra -Wno-misleading-indentation -fvisibility=hidden
include $(BUILD_SHARED_LIBRARY)
