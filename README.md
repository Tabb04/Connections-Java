# Connections: Multithreaded Java Client-Server Game

A high-performance, real-time multiplayer word association game inspired by *The New York Times Connections*. Built with **Java NIO (Non-blocking I/O)**, dual TCP/UDP networking channels, multithreaded worker pools, and JSON data persistence.

---

##  Overview

**Connections** is a centralized client-server application where a global, timed game session is shared simultaneously across all connected players. In each session, players are presented with a grid of 16 shuffled words and must organize them into 4 distinct thematic groups of 4 words each, with a maximum allowance of 4 mistakes per game.

The system is designed for high concurrency, low latency, and low memory overhead:
- **Java NIO Selector Loop** for non-blocking TCP network event handling.
- **Multithreaded Worker Pool** for concurrent request processing.
- **Asynchronous UDP Datagram Channel** for real-time game-end broadcasts and live match leaderboards.
- **Streaming JSON Parser** for loading massive game datasets on-demand with $O(1)$ memory complexity.
- **Background Persistence Engine** with JVM shutdown hooks.

---

##  System Architecture

### 1. Dual-Channel Server Architecture (TCP + UDP Broadcast)
The server operates a non-blocking network core anchored by a single **NIO Selector thread**:
- **TCP Control Channel (`ServerSocketChannel`)**: Bound to a configurable port (default `8080`), accepting non-blocking client connections. When data arrives (`SelectionKey.OP_READ`), the selector reads the raw bytes into a per-connection `ByteBuffer` accumulator.
- **Request Offloading to Worker Pool**: To prevent the Selector thread from stalling during heavy computations or IO, incoming JSON requests are immediately offloaded to a multithreaded `CachedThreadPool` (`ExecutorService`). Worker threads deserialize requests, execute business logic in `CommandProcessor`, and send responses back over TCP.
- **UDP Notification Channel (`DatagramSocket`)**: Used for asynchronous broadcast events (such as game completion). Since UDP notifications are fire-and-forget, they bypass Selector management for minimal overhead.

### 2. Dual-Thread Client Architecture
CLI applications typically face input thread blocking on `System.in`. To deliver real-time notifications without interrupting active user input, the client uses two decoupled threads:
- **Main Console Thread (`ClientMain`)**: Handles user interaction via CLI, reads commands from `System.in`, performs client-side lexical validation, sends JSON requests over TCP, and immediately returns to awaiting input.
- **Background Network Selector Thread (`NioClient`)**: Runs an asynchronous NIO Selector monitoring two non-blocking channels simultaneously:
  - `tcpChannel` (`SocketChannel`) for command responses.
  - `udpChannel` (`DatagramChannel`) for incoming broadcast notifications.
  Upon receiving network events, it renders formatted output directly to the console and refreshes the user prompt (`> `).

### 3. Dynamic Ephemeral UDP Port Registration
To ensure reliable UDP notifications through NATs and dynamic environments without manual port configuration:
1. At startup, the client binds its `DatagramChannel` to port `0`, prompting the OS to allocate a free ephemeral UDP port.
2. During `login`, the client transmits its ephemeral UDP port within the TCP authentication payload.
3. The server pairs the client's TCP remote IP with the provided UDP port, dynamically mapping active users in `userUdpAddresses` for targeted UDP broadcasts.

---

##  Game Logic & Dataset Optimization

### Streaming Dataset Processing (`WordDataset`)
To handle arbitrarily large game datasets (`words.json`) without risk of `OutOfMemoryError` or high heap consumption:
- **`countGames`**: Scans the JSON dataset sequentially token-by-token using Gson's low-level `JsonReader` streaming API. Unused nodes are skipped via `reader.skipValue()`, calculating the total game count in $O(1)$ memory.
- **`loadGameAtIndex`**: When starting a new session, the server opens a stream, skips directly to the targeted index, and deserializes **only that specific game entry**. Memory footprint remains minimal and constant regardless of dataset size.

### Game Lifecycle & Thread Safety (`GameManager` & `Game`)
- **Atomic Active Game Reference**: The active session is stored in an `AtomicReference<Game>`. Worker threads query `getCurrentGame()` concurrently without locking, while the timer thread rotates games atomically.
- **Scheduled Session Rotation**: A single-threaded `ScheduledExecutorService` manages session timers. Upon expiration, it flushes transient scores into historical statistics, selects a new unplayed game, updates `currentGame`, and triggers UDP broadcasts.
- **Transient State Isolation**: Active game states (`currentScore`, `currentMistakes`, `currentFoundGroups`) in `User` are marked `transient`. When periodic JSON persistence runs, transient active game fields are automatically omitted, protecting disk state against corruption if an unexpected shutdown occurs.

### Persistence & Fault Tolerance (`StorageManager`)
- **Periodic Background Saving**: An asynchronous background task periodically flushes registered users and historical game statistics to `data/users.json` and `data/games.json`.
- **JVM Shutdown Hook**: `ServerMain` registers a shutdown hook intercepting `SIGINT` / `CTRL+C`. On termination, the JVM executes a final synchronous data save before exiting safely.

---

##  Project Structure

```
Progetto/
├── config/
│   ├── server.properties      # Server configuration (port, timers, paths)
│   └── client.properties      # Client configuration (server IP, port)
├── data/
│   ├── words.json             # Game dataset
│   ├── users.json             # Registered user accounts & statistics
│   └── games.json             # Historical game stats
├── lib/
│   └── gson-2.10.1.jar        # Gson JSON library
├── src/
│   ├── client/
│   │   ├── ClientMain.java    # CLI entry point & user input thread
│   │   └── NioClient.java     # Non-blocking network selector thread
│   ├── common/
│   │   ├── ConfigReader.java  # Properties reader utility
│   │   ├── Constants.java     # Command names & error codes
│   │   ├── JsonRequest.java   # Client-to-server request DTO
│   │   └── JsonResponse.java  # Server-to-client response DTO
│   └── server/
│       ├── ServerMain.java    # Server startup & lifecycle initialization
│       ├── NioServer.java     # NIO TCP selector & UDP broadcast server
│       ├── CommandProcessor.java # Request router & business logic controller
│       ├── GameManager.java   # Game lifecycle & timer management
│       ├── Game.java          # Immutable active game model
│       ├── WordDataset.java   # Streaming JSON dataset parser
│       ├── StorageManager.java# Background JSON persistence manager
│       └── User.java          # User entity, stats & transient state
├── compile.sh                 # Compilation script
└── README.md
```

---

##  CLI Commands & Operations

Players interact with the server via the command-line interface:

| Command | Description |
| :--- | :--- |
| `register <username> <password>` | Register a new user account. |
| `login <username> <password>` | Authenticate user and join the current global game session. |
| `logout` | End session and disconnect user. |
| `submitProposal <w1> <w2> <w3> <w4>` | Submit a group of 4 words for verification. |
| `requestGameInfo [gameId]` | View details of current game or a completed historical game. |
| `requestGameStats [gameId]` | View participation and score statistics for a game session. |
| `requestPlayerStats` | View personal NYT-style statistics (win rate, streaks, mistake histogram). |
| `requestLeaderboard [topK\|username]` | View global leaderboard rankings or query specific player rank. |
| `updateCredentials <oldUser> <oldPass> <newUser\|-> <newPass\|->` | Update username or password. |

---

##  Build and Execution Instructions

### Prerequisites
- **Java Development Kit (JDK 8+)** installed.
- `lib/gson-2.10.1.jar` present in the project directory.

### 1. Compilation
Run the included build script from the project root directory:
```bash
./compile.sh
```
This script compiles all Java source files into `bin/` and creates executable JARs: `Server.jar` and `Client.jar`.

Alternatively, compile manually:
```bash
mkdir -p bin
javac -d bin -cp "lib/*" src/common/*.java src/server/*.java src/client/*.java
jar cfe Server.jar server.ServerMain -C bin/ .
jar cfe Client.jar client.ClientMain -C bin/ .
```

### 2. Running the Server
Launch the server using the compiled JAR:
```bash
java -cp "Server.jar:lib/*" server.ServerMain
```

### 3. Running the Client
Launch one or more client instances in separate terminal windows:
```bash
java -cp "Client.jar:lib/*" client.ClientMain
```
