#ifndef SOLVE_F_HPP
#define SOLVE_F_HPP

#include <optional>
#include <vector>

#include "instance.hpp"
#include "preprocessing.hpp"
#include "solution.hpp"

struct SolveFExtras {
    std::optional<std::vector<int>> forced_aisles;
    std::optional<std::vector<int>> forbidden_aisles;
    int lb_override = -1;
    int ub_override = -1;
};

struct SolveFResult {
    bool feasible = false;
    int items = 0;
    double obj_value = 0.0;
    std::vector<int> orders;
    std::vector<int> aisles;
};

SolveFResult solve_f(
    const Instance& inst,
    const PreprocResult& preproc,
    int H,
    const SolveFExtras& extras,
    double time_limit_s,
    int n_threads
);

#endif
