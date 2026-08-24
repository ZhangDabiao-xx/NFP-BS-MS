package org.example.beamsearch.spacemanager;

import org.example.beamsearch.common.Space;

import java.util.ArrayList;
import java.util.Comparator;

public class SpaceManager {
    private ArrayList<Space> spaceList;
    private final Comparator<Space> spaceComparator;

    public SpaceManager(final Comparator<Space> spaceComparator) {
        this.spaceList = new ArrayList<Space>();
        this.spaceComparator = spaceComparator;
    }

    private SpaceManager(ArrayList<Space> spaceList, final Comparator<Space> spaceComparator) {
        this.spaceList = spaceList;
        this.spaceComparator = spaceComparator;
    }

    public static ArrayList<Space> deleteContained(ArrayList<Space> spaceList) {
        ArrayList<Space> result = new ArrayList<Space>();
        for (int i=0; i<spaceList.size(); i++) {
            Space s1 = spaceList.get(i);
            boolean contained = false;
            for (int j=0; j<spaceList.size(); j++) {
                if (j!=i) {
                    Space s2 = spaceList.get(j);
                    if (s2.contains(s1)) {
                        contained = true;
                        break;
                    }
                }
            }
            if (!contained) {
                result.add(s1);
            }
        }
        return result;
    }

    public boolean contains(Space space) {
        for (Space s : spaceList) {
            if (s.contains(space)) {
                return true;
            }
        }
        return false;
    }

    public void insert(Space space) {
        if (!contains(space)) {
            spaceList.add(space);
        }
    }
    public void insert(ArrayList<Space> spaceList) {
        spaceList = deleteContained(spaceList);

        for (Space s:spaceList) {
            insert(s);
        }
    }

    public Space chooseBestSpace() {
        if (spaceList.size() == 0) {
            return null;
        }

        Space optimal = spaceList.get(0);
        for (int i=0; i<spaceList.size(); i++) {
            Space s = spaceList.get(i);
            if (spaceComparator.compare(s, optimal) < 0) {
                optimal = s;
            }
        }
        return optimal;
    }

    public boolean hasFreeSpace() {
        return spaceList.size() > 0;
    }

    public void deleteSpace(Space s) {
        spaceList.remove(s);
    }

    public ArrayList<Space> deleteOverlap(Space s) {
        ArrayList<Space> result = new ArrayList<Space>();
        ArrayList<Space> remain = new ArrayList<Space>();

        for (Space space : spaceList) {
            if (space.intersectTest(s)) {
                result.add(space);
            } else {
                remain.add(space);
            }
        }
        this.spaceList = remain;
        return result;
    }

    public SpaceManager copy() {
        ArrayList<Space> spaceList = (ArrayList<Space>) this.spaceList.clone();
        return new SpaceManager(spaceList, spaceComparator);
    }

    public ArrayList<Space> getSpaceList() {
        return spaceList;
    }

    public double getTotalSpaceArea(){
        double totalArea = 0;
        for (int i = 0; i < spaceList.size(); i++) {
            totalArea += spaceList.get(i).volume;
        }
        return totalArea;
    }
}

