/*
 * ZenithProxy StashMover
 * Copyright (C) 2026 AeiouJx
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.zenith.stashmover;

import com.zenith.mc.block.BlockPos;

/**
 * StashMover configuration.
 *
 * <p>A single bot moves items between two endpoints, any distance and any dimension, using
 * ender pearl static-chamber teleportation:
 * <ul>
 *   <li>Input side (pickup): take items from {@code inputChest}</li>
 *   <li>Output side (unload): deposit items into {@code outputChest}</li>
 * </ul>
 *
 * <p>Every coordinate may be left unset, in which case it is auto-detected at runtime by
 * scanning item frames for a marker item placed next to the target block:
 * <ul>
 *   <li>redstone dust - the pickup chest</li>
 *   <li>redstone block - the unload chest</li>
 *   <li>ender pearl - the pearl pit trapdoor below the frame, or the pearl restock chest
 *       attached to it (one per side)</li>
 * </ul>
 *
 * <p>Teleporting is done by whispering the pearl bot standing on the far side:
 * {@code /w <username> tp}
 */
public class StashMoverConfig {

    /** Whether the module runs */
    public boolean enabled = false;

    /** Input (machine) chest - items are taken from here. Optional: auto-detected when null */
    public BlockPos inputChest = null;
    /** Output (warehouse) chest - items are deposited here. Optional: auto-detected when null */
    public BlockPos outputChest = null;
    /** Pearl restock chest at the input side (used when the bot is nearby). Optional: auto-detected when null */
    public BlockPos inputPearlChest = null;
    /** Pearl restock chest at the output side (used when the bot is nearby). Optional: auto-detected when null */
    public BlockPos outputPearlChest = null;

    /** Pearl pit trapdoor at the input side. Optional: auto-detected when null */
    public BlockPos inputPearl = null;
    /** Pearl pit trapdoor at the output side. Optional: auto-detected when null */
    public BlockPos outputPearl = null;

    /** Pearl bot standing at the input side; whispered while at OUTPUT to pull the bot back to INPUT */
    public String inputBot = "";
    /** Pearl bot standing at the output side; whispered while at INPUT to pull the bot to OUTPUT */
    public String outputBot = "";

    /** Horizontal radius to scan for the bot's own thrown ender pearl (pit detection) */
    public int pearlDetectRadius = 48;
    /** Max distance to the pearlChest before restock is skipped (prevents cross-dimension pathing) */
    public int restockMaxDistance = 256;
    /** Restock when held pearl count drops below this */
    public int minPearls = 8;
    /** Upper bound on stacks taken per restock; the actual count is the free hotbar slots */
    public int restockPearlStacks = 9;
    /**
     * Restock while standing outside the pit as soon as the pearl count drops below this.
     * The restock chest sits a few blocks around the pit and is always within reach there, so
     * waiting until the pearls are actually gone means finding out with none left while the
     * bot is already wedged in the shaft. Each restock tops the free hotbar slots back up.
     * Change it with {@code /sm restockBelow <count>}.
     */
    public int restockBeforeThrowBelow = 16;

    /** Horizontal radius to scan for the nearest ender chest at each visit */
    public int echestSearchRadius = 16;
    /** Horizontal radius to scan for item frames when auto-detecting chests */
    public int frameScanRadius = 32;
    /** Max distance between a marker item frame and the chest it labels */
    public int frameChestMaxDistance = 2;
    /** Interaction distance to containers/trapdoors (blocks) */
    public int interactRange = 4;
    /** Coordinate jump above this distance counts as a successful teleport */
    public int tpDetectDistance = 8;
    /** Ticks to wait after a teleport lands before resuming (lets the world/chunks settle) */
    public int teleportSettleTicks = 60;
    /** Pathfinding timeout (ticks) */
    public int pathTimeoutTicks = 1200;
    /** Teleport wait timeout (ticks) */
    public int teleportTimeoutTicks = 600;
    /**
     * Ticks to wait for the loader bot to answer the pull whisper before whispering again.
     * Only covers "no answer at all" - once it has answered we wait out the teleport timeout.
     */
    public int whisperReplyTimeoutTicks = 200;
    /** Debug logging enabled */
    public boolean debug = false;
    /** Ticks between each action when bulk-transferring 27 slots to/from a container.
     *  0 = the whole batch is sent in a single tick (fastest, Inventory-Profiles style);
     *  higher = safer/slower. Overrides the global client inventory action delay for these batches. */
    public int bulkTransferDelayTicks = 0;
    /** Auto-disable when the input chest is found empty on the first cargo check */
    public boolean autoDisableOnEmpty = true;

    public boolean isConfigured() {
        return !normalizeUsername(inputBot).isEmpty()
            && !normalizeUsername(outputBot).isEmpty();
    }

    public void setChest(boolean input, int[] pos) {
        BlockPos bp = pos == null ? null : new BlockPos(pos[0], pos[1], pos[2]);
        if (input) {
            this.inputChest = bp;
        } else {
            this.outputChest = bp;
        }
    }

    public void setPearlChest(boolean input, int[] pos) {
        BlockPos bp = pos == null ? null : new BlockPos(pos[0], pos[1], pos[2]);
        if (input) {
            this.inputPearlChest = bp;
        } else {
            this.outputPearlChest = bp;
        }
    }

    public void setPearl(boolean input, int[] pos) {
        BlockPos bp = pos == null ? null : new BlockPos(pos[0], pos[1], pos[2]);
        if (input) {
            this.inputPearl = bp;
        } else {
            this.outputPearl = bp;
        }
    }

    public String missing() {
        StringBuilder sb = new StringBuilder();
        if (normalizeUsername(inputBot).isEmpty()) sb.append("inputBot, ");
        if (normalizeUsername(outputBot).isEmpty()) sb.append("outputBot, ");
        if (sb.length() == 0) return "none";
        sb.setLength(sb.length() - 2);
        return sb.toString();
    }

    /**
     * Normalize a pearl bot username: strip surrounding whitespace and any enclosing
     * quote characters. The `||` prefix (offline-server display name) is preserved.
     */
    public static String normalizeUsername(String name) {
        if (name == null) return "";
        name = name.trim();
        if (name.length() >= 2) {
            char first = name.charAt(0);
            char last = name.charAt(name.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                name = name.substring(1, name.length() - 1).trim();
            }
        }
        return name;
    }

    public String getStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("enabled=").append(enabled);
        sb.append(" minPearls=").append(minPearls);
        sb.append(" restockBelow=").append(restockBeforeThrowBelow);
        sb.append(" restockStacks=").append(restockPearlStacks);
        sb.append(" restockMax=").append(restockMaxDistance);
        sb.append(" frameScan=").append(frameScanRadius);
        sb.append("\n");
        sb.append("inputChest=").append(fmt(inputChest));
        sb.append(" outputChest=").append(fmt(outputChest));
        sb.append("\n");
        sb.append("inputPearl=").append(fmt(inputPearl));
        sb.append(" outputPearl=").append(fmt(outputPearl));
        sb.append("\n");
        sb.append("inputPearlChest=").append(fmt(inputPearlChest));
        sb.append(" outputPearlChest=").append(fmt(outputPearlChest));
        sb.append("\n");
        sb.append("inputBot='").append(inputBot).append("'");
        sb.append(" outputBot='").append(outputBot).append("'");
        return sb.toString();
    }

    private static String fmt(BlockPos pos) {
        if (pos == null) return "auto";
        return "(" + pos.x() + "," + pos.y() + "," + pos.z() + ")";
    }
}