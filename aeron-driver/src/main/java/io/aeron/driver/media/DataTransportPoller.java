/*
 * Copyright 2014-2020 Real Logic Limited.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron.driver.media;

import io.aeron.driver.Configuration;
import io.aeron.driver.DriverConductorProxy;
import io.aeron.protocol.DataHeaderFlyweight;
import io.aeron.protocol.RttMeasurementFlyweight;
import io.aeron.protocol.SetupFlyweight;
import org.agrona.BufferUtil;
import org.agrona.CloseHelper;
import org.agrona.ErrorHandler;
import org.agrona.LangUtil;
import org.agrona.collections.ArrayUtil;
import org.agrona.concurrent.UnsafeBuffer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;

import static io.aeron.logbuffer.FrameDescriptor.frameType;
import static io.aeron.protocol.HeaderFlyweight.*;
import static org.agrona.BitUtil.CACHE_LINE_LENGTH;

/**
 * Encapsulates the polling of data {@link UdpChannelTransport}s using whatever means provides the lowest latency.
 */
public class DataTransportPoller extends UdpTransportPoller
{
    private final ByteBuffer byteBuffer = BufferUtil.allocateDirectAligned(
        Configuration.MAX_UDP_PAYLOAD_LENGTH, CACHE_LINE_LENGTH);
    private final UnsafeBuffer unsafeBuffer = new UnsafeBuffer(byteBuffer);
    private final DataHeaderFlyweight dataMessage = new DataHeaderFlyweight(unsafeBuffer);
    private final SetupFlyweight setupMessage = new SetupFlyweight(unsafeBuffer);
    private final RttMeasurementFlyweight rttMeasurement = new RttMeasurementFlyweight(unsafeBuffer);
    private ChannelAndTransport[] channelAndTransports = new ChannelAndTransport[0];

    public DataTransportPoller(final ErrorHandler errorHandler)
    {
        super(errorHandler);
    }

    public void close()
    {
        final DataTransportPoller poller = this;
        for (final ChannelAndTransport channelEndpoint : channelAndTransports)
        {
            final ReceiveChannelEndpoint receiveChannelEndpoint = channelEndpoint.channelEndpoint;
            CloseHelper.close(errorHandler, () -> receiveChannelEndpoint.closeMultiRcvDestination(poller));
            CloseHelper.close(errorHandler, receiveChannelEndpoint);
        }

        super.close();
    }

    public int pollTransports()
    {
        int bytesReceived = 0;
        try
        {
            if (channelAndTransports.length <= ITERATION_THRESHOLD)
            {
                for (final ChannelAndTransport channelAndTransport : channelAndTransports)
                {
                    bytesReceived += poll(channelAndTransport);
                }
            }
            else
            {
                selector.selectNow();

                final SelectionKey[] keys = selectedKeySet.keys();
                for (int i = 0, length = selectedKeySet.size(); i < length; i++)
                {
                    bytesReceived += poll((ChannelAndTransport)keys[i].attachment());
                }

                selectedKeySet.reset();
            }
        }
        catch (final IOException ex)
        {
            LangUtil.rethrowUnchecked(ex);
        }

        return bytesReceived;
    }

    public SelectionKey registerForRead(final UdpChannelTransport transport)
    {
        return registerForRead((ReceiveChannelEndpoint)transport, transport, 0);
    }

    public SelectionKey registerForRead(
        final ReceiveChannelEndpoint channelEndpoint, final UdpChannelTransport transport, final int transportIndex)
    {
        SelectionKey key = null;
        try
        {
            final ChannelAndTransport channelAndTransport = new ChannelAndTransport(
                channelEndpoint, transport, transportIndex);

            key = transport.receiveDatagramChannel().register(selector, SelectionKey.OP_READ, channelAndTransport);
            channelAndTransports = ArrayUtil.add(channelAndTransports, channelAndTransport);
        }
        catch (final ClosedChannelException ex)
        {
            LangUtil.rethrowUnchecked(ex);
        }

        return key;
    }

    public void cancelRead(final UdpChannelTransport transport)
    {
        cancelRead((ReceiveChannelEndpoint)transport, transport);
    }

    public void cancelRead(final ReceiveChannelEndpoint channelEndpoint, final UdpChannelTransport transport)
    {
        final ChannelAndTransport[] transports = this.channelAndTransports;
        int index = ArrayUtil.UNKNOWN_INDEX;

        for (int i = 0, length = transports.length; i < length; i++)
        {
            if (channelEndpoint == transports[i].channelEndpoint && transport == transports[i].transport)
            {
                index = i;
                break;
            }
        }

        if (index != ArrayUtil.UNKNOWN_INDEX)
        {
            channelAndTransports = ArrayUtil.remove(transports, index);
        }
    }

    public void checkForReResolutions(final long nowNs, final DriverConductorProxy conductorProxy)
    {
        for (final ChannelAndTransport channelAndTransport : channelAndTransports)
        {
            channelAndTransport.channelEndpoint.checkForReResolution(nowNs, conductorProxy);
        }
    }

    private int poll(final ChannelAndTransport channelAndTransport)
    {
        int bytesReceived = 0;
        final InetSocketAddress srcAddress = channelAndTransport.transport.receive(byteBuffer);

        if (null != srcAddress)
        {
            final int length = byteBuffer.position();
            final ReceiveChannelEndpoint channelEndpoint = channelAndTransport.channelEndpoint;

            if (channelEndpoint.isValidFrame(unsafeBuffer, length))
            {
                channelEndpoint.receiveHook(unsafeBuffer, length, srcAddress);
                final int transportIndex = channelAndTransport.transportIndex;

                final int frameType = frameType(unsafeBuffer, 0);
                if (HDR_TYPE_DATA == frameType || HDR_TYPE_PAD == frameType)
                {
                    bytesReceived = channelEndpoint.onDataPacket(
                        dataMessage, unsafeBuffer, length, srcAddress, transportIndex);
                }
                else if (HDR_TYPE_SETUP == frameType)
                {
                    channelEndpoint.onSetupMessage(
                        setupMessage, unsafeBuffer, length, srcAddress, transportIndex);
                }
                else if (HDR_TYPE_RTTM == frameType)
                {
                    channelEndpoint.onRttMeasurement(
                        rttMeasurement, unsafeBuffer, length, srcAddress, transportIndex);
                }
            }
        }

        return bytesReceived;
    }

    static class ChannelAndTransport
    {
        final ReceiveChannelEndpoint channelEndpoint;
        final UdpChannelTransport transport;
        final int transportIndex;

        ChannelAndTransport(
            final ReceiveChannelEndpoint channelEndpoint, final UdpChannelTransport transport, final int transportIndex)
        {
            this.channelEndpoint = channelEndpoint;
            this.transport = transport;
            this.transportIndex = transportIndex;
        }
    }
}
