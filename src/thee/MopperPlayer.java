package thee;

import battlecode.common.Direction;
import battlecode.common.GameActionException;
import battlecode.common.MapInfo;
import battlecode.common.MapLocation;
import battlecode.common.PaintType;
import battlecode.common.RobotController;
import static victim.Shared.*;

public class MopperPlayer {

    public static void runMopper(RobotController rc) throws GameActionException {
        handleMessengerMovement(rc);

        // Prioritize moving toward flagged enemy paint (secondary marks near ruins)
        if (moveTowardFlaggedEnemy(rc)) return;

        // Random move + attack
        Direction dir = directions[rng.nextInt(directions.length)];
        MapInfo nextTile = rc.senseMapInfo(rc.getLocation().add(dir));
        if (rc.canMove(dir)) rc.move(dir);

        if (rc.canMopSwing(dir)) {
            rc.mopSwing(dir);
            System.out.println("Mop Swing! Booyah!");
        } else if (rc.canAttack(nextTile.getMapLocation()) && nextTile.getPaint().isEnemy()) {
            rc.attack(nextTile.getMapLocation());
        }

        updateEnemyRobots(rc);
    }

    /** Check for secondary marks left by soldiers — indicates enemy paint near a ruin. */
    private static boolean moveTowardFlaggedEnemy(RobotController rc) throws GameActionException {
        MapInfo[] nearby = rc.senseNearbyMapInfos();
        MapLocation bestTarget = null;
        int bestDist = Integer.MAX_VALUE;

        for (MapInfo tile : nearby) {
            if (tile.getMark() == PaintType.ALLY_SECONDARY) {
                // Found a flag — look for enemy paint nearby to clear
                int dist = rc.getLocation().distanceSquaredTo(tile.getMapLocation());
                if (dist < bestDist) {
                    bestDist = dist;
                    bestTarget = tile.getMapLocation();
                }
            }
        }

        if (bestTarget == null) return false;

        // Attack any reachable enemy paint near the flag
        for (MapInfo tile : rc.senseNearbyMapInfos(bestTarget, 8)) {
            if (tile.getPaint().isEnemy() && rc.canAttack(tile.getMapLocation())) {
                rc.attack(tile.getMapLocation());
                break;
            }
        }

        // Move toward the flag
        Direction dir = rc.getLocation().directionTo(bestTarget);
        if (rc.canMove(dir)) rc.move(dir);

        // If all enemy paint is cleared around the flag, remove the mark
        boolean stillDirty = false;
        for (MapInfo tile : rc.senseNearbyMapInfos(bestTarget, 8)) {
            if (tile.getPaint().isEnemy()) { stillDirty = true; break; }
        }
        if (!stillDirty && rc.canRemoveMark(bestTarget)) {
            rc.removeMark(bestTarget);
        }

        return true;
    }

    private static void handleMessengerMovement(RobotController rc) throws GameActionException {
        if (isMessenger && !isSaving) {
            rc.setIndicatorDot(rc.getLocation(), 255, 0, 0);
            Direction dir = rc.getLocation().directionTo(defaultLoc);
            if (rc.canMove(dir)) rc.move(dir);
            if (rc.getLocation() == defaultLoc) changeDefaultLocIfReached(rc);
            rc.setIndicatorLine(rc.getLocation(), defaultLoc, 0, 255, 0);
            checkIncompleteRuin(rc);
            updateAllyTowers(rc);
        }

        if (isMessenger && isSaving && !allyTowersLoc.isEmpty()) {
            MapLocation dest = allyTowersLoc.get(rng.nextInt(allyTowersLoc.size()));
            Direction dir = rc.getLocation().directionTo(dest);
            if (rc.canMove(dir)) rc.move(dir);
            rc.setIndicatorLine(rc.getLocation(), dest, 0, 255, 0);
            updateAllyTowers(rc);
        }
    }
}
