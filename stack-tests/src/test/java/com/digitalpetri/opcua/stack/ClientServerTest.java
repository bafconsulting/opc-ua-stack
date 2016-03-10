/*
 * Copyright 2015 Kevin Herron
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.digitalpetri.opcua.stack;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.beust.jcommander.internal.Lists;
import com.digitalpetri.opcua.stack.client.UaTcpStackClient;
import com.digitalpetri.opcua.stack.client.config.UaTcpStackClientConfig;
import com.digitalpetri.opcua.stack.core.Stack;
import com.digitalpetri.opcua.stack.core.UaException;
import com.digitalpetri.opcua.stack.core.channel.ClientSecureChannel;
import com.digitalpetri.opcua.stack.core.security.SecurityPolicy;
import com.digitalpetri.opcua.stack.core.serialization.UaResponseMessage;
import com.digitalpetri.opcua.stack.core.types.builtin.ByteString;
import com.digitalpetri.opcua.stack.core.types.builtin.DateTime;
import com.digitalpetri.opcua.stack.core.types.builtin.ExpandedNodeId;
import com.digitalpetri.opcua.stack.core.types.builtin.ExtensionObject;
import com.digitalpetri.opcua.stack.core.types.builtin.LocalizedText;
import com.digitalpetri.opcua.stack.core.types.builtin.NodeId;
import com.digitalpetri.opcua.stack.core.types.builtin.QualifiedName;
import com.digitalpetri.opcua.stack.core.types.builtin.StatusCode;
import com.digitalpetri.opcua.stack.core.types.builtin.Variant;
import com.digitalpetri.opcua.stack.core.types.builtin.XmlElement;
import com.digitalpetri.opcua.stack.core.types.enumerated.MessageSecurityMode;
import com.digitalpetri.opcua.stack.core.types.structured.EndpointDescription;
import com.digitalpetri.opcua.stack.core.types.structured.ReadValueId;
import com.digitalpetri.opcua.stack.core.types.structured.RequestHeader;
import com.digitalpetri.opcua.stack.core.types.structured.ResponseHeader;
import com.digitalpetri.opcua.stack.core.types.structured.TestStackRequest;
import com.digitalpetri.opcua.stack.core.types.structured.TestStackResponse;
import com.digitalpetri.opcua.stack.core.util.CryptoRestrictions;
import com.digitalpetri.opcua.stack.server.config.UaTcpStackServerConfig;
import com.digitalpetri.opcua.stack.server.tcp.SocketServer;
import com.digitalpetri.opcua.stack.server.tcp.UaTcpStackServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterTest;
import org.testng.annotations.BeforeTest;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static com.digitalpetri.opcua.stack.core.types.builtin.unsigned.Unsigned.ubyte;
import static com.digitalpetri.opcua.stack.core.types.builtin.unsigned.Unsigned.uint;
import static com.digitalpetri.opcua.stack.core.types.builtin.unsigned.Unsigned.ulong;
import static com.digitalpetri.opcua.stack.core.types.builtin.unsigned.Unsigned.ushort;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;

public class ClientServerTest extends SecurityFixture {

    @DataProvider
    public Object[][] getVariants() {
        return new Object[][]{
                {new Variant(true)},
                {new Variant((byte) 1)},
                {new Variant(ubyte(1))},
                {new Variant((short) 1)},
                {new Variant(ushort(1))},
                {new Variant(1)},
                {new Variant(uint(1))},
                {new Variant(1L)},
                {new Variant(ulong(1L))},
                {new Variant(3.14f)},
                {new Variant(6.12d)},
                {new Variant("hello, world")},
                {new Variant(DateTime.now())},
                {new Variant(UUID.randomUUID())},
                {new Variant(ByteString.of(new byte[]{1, 2, 3, 4}))},
                {new Variant(new XmlElement("<tag>hello</tag>"))},
                {new Variant(new NodeId(0, 42))},
                {new Variant(new ExpandedNodeId(1, 42, "uri", 1))},
                {new Variant(StatusCode.GOOD)},
                {new Variant(new QualifiedName(0, "QualifiedName"))},
                {new Variant(LocalizedText.english("LocalizedText"))},
                {new Variant(ExtensionObject.encode(new ReadValueId(NodeId.NULL_VALUE, uint(1), null, new QualifiedName(0, "DataEncoding"))))},
        };
    }

    private Logger logger = LoggerFactory.getLogger(getClass());

    private EndpointDescription[] endpoints;

    UaTcpStackServer server;

    @BeforeTest
    public void setUpClientServer() throws Exception {
        super.setUp();

        CryptoRestrictions.remove();
        // ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID);

        UaTcpStackServerConfig config = UaTcpStackServerConfig.builder()
                .setServerName("test")
                .setCertificateManager(serverCertificateManager)
                .setCertificateValidator(serverCertificateValidator)
                .build();

        server = new UaTcpStackServer(config);

        server.addEndpoint("opc.tcp://localhost:12685/test", null)
                .addEndpoint("opc.tcp://localhost:12685/test", null, serverCertificate, SecurityPolicy.Basic128Rsa15, MessageSecurityMode.Sign)
                .addEndpoint("opc.tcp://localhost:12685/test", null, serverCertificate, SecurityPolicy.Basic256, MessageSecurityMode.Sign)
                .addEndpoint("opc.tcp://localhost:12685/test", null, serverCertificate, SecurityPolicy.Basic256Sha256, MessageSecurityMode.Sign)
                .addEndpoint("opc.tcp://localhost:12685/test", null, serverCertificate, SecurityPolicy.Basic128Rsa15, MessageSecurityMode.SignAndEncrypt)
                .addEndpoint("opc.tcp://localhost:12685/test", null, serverCertificate, SecurityPolicy.Basic256, MessageSecurityMode.SignAndEncrypt)
                .addEndpoint("opc.tcp://localhost:12685/test", null, serverCertificate, SecurityPolicy.Basic256Sha256, MessageSecurityMode.SignAndEncrypt);

        server.addRequestHandler(TestStackRequest.class, (service) -> {
            TestStackRequest request = service.getRequest();

            ResponseHeader header = new ResponseHeader(
                    DateTime.now(),
                    request.getRequestHeader().getRequestHandle(),
                    StatusCode.GOOD,
                    null, null, null
            );

            service.setResponse(new TestStackResponse(header, request.getInput()));
        });

        server.startup();

        endpoints = UaTcpStackClient.getEndpoints("opc.tcp://localhost:12685/test").get();
    }

    @AfterTest
    public void tearDownClientServer() throws Exception {
        SocketServer.shutdownAll();
        Stack.sharedEventLoop().shutdownGracefully();
    }

    @Test(dataProvider = "getVariants")
    public void testClientServerRoundTrip_TestStack_NoSecurity(Variant input) throws Exception {
        EndpointDescription endpoint = endpoints[0];

        logger.info("SecurityPolicy={}, MessageSecurityMode={}, input={}",
                SecurityPolicy.fromUri(endpoint.getSecurityPolicyUri()), endpoint.getSecurityMode(), input);

        UaTcpStackClient client = createClient(endpoint);

        connectAndTest(input, client);
    }

    @Test(dataProvider = "getVariants")
    public void testClientServerRoundTrip_TestStack_Basic128Rsa15_Sign(Variant input) throws Exception {
        EndpointDescription endpoint = endpoints[1];

        logger.info("SecurityPolicy={}, MessageSecurityMode={}, input={}",
                SecurityPolicy.fromUri(endpoint.getSecurityPolicyUri()), endpoint.getSecurityMode(), input);

        UaTcpStackClient client = createClient(endpoint);

        connectAndTest(input, client);
    }

    @Test(dataProvider = "getVariants")
    public void testClientServerRoundTrip_TestStack_Basic256_Sign(Variant input) throws Exception {
        EndpointDescription endpoint = endpoints[2];

        logger.info("SecurityPolicy={}, MessageSecurityMode={}, input={}",
                SecurityPolicy.fromUri(endpoint.getSecurityPolicyUri()), endpoint.getSecurityMode(), input);

        UaTcpStackClient client = createClient(endpoint);

        connectAndTest(input, client);
    }

    @Test(dataProvider = "getVariants")
    public void testClientServerRoundTrip_TestStack_Basic256Sha256_Sign(Variant input) throws Exception {
        EndpointDescription endpoint = endpoints[3];

        logger.info("SecurityPolicy={}, MessageSecurityMode={}, input={}",
                SecurityPolicy.fromUri(endpoint.getSecurityPolicyUri()), endpoint.getSecurityMode(), input);

        UaTcpStackClient client = createClient(endpoint);

        connectAndTest(input, client);
    }

    @Test(dataProvider = "getVariants")
    public void testClientServerRoundTrip_TestStack_Basic128Rsa15_SignAndEncrypt(Variant input) throws Exception {
        EndpointDescription endpoint = endpoints[4];

        logger.info("SecurityPolicy={}, MessageSecurityMode={}, input={}",
                SecurityPolicy.fromUri(endpoint.getSecurityPolicyUri()), endpoint.getSecurityMode(), input);

        UaTcpStackClient client = createClient(endpoint);

        connectAndTest(input, client);
    }

    @Test(dataProvider = "getVariants")
    public void testClientServerRoundTrip_TestStack_Basic256_SignAndEncrypt(Variant input) throws Exception {
        EndpointDescription endpoint = endpoints[5];

        logger.info("SecurityPolicy={}, MessageSecurityMode={}, input={}",
                SecurityPolicy.fromUri(endpoint.getSecurityPolicyUri()), endpoint.getSecurityMode(), input);

        UaTcpStackClient client = createClient(endpoint);

        connectAndTest(input, client);
    }

    @Test(dataProvider = "getVariants")
    public void testClientServerRoundTrip_TestStack_Basic256Sha256_SignAndEncrypt(Variant input) throws Exception {
        EndpointDescription endpoint = endpoints[6];

        logger.info("SecurityPolicy={}, MessageSecurityMode={}, input={}",
                SecurityPolicy.fromUri(endpoint.getSecurityPolicyUri()), endpoint.getSecurityMode(), input);

        UaTcpStackClient client = createClient(endpoint);

        connectAndTest(input, client);
    }

    @Test
    public void testClientStateMachine() throws Exception {
        EndpointDescription endpoint = endpoints[0];

        Variant input = new Variant(42);
        logger.info("SecurityPolicy={}, MessageSecurityMode={}, input={}",
                SecurityPolicy.fromUri(endpoint.getSecurityPolicyUri()), endpoint.getSecurityMode(), input);

        UaTcpStackClient client = createClient(endpoint);

        // Test some where we don't wait for disconnect to finish...
        for (int i = 0; i < 1000; i++) {
            RequestHeader header = new RequestHeader(
                    NodeId.NULL_VALUE,
                    DateTime.now(),
                    uint(i), uint(0), null, uint(60000), null);

            TestStackRequest request = new TestStackRequest(header, uint(i), i, input);

            logger.info("sending request: {}", request);
            UaResponseMessage response = client.sendRequest(request).get();
            logger.info("got response: {}", response);

            client.disconnect();
        }

        // and test some where we DO wait...
        for (int i = 0; i < 1000; i++) {
            RequestHeader header = new RequestHeader(
                    NodeId.NULL_VALUE,
                    DateTime.now(),
                    uint(i), uint(0), null, uint(60000), null);

            TestStackRequest request = new TestStackRequest(header, uint(i), i, input);

            logger.info("sending request: {}", request);
            UaResponseMessage response = client.sendRequest(request).get();
            logger.info("got response: {}", response);

            client.disconnect().get();
        }
    }

    @Test
    public void testClientDisconnect() throws Exception {
        EndpointDescription endpoint = endpoints[0];
        Variant input = new Variant(42);

        logger.info("SecurityPolicy={}, MessageSecurityMode={}, input={}",
            SecurityPolicy.fromUri(endpoint.getSecurityPolicyUri()), endpoint.getSecurityMode(), input);

        UaTcpStackClient client = createClient(endpoint);

        RequestHeader header = new RequestHeader(
            NodeId.NULL_VALUE,
            DateTime.now(),
            uint(0), uint(0), null, uint(60000), null);

        TestStackRequest request = new TestStackRequest(header, uint(0), 0, input);

        logger.info("sending request: {}", request);
        UaResponseMessage response0 = client.sendRequest(request).get();
        logger.info("got response: {}", response0);

        // client doesn't wait for server to close the channel, it
        // closes after flushing the message, so we need to delay
        // here for the purpose of testing. we close the secure
        // channel and sleep to give the server time to act, then
        // assert that the server no longer knows about it.
        long secureChannelId = client.getChannelFuture().get().getChannelId();
        client.disconnect().get();
        Thread.sleep(100);
        logger.info("asserting channel closed...");
        assertNull(server.getSecureChannel(secureChannelId));
    }

    @Test
    public void testClientReconnect() throws Exception {
        EndpointDescription endpoint = endpoints[0];
        Variant input = new Variant(42);

        logger.info("SecurityPolicy={}, MessageSecurityMode={}, input={}",
                SecurityPolicy.fromUri(endpoint.getSecurityPolicyUri()), endpoint.getSecurityMode(), input);

        UaTcpStackClient client = createClient(endpoint);

        RequestHeader header = new RequestHeader(
                NodeId.NULL_VALUE,
                DateTime.now(),
                uint(0), uint(0), null, uint(60000), null);

        TestStackRequest request = new TestStackRequest(header, uint(0), 0, input);

        logger.info("sending request: {}", request);
        UaResponseMessage response0 = client.sendRequest(request).get();
        logger.info("got response: {}", response0);

        logger.info("initiating a reconnect by closing channel in server...");
        long secureChannelId = client.getChannelFuture().get().getChannelId();
        server.getSecureChannel(secureChannelId).attr(UaTcpStackServer.BoundChannelKey).get().close().await();

        logger.info("sending request: {}", request);
        UaResponseMessage response1 = client.sendRequest(request).get();
        logger.info("got response: {}", response1);

        client.disconnect().get();
    }

    @Test
    public void testClientReconnect_InvalidSecureChannel() throws Exception {
        EndpointDescription endpoint = endpoints[0];
        Variant input = new Variant(42);

        logger.info("SecurityPolicy={}, MessageSecurityMode={}, input={}",
                SecurityPolicy.fromUri(endpoint.getSecurityPolicyUri()), endpoint.getSecurityMode(), input);

        UaTcpStackClient client = createClient(endpoint);

        RequestHeader header = new RequestHeader(
                NodeId.NULL_VALUE,
                DateTime.now(),
                uint(0), uint(0), null, uint(60000), null);

        TestStackRequest request = new TestStackRequest(header, uint(0), 0, input);

        logger.info("sending request: {}", request);
        UaResponseMessage response0 = client.sendRequest(request).get();
        logger.info("got response: {}", response0);

        // Get our original valid secure channel, then sabotage it, then cause a disconnect.
        // The end effect is that we reconnect with an invalid secure channel id.
        ClientSecureChannel secureChannel = client.getChannelFuture().get();
        long secureChannelId = secureChannel.getChannelId();
        secureChannel.setChannelId(Long.MAX_VALUE);
        server.getSecureChannel(secureChannelId).attr(UaTcpStackServer.BoundChannelKey).get().close().await();
        Thread.sleep(500);

        logger.info("sending request: {}", request);
        UaResponseMessage response1 = client.sendRequest(request).get();
        logger.info("got response: {}", response1);
    }

    private UaTcpStackClient createClient(EndpointDescription endpoint) throws UaException {
        UaTcpStackClientConfig config = UaTcpStackClientConfig.builder()
                .setEndpoint(endpoint)
                .setKeyPair(clientKeyPair)
                .setCertificate(clientCertificate)
                .build();

        return new UaTcpStackClient(config);
    }

    private void connectAndTest(Variant input, UaTcpStackClient client) throws InterruptedException, java.util.concurrent.ExecutionException {
        client.connect().get();

        List<TestStackRequest> requests = Lists.newArrayList();
        List<CompletableFuture<? extends UaResponseMessage>> futures = Lists.newArrayList();

        for (int i = 0; i < 1000; i++) {
            RequestHeader header = new RequestHeader(
                    NodeId.NULL_VALUE,
                    DateTime.now(),
                    uint(i), uint(0), null,
                    uint(60000), null);

            requests.add(new TestStackRequest(header, uint(i), i, input));

            CompletableFuture<TestStackResponse> future = new CompletableFuture<>();

            future.thenAccept((response) -> assertEquals(response.getOutput(), input));

            futures.add(future);
        }

        client.sendRequests(requests, futures);

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[futures.size()])).get();

        client.disconnect().get();
        Thread.sleep(100);
    }

}
