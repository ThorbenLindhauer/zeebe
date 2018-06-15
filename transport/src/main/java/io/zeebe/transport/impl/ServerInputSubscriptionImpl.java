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
package io.zeebe.transport.impl;

import io.zeebe.dispatcher.FragmentHandler;
import io.zeebe.dispatcher.Subscription;
import io.zeebe.transport.*;
import io.zeebe.util.sched.ActorCondition;

public class ServerInputSubscriptionImpl implements ServerInputSubscription {
  protected final Subscription subscription;
  protected final FragmentHandler fragmentHandler;

  public ServerInputSubscriptionImpl(
      ServerOutput output,
      Subscription subscription,
      RemoteAddressList addressList,
      ServerMessageHandler messageHandler,
      ServerRequestHandler requestHandler) {
    this.subscription = subscription;
    this.fragmentHandler =
        new ServerReceiveHandler(output, addressList, messageHandler, requestHandler, null);
  }

  @Override
  public int poll() {
    return poll(Integer.MAX_VALUE);
  }

  @Override
  public boolean hasAvailable() {
    return subscription.hasAvailable();
  }

  @Override
  public void registerConsumer(ActorCondition onDataAvailable) {
    subscription.registerConsumer(onDataAvailable);
  }

  @Override
  public void removeConsumer(ActorCondition onDataAvailable) {
    subscription.removeConsumer(onDataAvailable);
  }

  @Override
  public int poll(int maxCount) {
    return subscription.poll(fragmentHandler, maxCount);
  }
}
