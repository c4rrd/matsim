/* *********************************************************************** *
 * project: org.matsim.*												   *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2008 by the members listed in the COPYING,        *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** */
package playground.vsp.congestion.handlers;

import org.matsim.api.core.v01.population.Activity;
import org.matsim.core.population.ActivityImpl;
import org.matsim.core.scoring.SumScoringFunction;
import org.matsim.core.scoring.functions.CharyparNagelActivityScoring;
import org.matsim.core.scoring.functions.CharyparNagelScoringParameters;
import org.matsim.core.utils.misc.Time;

/**
 * @author ikaddoura
 *
 */
public class MarginalSumScoringFunction {
//	private final static Logger log = Logger.getLogger(MarginalSumScoringFunction.class);
	
	public final double getNormalActivityDelayDisutility(CharyparNagelScoringParameters params, Activity activity, double delay) {
		
		SumScoringFunction delegateA = new SumScoringFunction() ;
		delegateA.addScoringFunction(new CharyparNagelActivityScoring(params));
		
		SumScoringFunction delegateB = new SumScoringFunction() ;
		delegateB.addScoringFunction(new CharyparNagelActivityScoring(params));
			
		if (activity.getStartTime() != Time.UNDEFINED_TIME && activity.getEndTime() != Time.UNDEFINED_TIME) {
        	// activity is not the first and not the last activity
        } else {
        	throw new RuntimeException("Missing start or end time! The provided activity is probably the first or last activity. Aborting...");
        }

		double scoreA0 = delegateA.getScore();
		double scoreB0 = delegateB.getScore();

		Activity activityWithoutDelay = new ActivityImpl(activity);
		activityWithoutDelay.setStartTime(activity.getStartTime() - delay);
		
//		log.info("activity: " + activity.toString());
//		log.info("activityWithoutDelay: " + activityWithoutDelay.toString());
		
		delegateA.handleActivity(activity);
		delegateB.handleActivity(activityWithoutDelay);

		delegateA.finish();
		delegateB.finish();
		
		double scoreA1 = delegateA.getScore();
		double scoreB1 = delegateB.getScore();
		
		double scoreWithDelay = scoreA1 - scoreA0;
		double scoreWithoutDelay = scoreB1 - scoreB0;
		
		double activityDelayDisutility = scoreWithoutDelay - scoreWithDelay;
		return activityDelayDisutility;	
	}
	
	public final double getOvernightActivityDelayDisutility(CharyparNagelScoringParameters params, Activity activityMorning, Activity activityEvening, double delay) {
		
		SumScoringFunction delegateA = new SumScoringFunction() ;
		delegateA.addScoringFunction(new CharyparNagelActivityScoring(params));
		
		SumScoringFunction delegateB = new SumScoringFunction() ;
		delegateB.addScoringFunction(new CharyparNagelActivityScoring(params));
		
        if (activityMorning.getStartTime() == Time.UNDEFINED_TIME && activityMorning.getEndTime() != Time.UNDEFINED_TIME) {
        	// 'morningActivity' is the first activity
        } else {
        	throw new RuntimeException("activityMorning is not the first activity. Or why does it have a start time? Aborting...");
        }
        
        if (activityEvening.getStartTime() != Time.UNDEFINED_TIME && activityEvening.getEndTime() == Time.UNDEFINED_TIME) {
        	// 'eveningActivity' is the last activity
        } else {
        	throw new RuntimeException("activityEvening is not the last activity. Or why does it have an end time? Aborting...");
        }
		        
		double scoreA0 = delegateA.getScore();
		double scoreB0 = delegateB.getScore();
				
		delegateA.handleActivity(activityMorning);
		delegateB.handleActivity(activityMorning);
		
		Activity activityEveningWithoutDelay = new ActivityImpl(activityEvening);
		activityEveningWithoutDelay.setStartTime(activityEvening.getStartTime() - delay);
		
//		log.info("activityMorning: " + activityMorning.toString());
//		log.info("activityEvening: " + activityEvening.toString());
//		log.info("activityEveningWithoutDelay: " + activityEveningWithoutDelay.toString());

		delegateA.handleActivity(activityEvening);
		delegateB.handleActivity(activityEveningWithoutDelay);
		
		delegateA.finish();
		delegateB.finish();
		
		double scoreA1 = delegateA.getScore();
		double scoreB1 = delegateB.getScore();
		
		double scoreWithDelay = scoreA1 - scoreA0;
		double scoreWithoutDelay = scoreB1 - scoreB0;
		
		double activityDelayDisutility = scoreWithoutDelay - scoreWithDelay;
		return activityDelayDisutility;
	}
	
}