package neighborhood;

import java.util.*;

import model.Problem;
import model.Solution;

public class AddAisle extends Move {

    private int addedAisle = -1;
    private Solution savedState;

    public AddAisle(Problem problem, Random random) {
        super(problem, random);
    }

    public int doMove(Solution solution) {
        super.doMove(solution);

        List<Integer> available = new ArrayList<>();
        for (int i = 0; i < problem.nAisles; i++) {
            if (!solution.hasAisle(i)) {
                available.add(i);
            }
        }

        if (available.isEmpty()) {
            return 0;
        }

        addedAisle = available.get(random.nextInt(available.size()));
        savedState = solution.clone();
        solution.addAisle(addedAisle);
        return solution.getObj() - initialCost;
    }

    @Override
    public boolean hasMove(Solution solution) {
        return solution.getAisleCount() < problem.nAisles;
    }

    @Override
    public void accept() {
        savedState = null;
        addedAisle = -1;
        super.accept();
    }

    @Override
    public void reject() {
        if (savedState != null) {
            currentSolution.restoreFrom(savedState);
            savedState = null;
            addedAisle = -1;
        }
        super.reject();
    }
}
