package common;

/**
 * Constants for operations and error codes.
 */
public class Constants{
    //Operations
    public static final String OP_REGISTER = "register";
    public static final String OP_UPDATE_CREDENTIALS = "updateCredentials";
    public static final String OP_LOGIN = "login";
    public static final String OP_LOGOUT = "logout";
    public static final String OP_SUBMIT_PROPOSAL = "submitProposal";
    public static final String OP_REQUEST_GAME_INFO = "requestGameInfo";
    public static final String OP_REQUEST_GAME_STATS = "requestGameStats";
    public static final String OP_REQUEST_LEADERBOARD = "requestLeaderboard";
    public static final String OP_REQUEST_PLAYER_STATS = "requestPlayerStats";
    
    //Error Codes
    public static final int ERR_INVALID_REQUEST = 100;
    public static final int ERR_ALREADY_REGISTERED = 101;
    public static final int ERR_AUTH_FAILED = 102;
    public static final int ERR_NOT_LOGGED_IN = 103;
    public static final int ERR_USER_NOT_FOUND = 104;
    public static final int ERR_GAME_NOT_FOUND = 105;
    public static final int ERR_INVALID_PROPOSAL = 106;
}
