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

import com.zenith.plugin.api.Plugin;
import com.zenith.plugin.api.PluginAPI;
import com.zenith.plugin.api.ZenithProxyPlugin;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.zenith.stashmover.command.StashMoverCommand;
import org.zenith.stashmover.module.StashMoverModule;

/**
 * Plugin entry point. Registers the StashMover module, its command and the shared config.
 */
@Plugin(
    id = BuildConstants.PLUGIN_ID,
    version = BuildConstants.VERSION,
    description = "ZenithProxy StashMover - pearl-based stash transfer between two endpoints",
    url = "https://github.com/AeiouJx/ZenithProxyStashMover",
    authors = {"AeiouJx"},
    mcVersions = {BuildConstants.MC_VERSION}
)
public class StashMoverPlugin implements ZenithProxyPlugin {

    public static StashMoverConfig PLUGIN_CONFIG;
    public static StashMoverModule PLUGIN_MODULE;
    public static ComponentLogger LOG;

    @Override
    public void onLoad(PluginAPI pluginAPI) {
        LOG = pluginAPI.getLogger();
        LOG.info("StashMover plugin loading...");

        PLUGIN_CONFIG = pluginAPI.registerConfig(BuildConstants.PLUGIN_ID, StashMoverConfig.class);
        PLUGIN_MODULE = new StashMoverModule(PLUGIN_CONFIG);

        pluginAPI.registerModule(PLUGIN_MODULE);
        pluginAPI.registerCommand(new StashMoverCommand(PLUGIN_CONFIG, PLUGIN_MODULE));

        LOG.info("StashMover plugin loaded.");
    }
}