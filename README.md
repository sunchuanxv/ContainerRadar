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
| 穿墙线框渲染 | 开发中 |
| 自定义方块白名单（任意方块可高亮） | 计划中 |
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

把 jar 放进 `.minecraft/mods/`，进入游戏（需开启作弊）：

```
/cr scan        扫描周围 32 格内的容器
/cr scan 64     指定半径（1 ~ 128）
```

输出示例：

```
[ContainerRadar] 半径 32 内找到 7 个容器，耗时 63 ms
  (112, 64, -34)  minecraft:chest
  (113, 64, -34)  lootr:chest
  ...
```

## 项目结构

```
src/main/java/com/scx/containerradar/
├── ContainerRadar.java            模组入口，注册指令监听
├── command/
│   └── ScanCommand.java           /cr 指令：参数解析 + 结果输出
└── scan/
    └── ContainerScanner.java      容器扫描（纯逻辑，无副作用，可被渲染器复用）
```

扫描逻辑与指令分离，是为了让后续的渲染器能直接复用同一个 `ContainerScanner`，
而不需要复制一份坐标遍历代码。

## 许可

[MIT](LICENSE)
