package client;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import java.io.IOException;
import java.util.List;
import java.util.ArrayList;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import common.JsonRequest;

/**
 * Manages client-side networking (TCP and UDP).
 * Uses a Java NIO Selector to listen for incoming messages in the background.
 */
public class NioClient implements Runnable{
    private final String serverAddress;
    private final int serverPort;
    private final Gson gson;

    private SocketChannel tcpChannel;
    private DatagramChannel udpChannel;
    private Selector selector;
    private boolean running = true;

    public NioClient(String serverAddress, int serverPort){
        this.serverAddress = serverAddress;
        this.serverPort = serverPort;
        this.gson = new Gson();
    }

    /**
     * Opens network connections and initializes the selector.
     */
    public void connect() throws IOException{
        selector = Selector.open();

        //TCP connection to server
        tcpChannel = SocketChannel.open(new InetSocketAddress(serverAddress, serverPort));
        tcpChannel.configureBlocking(false);
        //Buffer for incoming messages (synchronous TCP)
        tcpChannel.register(selector, SelectionKey.OP_READ, ByteBuffer.allocate(8192));

        //UDP channel for receiving asynchronous broadcasts (game over events)
        udpChannel = DatagramChannel.open();
        udpChannel.configureBlocking(false);
        udpChannel.bind(new InetSocketAddress(0)); //Ephemeral port
        udpChannel.register(selector, SelectionKey.OP_READ, ByteBuffer.allocate(4096));
    }

    /**
     * Returns the local UDP port used by the client to register it with the server.
     */
    public int getUdpPort(){
        try{
            return ((InetSocketAddress) udpChannel.getLocalAddress()).getPort();
        }catch(IOException e){
            return -1;
        }
    }

    /**
     * Serializes the request to JSON and sends it over TCP.
     */
    public void sendRequest(JsonRequest request){
        try{
            String jsonStr = gson.toJson(request) + "\n";
            ByteBuffer buffer = ByteBuffer.wrap(jsonStr.getBytes(StandardCharsets.UTF_8));
            while(buffer.hasRemaining()){
                tcpChannel.write(buffer);
            }
        }catch(IOException e){
            System.err.println("TCP connection error.");
            running = false;
        }
    }

    /**
     * Background thread that reads incoming network data without blocking the CLI console.
     */
    @Override
    public void run(){
        try{
            while(running && !Thread.interrupted()){
                selector.select(); //Wait for I/O events
                Iterator<SelectionKey> keys = selector.selectedKeys().iterator();

                while(keys.hasNext()){
                    SelectionKey key = keys.next();
                    keys.remove();

                    if (!key.isValid())
                        continue;

                    if(key.isReadable()){
                        if(key.channel() == tcpChannel){
                            readTcp(key); //Data from server in response to a command
                        }else if(key.channel() == udpChannel){
                            readUdp(key); //Asynchronous broadcast notification (e.g. game over)
                        }
                    }
                }
            }
        }catch(IOException e){
            if (running)
                System.err.println("NioClient network error.");
        }
    }

    /**
     * Reads the TCP response and prints it to the terminal.
     */
    private void readTcp(SelectionKey key) throws IOException{
        SocketChannel channel = (SocketChannel) key.channel();
        ByteBuffer buffer = (ByteBuffer) key.attachment();

        int bytesRead = channel.read(buffer);
        if(bytesRead == -1){
            System.err.println("\nServer connection closed.");
            running = false;
            System.exit(1);
            return;
        }

        buffer.flip();
        byte[] data = new byte[buffer.remaining()];
        buffer.get(data);
        String msg = new String(data, StandardCharsets.UTF_8).trim();
        buffer.compact();

        if(!msg.isEmpty()){
            System.out.println("\n[SERVER] -> " + msg);
            System.out.print("> "); //Restores CLI prompt
        }
    }

    /**
     * Reads incoming UDP broadcast messages.
     */
    private void readUdp(SelectionKey key) throws IOException{
        DatagramChannel channel = (DatagramChannel) key.channel();
        ByteBuffer buffer = (ByteBuffer) key.attachment();

        buffer.clear();
        channel.receive(buffer);
        buffer.flip();

        byte[] data = new byte[buffer.remaining()];
        buffer.get(data);
        String msg = new String(data, StandardCharsets.UTF_8).trim();

        //Print broadcast message explicitly
        System.out.println("\n[BROADCAST UDP] -> " + msg);

        System.out.print("> ");
    }
}
