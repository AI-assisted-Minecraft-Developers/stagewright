# StageWright

*[English](README.md) · [简体中文](README-zh_CN.md)*

一个面向 Minecraft 模组与整合包的游戏内集成测试框架。一个 **scene（场景）** 就是一段代码：它在活着的
世界里搭起一个局面，驱动它，然后断言结果。StageWright 会按选定的进程拓扑启动游戏，在运行中的服务器上
执行这些场景，并写出一份可被 Gradle 任务或命令行运行器判读的机器可读结果。

它运行在 Minecraft 1.21.1 上，Fabric 与 NeoForge 共用同一套场景。

## 它为什么存在

Minecraft 自带游戏内测试设施，两个加载器也都把它暴露了出来。StageWright 要补的是它没有覆盖的那部分：

- **场景用代码搭建自己的世界**，坐标相对于框架分配的原点，因此不存在一份需要与断言保持同步的独立世界
  文件，场景里也不会出现任何绝对坐标。
- **注册一次，两个加载器通用。** 场景写在共享代码里，通过 service loader 被发现，所以同一套用例在
  Fabric 和 NeoForge 上运行，不需要按加载器各留一份副本。
- **每一趟运行都要证明自己仍然能报出失败。** 每趟运行都带着框架必须捕获的哨兵场景——一个必须被报成
  失败，一个必须被报成超时，一个必须从不执行。哨兵落错位置的运行会被判为「测量坏了」，这与「模组坏了」
  不是一回事，也绝不该被当成后者来读。
- **同一套场景可以在几种进程形态下运行**，因为每一种都能确立另外两种确立不了的事实。
- **skip 不等于覆盖。** 一个在当前环境下跑不了的场景会记录一条 skip；另有一项检查会在某个场景在本项目
  运行的每一种拓扑下都被 skip 时让构建失败。
- **整合包不需要构建工具也能用。** 场景可以就是整合包 config 目录里的 JavaScript 文件；另有一个能力
  （capability）接缝，让模组或整合包把原版没有概念的东西教给框架。

## 环境要求

| | |
|---|---|
| Minecraft | 1.21.1 |
| Java | 21 |
| Fabric | Loader 0.16 或更新，需要 Fabric API |
| NeoForge | 21 |

制品发布到本地 Maven 仓库，group 为 `net.magicterra`。Gradle 插件是 `net.magicterra.stagewright`。

## 跑通第一趟的最短路径

### 测试一个整合包，不需要构建工具

```
java -jar stagewright.jar --game-dir <整合包的服务端目录> --scenes <一个存放 .js 文件的目录>
```

这条命令会把对应加载器的框架构建装进整合包的 `mods/`，把你的场景文件装好，判断这个整合包该怎么启动，
运行它，并判读结果。进程退出码就是判词：`0` 运行可信且全部通过，`1` 有场景失败，`2` 框架本身坏了、
本趟结果作废，`3` 游戏从未完成装配。

两个加载器的框架构建都在这个 jar 里，所以不存在需要你手工对齐的「加载器与版本配对」。其余选项见
`--help`，包括如何在没有显示器、没有账号的情况下运行一个真实客户端。

### 接入一个 Gradle 项目

```groovy
// settings.gradle
pluginManagement { repositories { mavenLocal(); gradlePluginPortal() } }

// build.gradle
plugins {
    id 'java'
    id 'net.magicterra.stagewright' version '0.1.0'
}

dependencies {
    // 放在存放你的场景的那个 source set 上 —— 见指南
    testmodImplementation 'net.magicterra:mc_stagewright-api:0.1.0+1.21.1:dev'
    modLocalRuntime       'net.magicterra:mc_stagewright-fabric:0.1.0+1.21.1'
}

stagewright {
    topologies {
        dedicatedServer {
            runTask    = 'runStagewrightDedicatedServer'          // 你自己的 dev-run 任务
            expectFile = file('src/testmod/expected-scenes.txt')
        }
    }
}
```

这会注册出 `./gradlew stagewrightDedicatedServer`：它准备一个干净的运行目录，运行你的 dev-run 任务，
再判读结果。[Getting started](docs/guide/getting-started.md) 完整走了这两条路线，包括该写的第一个场景，
以及在写的过程中如何只跑它一个。

## 一个场景长什么样

下面这段是 StageWright 自带的内建场景之一，原样取自
`common/src/main/java/net/magicterra/stagewright/harness/Scenes.java`：

```java
Scene.of("awaitTicks", 200, ctx -> {
    ctx.setBlock(0, 0, 0, Blocks.STONE);
    ctx.await(() -> ctx.ticks() >= 40).within(100).then(() -> {
        if (ctx.ticks() < 40) ctx.fail("await fired before its condition held");
        ctx.assertBlock(0, 0, 0, Blocks.STONE);
    });
}),
```

场景体在该场景的第一个 tick 上同步执行一次，内联在服务器的 tick 循环里。它从不阻塞、从不 sleep：任何
需要时间的事情都走 `await`，那也是场景跨越多个 tick 的唯一办法。每一个坐标都相对于框架分配的原点；
超出声明的 tick 预算是超时，运行会把它与失败分开报告。

日常写法是给方法加注解，这样场景名来自方法名，不会被写第二遍：

```java
@SceneSet("mymod")
public final class MagnetScenes implements SceneProvider {

    @SceneDef(budget = 200)
    static void pullsItemsWithinRadius(SceneContext s) { … }
}
```

完整参考见 [Writing a scene](docs/guide/writing-a-scene.md)。

## 进程拓扑

三种拓扑都把场景跑在**服务器**上；不同的是跑在哪个服务器上，以及是否有一个真实客户端连着它。

| 拓扑 | 启动了什么 | 适合什么 |
|---|---|---|
| dedicated server（专用服务器） | 一个无头专用服务器 | 套件的主体；也是「仅客户端的调用会被拒绝」和「玩家列表为空」这两件事唯一能被观测到的地方。 |
| integrated server（集成服务器） | 一个自带服务器的游戏客户端 | 任何需要同进程内客户端状态的东西，以及任何以帧为单位度量的东西。 |
| dedicated server with client（专用服务器加客户端） | 一个专用服务器，外加一个真实连上去的客户端 | 任何以两端之间那条线为主题的东西——也就是「在单人游戏里能用」和「在服务器上能用」之间的差别。 |

拓扑的名字由使用方自己取；上面这三个之所以是惯例，是因为它们说清了启动了什么。
[Topologies](docs/guide/topologies.md) 讲了哪些事实需要哪种形态，以及为什么一个被 skip 的场景什么也
证明不了。

## 文档

- [Getting started](docs/guide/getting-started.md) —— 两条入口路线，从头到尾。
- [Writing a scene](docs/guide/writing-a-scene.md) —— 生命周期、断言、注册、命名。
- [Capabilities](docs/guide/capabilities.md) —— 够到模组自己的机制，以及如何扩展这个接缝。
- [Topologies](docs/guide/topologies.md) —— 各种进程形态，以及每一种能确立什么。
- [Gates](docs/guide/gates.md) —— 如何运行这些检查，以及如何读它们的输出。
- [The Gradle plugin](docs/guide/gradle-plugin.md) —— 任务、配置项、source set 约定。
- [JUnit attach](docs/guide/junit-attach.md) —— 从运行中的游戏外部做断言。

线上格式与发布布局在 [`docs/reference/`](docs/reference/)，较大决策背后的取舍在
[`docs/design/`](docs/design/)，[`docs/README.md`](docs/README.md) 是全部文档的索引。

## StageWright 与 WorldDriver

[WorldDriver](https://github.com/AI-assisted-Minecraft-Developers/worlddriver) 是一个把运行中的游戏
暴露为单一可编程 API 面的 Minecraft 模组。StageWright 最早长在它里面，后来独立成了自己的仓库。

两者互相依赖，但方向相反、位置不同：StageWright 的运行时模块编译时依赖 WorldDriver 的 common 模块，
而 WorldDriver 以已发布的 Maven 制品消费 StageWright，并应用它的 Gradle 插件。这不是循环——它被模块
和 source set 切断了，因为 StageWright 的场景 API 不依赖任何东西，而 WorldDriver 的生产代码从不依赖
StageWright——但这确实意味着从零开始的引导只有一条可行顺序，共四步：

```bash
# 1. StageWright —— WorldDriver 在能完成配置之前所需要的四样东西
./gradlew -p engine publishToMavenLocal
./gradlew -p gradle-plugin publishToMavenLocal
./gradlew :stagewright-api:publishToMavenLocal :stagewright-attached:publishToMavenLocal

# 2. WorldDriver —— 它的 common 模块，StageWright 的运行时编译时依赖它
./gradlew -PworlddriverBootstrap :common:publishToMavenLocal

# 3. StageWright —— 其余全部
./gradlew publishToMavenLocal

# 4. WorldDriver —— 构建
./gradlew build
```

第 1 步之所以必须在最前面，是因为 WorldDriver 的根构建**应用**了这个 Gradle 插件，所以在插件 marker
出现在本地仓库之前，那边什么都配置不起来；而这四样制品没有一样碰到 WorldDriver。第 2 步的
`-PworlddriverBootstrap` 在一台干净的机器上不是可选项：它去掉了加载器模块对 StageWright 加载器 jar 的
开发期运行时依赖——Gradle 会在**配置**阶段解析它，而那些制品要到第 3 步才存在。

[`build.gradle`](build.gradle) 的头部注释给出了同一套顺序，并逐步写明了理由。只有场景 API 发生改动才会
迫使整套流程重走一遍，而那个模块只有接口与值类型，变动很少。

## 许可证

[LGPL-3.0-only](COPYING.LESSER) —— 附加许可部分，叠加在它所修改的 [`COPYING`](COPYING) 中的 GPL-3.0
正文之上。

仅仅**使用**本框架的模组——把这些制品声明为依赖、按服务接口编写场景、运行这些检查——不构成它的派生作品，
可以采用任何它自己喜欢的许可证。Copyleft 只附着于 StageWright 自身的源码，以及它们的修改副本。

有一个目录是明确的例外：`engine/src/main/java/.../engine/json/` 是原样内置的 minimal-json，采用 MIT
许可证。那些文件头是上游的条款，保持原样；详见它们旁边的 `VENDORED.md`。
