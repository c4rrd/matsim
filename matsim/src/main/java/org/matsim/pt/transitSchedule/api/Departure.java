/* *********************************************************************** *
 * project: org.matsim.*
 * Departure.java
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2009 by the members listed in the COPYING,        *
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

package org.matsim.pt.transitSchedule.api;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Identifiable;
import org.matsim.vehicles.Vehicle;

/**
 * Describes a single departure along a route in a transit line.
 *
 * @author mrieser
 */
public interface Departure extends Identifiable<Departure> {

	public abstract double getDepartureTime();

	/**
	 * @param vehicleId the id of the vehicle to be used for this departure, may be <code>null</code>
	 */
	public abstract void setVehicleId(final Id<Vehicle> vehicleId);

	/**
	 * @return The id of the vehicle to be used for this departure, may be <code>null</code>
	 */
	public abstract Id<Vehicle> getVehicleId();

}