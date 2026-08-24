package org.example.beamsearch.common;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;


public class Instance implements Serializable, Comparable<Instance> {
    public int length;
    public int width;
    public int typeCount;
    public int totalBoxCount = 0;
    public double totalBoxVolume = 0;
    public Box[] boxes = null;

    public Instance() {
    }

    public Instance(ArrayList<Box> oldBoxs, Container container) {
        this.length = container.length;
        this.width = container.width;

        typeCount = oldBoxs.size();
        totalBoxCount = 0;
        totalBoxVolume = 0;
        boxes = new Box[typeCount];
        for (int i = 0; i < oldBoxs.size(); i++) {
            boxes[i] = oldBoxs.get(i);
            boxes[i].typeNum = i + 1;
            boxes[i].count = oldBoxs.get(i).count;
            totalBoxVolume += boxes[i].volume * boxes[i].count;
            totalBoxCount += boxes[i].count;
        }
    }

    public Instance(Instance inst, ArrayList<Box> oldBoxs) {
        this.length = inst.length;
        this.width = inst.width;
        typeCount = oldBoxs.size();
        totalBoxCount = oldBoxs.size();
        totalBoxVolume = 0;
        boxes = new Box[totalBoxCount];
        for (int i = 0; i < oldBoxs.size(); i++) {
            boxes[i] = oldBoxs.get(i);
            boxes[i].typeNum = i + 1;
            boxes[i].count = 1;
            totalBoxVolume += boxes[i].volume;
        }
    }

    public ArrayList<Box> loadFromCsvFile(BufferedReader br, HashMap<String, Container> containers) throws IOException {
        ArrayList<Box> notopimized = new ArrayList<>();
        ArrayList<Box> boxs = new ArrayList<Box>();
        int type = 1;

        String line = br.readLine();
        line = br.readLine();
        while(line != null) {
            List<String> lineData = Arrays.asList(line.split(","));
            Box box = new Box();
            box.initializeWitColorFromCsv(lineData, containers, type);
            if(box.volume == -1.0) {
                line = br.readLine();
                continue;
            }else if (box.volume == 0.0) {
                notopimized.add(box);
                line = br.readLine();
                continue;
            }
            int found = -1;
            for (int i = 0; i < boxs.size(); i++) {
                if(box.orientId == 2) {
                    if(boxs.get(i).orientId == 2
                            && Math.max(boxs.get(i).variation[0][0], boxs.get(i).variation[0][1]) == Math.max(box.variation[0][0], box.variation[0][1])
                            && Math.min(boxs.get(i).variation[0][0], boxs.get(i).variation[0][1]) == Math.min(box.variation[0][0], box.variation[0][1])
                            && boxs.get(i).color.equals(box.color)) {
                        found = i;
                        break;
                    }
                }else {
                    if (boxs.get(i).orientId != 2
                            && boxs.get(i).variation[0][0] == box.variation[0][0]
                            && boxs.get(i).variation[0][1] == box.variation[0][1]
                            && boxs.get(i).color.equals(box.color)) {
                        found = i;
                        break;
                    }
                }
            }
            if (-1 != found) {
                int count = box.count;
                if(box.size[0] == boxs.get(found).size[0] && box.size[1] == boxs.get(found).size[1]) {
                    for (int i = 0; i < count; i++) {
                        boxs.get(found).ids.get(0).offer(box.id);
                    }
                }else {
                    for (int i = 0; i < count; i++) {
                        boxs.get(found).ids.get(1).offer(box.id);
                    }
                }
                boxs.get(found).count += box.count;
                totalBoxCount += box.count;
            } else {
                totalBoxCount += box.count;
                int count = box.count;
                for (int i = 0; i < count; i++) {
                    box.ids.get(0).offer(box.id);
                }
                boxs.add(box);
                type++;
            }
            line = br.readLine();
        }

        typeCount = type - 1;
        totalBoxCount = 0;
        boxes = new Box[boxs.size()];
        for (int i = 0; i < boxs.size(); i++) {
            boxes[i] = boxs.get(i);
            totalBoxCount += boxes[i].count;
            totalBoxVolume += boxes[i].volume * boxes[i].count;
        }
        return notopimized;
    }
    @Override
    public int compareTo(Instance o) {
        int difference = o.totalBoxCount - this.totalBoxCount;
        return difference;
    }
}

