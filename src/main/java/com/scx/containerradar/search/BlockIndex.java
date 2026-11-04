package com.scx.containerradar.search;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 方块索引：一次性把所有方块 ID 和本地化名称算好，供搜索用。
 *
 * <h2>为什么需要索引</h2>
 * 注册表里有约 3.6 万个方块。如果每敲一个字符就遍历一遍并调用 {@link Block#getName()}，
 * 每次搜索要做 3.6 万次"翻译查找 + Component 转字符串"，在输入框里连打几个字就会明显卡顿。
 *
 * <p>索引把这些昂贵操作**只做一次**（首次搜索时），之后每次搜索只是普通的字符串 {@code contains}，
 * 3.6 万次字符串比较大约 2~5 毫秒，完全可以每按键一次就搜。
 *
 * <h2>为什么同时存大小写两份名称</h2>
 * 匹配要忽略大小写，但显示要保留原样（英文名 "Diamond Ore" 不该显示成小写）。
 * 所以存两份：{@code name} 用来显示，{@code nameLower} 用来匹配。中文没有大小写，两份内容相同。
 *
 * <h2>中文搜索能用吗</h2>
 * 能，前提是游戏语言是中文。{@link Block#getName()} 返回的是<b>已本地化</b>的 Component，
 * 中文环境下 {@code getString()} 就是「钻石矿石」。
 * 例外：没做中文翻译的模组方块会返回翻译键（{@code block.xxx.yyy}）或英文名 ——
 * 这种情况下按 ID 搜索仍然有效，这也是同时匹配 ID 和名称的原因。
 *
 * <p>只在客户端调用（搜索界面是 GUI），服务端不会有翻译，索引出来全是翻译键。
 */
public final class BlockIndex {

    /**
     * 一个方块条目。
     *
     * @param id        注册名，例如 {@code minecraft:diamond_ore}
     * @param name      本地化名称，用于显示
     * @param nameLower 小写名称，仅用于匹配（避免每次搜索都调 toLowerCase）
     */
    public record Entry(ResourceLocation id, String name, String nameLower) {
    }

    /**
     * 搜索结果。
     *
     * @param entries 命中的条目（已按上限截断）
     * @param total   命中总数（可能大于 entries 的长度）
     */
    public record Result(List<Entry> entries, int total) {
    }

    /** 索引本体。首次使用前为 {@code null}（懒加载）。 */
    private static List<Entry> index = null;

    private BlockIndex() {
    }

    /**
     * 构建并返回索引。只在第一次调用时真正构建，之后直接返回缓存。
     *
     * <p>用 {@code synchronized} 是因为界面线程和可能的其他调用方要看到同一份数据，
     * 而构建过程有几百毫秒，不能被重入。
     */
    private static synchronized List<Entry> index() {
        if (index != null) {
            return index;
        }

        List<Entry> built = new ArrayList<>(ForgeRegistries.BLOCKS.getKeys().size());
        for (ResourceLocation id : ForgeRegistries.BLOCKS.getKeys()) {
            Block block = ForgeRegistries.BLOCKS.getValue(id);
            // getValue 对不存在的键会返回默认值（空气），正常遍历时不会出现，防御性判空
            if (block == null) {
                continue;
            }
            Component displayName = block.getName();
            String name = displayName.getString();
            built.add(new Entry(id, name, name.toLowerCase(Locale.ROOT)));
        }

        index = List.copyOf(built);
        return index;
    }

    /**
     * 按关键词搜索方块，同时匹配 ID 和本地化名称。
     *
     * @param query 关键词，大小写不敏感；中文直接输入即可
     * @param limit 最多返回多少条（界面一屏放不下太多，限流也能省渲染）
     * @return 命中条目与命中总数
     */
    public static Result search(String query, int limit) {
        String needle = query.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return new Result(List.of(), 0);
        }

        List<Entry> hits = new ArrayList<>();
        int total = 0;

        for (Entry entry : index()) {
            // ID 用完整形式匹配（含命名空间），这样搜 "create:" 能把某个模组的方块全列出来
            boolean idMatch = entry.id().toString().contains(needle);
            // 名称用预存的小写副本匹配，省掉每次的 toLowerCase
            boolean nameMatch = entry.nameLower().contains(needle);

            if (idMatch || nameMatch) {
                total++;
                // 只收集前 limit 条，但仍然把 total 数完，界面上才能显示"共 N 个"
                if (hits.size() < limit) {
                    hits.add(entry);
                }
            }
        }

        return new Result(hits, total);
    }

    /**
     * 索引是否已经构建过。给界面用来决定要不要先显示"正在建立索引"。
     */
    public static synchronized boolean isReady() {
        return index != null;
    }

    /**
     * 主动构建索引。
     *
     * <p>界面在打开时调用它：让 300 毫秒左右的构建成本落在"打开界面"那一刻，
     * 而不是落在玩家敲下第一个字符的时候 —— 后者的卡顿会让人觉得输入框坏了。
     */
    public static void ensureBuilt() {
        index();
    }
}
