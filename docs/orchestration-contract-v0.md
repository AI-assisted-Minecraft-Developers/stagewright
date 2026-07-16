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
  `net.magicterra.agent_driver.testkit.AgentDriverScenes`。

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
  （不计入裁决），不判 RED。这是刻意的前向兼容——v0 契约允许旧版本 harness（P1a/
  P1b 时期，尚未加 `scenes` 字段）产出的结果文件仍能被新版编排器正常裁决，字段
  缺失本身不是完整性违规。
- 本节语义只收紧（新增一条完整性门），不放松、不改既有字段/退出码含义，故契约
  仍冻结在 v0，不升版。
