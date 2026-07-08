package model;

import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
    private final List<Integer> aisles;

    public final HashMap<Integer, Integer> actualDemand = new HashMap<>();
    public final HashMap<Integer, Integer> actualStock = new HashMap<>();

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

    public void addAisle(int aisle) {
        aisles.add(aisle);

        problem.aisles.get(aisle).forEach((item, quantity) -> {
            actualStock.put(item, actualStock.getOrDefault(item, 0) + quantity);
        });

        for (int orderIdx = 0; orderIdx < problem.nOrders; orderIdx++) {

            if (orders.contains(orderIdx)) {
                continue;
            }

            // Check if the order can be fulfilled with the current aisles
            boolean canFulfill = true;

            for (Map.Entry<Integer, Integer> entry : problem.orders.get(orderIdx).entrySet()) {
                int item = entry.getKey();
                int quantity = entry.getValue();

                int availableQuantity = actualStock.getOrDefault(item, 0);

                if (availableQuantity < quantity) {
                    canFulfill = false;
                    break;
                }
            }

            if (!canFulfill) {
                continue;
            }

            // If the order can be fulfilled, add it to the solution
            orders.add(orderIdx);

            for (Map.Entry<Integer, Integer> entry : problem.orders.get(orderIdx).entrySet()) {
                int item = entry.getKey();
                int quantity = entry.getValue();

                actualStock.put(item, actualStock.get(item) - quantity);
            }
        }
    }

    public boolean hasAisle(int aisle) {
        return aisles.contains(aisle);
    }

    public Integer getAisleCount() {
        return aisles.size();
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
        this.actualStock.clear();
        this.actualStock.putAll(other.actualStock);
    }

    public Integer getObj() {
        if (aisles.isEmpty()) {
            return 0;
        }

        return orders.size() / aisles.size();
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
     * Writes the solution to a file.
     *
     * @param filePath the output file path.
     * @throws IOException in case any IO error occurs.
     */
    public void write(String filePath) throws IOException {

    }
}
