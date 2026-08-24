package org.example.beamsearch.lb.entity;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public class Item {
    public String id;
    public int w, h, s;

    public Item(String id, double w, double h) {
        this.id = id;
        this.w = (int) (w * 10);
        this.h = (int) (h * 10);
        this.s = this.w * this.h;
    }

    public static Comparator<Item> itemComparatorByIncreaseW = Comparator.comparingInt(o -> o.w);
    public static Comparator<Item> itemComparatorByIncreaseH = Comparator.comparingInt(o -> o.h);
    public static Comparator<Item> itemComparatorByIncreaseS = Comparator.comparingInt(o -> o.s);

    public static Item[] getItems(File workpieceF) throws Exception {
        BufferedReader br = new BufferedReader(new FileReader(workpieceF));
        String dataStr;
        dataStr = br.readLine();  // 跳过表头
        dataStr = br.readLine();  // 第一行数据
        List<Item> itemList = new ArrayList<>();
        while (dataStr != null){
            List<String> listData = Arrays.asList(dataStr.split(","));
            // CSV 列顺序: BatchNo,UPI,Qty,Color,Length,Width,IsSpecial,Rotatable
            // 取 Length(4) 和 Width(5) 作为 w, h
            double w = Double.parseDouble(listData.get(4));
            double h = Double.parseDouble(listData.get(5));
            itemList.add(new Item("1", w, h));
            dataStr = br.readLine();
        }
        br.close();
        Item[] items = new Item[itemList.size()];
        for (int i = 0; i < items.length; i++) {
            items[i] = itemList.get(i);
        }
        return items;
    }
}
