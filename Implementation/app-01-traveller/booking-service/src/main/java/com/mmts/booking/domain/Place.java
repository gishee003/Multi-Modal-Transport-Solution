package com.mmts.booking.domain;

import jakarta.persistence.Embeddable;

/** A named point on the map (the shared "Place" value type). Stored inside the leg row. */
@Embeddable
public class Place {

    private String name;
    private double lat;
    private double lon;
    private String stopId;

    protected Place() {
        // for JPA
    }

    public Place(String name, double lat, double lon, String stopId) {
        this.name = name;
        this.lat = lat;
        this.lon = lon;
        this.stopId = stopId;
    }

    public String getName() {
        return name;
    }

    public double getLat() {
        return lat;
    }

    public double getLon() {
        return lon;
    }

    public String getStopId() {
        return stopId;
    }
}
