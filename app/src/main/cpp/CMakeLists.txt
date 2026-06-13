cmake_minimum_required(VERSION 3.10.2)
project("snake_engine")

add_library(
    snake_engine
    SHARED
    snake_engine.cpp
)

find_library(
    log-lib
    log
)

target_link_libraries(
    snake_engine
    ${log-lib}
)
