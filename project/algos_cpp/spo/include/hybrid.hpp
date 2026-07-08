#ifndef HYBRID_HPP
#define HYBRID_HPP

#include "instance.hpp"
#include "preprocessing.hpp"
#include "solution.hpp"

struct HybridParams {
    double partial_time_s = 210.0;
    double total_time_s = 600.0;
    int n_threads = 8;
};

Solution solve_hybrid(
    const Instance& inst,
    const PreprocResult& preproc,
    const HybridParams& params
);

#endif
