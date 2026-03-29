package main_bot;

import static main_bot.Shared.LATEGAME_OFFENSIVE_TURNS;
import static main_bot.Shared.MOPPER_LOW_PAINT_THRESHOLD;
import static main_bot.Shared.bug0;
import static main_bot.Shared.bug2;
import static main_bot.Shared.directions;
import static main_bot.Shared.guessEnemyLocation;
import static main_bot.Shared.isWithinPattern;
import static main_bot.Shared.knownEnemyTowers;
import static main_bot.Shared.knownTowers;
import static main_bot.Shared.preRetreatState;
import static main_bot.Shared.reportEnemyTowers;
import static main_bot.Shared.rng;
import static main_bot.Shared.runRetreat;
import static main_bot.Shared.state;
import static main_bot.Shared.targetEnemyRuin;
import static main_bot.Shared.updateFriendlyTowers;
import static main_bot.Shared.updateSymmetryGuess;

import battlecode.common.Direction;
import battlecode.common.GameActionException;
import battlecode.common.MapInfo;
import battlecode.common.MapLocation;
import battlecode.common.PaintType;
import battlecode.common.RobotController;
import battlecode.common.RobotInfo;
import battlecode.common.UnitType;
import main_bot.Shared.RobotState;

/**
 * Mopper logic: attack-first lifesteal, paint transfer healer, help build at ruins.
 */
public class MopperPlayer {

    static MapLocation mopperHelpRuin = null;
    static MapLocation pushTarget = null;
    static int pushTargetAge = 0;

    public static void runMopper(RobotController rc) throws GameActionException {
        RobotInfo[] enemyRobots = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        boolean enemiesNearby = enemyRobots.length > 0;

        // Don't retreat if enemies are nearby — free attacks + lifesteal can sustain us
        if (state != RobotState.RETREAT && rc.getPaint() <= MOPPER_LOW_PAINT_THRESHOLD
            && !knownTowers.isEmpty() && !enemiesNearby) {
            preRetreatState = state;
            state = RobotState.RETREAT;
        }

        if (state == RobotState.RETREAT) {
            // Break out of retreat if enemies appear — stay and fight on frontline
            if (enemiesNearby && rc.getPaint() > 5) {
                state = preRetreatState != null ? preRetreatState : RobotState.ATTACKING;
                preRetreatState = null;
            } else {
                runRetreat(rc);
                return;
            }
        }

        updateFriendlyTowers(rc);
        updateSymmetryGuess(rc);
        reportEnemyTowers(rc);

        int round = rc.getRoundNum();

        if (state == RobotState.STARTING) {
            int roll = rc.getID() % 10;
            if (roll < 7) state = RobotState.ATTACKING;
            else state = RobotState.HELPING_BUILD;
        }

        // Late game: everyone attacks
        if (round >= LATEGAME_OFFENSIVE_TURNS && state != RobotState.RETREAT) {
            state = RobotState.ATTACKING;
            mopperHelpRuin = null;
        }

        RobotInfo[] allyRobots = rc.senseNearbyRobots(-1, rc.getTeam());
        MapLocation nearestEnemy = null;
        int nearestEnemyDist = Integer.MAX_VALUE;
        for (RobotInfo enemy : enemyRobots) {
            int dist = rc.getLocation().distanceSquaredTo(enemy.getLocation());
            if (dist < nearestEnemyDist) {
                nearestEnemyDist = dist;
                nearestEnemy = enemy.getLocation();
            }
        }

        // === PRIORITY 1: Combat — lifesteal from enemies (free action, always do first) ===
        if (nearestEnemy != null) {
            mopperCombat(rc, enemyRobots, nearestEnemy);
        }

        // === PRIORITY 2: Mop enemy paint nearby ===
        if (rc.isActionReady()) {
            mopBestEnemyPaint(rc);
        }

        // === PRIORITY 3: Transfer paint to low-paint allies (healer) ===
        if (rc.isActionReady()) {
            transferPaintToAlly(rc, allyRobots);
        }

        // === Auto-detect nearby ruin needing mop — ANY mopper helps instantly ===
        MapLocation ruinNeedingHelp = findRuinNeedingMop(rc);
        if (ruinNeedingHelp != null) {
            mopperHelpRuin = ruinNeedingHelp;
            state = RobotState.HELPING_BUILD;
        }

        // ==================== HELPING BUILD ====================
        if (state == RobotState.HELPING_BUILD) {
            if (mopperHelpRuin == null) {
                mopperHelpRuin = findRuinNeedingMop(rc);
            }

            if (mopperHelpRuin != null) {
                // Check if tower already built
                if (rc.canSenseLocation(mopperHelpRuin) && rc.senseRobotAtLocation(mopperHelpRuin) != null) {
                    mopperHelpRuin = null;
                    state = RobotState.ATTACKING;
                } else {
                    int distToRuin = rc.getLocation().distanceSquaredTo(mopperHelpRuin);
                    if (distToRuin > 8) {
                        bug2(rc, mopperHelpRuin);
                    } else {
                        if (rc.isActionReady()) {
                            mopPatternArea(rc, mopperHelpRuin);
                        }
                        // Circle around the ruin to mop all sides
                        Direction toRuin = rc.getLocation().directionTo(mopperHelpRuin);
                        Direction tangent = toRuin.rotateRight().rotateRight();
                        if (rc.canMove(tangent)) rc.move(tangent);
                        else if (rc.canMove(toRuin)) rc.move(toRuin);
                    }
                    rc.setIndicatorString("MOPPER helping build at " + mopperHelpRuin);
                }
            } else {
                // No ruin needs help — go attack
                state = RobotState.ATTACKING;
            }

        // ==================== ATTACKING ====================
        } else if (state == RobotState.ATTACKING) {

            // Pick attack target
            if (targetEnemyRuin == null) {
                // Visible enemy tower
                MapLocation[] ruinInfos = rc.senseNearbyRuins(-1);
                for (MapLocation ruin : ruinInfos) {
                    if (rc.canSenseRobotAtLocation(ruin)) {
                        RobotInfo r = rc.senseRobotAtLocation(ruin);
                        if (r != null && r.getTeam() != rc.getTeam()) {
                            targetEnemyRuin = ruin;
                            break;
                        }
                    }
                }
                // Known enemy towers
                if (targetEnemyRuin == null && !knownEnemyTowers.isEmpty()) {
                    MapLocation closest = null;
                    int closestDist = Integer.MAX_VALUE;
                    for (MapLocation et : knownEnemyTowers) {
                        int d = rc.getLocation().distanceSquaredTo(et);
                        if (d < closestDist) { closestDist = d; closest = et; }
                    }
                    targetEnemyRuin = closest;
                }
                // Guess enemy positions if no known structures
                if (targetEnemyRuin == null) {
                    pushTargetAge++;
                    if (pushTarget != null && rc.getLocation().distanceSquaredTo(pushTarget) <= 8) {
                        pushTarget = null;
                    }
                    if (pushTarget == null || pushTargetAge > 30) {
                        MapLocation enemySideRef = !knownTowers.isEmpty()
                            ? knownTowers.get(rc.getID() % knownTowers.size())
                            : getMopperCornerRef(rc);
                        MapLocation guessed = guessEnemyLocation(rc, enemySideRef);
                        Direction out = rc.getLocation().directionTo(guessed);
                        pushTarget = out != Direction.CENTER ? main_bot.Shared.extendToEdge(rc, guessed, out) : guessed;
                        pushTargetAge = 0;
                    }
                }
            }

            // Clear destroyed target
            if (targetEnemyRuin != null && rc.canSenseLocation(targetEnemyRuin)) {
                RobotInfo atTarget = rc.senseRobotAtLocation(targetEnemyRuin);
                if (atTarget == null || atTarget.getTeam() == rc.getTeam()) {
                    targetEnemyRuin = null;
                }
            }

            // Movement: chase enemies, push toward target, seek enemy paint
            if (nearestEnemy != null && nearestEnemyDist <= 8 && rc.isMovementReady()) {
                Direction toward = rc.getLocation().directionTo(nearestEnemy);
                if (rc.canMove(toward)) rc.move(toward);
                else if (rc.canMove(toward.rotateLeft())) rc.move(toward.rotateLeft());
                else if (rc.canMove(toward.rotateRight())) rc.move(toward.rotateRight());
            } else if (rc.isMovementReady()) {
                // Score movement: bias toward enemy paint and target.
                // Use a known tower as the enemy-side reference (not current pos) to avoid
                // mirroring center → center when the map is rotational and we're near center.
                MapLocation enemySideRef = !knownTowers.isEmpty()
                    ? knownTowers.get(rc.getID() % knownTowers.size())
                    : getMopperCornerRef(rc);
                MapLocation enemySide = guessEnemyLocation(rc, enemySideRef);

                // Anti-clumping: Calculate centroid of nearby allied Moppers
                int nearbyMoppers = 0;
                int mx = 0, my = 0;
                for (RobotInfo ally : allyRobots) {
                    if (ally.getType() == UnitType.MOPPER) {
                        nearbyMoppers++;
                        mx += ally.getLocation().x;
                        my += ally.getLocation().y;
                    }
                }
                MapLocation mopperCentroid = null;
                if (nearbyMoppers > 0) {
                    mopperCentroid = new MapLocation(mx / nearbyMoppers, my / nearbyMoppers);
                }

                MapLocation actualTarget = (targetEnemyRuin != null) ? targetEnemyRuin : pushTarget;
                if (actualTarget == null) actualTarget = enemySide; // fallback
                
                if (main_bot.Shared.isTracing) {
                    bug2(rc, actualTarget);
                } else {
                    Direction bestDir = null;
                    int bestScore = Integer.MIN_VALUE;

                    for (Direction d : directions) {
                        if (!rc.canMove(d)) continue;
                        MapLocation newLoc = rc.getLocation().add(d);
                        int score = 0;
                        MapInfo[] nearby = rc.senseNearbyMapInfos(newLoc, 5);
                        for (MapInfo info : nearby) {
                            PaintType p = info.getPaint();
                            if (p == PaintType.ENEMY_PRIMARY || p == PaintType.ENEMY_SECONDARY) score += 4;
                            else if (p == PaintType.EMPTY) score += 1;
                        }
                        // Strong push toward attack target
                        if (newLoc.distanceSquaredTo(actualTarget) < rc.getLocation().distanceSquaredTo(actualTarget)) {
                            score += 50;
                        }
                        // Anti-clumping applied
                        if (mopperCentroid != null) {
                            if (newLoc.distanceSquaredTo(mopperCentroid) < rc.getLocation().distanceSquaredTo(mopperCentroid)) {
                                score -= 4; // Penalty for moving closer to other moppers
                            }
                        }

                        if (score > bestScore) { bestScore = score; bestDir = d; }
                    }
                    
                    if (bestDir != null) {
                        MapLocation next = rc.getLocation().add(bestDir);
                        if (next.distanceSquaredTo(actualTarget) >= rc.getLocation().distanceSquaredTo(actualTarget) 
                            && !rc.canMove(rc.getLocation().directionTo(actualTarget))) {
                            // Can't move closer using greedy, and straight path is blocked -> tracing mode!
                            bug2(rc, actualTarget);
                        } else {
                            rc.move(bestDir);
                        }
                    } else {
                        bug2(rc, actualTarget);
                    }
                }
            }

            // Swing at enemies after moving
            if (rc.isActionReady() && nearestEnemy != null) {
                Direction swingDir = rc.getLocation().directionTo(nearestEnemy);
                if (rc.canMopSwing(swingDir)) {
                    rc.mopSwing(swingDir);
                }
            }

            // Mop enemy paint after moving
            if (rc.isActionReady()) {
                mopBestEnemyPaint(rc);
            }

            // Transfer paint after all attacks
            if (rc.isActionReady()) {
                transferPaintToAlly(rc, allyRobots);
            }

            String targetStr = (targetEnemyRuin != null) ? targetEnemyRuin.toString() : "roaming";
            rc.setIndicatorString("MOPPER atk → " + targetStr + " (paint:" + rc.getPaint() + ")");
        }
    }

    // ========== PAINT TRANSFER (HEALER) ==========

    /**
     * Transfer paint to a nearby low-paint ally. Prioritizes allies with the
     * lowest paint ratio. Only gives if we have enough to spare (>40%).
     */
    static void transferPaintToAlly(RobotController rc, RobotInfo[] allies) throws GameActionException {
        if (!rc.isActionReady()) return;
        int myPaint = rc.getPaint();
        int myMax = rc.getType().paintCapacity;
        // Only give paint if we have more than 40%
        if (myPaint < myMax * 0.4) return;

        RobotInfo bestTarget = null;
        double lowestRatio = 0.5; // only help allies below 50% paint

        for (RobotInfo ally : allies) {
            if (ally.getType().isTowerType()) continue;
            if (ally.getType() == UnitType.MOPPER) continue; // moppers get their own paint via lifesteal
            if (rc.getLocation().distanceSquaredTo(ally.getLocation()) > 2) continue;
            double ratio = (double) ally.getPaintAmount() / ally.getType().paintCapacity;
            if (ratio < lowestRatio) {
                lowestRatio = ratio;
                bestTarget = ally;
            }
        }

        if (bestTarget != null) {
            int give = Math.min(myPaint / 3,
                bestTarget.getType().paintCapacity - bestTarget.getPaintAmount());
            if (give > 0 && rc.canTransferPaint(bestTarget.getLocation(), give)) {
                rc.transferPaint(bestTarget.getLocation(), give);
            }
        }
    }

    // ========== MOPPER COMBAT ==========

    static void mopBestEnemyPaint(RobotController rc) throws GameActionException {
        MapInfo[] nearby = rc.senseNearbyMapInfos(rc.getType().actionRadiusSquared);
        for (MapInfo tile : nearby) {
            PaintType p = tile.getPaint();
            if ((p == PaintType.ENEMY_PRIMARY || p == PaintType.ENEMY_SECONDARY) && rc.canAttack(tile.getMapLocation())) {
                rc.attack(tile.getMapLocation());
                return;
            }
        }
    }

    static void mopperCombat(RobotController rc, RobotInfo[] enemies, MapLocation nearestEnemy) throws GameActionException {
        if (!rc.isActionReady()) return;

        // Try mop swing first if it hits 2+ enemies
        Direction bestSwing = null;
        int bestSwingHits = 0;
        for (Direction cardinal : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            if (!rc.canMopSwing(cardinal)) continue;
            int hits = countEnemiesInSwing(rc, cardinal, enemies);
            if (hits > bestSwingHits) {
                bestSwingHits = hits;
                bestSwing = cardinal;
            }
        }

        if (bestSwingHits >= 2 && bestSwing != null) {
            rc.mopSwing(bestSwing);
            return;
        }

        // Single target attack — prioritize highest paint enemies (more to steal)
        RobotInfo bestTarget = null;
        int bestTargetPaint = -1;
        for (RobotInfo enemy : enemies) {
            if (enemy.getType().isTowerType()) continue;
            if (rc.canAttack(enemy.getLocation())) {
                if (enemy.getPaintAmount() > bestTargetPaint) {
                    bestTargetPaint = enemy.getPaintAmount();
                    bestTarget = enemy;
                }
            }
        }
        if (bestTarget != null) {
            rc.attack(bestTarget.getLocation());
            return;
        }

        // Even 1-hit swing is free value
        if (bestSwingHits >= 1 && bestSwing != null) {
            rc.mopSwing(bestSwing);
        }
    }

    static int countEnemiesInSwing(RobotController rc, Direction swingDir, RobotInfo[] enemies) {
        MapLocation loc = rc.getLocation();
        MapLocation step1 = loc.add(swingDir);
        MapLocation step2 = step1.add(swingDir);
        Direction perp = swingDir.rotateRight().rotateRight();
        MapLocation[] swingTiles = {
            step1, step1.add(perp), step1.add(perp.opposite()),
            step2, step2.add(perp), step2.add(perp.opposite())
        };
        int count = 0;
        for (RobotInfo enemy : enemies) {
            MapLocation eLoc = enemy.getLocation();
            for (MapLocation t : swingTiles) {
                if (eLoc.equals(t)) { count++; break; }
            }
        }
        return count;
    }

    // ========== BUILD HELPERS ==========

    static MapLocation findRuinNeedingMop(RobotController rc) throws GameActionException {
        MapLocation[] ruins = rc.senseNearbyRuins(-1);
        MapLocation bestRuin = null;
        int bestDist = Integer.MAX_VALUE;
        for (MapLocation ruin : ruins) {
            if (rc.senseRobotAtLocation(ruin) != null) continue;
            MapInfo[] patternTiles = rc.senseNearbyMapInfos(ruin, 8);
            boolean hasEnemyPaint = false;
            for (MapInfo tile : patternTiles) {
                PaintType p = tile.getPaint();
                if (p == PaintType.ENEMY_PRIMARY || p == PaintType.ENEMY_SECONDARY) {
                    hasEnemyPaint = true;
                    break;
                }
            }
            if (hasEnemyPaint) {
                int dist = rc.getLocation().distanceSquaredTo(ruin);
                if (dist < bestDist) {
                    bestDist = dist;
                    bestRuin = ruin;
                }
            }
        }
        return bestRuin;
    }

    static void mopPatternArea(RobotController rc, MapLocation ruinLoc) throws GameActionException {
        MapInfo[] tiles = rc.senseNearbyMapInfos(ruinLoc, 8);
        for (MapInfo tile : tiles) {
            PaintType p = tile.getPaint();
            if ((p == PaintType.ENEMY_PRIMARY || p == PaintType.ENEMY_SECONDARY)
                && isWithinPattern(ruinLoc, tile.getMapLocation())
                && rc.canAttack(tile.getMapLocation())) {
                rc.attack(tile.getMapLocation());
                return;
            }
        }
    }

    /** Corner reference point in our own quadrant; mirroring gives actual enemy territory. */
    static MapLocation getMopperCornerRef(RobotController rc) {
        int w = rc.getMapWidth();
        int h = rc.getMapHeight();
        int sector = rc.getID() % 4;
        int qx = (sector % 2 == 0) ? w / 4 : 3 * w / 4;
        int qy = (sector < 2)      ? h / 4 : 3 * h / 4;
        return new MapLocation(qx, qy);
    }
}
