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
package io.zeebe.raft.protocol;

import static io.zeebe.raft.AppendResponseEncoder.*;

import io.zeebe.logstreams.log.LogStream;
import io.zeebe.raft.*;
import io.zeebe.transport.SocketAddress;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

public class AppendResponse extends AbstractRaftMessage
    implements HasSocketAddress, HasTerm, HasPartition {

  protected final AppendResponseDecoder bodyDecoder = new AppendResponseDecoder();
  protected final AppendResponseEncoder bodyEncoder = new AppendResponseEncoder();

  // read + write
  protected int partitionId;
  protected int term;
  protected boolean succeeded;
  protected long previousEventPosition;

  // read
  protected final DirectBuffer readHost = new UnsafeBuffer(0, 0);
  protected final SocketAddress readSocketAddress = new SocketAddress();

  // write
  private SocketAddress writeSocketAddress;

  public AppendResponse() {
    reset();
  }

  public AppendResponse reset() {
    partitionId = partitionIdNullValue();
    term = termNullValue();
    succeeded = false;
    previousEventPosition = previousEventPositionNullValue();
    readHost.wrap(0, 0);
    readSocketAddress.reset();

    writeSocketAddress = null;

    return this;
  }

  @Override
  protected int getVersion() {
    return bodyDecoder.sbeSchemaVersion();
  }

  @Override
  protected int getSchemaId() {
    return bodyDecoder.sbeSchemaId();
  }

  @Override
  protected int getTemplateId() {
    return bodyDecoder.sbeTemplateId();
  }

  @Override
  public int getPartitionId() {
    return partitionId;
  }

  @Override
  public int getTerm() {
    return term;
  }

  public boolean isSucceeded() {
    return succeeded;
  }

  public AppendResponse setSucceeded(final boolean succeeded) {
    this.succeeded = succeeded;
    return this;
  }

  public long getPreviousEventPosition() {
    return previousEventPosition;
  }

  public AppendResponse setPreviousEventPosition(final long previousEventPosition) {
    this.previousEventPosition = previousEventPosition;
    return this;
  }

  @Override
  public SocketAddress getSocketAddress() {
    return readSocketAddress;
  }

  public AppendResponse setRaft(final Raft raft) {
    final LogStream logStream = raft.getLogStream();

    partitionId = logStream.getPartitionId();
    term = raft.getTerm();

    writeSocketAddress = raft.getSocketAddress();

    return this;
  }

  @Override
  public int getLength() {
    int length = headerEncoder.encodedLength() + bodyEncoder.sbeBlockLength() + hostHeaderLength();

    if (writeSocketAddress != null) {
      length += writeSocketAddress.hostLength();
    }

    return length;
  }

  @Override
  public void wrap(final DirectBuffer buffer, int offset, final int length) {
    reset();

    final int frameEnd = offset + length;

    headerDecoder.wrap(buffer, offset);
    offset += headerDecoder.encodedLength();

    bodyDecoder.wrap(buffer, offset, headerDecoder.blockLength(), headerDecoder.version());

    partitionId = bodyDecoder.partitionId();
    term = bodyDecoder.term();
    succeeded = bodyDecoder.succeeded() == BooleanType.TRUE;
    previousEventPosition = bodyDecoder.previousEventPosition();
    readSocketAddress.port(bodyDecoder.port());

    offset += bodyDecoder.sbeBlockLength();

    offset += wrapVarData(buffer, offset, readHost, hostHeaderLength(), bodyDecoder.hostLength());
    bodyDecoder.limit(offset);

    readSocketAddress.host(readHost, 0, readHost.capacity());

    assert bodyDecoder.limit() == frameEnd
        : "Decoder read only to position "
            + bodyDecoder.limit()
            + " but expected "
            + frameEnd
            + " as final position";
  }

  @Override
  public void write(final MutableDirectBuffer buffer, int offset) {
    headerEncoder
        .wrap(buffer, offset)
        .blockLength(bodyEncoder.sbeBlockLength())
        .templateId(bodyEncoder.sbeTemplateId())
        .schemaId(bodyEncoder.sbeSchemaId())
        .version(bodyEncoder.sbeSchemaVersion());

    offset += headerEncoder.encodedLength();

    bodyEncoder
        .wrap(buffer, offset)
        .partitionId(partitionId)
        .term(term)
        .succeeded(succeeded ? BooleanType.TRUE : BooleanType.FALSE)
        .previousEventPosition(previousEventPosition);

    if (writeSocketAddress != null) {
      bodyEncoder
          .port(writeSocketAddress.port())
          .putHost(writeSocketAddress.getHostBuffer(), 0, writeSocketAddress.hostLength());
    }
  }
}
