/* *********************************************************************** *
 * project: org.matsim.*
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2017 by the members listed in the COPYING,        *
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

package org.matsim.contrib.drt.optimizer.insertion;

import java.util.ArrayList;
import java.util.List;

import org.matsim.contrib.drt.data.DrtRequest;
import org.matsim.contrib.drt.optimizer.VehicleData;
import org.matsim.contrib.drt.optimizer.insertion.PathDataProvider.PathDataSet;
import org.matsim.contrib.dvrp.path.OneToManyPathSearch.PathData;

/**
 * @author michalm
 */
public class InsertionGenerator {
	public static class Insertion {
		public final int pickupIdx;
		public final int dropoffIdx;
		public final PathData pathToPickup;
		public final PathData pathFromPickup;
		public final PathData pathToDropoff;// null if dropoff inserted directly after pickup
		public final PathData pathFromDropoff;// null if dropoff inserted at the end

		public Insertion(int pickupIdx, int dropoffIdx, PathData pathToPickup, PathData pathFromPickup,
				PathData pathToDropoff, PathData pathFromDropoff) {
			this.pickupIdx = pickupIdx;
			this.dropoffIdx = dropoffIdx;
			this.pathToPickup = pathToPickup;
			this.pathFromPickup = pathFromPickup;
			this.pathToDropoff = pathToDropoff;
			this.pathFromDropoff = pathFromDropoff;
		}

		@Override
		public String toString() {
			return "Insertion: pickupIdx=" + pickupIdx + ", dropoffIdx=" + dropoffIdx;
		}
	}

	private final PathDataProvider pathDataProvider;

	/// variables changed by findBestInsertion()
	private List<Insertion> insertions;
	private int stopCount;

	// path[0] is a special entry; path[i] corresponds to stop i-1, for 1 <= i <= stopCount
	private PathData[] pathsToPickup;
	private PathData[] pathsFromPickup;
	private PathData[] pathsToDropoff;
	private PathData[] pathsFromDropoff;

	// TODO filter out duplicated insertion when pickup/dropoff is at one of existing stops
	// filter out stops located too far away (e.g. straight-line distance); with the exception for the last stop???
	// filter out pickups at stops with outgoingOccupancy equal to the vehicle capacity
	// filter out dropoffs at stops with incomingOccupancy equal to the vehicle capacity
	// (but still we need to check the capacity constraints on all drives between the pickup and dropoff)
	//
	// TODO maxWaitTime
	// filter out stops which are visited too late
	//
	// private boolean[] considerPickupInsertion;
	// private boolean[] considerDropoffInsertion;

	public InsertionGenerator(PathDataProvider pathDataProvider) {
		this.pathDataProvider = pathDataProvider;
	}

	public List<Insertion> generateInsertions(DrtRequest drtRequest, VehicleData.Entry vEntry) {
		stopCount = vEntry.stops.size();
		initPathData(drtRequest, vEntry);
		findPickupDropoffInsertions(drtRequest, vEntry);
		return insertions;
	}

	private void initPathData(DrtRequest drtRequest, VehicleData.Entry vEntry) {
		PathDataSet set = pathDataProvider.getPathDataSet(drtRequest, vEntry);
		pathsToPickup = set.pathsToPickup;
		pathsFromPickup = set.pathsFromPickup;
		pathsToDropoff = set.pathsToDropoff;
		pathsFromDropoff = set.pathsFromDropoff;
	}

	private void findPickupDropoffInsertions(DrtRequest drtRequest, VehicleData.Entry vEntry) {
		insertions = new ArrayList<>();
		for (int i = 0; i <= stopCount; i++) {
			// pickup is inserted after node i, where
			// node 0 is 'start' (current position/immediate diversion point)
			// node i > 0 is (i-1)th 'stop task'
			// replacing i -> i+1 with i -> pickup -> i+1 means all following stop tasks are affected
			// (==> calc delay for tasks i to n ==> calc cost)

			int occupancy = (i == 0) ? vEntry.startOccupancy : vEntry.stops.get(i - 1).outputOccupancy;
			if (occupancy == vEntry.vehicle.getCapacity()) {
				// (after initPathData() is optimised, it will be also covered by pathsToPickup[i] == null)
				continue;// skip fully loaded arcs
			}
			if (pathsToPickup[i] == null) {
				continue;// skip fully loaded arcs
			}

			if (i < stopCount && // has next stop
					drtRequest.getFromLink() == vEntry.stops.get(i).task.getLink()) {// next stop is at the same link
				// optimize for cases where the pickup is at the same link as stop i (i.e. node i+1)
				// in this case inserting the pickup either before and after the stop is equivalent
				// ==> only evaluate insertion _after_ stop i (node i+1)
				continue;
			}

			iterateDropoffInsertions(drtRequest, vEntry, i);
		}
	}

	private void iterateDropoffInsertions(DrtRequest drtRequest, VehicleData.Entry vEntry, int i) {
		for (int j = i; j <= stopCount; j++) {
			// dropoff is inserted after node j, where
			// node j=i is 'pickup'
			// node j>i is (j-1)th 'stop task'
			// replacing j -> j+1 with j -> dropoff -> j+1 ==> all following stop tasks are affected
			// (==> calc delay for tasks j to n ==> calc cost)

			// no checking the capacity constraints if i == j
			if (j > i && // i -> pickup -> i+1 && j -> dropoff -> j+1
					vEntry.stops.get(j - 1).outputOccupancy == vEntry.vehicle.getCapacity()) {
				return;// stop iterating -- cannot insert dropoff after node j
			}

			if (j < stopCount && // has next stop
					drtRequest.getToLink() == vEntry.stops.get(j).task.getLink()) {// next stop is at the same link
				// optimize for cases where the dropoff is at the same link as stop j-1 (i.e. node j)
				// in this case inserting the dropoff either before and after the stop is equivalent
				// ==> only evaluate insertion _after_ stop j (node j+1)
				continue;
			}

			addInsertion(drtRequest, vEntry, i, j);
		}
	}

	private void addInsertion(DrtRequest drtRequest, VehicleData.Entry vEntry, int i, int j) {
		// i -> pickup
		PathData toPickup = pathsToPickup[i]; // i -> pickup
		PathData fromPickup = pathsFromPickup[i == j ? 0 : i + 1]; // pickup -> (dropoff | i+1)
		PathData toDropoff = i == j ? null // pickup followed by dropoff
				: pathsToDropoff[j]; // j -> dropoff
		PathData fromDropoff = j == stopCount ? null // dropoff inserted at the end
				: pathsFromDropoff[j + 1];

		insertions.add(new Insertion(i, j, toPickup, fromPickup, toDropoff, fromDropoff));
	}
}
