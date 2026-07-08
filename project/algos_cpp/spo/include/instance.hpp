#ifndef INSTANCE_HPP
#define INSTANCE_HPP

#include <vector>
#include <unordered_map>
#include <string>
#include <ostream>

#include "solution.hpp"

struct Instance {
    int n_orders, n_items, n_aisles;
    int LB, UB;

    std::vector<std::unordered_map<int, int>> orders;
    std::vector<std::unordered_map<int, int>> aisles;

    std::vector<std::unordered_map<int, int>> items_per_order;
    std::vector<std::unordered_map<int, int>> items_per_aisle;

    std::vector<int> Q;
    std::vector<int> D;
};

Instance read_instance(const std::string& path);
void write_solution(const Solution& sol, std::ostream& out);

#endif
