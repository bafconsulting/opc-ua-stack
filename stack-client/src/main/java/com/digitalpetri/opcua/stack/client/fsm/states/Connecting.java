package com.digitalpetri.opcua.stack.client.fsm.states;

import java.util.concurrent.CompletableFuture;

import com.digitalpetri.opcua.stack.client.UaTcpStackClient;
import com.digitalpetri.opcua.stack.client.fsm.ConnectionEvent;
import com.digitalpetri.opcua.stack.client.fsm.ConnectionState;
import com.digitalpetri.opcua.stack.client.fsm.ConnectionStateFsm;
import com.digitalpetri.opcua.stack.core.StatusCodes;
import com.digitalpetri.opcua.stack.core.UaException;
import com.digitalpetri.opcua.stack.core.channel.ClientSecureChannel;
import com.digitalpetri.opcua.stack.core.types.builtin.StatusCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Connecting implements ConnectionState {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private volatile ClientSecureChannel secureChannel;
    private final CompletableFuture<ClientSecureChannel> channelFuture;

    public Connecting(CompletableFuture<ClientSecureChannel> channelFuture) {
        this.channelFuture = channelFuture;
    }

    @Override
    public CompletableFuture<Void> activate(ConnectionEvent event, ConnectionStateFsm fsm) {
        CompletableFuture<Void> future = new CompletableFuture<>();

        connect(fsm, true, new CompletableFuture<>()).whenComplete((sc, ex) -> {
            if (sc != null) {
                secureChannel = sc;
                fsm.handleEvent(ConnectionEvent.CONNECT_SUCCEEDED);
            } else {
                channelFuture.completeExceptionally(ex);
                fsm.handleEvent(ConnectionEvent.CONNECT_FAILED);
            }

            future.complete(null);
        });

        return future;
    }

    private CompletableFuture<ClientSecureChannel> connect(ConnectionStateFsm fsm,
                                                           boolean initialAttempt,
                                                           CompletableFuture<ClientSecureChannel> future) {

        UaTcpStackClient.bootstrap(fsm.getClient(), 0L).whenComplete((sc, ex) -> {
            if (sc != null) {
                logger.debug("Channel bootstrap succeeded: localAddress={}, remoteAddress={}",
                        sc.getChannel().localAddress(), sc.getChannel().remoteAddress());

                future.complete(sc);
            } else {
                logger.debug("Channel bootstrap failed: {}", ex.getMessage(), ex);

                StatusCode statusCode = UaException.extract(ex)
                        .map(UaException::getStatusCode)
                        .orElse(StatusCode.BAD);

                boolean secureChannelError =
                        statusCode.getValue() == StatusCodes.Bad_TcpSecureChannelUnknown ||
                                statusCode.getValue() == StatusCodes.Bad_SecureChannelIdInvalid;

                if (initialAttempt && secureChannelError) {
                    // Try again if bootstrapping failed because we couldn't re-open the previous channel.
                    logger.debug("Previous channel unusable, retrying...");
                    connect(fsm, false, future);
                } else {
                    future.completeExceptionally(ex);
                }
            }
        });

        return future;
    }

    @Override
    public CompletableFuture<Void> deactivate(ConnectionEvent event, ConnectionStateFsm fsm) {
        return CF_VOID_COMPLETED;
    }

    @Override
    public ConnectionState transition(ConnectionEvent event, ConnectionStateFsm fsm) {
        switch (event) {
            case CONNECT_SUCCEEDED:
                return new Connected(secureChannel, channelFuture);

            case CONNECT_FAILED:
                return new Idle();
        }

        return this;
    }

    @Override
    public CompletableFuture<ClientSecureChannel> getSecureChannel() {
        return channelFuture;
    }

    @Override
    public String toString() {
        return "Connecting{}";
    }

}
