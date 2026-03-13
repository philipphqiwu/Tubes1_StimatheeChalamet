package himothee2;

import battlecode.common.Direction;
import battlecode.common.GameActionException;
import battlecode.common.MapInfo;
import battlecode.common.MapLocation;
import battlecode.common.PaintType;
import battlecode.common.RobotController;
import battlecode.common.RobotInfo;
import battlecode.common.UnitType;
import static himothee2.Shared.LATEGAME_OFFENSIVE_TURNS;
import static himothee2.Shared.LOW_PAINT_THRESHOLD;
import himothee2.Shared.RobotState;
import static himothee2.Shared.SPLASHER_UNLOCK_TURNS;
import static himothee2.Shared.SRP_PATTERN;
import static himothee2.Shared.SRP_TOWER_MIN;
import static himothee2.Shared.bug0;
import static himothee2.Shared.bug2;
import static himothee2.Shared.checkNearbyRuins;
import static himothee2.Shared.directions;
import static himothee2.Shared.exploreDir;
import static himothee2.Shared.exploreLoc;
import static himothee2.Shared.exploreSetRound;
import static himothee2.Shared.extendToEdge;
import static himothee2.Shared.getIsSecondary;
import static himothee2.Shared.guessEnemyLocation;
import static himothee2.Shared.initExploreDir;
import static himothee2.Shared.knownEnemyTowers;
import static himothee2.Shared.knownTowers;
import static himothee2.Shared.mapScale;
import static himothee2.Shared.preRetreatState;
import static himothee2.Shared.reportEnemyTowers;
import static himothee2.Shared.runRetreat;
import static himothee2.Shared.state;
import static himothee2.Shared.targetEnemyRuin;
import static himothee2.Shared.updateFriendlyTowers;
import static himothee2.Shared.updateSymmetryGuess;

/**
 * Soldier logic: exploring, building towers, painting patterns, attacking, SRP.
 * ~1/3 of soldiers (ID % 3 == 0) are designated SRP specialists.
 */
public class SoldierPlayer {

    // ========== GENERAL SOLDIER STATE ==========
    static MapLocation paintingRuinLoc = null;
    static UnitType paintingRuinType = null;
    static int paintingTurns = 0;
    static int turnsWithoutAttack = 0;
    static int soldierExploreTurns = 0;
    static boolean needsToDeliverSave = false;
    static MapLocation deliverTarget = null;
    static int lastSaveRequestTurn = -999;
    static int deliveryTurns = 0;
    static final int MAX_DELIVERY_TURNS = 10;

    // ========== SRP SPECIALIST STATE ==========
    /** Whether this soldier is an SRP specialist (set once, based on ID). */
    static boolean isSRPSpecialist = false;
    static boolean specialistInitialized = false;
    /** Current SRP center being worked on. */
    static MapLocation srpSpecialistTarget = null;
    /** Whether we have already called markResourcePattern for this target. */
    static boolean srpMarked = false;
    /** How many turns we've been stuck on one target. */
    static int srpStuckTurns = 0;

    static void clearPaintingState() {
        paintingRuinLoc = null;
        needsToDeliverSave = false;
        deliverTarget = null;
        lastSaveRequestTurn = -999;
        deliveryTurns = 0;
    }

    /** True if this soldier has the lowest ID among ally soldiers near the painting ruin. */
    static boolean isLowestIdSoldierNearRuin(RobotController rc) throws GameActionException {
        if (paintingRuinLoc == null) return true;
        int myID = rc.getID();
        RobotInfo[] nearbyAllies = rc.senseNearbyRobots(paintingRuinLoc, 18, rc.getTeam());
        for (RobotInfo ally : nearbyAllies) {
            if (ally.getType() == UnitType.SOLDIER && ally.getID() < myID) {
                return false;
            }
        }
        return true;
    }

    public static void runSoldier(RobotController rc) throws GameActionException {
        int round = rc.getRoundNum();

        // ---- One-time role assignment ----
        if (!specialistInitialized) {
            specialistInitialized = true;
            isSRPSpecialist = (rc.getID() % 3 == 0);
        }

        // ---- Initial state assignment ----
        if (state == RobotState.STARTING) {
            if (isSRPSpecialist) {
                state = RobotState.SRP_SPECIALIST;
            } else if (round >= SPLASHER_UNLOCK_TURNS || rc.getNumberTowers() >= 6) {
                state = (rc.getID() % 2 == 0) ? RobotState.ATTACKING : RobotState.EXPLORING;
            } else {
                state = RobotState.EXPLORING;
            }
        }

        // ---- Route SRP specialist to their own handler ----
        if (isSRPSpecialist || state == RobotState.SRP_SPECIALIST) {
            runSRPSpecialist(rc);
            return;
        }

        // ---- Retreat threshold for regular soldiers ----
        MapInfo standingOn = rc.senseMapInfo(rc.getLocation());
        int retreatThreshold = LOW_PAINT_THRESHOLD - 20 + (rc.getID() % 40);
        if (state == RobotState.PAINTING_PATTERN) {
            retreatThreshold = 10;
        }
        if (standingOn.getPaint() == PaintType.ENEMY_PRIMARY || standingOn.getPaint() == PaintType.ENEMY_SECONDARY) {
            if (state != RobotState.PAINTING_PATTERN) retreatThreshold += 30;
        }
        if (state != RobotState.RETREAT && rc.getPaint() <= retreatThreshold && !knownTowers.isEmpty()) {
            preRetreatState = state;
            state = RobotState.RETREAT;
        }

        if (state == RobotState.RETREAT) {
            runRetreat(rc);
            return;
        }

        // ---- PAINTING_PATTERN ----
        if (state == RobotState.PAINTING_PATTERN) {
            rc.setIndicatorString("im a painter at " + paintingRuinLoc);

            if (needsToDeliverSave) {
                deliveryTurns++;
                updateFriendlyTowers(rc);
                if (!Shared.isSaving) {
                    needsToDeliverSave = false;
                    deliverTarget = null;
                    deliveryTurns = 0;
                    rc.setIndicatorString("Save delivered! Returning to ruin " + paintingRuinLoc);
                } else if (deliveryTurns > MAX_DELIVERY_TURNS) {
                    needsToDeliverSave = false;
                    deliverTarget = null;
                    deliveryTurns = 0;
                    Shared.isSaving = false;
                    rc.setIndicatorString("Delivery timeout, back to painting");
                } else {
                    if (deliverTarget == null || !knownTowers.contains(deliverTarget)) {
                        deliverTarget = Shared.nearestFriendlyTower(rc);
                    }
                    if (deliverTarget != null && rc.isMovementReady()) {
                        bug2(rc, deliverTarget);
                        rc.setIndicatorString("Delivering save msg to tower at " + deliverTarget + " (" + deliveryTurns + "/" + MAX_DELIVERY_TURNS + ")");
                    }
                    paintingTurns++;
                    return;
                }
            }

            runPaintPattern(rc);
            paintingTurns++;
            updateFriendlyTowers(rc);

            if (Shared.isSaving && !needsToDeliverSave) {
                if (isLowestIdSoldierNearRuin(rc)) {
                    needsToDeliverSave = true;
                    deliverTarget = Shared.nearestFriendlyTower(rc);
                    deliveryTurns = 0;
                    lastSaveRequestTurn = rc.getRoundNum();
                } else {
                    Shared.isSaving = false;
                }
            }

            if (!needsToDeliverSave && paintingRuinLoc != null
                && rc.getChips() < Shared.TOWER_CHIP_COST
                && rc.getRoundNum() - lastSaveRequestTurn >= 8) {
                Shared.isSaving = true;
                Shared.savingForRuinLoc = paintingRuinLoc;
            }

        // ---- EXPLORING ----
        } else if (state == RobotState.EXPLORING) {
            rc.setIndicatorString("im exploring (" + soldierExploreTurns + ")");
            soldierExploreTurns++;
            MapInfo[] nearbyTiles = rc.senseNearbyMapInfos();
            MapInfo curRuin = null;
            int curDist = 999999;

            for (MapInfo tile : nearbyTiles) {
                if (tile.hasRuin() && rc.senseRobotAtLocation(tile.getMapLocation()) == null) {
                    int dist = tile.getMapLocation().distanceSquaredTo(rc.getLocation());
                    if (dist < curDist) {
                        curRuin = tile;
                        curDist = dist;
                    }
                }
            }

            if (curRuin == null) {
                for (MapInfo tile : nearbyTiles) {
                    if (rc.canSenseLocation(tile.getMapLocation()) && rc.senseRobotAtLocation(tile.getMapLocation()) != null && rc.senseRobotAtLocation(tile.getMapLocation()).getTeam() != rc.getTeam()) {
                        if (round >= SPLASHER_UNLOCK_TURNS || rc.getNumberTowers() >= 4) {
                            state = RobotState.ATTACKING;
                        }
                        break;
                    }
                }
            }

            if (curRuin != null) {
                soldierExploreTurns = 0;
                if (curDist > 4) bug0(rc, curRuin.getMapLocation());
                else {
                    boolean anotherSoldierPainting = false;
                    RobotInfo[] nearbyAllies = rc.senseNearbyRobots(curRuin.getMapLocation(), 4, rc.getTeam());
                    for (RobotInfo ally : nearbyAllies) {
                        if (ally.getType() == UnitType.SOLDIER && ally.getID() != rc.getID()) {
                            anotherSoldierPainting = true;
                            break;
                        }
                    }
                    if (!anotherSoldierPainting) {
                        state = RobotState.PAINTING_PATTERN;
                        paintingRuinType = detectExistingPatternType(rc, curRuin.getMapLocation());
                        turnsWithoutAttack = 0;
                        paintingTurns = 0;
                        paintingRuinLoc = curRuin.getMapLocation();
                    } else {
                        Direction awayFromRuin = curRuin.getMapLocation().directionTo(rc.getLocation());
                        if (rc.canMove(awayFromRuin)) rc.move(awayFromRuin);
                        else if (rc.canMove(awayFromRuin.rotateLeft())) rc.move(awayFromRuin.rotateLeft());
                        else if (rc.canMove(awayFromRuin.rotateRight())) rc.move(awayFromRuin.rotateRight());
                    }
                }
            }

            if (soldierExploreTurns >= 60 && (round >= SPLASHER_UNLOCK_TURNS || rc.getNumberTowers() >= 4)) {
                state = RobotState.ATTACKING;
                soldierExploreTurns = 0;
                targetEnemyRuin = null;
            } else if (rc.isMovementReady()) {
                if (exploreDir == null) {
                    initExploreDir(rc);
                }
                boolean needNewDir = rc.getLocation().distanceSquaredTo(exploreLoc) <= 8
                    || (round - exploreSetRound) > 30
                    || Shared.isNearEdge(rc, rc.getLocation());
                if (needNewDir) {
                    if (Shared.isNearEdge(rc, rc.getLocation())) {
                        exploreDir = Shared.pickExploreDir(rc, rc.getLocation());
                    } else {
                        int dirIdx = (rc.getID() + round / 30) % 8;
                        exploreDir = directions[dirIdx];
                    }
                    exploreLoc = extendToEdge(rc, rc.getLocation(), exploreDir);
                    exploreSetRound = round;
                }
                bug0(rc, exploreLoc);
            }

            updateFriendlyTowers(rc);
            checkNearbyRuins(rc);
            updateSymmetryGuess(rc);
            reportEnemyTowers(rc);

        // ---- ATTACKING ----
        } else if (state == RobotState.ATTACKING) {
            updateSymmetryGuess(rc);
            reportEnemyTowers(rc);

            RobotInfo[] nearbyAllies = rc.senseNearbyRobots(-1, rc.getTeam());
            int allyCombatants = 0;
            for (RobotInfo ally : nearbyAllies) {
                if (ally.getType() == UnitType.SOLDIER || ally.getType() == UnitType.SPLASHER) {
                    allyCombatants++;
                }
            }

            if (targetEnemyRuin == null) {
                MapLocation[] infos = rc.senseNearbyRuins(-1);
                MapLocation ruin;

                for (MapLocation info : infos) {
                    ruin = info;
                    if (ruin != null && rc.senseRobotAtLocation(ruin) != null && rc.senseRobotAtLocation(ruin).getTeam().opponent() == rc.getTeam()) {
                        targetEnemyRuin = ruin;
                        break;
                    }
                }

                if (targetEnemyRuin == null && !knownEnemyTowers.isEmpty()) {
                    MapLocation closest = null;
                    int closestDist = Integer.MAX_VALUE;
                    for (MapLocation et : knownEnemyTowers) {
                        int d = rc.getLocation().distanceSquaredTo(et);
                        if (d < closestDist) { closestDist = d; closest = et; }
                    }
                    targetEnemyRuin = closest;
                }

                if (targetEnemyRuin == null && infos.length > 0) {
                    ruin = infos[0];
                    if (rc.senseRobotAtLocation(ruin) == null) {
                        state = RobotState.EXPLORING;
                    }
                    targetEnemyRuin = guessEnemyLocation(rc, ruin);
                }

                if (targetEnemyRuin == null && !knownTowers.isEmpty()) {
                    targetEnemyRuin = guessEnemyLocation(rc, knownTowers.get(0));
                }
            }

            if (targetEnemyRuin != null) {
                if (rc.canSenseLocation(targetEnemyRuin)) {
                    if (rc.senseRobotAtLocation(targetEnemyRuin) == null || (rc.canSenseRobotAtLocation(targetEnemyRuin) && rc.senseRobotAtLocation(targetEnemyRuin).getTeam() == rc.getTeam())) {
                        state = RobotState.EXPLORING;
                        targetEnemyRuin = null;
                    }
                }

                if (targetEnemyRuin != null) {
                    int dsquared = rc.getLocation().distanceSquaredTo(targetEnemyRuin);

                    if (dsquared <= 8) {
                        if (allyCombatants >= 1 || round >= LATEGAME_OFFENSIVE_TURNS) {
                            if (rc.canAttack(targetEnemyRuin)) {
                                rc.attack(targetEnemyRuin);
                            }
                            Direction away = rc.getLocation().directionTo(targetEnemyRuin).opposite();
                            if (rc.canMove(away)) {
                                rc.move(away);
                            } else if (rc.canMove(away.rotateLeft())) {
                                rc.move(away.rotateLeft());
                            } else if (rc.canMove(away.rotateRight())) {
                                rc.move(away.rotateRight());
                            }
                        } else {
                            rc.setIndicatorString("Waiting for allies near " + targetEnemyRuin);
                            if (rc.canAttack(targetEnemyRuin)) {
                                rc.attack(targetEnemyRuin);
                            }
                        }
                    } else {
                        for (Direction d : directions) {
                            MapLocation newLoc = rc.getLocation().add(d);
                            if (newLoc.isWithinDistanceSquared(targetEnemyRuin, 8)) {
                                if (rc.canMove(d)) {
                                    rc.move(d);
                                    if (rc.canAttack(targetEnemyRuin)) {
                                        rc.attack(targetEnemyRuin);
                                    }
                                    break;
                                }
                            }
                        }
                        bug2(rc, targetEnemyRuin);
                    }

                    rc.setIndicatorDot(targetEnemyRuin, 0, 255, 0);
                    rc.setIndicatorString("Moving to enemy ruin at " + targetEnemyRuin);
                }
            }
        }

        // ---- Paint current tile (non-specialist common action) ----
        MapInfo currentTile = rc.senseMapInfo(rc.getLocation());
        if (!currentTile.getPaint().isAlly() && rc.canAttack(rc.getLocation())) {
            if (state == RobotState.PAINTING_PATTERN && paintingRuinLoc != null
                && Shared.isWithinPattern(paintingRuinLoc, rc.getLocation())) {
                boolean isSecondary = getIsSecondary(paintingRuinLoc, rc.getLocation(), paintingRuinType);
                rc.attack(rc.getLocation(), isSecondary);
            } else {
                rc.attack(rc.getLocation());
            }
        }

        tryCompletePatterns(rc);
    }

    // ========== SRP SPECIALIST ==========

    /**
     * Full behavior loop for an SRP specialist soldier.
     * Specialists never build towers or attack — they exclusively mark and paint SRP patterns.
     */
    static void runSRPSpecialist(RobotController rc) throws GameActionException {
        int round = rc.getRoundNum();

        // Retreat if low on paint
        int retreatThreshold = 30;
        if (state != RobotState.RETREAT && rc.getPaint() <= retreatThreshold && !knownTowers.isEmpty()) {
            preRetreatState = RobotState.SRP_SPECIALIST;
            state = RobotState.RETREAT;
        }
        if (state == RobotState.RETREAT) {
            runRetreat(rc);
            // After refill, go back to specialist mode
            if (state != RobotState.RETREAT) state = RobotState.SRP_SPECIALIST;
            return;
        }

        state = RobotState.SRP_SPECIALIST;
        updateFriendlyTowers(rc);
        updateSymmetryGuess(rc);

        // ---- Try to complete any nearby completed patterns ----
        tryCompletePatterns(rc);

        // ---- If we have a target, work it ----
        if (srpSpecialistTarget != null) {
            // Validate target is still markable
            if (rc.canSenseLocation(srpSpecialistTarget)) {
                if (!srpMarked && !rc.canMarkResourcePattern(srpSpecialistTarget)) {
                    // Someone already marked/completed it — find a new one
                    srpSpecialistTarget = null;
                    srpMarked = false;
                    srpStuckTurns = 0;
                }
            }

            // Check if this target's pattern is complete
            if (srpSpecialistTarget != null && rc.canSenseLocation(srpSpecialistTarget)
                && rc.canCompleteResourcePattern(srpSpecialistTarget)) {
                rc.completeResourcePattern(srpSpecialistTarget);
                rc.setIndicatorString("SRP SPEC ✓ completed @ " + srpSpecialistTarget);
                srpSpecialistTarget = null;
                srpMarked = false;
                srpStuckTurns = 0;
                return;
            }

            if (srpSpecialistTarget != null) {
                srpStuckTurns++;
                if (srpStuckTurns > 60) {
                    // Gave up — too long on one pattern (may be blocked)
                    srpSpecialistTarget = null;
                    srpMarked = false;
                    srpStuckTurns = 0;
                }
            }
        }

        // ---- No target: scan for a new SRP center ----
        if (srpSpecialistTarget == null) {
            srpSpecialistTarget = findBestSRPCandidate(rc);
            srpMarked = false;
            srpStuckTurns = 0;
        }

        // ---- Work the target ----
        if (srpSpecialistTarget != null) {
            int distToCenter = rc.getLocation().distanceSquaredTo(srpSpecialistTarget);

            // Step 1: Navigate close enough to mark (dist² ≤ 2 = adjacent/diagonal)
            if (!srpMarked) {
                if (distToCenter <= 2 && rc.canMarkResourcePattern(srpSpecialistTarget)) {
                    rc.markResourcePattern(srpSpecialistTarget);
                    srpMarked = true;
                    rc.setIndicatorString("SRP SPEC marked @ " + srpSpecialistTarget);
                } else if (distToCenter > 2) {
                    bug0(rc, srpSpecialistTarget);
                    rc.setIndicatorString("SRP SPEC → center @ " + srpSpecialistTarget + " (dist²=" + distToCenter + ")");
                } else if (!rc.canMarkResourcePattern(srpSpecialistTarget)) {
                    // Already marked by someone — treat as marked, start painting
                    srpMarked = true;
                }
            }

            // Step 2: Paint pattern tiles
            if (srpMarked) {
                boolean painted = paintSRPTiles(rc, srpSpecialistTarget);

                // Step 3: Try to complete
                if (rc.canCompleteResourcePattern(srpSpecialistTarget)) {
                    rc.completeResourcePattern(srpSpecialistTarget);
                    rc.setIndicatorString("SRP SPEC ✓ completed @ " + srpSpecialistTarget);
                    srpSpecialistTarget = null;
                    srpMarked = false;
                    srpStuckTurns = 0;
                    return;
                }

                // Move toward nearest unpainted tile in the 5×5 pattern
                if (rc.isMovementReady()) {
                    MapLocation bestUnpainted = findNearestUnpaintedSRPTile(rc, srpSpecialistTarget);
                    if (bestUnpainted != null) {
                        Direction toward = rc.getLocation().directionTo(bestUnpainted);
                        // Don't drift too far from the pattern center (stay within 5 tiles)
                        MapLocation afterMove = rc.getLocation().add(toward);
                        if (afterMove.distanceSquaredTo(srpSpecialistTarget) <= 20) {
                            if (rc.canMove(toward)) rc.move(toward);
                            else if (rc.canMove(toward.rotateLeft())) rc.move(toward.rotateLeft());
                            else if (rc.canMove(toward.rotateRight())) rc.move(toward.rotateRight());
                        } else {
                            // Orbit the center instead
                            Direction toCenter = rc.getLocation().directionTo(srpSpecialistTarget);
                            if (rc.canMove(toCenter)) rc.move(toCenter);
                        }
                    } else if (distToCenter > 2) {
                        // All visible tiles done — move to center to see the rest
                        bug0(rc, srpSpecialistTarget);
                    }
                }
                rc.setIndicatorString("SRP SPEC painting @ " + srpSpecialistTarget + (painted ? " ★" : ""));
            }
        } else {
            // No SRP candidate visible — explore like a normal soldier (biased away from enemy)
            if (rc.isMovementReady()) {
                if (exploreDir == null) initExploreDir(rc);
                boolean needNewDir = rc.getLocation().distanceSquaredTo(exploreLoc) <= 8
                    || (round - exploreSetRound) > 30
                    || Shared.isNearEdge(rc, rc.getLocation());
                if (needNewDir) {
                    if (Shared.isNearEdge(rc, rc.getLocation())) {
                        exploreDir = Shared.pickExploreDir(rc, rc.getLocation());
                    } else {
                        // Bias toward own side (away from enemies) to paint safe territory
                        int dirIdx = (rc.getID() + round / 20) % 8;
                        exploreDir = directions[dirIdx];
                    }
                    exploreLoc = extendToEdge(rc, rc.getLocation(), exploreDir);
                    exploreSetRound = round;
                }
                bug0(rc, exploreLoc);
            }
            rc.setIndicatorString("SRP SPEC exploring...");
        }

        // Always paint current tile if possible
        if (rc.isActionReady()) {
            MapInfo cur = rc.senseMapInfo(rc.getLocation());
            if (!cur.getPaint().isAlly() && rc.canAttack(rc.getLocation())) {
                if (srpSpecialistTarget != null && Shared.isWithinSRPPattern(srpSpecialistTarget, rc.getLocation())) {
                    int dx = rc.getLocation().x - srpSpecialistTarget.x + 2;
                    int dy = rc.getLocation().y - srpSpecialistTarget.y + 2;
                    boolean wantSec = SRP_PATTERN[dx][dy] == 2;
                    rc.attack(rc.getLocation(), wantSec);
                } else {
                    rc.attack(rc.getLocation());
                }
            }
        }
    }

    /**
     * Find the best SRP center candidate in vision.
     * Scans a 3×3 grid of aligned positions (multiples of 4 ±2) near the robot.
     * Picks the one with the most tiles still needing paint.
     */
    static MapLocation findBestSRPCandidate(RobotController rc) throws GameActionException {
        MapLocation me = rc.getLocation();
        MapLocation bestCenter = null;
        int bestUnpainted = -1;

        for (int dx = -8; dx <= 8; dx += 4) {
            for (int dy = -8; dy <= 8; dy += 4) {
                MapLocation center = alignSRPCenter(me.x + dx, me.y + dy);
                if (!rc.onTheMap(center)) continue;
                if (!rc.canMarkResourcePattern(center)) continue;

                // Count how many tiles need paint and check for enemy paint contamination
                int unpainted = 0;
                boolean bad = false;
                for (int x = -2; x <= 2 && !bad; x++) {
                    for (int y = -2; y <= 2 && !bad; y++) {
                        MapLocation tile = center.translate(x, y);
                        if (!rc.canSenseLocation(tile)) { unpainted++; continue; } // assume needs paint
                        MapInfo info = rc.senseMapInfo(tile);
                        if (info.getPaint().isEnemy()) { bad = true; break; }
                        boolean wantSec = SRP_PATTERN[x + 2][y + 2] == 2;
                        PaintType want = wantSec ? PaintType.ALLY_SECONDARY : PaintType.ALLY_PRIMARY;
                        if (info.getPaint() != want) unpainted++;
                    }
                }
                if (bad) continue;
                if (unpainted == 0) continue; // already complete
                if (unpainted > bestUnpainted) {
                    bestUnpainted = unpainted;
                    bestCenter = center;
                }
            }
        }
        return bestCenter;
    }

    /**
     * Find the nearest tile within the SRP 5×5 pattern that still needs painting.
     */
    static MapLocation findNearestUnpaintedSRPTile(RobotController rc, MapLocation center) throws GameActionException {
        MapLocation best = null;
        int bestDist = Integer.MAX_VALUE;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                MapLocation tile = center.translate(dx, dy);
                if (!rc.canSenseLocation(tile)) continue;
                MapInfo info = rc.senseMapInfo(tile);
                if (info.isWall() || info.hasRuin()) continue;
                boolean wantSec = SRP_PATTERN[dx + 2][dy + 2] == 2;
                PaintType want = wantSec ? PaintType.ALLY_SECONDARY : PaintType.ALLY_PRIMARY;
                if (info.getPaint() != want) {
                    int dist = rc.getLocation().distanceSquaredTo(tile);
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = tile;
                    }
                }
            }
        }
        return best;
    }

    // ========== PAINTING PATTERN ==========

    public static void runPaintPattern(RobotController rc) throws GameActionException {
        if (paintingRuinLoc == null) {
            state = RobotState.EXPLORING;
            clearPaintingState();
            return;
        }
        if (rc.canSenseLocation(paintingRuinLoc)) {
            RobotInfo atRuin = rc.senseRobotAtLocation(paintingRuinLoc);
            if (atRuin != null) {
                state = RobotState.EXPLORING;
                clearPaintingState();
                return;
            }
        }

        int distToRuin = rc.getLocation().distanceSquaredTo(paintingRuinLoc);
        if (distToRuin > 18) {
            bug0(rc, paintingRuinLoc);
            rc.setIndicatorString("Returning to ruin at " + paintingRuinLoc);
            if (paintingTurns > 30) {
                state = RobotState.EXPLORING;
                clearPaintingState();
            }
            return;
        }

        if (rc.canCompleteTowerPattern(paintingRuinType, paintingRuinLoc)) {
            rc.completeTowerPattern(paintingRuinType, paintingRuinLoc);
            state = RobotState.EXPLORING;
            clearPaintingState();
            return;
        }

        MapLocation bestUnpainted = null;
        int bestUnpaintedDist = Integer.MAX_VALUE;
        MapLocation bestAttackable = null;
        int bestAttackableDist = Integer.MAX_VALUE;
        boolean paintedSomething = false;
        int tilesNeedingPaint = 0;

        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                MapLocation tile = paintingRuinLoc.translate(dx, dy);
                if (tile.equals(paintingRuinLoc)) continue;
                if (!rc.canSenseLocation(tile)) continue;
                MapInfo info = rc.senseMapInfo(tile);
                if (info.isWall() || info.hasRuin()) continue;

                boolean isSecondary = getIsSecondary(paintingRuinLoc, tile, paintingRuinType);
                PaintType current = info.getPaint();
                boolean needsPaint = (current == PaintType.EMPTY)
                    || (current == PaintType.ENEMY_PRIMARY || current == PaintType.ENEMY_SECONDARY)
                    || (current.isAlly() && current.isSecondary() != isSecondary);

                if (needsPaint) {
                    tilesNeedingPaint++;
                    int dist = rc.getLocation().distanceSquaredTo(tile);
                    if (rc.isActionReady() && rc.canAttack(tile)) {
                        if (dist < bestAttackableDist) {
                            bestAttackableDist = dist;
                            bestAttackable = tile;
                        }
                    }
                    if (dist < bestUnpaintedDist) {
                        bestUnpaintedDist = dist;
                        bestUnpainted = tile;
                    }
                }
            }
        }

        if (bestAttackable != null && rc.isActionReady()) {
            boolean isSecondary = getIsSecondary(paintingRuinLoc, bestAttackable, paintingRuinType);
            rc.attack(bestAttackable, isSecondary);
            paintedSomething = true;
            turnsWithoutAttack = 0;
        }

        if (!paintedSomething && rc.isActionReady() && bestUnpainted == null) {
            turnsWithoutAttack++;
        } else if (!paintedSomething && rc.isActionReady() && bestAttackable == null && bestUnpainted != null) {
            turnsWithoutAttack++;
        } else if (paintedSomething) {
            turnsWithoutAttack = 0;
        }

        if (tilesNeedingPaint <= 10 && tilesNeedingPaint > 0) {
            int estimatedIncome = 2 + Shared.knownMoneyTowers.size() * 2;
            int projectedChips = rc.getChips() + estimatedIncome * tilesNeedingPaint;
            if (projectedChips < Shared.TOWER_CHIP_COST) {
                Shared.isSaving = true;
                Shared.savingForRuinLoc = paintingRuinLoc;
            }
        }

        rc.setIndicatorString("Painting " + paintingRuinLoc + " (" + tilesNeedingPaint + " left, " + rc.getChips() + " chips)");

        if (rc.isMovementReady()) {
            if (bestUnpainted != null) {
                MapLocation myLoc = rc.getLocation();
                Direction toward = myLoc.directionTo(bestUnpainted);
                MapLocation afterMove = myLoc.add(toward);
                if (afterMove.distanceSquaredTo(paintingRuinLoc) <= 18) {
                    if (rc.canMove(toward)) rc.move(toward);
                    else if (rc.canMove(toward.rotateLeft())) rc.move(toward.rotateLeft());
                    else if (rc.canMove(toward.rotateRight())) rc.move(toward.rotateRight());
                } else {
                    Direction toRuin = myLoc.directionTo(paintingRuinLoc);
                    if (rc.canMove(toRuin)) rc.move(toRuin);
                    else if (rc.canMove(toRuin.rotateLeft())) rc.move(toRuin.rotateLeft());
                    else if (rc.canMove(toRuin.rotateRight())) rc.move(toRuin.rotateRight());
                }
            } else if (distToRuin > 2) {
                Direction toward = rc.getLocation().directionTo(paintingRuinLoc);
                if (rc.canMove(toward)) rc.move(toward);
                else if (rc.canMove(toward.rotateLeft())) rc.move(toward.rotateLeft());
                else if (rc.canMove(toward.rotateRight())) rc.move(toward.rotateRight());
            }
        }

        if (rc.canCompleteTowerPattern(paintingRuinType, paintingRuinLoc)) {
            rc.completeTowerPattern(paintingRuinType, paintingRuinLoc);
            state = RobotState.EXPLORING;
            clearPaintingState();
            return;
        }

        if (turnsWithoutAttack > 30 && tilesNeedingPaint > 4) {
            state = RobotState.EXPLORING;
            clearPaintingState();
        } else if (turnsWithoutAttack > 50) {
            state = RobotState.EXPLORING;
            clearPaintingState();
        }
    }

    // ========== TOWER TYPE SELECTION ==========

    public static UnitType detectExistingPatternType(RobotController rc, MapLocation ruinLoc) throws GameActionException {
        if (rc.getNumberTowers() < 3) {
            return getNewTowerType(rc);
        }

        int paintMatchCount = 0;
        int moneyMatchCount = 0;
        int totalCheckable = 0;

        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                MapLocation tile = ruinLoc.translate(dx, dy);
                if (tile.equals(ruinLoc)) continue;
                if (!rc.canSenseLocation(tile)) continue;
                MapInfo info = rc.senseMapInfo(tile);
                if (info.isWall() || info.hasRuin()) continue;
                PaintType current = info.getPaint();
                if (!current.isAlly()) continue;

                totalCheckable++;
                boolean isPaintSec = getIsSecondary(ruinLoc, tile, UnitType.LEVEL_ONE_PAINT_TOWER);
                boolean isMoneySec = getIsSecondary(ruinLoc, tile, UnitType.LEVEL_ONE_MONEY_TOWER);
                if (current.isSecondary() == isPaintSec) paintMatchCount++;
                if (current.isSecondary() == isMoneySec) moneyMatchCount++;
            }
        }

        if (totalCheckable >= 3 && paintMatchCount != moneyMatchCount) {
            return paintMatchCount > moneyMatchCount ? UnitType.LEVEL_ONE_PAINT_TOWER : UnitType.LEVEL_ONE_MONEY_TOWER;
        }

        return getNewTowerType(rc);
    }

    public static UnitType getNewTowerType(RobotController rc) {
        int numTowers = rc.getNumberTowers();
        if (mapScale < 1.33) {
            if (numTowers < 3) return UnitType.LEVEL_ONE_MONEY_TOWER;
            return numTowers % 3 == 0 ? UnitType.LEVEL_ONE_PAINT_TOWER : UnitType.LEVEL_ONE_MONEY_TOWER;
        } else if (mapScale < 1.67) {
            if (numTowers < 4) return UnitType.LEVEL_ONE_MONEY_TOWER;
            return numTowers % 3 == 0 ? UnitType.LEVEL_ONE_PAINT_TOWER : UnitType.LEVEL_ONE_MONEY_TOWER;
        } else {
            if (numTowers < 5) return UnitType.LEVEL_ONE_MONEY_TOWER;
            return numTowers % 3 == 0 ? UnitType.LEVEL_ONE_PAINT_TOWER : UnitType.LEVEL_ONE_MONEY_TOWER;
        }
    }

    // ========== SRP UTILITIES ==========

    static MapLocation alignSRPCenter(int x, int y) {
        return new MapLocation(((x + 2) / 4) * 4 + 2, ((y + 2) / 4) * 4 + 2);
    }

    static boolean paintSRPTiles(RobotController rc, MapLocation center) throws GameActionException {
        if (!rc.isActionReady()) return false;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                MapLocation tile = center.translate(dx, dy);
                if (!rc.canSenseLocation(tile)) continue;
                MapInfo info = rc.senseMapInfo(tile);
                if (info.isWall() || info.hasRuin()) continue;
                boolean wantSecondary = SRP_PATTERN[dx + 2][dy + 2] == 2;
                PaintType want = wantSecondary ? PaintType.ALLY_SECONDARY : PaintType.ALLY_PRIMARY;
                PaintType current = info.getPaint();
                if (current != want && !current.isEnemy() && rc.canAttack(tile)) {
                    rc.attack(tile, wantSecondary);
                    return true;
                }
            }
        }
        return false;
    }

    static void tryCompletePatterns(RobotController rc) throws GameActionException {
        MapLocation me = rc.getLocation();
        for (int dx = -2; dx <= 2; dx += 4) {
            for (int dy = -2; dy <= 2; dy += 4) {
                MapLocation c = alignSRPCenter(me.x + dx, me.y + dy);
                if (me.distanceSquaredTo(c) <= 2 && rc.canCompleteResourcePattern(c)) {
                    rc.completeResourcePattern(c);
                }
            }
        }
    }
}
