# 进程外场景（attached scripts）设计

> **状态（2026-08-06）：§8 的 0–4 已落地并在第三方上跑通。** ATM10 一条命令跑完
> 3 条 attached + 30 条进程内，worst-wins **GREEN**，exit 0；worlddriver 两个 loader、
> Twilight Forest 全绿（纯搬家那一步的"行为零变化"要求）。剩下 5–8：`mc.test.end`
> 与 gradle plugin 的 `attachedScenes`（CLI 自己拥有进程树，杀掉即可，所以这条只挡
> Gradle 那条链）、一致性门、以及第二条"只有进程外能做到"的自测（跨客户端进程边界）。
>
> 落地时踩到的三个坑记在 §8 末——每一个都是**"看起来通过了/看起来是别的问题"**的形状，
> 没有一条是 review 看得出来的。
>
> 2026-08-05 修订。第一版的动词映射表和 §6 的门集成是对着直觉写的，没有对着代码核；
> 这一版每条断言都核过实现，改掉的地方在原处标了**（修订）**并说明原来错在哪 ——
> 因为"曾经以为能搬"本身是这份设计最容易重犯的错误。

## 0. 一句话

把 JS 场景解释器搬出游戏进程，让它 attach 到一个 Hold、把 `driver(method, params)`
接到 RPC 上。**这是加一种，不是换一种**：进程内场景那套 arena / 世界钉 / 泄漏审计 /
逐 tick 确定性搬不出去，理由在 §3。

## 1. 三个"家"，按代码跑在哪里划分

能力由**代码跑在哪**决定，不由用什么语言写决定。今天有两个家，这份设计加第三个。

| 家 | 语言 | 跑在 | 独有能力 | 结构上做不到 |
|---|---|---|---|---|
| **进程内场景** | JS（`config/stagewright/scenes/*.js`）或 Java（`@SceneDef`） | 服务端线程，内联在 tick 上 | arena（分配的原点 + 强制加载区块）、世界钉、arena 审计、tick 预算、字节确定性 | 阻塞、跨进程、在 dedicated 拓扑里碰客户端 |
| **客户端探针** | 仅 Java | 客户端 JVM 的客户端线程 | 看得见网络线（编码→socket→解码后的东西） | 服务端侧状态；**目前只能用 Java 写** |
| **attached 脚本**（本设计新增） | JS（CLI 跑）／Java（`:stagewright-junit`，**已存在**） | 自己的 JVM，全程走 RPC | 真异步等待、跨进程、重启游戏、驱动菜单（**有前提，见 §7.1**）、可挂调试器 | arena / 世界钉 / 审计 / 字节确定性 / **每场景独立原点** |

第三行两个格子**是同一个运行时的两个前端**——这就是"testmod 要不要有服务端线程之外的部分"
的答案，见 §7。

**（修订）**原表在 attached 一栏写了"驱动标题屏"。做不到，理由在 §7.1：客户端面的
endpoint descriptor 是**进世界之后**才写的，标题屏恰恰是它不存在的那段时间。

## 2. 落点：`cli/`，不是 `engine/`

`engine/build.gradle` 顶上那条 "NO EXTERNAL DEPENDENCIES" 是**继承来的约束**：这个
artifact 经 gradle plugin 落到消费者的 **buildscript classpath**，第三方坐标要从消费者的
`pluginManagement { repositories }` 解析，而 worlddriver 那份连 Maven Central 都没列。
他们为此把一个 JSON 库 vendored 进去也没加依赖。Rhino 进 engine 会正面撞上这条线。

`cli/` 没有这个约束——fat jar，audience 是下载一个文件就跑的整合包作者，已经有
`mavenCentral()` + `mavenLocal()`，已经依赖 engine，已经把两个 loader 的 mod jar 打进资源。

**Rhino 可用，已验证**：`dev.latvian.mods:rhino:2101.2.7-build.81`。拆开看过：366 个
class，全部在 `dev/latvian/mods/` 下，**0 处 minecraft 引用**，1.8 MB。纯 JVM classpath
上跑得起来，打进 fat jar 也不大。

两条前提原设计没写清，都是落地当天会绊一下的：

- **它不在 Maven Central。** 坐标来自 `https://maven.latvian.dev/releases`，根
  `build.gradle` 的 `subprojects.repositories` 为此专门加了一条。而 `cli/` 是**独立
  build**（`cli/settings.gradle` 只 `includeBuild('../engine')`），根 build 那条对它不生效
  ——要自己再声明一遍，并且用 `content { includeGroup 'dev.latvian.mods' }` 圈住。
  圈住不是洁癖：conformance-mods 的 twilight-forest 刚踩过一次——一个域名过期后
  被停靠页接管、对任意路径都答 200，Gradle 取第一个应答的仓库，于是它把声明在它
  后面的**九个**健康仓库全部黑洞掉，报出来是九份 "cannot parse POM"。
- **它是 KubeJS 的 fork，不是 Mozilla Rhino，而且这是故意的。** 两个家必须跑同一个
  解释器才谈得上"同一个文件"；而且现有代码已经依赖了 fork 的特性——`DriverAccess`
  的 `toJava` 靠的是"NativeObject implements Map in this fork"。换标准 Rhino 会静默改
  语义。

### 2.1 新模块：抽的是**词汇表**，不是 prelude（修订）

原设计要抽的是 prelude + `JavaAccess` + `CappedContext` + `SceneSpec` + `DriverBinding`，
理由是"两个前端各抄一份 prelude，第一次改就分叉"。方向对，范围错得很厉害。

`scenes-prelude.js` 一共 60 行，只定义了 `scene()` / `console` / `block()`。场景作者真正
打字的那些——`s.command` / `s.expect(…).as(…)` / `s.await(…).within(…).then(…)` /
`s.rel` / `s.setBlock` / `s.record` / `s.cleanup`——**一个都不在里面**。它们是 Rhino
直接反射打在 Java 对象上的（`JsScenes.invoke` 里那句 `cx.javaToJS(ctx, scope)`）。

所以"同一个场景文件在两个家里跑、源码不用改"要成立，`AttachedContext` 得手抄一份
**同名同签名**的 `SceneContext`（482 行）+ `Expect`（21 个断言方法）+ `AwaitBuilder`。
分叉风险 95% 在没被抽的那一半，而且 JS 按名字运行期解析，抄漏一个方法没有任何编译器
会说话。

**证据就在这份文档自己身上**：原 §4 的例子写 `.isAbove(60)`，`Expect` 里没有这个方法
（只有 `isGreaterThan` / `isAtLeast`）。一份连设计文档都会写错的词汇表，指望两份手抄
实现长期不漂移不现实。

好消息是分界线比想象中干净。`:stagewright-api` 的 15 个类里，**只有 `SceneContext`
（13 个 `net.minecraft` import）和 `Arena`（2 个）碰 Minecraft**；`Expect` / `Clock` /
`Terrain` / `Canary` / `Scene` / `SceneOutcome` / `SceneFailure` / `SceneSkipped` /
`SceneProvider` / `Stages` / `SceneSet` / `SceneDef` / `Perf` **全是 0**。

所以真正该抽的是：

```
:stagewright-script  (新, MC-free)
  scenes-prelude.js            资源，唯一一份
  JavaAccess / CappedContext   原样搬
  SceneReport                  interface { void violation(String,boolean); void record(String,Object);
                                           void fail(String); void skip(String); void cleanup(Runnable); }
  Expect                       ← 从 api 搬过来，改成打在 SceneReport 上。两个家同一份实现。
  Clock / Terrain / Canary / SceneOutcome / SceneFailure / SceneSkipped   ← 同上，本来就 MC-free
  SceneSpec                    收割结果的中立形态 (name, budgetTicks, optional, terrain, clock, body)
  DriverBinding                interface { Object route(String method, Map params); }

:stagewright-api     依赖它 → SceneContext implements SceneReport（世界那一半留在这儿）
:stagewright-common  依赖它 → JsScenes 把 SceneSpec 适配成 Scene + SceneContext
                             DriverAccess implements DriverBinding（反射进 WorldDriverCommon）
:stagewright-cli     依赖它 → AttachedScenes 把 SceneSpec 适配成 AttachedContext
                             RpcDriverBinding implements DriverBinding（走 websocket）
```

把 `SceneContext.violation/record/fail/skip/cleanup` 抽成 `SceneReport` 之后，**断言词汇表
是同一份实现**，两个家的分叉面缩到"世界那一半"。这也是唯一能让 `.isAbove` 这类漂移在
**编译期**就死掉的办法。

`driver('mc.observe.player')` 在两边都解析到 `DriverBinding.route`，所以这一层是干净的。
这正是 `DriverApi` 单一真相那条设计在还账：worlddriver 已经把整个动词面收敛到一个
`route(String, Map)`，这里只是给它加了第四个 transport，而不是第四份手抄 schema。

### 2.2 这个模块放进哪个 build，是个真问题（新增）

不是配置细节，是会卡住第 1 步的东西：

- 根 `settings.gradle` 只 include `api/common/fabric/neoforge/junit`。**`cli/` 和 `engine/`
  各自是独立 build。** CLI 要拿到新模块只有两条路：加一条 `includeBuild`，或走
  mavenLocal 发布物。
- 走发布物，就把根 `build.gradle` 顶上那段三步 RELEASE ORDER 变成四步；而且它是
  **CLI 与 mod 同时依赖的第一个模块**——一次版本错配 = 同一个 `.js` 在两个家里行为
  不同，恰好毁掉它存在的理由。倾向 `includeBuild`，把它当 engine 的同类。
- 它必须像 `:stagewright-junit` 一样被根 `subprojects{}` **按名字排除**，否则
  loom/architectury 会给一个 MC-free 模块套 Minecraft classpath。排除之后它就没有
  `namedElements` / `transformProductionFabric` 配置，`fabric/build.gradle` 和
  `neoforge/build.gradle` 里那两行 `shadowBundle project(path:…, configuration:'transformProduction…')`
  得换成普通 project 依赖。
- **包名**：`api/build.gradle` 顶上专门警告过包分裂——"It also owns the package
  `net.magicterra.stagewright.scene` OUTRIGHT … which FML resolves as a hard
  `java.lang.module.ResolutionException` at boot"。`JavaAccess`/`CappedContext` 现在住在
  `net.magicterra.stagewright.script`，`JsScenes`/`DriverAccess`/`JsBridge` 也在那个包。
  搬一半 = 同一个包跨两个 jar。mod jar 走 shadow 打包能救，但这条规矩在这个仓库里
  是有血案的：**要么整包搬，要么新包名**。`Expect` 那一族同理——它们现在在
  `net.magicterra.stagewright.scene`，那是 api 明文声称独占的包。

结论：新模块用**自己的包**（`net.magicterra.stagewright.script` 整包搬 +
`net.magicterra.stagewright.assertion` 收 `Expect` 那一族），不要在两个 jar 之间劈包。

### 2.3 别造第二个 websocket 客户端（新增）

原 §8 第 2 步要新写 `RpcDriverBinding`。但 `StageWrightRpc`（`junit/`，239 行，
`java.net.http.WebSocket` + gson）已经在跑 26 个契约测试，而且已经踩过分片帧、
无 id 的事件推送要丢弃、close 时要 fail 掉 in-flight 这些坑。§7 说"一个运行时两个前端"，
第 2 步却在造第二个 transport。

CLI 是独立 build，拿不到 `:stagewright-junit`（在 loom 主 build 里）。取舍只有两个，
必须现在做而不是写代码时再说：

1. **把 RPC 客户端也放进 MC-free 模块**（那它就不该叫 `-script`，叫 `:stagewright-attached`）。
   `StageWrightRpc` 从 junit 搬过来，junit 反过来依赖它。gson 跟着进 CLI 的 fat jar
   （~280 KB，可接受）。
2. CLI 依赖发布的 `mc_stagewright-junit`——会把 `junit-jupiter-api` 拖进一个跟 JUnit
   毫无关系的 fat jar。

选 1。顺带解释了模块名：它装的是"attached 这个家共用的东西"，不只是脚本。

## 3. 哪些能诚实地搬过去，哪些必须报错

`SceneContext` 今天约 30 个方法。逐个核过实现之后的表：

**搬得过去**（有对应动词，语义不变）

| SceneContext | 走什么 |
|---|---|
| `command(...)` | `mc.action.runCommand` |
| `blockAt` / `expectBlock` / `checkBlock` | `mc.world.block`（**但等值语义要处理，见 §3.2**） |
| `players()` / `player()` / `playerHere()` | `mc.observe.player` |
| `expect` / `check` / `fail` / `record` / `passNote` / `skip` | 纯逻辑，`SceneReport` 那一份实现直接用 |
| `cleanup(r)` | host 侧执行，语义一致 |
| `driver(method, params)` | 就是 RPC 调用本身 |

**搬不过去，必须当场报错并说清替代品**

| 动词 | 为什么 |
|---|---|
| `arena()` | arena 建造器按图案在**分配的原点**、**强制加载的区块**里写块。原点分配和 forceload 是 harness 侧的，不是动词 |
| `origin()` / `rel()` / `originX/Y/Z` / `setBlock` / `floor` | **（修订）**原表把这一整行放进了"搬得过去"。错的，理由在 §3.1 |
| `await(cond).within(n)` | **（修订）**原表映射到 `mc.wait.condition`。映射不成立，理由在 §3.3。attached 有自己的等待，但语义不同，得换个名字 |
| `perf()` | 从 tick 循环内部采样 tick 循环。进程外测到的是网络延迟 |
| `level()` / `server()` | 裸 Minecraft 对象，这个 JVM 里根本没有 |

报错照抄 `Clock.parse` 对未知名字的做法——**抛异常并列出全表**，绝不静默降级。一个
默默变成 no-op 的 `arena()` 会让场景在错误的世界里断言，然后失败在一个跟成因无关的地方。

### 3.0 这张表当天就过期了：能力面（新增，2026-08-05 晚）

上面那句"`SceneContext` 今天约 30 个方法"写于当天 17:34。同一天 22:26 落地的能力扩展面
（`capability-extension-design.md`）给 `SceneContext` 加了**一整层门面**，这份设计一个字
都没提。补上，因为它恰好落在最难的那一档：

| 门面 | 搬得过去？ | 为什么 |
|---|---|---|
| `mods()` | **能，而且该优先搬** | 纯查询模组 id/版本，一次 RPC 就够。attached 的所有条件分支都从它起步 |
| `capability(name)` / `capabilities()` / `hasCapability` | **不能**（第一版） | `CapabilityProvider` 是 `ServiceLoader` 从**游戏 JVM 的 classpath** 发现的。attached JVM 里没有那些 jar，`ServiceLoader` 只会答空表——**和"这个包没装这个模组"一模一样的答案**。这是本仓库反复出现的那个物种：框架自己的接线坏了，却穿着"模组不在场"的衣服 |
| `probe(className)` / `hasClass` | **不能** | 同上，而且更直接：反射的是**别的进程**的类 |
| `equip()` / `items()` / `menu()` | 不能（第一版） | 都要 `ctx.player()` 的 `ServerPlayer` 实例；跨进程只有 `mc.observe.player` 那份快照 |
| `advancements()` / `recipes()` / `structures()` / `loot()` / `quests()` | 原则上能，**但没有动词** | 它们读的是服务端注册表，天然适合一次 RPC 问一个大答案。今天 `mc.*` 里没有对应动词——这是**动词面的洞**，跟 §7.1 后半段同类 |

三条结论，都不是"再补几个动词"能绕开的：

1. **能力面第一版整体不搬**，并且抛的异常必须说清是**哪一种**不在场：不是"这个运行时
   没有 Mekanism"，而是"能力发现只在游戏进程里成立，attached 家问不到"。把这两句话
   混成一句，就是把框架的结构性限制报成用户的模组列表问题——`Mods` 那个类刚为此专门
   写了"没装是 failure、绝不是空列表"。
2. **注册表那五个门面要不要进动词面，得单独决定**，别夹带在这份设计里。每加一个 `mc.*`
   动词都是**所有 LLM 客户端每次 prompt 的永久 token 税**（worlddriver 的硬规矩）。
   五个只读注册表查询打包成**一个** `mc.registry.query`，比五个动词划算得多。
3. **§8 第 7 步那条一致性门因此有了明确的适用边界**：它只能对"两个家都支持的动词"生效。
   同一个 `.js` 在两个家跑出不同结果的地方，一半是 bug，一半是这张表——门必须能分清，
   否则它每天都在报已知差异。做法：一致性门只收**没有触碰不可搬动词**的场景文件，
   而"触碰了"由 attached 侧抛出的那个异常来判定，不靠人维护清单。

### 3.1 `origin()` / `setBlock` 搬不过去，变的是隔离不是坐标（修订）

`mc.system.testOrigin` 的实现是"**the canonical test arena origin**"——**一个固定点**，
整个服务器一个。而进程内是 `origin = (100000 + slot*512, 200, 100000)`，
`StageWrightHarness.assignSlots` 给每个场景发一格，跑前 forceload、跑后 `sweepArena` +
泄漏审计。

于是 attached 的所有场景会叠在同一个原点上写块：没有 slot 分配、没有 forceload、
没有 teardown、没有审计。这正是本仓库到处在警告的那个假信号——`RunDirectory.provision`
删世界的注释写得最清楚："Reusing one makes scenes fail in ways that look exactly like
product bugs"。

对比之下 `arena()` 抛异常反而是安全的：它当场报错。`setBlock` / `floor` 放行只是把
同一个坑挖得更隐蔽——**它会安静地污染下一个场景**。

所以 attached 的第一版：**写世界的动词一律抛异常**。等原点分配有了答案再放行，两条路：

- 给 `mc.system.testOrigin` 加参数，让 harness 侧也能按 slot 发原点；
- 或者 attached 自己按同一套 grid 公式算原点，并用 `/forceload add` + 自己 sweep。

第二条不需要动 worlddriver，但要把 grid 常量（`GRID_X0`/`GRID_Y`/`GRID_STEP`）从
`StageWrightHarness` 提到 MC-free 模块里去，两个家共用一份——否则就是又一处会漂移的
手抄常量。

### 3.2 `block()` 的等值语义两边不同（新增）

进程内 `blockAt()` 返回 `Block` 对象，`assertBlock` 用 `!=` 做**身份**比较；
`mc.world.block` 返回的是字符串 `type:"minecraft:stone"`。同一行

```javascript
s.expectBlock(0, 0, 0).isEqualTo(block('minecraft:stone'))
```

两边走的是完全不同的比较路径。这是全套里最高频的断言，必须显式设计而不是碰运气：
attached 的 `block(id)` 返回一个**带 `equals` 的 StageWright 值对象**（不是裸字符串），
`blockAt()` 返回同一个类型；进程内的 `Block` 继续走身份比较。两边的 `isEqualTo` 因此
都答对，而 §8 那条一致性门会盯着它。

### 3.3 `await` 映射不成立，attached 的等待是另一件事（修订）

`mc.wait.condition` 的形状是 `{invoke, params, field, value}`：它轮询**一个具名 driver
方法**并走 dotted field 取值。它**接不了 JS 闭包**，而原 §4 的例子给的正是闭包。

真实实现只能是 attached JVM 自己轮询，于是 `within(n)` 从 tick 变成 `n × 50ms` 墙钟。
这个折算在本仓库已经被证伪过：`StageWrightCommon` 那段 settle 屏障的注释白纸黑字写着,
catch-up ticks 是 ~3ms 而不是 50ms，同样的等待要多花 **2–2.3 倍** tick。也就是说同一个
`within(120)` 在两个家里含义不同，**且不是常数偏差**。

顺带两条上限：`mc.system.waitTicks` 上限 **200 ticks** 且拒绝在服务端线程运行；原 §4
那个 `within(20*60*10)` = 12000 ticks 既给不出一次调用，轮询版本又是 12000 次往返。

所以 attached 的等待**换个名字**，不要复用 `await(...).within(ticks)`：

```javascript
s.waitUntil(function () { … }).forMs(30000)      // 墙钟，attached 独有，名字就说了它是墙钟
s.waitFor('mc.observe.player', 'health', 0)      // 薄封装到 mc.wait.condition，服务端轮询
```

第二个形式才是该鼓励的：轮询发生在服务端，一次往返，`pollMs` 由 driver 控制。第一个
留给"条件必须在 attached 侧算"的场合，并且**默认打一条 record**说明它轮询了多少次——
一个跑了 12000 次往返的等待应该在结果里看得见。

### 3.4 真正不可约的那条差异（原文保留）

上面的动词表容易让人以为"再补几个动词就能整个搬出去"。不是。不可约的差异是**时序**：

- 进程内场景是**逐 tick 同步**的。"造 arena → 走 1 tick → 断言"是精确的，因为整段
  在同一个服务端线程上、在两个 tick 之间原子地发生。
- attached 脚本每个调用是一次网络往返，**两次调用之间游戏会 tick**。"走 1 tick"这件事
  它说不出口。

所以字节确定性、`withOriginSlot` 那套"suite 长大不能挪动原点"的保证、以及世界钉能给的
"每个场景看到同一个世界"，进程外拿不到——**不是没实现，是拿不到**。

结论：**加一种，不是换一种**。确定性 arena 留在进程内；流程测试、跨进程、UI 走 attached。

### 3.5 抛什么异常是契约的一部分，而且已经破过两次（新增，2026-08-05 晚）

§3 的表把 `command(...)` 列进"语义不变"。核过实现之后这句话不够——**返回值和异常类型
都是词汇表的一部分**，而这两样恰好是跨进程时最容易被悄悄改掉的。

- **返回值**：`command()` 返回的不是字符串，是 `CommandResult(int result, List<String> output)`，
  `output` 是命令回执的每一行。RPC 那边 `mc.action.runCommand` 必须原样带回这两样；
  少带 `output` 的话，所有靠读回执的场景在 attached 家静默变成读空表。
- **异常类型**：命令被拒时 `command()` 抛的是 **`IllegalArgumentException`**（`CommandSyntaxException`
  包过一层），**不是 `SceneFailure`**。这个区别不是学术的——场景大量用
  `try { s.command("data get …") } catch (…) { return false }` 把"拒绝"当成一个**答案**
  （路径不存在 = 槽位是空的）。catch 错类型不会让 catch 失败，它会让异常**逃到 harness**，
  报成 `unexpected IllegalArgumentException`，于是场景恰好在这个 helper 存在的理由那条
  分支上变红。这个错今天真发生过一次。
- 所以 attached 侧的 `command()` 收到 JSON-RPC error 之后，必须**重建同一种异常**。
  一个把所有 RPC 错误统一包成 `SceneFailure` 的实现，会让每一个可移植 `.js` 的 try/catch
  改变含义——而且是**静默**改变，因为两边都"抛了"。

**同一个物种的第三例，而且这一个必须写进模块划分：** Rhino 会把脚本调用的 Java 方法抛出的
任何异常包进 `WrappedException`，所以 `instanceof SceneFailure` / `instanceof SceneSkipped`
的裸判断永远不成立。`JsScenes.invoke` 为此有一段 `unwrapOurs`，沿着
`WrappedException.getWrappedException()` 和 `getCause()` 往下扒。它 2026-08-05 才修好，
在此之前**每个整合包每个场景文件的每一次 skip 都被报成 FAILED**。

`AttachedScenes` 会原样再需要一次这段逻辑。如果它被手抄一份，这个 bug 会一字不差地
在第二个家复活——而且照样会活很久，因为**只有 skip 能看见它**（被包住的 `SceneFailure`
照样报 FAIL，看起来完全正常）。所以 `unwrapOurs` 属于 §2.1 那个 MC-free 模块，
**和 `Expect` 同一档**：它不是"解释器细节"，它是"断言词汇表怎么把结果传出来"的一半。

## 4. 作者面

同一个 `scene()` 关键字，断言词汇表是同一份实现（§2.1）。区别在能用哪些动词、以及
等待怎么写（§3.3）：

```javascript
// attached/first-night.js —— 进程外，可以真的等
scene('flow.survivesFirstNight', 20 * 60 * 10, function (s) {
    s.command('time set night');
    s.waitFor('mc.observe.player', 'health', /* truthy */ undefined).forMs(30000);
    s.expect(driver('mc.observe.player').pos.y).as('没掉进洞里').isGreaterThan(60);
});
```

```
java -jar stagewright.jar --game-dir <pack> \
     --scenes <in-game .js>      \   # 进程内，装进 config/stagewright/scenes/
     --attached <attached .js>       # 进程外，本进程解释，走 RPC
```

`--attached` 有两条额外前提，都要进 `--help`：

1. **pack 里得有 driver**（worlddriver 提供 RPC）。`--scenes` 不需要——进程内场景的
   `SceneContext` 是 StageWright 自己的。
2. **这一轮的游戏必须以 hold 起**（`-Dstagewright.hold=true` +
   `-Dstagewright.endpoint=<path>`），而不是 CLI 今天硬写的
   `-Dstagewright.autorun=true`。理由在 §6：autorun 跑完会 halt 服务器，把 attached
   还连着的 socket 带走。

`--attached` 在 `Main` 里要加的编排量，原 §8 第 3 步低估了：起 hold → 轮询 descriptor →
attach → 跑 attached 场景 → 触发 `mc.test.run` → 等 done 尾 → 杀进程树。基本是
`Main.runClient` 那套 `awaitDoneFooter` / `kill` 的镜像。

## 5. 结果与裁决

第三份文件 `stagewright-attached-results.jsonl`，**沿用现有 orchestration-contract-v0**，
不发明新格式。裁决走现成的"取最坏"：`StageWrightVerdictTask.judgeCompanion` 已经实现了
server 结果 vs client 结果的 worst-wins（`GREEN 0 < RED 1 < DEAD 2 < ENV 3`），
把 `companionResults` 从"一个文件"泛化成"一组文件"即可。

沿用现成格式意味着要把这四件事补上，原设计一件都没写（修订）：

- **头里不该有 `worldPin` 键**——attached 不钉世界。这个键"只在有值时出现"的语义
  2026-08-05 刚做对，正好用上。（原文保留，这条是对的。）
- **头里必须有 `loader`**，契约的"结果文件"节把它列为必填。attached JVM 没有 loader，
  从 endpoint descriptor 的 `loader` 字段取——descriptor 本来就带。
- **场景记录必须有 `ticks`**，也是冻结字段。attached 没有 tick 计数：写 `0`，并在
  `data` 里带上真实的 `wallMs` 和轮询次数。**不要**拿墙钟除以 50 伪造一个 tick 数——
  §3.3 已经说明那个折算是错的，伪造只会让它错得看不出来。
- **空 registered 必须是 RED。** companion 走的是 `Verdict.judge(records, null)`，
  **不做期望清单对账**。于是一个注册了 0 个场景的 attached 文件 = 头 + 空 registered +
  done 尾 = **GREEN**。这就是 §6 自己痛陈的"静默跳过看起来和通过一模一样"，换了个
  文件原样重现。attached 半边要么有自己的 `--expect` 清单，要么至少有一条"registered
  为空即 RED"。后者是底线，前者才是对的。

还有一条**先于第三个文件必须修**的（新增）。**2026-08-05 已落地**——它不依赖 attached 的任何一步，
所以先修了：`provision` 的签名从 `String resultsFile` 改成 `List<Path>`，provision task 拿到了
verdict task 读的那个 `companionResultsFile`。落地时在本机盘上抓到了活的证物：
`fabric/run-stagewright-joining-client/stagewright-client-results.jsonl` 停在**当天 14:34**，
内容是完整的头 + 一条 PASS + `done` 尾——正是 verdict 会照单全收的形状。

- `RunDirectory.provision` 只删**它被告知的那一个** results 文件。而
  `companionResultsFile` 指向另一个目录（worlddriver 的
  `fabric/run-stagewright-joining-client/…`），**从来没被清过**。客户端半边起来了、
  但在写头之前崩掉，昨天那份完整的 GREEN 还躺在那儿 → 假绿。这是今天就存在的洞，
  加第三个文件会把它变成三倍。`provision` 要泛化成"清一组结果文件"，签名从
  `String resultsFile` 变成 `List<Path>`。

另外：`companionResults` 从一个文件泛化成一组是**契约变更**。
`docs/orchestration-contract-v0.md` 已经有六个 "v0 附录"，每个都写了"不升版"的理由；
这一条也要进去，而不是默默改。

## 6. 门（gate）集成：链的顺序是反的（修订）

今天 `:stagewright-junit` 的活法是：

```bash
./gradlew stagewrightDedicatedServerFabricHold        # 一个 shell
TESTKIT_ENDPOINT=.../stagewright-endpoint.json \      # 另一个 shell，手动设环境变量
  ../stagewright/gradlew -p ../stagewright :stagewright-junit:test --rerun-tasks
```

**而且 `TESTKIT_ENDPOINT` 没设时两半都 SKIP——一次没有这个变量的绿 `:stagewright-junit:test`
不是覆盖率。** 这是框架不该有的陷阱：静默跳过看起来和通过一模一样。这个诊断是对的。

原设计给的链是：

```
provision → 起 Hold → 跑进程内 suite → （endpoint 就绪后）跑 attached 半边 → 裁决 → 关 Hold
```

**这条链在 Gradle 里表达不出来。** Hold 任务本身不做事，它 `dependsOn` 的是宿主的 run
task，那是个 `JavaExec`，**会一直阻塞到 Ctrl-C**（`registerHoldTask` 的注释明写
"It ends when you stop it"）。Gradle 一个 project 的任务串行执行，所以"起 Hold"那一格
永远不返回，后面几格永远不开始。

而且 autorun 与 attached 天然互斥：autorun 的 suite 跑完会 `server.halt(false)`，
attached 还连着的 socket 当场断。

**需要的原语全都已经有了，只是顺序反了**：

- hold 下 suite 跑完**不 halt**——`StageWrightHarness` 里那段判断写得很清楚：
  "A hold outlives its suite. Halting here would take the endpoint down underneath
  whatever attached to it"；
- `mc.test.run` 就是从进程外触发进程内 suite 的入口（`TestRunVerb`），返回
  `{accepted:true, scenes:N}`，**done 尾仍是唯一的完成信号**；
- 旁路进程的收尸模型也有——`attachSideProcesses` 在 run task 的 `doFirst` 里把
  companion 拉起来，交给 build service，Gradle 在任何退出路径上都会关掉它。

所以正确的链是**一个 Hold，attached 当驾驶员**：

```
provision
  → 起 Hold（run task 阻塞，但它是 doFirst 里拉起 attached 的那一格）
      → attached 半边 attach 上来
      → 先跑 attached 自己的场景（此时世界未钉、未被 suite 扰动）
      → 再 mc.test.run 触发进程内 suite
      → 轮询 stagewright-results.jsonl 的 done 尾
      → 写 stagewright-attached-results.jsonl
      → 主动结束 hold（见下）
  → 两/三份 results worst-wins 裁决
```

顺序不能颠倒：进程内 suite 会 `WorldPin.applySuite` 钉住世界、跑完 `WorldPin.release`
放开，attached 场景夹在中间会看到一个正在被改的世界。

**缺的那一件**是"谁结束这个 hold"。今天 hold 只能 Ctrl-C。三个选项：

1. **给 `mc.test.*` 加一个 `mc.test.end`**——attached 跑完主动关。最直接，但多一个
   隐藏动词（`asHidden()`，不进 MCP `tools/list`，跟 `mc.test.run` 同规格，所以
   不吃 LLM 的 token 预算）。
2. attached 断开 websocket，hold 侧检测到"最后一个 attach 走了"就收工。省一个动词，
   但把"没人连着"和"连接抖了一下"混在一起，不值。
3. Gradle 侧超时杀。这是今天的兜底，不该是主路径。

选 1。它同时让 `:stagewright-junit` 那两个 shell 变成一个 gate task，这才是 §6 真正
要解决的问题。

### 6.1 hold 里的世界默认是**死的**（新增，2026-08-05 晚；已修）

这条直接推翻 §6 那条链的一个隐含前提。链里写着"**先跑 attached 自己的场景**（此时世界
未钉、未被 suite 扰动）"——未被扰动是真的，**活着不是**。

`ServerLevel.tick` 算 `!players.isEmpty() || !getForcedChunks().isEmpty()`，连续 300 tick
为假就**同时跳过实体循环和 `tickBlockEntities()`**。dedicated hold 按定义没有玩家；
而 `getForcedChunks()` 读的是 `/forceload` **存档数据**，不是 arena 用的运行时
`TicketType.FORCED` 票——所以那个 pin 对这个判定毫无贡献。**15 秒**之后，世界里没有任何
东西会自己动：实体不掉落、熔炉不点火、机器不运转。而服务端循环照跑、区块照加载、
命令照执行、方块放下去照读得回来——**没有一处看起来是停的**。

hold 从启动到写出 descriptor 到被 attach 上，远不止 15 秒。所以按 §6 那条链，
**每一条 attached 场景都会跑在一个静止的世界里**，而它读到的每一个 chunk 状态
（`getFullStatus` / `isPositionEntityTicking` / `shouldTickBlocksAt` / `areEntitiesLoaded`）
都会答 true，并且都是对的——chunk 状态从来不是这件事的判据。

**这不只影响未来的 attached，它今天就影响 `:stagewright-junit`。** junit 那半正是
"attach 到 hold、在 suite 之前跑"。它现有的测试大多是客户端面的观察和契约断言，
不依赖服务端自发的运动，所以没炸；但任何一条"等一个生物走过来 / 等一炉矿烧完"的
测试都会静默超时。

**修法与位置**：`resetEmptyTime()`（vanilla 自己的出口，`ServerChunkCache` 走支持路径
force-load 时调的就是它）每 tick 打在所有 level 上。**位置比修法重要**：第一版把它放在
`StageWrightHarness.observeServerTick`，而 `holding()` 分支下 **harness 根本不构造**
——要等 `mc.test.run` 才建。于是修好的正好是不需要修的那条路径（autorun suite），
hold 这条一动没动。它现在在 `StageWrightCommon.onServerTick`，与 harness 是否存在无关：
**"让世界保持活着"是"StageWright 被装上了"的属性，不是"suite 正在跑"的属性**，
而这个 mod 只会出现在测试运行里。

**验证方式本身就是这一节的论据**：修完之后，起 `stagewrightDedicatedServerFabricHold`，等它写出
descriptor，然后**完全从进程外、只走 RPC**（`mc.action.runCommand`）做两件事——在 hold 窗口里
summon 一个盔甲架，它从 Y=220 掉到地面；再摆一个熔炉、塞进矿石和煤，`execute if block …
minecraft:furnace[lit=true]` 从 `Test failed` 变成 `Test passed`。这正是一条 attached 场景能
写出来的东西，也正是它在修之前拿不到的东西。同一个 hold 上 `:stagewright-junit` 跑了
**40 条、0 失败、5 跳过**（跳过的是需要客户端的那半，dedicated hold 下本就该跳）。

（顺带一条给未来写这条自测的人：四条命令打成一个 batch 时，最后那句 `execute if` 会和前三条
落在同一个 tick 上，答 `Test failed`——熔炉还没 tick 过。进程内场景记的是 `ticksToLight=2`。
**这不是世界死了，是没等**。attached 家没有"走一个 tick"这个说法（§3.4），所以这类断言在
attached 侧必须走 §3.3 的 `waitUntil`，不能靠一次往返。）

对这份设计的两条硬约束：

1. §6 那条链**可以**保留"attached 先跑"的顺序——前提是上面这条修在位。顺序的理由
   （避开 `WorldPin.applySuite` 正在改世界的窗口）依然成立。
2. attached 的自测场景里**必须有一条断言世界是活的**，而且要在 attached 家里断言，
   不能只靠进程内那条 `cap.blockEntitiesTickInTheArena`——两个家共用一个服务端，但
   进程内那条只在 suite 跑起来之后才执行，恰好错过 hold 窗口。这条自测正是 §8 第 8 步
   要求的"只有进程外能做到"的场景之一：**它测的是 hold 窗口，而进程内场景按定义
   进不去那个窗口**。

## 7. testmod 要不要有服务端线程之外的部分

**已经有了，就是 `:stagewright-junit`。** 它 attach 到 Hold，跑在 JUnit 测试线程上，
全程走 RPC，可以自由异步等待——26 个 instrument 契约测试和 UI 测试都在那里。
它就是 §1 表格第三行的 Java 前端。

所以不该再造第三样东西，该做的是两件：

1. **把 attached 运行时收成一个东西、两个前端**（JS 给整合包作者，JUnit 给 mod 作者），
   共用 endpoint 描述符、共用 results 契约、共用 worst-wins 裁决、**共用一份 RPC
   客户端**（§2.3）。
2. **把集成缝补上**（§6），让任何一半都不能静默跳过。

至于"testmod 自己（游戏 JVM 内的 Java）要不要有离开服务端线程的部分"——**不要**。
testmod 存在的理由就是直接摸 `Level` / `ServerPlayer` / 确定性 tick；离开服务端线程
就把这些全丢了，还换不来跨进程的好处（因为还在同一个 JVM 里）。真正要离开的工作
属于另一个 artifact，而那个 artifact 已经存在。

### 7.1 剩下的那个洞，比原设计说的窄（修订）

原文说"客户端探针今天只能用 Java 写，attached-JS 落地之后这是剩下的最后一个洞"。
把它拆细一点，因为两半的难度差一个数量级：

- **驱动客户端 UI**（开背包、点按钮、读 screen tree）——`mc.client.screen.tree` /
  `mc.client.input.*` / `mc.client.screenshot` 这些动词**已经存在**，attached-JS 连到
  **客户端面**的 endpoint 就能用。这一半 attached-JS 落地即关闭。
- **观察网络线上的东西**（`client.damageSourceAcrossTheWire` 那种：断言"解码之后的
  payload 是什么"）——**没有动词**，因为解码后的包不是任何 `mc.*` 的返回值。这一半
  跟语言无关，是**动词面**的洞，不是"只能用 Java 写"的洞。要关它得先想清楚
  "把解码后的包做成可观察量"是不是一个好主意。

**还有一条前提，原设计当成能力写进了 §1 表格：驱动标题屏做不到。** 客户端面的
descriptor 是**进世界之后**才写的，`ClientDirector` 那行注释就是在解释为什么：
"mc.client.screen.tree answers at the title screen too, and every UI test would then
race the world it assumes it is standing in"。要驱动标题屏就得绕开 descriptor 直接读
`worlddriver-rpc.port`——等于承认 attach 契约有两个入口。§8 第 6 步那条自测要求因此
要么改成"跨客户端进程边界"，要么先给 descriptor 加一个 pre-world 面。

## 8. 落地顺序

0. **两条前置已落地（2026-08-05）**，都不依赖 attached 的任何一步，所以先做了：
   `provision` 清一组结果文件（原第 2 步，见 §5 末）；`resetEmptyTime()` 每 tick 打在所有
   level 上，且放在 `StageWrightCommon.onServerTick` 而不是 harness 上（§6.1）。第二条
   是这条链能成立的前提——没有它，attached 场景全部跑在静止的世界里。
1. **抽 `:stagewright-attached`（MC-free）**：整包搬 `net.magicterra.stagewright.script`
   （prelude + `JavaAccess` + `CappedContext` + **`unwrapOurs`，见 §3.5**），新包收
   `SceneReport` + `Expect` + `Clock`/`Terrain`/`Canary`/`SceneOutcome`/`SceneFailure`/
   `SceneSkipped`，`StageWrightRpc` 从 junit 搬进来，加 `SceneSpec` + `DriverBinding`。
   `api`/`common`/`junit` 改成依赖它。**行为零变化**，现有五个门必须全绿——
   这一步是纯搬家，任何 RED 都是搬错了。别忘 §2.2 的三件事：根 `subprojects{}` 排除、
   两个 loader 的 `shadowBundle` 换写法、不劈包。
2. `cli/` 加 Rhino 依赖（记得 §2 那条 `content{}` 圈定）+ `AttachedContext`
   ——**只读子集**：`command`（**连 `CommandResult` 的两个字段和拒绝时的异常类型一起，
   §3.5**）/ `blockAt` / `players` / `mods` / 断言 / `cleanup` / `driver` /
   `waitUntil` / `waitFor`；arena 类、写世界的动词、以及**整个能力面**（§3.0）一律抛异常
   带全表，且能力面的异常要说清是"attached 家问不到"而不是"这个包没装"。
4. `--attached <dir>`，写 `stagewright-attached-results.jsonl`，头补 `loader`、
   记录补 `ticks:0` + `data.wallMs`、空 registered 判 RED（§5）。
5. `Verdict` / `StageWrightVerdictTask` 的 companion 从一个文件泛化成一组；
   `docs/orchestration-contract-v0.md` 加一条 v0 附录说明这次变更。
6. 加 `mc.test.end`（隐藏动词），gradle plugin 的 `attachedScenes`，一个 gate task
   串起 §6 那条链，顺带把 `:stagewright-junit` 收进同一条链。
7. **一致性门**（新增，比 §3 那张手写表值钱）：同一个 `.js` 在两个家里各跑一遍，
   断言两边的 results 记录一致（outcome + reason）。§3 的表是人维护的、会烂；
   这条门是机器维护的。worlddriver 对自己三个 transport 干的就是这件事
   （"validation suite asserts all three return byte-identical results"）——attached
   是第四个 transport，应该继承这条纪律而不只是继承 `route()`。
   **适用边界见 §3.0 第 3 条**：只收没有触碰不可搬动词的文件，且"触碰了"由 attached 侧
   抛出的异常判定，不靠人维护清单。
8. 自测场景：`flow.*` 若干条，至少要有两条**只有进程外能做到**的：
   - 按 §7.1，"跨客户端进程边界"（连客户端面 endpoint 驱动 UI），不是"驱动标题屏"；
   - 按 §6.1，**在 hold 窗口里断言世界是活的**——进程内场景按定义进不去那个窗口，
     所以这条只有 attached 能测，而它守的是一条已经破过一次的不变量。

第 1 步是唯一有回归风险的一步，也是唯一必须全量重验的一步。第 2 步之后 attached 就
能跑真东西了，写世界那一半（§3.1 的两条路）可以晚一个版本再决定。

### 8.1 落地时踩到的三个坑（2026-08-06）

三个都不是设计错了，是**设计没写到那一层**。共同点：失败的样子都不指向成因。

1. **`--attached` 指向一个没有 driver 的包时，CLI 挂满整个 `--timeout`。** §4 明写了
   "pack 里得有 driver"，但只写成了前提，没写成**检测**。游戏第 30 秒就把诊断打进日志了
   （`worlddriver-rpc.port does not exist`），CLI 还要再等 59 分半，然后报一句更模糊的
   "never published"。现在 CLI 盯着那行——这是**我们自己的 mod 打的我们自己的字符串**，
   所以捞日志在这里是精确而不是脆弱。
2. **websocket URI 少了 `/rpc` 路径，握手不是失败而是超时。** 于是重试五分钟，每次都
   报"包大概还在启动"——自信、措辞良好、完全错误。`:stagewright-junit` 的 `Endpoint`
   一直是对的：**同一份 descriptor 的第二个读者，把同一个细节重新学了一遍**。这正是
   §9 那条"能交给机器就别写进文档"的反例，两个读者应该共用一个 parser。
3. **区块加载了不等于区块在 tick。** 那条"世界是活的"自测把带燃料的熔炉摆在出生点，
   熔炉不点火，而每一次读取都正常。进程内是 harness 把 arena 钉住的；进程外没有
   harness，场景**得自己开口要**——现在先 `/forceload add`，顺带让 `getForcedChunks()`
   真的非空（§6.1 说的正是它）。

第 3 条值得单独记：它和 §6.1 是同一件事的两个方向。§6.1 讲的是**整个 level** 会停下来，
这一条讲的是**level 活着而某个区块不在 block-ticking 范围里**。两者的症状一模一样——
东西不动，读数全正常——而修法完全不同。

---

## 9. 这份设计已经三次被同一件事修正

值得单独记一笔，因为它决定了该怎么读这份文档，也决定了第 1 步为什么必须"行为零变化"。

三次修正——动词表把 `origin`/`setBlock` 当成可搬（§3.1）、`await` 映射到 `mc.wait.condition`
（§3.3）、能力面整层缺失（§3.0）——**都是同一个错误**：对着直觉写"这两个东西看起来一样"，
没有对着实现核。每一次核完的结论都是"名字一样，语义不一样"。

这正是 attached 这个家最大的结构性风险：它的卖点是**同一个 `.js` 在两个家里跑**，
而"一样"这件事只要有一处是靠人写文档维护的，它就会烂。所以这份设计里真正承重的
不是 §3 那两张表，是三样机器维护的东西：

- §2.1 的**同一份 `Expect` 实现**（让 `.isAbove` 这类漂移死在编译期）；
- §3.5 的**同一份 `unwrapOurs`**（让 skip-报成-FAIL 这类 bug 不能在第二个家复活）；
- §8 第 7 步的**一致性门**（让两个家跑出的差异要么是 bug，要么被异常显式标注）。

表会烂，这三样不会。写新一节的时候先问：**这条约束能不能交给机器？** 能就别写进表里。
