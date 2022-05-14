/**
 * Copyright (c) The openTCS Authors.
 *
 * This program is free software and subject to the MIT license. (For details,
 * see the licensing information (LICENSE.txt) you should have received with
 * this copy of the software.)
 */
package com.sxj.commadapter.vehicle;

import com.google.inject.assistedinject.FactoryModuleBuilder;
import org.opentcs.customizations.kernel.KernelInjectionModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SimpleKernelInjectionModule
    extends KernelInjectionModule {

  private static final Logger LOG = LoggerFactory.getLogger(SimpleKernelInjectionModule.class);

  @Override
  protected void configure() {

    SimpleCommAdapterConfiguration configuration
        = getConfigBindingProvider().get(SimpleCommAdapterConfiguration.PREFIX,
                                         SimpleCommAdapterConfiguration.class);

    if (!configuration.enable()) {
      LOG.info("Simple communication adapter disabled by configuration.");
      return;
    }

    install(new FactoryModuleBuilder().build(SimpleAdapterComponentsFactory.class));
    vehicleCommAdaptersBinder().addBinding().to(SimpleCommAdapterFactory.class);
  }
}
