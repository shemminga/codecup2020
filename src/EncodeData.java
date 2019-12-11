import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;

public class EncodeData {
    private static final int MAX_SIZE_STRING_CONSTANT = 65535;

    public static void main(String[] args) throws IOException, ClassNotFoundException, DataFormatException {
        SjoerdsGomokuPlayer.Patterns patterns = GenPatterns.getPatterns();
        byte[] patternsBytes = serializePatterns(patterns);
        String patternsString = toUsableString(patternsBytes);

        SjoerdsGomokuPlayer.Patterns verifyPatterns = SjoerdsGomokuPlayer.DataReader.deserializePatterns(patternsString, patternsBytes.length);
        verifyEquals(patterns, verifyPatterns);

        System.out.println("static final class Data {");
        printData("PATTERNS", patternsBytes.length, patternsString);
        System.out.println("}");
    }

    private static void printData(final String name, final int length, final String string) {
        System.out.println("static final int " + name + "_UNCOMPRESSED_SIZE = " + length + ";");
        System.out.println("@SuppressWarnings(\"StringBufferReplaceableByString\") // It really can't be replaced by String.");
        System.out.println("static final String " + name + " = new StringBuilder()");

        String remaining = string;
        while (remaining.length() > MAX_SIZE_STRING_CONSTANT) {
            System.out.println(".append(\"" + remaining.substring(0, MAX_SIZE_STRING_CONSTANT - 1) + "\")");
            remaining = remaining.substring(MAX_SIZE_STRING_CONSTANT - 1);
        }

        System.out.println(".append(\"" + remaining + "\")");
        System.out.println(".toString();");
    }

    private static String toUsableString(final byte[] bytes) {
        byte[] compressed = compress(bytes);
        byte[] base64 = base64(compressed);
        return new String(base64, StandardCharsets.US_ASCII);
    }

    private static byte[] base64(final byte[] bytes) {
        return Base64.getEncoder().encode(bytes);
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

    private static byte[] serializePatterns(final SjoerdsGomokuPlayer.Patterns patterns) {
        final int totalCount =
                patterns.pat1.length + patterns.pat2.length + patterns.pat3.length + patterns.pat4.length;
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

        final ByteBuffer byteBuffer = ByteBuffer.allocate(
                Long.BYTES + longBuffer.capacity() * Long.BYTES + intBuffer.capacity() * Integer.BYTES);

        byteBuffer.putLong(longBuffer.capacity());
        longBuffer.rewind();
        byteBuffer.asLongBuffer().put(longBuffer);
        byteBuffer.position(byteBuffer.position() + longBuffer.capacity() * Long.BYTES);
        intBuffer.rewind();
        byteBuffer.asIntBuffer().put(intBuffer);

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

    private static void verifyEquals(SjoerdsGomokuPlayer.Patterns p1, SjoerdsGomokuPlayer.Patterns p2) {
        boolean equal =
                        Arrays.equals(p1.pat1, p2.pat1) &&
                        Arrays.equals(p1.pat2, p2.pat2) &&
                        Arrays.equals(p1.pat3, p2.pat3) &&
                        Arrays.equals(p1.pat4, p2.pat4);

        if (!equal) {
            System.err.printf("pat1 mismatch: %d (len %d %d)\n", Arrays.mismatch(p1.pat1, p2.pat1), p1.pat1.length, p2.pat1.length);
            System.err.printf("pat2 mismatch: %d (len %d %d)\n", Arrays.mismatch(p1.pat2, p2.pat2), p1.pat2.length, p2.pat2.length);
            System.err.printf("pat3 mismatch: %d (len %d %d)\n", Arrays.mismatch(p1.pat3, p2.pat3), p1.pat3.length, p2.pat3.length);
            System.err.printf("pat4 mismatch: %d (len %d %d)\n", Arrays.mismatch(p1.pat4, p2.pat4), p1.pat4.length, p2.pat4.length);

            throw new AssertionError("Verification failed: patterns not equal");
        } else {
            System.out.println("Deserialized correctly");
        }
    }
}
