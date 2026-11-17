package org.sbpo2025.challenge;

import org.apache.commons.lang3.time.StopWatch;

import ilog.concert.IloException;
import ilog.cplex.IloCplex.Status;

public class ParametricFractional extends Approach {

	ParametricModel paramModel;
	
	final double TOL = 1e-6;
	
	public ParametricFractional(Instance inst, StopWatch stopWatch, long timeLimit) {
		super(inst, stopWatch, timeLimit);
		paramModel = new ParametricModel(inst);
		paramModel.build();
	}

	
	public ChallengeSolution optimize() {
		try {
			paramModel.setNumThreads(8);
			printHeader();
			inst.loose();
			
			double rAst = 0;
			double value;
			int it = 0;
			
			while(getRemainingTime(stopWatch) > 5) {	
				paramModel.setTimeLimit(getRemainingTime(stopWatch));
				paramModel.setRatio(rAst);
				paramModel.solve();
				
				objVal = paramModel.getObjValue(); it++;
				printLine(it, rAst, (int) paramModel.getValue(paramModel.sumP), (int) paramModel.getValue(paramModel.sumY));
				value = paramModel.getObjValue();
				
				if(Math.abs(value) < TOL)
					break;
				else {
					rAst = paramModel.getValue(paramModel.sumP)/paramModel.getValue(paramModel.sumY);
					solution = paramModel.saveSolution();
					objVal = Math.max(objVal, rAst);
				}
			}
			
			logln("");
			logln("Solution found: " + rAst);
			logln("Total its: " + it);
			logln("Proved optimal? " + (paramModel.getStatus() == Status.Optimal) + "\n");
			
		} catch(IloException e) {
			e.printStackTrace();
		}
		
		return solution;
	}
	
	// === DEBUGGING AND LOGGING METHODS ===
	private void printHeader() throws IloException {
		logln("SPO Optimizer version 1 (authors: @andrefeijosantos, @PedroFiorio)");
		logln("Thread count: CPLEX using up to " + paramModel.getNumThreads() + " threads");
		logln("Variable types: 1 continuous; " + (paramModel.y.length + paramModel.p.length) + " binaries");
		logln("Time Limit: time limit set to " + MAX_RUNTIME/1000 + " seconds");
		logln("Considering tolerance: " + TOL);
		logln("");
		
		logln("  it  |    q*    |  N  |  D  |   f(q*)  ");
	}
	
	private void printLine(int it, double rAst, int N, int D) throws IloException {
		log(String.format("%5" + "s |", it));
		log(String.format("%9.5f" + " |", rAst));
		log(String.format("%4" + "s |", (int) N));
		log(String.format("%4" + "s |", (int) D));
		logln(String.format("%10.6f" + "", objVal));
	}
}
