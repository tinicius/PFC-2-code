cmake_minimum_required(VERSION 3.18)
project(spo_solver LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(ortools REQUIRED CONFIG)

set(SPO_INCLUDE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/include)

add_executable(spo_solver
    src/main.cpp
    src/instance.cpp
    src/max_subset_sum.cpp
    src/preprocessing.cpp
    src/solve_f.cpp
    src/par_it.cpp
    src/ref_lin.cpp
    src/bsearch.cpp
    src/hybrid.cpp
    src/tabu_search.cpp
)
target_include_directories(spo_solver PRIVATE ${SPO_INCLUDE_DIR})
target_link_libraries(spo_solver PRIVATE ortools::ortools)
