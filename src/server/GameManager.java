package server;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

import server.NioServer;

/**
 * Manages the lifecycle of Connections game sessions.
 * Responsible for loading datasets, executing timers, rotating game sessions
 * upon time expiration, and tracking historical game statistics.
 */
public class GameManager{
    private final String datasetFilePath; //JSON dataset file path
    private int totalGamesCount = 0;
    private final AtomicReference<Game> currentGame = new AtomicReference<>(); //Thread-safe atomic reference to active game
    private final ScheduledExecutorService scheduler; //Scheduler for game timer
    private final long gameDurationSeconds; //Duration of a single game session
    private final NioServer server; //Server reference to broadcast notifications to clients (UDP)
    private final java.util.Random random = new java.util.Random();
    
    //Historical statistics for past games
    public static class HistoricalGameStats{
        public int gameId;
        public WordDataset.GameData data;
        public int totalParticipants;
        public int finishedParticipants;
        public int wonParticipants;
        public double averageScore;
    }
    private final Map<Integer, HistoricalGameStats> pastGames = new ConcurrentHashMap<>();

    public GameManager(String datasetFilePath, long durationSeconds, NioServer server){
        this.datasetFilePath = datasetFilePath;
        this.gameDurationSeconds = durationSeconds;
        this.server = server;
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
    }

    /**
     * Starts the initial game session and sets up the recurring timer.
     */
    public void start(){
        this.totalGamesCount = WordDataset.countGames(datasetFilePath);
        System.out.println("[GAMEMANAGER] Found " + totalGamesCount + " games in file " + datasetFilePath);
        if(totalGamesCount <= 0){
            System.err.println("Empty or non-existent dataset, impossible to start GameManager.");
            return;
        }
        startNextGame();
    }

    /**
     * Concludes the current game, computes stats, broadcasts notifications, and starts the next session.
     */
    private void startNextGame(){
        Game current = getCurrentGame();
        if(current != null){
            //Collect statistics for the finished game session
            HistoricalGameStats stats = new HistoricalGameStats();
            stats.gameId = current.getGameId();
            stats.data = current.getData();
            int totalScore = 0;
            
            //Before changing games, flush transient scores into historical statistics for all connected users
            for(server.User user : server.getRegisteredUsers().values()){
                if(user.getCurrentGameId() != null && user.getCurrentGameId() == current.getGameId()){
                    stats.totalParticipants++;
                    if(user.isGameFinished()){
                        stats.finishedParticipants++;
                    }
                    if(user.getCurrentFoundGroups() != null && user.getCurrentFoundGroups().size() >= 3){
                        stats.wonParticipants++;
                    }
                    totalScore += user.getCurrentScore();
                    
                    user.resetGameState(-1); //Flush transient score and reset transient state
                }
            }
            if(stats.totalParticipants > 0){
                stats.averageScore = (double) totalScore / stats.totalParticipants;
            }
            pastGames.put(stats.gameId, stats);
        }

        //Select a new random game session on-demand via streaming
        WordDataset.GameData data = null;
        int retries = 0;
        
        while(data == null && retries < 10){
            int targetIndex = random.nextInt(totalGamesCount);
            WordDataset.GameData candidate = WordDataset.loadGameAtIndex(datasetFilePath, targetIndex);
            
            if(candidate != null){
                //If not played yet or if fallback retries exceeded
                if(!pastGames.containsKey(candidate.gameId) || retries == 9){
                    data = candidate;
                }
            }
            retries++;
        }
        
        //Fallback to the first dataset entry if selection fails
        if(data == null){
            data = WordDataset.loadGameAtIndex(datasetFilePath, 0);
        }
        
        //Create new Game instance and set it atomically
        Game newGame = new Game(data, gameDurationSeconds);
        currentGame.set(newGame);
        
        System.out.println("[GAMEMANAGER] New random game started: ID " + newGame.getGameId() + ". Duration: " + gameDurationSeconds + "s");

        //Automatic registration of active users and proactive broadcast of words and state
        if(server != null){
            server.broadcastNewGameStart(newGame);
        }

        //Schedule game termination when timer expires
        scheduler.schedule(()->{
            int endedGameId = currentGame.get().getGameId();
            System.out.println("[GAMEMANAGER] Times up for game ID " + endedGameId);
            //Start next game first to flush past game stats
            startNextGame();
            //Broadcast end-of-game event to clients
            if(server != null){
                try{ server.broadcastGameEnd(endedGameId); }catch(Exception e){ e.printStackTrace(); }
            }
        }, gameDurationSeconds, TimeUnit.SECONDS);
    }

    /**
     * Returns the currently active game session.
     * @return active Game
     */
    public Game getCurrentGame(){
        return currentGame.get();
    }
    
    /**
     * Returns historical statistics for a completed game.
     * @param gameId Game ID
     * @return HistoricalGameStats or null if not found
     */
    public HistoricalGameStats getHistoricalStats(int gameId){
        return pastGames.get(gameId);
    }
    
    public Map<Integer, HistoricalGameStats> getPastGamesMap(){
        return this.pastGames;
    }

    public void loadPastGames(Map<Integer, HistoricalGameStats> loadedGames){
        if(loadedGames != null && !loadedGames.isEmpty()){
            this.pastGames.putAll(loadedGames);
            System.out.println("[GAMEMANAGER] Loaded " + loadedGames.size() + " historical games. The next random games will try to avoid them as much as possible.");
        }
    }
}
