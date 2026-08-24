package org.example.beamsearch.common;

import java.util.*;

public class Box {
    public int typeNum;
    public int count;
    public double volume;
    public double scoreVolume;
    public double sizeVolume;
    public String color;
    public String name;
    public String id;
    public int orientId;
    public int containerOrientId;
    public int[][] variation = null;
    public int[] size = new int[2];
    public double length;
    public double width;

    public ArrayList<Queue<String>> ids = new ArrayList<>(Arrays.asList(new LinkedList<String>(), new LinkedList<String>()));

    public Box copy() {
        return new Box(typeNum, count, volume, scoreVolume, sizeVolume, color, name, id, orientId, containerOrientId, variation, size, length, width, ids);
    }

    public Box() {
    }

    public Box(int typeNum, int count, double volume, double scoreVolume, double sizeVolume, String color, String name, String id, int orientId, int containerOrientId, int[][] variation, int[] size, double length, double width, ArrayList<Queue<String>> ids) {
        this.typeNum = typeNum;
        this.count = count;
        this.volume = volume;
        this.scoreVolume = scoreVolume;
        this.sizeVolume = sizeVolume;
        this.color = color;
        this.name = name;
        this.id = id;
        this.orientId = orientId;
        this.containerOrientId = containerOrientId;
        this.variation = variation;
        this.size = size;
        this.length = length;
        this.width = width;
        this.ids = ids;
    }

    public void initializeWitColorFromCsv(List<String> lineData, HashMap<String, Container> containers, int _type) {
        typeNum = _type;

        name = lineData.get(0);
        id = lineData.get(1);
        count = Integer.parseInt(lineData.get(2));
        color = lineData.get(3);
        length = Double.parseDouble(lineData.get(4));
        width = Double.parseDouble(lineData.get(5));
        String isSpecial = lineData.get(6);

        if (isSpecial.equals("1")) {
            volume = -1.0;
            return;
        }

        size[0] = (int) (10 * length);
        size[1] = (int) (10 * width);

        int containerLength = containers.get(color).length;
        int containerWidth = containers.get(color).width;
        containerOrientId = containers.get(color).orientId;


        if (containerOrientId == 0) {
            variation = new int[2][2];

            variation[0][0] = (int) (10 * width);
            variation[0][1] = (int) (10 * length);

            variation[1][0] = (int) (10 * length);
            variation[1][1] = (int) (10 * width);

            orientId = 2;

            if ((variation[0][0] > containerWidth || variation[0][1] > containerLength)
                    && (variation[1][0] > containerWidth) || variation[1][1] > containerLength) {
                volume = 0.0;
                return;
            }

        } else {
            variation = new int[1][2];
            if (containers.get(color).orientId == 1) {
                variation[0][0] = 10 * width <= containerWidth ? (int) (10 * width) : 0;
                variation[0][1] = 10 * length <= containerLength ? (int) (10 * length) : 0;
                orientId = 0;
            } else if (containers.get(color).orientId == 2) {
                variation[0][0] = 10 * length <= containerWidth ? (int) (10 * length) : 0;
                variation[0][1] = 10 * width <= containerLength ? (int) (10 * width) : 0;
                orientId = 1;
            }

            if (variation[0][0] == 0 || variation[0][1] == 0) {
                volume = 0.0;
                return;
            }
        }

        // 第 8 列（可选）: 旋转标志。 "0"=可旋转（矩形 0°≡180°, 90°≡270°），"1"=禁止旋转。
        // 该标志覆盖 containerOrientId 决定的默认旋转能力。
        if (lineData.size() > 7) {
            String rotatable = lineData.get(7);
            if (rotatable.equals("1")) {
                orientId = 0;
                variation = new int[1][2];
                variation[0][0] = (int) (10 * width);
                variation[0][1] = (int) (10 * length);
            }
        }

        volume = 1.0 * (length * 10) * (width * 10);

        double r = 1;

        for (int i = 0; i < variation.length; i++) {
            double temp = 1;
            temp += variation[i][0] / containerLength * variation[i][0] / containerLength;
            temp += variation[i][1] / containerWidth * variation[i][1] / containerWidth;
            if (temp > r) {
                r = temp;
            }
        }
        scoreVolume = volume * r;

        double r1 = 1;
        r1 += (double) variation[0][1] / containerLength * variation[0][1] / containerLength;
        r1 += (double) variation[0][0] / containerWidth * variation[0][0] / containerWidth;
        r = r1;
        if (variation.length > 1) {
            double r2 = 1;
            r2 += (double) variation[1][1] / containerLength * variation[1][1] / containerLength;
            r2 += (double) variation[1][0] / containerWidth * variation[1][0] / containerWidth;
            if (r < r2) {
                r = r2;
            }
        }
        sizeVolume = volume * r;
    }
}

