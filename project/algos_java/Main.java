import java.io.IOException;
import java.util.Random;

import constructive.AisleFirst;
import heuristic.Heuristic;
import heuristic.SA;
import heuristic.ILS;
import model.Problem;
import model.Solution;
import neighborhood.AddAisle;
import neighborhood.Move;
import neighborhood.RemoveAisle;

public class Main {

    public static long startTimeMillis;

    public static String inFile = null;
    public static String outFile = null;
    public static long seed = 42;
    public static long timeLimit = 10000; // milliseconds
    public static long maxIters = 10000;
    public static String algo = "sa";
    public static double alpha = 0.95;
    public static double t0 = 1000.0;
    public static int saMax = 10000;
    public static String score = "useful";

    // ILS parameters
    public static int maxLocalIters = 5000;
    public static double perturbationStrength = 0.25;
    public static double acceptanceThreshold = 0.02;

    public static void main(String[] args) throws IOException {
        if (!readArgs(args)) {
            System.err.println("Usage: java Main --input=<path> --output=<path> --time-limit=<secs> [--seed=<int>] [--algo=<name>] [--params=<json>]");
            System.exit(1);
        }

        Problem problem = new Problem(inFile);
        Random random = new Random(seed);

        startTimeMillis = System.currentTimeMillis();

        Solution solution = new Solution(problem);

        if ("sa".equals(algo)) {
            // Seed SA with AisleFirst solution
            AisleFirst constructor = new AisleFirst();
            solution = constructor.solve(problem);

            Heuristic solver = new SA(problem, random, alpha, t0, saMax);
            Move addAisle = new AddAisle(problem, random, "AddAisle");
            addAisle.setPriority(2);
            solver.addMove(addAisle);
            
            Move removeAisle = new RemoveAisle(problem, random, "RemoveAisle");
            removeAisle.setPriority(1);
            solver.addMove(removeAisle);

            Move swapAisle = new neighborhood.SwapAisle(problem, random, "SwapAisle");
            swapAisle.setPriority(4);
            solver.addMove(swapAisle);

            Move swapOrder = new neighborhood.SwapOrder(problem, random, "SwapOrder");
            swapOrder.setPriority(3);
            solver.addMove(swapOrder);

            Move addOrder = new neighborhood.AddOrder(problem, random, "AddOrder");
            addOrder.setPriority(2);
            solver.addMove(addOrder);

            Move removeOrder = new neighborhood.RemoveOrder(problem, random, "RemoveOrder");
            removeOrder.setPriority(1);
            solver.addMove(removeOrder);

            if (solver.getMoves().size() > 0)
                solution = solver.run(solution, timeLimit, maxIters, System.out);
        }
        else if ("ils".equals(algo)) {
            // Seed ILS with AisleFirst solution
            AisleFirst constructor = new AisleFirst();
            solution = constructor.solve(problem);

            Heuristic solver = new ILS(problem, random, maxLocalIters, perturbationStrength, acceptanceThreshold);
            Move addAisle = new AddAisle(problem, random, "AddAisle");
            addAisle.setPriority(2);
            solver.addMove(addAisle);

            Move removeAisle = new RemoveAisle(problem, random, "RemoveAisle");
            removeAisle.setPriority(1);
            solver.addMove(removeAisle);

            Move swapAisle = new neighborhood.SwapAisle(problem, random, "SwapAisle");
            swapAisle.setPriority(4);
            solver.addMove(swapAisle);

            Move swapOrder = new neighborhood.SwapOrder(problem, random, "SwapOrder");
            swapOrder.setPriority(3);
            solver.addMove(swapOrder);

            Move addOrder = new neighborhood.AddOrder(problem, random, "AddOrder");
            addOrder.setPriority(2);
            solver.addMove(addOrder);

            Move removeOrder = new neighborhood.RemoveOrder(problem, random, "RemoveOrder");
            removeOrder.setPriority(1);
            solver.addMove(removeOrder);

            if (solver.getMoves().size() > 0)
                solution = solver.run(solution, timeLimit, maxIters, System.out);
        }
        else if ("aisle_first".equals(algo)) {
            AisleFirst solver = new AisleFirst();
            solution = solver.solve(problem);
        }
        else {
            System.err.println("Unknown algorithm: " + algo);
            System.exit(1);
        }

        double execTime = (System.currentTimeMillis() - startTimeMillis) / 1000.0;

        solution.write(outFile, execTime);
        solution.validate(System.err);

        System.out.printf("Best objective..: %f\n", solution.getObj());
        System.out.printf("Total runtime...: %.2fs\n", execTime);
    }

    /**
     * Reads the input arguments.
     *
     * @param args the input arguments
     * @return true if required arguments are present
     */
    public static boolean readArgs(String args[]) {
        for (String arg : args) {
            if (arg.startsWith("--input=")) {
                inFile = arg.substring("--input=".length());
            }
            else if (arg.startsWith("--output=")) {
                outFile = arg.substring("--output=".length());
            }
            else if (arg.startsWith("--time-limit=")) {
                timeLimit = Long.parseLong(arg.substring("--time-limit=".length())) * 1000;
            }
            else if (arg.startsWith("--seed=")) {
                seed = Long.parseLong(arg.substring("--seed=".length()));
            }
            else if (arg.startsWith("--algo=")) {
                algo = arg.substring("--algo=".length());
            }
            else if (arg.startsWith("--params=")) {
                parseParams(arg.substring("--params=".length()));
            }
        }

        return inFile != null && outFile != null;
    }

    /**
     * Parses a flat JSON object for algorithm params.
     * Expected format: {"alpha":0.95,"t0":1000.0,"saMax":10000,"maxIters":10000}
     */
    private static void parseParams(String json) {
        if (json == null || json.isEmpty()) return;
        json = json.trim();
        if (!json.startsWith("{") || !json.endsWith("}")) return;
        json = json.substring(1, json.length() - 1).trim();
        if (json.isEmpty()) return;

        String[] pairs = json.split(",");
        for (String pair : pairs) {
            pair = pair.trim();
            int colonIdx = pair.indexOf(':');
            if (colonIdx < 0) continue;
            String key = pair.substring(0, colonIdx).trim().replaceAll("^\"|\"$", "");
            String value = pair.substring(colonIdx + 1).trim().replaceAll("^\"|\"$", "");

            switch (key) {
                case "alpha":
                    alpha = Double.parseDouble(value);
                    break;
                case "t0":
                    t0 = Double.parseDouble(value);
                    break;
                case "saMax":
                    saMax = Integer.parseInt(value);
                    break;
                case "maxIters":
                    maxIters = Long.parseLong(value);
                    break;
                case "score":
                    score = value;
                    break;
                case "maxLocalIters":
                    maxLocalIters = Integer.parseInt(value);
                    break;
                case "perturbationStrength":
                    perturbationStrength = Double.parseDouble(value);
                    break;
                case "acceptanceThreshold":
                    acceptanceThreshold = Double.parseDouble(value);
                    break;
            }
        }
    }
}