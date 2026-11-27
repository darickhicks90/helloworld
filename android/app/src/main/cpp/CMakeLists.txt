cmake_minimum_required(VERSION 3.18.1)
project(foxbox_audio C)

add_library(foxbox_audio SHARED foxbox_audio.c nam.c)
# the amp captures are heavy maths: let the compiler vectorise and reorder it
set_source_files_properties(nam.c PROPERTIES COMPILE_FLAGS "-O3 -ffast-math")
target_link_libraries(foxbox_audio aaudio log m)
# 16 KB page alignment (required by Google Play for apps targeting Android 15+).
target_link_options(foxbox_audio PRIVATE "-Wl,-z,max-page-size=16384")
