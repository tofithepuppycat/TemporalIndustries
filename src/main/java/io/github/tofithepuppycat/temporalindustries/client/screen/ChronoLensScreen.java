package io.github.tofithepuppycat.temporalindustries.client.screen;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.item.ChronoscopeItem;
import io.github.tofithepuppycat.temporalindustries.network.ChronoLensSavePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Standalone (non-container) screen for the Chronoscope: the player types a moment, either as real
 * elapsed play time or as an in-game day and clock time, and travels to the Chrono Rift as it was
 * then.
 */
@SuppressWarnings("null")
public class ChronoLensScreen extends Screen {
    private static final ResourceLocation BASE_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            TemporalIndustries.MODID, "textures/gui/base.png");
    private static final int TEXTURE_WIDTH = 256;
    private static final int TEXTURE_HEIGHT = 256;
    private static final int CONTENT_MARGIN = 16;
    private static final int CONTENT_SIZE = TEXTURE_WIDTH - 2 * CONTENT_MARGIN;

    private static final int TEXT_PRIMARY = 0xFF2B2B2B;
    private static final int TEXT_MUTED = 0xFF7A7A7A;

    private static final int MODE_BUTTON_Y_OFFSET = 30;
    private static final int MODE_BUTTON_WIDTH = 120;
    private static final int FIELD_LABEL_Y_OFFSET = 66;
    private static final int FIELD_Y_OFFSET = 78;
    private static final int FIELD_WIDTH = 56;
    private static final int FIELD_HEIGHT = 20;
    private static final int FIELD_GAP = 12;
    private static final int PREVIEW_Y_OFFSET = 112;
    private static final int SLIDER_Y_OFFSET = 126;
    private static final int SLIDER_WIDTH = 188;
    private static final int NOW_BUTTON_Y_OFFSET = 150;
    private static final int NOW_BUTTON_WIDTH = 60;
    private static final int HINT_Y_OFFSET = 180;
    private static final int BUTTON_Y_OFFSET = 198;
    private static final int BUTTON_WIDTH = 96;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_GAP = 4;

    private static final long TICKS_PER_SECOND = 20L;
    private static final long TICKS_PER_DAY = 24000L;
    private static final long TICKS_PER_HOUR = 1000L;
    /** Tick 0 of a day is 06:00 on the in-game clock. */
    private static final int DAY_START_HOUR = 6;

    /** Slider fraction that maps to "now"; the final notch beyond it is the End of Time. */
    private static final double MAX_TIME_FRACTION = 0.999D;
    private static final String END_OF_TIME_TEXT = "???";

    /**
     * Slider over world start..now, kept in sync with the number fields in both directions; all the
     * way to the right selects the End of Time.
     */
    private final class TimeSlider extends AbstractSliderButton {
        TimeSlider(int x, int y, int width, int height) {
            super(x, y, width, height, Component.empty(), 0.0D);
        }

        void setFraction(double fraction) {
            this.value = fraction;
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.empty());
        }

        @Override
        protected void applyValue() {
            if (syncing) return;
            if (value >= 1.0D) {
                showEndOfTime();
            } else {
                writeFields(Math.round(value * currentGameTime()));
            }
        }
    }

    private final EditBox[] fields = new EditBox[3];
    private TimeSlider slider;
    private boolean syncing;
    private boolean endOfTime;

    private boolean realMode = true;
    private Button modeButton;
    private int leftPos;
    private int topPos;

    /** The moment already saved on the Chronoscope, or null if none. */
    @Nullable private final Long savedTime;

    public ChronoLensScreen(@Nullable Long savedTime) {
        super(Component.translatable("gui.temporalindustries.chrono_lens.title"));
        this.savedTime = savedTime;
    }

    /**
     * Opens this screen client-side only — call from inside a {@code level.isClientSide} branch.
     */
    public static void open(@Nullable Long savedTime) {
        Minecraft.getInstance().setScreen(new ChronoLensScreen(savedTime));
    }

    private int panelX() {
        return leftPos + CONTENT_MARGIN;
    }

    private int panelY() {
        return topPos + CONTENT_MARGIN;
    }

    @Override
    protected void init() {
        super.init();
        leftPos = (width - TEXTURE_WIDTH) / 2;
        topPos = (height - TEXTURE_HEIGHT) / 2;

        modeButton = addRenderableWidget(Button.builder(modeLabel(), btn -> toggleMode())
                .pos(panelX() + (CONTENT_SIZE - MODE_BUTTON_WIDTH) / 2, panelY() + MODE_BUTTON_Y_OFFSET)
                .size(MODE_BUTTON_WIDTH, BUTTON_HEIGHT)
                .build());

        int fieldsX = panelX() + (CONTENT_SIZE - (FIELD_WIDTH * 3 + FIELD_GAP * 2)) / 2;
        for (int i = 0; i < fields.length; i++) {
            EditBox box = new EditBox(font, fieldsX + i * (FIELD_WIDTH + FIELD_GAP), panelY() + FIELD_Y_OFFSET,
                    FIELD_WIDTH, FIELD_HEIGHT, fieldLabel(i));
            box.setMaxLength(6);
            box.setFilter(text -> endOfTime || text.matches("\\d*"));
            box.setResponder(text -> syncSlider());
            fields[i] = addRenderableWidget(box);
        }

        slider = addRenderableWidget(new TimeSlider(panelX() + (CONTENT_SIZE - SLIDER_WIDTH) / 2,
                panelY() + SLIDER_Y_OFFSET, SLIDER_WIDTH, BUTTON_HEIGHT));
        if (savedTime == null) writeFields(currentGameTime());
        else if (savedTime == ChronoscopeItem.END_OF_TIME) showEndOfTime();
        else writeFields(savedTime);

        addRenderableWidget(Button.builder(Component.translatable("gui.temporalindustries.chrono_lens.now_button"),
                        btn -> writeFields(currentGameTime()))
                .pos(panelX() + (CONTENT_SIZE - NOW_BUTTON_WIDTH) / 2, panelY() + NOW_BUTTON_Y_OFFSET)
                .size(NOW_BUTTON_WIDTH, BUTTON_HEIGHT)
                .build());

        int groupX = panelX() + (CONTENT_SIZE - (BUTTON_WIDTH * 2 + BUTTON_GAP)) / 2;
        int buttonY = panelY() + BUTTON_Y_OFFSET;
        addRenderableWidget(Button.builder(Component.translatable("gui.temporalindustries.chrono_lens.cancel_button"), btn -> onClose())
                .pos(groupX, buttonY)
                .size(BUTTON_WIDTH, BUTTON_HEIGHT)
                .build());
        addRenderableWidget(Button.builder(Component.translatable("gui.temporalindustries.chrono_lens.save_button"), btn -> save())
                .pos(groupX + BUTTON_WIDTH + BUTTON_GAP, buttonY)
                .size(BUTTON_WIDTH, BUTTON_HEIGHT)
                .build());
    }

    private Component modeLabel() {
        return Component.translatable(realMode
                ? "gui.temporalindustries.chrono_lens.mode_real"
                : "gui.temporalindustries.chrono_lens.mode_ingame");
    }

    private Component fieldLabel(int index) {
        String[] keys = realMode
                ? new String[] {"hours", "minutes", "seconds"}
                : new String[] {"day", "hour", "minute"};
        return Component.translatable("gui.temporalindustries.chrono_lens.field." + keys[index]);
    }

    private static long currentGameTime() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.level == null ? 0L : minecraft.level.getGameTime();
    }

    private void toggleMode() {
        long ticks = readGameTime();
        realMode = !realMode;
        modeButton.setMessage(modeLabel());
        if (endOfTime) {
            showEndOfTime();
        } else {
            writeFields(ticks);
        }
    }

    private int fieldValue(int index) {
        String text = fields[index].getValue();
        if (text.isEmpty()) return 0;
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    /** The moment the fields currently describe, clamped to between world start and now. */
    private long readGameTime() {
        long ticks;
        if (realMode) {
            long seconds = (long) fieldValue(0) * 3600L + (long) fieldValue(1) * 60L + fieldValue(2);
            ticks = seconds * TICKS_PER_SECOND;
        } else {
            long day = Math.max(1, fieldValue(0));
            long hour = ((fieldValue(1) - DAY_START_HOUR) % 24 + 24) % 24;
            ticks = (day - 1L) * TICKS_PER_DAY + hour * TICKS_PER_HOUR + Math.round(fieldValue(2) * TICKS_PER_HOUR / 60.0D);
        }
        return Math.max(0L, Math.min(ticks, currentGameTime()));
    }

    private void syncSlider() {
        if (syncing || slider == null || endOfTime) return;
        long now = currentGameTime();
        slider.setFraction(now <= 0L ? 0.0D : Math.min(MAX_TIME_FRACTION, (double) readGameTime() / now));
    }

    private void showEndOfTime() {
        endOfTime = true;
        syncing = true;
        try {
            for (int i = 0; i < fields.length; i++) {
                fields[i].setMessage(fieldLabel(i));
                fields[i].setValue(END_OF_TIME_TEXT);
                fields[i].setEditable(false);
            }
        } finally {
            syncing = false;
        }
        slider.setFraction(1.0D);
    }

    private void writeFields(long gameTime) {
        endOfTime = false;
        for (EditBox field : fields) field.setEditable(true);
        syncing = true;
        try {
            setFieldValues(gameTime);
        } finally {
            syncing = false;
        }
        syncSlider();
    }

    private void setFieldValues(long gameTime) {
        for (int i = 0; i < fields.length; i++) {
            fields[i].setMessage(fieldLabel(i));
        }
        if (realMode) {
            long seconds = gameTime / TICKS_PER_SECOND;
            fields[0].setValue(Long.toString(seconds / 3600L));
            fields[1].setValue(Long.toString(seconds / 60L % 60L));
            fields[2].setValue(Long.toString(seconds % 60L));
        } else {
            long inDay = gameTime % TICKS_PER_DAY;
            fields[0].setValue(Long.toString(gameTime / TICKS_PER_DAY + 1L));
            fields[1].setValue(Long.toString((inDay / TICKS_PER_HOUR + DAY_START_HOUR) % 24L));
            fields[2].setValue(Long.toString(Math.min(59L, Math.round((inDay % TICKS_PER_HOUR) * 60.0D / TICKS_PER_HOUR))));
        }
    }

    private Component previewText(long gameTime) {
        if (realMode) {
            long seconds = gameTime / TICKS_PER_SECOND;
            return Component.translatable("gui.temporalindustries.chrono_lens.preview.real",
                    String.format("%d:%02d:%02d", seconds / 3600L, seconds / 60L % 60L, seconds % 60L));
        }
        long inDay = gameTime % TICKS_PER_DAY;
        long hour = (inDay / TICKS_PER_HOUR + DAY_START_HOUR) % 24L;
        long minute = Math.min(59L, Math.round((inDay % TICKS_PER_HOUR) * 60.0D / TICKS_PER_HOUR));
        return Component.translatable("gui.temporalindustries.chrono_lens.preview.ingame",
                gameTime / TICKS_PER_DAY + 1L, String.format("%02d:%02d", hour, minute));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderTransparentBackground(guiGraphics);
        InnerBackground.render(guiGraphics, panelX(), panelY(), CONTENT_SIZE, CONTENT_SIZE);
        guiGraphics.blit(BASE_TEXTURE, leftPos, topPos, 0, 0, TEXTURE_WIDTH, TEXTURE_HEIGHT);
    }

    private void save() {
        PacketDistributor.sendToServer(new ChronoLensSavePacket(endOfTime ? ChronoscopeItem.END_OF_TIME : readGameTime()));
        onClose();
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Screen.render draws the background (panel texture included) and then the widgets, so the
        // text goes on top afterwards.
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        guiGraphics.drawString(font, title, panelX() + (CONTENT_SIZE - font.width(title)) / 2, panelY() + 8, TEXT_PRIMARY, false);

        for (int i = 0; i < fields.length; i++) {
            Component label = fieldLabel(i);
            guiGraphics.drawString(font, label, fields[i].getX() + (FIELD_WIDTH - font.width(label)) / 2,
                    panelY() + FIELD_LABEL_Y_OFFSET, TEXT_PRIMARY, false);
        }

        Component preview = endOfTime ? Component.literal(END_OF_TIME_TEXT) : previewText(readGameTime());
        guiGraphics.drawString(font, preview, panelX() + (CONTENT_SIZE - font.width(preview)) / 2, panelY() + PREVIEW_Y_OFFSET, TEXT_PRIMARY, false);

        Component hint = Component.translatable("gui.temporalindustries.chrono_lens.hint");
        guiGraphics.drawString(font, hint, panelX() + (CONTENT_SIZE - font.width(hint)) / 2, panelY() + HINT_Y_OFFSET, TEXT_MUTED, false);
    }
}
