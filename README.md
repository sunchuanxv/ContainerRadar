# Container Radar

![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1-3C8527)
![Forge](https://img.shields.io/badge/Forge-47.4.28-DFA86A)
![License](https://img.shields.io/badge/license-MIT-blue)
![Status](https://img.shields.io/badge/status-WIP-yellow)

穿墙高亮附近的容器 —— 按**能力**识别，而不是按方块 ID。

> A client-side Minecraft mod that highlights nearby containers through walls by
> detecting the Forge `IItemHandler` capability, so containers from **any** mod are
> recognized automatically — no per-mod compatibility code.

**当前状态**：开发中。容器扫描逻辑与调试指令已完成，高亮渲染尚未实现（见 Roadmap）。

## 为什么还要再造一个轮子？

现有的容器透视类模组（Chest ESP、Chest Finder ESP 等）普遍采用**硬编码方块白名单**——
把 `minecraft:chest`、`minecraft:barrel` 之类的 ID 写死在代码里。这带来三个问题：

- 装了 **Lootr**，Lootr 的战利品箱认不出来
- 装了 **铁箱子 / 精妙存储 / Create 仓库**，同样认不出来
- 想支持一个新方块，只能等作者更新

Container Radar 换了一种判断方式：

```java
BlockEntity be = level.getBlockEntity(pos);
if (be != null && be.getCapability(ForgeCapabilities.ITEM_HANDLER).isPresent()) {
    // 这就是容器
}
```

只要一个方块实体对外声明了 Forge 的 `ITEM_HANDLER`（物品存储能力），它就自动被认定为容器。
**不管是哪个模组、什么方块，一行兼容代码都不需要。**

这是本项目唯一的核心思路，也是它存在的理由。

## Roadmap

| 功能 | 状态 |
| --- | --- |
| 基于 capability 的容器识别 | 已完成 |
| `/cr scan` 调试指令（打印坐标与方块 ID） | 已完成 |
| 穿墙线框渲染（双 pass：近处实色 / 墙后淡色） | 已完成 |
| 自定义方块白名单（任意方块可高亮） | 已完成 |
| 准星选取 / 从 JEI 拖拽添加方块 | 计划中 |
| Lootr「已开启 / 未开启」状态差异化着色 | 计划中 |
| 图形化方块选择界面 | 计划中 |

## 环境要求

- Minecraft **1.20.1**
- Forge **47.4.28**（依赖范围 `[47,)`）
- Java **17**

纯客户端模组，目标是不需要在服务端安装。注意 `/cr scan` 指令目前走服务端执行路径，
因此该调试功能仅在单人游戏或服务端也装了本模组时可用；正式的高亮功能会改为纯客户端。

## 从源码构建

```bash
# Windows
gradlew.bat build

# macOS / Linux
./gradlew build
```

产物：`build/libs/containerradar-<version>.jar`

## 使用

把 jar 放进 `.minecraft/mods/`，进入游戏（需开启作弊）。

### 高亮

装好即生效：附近 16 格内的容器会显示**青色**线框。没被遮挡的框较实，
被墙挡住的会以淡色透出来 —— 这样你能同时判断"它在哪"和"它离我多远"。

### 指令

```
/cr scan            扫描周围 32 格，打印找到的目标
/cr scan 64         指定半径（1 ~ 128）

/cr add <方块ID>     加入自定义白名单（可省略 minecraft: 前缀）
/cr add ancient_debris
/cr add create:zinc_ore
/cr remove <方块ID>  移出白名单
/cr list            查看当前白名单
```

白名单里的方块会显示**橙色**线框，和容器的青色区分开。

输出示例：

```
[ContainerRadar] 半径 32 内找到 7 个目标（容器 + 自定义方块），耗时 63 ms
  (112, 64, -34)  minecraft:chest        [容器]
  (113, 64, -34)  lootr:chest            [容器]
  (108, 40, -30)  minecraft:ancient_debris  [方块]
  ...
```

### 手动编辑白名单

白名单存在 `.minecraft/config/containerradar-blocks.txt`，一行一个方块 ID：

```
# ContainerRadar 自定义方块白名单
minecraft:ancient_debris
minecraft:spawner
create:zinc_ore
```

改完重启游戏生效。注意：用 `/cr add`、`/cr remove` 修改后，文件会被重写，手写的注释会丢失。

## 项目结构

```
src/main/java/com/scx/containerradar/
├── ContainerRadar.java            模组入口，注册指令监听
├── command/
│   └── ScanCommand.java           /cr 指令树：参数解析 + 结果输出
├── filter/
│   └── BlockFilter.java           自定义方块白名单 + 配置文件读写
├── render/
│   └── ContainerRenderer.java     穿墙线框渲染（双 pass）
└── scan/
    └── ContainerScanner.java      目标扫描（纯逻辑，无副作用，指令与渲染共用）
```

三个模块的职责边界：`scan` 只回答"哪里有目标"，`render` 只负责"怎么画"，
`command` 只负责"怎么和玩家对话"。渲染器和指令都调用同一个 `ContainerScanner`，
所以"扫描规则"只需要在一处维护。

## 踩坑记录

开发过程中真实遇到过、并且值得记下来的问题：

- **`ForgeRegistries.BLOCKS.getValue(id)` 对不存在的 ID 返回 `Blocks.AIR` 而不是 `null`**。
  所以 `getValue(id) != null` 永远成立，无法用来校验 ID 合法性 —— 必须用 `containsKey(id)`。
  教训：想知道一个键在不在，不要"取出来再判空"。
- **`RenderStateShard.LineStateShard` 是 protected**，包外无法引用，因此线宽只能用默认的 1 像素。
  网上教程说需要 Access Transformer，但那只针对 5 参数版的 `RenderType.create`；
  7 参数版是 public 的，自己 `new` 各个 state shard 就能绕过去。
- **`StringArgumentType.word()` 不允许冒号**，用它接方块 ID 会让 `minecraft:chest` 直接解析失败。
  要用 `string()`。

## 许可

[MIT](LICENSE)
