package com.scx.containerradar.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.scx.containerradar.config.Settings;
import com.scx.containerradar.filter.BlockFilter;
import com.scx.containerradar.scan.ContainerScanner;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;

/**
 * {@code /cr} 指令树：扫描、增删自定义方块白名单。
 *
 * <p>完整用法：
 * <pre>
 *   /cr scan            扫描周围 32 格
 *   /cr scan 64         指定半径（1 ~ 128）
 *   /cr add ancient_debris      加入白名单（可省略 minecraft: 前缀）
 *   /cr add create:zinc_ore     带命名空间的模组方块
 *   /cr remove ancient_debris   移出白名单
 *   /cr list            查看当前白名单
 * </pre>
 *
 * <p>设计上这个类只做「解析输入 → 调用逻辑 → 格式化输出」，
 * 扫描在 {@link ContainerScanner}，白名单在 {@link BlockFilter}。
 * 想让指令换个样子改这里就行，不会影响功能本身。
 */
public final class ScanCommand {

    /** 聊天栏最多列出多少个坐标，避免一次刷屏几百行。 */
    private static final int MAX_LISTED = 10;

    /** /cr list 最多显示多少条，避免白名单塞了几百个时刷屏。 */
    private static final int MAX_FILTER_LISTED = 20;

    private ScanCommand() {
    }

    /**
     * 把 {@code /cr} 这棵指令树注册进游戏。
     *
     * <p>读法：{@code Commands.literal("cr")} 是根节点，每个 {@code .then(...)} 是它下面的一个分支。
     * 几个分支之间是<b>并列</b>关系（scan / add / remove / list），不是嵌套。
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("cr")

                        // --- /cr scan [radius] ---
                        .then(Commands.literal("scan")
                                .executes(ctx -> executeScan(ctx, Settings.radius()))
                                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 128))
                                        .executes(ctx -> executeScan(ctx, IntegerArgumentType.getInteger(ctx, "radius")))))

                        // --- /cr add <block> ---
                        // 这里必须用 StringArgumentType.string() 而不是 word()：
                        // word() 不允许冒号，minecraft:chest 这种 ID 会直接解析失败。
                        // 这个坑很隐蔽 —— 参数类型选错，玩家输入什么都是"格式错误"。
                        .then(Commands.literal("add")
                                .then(Commands.argument("block", StringArgumentType.string())
                                        .executes(ctx -> executeAdd(ctx, StringArgumentType.getString(ctx, "block")))))

                        // --- /cr remove <block> ---
                        .then(Commands.literal("remove")
                                .then(Commands.argument("block", StringArgumentType.string())
                                        .executes(ctx -> executeRemove(ctx, StringArgumentType.getString(ctx, "block")))))

                        // --- /cr list ---
                        .then(Commands.literal("list")
                                .executes(ScanCommand::executeList))

                        // --- /cr radius <半径> ---
                        .then(Commands.literal("radius")
                                .then(Commands.argument("radius",
                                                IntegerArgumentType.integer(Settings.MIN_RADIUS, Settings.MAX_RADIUS))
                                        .executes(ctx -> executeRadius(ctx, IntegerArgumentType.getInteger(ctx, "radius")))))

                        // --- /cr toggle [containers|whitelist] ---
                        // 不带参数 = 翻转总开关（全部显示 / 全部不显示）
                        .then(Commands.literal("toggle")
                                .executes(ScanCommand::executeToggleAll)
                                .then(Commands.literal("containers")
                                        .executes(ctx -> executeToggle(ctx, true)))
                                .then(Commands.literal("whitelist")
                                        .executes(ctx -> executeToggle(ctx, false))))

                        // --- /cr reload ---
                        .then(Commands.literal("reload")
                                .executes(ScanCommand::executeReload))
        );
    }

    // ==================================================================
    // /cr scan
    // ==================================================================

    /**
     * 扫描并列出周围的目标（容器 + 自定义方块）。
     *
     * @return 找到的目标数量，Brigadier 会把它作为指令结果
     */
    private static int executeScan(CommandContext<CommandSourceStack> ctx, int radius) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level = ctx.getSource().getLevel();
        BlockPos center = player.blockPosition();

        long startNanos = System.nanoTime();
        List<BlockPos> found = ContainerScanner.findTargets(level, center, radius);
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;

        ctx.getSource().sendSuccess(
                () -> Component.literal("[ContainerRadar] 半径 " + radius + " 内找到 "
                        + found.size() + " 个目标（容器 + 自定义方块），耗时 " + elapsedMs + " ms")
                        .withStyle(ChatFormatting.AQUA),
                false);

        // 数量超过渲染上限时给一句提示，否则玩家会疑惑"为什么只看到一部分框"
        int maxRendered = Settings.maxRendered();
        if (found.size() > maxRendered) {
            ctx.getSource().sendSuccess(
                    () -> Component.literal("  注意：超过渲染上限，画面中只会绘制最近的 "
                                    + maxRendered + " 个")
                            .withStyle(ChatFormatting.YELLOW),
                    false);
        }

        int shown = Math.min(found.size(), MAX_LISTED);
        for (int i = 0; i < shown; i++) {
            BlockPos pos = found.get(i);

            // 只查一次方块状态，后面两个用途共用（省一次区块查找）
            net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
            ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.getBlock());

            // 标出它是「容器」还是「白名单方块」，排查问题非常有用：
            // 加了一个方块却没高亮，看这里标的是 [容器] 还是 [方块]，就知道是哪条逻辑生效了
            boolean whitelisted = !BlockFilter.isEmpty() && BlockFilter.matches(state);
            String tag = whitelisted ? "[方块]" : "[容器]";

            ctx.getSource().sendSuccess(
                    () -> Component.literal("  (" + pos.getX() + ", " + pos.getY() + ", "
                            + pos.getZ() + ")  " + id + "  " + tag)
                            .withStyle(ChatFormatting.GRAY),
                    false);
        }

        if (found.size() > shown) {
            int rest = found.size() - shown;
            ctx.getSource().sendSuccess(
                    () -> Component.literal("  ……还有 " + rest + " 个").withStyle(ChatFormatting.DARK_GRAY),
                    false);
        }

        return found.size();
    }

    // ==================================================================
    // /cr add
    // ==================================================================

    private static int executeAdd(CommandContext<CommandSourceStack> ctx, String input) {
        ResourceLocation id = BlockFilter.parseId(input);

        // 三种失败情况分开报错，比统一说"失败"有用得多
        if (id == null) {
            return error(ctx, "方块 ID 格式不对：" + input + "（应形如 minecraft:ancient_debris）");
        }
        if (!BlockFilter.isRegisteredBlock(id)) {
            return error(ctx, "找不到这个方块：" + id + "（注意 ID 必须全小写）");
        }

        // 空气也是"已注册的方块"，但把它加进白名单毫无意义（而且会满屏框）：
        // 空气到处都是，一旦加进去，半径 16 内会有几千个方块被高亮。所以单独挡一道。
        if (ForgeRegistries.BLOCKS.getValue(id) == Blocks.AIR) {
            return error(ctx, "不能把空气加入白名单");
        }

        if (!BlockFilter.add(id)) {
            ctx.getSource().sendSuccess(
                    () -> Component.literal(id + " 已经在白名单里了").withStyle(ChatFormatting.GRAY), false);
            return 0;
        }

        ctx.getSource().sendSuccess(
                () -> Component.literal("已加入白名单：" + id + "（高亮会在 1 秒内出现）")
                        .withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    // ==================================================================
    // /cr remove
    // ==================================================================

    private static int executeRemove(CommandContext<CommandSourceStack> ctx, String input) {
        ResourceLocation id = BlockFilter.parseId(input);
        if (id == null) {
            return error(ctx, "方块 ID 格式不对：" + input);
        }

        if (!BlockFilter.remove(id)) {
            ctx.getSource().sendSuccess(
                    () -> Component.literal(id + " 不在白名单里").withStyle(ChatFormatting.GRAY), false);
            return 0;
        }

        ctx.getSource().sendSuccess(
                () -> Component.literal("已从白名单移除：" + id).withStyle(ChatFormatting.YELLOW), false);
        return 1;
    }

    // ==================================================================
    // /cr list
    // ==================================================================

    private static int executeList(CommandContext<CommandSourceStack> ctx) {
        List<ResourceLocation> ids = BlockFilter.ids();

        if (ids.isEmpty()) {
            ctx.getSource().sendSuccess(
                    () -> Component.literal("白名单是空的。用 /cr add <方块ID> 添加，例如 /cr add ancient_debris")
                            .withStyle(ChatFormatting.GRAY), false);
            return 0;
        }

        ctx.getSource().sendSuccess(
                () -> Component.literal("自定义方块白名单（共 " + ids.size() + " 个）：")
                        .withStyle(ChatFormatting.AQUA), false);

        int shown = Math.min(ids.size(), MAX_FILTER_LISTED);
        for (int i = 0; i < shown; i++) {
            ResourceLocation id = ids.get(i);
            ctx.getSource().sendSuccess(
                    () -> Component.literal("  " + id).withStyle(ChatFormatting.GRAY), false);
        }

        if (ids.size() > shown) {
            int rest = ids.size() - shown;
            ctx.getSource().sendSuccess(
                    () -> Component.literal("  ……还有 " + rest + " 个").withStyle(ChatFormatting.DARK_GRAY), false);
        }

        return ids.size();
    }

    /** 统一的错误输出：红色文字 + 返回 0（表示指令执行失败）。 */
    private static int error(CommandContext<CommandSourceStack> ctx, String message) {
        ctx.getSource().sendFailure(Component.literal("[ContainerRadar] " + message).withStyle(ChatFormatting.RED));
        return 0;
    }

    // ==================================================================
    // /cr radius | toggle | reload
    // ==================================================================

    /**
     * 修改扫描半径并立刻写回配置文件。
     *
     * <p>注意这里<b>没有</b>直接通知渲染器重扫：指令跑在服务端，而渲染器是客户端类，
     * 服务端代码引用客户端类在专用服务器上会崩。渲染器本来就有 1 秒一次的节流重扫，
     * 所以最多 1 秒后新设置自然生效，这个延迟可以接受。
     */
    private static int executeRadius(CommandContext<CommandSourceStack> ctx, int radius) {
        int applied = Settings.setRadius(radius);
        ctx.getSource().sendSuccess(
                () -> Component.literal("扫描半径已设为 " + applied + " 格").withStyle(ChatFormatting.GREEN),
                false);
        return applied;
    }

    /**
     * 翻转总开关（等于游戏内按快捷键）。
     *
     * <p>和 {@link #executeToggle} 的区别：那个管"显示容器"或"显示白名单"其中一个，
     * 这个管全部 —— 关掉之后扫描都不做，开销为零。
     */
    private static int executeToggleAll(CommandContext<CommandSourceStack> ctx) {
        boolean nowEnabled = Settings.toggleEnabled();
        ctx.getSource().sendSuccess(
                () -> Component.literal("容器雷达：" + (nowEnabled ? "已开启" : "已关闭"))
                        .withStyle(nowEnabled ? ChatFormatting.GREEN : ChatFormatting.YELLOW),
                false);
        return nowEnabled ? 1 : 0;
    }

    /**
     * 切换某个开关。
     *
     * @param containers true = 容器开关，false = 白名单开关
     */
    private static int executeToggle(CommandContext<CommandSourceStack> ctx, boolean containers) {
        boolean next = !(containers ? Settings.showContainers() : Settings.showWhitelist());
        if (containers) {
            Settings.setShowContainers(next);
        } else {
            Settings.setShowWhitelist(next);
        }

        String name = containers ? "容器高亮" : "白名单高亮";
        ctx.getSource().sendSuccess(
                () -> Component.literal(name + "：" + (next ? "已开启" : "已关闭"))
                        .withStyle(next ? ChatFormatting.GREEN : ChatFormatting.YELLOW),
                false);
        return next ? 1 : 0;
    }

    /** 重新从磁盘读取配置。玩家手动编辑过 JSON 之后用它生效，不必重启游戏。 */
    private static int executeReload(CommandContext<CommandSourceStack> ctx) {
        Settings.reload();
        ctx.getSource().sendSuccess(
                () -> Component.literal("[ContainerRadar] 已重新读取配置 · 总开关 "
                                + (Settings.enabled() ? "开" : "关")
                                + "，半径 " + Settings.radius()
                                + "，容器 " + (Settings.showContainers() ? "开" : "关")
                                + "，白名单 " + (Settings.showWhitelist() ? "开" : "关")
                                + "，渲染上限 " + Settings.maxRendered())
                        .withStyle(ChatFormatting.AQUA),
                false);
        return 1;
    }
}
