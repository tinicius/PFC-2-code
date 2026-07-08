#ifndef PAR_IT_HPP
#define PAR_IT_HPP

#include "instance.hpp"
#include "preprocessing.hpp"
#include "solution.hpp"
#include "solve_f.hpp"

struct ParItParams {
    double time_limit_s = 600.0;
    int n_threads = 8;
    double beta_perc = 0.01;
};

struct ParItResult {
    Solution best;
    bool optimal = false;
    int ascending_last_it = 0;
    int descending_last_it = 0;
    double incumbent = 0.0;
};

ParItResult solve_par_it(
    const Instance& inst,
    const PreprocResult& preproc,
    const ParItParams& params
);

#endif
