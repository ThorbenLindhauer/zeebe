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
import org.agrona.DirectBuffer;

public class ClientInputMessageSubscriptionImpl implements ClientInputMessageSubscription {
  protected final Subscription subscription;
  protected final FragmentHandler messageHandler;

  public ClientInputMessageSubscriptionImpl(
      Subscription subscription,
      ClientMessageHandler messageHandler,
      ClientOutput output,
      RemoteAddressList remoteAddresses) {
    this.subscription = subscription;
    this.messageHandler =
        new FragmentHandler() {
          @Override
          public int onFragment(
              DirectBuffer buffer, int offset, int length, int streamId, boolean isMarkedFailed) {
            final RemoteAddress remoteAddress = remoteAddresses.getByStreamId(streamId);
            final boolean success =
                messageHandler.onMessage(output, remoteAddress, buffer, offset, length);

            return success ? CONSUME_FRAGMENT_RESULT : POSTPONE_FRAGMENT_RESULT;
          }
        };
  }

  @Override
  public int poll() {
    return subscription.peekAndConsume(messageHandler, Integer.MAX_VALUE);
  }

  @Override
  public boolean hasAvailable() {
    return subscription.hasAvailable();
  }

  @Override
  public void registerConsumer(ActorCondition listener) {
    subscription.registerConsumer(listener);
  }

  @Override
  public void removeConsumer(ActorCondition listener) {
    subscription.removeConsumer(listener);
  }
}
