#include "simple_heuristic.hpp"
#include <algorithm>
#include <numeric>
#include <random>
#include <set>
#include <cmath>
#include <ostream>
#include <iostream>

std::vector<int> greedy_aisle_select(std::map<int, int> demand, const std::vector<std::map<int, int>>& aisles) {
    auto aisle_score = [&demand](const std::map<int, int>& aisle) {
        int score = 0;
        for (const auto& [item, qty] : demand) {
            auto it = aisle.find(item);
            if (it != aisle.end()) {
                score += std::min(qty, it->second);
            }
        }
        return score;
        };

    std::vector<int> sorted_aisles(aisles.size());
    std::iota(sorted_aisles.begin(), sorted_aisles.end(), 0);

    std::sort(sorted_aisles.begin(), sorted_aisles.end(), [&](int idx1, int idx2) {
        return aisle_score(aisles[idx1]) > aisle_score(aisles[idx2]);
        });

    std::vector<int> selected_aisles;
    for (int aisle_idx : sorted_aisles) {
        const auto& aisle = aisles[aisle_idx];
        if (aisle_score(aisle) == 0) continue;

        selected_aisles.push_back(aisle_idx);

        int remaining_demand = 0;
        for (auto& [item, needed] : demand) {
            auto it = aisle.find(item);
            if (it != aisle.end() && it->second > 0) {
                demand[item] = std::max(0, needed - it->second);
            }
            remaining_demand += demand[item];
        }

        if (remaining_demand == 0) break;
    }

    return selected_aisles;
}

std::vector<int> multi_greedy_aisle_select(std::map<int, int> demand, const std::vector<std::map<int, int>>& aisles) {
    std::map<int, int> remaining_demand;
    for (const auto& [item, qty] : demand) {
        if (qty > 0) remaining_demand[item] = qty;
    }

    std::vector<int> selected_aisles;
    std::set<int> available_aisles;
    for (size_t i = 0; i < aisles.size(); ++i) available_aisles.insert(i);

    while (!remaining_demand.empty() && !available_aisles.empty()) {
        int best_aisle_idx = -1;
        int max_score = 0;

        for (int idx : available_aisles) {
            const auto& aisle = aisles[idx];
            int score = 0;
            for (const auto& [item, qty] : aisle) {
                auto it = remaining_demand.find(item);
                if (it != remaining_demand.end()) {
                    score += std::min(it->second, qty);
                }
            }
            if (score > max_score) {
                max_score = score;
                best_aisle_idx = idx;
            }
        }

        if (max_score == 0) break;

        selected_aisles.push_back(best_aisle_idx);
        available_aisles.erase(best_aisle_idx);

        const auto& best_aisle = aisles[best_aisle_idx];
        for (const auto& [item, qty] : best_aisle) {
            auto it = remaining_demand.find(item);
            if (it != remaining_demand.end()) {
                it->second -= qty;
                if (it->second <= 0) {
                    remaining_demand.erase(it);
                }
            }
        }
    }

    return selected_aisles;
}

double similarity(const std::map<int, int>& a, const std::map<int, int>& b, bool weighted) {
    if (!weighted) {
        std::set<int> keys_a, keys_b;
        for (const auto& pair : a) keys_a.insert(pair.first);
        for (const auto& pair : b) keys_b.insert(pair.first);

        std::set<int> union_keys, intersect_keys;
        std::set_union(keys_a.begin(), keys_a.end(), keys_b.begin(), keys_b.end(), std::inserter(union_keys, union_keys.begin()));
        std::set_intersection(keys_a.begin(), keys_a.end(), keys_b.begin(), keys_b.end(), std::inserter(intersect_keys, intersect_keys.begin()));

        if (union_keys.empty()) return 0.0;
        return (double)intersect_keys.size() / union_keys.size();
    }
    else {
        std::set<int> keys;
        for (const auto& pair : a) keys.insert(pair.first);
        for (const auto& pair : b) keys.insert(pair.first);

        if (keys.empty()) return 0.0;

        int num = 0, den = 0;
        for (int item : keys) {
            int qa = 0, qb = 0;
            auto it_a = a.find(item);
            if (it_a != a.end()) qa = it_a->second;
            auto it_b = b.find(item);
            if (it_b != b.end()) qb = it_b->second;

            num += std::min(qa, qb);
            den += std::max(qa, qb);
        }
        if (den == 0) return 0.0;
        return (double)num / den;
    }
}

SimpleHeuristic::SimpleHeuristic(const std::map<std::string, std::string>& params) {
    auto get_param = [&params](const std::string& key, const std::string& def = "") {
        auto it = params.find(key);
        return it != params.end() ? it->second : def;
        };

    _order = get_param("order");
    _greedy = get_param("greedy");
    _first_order = get_param("first_order");

    std::set<std::string> valid_order = { "random", "asc", "desc", "similar", "similar_weighted", "diff", "diff_weighted" };
    if (!_order.empty() && valid_order.find(_order) == valid_order.end()) {
        throw std::invalid_argument("SimpleHeuristic: invalid 'order'=" + _order);
    }

    std::set<std::string> valid_greedy = { "simple", "multi", "exact" };
    if (!_greedy.empty() && valid_greedy.find(_greedy) == valid_greedy.end()) {
        throw std::invalid_argument("SimpleHeuristic: invalid 'greedy'=" + _greedy);
    }

    std::set<std::string> valid_first_order = { "random", "smaller", "bigger", "most_shared" };
    std::set<std::string> order_needs_first = { "similar", "similar_weighted", "diff", "diff_weighted" };
    if (order_needs_first.find(_order) != order_needs_first.end() &&
        !_first_order.empty() && valid_first_order.find(_first_order) == valid_first_order.end()) {
        throw std::invalid_argument("SimpleHeuristic: invalid 'first_order'=" + _first_order);
    }

    auto seed_str = get_param("seed");
    if (!seed_str.empty()) {
        _has_seed = true;
        _seed = std::stoi(seed_str);
    }
    else {
        _has_seed = false;
        _seed = 0;
    }

    auto exact_time_limit_str = get_param("exact_time_limit", "30.0");
    auto exact_num_workers_str = get_param("exact_num_workers", "0");

    try {
        _exact_time_limit = std::stod(exact_time_limit_str);
    }
    catch (...) {
        throw std::invalid_argument("SimpleHeuristic: 'exact_time_limit' must be a number");
    }
    if (_exact_time_limit <= 0) {
        throw std::invalid_argument("SimpleHeuristic: 'exact_time_limit' must be > 0");
    }

    try {
        _exact_num_workers = std::stoi(exact_num_workers_str);
    }
    catch (...) {
        throw std::invalid_argument("SimpleHeuristic: 'exact_num_workers' must be an integer");
    }
    if (_exact_num_workers < 0) {
        throw std::invalid_argument("SimpleHeuristic: 'exact_num_workers' must be >= 0");
    }
}

std::map<int, int> SimpleHeuristic::_aggregate_stock(const std::vector<std::map<int, int>>& aisles) {
    std::map<int, int> stock;
    for (const auto& aisle : aisles) {
        for (const auto& pair : aisle) {
            stock[pair.first] += pair.second;
        }
    }
    return stock;
}

SimpleHeuristic::PickOrdersResult SimpleHeuristic::_pick_orders(
    const std::vector<int>& indices,
    const std::vector<std::map<int, int>>& orders,
    const std::vector<int>& order_sizes,
    std::map<int, int> stock,
    int ub
) {
    PickOrdersResult result;
    result.total = 0;

    for (int idx : indices) {
        int size = order_sizes[idx];
        if (result.total + size > ub) continue;

        const auto& order = orders[idx];
        bool can_fulfill = true;
        for (const auto& pair : order) {
            if (stock[pair.first] < pair.second) {
                can_fulfill = false;
                break;
            }
        }

        if (!can_fulfill) continue;

        result.selected.push_back(idx);
        result.total += size;
        for (const auto& pair : order) {
            stock[pair.first] -= pair.second;
            result.demand[pair.first] += pair.second;
        }
    }

    return result;
}

int SimpleHeuristic::_get_most_shared_order(int n_orders, const std::vector<std::map<int, int>>& aisles,
    const std::vector<int>& order_sizes,
    const std::vector<std::map<int, int>>& orders) {
    std::map<int, int> item_aisle_count;
    for (const auto& aisle : aisles) {
        for (const auto& pair : aisle) {
            item_aisle_count[pair.first]++;
        }
    }

    int best_idx = -1;
    long long max_val1 = -1;
    int max_val2 = -1;

    for (int i = 0; i < n_orders; ++i) {
        long long sum_shared = 0;
        for (const auto& pair : orders[i]) {
            sum_shared += item_aisle_count[pair.first];
        }

        if (sum_shared > max_val1 || (sum_shared == max_val1 && order_sizes[i] > max_val2)) {
            max_val1 = sum_shared;
            max_val2 = order_sizes[i];
            best_idx = i;
        }
    }
    return best_idx;
}

int SimpleHeuristic::_pick_first_order(int n_orders, const std::vector<int>& order_sizes,
    const std::vector<std::map<int, int>>& orders,
    const std::vector<std::map<int, int>>& aisles) {
    if (_first_order == "bigger") {
        int max_idx = 0;
        for (int i = 1; i < n_orders; ++i) {
            if (order_sizes[i] > order_sizes[max_idx]) max_idx = i;
        }
        return max_idx;
    }
    if (_first_order == "smaller") {
        int min_idx = 0;
        for (int i = 1; i < n_orders; ++i) {
            if (order_sizes[i] < order_sizes[min_idx]) min_idx = i;
        }
        return min_idx;
    }
    if (_first_order == "most_shared") {
        return _get_most_shared_order(n_orders, aisles, order_sizes, orders);
    }

    std::vector<int> indices(n_orders);
    std::iota(indices.begin(), indices.end(), 0);

    std::mt19937 rng(_has_seed ? _seed : std::random_device{}());
    std::shuffle(indices.begin(), indices.end(), rng);
    return indices[0];
}

std::vector<int> SimpleHeuristic::_build_sequence(int n_orders, const std::vector<int>& order_sizes,
    const std::vector<std::map<int, int>>& orders,
    const std::vector<std::map<int, int>>& aisles) {
    std::vector<int> indices(n_orders);
    std::iota(indices.begin(), indices.end(), 0);

    if (_order == "random") {
        std::mt19937 rng(_has_seed ? _seed : std::random_device{}());
        std::shuffle(indices.begin(), indices.end(), rng);
        return indices;
    }
    else if (_order == "similar" || _order == "similar_weighted" || _order == "diff" || _order == "diff_weighted") {
        int ref_idx = _pick_first_order(n_orders, order_sizes, orders, aisles);
        const auto& reference = orders[ref_idx];

        bool weighted = (_order == "similar_weighted" || _order == "diff_weighted");
        bool similar = (_order == "similar" || _order == "similar_weighted");

        std::vector<std::pair<int, double>> sim_scores(n_orders);
        for (int i = 0; i < n_orders; ++i) {
            sim_scores[i] = { i, similarity(reference, orders[i], weighted) };
        }

        std::sort(sim_scores.begin(), sim_scores.end(), [similar](const auto& a, const auto& b) {
            return similar ? (a.second > b.second) : (a.second < b.second);
            });

        for (int i = 0; i < n_orders; ++i) {
            indices[i] = sim_scores[i].first;
        }
        return indices;
    }
    else if (_order == "asc") {
        std::sort(indices.begin(), indices.end(), [&](int a, int b) {
            return order_sizes[a] < order_sizes[b];
            });
        return indices;
    }
    else if (_order == "desc") {
        std::sort(indices.begin(), indices.end(), [&](int a, int b) {
            return order_sizes[a] > order_sizes[b];
            });
        return indices;
    }

    throw std::invalid_argument("SimpleHeuristic: invalid 'order'");
}

HeuristicResult SimpleHeuristic::solve(const ProblemInput& instance) {
    const auto& orders = instance.orders;
    const auto& aisles = instance.aisles;
    int lb = instance.lb;
    int ub = instance.ub;

    std::vector<int> order_sizes(instance.nOrders, 0);
    for (int i = 0; i < instance.nOrders; ++i) {
        for (const auto& pair : orders[i]) {
            order_sizes[i] += pair.second;
        }
    }

    std::map<int, int> stock = _aggregate_stock(aisles);

    std::vector<int> sequence = _build_sequence(instance.nOrders, order_sizes, orders, aisles);

    auto picked = _pick_orders(sequence, orders, order_sizes, stock, ub);

    if (picked.total < lb) {
        return HeuristicResult{ {}, {}, 0.0 };
    }

    std::vector<int> visited_aisles;
    if (_greedy == "exact") {
        throw std::runtime_error("exact aisle selection not implemented in C++");
    }
    else if (_greedy == "multi") {
        visited_aisles = multi_greedy_aisle_select(picked.demand, aisles);
    }
    else if (_greedy == "simple") {
        visited_aisles = greedy_aisle_select(picked.demand, aisles);
    }

    if (visited_aisles.empty()) {
        return HeuristicResult{ {}, {}, 0.0 };
    }

    return HeuristicResult{
        picked.selected,
        visited_aisles,
        (double)picked.total / visited_aisles.size()
    };
}
