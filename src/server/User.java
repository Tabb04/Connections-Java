package server;

import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;

public class User{
    private String username;
    private String password;

    //GLOBAL STATISTICS
    private int gamesPlayed = 0;
    private int gamesWon = 0;
    private int globalScore = 0;

    //Personal Statistics (NYT Connections style)
    private int puzzlesCompleted = 0;
    private int puzzlesWon = 0;
    private int puzzlesLost = 0;
    private int currentStreak = 0;
    private int maxStreak = 0;
    private int perfectPuzzles = 0;
    //Histogram: 0-3 mistakes (won), 4 mistakes (lost), 5 (unfinished due to timeout)
    private int[] mistakeHistogram = new int[6];

    //Historical match results for this user
    public static class GameResult{
        public int score;
        public int mistakes;
        public boolean won;

        public GameResult(int score, int mistakes, boolean won){
            this.score = score;
            this.mistakes = mistakes;
            this.won = won;
        }
    }

    private Map<Integer, GameResult> history = new HashMap<>();

    //Transient state (not saved to persistent JSON) for active game session
    private transient Integer currentGameId = -1;
    private transient Set<String> currentFoundGroups = new HashSet<>();
    private transient int currentMistakes = 0;
    private transient int currentScore = 0;
    private transient boolean gameFinished = false; //for this user

    public User(String username, String password){
        this.username = username;
        this.password = password;
    }

    public String getUsername(){
        return username;
    }

    public void setUsername(String username){
        this.username = username;
    }

    public String getPassword(){
        return password;
    }

    public void setPassword(String password){
        this.password = password;
    }

    public int getGamesPlayed(){
        return gamesPlayed;
    }

    public int getGamesWon(){
        return gamesWon;
    }

    public int getGlobalScore(){
        return globalScore;
    }

    //In-memory state management methods
    public synchronized void resetGameState(int gameId){
        //Before resetting, record current game results into global and personal stats
        if(this.currentGameId != null && this.currentGameId != -1){
            this.gamesPlayed++;
            this.globalScore += this.currentScore;
            boolean won = this.currentFoundGroups != null && this.currentFoundGroups.size() >= 3;

            //Save into user history for future queries (e.g., requestGameInfo)
            this.history.put(this.currentGameId, new GameResult(this.currentScore, this.currentMistakes, won));

            this.puzzlesCompleted++;

            if(won){
                this.gamesWon++;
                this.puzzlesWon++;
                this.currentStreak++;
                if(this.currentStreak > this.maxStreak){
                    this.maxStreak = this.currentStreak;
                }
                if(this.currentMistakes == 0){
                    this.perfectPuzzles++;
                }
                //Histogram update (won games with N mistakes)
                if(this.currentMistakes >= 0 && this.currentMistakes < 4){
                    this.mistakeHistogram[this.currentMistakes]++;
                }
            }else{
                this.currentStreak = 0; //Reset streak
                if(this.currentMistakes >= 4){
                    this.puzzlesLost++;
                    this.mistakeHistogram[4]++; //Lost games
                }else{
                    //Did not win and mistakes < 4 -> timer expired
                    this.mistakeHistogram[5]++; //Unfinished games
                }
            }
        }

        //Setup new game session
        this.currentGameId = gameId;
        if(this.currentFoundGroups == null){
            this.currentFoundGroups = new HashSet<>();
        }else{
            this.currentFoundGroups.clear();
        }
        this.currentMistakes = 0;
        this.currentScore = 0;
        this.gameFinished = false;
    }

    public Integer getCurrentGameId(){
        return currentGameId;
    }

    public Set<String> getCurrentFoundGroups(){
        return currentFoundGroups;
    }

    public int getCurrentMistakes(){
        return currentMistakes;
    }

    public int getCurrentScore(){
        return currentScore;
    }

    public boolean isGameFinished(){
        return gameFinished;
    }

    public void addMistake(){
        this.currentMistakes++;
        this.currentScore -= 4;
        if(this.currentMistakes >= 4){
            this.gameFinished = true;
        }
    }

    public void addFoundGroup(String theme){
        this.currentFoundGroups.add(theme);
        //Always adds +6 for a correct proposal
        this.currentScore += 6;

        //If 3 groups are found, game is won (4th group is implicit by elimination)
        if(this.currentFoundGroups.size() >= 3){
            this.gameFinished = true; //Won, last group is implicit
        }
    }

    public void setGameFinished(boolean finished){
        this.gameFinished = finished;
    }

    //Getters for statistics and history
    public Map<Integer, GameResult> getHistory(){
        if(history == null){
            history = new HashMap<>();
        }
        return history;
    }

    public int getPuzzlesCompleted(){
        return puzzlesCompleted;
    }

    public int getPuzzlesWon(){
        return puzzlesWon;
    }

    public int getPuzzlesLost(){
        return puzzlesLost;
    }

    public int getCurrentStreak(){
        return currentStreak;
    }

    public int getMaxStreak(){
        return maxStreak;
    }

    public int getPerfectPuzzles(){
        return perfectPuzzles;
    }

    public int[] getMistakeHistogram(){
        return mistakeHistogram;
    }
}
