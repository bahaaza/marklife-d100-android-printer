cmake_minimum_required(VERSION 3.22.1)
project("jbig_encoder" C)

# ---------------------------------------------------------------------------
# jbigkit source location
#
# Place jbig.c and jbig.h from jbigkit 2.1 into the jbigkit/ subdirectory:
#
#   wget -q https://www.cl.cam.ac.uk/~mgk25/jbigkit/download/jbigkit-2.1.tar.gz
#   tar -xzf jbigkit-2.1.tar.gz
#   cp jbigkit-2.1/libjbig/jbig.c  app/src/main/cpp/jbigkit/
#   cp jbigkit-2.1/libjbig/jbig.h  app/src/main/cpp/jbigkit/
#   cp jbigkit-2.1/libjbig/jbig_ar.h app/src/main/cpp/jbigkit/
#
# Or override via the CMake variable JBIGKIT_DIR when invoking Gradle:
#   arguments "-DJBIGKIT_DIR=/absolute/path/to/libjbig"
# ---------------------------------------------------------------------------

if(NOT DEFINED JBIGKIT_DIR)
    set(JBIGKIT_DIR "${CMAKE_CURRENT_SOURCE_DIR}/jbigkit")
endif()

add_library(
    jbig_encoder
    SHARED
    jbig_jni.c
    "${JBIGKIT_DIR}/jbig.c"
    "${JBIGKIT_DIR}/jbig_ar.c"
)

target_include_directories(jbig_encoder PRIVATE "${JBIGKIT_DIR}")

find_library(log-lib log)
target_link_libraries(jbig_encoder ${log-lib})
