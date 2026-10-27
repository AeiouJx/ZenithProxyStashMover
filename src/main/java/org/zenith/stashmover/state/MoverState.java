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
 *
 * <p>Movement ("go to X") and confirmation ("wait until X is true") are separate states, the
 * same way the villager trader plugin is laid out: a {@code GO_*} state starts the action and
 * hands over to an {@code AWAIT_*} state that polls for the result against a timer. That way a
 * timeout is always tied to one clearly named step instead of a bare counter shared by
 * unrelated things.
 */
public enum MoverState {
    /** Not running */
    IDLE,
    /** Entry point of a visit: clear the per-visit variables and move on */
    START,
    /** Decide which side we are on, or stand still until we can */
    EVAL_SIDE,
    /** Resolve the pearl pit position for this visit (auto-detect or override) */
    DETERMINE_PIT,
    /** Get close to the pearl pit */
    MOVE_TO_THROW,
    /** Climb out of the pit onto nearby dry ground */
    EXIT_WATER,
    /** Walk back to the trapdoor and right-click it (never mine it) */
    OPEN_TRAPDOOR,
    /** Wait for the clicked trapdoor to actually read as open */
    AWAIT_TRAPDOOR_OPEN,
    /** Check that a pearl is in hand, the lid is open and the drop path is clear */
    THROWING,
    /** Send the single right-click that throws the pearl straight down */
    THROW_PEARL,
    /** Wait for our own pearl entity to appear inside the pit column */
    AWAIT_PEARL_SPAWNED,
    /** Let go of the right-click for a couple of ticks before moving */
    AWAIT_THROW_SETTLE,
    /** Walk/jump clear of the pit with plain input */
    RETREAT,
    /** Gate into the next phase: the pearl has stopped sinking and we are clear */
    AWAIT_PEARL_STABLE,
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
    AWAIT_TELEPORT,
    /** Fatal failure */
    ERROR
}
