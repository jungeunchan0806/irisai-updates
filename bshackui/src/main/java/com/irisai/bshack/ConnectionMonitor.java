package com.irisai.bshack;

import android.app.Application;
import android.net.*;
import android.os.*;
import org.json.JSONObject;

/** Observes this app's default network. No pings, traffic interception or reconnect flood. */
final class ConnectionMonitor {
    private String state="unknown";
    private Network current;
    private long generation;
    private final Runnable restored;
    ConnectionMonitor(Application app,Runnable restored){
        this.restored=restored;
        try{
            ConnectivityManager manager=(ConnectivityManager)app.getSystemService(Application.CONNECTIVITY_SERVICE);
            if(manager==null)return;
            current=manager.getActiveNetwork();
            change(current==null?"offline":classify(manager.getNetworkCapabilities(current)));
            manager.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback(){
                @Override public void onAvailable(Network network){
                    if(!network.equals(current)){current=network;generation++;change("unknown");}
                }
                @Override public void onCapabilitiesChanged(Network network,NetworkCapabilities capabilities){
                    if(network.equals(current))change(classify(capabilities));
                }
                @Override public void onLost(Network network){
                    // A late loss callback for old Wi-Fi must not erase the new mobile network.
                    if(network.equals(current)){current=null;change("offline");}
                }
                @Override public void onUnavailable(){if(current==null)change("offline");}
            },new Handler(Looper.getMainLooper()));
        }catch(RuntimeException e){state="unknown";}
    }
    private static String classify(NetworkCapabilities capabilities){
        if(capabilities==null)return "unknown";
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            &&capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)?"online":"offline";
    }
    private void change(String next){
        if(next.equals(state))return;
        boolean recovered=next.equals("online")&&!state.equals("online");
        state=next;generation++;
        if(recovered)new Handler(Looper.getMainLooper()).post(restored);
    }
    String snapshot(boolean focused){
        try{return new JSONObject().put("network",state).put("focused",focused).put("generation",generation).toString();}
        catch(Exception e){return "{\"network\":\"unknown\",\"focused\":false,\"generation\":0}";}
    }
}
