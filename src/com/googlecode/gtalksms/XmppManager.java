package com.googlecode.gtalksms;

import org.jivesoftware.smack.ConnectionConfiguration;
import org.jivesoftware.smack.PacketListener;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.filter.MessageTypeFilter;
import org.jivesoftware.smack.filter.PacketFilter;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.Packet;
import org.jivesoftware.smack.packet.Presence;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.util.Log;
import android.widget.Toast;

import com.googlecode.gtalksms.tools.Tools;

public class XmppManager {

    public static final int DISCONNECTED = 0;
    // A "transient" state - will only be CONNECTING *during* a call to start()
    public static final int CONNECTING = 1;
    public static final int CONNECTED = 2;
    // A "transient" state - will only be DISCONNECTING *during* a call to stop()
    public static final int DISCONNECTING = 3;
    // This state either means we are waiting for the network to come up,
    // or waiting for a retry attempt etc.
    public static final int WAITING_TO_CONNECT = 4;

    // A list of intent actions we broadcast.
    static final public String ACTION_MESSAGE_RECEIVED = "com.googlecode.gtalksms.XMPP_MESSAGE_RECEIVED";
    static final public String ACTION_PRESENCE_CHANGED = "com.googlecode.gtalksms.XMPP_PRESENCE_CHANGED";
    static final public String ACTION_CONNECTION_CHANGED = "com.googlecode.gtalksms.XMPP_CONNECTION_CHANGED";
    
    // Indicates the current state of the service (disconnected/connecting/connected)
    private int _status = DISCONNECTED;
    private String _presenceMessage = "GTalkSMS";
    
    private ConnectionConfiguration _connectionConfiguration = null;
    private XMPPConnection _connection = null;
    private PacketListener _packetListener = null;
    
    // Our current retry attempt, plus a runnable and handler to implement retry
    private int _currentRetryCount = 0;
    Runnable _reconnectRunnable = null;
    Handler _reconnectHandler = new Handler();

    private SettingsManager _settings;
    private Context _context;
    
    public XmppManager(SettingsManager settings, Context context) {
        _settings = settings;
        _context = context;
    }

    public void start() {
        _connectionConfiguration = new ConnectionConfiguration(_settings.serverHost, _settings.serverPort, _settings.serviceName);

        _currentRetryCount = 0;
        _reconnectRunnable = new Runnable() {
            public void run() {
                if (_currentRetryCount > 0) {
                    Log.v(Tools.LOG_TAG, "attempting reconnection");
                    Toast.makeText(_context, "Reconnecting", Toast.LENGTH_SHORT).show();
                }
                initConnection();
            }
        };
        
        initConnection();
    }
    
    public void stop() {
        stop(DISCONNECTED);
    }

    public void stop(int finalState) {
        if (finalState != DISCONNECTED && finalState != WAITING_TO_CONNECT) {
            throw new IllegalStateException("Invalid State: " + finalState);
        }
         
        if (isConnected()) {
            updateStatus(DISCONNECTING);
            if (_settings.notifyApplicationConnection) {
                send("GTalkSMS stopped.");
            }
        }
        
        if (_reconnectRunnable != null) {
            _reconnectHandler.removeCallbacks(_reconnectRunnable);
        }
        
        if (_connection != null) {
            if (_packetListener != null) {
                _connection.removePacketListener(_packetListener);
            }
            // don't try to disconnect if already disconnected
            if (isConnected()) {
                _connection.disconnect();
            }
        }
        _connection = null;
        _packetListener = null;
        _connectionConfiguration = null;
        updateStatus(finalState);
    }
    
    /** Updates the status about the service state (and the statusbar)*/
    private void updateStatus(int status) {
        if (status != _status) {
            Intent intent = new Intent(ACTION_CONNECTION_CHANGED);
            intent.putExtra("old_state", _status);
            intent.putExtra("new_state", status);
            _context.sendBroadcast(intent);
            _status = status;
        }
    }

    private void maybeStartReconnect() {
        if (_currentRetryCount > 5) {
            // we failed after all the retries - just die.
            Log.v(Tools.LOG_TAG, "maybeStartReconnect ran out of retrys");
            stop();
            Toast.makeText(_context, "Failed to connect.", Toast.LENGTH_SHORT).show();
            return;
        } else {
            updateStatus(WAITING_TO_CONNECT);
            _currentRetryCount += 1;
            // a simple linear-backoff strategy.
            int timeout = 5000 * _currentRetryCount;
            Log.e(Tools.LOG_TAG, "maybeStartReconnect scheduling retry in " + timeout);
            _reconnectHandler.postDelayed(_reconnectRunnable, timeout);
        }
    }

    /** init the XMPP connection */
    private void initConnection() {
        updateStatus(CONNECTING);
        NetworkInfo active = ((ConnectivityManager)_context.getSystemService(Service.CONNECTIVITY_SERVICE)).getActiveNetworkInfo();
        if (active == null || !active.isAvailable()) {
            Log.e(Tools.LOG_TAG, "connection request, but no network available");
            Toast.makeText(_context, "Waiting for network to become available.", Toast.LENGTH_SHORT).show();
            // we don't destroy the service here - our network receiver will notify us when
            // the network comes up and we try again then.
            updateStatus(WAITING_TO_CONNECT);
            return;
        }
        
        XMPPConnection connection = new XMPPConnection(_connectionConfiguration);
        try {
            connection.connect();
        } catch (Exception e) {
            Log.e(Tools.LOG_TAG, "xmpp connection failed: " + e);
            Toast.makeText(_context, "Connection failed.", Toast.LENGTH_SHORT).show();
            maybeStartReconnect();
            return;
        }
        
        try {
            connection.login(_settings.mLogin, _settings.mPassword, "GTalkSMS");
        } catch (Exception e) {
            try {
                connection.disconnect();
            } catch (Exception e2) {
                Log.e(Tools.LOG_TAG, "xmpp disconnect failed: " + e2);
            }
            
            Log.e(Tools.LOG_TAG, "xmpp login failed: " + e);
            // sadly, smack throws the same generic XMPPException for network
            // related messages (eg "no response from the server") as for
            // authoritative login errors (ie, bad password).  The only
            // differentiator is the message itself which starts with this
            // hard-coded string.
            if (e.getMessage().indexOf("SASL authentication") == -1) {
                // doesn't look like a bad username/password, so retry
                Toast.makeText(_context, "Login failed", Toast.LENGTH_SHORT).show();
                maybeStartReconnect();
            } else {
                Toast.makeText(_context, "Invalid username or password", Toast.LENGTH_SHORT).show();
                stop();
            }
            return;
        }
        
        _connection = connection;
        onConnectionComplete();
    }

    private void onConnectionComplete() {
        
        Log.v(Tools.LOG_TAG, "connection established");
        _currentRetryCount = 0;
        PacketFilter filter = new MessageTypeFilter(Message.Type.chat);
        _packetListener = new PacketListener() {
            public void processPacket(Packet packet) {
                Message message = (Message) packet;

                if ( message.getFrom().toLowerCase().startsWith(_settings.mTo.toLowerCase() + "/") && 
                     !message.getFrom().equals(_connection.getUser())) {
                    if (message.getBody() != null) {
                        Intent intent = new Intent(ACTION_MESSAGE_RECEIVED);
                        intent.putExtra("message", message.getBody());
                        _context.sendBroadcast(intent);
                    }
                }
            }
        };
        _connection.addPacketListener(_packetListener, filter);
        updateStatus(CONNECTED);
        // Send welcome message
        if (_settings.notifyApplicationConnection) {
            send("Welcome to GTalkSMS " + Tools.getVersionName(_context, getClass()) + ". Send \"?\" for getting help");
        }
        Presence presence = new Presence(Presence.Type.available);
        presence.setStatus(_presenceMessage);
        presence.setPriority(24);                   
        _connection.sendPacket(presence);
    }
    
    /** returns true if the service is correctly connected */
    public boolean isConnected() {
        return    (_connection != null
                && _connection.isConnected()
                && _connection.isAuthenticated());
    }

    /** returns true if the service is correctly connected */
    public int getConnectionStatus() {
        return _status;
    }

    /** sends a message to the user */
    public void send(String message) {
        if (isConnected()) {
            Message msg = new Message(_settings.mTo, Message.Type.chat);
            msg.setBody(message);
            _connection.sendPacket(msg);
        }
    }
    
    public void setStatus(int batteryLevel) {
        _presenceMessage = "GTalkSMS - " + batteryLevel + "%";
        
        if (isConnected()) {
            Presence presence = new Presence(Presence.Type.available);
            presence.setStatus(_presenceMessage);
            presence.setPriority(24);                   
            _connection.sendPacket(presence);
        }
    }
}