package server;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import common.JsonRequest;
import common.JsonResponse;
import common.Constants;
import server.User;
import server.Game;
import server.WordDataset;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

/**
 * Processes incoming client requests based on the 'operation' field.
 * Executes business logic and returns a consistent JsonResponse.
 */
public class CommandProcessor{

    public static JsonResponse process(JsonRequest req, SocketChannel channel, NioServer server){
        if(req == null || req.operation == null){
            return JsonResponse.error(Constants.ERR_INVALID_REQUEST, "Operation not specified.");
        }

        switch(req.operation){
            case Constants.OP_REGISTER:
                return handleRegister(req, server);
            case Constants.OP_LOGIN:
                return handleLogin(req, channel, server);
            case Constants.OP_LOGOUT:
                return handleLogout(channel, server);
            case Constants.OP_SUBMIT_PROPOSAL:
                return handleSubmitProposal(req, channel, server);
            case Constants.OP_UPDATE_CREDENTIALS:
                return handleUpdateCredentials(req, channel, server);
            case Constants.OP_REQUEST_GAME_INFO:
                return handleGameInfo(req, channel, server);
            case Constants.OP_REQUEST_GAME_STATS:
                return handleGameStats(req, channel, server);
            case Constants.OP_REQUEST_LEADERBOARD:
                return handleLeaderboard(req, channel, server);
            case Constants.OP_REQUEST_PLAYER_STATS:
                return handlePlayerStats(channel, server);
            default:
                return JsonResponse.error(Constants.ERR_INVALID_REQUEST, "Unknown operation: " + req.operation);
        }
    }

    private static JsonResponse handleRegister(JsonRequest req, NioServer server){
        if(req.name == null || req.psw == null || req.name.trim().isEmpty()){
            return JsonResponse.error(Constants.ERR_INVALID_REQUEST, "Missing username or password.");
        }

        String username = req.name.trim();

        if(username.equalsIgnoreCase("topPlayers")){
            return JsonResponse.error(Constants.ERR_INVALID_REQUEST, "Username not allowed.");
        }

        synchronized(server.getRegisteredUsers()){
            if(server.getRegisteredUsers().containsKey(username)){
                return JsonResponse.error(Constants.ERR_ALREADY_REGISTERED, "User " + username + " already exists.");
            }
            User newUser = new User(username, req.psw);
            server.getRegisteredUsers().put(username, newUser);
        }
        return JsonResponse.success(new JsonObject());
    }

    private static JsonResponse handleLogin(JsonRequest req, SocketChannel channel, NioServer server){
        if(req.username == null || req.psw == null){
            return JsonResponse.error(Constants.ERR_INVALID_REQUEST, "Missing username or password.");
        }

        User user = server.getRegisteredUsers().get(req.username);
        if(user == null || !user.getPassword().equals(req.psw)){
            return JsonResponse.error(Constants.ERR_AUTH_FAILED, "Invalid credentials.");
        }

        server.getActiveConnections().put(channel, user);
        System.out.println("[SERVER] User logged in: " + user.getUsername());

        if(req.udpPort != null){
            try{
                InetSocketAddress remoteAddress = (InetSocketAddress) channel.getRemoteAddress();
                InetSocketAddress udpAddress = new InetSocketAddress(remoteAddress.getAddress(), req.udpPort);
                server.getUserUdpAddresses().put(user.getUsername(), udpAddress);
            }catch(Exception e){
                System.err.println("[SERVER] Error getting client IP for UDP: " + e.getMessage());
            }
        }

        Game game = server.getGameManager().getCurrentGame();
        JsonObject data = new JsonObject();
        data.addProperty("message", "Login completed. Welcome " + user.getUsername());

        if(game != null){
            data.addProperty("gameId", game.getGameId());
            data.addProperty("remainingTimeSeconds", game.getRemainingTimeSeconds());
            Gson gson = new Gson();
            JsonArray wordsArray = gson.toJsonTree(game.getAllWords()).getAsJsonArray();
            data.add("words", wordsArray);

            if(user.getCurrentGameId() == null || game.getGameId() != user.getCurrentGameId()){
                user.resetGameState(game.getGameId());
            }

            //Additional fields for login (correct proposals, mistake count, score)
            data.add("correctProposals", gson.toJsonTree(user.getCurrentFoundGroups()));
            data.addProperty("mistakes", user.getCurrentMistakes());
            data.addProperty("score", user.getCurrentScore());
        }
        return JsonResponse.success(data);
    }

    private static JsonResponse handleLogout(SocketChannel channel, NioServer server){
        User user = server.getActiveConnections().remove(channel);
        if(user != null){
            server.getUserUdpAddresses().remove(user.getUsername());
            System.out.println("[SERVER] User logged out: " + user.getUsername());
            return JsonResponse.success(new JsonObject());
        }
        return JsonResponse.error(Constants.ERR_NOT_LOGGED_IN, "User not logged in.");
    }

    private static JsonResponse handleSubmitProposal(JsonRequest req, SocketChannel channel, NioServer server){
        User user = server.getActiveConnections().get(channel);
        if(user == null)
            return JsonResponse.error(Constants.ERR_NOT_LOGGED_IN, "User not logged in.");

        if(req.words == null || req.words.size() != 4){
            return JsonResponse.error(Constants.ERR_INVALID_PROPOSAL, "You must send exactly 4 words.");
        }
        Game game = server.getGameManager().getCurrentGame();
        if(game == null){
            return JsonResponse.error(Constants.ERR_GAME_NOT_FOUND, "No active game in progress.");
        }
        if(user.getCurrentGameId() == null || game.getGameId() != user.getCurrentGameId()){
            //Lazy auto-participation for active game session
            user.resetGameState(game.getGameId());
        }

        synchronized(user){
            if(user.isGameFinished()){
                return JsonResponse.error(Constants.ERR_INVALID_REQUEST, "You have already finished this game.");
            }

            //Malformed proposal validation (duplicate words, non-game words, already assigned)
            Set<String> uniqueWords = new HashSet<>(req.words);
            if(uniqueWords.size() != 4){
                return JsonResponse.error(Constants.ERR_INVALID_PROPOSAL,
                        "Malformed proposal: duplicate words or wrong number.");
            }
            List<String> allWords = game.getAllWords();
            for(String w : req.words){
                if(!allWords.contains(w)){
                    return JsonResponse.error(Constants.ERR_INVALID_PROPOSAL,
                            "Malformed proposal: the word " + w + " does not belong to the current game.");
                }
            }
            for(String theme : user.getCurrentFoundGroups()){
                for(WordDataset.Group g : game.getData().groups){
                    if(g.theme.equals(theme)){
                        for(String w : req.words){
                            if(g.words.contains(w)){
                                return JsonResponse.error(Constants.ERR_INVALID_PROPOSAL,
                                        "Malformed proposal: the word " + w
                                                + " has already been grouped correctly.");
                            }
                        }
                    }
                }
            }

            WordDataset.Group foundGroup = game.checkProposal(req.words);
            JsonObject data = new JsonObject();

            if(foundGroup != null){
                if(user.getCurrentFoundGroups().contains(foundGroup.theme)){
                    return JsonResponse.error(Constants.ERR_INVALID_PROPOSAL, "You have already guessed this group.");
                }
                user.addFoundGroup(foundGroup.theme);
                data.addProperty("result", "correct");
                data.addProperty("theme", foundGroup.theme);
            }else{
                user.addMistake();
                data.addProperty("result", "wrong");
            }

            data.addProperty("mistakes", user.getCurrentMistakes());
            data.addProperty("score", user.getCurrentScore());
            data.addProperty("gameFinished", user.isGameFinished());

            return JsonResponse.success(data);
        }
    }

    private static JsonResponse handleUpdateCredentials(JsonRequest req, SocketChannel channel, NioServer server){
        if(req.oldName == null || req.oldPsw == null){
            return JsonResponse.error(Constants.ERR_INVALID_REQUEST,
                    "You must enter your old credentials to update them.");
        }

        User user = server.getRegisteredUsers().get(req.oldName);
        if(user == null || !user.getPassword().equals(req.oldPsw)){
            return JsonResponse.error(Constants.ERR_AUTH_FAILED, "Old credentials wrong or user not found.");
        }

        boolean changed = false;

        //Password change
        if(req.newPsw != null && !req.newPsw.isEmpty() && !req.newPsw.equals("-")){
            user.setPassword(req.newPsw);
            changed = true;
        }

        //Username update (key in registeredUsers map)
        if(req.newName != null && !req.newName.isEmpty() && !req.newName.equals("-")){
            if(req.newName.trim().equalsIgnoreCase("topPlayers")){
                return JsonResponse.error(Constants.ERR_INVALID_REQUEST, "New username not allowed.");
            }
            synchronized(server.getRegisteredUsers()){
                if(server.getRegisteredUsers().containsKey(req.newName)){
                    return JsonResponse.error(Constants.ERR_INVALID_REQUEST, "New username already exists.");
                }
                //Remove old key and insert updated key
                server.getRegisteredUsers().remove(user.getUsername());
                user.setUsername(req.newName);
                server.getRegisteredUsers().put(req.newName, user);
                changed = true;
            }
        }

        if(changed){
            JsonObject data = new JsonObject();
            data.addProperty("message", "Credentials updated successfully.");
            return JsonResponse.success(data);
        }else{
            return JsonResponse.error(Constants.ERR_INVALID_REQUEST, "No parameters to update.");
        }
    }

    private static JsonResponse handleGameInfo(JsonRequest req, SocketChannel channel, NioServer server){

        User user = server.getActiveConnections().get(channel);
        if(user == null)
            return JsonResponse.error(Constants.ERR_NOT_LOGGED_IN, "User not logged in.");

        Game game = server.getGameManager().getCurrentGame();
        Gson gson = new Gson();
        JsonObject data = new JsonObject();

        int targetId = (req.gameId == null || req.gameId == -1) ? (game != null ? game.getGameId() : -1) : req.gameId;

        if(game != null && targetId == game.getGameId()){
            //Lazy auto-participation if user state is unaligned with active game
            if(user.getCurrentGameId() == null || game.getGameId() != user.getCurrentGameId()){
                user.resetGameState(game.getGameId());
            }

            //Active game session
            data.addProperty("gameId", game.getGameId());
            data.addProperty("remainingTimeSeconds", game.getRemainingTimeSeconds());
            data.add("words", gson.toJsonTree(game.getAllWords()).getAsJsonArray());
            data.add("correctProposals", gson.toJsonTree(user.getCurrentFoundGroups()));
            data.addProperty("mistakes", user.getCurrentMistakes());
            data.addProperty("score", user.getCurrentScore());
            return JsonResponse.success(data);
        }else{
            //Completed game session
            GameManager.HistoricalGameStats stats = server.getGameManager().getHistoricalStats(targetId);
            if(stats == null){
                return JsonResponse.error(Constants.ERR_GAME_NOT_FOUND,
                        "Game " + targetId + " does not exist or has not yet concluded.");
            }
            data.addProperty("gameId", stats.gameId);
            data.add("groups", gson.toJsonTree(stats.data.groups));

            //User match-specific result for target game
            User.GameResult result = user.getHistory().get(targetId);
            if(result != null){
                data.addProperty("myMistakes", result.mistakes);
                data.addProperty("myScore", result.score);
                data.addProperty("won", result.won);
            }else{
                data.addProperty("participated", false);
            }
            return JsonResponse.success(data);
        }
    }

    private static JsonResponse handleGameStats(JsonRequest req, SocketChannel channel, NioServer server){
        User user = server.getActiveConnections().get(channel);
        if(user == null)
            return JsonResponse.error(Constants.ERR_NOT_LOGGED_IN, "User not logged in.");

        Game game = server.getGameManager().getCurrentGame();
        JsonObject data = new JsonObject();
        int targetId = (req.gameId == null || req.gameId == -1) ? (game != null ? game.getGameId() : -1) : req.gameId;

        if(game != null && targetId == game.getGameId()){
            //Active game: remaining time, total players, finished count, won count
            int playing = 0, finished = 0, won = 0;
            for(User u : server.getRegisteredUsers().values()){
                if(u.getCurrentGameId() != null && u.getCurrentGameId() == game.getGameId()){
                    if(!u.isGameFinished())
                        playing++;
                    if(u.isGameFinished())
                        finished++;
                    if(u.getCurrentFoundGroups() != null && u.getCurrentFoundGroups().size() >= 3)
                        won++;
                }
            }
            data.addProperty("gameId", game.getGameId());
            data.addProperty("remainingTimeSeconds", game.getRemainingTimeSeconds());
            data.addProperty("playersPlaying", playing);
            data.addProperty("playersFinished", finished);
            data.addProperty("playersWon", won);
            return JsonResponse.success(data);
        }else{
            //Completed game
            GameManager.HistoricalGameStats stats = server.getGameManager().getHistoricalStats(targetId);
            if(stats == null){
                return JsonResponse.error(Constants.ERR_GAME_NOT_FOUND,
                        "Game " + targetId + " stats not available.");
            }
            data.addProperty("gameId", stats.gameId);
            data.addProperty("totalParticipants", stats.totalParticipants);
            data.addProperty("finishedParticipants", stats.finishedParticipants);
            data.addProperty("wonParticipants", stats.wonParticipants);
            data.addProperty("averageScore", stats.averageScore);
            return JsonResponse.success(data);
        }
    }

    private static JsonResponse handlePlayerStats(SocketChannel channel, NioServer server){
        User user = server.getActiveConnections().get(channel);
        if(user == null)
            return JsonResponse.error(Constants.ERR_NOT_LOGGED_IN, "User not logged in.");

        JsonObject data = new JsonObject();
        data.addProperty("username", user.getUsername());
        data.addProperty("puzzlesCompleted", user.getPuzzlesCompleted());
        double winRate = user.getPuzzlesCompleted() > 0
                ? (double) user.getPuzzlesWon() / user.getPuzzlesCompleted() * 100
                : 0;
        double lossRate = user.getPuzzlesCompleted() > 0
                ? (double) user.getPuzzlesLost() / user.getPuzzlesCompleted() * 100
                : 0;
        data.addProperty("winRate", String.format("%.2f%%", winRate));
        data.addProperty("lossRate", String.format("%.2f%%", lossRate));
        data.addProperty("currentStreak", user.getCurrentStreak());
        data.addProperty("maxStreak", user.getMaxStreak());
        data.addProperty("perfectPuzzles", user.getPerfectPuzzles());

        Gson gson = new Gson();
        data.add("mistakeHistogram", gson.toJsonTree(user.getMistakeHistogram()));

        return JsonResponse.success(data);
    }

    private static JsonResponse handleLeaderboard(JsonRequest req, SocketChannel channel, NioServer server){
        User user = server.getActiveConnections().get(channel);
        if(user == null)
            return JsonResponse.error(Constants.ERR_NOT_LOGGED_IN, "User not logged in.");

        //Sort users by global historical score
        List<User> userList = new ArrayList<>(server.getRegisteredUsers().values());
        userList.sort((u1, u2)->Integer.compare(u2.getGlobalScore(), u1.getGlobalScore()));

        JsonObject data = new JsonObject();

        if(req.playerName != null && !req.playerName.isEmpty()){
            //Search rank for specific player
            int rank = -1;
            int score = 0;
            for(int i = 0; i < userList.size(); i++){
                if(userList.get(i).getUsername().equals(req.playerName)){
                    rank = i + 1;
                    score = userList.get(i).getGlobalScore();
                    break;
                }
            }
            if(rank != -1){
                data.addProperty("playerName", req.playerName);
                data.addProperty("rank", rank);
                data.addProperty("score", score);
                return JsonResponse.success(data);
            }else{
                return JsonResponse.error(Constants.ERR_USER_NOT_FOUND, "Player not found.");
            }
        }else{
            //Return top K players
            int limit = (req.topPlayers != null && req.topPlayers > 0) ? req.topPlayers : userList.size();
            JsonArray leaderArray = new JsonArray();
            for(int i = 0; i < Math.min(limit, userList.size()); i++){
                User u = userList.get(i);
                JsonObject entry = new JsonObject();
                entry.addProperty("rank", i + 1);
                entry.addProperty("username", u.getUsername());
                entry.addProperty("globalScore", u.getGlobalScore());
                leaderArray.add(entry);
            }
            data.add("topPlayers", leaderArray);
            return JsonResponse.success(data);
        }
    }
}
