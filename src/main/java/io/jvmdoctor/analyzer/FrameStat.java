package io.jvmdoctor.analyzer;

public record FrameStat(
        String className,
        String methodName,
        long threadCount,
        int totalThreads
) {
    public double percentage() {
        return totalThreads > 0 ? 100.0 * threadCount / totalThreads : 0;
    }

    public String frameKey() {
        return className + "." + methodName;
    }

    /** com.example.Foo → Foo */
    public String simpleClassName() {
        int dot = className.lastIndexOf('.');
        return dot >= 0 ? className.substring(dot + 1) : className;
    }
}
