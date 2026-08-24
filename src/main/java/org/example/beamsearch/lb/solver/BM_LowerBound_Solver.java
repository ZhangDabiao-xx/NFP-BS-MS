package org.example.beamsearch.lb.solver;

import org.example.beamsearch.lb.entity.Item;
import org.example.beamsearch.lb.util.CommonUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class BM_LowerBound_Solver {

    private static int M(int W, List<Integer> ws) {
        int res = 0;
        for (Integer w : ws) {
            W -= w;
            if (W >= 0) {
                res++;
            } else {
                break;
            }
        }
        return res;
    }

    private static int mPiePie(Item item, List<Integer> ws, List<Integer> hs, int W, int H) {
        return M(W - item.w, ws) * M(H, hs)
                + M(W, ws) * M(H - item.h, hs)
                - M(W - item.w, ws) * M(H - item.h, hs);
    }

    public static int LB_BM_3(int W, int H, Item[] items) {
        int lb = 1;
        int n = items.length;
        int halfBinW = W / 2;
        int halfBinH = H / 2;

        int cnt = Math.max(1, (int) Math.sqrt(1000000d / n));
        int stepX = Math.max(1, halfBinW / cnt);
        int stepY = Math.max(1, halfBinH / cnt);
        for (int p = 1; p <= halfBinH; p += stepX) {
            for (int q = 1; q <= halfBinW; q += stepY) {
                List<Integer> ws = new ArrayList<>(n);
                List<Integer> hs = new ArrayList<>(n);
                for (Item item : items) {
                    if (item.w > W - q && item.h > H - p) {

                    } else if (item.w > halfBinW && item.h > halfBinH) {

                    } else if (item.h >= p && item.w >= q) {
                        ws.add(item.w);
                        hs.add(item.h);
                    }
                }
                Collections.sort(ws);
                Collections.sort(hs);
                int tempLb = 0;
                int temp = 0;
                for (Item item : items) {

                    if (item.w > W - q && item.h > H - p) {
                        tempLb++;
                    } else if (item.w > halfBinW && item.h > halfBinH) {
                        tempLb++;
                        temp -= mPiePie(item, ws, hs, W, H);
                    } else if (item.h >= p && item.w >= q) {
                        temp++;
                    }
                }
                lb = Math.max(lb, tempLb
                        + Math.max(0, CommonUtil.ceilToInt((double) temp / (M(W, ws) * M(H, hs)))));
            }
        }
        return lb;
    }
}