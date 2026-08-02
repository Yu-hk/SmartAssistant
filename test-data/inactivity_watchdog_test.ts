import assert from 'node:assert/strict';
import { createInactivityWatchdog } from '../frontend/src/hooks/inactivityWatchdog.ts';

type Callback = () => void;

let nextId = 1;
const scheduled = new Map<number, Callback>();
const delays = new Map<number, number>();
const clock = {
  schedule(callback: Callback, delayMs: number): number {
    const id = nextId++;
    scheduled.set(id, callback);
    delays.set(id, delayMs);
    return id;
  },
  cancel(timerId: number): void {
    scheduled.delete(timerId);
    delays.delete(timerId);
  },
};

let firstTimedOut = 0;
let secondTimedOut = 0;
const first = createInactivityWatchdog(300_000, () => firstTimedOut++, clock);
const second = createInactivityWatchdog(300_000, () => secondTimedOut++, clock);

assert.equal(scheduled.size, 2, 'each conversation must own an independent timer');
assert.deepEqual([...delays.values()], [300_000, 300_000]);

const firstOriginalTimer = [...scheduled.keys()][0];
first.touch();
assert.equal(scheduled.has(firstOriginalTimer), false, 'activity must cancel the old timer');
assert.equal(scheduled.size, 2, 'activity in one conversation must not affect the other');

const secondTimer = [...scheduled.keys()][0];
scheduled.get(secondTimer)?.();
scheduled.delete(secondTimer);
assert.equal(secondTimedOut, 1);
assert.equal(firstTimedOut, 0, 'the other active conversation must remain open');

const firstCurrentTimer = [...scheduled.keys()][0];
scheduled.get(firstCurrentTimer)?.();
scheduled.delete(firstCurrentTimer);
assert.equal(firstTimedOut, 1);

first.cancel();
second.cancel();
assert.equal(scheduled.size, 0);

console.log('PASS inactivity watchdog: independent timers, reset, timeout, and cancel');
