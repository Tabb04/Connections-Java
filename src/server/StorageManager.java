package server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import server.User;
import server.GameManager.HistoricalGameStats;

import java.io.*;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Manages JSON persistence and loading of user data and historical game sessions.
 * Periodic saving operations are executed asynchronously in the background.
 */
public class StorageManager{
    private final ConcurrentHashMap<String, User> registeredUsers;
    private final String usersFilePath;
    private Map<Integer, HistoricalGameStats> pastGames;
    private final String gamesFilePath;
    private final ScheduledExecutorService scheduler;
    private final Gson gson;

    public StorageManager(ConcurrentHashMap<String, User> registeredUsers, String usersFilePath, String gamesFilePath){
        this.registeredUsers = registeredUsers;
        this.usersFilePath = usersFilePath;
        this.gamesFilePath = gamesFilePath;
        //Configure Gson with PrettyPrinting for human-readable JSON output
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
    }
    
    public void setPastGamesReference(Map<Integer, HistoricalGameStats> pastGames){
        this.pastGames = pastGames;
    }

    /**
     * Loads user accounts from disk when the server starts up.
     */
    public void loadUsers(){
        File file = new File(usersFilePath);
        if(!file.exists()){
            System.out.println("[STORAGE] No user file found (starting from scratch).");
            return;
        }

        try(FileReader reader = new FileReader(file)){
            Type type = new TypeToken<ConcurrentHashMap<String, User>>(){}.getType();
            ConcurrentHashMap<String, User> loaded = gson.fromJson(reader, type);
            if(loaded != null){
                registeredUsers.putAll(loaded);
                System.out.println("[STORAGE] Loaded " + loaded.size() + " registered users.");
            }
        }catch(IOException e){
            System.err.println("[STORAGE] Error loading users: " + e.getMessage());
        }
    }

    /**
     * Loads historical game statistics from disk.
     */
    public Map<Integer, HistoricalGameStats> loadPastGames(){
        File file = new File(gamesFilePath);
        if(!file.exists()){
            return new ConcurrentHashMap<>();
        }
        try(FileReader reader = new FileReader(file)){
            Type type = new TypeToken<ConcurrentHashMap<Integer, HistoricalGameStats>>(){}.getType();
            Map<Integer, HistoricalGameStats> loaded = gson.fromJson(reader, type);
            if(loaded != null){
                System.out.println("[STORAGE] Loaded " + loaded.size() + " historical game statistics.");
                return loaded;
            }
        }catch(IOException e){
            System.err.println("[STORAGE] Error loading games: " + e.getMessage());
        }
        return new ConcurrentHashMap<>();
    }

    /**
     * Starts a scheduled background task for periodic saving.
     */
    public void startPeriodicSave(int intervalMinutes){
        System.out.println("[STORAGE] Automatic save set for every " + intervalMinutes + " minutes.");
        scheduler.scheduleAtFixedRate(this::saveAll, intervalMinutes, intervalMinutes, TimeUnit.MINUTES);
    }
    
    public void saveAll(){
        saveUsers();
        savePastGames();
    }

    /**
     * Writes the registered users map to the JSON file.
     */
    public void saveUsers(){
        File file = new File(usersFilePath);
        file.getParentFile().mkdirs(); //Creates parent directory if it does not exist

        try(FileWriter writer = new FileWriter(file)){
            //Serializes registered users, ignoring transient active session fields
            gson.toJson(registeredUsers, writer);
            System.out.println("[STORAGE] Users saved successfully to disk.");
        }catch(IOException e){
            System.err.println("[STORAGE] Failed to save users: " + e.getMessage());
        }
    }
    
    /**
     * Writes historical game statistics to the JSON file.
     */
    public void savePastGames(){
        if (pastGames == null) return;
        File file = new File(gamesFilePath);
        file.getParentFile().mkdirs(); //Creates parent directory if it does not exist
        
        try(FileWriter writer = new FileWriter(file)){
            gson.toJson(pastGames, writer);
            System.out.println("[STORAGE] Historical games saved successfully to disk.");
        }catch(IOException e){
            System.err.println("[STORAGE] Failed to save games: " + e.getMessage());
        }
    }
    
    /**
     * Invoked during graceful server shutdown to perform final persistence save.
     */
    public void stop(){
        saveAll(); 
        scheduler.shutdown();
    }
}
