package chamalet;

import java.util.ArrayList;
import java.util.Random;
import java.util.HashSet;

import battlecode.common.Clock;
import battlecode.common.Direction;
import battlecode.common.GameActionException;
import battlecode.common.MapInfo;
import battlecode.common.MapLocation;
import battlecode.common.Message;
import battlecode.common.PaintType;
import battlecode.common.RobotController;
import battlecode.common.RobotInfo;
import battlecode.common.UnitType;


/**
 * RobotPlayer is the class that describes your main robot strategy.
 * The run() method inside this class is like your main function: this is what we'll call once your robot
 * is created!
 */
public class RobotPlayer {
    private enum MessageType {
        SAVE_CHIPS
    }

    /**
     * We will use this variable to count the number of turns this robot has been alive.
     * You can use static variables like this to save any information you want. Keep in mind that even though
     * these variables are static, in Battlecode they aren't actually shared between your robots.
     */
    static int turnCount = 0;
    static boolean isMessenger = false;
    static ArrayList<MapLocation> knownTowers = new ArrayList<>();
    static boolean isSaving = false;
    static int savingTurns = 0;

    static MapLocation target;
    static boolean isTracing = false;
    static Direction tracingDirection = null;
    static int smallestDistance = 1000000;
    static MapLocation closesLocation = null;
    static int obstacleStarDist = 0;

    static HashSet<MapLocation> line = null;
    static MapLocation prevDest = null;

    /**
     * A random number generator.
     * We will use this RNG to make some random moves. The Random class is provided by the java.util.Random
     * import at the top of this file. Here, we *seed* the RNG with a constant number (6147); this makes sure
     * we get the same sequence of numbers every time this code is run. This is very useful for debugging!
     */
    static final Random rng = new Random(6147);

    /** Array containing all the possible movement directions. */
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

    /**
     * run() is the method that is called when a robot is instantiated in the Battlecode world.
     * It is like the main function for your robot. If this method returns, the robot dies!
     *
     * @param rc  The RobotController object. You use it to perform actions from this robot, and to get
     *            information on its current status. Essentially your portal to interacting with the world.
     **/
    @SuppressWarnings("unused")
    public static void run(RobotController rc) throws GameActionException {
        // Hello world! Standard output is very useful for debugging.
        // Everything you say here will be directly viewable in your terminal when you run a match!
        System.out.println("I'm alive");

        // You can also use indicators to save debug notes in replays.
        rc.setIndicatorString("Hello world!");

        if (rc.getType() == UnitType.MOPPER && rc.getID() % 2 == 0) {
            System.out.println("I'm a messenger");
            isMessenger = true;
        }

        while (true) {
            // This code runs during the entire lifespan of the robot, which is why it is in an infinite
            // loop. If we ever leave this loop and return from run(), the robot dies! At the end of the
            // loop, we call Clock.yield(), signifying that we've done everything we want to do.

            turnCount += 1;  // We have now been alive for one more turn!

            // Try/catch blocks stop unhandled exceptions, which cause your robot to explode.
            try {
                // The same run() function is called for every robot on your team, even if they are
                // different types. Here, we separate the control depending on the UnitType, so we can
                // use different strategies on different robots. If you wish, you are free to rewrite
                // this into a different control structure!
                switch (rc.getType()){
                    case SOLDIER: runSoldier(rc); break; 
                    case MOPPER: runMopper(rc); break;
                    case SPLASHER: runSplasher(rc); break; // Consider upgrading examplefuncsplayer to use splashers!
                    default: runTower(rc); break;
                    }
                }
             catch (GameActionException e) {
                // Oh no! It looks like we did something illegal in the Battlecode world. You should
                // handle GameActionExceptions judiciously, in case unexpected events occur in the game
                // world. Remember, uncaught exceptions cause your robot to explode!
                System.out.println("GameActionException");
                e.printStackTrace();

            } catch (Exception e) {   
                // Oh no! It looks like our code tried to do something bad. This isn't a
                // GameActionException, so it's more likely to be a bug in our code.
                System.out.println("Exception");
                e.printStackTrace();

            } finally {
                // Signify we've done everything we want to do, thereby ending our turn.
                // This will make our code wait until the next turn, and then perform this loop again.
                Clock.yield();
            }
            // End of loop: go back to the top. Clock.yield() has ended, so it's time for another turn!
        }

        // Your code should never reach here (unless it's intentional)! Self-destruction imminent...
    }

    /**
     * Run a single turn for towers.
     * This code is wrapped inside the infinite loop in run(), so it is called once per turn.
     */
    public static void runTower(RobotController rc) throws GameActionException{
        if (rc.canUpgradeTower(rc.getLocation())) {
            rc.upgradeTower(rc.getLocation());
        }

        if (savingTurns == 0) {
            isSaving = false;
            // Pick a direction to build in.
            Direction dir = directions[rng.nextInt(directions.length)];
            MapLocation nextLoc = rc.getLocation().add(dir);
            // Pick a random robot type to build.
            int robotType = rng.nextInt(3);
            if (robotType == 0 && rc.canBuildRobot(UnitType.SOLDIER, nextLoc)){
                rc.buildRobot(UnitType.SOLDIER, nextLoc);
                System.out.println("BUILT A SOLDIER");
            }
            else if (robotType == 1 && rc.canBuildRobot(UnitType.MOPPER, nextLoc)){
                rc.buildRobot(UnitType.MOPPER, nextLoc);
                System.out.println("BUILT A MOPPER");
            }
            else if (robotType == 2 && rc.canBuildRobot(UnitType.SPLASHER, nextLoc)){
                rc.buildRobot(UnitType.SPLASHER, nextLoc);
                System.out.println("BUILT A SPLASHER");
            }
        } else {
            savingTurns--;
            rc.setIndicatorString("Saving for " + savingTurns + " more turns.");
        }

        // Read incoming messages
        Message[] messages = rc.readMessages(-1);
        for (Message m : messages) {
            System.out.println("Tower received message: '#" + m.getSenderID() + " " + m.getBytes());

            if (m.getBytes() == MessageType.SAVE_CHIPS.ordinal() && !isSaving) {
                savingTurns = 15;
                isSaving = true;
            }
        }

        // TODO: can we attack other bots?
        RobotInfo[] nearbyRobots = rc.senseNearbyRobots();
        for(RobotInfo robot: nearbyRobots){
            if(rc.canAttack(robot.getLocation())){
                rc.attack(robot.getLocation());
            } 
        }
    }

    public static void bug0(RobotController rc) throws GameActionException {
        // target perlu didefinisikan ntar
        Direction dir = rc.getLocation().directionTo(target);
        if (rc.canMove(dir)) {
            rc.move(dir);
        } else {
            for (int i = 0; i < 8; i++) {
                dir = dir.rotateLeft();
                if (rc.canMove(dir)) {
                    rc.move(dir);
                    break;
                }
            }
        }
    }

    public static void bug1(RobotController rc) throws GameActionException {
        if (!isTracing) {
            // try to move towards the target
            Direction dir = rc.getLocation().directionTo(target);
            if (rc.canMove(dir)) {
                rc.move(dir);
            }
            else {
                // go into tracing mode
                isTracing = true;
                tracingDirection = dir;
            }
        }
        else {
            // circle the obstacle, ignore target direction
            if (rc.getLocation().equals(closesLocation)) {
                isTracing = false;
                tracingDirection = null;
                smallestDistance = 1000000;
                closesLocation = null;
            }
            else {
                int curDist = rc.getLocation().distanceSquaredTo(target);
                if (curDist < smallestDistance) {
                    smallestDistance = curDist;
                    closesLocation = rc.getLocation();
                }

                if (rc.canMove(tracingDirection)) {
                    rc.move(tracingDirection);
                    tracingDirection.rotateRight();
                    tracingDirection.rotateRight();
                }
                else {
                    for (int i = 0; i < 8; i++) {
                        tracingDirection = tracingDirection.rotateLeft();
                        if (rc.canMove(tracingDirection)) {
                            rc.move(tracingDirection);
                            tracingDirection.rotateRight();
                            tracingDirection.rotateRight();
                            break;
                        }
                    }
                }
            }
        }
    }

    public static void bug2(RobotController rc) throws GameActionException {
        if (!target.equals(prevDest)) {
            prevDest = target;
            line = createLine(target, rc.getLocation());
        }

        if (!isTracing) {
            Direction dir = rc.getLocation().directionTo(target);
            if (rc.canMove(dir)) {
                rc.move(dir);
            }
            else {
                // go into tracing mode
                isTracing = true;
                obstacleStarDist = rc.getLocation().distanceSquaredTo(target);
                tracingDirection = dir;
            }
        }
        else {
            if (line.contains(rc.getLocation()) && rc.getLocation().distanceSquaredTo(target) < obstacleStarDist) {
                isTracing = false;
            }
            else {
                if (rc.canMove(tracingDirection)) {
                    rc.move(tracingDirection);
                    tracingDirection.rotateRight();
                    tracingDirection.rotateRight();
                }
                else {
                    for (int i = 0; i < 8; i++) {
                        tracingDirection = tracingDirection.rotateLeft();
                        if (rc.canMove(tracingDirection)) {
                            rc.move(tracingDirection);
                            tracingDirection.rotateRight();
                            tracingDirection.rotateRight();
                            break;
                        }
                    }
                }
            }
        }
    }

    // Bresenham's line algorithm
    public static HashSet<MapLocation> createLine(MapLocation a, MapLocation b) {
        HashSet<MapLocation> locs = new HashSet<>();
        int x = a.x, y = a.y;
        int dx = b.x - a.x;
        int dy = b.y - a.y;
        int sx = (int) Math.signum(dx);
        int sy = (int) Math.signum(dy);
        dx = Math.abs(dx);
        dy = Math.abs(dy);
        int d = Math.max(dx, dy);
        int r = d/2;
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
        } 
        else {
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

    /**
     * Run a single turn for a Soldier.
     * This code is wrapped inside the infinite loop in run(), so it is called once per turn.
     */
    public static void runSoldier(RobotController rc) throws GameActionException{
        // Sense information about all visible nearby tiles.
        MapInfo[] nearbyTiles = rc.senseNearbyMapInfos();
        // Search for a nearby ruin to complete.
        MapInfo curRuin = null;
        int curDist = 999999;
        for (MapInfo tile : nearbyTiles){
            if (tile.hasRuin()){
                int dist = tile.getMapLocation().distanceSquaredTo(rc.getLocation());
                if (dist < curDist) {
                    curRuin = tile;
                    curDist = dist;
                }
            }
        }

        RobotInfo[] nearbyRobots = rc.senseNearbyRobots();
        RobotInfo curTower = null;
        for (RobotInfo tower : nearbyRobots){
            if (tower.getType().ordinal() >= 0 && tower.getType().ordinal() <= 8){
                curTower = tower;
            }
        }

        if (curRuin != null){
            MapLocation targetLoc = curRuin.getMapLocation();
            Direction dir = rc.getLocation().directionTo(targetLoc);
            if (rc.canMove(dir))
                rc.move(dir);
            // Mark the pattern we need to draw to build a tower here if we haven't already.
            MapLocation shouldBeMarked = curRuin.getMapLocation().subtract(dir);
            if (rc.senseMapInfo(shouldBeMarked).getMark() == PaintType.EMPTY && rc.canMarkTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, targetLoc)){
                rc.markTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, targetLoc);
                System.out.println("Trying to build a tower at " + targetLoc);
            }
            // Fill in any spots in the pattern with the appropriate paint.
            for (MapInfo patternTile : rc.senseNearbyMapInfos(targetLoc, 8)){
                if (patternTile.getMark() != patternTile.getPaint() && patternTile.getMark() != PaintType.EMPTY){
                    boolean useSecondaryColor = patternTile.getMark() == PaintType.ALLY_SECONDARY;
                    if (rc.canAttack(patternTile.getMapLocation()))
                        rc.attack(patternTile.getMapLocation(), useSecondaryColor);
                }
            }
            // Complete the ruin if we can.
            if (rc.canCompleteTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, targetLoc)){
                rc.completeTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, targetLoc);
                rc.setTimelineMarker("Tower built", 0, 255, 0);
                System.out.println("Built a tower at " + targetLoc + "!");
            } 
        }

        if (rc.getPaint() <= 100){
            //go nearest tower
            if(curTower != null){
                if(rc.canTransferPaint(curTower.getLocation(), -50)){
                    rc.transferPaint(curTower.getLocation(), -50);
                }
                else if(rc.canTransferPaint(curTower.getLocation(), -20)){
                    rc.transferPaint(curTower.getLocation(), -20);
                }
            }
            
        }

        // Move and attack randomly if no objective.
        Direction dir = directions[rng.nextInt(directions.length)];
        MapLocation nextLoc = rc.getLocation().add(dir);
        if (rc.canMove(dir)){
            rc.move(dir);
        }
        // Try to paint beneath us as we walk to avoid paint penalties.
        // Avoiding wasting paint by re-painting our own tiles.
        MapInfo currentTile = rc.senseMapInfo(rc.getLocation());
        if (!currentTile.getPaint().isAlly() && rc.canAttack(rc.getLocation())){
            rc.attack(rc.getLocation());
        }
    }


    /**
     * Run a single turn for a Mopper.
     * This code is wrapped inside the infinite loop in run(), so it is called once per turn.
     */
    public static void runMopper(RobotController rc) throws GameActionException{
        if (isMessenger) {
            rc.setIndicatorDot(rc.getLocation(), 255, 0, 0);
        }

        if (isMessenger && isSaving && knownTowers.size() > 0) {
            // bisa dipintarin lagi pilih mau balik ke tower mana
            MapLocation dst = knownTowers.get(0);
            Direction dir = rc.getLocation().directionTo(dst);
            if (rc.canMove(dir)) {
                rc.move(dir);
            }
        }
        
        // Move and attack randomly.
        Direction dir = directions[rng.nextInt(directions.length)];
        MapLocation nextLoc = rc.getLocation().add(dir);
        if (rc.canMove(dir)){
            rc.move(dir);
        }
        // if (rc.canMopSwing(dir)){
        //     rc.mopSwing(dir);
        //     System.out.println("Mop Swing! Booyah!");
        // }
        else if (rc.canAttack(nextLoc)){
            rc.attack(nextLoc);
        }
        // We can also move our code into different methods or classes to better organize it!
        updateEnemyRobots(rc);

        if (isMessenger) {
            updateFriendlyTowers(rc);
            checkNearbyRuins(rc);
        }
    }

    public static void runSplasher(RobotController rc) throws GameActionException {
        Direction dir = directions[rng.nextInt(directions.length)];
        MapLocation nextLoc = rc.getLocation().add(dir);

        if (rc.canMove(dir)) {
            rc.move(dir);
        }
        if (rc.canAttack(nextLoc)) {
            rc.attack(nextLoc);
        }
    }

    public static void updateEnemyRobots(RobotController rc) throws GameActionException{
        // Sensing methods can be passed in a radius of -1 to automatically 
        // use the largest possible value.
        RobotInfo[] enemyRobots = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        if (enemyRobots.length != 0){
            rc.setIndicatorString("There are nearby enemy robots! Scary!");
            // Save an array of locations with enemy robots in them for possible future use.
            MapLocation[] enemyLocations = new MapLocation[enemyRobots.length];
            for (int i = 0; i < enemyRobots.length; i++){
                enemyLocations[i] = enemyRobots[i].getLocation();
            }
            RobotInfo[] allyRobots = rc.senseNearbyRobots(-1, rc.getTeam());
            // Occasionally try to tell nearby allies how many enemy robots we see.
            if (rc.getRoundNum() % 20 == 0){
                for (RobotInfo ally : allyRobots){
                    if (rc.canSendMessage(ally.location, enemyRobots.length)){
                        rc.sendMessage(ally.location, enemyRobots.length);
                    }
                }
            }
        }
    }

    public static void updateFriendlyTowers(RobotController rc) throws GameActionException {
        RobotInfo[] allyRobots = rc.senseNearbyRobots(-1, rc.getTeam());
        for (RobotInfo ally : allyRobots) {
            if (!ally.getType().isTowerType()) continue;

            MapLocation allyLoc = ally.location;
            if (knownTowers.contains(allyLoc)) {
                if (isSaving) {
                    if (rc.canSendMessage(allyLoc)) {
                        rc.sendMessage(allyLoc, MessageType.SAVE_CHIPS.ordinal());
                        isSaving = false;
                    }
                }

                continue;
            }

            knownTowers.add(allyLoc);
        }
    }

    public static void checkNearbyRuins(RobotController rc) throws GameActionException {
        MapInfo[] nearbyTiles = rc.senseNearbyMapInfos();
        // Search for a nearby ruin to complete.
        for (MapInfo tile : nearbyTiles){
            if (!tile.hasRuin()) continue;
            if (rc.senseRobotAtLocation(tile.getMapLocation()) != null) continue;

            Direction dir = tile.getMapLocation().directionTo(rc.getLocation());
            MapLocation markTile = tile.getMapLocation().add(dir);
            if (!rc.senseMapInfo(markTile).getMark().isAlly()) continue;

            isSaving = true;
            return;
        }       
    }
 
}
