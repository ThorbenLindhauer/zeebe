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

import static io.zeebe.dispatcher.impl.PositionUtil.*;
import static io.zeebe.dispatcher.impl.log.DataFrameDescriptor.*;

import io.zeebe.dispatcher.impl.log.DataFrameDescriptor;
import io.zeebe.dispatcher.impl.log.LogBuffer;
import io.zeebe.dispatcher.impl.log.LogBufferPartition;
import io.zeebe.util.metrics.Metric;
import io.zeebe.util.sched.ActorCondition;
import io.zeebe.util.sched.channel.ActorConditions;
import io.zeebe.util.sched.channel.ConsumableChannel;
import java.nio.ByteBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.slf4j.Logger;

public class Subscription implements ConsumableChannel {
  public static final Logger LOG = Loggers.DISPATCHER_LOGGER;

  protected final ActorConditions actorConditions = new ActorConditions();

  protected final AtomicPosition limit;
  protected final AtomicPosition position;
  protected final LogBuffer logBuffer;
  protected final int id;
  protected final String name;
  protected final ActorCondition dataConsumed;
  protected final ByteBuffer rawDispatcherBufferView;
  protected final Metric fragmentsConsumedMetric;

  protected volatile boolean isClosed = false;

  public Subscription(
      AtomicPosition position,
      AtomicPosition limit,
      int id,
      String name,
      ActorCondition onConsumption,
      LogBuffer logBuffer,
      Metric fragmentsConsumedMetric) {
    this.position = position;
    this.id = id;
    this.name = name;
    this.limit = limit;
    this.logBuffer = logBuffer;
    this.dataConsumed = onConsumption;
    this.fragmentsConsumedMetric = fragmentsConsumedMetric;

    // required so that a subscription can freely modify position and limit of the raw buffer
    this.rawDispatcherBufferView = logBuffer.createRawBufferView();
  }

  public long getPosition() {
    return position.get();
  }

  @Override
  public boolean hasAvailable() {
    return getLimit() > getPosition();
  }

  protected long getLimit() {
    return limit.get();
  }

  /**
   * Read fragments from the buffer and invoke the given handler for each fragment. Consume the
   * fragments (i.e. update the subscription position) after all fragments are handled.
   *
   * <p>Note that the handler is not aware of fragment batches.
   *
   * @return the amount of read fragments
   */
  public int poll(FragmentHandler frgHandler, int maxNumOfFragments) {
    int fragmentsRead = 0;

    if (!isClosed) {
      final long currentPosition = position.get();
      final long limit = getLimit();

      if (limit > currentPosition) {
        final int partitionId = partitionId(currentPosition);
        final int partitionOffset = partitionOffset(currentPosition);
        final LogBufferPartition partition = logBuffer.getPartition(partitionId);

        fragmentsRead =
            pollFragments(
                partition,
                frgHandler,
                partitionId,
                partitionOffset,
                maxNumOfFragments,
                limit,
                false);
      }
    }

    return fragmentsRead;
  }

  protected int pollFragments(
      final LogBufferPartition partition,
      final FragmentHandler frgHandler,
      int partitionId,
      int fragmentOffset,
      final int maxNumOfFragments,
      final long limit,
      boolean handlerControlled) {
    final UnsafeBuffer buffer = partition.getDataBuffer();

    int fragmentsConsumed = 0;

    int fragmentResult = FragmentHandler.CONSUME_FRAGMENT_RESULT;
    do {
      final int framedLength = buffer.getIntVolatile(lengthOffset(fragmentOffset));
      if (framedLength <= 0) {
        break;
      }

      final short type = buffer.getShort(typeOffset(fragmentOffset));
      if (type == TYPE_PADDING) {
        fragmentOffset += alignedLength(framedLength);

        if (fragmentOffset >= partition.getPartitionSize()) {
          ++partitionId;
          fragmentOffset = 0;
          break;
        }
      } else {
        final int streamId = buffer.getInt(streamIdOffset(fragmentOffset));
        final int flagsOffset = flagsOffset(fragmentOffset);
        final byte flags = buffer.getByte(flagsOffset);
        try {
          final boolean isMarkedAsFailed = flagFailed(flags);

          final int messageLength = messageLength(framedLength);
          final int handlerResult =
              frgHandler.onFragment(
                  buffer, messageOffset(fragmentOffset), messageLength, streamId, isMarkedAsFailed);

          if (handlerResult == FragmentHandler.FAILED_FRAGMENT_RESULT && !isMarkedAsFailed) {
            buffer.putByte(flagsOffset, DataFrameDescriptor.enableFlagFailed(flags));
          }

          if (handlerControlled) {
            fragmentResult = handlerResult;
          }

        } catch (RuntimeException e) {
          // TODO!
          LOG.error("Failed to handle fragment", e);
        }

        if (fragmentResult != FragmentHandler.POSTPONE_FRAGMENT_RESULT) {
          ++fragmentsConsumed;
          fragmentOffset += alignedLength(framedLength);
        }
      }
    } while (fragmentResult != FragmentHandler.POSTPONE_FRAGMENT_RESULT
        && fragmentsConsumed < maxNumOfFragments
        && position(partitionId, fragmentOffset) < limit);

    position.set(position(partitionId, fragmentOffset));
    dataConsumed.signal();
    this.fragmentsConsumedMetric.getAndAddOrdered(fragmentsConsumed);

    return fragmentsConsumed;
  }

  /**
   * Sequentially read fragments from the buffer and invoke the given handler for each fragment.
   * Consume the fragments (i.e. update the subscription position) depending on the return value of
   * {@link FragmentHandler#onFragment(org.agrona.DirectBuffer, int, int, int, boolean)}. If a
   * fragment is not consumed then no following fragments are read.
   *
   * <p>Note that the handler is not aware of fragment batches.
   *
   * @return the amount of read fragments
   */
  public int peekAndConsume(FragmentHandler frgHandler, int maxNumOfFragments) {
    int fragmentsRead = 0;

    if (!isClosed) {
      final long currentPosition = position.get();
      final long limit = getLimit();

      if (limit > currentPosition) {
        final int partitionId = partitionId(currentPosition);
        final int partitionOffset = partitionOffset(currentPosition);

        final LogBufferPartition partition = logBuffer.getPartition(partitionId);

        fragmentsRead =
            pollFragments(
                partition,
                frgHandler,
                partitionId,
                partitionOffset,
                maxNumOfFragments,
                limit,
                true);
      }
    }

    return fragmentsRead;
  }

  /**
   * Read fragments from the buffer as block. Use {@link BlockPeek#getBuffer()} to consume the
   * fragments and finish the operation using {@link BlockPeek#markCompleted()} or {@link
   * BlockPeek#markFailed()}.
   *
   * <p>Note that the block only contains complete fragment batches.
   *
   * @param isStreamAware if <code>true</code>, it stops reading fragments when a fragment has a
   *     different stream id than the previous one
   * @return amount of read bytes
   */
  public int peekBlock(BlockPeek availableBlock, int maxBlockSize, boolean isStreamAware) {
    int bytesAvailable = 0;

    if (!isClosed) {
      final long currentPosition = position.get();

      final long limit = getLimit();

      if (limit > currentPosition) {
        final int partitionId = partitionId(currentPosition);
        final int partitionOffset = partitionOffset(currentPosition);

        final LogBufferPartition partition = logBuffer.getPartition(partitionId);

        bytesAvailable =
            peekBlock(
                partition,
                availableBlock,
                partitionId,
                partitionOffset,
                maxBlockSize,
                limit,
                isStreamAware);
      }
    }

    return bytesAvailable;
  }

  protected int peekBlock(
      final LogBufferPartition partition,
      final BlockPeek availableBlock,
      int partitionId,
      int partitionOffset,
      final int maxBlockSize,
      long limit,
      final boolean isStreamAware) {
    final UnsafeBuffer buffer = partition.getDataBuffer();
    final int bufferOffset = partition.getUnderlyingBufferOffset();
    final int firstFragmentOffset = partitionOffset;

    int readBytes = 0;
    int initialStreamId = -1;
    boolean isReadingBatch = false;
    int offset = partitionOffset;
    int fragmentCount = 0;

    int offsetLimit = partitionOffset(limit);
    if (partitionId(limit) > partitionId) {
      offsetLimit = partition.getPartitionSize();
    }

    do {
      final int framedLength = buffer.getIntVolatile(lengthOffset(partitionOffset));
      if (framedLength <= 0) {
        break;
      }

      final short type = buffer.getShort(typeOffset(partitionOffset));
      if (type == TYPE_PADDING) {
        partitionOffset += alignedLength(framedLength);

        if (partitionOffset >= partition.getPartitionSize()) {
          partitionId += 1;
          partitionOffset = 0;
        }
        offset = partitionOffset;

        if (readBytes == 0) {
          position.proposeMaxOrdered(position(partitionId, partitionOffset));
          dataConsumed.signal();
        }

        break;
      } else {
        fragmentCount++;

        if (isStreamAware) {
          final int streamId = buffer.getInt(streamIdOffset(partitionOffset));
          if (readBytes == 0) {
            initialStreamId = streamId;
          } else if (streamId != initialStreamId) {
            break;
          }
        }

        final byte flags = buffer.getByte(flagsOffset(partitionOffset));
        if (!isReadingBatch) {
          isReadingBatch = flagBatchBegin(flags);
        } else {
          isReadingBatch = !flagBatchEnd(flags);
        }

        final int alignedFrameLength = alignedLength(framedLength);
        if (alignedFrameLength <= maxBlockSize - readBytes) {
          partitionOffset += alignedFrameLength;
          readBytes += alignedFrameLength;

          if (!isReadingBatch) {
            offset = partitionOffset;
          }
        } else {
          break;
        }
      }
    } while (maxBlockSize - readBytes > HEADER_LENGTH && partitionOffset < offsetLimit);

    // only read complete fragment batches
    final int blockLength = readBytes + offset - partitionOffset;
    if (blockLength > 0) {
      final int absoluteOffset = bufferOffset + firstFragmentOffset;

      availableBlock.setBlock(
          rawDispatcherBufferView,
          position,
          dataConsumed,
          initialStreamId,
          absoluteOffset,
          blockLength,
          partitionId,
          offset,
          fragmentCount,
          fragmentsConsumedMetric);
    }
    return blockLength;
  }

  @Override
  public void registerConsumer(ActorCondition consumer) {
    actorConditions.registerConsumer(consumer);
  }

  @Override
  public void removeConsumer(ActorCondition consumer) {
    actorConditions.registerConsumer(consumer);
  }

  public int getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  protected ActorConditions getActorConditions() {
    return actorConditions;
  }

  @Override
  public String toString() {
    final StringBuilder builder = new StringBuilder();
    builder.append("Subscription [id=");
    builder.append(id);
    builder.append(", name=");
    builder.append(name);
    builder.append("]");
    return builder.toString();
  }
}
