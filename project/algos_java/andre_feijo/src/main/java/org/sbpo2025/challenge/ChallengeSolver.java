package org.sbpo2025.challenge;

import org.apache.commons.lang3.time.StopWatch;

import ilog.concert.IloException;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;


enum Method {
	Iterative,
	GeneralParallelIterative,
	ParallelIterative,
	ParamFractional,
	RLFractional,
	ItRL,
	TabuSearch,
	GeneticAlgorithm
}

enum ItemsDistribution {
	AllOneItem,
	MixedItemsQuantities,
	AllMultipleItems
}

public class ChallengeSolver {
	
    ChallengeSolution solution = null;
    private Instance inst;
    private long seed;
    
    // Time Limit
    int HOURS   = 0, 
        MINUTES = 9, 
        SECONDS = 50;

    public ChallengeSolver(Instance instance, long seed) {
        this.inst = instance;
        this.seed = seed;
    }

    public ChallengeSolution solve(Method method, StopWatch stopWatch) {
    	print_header(method);
    	
    	switch(method) {
	    	case Iterative:
	        	Iterative itModel = new Iterative(this.inst, stopWatch, getTimeLimitInSeconds(), 30000);
	        	solution = itModel.optimize();
	        	break;

	    	case GeneralParallelIterative:
	    		ParallelIterative genParallelIterative = new ParallelIterative(this.inst, stopWatch, getTimeLimitInSeconds());
	        	solution = genParallelIterative.optimize();
	    		break;
	        	
	    	case ParallelIterative:
	    		ParallelIterative parallelIterative = new ParallelIterative(this.inst, stopWatch, getTimeLimitInSeconds());
	        	solution = parallelIterative.optimize();
	    		break;
	    		
	    	case ParamFractional:
	    		ParametricFractional paramFractional = new ParametricFractional(this.inst, stopWatch, getTimeLimitInSeconds());
	        	solution = paramFractional.optimize();
	    		break;
	    		
	    	case RLFractional:
	    		RefLinFractional refLinFractional = new RefLinFractional(this.inst, stopWatch, getTimeLimitInSeconds());
	        	solution = refLinFractional.optimize();
	    		break;
	    	
	    	case ItRL:
	    		ParallelIterative model1 = new ParallelIterative(this.inst, stopWatch, (int)(3.5*60*1000));
	    		RefLinFractional model2 = new RefLinFractional(this.inst, stopWatch, getTimeLimitInSeconds());
	        	solution = model1.optimize();
	    		
	        	if(!model1.optimal) {
		    		try {
		    			model2.init(model1, solution);
		    			model1 = null;
		    		} catch(IloException e) {
		    			e.printStackTrace();
		    		}
		    		
		        	solution = model2.optimize();
	        	}
	    		break;
	    		
	    	case TabuSearch:
	    		TSHeuristic tb = new TSHeuristic(this.inst, stopWatch, getTimeLimitInSeconds());
	        	solution = tb.optimize();
	    		break;
	    		
	    	case GeneticAlgorithm:
	    		GeneticAlgorithm ga = new GeneticAlgorithm(this.inst, stopWatch, getTimeLimitInSeconds(), this.seed);
	        	solution = ga.optimize();
	    		break;
    	}
    	
    	
    	System.out.println("Is Feasible: " + isSolutionFeasible());
    	System.out.println("Objective Value: " + computeObjectiveFunction());
    	System.out.println("Time: " + stopWatch);
    	
        return solution;
    }
    
    public ItemsDistribution getItemsDistribution() {
    	ItemsDistribution res = ItemsDistribution.AllOneItem;
    	
    	int multipleItemsCnt = 0;
    	for(int o = 0; o < inst.orders.size(); o++)
    		if(inst.orders.get(o).keySet().size() > 1) {
    			res = ItemsDistribution.MixedItemsQuantities;
    			multipleItemsCnt++;
    		}
    	
    	if(multipleItemsCnt == inst.orders.size())
    		res = ItemsDistribution.AllMultipleItems;
    	
    	return res;
    }
    
    public int getAmountOfUnitOrders() {
    	int cnt = 0;
    	
    	for(int o = 0; o < inst.orders.size(); o++)
    		if(inst.orders.get(o).keySet().size() == 1) {
    			var it = inst.orders.get(o).keySet().iterator().next();
    			if(inst.orders.get(o).get(it) == 1)
    				cnt++;
    		}
    	
    	return cnt;
    }
    
    protected int getTimeLimitInSeconds() {
    	return 1000*(3600 * HOURS + 60 * MINUTES + SECONDS);
    }

    protected boolean isSolutionFeasible() {
        Set<Integer> selectedOrders = solution.orders();
        Set<Integer> visitedAisles = solution.aisles();
        if (selectedOrders == null || visitedAisles == null || selectedOrders.isEmpty() || visitedAisles.isEmpty()) {
            return false;
        }

        int[] totalUnitsPicked = new int[inst.n];
        int[] totalUnitsAvailable = new int[inst.n];

        // Calculate total units picked
        for (int order : selectedOrders) {
            for (Map.Entry<Integer, Integer> entry : inst.orders.get(order).entrySet()) {
                totalUnitsPicked[entry.getKey()] += entry.getValue();
            }
        }

        // Calculate total units available
        for (int aisle : visitedAisles) {
            for (Map.Entry<Integer, Integer> entry : inst.aisles.get(aisle).entrySet()) {
                totalUnitsAvailable[entry.getKey()] += entry.getValue();
            }
        }

        // Check if the total units picked are within bounds
        int totalUnits = Arrays.stream(totalUnitsPicked).sum();
        if (totalUnits < inst.LB || totalUnits > inst.UB) {
            return false;
        }

        // Check if the units picked do not exceed the units available
        for (int i = 0; i < inst.n; i++) {
            if (totalUnitsPicked[i] > totalUnitsAvailable[i]) {
                return false;
            }
        }

        return true;
    }

    protected double computeObjectiveFunction() {
        Set<Integer> selectedOrders = solution.orders();
        Set<Integer> visitedAisles = solution.aisles();
        if (selectedOrders == null || visitedAisles == null || selectedOrders.isEmpty() || visitedAisles.isEmpty()) {
            return 0.0;
        }
        int totalUnitsPicked = 0;

        // Calculate total units picked
        for (int order : selectedOrders) {
            totalUnitsPicked += inst.orders.get(order).values().stream()
                    .mapToInt(Integer::intValue)
                    .sum();
        }

        // Calculate the number of visited aisles
        int numVisitedAisles = visitedAisles.size();

        // Objective function: total units picked / number of visited aisles
        return (double) totalUnitsPicked / numVisitedAisles;
    }
    
    protected void print_header(Method method) {
    	System.out.println("SPO Optimizer (authors: @andrefeijosantos, @PedroFiorio)");
    	
    	switch(method) {
	    	case TabuSearch:
	    		System.out.println("Using heuristic approach.\n");
	    		break;
	    		
	    	case GeneticAlgorithm:
	    		System.out.println("Using heuristic approach.\n");
	    		break;
	    	
	    	default:
	    		System.out.println("Solving instance to optimality.\n");
	    		break;
    	}
    }
}
