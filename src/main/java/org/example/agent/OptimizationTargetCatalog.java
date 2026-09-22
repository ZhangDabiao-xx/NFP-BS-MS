package org.example.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 提供给 Decision Agent 的受限真实代码地图。
 *
 * <p>地图只列出已确认存在、且适合性能优化的算法入口。模型只能从这里选择目标，
 * 因而不会把自然语言中的“普通重排”误写成不存在的 Java 类名。</p>
 */
final class OptimizationTargetCatalog {

    private final List<Target> targets;

    private OptimizationTargetCatalog(List<Target> targets) {
        this.targets = targets;
    }

    /** 创建并验证当前项目中的真实优化入口。 */
    static OptimizationTargetCatalog create(Path projectRoot) {
        Path root = projectRoot.toAbsolutePath().normalize();
        List<Target> targets = List.of(
                new Target(
                        "src/main/java/org/example/beamsearch/application/GlobalRepackOptimizer.java",
                        "GlobalRepackOptimizer.optimize",
                        "全局重排阶段的封装入口：传入既定时间上限、固定随机种子并调用 "
                                + "BeamSearch.ImproveByRepack；不应通过这里直接缩短时间预算。"),
                new Target(
                        "src/main/java/org/example/beamsearch/algo/BeamSearch.java",
                        "BeamSearch.ImproveByRepack",
                        "普通件和优先件共用的实际重排循环。现有代码已经包含连续 "
                                + "MAX_NO_IMPROVEMENT_SWEEPS 无减板扫描后的停止保护；"
                                + "若监控显示 noImprovementSweeps 为 0，不应重复添加同一保护。"));

        for (Target target : targets) {
            Path sourceFile = root.resolve(target.file()).normalize();
            if (!Files.isRegularFile(sourceFile)) {
                throw new IllegalStateException("优化代码地图中的文件不存在: " + sourceFile);
            }
        }
        return new OptimizationTargetCatalog(targets);
    }

    /** 返回可安全发送给模型的路径、方法名和已确认事实，不包含完整源码。 */
    JsonArray asJson() {
        JsonArray result = new JsonArray();
        for (Target target : targets) {
            JsonObject item = new JsonObject();
            item.addProperty("file", target.file());
            item.addProperty("method", target.method());
            item.addProperty("knownFacts", target.knownFacts());
            result.add(item);
        }
        return result;
    }

    /**
     * 本地确认模型选中的文件和方法确实来自代码地图，阻止虚构路径进入代码修改阶段。
     */
    void validateDecision(JsonObject decision) {
        if (!"ready_for_implementation".equals(stringValue(decision, "status"))) {
            return;
        }

        JsonObject plan = decision.getAsJsonObject("selectedPlan");
        JsonArray files = plan == null ? null : plan.getAsJsonArray("targetFiles");
        JsonArray methods = plan == null ? null : plan.getAsJsonArray("targetMethods");
        if (files == null || files.isEmpty() || methods == null || methods.isEmpty()) {
            throw new IllegalArgumentException("可实施方案必须从真实代码地图中选择文件和方法。");
        }

        for (JsonElement file : files) {
            if (!file.isJsonPrimitive() || !containsFile(file.getAsString())) {
                throw new IllegalArgumentException("Decision Agent 返回了不在代码地图中的文件: " + file);
            }
        }
        for (JsonElement method : methods) {
            if (!method.isJsonPrimitive() || !containsMethod(method.getAsString())) {
                throw new IllegalArgumentException("Decision Agent 返回了不在代码地图中的方法: " + method);
            }
        }

        for (Target target : targets) {
            boolean fileSelected = contains(files, target.file());
            boolean methodSelected = contains(methods, target.method());
            if (fileSelected != methodSelected) {
                throw new IllegalArgumentException("targetFiles 与 targetMethods 必须选择同一真实代码入口。");
            }
        }
    }

    private boolean containsFile(String file) {
        return targets.stream().anyMatch(target -> target.file().equals(file));
    }

    private boolean containsMethod(String method) {
        return targets.stream().anyMatch(target -> target.method().equals(method));
    }

    private boolean contains(JsonArray values, String expected) {
        for (JsonElement value : values) {
            if (value.isJsonPrimitive() && expected.equals(value.getAsString())) {
                return true;
            }
        }
        return false;
    }

    private String stringValue(JsonObject object, String field) {
        JsonElement value = object.get(field);
        return value == null || value.isJsonNull() || !value.isJsonPrimitive()
                ? "" : value.getAsString();
    }

    private record Target(String file, String method, String knownFacts) {
    }
}
