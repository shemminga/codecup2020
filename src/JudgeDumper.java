import java.io.InputStream;
import java.util.List;
import java.util.Scanner;

class JudgeDumper {
    private static final char ESC = '\u001b';
    private static final int BG_CYAN = 44;
    private static final int BG_GREY = 47;
    private static final int FG_GREY = 37;
    private static final int FG_BLACK = 30;
    private static final int FG_WHITE = 97;
    private static final int RESET = 0;

    static void sinkStreams(InputStream player1, InputStream player2) {
        sinkStream(player1, "P1: ");
        sinkStream(player2, "P2: ");
    }

    private static void sinkStream(InputStream in, String prefix) {
        final Thread thread = new Thread(() -> {
            final Scanner scanner = new Scanner(in);
            while (scanner.hasNext()) {
                System.err.println(prefix + scanner.nextLine());
            }
        });

        thread.setDaemon(true);
        thread.start();
    }

    static void printBoard(JudgeBoard board, String move, final StringBuilder builder) {
        int row = move.charAt(0) - 'A';
        int col = move.charAt(1) - 'a';

        builder.append("  abcd efgh ijkl mnop");
        for (int i = 0; i < JudgeBoard.BOARD_SIZE; i++) {
            if (i % 4 == 0) {
                builder.append(System.lineSeparator());
            }
            builder.append((char) ('A' + i));
            for (int j = 0; j < JudgeBoard.BOARD_SIZE; j++) {
                if (j % 4 == 0) {
                    builder.append(' ');
                }
                builder.append(stoneToString(board.getCell(i, j), row == i && col == j));
            }
            builder.append(' ')
                    .append((char) ('A' + i))
                    .append(System.lineSeparator());
        }
        builder.append("  abcd efgh ijkl mnop");
        builder.append(System.lineSeparator());
    }

    static void printMove(String move, JudgeBoard board) {
        final StringBuilder builder = new StringBuilder();
        builder.append(board.moveNumber)
                .append(" ")
                .append(board.playerToMove)
                .append(" ")
                .append(move)
                .append(System.lineSeparator());
        printBoard(board, move, builder);
        System.out.println(builder.toString());
    }

    private static String stoneToString(final JudgeBoard.Stone cell, final boolean isLastMove) {
        final int bg = isLastMove ? BG_CYAN : BG_GREY;

        switch (cell) {
        case NONE:
            return ansi(FG_GREY, BG_GREY) + "\u2b24" + ansi(RESET);
            //return /*ansi(30, 47) +*/ "\u00b7" /*+ ansi(0)*/;
        case WHITE:
            return ansi(FG_WHITE, bg) + "\u2b24" + ansi(RESET);
            //return "\u2b58";
        case BLACK:
            return ansi(FG_BLACK, bg) + "\u2b24" + ansi(RESET);
            //return "\u23fa";
        }
        throw new AssertionError();
    }

    private static String ansi(int fg, int bg) {
        return ansi(fg) + ansi(bg);
    }

    private static String ansi(int code) {
        return ESC + "[" + code + "m";
    }

    static void printResult(final JudgeBoard board) {
        System.out.println(board.gameResult + " in move " + board.moveNumber);
    }

    static void printAllMoves(final List<String> moves) {
        System.out.println(System.lineSeparator());
        System.out.println(System.lineSeparator());

        System.out.println("     P1   P2");

        for (int i = 0; i < moves.size(); i++) {
            String p1mv = (i < 3 || i % 2 == 0) ? moves.get(i) : "  ";
            String p2mv = (i > 2 && i % 2 == 1) ? moves.get(i) : "  ";

            System.out.printf("%3d. %s   %s\n", i + 1, p1mv, p2mv);
        }
    }
}
