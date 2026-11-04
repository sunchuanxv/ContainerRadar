package com.scx.containerradar.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.scx.containerradar.config.Settings;
import com.scx.containerradar.filter.BlockFilter;
import com.scx.containerradar.scan.ContainerScanner;
import com.scx.containerradar.search.BlockIndex;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * {@code /cr} 指令树：扫描、白名单、开关、半径、搜索。
 *
 * <h2>这是一个「客户端指令」</h2>
 * 注册在 {@code RegisterClientCommandsEvent} 上（见 {@link ClientCommands}），
 * 执行全程在客户端完成，<b>不需要服务端安装本模组</b>。
 *
 * <p>为什么之前不是这样：最初注册在 {@code RegisterCommandsEvent}（服务端事件）上，
 * 在单人游戏里一切正常（客户端和服务端是同一个 JVM，静态状态共享），
 * 但在别人的服务器上会全面失效 —— 指令改的是<b>服务端</b>的白名单和配置，
 * 而画面渲染读的是<b>客户端</b>的那份，于是出现"提示已加入但画面毫无变化"，
 * {@code /cr search} 还会因为服务端没有语言文件而搜不到中文。
 *
 * <p>改成客户端指令后：改动直接作用于客户端状态，立即生效；服务端零负担。
 * 代价是不能被命令方块调用、不能用 {@code /execute as} —— 对纯视觉模组来说无关紧要。
 *
 * <h2>输出为什么走聊天栏而不是 sendSuccess</h2>
 * 客户端指令的 {@code CommandSourceStack} 输出通路在不同环境（单人 / 多人 / 专用服务端）
 * 行为不一致，而 {@code Minecraft.getInstance().gui.getChat().addMessage(...)}
 * 是确定的：一定出现在玩家聊天栏里。功能反馈不该有"可能不显示"这种不确定性。
 */
public final class ScanCommand {

    /** 聊天栏最多列出多少个坐标，避免一次刷屏几百行。 */
    private static final int MAX_LISTED = 10;

    /** /cr list 最多显示多少条，避免白名单塞了几百个时刷屏。 */
    private static final int MAX_FILTER_LISTED = 20;

    /** Tab 补全最多返回多少条。 */
    private static final int MAX_SUGGESTIONS = 50;

    /** Tab 补全至少要输入几个字符。 */
    private static final int MIN_SUGGEST_PREFIX = 2;

    /**
     * 方块 ID 的 Tab 补全，{@code /cr add} 和 {@code /cr remove} 共用。
     *
     * <p>为什么必须限制：
     * <ul>
     *   <li><b>至少 2 个字符</b>：{@code ForgeRegistries.BLOCKS} 里有三万多个方块，
     *       输入为空时把全部建议发给客户端，单次数据量能到 1 MB，客户端会卡住</li>
     *   <li><b>最多 50 条</b>：补全列表本身也有渲染上限，几百条既刷屏又不好选</li>
     * </ul>
     *
     * <p>匹配用 {@code contains} 而不是 {@code startsWith}：玩家通常记得词根而不是开头，
     * 输 {@code debris} 就能找到 {@code ancient_debris}。
     */
    private static final SuggestionProvider<CommandSourceStack> BLOCK_ID_SUGGESTIONS = (context, builder) -> {
        String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
        if (typed.length() < MIN_SUGGEST_PREFIX) {
            return builder.buildFuture();
        }

        int added = 0;
        for (ResourceLocation id : ForgeRegistries.BLOCKS.getKeys()) {
            if (id.toString().contains(typed)) {
                builder.suggest(id.toString());
                if (++added >= MAX_SUGGESTIONS) {
                    break;
                }
            }
        }
        return builder.buildFuture();
    };

    private ScanCommand() {
    }

    /**
     * 把 {@code /cr} 这棵指令树注册进客户端的指令调度器。
     *
     * <p>读法：{@code Commands.literal("cr")} 是根节点，每个 {@code .then(...)} 是它下面的一个分支，
     * 分支之间是<b>并列</b>关系，不是嵌套。
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("cr")

                        // 只敲 /cr 时打印帮助。父节点不挂 .executes() 的话，
                        // 玩家敲它只会得到一个没头没尾的报错。
                        .executes(ScanCommand::executeHelp)

                        // --- /cr scan [radius] ---
                        .then(Commands.literal("scan")
                                .executes(ctx -> executeScan(ctx, Settings.radius()))
                                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 128))
                                        .executes(ctx -> executeScan(ctx, IntegerArgumentType.getInteger(ctx, "radius")))))

                        // --- /cr add <block> ---
                        // 用 ResourceLocationArgument.id() 而不是 StringArgumentType：
                        // Brigadier 的 readString() 会在冒号处停下（实测 "minecraft:x" 只读到 "minecraft"），
                        // 字符串参数根本无法接收带命名空间的 ID，Tab 补全一插入就报
                        // "参数后应有空格分隔，但发现了尾随数据"。
                        .then(Commands.literal("add")
                                .then(Commands.argument("block", ResourceLocationArgument.id())
                                        .suggests(BLOCK_ID_SUGGESTIONS)
                                        .executes(ctx -> executeAdd(ctx,
                                                ResourceLocationArgument.getId(ctx, "block")))))

                        // --- /cr remove <block> ---
                        .then(Commands.literal("remove")
                                .then(Commands.argument("block", ResourceLocationArgument.id())
                                        .suggests(BLOCK_ID_SUGGESTIONS)
                                        .executes(ctx -> executeRemove(ctx,
                                                ResourceLocationArgument.getId(ctx, "block")))))

                        // --- /cr list ---
                        .then(Commands.literal("list")
                                .executes(ScanCommand::executeList))

                        // --- /cr search <关键词> ---
                        .then(Commands.literal("search")
                                .then(Commands.argument("keyword", StringArgumentType.greedyString())
                                        .executes(ctx -> executeSearch(ctx,
                                                StringArgumentType.getString(ctx, "keyword")))))

                        // --- /cr radius <半径> ---
                        .then(Commands.literal("radius")
                                .then(Commands.argument("radius",
                                                IntegerArgumentType.integer(Settings.MIN_RADIUS, Settings.MAX_RADIUS))
                                        .executes(ctx -> executeRadius(ctx, IntegerArgumentType.getInteger(ctx, "radius")))))

                        // --- /cr toggle [containers|whitelist] ---
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
    // 输出
    // ==================================================================

    /**
     * 往玩家聊天栏发一条消息。
     *
     * <p>玩家还没进世界时（{@code player == null}）直接丢弃 —— 这种时候也没有指令能执行。
     */
    private static void say(Component message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.gui.getChat().addMessage(message);
        }
    }

    private static void sayError(String message) {
        say(Component.literal("[ContainerRadar] " + message).withStyle(ChatFormatting.RED));
    }

    // ==================================================================
    // /cr（帮助）
    // ==================================================================

    /** 帮助文本。改文案只动这里，不会漏行。 */
    private static final String[] HELP_LINES = {
            "  /cr scan [半径]        扫描并列出目标（不带半径时用配置里的值）",
            "  /cr toggle             开启 / 关闭全部高亮",
            "  /cr toggle containers  单独开关容器高亮",
            "  /cr toggle whitelist   单独开关白名单高亮",
            "  /cr radius <8~64>      设置扫描半径（写入配置文件）",
            "  /cr add <方块ID>       加入自定义白名单（支持 Tab 补全）",
            "  /cr remove <方块ID>    移出白名单",
            "  /cr search <关键词>    按 ID 或名称搜索方块（支持中文）",
            "  /cr list               查看当前白名单",
            "  /cr reload             重新读取配置文件",
    };

    private static int executeHelp(CommandContext<CommandSourceStack> ctx) {
        say(Component.literal("[ContainerRadar] 指令列表").withStyle(ChatFormatting.AQUA));
        for (String line : HELP_LINES) {
            say(Component.literal(line).withStyle(ChatFormatting.GRAY));
        }
        say(Component.literal("快捷键：K 加入准星方块 · L 移出 · J 开关全部 · O 设置界面")
                .withStyle(ChatFormatting.DARK_GRAY));
        return 1;
    }

    // ==================================================================
    // /cr scan
    // ==================================================================

    private static int executeScan(CommandContext<CommandSourceStack> ctx, int radius) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return 0;
        }

        Level level = mc.level;
        BlockPos center = mc.player.blockPosition();

        long startNanos = System.nanoTime();
        List<BlockPos> found = ContainerScanner.findTargets(level, center, radius);
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;

        say(Component.literal("[ContainerRadar] 半径 " + radius + " 内找到 "
                + found.size() + " 个目标（容器 + 自定义方块），耗时 " + elapsedMs + " ms")
                .withStyle(ChatFormatting.AQUA));

        // 数量超过渲染上限时提示一句，否则玩家会疑惑"为什么只看到一部分框"
        int maxRendered = Settings.maxRendered();
        if (found.size() > maxRendered) {
            say(Component.literal("  注意：超过渲染上限，画面中只会绘制最近的 " + maxRendered + " 个")
                    .withStyle(ChatFormatting.YELLOW));
        }

        int shown = Math.min(found.size(), MAX_LISTED);
        for (int i = 0; i < shown; i++) {
            BlockPos pos = found.get(i);

            // 只查一次方块状态，两个用途共用
            net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
            ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.getBlock());

            // 标出它是「容器」还是「白名单方块」：加了一个方块却没高亮时，
            // 看这里标的是 [容器] 还是 [方块] 就能定位是哪条逻辑生效了
            boolean whitelisted = !BlockFilter.isEmpty() && BlockFilter.matches(state);
            String tag = whitelisted ? "[方块]" : "[容器]";

            say(Component.literal("  (" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")  "
                    + id + "  " + tag).withStyle(ChatFormatting.GRAY));
        }

        if (found.size() > shown) {
            say(Component.literal("  ……还有 " + (found.size() - shown) + " 个")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }

        return found.size();
    }

    // ==================================================================
    // /cr add
    // ==================================================================

    private static int executeAdd(CommandContext<CommandSourceStack> ctx, ResourceLocation id) {
        // ID 的语法合法性由 ResourceLocationArgument 保证，这里只校验"这个方块是否存在"。
        // 注意不能用 getValue(id) != null 判断 —— Forge 对未知 ID 返回默认值（空气）而不是 null。
        if (!BlockFilter.isRegisteredBlock(id)) {
            sayError("找不到这个方块：" + id + "（注意 ID 必须全小写）");
            return 0;
        }

        // 空气也是"已注册的方块"，但加进白名单毫无意义（空气到处都是，会满屏框）
        if (ForgeRegistries.BLOCKS.getValue(id) == Blocks.AIR) {
            sayError("不能把空气加入白名单");
            return 0;
        }

        if (!BlockFilter.add(id)) {
            say(Component.literal(id + " 已经在白名单里了").withStyle(ChatFormatting.GRAY));
            return 0;
        }

        say(Component.literal("已加入白名单：" + id + "（高亮会在 1 秒内出现）")
                .withStyle(ChatFormatting.GREEN));
        return 1;
    }

    // ==================================================================
    // /cr remove
    // ==================================================================

    private static int executeRemove(CommandContext<CommandSourceStack> ctx, ResourceLocation id) {
        if (!BlockFilter.remove(id)) {
            say(Component.literal(id + " 不在白名单里").withStyle(ChatFormatting.GRAY));
            return 0;
        }
        say(Component.literal("已从白名单移除：" + id).withStyle(ChatFormatting.YELLOW));
        return 1;
    }

    // ==================================================================
    // /cr list
    // ==================================================================

    private static int executeList(CommandContext<CommandSourceStack> ctx) {
        List<ResourceLocation> ids = BlockFilter.ids();

        if (ids.isEmpty()) {
            say(Component.literal("白名单是空的。用 /cr add <方块ID> 添加，例如 /cr add ancient_debris")
                    .withStyle(ChatFormatting.GRAY));
            return 0;
        }

        say(Component.literal("自定义方块白名单（共 " + ids.size() + " 个）：")
                .withStyle(ChatFormatting.AQUA));

        int shown = Math.min(ids.size(), MAX_FILTER_LISTED);
        for (int i = 0; i < shown; i++) {
            say(Component.literal("  " + ids.get(i)).withStyle(ChatFormatting.GRAY));
        }

        if (ids.size() > shown) {
            say(Component.literal("  ……还有 " + (ids.size() - shown) + " 个")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }

        return ids.size();
    }

    // ==================================================================
    // /cr search
    // ==================================================================

    private static int executeSearch(CommandContext<CommandSourceStack> ctx, String keyword) {
        long startNanos = System.nanoTime();
        BlockIndex.Result result = BlockIndex.search(keyword, MAX_LISTED);
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;

        if (result.total() == 0) {
            say(Component.literal("没有匹配「" + keyword + "」的方块").withStyle(ChatFormatting.GRAY));
            return 0;
        }

        say(Component.literal("「" + keyword + "」匹配 " + result.total() + " 个方块"
                + "（显示前 " + result.entries().size() + " 个，耗时 " + elapsedMs + " ms）")
                .withStyle(ChatFormatting.AQUA));

        // 已经加进白名单的标出来，避免重复添加
        Set<ResourceLocation> existing = new HashSet<>(BlockFilter.ids());
        for (BlockIndex.Entry entry : result.entries()) {
            String mark = existing.contains(entry.id()) ? " [已加]" : "";
            say(Component.literal("  " + entry.id() + "  " + entry.name() + mark)
                    .withStyle(ChatFormatting.GRAY));
        }

        return result.total();
    }

    // ==================================================================
    // /cr radius | toggle | reload
    // ==================================================================

    private static int executeRadius(CommandContext<CommandSourceStack> ctx, int radius) {
        int applied = Settings.setRadius(radius);
        say(Component.literal("扫描半径已设为 " + applied + " 格").withStyle(ChatFormatting.GREEN));
        return applied;
    }

    /**
     * 翻转总开关（等于游戏内按快捷键）。
     *
     * @param containers true = 管"显示容器"那个开关，false = 管"显示白名单"
     */
    private static int executeToggle(CommandContext<CommandSourceStack> ctx, boolean containers) {
        boolean next = !(containers ? Settings.showContainers() : Settings.showWhitelist());
        if (containers) {
            Settings.setShowContainers(next);
        } else {
            Settings.setShowWhitelist(next);
        }

        String name = containers ? "容器高亮" : "白名单高亮";
        say(Component.literal(name + "：" + (next ? "已开启" : "已关闭"))
                .withStyle(next ? ChatFormatting.GREEN : ChatFormatting.YELLOW));
        return next ? 1 : 0;
    }

    private static int executeToggleAll(CommandContext<CommandSourceStack> ctx) {
        boolean nowEnabled = Settings.toggleEnabled();
        say(Component.literal("容器雷达：" + (nowEnabled ? "已开启" : "已关闭"))
                .withStyle(nowEnabled ? ChatFormatting.GREEN : ChatFormatting.YELLOW));
        return nowEnabled ? 1 : 0;
    }

    private static int executeReload(CommandContext<CommandSourceStack> ctx) {
        Settings.reload();
        say(Component.literal("[ContainerRadar] 已重新读取配置 · 总开关 "
                + (Settings.enabled() ? "开" : "关")
                + "，半径 " + Settings.radius()
                + "，容器 " + (Settings.showContainers() ? "开" : "关")
                + "，白名单 " + (Settings.showWhitelist() ? "开" : "关")
                + "，渲染上限 " + Settings.maxRendered())
                .withStyle(ChatFormatting.AQUA));
        return 1;
    }
}
