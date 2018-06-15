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
package io.zeebe.logstreams.util;

import io.zeebe.logstreams.LogStreams;
import io.zeebe.logstreams.impl.LogStreamBuilder;
import io.zeebe.logstreams.log.LogStream;
import io.zeebe.logstreams.spi.SnapshotStorage;
import io.zeebe.servicecontainer.ServiceContainer;
import io.zeebe.servicecontainer.impl.ServiceContainerImpl;
import io.zeebe.util.buffer.BufferUtil;
import io.zeebe.util.sched.ActorScheduler;
import io.zeebe.util.sched.clock.ControlledActorClock;
import io.zeebe.util.sched.testing.ActorSchedulerRule;
import java.util.function.Consumer;
import org.junit.rules.ExternalResource;
import org.junit.rules.TemporaryFolder;

public class LogStreamRule extends ExternalResource {
  public static final String DEFAULT_NAME = "test-logstream";

  private final String name;
  private final TemporaryFolder temporaryFolder;

  private ActorScheduler actorScheduler;
  private ServiceContainer serviceContainer;
  private LogStream logStream;

  private ControlledActorClock clock = new ControlledActorClock();
  private SnapshotStorage snapshotStorage;

  private final Consumer<LogStreamBuilder> streamBuilder;

  private LogStreamBuilder builder;

  public LogStreamRule(final TemporaryFolder temporaryFolder) {
    this(DEFAULT_NAME, temporaryFolder);
  }

  public LogStreamRule(
      final TemporaryFolder temporaryFolder, final Consumer<LogStreamBuilder> streamBuilder) {
    this(DEFAULT_NAME, temporaryFolder, streamBuilder);
  }

  public LogStreamRule(final String name, final TemporaryFolder temporaryFolder) {
    this(name, temporaryFolder, b -> {});
  }

  public LogStreamRule(
      final String name,
      final TemporaryFolder temporaryFolder,
      final Consumer<LogStreamBuilder> streamBuilder) {
    this.name = name;
    this.temporaryFolder = temporaryFolder;
    this.streamBuilder = streamBuilder;
  }

  @Override
  protected void before() {
    actorScheduler = new ActorSchedulerRule(clock).get();
    actorScheduler.start();

    serviceContainer = new ServiceContainerImpl(actorScheduler);
    serviceContainer.start();

    builder =
        LogStreams.createFsLogStream(BufferUtil.wrapString(name), 0)
            .logDirectory(temporaryFolder.getRoot().getAbsolutePath())
            .serviceContainer(serviceContainer);

    // apply additional configs
    streamBuilder.accept(builder);

    openLogStream();
  }

  @Override
  protected void after() {
    if (logStream != null) {
      logStream.close();
    }

    actorScheduler.stop();
  }

  public void closeLogStream() {
    logStream.close();
    logStream = null;
    snapshotStorage = null;
  }

  public void openLogStream() {
    logStream = builder.build().join();
    snapshotStorage = builder.getSnapshotStorage();
    logStream.openAppender().join();
  }

  public LogStream getLogStream() {
    return logStream;
  }

  public SnapshotStorage getSnapshotStorage() {
    return snapshotStorage;
  }

  public void setCommitPosition(final long position) {
    logStream.setCommitPosition(position);
  }

  public long getCommitPosition() {
    return logStream.getCommitPosition();
  }

  public ControlledActorClock getClock() {
    return clock;
  }

  public ActorScheduler getActorScheduler() {
    return actorScheduler;
  }

  public ServiceContainer getServiceContainer() {
    return serviceContainer;
  }
}
