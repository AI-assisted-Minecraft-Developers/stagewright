# 能力扩展面：让三方模组和整合包接上原版没有的东西

> 状态（2026-08-05）：设计 + 落地 + 双向验证。前置是 `scene-capability-design.md` 里那八个
> facet —— 那一份解决"场景能看见什么"，这一份解决"**看不见的东西谁来补**"。
>
> §1–§6 是第一轮：SPI + `Probe`，两扇门。**§7 起是第二轮**，补的是这两扇门之间的空档——
> 声明式描述符（不写 Java 也不用反射字符串）、按**平台能力**而不是按模组写的通用实现、
> 以及"这个运行时到底装了什么"这个所有条件判断的地基。
>
> 验证是两头的：worlddriver 自测跑（**没有** Curios / FTB Quests）证明"注册表工作、模组不在
> 场时 skip 而不是炸"；ATM10（**两个都有**）用 `.js` 场景证明"整合包作者不写一行 Java 也能
> 用上模组作者提供的适配器"。只跑其中一头都证明不了这套机制成立。

## 0. 缺口，一句话

今天 `s.equip()` 的 Curios 那一半和 `s.quests()` 整个，都是**写死在 `:stagewright-api` 里的
反射**。于是：

- 想让第 3 个模组可测 → 改 StageWright、发版、所有人跟着升。**框架成了瓶颈。**
- 整合包作者手里那个我们没听说过的模组 → **完全没辙**，连一条难看的路都没有。
- 那两份反射字符串没有任何编译期约束，模组一改包名，错误出现在**别人**的 gate 上。

八个 facet 覆盖的是原版 API。原版之外的每一样东西——Curios 的槽位、FTB Quests 的任务图、
Mekanism 的化学罐、Create 的应力网络、AE2 的 ME 网络——都在这个洞里。而整合包测试的价值
恰恰**几乎全部**在这个洞里。

## 1. 两类人，两扇门

| 谁 | 手里有什么 | 需要的门 |
|---|---|---|
| **模组作者** | Java、构建、自己的类在 compile classpath 上 | 一个 SPI：写个适配器，任何人的场景都能用 |
| **整合包作者** | 一堆 `.js`、`config/`、`mods/`；**编译不了任何东西** | 要么有现成适配器，要么能用数据描述反射 |

两扇门都要开。只开第一扇，整合包作者被挡在外面——而 ATM10 那半个目标正是他们。
只开第二扇，模组作者被迫写反射去调**自己的**类，荒谬。

## 2. 第一扇门：`CapabilityProvider`

沿用 `SceneProvider` 已经验证过的形状——接口在 `:stagewright-api`，`ServiceLoader` 在
`:stagewright-common` 里发现：

```java
public interface CapabilityProvider {
    String name();                            // "curios" / "ftbquests" / "mymod:rituals"
    boolean availableIn(SceneContext ctx);    // 探针
    Object facet(SceneContext ctx);           // 场景真正调的那个对象
}
```

场景侧：

```java
s.capability("mymod:rituals")                 // 不在场 → skip，并列出在场的有哪些
s.capability("mymod:rituals", Rituals.class)  // Java 侧带类型校验的形式
s.hasCapability("mymod:rituals")              // 想分支而不是 skip 的场景用这个
s.capabilities()                              // 这个运行时提供了哪些 —— record 出来
```

```js
s.capability('mymod:rituals').consecrate(0, 1, 0);   // JS 侧一模一样，无需任何改动
```

**不在场 = skip，不是 fail。** 和 `SceneContext.player()`、和现在 Curios/FTB Quests 的语义
完全一致：模组没装不是整合包的 bug，但也绝不能静默通过。

### 2.1 发现必须是防御式的，否则这扇门开不了

一个针对 Curios 写的适配器 **import 了 Curios 的类**。没装 Curios 的运行时里，
`ServiceLoader` 一实例化它就是 `NoClassDefFoundError`——而且是在**整个发现流程**里炸，
把别人的 provider 也带走。

`StageWrightCommon.installVerbHooks` 的注释里已经记过这个坑的另一半：
`List.of(A::foo, B::bar)` 会在构造 list 时就解析全部类，try/catch 根本接不到。

所以发现走 `ServiceLoader.stream()`，**逐个 `provider.get()` 外面套 try/catch(Throwable)**：

- 加载失败 → 这个 provider 记为"不在场"，并记下**是哪个类、少了什么**；
- 其他 provider 不受影响。

这条规则同时让适配器可以**正常地、带类型地**写——不必为了活下来而写成反射。一个适配器
在它的模组不在场时加载不了，语义上本来就正确。

### 2.2 name 的约定

`"curios"`、`"ftbquests"` 这种裸名留给 StageWright 自带的；三方一律 `"<modid>:<what>"`。
重名**直接拒绝并报出两个类名**——理由和场景重名一样：后注册的悄悄覆盖先注册的，
场景于是测了一个它没打算测的东西。

## 3. 第二扇门：`Probe` —— 给写不了 Java 的人的反射，带一条让它成立的硬规则

整合包作者要碰一个我们没有适配器的模组，只剩反射。给，但要**有界、且失败时说人话**：

```js
if (!s.hasClass('com.simibubi.create.Create')) return s.skip('needs Create');
var api = s.probe('com.simibubi.create.content.kinetics.KineticNetwork');
var stress = api.on(networkObject).call('getCapacity').asDouble();
```

### 3.1 那条硬规则：`Probe` 拒绝 `net.minecraft.*`

这不是保守，是**正确性**。JS 安全规则（`SceneContext.originX()` 的 javadoc）说的是：

> 场景可以**持有** Minecraft 对象，但绝不能**在它上面按名字调方法**。

原因是生产 Fabric jar 走 intermediary 映射，`getX` 在那儿叫 `method_10263`。
而**模组自己的类不参与重映射**——`com.simibubi.create.*` 在哪个运行时都叫这个名字。

于是同一件事，对模组 API 是安全的，对 Minecraft 是必然出错的。`Probe` 把这条变成机器
规则：**类名落在 `net.minecraft.*` 直接抛**，错误信息解释为什么，并指回对应的 facet。

把 Minecraft 对象**作为参数传进去**是允许的——那是持有，不是调用。这正好让
`s.player()`、`s.level()` 拿到的东西可以喂给模组 API，而整条链仍然是安全的。

### 3.2 返回值一律再包一层

非基本类型的返回值包成新的 `Probe`，而不是裸对象。两个好处：可以继续链下去；
以及 JS 侧**永远拿不到一个它可能会去调方法的裸 Minecraft 对象**。落地时用
`asString()` / `asInt()` / `asDouble()` / `asBoolean()` / `isNull()` 显式出界。

### 3.3 找不到就把候选列出来

`Ids.suggest` 已经立过规矩。反射的错误信息如果只说 "no such method getCapacity"，
作者要去反编译；如果说 "…—— 这个类上的方法有 getCapacity(int), getNetworkCapacity(),
capacity()"，作者当场就改完了。参数个数不匹配同理。

## 4. 自带适配器：Curios 与 FTB Quests

两个已经存在的集成注册成 provider，`s.equip()` / `s.quests()` 保留为便捷入口。
**代码不搬家**——这一步要的是让它们成为这套机制的实例，从而：

- `s.capabilities()` 能如实报出这个运行时有什么，一条 skip 才追得到源头；
- 它们的探针语义（在场→工作，不在场→skip）从两处特例变成一条通则；
- 新写第三个集成的人有两个能照抄的样板。

## 5. 落地顺序

1. `CapabilityProvider` 接口（`:stagewright-api`）。
2. `Capabilities` 注册表 + 防御式发现（`:stagewright-api`，`ServiceLoader` 在场景侧就够用）。
3. `SceneContext`：`capability` / `hasCapability` / `capabilities`。
4. `Probe` + `SceneContext.probe` / `hasClass`。
5. 自带 Curios / FTB Quests 两个 provider。
6. `cap.*` 自测场景：注册表读得到、重名被拒、不在场是 skip、`Probe` 拒绝
   `net.minecraft.*`、找不到方法时列候选。
7. prelude 注释里加 JS 侧用法（**不加函数**——`s.capability` 走 Rhino 直达，
   prelude 每加一个符号都是整合包作者要多学的东西）。

## 5.5 落地时踩到的一条（同一个物种的第六例）

`s.capabilities()` 返回的是**可用**的能力。于是：

- 发现流程一个 provider 都没找到 → 返回空；
- 找到两个但两个模组都不在场 → 也返回空。

对 `hasCapability(...)` 的**每一个问题，这两种运行时给出完全相同的答案**。也就是说：
service 文件没进 jar、被 shadow 合并吃掉、META-INF 没打包——这些全都表现为"这个整合包
没装那个模组"，而自测套件照绿。

自测场景第一版正是这么写的（断言 `hasCapability('curios')` 是 false），**它对自己要防的那个
故障完全免疫**。而且第一次真跑时 skip 信息就把这件事喊了出来："no capability provider is
registered at all" ——那句话当时是错的，两个 provider 明明加载了，只是都不可用。

修法是把两个问题分开：`capabilities()`（可用）与 `capabilityProviders()`（**加载了的**，
不管可不可用）。自测断言 `capabilityProviders()` **必须**包含 `curios` 和 `ftbquests`，
而 `capabilities()` **必须**是空的——这两条一起，只有"框架正常 + 模组不在场"能同时满足。

skip 信息也跟着分成三句，因为这是三种不同的处境：provider 说不（模组没装，正常）／
这个名字没有 provider 但别的有（名字写错，或那个适配器没发布）／**一个都没加载**
（框架自己的 service 文件没进 jar —— 这是 StageWright 的 bug，穿着"模组没装"的外衣）。

## 6. 会踩的坑

- **`ServiceLoader` 的上下文类加载器。** 现有代码已经踩过：注册表必须在**服务器线程**上
  解析一次（`StageWrightCommon` 的注释）。能力发现挂同一个时机，别再解析一次。
- **`availableIn` 不能有副作用，而且会被调用多次。** 结果缓存在一次 run 内。
- **不要让 `facet()` 每次都新建。** 场景拿到的应该是同一个对象，否则带 `cleanup` 的适配器
  会登记多份。
- **`Probe` 是逃生口，不是 API。** 一个整合包里同一段反射出现三次，就该变成自带适配器 ——
  文档里明说，否则这扇门会变成不写适配器的借口。

---

# 第二轮：把两扇门之间的空档补上

第一轮开了两扇门：模组作者写 `CapabilityProvider`，整合包作者写 `s.probe('类名')`。
真拿 ATM10（452 个模组）跑过之后，中间那段空档很清楚：

- 整合包作者**没有**适配器可用时，只剩把类名硬编码进**每一个**场景。模组一改包名，
  整包场景一起烂，而且烂在别人的 gate 上。
- "写个适配器"这条路对**一个模组**成立，对**一类模组**荒谬：Mekanism、Thermal、Powah、
  Industrial Foregoing…… 它们的能量 API 互不相同，但**能量能力**是同一个。按模组写是订阅制，
  按标准写是一次性成本。
- 所有条件判断的第一句都是"这个包里有没有 X"，而在此之前，场景根本问不出这句话。

## 7. 地基：`s.mods()`

```js
s.mods().loaded('mekanism')      s.mods().version('create')      s.mods().count()
s.mods().require('ae2')          // 不在 → skip，理由里带模组名
s.mods().any('a', 'b')           // fork / 改名 / 兼容层，一条断言接住
```

模组列表在 **loader** 手里（`FabricLoader` / `ModList`），而 `:stagewright-api` 按它自己
build 文件顶上的规矩**两个 loader 都不依赖**。所以由各自的 entrypoint 在启动时**推进来**
（`Mods.install(map)`），不走反射——loader 的类确实不参与重映射，反射是能用的，但那是拿
两个可能写错的字符串去够别人已经**有类型**拿在手里的数据。

### 7.1 "没装"和"列表没到"必须分开

没安装列表时，`Mods` **抛 `SceneFailure`**，绝不返回空表。

空表会让每一个 `loaded()` 都答 false，于是整包的条件场景全部 skip，理由是"某模组没装"——
而它明明装着。**框架自己的接线坏了，却穿着"模组没装"的外衣**，这正是 §5.5 那条的同一个物种。
第七例。

## 8. 声明式能力：`config/stagewright/capabilities/*.json`

`CapabilityProvider` 要一个 jar。整合包作者手里只有一个文件夹——让他为了写个类名去搭
Gradle，和让他为了写场景去搭 Gradle 是同一个错误。所以描述符是**文件**：

```json
{
  "name": "mymod:rituals",
  "mods":    ["mymod"],
  "classes": ["com.mymod.RitualApi"],
  "items":   ["mymod:chalk"],
  "probe":   "com.mymod.RitualApi",
  "absent":  "MyMod 不在这个整合包里"
}
```

```js
s.capability('mymod:rituals').probe().callStatic('lookup');
```

买到的是**间接层**：类名活在一个文件里，而不是活在每一个够它的场景里。

- 条件可以是 `mods`（全要）/ `anyMods`（有一个就行）/ `classes` / `items` / `blocks`。
  注册表那两条是"模组装了但内容没开"唯一能查到的地方——config 里关掉一个功能的模组，
  模组列表照样报在场。
- **一个条件都不写 → 加载时直接拒绝。** 不写条件的描述符在**任何**运行时都报可用，于是
  依赖它的场景会在没有那东西的包上跑起来，然后**为了一个跟包无关的理由失败**。
  拒绝文件比这好。
- 来源两处：harness jar 里的 `data/stagewright/capabilities.json`（自带的，见 §9），
  和整合包自己的 `config/stagewright/capabilities/*.json`。**同名时整合包的覆盖自带的**
  ——和"重名直接拒绝"（§2.2）刚好相反，而且是故意的：模组作者之间重名是事故，
  整合包覆盖框架是**唯一的修法**，某个 fork 挪了类，他不该等我们发版。

### 8.1 一个文件夹，按扩展名分流

`.js` 进 `config/stagewright/scenes/`，`.json` 进 `config/stagewright/capabilities/`，
`expected-scenes.txt` 本来就靠扩展名挑出来。CLI 和 Gradle 插件走同一份
`engine/RunDirectory` 逻辑。给一个没有构建工具的人两个 flag 两个目录，是把构建工具的
归档习惯扣在他头上。

## 9. 自带描述符：真正意义上的"自动识别"

`data/stagewright/capabilities.json` 里是 StageWright 自带的一小把（mekanism / ae2 /
create / ars_nouveau / apotheosis / mysticalagriculture）。**整合包什么都不用配**：有那个
模组就有那个能力，没有就是一条说清楚的 skip。

每一条都**卡在类上而不是卡在 modid 上**，而且每一个类名都是从模组 jar 里**读**出来的，
不是记出来的——类存在正好就是它的 probe 能工作的条件，modid 的拼写从此不进这个等式。

**故意短。** 这不是生态普查，`s.mods()` 已经能回答"有没有 X"。一条能进这个列表，
靠的是它点出一个**场景本来要硬编码**的稳定 API 根类。

用一个资源文件而不是一个资源目录：在 jar 里**枚举 classpath 目录**在 exploded 构建下能用、
在真 jar 里不能用——正好是"开发机上没事、每个用户那里都坏"的形状。

## 10. 通用实现：按**平台能力**写，不按模组写

三个 facet，`itemhandler` / `energy` / `fluids`，接口在 `:stagewright-api`
（`BlockInventory` / `Energy` / `Fluids`），实现在 `:stagewright-neoforge`。

```js
var inv = s.capability('itemhandler');
inv.insert(0, 0, 0, 0, 'minecraft:diamond', 3);
s.capability('energy').fill(0, 0, 0);
s.capability('fluids').fill(0, 0, 0, 'minecraft:water', 1000);
```

这才是"常见的实现"值得有的那个意思。几百个科技模组不共享 API，它们共享**能力**——
漏斗和管道就是这么跟它们说话的。所以照着能力写，**一次覆盖全部**，包括这个文件写完之后
才出现的模组；也不会误覆盖：没实现的方块报"没实现"，而不是猜。

- **接口在 api，实现在 loader 模块。** 能力属于 loader，而 api 两个都不依赖。接口放在
  api 才让 common 里的场景能持有这个类型，同时这也是一张长期有效的邀请函：谁按 Fabric
  那边的 storage API 实现同一组接口，**现有场景一行都不用改**。在那之前，Fabric 跑报
  能力不在场，场景记一条说明原因的 skip。
- **坐标是场地相对的 `(dx, dy, dz)`**，和 `SceneContext` 上其他方块动词一致。收绝对坐标
  会成为 API 里唯一一处要求场景知道自己被放在哪的地方——而那正是网格存在的目的。
- **进出只有基本类型和字符串 id。** §2 的 JS 安全规则，一个跨 loader 边界的 facet 没有
  任何余地去弯它。
- **没有 handler 的方块是 FAIL 并报出方块名**，不是 null、不是 0。对一个根本没有库存的
  东西断言库存，本身就已经发现问题了，值得一句人话；想分支的场景用 `present()`。
- 原版容器也实现 `itemhandler`——所以**机制**能在一个模组都不装的情况下证明（箱子答 27 格），
  只有**覆盖面**需要拿整合包来证。

### 10.0 写操作必须遍历六个面（这条花了两轮跑才拿到）

第一轮 ATM10：能量方块报 `capacity=1600000`、`canReceive()=true`，塞 1000 收 **0**。
第二轮加了"等 10 tick 再写"（怀疑 BlockEntity 没初始化完）：**两个时刻数字一模一样**，
猜错了。

真因：方块能力是**带 `Direction` 查的**，`null` 表示"不指定面"。绝大多数模组在 `null` 上返回
一个**内部视图**，带着机器真实的数值——所以**读全对**——而**插入**由机器的侧面配置决定，
只在被配成输入的那些面上放行。

所以现在写操作按 `null` → 六个面的顺序**逐个试，第一个吃下就停**（因此绝不会插两次），
handler 按引用去重（同一个 handler 挂六面的方块否则要被问七次）。

这是测试 API 该有的默认：一个断言"机器能充上电"的场景，不该先变成一个关于这台机器侧面配置的场景。
症状里没有任何一句话指向"面不对"——作者只会得出"机器坏了"或者"场地没在跑"的结论。

### 10.1 `mergeServiceFiles()` 是承重的

`META-INF/services/…CapabilityProvider` 现在在 `:stagewright-api`（自带的 Curios /
FTB Quests）和 `:stagewright-neoforge`（这三个）**两处都有**。shadow 默认同路径后写覆盖
先写——不加 `mergeServiceFiles()` 就只进一份，另一份里的每个能力都报"不在场"，
**和模组没装长得一模一样**。两个 loader 的 build 文件都加了，注释写在 service 文件里。

## 11. 双向验证：两头都跑，缺一头证明不了任何事

| | worlddriver 自测（46 mods，两个 loader） | ATM10（452 mods，NeoForge） |
|---|---|---|
| `s.mods()` | 46，能点名 `worlddriver` 版本 | 452+，能点名 `mekanism` / `allthemodium` |
| 自带描述符 | 6 个全部**注册**、全部**不可用** | mekanism / ae2 / create / mysticalagriculture / apotheosis **全部命中** |
| 整合包描述符 | `pack:driver`（指向 worlddriver 自己）解析、probe 通 | `atm:tweaks` / `atm:dimensions` 解析；**并覆盖自带的 `mekanism`** |
| `itemhandler` | NeoForge：原版箱子 27 格、插入/取出/拒绝都对<br>Fabric：一条说明原因的 skip | Mekanism 能量方块的槽位 |
| `energy` / `fluids` | 原版没有能量，`present()` 处处 false（真答案） | 能量方块充电、流体罐进水 |

一头单独看都是空的：**永远可用**和**永远不可用**各能骗过其中一张表的一半。
这张表两列一起看，才排除得掉。


---

## 12. 走通 vs 连通：两个整合包问题，不是一个

§1–§11 讲的是**接线**。把它真指向 ATM10 之后暴露出来的另一件事，和能力扩展无关但同样是这个目标的一半：

**这条路是不是连着的**（配方能解开、任务的依赖还在、成就的父节点没动）和**这条路能不能走**
（真去解锁一个成就、真去领一个任务、真让世界生成一座结构）是两个问题。前者是 450 个模组一起
更新时**静默**烂掉的那一半，值得占大头；但它不等于后者。

现在两边都有场景。走通那一半在 ATM10 上撞到三件事，每一件都值得写下来：

### 12.1 `.js` 场景 skip 会被报成 FAILED

Rhino 会把脚本调用的 Java 方法抛出的异常**包进 `WrappedException`**，于是 `JsScenes.invoke` 里
那句 `instanceof SceneSkipped` 永远不成立。被包住的 `SceneFailure` 照样走到 `ctx.fail`、照样报
FAIL——所以这个 bug 活了很久。**只有 skip 能看见它**：一个在没有玩家的拓扑上要玩家的场景文件，
被报成 **FAILED**，消息是 `Wrapped …SceneSkipped: no connected player`。

这把这个框架最核心的一条规则整个翻了过来：**东西不在场是一条记录在案的 skip，绝不是 failure。**
影响的是每一个整合包的每一个场景文件，对每一个不在场的模组、维度和玩家。发现它的正是那个"领任务"
的场景——恰恰是在服务器上**必须** skip 的那一个。

### 12.2 进度解锁 / 任务领取需要玩家，服务器上没有，而且绕不过去

`Advancements.grant` 取 `ctx.player()`；FTB Quests 把完成状态挂在**队伍数据**上，那是玩家加入时
建的。从服务器内部没有绕法：NeoForge 自己的 `FakePlayer` 对 `award(...)` 是**硬编码 `return false`**
——故意的，假玩家本来就不该挣到任何东西。

所以这两个场景照写，在这个拓扑上记一条**说清楚原因**的 skip，等这个包挂上客户端跑的那天原样通过。
机制本身不是没验证过：**Twilight Forest 的套件是真授予、真撤销**的，在有玩家的那个拓扑上。

### 12.3 "搜不到结构"和"这个结构根本放不下"是同一个返回值

`findNearestMapStructure` 在生成器**没有这个结构的 placement** 时立刻返回 null。于是"搜过了没有"
和"根本没得搜"除了看表**分不出来**：在这个包里找矿井 **2 毫秒**返回空，找村庄 **1394 毫秒**返回坐标。
这个包不放原版矿井。

一个只盯一种结构的场景，就是在断言**别人的 worldgen 配置**，并把它报成 worldgen 坏了。所以场景问
五种、断言**至少一种放得下**——这是任何能玩的包都不可能不满足的那条线。
