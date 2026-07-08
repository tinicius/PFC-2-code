package neighborhood;

import java.util.*;

import model.Problem;
import model.Solution;

public class RemoveAisle extends Move {

    private int removedAisle = -1;
    private Solution savedState;

    public RemoveAisle(Problem problem, Random random, String name) {
        super(problem, random, name);
    }

    public double doMove(Solution solution) {
        super.doMove(solution);

        if (solution.aisles.isEmpty()) {
            return 0;
        }

        removedAisle = solution.aisles.get(random.nextInt(solution.aisles.size()));
        savedState = solution.clone();

        solution.aisles.remove((Integer) removedAisle);

        final HashMap<Integer, Integer> actualStock = new HashMap<>();

        solution.aisles.forEach(aisle -> {
            problem.aisles.get(aisle).forEach((item, quantity) -> {
                actualStock.merge(item, quantity, Integer::sum);
            });
        });

        solution.orders.clear();

        int totalPicked = 0;

        for (int orderIdx = 0; orderIdx < problem.nOrders; orderIdx++) {
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

            // Check if adding this order would exceed the upper bound
            int orderUnits = 0;
            for (int quantity : problem.orders.get(orderIdx).values()) {
                orderUnits += quantity;
            }
            if (totalPicked + orderUnits > problem.ub) {
                continue;
            }

            // If the order can be fulfilled, add it to the solution
            solution.orders.add(orderIdx);
            totalPicked += orderUnits;

            for (Map.Entry<Integer, Integer> entry : problem.orders.get(orderIdx).entrySet()) {
                int item = entry.getKey();
                int quantity = entry.getValue();

                actualStock.merge(item, -quantity, Integer::sum);
            }
        }

        if (totalPicked < problem.lb) {
            return -(Math.abs(initialCost) + 1);
        }

        return solution.getObj() - initialCost;
    }

    @Override
    public boolean hasMove(Solution solution) {
        return solution.aisles.size() > 0;
    }

    @Override
    public void accept() {
        savedState = null;
        removedAisle = -1;
        super.accept();
    }

    @Override
    public void reject() {
        if (savedState != null) {
            currentSolution.restoreFrom(savedState);
            savedState = null;
            removedAisle = -1;
        }
        super.reject();
    }
}
