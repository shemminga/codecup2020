import java.util.Arrays;
import java.util.Objects;
import java.util.Random;

public class SjoerdsGomokuPlayerTest {
    private static final SjoerdsGomokuPlayer.DbgPrinter DBG_PRINTER =
            new SjoerdsGomokuPlayer.DbgPrinter(System.err, SjoerdsGomokuPlayer.START_UP_TIME, false);
    private static final SjoerdsGomokuPlayer.MoveConverter MOVE_CONVERTER =
            new SjoerdsGomokuPlayer.MoveConverter(DBG_PRINTER);

    public static void main(String[] args) {
        testMoves();
    }

    private static void testMoves() {
        final SjoerdsGomokuPlayer.IO io = SjoerdsGomokuPlayer.makeIO(DBG_PRINTER, null, null);
        final SjoerdsGomokuPlayer.MoveGenerator moveGenerator = SjoerdsGomokuPlayer.getMoveGenerator(new Random(), io);

        //testFinish5(moveGenerator);
        testFinish4(moveGenerator);
    }

    private static void testFinish5(final SjoerdsGomokuPlayer.MoveGenerator moveGenerator) {
        testMoveGen("Win horizontal", "Ae", moveGenerator, "Aa", "Ba", "Ab", "Bb", "Ac", "Bc", "Ad", "Bd");
        testMoveGen("Win diagonal", "Ee", moveGenerator, "Aa", "Da", "Bb", "Mm", "Cc", "Bc", "Dd", "Bd");
        testMoveGen("Block loss", "Ee", moveGenerator, "Hi", "Aa", "Da", "Bb", "Mm", "Cc", "Bc", "Dd");
    }

    private static void testFinish4(final SjoerdsGomokuPlayer.MoveGenerator moveGenerator) {
        testMoveGen("Exploit open 3", "Ab", moveGenerator, "Ac", "Ba", "Ad", "Bb", "Ae", "Bc");
    }

    private static void testMoveGen(String desc, String expectedMove, SjoerdsGomokuPlayer.MoveGenerator moveGenerator, String... setupMoves) {
        final SjoerdsGomokuPlayer.Board board = newBoard(setupMoves);
        final SjoerdsGomokuPlayer.Move move = moveGenerator.generateMove(board);
        expect(expectedMove, toString(move), desc);
        System.out.println("Test OK: " + desc);
    }

    private static SjoerdsGomokuPlayer.Board newBoard(String... moves) {
        final SjoerdsGomokuPlayer.Board board = new SjoerdsGomokuPlayer.Board();

        Arrays.stream(moves)
                .map(SjoerdsGomokuPlayerTest::toMove)
                .forEach(board::apply);

        return board;
    }


    private static SjoerdsGomokuPlayer.Move toMove(String mv) {
        final int fieldIdx = MOVE_CONVERTER.toFieldIdx(mv.charAt(0), mv.charAt(1));
        return MOVE_CONVERTER.toMove(fieldIdx);
    }

    private static String toString(SjoerdsGomokuPlayer.Move move) {
        final int fieldIdx = MOVE_CONVERTER.toFieldIdx(move);
        return MOVE_CONVERTER.toString(fieldIdx);
    }

    private static void expect(String expected, String actual, String desc) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError("Test " + desc + " NOK: Got: " + actual + "; expected: " + expected);
        }
    }
}
