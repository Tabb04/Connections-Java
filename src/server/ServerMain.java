package server;

import common.ConfigReader;
import server.GameManager;
import server.WordDataset;
import server.NioServer;
import server.StorageManager;

import java.util.List;

/**
 * Main entry point for the Server application.
 */
public class ServerMain{
    public static void main(String[] args){
        System.out.println("Starting Server Connections...");
        
        //1. Load configuration settings
        ConfigReader config = new ConfigReader("config/server.properties");
        int port = config.getIntProperty("server.port", 8080);
        String datasetPath = config.getProperty("server.words.dataset.path", "data/words.json");
        long timerSeconds = config.getIntProperty("server.timer.seconds", 300); // default 5 minutes
        
        //2. The dataset will be streamed by GameManager using the path
        System.out.println("Dataset configured: " + datasetPath);
        
        //3. Initialize Network (NIO Server)
        NioServer server = new NioServer(port);
        
        //4. Initialize Game Manager (GameManager)
        GameManager gameManager = new GameManager(datasetPath, timerSeconds, server);
        server.setGameManager(gameManager);
        
        //5. Persistence: load previous users and start background periodic saving task
        String dataDir = config.getProperty("server.data.dir", "data");
        int persistInterval = config.getIntProperty("server.persistence.interval.minutes", 5);
        StorageManager storage = new StorageManager(server.getRegisteredUsers(), dataDir + "/users.json", dataDir + "/games.json");
        storage.loadUsers();
        
        java.util.Map<Integer, GameManager.HistoricalGameStats> loadedGames = storage.loadPastGames();
        gameManager.loadPastGames(loadedGames);
        storage.setPastGamesReference(gameManager.getPastGamesMap());
        
        storage.startPeriodicSave(persistInterval);
        
        //Add a Shutdown Hook to intercept CTRL+C (SIGINT) and gracefully save data
        Runtime.getRuntime().addShutdownHook(new Thread(()->{
            System.out.println("\n[SERVER] Shutdown detected. Emergency data save in progress...");
            storage.stop();
        }));
        
        //6. Start GameManager (first game timer)
        gameManager.start();
        
        //7. Start NIO Server (Blocking loop listening for incoming TCP requests)
        try{
            server.start();
        }catch(Exception e){
            e.printStackTrace();
            System.exit(1);
        }
    }
}
