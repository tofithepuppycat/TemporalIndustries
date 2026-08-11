package io.github.tofithepuppycat.temporalindustries.network.udp;

import io.github.tofithepuppycat.temporalindustries.TemporalIndustries;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
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
import java.util.function.Consumer;
import java.util.zip.Inflater;

/**
 * UDP client for receiving bulk temporal data from the server.
 *
 * The server UDP port is communicated via the normal Minecraft TCP channel
 * (a future ServerUdpInfoPacket S2C packet). Once connected, the client sends
 * a HANDSHAKE_REQ so the server knows its address, then listens for DATA_FRAGs.
 *
 * On full reassembly, the inflated payload is handed to a registered handler
 * (e.g., the global rollback applicator).
 */
@EventBusSubscriber(modid = TemporalIndustries.MODID, value = Dist.CLIENT)
public final class TemporalUdpClient {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final int RECEIVE_BUFFER = 2048;

    private static TemporalUdpClient INSTANCE;

    private DatagramSocket socket;
    private Thread receiveThread;
    private volatile boolean running;
    private InetSocketAddress serverAddr;

    // sessionId → inbound session
    private final Map<Integer, UdpSession> inboundSessions = new ConcurrentHashMap<>();
    // Registered handler: called with decompressed payload bytes when a transfer completes
    private static volatile Consumer<byte[]> payloadHandler;

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        INSTANCE = new TemporalUdpClient();
    }

    // -------------------------------------------------------------------------
    // Public API

    /**
     * Connects to the server's UDP endpoint. Should be called after the player
     * connects and the server communicates its UDP port via TCP.
     */
    public static void connect(String serverHost, int udpPort) {
        if (INSTANCE == null) return;
        INSTANCE.connectInternal(serverHost, udpPort);
    }

    public static void disconnect() {
        if (INSTANCE == null) return;
        INSTANCE.disconnectInternal();
    }

    /** Registers the callback that receives fully reassembled + decompressed payloads. */
    public static void setPayloadHandler(Consumer<byte[]> handler) {
        payloadHandler = handler;
    }

    // -------------------------------------------------------------------------
    // Internal

    private void connectInternal(String serverHost, int udpPort) {
        disconnectInternal();
        try {
            socket = new DatagramSocket();
            serverAddr = new InetSocketAddress(serverHost, udpPort);
            running = true;
            receiveThread = new Thread(this::receiveLoop, "TemporalUDP-Client");
            receiveThread.setDaemon(true);
            receiveThread.start();

            sendHandshake();
            LOGGER.info("[TemporalIndustries] UDP client connected to {}:{}", serverHost, udpPort);
        } catch (IOException e) {
            LOGGER.error("[TemporalIndustries] UDP client connect failed: {}", e.getMessage());
        }
    }

    private void disconnectInternal() {
        running = false;
        if (socket != null && !socket.isClosed()) socket.close();
        if (receiveThread != null) {
            try { receiveThread.join(300); } catch (InterruptedException ignored) {}
        }
        inboundSessions.clear();
    }

    private void sendHandshake() throws IOException {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        UUID playerId = mc.player.getUUID();
        byte[] frame = UdpFrame.buildHandshakeReq(playerId);
        socket.send(new DatagramPacket(frame, frame.length, serverAddr));
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
                handleFrame(data);
            } catch (IOException e) {
                if (running) LOGGER.warn("[TemporalIndustries] UDP client receive error: {}", e.getMessage());
            }
        }
    }

    private void handleFrame(byte[] data) throws IOException {
        if (data.length < 1) return;
        byte type = UdpFrame.frameType(data);
        ByteBuffer payload = UdpFrame.wrap(data);

        switch (type) {
            case UdpFrame.TYPE_HANDSHAKE_ACK -> {
                if (data.length < 17) return;
                int sessionId = payload.getInt();
                int totalFragments = payload.getInt();
                // totalBytes ignored for now
                inboundSessions.put(sessionId, UdpSession.forReceive(sessionId, totalFragments));
                LOGGER.debug("[TemporalIndustries] UDP: inbound session {} ({} frags)", sessionId, totalFragments);
            }
            case UdpFrame.TYPE_DATA_FRAG -> {
                if (data.length < 15) return;
                int sessionId = payload.getInt();
                int fragIndex = payload.getInt();
                payload.getInt(); // totalFragments (already known from ACK)
                int fragLen = payload.getShort() & 0xFFFF;
                byte[] fragData = new byte[fragLen];
                payload.get(fragData);

                UdpSession session = inboundSessions.get(sessionId);
                if (session == null) return;

                // Send per-fragment ACK
                byte[] ack = UdpFrame.buildAck(sessionId, fragIndex);
                socket.send(new DatagramPacket(ack, ack.length, serverAddr));

                if (session.receiveFragment(fragIndex, fragData)) {
                    onSessionComplete(session);
                }
            }
            case UdpFrame.TYPE_TRANSFER_DONE -> {
                if (data.length < 9) return;
                int sessionId = payload.getInt();
                inboundSessions.remove(sessionId);
                byte[] doneAck = UdpFrame.buildDoneAck(sessionId);
                socket.send(new DatagramPacket(doneAck, doneAck.length, serverAddr));
            }
        }
    }

    private void onSessionComplete(UdpSession session) {
        inboundSessions.remove(session.getSessionId());
        byte[] raw = session.reassemble();
        if (raw == null) return;

        byte[] decompressed = decompress(raw);
        if (decompressed == null) return;

        Consumer<byte[]> handler = payloadHandler;
        if (handler != null) {
            Minecraft.getInstance().execute(() -> handler.accept(decompressed));
        }
    }

    // -------------------------------------------------------------------------
    // Decompression

    private static byte[] decompress(byte[] compressed) {
        try {
            Inflater inflater = new Inflater();
            inflater.setInput(compressed);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(compressed.length * 4);
            byte[] buf = new byte[4096];
            while (!inflater.finished()) {
                int count = inflater.inflate(buf);
                out.write(buf, 0, count);
            }
            inflater.end();
            return out.toByteArray();
        } catch (Exception e) {
            LOGGER.error("[TemporalIndustries] UDP decompression failed: {}", e.getMessage());
            return null;
        }
    }
}
