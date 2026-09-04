# NFP-BS-MS

项目只保留三个启动入口：一个完整求解入口和两个独立可视化入口。

## 1. 完整求解

`org.example.application.IntegratedPackingApplication` 依次完成 NFP 拼接、组块矩形化和优先级排样。

```powershell
mvn exec:java
```

默认案例目录是 `data/inputData`；需要处理其他目录或单个 JSON 时，只传入案例路径：

```powershell
mvn exec:java '-Dexec.args="F:\案例\Others1.json"'
```

程序会在案例目录的同级目录自动创建 `NFPJoint1` 和 `Result1`。例如，
`data/inputData/Others1.json` 的结果会写入 `data/NFPJoint1` 和 `data/Result1`；
中间的 `material.csv` 和 `workpiece` 写入 `Result1/bridge/<案例名>/`。

## 2. NFP 拼接可视化

`org.example.nfp.visual.OutputDataVisualizer` 独立把一个 NFP 文本结果文件或结果目录渲染为 PNG。

```powershell
mvn exec:java -Dexec.mainClass=org.example.nfp.visual.OutputDataVisualizer -Dexec.args="data/NFPJoint1 data/NFPPicture1"
```

## 3. 排样结果可视化

`org.example.visualizer.PackingResultVisualizer` 独立把排样结果渲染为 PNG。第三个参数可选，仅渲染指定案例。

```powershell
mvn exec:java -Dexec.mainClass=org.example.visualizer.PackingResultVisualizer -Dexec.args="data/Result1 data/visualResult1"
```
