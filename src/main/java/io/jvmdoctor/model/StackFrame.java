package io.jvmdoctor.model;

public record StackFrame(String className, String methodName, String fileName, int lineNumber) {

    @Override
    public String toString() {
        String location = (fileName != null)
                ? fileName + (lineNumber >= 0 ? ":" + lineNumber : "")
                : "Unknown Source";
        return "at " + className + "." + methodName + "(" + location + ")";
    }
}
