#ifndef REF_LIN_HPP
#define REF_LIN_HPP

#include "instance.hpp"
#include "preprocessing.hpp"
#include "solution.hpp"

struct RefLinParams {
    int h_min = 1;
    int h_max = -1;
    double incumbent = 0.0;
    double time_limit_s = 600.0;
    int n_threads = 8;
};

Solution solve_ref_lin(
    const Instance& inst,
    const PreprocResult& preproc,
    const RefLinParams& params
);

#endif
