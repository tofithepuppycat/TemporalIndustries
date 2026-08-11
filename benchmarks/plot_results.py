"""Turns the CSVs written by StorageBenchmark into thesis figures.

Usage: python benchmarks/plot_results.py [csv_dir] [figure_dir]
"""
import sys
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import pandas as pd

csv_dir = Path(sys.argv[1] if len(sys.argv) > 1 else "build/benchmark")
fig_dir = Path(sys.argv[2] if len(sys.argv) > 2 else csv_dir / "figures")
fig_dir.mkdir(parents=True, exist_ok=True)

# Fixed colour per strategy so a series keeps its colour in every figure.
STORES = {
    "Naive full copy": ("#8a8a85", "o"),
    "Mod baseline (RLE + delta chain)": ("#2a78d6", "s"),
    "Section dedup (content-addressed)": ("#eb6834", "^"),
    "Reverse delta (head materialised)": ("#1baf7a", "D"),
    "Section delta (cost-based keyframes)": ("#e87ba4", "v"),
}
INK, MUTED, GRID = "#0b0b0b", "#52514e", "#e6e5e1"

plt.rcParams.update({
    "figure.facecolor": "#fcfcfb", "axes.facecolor": "#fcfcfb", "savefig.facecolor": "#fcfcfb",
    "text.color": INK, "axes.labelcolor": MUTED, "xtick.color": MUTED, "ytick.color": MUTED,
    "axes.edgecolor": GRID, "axes.grid": True, "grid.color": GRID, "grid.linewidth": 0.8,
    "axes.spines.top": False, "axes.spines.right": False, "font.size": 10, "axes.titlesize": 11,
    "axes.titleweight": "bold", "axes.titlelocation": "left", "lines.linewidth": 2, "lines.markersize": 6,
})

size = pd.read_csv(csv_dir / "size.csv")
commit = pd.read_csv(csv_dir / "commit.csv")
resolve = pd.read_csv(csv_dir / "resolve.csv")
diff = pd.read_csv(csv_dir / "diff.csv")
scrub = pd.read_csv(csv_dir / "scrub.csv")
workloads = list(size["workload"].unique())
stores = [s for s in STORES if s in set(size["store"])]


def short(store):
    return store.split(" (")[0]


def grid(title, ylabel, frame, x, y, logx=True, logy=True, xlabel="", suptitle_note=""):
    """One small-multiple line chart per workload with a shared legend."""
    fig, axes = plt.subplots(2, 3, figsize=(13, 7.2), sharex=True)
    for ax, workload in zip(axes.flat, workloads):
        sub = frame[frame["workload"] == workload]
        for store in stores:
            data = sub[sub["store"] == store].sort_values(x)
            color, marker = STORES[store]
            ax.plot(data[x], data[y], color=color, marker=marker, label=short(store))
        ax.set_title(workload)
        if logx:
            ax.set_xscale("log", base=2)
        if logy:
            ax.set_yscale("log")
        ax.set_xlabel(xlabel)
        ax.set_ylabel(ylabel)
    handles, labels = axes.flat[0].get_legend_handles_labels()
    legend_ax = axes.flat[-1]
    legend_ax.axis("off")
    legend_ax.legend(handles, labels, loc="center left", frameon=False, fontsize=11)
    fig.suptitle(title + suptitle_note, x=0.01, ha="left", fontsize=13, fontweight="bold")
    fig.tight_layout(rect=(0, 0, 1, 0.95))
    return fig


def bars(title, ylabel, frame, value, logy=False, fname="", note=""):
    """Grouped bars: one cluster per workload, one bar per strategy."""
    fig, ax = plt.subplots(figsize=(11, 5))
    shown = [s for s in stores if s in set(frame["store"])]
    width = 0.8 / len(shown)
    for i, store in enumerate(shown):
        data = frame[frame["store"] == store].set_index("workload").reindex(workloads)
        xs = [j + (i - (len(shown) - 1) / 2) * width for j in range(len(workloads))]
        ax.bar(xs, data[value], width=width * 0.92, color=STORES[store][0], label=short(store))
    ax.set_xticks(range(len(workloads)), workloads)
    ax.set_ylabel(ylabel)
    ax.set_title(title + note)
    ax.grid(axis="x", visible=False)
    if logy:
        ax.set_yscale("log")
    ax.legend(frameon=False, ncol=len(shown), loc="upper center", bbox_to_anchor=(0.5, -0.1), fontsize=9)
    fig.tight_layout()
    return fig


# 1. Storage growth
final_versions = int(size["versions"].max())
fig = grid("Storage used by the chunk history (deflated)", "bytes, log scale", size, "versions", "deflated_bytes",
           xlabel="committed versions")
fig.savefig(fig_dir / "1_storage_growth.png", dpi=160)

# 2. Compression versus the naive copy at the final version count
final = size[size["versions"] == final_versions].copy()
naive = final[final["store"] == "Naive full copy"].set_index("workload")["deflated_bytes"]
final["factor"] = final.apply(lambda r: naive[r["workload"]] / r["deflated_bytes"], axis=1)
fig = bars(f"Space saved versus naive full copies ({final_versions} versions, deflated)", "times smaller, log scale",
           final[final["store"] != "Naive full copy"], "factor", logy=True)
fig.savefig(fig_dir / "2_space_saving.png", dpi=160)

# 3. Raw vs deflated for the mixed workload
mixed = final[final["workload"] == "Mixed"].set_index("store").reindex(stores)
fig, ax = plt.subplots(figsize=(10, 4.8))
xs = range(len(stores))
ax.bar([x - 0.2 for x in xs], mixed["raw_bytes"], width=0.38, color=[STORES[s][0] for s in stores], alpha=0.45, label="serialised")
ax.bar([x + 0.2 for x in xs], mixed["deflated_bytes"], width=0.38, color=[STORES[s][0] for s in stores], label="serialised + deflate")
ax.set_yscale("log")
ax.set_xticks(list(xs), [short(s).replace(" ", "\n", 1) for s in stores], fontsize=9)
ax.set_ylabel("bytes, log scale")
ax.set_title(f"Mixed workload, {final_versions} versions: effect of the compression layer")
ax.grid(axis="x", visible=False)
ax.legend(frameon=False)
fig.tight_layout()
fig.savefig(fig_dir / "3_compression_layer.png", dpi=160)

# 4. Commit cost
fig = bars("Mean time to commit one new version", "milliseconds", commit, "mean_ms")
fig.savefig(fig_dir / "4_commit_time.png", dpi=160)

# 5. Resolve time against distance from the head
fig = grid("Time to reconstruct an old version", "microseconds, log scale", resolve, "distance", "resolve_us",
           xlabel="versions behind head")
fig.savefig(fig_dir / "5_resolve_time.png", dpi=160)

# 6. Rollback diff time
rollback = diff[diff["direction"] == "rollback"]
fig = grid("Time to compute a rollback (head to an older version)", "microseconds, log scale", rollback, "distance",
           "diff_us", xlabel="versions rolled back")
fig.savefig(fig_dir / "6_rollback_diff_time.png", dpi=160)

# 7. Timeline scrubbing
fig, axes = plt.subplots(1, 2, figsize=(12, 4.8), sharey=True)
for ax, cache in zip(axes, ["none", "lru16"]):
    sub = scrub[scrub["cache"] == cache].groupby("store")["total_ms"].mean().reindex(stores)
    ax.bar(range(len(stores)), sub, color=[STORES[s][0] for s in stores], width=0.7)
    ax.set_xticks(range(len(stores)), [short(s).replace(" ", "\n", 1) for s in stores], fontsize=8)
    ax.set_title("No cache" if cache == "none" else "LRU cache (16 versions)")
    ax.grid(axis="x", visible=False)
axes[0].set_ylabel("ms for 300 slider positions (mean of workloads)")
fig.tight_layout()
fig.savefig(fig_dir / "7_timeline_scrub.png", dpi=160)

# Summary table for the text of the thesis
summary = final.set_index(["workload", "store"])[["raw_bytes", "deflated_bytes", "factor"]]
summary.to_csv(fig_dir / "summary_space.csv")
print(f"Wrote figures to {fig_dir}")
