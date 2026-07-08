#pragma once

#include <string>
#include <unordered_map>
#include <vector>

// Exact ILP: select the fewest aisles that collectively cover all demand.
//
// Model
// -----
// Variables   x_a in {0, 1}  — 1 if aisle a is selected
//
// Minimize    Sum_a  x_a
//
// Subject to  Sum_a  supply[a][i] * x_a  >=  demand[i]   for every item i
//             with demand[i] > 0
//
// This is a Set Multicover ILP. Per-item supply bounds implicitly encode
// the capacity of each aisle, so no additional y variables are needed.

struct ILPResult {
    std::vector<int> selected_aisles;  // indices of chosen aisles
    std::string status;                // CP-SAT status name (e.g. "OPTIMAL")
    int num_selected = 0;              // selected_aisles.size()
    double elapsed_seconds = 0.0;      // wall-clock solve time

    ILPResult() = default;
    ILPResult(std::vector<int> selected, std::string status_name,
              int count, double elapsed)
        : selected_aisles(std::move(selected)),
          status(std::move(status_name)),
          num_selected(count),
          elapsed_seconds(elapsed) {}
};

// demand: {item_id: quantity_needed} — aggregated from selected orders.
// aisles: one map per aisle of {item_id: quantity_available}.
// time_limit_seconds: CP-SAT wall-clock limit.
// num_workers: number of parallel search workers (0 = solver default).
//
// Throws std::runtime_error if no feasible solution is found within the
// time limit (i.e. the solver status is neither OPTIMAL nor FEASIBLE).
ILPResult solve_min_aisle_cover(
    const std::unordered_map<int, int>& demand,
    const std::vector<std::unordered_map<int, int>>& aisles,
    double time_limit_seconds = 30.0,
    int num_workers = 0);
