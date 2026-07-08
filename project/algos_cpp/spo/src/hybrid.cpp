#include "hybrid.hpp"
#include "par_it.hpp"
#include "ref_lin.hpp"

#include <algorithm>

Solution solve_hybrid(
    const Instance& inst,
    const PreprocResult& preproc,
    const HybridParams& params
) {
    Solution result;

    ParItParams par_params;
    par_params.time_limit_s = params.partial_time_s;
    par_params.n_threads = params.n_threads;

    ParItResult par_result = solve_par_it(inst, preproc, par_params);

    if (par_result.optimal) {
        return par_result.best;
    }

    if (par_result.best.feasible) {
        result = par_result.best;
    }

    double remaining_time = params.total_time_s - params.partial_time_s;
    if (remaining_time <= 0) {
        return result;
    }

    int h_min = par_result.ascending_last_it;
    int h_max = par_result.descending_last_it;
    if (h_max < h_min) {
        h_max = h_min;
    }

    RefLinParams ref_params;
    ref_params.h_min = std::max(1, h_min);
    ref_params.h_max = h_max;
    ref_params.incumbent = par_result.incumbent;
    ref_params.time_limit_s = remaining_time;
    ref_params.n_threads = params.n_threads;

    Solution ref_result = solve_ref_lin(inst, preproc, ref_params);

    if (ref_result.feasible && ref_result.objective > result.objective) {
        result = ref_result;
    }

    return result;
}
