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
| 1 | RED：非金丝雀失败/超时/被吞/漂移记录，或缺尾 |
| 2 | DEAD：任一金丝雀判错——框架抓失败的能力失效，整轮结果作废 |
| 3 | ENV：起不来 / 缺结果文件 / 缺头 |

`required=false` 场景允许 FAIL/TIMEOUT（GREEN 不受影响，报告行标 `fail(optional)`），
但**不允许被吞**：注册即必须有场景记录，缺记录 = RED，与 required 无关——被吞是
框架完整性违规（#85 病），不是测试结果。

## 游戏内时序契约
结果写盘只在场景边界（P0 探针事故教训，agent-driver 926396d）；
确定性敏感场景入驻（P1c）前须复核，必要时改异步 writer。
