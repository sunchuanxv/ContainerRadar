package com.scx.containerradar.scan;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;

import java.util.ArrayList;
import java.util.List;

/**
 * 容器扫描器：在一个立方体范围内，找出所有「能装东西的方块」。
 *
 * <p>这个类只做纯计算，不碰渲染、不碰网络、不碰界面。
 * 以后的指令（ScanCommand）和高亮渲染器都会调用它，
 * 所以它必须保持「传入参数 → 返回坐标列表」的无状态形式，不能依赖任何全局变量。
 *
 * <p><b>核心思路：不按方块 ID 认容器，按「能力」认容器。</b>
 * 只要一个方块实体对外声明了 ITEM_HANDLER（物品存储能力），就认为它是容器。
 * 这样一来，原版箱子、Lootr 箱子、铁箱子、精妙存储、Create 仓库……
 * 全部自动兼容，不需要为任何一个模组单独写适配代码。
 * 这也是本项目区别于 Chest Finder ESP（硬编码方块白名单）的地方。
 */
public final class ContainerScanner {

    /**
     * 工具类不应该被实例化，所有方法都是 static。
     * 私有构造器 + final 类 = 从源头上挡住 `new ContainerScanner()` 这种写法。
     */
    private ContainerScanner() {
    }

    /**
     * 找出以 center 为中心、radius 为半径的立方体范围内所有容器。
     *
     * @param level  要扫描的世界。用 Level 而不是 ClientLevel，
     *               因为服务端和客户端各自持有一份 World 对象，扫描要以「方块实体数据在哪里」为准
     * @param center 扫描中心，通常是玩家的脚下方块坐标
     * @param radius 半径（方块数）。实际扫描体积是 (2*radius+1)^3 个坐标，
     *               radius=32 时约 27 万个位置，所以这是个「重」方法，绝对不能每帧调用
     * @return 所有容器的坐标。返回的是不可变副本，可以安全存进集合、跨帧持有
     */
    public static List<BlockPos> findContainers(Level level, BlockPos center, int radius) {

        // 用 List 声明，用 ArrayList 实现。调用方只关心「这是一串坐标」，
        // 不需要知道底层是数组还是链表 —— 这叫面向接口编程。
        List<BlockPos> result = new ArrayList<>();

        // 算出扫描立方体的两个对角点。
        // BlockPos.offset(dx, dy, dz) = 向量加法，返回一个「新的」BlockPos，原坐标不变。
        // BlockPos 是不可变类，所以每次 offset 都会创建新对象，这是它的设计（线程安全、可安全缓存）。
        BlockPos max = center.offset(radius, radius, radius);
        BlockPos min = center.offset(-radius, -radius, -radius);

        // betweenClosed(min, max) 返回一个可迭代对象，能遍历立方体内所有整数坐标。
        // 名字里的 Closed 表示「包含边界」，即 min 和 max 本身也会被扫到（闭区间）。
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {

            // 【第一步】拿到这个坐标上的「方块实体」（BlockEntity）。
            //
            // 关键概念：不是每个方块都有 BlockEntity。
            //   石头、泥土、玻璃 —— 只有类型和状态，没有额外数据，getBlockEntity 返回 null
            //   箱子、熔炉、漏斗 —— 需要记住「里面装了什么 / 烧到哪一步」，所以有 BlockEntity
            //
            // 所以「拿不到 BlockEntity」= 这个坐标顶多是个普通方块，可以直接跳过，性能上也是免费的。
            BlockEntity be = level.getBlockEntity(pos);

            // 【第二步】问这个方块实体：「你能装物品吗？」
            // 判断逻辑抽到了下面的 isContainer()，因为指令（跑在服务端）和渲染器（跑在客户端）
            // 必须用同一套标准，不能各写一份 —— 哪天标准变了只改一处。
            if (isContainer(be)) {

                // 【第三步】记录这个坐标。
                //
                // 关键坑：betweenClosed 为了节省内存，每次迭代都复用同一个 BlockPos 对象，
                // 只修改它内部的 x/y/z 三个字段。如果直接 add(pos)，
                // 集合里存的全是同一个引用，循环结束后所有元素都会指向最后那个坐标。
                //
                // immutable() 创建一个「不会被复用」的独立副本，这一步不能省。
                result.add(pos.immutable());
            }
        }

        return result;
    }

    /**
     * 判断一个方块实体是不是「容器」。
     *
     * <p>这里用<b>两个条件</b>，而不是只看能力，原因很实际：
     *
     * <ol>
     *   <li><b>ITEM_HANDLER 能力</b>：Forge 统一的物品存储契约。装了模组容器
     *       （铁箱子、精妙存储、Create 仓库等）时，这是最可靠的判断依据。</li>
     *   <li><b>instanceof Container</b>：原版接口。加这个兜底是因为扫描可能发生在
     *       <b>客户端世界</b>（渲染器就是这么用的），而客户端的方块实体不一定都暴露了
     *       capability。原版箱子、Lootr 箱子都实现了 Container，兜底能保证它们在客户端也被认出来。</li>
     * </ol>
     *
     * <p>换句话说：能力优先，接口兜底。两个都不满足才算不是容器。
     *
     * @param be 可能为 {@code null}（普通方块没有方块实体）
     * @return 是容器返回 true
     */
    private static boolean isContainer(BlockEntity be) {
        // 先挡掉 null，后面就可以放心调方法了。
        // 这个判断放在方法内部，而不是让每个调用点自己判，能避免漏判导致的空指针。
        if (be == null) {
            return false;
        }

        // 条件一：有物品存储能力（跨模组通用）
        if (be.getCapability(ForgeCapabilities.ITEM_HANDLER).isPresent()) {
            return true;
        }

        // 条件二：实现了原版 Container 接口（客户端兜底）
        return be instanceof Container;
    }
}
