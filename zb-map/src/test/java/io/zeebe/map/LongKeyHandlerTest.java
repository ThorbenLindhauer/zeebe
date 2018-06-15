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
package io.zeebe.map;

import static org.assertj.core.api.Assertions.assertThat;

import io.zeebe.map.types.LongKeyHandler;
import java.util.Random;
import java.util.function.Consumer;
import org.agrona.BitUtil;
import org.junit.Test;

/** */
public class LongKeyHandlerTest {
  private static final int DATA_COUNT = 10_000_000;

  private final int bufferSize = 1 << 30;
  private final int partitionSize = BitUtil.align(bufferSize / 3, 8);

  public static long position(int partitionId, int partitionOffset) {
    return (long) partitionId << 32 | (long) partitionOffset & 4294967295L;
  }

  @Test
  public void shouldGenerateSameHashes() {
    // given
    final LongKeyHandler keyHandler = new LongKeyHandler();
    final int hashes[] = new int[DATA_COUNT];

    // when
    for (int i = 0; i < DATA_COUNT; i++) {
      keyHandler.theKey = i;
      hashes[i] = keyHandler.keyHashCode();
    }

    // then
    for (int i = 0; i < DATA_COUNT; i++) {
      keyHandler.theKey = i;
      assertThat(keyHandler.keyHashCode()).isEqualTo(hashes[i]);
    }
  }

  @Test
  public void shouldAddLinearKeys() {
    final Long2LongZbMap map = new Long2LongZbMap();

    for (int i = 0; i < DATA_COUNT; i++) {
      map.put(i, i);
    }

    assertThat(map.hashTable.getCapacity()).isLessThan(DATA_COUNT / 2);
  }

  @Test
  public void shouldAddKeysToMap() {
    final Long2LongZbMap map = new Long2LongZbMap();
    positionAsKeyProducer(
        (position) -> {
          map.put(position, position);
        });

    assertThat(map.hashTable.getCapacity()).isLessThan(DATA_COUNT / 2);
  }

  private void positionAsKeyProducer(Consumer<Long> consumer) {
    int segmentId = 0;
    int segmentOffset = 0;
    final Random random = new Random(1 << 15);

    for (int i = 0; i < DATA_COUNT; i++) {
      final long position = position(segmentId, segmentOffset);
      consumer.accept(position);

      if (i % partitionSize == 0) {
        segmentId++;
        segmentOffset = 0;
      }

      final int eventSize = random.nextInt(200);
      segmentOffset += BitUtil.align(eventSize, 8);
    }
  }
}
