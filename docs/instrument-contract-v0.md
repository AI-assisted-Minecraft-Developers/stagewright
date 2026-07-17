# 仪表契约 v0（T0 依赖面子集，冻结 2026-07-16）

分支：`feature/executor-permove-ascend`。落地 commits：`a53d8dc`（verdict 抽取）、
`a540771`（骨架+run 配置+金丝雀）、`ee95891`（batch A：路由/schema/脚本）、
`11088a5`（batch B：world/obs/events/wait）、`a436c61`（fabric 就绪探针竞态修复，
Task 5 Step 1 现场发现，见下方「运行器时序」）。

运行器：`python3 scripts/testkit/instrument.py --loader {neoforge|fabric}`。

裸 RPC 直打 `AgentApi.route()`，跑在 agent-driver 裸专服（
`:<loader>:runContractServer`，runDir `<loader>/run-contract/`，RPC 端口
ephemeral 经 `agent-rpc.port` 发现）。退出码同编排契约 v0：
0 GREEN / 1 RED / 2 DEAD（金丝雀误判）/ 3 ENV。

信任链（spec §4）：本套件绿 → testkit setup/断言可信 → 行为面测试可信。本套件
故意独立于 testkit 自身的断言栈（`common/src/main/java/net/magicterra/testkit`）
之外——它验的是 testkit 所依赖的 agent-driver 仪表面本身，不能用被验对象的代码
去验证被验对象。

## 运行器时序（Task 5 现场发现，已修复）

`launch()` 的就绪门原探针 `mc.system.version`——该方法不调用
`AgentApi.level()`，RPC 在 `onServerStarting` 起来即可回应,比
`onServerStarted`（`AgentApi.attachServer()` 所在）早。fabric 首次全量 17 项
真跑（Task 5 Step 1）在快速 flat-world 首启（RPC 监听到 `Done` 仅约 1s）下
输了这场竞态：探针在 attach 前就返回就绪，8/17 项touch `api.level()` 的检查
（world/obs/events 族）全部 `FAIL — AgentApi not attached to a server`；
7 项不需要 attach 的检查（route/schema/script/wait 族）正常 PASS。修复
（`a436c61`）把就绪探针换成 `mc.observe.player`——只读、无副作用（空
PlayerList 上返回 `{present:false}`，从不因内容抛错），但其函数体第一行就是
`api.level()`（显式「assert attached」门），因此等它成功才是真正的「已 attach」
门,不只是「传输层已通」门。fabric 复跑后 17/17 PASS，`VERDICT: GREEN`。

## 检查清单（21 + 2 金丝雀）

> 检查 18-21 是 P2a（verb 管线 + #280 收口）追加,见文末「P2a 附录」。语义只收紧
> （封闭 schema、大声拒键、统一校验),仍是 v0。

| # | 检查名 | 断言什么 | 钉住哪条病历 |
|---|---|---|---|
| 1 | `system.versionShape` | `mc.system.version` 返回 `modid=="agent_driver"` 且 `uptimeMs` 是非负整数 | 身份/契约基线——信任链起点,后续所有检查隐式依赖 route 本身能回应 |
| 2 | `route.unknownMethod` | 未知 method → `error` 非空且含 `"unknown method"`,不静默回 `result` | 大声失败基线（`AgentApi.route()` 未知分派必须显式报错,不能被吞成空结果） |
| 3 | `route.invalidParams.missingKey` | `mc.system.waitTicks{}`（缺必填 `ticks`）→ error 含 `invalid params`/`ticks`（Task 3 从 `mc.observe.eventsSince` 换过来,见下方永久断言台账） | 封闭 schema 必填键校验不能被绕过 |
| 4 | `route.invalidParams.wrongType` | `waitTicks{ticks:"not-a-number"}` → error 存在（同上换过来） | schema 类型校验存在 |
| 5 | `route.invalidParams.unknownKey` | `waitTicks{ticks:1,bogusKey:1}` → error 含 `unexpected key`/`invalid params` | **#280 静默吞病族**——封闭 schema 必须拒绝未知键,不能悄悄丢弃 |
| 6 | `route.clientOnlyVerb` | `mc.bot.status` 在专服上 → error 含 `client only`,不静默返回/no-op | **#280 静默吞病族**——client-only verb 在专服上必须大声失败,不能悄悄不作为 |
| 7 | `script.evalParity` | `Agent.invoke('mc.system.version').modid` 走 in-JVM `invokeJson` 路由,结果与外部 RPC 传输一致 | schema 单源校验（`project_schema_single_source_merged`）——两条调用路径不能分叉出不同行为 |
| 8 | `world.setblockQueryReadback` | vanilla `/setblock` 写入 → `mc.query` 读回同一方块同一坐标 | 双源对账基线（spec §4.2）——driver 读路径与 vanilla 写路径必须一致,不能有独立于游戏状态的镜像态 |
| 9 | `world.fillCount` | `mc.action.fill` 3×3×3=27 格 → `placed==27` 且 `mc.query` 读回 27 个 | 双源对账——批量写入计数与读回计数必须双向一致 |
| 10 | `world.snapshotRestore` | snapshot → 篡改 → restore(discard) → 标记块消失 | world 快照/恢复往返干净性（无残留） |
| 11 | `obs.containerDurability` | `diamond_pickaxe[damage=123]` → `mc.observe.container` 读回 `damage=123 maxDamage=1561 durability=1438` 三字段 | **永久断言 #42（工具耐久不可见）**——耐久必须作为可观测三元组暴露,不能只有内部状态 |
| 12 | `obs.playerAbsentPin` | 空 PlayerList 专服上 `mc.observe.player` → `{present:false}`,不抛异常 | 文档化语义钉子——为 #41/#45/#55（依赖真玩家的深断言,P2 才能补）和 avatar FakePlayer 不入 PlayerList 的已知缺口划清当前基线（见下方已知缺口） |
| 13 | `obs.entityQuery` | `summon zombie(NoAI)` → `mc.query{q:entities}` 精确读回 1 个,且 `health` 是正数 | 实体查询基础可用性——为后续依赖生物观测的行为面测试打底 |
| 14 | `events.commandResult` | 非 `setblock` 快速路径命令（`/fill`）→ 走 Brigadier 分支 → 产出 `command.result` 事件,`success:true` | 事件发射路径分叉钉子（Task 4 发现：纯 `setblock` 走快速路径,`ActionApi.java:126-163` 在 `emit` 之前 `return`,永远不产生该事件——批 B 换成 `/fill` 强制走 Brigadier 分支才拿到真事件） |
| 15 | `events.cursorMonotonic` | 两次 `mc.observe.cursor`（裸 `long`,非 `{"cursor":N}`,Task 4 发现的返回形状坑）在命令之间严格递增 | 事件游标单调性——依赖游标做增量拉取的调用方（`mc.observe.eventsSince`/`mc.wait.*`）的正确性前提 |
| 16 | `wait.ticks` | `mc.system.waitTicks{ticks:10}` 返回 `{waited:10}`（非布尔,Task 4 发现的精确返回形状）且真实阻塞 ≥300ms | tick 级阻塞等待的真实性——不能立即返回假装等过了 |
| 17 | `wait.conditionValue` | `mc.wait.condition` 按 `field`(`slots.2.count`)+`value`(7) 轮询直到满足,`satisfied:true value:7` | 条件等待的值匹配语义——为策略层「等到某个具体值」的编排原语打底 |
| 金丝雀 1 | `canary.mustFail` | 必须被判为 FAIL,否则 `judge()` → DEAD | 金丝雀条款（spec §5）——框架抓失败的能力本身在被验证 |
| 金丝雀 2 | `canary.mustSwallow` | 注册但从不执行,不得出现任何记录,出现记录 = DEAD | 金丝雀条款——**#85 静默吞测试** 的镜像验证:本门必须能抓到「有记录但不该执行」和「该执行却被吞」两种反向违规 |

## 永久断言台账

- #42 工具耐久可见性 → `obs.containerDurability`（damage/maxDamage/durability
  三字段）。
- #280 病族（静默吞）→ `route.clientOnlyVerb`（client-only verb 必须大声失败）
  + `route.invalidParams.unknownKey`（封闭 schema 拒未知键）。

## 已知缺口（未来断言，P2）

- #41 全背包 36 槽、#45 攻击冷却、#55 伤害源：需要 PlayerList 内的真玩家（T1/T2）。
- `/agentserver` FakePlayer 对 `mc.observe.player` 不可见（不入 PlayerList）——
  avatar 可观察性接线后补断言。
- `mc.bot.setting` 未知键静默吞（applied/rejected 均不出现）——P2 修复+断言。

## 双 loader 验收记录（2026-07-16）

- fabric 首跑（17 项真跑）：`VERDICT: RED`,8/17 FAIL,全部 `AgentApi not
  attached to a server`——就绪探针竞态（见上方「运行器时序」）,非产品缺陷。
  `agent-rpc.port` 与 neoforge 侧不撞车（fabric 40703 / neoforge 各自独立
  ephemeral 端口）,Task 2 Step 2 的 `configureEach` 端口覆盖风险未兑现。
- 就绪探针修复（`a436c61`）后 fabric 复跑：`VERDICT: GREEN`,17/17 PASS,
  exit=0。
- 门自证（临时改错,均已 `git checkout --` 还原,零 commit 残留）：
  - 改 `check_version_shape` 断言（`!= 'agent_driver'` → `!= 'nonsense'`）→
    neoforge → `VERDICT: RED`,`FAIL: 'system.versionShape' -> FAIL —
    modid='agent_driver' != 'agent_driver'`,exit=1。
  - 改 `canary_must_fail` 为 `return None` → neoforge → `VERDICT: DEAD`,
    `DEAD: canary 'canary.mustFail' -> PASS, expected FAIL`,exit=2。
  - 两次均 `git checkout -- scripts/testkit/instrument.py` 还原,`git status`
    确认干净后重跑 → `VERDICT: GREEN`,17/17 PASS,exit=0。
- 双 loader 确定性重跑（`neoforge && fabric`）：两轮均 `VERDICT: GREEN`,
  combined exit=0。逐项 17 检查 + 2 金丝雀在两个 loader 上行为一致（仅
  `wallMs` 计时有正常抖动）。

详细现场记录（含每次运行的完整终端输出）见
`.superpowers/sdd/task-5-report.md`。

## P2a 附录 — verb 注册管线 + #280 收口（追加检查 18-21）

落地 commits：`14c6541`+`35c98c0`（配对注册 + 命名空间政策 + schema-less 派发洞收口）、
`39d99e5`（#280 根修：SettingsRegistry 单源 + 封闭 schema + apply 大声拒）、
`6de3d97`+`ae9982d`（`mc.test.reset` verb + `ad.settingRegistryClosed` 场景）、
本 Task（instrument.py 追加检查 + 文档）。四腿 headless 可测子集；客户端端到端 A/B
（真开客户端打未知键 / reset 完整性 3 跑）留 P2b 的 T1 仪表扩展。

### 命名空间政策（配对注册入口 enforcement）

- `mc.*` 保留给驱动层。
- `mc.test.*` 授予 testkit-runtime;`mc.test.yaml` 为既有驱动层 harness verb,**祖父条款**。
- 第三方一律 `<modid>.*`。
- 新配对注册入口 `ToolCatalog.registerVerb(schema, handler)` 对违反者**注册时即抛**;
  且拒绝劫持驱动层既有 baseline 名（verb-hijack guard,review I-1）。

### 配对注册契约（schema-less 派发不再静默跳过）

`registerVerb` 原子完成 schema 供给 + route 注册。派发时 schema 缺失 = `route()`
大声抛 `IllegalStateException`（不再 `if (s != null)` 静默跳过——那是 #280 同形洞）。
开机 `requireSchemasFor` 保证 boot 全集有 schema;post-boot 只有配对入口可加 route,
故该异常只可能命中「绕过配对入口直接 addRoute 且未声明 ToolSchema」的编程错误。

### #280 收口语义

- **单源**：`SettingsRegistry`（从 `SettingsSnapshot` 的键枚举——手列键 + 反射补全
  `public static volatile` BotConfig 原始类型字段——派生),229 键。新 BotConfig 旗标
  自动进注册表（#280 病根=新旗标漏 schema)。
- **封闭 schema**：`mc.bot.setting` 的 input schema = 全键 prop + `additionalProperties(false)`。
- **all-or-nothing**：一次调用含**任一**未知键 → 整个调用被拒,**什么都不 apply**
  （A/B 脚本大声失败而非静默半应用)。已知键正常 apply 不受影响。
- **inert[] 漂移报告**：apply 键集 ⊆ 注册表键集的自检;差集（若有）以 `inert[]` 报告,不静默。
- **校验统一（transport/side-uniform)**：校验在 `route()` 派发时跑（`AgentApi.route`:
  先 `paramsValidator.validate`,后 handler),**先于** client-only 门。故专服上打未知键
  也得 VALIDATOR 的 unexpected-key 错,不是 client-only 错——见检查 19 的现场定序发现。

### 检查清单（追加）

| # | 检查名 | 断言什么 | 钉住哪条 |
|---|---|---|---|
| 18 | `catalog.settingSchemaClosed` | 经 **MCP `tools/list`**（HTTP,`agent-mcp.port`）读回 `mc.bot.setting` 的 `inputSchema`:`type=="object"`、`additionalProperties` 非 `true`（=封闭)、`properties` 数 ≥ 200 | #280 结构半——封闭 schema + 单源全键集（229；<200 抓丢反射补全 pass） |
| 19 | `route.settingUnknownKey` | 专服裸 RPC 打 `mc.bot.setting{definitelyNotAKnob:true}` → error 含 `unexpected key` + 键名,**非** client-only | #280 行为半 + 校验统一定序（validator 先于 client-only 门) |
| 20 | `route.testResetClientOnly` | 专服裸 RPC 打 `mc.test.reset`（空参过校验）→ error 含 `client only` + `mc.test.reset`,不静默 no-op | client-only verb 在专服大声失败 |
| 21 | `route.testResetSchemaPaired` | 专服打 `mc.test.reset{nope:true}` → error 含 `unexpected key` + `nope`,**先于** client-only | 配对注册元证明（schema 存在 AND 封闭 AND 校验统一)+ schema-less 派发洞回归 |

### 现场定序发现（检查 19 的诚实记录）

计划把「validator 先于 client-only」当作统一契约的读法。**现场证实即此序**:
`AgentApi.route()`（common `AgentApi.java`）先跑 `paramsValidator.validate(method, p)`,
再 `fn.apply(p)`——而 `mc.bot.setting` 的 handler `requireBot()` 在专服上才抛 client-only。
故专服上未知键先撞 SchemaValidator 的 `unexpected key`（`invalid params for mc.bot.setting:
unexpected key 'definitelyNotAKnob'`),client-only 门根本没到。两 loader 全量 GREEN 复现。

### tools/list 封闭对象渲染坑（检查 18 的诚实记录）

`additionalProperties(false)` 把内部字段置 **null**（`Schema.java`:`allow ? Boolean.TRUE : null`),
Obj codec 以 `optionalFieldOf("additionalProperties")` 渲染,故**封闭对象在 tools/list 里
省略该键**——绝不渲染成字面 `false`。封闭约定 = `SchemaValidator`（`open ==
additionalProperties is Boolean.TRUE`):**缺失或 false = 封闭,仅 `true` = 开放**。检查 18
即断言「`additionalProperties` 非 `true`」而非「== false」。此为 v0 契约的一部分。

### schema 读回路径的诚实说明

裸 RPC `/rpc`（`AgentApi.route`）**不暴露** schema 目录;`mc.script.eval` 也读不到——
此 Rhino fork 剥了 `Packages` 全局,JS 无法按名解析 `ToolCatalog`（与沙箱 denylist 无关）。
schema 的结构化读回唯一诚实路径 = MCP `tools/list` HTTP 端点（`ToolSchema.mcpTool` →
`Schemas.render`,与 route 层 `SchemaValidator` 同一 typed Schema,单源)。专服在
`onServerStarting`（`AgentDriverCommon.ensureMcpUp`)随 RPC 一起起 MCP,端口写
`agent-mcp.port`。检查 18 即走此路。

现场记录（五门验收完整输出）见 `.superpowers/sdd/task-4-report.md`。
