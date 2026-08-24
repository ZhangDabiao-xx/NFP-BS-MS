package org.example.beamsearch.common;

import java.util.*;

public class Solution {
    private Instance inst;
    private ArrayList<PlacedCuboid> placedCuboid = new ArrayList<PlacedCuboid>();


    public ArrayList<PlacedCuboid> getPlacedCuboid() {
        return placedCuboid;
    }

    public Instance getInst() {
        return inst;
    }


    private double boxesVolume = 0;

    public Solution(Instance inst) {
        this.inst = inst;
    }

    public void add(PlacedCuboid p) {
        placedCuboid.add(p);
        boxesVolume += p.getVolume();
    }

    public void add(PlacedBlock pb) {
        for (PlacedCuboid pc : pb.block.cuboid) {
            add(pc.translate(pb.x, pb.y));
        }
    }

    public double getUtilization() {
        return 100 * boxesVolume / (inst.length) / (inst.width);
    }

    public double getContainerArea() {
        return (inst.length) * (inst.width);
    }


    public double getBoxesVolume() {
        return boxesVolume;
    }

    @Override
    public String toString() {
        String result = "";
        result += ("Container length: " + inst.length + "\n");
        result += ("Container width: " + inst.width + "\n");

        result += ("Boxes Volume: " + boxesVolume + "\n");
        result += ("Container Volume: " + (inst.length * inst.width) + "\n");
        result += ("Utilization: " + getUtilization() + "\n\n");

        result += ("Packed Boxes: " + placedCuboid.size() + "\n");
        result += ("format: x y length width\n");
        for (PlacedCuboid p : placedCuboid) {
            result += (p.x + " " + p.y + " " + " "
                    + (p.length) + " " + (p.width) + "\n");
        }


        result += ("\nnumOfBlemishes: " + 0 + "\n");

        return result;
    }

}

