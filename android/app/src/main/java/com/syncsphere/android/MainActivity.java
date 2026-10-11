package com.syncsphere.android;

import android.app.*;
import android.content.*;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import androidx.core.content.ContextCompat;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import org.json.JSONObject;
import org.json.JSONArray;
import org.webrtc.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;

public class MainActivity extends Activity {
    private static final int CAPTURE_REQUEST=4101;
    private static final int QR_SCAN_REQUEST=49374;
    private static final long CAPTURE_SERVICE_BIND_TIMEOUT_MS=10000;
    private static final int COLOR_NAVY=Color.rgb(15, 30, 61);
    private static final int COLOR_BLUE=Color.rgb(37, 99, 235);
    private static final int COLOR_MUTED=Color.rgb(91, 107, 140);
    private static final int COLOR_LINE=Color.rgb(225, 232, 245);
    private static final int COLOR_SURFACE=Color.rgb(239, 246, 255);
    private EditText serverInput, sessionInput;
    private TextView status;
    private Button startButton;
    private LinearLayout appRoot, pageBody;
    private Button tabShare, tabClipbox;
    private EditText clipRoomInput, clipTextInput, clipServerInput;
    private TextView clipStatus, clipCount, clipDeviceCount;
    private LinearLayout clipList;
    private Button clipJoinButton;
    private CheckBox clipAutoCopy;
    private ClipboxClient clipboxClient;
    private final DevicePresenceClient devicePresenceClient=new DevicePresenceClient();
    private String clipRoom;
    private String clipDraft="";
    private String clipDeviceId;
    private String registeredPresenceUrl;
    private boolean deviceRegistered;
    private JSONArray currentClipItems=new JSONArray();
    private int currentClipDeviceCount;
    private final Set<String> seenClipIds = new HashSet<>();
    private boolean clipInitialStateReceived;
    private boolean scanForScreenMirror;
    private PeerConnectionFactory factory;
    private PeerConnection peer;
    private EglBase eglBase;
    private VideoSource videoSource;
    private VideoTrack videoTrack;
    private DataChannel dataChannel;
    private StompClient stomp;
    private final List<IceCandidate> pendingIceCandidates = new CopyOnWriteArrayList<>();
    private final Queue<IceCandidate> pendingLocalIceCandidates = new ConcurrentLinkedQueue<>();
    private volatile boolean remoteDescriptionSet;
    private volatile int sentIceCandidateCount;
    private volatile int receivedIceCandidateCount;
    private volatile int generatedIceCandidateCount;
    private volatile PeerConnection.IceConnectionState iceConnectionState =
            PeerConnection.IceConnectionState.NEW;
    private volatile PeerConnection.IceGatheringState iceGatheringState =
            PeerConnection.IceGatheringState.NEW;
    private volatile boolean localAnswerSet;
    private volatile boolean localAnswerHasIceCredentials;
    private String sessionId, deviceId;
    private MediaProjectionManager projectionManager;
    private ClipboardManager clipboard;
    private boolean suppressClipboard;
    private ScreenCaptureService captureService;
    private boolean captureServiceBound;
    private Intent capturePermissionData;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable captureServiceBindTimeout = () -> {
        if (captureServiceBound || capturePermissionData == null) return;
        capturePermissionData = null;
        status.setText("Screen capture service did not connect. Close and reopen SyncSphere, then try again.");
        stopService(new Intent(this,ScreenCaptureService.class));
        tearDownWebRTC();
    };

    private final ServiceConnection captureServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mainHandler.removeCallbacks(captureServiceBindTimeout);
            ScreenCaptureService.LocalBinder binder = (ScreenCaptureService.LocalBinder) service;
            captureService = binder.getService();
            captureServiceBound = true;
            if (capturePermissionData != null) {
                status.setText("Capture service ready. Preparing video…");
                Intent permissionData = capturePermissionData;
                capturePermissionData = null;
                continueScreenCapture(permissionData);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mainHandler.removeCallbacks(captureServiceBindTimeout);
            captureService = null;
            captureServiceBound = false;
            if (status != null) status.setText("Screen capture service disconnected.");
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        android.content.SharedPreferences preferences=getSharedPreferences("syncsphere",MODE_PRIVATE);
        deviceId=preferences.getString("deviceId",UUID.randomUUID().toString());
        clipDeviceId=deviceId;
        preferences.edit().putString("deviceId",deviceId).putString("clipDeviceId",clipDeviceId).apply();
        buildUi();
    }
    private void buildUi(){
        appRoot=new LinearLayout(this);
        appRoot.setOrientation(LinearLayout.VERTICAL);
        appRoot.setBackgroundColor(COLOR_SURFACE);

        LinearLayout header=new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(22),dp(18),dp(22),dp(14));
        header.setBackgroundColor(Color.WHITE);
        TextView brand=new TextView(this);
        brand.setText("SyncSphere");
        brand.setTextColor(COLOR_NAVY);
        brand.setTextSize(22);
        brand.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        header.addView(brand,new LinearLayout.LayoutParams(0,dp(34),1));
        TextView badge=new TextView(this);
        badge.setText("CROSS-DEVICE");
        badge.setTextColor(COLOR_BLUE);
        badge.setTextSize(10);
        badge.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        badge.setPadding(dp(10),dp(7),dp(10),dp(7));
        badge.setBackground(roundRect(Color.rgb(219,234,254),dp(20)));
        header.addView(badge);
        appRoot.addView(header);

        LinearLayout tabs=new LinearLayout(this);
        tabs.setPadding(dp(16),dp(8),dp(16),dp(8));
        tabs.setBackgroundColor(Color.WHITE);
        tabShare=makeTab("Screen Share");
        tabClipbox=makeTab("Clipbox");
        tabs.addView(tabShare,new LinearLayout.LayoutParams(0,dp(44),1));
        LinearLayout.LayoutParams clipTabParams=new LinearLayout.LayoutParams(0,dp(44),1);
        clipTabParams.leftMargin=dp(8);
        tabs.addView(tabClipbox,clipTabParams);
        appRoot.addView(tabs);

        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        pageBody=new LinearLayout(this);
        pageBody.setOrientation(LinearLayout.VERTICAL);
        pageBody.setPadding(dp(18),dp(20),dp(18),dp(26));
        scroll.addView(pageBody);
        appRoot.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(appRoot);
        tabShare.setOnClickListener(v->showScreenShare());
        tabClipbox.setOnClickListener(v->showClipbox());
        showScreenShare();

        clipboard=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        clipboard.addPrimaryClipChangedListener(()->{
            if(suppressClipboard || dataChannel==null || dataChannel.state()!=DataChannel.State.OPEN) return;
            try { String text=clipboard.getPrimaryClip().getItemAt(0).coerceToText(this).toString(); sendData(new JSONObject().put("type","CLIPBOARD").put("text",text)); } catch(Exception ignored){}
        });
    }

    private void showScreenShare(){
        styleTab(tabShare,true);
        styleTab(tabClipbox,false);
        pageBody.removeAllViews();
        buildScreenShareUi(pageBody);
    }

    private void showClipbox(){
        styleTab(tabShare,false);
        styleTab(tabClipbox,true);
        pageBody.removeAllViews();
        buildClipboxUi(pageBody);
    }

    private void buildScreenShareUi(LinearLayout box){
        TextView title=heading("Android screen sharing");
        box.addView(title);
        box.addView(subtitle("Mirror this phone to your SyncSphere desktop using a private room code."),bottomMargin(18));
        LinearLayout card=card(box);
        card.addView(fieldLabel("Backend WebSocket URL"));
        serverInput=editText("ws://192.168.1.10:8080/ws",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        serverInput.setText(serverUrl());
        card.addView(serverInput,bottomMargin(12));
        card.addView(fieldLabel("Desktop session code"));
        sessionInput=editText("Enter the code shown on desktop",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        LinearLayout sessionRow=new LinearLayout(this);
        sessionRow.setGravity(Gravity.CENTER_VERTICAL);
        sessionRow.addView(sessionInput,new LinearLayout.LayoutParams(0,dp(52),1));
        Button scan=secondaryButton("Scan QR");
        LinearLayout.LayoutParams scanParams=new LinearLayout.LayoutParams(-2,dp(48));
        scanParams.leftMargin=dp(8);
        sessionRow.addView(scan,scanParams);
        card.addView(sessionRow,bottomMargin(14));
        status=subtitle("Disconnected");
        card.addView(status,bottomMargin(12));
        startButton=primaryButton("Start screen sharing");
        card.addView(startButton,new LinearLayout.LayoutParams(-1,dp(50)));
        Button accessibility=secondaryButton("Enable remote touch permission");
        LinearLayout.LayoutParams permissionParams=bottomMargin(0);
        permissionParams.topMargin=dp(10);
        card.addView(accessibility,permissionParams);
        startButton.setOnClickListener(v->beginCapture());
        scan.setOnClickListener(v->{scanForScreenMirror=true;launchQrScanner();});
        accessibility.setOnClickListener(v->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        listenForServerUrl(serverInput);
    }

    private void buildClipboxUi(LinearLayout box){
        box.addView(heading("Clipbox"));
        box.addView(subtitle("Share notes, links, and copied text between your phone and other devices. Replace the example server address with your PC's LAN IP when on Wi‑Fi."),bottomMargin(18));

        LinearLayout roomCard=card(box);
        roomCard.addView(fieldLabel("Backend WebSocket URL"));
        clipServerInput=editText("ws://192.168.1.10:8080/ws",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        clipServerInput.setText(serverUrl());
        roomCard.addView(clipServerInput,bottomMargin(14));
        listenForServerUrl(clipServerInput);

        roomCard.addView(fieldLabel("Shared room code"));
        LinearLayout roomRow=new LinearLayout(this);
        roomRow.setGravity(Gravity.CENTER_VERTICAL);
        clipRoomInput=editText("Enter a 4-12 character code",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        clipRoomInput.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(12)});
        clipRoomInput.setText(clipRoom==null?getPreferences(MODE_PRIVATE).getString("clipRoom",""):clipRoom);
        roomRow.addView(clipRoomInput,new LinearLayout.LayoutParams(0,dp(52),1));
        Button generate=secondaryButton("New code");
        LinearLayout.LayoutParams generateParams=new LinearLayout.LayoutParams(-2,dp(48));
        generateParams.leftMargin=dp(8);
        roomRow.addView(generate,generateParams);
        Button scan=secondaryButton("Scan");
        LinearLayout.LayoutParams scanParams=new LinearLayout.LayoutParams(-2,dp(48));
        scanParams.leftMargin=dp(8);
        roomRow.addView(scan,scanParams);
        roomCard.addView(roomRow,bottomMargin(12));
        LinearLayout connectionRow=new LinearLayout(this);
        connectionRow.setGravity(Gravity.CENTER_VERTICAL);
        Button join=primaryButton("Join room");
        clipJoinButton=join;
        connectionRow.addView(join,new LinearLayout.LayoutParams(0,dp(48),1));
        Button share=secondaryButton("Share");
        LinearLayout.LayoutParams shareParams=new LinearLayout.LayoutParams(0,dp(48),1);
        shareParams.leftMargin=dp(8);
        connectionRow.addView(share,shareParams);
        roomCard.addView(connectionRow,bottomMargin(12));
        LinearLayout statusRow=new LinearLayout(this);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        clipStatus=subtitle("Not connected");
        statusRow.addView(clipStatus,new LinearLayout.LayoutParams(0,-2,1));
        clipDeviceCount=subtitle("");
        statusRow.addView(clipDeviceCount);
        roomCard.addView(statusRow);
        generate.setOnClickListener(v->{
            String code=generateRoomCode();
            clipRoomInput.setText(code);
            connectClipbox(code);
        });
        scan.setOnClickListener(v->{scanForScreenMirror=false;launchQrScanner();});
        join.setOnClickListener(v->{
            if(clipboxClient!=null && clipboxClient.isConnected()) leaveClipbox();
            else connectClipbox(clipRoomInput.getText().toString());
        });
        share.setOnClickListener(v->shareClipboxRoom());
        if(clipboxClient!=null && clipboxClient.isConnected()){
            clipRoomInput.setEnabled(false);
            clipJoinButton.setText("Leave room");
            clipStatus.setText("Room "+clipRoom+" is live");
            clipDeviceCount.setText(currentClipDeviceCount+" "+(currentClipDeviceCount==1?"device":"devices"));
            renderClipItems(currentClipItems);
        }

        LinearLayout composeCard=card(box);
        composeCard.addView(fieldLabel("Write a clip"));
        clipTextInput=new EditText(this);
        clipTextInput.setGravity(Gravity.TOP|Gravity.START);
        clipTextInput.setTextSize(15);
        clipTextInput.setHint("Paste or type something to share…");
        clipTextInput.setMinLines(4);
        clipTextInput.setMaxLines(8);
        clipTextInput.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        clipTextInput.setPadding(dp(14),dp(12),dp(14),dp(12));
        clipTextInput.setBackground(roundRect(Color.rgb(248,250,255),dp(12),COLOR_LINE));
        clipTextInput.setText(clipDraft);
        composeCard.addView(clipTextInput,bottomMargin(10));
        clipCount=subtitle("0 / 20,000");
        clipCount.setGravity(Gravity.RIGHT);
        composeCard.addView(clipCount,bottomMargin(10));
        clipTextInput.addTextChangedListener(new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){
                clipDraft=s.toString();
                if(clipCount!=null)clipCount.setText(s.length()+" / 20,000");
            }
            public void afterTextChanged(android.text.Editable e){}
        });
        LinearLayout actions=new LinearLayout(this);
        Button send=primaryButton("Send clip");
        actions.addView(send,new LinearLayout.LayoutParams(0,dp(48),1));
        Button paste=secondaryButton("Paste & send");
        LinearLayout.LayoutParams pasteParams=new LinearLayout.LayoutParams(0,dp(48),1);
        pasteParams.leftMargin=dp(8);
        actions.addView(paste,pasteParams);
        composeCard.addView(actions,bottomMargin(10));
        clipAutoCopy=new CheckBox(this);
        clipAutoCopy.setText("Auto-copy clips from other devices");
        clipAutoCopy.setTextColor(COLOR_MUTED);
        clipAutoCopy.setTextSize(13);
        clipAutoCopy.setButtonTintList(android.content.res.ColorStateList.valueOf(COLOR_BLUE));
        clipAutoCopy.setChecked(getPreferences(MODE_PRIVATE).getBoolean("clipAutoCopy",false));
        composeCard.addView(clipAutoCopy);
        send.setOnClickListener(v->sendClip());
        paste.setOnClickListener(v->pasteAndSendClip());
        clipAutoCopy.setOnCheckedChangeListener((button,checked)->getPreferences(MODE_PRIVATE).edit().putBoolean("clipAutoCopy",checked).apply());

        LinearLayout listCard=card(box);
        LinearLayout listHeader=new LinearLayout(this);
        listHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView listTitle=fieldLabel("Shared clips");
        listHeader.addView(listTitle,new LinearLayout.LayoutParams(0,-2,1));
        Button clear=secondaryButton("Clear");
        listHeader.addView(clear);
        listCard.addView(listHeader,bottomMargin(10));
        clipList=new LinearLayout(this);
        clipList.setOrientation(LinearLayout.VERTICAL);
        TextView empty=subtitle("Join a room to view its clips.");
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(12),dp(20),dp(12),dp(20));
        listCard.addView(clipList);
        clipList.addView(empty);
        clear.setOnClickListener(v->{
            if(clipboxClient!=null && clipboxClient.isConnected()){
                new AlertDialog.Builder(this).setTitle("Clear room clips?")
                        .setMessage("This removes all clips for every device in this room.")
                        .setNegativeButton("Cancel",null)
                        .setPositiveButton("Clear", (dialog,which)->sendClipboxAction("clear",new JSONObject()))
                        .show();
            }
        });
    }

    private Button makeTab(String label){
        Button button=new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        button.setPadding(dp(12),0,dp(12),0);
        return button;
    }

    private void styleTab(Button button,boolean selected){
        button.setTextColor(selected?Color.WHITE:COLOR_MUTED);
        button.setBackground(roundRect(selected?COLOR_BLUE:COLOR_SURFACE,dp(12)));
    }

    private LinearLayout card(LinearLayout parent){
        LinearLayout card=new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16),dp(16),dp(16),dp(16));
        card.setBackground(roundRect(Color.WHITE,dp(18),COLOR_LINE));
        LinearLayout.LayoutParams params=bottomMargin(14);
        parent.addView(card,params);
        return card;
    }

    private TextView heading(String value){
        TextView view=new TextView(this);
        view.setText(value);
        view.setTextColor(COLOR_NAVY);
        view.setTextSize(24);
        view.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return view;
    }

    private TextView subtitle(String value){
        TextView view=new TextView(this);
        view.setText(value);
        view.setTextColor(COLOR_MUTED);
        view.setTextSize(13);
        return view;
    }

    private TextView fieldLabel(String value){
        TextView view=new TextView(this);
        view.setText(value);
        view.setTextColor(COLOR_NAVY);
        view.setTextSize(13);
        view.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        view.setPadding(0,0,0,dp(7));
        return view;
    }

    private EditText editText(String hint,int inputType){
        EditText input=new EditText(this);
        input.setSingleLine(true);
        input.setTextSize(14);
        input.setHint(hint);
        input.setInputType(inputType);
        input.setPadding(dp(14),0,dp(14),0);
        input.setBackground(roundRect(Color.rgb(248,250,255),dp(12),COLOR_LINE));
        return input;
    }

    private Button primaryButton(String text){
        Button button=new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        button.setBackground(roundRect(COLOR_BLUE,dp(12)));
        return button;
    }

    private Button secondaryButton(String text){
        Button button=new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextColor(COLOR_BLUE);
        button.setTextSize(13);
        button.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        button.setBackground(roundRect(Color.rgb(239,246,255),dp(12),COLOR_LINE));
        return button;
    }

    private GradientDrawable roundRect(int color,int radius){
        GradientDrawable drawable=new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private GradientDrawable roundRect(int color,int radius,int strokeColor){
        GradientDrawable drawable=roundRect(color,radius);
        drawable.setStroke(dp(1),strokeColor);
        return drawable;
    }

    private LinearLayout.LayoutParams bottomMargin(int margin){
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);
        params.bottomMargin=dp(margin);
        return params;
    }

    private int dp(int value){
        return Math.round(value*getResources().getDisplayMetrics().density);
    }

    private String serverUrl(){
        return getPreferences(MODE_PRIVATE).getString("serverUrl","ws://192.168.1.10:8080/ws");
    }

    private void listenForServerUrl(EditText input){
        input.addTextChangedListener(new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){
                getPreferences(MODE_PRIVATE).edit().putString("serverUrl",s.toString().trim()).apply();
            }
            public void afterTextChanged(android.text.Editable e){}
        });
    }

    private String generateRoomCode(){
        final String alphabet="ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder code=new StringBuilder(6);
        Random random=new Random();
        for(int i=0;i<6;i++) code.append(alphabet.charAt(random.nextInt(alphabet.length())));
        return code.toString();
    }

    private void connectClipbox(String requestedRoom){
        String normalized=requestedRoom.trim().toUpperCase(Locale.ROOT);
        if(!normalized.matches("[A-Z0-9]{4,12}")){
            clipRoomInput.setError("Use 4–12 letters or numbers");
            return;
        }
        String url=normalizeWebSocketUrl(clipServerInput.getText().toString());
        if(url==null){
            clipServerInput.setError("Enter a valid ws:// or wss:// backend URL");
            return;
        }
        if(clipboxClient!=null) clipboxClient.close();
        if(deviceRegistered) updateDevicePresence(false);
        clipRoom=normalized;
        getPreferences(MODE_PRIVATE).edit().putString("clipRoom",clipRoom).apply();
        clipRoomInput.setText(clipRoom);
        clipRoomInput.setEnabled(false);
        clipJoinButton.setText("Connecting…");
        clipStatus.setText("Connecting…");
        clipDeviceCount.setText("");
        seenClipIds.clear();
        clipInitialStateReceived=false;
        renderClipItems(new JSONArray());
        final ClipboxClient[] clientHolder=new ClipboxClient[1];
        clientHolder[0]=new ClipboxClient(url,clipRoom,new ClipboxClient.Listener(){
            public void onConnected(){
                runOnUiThread(()->{
                    if(clipStatus!=null) clipStatus.setText("Connected · joining room…");
                    clientHolder[0].send(clipDeviceId,"join",new JSONObject());
                });
            }
            public void onMessage(JSONObject message){
                runOnUiThread(()->handleClipboxMessage(message));
            }
            public void onError(String error){
                runOnUiThread(()->{
                    if(clipStatus!=null) clipStatus.setText("Connection issue: "+error);
                    if(clipRoomInput!=null) clipRoomInput.setEnabled(true);
                    if(clipJoinButton!=null) clipJoinButton.setText("Join room");
                });
            }
        });
        clipboxClient=clientHolder[0];
        clipboxClient.connect();
    }

    private void leaveClipbox(){
        if(clipboxClient!=null){clipboxClient.close();clipboxClient=null;}
        if(deviceRegistered) updateDevicePresence(false);
        clipRoomInput.setEnabled(true);
        clipJoinButton.setText("Join room");
        clipStatus.setText("Left room");
        clipDeviceCount.setText("");
        currentClipItems=new JSONArray();
        currentClipDeviceCount=0;
        seenClipIds.clear();
        renderClipItems(currentClipItems);
    }

    private String normalizeWebSocketUrl(String raw){
        String url=raw.trim();
        if(url.startsWith("http://")) url="ws://"+url.substring(7);
        else if(url.startsWith("https://")) url="wss://"+url.substring(8);
        if(!(url.startsWith("ws://")||url.startsWith("wss://"))) return null;
        if(!url.endsWith("/ws")) url=url.endsWith("/")?url+"ws":url+"/ws";
        return url;
    }

    private void handleClipboxMessage(JSONObject message){
        String type=message.optString("type");
        if(type.equals("error")){
            if(clipStatus!=null) clipStatus.setText("Room error: "+message.optString("reason","Request failed"));
            return;
        }
        if(type.equals("state")){
            JSONArray items=message.optJSONArray("items");
            if(items==null) items=new JSONArray();
            if(!clipInitialStateReceived){
                for(int i=0;i<items.length();i++) seenClipIds.add(items.optJSONObject(i)==null?"":items.optJSONObject(i).optString("id"));
                clipInitialStateReceived=true;
            }else if(clipAutoCopy!=null && clipAutoCopy.isChecked() && items.length()>0){
                JSONObject newest=items.optJSONObject(0);
                if(newest!=null){
                    String id=newest.optString("id");
                    if(!newest.optString("deviceId").equals(clipDeviceId) && !seenClipIds.contains(id)){
                        setAndroidClipboard(newest.optString("text"));
                    }
                }
            }
            for(int i=0;i<items.length();i++){
                JSONObject item=items.optJSONObject(i);
                if(item!=null) seenClipIds.add(item.optString("id"));
            }
            if(clipStatus!=null) clipStatus.setText("Room "+clipRoom+" is live");
            if(!deviceRegistered) updateDevicePresence(true);
            if(clipDeviceCount!=null){
                int count=message.optInt("devices",0);
                currentClipDeviceCount=count;
                clipDeviceCount.setText(count+" "+(count==1?"device":"devices"));
            }
            currentClipItems=items;
            if(clipJoinButton!=null) clipJoinButton.setText("Leave room");
            renderClipItems(items);
        }
    }

    private void renderClipItems(JSONArray items){
        if(clipList==null) return;
        clipList.removeAllViews();
        if(items.length()==0){
            TextView empty=subtitle(clipboxClient!=null&&clipboxClient.isConnected()
                    ?"Nothing shared here yet. Send the first clip."
                    :"Join a room to view its clips.");
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(12),dp(20),dp(12),dp(20));
            clipList.addView(empty);
            return;
        }
        for(int i=0;i<items.length();i++){
            JSONObject item=items.optJSONObject(i);
            if(item==null) continue;
            String id=item.optString("id");
            String value=item.optString("text");
            LinearLayout row=new LinearLayout(this);
            row.setGravity(Gravity.TOP);
            row.setPadding(dp(12),dp(12),dp(12),dp(12));
            row.setBackground(roundRect(COLOR_SURFACE,dp(12),COLOR_LINE));
            LinearLayout.LayoutParams rowParams=bottomMargin(8);
            clipList.addView(row,rowParams);
            LinearLayout content=new LinearLayout(this);
            content.setOrientation(LinearLayout.VERTICAL);
            row.addView(content,new LinearLayout.LayoutParams(0,-2,1));
            TextView clipText=new TextView(this);
            clipText.setText(value);
            clipText.setTextColor(COLOR_NAVY);
            clipText.setTextSize(14);
            clipText.setMaxLines(5);
            clipText.setEllipsize(android.text.TextUtils.TruncateAt.END);
            content.addView(clipText);
            TextView time=subtitle(formatClipTime(item.optString("ts")));
            time.setTextSize(11);
            LinearLayout.LayoutParams timeParams=bottomMargin(0);
            timeParams.topMargin=dp(7);
            content.addView(time,timeParams);
            LinearLayout buttons=new LinearLayout(this);
            buttons.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams buttonsParams=new LinearLayout.LayoutParams(-2,-2);
            buttonsParams.leftMargin=dp(8);
            row.addView(buttons,buttonsParams);
            Button copy=secondaryButton("Copy");
            buttons.addView(copy);
            Button delete=secondaryButton("Delete");
            LinearLayout.LayoutParams deleteParams=bottomMargin(0);
            deleteParams.topMargin=dp(5);
            buttons.addView(delete,deleteParams);
            copy.setOnClickListener(v->setAndroidClipboard(value));
            delete.setOnClickListener(v->{
                try{sendClipboxAction("delete",new JSONObject().put("id",id));}
                catch(Exception error){clipStatus.setText("Could not delete clip: "+error.getMessage());}
            });
        }
    }

    private String formatClipTime(String value){
        try{
            java.time.Instant instant=java.time.Instant.parse(value);
            return java.time.format.DateTimeFormatter.ofPattern("MMM d, h:mm a",Locale.getDefault())
                    .withZone(java.time.ZoneId.systemDefault()).format(instant);
        }catch(Exception ignored){return value;}
    }

    private void sendClip(){
        if(clipTextInput==null) return;
        String value=clipTextInput.getText().toString();
        if(value.trim().isEmpty()){
            clipTextInput.setError("Type something to share");
            return;
        }
        if(value.length()>20000){
            clipTextInput.setError("Clips are limited to 20,000 characters");
            return;
        }
        try{
            sendClipboxAction("add",new JSONObject().put("text",value));
            clipTextInput.setText("");
        }catch(Exception error){
            clipStatus.setText("Could not send clip: "+error.getMessage());
        }
    }

    private void pasteAndSendClip(){
        if(clipboard==null||!clipboard.hasPrimaryClip()){
            clipStatus.setText("Your clipboard is empty.");
            return;
        }
        CharSequence contents=clipboard.getPrimaryClip().getItemAt(0).coerceToText(this);
        if(contents==null||contents.toString().trim().isEmpty()){
            clipStatus.setText("Your clipboard has no text to send.");
            return;
        }
        clipTextInput.setText(contents);
        sendClip();
    }

    private void sendClipboxAction(String type,JSONObject details){
        if(clipboxClient==null||!clipboxClient.isConnected()){
            clipStatus.setText("Join a room before sending clips.");
            return;
        }
        clipboxClient.send(clipDeviceId,type,details);
    }

    private void updateDevicePresence(boolean online){
        String rawUrl=clipServerInput==null?serverUrl():clipServerInput.getText().toString();
        String websocketUrl=normalizeWebSocketUrl(rawUrl);
        if(websocketUrl==null) return;
        if(online){
            registeredPresenceUrl=websocketUrl;
            deviceRegistered=true;
            devicePresenceClient.register(websocketUrl,deviceId);
        }else{
            deviceRegistered=false;
            String url=registeredPresenceUrl==null?websocketUrl:registeredPresenceUrl;
            devicePresenceClient.setOffline(url,deviceId);
            registeredPresenceUrl=null;
        }
    }

    private void setAndroidClipboard(String value){
        if(clipboard==null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText("SyncSphere Clipbox",value));
        if(clipStatus!=null) clipStatus.setText("Copied incoming clip to Android clipboard.");
    }

    private void shareClipboxRoom(){
        if(clipRoom==null||clipRoom.isEmpty()){
            clipRoomInput.setError("Join a room first");
            return;
        }
        String backend=clipServerInput.getText().toString().trim();
        String url=backend.replaceFirst("^ws:","http:").replaceFirst("^wss:","https:");
        if(url.endsWith("/ws")) url=url.substring(0,url.length()-3);
        Intent intent=new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT,"Join my SyncSphere Clipbox room "+clipRoom+"\nServer: "+url+"\nRoom code: "+clipRoom);
        startActivity(Intent.createChooser(intent,"Share Clipbox room"));
    }

    private void launchQrScanner(){
        IntentIntegrator scanner=new IntentIntegrator(this);
        scanner.setRequestCode(QR_SCAN_REQUEST);
        scanner.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE);
        scanner.setPrompt("Scan the SyncSphere pairing QR");
        scanner.setBeepEnabled(false);
        scanner.setOrientationLocked(false);
        scanner.initiateScan();
    }

    private void handlePairingQr(String contents){
        try{
            android.net.Uri pairing=android.net.Uri.parse(contents);
            if(!"syncsphere".equals(pairing.getScheme())||!"pair".equals(pairing.getHost())){
                throw new IllegalArgumentException("This is not a SyncSphere pairing code.");
            }
            String type=pairing.getQueryParameter("type");
            String server=pairing.getQueryParameter("server");
            String room=pairing.getQueryParameter("room");
            if(server==null||room==null||!(server.startsWith("ws://")||server.startsWith("wss://"))){
                throw new IllegalArgumentException("The pairing QR is missing its server address or room code.");
            }
            if("clipbox".equals(type)){
                showClipbox();
                clipServerInput.setText(server);
                clipRoomInput.setText(room);
                connectClipbox(room);
                clipStatus.setText("Scanned room code. Connecting…");
            }else if("screen".equals(type)){
                showScreenShare();
                serverInput.setText(server);
                sessionInput.setText(room);
                scanForScreenMirror=false;
                beginCapture();
            }else{
                throw new IllegalArgumentException("This QR code is not for a supported SyncSphere feature.");
            }
        }catch(Exception error){
            new AlertDialog.Builder(this)
                    .setTitle("Could not read pairing QR")
                    .setMessage(error.getMessage()==null?"The QR code is invalid.":error.getMessage())
                    .setPositiveButton("OK",null)
                    .show();
        }
    }
    private void beginCapture(){
        sessionId=sessionInput.getText().toString().trim().toUpperCase();
        if(sessionId.isEmpty()){sessionInput.setError("Enter the Windows session code");return;}
        projectionManager=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(projectionManager.createScreenCaptureIntent(),CAPTURE_REQUEST);
    }
    private void startWebRTC(Intent permission){
        status.setText("Starting screen capture service…");
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(this).createInitializationOptions());
        eglBase=EglBase.create();
        factory=PeerConnectionFactory.builder()
                .setVideoEncoderFactory(new DefaultVideoEncoderFactory(
                        eglBase.getEglBaseContext(),true,true))
                .setVideoDecoderFactory(new DefaultVideoDecoderFactory(eglBase.getEglBaseContext()))
                .createPeerConnectionFactory();
        List<PeerConnection.IceServer> ice=new ArrayList<>(); ice.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());
        PeerConnection.RTCConfiguration cfg=new PeerConnection.RTCConfiguration(ice); cfg.sdpSemantics=PeerConnection.SdpSemantics.UNIFIED_PLAN;
        cfg.continualGatheringPolicy=PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;
        remoteDescriptionSet=false;
        pendingIceCandidates.clear();
        pendingLocalIceCandidates.clear();
        sentIceCandidateCount=0;
        receivedIceCandidateCount=0;
        generatedIceCandidateCount=0;
        iceConnectionState=PeerConnection.IceConnectionState.NEW;
        iceGatheringState=PeerConnection.IceGatheringState.NEW;
        localAnswerSet=false;
        localAnswerHasIceCredentials=false;
        peer=factory.createPeerConnection(cfg,new PeerConnection.Observer(){
            public void onIceCandidate(IceCandidate c){
                generatedIceCandidateCount++;
                updateIceDiagnostics();
                sendIceCandidate(c);
            }
            public void onDataChannel(DataChannel dc){ dataChannel=dc; setupDataChannel(dc); }
            public void onConnectionChange(PeerConnection.PeerConnectionState s){ runOnUiThread(()->status.setText("WebRTC: "+s)); }
            public void onAddStream(MediaStream s){} public void onRemoveStream(MediaStream s){} public void onSignalingChange(PeerConnection.SignalingState s){}
            public void onIceConnectionChange(PeerConnection.IceConnectionState s){
                iceConnectionState=s;
                updateIceDiagnostics();
            }
            public void onIceConnectionReceivingChange(boolean b){}
            public void onIceGatheringChange(PeerConnection.IceGatheringState s){
                iceGatheringState=s;
                updateIceDiagnostics();
            }
            public void onIceCandidatesRemoved(IceCandidate[] c){} public void onRenegotiationNeeded(){} public void onAddTrack(RtpReceiver r,MediaStream[] s){}
        });
        videoSource=factory.createVideoSource(true);
        capturePermissionData=permission;
        Intent serviceIntent=new Intent(this,ScreenCaptureService.class);
        try{
            status.setText("Starting screen capture service…");
            ContextCompat.startForegroundService(this,serviceIntent);
            if(!bindService(serviceIntent,captureServiceConnection,BIND_AUTO_CREATE)){
                throw new IllegalStateException("Could not bind to the screen capture service.");
            }
            mainHandler.postDelayed(captureServiceBindTimeout,CAPTURE_SERVICE_BIND_TIMEOUT_MS);
        }catch(RuntimeException e){
            capturePermissionData=null;
            status.setText("Could not start screen sharing: "+e.getMessage());
            stopService(serviceIntent);
            tearDownWebRTC();
        }
    }
    private void continueScreenCapture(Intent permission){
        try{
            status.setText("Capture service ready. Starting screen capture…");
            captureService.startCapture(permission,videoSource,eglBase.getEglBaseContext(),
                    ()->status.setText("Screen capture stopped."));
            status.setText("Screen capture running. Connecting to Windows…");
        }catch(RuntimeException e){
            status.setText("Screen capture failed: "+e.getMessage());
            tearDownWebRTC();
            return;
        }
        videoTrack=factory.createVideoTrack("syncsphere-screen",videoSource);
        RtpSender videoSender=peer.addTrack(videoTrack,new ArrayList<>(Collections.singletonList("syncsphere")));
        RtpParameters senderParameters=videoSender.getParameters();
        senderParameters.degradationPreference=RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION;
        for(RtpParameters.Encoding encoding:senderParameters.encodings){
            encoding.maxBitrateBps=4_000_000;
            encoding.maxFramerate=30;
        }
        if(!videoSender.setParameters(senderParameters)){
            status.setText("Screen sharing started, but video quality tuning was not accepted.");
        }
        dataChannel=peer.createDataChannel("syncsphere",new DataChannel.Init()); setupDataChannel(dataChannel);
        String ws=serverInput.getText().toString().trim();
        status.setText("Screen capture running. Connecting signaling…");
        stomp=new StompClient(ws,"/topic/signal/"+sessionId,new StompClient.Listener(){
            public void onConnected(){
                runOnUiThread(()->status.setText("Signaling connected. Waiting for the Windows session…"));
                try{
                    flushPendingLocalIceCandidates();
                    stomp.sendSignal(sessionId,deviceId,"ANDROID_READY",new JSONObject());
                }
                catch(Exception e){ runOnUiThread(()->status.setText("Could not announce Android session: "+e.getMessage())); }
            }
            public void onMessage(JSONObject m){
                if(deviceId.equals(m.optString("senderId"))) return;
                String type=m.optString("type");
                if(type.equals("OFFER")) runOnUiThread(()->status.setText("Windows session found. Negotiating video…"));
                handleSignal(type,m.optString("payload"));
            }
            public void onError(String e){runOnUiThread(()->status.setText("Signaling error: "+e));}
        }); stomp.connect();
    }
    private void sendIceCandidate(IceCandidate candidate){
        StompClient currentStomp=stomp;
        if(currentStomp==null || !currentStomp.isConnected()){
            pendingLocalIceCandidates.add(candidate);
            return;
        }
        try{
            currentStomp.sendSignal(sessionId,deviceId,"ICE",new JSONObject()
                    .put("sdpMid",candidate.sdpMid)
                    .put("sdpMLineIndex",candidate.sdpMLineIndex)
                    .put("candidate",candidate.sdp));
            sentIceCandidateCount++;
            updateIceDiagnostics();
        }catch(Exception e){
            pendingLocalIceCandidates.add(candidate);
            runOnUiThread(()->status.setText("Could not send ICE candidate: "+e.getMessage()));
        }
    }
    private void updateIceDiagnostics(){
        runOnUiThread(()->status.setText("WebRTC ICE: "+iceConnectionState
                +" | local answer "+(localAnswerSet
                        ?(localAnswerHasIceCredentials?"set with ICE credentials":"set without ICE credentials")
                        :"not set")
                +" | gathering "+iceGatheringState
                +" | generated "+generatedIceCandidateCount
                +" / sent "+sentIceCandidateCount
                +" / received "+receivedIceCandidateCount+" candidates"));
    }
    private void flushPendingLocalIceCandidates(){
        IceCandidate candidate;
        while((candidate=pendingLocalIceCandidates.poll())!=null){
            sendIceCandidate(candidate);
        }
    }
    private void handleSignal(String type,String payload){
        try{ JSONObject p=new JSONObject(payload);
            if(type.equals("OFFER")){ peer.setRemoteDescription(new SimpleSdpObserver(){
                public void onSetSuccess(){
                    remoteDescriptionSet=true;
                    applyPendingIceCandidates();
                    createAnswer();
                }
                public void onSetFailure(String error){ runOnUiThread(()->status.setText("Could not apply Windows offer: "+error)); }
            },new SessionDescription(SessionDescription.Type.OFFER,p.getString("sdp"))); }
            else if(type.equals("ANSWER")){ peer.setRemoteDescription(new SimpleSdpObserver(){
                public void onSetSuccess(){ remoteDescriptionSet=true; applyPendingIceCandidates(); }
                public void onSetFailure(String error){ runOnUiThread(()->status.setText("Could not apply Windows answer: "+error)); }
            },new SessionDescription(SessionDescription.Type.ANSWER,p.getString("sdp"))); }
            else if(type.equals("ICE")){
                IceCandidate candidate=new IceCandidate(p.optString("sdpMid"),p.optInt("sdpMLineIndex"),p.optString("candidate"));
                receivedIceCandidateCount++;
                updateIceDiagnostics();
                if(remoteDescriptionSet) addIceCandidate(candidate);
                else pendingIceCandidates.add(candidate);
            }
        }catch(Exception e){runOnUiThread(()->status.setText("Signal error: "+e.getMessage()));}
    }
    private void applyPendingIceCandidates(){
        for(IceCandidate candidate:pendingIceCandidates) addIceCandidate(candidate);
        pendingIceCandidates.clear();
    }
    private void addIceCandidate(IceCandidate candidate){
        if(peer!=null && !peer.addIceCandidate(candidate)){
            runOnUiThread(()->status.setText("WebRTC could not add a network candidate."));
        }
    }
    private void createAnswer(){
        peer.createAnswer(new SimpleSdpObserver(){
            @Override public void onCreateSuccess(SessionDescription s){
                peer.setLocalDescription(new SimpleSdpObserver(){
                    @Override public void onSetSuccess(){
                        localAnswerSet=true;
                        localAnswerHasIceCredentials=s.description.contains("a=ice-ufrag:")
                                && s.description.contains("a=ice-pwd:");
                        updateIceDiagnostics();
                        try{
                            stomp.sendSignal(sessionId,deviceId,"ANSWER",new JSONObject().put("type","answer").put("sdp",s.description));
                        }catch(Exception e){ runOnUiThread(()->status.setText("Could not send WebRTC answer: "+e.getMessage())); }
                    }
                    @Override public void onSetFailure(String error){ runOnUiThread(()->status.setText("Could not set Android WebRTC answer: "+error)); }
                },s);
            }
            @Override public void onCreateFailure(String error){ runOnUiThread(()->status.setText("Could not create Android WebRTC answer: "+error)); }
        },new MediaConstraints());
    }
    private void setupDataChannel(DataChannel dc){ dc.registerObserver(new DataChannel.Observer(){ public void onBufferedAmountChange(long l){} public void onStateChange(){} public void onMessage(DataChannel.Buffer b){ try{ String text=java.nio.charset.StandardCharsets.UTF_8.decode(b.data).toString(); JSONObject m=new JSONObject(text); String type=m.optString("type"); if(type.equals("TOUCH")){RemoteAccessibilityService.tap((float)m.optDouble("x"),(float)m.optDouble("y"));} else if(type.equals("CLIPBOARD")){setClipboard(m.optString("text"));} else if(type.equals("CLIPBOARD_REQUEST")){sendClipboard();} }catch(Exception ignored){} } }); }
    private void sendClipboard(){ try{if(clipboard.hasPrimaryClip()) sendData(new JSONObject().put("type","CLIPBOARD").put("text",clipboard.getPrimaryClip().getItemAt(0).coerceToText(this).toString()));}catch(Exception ignored){} }
    private void setClipboard(String text){ suppressClipboard=true; clipboard.setPrimaryClip(ClipData.newPlainText("SyncSphere",text)); new Handler().postDelayed(()->suppressClipboard=false,500); }
    private void sendData(JSONObject m){if(dataChannel!=null && dataChannel.state()==DataChannel.State.OPEN){byte[] bytes=m.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);dataChannel.send(new DataChannel.Buffer(java.nio.ByteBuffer.wrap(bytes),false));}}
    private void tearDownWebRTC(){
        mainHandler.removeCallbacks(captureServiceBindTimeout);
        if(stomp!=null){stomp.close();stomp=null;}
        if(peer!=null){peer.close();peer=null;}
        remoteDescriptionSet=false;
        pendingIceCandidates.clear();
        pendingLocalIceCandidates.clear();
        if(captureServiceBound){
            captureService.stopCapture();
            unbindService(captureServiceConnection);
            captureServiceBound=false;
            captureService=null;
        }else{
            stopService(new Intent(this,ScreenCaptureService.class));
            capturePermissionData=null;
        }
        if(videoTrack!=null){videoTrack.dispose();videoTrack=null;}
        if(videoSource!=null){videoSource.dispose();videoSource=null;}
        if(factory!=null){factory.dispose();factory=null;}
        if(eglBase!=null){eglBase.release();eglBase=null;}
    }
    @Override protected void onActivityResult(int req,int result,Intent data){
        if(req==QR_SCAN_REQUEST){
            IntentResult scan=new IntentIntegrator(this).parseActivityResult(result,data);
            if(scan!=null){
                if(scan.getContents()!=null) handlePairingQr(scan.getContents());
                else if(clipStatus!=null&&!scanForScreenMirror) clipStatus.setText("QR scan cancelled.");
                else if(status!=null&&scanForScreenMirror) status.setText("QR scan cancelled.");
                scanForScreenMirror=false;
            }
            return;
        }
        super.onActivityResult(req,result,data);
        if(req==CAPTURE_REQUEST && result==RESULT_OK && data!=null){ startWebRTC(data); }
        else if(req==CAPTURE_REQUEST && status!=null){ status.setText("Screen capture permission was not granted."); }
    }
    @Override protected void onDestroy(){if(clipboxClient!=null){clipboxClient.close();clipboxClient=null;}if(deviceRegistered)updateDevicePresence(false);tearDownWebRTC();super.onDestroy();}
    static class SimpleSdpObserver implements SdpObserver { public void onCreateSuccess(SessionDescription s){} public void onSetSuccess(){} public void onCreateFailure(String s){} public void onSetFailure(String s){} }
}
