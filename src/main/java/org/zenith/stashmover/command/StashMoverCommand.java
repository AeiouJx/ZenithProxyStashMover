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
package org.zenith.stashmover.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.zenith.command.api.Command;
import com.zenith.command.api.CommandCategory;
import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandUsage;
import com.zenith.discord.Embed;
import com.zenith.mc.block.BlockPos;
import org.zenith.stashmover.StashMoverConfig;
import org.zenith.stashmover.module.StashMoverModule;

import static com.zenith.command.brigadier.BlockPosArgument.blockPos;
import static com.zenith.command.brigadier.BlockPosArgument.getBlockPos;
import static org.zenith.stashmover.command.GreedyStringArgumentType.getString;
import static org.zenith.stashmover.command.GreedyStringArgumentType.greedyString;

/**
 * StashMover command set.
 */
public class StashMoverCommand extends Command {

    private final StashMoverConfig config;
    private final StashMoverModule module;

    public StashMoverCommand(StashMoverConfig config, StashMoverModule module) {
        this.config = config;
        this.module = module;
    }

    @Override
    public CommandUsage commandUsage() {
        return CommandUsage.builder()
            .name("stashMover")
            .aliases("sm")
            .category(CommandCategory.MODULE)
            .description("""
              StashMover - automatic stash transfer via ender pearl static-chamber teleportation.
              
              Input side (pickup) is prefixed with `input`, output side (unload) with `output`.
              Chests/pits are optional: when unset the bot auto-detects them from item frames:
                redstone dust -> input chest, redstone block -> output chest,
                ender pearl -> pearl pit trapdoor (below frame) / pearl restock chest (attached).
              Type a coordinate and press TAB in-game to fill it with the block at your crosshair.
              """)
            .usageLines(
                "inputBot <name> - pearl bot standing at the pickup side (whispered while at OUTPUT to return)",
                "inputChest <x> <y> <z> - pickup chest (auto from redstone dust frame if unset)",
                "inputPearl <x> <y> <z> - pickup side pearl pit trapdoor (auto if unset)",
                "inputPearlChest <x> <y> <z> - pickup side pearl restock chest (auto if unset)",
                "outputBot <name> - pearl bot standing at the unload side (whispered while at INPUT to go unload)",
                "outputChest <x> <y> <z> - unload chest (auto from redstone block frame if unset)",
                "outputPearl <x> <y> <z> - unload side pearl pit trapdoor (auto if unset)",
                "outputPearlChest <x> <y> <z> - unload side pearl restock chest (auto if unset)",
                "<key> clear - clear a setting and re-enable auto-detection",
                "start - start the cycle",
                "stop - stop the cycle",
                "status - show configuration and state",
                "debug [on|off] - toggle debug logging / show detailed debug info",
                "restockBelow [count] - restock ender pearls while below this count (default 16)",
                "bulkDelay [ticks] - delay between each of the 27 slot moves when bulk transferring (0 = all in one tick)"
            )
            .build();
    }

    @Override
    public LiteralArgumentBuilder<CommandContext> register() {
        return command("stashMover")
            .then(literal("inputChest")
                .then(posSetter("Input chest", pos -> config.inputChest = pos))
                .then(clearSetter("Input chest", () -> config.inputChest = null)))
            .then(literal("outputChest")
                .then(posSetter("Output chest", pos -> {
                    config.outputChest = pos;
                    module.resetForConfigChange();
                }))
                .then(clearSetter("Output chest", () -> {
                    config.outputChest = null;
                    module.resetForConfigChange();
                })))
            .then(literal("inputPearl")
                .then(posSetter("Input pearl pit trapdoor", pos -> config.inputPearl = pos))
                .then(clearSetter("Input pearl pit trapdoor", () -> config.inputPearl = null)))
            .then(literal("outputPearl")
                .then(posSetter("Output pearl pit trapdoor", pos -> config.outputPearl = pos))
                .then(clearSetter("Output pearl pit trapdoor", () -> config.outputPearl = null)))
            .then(literal("inputPearlChest")
                .then(posSetter("Input pearl chest", pos -> config.inputPearlChest = pos))
                .then(clearSetter("Input pearl chest", () -> config.inputPearlChest = null)))
            .then(literal("outputPearlChest")
                .then(posSetter("Output pearl chest", pos -> config.outputPearlChest = pos))
                .then(clearSetter("Output pearl chest", () -> config.outputPearlChest = null)))
            .then(literal("inputBot")
                .then(botSetter("Input bot", name -> config.inputBot = name))
                .then(literal("clear").executes(ctx -> {
                    config.inputBot = "";
                    ctx.getSource().getEmbed()
                        .title("Input bot cleared")
                        .successColor();
                    return OK;
                })))
            .then(literal("outputBot")
                .then(botSetter("Output bot", name -> config.outputBot = name))
                .then(literal("clear").executes(ctx -> {
                    config.outputBot = "";
                    ctx.getSource().getEmbed()
                        .title("Output bot cleared")
                        .successColor();
                    return OK;
                })))
            .then(literal("start")
                .executes(ctx -> {
                    config.enabled = true;
                    module.setEnabled(true);
                    module.reset();
                    ctx.getSource().getEmbed()
                        .title("StashMover started")
                        .description(config.isConfigured() ? "Cycle running" : "Config incomplete - set inputBot, outputBot (chests/pits may be auto-detected)")
                        .successColor();
                    return OK;
                }))
            .then(literal("stop")
                .executes(ctx -> {
                    config.enabled = false;
                    module.setEnabled(false);
                    ctx.getSource().getEmbed()
                        .title("StashMover stopped")
                        .errorColor();
                    return OK;
                }))
            .then(literal("status")
                .executes(ctx -> {
                    ctx.getSource().getEmbed()
                        .title("StashMover status")
                        .description("State: " + module.getStatus())
                        .addField("Config", config.getStatus())
                        .primaryColor();
                    return OK;
                }))
            .then(literal("debug")
                .executes(ctx -> {
                    config.debug = !config.debug;
                    ctx.getSource().getEmbed()
                        .title("StashMover debug " + (config.debug ? "enabled" : "disabled"))
                        .description("Detailed cycle logs go to the console/log.")
                        .successColor();
                    return OK;
                })
                .then(literal("on").executes(ctx -> {
                    config.debug = true;
                    ctx.getSource().getEmbed()
                        .title("StashMover debug enabled")
                        .successColor();
                    return OK;
                }))
                .then(literal("off").executes(ctx -> {
                    config.debug = false;
                    ctx.getSource().getEmbed()
                        .title("StashMover debug disabled")
                        .successColor();
                    return OK;
                }))
                .then(literal("info").executes(ctx -> {
                    ctx.getSource().getEmbed()
                        .title("StashMover debug info")
                        .description(module.getDebugInfo())
                        .addField("Config", config.getStatus())
                        .primaryColor();
                    return OK;
                })))
            .then(literal("restockBelow")
                .executes(ctx -> {
                    ctx.getSource().getEmbed()
                        .title("StashMover pearl restock threshold")
                        .description("Pearls are topped up while below " + config.restockBeforeThrowBelow
                            + ".\nEach restock fills the free hotbar slots and returns any overflow to the chest.")
                        .primaryColor();
                    return OK;
                })
                .then(argument("count", IntegerArgumentType.integer(0, 256)).executes(ctx -> {
                    config.restockBeforeThrowBelow = IntegerArgumentType.getInteger(ctx, "count");
                    ctx.getSource().getEmbed()
                        .title("StashMover pearl restock threshold set")
                        .description("Pearls are topped up while below " + config.restockBeforeThrowBelow + ".")
                        .successColor();
                    return OK;
                })))
            .then(literal("bulkDelay")
                .executes(ctx -> {
                    ctx.getSource().getEmbed()
                        .title("StashMover bulk transfer delay")
                        .description("Currently " + config.bulkTransferDelayTicks
                            + " tick(s) between each of the 27 slot moves (0 = all in one tick).")
                        .primaryColor();
                    return OK;
                })
                .then(argument("ticks", IntegerArgumentType.integer(0, 100)).executes(ctx -> {
                    config.bulkTransferDelayTicks = IntegerArgumentType.getInteger(ctx, "ticks");
                    ctx.getSource().getEmbed()
                        .title("StashMover bulk transfer delay set")
                        .description(config.bulkTransferDelayTicks
                            + " tick(s) between each of the 27 slot moves (0 = all in one tick).")
                        .successColor();
                    return OK;
                })));
    }

    private com.zenith.command.brigadier.ZRequiredArgumentBuilder<CommandContext, com.zenith.command.brigadier.Coordinates> posSetter(String title, java.util.function.Consumer<BlockPos> setter) {
        return argument("pos", blockPos()).executes(ctx -> {
            BlockPos pos = getBlockPos(ctx, "pos");
            setter.accept(pos);
            ctx.getSource().getEmbed()
                .title(title + " set")
                .description("(" + pos.x() + ", " + pos.y() + ", " + pos.z() + ")")
                .successColor();
            return OK;
        });
    }

    private com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandContext> clearSetter(String title, Runnable clear) {
        return literal("clear").executes(ctx -> {
            clear.run();
            ctx.getSource().getEmbed()
                .title(title + " cleared")
                .description("Auto-detection enabled")
                .successColor();
            return OK;
        });
    }

    private com.zenith.command.brigadier.ZRequiredArgumentBuilder<CommandContext, String> botSetter(String title, java.util.function.Consumer<String> setter) {
        return argument("name", greedyString()).executes(ctx -> {
            String name = StashMoverConfig.normalizeUsername(getString(ctx, "name"));
            setter.accept(name);
            ctx.getSource().getEmbed()
                .title(title + " set")
                .description("/w " + name + " tp")
                .successColor();
            return OK;
        });
    }

    @Override
    public void defaultEmbed(final Embed builder) {
        builder
            .title("StashMover")
            .description("Automatic stash transfer via ender pearl static-chamber teleportation")
            .addField("State", module.getStatus(), true)
            .primaryColor();
    }
}
