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
package io.zeebe.util.sched;

import io.zeebe.util.metrics.MetricsManager;
import io.zeebe.util.sched.clock.ActorClock;
import io.zeebe.util.sched.future.ActorFuture;
import io.zeebe.util.sched.metrics.ActorThreadMetrics;
import java.io.IOException;
import java.io.PrintStream;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.agrona.ExpandableArrayBuffer;

public class ActorScheduler {
  private final AtomicReference<SchedulerState> state = new AtomicReference<>();
  private final ActorExecutor actorTaskExecutor;
  private final MetricsManager metricsManager;

  public ActorScheduler(ActorSchedulerBuilder builder) {
    state.set(SchedulerState.NEW);
    actorTaskExecutor = builder.getActorExecutor();
    metricsManager = builder.getMetricsManager();
  }

  /**
   * Submits an non-blocking, CPU-bound actor.
   *
   * @param actor the actor to submit
   * @param collectTaskMetrics controls whether metrics should be written for this actor. This has
   *     the overhead cost (time & memory) for allocating the counters which are used to record the
   *     metrics. Generally, metrics should not be recorded for short-lived tasks but only make
   *     sense for long-lived tasks where a small overhead when initially submitting the actor is
   *     acceptable.
   */
  public ActorFuture<Void> submitActor(Actor actor) {
    return submitActor(actor, false);
  }

  /**
   * Submits an non-blocking, CPU-bound actor.
   *
   * @param actor the actor to submit
   * @param collectTaskMetrics controls whether metrics should be written for this actor. This has
   *     the overhead cost (time & memory) for allocating the counters which are used to record the
   *     metrics. Generally, metrics should not be recorded for short-lived tasks but only make
   *     sense for long-lived tasks where a small overhead when initially submitting the actor is
   *     acceptable.
   */
  public ActorFuture<Void> submitActor(Actor actor, boolean collectTaskMetrics) {
    return actorTaskExecutor.submitCpuBound(actor.actor.task, collectTaskMetrics);
  }

  /**
   * Submits an actor providing hints to the scheduler about how to best schedule the actor. Actors
   * must always be non-blocking. On top of that, the scheduler distinguishes
   *
   * <ul>
   *   <li>CPU-bound actors: actors which perform no or very little blocking I/O. It is possible to
   *       specify a priority.
   *   <li>I/O-bound actors: actors where the runtime is dominated by performing <strong>blocking
   *       I/O</strong> (usually filesystem writes). It is possible to specify the I/O device used
   *       by the actor.
   * </ul>
   *
   * Scheduling hints can be created using the {@link SchedulingHints} class.
   *
   * @param actor the actor to submit
   * @param collectTaskMetrics controls whether metrics should be written for this actor. This has
   *     the overhead cost (time & memory) for allocating the counters which are used to record the
   *     metrics. Generally, metrics should not be recorded for short-lived tasks but only make
   *     sense for long-lived tasks where a small overhead when initially submitting the actor is
   *     acceptable.
   * @param schedulingHints additional scheduling hint
   */
  public ActorFuture<Void> submitActor(
      Actor actor, boolean collectTaskMetrics, int schedulingHints) {
    final ActorTask task = actor.actor.task;

    final ActorFuture<Void> startingFuture;
    if (SchedulingHints.isCpuBound(schedulingHints)) {
      task.setPriority(SchedulingHints.getPriority(schedulingHints));
      startingFuture = actorTaskExecutor.submitCpuBound(task, collectTaskMetrics);
    } else {
      task.setDeviceId(SchedulingHints.getIoDevice(schedulingHints));
      startingFuture = actorTaskExecutor.submitIoBoundTask(task, collectTaskMetrics);
    }
    return startingFuture;
  }

  public void start() {
    if (state.compareAndSet(SchedulerState.NEW, SchedulerState.RUNNING)) {
      actorTaskExecutor.start();
    } else {
      throw new IllegalStateException("Cannot start scheduler already started.");
    }
  }

  public Future<Void> stop() {
    if (state.compareAndSet(SchedulerState.RUNNING, SchedulerState.TERMINATING)) {

      return actorTaskExecutor
          .closeAsync()
          .thenRun(
              () -> {
                state.set(SchedulerState.TERMINATED);
              });
    } else {
      throw new IllegalStateException("Cannot stop scheduler not running");
    }
  }

  public void dumpMetrics(PrintStream ps) {
    final ExpandableArrayBuffer buff = new ExpandableArrayBuffer();
    metricsManager.dump(buff, 0, System.currentTimeMillis());

    try {
      ps.write(buff.byteArray());
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  public static ActorSchedulerBuilder newActorScheduler() {
    return new ActorSchedulerBuilder();
  }

  public static ActorScheduler newDefaultActorScheduler() {
    return new ActorSchedulerBuilder().build();
  }

  public static class ActorSchedulerBuilder {
    private String schedulerName = "";
    private ActorClock actorClock;
    private MetricsManager metricsManager;

    private int cpuBoundThreadsCount = Math.max(1, Runtime.getRuntime().availableProcessors() - 2);
    private ActorThreadGroup cpuBoundActorGroup;
    private double[] priorityQuotas = new double[] {0.60, 0.30, 0.10};

    private int ioBoundThreadsCount = 2;
    private ActorThreadGroup ioBoundActorGroup;
    private int[] ioDeviceConcurrency = new int[] {2};

    private ActorThreadFactory actorThreadFactory;
    private ThreadPoolExecutor blockingTasksRunner;
    private Duration blockingTasksShutdownTime = Duration.ofSeconds(15);
    private ActorExecutor actorExecutor;

    private ActorTimerQueue actorTimerQueue;

    public ActorSchedulerBuilder setActorTimerQueue(ActorTimerQueue actorTimerQueue) {
      this.actorTimerQueue = actorTimerQueue;
      return this;
    }

    public ActorSchedulerBuilder setActorClock(ActorClock actorClock) {
      this.actorClock = actorClock;
      return this;
    }

    public ActorSchedulerBuilder setMetricsManager(MetricsManager metricsManager) {
      this.metricsManager = metricsManager;
      return this;
    }

    public ActorSchedulerBuilder setCpuBoundActorThreadCount(int actorThreadCount) {
      this.cpuBoundThreadsCount = actorThreadCount;
      return this;
    }

    public ActorSchedulerBuilder setIoBoundActorThreadCount(int ioBoundActorsThreadCount) {
      this.ioBoundThreadsCount = ioBoundActorsThreadCount;
      return this;
    }

    public ActorSchedulerBuilder setPriorityQuotas(double[] priorityQuotas) {
      this.priorityQuotas = priorityQuotas;
      return this;
    }

    public ActorSchedulerBuilder setActorThreadFactory(ActorThreadFactory actorThreadFactory) {
      this.actorThreadFactory = actorThreadFactory;
      return this;
    }

    public ActorSchedulerBuilder setBlockingTasksRunner(ThreadPoolExecutor blockingTasksRunner) {
      this.blockingTasksRunner = blockingTasksRunner;
      return this;
    }

    public ActorSchedulerBuilder setBlockingTasksShutdownTime(Duration blockingTasksShutdownTime) {
      this.blockingTasksShutdownTime = blockingTasksShutdownTime;
      return this;
    }

    public ActorSchedulerBuilder setActorExecutor(ActorExecutor actorExecutor) {
      this.actorExecutor = actorExecutor;
      return this;
    }

    public ActorSchedulerBuilder setIoDeviceConcurrency(int[] ioDeviceConcurrency) {
      this.ioDeviceConcurrency = ioDeviceConcurrency;
      return this;
    }

    public ActorSchedulerBuilder setSchedulerName(String schedulerName) {
      this.schedulerName = schedulerName;
      return this;
    }

    public String getSchedulerName() {
      return schedulerName;
    }

    public ActorClock getActorClock() {
      return actorClock;
    }

    public ActorTimerQueue getActorTimerQueue() {
      return actorTimerQueue;
    }

    public MetricsManager getMetricsManager() {
      return metricsManager;
    }

    public int getCpuBoundActorThreadCount() {
      return cpuBoundThreadsCount;
    }

    public int getIoBoundActorThreadCount() {
      return ioBoundThreadsCount;
    }

    public double[] getPriorityQuotas() {
      return priorityQuotas;
    }

    public ActorThreadFactory getActorThreadFactory() {
      return actorThreadFactory;
    }

    public ThreadPoolExecutor getBlockingTasksRunner() {
      return blockingTasksRunner;
    }

    public Duration getBlockingTasksShutdownTime() {
      return blockingTasksShutdownTime;
    }

    public ActorExecutor getActorExecutor() {
      return actorExecutor;
    }

    public int[] getIoDeviceConcurrency() {
      return ioDeviceConcurrency;
    }

    public ActorThreadGroup getCpuBoundActorThreads() {
      return cpuBoundActorGroup;
    }

    public ActorThreadGroup getIoBoundActorThreads() {
      return ioBoundActorGroup;
    }

    private void initMetricsManager() {
      if (metricsManager == null) {
        metricsManager = new MetricsManager();
      }
    }

    private void initActorThreadFactory() {
      if (actorThreadFactory == null) {
        actorThreadFactory = new DefaultActorThreadFactory();
      }
    }

    private void initBlockingTaskRunner() {
      if (blockingTasksRunner == null) {
        blockingTasksRunner =
            new ThreadPoolExecutor(
                1,
                Integer.MAX_VALUE,
                60L,
                TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                new BlockingTasksThreadFactory());
      }
    }

    private void initIoBoundActorThreadGroup() {
      if (ioBoundActorGroup == null) {
        ioBoundActorGroup = new IoBoundThreadGroup(this);
      }
    }

    private void initCpuBoundActorThreadGroup() {
      if (cpuBoundActorGroup == null) {
        cpuBoundActorGroup = new CpuBoundThreadGroup(this);
      }
    }

    private void initActorExecutor() {
      if (actorExecutor == null) {
        actorExecutor = new ActorExecutor(this);
      }
    }

    public ActorScheduler build() {
      initMetricsManager();
      initActorThreadFactory();
      initBlockingTaskRunner();
      initCpuBoundActorThreadGroup();
      initIoBoundActorThreadGroup();
      initActorExecutor();
      return new ActorScheduler(this);
    }
  }

  public interface ActorThreadFactory {
    ActorThread newThread(
        String name,
        int id,
        ActorThreadGroup threadGroup,
        TaskScheduler taskScheduler,
        ActorClock clock,
        ActorThreadMetrics metrics,
        ActorTimerQueue timerQueue);
  }

  public static class DefaultActorThreadFactory implements ActorThreadFactory {
    @Override
    public ActorThread newThread(
        String name,
        int id,
        ActorThreadGroup threadGroup,
        TaskScheduler taskScheduler,
        ActorClock clock,
        ActorThreadMetrics metrics,
        ActorTimerQueue timerQueue) {
      return new ActorThread(name, id, threadGroup, taskScheduler, clock, metrics, timerQueue);
    }
  }

  public static class BlockingTasksThreadFactory implements ThreadFactory {
    final AtomicLong idGenerator = new AtomicLong();

    @Override
    public Thread newThread(Runnable r) {
      final Thread thread = new Thread(r);
      thread.setName("zb-blocking-task-runner-" + idGenerator.incrementAndGet());
      return thread;
    }
  }

  private enum SchedulerState {
    NEW,
    RUNNING,
    TERMINATING,
    TERMINATED // scheduler is not reusable
  }

  public MetricsManager getMetricsManager() {
    return metricsManager;
  }
}
