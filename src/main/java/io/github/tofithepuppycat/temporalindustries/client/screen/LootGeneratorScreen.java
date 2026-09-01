package io.github.tofithepuppycat.temporalindustries.client.screen;

import io.github.tofithepuppycat.temporalindustries.Registration;
import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.block.entity.LootGeneratorBlockEntity;
import io.github.tofithepuppycat.temporalindustries.client.LootTableSuggestionsClientState;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyDisplay;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyType;
import io.github.tofithepuppycat.temporalindustries.menu.LootGeneratorMenu;
import io.github.tofithepuppycat.temporalindustries.network.LootGeneratorSetTablePacket;
import io.github.tofithepuppycat.temporalindustries.network.LootGeneratorTriggerRollPacket;
import io.github.tofithepuppycat.temporalindustries.network.LootTableSuggestionsRequestPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
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
 * state), a Generate button, a Chaos tank bar, and a roll-progress bar all in the header above the
 * chest slots - see {@code textures/gui/loot_generator.png} for the panel art (chest slots start at
 * 8,68; player inventory at 8,134; header controls at 8,8). */
@SuppressWarnings("null")
public class LootGeneratorScreen extends AbstractContainerScreen<LootGeneratorMenu> {
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
            TemporalIndustries.MODID, "textures/gui/loot_generator.png");

    private static final int IMAGE_WIDTH = 176;
    private static final int IMAGE_HEIGHT = 216;

    // Header starts below the machine title (drawn at y=6, ~9px tall) rather than right against it.
    private static final int FIELD_X = 8;
    private static final int FIELD_Y = 18;
    private static final int FIELD_WIDTH = 160;
    private static final int FIELD_HEIGHT = 12;

    private static final int BUTTON_X = 8;
    private static final int BUTTON_Y = 32;
    private static final int BUTTON_WIDTH = 70;
    private static final int BUTTON_HEIGHT = 12;

    private static final int CHAOS_BAR_X = 84;
    private static final int CHAOS_BAR_Y = 32;
    private static final int CHAOS_BAR_WIDTH = 84;
    private static final int CHAOS_BAR_HEIGHT = 12;

    private static final int PROGRESS_BAR_X = 8;
    private static final int PROGRESS_BAR_Y = 46;
    private static final int PROGRESS_BAR_WIDTH = 142;
    private static final int PROGRESS_BAR_HEIGHT = 6;

    // Icon sits right of the progress bar, sharing its right edge with the field/chaos-bar above
    // (leftPos + 168).
    private static final int ROLL_ICON_X = 152;
    private static final int ROLL_ICON_Y = 47;
    private static final int ROLL_ICON_SIZE = 16;

    // Once progress is within this many ticks of maxProgress, the spin locks onto the item that's
    // actually about to be placed instead of still cycling through the possible-items sample.
    private static final int ROLL_LOCK_TICKS = 4;
    // Cycle speed (client ticks per icon) slows down once past the halfway point, for a rough
    // deceleration into the landed item rather than an abrupt stop.
    private static final int ROLL_SPIN_TICKS_FAST = 3;
    private static final int ROLL_SPIN_TICKS_SLOW = 6;

    private static final int MAX_SUGGESTIONS = 4;
    private static final int SUGGESTION_ROW_HEIGHT = 10;

    private EditBox lootTableField;
    private String lastSentText = "";

    // Tab-completion cycles through the matches for whatever text was in the field before the first
    // Tab press in a run, rather than re-filtering against its own output on every subsequent press.
    private String tabCycleBase = null;
    private int tabCycleIndex = -1;
    private boolean applyingTabCompletion = false;

    // Advances every client tick the screen is open, purely to drive the roll-animation's icon
    // cycling - not synced, not persisted.
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

        addRenderableWidget(Button.builder(Component.translatable("gui.temporalindustries.loot_generator.generate"),
                        btn -> sendGenerate())
                .bounds(leftPos + BUTTON_X, topPos + BUTTON_Y, BUTTON_WIDTH, BUTTON_HEIGHT)
                .build());

        PacketDistributor.sendToServer(LootTableSuggestionsRequestPacket.INSTANCE);
    }

    private void resetTabCycle() {
        tabCycleBase = null;
        tabCycleIndex = -1;
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

    /** Tab/Shift+Tab: cycles the field's text through the matches for the text that was present
     * before cycling started, so repeated presses step through candidates instead of re-filtering
     * against whatever the previous press just inserted. */
    private boolean tabComplete(boolean reverse) {
        if (tabCycleBase == null) tabCycleBase = lootTableField.getValue();

        List<String> matches = matchingSuggestions(tabCycleBase);
        if (matches.isEmpty()) return false;

        tabCycleIndex = tabCycleIndex < 0
                ? (reverse ? matches.size() - 1 : 0)
                : Math.floorMod(tabCycleIndex + (reverse ? -1 : 1), matches.size());

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

    private void sendGenerate() {
        sendTableUpdateIfChanged();
        PacketDistributor.sendToServer(new LootGeneratorTriggerRollPacket(menu.getBlockPos()));
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // EditBox#keyPressed only consumes special keys (backspace, arrows, ctrl-combos) - plain
        // letters are inserted via charTyped instead, so it returns false for them. If we only acted
        // on that return value, AbstractContainerScreen#keyPressed would still fall through to its
        // own key-inventory close-check and hotbar-swap check on every ordinary letter, closing the
        // screen the moment you type e.g. "e" (the default inventory key) or a digit. AnvilScreen's
        // rename field avoids this the same way: swallow the whole keystroke whenever the field is
        // actively focused (EditBox#canConsumeInput), not just when it reports having handled it.
        if (lootTableField.isFocused() && (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)) {
            sendTableUpdateIfChanged();
            return true;
        }
        // Tab would otherwise move widget focus (Screen#keyPressed's changeFocus handling) - claim
        // it here first so it autocompletes the loot table id instead.
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
    protected void containerTick() {
        super.containerTick();
        rollAnimTick++;
    }

    @Override
    protected void renderBg(@NotNull GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        guiGraphics.blit(TEXTURE, leftPos, topPos, 0, 0, IMAGE_WIDTH, IMAGE_HEIGHT);

        lootTableField.setTextColor(menu.isSelectionValid() ? 0xFFFFFFFF : 0xFFFF5555);

        renderChaosBar(guiGraphics);
        renderProgressBar(guiGraphics);
        renderRollAnimation(guiGraphics);
    }

    private void renderChaosBar(GuiGraphics guiGraphics) {
        int x = leftPos + CHAOS_BAR_X;
        int y = topPos + CHAOS_BAR_Y;
        guiGraphics.fill(x, y, x + CHAOS_BAR_WIDTH, y + CHAOS_BAR_HEIGHT, 0xFF000000);
        FluidBarRenderer.renderHorizontal(guiGraphics, x + 1, y + 1, CHAOS_BAR_WIDTH - 2, CHAOS_BAR_HEIGHT - 2,
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

    /** While a roll is in progress, spins the icon through the possible-items sample rolled
     * server-side, slowing down past the halfway mark and locking onto the item that's actually
     * about to be placed for the last {@link #ROLL_LOCK_TICKS} ticks - a slot-machine "deciding the
     * loot" effect that resolves right as the progress bar fills. */
    private void renderRollAnimation(GuiGraphics guiGraphics) {
        ItemStack display = currentRollDisplayStack();
        if (display.isEmpty()) return;

        int x = leftPos + ROLL_ICON_X;
        int y = topPos + ROLL_ICON_Y;
        guiGraphics.renderItem(display, x, y);
    }

    /** Empty if no roll is in progress; otherwise whichever stack the animation should show this
     * frame (spinning sample item, or the real upcoming item once landed). */
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
        guiGraphics.drawString(font, playerInventoryTitle, 8, inventoryLabelY, 0xFF3F3F3F, false);
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        renderSuggestions(guiGraphics, mouseX, mouseY);
        renderTooltip(guiGraphics, mouseX, mouseY);

        if (isOver(mouseX, mouseY, leftPos + CHAOS_BAR_X, topPos + CHAOS_BAR_Y, CHAOS_BAR_WIDTH, CHAOS_BAR_HEIGHT)) {
            guiGraphics.renderTooltip(font, fluidTooltip(menu.getChaosFluidAmount(), menu.getChaosTankCapacity(), EntropyType.CHAOS), mouseX, mouseY);
        }
        if (isOver(mouseX, mouseY, leftPos + ROLL_ICON_X, topPos + ROLL_ICON_Y, ROLL_ICON_SIZE, ROLL_ICON_SIZE)) {
            renderRollIconTooltip(guiGraphics, mouseX, mouseY);
        }
    }

    /** The real item's tooltip once the spin has landed on it; a "still deciding" placeholder while
     * it's still cycling through the sample, so the tooltip doesn't spoil the result early. */
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

    /** Dropdown of matching loot table ids under the search field, drawn on top of everything else
     * while the field is focused and holds partial text (hidden once the text already exactly
     * matches a table, or once tab-cycling has landed on one - see {@link #matchingSuggestions}). */
    private void renderSuggestions(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        if (!lootTableField.isFocused()) return;
        List<String> matches = matchingSuggestions(lootTableField.getValue());
        if (matches.isEmpty()) return;

        int x = leftPos + FIELD_X;
        int y = topPos + FIELD_Y + FIELD_HEIGHT;
        int height = matches.size() * SUGGESTION_ROW_HEIGHT;
        guiGraphics.fill(x, y, x + FIELD_WIDTH, y + height, 0xF0000000);

        for (int i = 0; i < matches.size(); i++) {
            int rowY = y + i * SUGGESTION_ROW_HEIGHT;
            boolean hovered = isOver(mouseX, mouseY, x, rowY, FIELD_WIDTH, SUGGESTION_ROW_HEIGHT);
            boolean tabSelected = i == tabCycleIndex && tabCycleBase != null;
            if (hovered || tabSelected) {
                guiGraphics.fill(x, rowY, x + FIELD_WIDTH, rowY + SUGGESTION_ROW_HEIGHT, 0xFF5555FF);
            }
            guiGraphics.drawString(font, trimToWidth(matches.get(i), FIELD_WIDTH - 4), x + 2, rowY + 1, 0xFFFFFFFF, false);
        }
    }

    private String trimToWidth(String text, int maxWidth) {
        if (font.width(text) <= maxWidth) return text;
        return font.plainSubstrByWidth(text, maxWidth - font.width("...")) + "...";
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (lootTableField.isFocused()) {
            List<String> matches = matchingSuggestions(lootTableField.getValue());
            int x = leftPos + FIELD_X;
            int y = topPos + FIELD_Y + FIELD_HEIGHT;
            for (int i = 0; i < matches.size(); i++) {
                int rowY = y + i * SUGGESTION_ROW_HEIGHT;
                if (isOver((int) mouseX, (int) mouseY, x, rowY, FIELD_WIDTH, SUGGESTION_ROW_HEIGHT)) {
                    applyingTabCompletion = true;
                    lootTableField.setValue(matches.get(i));
                    applyingTabCompletion = false;
                    lootTableField.moveCursorToEnd(false);
                    resetTabCycle();
                    return true;
                }
            }
            // Clicking anywhere outside the field (slots, buttons, empty panel) unfocuses it -
            // otherwise, once focused, there was no way to click away from it.
            if (!lootTableField.isMouseOver(mouseX, mouseY)) {
                lootTableField.setFocused(false);
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private boolean isOver(int mouseX, int mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    private static Component fluidTooltip(int amount, int capacity, EntropyType type) {
        return Component.literal(EntropyDisplay.formatFluid(amount) + "/" + EntropyDisplay.formatFluid(capacity))
                .append(EntropyDisplay.unit(type));
    }
}
