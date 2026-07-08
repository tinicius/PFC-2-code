import java.io.IOException;
import java.util.Random;

import heuristic.Heuristic;
import heuristic.SA;
import model.Problem;
import model.Solution;
import neighborhood.AddAisle;

public class Main {

    public static long startTimeMillis;

    public static String inFile = "datasets/a/instance_0001.txt";

    public static long seed = 42;
    public static long timeLimit = (10 * 1000); // in milliseconds
    public static long maxIters = 10000;

    public static void main(String[] args) throws IOException {

        Problem problem = new Problem(inFile);
        Random random = new Random(seed);

        // re-starting time counting (after reading files)
        startTimeMillis = System.currentTimeMillis();

        Solution solution = new Solution(problem);

        Heuristic solver = new SA(problem, random, 0.95, 1000.0, 10000);

        solver.addMove(new AddAisle(problem, random));

        // running stochastic local search
        if (solver.getMoves().size() > 0)
            solution = solver.run(solution, timeLimit, maxIters, System.out);

        solution.validate(System.err);
        
        System.out.printf("Best makespan.....: %f\n", solution.getObj());
        System.out.printf("N. of Iterations..: %d\n", solver.getNIters());
        System.out.printf("Total runtime.....: %.2fs\n", (System.currentTimeMillis() - startTimeMillis) / 1000.0);
    }

    /**
     * Reads the input arguments.
     *
     * @param args the input arguments
     */
    public static boolean readArgs(String args[]) {
        if (args.length < 2) {
            // printUsage();
            return false;
        }

        int index = -1;

        inFile = args[++index];

        return true;
    }
}