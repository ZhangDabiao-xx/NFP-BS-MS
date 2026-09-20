package org.example.beamsearch.common;

import org.example.beamsearch.blockgenerator.GeneralBlock;

import java.util.ArrayList;

public class Space extends HyperCuboid {

    public int cornerId;
    public int cornerDistance = -1;
    public int Max_Corner_Count = 4;

    public Space(int ax, int ay, int bx, int by) {
        super(ax, ay, bx, by);
    }

    public Space intersect(Space o) {
        return new Space(Math.max(x1, o.x1), Math.max(y1, o.y1),
                Math.min(x2, o.x2), Math.min(y2, o.y2));
    }

    public int cornerDistance(int length, int width) {
        if (cornerDistance != -1) {
            return cornerDistance;
        }

        int[] sp = new int[]{
                x1 + y1,
                length - x2 + y1,
                length - x2 + width - y2,
                x1 + width - y2,
        };
        cornerId = 0;
        for (int i = 1; i < Max_Corner_Count; ++i) {
            if (sp[i] < sp[cornerId]) {
                cornerId = i;
            }
        }
        return cornerDistance = sp[cornerId];
    }

    public void cut(Space intersectSpace, ArrayList<Space> newSpaceList) {
        if (x1 != intersectSpace.x1) {
            newSpaceList.add(new Space(x1, y1, intersectSpace.x1, y2));
        }
        if (x2 != intersectSpace.x2) {
            newSpaceList.add(new Space(intersectSpace.x2, y1, x2, y2));
        }
        if (y1 != intersectSpace.y1) {
            newSpaceList.add(new Space(x1, y1, x2, intersectSpace.y1));
        }
        if (y2 != intersectSpace.y2) {
            newSpaceList.add(new Space(x1, intersectSpace.y2, x2, y2));
        }
    }

    public PlacedBlock packBlock(GeneralBlock b) {
        return packBlock(b, PlacementAnchor.NEAREST_BOARD_CORNER);
    }

    /**
     * 将一个已经确认能放入本空间的块放到指定锚点。
     *
     * @param b 待放置的可行矩形块。
     * @param placementAnchor 坐标锚点；为空时保持原有靠近板材外角的行为。
     * @return 带有最终左下角坐标的放置块。
     */
    public PlacedBlock packBlock(GeneralBlock b, PlacementAnchor placementAnchor) {
        PlacedBlock pb = new PlacedBlock(0, 0, b);
        if (placementAnchor == PlacementAnchor.SPACE_LOWER_LEFT) {
            pb.x = x1;
            pb.y = y1;
            return pb;
        }
        if (cornerId == 0) {
            pb.x = x1;
            pb.y = y1;
        } else if (cornerId == 1) {
            pb.x = x2 - b.length;
            pb.y = y1;
        } else if (cornerId == 2) {
            pb.x = x2 - b.length;
            pb.y = y2 - b.width;
        } else {
            pb.x = x1;
            pb.y = y2 - b.width;
        }
        return pb;
    }
}

