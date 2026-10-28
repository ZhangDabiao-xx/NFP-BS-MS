package org.example.beamsearch.application;

import org.example.beamsearch.common.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class StageLowerBoundCalculatorTest {
    @Test
    void respectsDifferentRotationPermissionsForIdenticalDimensionsAndExpandsQuantities() {
        Instance mixed = instance(box("fixed", 2, 6, 4, false), box("rotating", 2, 6, 4, true));
        StageLowerBound bound = StageLowerBoundCalculator.calculate(mixed, StageLowerBoundCalculator.PRIORITY);
        // 两个固定 6x4 加两个可转 6x4 可以在一张 10x10 板上组成风车布局。
        assertEquals(1, bound.lowerBound());
        assertEquals(4, bound.itemCount());
        assertEquals(2, bound.fixedItemCount());
        assertEquals(2, bound.rotatableItemCount());
        assertNull(bound.calculation().bmBound());

        Instance fixed = instance(box("fixed", 4, 6, 4, false));
        StageLowerBound fixedBound = StageLowerBoundCalculator.calculate(fixed, StageLowerBoundCalculator.PRIORITY);
        assertEquals(2, fixedBound.lowerBound());
        assertEquals(0, fixedBound.rotatableItemCount());
        assertNotNull(fixedBound.calculation().bmBound());
    }

    @Test
    void usesActualAllowedOrientationWithoutMutatingBoxesOrConsumingIds() {
        Box box = box("forced-90", 2, 8, 3, true);
        box.variation = new int[][]{{80, 30}};
        box.ids.get(0).add("physical-1");
        box.ids.get(0).add("physical-2");
        Instance instance = new Instance(new ArrayList<>(List.of(box)), new Container("1", 6, 10, 0));
        int[][] orientations = Arrays.stream(box.variation).map(int[]::clone).toArray(int[][]::new);
        StageLowerBound bound = StageLowerBoundCalculator.calculate(instance, StageLowerBoundCalculator.PRIORITY);
        assertEquals(1, bound.lowerBound());
        assertEquals(0, bound.rotatableItemCount());
        assertEquals(2, box.count);
        assertEquals(1, box.typeNum);
        assertTrue(Arrays.deepEquals(orientations, box.variation));
        assertEquals(List.of("physical-1", "physical-2"), new ArrayList<>(box.ids.get(0)));
    }

    @Test
    void resolvesForcedRotationsAndIgnoresZeroQuantityTypes() {
        Box forced = box("forced", 2, 8, 3, true);
        Box unused = box("unused", 0, 9, 9, false);
        Instance instance = new Instance(new ArrayList<>(List.of(forced, unused)), new Container("1", 6, 10, 0));
        StageLowerBound bound = StageLowerBoundCalculator.calculate(instance, StageLowerBoundCalculator.ORDINARY);
        assertEquals(2, bound.itemCount());
        assertEquals(0, bound.rotatableItemCount());
        assertEquals(1, bound.lowerBound());
        assertEquals(2, forced.variation.length);
    }

    @Test
    void rejectsMissingLegalOrientationsRatherThanEnablingRotation() {
        Box fixed = box("fixed", 1, 8, 3, false);
        Instance instance = new Instance(new ArrayList<>(List.of(fixed)), new Container("1", 6, 10, 0));
        assertThrows(IllegalArgumentException.class,
                () -> StageLowerBoundCalculator.calculate(instance, StageLowerBoundCalculator.PRIORITY));
    }

    static Instance instance(Box... boxes) {
        return new Instance(new ArrayList<>(List.of(boxes)), new Container("1", 10, 10, 0));
    }

    static Box box(String id, int count, int length, int width, boolean rotatable) {
        Box box = new Box();
        HashMap<String, Container> containers = new HashMap<>();
        containers.put("1", new Container("1", 10, 10, 0));
        box.initializeWitColorFromCsv(List.of("case", id, "" + count, "1", "" + length, "" + width,
                "0", rotatable ? "0" : "1"), containers, 1);
        return box;
    }
}
