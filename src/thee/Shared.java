package thee;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Random;

import battlecode.common.Direction;
import battlecode.common.GameActionException;
import battlecode.common.MapInfo;
import battlecode.common.MapLocation;
import battlecode.common.PaintType;
import battlecode.common.RobotController;
import battlecode.common.RobotInfo;
import battlecode.common.UnitType;

public class Shared {

    // ── Message types ──────────────────────────────────────────────────
    enum MessageType {
        SAVING,
        BUILDING
    }

    // ── Bug2 pathing state ─────────────────────────────────────────────
    static boolean isTracing = false;
    static int smallestDistance = 1000000;
    static MapLocation closestLocation = null;
    static Direction tracingDir = null;
    static MapLocation prevDest = null;
    static HashSet<MapLocation> line = null;
    static int obstacleStartDist = 0;

    // ── General state ──────────────────────────────────────────────────
    static int turnCount = 0;
    static boolean isMessenger = false;
    static boolean isSaving = false;
    static int skipTurns = 0;

    static ArrayList<MapLocation> allyTowersLoc = new ArrayList<>();
    static ArrayList<MapLocation> reportedRuins = new ArrayList<>();

    static int mapWidth;
    static int mapHeight;
    static MapLocation defaultLoc;
    static MapLocation closestRefill = null;
    static MapLocation spawnTower = null;
    static MapLocation target;
    static boolean isRefilling = false;

    // Paint thresholds for refilling
    static final int PAINT_LOW = 50;
    static final int PAINT_HIGH = 150;

    static final Random rng = new Random();

    static final Direction[] directions = {
        Direction.NORTH,
        Direction.NORTHEAST,
        Direction.EAST,
        Direction.SOUTHEAST,
        Direction.SOUTH,
        Direction.SOUTHWEST,
        Direction.WEST,
        Direction.NORTHWEST,
    };

    // ════════════════════════════════════════════════════════════════════
    //  Shared helpers
    // ════════════════════════════════════════════════════════════════════

    static MapLocation pickRandomDefaultLoc(RobotController rc) {
        int choice = rng.nextInt(5);
        switch (choice) {
            case 0:  System.out.println("Go Right");  return new MapLocation(mapWidth - 1, rc.getLocation().y);
            case 1:  System.out.println("Go Left");   return new MapLocation(0, rc.getLocation().y);
            case 2:  System.out.println("Go Up");     return new MapLocation(rc.getLocation().x, mapHeight - 1);
            case 3:  System.out.println("Go Down");   return new MapLocation(rc.getLocation().x, 0);
            default: System.out.println("Go Middle"); return new MapLocation((mapWidth - 1) / 2, (mapHeight - 1) / 2);
        }
    }

    static boolean isAtMapEdge(RobotController rc) {
        int x = rc.getLocation().x;
        int y = rc.getLocation().y;
        return x == mapWidth - 1 || x == 0 || y == mapHeight - 1 || y == 0;
    }

    static void changeDefaultLocIfReached(RobotController rc) throws GameActionException {
        int next = rng.nextInt(3);
        defaultLoc = switch (next) {
            case 0 -> new MapLocation(mapWidth - defaultLoc.x, mapHeight - defaultLoc.y);
            case 1 -> new MapLocation(defaultLoc.y, defaultLoc.x);
            default -> new MapLocation(mapWidth / 2, mapHeight / 2);
        };
    }

    static boolean isRuinPatternComplete(RobotController rc, MapLocation ruinLoc) throws GameActionException {
        boolean hasMarkedTile = false;
        for (MapInfo patternTile : rc.senseNearbyMapInfos(ruinLoc, 8)) {
            if (patternTile.getMapLocation().equals(ruinLoc)) continue;

            PaintType mark = patternTile.getMark();
            PaintType paint = patternTile.getPaint();
            if (mark == PaintType.EMPTY) continue;

            hasMarkedTile = true;
            if (mark != paint) return false;
        }
        return hasMarkedTile;
    }

    static boolean canCompleteAnyTowerPattern(RobotController rc, MapLocation ruinLoc)
            throws GameActionException {
        return rc.canCompleteTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, ruinLoc)
            || rc.canCompleteTowerPattern(UnitType.LEVEL_ONE_MONEY_TOWER, ruinLoc);
    }

    static void updateEnemyRobots(RobotController rc) throws GameActionException {
        RobotInfo[] enemyRobots = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        if (enemyRobots.length == 0) return;

        rc.setIndicatorString("There are nearby enemy robots! Scary!");

        MapLocation[] enemyLocations = new MapLocation[enemyRobots.length];
        for (int i = 0; i < enemyRobots.length; i++) {
            enemyLocations[i] = enemyRobots[i].getLocation();
        }

        if (rc.getRoundNum() % 20 == 0) {
            RobotInfo[] allyRobots = rc.senseNearbyRobots(-1, rc.getTeam());
            for (RobotInfo ally : allyRobots) {
                if (rc.canSendMessage(ally.location, enemyRobots.length)) {
                    rc.sendMessage(ally.location, enemyRobots.length);
                }
            }
        }
    }

    static void updateAllyTowers(RobotController rc) throws GameActionException {
        RobotInfo[] nearbyRobots = rc.senseNearbyRobots();
        for (RobotInfo bot : nearbyRobots) {
            if (!bot.getType().isTowerType()) continue;

            MapLocation towerLoc = bot.getLocation();
            if (allyTowersLoc.contains(towerLoc)) {
                if (isSaving && rc.canSendMessage(towerLoc)) {
                    rc.sendMessage(towerLoc, MessageType.SAVING.ordinal());
                    isSaving = false;
                }
                continue;
            }
            allyTowersLoc.add(towerLoc);
        }
    }

    static void checkIncompleteRuin(RobotController rc) throws GameActionException {
        MapInfo[] nearbyTiles = rc.senseNearbyMapInfos();
        for (MapInfo tile : nearbyTiles) {
            if (!tile.hasRuin()) continue;

            MapLocation ruinLoc = tile.getMapLocation();
            if (rc.senseRobotAtLocation(ruinLoc) != null) {
                reportedRuins.remove(ruinLoc);
                continue;
            }

            Direction dir = tile.getMapLocation().directionTo(rc.getLocation());
            MapLocation mark = tile.getMapLocation().add(dir);
            if (!rc.senseMapInfo(mark).getMark().isAlly());

            if (!isRuinPatternComplete(rc, ruinLoc)) continue;
            if (reportedRuins.contains(ruinLoc)) continue;

            reportedRuins.add(ruinLoc);
            isSaving = true;
            return;
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  Bug2 pathing
    // ════════════════════════════════════════════════════════════════════

    static void bug2(RobotController rc, MapLocation target) throws GameActionException {
        if (!target.equals(prevDest)) {
            prevDest = target;
            line = createLine(target, rc.getLocation());
        }

        if (!isTracing) {
            Direction dir = rc.getLocation().directionTo(target);
            if (rc.canMove(dir)) {
                rc.move(dir);
            } else {
                isTracing = true;
                obstacleStartDist = rc.getLocation().distanceSquaredTo(target);
                tracingDir = dir;
            }
        } else {
            if (line.contains(rc.getLocation()) && rc.getLocation().distanceSquaredTo(target) < obstacleStartDist) {
                isTracing = false;
            }

            if (rc.canMove(tracingDir)) {
                rc.move(tracingDir);
                tracingDir = tracingDir.rotateLeft();
                tracingDir = tracingDir.rotateRight();
            } else {
                for (int i = 0; i < 8; i++) {
                    tracingDir = tracingDir.rotateLeft();
                    if (rc.canMove(tracingDir)) {
                        rc.move(tracingDir);
                        tracingDir = tracingDir.rotateRight();
                        tracingDir = tracingDir.rotateRight();
                        break;
                    }
                }
            }
        }
    }

    static HashSet<MapLocation> createLine(MapLocation a, MapLocation b) {
        HashSet<MapLocation> locs = new HashSet<>();
        int x = a.x, y = a.y;
        int dx = b.x - a.x;
        int dy = b.y - a.y;
        int sx = (int) Math.signum(dx);
        int sy = (int) Math.signum(dy);
        dx = Math.abs(dx);
        dy = Math.abs(dy);
        int d = Math.max(dx, dy);
        int r = d / 2;

        if (dx > dy) {
            for (int i = 0; i < d; i++) {
                locs.add(new MapLocation(x, y));
                x += sx;
                r += dy;
                if (r >= dx) {
                    locs.add(new MapLocation(x, y));
                    y += sy;
                    r -= dx;
                }
            }
        } else {
            for (int i = 0; i < d; i++) {
                locs.add(new MapLocation(x, y));
                y += sy;
                r += dx;
                if (r >= dy) {
                    locs.add(new MapLocation(x, y));
                    x += sx;
                    r -= dy;
                }
            }
        }
        locs.add(new MapLocation(x, y));
        return locs;
    }
}
