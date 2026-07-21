package common;

import com.google.gson.JsonObject;

/**
 * Maps a JSON response from Server to Client.
 */
public class JsonResponse{
    public String status; //"success" or "error"
    public int errorCode; //0 if success
    public String errorMessage; //Error message, or null
    public JsonObject data; //Optional dynamic payload

    public JsonResponse(String status, int errorCode, String errorMessage, JsonObject data){
        this.status = status;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.data = data;
    }
    
    public static JsonResponse success(JsonObject data){
        return new JsonResponse("success", 0, null, data);
    }
    
    public static JsonResponse error(int errorCode, String errorMessage){
        return new JsonResponse("error", errorCode, errorMessage, null);
    }
}
