#ifndef AISLE_FIRST_DESC_HPP
#define AISLE_FIRST_DESC_HPP

#include "../simple/simple_heuristic.hpp"
#include <map>
#include <string>
#include <vector>

class AisleFirstDesc {
public:
    AisleFirstDesc(const std::map<std::string, std::string>& params);

    std::string name() const { return "aisle_first_desc"; }
    HeuristicResult solve(const ProblemInput& instance);

private:
    std::string _score;

    double _aisle_score(
        int idx,
        const std::vector<std::map<int, int>>& aisles,
        const std::map<int, int>& total_demand) const;

    std::vector<int> _rank_aisles(
        const std::vector<std::map<int, int>>& aisles,
        const std::map<int, int>& total_demand) const;

    std::vector<int> _build_sequence(
        int n_orders,
        const std::vector<int>& order_sizes) const;

    void _pack_orders(
        const std::vector<int>& sequence,
        const std::vector<std::map<int, int>>& orders,
        const std::vector<int>& order_sizes,
        std::map<int, int> inventory,
        int ub,
        std::vector<int>& selected,
        int& total) const;
};

#endif
