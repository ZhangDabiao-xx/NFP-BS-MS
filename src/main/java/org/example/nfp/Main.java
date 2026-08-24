package org.example.nfp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * NFP 计算测试入口，对应 C++ main.cpp
 */
public class Main {

    // ========== 辅助输出 ==========

    private static void printPolygon(String name, List<Point> poly) {
        System.out.printf("%s (%d vertices):%n  ", name, poly.size());
        for (int i = 0; i < poly.size(); i++) {
            System.out.printf("(%.2f,%.2f)", poly.get(i).x, poly.get(i).y);
            if (i < poly.size() - 1)
                System.out.print(" -> ");
            if ((i + 1) % 8 == 0 && i < poly.size() - 1)
                System.out.print("\n  ");
        }
        System.out.println();
    }

    private static void printNFPResult(NFPResult result) throws IOException{
        System.out.printf("  Compute Time: %.3f ms%n", result.computeTimeMs);
        PrintWriter pw = new PrintWriter("data\\NFPResult.txt");
        if (!result.outerNFP.isEmpty()) {
            System.out.printf("  Outer NFP vertices: %d%n", result.outerNFP.size());
            double area = Geometry.polygonAreaAbs(result.outerNFP);
            System.out.printf("  Outer NFP area: %.4f%n", area);

            List<Point> outerNFP = result.outerNFP;
            pw.print("outerNFP: ");
            for (Point p : outerNFP) {
                pw.printf("(%.2f,%.2f)", p.x, p.y);
                pw.print("->");
            }
            pw.println();
        }
        if (!result.holes.isEmpty()) {
            System.out.printf("  Outer NFP holes: %d%n", result.holes.size());
            for (int i = 0; i < result.holes.size(); i++) {
                double harea = Geometry.polygonAreaAbs(result.holes.get(i));
                System.out.printf("    Hole %d: %d vertices, area=%.4f%n",
                        i, result.holes.get(i).size(), harea);

                List<Point> holes = result.holes.get(i);
                pw.print("hole" + i + ": ");
                for (Point p : holes) {
                    pw.printf("(%.2f,%.2f)", p.x, p.y);
                    pw.print("->");
                }
                pw.println();
            }
        }
        if (!result.innerNFP.isEmpty()) {
            System.out.printf("  Inner NFP vertices: %d%n", result.innerNFP.size());
            double area = Geometry.polygonAreaAbs(result.innerNFP);
            System.out.printf("  Inner NFP area: %.4f%n", area);

            List<Point> innerNFP = result.innerNFP;
            pw.print("innerNFP: ");
            for (Point p : innerNFP) {
                pw.printf("(%.2f,%.2f)", p.x, p.y);
                pw.print("->");
            }
            pw.println();
        }
        if (!result.success) {
            System.out.printf("  ERROR: %s%n", result.errorMsg);
        }

        pw.close();
    }

    // ========== 多边形生成 ==========

    /**
     * 生成正多边形
     * 
     * @param n      边数
     * @param radius 外接圆半径
     */
    private static List<Point> generateRegularPolygon(int n, double radius) {
        List<Point> poly = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double angle = 2.0 * Math.PI * i / n - Math.PI / 2.0;
            poly.add(new Point(radius * Math.cos(angle), radius * Math.sin(angle)));
        }
        return poly;
    }

    /**
     * 生成星形多边形（凹多边形）
     * 
     * @param points 星角数
     * @param outerR 外圈半径
     * @param innerR 内圈半径
     */
    private static List<Point> generateStarPolygon(int points, double outerR, double innerR) {
        List<Point> poly = new ArrayList<>();
        for (int i = 0; i < points; i++) {
            double angle1 = 2.0 * Math.PI * i / points - Math.PI / 2.0;
            poly.add(new Point(outerR * Math.cos(angle1), outerR * Math.sin(angle1)));
            double angle2 = 2.0 * Math.PI * (i + 0.5) / points - Math.PI / 2.0;
            poly.add(new Point(innerR * Math.cos(angle2), innerR * Math.sin(angle2)));
        }
        return poly;
    }

    // ========== 测试用例 ==========

    private static void test1_ConvexSquares() throws IOException{
        System.out.println("========================================");
        System.out.println("Test 1: Two Convex Squares (Classic)");
        System.out.println("========================================");

        List<Point> A = Arrays.asList(
                new Point(0, 0), new Point(4, 0), new Point(4, 4), new Point(0, 4));
        List<Point> B = Arrays.asList(
                new Point(0, 0), new Point(2, 0), new Point(2, 2), new Point(0, 2));

        printPolygon("A", A);
        printPolygon("B", B);

        NFPResult result = NFPComputer.computeNFP(A, B);
        printNFPResult(result);
        System.out.println("  Expected Outer NFP area: 36 (6x6)");
        System.out.println();
    }

    private static void test2_ConvexTriangleSquare() throws IOException{
        System.out.println("========================================");
        System.out.println("Test 2: Triangle + Square");
        System.out.println("========================================");

        List<Point> A = Arrays.asList(
                new Point(0, 0), new Point(6, 0), new Point(3, 5));
        List<Point> B = Arrays.asList(
                new Point(0, 0), new Point(2, 0), new Point(2, 2), new Point(0, 2));

        printPolygon("A", A);
        printPolygon("B", B);

        NFPResult result = NFPComputer.computeNFP(A, B);
        printNFPResult(result);
        System.out.println();
    }

    private static void test3_ConvexPentagons() throws IOException{
        System.out.println("========================================");
        System.out.println("Test 3: Two Convex Pentagons");
        System.out.println("========================================");

        List<Point> A = generateRegularPolygon(5, 5.0);
        List<Point> B = generateRegularPolygon(5, 2.0);

        printPolygon("A", A);
        printPolygon("B", B);

        NFPResult result = NFPComputer.computeNFP(A, B);
        printNFPResult(result);
        System.out.println();
    }

    private static void test4_ConcaveLShape() throws IOException{
        System.out.println("========================================");
        System.out.println("Test 4: L-Shape (Concave) + Rectangle");
        System.out.println("========================================");

        List<Point> A = Arrays.asList(
                new Point(0, 0), new Point(6, 0), new Point(6, 2),
                new Point(2, 2), new Point(2, 6), new Point(0, 6));
        List<Point> B = Arrays.asList(
                new Point(0, 0), new Point(1, 0), new Point(1, 1), new Point(0, 1));

        printPolygon("A", A);
        printPolygon("B", B);

        NFPResult result = NFPComputer.computeNFP(A, B);
        printNFPResult(result);
        System.out.println();
    }

    private static void test5_StarPolygon() throws IOException{
        System.out.println("========================================");
        System.out.println("Test 5: Star (Concave) + Triangle");
        System.out.println("========================================");

        List<Point> A = generateStarPolygon(5, 5.0, 2.0);
        List<Point> B = Arrays.asList(
                new Point(0, 0), new Point(2, 0), new Point(1, 1.732));

        printPolygon("A", A);
        printPolygon("B", B);

        NFPResult result = NFPComputer.computeNFP(A, B);
        printNFPResult(result);
        System.out.println();
    }

    private static void test9_InnerNFP_SquareInSquare() throws IOException{
        System.out.println("========================================");
        System.out.println("Test 9: Inner NFP - Small Square in Large Square");
        System.out.println("========================================");

        List<Point> A = Arrays.asList(
                new Point(0, 0), new Point(10, 0), new Point(10, 10), new Point(0, 10));
        List<Point> B = Arrays.asList(
                new Point(0, 0), new Point(3, 0), new Point(3, 3), new Point(0, 3));

        printPolygon("Container A", A);
        printPolygon("Piece B", B);

        NFPResult result = NFPComputer.computeNFP(A, B, false, true);
        printNFPResult(result);
        System.out.println("  Expected Inner NFP: (0,0)-(7,0)-(7,7)-(0,7), area=49");
        System.out.println();
    }

    private static void test12_BurkeExample() throws IOException{
        System.out.println("========================================");
        System.out.println("Test 12: Burke et al. (2007) Figure Example");
        System.out.println("========================================");

        List<Point> A = Arrays.asList(
                new Point(0, 0), new Point(8, 0), new Point(8, 3),
                new Point(5, 3), new Point(5, 6), new Point(3, 6),
                new Point(3, 3), new Point(0, 3));
        List<Point> B = Arrays.asList(
                new Point(0, 0), new Point(3, 0), new Point(3, 2), new Point(0, 2));

        printPolygon("A (T-shape)", A);
        printPolygon("B (Rectangle)", B);

        NFPResult result = NFPComputer.computeNFP(A, B);
        printNFPResult(result);
        System.out.println();
    }

    private static void test13_CustomUShape() throws IOException{
        System.out.println("========================================");
        System.out.println("Test 13: U-Shape (Concave) + Small Rectangle");
        System.out.println("========================================");

        List<Point> A = Arrays.asList(
                new Point(0, 0), new Point(100, 0), new Point(100, 100),
                new Point(60, 100), new Point(60, 80), new Point(80, 80),
                new Point(80, 20), new Point(20, 20), new Point(20, 80),
                new Point(40, 80), new Point(40, 100), new Point(0, 100));
        List<Point> B = Arrays.asList(
                new Point(0, 0), new Point(30, 0), new Point(30, 30), new Point(0, 30));

        printPolygon("A (U-shape)", A);
        printPolygon("B (Small Rect)", B);

        NFPResult result = NFPComputer.computeNFP(A, B);
        printNFPResult(result);
        System.out.println();
    }

    // ========== 主程序 ==========

    public static void main(String[] args) throws IOException{
        System.out.println("===================================================");
        System.out.println("  No-Fit Polygon (NFP) Calculator - Java Test Suite");
        System.out.println("  Outer NFP (Minkowski Sum) + Inner NFP");
        System.out.println("===================================================");
        System.out.println();

        test1_ConvexSquares();
        /*test2_ConvexTriangleSquare();
        test3_ConvexPentagons();
        test4_ConcaveLShape();
        test5_StarPolygon();
        test9_InnerNFP_SquareInSquare();
        test12_BurkeExample();
        test13_CustomUShape();*/

        System.out.println("===================================================");
        System.out.println("  All tests completed!");
        System.out.println("===================================================");
    }
}