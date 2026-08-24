package org.example.nfp;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * NFP 核心计算器，对应 C++ nfp.cpp
 * 提供外NFP（Minkowski Sum）和内NFP（多边形交集）的计算
 */
public class NFPComputer {

    // ========== 凸多边形 Minkowski Sum（边排序合并法） ==========

    /**
     * 凸多边形的 Minkowski Sum
     * 两个凸多边形都必须为CCW方向
     * 从各自最底部最左顶点开始，按边方向角归并
     *
     * @param A 固定凸多边形 (CCW)
     * @param B 移动凸多边形 (CCW)
     * @return Minkowski Sum 结果多边形
     */
    public static List<Point> convexMinkowskiSum(List<Point> A, List<Point> B) {
        if (A.isEmpty() || B.isEmpty()) return new ArrayList<>();

        List<Point> pointSums = new ArrayList<>(A.size() * B.size());
        for (Point pointA : A) {
            for (Point pointB : B) {
                pointSums.add(pointA.add(pointB));
            }
        }

        List<Point> result = Geometry.convexHull(pointSums);
        result = Geometry.removeCollinearPoints(result);
        Geometry.ensureCCW(result);
        return result;
    }

    // ========== 外NFP计算（基于Clipper的Minkowski Sum） ==========

    /**
     * 计算外NFP的内部实现
     * 外NFP = Minkowski Sum(A, -B)，其中B以B[0]为参考点
     * 使用 ClipperBridge 进行条带合并，自动处理凹多边形的自交
     *
     * @param A 固定多边形
     * @param B 移动多边形
     * @param outerBoundary [输出] 外NFP边界
     * @param holes         [输出] 外NFP中的孔洞
     */
    private static void computeOuterNFP_Clipper(List<Point> A, List<Point> B,
                                                 List<Point> outerBoundary,
                                                 List<List<Point>> holes) throws IOException {
        outerBoundary.clear();
        holes.clear();

        // 预处理：确保CCW、去共线点
        List<Point> polyA = new ArrayList<>(A);
        List<Point> polyB = new ArrayList<>(B);
        Geometry.ensureCCW(polyA);
        Geometry.ensureCCW(polyB);
        polyA = Geometry.removeCollinearPoints(polyA);
        polyB = Geometry.removeCollinearPoints(polyB);

        if (polyA.size() < 3 || polyB.size() < 3) return;

        // 以 B[0] 为参考点：平移B使参考点在原点
        Point refB = polyB.get(0);
        for (int i = 0; i < polyB.size(); i++) {
            polyB.set(i, polyB.get(i).sub(refB));
        }

        // 对B取反并确保CCW
        List<Point> negB = Geometry.negatePolygon(polyB);
        Geometry.ensureCCW(negB);

        if (Geometry.isConvex(polyA) && Geometry.isConvex(negB)) {
            List<Point> outer = convexMinkowskiSum(polyA, negB);
            if (!outer.isEmpty()) {
                outerBoundary.addAll(outer);
            }
            return;
        }

        // 转为整数坐标
        List<long[]> clipA = ClipperBridge.toIntPath(polyA);
        List<long[]> clipNegB = ClipperBridge.toIntPath(negB);

        // 确保整数路径为CCW（正方向）
        if (!ClipperBridge.intPathOrientation(clipA)) {
            ClipperBridge.reverseIntPath(clipA);
        }
        if (!ClipperBridge.intPathOrientation(clipNegB)) {
            ClipperBridge.reverseIntPath(clipNegB);
        }

        // 计算 Minkowski Sum
        List<List<long[]>> solution = ClipperBridge.minkowskiSum(clipNegB, clipA);

        if (solution.isEmpty()) return;

        // 找面积最大的作为外边界
        double maxArea = 0;
        int outerIdx = -1;
        for (int i = 0; i < solution.size(); i++) {
            double area = ClipperBridge.intPathArea(solution.get(i));
            if (area > maxArea) {
                maxArea = area;
                outerIdx = i;
            }
        }

        // 如果没有正面积的，取绝对面积最大的
        if (outerIdx < 0) {
            maxArea = 0;
            for (int i = 0; i < solution.size(); i++) {
                double area = Math.abs(ClipperBridge.intPathArea(solution.get(i)));
                if (area > maxArea) {
                    maxArea = area;
                    outerIdx = i;
                }
            }
        }

        // 转回浮点坐标
        if (outerIdx >= 0) {
            List<Point> outer = ClipperBridge.fromIntPath(solution.get(outerIdx));
            Geometry.ensureCCW(outer);
            outerBoundary.addAll(outer);
        }

        // 其余组件作为孔洞
        for (int i = 0; i < solution.size(); i++) {
            if (i == outerIdx) continue;
            List<Point> hole = ClipperBridge.fromIntPath(solution.get(i));
            if (hole.size() >= 3) {
                holes.add(hole);
            }
        }
    }

    /**
     * 计算外NFP（公开接口）
     * 适用于凸和凹多边形
     *
     * @param A 固定多边形
     * @param B 移动多边形
     * @return 外NFP边界多边形
     */
    public static List<Point> computeOuterNFP(List<Point> A, List<Point> B) throws IOException {
        List<Point> outer = new ArrayList<>();
        List<List<Point>> holes = new ArrayList<>();
        computeOuterNFP_Clipper(A, B, outer, holes);
        return outer;
    }

    /**
     * Orbiting方法计算外NFP（当前实现委托给Clipper方法）
     */
    public static List<Point> computeOuterNFP_Orbiting(List<Point> stationary, List<Point> orbiting) throws IOException {
        return computeOuterNFP(stationary, orbiting);
    }

    // ========== 内NFP计算（多边形交集法） ==========

    /**
     * 计算内NFP的全部闭环。
     *
     * 算法：innerNFP(A, B) = ∩ (A - b_i)，表示 B 参考点在 A 内部可行移动的区域。
     * 用途：凹多边形或带内部缺陷的形状可能产生多个可行域闭环；
     * 旧逻辑只取最大面积闭环，会让 PolygonStitcher 看不到其他凹槽/内部边界上的候选点。
     *
     * @param container 容器多边形 (CCW)
     * @param piece     被放置的零件多边形 (CCW)
     * @return 内NFP全部闭环，按面积降序排列
     */
    public static List<List<Point>> computeInnerNFPLoops(List<Point> container, List<Point> piece) {
        List<Point> A = new ArrayList<>(container);
        List<Point> B = new ArrayList<>(piece);
        Geometry.ensureCCW(A);
        Geometry.ensureCCW(B);
        A = Geometry.removeCollinearPoints(A);
        B = Geometry.removeCollinearPoints(B);

        if (A.size() < 3 || B.size() < 3) return new ArrayList<>();

        Point refB = B.get(0);
        for (int i = 0; i < B.size(); i++) {
            B.set(i, B.get(i).sub(refB));
        }

        List<List<long[]>> shiftedContainers = new ArrayList<>();
        for (Point pieceVertex : B) {
            List<Point> shiftedContainer = new ArrayList<>(A.size());
            for (Point containerVertex : A) {
                shiftedContainer.add(containerVertex.sub(pieceVertex));
            }

            List<long[]> shiftedPath = ClipperBridge.toIntPath(shiftedContainer);
            if (!ClipperBridge.intPathOrientation(shiftedPath)) {
                ClipperBridge.reverseIntPath(shiftedPath);
            }
            shiftedContainers.add(shiftedPath);
        }

        List<List<long[]>> solution = ClipperBridge.intersectionAllPolygons(shiftedContainers);
        if (solution.isEmpty()) return new ArrayList<>();

        List<List<Point>> loops = new ArrayList<>();
        for (List<long[]> path : solution) {
            List<Point> loop = ClipperBridge.fromIntPath(path);
            loop = Geometry.removeCollinearPoints(loop);
            if (loop.size() < 3 || Geometry.polygonAreaAbs(loop) <= Geometry.EPS) {
                continue;
            }
            Geometry.ensureCCW(loop);
            loops.add(loop);
        }

        // 面积大的内部可行域通常更稳定，排序后旧接口也可以直接复用第一个作为 innerNFP。
        loops.sort((first, second) -> Double.compare(
                Geometry.polygonAreaAbs(second),
                Geometry.polygonAreaAbs(first)));
        return loops;
    }

    public static List<Point> computeInnerNFP(List<Point> container, List<Point> piece) {
        List<List<Point>> loops = computeInnerNFPLoops(container, piece);
        if (loops.isEmpty()) {
            return new ArrayList<>();
        }
        return loops.get(0);
    }

    // ========== 完整NFP计算接口 ==========

    /**
     * 计算A和B的NFP（外NFP和/或内NFP）
     *
     * @param A            固定多边形
     * @param B            移动多边形
     * @param computeOuter 是否计算外NFP
     * @param computeInner 是否计算内NFP
     * @return NFPResult 包含外NFP、孔洞、内NFP及计算时间
     */
    public static NFPResult computeNFP(List<Point> A, List<Point> B,
                                        boolean computeOuter, boolean computeInner) {
        NFPResult result = new NFPResult();
        long t0 = System.nanoTime();

        try {
            //判断是否计算外NFP
            if (computeOuter) {
                //建立外NFP
                List<Point> outerBoundary = new ArrayList<>();
                //建立孔洞
                List<List<Point>> holes = new ArrayList<>();
                computeOuterNFP_Clipper(A, B, outerBoundary, holes);
                result.outerNFP = outerBoundary;
                result.holes = holes;
            }
            // 判断是否计算内NFP。
            // innerLoops 保留所有内部可行域闭环；innerNFP 仍保留最大闭环，兼容旧调用方。
            if (computeInner) {
                result.innerLoops = computeInnerNFPLoops(A, B);
                result.innerNFP = result.innerLoops.isEmpty() ? new ArrayList<>() : result.innerLoops.get(0);
            }
            result.success = true;
        } catch (Exception e) {
            result.success = false;
            result.errorMsg = e.getMessage();
        }

        long t1 = System.nanoTime();
        result.computeTimeMs = (t1 - t0) / 1_000_000.0;
        return result;
    }

    /** 重载：默认同时计算外NFP和内NFP */
    public static NFPResult computeNFP(List<Point> A, List<Point> B) {
        return computeNFP(A, B, true, true);
    }

    // ========== 辅助函数 ==========

    /**
     * 找到B在A外侧的起始触碰位置（底部对齐）
     *
     * @param stationary 固定多边形
     * @param orbiting   移动多边形
     * @return B参考点的起始位置
     */
    public static Point findStartPosition(List<Point> stationary, List<Point> orbiting) {
        int sIdx = Geometry.bottomLeftVertex(stationary);
        int oIdx = Geometry.topRightVertex(orbiting);
        return stationary.get(sIdx).sub(orbiting.get(oIdx));
    }

    /**
     * 找到B在A内部的起始位置（底部左对齐）
     *
     * @param container 容器多边形
     * @param piece     零件多边形
     * @return B参考点的内部起始位置
     */
    public static Point findInnerStartPosition(List<Point> container, List<Point> piece) {
        int cIdx = Geometry.bottomLeftVertex(container);
        int pIdx = Geometry.bottomLeftVertex(piece);
        return container.get(cIdx).sub(piece.get(pIdx));
    }

    /**
     * 检测多边形沿方向滑动时的最大可滑动距离比例
     * 当前为简化实现，返回1.0（全距离可滑动）
     *
     * @param stationary  固定多边形
     * @param orbiting    移动多边形
     * @param translation 滑动方向向量
     * @param currentPos  当前位置
     * @return 可滑动比例 [0, 1]
     */
    public static double slideDistance(List<Point> stationary, List<Point> orbiting,
                                        Point translation, Point currentPos) {
        return 1.0;
    }
}