package org.example.beamsearch.lb.solver;


import org.example.beamsearch.lb.entity.Item;
import org.example.beamsearch.lb.util.CommonUtil;
import org.example.beamsearch.lb.util.DffUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class CCM_LowerBound_Solver {

    private static int LB_CCM_1(int C, int halfBinC, int step, List<Integer> cs) {
        int res = 0;
        for (int u = 0; u <= 2; u++) {
            for (int k = 1; k <= halfBinC; k += step) {
                double tempRes = 0;
                for (int c : cs) {
                    tempRes += ((double) DffUtil.dff(u, k, C, c, cs) / DffUtil.dff(u, k, C, C, cs));
                }
                res = Math.max(res, CommonUtil.ceilToInt(tempRes));
            }
        }
        return res;
    }

    public static int LB_CCM_1(int W, int H, Item[] items) {
        int lb = 1;
        int S = W * H;
        int halfBinW = W / 2;
        int halfBinH = H / 2;
        int halfBinS = S / 2;

        List<Item> itemListSortByS = new ArrayList<>(Arrays.asList(items));
        itemListSortByS.sort(Item.itemComparatorByIncreaseS);

        List<Item> itemListSortByW = new ArrayList<>(Arrays.asList(items));
        itemListSortByW.sort(Item.itemComparatorByIncreaseW);

        List<Item> itemListSortByH = new ArrayList<>(Arrays.asList(items));
        itemListSortByH.sort(Item.itemComparatorByIncreaseH);


        int cnt = Math.max(1, (int) Math.sqrt(1000000d / items.length));
        int stepW = Math.max(1, halfBinW / cnt);
        int stepH = Math.max(1, halfBinH / cnt);
        int stepS = Math.max(1, halfBinS / cnt);

        for (int p = 1; p <= halfBinH; p += stepH) {
            for (int q = 1; q <= halfBinW; q += stepW) {
                int largesSize = 0;
                List<Integer> list = new ArrayList<>();
                List<Integer> smalls = new ArrayList<>();
                for (Item item : itemListSortByS) {

                    if (item.w > W - q && item.h > H - p) {
                        largesSize++;
                    } else if (item.w >= q && item.h > H - p) {
                        list.add(item.s);
                    } else if (item.w > W - q && item.h >= p) {
                        list.add(item.s);
                    } else if (item.w >= q && item.h >= p) {
                        list.add(item.s);
                        smalls.add(item.s);
                    }
                }

                List<Integer> talls = new ArrayList<>();
                for (Item item : itemListSortByW) {
                    if (item.w > W - q && item.h > H - p) {

                    } else if (item.w >= q && item.h > H - p) {
                        talls.add(item.w);
                    }
                }

                List<Integer> wides = new ArrayList<>();
                for (Item item : itemListSortByH) {
                    if (item.w > W - q && item.h > H - p) {

                    } else if (item.w >= q && item.h > H - p) {

                    } else if (item.w > W - q && item.h >= p) {
                        wides.add(item.h);
                    }
                }

                int res1 = LB_CCM_1(S, halfBinS, stepS, list);

                int res2 = LB_CCM_1(H, halfBinH, stepH, wides);

                res2 += LB_CCM_1(W, halfBinW, stepW, talls);

                lb = Math.max(lb, largesSize + Math.max(res1, res2));

            }
        }
        return lb;
    }

    public static int LB_CCM_2(int W, int H, Item[] items) {
        int lb = 1;
        int n = items.length;
        int[] ws = new int[n];
        for (int i = 0; i < items.length; i++) {
            ws[i] = items[i].w;
        }
        Arrays.sort(ws);
        int[] hs = new int[n];
        for (int i = 0; i < items.length; i++) {
            hs[i] = items[i].h;
        }
        Arrays.sort(hs);
        int halfBinW = W / 2;
        int halfBinH = H / 2;

        int cnt = Math.max(1, (int) Math.sqrt(1000000d / n));
        int stepX = Math.max(1, halfBinW / cnt);
        int stepY = Math.max(1, halfBinH / cnt);
        for (int u = 0; u <= 2; u++) {
            for (int v = 0; v <= 2; v++) {
                for (int k = 1; k <= halfBinW; k += stepX) {
                    for (int l = 1; l <= halfBinH; l += stepY) {
                        double fracLb = 0d;
                        for (int j = 0; j < n; j++) {

                            fracLb += (DffUtil.dff(u, k, W, items[j].w, ws) * DffUtil.dff(v, l, H, items[j].h, hs));
                        }
                        fracLb /= (DffUtil.dff(u, k, W, W, ws) * DffUtil.dff(v, l, H, H, hs));
                        lb = Math.max(lb, CommonUtil.ceilToInt(fracLb));
                    }
                }
            }
        }
        return lb;
    }
}
