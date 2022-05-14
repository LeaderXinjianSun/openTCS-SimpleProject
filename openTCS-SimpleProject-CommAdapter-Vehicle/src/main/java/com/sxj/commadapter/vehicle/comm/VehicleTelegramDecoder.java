/**
 * Copyright (c) The openTCS Authors.
 *
 * This program is free software and subject to the MIT license. (For details,
 * see the licensing information (LICENSE.txt) you should have received with
 * this copy of the software.)
 */
package com.sxj.commadapter.vehicle.comm;

import com.sxj.commadapter.vehicle.telegrams.OrderResponse;
import com.sxj.commadapter.vehicle.telegrams.StateResponse;
import com.sxj.common.telegrams.Response;
import com.google.common.primitives.Ints;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.util.List;
import static java.util.Objects.requireNonNull;
import org.apache.commons.codec.binary.Hex;
import org.opentcs.contrib.communication.tcp.ConnectionEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decodes incoming bytes into {@link StateResponse} instances.
 *
 * @author Stefan Walter (Fraunhofer IML)
 */
public class VehicleTelegramDecoder
    extends ByteToMessageDecoder {

  /**
   * This class's Logger.
   */
  private static final Logger LOG = LoggerFactory.getLogger(VehicleTelegramDecoder.class);
  /**
   * The handler decoded responses are sent to.
   */
  private final ConnectionEventListener<Response> responseHandler;
  /**
   * The minimum bytes required to even try decoding (size of the smallest telegram).
   */
  private final long minimumBytesRequired;

  /**
   * Creates a new instance.
   *
   * @param responseHandler The handler decoded responses are sent to.
   */
  public VehicleTelegramDecoder(ConnectionEventListener<Response> responseHandler) {
    this.responseHandler = requireNonNull(responseHandler, "responseHandler");
    this.minimumBytesRequired = Ints.min(OrderResponse.TELEGRAM_LENGTH,
                                         StateResponse.TELEGRAM_LENGTH);
  }

  @Override
  protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
    // Don't do anything if we don't have enough bytes.
    if (in.readableBytes() < minimumBytesRequired) {
      return;
    }

    byte[] telegramData;
    in.markReaderIndex();

    if (in.readableBytes() >= OrderResponse.TELEGRAM_LENGTH) {
      LOG.debug("Checking if it's an order response...");
      telegramData = new byte[OrderResponse.TELEGRAM_LENGTH];
      in.readBytes(telegramData);
      LOG.debug("Telegram data: {}", Hex.encodeHexString(telegramData));
      if (OrderResponse.isOrderResponse(telegramData)) {
        responseHandler.onIncomingTelegram(new OrderResponse(telegramData));
        return;
      }
      else {
        in.resetReaderIndex();
      }
    }

    if (in.readableBytes() >= StateResponse.TELEGRAM_LENGTH) {
      LOG.debug("Checking if it's a state response...");
      telegramData = new byte[StateResponse.TELEGRAM_LENGTH];
      in.readBytes(telegramData);
      LOG.debug("Telegram data: {}", Hex.encodeHexString(telegramData));
      if (StateResponse.isStateResponse(telegramData)) {
        responseHandler.onIncomingTelegram(new StateResponse(telegramData));
      }
      else {
        // Don't reset reader index and discard bytes
        LOG.warn("Not a valid telegram: {}", Hex.encodeHexString(telegramData));
      }
    }
  }
}
