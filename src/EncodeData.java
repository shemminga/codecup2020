import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

public class EncodeData {
    private static final int BASE_SIZE = 1_287_251;

    /*
     * TODO: If I'm going to use this trick, the Patterns class should have an empty constructor. Probably no
     * initialization code should be in the class at all even. No field initializers, no constructor, etc. Just a
     * normal data class. Load all data from the ObjectInputStream.
     *
     * Very significant size gains (about 75-80% size reduction), but takes about a tenth of a second *more* to load.
     *
     * 7-8% of the time is in the Base64. May not be very costly to roll a custom Ascii85 implementation or some such.
     * 90% is in the ObjectInputStream.readObject(). The inflater is very efficient.
     */

    //final SjoerdsGomokuPlayer.Pattern[] pat4 = new SjoerdsGomokuPlayer.Pattern[3360];

    public static void main(String[] args) throws DataFormatException, IOException, ClassNotFoundException {
        main1(args);
        System.out.println("-".repeat(120));
        main2(args);
    }

    public static void main1(String[] args) throws IOException, ClassNotFoundException, DataFormatException {
        logSize("As code", BASE_SIZE);

        long startTime = System.nanoTime();
        final Patterns patterns = new Patterns();
        long endTime = System.nanoTime();

        double loadTime = endTime - startTime;

        byte[] ser = serializeObject(patterns);
        logSize("Serialized", ser);

        byte[] compr = compress(ser);
        logSize("Compressed", compr);

        byte[] enc = base64(compr);
        logSize("Base64", enc);

        System.out.println(new String(enc).lines().count());

        double deserTime = deser(enc, patterns);

        System.out.printf("Load: %.0f Deser: %.0f, delta: %f s\n", loadTime, deserTime, (loadTime - deserTime) / 1E9D);
    }

    public static void main2(String[] args) throws IOException, ClassNotFoundException, DataFormatException {
        logSize("As code", BASE_SIZE);

        long startTime = System.nanoTime();
        final Patterns patterns = new Patterns();
        long endTime = System.nanoTime();

        double loadTime = endTime - startTime;

        byte[] ser = serializeObject2(patterns);
        logSize("Serialized", ser);

        byte[] compr = compress(ser);
        logSize("Compressed", compr);

        byte[] enc = base64(compr);
        logSize("Base64", enc);

        System.out.println(new String(enc).lines()
                .count());

        double deserTime = deser2(enc, patterns);

        System.out.printf("Load: %.0f Deser: %.0f, delta: %f s\n", loadTime, deserTime, (loadTime - deserTime) / 1E9D);
    }

    private static long deser(final byte[] enc, final Patterns origPatterns) throws IOException, ClassNotFoundException, DataFormatException {
        long dStartTime = System.nanoTime();
        final byte[] decode = Base64.getMimeDecoder().decode(enc);
        long mdt = System.nanoTime();
        final ByteArrayInputStream bais = new ByteArrayInputStream(decode);
        long baist = System.nanoTime();
        final Inflater inflater = new Inflater(true);
        long inflt = System.nanoTime();
        final InflaterInputStream iis = new InflaterInputStream(bais, inflater);
        long iist = System.nanoTime();
        final ObjectInputStream ois = new ObjectInputStream(iis);
        long oist = System.nanoTime();
        final Patterns dPatterns = (Patterns) ois.readObject();
        long dEndTime = System.nanoTime();

        equals(origPatterns, dPatterns);

        System.out.println(dStartTime);
        System.out.println(mdt);
        System.out.println(baist);
        System.out.println(inflt);
        System.out.println(iist);
        System.out.println(oist);
        System.out.println(dEndTime);

        return dEndTime - dStartTime;
    }

    private static byte[] base64(final byte[] bytes) {
        return Base64.getMimeEncoder(120, new byte[]{'\n'}).encode(bytes);
    }

    private static byte[] compress(final byte[] bytes) {
        byte[] compr = new byte[bytes.length * 2];

        final Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION, true);
        deflater.setInput(bytes);
        deflater.finish();
        final int deflateLen = deflater.deflate(compr);
        deflater.end();

        return Arrays.copyOf(compr, deflateLen);
    }

    private static byte[] serializeObject(final Patterns patterns) throws IOException {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        final ObjectOutputStream oos = new ObjectOutputStream(baos);
        oos.writeObject(patterns);
        oos.close();
        return baos.toByteArray();
    }

    private static byte[] serializeObject2(final Patterns patterns) {
        final int totalCount = patterns.pat1.length + patterns.pat2.length + patterns.pat3.length + patterns.pat4.length;
        LongBuffer longBuffer = LongBuffer.allocate(totalCount * 8);
        List<Integer> intList = new ArrayList<>();

        intList.add(patterns.pat1.length);
        intList.add(patterns.pat2.length);
        intList.add(patterns.pat3.length);
        intList.add(patterns.pat4.length);

        addPatterns(patterns.pat1, longBuffer, intList);
        addPatterns(patterns.pat2, longBuffer, intList);
        addPatterns(patterns.pat3, longBuffer, intList);
        addPatterns(patterns.pat4, longBuffer, intList);

        final IntBuffer intBuffer = IntBuffer.allocate(intList.size());
        for (Integer i : intList) {
            intBuffer.put(i);
        }

        final ByteBuffer byteBuffer =
                ByteBuffer.allocate(Long.BYTES + longBuffer.capacity() * Long.BYTES + intBuffer.capacity() * Integer.BYTES);

        byteBuffer.putLong(longBuffer.capacity());
        longBuffer.rewind();
        byteBuffer.asLongBuffer().put(longBuffer);
        byteBuffer.position(byteBuffer.position() + longBuffer.capacity() * Long.BYTES);
        intBuffer.rewind();
        byteBuffer.asIntBuffer().put(intBuffer);
        //byteBuffer.position(byteBuffer.position() + intBuffer.capacity() * Integer.BYTES);

        return byteBuffer.array();
    }

    private static void addPatterns(final SjoerdsGomokuPlayer.Pattern[] pat, final LongBuffer longBuffer,
            final List<Integer> intList) {
        for (SjoerdsGomokuPlayer.Pattern p : pat) {
            longBuffer.put(p.emptyFields);
            longBuffer.put(p.playerStones);
            intList.add(p.fieldIdxs.length);
            for (int fieldIdx : p.fieldIdxs) {
                intList.add(fieldIdx);
            }
        }
    }

    private static long deser2(final byte[] enc, final Patterns origPatterns) throws IOException, ClassNotFoundException, DataFormatException {
        long dStartTime = System.nanoTime();
        final byte[] decode = Base64.getMimeDecoder().decode(enc);
        long mdt = System.nanoTime();

        final Inflater inflater = new Inflater(true);
        inflater.setInput(decode);
        final ByteBuffer byteBuffer = ByteBuffer.allocate(1572504);
        inflater.inflate(byteBuffer);
        if (!inflater.finished()) {
            throw new AssertionError();
        }
        inflater.end();
        byteBuffer.rewind();
        long inflt = System.nanoTime();

        final Patterns dPatterns = new Patterns(false);

        long patCreate = System.nanoTime();

        final long longBufferLen = byteBuffer.getLong();
        final LongBuffer longBuffer = byteBuffer.asLongBuffer();
        longBuffer.limit((int) longBufferLen);
        byteBuffer.position(byteBuffer.position() + (int) longBufferLen * Long.BYTES);
        final IntBuffer intBuffer = byteBuffer.asIntBuffer();

        dPatterns.pat1 = new SjoerdsGomokuPlayer.Pattern[intBuffer.get()];
        dPatterns.pat2 = new SjoerdsGomokuPlayer.Pattern[intBuffer.get()];
        dPatterns.pat3 = new SjoerdsGomokuPlayer.Pattern[intBuffer.get()];
        dPatterns.pat4 = new SjoerdsGomokuPlayer.Pattern[intBuffer.get()];

        readPatterns(dPatterns.pat1, longBuffer, intBuffer);
        readPatterns(dPatterns.pat2, longBuffer, intBuffer);
        readPatterns(dPatterns.pat3, longBuffer, intBuffer);
        readPatterns(dPatterns.pat4, longBuffer, intBuffer);

        long dEndTime = System.nanoTime();

        equals(origPatterns, dPatterns);

        System.out.println(dStartTime);
        System.out.println(mdt);
        System.out.println(inflt);
        System.out.println(patCreate);
        System.out.println(dEndTime);

        return dEndTime - dStartTime;
    }

    private static void readPatterns(final SjoerdsGomokuPlayer.Pattern[] pat, final LongBuffer longBuffer,
            final IntBuffer intBuffer) {
        for (int i = 0; i < pat.length; i++) {
            long[] emptyFields = new long[4];
            longBuffer.get(emptyFields);

            long[] playerStones = new long[4];
            longBuffer.get(playerStones);

            int[] fieldIdx = new int[intBuffer.get()];
            intBuffer.get(fieldIdx);

            pat[i] = new SjoerdsGomokuPlayer.Pattern(emptyFields, playerStones, fieldIdx);
        }
    }

    private static void equals(Patterns p1, Patterns p2) {
        boolean equal =
                        Arrays.equals(p1.pat1, p2.pat1) &&
                        Arrays.equals(p1.pat2, p2.pat2) &&
                        Arrays.equals(p1.pat3, p2.pat3) &&
                        Arrays.equals(p1.pat4, p2.pat4);

        if (!equal) {
            System.err.println("NOT EQUAL");

            System.err.printf("pat1 mismatch: %d (len %d %d)\n", Arrays.mismatch(p1.pat1, p2.pat1), p1.pat1.length, p2.pat1.length);
            System.err.printf("pat2 mismatch: %d (len %d %d)\n", Arrays.mismatch(p1.pat2, p2.pat2), p1.pat2.length, p2.pat2.length);
            System.err.printf("pat3 mismatch: %d (len %d %d)\n", Arrays.mismatch(p1.pat3, p2.pat3), p1.pat3.length, p2.pat3.length);
            System.err.printf("pat4 mismatch: %d (len %d %d)\n", Arrays.mismatch(p1.pat4, p2.pat4), p1.pat4.length, p2.pat4.length);
        } else {
            System.out.println("Deserialized correctly");
        }
    }

    private static void logSize(final String desc, final byte[] bytes) {
        logSize(desc, bytes.length);
    }

    private static void logSize(final String desc, final int len) {
        System.out.printf("%12s: %d (%.1f%%)\n", desc, len, 100D * len / BASE_SIZE);
    }
}
