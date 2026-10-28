package com.scx.containerradar.filter;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 自定义方块白名单：让玩家能高亮「不是容器」的任意方块（远古残骸、刷怪笼、某种矿石……）。
 *
 * <p>为什么用纯文本文件而不是 ForgeConfigSpec？
 * <ul>
 *   <li>Forge 配置在启动时加载，改完要重启游戏才生效；文本文件可以随时手改</li>
 *   <li>格式简单，一行一个 ID，玩家能直接看懂并手动编辑</li>
 *   <li>不依赖配置系统的复杂度，出问题容易排查</li>
 * </ul>
 *
 * <p>文件位置：{@code .minecraft/config/containerradar-blocks.txt}（开发环境是 {@code run/config/}）
 *
 * <p><b>注意</b>：用 {@code /cr add} 或 {@code /cr remove} 修改后，文件会被重写，
 * 你手写的注释会丢失——这是为了代码简单做的取舍。
 */
public final class BlockFilter {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 白名单文件路径。FMLPaths.CONFIGDIR 指向游戏目录下的 config 文件夹。 */
    private static final Path FILE = FMLPaths.CONFIGDIR.get().resolve("containerradar-blocks.txt");

    /**
     * 白名单集合。用 LinkedHashSet 而不是 HashSet，是为了保持插入顺序 ——
     * 玩家 {@code /cr list} 时看到的顺序和添加顺序一致，符合直觉。
     */
    private static final Set<ResourceLocation> IDS = new LinkedHashSet<>();

    /** 是否已经从磁盘加载过。用懒加载，避免在类初始化阶段碰文件系统。 */
    private static boolean loaded = false;

    private BlockFilter() {
    }

    /**
     * 懒加载：第一次被用到时才读文件。
     *
     * <p>整个方法加 {@code synchronized}，因为扫描可能在不同线程被触发，
     * 而 {@code LinkedHashSet} 不是线程安全的。加锁的成本远低于一次崩溃。
     */
    private static synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;

        if (!Files.exists(FILE)) {
            // 第一次运行：生成一个带注释的空模板，让玩家知道这个文件怎么改
            save();
            return;
        }

        try {
            for (String raw : Files.readAllLines(FILE, StandardCharsets.UTF_8)) {
                String line = raw.trim();
                // 跳过空行和注释行（# 开头）
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                ResourceLocation id = ResourceLocation.tryParse(line);
                if (id == null) {
                    LOGGER.warn("[ContainerRadar] 白名单里有无法解析的方块 ID：{}", line);
                    continue;
                }
                IDS.add(id);
            }
            LOGGER.info("[ContainerRadar] 已加载 {} 个自定义方块", IDS.size());
        } catch (IOException e) {
            LOGGER.error("[ContainerRadar] 读取白名单失败：{}", FILE, e);
        }
    }

    /**
     * 白名单是不是空的。
     *
     * <p>这个方法存在的意义是<b>性能</b>：扫描时每个坐标都会调用一次 {@link #matches}，
     * 而 matches 内部要查方块状态。白名单为空时直接跳过，能省掉几万次无用的查询。
     */
    public static synchronized boolean isEmpty() {
        ensureLoaded();
        return IDS.isEmpty();
    }

    /**
     * 判断一个方块状态是否在白名单里。
     *
     * @param state 要判断的方块状态
     * @return 在白名单里返回 true；白名单为空时返回 false
     */
    public static synchronized boolean matches(BlockState state) {
        ensureLoaded();
        if (IDS.isEmpty()) {
            return false;
        }
        // 方块对象 → 注册名（如 minecraft:ancient_debris）。
        // getKey 理论上可能返回 null（未注册的方块），所以要判空
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        return id != null && IDS.contains(id);
    }

    /** 返回白名单里的所有 ID（副本，防止外部修改内部集合）。 */
    public static synchronized List<ResourceLocation> ids() {
        ensureLoaded();
        return new ArrayList<>(IDS);
    }

    /**
     * 加入白名单。
     *
     * @return 原本不在列表里返回 true；已经在里面返回 false（用于给玩家不同的提示）
     */
    public static synchronized boolean add(ResourceLocation id) {
        ensureLoaded();
        if (!IDS.add(id)) {
            return false;
        }
        save();
        return true;
    }

    /**
     * 从白名单移除。
     *
     * @return 原本在列表里返回 true；本来就没有返回 false
     */
    public static synchronized boolean remove(ResourceLocation id) {
        ensureLoaded();
        if (!IDS.remove(id)) {
            return false;
        }
        save();
        return true;
    }

    /** 把内存里的集合写回磁盘。任何一次 add/remove 之后都要调用。 */
    private static synchronized void save() {
        List<String> lines = new ArrayList<>();
        lines.add("# ContainerRadar 自定义方块白名单");
        lines.add("# 每行写一个方块 ID，# 开头的是注释行");
        lines.add("# 示例：");
        lines.add("#   minecraft:ancient_debris");
        lines.add("#   minecraft:spawner");
        lines.add("#   create:zinc_ore");
        lines.add("#");
        lines.add("# 提示：也可以用游戏内指令 /cr add <方块ID> 或 /cr remove <方块ID> 来改");
        lines.add("# 注意：用指令修改后本文件会被重写，你手写的注释会丢失");
        lines.add("");
        for (ResourceLocation id : IDS) {
            lines.add(id.toString());
        }

        try {
            // 确保 config 目录存在（首次启动时可能还没建）
            Files.createDirectories(FILE.getParent());
            Files.write(FILE, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.error("[ContainerRadar] 写入白名单失败：{}", FILE, e);
        }
    }

    /**
     * 把玩家输入的字符串解析成方块 ID。
     *
     * <p>支持简写：不带冒号时自动补 {@code minecraft:}，所以 {@code /cr add ancient_debris}
     * 和 {@code /cr add minecraft:ancient_debris} 效果一样。少打几个字，体验差别很大。
     *
     * @param input 玩家输入，如 {@code ancient_debris} 或 {@code create:zinc_ore}
     * @return 解析成功返回 ID，格式非法返回 {@code null}
     */
    public static ResourceLocation parseId(String input) {
        String text = input.trim();
        if (text.isEmpty()) {
            return null;
        }
        if (!text.contains(":")) {
            text = "minecraft:" + text;
        }
        return ResourceLocation.tryParse(text);
    }

    /**
     * 检查某个 ID 是否真的对应一个已注册的方块。
     *
     * <p>不检查的话，玩家能往白名单里塞 {@code minecraft:not_a_block}，
     * 文件看起来没问题，但永远扫不到东西——这种"静默失败"最难查。
     *
     * <p><b>这里踩过一个坑，值得记住</b>：一开始写的是
     * {@code ForgeRegistries.BLOCKS.getValue(id) != null}，结果任何乱写的 ID 都能通过。
     * 因为 Forge 的 {@code getValue} 对不存在的键<b>返回默认值而不是 null</b>——
     * 方块注册表的默认值是空气（{@code Blocks.AIR}）。
     *
     * <p>所以判断"存不存在"要用 {@code containsKey}，不要用 {@code getValue != null}。
     * 这是一条通用规律：<b>想知道某个键在不在，就别去取值再判空。</b>
     */
    public static boolean isRegisteredBlock(ResourceLocation id) {
        return ForgeRegistries.BLOCKS.containsKey(id);
    }
}
