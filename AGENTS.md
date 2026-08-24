# Repository Guidelines

## Project Structure & Module Organization

- `src/main/java/org/example/nfp/` contains polygon geometry, NFP computation, block stitching, and batch entry points. `nfp/visual/` renders generated results.
- `src/main/java/org/example/beamsearch/` contains the beam-search packing algorithm, block generation, lower-bound solvers, and application runners.
- `data/inputData/` stores JSON cases. Generated results normally go to `data/outputData/`, `data/NFPresult1/`, `data/NFPpicture1/`, or `data/packResult/`.
- `pom.xml` defines the Java 17 Maven build and Gson/JavaFX dependencies. Treat `target/` and `out/` as generated artifacts; do not edit or commit them.

## Build, Test, and Development Commands

Run commands from the repository root:

```powershell
mvn clean test
mvn package
mvn exec:java
mvn exec:java -Dexec.mainClass=org.example.nfp.BatchBlockStitcher -Dexec.args="data/inputData data/NFPresult1 5"
```

`clean test` compiles and runs tests, `package` creates the shaded runnable JAR, the default `exec:java` invocation runs `OutputDataVisualizer`, and the final command runs batch NFP stitching with optional input, output, and round-count arguments. Use separate output directories when experimenting so existing data is preserved.

## Coding Style & Naming Conventions

Use Java 17, four-space indentation, braces on the declaration line, and UTF-8 source files. Keep package names lowercase; use `PascalCase` for classes, `camelCase` for methods/variables, and `UPPER_SNAKE_CASE` for constants. No formatter or linter is configured, so keep changes consistent with nearby code and verify with Maven.

## Testing Guidelines

There is currently no `src/test` tree and no test framework configured. Add unit tests under `src/test/java` (with fixtures under `src/test/resources`) when changing algorithmic behavior; name classes `*Test` and run them with `mvn test`. For visual or data-pipeline changes, record the input case and output directory used for manual verification.

## Commit & Pull Request Guidelines

This checkout has no accessible Git history, so no repository-specific commit convention can be inferred. Use short, imperative subjects such as `Fix NFP block filtering`, keep commits focused, and avoid generated files. Pull requests should explain the behavior change, list validation commands and representative data cases, link the relevant issue, and include screenshots when visual output changes.
