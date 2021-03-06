/**
 * Copyright (c) 2016 Bosch Software Innovations GmbH.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Bosch Software Innovations GmbH - initial creation
 */
package org.eclipse.hono.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.qpid.proton.message.Message;
import org.eclipse.hono.config.HonoConfigProperties;
import org.eclipse.hono.connection.ConnectionFactory;
import org.eclipse.hono.util.Constants;
import org.eclipse.hono.util.ResourceIdentifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.proton.ProtonClientOptions;
import io.vertx.proton.ProtonConnection;
import io.vertx.proton.ProtonDelivery;
import io.vertx.proton.ProtonHelper;
import io.vertx.proton.ProtonQoS;
import io.vertx.proton.ProtonSender;

/**
 * A downstream adapter that provides support for sending messages to an AMQP 1.0 container.
 *
 */
@Component
public abstract class ForwardingDownstreamAdapter implements DownstreamAdapter {

    /**
     * A logger to be shared with subclasses.
     */
    protected Logger                        logger                          = LoggerFactory.getLogger(getClass());
    /**
     * The Hono configuration.
     */
    protected HonoConfigProperties          honoConfig                      = new HonoConfigProperties();
    private final Map<UpstreamReceiver, ProtonSender> activeSenders         = new HashMap<>();
    private final Map<String, List<UpstreamReceiver>> sendersPerConnection  = new HashMap<>();
    private boolean                         running                         = false;
    private final Vertx                     vertx;
    private ProtonConnection                downstreamConnection;
    private SenderFactory                   senderFactory;
    private ConnectionFactory               downstreamConnectionFactory;

    /**
     * Creates a new adapter instance for a sender factory.
     * 
     * @param vertx The Vert.x instance to run on.
     * @param senderFactory The factory to use for creating new senders for downstream telemetry data.
     * @throws NullPointerException if any of the parameters is {@code null}.
     */
    protected ForwardingDownstreamAdapter(final Vertx vertx, final SenderFactory senderFactory) {
        this.vertx = Objects.requireNonNull(vertx);
        this.senderFactory = Objects.requireNonNull(senderFactory);
    }

    /**
     * Sets the global Hono configuration properties.
     * 
     * @param props The properties.
     * @throws NullPointerException if props is {@code null}.
     * @throws IllegalStateException if this adapter is already running.
     */
    @Autowired(required = false)
    public final void setHonoConfiguration(final HonoConfigProperties props) {
        if (running) {
            throw new IllegalStateException("configuration can not be set on running adapter");
        } else {
            this.honoConfig = Objects.requireNonNull(props);
        }
    }

    /**
     * Sets the factory to use for connecting to the downstream container.
     * 
     * @param factory The factory.
     * @throws NullPointerException if the factory is {@code null}.
     * @throws IllegalStateException if this adapter is already running.
     */
    @Autowired
    public final void setDownstreamConnectionFactory(final ConnectionFactory factory) {
        if (running) {
            throw new IllegalStateException("downstream container host can not be set on running adapter");
        } else {
            this.downstreamConnectionFactory = Objects.requireNonNull(factory);
        }
    }

    /**
     * Connects to the downstream container.
     * 
     * @param startFuture The result of the connection attempt.
     * @throws IllegalStateException If the downstream container host is {@code null}
     *                               or the downstream container port is 0.
     */
    @Override
    public final void start(final Future<Void> startFuture) {

        if (running) {
            startFuture.complete();
        } else if (downstreamConnectionFactory == null) {
            throw new IllegalStateException("downstream connection factory is not set");
        } else {
            running = true;
            if (honoConfig.isWaitForDownstreamConnectionEnabled()) {
                logger.info("waiting for connection to downstream container");
                connectToDownstream(createClientOptions(), connectAttempt -> {
                    if (connectAttempt.succeeded()) {
                        startFuture.complete();
                    } else {
                        startFuture.fail(connectAttempt.cause());
                    }
                });
            } else {
                connectToDownstream(createClientOptions());
                startFuture.complete();
            }
        }
    }

    /**
     * Closes the connection with the downstream container.
     * 
     * @param stopFuture Always succeeds.
     */
    @Override
    public final void stop(final Future<Void> stopFuture) {

        if (running) {
            if (downstreamConnection != null && !downstreamConnection.isDisconnected()) {
                final String container = downstreamConnection.getRemoteContainer();
                logger.info("closing connection to downstream container [{}]", container);
                downstreamConnection.closeHandler(null).disconnectHandler(null).close();
            } else {
                logger.debug("downstream connection already closed");
            }
            running = false;
        }
        stopFuture.complete();
    }

    /**
     * Gets the name of the downstream AMQP 1.0 container this adapter is forwarding messages to.
     * 
     * @return The name or {@code null} if this adapter is currently not connected.
     */
    protected final String getDownstreamContainer() {
        if (downstreamConnection != null) {
            return downstreamConnection.getRemoteContainer();
        } else {
            return null;
        }
    }

    private ProtonClientOptions createClientOptions() {
        return new ProtonClientOptions()
                .setConnectTimeout(100)
                .setReconnectAttempts(-1).setReconnectInterval(200); // reconnect forever, every 200 millisecs
    }

    private void connectToDownstream(final ProtonClientOptions options) {
        connectToDownstream(options, conAttempt -> {});
    }

    private void connectToDownstream(final ProtonClientOptions options, final Handler<AsyncResult<ProtonConnection>> connectResultHandler) {

        downstreamConnectionFactory.connect(
                options,
                this::onRemoteClose,
                this::onDisconnectFromDownstreamContainer,
                connectAttempt -> {
                    if (connectAttempt.succeeded()) {
                        this.downstreamConnection = connectAttempt.result();
                        connectResultHandler.handle(Future.succeededFuture(connectAttempt.result()));
                    } else {
                        logger.info("failed to connect to downstream container", connectAttempt.cause());
                        connectResultHandler.handle(Future.failedFuture(connectAttempt.cause()));
                    }
                });
    }

    private void onRemoteClose(final AsyncResult<ProtonConnection> remoteClose) {
        logger.info("connection to downstream container [{}] is closed", downstreamConnection.getRemoteContainer());
        downstreamConnection.close();
    }

    /**
     * Handles unexpected disconnection from downstream container.
     * 
     * @param con the failed connection
     */
    private void onDisconnectFromDownstreamContainer(final ProtonConnection con) {
        // all links to downstream host will now be stale and unusable
        logger.warn("lost connection to downstream container [{}], closing upstream receivers ...", con.getRemoteContainer());

        for (UpstreamReceiver client : activeSenders.keySet()) {
            client.close(ErrorConditions.ERROR_NO_DOWNSTREAM_CONSUMER);
        }
        sendersPerConnection.clear();
        activeSenders.clear();
        con.disconnectHandler(null);
        con.disconnect();
        final ProtonClientOptions clientOptions = createClientOptions();
        if (clientOptions.getReconnectAttempts() != 0) {
            vertx.setTimer(300, reconnect -> {
                logger.info("attempting to re-connect to downstream container");
                connectToDownstream(clientOptions);
            });
        }
    }

    @Override
    public final void onClientAttach(final UpstreamReceiver client, final Handler<AsyncResult<Void>> resultHandler) {

        if (!running) {
            throw new IllegalStateException("adapter must be started first");
        }

        Objects.requireNonNull(client);
        Objects.requireNonNull(resultHandler);

        ProtonSender sender = activeSenders.get(client);
        if (sender != null && sender.isOpen()) {
            logger.info("reusing existing downstream sender [con: {}, link: {}]", client.getConnectionId(), client.getLinkId());
            resultHandler.handle(Future.succeededFuture());
        } else {
            createSender(
                    client.getTargetAddress(),
                    replenishedSender -> handleFlow(replenishedSender, client),
                    creationAttempt -> {
                        if (creationAttempt.succeeded()) {
                            logger.info("created downstream sender [con: {}, link: {}]", client.getConnectionId(), client.getLinkId());
                            addSender(client, creationAttempt.result());
                            resultHandler.handle(Future.succeededFuture());
                        } else {
                            logger.warn("can't create downstream sender [con: {}, link: {}]", client.getConnectionId(), client.getLinkId(), creationAttempt.cause());
                            removeSender(client);
                            resultHandler.handle(Future.failedFuture(creationAttempt.cause()));
                        }
                    });
        }
    }

    /**
     * Invoked when a downstream sender receives link credit and/or a drain request from the downstream container.
     * <p>
     * The credits/drain request is forwarded to the corresponding upstream client.
     * 
     * @param replenishedSender The downstream sender that has received the FLOW.
     * @param client The upstream client associated with the sender.
     */
    public final void handleFlow(
            final ProtonSender replenishedSender,
            final UpstreamReceiver client) {

        logger.debug("received FLOW from downstream sender [con:{}, link: {}, credits: {}, queued: {}, drain: {}",
                client.getConnectionId(), client.getLinkId(), replenishedSender.getCredit(),
                replenishedSender.getQueued(), replenishedSender.getDrain());
        if (replenishedSender.getDrain()) {
            // send drain request upstream and act upon result of request to drain upstream client
            client.drain(10000, drainAttempt -> {
                if (drainAttempt.succeeded()) {
                    replenishedSender.drained();
                }
            });
        } else {
            int downstreamCredit = getAvailableDownstreamCredit(replenishedSender);
            client.replenish(downstreamCredit);
        }
    }

    private static int getAvailableDownstreamCredit(final ProtonSender downstreamSender) {
        return Math.max(0, downstreamSender.getCredit() - downstreamSender.getQueued());
    }

    private void createSender(
            final String targetAddress,
            final Handler<ProtonSender> sendQueueDrainHandler,
            final Handler<AsyncResult<ProtonSender>> handler) {

        Future<ProtonSender> result = Future.future();
        result.setHandler(handler);
        if (downstreamConnection == null || downstreamConnection.isDisconnected()) {
            result.fail("downstream connection must be opened before creating sender");
        } else {
            String tenantOnlyTargetAddress = getTenantOnlyTargetAddress(targetAddress);
            String address = tenantOnlyTargetAddress.replace(Constants.DEFAULT_PATH_SEPARATOR, honoConfig.getPathSeparator());
            senderFactory.createSender(downstreamConnection, address, getDownstreamQos(), sendQueueDrainHandler, result);
        }
    }

    private static String getTenantOnlyTargetAddress(final String address) {
        ResourceIdentifier targetAddress = ResourceIdentifier.fromString(address);
        return String.format("%s/%s", targetAddress.getEndpoint(), targetAddress.getTenantId());
    }

    /**
     * Associates a downstream sender with a corresponding upstream client.
     * 
     * @param link The upstream client.
     * @param sender The downstream sender.
     */
    public final void addSender(final UpstreamReceiver link, final ProtonSender sender) {
        sender.attachments().set(Constants.KEY_CONNECTION_ID, String.class, link.getConnectionId());
        sender.setAutoDrained(false); // we need to propagate drain requests upstream and wait for the result
        activeSenders.put(link, sender);
        List<UpstreamReceiver> senders = sendersPerConnection.get(link.getConnectionId());
        if (senders == null) {
            senders = new ArrayList<>();
            sendersPerConnection.put(link.getConnectionId(), senders);
        }
        senders.add(link);
    }

    /**
     * Removes all state kept for an upstream client.
     * <p>
     * Any downstream sender associated with the client is closed.
     * 
     * @param link The upstream client.
     */
    public final void removeSender(final UpstreamReceiver link) {
        List<UpstreamReceiver> senders = sendersPerConnection.get(link.getConnectionId());
        if (senders != null) {
            senders.remove(link);
        }
        ProtonSender downstreamSender = activeSenders.remove(link);
        if (downstreamSender != null && downstreamSender.isOpen()) {
            logger.info("closing downstream sender [con: {}, link: {}]", link.getConnectionId(), link.getLinkId());
            downstreamSender.close();
        }
    }

    @Override
    public final void onClientDetach(final UpstreamReceiver client) {

        if (!running) {
            throw new IllegalStateException("adapter must be started first");
        }

        Objects.requireNonNull(client);

        removeSender(client);
    }

    @Override
    public final void onClientDisconnect(final String connectionId) {

        if (!running) {
            throw new IllegalStateException("adapter must be started first");
        }

        List<UpstreamReceiver> senders = sendersPerConnection.remove(Objects.requireNonNull(connectionId));
        if (senders != null && !senders.isEmpty()) {
            logger.info("closing {} downstream senders for connection [id: {}]", senders.size(), connectionId);
            for (UpstreamReceiver link : senders) {
                closeSender(link);
            }
        }
    }

    private void closeSender(final UpstreamReceiver link) {
        ProtonSender sender = activeSenders.remove(link);
        if (sender != null && sender.isOpen()) {
            logger.info("closing downstream sender [con: {}, link: {}]", link.getConnectionId(), link.getLinkId());
            sender.close();
        }
    }

    @Override
    public final void processMessage(final UpstreamReceiver client, final ProtonDelivery delivery, final Message msg) {

        if (!running) {
            throw new IllegalStateException("adapter must be started first");
        }

        Objects.requireNonNull(client);
        Objects.requireNonNull(msg);
        Objects.requireNonNull(delivery);
        ProtonSender sender = activeSenders.get(client);
        if (sender == null) {
            logger.info("no downstream sender for link [{}] available, discarding message and closing link with client", client.getLinkId());
            client.close(ErrorConditions.ERROR_NO_DOWNSTREAM_CONSUMER);
        } else if (sender.isOpen()) {
            if (sender.getCredit() <= 0) {
                if (delivery.remotelySettled()) {
                    // sender has sent the message pre-settled, i.e. we can simply discard the message
                    logger.debug("no downstream credit available for link [{}], discarding message [{}]",
                            client.getLinkId(), msg.getMessageId());
                    ProtonHelper.accepted(delivery, true);
                } else {
                    // sender needs to be informed that we cannot process the message
                    logger.debug("no downstream credit available for link [{}], releasing message [{}]",
                            client.getLinkId(), msg.getMessageId());
                    ProtonHelper.released(delivery, true);
                }
            } else {
                // make sure upstream client does not starve before more credits flow in from downstream container
                client.replenish(getAvailableDownstreamCredit(sender));
                logger.trace("forwarding message [id: {}, to: {}, content-type: {}] to downstream container [{}], credit available: {}, queued: {}",
                        msg.getMessageId(), msg.getAddress(), msg.getContentType(), getDownstreamContainer(), sender.getCredit(), sender.getQueued());
                forwardMessage(sender, msg, delivery);
            }
        } else {
            logger.warn("downstream sender for link [{}] is not open, discarding message and closing link with client", client.getLinkId());
            client.close(ErrorConditions.ERROR_NO_DOWNSTREAM_CONSUMER);
            onClientDetach(client);
        }
    }

    /**
     * Checks if there are any downstream senders associated with upstream clients.
     * 
     * @return {@code true} if there are.
     */
    protected boolean isActiveSendersEmpty() {
        return activeSenders != null && activeSenders.isEmpty();
    }

    /**
     * Checks if there are any downstream senders associated with a particular upstream client connection.
     * 
     * @return {@code true} if there are.
     */
    protected boolean isSendersPerConnectionEmpty() {
        return sendersPerConnection != null && sendersPerConnection.isEmpty();
    }

    /**
     * Forwards the message to the downstream container.
     * <p>
     * It is the implementer's responsibility to handle message disposition and settlement with the upstream
     * client using the <em>delivery</em> object.
     * 
     * @param sender The link to the downstream container.
     * @param msg The message to send.
     * @param delivery The handle for settling the message with the client.
     */
    protected abstract void forwardMessage(final ProtonSender sender, final Message msg, final ProtonDelivery delivery);

    /**
     * Gets the Quality-of-Service type used for the link with the downstream container.
     * 
     * @return The QoS.
     */
    protected abstract ProtonQoS getDownstreamQos();
}
