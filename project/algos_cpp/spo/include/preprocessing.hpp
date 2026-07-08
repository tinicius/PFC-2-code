#ifndef PREPROCESSING_HPP
#define PREPROCESSING_HPP

#include <vector>

#include "instance.hpp"

struct PreprocResult {
    std::vector<bool> order_removed;
    std::vector<bool> item_removed;
    std::vector<bool> item_easy;
    std::vector<bool> aisle_dominated;
};

PreprocResult preprocess(Instance& inst);

#endif
