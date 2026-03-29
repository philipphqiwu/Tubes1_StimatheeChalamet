package main_bot;

import static main_bot.Shared.EARLY_GAME_TURNS;
import static main_bot.Shared.LATEGAME_OFFENSIVE_TURNS;
import static main_bot.Shared.SPLASHER_UNLOCK_TURNS;
import static main_bot.Shared.decodeEnemyTowerMessage;
import static main_bot.Shared.decodeSaveChipsMessage;
import static main_bot.Shared.decodeSymMessage;
import static main_bot.Shared.directions;
import static main_bot.Shared.encodeEnemyTowerMessage;
import static main_bot.Shared.encodeSaveChipsMessage;
import static main_bot.Shared.encodeSymMessage;
import static main_bot.Shared.isEnemyTowerMessage;
import static main_bot.Shared.isSaveChipsMessage;
import static main_bot.Shared.isSaving;
import static main_bot.Shared.isSymMessage;
import static main_bot.Shared.knownEnemyTowers;
import static main_bot.Shared.saveChipTarget;
import static main_bot.Shared.savingTurns;
import static main_bot.Shared.spawnCount;
import static main_bot.Shared.symHorizontal;
import static main_bot.Shared.symRotational;
import static main_bot.Shared.symVertical;

import battlecode.common.Direction;
import battlecode.common.GameActionException;
import battlecode.common.MapInfo;
import battlecode.common.MapLocation;
import battlecode.common.Message;
import battlecode.common.RobotController;
import battlecode.common.RobotInfo;
import battlecode.common.UnitType;

/**
 * Tower logic: spawning units, relaying messages, upgrading, attacking enemies.
 */
public class TowerPlayer {

    static MapLocation savingForRuinLoc = null;

    public static void runTower(RobotController rc) throws GameActionException {
        // Prioritize building new towers over upgrading existing ones.
        // Upgrades are expensive (2500/5000) vs new tower (1000) — only upgrade when
        // we have enough towers or enough chips that it won't delay expansion.
        if (rc.canUpgradeTower(rc.getLocation())) {
            int towers = rc.getNumberTowers();
            int chips = rc.getChips();
            boolean shouldUpgrade = false;

            // Always defer if actively saving for a new tower
            if (isSaving) {
                shouldUpgrade = false;
            }
            // Only upgrade when we have enough towers and a chip surplus
            // Lv1→Lv2 costs 2500, Lv2→Lv3 costs 5000
            // Keep a buffer so we can still build new towers (1000 each)
            else if (towers >= 4 && chips >= 3500) {
                shouldUpgrade = true;
            } else if (towers >= 6 && chips >= 6000) {
                shouldUpgrade = true;
            }

            if (shouldUpgrade) {
                rc.upgradeTower(rc.getLocation());
            }
        }

        // Count unclaimed ruins visible from this tower — used for chip target
        int nearbyUnclaimedRuins = 0;
        MapLocation[] visibleRuins = rc.senseNearbyRuins(-1);
        for (MapLocation ruin : visibleRuins) {
            if (rc.senseRobotAtLocation(ruin) == null) {
                nearbyUnclaimedRuins++;
            }
        }

        // Threshold-based saving: stop once we have enough chips, or safety cap expires
        if (isSaving) {
            if (rc.getChips() >= saveChipTarget || savingTurns <= 0) {
                isSaving = false;
                savingTurns = 0;
                savingForRuinLoc = null;
            } else {
                savingTurns--;
                String ruinStr = savingForRuinLoc != null ? savingForRuinLoc.toString() : "?";
                rc.setIndicatorString("Saving for tower at " + ruinStr + ": "
                    + rc.getChips() + "/" + saveChipTarget
                    + " (" + savingTurns + "t left, " + nearbyUnclaimedRuins + " ruins nearby)");
            }
        }

        if (!isSaving) {

            int round = rc.getRoundNum();
            UnitType toBuild;

            if (round < EARLY_GAME_TURNS) {
                toBuild = (spawnCount % 5 < 3) ? UnitType.SOLDIER : UnitType.MOPPER;
            } else if (round < SPLASHER_UNLOCK_TURNS) {
                int mod = spawnCount % 6;
                if (mod < 2) toBuild = UnitType.SOLDIER;
                else if (mod < 3) toBuild = UnitType.SPLASHER;
                else toBuild = UnitType.MOPPER;
            } else if (round < LATEGAME_OFFENSIVE_TURNS) {
                int mod = spawnCount % 6;
                if (mod < 1) toBuild = UnitType.SOLDIER;
                else if (mod < 3) toBuild = UnitType.SPLASHER;
                else toBuild = UnitType.MOPPER;
            } else {
                int mod = spawnCount % 6;
                if (mod < 2) toBuild = UnitType.SPLASHER;
                else if (mod < 5) toBuild = UnitType.MOPPER;
                else toBuild = UnitType.SOLDIER;
            }

            MapLocation bestSpawn = null;
            int bestScore = Integer.MAX_VALUE;
            for (Direction d : directions) {
                MapLocation loc = rc.getLocation().add(d);
                if (!rc.canBuildRobot(toBuild, loc)) continue;
                MapInfo info = rc.senseMapInfo(loc);
                int score = 0;
                RobotInfo[] nearbyAllies = rc.senseNearbyRobots(loc, 2, rc.getTeam());
                for (RobotInfo r : nearbyAllies) {
                    if (!r.getType().isTowerType()) score += 10;
                }
                if (!info.getPaint().isAlly()) score += 5;
                if (score < bestScore) { bestScore = score; bestSpawn = loc; }
            }

            if (bestSpawn != null) {
                rc.buildRobot(toBuild, bestSpawn);
                spawnCount++;
            }
            if (!symHorizontal || !symVertical || !symRotational) {
                if (rc.canBroadcastMessage()) {
                    rc.broadcastMessage(encodeSymMessage());
                }
            }
            for (MapLocation eLoc : knownEnemyTowers) {
                if (rc.canBroadcastMessage()) {
                    rc.broadcastMessage(encodeEnemyTowerMessage(eLoc));
                }
            }
        } else {
            String ruinStr = savingForRuinLoc != null ? savingForRuinLoc.toString() : "?";
            rc.setIndicatorString("Saving for tower at " + ruinStr + ": "
                + rc.getChips() + "/" + saveChipTarget
                + " (" + savingTurns + "t left, " + nearbyUnclaimedRuins + " ruins nearby)");
        }

        // Read incoming messages
        Message[] messages = rc.readMessages(-1);
        for (Message m : messages) {
            System.out.println("Tower received message: '#" + m.getSenderID() + " " + m.getBytes());

            if (isSaveChipsMessage(m.getBytes()) && !isSaving) {
                if (rc.getRoundNum() < LATEGAME_OFFENSIVE_TURNS) {
                    int currentChips = rc.getChips();
                    // Target: enough for at least 1 tower, plus extra if multiple ruins nearby
                    int target = Shared.TOWER_CHIP_COST * Math.max(1, nearbyUnclaimedRuins);
                    if (currentChips < target) {
                        savingForRuinLoc = decodeSaveChipsMessage(m.getBytes());
                        rc.broadcastMessage(encodeSaveChipsMessage(savingForRuinLoc));
                        saveChipTarget = target;
                        // Safety cap: 20 turns max to prevent infinite saving
                        savingTurns = 20;
                        isSaving = true;
                    }
                }
            }

            if (isSymMessage(m.getBytes())) {
                boolean oldH = symHorizontal, oldV = symVertical, oldR = symRotational;
                decodeSymMessage(m.getBytes());
                if (oldH != symHorizontal || oldV != symVertical || oldR != symRotational) {
                    if (rc.canBroadcastMessage()) {
                        rc.broadcastMessage(encodeSymMessage());
                    }
                }
            }

            if (isEnemyTowerMessage(m.getBytes())) {
                MapLocation loc = decodeEnemyTowerMessage(m.getBytes());
                if (!knownEnemyTowers.contains(loc)) {
                    knownEnemyTowers.add(loc);
                    if (rc.canBroadcastMessage()) {
                        rc.broadcastMessage(encodeEnemyTowerMessage(loc));
                    }
                }
            }
        }

        RobotInfo[] nearbyRobots = rc.senseNearbyRobots();
        for (RobotInfo robot : nearbyRobots) {
            if (rc.canAttack(robot.getLocation())) {
                rc.attack(robot.getLocation());
            }
        }
    }
}
