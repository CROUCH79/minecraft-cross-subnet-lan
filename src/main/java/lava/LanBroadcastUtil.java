package lava;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class LanBroadcastUtil {
   private static final Logger LOGGER = LogManager.getLogger();
   public static final String MULTICAST_GROUP = "224.0.2.60";
   public static final int LAN_PORT = 4445;
   private static final int MULTICAST_TTL = 32;
   private static volatile MulticastSocket multicastSocket;
   private static List<InetAddress> cachedTargets;
   private static long cacheMs;
   private static List<InetAddress> privateDirectedCache;
   private static List<InetAddress> localNetworkCache;
   private static long localNetworkCacheMs;
   private static final List<int[]> prefixNets = new ArrayList();
   private static int prefixUnicastCursor;
   private static long prefixNetCacheMs;
   private static volatile Thread tenSlash16Owner;

   private LanBroadcastUtil() {
   }

   public static void requestLocalNetworkAccess() {
      if (Minecraft.IS_RUNNING_ON_MAC) {
         Thread thread = new Thread(new LanBroadcastUtil$1(), "LocalNetworkAccess");
         thread.setDaemon(true);
         thread.start();
      }
   }

   private static void probeLocalNetwork() {
      try {
         InetAddress multicastAddr = InetAddress.getByName(MULTICAST_GROUP);
         sendMulticastPacket(new byte[]{0}, multicastAddr, LAN_PORT);
      } catch (Exception e) {
         LOGGER.warn("Failed to send multicast network probe", e);
      }

      for (String addr : getLocalIPv4Addresses()) {
         try {
            byte[] raw = InetAddress.getByName(addr).getAddress();
            if (raw.length == 4) {
               InetAddress upstream = InetAddress.getByAddress(new byte[]{raw[0], raw[1], raw[2], 1});
               Socket socket = new Socket();
               socket.connect(new InetSocketAddress(upstream, 80), 250);
               socket.close();
            }
         } catch (Exception ignored) {
         }

         try {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(addr, 9), 200);
            socket.close();
         } catch (Exception ignored) {
         }
      }
   }

   public static List<InetAddress> getAdvertisementTargets() {
      long now = System.currentTimeMillis();
      if (cachedTargets != null && now - cacheMs < 8000L) {
         return cachedTargets;
      }

      LinkedHashSet<InetAddress> targets = new LinkedHashSet<>();

      try {
         targets.add(InetAddress.getByName(MULTICAST_GROUP));
      } catch (Exception e) {
         LOGGER.warn("Could not resolve LAN multicast group", e);
      }

      try {
         targets.add(InetAddress.getByName("255.255.255.255"));
      } catch (Exception ignored) {
      }

      try {
         Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
         if (interfaces != null) {
            while (interfaces.hasMoreElements()) {
               NetworkInterface nic = interfaces.nextElement();
               try {
                  if (!nic.isUp() || nic.isLoopback()) {
                     continue;
                  }
               } catch (SocketException ignored) {
                  continue;
               }

               for (InterfaceAddress address : nic.getInterfaceAddresses()) {
                  InetAddress broadcast = address.getBroadcast();
                  if (broadcast != null) {
                     targets.add(broadcast);
                  }

                  InetAddress local = address.getAddress();
                  if (local instanceof Inet4Address && !local.isLoopbackAddress() && !local.isLinkLocalAddress()) {
                     try {
                        byte[] bytes = local.getAddress();
                        short prefixLen = address.getNetworkPrefixLength();
                        if (prefixLen > 0 && prefixLen < 32) {
                           int ip = (bytes[0] & 255) << 24 | (bytes[1] & 255) << 16 | (bytes[2] & 255) << 8 | (bytes[3] & 255);
                           int mask = -1 << (32 - prefixLen);
                           int network = ip | ~mask;
                           targets.add(InetAddress.getByAddress(new byte[]{
                              (byte) ((network >>> 24) & 255),
                              (byte) ((network >>> 16) & 255),
                              (byte) ((network >>> 8) & 255),
                              (byte) (network & 255)
                           }));
                        }

                        targets.add(InetAddress.getByAddress(new byte[]{bytes[0], bytes[1], bytes[2], -1}));
                     } catch (Exception ignored) {
                     }
                  }
               }
            }
         }
      } catch (SocketException e) {
         LOGGER.warn("Could not enumerate network interfaces for LAN broadcast", e);
      }

      addPrivateDirectedBroadcasts(targets);
      cachedTargets = new ArrayList<>(targets);
      cacheMs = now;
      return cachedTargets;
   }

   public static List<InetAddress> getLocalNetworkTargets() {
      long now = System.currentTimeMillis();
      if (localNetworkCache != null && now - localNetworkCacheMs < 5000L) {
         return localNetworkCache;
      }

      LinkedHashSet<InetAddress> targets = new LinkedHashSet<>();

      try {
         targets.add(InetAddress.getByName(MULTICAST_GROUP));
         targets.add(InetAddress.getByName("255.255.255.255"));
      } catch (Exception ignored) {
      }

      try {
         Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
         if (interfaces != null) {
            while (interfaces.hasMoreElements()) {
               NetworkInterface nic = interfaces.nextElement();
               try {
                  if (!nic.isUp() || nic.isLoopback()) {
                     continue;
                  }
               } catch (SocketException ignored) {
                  continue;
               }

               for (InterfaceAddress address : nic.getInterfaceAddresses()) {
                  InetAddress local = address.getAddress();
                  if (local instanceof Inet4Address && !local.isLoopbackAddress() && !local.isLinkLocalAddress()) {
                     InetAddress broadcast = address.getBroadcast();
                     if (broadcast != null) {
                        targets.add(broadcast);
                     }

                     try {
                        byte[] bytes = local.getAddress();
                        short prefixLen = address.getNetworkPrefixLength();
                        if (prefixLen > 0 && prefixLen < 32) {
                           int ip = (bytes[0] & 255) << 24 | (bytes[1] & 255) << 16 | (bytes[2] & 255) << 8 | (bytes[3] & 255);
                           int mask = prefixLen == 0 ? 0 : -1 << (32 - prefixLen);
                           int network = ip & mask;
                           int last = network | ~mask;
                           targets.add(InetAddress.getByAddress(new byte[]{
                              (byte) ((last >>> 24) & 255),
                              (byte) ((last >>> 16) & 255),
                              (byte) ((last >>> 8) & 255),
                              (byte) (last & 255)
                           }));
                        }
                     } catch (Exception ignored) {
                     }
                  }
               }
            }
         }
      } catch (SocketException ignored) {
      }

      localNetworkCache = new ArrayList<>(targets);
      localNetworkCacheMs = now;
      return localNetworkCache;
   }

   private static void addPrivateDirectedBroadcasts(Set<InetAddress> targets) {
      if (privateDirectedCache == null) {
         ArrayList<InetAddress> values = new ArrayList<>(4352);

         try {
            for (int i = 0; i <= 255; ++i) {
               values.add(InetAddress.getByAddress(new byte[]{-64, -88, (byte) i, -1}));
            }

            for (int v = 16; v <= 31; ++v) {
               for (int i = 0; i <= 255; ++i) {
                  values.add(InetAddress.getByAddress(new byte[]{-84, (byte) v, (byte) i, -1}));
               }
            }
         } catch (Exception ignored) {
         }

         privateDirectedCache = values;
      }

      targets.addAll(privateDirectedCache);

      try {
         int tick = (int) (System.currentTimeMillis() / 8000L % 256L);
         for (int i = 0; i <= 255; ++i) {
            targets.add(InetAddress.getByAddress(new byte[]{10, (byte) tick, (byte) i, -1}));
         }
      } catch (Exception ignored) {
      }
   }

   public static int sendPrefixUnicastBatch(DatagramSocket socket, byte[] payload, int count) {
      if (socket == null || socket.isClosed() || payload == null || count <= 0) {
         return 0;
      }

      sendMulticastPacket(payload);
      return 1;
   }

   private static void refreshPrefixNets() {
      long now = System.currentTimeMillis();
      if (prefixNets.isEmpty() || now - prefixNetCacheMs >= 8000L) {
         prefixNets.clear();

         try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) {
               return;
            }

            while (interfaces.hasMoreElements()) {
               NetworkInterface nic = interfaces.nextElement();
               try {
                  if (!nic.isUp() || nic.isLoopback()) {
                     continue;
                  }
               } catch (SocketException ignored) {
                  continue;
               }

               for (InterfaceAddress address : nic.getInterfaceAddresses()) {
                  InetAddress local = address.getAddress();
                  if (local instanceof Inet4Address && !local.isLoopbackAddress() && !local.isLinkLocalAddress()) {
                     byte[] bytes = local.getAddress();
                     short prefixLen = address.getNetworkPrefixLength();
                     int ip = (bytes[0] & 255) << 24 | (bytes[1] & 255) << 16 | (bytes[2] & 255) << 8 | (bytes[3] & 255);
                     short adjusted = prefixLen;
                     if ((bytes[0] & 255) == 10 && prefixLen > 16) {
                        adjusted = 16;
                     }

                     if (adjusted < 16) {
                        adjusted = 16;
                     }

                     if (adjusted < 32) {
                        int mask = -1 << (32 - adjusted);
                        int network = ip & mask;
                        int total = (1 << (32 - adjusted)) - 2;
                        if (total > 0) {
                           prefixNets.add(new int[]{network, adjusted, total, ip});
                        }

                        if ((bytes[0] & 255) == 10) {
                           int classA = ip & -65536;
                           prefixNets.add(new int[]{classA, 16, 65534, ip});
                        }
                     }
                  }
               }
            }
         } catch (SocketException ignored) {
         }

         prefixNetCacheMs = now;
      }
   }

   public static void cancelLocalWalk() {
      tenSlash16Owner = null;
   }

   public static void sendLocalTenSlash16(DatagramSocket socket, byte[] payload) {
      if (socket != null && !socket.isClosed() && payload != null) {
         sendMulticastPacket(payload);
      }
   }

   public static void sendLocalTenBroadcasts(DatagramSocket socket, byte[] payload) {
      if (socket != null && !socket.isClosed() && payload != null) {
         sendMulticastPacket(payload);
      }
   }

   private static void sendSlash16Broadcasts(DatagramSocket socket, byte[] payload, int start, Thread owner) {
      if (socket != null && !socket.isClosed() && payload != null) {
         sendMulticastPacket(payload);
      }
   }

   private static void sendIp(DatagramSocket socket, byte[] payload, int address) {
      try {
         InetAddress target = InetAddress.getByAddress(new byte[]{
            (byte) ((address >>> 24) & 255),
            (byte) ((address >>> 16) & 255),
            (byte) ((address >>> 8) & 255),
            (byte) (address & 255)
         });
         socket.send(new DatagramPacket(payload, payload.length, target, LAN_PORT));
      } catch (Exception ignored) {
      }
   }

   private static int[][] collectTenSlash16() {
      LinkedHashMap<Integer, Integer> map = new LinkedHashMap<>();

      try {
         Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
         if (interfaces == null) {
            return new int[0][0];
         }

         while (interfaces.hasMoreElements()) {
            NetworkInterface nic = interfaces.nextElement();
            try {
               if (!nic.isUp() || nic.isLoopback()) {
                  continue;
               }
            } catch (SocketException ignored) {
               continue;
            }

            String name = nic.getName() != null ? nic.getName().toLowerCase() : "";
            if (!name.startsWith("utun") && !name.startsWith("awdl") && !name.startsWith("llw") && !name.startsWith("bridge") && !name.startsWith("vmnet") && !name.startsWith("docker") && !name.startsWith("vnic") && !name.startsWith("tun") && !name.startsWith("tap")) {
               for (InterfaceAddress address : nic.getInterfaceAddresses()) {
                  InetAddress local = address.getAddress();
                  if (local instanceof Inet4Address && !local.isLoopbackAddress() && !local.isLinkLocalAddress()) {
                     byte[] bytes = local.getAddress();
                     if ((bytes[0] & 255) == 10) {
                        int ip = (bytes[0] & 255) << 24 | (bytes[1] & 255) << 16 | (bytes[2] & 255) << 8 | (bytes[3] & 255);
                        int network = ip & -65536;
                        map.put(network, ip);
                     }
                  }
               }
            }
         }
      } catch (SocketException ignored) {
      }

      int[][] result = new int[map.size()][2];
      int index = 0;
      for (Map.Entry<Integer, Integer> entry : map.entrySet()) {
         result[index][0] = entry.getKey();
         result[index][1] = entry.getValue();
         index++;
      }

      return result;
   }

   public static List<InetAddress> getUnicastScanTargets() {
      return new ArrayList<>();
   }

   public static List<String> getLocalIPv4Addresses() {
      ArrayList<String> values = new ArrayList<>();

      try {
         Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
         if (interfaces == null) {
            return values;
         }

         while (interfaces.hasMoreElements()) {
            NetworkInterface nic = interfaces.nextElement();
            try {
               if (!nic.isUp() || nic.isLoopback()) {
                  continue;
               }
            } catch (SocketException ignored) {
               continue;
            }

            Enumeration<InetAddress> addresses = nic.getInetAddresses();
            while (addresses.hasMoreElements()) {
               InetAddress address = addresses.nextElement();
               if (address instanceof Inet4Address && !address.isLoopbackAddress() && !address.isLinkLocalAddress() && !address.isAnyLocalAddress()) {
                  String host = address.getHostAddress();
                  if (!host.startsWith("169.254.") && !host.equals("0.0.0.0")) {
                     String name = nic.getName() != null ? nic.getName().toLowerCase() : "";
                     if (!name.startsWith("utun") && !name.startsWith("awdl") && !name.startsWith("llw") && !name.startsWith("bridge") && !name.startsWith("vmnet") && !name.startsWith("docker") && !name.startsWith("vnic") && !name.startsWith("tun") && !name.startsWith("tap")) {
                        values.add(host);
                     }
                  }
               }
            }
         }
      } catch (SocketException e) {
         LOGGER.warn("Could not list local IPv4 addresses", e);
      }

      return values;
   }

   public static String getPreferredLanIPv4() {
      List<String> values = getLocalIPv4Addresses();
      String preferred = null;
      byte weight = -1;

      for (String host : values) {
         byte score = 1;
         if (host.startsWith("192.168.")) {
            score = 30;
         } else if (host.startsWith("10.")) {
            score = 20;
         } else if (host.startsWith("172.")) {
            try {
               int second = Integer.parseInt(host.split("\\.")[1]);
               if (second >= 16 && second <= 31) {
                  score = 15;
               }
            } catch (Exception ignored) {
            }
         }

         if (score > weight) {
            weight = score;
            preferred = host;
         }
      }

      return preferred;
   }

   public static List<NetworkInterface> getUsableInterfaces() {
      ArrayList<NetworkInterface> values = new ArrayList<>();

      try {
         Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
         if (interfaces == null) {
            return values;
         }

         for (NetworkInterface nic : Collections.list(interfaces)) {
            try {
               if (nic.isUp() && !nic.isLoopback()) {
                  values.add(nic);
               }
            } catch (SocketException ignored) {
            }
         }
      } catch (SocketException ignored) {
      }

      return values;
   }

   public static void sendMulticastPacket(byte[] payload) {
      sendMulticastPacket(payload, InetAddress.getByName(MULTICAST_GROUP), LAN_PORT);
   }

   public static void sendMulticastPacket(byte[] payload, InetAddress target, int port) {
      if (payload == null) {
         return;
      }

      try {
         MulticastSocket socket = getOrCreateMulticastSocket();
         if (socket != null) {
            socket.send(new DatagramPacket(payload, payload.length, target, port));
         }
      } catch (Exception e) {
         LOGGER.warn("Failed to send multicast packet to {}:{}", target, port, e);
      }
   }

   private static synchronized MulticastSocket getOrCreateMulticastSocket() throws SocketException, UnknownHostException {
      if (multicastSocket == null || multicastSocket.isClosed()) {
         multicastSocket = new MulticastSocket();
         multicastSocket.setTimeToLive(MULTICAST_TTL);
         multicastSocket.setLoopbackMode(false);

         NetworkInterface selected = findPreferredInterface();
         if (selected != null) {
            multicastSocket.setNetworkInterface(selected);
         }
      }
      return multicastSocket;
   }

   private static NetworkInterface findPreferredInterface() {
      try {
         Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
         if (interfaces == null) {
            return null;
         }

         while (interfaces.hasMoreElements()) {
            NetworkInterface nic = interfaces.nextElement();
            try {
               if (!nic.isUp() || nic.isLoopback()) {
                  continue;
               }
               if (nic.supportsMulticast()) {
                  return nic;
               }
            } catch (SocketException ignored) {
            }
         }
      } catch (SocketException ignored) {
      }

      return null;
   }

   public static void closeMulticastSocket() {
      if (multicastSocket != null && !multicastSocket.isClosed()) {
         try {
            multicastSocket.close();
         } catch (Exception e) {
            LOGGER.warn("Error closing multicast socket", e);
         }
      }
   }

   static void access$000() {
      probeLocalNetwork();
   }
}
