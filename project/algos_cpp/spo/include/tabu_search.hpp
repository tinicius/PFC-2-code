#ifndef TABU_SEARCH_HPP
#define TABU_SEARCH_HPP

#include "instance.hpp"
#include "preprocessing.hpp"
#include "solution.hpp"

struct TabuParams {
    double time_limit_s = 600.0;
    int tabu_lock = 10;
    int n_threads = 8;
    double disturb_factor = 1.0;
};

Solution solve_tabu(
    const Instance& inst,
    const PreprocResult& preproc,
    const TabuParams& params
);

#endif
