/*
 * Copyright 2018 Google LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.tools.jib.builder;

import com.google.cloud.tools.jib.event.DefaultEventEmitter;
import com.google.cloud.tools.jib.event.EventEmitter;
import com.google.cloud.tools.jib.event.EventHandlers;
import com.google.cloud.tools.jib.event.JibEventType;
import com.google.cloud.tools.jib.event.events.TimerEvent;
import com.google.cloud.tools.jib.event.events.TimerEvent.State;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import javax.annotation.Nullable;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

/** Tests for {@link TimerEventEmitter}. */
@RunWith(MockitoJUnitRunner.class)
public class TimerEventEmitterTest {

  private final Deque<TimerEvent> timerEventQueue = new ArrayDeque<>();

  @Mock private Clock mockClock;

  @Test
  public void testLogging() {
    EventEmitter eventEmitter =
        new DefaultEventEmitter(new EventHandlers().add(JibEventType.TIMING, timerEventQueue::add));

    Mockito.when(mockClock.instant()).thenReturn(Instant.EPOCH);
    try (TimerEventEmitter parentTimerEventEmitter =
        new TimerEventEmitter(eventEmitter, "description", mockClock, null)) {
      Mockito.when(mockClock.instant()).thenReturn(Instant.EPOCH.plusMillis(1));
      parentTimerEventEmitter.lap();
      Mockito.when(mockClock.instant()).thenReturn(Instant.EPOCH.plusMillis(1).plusNanos(1));
      try (TimerEventEmitter ignored = parentTimerEventEmitter.subTimer("child description")) {
        Mockito.when(mockClock.instant()).thenReturn(Instant.EPOCH.plusMillis(2));
        // Laps on close.
      }
    }

    TimerEvent timerEvent = getNextTimerEvent();
    verifyNoParent(timerEvent);
    verifyStartState(timerEvent);
    verifyDescription(timerEvent, "description");

    TimerEvent.Timer parentTimer = timerEvent.getTimer();

    timerEvent = getNextTimerEvent();
    verifyNoParent(timerEvent);
    verifyStateFirstLap(timerEvent, State.LAP);
    verifyDescription(timerEvent, "description");

    timerEvent = getNextTimerEvent();
    verifyParent(timerEvent, parentTimer);
    verifyStartState(timerEvent);
    verifyDescription(timerEvent, "child description");

    timerEvent = getNextTimerEvent();
    verifyParent(timerEvent, parentTimer);
    verifyStateFirstLap(timerEvent, State.FINISHED);
    verifyDescription(timerEvent, "child description");

    timerEvent = getNextTimerEvent();
    verifyNoParent(timerEvent);
    verifyStateNotFirstLap(timerEvent, State.FINISHED);
    verifyDescription(timerEvent, "description");

    Assert.assertTrue(timerEventQueue.isEmpty());
  }

  /**
   * Verifies the next {@link TimerEvent} on the {@link #timerEventQueue}.
   *
   * @param expectedParentTimer the expected parent {@link TimerEvent.Timer}, or {@code null} if
   *     none expected
   * @param expectedState the expected {@link TimerEvent.State}
   * @param expectedDescription the expected description
   * @param hasLapped {@code true} if the timer has already lapped; {@code false} if it has not
   * @return the verified {@link TimerEvent}
   */
  private TimerEvent.Timer verifyNextTimerEvent(
      @Nullable TimerEvent.Timer expectedParentTimer,
      State expectedState,
      String expectedDescription,
      boolean hasLapped) {
    TimerEvent timerEvent = timerEventQueue.poll();
    Assert.assertNotNull(timerEvent);

    if (expectedParentTimer == null) {
      Assert.assertFalse(timerEvent.getTimer().getParent().isPresent());
    } else {
      Assert.assertTrue(timerEvent.getTimer().getParent().isPresent());
      Assert.assertSame(expectedParentTimer, timerEvent.getTimer().getParent().get());
    }
    Assert.assertEquals(expectedState, timerEvent.getState());
    if (expectedState == State.START) {
      Assert.assertEquals(Duration.ZERO, timerEvent.getDuration());
      Assert.assertEquals(Duration.ZERO, timerEvent.getElapsed());
    } else {
      Assert.assertTrue(timerEvent.getDuration().compareTo(Duration.ZERO) > 0);
      if (hasLapped) {
        Assert.assertTrue(timerEvent.getElapsed().compareTo(timerEvent.getDuration()) > 0);
      } else {
        Assert.assertEquals(0, timerEvent.getElapsed().compareTo(timerEvent.getDuration()));
      }
    }
    Assert.assertEquals(expectedDescription, timerEvent.getDescription());

    return timerEvent.getTimer();
  }

  /**
   * Verifies that the {@code timerEvent}'s timer has no parent.
   *
   * @param timerEvent the {@link TimerEvent} to verify
   */
  private void verifyNoParent(TimerEvent timerEvent) {
    Assert.assertFalse(timerEvent.getTimer().getParent().isPresent());
  }

  /**
   * Verifies that the {@code timerEvent}'s timer has parent {@code expectedParentTimer}.
   *
   * @param timerEvent the {@link TimerEvent} to verify
   * @param expectedParentTimer the expected parent timer
   */
  private void verifyParent(TimerEvent timerEvent, TimerEvent.Timer expectedParentTimer) {
    Assert.assertTrue(timerEvent.getTimer().getParent().isPresent());
    Assert.assertSame(expectedParentTimer, timerEvent.getTimer().getParent().get());
  }

  /**
   * Verifies that the {@code timerEvent}'s state is {@link State#START}.
   *
   * @param timerEvent the {@link TimerEvent} to verify
   */
  private void verifyStartState(TimerEvent timerEvent) {
    Assert.assertEquals(State.START, timerEvent.getState());
    Assert.assertEquals(Duration.ZERO, timerEvent.getDuration());
    Assert.assertEquals(Duration.ZERO, timerEvent.getElapsed());
  }

  /**
   * Verifies that the {@code timerEvent}'s state is {@code expectedState} and that this is the
   * first lap for the timer.
   *
   * @param timerEvent the {@link TimerEvent} to verify
   * @param expectedState the expected {@link State}
   */
  private void verifyStateFirstLap(TimerEvent timerEvent, State expectedState) {
    Assert.assertEquals(expectedState, timerEvent.getState());
    Assert.assertTrue(timerEvent.getDuration().compareTo(Duration.ZERO) > 0);
    Assert.assertEquals(0, timerEvent.getElapsed().compareTo(timerEvent.getDuration()));
  }

  /**
   * Verifies that the {@code timerEvent}'s state is {@code expectedState} and that this is not the
   * first lap for the timer.
   *
   * @param timerEvent the {@link TimerEvent} to verify
   * @param expectedState the expected {@link State}
   */
  private void verifyStateNotFirstLap(TimerEvent timerEvent, State expectedState) {
    Assert.assertEquals(expectedState, timerEvent.getState());
    Assert.assertTrue(timerEvent.getDuration().compareTo(Duration.ZERO) > 0);
    Assert.assertTrue(timerEvent.getElapsed().compareTo(timerEvent.getDuration()) > 0);
  }

  /**
   * Verifies that the {@code timerEvent}'s description is {@code expectedDescription}.
   *
   * @param timerEvent the {@link TimerEvent} to verify
   * @param expectedDescription the expected description
   */
  private void verifyDescription(TimerEvent timerEvent, String expectedDescription) {
    Assert.assertEquals(expectedDescription, timerEvent.getDescription());
  }

  /**
   * Gets the next {@link TimerEvent} on the {@link #timerEventQueue}.
   *
   * @return the next {@link TimerEvent}
   */
  private TimerEvent getNextTimerEvent() {
    TimerEvent timerEvent = timerEventQueue.poll();
    Assert.assertNotNull(timerEvent);
    return timerEvent;
  }
}