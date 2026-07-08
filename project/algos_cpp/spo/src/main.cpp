#include <iostream>
#include <string>
#include <vector>
#include <map>
#include <fstream>
#include <sstream>
#include <stdexcept>
#include <cstdlib>
#include <chrono>

#include "instance.hpp"
#include "preprocessing.hpp"
#include "solution.hpp"
#include "par_it.hpp"
#include "ref_lin.hpp"
#include "hybrid.hpp"
#include "tabu_search.hpp"

std::map<std::string, std::string> parse_args(int argc, char* argv[]) {
    std::map<std::string, std::string> args;
    for (int i = 1; i < argc; ++i) {
        std::string arg = argv[i];
        if (arg.substr(0, 2) == "--") {
            size_t eq = arg.find('=');
            if (eq != std::string::npos) {
                args[arg.substr(2, eq - 2)] = arg.substr(eq + 1);
            } else {
                args[arg.substr(2)] = "";
            }
        }
    }
    return args;
}

int get_int(const std::map<std::string, std::string>& args,
            const std::string& key, int default_val) {
    auto it = args.find(key);
    if (it == args.end()) return default_val;
    return std::stoi(it->second);
}

double get_double(const std::map<std::string, std::string>& args,
                  const std::string& key, double default_val) {
    auto it = args.find(key);
    if (it == args.end()) return default_val;
    return std::stod(it->second);
}

void write_json_solution(const Solution& sol, double exec_time, std::ostream& out) {
    out << "{\"selected_orders\":[";
    for (size_t i = 0; i < sol.orders.size(); ++i) {
        out << sol.orders[i] << (i + 1 < sol.orders.size() ? "," : "");
    }
    out << "],\"visited_aisles\":[";
    for (size_t i = 0; i < sol.aisles.size(); ++i) {
        out << sol.aisles[i] << (i + 1 < sol.aisles.size() ? "," : "");
    }
    out << "],\"exec_time\":" << exec_time;
    if (sol.feasible) {
        out << ",\"objective\":" << sol.objective;
    }
    out << "}\n";
}

int main(int argc, char* argv[]) {
    auto start_time = std::chrono::steady_clock::now();

    try {
        auto args = parse_args(argc, argv);

        std::string input_path;
        auto it = args.find("input");
        if (it != args.end()) {
            input_path = it->second;
        } else {
            std::cerr << "Missing required argument: --input\n";
            return 2;
        }

        std::string output_path;
        it = args.find("output");
        if (it != args.end()) {
            output_path = it->second;
        } else {
            std::cerr << "Missing required argument: --output\n";
            return 2;
        }

        std::string method = "par-it";
        it = args.find("method");
        if (it != args.end()) {
            method = it->second;
        }

        double time_limit = get_double(args, "time-limit", 600.0);
        int n_threads = get_int(args, "threads", 8);
        double partial_time = get_double(args, "partial-time", 210.0);
        int tabu_lock = get_int(args, "tabu-lock", 10);

        Instance inst = read_instance(input_path);
        PreprocResult preproc = preprocess(inst);

        Solution solution;

        if (method == "par-it") {
            ParItParams p;
            p.time_limit_s = time_limit;
            p.n_threads = n_threads;
            ParItResult r = solve_par_it(inst, preproc, p);
            solution = r.best;
        } else if (method == "ref-lin") {
            RefLinParams p;
            p.time_limit_s = time_limit;
            p.n_threads = n_threads;
            solution = solve_ref_lin(inst, preproc, p);
        } else if (method == "hybrid") {
            HybridParams p;
            p.total_time_s = time_limit;
            p.partial_time_s = partial_time;
            p.n_threads = n_threads;
            solution = solve_hybrid(inst, preproc, p);
        } else if (method == "tabu") {
            TabuParams p;
            p.time_limit_s = time_limit;
            p.n_threads = n_threads;
            p.tabu_lock = tabu_lock;
            solution = solve_tabu(inst, preproc, p);
        } else {
            std::cerr << "Unknown method: " << method << "\n";
            return 2;
        }

        auto end_time = std::chrono::steady_clock::now();
        double exec_time = std::chrono::duration<double>(end_time - start_time).count();

        std::ofstream out(output_path);
        if (!out) {
            std::cerr << "Cannot open output file: " << output_path << "\n";
            return 2;
        }

        if (solution.feasible) {
            write_json_solution(solution, exec_time, out);
            out.close();
            return 0;
        } else {
            write_json_solution(solution, exec_time, out);
            out.close();
            return 1;
        }
    } catch (const std::exception& e) {
        std::cerr << "Error: " << e.what() << "\n";
        return 2;
    }
}
