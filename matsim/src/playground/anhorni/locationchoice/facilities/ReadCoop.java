package playground.anhorni.locationchoice.facilities;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.TreeMap;

import org.apache.log4j.Logger;
import org.matsim.gbl.Gbl;

public class ReadCoop {
	
	private final static Logger log = Logger.getLogger(ReadCoop.class);
	
	public void completeWithCoop(String file, TreeMap<String, ZHFacilityComposed> zhfacilities) {
		
		try {
			FileReader fileReader = new FileReader(file);
			BufferedReader bufferedReader = new BufferedReader(fileReader);
			String curr_line = bufferedReader.readLine(); // Skip header
						
			while ((curr_line = bufferedReader.readLine()) != null) {
				String[] entries = curr_line.split(";", -1);
				
				String PLZ = entries[10].trim();
				String streetAndNumber = entries[9].trim();			
				String [] addressParts = streetAndNumber.split(" ", -1);
				String lastElement = addressParts[addressParts.length -1];
				
				String street = "";
				if (lastElement.matches("\\d{1,7}") || lastElement.contains("-") || lastElement.contains("/") ||
						lastElement.endsWith("a") || lastElement.endsWith("A") || lastElement.contains("+") || 
						lastElement.endsWith("c") || lastElement.endsWith("c"))  {
					for (int i = 0; i < addressParts.length-1; i++) {
						street +=  addressParts[i].toUpperCase() + " ";
					}
				}
				else {
					street = streetAndNumber.toUpperCase();
					
				}
				String key = PLZ + street;
				
				if (zhfacilities.get(key) != null) {
					zhfacilities.get(key).setShopType(entries[5].trim());
					log.info(key + " attribute added");
				}
			}
		} catch (IOException e) {
			Gbl.errorMsg(e);
		}
		
	}

}
