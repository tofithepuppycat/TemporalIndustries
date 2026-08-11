package io.github.tofithepuppycat.temporalindustries.network.udp;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.Deflater;

/**
 * UDP server for bulk temporal data transfers.
 *
 * Lifecycle: started when the Minecraft server starts, stopped on server stop.
 * Listens on (Minecraft port + 1), default 25566.
 *
 * Usage: call TemporalUdpServer.send(playerUUID, payload) from any thread to
 * initiate an outbound bulk transfer. The payload should be pre-serialized and
 * will be ZLIB-compressed before fragmentation.
 *
 * The actual trigger device (global rollback) is not yet implemented; this class
 * provides the infrastructure for when it is.
 */
@EventBusSubscriber(modid = TemporalIndustries.MODID)
public final class TemporalUdpServer {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final int UDP_PORT_OFFSET = 1;
    private static final int RECEIVE_BUFFER = 2048;
    private static final int RETRANSMIT_DELAY_MS = 200;

    private static TemporalUdpServer INSTANCE;

    private DatagramSocket socket;
    private Thread receiveThread;
    private volatile boolean running;
    private final AtomicInteger nextSessionId = new AtomicInteger(1);

    // sessionId → outbound session
    private final Map<Integer, UdpSession> outboundSessions = new ConcurrentHashMap<>();
    // playerUUID → InetSocketAddress (populated on HANDSHAKE_REQ)
    private final Map<UUID, InetSocketAddress> playerAddresses = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // Server lifecycle

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        int minecraftPort = event.getServer().getPort();
        int udpPort = minecraftPort + UDP_PORT_OFFSET;
        INSTANCE = new TemporalUdpServer();
        INSTANCE.start(udpPort);
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        if (INSTANCE != null) {
            INSTANCE.stop();
            INSTANCE = null;
        }
    }

    private void start(int port) {
        try {
            socket = new DatagramSocket(port);
            running = true;
            receiveThread = new Thread(this::receiveLoop, "TemporalUDP-Server");
            receiveThread.setDaemon(true);
            receiveThread.start();
            LOGGER.info("[TemporalIndustries] UDP server listening on port {}", port);
        } catch (IOException e) {
            LOGGER.error("[TemporalIndustries] Failed to open UDP socket on port {}: {}", port, e.getMessage());
        }
    }

    private void stop() {
        running = false;
        if (socket != null && !socket.isClosed()) socket.close();
        if (receiveThread != null) {
            try { receiveThread.join(500); } catch (InterruptedException ignored) {}
        }
    }

    // -------------------------------------------------------------------------
    // Public API

    /**
     * Compresses and sends payload to the given player via UDP.
     * The client must have already performed the handshake (i.e. opened the GUI that
     * triggers a UDP handshake request) before this is called.
     */
    public static void send(UUID playerUUID, byte[] rawPayload) {
        if (INSTANCE == null) return;
        INSTANCE.sendInternal(playerUUID, rawPayload);
    }

    private void sendInternal(UUID playerUUID, byte[] rawPayload) {
        InetSocketAddress addr = playerAddresses.get(playerUUID);
        if (addr == null) {
            LOGGER.warn("[TemporalIndustries] No UDP address for player {}", playerUUID);
            return;
        }

        byte[] compressed = compress(rawPayload);
        int sessionId = nextSessionId.getAndIncrement();
        UdpSession session = UdpSession.forSend(sessionId, compressed, addr);
        outboundSessions.put(sessionId, session);

        try {
            byte[] ack = UdpFrame.buildHandshakeAck(sessionId, session.getTotalFragments(), session.payloadLength());
            send(ack, addr);

            // Send all fragments; retransmit loop handles losses via NACK
            for (int i = 0; i < session.getTotalFragments(); i++) {
                send(session.buildFragment(i), addr);
            }
        } catch (IOException e) {
            LOGGER.error("[TemporalIndustries] Error sending UDP session {}: {}", sessionId, e.getMessage());
            outboundSessions.remove(sessionId);
        }
    }

    // -------------------------------------------------------------------------
    // Receive loop

    private void receiveLoop() {
        byte[] buf = new byte[RECEIVE_BUFFER];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        while (running) {
            try {
                socket.receive(packet);
                byte[] data = new byte[packet.getLength()];
                System.arraycopy(packet.getData(), packet.getOffset(), data, 0, data.length);
                InetSocketAddress sender = (InetSocketAddress) packet.getSocketAddress();
                handleFrame(data, sender);
            } catch (IOException e) {
                if (running) LOGGER.warn("[TemporalIndustries] UDP receive error: {}", e.getMessage());
            }
        }
    }

    private void handleFrame(byte[] data, InetSocketAddress sender) throws IOException {
        if (data.length < 1) return;
        byte type = UdpFrame.frameType(data);
        ByteBuffer payload = UdpFrame.wrap(data);

        switch (type) {
            case UdpFrame.TYPE_HANDSHAKE_REQ -> {
                if (data.length < 17) return;
                long msb = payload.getLong();
                long lsb = payload.getLong();
                UUID playerUUID = new UUID(msb, lsb);
                playerAddresses.put(playerUUID, sender);
                LOGGER.debug("[TemporalIndustries] UDP handshake from player {}", playerUUID);
            }
            case UdpFrame.TYPE_ACK -> {
                if (data.length < 9) return;
                int sessionId = payload.getInt();
                int fragIndex = payload.getInt();
                UdpSession session = outboundSessions.get(sessionId);
                if (session == null) return;
                session.markAcked(fragIndex);
                if (session.isFullyAcked()) {
                    byte[] done = UdpFrame.buildTransferDone(sessionId, UdpFrame.crc32(new byte[0]));
                    send(done, session.getClientAddr());
                    outboundSessions.remove(sessionId);
                }
            }
            case UdpFrame.TYPE_NACK -> {
                if (data.length < 9) return;
                int sessionId = payload.getInt();
                int fragIndex = payload.getInt();
                UdpSession session = outboundSessions.get(sessionId);
                if (session != null) {
                    send(session.buildFragment(fragIndex), session.getClientAddr());
                }
            }
            case UdpFrame.TYPE_DONE_ACK -> {
                if (data.length < 5) return;
                int sessionId = payload.getInt();
                outboundSessions.remove(sessionId);
            }
        }
    }

    private void send(byte[] data, InetSocketAddress addr) throws IOException {
        if (socket == null || socket.isClosed()) return;
        DatagramPacket packet = new DatagramPacket(data, data.length, addr);
        socket.send(packet);
    }

    // -------------------------------------------------------------------------
    // Compression

    private static byte[] compress(byte[] input) {
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        deflater.setInput(input);
        deflater.finish();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(input.length);
        byte[] buf = new byte[4096];
        while (!deflater.finished()) {
            int count = deflater.deflate(buf);
            out.write(buf, 0, count);
        }
        deflater.end();
        return out.toByteArray();
    }
}
