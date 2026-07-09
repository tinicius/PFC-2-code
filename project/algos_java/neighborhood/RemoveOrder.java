package neighborhood;

import java.util.*;

import model.Problem;
import model.Solution;

public class RemoveOrder extends Move {

    private int removedOrder = -1;
    private Solution savedState;

    public RemoveOrder(Problem problem, Random random, String name) {
        super(problem, random, name);
    }

    @Override
    public double doMove(Solution solution) {
        super.doMove(solution);

        if (solution.orders.isEmpty()) {
            return 0;
        }

        removedOrder = solution.orders.get(random.nextInt(solution.orders.size()));
        savedState = solution.clone();

        solution.removeOrder(removedOrder);

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
        return solution.aisles.size() > 0 && solution.orders.size() > 0;
    }

    @Override
    public void accept() {
        savedState = null;
        removedOrder = -1;
        super.accept();
    }

    @Override
    public void reject() {
        if (savedState != null) {
            currentSolution.restoreFrom(savedState);
            savedState = null;
            removedOrder = -1;
        }
        super.reject();
    }
}
