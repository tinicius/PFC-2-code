package neighborhood;

import java.util.*;

import model.Problem;
import model.Solution;

public class AddOrder extends Move {

    private int addedOrder = -1;
    private Solution savedState;

    public AddOrder(Problem problem, Random random, String name) {
        super(problem, random, name);
    }

    @Override
    public double doMove(Solution solution) {
        super.doMove(solution);

        List<Integer> availableOrders = new ArrayList<>();
        // Calculate remaining stock
        int[] remainingStock = solution.stock.clone();
        for (int orderIdx : solution.orders) {
            for (Map.Entry<Integer, Integer> entry : problem.orders.get(orderIdx).entrySet()) {
                remainingStock[entry.getKey()] -= entry.getValue();
            }
        }

        for (int i = 0; i < problem.nOrders; i++) {
            if (!solution.orders.contains(i)) {
                boolean canFulfill = true;
                for (Map.Entry<Integer, Integer> entry : problem.orders.get(i).entrySet()) {
                    if (remainingStock[entry.getKey()] < entry.getValue()) {
                        canFulfill = false;
                        break;
                    }
                }
                if (canFulfill && solution.getTotalItemsPicked() + problem.orderUnits[i] <= problem.ub) {
                    availableOrders.add(i);
                }
            }
        }

        if (availableOrders.isEmpty()) {
            return 0;
        }

        addedOrder = availableOrders.get(random.nextInt(availableOrders.size()));
        savedState = solution.clone();

        solution.addOrder(addedOrder);

        if (solution.getTotalItemsPicked() < problem.lb) {
            deltaObj = -1;
            return -(Math.abs(initialCost) + 1);
        }

        double delta = solution.getObj() - initialCost;
        deltaObj = (delta > 0) ? 1 : (delta == 0) ? 0 : -1;
        return delta;
    }

    @Override
    public boolean hasMove(Solution solution) {
        return solution.aisles.size() > 0 && solution.orders.size() < problem.nOrders;
    }

    @Override
    public void accept() {
        savedState = null;
        addedOrder = -1;
        super.accept();
    }

    @Override
    public void reject() {
        if (savedState != null) {
            currentSolution.restoreFrom(savedState);
            savedState = null;
            addedOrder = -1;
        }
        super.reject();
    }
}
