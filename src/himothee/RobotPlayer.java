package himothee;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Random;

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
    /**
     * We will use this variable to count the number of turns this robot has been alive.
     * You can use static variables like this to save any information you want. Keep in mind that even though
     * these variables are static, in Battlecode they aren't actually shared between your robots.
     */
    private enum MessageType{
        SAVE_CHIPS,
        SYM_UPDATE  // payload encodes which symmetries are disproved
    }

    // Symmetry message encoding: bits 8-10 store disproved flags
    // bit 8 = horizontal disproved, bit 9 = vertical disproved, bit 10 = rotational disproved
    static final int SYM_MSG_OFFSET = 8;
    static final int SYM_H_BIT = 1 << SYM_MSG_OFFSET;
    static final int SYM_V_BIT = 1 << (SYM_MSG_OFFSET + 1);
    static final int SYM_R_BIT = 1 << (SYM_MSG_OFFSET + 2);

    static int encodeSymMessage(){
        int msg = MessageType.SYM_UPDATE.ordinal();
        if(!symHorizontal) msg |= SYM_H_BIT;
        if(!symVertical) msg |= SYM_V_BIT;
        if(!symRotational) msg |= SYM_R_BIT;
        return msg;
    }

    static void decodeSymMessage(int msg){
        if((msg & SYM_H_BIT) != 0) symHorizontal = false;
        if((msg & SYM_V_BIT) != 0) symVertical = false;
        if((msg & SYM_R_BIT) != 0) symRotational = false;
    }

    static boolean isSymMessage(int msg){
        return (msg & 0xFF) == MessageType.SYM_UPDATE.ordinal();
    }

    private enum RobotState{
        STARTING,
        PAINTING_PATTERN,
        EXPLORING,
        ATTACKING,
        RETREAT
    }

    // Pathfinding variables
    // bug1
    static boolean isTracing = false;
    static int smallestDistance = 1000000;
    static MapLocation closestLocation = null;
    static Direction tracingDir = null;
    // bug2
    static MapLocation prevDest = null;
    static HashSet<MapLocation> line = null;
    static int obstacleStartDist = 0;

    static int turnCount = 0;
    static boolean isMessenger = false;
    static boolean isSaving = false;
    static int savingTurns = 0;
    static ArrayList<MapLocation> knownTowers = new ArrayList<>();

    static RobotState state = RobotState.STARTING;
    static RobotState preRetreatState = null;
    static final int LOW_PAINT_THRESHOLD = 100;
    static MapLocation targetEnemyRuin = null;

    // Symmetry detection
    static boolean symHorizontal = true;
    static boolean symVertical = true;
    static boolean symRotational = true;
    static ArrayList<MapLocation> knownRuins = new ArrayList<>();

    static final int EARLY_GAME_TURNS = 50;
    static final int SPLASHER_UNLOCK_TURNS = 100;

    static boolean[][] paintTowerPattern = null;
    static boolean[][] moneyTowerPattern = null;

    static MapLocation paintingRuinLoc = null;
    static UnitType paintingRuinType = null;
    static int paintingTurns = 0;
    static int turnsWithoutAttack = 0;
    
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

        paintTowerPattern = rc.getTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER);
        moneyTowerPattern = rc.getTowerPattern(UnitType.LEVEL_ONE_MONEY_TOWER);

        // && rc.getID() % 2 == 0
        if(rc.getType() == UnitType.MOPPER && rc.getID() % 2 == 0){
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
        if(rc.canUpgradeTower(rc.getLocation())){
            rc.upgradeTower(rc.getLocation());
        }
        if(savingTurns == 0){
            isSaving = false;
            // Pick a direction to build in.
            Direction dir = directions[rng.nextInt(directions.length)];
            MapLocation nextLoc = rc.getLocation().add(dir);

            // Phased production: early game mostly soldiers, later mix in moppers & splashers
            int round = rc.getRoundNum();
            UnitType toBuild = null;

            if(round < EARLY_GAME_TURNS){
                // Early game: only soldiers
                toBuild = UnitType.SOLDIER;
            } else if(round < SPLASHER_UNLOCK_TURNS){
                // Mid game: soldiers + occasional splasher (20% chance)
                int roll = rng.nextInt(10);
                if(roll < 2){
                    toBuild = UnitType.SPLASHER;
                } else {
                    toBuild = UnitType.SOLDIER;
                }
            } else {
                // Late game: soldiers 50%, splashers 30%, moppers 20%
                int roll = rng.nextInt(10);
                if(roll < 5){
                    toBuild = UnitType.SOLDIER;
                } else{
                    toBuild = UnitType.SPLASHER;
                }
                // } else {
                //     toBuild = UnitType.MOPPER;
                // }
            }

            if(toBuild != null && rc.canBuildRobot(toBuild, nextLoc)){
                rc.buildRobot(toBuild, nextLoc);
                System.out.println("BUILT A " + toBuild);
            }

            // Broadcast symmetry info so newly spawned bots pick it up
            if(!symHorizontal || !symVertical || !symRotational){
                if(rc.canBroadcastMessage()){
                    rc.broadcastMessage(encodeSymMessage());
                }
            }
        } else{
            savingTurns--;
            rc.setIndicatorString("Saving for " + savingTurns + " more turns.");
            // System.out.println("HEMAT BRO!");
        }
        

        // Read incoming messages
        Message[] messages = rc.readMessages(-1);
        for (Message m : messages) {
            System.out.println("Tower received message: '#" + m.getSenderID() + " " + m.getBytes());

            if(m.getBytes() == MessageType.SAVE_CHIPS.ordinal() && !isSaving){
                rc.broadcastMessage(MessageType.SAVE_CHIPS.ordinal());
                savingTurns = 15;
                isSaving = true; 
            }

            // Relay symmetry info: absorb + broadcast to all nearby units
            if(isSymMessage(m.getBytes())){
                boolean oldH = symHorizontal, oldV = symVertical, oldR = symRotational;
                decodeSymMessage(m.getBytes());
                // Only broadcast if we learned something new
                if(oldH != symHorizontal || oldV != symVertical || oldR != symRotational){
                    if(rc.canBroadcastMessage()){
                        rc.broadcastMessage(encodeSymMessage());
                    }
                }
            }
        }

        RobotInfo[] nearbyRobots = rc.senseNearbyRobots();
        for(RobotInfo robot: nearbyRobots){
            if(rc.canAttack(robot.getLocation())){
                rc.attack(robot.getLocation());
            } 
        }
    }


    /**
     * Run a single turn for a Soldier.
     * This code is wrapped inside the infinite loop in run(), so it is called once per turn.
     */
    public static void runSoldier(RobotController rc) throws GameActionException{

        if(state == RobotState.STARTING){
            if(rc.getID()%2==0){
                state = RobotState.ATTACKING;
            } else{
                state = RobotState.EXPLORING;
            }
        }

        // Check for low paint and enter retreat
        if(state != RobotState.RETREAT && rc.getPaint() <= LOW_PAINT_THRESHOLD && knownTowers.size() > 0){
            preRetreatState = state;
            state = RobotState.RETREAT;
        }

        if(state == RobotState.RETREAT){
            runRetreat(rc);
            return;
        }

        if(state == RobotState.PAINTING_PATTERN){
            rc.setIndicatorString("im a painter");
            runPaintPattern(rc);
            paintingTurns++;

        } else if(state == RobotState.EXPLORING){
            rc.setIndicatorString("im exploring");
            // Sense information about all visible nearby tiles.
            MapInfo[] nearbyTiles = rc.senseNearbyMapInfos();
            // Search for a nearby ruin to complete.
            MapInfo curRuin = null;
            int curDist = 999999;
            for (MapInfo tile : nearbyTiles){
                if (tile.hasRuin() && rc.senseRobotAtLocation(tile.getMapLocation()) == null){
                    int dist = tile.getMapLocation().distanceSquaredTo(rc.getLocation());
                    if(dist < curDist){
                        curRuin = tile;
                        curDist = dist;
                    }
                }
                //  && rc.senseRobotAtLocation(tile.getMapLocation()).getTeam().isPlayer()
                if (rc.canSenseLocation(tile.getMapLocation()) && rc.senseRobotAtLocation(tile.getMapLocation()) != null && rc.senseRobotAtLocation(tile.getMapLocation()).getTeam()!=rc.getTeam()){
                    state = RobotState.ATTACKING;
                    break;
                }
            }

            if(curRuin != null){
                if(curDist > 4) bug0(rc, curRuin.getMapLocation());
                else{
                    // Only commit to painting if no other friendly soldier is already working this ruin
                    boolean anotherSoldierHere = false;
                    RobotInfo[] nearbyAllies = rc.senseNearbyRobots(curRuin.getMapLocation(), 8, rc.getTeam());
                    for(RobotInfo ally : nearbyAllies){
                        if(ally.getType() == UnitType.SOLDIER && ally.getID() != rc.getID()){
                            anotherSoldierHere = true;
                            break;
                        }
                    }
                    if(!anotherSoldierHere){
                        state = RobotState.PAINTING_PATTERN;
                        paintingRuinType = getNewTowerType(rc);
                        turnsWithoutAttack = 0;
                        paintingTurns = 0;
                        paintingRuinLoc = curRuin.getMapLocation();
                    }
                    // Otherwise skip this ruin and keep exploring
                }
            }

            // Move and attack randomly if no objective.
            Direction dir = directions[rng.nextInt(directions.length)];
            MapLocation nextLoc = rc.getLocation().add(dir);
            if (rc.canMove(dir)){
                rc.move(dir);
            }
            
            updateFriendlyTowers(rc);
            checkNearbyRuins(rc);
            updateSymmetryGuess(rc);

        } else if(state == RobotState.ATTACKING){
            // rc.setIndicatorString("im attacking");
            updateSymmetryGuess(rc);

            if(targetEnemyRuin == null){
                MapLocation[] infos = rc.senseNearbyRuins(-1);
                MapLocation ruin;

                // First check: is there a visible enemy tower we can directly target?
                for(MapLocation info: infos){
                    ruin = info;
                    if(ruin != null && rc.senseRobotAtLocation(ruin) != null && rc.senseRobotAtLocation(ruin).getTeam().opponent() == rc.getTeam()){
                        targetEnemyRuin = ruin;
                        break;
                    }
                }

                // If no visible enemy, guess based on symmetry from a known friendly tower/ruin
                if(targetEnemyRuin == null && infos.length > 0){
                    ruin = infos[0];
                    if(rc.senseRobotAtLocation(ruin) == null){
                        state = RobotState.EXPLORING;
                    }
                    targetEnemyRuin = guessEnemyLocation(rc, ruin);
                }

                // Also try mirroring a known friendly tower
                if(targetEnemyRuin == null && !knownTowers.isEmpty()){
                    targetEnemyRuin = guessEnemyLocation(rc, knownTowers.get(0));
                }
            }
           

            if(targetEnemyRuin != null){
                if(rc.canSenseLocation(targetEnemyRuin)){
                    if(rc.senseRobotAtLocation(targetEnemyRuin) == null || (rc.canSenseRobotAtLocation(targetEnemyRuin) && rc.senseRobotAtLocation(targetEnemyRuin).getTeam()==rc.getTeam())){
                        state = RobotState.EXPLORING;
                        targetEnemyRuin = null;
                    }
                }

                int dsquared = rc.getLocation().distanceSquaredTo(targetEnemyRuin);
                
                if(dsquared <= 8){
                    // Attack the enemy
                    if(rc.canAttack(targetEnemyRuin)){
                        rc.attack(targetEnemyRuin);
                    }

                    // Move away from the enemy
                    Direction away = rc.getLocation().directionTo(targetEnemyRuin).opposite();
                    if(rc.canMove(away)){
                        rc.move(away);
                    } else if(rc.canMove(away.rotateLeft())){
                        rc.move(away.rotateLeft());
                    } else if(rc.canMove(away.rotateRight())){
                        rc.move(away.rotateRight());
                    }
        
                } else{
                    // Check if only adjacent tiles are within attack radius of the tower
                    for(Direction d: directions){
                        MapLocation newLoc = rc.getLocation().add(d);
                        
                        if(newLoc.isWithinDistanceSquared(targetEnemyRuin, 8)){
                            if(rc.canMove(d)){
                                rc.move(d);
                                if(rc.canAttack(targetEnemyRuin)){
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
        

    
        // if (curRuin != null){
        //     MapLocation targetLoc = curRuin.getMapLocation();
        //     Direction dir = rc.getLocation().directionTo(targetLoc);
        //     if (rc.canMove(dir))
        //         rc.move(dir);
        //     // Mark the pattern we need to draw to build a tower here if we haven't already.
        //     MapLocation shouldBeMarked = curRuin.getMapLocation().subtract(dir);
        //     if (rc.senseMapInfo(shouldBeMarked).getMark() == PaintType.EMPTY && rc.canMarkTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, targetLoc)){
        //         rc.markTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, targetLoc);
        //         System.out.println("Trying to build a tower at " + targetLoc);
        //     }
        //     // Fill in any spots in the pattern with the appropriate paint.
        //     for (MapInfo patternTile : rc.senseNearbyMapInfos(targetLoc, 8)){
        //         if (patternTile.getMark() != patternTile.getPaint() && patternTile.getMark() != PaintType.EMPTY){
        //             boolean useSecondaryColor = patternTile.getMark() == PaintType.ALLY_SECONDARY;
        //             if (rc.canAttack(patternTile.getMapLocation()))
        //                 rc.attack(patternTile.getMapLocation(), useSecondaryColor);
        //         }
        //     }
        //     // Complete the ruin if we can.
        //     if (rc.canCompleteTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, targetLoc)){
        //         rc.completeTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, targetLoc);

        //         rc.setTimelineMarker("Tower built", 0, 255, 0);
        //         System.out.println("Built a tower at " + targetLoc + "!");
        //     }
        // }


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
        if(isMessenger){
            rc.setIndicatorDot(rc.getLocation(), 255, 0, 0);
        }

        // Check for low paint and enter retreat
        if(state != RobotState.RETREAT && rc.getPaint() <= LOW_PAINT_THRESHOLD && knownTowers.size() > 0){
            preRetreatState = state;
            state = RobotState.RETREAT;
        }

        if(state == RobotState.RETREAT){
            runRetreat(rc);
            return;
        }

        if(isMessenger && isSaving && knownTowers.size() > 0){
            MapLocation dst = knownTowers.get(0);
            Direction dir = rc.getLocation().directionTo(dst);
            if(rc.canMove(dir)){
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

        if(isMessenger){
            updateFriendlyTowers(rc);
            checkNearbyRuins(rc);
        }
    }

    public static void checkNearbyRuins(RobotController rc) throws GameActionException{
        // Check for unclaimed ruins nearby (no tower built yet) -- trigger saving
        MapInfo[] nearbyTiles = rc.senseNearbyMapInfos();
        for (MapInfo tile : nearbyTiles){
            if(!tile.hasRuin()) continue;
            if(rc.senseRobotAtLocation(tile.getMapLocation()) != null) continue;
            // Found an unclaimed ruin -- save chips for tower construction
            isSaving = true;
            return;
        }
    }

    public static void updateFriendlyTowers(RobotController rc) throws GameActionException{
        RobotInfo[] allyRobots = rc.senseNearbyRobots(-1, rc.getTeam());
        for (RobotInfo ally : allyRobots){
            if(!ally.getType().isTowerType()) continue;

            MapLocation allyLoc = ally.location;
            if(knownTowers.contains(allyLoc)){
                if(isSaving){
                    if(rc.canSendMessage(allyLoc)){
                        rc.sendMessage(allyLoc, MessageType.SAVE_CHIPS.ordinal());
                        isSaving = false;
                    }
                }
                continue;
            }

            knownTowers.add(allyLoc);
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
            // if (rc.getRoundNum() % 20 == 0){
            //     for (RobotInfo ally : allyRobots){
            //         if (rc.canSendMessage(ally.location, enemyRobots.length)){
            //             rc.sendMessage(ally.location, enemyRobots.length);
            //         }
            //     }
            // }
        }
    }

    static int splasherTargetIdx = 0; // Index to cycle through known towers for symmetry guesses

    /**
     * Run a single turn for a splasher.
     * This code is wrapped inside the infinite loop in run(), so it is called once per turn.
     */
    public static void runSplasher(RobotController rc) throws GameActionException{
        // Check for low paint and enter retreat
        if(state != RobotState.RETREAT && rc.getPaint() <= LOW_PAINT_THRESHOLD && !knownTowers.isEmpty()){
            preRetreatState = state;
            state = RobotState.RETREAT;
        }

        if(state == RobotState.RETREAT){
            runRetreat(rc);
            return;
        }

        // Initialize: most splashers attack, ~30% explore to claim territory
        if(state == RobotState.STARTING){
            if(rc.getID() % 10 < 3){
                state = RobotState.EXPLORING;
            } else {
                state = RobotState.ATTACKING;
            }
        }

        updateFriendlyTowers(rc);
        updateSymmetryGuess(rc);

        // --- Always check for nearby enemy robots/towers and prioritize them ---
        RobotInfo[] enemyRobots = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        MapLocation nearestEnemy = null;
        int nearestEnemyDist = Integer.MAX_VALUE;
        MapLocation nearestEnemyTower = null;
        int nearestTowerDist = Integer.MAX_VALUE;
        for(RobotInfo enemy : enemyRobots){
            int dist = rc.getLocation().distanceSquaredTo(enemy.getLocation());
            if(enemy.getType().isTowerType()){
                if(dist < nearestTowerDist){
                    nearestTowerDist = dist;
                    nearestEnemyTower = enemy.getLocation();
                }
            } else {
                if(dist < nearestEnemyDist){
                    nearestEnemyDist = dist;
                    nearestEnemy = enemy.getLocation();
                }
            }
        }

        // If we see an enemy tower directly, lock onto it
        if(nearestEnemyTower != null){
            targetEnemyRuin = nearestEnemyTower;
            if(state != RobotState.ATTACKING) state = RobotState.ATTACKING;
        }
        // If we see enemy robots, switch to attack mode
        else if(nearestEnemy != null && state == RobotState.EXPLORING){
            state = RobotState.ATTACKING;
        }

        if(state == RobotState.EXPLORING){
            rc.setIndicatorString("SPLASHER exploring/painting");

            // Even in explore, splash aggressively -- low threshold
            if(rc.isActionReady()){
                MapLocation bestTarget = findBestSplashTarget(rc, null);
                if(bestTarget != null){
                    rc.attack(bestTarget);
                }
            }

            // Move toward enemy paint / unpainted areas -- prefer enemy territory
            Direction bestDir = null;
            int bestDirScore = -1;
            for(Direction d : directions){
                if(!rc.canMove(d)) continue;
                MapLocation newLoc = rc.getLocation().add(d);
                int score = 0;
                MapInfo[] nearby = rc.senseNearbyMapInfos(newLoc, 8);
                for(MapInfo info : nearby){
                    PaintType p = info.getPaint();
                    if(p == PaintType.ENEMY_PRIMARY || p == PaintType.ENEMY_SECONDARY) score += 3;
                    else if(p == PaintType.EMPTY) score += 1;
                    // Ally paint is 0 -- we don't want to go there
                }
                // Slight bias toward enemy side of map
                MapLocation enemySide = guessEnemyLocation(rc, rc.getLocation());
                if(newLoc.distanceSquaredTo(enemySide) < rc.getLocation().distanceSquaredTo(enemySide)){
                    score += 2;
                }
                if(score > bestDirScore){
                    bestDirScore = score;
                    bestDir = d;
                }
            }
            if(bestDir != null){
                rc.move(bestDir);
            } else {
                Direction dir = directions[rng.nextInt(directions.length)];
                if(rc.canMove(dir)) rc.move(dir);
            }

            // Periodically switch to attack if we've been exploring too long
            if(turnCount % 20 == 0){
                state = RobotState.ATTACKING;
                targetEnemyRuin = null;
            }

        } else if(state == RobotState.ATTACKING){
            rc.setIndicatorString("SPLASHER attacking");

            // --- Acquire target ---
            if(targetEnemyRuin == null){
                // 1. Check for visible enemy towers
                MapLocation[] ruinInfos = rc.senseNearbyRuins(-1);
                for(MapLocation ruin : ruinInfos){
                    if(ruin != null && rc.canSenseRobotAtLocation(ruin)){
                        RobotInfo r = rc.senseRobotAtLocation(ruin);
                        if(r != null && r.getTeam() != rc.getTeam()){
                            targetEnemyRuin = ruin;
                            break;
                        }
                    }
                }
                // 2. Guess via symmetry -- cycle through all known towers
                if(targetEnemyRuin == null && !knownTowers.isEmpty()){
                    splasherTargetIdx = splasherTargetIdx % knownTowers.size();
                    targetEnemyRuin = guessEnemyLocation(rc, knownTowers.get(splasherTargetIdx));
                    splasherTargetIdx++;
                }
                // 3. Fallback: head toward enemy side of map
                if(targetEnemyRuin == null){
                    targetEnemyRuin = guessEnemyLocation(rc, new MapLocation(rc.getMapWidth() / 2, rc.getMapHeight() / 2));
                }
            }

            // Validate target: if visible and no enemy tower there, pick a new one
            if(targetEnemyRuin != null && rc.canSenseLocation(targetEnemyRuin)){
                RobotInfo atTarget = rc.senseRobotAtLocation(targetEnemyRuin);
                if(atTarget == null || atTarget.getTeam() == rc.getTeam()){
                    // Target is gone -- don't go back to exploring, just pick a new attack target
                    targetEnemyRuin = null;
                    // Try to find another target immediately
                    if(!knownTowers.isEmpty()){
                        splasherTargetIdx = splasherTargetIdx % knownTowers.size();
                        targetEnemyRuin = guessEnemyLocation(rc, knownTowers.get(splasherTargetIdx));
                        splasherTargetIdx++;
                    }
                }
            }

            // --- Splash: prioritize enemy paint, enemy robots, and near enemy towers ---
            if(rc.isActionReady()){
                MapLocation bestTarget = findBestSplashTarget(rc, targetEnemyRuin);
                if(bestTarget != null){
                    rc.attack(bestTarget);
                }
            }

            // --- Move toward target, but prefer positions that maximize splash value ---
            if(nearestEnemyTower != null && rc.getLocation().distanceSquaredTo(nearestEnemyTower) <= 16){
                // Close to enemy tower: kite -- splash and back away
                Direction away = rc.getLocation().directionTo(nearestEnemyTower).opposite();
                if(rc.canMove(away)) rc.move(away);
                else if(rc.canMove(away.rotateLeft())) rc.move(away.rotateLeft());
                else if(rc.canMove(away.rotateRight())) rc.move(away.rotateRight());
            } else if(nearestEnemy != null && rc.getLocation().distanceSquaredTo(nearestEnemy) <= 4){
                // Very close to enemy unit: step back to stay at splash range
                Direction away = rc.getLocation().directionTo(nearestEnemy).opposite();
                if(rc.canMove(away)) rc.move(away);
                else if(rc.canMove(away.rotateLeft())) rc.move(away.rotateLeft());
                else if(rc.canMove(away.rotateRight())) rc.move(away.rotateRight());
            } else if(targetEnemyRuin != null){
                bug2(rc, targetEnemyRuin);
                rc.setIndicatorDot(targetEnemyRuin, 255, 0, 255);
            } else {
                // Push toward enemy half of the map
                MapLocation enemyHalf = guessEnemyLocation(rc, new MapLocation(rc.getMapWidth() / 4, rc.getMapHeight() / 4));
                bug0(rc, enemyHalf);
            }
        }

        // Only self-paint if action wasn't used for something better
        if(rc.isActionReady()){
            MapInfo currentTile = rc.senseMapInfo(rc.getLocation());
            if(!currentTile.getPaint().isAlly() && rc.canAttack(rc.getLocation())){
                rc.attack(rc.getLocation());
            }
        }
    }

    /**
     * Find the best location to splash for maximum impact.
     * Heavily weights enemy paint, enemy robots, and proximity to enemy towers.
     */
    static MapLocation findBestSplashTarget(RobotController rc, MapLocation enemyTowerLoc) throws GameActionException{
        // Collect friendly ruin locations to avoid wrecking tower patterns
        MapLocation[] nearbyRuins = rc.senseNearbyRuins(-1);
        ArrayList<MapLocation> friendlyPatternRuins = new ArrayList<>();
        for(MapLocation ruin : nearbyRuins){
            // Unclaimed ruin (no tower yet) or friendly tower = protect the pattern area
            RobotInfo atRuin = rc.senseRobotAtLocation(ruin);
            if(atRuin == null || atRuin.getTeam() == rc.getTeam()){
                friendlyPatternRuins.add(ruin);
            }
        }

        MapLocation bestTarget = null;
        int bestScore = 0;
        MapInfo[] attackableTiles = rc.senseNearbyMapInfos(rc.getType().actionRadiusSquared);
        for(MapInfo tile : attackableTiles){
            MapLocation loc = tile.getMapLocation();
            if(!rc.canAttack(loc)) continue;

            // Skip targets whose splash would hit friendly tower pattern areas
            boolean hitsPattern = false;
            for(MapLocation ruin : friendlyPatternRuins){
                // Splash radius is 4 (distance squared). Pattern is 5x5 around ruin (dist sq <= 8).
                // If splash center is within dist sq 12 of a ruin, splash tiles can overlap the pattern.
                if(loc.isWithinDistanceSquared(ruin, 12)){
                    hitsPattern = true;
                    break;
                }
            }
            if(hitsPattern) continue;

            int score = 0;
            MapInfo[] splash = rc.senseNearbyMapInfos(loc, 4);
            for(MapInfo s : splash){
                PaintType p = s.getPaint();
                if(p == PaintType.ENEMY_PRIMARY || p == PaintType.ENEMY_SECONDARY){
                    score += 4; // High value: flipping enemy paint
                } else if(p == PaintType.EMPTY){
                    score += 1; // Some value: claiming neutral
                }
                // Ally paint = 0, don't waste splash on it
            }
            // Big bonus for splashing near a visible enemy tower
            if(enemyTowerLoc != null && loc.isWithinDistanceSquared(enemyTowerLoc, 9)){
                score += 8;
            }
            // Bonus for hitting tiles near enemy robots
            RobotInfo[] nearbyEnemies = rc.senseNearbyRobots(loc, 4, rc.getTeam().opponent());
            score += nearbyEnemies.length * 3;

            if(score > bestScore){
                bestScore = score;
                bestTarget = loc;
            }
        }
        // Lower threshold: splash even if only 1 enemy tile or a couple empty tiles
        return bestScore >= 2 ? bestTarget : null;
    }

    public static void runRetreat(RobotController rc) throws GameActionException{
        rc.setIndicatorString("RETREATING - paint: " + rc.getPaint());

        // Find nearest known tower
        MapLocation nearestTower = null;
        int bestDist = Integer.MAX_VALUE;
        for(MapLocation tower : knownTowers){
            int dist = rc.getLocation().distanceSquaredTo(tower);
            if(dist < bestDist){
                bestDist = dist;
                nearestTower = tower;
            }
        }

        if(nearestTower == null){
            // No towers known, go back to previous state
            state = preRetreatState != null ? preRetreatState : RobotState.EXPLORING;
            preRetreatState = null;
            return;
        }

        // If adjacent to tower, request paint refill
        if(rc.getLocation().isAdjacentTo(nearestTower)){
            // Try to transfer paint from tower (negative = take paint)
            int paintNeeded = rc.getType().paintCapacity - rc.getPaint();
            if(rc.canTransferPaint(nearestTower, -paintNeeded)){
                rc.transferPaint(nearestTower, -paintNeeded);
            }
            // Send a message to the tower
            if(rc.canSendMessage(nearestTower)){
                rc.sendMessage(nearestTower, MessageType.SAVE_CHIPS.ordinal());
            }
            // If paint restored above threshold, exit retreat
            if(rc.getPaint() > LOW_PAINT_THRESHOLD){
                state = preRetreatState != null ? preRetreatState : RobotState.EXPLORING;
                preRetreatState = null;
            }
        } else {
            // Navigate towards nearest tower
            bug2(rc, nearestTower);
        }

        updateFriendlyTowers(rc);
    }

    public static void runPaintPattern(RobotController rc) throws GameActionException{
        if(paintingTurns % 3 == 0){
            Direction toRuin = rc.getLocation().directionTo(paintingRuinLoc);
            Direction tangent = toRuin.rotateRight().rotateRight();
            int distance = rc.getLocation().distanceSquaredTo(paintingRuinLoc);
            if(distance > 4){
                tangent = tangent.rotateLeft();
            }

            if(rc.canMove(tangent)) rc.move(tangent);
        }

        if(rc.isActionReady()){
            MapInfo[] infos = rc.senseNearbyMapInfos(3);
            boolean attacked = false;
            for(MapInfo info: infos){
                MapLocation loc = info.getMapLocation();
                if(!isWithinPattern(paintingRuinLoc, loc)) continue;
                boolean isSecondary = getIsSecondary(paintingRuinLoc, loc, paintingRuinType);
                PaintType current = info.getPaint();
                // Paint if: empty, enemy paint, or wrong ally color
                boolean needsPaint = (current == PaintType.EMPTY)
                    || (current == PaintType.ENEMY_PRIMARY || current == PaintType.ENEMY_SECONDARY)
                    || (current.isAlly() && current.isSecondary() != isSecondary);
                if(needsPaint && rc.canAttack(loc)){
                    rc.attack(loc, isSecondary);
                    attacked = true;
                    turnsWithoutAttack = 0;
                    break;
                }
            }
            if(!attacked) turnsWithoutAttack++;
        }

        if (rc.canCompleteTowerPattern(paintingRuinType, paintingRuinLoc)) {
            rc.completeTowerPattern(paintingRuinType, paintingRuinLoc);
            state = RobotState.EXPLORING;
        }

        if(turnsWithoutAttack > 5){
            // Can't make progress here -- move on
            state = RobotState.EXPLORING;
            paintingRuinLoc = null;
        }

        
    }

    public static UnitType getNewTowerType(RobotController rc){
        if(rc.getNumberTowers() < 4){
            return UnitType.LEVEL_ONE_MONEY_TOWER;
        }
        return rc.getNumberTowers() % 2 == 1 ? UnitType.LEVEL_ONE_MONEY_TOWER: UnitType.LEVEL_ONE_PAINT_TOWER;
    }

    public static boolean getIsSecondary(MapLocation ruinLoc, MapLocation paintLoc, UnitType towerType){
        if(!isWithinPattern(ruinLoc, paintLoc)) return false;
        int col = paintLoc.x - ruinLoc.x + 2;
        int row = paintLoc.y - ruinLoc.y + 2;
        return towerType == UnitType.LEVEL_ONE_PAINT_TOWER ? paintTowerPattern[row][col] : moneyTowerPattern[row][col];
    }

    public static boolean isWithinPattern(MapLocation ruinLoc, MapLocation paintLoc){
        return Math.abs(paintLoc.x - ruinLoc.x) <= 2 && Math.abs(paintLoc.y - ruinLoc.y) <= 2 && !ruinLoc.equals(paintLoc);
    }

    // ========== SYMMETRY DETECTION ==========

    public static MapLocation mirrorHorizontal(RobotController rc, MapLocation loc){
        return new MapLocation(loc.x, rc.getMapHeight() - 1 - loc.y);
    }

    public static MapLocation mirrorVertical(RobotController rc, MapLocation loc){
        return new MapLocation(rc.getMapWidth() - 1 - loc.x, loc.y);
    }

    public static MapLocation mirrorRotational(RobotController rc, MapLocation loc){
        return new MapLocation(rc.getMapWidth() - 1 - loc.x, rc.getMapHeight() - 1 - loc.y);
    }

    public static void readSymmetryMessages(RobotController rc) throws GameActionException{
        Message[] messages = rc.readMessages(-1);
        for(Message m : messages){
            if(isSymMessage(m.getBytes())){
                decodeSymMessage(m.getBytes());
            }
        }
    }

    public static void sendSymmetryToTowers(RobotController rc) throws GameActionException{
        // If we've disproved any symmetry, tell nearby towers so they can broadcast
        if(symHorizontal && symVertical && symRotational) return; // nothing to share
        for(MapLocation tower : knownTowers){
            if(rc.canSendMessage(tower)){
                rc.sendMessage(tower, encodeSymMessage());
                break; // one tower is enough, it will broadcast
            }
        }
    }

    public static void updateSymmetryGuess(RobotController rc) throws GameActionException{
        // Read symmetry broadcasts from towers first
        readSymmetryMessages(rc);

        boolean oldH = symHorizontal, oldV = symVertical, oldR = symRotational;

        // Collect newly visible ruins for future reference
        MapLocation[] nearbyRuins = rc.senseNearbyRuins(-1);
        for(MapLocation ruin : nearbyRuins){
            if(!knownRuins.contains(ruin)){
                knownRuins.add(ruin);
            }
        }

        // For each known ruin/tower, check if the mirrored location is visible.
        // If visible and NO ruin exists there, that symmetry is disproven.
        ArrayList<MapLocation> landmarks = new ArrayList<>();
        landmarks.addAll(knownRuins);
        landmarks.addAll(knownTowers);

        for(MapLocation loc : landmarks){
            if(symHorizontal){
                MapLocation mirrored = mirrorHorizontal(rc, loc);
                if(rc.canSenseLocation(mirrored)){
                    MapInfo info = rc.senseMapInfo(mirrored);
                    if(!info.hasRuin() && rc.senseRobotAtLocation(mirrored) == null){
                        symHorizontal = false;
                    } else if(rc.senseRobotAtLocation(mirrored) != null && rc.senseRobotAtLocation(mirrored).getTeam() == rc.getTeam()){
                        // Our own tower at the mirror = same side, not enemy mirror
                        // Only disprove if this is NOT the same location
                        if(!mirrored.equals(loc)) symHorizontal = false;
                    }
                }
            }
            if(symVertical){
                MapLocation mirrored = mirrorVertical(rc, loc);
                if(rc.canSenseLocation(mirrored)){
                    MapInfo info = rc.senseMapInfo(mirrored);
                    if(!info.hasRuin() && rc.senseRobotAtLocation(mirrored) == null){
                        symVertical = false;
                    } else if(rc.senseRobotAtLocation(mirrored) != null && rc.senseRobotAtLocation(mirrored).getTeam() == rc.getTeam()){
                        if(!mirrored.equals(loc)) symVertical = false;
                    }
                }
            }
            if(symRotational){
                MapLocation mirrored = mirrorRotational(rc, loc);
                if(rc.canSenseLocation(mirrored)){
                    MapInfo info = rc.senseMapInfo(mirrored);
                    if(!info.hasRuin() && rc.senseRobotAtLocation(mirrored) == null){
                        symRotational = false;
                    } else if(rc.senseRobotAtLocation(mirrored) != null && rc.senseRobotAtLocation(mirrored).getTeam() == rc.getTeam()){
                        if(!mirrored.equals(loc)) symRotational = false;
                    }
                }
            }
        }

        // If we learned something new, tell a nearby tower
        if(oldH != symHorizontal || oldV != symVertical || oldR != symRotational){
            sendSymmetryToTowers(rc);
        }
    }

    public static MapLocation guessEnemyLocation(RobotController rc, MapLocation friendlyLoc){
        // Try each remaining possible symmetry; prefer the first valid one
        if(symRotational){
            return mirrorRotational(rc, friendlyLoc);
        } else if(symVertical){
            return mirrorVertical(rc, friendlyLoc);
        } else if(symHorizontal){
            return mirrorHorizontal(rc, friendlyLoc);
        }
        // Fallback: rotational (always a reasonable default)
        return mirrorRotational(rc, friendlyLoc);
    }

    // ========== PATHFINDING ==========

    public static void bug0(RobotController rc, MapLocation target) throws GameActionException{
        Direction dir = rc.getLocation().directionTo(target);
        MapLocation nextLocation = rc.getLocation().add(dir);
        rc.setIndicatorDot(nextLocation, 255, 0, 0);
        Clock.yield();

        if(rc.canMove(dir)){
            rc.move(dir);
        }
        else{
            for(int i = 0; i < 8; i++){
                dir = dir.rotateLeft();
                if(rc.canMove(dir)){
                    rc.move(dir);
                    break;
                }
            }
        }
    }

    public static void bug2(RobotController rc, MapLocation target) throws GameActionException{
        if(!target.equals(prevDest)){
            prevDest = target;
            line = createLine(target, rc.getLocation());
        }

        if(!isTracing){
            Direction dir = rc.getLocation().directionTo(target);
            if(rc.canMove(dir)){
                rc.move(dir);
            } else{
                isTracing = true;
                obstacleStartDist = rc.getLocation().distanceSquaredTo(target);
                tracingDir = dir;
            }
        } else{
            if(line.contains(rc.getLocation()) && rc.getLocation().distanceSquaredTo(target) < obstacleStartDist){
                isTracing = false;
            }

            if(rc.canMove(tracingDir)){
                rc.move(tracingDir);
                tracingDir = tracingDir.rotateLeft();
                tracingDir = tracingDir.rotateRight();
            }
            else{
                for(int i = 0; i<8; i++){
                    tracingDir = tracingDir.rotateLeft();
                    if(rc.canMove(tracingDir)){
                        rc.move(tracingDir);
                        tracingDir = tracingDir.rotateRight();
                        tracingDir = tracingDir.rotateRight();
                        break;
                    }
                }
            }
        }
    }

    public static HashSet<MapLocation> createLine(MapLocation a, MapLocation b){
        HashSet<MapLocation> locs = new HashSet<>();
        int x = a.x, y = a.y;
        int dx = b.x-a.x;
        int dy = b.y-a.y;
        int sx = (int) Math.signum(dx);
        int sy = (int) Math.signum(dy);
        dx = Math.abs(dx);
        dy = Math.abs(dy);
        int d = Math.max(dx, dy);
        int r = d/2;
        if(dx>dy){
            for(int i = 0; i < d; i++){
                locs.add(new MapLocation(x,y));
                x += sx;
                r += dy;
                if(r>=dx){
                    locs.add(new MapLocation(x, y));
                    y += sy;
                    r -= dx;
                }
            }
        }else{
            for(int i = 0; i < d; i++){
                locs.add(new MapLocation(x,y));
                y += sy;
                r += dx;
                if(r>=dy){
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