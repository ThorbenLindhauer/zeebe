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
package io.zeebe.transport.impl.sender;

import io.zeebe.transport.*;
import io.zeebe.transport.impl.ClientResponseImpl;
import io.zeebe.transport.impl.IncomingResponse;
import io.zeebe.util.sched.future.ActorFuture;
import io.zeebe.util.sched.future.CompletableActorFuture;
import java.time.Duration;
import java.util.Deque;
import java.util.LinkedList;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.slf4j.Logger;

public class OutgoingRequest {
  private static final Logger LOG = Loggers.TRANSPORT_LOGGER;

  private final TransportHeaderWriter headerWriter = new TransportHeaderWriter();

  private final ActorFuture<ClientResponse> responseFuture = new CompletableActorFuture<>();

  private final Supplier<RemoteAddress> remoteAddressSupplier;

  private final Predicate<DirectBuffer> retryPredicate;

  private final Duration timeout;

  private final Deque<RemoteAddress> remotesTried = new LinkedList<>();

  private final MutableDirectBuffer requestBuffer;

  private long timerId = -1;

  private long lastRequestId = -1;

  private boolean isTimedout;

  public OutgoingRequest(
      Supplier<RemoteAddress> remoteAddressSupplier,
      Predicate<DirectBuffer> retryPredicate,
      UnsafeBuffer requestBuffer,
      Duration timeout) {
    this.remoteAddressSupplier = remoteAddressSupplier;
    this.retryPredicate = retryPredicate;
    this.requestBuffer = requestBuffer;
    this.timeout = timeout;
  }

  public ActorFuture<ClientResponse> getResponseFuture() {
    return responseFuture;
  }

  public RemoteAddress getNextRemoteAddress() {
    return remoteAddressSupplier.get();
  }

  public boolean tryComplete(IncomingResponse incomingResponse) {
    final DirectBuffer data = incomingResponse.getResponseBuffer();

    if (responseFuture.isDone()) {
      return true;
    } else if (!retryPredicate.test(data)) {
      try {
        final RemoteAddress remoteAddress = remotesTried.peekFirst();
        final ClientResponseImpl response = new ClientResponseImpl(incomingResponse, remoteAddress);
        responseFuture.complete(response);
      } catch (Exception e) {
        LOG.debug("Could not complete request future", e);
      }

      return true;
    } else {
      // should retry
      return false;
    }
  }

  public void fail(Throwable throwable) {
    try {
      responseFuture.completeExceptionally(throwable);
    } catch (Exception e) {
      LOG.debug("Could not complete request future exceptionally", e);
    }
  }

  public DirectBuffer getRequestBuffer() {
    return requestBuffer;
  }

  public RemoteAddress getCurrentRemoteAddress() {
    return remotesTried.peekFirst();
  }

  public Duration getTimeout() {
    return timeout;
  }

  public void markRemoteAddress(RemoteAddress remoteAddress) {
    if (!remoteAddress.equals(remotesTried.peekFirst())) {
      remotesTried.push(remoteAddress);
    }
  }

  public TransportHeaderWriter getHeaderWriter() {
    return headerWriter;
  }

  public void setTimerId(long timerId) {
    this.timerId = timerId;
  }

  public boolean hasTimeoutScheduled() {
    return timerId != -1;
  }

  public long getTimerId() {
    return timerId;
  }

  public long getLastRequestId() {
    return lastRequestId;
  }

  public void setLastRequestId(long requestId) {
    this.lastRequestId = requestId;
  }

  public void timeout() {
    isTimedout = true;
    fail(new RequestTimeoutException("Request timed out after " + timeout));
  }

  public boolean isTimedout() {
    return isTimedout;
  }
}
