#include "tabu_search.hpp"
#include "bsearch.hpp"
#include "solve_f.hpp"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <mutex>
#include <thread>
#include <vector>

static const int MAX_NO_IMPRV_ITS = 20;

Solution solve_tabu(
    const Instance& inst,
    const PreprocResult& preproc,
    const TabuParams& params
) {
    Solution best;

    // Phase 1: Binary search for initial solution
    BSearchResult bsearch_result = solve_bsearch(
        inst, preproc, params.time_limit_s * 0.3, params.n_threads);

    if (!bsearch_result.best.feasible) {
        return best;
    }

    best = bsearch_result.best;

    int n_aisles = inst.n_aisles;
    int current_H = static_cast<int>(best.aisles.size());

    // Build current solution state
    std::vector<bool> current_y(n_aisles, false);
    for (int a : best.aisles) {
        current_y[a] = true;
    }

    // Tabu list: tabu[a] = iterations remaining
    std::vector<int> tabu(n_aisles, 0);

    std::mutex sol_mutex;
    std::atomic<bool> improved{true};
    std::atomic<int> no_imprv_it{0};

    auto start_time = std::chrono::steady_clock::now();

    while (no_imprv_it.load() < MAX_NO_IMPRV_ITS) {
        auto elapsed = std::chrono::duration<double>(
            std::chrono::steady_clock::now() - start_time).count();
        double remaining = params.time_limit_s - elapsed;
        if (remaining <= 0) break;

        improved.store(false);
        double it_time = remaining / 2.0;

        // mv1: Try adding an aisle (increase H by 1)
        std::thread mv1_thread([&]() {
            int best_add_aisle = -1;
            double best_add_obj = best.objective;

            for (int a = 0; a < n_aisles; ++a) {
                if (current_y[a]) continue;
                if (tabu[a] > 0) continue;
                if (preproc.aisle_dominated[a]) continue;

                SolveFExtras extras;
                extras.forced_aisles = best.aisles;
                extras.forced_aisles->push_back(a);

                SolveFResult sf = solve_f(inst, preproc, current_H + 1, extras,
                                          it_time / n_aisles, 1);

                if (sf.feasible && sf.obj_value > best_add_obj) {
                    best_add_obj = sf.obj_value;
                    best_add_aisle = a;
                }
            }

            if (best_add_aisle >= 0) {
                std::lock_guard<std::mutex> lock(sol_mutex);
                if (best_add_obj > best.objective) {
                    best.objective = best_add_obj;
                    current_y[best_add_aisle] = true;
                    best.aisles.push_back(best_add_aisle);
                    ++current_H;
                    tabu[best_add_aisle] = params.tabu_lock;
                    improved.store(true);
                }
            }
        });

        // mv2: Try removing an aisle (decrease H by 1)
        std::thread mv2_thread([&]() {
            int best_rem_aisle = -1;
            double best_rem_obj = best.objective;

            for (int a : best.aisles) {
                if (!current_y[a]) continue;
                if (tabu[a] > 0) continue;

                std::vector<int> new_aisles;
                for (int ba : best.aisles) {
                    if (ba != a) new_aisles.push_back(ba);
                }

                if (new_aisles.empty()) continue;

                SolveFExtras extras;
                extras.forced_aisles = new_aisles;

                SolveFResult sf = solve_f(inst, preproc, current_H - 1, extras,
                                          it_time / n_aisles, 1);

                if (sf.feasible && sf.obj_value > best_rem_obj) {
                    best_rem_obj = sf.obj_value;
                    best_rem_aisle = a;
                }
            }

            if (best_rem_aisle >= 0) {
                std::lock_guard<std::mutex> lock(sol_mutex);
                if (best_rem_obj > best.objective) {
                    best.objective = best_rem_obj;
                    current_y[best_rem_aisle] = false;
                    tabu[best_rem_aisle] = params.tabu_lock;
                    improved.store(true);
                }
            }
        });

        mv1_thread.join();
        mv2_thread.join();

        // Decrement tabu counters
        for (int a = 0; a < n_aisles; ++a) {
            if (tabu[a] > 0) --tabu[a];
        }

        if (!improved.load()) {
            no_imprv_it.fetch_add(1);
        } else {
            no_imprv_it.store(0);
        }
    }

    return best;
}
