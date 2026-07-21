package server;

import java.util.*;

/**
 * Represents the state of an active global Connections game session.
 */
public class Game{
    private final int gameId;
    private final WordDataset.GameData data;
    private final long startTimeMillis;
    private final long durationMillis;
    private final List<String> allWords; 
    
    public Game(WordDataset.GameData data, long durationSeconds){
        this.gameId = data.gameId;
        this.data = data;
        this.startTimeMillis = System.currentTimeMillis();
        this.durationMillis = durationSeconds * 1000;
        
        List<String> words = new ArrayList<>();
        for(WordDataset.Group g : data.groups){
            words.addAll(g.words);
        }
        Collections.shuffle(words, new Random());
        this.allWords = Collections.unmodifiableList(words);
    }
    
    public int getGameId(){ return gameId; }
    
    public List<String> getAllWords(){ return allWords; }
    
    public long getRemainingTimeSeconds(){
        long elapsed = System.currentTimeMillis() - startTimeMillis;
        long remaining = durationMillis - elapsed;
        return remaining > 0 ? remaining / 1000 : 0;
    }
    
    public WordDataset.GameData getData(){ return data; }
    
    public WordDataset.Group checkProposal(List<String> proposal){
        if(proposal == null || proposal.size() != 4) return null;
        for(WordDataset.Group g : data.groups){
            if(new HashSet<>(g.words).equals(new HashSet<>(proposal))){
                return g;
            }
        }
        return null;
    }
}
