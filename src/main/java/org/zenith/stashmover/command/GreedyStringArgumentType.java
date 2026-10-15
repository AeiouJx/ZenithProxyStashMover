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

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.zenith.command.brigadier.SerializableArgumentType;
import org.geysermc.mcprotocollib.protocol.data.game.command.CommandParser;
import org.geysermc.mcprotocollib.protocol.data.game.command.properties.CommandProperties;
import org.geysermc.mcprotocollib.protocol.data.game.command.properties.StringProperties;
import org.jspecify.annotations.NonNull;

import java.util.Collection;
import java.util.List;

/**
 * A string argument type that accepts any character, including {@code |}, so that player
 * names can be typed directly into the vanilla chat command parser.
 */
public class GreedyStringArgumentType implements SerializableArgumentType<String> {

    private static final GreedyStringArgumentType INSTANCE = new GreedyStringArgumentType();

    public static GreedyStringArgumentType greedyString() {
        return INSTANCE;
    }

    public static String getString(final CommandContext<?> context, final String name) {
        return context.getArgument(name, String.class);
    }

    @Override
    public String parse(final StringReader reader) throws CommandSyntaxException {
        final String result = reader.getRemaining();
        reader.setCursor(reader.getTotalLength());
        return result;
    }

    @Override
    public Collection<String> getExamples() {
        return List.of("SomePlayer");
    }

    @Override
    public @NonNull CommandParser commandParser() {
        return CommandParser.STRING;
    }

    @Override
    public @NonNull CommandProperties commandProperties() {
        return StringProperties.GREEDY_PHRASE;
    }
}
