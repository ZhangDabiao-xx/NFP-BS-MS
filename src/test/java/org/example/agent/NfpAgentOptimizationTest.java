package org.example.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.example.beamsearch.application.PackingRuntimeConfig;
import org.example.nfp.BatchBlockStitcher;
import org.example.nfp.Block;
import org.example.nfp.NfpSearchSession;
import org.example.nfp.Point;
import org.example.nfp.PolygonItem;
import org.example.nfp.PolygonStitcher;
import org.example.nfp.selection.NfpFeatureExtractor;
import org.example.nfp.selection.NfpScoringProfile;
import org.example.nfp.selection.NfpSelectionModel;
import org.example.nfp.selection.NfpSelectionStage;
import org.example.nfp.selection.ProfileDrivenNfpSelectionModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Agent 专属回归测试：真实小规模几何/排样，禁止联网，不运行完整 Cabinet1。 */
class NfpAgentOptimizationTest {
    @TempDir Path temporary;

    @Test
    void realBoundingBoxFeaturesDistinguishEqualAreaShapesAndPlateSizes() {
        Block thin = Block.fromSingle(rectangle("thin", 200, 50));
        Block square = Block.fromSingle(rectangle("square", 100, 100));
        Map<String, Double> left = NfpFeatureExtractor.root(thin, 1, 2, 400, 200);
        Map<String, Double> right = NfpFeatureExtractor.root(square, 1, 2, 400, 200);
        assertEquals(left.get("boxAreaToPlateArea"), right.get("boxAreaToPlateArea"));
        assertEquals(0.5, left.get("longSideToPlateLongSide"), 1e-10);
        assertEquals(0.25, left.get("shortSideToPlateShortSide"), 1e-10);
        assertEquals(0.5, right.get("shortSideToPlateShortSide"), 1e-10);
        PolygonItem fixed = new PolygonItem("fixed", false, new Point(0, 0), thin.placements.get(0).item.points,
                List.of(0, 180), false);
        assertEquals(0.0, NfpFeatureExtractor.root(Block.fromSingle(fixed), 1, 2).get("canSwapSides"));
        assertEquals(1.0, left.get("canSwapSides"));
    }

    @Test
    void rawGeometryCacheIsSharedButOppositeProfilesRerankIndependently() {
        Block root = Block.fromSingle(corner());
        PolygonItem moving = rectangle("moving", 40, 20);
        try (NfpSearchSession session = new NfpSearchSession(300, 200, 30_000)) {
            List<PolygonStitcher.StitchingCandidate> high = session.candidates(root, moving, List.of(0, 90), contactModel(1), 1000);
            List<PolygonStitcher.StitchingCandidate> low = session.candidates(root, moving, List.of(0, 90), contactModel(-1), 1000);
            assertTrue(high.size() > 1, "测试必须包含多个真实可行位置");
            assertEquals(high.size(), low.size()); assertEquals(1, session.misses()); assertEquals(1, session.hits());
            double best = NfpFeatureExtractor.placement(high.get(0), moving, 0).get("contactRatio");
            double worst = NfpFeatureExtractor.placement(low.get(0), moving, 0).get("contactRatio");
            assertTrue(best > worst, "同一未截断候选池应允许相反偏好选出不同位置");
            assertEquals(high.get(0), session.candidates(root, moving, List.of(0, 90), contactModel(1), 1000).get(0));
        }
        assertNull(NfpSearchSession.current());
    }

    @Test
    void localMergeKeepsLockedBlocksAndRejectsLostOrDuplicatedMembers() throws Exception {
        PolygonItem a = rectangle("a", 20, 10), b = rectangle("b", 30, 10), c = rectangle("c", 40, 10);
        List<PolygonItem> items = List.of(a, b, c);
        Path original = temporary.resolve("original.txt"), rebuilt = temporary.resolve("rebuilt.txt");
        Path merged = temporary.resolve("merged.txt");
        BatchBlockStitcher.writeBlocks(original, items.stream().map(Block::fromSingle).toList());
        BatchBlockStitcher.writeBlocks(rebuilt, List.of(Block.fromSingle(b)));
        NfpLocalPlan plan = NfpLocalPlan.read(original);
        plan.merge(rebuilt, Set.of("b"), items, merged);
        assertEquals(NfpPlanSignature.fromResultFile(original), NfpPlanSignature.fromResultFile(merged));
        assertEquals(3, NfpLocalPlan.read(merged).actualAreas(Map.of("a", a, "b", b, "c", c)).size());
        BatchBlockStitcher.writeBlocks(rebuilt, List.of(Block.fromSingle(a)));
        assertThrows(IOException.class, () -> plan.merge(rebuilt, Set.of("b"), items, merged));
        BatchBlockStitcher.writeBlocks(rebuilt, List.of(Block.fromSingle(b), Block.fromSingle(c)));
        assertThrows(IOException.class, () -> plan.merge(rebuilt, Set.of("b", "c"), List.of(a, b), merged));
    }

    @Test
    void inputEquivalenceRetainsActualAreaAsASeparateKey() throws Exception {
        String header = "BatchNo,UPI,Qty,Color,Length,Width,IsSpecial,Rotatable,ActualWorkpieceArea\n";
        Path a = temporary.resolve("a.csv"), b = temporary.resolve("b.csv");
        Files.writeString(a, header + "x,a,1,0,100.0000,50.0000,0,0,4500.0000\n");
        Files.writeString(b, header + "x,b,1,0,100.0000,50.0000,0,0,4000.0000\n");
        var first = NfpExperimentEvaluator.inputKey(a); var second = NfpExperimentEvaluator.inputKey(b);
        assertEquals(first.geometry(), second.geometry()); assertNotEquals(first.withArea(), second.withArea());
    }

    @Test
    void invalidQualityCannotBecomeChampionAndBudgetIsRestoredOnFailure() {
        assertFalse(NfpExperimentEvaluator.verified(new JsonObject()));
        assertThrows(IllegalArgumentException.class, () -> NfpExperimentEvaluator.compare(new JsonObject(), new JsonObject()));
        String previous = System.getProperty(PackingRuntimeConfig.AGENT_EXPERIMENT_BUDGET_PROPERTY);
        var stdout = System.out;
        try {
            System.setProperty(PackingRuntimeConfig.AGENT_EXPERIMENT_BUDGET_PROPERTY, "1234");
            assertThrows(IOException.class, () -> NfpExperimentEvaluator.measure(temporary.resolve("missing"),
                    temporary.resolve("input"), temporary.resolve("output"), 10));
            assertEquals("1234", System.getProperty(PackingRuntimeConfig.AGENT_EXPERIMENT_BUDGET_PROPERTY));
            assertSame(stdout, System.out);
        } finally {
            if (previous == null) System.clearProperty(PackingRuntimeConfig.AGENT_EXPERIMENT_BUDGET_PROPERTY);
            else System.setProperty(PackingRuntimeConfig.AGENT_EXPERIMENT_BUDGET_PROPERTY, previous);
        }
    }

    @Test
    void agentSessionDoesNotChangeSubsequentBaselineAndTimeoutCleansUp() throws Exception {
        List<PolygonItem> items = List.of(corner(), rectangle("small", 40, 20));
        Path before = temporary.resolve("before.txt"), after = temporary.resolve("after.txt");
        BatchBlockStitcher.writeBlocks(before, BatchBlockStitcher.buildBlocks(items, 3));
        NfpScoringProfile profile = NfpScoringProfileAgent.localPortfolio().get(0);
        try (NfpSearchSession session = new NfpSearchSession(300, 200, 30_000)) {
            BatchBlockStitcher.buildBlocks(items, 3, new ProfileDrivenNfpSelectionModel(profile), 20, 20, null);
            session.startTrial(1); Thread.sleep(5);
            assertThrows(NfpSearchSession.SearchTimeoutException.class, NfpSearchSession::checkDeadline);
        }
        BatchBlockStitcher.writeBlocks(after, BatchBlockStitcher.buildBlocks(items, 3));
        assertEquals(Files.readString(before), Files.readString(after));
        assertNull(NfpSearchSession.current());
    }

    @Test
    void offlineExperimentSkipsUnchangedPlansAndRetainsVerifiedBaseline() throws Exception {
        // 三个规则矩形无法凭空改善拼接，用来验证重复跳过、离线分支和端到端产物。
        Path input = temporary.resolve("mini.json");
        JsonObject root = new JsonObject(); JsonArray items = new JsonArray();
        for (PolygonItem item : List.of(rectangle("a", 80, 40), rectangle("b", 60, 40), rectangle("c", 50, 30))) {
            JsonObject value = new JsonObject(); value.addProperty("id", item.id); value.addProperty("BackFrontPriority", false);
            value.addProperty("smallItem", false); JsonArray rotate = new JsonArray(); rotate.add(0); rotate.add(90); value.add("rotate", rotate);
            JsonArray points = new JsonArray();
            for (Point point : item.points) { JsonArray xy = new JsonArray(); xy.add(point.x); xy.add(point.y); points.add(xy); }
            value.add("points", points); JsonArray center = new JsonArray(); center.add(0); center.add(0); value.add("centPt", center);
            items.add(value);
        }
        root.add("items", items); JsonArray plates = new JsonArray(); JsonObject plate = new JsonObject();
        plate.addProperty("width", 300); plate.addProperty("height", 200); plates.add(plate); root.add("plates", plates);
        AgentJsonFiles.writeObject(input, root);
        var settings = new NfpAgentOptimizer.Settings(1, 2, 10, 20, 30, 30_000, 1_000, false);
        Path output = new NfpAgentOptimizer(settings).run(input, temporary.resolve("experiment"));
        JsonObject result = AgentJsonFiles.readObject(output, "smoke result");
        assertEquals("baseline_retained", result.get("outcome").getAsString());
        assertTrue(NfpExperimentEvaluator.verified(result.getAsJsonObject("championQuality")));
        assertEquals(0, result.get("quickMeasuredCount").getAsInt());
        assertFalse(result.getAsJsonArray("trials").isEmpty());
        for (var trial : result.getAsJsonArray("trials")) {
            assertTrue(trial.getAsJsonObject().get("outcome").getAsString().startsWith("skipped_"));
        }
    }

    @Test
    void offlineLocalSearchCanProduceAndEvaluateDifferentPackingInputs() throws Exception {
        Path input = temporary.resolve("local-search.json");
        // L 件可由两个 80x40 矩形补齐，Agent 应能保留只填一半的中间块作为替代方案。
        writeCase(input, List.of(corner(), rectangle("b", 80, 40), rectangle("c", 80, 40), rectangle("d", 120, 20)));
        var settings = new NfpAgentOptimizer.Settings(1, 3, 10, 20, 30, 30_000, 2_000, false);
        Path output = new NfpAgentOptimizer(settings).run(input, temporary.resolve("local-experiment"));
        JsonObject result = AgentJsonFiles.readObject(output, "local smoke result");
        assertTrue(result.get("quickMeasuredCount").getAsInt() > 0, "必须实际评估不同方案，不能所有新测试都只覆盖跳过分支");
        assertTrue(NfpExperimentEvaluator.verified(result.getAsJsonObject("championQuality")));
        assertTrue(result.get("rawCacheHits").getAsLong() > 0);
        assertTrue(result.has("baselineMedium"), "有候选时需要同预算中档基准");
        boolean finalMeasured = false;
        for (var element : result.getAsJsonArray("trials")) {
            JsonObject trial = element.getAsJsonObject();
            if (!trial.has("finalQuality")) continue;
            finalMeasured = true;
            assertEquals(30, trial.get("finalBudgetMs").getAsInt());
            assertEquals("verified_equal", trial.get("outcome").getAsString());
        }
        assertTrue(finalMeasured, "小案例必须覆盖最终等预算验证分支");
        assertEquals("baseline_retained", result.get("outcome").getAsString());
    }

    @Test
    void existingCabinetArtifactsCanBeReadWithoutChangingThem() throws Exception {
        Path input = Path.of("data/inputData2/Cabinet1.json"), planFile = Path.of("data/BA06_NFP/Cabinet1.txt");
        // 此项为本地兼容性检查；没有用户数据的干净 CI 明确跳过，其他几何测试仍完整运行。
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(input) && Files.isRegularFile(planFile));
        List<PolygonItem> items = BatchBlockStitcher.readItems(input);
        NfpLocalPlan plan = NfpLocalPlan.read(planFile); plan.verifyCoverage(items);
        Set<String> unlocked = plan.unlock(0, Set.of());
        assertFalse(unlocked.isEmpty()); assertTrue(unlocked.size() <= 36);
        Map<String, PolygonItem> itemMap = new java.util.HashMap<>(); items.forEach(item -> itemMap.put(item.id, item));
        assertEquals(items.stream().mapToDouble(item -> item.area).sum(),
                plan.actualAreas(itemMap).values().stream().mapToDouble(Double::doubleValue).sum(), 0.001);
    }

    /** 构造独立小案例，不复用或覆盖用户的真实输入/结果。 */
    private static void writeCase(Path input, List<PolygonItem> polygons) throws IOException {
        JsonObject root = new JsonObject(); JsonArray items = new JsonArray();
        for (PolygonItem item : polygons) {
            JsonObject value = new JsonObject(); value.addProperty("id", item.id);
            value.addProperty("BackFrontPriority", item.backFrontPriority); value.addProperty("smallItem", item.smallItem);
            JsonArray rotations = new JsonArray(); item.rotate.forEach(rotations::add); value.add("rotate", rotations);
            JsonArray points = new JsonArray();
            for (Point point : item.points) { JsonArray xy = new JsonArray(); xy.add(point.x); xy.add(point.y); points.add(xy); }
            value.add("points", points); JsonArray center = new JsonArray(); center.add(0); center.add(0); value.add("centPt", center);
            items.add(value);
        }
        root.add("items", items); JsonArray plates = new JsonArray(); JsonObject plate = new JsonObject();
        plate.addProperty("width", 300); plate.addProperty("height", 200); plates.add(plate); root.add("plates", plates);
        AgentJsonFiles.writeObject(input, root);
    }

    private static PolygonItem rectangle(String id, double length, double width) {
        return new PolygonItem(id, false, new Point(0, 0),
                List.of(new Point(0, 0), new Point(length, 0), new Point(length, width), new Point(0, width)), List.of(0, 90), false);
    }

    private static PolygonItem corner() {
        return new PolygonItem("corner", false, new Point(0, 0), List.of(new Point(0, 0), new Point(100, 0),
                new Point(100, 20), new Point(20, 20), new Point(20, 100), new Point(0, 100)), List.of(0, 90), false);
    }

    private static NfpSelectionModel contactModel(double direction) {
        return new NfpSelectionModel() {
            @Override public double score(NfpSelectionStage stage, Map<String, Double> features) { return direction * features.getOrDefault("contactRatio", 0.0); }
            @Override public String id() { return "contact-" + direction; }
            @Override public boolean isProfileDriven() { return true; }
        };
    }
}
