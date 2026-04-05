package io.jvmdoctor.model;

import java.util.List;

public record ThreadInfo(
        String name,
        long threadId,
        String state,
        int priority,
        boolean daemon,
        boolean virtual,
        String carrierThread,
        String nativeThreadId,
        double cpuMillis,
        double elapsedSeconds,
        LockInfo waitingOnLock,
        List<LockInfo> heldLocks,
        List<StackFrame> stackFrames
) {
    /**
     * Backwards-compatible constructor for non-virtual threads.
     */
    public ThreadInfo(String name, long threadId, String state, int priority, boolean daemon,
                      LockInfo waitingOnLock, List<LockInfo> heldLocks, List<StackFrame> stackFrames) {
        this(name, threadId, state, priority, daemon, false, null, null, Double.NaN, Double.NaN,
                waitingOnLock, heldLocks, stackFrames);
    }

    public int stackDepth() {
        return stackFrames == null ? 0 : stackFrames.size();
    }

    public boolean isBlocked() {
        return "BLOCKED".equalsIgnoreCase(state);
    }

    public boolean isWaiting() {
        return "WAITING".equalsIgnoreCase(state) || "TIMED_WAITING".equalsIgnoreCase(state);
    }

    public boolean isVirtual() {
        return virtual;
    }

    public boolean isPlatformThread() {
        return !virtual;
    }

    public boolean hasNativeThreadId() {
        return nativeThreadId != null && !nativeThreadId.isBlank();
    }

    public boolean hasCpuTime() {
        return !Double.isNaN(cpuMillis) && cpuMillis >= 0;
    }

    public boolean hasElapsedTime() {
        return !Double.isNaN(elapsedSeconds) && elapsedSeconds >= 0;
    }

    public double cpuLoadRatio() {
        if (!hasCpuTime() || !hasElapsedTime() || elapsedSeconds <= 0) {
            return Double.NaN;
        }
        return cpuMillis / (elapsedSeconds * 1000.0);
    }

    public double cpuMillisPerSecond() {
        if (!hasCpuTime() || !hasElapsedTime() || elapsedSeconds <= 0) {
            return Double.NaN;
        }
        return cpuMillis / elapsedSeconds;
    }
}
