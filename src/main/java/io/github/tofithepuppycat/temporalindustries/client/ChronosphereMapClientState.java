package io.github.tofithepuppycat.temporalindustries.client;

import com.mojang.blaze3d.platform.NativeImage;
import io.github.tofithepuppycat.temporalindustries.chronomap.ChronoMapSampler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
import net.minecraft.world.level.material.MapColor;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/** Client-side cache of the active Chronosphere map overlay's terrain thumbnails — decodes the
 * packed vanilla map-color bytes {@link ChronosphereMapSyncPacket} delivers into an actual
 * {@link DynamicTexture} per chunk, lazily and only re-uploaded when that chunk's data changes. */
public final class ChronosphereMapClientState {
    private static BlockPos activeMachinePos;
    private static Map<Long, byte[]> serverThumbnails = new HashMap<>();
    private static final Map<Long, ResourceLocation> textureLocations = new HashMap<>();
    /** chunkKey -> the exact byte[] instance last uploaded to that chunk's texture, so an
     * unchanged payload (the common case on the periodic refresh) never re-touches the GPU. */
    private static final Map<Long, byte[]> uploadedThumbnails = new HashMap<>();

    private ChronosphereMapClientState() {}

    public static void updateFromServer(BlockPos machinePos, Map<Long, byte[]> thumbnails) {
        if (activeMachinePos == null || !activeMachinePos.equals(machinePos)) {
            clearAll();
        }
        activeMachinePos = machinePos;
        serverThumbnails = thumbnails;
    }

    /** Unconditionally releases every cached texture — call when the map overlay/screen closes or
     * the client disconnects, so a stale BlockPos can't keep matching coordinates in a different
     * world/server and reuse GPU textures painted from data that no longer applies. */
    public static void clearAll() {
        Minecraft mc = Minecraft.getInstance();
        for (ResourceLocation location : textureLocations.values()) {
            mc.getTextureManager().release(location);
        }
        textureLocations.clear();
        uploadedThumbnails.clear();
        serverThumbnails = new HashMap<>();
        activeMachinePos = null;
    }

    /** The texture to blit for chunkKey (a SIZE x SIZE image, see {@link ChronoMapSampler#SIZE}),
     * uploading/refreshing it lazily as needed, or null if no terrain data has been received for
     * that chunk yet (not yet synced, or not currently loaded server-side). */
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
            location = mc.getTextureManager().register("chronosphere_map_" + Long.toHexString(chunkKey), texture);
            textureLocations.put(chunkKey, location);
        }

        uploadedThumbnails.put(chunkKey, colors);
        return location;
    }
}
