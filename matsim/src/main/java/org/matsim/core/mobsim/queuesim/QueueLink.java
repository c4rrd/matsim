/* *********************************************************************** *
 * project: org.matsim.*
 * QueueLink.java
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2007 by the members listed in the COPYING,        *
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

package org.matsim.core.mobsim.queuesim;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;

import org.apache.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.core.events.AgentStuckEventImpl;
import org.matsim.core.events.AgentWait2LinkEventImpl;
import org.matsim.core.events.LinkEnterEventImpl;
import org.matsim.core.events.LinkLeaveEventImpl;
import org.matsim.core.gbl.Gbl;
import org.matsim.core.mobsim.framework.PersonDriverAgent;
import org.matsim.core.network.LinkImpl;
import org.matsim.core.network.NetworkImpl;
import org.matsim.core.utils.misc.NetworkUtils;
import org.matsim.core.utils.misc.Time;
import org.matsim.vis.otfvis.handler.OTFDefaultLinkHandler;
import org.matsim.vis.snapshots.writers.AgentSnapshotInfo;
import org.matsim.vis.snapshots.writers.PositionInfo;

/**
 * @author dstrippgen
 * @author dgrether
 * @author mrieser
 */
public class QueueLink {

  final private static Logger log = Logger.getLogger(QueueLink.class);

  private static int spaceCapWarningCount = 0;

  /**
   * All vehicles from parkingList move to the waitingList as soon as their time
   * has come. They are then filled into the vehQueue, depending on free space
   * in the vehQueue
   */
  /*package*/ final Queue<QueueVehicle> waitingList = new LinkedList<QueueVehicle>();
  /**
   * The Link instance containing the data
   */
  private final Link link;
  /**
   * Reference to the QueueNetwork instance this link belongs to.
   */
  private final QueueNetwork queueNetwork;
  /**
   * Reference to the QueueNode which is at the end of each QueueLink instance
   */
  private final QueueNode toQueueNode;

  private boolean active = false;

  private final Map<Id, QueueVehicle> parkedVehicles = new LinkedHashMap<Id, QueueVehicle>(10);

  /*package*/ VisData visdata = this.new VisDataImpl();

  private QueueSimEngine simEngine = null;

  private double length = Double.NaN;

  private double freespeedTravelTime = Double.NaN;

   /** the last timestep the front-most vehicle in the buffer was moved. Used for detecting dead-locks. */
  /*package*/ double bufferLastMovedTime = Time.UNDEFINED_TIME;
   /**
   * The list of vehicles that have not yet reached the end of the link
   * according to the free travel speed of the link
   */
  /*package*/ private final LinkedList<QueueVehicle> vehQueue = new LinkedList<QueueVehicle>();

  /**
   * Holds all vehicles that are ready to cross the outgoing intersection
   */
  /*package*/ final Queue<QueueVehicle> buffer = new LinkedList<QueueVehicle>();

  private double storageCapacity;

  private double usedStorageCapacity;

  /**
   * The number of vehicles able to leave the buffer in one time step (usually 1s).
   */
  private double simulatedFlowCapacity; // previously called timeCap

  /*package*/ double inverseSimulatedFlowCapacity; // optimization, cache 1.0 / simulatedFlowCapacity

  private int bufferStorageCapacity; // optimization, cache Math.ceil(simulatedFlowCap)

  private double flowCapFraction; // optimization, cache simulatedFlowCap - (int)simulatedFlowCap

  /**
   * The (flow) capacity available in one time step to move vehicles into the
   * buffer. This value is updated each time step by a call to
   * {@link #updateBufferCapacity(double)}.
   */
  private double bufferCap = 0.0;

  /**
   * Stores the accumulated fractional parts of the flow capacity. See also
   * flowCapFraction.
   */
  private double buffercap_accumulate = 1.0;

  /**
   * Initializes a QueueLink with one QueueLane.
   * @param link2
   * @param queueNetwork
   * @param toNode
   * @see QueueLink#createLanes(java.util.List)
   */
  public QueueLink(final Link link2, final QueueNetwork queueNetwork, final QueueNode toNode) {
    this.link = link2;
    this.queueNetwork = queueNetwork;
    this.toQueueNode = toNode;
    this.length = this.getLink().getLength();
    this.freespeedTravelTime = this.length / this.getLink().getFreespeed();
    this.calculateCapacities();
  }

  /** Is called after link has been read completely */
  public void finishInit() {
    this.active = false;
  }

  public void setSimEngine(final QueueSimEngine simEngine) {
    this.simEngine = simEngine;
  }

  public void activateLink() {
    if (!this.active) {
      this.simEngine.activateLink(this);
      this.active = true;
    }
  }

  /**
   * Adds a vehicle to the link, called by
   * {@link QueueNode#moveVehicleOverNode(QueueVehicle, QueueLink, double)}.
   *
   * @param veh
   *          the vehicle
   */
  public void add(final QueueVehicle veh) {
    double now = SimulationTimer.getTime();
    activateLink();
    this.add(veh, now);
    veh.setCurrentLink(this.getLink());
    QueueSimulation.getEvents().processEvent(
        new LinkEnterEventImpl(now, veh.getDriver().getPerson().getId(),
            this.getLink().getId()));
  }

   /**
   * Adds a vehicle to the lane.
   *
   * @param veh
   * @param now the current time
   */
  /*package*/ void add(final QueueVehicle veh, final double now) {
    this.vehQueue.add(veh);
    this.usedStorageCapacity += veh.getSizeInEquivalents();
    double departureTime;
      /* It's not the original lane,
       * so there is a fractional rest we add to this link's freeSpeedTravelTime */
      departureTime = now + this.freespeedTravelTime
      + veh.getEarliestLinkExitTime() - Math.floor(veh.getEarliestLinkExitTime());
      /* It's a QueueLane that is directly connected to a QueueNode,
       * so we have to floor the freeLinkTravelTime in order the get the same
       * results compared to the old mobSim */
    departureTime = Math.floor(departureTime);
    veh.setLinkEnterTime(now);
    veh.setEarliestLinkExitTime(departureTime);
  }

  public void clearVehicles() {
    this.parkedVehicles.clear();
    double now = SimulationTimer.getTime();

    for (QueueVehicle veh : this.waitingList) {
      QueueSimulation.getEvents().processEvent(
          new AgentStuckEventImpl(now, veh.getDriver().getPerson().getId(), veh.getCurrentLink().getId(), veh.getDriver().getCurrentLeg().getMode()));
    }
    AbstractSimulation.decLiving(this.waitingList.size());
    AbstractSimulation.incLost(this.waitingList.size());
    this.waitingList.clear();

    for (QueueVehicle veh : this.vehQueue) {
      QueueSimulation.getEvents().processEvent(
          new AgentStuckEventImpl(now, veh.getDriver().getPerson().getId(), veh.getCurrentLink().getId(), veh.getDriver().getCurrentLeg().getMode()));
    }
    AbstractSimulation.decLiving(this.vehQueue.size());
    AbstractSimulation.incLost(this.vehQueue.size());
    this.vehQueue.clear();

    for (QueueVehicle veh : this.buffer) {
      QueueSimulation.getEvents().processEvent(
          new AgentStuckEventImpl(now, veh.getDriver().getPerson().getId(), veh.getCurrentLink().getId(), veh.getDriver().getCurrentLeg().getMode()));
    }
    AbstractSimulation.decLiving(this.buffer.size());
    AbstractSimulation.incLost(this.buffer.size());
    this.buffer.clear();
  }

  public void addParkedVehicle(QueueVehicle vehicle) {
    this.parkedVehicles.put(vehicle.getId(), vehicle);
    vehicle.setCurrentLink(this.link);
  }

  /*package*/ QueueVehicle getParkedVehicle(Id vehicleId) {
    return this.parkedVehicles.get(vehicleId);
  }

  public QueueVehicle removeParkedVehicle(Id vehicleId) {
    return this.parkedVehicles.remove(vehicleId);
  }

  public void addDepartingVehicle(QueueVehicle vehicle) {
    this.waitingList.add(vehicle);
    this.activateLink();
  }

  public boolean moveLink(double now) {
    boolean ret = false;
    ret = this.moveLane(now);
    this.active = ret;
    return ret;
  }

   /** called from framework, do everything related to link movement here
  *
  * @param now current time step
  * @return
  */
 protected boolean moveLane(final double now) {
   updateBufferCapacity();

   // move vehicles from lane to buffer.  Includes possible vehicle arrival.  Which, I think, would only be triggered
   // if this is the original lane.
   moveLaneToBuffer(now);
   // move vehicles from waitingQueue into buffer if possible
   moveWaitToBuffer(now);
   return this.isActive();
 }

 private void updateBufferCapacity() {
   this.bufferCap = this.simulatedFlowCapacity;
   if (this.buffercap_accumulate < 1.0) {
     this.buffercap_accumulate += this.flowCapFraction;
   }
 }

 /**
  * Move vehicles from link to buffer, according to buffer capacity and
  * departure time of vehicle. Also removes vehicles from lane if the vehicle
  * arrived at its destination.
  *
  * @param now
  *          The current time.
  */
 protected void moveLaneToBuffer(final double now) {
   QueueVehicle veh;

   // handle regular traffic
   while ((veh = this.vehQueue.peek()) != null) {
     if (veh.getEarliestLinkExitTime() > now){
       return;
     }
     PersonDriverAgent driver = veh.getDriver();
       // Check if veh has reached destination:
       if ((this.getLink().getId().equals(driver.getDestinationLinkId())) && (driver.chooseNextLinkId() == null)) {
         driver.legEnds(now);
         this.processVehicleArrival(now, veh);
         // remove _after_ processing the arrival to keep link active
         this.vehQueue.poll();
         this.usedStorageCapacity -= veh.getSizeInEquivalents();
         continue;
       }

       /* is there still room left in the buffer, or is it overcrowded from the
        * last time steps? */
       if (!hasBufferSpace()) {
         return;
       }

       addToBuffer(veh, now);
       this.vehQueue.poll();
       this.usedStorageCapacity -= veh.getSizeInEquivalents();
   } // end while
 }

  /**
  * Move as many waiting cars to the link as it is possible
  *
  * @param now
  *          the current time
  */
 private void moveWaitToBuffer(final double now) {
   while (hasBufferSpace()) {
     QueueVehicle veh = this.waitingList.poll();
     if (veh == null) {
       return;
     }

     QueueSimulation.getEvents().processEvent(
     new AgentWait2LinkEventImpl(now, veh.getDriver().getPerson().getId(), this.getLink().getId(), veh.getDriver().getCurrentLeg().getMode()));
     addToBuffer(veh, now);
   }
 }
  public void processVehicleArrival(final double now, final QueueVehicle veh) {
//    QueueSimulation.getEvents().processEvent(
//        new AgentArrivalEventImpl(now, veh.getDriver().getPerson(),
//            this.getLink(), veh.getDriver().getCurrentLeg()));
    // Need to inform the veh that it now reached its destination.
//    veh.getDriver().legEnds(now);
    addParkedVehicle(veh);
  }


  public boolean bufferIsEmpty() {
    return this.buffer.isEmpty();
  }

  public boolean hasSpace() {
    return this.usedStorageCapacity < getStorageCapacity();
  }

  public void recalcTimeVariantAttributes(double now) {
    this.freespeedTravelTime = this.length / this.getLink().getFreespeed(now);
    calculateFlowCapacity(now);
    calculateStorageCapacity(now);
  }

   void calculateCapacities() {
      calculateFlowCapacity(Time.UNDEFINED_TIME);
      calculateStorageCapacity(Time.UNDEFINED_TIME);
      this.buffercap_accumulate = (this.flowCapFraction == 0.0 ? 0.0 : 1.0);
    }

    private void calculateFlowCapacity(final double time) {
      this.simulatedFlowCapacity = ((LinkImpl)this.getLink()).getFlowCapacity(time);
      // we need the flow capcity per sim-tick and multiplied with flowCapFactor
      this.simulatedFlowCapacity = this.simulatedFlowCapacity * SimulationTimer.getSimTickTime() * Gbl.getConfig().simulation().getFlowCapFactor();
      this.inverseSimulatedFlowCapacity = 1.0 / this.simulatedFlowCapacity;
      this.flowCapFraction = this.simulatedFlowCapacity - (int) this.simulatedFlowCapacity;
    }

    private void calculateStorageCapacity(final double time) {
      double storageCapFactor = Gbl.getConfig().simulation().getStorageCapFactor();
      this.bufferStorageCapacity = (int) Math.ceil(this.simulatedFlowCapacity);

      double numberOfLanes = this.getLink().getNumberOfLanes(time);
      // first guess at storageCapacity:
      this.storageCapacity = (this.length * numberOfLanes)
          / ((NetworkImpl) this.getQueueNetwork().getNetworkLayer()).getEffectiveCellSize() * storageCapFactor;

      // storage capacity needs to be at least enough to handle the cap_per_time_step:
      this.storageCapacity = Math.max(this.storageCapacity, this.bufferStorageCapacity);

      /*
       * If speed on link is relatively slow, then we need MORE cells than the
       * above spaceCap to handle the flowCap. Example: Assume freeSpeedTravelTime
       * (aka freeTravelDuration) is 2 seconds. Than I need the spaceCap TWO times
       * the flowCap to handle the flowCap.
       */
      double tempStorageCapacity = this.freespeedTravelTime * this.simulatedFlowCapacity;
      if (this.storageCapacity < tempStorageCapacity) {
        if (spaceCapWarningCount <= 10) {
          log.warn("Link " + this.getLink().getId() + " too small: enlarge storage capcity from: " + this.storageCapacity + " Vehicles to: " + tempStorageCapacity + " Vehicles.  This is not fatal, but modifies the traffic flow dynamics.");
          if (spaceCapWarningCount == 10) {
              log.warn("Additional warnings of this type are suppressed.");
          }
          spaceCapWarningCount++;
        }
        this.storageCapacity = tempStorageCapacity;
      }
    }


  public QueueVehicle getVehicle(Id vehicleId) {
    QueueVehicle ret = getParkedVehicle(vehicleId);
    if (ret != null) {
      return ret;
    }
    for (QueueVehicle veh : this.vehQueue) {
      if (veh.getId().equals(vehicleId))
        return veh;
    }
    for (QueueVehicle veh : this.buffer) {
      if (veh.getId().equals(vehicleId))
        return veh;
    }
    for (QueueVehicle veh : this.waitingList) {
      if (veh.getId().equals(vehicleId))
        return veh;
    }
    return null;
  }

  public Collection<QueueVehicle> getAllVehicles() {

    Collection<QueueVehicle> vehicles = this.getAllNonParkedVehicles();
    vehicles.addAll(this.parkedVehicles.values());
//      new ArrayList<QueueVehicle>(this.parkedVehicles.values());
//    vehicles.addAll(transitQueueLaneFeature.getFeatureVehicles());
//    vehicles.addAll(this.waitingList);
//    vehicles.addAll(this.vehQueue);
//    vehicles.addAll(this.buffer);
    return vehicles;
  }

  public Collection<QueueVehicle> getAllNonParkedVehicles(){
    Collection<QueueVehicle> vehicles = new ArrayList<QueueVehicle>();
    vehicles.addAll(this.waitingList);
    vehicles.addAll(this.vehQueue);
    vehicles.addAll(this.buffer);
    return vehicles;
  }

   /**
   * @return Returns the maximum number of vehicles that can be placed on the
   *         link at a time.
   */
  /*package*/ double getStorageCapacity() {
    return this.storageCapacity;
  }

  /**
   * @return the total space capacity available on that link (includes the space on lanes if available)
   */
  public double getSpaceCap() {
    return this.storageCapacity;
  }

//  public Queue<QueueVehicle> getVehiclesInBuffer() {
//    return this.originalLane.getVehiclesInBuffer();
//  }

  /**
   * One should think about the need for this method
   * because it is only called by one testcase
   * @return
   */
  protected int vehOnLinkCount() {
    return this.vehQueue.size();
  }


  public Link getLink() {
    return this.link;
  }

  public QueueNetwork getQueueNetwork() {
    return this.queueNetwork;
  }

  public QueueNode getToQueueNode() {
    return this.toQueueNode;
  }

  /**
   * This method returns the normalized capacity of the link, i.e. the capacity
   * of vehicles per second. It is considering the capacity reduction factors
   * set in the config and the simulation's tick time.
   *
   * @return the flow capacity of this link per second, scaled by the config
   *         values and in relation to the SimulationTimer's simticktime.
   */
  public double getSimulatedFlowCapacity() {
    return this.simulatedFlowCapacity;
  }

  public VisData getVisData() {
    return this.visdata;
  }

   private boolean isActive() {
      /*
       * Leave Lane active as long as there are vehicles on the link (ignore
       * buffer because the buffer gets emptied by nodes and not links) and leave
       * link active until buffercap has accumulated (so a newly arriving vehicle
       * is not delayed).
       */
      boolean active = (this.buffercap_accumulate < 1.0) || (!this.vehQueue.isEmpty()) || (!this.waitingList.isEmpty());
      return active;
    }

    public LinkedList<QueueVehicle> getVehQueue() {
      return this.vehQueue;
    }

    /**
     * @return <code>true</code> if there are less vehicles in buffer than the flowCapacity's ceil
     */
    private boolean hasBufferSpace() {
      return ((this.buffer.size() < this.bufferStorageCapacity) && ((this.bufferCap >= 1.0)
          || (this.buffercap_accumulate >= 1.0)));
    }

    private void addToBuffer(final QueueVehicle veh, final double now) {
      if (this.bufferCap >= 1.0) {
        this.bufferCap--;
      }
      else if (this.buffercap_accumulate >= 1.0) {
        this.buffercap_accumulate--;
      }
      else {
        throw new IllegalStateException("Buffer of link " + this.getLink().getId() + " has no space left!");
      }
      this.buffer.add(veh);
      if (this.buffer.size() == 1) {
        this.bufferLastMovedTime = now;
      }
      this.getToQueueNode().activateNode();
    }
    /*package*/ QueueVehicle popFirstFromBuffer() {
      double now = SimulationTimer.getTime();
      QueueVehicle veh = this.buffer.poll();
      this.bufferLastMovedTime = now; // just in case there is another vehicle in the buffer that is now the new front-most
      QueueSimulation.getEvents().processEvent(new LinkLeaveEventImpl(now, veh.getDriver().getPerson().getId(), this.getLink().getId()));
      return veh;
    }
    QueueVehicle getFirstFromBuffer() {
      return this.buffer.peek();
    }
  /**
   * Inner class to capsulate visualization methods
   * @author dgrether
   *
   */
  class VisDataImpl implements VisData {

    private final double linkScale =  OTFDefaultLinkHandler.LINK_SCALE;

//    /**
//     * @return The value for coloring the link in NetVis. Actual: veh count / space capacity
//     */
//    public double getDisplayableSpaceCapValue() {
//      return (QueueLink.this.buffer.size() + QueueLink.this.vehQueue.size()) / QueueLink.this.storageCapacity;
//    }

//    /**
//     * Returns a measure for how many vehicles on the link have a travel time
//     * higher than freespeedTraveltime on a scale from 0 to 2. When more then half
//     * of the possible vehicles are delayed, the value 1 will be returned, which
//     * depicts the worst case on a (traditional) scale from 0 to 1.
//     *
//     * @return A measure for the number of vehicles being delayed on this link.
//     */
//    public double getDisplayableTimeCapValue(double now) {
//      int count = QueueLink.this.buffer.size();
//      for (QueueVehicle veh : QueueLink.this.vehQueue) {
//        // Check if veh has reached destination
//        if (veh.getEarliestLinkExitTime() <= now) {
//          count++;
//        }
//      }
//      return count * 2.0 / QueueLink.this.storageCapacity;
//    }



//    public Collection<AgentOnLink> getDrawableCollection() {
//      Collection<AgentSnapshotInfo> positions = new ArrayList<AgentSnapshotInfo>();
//      getVehiclePositions(positions);
//
//      List<AgentOnLink> vehs = new ArrayList<AgentOnLink>();
//      for (AgentSnapshotInfo pos : positions) {
//        if (pos.getAgentState() == AgentSnapshotInfo.AgentState.PERSON_DRIVING_CAR) {
//          AgentOnLink veh = new AgentOnLink();
//          veh.posInLink_m = pos.getDistanceOnLink();
//          vehs.add(veh);
//        }
//      }
//
//      return vehs;
//    }

    public Collection<AgentSnapshotInfo> getVehiclePositions(
        final Collection<AgentSnapshotInfo> positions) {
      String snapshotStyle = Gbl.getConfig().simulation().getSnapshotStyle();
      if ("queue".equals(snapshotStyle)) {
        getVehiclePositionsQueue(positions);
      } else if ("equiDist".equals(snapshotStyle)) {
        getVehiclePositionsEquil(positions);
      } else {
        log.warn("The snapshotStyle \"" + snapshotStyle + "\" is not supported.");
      }
      return positions;
    }

    /**
     * Calculates the positions of all vehicles on this link so that there is
     * always the same distance between following cars. A single vehicle will be
     * placed at the middle (0.5) of the link, two cars will be placed at
     * positions 0.25 and 0.75, three cars at positions 0.16, 0.50, 0.83, and so
     * on.
     *
     * @param positions
     *          A collection where the calculated positions can be stored.
     */
    private void getVehiclePositionsEquil(final Collection<AgentSnapshotInfo> positions) {
      double time = SimulationTimer.getTime();
      int cnt = QueueLink.this.buffer.size() + QueueLink.this.vehQueue.size();
      int nLanes = NetworkUtils.getNumberOfLanesAsInt(Time.UNDEFINED_TIME, QueueLink.this.getLink());
      if (cnt > 0) {
        double cellSize = QueueLink.this.getLink().getLength() / cnt;
        double distFromFromNode = QueueLink.this.getLink().getLength() - cellSize / 2.0;
        double freespeed = QueueLink.this.getLink().getFreespeed();

        // the cars in the buffer
        for (QueueVehicle veh : QueueLink.this.buffer) {
          int lane = 1 + (veh.getId().hashCode() % nLanes);
          int cmp = (int) (veh.getEarliestLinkExitTime() + QueueLink.this.inverseSimulatedFlowCapacity + 2.0);
          double speed = (time > cmp ? 0.0 : freespeed);
          AgentSnapshotInfo position = new PositionInfo(veh.getDriver().getPerson().getId(), QueueLink.this.getLink(),
              distFromFromNode, lane, speed, AgentSnapshotInfo.AgentState.PERSON_DRIVING_CAR);
          positions.add(position);
          distFromFromNode -= cellSize;
        }

        // the cars in the drivingQueue
        for (QueueVehicle veh : QueueLink.this.vehQueue) {
          int lane = 1 + (veh.getId().hashCode() % nLanes);
          int cmp = (int) (veh.getEarliestLinkExitTime() + QueueLink.this.inverseSimulatedFlowCapacity + 2.0);
          double speed = (time > cmp ? 0.0 : freespeed);
          AgentSnapshotInfo position = new PositionInfo(veh.getDriver().getPerson().getId(), QueueLink.this.getLink(),
              distFromFromNode, lane, speed, AgentSnapshotInfo.AgentState.PERSON_DRIVING_CAR);
          positions.add(position);
          distFromFromNode -= cellSize;
        }
      }

      // the cars in the waitingQueue
      // the actual position doesn't matter, so they're just placed next to the
      // link at the end
      cnt = QueueLink.this.waitingList.size();
      if (cnt > 0) {
        int lane = nLanes + 2;
        double cellSize = Math.min(7.5, QueueLink.this.getLink().getLength() / cnt);
        double distFromFromNode = QueueLink.this.getLink().getLength() - cellSize / 2.0;
        for (QueueVehicle veh : QueueLink.this.waitingList) {
        	AgentSnapshotInfo position = new PositionInfo(veh.getDriver().getPerson().getId(), QueueLink.this.getLink(),
              distFromFromNode, lane, 0.0, AgentSnapshotInfo.AgentState.PERSON_AT_ACTIVITY);
          positions.add(position);
          distFromFromNode -= cellSize;
        }
      }

    }

    /**
     * Calculates the positions of all vehicles on this link according to the
     * queue-logic: Vehicles are placed on the link according to the ratio between
     * the free-travel time and the time the vehicles are already on the link. If
     * they could have left the link already (based on the time), the vehicles
     * start to build a traffic-jam (queue) at the end of the link.
     *
     * @param positions
     *          A collection where the calculated positions can be stored.
     */
    private void getVehiclePositionsQueue(final Collection<AgentSnapshotInfo> positions) {
      double now = SimulationTimer.getTime();
      Link link = QueueLink.this.getLink();
      double queueEnd = getInitialQueueEnd();
      double storageCapFactor = Gbl.getConfig().simulation().getStorageCapFactor();
      double cellSize = ((NetworkImpl)QueueLink.this.getQueueNetwork().getNetworkLayer()).getEffectiveCellSize();
      double vehLen = calculateVehicleLength(link, storageCapFactor, cellSize);

      queueEnd = positionVehiclesFromBuffer(positions, now, queueEnd, link, vehLen);
      positionOtherDrivingVehicles(positions, now, queueEnd, link, vehLen);

      positionVehiclesFromWaitingList(positions, link, cellSize);
    }

    private double calculateVehicleLength(Link link,
        double storageCapFactor, double cellSize) {
      double vehLen = Math.min( // the length of a vehicle in visualization
          link.getLength() / (QueueLink.this.storageCapacity + QueueLink.this.bufferStorageCapacity), // all vehicles must have place on the link
          cellSize / storageCapFactor); // a vehicle should not be larger than it's actual size
      return vehLen;
    }

    private double getInitialQueueEnd() {
      double queueEnd = QueueLink.this.getLink().getLength(); // the position of the start of the queue jammed vehicles build at the end of the link
      return queueEnd;
    }

    /**
     *  put all cars in the buffer one after the other
     */
    private double positionVehiclesFromBuffer(
        final Collection<AgentSnapshotInfo> positions, double now,
        double queueEnd, Link link, double vehLen) {
      for (QueueVehicle veh : QueueLink.this.buffer) {

        int lane = 1 + (veh.getId().hashCode() % NetworkUtils.getNumberOfLanesAsInt(Time.UNDEFINED_TIME, QueueLink.this.getLink()));

        int cmp = (int) (veh.getEarliestLinkExitTime() + QueueLink.this.inverseSimulatedFlowCapacity + 2.0);
        double speed = (now > cmp) ? 0.0 : link.getFreespeed(now);

        AgentSnapshotInfo position = new PositionInfo(this.linkScale, veh.getDriver().getPerson().getId(), link, queueEnd,
            lane, speed, AgentSnapshotInfo.AgentState.PERSON_DRIVING_CAR);
        positions.add(position);
        queueEnd -= vehLen;
      }
      return queueEnd;
    }

    /**
     * place other driving cars according the following rule:
     * - calculate the time how long the vehicle is on the link already
     * - calculate the position where the vehicle should be if it could drive with freespeed
     * - if the position is already within the congestion queue, add it to the queue with slow speed
     * - if the position is not within the queue, just place the car  with free speed at that place
     */
    private void positionOtherDrivingVehicles(
        final Collection<AgentSnapshotInfo> positions, double now,
        double queueEnd, Link link, double vehLen) {
      double lastDistance = Integer.MAX_VALUE;
      double ttfs = link.getLength() / link.getFreespeed(now);
      for (QueueVehicle veh : QueueLink.this.vehQueue) {
        double travelTime = now - veh.getLinkEnterTime();
        double distanceOnLink = (ttfs == 0.0 ? 0.0
            : ((travelTime / ttfs) * link.getLength()));
        if (distanceOnLink > queueEnd) { // vehicle is already in queue
          distanceOnLink = queueEnd;
          queueEnd -= vehLen;
        }
        if (distanceOnLink >= lastDistance) {
          /*
           * we have a queue, so it should not be possible that one vehicles
           * overtakes another. additionally, if two vehicles entered at the same
           * time, they would be drawn on top of each other. we don't allow this,
           * so in this case we put one after the other. Theoretically, this could
           * lead to vehicles placed at negative distance when a lot of vehicles
           * all enter at the same time on an empty link. not sure what to do
           * about this yet... just setting them to 0 currently.
           */
          distanceOnLink = lastDistance - vehLen;
          if (distanceOnLink < 0)
            distanceOnLink = 0.0;
        }
        int cmp = (int) (veh.getEarliestLinkExitTime() + QueueLink.this.inverseSimulatedFlowCapacity + 2.0);
        double speed = (now > cmp) ? 0.0 : link.getFreespeed(now);
        int tmpLane ;
        try {
          tmpLane = Integer.parseInt(veh.getId().toString()) ;
        } catch ( NumberFormatException ee ) {
          tmpLane = veh.getId().hashCode() ;
        }
        int lane = 1 + (tmpLane % NetworkUtils.getNumberOfLanesAsInt(Time.UNDEFINED_TIME, link));
        AgentSnapshotInfo position = new PositionInfo(this.linkScale, veh.getDriver().getPerson().getId(), link, distanceOnLink,
            lane, speed, AgentSnapshotInfo.AgentState.PERSON_DRIVING_CAR);
        positions.add(position);
        lastDistance = distanceOnLink;
      }
    }

    /**
     * Put the vehicles from the waiting list in positions. Their actual
     * position doesn't matter, so they are just placed to the coordinates of
     * the from node
     */
    private int positionVehiclesFromWaitingList(
        final Collection<AgentSnapshotInfo> positions, Link link,
        double cellSize) {
      int lane = NetworkUtils.getNumberOfLanesAsInt(Time.UNDEFINED_TIME, link) + 1; // place them next to the link
      for (QueueVehicle veh : QueueLink.this.waitingList) {
    	  AgentSnapshotInfo position = new PositionInfo(this.linkScale, veh.getDriver().getPerson().getId(), QueueLink.this.getLink(),
            /*positionOnLink*/cellSize, lane, 0.0, AgentSnapshotInfo.AgentState.PERSON_AT_ACTIVITY);
        positions.add(position);
      }
      return lane;
    }
  }

}

