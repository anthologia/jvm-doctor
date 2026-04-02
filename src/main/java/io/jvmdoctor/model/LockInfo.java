package io.jvmdoctor.model;

public record LockInfo(String lockId, String lockClassName) {

    @Override
    public String toString() {
        return lockClassName + "@" + lockId;
    }
}
