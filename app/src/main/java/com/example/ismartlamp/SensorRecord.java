package com.example.ismartlamp;

public class SensorRecord {
    private String lampId;
    private String lampName;
    private float h2s, o2, co, ch4, temp, hum;
    private String timestamp;
    private double latitude, longitude;

    public SensorRecord(String lampName, float h2s, float o2, float co, float ch4,
                        float temp, float hum, String timestamp,
                        String lampId, double latitude, double longitude) {
        this.lampId = lampId;
        this.lampName = lampName;
        this.h2s = h2s;
        this.o2 = o2;
        this.co = co;
        this.ch4 = ch4;
        this.temp = temp;
        this.hum = hum;
        this.timestamp = timestamp;
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public String getLampId() { return lampId; }
    public String getLampName() { return lampName; }
    public float getH2s() { return h2s; }
    public float getO2() { return o2; }
    public float getCo() { return co; }
    public float getCh4() { return ch4; }
    public float getTemp() { return temp; }
    public float getHum() { return hum; }
    public String getDatetime() { return timestamp; }
    public double getLatitude() { return latitude; }
    public double getLongitude() { return longitude; }

    public float getValueByType(String type) {
        switch (type) {
            case "h2s": return h2s;
            case "o2": return o2;
            case "co": return co;
            case "ch4": return ch4;
            case "temperature": return temp;
            case "humidity": return hum;
            default: return 0f;
        }
    }
}
