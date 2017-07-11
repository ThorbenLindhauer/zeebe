/*
 * Copyright © 2017 camunda services GmbH (info@camunda.com)
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
package io.zeebe.transport.impl;

import org.agrona.DirectBuffer;

import io.zeebe.dispatcher.FragmentHandler;
import io.zeebe.transport.RemoteAddress;
import io.zeebe.transport.ServerMessageHandler;
import io.zeebe.transport.ServerOutput;
import io.zeebe.transport.ServerRequestHandler;

public class ServerReceiveHandler implements FragmentHandler
{
    private final TransportHeaderDescriptor transportHeaderDescriptor = new TransportHeaderDescriptor();
    private final RequestResponseHeaderDescriptor requestResponseHeaderDescriptor = new RequestResponseHeaderDescriptor();
    private final RemoteAddressList remoteAddressList;
    private final ServerMessageHandler messageHandler;
    private final ServerRequestHandler requestHandler;
    protected final ServerOutput output;

    public ServerReceiveHandler(
            ServerOutput output,
            RemoteAddressList remoteAddressList,
            ServerMessageHandler messageHandler,
            ServerRequestHandler requestHandler)
    {
        this.output = output;
        this.remoteAddressList = remoteAddressList;
        this.messageHandler = messageHandler;
        this.requestHandler = requestHandler;
    }

    @Override
    public int onFragment(DirectBuffer buffer, int readOffset, int length, int streamId, boolean isMarkedFailed)
    {
        int result = FAILED_FRAGMENT_RESULT;

        final RemoteAddress remoteAddress = remoteAddressList.getByStreamId(streamId);

        transportHeaderDescriptor.wrap(buffer, readOffset);
        readOffset += TransportHeaderDescriptor.headerLength();
        length -= TransportHeaderDescriptor.headerLength();

        final int protocolId = transportHeaderDescriptor.protocolId();

        switch (protocolId)
        {
            case TransportHeaderDescriptor.REQUEST_RESPONSE:

                requestResponseHeaderDescriptor.wrap(buffer, readOffset);
                readOffset += RequestResponseHeaderDescriptor.headerLength();
                length -= RequestResponseHeaderDescriptor.headerLength();

                final long requestId = requestResponseHeaderDescriptor.requestId();
                result = requestHandler.onRequest(output, remoteAddress, buffer, readOffset, length, requestId) ? CONSUME_FRAGMENT_RESULT : POSTPONE_FRAGMENT_RESULT;

                break;

            case TransportHeaderDescriptor.FULL_DUPLEX_SINGLE_MESSAGE:

                result = messageHandler.onMessage(output, remoteAddress, buffer, readOffset, length) ? CONSUME_FRAGMENT_RESULT : POSTPONE_FRAGMENT_RESULT;

                break;

            default:
                // ignore / fail

        }

        return result;
    }
}


