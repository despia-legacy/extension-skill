package despia.ext.applock;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class DnsPacket {
    private DnsPacket() {}
    public static final class Request {
        public final byte[] ip, dns;
        public final String hostname;
        private final byte[] questionWire;
        private Request(byte[] ip, byte[] dns, String hostname, byte[] questionWire) { this.ip = ip; this.dns = dns; this.hostname = hostname; this.questionWire = questionWire; }
        public byte[] blocked() {
            byte[] answer = Arrays.copyOf(dns, 12 + questionWire.length);
            System.arraycopy(questionWire, 0, answer, 12, questionWire.length);
            answer[2] = (byte)(0x80 | (dns[2] & 1)); answer[3] = (byte)0x83;
            for (int index = 6; index < 12; index++) answer[index] = 0;
            return response(answer);
        }
        public byte[] failure() {
            byte[] answer = Arrays.copyOf(dns, 12 + questionWire.length);
            System.arraycopy(questionWire, 0, answer, 12, questionWire.length);
            answer[2] = (byte)(0x80 | (dns[2] & 1)); answer[3] = (byte)0x82;
            for (int index = 6; index < 12; index++) answer[index] = 0;
            return response(answer);
        }
        public byte[] response(byte[] answer) {
            if (answer.length < 12 || answer.length > 65507 || answer[0] != dns[0] || answer[1] != dns[1] || (answer[2] & 0x80) == 0) throw new IllegalArgumentException("Invalid DNS response");
            Question question = question(answer);
            if (!question.hostname.equals(hostname) || !Arrays.equals(Arrays.copyOfRange(question.wire, question.wire.length - 4, question.wire.length), Arrays.copyOfRange(questionWire, questionWire.length - 4, questionWire.length))) throw new IllegalArgumentException("DNS response question mismatch");
            int header = (ip[0] & 15) * 4;
            byte[] packet = new byte[28 + answer.length]; packet[0] = 0x45; put16(packet, 2, packet.length); packet[4] = ip[4]; packet[5] = ip[5]; packet[8] = 64; packet[9] = 17;
            System.arraycopy(ip, 16, packet, 12, 4); System.arraycopy(ip, 12, packet, 16, 4);
            packet[20] = ip[header + 2]; packet[21] = ip[header + 3]; packet[22] = ip[header]; packet[23] = ip[header + 1]; put16(packet, 24, answer.length + 8);
            System.arraycopy(answer, 0, packet, 28, answer.length); put16(packet, 10, checksum(packet, 0, 20, 0));
            int pseudo = sum(packet, 12, 8, 0) + 17 + answer.length + 8;
            int udp = checksum(packet, 20, answer.length + 8, pseudo); put16(packet, 26, udp == 0 ? 65535 : udp);
            return packet;
        }
    }
    public static Request parse(byte[] packet, int length) {
        if (length < 28 || length > packet.length || (packet[0] >>> 4 & 15) != 4) throw new IllegalArgumentException("Invalid IPv4 DNS packet");
        int header = (packet[0] & 15) * 4, total = u16(packet, 2);
        if (header < 20 || total != length || header + 8 > total || packet[9] != 17 || (u16(packet, 6) & 0x3fff) != 0 || checksum(packet, 0, header, 0) != 0) throw new IllegalArgumentException("Invalid or fragmented IPv4 UDP packet");
        if ((packet[16] & 255) != 10 || (packet[17] & 255) != 231 || packet[18] != 0 || packet[19] != 2 || u16(packet, header + 2) != 53 || u16(packet, header) == 0) throw new IllegalArgumentException("Packet is not addressed to the local DNS proxy");
        int udpLength = u16(packet, header + 4);
        if (udpLength < 20 || header + udpLength != total) throw new IllegalArgumentException("Invalid UDP length");
        if (u16(packet, header + 6) != 0 && checksum(packet, header, udpLength, sum(packet, 12, 8, 0) + 17 + udpLength) != 0) throw new IllegalArgumentException("Invalid UDP checksum");
        byte[] dns = Arrays.copyOfRange(packet, header + 8, total);
        if ((dns[2] & 0xf8) != 0 || (dns[3] & 0x0f) != 0) throw new IllegalArgumentException("Not a standard DNS query");
        Question question = question(dns);
        return new Request(Arrays.copyOf(packet, length), dns, question.hostname, question.wire);
    }
    private static final class Question {
        final String hostname; final byte[] wire;
        Question(String hostname, byte[] wire) { this.hostname = hostname; this.wire = wire; }
    }
    private static Question question(byte[] dns) {
        if (dns.length < 17 || u16(dns, 4) != 1) throw new IllegalArgumentException("DNS query must have exactly one question");
        StringBuilder hostname = new StringBuilder(); ByteArrayOutputStream wire = new ByteArrayOutputStream(); Set<Integer> visited = new HashSet<>(); int position = 12, end = -1;
        while (true) {
            if (position >= dns.length || !visited.add(position)) throw new IllegalArgumentException("Truncated or cyclic DNS name");
            int length = dns[position++] & 255;
            if (length == 0) { if (end < 0) end = position; break; }
            if ((length & 0xc0) == 0xc0) {
                if (position >= dns.length) throw new IllegalArgumentException("Truncated DNS pointer");
                int pointer = (length & 0x3f) << 8 | dns[position++] & 255;
                if (pointer < 12 || pointer >= dns.length) throw new IllegalArgumentException("Invalid DNS pointer");
                if (end < 0) end = position; position = pointer; continue;
            }
            if ((length & 0xc0) != 0 || position + length > dns.length) throw new IllegalArgumentException("Invalid DNS label");
            if (hostname.length() != 0) hostname.append('.');
            for (int index = 0; index < length; index++) { int letter = dns[position + index] & 255; if (letter > 127) throw new IllegalArgumentException("Use punycode DNS names"); }
            wire.write(length); wire.write(dns, position, length);
            hostname.append(new String(dns, position, length, StandardCharsets.US_ASCII)); position += length;
            if (hostname.length() > 253) throw new IllegalArgumentException("DNS name is too long");
        }
        if (end + 4 > dns.length || u16(dns, end + 2) != 1) throw new IllegalArgumentException("Invalid DNS question class");
        wire.write(0); wire.write(dns, end, 4);
        return new Question(hostname.toString().toLowerCase(Locale.ROOT), wire.toByteArray());
    }
    static int u16(byte[] bytes, int offset) { return (bytes[offset] & 255) << 8 | bytes[offset + 1] & 255; }
    static void put16(byte[] bytes, int offset, int number) { bytes[offset] = (byte)(number >>> 8); bytes[offset + 1] = (byte)number; }
    private static int sum(byte[] bytes, int start, int length, int initial) {
        long sum = initial;
        for (int offset = 0; offset < length; offset += 2) sum += (bytes[start + offset] & 255) << 8 | (offset + 1 < length ? bytes[start + offset + 1] & 255 : 0);
        while ((sum >>> 16) != 0) sum = (sum & 65535) + (sum >>> 16);
        return (int)sum;
    }
    static int checksum(byte[] bytes, int start, int length, int initial) { return ~sum(bytes, start, length, initial) & 65535; }
}
