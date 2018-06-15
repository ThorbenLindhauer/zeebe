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
package io.zeebe.servicecontainer.impl;

import static io.zeebe.servicecontainer.impl.ActorFutureAssertions.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.*;

import io.zeebe.servicecontainer.*;
import io.zeebe.util.sched.future.ActorFuture;
import io.zeebe.util.sched.future.CompletableActorFuture;
import io.zeebe.util.sched.testing.ControlledActorSchedulerRule;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;

@SuppressWarnings("unchecked")
public class AsyncStartTest {
  @Rule public ControlledActorSchedulerRule actorSchedulerRule = new ControlledActorSchedulerRule();

  ServiceContainer serviceContainer;

  ServiceName<Object> service1Name;
  ServiceName<Object> service2Name;

  @Before
  public void setup() {
    serviceContainer = new ServiceContainerImpl(actorSchedulerRule.get());
    serviceContainer.start();

    service1Name = ServiceName.newServiceName("service1", Object.class);
    service2Name = ServiceName.newServiceName("service2", Object.class);
  }

  @Test
  public void shouldWaitForAsyncStart() {
    // when
    final AsyncStartService service = new AsyncStartService();
    service.future = new CompletableActorFuture<>();

    final ActorFuture<Object> startFuture =
        serviceContainer.createService(service1Name, service).install();
    actorSchedulerRule.workUntilDone();

    // then
    assertNotCompleted(startFuture);
  }

  @Test
  public void shouldContinueOnAsyncStartComplete() {
    // given
    final AsyncStartService service = new AsyncStartService();
    service.future = new CompletableActorFuture<Void>();

    final ActorFuture<Object> startFuture =
        serviceContainer.createService(service1Name, service).install();
    actorSchedulerRule.workUntilDone();
    assertNotCompleted(startFuture);

    // when
    service.future.complete(null);
    actorSchedulerRule.workUntilDone();

    // then
    assertCompleted(startFuture);
  }

  @Test
  public void shouldContinueOnAsyncStartCompleteAndReturn() {
    // given
    final AsyncStartService service = new AsyncStartService();
    service.future = new CompletableActorFuture<Void>();

    final ActorFuture<Object> startFuture =
        serviceContainer.createService(service1Name, service).install();

    actorSchedulerRule.workUntilDone();
    assertNotCompleted(startFuture);

    // when
    service.future.complete(null);
    actorSchedulerRule.workUntilDone();

    // then
    assertCompleted(startFuture);
    assertEquals(service.get(), startFuture.join());
  }

  @Test
  public void shouldContinueOnAsyncStartCompletedExceptionally() {
    // given
    final AsyncStartService service = new AsyncStartService();
    service.future = new CompletableActorFuture<Void>();

    final ActorFuture<Object> startFuture =
        serviceContainer.createService(service1Name, service).install();
    actorSchedulerRule.workUntilDone();

    // when
    service.future.completeExceptionally(new RuntimeException());
    actorSchedulerRule.workUntilDone();

    // then
    assertFailed(startFuture);
  }

  @Test
  public void shouldWaitOnSuppliedFuture() {
    // when
    final AsyncStartService service = new AsyncStartService();
    service.future = new CompletableActorFuture<>();

    final ActorFuture<Object> startFuture =
        serviceContainer.createService(service1Name, service).install();
    actorSchedulerRule.workUntilDone();

    // then
    assertNotCompleted(startFuture);
  }

  @Test
  public void shouldWaitForAction() {
    // when
    final AsyncStartService service = new AsyncStartService();
    final Runnable mockAction = mock(Runnable.class);
    service.action = mockAction;

    final ActorFuture<Object> startFuture =
        serviceContainer.createService(service1Name, service).install();
    actorSchedulerRule.workUntilDone();

    // then
    assertNotCompleted(startFuture);
  }

  @Test
  public void shouldContinueOnAction() {
    // given
    final AsyncStartService service = new AsyncStartService();
    final Runnable mockAction = mock(Runnable.class);
    service.action = mockAction;

    final ActorFuture<Object> startFuture =
        serviceContainer.createService(service1Name, service).install();
    actorSchedulerRule.workUntilDone();
    actorSchedulerRule.awaitBlockingTasksCompleted(1);

    // when
    verify(mockAction).run();
    actorSchedulerRule.workUntilDone();

    // then
    assertCompleted(startFuture);
  }

  @Test
  @Ignore
  public void shouldStopOnExceptionFromAction() {
    // given
    final AsyncStartService service = new AsyncStartService();
    final Runnable mockAction = mock(Runnable.class);

    doThrow(new RuntimeException()).when(mockAction).run();

    service.action = mockAction;

    final ActorFuture<Object> startFuture =
        serviceContainer.createService(service1Name, service).install();

    // when
    actorSchedulerRule.workUntilDone();

    // then
    assertFailed(startFuture);
    verify(mockAction).run();
  }

  @Test
  public void shouldContineOnSuppliedFuture() {
    // given
    final AsyncStartService service = new AsyncStartService();
    service.future = new CompletableActorFuture<>();

    final ActorFuture<Object> startFuture =
        serviceContainer.createService(service1Name, service).install();
    actorSchedulerRule.workUntilDone();

    // when
    service.future.complete(null);
    actorSchedulerRule.workUntilDone();

    // then
    assertCompleted(startFuture);
  }

  @Test
  @Ignore
  public void shouldFailOnSuppliedFutureCompletedExceptionally() {
    // given
    final AsyncStartService service = new AsyncStartService();
    service.future = new CompletableActorFuture<>();

    final ActorFuture<Object> startFuture =
        serviceContainer.createService(service1Name, service).install();
    actorSchedulerRule.workUntilDone();

    // when
    service.future.completeExceptionally(new Throwable());
    actorSchedulerRule.workUntilDone();

    // then
    assertFailed(startFuture);
  }

  @Test
  public void shouldWaitOnConcurrentStart() {
    // when
    final AsyncStartService service = new AsyncStartService();
    service.future = new CompletableActorFuture<Void>();

    final ActorFuture<Object> service1StartFuture =
        serviceContainer.createService(service1Name, service).install();
    final ActorFuture service2StartFuture =
        serviceContainer
            .createService(service2Name, mock(Service.class))
            .dependency(service1Name)
            .install();
    actorSchedulerRule.workUntilDone();

    // then
    assertNotCompleted(service1StartFuture);
    assertNotCompleted(service2StartFuture);
  }

  @Test
  public void shouldContinueConcurrentStart() {
    // given
    final AsyncStartService service = new AsyncStartService();
    service.future = new CompletableActorFuture<Void>();

    final ActorFuture<Object> service1StartFuture =
        serviceContainer.createService(service1Name, service).install();
    final ActorFuture service2StartFuture =
        serviceContainer
            .createService(service2Name, mock(Service.class))
            .dependency(service1Name)
            .install();
    actorSchedulerRule.workUntilDone();

    // when
    service.future.complete(null);
    actorSchedulerRule.workUntilDone();

    // then
    assertCompleted(service1StartFuture);
    assertCompleted(service2StartFuture);
  }

  @Test
  @Ignore
  public void shouldFailConcurrentStart() {
    // given
    final AsyncStartService service = new AsyncStartService();

    final ActorFuture<Object> service1StartFuture =
        serviceContainer.createService(service1Name, service).install();
    final ActorFuture service2StartFuture =
        serviceContainer
            .createService(service2Name, mock(Service.class))
            .dependency(service1Name)
            .install();
    actorSchedulerRule.workUntilDone();

    // when
    service.future.completeExceptionally(new Throwable());
    actorSchedulerRule.workUntilDone();

    // then
    assertFailed(service1StartFuture);
    assertFailed(service2StartFuture);
  }

  @Test
  public void shouldWaitForAsyncStartWhenRemovedConcurrently() {
    // given
    final AsyncStartService service = new AsyncStartService();
    service.future = new CompletableActorFuture<>();

    final ActorFuture<Object> service1StartFuture =
        serviceContainer.createService(service1Name, service).install();
    actorSchedulerRule.workUntilDone();

    // when
    final ActorFuture<Void> removeFuture = serviceContainer.removeService(service1Name);
    actorSchedulerRule.workUntilDone();

    // then
    assertNotCompleted(service1StartFuture);
    assertNotCompleted(removeFuture);

    // AND

    // when
    service.future.complete(null);
    actorSchedulerRule.workUntilDone();

    // then
    assertFailed(service1StartFuture);
    assertCompleted(removeFuture);
    assertThat(service.wasStopped).isTrue();
  }

  @Test
  public void shouldWaitForAsyncStartWhenRemovedConcurrentlyFailure() {
    // given
    final AsyncStartService service = new AsyncStartService();
    service.future = new CompletableActorFuture<Void>();

    final ActorFuture<Object> service1StartFuture =
        serviceContainer.createService(service1Name, service).install();

    actorSchedulerRule.workUntilDone();

    // when
    final ActorFuture<Void> removeFuture = serviceContainer.removeService(service1Name);
    actorSchedulerRule.workUntilDone();

    // then
    assertNotCompleted(service1StartFuture);
    assertNotCompleted(removeFuture);

    // AND

    // when
    service.future.completeExceptionally(new Throwable()); // async start completes exceptionally
    actorSchedulerRule.workUntilDone();

    // then
    assertFailed(service1StartFuture);
    assertCompleted(removeFuture);
    assertThat(service.wasStopped).isFalse();
  }

  static class AsyncStartService implements Service<Object> {
    CompletableActorFuture<Void> future;
    Object value = new Object();
    Runnable action;
    volatile boolean wasStopped = false;

    @Override
    public void start(ServiceStartContext startContext) {
      if (action != null) {
        startContext.run(action);
      } else if (future != null) {
        startContext.async(future);
      }
    }

    @Override
    public void stop(ServiceStopContext stopContext) {
      wasStopped = true;
    }

    @Override
    public Object get() {
      return value;
    }
  }
}
