package org.example.beamsearch.lb.entity;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** One physical rectangle. w/h use integer units; one input unit equals ten integer units. */
public class Item {
    public String id;
    public int w;
    public int h;
    public long s;
    /** Effective only when the solver's allowRotation argument is true. */
    public boolean rotatable;

    private Item() {
    }

    public Item(String id, double w, double h) {
        this(id, w, h, true);
    }

    public Item(String id, double w, double h, boolean rotatable) {
        this.id = id;
        this.w = scale(w);
        this.h = scale(h);
        this.s = (long) this.w * this.h;
        this.rotatable = rotatable;
    }

    public static Item fromScaledUnits(String id, int w, int h) {
        return fromScaledUnits(id, w, h, true);
    }

    public static Item fromScaledUnits(String id, int w, int h, boolean rotatable) {
        if (w <= 0 || h <= 0) {
            throw new IllegalArgumentException("Rectangle dimensions must be positive");
        }
        Item item = new Item();
        item.id = id;
        item.w = w;
        item.h = h;
        item.s = (long) w * h;
        item.rotatable = rotatable;
        return item;
    }

    /** Reject unsupported precision rather than silently solving a smaller rectangle. */
    public static int scale(double value) {
        if (!Double.isFinite(value) || value <= 0) {
            throw new IllegalArgumentException("Dimension must be finite and positive");
        }
        try {
            return BigDecimal.valueOf(value).movePointRight(1).intValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Dimension must fit integer units at precision 0.1: " + value,
                    exception);
        }
    }

    public static Comparator<Item> itemComparatorByIncreaseW = Comparator.comparingInt(o -> o.w);
    public static Comparator<Item> itemComparatorByIncreaseH = Comparator.comparingInt(o -> o.h);
    public static Comparator<Item> itemComparatorByIncreaseS = Comparator.comparingLong(o -> (long) o.w * o.h);

    /** Read rectangle CSV, expanding Qty; 0 means rotatable and 1 means fixed orientation. */
    public static Item[] getItems(File workpieceFile) throws IOException {
        List<Item> result = new ArrayList<>();
        List<String> lines = Files.readAllLines(workpieceFile.toPath(), StandardCharsets.UTF_8);
        for (int lineNumber = 1; lineNumber < lines.size(); lineNumber++) {
            if (lines.get(lineNumber).isBlank()) {
                continue;
            }
            String[] columns = lines.get(lineNumber).split(",", -1);
            try {
                if (columns.length < 7 || !columns[6].strip().equals("0")) {
                    throw new IllegalArgumentException("Expected a rectangle row (IsSpecial=0)");
                }
                int quantity = Integer.parseInt(columns[2].strip());
                if (quantity < 0) {
                    throw new IllegalArgumentException("Negative quantity");
                }
                boolean rotatable = true;
                if (columns.length > 7 && !columns[7].isBlank()) {
                    String flag = columns[7].strip();
                    if (!flag.equals("0") && !flag.equals("1")) {
                        throw new IllegalArgumentException("Rotatable must be 0 or 1");
                    }
                    rotatable = flag.equals("0");
                }
                for (int copy = 0; copy < quantity; copy++) {
                    result.add(new Item(columns[1].strip(), Double.parseDouble(columns[4].strip()),
                            Double.parseDouble(columns[5].strip()), rotatable));
                }
            } catch (IllegalArgumentException exception) {
                throw new IOException("Invalid rectangle CSV: " + workpieceFile + " line " + (lineNumber + 1),
                        exception);
            }
        }
        return result.toArray(Item[]::new);
    }
}
