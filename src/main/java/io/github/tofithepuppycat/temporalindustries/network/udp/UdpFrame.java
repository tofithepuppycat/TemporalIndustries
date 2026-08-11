package io.github.tofithepuppycat.temporalindustries.network.udp;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Wire format for the temporal UDP bulk-transfer protocol.
 *
 * All multi-byte fields are big-endian. Frame layout:
 *
 *   HANDSHAKE_REQ  [type:1][playerUUID:16]
 *   HANDSHAKE_ACK  [type:1][sessionId:4][totalFragments:4][totalBytes:8]
 *   DATA_FRAG      [type:1][sessionId:4][fragIndex:4][totalFragments:4][payloadLen:2][payload:N]
 *   ACK            [type:1][sessionId:4][fragIndex:4]
 *   NACK           [type:1][sessionId:4][fragIndex:4]
 *   TRANSFER_DONE  [type:1][sessionId:4][crc32:4]
 *   DONE_ACK       [type:1][sessionId:4]
 *
 * Maximum UDP payload (safe MTU): 1400 bytes.
 * Maximum fragment data payload: MAX_FRAG_PAYLOAD bytes.
 */
public final class UdpFrame {
    public static final byte TYPE_HANDSHAKE_REQ  = 1;
    public static final byte TYPE_HANDSHAKE_ACK  = 2;
    public static final byte TYPE_DATA_FRAG      = 3;
    public static final byte TYPE_ACK            = 4;
    public static final byte TYPE_NACK           = 5;
    public static final byte TYPE_TRANSFER_DONE  = 6;
    public static final byte TYPE_DONE_ACK       = 7;

    public static final int MAX_FRAG_PAYLOAD = 1380;

    private UdpFrame() {}

    // -------------------------------------------------------------------------
    // Builders

    public static byte[] buildHandshakeReq(java.util.UUID playerUUID) {
        ByteBuffer buf = ByteBuffer.allocate(17).order(ByteOrder.BIG_ENDIAN);
        buf.put(TYPE_HANDSHAKE_REQ);
        buf.putLong(playerUUID.getMostSignificantBits());
        buf.putLong(playerUUID.getLeastSignificantBits());
        return buf.array();
    }

    public static byte[] buildHandshakeAck(int sessionId, int totalFragments, long totalBytes) {
        ByteBuffer buf = ByteBuffer.allocate(17).order(ByteOrder.BIG_ENDIAN);
        buf.put(TYPE_HANDSHAKE_ACK);
        buf.putInt(sessionId);
        buf.putInt(totalFragments);
        buf.putLong(totalBytes);
        return buf.array();
    }

    public static byte[] buildDataFrag(int sessionId, int fragIndex, int totalFragments,
                                       byte[] payload, int offset, int length) {
        ByteBuffer buf = ByteBuffer.allocate(1 + 4 + 4 + 4 + 2 + length).order(ByteOrder.BIG_ENDIAN);
        buf.put(TYPE_DATA_FRAG);
        buf.putInt(sessionId);
        buf.putInt(fragIndex);
        buf.putInt(totalFragments);
        buf.putShort((short) length);
        buf.put(payload, offset, length);
        return buf.array();
    }

    public static byte[] buildAck(int sessionId, int fragIndex) {
        ByteBuffer buf = ByteBuffer.allocate(9).order(ByteOrder.BIG_ENDIAN);
        buf.put(TYPE_ACK);
        buf.putInt(sessionId);
        buf.putInt(fragIndex);
        return buf.array();
    }

    public static byte[] buildNack(int sessionId, int fragIndex) {
        ByteBuffer buf = ByteBuffer.allocate(9).order(ByteOrder.BIG_ENDIAN);
        buf.put(TYPE_NACK);
        buf.putInt(sessionId);
        buf.putInt(fragIndex);
        return buf.array();
    }

    public static byte[] buildTransferDone(int sessionId, int crc32) {
        ByteBuffer buf = ByteBuffer.allocate(9).order(ByteOrder.BIG_ENDIAN);
        buf.put(TYPE_TRANSFER_DONE);
        buf.putInt(sessionId);
        buf.putInt(crc32);
        return buf.array();
    }

    public static byte[] buildDoneAck(int sessionId) {
        ByteBuffer buf = ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN);
        buf.put(TYPE_DONE_ACK);
        buf.putInt(sessionId);
        return buf.array();
    }

    // -------------------------------------------------------------------------
    // Readers

    public static byte frameType(byte[] data) {
        return data[0];
    }

    public static ByteBuffer wrap(byte[] data) {
        return ByteBuffer.wrap(data, 1, data.length - 1).order(ByteOrder.BIG_ENDIAN);
    }

    // CRC-32 over raw bytes (java.util.zip.CRC32)
    public static int crc32(byte[] data) {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(data);
        return (int) crc.getValue();
    }
}
