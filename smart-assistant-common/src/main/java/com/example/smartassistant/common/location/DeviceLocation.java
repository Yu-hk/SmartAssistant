package com.example.smartassistant.common.location;

import java.time.Duration;
import java.util.Map;

/**
 * User-authorized, short-lived device location supplied by the client.
 *
 * <p>The location is request context only. Callers should not persist it in
 * conversation history, semantic caches, or ordinary application logs.</p>
 */
public class DeviceLocation {

    public static final long MAX_AGE_MILLIS = Duration.ofMinutes(10).toMillis();
    public static final double MAX_ACCURACY_METERS = 50_000d;
    private static final long MAX_CLOCK_SKEW_MILLIS = Duration.ofMinutes(1).toMillis();

    private Double latitude;
    private Double longitude;
    private Double accuracyMeters;
    private Long capturedAt;

    public DeviceLocation() {
    }

    public DeviceLocation(Double latitude, Double longitude, Double accuracyMeters, Long capturedAt) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.accuracyMeters = accuracyMeters;
        this.capturedAt = capturedAt;
    }

    public Double getLatitude() {
        return latitude;
    }

    public void setLatitude(Double latitude) {
        this.latitude = latitude;
    }

    public Double getLongitude() {
        return longitude;
    }

    public void setLongitude(Double longitude) {
        this.longitude = longitude;
    }

    public Double getAccuracyMeters() {
        return accuracyMeters;
    }

    public void setAccuracyMeters(Double accuracyMeters) {
        this.accuracyMeters = accuracyMeters;
    }

    public Long getCapturedAt() {
        return capturedAt;
    }

    public void setCapturedAt(Long capturedAt) {
        this.capturedAt = capturedAt;
    }

    public boolean isUsable() {
        return isUsableAt(System.currentTimeMillis());
    }

    public boolean isUsableAt(long nowMillis) {
        if (!isFinite(latitude) || !isFinite(longitude)
                || latitude < -90d || latitude > 90d
                || longitude < -180d || longitude > 180d) {
            return false;
        }
        if (accuracyMeters != null
                && (!isFinite(accuracyMeters) || accuracyMeters < 0d
                || accuracyMeters > MAX_ACCURACY_METERS)) {
            return false;
        }
        if (capturedAt == null) {
            return false;
        }
        long age = nowMillis - capturedAt;
        return age >= -MAX_CLOCK_SKEW_MILLIS && age <= MAX_AGE_MILLIS;
    }

    public String coordinateQuery() {
        if (!isUsable()) {
            throw new IllegalStateException("Device location is not usable");
        }
        return String.format(java.util.Locale.ROOT, "%.6f,%.6f", latitude, longitude);
    }

    public static DeviceLocation from(Object value) {
        if (value instanceof DeviceLocation location) {
            return location;
        }
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        return new DeviceLocation(
                number(map.get("latitude")),
                number(map.get("longitude")),
                number(map.get("accuracyMeters")),
                longNumber(map.get("capturedAt")));
    }

    private static boolean isFinite(Double value) {
        return value != null && Double.isFinite(value);
    }

    private static Double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private static Long longNumber(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }
}
