/* *********************************************************************** *
 * project: org.matsim.*
 * AdaptFacilities.java
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2012 by the members listed in the COPYING,        *
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

package preprocess;

import java.io.BufferedWriter;
import java.io.IOException;

import occupancy.FacilityOccupancy;

import org.apache.log4j.Logger;
import org.matsim.core.api.experimental.facilities.ActivityFacility;
import org.matsim.core.api.experimental.facilities.Facility;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.facilities.ActivityFacilitiesImpl;
import org.matsim.core.facilities.FacilitiesWriter;
import org.matsim.core.facilities.MatsimFacilitiesReader;
import org.matsim.core.gbl.Gbl;
import org.matsim.core.scenario.ScenarioImpl;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.geometry.CoordImpl;
import org.matsim.core.utils.geometry.CoordUtils;
import org.matsim.core.utils.io.IOUtils;

public class AdaptFacilities {

	private static Logger log = Logger.getLogger(AdaptFacilities.class);
	private ScenarioImpl scenario;
	private CoordImpl ZurichCenter = new CoordImpl(683508.5,246832.9063);

	public AdaptFacilities() {
		super();		
	}

	public static void main(String[] args) throws IOException {
		AdaptFacilities adaptFacilities = new AdaptFacilities();
		adaptFacilities.run();
	}

	public void run() {
		scenario = (ScenarioImpl) ScenarioUtils.createScenario(ConfigUtils.createConfig());


		MatsimFacilitiesReader FacReader = new MatsimFacilitiesReader((ScenarioImpl) scenario);  
		System.out.println("Reading facilities xml file... ");
		FacReader.readFile("./input/facilities.xml.gz");
		System.out.println("Reading facilities xml file...done.");
		ActivityFacilitiesImpl facilities = ((ScenarioImpl) scenario).getActivityFacilities();
		log.info("Number of facilities: " +facilities.getFacilities().size());

		//    for (ActivityFacility f : facilities.getFacilitiesForActivityType("shop_retail").values()) {
		//    	double cap = Math.max(10,Math.round((f.getActivityOptions().get("shop_retail").getCapacity())/10));
		//    	f.getActivityOptions().get("shop_retail").setCapacity(cap);
		//    }

		try {

			final String header="Facility_id\tx\ty\tdistance\tunder12";
			final BufferedWriter out =
					IOUtils.getBufferedWriter("./output/distance.txt");

			out.write(header);
			out.newLine();

			for (ActivityFacility facility : facilities.getFacilities().values()) {
				out.write(facility.getId().toString() + "\t"+
						facility.getCoord().getX() + "\t"+
						facility.getCoord().getY()
						+ "\t");
				double distance = CoordUtils.calcDistance(facility.getCoord(), ZurichCenter);
				out.write((int) distance +"\t");
				if (distance < 12000){
					out.write("1\t");
				}
				else {
					out.write("0\t");
				}
				out.newLine();
			}
			out.flush();
			out.close();
		} catch (final IOException e) {
			Gbl.errorMsg(e);
		}
	}
}
