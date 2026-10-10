package org.example.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 生成已写入 NFP 结果的稳定方案标识。
 *
 * <p>不能用块数或单件数判断方案相同：两个方案可能数量相等而工件组合、旋转或坐标不同。
 * 本类按块和成员排序后，保留每个成员的 ID、相对旋转、平移和实际轮廓，再计算 SHA-256。</p>
 */
final class NfpPlanSignature {
    private NfpPlanSignature() {
    }

    static String fromResultFile(Path resultFile) throws IOException {
        List<String> blocks = new ArrayList<>();
        List<String> members = null;
        String itemId = null, rotation = "", translation = "", points = "";
        for (String raw : Files.readAllLines(resultFile, StandardCharsets.UTF_8)) {
            String line = raw.strip();
            if (raw.startsWith("block ")) {
                if (members != null) {
                    appendMember(members, itemId, rotation, translation, points);
                    blocks.add(canonicalBlock(members));
                }
                members = new ArrayList<>();
                itemId = null; rotation = ""; translation = ""; points = "";
            } else if (members != null && line.startsWith("- id=")) {
                appendMember(members, itemId, rotation, translation, points);
                itemId = line.substring("- id=".length()).strip();
                rotation = ""; translation = ""; points = "";
            } else if (members != null && line.startsWith("selectedRelativeRotation=")) {
                rotation = line.substring("selectedRelativeRotation=".length()).strip();
            } else if (members != null && line.startsWith("translation=")) {
                translation = line.substring("translation=".length()).replaceAll("\\s+", " ");
            } else if (members != null && line.startsWith("placedPoints=")) {
                points = line.substring("placedPoints=".length()).replaceAll("\\s+", " ");
            }
        }
        if (members != null) {
            appendMember(members, itemId, rotation, translation, points);
            blocks.add(canonicalBlock(members));
        }
        blocks.sort(String::compareTo);
        return sha256(String.join("\n", blocks));
    }

    /** 保存易读的结构摘要，便于人工审计哈希对应的方案。 */
    static String describe(Path resultFile) throws IOException {
        List<String> membersPerBlock = new ArrayList<>();
        int members = 0; boolean inBlock = false;
        for (String line : Files.readAllLines(resultFile, StandardCharsets.UTF_8)) {
            if (line.startsWith("block ")) {
                if (inBlock) membersPerBlock.add(Integer.toString(members));
                inBlock = true; members = 0;
            } else if (line.strip().startsWith("- id=")) members++;
        }
        if (inBlock) membersPerBlock.add(Integer.toString(members));
        membersPerBlock.sort(Comparator.naturalOrder());
        return "blocks=" + membersPerBlock.size() + ";memberCounts=" + String.join(",", membersPerBlock);
    }

    /** Counts composite block sizes, e.g. {2: 4, 3: 18}; singles are intentionally excluded. */
    static Map<Integer, Integer> compositeMemberHistogram(Path resultFile) throws IOException {
        Map<Integer, Integer> result = new HashMap<>();
        int members = 0; boolean inBlock = false;
        for (String line : Files.readAllLines(resultFile, StandardCharsets.UTF_8)) {
            if (line.startsWith("block ")) {
                addCompositeSize(result, inBlock, members);
                inBlock = true; members = 0;
            } else if (line.strip().startsWith("- id=")) members++;
        }
        addCompositeSize(result, inBlock, members);
        return Map.copyOf(result);
    }

    private static void addCompositeSize(Map<Integer, Integer> histogram, boolean inBlock, int members) {
        if (inBlock && members > 1) histogram.merge(members, 1, Integer::sum);
    }

    private static void appendMember(List<String> members, String id, String rotation,
                                     String translation, String points) {
        if (id != null && !id.isBlank()) members.add(id + "|r=" + rotation + "|t=" + translation + "|p=" + points);
    }

    private static String canonicalBlock(List<String> members) {
        members.sort(String::compareTo);
        return String.join(";", members);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算 NFP 方案签名", exception);
        }
    }
}
