import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

public class BedrockPing {

    // RakNet magic bytes — всегда одинаковые
    private static final byte[] RAKNET_MAGIC = {
        (byte)0x00, (byte)0xff, (byte)0xff, (byte)0x00,
        (byte)0xfe, (byte)0xfe, (byte)0xfe, (byte)0xfe,
        (byte)0xfd, (byte)0xfd, (byte)0xfd, (byte)0xfd,
        (byte)0x12, (byte)0x34, (byte)0x56, (byte)0x78
    };

    private static final byte ID_UNCONNECTED_PING = 0x01;
    private static final byte ID_UNCONNECTED_PONG = 0x1C;
    private static final int  BUFFER_SIZE         = 2048;
    private static final int  TIMEOUT_MS          = 5000;

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "play.example.com";
        int    port = args.length > 1 ? Integer.parseInt(args[1]) : 19132;

        System.out.printf("Pinging Bedrock server %s:%d...%n", host, port);

        PongData pong = ping(host, port);

        if (pong == null) {
            System.out.println("No response (server offline or unreachable)");
            return;
        }

        System.out.println("=== Server Info ===");
        System.out.println("Latency   : " + pong.latencyMs + " ms");
        System.out.println("Server ID : " + pong.serverId);
        System.out.println("MOTD      : " + pong.motd);
        System.out.println("Version   : " + pong.version);
        System.out.println("Protocol  : " + pong.protocol);
        System.out.println("Players   : " + pong.onlinePlayers + "/" + pong.maxPlayers);
        System.out.println("Map       : " + pong.mapName);
        System.out.println("Gamemode  : " + pong.gamemode);
    }

    // ─── Ping ────────────────────────────────────────────────────

    public static PongData ping(String host, int port) throws Exception {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(TIMEOUT_MS);

            byte[] packet = buildPingPacket();
            InetAddress address = InetAddress.getByName(host);

            DatagramPacket outPacket = new DatagramPacket(
                packet, packet.length, address, port
            );

            long sendTime = System.currentTimeMillis();
            socket.send(outPacket);

            // Ждём Pong
            byte[] buf = new byte[BUFFER_SIZE];
            DatagramPacket inPacket = new DatagramPacket(buf, buf.length);

            try {
                socket.receive(inPacket);
            } catch (SocketTimeoutException e) {
                return null;
            }

            long latency = System.currentTimeMillis() - sendTime;
            byte[] data  = Arrays.copyOf(inPacket.getData(), inPacket.getLength());

            return parsePong(data, latency);
        }
    }

    // ─── Build Ping Packet ────────────────────────────────────────

    private static byte[] buildPingPacket() {
        // Packet ID (1) + Time (8) + Magic (16) + Client GUID (8) = 33 bytes
        ByteBuffer buf = ByteBuffer.allocate(33);
        buf.order(ByteOrder.BIG_ENDIAN);

        buf.put(ID_UNCONNECTED_PING);
        buf.putLong(System.currentTimeMillis());  // timestamp
        buf.put(RAKNET_MAGIC);
        buf.putLong(new java.util.Random().nextLong()); // случайный GUID клиента

        return buf.array();
    }

    // ─── Parse Pong ──────────────────────────────────────────────

    private static PongData parsePong(byte[] data, long latencyMs) {
        if (data.length < 35 || data[0] != ID_UNCONNECTED_PONG) {
            System.err.println("Invalid pong packet, ID: 0x" +
                String.format("%02X", data[0]));
            return null;
        }

        ByteBuffer buf = ByteBuffer.wrap(data);
        buf.order(ByteOrder.BIG_ENDIAN);

        buf.get();                    // packet ID
        long pingTime  = buf.getLong(); // время из нашего пинга
        long serverId  = buf.getLong(); // GUID сервера
        buf.position(buf.position() + 16); // пропускаем magic

        // Длина строки MOTD (short, big-endian)
        short strLen = buf.getShort();
        byte[] strBytes = new byte[strLen];
        buf.get(strBytes);
        String raw = new String(strBytes);

        return PongData.fromRawMotd(raw, serverId, latencyMs);
    }

    // ─── Data class ───────────────────────────────────────────────

    public static class PongData {
        public long   serverId;
        public long   latencyMs;
        public String motd;
        public String version;
        public int    protocol;
        public int    onlinePlayers;
        public int    maxPlayers;
        public String mapName;
        public String gamemode;

        // Bedrock MOTD формат:
        // MCPE;MOTD;protocol;version;online;max;serverGUID;mapName;gamemode;...
        public static PongData fromRawMotd(String raw, long serverId, long latencyMs) {
            PongData d    = new PongData();
            d.serverId    = serverId;
            d.latencyMs   = latencyMs;

            String[] parts = raw.split(";");

            d.motd          = parts.length > 1  ? parts[1]  : "Unknown";
            d.protocol      = parts.length > 2  ? parseIntSafe(parts[2]) : -1;
            d.version       = parts.length > 3  ? parts[3]  : "Unknown";
            d.onlinePlayers = parts.length > 4  ? parseIntSafe(parts[4]) : 0;
            d.maxPlayers    = parts.length > 5  ? parseIntSafe(parts[5]) : 0;
            d.mapName       = parts.length > 7  ? parts[7]  : "Unknown";
            d.gamemode      = parts.length > 8  ? parts[8]  : "Unknown";

            return d;
        }

        private static int parseIntSafe(String s) {
            try { return Integer.parseInt(s.trim()); }
            catch (NumberFormatException e) { return 0; }
        }
    }
}
