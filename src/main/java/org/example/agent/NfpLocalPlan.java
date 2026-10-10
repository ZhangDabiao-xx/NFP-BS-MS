package org.example.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.example.nfp.PolygonItem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 局部破坏/重建的文档模型：未解锁块逐字保留，不从简化矩形反推原始几何。 */
final class NfpLocalPlan {
    record Part(String text, String id, List<String> members, double fillRate,
                double length, double width, boolean rotatable, boolean priority) { }
    private final List<Part> parts;

    private NfpLocalPlan(List<Part> parts) { this.parts = List.copyOf(parts); }

    /** 缓存/合并文档都必须覆盖输入的每个 ID 恰好一次；只比较面积总和会漏掉等面积重复件。 */
    void verifyCoverage(List<PolygonItem> expected) throws IOException {
        Set<String> found = new HashSet<>();
        for (Part part : parts) for (String member : part.members) {
            if (!found.add(member)) throw new IOException("NFP 文档重复工件: " + member);
        }
        Set<String> required = new HashSet<>(); expected.forEach(item -> required.add(item.id));
        if (required.size() != expected.size() || !found.equals(required)) throw new IOException("NFP 文档工件覆盖不一致");
    }

    static NfpLocalPlan read(Path file) throws IOException {
        List<Part> parts = new ArrayList<>();
        String[] sections = Files.readString(file).split("(?m)^block \\d+\\s*\\R");
        for (int i = 1; i < sections.length; i++) {
            String body = sections[i].strip();
            String id = "";
            double fill = 0, length = 0, width = 0;
            boolean priority = false, rotatable = false;
            List<String> members = new ArrayList<>();
            for (String raw : body.split("\\R")) {
                if (raw.startsWith("id=")) id = raw.substring(3);
                if (raw.startsWith("fillRate=")) fill = Double.parseDouble(raw.substring(9));
                if (raw.startsWith("BackFrontPriority=")) priority = Boolean.parseBoolean(raw.substring(18));
                if (raw.startsWith("rotate=")) {
                    for (var angle : JsonParser.parseString(raw.substring(7)).getAsJsonArray()) {
                        if (angle.getAsInt() == 90 || angle.getAsInt() == 270) rotatable = true;
                    }
                }
                if (raw.startsWith("combinedCoordinates=")) {
                    double minX = Double.POSITIVE_INFINITY, minY = minX;
                    double maxX = Double.NEGATIVE_INFINITY, maxY = maxX;
                    for (var point : JsonParser.parseString(raw.substring(20)).getAsJsonArray()) {
                        JsonArray xy = point.getAsJsonArray();
                        minX = Math.min(minX, xy.get(0).getAsDouble()); maxX = Math.max(maxX, xy.get(0).getAsDouble());
                        minY = Math.min(minY, xy.get(1).getAsDouble()); maxY = Math.max(maxY, xy.get(1).getAsDouble());
                    }
                    length = maxX - minX; width = maxY - minY;
                }
                if (raw.strip().startsWith("- id=")) members.add(raw.strip().substring(5));
            }
            if (id.isBlank() || members.isEmpty() || !(length > 0) || !(width > 0)) {
                throw new IOException("NFP 块缺少有效几何或成员: " + id);
            }
            parts.add(new Part(body, id, List.copyOf(members), fill, length, width, rotatable, priority));
        }
        if (parts.isEmpty()) throw new IOException("空 NFP 方案: " + file);
        return new NfpLocalPlan(parts);
    }

    /** 解锁约 20% 复合块和至多 8 个互补单件；全窗口至多 36 件，轮次轮换避免固定邻域。 */
    Set<String> unlock(int round, Set<String> weakBlockIds) {
        List<Part> composites = parts.stream().filter(part -> part.members.size() > 1)
                .sorted(Comparator.comparing((Part part) -> !weakBlockIds.contains(part.id))
                        .thenComparingDouble(Part::fillRate).thenComparing(Part::rotatable).thenComparing(Part::id)).toList();
        Set<String> unlocked = new LinkedHashSet<>();
        int count = Math.max(1, Math.min(5, (int) Math.ceil(composites.size() * 0.2)));
        for (int i = 0; i < Math.min(count, composites.size()); i++) {
            Part part = composites.get(Math.floorMod(round * count + i, composites.size()));
            if (unlocked.size() + part.members.size() <= 28) unlocked.addAll(part.members);
        }
        List<Part> anchors = parts.stream().filter(part -> unlocked.containsAll(part.members)).toList();
        List<Part> singles = parts.stream().filter(part -> part.members.size() == 1)
                .sorted(Comparator.comparing((Part part) -> !weakBlockIds.contains(part.id))
                        .thenComparingDouble(part -> complementDistance(part, anchors)).thenComparing(Part::id)).toList();
        for (int i = 0; i < Math.min(8, singles.size()) && unlocked.size() < 36; i++) {
            unlocked.addAll(singles.get(Math.floorMod(round * 3 + i, singles.size())).members);
        }
        return Set.copyOf(unlocked);
    }

    /** 尺寸越接近复合块短边、优先级一致的单件越早进入重建池；这只是选邻域，不改变安全约束。 */
    private static double complementDistance(Part single, List<Part> anchors) {
        return anchors.stream().filter(part -> part.priority == single.priority)
                .mapToDouble(part -> Math.abs(Math.min(single.length, single.width) - Math.min(part.length, part.width)))
                .min().orElse(10_000 + single.fillRate);
    }

    /** 合并前验证整例恰好覆盖一次，拒绝漏件、重复件与跨锁定块的半解锁。 */
    void merge(Path rebuiltFile, Set<String> unlocked, List<PolygonItem> expected, Path output) throws IOException {
        List<Part> merged = new ArrayList<>();
        for (Part part : parts) {
            if (part.members.stream().noneMatch(unlocked::contains)) merged.add(part);
            else if (!unlocked.containsAll(part.members)) throw new IOException("不允许只拆除块的一部分: " + part.id);
        }
        merged.addAll(read(rebuiltFile).parts);
        new NfpLocalPlan(merged).verifyCoverage(expected);
        StringBuilder text = new StringBuilder("blockCount=" + merged.size() + "\n");
        for (int i = 0; i < merged.size(); i++) text.append("\nblock ").append(i + 1).append('\n').append(merged.get(i).text).append('\n');
        Files.writeString(output, text);
    }

    /** 将弱板 UPI 映射回可重建的成员、尺寸与旋转约束，供下一批 Profile 使用。 */
    JsonArray describe(Set<String> ids, Map<String, PolygonItem> items) {
        JsonArray result = new JsonArray();
        for (Part part : parts) {
            if (!ids.isEmpty() && !ids.contains(part.id)) continue;
            JsonObject value = new JsonObject();
            value.addProperty("blockId", part.id);
            JsonArray members = new JsonArray(); part.members.forEach(members::add); value.add("members", members);
            value.addProperty("length", part.length); value.addProperty("width", part.width);
            value.addProperty("rotatable", part.rotatable); value.addProperty("fillRate", part.fillRate);
            value.addProperty("actualArea", part.members.stream().map(items::get).mapToDouble(item -> item.area).sum());
            result.add(value);
            if (result.size() >= 24) break;
        }
        return result;
    }

    /** 完整面积映射用于排样反馈，不受 LLM 提示中 24 个块的展示上限影响。 */
    Map<String, Double> actualAreas(Map<String, PolygonItem> items) {
        Map<String, Double> result = new java.util.HashMap<>();
        for (Part part : parts) result.put(part.id, part.members.stream().map(items::get).mapToDouble(item -> item.area).sum());
        return result;
    }
}
