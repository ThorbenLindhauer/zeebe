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
package io.zeebe.util.sched;

import io.zeebe.util.sched.ActorTask.TaskSchedulingState;
import io.zeebe.util.sched.future.ActorFuture;
import io.zeebe.util.sched.future.CompletableActorFuture;
import java.util.concurrent.Callable;

@SuppressWarnings({"unchecked", "rawtypes"})
public class ActorJob {
  TaskSchedulingState schedulingState;

  Actor actor;
  ActorTask task;

  ActorJob next;

  private Callable<?> callable;
  private Runnable runnable;
  private Object invocationResult;
  private boolean isAutoCompleting;
  private boolean isDoneCalled;

  private ActorFuture resultFuture;

  ActorThread actorThread;

  private ActorSubscription subscription;

  public void onJobAddedToTask(ActorTask task) {
    this.actor = task.actor;
    this.task = task;
    this.schedulingState = TaskSchedulingState.QUEUED;
  }

  void execute(ActorThread runner) {
    runner.getMetrics().incrementJobCount();

    this.actorThread = runner;
    try {
      invoke(runner);

      if (resultFuture != null) {
        resultFuture.complete(invocationResult);
        resultFuture = null;
      }

    } catch (Exception e) {
      task.onFailure(e);
    } finally {
      this.actorThread = null;

      // in any case, success or exception, decide if the job should be resubmitted
      if (isTriggeredBySubscription() || (isAutoCompleting && runnable == null) || isDoneCalled) {
        schedulingState = TaskSchedulingState.TERMINATED;
      } else {
        schedulingState = TaskSchedulingState.QUEUED;
      }
    }
  }

  private void invoke(ActorThread runner) throws Exception {
    if (callable != null) {
      invocationResult = callable.call();
    } else {
      if (!isTriggeredBySubscription()) {
        // TODO: preempt after fixed number of iterations
        while (runnable != null && !task.shouldYield && !isDoneCalled) {
          final Runnable r = this.runnable;

          if (isAutoCompleting) {
            this.runnable = null;
          }

          r.run();
        }
      } else {
        runnable.run();
      }
    }
  }

  public void appendChild(ActorJob spawnedTask) {
    spawnedTask.next = this.next;
    this.next = spawnedTask;
  }

  /**
   * Append a child task to this task. The new child task is appended to the list of tasks spawned
   * by this task such that it is executed last.
   */
  public void append(ActorJob newJob) {
    ActorJob job = this;
    assert job != newJob : "Job cannot be twice in a job queue";

    while (job.next != null) {
      job = job.next;
      assert job != newJob : "Job cannot be twice in a job queue";
    }

    job.appendChild(newJob);
  }

  /**
   * remove this task from the chain of tasks to execute
   *
   * @return the next task
   */
  protected ActorJob getNext() {
    final ActorJob next = this.next;
    this.next = null;
    return next;
  }

  public void setRunnable(Runnable runnable) {
    this.runnable = runnable;
  }

  public ActorFuture setCallable(Callable<?> callable) {
    this.callable = callable;
    setResultFuture(new CompletableActorFuture<>());
    return resultFuture;
  }

  public ActorFuture getResultFuture() {
    return resultFuture;
  }

  /** used to recycle the job object */
  void reset() {
    schedulingState = TaskSchedulingState.NOT_SCHEDULED;

    next = null;
    actor = null;

    task = null;
    actorThread = null;

    callable = null;
    runnable = null;
    invocationResult = null;
    isAutoCompleting = true;
    isDoneCalled = false;

    resultFuture = null;
    subscription = null;
  }

  public void markDone() {
    if (isAutoCompleting) {
      throw new UnsupportedOperationException(
          "Incorrect use of actor.done(). Can only be called in methods submitted using actor.runUntilDone(Runnable r)");
    }

    isDoneCalled = true;
  }

  public void setAutoCompleting(boolean isAutoCompleting) {
    this.isAutoCompleting = isAutoCompleting;
  }

  @Override
  public String toString() {
    String toString = "";

    if (runnable != null) {
      toString += runnable.getClass().getName();
    }
    if (callable != null) {
      toString += callable.getClass().getName();
    }

    toString += " " + schedulingState;

    return toString;
  }

  public boolean isTriggeredBySubscription() {
    return subscription != null;
  }

  public void setSubscription(ActorSubscription subscription) {
    this.subscription = subscription;
    task.addSubscription(subscription);
  }

  public ActorSubscription getSubscription() {
    return subscription;
  }

  public ActorTask getTask() {
    return task;
  }

  public Actor getActor() {
    return actor;
  }

  public ActorThread getActorThread() {
    return actorThread;
  }

  public void setResultFuture(ActorFuture resultFuture) {
    assert !resultFuture.isDone();
    this.resultFuture = resultFuture;
  }

  public void failFuture(String reason) {
    failFuture(new RuntimeException(reason));
  }

  public void failFuture(Exception cause) {
    if (this.resultFuture != null) {
      resultFuture.completeExceptionally(cause);
    }
  }
}
