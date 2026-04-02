package io.jvmdoctor.model;

import java.util.List;

public record ThreadInfo(
        String name,
        long threadId,
        String state,
        int priority,
        boolean daemon,
        LockInfo waitingOnLock,
        List<LockInfo> heldLocks,
        List<StackFrame> stackFrames
) {
    public int stackDepth() {
        return stackFrames == null ? 0 : stackFrames.size();
    }

    public boolean isBlocked() {
        return "BLOCKED".equalsIgnoreCase(state);
    }

    public boolean isWaiting() {
        return "WAITING".equalsIgnoreCase(state) || "TIMED_WAITING".equalsIgnoreCase(state);
    }
}
