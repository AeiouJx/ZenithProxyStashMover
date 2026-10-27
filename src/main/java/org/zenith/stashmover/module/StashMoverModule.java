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
package org.zenith.stashmover.module;

import com.github.rfresh2.EventConsumer;
import com.zenith.Proxy;
import com.zenith.cache.data.entity.Entity;
import com.zenith.cache.data.inventory.Container;
import com.zenith.event.client.ClientBotTick;
import com.zenith.event.chat.PublicChatEvent;
import com.zenith.event.chat.WhisperChatEvent;
import com.zenith.feature.inventory.InventoryActionRequest;
import com.zenith.feature.inventory.actions.ClickItem;
import com.zenith.feature.inventory.actions.CloseContainer;
import com.zenith.feature.inventory.actions.InventoryAction;
import com.zenith.feature.inventory.actions.SetHeldItem;
import com.zenith.feature.inventory.actions.ShiftClick;
import com.zenith.feature.inventory.util.InventoryActionMacros;
import com.zenith.feature.inventory.util.InventoryUtil;
import com.zenith.feature.pathfinder.goals.GoalBlock;
import com.zenith.feature.pathfinder.goals.GoalGetToBlock;
import com.zenith.feature.player.ClickTarget;
import com.zenith.feature.player.Input;
import com.zenith.feature.player.InputRequest;
import com.zenith.feature.player.World;
import com.zenith.mc.block.BlockPos;
import com.zenith.mc.block.Direction;
import com.zenith.mc.block.properties.api.BlockStateProperties;
import com.zenith.mc.item.ItemRegistry;
import com.zenith.module.api.Module;
import com.zenith.util.timer.Timer;
import com.zenith.util.timer.Timers;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.MetadataTypes;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.ProjectileData;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ShiftClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatPacket;
import org.zenith.stashmover.StashMoverConfig;
import org.zenith.stashmover.state.MoverState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static com.github.rfresh2.EventConsumer.of;
import static com.zenith.Globals.*;

/**
 * StashMover core module.
 *
 * <p>START -> EVAL_SIDE -> DETERMINE_PIT -> MOVE_TO_THROW ->
 * (EXIT_WATER -> OPEN_TRAPDOOR -> AWAIT_TRAPDOOR_OPEN)* -> THROWING -> THROW_PEARL ->
 * AWAIT_PEARL_SPAWNED -> AWAIT_THROW_SETTLE -> RETREAT -> AWAIT_PEARL_STABLE ->
 * RESTOCK_PEARLS -> MOVE_TO_CHEST -> CARGO -> [MOVE_TO_ECHEST -> ECHEST]* -> CLOSING ->
 * WHISPER -> AWAIT_TELEPORT -> START
 *
 * <p>Teleporting is dimension-agnostic: success is detected purely by a coordinate jump, and
 * the side is re-confirmed from positive evidence before any cargo work after a pull.
 */
public class StashMoverModule extends Module {

    private static final int MOVE_PRIORITY = 10000;
    private static final int INV_PRIORITY = 10000;
    private static final int MAX_TP_RETRIES = 3;
    /** How many times we re-throw before whispering with an empty chamber behind us */
    private static final int MAX_WHISPER_REPAIRS = 2;
    /** Max ticks to wait for a newly thrown pearl to appear before re-throwing */
    private static final int THROW_DETECT_TICKS = 120;
    /** How far a pearl has to rise off its lowest point before we hop away (one block) */
    private static final double PEARL_RISE_ONE_BLOCK = 1.0;
    /** Max ticks to watch the pearl sink and start rising before retreating anyway */
    private static final int THROW_SINK_TIMEOUT_TICKS = 120;
    private static final int MAX_THROW_RETRIES = 3;
    /** Distance (blocks) the sneak-in must gain on the pit before the stall counter resets */
    private static final double CREEP_PROGRESS_EPS = 0.05;
    /** Ticks to stand still at the sneak edge guard before backing off and trying again */
    private static final int CREEP_HOLD_TICKS = 45;
    /** How many times we may restart the sneak-in before giving up on lining up */
    private static final int MAX_CREEP_ATTEMPTS = 3;
    /** Distance from the pit centre (0.30 blocks) at which a throw counts as well centred */
    private static final double THROW_CENTER_SQ = 0.09;
    /**
     * Ticks the sneak-in may stay pinned by the lid/trapdoor box before we throw from an
     * off-centre spot. Without it a bot whose feet are wedged would never throw at all.
     */
    private static final int CREEP_CENTER_HOLD_TICKS = 60;
    /** Fruitless pearl restock cycles before halting instead of looping THROWING <-> RESTOCK */
    private static final int MAX_FAILED_RESTOCKS = 2;
    /**
 * Rough depth of the static chamber's shaft. Only used for diagnostics/logging - the rescue
 * trigger itself is "inside the cross-section and below the rim", which is the condition that
 * actually matters.
 */
private static final int PIT_SHAFT_DEPTH = 8;
    /** Ticks spent on one direction before rotating when hopping off a shut trapdoor */
    private static final int STEP_OFF_DIRECTION_TICKS = 6;
    /**
     * Escape phases: 4 directions forwards, 4 directions backwards, 4 directions sneaking.
     * After those the bot gives up on walking and clicks the trapdoor where it stands.
     */
    private static final int STEP_OFF_PHASES = 12;
    /** Extra yaw offsets tried after the trapdoor facing when hopping off a shut trapdoor */
    private static final float[] HOP_SIDE_OFFSETS = {90f, 180f, -90f};
    /** Ticks of hand-driven hopping used to shake baritone loose when pathing stalls */
    private static final int SHAKE_OFF_TICKS = 12;
    /** Close enough to the stand cell next to the pit that the sneak-in can start (0.6 blocks) */
    private static final double STAND_ARRIVED_SQ = 0.36;
    /** Beyond this the walk to the stand cell is handed to baritone (2.5 blocks) */
    private static final double STAND_BARITONE_SQ = 6.25;
    /** Ticks of hand-walking toward the stand cell before it counts as a failed approach */
    private static final int STAND_HAND_TIMEOUT_TICKS = 100;
    private static final int MAX_TRAPDOOR_RETRIES = 3;
    /** Ticks to wait for a clicked trapdoor to read as open before clicking it again */
    private static final int AWAIT_TRAPDOOR_TICKS = 10;
    private static final double PIT_NEAR_DIST_SQ = 4.0;
    /** How far off the pit centre a pearl may sit horizontally and still count as "in the pit". */
    private static final double PIT_PEARL_COL_DX = 0.6;
    private static final double PIT_PEARL_COL_DZ = 0.6;
    /** Ticks the pearl must sit risen at the trapdoor before cargo may begin (~3s) */
    private static final int PEARL_STAY_TICKS = 15;
    /** Max ticks RETREAT waits for the pearl to rise before giving up and proceeding */
    private static final int RETREAT_STABLE_TIMEOUT_TICKS = 600;
    /** A coordinate jump larger than this during RETREAT means the pearl triggered us */
    private static final double PEARL_TRIGGER_JUMP_SQ = 9.0;
    /** Ticks to wait in START after a teleport before declaring the area unconfirmable */
    private static final int SIDE_CONFIRM_TIMEOUT_TICKS = 100;
    /**
     * How close we must be to one of the two stashes before any cargo work may start. Both
     * sides sit next to each other within their own area, so anything beyond this is either the
     * queue server's spawn or mid-flight across the world - neither of which has a container
     * to work with.
     */
    private static final double MAX_SIDE_DIST_SQ = 512.0 * 512.0;
    private static final double PEARL_REST_VEL_SQ = 0.05;

    private final StashMoverConfig config;

    private MoverState state = MoverState.IDLE;
    private MoverState afterClose = MoverState.IDLE;

    private boolean isInputSide = true;
    /** After a teleport the side must be re-confirmed from positive evidence before cargo */
    private boolean sideConfirmRequired = false;
    private int sideConfirmTicks = 0;

    private int stateTicks = 0;
    private int pathTicks = 0;
    /**
     * One timer per awaited step. {@link #setState} resets all of them, so a timeout always
     * measures how long the current step has been waiting - not how long some counter that is
     * shared with three other branches happens to have been running.
     */
    private final Timer awaitTrapdoorTimer = Timers.tickTimer();
    private final Timer awaitPearlSpawnTimer = Timers.tickTimer();
    private final Timer awaitThrowSettleTimer = Timers.tickTimer();
    private final Timer awaitPearlStableTimer = Timers.tickTimer();
    private final Timer awaitRetreatTimer = Timers.tickTimer();
    private final Timer awaitTeleportTimer = Timers.tickTimer();
    private int openAttemptCooldown = 0;
    private int tpRetries = 0;
    private int throwRetries = 0;
    private int trapdoorRetries = 0;
    private boolean firstCargoCheck = true;
    private boolean restockSubmitted = false;

    private int[] currentPit = null;
    /** Original Y of the water surface before currentPit was overwritten with trapdoorPos */
    private int pitSurfaceY = -1;
    /** Y of the tracked pearl last tick, used to see whether it is still sinking. */
    private double pearlLastY = 0.0;
    private boolean pearlRiseInit = false;
    /**
     * Consecutive re-throws caused by the pearl never confirming in the chamber. Kept separate
     * from throwRetries (which is reset on every entry to THROWING) so that a failing chamber
     * cannot reset its own retry budget and burn pearls forever.
     */
    private int rethrowCount = 0;
    /** Entity ID of the pearl found by pearlRestingInPit() */
    private int lastFoundPearlId = -1;
    /** Lowest Y the freshly thrown pearl has reached, for "sank, then floated back up" */
    private double pearlLowestY = 0.0;
    private boolean pearlSinkInit = false;
    /** Whether the cargo log message has been printed for the current CARGO session */
    private boolean cargoLogged = false;
    /** Consecutive MOVE_TO_THROW path failures (survives state transitions) */
    private int moveToThrowRetries = 0;
    /** Last MOVE_TO_THROW arrival verdict, only used to log transitions */
    private boolean lastNearThrow = false;
    /** Set when the facing-side stand cell could not be reached, so baritone parks us instead */
    private boolean standApproachFailed = false;
    /** Sneak-creep progress tracking (walking to the throw spot over the pit edge) */
    private int creepStuckTicks = 0;
    private double creepLastHx = 0.0;
    private double creepLastHz = 0.0;
    private boolean creepInit = false;
    /** Progress tracking for climbing back out of the shaft under the pit */
    private int riseStuckTicks = 0;
    private double riseLastX = 0.0;
    private double riseLastZ = 0.0;
    private boolean riseInit = false;
    /** Consecutive ticks spent under the pit level, guards against sinking forever */
    private int belowPitTicks = 0;
    /** Trapdoor to open instead of the target pit's - set while stuck under a neighbour's lid */
    private int[] overrideTrapdoor = null;
    private int[] landingPos = null;
    private int[] echestPos = null;
    private int[] trapdoorPos = null;
    private float[] lastAim = new float[]{0f, 0f};
    private Set<Integer> pearlIdsBeforeThrow = new HashSet<>();

    private double tpPrevX, tpPrevY, tpPrevZ;

    private double lastX, lastY, lastZ;
    private boolean hasLastPos = false;
    private int settleTicks = 0;
    private boolean errorHandled = false;

    private int restingPearlId = -1;
    private int pearlStayTicks = 0;
    private int pearlMissingTicks = 0;
    private int retreatStableTicks = 0;
    private double retreatPrevX, retreatPrevY, retreatPrevZ;
    private boolean hasRetreatPrev = false;
    private boolean retreatLeaveWarned = false;
    private int retreatNoProgressTicks = 0;
    private double retreatPrevHx, retreatPrevHz;
    private boolean jumpHeld = false;
    /** True while a right-click input is held open (trapdoor toggle); released next tick */
    private boolean rightClickHeld = false;
    /** True after we jumped to clear our bounding box from the trapdoor before right-clicking */
    private boolean trapdoorJumped = false;
    /** Ticks spent stepping off a closed trapdoor we are standing on, so we can click it */
    private int trapdoorStepOffTicks = 0;
    /** Stall detection while walking back towards a trapdoor we hopped away from */
    private boolean trapdoorApproachInit = false;
    private int trapdoorApproachStall = 0;
    private double trapdoorApproachLastX = 0.0;
    private double trapdoorApproachLastZ = 0.0;
    /** Closest horizontal distance to the pit centre reached by the current sneak-in run */
    private double creepBestDist = Double.MAX_VALUE;
    /** How many times the sneak-in has been abandoned and restarted from further back */
    private int creepAttempts = 0;
    /** Where to go once the pearl restock finishes - normally the cargo flow */
    private MoverState afterRestock = MoverState.MOVE_TO_CHEST;
    /** Ticks spent climbing out of the pit shaft in a non-throw state */
    private int shaftEscapeTicks = 0;
    /** Ticks spent in EXIT_WATER getting out of the pit after arrival */
    private int pitEscapeTicks = 0;
    /** A "move restocked pearls into the hotbar" request is still in flight */
    private boolean restockHotbarPending = false;
    /** A "return overflow pearls to the chest" request is still in flight */
    private boolean restockOverflowPending = false;
    /** Consecutive restock cycles that ended without a single pearl */
    private int restockFailCount = 0;
    /** Logged once while we wait for the bot to land next to one of the two stashes */
    private boolean waitingForSideLogged = false;
    /** Loader bot we whispered for the pull (lower case), empty when not waiting for one */
    private String whisperTarget = "";
    /** True once the loader bot answered that whisper - after that we only wait for the pull */
    private boolean loaderReplied = false;
    /** Re-throws done to stock this side's chamber before whispering for the pull */
    private int whisperRepairAttempts = 0;
    /** Set while the current throw was started only to restock the chamber before a whisper */
    private boolean whisperRepairPending = false;
    /** Remaining ticks of hand-driven hopping to shake baritone loose */
    private int shakeOffTicks = 0;
    /**
     * The configured pit we were pulled away from. While {@link #detourPending} is set the
     * module runs one throw into the chamber we actually landed in, then restores these.
     */
    private int[] detourPit = null;
    private int[] detourTrapdoor = null;
    private boolean detourPending = false;
    private boolean shaftEscapeWarned = false;

    public StashMoverModule(StashMoverConfig config) {
        this.config = config;
    }

    @Override
    public boolean enabledSetting() {
        return config != null && config.enabled;
    }

    @Override
    public List<EventConsumer<?>> registerEvents() {
        return List.of(
            of(ClientBotTick.class, this::onTick),
            of(ClientBotTick.Starting.class, e -> reset()),
            of(ClientBotTick.Stopped.class, e -> reset()),
            of(WhisperChatEvent.class, this::onWhisper),
            of(PublicChatEvent.class, this::onPublicChat)
        );
    }

    @Override
    public void onEnable() {
        info("Enabled");
        reset();
        setState(MoverState.START);
    }

    @Override
    public void onDisable() {
        info("Disabled");
        jumpAssist(false);
        try {
            BARITONE.stop();
        } catch (Exception ignored) { }
        setState(MoverState.IDLE);
    }

    /**
     * Whispers from the loader bot count as an answer to the pull request. The content does not
     * matter - pearl bots answer with "tp", "ok" or just noise - only that it heard us, which is
     * what tells the wait loop not to send the same whisper again.
     */
    private void onWhisper(WhisperChatEvent event) {
        if (event.outgoing() || whisperTarget.isEmpty()) return;
        var sender = event.sender();
        if (sender == null || !whisperTarget.equalsIgnoreCase(sender.getName())) return;
        onLoaderReply("whisper", event.message());
    }

    /** Some pearl bots answer in public chat instead of whispering back - accept that too. */
    private void onPublicChat(PublicChatEvent event) {
        if (whisperTarget.isEmpty() || state != MoverState.AWAIT_TELEPORT) return;
        var sender = event.sender();
        if (sender == null || !whisperTarget.equalsIgnoreCase(sender.getName())) return;
        onLoaderReply("public chat", event.message());
    }

    private void onLoaderReply(String kind, String message) {
        String msg = message == null ? "" : message;
        if (msg.length() > 120) msg = msg.substring(0, 120) + "...";
        if (!loaderReplied) {
            info("{} answered our pull whisper ({}) - waiting for the pull", whisperTarget, kind);
        }
        dbg("loader reply: {}", msg);
        loaderReplied = true;
    }

    /**
     * Clear all per-cycle state and stop movement. Safe to call at any time.
     */
    public void reset() {
        try {
            BARITONE.stop();
        } catch (Exception ignored) { }
        jumpAssist(false);
        state = MoverState.IDLE;
        afterClose = MoverState.IDLE;
        stateTicks = 0;
        pathTicks = 0;
        openAttemptCooldown = 0;
        tpRetries = 0;
        throwRetries = 0;
        trapdoorRetries = 0;
        firstCargoCheck = true;
        restockSubmitted = false;
        currentPit = null;
        pitSurfaceY = -1;
        landingPos = null;
        echestPos = null;
        trapdoorPos = null;
        restingPearlId = -1;
        pearlStayTicks = 0;
        pearlMissingTicks = 0;
        retreatStableTicks = 0;
        retreatLeaveWarned = false;
        rightClickHeld = false;
        hasRetreatPrev = false;
        sideConfirmRequired = false;
        sideConfirmTicks = 0;
        pearlIdsBeforeThrow.clear();
        hasLastPos = false;
        settleTicks = 0;
        errorHandled = false;
        lastNearThrow = false;
        standApproachFailed = false;
        pearlSinkInit = false;
        whisperTarget = "";
        loaderReplied = false;
        whisperRepairAttempts = 0;
        whisperRepairPending = false;
        creepStuckTicks = 0;
        creepInit = false;
        riseStuckTicks = 0;
        riseInit = false;
        belowPitTicks = 0;
        moveToThrowRetries = 0;
        rethrowCount = 0;
        shakeOffTicks = 0;
        detourPit = null;
        detourTrapdoor = null;
        detourPending = false;
        creepAttempts = 0;
        creepBestDist = Double.MAX_VALUE;
    }

    public void resetForConfigChange() {
        if (containerOpen()) {
            if (!INVENTORY.hasActiveRequest()) {
                INVENTORY.submit(InventoryActionRequest.builder()
                    .owner(this).actions(new CloseContainer()).priority(INV_PRIORITY).build());
            }
        }
        reset();
        info("Config changed - module state reset");
    }

    public String getStatus() {
        String side = isInputSide ? "INPUT" : "OUTPUT";
        return state.name() + " @" + side + " (ticks=" + stateTicks + ") pit=" + fmt(currentPit);
    }

    public String getDebugInfo() {
        var p = CACHE.getPlayerCache();
        return getStatus()
            + "\npos=" + String.format("%.1f, %.1f, %.1f", p.getX(), p.getY(), p.getZ())
            + "\ntrapdoor=" + fmt(trapdoorPos)
            + "\nlanding=" + fmt(landingPos)
            + "\nechest=" + fmt(echestPos)
            + "\npearls=" + pearlCount()
            + "\nthrowRetries=" + throwRetries
            + "\nrestingPearlId=" + restingPearlId + " pearlStayTicks=" + pearlStayTicks
            + "\nsideConfirmRequired=" + sideConfirmRequired
            + "\nsettleTicks=" + settleTicks;
    }

    private void setState(MoverState s) {
        state = s;
        stateTicks = 0;
        pathTicks = 0;
        openAttemptCooldown = 0;
        awaitTrapdoorTimer.reset();
        awaitPearlSpawnTimer.reset();
        awaitThrowSettleTimer.reset();
        awaitPearlStableTimer.reset();
        awaitRetreatTimer.reset();
        awaitTeleportTimer.reset();
        retreatNoProgressTicks = 0;
        trapdoorStepOffTicks = 0;
        trapdoorApproachInit = false;
        trapdoorApproachStall = 0;
        waitingForSideLogged = false;
        if (s == MoverState.MOVE_TO_THROW) {
            // the sneak-in stall counter is only meaningful within one approach
            creepStuckTicks = 0;
            creepInit = false;
            creepBestDist = Double.MAX_VALUE;
        }
        logSnapshot(s);
    }

    /**
     * Everything needed to diagnose a stuck run in one line: where the bot is, where the pit
     * and trapdoor are, which way the trapdoor faces, what sits under the eyes, and every
     * arrival verdict the state machine uses.
     */
    private void logSnapshot(MoverState s) {
        if (!isEnabled() || currentPit == null) return;
        try {
            var p = CACHE.getPlayerCache();
            double hdx = p.getX() - (currentPit[0] + 0.5);
            double hdz = p.getZ() - (currentPit[2] + 0.5);
            info("SNAP {} | bot=({},{},{}) pit={} trap={} open={} facing={}"
                    + " | hdx={} hdz={} hDist={} downBlock={} blocks={}"
                    + " | nearThrow={} inPitCol={} fallIn={} away={} liquid={} stuck={}",
                s,
                String.format("%.2f", p.getX()), String.format("%.2f", p.getY()), String.format("%.2f", p.getZ()),
                fmt(currentPit), trapdoorPos != null ? fmt(trapdoorPos) : "null",
                trapdoorPos != null && isTrapdoorOpen(), trapdoorFacing(),
                String.format("%.2f", hdx), String.format("%.2f", hdz),
                String.format("%.2f", Math.sqrt(hdx * hdx + hdz * hdz)),
                downBlockName(), downBlockNames(),
                nearThrowPos(), botInPitColumn(), pearlWouldFallIn(),
                awayFromPit(), inLiquid(), isStuckInBlock());
        } catch (Exception e) {
            // never let diagnostics break the state machine
        }
    }

    // ==================== main loop ====================

    private void onTick(ClientBotTick event) {
        if (!isEnabled()) return;
        // Sitting in the 2b2t queue server (or dead): touch nothing at all. Right after a
        // restart the bot is at the world spawn on the queue server, millions of blocks from
        // both stashes, and none of the cargo flow makes sense there. Rewind to the entry
        // point so we pick up the real position once we are actually on the main server.
        if (Proxy.getInstance().isInQueue() || !CACHE.getPlayerCache().getThePlayer().isAlive()) {
            if (state != MoverState.START) {
                jumpAssist(false);
                BARITONE.stop();
                reset();
                setState(MoverState.START);
            }
            return;
        }

        if (config == null || !config.isConfigured()) {
            if (state != MoverState.IDLE) {
                warn("Config incomplete, staying idle. Missing: {}", config == null ? "config" : config.missing());
                setState(MoverState.IDLE);
            }
            return;
        }

        // ----- global teleport hook -----
        // Any large coordinate jump outside the whisper/wait flow means we got teleported
        // unexpectedly (e.g. the pearl bot pulled us early or a lag-back). Resync the side.
        var pc = CACHE.getPlayerCache();
        double px = pc.getX(), py = pc.getY(), pz = pc.getZ();
        if (hasLastPos) {
            double d2 = distSq(px, py, pz, lastX, lastY, lastZ);
            boolean expecting = state == MoverState.WHISPER
                || state == MoverState.AWAIT_TELEPORT
                || state == MoverState.IDLE
                || state == MoverState.START
                || state == MoverState.EVAL_SIDE
                || state == MoverState.ERROR;
            if (!expecting && d2 > (double) config.tpDetectDistance * config.tpDetectDistance) {
                landingPos = new int[]{(int) Math.floor(px), (int) Math.floor(py), (int) Math.floor(pz)};
                isInputSide = !isInputSide;
                sideConfirmRequired = true;
                sideConfirmTicks = 0;
                info("Global teleport detected, landed at {}, now presumptively {} side (must re-confirm)", fmt(landingPos), isInput() ? "INPUT" : "OUTPUT");
                BARITONE.stop();
                settleTicks = config.teleportSettleTicks;
                setState(MoverState.START);
            }
        }
        lastX = px;
        lastY = py;
        lastZ = pz;
        hasLastPos = true;

        if (settleTicks > 0) {
            settleTicks--;
            return;
        }

        stateTicks++;
        if (stateTicks % 20 == 1) {
            dbg("State={} ticks={} pos=({},{},{}) liquid={} stuck={} trapOpen={}",
                state, stateTicks, pc.getX(), pc.getY(), pc.getZ(),
                inLiquid(), isStuckInBlock(), trapdoorPos != null && isTrapdoorOpen());
        }
        // If we somehow ended up wedged in the pit shaft while in a state that assumes we
        // are already on the surface, climb out by hand FIRST: baritone cannot escape a 1x1
        // bubble column - it keeps recomputing MovementAscend / "No path found" and, failing
        // that, will happily mine the very trapdoor we need to get out.
        if (state != MoverState.MOVE_TO_THROW && state != MoverState.OPEN_TRAPDOOR
            && state != MoverState.AWAIT_TRAPDOOR_OPEN
            && state != MoverState.THROWING && state != MoverState.THROW_PEARL
            && state != MoverState.AWAIT_PEARL_SPAWNED && state != MoverState.AWAIT_THROW_SETTLE
            && state != MoverState.RETREAT && state != MoverState.AWAIT_PEARL_STABLE
            && state != MoverState.ERROR && state != MoverState.IDLE
            && escapeFromPitShaft()) {
            return;
        }
        try {
            switch (state) {
                case IDLE -> setState(MoverState.START);
                case START -> handleStart();
                case EVAL_SIDE -> handleEvalSide();
                case DETERMINE_PIT -> handleDeterminePit();
                case MOVE_TO_THROW -> handleMoveToThrow();
                case EXIT_WATER -> handleExitWater();
                case OPEN_TRAPDOOR -> handleOpenTrapdoor();
                case AWAIT_TRAPDOOR_OPEN -> handleAwaitTrapdoorOpen();
                case THROWING -> handleThrowing();
                case THROW_PEARL -> handleThrowPearl();
                case AWAIT_PEARL_SPAWNED -> handleAwaitPearlSpawned();
                case AWAIT_THROW_SETTLE -> handleAwaitThrowSettle();
                case RETREAT -> handleRetreat();
                case AWAIT_PEARL_STABLE -> handleAwaitPearlStable();
                case RESTOCK_PEARLS -> handleRestockPearls();
                case MOVE_TO_CHEST -> handleMoveToContainer(activeChest(), MoverState.CARGO);
                case CARGO -> handleCargo();
                case MOVE_TO_ECHEST -> handleMoveToEchest();
                case ECHEST -> handleEchest();
                case CLOSING -> handleClosing();
                case WHISPER -> handleWhisper();
                case AWAIT_TELEPORT -> handleAwaitTeleport();
                case ERROR -> handleError();
            }
        } catch (Throwable e) {
            error("Tick error in state {}: {}", state, e);
            setState(MoverState.ERROR);
        }
    }

    private boolean isInput() {
        return isInputSide;
    }

    /**
     * Escape routine for "the bot fell back into the pit shaft" in a state that normally
     * assumes we are already standing on the surface (MOVE_TO_CHEST, CARGO, ECHEST, ...).
     * Baritone is hopeless inside a 1x1 bubble column, so stop it and climb out with plain
     * input: open the shut trapdoor from underneath, otherwise ride the bubble column up
     * and step off. Returns true while it is handling the situation.
     */
    private boolean escapeFromPitShaft() {
        int[] pit = currentPit;
        if (pit == null) {
            shaftEscapeWarned = false;
            return false;
        }
        int trapY = trapdoorPos != null ? trapdoorPos[1] : pit[1];
        var p = CACHE.getPlayerCache();
        double hdx = p.getX() - (pit[0] + 0.5);
        double hdz = p.getZ() - (pit[2] + 0.5);
        // Wedged in the shaft: inside the chamber's cross-section AND below the rim. Outside the
        // cross-section it is normal ground - the restock and cargo chests sit just beyond
        // the rim and must be left alone.
        if (!inPitFootprint()) {
            shaftEscapeWarned = false;
            shaftEscapeTicks = 0;
            return false;
        }
        // at or above the rim we are not stuck, we are just standing at the hole
        if (p.getY() >= trapY - 0.1) {
            shaftEscapeWarned = false;
            shaftEscapeTicks = 0;
            return false;
        }
        BARITONE.stop();
        if (!shaftEscapeWarned) {
            shaftEscapeWarned = true;
            shaftEscapeTicks = 0;
            warn("Fell back into the pit shaft at y={} (rim y={}) - climbing out by hand, baritone stopped so it cannot mine the trapdoor",
                String.format("%.2f", p.getY()), trapY);
        }
        int[] above = trapdoorAboveBot();
        if (above != null && !isTrapdoorOpenAt(above)) {
            overrideTrapdoor = above;
            info("Under the shut trapdoor at {}, opening it from below", fmt(above));
            setState(MoverState.OPEN_TRAPDOOR);
            return true;
        }
        shaftEscapeTicks++;
        if (shaftEscapeTicks > 200) {
            error("Still wedged in the pit shaft after {} ticks (y={}, rim={}) - halting",
                shaftEscapeTicks, String.format("%.2f", p.getY()), trapY);
            setState(MoverState.ERROR);
            return true;
        }
        submitRiseInput();
        return true;
    }

    /** Verbose log: promoted to info when `sm debug` is on, otherwise module debug level */
    private void dbg(String msg, Object... args) {
        if (config != null && config.debug) {
            info(msg, args);
        } else {
            debug(msg, args);
        }
    }

    /**
 * Entry point: clear the per-visit variables and move on. All deciding happens in EVAL_SIDE.
 */
    private void handleStart() {
        tpRetries = 0;
        throwRetries = 0;
        trapdoorRetries = 0;
        firstCargoCheck = true;
        restockSubmitted = false;
        currentPit = null;
        pitSurfaceY = -1;
        echestPos = null;
        trapdoorPos = null;
        restingPearlId = -1;
        pearlStayTicks = 0;
        pearlMissingTicks = 0;
        retreatStableTicks = 0;
        hasRetreatPrev = false;
        pearlIdsBeforeThrow.clear();
        standApproachFailed = false;
        pearlSinkInit = false;
        whisperTarget = "";
        loaderReplied = false;
        whisperRepairAttempts = 0;
        whisperRepairPending = false;
        setState(MoverState.EVAL_SIDE);
    }

    /**
     * Decide which side we are on, or stand still until we can.
     *
     * <p>A coordinate jump drops us wherever we were standing when the pull happened - and a
     * fresh login lands on the queue server's world spawn, roughly eleven million blocks from
     * both stashes. Baritone cannot path that far, so all it does is recompute and fail for as
     * long as we let it, and neither side is reachable from there anyway.
     */
    private void handleEvalSide() {
        double nearest = nearestSideDistSq();
        if (nearest > MAX_SIDE_DIST_SQ) {
            if (!waitingForSideLogged) {
                waitingForSideLogged = true;
                warn("Neither stash is reachable from here (nearest is {} blocks away) - idling until we land next to one",
                    (long) Math.sqrt(nearest));
                logBotNeighborhood();
            }
            return;
        }
        waitingForSideLogged = false;

        Boolean detected = detectSide();
        if (detected != null) {
            isInputSide = detected;
            sideConfirmRequired = false;
            sideConfirmTicks = 0;
        } else if (sideConfirmRequired) {
            // After a teleport we must re-confirm the area from positive evidence
            // before being allowed to touch any cargo.
            if (++sideConfirmTicks > SIDE_CONFIRM_TIMEOUT_TICKS) {
                error("Cannot confirm which area (input/output) after teleport - check that inputChest/outputChest are set. Halting.");
                setState(MoverState.ERROR);
                return;
            }
            dbg("Waiting to re-confirm area after teleport ({}/{})", sideConfirmTicks, SIDE_CONFIRM_TIMEOUT_TICKS);
            return;
        } else {
            // Keep the side established by the last teleport when nothing is conclusive
            dbg("Could not determine side by chests/markers, keeping {} side", isInput() ? "INPUT" : "OUTPUT");
        }
        info("Start visit at {}", isInput() ? "INPUT" : "OUTPUT");
        setState(MoverState.DETERMINE_PIT);
    }

/** Squared distance to the nearer configured stash, or -1 when neither side has one. */
private double nearestSideDistSq() {
        int[] inPos = blockPosToInts(config.inputChest);
        int[] outPos = blockPosToInts(config.outputChest);
        if (inPos == null && outPos == null) return -1.0;
        if (inPos == null) return distSqFromBot(outPos);
        if (outPos == null) return distSqFromBot(inPos);
        return Math.min(distSqFromBot(inPos), distSqFromBot(outPos));
    }

    /**
     * Determine the current side from positive evidence only: nearest of the two known chests,
     * then a nearby marker item frame (redstone dust -> INPUT, redstone block -> OUTPUT), then a
     * single known chest within scan range. Returns null when nothing is conclusive.
     */
    private Boolean detectSide() {
        int[] inPos = blockPosToInts(config.inputChest);
        int[] outPos = blockPosToInts(config.outputChest);
        if (inPos != null && outPos != null) {
            return distSqFromBot(inPos) <= distSqFromBot(outPos);
        }

        int[] dustChest = detectChestByMarker(ItemRegistry.REDSTONE.id());
        int[] blockChest = detectChestByMarker(ItemRegistry.REDSTONE_BLOCK.id());
        if (dustChest != null && blockChest == null) return true;
        if (blockChest != null && dustChest == null) return false;
        if (dustChest != null) {
            return distSqFromBot(dustChest) <= distSqFromBot(blockChest);
        }

        double nearSq = (double) config.frameScanRadius * config.frameScanRadius;
        boolean inNear = inPos != null && distSqFromBot(inPos) <= nearSq;
        boolean outNear = outPos != null && distSqFromBot(outPos) <= nearSq;
        if (inNear && !outNear) return true;
        if (outNear && !inNear) return false;
        return null;
    }

    // ==================== pearl pit detection ====================

    private void handleDeterminePit() {
        if (currentPit == null) {
            // manual config wins
            int[] configured = blockPosToInts(isInput() ? config.inputPearl : config.outputPearl);
            if (configured != null) {
                currentPit = configured;
                trapdoorPos = configured;
                info("Pearl pit (configured) at {}", fmt(currentPit));
            } else {
                currentPit = findPitFromPearlFrame();
                if (currentPit == null) currentPit = findPearlBotPit();
                if (currentPit == null) currentPit = findOwnPearl();
                if (currentPit == null) currentPit = currentBlockOr(landingPos);
                if (currentPit == null) {
                    error("Could not determine the pearl pit for the {} side - set it with 'stashMover {}Pearl <x> <y> <z>' or place an ender-pearl item frame nearby. Halting.",
                        isInput() ? "input" : "output", isInput() ? "input" : "output");
                    setState(MoverState.ERROR);
                    return;
                }
                info("Pearl pit at {}", fmt(currentPit));
                trapdoorPos = findNearestTrapdoor(currentPit, 2);
                if (trapdoorPos != null) {
                    info("Trapdoor at {} becomes the final pearl pit", fmt(trapdoorPos));
                    pitSurfaceY = currentPit[1];
                    currentPit = trapdoorPos;
                }
            }
            int own = ownPearlsInPit();
            if (own > 0 || pearlRestingInPit()) {
                info("{} own pearl(s) already in the pit, skipping the throw", own);
                restingPearlId = lastFoundPearlId != -1 ? lastFoundPearlId : ownRisenPearlIdAtPit();
                pearlStayTicks = 0;
                pearlMissingTicks = 0;
                retreatStableTicks = 0;
                retreatLeaveWarned = false;
                hasRetreatPrev = false;
                pearlIdsBeforeThrow.clear();
                setState(MoverState.RETREAT);
                return;
            }
        }
        dbg("DETERMINE_PIT done: pit={} trapdoor={}", fmt(currentPit), fmt(trapdoorPos));
        // the stand cell is derived from the pit and its trapdoor, so a new pit gets a new try
        standApproachFailed = false;

        // A pull always drops us into a chamber, and a side usually has two of them: the
        // configured pit plus the one whose pearl the loader bot just consumed. Identify the
        // column we are actually standing in, climb out of it, and stock it with a throw of
        // its own before walking over to the configured pit - otherwise that chamber stays
        // empty and the loader has nothing to pull us with next time.
        int[] arrival = arrivalChamber();
        if (arrival != null && !sameColumn(arrival, currentPit)) {
            int stocked = ownPearlsInChamber(arrival);
            if (stocked > 0) {
                info("Pulled into the neighbouring chamber {} which already holds {} of our pearls - "
                    + "walking over to the configured pit {}", fmt(arrival), stocked, fmt(currentPit));
            } else {
                info("Pulled into the neighbouring chamber {} - stocking it with a pearl first, "
                    + "then heading to the configured pit {}", fmt(arrival), fmt(currentPit));
                detourPit = currentPit;
                detourTrapdoor = trapdoorPos;
                currentPit = arrival;
                int[] lid = trapdoorOverColumn(arrival[0], arrival[2], arrival[1]);
                trapdoorPos = lid != null ? lid : findNearestTrapdoor(arrival, 3);
                detourPending = true;
                pitSurfaceY = -1;
                echestPos = null;
                restingPearlId = -1;
                retreatStableTicks = 0;
                pearlRiseInit = false;
                belowPitTicks = 0;
                moveToThrowRetries = 0;
                pearlIdsBeforeThrow.clear();
            }
        }

        // Being pulled into the chamber drops us either on top of the (closed) trapdoor or
        // wedged underneath it. Step out of the pit FIRST - open the trapdoor, hop out along
        // its facing - and only then walk back in to the throw position.
        if (trappedInPitOnArrival()) {
            info("Landed {} - floating up and hopping out along the trapdoor facing before doing anything else",
                CACHE.getPlayerCache().getY() < currentPit[1] - 0.1
                    ? "under the trapdoor" : "on the closed trapdoor");
            pitEscapeTicks = 0;
            setState(MoverState.EXIT_WATER);
            return;
        }
        setState(MoverState.MOVE_TO_THROW);
    }

    /**
 * Any trapdoor that is still sealing the chamber the bot is in, or null when they are all
 * open. A pit can be covered by two lids (one at the pit level, one above it) and we can land
 * under either of them, so scan the bot's own column top-down and return the first shut one.
 */
private int[] shutChamberTrapdoor() {
        var p = CACHE.getPlayerCache();
        int bx = (int) Math.floor(p.getX());
        int bz = (int) Math.floor(p.getZ());
        int feet = (int) Math.floor(p.getY());
        for (int y = feet + 2; y >= feet - 2; y--) {
            var b = WORLD_BLOCK(p.getX(), y, p.getZ());
            if (b == null || !b.name().endsWith("_trapdoor")) continue;
            if (isTrapdoorOpenAt(new int[]{bx, y, bz})) continue;
            return new int[]{bx, y, bz};
        }
        return null;
    }

/**
 * The chamber the bot is standing in right now, expressed as the position to use as its pit:
 * the trapdoor at the top of its bubble column (or the column block itself when there is no
 * lid), and null when the bot is not standing in a chamber at all.
 *
 * <p>Two arrival shapes have to be recognised, and a pull produces one of them: inside the
 * bubble column (the pearl chamber proper) or on top of the closed lid above it. Walking
 * around on the surface is not an arrival, which is why this looks at the bot's own column
 * instead of scanning a radius around the configured pit.
 */
private int[] arrivalChamber() {
        var p = CACHE.getPlayerCache();
        int bx = (int) Math.floor(p.getX());
        int bz = (int) Math.floor(p.getZ());
        int feet = (int) Math.floor(p.getY());
        // 1. the column of the chamber we are in: our own cell first, then the rest of the
        //    3x3 footprint, because a pull can drop us on the rim next to the shaft
        int[] col = bubbleColumnNear(bx, feet, bz);
        if (col != null) {
            int[] lid = trapdoorOverColumn(col[0], col[2], col[1]);
            return lid != null ? lid : col;
        }
        // 2. standing on (or wedged in) the closed lid above the column
        int[] lid = trapdoorOverColumn(bx, bz, feet);
        if (lid != null && Math.abs(lid[1] - feet) <= 2) return lid;
        return null;
}

    /**
     * The nearest bubble column inside the 3x3 footprint around the given cell, or null. This
     * is what identifies a pearl chamber: the column is the part that holds the pearl, so the
     * chamber's X/Z come from it rather than from wherever the bot happened to land.
     */
    private int[] bubbleColumnNear(int bx, int feet, int bz) {
        int[][] offsets = {{0, 0}, {0, 1}, {0, -1}, {1, 0}, {-1, 0},
            {1, 1}, {-1, -1}, {1, -1}, {-1, 1}};
        for (int[] off : offsets) {
            int x = bx + off[0];
            int z = bz + off[1];
            for (int y = feet + 1; y >= feet - 2; y--) {
                var b = WORLD_BLOCK(x + 0.5, y, z + 0.5);
                if (b == null) break;
                String n = b.name();
                if (n.contains("bubble_column")) return new int[]{x, y, z};
                if (n.endsWith("_trapdoor") || isSolidBlock(b)) break;
            }
        }
        return null;
    }

    /**
     * The trapdoor inside one column closest to the given Y. A chamber can carry two lids
     * (one at the pit level, one above it) and either of them can be the one we need, so
     * "nearest" is the right pick - always taking the top one would make a deep landing look
     * like it is above the rim.
     */
    private int[] trapdoorOverColumn(int x, int z, int nearY) {
        int[] best = null;
        for (int y = nearY + PIT_SHAFT_DEPTH + 2; y >= nearY - 3; y--) {
            var b = WORLD_BLOCK(x + 0.5, y, z + 0.5);
            if (b == null || !b.name().endsWith("_trapdoor")) continue;
            if (best == null || Math.abs(y - nearY) < Math.abs(best[1] - nearY)) best = new int[]{x, y, z};
        }
        return best;
    }

    /** True when both positions sit in the same pit column (same block X/Z). */
    private static boolean sameColumn(int[] a, int[] b) {
        return a != null && b != null && a[0] == b[0] && a[2] == b[2];
    }

    /**
     * True when we cannot simply walk to the throw position because we are inside the pit:
     * below the rim (wedged in the shaft) or standing on the closed trapdoor that seals it.
     */
    private boolean trappedInPitOnArrival() {
        if (currentPit == null) return false;
        if (!nearPitColumn()) return false;
        var p = CACHE.getPlayerCache();
        int trapY = trapdoorPos != null ? trapdoorPos[1] : currentPit[1];
        if (p.getY() < trapY - 0.1) return true;
        return botInPitColumn() && !isTrapdoorOpen();
    }

    /**
     * Find the current side's pearl restock chest marker frame (an item frame
     * holding an ender pearl). The frame's own block is the pearl pit region;
     * the caller then converges onto the trapdoor found near it.
     */
    private int[] findPitFromPearlFrame() {
        var p = CACHE.getPlayerCache();
        double maxDistSq = (double) config.frameScanRadius * config.frameScanRadius;
        int[] best = null;
        double bestD = Double.MAX_VALUE;
        for (Entity e : CACHE.getEntityCache().getEntities().values()) {
            var type = e.getEntityType();
            if (type != EntityType.ITEM_FRAME && type != EntityType.GLOW_ITEM_FRAME) continue;
            ItemStack item = e.getMetadataValue(8, MetadataTypes.ITEM, ItemStack.class);
            if (item == null || item.getId() != ItemRegistry.ENDER_PEARL.id()) continue;
            double d = distSq(p.getX(), p.getY(), p.getZ(), e.getX(), e.getY(), e.getZ());
            if (d > maxDistSq) continue;
            int[] pit = new int[]{(int) Math.floor(e.getX()), (int) Math.floor(e.getY()), (int) Math.floor(e.getZ())};
            double pd = distSq(p.getX(), p.getY(), p.getZ(), pit[0] + 0.5, pit[1] + 0.5, pit[2] + 0.5);
            if (pd < bestD) {
                bestD = pd;
                best = pit;
            }
        }
        if (best != null) debug("Found pearl pit marker frame at {}", fmt(best));
        return best;
    }

    /**
     * Find the current side's pearl bot (by username) and use its position as the pit.
     */
    private int[] findPearlBotPit() {
        String name = StashMoverConfig.normalizeUsername(isInput() ? config.inputBot : config.outputBot);
        if (name.isEmpty()) return null;
        var entry = CACHE.getTabListCache().getFromName(name).orElse(null);
        if (entry == null) return null;
        var player = CACHE.getEntityCache().get(entry.getProfile().getId());
        if (player == null) return null;
        int[] pos = new int[]{(int) Math.floor(player.getX()), (int) Math.floor(player.getY()), (int) Math.floor(player.getZ())};
        dbg("Found pearl bot {} at {}", name, fmt(pos));
        return pos;
    }

    /**
     * Find the bot's own thrown ender pearl entity within pearlDetectRadius.
     * The pit is the block where that pearl rests.
     */
    private int[] findOwnPearl() {
        var p = CACHE.getPlayerCache();
        int selfId = p.getEntityId();
        double px = p.getX(), py = p.getY(), pz = p.getZ();
        double maxDistSq = (double) config.pearlDetectRadius * config.pearlDetectRadius;
        int[] best = null;
        double bestD = Double.MAX_VALUE;
        for (Entity e : CACHE.getEntityCache().getEntities().values()) {
            if (e.getEntityType() != EntityType.ENDER_PEARL) continue;
            if (!(e.getObjectData() instanceof ProjectileData pd) || pd.getOwnerId() != selfId) continue;
            double d = distSq(px, py, pz, e.getX(), e.getY(), e.getZ());
            if (d > maxDistSq) continue;
            if (d < bestD) {
                bestD = d;
                best = new int[]{(int) Math.floor(e.getX()), (int) Math.floor(e.getY()), (int) Math.floor(e.getZ())};
            }
        }
        if (best != null) debug("Found own ender pearl at {}", fmt(best));
        return best;
    }

    private int[] currentBlockOr(int[] fallback) {
        var p = CACHE.getPlayerCache();
        int[] cur = new int[]{(int) Math.floor(p.getX()), (int) Math.floor(p.getY()), (int) Math.floor(p.getZ())};
        if (fallback != null && distSqFromBot(fallback) < distSqFromBot(cur)) return fallback;
        return cur;
    }

    // ==================== getting to the pit ====================

    private void handleMoveToThrow() {
        var p = CACHE.getPlayerCache();
        if (stateTicks <= 1) logPitGeometry();
        double hdx = p.getX() - (currentPit[0] + 0.5);
        double hdz = p.getZ() - (currentPit[2] + 0.5);
        double horizSq = hdx * hdx + hdz * hdz;
        int trapY = trapdoorPos != null ? trapdoorPos[1] : currentPit[1];
        boolean aboveWater = p.getY() >= trapY - 0.1;

        // stuck below the pit (dropped into the shaft or wedged under it): the trapdoor must be
        // open or we are walled in, then climb back up to throw height
        if (!aboveWater && horizSq < 4.0) {
            BARITONE.stop();
            // rule is simply "swim up, and open whatever trapdoor is above us" - it may be
            // a neighbouring chamber's trapdoor, which is still the one sealing us in
            int[] above = trapdoorAboveBot();
            if (above != null && !isTrapdoorOpenAt(above)) {
                overrideTrapdoor = above;
                info("Under the shut trapdoor at {}, opening it from below", fmt(above));
                setState(MoverState.OPEN_TRAPDOOR);
                return;
            }
            overrideTrapdoor = null;
            // guard against sinking forever in a neighbouring chamber
            if (belowPitTicks > 300) {
                error("Still below the pit after {} ticks (y={}, pitY={}), giving up",
                    belowPitTicks, String.format("%.2f", p.getY()), trapY);
                setState(MoverState.ERROR);
                return;
            }
            belowPitTicks++;
            submitRiseInput();
            return;
        }
        belowPitTicks = 0;
        overrideTrapdoor = null;

        // Once we are up at rim level the restock chest (a few blocks away) is reachable, so top the
        // pearls up on the way in rather than on an empty inventory. Only skipped while we are
        // still down in the shaft, where baritone cannot walk anywhere.
        if (aboveWater && restockBeforeThrow()) {
            return;
        }
        if (inLiquid() || (isStuckInBlock() && nearPitColumn())) {
            BARITONE.stop();
            if (isStuckInBlock() && stateTicks <= 2) {
                info("Stuck near the pit | bot=({},{},{}) horizSq={} blocks={}",
                    String.format("%.2f", p.getX()), String.format("%.2f", p.getY()),
                    String.format("%.2f", p.getZ()),
                    String.format("%.2f", horizSq), downBlockNames());
            }
            setState(MoverState.EXIT_WATER);
            return;
        }

        // debug: log only when the arrival verdict flips, so tests stay readable
        boolean near = nearThrowPos();
        if (near != lastNearThrow) {
            lastNearThrow = near;
            var db = downBlockFromEyes();
            info("nearThrow={} bot=({},{},{}) hdx={} hdz={} y={} downBlock={} facing={} inPit={}",
                near,
                String.format("%.2f", p.getX()), String.format("%.2f", p.getY()), String.format("%.2f", p.getZ()),
                String.format("%.2f", hdx), String.format("%.2f", hdz), String.format("%.2f", p.getY()),
                db == null ? "none" : db.name(), trapdoorFacing(), botInPitColumn());
        }

        // Before sneaking in, walk to the cell a player would start from: the block next to the
        // pit on the trapdoor's facing side, at rim level. Hopping out of the shaft can leave us
        // anywhere around it, and letting baritone pick "the nearest cell to the pit" is what
        // used to leave the bot on the far side, where the sneak-in has to cross the rim.
        int[] stand = aboveWater ? facingStandBlock() : null;
        if (!near && stand != null && !standApproachFailed) {
            double sdx = (stand[0] + 0.5) - p.getX();
            double sdz = (stand[2] + 0.5) - p.getZ();
            double sd2 = sdx * sdx + sdz * sdz;
            if (sd2 > STAND_ARRIVED_SQ) {
                if (stateTicks % 40 == 1 || sd2 > STAND_BARITONE_SQ) {
                    dbg("Heading for the stand cell {} on the {} side of the pit (dist={})",
                        fmt(stand), trapdoorFacing(), String.format("%.2f", Math.sqrt(sd2)));
                }
                if (sd2 > STAND_BARITONE_SQ) {
                    // more than a couple of blocks: let baritone route around the shaft
                    if (shakeOffTicks > 0) {
                        shakeOffTicks--;
                        submitInput(Input.builder().pressingForward(true).jumping(true).build(),
                            yawTo(sdx, sdz), 0f);
                        return;
                    }
                    if (!BARITONE.getCustomGoalProcess().isActive()) {
                        BARITONE.pathTo(new GoalBlock(stand[0], stand[1], stand[2]));
                    }
                    if (++pathTicks > config.pathTimeoutTicks) {
                        pathTicks = 0;
                        onStandApproachTimeout(stand);
                    }
                    return;
                }
                // close: walk the last block or two by hand, no baritone. The rim sits one block
                // above the surrounding ground, so hop while the feet are still below it.
                BARITONE.stop();
                boolean stepUp = stand[1] > (int) Math.floor(p.getY() + 0.01);
                submitInput(Input.builder().pressingForward(true).jumping(stepUp).build(),
                    yawTo(sdx, sdz), 0f);
                if (++pathTicks > STAND_HAND_TIMEOUT_TICKS) {
                    pathTicks = 0;
                    onStandApproachTimeout(stand);
                }
                return;
            }
            pathTicks = 0;
        }

        // close range: baritone already parked us on a standable cell beside the pit,
        // so from here on everything is hand-driven input
        if (horizSq < 9.0 && aboveWater) {
            BARITONE.stop();
            // open the trapdoor only from right beside it: the BlockPosition raycast that
            // carries the useItemOn misses the block from further than ~1.5 blocks
            boolean besidePit = horizSq <= 2.25;
            if (trapdoorPos != null && !isTrapdoorOpen() && besidePit) {
                double thdx = p.getX() - (trapdoorPos[0] + 0.5);
                double thdz = p.getZ() - (trapdoorPos[2] + 0.5);
                if (Math.abs(thdx) < 0.4 && Math.abs(thdz) < 0.4 && stateTicks <= 60) {
                    if (stateTicks <= 2) {
                        info("Step aside: standing on the closed trapdoor dx={} dz={}",
                            String.format("%.2f", thdx), String.format("%.2f", thdz));
                    }
                    submitInput(Input.builder().pressingForward(true).build(), stepAsideYaw(), 0f);
                    return;
                }
                info("Beside the pit with the trapdoor closed, opening it first");
                setState(MoverState.OPEN_TRAPDOOR);
                return;
            }

            // arrived: straight-down look sees the pit water -> throw
            if (near) {
                // Arrived at the throw position. Deliberately the one place that counts ANY
                // pearl rather than only our own: this is the physical question "is there
                // already something down there?", and throwing a second one on top of a pearl
                // somebody else parked is how one run once left nine of them stacked in the
                // chamber.
                int inPit = pearlsInPit();
                if (inPit > 0) {
                    int ownHere = ownPearlsInPit();
                    info("Chamber already holds {} pearl(s) ({} of them ours) - not throwing another one",
                        inPit, ownHere);
                    if (ownHere == 0) {
                        warn("The pearl in the chamber is not ours - the loader may not be able to use it");
                    }
                    restingPearlId = lastFoundPearlId != -1 ? lastFoundPearlId : ownRisenPearlIdAtPit();
                    retreatStableTicks = 0;
                    pearlRiseInit = false;
                    retreatLeaveWarned = false;
                    hasRetreatPrev = false;
                    pearlMissingTicks = 0;
                    creepAttempts = 0;
                    setState(MoverState.RETREAT);
                    return;
                }
                // Being over the column is not the same as being over its middle: lean toward the
                // centre for as long as the guard lets us before the pearl is dropped. Inside the
                // column itself there is nothing to lean in from - and pressing forward while
                // floating there can push us back out of the bubble column - so hold and throw.
                if (!botInPitColumn()) {
                    trackCreepProgress(Math.sqrt(horizSq));
                    if (!throwCentered() && creepStuckTicks <= CREEP_CENTER_HOLD_TICKS) {
                        submitInput(Input.builder().pressingForward(true).sneaking(true).build(), pitFacingYaw(), 0f);
                        return;
                    }
                }
                moveToThrowRetries = 0;
                creepStuckTicks = 0;
                creepInit = false;
                creepAttempts = 0;
                creepBestDist = Double.MAX_VALUE;
                info("Throw position reached ({}), going to THROWING",
                    botInPitColumn() ? "inside the pit" : "sneaked in to the pit rim");
                setState(MoverState.THROWING);
                return;
            }
            // case 1: already standing in the pit column - no sneaking needed, just hold
            if (botInPitColumn()) {
                submitInput(Input.builder().build(), pitFacingYaw(), 90f);
                if (stateTicks % 20 == 0) {
                    info("Standing in the pit column but not throw-ready yet (waited={} ticks) | nearThrow={} fallIn={} y={}",
                        stateTicks, near, pearlWouldFallIn(), String.format("%.2f", p.getY()));
                }
                if (stateTicks > 200) {
                    error("Standing in the pit column but never became throw-ready after {} ticks",
                        stateTicks);
                    setState(MoverState.ERROR);
                    return;
                }
                return;
            }
            // case 2: outside the pit - sneak toward the center by hand (never baritone);
            // the sneak edge guard stops us exactly where the feet hang over the pit
            //
            // Stall detection must be based on NET progress toward the pit, not on the
            // per-tick position delta: pressing forward into the sneak edge guard makes the
            // guard shove us back, so the bot oscillates by a few centimetres forever and a
            // delta based counter would be reset on every single tick - that is the jitter.
            double creepDist = Math.sqrt(horizSq);
            trackCreepProgress(creepDist);
            // Just being over the column's edge is not good enough: the pearl drops straight down
            // from the eyes, so keep leaning in until we are close to the middle - it should land
            // in the centre of the chamber, not against a wall. Only when leaning in has clearly
            // stopped helping (the lid/trapdoor box pins the feet and the server rejects any
            // further movement) do we throw from where we got stuck.
            if (pearlWouldFallIn()) {
                if (throwCentered() || creepStuckTicks > CREEP_CENTER_HOLD_TICKS) {
                    info("Throw position centred or pinned, throwing from here | hDist={}",
                        String.format("%.2f", creepDist));
                    creepAttempts = 0;
                    setState(MoverState.THROWING);
                    return;
                }
                submitInput(Input.builder().pressingForward(true).sneaking(true).build(), pitFacingYaw(), 0f);
                return;
            }
            // The view is not on the pit yet. Stop dead, look straight down and re-check instead of
            // grinding the guard - and if that does not line us up either, back off and sneak in
            // again from a fresh run.
            if (creepStuckTicks <= 5) {
                submitInput(Input.builder().pressingForward(true).sneaking(true).build(), pitFacingYaw(), 0f);
                return;
            }
            {
                submitInput(Input.builder().build(), pitFacingYaw(), 90f);
                if (creepStuckTicks == 6) {
                    info("Creep blocked at hDist={}, standing still and checking the view",
                        String.format("%.2f", creepDist));
                    logBotNeighborhood();
                }
                // still no clean line on the pit after standing still for a while: stand up,
                // step back and sneak in again from a fresh run instead of grinding the guard
                if (creepStuckTicks > CREEP_HOLD_TICKS) {
                    if (++creepAttempts > MAX_CREEP_ATTEMPTS) {
                        error("Never lined up with the pit (hDist={}) after {} sneak approaches",
                            String.format("%.2f", creepDist), MAX_CREEP_ATTEMPTS);
                        setState(MoverState.ERROR);
                        return;
                    }
                    info("No clear view from hDist={}, standing up and backing off to sneak in again ({}/{})",
                        String.format("%.2f", creepDist), creepAttempts, MAX_CREEP_ATTEMPTS);
                    creepStuckTicks = 0;
                    creepInit = false;
                    creepBestDist = Double.MAX_VALUE;
                    submitInput(Input.builder()
                        .pressingBack(true)
                        .jumping(true)
                        .build(), pitFacingYaw(), 0f);
                }
                return;
            }
        }
        // far: baritone walks to the PIT itself - it parks the bot on a standable cell right
        // next to the hole, which is exactly the spot we then sneak in from
        if (creepInit) {
            double mx = p.getX() - creepLastHx;
            double mz = p.getZ() - creepLastHz;
            if (mx * mx + mz * mz < 0.0004) {
                creepStuckTicks++;
            } else {
                creepStuckTicks = 0;
            }
        } else {
            creepInit = true;
        }
        creepLastHx = p.getX();
        creepLastHz = p.getZ();
        if (creepStuckTicks > 60) {
            creepStuckTicks = 0;
            creepInit = false;
            BARITONE.stop();
            if (++moveToThrowRetries > 5) {
                error("Could not path to the pearl pit after 5 retries | bot=({},{}) hDist={} facing={}",
                    String.format("%.2f", p.getX()), String.format("%.2f", p.getZ()),
                    String.format("%.2f", Math.sqrt(horizSq)), trapdoorFacing());
                setState(MoverState.ERROR);
                return;
            }
            warn("No progress walking to the pit, shaking loose then retrying ({}/5) | bot=({}) hDist={} facing={}",
                moveToThrowRetries,
                String.format("%.2f", p.getX()), String.format("%.2f", p.getZ()),
                String.format("%.2f", Math.sqrt(horizSq)), trapdoorFacing());
            logBotNeighborhood();
            // Re-issuing the same goal does nothing when baritone parks us on top of a
            // NEIGHBOURING chamber's trapdoor - a one-block island where a path is computed
            // but the first step can never execute. Hop toward the pit by hand for a moment to
            // shake loose, exactly like a player would, and only then let baritone path again.
            shakeOffTicks = SHAKE_OFF_TICKS;
        }
        if (shakeOffTicks > 0) {
            shakeOffTicks--;
            submitInput(Input.builder()
                .pressingForward(true)
                .jumping(true)
                .build(), pitFacingYaw(), 0f);
            return;
        }
        if (!BARITONE.getCustomGoalProcess().isActive()) {
            if (pathTicks++ > config.pathTimeoutTicks) {
                pathTicks = 0;
                if (++moveToThrowRetries > 5) {
                    error("Could not path to the throw position after 5 retries");
                    setState(MoverState.ERROR);
                    return;
                }
                warn("Path to pit timed out, retrying ({})", moveToThrowRetries);
            }
            BARITONE.pathTo(new GoalGetToBlock(
                new BlockPos(currentPit[0], currentPit[1], currentPit[2])));
        }
    }

    private String downBlockName() {
        var b = downBlockFromEyes();
        return b == null ? "none" : b.name();
    }

    /** Names of the blocks at the bot's feet and head - shows what it is wedged in. */
    private String downBlockNames() {
        var p = CACHE.getPlayerCache();
        int py = (int) Math.floor(p.getY());
        var feet = WORLD_BLOCK(p.getX(), py, p.getZ());
        var head = WORLD_BLOCK(p.getX(), py + 1, p.getZ());
        return "feet=" + (feet == null ? "null" : feet.name())
            + " head=" + (head == null ? "null" : head.name());
    }

    /**
     * The pearl is released straight down from the eyes, so the bot is only in position when
     * the eyes sit over the pit column and nothing solid sits between them and the pit -
     * the pearl then falls into the chamber (which is a bubble column, not water).
     */
    private boolean pearlWouldFallIn() {
        var p = CACHE.getPlayerCache();
        int ex = (int) Math.floor(p.getX());
        int ez = (int) Math.floor(p.getZ());
        if (ex != currentPit[0] || ez != currentPit[2]) return false;
        if (trapdoorPos != null && !isTrapdoorOpen()) return false;
        int trapY = trapdoorPos != null ? trapdoorPos[1] : currentPit[1];
        for (int y = (int) Math.floor(p.getEyeY()); y > trapY; y--) {
            var b = WORLD_BLOCK(ex + 0.5, y, ez + 0.5);
            if (b == null) continue;
            String n = b.name();
            if (n.contains("air") || n.endsWith("_trapdoor") || n.endsWith("_sign")) continue;
            return false;
        }
        return true;
    }

    /** Dump the blocks around the pit so the layout does not have to be guessed. */
    private void logPitGeometry() {
        int cx = currentPit[0], cz = currentPit[2];
        StringBuilder sb = new StringBuilder();
        for (int dz = 1; dz >= -1; dz--) {
            sb.append("\n  dz=").append(dz).append(": ");
            for (int dx = -1; dx <= 1; dx++) {
                StringBuilder col = new StringBuilder();
                for (int y = currentPit[1] + 2; y >= currentPit[1] - 3; y--) {
                    var b = WORLD_BLOCK(cx + dx + 0.5, y, cz + dz + 0.5);
                    if (b == null) continue;
                    String s = shortName(b.name());
                    if (s.equals("air")) continue;
                    col.append(y).append(':').append(s).append(' ');
                }
                sb.append(dx == 0 ? "[" : "").append(col.length() == 0 ? "- " : col.toString())
                    .append(dx == 0 ? "] " : " ");
            }
        }
        info("Pit 3D around {} trapOpen={} facing={} (columns dx=-1..1, pit at dx=0):{}",
            fmt(currentPit), trapdoorPos != null && isTrapdoorOpen(), trapdoorFacing(), sb);
    }

    /** Dump the 3x3x3 block cube around the bot - shows whatever wall it is stuck against. */
    private void logBotNeighborhood() {
        try {
            var p = CACHE.getPlayerCache();
            int bx = (int) Math.floor(p.getX());
            int by = (int) Math.floor(p.getY());
            int bz = (int) Math.floor(p.getZ());
            StringBuilder sb = new StringBuilder();
            for (int dy = 1; dy >= -1; dy--) {
                sb.append("\n  y=").append(by + dy).append("  ");
                for (int dz = -1; dz <= 1; dz++) {
                    sb.append("z").append(dz > 0 ? "+" : "").append(dz).append(":");
                    for (int dx = -1; dx <= 1; dx++) {
                        var b = WORLD_BLOCK(bx + dx + 0.5, by + dy, bz + dz + 0.5);
                        String s = b == null ? "?" : shortName(b.name());
                        if (dx == 0 && dz == 0) s = "[" + s + "]";
                        sb.append(' ').append(s);
                    }
                    sb.append("  |  ");
                }
            }
            info("Bot cube at ({},{},{}) bot=({},{},{}):{}",
                bx, by, bz,
                String.format("%.2f", p.getX()), String.format("%.2f", p.getY()), String.format("%.2f", p.getZ()),
                sb);
        } catch (Exception e) {
            // diagnostics must never break the state machine
        }
    }

    private String shortName(String n) {
        int i = n.indexOf(':');
        String s = i >= 0 ? n.substring(i + 1) : n;
        return s.length() > 9 ? s.substring(0, 9) : s;
    }

    /** True when the bot stands inside the pit column (its integer x/z equal the pit's). */
    private boolean botInPitColumn() {
        var p = CACHE.getPlayerCache();
        return (int) Math.floor(p.getX()) == currentPit[0]
            && (int) Math.floor(p.getZ()) == currentPit[2];
    }

    /**
     * Feet position the bot should stand in to throw into the pit.
     * Prefer standing on top of the trapdoor block; otherwise just above the pit.
     */
    private int[] throwStand() {
        if (trapdoorPos != null) return new int[]{trapdoorPos[0], trapdoorPos[1] + 1, trapdoorPos[2]};
        return new int[]{currentPit[0], currentPit[1] + 1, currentPit[2]};
    }

    /**
     * Cell a player would walk to before stepping in: the rim block next to the pit on the
     * trapdoor's facing side, one block out from the centre hole along that axis. Baritone
     * otherwise parks us on whatever standable cell is nearest, which can be on the opposite
     * side of the shaft, where the sneak-in has to cross the rim first.
     *
     * @return feet position {x, y, z}, or null when no cell on that side is standable
     */
    private int[] facingStandBlock() {
        Direction f = trapdoorFacing();
        if (f == null) return null;
        int rimY = (trapdoorPos != null ? trapdoorPos[1] : currentPit[1]) + 1;
        // r=1 is the rim block itself; r=2 is the fallback one further out (rim taken by the
        // restock chest, a wall, or a sign post)
        for (int r = 1; r <= 2; r++) {
            int x = currentPit[0] + f.x() * r;
            int z = currentPit[2] + f.z() * r;
            // the rim can sit one lower on uneven ground, so try the level below as well
            for (int dy = 0; dy >= -1; dy--) {
                int y = rimY + dy;
                if (standableAt(x, y, z)) return new int[]{x, y, z};
            }
        }
        return null;
    }

    /** Count a failed walk to the stand cell; after three tries baritone parks us instead. */
    private void onStandApproachTimeout(int[] stand) {
        if (++moveToThrowRetries > 2) {
            standApproachFailed = true;
            moveToThrowRetries = 0;
            warn("Could not reach the stand cell {} next to the pit - letting baritone park us wherever it can",
                fmt(stand));
            logBotNeighborhood();
            return;
        }
        warn("Path to the stand cell {} timed out, retrying ({}/3)", fmt(stand), moveToThrowRetries);
        shakeOffTicks = SHAKE_OFF_TICKS;
    }

    /** Solid floor underneath, clear of collision and liquid for the two body blocks. */
    private boolean standableAt(int x, int y, int z) {
        if (!World.isChunkLoadedBlockPos(x, z)) return false;
        var below = WORLD_BLOCK(x + 0.5, y - 1, z + 0.5);
        if (below == null || !isSolidBlock(below)) return false;
        var feet = WORLD_BLOCK(x + 0.5, y, z + 0.5);
        var head = WORLD_BLOCK(x + 0.5, y + 1, z + 0.5);
        if (feet == null || head == null) return false;
        return !isSolidBlock(feet) && !isSolidBlock(head)
            && !isLiquidBlock(feet) && !isLiquidBlock(head);
    }

    /**
     * Arrival check for the throw spot, three conditions:
     *  1. per-axis horizontal distance to the pit center (rejects diagonal 0.5/0.5 spots),
     *  2. the bot stands at the right Y level,
     *  3. the eyes are over the pit column with a clear drop into the chamber - that is
     *     exactly what makes the straight-down pearl actually enter the pit.
     */
    private boolean nearThrowPos() {
        var p = CACHE.getPlayerCache();
        double hdx = p.getX() - (currentPit[0] + 0.5);
        double hdz = p.getZ() - (currentPit[2] + 0.5);
        if (Math.abs(hdx) > 0.55 || Math.abs(hdz) > 0.55) return false;
        double trapY = trapdoorPos != null ? trapdoorPos[1] : currentPit[1];
        if (p.getY() < trapY - 0.1 || p.getY() > trapY + 1.5) return false;
        return pearlWouldFallIn();
    }

    /**
     * Ready to drop the pearl straight down. Strictly: the eyes must be over the pit column
     * with a clear drop into the chamber, i.e. the view really is on the pit. A relaxed
     * distance check is deliberately NOT used - throwing from beside the pit lands the pearl
     * on the rim block instead of in the chamber.
     */
    private boolean readyToThrow() {
        return nearThrowPos();
    }

    /** Horizontal distance in blocks from the bot to the pit centre. */
    private double pitCenterDist() {
        var p = CACHE.getPlayerCache();
        double dx = p.getX() - (currentPit[0] + 0.5);
        double dz = p.getZ() - (currentPit[2] + 0.5);
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Close enough to the pit centre that the pearl lands in the middle of the chamber instead
     * of against a wall. Deliberately stricter than {@link #nearThrowPos()}: touching the
     * column's edge makes the drop legal, it does not make it a well-placed throw.
     */
    private boolean throwCentered() {
        double d = pitCenterDist();
        return d * d <= THROW_CENTER_SQ;
    }

    /**
     * Track how long the sneak-in has been stuck. Progress is net gain toward the pit centre,
     * not per-tick movement: pressing forward into the edge guard makes the guard shove the bot
     * back every tick, so a per-tick check would see "progress" forever.
     */
    private void trackCreepProgress(double creepDist) {
        if (!creepInit) {
            creepInit = true;
            creepBestDist = creepDist;
        } else if (creepDist < creepBestDist - CREEP_PROGRESS_EPS) {
            creepBestDist = creepDist;
            creepStuckTicks = 0;
        } else {
            creepStuckTicks++;
        }
    }

    /** First non-air block straight below the eyes, skipping trapdoors. Null if none. */
    private com.zenith.mc.block.Block downBlockFromEyes() {
        var p = CACHE.getPlayerCache();
        int ex = (int) Math.floor(p.getX());
        int ez = (int) Math.floor(p.getZ());
        int minY = Math.min(currentPit[1], trapdoorPos != null ? trapdoorPos[1] : currentPit[1]) - 3;
        for (int y = (int) Math.floor(p.getEyeY()); y >= minY; y--) {
            var b = WORLD_BLOCK(ex + 0.5, y, ez + 0.5);
            if (b == null) return null;
            String n = b.name();
            if (n.contains("air") || n.endsWith("_trapdoor")) continue;
            return b;
        }
        return null;
    }

    private boolean inLiquid() {
        var p = CACHE.getPlayerCache();
        int py = (int) Math.floor(p.getY());
        return isLiquidBlock(WORLD_BLOCK(p.getX(), py, p.getZ()))
            || isLiquidBlock(WORLD_BLOCK(p.getX(), py + 1, p.getZ()));
    }

    /** True when the bot's feet or head is inside a solid (non-air, non-liquid) block. */
    private boolean isStuckInBlock() {
        if (inLiquid()) return false;
        var p = CACHE.getPlayerCache();
        int py = (int) Math.floor(p.getY());
        var feet = WORLD_BLOCK(p.getX(), py, p.getZ());
        var head = WORLD_BLOCK(p.getX(), py + 1, p.getZ());
        return isSolidBlock(feet) || isSolidBlock(head);
    }

    private static boolean isSolidBlock(com.zenith.mc.block.Block b) {
        if (b == null) return false;
        String n = b.name();
        if (n.equals("air") || n.contains("water") || n.contains("lava")
            || n.contains("void") || n.contains("structure") || n.contains("barrier")
            || n.contains("trapdoor") || n.contains("sign") || n.contains("bubble_column")) {
            return false;
        }
        // Decoration has no collision box at all. Without this the bot is reported as
        // "stuck in a block" every time it merely walks through tall grass on the surface,
        // which sends it into EXIT_WATER to climb out of a pit it is nowhere near.
        for (String decor : NON_COLLIDABLE) {
            if (n.equals(decor)) return false;
        }
        return true;
    }

    /** Blocks that exist in the world but never obstruct movement */
    private static final String[] NON_COLLIDABLE = {
        "tall_grass", "short_grass", "grass", "fern", "large_fern", "dead_bush",
        "seagrass", "tall_seagrass", "kelp", "kelp_plant", "sugar_cane",
        "poppy", "dandelion", "blue_orchid", "allium", "azure_bluet", "red_tulip",
        "orange_tulip", "white_tulip", "pink_tulip", "oxeye_daisy", "cornflower",
        "lily_of_the_valley", "sunflower", "lilac", "rose_bush", "peony",
        "torch", "wall_torch", "soul_torch", "soul_wall_torch", "lantern",
        "scaffolding", "rail", "glow_lichen", "hanging_roots", "small_dripleaf",
        "big_dripleaf", "snow", "fire", "nether_sprouts", "cave_vines", "twisting_vines",
        "weeping_vines", "pink_petals", "short_dry_grass", "tall_dry_grass"
    };

    private static com.zenith.mc.block.Block WORLD_BLOCK(double x, int y, double z) {
        try {
            return World.getBlock((int) Math.floor(x), y, (int) Math.floor(z));
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isLiquidBlock(com.zenith.mc.block.Block b) {
        if (b == null) return false;
        String n = b.name();
        return n.contains("water") || n.contains("lava");
    }

    /**
     * Get out of the chamber before doing anything else. The trapdoor is normally closed again
     * after a pull and the bot lands either on top of it or wedged under it, so every lid is
     * opened first (the bubble column only lifts us through an open one), then we hop out along
     * the trapdoor's facing axis. Once clear on solid ground, MOVE_TO_THROW handles the
     * deliberate sneak back in.
 */
    private void handleExitWater() {
        var p = CACHE.getPlayerCache();
        int trapY = trapdoorPos != null ? trapdoorPos[1]
            : (currentPit != null ? currentPit[1] : Integer.MIN_VALUE);

        // 1. EVERY trapdoor sealing this chamber has to be open before anything else - a chamber
        // can have two lids (e.g. one at the pit level and one above it), and the bubble
        // column only lifts us through an open one. Handle them one after another: each time
        // OPEN_TRAPDOOR finishes it hands control back and we look for the next shut lid.
        int[] sealed = shutChamberTrapdoor();
        if (sealed != null) {
            overrideTrapdoor = sealed;
            if (stateTicks <= 2) {
                info("Chamber lid {} is shut - opening it before stepping out | bot=({},{})",
                    fmt(sealed), String.format("%.2f", p.getX()), String.format("%.2f", p.getZ()));
            }
            setState(MoverState.OPEN_TRAPDOOR);
            return;
        }
        if (trapdoorPos != null && !isTrapdoorOpen()) {
            // go back through EXIT_WATER once it is open: there may be a second lid
            overrideTrapdoor = trapdoorPos;
            setState(MoverState.OPEN_TRAPDOOR);
            return;
        }

        // 2. out of the pit already? then we are done
        if (clearOfPit()) {
            jumpAssist(false);
            BARITONE.stop();
            submitInput(Input.builder().build(), pitFacingYaw(), 0f);
            pitEscapeTicks = 0;
            // Only now, with the bot standing outside the chamber, decide whether a throw is
            // needed at all: one of our own pearls already in the pit means this round is
            // stocked, so go straight to the cargo work. If there is none, walk back in and
            // throw. Somebody else's pearl does not count - it is not ours for the loader to
            // use - and how far we hopped off the trapdoor does not change the decision.
            int own = ownPearlsInPit();
            if (!detourPending && (own > 0 || pearlRestingInPit())) {
                info("Out of the pit, {} own pearl(s) already in it at {} ({} blocks away) - "
                        + "skipping the throw and going straight to {}",
                    own, fmt(currentPit), (long) Math.sqrt(distSqFromBot(currentPit)),
                    isInput() ? "cargo" : "unload");
                restingPearlId = lastFoundPearlId != -1 ? lastFoundPearlId : ownRisenPearlIdAtPit();
                retreatStableTicks = 0;
                pearlRiseInit = false;
                retreatLeaveWarned = false;
                hasRetreatPrev = false;
                pearlMissingTicks = 0;
                setState(MoverState.RETREAT);
                return;
            }
            setState(MoverState.MOVE_TO_THROW);
            return;
        }

        if (stateTicks % 20 == 0) {
            info("EXIT_PIT: climbing out | y={} rimY={} inPitCol={} liquid={} stuck={}",
                String.format("%.2f", p.getY()), trapY, botInPitColumn(),
                inLiquid(), isStuckInBlock());
        }

        // 3. still below the rim: ride the bubble column up until we are level with it
        if (p.getY() < trapY - 0.1) {
            BARITONE.stop();
            submitRiseInput();
        } else {
            // 4. at rim level but still in the shaft: hop out along the trapdoor facing
            BARITONE.stop();
            submitInput(Input.builder()
                .pressingForward(true)
                .jumping(true)
                .build(), stepAsideYaw(), 0f);
        }

        pitEscapeTicks++;
        if (pitEscapeTicks > 200) {
            jumpAssist(false);
            error("Could not climb out of the pit after {} ticks (y={}, rim={})",
                pitEscapeTicks, String.format("%.2f", p.getY()), trapY);
            setState(MoverState.ERROR);
        }
    }

    /** Out of the pit: at/above the rim, no longer in the pit column, standing on something */
    private boolean clearOfPit() {
        if (currentPit == null) return true;
        var p = CACHE.getPlayerCache();
        // Nowhere near the pit (e.g. still at the world spawn right after login) means we are
        // definitely not inside it - comparing Y alone would call a bot 11 million blocks away
        // "still below the rim" and send it climbing out of a pit it never entered.
        if (!nearPitColumn()) return true;
        int trapY = trapdoorPos != null ? trapdoorPos[1] : currentPit[1];
        if (p.getY() < trapY - 0.1) return false;
        if (botInPitColumn()) return false;
        return isSolidBlock(WORLD_BLOCK(p.getX(), (int) Math.floor(p.getY()) - 1, p.getZ()));
    }

    /**
 * The chamber's cross-section, counted as a 3x3 block square around the bubble column (the
 * centre plus its eight neighbours). That square - not a radius - is the right test for "the
 * bot is wedged in the shaft": the cargo and restock chests sit further out, and the rim is
 * often a block higher than the floor around it, so a plain "below the rim" test used to drag
 * the bot out of CARGO to climb out of a pit it never fell into.
 */
private boolean inPitFootprint() {
        if (currentPit == null) return false;
        var p = CACHE.getPlayerCache();
        int bx = (int) Math.floor(p.getX());
        int bz = (int) Math.floor(p.getZ());
        return Math.abs(bx - currentPit[0]) <= 1 && Math.abs(bz - currentPit[2]) <= 1;
    }

    /**
 * Same 3x3 chamber footprint as {@link #inPitFootprint()} - kept as a named alias so the
 * pit-adjacency checks read clearly.
 */
    private boolean nearPitColumn() {
        return inPitFootprint();
    }

    /**
     * Top the pearls up while we are still standing outside the pit. The restock chest is only
     * a few blocks from the pit and reachable from there, so doing it now is cheap - waiting
     * until the pearls run out means trying to walk to the chest from inside the shaft.
     */
    private boolean restockBeforeThrow() {
        if (pearlCount() >= config.restockBeforeThrowBelow) return false;
        int[] pearlChest = pearlChestPos();
        if (pearlChest == null) return false;
        if (distSqFromBot(pearlChest) > (double) config.restockMaxDistance * config.restockMaxDistance) {
            warn("Pearls low ({}/{}) but the restock chest is out of range - skipping the pre-throw top-up",
                pearlCount(), config.restockBeforeThrowBelow);
            return false;
        }
        if (stateTicks % 20 == 1) {
            info("Pearls at {}/{}, topping up from the chest next to the pit before walking in",
                pearlCount(), config.restockBeforeThrowBelow);
        }
        restockSubmitted = false;
        afterRestock = MoverState.MOVE_TO_THROW;
        setState(MoverState.RESTOCK_PEARLS);
        return true;
    }

    /**
     * Scan outward from the pit (up to 6 blocks) for a dry, walkable cell adjacent
     * to the trapdoor that the bot can stand on before walking onto it.
     */
    private int[] findNearbyDryStand() {
        if (currentPit == null) return null;
        int cx = currentPit[0], cy = currentPit[1], cz = currentPit[2];
        int[] best = null;
        double bestD = Double.MAX_VALUE;
        for (int r = 1; r <= 6; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    for (int dy = -1; dy <= 2; dy++) {
                        int x = cx + dx, y = cy + dy, z = cz + dz;
                        if (!World.isChunkLoadedBlockPos(x, z)) continue;
                        var feet = World.getBlock(x, y, z);
                        var below = y > -64 ? World.getBlock(x, y - 1, z) : null;
                        if (feet == null || below == null) continue;
                        if (isLiquidBlock(feet) || isLiquidBlock(below)) continue;
                        if (below.name().contains("air")) continue;
                        // exclude any trapdoor below (could be another pit opening)
                        if (below.name().contains("trapdoor")) continue;
                        // must not be inside the pit column itself
                        if (x == cx && z == cz) continue;
                        double d = distSqFromBot(x + 0.5, y + 0.5, z + 0.5);
                        if (d < bestD) {
                            bestD = d;
                            best = new int[]{x, y, z};
                        }
                    }
                }
            }
            if (best != null) break;
        }
        return best;
    }

    // ==================== trapdoor ====================

    private void handleOpenTrapdoor() {
        int[] target = openTarget();
        if (target == null) {
            trapdoorPos = findNearestTrapdoor(currentPit, 5);
            target = trapdoorPos;
            if (target == null) {
                info("No trapdoor found near the pit, assuming it does not need opening");
                setState(MoverState.THROWING);
                return;
            }
            info("Trapdoor at {} facing {}", fmt(trapdoorPos), trapdoorFacing());
        }
        if (isTrapdoorOpenAt(target)) {
            if (overrideTrapdoor != null) {
                info("Opened the chamber lid at {}", fmt(overrideTrapdoor));
                overrideTrapdoor = null;
                // back to EXIT_WATER: a chamber can have a second lid still shut, and that
                // is where both are found before the bot tries to hop out
                setState(MoverState.EXIT_WATER);
            } else {
                setState(MoverState.THROWING);
            }
            return;
        }
        var p = CACHE.getPlayerCache();
        // too far for a reliable useItemOn raycast: close in first instead of clicking blind
        double tdx = p.getX() - (target[0] + 0.5);
        double tdz = p.getZ() - (target[2] + 0.5);
        double td2 = tdx * tdx + tdz * tdz;
        if (td2 > 2.25) {
            // Too far for a reliable useItemOn raycast: walk back towards the trapdoor. Hopping
            // off it can fling us a dozen blocks away, and without a stall check this walks
            // forever against whatever we landed on instead of ever opening the lid.
            if (trapdoorApproachInit) {
                double mx = p.getX() - trapdoorApproachLastX;
                double mz = p.getZ() - trapdoorApproachLastZ;
                if (mx * mx + mz * mz < 0.0004) {
                    trapdoorApproachStall++;
                } else {
                    trapdoorApproachStall = 0;
                }
            } else {
                trapdoorApproachInit = true;
            }
            trapdoorApproachLastX = p.getX();
            trapdoorApproachLastZ = p.getZ();
            if (trapdoorApproachStall > 90) {
                error("Walked back towards the trapdoor at {} for {} ticks without arriving | bot=({},{})",
                    fmt(target), trapdoorApproachStall,
                    String.format("%.2f", p.getX()), String.format("%.2f", p.getZ()));
                setState(MoverState.ERROR);
                return;
            }
            if (trapdoorApproachStall == 30) {
                info("Blocked on the way back to the trapdoor at {}, hopping over it | bot=({},{})",
                    fmt(target), String.format("%.2f", p.getX()), String.format("%.2f", p.getZ()));
                logBotNeighborhood();
            }
            float yaw = (float) normalizeYaw(Math.toDegrees(Math.atan2(-tdx, tdz)));
            submitInput(Input.builder()
                .pressingForward(true)
                .jumping(trapdoorApproachStall > 15)
                .build(), yaw, 0f);
            return;
        }
        trapdoorApproachInit = false;
        trapdoorApproachStall = 0;
// Deep in the shaft the trapdoor is overhead and can be clicked from underneath; anywhere
        // else in the pit column the bot's own hitbox intersects it, so clicking is rejected and
        // we must hop out along the facing first and click it from the side.
        boolean inPitColumn = (int) Math.floor(p.getX()) == target[0]
            && (int) Math.floor(p.getZ()) == target[2];
        boolean belowTrapdoor = inPitColumn && p.getY() < target[1] - 0.1;

        if (inPitColumn && !belowTrapdoor) {
            trapdoorStepOffTicks++;
            int tick = trapdoorStepOffTicks;
            int phase = (tick - 1) / STEP_OFF_DIRECTION_TICKS;
            // Escaping a shut chamber takes a few different tries. In the worst case the bot
            // stands exactly on the bubble column top with the closed trapdoor's collision box
            // at its feet: the server then rejects EVERY horizontal move (all four directions
            // leave the position unchanged to the centimetre) because the hitbox cannot clear
            // the trapdoor it is standing in. Only the player can break that deadlock, and the
            // player right-clicks the lid - which is what the click path below finally does.
            if (phase >= STEP_OFF_PHASES) {
                if (tick == STEP_OFF_PHASES * STEP_OFF_DIRECTION_TICKS + 1) {
                    warn("Hopped every direction without leaving the pit column, clicking the trapdoor in place | bot=({},{}) y={}",
                        String.format("%.2f", p.getX()), String.format("%.2f", p.getZ()),
                        String.format("%.2f", p.getY()));
                    logBotNeighborhood();
                }
                trapdoorStepOffTicks = STEP_OFF_PHASES * STEP_OFF_DIRECTION_TICKS;
                // fall through to the click logic
            } else {
                float yaw = hopYaws()[phase % 4];
                boolean back = phase >= 4 && phase < 8;
                boolean sneak = phase >= 8;
                Input.Builder builder = Input.builder().jumping(true);
                if (back) builder.pressingBack(true); else builder.pressingForward(true);
                if (sneak) builder.sneaking(true);
                if (tick == 1) {
                    info("In the pit column at y={}, hopping out toward the facing side to open the trapdoor | bot=({},{}) facing={}",
                        String.format("%.2f", p.getY()),
                        String.format("%.2f", p.getX()), String.format("%.2f", p.getZ()), trapdoorFacing());
                } else if (tick % STEP_OFF_DIRECTION_TICKS == 1) {
                    info("Still in the pit column, trying yaw={} ({}{}) to step off",
                        yaw, back ? "back" : "forward", sneak ? " sneak" : "");
                }
                submitInput(builder.build(), yaw, 0f);
                return;
            }
        }
        trapdoorStepOffTicks = 0;
        // Always aim straight AT the trapdoor and click that exact block, whether we are
        // beside it or standing on it - anything else can end up aimed at the floor.
        var aim = aimAt(target);
        lastAim = aim;
        var click = new ClickTarget.BlockPosition(target[0], target[1], target[2]);
        submitInput(Input.builder()
            .rightClick(true)
            .clickTarget(click)
            .clickRequiresRotation(false)
            .build(), aim[0], aim[1]);
        rightClickHeld = true;
        awaitTrapdoorTimer.reset();
        setState(MoverState.AWAIT_TRAPDOOR_OPEN);
    }

    /**
     * Wait for the lid that was just clicked to read as open. A rejected click - own hitbox in
     * the way, out of reach, a lag spike - is indistinguishable from "nothing happened", so a
     * timeout simply repeats the whole open step (which redoes the hop-out dance when that is
     * what the position needs).
     */
    private void handleAwaitTrapdoorOpen() {
        // release the click a tick after it was sent, so the server processes one useItemOn
        if (rightClickHeld) {
            rightClickHeld = false;
            submitInput(Input.builder().build(), lastAim[0], lastAim[1]);
            return;
        }
        int[] target = openTarget();
        if (target == null) {
            setState(MoverState.THROWING);
            return;
        }
        if (isTrapdoorOpenAt(target)) {
            info("Opened the chamber lid at {}", fmt(target));
            if (overrideTrapdoor != null) {
                // a lid found while climbing out: back to EXIT_WATER, which looks for a
                // second lid before the bot tries to hop out
                overrideTrapdoor = null;
                setState(MoverState.EXIT_WATER);
            } else {
                setState(MoverState.THROWING);
            }
            return;
        }
        if (awaitTrapdoorTimer.tick(AWAIT_TRAPDOOR_TICKS)) {
            if (++trapdoorRetries > MAX_TRAPDOOR_RETRIES) {
                error("Failed to open the trapdoor at {} after {} attempts", fmt(target), MAX_TRAPDOOR_RETRIES);
                setState(MoverState.ERROR);
            } else {
                setState(MoverState.OPEN_TRAPDOOR);
            }
        }
    }

    /** Horizontal facing of the trapdoor block, or null if unknown. */
    private Direction trapdoorFacing() {
        if (trapdoorPos == null) return null;
        try {
            var state = World.getBlockState(trapdoorPos[0], trapdoorPos[1], trapdoorPos[2]);
            return state.getProperty(BlockStateProperties.HORIZONTAL_FACING);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isTrapdoorOpen() {
        return isTrapdoorOpenAt(trapdoorPos);
    }

    /** Open state of an arbitrary trapdoor, false when unknown or not a trapdoor. */
    private boolean isTrapdoorOpenAt(int[] pos) {
        if (pos == null) return false;
        try {
            var state = World.getBlockState(pos[0], pos[1], pos[2]);
            Boolean open = state.getProperty(BlockStateProperties.OPEN);
            return open != null && open;
        } catch (Exception e) {
            return false;
        }
    }

    /** The trapdoor the bot should open: an override set while it is stuck under a
     *  neighbouring chamber's trapdoor, otherwise the trapdoor of the target pit. */
    private int[] openTarget() {
        return overrideTrapdoor != null ? overrideTrapdoor : trapdoorPos;
    }

    /**
     * Scan straight up from the bot for the first real block: when that is a shut trapdoor
     * this is the one sealing the chamber the bot is standing in - possibly a neighbouring
     * chamber's, which is fine, because the rule is simply "swim up, open what is above".
     */
    private int[] trapdoorAboveBot() {
        var p = CACHE.getPlayerCache();
        int bx = (int) Math.floor(p.getX());
        int bz = (int) Math.floor(p.getZ());
        int bottom = (int) Math.floor(p.getY()) - 1;
        for (int y = bottom + 3; y >= bottom; y--) {
            var b = WORLD_BLOCK(bx + 0.5, y, bz + 0.5);
            if (b == null) continue;
            String n = b.name();
            if (n.endsWith("_trapdoor")) return new int[]{bx, y, bz};
            // the chamber is decorated with a wall sign right above the trapdoor: signs, slabs,
            // torches etc. are not what seals us in, so keep looking instead of giving up
            if (isSolidBlock(b)) return null;
        }
        return null;
    }

    private int[] findNearestTrapdoor(int[] around, int radius) {
        final int bx = around[0], by = around[1], bz = around[2];
        int[] best = null;
        int bestD = Integer.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    int x = bx + dx, y = by + dy, z = bz + dz;
                    if (!World.isChunkLoadedBlockPos(x, z)) continue;
                    var b = World.getBlock(x, y, z);
                    if (b == null || !b.name().endsWith("_trapdoor")) continue;
                    int d = dx * dx + dy * dy + dz * dz;
                    if (d < bestD) {
                        bestD = d;
                        best = new int[]{x, y, z};
                    }
                }
            }
        }
        return best;
    }

    // ==================== throwing ====================

    /**
     * Prepare the throw: a pearl in the held slot, the lid open, the drop path clear. Nothing
     * is thrown from here - {@link #handleThrowPearl()} does the single click, so a retry does
     * not repeat these checks and a rejected click is retried on its own.
     */
    private void handleThrowing() {
        if (!pearlInHeld()) {
            if (!hasAnyPearl()) {
                warn("No ender pearl available, restocking first");
                if (pearlChestUnreachable()) {
                    error("No pearl and the pearl restock chest is unreachable");
                    setState(MoverState.ERROR);
                } else {
                    restockSubmitted = false;
                    // we are mid-throw: once restocked we must go BACK to the pit,
                    // not on to the cargo flow, or the throw is silently skipped
                    afterRestock = MoverState.MOVE_TO_THROW;
                    setState(MoverState.RESTOCK_PEARLS);
                }
                return;
            }
            if (!INVENTORY.hasActiveRequest()) {
                submitMovePearlToHotbar();
            }
            if (stateTicks % 20 == 0) {
                info("THROWING waiting: pearl not in the held slot (anyPearl={} requestActive={} waited={} ticks)",
                    hasAnyPearl(), INVENTORY.hasActiveRequest(), stateTicks);
            }
            return;
        }
        if (trapdoorPos != null && !isTrapdoorOpen()) {
            warn("Trapdoor closed, reopening before the throw");
            setState(MoverState.OPEN_TRAPDOOR);
            return;
        }
        if (!throwPathClear()) {
            if (stateTicks % 20 == 0) {
                info("THROWING waiting: throw path not clear (waited={} ticks, limit={})",
                    stateTicks, config.pathTimeoutTicks);
            }
            if (stateTicks > config.pathTimeoutTicks) {
                error("Throw path is blocked and cannot be cleared");
                setState(MoverState.ERROR);
            }
            return;
        }
        pearlIdsBeforeThrow = currentPearlIds();
        throwRetries = 0;
        moveToThrowRetries = 0;
        restingPearlId = -1;
        pearlStayTicks = 0;
        setState(MoverState.THROW_PEARL);
    }

    /** The single right-click of the round: straight down (pitch 90) from the throw stand. */
    private void handleThrowPearl() {
        if (isStuckInBlock()) {
            setState(MoverState.EXIT_WATER);
            return;
        }
        if (!readyToThrow()) {
            dbg("Not at the throw position yet, pathing back to the pit");
            setState(MoverState.MOVE_TO_THROW);
            return;
        }
        if (trapdoorPos != null && !isTrapdoorOpen()) {
            warn("Trapdoor closed before the throw, reopening");
            setState(MoverState.OPEN_TRAPDOOR);
            return;
        }
        lastAim = new float[]{lastAim[0], 90f};
        var dp = CACHE.getPlayerCache();
        double hdx = (currentPit[0] + 0.5) - dp.getX();
        double hdz = (currentPit[2] + 0.5) - dp.getZ();
        info("Throwing pearl straight down at pit {} | bot=({},{},{}) eyeY={} | hOff=({},{}) hDist={} | yaw={} pitch=90",
            fmt(currentPit),
            String.format("%.2f", dp.getX()), String.format("%.2f", dp.getY()), String.format("%.2f", dp.getZ()),
            String.format("%.2f", dp.getEyeY()),
            String.format("%.2f", hdx), String.format("%.2f", hdz),
            String.format("%.2f", Math.sqrt(hdx * hdx + hdz * hdz)),
            String.format("%.1f", lastAim[0]));
        submitInput(Input.builder()
            .rightClick(true)
            .clickTarget(ClickTarget.None.INSTANCE)
            .clickRequiresRotation(false)
            .build(), lastAim[0], 90f);
        awaitPearlSpawnTimer.reset();
        setState(MoverState.AWAIT_PEARL_SPAWNED);
    }

    /**
     * Wait for our own pearl entity to show up inside the pit column. A throw that never
     * spawns is a rejected click or a lag spike, and re-clicking is cheap compared with
     * walking all the way back in, so retry the click while the budget lasts.
     */
    private void handleAwaitPearlSpawned() {
        if (isStuckInBlock()) {
            setState(MoverState.EXIT_WATER);
            return;
        }
        int pid = ownNewPearlIdNearPit();
        if (pid != -1) {
            Entity pe = CACHE.getEntityCache().get(pid);
            info("Pearl entered the pit and is falling clear | pearl=({},{},{}) dy={}",
                pe == null ? "?" : String.format("%.2f", pe.getX()),
                pe == null ? "?" : String.format("%.2f", pe.getY()),
                pe == null ? "?" : String.format("%.2f", pe.getZ()),
                pe == null ? "?" : String.format("%.2f", pe.getY() - currentPit[1]));
            restingPearlId = pid;
            pearlStayTicks = 0;
            retreatStableTicks = 0;
            pearlRiseInit = false;
            pearlSinkInit = false;
            awaitThrowSettleTimer.reset();
            setState(MoverState.AWAIT_THROW_SETTLE);
            return;
        }
        if (!awaitPearlSpawnTimer.tick(THROW_DETECT_TICKS)) return;
        if (++throwRetries > MAX_THROW_RETRIES) {
            error("Pearl throw not confirmed after {} attempts", MAX_THROW_RETRIES);
            setState(MoverState.ERROR);
            return;
        }
        var dp2 = CACHE.getPlayerCache();
        warn("Pearl throw not confirmed after {} ticks, throwing again ({}/{}) | bot=({},{},{}) lastAim=({},{})",
            THROW_DETECT_TICKS, throwRetries, MAX_THROW_RETRIES,
            String.format("%.2f", dp2.getX()), String.format("%.2f", dp2.getY()), String.format("%.2f", dp2.getZ()),
            String.format("%.1f", lastAim[0]), String.format("%.1f", lastAim[1]));
        pearlIdsBeforeThrow = currentPearlIds();
        setState(MoverState.THROW_PEARL);
    }

    /**
     * Watch the pearl dive before stepping away. A player waits for the pearl to reach the
     * bottom of the shaft and get lifted about a block back up before turning around: by then it
     * has clearly made it into the chamber, so the retreat cannot be mistaken for a re-throw and
     * the pearl is left where the loader bot expects it.
     */
    private void handleAwaitThrowSettle() {
        // keep the look direction, but release every movement / click key while waiting
        submitInput(Input.builder().build(), lastAim[0], lastAim[1]);
        Entity pearl = restingPearlId != -1 ? CACHE.getEntityCache().get(restingPearlId) : null;
        if (pearl != null) {
            double y = pearl.getY();
            if (!pearlSinkInit) {
                pearlSinkInit = true;
                pearlLowestY = y;
            } else if (y < pearlLowestY) {
                pearlLowestY = y;
            } else if (y >= pearlLowestY + PEARL_RISE_ONE_BLOCK) {
                info("Pearl reached the bottom (lowest y={}) and floated back up to y={} - hopping out along the {} side",
                    String.format("%.2f", pearlLowestY), String.format("%.2f", y), trapdoorFacing());
                setState(MoverState.RETREAT);
                return;
            }
            if (stateTicks % 20 == 0) {
                dbg("AWAIT_THROW_SETTLE: pearl y={} lowest={} risen={}",
                    String.format("%.2f", y), String.format("%.2f", pearlLowestY),
                    String.format("%.2f", y - pearlLowestY));
            }
        }
        if (!awaitThrowSettleTimer.tick(THROW_SINK_TIMEOUT_TICKS)) return;
        if (pearl == null) {
            warn("Pearl entity vanished before it settled - hopping out anyway");
        } else {
            warn("Pearl did not sink and float back up within {} ticks (lowest={}, now={}) - hopping out anyway",
                THROW_SINK_TIMEOUT_TICKS, String.format("%.2f", pearlLowestY),
                String.format("%.2f", pearl.getY()));
        }
        setState(MoverState.RETREAT);
    }

    /**
     * True when a pearl thrown straight down from the throw stand can reach the pit:
     * the trapdoor (if any) is open and the column down to the pit is air/liquid (no solids).
     */
    private boolean throwPathClear() {
        if (trapdoorPos != null && !isTrapdoorOpen()) return false;
        // require the pit column chunk to be actually loaded; after a teleport the
        // client may not have received the chunk data yet and a throw would silently fail
        if (!World.isChunkLoadedBlockPos(currentPit[0], currentPit[2])) return false;
        int[] stand = throwStand();
        int endY = currentPit[1];
        for (int y = stand[1] - 1; y >= endY; y--) {
            var b = World.getBlock(stand[0], y, stand[2]);
            if (b == null) return false;
            String n = b.name();
            if (n.contains("air") || n.contains("water") || n.contains("lava")) continue;
            if (n.endsWith("_trapdoor")) continue;
            return false;
        }
        return true;
    }

/**
     * Ender pearls in the chamber that belong to us. This answers "do we already have cargo
     * waiting for the loader bot on this side?" - somebody else's pearl is not ours to use, and
     * is not evidence that this round is stocked.
     */
    private int ownPearlsInPit() {
        return ownPearlsInChamber(currentPit);
    }

    /** Same question as {@link #ownPearlsInPit()}, for a chamber other than the current one. */
    private int ownPearlsInChamber(int[] pit) {
        if (pit == null) return 0;
        int selfId = CACHE.getPlayerCache().getEntityId();
        int n = 0;
        for (Entity e : CACHE.getEntityCache().getEntities().values()) {
            if (e.getEntityType() != EntityType.ENDER_PEARL) continue;
            if (!(e.getObjectData() instanceof ProjectileData pd) || pd.getOwnerId() != selfId) continue;
            if (pearlInsidePitColumn(e, pit)) n++;
        }
        return n;
    }

    /**
     * Ender pearls in the chamber, ours or anybody's. This answers the physical question "is
     * there already something down there?" - throwing on top of a pearl somebody else parked
     * would just pile the chamber up, which is how one run once left nine of them in there.
     */
    private int pearlsInPit() {
        if (currentPit == null) return 0;
        int n = 0;
        for (Entity e : CACHE.getEntityCache().getEntities().values()) {
            if (e.getEntityType() != EntityType.ENDER_PEARL) continue;
            if (pearlInsidePitColumn(e)) n++;
        }
        return n;
    }

    /** One of our own pearls already risen and resting at the pit, so we need not throw. */
    private boolean pearlRestingInPit() {
        int selfId = CACHE.getPlayerCache().getEntityId();
        for (Entity e : CACHE.getEntityCache().getEntities().values()) {
            if (e.getEntityType() != EntityType.ENDER_PEARL) continue;
            if (!(e.getObjectData() instanceof ProjectileData pd) || pd.getOwnerId() != selfId) continue;
            if (pearlRisenAtPit(e)) {
                dbg("Own ender pearl already risen and resting at the pit at {},{}", currentPit[0], currentPit[2]);
                lastFoundPearlId = e.getEntityId();
                return true;
            }
        }
        return false;
    }

    /**
     * True when the pearl entity is within the pit column and has risen to the trapdoor
     * opening height (the water pushes it up until it rests against the open trapdoor).
     */
    private boolean pearlRisenAtPit(Entity e) {
        int ex = (int) Math.floor(e.getX());
        int ez = (int) Math.floor(e.getZ());
        if (Math.abs(ex - currentPit[0]) > 1 || Math.abs(ez - currentPit[2]) > 1) return false;
        double trapdoorY = trapdoorPos != null ? trapdoorPos[1] : currentPit[1] + 1;
        return e.getY() >= trapdoorY - 0.75 && e.getY() <= trapdoorY + 2.5;
    }

    private Set<Integer> currentPearlIds() {
        Set<Integer> ids = new HashSet<>();
        int selfId = CACHE.getPlayerCache().getEntityId();
        for (Entity e : CACHE.getEntityCache().getEntities().values()) {
            if (e.getEntityType() != EntityType.ENDER_PEARL) continue;
            if (e.getObjectData() instanceof ProjectileData pd && pd.getOwnerId() == selfId) {
                ids.add(e.getEntityId());
            }
        }
        return ids;
    }

/**
     * True when a pearl sits inside the pit column horizontally, anywhere from falling-in to
     * risen-to-rest vertically. The column throws the pearl past the trapdoor and back down
     * again, so the vertical window is generous - only the horizontal test is reliable, and it
     * is what stops a pearl resting on the rim from counting.
 */
    private boolean pearlInsidePitColumn(Entity e) {
        return pearlInsidePitColumn(e, currentPit);
    }

    /** Same horizontal test against an arbitrary chamber column. */
    private boolean pearlInsidePitColumn(Entity e, int[] pit) {
        if (pit == null) return false;
        double dx = Math.abs(e.getX() - (pit[0] + 0.5));
        double dz = Math.abs(e.getZ() - (pit[2] + 0.5));
        if (dx > PIT_PEARL_COL_DX || dz > PIT_PEARL_COL_DZ) return false;
        double dy = e.getY() - pit[1];
        return dy >= -4.0 && dy <= 9.0;
    }

    private int ownNewPearlIdNearPit() {
        int selfId = CACHE.getPlayerCache().getEntityId();
        for (Entity e : CACHE.getEntityCache().getEntities().values()) {
            if (e.getEntityType() != EntityType.ENDER_PEARL) continue;
            if (!(e.getObjectData() instanceof ProjectileData pd) || pd.getOwnerId() != selfId) continue;
            if (pearlIdsBeforeThrow.contains(e.getEntityId())) continue;
            if (!pearlInsidePitColumn(e)) continue;
            return e.getEntityId();
        }
        return -1;
    }

    /**
     * Return the entity id of a newly-thrown own pearl that has risen to the trapdoor
     * opening and is resting (near-zero velocity), or -1 if none has settled yet.
     */
    private int ownRisenPearlIdAtPit() {
        int selfId = CACHE.getPlayerCache().getEntityId();
        for (Entity e : CACHE.getEntityCache().getEntities().values()) {
            if (e.getEntityType() != EntityType.ENDER_PEARL) continue;
            if (!(e.getObjectData() instanceof ProjectileData pd) || pd.getOwnerId() != selfId) continue;
            if (pearlIdsBeforeThrow.contains(e.getEntityId())) continue;
            if (!pearlInsidePitColumn(e)) continue;
            if (!pearlRisenAtPit(e)) continue;
            // position alone is enough: a pearl that has risen to the trapdoor height
            // inside the pit column is resting regardless of small water bobbing
            return e.getEntityId();
        }
        return -1;
    }

    /**
     * Aim direction (yaw, pitch) from the bot's eye to the given block center.
     */
    private float[] aimAt(int[] pos) {
        var p = CACHE.getPlayerCache();
        double ex = p.getX(), ey = p.getEyeY(), ez = p.getZ();
        double tx = pos[0] + 0.5, ty = pos[1] + 0.5, tz = pos[2] + 0.5;
        double dx = tx - ex, dy = ty - ey, dz = tz - ez;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        double pitch = -Math.toDegrees(Math.atan2(dy, horiz));
        double yaw = Math.toDegrees(Math.atan2(-dx, dz));
        yaw = normalizeYaw(yaw);
        return new float[]{(float) yaw, (float) pitch};
    }

    private static double normalizeYaw(double yaw) {
        while (yaw > 180) yaw -= 360;
        while (yaw < -180) yaw += 360;
        return yaw;
    }

    /**
     * Walk/jump clear of the pit with pure direct input along the trapdoor's FACING side -
     * baritone cannot path out of the shaft and would leave us in it. Nothing about the pearl
     * is decided here; leaving the pit comes first and {@link #handleAwaitPearlStable()} is
     * the gate into cargo.
     */
    private void handleRetreat() {
        if (pearlTriggeredOnUs()) return;
        var pc = CACHE.getPlayerCache();
        if (awayFromPit()) {
            jumpAssist(false);
            BARITONE.stop();
            info("Clear of the pit (dist={}), waiting for the pearl to settle",
                String.format("%.2f", awayDist()));
            setState(MoverState.AWAIT_PEARL_STABLE);
            return;
        }
        // fell back into the shaft: the walls block any sideways move, so rise through
        // the open trapdoor first and only then walk off
        BARITONE.stop();
        double trapRefY = trapdoorPos != null ? trapdoorPos[1] : currentPit[1];
        if (pc.getY() < trapRefY - 0.1) {
            if (stateTicks % 20 == 0) {
                info("RETREAT: below the pit y={} (trapY={}), floating up in place first",
                    String.format("%.2f", pc.getY()), String.format("%.0f", trapRefY));
            }
            // phase 1: float straight up to trapdoor level first - moving sideways while
            // still inside the shaft would just shove us out of the bubble column
            submitRiseInput();
            return;
        }
        // phase 2: at trapdoor level, walk away along the trapdoor facing
        // track horizontal progress; if stuck, rotate through 4 directions
        double hx = pc.getX() - retreatPrevHx;
        double hz = pc.getZ() - retreatPrevHz;
        if (hx * hx + hz * hz < 0.01) {
            retreatNoProgressTicks++;
        } else {
            retreatNoProgressTicks = 0;
        }
        retreatPrevHx = pc.getX();
        retreatPrevHz = pc.getZ();
        float yaw;
        if (retreatNoProgressTicks > 40) {
            // stuck in place: rotate through the escape directions, facing side first
            int dir = (retreatNoProgressTicks / 40) % 4;
            yaw = hopYaws()[dir];
            if (retreatNoProgressTicks % 40 == 1) {
                info("RETREAT: no progress for {} ticks, trying yaw={}", retreatNoProgressTicks, yaw);
            }
        } else {
            yaw = retreatYaw();
        }
        double rdx = pc.getX() - (currentPit[0] + 0.5);
        double rdz = pc.getZ() - (currentPit[2] + 0.5);
        if (stateTicks % 10 == 0) {
            info("RETREAT: bot=({},{},{}) dist={} yaw={} yawAway={} yawFacing={}",
                String.format("%.2f", pc.getX()), String.format("%.2f", pc.getY()),
                String.format("%.2f", pc.getZ()),
                String.format("%.2f", Math.sqrt(rdx * rdx + rdz * rdz)),
                String.format("%.1f", yaw),
                String.format("%.1f", retreatYaw()),
                String.format("%.1f", retreatYawFacing()));
        }
        // stand up and step back away from the hole; yaw is rotated when stuck so the
        // "try another way out" fallback actually changes the direction
        submitInput(Input.builder()
            .pressingForward(true)
            .jumping(true)
            .build(), yaw, 0f);
        jumpHeld = true; // released once we are out
        if (awaitRetreatTimer.tick(RETREAT_STABLE_TIMEOUT_TICKS)) {
            rethrowOrHalt("never got clear of the pit");
        }
    }

    /**
     * Gate into the next phase: the pearl has to have stopped sinking for
     * {@link #PEARL_STAY_TICKS} while we stand clear of the pit. "Stopped sinking" covers both
     * being lifted back up and already resting against the sign above the trapdoor.
     */
    private void handleAwaitPearlStable() {
        if (pearlTriggeredOnUs()) return;

        if (restingPearlId != -1) {
            pearlMissingTicks = 0;
            // "settled" only means the pearl turned around at the bottom of the pit and is
            // on its way back up - there is no need to wait for it to reach the trapdoor.
            Entity pe = CACHE.getEntityCache().get(restingPearlId);
            if (pe != null && pearlInsidePitColumn(pe)) {
                double y = pe.getY();
                if (pearlRiseInit) {
                    if (y >= pearlLastY - 0.005) retreatStableTicks++;
                    else retreatStableTicks = 0;
                }
                pearlLastY = y;
                pearlRiseInit = true;
            } else {
                retreatStableTicks = 0;
                pearlRiseInit = false;
            }
        } else {
            // no pearl id recorded (e.g. re-entered); try to observe one rising
            int pid = ownRisenPearlIdAtPit();
            if (pid != -1) {
                restingPearlId = pid;
                pearlMissingTicks = 0;
                retreatStableTicks = 1;
            } else if (++pearlMissingTicks > THROW_DETECT_TICKS) {
                // pearl never appeared after the throw window -> re-throw
                onPearlTriggeredDuringRetreat("pearl never appeared");
                return;
            }
        }

        if (!awayFromPit()) {
            // shoved back into the shaft (a hop off the lid can do that): climb out again
            if (!retreatLeaveWarned) {
                retreatLeaveWarned = true;
                warn("Back in the pit while waiting for the pearl, leaving again before {}",
                    isInput() ? "cargo" : "unload");
            }
            setState(MoverState.RETREAT);
            return;
        }
        if (retreatStableTicks >= PEARL_STAY_TICKS) {
            info("Pearl settled in the chamber ({} stable ticks) and we are clear of the pit (dist={}), proceeding to {}",
                retreatStableTicks, String.format("%.2f", awayDist()), isInput() ? "cargo" : "unload");
            finishRetreat();
            return;
        }
        if (stateTicks % 20 == 0) {
            dbg("AWAIT_PEARL_STABLE: {}/{} ticks without sinking | pearl={}",
                retreatStableTicks, PEARL_STAY_TICKS, restingPearlId);
        }
        if (awaitPearlStableTimer.tick(RETREAT_STABLE_TIMEOUT_TICKS)) {
            rethrowOrHalt("pearl not confirmed in the chamber");
        }
    }

    /**
     * Watches for the pearl firing on us - a small coordinate jump, or the pearl we were
     * tracking vanishing. Either means we are about to be pulled somewhere else, so the round
     * starts over instead of carrying on with stale state.
     *
     * @return true when it fired and the caller must stop handling this tick
     */
    private boolean pearlTriggeredOnUs() {
        var pc = CACHE.getPlayerCache();
        if (hasRetreatPrev) {
            double jumpSq = distSq(pc.getX(), pc.getY(), pc.getZ(), retreatPrevX, retreatPrevY, retreatPrevZ);
            if (jumpSq > PEARL_TRIGGER_JUMP_SQ) {
                onPearlTriggeredDuringRetreat("coordinate jump of " + Math.sqrt(jumpSq) + " blocks");
                return true;
            }
        }
        retreatPrevX = pc.getX();
        retreatPrevY = pc.getY();
        retreatPrevZ = pc.getZ();
        hasRetreatPrev = true;
        if (restingPearlId != -1 && !entityExists(restingPearlId)) {
            // the pearl we confirmed is gone -> it triggered on us (or broke)
            onPearlTriggeredDuringRetreat("pearl entity disappeared");
            return true;
        }
        return false;
    }

    /** The pearl never confirmed in the chamber: throw again, or halt once the budget is spent. */
    private void rethrowOrHalt(String why) {
        if (++rethrowCount > MAX_THROW_RETRIES) {
            error("{} - halting after {} re-throws", why, MAX_THROW_RETRIES);
            jumpAssist(false);
            setState(MoverState.ERROR);
            return;
        }
        warn("{} after {} ticks, re-throwing ({}/{}) | pearlsLeft={}",
            why, RETREAT_STABLE_TIMEOUT_TICKS, rethrowCount, MAX_THROW_RETRIES, pearlCount());
        jumpAssist(false);
        BARITONE.stop();
        restingPearlId = -1;
        pearlMissingTicks = 0;
        retreatStableTicks = 0;
        retreatLeaveWarned = false;
        hasRetreatPrev = false;
        pearlRiseInit = false;
        pearlIdsBeforeThrow.clear();
        setState(MoverState.MOVE_TO_THROW);
    }

    /**
     * Yaw the bot retreats along: along the trapdoor's FACING axis, i.e. straight back the
     * way it walked in. This axis is perpendicular to the row of adjacent static chambers,
     * so retreating this way can never push the bot into the neighbouring pit - which is what
     * "walk away from the pit centre" used to do and how it ended up falling into the next one.
     */
    private float retreatYaw() {
        Direction f = trapdoorFacing();
        if (f != null) {
            return (float) normalizeYaw(Math.toDegrees(Math.atan2(-f.x(), f.z())));
        }
        return retreatYawAway();
    }

    /** Fallback: straight away from the pit centre, used when the trapdoor facing is unknown. */
    private float retreatYawAway() {
        var p = CACHE.getPlayerCache();
        double dx = p.getX() - (currentPit[0] + 0.5);
        double dz = p.getZ() - (currentPit[2] + 0.5);
        if (dx * dx + dz * dz > 0.01) {
            return (float) normalizeYaw(Math.toDegrees(Math.atan2(-dx, dz)));
        }
        return lastAim[0];
    }

    /** Yaw used to step off a closed trapdoor: back toward the trapdoor's facing side. */
    private float stepAsideYaw() {
        Direction f = trapdoorFacing();
        if (f == null) return retreatYaw();
        return (float) normalizeYaw(Math.toDegrees(Math.atan2(-f.x(), f.z())));
    }

    /**
     * Escape yaws in the order they are tried: the trapdoor's own facing side first, because
     * that is the way a player leaves the pit - straight out over the lid. Only when that fails
     * do the sideways directions follow, so a working exit is never missed just because an
     * arbitrary absolute direction came first.
     */
    private float[] hopYaws() {
        float base = stepAsideYaw();
        float[] yaws = new float[HOP_SIDE_OFFSETS.length + 1];
        yaws[0] = base;
        for (int i = 0; i < HOP_SIDE_OFFSETS.length; i++) {
            yaws[i + 1] = (float) normalizeYaw(base + HOP_SIDE_OFFSETS[i]);
        }
        return yaws;
    }

    /** Yaw that points from the bot straight at the pit center (the sneak-creep direction). */
    private float pitFacingYaw() {
        var p = CACHE.getPlayerCache();
        double dx = (currentPit[0] + 0.5) - p.getX();
        double dz = (currentPit[2] + 0.5) - p.getZ();
        if (dx * dx + dz * dz < 0.0004) return lastAim[0];
        return (float) normalizeYaw(Math.toDegrees(Math.atan2(-dx, dz)));
    }

    /** Yaw that makes the bot face the given horizontal offset. */
    private float yawTo(double dx, double dz) {
        if (dx * dx + dz * dz < 0.0004) return lastAim[0];
        return (float) normalizeYaw(Math.toDegrees(Math.atan2(-dx, dz)));
    }

    /**
     * Climb back out of the shaft. Inside a bubble column the bot simply jumps to swim up -
     * pressing forward as well would shove it out of the column and into a neighbouring
     * chamber. Only when jumping makes no headway at all do we shuffle sideways, rotating
     * through the four directions until we line up with a clear column.
     */
    private void submitRiseInput() {
        var p = CACHE.getPlayerCache();
        if (riseInit) {
            double mx = p.getX() - riseLastX;
            double mz = p.getZ() - riseLastZ;
            if (mx * mx + mz * mz < 0.01) riseStuckTicks++;
            else riseStuckTicks = 0;
        } else {
            riseInit = true;
        }
        riseLastX = p.getX();
        riseLastZ = p.getZ();
        if (riseStuckTicks > 15) {
            int dir = (riseStuckTicks / 15) % 4;
            float yaw = dir * 90f;
            if (riseStuckTicks % 45 == 1) {
                info("Rise stalled at y={}, shuffling yaw={} to line up with a clear column",
                    String.format("%.2f", p.getY()), yaw);
            }
            submitInput(Input.builder()
                .pressingForward(true)
                .jumping(true)
                .build(), yaw, 0f);
            return;
        }
        submitInput(Input.builder().jumping(true).build(), pitFacingYaw(), 0f);
    }

    private void onPearlTriggeredDuringRetreat(String reason) {
        if (++throwRetries > MAX_THROW_RETRIES) {
            error("Pearl kept getting triggered while retreating ({}) after {} retries - halting", reason, MAX_THROW_RETRIES);
            jumpAssist(false);
            setState(MoverState.ERROR);
            return;
        }
        warn("Pearl triggered on us while retreating ({}), re-throwing ({})", reason, throwRetries);
        BARITONE.stop();
        jumpAssist(false);
        restingPearlId = -1;
        pearlStayTicks = 0;
        pearlMissingTicks = 0;
        retreatStableTicks = 0;
        retreatLeaveWarned = false;
        hasRetreatPrev = false;
        pearlIdsBeforeThrow.clear();
        setState(MoverState.MOVE_TO_THROW);
    }

    private void finishRetreat() {
        jumpAssist(false);
        hasRetreatPrev = false;
        retreatLeaveWarned = false;
        rethrowCount = 0;
        // clear the stability tracking so the next chamber starts counting from zero - the
        // detour throw for a neighbouring chamber runs a second round in the same visit
        retreatStableTicks = 0;
        pearlStayTicks = 0;
        pearlMissingTicks = 0;
        pearlRiseInit = false;
        restingPearlId = -1;
        if (detourPending) {
            // That throw stocked the neighbouring chamber we were pulled into. Put the real
            // configured pit back and walk over to it for the actual round.
            info("Neighbouring chamber {} stocked, heading back to the configured pit {}",
                fmt(currentPit), fmt(detourPit));
            currentPit = detourPit;
            trapdoorPos = detourTrapdoor;
            detourPit = null;
            detourTrapdoor = null;
            detourPending = false;
            pitEscapeTicks = 0;
            int stocked = ownPearlsInChamber(currentPit);
            if (stocked > 0) {
                // our pearl from the previous round is still sitting there: nothing to throw,
                // go straight on to the restock/cargo flow instead of walking over for nothing
                info("Configured pit {} already holds {} of our pearls - no throw needed, going on to {}",
                    fmt(currentPit), stocked, isInput() ? "cargo" : "unload");
                setState(MoverState.RESTOCK_PEARLS);
                return;
            }
            setState(MoverState.MOVE_TO_THROW);
            return;
        }
        if (whisperRepairPending) {
            // this throw only existed to put a pearl back into the chamber we are about to
            // leave, so skip the cargo flow and resume the whisper that was interrupted
            whisperRepairPending = false;
            info("Chamber restocked - resuming the pull whisper");
            setState(MoverState.WHISPER);
            return;
        }
        setState(MoverState.RESTOCK_PEARLS);
    }

    /** Current horizontal distance from the pit centre, for logging. */
    private double awayDist() {
        var p = CACHE.getPlayerCache();
        double dx = p.getX() - (currentPit[0] + 0.5);
        double dz = p.getZ() - (currentPit[2] + 0.5);
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Yaw along the trapdoor's own FACING direction - i.e. "behind" the bot when it walked
     * in from the facing side. Kept next to retreatYaw() so the two can be compared in the
     * logs: they agree when the bot stands on the facing side and disagree otherwise.
     */
    private float retreatYawFacing() {
        Direction f = trapdoorFacing();
        if (f == null) return retreatYawAway();
        return (float) normalizeYaw(Math.toDegrees(Math.atan2(-f.x(), f.z())));
    }

    private boolean entityExists(int entityId) {
        return CACHE.getEntityCache().get(entityId) != null;
    }

    /**
     * True when the tracked pearl entity has risen to the trapdoor opening.
     * Position alone is sufficient: a pearl that has risen to trapdoor height
     * inside the pit column is resting regardless of small water bobbing.
     */
    private boolean pearlEntityRisenAndStatic(int entityId) {
        Entity e = CACHE.getEntityCache().get(entityId);
        if (e == null || e.getEntityType() != EntityType.ENDER_PEARL) return false;
        return pearlRisenAtPit(e);
    }

    /**
     * True when the bot is out of the water and clear of the pit opening.
     * Cargo / restock is not allowed before this holds.
     */
    private boolean awayFromPit() {
        if (inLiquid()) return false;
        var p = CACHE.getPlayerCache();
        double dx = p.getX() - (currentPit[0] + 0.5);
        double dz = p.getZ() - (currentPit[2] + 0.5);
        double d2 = dx * dx + dz * dz;
        // stop retreating once >=1 block clear of the pit column (within 1-2 blocks range)
        return d2 >= 1.0;
    }

    /** Hold (or release) the jump key to help the bot swim/jump out of the water. */
    private void jumpAssist(boolean on) {
        if (on == jumpHeld) return;
        jumpHeld = on;
        var p = CACHE.getPlayerCache();
        submitInput(Input.builder().jumping(on).build(), p.getYaw(), p.getPitch());
    }

    // ==================== pearl restock ====================

    private void handleRestockPearls() {
        MoverState next = afterRestock;
        afterRestock = MoverState.MOVE_TO_CHEST;
        // Same threshold as the pre-throw check: arriving with 6-7 pearls would otherwise look "good
        // enough" (minPearls is 8) and leave the hotbar unfilled.
        if (pearlCount() >= config.restockBeforeThrowBelow) {
            restockFailCount = 0;
            setState(next);
            return;
        }
        int[] pearlChest = pearlChestPos();
        if (pearlChest == null) {
            warn("Pearls low but no pearl restock chest detectable for the {} side - set it with 'stashMover {}PearlChest <x> <y> <z>' or place an ender-pearl item frame next to it",
                isInput() ? "input" : "output", isInput() ? "input" : "output");
            setState(failedRestock(next));
            return;
        }
        if (distSqFromBot(pearlChest) > (double) config.restockMaxDistance * config.restockMaxDistance) {
            warn("Pearls low but pearlChest is out of restock range");
            setState(failedRestock(next));
            return;
        }
        if (!containerOpen()) {
            if (openContainerAt(pearlChest)) {
                restockSubmitted = false;
                restockHotbarPending = false;
                restockOverflowPending = false;
            }
            return;
        }
        if (INVENTORY.hasActiveRequest()) return;
        if (!restockSubmitted) {
            // Take exactly enough stacks to fill the hotbar's empty slots: pearls belong in the
            // hotbar anyway, and topping the whole bar up means many rounds without a restock.
            int slots = freeHotbarSlots();
            int stacks = Math.max(1, Math.min(slots, config.restockPearlStacks));
            info("Pearls at {}/{}, restocking {} stack(s) to fill {} free hotbar slot(s)",
                pearlCount(), config.restockBeforeThrowBelow, stacks, slots);
            var actions = InventoryActionMacros.withdraw(id(), this::isPearl, stacks);
            if (!actions.isEmpty()) {
                INVENTORY.submit(InventoryActionRequest.builder()
                    .owner(this).actions(actions).priority(INV_PRIORITY).build());
            }
            restockSubmitted = true;
            return;
        }
        if (restockHotbarPending) {
            if (INVENTORY.hasActiveRequest()) return;
            restockHotbarPending = false;
        } else if (movePearlsToHotbar()) {
            // The withdraw macro drops the stack into the first free player slot, which is
            // normally the MAIN inventory. Shift them into the hotbar so a pearl is always one
            // click away instead of costing a hotbar swap before every throw.
            restockHotbarPending = true;
            return;
        }

        if (restockOverflowPending) {
            if (INVENTORY.hasActiveRequest()) return;
            restockOverflowPending = false;
            info("Restocked pearls, now {} ({} in the hotbar, {} free slots)",
                pearlCount(), hotbarPearlCount(), freeHotbarSlots());
            startClose(gainedPearls(next));
            return;
        }
        // Whatever did not fit in the hotbar is still lying in the main inventory - put it back
        // so the cargo slots stay free for actual cargo (depositMainOnly skips hotbar/offhand).
        var overflow = depositMainOnly(id(), this::isPearl);
        if (!overflow.isEmpty()) {
            INVENTORY.submit(InventoryActionRequest.builder()
                .owner(this).actions(overflow).priority(INV_PRIORITY).build());
            restockOverflowPending = true;
            return;
        }
        info("Restocked pearls, now {} ({} in the hotbar, {} free slots)",
            pearlCount(), hotbarPearlCount(), freeHotbarSlots());
        startClose(gainedPearls(next));
    }

    /** A completed restock cycle that actually yielded pearls clears the failure streak. */
    private MoverState gainedPearls(MoverState next) {
        if (hasAnyPearl()) {
            restockFailCount = 0;
            return next;
        }
        return failedRestock(next);
    }

    /**
     * A restock cycle that produced nothing. Handing control back to MOVE_TO_THROW with an empty
     * inventory would bounce THROWING -> RESTOCK_PEARLS -> THROWING forever, so count the
     * fruitless attempts and stop instead. Reaching the normal cargo flow is harmless: the next
     * visit restocks again.
     */
    private MoverState failedRestock(MoverState next) {
        if (next != MoverState.MOVE_TO_THROW) return next;
        restockFailCount++;
        if (restockFailCount <= MAX_FAILED_RESTOCKS) {
            warn("Restock produced no pearls ({}/{}), trying the chest again",
                restockFailCount, MAX_FAILED_RESTOCKS);
            return MoverState.MOVE_TO_THROW;
        }
        error("Out of pearls and the restock chest has none after {} attempts - halting",
            MAX_FAILED_RESTOCKS);
        return MoverState.ERROR;
    }

    /**
     * Move every pearl sitting in the main inventory (and the offhand) into a free hotbar slot.
     * Slot layout used throughout this module: 36..44 hotbar, 9..35 main inventory, 45 offhand.
     * Returns true when an action list was submitted.
     */
    private boolean movePearlsToHotbar() {
        var inv = CACHE.getPlayerCache().getPlayerInventory();
        List<Integer> free = new ArrayList<>();
        for (int i = 36; i <= 44; i++) {
            if (inv.get(i) == null) free.add(i);
        }
        if (free.isEmpty()) return false;

        List<InventoryAction> actions = new ArrayList<>();
        int moved = 0;
        for (int i = 9; i <= 45 && !free.isEmpty(); i++) {
            if (i > 35 && i < 45) continue;              // skip the hotbar range itself
            if (!isPearl(inv.get(i))) continue;
            int dst = free.remove(0);
            actions.add(new ClickItem(0, i, ClickItemAction.LEFT_CLICK));
            actions.add(new ClickItem(0, dst, ClickItemAction.LEFT_CLICK));
            moved++;
        }
        if (moved == 0) return false;
        INVENTORY.submit(InventoryActionRequest.builder()
            .owner(this).actions(actions).priority(INV_PRIORITY).build());
        return true;
    }

    /** Number of ender pearls sitting in the hotbar */
    private int hotbarPearlCount() {
        var inv = CACHE.getPlayerCache().getPlayerInventory();
        int n = 0;
        for (int i = 36; i <= 44; i++) {
            if (isPearl(inv.get(i))) n++;
        }
        return n;
    }

    /** Empty slots in the hotbar (36..44) - a restock tops these up */
    private int freeHotbarSlots() {
        var inv = CACHE.getPlayerCache().getPlayerInventory();
        int n = 0;
        for (int i = 36; i <= 44; i++) {
            if (inv.get(i) == null) n++;
        }
        return n;
    }

    /**
     * Resolve the pearl restock chest for the current side (manual config first,
     * otherwise auto-detect from an item frame holding an ender pearl).
     */
    private int[] pearlChestPos() {
        BlockPos bp = isInput() ? config.inputPearlChest : config.outputPearlChest;
        if (bp != null) return blockPosToInts(bp);
        int[] detected = detectPearlChest();
        if (detected != null) {
            config.setPearlChest(isInput(), detected);
            info("Auto-detected {} pearl chest at {}", isInput() ? "input" : "output", fmt(detected));
        }
        return detected;
    }

    /** True when the current side's pearl restock chest is missing or beyond restock range */
    private boolean pearlChestUnreachable() {
        int[] pearlChest = pearlChestPos();
        return pearlChest == null
            || distSqFromBot(pearlChest) > (double) config.restockMaxDistance * config.restockMaxDistance;
    }

    // ==================== containers / cargo ====================

    private int[] activeChest() {
        int[] pos = isInput() ? blockPosToInts(config.inputChest) : blockPosToInts(config.outputChest);
        return pos;
    }

    /**
     * Convert a BlockPos config value to int[] (or null when auto-detection is enabled).
     */
    private static int[] blockPosToInts(BlockPos pos) {
        return pos == null ? null : new int[]{pos.x(), pos.y(), pos.z()};
    }

    private void handleMoveToContainer(int[] pos, MoverState next) {
        if (pos == null) {
            int[] detected = detectSideChest();
            if (detected == null) {
                error("Could not locate the {} chest - set it with 'stashMover {}Chest <x> <y> <z>' or place a {} item frame next to it. Halting.",
                    isInput() ? "input" : "output", isInput() ? "input" : "output",
                    isInput() ? "redstone dust" : "redstone block");
                setState(MoverState.ERROR);
                return;
            }
            pos = detected;
            config.setChest(isInput(), pos);
            info("Auto-detected {} chest at {}", isInput() ? "input" : "output", fmt(pos));
        }
        if (openContainerAt(pos)) {
            setState(next);
        }
    }

    /**
     * Find the current side's chest by scanning item frames for the marker item.
     * Redstone dust (input side) / redstone block (output side).
     * Returns the first chest found near a marked frame within the configured radius.
     */
    private int[] detectSideChest() {
        int marker = isInput() ? ItemRegistry.REDSTONE.id() : ItemRegistry.REDSTONE_BLOCK.id();
        return detectChestByMarker(marker);
    }

    private int[] detectPearlChest() {
        return detectChestByMarker(ItemRegistry.ENDER_PEARL.id());
    }

    /**
     * Find a chest near an item frame whose held item matches the given marker,
     * within the configured scan radius from the bot.
     */
    private int[] detectChestByMarker(int markerItemId) {
        var p = CACHE.getPlayerCache();
        double maxDistSq = (double) config.frameScanRadius * config.frameScanRadius;
        int[] best = null;
        double bestD = Double.MAX_VALUE;
        for (Entity e : CACHE.getEntityCache().getEntities().values()) {
            var type = e.getEntityType();
            if (type != EntityType.ITEM_FRAME && type != EntityType.GLOW_ITEM_FRAME) continue;
            // 1.21.4 ItemFrame metadata: index 8 = FACING (Direction), index 9 = ITEM (ItemStack)
            ItemStack item = e.getMetadataValue(9, MetadataTypes.ITEM, ItemStack.class);
            if (item == null || item.getId() != markerItemId) continue;
            double d = distSq(p.getX(), p.getY(), p.getZ(), e.getX(), e.getY(), e.getZ());
            if (d > maxDistSq) continue;
            int[] chest = chestNearFrame(e);
            if (chest != null) {
                double cd = distSq(p.getX(), p.getY(), p.getZ(), chest[0] + 0.5, chest[1] + 0.5, chest[2] + 0.5);
                if (cd < bestD) {
                    bestD = cd;
                    best = chest;
                }
            }
        }
        return best;
    }

    private int[] chestNearFrame(Entity frame) {
        int fx = (int) Math.floor(frame.getX());
        int fy = (int) Math.floor(frame.getY());
        int fz = (int) Math.floor(frame.getZ());
        // 1.21.4 ItemFrame metadata: index 8 = FACING (Direction)
        // pointing away from the block they are mounted on; the container is at frame - facing
        try {
            var facing = frame.getMetadataValue(8, MetadataTypes.DIRECTION);
            if (facing != null) {
                int fx2 = 0, fy2 = 0, fz2 = 0;
                switch (facing) {
                    case DOWN -> fy2 = 1;   // frame faces down, block is above
                    case UP -> fy2 = -1;    // frame faces up, block is below
                    case NORTH -> fz2 = 1;  // frame faces north (-Z), block is at +Z
                    case SOUTH -> fz2 = -1; // frame faces south (+Z), block is at -Z
                    case WEST -> fx2 = 1;   // frame faces west (-X), block is at +X
                    case EAST -> fx2 = -1;  // frame faces east (+X), block is at -X
                }
                int ax = fx + fx2, ay = fy + fy2, az = fz + fz2;
                if (World.isChunkLoadedBlockPos(ax, az)) {
                    var b = World.getBlock(ax, ay, az);
                    if (b != null && isChestBlock(b.name())) {
                        return new int[]{ax, ay, az};
                    }
                }
            }
        } catch (Exception ignored) {}
        // fallback: check all 6 adjacent positions, preferring horizontal over vertical
        int[][] dirs = {{1,0,0},{-1,0,0},{0,0,1},{0,0,-1},{0,1,0},{0,-1,0}};
        for (int[] d : dirs) {
            int x = fx + d[0], y = fy + d[1], z = fz + d[2];
            if (!World.isChunkLoadedBlockPos(x, z)) continue;
            var b = World.getBlock(x, y, z);
            if (b != null && isChestBlock(b.name())) {
                return new int[]{x, y, z};
            }
        }
        // fallback: search a small radius in case the frame position is slightly off
        int r = config.frameChestMaxDistance;
        int[] best = null;
        int bestHand = Integer.MAX_VALUE;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    int x = fx + dx, y = fy + dy, z = fz + dz;
                    if (!World.isChunkLoadedBlockPos(x, z)) continue;
                    var b = World.getBlock(x, y, z);
                    if (b == null || !isChestBlock(b.name())) continue;
                    int hand = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                    if (hand < bestHand) {
                        bestHand = hand;
                        best = new int[]{x, y, z};
                    }
                }
            }
        }
        return best;
    }

    private static boolean isChestBlock(String name) {
        if (name == null) return false;
        return name.contains("chest") || name.contains("barrel");
    }

    /**
     * Walk to a container if needed and open it.
     * @return true if the container is open
     */
    private boolean openContainerAt(int[] pos) {
        if (containerOpen()) return true;
        double d2 = distSqFromBot(pos);
        if (d2 <= (double) config.interactRange * config.interactRange) {
            BARITONE.stop();
            if (openAttemptCooldown <= 0) {
                BARITONE.rightClickBlock(pos[0], pos[1], pos[2]);
                openAttemptCooldown = 10;
            } else {
                openAttemptCooldown--;
            }
        } else {
            if (!BARITONE.getCustomGoalProcess().isActive()) {
                if (pathTicks++ > config.pathTimeoutTicks) {
                    pathTicks = 0;
                    warn("Path to container timed out, retrying");
                }
                BARITONE.pathTo(new GoalGetToBlock(new BlockPos(pos[0], pos[1], pos[2])));
            }
        }
        return false;
    }

    private void handleCargo() {
        if (!containerOpen()) {
            setState(MoverState.MOVE_TO_CHEST);
            return;
        }
        if (INVENTORY.hasActiveRequest()) return;
        Container c = openContainer();
        int size = c.getSize();
        int chestEnd = size - 36;
        int mainStart = size - 36;
        int mainEnd = size - 9;

        if (isInput()) {
            if (firstNonEmpty(c, 0, chestEnd, s -> true) == -1) {
                if (firstCargoCheck && config.autoDisableOnEmpty) {
                    info("Input chest is empty on first check, auto-disabling");
                    setEnabled(false);
                    return;
                }
                firstCargoCheck = false;
                info("Input chest emptied after taking items, heading to unload");
                startClose(MoverState.WHISPER);
                return;
            }
            firstCargoCheck = false;
            int emptyMain = countEmpty(c, mainStart, mainEnd);
            int occupiedChest = countNonEmpty(c, 0, chestEnd);
            if (emptyMain == 0) {
                startClose(MoverState.MOVE_TO_ECHEST);
                return;
            }
            // withdraw only up to the number of empty main-inventory slots so
            // cargo never spills into the hotbar (reserved for pearls/tools)
            List<InventoryAction> actions = withdrawMainOnly(id(), Math.min(occupiedChest, emptyMain));
            if (actions.isEmpty()) {
                startClose(MoverState.MOVE_TO_ECHEST);
                return;
            }
            submitActions(actions);
            if (!cargoLogged) {
                cargoLogged = true;
                info("[INPUT] Taking {} slots into main inventory (hotbar reserved)", actions.size());
            }
        } else {
            // OUTPUT side: deposit cargo into the output chest
            List<InventoryAction> actions = depositMainOnly(id(), this::isCargo);
            if (actions.isEmpty()) {
                // no cargo in main, or chest is full and nothing can be deposited
                if (firstNonEmpty(c, mainStart, mainEnd, this::isCargo) != -1) {
                    // cargo exists in main but deposit failed -> chest is full
                    error("Output chest is full at {} - cannot deposit cargo. "
                        + "Set a new output chest with 'stashMover outputChest <x> <y> <z>' "
                        + "then run 'stashMover start'", fmt(activeChest()));
                    startClose(MoverState.ERROR);
                    return;
                }
                startClose(MoverState.MOVE_TO_ECHEST);
                return;
            }
            int depositSlots = countCargoSlots(c, mainStart, mainEnd);
            submitActions(actions);
            if (!cargoLogged) {
                cargoLogged = true;
                info("[OUTPUT] Depositing {} cargo slots into chest", depositSlots);
            }
        }
    }

    private void handleMoveToEchest() {
        if (echestPos == null) {
            echestPos = findNearestEchest();
            if (echestPos == null) {
                info("No ender chest found nearby, skipping echest step");
                setState(MoverState.WHISPER);
                return;
            }
        }
        if (openContainerAt(echestPos)) {
            setState(MoverState.ECHEST);
        }
    }

    private void handleEchest() {
        if (!containerOpen()) {
            setState(MoverState.MOVE_TO_ECHEST);
            return;
        }
        if (INVENTORY.hasActiveRequest()) return;
        Container c = openContainer();
        int size = c.getSize();
        int chestEnd = size - 36;
        int mainStart = size - 36;
        int mainEnd = size - 9;

        if (isInput()) {
            // main inventory -> ender chest (bulk shift-click, one request)
            if (firstNonEmpty(c, mainStart, mainEnd, this::isCargo) == -1) {
                startClose(MoverState.MOVE_TO_CHEST);
                return;
            }
            if (firstEmpty(c, 0, chestEnd) == -1) {
                info("Ender chest full, finishing the trip");
                startClose(MoverState.WHISPER);
                return;
            }
            submitActions(depositMainOnly(id(), this::isCargo));
        } else {
            // ender chest -> main inventory (bulk shift-click, one request)
            if (firstNonEmpty(c, 0, chestEnd, s -> true) == -1) {
                // echest is empty - but before whispering, make sure the main
                // inventory has no leftover cargo we just withdrew; deposit it first
                if (firstNonEmpty(c, mainStart, mainEnd, this::isCargo) != -1) {
                    info("Echest emptied, depositing remaining cargo before whispering");
                    startClose(MoverState.MOVE_TO_CHEST);
                } else {
                    startClose(MoverState.WHISPER);
                }
                return;
            }
            if (firstEmpty(c, mainStart, mainEnd) == -1) {
                startClose(MoverState.MOVE_TO_CHEST);
                return;
            }
            int emptyMain = countEmpty(c, mainStart, mainEnd);
            int occupiedEchest = countNonEmpty(c, 0, chestEnd);
            List<InventoryAction> actions = withdrawMainOnly(id(), Math.min(occupiedEchest, emptyMain));
            if (actions.isEmpty()) {
                startClose(MoverState.MOVE_TO_CHEST);
                return;
            }
            submitActions(actions);
        }
    }

    private void startClose(MoverState next) {
        afterClose = next;
        setState(MoverState.CLOSING);
    }

    private void handleClosing() {
        if (!containerOpen()) {
            setState(afterClose);
            return;
        }
        if (!INVENTORY.hasActiveRequest()) {
            INVENTORY.submit(InventoryActionRequest.builder()
                .owner(this).actions(new CloseContainer()).priority(INV_PRIORITY).build());
        }
    }

    // ==================== teleport ====================

    /**
     * Whisper the pearl bot standing at the DESTINATION side: that bot is next to the
     * destination pit, so loading the pearl resting there teleports us across.
     * At the input side -> whisper outputBot; at the output side -> whisper inputBot.
     */
    private void handleWhisper() {
        boolean atInput = isInput();
        String name = StashMoverConfig.normalizeUsername(atInput ? config.outputBot : config.inputBot);
        if (name.isEmpty()) {
            error("{} pearl bot username not configured", atInput ? "outputBot (whispered while at INPUT)" : "inputBot (whispered while at OUTPUT)");
            setState(MoverState.ERROR);
            return;
        }
        if (atInput) config.outputBot = name;
        else config.inputBot = name;

        // Do not ask for the pull with an empty chamber behind us: the loader bot on THIS side
        // needs our pearl to pull us back here next round. If it is gone (consumed early, or the
        // pearl never settled), walk back and throw one before whispering.
        if (currentPit != null && whisperRepairAttempts < MAX_WHISPER_REPAIRS
            && ownPearlsInPit() == 0 && !pearlRestingInPit()) {
            whisperRepairAttempts++;
            warn("No own pearl in the pit on this side - throwing one before whispering ({}/{})",
                whisperRepairAttempts, MAX_WHISPER_REPAIRS);
            BARITONE.stop();
            whisperRepairPending = true;
            setState(MoverState.MOVE_TO_THROW);
            return;
        }

        String cmd = "/w " + name + " tp";
        var p = CACHE.getPlayerCache();
        whisperTarget = name;
        loaderReplied = false;
        sendClientPacketAsync(new ServerboundChatPacket(cmd));
        info("Sent teleport command: {} (whispering the {} side's pearl bot to pull us there)", cmd, atInput ? "output" : "input");
        tpPrevX = p.getX();
        tpPrevY = p.getY();
        tpPrevZ = p.getZ();
        setState(MoverState.AWAIT_TELEPORT);
    }

    /**
     * Wait for the coordinate jump that confirms the pull. The whisper is repeated while the
     * bot is still standing where it whispered - the loader bot sometimes receives the message
     * and does nothing (its own pearl spot empty or in use), and silence there used to look
     * exactly like a slow teleport.
     */
    private void handleAwaitTeleport() {
        var p = CACHE.getPlayerCache();
        double d2 = distSq(p.getX(), p.getY(), p.getZ(), tpPrevX, tpPrevY, tpPrevZ);
        if (d2 > (double) config.tpDetectDistance * config.tpDetectDistance) {
            landingPos = new int[]{(int) Math.floor(p.getX()), (int) Math.floor(p.getY()), (int) Math.floor(p.getZ())};
            info("Teleport detected, landed at {}", fmt(landingPos));
            isInputSide = !isInputSide;
            sideConfirmRequired = true;
            sideConfirmTicks = 0;
            settleTicks = config.teleportSettleTicks;
            whisperTarget = "";
            loaderReplied = false;
            setState(MoverState.START);
            return;
        }
        // No answer at all after a while: the whisper probably never reached the loader (chat
        // throttle, wrong name, loader busy), so ask again instead of sitting out the whole
        // teleport timeout. Once it has answered we only wait for the pull.
        if (!loaderReplied && !whisperTarget.isEmpty()
            && stateTicks > config.whisperReplyTimeoutTicks) {
            if (tpRetries++ < MAX_TP_RETRIES) {
                warn("No reply from {} after {} ticks - whispering again ({}/{})",
                    whisperTarget, config.whisperReplyTimeoutTicks, tpRetries, MAX_TP_RETRIES);
                setState(MoverState.WHISPER);
            } else {
                error("No reply from {} after {} whispers - the pearl bot is not answering (check its name and that it is online)",
                    whisperTarget, MAX_TP_RETRIES + 1);
                setState(MoverState.ERROR);
            }
            return;
        }
        if (!awaitTeleportTimer.tick(config.teleportTimeoutTicks)) {
            if (stateTicks % 100 == 0) {
                dbg("Waiting for the pull ({}/{} ticks, whisper {}/{})",
                    stateTicks, config.teleportTimeoutTicks, tpRetries, MAX_TP_RETRIES);
            }
            return;
        }
        // timeout: the previous whisper did nothing. Ask again from the same spot, keeping
        // tpPrev* in sync so the next wait measures from here.
        if (tpRetries++ < MAX_TP_RETRIES) {
            warn("Teleport timeout after {} ticks, whispering again ({}/{})", config.teleportTimeoutTicks, tpRetries, MAX_TP_RETRIES);
            setState(MoverState.WHISPER);
        } else {
            error("Teleport failed after {} whispers - the pearl bot on the other side is not pulling us (check its pearl spot)",
                MAX_TP_RETRIES);
            setState(MoverState.ERROR);
        }
    }

    private void handleError() {
        if (!errorHandled) {
            BARITONE.stop();
            errorHandled = true;
            error("StashMover halted. Fix the issue and run 'stashMover start' to resume.");
        }
    }

    // ==================== pearl helpers ====================

    private int pearlId() {
        return ItemRegistry.ENDER_PEARL.id();
    }

    private boolean isPearl(ItemStack s) {
        return s != null && s.getId() == pearlId();
    }

    private boolean isCargo(ItemStack s) {
        return s != null && s.getId() != pearlId();
    }

    private int pearlCount() {
        int count = 0;
        for (ItemStack s : CACHE.getPlayerCache().getPlayerInventory()) {
            if (isPearl(s)) count += s.getAmount();
        }
        return count;
    }

    private boolean hasAnyPearl() {
        return InventoryUtil.searchPlayerInventory(this::isPearl) != -1;
    }

    private boolean pearlInHeld() {
        int held = CACHE.getPlayerCache().getHeldItemSlot();
        var inv = CACHE.getPlayerCache().getPlayerInventory();
        return isPearl(inv.get(36 + held));
    }

    private void submitMovePearlToHotbar() {
        var inv = CACHE.getPlayerCache().getPlayerInventory();
        int held = CACHE.getPlayerCache().getHeldItemSlot();

        for (int i = 36; i <= 44; i++) {
            if (isPearl(inv.get(i))) {
                INVENTORY.submit(InventoryActionRequest.builder()
                    .owner(this).actions(new SetHeldItem(i - 36)).priority(INV_PRIORITY).build());
                return;
            }
        }

        int src = -1;
        if (isPearl(inv.get(45))) {
            src = 45;
        } else {
            for (int i = 9; i <= 35; i++) {
                if (isPearl(inv.get(i))) {
                    src = i;
                    break;
                }
            }
        }
        if (src == -1) return;

        int empty = -1;
        for (int i = 36; i <= 44; i++) {
            if (inv.get(i) == null) {
                empty = i;
                break;
            }
        }
        if (empty != -1) {
            List<InventoryAction> actions = List.of(
                new ClickItem(0, src, ClickItemAction.LEFT_CLICK),
                new ClickItem(0, empty, ClickItemAction.LEFT_CLICK),
                new SetHeldItem(empty - 36)
            );
            INVENTORY.submit(InventoryActionRequest.builder()
                .owner(this).actions(actions).priority(INV_PRIORITY).build());
        } else {
            INVENTORY.submit(InventoryActionRequest.builder()
                .owner(this).actions(InventoryActionMacros.swapSlots(0, src, 36 + held))
                .priority(INV_PRIORITY).build());
        }
    }

    // ==================== inventory actions ====================

    /**
     * Submit a bulk transfer. Uses config.bulkTransferDelayTicks to override the slow
     * global per-action delay so all 27 slots move in one tick (or a configurable
     * few ticks) instead of one action every 5 ticks.
     */
    private void submitActions(List<InventoryAction> actions) {
        if (actions.isEmpty()) return;
        INVENTORY.submit(InventoryActionRequest.builder()
            .owner(this)
            .actions(actions)
            .actionDelayTicks(Math.max(0, config.bulkTransferDelayTicks))
            .priority(INV_PRIORITY)
            .build());
    }

    /**
     * Build shift-click actions that deposit cargo from the MAIN inventory (slots
     * [size-36, size-9)) into the open container. The hotbar ([size-9, size-1)) and
     * offhand are intentionally excluded so the held pearls / rockets / totem stay put.
     */
    private List<InventoryAction> depositMainOnly(int containerId, Predicate<ItemStack> filter) {
        Container c = openContainer();
        if (c.getContainerId() != containerId) return List.of();
        int size = c.getSize();
        int mainStart = size - 36;
        int mainEnd = size - 9;
        List<InventoryAction> actions = new ArrayList<>();
        for (int i = mainStart; i < mainEnd; i++) {
            ItemStack s = c.getItemStack(i);
            if (s == null) continue;
            if (!filter.test(s)) continue;
            actions.add(new ShiftClick(containerId, i, ShiftClickItemAction.LEFT_CLICK));
        }
        return actions;
    }

    private int firstNonEmpty(Container c, int from, int to, Predicate<ItemStack> filter) {
        for (int i = from; i < to; i++) {
            ItemStack s = c.getItemStack(i);
            if (s != null && filter.test(s)) return i;
        }
        return -1;
    }

    private int firstEmpty(Container c, int from, int to) {
        for (int i = from; i < to; i++) {
            if (c.getItemStack(i) == null) return i;
        }
        return -1;
    }

    private int countNonEmpty(Container c, int from, int to) {
        int n = 0;
        for (int i = from; i < to; i++) {
            if (c.getItemStack(i) != null) n++;
        }
        return n;
    }

    private int countEmpty(Container c, int from, int to) {
        int n = 0;
        for (int i = from; i < to; i++) {
            if (c.getItemStack(i) == null) n++;
        }
        return n;
    }

    /**
     * Build shift-click actions that move at most {@code maxMoves} occupied container
     * slots (0..chestEnd) into the player inventory. The vanilla shift-click destination
     * fills the main inventory first and only overflows into the hotbar when the main
     * inventory is full, so capping the number of moves at the empty main-inventory
     * count guarantees the hotbar stays untouched.
     */
    private List<InventoryAction> withdrawMainOnly(int containerId, int maxMoves) {
        Container c = openContainer();
        if (c.getContainerId() != containerId) return List.of();
        int size = c.getSize();
        int chestEnd = size - 36;
        List<InventoryAction> actions = new ArrayList<>();
        for (int i = 0; i < chestEnd && actions.size() < maxMoves; i++) {
            if (c.getItemStack(i) == null) continue;
            actions.add(new ShiftClick(containerId, i, ShiftClickItemAction.LEFT_CLICK));
        }
        return actions;
    }

    private int countCargoSlots(Container c, int from, int to) {
        int n = 0;
        for (int i = from; i < to; i++) {
            var s = c.getItemStack(i);
            if (s != null && isCargo(s)) n++;
        }
        return n;
    }

    // ==================== input ====================

    private void submitInput(Input input, float yaw, float pitch) {
        INPUTS.submit(InputRequest.builder()
            .owner(this)
            .input(input)
            .yaw(yaw)
            .pitch(pitch)
            .priority(MOVE_PRIORITY)
            .build());
    }

    // ==================== world / distance ====================

    private int[] findNearestEchest() {
        var p = CACHE.getPlayerCache();
        int px = (int) Math.floor(p.getX());
        int py = (int) Math.floor(p.getY());
        int pz = (int) Math.floor(p.getZ());
        int r = config.echestSearchRadius;
        int[] best = null;
        double bestD = Double.MAX_VALUE;
        for (int x = px - r; x <= px + r; x++) {
            for (int z = pz - r; z <= pz + r; z++) {
                if (!World.isChunkLoadedBlockPos(x, z)) continue;
                for (int y = py - 8; y <= py + 8; y++) {
                    var b = World.getBlock(x, y, z);
                    if (b != null && b.name().contains("ender_chest")) {
                        double d = distSqFromBot(x + 0.5, y + 0.5, z + 0.5);
                        if (d < bestD) {
                            bestD = d;
                            best = new int[]{x, y, z};
                        }
                    }
                }
            }
        }
        return best;
    }

    private double distSqFromBot(int[] pos) {
        return distSqFromBot(pos[0] + 0.5, pos[1] + 0.5, pos[2] + 0.5);
    }

    private double distSqFromBot(double x, double y, double z) {
        var p = CACHE.getPlayerCache();
        return distSq(p.getX(), p.getY(), p.getZ(), x, y, z);
    }

    private boolean containerOpen() {
        return id() != 0;
    }

    private int id() {
        return CACHE.getPlayerCache().getInventoryCache().getOpenContainerId();
    }

    private Container openContainer() {
        return CACHE.getPlayerCache().getInventoryCache().getOpenContainer();
    }

    private static double distSq(double ax, double ay, double az, double bx, double by, double bz) {
        double dx = ax - bx, dy = ay - by, dz = az - bz;
        return dx * dx + dy * dy + dz * dz;
    }

    private static String fmt(int[] pos) {
        if (pos == null) return "null";
        return "[" + pos[0] + ", " + pos[1] + ", " + pos[2] + "]";
    }
}
