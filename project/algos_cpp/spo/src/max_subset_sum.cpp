#include "max_subset_sum.hpp"
#include <algorithm>
#include <numeric>
#include <vector>

int max_subset_sum(const std::vector<int>& weights, int capacity) {
    if (capacity <= 0 || weights.empty()) return 0;

    int total = std::min(
        std::accumulate(weights.begin(), weights.end(), 0),
        capacity
    );

    std::vector<char> dp(total + 1, 0);
    dp[0] = 1;

    for (int w : weights) {
        if (w > total) continue;
        for (int s = total; s >= w; --s) {
            if (dp[s - w]) dp[s] = 1;
        }
        if (dp[total]) break;
    }

    for (int s = total; s >= 0; --s) {
        if (dp[s]) return s;
    }
    return 0;
}
