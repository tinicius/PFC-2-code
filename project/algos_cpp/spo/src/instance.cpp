#include "instance.hpp"
#include <fstream>
#include <sstream>
#include <stdexcept>

Instance read_instance(const std::string& path) {
    std::ifstream file(path);
    if (!file.is_open()) {
        throw std::runtime_error("Cannot open instance file: " + path);
    }

    Instance inst;

    if (!(file >> inst.n_orders >> inst.n_items >> inst.n_aisles)) {
        throw std::runtime_error("Invalid instance format: header");
    }

    inst.orders.resize(inst.n_orders);
    inst.aisles.resize(inst.n_aisles);

    inst.items_per_order.resize(inst.n_items);
    inst.items_per_aisle.resize(inst.n_items);

    inst.Q.assign(inst.n_items, 0);
    inst.D.assign(inst.n_items, 0);

    for (int o = 0; o < inst.n_orders; ++o) {
        int n_order_items;
        if (!(file >> n_order_items)) {
            throw std::runtime_error("Invalid instance format: order " + std::to_string(o));
        }
        for (int k = 0; k < n_order_items; ++k) {
            int item_idx, qty;
            if (!(file >> item_idx >> qty)) {
                throw std::runtime_error("Invalid instance format: order " + std::to_string(o) + " item " + std::to_string(k));
            }
            inst.orders[o][item_idx] = qty;
            inst.items_per_order[item_idx][o] = qty;
            inst.D[item_idx] += qty;
        }
    }

    for (int a = 0; a < inst.n_aisles; ++a) {
        int n_aisle_items;
        if (!(file >> n_aisle_items)) {
            throw std::runtime_error("Invalid instance format: aisle " + std::to_string(a));
        }
        for (int k = 0; k < n_aisle_items; ++k) {
            int item_idx, qty;
            if (!(file >> item_idx >> qty)) {
                throw std::runtime_error("Invalid instance format: aisle " + std::to_string(a) + " item " + std::to_string(k));
            }
            inst.aisles[a][item_idx] = qty;
            inst.items_per_aisle[item_idx][a] = qty;
            inst.Q[item_idx] += qty;
        }
    }

    if (!(file >> inst.LB >> inst.UB)) {
        throw std::runtime_error("Invalid instance format: LB/UB");
    }

    return inst;
}

void write_solution(const Solution& sol, std::ostream& out) {
    out << sol.orders.size() << "\n";
    for (int o : sol.orders) {
        out << o << "\n";
    }
    out << sol.aisles.size() << "\n";
    for (int a : sol.aisles) {
        out << a << "\n";
    }
}
