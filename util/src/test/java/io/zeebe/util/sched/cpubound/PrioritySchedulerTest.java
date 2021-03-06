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
package io.zeebe.util.sched.cpubound;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.zeebe.util.sched.ActorTask;
import io.zeebe.util.sched.PriorityScheduler;
import io.zeebe.util.sched.clock.ActorClock;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import org.junit.Test;
import org.mockito.InOrder;

@SuppressWarnings("unchecked")
public class PrioritySchedulerTest {

  @Test
  public void testRequestsTasksAccordingToPriorityQuotas() {
    final ActorTask task = mock(ActorTask.class);
    final IntFunction<ActorTask> getTaskFn = mock(IntFunction.class);
    final ActorClock clock = mock(ActorClock.class);

    when(clock.getNanoTime()).thenReturn(0L);
    when(getTaskFn.apply(anyInt())).thenReturn(task);

    final PriorityScheduler scheduler =
        new PriorityScheduler(getTaskFn, new double[] {0.2, 0.3, 0.5});

    for (int i = 0; i < 100; i++) {
      when(clock.getNanoTime()).thenReturn(TimeUnit.MILLISECONDS.toNanos(i * 10));
      scheduler.getNextTask(clock);
    }

    verify(getTaskFn, times(20)).apply(0);
    verify(getTaskFn, times(30)).apply(1);
    verify(getTaskFn, times(50)).apply(2);
  }

  @Test
  public void testRequestsTasksAccordingToPriorityQuotas2Runs() {
    final ActorTask task = mock(ActorTask.class);
    final IntFunction<ActorTask> getTaskFn = mock(IntFunction.class);
    final ActorClock clock = mock(ActorClock.class);

    when(clock.getNanoTime()).thenReturn(0L);
    when(getTaskFn.apply(anyInt())).thenReturn(task);

    final PriorityScheduler scheduler =
        new PriorityScheduler(getTaskFn, new double[] {0.3, 0.3, 0.4});

    for (int i = 0; i < 200; i++) {
      when(clock.getNanoTime()).thenReturn(TimeUnit.MILLISECONDS.toNanos(i * 10));
      scheduler.getNextTask(clock);
    }

    verify(getTaskFn, times(60)).apply(0);
    verify(getTaskFn, times(60)).apply(1);
    verify(getTaskFn, times(80)).apply(2);
  }

  @Test
  public void testSelectNextHighestPriorityWhenTaskUnavailable() {
    // given
    final ActorTask task = mock(ActorTask.class);
    final ActorClock clock = mock(ActorClock.class);

    when(clock.getNanoTime()).thenReturn(0L);

    // given
    IntFunction<ActorTask> getTaskFn = mock(IntFunction.class);
    PriorityScheduler scheduler = new PriorityScheduler(getTaskFn, new double[] {0, 0, 1});
    when(getTaskFn.apply(eq(2))).thenReturn(null);
    when(getTaskFn.apply(eq(1))).thenReturn(task);
    // when
    scheduler.getNextTask(clock);
    // then
    InOrder inOrder = inOrder(getTaskFn);
    inOrder.verify(getTaskFn).apply(2);
    inOrder.verify(getTaskFn).apply(1);
    inOrder.verifyNoMoreInteractions();

    // given
    getTaskFn = mock(IntFunction.class);
    scheduler = new PriorityScheduler(getTaskFn, new double[] {0, 1, 0});
    when(getTaskFn.apply(eq(1))).thenReturn(null);
    when(getTaskFn.apply(eq(0))).thenReturn(task);
    // when
    scheduler.getNextTask(clock);
    // then
    inOrder = inOrder(getTaskFn);
    inOrder.verify(getTaskFn).apply(1);
    inOrder.verify(getTaskFn).apply(0);
    inOrder.verifyNoMoreInteractions();

    // given
    getTaskFn = mock(IntFunction.class);
    scheduler = new PriorityScheduler(getTaskFn, new double[] {1, 0, 0});
    when(getTaskFn.apply(eq(0))).thenReturn(null);
    when(getTaskFn.apply(eq(2))).thenReturn(task);
    // when
    scheduler.getNextTask(clock);
    // then
    inOrder = inOrder(getTaskFn);
    inOrder.verify(getTaskFn).apply(0);
    inOrder.verify(getTaskFn).apply(2);
    inOrder.verifyNoMoreInteractions();
  }
}
