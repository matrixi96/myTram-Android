package pl.mytram.app;

import java.nio.charset.StandardCharsets;

/** Minimalny czytnik formatu Protocol Buffers (wystarczy do GTFS-Realtime, bez bibliotek). */
final class Pb {
    private final byte[] b;
    private int pos;
    private final int end;
    int field;
    int wire;

    Pb(byte[] b) { this(b, 0, b.length); }

    Pb(byte[] b, int start, int end) {
        this.b = b;
        this.pos = start;
        this.end = end;
    }

    /** Przechodzi do kolejnego pola. Zwraca false na końcu wiadomości. */
    boolean next() {
        if (pos >= end) return false;
        long tag = varint();
        field = (int) (tag >>> 3);
        wire = (int) (tag & 7);
        return true;
    }

    long varint() {
        long result = 0;
        int shift = 0;
        while (pos < end) {
            int x = b[pos++] & 0xFF;
            result |= (long) (x & 0x7F) << shift;
            if ((x & 0x80) == 0) return result;
            shift += 7;
            if (shift > 63) break;
        }
        return result;
    }

    private int len() {
        int n = (int) varint();
        if (n < 0 || pos + n > end) throw new IllegalStateException("zly protobuf");
        return n;
    }

    String str() {
        int n = len();
        String s = new String(b, pos, n, StandardCharsets.UTF_8);
        pos += n;
        return s;
    }

    Pb msg() {
        int n = len();
        Pb p = new Pb(b, pos, pos + n);
        pos += n;
        return p;
    }

    float f32() {
        int v = (b[pos] & 0xFF) | (b[pos + 1] & 0xFF) << 8 | (b[pos + 2] & 0xFF) << 16 | (b[pos + 3] & 0xFF) << 24;
        pos += 4;
        return Float.intBitsToFloat(v);
    }

    double f64() {
        long v = 0;
        for (int i = 7; i >= 0; i--) v = (v << 8) | (b[pos + i] & 0xFF);
        pos += 8;
        return Double.longBitsToDouble(v);
    }

    /** Liczba z pola: varint albo fixed. */
    long num() {
        if (wire == 0) return varint();
        if (wire == 5) { long v = Float.floatToIntBits(f32()); return v; }
        if (wire == 1) { pos += 8; return 0; }
        skip();
        return 0;
    }

    void skip() {
        switch (wire) {
            case 0: varint(); break;
            case 1: pos += 8; break;
            case 2: { int n = len(); pos += n; break; }
            case 5: pos += 4; break;
            default: pos = end; // nieznany typ - koniec tej wiadomości
        }
    }
}
