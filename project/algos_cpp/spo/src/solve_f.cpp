#include "solve_f.hpp"

#include "ortools/sat/cp_model.h"
#include "ortools/sat/sat_parameters.pb.h"

namespace sat = operations_research::sat;

SolveFResult solve_f(
    const Instance& inst,
    const PreprocResult& preproc,
    int H,
    const SolveFExtras& extras,
    double time_limit_s,
    int n_threads
) {
    SolveFResult result;

    int n_orders = inst.n_orders;
    int n_aisles = inst.n_aisles;
    int n_items = inst.n_items;

    int lb = (extras.lb_override >= 0) ? extras.lb_override : inst.LB;
    int ub = (extras.ub_override >= 0) ? extras.ub_override : inst.UB;

    sat::CpModelBuilder model;

    std::vector<sat::BoolVar> y(n_aisles);
    std::vector<bool> y_active(n_aisles, false);
    for (int a = 0; a < n_aisles; ++a) {
        if (preproc.aisle_dominated[a]) continue;
        y[a] = model.NewBoolVar();
        y_active[a] = true;
    }

    std::vector<sat::BoolVar> p(n_orders);
    std::vector<bool> p_active(n_orders, false);
    for (int o = 0; o < n_orders; ++o) {
        if (preproc.order_removed[o]) continue;
        p[o] = model.NewBoolVar();
        p_active[o] = true;
    }

    // Constraint (23): Σ y[a] = H
    {
        sat::LinearExpr sum_y;
        for (int a = 0; a < n_aisles; ++a) {
            if (!y_active[a]) continue;
            sum_y += y[a];
        }
        model.AddEquality(sum_y, H);
    }

    // Constraint (24): Σ_o w_{i,o}·p[o] ≤ Σ_a q_{i,a}·y[a]
    for (int i = 0; i < n_items; ++i) {
        if (preproc.item_removed[i]) continue;
        if (preproc.item_easy[i]) continue;

        auto& order_map = inst.items_per_order[i];
        auto& aisle_map = inst.items_per_aisle[i];

        if (order_map.empty()) continue;

        sat::LinearExpr demand_expr;
        bool has_demand = false;
        for (auto& [o, w] : order_map) {
            if (!p_active[o]) continue;
            demand_expr += w * p[o];
            has_demand = true;
        }
        if (!has_demand) continue;

        sat::LinearExpr supply_expr;
        bool has_supply = false;
        for (auto& [a, q] : aisle_map) {
            if (!y_active[a]) continue;
            supply_expr += q * y[a];
            has_supply = true;
        }

        if (!has_supply) {
            model.AddEquality(demand_expr, 0);
        } else {
            model.AddLessOrEqual(demand_expr, supply_expr);
        }
    }

    // Binary constraint for easy items:
    // p[o] ≤ Σ_{a∈A_i} y[a]  ∀ i ∈ easy, ∀ o ∈ O_i
    for (int i = 0; i < n_items; ++i) {
        if (!preproc.item_easy[i]) continue;

        sat::LinearExpr sum_y_for_item;
        for (auto& [a, q] : inst.items_per_aisle[i]) {
            if (!y_active[a]) continue;
            sum_y_for_item += y[a];
        }

        for (auto& [o, w] : inst.items_per_order[i]) {
            if (!p_active[o]) continue;
            model.AddLessOrEqual(p[o], sum_y_for_item);
        }
    }

    // Forced aisles: y[a] = 1
    if (extras.forced_aisles.has_value()) {
        for (int a : extras.forced_aisles.value()) {
            if (y_active[a]) {
                model.AddEquality(y[a], 1);
            }
        }
    }

    // Forbidden aisles: y[a] = 0
    if (extras.forbidden_aisles.has_value()) {
        for (int a : extras.forbidden_aisles.value()) {
            if (y_active[a]) {
                model.AddEquality(y[a], 0);
            }
        }
    }

    // Objective (22): max Σ_{o,i} w_{i,o}·p[o]
    sat::LinearExpr total_items;
    for (int o = 0; o < n_orders; ++o) {
        if (!p_active[o]) continue;
        for (auto& [i, w] : inst.orders[o]) {
            total_items += w * p[o];
        }
    }
    model.Maximize(total_items);

    // Constraint (25): LB ≤ total_items ≤ UB
    model.AddGreaterOrEqual(total_items, lb);
    model.AddLessOrEqual(total_items, ub);

    // Set solver parameters
    sat::SatParameters params;
    params.set_max_time_in_seconds(time_limit_s);
    if (n_threads > 0) {
        params.set_num_search_workers(n_threads);
    }

    // Solve
    sat::CpSolverResponse response = sat::SolveWithParameters(model.Build(), params);

    if (response.status() == sat::CpSolverStatus::OPTIMAL ||
        response.status() == sat::CpSolverStatus::FEASIBLE) {
        result.feasible = true;
        result.items = static_cast<int>(sat::SolutionIntegerValue(response, total_items));

        for (int o = 0; o < n_orders; ++o) {
            if (!p_active[o]) continue;
            if (sat::SolutionBooleanValue(response, p[o])) {
                result.orders.push_back(o);
            }
        }

        for (int a = 0; a < n_aisles; ++a) {
            if (!y_active[a]) continue;
            if (sat::SolutionBooleanValue(response, y[a])) {
                result.aisles.push_back(a);
            }
        }

        if (H > 0) {
            result.obj_value = static_cast<double>(result.items) / H;
        }
    }

    return result;
}
