# mc-testkit 编排契约 v0（冻结 2026-07-16）

本契约是编排器（现 Python `scripts/testkit/t0.py`，将来 gradle-plugin）与游戏内
harness 之间的接口。**变更需升 v1 并保持 v0 解析兼容。**

## 启动协议
- T0 壳 = 普通专用服务器 loom run `:testkit-<loader>:runTestkitServer`
  （runDir `mc-testkit/<loader>/run-testkit`，JVM sysprop `testkit.autorun=true` 触发）。
- 编排器负责预备 runDir：`eula.txt`、`server.properties`（server-port=25599、
  level-type=minecraft\:flat、online-mode=false、spawn-protection=0）、删 `world/`
  与旧结果文件；跑前按显式 PID 清扫命令行含 `testkit.autorun` 的残留 JVM（禁 pkill）。
  以上为契约相关键，编排器实际写入的完整集以 `scripts/testkit/t0.py` 的
  `provision()` 为准（非穷举列表）。
- harness 跑完注册表后自行 `MinecraftServer.halt(false)` 正常停机；
  **服务器进程退出码不是裁决依据**，裁决唯一来源是结果文件。
- **编排器的完成信号 = 结果文件的 done 尾记录，不是 gradle 退出**（实证：halt 后
  游戏 JVM 秒级干净退出，但 loom run task 不归还控制权）；观察到尾记录 → 宽限
  数秒 → 终止 gradle + 显式 PID 清扫 → 裁决。

## 结果文件
`<runDir>/testkit-results.jsonl`，UTF-8，一行一个 JSON 对象：
- 头 `{"type":"suite","loader":"neoforge|fabric","registered":[{"name","required","canary"}...]}`
- 场景 `{"type":"scene","name","outcome":"PASS|FAIL|TIMEOUT|ENV_FAIL","ticks","wallMs","reason"}`
- 尾 `{"type":"done","scenes":N}`（缺尾 = harness 中途死亡 = RED）
- `canary` ∈ NONE | MUST_FAIL | MUST_TIMEOUT | MUST_SWALLOW；
  MUST_SWALLOW 场景**不得**有场景记录（有 = 门死）。
  金丝雀场景若记录为 `ENV_FAIL`（例如 PREP 阶段区块加载失败）同样按 outcome
  不匹配处理 → exit 2 DEAD；这是保守裁决——即便根因是环境问题，整轮结果仍作废。

## 退出码
| code | 含义 |
|---|---|
| 0 | GREEN：尾在、注册==执行（吞金丝雀除外）、金丝雀全中、required 非金丝雀全 PASS |
| 1 | RED：非金丝雀失败/超时/被吞/漂移记录/`done.scenes` 计数不符（TRUNCATED），或缺尾 |
| 2 | DEAD：任一金丝雀判错——框架抓失败的能力失效，整轮结果作废 |
| 3 | ENV：起不来 / 缺结果文件 / 缺头 |

`required=false` 场景允许 FAIL/TIMEOUT（GREEN 不受影响，报告行标 `fail(optional)`），
但**不允许被吞**：注册即必须有场景记录，缺记录 = RED，与 required 无关——被吞是
框架完整性违规（#85 病），不是测试结果。

## 游戏内时序契约
结果写盘只在场景边界（P0 探针事故教训，agent-driver 926396d）；
确定性敏感场景入驻（P1c）前须复核，必要时改异步 writer。

## SceneProvider（v0 附录）
下游 mod（P1c 起：agent-driver 自身）通过 SPI 向 T0 套件贡献场景，语义只澄清、不改
线协议，版本仍 v0：

- **发现机制**：`Scenes.all()` = 内建 `builtin()` 列表 ++ `ServiceLoader.load(SceneProvider.class)`
  按发现顺序逐个 provider 的 `scenes()` 拼接（`Scenes.java`）。执行顺序 = 拼接顺序，即
  suite header 的 `registered[]` 与实际执行顺序一致。
- **内建在前，下游 provider 在后**：`Scenes.builtin()` 先入表，SPI 发现到的场景全部
  追加在后面——内建场景（含全部金丝雀）永远排在业务场景之前。
- **名字全局唯一**：`TestkitHarness` 构造期对「内建 + 全部 provider」合并后的完整
  列表跑 `rejectDuplicateNames()`，早于 `writeSuiteHeader()`。撞名 →
  `IllegalStateException`，服务器崩在写头之前，编排器读不到 `type:suite` 记录 →
  按 exit 3 ENV 裁决（不是 RED——连头都没有，不是"头对不上执行"）。
- **金丝雀仍由内建场景承担**：三枚金丝雀（`canaryMustFail`/`canaryMustTimeout`/
  `canaryMustSwallow`）只定义在 `Scenes.builtin()`。下游 SceneProvider 不贡献、也不需要
  贡献自己的金丝雀——框架"抓失败"的能力由内建金丝雀单点验证一次即可，下游只贡献
  业务场景本身（P1c 例：三个 `ad.*` 场景均 `required=true, canary=NONE`）。
- **发现路径**：`META-INF/services/net.magicterra.testkit.scene.SceneProvider`，
  文件内容一行一个实现类全限定名。P1c 例（neoforge 模块）：该文件单行为
  `net.magicterra.agent.neoforge.testkit.AgentDriverScenes`。

## done.scenes 对账
P1c 新增的完整性检查（编排器 `verdict.judge()`），比"场景被吞"（SWALLOWED：某个
`registered` 名字没有对应场景记录）更底层，专抓"文件本身被截断"：

- **判据**：footer `{"type":"done","scenes":N}` 的 `N` 与编排器实际解析到的场景记录
  条数（按名字去重前的总条数）比较，不相等 → RED，报告行前缀 `TRUNCATED`
  （`TRUNCATED: done.scenes=<N> but <M> scene records`）。
- **与 SWALLOWED 的分工**：SWALLOWED 抓"该执行的场景在 `registered[]` 里但没有
  对应记录"（名字维度的完整性）；TRUNCATED 抓"harness 自己数的写入条数与编排器
  实际读到的条数对不上"（文件维度的完整性）——例如异步 writer 队列在 shutdown
  竞态下漏 flush 掉几条记录，即便每个名字看起来都对上了也可能被这条抓到。
- **缺字段容忍（前向兼容）**：若 `done` 记录没有 `scenes` 字段，这条检查直接跳过
  （不计入裁决），不判 RED。这不是对本仓库 harness 历史版本的兼容——本仓库
  `ResultsJsonl` 自 P1a 第一个 harness commit（`936f4f2`）起就一直携带 `scenes`
  字段，从未缺过。容忍的是**第三方/未来 harness 实现**：v0 契约本身不强制 footer
  必须携带该字段，缺字段的结果文件不能被这条新增的检查判定为完整性违规。
- 本节语义只收紧（新增一条完整性门），不放松、不改既有字段/退出码含义，故契约
  仍冻结在 v0，不升版。

## --expect-scene 外部期望门（v0 附录，P1.5a）
`t0.py --expect-scene name1,name2,...`：逗号分隔的场景名列表，每个名字**必须**出现在
suite header 的 `registered[]` 里，否则整轮判 RED（报告行 `MISSING-EXPECTED: <name>
not in registered`）。实现 = `verdict.judge(records, expected=[...])` 对 `registered[]`
与 `expected` 做集合比对，与场景本身的 outcome 无关。

- **抓的是什么**：这条检查独立于、且早于 SWALLOWED/TRUNCATED 两门——那两门都假定
  场景"已注册"（在 `registered[]` 里）为前提，只检查"注册了但没执行"或"文件被截断"。
  `--expect-scene` 抓的是**注册这一层本身**：SceneProvider 走 `ServiceLoader` 发现
  （`META-INF/services` 一行一个 FQCN），如果这根线断了（打包遗漏该文件、jar 未上
  classpath、typo、编译顺序问题），下游 `ad.*` 场景会从 `registered[]` 里**整体消失**
  ——而套件本身仍然自洽地跑完注册到的那些场景、判 GREEN。这是套件组装层的自洽假绿，
  SWALLOWED/TRUNCATED 两门结构性地管不到它。
- **为何是外部而非内部**：由编排器（而非游戏内 harness 自己）断言"这些名字必须出现"，
  不依赖套件自证——组装链路断裂时，游戏内 harness 本身没有任何信号可以感知"本该有
  一个 provider 没被发现"。
- **legacy 删除前置**（终审 Important，记录见本文件 SceneProvider 附录 + TODO.md）：
  legacy `@GameTest` 三胞胎（及 P1.5a 新增的 `ad.selfShaftDigUp`/`ad.descentYaw` 对应
  legacy 双胞胎）的删除条件之一就是这道门已武装并稳定通过——只有外部期望门在场，才能
  确认"legacy 删了之后 ad.\* 仍然真的在跑"，而不是套件组装链路悄悄断裂后自洽空转。
- 语义只新增一条外部检查、不改现有字段/退出码含义，契约仍 v0。

## originSlot 坐标钉扎（v0 附录，P1.5a）
`Scene.withOriginSlot(int slot)`（`Scene.java`/`TestkitHarness.assignSlots`）为确定性
敏感场景固定 grid 分配的坐标格，与其余场景的注册顺序解耦：

- **默认（自动）分配**：无 `withOriginSlot` 的场景按注册顺序分配自增 slot（0,1,2,...，
  跳过任何显式 pin 占用的号），`origin = (100000 + slot*512, 200, 100000)`
  （`TestkitHarness` 的 `GRID_X0`/`GRID_Z0`/`GRID_Y`/`GRID_STEP`）。**新增/删除任何一个
  场景都会让后续所有自动分配场景的坐标整体平移**——对绝大多数场景无所谓（arena 自
  包含，搬到哪个网格格子物理行为不变），但对双精度物理敏感的确定性场景不该把"坐标
  平移带来的浮点误差"和"真回归"混为一谈。
- **`withOriginSlot(int slot)`**：显式指定一个远高于自动分配范围的固定 slot，该场景
  的坐标从此与注册表增长完全解耦。P1.5a 例：`ad.descentYaw` 用 `slot=4000`
  （origin x = 100000 + 4000×512 = 2,148,000，在世界边界 ±30,000,000 内，永不与自动
  增长的注册表相撞）——这是 P0 探针事故（server 线程同步 IO 打破了这个场景的字节级
  确定性，A/B 定罪后修为异步 writer）之后对同一类"任何微小扰动都可能翻转轨迹"敏感度
  的延伸防护：坐标漂移本身不该成为另一个扰动源。
- **发布后不得变更**：一旦某个 pinned slot 的基线值（本任务记录 `ad.descentYaw` 的
  `sumAbsDyaw=871°`/`backSteps=53`，2026-07-16 测得，见两个 twin 文件的 javadoc）被
  写入文档或用作回归门槛，挪动 slot 号即视为破坏性变更——必须连带重新测量并更新
  黄金基线，不能静默挪动。
- Slot 号冲突（两个场景显式 pin 同一个 slot）在 harness 构造期抛
  `IllegalStateException`，与撞名门同一时机失败，同样按 exit 3 ENV 裁决。
- 本节语义只新增一种坐标分配方式（默认自动分配行为不变），不改现有字段/退出码含义，
  契约仍冻结在 v0，不升版。

## chunkRadius 声明武器（v0 附录，P1.5a）
`Scene.withChunkRadius(int r)`（默认 `r=1`）声明该场景需要多大的强制加载窗口，
PREP 阶段等 `(2r+1)×(2r+1)` 个区块全部 `hasChunkAt` 为真才放行场景体开始 tick
（`TestkitHarness.allChunksLoaded`/`forceChunks`）。

- **默认窗口**：`r=1` 覆盖以 origin 所在区块为中心的 3×3 区块，即 origin 相对
  方块坐标 `[-16,+31]`（origin 本身落在区块边界，`GRID_STEP=512` 是 16 的整数倍）。
  绝大多数场景足迹都在此窗口内。
- **何时需要更大半径**：场景的建造/寻路足迹超出默认窗口时必须显式
  `.withChunkRadius(r)`，否则场景体可能在部分区块未加载完成时开始建造/寻路，读到
  假的"空气"方块——这类失败长得像"物理漂移"或"环境问题"，而不是明显的加载竞态，
  极难与真回归区分。P1.5a 例：`ad.descentYaw` 用 `.withChunkRadius(2)`（窗口
  `[-32,+47]`）——其足迹本身仍在 `r=1` 窗口内，但作为字节确定性敏感场景显式选择
  更大余量，不与未来 footprint 微调抢占安全边际。
- **声明式，非自动推断**：harness 不会替场景猜测足迹；场景作者必须显式选择半径。
  写错（半径太小）的后果是该场景独有的环境类不稳定（PREP 超 `PREP_BUDGET_TICKS`
  会记 `ENV_FAIL`），不影响其他场景。
- 本节语义只新增一种可选窗口声明（默认 `r=1` 行为不变），不改现有字段/退出码含义，
  契约仍冻结在 v0，不升版。
