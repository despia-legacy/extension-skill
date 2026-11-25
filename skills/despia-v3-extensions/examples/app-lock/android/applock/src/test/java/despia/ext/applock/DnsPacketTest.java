package despia.ext.applock;

import org.junit.Test;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

public final class DnsPacketTest {
    @Test public void blockedReplyIsNxdomainWithReversedEndpointsAndValidChecksums() throws Exception {
        byte[] packet = query("www.example.com"); DnsPacket.Request request = DnsPacket.parse(packet, packet.length);
        assertEquals("www.example.com", request.hostname); byte[] reply = request.blocked();
        assertEquals(3, reply[31] & 15); assertEquals(0, DnsPacket.checksum(reply, 0, 20, 0)); assertEquals(53, DnsPacket.u16(reply, 20)); assertEquals(53000, DnsPacket.u16(reply, 22));
        assertArrayEquals(Arrays.copyOfRange(packet, 16, 20), Arrays.copyOfRange(reply, 12, 16));
        assertEquals(0, DnsPacket.checksum(reply, 20, reply.length - 20, pseudo(reply)));
    }
    @Test public void failureReplyIsServfail() throws Exception { byte[] query = query("example.com"); byte[] reply = DnsPacket.parse(query, query.length).failure(); assertEquals(2, reply[31] & 15); }
    @Test public void refusesBadIpv4UdpLengthAndFragmentation() throws Exception {
        byte[] valid = query("example.com"), badLength = valid.clone(); badLength[25]--; checksum(badLength); assertThrows(IllegalArgumentException.class, () -> DnsPacket.parse(badLength, badLength.length));
        byte[] fragment = valid.clone(); fragment[6] = 0x20; checksum(fragment); assertThrows(IllegalArgumentException.class, () -> DnsPacket.parse(fragment, fragment.length));
        byte[] badChecksum = valid.clone(); badChecksum[8]--; assertThrows(IllegalArgumentException.class, () -> DnsPacket.parse(badChecksum, badChecksum.length));
    }
    @Test public void refusesWrongDestinationAndUnexpectedTransport() throws Exception {
        byte[] wrong = query("example.com"); wrong[19] = 3; checksum(wrong); assertThrows(IllegalArgumentException.class, () -> DnsPacket.parse(wrong, wrong.length));
        byte[] tcp = query("example.com"); tcp[9] = 6; checksum(tcp); assertThrows(IllegalArgumentException.class, () -> DnsPacket.parse(tcp, tcp.length));
    }
    @Test public void detectsCompressionPointerCyclesAndTruncatedLabels() throws Exception {
        byte[] cyclic = query("example.com"); cyclic[40] = (byte)0xc0; cyclic[41] = 12; checksum(cyclic); assertThrows(IllegalArgumentException.class, () -> DnsPacket.parse(cyclic, cyclic.length));
        byte[] truncated = query("example.com"); truncated[40] = 63; checksum(truncated); assertThrows(IllegalArgumentException.class, () -> DnsPacket.parse(truncated, truncated.length));
    }
    @Test public void compressedQuestionReplyIsExpandedWithoutDanglingPointers() throws Exception {
        byte[] original = query("example.com"); byte[] packet = Arrays.copyOf(original, original.length + 6);
        // A valid forward pointer is unusual but ensures the response rebuilds the question.
        packet[40] = (byte)0xc0; packet[41] = 18;
        packet[42] = 0; packet[43] = 1; packet[44] = 0; packet[45] = 1;
        byte[] name = Arrays.copyOfRange(original, 40, original.length - 4); System.arraycopy(name, 0, packet, 46, name.length);
        DnsPacket.put16(packet, 2, packet.length); DnsPacket.put16(packet, 24, packet.length - 20); checksum(packet);
        DnsPacket.Request request = DnsPacket.parse(packet, packet.length); byte[] reply = request.blocked();
        assertEquals(7, reply[40]); assertEquals("example.com", request.hostname); assertEquals(original.length, reply.length);
    }
    @Test public void refusesResponseTransactionAndQuestionMismatch() throws Exception {
        byte[] packet = query("example.com"); DnsPacket.Request request = DnsPacket.parse(packet, packet.length); byte[] reply = Arrays.copyOfRange(packet, 28, packet.length); reply[2] |= (byte)0x80;
        request.response(reply); byte[] wrong = reply.clone(); wrong[0]++; assertThrows(IllegalArgumentException.class, () -> request.response(wrong));
        byte[] other = query("example.net"); byte[] otherDns = Arrays.copyOfRange(other, 28, other.length); otherDns[2] |= (byte)0x80; assertThrows(IllegalArgumentException.class, () -> request.response(otherDns));
    }
    private static byte[] query(String name) throws Exception {
        ByteArrayOutputStream wire = new ByteArrayOutputStream(); wire.write(new byte[]{0x11,0x21,1,0,0,1,0,0,0,0,0,0});
        for (String label : name.split("\\.")) { byte[] bytes = label.getBytes("US-ASCII"); wire.write(bytes.length); wire.write(bytes); } wire.write(new byte[]{0,0,1,0,1});
        byte[] packet = new byte[28 + wire.size()]; packet[0] = 0x45; packet[8] = 64; packet[9] = 17;
        packet[12] = 10; packet[13] = (byte)231; packet[15] = 1; packet[16] = 10; packet[17] = (byte)231; packet[19] = 2;
        DnsPacket.put16(packet, 2, packet.length); DnsPacket.put16(packet, 20, 53000); DnsPacket.put16(packet, 22, 53); DnsPacket.put16(packet, 24, wire.size() + 8); System.arraycopy(wire.toByteArray(), 0, packet, 28, wire.size()); checksum(packet); return packet;
    }
    private static void checksum(byte[] packet) { packet[10] = packet[11] = 0; DnsPacket.put16(packet, 10, DnsPacket.checksum(packet, 0, 20, 0)); }
    private static int pseudo(byte[] packet) { int sum = 17 + packet.length - 20; for (int index = 12; index < 20; index += 2) sum += DnsPacket.u16(packet, index); return sum; }
}
