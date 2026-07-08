#ifndef SOLUTION_HPP
#define SOLUTION_HPP

#include <vector>

struct Solution {
    std::vector<int> orders;
    std::vector<int> aisles;
    double objective = 0.0;
    bool feasible = false;
    bool optimal = false;
};

#endif
