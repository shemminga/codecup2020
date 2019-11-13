import java.util.Arrays;

@SuppressWarnings("FieldCanBeLocal")
class TestDumper {
    private static int BG_RED = 41;
    private static int BG_CYAN = 44;
    private static int BG_GREY = 47;
    private static int FG_CYAN = 34;
    private static int FG_GREY = 37;
    private static int FG_BLACK = 30;
    private static int FG_WHITE = 97;
    private static int RESET = 0;

    private static final SjoerdsGomokuPlayer.MoveConverter MOVE_CONVERTER = new SjoerdsGomokuPlayer.MoveConverter();
    private static char ESC = '\u001b';

    static void printBoard(SjoerdsGomokuPlayer.Board board, final String expected, final String actual) {
        System.out.print("  abcd efgh ijkl mnop");

        for (int fieldIdx = 0; fieldIdx < 256; fieldIdx++) {
            final SjoerdsGomokuPlayer.Move move = MOVE_CONVERTER.toMove(fieldIdx);
            final String fieldStr = MOVE_CONVERTER.toString(fieldIdx);

            if (fieldIdx % 16 == 0) {
                if (fieldIdx > 0 && fieldIdx % 64 == 0) System.out.println();
                System.out.println();
                System.out.print(fieldStr.charAt(0));
            }

            if (fieldIdx % 4 == 0) {
                System.out.print(" ");
            }

            int fg = FG_GREY;
            int bg = BG_GREY;

            if (matches(board.playerStones, move)) {
                fg = FG_BLACK;
            } else if (matches(board.opponentStones, move)) {
                fg = FG_WHITE;
            }

            if (fieldStr.equals(expected)) {
                fg = FG_CYAN;
                bg = BG_CYAN;
            } else if (fieldStr.equals(actual)) {
                bg = BG_RED;
            }

            ansi(fg, bg);
            System.out.print("\u2b24");
            ansi(RESET);

            if (fieldIdx % 16 == 15) {
                System.out.print(" " + fieldStr.charAt(0));
            }
        }

        System.out.println();
        System.out.println("  abcd efgh ijkl mnop");
    }

    private static boolean matches(final long[] stones, final SjoerdsGomokuPlayer.Move move) {
        final long[] fields = new long[4];
        for (int i = 0; i < 4; i++) {
            fields[i] = stones[i] & move.move[i];
        }

        return Arrays.equals(fields, move.move);
    }

    private static void ansi(int fg, int bg) {
        System.out.print(ansiVal(fg) + ansiVal(bg));
    }

    private static void ansi(int code) {
        System.out.print(ansiVal(code));
    }

    private static String ansiVal(int code) {
        return ESC + "[" + code + "m";
    }
}
