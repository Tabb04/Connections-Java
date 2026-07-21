package server;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import common.JsonRequest;
import common.JsonResponse;
import server.GameManager;
import server.User;

/**
 * Main NIO Server that accepts TCP connections and dispatches incoming requests
 * to a Thread Pool for processing. Also manages UDP notification broadcasts.
 */
public class NioServer{
    private final int port;
    private final ExecutorService workerPool; //Worker thread pool (CachedThreadPool) for handling requests in parallel
    private GameManager gameManager; //Game lifecycle manager
    private final Gson gson;
    private Selector selector;
    private ServerSocketChannel serverChannel;
    private DatagramSocket udpSocket;

    //Global map for registered users
    private final ConcurrentHashMap<String, User> registeredUsers = new ConcurrentHashMap<>();

    //Map linking active TCP connections (SocketChannel) to authenticated users
    private final ConcurrentHashMap<SocketChannel, User> activeConnections = new ConcurrentHashMap<>();

    //Map associating authenticated users with their UDP addresses (for async notifications)
    private final ConcurrentHashMap<String, InetSocketAddress> userUdpAddresses = new ConcurrentHashMap<>();

    public NioServer(int port){
        this.port = port;
        this.workerPool = Executors.newCachedThreadPool();
        this.gson = new Gson();
    }

    public void setGameManager(GameManager gameManager){
        this.gameManager = gameManager;
    }

    public GameManager getGameManager(){
        return this.gameManager;
    }

    public ConcurrentHashMap<String, User> getRegisteredUsers(){
        return this.registeredUsers;
    }

    public ConcurrentHashMap<SocketChannel, User> getActiveConnections(){
        return this.activeConnections;
    }

    public ConcurrentHashMap<String, InetSocketAddress> getUserUdpAddresses(){
        return this.userUdpAddresses;
    }

    /**
     * Starts the NIO server and opens listener port.
     */
    public void start() throws IOException{
        selector = Selector.open();

        //Configure server channel as non-blocking
        serverChannel = ServerSocketChannel.open();
        serverChannel.bind(new InetSocketAddress(port));
        serverChannel.configureBlocking(false);
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);

        //Open standard UDP socket for fire-and-forget notifications
        udpSocket = new DatagramSocket();

        System.out.println("[SERVER] Listening on TCP port " + port);

        //Main Selector loop
        while(!Thread.interrupted()){
            selector.select(); //Blocks until I/O events occur
            Set<SelectionKey> selectedKeys = selector.selectedKeys();
            Iterator<SelectionKey> iter = selectedKeys.iterator();

            while(iter.hasNext()){
                SelectionKey key = iter.next();
                iter.remove();

                if (!key.isValid())
                    continue;

                if(key.isAcceptable()){
                    acceptConnection(key); //New incoming TCP connection
                }else if(key.isReadable()){
                    readRequest(key); //Incoming data ready to read
                }
            }
        }
    }

    /**
     * Accepts new incoming TCP client connections.
     */
    private void acceptConnection(SelectionKey key) throws IOException{
        ServerSocketChannel ssc = (ServerSocketChannel) key.channel();
        SocketChannel clientChannel = ssc.accept();
        clientChannel.configureBlocking(false);
        //Registers buffer for JSON data per connection
        clientChannel.register(selector, SelectionKey.OP_READ, ByteBuffer.allocate(8192));
        System.out.println("[SERVER] New TCP connection from " + clientChannel.getRemoteAddress());
    }

    /**
     * Reads JSON request string from client channel and submits task to worker thread.
     */
    private void readRequest(SelectionKey key){
        SocketChannel clientChannel = (SocketChannel) key.channel();
        ByteBuffer buffer = (ByteBuffer) key.attachment();

        try{
            int bytesRead = clientChannel.read(buffer);
            if(bytesRead == -1){
                //Connection closed abruptly by client
                handleDisconnection(clientChannel);
                return;
            }

            buffer.flip();
            byte[] data = new byte[buffer.remaining()];
            buffer.get(data);
            String message = new String(data, StandardCharsets.UTF_8).trim();
            buffer.compact(); //Prepares buffer for subsequent reads

            if(!message.isEmpty()){
                //Submits processing task asynchronously to worker pool
                workerPool.submit(()->processMessage(clientChannel, message));
            }
        }catch(IOException e){
            handleDisconnection(clientChannel);
        }
    }

    /**
     * Cleans up user session mappings upon disconnection.
     */
    private void handleDisconnection(SocketChannel clientChannel){
        try{
            User u = activeConnections.remove(clientChannel);
            if(u != null){
                userUdpAddresses.remove(u.getUsername());
                System.out.println("[SERVER] User disconnected: " + u.getUsername());
            }else{
                System.out.println("[SERVER] Anonymous client disconnected");
            }
            clientChannel.close();
        }catch(IOException ex){
            ex.printStackTrace();
        }
    }

    /**
     * Executed by worker thread to process request and generate response.
     */
    private void processMessage(SocketChannel clientChannel, String message){
        try{
            //Parses incoming JSON request
            JsonRequest request = gson.fromJson(message, JsonRequest.class);

            //Dispatches logic to CommandProcessor
            JsonResponse response = CommandProcessor.process(request, clientChannel, this);

            //Sends response back to client
            sendResponse(clientChannel, response);

        }catch(Exception e){
            e.printStackTrace();
            JsonResponse err = JsonResponse.error(400, "Malformed request or server error.");
            sendResponse(clientChannel, err);
        }
    }

    /**
     * Synchronously writes JSON response to client TCP SocketChannel.
     */
    private void sendResponse(SocketChannel channel, JsonResponse response){
        try{
            //Appends \n delimiter to assist client message framing
            String jsonStr = gson.toJson(response) + "\n";
            ByteBuffer buffer = ByteBuffer.wrap(jsonStr.getBytes(StandardCharsets.UTF_8));
            while(buffer.hasRemaining()){
                channel.write(buffer);
            }
        }catch(IOException e){
            handleDisconnection(channel);
        }
    }

    /**
     * Broadcasts UDP packets to all authenticated clients notifying game conclusion.
     * Payload includes finished game stats and match leaderboard.
     */
    public void broadcastGameEnd(int gameId){
        JsonObject payload = new JsonObject();
        payload.addProperty("event", "GAME_ENDED");
        payload.addProperty("gameId", gameId);

        //Finished game stats and solution
        GameManager.HistoricalGameStats stats = gameManager.getHistoricalStats(gameId);
        if(stats != null){
            payload.addProperty("totalParticipants", stats.totalParticipants);
            payload.addProperty("finishedParticipants", stats.finishedParticipants);
            payload.addProperty("wonParticipants", stats.wonParticipants);
            payload.addProperty("averageScore", stats.averageScore);
            
            //Attach game solution groups
            if(stats.data != null && stats.data.groups != null){
                payload.add("groups", gson.toJsonTree(stats.data.groups));
            }
        }

        //Match specific leaderboard (matchLeaderboard)
        List<User> matchParticipants = new ArrayList<>();
        for(User u : registeredUsers.values()){
            if(u.getHistory().get(gameId) != null){
                matchParticipants.add(u);
            }
        }
        
        //Sort participants by score obtained IN THIS MATCH (descending)
        matchParticipants.sort((u1, u2)->Integer.compare(u2.getHistory().get(gameId).score, u1.getHistory().get(gameId).score));
        
        JsonArray matchLeaderArray = new JsonArray();
        for(int i = 0; i < matchParticipants.size(); i++){
            User u = matchParticipants.get(i);
            JsonObject entry = new JsonObject();
            entry.addProperty("rank", i + 1);
            entry.addProperty("username", u.getUsername());
            entry.addProperty("score", u.getHistory().get(gameId).score);
            matchLeaderArray.add(entry);
        }
        payload.add("matchLeaderboard", matchLeaderArray);

        //Iterate over active users and customize payload
        for(Map.Entry<String, InetSocketAddress> entry : userUdpAddresses.entrySet()){
            String username = entry.getKey();
            InetSocketAddress address = entry.getValue();
            User user = registeredUsers.get(username);
            
            JsonObject userPayload = payload.deepCopy();
            
            if(user != null){
                User.GameResult result = user.getHistory().get(gameId);
                if(result != null){
                    userPayload.addProperty("participated", true);
                    userPayload.addProperty("myMistakes", result.mistakes);
                    userPayload.addProperty("myScore", result.score);
                    userPayload.addProperty("won", result.won);
                }else{
                    userPayload.addProperty("participated", false);
                }
            }
            
            byte[] buffer = gson.toJson(userPayload).getBytes(StandardCharsets.UTF_8);
            
            try{
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length, address);
                System.out.println("[SERVER] Sending UDP to " + address + " length " + buffer.length + " bytes");
                udpSocket.send(packet);
            }catch(IOException e){
                System.err.println("[SERVER] Error UDP to " + address);
            }
        }
    }

    /**
     * Broadcasts TCP message to all connected clients with new game details and words,
     * automatically resetting user state for the new session.
     */
    public void broadcastNewGameStart(server.Game newGame){
        //Auto-register connected users for internal state consistency
        for(Map.Entry<SocketChannel, User> entry : activeConnections.entrySet()){
            User user = entry.getValue();
            user.resetGameState(newGame.getGameId());
        }

        //Send TCP broadcast in a background thread after a short delay
        new Thread(()->{
            try{
                Thread.sleep(1000);
            }catch(InterruptedException e){
                Thread.currentThread().interrupt();
            }

            JsonObject baseData = new JsonObject();
            baseData.addProperty("event", "NEW_GAME_STARTED");
            baseData.addProperty("message", "New game started!");
            baseData.addProperty("gameId", newGame.getGameId());
            //Update remaining time
            baseData.addProperty("remainingTimeSeconds", newGame.getRemainingTimeSeconds());
            
            JsonArray wordsArray = gson.toJsonTree(newGame.getAllWords()).getAsJsonArray();
            baseData.add("words", wordsArray);
            
            for(Map.Entry<SocketChannel, User> entry : activeConnections.entrySet()){
                SocketChannel channel = entry.getKey();
                User user = entry.getValue();
                
                //Create cloned payload with user-specific state
                JsonObject userData = baseData.deepCopy();
                userData.add("correctProposals", gson.toJsonTree(user.getCurrentFoundGroups()));
                userData.addProperty("mistakes", user.getCurrentMistakes());
                userData.addProperty("score", user.getCurrentScore());
                
                //TCP send
                sendResponse(channel, JsonResponse.success(userData));
            }
        }).start();
    }
}
