package com.scx.containerradar.compat;

import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lootr 兼容层：判断一个容器「我开过没有」。
 *
 * <h2>为什么要单独一个类，而且要反射</h2>
 * 本模组不能硬依赖 Lootr —— 没装 Lootr 的玩家会因为找不到类而在启动时崩溃。
 * 所以这里：
 * <ul>
 *   <li>先用 {@link ModList#isLoaded(String)} 判断 Lootr 在不在，不在就直接返回 {@code NOT_LOOTR}</li>
 *   <li>全程用反射调方法，<b>代码里不出现任何 Lootr 的类型名</b>，
 *       这样即使 Lootr 没装，这个类也能正常加载、正常返回"不关我事"</li>
 * </ul>
 *
 * <h2>Lootr 的状态存在哪（读源码得到的结论）</h2>
 * Lootr 的容器方块实体实现了公开接口 {@code noobanidus.mods.lootr.api.IHasOpeners}，
 * 它只有一个方法：
 * <pre>
 *   Set&lt;UUID&gt; getOpeners();   // 开过这个容器的所有玩家
 * </pre>
 *
 * <p>这个集合在<b>客户端也有值</b>——证据是 Lootr 自己的渲染器
 * {@code LootrChestBlockRenderer#getMaterial} 里会调 {@code getOpeners()} 再和自己玩家的 UUID 比对，
 * 用来决定箱子贴图是金色（没开过）还是蓝色（开过了）。既然它能在纯客户端画出两种颜色，
 * 说明这份数据是同步到客户端的。我们只是把同一份数据拿来做线框颜色。
 *
 * <h2>为什么用 Optional 包一层缓存</h2>
 * 反射找方法失败会抛 {@code NoSuchMethodException}，而抛异常的开销很高。
 * 渲染时每个方块每帧都要问一次，如果每次都抛一遍异常，帧率会明显下降。
 * 所以按类缓存结果，<b>连"没找到"这个结果也缓存</b>——这才是这里用
 * {@code Optional} 而不是直接存 {@code Method} 的原因。
 */
public final class LootrCompat {

    /** Lootr 的状态，供渲染器决定颜色。 */
    public enum State {
        /** 不是 Lootr 的容器（普通箱子、木桶等），或者根本没装 Lootr。 */
        NOT_LOOTR,
        /** 是 Lootr 容器，但我还没开过 —— 里面有属于我的战利品。 */
        UNOPENED,
        /** 是 Lootr 容器，我已经开过了 —— 不用再跑一趟。 */
        OPENED
    }

    /** Lootr 是否已加载。只算一次，ModList 查询本身也不便宜。 */
    private static final boolean LOOTR_LOADED = ModList.get().isLoaded("lootr");

    /**
     * 按方块实体的类缓存 {@code getOpeners} 方法。
     *
     * <p>用 {@code ConcurrentHashMap} 是因为扫描可能在不同线程触发。
     * 值用 {@code Optional} 是为了把"这个类没有这个方法"也缓存下来。
     */
    private static final Map<Class<?>, Optional<Method>> METHOD_CACHE = new ConcurrentHashMap<>();

    private LootrCompat() {
    }

    /** 当前环境有没有装 Lootr。 */
    public static boolean isLoaded() {
        return LOOTR_LOADED;
    }

    /**
     * 判断某个方块实体对某个玩家来说处于什么状态。
     *
     * @param be       要判断的方块实体，可以为 {@code null}
     * @param playerId 当前玩家的 UUID；客户端用 {@code Minecraft.getInstance().player.getUUID()}
     * @return Lootr 状态；不是 Lootr 容器、没装 Lootr、或反射失败都返回 {@code NOT_LOOTR}
     */
    public static State stateOf(BlockEntity be, UUID playerId) {
        // 没装 Lootr / 没有方块实体 / 没有玩家信息：直接放弃，一行反射都不做
        if (!LOOTR_LOADED || be == null || playerId == null) {
            return State.NOT_LOOTR;
        }

        // 按类查缓存。第一次遇到某个 Lootr 容器类时会反射一次，之后全是查表。
        Optional<Method> cached = METHOD_CACHE.computeIfAbsent(be.getClass(), LootrCompat::resolveGetOpeners);
        if (cached.isEmpty()) {
            // 这个类没有 getOpeners 方法 —— 说明它不是 Lootr 的容器
            return State.NOT_LOOTR;
        }

        try {
            Object result = cached.get().invoke(be);
            if (result instanceof Set<?> openers) {
                // 集合里有我的 UUID = 我开过
                return openers.contains(playerId) ? State.OPENED : State.UNOPENED;
            }
        } catch (ReflectiveOperationException e) {
            // 反射失败不该让游戏崩溃：顶多颜色不对，功能仍然可用
            return State.NOT_LOOTR;
        }
        return State.NOT_LOOTR;
    }

    /**
     * 反射查找 {@code getOpeners()} 方法。
     *
     * <p>找不到就返回 {@code Optional.empty()}，绝不抛出去 ——
     * 这个方法会被 {@code computeIfAbsent} 调用，抛异常会打断整个缓存逻辑。
     */
    private static Optional<Method> resolveGetOpeners(Class<?> clazz) {
        try {
            Method method = clazz.getMethod("getOpeners");
            // 顺手校验返回值类型，防止别的模组碰巧也有同名方法却不是我们想要的
            if (Set.class.isAssignableFrom(method.getReturnType())) {
                return Optional.of(method);
            }
        } catch (NoSuchMethodException ignored) {
            // 正常情况：绝大多数方块实体都没有这个方法
        }
        return Optional.empty();
    }
}
