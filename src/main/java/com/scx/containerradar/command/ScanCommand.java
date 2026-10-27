package com.scx.containerradar.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.scx.containerradar.scan.ContainerScanner;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;

/**
 * 指令 {@code /cr scan [半径]}：扫描周围并列出找到的容器。
 *
 * <p>这个类只负责三件事：声明指令结构、从上下文里取参数、把结果打印到聊天栏。
 * 真正的扫描逻辑在 {@link ContainerScanner} 里，两边通过「传参 + 收返回值」通信，
 * 互不知道对方内部怎么实现 —— 这就是为什么扫描逻辑要单独抽一个类。
 *
 * <p>指令结构：
 * <pre>
 *   /cr scan          使用默认半径（32 格）
 *   /cr scan 64       指定半径（1 ~ 128 格）
 * </pre>
 *
 * <p>注意：指令是在<b>逻辑服务端</b>执行的。单人游戏里也有一个内置服务端，
 * 所以能正常用；但如果你以后要在别人的服务器上用，服务端必须也装这个模组，
 * 或者改成纯客户端的键位触发（阶段 06 会做）。
 */
public final class ScanCommand {

    /** 不带参数时使用的默认半径。 */
    private static final int DEFAULT_RADIUS = 32;

    /** 聊天栏最多列出多少个坐标，避免一次刷屏几百行。 */
    private static final int MAX_LISTED = 10;

    private ScanCommand() {
    }

    /**
     * 把 {@code /cr} 这条指令注册进游戏。
     *
     * <p>由主类在 {@code RegisterCommandsEvent} 里调用 —— 那个事件是 Forge 在
     * 「准备注册所有指令」时发出的，只有在这个时机注册才有效。
     *
     * @param dispatcher Brigadier 的指令分发器，游戏里所有指令都挂在它上面
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {

        // Commands.literal(...) = 一个固定的字面量节点，玩家必须原样敲出这个词。
        // 所以整棵树的读法是：/cr → scan →（可选）一个整数参数
        dispatcher.register(
                Commands.literal("cr")
                        .then(Commands.literal("scan")

                                // .executes(...) = 「玩家只敲到 /cr scan 就回车」时执行什么
                                .executes(ctx -> execute(ctx, DEFAULT_RADIUS))

                                // .then(...) = 「/cr scan 后面还能再接什么」
                                // IntegerArgumentType.integer(1, 128) = 一个 1~128 的整数参数。
                                // 敲 /cr scan 999 或 /cr scan abc 会在进入你的方法「之前」就被 Brigadier 拒绝，
                                // 你不需要自己写校验 —— 这是用 Brigadier 而不是手撸字符串解析的最大好处
                                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 128))
                                        .executes(ctx -> execute(ctx, IntegerArgumentType.getInteger(ctx, "radius"))))
                        )
        );
    }

    /**
     * 真正干活的方法。
     *
     * @param ctx    指令上下文，能从里面取到「谁执行了这条指令」「在哪个世界」
     * @param radius 已经校验过范围的半径
     * @return 找到的容器数量。Brigadier 会把这个数字作为「指令结果」，
     *         可用于计分板、命令方块条件判断等场景，这里顺便让它有意义
     * @throws CommandSyntaxException 当执行者不是玩家（比如从服务器控制台敲）时抛出，
     *                                Brigadier 会自动把异常翻译成聊天栏里的红字提示
     */
    private static int execute(CommandContext<CommandSourceStack> ctx, int radius) throws CommandSyntaxException {

        // getPlayerOrException()：取执行这条指令的玩家。
        // 如果是从控制台敲的，这里会抛异常 —— 所以它比 getPlayer() 更适合我们
        ServerPlayer player = ctx.getSource().getPlayerOrException();

        // getLevel()：取这条指令所在的逻辑服务端世界。
        // 注意这里绝不能用 Minecraft.getInstance().level —— 那是客户端那份世界对象，
        // 在服务端代码里用它，轻则拿到 null，重则在专用服务器上直接崩溃
        ServerLevel level = ctx.getSource().getLevel();

        // blockPosition()：玩家脚下那个方块的整数坐标，作为扫描中心
        BlockPos center = player.blockPosition();

        // 用纳秒计时再换算成毫秒。这是为了让你直观看到「27 万个坐标要扫多久」，
        // 也为阶段 03 的性能优化留一个可对比的基准数字
        long startNanos = System.nanoTime();
        List<BlockPos> found = ContainerScanner.findContainers(level, center, radius);
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;

        // sendSuccess(Supplier<Component>, boolean)
        //   第一个参数是消息内容。传 Supplier（lambda）而不是直接传 Component，
        //   是为了在消息不需要显示时省掉拼接开销
        //   第二个参数 false = 这条消息不写进服务器日志，避免刷日志
        //
        // .withStyle(ChatFormatting.AQUA) = 给文字上色，AQUA 是青色
        ctx.getSource().sendSuccess(
                () -> Component.literal("[ContainerRadar] 半径 " + radius + " 内找到 "
                        + found.size() + " 个容器，耗时 " + elapsedMs + " ms")
                        .withStyle(ChatFormatting.AQUA),
                false);

        // 最多列 10 个坐标，避免一次刷屏
        int shown = Math.min(found.size(), MAX_LISTED);
        for (int i = 0; i < shown; i++) {
            BlockPos pos = found.get(i);

            // 把方块对象反查成它的注册名（如 minecraft:chest、lootr:chest）。
            // ForgeRegistries.BLOCKS 是「所有已注册方块」的注册表，
            // getKey(block) 拿到它在表里的名字。这是最直观的调试手段：
            // 你能一眼看出扫到的是原版箱子还是某个模组的箱子
            ResourceLocation id = ForgeRegistries.BLOCKS.getKey(level.getBlockState(pos).getBlock());

            // 这里的 pos 和 id 都是循环体内的局部变量，Java 要求 lambda 捕获的必须是
            // 「有效 final」变量；在循环体内声明且不重新赋值，正好满足这个条件
            ctx.getSource().sendSuccess(
                    () -> Component.literal("  (" + pos.getX() + ", " + pos.getY() + ", "
                            + pos.getZ() + ")  " + id)
                            .withStyle(ChatFormatting.GRAY),
                    false);
        }

        // 超过 10 个时给个提示，否则玩家会以为只找到这些
        if (found.size() > shown) {
            int rest = found.size() - shown;
            ctx.getSource().sendSuccess(
                    () -> Component.literal("  ……还有 " + rest + " 个").withStyle(ChatFormatting.DARK_GRAY),
                    false);
        }

        return found.size();
    }
}
