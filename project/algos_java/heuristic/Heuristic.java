package heuristic;

import model.Problem;
import model.Solution;
import neighborhood.Move;

import java.io.*;
import java.util.*;

/**
 * This abstract class represents a Heuristic (or Local Search method). The
 * basic methods and neighborhood selection are
 * included.
 *
 * @author Tulio Toffolo
 */
public abstract class Heuristic {

    public final static boolean USE_LEARNING = false;

    public final Problem problem;
    public final Random random;
    public final String name;

    protected final List<Move> moves = new ArrayList<>();

    protected Solution bestSolution;
    protected int sumWeights = 0;
    protected long nIters = 0;

    /**
     * Pre-allocated buffer for selectMove to avoid per-call allocation.
     */
    private final Move[] availableBuf = new Move[64];

    /**
     * Instantiates a new Heuristic.
     *
     * @param problem the problem reference.
     * @param random  the random number generator.
     * @param name    the name
     */
    public Heuristic(Problem problem, Random random, String name) {
        this.problem = problem;
        this.random = random;
        this.name = name;
    }

    /**
     * Adds a move to the heuristic.
     *
     * @param move the move to be added.
     */
    public void addMove(Move move) {
        moves.add(move);
        moves.sort((a, b) -> -Integer.compare(a.getPriority(), b.getPriority()));
        sumWeights += move.getPriority();
    }

    /**
     * Accepts move and updates learning algorithm (if present).
     *
     * @param move the move to be accepted.
     */
    public void acceptMove(Move move) {
        move.accept();

        // if (USE_LEARNING && move.getDeltaCost() < 0)
        // learningAutomata.updateProbabilities(1.0);
    }

    /**
     * Rejects move and updates learning algorithm (if present).
     *
     * @param move the move to be rejected.
     */
    public void rejectMove(Move move) {
        move.reject();

        // if (USE_LEARNING) learningAutomata.updateProbabilities(0.0);
    }

    /**
     * Resets all moves considered by the heuristic.
     */
    public void resetMoves() {
        for (Move move : moves)
            move.reset();
    }

    /**
     * Runs the local search, returning the best solution obtained..
     *
     * @param solution        the initial (input) solution.
     * @param timeLimitMillis the time limit in milliseconds.
     * @param maxIters        the maximum number of iterations to execute.
     * @param output          the output
     * @return the solution
     */
    public abstract Solution run(Solution solution, long timeLimitMillis, long maxIters, PrintStream output);

    /**
     * Selects move using a pre-allocated buffer (zero allocation).
     *
     * @param solution the solution
     * @return a randomly selected move (neighborhood), considering the provided
     *         weights.
     */
    protected Move selectMove(Solution solution) {
        int count = 0;
        int totalWeight = 0;
        for (int i = 0, n = moves.size(); i < n; i++) {
            Move move = moves.get(i);
            if (move.hasMove(solution)) {
                availableBuf[count++] = move;
                totalWeight += move.getPriority();
            }
        }
        if (count == 0) return null;
        int r = random.nextInt(totalWeight);
        int cumulative = 0;
        for (int i = 0; i < count; i++) {
            cumulative += availableBuf[i].getPriority();
            if (r < cumulative) return availableBuf[i];
        }
        return availableBuf[count - 1];
    }

    // region getters and setters

    /**
     * Gets best solution.
     *
     * @return the best solution obtained so far.
     */
    public Solution getBestSolution() {
        return bestSolution;
    }

    /**
     * Returns an unmodifiableList with the moves in the heuristic.
     *
     * @return an unmodifiableList with the moves in the heuristic.
     */
    public List<Move> getMoves() {
        return Collections.unmodifiableList(moves);
    }

    /**
     * Gets the number of iterations executed.
     *
     * @return the n iters
     */
    public long getNIters() {
        return nIters;
    }

    /**
     * Returns the string representation of the heuristic.
     *
     * @return the string representation of the heuristic.
     */
    public String toString() {
        return name;
    }

    // endregion getters and setters
}
