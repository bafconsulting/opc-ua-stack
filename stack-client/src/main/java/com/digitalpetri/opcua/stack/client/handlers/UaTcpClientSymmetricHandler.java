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

package com.digitalpetri.opcua.stack.client.handlers;

import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.digitalpetri.opcua.stack.client.UaTcpStackClient;
import com.digitalpetri.opcua.stack.core.StatusCodes;
import com.digitalpetri.opcua.stack.core.UaException;
import com.digitalpetri.opcua.stack.core.channel.ChannelSecurity;
import com.digitalpetri.opcua.stack.core.channel.ClientSecureChannel;
import com.digitalpetri.opcua.stack.core.channel.MessageAbortedException;
import com.digitalpetri.opcua.stack.core.channel.SerializationQueue;
import com.digitalpetri.opcua.stack.core.channel.headers.HeaderDecoder;
import com.digitalpetri.opcua.stack.core.channel.headers.SymmetricSecurityHeader;
import com.digitalpetri.opcua.stack.core.channel.messages.MessageType;
import com.digitalpetri.opcua.stack.core.serialization.UaMessage;
import com.digitalpetri.opcua.stack.core.serialization.UaResponseMessage;
import com.digitalpetri.opcua.stack.core.types.builtin.unsigned.UInteger;
import com.digitalpetri.opcua.stack.core.util.BufferUtil;
import com.digitalpetri.opcua.stack.core.util.LongSequence;
import com.google.common.collect.Maps;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageCodec;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UaTcpClientSymmetricHandler extends ByteToMessageCodec<UaRequestFuture> implements HeaderDecoder {

    public static final AttributeKey<Map<Long, UaRequestFuture>> KEY_PENDING_REQUEST_FUTURES =
            AttributeKey.valueOf("pending-request-futures");

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final Map<Long, UaRequestFuture> pending;
    private final LongSequence requestId;

    private List<ByteBuf> chunkBuffers;

    private final int maxChunkCount;
    private final int maxChunkSize;

    private final UaTcpStackClient client;
    private final SerializationQueue serializationQueue;
    private final ClientSecureChannel secureChannel;
    private final CompletableFuture<ClientSecureChannel> handshakeFuture;

    public UaTcpClientSymmetricHandler(UaTcpStackClient client,
                                       SerializationQueue serializationQueue,
                                       ClientSecureChannel secureChannel,
                                       CompletableFuture<ClientSecureChannel> handshakeFuture) {
        this.client = client;
        this.serializationQueue = serializationQueue;
        this.secureChannel = secureChannel;
        this.handshakeFuture = handshakeFuture;

        maxChunkCount = serializationQueue.getParameters().getLocalMaxChunkCount();
        maxChunkSize = serializationQueue.getParameters().getLocalReceiveBufferSize();

        chunkBuffers = new ArrayList<>(maxChunkCount);

        secureChannel
                .attr(KEY_PENDING_REQUEST_FUTURES)
                .setIfAbsent(Maps.newConcurrentMap());

        pending = secureChannel.attr(KEY_PENDING_REQUEST_FUTURES).get();

        secureChannel
                .attr(ClientSecureChannel.KEY_REQUEST_ID_SEQUENCE)
                .setIfAbsent(new LongSequence(1L, UInteger.MAX_VALUE));

        requestId = secureChannel.attr(ClientSecureChannel.KEY_REQUEST_ID_SEQUENCE).get();
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        List<UaMessage> awaitingHandshake =
                ctx.channel().attr(UaTcpClientAcknowledgeHandler.KEY_AWAITING_HANDSHAKE).get();

        if (awaitingHandshake != null) {
            logger.debug("{} message(s) queued before handshake completed; sending now.", awaitingHandshake.size());
            awaitingHandshake.forEach(m -> ctx.pipeline().write(m));
            ctx.flush();

            ctx.channel().attr(UaTcpClientAcknowledgeHandler.KEY_AWAITING_HANDSHAKE).remove();
        }

        client.getExecutorService().execute(() -> handshakeFuture.complete(secureChannel));
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, UaRequestFuture message, ByteBuf out) throws Exception {
        serializationQueue.encode((binaryEncoder, chunkEncoder) -> {
            ByteBuf messageBuffer = BufferUtil.buffer();

            try {
                binaryEncoder.setBuffer(messageBuffer);
                binaryEncoder.encodeMessage(null, message.getRequest());

                List<ByteBuf> chunks = chunkEncoder.encodeSymmetric(
                        secureChannel,
                        MessageType.SecureMessage,
                        messageBuffer,
                        requestId.getAndIncrement()
                );

                long requestId = chunkEncoder.getLastRequestId();
                pending.put(requestId, message);

                // No matter how we complete, make sure the entry in pending is removed.
                // This covers the case where the request fails due to a timeout in the
                // upper layers as well as normal completion.
                message.getFuture().whenComplete(
                        (r, x) -> pending.remove(requestId));

                ctx.executor().execute(() -> {
                    chunks.forEach(c -> ctx.write(c, ctx.voidPromise()));
                    ctx.flush();
                });
            } catch (UaException e) {
                logger.error("Error encoding {}: {}", message.getClass(), e.getMessage(), e);
                ctx.close();
            } finally {
                messageBuffer.release();
            }
        });
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf buffer, List<Object> out) throws Exception {
        buffer = buffer.order(ByteOrder.LITTLE_ENDIAN);

        while (buffer.readableBytes() >= HEADER_LENGTH &&
                buffer.readableBytes() >= getMessageLength(buffer)) {

            int messageLength = getMessageLength(buffer);
            MessageType messageType = MessageType.fromMediumInt(buffer.getMedium(buffer.readerIndex()));

            switch (messageType) {
                case SecureMessage:
                    onSecureMessage(ctx, buffer.readSlice(messageLength));
                    break;

                default:
                    throw new UaException(
                            StatusCodes.Bad_TcpMessageTypeInvalid,
                            "unexpected MessageType: " + messageType);
            }
        }
    }

    private void onSecureMessage(ChannelHandlerContext ctx, ByteBuf buffer) throws UaException {
        buffer.skipBytes(3 + 1 + 4); // skip messageType, chunkType, messageSize

        long secureChannelId = buffer.readUnsignedInt();
        if (secureChannelId != secureChannel.getChannelId()) {
            throw new UaException(StatusCodes.Bad_SecureChannelIdInvalid,
                    "invalid secure channel id: " + secureChannelId);
        }

        int chunkSize = buffer.readerIndex(0).readableBytes();
        if (chunkSize > maxChunkSize) {
            throw new UaException(StatusCodes.Bad_TcpMessageTooLarge,
                    String.format("max chunk size exceeded (%s)", maxChunkSize));
        }

        chunkBuffers.add(buffer.retain());

        if (chunkBuffers.size() > maxChunkCount) {
            throw new UaException(StatusCodes.Bad_TcpMessageTooLarge,
                    String.format("max chunk count exceeded (%s)", maxChunkCount));
        }

        char chunkType = (char) buffer.getByte(3);

        if (chunkType == 'A' || chunkType == 'F') {
            final List<ByteBuf> buffersToDecode = chunkBuffers;
            chunkBuffers = new ArrayList<>(maxChunkCount);

            serializationQueue.decode((binaryDecoder, chunkDecoder) -> {
                ByteBuf decodedBuffer = null;

                try {
                    validateChunkHeaders(buffersToDecode);

                    decodedBuffer = chunkDecoder.decodeSymmetric(secureChannel, buffersToDecode);

                    binaryDecoder.setBuffer(decodedBuffer);
                    UaResponseMessage response = binaryDecoder.decodeMessage(null);

                    UaRequestFuture request = pending.remove(chunkDecoder.getLastRequestId());

                    if (request != null) {
                        client.getExecutorService().execute(
                                () -> request.getFuture().complete(response));
                    } else {
                        logger.warn("No UaRequestFuture for requestId={}", chunkDecoder.getLastRequestId());
                    }
                } catch (MessageAbortedException e) {
                    logger.debug("Received message abort chunk; error={}, reason={}", e.getStatusCode(), e.getMessage());

                    UaRequestFuture request = pending.remove(chunkDecoder.getLastRequestId());

                    if (request != null) {
                        client.getExecutorService().execute(
                                () -> request.getFuture().completeExceptionally(e));
                    } else {
                        logger.warn("No UaRequestFuture for requestId={}", chunkDecoder.getLastRequestId());
                    }
                } catch (Throwable t) {
                    logger.error("Error decoding symmetric message: {}", t.getMessage(), t);
                    ctx.close();
                    serializationQueue.pause();
                } finally {
                    if (decodedBuffer != null) {
                        decodedBuffer.release();
                    }
                    buffersToDecode.clear();
                }
            });
        }
    }

    private void validateChunkHeaders(List<ByteBuf> chunkBuffers) throws UaException {
        ChannelSecurity channelSecurity = secureChannel.getChannelSecurity();
        long currentTokenId = channelSecurity.getCurrentToken().getTokenId().longValue();
        long previousTokenId = channelSecurity.getPreviousToken()
                .map(t -> t.getTokenId().longValue())
                .orElse(-1L);

        for (ByteBuf chunkBuffer : chunkBuffers) {
            chunkBuffer.skipBytes(3 + 1 + 4 + 4); // skip messageType, chunkType, messageSize, secureChannelId

            SymmetricSecurityHeader securityHeader = SymmetricSecurityHeader.decode(chunkBuffer);

            if (securityHeader.getTokenId() != currentTokenId) {
                if (securityHeader.getTokenId() != previousTokenId) {
                    String message = String.format(
                            "received unknown secure channel token. " +
                                    "tokenId=%s, currentTokenId=%s, previousTokenId=%s",
                            securityHeader.getTokenId(), currentTokenId, previousTokenId);

                    throw new UaException(StatusCodes.Bad_SecureChannelTokenUnknown, message);
                }
            }

            chunkBuffer.readerIndex(0);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        handshakeFuture.completeExceptionally(
                new UaException(StatusCodes.Bad_ConnectionClosed, "connection closed"));

        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        logger.error("Exception caught: {}", cause.getMessage(), cause);
        ctx.close();
    }

}
