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
package io.zeebe.test.util.stream;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Test;

public class StreamWrapperTest {

  @Test
  public void shouldSkipElementsBasedOnPredicate() {
    // given
    final Stream<Integer> stream = Arrays.asList(1, 2, 3, 4, 5).stream();
    final StreamWrapper<Integer> wrapper = new StreamWrapper<>(stream);

    // when
    final List<Integer> result = wrapper.skipUntil(i -> i == 3).collect(Collectors.toList());

    // then
    assertThat(result).containsExactly(3, 4, 5);
  }
}
