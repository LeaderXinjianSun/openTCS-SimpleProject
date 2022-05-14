/**
 * Copyright (c) The openTCS Authors.
 *
 * This program is free software and subject to the MIT license. (For details,
 * see the licensing information (LICENSE.txt) you should have received with
 * this copy of the software.)
 */
package com.sxj.commadapter.vehicle;

import com.sxj.common.telegrams.RequestResponseMatcher;
import com.sxj.common.telegrams.TelegramSender;
import org.opentcs.data.model.Vehicle;

/**
 * A factory for various instances specific to the comm adapter.
 *
 * @author Martin Grzenia (Fraunhofer IML)
 */
public interface SimpleAdapterComponentsFactory {

  /**
   * Creates a new SimpleCommAdapter for the given vehicle.
   *
   * @param vehicle The vehicle
   * @return A new SimpleCommAdapter for the given vehicle
   */
  SimpleCommAdapter createSimpleCommAdapter(Vehicle vehicle);

  /**
   * Creates a new {@link RequestResponseMatcher}.
   *
   * @param telegramSender Sends telegrams/requests.
   * @return The created {@link RequestResponseMatcher}.
   */
  RequestResponseMatcher createRequestResponseMatcher(TelegramSender telegramSender);
}
