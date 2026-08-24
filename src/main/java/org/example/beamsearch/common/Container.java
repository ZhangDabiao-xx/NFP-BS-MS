package org.example.beamsearch.common;

public class Container {
    public String color;
    public int length;
    public int width;
    public int orientId;

    public Container(String color, 	double length, double width, int orientId){
        this.color = color;
        this.length = (int) (10*length);
        this.width = (int) (10*width);
        this.orientId = orientId;

    }

}
