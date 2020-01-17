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

    public static void main(String[] args) throws DataFormatException {
        SjoerdsGomokuPlayer.Patterns patterns = GenPatterns.getPatterns();
        byte[] patternsBytes = serializePatterns(patterns);
        String patternsString = toUsableString(patternsBytes);

        SjoerdsGomokuPlayer.Patterns verifyPatterns = SjoerdsGomokuPlayer.DataReader.deserializePatterns(patternsString, patternsBytes.length);
        verifyEquals(patterns, verifyPatterns);

        //Map<SjoerdsGomokuPlayer.Board, SjoerdsGomokuPlayer.CalcResult> ownOpeningBook = GenOpeningBook.getOwnOpeningBook();
        //byte[] ownOpeningBookBytes = GenOpeningBook.serializeCalcCache(ownOpeningBook);
        //String ownOpeningBookString = toUsableString(ownOpeningBookBytes);
        //
        //final Map<SjoerdsGomokuPlayer.Board, SjoerdsGomokuPlayer.CalcResult> verifyOwnOpeningBook = new HashMap<>();
        ////SjoerdsGomokuPlayer.DataReader.loadOwnOpeningBook(verifyOwnOpeningBook, false, ownOpeningBookString, ownOpeningBookBytes.length);
        //GenOpeningBook.verifyEquals(ownOpeningBook, verifyOwnOpeningBook);

        StringBuilder sb = new StringBuilder();
        sb.append("@SuppressWarnings(\"StringBufferReplaceableByString\") // They really can't be replaced by Strings.")
                .append(System.lineSeparator())
                .append("static final class Data {")
                .append(System.lineSeparator());
        printData(sb, "PATTERNS", patternsBytes.length, patternsString);
        //printData(sb, "OWN_OPENING_BOOK", ownOpeningBookBytes.length, ownOpeningBookString);
        sb.append("}")
                .append(System.lineSeparator());

        String s = sb.toString();
        if (s.length() > 1_000_000) {
            System.err.println("TOO BIG! " + s.length());
        } else {
            System.out.println(s);
        }
    }

    private static void printData(final StringBuilder sb, final String name, final int length, final String string) {
        sb.append("static final int ")
                .append(name)
                .append("_UNCOMPRESSED_SIZE = ")
                .append(length)
                .append(";")
                .append(System.lineSeparator())
                .append("static final String ")
                .append(name)
                .append(" = new StringBuilder()")
                .append(System.lineSeparator());

        String remaining = string;
        while (remaining.length() > MAX_SIZE_STRING_CONSTANT) {
            sb.append(".append(\"")
                    .append(remaining.substring(0, MAX_SIZE_STRING_CONSTANT - 1))
                    .append("\")")
                    .append(System.lineSeparator());
            remaining = remaining.substring(MAX_SIZE_STRING_CONSTANT - 1);
        }

        sb.append(".append(\"")
                .append(remaining)
                .append("\")")
                .append(System.lineSeparator())
                .append(".toString();")
                .append(System.lineSeparator());
    }

    static String toUsableString(final byte[] bytes) {
        // Double compression shaves another 30% from final Base64 data for patterns, and 5% for the opening book.
        byte[] compressed1 = compress(bytes);
        byte[] compressed2 = compress(compressed1);
        byte[] base64 = base64(compressed2);

        if (compressed1.length > bytes.length) {
            // Decompress will fail.
            throw new AssertionError();
        }

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
        List<Long> longList = new ArrayList<>();
        List<Integer> intList = new ArrayList<>();

        intList.add(patterns.allPatterns.length);
        addPatterns(patterns.allPatterns, longList, intList);

        final LongBuffer longBuffer = LongBuffer.allocate(longList.size());
        for (Long l : longList) {
            longBuffer.put(l);
        }

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

    private static void addPatterns(final SjoerdsGomokuPlayer.Pattern[] pat, final List<Long> longList,
            final List<Integer> intList) {
        for (SjoerdsGomokuPlayer.Pattern p : pat) {
            for (long eF : p.emptyFields)
                longList.add(eF);
            for (long pS : p.playerStones)
                longList.add(pS);
            intList.add(p.moves.length);
            for (int fieldIdx : p.moves)
                intList.add(fieldIdx);
            for (int type : p.moveTypes)
                intList.add(type);
        }
    }

    private static void verifyEquals(SjoerdsGomokuPlayer.Patterns p1, SjoerdsGomokuPlayer.Patterns p2) {
        if (!Arrays.equals(p1.allPatterns, p2.allPatterns)) {
            System.err.printf("allPatterns mismatch: %d (len %d %d)\n", Arrays.mismatch(p1.allPatterns, p2.allPatterns),
                    p1.allPatterns.length, p2.allPatterns.length);

            throw new AssertionError("Verification failed: patterns not equal");
        } else {
            System.out.println("Patterns deserialized correctly");
        }
    }
}
