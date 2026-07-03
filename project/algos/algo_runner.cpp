#include <iostream>
#include <string>
#include <chrono>
#include <thread>
#include <fstream>
#include <map>
#include <vector>
#include "simple_heuristic.hpp"
#include <sstream>
#include <stdexcept>

ProblemInput load_instance(const std::string& filename) {
    std::ifstream file(filename);
    if (!file.is_open()) {
        throw std::runtime_error("Cannot open file: " + filename);
    }
    
    int nOrders, nItems, nAisles;
    if (!(file >> nOrders >> nItems >> nAisles)) {
        throw std::runtime_error("Invalid file format: header");
    }

    ProblemInput input;
    input.nOrders = nOrders;
    
    for (int i = 0; i < nOrders; ++i) {
        int n_order_items;
        if (!(file >> n_order_items)) throw std::runtime_error("Invalid file format: orders");
        std::map<int, int> order;
        for (int k = 0; k < n_order_items; ++k) {
            int item_idx, item_qty;
            file >> item_idx >> item_qty;
            order[item_idx] = item_qty;
        }
        input.orders.push_back(order);
    }

    for (int i = 0; i < nAisles; ++i) {
        int n_aisle_items;
        if (!(file >> n_aisle_items)) throw std::runtime_error("Invalid file format: aisles");
        std::map<int, int> aisle;
        for (int k = 0; k < n_aisle_items; ++k) {
            int item_idx, item_qty;
            file >> item_idx >> item_qty;
            aisle[item_idx] = item_qty;
        }
        input.aisles.push_back(aisle);
    }

    if (!(file >> input.lb >> input.ub)) {
        throw std::runtime_error("Invalid file format: lb/ub");
    }

    return input;
}

std::map<std::string, std::string> parse_args(int argc, char* argv[])
{
    std::map<std::string, std::string> args;
    for (int i = 1; i < argc; ++i)
    {
        std::string arg = argv[i];
        size_t pos = arg.find('=');
        if (pos != std::string::npos && arg.substr(0, 2) == "--")
        {
            args[arg.substr(2, pos - 2)] = arg.substr(pos + 1);
        }
    }
    return args;
}

int main(int argc, char* argv[])
{
    auto args = parse_args(argc, argv);

    // Check required arguments
    std::vector<std::string> required = { "binary", "input", "params", "time-limit", "seed", "output" };
    for (const auto& req : required)
    {
        if (args.find(req) == args.end())
        {
            std::cerr << "Missing required argument: --" << req << "\n";
            return 1;
        }
    }

    std::string binary = args["binary"];

    if (binary != "simple_heuristic")
    {
        std::cerr << "Unknown binary: " << binary << "\n";
        return 2;
    }

    // Start timer
    auto start = std::chrono::high_resolution_clock::now();

    // Mock algorithm execution
    HeuristicResult result;
    bool has_result = false;

    try
    {
        SimpleHeuristic heuristic(args);
        ProblemInput parsed_input = load_instance(args["input"]);
        result = heuristic.solve(parsed_input);
        has_result = true;
    }
    catch (const std::exception& e)
    {
        std::cerr << "Error running simple_heuristic: " << e.what() << "\n";
        return 3;
    }

    // Stop timer
    auto end = std::chrono::high_resolution_clock::now();
    std::chrono::duration<double> elapsed = end - start;

    // Write JSON to --output path
    std::ofstream out(args["output"]);
    if (!out)
    {
        std::cerr << "Cannot open output file: " << args["output"] << "\n";
        return 1;
    }

    out << "{\"selected_orders\":[";
    if (has_result)
    {
        for (size_t i = 0; i < result.selected_orders.size(); ++i)
        {
            out << result.selected_orders[i] << (i + 1 < result.selected_orders.size() ? "," : "");
        }
    }
    out << "],\"visited_aisles\":[";
    if (has_result)
    {
        for (size_t i = 0; i < result.visited_aisles.size(); ++i)
        {
            out << result.visited_aisles[i] << (i + 1 < result.visited_aisles.size() ? "," : "");
        }
    }
    out << "],\"exec_time\":" << elapsed.count();
    if (has_result)
        out << ",\"objective\":" << result.objective;
    out << "}\n";
    out.close();

    return 0;
}
