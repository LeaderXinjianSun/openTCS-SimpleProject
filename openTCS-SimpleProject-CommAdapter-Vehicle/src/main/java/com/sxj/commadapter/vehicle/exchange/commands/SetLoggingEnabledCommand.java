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
 * A command to enable/disable logging.
 *
 * @author Martin Grzenia (Fraunhofer IML)
 */
public class SetLoggingEnabledCommand
    implements AdapterCommand {

  /**
   * The new logging state.
   */
  private final boolean enabled;

  /**
   * Creates a new instance.
   *
   * @param enabled The new logging state.
   */
  public SetLoggingEnabledCommand(boolean enabled) {
    this.enabled = enabled;
  }

  @Override
  public void execute(VehicleCommAdapter adapter) {
    if (!(adapter instanceof SimpleCommAdapter)) {
      return;
    }

    SimpleCommAdapter exampleAdapter = (SimpleCommAdapter) adapter;
    exampleAdapter.getProcessModel().setLoggingEnabled(enabled);
  }
}
