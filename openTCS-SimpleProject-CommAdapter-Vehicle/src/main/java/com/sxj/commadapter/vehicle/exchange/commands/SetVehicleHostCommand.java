/**
 * Copyright (c) The openTCS Authors.
 *
 * This program is free software and subject to the MIT license. (For details,
 * see the licensing information (LICENSE.txt) you should have received with
 * this copy of the software.)
 */
package com.sxj.commadapter.vehicle.exchange.commands;

import com.sxj.commadapter.vehicle.SimpleCommAdapter;
import static java.util.Objects.requireNonNull;
import org.opentcs.drivers.vehicle.AdapterCommand;
import org.opentcs.drivers.vehicle.VehicleCommAdapter;

/**
 * A command to set the vehicle's host.
 *
 * @author Martin Grzenia (Fraunhofer IML)
 */
public class SetVehicleHostCommand
    implements AdapterCommand {

  /**
   * The host to set.
   */
  private final String host;

  /**
   * Creates a new instnace.
   *
   * @param host The host to set.
   */
  public SetVehicleHostCommand(String host) {
    this.host = requireNonNull(host, "host");
  }

  @Override
  public void execute(VehicleCommAdapter adapter) {
    if (!(adapter instanceof SimpleCommAdapter)) {
      return;
    }

    SimpleCommAdapter exampleAdapter = (SimpleCommAdapter) adapter;
    exampleAdapter.getProcessModel().setVehicleHost(host);
  }
}
