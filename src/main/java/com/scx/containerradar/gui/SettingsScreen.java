package com.scx.containerradar.gui;

import com.scx.containerradar.config.Settings;
import com.scx.containerradar.filter.BlockFilter;
import com.scx.containerradar.render.ContainerRenderer;
import com.scx.containerradar.search.BlockIndex;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * 设置界面：开关 + 半径 + 方块搜索 + 白名单管理。
 *
 * <h2>布局为什么全是手算坐标</h2>
 * 原版 GUI 没有布局系统（没有 flex、没有约束），所有位置都是像素坐标。
 * 能依靠的只有 {@code this.width} / {@code this.height}，所以这里用
 * "左右留边距 + 剩余宽度均分"的方式摆放，窗口大小变化时会自适应。
 *
 * <h2>两个列表的滚动是自己写的</h2>
 * Minecraft 没有通用的滚动列表控件：
 * <ul>
 *   <li>{@code ObjectSelectionList} 是给"选一个选项"设计的，样式（始终居中、带选中态）
 *       改不成普通列表</li>
 *   <li>所以这里用最朴素的做法：维护一个 {@code scroll} 偏移，只渲染可见的若干行，
 *       用 {@link GuiGraphics#enableScissor} 把超出的部分裁掉</li>
 * </ul>
 * 裁剪这一步不能省 —— 否则滚到边界时，列表内容会画到面板外面去。
 *
 * <h2>为什么搜索框每次输入都重新搜索</h2>
 * {@link BlockIndex} 已经把名称算好缓存了，一次搜索只是几万次字符串比较（2~5 毫秒），
 * 所以不需要防抖。加防抖反而会让输入感觉"慢半拍"。
 */
public class SettingsScreen extends Screen {

    /** 左右留白。 */
    private static final int MARGIN = 16;

    /** 列表每行高度。字体高度是 9，留 3 像素间距。 */
    private static final int ROW_HEIGHT = 12;

    /** 控件之间的间隙。 */
    private static final int GAP = 4;

    /** 搜索结果显示上限。再多玩家也看不完，反而拖慢渲染。 */
    private static final int MAX_RESULTS = 100;

    private static final int WIDGET_HEIGHT = 20;

    // ---------------- 状态 ----------------

    private EditBox searchBox;

    /** 当前搜索词。空字符串表示没有搜索。 */
    private String query = "";

    /** 当前搜索结果。 */
    private List<BlockIndex.Entry> results = List.of();

    /** 当前白名单快照（每次增删后刷新，避免每次渲染都去查）。 */
    private List<ResourceLocation> whitelist = List.of();

    /** 两个列表各自的滚动偏移（单位：行）。 */
    private int resultScroll;
    private int whitelistScroll;

    // ---------------- 布局（init 里算好，render 里复用） ----------------

    private int resultsX;
    private int whitelistX;
    private int listTop;
    private int listWidth;
    private int listHeight;

    public SettingsScreen() {
        super(Component.literal("容器雷达设置"));
    }

    @Override
    protected void init() {
        // 在这里主动建索引：代价落在"打开界面"这一下，而不是敲第一个字的时候
        BlockIndex.ensureBuilt();
        this.whitelist = new ArrayList<>(BlockFilter.ids());

        int contentWidth = this.width - MARGIN * 2;
        int buttonWidth = (contentWidth - GAP * 2) / 3;
        int y = 24;

        // 三个开关横排。按下后立刻刷新自己的文字，让玩家看到状态变化
        this.addRenderableWidget(Button.builder(masterLabel(), button -> {
            Settings.toggleEnabled();
            button.setMessage(masterLabel());
            ContainerRenderer.requestRescan();
        }).bounds(MARGIN, y, buttonWidth, WIDGET_HEIGHT).build());

        this.addRenderableWidget(Button.builder(containersLabel(), button -> {
            Settings.setShowContainers(!Settings.showContainers());
            button.setMessage(containersLabel());
            ContainerRenderer.requestRescan();
        }).bounds(MARGIN + buttonWidth + GAP, y, buttonWidth, WIDGET_HEIGHT).build());

        this.addRenderableWidget(Button.builder(whitelistLabel(), button -> {
            Settings.setShowWhitelist(!Settings.showWhitelist());
            button.setMessage(whitelistLabel());
            ContainerRenderer.requestRescan();
        }).bounds(MARGIN + (buttonWidth + GAP) * 2, y, buttonWidth, WIDGET_HEIGHT).build());
        y += WIDGET_HEIGHT + GAP;

        // 半径滑块占满整行
        this.addRenderableWidget(new RadiusSlider(MARGIN, y, contentWidth, WIDGET_HEIGHT));
        y += WIDGET_HEIGHT + GAP + 2;

        // 搜索框。用 suggestion 做占位提示，比单独画一行标签省空间
        this.searchBox = new EditBox(this.font, MARGIN, y, contentWidth, WIDGET_HEIGHT,
                Component.literal("搜索方块"));
        this.searchBox.setSuggestion("输入 ID 或名称搜索，例如 钻石 / diamond");
        this.searchBox.setMaxLength(64);
        this.searchBox.setResponder(this::onQueryChanged);
        this.addRenderableWidget(this.searchBox);
        y += WIDGET_HEIGHT + GAP + 2;

        // 两个面板并排：左边搜索结果，右边当前白名单
        this.listTop = y;
        this.listHeight = this.height - y - 32;
        this.listWidth = (contentWidth - GAP) / 2;
        this.resultsX = MARGIN;
        this.whitelistX = MARGIN + this.listWidth + GAP;

        this.addRenderableWidget(Button.builder(Component.literal("完成"), button -> this.onClose())
                .bounds(this.width / 2 - 60, this.height - 26, 120, WIDGET_HEIGHT).build());
    }

    /** 输入变化时重新搜索。索引已缓存，几万次字符串比较足够快，不需要防抖。 */
    private void onQueryChanged(String text) {
        this.query = text;
        this.results = BlockIndex.search(text, MAX_RESULTS).entries();
        this.resultScroll = 0;
    }

    private Component masterLabel() {
        return Component.literal("全部高亮：" + (Settings.enabled() ? "开" : "关"));
    }

    private Component containersLabel() {
        return Component.literal("显示容器：" + (Settings.showContainers() ? "开" : "关"));
    }

    private Component whitelistLabel() {
        return Component.literal("显示白名单：" + (Settings.showWhitelist() ? "开" : "关"));
    }

    // ==================================================================
    // 渲染
    // ==================================================================

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 顺序：背景 → 控件（super.render 负责）→ 面板 → 列表内容 → 文字
        this.renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawCenteredString(this.font, this.title, this.width / 2, 8, 0xFFFFFF);

        drawPanel(graphics, this.resultsX, "搜索结果 " + this.results.size(), this.query.isEmpty());
        drawPanel(graphics, this.whitelistX, "白名单 " + this.whitelist.size(), this.whitelist.isEmpty());

        drawResults(graphics, mouseX, mouseY);
        drawWhitelist(graphics, mouseX, mouseY);
    }

    /**
     * 画面板背景、边框和标题。
     *
     * @param showHint 面板为空时是否显示操作提示
     */
    private void drawPanel(GuiGraphics graphics, int x, String title, boolean showHint) {
        int bottom = this.listTop + this.listHeight;
        graphics.fill(x, this.listTop, x + this.listWidth, bottom, 0x66000000);
        graphics.drawString(this.font, title, x + 4, this.listTop + 3, 0xFFD700);

        if (showHint) {
            graphics.drawString(this.font, hintText(x), x + 4, this.listTop + 17, 0x888888);
        }
    }

    private String hintText(int panelX) {
        return panelX == this.resultsX ? "在上方输入关键词" : "搜索后点击左侧结果添加";
    }

    /** 一屏能显示多少行（减去标题占的那一行）。 */
    private int visibleRows() {
        return Math.max(1, (this.listHeight - 16) / ROW_HEIGHT);
    }

    private void drawResults(GuiGraphics graphics, int mouseX, int mouseY) {
        if (this.results.isEmpty()) {
            return;
        }
        int rows = visibleRows();
        clampScroll();

        // 裁剪：不裁的话滚到边界时内容会画到面板外面
        graphics.enableScissor(this.resultsX + 1, this.listTop + 14,
                this.resultsX + this.listWidth - 1, this.listTop + this.listHeight - 1);

        for (int i = 0; i < rows; i++) {
            int index = this.resultScroll + i;
            if (index >= this.results.size()) {
                break;
            }
            BlockIndex.Entry entry = this.results.get(index);
            int rowY = rowTop(i);

            if (isHoveringRow(mouseX, mouseY, this.resultsX, rowY)) {
                graphics.fill(this.resultsX + 1, rowY, this.resultsX + this.listWidth - 1,
                        rowY + ROW_HEIGHT, 0x55FFFFFF);
            }

            // 已加入白名单的用灰色，避免重复添加
            boolean added = this.whitelist.contains(entry.id());
            int color = added ? 0x888888 : 0xFFFFFF;
            String text = trim(entry.name() + "  " + entry.id(), this.listWidth - 8);
            graphics.drawString(this.font, text, this.resultsX + 4, rowY + 2, color);
        }

        graphics.disableScissor();
    }

    private void drawWhitelist(GuiGraphics graphics, int mouseX, int mouseY) {
        if (this.whitelist.isEmpty()) {
            return;
        }
        int rows = visibleRows();
        clampScroll();

        graphics.enableScissor(this.whitelistX + 1, this.listTop + 14,
                this.whitelistX + this.listWidth - 1, this.listTop + this.listHeight - 1);

        for (int i = 0; i < rows; i++) {
            int index = this.whitelistScroll + i;
            if (index >= this.whitelist.size()) {
                break;
            }
            ResourceLocation id = this.whitelist.get(index);
            int rowY = rowTop(i);

            if (isHoveringRow(mouseX, mouseY, this.whitelistX, rowY)) {
                // 悬停变红，暗示"点一下会删掉"
                graphics.fill(this.whitelistX + 1, rowY, this.whitelistX + this.listWidth - 1,
                        rowY + ROW_HEIGHT, 0x66AA0000);
            }

            String text = trim(id + "  [移除]", this.listWidth - 8);
            graphics.drawString(this.font, text, this.whitelistX + 4, rowY + 2, 0xFFFFFF);
        }

        graphics.disableScissor();
    }

    private int rowTop(int visibleIndex) {
        return this.listTop + 16 + visibleIndex * ROW_HEIGHT;
    }

    private boolean isHoveringRow(double mouseX, double mouseY, int panelX, int rowY) {
        return mouseX >= panelX && mouseX < panelX + this.listWidth
                && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;
    }

    /** 按像素宽度截断并加省略号，避免长 ID 顶出面板。 */
    private String trim(String text, int maxWidth) {
        if (this.font.width(text) <= maxWidth) {
            return text;
        }
        return this.font.plainSubstrByWidth(text, Math.max(0, maxWidth - 6)) + "…";
    }

    /** 把两个列表的滚动偏移都夹到合法范围，防止越界。 */
    private void clampScroll() {
        int rows = visibleRows();
        this.resultScroll = clamp(this.resultScroll, 0, Math.max(0, this.results.size() - rows));
        this.whitelistScroll = clamp(this.whitelistScroll, 0, Math.max(0, this.whitelist.size() - rows));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    // ==================================================================
    // 交互
    // ==================================================================

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 先让原版控件（按钮、滑块、输入框）处理。它们接管了这次点击就直接返回，
        // 否则点按钮时列表也会跟着响应，一次点击触发两件事。
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (button != 0) {
            return false;
        }

        // 点搜索结果 → 加入白名单
        int resultIndex = rowIndexAt(mouseX, mouseY, this.resultsX, this.results.size(), this.resultScroll);
        if (resultIndex >= 0) {
            BlockFilter.add(this.results.get(resultIndex).id());
            this.whitelist = new ArrayList<>(BlockFilter.ids());
            ContainerRenderer.requestRescan();
            return true;
        }

        // 点白名单条目 → 移除
        int whiteIndex = rowIndexAt(mouseX, mouseY, this.whitelistX, this.whitelist.size(), this.whitelistScroll);
        if (whiteIndex >= 0) {
            BlockFilter.remove(this.whitelist.get(whiteIndex));
            this.whitelist = new ArrayList<>(BlockFilter.ids());
            // 移除后列表变短，滚动位置可能越界，立刻夹一次
            clampScroll();
            ContainerRenderer.requestRescan();
            return true;
        }

        return false;
    }

    /**
     * 判断点击落在某个列表的第几行。
     *
     * @return 该行在数据里的真实下标；没点中任何行返回 -1
     */
    private int rowIndexAt(double mouseX, double mouseY, int panelX, int itemCount, int scroll) {
        if (mouseX < panelX || mouseX >= panelX + this.listWidth) {
            return -1;
        }
        int relativeY = (int) mouseY - this.listTop - 16;
        if (relativeY < 0) {
            return -1;
        }
        int visibleRow = relativeY / ROW_HEIGHT;
        if (visibleRow >= visibleRows()) {
            return -1;
        }
        int index = scroll + visibleRow;
        return index < itemCount ? index : -1;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int rows = visibleRows();
        int step = (int) Math.signum(delta);

        if (mouseX >= this.resultsX && mouseX < this.resultsX + this.listWidth) {
            this.resultScroll = clamp(this.resultScroll - step, 0,
                    Math.max(0, this.results.size() - rows));
            return true;
        }
        if (mouseX >= this.whitelistX && mouseX < this.whitelistX + this.listWidth) {
            this.whitelistScroll = clamp(this.whitelistScroll - step, 0,
                    Math.max(0, this.whitelist.size() - rows));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public void onClose() {
        ContainerRenderer.requestRescan();
        super.onClose();
    }

    // ==================================================================
    // 半径滑块
    // ==================================================================

    /**
     * 原版只提供 {@link AbstractSliderButton}：一个 0~1 之间的 {@code value}，
     * 加上两个必须自己实现的抽象方法 —— {@code applyValue()}（值变了做什么）
     * 和 {@code updateMessage()}（文案怎么变）。所以"8~64 格"的映射要自己做。
     */
    private static class RadiusSlider extends AbstractSliderButton {

        RadiusSlider(int x, int y, int width, int height) {
            super(x, y, width, height, Component.empty(), toRatio(Settings.radius()));
            this.updateMessage();
        }

        private static double toRatio(int radius) {
            return (radius - Settings.MIN_RADIUS) / (double) (Settings.MAX_RADIUS - Settings.MIN_RADIUS);
        }

        private int currentRadius() {
            return Settings.MIN_RADIUS + (int) Math.round(this.value * (Settings.MAX_RADIUS - Settings.MIN_RADIUS));
        }

        @Override
        protected void updateMessage() {
            this.setMessage(Component.literal("扫描半径：" + currentRadius() + " 格"));
        }

        @Override
        protected void applyValue() {
            Settings.setRadius(currentRadius());
        }
    }
}
