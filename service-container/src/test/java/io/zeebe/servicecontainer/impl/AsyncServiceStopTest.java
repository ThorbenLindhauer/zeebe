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

import static io.zeebe.servicecontainer.impl.ActorFutureAssertions.assertCompleted;
import static io.zeebe.servicecontainer.impl.ActorFutureAssertions.assertNotCompleted;
import static org.mockito.Mockito.*;

import io.zeebe.servicecontainer.*;
import io.zeebe.servicecontainer.testing.ServiceContainerRule;
import io.zeebe.util.sched.future.ActorFuture;
import io.zeebe.util.sched.future.CompletableActorFuture;
import io.zeebe.util.sched.testing.ControlledActorSchedulerRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;

@SuppressWarnings("unchecked")
public class AsyncServiceStopTest {
  public ControlledActorSchedulerRule actorSchedulerRule = new ControlledActorSchedulerRule();
  public ServiceContainerRule serviceContainerRule = new ServiceContainerRule(actorSchedulerRule);

  @Rule
  public RuleChain ruleChain = RuleChain.outerRule(actorSchedulerRule).around(serviceContainerRule);

  ServiceName<Object> service1Name = ServiceName.newServiceName("service1", Object.class);
  ServiceName<Object> service2Name = ServiceName.newServiceName("service2", Object.class);

  @Test
  public void shouldWaitForAsyncStop() {
    final ServiceContainer serviceContainer = serviceContainerRule.get();

    // given
    final AsyncStopService service = new AsyncStopService();
    service.future = new CompletableActorFuture<Void>();

    serviceContainer.createService(service1Name, service).install();
    actorSchedulerRule.workUntilDone();

    // when
    final ActorFuture<Void> removeFuture = serviceContainer.removeService(service1Name);
    actorSchedulerRule.workUntilDone();

    // then
    assertNotCompleted(removeFuture);
  }

  @Test
  public void shouldContinueOnAsyncStopComplete() {
    final ServiceContainer serviceContainer = serviceContainerRule.get();

    // given
    final AsyncStopService service = new AsyncStopService();
    service.future = new CompletableActorFuture<Void>();

    serviceContainer.createService(service1Name, service).install();
    actorSchedulerRule.workUntilDone();

    final ActorFuture<Void> removeFuture = serviceContainer.removeService(service1Name);
    actorSchedulerRule.workUntilDone();

    // when
    service.future.complete(null);
    actorSchedulerRule.workUntilDone();

    // then
    assertCompleted(removeFuture);
  }

  @Test
  public void shouldContineOnSuppliedFutureCompletedExceptionally() {
    final ServiceContainer serviceContainer = serviceContainerRule.get();

    // given
    final AsyncStopService service = new AsyncStopService();
    service.future = new CompletableActorFuture<>();

    serviceContainer.createService(service1Name, service).install();
    actorSchedulerRule.workUntilDone();

    final ActorFuture<Void> removeFuture = serviceContainer.removeService(service1Name);
    actorSchedulerRule.workUntilDone();

    // when
    service.future.completeExceptionally(new RuntimeException());
    actorSchedulerRule.workUntilDone();

    // then
    assertCompleted(removeFuture);
  }

  @Test
  public void shouldWaitForAction() {
    final ServiceContainer serviceContainer = serviceContainerRule.get();

    // given
    final AsyncStopService service = new AsyncStopService();
    final Runnable mockAction = mock(Runnable.class);
    service.action = mockAction;

    serviceContainer.createService(service1Name, service).install();
    actorSchedulerRule.workUntilDone();

    // when
    final ActorFuture<Void> removeFuture = serviceContainer.removeService(service1Name);
    actorSchedulerRule.workUntilDone();

    // then
    assertNotCompleted(removeFuture);
  }

  @Test
  public void shouldContinueOnAction() {
    final ServiceContainer serviceContainer = serviceContainerRule.get();

    // given
    final AsyncStopService service = new AsyncStopService();
    final Runnable mockAction = mock(Runnable.class);
    service.action = mockAction;

    serviceContainer.createService(service1Name, service).install();
    actorSchedulerRule.workUntilDone();

    // when
    final ActorFuture<Void> removeFuture = serviceContainer.removeService(service1Name);
    actorSchedulerRule.workUntilDone();

    // when
    actorSchedulerRule.awaitBlockingTasksCompleted(1);
    verify(mockAction).run();
    actorSchedulerRule.workUntilDone();

    // then
    assertCompleted(removeFuture);
  }

  @Test
  public void shouldContinueOnExceptionFromAction() {
    final ServiceContainer serviceContainer = serviceContainerRule.get();

    // given
    final AsyncStopService service = new AsyncStopService();
    final Runnable mockAction = mock(Runnable.class);
    service.action = mockAction;

    doThrow(new RuntimeException()).when(mockAction).run();

    serviceContainer.createService(service1Name, service).install();
    actorSchedulerRule.workUntilDone();

    // when
    final ActorFuture<Void> removeFuture = serviceContainer.removeService(service1Name);
    actorSchedulerRule.workUntilDone();

    // when
    actorSchedulerRule.awaitBlockingTasksCompleted(1);
    verify(mockAction).run();
    actorSchedulerRule.workUntilDone();

    // then
    assertCompleted(removeFuture);
  }

  @Test
  public void shouldWaitOnConcurrentStop() {
    final ServiceContainer serviceContainer = serviceContainerRule.get();

    // given
    final AsyncStopService service = new AsyncStopService();
    service.future = new CompletableActorFuture<Void>();

    serviceContainer.createService(service1Name, service).dependency(service2Name).install();
    serviceContainer.createService(service2Name, mock(Service.class)).install();
    actorSchedulerRule.workUntilDone();

    // when
    final ActorFuture<Void> service1RemoveFuture = serviceContainer.removeService(service1Name);
    final ActorFuture<Void> service2RemoveFuture = serviceContainer.removeService(service2Name);
    actorSchedulerRule.workUntilDone();

    // then
    assertNotCompleted(service1RemoveFuture);
    assertNotCompleted(service2RemoveFuture);
  }

  @Test
  public void shouldContinueConcurrentStop() {
    final ServiceContainer serviceContainer = serviceContainerRule.get();

    // given
    final AsyncStopService service = new AsyncStopService();
    service.future = new CompletableActorFuture<Void>();

    serviceContainer.createService(service1Name, service).dependency(service2Name).install();
    serviceContainer.createService(service2Name, mock(Service.class)).install();
    actorSchedulerRule.workUntilDone();

    final ActorFuture<Void> service1RemoveFuture = serviceContainer.removeService(service1Name);
    final ActorFuture<Void> service2RemoveFuture = serviceContainer.removeService(service2Name);
    actorSchedulerRule.workUntilDone();

    // when
    service.future.complete(null);
    actorSchedulerRule.workUntilDone();

    // then
    assertCompleted(service1RemoveFuture);
    assertCompleted(service2RemoveFuture);
  }

  static class AsyncStopService implements Service<Object> {
    CompletableActorFuture<Void> future;
    Object value = new Object();
    Runnable action;

    @Override
    public void start(ServiceStartContext startContext) {}

    @Override
    public void stop(ServiceStopContext stopContext) {
      if (action != null) {
        stopContext.run(action);
      } else if (future != null) {
        stopContext.async(future);
      }
    }

    @Override
    public Object get() {
      return value;
    }
  }
}
