package com.scx.containerradar.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.scx.containerradar.config.Settings;
import com.scx.containerradar.filter.BlockFilter;
import com.scx.containerradar.scan.ContainerScanner;
import com.scx.containerradar.search.BlockIndex;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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
     * <p>匹配用的是 {@code contains} 而不是 {@code startsWith}：玩家通常记得"词根"而不是开头，
     * 输 {@code debris} 就能找到 {@code ancient_debris}，比要求从头匹配好用得多。
     */
    private static final SuggestionProvider<CommandSourceStack> BLOCK_ID_SUGGESTIONS = (context, builder) -> {
        // 小写化以便忽略大小写匹配（方块 ID 本身全是小写，但玩家可能输入大写）
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
     * 把 {@code /cr} 这棵指令树注册进游戏。
     *
     * <p>读法：{@code Commands.literal("cr")} 是根节点，每个 {@code .then(...)} 是它下面的一个分支。
     * 几个分支之间是<b>并列</b>关系（scan / add / remove / list），不是嵌套。
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("cr")

                        // 只敲 /cr 时打印帮助。父节点如果不挂 .executes()，
                        // 玩家敲它只会得到一个没头没尾的报错 —— 新玩家根本不知道该敲什么。
                        .executes(ScanCommand::executeHelp)

                        // --- /cr scan [radius] ---
                        .then(Commands.literal("scan")
                                .executes(ctx -> executeScan(ctx, Settings.radius()))
                                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 128))
                                        .executes(ctx -> executeScan(ctx, IntegerArgumentType.getInteger(ctx, "radius")))))

                        // --- /cr add <block> ---
                        // 用 ResourceLocationArgument.id() 而不是 StringArgumentType：
                        // Brigadier 的 readString() 会在冒号处停下（实测 "minecraft:x" 只读到 "minecraft"），
                        // 所以字符串参数根本无法接收带命名空间的方块 ID，Tab 补全一插入就报
                        // "参数后应有空格分隔，但发现了尾随数据"。
                        // ResourceLocationArgument 是原版专为此设计的：它按 ID 的字符集读，
                        // 允许冒号，且不带前缀时自动补 minecraft:。
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
                        // 同时匹配方块 ID 和本地化名称，中文直接搜即可。
                        // 用 greedyString 是因为名称里可能有空格（如 "Polished Deepslate Stairs"），
                        // 也方便一次搜多个词。
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
    // /cr（帮助）
    // ==================================================================

    /**
     * 帮助文本。
     *
     * <p>放在静态数组里而不是一个个 sendSuccess 调用，好处是改文案只动这里，
     * 也不用担心漏掉某一行。
     */
    private static final String[] HELP_LINES = {
            "  /cr scan [半径]        扫描并列出目标（不带半径时用配置里的值）",
            "  /cr toggle             开启 / 关闭全部高亮",
            "  /cr toggle containers  单独开关容器高亮",
            "  /cr toggle whitelist   单独开关白名单高亮",
            "  /cr radius <8~64>      设置扫描半径（写入配置文件）",
            "  /cr add <方块ID>       加入自定义白名单",
            "  /cr remove <方块ID>    移出白名单",
            "  /cr search <关键词>    按 ID 或名称搜索方块（支持中文）",
            "  /cr list               查看当前白名单",
            "  /cr reload             重新读取配置文件",
    };

    /** 打印指令列表。只敲 {@code /cr} 就会走到这里。 */
    private static int executeHelp(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(
                () -> Component.literal("[ContainerRadar] 指令列表").withStyle(ChatFormatting.AQUA), false);

        // 逐行发送而不是拼成一个多行 Component：聊天栏会自然换行，也方便逐条复制
        for (String line : HELP_LINES) {
            ctx.getSource().sendSuccess(
                    () -> Component.literal(line).withStyle(ChatFormatting.GRAY), false);
        }

        ctx.getSource().sendSuccess(
                () -> Component.literal("快捷键：K 加入准星方块 · L 移出 · J 开关全部")
                        .withStyle(ChatFormatting.DARK_GRAY), false);
        return 1;
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

    private static int executeAdd(CommandContext<CommandSourceStack> ctx, ResourceLocation id) {
        // ID 的语法合法性由 ResourceLocationArgument 保证，这里只校验"这个方块是否存在"。
        // 注意不能用 getValue(id) != null 判断 —— Forge 对未知 ID 返回默认值（空气）而不是 null。
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

    private static int executeRemove(CommandContext<CommandSourceStack> ctx, ResourceLocation id) {
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
    // /cr search
    // ==================================================================

    /**
     * 按关键词搜索方块，同时匹配 ID 和本地化名称。
     *
     * <p>这个指令的主要用途是<b>验证搜索索引</b>：在接图形界面之前，先确认
     * 中文名称能搜到、索引构建耗时可以接受，避免把问题带进 GUI 里。
     */
    private static int executeSearch(CommandContext<CommandSourceStack> ctx, String keyword) {
        long startNanos = System.nanoTime();
        BlockIndex.Result result = BlockIndex.search(keyword, MAX_LISTED);
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;

        if (result.total() == 0) {
            ctx.getSource().sendSuccess(
                    () -> Component.literal("没有匹配「" + keyword + "」的方块").withStyle(ChatFormatting.GRAY),
                    false);
            return 0;
        }

        ctx.getSource().sendSuccess(
                () -> Component.literal("「" + keyword + "」匹配 " + result.total() + " 个方块"
                                + "（显示前 " + result.entries().size() + " 个，耗时 " + elapsedMs + " ms）")
                        .withStyle(ChatFormatting.AQUA), false);

        // 已经加进白名单的标出来，避免玩家重复添加
        Set<ResourceLocation> existing = new HashSet<>(BlockFilter.ids());
        for (BlockIndex.Entry entry : result.entries()) {
            String mark = existing.contains(entry.id()) ? " [已加]" : "";
            ctx.getSource().sendSuccess(
                    () -> Component.literal("  " + entry.id() + "  " + entry.name() + mark)
                            .withStyle(ChatFormatting.GRAY), false);
        }

        return result.total();
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
