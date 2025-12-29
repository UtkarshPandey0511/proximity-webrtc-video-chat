package com.example.demo;

public class PlayerState {

    public String id;
    public String name;
    public String room;

    public double x;
    public double y;

    public String clusterId;

    public PlayerState(String id, String name, String room) {
        this.id = id;
        this.name = name;
        this.room = room;
        this.x = 200;
        this.y = 200;
        this.clusterId = null;
    }
}
