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

### 启动 tick 债 settle 屏障（v0 附录，task#88 / D1-T1）
新起的 `MinecraftServer` 带累积 tick 债，起步会以 ~3ms/tick 不节流地"追帧"直到追平，
才回落到稳定的 ~50ms 节奏。这段突发窗内，场景里墙钟绑定的等待（实体入索引等）为同一段
真实延迟要多吃 2-2.3× 的 tick——正是 `ad.entityLeash` 反复止血（`within` 60→120→180）的
根因。`TestkitCommon.onServerTick` 现在把 `harness.tick()` 的**转发**挡在一道 settle 屏障后：
连续 10 个服务器 tick 间距 ≥40ms（tick 债已排空）之前一律不转发；达成时打一条 INFO
`testkit: tick cadence settled after <N> server ticks (tick debt drained)`；安全阀=1200 tick
仍未稳定则强制开跑并打 WARN（永不无限挂起）。**只挡转发**：harness 构造、tick-pure 的
`within`/`PREP_BUDGET_TICKS` 语义全不变；因为 settle 前 `harness.tick()` 根本没被调过，所有
tick 预算天然从首个 settle 后 tick 起算。

对编排契约的影响：**done footer 语义、退出码判据、结果 JSONL 字节一律不变**（settle 只是
把首场景开跑推后若干 tick，不改任何场景的指标）；唯一可见差异=首场景开跑前 server log
多出一条上述 settle INFO 行（两个 loader 都出现）。

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
  文件内容一行一个实现类全限定名。现行例（P1.6 起 provider 移入 loader 共享的
  common 模块，一份注册服务所有 loader；P4a 起 provider 类与 service 文件均在
  `common/src/testmod` 源集=生产 jar 之外，类位于 `.scene` 子包——与 common main
  同包会触发 JPMS split-package 启动崩溃）：该文件单行为
  `net.magicterra.agent.bot.testkit.scene.AgentDriverScenes`（P1c 时曾位于 neoforge 模块，
  已随 P1.6 搬迁删除；全源码树内每个 provider 只允许一份 service 文件，重复注册会
  触发重名门 RED）。

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

## --expect-file 清单锚定门（v0 附录，P1.5b）
`t0.py --expect-file <path>`：把 `--expect-scene` 的期望名单从命令行搬进一个**签入版本库
的清单文件**（`scripts/testkit/expected-scenes-neoforge.txt`），用同一套集合比对逻辑武装
外部期望门——语义与 `--expect-scene` 完全一致，只是期望来源从「每次手敲命令行」变成
「随代码一起 review 的文件」，防止期望名单与已迁移场景清单漂移。

- **文件格式**：一行一个场景名；`#` 起注释（行内也算，`#` 之后整段丢弃）；空行忽略；
  一行内允许逗号分隔多名（与 `--expect-scene` 同解析）。解析后名字**必须**逐个出现在
  suite header 的 `registered[]` 里，否则整轮判 RED——报告行与 `--expect-scene` **完全
  相同**（`MISSING-EXPECTED: <name> not in registered`），两个来源在 `verdict.judge()`
  眼里没有区别。
- **同 commit 锚定规则**：每个迁移的 `ad.*` 场景**必须在添加该场景的同一个 commit 里**
  把名字加进此清单——清单与场景代码同 review、同落地。一个名字若同时缺席**清单**与
  `registered[]`，正是这道门要抓的「套件组装层自洽假绿」（SceneProvider 断链时 `ad.*`
  整体从注册消失、套件仍自洽判绿）。
- **大声失败（空期望集=错误退出，非静默降级）**：两种情形下编排器**不进入跑批**，直接
  `argparse` 报错退出 **exit 2**——①`--expect-file` 指向的文件不存在（`--expect-file
  not found: <path>`）；②文件存在但剥掉注释/空行后**零个名字**（`expectation source
  given but contains no scene names`；这条 union 后集合为空的检查对 `--expect-scene` 同样
  生效）。设计意图：一个「武装了期望门却期望空集」的运行必须是硬错误，绝不能被解读为
  「什么都不期望」而静默放行——那会把这道门本身悄悄卸掉。（注：这里的 exit 2 是编排器
  **启动前**的参数错误，语义上不同于游戏内跑批后的 canary-DEAD exit 2；两者都表达
  「结果不可信、别当真值」，此处是「根本没跑成」。）
- **与 `--expect-scene` 并集去重**：两者可同时给，期望集 = `sorted(set(from_file) |
  set(from_scene))`（`t0.py` main），重叠名字只计一次。`--expect-file` 是验收正门形态
  （期望入库、不会与迁移清单漂移），`--expect-scene` 降为 ad-hoc 一次性覆盖。
- 本节语义只把既有 `--expect-scene` 外部门的**期望来源**扩展为文件（并明确空期望集=
  大声错误），不新增线协议字段、不改退出码含义、报告行不变，契约仍冻结在 v0，不升版。

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

## TESTKIT_ENDPOINT attach 契约（v0 附录，P2c T1）
P2b `t1.py --hold` 让一个 fabric CLIENT 拓扑（integrated server + 真客户端）在场景
之外保持在线，供进程外消费者对它发 RPC。P2c 在此基础上新增一个**端点描述文件**，
让一个独立 JVM（JUnit 5 attach 模块，非本仓库 gradle daemon 的子进程）能发现这个
拓扑，而不必硬编码端口/世界名——`instrument_client.py --attach` 走的是同一个
`--hold` 拓扑，但它是本仓库内的 Python，直接读 `PORT_FILE` 即可；JUnit attach 是
第三方消费者，需要一份自描述、路径显式的契约文件。

### 端点文件 schema（v1，冻结）
`<RUN_DIR>/testkit-endpoint.json`（`RUN_DIR` 按 loader 解析，fabric 现为
`fabric/run-t1/`），UTF-8 JSON 单对象，逐键：

```json
{
  "version": 1,
  "topology": "integrated_plus_client",
  "loader": "fabric",
  "rpcHost": "127.0.0.1",
  "rpcPort": 39843,
  "worldName": "TestkitT1",
  "holdPid": 12345,
  "writtenAtEpochMs": 1752700000000
}
```

- `version`：schema 版本号，当前恒 `1`；破坏性改键需升版号（同本契约文件自身
  "变更需升 v1" 的纪律）。
- `topology`：拓扑形状枚举 `{integrated_plus_client, dedicated_plus_client}`。T1
  `t1.py --hold` 写 `"integrated_plus_client"`（客户端内置集成服务器）；T2
  `t2.py --hold` 写 `"dedicated_plus_client"`（客户端 multiplayer 直连到一台独立专用
  服务器，见 P3a T2 附录）。两者共享同一份 v1 schema——`rpcPort` 恒为**客户端**面
  RPC 端口（JUnit UI 场景只打客户端），拓扑差异由此键和下面的可选 `serverRpcPort`
  表达，`Endpoint.wsUri()` 与拓扑无关。
- `loader`：`t1.py` 写入时硬编码 `"fabric"`（P2c T4 引入 `--loader` 泛化后按实际
  loader 参数写入；schema 本身不变，只是取值从常量变为参数）。
- `rpcHost`：恒 `"127.0.0.1"`——client 拓扑的 agent-rpc websocket 只监听本机回环，
  从未对外暴露过。
- `rpcPort`：`t1.py` 通过既有 `PORT_FILE`/`discover_port` 机制发现的实际端口
  （ephemeral，每次 `--hold` 不同）。
- `worldName`：恒 `"TestkitT1"`（`t1.py` 的 `WORLD_NAME` 常量，与场景清单/verdict
  裁决共享同一个世界名）。
- `holdPid`：`t1.py` 追踪的 client 进程 pid（`launch_client()` 返回并被
  `stop_client()`/`kill_pid()` 操作的同一个 `Popen` 句柄的 `.pid`——即 gradle
  wrapper 子进程，不是 gradle 二次 fork 出的 Knot 客户端 JVM 本体的 pid；
  `sweep_client_jvms()` 按 ps 命令行匹配另行清扫那个 JVM，不是一个被追踪的独立
  变量）。此字段是遥测/人工排障用途，**不是**探活依据（见下）。
- `writtenAtEpochMs`：`t1.py` 写文件那一刻的墙钟毫秒时间戳（`int(time.time()*1000)`）。
  **仅供参考，不是新鲜度判据**——见下条。
- `serverRpcPort`（**可选，v1 兼容扩展，P3a T2**）：专用服务器的 agent-rpc 端口。仅
  `dedicated_plus_client`（`t2.py --hold`）端点写此键；`integrated_plus_client`
  （`t1.py --hold`）端点**不写**。`Endpoint.java` 以 `Integer` optional 解析——存在则取值、
  缺失则 `null`——故 T1 端点照常解析、冻结的必需 8 键契约不破。当前的 JUnit UI 场景不消费
  此键（它们只打 `rpcPort` 客户端面）；它为将来的双 socket 消费者（P3a T4 双端仪表）预留
  服务器面地址。**未知键一律容忍**（前向兼容）：`Endpoint.parse` 只读它认识的键，将来加键
  不破旧读者。

### 生命周期
- **写入时机**：仅 `--hold` 路径，且仅在客户端**已进世界**（`drive_into_world`
  完成）且 RPC 端口**已发现**之后——即拓扑真正可用、可以被外部消费者连接的那一刻，
  不是进程启动的那一刻。写完立即向 stdout 打印一行
  `export TESTKIT_ENDPOINT=<端点文件绝对路径>`，供人工/CI 把这一行 eval 进环境。
- **非 `--hold`（scored run）路径不写**：scored run 没有可供外部 attach 的持续在线
  拓扑（跑完场景即 halt 集成服务器、teardown），写一个指向即将消失的端口的端点文件
  只会制造陷阱，故这条路径上完全没有调用点。
- **删除时机**：teardown 的既有 `finally`（与 Xvfb 进程、`saves/TestkitT1` 世界副本
  清理同一处）无条件尝试删除端点文件，容忍它不存在（scored run 从未写过、或 hold
  run 在进世界之前就失败退出）。这条 `finally` 覆盖**每一条**退出路径，包括
  `--hold` 的文档化释放方式 Ctrl-C（`KeyboardInterrupt` 不被内层
  `except Exception` 吞掉，照样穿过所有 `finally` 层——`stop_client` 已经依赖这个
  事实，端点删除复用同一保证）。**陈旧端点文件是最危险的残留**——它会让一个后来的
  JUnit 进程连上一个早已不存在（或更糟，被无关新进程占用同一端口）的拓扑；宁可
  "探活失败、大声报错"，不可"文件还在、指向死链接"。
- **探活是唯一真判据**：`writtenAtEpochMs` 不供 JUnit 侧判新鲜度用——文件存在
  且时间戳看起来"新"完全不保证它指向的进程仍然活着（例如 `--hold` 被外部信号
  杀死但来不及跑 `finally`）。JUnit attach 侧必须实际发一次 RPC（P2c T2 约定
  `mc.system.version` 一发 5s 超时）作为"这个端点真的可用"的唯一证明；探活失败
  必须 fail-fast 大声报错（提示原文含 `python3 scripts/testkit/t1.py --hold`），
  不得静默 skip。
- **串行租约**：一次 `--hold` 只支持一个拓扑实例服务一个 attach 客户端——`t1.py`
  不做多实例端口/世界隔离，`RUN_DIR`/`WORLD_NAME`/`ENDPOINT_FILE` 全是进程级单例
  路径。并发跑第二个 `--hold` 会互相踩世界目录和端点文件；这是当前明确的形状边界，
  不是意外行为，多租户需求超出本轮范围。
- 本节新增一份独立于结果文件线协议的描述性文件（不改 `testkit-results.jsonl`
  格式、不改任何退出码含义），契约仍冻结在 v0，不升版。

## T2 双进程拓扑（dedicated + client，v0 附录，P3a T2/T3）
T0/T1 都在**单进程**里跑套件（T0 专用服务器自跑；T1 客户端内置集成服务器）。T2
证明真正的**生产拓扑**：一台朴素专用服务器（`t2Server`，工作目录 `<loader>/run-t2`，
gradle 任务 `:<loader>:runT2Server`）+ 一个独立真客户端（复用 T1 的
`:<loader>:runTestkitClient`/`run-t1`，Xvfb 下），客户端经 multiplayer 直连服务器。
`t2.py`（`--loader {fabric,neoforge}`，默认 fabric）是编排器。

### 双进程启动/停止协议
- **服务器**：`t2.py` 每次 provision 时**生成并钉死** `run-t2/server.properties`
  （`online-mode=false` 让离线开发客户端可入；固定 `server-port` 让 `t2.py` 知道直连
  地址；flat 世界；`server-port` 按 loader 取自 `SERVER_PORTS`，fabric=25597、
  neoforge=25596，均与 dogfood 的 25599 及彼此互异，故两 loader 可并跑）。首跑先
  mint 一个 byte-clean 世界模板（autorun OFF，不跑场景），归档到
  `.t2-world-template-<loader>`；scored/hold 每次从模板拷一份 `run-t2/world`。
- **客户端**：复用 T1 的 `run-t1`/`launch_client`（byte-identical，不 fork），autorun OFF。
- **启动次序**：先起服务器并**门控世界就绪**（`mc.observe.player` 首个不报错的回复==
  `SERVER_STARTED`+overworld 就绪；`mc.wait.worldReady` 只对客户端有效、专用服务器上会抛，
  故不能用它当门），再起客户端——避免直连撞上未加载的世界。
- **停止次序**（`finally`，覆盖**每一条**退出路径，含 Ctrl-C；禁 `pkill`）：先客户端
  （best-effort 断连→按 PID 杀+按命令行 sweep 客户端 JVM），后服务器（SIGTERM gradle
  进程组→有界等待→按 `/proc/<pid>/cwd==run-t2` sweep 专用服务器 JVM），再删端点描述文件、
  删 `run-t2/world`、删两个 `agent-rpc.port`、杀 Xvfb。

### 双端口发现
两条 agent-rpc websocket，各写各的 `agent-rpc.port`：服务器面在
`run-t2/agent-rpc.port`，客户端面在 `run-t1/agent-rpc.port`。`t2.py` 用同一
`discover_port`/`connect` 机制分别读回，同一 event loop 里同时握住两条 socket 做
**双端探针**（客户端 `mc.client.player` 有 pos **且** 服务器 `mc.observe.player`
`present:true`——即专用服务器 PlayerList 里有一个真玩家）。

### `mc.test.run` 语义（场景执行入口，见 P3a T1 附录 / `TestRunVerb`）
T2 服务器 autorun OFF，套件不在 boot 自跑。双端探针过后，`t2.py` 经**服务器面** RPC 发一次
`mc.test.run`（裸信封，隐藏 verb 无参），断言 `{accepted:true, scenes:N}`（N=注册场景数，
**含金丝雀**）。此 verb 幂等：套件已跑/在跑则大声报错不重跑；JSONL done footer 仍是唯一完成
信号。harness 把结果写到 `run-t2/testkit-results.jsonl`（相对服务器工作目录），跑完 `halt()`
服务器。`t2.py` 轮询该文件的 done footer（纯文件轮询，socket 无关），再用 `verdict.py`
+ `expected-scenes-<loader>.txt`（对账服务器 header 的 `registered[]`）裁决，退出码
0/1/2/3（与 T0/T1 同义：GREEN/RED/DEAD/ENV）。三大 byte 金值（descentYaw、
selfShaftDigUp worstBackslide、gearScope 属性）在**场景内部**断言，故场景 PASS == byte 命中。

### `--hold` 端点（拓扑枚举 + serverRpcPort）
`t2.py --hold`：不跑场景，双端探针过后写 `run-t2/testkit-endpoint.json`（schema v1，
`topology:"dedicated_plus_client"`、`rpcPort`=**客户端** RPC 端口、可选
`serverRpcPort`=**服务器** RPC 端口），打印 `export TESTKIT_ENDPOINT=<绝对路径>`，保持双进程
在线直到 Ctrl-C（`finally` 无条件删端点文件）。JUnit UI 场景 attach 到 `rpcPort` 客户端面即可
（它们只打客户端），与 T1 端点唯一差别是 topology 取值和多出的可选 `serverRpcPort`；schema
不升版，冻结的必需 8 键不变。

## 客户端进程池 `pool.py`（v0 附录，P3b T2）
`t1.py --hold` / `t2.py --hold` 每次都从零冷启一套拓扑（T1 ~30-90s，T2 数分钟），再 publish
一个 `TESTKIT_ENDPOINT` 端点、idle 到 Ctrl-C。`scripts/testkit/pool.py` 是这套 `--hold`+端点
契约之上的**进程池**：把一套拓扑跨多次调用**保活**，让 `instrument_client.py --attach` / JUnit
attach 模块以**秒级**连上，而不是每次冷启。

CLI：`pool.py {ensure|status|stop} --topology {t1,t2} --loader {fabric,neoforge}`（默认 t1/fabric）。
端点路径不硬编码——`t1` 走 `t1.resolve_loader(loader).endpoint_file`（`<loader>/run-t1/testkit-endpoint.json`），
`t2` 走 `t2.resolve_t2(loader).endpoint_file`（`<loader>/run-t2/testkit-endpoint.json`），单一真源。
**禁 pkill**，所有进程操作只针对显式记录的 PID。

### 状态文件 `scripts/testkit/.pool-state.json`（gitignored）
池自己记录它启动过的每一套 hold，供 `stop` 按显式 PID 释放。UTF-8 JSON 单对象，原子写
（`.tmp`→`os.replace`）：

```json
{
  "version": 1,
  "entries": {
    "t1/fabric": {
      "pid": 2250123,
      "topology": "t1",
      "loader": "fabric",
      "startedAtEpochMs": 1752800000000,
      "log": "/abs/.../fabric/run-t1/pool-hold.log"
    }
  }
}
```

- key = `"<topology>/<loader>"`；`pid` = 池 detach 出去的 `t1.py/t2.py --hold` **Python 进程** PID
  （这正是 `stop` SIGINT 的目标——`--hold` 的文档化释放路径 Ctrl-C）。注意它**不是**端点文件里那个
  `holdPid`（后者是 `t1.py` 内部追踪的 gradle wrapper 子进程 PID，语义不同）——池只管自己启动的进程。
- `log` = 该 hold 的 stdout/stderr 落盘位置（run 目录下 `pool-hold.log`）。
- 文件缺失/损坏一律降级为空池（fresh checkout 上 `stop`/`status` 照常工作），从不抛。
- **并发写用 flock 串行化**：状态文件的每一次 read-modify-write（`put_entry`/`del_entry`）都在
  `scripts/testkit/.pool-state.lock` 上持有 `fcntl.flock(LOCK_EX)` 的临界区内完成，且**在锁内重新
  load** 后再改再存。否则两个针对**不同 key** 的 `ensure`（如 t1/fabric + t2/fabric，都是数分钟冷启）
  会 load-load-save-save 交错，后写者抹掉前写者的 PID——被启动的 hold 仍在跑却丢了 PID，`stop`
  永远释放不掉它。锁文件同样 gitignored。

### `ensure` 语义（幂等：活→秒回，死→清→新起）
1. 端点文件**存在** 且 一次裸 RPC `mc.system.version` 探活成功（对 `rpcPort`；T2 另探 `serverRpcPort`，
   两面都须活）→ 打印端点路径 + `reused` + `export TESTKIT_ENDPOINT=…`，exit 0。
2. 否则先**清残留**（陈旧端点文件；池状态文件里记录过的旧 hold PID——SIGINT→SIGKILL 按显式 PID），
   再把 `--hold` 作为 **detached 子进程**启动（`start_new_session=True`，故 Ctrl-C 打在 pool.py 上不会
   波及 hold；stdin 关闭、stdout/stderr→run 目录 log），把 `{pid,topology,loader,startedAtEpochMs,log}`
   记进状态文件，**有界轮询**端点文件出现+探活（t1 240s / t2 360s 预算），成功→打印路径 + `started` +
   export，exit 0。
3. 预算内没起来（或 hold 进程提前死）→ 按记录 PID 杀掉刚启动的 hold（SIGINT 先、宽限后 SIGKILL），
   删状态项，**exit 3（ENV）**。

**探活是唯一真判据**（沿 attach 契约）：端点文件存在 ≠ 指向的进程还活着，必须实发一次 `mc.system.version`
（~5s 超时）证明可用。

### `status` 语义
遍历全部 topology×loader（状态文件 + 磁盘端点文件两来源），按**探活**打印
`alive`/`stale`/`absent`（不是只看文件是否存在），并标注 `pool-managed`（有状态项）/`orphan`（有端点无状态项）。
恒 exit 0。

### `stop` 语义 + orphan 拒绝规则
决策取自四元组（有状态项？记录的 PID 还活着？磁盘有端点？端点探活？）：
- 有状态项 + **PID 活** → SIGINT 记录的 hold PID（hold 的 `finally` 会删端点文件）→ 有界等端点文件
  消失 → 宽限后 SIGKILL（并由池自己兜底删端点残留，安全：这是我们自己的活 hold）→ 删状态项。
- 有状态项 + **PID 已死** → 记录已陈旧，只删状态项；再看端点：
  - 端点**探活成功** → **大声拒绝**：这个端点现在由**另一个**进程（如手动 `--hold`）发布，PID 不是
    我们的，**绝不删端点/杀进程**——只清掉我们自己那条死记录。
  - 端点**探死/不存在** → 纯残留，直接删端点（若在）+ 清记录，**不等宽限**（短路）。
- 无状态项但磁盘上有端点：探活——
  - **活**：**大声拒绝**（同上，非池启动，PID 不该靠猜）。
  - **死**：陈旧残留，直接删端点文件。
- 无状态项且无端点：no-op。

关键不变式：`stop` **绝不**因为一条陈旧状态项就盲删/盲杀一个端点——记录的 PID 一旦已死，端点必先
重新探活；探活成功即视为「不是我们的」而拒绝。

### 两条工作流
```bash
# 工作流 A：pool 保活 T1 → instrument_client attach（秒级复连）
python3 scripts/testkit/pool.py ensure --topology t1        # started（或 reused）
eval "$(python3 scripts/testkit/pool.py ensure --topology t1 | grep ^export)"
TESTKIT_ENDPOINT=$TESTKIT_ENDPOINT python3 scripts/testkit/instrument_client.py --attach
python3 scripts/testkit/pool.py stop --topology t1          # 释放

# 工作流 B：pool 保活 T2 → gradle JUnit attach（双 socket 生产拓扑）
python3 scripts/testkit/pool.py ensure --topology t2        # started（或 reused）
export TESTKIT_ENDPOINT="$(python3 scripts/testkit/pool.py ensure --topology t2 | grep ^export | cut -d= -f2)"
./gradlew :testkit-junit:test    # JUnit attach 模块读 TESTKIT_ENDPOINT
python3 scripts/testkit/pool.py stop --topology t2
```

- **串行租约**（继承 `--hold` 的形状边界）：一套 topology×loader 同一时刻只保活一个实例
  （`RUN_DIR`/`WORLD_NAME`/端点文件都是进程级单例路径）；池不做多实例隔离。
- 本节新增一个**独立于结果文件线协议**的编排辅助工具（不改 `testkit-results.jsonl` 格式、端点
  schema、任何退出码含义），契约仍冻结在 v0，不升版。
