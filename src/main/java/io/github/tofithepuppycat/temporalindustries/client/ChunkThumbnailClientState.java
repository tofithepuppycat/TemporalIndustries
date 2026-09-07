package io.github.tofithepuppycat.temporalindustries.client;

import com.mojang.blaze3d.platform.NativeImage;
import io.github.tofithepuppycat.temporalindustries.chronomap.ChronoMapSampler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
import net.minecraft.world.level.material.MapColor;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/** Client-side cache of terrain thumbnails for whichever chunk-selection map is currently open;
 * decodes packed vanilla map-color bytes into a {@link DynamicTexture} per chunk, lazily
 * re-uploaded only when that chunk's data changes. Keyed by a generic anchor chunk key so it's
 * shared between the Chronosphere's claim map and the Portable Chrono Marker's area-select map. */
public final class ChunkThumbnailClientState {
    @Nullable
    private static Long activeAnchorKey;
    private static Map<Long, byte[]> serverThumbnails = new HashMap<>();
    private static final Map<Long, ResourceLocation> textureLocations = new HashMap<>();
    /** chunkKey -> the exact byte[] instance last uploaded, so an unchanged payload never re-touches the GPU. */
    private static final Map<Long, byte[]> uploadedThumbnails = new HashMap<>();

    private ChunkThumbnailClientState() {}

    public static void updateFromServer(long anchorKey, Map<Long, byte[]> thumbnails) {
        if (activeAnchorKey == null || activeAnchorKey != anchorKey) {
            clearAll();
        }
        activeAnchorKey = anchorKey;
        serverThumbnails = thumbnails;
    }

    /** Releases every cached texture; call when the map overlay closes or the client disconnects. */
    public static void clearAll() {
        Minecraft mc = Minecraft.getInstance();
        for (ResourceLocation location : textureLocations.values()) {
            mc.getTextureManager().release(location);
        }
        textureLocations.clear();
        uploadedThumbnails.clear();
        serverThumbnails = new HashMap<>();
        activeAnchorKey = null;
    }

    /** The texture to blit for chunkKey, uploading/refreshing it lazily, or null if no terrain
     * data has been received for that chunk yet. */
    @Nullable
    public static ResourceLocation getTexture(long chunkKey) {
        byte[] colors = serverThumbnails.get(chunkKey);
        if (colors == null) return null;

        if (uploadedThumbnails.get(chunkKey) == colors) {
            return textureLocations.get(chunkKey);
        }

        int size = ChronoMapSampler.SIZE;
        NativeImage image = new NativeImage(size, size, false);
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                int argb = MapColor.getColorFromPackedId(colors[z * size + x] & 0xFF);
                image.setPixelRGBA(x, z, FastColor.ABGR32.fromArgb32(argb));
            }
        }

        ResourceLocation location = textureLocations.get(chunkKey);
        Minecraft mc = Minecraft.getInstance();
        if (location != null) {
            DynamicTexture texture = (DynamicTexture) mc.getTextureManager().getTexture(location, null);
            if (texture != null) {
                texture.setPixels(image);
                texture.upload();
            }
        } else {
            DynamicTexture texture = new DynamicTexture(image);
            location = mc.getTextureManager().register("chunk_thumbnail_" + Long.toHexString(chunkKey), texture);
            textureLocations.put(chunkKey, location);
        }

        uploadedThumbnails.put(chunkKey, colors);
        return location;
    }
}
