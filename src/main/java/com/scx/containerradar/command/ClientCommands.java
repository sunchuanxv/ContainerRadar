package com.scx.containerradar.command;

import com.scx.containerradar.ContainerRadar;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 把 {@code /cr} 注册为<b>客户端指令</b>。
 *
 * <h2>为什么用这个事件而不是 {@code RegisterCommandsEvent}</h2>
 * <ul>
 *   <li>{@code RegisterCommandsEvent} 是<b>服务端</b>事件。指令跑在服务端，
 *       改的是服务端的白名单和配置，而画面渲染读的是客户端的 —— 在别人的服务器上
 *       会出现"提示已加入但画面毫无变化"，`/cr search` 还会因为服务端没有语言文件而搜不到中文</li>
 *   <li>{@code RegisterClientCommandsEvent} 是<b>客户端</b>事件。指令在本地执行，
 *       改的就是渲染用的那份状态，立即生效，而且服务端完全不需要装这个模组</li>
 * </ul>
 *
 * <p>这个类必须挂在 <b>Forge 事件总线 + 客户端</b>：mod 事件总线（{@code Bus.MOD}）
 * 收不到这个事件。挂错总线的表现是"指令完全不存在"，且没有任何报错。
 *
 * <p>配合 {@code mods.toml} 里的 {@code clientSideOnly=true}，专用服务器会直接跳过加载本模组 ——
 * 所以这个类永远不会在服务端被实例化，里面引用客户端指令体系是安全的。
 */
@Mod.EventBusSubscriber(
        modid = ContainerRadar.MODID,
        bus = Mod.EventBusSubscriber.Bus.FORGE,
        value = Dist.CLIENT)
public final class ClientCommands {

    private ClientCommands() {
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        ScanCommand.register(event.getDispatcher());
    }
}
