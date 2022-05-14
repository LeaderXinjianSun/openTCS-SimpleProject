/**
 * Copyright (c) The openTCS Authors.
 *
 * This program is free software and subject to the MIT license. (For details,
 * see the licensing information (LICENSE.txt) you should have received with
 * this copy of the software.)
 */
package com.sxj.commadapter.vehicle.exchange.commands;

import com.sxj.commadapter.vehicle.SimpleCommAdapter;
import org.opentcs.drivers.vehicle.AdapterCommand;
import org.opentcs.drivers.vehicle.VehicleCommAdapter;

/**
 * A command to set the adapter's reconnect on connection loss flag.
 *
 * @author Martin Grzenia (Fraunhofer IML)
 */
public class SetReconnectingOnConnectionLossCommand
    implements AdapterCommand {

  /**
   * The flag state to set.
   */
  private final boolean reconnect;

  /**
   * Creates a new instance.
   *
   * @param reconnect The flag state to set
   */
  public SetReconnectingOnConnectionLossCommand(boolean reconnect) {
    this.reconnect = reconnect;
  }

  @Override
  public void execute(VehicleCommAdapter adapter) {
    if (!(adapter instanceof SimpleCommAdapter)) {
      return;
    }

    SimpleCommAdapter exampleAdapter = (SimpleCommAdapter) adapter;
    exampleAdapter.getProcessModel().setReconnectingOnConnectionLoss(reconnect);
  }
}
