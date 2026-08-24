package org.example.beamsearch.lb.util;


import java.util.List;

public class CommonUtil {

    public static int ceilToInt(double x) {
        return (int) Math.ceil(x - 1e-10);
    }

    public static int solve1dCkp(int C, int[] xs) {
        int res = 0;
        for (int x : xs) {
            C -= x;
            if (C >= 0) {
                res++;
            } else {
                break;
            }
        }
        return res;
    }

    public static int solve1dCkp(int C, List<Integer> xs) {
        int res = 0;
        for (int x : xs) {
            C -= x;
            if (C >= 0) {
                res++;
            } else {
                break;
            }
        }
        return res;
    }

}