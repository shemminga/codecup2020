import java.util.Arrays;
import java.util.Objects;
import java.util.Random;

public class SjoerdsGomokuPlayerTest {
    private static char ESC = '\u001b';

    private static final SjoerdsGomokuPlayer.DbgPrinter DBG_PRINTER =
            new SjoerdsGomokuPlayer.DbgPrinter(System.err, SjoerdsGomokuPlayer.START_UP_TIME, false);
    private static final SjoerdsGomokuPlayer.MoveConverter MOVE_CONVERTER =
            new SjoerdsGomokuPlayer.MoveConverter(DBG_PRINTER);
    protected static final SjoerdsGomokuPlayer.IO IO = SjoerdsGomokuPlayer.makeIO(DBG_PRINTER, null, null);
    private static final SjoerdsGomokuPlayer.MoveGenerator MOVE_GENERATOR =
            SjoerdsGomokuPlayer.getMoveGenerator(new Random(), IO);

    public static void main(String[] args) {
        testMoves();
    }

    private static void testMoves() {
        // Warm-up loading patterns and such
        MOVE_GENERATOR.generateMove(newBoard("Aa", "Pp", "Bb", "Oo", "Cc", "Nn", "Dd", "Mm"));

        testFinish5();
        testFinish4();

        testScenario1();
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
        testMoveGen("Block double closed 3", "Dm", "Am", "Pp", "Bm", "Jg", "Cm", "Ap",
                "Dn", "Aa", "Do", "Pa", "Dp");
    }

    private static void testScenario1() {
        final SjoerdsGomokuPlayer.Board board = newBoard("Gh", "Jh", "Ji", "Kg", "Kh", "Mf", "Ki", "Mi", "Lg", "Lh",
                "Li", "Lm");
        testScenario("Scenario 1", true, board, "Hi", "Ii", "Ij", "Hk",
                /* Finishing moves: */ "Fg", "Ef", "Jk");
    }

    private static void testScenario(final String desc, final boolean blackStarts, final SjoerdsGomokuPlayer.Board board, final String... steps) {
        System.out.println("-\nScenario: " + desc);
        boolean blackOnTurn = blackStarts;
        for (int i = 0; i < steps.length; i++) {
            String stepDesc = String.format("%s - Step %d %s", desc, i + 1, blackOnTurn ? "Black" : "White");

            testMoveGen(stepDesc, steps[i], board);
            board.apply(toMove(steps[i]));

            blackOnTurn = !blackOnTurn;
        }
        System.out.println("Scenario OK: " + desc);
    }

    private static void testMoveGen(String desc, String expectedMove, String... setupMoves) {
        final SjoerdsGomokuPlayer.Board board = newBoard(setupMoves);
        testMoveGen(desc, expectedMove, board);
    }

    private static void testMoveGen(final String desc, final String expectedMove,
            final SjoerdsGomokuPlayer.Board board) {
        final long start = System.nanoTime();
        final SjoerdsGomokuPlayer.Move move = MOVE_GENERATOR.generateMove(board);
        final long end = System.nanoTime();
        expect(expectedMove, toString(move), desc);
        System.out.printf("Test OK: %s (duration %d ns = %.5f s) %s\n", desc, end - start, ((double) end - start) * 1E-9D,
                durationWarning(end - start));
    }

    private static SjoerdsGomokuPlayer.Board newBoard(String... moves) {
        final SjoerdsGomokuPlayer.Board board = new SjoerdsGomokuPlayer.Board();

        Arrays.stream(moves)
                .map(SjoerdsGomokuPlayerTest::toMove)
                .forEach(board::apply);

        if (moves.length % 2 == 1) {
            // Play opponent
            board.flip();
        }

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

    private static String durationWarning(final long duration) {
        if (duration > 15_000_000) {
            return ansi(103) + "WARNING! Long duration!" + ansi(0);
        }
        return "";
    }

    private static void expect(String expected, String actual, String desc) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError("Test " + desc + " NOK: Got: " + actual + "; expected: " + expected);
        }
    }

    private static String ansi(int fg, int bg) {
        return ansi(fg) + ansi(bg);
    }

    private static String ansi(int code) {
        return ESC + "[" + code + "m";
    }
}
