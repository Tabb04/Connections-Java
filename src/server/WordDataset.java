package server;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.FileReader;
import java.lang.reflect.Type;
import java.util.List;

public class WordDataset{
    public static class Group{
        public String theme;
        public List<String> words;
    }

    public static class GameData{
        public int gameId;
        public List<Group> groups;
    }

    public static int countGames(String filePath){
        int count = 0;
        try(com.google.gson.stream.JsonReader reader = new com.google.gson.stream.JsonReader(new FileReader(filePath))){
            reader.beginArray();
            while(reader.hasNext()){
                reader.skipValue();
                count++;
            }
            reader.endArray();
        }catch(Exception e){
            System.err.println("Errore durante il conteggio dei game: " + e.getMessage());
        }
        return count;
    }

    public static GameData loadGameAtIndex(String filePath, int targetIndex){
        try(com.google.gson.stream.JsonReader reader = new com.google.gson.stream.JsonReader(new FileReader(filePath))){
            Gson gson = new Gson();
            reader.beginArray();
            int currentIndex = 0;
            while(reader.hasNext()){
                if(currentIndex == targetIndex){
                    return gson.fromJson(reader, GameData.class);
                }else{
                    reader.skipValue();
                    currentIndex++;
                }
            }
            reader.endArray();
        }catch(Exception e){
            System.err.println("Errore nel caricamento del gioco on-demand: " + e.getMessage());
        }
        return null;
    }
}
