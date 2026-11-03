package com.scx.containerradar.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import com.scx.containerradar.ContainerRadar;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 用户设置：扫描半径、显示开关、渲染上限。
 *
 * <p>存在 {@code config/containerradar-settings.json}，格式是给人看的：
 *
 * <pre>
 * {
 *   "enabled": true,
 *   "radius": 32,
 *   "showContainers": true,
 *   "showWhitelist": true,
 *   "maxRendered": 400
 * }
 * </pre>
 *
 * <h2>为什么用 JSON + 静态单例，而不用 ForgeConfigSpec</h2>
 * <ul>
 *   <li><b>格式简单</b>：玩家直接看得懂、改得动，不需要重启游戏</li>
 *   <li><b>热生效</b>：Forge 的配置系统在启动时加载，改完要重载；这里改完下一次扫描就读到了</li>
 *   <li><b>和已有代码一致</b>：白名单 {@code BlockFilter} 也是这个套路，不用维护两套心智模型</li>
 * </ul>
 *
 * <h2>线程</h2>
 * 所有读写都加 {@code synchronized}。扫描可能在渲染线程或服务端线程触发，
 * 而玩家也可能随时用指令改设置 —— 不加锁迟早遇到读到一半的状态。
 */
public final class Settings {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Path FILE = FMLPaths.CONFIGDIR.get().resolve("containerradar-settings.json");

    /** 缩进输出，方便玩家手动编辑。 */
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 半径下限。太小看不出效果，没有意义。 */
    public static final int MIN_RADIUS = 8;

    /** 半径上限。再大就没意义了：客户端渲染距离一般也就 8~12 个区块（128~192 格）。 */
    public static final int MAX_RADIUS = 64;

    /** 渲染上限的合法范围。 */
    private static final int MIN_MAX_RENDERED = 1;
    private static final int MAX_MAX_RENDERED = 5000;

    // ---- 内存里的当前值（默认值就是首次启动时写入文件的内容）----
    private static boolean enabled = true;
    private static int radius = 32;
    private static boolean showContainers = true;
    private static boolean showWhitelist = true;
    private static int maxRendered = ContainerRadar.MAX_RENDERED;

    private static boolean loaded = false;

    private Settings() {
    }

    /**
     * JSON 的映射对象。
     *
     * <p>字段用包装类型不用基本类型，是为了能区分"文件里没写这个键"（null）
     * 和"写了 0"。缺失的键在加载时会保留默认值，而不是被覆盖成 0 / false ——
     * 这样玩家删掉某一行不会把设置搞坏。
     */
    private static final class Data {
        Boolean enabled;
        Integer radius;
        Boolean showContainers;
        Boolean showWhitelist;
        Integer maxRendered;
    }

    // ==================================================================
    // 读取
    // ==================================================================

    /** 总开关：关闭时连扫描都不做，开销为零。 */
    public static synchronized boolean enabled() {
        ensureLoaded();
        return enabled;
    }

    /** 扫描半径（格），保证在合法范围内。 */
    public static synchronized int radius() {
        ensureLoaded();
        return radius;
    }

    /** 是否绘制容器。 */
    public static synchronized boolean showContainers() {
        ensureLoaded();
        return showContainers;
    }

    /** 是否绘制白名单方块。 */
    public static synchronized boolean showWhitelist() {
        ensureLoaded();
        return showWhitelist;
    }

    /** 单帧最多绘制多少个目标。 */
    public static synchronized int maxRendered() {
        ensureLoaded();
        return maxRendered;
    }

    // ==================================================================
    // 修改（改完立刻写回文件）
    // ==================================================================

    /** 设置总开关。 */
    public static synchronized void setEnabled(boolean value) {
        ensureLoaded();
        enabled = value;
        save();
    }

    /** 翻转总开关，返回翻转后的值。 */
    public static synchronized boolean toggleEnabled() {
        ensureLoaded();
        enabled = !enabled;
        save();
        return enabled;
    }

    /** 设置半径，并夹到合法范围内。返回最终生效的值。 */
    public static synchronized int setRadius(int value) {
        ensureLoaded();
        radius = clamp(value, MIN_RADIUS, MAX_RADIUS);
        save();
        return radius;
    }

    /** 设置是否绘制容器。 */
    public static synchronized void setShowContainers(boolean value) {
        ensureLoaded();
        showContainers = value;
        save();
    }

    /** 设置是否绘制白名单方块。 */
    public static synchronized void setShowWhitelist(boolean value) {
        ensureLoaded();
        showWhitelist = value;
        save();
    }

    /** 设置渲染上限。 */
    public static synchronized int setMaxRendered(int value) {
        ensureLoaded();
        maxRendered = clamp(value, MIN_MAX_RENDERED, MAX_MAX_RENDERED);
        save();
        return maxRendered;
    }

    /**
     * 从磁盘重新读取。
     *
     * <p>给 {@code /cr reload} 用 —— 玩家手动编辑了 JSON 之后，让改动立刻生效，
     * 而不必重启游戏。
     */
    public static synchronized void reload() {
        loaded = false;
        ensureLoaded();
    }

    // ==================================================================
    // 内部实现
    // ==================================================================

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;

        if (!Files.exists(FILE)) {
            // 第一次运行：写一份带默认值的文件出来，玩家能直接看到有哪些选项可改
            save();
            return;
        }

        try (Reader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
            Data data = GSON.fromJson(reader, Data.class);
            if (data != null) {
                // 逐个判断是否为 null：文件里缺失的键保留默认值，而不是被覆盖成 0
                if (data.enabled != null) {
                    enabled = data.enabled;
                }
                if (data.radius != null) {
                    radius = clamp(data.radius, MIN_RADIUS, MAX_RADIUS);
                }
                if (data.showContainers != null) {
                    showContainers = data.showContainers;
                }
                if (data.showWhitelist != null) {
                    showWhitelist = data.showWhitelist;
                }
                if (data.maxRendered != null) {
                    maxRendered = clamp(data.maxRendered, MIN_MAX_RENDERED, MAX_MAX_RENDERED);
                }
            }
            LOGGER.info("[ContainerRadar] 已加载设置：半径 {}，容器 {}，白名单 {}，渲染上限 {}",
                    radius, showContainers, showWhitelist, maxRendered);
        } catch (Exception e) {
            // 配置文件坏了不该让游戏崩溃：用默认值继续跑，并在日志里说明
            LOGGER.error("[ContainerRadar] 读取设置失败，使用默认值：{}", FILE, e);
        }
    }

    private static void save() {
        Data data = new Data();
        data.enabled = enabled;
        data.radius = radius;
        data.showContainers = showContainers;
        data.showWhitelist = showWhitelist;
        data.maxRendered = maxRendered;

        try {
            Files.createDirectories(FILE.getParent());
            try (Writer writer = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
                GSON.toJson(data, writer);
            }
        } catch (IOException e) {
            LOGGER.error("[ContainerRadar] 写入设置失败：{}", FILE, e);
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
