package io.github.tofithepuppycat.temporalindustries.client.screen;

import org.jetbrains.annotations.NotNull;

import io.github.tofithepuppycat.temporalindustries.client.ChronosphereClientState;
import io.github.tofithepuppycat.temporalindustries.block.entity.ChronosphereBlockEntity;
import io.github.tofithepuppycat.temporalindustries.menu.ChronosphereMenu;
import io.github.tofithepuppycat.temporalindustries.network.ChronosphereJumpPacket;
import io.github.tofithepuppycat.temporalindustries.network.ChronosphereStateRequestPacket;
import io.github.tofithepuppycat.temporalindustries.network.ChronosphereToggleChunkPacket;
import io.github.tofithepuppycat.temporalindustries.network.UpdateChronosphereTimePacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.network.PacketDistributor;

/** GUI for the Chronosphere block: a 5x5 map of chunks around it to claim/release, plus a shared
 * time scrubber and Jump button that moves every claimed chunk together, paid from one energy pool. */
@SuppressWarnings("null")
public class ChronosphereScreen extends AbstractContainerScreen<ChronosphereMenu> {
    private static final int RADIUS = ChronosphereBlockEntity.MAX_RADIUS;
    private static final int GRID_SIZE = RADIUS * 2 + 1;
    private static final int CELL_SIZE = 20;
    private static final int CELL_GAP = 2;
    private static final int GRID_PIXELS = GRID_SIZE * CELL_SIZE + (GRID_SIZE - 1) * CELL_GAP;

    private static final int ENERGY_BAR_WIDTH = 160;
    private static final int ENERGY_BAR_HEIGHT = 8;
    private static final int SCRUBBER_WIDTH = 176;
    private static final int SCRUBBER_HEIGHT = 10;

    private static final int SYNC_INTERVAL_TICKS = 20;

    private static final int COLOR_HOME       = 0xFFFFD166;
    private static final int COLOR_SELECTED   = 0xFF66C7FF;
    private static final int COLOR_BLOCKED    = 0xFF8A3A3A;
    private static final int COLOR_AVAILABLE  = 0xFF3A3A3A;
    private static final int COLOR_BORDER     = 0xFF000000;

    private Button jumpButton;
    private int ticksSinceSync = 0;
    private boolean draggingScrubber = false;
    private long previewSelectedGameTime;

    private int gridX;
    private int gridY;
    private int energyBarX;
    private int energyBarY;
    private int scrubberX;
    private int scrubberY;

    public ChronosphereScreen(ChronosphereMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        imageWidth = 200;
        imageHeight = 246;
        inventoryLabelY = imageHeight + 100; // Push off-screen to hide inventory
    }

    @Override
    protected void init() {
        super.init();

        gridX = leftPos + (imageWidth - GRID_PIXELS) / 2;
        gridY = topPos + 22;
        energyBarX = leftPos + (imageWidth - ENERGY_BAR_WIDTH) / 2;
        energyBarY = gridY + GRID_PIXELS + 10;
        scrubberX = leftPos + (imageWidth - SCRUBBER_WIDTH) / 2;
        scrubberY = energyBarY + 28;

        previewSelectedGameTime = menu.getSelectedGameTime();

        int buttonWidth = 100;
        jumpButton = Button.builder(Component.translatable("gui.temporalindustries.time_machine.jump"), btn -> jumpAndClose())
                .pos(leftPos + (imageWidth - buttonWidth) / 2, scrubberY + 56)
                .size(buttonWidth, 20)
                .build();
        addRenderableWidget(jumpButton);

        ChronosphereClientState.setActiveMachine(menu.getBlockPos());
        PacketDistributor.sendToServer(new ChronosphereStateRequestPacket(menu.getBlockPos()));
    }

    @Override
    protected void containerTick() {
        super.containerTick();

        ticksSinceSync++;
        if (ticksSinceSync >= SYNC_INTERVAL_TICKS) {
            ticksSinceSync = 0;
            PacketDistributor.sendToServer(new ChronosphereStateRequestPacket(menu.getBlockPos()));
        }
    }

    private void jumpAndClose() {
        PacketDistributor.sendToServer(new ChronosphereJumpPacket(menu.getBlockPos(), previewSelectedGameTime));
        if (minecraft != null && minecraft.player != null) {
            minecraft.player.closeContainer();
        }
    }

    @Override
    protected void renderBg(@NotNull GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        guiGraphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xE0101010);
        guiGraphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + 1, 0xFF555555);

        renderGrid(guiGraphics);
        renderEnergyBar(guiGraphics);
        renderScrubber(guiGraphics);
    }

    private void renderGrid(GuiGraphics guiGraphics) {
        ChunkPos home = new ChunkPos(menu.getBlockPos());

        for (int row = 0; row < GRID_SIZE; row++) {
            for (int col = 0; col < GRID_SIZE; col++) {
                int dx = col - RADIUS;
                int dz = row - RADIUS;
                int cellX = gridX + col * (CELL_SIZE + CELL_GAP);
                int cellY = gridY + row * (CELL_SIZE + CELL_GAP);

                int color;
                if (dx == 0 && dz == 0) {
                    color = COLOR_HOME;
                } else {
                    long key = new ChunkPos(home.x + dx, home.z + dz).toLong();
                    if (ChronosphereClientState.isSelected(key)) {
                        color = COLOR_SELECTED;
                    } else if (ChronosphereClientState.isBlocked(key)) {
                        color = COLOR_BLOCKED;
                    } else {
                        color = COLOR_AVAILABLE;
                    }
                }

                guiGraphics.fill(cellX, cellY, cellX + CELL_SIZE, cellY + CELL_SIZE, COLOR_BORDER);
                guiGraphics.fill(cellX + 1, cellY + 1, cellX + CELL_SIZE - 1, cellY + CELL_SIZE - 1, color);
            }
        }
    }

    private void renderEnergyBar(GuiGraphics guiGraphics) {
        int energyStored = menu.getEnergyStored();
        int energyCapacity = menu.getEnergyCapacity();

        guiGraphics.fill(energyBarX, energyBarY, energyBarX + ENERGY_BAR_WIDTH, energyBarY + ENERGY_BAR_HEIGHT, 0xFF000000);
        if (energyCapacity > 0 && energyStored > 0) {
            int filled = Math.max(1, Math.round((energyStored / (float) energyCapacity) * (ENERGY_BAR_WIDTH - 2)));
            guiGraphics.fill(energyBarX + 1, energyBarY + 1, energyBarX + 1 + filled, energyBarY + ENERGY_BAR_HEIGHT - 1, 0xFF4DD0E1);
        }
    }

    private void renderScrubber(GuiGraphics guiGraphics) {
        guiGraphics.fill(scrubberX, scrubberY, scrubberX + SCRUBBER_WIDTH, scrubberY + SCRUBBER_HEIGHT, 0xFF000000);

        long placed = menu.getPlacedGameTime();
        long current = getCurrentGameTime();
        long range = Math.max(1L, current - placed);
        double fraction = Math.max(0.0D, Math.min(1.0D, (previewSelectedGameTime - placed) / (double) range));
        int handleX = scrubberX + (int) Math.round(fraction * (SCRUBBER_WIDTH - 4));

        guiGraphics.fill(scrubberX + 1, scrubberY + 1, scrubberX + SCRUBBER_WIDTH - 1, scrubberY + SCRUBBER_HEIGHT - 1, 0xFF2A2A2A);
        guiGraphics.fill(handleX, scrubberY, handleX + 4, scrubberY + SCRUBBER_HEIGHT, 0xFFFFFFFF);
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        guiGraphics.drawString(font, Component.translatable("block.temporalindustries.chronosphere"), leftPos + 8, topPos + 8, 0xFFFFFF, false);

        int labelY = scrubberY + 16;
        guiGraphics.drawString(font, Component.literal(
                menu.getBlockEntity().getChunkCount() + " / " + (GRID_SIZE * GRID_SIZE) + " chunks claimed"),
                leftPos + 8, labelY, 0xFFBFBFBF, false);
        labelY += 10;

        guiGraphics.drawString(font, Component.translatable("gui.temporalindustries.time_machine.preview_current", formatGameDayTime(getCurrentGameTime())),
                leftPos + 8, labelY, 0xFFFFFF, false);
        labelY += 10;

        long diff = previewSelectedGameTime - getCurrentGameTime();
        String direction = diff <= 0L
                ? "gui.temporalindustries.time_machine.preview_past"
                : "gui.temporalindustries.time_machine.preview_future";
        guiGraphics.drawString(font, Component.translatable(direction, formatSincePlaced(Math.abs(diff))), leftPos + 8, labelY, 0xFFFFFF, false);
        labelY += 10;

        guiGraphics.drawString(font, Component.literal("Jump cost: " + ChronosphereClientState.getTotalJumpCost() + " FE"),
                leftPos + 8, labelY, 0xFFBFBFBF, false);
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        renderBg(guiGraphics, partialTick, mouseX, mouseY);
        renderLabels(guiGraphics, mouseX, mouseY);
        for (var widget : renderables) {
            widget.render(guiGraphics, mouseX, mouseY, partialTick);
        }

        if (isMouseOverEnergyBar(mouseX, mouseY)) {
            guiGraphics.renderTooltip(font, Component.literal(menu.getEnergyStored() + " / " + menu.getEnergyCapacity() + " FE"), mouseX, mouseY);
        }
    }

    private boolean isMouseOverEnergyBar(int mouseX, int mouseY) {
        return mouseX >= energyBarX && mouseX <= energyBarX + ENERGY_BAR_WIDTH
                && mouseY >= energyBarY && mouseY <= energyBarY + ENERGY_BAR_HEIGHT;
    }

    private boolean isMouseOverScrubber(double mouseX, double mouseY) {
        return mouseX >= scrubberX && mouseX <= scrubberX + SCRUBBER_WIDTH
                && mouseY >= scrubberY && mouseY <= scrubberY + SCRUBBER_HEIGHT;
    }

    private void setPreviewFromMouseX(double mouseX) {
        double fraction = Math.max(0.0D, Math.min(1.0D, (mouseX - scrubberX) / (double) SCRUBBER_WIDTH));
        long placed = menu.getPlacedGameTime();
        long current = getCurrentGameTime();
        previewSelectedGameTime = placed + Math.round(fraction * (current - placed));
    }

    private long getCurrentGameTime() {
        if (minecraft == null || minecraft.level == null) {
            return 0L;
        }
        return minecraft.level.getGameTime();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            if (isMouseOverScrubber(mouseX, mouseY)) {
                draggingScrubber = true;
                setPreviewFromMouseX(mouseX);
                PacketDistributor.sendToServer(new UpdateChronosphereTimePacket(menu.getBlockPos(), previewSelectedGameTime));
                return true;
            }

            ChunkPos clicked = getGridCellAt(mouseX, mouseY);
            if (clicked != null && !clicked.equals(new ChunkPos(menu.getBlockPos()))) {
                long key = clicked.toLong();
                boolean add = !ChronosphereClientState.isSelected(key);
                if (!(add && ChronosphereClientState.isBlocked(key))) {
                    PacketDistributor.sendToServer(new ChronosphereToggleChunkPacket(menu.getBlockPos(), key, add));
                }
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingScrubber) {
            setPreviewFromMouseX(mouseX);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && draggingScrubber) {
            draggingScrubber = false;
            PacketDistributor.sendToServer(new UpdateChronosphereTimePacket(menu.getBlockPos(), previewSelectedGameTime));
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private ChunkPos getGridCellAt(double mouseX, double mouseY) {
        if (mouseX < gridX || mouseY < gridY || mouseX >= gridX + GRID_PIXELS || mouseY >= gridY + GRID_PIXELS) {
            return null;
        }
        int col = (int) ((mouseX - gridX) / (CELL_SIZE + CELL_GAP));
        int row = (int) ((mouseY - gridY) / (CELL_SIZE + CELL_GAP));
        double cellLocalX = (mouseX - gridX) - col * (CELL_SIZE + CELL_GAP);
        double cellLocalY = (mouseY - gridY) - row * (CELL_SIZE + CELL_GAP);
        if (cellLocalX > CELL_SIZE || cellLocalY > CELL_SIZE) return null; // clicked in the gap
        if (col < 0 || col >= GRID_SIZE || row < 0 || row >= GRID_SIZE) return null;

        ChunkPos home = new ChunkPos(menu.getBlockPos());
        return new ChunkPos(home.x + (col - RADIUS), home.z + (row - RADIUS));
    }

    /** Formats an absolute game time as "Day {day} | {HH:MM}". Day 1 starts at game time 0; a
     * Minecraft day is 24000 ticks, and tick 0 within a day is 06:00. */
    private static String formatGameDayTime(long gameTime) {
        long day = Math.floorDiv(gameTime, 24000L) + 1L;
        long dayTicks = Math.floorMod(gameTime, 24000L);
        long hour = ((dayTicks / 1000L) + 6L) % 24L;
        long minute = (dayTicks % 1000L) * 60L / 1000L;
        return String.format("Day %d | %02d:%02d", day, hour, minute);
    }

    private static String formatSincePlaced(long ticks) {
        long totalSeconds = Math.max(0L, ticks) / 20L;
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;

        if (hours > 0L) {
            return hours + "h " + minutes + "m";
        }
        if (minutes > 0L) {
            return minutes + "m " + seconds + "s";
        }
        return seconds + "s";
    }
}
