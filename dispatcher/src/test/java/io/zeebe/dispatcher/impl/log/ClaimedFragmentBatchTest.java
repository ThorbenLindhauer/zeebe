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

import static io.zeebe.dispatcher.impl.PositionUtil.position;
import static io.zeebe.dispatcher.impl.log.DataFrameDescriptor.*;
import static org.assertj.core.api.Assertions.assertThat;

import io.zeebe.dispatcher.ClaimedFragmentBatch;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class ClaimedFragmentBatchTest {
  private static final Runnable DO_NOTHING = () -> {};

  private static final int PARTITION_ID = 1;
  private static final int PARTITION_OFFSET = 16;
  private static final int FRAGMENT_LENGTH = 1024;

  private static final byte[] MESSAGE = "message".getBytes();
  private static final int MESSAGE_LENGTH = MESSAGE.length;

  private UnsafeBuffer underlyingBuffer;
  private ClaimedFragmentBatch claimedBatch;

  @Rule public ExpectedException thrown = ExpectedException.none();

  @Before
  public void init() {
    underlyingBuffer = new UnsafeBuffer(new byte[PARTITION_OFFSET + FRAGMENT_LENGTH]);
    claimedBatch = new ClaimedFragmentBatch();
  }

  @Test
  public void shouldAddFragment() {
    // given
    claimedBatch.wrap(
        underlyingBuffer, PARTITION_ID, PARTITION_OFFSET, FRAGMENT_LENGTH, DO_NOTHING);

    // when
    final long position = claimedBatch.nextFragment(MESSAGE_LENGTH, 1);

    final int fragmentOffset = claimedBatch.getFragmentOffset();
    claimedBatch.getBuffer().putBytes(fragmentOffset, MESSAGE);

    // then
    assertThat(position)
        .isEqualTo(position(PARTITION_ID, PARTITION_OFFSET + alignedFramedLength(MESSAGE_LENGTH)));
    assertThat(fragmentOffset).isEqualTo(HEADER_LENGTH);

    assertThat(underlyingBuffer.getInt(lengthOffset(PARTITION_OFFSET)))
        .isEqualTo(-framedLength(MESSAGE_LENGTH));
    assertThat(underlyingBuffer.getShort(typeOffset(PARTITION_OFFSET))).isEqualTo(TYPE_MESSAGE);
    assertThat(underlyingBuffer.getInt(streamIdOffset(PARTITION_OFFSET))).isEqualTo(1);

    final byte[] buffer = new byte[MESSAGE_LENGTH];
    underlyingBuffer.getBytes(messageOffset(PARTITION_OFFSET), buffer);
    assertThat(buffer).isEqualTo(MESSAGE);
  }

  @Test
  public void shouldAddMultipleFragments() {
    // given
    claimedBatch.wrap(
        underlyingBuffer, PARTITION_ID, PARTITION_OFFSET, FRAGMENT_LENGTH, DO_NOTHING);

    claimedBatch.nextFragment(MESSAGE_LENGTH, 1);

    // when
    final long position = claimedBatch.nextFragment(MESSAGE_LENGTH, 2);
    final int fragmentOffset = claimedBatch.getFragmentOffset();

    // then
    assertThat(position)
        .isEqualTo(
            position(PARTITION_ID, PARTITION_OFFSET + 2 * alignedFramedLength(MESSAGE_LENGTH)));
    assertThat(fragmentOffset).isEqualTo(HEADER_LENGTH + alignedFramedLength(MESSAGE_LENGTH));

    final int bufferOffset = PARTITION_OFFSET + alignedFramedLength(MESSAGE_LENGTH);
    assertThat(underlyingBuffer.getInt(lengthOffset(bufferOffset)))
        .isEqualTo(-framedLength(MESSAGE_LENGTH));
    assertThat(underlyingBuffer.getShort(typeOffset(bufferOffset))).isEqualTo(TYPE_MESSAGE);
    assertThat(underlyingBuffer.getInt(streamIdOffset(bufferOffset))).isEqualTo(2);
  }

  @Test
  public void shouldCommitBatch() {
    // given
    claimedBatch.wrap(
        underlyingBuffer, PARTITION_ID, PARTITION_OFFSET, FRAGMENT_LENGTH, DO_NOTHING);

    claimedBatch.nextFragment(MESSAGE_LENGTH, 1);
    claimedBatch.nextFragment(MESSAGE_LENGTH, 2);

    // when
    claimedBatch.commit();

    // then
    int bufferOffset = PARTITION_OFFSET;
    assertThat(underlyingBuffer.getInt(lengthOffset(bufferOffset)))
        .isEqualTo(framedLength(MESSAGE_LENGTH));
    assertThat(underlyingBuffer.getShort(typeOffset(bufferOffset))).isEqualTo(TYPE_MESSAGE);
    assertThat(underlyingBuffer.getByte(flagsOffset(bufferOffset)))
        .isEqualTo(enableFlagBatchBegin((byte) 0));

    bufferOffset += alignedFramedLength(MESSAGE_LENGTH);
    assertThat(underlyingBuffer.getInt(lengthOffset(bufferOffset)))
        .isEqualTo(framedLength(MESSAGE_LENGTH));
    assertThat(underlyingBuffer.getShort(typeOffset(bufferOffset))).isEqualTo(TYPE_MESSAGE);
    assertThat(underlyingBuffer.getByte(flagsOffset(bufferOffset)))
        .isEqualTo(enableFlagBatchEnd((byte) 0));
  }

  @Test
  public void shouldAbortBatch() {
    // given
    claimedBatch.wrap(
        underlyingBuffer, PARTITION_ID, PARTITION_OFFSET, FRAGMENT_LENGTH, DO_NOTHING);

    claimedBatch.nextFragment(MESSAGE_LENGTH, 1);
    claimedBatch.nextFragment(MESSAGE_LENGTH, 2);

    // when
    claimedBatch.abort();

    // then
    int bufferOffset = PARTITION_OFFSET;
    assertThat(underlyingBuffer.getInt(lengthOffset(bufferOffset)))
        .isEqualTo(framedLength(MESSAGE_LENGTH));
    assertThat(underlyingBuffer.getShort(typeOffset(bufferOffset))).isEqualTo(TYPE_PADDING);
    assertThat(underlyingBuffer.getByte(flagsOffset(bufferOffset))).isEqualTo((byte) 0);

    bufferOffset += alignedFramedLength(MESSAGE_LENGTH);
    assertThat(underlyingBuffer.getInt(lengthOffset(bufferOffset)))
        .isEqualTo(framedLength(MESSAGE_LENGTH));
    assertThat(underlyingBuffer.getShort(typeOffset(bufferOffset))).isEqualTo(TYPE_PADDING);
    assertThat(underlyingBuffer.getByte(flagsOffset(bufferOffset))).isEqualTo((byte) 0);
  }

  @Test
  public void shouldFillRemainingBatchLengthOnCommit() {
    // given
    claimedBatch.wrap(
        underlyingBuffer, PARTITION_ID, PARTITION_OFFSET, FRAGMENT_LENGTH, DO_NOTHING);

    claimedBatch.nextFragment(MESSAGE_LENGTH, 1);
    claimedBatch.nextFragment(MESSAGE_LENGTH, 2);

    // when
    claimedBatch.commit();

    // then
    final int bufferOffset = PARTITION_OFFSET + 2 * alignedFramedLength(MESSAGE_LENGTH);
    assertThat(underlyingBuffer.getInt(lengthOffset(bufferOffset)))
        .isEqualTo(FRAGMENT_LENGTH - 2 * alignedFramedLength(MESSAGE_LENGTH));
    assertThat(underlyingBuffer.getShort(typeOffset(bufferOffset))).isEqualTo(TYPE_PADDING);
  }

  @Test
  public void shouldFillRemainingBatchLengthOnAbort() {
    // given
    claimedBatch.wrap(
        underlyingBuffer, PARTITION_ID, PARTITION_OFFSET, FRAGMENT_LENGTH, DO_NOTHING);

    claimedBatch.nextFragment(MESSAGE_LENGTH, 1);
    claimedBatch.nextFragment(MESSAGE_LENGTH, 2);

    // when
    claimedBatch.abort();

    // then
    final int bufferOffset = PARTITION_OFFSET + 2 * alignedFramedLength(MESSAGE_LENGTH);
    assertThat(underlyingBuffer.getInt(lengthOffset(bufferOffset)))
        .isEqualTo(FRAGMENT_LENGTH - 2 * alignedFramedLength(MESSAGE_LENGTH));
    assertThat(underlyingBuffer.getShort(typeOffset(bufferOffset))).isEqualTo(TYPE_PADDING);
  }

  @Test
  public void shouldCommitSingleFragmentBatch() {
    // given
    claimedBatch.wrap(
        underlyingBuffer, PARTITION_ID, PARTITION_OFFSET, FRAGMENT_LENGTH, DO_NOTHING);

    claimedBatch.nextFragment(MESSAGE_LENGTH, 1);

    // when
    claimedBatch.commit();

    // then
    int bufferOffset = PARTITION_OFFSET;
    assertThat(underlyingBuffer.getInt(lengthOffset(bufferOffset)))
        .isEqualTo(framedLength(MESSAGE_LENGTH));
    assertThat(underlyingBuffer.getShort(typeOffset(bufferOffset))).isEqualTo(TYPE_MESSAGE);
    assertThat(underlyingBuffer.getByte(flagsOffset(bufferOffset))).isEqualTo((byte) 0);

    bufferOffset += alignedFramedLength(MESSAGE_LENGTH);
    assertThat(underlyingBuffer.getInt(lengthOffset(bufferOffset)))
        .isEqualTo(FRAGMENT_LENGTH - alignedFramedLength(MESSAGE_LENGTH));
    assertThat(underlyingBuffer.getShort(typeOffset(bufferOffset))).isEqualTo(TYPE_PADDING);
    assertThat(underlyingBuffer.getByte(flagsOffset(bufferOffset))).isEqualTo((byte) 0);
  }

  @Test
  public void shouldFillBatchCompletely() {
    // given
    claimedBatch.wrap(
        underlyingBuffer, PARTITION_ID, PARTITION_OFFSET, FRAGMENT_LENGTH, DO_NOTHING);

    claimedBatch.nextFragment(MESSAGE_LENGTH, 1);

    // when
    final int remainingCapacity = FRAGMENT_LENGTH - alignedFramedLength(MESSAGE_LENGTH);
    final int fragmentLength = remainingCapacity - HEADER_LENGTH;

    claimedBatch.nextFragment(fragmentLength, 2);
    claimedBatch.commit();

    // then
    final int bufferOffset = PARTITION_OFFSET + alignedFramedLength(MESSAGE_LENGTH);
    assertThat(underlyingBuffer.getInt(lengthOffset(bufferOffset)))
        .isEqualTo(framedLength(fragmentLength));
  }

  @Test
  public void shouldAddFragmentIfRemainingCapacityIsLessThanAlignment() {
    // given
    claimedBatch.wrap(
        underlyingBuffer, PARTITION_ID, PARTITION_OFFSET, FRAGMENT_LENGTH, DO_NOTHING);

    claimedBatch.nextFragment(MESSAGE_LENGTH, 1);

    // when
    final int remainingCapacity = FRAGMENT_LENGTH - alignedFramedLength(MESSAGE_LENGTH);
    final int fragmentLength = remainingCapacity - HEADER_LENGTH - FRAME_ALIGNMENT + 1;

    claimedBatch.nextFragment(fragmentLength, 2);
    claimedBatch.commit();

    // then
    final int bufferOffset = PARTITION_OFFSET + alignedFramedLength(MESSAGE_LENGTH);
    assertThat(underlyingBuffer.getInt(lengthOffset(bufferOffset)))
        .isEqualTo(framedLength(fragmentLength));
  }

  @Test
  public void shouldFailToAddFragmentIfGreaterThanRemainingCapacity() {
    // given
    claimedBatch.wrap(
        underlyingBuffer, PARTITION_ID, PARTITION_OFFSET, FRAGMENT_LENGTH, DO_NOTHING);

    claimedBatch.nextFragment(MESSAGE_LENGTH, 1);

    // then
    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("The given fragment length is greater than the remaining capacity");

    // when
    final int remainingCapacity = FRAGMENT_LENGTH - alignedFramedLength(MESSAGE_LENGTH);

    claimedBatch.nextFragment(remainingCapacity - HEADER_LENGTH + 1, 2);
  }

  @Test
  public void shouldFailToAddFragmentIfRemainingCapacityIsLessThanPaddingMessage() {
    // given
    claimedBatch.wrap(
        underlyingBuffer, PARTITION_ID, PARTITION_OFFSET, FRAGMENT_LENGTH, DO_NOTHING);

    claimedBatch.nextFragment(MESSAGE_LENGTH, 1);

    // then
    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("The given fragment length is greater than the remaining capacity");

    // when
    final int remainingCapacity = FRAGMENT_LENGTH - alignedFramedLength(MESSAGE_LENGTH);

    claimedBatch.nextFragment(remainingCapacity - HEADER_LENGTH - FRAME_ALIGNMENT, 2);
  }
}
