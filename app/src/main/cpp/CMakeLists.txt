cmake_minimum_required(VERSION 3.22.1)
project("tgeditor" LANGUAGES CXX)

add_library(tgeditor SHARED
    image_native.cpp
)

find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)

target_link_libraries(tgeditor
    ${log-lib}
    ${jnigraphics-lib}
)
