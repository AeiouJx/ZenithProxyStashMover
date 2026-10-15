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
package org.zenith.stashmover.state;

/**
 * StashMover single-role, bi-directional cycle state machine.
 */
public enum MoverState {
    /** Not running */
    IDLE,
    /** Start of a visit: identify which side the bot is on */
    START,
    /** Resolve the pearl pit position for this visit (auto-detect or override) */
    DETERMINE_PIT,
    /** Get close to the pearl pit */
    MOVE_TO_THROW,
    /** Climb out of the water pit onto nearby dry ground */
    EXIT_WATER,
    /** Open the pit trapdoor if it is currently closed */
    OPEN_TRAPDOOR,
    /** Sneak, aim and throw the ender pearl into the pit, then wait for it to rise to the trapdoor and rest (~3s) */
    THROWING,
    /** Jump out of the water and retreat away from the trapdoor side; re-throw if the pearl triggers on us; wait for pearl stability */
    RETREAT,
    /** Refill ender pearls if below the minimum */
    RESTOCK_PEARLS,
    /** Walk to the active cargo chest */
    MOVE_TO_CHEST,
    /** Input: take items into the main inventory. Output: deposit items into the chest */
    CARGO,
    /** Walk to the nearest ender chest */
    MOVE_TO_ECHEST,
    /** Input: stash main inventory into the ender chest. Output: withdraw and deposit */
    ECHEST,
    /** Close the currently open container */
    CLOSING,
    /** Whisper the opposite side's pearl bot to teleport away */
    WHISPER,
    /** Wait for the coordinate jump that confirms the teleport */
    WAIT_TELEPORT,
    /** Fatal failure */
    ERROR
}