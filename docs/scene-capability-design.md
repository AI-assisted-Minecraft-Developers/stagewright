# 场景能力面设计：从"摆方块"到"测流程"

> **状态（2026-08-05）：§5 的 1–7 全部实现，并且已经在两个第三方目标上跑完。**
>
> - 自测：worlddriver dogfood 里的 `cap.*`（25 条），dedicated 与 integrated 两个拓扑。
> - **Twilight Forest**（`conformance-mods/twilight-forest`，MDG 2.0.76）：16 条 `tf.*`，
>   `stagewrightDedicatedServer` 与 `stagewrightIntegratedServer` 双 **GREEN**，
>   `src/main` 零改动。
> - **All the Mods 10**（452 个 mod 的服务端整合包）：10 条 `.js` 场景，走 CLI
>   `java -jar stagewright.jar --game-dir … --scenes …`，无 worlddriver、无 Gradle。
>
> 落地过程中发现的**静默失败**坑记在 §7，交付缺口记在 §6.5——每一个都是"看起来通过了
> 其实没测到"的形状，全部由真跑抓到，没有一条是 review 看出来的。

## 0. 缺口，一句话

`SceneContext` 今天能造方块、能断言方块。而两个目标场景——Twilight Forest 的通关流程
和 All the Mods 10 的 ATM 之星——要测的是**物品、进度、配方、维度、战利品和菜单**，
这六样它一个都看不见。

这不是"再加几个方法"的量级，所以先写清楚加什么、为什么这么切、以及**哪些约束决定了
形状**——尤其是最后一条，它是这份设计里唯一不能妥协的东西。

## 1. 两个场景各自要什么（调研结论，不是猜的）

### Twilight Forest

核过 fork 的源码，四块需求映射如下：

| 目标 | TF 里的实现 | 需要的能力 |
|---|---|---|
| **通关流程** | `AdvancementLockedStructure.doesPlayerHaveRequiredAdvancements(player)`；`ProgressionEvents` 每 20 tick 检查玩家是否闯进未解锁区域；`TFConfig.getPortalLockingAdvancement` 锁传送门 | **进度（advancement）读写** + **维度切换** + **结构定位** |
| **饰品装备效果** | `compat/curios/CuriosCompat`：`CuriosApi` / `SlotContext` / `SlotResult`，Charm of Keeping、Charm of Life、蝉 | **往具名槽位装备并读回效果** |
| **道具食物** | `TFItems` 的食物族 | **给物品、吃、读饱食度/状态效果** |
| **GUI** | `UncraftingTableBlock` + `UncraftingMenu`（TF 的招牌 GUI） | **服务端打开容器菜单、读槽位、点击** |

关键发现：**TF 的整条通关阶梯是 advancement 门控的**，不是靠打死 boss 这个动作本身。
所以"进度"这一条能力打通，通关流程测试就从"要真打八个 boss"变成"授予进度 → 断言
下一层结构/传送门确实解锁了"——可测，且测的正是 TF 自己的门控逻辑。

### All the Mods 10

`ServerFiles-7.3.zip`：NeoForge 21.1.247，**452 个 mod**，`config/ftbquests/quests/chapters/`
下一整套 `.snbt`（含 `chapter_2_the_star`、`achapter_2r_6the_atm_star`），ATM 之星本体在
`allthetweaks`。

| 目标 | 需要的能力 |
|---|---|
| **进度解锁** | 进度读写（与 TF 同一条） |
| **任务领取** | FTB Quests 的服务端 API（**探针式**，见 §2.4） |
| **结构生成** | 结构定位 |
| **维度运转** | 维度切换 + 断言目标维度在 tick |
| **战利品表** | 掷一次战利品表并读出物品 |
| **合成 ATM 之星** | **配方查询与闭包**——见下 |

**ATM 之星不该靠"玩到"来测。** 那是上百小时的流程，跑一遍既不可能也不可重复。
可测的是**图的性质**：从 `allthetweaks:atm_star` 出发，沿配方图往下走，直到全部落在
原版可获得的原料上——图闭合、没有断边、没有指向不存在物品的配方。这类断言对整合包
作者才是真正有价值的（"我改了个配方，星星还合得出来吗"），而且**跑一次是秒级**。

这条结论决定了 `recipes()` 这个 facet 的形状：它要能做**闭包遍历**，不只是查单条配方。

## 2. 形状：四条约束决定的

### 2.1 JS 安全是硬约束，不是风格

`SceneContext.originX()` 的 javadoc 已经把规矩写死了：

> **a scene file may call StageWright's own methods and pass Minecraft objects around, but must
> never call a method ON a Minecraft object.**

因为 JS 按名字在运行期解析，而生产 Fabric jar 是 intermediary 映射——`getX` 在那里叫
`method_10263`。同一行在 NeoForge（mojmap）过、在 Fabric 挂。

推论，每个新增方法都必须守：**参数和返回值只能是 StageWright 自己的类型、字符串 id、
或基本类型。** 不能返回 `ItemStack` 让作者去调 `getCount()`，要返回一个我们自己的值对象
或直接返回 `int`。

这条同时解释了为什么这些能力**不能**只做成 worlddriver 的 RPC 动词：进程内场景走不到
RPC，而且 §2.3 说的那个杠杆会丢。

### 2.2 facet，不是方法堆

`SceneContext` 已经 482 行，硬预算 3000。照 `arena()` / `perf()` 的先例，新能力各自
一个 facet 类，`SceneContext` 上只多一个返回它的方法：

```java
s.items()        // 物品与背包
s.advancements() // 进度
s.recipes()      // 配方与闭包
s.loot()         // 战利品表
s.structures()   // 结构
s.menu()         // 服务端容器菜单
s.equip()        // 装备槽（含 Curios）
s.quests()       // FTB Quests（探针式）
```

好处不只是行数：facet 名本身是文档，`s.recipes().` 一敲，IDE 就把这一族列出来了。

### 2.3 放在 `:stagewright-api`，因为它们都是原版 API

`items` / `advancements` / `recipes` / `loot` / `structures` / `menu` / `equip` 全部只用
`net.minecraft.server.*` 的东西，**一行 worlddriver 都不需要**。所以它们进
`:stagewright-api`，那里 `api/build.gradle` 顶上写着 "KEEP IT DEPENDENCY-FREE"——
指的是不许碰 worlddriver 和 harness，Minecraft 本来就在。

这带来这份设计最大的杠杆：**JS 场景是靠 Rhino 反射直接打在 `SceneContext` 上的**
（`JsScenes.invoke` 里那句 `cx.javaToJS(ctx, scope)`）。所以

> 一个 Java facet 写完，**整合包作者的 `.js` 场景同时就有了**——不用加 MCP 动词，
> 不用改 prelude，不吃 LLM 的 token 预算。

ATM10 那一半（CLI + `.js` 场景，没有 Gradle 项目）因此不需要单独的能力建设。这是
"能力优先"最划算的地方。

### 2.4 第三方 mod 用探针，绝不用依赖

Curios 和 FTB Quests 不能进依赖：`:stagewright-api` 是每个 conformance fork 都要
compile 的东西，给它挂一个 Curios 依赖等于要求每个被测 mod 都装 Curios。

照抄 `StageWrightCommon.driverPresent()` 那个已经验证过的做法——**按名字 `Class.forName`
探针 + 反射调用**：

- 在场 → facet 正常工作；
- 不在场 → `s.skip("这个场景需要 Curios，这个运行时没有")`，**记录为 PASS 并带原因**，
  而不是 `NoClassDefFoundError`，也不是静默通过。

`SceneContext.player()` 在没有玩家时 `skip` 的先例就是这个语义，直接沿用。

`StageWrightCommon.installVerbHooks` 的注释还留了一个更细的坑，一起抄过来：**探针必须
在前，且必须按名字**——`List.of(A::foo, B::bar)` 会在构建这个 list 时就把所有类解析掉，
于是 try/catch 根本接不到 `NoClassDefFoundError`。

## 3. 逐个 facet

下面每个都给**最小可用面**。宁可少，加容易，减难——每个方法都会进整合包作者的词汇表。

### 3.1 `s.items()` — 物品与背包

```java
s.items().give("minecraft:diamond", 64);          // 给当前玩家
s.items().count("minecraft:diamond");             // 背包里有多少
s.items().has("twilightforest:naga_scale");       // 有没有
s.items().clear();                                // 清空（scene 结束自动还原）
s.items().hold("twilightforest:ironwood_sword");  // 拿在主手
s.items().eat("twilightforest:hydra_chop");       // 吃掉，返回吃之前/之后的饱食度
s.items().foodLevel();  s.items().saturation();
s.items().effects();                              // List<String>，效果 id
```

id 全走字符串（§2.1）。未知 id **抛异常**，不静默变空气——`block()` 的注释已经论证过
这个选择："Unknown ids fail loudly at the point of use rather than resolving to air, which
is indistinguishable from 'the scene placed nothing'."

`clear()` 自动登记 `cleanup`：背包是玩家身上的状态，跨场景活着，和 waystones 那个
"数据库大小变成场景顺序的函数"是同一类病。

### 3.2 `s.advancements()` — 进度（TF 与 ATM 共用的那一条）

```java
s.advancements().grant("twilightforest:progress_naga");   // 补齐所有剩余 criteria
s.advancements().revoke("twilightforest:progress_naga");
s.advancements().has("twilightforest:progress_naga");     // boolean
s.advancements().remaining("...");                        // List<String>，还差哪些 criteria
s.advancements().awaitEarned("...", 200);                 // 挂一个 await step
```

实现取自 fork 里已经在跑的写法（1.21.1 正确）：

```java
PlayerAdvancements adv = player.getAdvancements();
AdvancementHolder holder = player.getServer().getAdvancements().get(id);
for (String criterion : adv.getOrStartProgress(holder).getRemainingCriteria()) adv.award(holder, criterion);
// 查询
adv.getOrStartProgress(holder).isDone();
```

**`grant` 必须登记 `cleanup` 做 revoke。** 进度是持久化在玩家档案上的，一个授予了
`progress_lich` 的场景会让后面每个"未解锁时应该被拦住"的场景失效——而那类场景失败时
指向的是 TF 的门控逻辑，不是指向前一个场景。这是 waystones 那个教训的第二次应用。

### 3.3 `s.recipes()` — 配方与闭包（ATM 之星那条）

```java
s.recipes().producing("allthetweaks:atm_star");   // List<RecipeInfo>，谁能产出它
s.recipes().ingredientsOf(recipeId);              // List<String>，需要什么
s.recipes().closureOf("allthetweaks:atm_star");   // 递归展开，返回 Closure
```

`Closure` 是这个 facet 的产品，而不是一堆散方法：

```java
closure.depth()            // 图深
closure.itemCount()        // 触及多少种物品
closure.leaves()           // 展不下去的叶子（原料）
closure.danglingItems()    // 被引用但没有任何配方、也不在叶子白名单里 —— 断边
closure.cycles()           // 环
```

`danglingItems()` 是这一族里最有价值的一个：整合包作者改了配方之后，"星星还合得出来吗"
这个问题的答案就是它是不是空的。

底座是 `level.getRecipeManager().getRecipes()`（worlddriver 的 `RecipeApi` 已经在用同一个
入口，两边可以对照，但不共享代码——`:stagewright-api` 不依赖 worlddriver）。

**遍历必须有上限**：ATM10 有 452 个 mod，配方图很大且几乎肯定有环。`closureOf` 带
`maxNodes` / `maxDepth`，超了**抛异常并说清超在哪**，不返回半个结果——半个闭包看起来
和"图是闭合的"一模一样。

### 3.4 `s.loot()` — 战利品表

```java
s.loot().exists("minecraft:chests/simple_dungeon");
s.loot().roll("twilightforest:structures/hedge_maze", 20);  // 掷 20 次，返回 List<String> 物品 id
s.loot().rollCounts(tableId, 100);                          // Map<String,Integer>，出现次数
```

1.21 的入口是 `server.reloadableRegistries().getLootTable(ResourceKey<LootTable>)`。
掷需要 `LootParams`，最小可用形态给一个"origin 处、无杀手、无工具"的默认上下文，
并允许指定 luck。

**次数要显式**：一次掷出空手是完全合法的战利品表行为，断言"掷一次拿到东西"是个会
随机红的测试。API 只提供"掷 N 次"，让作者被迫想清楚他要断言的是分布还是存在性。

### 3.5 `s.structures()` — 结构

```java
s.structures().locate("twilightforest:hedge_maze", 3000);  // 返回 Pos 或 null
s.structures().existsAt("...", x, y, z);                   // 该点是否落在该结构内
```

`locate` 是**慢**的（可能扫几千格、加载大量区块），所以它必须：

- 只在 `Terrain.GENERATED` 的场景里可用，别的地形抛异常说明原因；
- 默认半径小，并且把实际扫描耗时 `record` 出来——一个花了 8 秒的 locate 应该在结果里
  看得见，因为它会挤占同一轮里别的场景的 tick 预算。

### 3.6 `s.menu()` — 服务端容器菜单（GUI 的可测那一半）

这里要把话说清楚，因为"GUI 测试"这个词很容易越界：

- **服务端菜单**（`AbstractContainerMenu`：槽位、点击、配方匹配、结果输出）**是可测的**，
  而且这是 TF 的 Uncrafting Table 绝大部分逻辑所在——它就是一个菜单实现。
- **客户端屏幕**（像素、布局、渲染）**不在这个 facet 里**，那是客户端探针 / attached
  的活（`mc.client.screen.tree` / `mc.client.input.*`）。

```java
s.menu().openAt(dx, dy, dz);              // 用方块的 MenuProvider 给玩家开菜单
s.menu().slotCount();
s.menu().put(slot, "twilightforest:ironwood_sword", 1);
s.menu().item(slot);                      // 槽位里是什么（字符串 id）
s.menu().count(slot);
s.menu().click(slot);                     // 走 menu.clicked(...)，真实路径
s.menu().close();                         // 自动登记 cleanup
```

必须是玩家在场的场景（`playerHere()`），所以在 bare dedicated server 上自动 `skip`——
和 waystones 那两个场景同一个模式。

### 3.7 `s.equip()` — 装备槽，Curios 在场时扩展

```java
s.equip().armor("head", "twilightforest:knightmetal_helmet");   // 原版 EquipmentSlot
s.equip().curio("necklace", "twilightforest:charm_of_life_2");  // 需要 Curios，否则 skip
s.equip().curioSlots();                                          // List<String>，这个运行时有哪些槽
s.equip().inCurio("necklace");                                   // 槽里是什么
```

Curios 半边全反射（§2.4）：`top.theillusivec4.curios.api.CuriosApi#getCuriosInventory`。
装备之后要断言的"效果"由场景自己用 `s.items().effects()` / 玩家属性去读——facet 不猜
什么叫"效果"。

### 3.8 `s.quests()` — FTB Quests（探针，ATM 专用）

最小面，因为它是四个 facet 里唯一一个只服务一个场景的：

```java
s.quests().loaded();                       // FTB Quests 在不在
s.quests().chapters();                     // List<String>
s.quests().questCount();
s.quests().isComplete(questId);
s.quests().complete(questId);              // 领取/标记完成
s.quests().dependenciesOf(questId);
```

`dependenciesOf` + `chapters` 就够做"任务图闭合"那类断言了——和 §3.3 的配方闭包同一个
思路：**测图，不测玩**。

## 4. 维度：这一条要动 harness，不是加 facet

其余七个都是 facet，这一个不是。今天 `SceneContext` 的 `level` 是构造时定死的一个
`ServerLevel`，`StageWrightHarness` 在里面按 grid 发原点、forceload、跑完 sweep + 审计。
TF 的整个内容在**另一个维度**，ATM 的 `allthemodium` 也是。

所以是 `Scene` 上的一个声明，和 `withTerrain` / `withClock` 平级：

```java
@SceneDef(budget = 400, dimension = "twilightforest:twilight_forest")
```

```javascript
scene('tf.nagaCourtyardGenerates', 400, function (s) { … }, { dimension: 'twilightforest:twilight_forest' })
```

harness 侧要处理的四件事，每件都有现成的对应物可抄：

1. **维度不存在**（mod 没装）→ 场景 `skip` 带原因，不是 RED。和 `Terrain.parse` 对未知
   名字抛异常不同——地形是 StageWright 自己发的，维度是别人的。
2. **grid 原点在新维度里同样要 forceload**，`forceChunks` 已经是按 `ServerLevel` 传的，
   改动小。
3. **`WorldPin` 要不要跟着钉**：钉的是 gamerule 和时间，那是 server 级/level 级混合的。
   要核清楚哪些是 per-level，否则会出现"钉了主世界、场景在暮色森林"的假保证。
4. **arena 审计的基线**要按 level 取，否则跨维度场景一定报泄漏。

`Terrain` 今天的三个值（`run_world` / `superflat` / `generated`）是 StageWright 自己发的
维度，所以 `dimension` 和 `terrain` 是**互斥**的：指定了别人的维度，就不能再要求
StageWright 把地形换掉。这个冲突要在 `Scene` 构建期抛，不要留到运行期。

## 5. 落地顺序

按"两个场景都要 / 只有一个要"和"纯原版 / 要探针"排：

1. **`s.items()`** — 一切的地基，没有它连"给把剑"都要写 `/give` 字符串。
2. **`s.advancements()`** — TF 通关流程 + ATM 进度解锁，两边共用，且 TF 的门控逻辑
   直接建立在它上面。
3. **`s.recipes()` + `Closure`** — ATM 之星那条主线，秒级可跑，价值密度最高。
4. **`s.menu()`** — TF Uncrafting Table；也是"GUI 可测的那一半"的定义。
5. **维度**（§4，动 harness）— TF 的内容全在暮色森林里，不打通这条 TF 只能测注册表。
6. **`s.loot()` / `s.structures()`** — 两边都要，但都可以靠前四条先绕过去。
7. **`s.equip()`（Curios）/ `s.quests()`（FTB）** — 探针式，各自只服务一个场景，最后做。

每一步的验收都是同一句话：**用它写一条真场景，在 conformance fork 里跑绿**——
1–4 用 twilight-forest（Gradle 路径），3 同时用 ATM10（CLI + `.js` 路径，验证 §2.3
那个"Java facet 自动惠及 JS"的杠杆真的成立）。

## 6. 实际落地时改了什么（对照 §3 的设计）

三处与设计不同，都是核过实现之后改的：

- **维度不是新机制，是放开一个已有的。** §4 说要动 harness。真去看的时候发现 `Terrain`
  本来就是"这个场景在哪个维度"——`StageWrightHarness.levelFor` 早就按场景解析 level 了。
  所以改动只是：允许任意维度 id、和 `terrain` 互斥（在 `Scene` 构造期就拒绝）、加一条
  建筑高度钳制、以及**把"缺席"分成两种**——别人的维度缺席记 SKIP，StageWright 自己的
  地形维度缺席仍然是 ENV_FAIL，因为后者意味着我们的数据包没加载。
- **`s.loot()` 必须自己校验参数集。** 设计里写的是"`getRandomItems` 会抛，我们翻译一下
  错误信息"。它不抛。见 §7。
- **`s.quests().complete()` 不登记 cleanup，而且这是故意的。** FTB Quests 没有一个能连
  奖励和被解锁的后继任务一起回滚的公开接口，所以"撤销"只能是半个撤销假装成整个。
  正确姿势是靠 `cleanWorld`（每个门拓扑本来就删档），并把这条写在方法的 javadoc 里，
  而不是登记一个没人说得清效果的 cleanup。

## 6.5 交付缺口：harness 怎么进到别人的运行时（第三方 mod 第一次真跑才暴露）

facet 全部写完、`cap.*` 25 个自测场景全绿之后，把 Twilight Forest 接上来，**gate 跑完
一个 20 分钟的 run，一条结果都没写**，日志里连 stagewright 这个词都没有。

原因不在 facet，在交付：**MDG（ModDevGradle）的 dev run 没有"再加一个 mod"这回事**。

- worlddriver 是 loom 构建，`modLocalRuntime` 一行就把 harness jar 摆到 FML 面前——所以这
  个洞在只有一个消费者的时候根本不存在。
- MDG 没有对应物。退而求其次把 harness 加到 `runtimeClasspath`：jar **确实**进了 MDG 生成
  的 classpath 文件，FML 的 discoverer **确实**按名字打印了它——打印的是
  `Skipping ... because it was already located earlier`，即被当作普通游戏库认领了。
  mod 列表里没有它。游戏正常启动、正常 tick、什么都不写，gate 报"游戏从未 arm"。
- **`mods/` 才是路**。`ModsFolderLocator` 在任何 run（dev 或生产）都读它，1.21 的 NeoForge
  发布 jar 丢进去不需要重映射。附带一个更好的性质：这样跑的是**整合包作者会装的那个
  artifact**，不是它的 dev 变体。

修法不是在消费者的 build.gradle 里写 25 行 Copy——那是把框架的问题摊给每一个使用者。规则
搬进 `engine/ModInstall`（CLI 早就有同一套规则，`RunDirectory` 的 javadoc 里"两份会漂移"
的论证一字不改地适用），plugin 的 topology 增加 `installMods`：

```groovy
stagewright { topologies { dedicatedServer { installMods.from configurations.localRuntime } } }
```

其中最不显然的一条是**先扫再装**：jar 名带版本，升级会落在旧版**旁边**而不是**上面**，
FML 于是看到两个 StageWright——要么拒绝启动，要么 arm 旧的那个，然后把旧代码的行为报成新
代码的。

## 7. 五个静默失败坑（都是真踩到的，不是设想）

前四条是自测场景第一次真跑时抓到的，第五条要等接到真整合包上才会出现。它们**全部**属于
"代码跑了、断言过了、其实什么都没测到"的形状，没有一条是 review 看出来的。

1. **带默认值的注册表让 null 检查永远不生效。** `Ids.require` 原本查 `registry.get(key)`
   是不是 null。但 `BuiltInRegistries.ITEM` / `BLOCK` 是 **defaulted** registry，对未注册
   的 id 返回 `minecraft:air` 而不是 null。于是每个拼错的 id 都变成一个空 `ItemStack`，
   下游报出来的是"背包满了""槽位是空的""配方没有原料"——**每一种症状，除了拼写错误
   本身**。讽刺的是这份设计 §2.1 就是在警告这个，然后第一版实现踩了进去。
   修法：`registry.containsKey(key)`。
2. **`LootTable.getRandomItems` 不校验参数集。** 拿箱子参数去滚
   `minecraft:entities/zombie`，它不抛异常，**返回空列表**——因为表里的条件求值不出来。
   场景会得到"这怪不掉东西"这个错误结论，而它和正确结论长得一模一样。
   修法：自己比 `table.getParamSet()`，不匹配就拒绝，并在错误信息里点明"游戏本来是不会
   告诉你的"。
3. **`getLootTable` 对缺失的表返回 `LootTable.EMPTY` 而不是 null。** 同一类。一个被改名
   的战利品表读起来就是"一个什么都不掉的表"——而那是表的合法状态，所以和 bug 不可区分。
4. **`SceneContext.command` 的命令源没有实体，`@s` 解析不到任何人。** 这条 `command`
   自己的 javadoc 已经警告过 `@p`，但 `@s` 同样中招。写食物场景时用
   `/effect give @s minecraft:hunger` 掉血，命令直接抛。修法不是绕过命令，而是给
   `s.items()` 加 `hunger(food, saturation)` ——**测试的前置状态应该由 facet 直接设置，
   不该绕道命令层**。

第五条是接到 ATM10 上第一次跑出来的，形状一模一样，但**修不掉，只能报**：

5. **`Recipe#getIngredients()` 是个返回空表的 default 方法。** 于是任何自定义 recipe type
   （模组机器配方基本全是）对"你的原料是什么"回答的是**"没有原料"**，而不是"我不回答"。
   `closureOf('allthetweaks:atm_star')` 因此走出来 11 个物品、22 条配方、depth 10、
   **leaves=0、unresolvable=0**——它压根没离开 star ↔ star_block 的压缩环，而且看上去
   非常健康。ATM 之星真正的原料树（MI 多方块的 `star_altar`）完全不可见。
   这个没有别的接口可问，所以修法是**把不确定性变成输出**：这类配方进
   `Closure.opaque()`，`closureOf` 的 javadoc 写明"只能看到 `getIngredients()` 能看到的
   那么远"。空的 `opaque` 才意味着这次遍历是完整的。

共同点值得单独说一句：**Minecraft 的查找 API 大量使用"默认值"而不是 null 或异常**——
defaulted registry 给你 air，缺失的战利品表给你 `LootTable.EMPTY`，参数集不匹配给你空列表，
没实现的 `getIngredients` 给你空列表。所以任何"id → 对象"的封装，默认都是不安全的。
前四条能靠 `Ids` 一个类收口；第五条收不了口，因为缺的不是校验而是接口——那就必须让调用者
看见"我这次没看全"，而不是让它长得像"看全了，就这么点"。

## 8. 会踩的坑（先记下来，省得再发现一次）

- **每个改玩家状态的方法都必须登记 `cleanup`。** 背包、进度、装备、打开的菜单全部
  跨场景存活。waystones 那次是"数据库里 156 条，144 条是上一个场景留的"；这里同类
  的东西有四种。
- **`locate` 和 `closureOf` 是这一族里唯二可能跑很久的**，都必须有上限并把耗时
  `record` 出来。场景体跑在 tick 上，一次 8 秒的 locate 就是 160 个 tick 的预算。
- **JS 那边没有编译器。** 每个 facet 上线时，`.js` 侧要有一条对应的冒烟场景，
  否则"Java 能用、JS 挂在名字上"这类错要等到整合包作者手里才发现。
- **未知 id 一律抛异常。** 八个 facet 全部涉及 id 字符串，静默降级会让场景在错误的
  前提下通过——这是整份设计里最容易犯、也最难查的一类错。
