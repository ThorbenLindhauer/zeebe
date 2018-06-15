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
package io.zeebe.dispatcher;

import static io.zeebe.dispatcher.impl.PositionUtil.position;
import static io.zeebe.dispatcher.impl.log.DataFrameDescriptor.*;
import static org.agrona.BitUtil.align;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import io.zeebe.dispatcher.impl.log.DataFrameDescriptor;
import io.zeebe.dispatcher.impl.log.LogBuffer;
import io.zeebe.dispatcher.impl.log.LogBufferPartition;
import io.zeebe.util.allocation.AllocatedBuffer;
import io.zeebe.util.metrics.Metric;
import io.zeebe.util.sched.ActorCondition;
import java.nio.ByteBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.Before;
import org.junit.Test;

public class SubscriptionPeekBlockTest {
  static final int A_PARTITION_LENGTH = 1024;
  static final int A_MSG_PAYLOAD_LENGTH = 10;
  static final int A_FRAGMENT_LENGTH = align(A_MSG_PAYLOAD_LENGTH + HEADER_LENGTH, FRAME_ALIGNMENT);
  static final int A_PARTITION_ID = 10;
  static final int A_STREAM_ID = 20;
  static final int ANOTHER_STREAM_ID = 25;
  static final int A_PARTITION_DATA_SECTION_OFFSET = A_PARTITION_LENGTH;

  UnsafeBuffer metadataBufferMock;
  UnsafeBuffer dataBufferMock;
  LogBufferPartition logBufferPartition;
  AtomicPosition subscriberPositionMock;
  AllocatedBuffer allocatedBufferMock;
  ByteBuffer rawBuffer;
  ByteBuffer rawBufferView;
  BlockPeek blockPeekSpy;
  Dispatcher dispatcherMock;

  Subscription subscription;
  private ActorCondition dataConsumed;
  private Metric metric;

  @Before
  public void setup() {
    dataBufferMock = mock(UnsafeBuffer.class);
    metadataBufferMock = mock(UnsafeBuffer.class);
    rawBuffer = ByteBuffer.allocate(A_PARTITION_LENGTH * 3);
    rawBufferView = rawBuffer.duplicate();
    allocatedBufferMock = mock(AllocatedBuffer.class);

    when(dataBufferMock.capacity()).thenReturn(A_PARTITION_LENGTH);
    when(allocatedBufferMock.getRawBuffer()).thenReturn(rawBuffer);
    logBufferPartition =
        new LogBufferPartition(dataBufferMock, metadataBufferMock, A_PARTITION_DATA_SECTION_OFFSET);

    subscriberPositionMock = mock(AtomicPosition.class);

    blockPeekSpy = spy(new BlockPeek());

    final LogBuffer logBuffer = mock(LogBuffer.class);
    when(logBuffer.createRawBufferView()).thenReturn(rawBufferView);

    dataConsumed = mock(ActorCondition.class);
    metric = mock(Metric.class);
    subscription =
        new Subscription(
            subscriberPositionMock,
            mock(AtomicPosition.class),
            0,
            "0",
            dataConsumed,
            logBuffer,
            metric);
  }

  @Test
  public void shouldReadSingleFragment() {
    final int fragOffset = 0;

    when(dataBufferMock.getIntVolatile(lengthOffset(fragOffset)))
        .thenReturn(framedLength(A_MSG_PAYLOAD_LENGTH));
    when(dataBufferMock.getShort(typeOffset(fragOffset))).thenReturn(TYPE_MESSAGE);
    when(dataBufferMock.getInt(streamIdOffset(fragOffset))).thenReturn(A_STREAM_ID);
    when(dataBufferMock.getByte(flagsOffset(fragOffset))).thenReturn((byte) 0);

    // when
    final int bytesAvailable =
        subscription.peekBlock(
            logBufferPartition,
            blockPeekSpy,
            A_PARTITION_ID,
            fragOffset,
            A_FRAGMENT_LENGTH,
            position(A_PARTITION_ID, A_FRAGMENT_LENGTH),
            false);

    // then
    assertThat(bytesAvailable).isEqualTo(A_FRAGMENT_LENGTH);
    // one fragment was peeked
    verify(blockPeekSpy)
        .setBlock(
            rawBufferView,
            subscriberPositionMock,
            dataConsumed,
            -1,
            fragOffset + A_PARTITION_DATA_SECTION_OFFSET,
            A_FRAGMENT_LENGTH,
            A_PARTITION_ID,
            nextFragmentOffset(fragOffset),
            1,
            metric);
    // and the position was not increased
    verifyNoMoreInteractions(subscriberPositionMock);
  }

  @Test
  public void shouldUpdatePositionOnCommit() {
    final int fragOffset = 0;
    final int flagsOffset = DataFrameDescriptor.flagsOffset(fragOffset);

    when(dataBufferMock.getIntVolatile(lengthOffset(fragOffset)))
        .thenReturn(framedLength(A_MSG_PAYLOAD_LENGTH));
    when(dataBufferMock.getShort(typeOffset(fragOffset))).thenReturn(TYPE_MESSAGE);
    when(dataBufferMock.getInt(streamIdOffset(fragOffset))).thenReturn(A_STREAM_ID);
    when(dataBufferMock.getByte(flagsOffset(fragOffset))).thenReturn((byte) 0);

    // when
    subscription.peekBlock(
        logBufferPartition,
        blockPeekSpy,
        A_PARTITION_ID,
        fragOffset,
        A_FRAGMENT_LENGTH,
        position(A_PARTITION_ID, A_FRAGMENT_LENGTH),
        false);

    blockPeekSpy.markCompleted();

    // then
    // the position was increased by the fragment length
    verify(subscriberPositionMock)
        .proposeMaxOrdered(position(A_PARTITION_ID, nextFragmentOffset(fragOffset)));
    // and the fragment was not marked as failed
    final byte flags = rawBuffer.get(A_PARTITION_DATA_SECTION_OFFSET + flagsOffset);
    assertThat(DataFrameDescriptor.flagFailed(flags)).isFalse();
  }

  @Test
  public void shouldUpdatePositionOnFailed() {
    final int fragOffset = 0;
    final int flagsOffset = DataFrameDescriptor.flagsOffset(fragOffset);

    rawBuffer.putInt(
        A_PARTITION_DATA_SECTION_OFFSET + lengthOffset(fragOffset),
        framedLength(A_MSG_PAYLOAD_LENGTH));
    when(dataBufferMock.getIntVolatile(lengthOffset(fragOffset)))
        .thenReturn(framedLength(A_MSG_PAYLOAD_LENGTH));
    when(dataBufferMock.getShort(typeOffset(fragOffset))).thenReturn(TYPE_MESSAGE);
    when(dataBufferMock.getInt(streamIdOffset(fragOffset))).thenReturn(A_STREAM_ID);
    when(dataBufferMock.getByte(flagsOffset(fragOffset))).thenReturn((byte) 0);

    // when
    subscription.peekBlock(
        logBufferPartition,
        blockPeekSpy,
        A_PARTITION_ID,
        fragOffset,
        A_FRAGMENT_LENGTH,
        position(A_PARTITION_ID, A_FRAGMENT_LENGTH),
        false);

    blockPeekSpy.markFailed();

    // then
    // the position was increased by the fragment length
    verify(subscriberPositionMock)
        .proposeMaxOrdered(position(A_PARTITION_ID, nextFragmentOffset(fragOffset)));
    // and the fragment was marked as failed
    final byte flags = rawBuffer.get(A_PARTITION_DATA_SECTION_OFFSET + flagsOffset);
    assertThat(DataFrameDescriptor.flagFailed(flags)).isTrue();
  }

  @Test
  public void shouldReadMultipleFragmentsAsBlock() {
    final int firstFragOffset = 0;
    final int secondFragOffset = nextFragmentOffset(firstFragOffset);
    final int nextFragOffset = nextFragmentOffset(secondFragOffset);

    when(dataBufferMock.getIntVolatile(lengthOffset(firstFragOffset)))
        .thenReturn(framedLength(A_MSG_PAYLOAD_LENGTH));
    when(dataBufferMock.getShort(typeOffset(firstFragOffset))).thenReturn(TYPE_MESSAGE);
    when(dataBufferMock.getInt(streamIdOffset(firstFragOffset))).thenReturn(A_STREAM_ID);

    when(dataBufferMock.getIntVolatile(lengthOffset(secondFragOffset)))
        .thenReturn(framedLength(A_MSG_PAYLOAD_LENGTH));
    when(dataBufferMock.getShort(typeOffset(secondFragOffset))).thenReturn(TYPE_MESSAGE);
    when(dataBufferMock.getInt(streamIdOffset(secondFragOffset))).thenReturn(A_STREAM_ID);

    // when
    final int bytesAvailable =
        subscription.peekBlock(
            logBufferPartition,
            blockPeekSpy,
            A_PARTITION_ID,
            firstFragOffset,
            2 * A_FRAGMENT_LENGTH,
            position(A_PARTITION_ID, nextFragOffset),
            false);

    blockPeekSpy.markCompleted();

    // then
    assertThat(bytesAvailable).isEqualTo(2 * A_FRAGMENT_LENGTH);
    // two fragments were peeked
    verify(blockPeekSpy)
        .setBlock(
            rawBufferView,
            subscriberPositionMock,
            dataConsumed,
            -1,
            firstFragOffset + A_PARTITION_DATA_SECTION_OFFSET,
            2 * A_FRAGMENT_LENGTH,
            A_PARTITION_ID,
            nextFragOffset,
            2,
            metric);
    // and the position was increased by the fragment length of the two fragments
    verify(subscriberPositionMock).proposeMaxOrdered(position(A_PARTITION_ID, nextFragOffset));
  }

  @Test
  public void shouldNotReadBeyondLimit() {
    final int firstFragOffset = 0;
    final int secondFragOffset = nextFragmentOffset(firstFragOffset);
    final long limit = position(A_PARTITION_ID, A_FRAGMENT_LENGTH);

    when(dataBufferMock.getIntVolatile(lengthOffset(firstFragOffset)))
        .thenReturn(framedLength(A_MSG_PAYLOAD_LENGTH));
    when(dataBufferMock.getShort(typeOffset(firstFragOffset))).thenReturn(TYPE_MESSAGE);
    when(dataBufferMock.getInt(streamIdOffset(firstFragOffset))).thenReturn(A_STREAM_ID);

    when(dataBufferMock.getIntVolatile(lengthOffset(secondFragOffset)))
        .thenReturn(framedLength(A_MSG_PAYLOAD_LENGTH));
    when(dataBufferMock.getShort(typeOffset(secondFragOffset))).thenReturn(TYPE_MESSAGE);
    when(dataBufferMock.getInt(streamIdOffset(secondFragOffset))).thenReturn(A_STREAM_ID);

    // when
    final int bytesAvailable =
        subscription.peekBlock(
            logBufferPartition,
            blockPeekSpy,
            A_PARTITION_ID,
            firstFragOffset,
            2 * A_FRAGMENT_LENGTH,
            limit,
            false);

    blockPeekSpy.markCompleted();

    // then
    assertThat(bytesAvailable).isEqualTo(A_FRAGMENT_LENGTH);
    // only one fragment was peeked
    verify(blockPeekSpy)
        .setBlock(
            rawBufferView,
            subscriberPositionMock,
            dataConsumed,
            -1,
            firstFragOffset + A_PARTITION_DATA_SECTION_OFFSET,
            A_FRAGMENT_LENGTH,
            A_PARTITION_ID,
            secondFragOffset,
            1,
            metric);
    // and the position was increased by the fragment length of the one fragment
    verify(subscriberPositionMock).proposeMaxOrdered(position(A_PARTITION_ID, secondFragOffset));
  }

  @Test
  public void shouldNotReadDifferentStreamsIfStreamAware() {
    final int firstFragOffset = 0;
    final int secondFragOffset = nextFragmentOffset(firstFragOffset);

    when(dataBufferMock.getIntVolatile(lengthOffset(firstFragOffset)))
        .thenReturn(framedLength(A_MSG_PAYLOAD_LENGTH));
    when(dataBufferMock.getShort(typeOffset(firstFragOffset))).thenReturn(TYPE_MESSAGE);
    when(dataBufferMock.getInt(streamIdOffset(firstFragOffset))).thenReturn(A_STREAM_ID);

    when(dataBufferMock.getIntVolatile(lengthOffset(secondFragOffset)))
        .thenReturn(framedLength(A_MSG_PAYLOAD_LENGTH));
    when(dataBufferMock.getShort(typeOffset(secondFragOffset))).thenReturn(TYPE_MESSAGE);
    when(dataBufferMock.getInt(streamIdOffset(secondFragOffset)))
        .thenReturn(ANOTHER_STREAM_ID); // different stream id than first msg

    // when
    final int bytesAvailable =
        subscription.peekBlock(
            logBufferPartition,
            blockPeekSpy,
            A_PARTITION_ID,
            firstFragOffset,
            2 * A_FRAGMENT_LENGTH,
            position(A_PARTITION_ID, nextFragmentOffset(secondFragOffset)),
            true);

    // then
    assertThat(bytesAvailable).isEqualTo(A_FRAGMENT_LENGTH);
    // only one fragment was peeked
    verify(blockPeekSpy)
        .setBlock(
            rawBufferView,
            subscriberPositionMock,
            dataConsumed,
            A_STREAM_ID,
            firstFragOffset + A_PARTITION_DATA_SECTION_OFFSET,
            A_FRAGMENT_LENGTH,
            A_PARTITION_ID,
            secondFragOffset,
            2,
            metric);
  }

  @Test
  public void shouldReadDifferentStreamsIfNotStreamAware() {
    final int firstFragOffset = 0;
    final int secondFragOffset = nextFragmentOffset(firstFragOffset);
    final int nextFragOffset = nextFragmentOffset(secondFragOffset);

    when(dataBufferMock.getIntVolatile(lengthOffset(firstFragOffset)))
        .thenReturn(framedLength(A_MSG_PAYLOAD_LENGTH));
    when(dataBufferMock.getShort(typeOffset(firstFragOffset))).thenReturn(TYPE_MESSAGE);
    when(dataBufferMock.getInt(streamIdOffset(firstFragOffset))).thenReturn(A_STREAM_ID);

    when(dataBufferMock.getIntVolatile(lengthOffset(secondFragOffset)))
        .thenReturn(framedLength(A_MSG_PAYLOAD_LENGTH));
    when(dataBufferMock.getShort(typeOffset(secondFragOffset))).thenReturn(TYPE_MESSAGE);
    when(dataBufferMock.getInt(streamIdOffset(secondFragOffset)))
        .thenReturn(ANOTHER_STREAM_ID); // different stream id than first msg

    // when
    final int bytesAvailable =
        subscription.peekBlock(
            logBufferPartition,
            blockPeekSpy,
            A_PARTITION_ID,
            firstFragOffset,
            2 * A_FRAGMENT_LENGTH,
            position(A_PARTITION_ID, nextFragOffset),
            false);

    // then
    assertThat(bytesAvailable).isEqualTo(2 * A_FRAGMENT_LENGTH);
    // both fragments were peeked
    verify(blockPeekSpy)
        .setBlock(
            rawBufferView,
            subscriberPositionMock,
            dataConsumed,
            -1,
            firstFragOffset + A_PARTITION_DATA_SECTION_OFFSET,
            2 * A_FRAGMENT_LENGTH,
            A_PARTITION_ID,
            nextFragOffset,
            2,
            metric);
  }

  @Test
  public void shouldRollOverPartitionOnPaddingIfEndOfPArtition() {
    final int fragOffset = A_PARTITION_LENGTH - A_FRAGMENT_LENGTH;

    when(dataBufferMock.getIntVolatile(lengthOffset(fragOffset)))
        .thenReturn(framedLength(A_MSG_PAYLOAD_LENGTH));
    when(dataBufferMock.getShort(typeOffset(fragOffset))).thenReturn(TYPE_PADDING);

    // when
    final int bytesAvailable =
        subscription.peekBlock(
            logBufferPartition,
            blockPeekSpy,
            A_PARTITION_ID,
            fragOffset,
            A_FRAGMENT_LENGTH,
            position(A_PARTITION_ID + 1, 0),
            false);

    // then
    assertThat(bytesAvailable).isEqualTo(0);
    // no fragment was peeked
    verifyNoMoreInteractions(blockPeekSpy);
    // and the position was set to the beginning of the next partition
    verify(subscriberPositionMock).proposeMaxOrdered(position(A_PARTITION_ID + 1, 0));
  }

  @Test
  public void shouldRollOverIfHitsPadding() {
    final int firstFragOffset = A_PARTITION_LENGTH - (2 * A_FRAGMENT_LENGTH);
    final int secondFragOffset = nextFragmentOffset(firstFragOffset);

    final int nextPartionId = A_PARTITION_ID + 1;
    final int nextFragOffset = 0;

    when(dataBufferMock.getIntVolatile(lengthOffset(firstFragOffset)))
        .thenReturn(framedLength(A_MSG_PAYLOAD_LENGTH));
    when(dataBufferMock.getShort(typeOffset(firstFragOffset))).thenReturn(TYPE_MESSAGE);
    when(dataBufferMock.getInt(streamIdOffset(firstFragOffset))).thenReturn(A_STREAM_ID);

    when(dataBufferMock.getIntVolatile(lengthOffset(secondFragOffset)))
        .thenReturn(framedLength(A_MSG_PAYLOAD_LENGTH));
    when(dataBufferMock.getShort(typeOffset(secondFragOffset))).thenReturn(TYPE_PADDING);

    // when
    final int bytesAvailable =
        subscription.peekBlock(
            logBufferPartition,
            blockPeekSpy,
            A_PARTITION_ID,
            firstFragOffset,
            2 * A_FRAGMENT_LENGTH,
            position(nextPartionId, nextFragOffset),
            false);

    blockPeekSpy.markCompleted();

    // then
    assertThat(bytesAvailable).isEqualTo(A_FRAGMENT_LENGTH);
    // the fragment was peeked
    verify(blockPeekSpy)
        .setBlock(
            rawBufferView,
            subscriberPositionMock,
            dataConsumed,
            -1,
            firstFragOffset + A_PARTITION_DATA_SECTION_OFFSET,
            A_FRAGMENT_LENGTH,
            nextPartionId,
            nextFragOffset,
            1,
            metric);
    // and the position was rolled over to the next partition
    verify(subscriberPositionMock)
        .proposeMaxOrdered(position(nextPartionId, nextFragOffset)); // is secondFragOffset somehow
  }

  @Test
  public void shouldNotRollOverPartitionOnPaddingIfNotEndOfPArtition() {
    final int fragOffset = 0;

    when(dataBufferMock.getIntVolatile(lengthOffset(fragOffset)))
        .thenReturn(framedLength(A_MSG_PAYLOAD_LENGTH));
    when(dataBufferMock.getShort(typeOffset(fragOffset))).thenReturn(TYPE_PADDING);

    // when
    final int bytesAvailable =
        subscription.peekBlock(
            logBufferPartition,
            blockPeekSpy,
            A_PARTITION_ID,
            fragOffset,
            2 * A_FRAGMENT_LENGTH,
            position(A_PARTITION_ID, A_FRAGMENT_LENGTH),
            false);

    // then
    assertThat(bytesAvailable).isEqualTo(0);
    // no fragment was peeked
    verifyNoMoreInteractions(blockPeekSpy);
    // and the position was rolled over to the next fragement after the padding
    verify(subscriberPositionMock)
        .proposeMaxOrdered(position(A_PARTITION_ID, nextFragmentOffset(fragOffset)));
  }

  @Test
  public void shouldNotRollOverIfHitsPaddingNotAtAndOfPartition() {
    final int firstFragOffset = 0;
    final int secondFragOffset = nextFragmentOffset(firstFragOffset);
    final int nextFragOffset = nextFragmentOffset(secondFragOffset);

    when(dataBufferMock.getIntVolatile(lengthOffset(firstFragOffset)))
        .thenReturn(framedLength(A_MSG_PAYLOAD_LENGTH));
    when(dataBufferMock.getShort(typeOffset(firstFragOffset))).thenReturn(TYPE_MESSAGE);
    when(dataBufferMock.getInt(streamIdOffset(firstFragOffset))).thenReturn(A_STREAM_ID);

    when(dataBufferMock.getIntVolatile(lengthOffset(secondFragOffset)))
        .thenReturn(framedLength(A_MSG_PAYLOAD_LENGTH));
    when(dataBufferMock.getShort(typeOffset(secondFragOffset))).thenReturn(TYPE_PADDING);

    // when
    final int bytesAvailable =
        subscription.peekBlock(
            logBufferPartition,
            blockPeekSpy,
            A_PARTITION_ID,
            firstFragOffset,
            2 * A_FRAGMENT_LENGTH,
            position(A_PARTITION_ID, nextFragOffset),
            false);

    blockPeekSpy.markCompleted();

    // then
    assertThat(bytesAvailable).isEqualTo(A_FRAGMENT_LENGTH);
    // the fragment was peeked
    verify(blockPeekSpy)
        .setBlock(
            rawBufferView,
            subscriberPositionMock,
            dataConsumed,
            -1,
            firstFragOffset + A_PARTITION_DATA_SECTION_OFFSET,
            A_FRAGMENT_LENGTH,
            A_PARTITION_ID,
            nextFragOffset,
            1,
            metric);
    // and the position was rolled over to the next fragement after the padding
    verify(subscriberPositionMock)
        .proposeMaxOrdered(position(A_PARTITION_ID, nextFragOffset)); // is secondFragOffset somehow
  }

  @Test
  public void shouldNotReadIncompleteMessage() {
    final int fragOffset = 0;

    when(subscriberPositionMock.get()).thenReturn(position(A_PARTITION_ID, fragOffset));
    when(dataBufferMock.getIntVolatile(lengthOffset(fragOffset)))
        .thenReturn(-framedLength(A_MSG_PAYLOAD_LENGTH));

    // when
    final int bytesAvailable =
        subscription.peekBlock(
            logBufferPartition,
            blockPeekSpy,
            A_PARTITION_ID,
            fragOffset,
            A_FRAGMENT_LENGTH,
            0,
            false);

    // then
    assertThat(bytesAvailable).isEqualTo(0);
    // no fragment was peeked
    verifyNoMoreInteractions(blockPeekSpy);
  }

  @Test
  public void shouldReadFragmentBatch() {
    final int firstFragOffset = 0;
    final int secondFragOffset = nextFragmentOffset(firstFragOffset);
    final int nextFragOffset = nextFragmentOffset(secondFragOffset);

    when(dataBufferMock.getIntVolatile(lengthOffset(firstFragOffset)))
        .thenReturn(framedLength(A_MSG_PAYLOAD_LENGTH));
    when(dataBufferMock.getShort(typeOffset(firstFragOffset))).thenReturn(TYPE_MESSAGE);
    when(dataBufferMock.getInt(streamIdOffset(firstFragOffset))).thenReturn(A_STREAM_ID);
    when(dataBufferMock.getByte(flagsOffset(firstFragOffset)))
        .thenReturn(enableFlagBatchBegin((byte) 0));

    when(dataBufferMock.getIntVolatile(lengthOffset(secondFragOffset)))
        .thenReturn(framedLength(A_MSG_PAYLOAD_LENGTH));
    when(dataBufferMock.getShort(typeOffset(secondFragOffset))).thenReturn(TYPE_MESSAGE);
    when(dataBufferMock.getInt(streamIdOffset(secondFragOffset))).thenReturn(A_STREAM_ID);
    when(dataBufferMock.getByte(flagsOffset(secondFragOffset)))
        .thenReturn(enableFlagBatchEnd((byte) 0));

    // when
    final int bytesAvailable =
        subscription.peekBlock(
            logBufferPartition,
            blockPeekSpy,
            A_PARTITION_ID,
            firstFragOffset,
            2 * A_FRAGMENT_LENGTH,
            position(A_PARTITION_ID, nextFragOffset),
            false);

    blockPeekSpy.markCompleted();

    // then
    assertThat(bytesAvailable).isEqualTo(2 * A_FRAGMENT_LENGTH);
    // two fragments were peeked
    verify(blockPeekSpy)
        .setBlock(
            rawBufferView,
            subscriberPositionMock,
            dataConsumed,
            -1,
            firstFragOffset + A_PARTITION_DATA_SECTION_OFFSET,
            2 * A_FRAGMENT_LENGTH,
            A_PARTITION_ID,
            nextFragOffset,
            2,
            metric);
    // and the position was increased by the fragment length of the two fragments
    verify(subscriberPositionMock).proposeMaxOrdered(position(A_PARTITION_ID, nextFragOffset));
  }

  @Test
  public void shouldNotReadFragmentBatchPartitial() {
    final int firstFragOffset = 0;
    final int secondFragOffset = nextFragmentOffset(firstFragOffset);
    final int nextFragOffset = nextFragmentOffset(secondFragOffset);

    when(dataBufferMock.getIntVolatile(lengthOffset(firstFragOffset)))
        .thenReturn(framedLength(A_MSG_PAYLOAD_LENGTH));
    when(dataBufferMock.getShort(typeOffset(firstFragOffset))).thenReturn(TYPE_MESSAGE);
    when(dataBufferMock.getInt(streamIdOffset(firstFragOffset))).thenReturn(A_STREAM_ID);
    when(dataBufferMock.getByte(flagsOffset(firstFragOffset)))
        .thenReturn(enableFlagBatchBegin((byte) 0));

    when(dataBufferMock.getIntVolatile(lengthOffset(secondFragOffset)))
        .thenReturn(framedLength(A_MSG_PAYLOAD_LENGTH));
    when(dataBufferMock.getShort(typeOffset(secondFragOffset))).thenReturn(TYPE_MESSAGE);
    when(dataBufferMock.getInt(streamIdOffset(secondFragOffset))).thenReturn(A_STREAM_ID);
    when(dataBufferMock.getByte(flagsOffset(secondFragOffset)))
        .thenReturn(enableFlagBatchEnd((byte) 0));

    // when
    final int bytesAvailable =
        subscription.peekBlock(
            logBufferPartition,
            blockPeekSpy,
            A_PARTITION_ID,
            firstFragOffset,
            A_FRAGMENT_LENGTH,
            position(A_PARTITION_ID, nextFragOffset),
            false);

    // then
    assertThat(bytesAvailable).isEqualTo(0);
    // no fragment was peeked
    verifyNoMoreInteractions(blockPeekSpy);
  }

  @Test
  public void shouldDiscardPartitialFragmentBatch() {
    final int firstFragOffset = 0;
    final int secondFragOffset = nextFragmentOffset(firstFragOffset);
    final int nextFragOffset = nextFragmentOffset(secondFragOffset);

    when(dataBufferMock.getIntVolatile(lengthOffset(firstFragOffset)))
        .thenReturn(framedLength(A_MSG_PAYLOAD_LENGTH));
    when(dataBufferMock.getShort(typeOffset(firstFragOffset))).thenReturn(TYPE_MESSAGE);
    when(dataBufferMock.getInt(streamIdOffset(firstFragOffset))).thenReturn(A_STREAM_ID);
    when(dataBufferMock.getByte(flagsOffset(firstFragOffset))).thenReturn((byte) 0);

    when(dataBufferMock.getIntVolatile(lengthOffset(secondFragOffset)))
        .thenReturn(framedLength(A_MSG_PAYLOAD_LENGTH));
    when(dataBufferMock.getShort(typeOffset(secondFragOffset))).thenReturn(TYPE_MESSAGE);
    when(dataBufferMock.getInt(streamIdOffset(secondFragOffset))).thenReturn(A_STREAM_ID);
    when(dataBufferMock.getByte(flagsOffset(secondFragOffset)))
        .thenReturn(enableFlagBatchBegin((byte) 0));

    // when
    final int bytesAvailable =
        subscription.peekBlock(
            logBufferPartition,
            blockPeekSpy,
            A_PARTITION_ID,
            firstFragOffset,
            2 * A_FRAGMENT_LENGTH,
            position(A_PARTITION_ID, nextFragOffset),
            false);

    blockPeekSpy.markCompleted();

    // then
    assertThat(bytesAvailable).isEqualTo(A_FRAGMENT_LENGTH);
    // one fragment was peeked
    verify(blockPeekSpy)
        .setBlock(
            rawBufferView,
            subscriberPositionMock,
            dataConsumed,
            -1,
            firstFragOffset + A_PARTITION_DATA_SECTION_OFFSET,
            A_FRAGMENT_LENGTH,
            A_PARTITION_ID,
            secondFragOffset,
            2,
            metric);
    // and the position was increased by the fragment length of the two fragments
    verify(subscriberPositionMock).proposeMaxOrdered(position(A_PARTITION_ID, secondFragOffset));
  }

  private int nextFragmentOffset(final int currentOffset) {
    return currentOffset + A_FRAGMENT_LENGTH;
  }
}
