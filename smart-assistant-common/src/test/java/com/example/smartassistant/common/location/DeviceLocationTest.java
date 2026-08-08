package com.example.smartassistant.common.location;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeviceLocationTest {

    @Test
    void acceptsFreshCityLevelLocation() {
        long now = System.currentTimeMillis();
        DeviceLocation location = DeviceLocation.from(Map.of(
                "latitude", 39.9042,
                "longitude", 116.4074,
                "accuracyMeters", 1200,
                "capturedAt", now));

        assertTrue(location.isUsableAt(now));
    }

    @Test
    void rejectsExpiredOutOfRangeAndVeryInaccurateLocations() {
        long now = System.currentTimeMillis();
        assertFalse(new DeviceLocation(39.9, 116.4, 1000d,
                now - Duration.ofMinutes(11).toMillis()).isUsableAt(now));
        assertFalse(new DeviceLocation(91d, 116.4, 1000d, now).isUsableAt(now));
        assertFalse(new DeviceLocation(39.9, 116.4, 80_000d, now).isUsableAt(now));
    }
}
