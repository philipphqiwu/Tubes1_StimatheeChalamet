package alternative_bots_2;

import static alternative_bots_2.Shared.defaultLoc;
import static alternative_bots_2.Shared.isMessenger;
import static alternative_bots_2.Shared.mapHeight;
import static alternative_bots_2.Shared.mapWidth;
import static alternative_bots_2.Shared.pickRandomDefaultLoc;
import static alternative_bots_2.Shared.spawnTower;
import static alternative_bots_2.Shared.target;
import static alternative_bots_2.Shared.turnCount;

import battlecode.common.Clock;
import battlecode.common.GameActionException;
import battlecode.common.RobotController;
import battlecode.common.RobotInfo;
import battlecode.common.UnitType;

public class RobotPlayer {

    @SuppressWarnings("unused")
    public static void run(RobotController rc) throws GameActionException {
        System.out.println("I'm alive");
        rc.setIndicatorString("Hello world!");

        mapWidth = rc.getMapWidth();
        mapHeight = rc.getMapHeight();
        defaultLoc = pickRandomDefaultLoc(rc);
        target = defaultLoc;

        // Record spawn tower: on first turn, the nearest allied tower is the one that spawned us
        RobotInfo[] nearby = rc.senseNearbyRobots(-1, rc.getTeam());
        for (RobotInfo r : nearby) {
            if (r.getType().isTowerType()) {
                spawnTower = r.getLocation();
                break;
            }
        }

        if (rc.getType() == UnitType.MOPPER) {
            isMessenger = true;
        }

        while (true) {
            turnCount += 1;
            try {
                switch (rc.getType()) {
                    case SOLDIER:  SoldierPlayer.runSoldier(rc);  break;
                    case MOPPER:   MopperPlayer.runMopper(rc);    break;
                    case SPLASHER: break;
                    default:       TowerPlayer.runTower(rc);      break;
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
}
