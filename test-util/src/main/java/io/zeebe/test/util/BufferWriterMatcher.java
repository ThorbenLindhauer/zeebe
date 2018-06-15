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
package io.zeebe.test.util;

import io.zeebe.util.buffer.BufferReader;
import io.zeebe.util.buffer.BufferWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.agrona.concurrent.UnsafeBuffer;
import org.hamcrest.Matcher;
import org.mockito.ArgumentMatcher;

/**
 * Note: this matcher does not work when a {@link BufferWriter} is reused throughout a test. Mockito
 * only captures the reference, so after the test the {@link BufferWriter} contains the latest
 * state.
 *
 * @author Lindhauer
 */
public class BufferWriterMatcher<T extends BufferReader> implements ArgumentMatcher<BufferWriter> {
  protected T reader;

  protected List<BufferReaderMatch<T>> propertyMatchers = new ArrayList<>();

  public BufferWriterMatcher(T reader) {
    this.reader = reader;
  }

  @Override
  public boolean matches(BufferWriter argument) {
    if (argument == null) {
      return false;
    }

    final UnsafeBuffer buffer = new UnsafeBuffer(new byte[argument.getLength()]);
    argument.write(buffer, 0);

    reader.wrap(buffer, 0, buffer.capacity());

    for (BufferReaderMatch<T> matcher : propertyMatchers) {
      if (!matcher.matches(reader)) {
        return false;
      }
    }

    return true;
  }

  public BufferWriterMatcher<T> matching(Function<T, Object> actualProperty, Object expectedValue) {
    final BufferReaderMatch<T> match = new BufferReaderMatch<>();
    match.propertyExtractor = actualProperty;

    if (expectedValue instanceof Matcher) {
      match.expectedValueMatcher = (Matcher<?>) expectedValue;
    } else {
      match.expectedValue = expectedValue;
    }

    propertyMatchers.add(match);

    return this;
  }

  public static <T extends BufferReader> BufferWriterMatcher<T> writesProperties(
      Class<T> readerClass) {
    try {
      final BufferWriterMatcher<T> matcher = new BufferWriterMatcher<>(readerClass.newInstance());

      return matcher;
    } catch (Exception e) {
      throw new RuntimeException("Could not construct matcher", e);
    }
  }
}
