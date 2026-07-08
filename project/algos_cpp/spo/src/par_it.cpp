#include "par_it.hpp"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <mutex>
#include <thread>

ParItResult solve_par_it(
    const Instance& inst,
    const PreprocResult& preproc,
    const ParItParams& params
) {
    ParItResult result;

    int n_aisles = inst.n_aisles;
    int beta = std::max(1, static_cast<int>(std::ceil(params.beta_perc * n_aisles)));

    std::atomic<double> asc_incumbent{0.0};
    std::atomic<double> desc_incumbent{0.0};
    std::atomic<int> asc_h{1};
    std::atomic<int> desc_h{n_aisles};
    std::atomic<bool> asc_done{false};
    std::atomic<bool> desc_done{false};
    std::mutex sol_mutex;

    auto start_time = std::chrono::steady_clock::now();
    double time_limit = params.time_limit_s;

    auto asc_thread = std::thread([&]() {
        int h = 1;
        while (h < n_aisles && !asc_done.load()) {
            if (desc_done.load() && h >= desc_h.load()) break;

            auto elapsed = std::chrono::duration<double>(
                std::chrono::steady_clock::now() - start_time).count();
            double remaining = time_limit - elapsed;
            if (remaining <= 0) break;

            double thread_time = remaining / 2.0;

            SolveFExtras extras;
            int current_ub = inst.UB;
            if (desc_incumbent.load() > 0) {
                current_ub = std::min(current_ub,
                    static_cast<int>(std::ceil(desc_incumbent.load() * h)));
            }
            extras.ub_override = current_ub;
            extras.lb_override = inst.LB;

            SolveFResult sf = solve_f(inst, preproc, h, extras, thread_time, params.n_threads / 2);

            if (sf.feasible) {
                asc_incumbent.store(sf.obj_value);
                {
                    std::lock_guard<std::mutex> lock(sol_mutex);
                    if (sf.obj_value > result.best.objective) {
                        result.best.orders = sf.orders;
                        result.best.aisles = sf.aisles;
                        result.best.objective = sf.obj_value;
                        result.best.feasible = true;
                    }
                }
                asc_h.store(h);
                ++h;
            } else {
                break;
            }
        }
        asc_done.store(true);
        result.ascending_last_it = asc_h.load();
    });

    auto desc_thread = std::thread([&]() {
        int h = n_aisles;
        while (h > 0 && !desc_done.load()) {
            if (asc_done.load() && h <= asc_h.load()) break;

            auto elapsed = std::chrono::duration<double>(
                std::chrono::steady_clock::now() - start_time).count();
            double remaining = time_limit - elapsed;
            if (remaining <= 0) break;

            double thread_time = remaining / 2.0;

            SolveFExtras extras;
            int current_lb = inst.LB;
            if (asc_incumbent.load() > 0) {
                current_lb = std::max(current_lb,
                    static_cast<int>(std::floor(asc_incumbent.load() * h)));
            }
            extras.lb_override = current_lb;
            extras.ub_override = inst.UB;

            SolveFResult sf = solve_f(inst, preproc, h, extras, thread_time, params.n_threads / 2);

            if (sf.feasible) {
                desc_incumbent.store(sf.obj_value);
                {
                    std::lock_guard<std::mutex> lock(sol_mutex);
                    if (sf.obj_value > result.best.objective) {
                        result.best.orders = sf.orders;
                        result.best.aisles = sf.aisles;
                        result.best.objective = sf.obj_value;
                        result.best.feasible = true;
                    }
                }
                desc_h.store(h);
                h -= beta;
            } else {
                break;
            }
        }
        desc_done.store(true);
        result.descending_last_it = desc_h.load();
    });

    asc_thread.join();
    desc_thread.join();

    result.optimal = (asc_done.load() && desc_done.load() &&
                      asc_h.load() >= desc_h.load());

    if (result.best.feasible) {
        result.incumbent = result.best.objective;
    }

    return result;
}
