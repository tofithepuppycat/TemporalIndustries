package io.github.tofithepuppycat.temporalindustries.entropy;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;

/**
 * Mouse and keyboard icons drawn from the mod's glyph font. Translations reference them with
 * {@code {name}} tokens, e.g. {@code {sneak} + {rmb}}.
 */
public final class InputGlyphs {
    public static final ResourceLocation FONT = ResourceLocation.fromNamespaceAndPath("temporalindustries", "glyphs");

    private static final Map<String, Character> GLYPHS = Map.of(
            "lmb", '',
            "rmb", '',
            "sneak", '',
            "scroll", ''
    );

    private InputGlyphs() {}

    /** The glyph for a token name such as {@code rmb}, or null when no glyph has that name. */
    public static MutableComponent get(String name) {
        Character glyph = GLYPHS.get(name);
        if (glyph == null) return null;
        return Component.literal(String.valueOf(glyph)).withStyle(Style.EMPTY.withFont(FONT));
    }
}
