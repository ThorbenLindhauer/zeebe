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
package io.zeebe.test;

import static io.zeebe.test.EmbeddedBrokerRule.DEFAULT_CONFIG_SUPPLIER;
import static io.zeebe.test.TopicEventRecorder.jobKey;
import static io.zeebe.test.TopicEventRecorder.wfInstanceKey;
import static org.assertj.core.api.Assertions.fail;

import io.zeebe.client.ZeebeClient;
import io.zeebe.client.api.events.JobEvent;
import io.zeebe.client.api.events.JobState;
import io.zeebe.client.api.events.WorkflowInstanceEvent;
import io.zeebe.client.api.events.WorkflowInstanceState;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Properties;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.junit.rules.ExternalResource;

public class ZeebeTestRule extends ExternalResource {
  private EmbeddedBrokerRule brokerRule;
  private ClientRule clientRule;
  private TopicEventRecorder topicEventRecorder;

  public ZeebeTestRule() {
    this(DEFAULT_CONFIG_SUPPLIER, Properties::new);
  }

  public ZeebeTestRule(
      final Supplier<InputStream> configSupplier, final Supplier<Properties> propertiesProvider) {
    brokerRule = new EmbeddedBrokerRule(configSupplier);
    clientRule = new ClientRule(propertiesProvider, true);

    topicEventRecorder = new TopicEventRecorder(clientRule, clientRule.getDefaultTopic());
  }

  public ZeebeClient getClient() {
    return clientRule.getClient();
  }

  @Override
  protected void before() {
    brokerRule.before();
    clientRule.before();
    topicEventRecorder.before();
  }

  @Override
  protected void after() {
    topicEventRecorder.after();
    clientRule.after();
    brokerRule.after();
  }

  public void waitUntilWorkflowInstanceCompleted(final long key) {
    waitUntil(
        () -> !topicEventRecorder.getWorkflowInstanceEvents(wfInstanceKey(key)).isEmpty(),
        "no workflow instance found with key " + key);

    waitUntil(
        () -> {
          final WorkflowInstanceEvent event =
              topicEventRecorder.getLastWorkflowInstanceEvent(wfInstanceKey(key));
          return event.getState().equals(WorkflowInstanceState.COMPLETED);
        },
        "workflow instance is not completed");
  }

  public void waitUntilJobCompleted(final long key) {
    waitUntil(
        () -> !topicEventRecorder.getJobEvents(jobKey(key)).isEmpty(),
        "no job found with key " + key);

    waitUntil(
        () -> {
          final JobEvent event = topicEventRecorder.getLastJobEvent(jobKey(key));
          return event.getState().equals(JobState.COMPLETED);
        },
        "job is not completed");
  }

  public void printWorkflowInstanceEvents(final long key) {
    topicEventRecorder
        .getWorkflowInstanceEvents(wfInstanceKey(key))
        .forEach(
            event -> {
              System.out.println("> " + event);
            });
  }

  private void waitUntil(final BooleanSupplier condition, final String failureMessage) {
    final long timeout = Instant.now().plus(Duration.ofSeconds(5)).toEpochMilli();

    while (!condition.getAsBoolean() && System.currentTimeMillis() < timeout) {
      sleep(100);
    }

    if (!condition.getAsBoolean()) {
      fail(failureMessage);
    }
  }

  private void sleep(final int millis) {
    try {
      Thread.sleep(millis);
    } catch (final InterruptedException e) {
      // ignore
    }
  }

  public String getDefaultTopic() {
    return clientRule.getDefaultTopic();
  }

  public int getDefaultPartition() {
    return clientRule.getDefaultPartition();
  }
}
