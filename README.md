# NFP-BS-MS

本项目求解带优先级约束的矩形与不规则图形排样问题。运行路径分为互不干扰的基准链与 Agent 优化链：基准结果始终可重复，Agent 结果始终单独留存并接受验证。

## 代码主干

```text
BaselinePackingApplication
  -> PackingPipeline
     -> BatchBlockStitcher        # 不规则工件 NFP 拼接、矩形化
     -> NFPToBeamSearchBridge     # 生成 material.csv / workpiece
     -> PriorityFirstPacker       # 优先件先排、普通件填充、全局重排

AgentOptimizationApplication
  -> nfp     -> NfpAgentExperiment -> NfpAgentOptimizer
  -> packing -> PackingAgentWorkflow
```

`org.example.nfp` 只负责几何与 NFP 组块；`org.example.beamsearch` 只负责矩形优先级排样；`org.example.agent` 只包含明确触发的优化实验与代码优化闭环。两个可视化类独立读取结果，不参与求解主干。

## 基准启动入口

`org.example.application.BaselinePackingApplication` 是 Maven 默认入口，也是唯一的基准入口。它不会读取或调用任何 Agent。

```powershell
mvn exec:java
mvn exec:java '-Dexec.args="data/inputData2/Cabinet1.json"'
mvn exec:java '-Dexec.args="data/inputData2/Cabinet1.json data/custom-nfp data/custom-packing"'
```

输入可以是一个 JSON 案例或案例目录。传入三个参数时，第二、第三个参数分别是 NFP 与排样输出目录；桥接文件写入 `<packingOutputDirectory>/material/<案例名>/`。只传输入时，结果仍自动写入输入目录同级的 `BA06_NFP` 和 `BA06_Packing`。

## 混合排样收敛统计

在 `BaselinePackingApplication` 顶部集中设置三个参数：

- `ENABLE_CONVERGENCE_STATISTICS`：默认 `false`，只有设为 `true` 才采集和写出统计文件；
- `CONVERGENCE_SAMPLE_INTERVAL_MS`：默认 `1000` 毫秒，在局部尝试结束后按实际时间采样；
- `CONVERGENCE_OUTPUT_DIRECTORY`：默认 `data/packingConvergence`，可修改为其他目录。

启用后，每个案例输出 `<统计目录>/<案例名>/priority_optimization.csv` 和
`ordinary_optimization.csv`。两阶段分别从零计时，不包含 NFP、初始排样和普通件插入。
除常规采样外，初始点、历史最高评分刷新、成功减板和最终点始终保留，因此行间隔不一定相等。
没有对应优化阶段时，该 CSV 写入 `skipped` 行，评分留空。纯矩形入口及原有无配置调用不启用此功能。

绘图使用 `elapsed_ms` 作横坐标，`score_u_squared`（当前评分）或
`best_score_u_squared`（历史最好评分）作纵坐标。历史最好评分适合画阶梯图；当前评分为稀疏采样值，不能视为全部提交的精确轨迹。评分为整体矩形利用率的平方：
`U = S / ((Nb - 1 + min(Uj)) * W * H)`，`score = U * U`。
面积统一采用传入优化阶段的矩形面积（NFP 拼接块的外接矩形），单位为平方毫米；
分子 `S` 为该阶段固定的全部输入矩形面积，各板 `Uj` 同样使用矩形占用面积，最低利用率板作为计算上的末板。
CSV 总面积列名为 `total_rectangle_area`；时间与评分列名保持不变，现有按评分列绘图的程序可继续读取。
最终排样报告中的真实多边形利用率统计保持不变，因此其数值不必与收敛 CSV 相同。
旧 CSV 仍为原面积口径，不能通过改列名转换；需要重新求解才能生成矩形口径的收敛数据。
搜索中仅缓存数值，整个案例结束后统一写盘；采样步长很小时会增加统计开销。

## Agent 启动入口

`org.example.agent.AgentOptimizationApplication` 是唯一的 Agent 启动入口，必须显式指定模式：

```powershell
# NFP Agent：对案例或案例目录生成独立的受限候选实验
mvn exec:java -Dexec.mainClass=org.example.agent.AgentOptimizationApplication `
  '-Dexec.args="nfp data/inputData2/Cabinet1.json 4"'

# Packing Agent：基于已有的 baseline 排样结果执行“决策 -> 修改 -> 编译/重跑验证”闭环
mvn exec:java -Dexec.mainClass=org.example.agent.AgentOptimizationApplication `
  '-Dexec.args="packing data/BA06_Packing/Cabinet1"'
```

NFP Agent 的实验产物写在 `BA06_Packing/<案例名>/llm/nfp-agent/`，不会覆写 baseline 的 NFP 或排样文件。Packing Agent 需要先有 baseline 运行生成的 `llm/run-report.json` 与 `material/<案例名>/` 桥接输入；每次候选修改都会写入独立 `llm/iteration-XX/` 目录，验证失败会恢复源码。

调用远程模型前，在 `src/main/java/org/example/agent/DeepSeekConfig.java` 中配置 API Key、URL 与模型。不要提交真实密钥。

## CLASSData 纯矩形入口

`org.example.application.RectanglePackingApplication` 独立处理 CLASSData 的纯矩形案例，不经过 NFP，也不改变不规则图形 baseline。入口顶部的三个常量用于配置：

- `INPUT_PATH`：CLASSData 根目录、编号子目录，或一个 `.txt` 案例；
- `OUTPUT_DIRECTORY`：结果根目录；
- `ALLOW_ROTATION`：所有矩形共用的 90° 旋转开关。

每个 `.txt` 的每行是两个矩形尺寸。程序从相同编号的 `material_XX.csv` 读取板材规格，将全部工件标记为优先件后复用现有优先件排样和全局重排优化。

该入口按完整案例计算一次矩形板数下界，并使用同一个 `ALLOW_ROTATION` 参数决定下界的旋转条件。
初始解达到下界时跳过全局重排；成功减板后达到下界时立即结束后续重排。默认使用面积、DFF 和适用时的 BM 下界，
不启用较耗时的 CCM_1。下界计算耗时单独记录，不扣减重排预算。`optimized.log`、`total.txt` 和
`llm/run-report.json` 会记录下界、旋转条件及停止原因。混合排样与 Agent 原有调用路径不启用此功能。
下界模块与独立验证方法见 [lb 说明](src/main/java/org/example/beamsearch/lb/README.md)。

```powershell
mvn exec:java -Dexec.mainClass=org.example.application.RectanglePackingApplication
```

## 可视化入口

```powershell
# NFP 拼接结果
mvn exec:java -Dexec.mainClass=org.example.nfp.visual.OutputDataVisualizer `
  '-Dexec.args="data/BA06_NFP data/NFPPicture1"'

# 排样结果
mvn exec:java -Dexec.mainClass=org.example.visualizer.PackingResultVisualizer `
  '-Dexec.args="data/BA06_Packing data/visualResult1"'
```

## 验证

```powershell
mvn clean test
mvn package
```
