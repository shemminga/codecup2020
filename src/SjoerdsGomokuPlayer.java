import java.io.IOException;
import java.util.Random;

public class SjoerdsGomokuPlayer {
    private static final long START_UP_TIME = System.nanoTime();
    private static final PrintTool PRINTER = new PrintTool();
    private static final Random rnd = new Random();

    static {
        @SuppressWarnings("NumericOverflow")
        final long seed = 8682522807148012L * 1181783497276652981L * System.nanoTime();
        rnd.setSeed(seed);
        log("SD " + seed);
    }

    private static void log(String message) {
        System.err.println((System.nanoTime() - START_UP_TIME) + " " + message);
    }

    public static void main(String[] args) {
        try {
            main();
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(0); // Exit nicely, cause why not?
        }
    }

    @SuppressWarnings("InfiniteLoopStatement")
    private static void main() throws IOException {
        final Board board = new Board();

        final Move firstMove = IO.readMove();

        if (firstMove == Move.START) {
            for (int i = 0; i < 3; i++) {
                final Move move = generateMove(board);
                board.apply(move);
                IO.outputMove(move);
            }
        } else {
            board.apply(firstMove);

            for (int i = 0; i < 2; i++) {
                Move move = IO.readMove();
                board.apply(move);
            }

            if (rnd.nextBoolean()) {
                board.flip();
                IO.outputMove(Move.SWITCH);
            } else {
                final Move move = generateMove(board);
                board.apply(move);
                IO.outputMove(move);
            }
        }

        while (true) {
            Move move = IO.readMove();
            board.apply(move);

            final Move myMove = generateMove(board);
            board.apply(myMove);
            IO.outputMove(myMove);
        }
    }

    private static Move generateMove(Board board) {
        return rnd.ints(0, 256)
                .mapToObj(Move::fromFieldIdx)
                .filter(board::validMove)
                .findAny()
                .orElseThrow();
    }

    private static final class IO {
        private static Move readMove() throws IOException {
            System.err.println('-');
            final int rowInt = robustRead();
            final int colInt = robustRead();

            final int fieldIdx = (rowInt - 'A') * 16 + (colInt - 'a');
            String moveStr = (char) rowInt + "" + (char) colInt;

            if (fieldIdx == 307) {
                // = "St", first letters of Start
                System.in.skip(3);
                PRINTER.printMove("IM", moveStr, Move.START);
                return Move.START;
            }

            if (fieldIdx == 276) {
                // = "Qu", first letters of Quit
                PRINTER.printMove("IM", moveStr, Move.QUIT);
                log("Exit by command");
                System.exit(0);
            }

            final Move move = Move.fromFieldIdx(fieldIdx);

            PRINTER.printMove("IM", moveStr, move);
            return move;
        }

        private static int robustRead() throws IOException {
            int val;

            do {
                val = System.in.read();
                if (val < 0) {
                    // End of game
                    System.exit(0);
                }
            } while (val == 32 || val == 9 || val == 10 ||
                    val == 13); // Skip some whitespace, to help with buffered input

            return val;
        }

        private static void outputMove(Move move) {
            final String moveStr;
            if (move == Move.SWITCH) {
                moveStr = "Zz";
            } else {
                int fieldIdx = move.toFieldIdx();

                final int col = fieldIdx % 16;
                final int row = (fieldIdx - col) / 16;

                final char rowC = (char) (row + 'A');
                final char colC = (char) (col + 'a');
                moveStr = "" + rowC + colC;
            }

            PRINTER.printMove("OM", moveStr, move);

            System.err.flush(); // Flush stderr so debug output and regular output are ordered correctly
            System.out.println(moveStr);
            System.out.flush();
        }
    }

    private static final class Move {
        private static final Move START = new Move(new long[]{0, 0, 0, 0});
        private static final Move QUIT = new Move(new long[]{0, 0, 0, 0});
        private static final Move SWITCH = new Move(new long[]{0, 0, 0, 0});

        private long[] move;

        private Move(final long[] move) {
            this.move = move;
        }

        private static Move fromFieldIdx(final int fieldIdx) {
            long[] moveLong;
            if (fieldIdx < 64) {
                moveLong = new long[]{Long.MIN_VALUE >>> fieldIdx, 0, 0, 0};
            } else if (fieldIdx < 128) {
                moveLong = new long[]{0, Long.MIN_VALUE >>> (fieldIdx - 64), 0, 0};
            } else if (fieldIdx < 192) {
                moveLong = new long[]{0, 0, Long.MIN_VALUE >>> (fieldIdx - 128), 0};
            } else if (fieldIdx < 256) {
                moveLong = new long[]{0, 0, 0, Long.MIN_VALUE >>> (fieldIdx - 192)};
            } else if (fieldIdx == 425) {
                // "Zz", meaning the other side lets us play
                return SWITCH;
            } else {
                log("Unexpected move fieldIdx " + fieldIdx);
                moveLong = new long[]{0, 0, 0, 0};
            }

            return new Move(moveLong);
        }

        private int toFieldIdx() {
            return findBit(move[0], 0) + findBit(move[1], 64) + findBit(move[2], 128) + findBit(move[3], 192);
        }

        private static int findBit(final long l, final int offset) {
            if (l == 0) {
                return 0;
            }

            for (int i = 0; i < 64; i++) {
                if (l << i == Long.MIN_VALUE) {
                    return i + offset;
                }
            }

            return 0;
        }
    }

    private static final class Board {
        private static final int PLAYER = 0;
        private static final int OPPONENT = ~0;

        private int playerToMove = PLAYER;
        private long[] playerPieces = new long[]{0, 0, 0, 0};
        private long[] opponentPieces = new long[]{0, 0, 0, 0};

        private Board apply(Move move) {
            if (move == Move.SWITCH) {
                return flip();
            }

            long[] updatee = playerToMove == PLAYER ? playerPieces : opponentPieces;

            for (int i = 0; i < 4; i++) {
                updatee[i] |= move.move[i];
            }

            playerToMove = ~playerToMove;

            PRINTER.printBoard("GB", this);
            return this;
        }

        private Board flip() {
            long[] swap = playerPieces;
            playerPieces = opponentPieces;
            opponentPieces = swap;

            PRINTER.printBoard("GB", this);
            return this;
        }

        boolean validMove(Move move) {
            long[] results = new long[4];
            long[] resultsP = new long[4];
            long[] resultsO = new long[4];

            for (int i = 0; i < 4; i++) {
                resultsP[i] = (playerPieces[i] & move.move[i]);
            }

            for (int i = 0; i < 4; i++) {
                resultsO[i] = (opponentPieces[i] & move.move[i]);
            }

            for (int i = 0; i < 4; i++) {
                results[i] = resultsP[i] | resultsO[i];
            }

            return (results[0] | results[1] | results[2] | results[3]) == 0;
        }
    }

    public static class PrintTool {
        void printBoard(String type, Board board) {
            log(type + " " +
                    (board.playerToMove == Board.PLAYER ? "P" : "O") + " " +
                    Long.toHexString(board.playerPieces[0]) + " " +
                    Long.toHexString(board.playerPieces[1]) + " " +
                    Long.toHexString(board.playerPieces[2]) + " " +
                    Long.toHexString(board.playerPieces[3]) + " / " +

                    Long.toHexString(board.opponentPieces[0]) + " " +
                    Long.toHexString(board.opponentPieces[1]) + " " +
                    Long.toHexString(board.opponentPieces[2]) + " " +
                    Long.toHexString(board.opponentPieces[3]));
        }

        void printMove(String type, String moveStr, Move move) {
            if (move == Move.START) {
                log(type + " " + moveStr + " START");
            } else if (move == Move.QUIT) {
                log(type + " " + moveStr + " QUIT");
            } else if (move == Move.SWITCH) {
                log(type + " " + moveStr + " SWITCH");
            } else {
                log(type + " " + moveStr + " " + Long.toHexString(move.move[0]) + " " + Long.toHexString(move.move[1]) +
                        " " + Long.toHexString(move.move[2]) + " " + Long.toHexString(move.move[3]));
            }
        }
    }
}
