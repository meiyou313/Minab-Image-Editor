package org.telegram.messenger;

import java.util.concurrent.atomic.AtomicInteger;

public class SharedConfig {
    public static final int PERFORMANCE_CLASS_LOW = 0;
    public static final int PERFORMANCE_CLASS_AVERAGE = 1;
    public static final int PERFORMANCE_CLASS_HIGH = 2;

    private static final AtomicInteger localIdCounter = new AtomicInteger(-210000);

    public static int getDevicePerformanceClass() {
        return PERFORMANCE_CLASS_HIGH;
    }

    public static int getLastLocalId() {
        return localIdCounter.decrementAndGet();
    }
}
