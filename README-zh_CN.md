# StageWright

*[English](README.md) · [简体中文](README-zh_CN.md)*

一个面向 Minecraft 模组与整合包的游戏内测试框架。测试可以是 JUnit 5 类，也可以是 YAML 或 JavaScript
文件，它们在真实运行的游戏里执行：测试拿到游戏本身、一块可以随意搭建的世界区域和可以操控的玩家，做完要做的事，
再断言结果。StageWright 按测试的要求启动对应的游戏，在里面执行测试，并把结果作为普通的 JUnit 结果报告给
Gradle、IDE 或它自带的命令行运行器。

它运行在 Minecraft 1.21.1 上，Fabric 与 NeoForge 共用同一套测试。

## 为什么需要它

Minecraft 自带游戏内测试设施，两个加载器也都提供了它。StageWright 补上的是它没有覆盖的部分：

- **测试声明自己需要什么样的游戏。** 场景文件写明是哪一种游戏——专用服务器、自带服务器的客户端，还是一台专用
  服务器加一个连上来的真实客户端——以及启动时用什么世界、什么文件、哪些模组。每一种都能确认另外两种确认不了的事实。
- **测试用代码搭建自己的世界。** 测试租用一块地块（plot）：一块空的世界区域，测试结束后原样恢复。因此不需要
  另外维护一份与断言同步的世界文件，测试之间也互不干扰。
- **测试从上到下顺序读得通。** 测试在测试线程上执行，而不是服务器线程，所以调用游戏、等待若干 tick、等待某个
  条件成立，都是普通的阻塞调用。
- **一套测试，两个加载器通用。** 测试写在共享代码里，同一套用例在 Fabric 和 NeoForge 上运行。
- **永远运行不了的测试算作失败。** 如果某个测试的 `@Requires` 在任何场景里都满足不了，整次运行会失败，而不是
  到处被悄悄跳过；游戏里加载失败的测试会报成失败，而不是凭空消失。
- **整合包不需要构建工具也能用。** YAML 和 JavaScript 测试直接放在整合包的服务器目录里；另有一套能力
  （capability）机制，让模组或整合包把原版没有概念的东西教给框架。

## 环境要求

| | |
|---|---|
| Minecraft | 1.21.1 |
| Java | 21 |
| Fabric | Loader 0.16 或更新，需要 Fabric API |
| NeoForge | 21 |
| WorldDriver | 每个游戏进程都必须有，见[下文](#stagewright-与-worlddriver) |

推送到 `master` 的提交会被构建、测试，并以 `0.1.0-alpha.<构建编号>+1.21.1` 发布到
`https://nexus.gardel.top/repository/maven-releases`，group 为 `net.magicterra`。在你需要的版本出现在那里之前，
先把 StageWright 发布到本地 Maven 仓库，使用 `0.1.0-snapshot+1.21.1`；发布顺序见
[发布与制品](docs/reference/publishing.md)。Gradle 插件是 `net.magicterra.stagewright`，命令行运行器是
`net.magicterra:stagewright-cli`。

## 最短的上手路径

### 在 Gradle 项目里

```groovy
// build.gradle，与 fabric-loom、architectury-loom 或 ModDevGradle 并列
plugins {
    id 'net.magicterra.stagewright' version '0.1.0-snapshot+1.21.1'
}

repositories {
    mavenLocal()
    maven { url 'https://nexus.gardel.top/repository/maven-releases'; content { includeGroup 'net.magicterra' } }
}

stagewright { tests { fabric { } } }
```

把场景文件放进 `src/testmod/resources/stagewright/scenes/`，测试放进 `src/testmod/java/`（YAML 和 JavaScript
测试放进 `src/testmod/resources/stagewright/tests/`），然后运行 `./gradlew stagewrightTestFabric`。

### 对整合包

```
java -jar stagewright.jar test <整合包的服务器目录>
```

它会在整合包自己的服务器里、用一个单独的世界，运行 `<服务器目录>/stagewright/tests` 下的 YAML 和 JavaScript
测试，场景文件取自 `<服务器目录>/stagewright/scenes`；整合包自己的世界和 `mods/` 都不会被改动。JUnit XML 报告写到
`<服务器目录>/stagewright/reports`。加上 `--client neoforge:1.21.1:<版本>` 还会安装一个客户端，并运行需要客户端的场景。

[入门](docs/guide/getting-started.md)完整介绍了这两条路径。

## 测试长什么样

一个场景文件 `stagewright/scenes/mymod.yaml`：

```yaml
topology: dedicatedServer
```

以及一个指定这个场景的测试：

```java
@Scene("mymod")
class FirstTest {

    @Test
    void aPlacedBlockReadsBack(Game game, @Plot.Size(8) Plot plot) {
        plot.place(1, 0, 1, Blocks.STONE);
        boolean stone = game.onServer(server ->
                server.overworld().getBlockState(plot.at(1, 0, 1)).is(Blocks.STONE));
        assertTrue(stone, "stone where the test placed it");
    }

    @Test
    void aBotKeepsWhatItIsGiven(Bot bot) {
        bot.give(Items.BREAD, 3);
        assertEquals(3, bot.count(Items.BREAD));
    }
}
```

同样的检查写成 JavaScript 测试：

```javascript
test('a placed block reads back', { scene: 'mymod' }, (t) => {
    const plot = t.plot({ size: 8 });
    plot.place(1, 0, 1, 'minecraft:stone');
    t.expect(t.call('mc.world.block', { pos: plot.at(1, 0, 1) }).type).equals('minecraft:stone');
});
```

其余内容见[编写测试](docs/guide/writing-tests.md)：逐 tick 操控机器人和生物、等待、软断言、测试之间的依赖、
在同一个世界上重启游戏、性能测量。

## 拓扑

| 拓扑 | 启动什么 | 适合做什么 |
|---|---|---|
| `dedicatedServer` | 一台无界面的专用服务器 | 大多数测试；也只有在这里才能观察到「只限客户端的调用被拒绝」和「玩家列表为空」。 |
| `integratedServer` | 一个自带服务器的游戏客户端 | 需要同一进程里客户端状态的测试，以及以帧为单位测量的东西。 |
| `dedicatedServerWithClient` | 一台专用服务器，加一个连上来的真实客户端 | 测试对象是两端之间连接的情况——也就是「单人能用」和「服务器上能用」之间的差别。 |

[拓扑](docs/guide/topologies.md)解释了哪些事实需要哪一种游戏。

## 文档

- [入门](docs/guide/getting-started.md)——两种使用方式，从头到尾。
- [编写测试](docs/guide/writing-tests.md)——测试 API，Java、YAML 和 JavaScript 三种写法。
- [能力](docs/guide/capabilities.md)——触及模组自己的机制，以及如何扩展。
- [拓扑](docs/guide/topologies.md)——几种游戏各自能确认什么。
- [Gradle 插件](docs/guide/gradle-plugin.md)——任务、属性和约定。

文件格式与发布的制品见 [`docs/reference/`](docs/reference/)，较大决策背后的考虑见 [`docs/design/`](docs/design/)，
[`docs/README.md`](docs/README.md) 是全部文档的索引。

## StageWright 与 WorldDriver

[WorldDriver](https://github.com/AI-assisted-Minecraft-Developers/worlddriver) 是一个把运行中的游戏暴露为一套
可编程 API 的 Minecraft 模组。StageWright 最初在它内部诞生，后来成为独立的仓库。StageWright 的测试通过
WorldDriver 的方法操作游戏，所以 StageWright 在 Fabric 和 NeoForge 上都必须有 WorldDriver：Gradle 插件会把它带进
测试运行；命令行运行器自带构建时所用的那个 WorldDriver 版本，整合包的 `mods/` 里没有 WorldDriver 时就加载它。

两者互相依赖，方向相反、位置不同：StageWright 的 API 和运行时编译时依赖 WorldDriver 发布的 API 和 common 模块；
WorldDriver 则用 StageWright 的 API 编写自己的测试，并用 StageWright 的 Gradle 插件运行它们。这并不构成循环——
WorldDriver 的正式代码从不依赖 StageWright。双方各自依赖对方的一个确切的已发布版本，写在 `gradle.properties` 里
（这边是 `worlddriver_version`，那边是 `stagewright_version`），因此谁都不需要对方的源码就能构建。
[发布与制品](docs/reference/publishing.md)说明了如何升级到更新的版本、如何改用你自己本地构建的另一方，以及在双方
都还没发布过任何东西时应按什么顺序发布。

## 许可证

[LGPL-3.0-only](COPYING.LESSER)——这是附加许可，修改的是 [`COPYING`](COPYING) 中的 GPL-3.0 文本。

仅仅*使用*本框架的模组——把这些制品声明为依赖、基于它的 API 编写测试、运行这些测试——不构成它的衍生作品，可以
采用任何许可证。copyleft 只约束 StageWright 自身的源码及其修改版本。

有一个目录是明文记载的例外：`engine/src/main/java/.../engine/json/` 是按 MIT 许可原样引入的 minimal-json。
那些文件头是上游的条款，保持原样；详见同目录下的 `VENDORED.md`。

命令行运行器的 `stagewright.jar` 里还带有第三方库。该 jar 中的 `META-INF/THIRD-PARTY-NOTICES` 逐一列出了它们
及其来源，`META-INF/licenses/` 中是它们的许可证全文。
