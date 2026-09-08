package io.github.tofithepuppycat.temporalindustries.client.screen;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.block.entity.LootGeneratorBlockEntity;
import io.github.tofithepuppycat.temporalindustries.client.IconButtonRenderer;
import io.github.tofithepuppycat.temporalindustries.client.LootTableSuggestionsClientState;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyDisplay;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
import io.github.tofithepuppycat.temporalindustries.menu.LootGeneratorMenu;
import io.github.tofithepuppycat.temporalindustries.network.LootGeneratorSetLuckPacket;
import io.github.tofithepuppycat.temporalindustries.network.LootGeneratorSetTablePacket;
import io.github.tofithepuppycat.temporalindustries.network.LootGeneratorStopPacket;
import io.github.tofithepuppycat.temporalindustries.network.LootGeneratorToggleRepeatPacket;
import io.github.tofithepuppycat.temporalindustries.network.LootGeneratorTriggerRollPacket;
import io.github.tofithepuppycat.temporalindustries.network.LootTableSuggestionsRequestPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Textured GUI for the Loot Generator: a loot table text field (tinted to show server-validated
 * state) with a vertical Chaos tank bar alongside it, a luck slider, a roll-progress bar, and
 * play/stop and single/repeat icon buttons next to the cosmetic roll-preview slot. */
@SuppressWarnings("null")
public class LootGeneratorScreen extends AbstractContainerScreen<LootGeneratorMenu> {
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
            TemporalIndustries.MODID, "textures/gui/loot_generator.png");
    private static final ResourceLocation ICON_DIE = ResourceLocation.fromNamespaceAndPath(
            TemporalIndustries.MODID, "textures/gui/icon_die.png");
    private static final ResourceLocation ICON_STOP = ResourceLocation.fromNamespaceAndPath(
            TemporalIndustries.MODID, "textures/gui/icon_stop.png");
    private static final ResourceLocation ICON_ARROW_RIGHT = ResourceLocation.fromNamespaceAndPath(
            TemporalIndustries.MODID, "textures/gui/icon_arrow_right.png");
    private static final ResourceLocation ICON_LOOP = ResourceLocation.fromNamespaceAndPath(
            TemporalIndustries.MODID, "textures/gui/icon_loop.png");

    private static final int IMAGE_WIDTH = 176;
    private static final int IMAGE_HEIGHT = 216;

    // Rows top to bottom: search field (with the Chaos bar alongside it), luck slider, progress
    // bar, then the play/mode icons.
    private static final int FIELD_X = 8;
    private static final int FIELD_Y = 16;
    private static final int FIELD_HEIGHT = 12;
    // Usable header width between the left inset and the panel's right edge (leftPos + 168).
    private static final int CONTENT_WIDTH = 160;

    private static final int CHAOS_BAR_WIDTH = 10;
    // Flush against the field's right edge, shortening the field to make room for the bar.
    private static final int CHAOS_BAR_X = FIELD_X + CONTENT_WIDTH - CHAOS_BAR_WIDTH;
    private static final int CHAOS_BAR_Y = FIELD_Y;
    private static final int FIELD_WIDTH = CHAOS_BAR_X - FIELD_X - 2;

    private static final int LUCK_SLIDER_X = FIELD_X;
    private static final int LUCK_SLIDER_Y = FIELD_Y + FIELD_HEIGHT + 2;
    private static final int LUCK_SLIDER_WIDTH = FIELD_WIDTH;
    private static final int LUCK_SLIDER_HEIGHT = 8;

    private static final int PROGRESS_BAR_X = FIELD_X;
    private static final int PROGRESS_BAR_Y = LUCK_SLIDER_Y + LUCK_SLIDER_HEIGHT + 2;
    private static final int PROGRESS_BAR_WIDTH = FIELD_WIDTH;
    private static final int PROGRESS_BAR_HEIGHT = 6;

    // Spans the field, luck slider, and progress bar rows.
    private static final int CHAOS_BAR_HEIGHT = PROGRESS_BAR_Y + PROGRESS_BAR_HEIGHT - CHAOS_BAR_Y;

    private static final int ICON_SIZE = IconButtonRenderer.SIZE;
    private static final int BUTTONS_Y = PROGRESS_BAR_Y + PROGRESS_BAR_HEIGHT + 3;

    // Right-aligned on the buttons row, beside the cosmetic roll-preview slot.
    private static final int ROLL_ICON_SIZE = 16;
    private static final int ROLL_ICON_X = FIELD_X + CONTENT_WIDTH - ROLL_ICON_SIZE;
    private static final int ROLL_ICON_Y = BUTTONS_Y;

    private static final int MODE_ICON_X = ROLL_ICON_X - ICON_SIZE - 3;
    private static final int MODE_ICON_Y = BUTTONS_Y;
    private static final int PLAY_ICON_X = MODE_ICON_X - ICON_SIZE - 2;
    private static final int PLAY_ICON_Y = BUTTONS_Y;

    // Within this many ticks of maxProgress, the spin locks onto the item about to be placed.
    private static final int ROLL_LOCK_TICKS = 4;
    // Cycle speed slows down past the halfway point, for a deceleration effect.
    private static final int ROLL_SPIN_TICKS_FAST = 3;
    private static final int ROLL_SPIN_TICKS_SLOW = 6;

    // Total matches gathered; only VISIBLE_SUGGESTIONS shown at once, rest reachable by scrolling.
    private static final int MAX_SUGGESTIONS = 50;
    private static final int VISIBLE_SUGGESTIONS = 4;
    private static final int SUGGESTION_ROW_HEIGHT = 10;
    // Wider than the panel itself so loot table ids aren't cut off; overhangs both edges.
    private static final int SUGGESTIONS_WIDTH = 240;

    private EditBox lootTableField;
    private String lastSentText = "";
    private int lastSentLuck;
    private LuckSlider luckSlider;

    // Tab-completion cycles through matches for the text present before the first Tab press.
    private String tabCycleBase = null;
    private int tabCycleIndex = -1;
    private boolean applyingTabCompletion = false;

    // First suggestion row currently drawn, when more matches exist than fit in the dropdown.
    private int suggestionScrollOffset = 0;

    // Drives the roll-animation's icon cycling; not synced, not persisted.
    private int rollAnimTick = 0;

    public LootGeneratorScreen(LootGeneratorMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        imageWidth = IMAGE_WIDTH;
        imageHeight = IMAGE_HEIGHT;
        inventoryLabelY = 124; // just above the player inventory grid, which starts at y=126
    }

    @Override
    protected void init() {
        super.init();

        ResourceLocation selected = menu.getSelectedLootTable();
        String initialText = selected == null ? "" : selected.toString();
        lastSentText = initialText;

        lootTableField = new EditBox(font, leftPos + FIELD_X, topPos + FIELD_Y, FIELD_WIDTH, FIELD_HEIGHT,
                Component.translatable("gui.temporalindustries.loot_generator.table_field"));
        lootTableField.setMaxLength(256);
        lootTableField.setValue(initialText);
        lootTableField.setHint(Component.translatable("gui.temporalindustries.loot_generator.table_hint"));
        lootTableField.setResponder(text -> {
            if (!applyingTabCompletion) resetTabCycle();
        });
        addRenderableWidget(lootTableField);

        lastSentLuck = menu.getLuck();
        luckSlider = new LuckSlider(leftPos + LUCK_SLIDER_X, topPos + LUCK_SLIDER_Y,
                LUCK_SLIDER_WIDTH, LUCK_SLIDER_HEIGHT, menu.getLuck());
        addRenderableWidget(luckSlider);

        PacketDistributor.sendToServer(LootTableSuggestionsRequestPacket.INSTANCE);
    }

    // AbstractContainerScreen#mouseDragged never forwards to child widgets; without this override,
    // dragging the luck slider would only jump to the initial click position.
    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (getFocused() == luckSlider && isDragging()) {
            return luckSlider.mouseDragged(mouseX, mouseY, button, dragX, dragY);
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        rollAnimTick++;
        // Keep the slider in sync with the server value except while the player has it focused,
        // so we don't fight their input.
        if (luckSlider != null && !luckSlider.isFocused()) {
            luckSlider.syncFromServer(menu.getLuck());
        }
    }

    /** Drags to pick a luck level (0-{@link LootGeneratorBlockEntity#MAX_LUCK}) fed into the loot
     * roll's luck parameter, at a Chaos surcharge that scales with the setting. Only sends a packet
     * when the discrete luck level actually changes. */
    private class LuckSlider extends AbstractSliderButton {
        LuckSlider(int x, int y, int width, int height, int initialLuck) {
            super(x, y, width, height, Component.empty(), initialLuck / (double) LootGeneratorBlockEntity.MAX_LUCK);
            updateMessage();
        }

        private int luckFromValue() {
            return (int) Math.round(value * LootGeneratorBlockEntity.MAX_LUCK);
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.translatable("gui.temporalindustries.loot_generator.luck",
                    luckFromValue(), LootGeneratorBlockEntity.MAX_LUCK));
        }

        @Override
        protected void applyValue() {
            int luck = luckFromValue();
            if (luck == lastSentLuck) return;
            lastSentLuck = luck;
            PacketDistributor.sendToServer(new LootGeneratorSetLuckPacket(menu.getBlockPos(), luck));
        }

        /** Pulls the slider's handle to match a server value without re-sending it via {@link #applyValue}. */
        void syncFromServer(int luck) {
            if (luck == lastSentLuck) return;
            lastSentLuck = luck;
            value = luck / (double) LootGeneratorBlockEntity.MAX_LUCK;
            updateMessage();
        }
    }

    private void resetTabCycle() {
        tabCycleBase = null;
        tabCycleIndex = -1;
        suggestionScrollOffset = 0;
    }

    /** Loot table ids matching {@code text}, ranked prefix-matches-first, capped to
     * {@link #MAX_SUGGESTIONS}. */
    private List<String> matchingSuggestions(String text) {
        String needle = text.trim().toLowerCase(java.util.Locale.ROOT);
        if (needle.isEmpty()) return List.of();

        List<String> prefixMatches = new ArrayList<>();
        List<String> containsMatches = new ArrayList<>();
        for (ResourceLocation id : LootTableSuggestionsClientState.getLootTables()) {
            String value = id.toString();
            String lower = value.toLowerCase(java.util.Locale.ROOT);
            if (lower.equals(needle)) continue;
            if (lower.startsWith(needle)) prefixMatches.add(value);
            else if (lower.contains(needle)) containsMatches.add(value);
            if (prefixMatches.size() >= MAX_SUGGESTIONS) break;
        }
        prefixMatches.sort(String::compareTo);
        containsMatches.sort(String::compareTo);

        List<String> result = new ArrayList<>(prefixMatches);
        for (String match : containsMatches) {
            if (result.size() >= MAX_SUGGESTIONS) break;
            result.add(match);
        }
        if (result.size() > MAX_SUGGESTIONS) result = result.subList(0, MAX_SUGGESTIONS);
        return result;
    }

    /** Tab/Shift+Tab: cycles the field's text through matches for the text present before cycling started. */
    private boolean tabComplete(boolean reverse) {
        if (tabCycleBase == null) tabCycleBase = lootTableField.getValue();

        List<String> matches = matchingSuggestions(tabCycleBase);
        if (matches.isEmpty()) return false;

        tabCycleIndex = tabCycleIndex < 0
                ? (reverse ? matches.size() - 1 : 0)
                : Math.floorMod(tabCycleIndex + (reverse ? -1 : 1), matches.size());
        if (tabCycleIndex < suggestionScrollOffset) {
            suggestionScrollOffset = tabCycleIndex;
        } else if (tabCycleIndex >= suggestionScrollOffset + VISIBLE_SUGGESTIONS) {
            suggestionScrollOffset = tabCycleIndex - VISIBLE_SUGGESTIONS + 1;
        }

        applyingTabCompletion = true;
        lootTableField.setValue(matches.get(tabCycleIndex));
        applyingTabCompletion = false;
        lootTableField.moveCursorToEnd(false);
        return true;
    }

    private void sendTableUpdateIfChanged() {
        String text = lootTableField.getValue();
        if (Objects.equals(text, lastSentText)) return;
        lastSentText = text;
        PacketDistributor.sendToServer(new LootGeneratorSetTablePacket(menu.getBlockPos(), text));
    }

    /** Play/stop icon: starts generation (single roll or repeating chain) if idle, otherwise halts it. */
    private void togglePlayStop() {
        if (menu.isRunning()) {
            PacketDistributor.sendToServer(new LootGeneratorStopPacket(menu.getBlockPos()));
        } else {
            sendTableUpdateIfChanged();
            PacketDistributor.sendToServer(new LootGeneratorTriggerRollPacket(menu.getBlockPos()));
        }
    }

    /** Single/repeat icon: flips the mode for the next generation; doesn't start or stop anything itself. */
    private void toggleRepeatMode() {
        PacketDistributor.sendToServer(new LootGeneratorToggleRepeatPacket(menu.getBlockPos()));
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // EditBox#keyPressed returns false for plain letters; without swallowing them via
        // canConsumeInput, AbstractContainerScreen would close the screen on keys like "e".
        if (lootTableField.isFocused() && (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)) {
            sendTableUpdateIfChanged();
            return true;
        }
        // Tab would otherwise move widget focus; claim it here to autocomplete the loot table id instead.
        if (lootTableField.isFocused() && keyCode == GLFW.GLFW_KEY_TAB) {
            tabComplete((modifiers & GLFW.GLFW_MOD_SHIFT) != 0);
            return true;
        }
        boolean fieldHandled = lootTableField.keyPressed(keyCode, scanCode, modifiers);
        return fieldHandled || lootTableField.canConsumeInput() || super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void removed() {
        super.removed();
        sendTableUpdateIfChanged();
    }

    @Override
    protected void renderBg(@NotNull GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        guiGraphics.blit(TEXTURE, leftPos, topPos, 0, 0, IMAGE_WIDTH, IMAGE_HEIGHT);

        lootTableField.setTextColor(menu.isSelectionValid() ? 0xFFFFFFFF : 0xFFFF5555);

        renderControlIcons(guiGraphics);
        renderChaosBar(guiGraphics);
        renderProgressBar(guiGraphics);
        renderRollAnimation(guiGraphics);
    }

    /** Play/stop and single/repeat icon buttons, drawn as icon buttons rather than vanilla Buttons. */
    private void renderControlIcons(GuiGraphics guiGraphics) {
        int playX = leftPos + PLAY_ICON_X;
        int modeX = leftPos + MODE_ICON_X;
        int y = topPos + PLAY_ICON_Y;

        IconButtonRenderer.renderBackground(guiGraphics, playX, y, 0);
        IconButtonRenderer.renderIcon(guiGraphics, menu.isRunning() ? ICON_STOP : ICON_DIE, playX, y);

        IconButtonRenderer.renderBackground(guiGraphics, modeX, y, 0);
        IconButtonRenderer.renderIcon(guiGraphics, menu.isRepeatMode() ? ICON_LOOP : ICON_ARROW_RIGHT, modeX, y);
    }

    private void renderChaosBar(GuiGraphics guiGraphics) {
        int x = leftPos + CHAOS_BAR_X;
        int y = topPos + CHAOS_BAR_Y;
        guiGraphics.fill(x, y, x + CHAOS_BAR_WIDTH, y + CHAOS_BAR_HEIGHT, 0xFF000000);
        FluidBarRenderer.renderVertical(guiGraphics, x + 1, y + 1, CHAOS_BAR_WIDTH - 2, CHAOS_BAR_HEIGHT - 2,
                menu.getChaosFluidAmount(), menu.getChaosTankCapacity(), Registration.CHAOS_FLUID_TYPE.get(), EntropyType.CHAOS.color());
    }

    private void renderProgressBar(GuiGraphics guiGraphics) {
        int x = leftPos + PROGRESS_BAR_X;
        int y = topPos + PROGRESS_BAR_Y;
        guiGraphics.fill(x, y, x + PROGRESS_BAR_WIDTH, y + PROGRESS_BAR_HEIGHT, 0xFF000000);

        int progress = menu.getProgress();
        int maxProgress = menu.getMaxProgress();
        if (progress <= 0 || maxProgress <= 0) return;

        int filled = Math.max(1, Math.round((progress / (float) maxProgress) * (PROGRESS_BAR_WIDTH - 2)));
        filled = Math.min(PROGRESS_BAR_WIDTH - 2, filled);
        guiGraphics.fill(x + 1, y + 1, x + 1 + filled, y + PROGRESS_BAR_HEIGHT - 1, 0xFF55FF55);
    }

    /** While a roll is in progress, spins the icon through the possible-items sample, slowing down
     * past the halfway mark and locking onto the actual item for the last {@link #ROLL_LOCK_TICKS} ticks. */
    private void renderRollAnimation(GuiGraphics guiGraphics) {
        ItemStack display = currentRollDisplayStack();
        if (display.isEmpty()) return;

        int x = leftPos + ROLL_ICON_X;
        int y = topPos + ROLL_ICON_Y - 1;
        guiGraphics.renderItem(display, x, y);
    }

    /** Empty if no roll is in progress; otherwise the stack the animation should show this frame. */
    private ItemStack currentRollDisplayStack() {
        ItemStack next = menu.getNextRollItem();
        if (next.isEmpty()) return ItemStack.EMPTY;

        int progress = menu.getProgress();
        int maxProgress = menu.getMaxProgress();
        if (maxProgress <= 0 || progress >= maxProgress - ROLL_LOCK_TICKS) return next;

        List<ItemStack> possible = menu.getPossibleItems();
        if (possible.isEmpty()) return next;

        int spinTicks = progress > maxProgress / 2 ? ROLL_SPIN_TICKS_SLOW : ROLL_SPIN_TICKS_FAST;
        int index = (rollAnimTick / spinTicks) % possible.size();
        return possible.get(index);
    }

    @Override
    protected void renderLabels(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY) {
        Component machineTitle = Component.translatable("block.temporalindustries.loot_generator");
        guiGraphics.drawString(font, machineTitle, (imageWidth - font.width(machineTitle)) / 2, 6, 0xFF3F3F3F, false);
        guiGraphics.drawString(font, "Output", 8, 58, 0xFF3F3F3F, false);
        guiGraphics.drawString(font, playerInventoryTitle, 8, inventoryLabelY, 0xFF3F3F3F, false);
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // The suggestions dropdown covers the luck slider's row when shown; skip drawing the
        // slider rather than fighting draw order to paint over its queued text.
        boolean suggestionsShown = lootTableField.isFocused() && !matchingSuggestions(lootTableField.getValue()).isEmpty();
        luckSlider.visible = !suggestionsShown;

        renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        renderSuggestions(guiGraphics, mouseX, mouseY);
        renderTooltip(guiGraphics, mouseX, mouseY);

        if (isOver(mouseX, mouseY, leftPos + PLAY_ICON_X, topPos + PLAY_ICON_Y, ICON_SIZE, ICON_SIZE)) {
            guiGraphics.renderTooltip(font, Component.translatable(menu.isRunning()
                    ? "gui.temporalindustries.loot_generator.stop"
                    : "gui.temporalindustries.loot_generator.generate"), mouseX, mouseY);
        }
        if (isOver(mouseX, mouseY, leftPos + MODE_ICON_X, topPos + MODE_ICON_Y, ICON_SIZE, ICON_SIZE)) {
            guiGraphics.renderTooltip(font, Component.translatable(menu.isRepeatMode()
                    ? "gui.temporalindustries.loot_generator.mode_repeat"
                    : "gui.temporalindustries.loot_generator.mode_single"), mouseX, mouseY);
        }
        if (isOver(mouseX, mouseY, leftPos + CHAOS_BAR_X, topPos + CHAOS_BAR_Y, CHAOS_BAR_WIDTH, CHAOS_BAR_HEIGHT)) {
            guiGraphics.renderTooltip(font, fluidTooltip(menu.getChaosFluidAmount(), menu.getChaosTankCapacity(), EntropyType.CHAOS), mouseX, mouseY);
        }
        if (isOver(mouseX, mouseY, leftPos + ROLL_ICON_X, topPos + ROLL_ICON_Y, ROLL_ICON_SIZE, ROLL_ICON_SIZE)) {
            renderRollIconTooltip(guiGraphics, mouseX, mouseY);
        }
        if (isOver(mouseX, mouseY, leftPos + LUCK_SLIDER_X, topPos + LUCK_SLIDER_Y, LUCK_SLIDER_WIDTH, LUCK_SLIDER_HEIGHT)) {
            String luckCostText = Component.translatable("gui.temporalindustries.loot_generator.luck_cost",
                    menu.getRollCost(), menu.getItemCost()).getString();
            guiGraphics.renderTooltip(font, EntropyDisplay.colorTokens(luckCostText, ChatFormatting.WHITE), mouseX, mouseY);
        }
    }

    /** The real item's tooltip once landed; a "still deciding" placeholder while still cycling. */
    private void renderRollIconTooltip(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        ItemStack next = menu.getNextRollItem();
        if (next.isEmpty()) return;

        int progress = menu.getProgress();
        int maxProgress = menu.getMaxProgress();
        boolean landed = maxProgress > 0 && progress >= maxProgress - ROLL_LOCK_TICKS;
        if (landed) {
            guiGraphics.renderTooltip(font, next, mouseX, mouseY);
        } else {
            guiGraphics.renderTooltip(font, Component.translatable("gui.temporalindustries.loot_generator.deciding"), mouseX, mouseY);
        }
    }

    /** Clamps {@link #suggestionScrollOffset} so its window of {@link #VISIBLE_SUGGESTIONS} rows stays within {@code matchCount}. */
    private void clampSuggestionScroll(int matchCount) {
        int maxOffset = Math.max(0, matchCount - VISIBLE_SUGGESTIONS);
        suggestionScrollOffset = Math.max(0, Math.min(suggestionScrollOffset, maxOffset));
    }

    /** Dropdown of matching loot table ids under the search field while it holds partial text.
     * Centered under the field/panel, and scrollable when there are more matches than fit. */
    private void renderSuggestions(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        if (!lootTableField.isFocused()) return;
        List<String> matches = matchingSuggestions(lootTableField.getValue());
        if (matches.isEmpty()) return;
        clampSuggestionScroll(matches.size());

        int x = leftPos + (IMAGE_WIDTH - SUGGESTIONS_WIDTH) / 2;
        int y = topPos + FIELD_Y + FIELD_HEIGHT;
        int visibleCount = Math.min(VISIBLE_SUGGESTIONS, matches.size());
        int height = visibleCount * SUGGESTION_ROW_HEIGHT;

        // Flush text queued by earlier widgets first, or it would show through our opaque background.
        guiGraphics.flush();
        guiGraphics.fill(x, y, x + SUGGESTIONS_WIDTH, y + height, 0xF0000000);

        for (int row = 0; row < visibleCount; row++) {
            int i = suggestionScrollOffset + row;
            int rowY = y + row * SUGGESTION_ROW_HEIGHT;
            boolean hovered = isOver(mouseX, mouseY, x, rowY, SUGGESTIONS_WIDTH, SUGGESTION_ROW_HEIGHT);
            boolean tabSelected = i == tabCycleIndex && tabCycleBase != null;
            if (hovered || tabSelected) {
                guiGraphics.fill(x, rowY, x + SUGGESTIONS_WIDTH, rowY + SUGGESTION_ROW_HEIGHT, 0xFF5555FF);
            }
            guiGraphics.drawString(font, trimToWidth(matches.get(i), SUGGESTIONS_WIDTH - 4), x + 2, rowY + 1, 0xFFFFFFFF, false);
        }
        guiGraphics.flush();
    }

    private String trimToWidth(String text, int maxWidth) {
        if (font.width(text) <= maxWidth) return text;
        return font.plainSubstrByWidth(text, maxWidth - font.width("...")) + "...";
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            if (isOver((int) mouseX, (int) mouseY, leftPos + PLAY_ICON_X, topPos + PLAY_ICON_Y, ICON_SIZE, ICON_SIZE)) {
                togglePlayStop();
                return true;
            }
            if (isOver((int) mouseX, (int) mouseY, leftPos + MODE_ICON_X, topPos + MODE_ICON_Y, ICON_SIZE, ICON_SIZE)) {
                toggleRepeatMode();
                return true;
            }
        }
        if (lootTableField.isFocused()) {
            List<String> matches = matchingSuggestions(lootTableField.getValue());
            clampSuggestionScroll(matches.size());
            int x = leftPos + (IMAGE_WIDTH - SUGGESTIONS_WIDTH) / 2;
            int y = topPos + FIELD_Y + FIELD_HEIGHT;
            int visibleCount = Math.min(VISIBLE_SUGGESTIONS, matches.size());
            for (int row = 0; row < visibleCount; row++) {
                int rowY = y + row * SUGGESTION_ROW_HEIGHT;
                if (isOver((int) mouseX, (int) mouseY, x, rowY, SUGGESTIONS_WIDTH, SUGGESTION_ROW_HEIGHT)) {
                    applyingTabCompletion = true;
                    lootTableField.setValue(matches.get(suggestionScrollOffset + row));
                    applyingTabCompletion = false;
                    lootTableField.moveCursorToEnd(false);
                    resetTabCycle();
                    return true;
                }
            }
            // Clicking outside the field unfocuses it; otherwise there'd be no way to click away.
            if (!lootTableField.isMouseOver(mouseX, mouseY)) {
                lootTableField.setFocused(false);
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (lootTableField.isFocused()) {
            List<String> matches = matchingSuggestions(lootTableField.getValue());
            int x = leftPos + (IMAGE_WIDTH - SUGGESTIONS_WIDTH) / 2;
            int y = topPos + FIELD_Y + FIELD_HEIGHT;
            int visibleCount = Math.min(VISIBLE_SUGGESTIONS, matches.size());
            int height = visibleCount * SUGGESTION_ROW_HEIGHT;
            if (!matches.isEmpty() && isOver((int) mouseX, (int) mouseY, x, y, SUGGESTIONS_WIDTH, height)) {
                suggestionScrollOffset -= (int) Math.signum(scrollY);
                clampSuggestionScroll(matches.size());
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private boolean isOver(int mouseX, int mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    private static Component fluidTooltip(int amount, int capacity, EntropyType type) {
        return Component.literal(EntropyDisplay.formatFluid(amount) + "/" + EntropyDisplay.formatFluid(capacity))
                .append(EntropyDisplay.unit(type));
    }
}
