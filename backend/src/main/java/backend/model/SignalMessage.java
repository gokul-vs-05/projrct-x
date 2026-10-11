package backend.model;

public class SignalMessage {
    private String sessionId;
    private String senderId;
    private String type;
    private String payload;

    public SignalMessage() {}
    public String getSessionId(){ return sessionId; }
    public void setSessionId(String v){ sessionId=v; }
    public String getSenderId(){ return senderId; }
    public void setSenderId(String v){ senderId=v; }
    public String getType(){ return type; }
    public void setType(String v){ type=v; }
    public String getPayload(){ return payload; }
    public void setPayload(String v){ payload=v; }
}
