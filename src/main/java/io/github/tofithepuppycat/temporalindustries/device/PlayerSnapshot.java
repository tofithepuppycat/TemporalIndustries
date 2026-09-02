package io.github.tofithepuppycat.temporalindustries.device;

import io.github.tofithepuppycat.temporalindustries.compat.curios.CuriosCompat;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;

/**
 * A full capture of a player's state (position, vitals, inventory) taken the moment a
 * Temporal Anchor is calibrated. {@link #applyTo(ServerPlayer)} restores every field,
 * discarding anything the player did after the checkpoint.
 */
public class PlayerSnapshot {
    private final double x;
    private final double y;
    private final double z;
    private final float yRot;
    private final float xRot;
    private final ResourceLocation dimension;
    private final float health;
    private final int foodLevel;
    private final float saturation;
    private final float exhaustion;
    private final int xpLevel;
    private final float xpProgress;
    private final int totalXp;
    private final ListTag effects;
    private final ListTag inventory;
    @Nullable
    private final ListTag curiosInventory;
    private final long gameTime;

    public PlayerSnapshot(double x, double y, double z, float yRot, float xRot, ResourceLocation dimension,
            float health, int foodLevel, float saturation, float exhaustion,
            int xpLevel, float xpProgress, int totalXp, ListTag effects, ListTag inventory,
            @Nullable ListTag curiosInventory, long gameTime) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.yRot = yRot;
        this.xRot = xRot;
        this.dimension = dimension;
        this.health = health;
        this.foodLevel = foodLevel;
        this.saturation = saturation;
        this.exhaustion = exhaustion;
        this.xpLevel = xpLevel;
        this.xpProgress = xpProgress;
        this.totalXp = totalXp;
        this.effects = effects;
        this.inventory = inventory;
        this.curiosInventory = curiosInventory;
        this.gameTime = gameTime;
    }

    public long getGameTime() {
        return gameTime;
    }

    public static PlayerSnapshot capture(ServerPlayer player) {
        ListTag effects = new ListTag();
        for (MobEffectInstance effect : player.getActiveEffects()) {
            effects.add(effect.save());
        }

        ListTag curiosInventory = ModList.get().isLoaded("curios") ? CuriosCompat.saveCurios(player) : null;

        return new PlayerSnapshot(
                player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot(),
                player.level().dimension().location(),
                player.getHealth(),
                player.getFoodData().getFoodLevel(), player.getFoodData().getSaturationLevel(), player.getFoodData().getExhaustionLevel(),
                player.experienceLevel, player.experienceProgress, player.totalExperience,
                effects,
                player.getInventory().save(new ListTag()),
                curiosInventory,
                player.level().getGameTime());
    }

    /** @param restoreInventory whether to also overwrite the player's current inventory with the
     * snapshot's — false for the Temporal Anchor's "keep inventory" rewind mode. */
    public void applyTo(ServerPlayer player, boolean restoreInventory) {
        MinecraftServer server = player.getServer();
        ServerLevel targetLevel = null;
        if (server != null) {
            targetLevel = server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
        }
        if (targetLevel == null) {
            targetLevel = player.serverLevel();
        }

        player.teleportTo(targetLevel, x, y, z, yRot, xRot);
        player.setHealth(health);

        player.getFoodData().setFoodLevel(foodLevel);
        player.getFoodData().setSaturation(saturation);
        player.getFoodData().setExhaustion(exhaustion);

        player.experienceLevel = xpLevel;
        player.experienceProgress = xpProgress;
        player.totalExperience = totalXp;
        player.connection.send(new ClientboundSetExperiencePacket(xpProgress, totalXp, xpLevel));

        player.removeAllEffects();
        for (int i = 0; i < effects.size(); i++) {
            MobEffectInstance instance = MobEffectInstance.load(effects.getCompound(i));
            if (instance != null) {
                player.addEffect(instance);
            }
        }

        if (restoreInventory) {
            player.getInventory().clearContent();
            player.getInventory().load(inventory);
            if (curiosInventory != null && ModList.get().isLoaded("curios")) {
                CuriosCompat.loadCurios(player, curiosInventory);
            }
        }
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putDouble("X", x);
        tag.putDouble("Y", y);
        tag.putDouble("Z", z);
        tag.putFloat("YRot", yRot);
        tag.putFloat("XRot", xRot);
        tag.putString("Dimension", dimension.toString());
        tag.putFloat("Health", health);
        tag.putInt("FoodLevel", foodLevel);
        tag.putFloat("Saturation", saturation);
        tag.putFloat("Exhaustion", exhaustion);
        tag.putInt("XpLevel", xpLevel);
        tag.putFloat("XpProgress", xpProgress);
        tag.putInt("TotalXp", totalXp);
        tag.put("Effects", effects);
        tag.put("Inventory", inventory);
        if (curiosInventory != null) {
            tag.put("CuriosInventory", curiosInventory);
        }
        tag.putLong("GameTime", gameTime);
        return tag;
    }

    public static PlayerSnapshot fromTag(CompoundTag tag) {
        return new PlayerSnapshot(
                tag.getDouble("X"), tag.getDouble("Y"), tag.getDouble("Z"),
                tag.getFloat("YRot"), tag.getFloat("XRot"),
                ResourceLocation.tryParse(tag.getString("Dimension")),
                tag.getFloat("Health"),
                tag.getInt("FoodLevel"), tag.getFloat("Saturation"), tag.getFloat("Exhaustion"),
                tag.getInt("XpLevel"), tag.getFloat("XpProgress"), tag.getInt("TotalXp"),
                tag.getList("Effects", Tag.TAG_COMPOUND),
                tag.getList("Inventory", Tag.TAG_COMPOUND),
                tag.contains("CuriosInventory") ? tag.getList("CuriosInventory", Tag.TAG_COMPOUND) : null,
                tag.getLong("GameTime"));
    }

}
