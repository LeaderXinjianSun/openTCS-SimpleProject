/**
 * Copyright (c) The openTCS Authors.
 *
 * This program is free software and subject to the MIT license. (For details,
 * see the licensing information (LICENSE.txt) you should have received with
 * this copy of the software.)
 */
package com.sxj.commadapter.vehicle;

import com.sxj.commadapter.vehicle.comm.VehicleTelegramDecoder;
import com.sxj.commadapter.vehicle.comm.VehicleTelegramEncoder;
import com.sxj.commadapter.vehicle.exchange.SimpleProcessModelTO;
import com.sxj.commadapter.vehicle.telegrams.OrderRequest;
import com.sxj.commadapter.vehicle.telegrams.OrderResponse;
import com.sxj.commadapter.vehicle.telegrams.StateRequest;
import com.sxj.commadapter.vehicle.telegrams.StateResponse;
import com.sxj.commadapter.vehicle.telegrams.StateResponse.LoadState;
import com.sxj.common.dispatching.LoadAction;
import com.sxj.common.telegrams.BoundedCounter;
import static com.sxj.common.telegrams.BoundedCounter.UINT16_MAX_VALUE;
import com.sxj.common.telegrams.Request;
import com.sxj.common.telegrams.RequestResponseMatcher;
import com.sxj.common.telegrams.Response;
import com.sxj.common.telegrams.Telegram;
import com.sxj.common.telegrams.TelegramSender;
import com.google.common.primitives.Ints;
import com.google.inject.assistedinject.Assisted;
import io.netty.channel.ChannelHandler;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import java.beans.PropertyChangeEvent;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import static java.util.Objects.requireNonNull;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.inject.Inject;
import org.opentcs.contrib.communication.tcp.ConnectionEventListener;
import org.opentcs.contrib.communication.tcp.TcpClientChannelManager;
import org.opentcs.customizations.kernel.KernelExecutor;
import org.opentcs.data.model.Vehicle;
import org.opentcs.data.order.DriveOrder;
import org.opentcs.data.order.TransportOrder;
import org.opentcs.drivers.vehicle.BasicVehicleCommAdapter;
import org.opentcs.drivers.vehicle.MovementCommand;
import org.opentcs.drivers.vehicle.VehicleProcessModel;
import org.opentcs.drivers.vehicle.management.VehicleProcessModelTO;
import org.opentcs.util.ExplainedBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An example implementation for a communication adapter.
 *
 * @author Mats Wilhelm (Fraunhofer IML)
 */
public class SimpleCommAdapter
    extends BasicVehicleCommAdapter
    implements ConnectionEventListener<Response>,
               TelegramSender {

  /**
   * This class's logger.
   */
  private static final Logger LOG = LoggerFactory.getLogger(SimpleCommAdapter.class);
  /**
   * Maps movement commands from openTCS to the telegrams sent to the attached vehicle.
   */
  private final OrderMapper orderMapper;
  /**
   * The components factory.
   */
  private final SimpleAdapterComponentsFactory componentsFactory;
  /**
   * The kernel's executor service.
   */
  private final ScheduledExecutorService kernelExecutor;
  /**
   * Manages counting the ids for all {@link Request} telegrams.
   */
  private final BoundedCounter globalRequestCounter = new BoundedCounter(0, UINT16_MAX_VALUE);
  /**
   * Maps commands to order IDs so we know which command to report as finished.
   */
  private final Map<MovementCommand, Integer> orderIds = new ConcurrentHashMap<>();
  /**
   * Manages the channel to the vehicle.
   */
  private TcpClientChannelManager<Request, Response> vehicleChannelManager;
  /**
   * Matches requests to responses and holds a queue for pending requests.
   */
  private RequestResponseMatcher requestResponseMatcher;
  /**
   * A future for the periodic state requester task.
   */
  private ScheduledFuture<?> stateRequestFuture;
  /**
   * Whether we have sent a state request and are still waiting for a response.
   */
  private volatile boolean expectingStateResponse;

  /**
   * Creates a new instance.
   *
   * @param vehicle The attached vehicle.
   * @param orderMapper The order mapper for movement commands.
   * @param componentsFactory The components factory.
   * @param kernelExecutor The kernel's executor service.
   */
  @Inject
  public SimpleCommAdapter(@Assisted Vehicle vehicle,
                            OrderMapper orderMapper,
                            SimpleAdapterComponentsFactory componentsFactory,
                            @KernelExecutor ScheduledExecutorService kernelExecutor) {
    super(new SimpleProcessModel(vehicle), 3, 2, LoadAction.CHARGE, kernelExecutor);
    this.orderMapper = requireNonNull(orderMapper, "orderMapper");
    this.componentsFactory = requireNonNull(componentsFactory, "componentsFactory");
    this.kernelExecutor = requireNonNull(kernelExecutor, "kernelExecutor");
  }

  @Override
  public void initialize() {
    super.initialize();
    this.requestResponseMatcher = componentsFactory.createRequestResponseMatcher(this);
  }

  @Override
  public synchronized void enable() {
    if (isEnabled()) {
      return;
    }

    //Create the channel manager responsible for connections with the vehicle
    vehicleChannelManager = new TcpClientChannelManager<>(this,
                                                          this::getChannelHandlers,
                                                          getProcessModel().getVehicleIdleTimeout(),
                                                          getProcessModel().isLoggingEnabled());
    //Initialize the channel manager
    vehicleChannelManager.initialize();
    super.enable();
  }

  @Override
  public synchronized void disable() {
    if (!isEnabled()) {
      return;
    }

    super.disable();
    vehicleChannelManager.terminate();
    vehicleChannelManager = null;
  }

  @Override
  public synchronized void clearCommandQueue() {
    super.clearCommandQueue();
    orderIds.clear();
  }

  @Override
  protected synchronized void connectVehicle() {
    if (vehicleChannelManager == null) {
      LOG.warn("{}: VehicleChannelManager not present.", getName());
      return;
    }

    vehicleChannelManager.connect(getProcessModel().getVehicleHost(),
                                  getProcessModel().getVehiclePort());
  }

  @Override
  protected synchronized void disconnectVehicle() {
    if (vehicleChannelManager == null) {
      LOG.warn("{}: VehicleChannelManager not present.", getName());
      return;
    }

    vehicleChannelManager.disconnect();
  }

  @Override
  protected synchronized boolean isVehicleConnected() {
    return vehicleChannelManager != null && vehicleChannelManager.isConnected();
  }

  @Override
  public void propertyChange(PropertyChangeEvent evt) {
    super.propertyChange(evt);
    if (!(evt.getSource() instanceof SimpleProcessModel)) {
      return;
    }

    // Handling of events from the vehicle gui panels start here
    if (Objects.equals(evt.getPropertyName(),
                       VehicleProcessModel.Attribute.COMM_ADAPTER_CONNECTED.name())) {
      if (getProcessModel().isCommAdapterConnected()) {
        // Once the connection is established, ensure that logging is enabled/disabled for it as
        // configured by the user.
        vehicleChannelManager.setLoggingEnabled(getProcessModel().isLoggingEnabled());
      }
    }
    if (Objects.equals(evt.getPropertyName(),
                       VehicleProcessModel.Attribute.COMM_ADAPTER_CONNECTED.name())
        || Objects.equals(evt.getPropertyName(),
                          SimpleProcessModel.Attribute.PERIODIC_STATE_REQUESTS_ENABLED.name())) {
      if (getProcessModel().isCommAdapterConnected()
          && getProcessModel().isPeriodicStateRequestEnabled()) {
        startPeriodicStateRequesting();
      }
      else {
        stopPeriodicStateRequesting();
      }
    }
    if (Objects.equals(evt.getPropertyName(),
                       SimpleProcessModel.Attribute.PERIOD_STATE_REQUESTS_INTERVAL.name())) {
      if (getProcessModel().isPeriodicStateRequestEnabled()) {
        stopPeriodicStateRequesting();
        startPeriodicStateRequesting();
      }
    }
  }

  @Override
  public final SimpleProcessModel getProcessModel() {
    return (SimpleProcessModel) super.getProcessModel();
  }

  @Override
  protected VehicleProcessModelTO createCustomTransferableProcessModel() {
    //Add extra information of the vehicle when sending to other software like control center or 
    //plant overview
    return new SimpleProcessModelTO()
        .setVehicleRef(getProcessModel().getVehicleReference())
        .setCurrentState(getProcessModel().getCurrentState())
        .setPreviousState(getProcessModel().getPreviousState())
        .setLastOrderSent(getProcessModel().getLastOrderSent())
        .setDisconnectingOnVehicleIdle(getProcessModel().isDisconnectingOnVehicleIdle())
        .setLoggingEnabled(getProcessModel().isLoggingEnabled())
        .setReconnectDelay(getProcessModel().getReconnectDelay())
        .setReconnectingOnConnectionLoss(getProcessModel().isReconnectingOnConnectionLoss())
        .setVehicleHost(getProcessModel().getVehicleHost())
        .setVehicleIdle(getProcessModel().isVehicleIdle())
        .setVehicleIdleTimeout(getProcessModel().getVehicleIdleTimeout())
        .setVehiclePort(getProcessModel().getVehiclePort())
        .setPeriodicStateRequestEnabled(getProcessModel().isPeriodicStateRequestEnabled())
        .setStateRequestInterval(getProcessModel().getStateRequestInterval());
  }

  @Override
  public synchronized void sendCommand(MovementCommand cmd)
      throws IllegalArgumentException {
    requireNonNull(cmd, "cmd");

    try {
      OrderRequest telegram = orderMapper.mapToOrder(cmd);
      orderIds.put(cmd, telegram.getOrderId());
      LOG.debug("{}: Enqueuing order request with: order id={}, dest. id={}, dest. action={}",
                getName(),
                telegram.getOrderId(),
                telegram.getDestinationId(),
                telegram.getDestinationAction());

      // Add the telegram to the queue. Telegram will be send later when its the first telegram in 
      // the queue. This ensures that we always wait for a response until we send a new request.
      requestResponseMatcher.enqueueRequest(telegram);
    }
    catch (IllegalArgumentException exc) {
      LOG.error("{}: Failed to enqueue command {}", getName(), cmd, exc);
    }
  }

  @Override
  public ExplainedBoolean canProcess(@Nonnull TransportOrder order) {
    return canProcessOperations(
        order.getFutureDriveOrders().stream()
            .map(driveOrder -> driveOrder.getDestination().getOperation())
            .collect(Collectors.toList())
    );
  }

  @Override
  public void processMessage(Object message) {
    //Process messages sent from the kernel or a kernel extension
  }

  @Override
  public void onConnect() {
    if (!isEnabled()) {
      return;
    }
    LOG.debug("{}: connected", getName());
    getProcessModel().setCommAdapterConnected(true);
    // Request the vehicle's current state (preparation for the state requester task)
    requestResponseMatcher.enqueueRequest(new StateRequest(Telegram.ID_DEFAULT));
    // Check for resending last request
    requestResponseMatcher.checkForSendingNextRequest();
  }

  @Override
  public void onFailedConnectionAttempt() {
    if (!isEnabled()) {
      return;
    }
    getProcessModel().setCommAdapterConnected(false);
    if (isEnabled() && getProcessModel().isReconnectingOnConnectionLoss()) {
      vehicleChannelManager.scheduleConnect(getProcessModel().getVehicleHost(),
                                            getProcessModel().getVehiclePort(),
                                            getProcessModel().getReconnectDelay());
    }
  }

  @Override
  public void onDisconnect() {
    LOG.debug("{}: disconnected", getName());
    getProcessModel().setCommAdapterConnected(false);
    getProcessModel().setVehicleIdle(true);
    getProcessModel().setVehicleState(Vehicle.State.UNKNOWN);
    if (isEnabled() && getProcessModel().isReconnectingOnConnectionLoss()) {
      vehicleChannelManager.scheduleConnect(getProcessModel().getVehicleHost(),
                                            getProcessModel().getVehiclePort(),
                                            getProcessModel().getReconnectDelay());
    }
  }

  @Override
  public void onIdle() {
    LOG.debug("{}: idle", getName());
    getProcessModel().setVehicleIdle(true);
    // If we are supposed to reconnect automatically, do so.
    if (isEnabled() && getProcessModel().isDisconnectingOnVehicleIdle()) {
      LOG.debug("{}: Disconnecting on idle timeout...", getName());
      disconnectVehicle();
    }
  }

  @Override
  public synchronized void onIncomingTelegram(Response response) {
    requireNonNull(response, "response");

    // Remember that we have received a sign of life from the vehicle
    getProcessModel().setVehicleIdle(false);

    //Check if the response matches the current request
    if (!requestResponseMatcher.tryMatchWithCurrentRequest(response)) {
      // XXX Either ignore the message or close the connection
      return;
    }

    if (response instanceof StateResponse) {
      onStateResponse((StateResponse) response);
    }
    else {
      LOG.debug("{}: Receiving response: {}", getName(), response);
    }

    //Send the next telegram if one is waiting
    requestResponseMatcher.checkForSendingNextRequest();
  }

  @Override
  public synchronized void sendTelegram(Request telegram) {
    requireNonNull(telegram, "telegram");
    if (!isVehicleConnected()) {
      LOG.debug("{}: Not connected - not sending request '{}'",
                getName(),
                telegram);
      return;
    }

    // Update the request's id
    telegram.updateRequestContent(globalRequestCounter.getAndIncrement());

    vehicleChannelManager.send(telegram);

    // If the telegram is an order, remember it.
    if (telegram instanceof OrderRequest) {
      getProcessModel().setLastOrderSent((OrderRequest) telegram);
    }
  }

  private ExplainedBoolean canProcessOperations(List<String> operations) {
    requireNonNull(operations, "operations");
    boolean canProcess = true;
    String reason = "";
    if (!isEnabled()) {
      canProcess = false;
      reason = "Adapter not enabled";
    }
    if (canProcess && !isVehicleConnected()) {
      canProcess = false;
      reason = "Vehicle does not seem to be connected";
    }
    if (canProcess
        && getProcessModel().getCurrentState().getLoadState() == LoadState.UNKNOWN) {
      canProcess = false;
      reason = "Vehicle's load state is undefined";
    }
    boolean loaded = getProcessModel().getCurrentState().getLoadState() == LoadState.FULL;
    final Iterator<String> opIter = operations.iterator();
    while (canProcess && opIter.hasNext()) {
      final String nextOp = opIter.next();
      // If we're loaded, we cannot load another piece, but could unload.
      if (loaded) {
        if (nextOp.startsWith(LoadAction.LOAD)) {
          canProcess = false;
          reason = "Cannot load when already loaded";
        }
        else if (nextOp.startsWith(LoadAction.UNLOAD)) {
          loaded = false;
        }
        else if (nextOp.startsWith(DriveOrder.Destination.OP_PARK)) {
          canProcess = false;
          reason = "Vehicle shouldn't park while in a loaded state.";
        }
        else if (nextOp.startsWith(LoadAction.CHARGE)) {
          canProcess = false;
          reason = "Vehicle shouldn't charge while in a loaded state.";
        }
      }
      // If we're not loaded, we could load, but not unload.
      else if (nextOp.startsWith(LoadAction.LOAD)) {
        loaded = true;
      }
      else if (nextOp.startsWith(LoadAction.UNLOAD)) {
        canProcess = false;
        reason = "Cannot unload when not loaded";
      }
    }
    return new ExplainedBoolean(canProcess, reason);
  }

  public RequestResponseMatcher getRequestResponseMatcher() {
    return requestResponseMatcher;
  }

  private void startPeriodicStateRequesting() {
    if (stateRequestFuture != null) {
      LOG.warn("Periodic state requesting already running - not started again.");
      return;
    }

    LOG.debug("Starting periodic state requests...");

    stateRequestFuture = kernelExecutor.scheduleAtFixedRate(
        () -> requestStateFromVehicle(),
        getProcessModel().getStateRequestInterval(),
        getProcessModel().getStateRequestInterval(),
        TimeUnit.MILLISECONDS
    );
  }

  private void stopPeriodicStateRequesting() {
    if (stateRequestFuture == null) {
      LOG.warn("Periodic state requesting not running - not stopped.");
      return;
    }

    LOG.debug("Stopping periodic state requests...");

    stateRequestFuture.cancel(false);
    stateRequestFuture = null;
  }

  private void requestStateFromVehicle() {
    if (expectingStateResponse) {
      LOG.warn("No response to previous state request, yet - not sending another one.");
    }
    else {
      requestResponseMatcher.enqueueRequest(new StateRequest(Telegram.ID_DEFAULT));
      expectingStateResponse = true;
    }
  }

  private void onStateResponse(StateResponse stateResponse) {
    requireNonNull(stateResponse, "stateResponse");

    expectingStateResponse = false;

    final StateResponse previousState = getProcessModel().getCurrentState();
    final StateResponse currentState = stateResponse;

    kernelExecutor.submit(() -> {
      // Update the vehicle's current state and remember the old one.
      getProcessModel().setPreviousState(previousState);
      getProcessModel().setCurrentState(currentState);

      checkForVehiclePositionUpdate(previousState, currentState);
      checkForVehicleStateUpdate(previousState, currentState);
      checkOrderFinished(previousState, currentState);

      // XXX Process further state updates extracted from the telegram here.
    });
  }

  private void checkForVehiclePositionUpdate(StateResponse previousState,
                                             StateResponse currentState) {
    if (previousState.getPositionId() == currentState.getPositionId()) {
      return;
    }
    // Map the reported position ID to a point name.
    String currentPosition = String.valueOf(currentState.getPositionId());
    LOG.debug("{}: Vehicle is now at point {}", getName(), currentPosition);
    // Update the position with the rest of the system, but only if it's not zero (unknown).
    if (currentState.getPositionId() != 0) {
      getProcessModel().setVehiclePosition(currentPosition);
    }
  }

  private void checkForVehicleStateUpdate(StateResponse previousState,
                                          StateResponse currentState) {
    if (previousState.getOperationState() == currentState.getOperationState()) {
      return;
    }
    getProcessModel().setVehicleState(translateVehicleState(currentState.getOperationState()));
  }

  private void checkOrderFinished(StateResponse previousState, StateResponse currentState) {
    if (currentState.getLastFinishedOrderId() == 0) {
      return;
    }
    // If the last finished order ID hasn't changed, don't bother.
    if (previousState.getLastFinishedOrderId() == currentState.getLastFinishedOrderId()) {
      return;
    }
    // Check if the new finished order ID is in the queue of sent orders.
    // If yes, report all orders up to that one as finished.
    if (!orderIds.containsValue(currentState.getLastFinishedOrderId())) {
      LOG.debug("{}: Ignored finished order ID {} (reported by vehicle, not found in sent queue).",
                getName(),
                currentState.getLastFinishedOrderId());
      return;
    }

    Iterator<MovementCommand> cmdIter = getSentQueue().iterator();
    boolean finishedAll = false;
    while (!finishedAll && cmdIter.hasNext()) {
      MovementCommand cmd = cmdIter.next();
      cmdIter.remove();
      int orderId = orderIds.remove(cmd);
      if (orderId == currentState.getLastFinishedOrderId()) {
        finishedAll = true;
      }

      LOG.debug("{}: Reporting command with order ID {} as executed: {}", getName(), orderId, cmd);
      getProcessModel().commandExecuted(cmd);
    }
  }

  /**
   * Map the vehicle's operation states to the kernel's vehicle states.
   *
   * @param operationState The vehicle's current operation state.
   */
  private Vehicle.State translateVehicleState(StateResponse.OperationState operationState) {
    switch (operationState) {
      case IDLE:
        return Vehicle.State.IDLE;
      case MOVING:
      case ACTING:
        return Vehicle.State.EXECUTING;
      case CHARGING:
        return Vehicle.State.CHARGING;
      case ERROR:
        return Vehicle.State.ERROR;
      default:
        return Vehicle.State.UNKNOWN;
    }
  }

  /**
   * Returns the channel handlers responsible for writing and reading from the byte stream.
   *
   * @return The channel handlers responsible for writing and reading from the byte stream
   */
  private List<ChannelHandler> getChannelHandlers() {
    return Arrays.asList(new LengthFieldBasedFrameDecoder(getMaxTelegramLength(), 1, 1, 2, 0),
                         new VehicleTelegramDecoder(this),
                         new VehicleTelegramEncoder());
  }

  private int getMaxTelegramLength() {
    return Ints.max(OrderResponse.TELEGRAM_LENGTH,
                    StateResponse.TELEGRAM_LENGTH);
  }
}
