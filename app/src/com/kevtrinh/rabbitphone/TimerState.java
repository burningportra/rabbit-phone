package com.kevtrinh.rabbitphone;

/** Immutable, Android-free single timer. Elapsed time includes device sleep. */
public final class TimerState {
    public enum Phase { NONE, RUNNING, PAUSED, FINISHED }
    public static final long MIN_DURATION_MS = 1_000L;
    public static final long MAX_DURATION_MS = 359_999_000L; // 99:59:59

    public static final class Time {
        public final long elapsedMs, wallMs;
        public final int bootCount;
        public Time(long elapsedMs, long wallMs, int bootCount) {
            if (elapsedMs < 0 || wallMs < 0 || bootCount < 0)
                throw new IllegalArgumentException("A valid elapsed clock, wall clock, and boot counter are required");
            this.elapsedMs = elapsedMs; this.wallMs = wallMs; this.bootCount = bootCount;
        }
    }

    public final Phase phase;
    public final long durationMs, remainingAtAnchorMs, deadlineElapsedMs, deadlineWallMs;
    public final int bootCount;
    public final String token;
    public final boolean notified;

    private TimerState(Phase phase, long durationMs, long remaining, long elapsedDeadline,
            long wallDeadline, int bootCount, String token, boolean notified) {
        this.phase = phase; this.durationMs = durationMs; this.remainingAtAnchorMs = remaining;
        this.deadlineElapsedMs = elapsedDeadline; this.deadlineWallMs = wallDeadline;
        this.bootCount = bootCount; this.token = token; this.notified = notified;
    }

    public static TimerState empty() { return new TimerState(Phase.NONE, 0, 0, 0, 0, 0, "", false); }

    public static TimerState start(long duration, String token, Time now) {
        validateDuration(duration);
        return running(duration, duration, token, now);
    }

    public static void validateDuration(long duration) {
        if (duration < MIN_DURATION_MS || duration > MAX_DURATION_MS)
            throw new IllegalArgumentException("Choose a timer from 1 second to 99 hours, 59 minutes, 59 seconds");
    }

    private static void validateToken(String token) {
        if (token == null || !token.matches("[a-f0-9]{32}"))
            throw new IllegalArgumentException("Invalid timer generation");
    }

    private static long deadline(long clock, long remaining) {
        if (clock > Long.MAX_VALUE - remaining) throw new IllegalArgumentException("Timer clock is out of range");
        return clock + remaining;
    }

    private static TimerState running(long duration, long remaining, String token, Time now) {
        validateToken(token);
        return new TimerState(Phase.RUNNING, duration, remaining, deadline(now.elapsedMs, remaining),
                deadline(now.wallMs, remaining), now.bootCount, token, false);
    }

    public long remainingMs(Time now) {
        if (phase == Phase.PAUSED) return remainingAtAnchorMs;
        if (phase != Phase.RUNNING) return 0;
        long end = bootCount == now.bootCount ? deadlineElapsedMs : deadlineWallMs;
        long clock = bootCount == now.bootCount ? now.elapsedMs : now.wallMs;
        return end <= clock ? 0 : Math.min(remainingAtAnchorMs, end - clock);
    }

    /** A same-boot wall-clock edit only updates the recovery anchor. After reboot,
     * wall time recovers the remainder, capped at the last known running remainder. */
    public TimerState reconcile(Time now, boolean wallClockChanged) {
        if (phase != Phase.RUNNING) return this;
        long remaining = remainingMs(now);
        if (remaining == 0) return new TimerState(Phase.FINISHED, durationMs, 0, 0, 0, now.bootCount, token, false);
        if (bootCount != now.bootCount || wallClockChanged) return running(durationMs, remaining, token, now);
        return this;
    }

    public TimerState expire(String alarmToken, Time now) {
        if (!token.equals(alarmToken) || phase != Phase.RUNNING) return this;
        return reconcile(now, false);
    }

    public TimerState pause(String nextToken, Time now) {
        TimerState current = reconcile(now, false);
        if (current.phase != Phase.RUNNING) return current;
        validateToken(nextToken);
        return new TimerState(Phase.PAUSED, durationMs, current.remainingMs(now), 0, 0,
                now.bootCount, nextToken, false);
    }

    public TimerState resume(String nextToken, Time now) {
        return phase == Phase.PAUSED ? running(durationMs, remainingAtAnchorMs, nextToken, now) : this;
    }

    public TimerState cancel(String nextToken) {
        validateToken(nextToken);
        return new TimerState(Phase.NONE, 0, 0, 0, 0, 0, nextToken, false);
    }

    public boolean needsNotification() { return phase == Phase.FINISHED && !notified; }

    public TimerState markNotified() {
        return needsNotification() ? new TimerState(phase, durationMs, 0, 0, 0, bootCount, token, true) : this;
    }

    public static TimerState restore(Phase phase, long duration, long remaining, long elapsedDeadline,
            long wallDeadline, int bootCount, String token, boolean notified) {
        if (phase == null || bootCount < 0 || elapsedDeadline < 0 || wallDeadline < 0)
            throw new IllegalArgumentException("Invalid stored timer clock");
        if (phase == Phase.NONE) {
            if (token == null || (!token.isEmpty() && !token.matches("[a-f0-9]{32}"))
                    || duration != 0 || remaining != 0 || notified || elapsedDeadline != 0 || wallDeadline != 0)
                throw new IllegalArgumentException("Invalid empty timer");
        } else {
            validateToken(token); validateDuration(duration);
            if (remaining < 0 || remaining > duration || (phase != Phase.FINISHED && remaining == 0)
                    || (notified && phase != Phase.FINISHED) || (phase == Phase.FINISHED && remaining != 0)
                    || (phase == Phase.RUNNING && (elapsedDeadline < remaining || wallDeadline < remaining))
                    || (phase != Phase.RUNNING && (elapsedDeadline != 0 || wallDeadline != 0)))
                throw new IllegalArgumentException("Invalid stored timer state");
        }
        return new TimerState(phase, duration, remaining, elapsedDeadline, wallDeadline, bootCount, token, notified);
    }
}
