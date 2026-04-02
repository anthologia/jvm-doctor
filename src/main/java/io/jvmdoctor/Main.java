package io.jvmdoctor;

/**
 * Launcher class — required to avoid JavaFX module system issues with fat JARs.
 */
public class Main {
    public static void main(String[] args) {
        JvmDoctorApp.main(args);
    }
}
