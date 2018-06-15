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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.zeebe.servicecontainer.*;
import io.zeebe.util.sched.future.ActorFuture;
import io.zeebe.util.sched.testing.ControlledActorSchedulerRule;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.InOrder;

@SuppressWarnings({"unchecked"})
public class DependenciesTest {
  @Rule public ControlledActorSchedulerRule actorSchedulerRule = new ControlledActorSchedulerRule();

  ServiceContainer serviceContainer;
  ServiceName<Object> service1;
  ServiceName<Object> service2;
  ServiceName<Object> service3;

  @Before
  public void setup() {
    serviceContainer = new ServiceContainerImpl(actorSchedulerRule.get());
    serviceContainer.start();
    service1 = ServiceName.newServiceName("service1", Object.class);
    service2 = ServiceName.newServiceName("service2", Object.class);
    service3 = ServiceName.newServiceName("service3", Object.class);
  }

  @Test
  public void shouldInstallServiceWithoutDependencies() {
    final Service<Object> mockService = mock(Service.class);

    final ActorFuture<Object> serviceFuture =
        serviceContainer.createService(service1, mockService).install();

    actorSchedulerRule.workUntilDone();

    assertCompleted(serviceFuture);
    verify(mockService, times(1)).start(any(ServiceStartContext.class));
  }

  @Test
  public void shouldReinstallService() {
    final Service<Object> mockService = mock(Service.class);

    // given
    serviceContainer.createService(service1, mockService).install();
    actorSchedulerRule.workUntilDone();
    serviceContainer.removeService(service1);
    actorSchedulerRule.workUntilDone();

    // when then
    final ActorFuture<Object> installFuture =
        serviceContainer.createService(service1, mockService).install();
    actorSchedulerRule.workUntilDone();

    assertCompleted(installFuture);
  }

  @Test
  public void shouldNotInstallServiceWithMissingDependencies() {
    final Service<Object> mockService = mock(Service.class);

    final ActorFuture<Object> serviceFuture =
        serviceContainer.createService(service1, mockService).dependency(service2).install();

    actorSchedulerRule.workUntilDone();

    assertNotCompleted(serviceFuture);
    verify(mockService, times(0)).start(any(ServiceStartContext.class));
  }

  @Test
  public void shouldNotInstallServiceWithExistingName() {
    // given
    final Service<Object> mockService = mock(Service.class);
    serviceContainer.createService(service1, mockService).install();

    actorSchedulerRule.workUntilDone();

    // when
    final ActorFuture<Object> serviceFuture2 =
        serviceContainer.createService(service1, mockService).dependency(service2).install();

    actorSchedulerRule.workUntilDone();

    // then
    assertFailed(serviceFuture2);
  }

  @Test
  public void shouldInstallServiceWhenDependenciesAreAvailableAtStart() {
    final Service<Object> mockService1 = mock(Service.class);
    final Service<Object> mockService2 = mock(Service.class);

    serviceContainer.createService(service1, mockService1).install();

    actorSchedulerRule.workUntilDone();

    verify(mockService1, times(1)).start(any(ServiceStartContext.class));

    serviceContainer.createService(service2, mockService2).dependency(service1).install();

    actorSchedulerRule.workUntilDone();

    verify(mockService2, times(1)).start(any(ServiceStartContext.class));
  }

  @Test
  public void shouldInstallServiceWhenDependenciesAreResolved() {
    final Service<Object> mockService1 = mock(Service.class);
    final Service<Object> mockService2 = mock(Service.class);

    serviceContainer.createService(service1, mockService1).dependency(service2).install();

    actorSchedulerRule.workUntilDone();

    verify(mockService1, times(0)).start(any(ServiceStartContext.class));

    serviceContainer.createService(service2, mockService2).install();

    actorSchedulerRule.workUntilDone();

    final InOrder inOrder = inOrder(mockService1, mockService2);

    inOrder.verify(mockService2, times(1)).start(any(ServiceStartContext.class));
    inOrder.verify(mockService1, times(1)).start(any(ServiceStartContext.class));
  }

  @Test
  public void shouldRemoveServiceWithoutDependencies() {
    final Service<Object> mockService = mock(Service.class);

    // given
    serviceContainer.createService(service1, mockService).install();
    actorSchedulerRule.workUntilDone();

    // when
    serviceContainer.removeService(service1);
    actorSchedulerRule.workUntilDone();

    // then
    verify(mockService, times(1)).stop(any(ServiceStopContext.class));
  }

  @Test
  public void shouldStopDependentServicesFirst() {
    final Service<Object> mockService1 = mock(Service.class);
    final Service<Object> mockService2 = mock(Service.class);

    // given
    serviceContainer.createService(service1, mockService1).dependency(service2).install();
    serviceContainer.createService(service2, mockService2).install();

    actorSchedulerRule.workUntilDone();

    // when
    serviceContainer.removeService(service2);
    actorSchedulerRule.workUntilDone();

    // then
    final InOrder inOrder = inOrder(mockService1, mockService2);
    inOrder.verify(mockService1, times(1)).stop(any(ServiceStopContext.class));
    inOrder.verify(mockService2, times(1)).stop(any(ServiceStopContext.class));
  }

  @Test
  public void shouldStopDependentServicesFirstCase2() {
    final Service<Object> mockService1 = mock(Service.class);
    final Service<Object> mockService2 = mock(Service.class);
    final Service<Object> mockService3 = mock(Service.class);

    // given
    serviceContainer.createService(service1, mockService1).dependency(service2).install();
    serviceContainer.createService(service2, mockService2).dependency(service3).install();
    serviceContainer.createService(service3, mockService3).install();

    actorSchedulerRule.workUntilDone();

    // when
    serviceContainer.removeService(service3);
    actorSchedulerRule.workUntilDone();

    // then
    final InOrder inOrder = inOrder(mockService1, mockService2, mockService3);
    inOrder.verify(mockService1, times(1)).stop(any(ServiceStopContext.class));
    inOrder.verify(mockService2, times(1)).stop(any(ServiceStopContext.class));
    inOrder.verify(mockService3, times(1)).stop(any(ServiceStopContext.class));
  }

  /**
   * Same as test above but dependency direction is reversed with respect to installation order.
   * Having this test makes it a little more affirming that the service container does not close
   * services in arbitrary order.
   */
  @Test
  public void shouldStopDependentServicesFirstWithReverseInstallationOrder() {
    final Service<Object> mockService1 = mock(Service.class);
    final Service<Object> mockService2 = mock(Service.class);

    // given
    serviceContainer.createService(service1, mockService1).install();
    serviceContainer.createService(service2, mockService2).dependency(service1).install();

    actorSchedulerRule.workUntilDone();

    // when
    serviceContainer.removeService(service1);
    actorSchedulerRule.workUntilDone();

    // then
    final InOrder inOrder = inOrder(mockService1, mockService2);
    inOrder.verify(mockService2, times(1)).stop(any(ServiceStopContext.class));
    inOrder.verify(mockService1, times(1)).stop(any(ServiceStopContext.class));
  }

  @Test
  public void shouldStopAllServicesWhenStoppingContainer() {
    final Service<Object> mockService1 = mock(Service.class);
    final Service<Object> mockService2 = mock(Service.class);

    // given
    serviceContainer.createService(service1, mockService1).install();
    serviceContainer.createService(service2, mockService2).dependency(service1).install();
    actorSchedulerRule.workUntilDone();

    // when
    serviceContainer.closeAsync();
    actorSchedulerRule.workUntilDone();

    // then
    final InOrder inOrder = inOrder(mockService1, mockService2);
    inOrder.verify(mockService2, times(1)).stop(any(ServiceStopContext.class));
    inOrder.verify(mockService1, times(1)).stop(any(ServiceStopContext.class));
  }

  @Test
  public void shouldStopAllServicesWhenStoppingContainerWithReverseInstallationOrder() {
    final Service<Object> mockService1 = mock(Service.class);
    final Service<Object> mockService2 = mock(Service.class);

    // given
    serviceContainer.createService(service1, mockService1).dependency(service2).install();
    serviceContainer.createService(service2, mockService2).install();
    actorSchedulerRule.workUntilDone();

    // when
    serviceContainer.closeAsync();
    actorSchedulerRule.workUntilDone();

    // then
    final InOrder inOrder = inOrder(mockService1, mockService2);
    inOrder.verify(mockService1, times(1)).stop(any(ServiceStopContext.class));
    inOrder.verify(mockService2, times(1)).stop(any(ServiceStopContext.class));
  }
}
