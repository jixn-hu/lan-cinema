package com.lancinema.app;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.json.JSONArray;
import org.json.JSONObject;

final class PeerDiscovery {
  static final String TYPE = "_lanvideo._tcp.";
  final NsdManager manager;
  final String id;
  final ConcurrentHashMap<String, JSONObject> peers = new ConcurrentHashMap<>();
  final ConcurrentLinkedQueue<NsdServiceInfo> pending = new ConcurrentLinkedQueue<>();
  boolean resolving = false;
  WifiManager.MulticastLock multicast;
  volatile String error = "";

  PeerDiscovery(Context context, String id) {
    this.id = id;
    manager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
    WifiManager wifi =
        (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
    if (wifi != null) {
      multicast = wifi.createMulticastLock("lan-cinema-discovery");
      multicast.setReferenceCounted(false);
    }
  }

  void start(int port) {
    if (multicast != null && !multicast.isHeld()) multicast.acquire();
    NsdServiceInfo info = new NsdServiceInfo();
    info.setServiceName("LanVideo-" + id.substring(0, 8));
    info.setServiceType(TYPE);
    info.setPort(port);
    info.setAttribute("id", id);
    info.setAttribute("name", android.os.Build.MODEL);
    info.setAttribute("platform", "android");
    info.setAttribute("version", "1");
    manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration);
    manager.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, discovery);
  }

  final NsdManager.RegistrationListener registration =
      new NsdManager.RegistrationListener() {
        public void onServiceRegistered(NsdServiceInfo i) {}

        public void onServiceUnregistered(NsdServiceInfo i) {}

        public void onRegistrationFailed(NsdServiceInfo i, int code) {
          error = "自动广播失败（" + code + "），可手动连接";
        }

        public void onUnregistrationFailed(NsdServiceInfo i, int code) {}
      };
  final NsdManager.DiscoveryListener discovery =
      new NsdManager.DiscoveryListener() {
        public void onDiscoveryStarted(String t) {
          error = "";
        }

        public void onDiscoveryStopped(String t) {}

        public void onStartDiscoveryFailed(String t, int code) {
          error = "自动发现失败（" + code + "），可手动连接";
        }

        public void onStopDiscoveryFailed(String t, int code) {}

        public void onServiceFound(NsdServiceInfo info) {
          pending.add(info);
          resolveNext();
        }

        public void onServiceLost(NsdServiceInfo info) {
          peers.remove(info.getServiceName());
        }
      };

  // Old Android versions only allow one resolveService call at a time.
  synchronized void resolveNext() {
    if (resolving) return;
    NsdServiceInfo item = pending.poll();
    if (item == null) return;
    resolving = true;
    manager.resolveService(
        item,
        new NsdManager.ResolveListener() {
          public void onResolveFailed(NsdServiceInfo i, int code) {
            done();
          }

          public void onServiceResolved(NsdServiceInfo i) {
            try {
              String peerId = attribute(i, "id");
              if (!peerId.equals(id) && !peerId.isEmpty() && i.getHost() != null) {
                String host = i.getHost().getHostAddress();
                if (host.contains(":")) host = "[" + host + "]";
                peers.put(
                    i.getServiceName(),
                    new JSONObject()
                        .put("id", peerId)
                        .put("name", attribute(i, "name"))
                        .put("platform", attribute(i, "platform"))
                        .put("url", "http://" + host + ":" + i.getPort()));
              }
            } catch (Exception ignored) {
            } finally {
              done();
            }
          }

          void done() {
            synchronized (PeerDiscovery.this) {
              resolving = false;
            }
            resolveNext();
          }
        });
  }

  static String attribute(NsdServiceInfo info, String name) {
    byte[] value = info.getAttributes().get(name);
    return value == null ? "" : new String(value, StandardCharsets.UTF_8);
  }

  JSONArray devices() {
    return new JSONArray(peers.values());
  }

  static JSONArray addresses(int port) {
    JSONArray out = new JSONArray();
    try {
      for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
        if (!iface.isUp() || iface.isLoopback()) continue;
        for (InetAddress address : Collections.list(iface.getInetAddresses()))
          if (address instanceof Inet4Address && !address.isLinkLocalAddress())
            out.put("http://" + address.getHostAddress() + ":" + port);
      }
    } catch (Exception ignored) {
    }
    return out;
  }

  void stop() {
    try {
      manager.stopServiceDiscovery(discovery);
    } catch (Exception ignored) {
    }
    try {
      manager.unregisterService(registration);
    } catch (Exception ignored) {
    }
    if (multicast != null && multicast.isHeld()) multicast.release();
    peers.clear();
  }
}
