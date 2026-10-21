# 矩形排样下界（独立模块）

该模块本身不调用 NFP、BeamSearch、全局重排或 Agent，可独立计算和验证。
目前仅 `RectanglePackingApplication` 的纯矩形流程显式使用本模块；混合排样不启用下界停止。
下界保证的是板材数量的必要下限，不保证与真实最优板数或文献最强下界相等。

## 调用方式

```java
Item[] items = {
    new Item("A", 6, 4), new Item("B", 6, 4), new Item("C", 6, 4),
    new Item("D", 6, 4), new Item("E", 6, 4)
};
int width = Item.scale(10);
int height = Item.scale(10);
int fixed = RectangleLowerBoundSolver.solve(width, height, items, false);   // 3
int rotated = RectangleLowerBoundSolver.solve(width, height, items, true); // 2
var detail = RectangleLowerBoundSolver.solveDetailed(width, height, items, false, true);
```

- `false`：所有物品保持输入方向。
- `true`：允许 0°/90°；单件的 `rotatable=false` 仍禁止该件旋转。
- 每个 `Item` 代表一个物理工件，重复件需重复传入。CSV 适配器会按 `Qty` 展开。
- `new Item(id, w, h)` 和 `Item.scale` 将输入尺寸精确乘以 10。最多支持一位小数，超出精度、
  非正数、非有限数或整数范围会报错，避免静默截断。已有整数尺寸请用 `Item.fromScaledUnits`。
- 求解器的板材尺寸必须与 `Item.w/h` 使用相同整数单位，禁止再次缩放已经转换的尺寸。
- 无可行方向的工件抛出 `IllegalArgumentException`，不会把不可行案例伪装成一个有限下界。
- 空工件数组返回 0。输入不被修改。内部对尺寸去除共同公约数，面积和求和采用精确整数运算，
  超过 `long` 范围时使用 `BigInteger`，不使用浮点 epsilon 判定整数下界。

`solve` 合并面积下界、DFF 下界及适用时的 BM_3。`solveDetailed(..., includeCcm1=true)`
额外计算较耗时的固定方向 CCM_1。BM_3、CCM_1 的旧三参数接口保持固定方向语义；
`CCM_LowerBound_Solver.LB_CCM_2(W,H,items,allowRotation)` 也可独立调用。
当任何工件仍有两个可行方向时，不计算固定方向的 BM_3/CCM_1，对应明细值为 null。

## 纯矩形入口接入

`RectanglePackingApplication.ALLOW_ROTATION` 同时写入工件旋转标志并传给纯矩形专用调用。
每个 TXT 案例在初始排样之前计算一次下界；同尺寸重复件按案例内数量完整计入。
初始板数等于下界时不进入重排；重排提交完整新解并减少板数后再次判断，等于下界立即退出。
未达到下界时继续使用原有时间预算和连续无减板轮数停止条件。

下界只通过方法参数传递，没有静态开关或跨案例缓存。`LoadingTestRun.runWithImprove`、
不带旋转参数的兼容方法和混合排样调用均不启用下界；因此先运行矩形再运行混合案例也不会泄漏该设置。
仅显式四参数 `runRectangleWithImprove(..., allowRotation)` 使用完整案例的下界。

矩形日志和 `total.txt` 记录下界与停止原因，JSON 报告增加 `rectangleLowerBound`，包含
`scope=whole_rectangle_case`、旋转条件、分项下界、初始/最终板数、差距、计算耗时及是否达到下界。
计算时间不扣减优化预算，但计入矩形总排样时间。混合报告保持原有字段。

提前退出后仍完成工件绑定、未排数量计算、几何合法性检查和板材剩余空间快照重建。
存在被加载器过滤的工件、旋转设置不一致、非法布局或下界大于可行板数时拒绝宣告达到下界。

## 旋转下界的有效性

对一对有效的双可行变换 `f,g`，令每件物品的系数为其所有可行方向中变换面积的最小值：

```text
a_i = min { f(w) * g(h) : (w,h) 是该件允许且能装入板材的方向 }
LB(f,g) = ceil(sum_i a_i / (f(W) * g(H)))
```

任意实际布局中，每件选定方向的变换面积不少于 `a_i`；每张板的变换面积不超过
`f(W)*g(H)`。因此上述值不超过任意可行布局的板数。对多组变换取最大值仍然有效。
不允许旋转时方向集合退化为一个元素。

实现包含恒等变换和 CCM 的三类 DFF/DDFF。数据相关 DDFF 的定义域保留每个物理工件所有
可行方向的尺寸及重数；每个实际布局所选维度的子集都包含于该多重集合，因此依然适用。
DDFF 的基数背包只使用 `k <= x <= C/2` 的尺寸，排序前缀和配合二分查询。
零分母候选被跳过。奇数容量的中点不会用向下取整近似。

公式参考：Côté、Haouari、Iori，*A Primal Decomposition Algorithm for the Two-dimensional
Bin Packing Problem*，第 5 节（CCM DFF 及固定方向变换面积下界）：
https://arxiv.org/pdf/1909.06835 。旋转方向逐件取最小值是本实现明确采用的安全扩展。
本实现对大参数范围进行确定性采样；采样只影响下界强度，不改变每个候选的有效性。

## 独立 CLASSData 验证

```powershell
mvn exec:java '-Dexec.mainClass=org.example.beamsearch.lb.validation.ClassDataLowerBoundApplication' '-Dexec.args=data/CLASSData data/lbValidation/run1 data/CLASSDataResult_NOROTATION6 data/CLASSDataResult_2 --ccm1'
```

前两个参数是输入根目录、新的验证输出目录。后两个可选参数是现有不旋转/旋转排样结果根目录；
最后的可选 `--ccm1` 启用固定方向 CCM_1。只传输入/输出两个参数时不读取已有排样结果。
本程序只计算下界并读取已有布局，不启动或修改任何排样。

输出：

- `cases.csv`：每个案例的两种模式、分项下界、合并下界、重新验证的可行上界、耗时。
- `summary.json`：按物品数汇总下界、有效上界数量、相等/超过上界次数和用户提供的文献汇总值。

用户提供图中不旋转 LB 为 515/975/1440/1955/2322，总计 7208；旋转 LB 为
481/926/1379/1871/2235，总计 6892。每行是该物品数量下 100 个案例的板数总和。
注意不旋转五行的实际加总为 7207，与图中 Total=7208 相差 1。报告中的
`referenceLowerBoundSum` 为分组加总，`referencePrintedTotal` 保留图中总计，
`referenceRowsMatchPrintedTotal` 标识两者是否一致。
仅在使用同一标准数据集、方向规则和汇总范围时具有可比性。文献 LB 不是逐例最优值或可行上界；
不应以“汇总未超过文献 LB”单独证明程序正确，也不应要求不同下界算法必然达到同一汇总值。

## 测试

```powershell
mvn test
mvn test '-Dtest=LowerBoundSolverTest,ClassDataLowerBoundApplicationTest'
```

测试包括奇数容量/溢出反例、方向限制、强制旋转、数量、精度及非法输入；穷举一维 DFF 可行性；
与独立网格穷举和子集分板动态规划比较 200 个小实例在两种方向规则下的精确最优值；
检查 60 个已知单板可行的奇数非正方形切分实例；批量验证器还会拒绝重叠布局作为可行上界。

## 2026-10-20 验证记录

使用 `data/CLASSData` 全部 500 个案例，每种方向规则独立计算 500 次。
不旋转可行布局来自 `data/CLASSDataResult_NOROTATION6`，旋转布局来自
`data/CLASSDataResult_2`；1000 个布局均通过重新检查身份、数量、原始尺寸、旋转、边界和重叠。

| 物品数（每组100例） | 不旋转（含CCM_1） | 图中不旋转LB | 旋转 | 图中旋转LB |
|---|---:|---:|---:|---:|
| 20 | 511 | 515 | 481 | 481 |
| 40 | 964 | 975 | 925 | 926 |
| 60 | 1434 | 1440 | 1377 | 1379 |
| 80 | 1945 | 1955 | 1871 | 1871 |
| 100 | 2318 | 2322 | 2235 | 2235 |
| 分组之和 | 7172 | 7207 | 6889 | 6892 |

图中不旋转 Total 另写作 7208，保留为原图的加总差异。以上是下界强度的比较，
没有将图中分组值当作逐例最优值。含 CCM_1 时，440 个不旋转案例、401 个旋转案例的下界
与已验证可行板数相等，从而证明这些具体案例的板数最优；所有案例均无 LB 超过可行上界。

默认快速组合不含 CCM_1，不旋转总和 7167（435 个案例达可行上界），500 例计算合计约
4.20 秒；旋转总和 6889，500 例约 1.92 秒。启用 CCM_1 后，不旋转 500 例合计约
197.78 秒。耗时为本机该次测量，仅累计下界计算，未含读取和布局验证。

详细产物：`data/lbValidation/20261020/{cases.csv,summary.json}`（含 CCM_1）及
`data/lbValidation/20261020-fast/{cases.csv,summary.json}`（默认组合）。所有默认组合的分项
与增强组合逐例一致，CCM_1 仅在 5 个案例上增强了最终下界。产物位于独立数据目录，未覆盖
原案例或已有排样结果。Maven 回归测试通过：23 项中 22 项通过、1 项跳过；其中新加的
下界与批量验证测试共 9 项，全部通过。

## 纯矩形接入验证记录（2026-10-20）

全项目 `mvn test` 通过：31 项中 30 项通过、1 项跳过。新增集成检查覆盖：统一旋转条件、
案例下界只计算一次、优化前达界跳过、减板后达界退出、未达下界继续搜索、零预算保持原停止条件、
快照重建、完整性和几何校验、同进程通用入口不继承矩形开关，以及混合旋转规则的优先/普通件排样。

实测输入为 `data/CLASSData/01/cl_01_020_01.txt` 和 `cl_01_020_02.txt`，各自运行旋转与
不旋转模式，使用原有 600 秒优化预算；通过 `RectanglePackingApplication.run` 执行。
输出使用独立目录 `data/rectangleLowerBoundIntegration/20261020/{fixed,rotation}/01/<case>/`，
没有覆盖历史排样。`integration-summary.json` 汇总本次四个结果。

| 模式 | 案例 | 下界 | 初始板数 | 最终板数 | 停止原因 |
|---|---|---:|---:|---:|---|
| 不旋转 | cl_01_020_01 | 8 | 8 | 8 | lower_bound_reached_before_optimization |
| 不旋转 | cl_01_020_02 | 5 | 6 | 5 | lower_bound_reached_after_reduction |
| 可旋转 | cl_01_020_01 | 7 | 8 | 7 | lower_bound_reached_after_reduction |
| 可旋转 | cl_01_020_02 | 5 | 5 | 5 | lower_bound_reached_before_optimization |

四次运行均完整放置 20 件工件，几何与板材快照校验通过，各案例日志中的下界计算记录恰好一次。
