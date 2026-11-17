package org.sbpo2025.challenge;

import java.util.List;
import java.util.Map;

import ilog.concert.IloConstraint;
import ilog.concert.IloException;
import ilog.concert.IloIntVar;
import ilog.concert.IloLinearIntExpr;
import ilog.concert.IloLinearNumExpr;
import ilog.concert.IloNumVar;
import ilog.cplex.IloCplex;
import ilog.cplex.IloCplex.Status;

public class BasicModel {
	
	// Test instance.
	Instance inst;
	
	// CPLEX model.
	IloCplex model;
	
	// Model constants.
	List<Map<Integer, Integer>> q, w;
	
	// Model variables.
	public IloLinearNumExpr objective;
	public IloNumVar[] p;
	
	IloConstraint lbConstr = null;
	IloConstraint ubConstr = null;
	
	
	public BasicModel(Instance inst) {
		this.inst = inst;
	}
	
	public void build() {
		// Building CPLEX model.
		try {			
			model = new IloCplex();
			
			buildConsts();
			buildConstsSpecific();
			buildVars();
			buildVarsSpecific();
			buildConstrs();
			buildConstrsSpecific();
			buildObjective();	
			
			buildSpecific();
			
		} catch(IloException e) {
			System.out.print("No model built. Error: ");
			e.printStackTrace();
		}
	}
	
	protected void buildConsts() throws IloException {	
		q = inst.aisles;
		w = inst.orders;
	}
	
	protected void buildConstsSpecific() throws IloException {
		return;
	}
	
	protected void buildVars() {
		try {	
			// 1, if o-ith orders was built; 0, otherwise.
			p = new IloNumVar[inst.orders.size()];
	        for (int o = 0; o < inst.orders.size(); o++) {	        	
	        	// If it's impossible to build o, don't create its p.
	        	if(inst.wontBuild.get(o)) {
	        		p[o] = null;
	        		continue;
	        	}
	        	
	        	//p[o] = model.boolVar("p_" + o);
	        	//if(true) continue;
	        	
	        	int firstItem = inst.orders.get(o).keySet().iterator().next();
	        	if(inst.orders.get(o).keySet().size() == 1 && inst.orders.get(o).get(firstItem) == 1)
	        		p[o] = model.numVar(0, 1, "p_" + o);
	        	else
	        		p[o] = model.boolVar("p_" + o);
	        }
	        
		} catch(IloException e) {
			e.printStackTrace();
		}
	}
	
	protected void buildVarsSpecific() throws IloException {
		return;
	}
	
	protected void buildObjective() {
		try {
			model.addMaximize(objective);
			
		} catch(IloException e) {
			e.printStackTrace();
		}
	}
	
	protected void buildConstrs() throws IloException {
		objective = model.linearNumExpr();
		int totalItems = 0;
		for(int o = 0; o < inst.orders.size(); o++) {
			for(int i : inst.orders.get(o).keySet())
				totalItems += w.get(o).get(i);
			if(p[o] != null) objective.addTerm(totalItems, p[o]); 
			totalItems = 0;
		}
		
		lbConstr = model.addLe(inst.LB,  objective);
		ubConstr = model.addLe(objective, inst.UB);
	}
	
	protected void buildConstrsSpecific() throws IloException {
		return;
	}
	
	protected void buildSpecific() throws IloException {
		return;
	}
	
	public void solve() throws IloException {
		model.solve();
	}
	
	public Status getStatus() throws IloException {
		return model.getStatus();
	}
	
	public double getObjValue() throws IloException {
		return model.getObjValue();
	}
	
	public void setTimeLimit(long timeLimit) throws IloException {
		model.setParam(IloCplex.Param.TimeLimit, timeLimit);
	}
	
	public double getTimeLimit() throws IloException {
		return model.getParam(IloCplex.Param.TimeLimit);
	}
	
	public double getValue(IloIntVar var) throws IloException {
		return model.getValue(var);
	}
	
	public double getValue(IloNumVar var) throws IloException {
		return model.getValue(var);
	}
	
	public double getValue(IloLinearIntExpr expr) throws IloException {
		return model.getValue(expr);
	}
	
	public double getValue(IloLinearNumExpr expr) throws IloException {
		return model.getValue(expr);
	}
	
	public int getNumThreads() throws IloException {
		return model.getParam(IloCplex.Param.Threads);
	}
	
	public void setNumThreads(int numThreads) throws IloException {
		model.setParam(IloCplex.Param.Threads, numThreads);
	}
	
	public ChallengeSolution saveSolution() throws IloException {
		return null;
	}
}
