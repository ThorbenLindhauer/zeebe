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
package io.zeebe.map.types;

import io.zeebe.map.ValueHandler;
import org.agrona.BitUtil;
import org.agrona.UnsafeAccess;
import sun.misc.Unsafe;

@SuppressWarnings("restriction")
public class LongValueHandler implements ValueHandler {
  private static final Unsafe UNSAFE = UnsafeAccess.UNSAFE;

  public long theValue;

  @Override
  public void setValueLength(int length) {
    // ignore; length is static
  }

  @Override
  public int getValueLength() {
    return BitUtil.SIZE_OF_LONG;
  }

  @Override
  public void writeValue(long writeValueAddr) {
    UNSAFE.putLong(writeValueAddr, theValue);
  }

  @Override
  public void readValue(long valueAddr, int valueLength) {
    theValue = UNSAFE.getLong(valueAddr);
  }
}
