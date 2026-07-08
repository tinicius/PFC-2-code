#include "preprocessing.hpp"
#include "max_subset_sum.hpp"

#include <algorithm>
#include <numeric>

PreprocResult preprocess(Instance& inst) {
    int n_orders = inst.n_orders;
    int n_items = inst.n_items;
    int n_aisles = inst.n_aisles;

    PreprocResult res;
    res.order_removed.assign(n_orders, false);
    res.item_removed.assign(n_items, false);
    res.item_easy.assign(n_items, false);
    res.aisle_dominated.assign(n_aisles, false);

    bool changed = true;

    while (changed) {
        changed = false;

        // === Rule 1: Remove infeasible orders ===
        // An order is infeasible if any item i has w_{i,o} > Q[i]
        for (int o = 0; o < n_orders; ++o) {
            if (res.order_removed[o]) continue;
            for (auto& [i, w] : inst.orders[o]) {
                if (w > inst.Q[i]) {
                    res.order_removed[o] = true;
                    changed = true;
                    break;
                }
            }
        }

        // Update D after order removals
        inst.D.assign(n_items, 0);
        for (int o = 0; o < n_orders; ++o) {
            if (res.order_removed[o]) continue;
            for (auto& [i, w] : inst.orders[o]) {
                inst.D[i] += w;
            }
        }

        // === Rule 2: Remove excess unit-demand orders ===
        // For each item i, let D̄_i be the number of unit-demand orders for i.
        // If D̄_i > Q[i], remove D̄_i - Q[i] such orders.
        for (int i = 0; i < n_items; ++i) {
            if (res.item_removed[i]) continue;

            std::vector<int> unit_orders;
            for (auto& [o, w] : inst.items_per_order[i]) {
                if (res.order_removed[o]) continue;
                if (w == 1) {
                    unit_orders.push_back(o);
                }
            }

            int D_bar = static_cast<int>(unit_orders.size());
            if (D_bar > inst.Q[i]) {
                int to_remove = D_bar - inst.Q[i];
                for (int k = 0; k < to_remove && k < static_cast<int>(unit_orders.size()); ++k) {
                    res.order_removed[unit_orders[k]] = true;
                    changed = true;
                }
            }
        }

        // === Rule 3: Remove orphan items ===
        // An item is orphaned if all its orders have been removed
        for (int i = 0; i < n_items; ++i) {
            if (res.item_removed[i]) continue;
            bool has_active_order = false;
            for (auto& [o, w] : inst.items_per_order[i]) {
                if (!res.order_removed[o]) {
                    has_active_order = true;
                    break;
                }
            }
            if (!has_active_order) {
                res.item_removed[i] = true;
                changed = true;
            }
        }

        // === Rule 4: Supply cap ===
        // q_{i,a} = min(q_{i,a}, D[i])
        for (int a = 0; a < n_aisles; ++a) {
            if (res.aisle_dominated[a]) continue;
            for (auto& [i, q] : inst.aisles[a]) {
                if (res.item_removed[i]) continue;
                int capped = std::min(q, inst.D[i]);
                if (capped != q) {
                    inst.Q[i] -= q - capped;
                    q = capped;
                    changed = true;
                }
            }
        }

        // === Rule 5: MaxSubsetSum for single-aisle items ===
        // If item i appears in exactly one aisle a, reduce q_{i,a} via MaxSubsetSum
        for (int i = 0; i < n_items; ++i) {
            if (res.item_removed[i]) continue;
            if (inst.items_per_aisle[i].size() != 1) continue;

            int a = inst.items_per_aisle[i].begin()->first;
            if (res.aisle_dominated[a]) continue;

            std::vector<int> order_weights;
            for (auto& [o, w] : inst.items_per_order[i]) {
                if (!res.order_removed[o]) {
                    order_weights.push_back(w);
                }
            }

            int max_sum = max_subset_sum(order_weights, inst.D[i]);
            int old_q = inst.aisles[a][i];
            int new_q = std::min(old_q, max_sum);
            if (new_q != old_q) {
                inst.Q[i] -= old_q - new_q;
                inst.aisles[a][i] = new_q;
                changed = true;
            }
        }

        // Recompute items_per_aisle and Q after modifications
        inst.Q.assign(n_items, 0);
        for (int a = 0; a < n_aisles; ++a) {
            if (res.aisle_dominated[a]) continue;
            for (auto& [i, q] : inst.aisles[a]) {
                if (!res.item_removed[i]) {
                    inst.Q[i] += q;
                }
            }
        }

        // === Rule 6: Identify easy items ===
        // An item is "easy" if EVERY aisle containing i already has enough
        // supply to cover D[i]. This ensures the relaxed constraint
        // p[o] <= sum(y[a]) is sound.
        for (int i = 0; i < n_items; ++i) {
            if (res.item_removed[i]) continue;
            bool all_aisles_sufficient = true;
            for (auto& [a, q] : inst.items_per_aisle[i]) {
                if (res.aisle_dominated[a]) continue;
                if (q < inst.D[i]) {
                    all_aisles_sufficient = false;
                    break;
                }
            }
            if (all_aisles_sufficient && !inst.items_per_aisle[i].empty()) {
                res.item_easy[i] = true;
            }
        }
    }

    return res;
}
