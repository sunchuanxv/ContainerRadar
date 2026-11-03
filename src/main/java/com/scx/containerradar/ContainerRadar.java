package com.scx.containerradar;

import com.mojang.logging.LogUtils;
import com.scx.containerradar.command.ScanCommand;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;


// The value here should match an entry in the META-INF/mods.toml file
@Mod(ContainerRadar.MODID)
public class ContainerRadar
{
    // Define mod id in a common place for everything to reference
    public static final String MODID = "containerradar";

    /**
     * 单帧最多绘制多少个目标的线框。
     *
     * <p>这个上限是必需的，不是保守估计。玩家可以把「草方块」这类到处都是的方块加进白名单，
     * 半径 64 时会扫到一万六千多个 —— 每个框 12 条棱、渲染两遍，就是四十万条线段，
     * 帧率会直接崩掉。
     *
     * <p>超限时按距离排序，只绘制最近的若干个：玩家最关心的是"眼前这些"，
     * 远处的少画几个几乎察觉不到，但帧率能稳住。
     *
     * <p>放在主类里是为了让客户端（渲染器）和服务端（指令）都能引用，
     * 而不用让服务端去加载客户端类。
     */
    public static final int MAX_RENDERED = 400;
    // Directly reference a slf4j logger
    private static final Logger LOGGER = LogUtils.getLogger();


    public ContainerRadar(FMLJavaModLoadingContext context)
    {
        // 把自己注册到 Forge 事件总线：主类里所有 @SubscribeEvent 方法（比如 onRegisterCommands）
        // 都靠这一句才能收到事件。mod 事件总线（context.getModEventBus()）目前还没用到 ——
        // 它用于注册方块/物品/按键这类"加载期"事件，等真需要时再加。
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        ScanCommand.register(event.getDispatcher());
    }

    // You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent
    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents
    {
        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event)
        {
            // 留一行启动日志：出问题时能在 latest.log 里确认模组确实加载了
            LOGGER.info("[ContainerRadar] 客户端初始化完成");
        }

        /**
         * 注册自定义快捷键。
         *
         * <p>必须挂在这里（mod 事件总线 + 客户端），不能在 Forge 总线上注册按键 ——
         * 挂错位置的话按键不会出现在游戏的"按键绑定"设置里，也永远不会触发。
         */
        @SubscribeEvent
        public static void onRegisterKeyMappings(net.minecraftforge.client.event.RegisterKeyMappingsEvent event)
        {
            com.scx.containerradar.key.KeyBindings.register(event);
        }
    }
}
