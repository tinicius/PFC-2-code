#include "bsearch.hpp"
#include "solve_f.hpp"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <mutex>
#include <thread>

BSearchResult solve_bsearch(
    const Instance& inst,
    const PreprocResult& preproc,
    double time_limit_s,
    int n_threads,
    double alfa
) {
    BSearchResult result;

    int n_aisles = inst.n_aisles;

    std::atomic<int> asc_h{1};
    std::atomic<int> desc_lo{1};
    std::atomic<int> desc_hi{n_aisles};
    std::atomic<double> best_obj{0.0};
    std::mutex sol_mutex;
    Solution best_solution;
    best_solution.objective = 0.0;

    auto start_time = std::chrono::steady_clock::now();

    auto asc_thread = std::thread([&]() {
        int h = 1;
        while (h <= n_aisles) {
            auto elapsed = std::chrono::duration<double>(
                std::chrono::steady_clock::now() - start_time).count();
            double remaining = time_limit_s - elapsed;
            if (remaining <= 0) break;

            double thread_time = remaining / 2.0;

            SolveFExtras extras;
            SolveFResult sf = solve_f(inst, preproc, h, extras,
                                      thread_time, std::max(1, n_threads / 2));

            if (sf.feasible) {
                asc_h.store(h);
                {
                    std::lock_guard<std::mutex> lock(sol_mutex);
                    if (sf.obj_value > best_solution.objective) {
                        best_solution.orders = sf.orders;
                        best_solution.aisles = sf.aisles;
                        best_solution.objective = sf.obj_value;
                        best_solution.feasible = true;
                        best_obj.store(sf.obj_value);
                    }
                }
                ++h;
            } else {
                break;
            }
        }
    });

    auto desc_thread = std::thread([&]() {
        while (desc_lo.load() <= desc_hi.load()) {
            auto elapsed = std::chrono::duration<double>(
                std::chrono::steady_clock::now() - start_time).count();
            double remaining = time_limit_s - elapsed;
            if (remaining <= 0) break;

            int mid = (desc_lo.load() + desc_hi.load()) / 2;
            double thread_time = remaining / 2.0;

            SolveFExtras extras;
            if (best_obj.load() > 0) {
                int new_lb = static_cast<int>(std::floor(alfa * best_obj.load() * mid));
                extras.lb_override = std::max(inst.LB, new_lb);
            }

            SolveFResult sf = solve_f(inst, preproc, mid, extras,
                                      thread_time, std::max(1, n_threads / 2));

            if (sf.feasible) {
                {
                    std::lock_guard<std::mutex> lock(sol_mutex);
                    if (sf.obj_value > best_solution.objective) {
                        best_solution.orders = sf.orders;
                        best_solution.aisles = sf.aisles;
                        best_solution.objective = sf.obj_value;
                        best_solution.feasible = true;
                        best_obj.store(sf.obj_value);
                    }
                }
                desc_hi.store(mid - 1);
            } else {
                desc_lo.store(mid + 1);
            }
        }
    });

    asc_thread.join();
    desc_thread.join();

    result.best = best_solution;
    if (best_solution.feasible) {
        result.incumbent = best_solution.objective;
    }
    result.ascending_last_not_aborted = asc_h.load();
    result.descending_last_not_aborted = desc_lo.load();

    return result;
}
