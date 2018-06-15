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
package io.zeebe.raft.state;

import io.zeebe.logstreams.log.LogStream;
import io.zeebe.protocol.intent.RaftIntent;
import io.zeebe.raft.*;
import io.zeebe.raft.controller.AppendRaftEventController;
import io.zeebe.raft.protocol.AppendResponse;
import io.zeebe.raft.protocol.ConfigurationRequest;
import io.zeebe.transport.*;
import io.zeebe.util.sched.ActorCondition;
import io.zeebe.util.sched.ActorControl;
import java.util.Arrays;
import java.util.List;

public class LeaderState extends AbstractRaftState {
  private final AppendRaftEventController configurationChangeController;

  private ActorCondition appendCondition;

  private boolean initialEventCommitted = false;
  private long initialEventPosition = -1;

  public LeaderState(Raft raft, ActorControl raftActor) {
    super(raft, raftActor);
    this.configurationChangeController = new AppendRaftEventController(raft, raftActor);
  }

  @Override
  protected void onEnterState() {
    super.onEnterState();

    if (raftMembers.getMemberSize() == 0) {
      createOnAppendContition();
    }
  }

  @Override
  protected void onLeaveState() {
    configurationChangeController.close();
    removeOnAppendCondition();
    super.onLeaveState();
  }

  private void createOnAppendContition() {
    if (appendCondition == null) {
      appendCondition = raftActor.onCondition("append-condition", this::commitPositionOnSingleNode);
      logStream.registerOnAppendCondition(appendCondition);
    }
  }

  private void removeOnAppendCondition() {
    if (appendCondition != null) {
      appendCondition.cancel();
      logStream.removeOnAppendCondition(appendCondition);
    }

    appendCondition = null;
  }

  @Override
  public RaftState getState() {
    return RaftState.LEADER;
  }

  @Override
  public void configurationRequest(
      final ServerOutput serverOutput,
      final RemoteAddress remoteAddress,
      final long requestId,
      final ConfigurationRequest configurationRequest) {
    if (!raft.mayStepDown(configurationRequest)) {
      if (initialEventCommitted && !configurationChangeController.isHandlingConfigurationChange()) {
        final SocketAddress member = configurationRequest.getSocketAddress();
        if (configurationRequest.isJoinRequest()) {
          join(serverOutput, remoteAddress, requestId, member);
        } else {
          leave(serverOutput, remoteAddress, requestId, member);
        }
      } else {
        rejectConfigurationRequest(serverOutput, remoteAddress, requestId);
      }
    }
  }

  private void join(
      final ServerOutput serverOutput,
      final RemoteAddress remoteAddress,
      final long requestId,
      SocketAddress newMember) {
    if (raftMembers.hasMember(newMember)) {
      acceptConfigurationRequest(serverOutput, remoteAddress, requestId);
    } else {
      // create new socket address object as it is stored in a map
      if (raft.joinMember(new SocketAddress(newMember))) {
        configurationChangeController.prepare(
            serverOutput, remoteAddress, requestId, RaftIntent.MEMBER_ADDED);
        configurationChangeController.appendEvent();

        // remove condition
        removeOnAppendCondition();
      }
    }
  }

  private void leave(
      final ServerOutput serverOutput,
      final RemoteAddress remoteAddress,
      final long requestId,
      SocketAddress member) {
    if (!raftMembers.hasMember(member)) {
      acceptConfigurationRequest(serverOutput, remoteAddress, requestId);
    } else {
      configurationChangeController.prepare(
          serverOutput, remoteAddress, requestId, RaftIntent.MEMBER_REMOVED);

      raftActor.runOnCompletion(
          raft.memberLeaves(member),
          (canLeave, t) -> {
            if (canLeave) {
              // re-add append condition
              if (raftMembers.getMemberSize() == 0) {
                createOnAppendContition();
              }

              configurationChangeController.appendEvent();
            } else {
              configurationChangeController.reset();
            }
          });
    }
  }

  @Override
  public void appendResponse(final AppendResponse appendResponse) {
    if (!raft.mayStepDown(appendResponse)) {
      final boolean succeeded = appendResponse.isSucceeded();
      final long eventPosition = appendResponse.getPreviousEventPosition();

      final RaftMember member =
          raftMembers.getMemberBySocketAddress(appendResponse.getSocketAddress());

      if (member != null) {
        if (succeeded) {
          member.onFollowerHasAcknowledgedPosition(eventPosition);
          commit();
        } else {
          member.onFollowerHasFailedPosition(eventPosition);
        }
      }
    }
  }

  private void commit() {
    final List<RaftMember> memberList = raftMembers.getMemberList();

    final int memberSize = memberList.size();
    final long[] positions = new long[memberSize + 1];

    for (int i = 0; i < memberSize; i++) {
      positions[i] = memberList.get(i).getMatchPosition();
    }

    // TODO(menski): `raft.getLogStream().getCurrentAppenderPosition()` is wrong as the current
    // appender
    // position is the next position which is written. This means in a single node cluster the log
    // already committed an event which will be written in the future. `- 1` is a hotfix for this.
    // see https://github.com/zeebe-io/zeebe/issues/501
    positions[memberSize] = logStream.getLogStorageAppender().getCurrentAppenderPosition() - 1;

    Arrays.sort(positions);

    final long commitPosition = positions[memberSize + 1 - raft.requiredQuorum()];

    final LogStream logStream = raft.getLogStream();

    if (initialEventPosition >= 0
        && commitPosition >= initialEventPosition
        && logStream.getCommitPosition() < commitPosition) {
      logStream.setCommitPosition(commitPosition);
    }
  }

  private void commitPositionOnSingleNode() {
    final long commitPosition = logStream.getLogStorageAppender().getCurrentAppenderPosition() - 1;

    if (initialEventPosition >= 0
        && commitPosition >= initialEventPosition
        && logStream.getCommitPosition() < commitPosition) {
      logStream.setCommitPosition(commitPosition);
    }
  }

  public void setInitialEventPosition(long position) {
    this.initialEventPosition = position;
  }

  public void setInitialEventCommitted() {
    this.initialEventCommitted = true;
  }
}
