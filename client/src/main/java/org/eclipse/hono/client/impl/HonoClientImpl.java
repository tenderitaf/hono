/**
 * Copyright (c) 2016, 2017 Bosch Software Innovations GmbH.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Bosch Software Innovations GmbH - initial creation
 */
package org.eclipse.hono.client.impl;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.apache.qpid.proton.message.Message;
import org.eclipse.hono.client.HonoClient;
import org.eclipse.hono.client.MessageConsumer;
import org.eclipse.hono.client.MessageSender;
import org.eclipse.hono.client.RegistrationClient;
import org.eclipse.hono.connection.ConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vertx.core.AsyncResult;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.proton.ProtonClientOptions;
import io.vertx.proton.ProtonConnection;

/**
 * A helper class for creating Vert.x based clients for Hono's arbitrary APIs.
 */
public final class HonoClientImpl implements HonoClient {

    private static final Logger LOG = LoggerFactory.getLogger(HonoClientImpl.class);
    private final Map<String, MessageSender> activeSenders = new ConcurrentHashMap<>();
    private final Map<String, RegistrationClient> activeRegClients = new ConcurrentHashMap<>();
    private final Map<String, Boolean> senderCreationLocks = new ConcurrentHashMap<>();
    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private ProtonClientOptions clientOptions;
    private ProtonConnection connection;
    private Vertx vertx;
    private Context context;
    private ConnectionFactory connectionFactory;

    /**
     * Creates a new client for a set of configuration properties.
     * 
     * @param vertx The Vert.x instance to execute the client on, if {@code null} a new Vert.x instance is used.
     * @param connectionFactory The factory to use for creating an AMQP connection to the Hono server.
     */
    public HonoClientImpl(final Vertx vertx, final ConnectionFactory connectionFactory) {
        if (vertx != null) {
            this.vertx = vertx;
        } else {
            this.vertx = Vertx.vertx();
        }
        this.connectionFactory = connectionFactory;
    }

    /**
     * Sets the connection to the Hono server.
     * <p>
     * This method is mostly useful to inject a (mock) connection when running tests.
     * 
     * @param connection The connection to use.
     */
    void setConnection(final ProtonConnection connection) {
        this.connection = connection;
    }

    /**
     * Sets the vertx context to run all interactions with the Hono server on.
     * <p>
     * This method is mostly useful to inject a (mock) context when running tests.
     * 
     * @param context The context to use.
     */
    void setContext(final Context context) {
        this.context = context;
    }

    /* (non-Javadoc)
     * @see org.eclipse.hono.client.HonoClient#isConnected()
     */
    @Override
    public boolean isConnected() {
        return connection != null && !connection.isDisconnected();
    }

    /* (non-Javadoc)
     * @see org.eclipse.hono.client.HonoClient#getConnectionStatus()
     */
    @Override
    public Map<String, Object> getConnectionStatus() {

        Map<String, Object> result = new HashMap<>();
        result.put("name", connectionFactory.getName());
        result.put("connected", isConnected());
        result.put("Hono server", String.format("%s:%d", connectionFactory.getHost(), connectionFactory.getPort()));
        result.put("#regClients", activeRegClients.size());
        result.put("senders", getSenderStatus());
        return result;
    }

    /* (non-Javadoc)
     * @see org.eclipse.hono.client.HonoClient#getSenderStatus()
     */
    @Override
    public JsonArray getSenderStatus() {

        JsonArray result = new JsonArray();
        for (Entry<String, MessageSender> senderEntry : activeSenders.entrySet()) {
            final MessageSender sender = senderEntry.getValue();
            JsonObject senderStatus = new JsonObject()
                .put("address", senderEntry.getKey())
                .put("open", sender.isOpen())
                .put("credit", sender.getCredit());
            result.add(senderStatus);
        }
        return result;
    }

    /* (non-Javadoc)
     * @see org.eclipse.hono.client.HonoClient#connect(io.vertx.proton.ProtonClientOptions, io.vertx.core.Handler)
     */
    @Override
    public HonoClient connect(final ProtonClientOptions options, final Handler<AsyncResult<HonoClient>> connectionHandler) {
        return connect(options, connectionHandler, null);
    }

    /* (non-Javadoc)
     * @see org.eclipse.hono.client.HonoClient#connect(io.vertx.proton.ProtonClientOptions, io.vertx.core.Handler, io.vertx.core.Handler)
     */
    @Override
    public HonoClient connect(
            final ProtonClientOptions options,
            final Handler<AsyncResult<HonoClient>> connectionHandler,
            final Handler<ProtonConnection> disconnectHandler) {

        Objects.requireNonNull(connectionHandler);

        if (isConnected()) {
            LOG.debug("already connected to server [{}:{}]", connectionFactory.getHost(), connectionFactory.getPort());
            connectionHandler.handle(Future.succeededFuture(this));
        } else if (connecting.compareAndSet(false, true)) {

            setConnection(null);
            if (options == null) {
                clientOptions = new ProtonClientOptions();
            } else {
                clientOptions = options;
            }

            connectionFactory.connect(
                    clientOptions,
                    this::onRemoteClose,
                    disconnectHandler != null ? disconnectHandler : this::onRemoteDisconnect,
                    conAttempt -> {
                        connecting.compareAndSet(true, false);
                        if (conAttempt.failed()) {
                            connectionHandler.handle(Future.failedFuture(conAttempt.cause()));
                        } else {
                            setConnection(conAttempt.result());
                            setContext(Vertx.currentContext());
                            connectionHandler.handle(Future.succeededFuture(this));
                        }
                    });
        } else {
            LOG.debug("already trying to connect to Hono server ...");
        }
        return this;
    }

    private void onRemoteClose(final AsyncResult<ProtonConnection> remoteClose) {
        if (remoteClose.failed()) {
            LOG.info("Hono server [{}:{}] closed connection with error condition: {}",
                    connectionFactory.getHost(), connectionFactory.getPort(), remoteClose.cause().getMessage());
        }
        connection.close();
        onRemoteDisconnect(connection);
    }

    private void onRemoteDisconnect(final ProtonConnection con) {

        LOG.warn("lost connection to Hono server [{}:{}]", connectionFactory.getHost(), connectionFactory.getPort());
        con.disconnect();
        activeSenders.clear();
        activeRegClients.clear();
        if (clientOptions.getReconnectAttempts() != 0) {
            // give Vert.x some time to clean up NetClient
            vertx.setTimer(300, reconnect -> {
                LOG.info("attempting to re-connect to Hono server [{}:{}]", connectionFactory.getHost(), connectionFactory.getPort());
                connect(clientOptions, done -> {});
            });
        }
    }

    /* (non-Javadoc)
     * @see org.eclipse.hono.client.HonoClient#getOrCreateTelemetrySender(java.lang.String, io.vertx.core.Handler)
     */
    @Override
    public HonoClient getOrCreateTelemetrySender(final String tenantId, final Handler<AsyncResult<MessageSender>> resultHandler) {
        return getOrCreateTelemetrySender(tenantId, null, resultHandler);
    }

    /* (non-Javadoc)
     * @see org.eclipse.hono.client.HonoClient#getOrCreateTelemetrySender(java.lang.String, java.lang.String, io.vertx.core.Handler)
     */
    @Override
    public HonoClient getOrCreateTelemetrySender(final String tenantId, final String deviceId, final Handler<AsyncResult<MessageSender>> resultHandler) {
        Objects.requireNonNull(tenantId);
        getOrCreateSender(
                TelemetrySenderImpl.getTargetAddress(tenantId, deviceId),
                (creationResult) -> createTelemetrySender(tenantId, deviceId, creationResult),
                resultHandler);
        return this;
    }

    /* (non-Javadoc)
     * @see org.eclipse.hono.client.HonoClient#getOrCreateEventSender(java.lang.String, io.vertx.core.Handler)
     */
    @Override
    public HonoClient getOrCreateEventSender(final String tenantId, final Handler<AsyncResult<MessageSender>> resultHandler) {
        return getOrCreateEventSender(tenantId, null, resultHandler);
    }

    /* (non-Javadoc)
     * @see org.eclipse.hono.client.HonoClient#getOrCreateEventSender(java.lang.String, java.lang.String, io.vertx.core.Handler)
     */
    @Override
    public HonoClient getOrCreateEventSender(
            final String tenantId,
            final String deviceId,
            final Handler<AsyncResult<MessageSender>> resultHandler) {

        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(resultHandler);
        getOrCreateSender(
                EventSenderImpl.getTargetAddress(tenantId, deviceId),
                (creationResult) -> createEventSender(tenantId, deviceId, creationResult),
                resultHandler);
        return this;
    }

    void getOrCreateSender(final String key, final Consumer<Handler<AsyncResult<MessageSender>>> newSenderSupplier,
            final Handler<AsyncResult<MessageSender>> resultHandler) {

        final MessageSender sender = activeSenders.get(key);
        if (sender != null && sender.isOpen()) {
            LOG.debug("reusing existing message sender [target: {}, credit: {}]", key, sender.getCredit());
            resultHandler.handle(Future.succeededFuture(sender));
        } else if (!senderCreationLocks.computeIfAbsent(key, k -> Boolean.FALSE)) {
            senderCreationLocks.put(key, Boolean.TRUE);
            LOG.debug("creating new message sender for {}", key);

            newSenderSupplier.accept(creationAttempt -> {
                if (creationAttempt.succeeded()) {
                    MessageSender newSender = creationAttempt.result();
                    LOG.debug("successfully created new message sender for {}", key);
                    activeSenders.put(key, newSender);
                } else {
                    LOG.debug("failed to create new message sender for {}", key, creationAttempt.cause());
                    activeSenders.remove(key);
                }
                senderCreationLocks.remove(key);
                resultHandler.handle(creationAttempt);
            });

        } else {
            LOG.debug("already trying to create a message sender for {}", key);
            resultHandler.handle(Future.failedFuture("sender link not established yet"));
        }
    }

    private HonoClient createTelemetrySender(
            final String tenantId,
            final String deviceId,
            final Handler<AsyncResult<MessageSender>> creationHandler) {

        Future<MessageSender> senderTracker = Future.future();
        senderTracker.setHandler(creationHandler);
        checkConnection().compose(
                connected -> TelemetrySenderImpl.create(context, connection, tenantId, deviceId,
                        onSenderClosed -> {
                            activeSenders.remove(TelemetrySenderImpl.getTargetAddress(tenantId, deviceId));
                        },
                        senderTracker.completer()),
                senderTracker);
        return this;
    }

    /* (non-Javadoc)
     * @see org.eclipse.hono.client.HonoClient#createTelemetryConsumer(java.lang.String, java.util.function.Consumer, io.vertx.core.Handler)
     */
    @Override
    public HonoClient createTelemetryConsumer(
            final String tenantId,
            final Consumer<Message> telemetryConsumer,
            final Handler<AsyncResult<MessageConsumer>> creationHandler) {

        Future<MessageConsumer> consumerTracker = Future.future();
        consumerTracker.setHandler(creationHandler);
        checkConnection().compose(
                connected -> TelemetryConsumerImpl.create(context, connection, tenantId, connectionFactory.getPathSeparator(), telemetryConsumer, consumerTracker.completer()),
                consumerTracker);
        return this;
    }

    /* (non-Javadoc)
     * @see org.eclipse.hono.client.HonoClient#createEventConsumer(java.lang.String, java.util.function.Consumer, io.vertx.core.Handler)
     */
    @Override
    public HonoClient createEventConsumer(
            final String tenantId,
            final Consumer<Message> eventConsumer,
            final Handler<AsyncResult<MessageConsumer>> creationHandler) {

        Future<MessageConsumer> consumerTracker = Future.future();
        consumerTracker.setHandler(creationHandler);
        checkConnection().compose(
                connected -> EventConsumerImpl.create(context, connection, tenantId, connectionFactory.getPathSeparator(), eventConsumer, consumerTracker.completer()),
                consumerTracker);
        return this;
    }

    private HonoClient createEventSender(
            final String tenantId,
            final String deviceId,
            final Handler<AsyncResult<MessageSender>> creationHandler) {

        Future<MessageSender> senderTracker = Future.future();
        senderTracker.setHandler(creationHandler);
        checkConnection().compose(
                connected -> EventSenderImpl.create(context, connection, tenantId, deviceId,
                        onSenderClosed -> {
                            activeSenders.remove(EventSenderImpl.getTargetAddress(tenantId, deviceId));
                        },
                        senderTracker.completer()),
                senderTracker);
        return this;
    }

    private <T> Future<T> checkConnection() {
        if (connection == null || connection.isDisconnected()) {
            return Future.failedFuture("client is not connected to Hono (yet)");
        } else {
            return Future.succeededFuture();
        }
    }

    /* (non-Javadoc)
     * @see org.eclipse.hono.client.HonoClient#getOrCreateRegistrationClient(java.lang.String, io.vertx.core.Handler)
     */
    @Override
    public HonoClient getOrCreateRegistrationClient(
            final String tenantId,
            final Handler<AsyncResult<RegistrationClient>> resultHandler) {

        final RegistrationClient regClient = activeRegClients.get(Objects.requireNonNull(tenantId));
        if (regClient != null && regClient.isOpen()) {
            LOG.debug("reusing existing registration client for [{}]", tenantId);
            resultHandler.handle(Future.succeededFuture(regClient));
        } else {
            createRegistrationClient(tenantId, resultHandler);
        }
        return this;
    }

    /* (non-Javadoc)
     * @see org.eclipse.hono.client.HonoClient#createRegistrationClient(java.lang.String, io.vertx.core.Handler)
     */
    @Override
    public HonoClient createRegistrationClient(
            final String tenantId,
            final Handler<AsyncResult<RegistrationClient>> creationHandler) {

        Objects.requireNonNull(tenantId);
        if (connection == null || connection.isDisconnected()) {
            creationHandler.handle(Future.failedFuture("client is not connected to Hono (yet)"));
        } else {
            LOG.debug("creating new registration client for [{}]", tenantId);
            RegistrationClientImpl.create(context, connection, tenantId, creationAttempt -> {
                if (creationAttempt.succeeded()) {
                    activeRegClients.put(tenantId, creationAttempt.result());
                    LOG.debug("successfully created registration client for [{}]", tenantId);
                    creationHandler.handle(Future.succeededFuture(creationAttempt.result()));
                } else {
                    LOG.debug("failed to create registration client for [{}]", tenantId, creationAttempt.cause());
                    creationHandler.handle(Future.failedFuture(creationAttempt.cause()));
                }
            });
        }
        return this;
    }

    /* (non-Javadoc)
     * @see org.eclipse.hono.client.HonoClient#shutdown()
     */
    @Override
    public void shutdown() {
        final CountDownLatch latch = new CountDownLatch(1);
        shutdown(done -> {
            if (done.succeeded()) {
                latch.countDown();
            } else {
                LOG.error("could not close connection to server", done.cause());
            }
        });
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                LOG.error("shutdown of client timed out");
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /* (non-Javadoc)
     * @see org.eclipse.hono.client.HonoClient#shutdown(io.vertx.core.Handler)
     */
    @Override
    public void shutdown(final Handler<AsyncResult<Void>> completionHandler) {

        if (connection == null || connection.isDisconnected()) {
            LOG.info("connection to server [{}:{}] already closed", connectionFactory.getHost(), connectionFactory.getPort());
            completionHandler.handle(Future.succeededFuture());
        } else {
            context.runOnContext(close -> {
                LOG.info("closing connection to server [{}:{}]...", connectionFactory.getHost(), connectionFactory.getPort());
                connection.disconnectHandler(null); // make sure we are not trying to re-connect
                connection.closeHandler(closedCon -> {
                    if (closedCon.succeeded()) {
                        LOG.info("closed connection to server [{}:{}]", connectionFactory.getHost(), connectionFactory.getPort());
                    } else {
                        LOG.info("could not close connection to server [{}:{}]", connectionFactory.getHost(), connectionFactory.getPort(), closedCon.cause());
                    }
                    connection.disconnect();
                    if (completionHandler != null) {
                        completionHandler.handle(Future.succeededFuture());
                    }
                }).close();
            });
        }
    }
}
