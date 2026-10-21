package org.example.beamsearch.lb.validation;

import com.google.gson.GsonBuilder;
import org.example.beamsearch.lb.entity.Item;
import org.example.beamsearch.lb.solver.RectangleLowerBoundSolver;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Standalone benchmark only: never starts packing, NFP or an Agent. */
public final class ClassDataLowerBoundApplication {
    private ClassDataLowerBoundApplication() { }

    private static final Map<Integer, Integer> FIXED_REFERENCE =
            Map.of(20, 515, 40, 975, 60, 1440, 80, 1955, 100, 2322);
    private static final Map<Integer, Integer> ROTATED_REFERENCE =
            Map.of(20, 481, 40, 926, 60, 1379, 80, 1871, 100, 2235);

    public static void main(String[] args) throws IOException {
        if (args.length != 2 && args.length != 4 && args.length != 5) {
            throw new IllegalArgumentException("Usage: <CLASSData root> <new output directory> "
                    + "[<fixed packing results> <rotated packing results> [--ccm1]]");
        }
        boolean ccm1 = args.length == 5;
        if (ccm1 && !args[4].equals("--ccm1")) {
            throw new IllegalArgumentException("Unknown option: " + args[4]);
        }
        run(Path.of(args[0]), Path.of(args[1]), args.length >= 4 ? Path.of(args[2]) : null,
                args.length >= 4 ? Path.of(args[3]) : null, ccm1);
    }

    public static void run(Path inputRoot, Path output, Path fixedResults, Path rotatedResults,
                           boolean includeCcm1) throws IOException {
        List<Path> cases;
        try (var paths = Files.walk(inputRoot)) {
            cases = paths.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".txt")).sorted().toList();
        }
        if (cases.isEmpty()) {
            throw new IOException("No CLASSData cases: " + inputRoot);
        }
        Files.createDirectories(output);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("inputRoot", inputRoot.toAbsolutePath().normalize().toString());
        summary.put("referenceSource", "User-supplied BWMV Tables 8 (fixed) and 9 (0/90 degrees)");
        summary.put("referenceMeaning", "Published LB are sums over 100 cases per item-count group, "
                + "not per-instance optima or upper bounds. Equality is not required; an excess is not "
                + "by itself an invalid-bound certificate. No per-instance literature LB was supplied.");
        summary.put("includeCcm1ForFixedOrientations", includeCcm1);
        try (BufferedWriter csv = Files.newBufferedWriter(output.resolve("cases.csv"), StandardCharsets.UTF_8)) {
            csv.write("case,n,allowRotation,areaLB,dffLB,bmLB,ccm1LB,lowerBound,verifiedUpperBound,upperBoundCheck,elapsedMs\n");
            for (boolean rotate : new boolean[]{false, true}) {
                Map<Integer, Group> groups = new TreeMap<>();
                int processed = 0;
                for (Path source : cases) {
                    String group = source.getParent().getFileName().toString();
                    String name = source.getFileName().toString().replaceFirst("[.]txt$", "");
                    int[] board = readBoard(inputRoot.resolve("material_" + group + ".csv"));
                    Item[] items = readRectangles(source);
                    RectangleLowerBoundSolver.Result result = RectangleLowerBoundSolver.solveDetailed(
                            board[0], board[1], items, rotate, includeCcm1);
                    Path packingRoot = rotate ? rotatedResults : fixedResults;
                    UpperBound upper = packingRoot == null ? new UpperBound(null, "not_supplied")
                            : verifyPacking(packingRoot.resolve(group).resolve(name).resolve("optimized.csv"),
                                    name, board[0], board[1], items, rotate);
                    Group aggregate = groups.computeIfAbsent(items.length, n -> new Group());
                    aggregate.add(result, upper);
                    csv.write(String.format(Locale.ROOT, "%s/%s,%d,%s,%d,%d,%s,%s,%d,%s,%s,%.3f%n",
                            group, name, items.length, rotate, result.areaBound(), result.dffBound(),
                            text(result.bmBound()), text(result.ccm1Bound()), result.lowerBound(),
                            text(upper.boards), upper.status, result.elapsedNanos() / 1_000_000.0));
                    if (++processed % 100 == 0) {
                        System.out.println("Lower-bound validation: rotation=" + rotate + ", cases=" + processed);
                    }
                }
                Map<String, Object> mode = new LinkedHashMap<>();
                Map<Integer, Integer> reference = rotate ? ROTATED_REFERENCE : FIXED_REFERENCE;
                int total = 0;
                int referenceTotal = reference.values().stream().mapToInt(Integer::intValue).sum();
                for (var entry : groups.entrySet()) {
                    Group group = entry.getValue();
                    group.referenceLowerBoundSum = reference.get(entry.getKey());
                    group.differenceFromReference = group.referenceLowerBoundSum == null ? null
                            : group.lowerBoundSum - group.referenceLowerBoundSum;
                    total += group.lowerBoundSum;
                }
                mode.put("caseCount", processed);
                mode.put("standardGroupCounts", processed == 500 && groups.keySet().equals(reference.keySet())
                        && groups.values().stream().allMatch(g -> g.caseCount == 100));
                mode.put("lowerBoundSum", total);
                mode.put("referenceLowerBoundSum", referenceTotal);
                // Preserve the supplied table's arithmetic discrepancy instead of silently
                // changing either its per-group rows or its printed Total.
                int printedReferenceTotal = rotate ? 6892 : 7208;
                mode.put("referencePrintedTotal", printedReferenceTotal);
                mode.put("referenceRowsMatchPrintedTotal", referenceTotal == printedReferenceTotal);
                mode.put("groups", groups);
                summary.put(rotate ? "rotation" : "fixed", mode);
            }
        }
        Files.writeString(output.resolve("summary.json"), new GsonBuilder().setPrettyPrinting().serializeNulls()
                .create().toJson(summary), StandardCharsets.UTF_8);
        System.out.println("Standalone lower-bound reports: " + output.toAbsolutePath().normalize());
    }

    private static String text(Integer value) {
        return value == null ? "" : value.toString();
    }

    private static int[] readBoard(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int i = 1; i < lines.size(); i++) {
            if (!lines.get(i).isBlank()) {
                String[] row = lines.get(i).split(",");
                return new int[]{Item.scale(Double.parseDouble(row[1].strip())),
                        Item.scale(Double.parseDouble(row[2].strip()))};
            }
        }
        throw new IOException("No board specification: " + file);
    }

    private static Item[] readRectangles(Path file) throws IOException {
        List<Item> items = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            String[] sizes = line.strip().split("\\s+");
            if (sizes.length != 2) {
                throw new IOException("Expected two dimensions: " + file);
            }
            items.add(new Item(Integer.toString(items.size()), Double.parseDouble(sizes[0]),
                    Double.parseDouble(sizes[1])));
        }
        if (items.isEmpty()) {
            throw new IOException("Empty rectangle case: " + file);
        }
        return items.toArray(Item[]::new);
    }

    private record UpperBound(Integer boards, String status) { }
    private record Placement(long x, long y, long right, long top) { }

    /** Check the actual CSV geometry and item identities, rather than trusting a report's passed flag. */
    private static UpperBound verifyPacking(Path file, String name, int width, int height,
                                            Item[] items, boolean rotate) {
        if (!Files.isRegularFile(file)) {
            return new UpperBound(null, "missing");
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            Set<Integer> seen = new HashSet<>();
            Map<Integer, List<Placement>> boards = new HashMap<>();
            for (int row = 1; row < lines.size(); row++) {
                if (lines.get(row).isBlank()) {
                    continue;
                }
                String[] columns = Arrays.stream(lines.get(row).split(",")).map(String::strip).toArray(String[]::new);
                if (columns.length < 9) {
                    return new UpperBound(null, "invalid_columns");
                }
                String prefix = name + "-";
                if (!columns[3].startsWith(prefix)) {
                    return new UpperBound(null, "invalid_identity");
                }
                int index = Integer.parseInt(columns[3].substring(prefix.length())) - 1;
                if (index < 0 || index >= items.length || !seen.add(index)) {
                    return new UpperBound(null, "invalid_identity");
                }
                Item item = items[index];
                int orientation = Integer.parseInt(columns[6]);
                if (orientation != 0 && (orientation != 90 || !rotate)) {
                    return new UpperBound(null, "invalid_rotation");
                }
                if (Item.scale(Double.parseDouble(columns[7])) != item.w
                        || Item.scale(Double.parseDouble(columns[8])) != item.h) {
                    return new UpperBound(null, "invalid_dimensions");
                }
                long x = Long.parseLong(columns[4]);
                long y = Long.parseLong(columns[5]);
                Placement placed = new Placement(x, y, x + (orientation == 0 ? item.w : item.h),
                        y + (orientation == 0 ? item.h : item.w));
                if (x < 0 || y < 0 || placed.right > width || placed.top > height) {
                    return new UpperBound(null, "invalid_boundary");
                }
                int number = Integer.parseInt(columns[1]);
                if (number <= 0) {
                    return new UpperBound(null, "invalid_board_number");
                }
                List<Placement> board = boards.computeIfAbsent(number, ignored -> new ArrayList<>());
                for (Placement other : board) {
                    if (placed.x < other.right && other.x < placed.right
                            && placed.y < other.top && other.y < placed.top) {
                        return new UpperBound(null, "invalid_overlap");
                    }
                }
                board.add(placed);
            }
            return seen.size() == items.length ? new UpperBound(boards.size(), "valid")
                    : new UpperBound(null, "invalid_piece_count");
        } catch (IOException | IllegalArgumentException exception) {
            return new UpperBound(null, "unreadable_or_invalid_csv");
        }
    }

    private static final class Group {
        int caseCount;
        int lowerBoundSum;
        int areaBoundSum;
        int upperBoundsVerified;
        int lowerBoundsEqualUpperBound;
        int lowerBoundsExceedUpperBound;
        int invalidExistingPackings;
        int missingExistingPackings;
        Integer referenceLowerBoundSum;
        Integer differenceFromReference;
        double elapsedMs;

        void add(RectangleLowerBoundSolver.Result result, UpperBound upper) {
            caseCount++;
            lowerBoundSum += result.lowerBound();
            areaBoundSum += result.areaBound();
            elapsedMs += result.elapsedNanos() / 1_000_000.0;
            if (upper.boards != null) {
                upperBoundsVerified++;
                if (result.lowerBound() == upper.boards) {
                    lowerBoundsEqualUpperBound++;
                }
                if (result.lowerBound() > upper.boards) {
                    lowerBoundsExceedUpperBound++;
                }
            } else if (upper.status.startsWith("invalid") || upper.status.equals("unreadable_or_invalid_csv")) {
                invalidExistingPackings++;
            } else {
                missingExistingPackings++;
            }
        }
    }
}
