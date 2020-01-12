import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.DataFormatException;

public class GenOpeningBook {
    private static final SjoerdsGomokuPlayer.IO IO =
            new SjoerdsGomokuPlayer.IO(System.in, System.out, System.err, false);

    static Map<SjoerdsGomokuPlayer.Board, SjoerdsGomokuPlayer.CalcResult> getOwnOpeningBook() throws DataFormatException {
        SjoerdsGomokuPlayer.PatternMatchMoveGenerator moveGen =
                new SjoerdsGomokuPlayer.PatternMatchMoveGenerator(IO.moveConverter, IO.dbgPrinter, IO.timer);
        moveGen.maxNanos *= 60 * 300;
        moveGen.maxDepth = 2;

        addOpening(moveGen, SjoerdsGomokuPlayer.Move.OPENING, SjoerdsGomokuPlayer.Board.PLAYER);

        Map<SjoerdsGomokuPlayer.Board, SjoerdsGomokuPlayer.CalcResult> cachePlayer = moveGen.calcCache;
        moveGen.calcCache = new HashMap<>();

        addOpening(moveGen, SjoerdsGomokuPlayer.Move.OPENING, SjoerdsGomokuPlayer.Board.OPPONENT);
        Map<SjoerdsGomokuPlayer.Board, SjoerdsGomokuPlayer.CalcResult> cacheOpponent = moveGen.calcCache;

        System.out.println("cachePlayer.size() = " + cachePlayer.size());
        System.out.println("cacheOpponent.size() = " + cacheOpponent.size());

        cacheOpponent.forEach((board, calcResult) -> {
            SjoerdsGomokuPlayer.Board flip = board.copy().flip();

            if (!cachePlayer.containsKey(flip)) {
                byte[] swap;

                if (calcResult.match4 != null) {
                    swap = calcResult.match4[0];
                    calcResult.match4[0] = calcResult.match4[1];
                    calcResult.match4[1] = swap;
                }

                if (calcResult.match3 != null) {
                    swap = calcResult.match3[0];
                    calcResult.match3[0] = calcResult.match3[1];
                    calcResult.match3[1] = swap;
                }

                if (calcResult.match2 != null) {
                    swap = calcResult.match2[0];
                    calcResult.match2[0] = calcResult.match2[1];
                    calcResult.match2[1] = swap;
                }

                if (calcResult.match1 != null) {
                    swap = calcResult.match1[0];
                    calcResult.match1[0] = calcResult.match1[1];
                    calcResult.match1[1] = swap;
                }

                cachePlayer.put(flip, calcResult);
            }
        });

        System.out.println("Final calcCache size " + cachePlayer.size());

        return cachePlayer;
    }

    static void verifyEquals(final Map<SjoerdsGomokuPlayer.Board, SjoerdsGomokuPlayer.CalcResult> original,
            final Map<SjoerdsGomokuPlayer.Board, SjoerdsGomokuPlayer.CalcResult> deserialized) {
        if (original.size() != deserialized.size()) {
            System.err.printf("Array size mismatch: original %d vs deserialize %d%n", original.size(), deserialized.size());
            throw new AssertionError("Verification failed: caches of unequal size");
        }

        original.forEach((board, calcResult) -> {
            SjoerdsGomokuPlayer.CalcResult deserCR = deserialized.get(board);

            boolean equal = Objects.equals(calcResult.moves, deserCR.moves) &&
                    verifyMatches(calcResult.match4, deserCR.match4) && //
                    verifyMatches(calcResult.match3, deserCR.match3) && //
                    verifyMatches(calcResult.match2, deserCR.match2) && //
                    verifyMatches(calcResult.match1, deserCR.match1);

            if (!equal) {
                printVerifyDifference("match4", calcResult.match4, deserCR.match4);
                printVerifyDifference("match3", calcResult.match3, deserCR.match3);
                printVerifyDifference("match2", calcResult.match2, deserCR.match2);
                printVerifyDifference("match1", calcResult.match1, deserCR.match1);

                Object[] moves1 = calcResult.moves == null ? null : calcResult.moves.toArray();
                Object[] moves2 = deserCR.moves == null ? null : deserCR.moves.toArray();
                printVerifyDifference("moves", moves1, moves2);

                throw new AssertionError("Verification failed: patterns not equal");
            }
        });

        System.out.println("Deserialized correctly");
    }

    private static boolean verifyMatches(final byte[][] calcResult, final byte[][] deserCR) {
        if (calcResult == null || deserCR == null) return calcResult == deserCR;
        return Arrays.equals(calcResult[0], deserCR[0]) && Arrays.equals(calcResult[1], deserCR[1]);
    }

    private static void printVerifyDifference(String name, byte[][] arr1, byte[][] arr2) {
        if (arr1 == null || arr2 == null) {
            System.err.printf("%s mismatch: %s vs %s%n", name, arr1, arr2);
            return;
        }

        System.err.printf("%s[0] mismatch: %d (len %d %d)%n", name, Arrays.mismatch(arr1[0], arr2[0]), arr1[0].length, arr2[0].length);
        System.err.printf("%s[1] mismatch: %d (len %d %d)%n", name, Arrays.mismatch(arr1[1], arr2[1]), arr1[1].length, arr2[1].length);
    }

    private static void printVerifyDifference(String name, Object[] arr1, Object[] arr2) {
        if (arr1 == null || arr2 == null) {
            System.err.printf("%s mismatch: %s vs %s%n", name, arr1, arr2);
            return;
        }

        System.err.printf("%s mismatch: %d (len %d %d)%n", name, Arrays.mismatch(arr1, arr2), arr1.length, arr2.length);
    }

    private static void addOpening(final SjoerdsGomokuPlayer.PatternMatchMoveGenerator moveGen,
            final SjoerdsGomokuPlayer.Move[] opening, final int playerToMove) {
        IO.timer.totalTime = 0;
        IO.timer.timerStart = System.nanoTime();

        SjoerdsGomokuPlayer.Board board = new SjoerdsGomokuPlayer.Board();
        board.playerToMove = playerToMove;
        board.apply(opening[0]);
        board.apply(opening[1]);
        board.apply(opening[2]);
        SjoerdsGomokuPlayer.Move move = moveGen.generateMove(board);
    }

    static byte[] serializeCalcCache(final Map<SjoerdsGomokuPlayer.Board, SjoerdsGomokuPlayer.CalcResult> cache) {
        final int count = cache.size();

        List<Long> longList = new ArrayList<>();
        List<Integer> intList = new ArrayList<>();
        List<Byte> byteList = new ArrayList<>();

        cache.forEach((key, value) -> {
            addBoard(key, longList, intList, byteList);
            addCalcResult(value, intList, byteList);
        });

        final LongBuffer longBuffer = LongBuffer.allocate(longList.size());
        longList.forEach(longBuffer::put);

        final IntBuffer intBuffer = IntBuffer.allocate(intList.size());
        intList.forEach(intBuffer::put);

        final ByteBuffer byteBuffer = ByteBuffer.allocate(byteList.size());
        byteList.forEach(byteBuffer::put);

        final ByteBuffer buffer = ByteBuffer.allocate(
                3 * Long.BYTES + longBuffer.capacity() * Long.BYTES + intBuffer.capacity() * Integer.BYTES + byteBuffer.capacity());

        buffer.putLong(longBuffer.capacity());
        buffer.putLong(intBuffer.capacity());
        buffer.putLong(count);

        longBuffer.rewind();
        buffer.asLongBuffer()
                .put(longBuffer);
        buffer.position(buffer.position() + longBuffer.capacity() * Long.BYTES);

        intBuffer.rewind();
        buffer.asIntBuffer()
                .put(intBuffer);
        buffer.position(buffer.position() + intBuffer.capacity() * Integer.BYTES);

        byteBuffer.rewind();
        buffer.put(byteBuffer);

        return buffer.array();
    }

    private static void addBoard(final SjoerdsGomokuPlayer.Board board, final List<Long> longList,
            final List<Integer> intList, final List<Byte> byteList) {
        intList.add(board.playerToMove);
        intList.add(board.moves);

        for (int i = 0; i < 4; i++) longList.add(board.playerStones[i]);
        for (int i = 0; i < 4; i++) longList.add(board.opponentStones[i]);
    }

    private static void addCalcResult(final SjoerdsGomokuPlayer.CalcResult calcResult, final List<Integer> intList,
            final List<Byte> byteList) {

        byte bools = (byte) (//
                setBit(0, calcResult.match4 != null) | //
                setBit(1, calcResult.match3 != null) | //
                setBit(2, calcResult.match2 != null) | //
                setBit(3, calcResult.match1 != null) | //
                setBit(4, calcResult.moves != null));

        byteList.add(bools);

        for (int onMove = 0; onMove <= 1; onMove++)
            for (int fieldIdx = 0; fieldIdx < 256; fieldIdx++)
                if (calcResult.match4 != null)
                    byteList.add(calcResult.match4[onMove][fieldIdx]);

        for (int onMove = 0; onMove <= 1; onMove++)
            for (int fieldIdx = 0; fieldIdx < 256; fieldIdx++)
                if (calcResult.match3 != null)
                    byteList.add(calcResult.match3[onMove][fieldIdx]);

        for (int onMove = 0; onMove <= 1; onMove++)
            for (int fieldIdx = 0; fieldIdx < 256; fieldIdx++)
                if (calcResult.match2 != null)
                    byteList.add(calcResult.match2[onMove][fieldIdx]);

        for (int onMove = 0; onMove <= 1; onMove++)
            for (int fieldIdx = 0; fieldIdx < 256; fieldIdx++)
                if (calcResult.match1 != null)
                    byteList.add(calcResult.match1[onMove][fieldIdx]);

        if (calcResult.moves != null) {
            intList.add(calcResult.moves.size());
            intList.addAll(calcResult.moves);
        }
    }

    private static byte setBit(final int bit, final boolean bool) {
        return (byte) (!bool ? 0 : 1 << bit);
    }
}
