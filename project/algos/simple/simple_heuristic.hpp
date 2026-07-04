#ifndef SIMPLE_HEURISTIC_HPP
#define SIMPLE_HEURISTIC_HPP

#include <vector>
#include <map>
#include <string>
#include <stdexcept>

struct ProblemInput {
    int nOrders;
    std::vector<std::map<int, int>> orders;
    std::vector<std::map<int, int>> aisles;
    int lb;
    int ub;
};

struct HeuristicResult {
    std::vector<int> selected_orders;
    std::vector<int> visited_aisles;
    double objective;
};

class SimpleHeuristic {
public:
    SimpleHeuristic(const std::map<std::string, std::string>& params);
    
    std::string name() const { return "simple_heuristic"; }
    HeuristicResult solve(const ProblemInput& instance);

private:
    std::string _order;
    std::string _greedy;
    std::string _first_order;
    int _seed;
    bool _has_seed;
    double _exact_time_limit;
    int _exact_num_workers;

    std::vector<int> _build_sequence(int n_orders, const std::vector<int>& order_sizes,
                                     const std::vector<std::map<int, int>>& orders,
                                     const std::vector<std::map<int, int>>& aisles);

    int _pick_first_order(int n_orders, const std::vector<int>& order_sizes,
                          const std::vector<std::map<int, int>>& orders,
                          const std::vector<std::map<int, int>>& aisles);

    int _get_most_shared_order(int n_orders, const std::vector<std::map<int, int>>& aisles,
                               const std::vector<int>& order_sizes,
                               const std::vector<std::map<int, int>>& orders);

    std::map<int, int> _aggregate_stock(const std::vector<std::map<int, int>>& aisles);

    struct PickOrdersResult {
        std::vector<int> selected;
        std::map<int, int> demand;
        int total;
    };

    PickOrdersResult _pick_orders(const std::vector<int>& indices,
                                  const std::vector<std::map<int, int>>& orders,
                                  const std::vector<int>& order_sizes,
                                  std::map<int, int> stock,
                                  int ub);
};

// Expose these utility functions so they can be tested/used separately if needed.
std::vector<int> greedy_aisle_select(std::map<int, int> demand, const std::vector<std::map<int, int>>& aisles);
std::vector<int> multi_greedy_aisle_select(std::map<int, int> demand, const std::vector<std::map<int, int>>& aisles);
double similarity(const std::map<int, int>& a, const std::map<int, int>& b, bool weighted = false);

#endif
