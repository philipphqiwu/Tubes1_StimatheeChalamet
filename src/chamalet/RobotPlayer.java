package chamalet;

import battlecode.common.*;
import java.util.HashSet;
import java.util.Random;

public class RobotPlayer {

    static int turnCount = 0;
    static final Random rng = new Random(6147);

    static final Direction[] directions = {
        Direction.NORTH, Direction.NORTHEAST, Direction.EAST, Direction.SOUTHEAST,
        Direction.SOUTH, Direction.SOUTHWEST, Direction.WEST, Direction.NORTHWEST,
    };

    static final Direction[] cardinals = {
        Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST,
    };

    static final int EARLY_END = 0;

    static Direction exploreDir = null;
    static MapLocation nearestTower = null;
    static MapLocation targetRuin = null;
    static int refuelThreshold = 60;
    static MapLocation symmetryTarget = null;

    static int spawnCount = 0;

    static final int SOLDIER_PAINT_THRESHOLD = 100;
    static MapLocation blockedRuin = null;
    static int blockedRuinRound = 0;
    static final int RUIN_BLOCK_COOLDOWN = 50;

    static final int MSG_SAVE_CHIPS = 1;
    static int saveChipsUntil = -1;

    static boolean b2Tracing = false;
    static int b2StartDist = 0;
    static Direction b2Dir = null;
    static MapLocation b2Dest = null;
    static HashSet<MapLocation> b2Line = null;

    @SuppressWarnings("unused")
    public static void run(RobotController rc) throws GameActionException {
        refuelThreshold = Math.max(rc.getMapWidth(), rc.getMapHeight());
        if (symmetryTarget == null && rc.getType().isTowerType()) {
            symmetryTarget = new MapLocation(
                rc.getMapWidth()  - 1 - rc.getLocation().x,
                rc.getMapHeight() - 1 - rc.getLocation().y);
        }

        while (true) {
            turnCount += 1;
            try {
                switch (rc.getType()) {
                    case SOLDIER:  runSoldier(rc);  break;
                    case SPLASHER: runSplasher(rc); break;
                    case MOPPER:   runMopper(rc);   break;
                    default:       runTower(rc);    break;
                }
            } catch (GameActionException e) {
                System.out.println("GameActionException");
                e.printStackTrace();
            } catch (Exception e) {
                System.out.println("Exception");
                e.printStackTrace();
            } finally {
                Clock.yield();
            }
        }
    }

    /** Read chip-save requests, attack, spawn. */
    public static void runTower(RobotController rc) throws GameActionException {
        for (Message msg : rc.readMessages(-1)) {
            if (msg.getBytes() == MSG_SAVE_CHIPS) {
                saveChipsUntil = rc.getRoundNum() + 80;
            }
        }
        towerAttack(rc);
        towerSpawn(rc);
    }

    /** Attack lowest-HP enemy in range; tie-break by distance. */
    static void towerAttack(RobotController rc) throws GameActionException {
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        if (enemies.length == 0 || !rc.isActionReady()) return;

        RobotInfo target = null;
        int minHp = Integer.MAX_VALUE;
        int minDist = Integer.MAX_VALUE;
        for (RobotInfo enemy : enemies) {
            if (!rc.canAttack(enemy.location)) continue;
            if (enemy.health < minHp
                    || (enemy.health == minHp
                        && rc.getLocation().distanceSquaredTo(enemy.location) < minDist)) {
                minHp   = enemy.health;
                minDist = rc.getLocation().distanceSquaredTo(enemy.location);
                target  = enemy;
            }
        }
        if (target != null) rc.attack(target.location);
    }

    /**
     * Early (<150): S S M. Mid/late: S S Sp Sp M M M.
     * Holds chip buffer; bigger buffer when saving for tower build.
     */
    static void towerSpawn(RobotController rc) throws GameActionException {
        if (!rc.isActionReady()) return;

        int round = rc.getRoundNum();

        int chipFloor = (round < 150) ? 200 : 400;
        if (round < saveChipsUntil) chipFloor = Math.max(chipFloor, 1100);
        if (rc.getChips() < chipFloor) return;

        UnitType toBuild;
        if (round < 150) {
            int mod = spawnCount % 3;
            if (mod < 2) toBuild = UnitType.SOLDIER;
            else         toBuild = UnitType.MOPPER;
        } else {
            int mod = spawnCount % 7;
            if (mod < 2)      toBuild = UnitType.SOLDIER;
            else if (mod < 4) toBuild = UnitType.SPLASHER;
            else              toBuild = UnitType.MOPPER;
        }

        MapLocation bestSpawn = null;
        int bestScore = Integer.MAX_VALUE;
        for (Direction dir : directions) {
            MapLocation loc = rc.getLocation().add(dir);
            if (!rc.canBuildRobot(toBuild, loc)) continue;
            int score = rc.senseNearbyRobots(loc, 2, rc.getTeam()).length * 10;
            if (!rc.senseMapInfo(loc).getPaint().isAlly()) score += 5;
            if (score < bestScore) { bestScore = score; bestSpawn = loc; }
        }

        if (bestSpawn != null) {
            rc.buildRobot(toBuild, bestSpawn);
            spawnCount++;
        }
    }

    // ================================================================
    //  SOLDIER
    // ================================================================

    /** Refuel → claim ruin → micro-attack enemy tower → explore + paint. */
    public static void runSoldier(RobotController rc) throws GameActionException {
        rememberNearestPaintTower(rc);

        if (rc.getPaint() < SOLDIER_PAINT_THRESHOLD) {
            if (!tryWithdrawPaint(rc)) {
                seekRefuelTower(rc);
            }
            return;
        }

        if (targetRuin != null && rc.canSenseLocation(targetRuin)) {
            if (rc.senseRobotAtLocation(targetRuin) != null) {
                targetRuin = null;
            }
        }
        if (targetRuin == null) {
            targetRuin = findNearestUnbuiltRuin(rc);
        }
        if (targetRuin != null) {
            tryBuildTower(rc, targetRuin);
            return;
        }

        if (tryMicroAttackEnemyTower(rc)) {
            return;
        }

        exploreMove(rc);
        paintCurrentTile(rc);
    }

    /** Signal chip-save, navigate to ruin, mark pattern, paint tiles, complete tower. */
    static boolean tryBuildTower(RobotController rc, MapLocation ruinLoc) throws GameActionException {
        if (ruinLoc == null) return false;

        int distToRuin = rc.getLocation().distanceSquaredTo(ruinLoc);

        if (nearestTower != null && rc.canSendMessage(nearestTower)) {
            rc.sendMessage(nearestTower, MSG_SAVE_CHIPS);
        }

        if (distToRuin > 8) {
            targetRuin = ruinLoc;
            bug2(rc, ruinLoc);
            return false;
        }

        UnitType towerType;
        int allyMoney = 0, allyPaint = 0;
        for (RobotInfo ally : rc.senseNearbyRobots(-1, rc.getTeam())) {
            if (ally.type == UnitType.LEVEL_ONE_MONEY_TOWER
                    || ally.type == UnitType.LEVEL_TWO_MONEY_TOWER
                    || ally.type == UnitType.LEVEL_THREE_MONEY_TOWER) allyMoney++;
            else if (isPaintTower(ally.type)) allyPaint++;
        }
        int totalTowers = allyMoney + allyPaint + 1;
        if (allyPaint * 3 < totalTowers) {
            towerType = UnitType.LEVEL_ONE_PAINT_TOWER;
        } else {
            towerType = UnitType.LEVEL_ONE_MONEY_TOWER;
        }

        if (rc.canMarkTowerPattern(towerType, ruinLoc)) {
            rc.markTowerPattern(towerType, ruinLoc);
        }

        MapLocation tileToPaint = null;
        int bestDist = Integer.MAX_VALUE;
        boolean useSecondary = false;
        boolean hasEnemyPaint = false;

        for (MapInfo patternTile : rc.senseNearbyMapInfos(ruinLoc, 8)) {
            if (patternTile.getMark() == PaintType.EMPTY) continue;
            if (patternTile.getPaint().isEnemy()) { hasEnemyPaint = true; continue; }
            if (patternTile.getMark() == patternTile.getPaint()) continue;
            MapLocation loc = patternTile.getMapLocation();
            int dist = rc.getLocation().distanceSquaredTo(loc);
            if (dist < bestDist) {
                bestDist = dist;
                tileToPaint = loc;
                useSecondary = patternTile.getMark() == PaintType.ALLY_SECONDARY;
            }
        }

        if (hasEnemyPaint && tileToPaint == null) {
            blockedRuin = ruinLoc;
            blockedRuinRound = rc.getRoundNum();
            targetRuin = null;
            return false;
        }

        if (tileToPaint != null) {
            if (!rc.canAttack(tileToPaint)) {
                bug2(rc, tileToPaint);
            }
            if (rc.canAttack(tileToPaint)) {
                rc.attack(tileToPaint, useSecondary);
            }
        } else {
            if (distToRuin > 2) {
                bug2(rc, ruinLoc);
            }
        }

        if (rc.canCompleteTowerPattern(towerType, ruinLoc)) {
            rc.completeTowerPattern(towerType, ruinLoc);
            targetRuin = null;
        }

        return true;
    }

    /** Fire + kite enemy tower. Advance if out of range; retreat on cooldown. */
    static boolean tryMicroAttackEnemyTower(RobotController rc) throws GameActionException {
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());

        RobotInfo nearestTower = null;
        int nearestDist = Integer.MAX_VALUE;
        for (RobotInfo enemy : enemies) {
            if (!enemy.type.isTowerType()) continue;
            int dist = rc.getLocation().distanceSquaredTo(enemy.location);
            if (dist < nearestDist) {
                nearestDist = dist;
                nearestTower = enemy;
            }
        }
        if (nearestTower == null) return false;

        MapLocation towerLoc = nearestTower.location;

        if (rc.isActionReady()) {
            if (rc.canAttack(towerLoc)) {
                rc.attack(towerLoc);
                if (rc.isMovementReady()) {
                    Direction away = towerLoc.directionTo(rc.getLocation());
                    if (rc.canMove(away)) rc.move(away);
                    else if (rc.canMove(away.rotateLeft())) rc.move(away.rotateLeft());
                    else if (rc.canMove(away.rotateRight())) rc.move(away.rotateRight());
                }
            } else {
                bug2(rc, towerLoc);
                if (rc.isActionReady() && rc.canAttack(towerLoc)) {
                    rc.attack(towerLoc);
                }
            }
        } else {
            if (rc.isMovementReady()) {
                Direction away = towerLoc.directionTo(rc.getLocation());
                if (rc.canMove(away)) rc.move(away);
                else if (rc.canMove(away.rotateLeft())) rc.move(away.rotateLeft());
                else if (rc.canMove(away.rotateRight())) rc.move(away.rotateRight());
            }
        }
        return true;
    }

    /** score = distSq + RUIN_PAINT_PENALTY (enemy paint present) − RUIN_ALLY_BONUS (ally tower nearby). */
    static final int RUIN_PAINT_PENALTY = 1000;
    static final int RUIN_ALLY_BONUS    =  200;

    static MapLocation findNearestUnbuiltRuin(RobotController rc) throws GameActionException {
        MapInfo[] nearby = rc.senseNearbyMapInfos();
        MapLocation bestRuin = null;
        int bestScore = Integer.MAX_VALUE;

        for (MapInfo tile : nearby) {
            if (!tile.hasRuin()) continue;
            MapLocation ruinLoc = tile.getMapLocation();
            if (blockedRuin != null
                    && ruinLoc.equals(blockedRuin)
                    && rc.getRoundNum() - blockedRuinRound < RUIN_BLOCK_COOLDOWN) continue;
            if (rc.senseRobotAtLocation(ruinLoc) != null) continue;

            int score = rc.getLocation().distanceSquaredTo(ruinLoc);
            for (MapInfo pt : rc.senseNearbyMapInfos(ruinLoc, 8)) {
                if (pt.getPaint().isEnemy()) { score += RUIN_PAINT_PENALTY; break; }
            }
            for (RobotInfo ally : rc.senseNearbyRobots(ruinLoc, 20, rc.getTeam())) {
                if (ally.type.isTowerType()) { score -= RUIN_ALLY_BONUS; break; }
            }

            if (score < bestScore) { bestScore = score; bestRuin = ruinLoc; }
        }
        return bestRuin;
    }

    // ================================================================
    //  SPLASHER
    // ================================================================

    /** Refuel → splash → (kite tower / greedy move) → splash again. */
    public static void runSplasher(RobotController rc) throws GameActionException {
        rememberNearestPaintTower(rc);

        if (rc.getPaint() < (int)(rc.getType().paintCapacity * 0.15)) {
            if (!tryWithdrawPaint(rc)) {
                seekRefuelTower(rc);
            }
            return;
        }

        boolean splashed = trySplashEnemyPaint(rc);

        RobotInfo nearestEnemyTower = null;
        int nearestTowerDist = Integer.MAX_VALUE;
        for (RobotInfo enemy : rc.senseNearbyRobots(-1, rc.getTeam().opponent())) {
            if (!enemy.type.isTowerType()) continue;
            int d = rc.getLocation().distanceSquaredTo(enemy.location);
            if (d < nearestTowerDist) { nearestTowerDist = d; nearestEnemyTower = enemy; }
        }

        if (rc.isMovementReady()) {
            if (nearestEnemyTower != null && nearestTowerDist <= 4) {
                Direction away = nearestEnemyTower.location.directionTo(rc.getLocation());
                if (rc.canMove(away)) rc.move(away);
                else if (rc.canMove(away.rotateLeft())) rc.move(away.rotateLeft());
                else if (rc.canMove(away.rotateRight())) rc.move(away.rotateRight());
            } else {
                Direction moveDir = greedySplasherDirection(rc);
                if (moveDir != null && rc.canMove(moveDir)) rc.move(moveDir);
            }
        }

        if (!splashed) trySplashEnemyPaint(rc);
    }

    /** Best move direction by enemy paint tiles in landing-spot radius; explore if none. */
    static Direction greedySplasherDirection(RobotController rc) throws GameActionException {
        Direction bestDir = null;
        int bestScore = 0;

        for (Direction dir : directions) {
            if (!rc.canMove(dir)) continue;
            MapLocation dest = rc.getLocation().add(dir);
            int score = 0;
            for (MapInfo tile : rc.senseNearbyMapInfos(dest, rc.getType().actionRadiusSquared)) {
                if (!tile.isPassable()) continue;
                if (tile.getPaint().isEnemy()) score += 3;
            }
            if (score > bestScore) { bestScore = score; bestDir = dir; }
        }

        if (bestDir == null) return getExploreDirection(rc);
        return bestDir;
    }

    /** Fire on best splash center (enemy +4, ally −2); skip ruin patterns, skip if score ≤ 0. */
    static boolean trySplashEnemyPaint(RobotController rc) throws GameActionException {
        if (!rc.isActionReady()) return false;

        MapInfo[] allVisible = rc.senseNearbyMapInfos();
        MapLocation[] protectedRuins = new MapLocation[allVisible.length];
        int protectedCount = 0;
        for (MapInfo t : allVisible) {
            if (t.hasRuin() && rc.senseRobotAtLocation(t.getMapLocation()) == null) {
                protectedRuins[protectedCount++] = t.getMapLocation();
            }
        }

        MapLocation bestTarget = null;
        int bestScore = 0;

        MapInfo[] candidates = rc.senseNearbyMapInfos(rc.getLocation(), rc.getType().actionRadiusSquared);
        for (MapInfo tile : candidates) {
            MapLocation loc = tile.getMapLocation();
            if (!rc.canAttack(loc)) continue;

            boolean hitsPattern = false;
            for (int i = 0; i < protectedCount; i++) {
                if (loc.isWithinDistanceSquared(protectedRuins[i], 8)) { hitsPattern = true; break; }
            }
            if (hitsPattern) continue;

            int score = 0;
            for (MapInfo s : rc.senseNearbyMapInfos(loc, 2)) {
                if (!s.isPassable()) continue;
                PaintType p = s.getPaint();
                if (p.isEnemy())     score += 4;
                else if (p.isAlly()) score -= 2;
            }
            if (score > bestScore) { bestScore = score; bestTarget = loc; }
        }

        if (bestTarget != null && rc.canAttack(bestTarget)) {
            rc.attack(bestTarget);
            return true;
        }
        return false;
    }

    // ================================================================
    //  MOPPER
    // ================================================================

    /** Refuel → mop ruin area → mop paint → combat → transfer → move → mop + combat again. */
    public static void runMopper(RobotController rc) throws GameActionException {
        rememberNearestTower(rc);

        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        RobotInfo[] allies  = rc.senseNearbyRobots(-1, rc.getTeam());

        if (rc.getPaint() < refuelThreshold && enemies.length == 0) {
            if (!tryWithdrawPaint(rc)) {
                seekRefuelTower(rc);
                return;
            }
        }

        if (rc.isActionReady()) mopRuinArea(rc);
        if (rc.isActionReady()) mopNearestEnemyPaint(rc);
        if (rc.isActionReady() && enemies.length > 0) mopperCombat(rc, enemies);
        if (rc.isActionReady()) mopperTransferPaint(rc, allies);

        if (rc.isMovementReady()) {
            Direction moveDir = greedyMopperDirection(rc);
            if (moveDir != null && rc.canMove(moveDir)) {
                rc.move(moveDir);
            } else {
                MapLocation nearestEnemy = null;
                int nearestDist = Integer.MAX_VALUE;
                for (RobotInfo e : enemies) {
                    int d = rc.getLocation().distanceSquaredTo(e.location);
                    if (d < nearestDist) { nearestDist = d; nearestEnemy = e.location; }
                }
                if (nearestEnemy != null) bug2(rc, nearestEnemy);
            }
        }

        if (rc.isActionReady()) mopNearestEnemyPaint(rc);
        if (rc.isActionReady()) {
            RobotInfo[] enemiesAfterMove = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
            if (enemiesAfterMove.length > 0) mopperCombat(rc, enemiesAfterMove);
        }
    }

    /** Swing ≥2 enemies; else single-attack highest-paint enemy; fallback 1-hit swing. */
    static void mopperCombat(RobotController rc, RobotInfo[] enemies) throws GameActionException {
        if (!rc.isActionReady()) return;

        Direction bestSwing = null;
        int bestSwingHits = 0;
        for (Direction dir : cardinals) {
            if (!rc.canMopSwing(dir)) continue;
            int hits = countEnemiesInSwingArea(rc, dir, enemies);
            if (hits > bestSwingHits) { bestSwingHits = hits; bestSwing = dir; }
        }

        if (bestSwingHits >= 2 && bestSwing != null) {
            rc.mopSwing(bestSwing);
            return;
        }

        RobotInfo bestTarget = null;
        int bestPaint = -1;
        for (RobotInfo enemy : enemies) {
            if (enemy.type.isTowerType()) continue;
            if (!rc.canAttack(enemy.location)) continue;
            if (enemy.paintAmount > bestPaint) { bestPaint = enemy.paintAmount; bestTarget = enemy; }
        }
        if (bestTarget != null) {
            rc.attack(bestTarget.location);
            return;
        }

        if (bestSwingHits >= 1 && bestSwing != null) {
            rc.mopSwing(bestSwing);
        }
    }

    static int countEnemiesInSwingArea(RobotController rc, Direction dir, RobotInfo[] enemies) {
        MapLocation loc = rc.getLocation();
        MapLocation step1 = loc.add(dir);
        MapLocation step2 = step1.add(dir);
        Direction perp = dir.rotateRight().rotateRight();
        MapLocation[] swingTiles = {
            step1, step1.add(perp), step1.add(perp.opposite()),
            step2, step2.add(perp), step2.add(perp.opposite())
        };
        int count = 0;
        for (RobotInfo enemy : enemies) {
            for (MapLocation t : swingTiles) {
                if (enemy.location.equals(t)) { count++; break; }
            }
        }
        return count;
    }

    /** Mop enemy paint inside the pattern area of the nearest unbuilt ruin. */
    static void mopRuinArea(RobotController rc) throws GameActionException {
        MapInfo[] allTiles = rc.senseNearbyMapInfos();
        for (MapInfo tile : allTiles) {
            if (!tile.hasRuin()) continue;
            MapLocation ruinLoc = tile.getMapLocation();
            if (rc.senseRobotAtLocation(ruinLoc) != null) continue;
            for (MapInfo pt : rc.senseNearbyMapInfos(ruinLoc, 8)) {
                if (pt.getPaint().isEnemy() && rc.canAttack(pt.getMapLocation())) {
                    rc.attack(pt.getMapLocation());
                    return;
                }
            }
        }
    }

    /** Mop nearest enemy-painted tile in action range. */
    static boolean mopNearestEnemyPaint(RobotController rc) throws GameActionException {
        if (!rc.isActionReady()) return false;
        MapInfo[] nearby = rc.senseNearbyMapInfos(rc.getType().actionRadiusSquared);
        for (MapInfo tile : nearby) {
            if (tile.getPaint().isEnemy() && rc.canAttack(tile.getMapLocation())) {
                rc.attack(tile.getMapLocation());
                return true;
            }
        }
        return false;
    }

    /** Give paint to the lowest-ratio adjacent ally (<50%) if we have >40% capacity. */
    static void mopperTransferPaint(RobotController rc, RobotInfo[] allies) throws GameActionException {
        int myPaint = rc.getPaint();
        int myMax   = rc.getType().paintCapacity;
        if (myPaint < myMax * 0.4) return;

        RobotInfo bestTarget = null;
        double lowestRatio = 0.5;
        for (RobotInfo ally : allies) {
            if (ally.type.isTowerType() || ally.type == UnitType.MOPPER) continue;
            if (rc.getLocation().distanceSquaredTo(ally.location) > 2) continue;
            double ratio = (double) ally.paintAmount / ally.type.paintCapacity;
            if (ratio < lowestRatio) { lowestRatio = ratio; bestTarget = ally; }
        }
        if (bestTarget != null) {
            int give = Math.min(myPaint / 3, bestTarget.type.paintCapacity - bestTarget.paintAmount);
            if (give > 0 && rc.canTransferPaint(bestTarget.location, give)) {
                rc.transferPaint(bestTarget.location, give);
            }
        }
    }

    /** Best move direction by enemy paint (+2) and enemy bots (+3) in radius 8; explore if none. */
    static Direction greedyMopperDirection(RobotController rc) throws GameActionException {
        Direction bestDir = null;
        int bestScore = -1;

        for (Direction dir : directions) {
            if (!rc.canMove(dir)) continue;
            MapLocation dest = rc.getLocation().add(dir);

            int score = 0;
            MapInfo[] tiles = rc.senseNearbyMapInfos(dest, 8);
            for (MapInfo tile : tiles) {
                if (tile.getPaint().isEnemy()) score += 2;
            }
            RobotInfo[] enemies = rc.senseNearbyRobots(dest, 8, rc.getTeam().opponent());
            score += enemies.length * 3;

            if (score > bestScore) { bestScore = score; bestDir = dir; }
        }

        if (bestDir == null || bestScore <= 0) return getExploreDirection(rc);
        return bestDir;
    }

    // ================================================================
    //  SHARED UTILITIES
    // ================================================================

    static boolean isPaintTower(UnitType type) {
        return type == UnitType.LEVEL_ONE_PAINT_TOWER
            || type == UnitType.LEVEL_TWO_PAINT_TOWER
            || type == UnitType.LEVEL_THREE_PAINT_TOWER;
    }

    /** Update nearestTower to the closest visible ally paint tower. */
    static void rememberNearestPaintTower(RobotController rc) throws GameActionException {
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        MapLocation best = null;
        int bestDist = Integer.MAX_VALUE;

        for (RobotInfo ally : allies) {
            if (!isPaintTower(ally.type)) continue;
            int dist = rc.getLocation().distanceSquaredTo(ally.location);
            if (dist < bestDist) { bestDist = dist; best = ally.location; }
        }

        if (best != null) nearestTower = best;
    }

    /** Update nearestTower to the closest visible ally tower (any type). */
    static void rememberNearestTower(RobotController rc) throws GameActionException {
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        MapLocation best = null;
        int bestDist = Integer.MAX_VALUE;

        for (RobotInfo ally : allies) {
            if (!ally.type.isTowerType()) continue;
            int dist = rc.getLocation().distanceSquaredTo(ally.location);
            if (dist < bestDist) { bestDist = dist; best = ally.location; }
        }

        if (best != null) nearestTower = best;
    }

    /** Pull paint from an adjacent tower (soldiers/splashers: paint tower only). */
    static boolean tryWithdrawPaint(RobotController rc) throws GameActionException {
        if (!rc.isActionReady()) return false;

        boolean paintTowerOnly = (rc.getType() == UnitType.SOLDIER || rc.getType() == UnitType.SPLASHER);
        RobotInfo[] nearby = rc.senseNearbyRobots(2, rc.getTeam());
        for (RobotInfo ally : nearby) {
            if (!ally.type.isTowerType()) continue;
            if (paintTowerOnly && !isPaintTower(ally.type)) continue;
            if (ally.paintAmount > 0) {
                int needed = rc.getType().paintCapacity - rc.getPaint();
                int canTake = Math.min(needed, ally.paintAmount);
                if (canTake > 0 && rc.canTransferPaint(ally.location, -canTake)) {
                    rc.transferPaint(ally.location, -canTake);
                    return true;
                }
            }
        }
        return false;
    }

    /** Bug2 toward nearest tower; fallback to last known location or map center. */
    static void seekRefuelTower(RobotController rc) throws GameActionException {
        boolean paintTowerOnly = (rc.getType() == UnitType.SOLDIER || rc.getType() == UnitType.SPLASHER);

        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        MapLocation nearestTower = null;
        int nearestDist = Integer.MAX_VALUE;
        for (RobotInfo ally : allies) {
            if (!ally.type.isTowerType()) continue;
            if (paintTowerOnly && !isPaintTower(ally.type)) continue;
            int dist = rc.getLocation().distanceSquaredTo(ally.location);
            if (dist < nearestDist) {
                nearestDist = dist;
                nearestTower = ally.location;
            }
        }

        if (nearestTower != null) {
            bug2(rc, nearestTower);
        } else {
            MapLocation center = new MapLocation(rc.getMapWidth() / 2, rc.getMapHeight() / 2);
            bug2(rc, center);
        }
    }

    /** Paint underfoot: respect pattern mark if present, otherwise paint empty tiles. */
    static void paintCurrentTile(RobotController rc) throws GameActionException {
        if (!rc.isActionReady()) return;
        MapInfo currentTile = rc.senseMapInfo(rc.getLocation());
        PaintType mark = currentTile.getMark();
        PaintType paint = currentTile.getPaint();

        if (mark != PaintType.EMPTY) {
            if (mark != paint && rc.canAttack(rc.getLocation())) {
                boolean useSecondary = mark == PaintType.ALLY_SECONDARY;
                rc.attack(rc.getLocation(), useSecondary);
            }
        } else {
            if (paint == PaintType.EMPTY && rc.canAttack(rc.getLocation())) {
                rc.attack(rc.getLocation());
            }
        }
    }

    static void moveToward(RobotController rc, MapLocation target) throws GameActionException {
        if (!rc.isMovementReady()) return;
        if (rc.getLocation().equals(target)) return;

        Direction dir = rc.getLocation().directionTo(target);

        if (rc.canMove(dir)) {
            rc.move(dir);
        } else if (rc.canMove(dir.rotateLeft())) {
            rc.move(dir.rotateLeft());
        } else if (rc.canMove(dir.rotateRight())) {
            rc.move(dir.rotateRight());
        } else if (rc.canMove(dir.rotateLeft().rotateLeft())) {
            rc.move(dir.rotateLeft().rotateLeft());
        } else if (rc.canMove(dir.rotateRight().rotateRight())) {
            rc.move(dir.rotateRight().rotateRight());
        }
    }

    /** symmetryTarget → spread from ally centroid → random persistent direction. */
    static Direction getExploreDirection(RobotController rc) throws GameActionException {
        if (symmetryTarget != null
                && rc.getLocation().distanceSquaredTo(symmetryTarget) > 16) {
            Direction toSym = rc.getLocation().directionTo(symmetryTarget);
            if (rc.canMove(toSym)) return toSym;
            if (rc.canMove(toSym.rotateLeft())) return toSym.rotateLeft();
            if (rc.canMove(toSym.rotateRight())) return toSym.rotateRight();
        }

        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        if (allies.length > 0) {
            int sumX = 0, sumY = 0, count = 0;
            for (RobotInfo ally : allies) {
                if (!ally.type.isTowerType()) {
                    sumX += ally.location.x;
                    sumY += ally.location.y;
                    count++;
                }
            }
            if (count > 0) {
                MapLocation allyCenter = new MapLocation(sumX / count, sumY / count);
                Direction awayFromAllies = allyCenter.directionTo(rc.getLocation());
                if (rc.canMove(awayFromAllies)) return awayFromAllies;
                if (rc.canMove(awayFromAllies.rotateLeft())) return awayFromAllies.rotateLeft();
                if (rc.canMove(awayFromAllies.rotateRight())) return awayFromAllies.rotateRight();
            }
        }

        if (exploreDir == null || !rc.canMove(exploreDir)) {
            exploreDir = directions[rng.nextInt(directions.length)];
        }
        for (int i = 0; i < 8; i++) {
            if (rc.canMove(exploreDir)) return exploreDir;
            exploreDir = exploreDir.rotateRight();
        }
        return null;
    }

    /** Bug2 toward symmetryTarget while far; fallback to getExploreDirection. */
    static void exploreMove(RobotController rc) throws GameActionException {
        if (!rc.isMovementReady()) return;
        if (symmetryTarget != null && rc.getLocation().distanceSquaredTo(symmetryTarget) > 16) {
            bug2(rc, symmetryTarget);
            return;
        }
        Direction dir = getExploreDirection(rc);
        if (dir != null && rc.canMove(dir)) {
            rc.move(dir);
        }
    }

    // ================================================================
    //  PATHFINDING
    // ================================================================

    /** Tile score: empty +5, ally +2, enemy −6, wall/off-map −9999. */
    static int moveTileScore(RobotController rc, MapLocation loc) throws GameActionException {
        if (!rc.onTheMap(loc)) return -9999;
        MapInfo info = rc.senseMapInfo(loc);
        if (info.isWall()) return -9999;
        PaintType p = info.getPaint();
        if (p == PaintType.EMPTY) return 5;
        if (p.isAlly()) return 2;
        return -6;
    }

    /** Free: best-scored step toward target. Trace: right-hand wall-follow to M-line. */
    static void bug2(RobotController rc, MapLocation target) throws GameActionException {
        if (!rc.isMovementReady()) return;
        if (target == null) return;

        if (!target.equals(b2Dest)) {
            b2Dest = target;
            b2Tracing = false;
            b2Dir = null;
            b2Line = null;
        }

        if (!b2Tracing) {
            Direction dir = rc.getLocation().directionTo(target);
            Direction[] tries = {dir, dir.rotateLeft(), dir.rotateRight()};
            Direction best = null;
            int bestScore = -9999;
            for (Direction d : tries) {
                if (rc.canMove(d)) {
                    int score = moveTileScore(rc, rc.getLocation().add(d));
                    if (d == dir) score += 3;
                    if (score > bestScore) { bestScore = score; best = d; }
                }
            }
            if (best != null && bestScore > -6) {
                rc.move(best);
            } else if (rc.canMove(dir)) {
                rc.move(dir);
            } else {
                b2Tracing = true;
                b2StartDist = rc.getLocation().distanceSquaredTo(target);
                b2Dir = dir;
                b2Line = createLine(rc.getLocation(), target);
            }
        } else {
            if (b2Line != null && b2Line.contains(rc.getLocation())
                    && rc.getLocation().distanceSquaredTo(target) < b2StartDist) {
                b2Tracing = false;
                b2Line = null;
                return;
            }
            if (b2Dir == null) b2Dir = rc.getLocation().directionTo(target);
            for (int i = 0; i < 8; i++) {
                if (rc.canMove(b2Dir)) {
                    rc.move(b2Dir);
                    b2Dir = b2Dir.rotateRight().rotateRight();
                    break;
                }
                b2Dir = b2Dir.rotateLeft();
            }
        }
    }

    static HashSet<MapLocation> createLine(MapLocation a, MapLocation b) {
        HashSet<MapLocation> locs = new HashSet<>();
        int x = a.x, y = a.y;
        int dx = b.x - a.x, dy = b.y - a.y;
        int sx = (int) Math.signum(dx), sy = (int) Math.signum(dy);
        dx = Math.abs(dx); dy = Math.abs(dy);
        int d = Math.max(dx, dy), r = d / 2;
        if (dx > dy) {
            for (int i = 0; i < d; i++) {
                locs.add(new MapLocation(x, y)); x += sx; r += dy;
                if (r >= dx) { locs.add(new MapLocation(x, y)); y += sy; r -= dx; }
            }
        } else {
            for (int i = 0; i < d; i++) {
                locs.add(new MapLocation(x, y)); y += sy; r += dx;
                if (r >= dy) { locs.add(new MapLocation(x, y)); x += sx; r -= dy; }
            }
        }
        locs.add(new MapLocation(x, y));
        return locs;
    }
}