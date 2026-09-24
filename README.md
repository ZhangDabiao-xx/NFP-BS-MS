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
也不会替代现有的排样流程。完整排样每处理完一个案例，都会在该案例的
结果目录的 `llm/run-report.json`，其中包含板数、利用率、未排件数、阶段耗时与
边界/重叠等校验结果。例如：`data/Result10_evaluate/Others1/llm/run-report.json`。

请先打开 `src/main/java/org/example/agent/DeepSeekConfig.java`，填写：

```java
private static final String API_KEY = "请在这里输入你的 DeepSeek API Key";
private static final String API_URL = "https://api.deepseek.com/chat/completions";
private static final String MODEL = "deepseek-flash";
private static final long TIMEOUT_SECONDS = 120L;
```

首次验证可直接运行 `org.example.agent.DeepSeekAnalysisApplication`，不需要输入
模型参数或 PowerShell 参数；它会分析示例报告并写入 `tmp/agent-analysis.json`。

要分析真实案例时，将 `DeepSeekAnalysisApplication` 的第一个可选运行参数设为
对应案例的 `run-report.json`；第二个可选参数是分析结果的保存位置。例如：

```powershell
mvn exec:java `
  -Dexec.mainClass=org.example.agent.DeepSeekAnalysisApplication `
  -Dexec.args="examples/agent/run-report-example.json tmp/agent-analysis.json"
```

`examples/agent/run-report-example.json` 是一个可直接复制的输入示例；模型参数始终
在 `DeepSeekConfig.java` 的代码常量中配置，不通过命令行传入。真实 API Key 不要提交到公开仓库。

## 5. 优化方案生成 Agent（第二步接入）

`org.example.agent.DeepSeekOptimizationProposalApplication` 读取同一案例的
`llm/run-report.json` 与 `llm/analysis.json`，生成 `llm/optimization-proposal.json`。它只提出
待审查的修改方案，不会自动修改排样代码。

默认案例路径已写在该类顶部，直接从 IDE 运行即可。切换案例时，只修改
`RUN_REPORT_PATH`、`ANALYSIS_PATH` 和 `PROPOSAL_PATH` 三个常量。下一阶段将由
可行性审查 Agent 结合具体代码检查这些方案，再决定是否允许生成修改补丁。

## 6. 精简优化工作流主入口（当前推荐）

新的主入口是 `org.example.agent.DeepSeekOptimizationWorkflowApplication`。它将原先的
“分析 Agent、方案生成 Agent、可行性审查 Agent”收敛为一次决策调用，并在同一入口完成
代码修改与非 LLM 验证：

`run-report.json → 代码地图 → Decision Agent → Code Modification Agent → 结构化写入 → 编译 → 候选排样重跑 → 验证`

直接从 IDE 运行即可，不需要命令行参数。切换案例时只修改该类顶部的
`CASE_OUTPUT_DIRECTORY`。运行前必须已经完成该案例的一次排样，使
`run-report.json`、`material.csv` 和 `workpiece` 已存在。

主入口会先从当前项目源码构建确定性的 `code-map.json`。代码地图包含模块概览、调用链、二维排样
数据约束、允许优化的 `targetId` 和预登记的 `editPointId`。Decision Agent 只接收运行报告、代码地图
和真实目标方法源码，并且只能选择一个 `targetId`；它不再输出文件路径或方法名。

每个案例还会维护 `llm/optimization-memory.json`。该文件不是模型聊天记录，而是该案例已经结束的
实验台账：每轮的策略目标、实际修改点、验证结论，以及 `Sp → So → N → 利用率 → 耗时` 的指标变化。
主入口首次升级到该机制时会从已有的 `iteration-XX` 目录自动回填；以后每次启动都会向 Decision Agent
提供最近六条精简经验。因此，已被验证为“可运行但无收益”的相同策略目标和修改点不会被直接重复尝试；
若仍选择同一策略，模型必须提出不同修改点或不同、可验证的假设。

当决策为 `ready_for_implementation` 时，代码修改 Agent 会收到该目标方法、数据约束和可修改区域，
只能输出 `editPointId + replacementText`。程序根据 `editPointId` 的唯一开始/结束锚点定位真实源码，
因此模型不需要复写大段原文，也不会因 Windows 换行符或缩进差异导致匹配失败。修改前仍会自动备份。

代码地图面向排样策略层，当前登记的可修改目标包括：空闲空间排序、可行组块排序、拆板重组时的
组块排序，以及 `BeamSearch.ImproveByRepack` 中的待拆板选择、候选生成/遍历顺序和无改进停止条件。
每个目标会附带真实方法源码及相关类型源码。代码地图同时明确：`Instance` 只有 `length`、`width`，
`Box.volume` 是二维矩形面积；模型不能再把它误认为三维体积。

候选结果不再只要求“不退化”。确定性验证器按 `Sp → So → N → 平均利用率 → 总排样时间` 的
字典序判断收益：候选必须先通过可行性保护，并且必须在前四项解质量上严格提升；只有解质量完全
相同时，至少缩短 3% 总排样时间才会被保留。没有收益的候选源码会自动恢复，而不是成为下一轮基线。

`decision.json` 的 `status` 表示下一步：

- `ready_for_implementation`：存在唯一的 `selectedPlan`，可交给后续代码修改阶段。
- `collect_evidence`：报告证据不足，应先补充计时或统计数据。
- `no_change`：当前结果没有值得修改的方向。

每次主入口启动会更新案例目录的 `llm/code-map.json`。每次代码修改会在新的
`llm/iteration-XX` 目录写入：

- `decision.json`：本轮唯一的已选方案；
- `code-changes.json`：代码 Agent 输出的 `editPointId` 与替换内容；
- `modification-report.md`：面向阅读的“本次修改了哪些代码、为何修改”说明；
- `backups/`：修改前的原始源码；
- `validation.json`：Java 编译、候选案例运行和基线指标比较结果；
- `candidate-output/`：候选代码产生的独立排样结果，不覆盖原案例结果。
- `result-analysis.json`：验证通过后，对候选排样结果进行的新一轮分析。

验证不调用 LLM：它会编译全部主源码，使用已有 bridge 输入重跑当前案例，并保护既有的
可行性校验、未排工件数、板数和真实平均利用率。验证失败会自动恢复本轮源码，并把错误原因
反馈给代码修改 Agent；若候选安全但没有收益，则重新调用 Decision Agent 选择不同的策略目标。
整个入口最多尝试三次候选代码。成功时，修改会保留，汇总状态写入 `llm/final-result.json`。
成功后的候选运行报告会成为下一次启动主入口时的分析基线，因此不需要手动移动 JSON 文件。
旧的 `DeepSeekProposalReviewLoopApplication` 仅保留为早期多 Agent 实验入口，不再作为推荐流程。

## 7. 全局重排轻量监控

每次排样完成后，`RunReportWriter` 都会确保在当前案例结果目录创建 `llm` 子目录，并写入
`llm/run-report.json`。报告的 `repackMonitoring.priority` 和 `repackMonitoring.ordinary`
分别记录两个全局重排阶段的：时间上限、实际耗时、开始/结束板数、外层循环次数、候选板材数、
组合候选数、实际重排次数、成功改进次数、减板次数和停止原因。

监控只增加计数与计时，不改变 Beam Search、随机种子、约束条件或十分钟重排预算。重新运行
对应案例的排样程序后，新的字段会自动出现在该案例的 `run-report.json` 中。

## 8. NFP 拼接策略 Agent

完整入口 `IntegratedPackingApplication` 现在会在每个案例开始 NFP 拼接前，先在该案例的
`llm` 目录生成 `nfp-stitch-report.json`。报告统计小矩形数量与面积占比、异形工件数量、
低填充率异形根工件数量和估算凹腔面积。NFP Decision Agent 只能在以下确定性策略中选择，
不能生成拼接代码或放宽几何约束：

- `balanced`：平衡关键凹腔收益和小件机会成本；
- `cavity_first`：优先保护关键凹腔及其小件资源；
- `preserve_scarce_small_rectangles`：不让第一阶段消耗新增的小矩形，保留它们给后续排样填缝；
- `compact_blocks_first`：优先紧凑、高填充率的组合块。

决策保存为 `nfp-stitch-decision.json`；排样结束后，实际的 `Sp、So、N、利用率` 会写入
`nfp-stitch-result.json` 和可持续读取的 `nfp-stitch-memory.json`。下一次处理同一案例时，
Agent 会读取最近四次策略结果，避免无证据地重复已经退化的策略。若 DeepSeek 暂时不可用，
程序会记录原因并安全回退到 `balanced`，不会跳过 NFP 几何校验或中断完整求解。
