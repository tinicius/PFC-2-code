#ifndef BSEARCH_HPP
#define BSEARCH_HPP

#include "instance.hpp"
#include "preprocessing.hpp"
#include "solution.hpp"

struct BSearchResult {
    Solution best;
    double incumbent = 0.0;
    int ascending_last_not_aborted = 0;
    int descending_last_not_aborted = 0;
};

BSearchResult solve_bsearch(
    const Instance& inst,
    const PreprocResult& preproc,
    double time_limit_s,
    int n_threads,
    double alfa = 0.95
);

#endif
