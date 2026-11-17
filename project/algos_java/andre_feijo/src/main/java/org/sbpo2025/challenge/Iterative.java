package org.sbpo2025.challenge;

import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.time.StopWatch;

import ilog.concert.*;
import ilog.cplex.*;


public class Iterative extends Approach {
	
	// CPLEX model.
	ItModel model;
	
	// Solver informations.
	boolean optimal = true;
	int MIN_AISLES;
	int MAX_AISLES;
	
	// Some other data.
	int dummySolutionTime;
	boolean solutionFound = false;
	
	public Iterative(Instance inst, StopWatch stopWatch, long timeLimit, int dummyTime) {
		super(inst, stopWatch, timeLimit);
		this.model = new ItModel(inst, 20);
		this.MAX_AISLES = inst.aisles.size();
		this.dummySolutionTime = dummyTime;
	}

	
	public ChallengeSolution optimize() {
		try {		
			model.build();			
			print_header();
			
			for(; MAX_AISLES >= 0; MAX_AISLES--) {
				if(getRemainingDummyTime(stopWatch) <= 5) {
					logln("Time Limit for dummy solutions reached.\n");
					break;
				}
				
				// Set parameters for running the model for h aisles..
				model.setTimeLimit(getRemainingDummyTime(stopWatch));
				model.setSumY(MAX_AISLES);
				
				// Optimizes model for "num_aisles" aisles.
				model.solve();
				
				// If a better solution was found.
				if((model.getStatus() == IloCplex.Status.Feasible || model.getStatus() == IloCplex.Status.Optimal) && model.getObjValue()/MAX_AISLES > objVal) {					
					objVal = model.getObjValue()/MAX_AISLES;
					solution = model.saveSolution();
				}
				
				print_line(MAX_AISLES);
			}

			
			for(int h = 1; h <= MAX_AISLES; h++) {
				if(getRemainingTime(stopWatch) <= 5) {
					logln("Time Limit reached.");
					optimal = false;
					break;
				}
				
				// Update minimum aisles.
				if(!solutionFound)
					MIN_AISLES = h;
				
				// Sets the Lower Bound.
				if(solution != null) {
					int new_lb = (int) Math.floor(objVal * h + 1);
					if(new_lb > inst.UB) break;
					model.setLB(Math.max(inst.LB, new_lb));
				}
				
				// Set parameters for running the model for h aisles..
				model.setTimeLimit(getRemainingTime(stopWatch));
				model.setSumY(h);
				
				// Optimizes model for "num_aisles" aisles.
				model.solve();
				
				// If a better solution was found.
				if((model.getStatus() == IloCplex.Status.Feasible || model.getStatus() == IloCplex.Status.Optimal) && model.getObjValue()/h > objVal) {					
					objVal = model.getObjValue()/h;
					solution = model.saveSolution();
					
					// Update max_aisles.
					double result = inst.UB / objVal;
					MAX_AISLES = Math.min(MAX_AISLES, (int) Math.floor(result));
					
					solutionFound = true;
				}
				
				print_line(h);
			}
			
			logln("" + MIN_AISLES + " " + MAX_AISLES);
			
			logln("Solution found: " + objVal);
			logln("Optimal: " + optimal + "\n");
			
		} catch(IloException e) {
			e.printStackTrace();
		}
		
		return solution;
	}
	
	protected long getRemainingDummyTime(StopWatch stopWatch) {
        return Math.max(TimeUnit.SECONDS.convert(dummySolutionTime - stopWatch.getTime(TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS), 0);
    }
	
	// === DEBUGGING AND LOGGING METHODS ===
	private void print_header() throws IloException {
		logln("SPO Optimizer version 1 (Copyright André Luiz F. dos Santos, Pedro Fiorio Baldotto)");
		logln("Thread count: CPLEX using up to " + model.getNumThreads() + " threads");
		logln("Variable types: 1 continuous; " + (model.y.length + model.p.length) + " integer (" + (model.y.length + model.p.length) + " binaries)");
		logln("");
		
		logln("  h  |  H  |  LB  |  UB  |  Incumbent  |  Status  ");
	}
	
	private void print_line(int h) throws IloException {
		log(String.format("%4" + "s |", h));
		log(String.format("%4" + "s |", MAX_AISLES));
		log(String.format("%5" + "s |", (int) model.getLB()));
		log(String.format("%5" + "s |", (int) model.getUB()));
		
		if(objVal > 0) log(String.format("%12.6f" + " |",  objVal));
		else log(String.format("%12" + "s |", "-"));
		
		logln(String.format("%10" + "s", model.getStatus()));
	}
	
	public boolean isOptimal() {
		return optimal;
	}
}
