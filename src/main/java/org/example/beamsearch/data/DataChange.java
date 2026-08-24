package org.example.beamsearch.data;

import java.io.*;

/**
 * ClassName: DataChange
 * Package: data
 * Description:
 *
 * @Author:
 * @Create: 2025/1/11 - 18:06
 * @Version:
 */
public class DataChange {
    public static void main(String[] args) throws IOException {
        BufferedReader br = new BufferedReader(new FileReader("C:\\Users\\13160\\Desktop\\test\\OPP_2D_20_1s_3I_4W.ins2D"));
        BufferedWriter bw = new BufferedWriter(new FileWriter("C:\\Users\\13160\\Desktop\\test\\workpiece.csv"));
        bw.write("BatchNo,UPI,Qty,Color,Length,Width,IsSpecial");
        bw.newLine();
        bw.flush();
        br.readLine();
        br.readLine();
        String dataList;
        while ((dataList = br.readLine()) != null){
            String[] datas = dataList.split(" ");
            int index = Integer.parseInt(datas[0]);
            int length = Integer.parseInt(datas[1]);
            int width = Integer.parseInt(datas[2]);
            bw.write("2025.01.11," + index + ",1," + "VA_18_034B," + length + "," + width + ",0");
            bw.newLine();
            bw.flush();
        }
        bw.close();
    }
}
