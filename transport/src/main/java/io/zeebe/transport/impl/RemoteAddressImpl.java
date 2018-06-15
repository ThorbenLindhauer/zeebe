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

import io.zeebe.transport.RemoteAddress;
import io.zeebe.transport.SocketAddress;

public class RemoteAddressImpl implements RemoteAddress {
  public static final int STATE_ACTIVE = 1 << 0;
  public static final int STATE_INACTIVE = 1 << 1;
  public static final int STATE_RETIRED = 1 << 2;

  private final int streamId;
  private final SocketAddress addr;
  private volatile int state;

  public RemoteAddressImpl(int streamId, SocketAddress addr) {
    this.streamId = streamId;
    this.addr = addr;
    this.state = STATE_ACTIVE;
  }

  public int getStreamId() {
    return streamId;
  }

  public SocketAddress getAddress() {
    return addr;
  }

  public void deactivate() {
    this.state = STATE_INACTIVE;
  }

  public void retire() {
    this.state = STATE_RETIRED;
  }

  public void activate() {
    this.state = STATE_ACTIVE;
  }

  public boolean isInAnyState(int mask) {
    return (this.state & mask) != 0;
  }

  public boolean isActive() {
    return isInAnyState(STATE_ACTIVE);
  }

  @Override
  public String toString() {
    return "RemoteAddress{" + "streamId=" + streamId + ", addr=" + addr + '}';
  }
}
