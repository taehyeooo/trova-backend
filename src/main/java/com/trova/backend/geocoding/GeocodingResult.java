package com.trova.backend.geocoding;

public record GeocodingResult(Double latitude, Double longitude) {
    public static GeocodingResult empty() {
        return new GeocodingResult(null, null);
    }
}
