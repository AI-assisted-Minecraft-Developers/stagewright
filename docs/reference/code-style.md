# Code style and static checks

Three tools judge the source, each on its own part, and all three are applied by the `stagewright.lint` plugin in
`build-logic/` to every project of the three builds (the root, `engine/` and `gradle-plugin/`). Each runs under `check`.

## Layout: Spotless

Spotless alone decides layout. Nobody arranges it by hand, and no other tool has an opinion on it.

| Files | Formatter |
|---|---|
| Java, every source set | palantir-java-format 2.103.0, which also reflows javadoc text |
| `*.gradle`, `build-logic/` included | Groovy-Eclipse, indenting with four spaces (`config/spotless/greclipse.properties`) |
| JavaScript under `src/` (script tests) | Prettier 3.6.2, 120 columns, four-space indentation |
| Markdown, properties, YAML, JSON, TOML, `pack.mcmeta`, the XML under `config/` | trailing whitespace removed, leading tabs turned into spaces, a final newline |

`./gradlew spotlessApply` rewrites the files; `spotlessCheck` reports what it would change. Run them in each of the
three builds (`-p engine`, `-p gradle-plugin`): a build formats only its own files.

The one-time reformat of every file is listed in `.git-blame-ignore-revs`. To have `git blame` skip it, run once:
`git config blame.ignoreRevsFile .git-blame-ignore-revs`.

The Groovy-Eclipse formatter cannot parse a quoted string inside a `${}` in another string. Give the inner expression
its own variable instead.

## Everything else: Checkstyle and PMD

Both read only the main source sets. Their configuration is in `config/checkstyle/` and `config/pmd/ruleset.xml`, and
each file says what it adds or leaves out.

- Checkstyle 14.3.0: Google's checks without the layout ones, plus JavaNCSS (method and class size), declaration order,
  at most ten parameters, and a string written four or more times in one file. Naming checks do not apply in a
  `mixin` package (`config/checkstyle/suppressions.xml`).
- PMD 7.29.0: PMD's quickstart set, without the rules Checkstyle or the formatter already cover, plus a list of
  added rules.

To silence one finding, say why in a comment beside it and use `@SuppressWarnings("checkstyle:<check name in lower
case>")` or `@SuppressWarnings("PMD.<RuleName>")`.

For now both only report: the build does not fail on their findings until the existing ones are fixed. The reports
are in each project's `build/reports/checkstyle/` and `build/reports/pmd/`.

## Module boundaries: ArchitectureTest

Each build's tests check which module may depend on what, on the compiled classes:

| Test | Checks |
|---|---|
| `engine/…/ArchitectureTest` | the engine and the supervision agent link against no Minecraft, loader or WorldDriver class |
| `gradle-plugin/…/ArchitectureTest` | the same for the Gradle plugin; it may still name those classes in strings |
| `cli/…/ArchitectureTest` | the same for the CLI; and in the two loader jars it carries, only the `driver`, `jupiter` and `test` packages use WorldDriver, no WorldDriver, Rhino or Netty class is carried, and the classes the test engine relies on are there |
