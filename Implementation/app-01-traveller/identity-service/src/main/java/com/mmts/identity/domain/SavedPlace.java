package com.mmts.identity.domain;

import jakarta.persistence.Embeddable;

/** A place the user saved, such as "Home" or "Office". Stored inside the user's account. */
@Embeddable
public class SavedPlace {

    private String label;
    private String name;
    private double lat;
    private double lon;
    private String stopId;

    protected SavedPlace() {
        // for JPA
    }

    public SavedPlace(String label, String name, double lat, double lon, String stopId) {
        this.label = label;
        this.name = name;
        this.lat = lat;
        this.lon = lon;
        this.stopId = stopId;
    }

    public String getLabel() { return label; }
    public String getName() { return name; }
    public double getLat() { return lat; }
    public double getLon() { return lon; }
    public String getStopId() { return stopId; }
}
