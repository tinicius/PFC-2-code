// To compile: mvn clean package
// To run: java -Djava.library.path=C:/Applications/CPLEX_Studio2211/opl/bin/x64_win64 -jar target/ChallengeSBPO2025-1.0.jar <input-file> <output-file>

package org.sbpo2025.challenge;

import org.apache.commons.lang3.time.StopWatch;

public class Challenge {
	
	final static int SMALL_ENOUGH = 1000,
		             BIG_NUMBER_OF_BOOLS = 2400,
		             HUGE_NUMBER_OF_BOOLS = 5700;
	final static double BIG_RANGABLE = 3.7;
	
    public static void main(String[] args) {
        // Start the stopwatch to track the running time
        StopWatch stopWatch = StopWatch.createStarted();

        // Check for the correct usage of command-line arguments.
        if (args.length < 2 || args.length > 3) {
            System.out.println("Usage: java -Djava.library.path=C:\\Applications\\CPLEX_Studio2211\\opl\\bin\\x64_win64 "
            		+ "-jar target/ChallengeSBPO2025-1.0.jar <inputFilePath> <outputFilePath> [seed]");
            return;
        }
        
        long seed = args.length == 3 ? Long.parseLong(args[2]) : 1L;
        
        // Read instance file.
        Instance instance = new Instance(args[0]);
        
        // Execute solver for instance read.
        var solver = new ChallengeSolver(instance, seed);
        ChallengeSolution solution = null;
        
        //solution = solver.solve(Method.ParallelIterative, stopWatch);
        //solution = solver.solve(Method.GeneticAlgorithm, stopWatch);
        
        switch(solver.getItemsDistribution()) {
        	case AllOneItem:
        		//if(instance.orders.size() - solver.getAmountOfUnitOrders() + instance.aisles.size() >= HUGE_NUMBER_OF_BOOLS)
        			//solution = solver.solve(Method.GeneticAlgorithm, stopWatch);
        		//else 
        		if(instance.orders.size() - solver.getAmountOfUnitOrders() + instance.aisles.size() >= BIG_NUMBER_OF_BOOLS)
        			solution = solver.solve(Method.TabuSearch, stopWatch);
        		else
        			solution = solver.solve(Method.ParallelIterative, stopWatch);
        		break;
        		
        	case MixedItemsQuantities:
        		if(instance.orders.size() <= SMALL_ENOUGH || (100.0/instance.aisles.size() * ((double)instance.UB)/instance.medianItems >= BIG_RANGABLE))
        			solution = solver.solve(Method.ParallelIterative, stopWatch);
        		else
        			solution = solver.solve(Method.TabuSearch, stopWatch);
        		break;
        		
        	case AllMultipleItems:
        		if(instance.orders.size() <= SMALL_ENOUGH || (100.0/instance.aisles.size() * ((double)instance.UB)/instance.medianItems >= BIG_RANGABLE))
        			solution = solver.solve(Method.ParallelIterative, stopWatch);
        		else
        			solution = solver.solve(Method.TabuSearch, stopWatch);
        		break;
        }
        
        
        instance.writeOutput(solution, args[1], stopWatch.getTime() / 1000.0);
    }
}
