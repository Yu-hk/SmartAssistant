export interface InactivityWatchdog {
  touch: () => void;
  cancel: () => void;
}

interface WatchdogClock {
  schedule: (callback: () => void, delayMs: number) => number;
  cancel: (timerId: number) => void;
}

const browserClock: WatchdogClock = {
  schedule: (callback, delayMs) => window.setTimeout(callback, delayMs),
  cancel: timerId => window.clearTimeout(timerId),
};

/**
 * Creates a resettable one-shot inactivity timer. Calling touch() postpones the
 * timeout, while cancel() permanently disarms this watchdog instance.
 */
export function createInactivityWatchdog(
  timeoutMs: number,
  onTimeout: () => void,
  clock: WatchdogClock = browserClock,
): InactivityWatchdog {
  let timerId: number | null = null;
  let stopped = false;

  const cancelTimer = () => {
    if (timerId != null) {
      clock.cancel(timerId);
      timerId = null;
    }
  };

  const touch = () => {
    if (stopped) return;
    cancelTimer();
    timerId = clock.schedule(() => {
      timerId = null;
      if (stopped) return;
      stopped = true;
      onTimeout();
    }, timeoutMs);
  };

  const cancel = () => {
    stopped = true;
    cancelTimer();
  };

  touch();
  return { touch, cancel };
}
