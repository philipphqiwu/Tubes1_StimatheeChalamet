package alternative_bots_2;

import static alternative_bots_2.Shared.directions;
import static alternative_bots_2.Shared.skipTurns;

import alternative_bots_2.Shared.MessageType;
import battlecode.common.Direction;
import battlecode.common.GameActionException;
import battlecode.common.MapInfo;
import battlecode.common.MapLocation;
import battlecode.common.Message;
import battlecode.common.RobotController;
import battlecode.common.RobotInfo;
import battlecode.common.UnitType;

public class TowerPlayer {

    private static int spawnCount = 0;
    // Keep at least one tower completion worth of chips so completed patterns can be converted.
    private static final int CHIP_RESERVE = 1000;
    private static final int SAVE_SKIP_TURNS = 70;

    public static void runTower(RobotController rc) throws GameActionException {
        readTowerMessages(rc);
        trySpawnRobot(rc);

        tryUpgradeTower(rc);
    }

    private static void tryUpgradeTower(RobotController rc) throws GameActionException {
        if (!rc.canUpgradeTower(rc.getLocation())) return;

        int towers = rc.getNumberTowers();
        int chips = rc.getChips();
        boolean shouldUpgrade = false;

        // Do not upgrade while we're intentionally pausing unit production for chip saving.
        if (skipTurns > 0) {
            shouldUpgrade = false;
        } else if (towers >= 4 && chips >= 3500) {
            shouldUpgrade = true;
        } else if (towers >= 6 && chips >= 6000) {
            shouldUpgrade = true;
        }

        if (shouldUpgrade) {
            rc.upgradeTower(rc.getLocation());
        }
    }

    private static void trySpawnRobot(RobotController rc) throws GameActionException {
        if (skipTurns > 0) {
            skipTurns--;
            rc.setIndicatorString("Saving chips for tower completion: " + skipTurns + " turns left");
            return;
        }

        UnitType toBuild = chooseUnitType(rc);
        int neededChips = toBuild.moneyCost + CHIP_RESERVE;
        if (rc.getChips() < neededChips) {
            rc.setIndicatorString("Holding chips: " + rc.getChips() + "/" + neededChips);
            return;
        }

        MapLocation spawnLoc = findBestSpawn(rc, toBuild);
        if (spawnLoc != null) {
            rc.buildRobot(toBuild, spawnLoc);
            spawnCount++;
        }
    }

    private static UnitType chooseUnitType(RobotController rc) {
        int round = rc.getRoundNum();
        double mapScale = Math.max(1.0, Math.sqrt((double) (rc.getMapWidth() * rc.getMapHeight()) / 900.0));
        int earlyGameTurns = (int) (50 * mapScale);
        int splasherUnlockTurns = (int) (100 * mapScale);
        int lateGameOffensiveTurns = (int) (200 * mapScale);

        if (round < earlyGameTurns) {
            return (spawnCount % 5 < 3) ? UnitType.SOLDIER : UnitType.MOPPER;
        } else if (round < splasherUnlockTurns) {
            int mod = spawnCount % 6;
            if (mod < 2) return UnitType.SOLDIER;
            if (mod < 3) return UnitType.SPLASHER;
            return UnitType.MOPPER;
        } else if (round < lateGameOffensiveTurns) {
            int mod = spawnCount % 6;
            if (mod < 1) return UnitType.SOLDIER;
            if (mod < 3) return UnitType.SPLASHER;
            return UnitType.MOPPER;
        } else {
            int mod = spawnCount % 6;
            if (mod < 2) return UnitType.SPLASHER;
            if (mod < 5) return UnitType.MOPPER;
            return UnitType.SOLDIER;
        }
    }

    private static MapLocation findBestSpawn(RobotController rc, UnitType toBuild) throws GameActionException {
        MapLocation bestSpawn = null;
        int bestScore = Integer.MAX_VALUE;
        for (Direction d : directions) {
            MapLocation loc = rc.getLocation().add(d);
            if (!rc.canBuildRobot(toBuild, loc)) continue;

            MapInfo info = rc.senseMapInfo(loc);
            int score = 0;
            RobotInfo[] nearbyAllies = rc.senseNearbyRobots(loc, 2, rc.getTeam());
            for (RobotInfo ally : nearbyAllies) {
                if (!ally.getType().isTowerType()) score += 10;
            }
            if (!info.getPaint().isAlly()) score += 5;

            if (score < bestScore) {
                bestScore = score;
                bestSpawn = loc;
            }
        }
        return bestSpawn;
    }

    private static void readTowerMessages(RobotController rc) throws GameActionException {
        Message[] messages = rc.readMessages(-1);
        for (Message m : messages) {
            System.out.println("Tower received message from " + m.getSenderID() + " : " + m.getBytes());
            if (m.getBytes() == MessageType.SAVING.ordinal()) {
                skipTurns = Math.max(skipTurns, SAVE_SKIP_TURNS);
            }
        }
    }
}
