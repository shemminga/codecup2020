import java.util.Arrays;
import java.util.Objects;
import java.util.Random;

public class SjoerdsGomokuPlayerTest {
    private static final SjoerdsGomokuPlayer.DbgPrinter DBG_PRINTER =
            new SjoerdsGomokuPlayer.DbgPrinter(System.err, SjoerdsGomokuPlayer.START_UP_TIME, false);
    private static final SjoerdsGomokuPlayer.MoveConverter MOVE_CONVERTER =
            new SjoerdsGomokuPlayer.MoveConverter(DBG_PRINTER);
    protected static final SjoerdsGomokuPlayer.IO IO = SjoerdsGomokuPlayer.makeIO(DBG_PRINTER, null, null);
    protected static final SjoerdsGomokuPlayer.MoveGenerator MOVE_GENERATOR =
            SjoerdsGomokuPlayer.getMoveGenerator(new Random(), IO);

    public static void main(String[] args) {
        testMoves();
    }

    private static void testMoves() {

        testFinish5();
        testFinish4();
    }

    private static void testFinish5() {
        testMoveGen("Win horizontal", "Ae", "Aa", "Ba", "Ab", "Bb", "Ac", "Bc", "Ad", "Bd");
        testMoveGen("Win diagonal", "Ee", "Aa", "Da", "Bb", "Mm", "Cc", "Bc", "Dd", "Bd");
        testMoveGen("Block loss", "Ee", "Hi", "Aa", "Da", "Bb", "Mm", "Cc", "Bc", "Dd");
    }

    private static void testFinish4() {
        testMoveGen("Exploit open 3", "Af", "Ac", "Ba", "Ad", "Bb", "Ae", "Bc");
        testMoveGen("Ignore enemy open 3", "Kg",  "Lg", "Lh", "Mg", "Mh", "Ng", "Nh");
        testMoveGen("Block open 3", "Kg", "Fo", "Lg", "Lh", "Mg", "Mh", "Ng");

        testMoveGen("Exploit double closed 3", "Dm", "Am", "Pp", "Bm", "Jg", "Cm", "Ap",
                "Dn", "Aa", "Do", "Pa", "Dp", "Ii");
    }

    private static void testMoveGen(String desc, String expectedMove, String... setupMoves) {
        final SjoerdsGomokuPlayer.Board board = newBoard(setupMoves);
        final SjoerdsGomokuPlayer.Move move = MOVE_GENERATOR.generateMove(board);
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
