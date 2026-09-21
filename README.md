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

## 4. DeepSeek 结果分析 Agent（第一步接入）

当前版本只接入“读取运行报告并分析结果”的 Agent，不会自动修改求解代码，
也不会替代现有的排样流程。

请先打开 `src/main/java/org/example/agent/DeepSeekConfig.java`，填写：

```java
private static final String API_KEY = "请在这里输入你的 DeepSeek API Key";
private static final String API_URL = "https://api.deepseek.com/chat/completions";
private static final String MODEL = "deepseek-flash";
private static final long TIMEOUT_SECONDS = 120L;
```

然后运行：

```powershell
mvn exec:java `
  -Dexec.mainClass=org.example.agent.DeepSeekAnalysisApplication `
  -Dexec.args="examples/agent/run-report-example.json tmp/agent-analysis.json"
```

`examples/agent/run-report-example.json` 是一个可直接复制的输入示例；实际使用时，
将其替换为后续运行器生成的 JSON 报告。第二个参数可选；不填写时，
分析结果直接输出到终端。真实 API Key 不要提交到公开仓库。
