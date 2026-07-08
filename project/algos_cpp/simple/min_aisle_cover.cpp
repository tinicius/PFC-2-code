#include "min_aisle_cover.h"

#include <chrono>
#include <sstream>
#include <stdexcept>

#include "ortools/sat/cp_model.h"
#include "ortools/sat/cp_model.pb.h"
#include "ortools/sat/cp_model_solver.h"
#include "ortools/sat/sat_parameters.pb.h"
#include "ortools/util/time_limit.h"

using operations_research::sat::BoolVar;
using operations_research::sat::CpModelBuilder;
using operations_research::sat::CpSolverResponse;
using operations_research::sat::CpSolverStatus;
using operations_research::sat::LinearExpr;
using operations_research::sat::Model;
using operations_research::sat::NewSatParameters;
using operations_research::sat::SatParameters;

ILPResult solve_min_aisle_cover(
    const std::unordered_map<int, int>& demand,
    const std::vector<std::unordered_map<int, int>>& aisles,
    double time_limit_seconds,
    int num_workers) {
    // Filter to items with strictly positive demand.
    std::unordered_map<int, int> active_demand;
    active_demand.reserve(demand.size());
    for (const auto& [item, qty] : demand) {
        if (qty > 0) {
            active_demand[item] = qty;
        }
    }

    if (active_demand.empty()) {
        return ILPResult({}, "OPTIMAL", 0, 0.0);
    }

    const int n_aisles = static_cast<int>(aisles.size());
    CpModelBuilder model;

    // Decision variables: x[a] = 1 iff aisle a is visited.
    std::vector<BoolVar> x;
    x.reserve(n_aisles);
    for (int a = 0; a < n_aisles; ++a) {
        x.push_back(model.NewBoolVar());
    }

    // Coverage constraints: total supply across selected aisles must meet
    // demand for every item that is actually needed.
    for (const auto& [item, qty] : active_demand) {
        LinearExpr supply_sum;
        for (int a = 0; a < n_aisles; ++a) {
            const auto it = aisles[a].find(item);
            const int qty_available = (it != aisles[a].end()) ? it->second : 0;
            if (qty_available != 0) {
                supply_sum += LinearExpr::Term(x[a], qty_available);
            }
        }
        model.AddGreaterOrEqual(supply_sum, qty);
    }

    // Objective: minimize number of visited aisles.
    LinearExpr total_selected;
    for (int a = 0; a < n_aisles; ++a) {
        total_selected += x[a];
    }
    model.Minimize(total_selected);

    Model sat_model;
    SatParameters parameters;
    parameters.set_max_time_in_seconds(time_limit_seconds);
    if (num_workers > 0) {
        parameters.set_num_search_workers(num_workers);
    }
    sat_model.Add(NewSatParameters(parameters));

    const auto t0 = std::chrono::steady_clock::now();
    const CpSolverResponse response =
        operations_research::sat::SolveCpModel(model.Build(), &sat_model);
    const auto t1 = std::chrono::steady_clock::now();
    const double elapsed = std::chrono::duration<double>(t1 - t0).count();

    const CpSolverStatus status = response.status();
    const std::string status_name = CpSolverStatus_Name(status);

    if (status == CpSolverStatus::OPTIMAL || status == CpSolverStatus::FEASIBLE) {
        std::vector<int> selected;
        for (int a = 0; a < n_aisles; ++a) {
            if (operations_research::sat::SolutionBooleanValue(response, x[a])) {
                selected.push_back(a);
            }
        }
        return ILPResult(selected, status_name, static_cast<int>(selected.size()),
                          elapsed);
    }

    // No feasible solution found within the time limit.
    std::ostringstream msg;
    msg << "solve_min_aisle_cover: no feasible solution found (status="
        << status_name << ", elapsed=" << elapsed << "s)";
    throw std::runtime_error(msg.str());
}
