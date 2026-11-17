package org.sbpo2025.challenge;

import org.apache.commons.lang3.time.StopWatch;

import ilog.concert.IloException;
import ilog.concert.IloNumVar;
import ilog.cplex.IloCplex.Status;


public class RefLinFractional extends Approach {

	RefLinModel refLinModel;
	
	public RefLinFractional(Instance inst, StopWatch stopWatch, long time_limit) {
		super(inst, stopWatch, time_limit);
		refLinModel = new RefLinModel(inst);
		refLinModel.build();
	}

	public ChallengeSolution optimize() {
		try {
			refLinModel.setNumThreads(8);
			printHeader();
			inst.loose();
			
			refLinModel.setTimeLimit(getRemainingTime(stopWatch));
			refLinModel.solve();
			objVal = refLinModel.getObjValue();
			solution = refLinModel.saveSolution();
			
			logln("u: " + refLinModel.model.getValue(refLinModel.u));
			
			logln("");
			logln("Solution found: " + objVal);
			logln("Proved optimal? " + (refLinModel.getStatus() == Status.Optimal) + "\n");
			
		} catch(IloException e) {
			e.printStackTrace();
		}
		
		return solution;
	}
	
	public void init(ParallelIterative approach, ChallengeSolution sol) throws IloException {
		logln("Running Reformulation Linearization for aisle range: [" + approach.ascendingLastIt + ", " + approach.decendingLastIt + "]");
		//logln("Running Reformulation Linearization for items range: " + refLinModel.lbConstr + ", " + refLinModel.ubConstr);
		
		//refLinModel.setUB(approach.upperBoundItems);
		//refLinModel.setLB(approach.lowerBoundItems);
		
		refLinModel.setAislesRange(approach.ascendingLastIt, approach.decendingLastIt);	
		refLinModel.setObjUB(approach.upperBoundItems/approach.ascendingLastIt);	
		
		/*IloNumVar[] vars = new IloNumVar[refLinModel.model.getNcols()];
		double[] values = new double[refLinModel.model.getNcols()];
		int j = 1;
		
		vars[0] = refLinModel.u;
		values[0] = 1.0/sol.aisles().size();
		
		for(int o = 0; o < inst.orders.size(); o++) {
			if(refLinModel.p[o] == null)
				continue;
			
			double v;
			if(sol.orders().contains(o))
				v = 1;
			else v = 0;
				
			vars[j] = refLinModel.p[o];
			values[j] = v; j++;
			
			vars[j] = refLinModel.t[o];
			values[j] = v * values[0];
			j++;
		}
		
		for(int a = 0; a < inst.aisles.size(); a++) {
			double v;
			if(sol.orders().contains(a))
				v = 1;
			else v = 0;
			
			vars[j] = refLinModel.y[a];
			values[j] = v; j++;
			
			vars[j] = refLinModel.g[a];
			values[j] = v * values[0];
			j++;
		}*/
		
		//refLinModel.model.addMIPStart(vars, values);
		//values = null;
		//vars = null;
	}
	
	private void printHeader() throws IloException {
		logln("SPO Optimizer version 1 (authors: @andrefeijosantos, @PedroFiorio)");
		logln("Thread count: CPLEX using up to " + refLinModel.getNumThreads() + " threads");
		logln("Variable types: 1 continuous; " + (refLinModel.y.length + refLinModel.p.length) + " binaries");
		logln("");
	}
}
