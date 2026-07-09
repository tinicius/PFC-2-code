package neighborhood;

import java.util.*;

import model.Problem;
import model.Solution;

public class SwapAisle extends Move {

    private int addedAisle = -1;
    private int removedAisle = -1;
    private Solution savedState;

    public SwapAisle(Problem problem, Random random, String name) {
        super(problem, random, name);
    }

    @Override
    public double doMove(Solution solution) {
        super.doMove(solution);

        if (solution.aisles.isEmpty() || solution.aisles.size() == problem.nAisles) {
            return 0;
        }

        List<Integer> available = new ArrayList<>();
        for (int i = 0; i < problem.nAisles; i++) {
            if (!solution.aislePresent[i]) {
                available.add(i);
            }
        }

        if (available.isEmpty()) {
            return 0;
        }

        removedAisle = solution.aisles.get(random.nextInt(solution.aisles.size()));
        addedAisle = available.get(random.nextInt(available.size()));
        savedState = solution.clone();

        solution.removeAisle(removedAisle);
        solution.addAisle(addedAisle);
        solution.randomizedGreedyRebuildOrders(random);

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
        return solution.aisles.size() > 0 && solution.aisles.size() < problem.nAisles;
    }

    @Override
    public void accept() {
        savedState = null;
        addedAisle = -1;
        removedAisle = -1;
        super.accept();
    }

    @Override
    public void reject() {
        if (savedState != null) {
            currentSolution.restoreFrom(savedState);
            savedState = null;
            addedAisle = -1;
            removedAisle = -1;
        }
        super.reject();
    }
}
