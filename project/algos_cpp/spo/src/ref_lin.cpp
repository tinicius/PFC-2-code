#include "ref_lin.hpp"
#include "solve_f.hpp"

#include "absl/time/time.h"
#include "ortools/linear_solver/linear_solver.h"

using namespace operations_research;

Solution solve_ref_lin(
    const Instance& inst,
    const PreprocResult& preproc,
    const RefLinParams& params
) {
    Solution result;

    int n_orders = inst.n_orders;
    int n_aisles = inst.n_aisles;
    int n_items = inst.n_items;

    int h_max_val = (params.h_max < 0) ? n_aisles : params.h_max;

    std::unique_ptr<MPSolver> solver(MPSolver::CreateSolver("SCIP"));
    if (!solver) {
        return result;
    }

    solver->SetTimeLimit(absl::Seconds(params.time_limit_s));

    const double M = 1.0;

    // Variables
    MPVariable* const u = solver->MakeNumVar(0.0, M, "u");

    std::vector<MPVariable*> t(n_orders);
    for (int o = 0; o < n_orders; ++o) {
        if (preproc.order_removed[o]) {
            t[o] = solver->MakeNumVar(0.0, 0.0, "t_" + std::to_string(o));
        } else {
            t[o] = solver->MakeNumVar(0.0, M, "t_" + std::to_string(o));
        }
    }

    std::vector<MPVariable*> g(n_aisles);
    for (int a = 0; a < n_aisles; ++a) {
        if (preproc.aisle_dominated[a]) {
            g[a] = solver->MakeNumVar(0.0, 0.0, "g_" + std::to_string(a));
        } else {
            g[a] = solver->MakeNumVar(0.0, M, "g_" + std::to_string(a));
        }
    }

    std::vector<MPVariable*> p(n_orders);
    for (int o = 0; o < n_orders; ++o) {
        if (preproc.order_removed[o]) {
            p[o] = solver->MakeIntVar(0, 0, "p_" + std::to_string(o));
        } else {
            p[o] = solver->MakeIntVar(0, 1, "p_" + std::to_string(o));
        }
    }

    std::vector<MPVariable*> y(n_aisles);
    for (int a = 0; a < n_aisles; ++a) {
        if (preproc.aisle_dominated[a]) {
            y[a] = solver->MakeIntVar(0, 0, "y_" + std::to_string(a));
        } else {
            y[a] = solver->MakeIntVar(0, 1, "y_" + std::to_string(a));
        }
    }

    // Constraint (11): Σ g_a = 1
    {
        MPConstraint* const c = solver->MakeRowConstraint(1.0, 1.0);
        for (int a = 0; a < n_aisles; ++a) {
            c->SetCoefficient(g[a], 1.0);
        }
    }

    // Constraint (12): Σ_o w_{i,o} * t_o ≤ Σ_a q_{i,a} * g_a  ∀i
    for (int i = 0; i < n_items; ++i) {
        if (preproc.item_removed[i]) continue;

        MPConstraint* const c = solver->MakeRowConstraint(-MPSolver::infinity(), 0.0);
        for (auto& [o, w] : inst.items_per_order[i]) {
            if (preproc.order_removed[o]) continue;
            c->SetCoefficient(t[o], static_cast<double>(w));
        }
        for (auto& [a, q] : inst.items_per_aisle[i]) {
            if (preproc.aisle_dominated[a]) continue;
            c->SetCoefficient(g[a], -static_cast<double>(q));
        }
    }

    // Constraint (13): t_o ≤ u  ∀o
    // Constraint (14): t_o ≤ p_o  ∀o
    // Constraint (15): t_o ≥ u + p_o - 1  ∀o
    for (int o = 0; o < n_orders; ++o) {
        if (preproc.order_removed[o]) continue;

        // t_o ≤ u
        {
            MPConstraint* const c = solver->MakeRowConstraint(-MPSolver::infinity(), 0.0);
            c->SetCoefficient(t[o], 1.0);
            c->SetCoefficient(u, -1.0);
        }

        // t_o ≤ p_o
        {
            MPConstraint* const c = solver->MakeRowConstraint(-MPSolver::infinity(), 0.0);
            c->SetCoefficient(t[o], 1.0);
            c->SetCoefficient(p[o], -1.0);
        }

        // t_o ≥ u + p_o - 1
        {
            MPConstraint* const c = solver->MakeRowConstraint(-1.0, MPSolver::infinity());
            c->SetCoefficient(t[o], 1.0);
            c->SetCoefficient(u, -1.0);
            c->SetCoefficient(p[o], -1.0);
        }
    }

    // Constraint (17): g_a ≤ u  ∀a
    // Constraint (18): g_a ≤ y_a  ∀a
    // Constraint (19): g_a ≥ u + y_a - 1  ∀a
    for (int a = 0; a < n_aisles; ++a) {
        if (preproc.aisle_dominated[a]) continue;

        // g_a ≤ u
        {
            MPConstraint* const c = solver->MakeRowConstraint(-MPSolver::infinity(), 0.0);
            c->SetCoefficient(g[a], 1.0);
            c->SetCoefficient(u, -1.0);
        }

        // g_a ≤ y_a
        {
            MPConstraint* const c = solver->MakeRowConstraint(-MPSolver::infinity(), 0.0);
            c->SetCoefficient(g[a], 1.0);
            c->SetCoefficient(y[a], -1.0);
        }

        // g_a ≥ u + y_a - 1
        {
            MPConstraint* const c = solver->MakeRowConstraint(-1.0, MPSolver::infinity());
            c->SetCoefficient(g[a], 1.0);
            c->SetCoefficient(u, -1.0);
            c->SetCoefficient(y[a], -1.0);
        }
    }

    // Interval restriction: h_min ≤ 1/u ≤ h_max
    // → 1/h_max ≤ u ≤ 1/h_min
    {
        double u_lb = 1.0 / static_cast<double>(h_max_val);
        double u_ub = 1.0 / static_cast<double>(params.h_min);
        u->SetBounds(u_lb, u_ub);
    }

    // Constraint (21): LB * u ≤ Σ_{o,i} w_{i,o} * t_o ≤ UB * u
    {
        // LB * u ≤ Σ w * t  →  Σ w * t - LB * u ≥ 0
        {
            MPConstraint* const c = solver->MakeRowConstraint(0.0, MPSolver::infinity());
            for (int o = 0; o < n_orders; ++o) {
                if (preproc.order_removed[o]) continue;
                for (auto& [i, w] : inst.orders[o]) {
                    c->SetCoefficient(t[o], static_cast<double>(w));
                }
            }
            c->SetCoefficient(u, -static_cast<double>(inst.LB));
        }

        // Σ w * t ≤ UB * u → Σ w * t - UB * u ≤ 0
        {
            MPConstraint* const c = solver->MakeRowConstraint(-MPSolver::infinity(), 0.0);
            for (int o = 0; o < n_orders; ++o) {
                if (preproc.order_removed[o]) continue;
                for (auto& [i, w] : inst.orders[o]) {
                    c->SetCoefficient(t[o], static_cast<double>(w));
                }
            }
            c->SetCoefficient(u, -static_cast<double>(inst.UB));
        }
    }

    // Incumbent constraint: Σ_{o,i} w_{i,o} * t_o ≥ incumbent (if set)
    if (params.incumbent > 0.0) {
        MPConstraint* const c = solver->MakeRowConstraint(params.incumbent, MPSolver::infinity());
        for (int o = 0; o < n_orders; ++o) {
            if (preproc.order_removed[o]) continue;
            for (auto& [i, w] : inst.orders[o]) {
                c->SetCoefficient(t[o], static_cast<double>(w));
            }
        }
    }

    // Objective: max Σ_{o,i} w_{i,o} * t_o
    MPObjective* const objective = solver->MutableObjective();
    for (int o = 0; o < n_orders; ++o) {
        if (preproc.order_removed[o]) continue;
        for (auto& [i, w] : inst.orders[o]) {
            objective->SetCoefficient(t[o], static_cast<double>(w));
        }
    }
    objective->SetMaximization();

    // Solve
    MPSolver::ResultStatus status = solver->Solve();

    if (status == MPSolver::OPTIMAL || status == MPSolver::FEASIBLE) {
        // Get optimal H = round(1/u)
        double u_val = u->solution_value();
        int H = static_cast<int>(std::round(1.0 / u_val));
        H = std::max(params.h_min, std::min(H, h_max_val));

        // Solve F(H) with CP-SAT to get binary solution
        SolveFExtras extras;
        SolveFResult sf = solve_f(
            inst, preproc, H, extras, params.time_limit_s, params.n_threads
        );

        if (sf.feasible) {
            result.feasible = true;
            result.orders = sf.orders;
            result.aisles = sf.aisles;
            result.objective = sf.obj_value;
        }
    }

    return result;
}
