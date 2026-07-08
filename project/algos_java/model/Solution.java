package model;

import java.io.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * This class represents a Solution of the Unrelated Parallel Machine Scheduling
 * Problem..
 *
 * @author Tulio Toffolo
 */
public class Solution {

    public final Problem problem;

    public final List<Integer> orders;
    public final List<Integer> aisles;

    private boolean assertOn = false;

    /**
     * Instantiates a new Solution.
     *
     * @param problem problem considered.
     */
    public Solution(Problem problem) {
        this.problem = problem;

        orders = new ArrayList<>();
        aisles = new ArrayList<>();

        // *assigns* true if assertions are on.
        assert assertOn = true;
    }

    /**
     * Private constructor used for cloning.
     *
     * @param solution solution to copy from.
     */
    private Solution(Solution solution) {
        this.problem = solution.problem;

        this.orders = new ArrayList<>(solution.orders);
        this.aisles = new ArrayList<>(solution.aisles);

        assertOn = solution.assertOn;
    }

    /**
     * Creates and returns a copy of this solution.
     */
    public Solution clone() {
        return new Solution(this);
    }

    /**
     * Restores this solution's state from another solution (used for rollback).
     */
    public void restoreFrom(Solution other) {
        this.orders.clear();
        this.orders.addAll(other.orders);
        this.aisles.clear();
        this.aisles.addAll(other.aisles);
    }

    public Double getObj() {
        if (aisles.isEmpty()) {
            return 0.0;
        }

        int itemsPicked = 0;
        for (int order : orders) {
            for (int quantity : problem.orders.get(order).values()) {
                itemsPicked += quantity;
            }
        }

        return (double) itemsPicked / aisles.size();
    }

    /**
     * Validates the solution.
     *
     * @param output output stream (example: System.out) to print eventual error
     *               messages.
     * @return true if the solution and its costs are valid and false otherwise.
     */
    public boolean validate(PrintStream output) {
        // Check wave size bounds
        int totalUnitsPicked = 0;

        for (int order : this.orders) {
            for (int quantity : problem.orders.get(order).values()) {
                totalUnitsPicked += quantity;
            }
        }

        if (totalUnitsPicked < problem.lb || totalUnitsPicked > problem.ub) {
            return false;
        }

        // Collect all required items
        Set<Integer> requiredItems = new HashSet<>();

        for (int order : this.orders) {
            requiredItems.addAll(problem.orders.get(order).keySet());
        }

        // Check stock availability
        for (int item : requiredItems) {

            int totalRequired = 0;
            for (int order : this.orders) {
                totalRequired += problem.orders
                        .get(order)
                        .getOrDefault(item, 0);
            }

            int totalAvailable = 0;
            for (int aisle : this.aisles) {
                totalAvailable += problem.aisles
                        .get(aisle)
                        .getOrDefault(item, 0);
            }

            if (totalRequired > totalAvailable) {
                return false;
            }
        }

        return true;
    }

    /**
     * Writes the solution to a JSON file.
     *
     * @param filePath the output file path.
     * @param execTime the execution time in seconds.
     * @throws IOException in case any IO error occurs.
     */
    public void write(String filePath, double execTime) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"selected_orders\":[");
        for (int i = 0; i < orders.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(orders.get(i));
        }
        sb.append("],\"visited_aisles\":[");
        for (int i = 0; i < aisles.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(aisles.get(i));
        }
        sb.append("],\"exec_time\":").append(execTime).append("}");
        Files.write(Paths.get(filePath), sb.toString().getBytes());
    }
}
