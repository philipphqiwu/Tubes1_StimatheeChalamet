package thee;

import battlecode.common.Direction;
import battlecode.common.GameActionException;
import battlecode.common.MapInfo;
import battlecode.common.MapLocation;
import battlecode.common.PaintType;
import battlecode.common.RobotController;
import battlecode.common.RobotInfo;
import battlecode.common.UnitType;
import static victim.Shared.PAINT_HIGH;
import static victim.Shared.PAINT_LOW;
import static victim.Shared.bug2;
import static victim.Shared.changeDefaultLocIfReached;
import static victim.Shared.closestRefill;
import static victim.Shared.defaultLoc;
import static victim.Shared.directions;
import static victim.Shared.isAtMapEdge;
import static victim.Shared.isRefilling;
import static victim.Shared.rng;
import static victim.Shared.spawnTower;
import static victim.Shared.target;

public class SoldierPlayer {

    private static boolean[][] paintTowerPattern = null;
    private static boolean[][] moneyTowerPattern = null;
    private static final int TOWER_COMPLETION_CHIP_COST = Math.min(
        UnitType.LEVEL_ONE_PAINT_TOWER.moneyCost,
        UnitType.LEVEL_ONE_MONEY_TOWER.moneyCost
    );
    private static final int RUIN_RETARGET_COOLDOWN_TURNS = 10;
    private static int ruinRetargetCooldown = 0;
    private static MapLocation ignoredCompletedRuin = null;

    public static void runSoldier(RobotController rc) throws GameActionException {
        MapInfo[] nearbyTiles = rc.senseNearbyMapInfos();
        RobotInfo[] nearbyRobots = rc.senseNearbyRobots();

        // Clear the ignored ruin once we can afford to build, or if a tower was
        // already built there by another soldier.  The ruinRetargetCooldown
        // (checked below) still prevents immediate snap-back for several turns.
        if (ignoredCompletedRuin != null) {
            boolean towerBuiltThere = rc.canSenseRobotAtLocation(ignoredCompletedRuin)
                && rc.senseRobotAtLocation(ignoredCompletedRuin) != null;
            if (canAffordTowerCompletion(rc) || towerBuiltThere) {
                ignoredCompletedRuin = null;
            }
        }

        updateClosestRefill(rc, nearbyRobots);

        // Stop refilling once paint is high enough
        if (isRefilling && rc.getPaint() >= PAINT_HIGH) {
            isRefilling = false;
        }
        // Start refilling when paint drops too low
        if (!isRefilling && rc.getPaint() <= PAINT_LOW) {
            isRefilling = true;
        }

        if (isRefilling) {
            moveToRefill(rc);
            return;
        }

        // After abandoning a completed-but-unbuildable ruin, stay in explore mode
        // for a couple turns to avoid immediate back-and-forth retargeting.
        if (ruinRetargetCooldown > 0) {
            ruinRetargetCooldown--;
            runExploreTurn(rc, nearbyTiles);
            return;
        }

        MapInfo curRuin = findClosestEmptyRuin(rc, nearbyTiles);
        if (curRuin != null) {
            handleRuinBuilding(rc, curRuin);
            // Direction dir = directions[rng.nextInt(directions.length)];
            // if (rc.canMove(dir)) rc.move(dir);
            return;
        }

        runExploreTurn(rc, nearbyTiles);
    }

    private static void runExploreTurn(RobotController rc, MapInfo[] nearbyTiles) throws GameActionException {

        // Check for enemy paint near ruins — mark so moppers can find and clear it
        flagEnemyPaintAtRuins(rc, nearbyTiles);

        updateSoldierTarget(rc);
        moveTowardTarget(rc);
    }

    private static void flagEnemyPaintAtRuins(RobotController rc, MapInfo[] nearbyTiles) throws GameActionException {
        for (MapInfo tile : nearbyTiles) {
            if (!tile.hasRuin()) continue;
            MapLocation ruinLoc = tile.getMapLocation();
            // Check if there's enemy paint in the 5x5 area around this ruin
            boolean hasEnemyPaint = false;
            for (MapInfo patternTile : rc.senseNearbyMapInfos(ruinLoc, 8)) {
                if (patternTile.getPaint().isEnemy()) {
                    hasEnemyPaint = true;
                    break;
                }
            }
            if (!hasEnemyPaint) continue;
            // Mark a tile adjacent to the ruin so moppers can detect it
            for (Direction d : directions) {
                MapLocation markLoc = ruinLoc.add(d);
                if (rc.canMark(markLoc)) {
                    rc.mark(markLoc, true);
                    rc.setIndicatorDot(ruinLoc, 255, 0, 0);
                    return; // one flag per turn is enough
                }
            }
        }
    }

    private static MapInfo findClosestEmptyRuin(RobotController rc, MapInfo[] nearbyTiles) throws GameActionException {
        MapInfo curRuin = null;
        int curDist = 999999;
        for (MapInfo tile : nearbyTiles) {
            if (!tile.hasRuin()) continue;

            MapLocation ruinLoc = tile.getMapLocation();
            if (ignoredCompletedRuin != null && ruinLoc.equals(ignoredCompletedRuin)) continue;
            if (rc.canSenseRobotAtLocation(ruinLoc)) continue;

            // Skip if another allied soldier is already building at this ruin
            RobotInfo[] nearRuin = rc.senseNearbyRobots(ruinLoc, 8, rc.getTeam());
            boolean alreadyOccupied = false;
            for (RobotInfo ally : nearRuin) {
                if (ally.getType() == UnitType.SOLDIER && ally.getID() != rc.getID()) {
                    alreadyOccupied = true;
                    break;
                }
            }
            if (alreadyOccupied) continue;

            // If a pattern is fully painted but chips are insufficient, blacklist
            // this ruin so we don't keep re-approaching it from slightly further
            // away (where isPatternComplete returns false due to limited sensor
            // range, causing the soldier to walk back and discover it's complete
            // again — an infinite loop).
            if (isAnyTowerPatternComplete(rc, ruinLoc) && !canAffordTowerCompletion(rc)) {
                ignoredCompletedRuin = ruinLoc;
                ruinRetargetCooldown = RUIN_RETARGET_COOLDOWN_TURNS;
                Shared.isTracing = false;
                Shared.prevDest = null;
                defaultLoc = Shared.pickRandomDefaultLoc(rc);
                target = defaultLoc;
                continue;
            }

            int dist = ruinLoc.distanceSquaredTo(rc.getLocation());
            if (dist < curDist) {
                curRuin = tile;
                curDist = dist;
            }
        }
        return curRuin;
    }

    // cuman ambil refill tower dari paint tower
    private static void updateClosestRefill(RobotController rc, RobotInfo[] nearbyRobots) {
        int bestDist = (closestRefill != null)
            ? rc.getLocation().distanceSquaredTo(closestRefill)
            : Integer.MAX_VALUE;
        for (RobotInfo robot : nearbyRobots) {
            if (!robot.getType().isTowerType()) continue;
            if (robot.getTeam() != rc.getTeam()) continue;
            if (robot.getType() != UnitType.LEVEL_ONE_PAINT_TOWER && robot.getType() != UnitType.LEVEL_TWO_PAINT_TOWER && robot.getType() != UnitType.LEVEL_THREE_PAINT_TOWER) continue;
            int dist = rc.getLocation().distanceSquaredTo(robot.getLocation());
            if (dist < bestDist) {
                bestDist = dist;
                closestRefill = robot.getLocation();
            }
        }
    }

    private static UnitType getNextTowerType(RobotController rc) throws GameActionException {
        return (rc.getNumberTowers() % 2 == 0)
            ? UnitType.LEVEL_ONE_PAINT_TOWER
            : UnitType.LEVEL_ONE_MONEY_TOWER;
    }

    private static void ensureTowerPatternsLoaded(RobotController rc) throws GameActionException {
        if (paintTowerPattern == null) {
            paintTowerPattern = rc.getTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER);
        }
        if (moneyTowerPattern == null) {
            moneyTowerPattern = rc.getTowerPattern(UnitType.LEVEL_ONE_MONEY_TOWER);
        }
    }

    private static boolean isWithinPattern(MapLocation ruinLoc, MapLocation paintLoc) {
        return Math.abs(paintLoc.x - ruinLoc.x) <= 2
            && Math.abs(paintLoc.y - ruinLoc.y) <= 2
            && !ruinLoc.equals(paintLoc);
    }

    private static boolean getIsSecondary(MapLocation ruinLoc, MapLocation paintLoc,
                                          UnitType towerType) {
        if (!isWithinPattern(ruinLoc, paintLoc)) return false;
        int col = paintLoc.x - ruinLoc.x + 2;
        int row = paintLoc.y - ruinLoc.y + 2;
        return towerType == UnitType.LEVEL_ONE_PAINT_TOWER
            ? paintTowerPattern[row][col]
            : moneyTowerPattern[row][col];
    }

    private static UnitType detectExistingPatternType(RobotController rc,
                                                      MapLocation ruinLoc) throws GameActionException {
        ensureTowerPatternsLoaded(rc);

        // Early game: ambient allied paint can create false positives.
        if (rc.getNumberTowers() < 3) {
            return getNextTowerType(rc);
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
            return paintMatchCount > moneyMatchCount
                ? UnitType.LEVEL_ONE_PAINT_TOWER
                : UnitType.LEVEL_ONE_MONEY_TOWER;
        }

        return getNextTowerType(rc);
    }

    private static boolean isPatternCompleteForType(RobotController rc, MapLocation ruinLoc,
                                                    UnitType towerType) throws GameActionException {
        ensureTowerPatternsLoaded(rc);

        boolean hasCheckableTile = false;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                MapLocation tile = ruinLoc.translate(dx, dy);
                if (tile.equals(ruinLoc)) continue;
                if (!rc.canSenseLocation(tile)) return false;

                MapInfo info = rc.senseMapInfo(tile);
                if (info.isWall() || info.hasRuin()) continue;

                hasCheckableTile = true;
                boolean useSecondaryColor = getIsSecondary(ruinLoc, tile, towerType);
                PaintType desired = useSecondaryColor
                    ? PaintType.ALLY_SECONDARY
                    : PaintType.ALLY_PRIMARY;

                if (info.getPaint() != desired) return false;
            }
        }
        return hasCheckableTile;
    }

    private static boolean isAnyTowerPatternComplete(RobotController rc,
                                                     MapLocation ruinLoc) throws GameActionException {
        return isPatternCompleteForType(rc, ruinLoc, UnitType.LEVEL_ONE_PAINT_TOWER)
            || isPatternCompleteForType(rc, ruinLoc, UnitType.LEVEL_ONE_MONEY_TOWER);
    }

    private static boolean canAffordTowerCompletion(RobotController rc) {
        return rc.getChips() >= TOWER_COMPLETION_CHIP_COST;
    }

    private static void abandonRuinAndExplore(RobotController rc, MapLocation ruinLoc)
            throws GameActionException {
        ignoredCompletedRuin = ruinLoc;
        ruinRetargetCooldown = RUIN_RETARGET_COOLDOWN_TURNS;

        // Reset Bug2 tracing state so the pathfinder doesn't guide us back
        // toward the ruin we just abandoned.
        Shared.isTracing = false;
        Shared.prevDest = null;

        // Pick a new default exploration target that leads away from this ruin
        defaultLoc = Shared.pickRandomDefaultLoc(rc);
        target = defaultLoc;

        moveTowardTarget(rc);
    }

    private static void handleRuinBuilding(RobotController rc, MapInfo curRuin) throws GameActionException {
        MapLocation targetLoc = curRuin.getMapLocation();

        // Pattern is already complete but cannot be completed yet (usually chip-gated).
        // Abandon this ruin so we don't keep coming back.
        if (isAnyTowerPatternComplete(rc, targetLoc) && !canAffordTowerCompletion(rc)) {
            abandonRuinAndExplore(rc, targetLoc);
            return;
        }

        int dist = rc.getLocation().distanceSquaredTo(targetLoc);

        Direction dir;
        if (dist <= 2) {
            // Adjacent to ruin — jump between diagonal positions
            // Diagonal positions give better paint coverage of the 5x5 pattern
            Direction fromRuin = targetLoc.directionTo(rc.getLocation());
            boolean isDiag = (fromRuin == Direction.NORTHEAST || fromRuin == Direction.NORTHWEST
                           || fromRuin == Direction.SOUTHEAST || fromRuin == Direction.SOUTHWEST);
            if (isDiag) {
                // Already diagonal — hop to the next diagonal (rotate by 90°)
                dir = rc.getLocation().directionTo(targetLoc.add(fromRuin.rotateRight()));
            } else {
                // Cardinal position — move to a diagonal immediately
                dir = rc.getLocation().directionTo(targetLoc.add(fromRuin.rotateRight().rotateRight()));
            }
            if (!rc.canMove(dir)) {
                // Try the other diagonal direction
                if (isDiag) {
                    dir = rc.getLocation().directionTo(targetLoc.add(fromRuin.rotateLeft()));
                } else {
                    dir = rc.getLocation().directionTo(targetLoc.add(fromRuin.rotateLeft().rotateLeft()));
                }
            }
        } else {
            // Far from ruin — move toward it
            dir = rc.getLocation().directionTo(targetLoc);
        }
        if (rc.canMove(dir)) rc.move(dir);
        rc.setIndicatorLine(rc.getLocation(), targetLoc, 0, 255, 0);

        UnitType towerType = detectExistingPatternType(rc, targetLoc);
        fillTowerPattern(rc, targetLoc, towerType);
        completeTowerPattern(rc, towerType, targetLoc);
    }

    private static void fillTowerPattern(RobotController rc, MapLocation targetLoc,
                                         UnitType towerType) throws GameActionException {
        ensureTowerPatternsLoaded(rc);

        if (towerType == UnitType.LEVEL_ONE_PAINT_TOWER) {
            rc.setIndicatorDot(targetLoc, 255, 0, 0);
        } else {
            rc.setIndicatorDot(targetLoc, 255, 0, 255);
        }

        for (MapInfo patternTile : rc.senseNearbyMapInfos(targetLoc, 8)) {
            MapLocation tileLoc = patternTile.getMapLocation();
            if (!isWithinPattern(targetLoc, tileLoc)) continue;
            if (patternTile.isWall() || patternTile.hasRuin()) continue;

            boolean useSecondaryColor = getIsSecondary(targetLoc, tileLoc, towerType);
            PaintType desired = useSecondaryColor
                ? PaintType.ALLY_SECONDARY
                : PaintType.ALLY_PRIMARY;

            if (patternTile.getPaint() != desired && rc.canAttack(tileLoc)) {
                rc.attack(tileLoc, useSecondaryColor);
            }
        }
    }

    private static void completeTowerPattern(RobotController rc, UnitType towerType,
                                             MapLocation targetLoc) throws GameActionException {
        if (rc.canCompleteTowerPattern(towerType, targetLoc)) {
            rc.completeTowerPattern(towerType, targetLoc);
            rc.setTimelineMarker("Tower built", 0, 255, 0);
            System.out.println("Built a tower at " + targetLoc + "!");
        }
    }

    private static void updateSoldierTarget(RobotController rc) throws GameActionException {
        if (target != defaultLoc) target = defaultLoc;
        paintNearbyUnclaimedTiles(rc);
    }

    private static void paintNearbyUnclaimedTiles(RobotController rc) throws GameActionException {
        for (MapInfo tile : rc.senseNearbyMapInfos(4)) {
            if (!tile.getPaint().isAlly() && tile.isPassable() && rc.canAttack(tile.getMapLocation())) {
                rc.attack(tile.getMapLocation());
            }
        }
    }

    private static void moveToRefill(RobotController rc) throws GameActionException {
        if (closestRefill == null) {
            // No paint tower known — head toward spawn tower to find one
            if (spawnTower != null) {
                rc.setIndicatorString("Refilling: heading to spawn tower " + spawnTower);
                rc.setIndicatorLine(rc.getLocation(), spawnTower, 255, 165, 0);
                // Update knowledge while walking back
                updateClosestRefill(rc, rc.senseNearbyRobots());
                if (closestRefill != null) return; // found one, next turn will go there
                bug2(rc, spawnTower);
            } else {
                moveTowardTarget(rc);
                rc.setIndicatorString("Continue Exploring: no tower known");
            }
            return;
        }

        rc.setIndicatorString("Refilling: heading to " + closestRefill);
        rc.setIndicatorLine(rc.getLocation(), closestRefill, 255, 165, 0);

        // Try to pull paint from the tower if in range
        if (rc.canSenseRobotAtLocation(closestRefill)) {
            RobotInfo tower = rc.senseRobotAtLocation(closestRefill);
            if (tower != null) {
                int paintNeeded = rc.getType().paintCapacity - rc.getPaint();
                int transferAmount = Math.min(paintNeeded, tower.getPaintAmount());
                if (transferAmount > 0 && rc.canTransferPaint(closestRefill, -transferAmount)) {
                    rc.transferPaint(closestRefill, -transferAmount);
                    return;
                }
            }
        }

        // Move closer to the tower
        Direction dir = rc.getLocation().directionTo(closestRefill);
        if (rc.canMove(dir)) rc.move(dir);
        dir = directions[rng.nextInt(directions.length)];
        if (rc.canMove(dir)) rc.move(dir);
    }

    private static void moveTowardTarget(RobotController rc) throws GameActionException {
        bug2(rc, target);

        if (isAtMapEdge(rc)) changeDefaultLocIfReached(rc);
        rc.setIndicatorLine(rc.getLocation(), target, 0, 255, 0);
        rc.setIndicatorString("Moving towards" + target);
    }
}
