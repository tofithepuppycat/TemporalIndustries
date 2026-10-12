package io.github.tofithepuppycat.temporalindustries.network.udp;

import org.jetbrains.annotations.Nullable;

import java.net.InetSocketAddress;
import java.util.BitSet;

/** Tracks one in-flight bulk-transfer session. */
public class UdpSession {
    private final int sessionId;
    private final int totalFragments;

    // --- server-side (outbound) ---
    @Nullable private final byte[] payload;
    @Nullable private final InetSocketAddress clientAddr;
    private final BitSet acked;

    // --- client-side (inbound) ---
    @Nullable private final byte[][] fragments;
    private int receivedCount;

    /** Construct a server-side (outbound) session. */
    public static UdpSession forSend(int sessionId, byte[] payload, InetSocketAddress clientAddr) {
        int total = (payload.length + UdpFrame.MAX_FRAG_PAYLOAD - 1) / UdpFrame.MAX_FRAG_PAYLOAD;
        return new UdpSession(sessionId, total, payload, clientAddr, null);
    }

    /** Construct a client-side (inbound) session. */
    public static UdpSession forReceive(int sessionId, int totalFragments) {
        return new UdpSession(sessionId, totalFragments, null, null, new byte[totalFragments][]);
    }

    private UdpSession(int sessionId, int totalFragments,
                       @Nullable byte[] payload,
                       @Nullable InetSocketAddress clientAddr,
                       @Nullable byte[][] fragments) {
        this.sessionId = sessionId;
        this.totalFragments = totalFragments;
        this.payload = payload;
        this.clientAddr = clientAddr;
        this.acked = new BitSet(totalFragments);
        this.fragments = fragments;
    }

    public int getSessionId() { return sessionId; }
    public int getTotalFragments() { return totalFragments; }
    @Nullable public InetSocketAddress getClientAddr() { return clientAddr; }

    /** Returns the raw bytes for a single outbound fragment. */
    public byte[] buildFragment(int fragIndex) {
        if (payload == null) throw new IllegalStateException("Not a send session");
        int offset = fragIndex * UdpFrame.MAX_FRAG_PAYLOAD;
        int length = Math.min(UdpFrame.MAX_FRAG_PAYLOAD, payload.length - offset);
        return UdpFrame.buildDataFrag(sessionId, fragIndex, totalFragments, payload, offset, length);
    }

    public void markAcked(int fragIndex) {
        acked.set(fragIndex);
    }

    public boolean isFullyAcked() {
        return acked.cardinality() == totalFragments;
    }

    public long payloadLength() {
        return payload == null ? 0 : payload.length;
    }

    /** Stores an inbound fragment. */
    public boolean receiveFragment(int fragIndex, byte[] data) {
        if (fragments == null) throw new IllegalStateException("Not a receive session");
        if (fragments[fragIndex] == null) {
            fragments[fragIndex] = data;
            receivedCount++;
        }
        return receivedCount == totalFragments;
    }

    public boolean isComplete() { return receivedCount == totalFragments; }

    /** Reassembles all fragments into a single byte array. */
    @Nullable
    public byte[] reassemble() {
        if (fragments == null || !isComplete()) return null;
        int total = 0;
        for (byte[] frag : fragments) total += frag.length;
        byte[] out = new byte[total];
        int pos = 0;
        for (byte[] frag : fragments) {
            System.arraycopy(frag, 0, out, pos, frag.length);
            pos += frag.length;
        }
        return out;
    }
}
