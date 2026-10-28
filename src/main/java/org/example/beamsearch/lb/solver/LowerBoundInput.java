package org.example.beamsearch.lb.solver;

import org.example.beamsearch.lb.entity.Item;

/** Validated copies, with forced orientations resolved and a common scale removed. */
record LowerBoundInput(int width, int height, Item[] items) {
    static LowerBoundInput prepare(int width, int height, Item[] items, boolean allowRotation) {
        if (width <= 0 || height <= 0 || items == null) {
            throw new IllegalArgumentException("Positive board dimensions and non-null items required");
        }
        Item[] copies = new Item[items.length];
        int divisor = gcd(width, height);
        for (int i = 0; i < items.length; i++) {
            Item item = items[i];
            if (item == null || item.w <= 0 || item.h <= 0) {
                throw new IllegalArgumentException("Invalid rectangle at index " + i);
            }
            boolean fixedFits = item.w <= width && item.h <= height;
            boolean rotatedFits = allowRotation && item.rotatable && item.h <= width && item.w <= height;
            if (!fixedFits && !rotatedFits) {
                throw new IllegalArgumentException("Rectangle has no feasible orientation: " + item.id);
            }
            int w = fixedFits ? item.w : item.h;
            int h = fixedFits ? item.h : item.w;
            copies[i] = Item.fromScaledUnits(item.id, w, h,
                    fixedFits && rotatedFits && item.w != item.h);
            divisor = gcd(divisor, gcd(w, h));
        }
        for (int i = 0; i < copies.length; i++) {
            Item item = copies[i];
            copies[i] = Item.fromScaledUnits(item.id, item.w / divisor, item.h / divisor, item.rotatable);
        }
        return new LowerBoundInput(width / divisor, height / divisor, copies);
    }

    boolean hasRotation() {
        for (Item item : items) {
            if (item.rotatable) {
                return true;
            }
        }
        return false;
    }

    private static int gcd(int a, int b) {
        while (b != 0) {
            int remainder = a % b;
            a = b;
            b = remainder;
        }
        return a;
    }
}
