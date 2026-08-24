package org.example.beamsearch.common;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.nio.file.Path;
import java.nio.file.Paths;

public class ProblemLoader {
    private BufferedReader br;
    public double maxOptimizeTime;

    private HashMap<String, Container> containers;

    public void loadContainer(String containerInPath) throws IOException {
        containers = new HashMap<>();

        BufferedReader conR = new BufferedReader(new FileReader(containerInPath));
        String line = conR.readLine();
        line = conR.readLine();
        while(line != null) {
            List<String> lineData = Arrays.asList(line.split(","));
            String color = lineData.get(0);
            double length =Double.parseDouble(lineData.get(1));
            double width =Double.parseDouble(lineData.get(2));
            int orientId =Integer.parseInt(lineData.get(3));


            Container con = new Container(color,length,width,orientId);
            containers.put(color, con);

            line = conR.readLine();
        }
        conR.close();
    }

    public ProblemLoader(String workPieceInPath) throws IOException {
        br = new BufferedReader(new FileReader(workPieceInPath));

        this.maxOptimizeTime = 600;
    }

    public ArrayList<Instance> LoadInstancesFromCsv(boolean considerColor, String outPath) throws IOException {
        ArrayList<Instance> instances = new ArrayList<Instance>();
        Instance instance = new Instance();
        ArrayList<Box> unOptimizeBox =  instance.loadFromCsvFile(br, containers);
        br.close();
        if (considerColor) {
            Arrays.sort(instance.boxes, new Comparator<Box>() {
                @Override
                public int compare(Box b1, Box b2) {
                    return b1.color.compareTo(b2.color);
                }
            });
            ArrayList<Box> boxs = new ArrayList<>();
            for (int i = 0; i < instance.boxes.length; i++) {
                if (i > 0 && !instance.boxes[i].color.equals(instance.boxes[i - 1].color)) {
                    Container container = containers.get(instance.boxes[i - 1].color);
                    Instance newInst = new Instance(boxs, container);
                    instances.add(newInst);
                    boxs.clear();
                }
                boxs.add(instance.boxes[i]);
            }

            if (boxs.size() > 0) {
                Container container = containers.get(boxs.get(0).color);
                Instance newInst = new Instance(boxs, container);
                instances.add(newInst);
            }

        } else {
            instances.add(instance);
            double totalArea = 0.0;
            for (int i = 0; i < instance.boxes.length; i++) {
                totalArea += (instance.boxes[i].volume) / 100.0;
            }
            System.out.println(instance.boxes.length + "\t" + totalArea);
        }

        PrintWriter pw;
        Path path = Paths.get(outPath);
        String result = path.resolve("notOptimize.csv").toString().replace("\\", "/");
        pw = new PrintWriter(new BufferedWriter(new OutputStreamWriter(new FileOutputStream(result), StandardCharsets.UTF_8)));
        pw.println("Batch, UPI, Reason");
        for(Box box : unOptimizeBox) {
            String reason = "Unknown error";
            if(box.volume == 0.0){
                reason = "Size out of limit" ;
            }
            pw.println(box.name+","+box.id+","+ reason);
            pw.flush();
        }
        pw.close();
        return instances;
    }

}

