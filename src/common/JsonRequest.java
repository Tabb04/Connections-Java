package common;

import java.util.List;

/**
 * Maps a JSON request from Client to Server.
 * Contains all potential fields for simplicity (null if unused in a specific operation).
 */
public class JsonRequest{
    public String operation;
    
    //User credentials
    public String name;
    public String psw;
    public String username;
    
    //For submitProposal
    public List<String> words;
    
    //For updateCredentials
    public String oldName;
    public String newName;
    public String oldPsw;
    public String newPsw;
    
    //For info and stats
    public Integer gameId;
    
    //For leaderboard
    public String playerName;
    public Integer topPlayers;
    
    //Additional field to exchange client UDP port
    public Integer udpPort;
    
    public JsonRequest(String operation){
        this.operation = operation;
    }
}
