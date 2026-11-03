package com.scx.containerradar.key;

import com.mojang.blaze3d.platform.InputConstants;
import com.scx.containerradar.ContainerRadar;
import com.scx.containerradar.filter.BlockFilter;
import com.scx.containerradar.render.ContainerRenderer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.lwjgl.glfw.GLFW;

/**
 * 快捷键：把准星对准的方块加入 / 移出自定义白名单。
 *
 * <p>为什么不直接用指令？
 * 因为指令要打开聊天框、手敲方块 ID（{@code minecraft:ancient_debris} 这种），
 * 而玩家在游戏里最自然的动作是"看着它、按个键"。这是体验上的刚需，不是锦上添花。
 *
 * <h2>两个事件总线，别搞混</h2>
 * <ul>
 *   <li>{@link RegisterKeyMappingsEvent} 在 <b>mod 事件总线</b>上触发，且必须在客户端。
 *       所以注册动作放在主类的 {@code ClientModEvents}（那里标的是 {@code bus = MOD}）里，
 *       调用本类的 {@link #register(RegisterKeyMappingsEvent)}</li>
 *   <li>{@link TickEvent.ClientTickEvent} 在 <b>Forge 事件总线</b>上触发。
 *       本类自己的 {@code @EventBusSubscriber} 标的是 {@code bus = FORGE}，用来收这个</li>
 * </ul>
 * 一个类只能挂一个总线，所以按键注册和按键响应被分在了两个地方 —— 这是 Forge 的固定套路。
 *
 * <h2>为什么在 tick 里轮询，而不是监听按键事件</h2>
 * 原版 {@link KeyMapping#consumeClick()} 是"队列"式设计：按下时入队，你什么时候取都行。
 * 在 tick 里统一取，能保证"按一次只响应一次"，而且拿得到当前的世界状态（准星指向哪）。
 * 如果在按键回调里直接做逻辑，会遇到"按得比 tick 快"导致的漏按/重复触发。
 */
@Mod.EventBusSubscriber(modid = ContainerRadar.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class KeyBindings {

    /**
     * 按键分类。这个 ID 要能在语言文件里找到对应翻译，
     * 否则游戏设置界面会直接显示原始 key（比如 {@code key.categories.containerradar}）。
     */
    public static final String CATEGORY = "key.categories.containerradar";

    /**
     * 加入白名单，默认 K。
     *
     * <p>{@code KeyConflictContext.IN_GAME} 表示这个键只在游戏内（不在 GUI 里）生效，
     * 这样玩家在背包里按 K 不会误触发。
     */
    private static final KeyMapping ADD_TARGET = new KeyMapping(
            "key.containerradar.add_target",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_K,
            CATEGORY);

    /** 移出白名单，默认 L。 */
    private static final KeyMapping REMOVE_TARGET = new KeyMapping(
            "key.containerradar.remove_target",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_L,
            CATEGORY);

    private KeyBindings() {
    }

    /**
     * 把按键注册进游戏的"按键绑定"菜单。由主类在 mod 事件总线上调用。
     *
     * @param event Forge 提供的注册事件，只有在这个时机注册的按键才会出现在设置界面里
     */
    public static void register(RegisterKeyMappingsEvent event) {
        event.register(ADD_TARGET);
        event.register(REMOVE_TARGET);
    }

    /**
     * 每个客户端 tick 检查一次按键队列（一秒 20 次，够快也不会浪费）。
     *
     * <p>只在 {@code Phase.END} 处理：一 tick 分开始和结束两个阶段，
     * 放在 END 能保证这一 tick 的世界状态已经更新完（比如准星指向已经刷新）。
     */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        // consumeClick() 返回 true 表示"队列里还有一次点击"，同时把它消耗掉。
        // 用 while 是为了把队列清空 —— 万一玩家一 tick 内按了两下，也不会留到下一 tick 才响应。
        boolean wantAdd = false;
        boolean wantRemove = false;
        while (ADD_TARGET.consumeClick()) {
            wantAdd = true;
        }
        while (REMOVE_TARGET.consumeClick()) {
            wantRemove = true;
        }

        if (!wantAdd && !wantRemove) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }

        ResourceLocation id = lookedAtBlockId(mc);
        if (id == null) {
            // 准星对着空气/太远：给个明确反馈，而不是"按了没反应"。
            // 静默失败是最让人困惑的交互设计。
            actionBar(mc, Component.literal("准星没有对准任何方块").withStyle(ChatFormatting.GRAY));
            return;
        }

        if (wantAdd) {
            handleAdd(mc, id);
        } else {
            handleRemove(mc, id);
        }
    }

    private static void handleAdd(Minecraft mc, ResourceLocation id) {
        if (!BlockFilter.isRegisteredBlock(id)) {
            actionBar(mc, Component.literal("找不到这个方块：" + id).withStyle(ChatFormatting.RED));
            return;
        }
        if (!BlockFilter.add(id)) {
            actionBar(mc, Component.literal(id + " 已经在白名单里了").withStyle(ChatFormatting.GRAY));
            return;
        }
        // 立刻请求渲染器重扫，不然要等最多 1 秒才看到框，手感会"卡一下"。
        ContainerRenderer.requestRescan();
        actionBar(mc, Component.literal("已加入白名单：" + id).withStyle(ChatFormatting.GREEN));
    }

    private static void handleRemove(Minecraft mc, ResourceLocation id) {
        if (!BlockFilter.remove(id)) {
            actionBar(mc, Component.literal(id + " 不在白名单里").withStyle(ChatFormatting.GRAY));
            return;
        }
        ContainerRenderer.requestRescan();
        actionBar(mc, Component.literal("已移出白名单：" + id).withStyle(ChatFormatting.YELLOW));
    }

    /**
     * 取准星指向的方块的注册名。
     *
     * <p>{@code mc.hitResult} 是游戏每帧帮你算好的"射线检测结果"，已经考虑了攻击距离、
     * 方块形状、流体等规则。直接拿来用比自己再算一遍射线要准确得多 ——
     * 判断"玩家看着哪个方块"这种需求，永远先看 hitResult。
     *
     * @return 方块 ID；没对着方块、或者是空气时返回 {@code null}
     */
    private static ResourceLocation lookedAtBlockId(Minecraft mc) {
        // instanceof 模式匹配：既判断类型又完成转换，Java 16+ 的写法
        if (!(mc.hitResult instanceof BlockHitResult hit)) {
            return null;
        }
        BlockPos pos = hit.getBlockPos();
        BlockState state = mc.level.getBlockState(pos);
        if (state.isAir()) {
            return null;
        }
        return ForgeRegistries.BLOCKS.getKey(state.getBlock());
    }

    /** 在物品栏上方的动作栏显示一行提示。比聊天栏安静，不会刷屏。 */
    private static void actionBar(Minecraft mc, Component message) {
        if (mc.player != null) {
            mc.player.displayClientMessage(message, true);
        }
    }
}
