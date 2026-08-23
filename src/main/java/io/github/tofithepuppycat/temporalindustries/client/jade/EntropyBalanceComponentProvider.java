package io.github.tofithepuppycat.temporalindustries.client.jade;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyDisplay;
import io.github.tofithepuppycat.temporalindustries.entropy.EntropyInfoProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

/**
 * Shows a time machine's order/chaos balance (Chronovault, Chronosphere, Chronodial — anything
 * reporting {@link EntropyInfoProvider#hasEntropyBalance()}), mirroring the balance line the Entropy
 * Glasses overlay draws under its bar. Deliberately doesn't expose the underlying tank mB amounts —
 * only the derived balance value and its drift rate, same as {@code gui.temporalindustries.entropy.value}.
 */
public enum EntropyBalanceComponentProvider implements IBlockComponentProvider {
    INSTANCE;

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        if (!(accessor.getBlockEntity() instanceof EntropyInfoProvider provider) || !provider.hasEntropyBalance()) return;

        int max = provider.getEntropyBalanceMax();
        int entropy = provider.getEntropyBalance();
        int offset = entropy - max / 2;
        String sign = offset >= 0 ? "+" : "";
        tooltip.add(Component.translatable("gui.temporalindustries.entropy.value", sign + EntropyDisplay.formatBalance(offset)));

        float rate = provider.getEntropyRatePerSecond();
        if (rate != 0f) {
            String rateSign = rate > 0 ? "+" : "";
            tooltip.add(Component.translatable("gui.temporalindustries.entropy.rate",
                    rateSign + String.format(java.util.Locale.ROOT, "%.1f", rate)));
        }
    }

    @Override
    public ResourceLocation getUid() {
        return ResourceLocation.fromNamespaceAndPath(TemporalIndustries.MODID, "entropy_balance");
    }
}
