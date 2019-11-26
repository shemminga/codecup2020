import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Arrays;
import java.util.Base64;
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

    public static void main(String[] args) throws IOException, ClassNotFoundException, DataFormatException {
        logSize("As code", BASE_SIZE);

        long startTime = System.nanoTime();
        final Patterns patterns = new Patterns();
        long endTime = System.nanoTime();

        double loadTime = endTime - startTime;

        byte[] ser = serializeObject(patterns);
        logSize("Serialized", ser);

        byte[] compr = compress(ser);
        logSize("Compessed", compr);

        byte[] enc = base64(compr);
        logSize("Base64", enc);

        System.out.println(new String(enc).lines().count());

        double deserTime = deser(enc);

        System.out.printf("Load: %.0f Deser: %.0f, delta: %f s", loadTime, deserTime, (loadTime - deserTime) / 1E9D);
    }

    private static long deser(final byte[] enc) throws IOException, ClassNotFoundException, DataFormatException {
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

    private static void logSize(final String desc, final byte[] bytes) {
        logSize(desc, bytes.length);
    }

    private static void logSize(final String desc, final int len) {
        System.out.printf("%12s: %d (%.1f%%)\n", desc, len, 100D * len / BASE_SIZE);
    }
}
