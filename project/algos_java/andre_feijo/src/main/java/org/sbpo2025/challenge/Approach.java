package org.sbpo2025.challenge;

import java.util.concurrent.TimeUnit;
import org.apache.commons.lang3.time.StopWatch;


public class Approach {

	// Model instance.
	Instance inst;
	
	// Objective Value.
	double objVal;
	
	// Useful data for running the solution.
	long MAX_RUNTIME;
	ChallengeSolution solution = null;
	StopWatch stopWatch;
	
	public Approach(Instance inst, StopWatch stopWatch, long time_limit) {
		this.inst = inst;
		this.stopWatch = stopWatch;
		this.MAX_RUNTIME = time_limit;
		this.objVal = ((double)inst.LB)/inst.aisles.size();
	}
	

	// === AUXILIAR FUNCTIONS ===	
	protected long getRemainingTime(StopWatch stopWatch) {
        return Math.max(TimeUnit.SECONDS.convert(MAX_RUNTIME - stopWatch.getTime(TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS), 0);
    }
	
	
	// === DEBUGGING AND LOGGING METHODS ===
	protected void log(String text) {
		System.out.print(text);
	}
	
	protected void logln(String text) {
		System.out.println(text);
	}
}
