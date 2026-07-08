#include "aisle_first.hpp"

#include <algorithm>
#include <numeric>
#include <set>
#include <stdexcept>

using namespace std;

AisleFirstDesc::AisleFirstDesc(const map<string, string>& params) {
    auto get_param = [&params](const string& key, const string& def = "") {
        auto it = params.find(key);
        return it != params.end() ? it->second : def;
    };

    _score = get_param("score", "useful");

    set<string> valid_score = {"useful", "units", "variety", "mixed"};
    if (valid_score.find(_score) == valid_score.end()) {
        throw invalid_argument("AisleFirstDesc: invalid 'score'=" + _score);
    }
}

double AisleFirstDesc::_aisle_score(
    int idx,
    const vector<map<int, int>>& aisles,
    const map<int, int>& total_demand) const
{
    const auto& aisle = aisles[idx];
    if (_score == "useful") {
        double val = 0.0;
        for (const auto& [item, qty] : aisle) {
            auto it = total_demand.find(item);
            if (it != total_demand.end() && it->second > 0) {
                val += min(qty, it->second);
            }
        }
        return val;
    }
    if (_score == "units") {
        double val = 0.0;
        for (const auto& [item, qty] : aisle) {
            val += qty;
        }
        return val;
    }
    if (_score == "variety") {
        return static_cast<double>(aisle.size());
    }
    if (_score == "mixed") {
        double units = 0.0;
        for (const auto& [item, qty] : aisle) {
            units += qty;
        }
        return units * static_cast<double>(aisle.size());
    }
    return 0.0;
}

vector<int> AisleFirstDesc::_rank_aisles(
    const vector<map<int, int>>& aisles,
    const map<int, int>& total_demand) const
{
    vector<int> indices(aisles.size());
    iota(indices.begin(), indices.end(), 0);

    vector<double> scores(aisles.size());
    for (size_t i = 0; i < aisles.size(); ++i) {
        scores[i] = _aisle_score(i, aisles, total_demand);
    }

    sort(indices.begin(), indices.end(), [&](int a, int b) {
        return scores[a] > scores[b];
    });

    return indices;
}

vector<int> AisleFirstDesc::_build_sequence(
    int n_orders,
    const vector<int>& order_sizes) const
{
    vector<int> seq(n_orders);
    iota(seq.begin(), seq.end(), 0);

    sort(seq.begin(), seq.end(), [&](int a, int b) {
        return order_sizes[a] > order_sizes[b];
    });

    return seq;
}

void AisleFirstDesc::_pack_orders(
    const vector<int>& sequence,
    const vector<map<int, int>>& orders,
    const vector<int>& order_sizes,
    map<int, int> inventory,
    int ub,
    vector<int>& selected,
    int& total) const
{
    selected.clear();
    total = 0;

    for (int idx : sequence) {
        int size = order_sizes[idx];
        if (total + size > ub) continue;

        const auto& order = orders[idx];
        bool can_fulfill = true;
        for (const auto& [item, qty] : order) {
            auto it = inventory.find(item);
            if (it == inventory.end() || it->second < qty) {
                can_fulfill = false;
                break;
            }
        }
        if (!can_fulfill) continue;

        selected.push_back(idx);
        total += size;
        for (const auto& [item, qty] : order) {
            inventory[item] -= qty;
        }
    }
}

HeuristicResult AisleFirstDesc::solve(const ProblemInput& instance) {
    const auto& orders = instance.orders;
    const auto& aisles = instance.aisles;
    int lb = instance.lb;
    int ub = instance.ub;
    int n_orders = instance.nOrders;

    if (n_orders == 0 || aisles.empty()) {
        return HeuristicResult{{}, {}, 0.0};
    }

    // Order sizes
    vector<int> order_sizes(n_orders, 0);
    for (int i = 0; i < n_orders; ++i) {
        for (const auto& [item, qty] : orders[i]) {
            order_sizes[i] += qty;
        }
    }

    // Total demand across all orders
    map<int, int> total_demand;
    for (const auto& order : orders) {
        for (const auto& [item, qty] : order) {
            total_demand[item] += qty;
        }
    }

    // Rank aisles descending by score
    auto ranked_aisles = _rank_aisles(aisles, total_demand);

    // Order sequence: descending by size
    auto sequence = _build_sequence(n_orders, order_sizes);

    vector<int> best_orders;
    vector<int> best_aisles;
    double best_obj = 0.0;

    map<int, int> inventory;
    int k = 0;
    for (int aisle_idx : ranked_aisles) {
        ++k;

        // Accumulate inventory
        for (const auto& [item, qty] : aisles[aisle_idx]) {
            inventory[item] += qty;
        }

        // Early stop: ub/k can't beat best_obj
        if (static_cast<double>(ub) / k <= best_obj) break;

        // Pack orders against current inventory
        vector<int> selected;
        int total_units = 0;
        _pack_orders(sequence, orders, order_sizes, inventory, ub,
                     selected, total_units);

        if (total_units < lb) continue;

        double obj = static_cast<double>(total_units) / k;
        if (obj > best_obj) {
            best_obj = obj;
            best_orders = move(selected);
            best_aisles.assign(ranked_aisles.begin(), ranked_aisles.begin() + k);
        }
    }

    if (best_orders.empty()) {
        return HeuristicResult{{}, {}, 0.0};
    }

    return HeuristicResult{
        move(best_orders),
        move(best_aisles),
        best_obj
    };
}
