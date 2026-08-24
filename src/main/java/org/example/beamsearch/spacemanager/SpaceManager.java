package org.example.beamsearch.spacemanager;

import org.example.beamsearch.common.PlacedCuboid;
import org.example.beamsearch.common.Space;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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
        if (s == null) {
            return;
        }

        // SpaceManager.copy() 现在会深复制 Space 对象，不能再依赖
        // ArrayList.remove(Object) 的对象引用相等性；按矩形坐标删除，
        // 才能保证 State.deleteSpace() 在复制后的管理器中正常工作。
        for (int i = 0; i < spaceList.size(); i++) {
            Space current = spaceList.get(i);
            if (current.x1 == s.x1
                    && current.y1 == s.y1
                    && current.x2 == s.x2
                    && current.y2 == s.y2) {
                spaceList.remove(i);
                return;
            }
        }
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
        // 不能只复制 ArrayList。Space 会缓存 cornerDistance，后续阶段还会
        // 长期保存快照，因此这里复制每个矩形对象，避免不同搜索分支共享可变对象。
        ArrayList<Space> copiedSpaceList = new ArrayList<>(this.spaceList.size());
        for (Space space : this.spaceList) {
            copiedSpaceList.add(new Space(space.x1, space.y1, space.x2, space.y2));
        }
        return new SpaceManager(copiedSpaceList, spaceComparator);
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

    /**
     * 根据一张板材上已经放置的矩形重新计算几何剩余空间。
     *
     * <p>State.updateSpaces() 为了提高搜索速度，会过滤掉当前工件无法放入
     * 的空间。优先件排完后，这种过滤会让空间列表为空，普通件就无法继续
     * 使用真实剩余区域。因此跨阶段传递状态时使用本方法，不依赖搜索阶段
     * 的过滤结果。</p>
     *
     * @param boardLength 板材长度，使用 BeamSearch 内部整数单位
     * @param boardWidth  板材宽度，使用 BeamSearch 内部整数单位
     * @param placedCuboids 已放置的矩形
     * @return 不与已放置矩形重叠的剩余矩形列表
     */
    public static ArrayList<Space> calculateResidualSpaces(int boardLength,
                                                            int boardWidth,
                                                            List<PlacedCuboid> placedCuboids) {
        ArrayList<Space> remainingSpaces = new ArrayList<>();
        if (boardLength <= 0 || boardWidth <= 0) {
            return remainingSpaces;
        }

        remainingSpaces.add(new Space(0, 0, boardLength, boardWidth));
        if (placedCuboids == null) {
            return remainingSpaces;
        }

        for (PlacedCuboid placedCuboid : placedCuboids) {
            if (placedCuboid == null || placedCuboid.length <= 0 || placedCuboid.width <= 0) {
                continue;
            }

            Space taken = new Space(
                    placedCuboid.x,
                    placedCuboid.y,
                    placedCuboid.x + placedCuboid.length,
                    placedCuboid.y + placedCuboid.width);

            ArrayList<Space> nextSpaces = new ArrayList<>();
            for (Space space : remainingSpaces) {
                if (!space.intersectTest(taken)) {
                    nextSpaces.add(space);
                    continue;
                }

                Space intersection = space.intersect(taken);
                space.cut(intersection, nextSpaces);
            }

            // 删除被其他剩余空间完全包含的矩形，保持与搜索阶段一致的
            // 非支配空间表示，避免后续候选数量无意义地增长。
            remainingSpaces = deleteContained(nextSpaces);
        }

        return deleteContained(remainingSpaces);
    }
}

