package cn.hongguo.tv.media;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/** Tiny in-memory MP4 fixtures for codec signaling and stsc validation. */
public class DecryptorTest {
    private static final int SAMPLE_SIZE = 4;

    @Test public void encryptedVideoEntryUsesOriginalAvcCodecWhenSignalingIsStripped() {
        byte[] file = videoMp4("avc1", new long[][]{{1, 1, 1}}, 1, 1, SAMPLE_SIZE, true);
        Decryptor.Box trak = Decryptor.findFirst(file, 0, file.length, "trak");
        assertNotNull(trak);

        String codec = Decryptor.readVideoCodec(file, trak);
        assertEquals("Use the frma codec rather than the encrypted encv sample entry", "avc1", codec);
        Decryptor.Box moov = Decryptor.findFirst(file, 0, file.length, "moov");
        assertNotNull(moov);
        Decryptor.rewriteVideoEntry(file, moov, codec);

        assertEquals("The output should retain the original frma and rewrite encv to AVC",
                2, countFourcc(file, "avc1"));
        assertEquals("Encrypted video entry must not be mislabeled as HEVC", 0, countFourcc(file, "hvc1"));
        assertEquals("Encrypted sample entry must be removed", 0, countFourcc(file, "encv"));
    }

    @Test public void validIncreasingChunkRunsStillMapSamples() {
        byte[] file = videoMp4("hvc1", new long[][]{{1, 1, 1}, {2, 1, 1}}, 2, 2, SAMPLE_SIZE, false);
        assertTrue(Decryptor.validChunkRunStarts(new long[]{1, 2}, 2));
        assertArrayEquals(new int[]{SAMPLE_SIZE, SAMPLE_SIZE}, Decryptor.videoSampleSizes(file));
        Decryptor.Box mdat = Decryptor.findFirst(file, 0, file.length, "mdat");
        assertNotNull(mdat);
        assertArrayEquals(new int[]{mdat.start + 8, mdat.start + 8 + SAMPLE_SIZE}, Decryptor.videoSampleOffsets(file));
    }

    @Test public void malformedStscTablesAreRejectedBeforeChunkRunExpansion() {
        assertNull(Decryptor.videoSampleSizes(videoMp4("hvc1", new long[][]{{1, 1, 1}, {3, 1, 1}},
                2, 2, SAMPLE_SIZE, false)));
        assertNull(Decryptor.videoSampleSizes(videoMp4("hvc1", new long[][]{{1, 1, 1}, {1, 1, 1}},
                2, 2, SAMPLE_SIZE, false)));
        assertNull(Decryptor.videoSampleSizes(videoMp4("hvc1", new long[][]{{2, 1, 1}, {1, 1, 1}},
                2, 2, SAMPLE_SIZE, false)));
        assertNull(Decryptor.videoSampleSizes(videoMp4("hvc1", new long[][]{{0, 1, 1}},
                2, 2, SAMPLE_SIZE, false)));
        assertFalse(Decryptor.validChunkRunStarts(new long[]{0}, 2));
        assertFalse(Decryptor.validChunkRunStarts(new long[]{2, 1}, 2));
        assertFalse(Decryptor.validChunkRunStarts(new long[]{1, 1}, 2));
        assertFalse(Decryptor.validChunkRunStarts(new long[]{1, 3}, 2));
        // Check the formerly pathological uint32 boundary directly; don't start the old long loop.
        assertFalse(Decryptor.validChunkRunStarts(new long[]{1, 0xffff_ffffL}, 2));
    }

    private static byte[] videoMp4(String originalCodec, long[][] stscEntries, int chunks,
                                   int samples, int sampleSize, boolean encrypted) {
        byte[] moov = moov(originalCodec, stscEntries, chunks, samples, sampleSize, encrypted, new long[chunks]);
        long mdatPayload = (long) moov.length + 8;
        long[] offsets = new long[chunks];
        for (int chunk = 0; chunk < chunks; chunk++) offsets[chunk] = mdatPayload + (long) chunk * sampleSize;
        moov = moov(originalCodec, stscEntries, chunks, samples, sampleSize, encrypted, offsets);
        return concat(moov, box("mdat", new byte[samples * sampleSize]));
    }

    private static byte[] moov(String originalCodec, long[][] stscEntries, int chunks, int samples,
                               int sampleSize, boolean encrypted, long[] offsets) {
        byte[] visualHeader = new byte[78];
        byte[] sinf = box("sinf", box("frma", ascii(originalCodec)));
        byte[] sampleEntry = box("encv", concat(visualHeader, sinf));
        byte[] stsd = box("stsd", concat(new byte[4], intBytes(1), sampleEntry));
        byte[] stsz = box("stsz", concat(new byte[4], intBytes(sampleSize), intBytes(samples)));

        ByteArrayOutputStream chunkOffsets = new ByteArrayOutputStream();
        for (int i = 0; i < chunks; i++) writeInt(chunkOffsets, (int) offsets[i]);
        byte[] stco = box("stco", concat(new byte[4], intBytes(chunks), chunkOffsets.toByteArray()));

        ByteArrayOutputStream runs = new ByteArrayOutputStream();
        for (long[] entry : stscEntries) {
            writeInt(runs, (int) entry[0]);
            writeInt(runs, (int) entry[1]);
            writeInt(runs, (int) entry[2]);
        }
        byte[] stsc = box("stsc", concat(new byte[4], intBytes(stscEntries.length), runs.toByteArray()));
        byte[] stbl = box("stbl", concat(stsd, stsz, stco, stsc));
        byte[] minf = box("minf", stbl);
        byte[] handler = box("hdlr", concat(new byte[8], ascii("vide"), new byte[12]));
        byte[] mdia = box("mdia", concat(handler, minf));
        if (encrypted) {
            byte[] senc = box("senc", concat(new byte[4], intBytes(samples), new byte[8 * samples]));
            mdia = concat(mdia, senc);
        }
        return box("moov", box("trak", mdia));
    }

    private static int countFourcc(byte[] data, String fourcc) {
        byte[] value = ascii(fourcc);
        int count = 0;
        for (int i = 0; i <= data.length - value.length; i++) {
            boolean match = true;
            for (int j = 0; j < value.length; j++) {
                if (data[i + j] != value[j]) { match = false; break; }
            }
            if (match) count++;
        }
        return count;
    }

    private static byte[] box(String type, byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeInt(out, payload.length + 8);
        out.write(ascii(type), 0, 4);
        out.write(payload, 0, payload.length);
        return out.toByteArray();
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.write(part, 0, part.length);
        return out.toByteArray();
    }

    private static byte[] intBytes(int value) {
        return new byte[]{(byte) (value >>> 24), (byte) (value >>> 16),
                (byte) (value >>> 8), (byte) value};
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        byte[] bytes = intBytes(value);
        out.write(bytes, 0, bytes.length);
    }

    private static byte[] ascii(String value) { return value.getBytes(StandardCharsets.US_ASCII); }
}
