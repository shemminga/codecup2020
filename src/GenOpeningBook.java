import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DataFormatException;

public class GenOpeningBook {
    private static final SjoerdsGomokuPlayer.IO IO =
            new SjoerdsGomokuPlayer.IO(System.in, System.out, System.err, false);

    public static void main(String[] args) throws DataFormatException {
        getOwnOpeningBook();
    }

    static Map<SjoerdsGomokuPlayer.Board, SjoerdsGomokuPlayer.CalcResult> getOwnOpeningBook() throws DataFormatException {
        SjoerdsGomokuPlayer.PatternMatchMoveGenerator moveGen =
                new SjoerdsGomokuPlayer.PatternMatchMoveGenerator(IO.moveConverter, IO.dbgPrinter, IO.timer);
        moveGen.maxNanos *= 60 * 300;
        moveGen.maxDepth = 9;

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
                cachePlayer.put(flip, calcResult);
            }
        });

        System.out.println("Final calcCache size " + cachePlayer.size());

        return cachePlayer;
    }

    static void verifyEquals(final Map<SjoerdsGomokuPlayer.Board, SjoerdsGomokuPlayer.CalcResult> original,
            final Map<SjoerdsGomokuPlayer.Board, SjoerdsGomokuPlayer.CalcResult> deserialized) {
        if (original.size() != deserialized.size()) {
            System.err.printf("Map size mismatch: original %d vs deserialize %d%n", original.size(), deserialized.size());
            throw new AssertionError("Verification failed: caches of unequal size");
        }

        original.forEach((board, calcResult) -> {
            SjoerdsGomokuPlayer.CalcResult deserCR = deserialized.get(board);

            boolean equal = calcResult.ownScore == deserCR.ownScore &&
                    Arrays.equals(calcResult.moves, deserCR.moves);

            if (!equal) {
                System.err.printf("ownScore mismatch %d %d%n", calcResult.ownScore, deserCR.ownScore);
                System.err.printf("moves mismatch: %d%n", Arrays.mismatch(calcResult.moves, deserCR.moves));

                throw new AssertionError("Verification failed: calcResult not equal");
            }
        });

        System.out.println("CalcCache deserialized correctly");
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
        List<Long> longList = new ArrayList<>();
        List<Integer> intList = new ArrayList<>();

        intList.add(cache.size());

        cache.forEach((key, value) -> {
            addBoard(key, longList, intList);
            addCalcResult(value, intList);
        });

        final LongBuffer longBuffer = LongBuffer.allocate(longList.size());
        longList.forEach(longBuffer::put);

        final IntBuffer intBuffer = IntBuffer.allocate(intList.size());
        intList.forEach(intBuffer::put);

        final ByteBuffer buffer = ByteBuffer.allocate(
                Long.BYTES + longBuffer.capacity() * Long.BYTES + intBuffer.capacity() * Integer.BYTES);

        buffer.putLong(longBuffer.capacity());

        longBuffer.rewind();
        buffer.asLongBuffer().put(longBuffer);
        buffer.position(buffer.position() + longBuffer.capacity() * Long.BYTES);

        intBuffer.rewind();
        buffer.asIntBuffer().put(intBuffer);
        buffer.position(buffer.position() + intBuffer.capacity() * Integer.BYTES);

        return buffer.array();
    }

    private static void addBoard(final SjoerdsGomokuPlayer.Board board, final List<Long> longList,
            final List<Integer> intList) {
        intList.add(board.playerToMove);
        intList.add(board.moves);

        for (int i = 0; i < 4; i++) longList.add(board.playerStones[i]);
        for (int i = 0; i < 4; i++) longList.add(board.opponentStones[i]);
    }

    private static void addCalcResult(final SjoerdsGomokuPlayer.CalcResult calcResult, final List<Integer> intList) {
        intList.add(calcResult.ownScore);
        intList.add(calcResult.moves.length);

        for (int mv : calcResult.moves)
            intList.add(mv);
    }
}
