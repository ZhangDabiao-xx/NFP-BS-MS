package org.example.nfp;

import java.util.Arrays;
import java.util.List;

public class QuickNFPCheck {
    public static void main(String[] args) throws Exception {
        List<Point> a = Arrays.asList(
                new Point(0, 0), new Point(4, 0), new Point(4, 4), new Point(0, 4));
        List<Point> b = Arrays.asList(
                new Point(0, 0), new Point(2, 0), new Point(2, 2), new Point(0, 2));

        NFPResult result = NFPComputer.computeNFP(a, b);
        System.out.printf("outerVertices=%d%n", result.outerNFP.size());
        System.out.printf("outerArea=%.4f%n", Geometry.polygonAreaAbs(result.outerNFP));
        for (Point point : result.outerNFP) {
            System.out.printf("(%.2f,%.2f)%n", point.x, point.y);
        }
        System.out.printf("innerVertices=%d%n", result.innerNFP.size());
        System.out.printf("innerArea=%.4f%n", Geometry.polygonAreaAbs(result.innerNFP));
    }
}