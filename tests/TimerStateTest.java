import com.kevtrinh.rabbitphone.TimerState;

public final class TimerStateTest {
    private static final String A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String C = "cccccccccccccccccccccccccccccccc";
    private static int groups;
    private static TimerState.Time time(long elapsed, long wall, int boot) {
        return new TimerState.Time(elapsed, wall, boot);
    }
    private static TimerState.Time base() { return time(1_000, 1_000_000, 7); }
    private static TimerState running() { return TimerState.start(10_000, A, base()); }
    private static void check(boolean okay, String message) { if (!okay) throw new AssertionError(message); }
    private static void equal(long expected, long actual) { check(expected == actual, expected + " != " + actual); }
    private static void phase(TimerState.Phase expected, TimerState state) { check(state.phase == expected, "Unexpected phase " + state.phase); }
    private static void rejected(Runnable action) {
        boolean failed = false;
        try { action.run(); } catch (IllegalArgumentException expected) { failed = true; }
        check(failed, "Invalid state accepted");
    }

    public static void main(String[] args) {
        phase(TimerState.Phase.NONE, TimerState.empty());
        equal(0, TimerState.empty().remainingMs(base())); groups++;

        equal(1_000, TimerState.start(1_000, A, base()).remainingMs(base()));
        equal(TimerState.MAX_DURATION_MS, TimerState.start(TimerState.MAX_DURATION_MS, A, base()).remainingMs(base()));
        rejected(new Runnable() { public void run() { TimerState.start(999, A, base()); } });
        rejected(new Runnable() { public void run() { TimerState.start(TimerState.MAX_DURATION_MS + 1, A, base()); } });
        rejected(new Runnable() { public void run() { TimerState.start(10_000, A, time(Long.MAX_VALUE - 5, 100, 7)); } }); groups++;

        TimerState state = running();
        equal(7_000, state.remainingMs(time(4_000, 1_003_000, 7)));
        equal(1, state.remainingMs(time(10_999, 1_009_999, 7)));
        equal(0, state.remainingMs(time(11_000, 1_010_000, 7)));
        equal(0, state.remainingMs(time(90_000, 1_090_000, 7))); groups++;

        // elapsedRealtime advances while asleep: there is no uptime-based extension.
        phase(TimerState.Phase.FINISHED, state.reconcile(time(800_000, 1_799_000, 7), false)); groups++;

        equal(7_000, state.remainingMs(time(4_000, 6_000_000, 7)));
        equal(7_000, state.remainingMs(time(4_000, 100, 7)));
        TimerState clockEdited = state.reconcile(time(4_000, 6_000_000, 7), true);
        equal(11_000, clockEdited.deadlineElapsedMs);
        equal(6_007_000, clockEdited.deadlineWallMs);
        equal(6_000, clockEdited.remainingMs(time(5_000, 42, 7))); groups++;

        TimerState rebooted = state.reconcile(time(300, 1_006_000, 8), false);
        phase(TimerState.Phase.RUNNING, rebooted);
        equal(4_000, rebooted.remainingMs(time(300, 1_006_000, 8)));
        equal(4_300, rebooted.deadlineElapsedMs);
        equal(8, rebooted.bootCount);
        equal(3_000, rebooted.remainingMs(time(1_300, 7_000_000, 8))); groups++;

        phase(TimerState.Phase.FINISHED, state.reconcile(time(300, 1_010_000, 8), false));
        equal(10_000, state.reconcile(time(300, 900_000, 8), false).remainingAtAnchorMs); groups++;

        TimerState paused = state.pause(B, time(4_000, 1_003_000, 7));
        phase(TimerState.Phase.PAUSED, paused);
        check(paused.token.equals(B), "Pause must invalidate the old generation");
        equal(7_000, paused.remainingMs(time(900_000, 9_000_000, 8)));
        check(paused.expire(A, time(900_000, 9_000_000, 8)) == paused, "Paused timer accepted an old expiry"); groups++;

        TimerState resumed = paused.resume(C, time(100_000, 5_000_000, 8));
        phase(TimerState.Phase.RUNNING, resumed);
        equal(10_000, resumed.durationMs); equal(7_000, resumed.remainingAtAnchorMs);
        equal(107_000, resumed.deadlineElapsedMs);
        check(resumed.expire(A, time(200_000, 6_000_000, 8)) == resumed, "Pre-pause alarm completed resumed timer");
        equal(7_000, resumed.reconcile(time(1, 1, 9), false).remainingAtAnchorMs); groups++;

        TimerState shortPause = state.pause(B, time(10_500, 1_009_500, 7));
        equal(500, shortPause.remainingAtAnchorMs);
        equal(500, shortPause.resume(C, time(50_000, 2_000_000, 7)).remainingMs(time(50_000, 2_000_000, 7))); groups++;

        TimerState replacement = TimerState.start(20_000, B, time(9_000, 1_008_000, 7));
        check(replacement.expire(A, time(40_000, 1_039_000, 7)) == replacement, "Replaced alarm affected new timer");
        TimerState canceled = replacement.cancel(C);
        phase(TimerState.Phase.NONE, canceled);
        check(canceled.expire(B, time(40_000, 1_039_000, 7)) == canceled, "Canceled timer accepted late expiry"); groups++;

        check(state.expire(A, time(10_999, 1_009_999, 7)) == state, "Early alarm completed timer");
        TimerState finished = state.expire(A, time(11_000, 1_010_000, 7));
        phase(TimerState.Phase.FINISHED, finished);
        equal(0, finished.remainingMs(time(99_000, 10, 99)));
        check(finished.needsNotification(), "Completion did not request an alert");
        TimerState delivered = finished.markNotified();
        check(!delivered.needsNotification(), "Completion requested duplicate alert");
        check(delivered.expire(A, time(99_000, 10, 99)) == delivered, "Repeated alarm changed delivered timer"); groups++;

        TimerState latePause = state.pause(B, time(11_000, 1_010_000, 7));
        phase(TimerState.Phase.FINISHED, latePause);
        check(latePause.token.equals(A) && latePause.needsNotification(), "Pause hid a due completion");
        check(latePause.resume(C, base()) == latePause, "Resume restarted finished timer without explicit start"); groups++;

        TimerState restored = TimerState.restore(resumed.phase, resumed.durationMs, resumed.remainingAtAnchorMs,
                resumed.deadlineElapsedMs, resumed.deadlineWallMs, resumed.bootCount, resumed.token, resumed.notified);
        equal(6_500, restored.remainingMs(time(100_500, 5_000_500, 8)));
        TimerState restoredDelivered = TimerState.restore(delivered.phase, delivered.durationMs, 0, 0, 0,
                delivered.bootCount, delivered.token, true);
        check(!restoredDelivered.needsNotification(), "Restore lost completion deduplication"); groups++;

        rejected(new Runnable() { public void run() { TimerState.restore(TimerState.Phase.PAUSED, 10_000, -1, 0, 0, 7, A, false); } });
        rejected(new Runnable() { public void run() { TimerState.restore(TimerState.Phase.RUNNING, 10_000, 10_000, 0, 0, 7, A, false); } });
        rejected(new Runnable() { public void run() { TimerState.restore(TimerState.Phase.FINISHED, 10_000, 1, 0, 0, 7, A, false); } });
        rejected(new Runnable() { public void run() { TimerState.start(1_000, "bad-token", base()); } });
        rejected(new Runnable() { public void run() { time(1, 1, -1); } }); groups++;

        System.out.println(groups + " timer state groups passed: limits, elapsed sleep, wall edits, reboot, pause/resume, replacement/cancel, stale/early expiry, once-only completion, restoration validation.");
    }
}
