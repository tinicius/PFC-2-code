package model;

import java.io.*;
import java.nio.file.*;
import java.util.ArrayList;
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
    public final List<Integer> aisles;

    /**
     * Fast O(1) lookup for aisle membership (replaces aisles.contains()).
     */
    public final boolean[] aislePresent;

    /**
     * Aggregated stock from all selected aisles, indexed by item id.
     * Updated incrementally via addAisle() / removeAisle().
     */
    public final int[] stock;

    /**
     * Work buffer for greedy rebuild — avoids allocation on every call.
     */
    private final int[] tempStock;

    /**
     * Cached total items picked across all selected orders.
     * Updated incrementally via addOrder() / clearOrders().
     */
    private int totalItemsPicked;

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

        aislePresent = new boolean[problem.nAisles];
        stock = new int[problem.nItems];
        tempStock = new int[problem.nItems];
        totalItemsPicked = 0;

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

        this.aislePresent = solution.aislePresent.clone();
        this.stock = solution.stock.clone();
        this.tempStock = new int[problem.nItems]; // work buffer, no need to copy
        this.totalItemsPicked = solution.totalItemsPicked;

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
        System.arraycopy(other.aislePresent, 0, this.aislePresent, 0, aislePresent.length);
        System.arraycopy(other.stock, 0, this.stock, 0, stock.length);
        this.totalItemsPicked = other.totalItemsPicked;
    }

    /**
     * Adds an aisle and incrementally updates stock.
     */
    public void addAisle(int aisle) {
        aisles.add(aisle);
        aislePresent[aisle] = true;
        for (Map.Entry<Integer, Integer> e : problem.aisles.get(aisle).entrySet()) {
            stock[e.getKey()] += e.getValue();
        }
    }

    /**
     * Removes an aisle and incrementally updates stock.
     */
    public void removeAisle(int aisle) {
        aisles.remove((Integer) aisle);
        aislePresent[aisle] = false;
        for (Map.Entry<Integer, Integer> e : problem.aisles.get(aisle).entrySet()) {
            stock[e.getKey()] -= e.getValue();
        }
    }

    /**
     * Clears all selected orders and resets totalItemsPicked.
     */
    public void clearOrders() {
        orders.clear();
        totalItemsPicked = 0;
    }

    /**
     * Adds an order and updates the cached totalItemsPicked.
     */
    public void addOrder(int orderIdx) {
        orders.add(orderIdx);
        totalItemsPicked += problem.orderUnits[orderIdx];
    }

    /**
     * Returns the cached total items picked.
     */
    public int getTotalItemsPicked() {
        return totalItemsPicked;
    }

    /**
     * Greedily rebuilds the order selection based on current aisles/stock.
     * Uses a reusable work buffer to avoid per-call allocation.
     */
    public void greedyRebuildOrders() {
        clearOrders();
        System.arraycopy(stock, 0, tempStock, 0, stock.length);

        for (int orderIdx = 0; orderIdx < problem.nOrders; orderIdx++) {
            // Check if the order can be fulfilled with the current stock
            boolean canFulfill = true;
            for (Map.Entry<Integer, Integer> entry : problem.orders.get(orderIdx).entrySet()) {
                if (tempStock[entry.getKey()] < entry.getValue()) {
                    canFulfill = false;
                    break;
                }
            }
            if (!canFulfill) continue;

            // Check if adding this order would exceed the upper bound
            int units = problem.orderUnits[orderIdx];
            if (totalItemsPicked + units > problem.ub) continue;

            // Add the order and consume stock
            addOrder(orderIdx);
            for (Map.Entry<Integer, Integer> entry : problem.orders.get(orderIdx).entrySet()) {
                tempStock[entry.getKey()] -= entry.getValue();
            }
        }
    }

    /**
     * Returns the objective value. O(1) thanks to cached totalItemsPicked.
     */
    public Double getObj() {
        if (aisles.isEmpty()) {
            return 0.0;
        }
        return (double) totalItemsPicked / aisles.size();
    }

    /**
     * Validates the solution.
     *
     * @param output output stream (example: System.out) to print eventual error
     *               messages.
     * @return true if the solution and its costs are valid and false otherwise.
     */
    public boolean validate(PrintStream output) {
        // Check wave size bounds (independent recomputation for verification)
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
