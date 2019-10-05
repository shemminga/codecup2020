import java.io.InputStream;
import java.util.Scanner;

class JudgeDumper {
    static char ESC = '\u001b';

    JudgeDumper(InputStream player1, InputStream player2) {
        sinkStream(player1, "P1: ");
        sinkStream(player2, "P2: ");
    }

    private void sinkStream(InputStream in, String prefix) {
        final Thread thread = new Thread(() -> {
            final Scanner scanner = new Scanner(in);
            while (scanner.hasNext()) {
                scanner.nextLine();
                System.err.println(prefix + scanner.nextLine());
            }
        });

        thread.setDaemon(true);
        thread.start();
    }

    static void printBoard(JudgeBoard board, final StringBuilder builder) {
        for (int i = 0; i < JudgeBoard.BOARD_SIZE; i++) {
            if (i % 4 == 0) {
                builder.append(System.lineSeparator());
            }
            for (int j = 0; j < JudgeBoard.BOARD_SIZE; j++) {
                if (j % 4 == 0) {
                    builder.append(' ');
                }
                builder.append(stoneToString(board.getCell(i, j)));
            }
            builder.append(System.lineSeparator());
        }
        builder.append(System.lineSeparator());
    }

    void printMove(String move, JudgeBoard board) {
        final StringBuilder builder = new StringBuilder();
        builder.append(board.moveNumber)
                .append(" ")
                .append(board.playerToMove)
                .append(" ")
                .append(move)
                .append(System.lineSeparator());
        printBoard(board, builder);
        System.out.println(builder.toString());
    }

    private static String stoneToString(final JudgeBoard.Stone cell) {
        switch (cell) {
        case NONE:
            return ansi(37, 47) + "\u2b24" + ansi(0);
            //return /*ansi(30, 47) +*/ "\u00b7" /*+ ansi(0)*/;
        case WHITE:
            return ansi(97, 47) + "\u2b24" + ansi(0);
            //return "\u2b58";
        case BLACK:
            return ansi(30, 47) + "\u2b24" + ansi(0);
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

    void printResult(final JudgeBoard board) {
        System.out.println(board.gameResult + " in move " + board.moveNumber);
    }
}
