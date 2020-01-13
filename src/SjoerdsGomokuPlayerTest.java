import java.util.Arrays;
import java.util.Objects;
import java.util.zip.DataFormatException;

public class SjoerdsGomokuPlayerTest {
    private static final SjoerdsGomokuPlayer.IO IO = new SjoerdsGomokuPlayer.IO(null, null, System.err, false);

    public static void main(String[] args) throws DataFormatException {
        IO.dbgPrinter.printMoves = true;
        IO.dbgPrinter.printMove("Opening 1", "Hh", IO.moveConverter.toMove(119));
        IO.dbgPrinter.printMove("Opening 2", "Ii", IO.moveConverter.toMove(136));
        IO.dbgPrinter.printMove("Opening 3", "Kh", IO.moveConverter.toMove(167));
        IO.dbgPrinter.printMoves = false;
        // Warm-up loading patterns and such
        //MOVE_GENERATOR.generateMove(newBoard("Aa", "Pp", "Bb", "Oo", "Cc", "Nn", "Dd", "Mm"));

        //System.out.println("Waiting for enter");
        //System.in.read();

        testMoves();
    }

    private static void testMoves() throws DataFormatException {
        testFinish5();
        testFinish4();

        testScenario1();
        testScenario2();
    }

    private static void testFinish5() throws DataFormatException {
        testMoveGen("Win horizontal", "Ae", "Aa", "Ba", "Ab", "Bb", "Ac", "Bc", "Ad", "Bd");
        testMoveGen("Win diagonal", "Ee", "Aa", "Da", "Bb", "Mm", "Cc", "Bc", "Dd", "Bd");
        testMoveGen("Block loss", "Ee", "Hi", "Aa", "Da", "Bb", "Mm", "Cc", "Bc", "Dd");
    }

    private static void testFinish4() throws DataFormatException {
        testMoveGen("Exploit open 3", "Ab", "Ac", "Ba", "Ad", "Bb", "Ae", "Bc");
        testMoveGen("Ignore enemy open 3", "Kg",  "Lg", "Lh", "Mg", "Mh", "Ng", "Nh");
        testMoveGen("Block open 3", "Kg", "Fo", "Lg", "Lh", "Mg", "Mh", "Ng");

        testMoveGen("Exploit double closed 3", "Dm", "Am", "Pp", "Bm", "Jg", "Cm", "Ap",
                "Dn", "Aa", "Do", "Pa", "Dp", "Ii");
        testMoveGen("Block double closed 3", "Dl", "Am", "Pp", "Bm", "Jg", "Cm", "Ap",
                "Dn", "Aa", "Do", "Pa", "Dp");
    }

    private static void testScenario1() throws DataFormatException {
        final SjoerdsGomokuPlayer.Board board = newBoard("Gh", "Jh", "Ji", "Kg", "Kh", "Mf", "Ki", "Mi", "Lg", "Lh",
                "Li", "Lm");
        //testScenario("Scenario 1", true, board, "Hi", "Ii", "Ij", "Hk",
        //        /* Finishing moves: */ "Fg", "Ef", "Jk");
        testScenario("Scenario 1", true, board, "Ij", "Hk", "Hi", "Ii",
                /* Finishing moves: */ "Fg", "Ef", "Jk");
    }

    private static void testScenario2() throws DataFormatException {
        final SjoerdsGomokuPlayer.Board board = newBoard("El", "Fk", "Ek", "Zz", "Ej", "Di", "Fj", "Gj", "Gk", "Fi",
                "Ei", "Dh", "Eh", "Eg", "Dj", "Hl", "Fl", "Fm", "Ff", "Bj", "Gg", "Ck", "Hf", "Fh", "Gf");
        //testScenario("Scenario 2", false, board,"Ak");
        testScenario("Scenario 2", false, board, "Ci", "Ak", "Cj");
    }

    private static void testScenario(final String desc, final boolean blackStarts, final SjoerdsGomokuPlayer.Board board, final String... steps)
            throws DataFormatException {
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

    private static void testMoveGen(String desc, String expectedMove, String... setupMoves) throws DataFormatException {
        final SjoerdsGomokuPlayer.Board board = newBoard(setupMoves);
        testMoveGen(desc, expectedMove, board);
    }

    private static void testMoveGen(final String desc, final String expectedMove,
            final SjoerdsGomokuPlayer.Board board) throws DataFormatException {
        final SjoerdsGomokuPlayer.PatternMatchMoveGenerator generator =
                new SjoerdsGomokuPlayer.PatternMatchMoveGenerator(IO.moveConverter, IO.dbgPrinter, IO.timer);
        IO.timer.timerStart = System.nanoTime();
        IO.timer.totalTime = 0;
        IO.timer.generatedMoves = 0;
        IO.timer.boardsScored = 0;

        final long start = System.nanoTime();
        final SjoerdsGomokuPlayer.Move move = generator.generateMove(board);
        final long end = System.nanoTime();

        expect(board.copy().apply(move), expectedMove, toString(move), desc);

        System.out.printf("Generated moves: %d, boards scored: %d\n", IO.timer.generatedMoves, IO.timer.boardsScored);
        System.out.printf("Test OK: %s (duration %d ns = %.5f s) %s\n", desc, end - start, ((double) end - start) * 1E-9D,
                durationWarning(end - start));
    }

    private static SjoerdsGomokuPlayer.Board newBoard(String... moves) {
        final SjoerdsGomokuPlayer.Board board = new SjoerdsGomokuPlayer.Board();

        Arrays.stream(moves)
                .map(SjoerdsGomokuPlayerTest::toMove)
                .forEach(board::apply);

        return board;
    }

    private static SjoerdsGomokuPlayer.Move toMove(String mv) {
        if (mv.equals("Zz")) {
            return SjoerdsGomokuPlayer.Move.SWITCH;
        }

        final int fieldIdx = SjoerdsGomokuPlayer.MoveConverter.toFieldIdx(mv.charAt(0), mv.charAt(1));
        return IO.moveConverter.toMove(fieldIdx);
    }

    private static String toString(SjoerdsGomokuPlayer.Move move) {
        final int fieldIdx = SjoerdsGomokuPlayer.MoveConverter.toFieldIdx(move);
        return SjoerdsGomokuPlayer.MoveConverter.toString(fieldIdx);
    }

    private static String durationWarning(final long duration) {
        if (duration > 15_000_000) {
            return ansi(103) + "WARNING! Long duration!" + ansi(0);
        }
        return "";
    }

    private static void expect(final SjoerdsGomokuPlayer.Board board, String expected, String actual, String desc) {
        if (!Objects.equals(expected, actual)) {
            TestDumper.printBoard(board, expected, actual);
            throw new AssertionError("Test " + desc + " NOK: Got: " + actual + "; expected: " + expected);
        }
    }

    private static String ansi(int fg, int bg) {
        return ansi(fg) + ansi(bg);
    }

    private static String ansi(int code) {
        final char ESC = '\u001b';
        return ESC + "[" + code + "m";
    }
}
