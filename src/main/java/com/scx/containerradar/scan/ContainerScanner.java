package com.scx.containerradar.scan;

import com.scx.containerradar.filter.BlockFilter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.common.capabilities.ForgeCapabilities;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
     * 找出以 center 为中心、radius 为半径的立方体范围内所有「扫描目标」（<b>按区块遍历</b>）。
     *
     * <p>「目标」= 容器 <b>或</b> 玩家加进白名单的任意方块。
     *
     * <h2>为什么不逐个坐标查</h2>
     * 旧实现（{@link #findTargetsBruteForce}）要遍历立方体内的每一个方块坐标：
     * 半径 64 就是 129³ ≈ 214 万个位置，每个位置都查一次哈希表。而一个区块有
     * 98,304 个方块位置，里面的方块实体通常只有 0~20 个 —— 等于为了找这 20 个，
     * 把 9.8 万个位置全问了一遍。
     *
     * <p>这里换两个思路，效果等价但成本降两三个数量级：
     * <ul>
     *   <li><b>容器</b>：直接读区块的方块实体列表（{@code getBlockEntities()}），
     *       成本只和方块实体数量有关，与半径体积无关</li>
     *   <li><b>白名单方块</b>：用原版 {@code findBlocks}，它遍历的是区块的 section palette
     *       （这个区块里实际出现过的方块类型），不碰空气也不碰没出现过的东西</li>
     * </ul>
     *
     * <h2>两个必须注意的坑</h2>
     * <ol>
     *   <li>{@code level.getChunk} 对<b>未加载</b>的区块会触发同步加载（从磁盘读+生成），
     *       主线程会卡几百毫秒。所以先用 {@code hasChunk} 判断，没加载就跳过</li>
     *   <li>方块实体列表里什么都有（告示牌、熔炉、床、旗帜……），仍然要过容器判断</li>
     * </ol>
     */
    public static List<BlockPos> findTargets(Level level, BlockPos center, int radius) {

        // 用 Set 收集：同一个坐标可能从两条路径被找到两次
        // （比如玩家把某个箱子也加进了白名单），Set 自动去重
        Set<BlockPos> found = new LinkedHashSet<>();

        // 半径范围内的区块坐标范围。>> 4 等价于除以 16（区块边长），位运算更快也更直观
        int minChunkX = (center.getX() - radius) >> 4;
        int maxChunkX = (center.getX() + radius) >> 4;
        int minChunkZ = (center.getZ() - radius) >> 4;
        int maxChunkZ = (center.getZ() + radius) >> 4;

        // 白名单为空时完全跳过第二条路径（大多数玩家的情况），省掉整轮 findBlocks
        boolean hasWhitelist = !BlockFilter.isEmpty();

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {

                // 未加载的区块直接跳过。千万不要在这里调 getChunk —— 它会触发同步加载
                if (!level.hasChunk(chunkX, chunkZ)) {
                    continue;
                }

                LevelChunk chunk = level.getChunk(chunkX, chunkZ);

                // 路径一：区块里的方块实体（容器几乎都在这里）
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    BlockPos pos = be.getBlockPos();
                    if (!inCube(pos, center, radius)) {
                        continue;
                    }
                    if (isContainer(be)) {
                        // 方块实体的坐标本身就是独立对象，但仍然统一 immutable()，
                        // 避免以后有人改成别处传进来的共享坐标时踩坑
                        found.add(pos.immutable());
                    }
                }

                // 路径二：白名单方块（矿石、远古残骸这类没有方块实体，只能靠 palette 找）
                if (hasWhitelist) {
                    chunk.findBlocks(
                            state -> BlockFilter.matches(state),
                            (pos, state) -> {
                                // findBlocks 回调里的 pos 是复用的可变对象，必须取副本
                                if (inCube(pos, center, radius)) {
                                    found.add(pos.immutable());
                                }
                            });
                }
            }
        }

        return new ArrayList<>(found);
    }

    /**
     * 判断一个坐标是否落在以 center 为中心、radius 为半径的<b>立方体</b>内。
     *
     * <p>注意这里刻意用立方体而不是球体 —— 因为旧的逐坐标实现用的是
     * {@code BlockPos.betweenClosed}，那是个立方体。用球体的话结果会少掉八个角的方块，
     * 那就不是"重构"而是"改行为"了。想改成球体可以，但应该作为独立的产品决定。
     */
    private static boolean inCube(BlockPos pos, BlockPos center, int radius) {
        return Math.abs(pos.getX() - center.getX()) <= radius
                && Math.abs(pos.getY() - center.getY()) <= radius
                && Math.abs(pos.getZ() - center.getZ()) <= radius;
    }

    /**
     * 旧实现：逐个坐标遍历。<b>只作为重构后的对照基准保留</b>，正式代码不再调用。
     *
     * <p>{@code /cr compare} 指令会同时跑新旧两个实现并比对结果，用来证明重构没有改变行为。
     * 验证通过后可以整段删除。
     *
     * @deprecated 已被 {@link #findTargets} 取代
     */
    @Deprecated
    public static List<BlockPos> findTargetsBruteForce(Level level, BlockPos center, int radius) {

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

            // 【第二步】判断这个坐标算不算「目标」。两个条件满足其一即可：
            //   1. 它是容器（有物品存储能力，或实现了原版 Container 接口）
            //   2. 玩家把它加进了自定义白名单（远古残骸、刷怪笼这类不是容器的方块）
            //
            // 第二个条件写成 !isEmpty() && matches(...) 是<有意的短路优化>：
            // 白名单为空时（大多数玩家的情况）连 getBlockState 都不会调用，等于零成本。
            // 如果改成 matches(...) 内部判空，每个坐标都要先查一次方块状态 —— 半径 16 就是白扫 3.6 万次。
            boolean whitelisted = !BlockFilter.isEmpty() && BlockFilter.matches(level.getBlockState(pos));

            if (isContainer(be) || whitelisted) {

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
