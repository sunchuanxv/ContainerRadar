package com.scx.containerradar.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.scx.containerradar.ContainerRadar;
import com.scx.containerradar.filter.BlockFilter;
import com.scx.containerradar.scan.ContainerScanner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.opengl.GL11;

import java.util.List;

/**
 * 容器高亮渲染器：给扫描到的容器画线框，<b>可以透过墙看到</b>。
 *
 * <p>渲染分两遍，这是本类唯一需要动脑的地方：
 * <ol>
 *   <li><b>第一遍</b>用原版 {@link RenderType#lines()}（带深度测试）：没被遮挡的框画成实色，
 *       让人一眼知道"这个箱子在我前面"</li>
 *   <li><b>第二遍</b>用自定义的 {@link #SEE_THROUGH_LINES}（关掉深度测试）：所有框都淡淡地再画一层，
 *       被墙挡住的也能看见</li>
 * </ol>
 *
 * <p>为什么要两遍？如果只画穿墙那遍，前排和后排的框颜色一样，你会分不清哪个箱子离你更近，
 * 空间感会丢失。这是很多商业 ESP 模组的做法。
 */
@Mod.EventBusSubscriber(modid = ContainerRadar.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ContainerRenderer {

    /** 扫描半径（格）。调大能看得更远，但扫描耗时是半径的立方 —— 见 updateCache 的注释。 */
    private static final int SCAN_RADIUS = 16;

    /** 多久重扫一次（单位：tick，20 tick = 1 秒）。 */
    private static final int RESCAN_INTERVAL_TICKS = 20;

    /** 玩家移动超过这个距离就立刻重扫。8 格的平方 = 64，用平方比较可以省掉开方运算。 */
    private static final double MOVE_THRESHOLD_SQ = 64.0D;

    /** 容器的颜色：偏青的蓝色（RGBA，取值 0~1）。 */
    private static final float[] COLOR_CONTAINER = {0.25F, 0.85F, 1.00F};

    /** 自定义白名单方块的颜色：橙黄。和容器用不同颜色，一眼能分清"这是箱子"还是"这是我加的方块"。 */
    private static final float[] COLOR_CUSTOM = {1.00F, 0.72F, 0.20F};

    /** 第一遍（未遮挡）的透明度：比较实。 */
    private static final float ALPHA_SOLID = 0.90F;

    /** 第二遍（穿墙）的透明度：比较淡，避免和第一遍混成一片。 */
    private static final float ALPHA_GHOST = 0.35F;

    /**
     * 半透明混合状态。
     *
     * <p>这是显卡的两个开关：{@code enableBlend} 打开混合，{@code defaultBlendFunc} 指定
     * "按 alpha 通道混合"。不打开混合的话，顶点里的 alpha 值会被忽略，线框变成纯色不透明。
     *
     * <p>构造器要两个 Runnable：第一个在"开始画之前"执行，第二个在"画完之后"执行。
     * <b>后面那个必须把状态还原</b>，否则会污染游戏后续所有渲染 —— 这是渲染代码最容易出事的地方。
     */
    private static final RenderStateShard.TransparencyStateShard TRANSLUCENT = new RenderStateShard.TransparencyStateShard(
            "containerradar_translucent",
            () -> {
                RenderSystem.enableBlend();
                RenderSystem.defaultBlendFunc();
            },
            () -> {
                RenderSystem.disableBlend();
                RenderSystem.defaultBlendFunc();
            });

    /**
     * 自定义渲染类型：和原版 {@code RenderType.lines()} 几乎一样，只有两处不同。
     *
     * <p><b>注意这里没有用 Access Transformer。</b>网上很多教程说必须加 AT，那是因为它们用的是
     * 包内可见的 5 参数版 {@code RenderType.create}。这里用的是 7 参数版，它在 Forge 1.20.1 里是
     * public 的，不需要改任何访问权限。同理，{@code RenderStateShard.NO_DEPTH_TEST} 这类预置常量
     * 是 protected 拿不到，但它们的<b>构造器都是 public</b>，所以自己 new 一个效果完全一样。
     *
     * <p>逐项说明：
     * <ul>
     *   <li>{@code DefaultVertexFormat.POSITION_COLOR_NORMAL} —— 每个顶点携带"坐标 + 颜色 + 法线"。
     *       画线段必须带法线，否则着色器算不出亮度</li>
     *   <li>{@code VertexFormat.Mode.LINES} —— 每两个顶点连成一条线段</li>
     *   <li>{@code 1536} —— 缓冲区初始大小（字节），一个方块框 24 个顶点绰绰有余</li>
 *   <li><b>线宽</b>这里没设，用的是默认的 1 像素。原因是 {@code RenderStateShard.LineStateShard}
 *       这个<b>类</b>是 protected（虽然它的构造器是 public），在包外无法引用 ——
 *       这是 Forge 1.20.1 里少数几个必须靠 Access Transformer 才能用的东西。
 *       1 像素已经够看清，不值得为此引入 AT 的复杂度。想要更粗的线可以以后再研究</li>
     *   <li>{@code DepthTestStateShard("always", GL_ALWAYS)} —— <b>核心</b>：深度测试永远通过，
     *       等于"无视前面有没有东西挡着"，这就是穿墙的实现</li>
     *   <li>{@code WriteMaskStateShard(true, false)} —— 写颜色、<b>不写深度</b>。
     *       如果不写深度，后面画的 GUI 等元素就不会被这些线框"挡住"</li>
     *   <li>{@code CullStateShard(false)} —— 关闭背面剔除，线框从两侧都能看到</li>
     * </ul>
     */
    private static final RenderType SEE_THROUGH_LINES = RenderType.create(
            "containerradar_see_through_lines",
            DefaultVertexFormat.POSITION_COLOR_NORMAL,
            VertexFormat.Mode.LINES,
            1536,
            false,
            false,
            RenderType.CompositeState.builder()
                    .setShaderState(new RenderStateShard.ShaderStateShard(GameRenderer::getRendertypeLinesShader))
                    .setTransparencyState(TRANSLUCENT)
                    .setDepthTestState(new RenderStateShard.DepthTestStateShard("always", GL11.GL_ALWAYS))
                    .setWriteMaskState(new RenderStateShard.WriteMaskStateShard(true, false))
                    .setCullState(new RenderStateShard.CullStateShard(false))
                    .createCompositeState(false));

    /** 扫描结果缓存。渲染时直接用这个，不再查方块 —— 这是性能的关键。 */
    private static List<BlockPos> cached = List.of();

    /** 上次扫描时的玩家位置，用于判断"是否移动了足够远"。 */
    private static BlockPos lastCenter = null;

    /** 上次扫描时的游戏时间（tick），用于判断"是否过了足够久"。 */
    private static long lastScanTick = Long.MIN_VALUE;

    private ContainerRenderer() {
    }

    /**
     * 每帧渲染世界时被调用一次。注意是<b>每帧</b>（60 次/秒），所以这里面的任何耗时操作都会被放大。
     */
    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {

        // 【第一步】只在一个特定阶段画。
        // 一帧的渲染分成很多阶段（天空、实体、半透明方块……）。AFTER_TRANSLUCENT_BLOCKS
        // 表示"半透明方块画完之后"，这是画线框的常用时机 —— 此时画面主体已就位，线框能正确叠在上面。
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        // 主菜单和世界加载时 level / player 可能是 null，直接返回，否则空指针崩溃
        if (level == null || mc.player == null) {
            return;
        }

        BlockPos playerPos = mc.player.blockPosition();

        // 【第二步】更新缓存（如果过期了）
        updateCache(level, playerPos);

        if (cached.isEmpty()) {
            return;
        }

        // 【第三步】把坐标系从「世界坐标」换成「摄像机相对坐标」。
        //
        // Minecraft 的画面永远以摄像机为原点。箱子在世界坐标 (112, 64, -34)，
        // 而摄像机在 (100, 64, 0) 时，画框必须用 (12, 0, -34) —— 也就是减掉摄像机位置。
        // 不减的话框会画错位置，而且你一走动就跟着漂。
        PoseStack poseStack = event.getPoseStack();
        Vec3 cameraPos = mc.gameRenderer.getMainCamera().getPosition();

        poseStack.pushPose();                       // 保存现场：之后要还原，否则会污染后续渲染
        poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);

        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();

        // 【第四步·第一遍】带深度测试的实色框，只能看到没被遮挡的
        VertexConsumer solid = buffers.getBuffer(RenderType.lines());
        drawAll(level, poseStack, solid, ALPHA_SOLID);
        // endBatch 才是真正"交给显卡画"的时刻，不调用就什么都不显示。
        // 这里按类型分别提交，是为了让两遍用各自的状态（一个开深度、一个关深度）。
        buffers.endBatch(RenderType.lines());

        // 【第五步·第二遍】关掉深度测试的淡色框，能透过墙看到
        VertexConsumer ghost = buffers.getBuffer(SEE_THROUGH_LINES);
        drawAll(level, poseStack, ghost, ALPHA_GHOST);
        buffers.endBatch(SEE_THROUGH_LINES);

        // 和上面的 pushPose 配对。不 pop 的话，这个 translate 会残留，
        // 导致同一帧里后面所有渲染（血条、物品栏、其他模组的 ESP）全部跟着偏移。
        poseStack.popPose();
    }

    /**
     * 把缓存里所有目标都画一遍框。
     *
     * @param level     当前世界，用来判断每个坐标是"容器"还是"白名单方块"（决定颜色）
     * @param poseStack 已经做过摄像机偏移的坐标变换栈
     * @param consumer  顶点接收器（不同渲染类型对应不同实例）
     * @param alpha     透明度
     */
    private static void drawAll(Level level, PoseStack poseStack, VertexConsumer consumer, float alpha) {
        for (BlockPos pos : cached) {
            // AABB = 轴对齐包围盒，就是一个方块大小的立方体。
            // inflate(0.002) 把它向外扩 2 毫米 —— 不加的话，框的坐标和方块表面完全重合，
            // 会互相"打架"（z-fighting），表现为线条闪烁、断裂。
            AABB box = new AABB(pos).inflate(0.002D);

            // 判断颜色。这个查询每帧每个框一次，但框的数量只有几十个，开销可以忽略。
            // 注意 != isEmpty() 的短路：白名单为空时根本不查方块状态
            boolean custom = !BlockFilter.isEmpty() && BlockFilter.matches(level.getBlockState(pos));
            float[] color = custom ? COLOR_CUSTOM : COLOR_CONTAINER;

            // LevelRenderer.renderLineBox 是原版现成的工具方法：给一个立方体，
            // 它会把 12 条棱都画出来。自己算 12 条线的顶点纯属重复劳动。
            LevelRenderer.renderLineBox(poseStack, consumer, box, color[0], color[1], color[2], alpha);
        }
    }

    /**
     * 按需重扫。只在两种情况下真的去扫：
     * <ul>
     *   <li>距上次扫描超过 {@link #RESCAN_INTERVAL_TICKS} 个 tick（默认 1 秒）</li>
     *   <li>玩家移动超过 8 格</li>
     * </ul>
     *
     * <p>为什么必须节流：扫描耗时和半径的<b>立方</b>成正比。
     * 半径 16 是 33³ ≈ 3.6 万个坐标，半径 32 就是 65³ ≈ 27 万个。
     * 如果每帧都扫，60 帧/秒 × 27 万 = 每秒 1600 万次查询，帧率立刻完蛋。
     */
    private static void updateCache(Level level, BlockPos playerPos) {
        long tick = level.getGameTime();

        boolean needScan = lastCenter == null                          // 第一次
                || tick - lastScanTick >= RESCAN_INTERVAL_TICKS        // 超过 1 秒
                || lastCenter.distSqr(playerPos) > MOVE_THRESHOLD_SQ;  // 移动超过 8 格

        if (!needScan) {
            return;
        }

        cached = ContainerScanner.findTargets(level, playerPos, SCAN_RADIUS);

        // 这里必须 immutable()：playerPos 是从玩家身上取的，后续可能被复用/修改，
        // 直接引用会让"上次扫描位置"跟着变，导致移动判断永远失效。
        lastCenter = playerPos.immutable();
        lastScanTick = tick;
    }
}
