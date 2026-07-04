cmake_minimum_required(VERSION 3.18)
project(algo_runner LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(ortools REQUIRED CONFIG)

add_executable(algo_runner
    algo_runner.cpp
    simple/simple_heuristic.cpp
    simple/min_aisle_cover.cpp
)
target_include_directories(algo_runner PRIVATE simple)
target_link_libraries(algo_runner PRIVATE ortools::ortools)
