package com.scx.containerradar.gui;

import com.scx.containerradar.config.Settings;
import com.scx.containerradar.render.ContainerRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 设置界面：总开关、两个分类开关、扫描半径。
 *
 * <h2>为什么第一版不做白名单列表</h2>
 * Minecraft 的 GUI 没有现成的滚动列表控件：
 * <ul>
 *   <li>{@code ObjectSelectionList} 是为"选一个选项"设计的，样式很难改成普通列表</li>
 *   <li>自己写滚动要处理：可见范围计算、滚轮事件、拖动滚动条、内容超界裁剪</li>
 * </ul>
 * 这些加起来比"开关 + 滑块"麻烦得多。而白名单的增删已经有两个入口（快捷键 K/L、指令），
 * 不缺这一条路。所以第一版只做真正需要"看着调"的东西 —— 半径和开关。
 *
 * <h2>布局为什么全是手算坐标</h2>
 * 原版 GUI 没有布局系统（没有 flex、没有约束），所有位置都是像素坐标。
 * 唯一能依靠的是 {@code this.width} / {@code this.height}（窗口尺寸），
 * 所以这里用"屏幕中心 ± 固定宽度"的方式摆放，窗口大小变化时会跟着居中。
 *
 * <p>注意：这个类只在客户端加载 —— 它被 {@code KeyBindings}（服务端永不加载）引用，
 * 服务端不会碰到 {@code net.minecraft.client.gui} 这些类。
 */
public class SettingsScreen extends Screen {

    /** 控件宽度。200 在 1080p 下看着舒服，窗口再小也不会挤。 */
    private static final int WIDGET_WIDTH = 200;

    /** 控件高度，原版按钮的标准高度。 */
    private static final int WIDGET_HEIGHT = 20;

    /** 行间距：控件高度 + 4 像素空隙，保证不粘连。 */
    private static final int ROW_GAP = WIDGET_HEIGHT + 4;

    public SettingsScreen() {
        super(Component.literal("容器雷达设置"));
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        // 整块内容垂直居中：先估算总高度，再从中间往上挪一半
        int startY = this.height / 2 - (ROW_GAP * 5) / 2;

        int x = centerX - WIDGET_WIDTH / 2;
        int y = startY;

        // 总开关。按下后立刻改写自己的文字，让玩家看到状态变化 ——
        // 如果只改设置不刷新按钮，玩家会觉得"点了没反应"
        this.addRenderableWidget(Button.builder(
                        masterLabel(),
                        button -> {
                            Settings.toggleEnabled();
                            button.setMessage(masterLabel());
                        })
                .bounds(x, y, WIDGET_WIDTH, WIDGET_HEIGHT)
                .build());
        y += ROW_GAP;

        this.addRenderableWidget(Button.builder(
                        containersLabel(),
                        button -> {
                            Settings.setShowContainers(!Settings.showContainers());
                            button.setMessage(containersLabel());
                        })
                .bounds(x, y, WIDGET_WIDTH, WIDGET_HEIGHT)
                .build());
        y += ROW_GAP;

        this.addRenderableWidget(Button.builder(
                        whitelistLabel(),
                        button -> {
                            Settings.setShowWhitelist(!Settings.showWhitelist());
                            button.setMessage(whitelistLabel());
                        })
                .bounds(x, y, WIDGET_WIDTH, WIDGET_HEIGHT)
                .build());
        y += ROW_GAP;

        this.addRenderableWidget(new RadiusSlider(x, y, WIDGET_WIDTH, WIDGET_HEIGHT));
        y += ROW_GAP + 6;

        // 完成按钮窄一点，和上面的整行控件区分开
        this.addRenderableWidget(Button.builder(
                        Component.literal("完成"),
                        button -> this.onClose())
                .bounds(centerX - 60, y, 120, WIDGET_HEIGHT)
                .build());
    }

    // 三个按钮的文字都从 Settings 现读，保证显示的是真实状态而不是缓存

    private Component masterLabel() {
        return Component.literal("全部高亮：" + (Settings.enabled() ? "开" : "关"));
    }

    private Component containersLabel() {
        return Component.literal("显示容器：" + (Settings.showContainers() ? "开" : "关"));
    }

    private Component whitelistLabel() {
        return Component.literal("显示白名单方块：" + (Settings.showWhitelist() ? "开" : "关"));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 先铺背景（半透明黑），再让 Screen 画所有控件，最后画文字。
        // 顺序反了的话控件会被背景盖住 —— 这是新手最容易踩的坑。
        this.renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawCenteredString(this.font, this.title, this.width / 2, 24, 0xFFFFFF);
        graphics.drawCenteredString(this.font,
                Component.literal("Esc 或 O 关闭"), this.width / 2, this.height - 32, 0xAAAAAA);
    }

    /**
     * 关闭时请求重扫，让刚改的设置当帧就生效。
     *
     * <p>不这么做的话，改完半径最多要等 1 秒（渲染器的扫描节流）才看到变化，
     * 玩家会以为没生效。
     */
    @Override
    public void onClose() {
        ContainerRenderer.requestRescan();
        super.onClose();
    }

    /**
     * 半径滑块。
     *
     * <p>原版只提供 {@link AbstractSliderButton}：一个 0~1 之间的 {@code value}，
     * 加上两个必须自己实现的抽象方法 —— {@code applyValue()}（值变了要做什么）
     * 和 {@code updateMessage()}（显示的文案怎么变）。所以"8~64 格"这个映射要自己做。
     */
    private static class RadiusSlider extends AbstractSliderButton {

        RadiusSlider(int x, int y, int width, int height) {
            // 父类要求传初始值，约定是 0~1 的比例，所以先把当前半径换算成比例
            super(x, y, width, height, Component.empty(), toRatio(Settings.radius()));
            // 父类构造器不会调 updateMessage，必须自己补一次，否则一开始显示空文字
            this.updateMessage();
        }

        private static double toRatio(int radius) {
            return (radius - Settings.MIN_RADIUS) / (double) (Settings.MAX_RADIUS - Settings.MIN_RADIUS);
        }

        /** 把 0~1 的比例换算回整数半径。四舍五入，避免拖动时"跳格"。 */
        private int currentRadius() {
            return Settings.MIN_RADIUS + (int) Math.round(this.value * (Settings.MAX_RADIUS - Settings.MIN_RADIUS));
        }

        @Override
        protected void updateMessage() {
            this.setMessage(Component.literal("扫描半径：" + currentRadius() + " 格"));
        }

        @Override
        protected void applyValue() {
            // 每次拖动都会调用，内部会写文件 —— 写频率很高但文件很小（几百字节），
            // 而且只在玩家拖动时触发，不影响游戏帧率
            Settings.setRadius(currentRadius());
        }
    }
}
