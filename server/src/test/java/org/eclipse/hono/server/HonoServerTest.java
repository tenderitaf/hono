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

import static org.hamcrest.CoreMatchers.*;
import static org.junit.Assert.*;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.*;

import java.security.Principal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.vertx.core.Future;
import org.apache.qpid.proton.amqp.transport.Target;
import org.apache.qpid.proton.engine.Record;
import org.apache.qpid.proton.engine.impl.RecordImpl;
import org.eclipse.hono.TestSupport;
import org.eclipse.hono.authorization.AuthorizationConstants;
import org.eclipse.hono.authorization.Permission;
import org.eclipse.hono.config.HonoConfigProperties;
import org.eclipse.hono.telemetry.TelemetryConstants;
import org.eclipse.hono.util.Constants;
import org.eclipse.hono.util.ResourceIdentifier;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import io.vertx.core.Context;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.json.JsonObject;
import io.vertx.proton.ProtonConnection;
import io.vertx.proton.ProtonReceiver;

/**
 * Unit tests for Hono Server.
 *
 */
public class HonoServerTest {

    private static final String CON_ID = "connection-id";

    private Vertx vertx;
    private EventBus eventBus;

    /**
     * Sets up common mock objects used by the test cases.
     */
    @Before
    public void initMocks() {
        eventBus = mock(EventBus.class);
        vertx = mock(Vertx.class);
        when(vertx.eventBus()).thenReturn(eventBus);
    }

    private HonoServer createServer(final Endpoint telemetryEndpoint) {

        HonoServer server = new HonoServer();
        if (telemetryEndpoint != null) {
            server.addEndpoint(telemetryEndpoint);
        }
        server.init(vertx, mock(Context.class));
        return server;
    }

    @Test
    public void testHandleReceiverOpenForwardsToTelemetryEndpoint() throws InterruptedException {

        // GIVEN a server with a telemetry endpoint
        final String targetAddress = TelemetryConstants.NODE_ADDRESS_TELEMETRY_PREFIX + Constants.DEFAULT_TENANT;
        final CountDownLatch linkEstablished = new CountDownLatch(1);
        final Endpoint telemetryEndpoint = new BaseEndpoint(vertx) {

            @Override
            public String getName() {
                return TelemetryConstants.TELEMETRY_ENDPOINT;
            }

            @Override
            public void onLinkAttach(final ProtonReceiver receiver, final ResourceIdentifier targetResource) {
                linkEstablished.countDown();
            }
        };
        HonoServer server = createServer(telemetryEndpoint);
        final JsonObject authMsg = AuthorizationConstants.getAuthorizationMsg(Constants.SUBJECT_ANONYMOUS, targetAddress, Permission.WRITE.toString());
        TestSupport.expectReplyForMessage(eventBus, server.getAuthServiceAddress(), authMsg, AuthorizationConstants.ALLOWED);

        // WHEN a client connects to the server using a telemetry address
        final Target target = getTarget(targetAddress);
        final ProtonReceiver receiver = mock(ProtonReceiver.class);
        when(receiver.getRemoteTarget()).thenReturn(target);
        when(receiver.attachments()).thenReturn(mock(Record.class));
        server.handleReceiverOpen(newAuthenticatedConnection(Constants.SUBJECT_ANONYMOUS), receiver);

        // THEN the server delegates link establishment to the telemetry endpoint 
        assertTrue(linkEstablished.await(1, TimeUnit.SECONDS));
    }

    @Test
    public void testHandleReceiverOpenRejectsUnauthorizedClient() throws InterruptedException {

        final String UNAUTHORIZED_SUBJECT = "unauthorized";
        // GIVEN a server with a telemetry endpoint
        final String restrictedTargetAddress = TelemetryConstants.NODE_ADDRESS_TELEMETRY_PREFIX + "RESTRICTED_TENANT";

        final Endpoint telemetryEndpoint = mock(Endpoint.class);
        when(telemetryEndpoint.getName()).thenReturn(TelemetryConstants.TELEMETRY_ENDPOINT);
        HonoServer server = createServer(telemetryEndpoint);
        final JsonObject authMsg = AuthorizationConstants.getAuthorizationMsg(UNAUTHORIZED_SUBJECT, restrictedTargetAddress, Permission.WRITE.toString());
        TestSupport.expectReplyForMessage(eventBus, server.getAuthServiceAddress(), authMsg, AuthorizationConstants.DENIED);

        // WHEN a client connects to the server using a telemetry address for a tenant it is not authorized to write to
        final CountDownLatch linkClosed = new CountDownLatch(1);
        final Target target = getTarget(restrictedTargetAddress);
        final ProtonReceiver receiver = mock(ProtonReceiver.class);
        when(receiver.getRemoteTarget()).thenReturn(target);
        when(receiver.setCondition(any())).thenReturn(receiver);
        when(receiver.close()).thenAnswer(invocation -> {
            linkClosed.countDown();
            return receiver;
        });
        server.handleReceiverOpen(newAuthenticatedConnection(UNAUTHORIZED_SUBJECT), receiver);

        // THEN the server closes the link with the client
        assertTrue(linkClosed.await(1, TimeUnit.SECONDS));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void testServerPublishesConnectionIdOnClientDisconnect() {

        // GIVEN a Hono server
        HonoServer server = createServer(null);

        // WHEN a client connects to the server
        ArgumentCaptor<Handler> closeHandlerCaptor = ArgumentCaptor.forClass(Handler.class);
        final ProtonConnection con = newAuthenticatedConnection(Constants.SUBJECT_ANONYMOUS);
        when(con.disconnectHandler(closeHandlerCaptor.capture())).thenReturn(con);

        server.handleRemoteConnectionOpen(con);

        // THEN a handler is registered with the connection that publishes
        // an event on the event bus when the client disconnects
        closeHandlerCaptor.getValue().handle(con);
        verify(eventBus).publish(Constants.EVENT_BUS_ADDRESS_CONNECTION_CLOSED, CON_ID);
    }

    /**
     * Verifies that a Hono server will bind to the default AMQPS port only
     * when using a default configuration with only the key store property being set.
     */
    @Test
    public void checkSecurePortAutoSelect() {

        // GIVEN a configuration with a key store set
        HonoConfigProperties configProperties = new HonoConfigProperties();
        configProperties.setKeyStorePath("/etc/hono/certs/honoKeyStore.p12");

        // WHEN using this configuration to determine the server's port configuration
        // secure port config: no port set -> secure IANA port selected
        Future<Void> portConfigurationTracker = Future.future();
        HonoServer server = createServer(null);
        server.setHonoConfiguration(configProperties);
        server.determinePortConfigurations(portConfigurationTracker);

        // THEN the default AMQPS port is selected and no insecure port will be opened
        assertTrue(portConfigurationTracker.succeeded());
        assertTrue(server.isOpenSecurePort());
        assertThat(server.getPort(), is(Constants.PORT_AMQPS));
        assertFalse(server.isOpenInsecurePort());
    }

    /**
     * Verifies that a Hono server will bind to the configured secure port only
     * when using a default configuration with only the key store and the port property being set.
     */
    @Test
    public void checkSecurePortExplicitlySet() {

        // GIVEN a configuration with a key store and a secure port being set
        HonoConfigProperties configProperties = new HonoConfigProperties();
        configProperties.setKeyStorePath("/etc/hono/certs/honoKeyStore.p12");
        configProperties.setPort(8989);

        // WHEN using this configuration to determine the server's port configuration
        // secure port config: explicit port set -> port used
        Future<Void> portConfigurationTracker = Future.future();
        HonoServer server = createServer(null);
        server.setHonoConfiguration(configProperties);
        server.determinePortConfigurations(portConfigurationTracker);

        // THEN the configured port is used and no insecure port will be opened
        assertTrue(portConfigurationTracker.succeeded());
        assertTrue(server.isOpenSecurePort());
        assertThat(server.getPort(), is(8989));
        assertFalse(server.isOpenInsecurePort());
    }

    /**
     * Verifies that a Hono server will not be able to start
     * when using a default configuration with not key store being set.
     */
    @Test
    public void checkNoPortsSet() {

        // GIVEN a default configuration with no key store being set
        HonoConfigProperties configProperties = new HonoConfigProperties();

        // WHEN using this configuration to determine the server's port configuration
        Future<Void> portConfigurationTracker = Future.future();
        HonoServer server = createServer(null);
        server.setHonoConfiguration(configProperties);
        server.determinePortConfigurations(portConfigurationTracker);

        // THEN the port configuration fails
        assertTrue(portConfigurationTracker.failed());
    }

    /**
     * Verifies that a Hono server will bind to the default AMQP port only
     * when using a default configuration with the insecure port being enabled.
     */
    @Test
    public void checkInsecureOnlyPort() {

        // GIVEN a default configuration with insecure port being enabled but no key store being set
        HonoConfigProperties configProperties = new HonoConfigProperties();
        configProperties.setInsecurePortEnabled(true);

        // WHEN using this configuration to determine the server's port configuration
        Future<Void> portConfigurationTracker = Future.future();
        HonoServer server = createServer(null);
        server.setHonoConfiguration(configProperties);
        server.determinePortConfigurations(portConfigurationTracker);

        // THEN the server will bind to the default AMQP port only
        assertTrue(portConfigurationTracker.succeeded());
        assertFalse(server.isOpenSecurePort());
        assertTrue(server.isOpenInsecurePort());
        assertThat(server.getInsecurePort(), is(Constants.PORT_AMQP));
    }

    /**
     * Verifies that a Hono server will bind to a configured insecure port only
     * when using a default configuration with the insecure port property being set.
     */
    @Test
    public void checkInsecureOnlyPortExplicitlySet() {

        // GIVEN a default configuration with insecure port being set to a specific port.
        HonoConfigProperties configProperties = new HonoConfigProperties();
        configProperties.setInsecurePortEnabled(true);
        configProperties.setInsecurePort(8888);

        // WHEN using this configuration to determine the server's port configuration
        Future<Void> portConfigurationTracker = Future.future();
        HonoServer server = createServer(null);
        server.setHonoConfiguration(configProperties);
        server.determinePortConfigurations(portConfigurationTracker);

        // THEN the server will bind to the configured insecure port only
        assertTrue(portConfigurationTracker.succeeded());
        assertFalse(server.isOpenSecurePort());
        assertTrue(server.isOpenInsecurePort());
        assertThat(server.getInsecurePort(), is(8888));
    }

    /**
     * Verifies that a Hono server will bind to both the default AMQP and AMQPS ports
     * when using a default configuration with the insecure port being enabled and the
     * key store property being set.
     */
    @Test
    public void checkBothPortsOpen() {

        // GIVEN a default configuration with insecure port being enabled and a key store being set.
        HonoConfigProperties configProperties = new HonoConfigProperties();
        configProperties.setInsecurePortEnabled(true);
        configProperties.setKeyStorePath("/etc/hono/certs/honoKeyStore.p12");

        // WHEN using this configuration to determine the server's port configuration
        Future<Void> portConfigurationTracker = Future.future();
        HonoServer server = createServer(null);
        server.setHonoConfiguration(configProperties);
        server.determinePortConfigurations(portConfigurationTracker);

        // THEN the server will bind to both the default AMQP and AMQPS ports
        assertTrue(portConfigurationTracker.succeeded());
        assertTrue(server.isOpenSecurePort());
        assertThat(server.getPort(), is(Constants.PORT_AMQPS));
        assertTrue(server.isOpenInsecurePort());
        assertThat(server.getInsecurePort(), is(Constants.PORT_AMQP));
    }

    /**
     * Verifies that a Hono server will not start
     * when using a default configuration with both secure and insecure ports being enabled and
     * set to the same port number.
     */
    @Test
    public void checkBothPortsSetToSame() {

        // GIVEN a default configuration with both the insecure port and the secure port
        // being set to the same value.
        HonoConfigProperties configProperties = new HonoConfigProperties();
        configProperties.setInsecurePortEnabled(true);
        configProperties.setKeyStorePath("/etc/hono/certs/honoKeyStore.p12");
        configProperties.setInsecurePort(8888);
        configProperties.setPort(8888);

        // WHEN using this configuration to determine the server's port configuration
        Future<Void> portConfigurationTracker = Future.future();
        HonoServer server = createServer(null);
        server.setHonoConfiguration(configProperties);
        server.determinePortConfigurations(portConfigurationTracker);

        // THEN port configuration fails
        assertTrue(portConfigurationTracker.failed());
    }

    private static Target getTarget(final String targetAddress) {
        Target result = mock(Target.class);
        when(result.getAddress()).thenReturn(targetAddress);
        return result;
    }

    private static ProtonConnection newAuthenticatedConnection(final String name) {
        final Record attachments = new RecordImpl();
        attachments.set(Constants.KEY_CONNECTION_ID, String.class, CON_ID);
        attachments.set(Constants.KEY_CLIENT_PRINCIPAL, Principal.class, new Principal() {

            @Override
            public String getName() {
                return name;
            }
        });
        final ProtonConnection con = mock(ProtonConnection.class);
        when(con.attachments()).thenReturn(attachments);
        when(con.getRemoteContainer()).thenReturn("test-client");
        return con;
    }
}
