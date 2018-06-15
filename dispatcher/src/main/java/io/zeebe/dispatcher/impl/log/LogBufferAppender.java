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
package io.zeebe.dispatcher.impl.log;

import static io.zeebe.dispatcher.impl.log.DataFrameDescriptor.*;
import static org.agrona.BitUtil.align;
import static org.agrona.UnsafeAccess.UNSAFE;

import io.zeebe.dispatcher.ClaimedFragment;
import io.zeebe.dispatcher.ClaimedFragmentBatch;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

public class LogBufferAppender {
  public static final int RESULT_PADDING_AT_END_OF_PARTITION = -2;
  public static final int RESULT_END_OF_PARTITION = -1;

  @SuppressWarnings("restriction")
  public int appendFrame(
      final LogBufferPartition partition,
      final int activePartitionId,
      final DirectBuffer msg,
      final int start,
      final int length,
      final int streamId) {
    final int partitionSize = partition.getPartitionSize();
    final int framedLength = framedLength(length);
    final int alignedFrameLength = alignedLength(framedLength);

    // move the tail of the partition
    final int frameOffset = partition.getAndAddTail(alignedFrameLength);

    int newTail = frameOffset + alignedFrameLength;

    if (newTail <= (partitionSize - HEADER_LENGTH)) {
      final UnsafeBuffer buffer = partition.getDataBuffer();

      // write negative length field
      buffer.putIntOrdered(lengthOffset(frameOffset), -framedLength);
      UNSAFE.storeFence();
      buffer.putShort(typeOffset(frameOffset), TYPE_MESSAGE);
      buffer.putInt(streamIdOffset(frameOffset), streamId);
      buffer.putBytes(messageOffset(frameOffset), msg, start, length);

      // commit the message
      buffer.putIntOrdered(lengthOffset(frameOffset), framedLength);
    } else {
      newTail = onEndOfPartition(partition, frameOffset);
    }

    return newTail;
  }

  @SuppressWarnings("restriction")
  public int claim(
      final LogBufferPartition partition,
      final int activePartitionId,
      final ClaimedFragment claim,
      final int length,
      final int streamId,
      Runnable onComplete) {
    final int partitionSize = partition.getPartitionSize();
    final int framedMessageLength = framedLength(length);
    final int alignedFrameLength = alignedLength(framedMessageLength);

    // move the tail of the partition
    final int frameOffset = partition.getAndAddTail(alignedFrameLength);

    int newTail = frameOffset + alignedFrameLength;

    if (newTail <= (partitionSize - HEADER_LENGTH)) {
      final UnsafeBuffer buffer = partition.getDataBuffer();

      // write negative length field
      buffer.putIntOrdered(lengthOffset(frameOffset), -framedMessageLength);
      UNSAFE.storeFence();
      buffer.putShort(typeOffset(frameOffset), TYPE_MESSAGE);
      buffer.putInt(streamIdOffset(frameOffset), streamId);

      claim.wrap(buffer, frameOffset, framedMessageLength, onComplete);
      // Do not commit the message
    } else {
      newTail = onEndOfPartition(partition, frameOffset);
    }

    return newTail;
  }

  public int claim(
      final LogBufferPartition partition,
      final int activePartitionId,
      final ClaimedFragmentBatch batch,
      final int fragmentCount,
      final int batchLength,
      final Runnable onComplete) {
    final int partitionSize = partition.getPartitionSize();
    // reserve enough space for frame alignment because each batch fragment must start on an aligned
    // position
    final int framedMessageLength =
        batchLength + fragmentCount * (HEADER_LENGTH + FRAME_ALIGNMENT) + FRAME_ALIGNMENT;
    final int alignedFrameLength = align(framedMessageLength, FRAME_ALIGNMENT);

    // move the tail of the partition
    final int frameOffset = partition.getAndAddTail(alignedFrameLength);

    int newTail = frameOffset + alignedFrameLength;

    if (newTail <= (partitionSize - HEADER_LENGTH)) {
      final UnsafeBuffer buffer = partition.getDataBuffer();
      // all fragment data are written using the claimed batch
      batch.wrap(buffer, activePartitionId, frameOffset, alignedFrameLength, onComplete);
    } else {
      newTail = onEndOfPartition(partition, frameOffset);
    }

    return newTail;
  }

  @SuppressWarnings("restriction")
  protected int onEndOfPartition(final LogBufferPartition partition, final int partitionOffset) {
    int newTail = RESULT_END_OF_PARTITION;

    final int padLength = partition.getPartitionSize() - partitionOffset;

    if (padLength >= HEADER_LENGTH) {
      // this message tripped the end of the partition, fill buffer with padding
      final UnsafeBuffer buffer = partition.getDataBuffer();
      buffer.putIntOrdered(lengthOffset(partitionOffset), -padLength);
      UNSAFE.storeFence();
      buffer.putShort(typeOffset(partitionOffset), TYPE_PADDING);
      buffer.putIntOrdered(lengthOffset(partitionOffset), padLength);

      newTail = RESULT_PADDING_AT_END_OF_PARTITION;
    }

    return newTail;
  }
}
