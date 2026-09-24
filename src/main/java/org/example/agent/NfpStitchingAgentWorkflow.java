package org.example.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.example.nfp.BatchBlockStitcher;
import org.example.nfp.NfpStitchingPolicy;
import org.example.nfp.PolygonItem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * NFP 策略 Agent 的受限入口。
 *
 * <p>该入口只选择 {@link NfpStitchingPolicy}，实际 NFP 拼接仍由 BatchBlockStitcher
 * 执行。API 暂时不可用时自动退回平衡策略，保证完整求解流程不会因分析服务中断。</p>
 */
public final class NfpStitchingAgentWorkflow {

    private static final String REPORT_FILE_NAME = "nfp-stitch-report.json";
    private static final String DECISION_FILE_NAME = "nfp-stitch-decision.json";
    private static final String RESULT_FILE_NAME = "nfp-stitch-result.json";
    private static final String MEMORY_FILE_NAME = "nfp-stitch-memory.json";
    private static final int MAX_RECENT_EXPERIENCES = 4;

    private NfpStitchingAgentWorkflow() {
    }

    /** NFP 拼接前根据当前案例特征选择一个受限策略，并保存报告与决策。 */
    public static Selection selectPolicy(Path caseJsonFile, Path llmDirectory) throws IOException {
        List<PolygonItem> items = BatchBlockStitcher.readItems(caseJsonFile);
        JsonObject report = buildReport(caseJsonFile, items);
        AgentJsonFiles.writeObject(llmDirectory.resolve(REPORT_FILE_NAME), report);

        JsonObject history = readExperienceContext(llmDirectory);
        JsonObject decision;
        boolean agentUsed = true;
        try {
            DeepSeekClient client = new DeepSeekClient(DeepSeekConfig.fromCode());
            decision = new NfpStitchingDecisionAgent(client).decide(report, history);
        } catch (Exception exception) {
            // NFP 本身不依赖 LLM；不可用时用可审计的默认策略继续完成求解。
            agentUsed = false;
            decision = fallbackDecision(exception);
        }

        AgentJsonFiles.writeObject(llmDirectory.resolve(DECISION_FILE_NAME), decision);
        return new Selection(NfpStitchingPolicy.fromId(
                decision.get("selectedPolicyId").getAsString()), decision, report, agentUsed);
    }

    /**
     * 排样结束后保存策略与最终指标，形成 NFP Agent 的跨运行经验。
     *
     * <p>经验仅用于之后的策略选择，不会修改已完成的 NFP 或排样结果。</p>
     */
    public static void recordPackingOutcome(Path llmDirectory, Selection selection) throws IOException {
        Path reportPath = llmDirectory.resolve("run-report.json");
        if (!Files.isRegularFile(reportPath)) {
            return;
        }
        JsonObject runReport = AgentJsonFiles.readObject(reportPath, "NFP 策略后的排样报告");
        JsonObject memory = readMemory(llmDirectory);
        JsonArray entries = memory.getAsJsonArray("experiences");

        JsonObject entry = new JsonObject();
        entry.addProperty("sequence", nextSequence(entries));
        entry.addProperty("policyId", selection.policy().id());
        entry.addProperty("agentUsed", selection.agentUsed());
        entry.add("packingQuality", packingQuality(runReport));
        entry.addProperty("comparisonWithPrevious", compareWithPrevious(entries, runReport));
        entries.add(entry);
        while (entries.size() > 20) {
            entries.remove(0);
        }
        AgentJsonFiles.writeObject(llmDirectory.resolve(MEMORY_FILE_NAME), memory);

        JsonObject result = new JsonObject();
        result.addProperty("selectedPolicyId", selection.policy().id());
        result.addProperty("agentUsed", selection.agentUsed());
        result.add("packingQuality", packingQuality(runReport));
        result.addProperty("comparisonWithPrevious", entry.get("comparisonWithPrevious").getAsString());
        AgentJsonFiles.writeObject(llmDirectory.resolve(RESULT_FILE_NAME), result);
    }

    /** 保存给调用方的策略、原始决策和案例特征报告。 */
    public record Selection(NfpStitchingPolicy policy,
                            JsonObject decision,
                            JsonObject report,
                            boolean agentUsed) {
    }

    private static JsonObject buildReport(Path caseJsonFile, List<PolygonItem> items) {
        int smallItemCount = 0;
        int smallRectangleCount = 0;
        int irregularItemCount = 0;
        int lowFillIrregularCount = 0;
        double totalArea = 0.0;
        double smallRectangleArea = 0.0;
        double estimatedCavityArea = 0.0;
        List<Double> smallRectangleAreas = new ArrayList<>();

        for (PolygonItem item : items) {
            totalArea += item.area;
            if (item.smallItem) {
                smallItemCount++;
            }
            if (item.smallItem && item.rectangular) {
                smallRectangleCount++;
                smallRectangleArea += item.area;
                smallRectangleAreas.add(item.area);
            }
            if (!item.rectangular) {
                irregularItemCount++;
                estimatedCavityArea += Math.max(0.0, item.boxArea - item.area);
                if (!item.smallItem && item.fillRate < 0.90) {
                    lowFillIrregularCount++;
                }
            }
        }

        JsonObject profile = new JsonObject();
        profile.addProperty("itemCount", items.size());
        profile.addProperty("smallItemCount", smallItemCount);
        profile.addProperty("smallRectangleCount", smallRectangleCount);
        profile.addProperty("smallRectangleAreaFraction", ratio(smallRectangleArea, totalArea));
        profile.addProperty("smallRectangleMedianArea", median(smallRectangleAreas));
        profile.addProperty("irregularItemCount", irregularItemCount);
        profile.addProperty("lowFillIrregularRootCount", lowFillIrregularCount);
        profile.addProperty("estimatedIrregularCavityArea", estimatedCavityArea);
        profile.addProperty("smallRectangleSupply", smallRectangleSupply(items.size(), smallRectangleCount));

        JsonObject report = new JsonObject();
        report.addProperty("schemaVersion", "1.0");
        report.addProperty("caseName", stripExtension(caseJsonFile.getFileName().toString()));
        report.add("itemProfile", profile);
        report.add("availablePolicies", policiesJson());
        report.addProperty("interpretation", "smallRectangleSupply=scarce 表示小矩形较少，"
                + "其机会成本可能高于一次局部凹腔填充收益。");
        return report;
    }

    private static JsonArray policiesJson() {
        JsonArray values = new JsonArray();
        for (NfpStitchingPolicy policy : NfpStitchingPolicy.values()) {
            JsonObject value = new JsonObject();
            value.addProperty("policyId", policy.id());
            value.addProperty("description", policy.description());
            value.addProperty("preservesSmallRectanglesForPacking",
                    policy.preserveSmallRectanglesForPacking());
            values.add(value);
        }
        return values;
    }

    private static JsonObject fallbackDecision(Exception exception) {
        JsonObject decision = new JsonObject();
        decision.addProperty("status", "no_change");
        decision.addProperty("selectedPolicyId", NfpStitchingPolicy.BALANCED.id());
        decision.addProperty("reason", "NFP 策略 Agent 暂不可用，使用平衡策略继续确定性求解。"
                + "原因: " + limited(exception.getMessage()));
        decision.addProperty("expectedTradeoff", "不改变已有的小件预留规则。");
        decision.addProperty("humanDecision", "none");
        return decision;
    }

    private static JsonObject readExperienceContext(Path llmDirectory) throws IOException {
        JsonArray values = readMemory(llmDirectory).getAsJsonArray("experiences");
        JsonArray recent = new JsonArray();
        int start = Math.max(0, values.size() - MAX_RECENT_EXPERIENCES);
        for (int index = start; index < values.size(); index++) {
            recent.add(values.get(index).deepCopy());
        }
        JsonObject context = new JsonObject();
        context.add("recentExperiences", recent);
        context.addProperty("note", "历史指标仅用于策略比较，不能覆盖当前案例特征或几何约束。");
        return context;
    }

    private static JsonObject readMemory(Path llmDirectory) throws IOException {
        Path path = llmDirectory.resolve(MEMORY_FILE_NAME);
        if (Files.isRegularFile(path)) {
            JsonObject memory = AgentJsonFiles.readObject(path, "NFP 拼接经验文件");
            if (memory.has("experiences") && memory.get("experiences").isJsonArray()) {
                return memory;
            }
        }
        JsonObject memory = new JsonObject();
        memory.addProperty("schemaVersion", "1.0");
        memory.add("experiences", new JsonArray());
        return memory;
    }

    private static JsonObject packingQuality(JsonObject runReport) {
        JsonObject quality = new JsonObject();
        copyNumber(runReport, quality, "priorityBoardCount");
        copyNumber(runReport, quality, "ordinaryBoardCount");
        copyNumber(runReport, quality, "boardCount");
        copyNumber(runReport, quality, "actualAverageUtilization");
        return quality;
    }

    private static String compareWithPrevious(JsonArray entries, JsonObject runReport) {
        if (entries.isEmpty()) {
            return "first_measurement";
        }
        JsonObject previous = entries.get(entries.size() - 1).getAsJsonObject()
                .getAsJsonObject("packingQuality");
        JsonObject current = packingQuality(runReport);
        int comparison = compareQuality(previous, current);
        return comparison < 0 ? "better" : comparison > 0 ? "worse" : "equal";
    }

    /** 返回 -1 表示当前质量更好，比较顺序与排样验证器一致。 */
    private static int compareQuality(JsonObject previous, JsonObject current) {
        String[] lowerIsBetter = {"priorityBoardCount", "ordinaryBoardCount", "boardCount"};
        for (String name : lowerIsBetter) {
            int comparison = Double.compare(number(current, name), number(previous, name));
            if (comparison != 0) {
                return comparison;
            }
        }
        return -Double.compare(number(current, "actualAverageUtilization"),
                number(previous, "actualAverageUtilization"));
    }

    private static int nextSequence(JsonArray entries) {
        return entries.isEmpty() ? 1 : entries.get(entries.size() - 1).getAsJsonObject()
                .get("sequence").getAsInt() + 1;
    }

    private static String smallRectangleSupply(int itemCount, int smallRectangleCount) {
        if (smallRectangleCount <= 3 || smallRectangleCount * 10 < Math.max(1, itemCount)) {
            return "scarce";
        }
        if (smallRectangleCount * 4 < itemCount) {
            return "limited";
        }
        return "abundant";
    }

    private static double median(List<Double> values) {
        if (values.isEmpty()) {
            return 0.0;
        }
        values.sort(Comparator.naturalOrder());
        int middle = values.size() / 2;
        return values.size() % 2 == 0
                ? (values.get(middle - 1) + values.get(middle)) / 2.0 : values.get(middle);
    }

    private static double ratio(double numerator, double denominator) {
        return denominator <= 0.0 ? 0.0 : numerator / denominator;
    }

    private static void copyNumber(JsonObject source, JsonObject target, String name) {
        target.addProperty(name, source.has(name) ? source.get(name).getAsDouble() : 0.0);
    }

    private static double number(JsonObject object, String name) {
        return object.has(name) ? object.get(name).getAsDouble() : 0.0;
    }

    private static String stripExtension(String name) {
        int index = name.lastIndexOf('.');
        return index > 0 ? name.substring(0, index) : name;
    }

    private static String limited(String value) {
        String text = value == null ? "未提供异常信息。" : value.replaceAll("\\s+", " ").strip();
        return text.length() <= 180 ? text : text.substring(0, 180) + "...";
    }
}
