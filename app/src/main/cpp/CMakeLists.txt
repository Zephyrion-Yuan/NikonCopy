cmake_minimum_required(VERSION 3.22.1)
project("mtime_jni" C)

add_library(mtime_jni SHARED mtime_jni.c)
target_link_libraries(mtime_jni log)
