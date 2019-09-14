import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.Random;

public class SjoerdsGomokuPlayer {
    private static final long START_UP_TIME = System.nanoTime();

    private final DbgPrinter dbgPrinter;
    private final MoveGenerator moveGenerator;
    private final Random rnd;
    private final IO io;

    public static void main(String[] args) throws IOException {
        final DbgPrinter dbgPrinter = new DbgPrinter(System.err, START_UP_TIME);

        @SuppressWarnings("NumericOverflow")
        final long seed = 8682522807148012L * 1181783497276652981L * System.nanoTime();
        dbgPrinter.log("SD " + seed);

        final Random rnd = new Random(seed);
        final IO io = new IO(new MoveConverter(dbgPrinter), System.in, System.out, dbgPrinter);

        MoveGenerator gen = board -> rnd.ints(0, 256)
                .mapToObj(io.moveConverter::toMove)
                .filter(board::validMove)
                .findAny()
                .orElseThrow();

        final SjoerdsGomokuPlayer player = new SjoerdsGomokuPlayer(gen, rnd, io, dbgPrinter);

        player.play();
    }

    private SjoerdsGomokuPlayer(final MoveGenerator moveGenerator, final Random rnd, final IO io,
            final DbgPrinter dbgPrinter) {
        this.moveGenerator = moveGenerator;
        this.rnd = rnd;
        this.io = io;
        this.dbgPrinter = dbgPrinter;
    }

    private void play() throws IOException {
        final Board board = new Board();

        final Move firstMove = io.readMove();

        if (firstMove == Move.START) {
            for (int i = 0; i < 3; i++) {
                final Move move = moveGenerator.generateMove(board);
                applyMove(board, move);
                io.outputMove(move);
            }
        } else {
            applyMove(board, firstMove);

            for (int i = 0; i < 2; i++) {
                Move move = io.readMove();
                applyMove(board, move);
            }

            if (rnd.nextBoolean()) {
                board.flip();
                io.outputMove(Move.SWITCH);
            } else {
                final Move move = moveGenerator.generateMove(board);
                applyMove(board, move);
                io.outputMove(move);
            }
        }

        while (true) {
            Move move = io.readMove();
            if (move == Move.QUIT) {
                dbgPrinter.log("Exit by command");
                return;
            }

            applyMove(board, move);

            final Move myMove = moveGenerator.generateMove(board);
            applyMove(board, myMove);
            io.outputMove(myMove);
        }
    }

    private void applyMove(Board board, Move move) {
        board.apply(move);
        dbgPrinter.printBoard("GB", board);
    }

    private static final class MoveConverter {
        private final DbgPrinter dbgPrinter;

        private MoveConverter(DbgPrinter dbgPrinter) {
            this.dbgPrinter = dbgPrinter;
        }

        private Move toMove(int fieldIdx) {
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
                return Move.SWITCH;
            } else {
                dbgPrinter.log("Unexpected move fieldIdx " + fieldIdx);
                moveLong = new long[]{0, 0, 0, 0};
            }

            return new Move(moveLong);
        }

        private int toFieldIdx(Move move) {
            return findBit(move.move[0], 0) + findBit(move.move[1], 64) + findBit(move.move[2], 128) +
                    findBit(move.move[3], 192);
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

    private static final class IO {
        private final MoveConverter moveConverter;
        private final InputStream in;
        private final PrintStream out;
        private final DbgPrinter dbgPrinter;

        private IO(MoveConverter moveConverter, InputStream in, PrintStream out, final DbgPrinter dbgPrinter) {
            this.moveConverter = moveConverter;
            this.in = in;
            this.out = out;
            this.dbgPrinter = dbgPrinter;
        }

        private Move readMove() throws IOException {
            dbgPrinter.separator();
            final int rowInt = robustRead();
            final int colInt = robustRead();

            final int fieldIdx = (rowInt - 'A') * 16 + (colInt - 'a');
            String moveStr = (char) rowInt + "" + (char) colInt;

            if (fieldIdx == 307) {
                // = "St", first letters of Start
                in.skip(3);
                dbgPrinter.printMove("IM", moveStr, Move.START);
                return Move.START;
            }

            if (fieldIdx == 276) {
                // = "Qu", first letters of Quit
                dbgPrinter.printMove("IM", moveStr, Move.QUIT);
                return Move.QUIT;
            }

            final Move move = moveConverter.toMove(fieldIdx);

            dbgPrinter.printMove("IM", moveStr, move);
            return move;
        }

        private int robustRead() throws IOException {
            int val;

            do {
                val = in.read();
                if (val < 0) {
                    throw new IOException("Unexpected end of input");
                }
            } while (val == 32 || val == 9 || val == 10 || val == 13); // Whitespace

            return val;
        }

        private void outputMove(Move move) {
            final String moveStr;
            if (move == Move.SWITCH) {
                moveStr = "Zz";
            } else {
                int fieldIdx = moveConverter.toFieldIdx(move);

                final int col = fieldIdx % 16;
                final int row = (fieldIdx - col) / 16;

                final char rowC = (char) (row + 'A');
                final char colC = (char) (col + 'a');
                moveStr = "" + rowC + colC;
            }

            dbgPrinter.printMove("OM", moveStr, move);

            dbgPrinter.flush(); // Flush debug output so debug and regular output are ordered correctly
            out.println(moveStr);
            out.flush();
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

            return this;
        }

        private Board flip() {
            long[] swap = playerPieces;
            playerPieces = opponentPieces;
            opponentPieces = swap;
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

    private static class DbgPrinter {
        private final PrintStream err;
        private final long startUpTime;

        private DbgPrinter(final PrintStream err, final long startUpTime) {
            this.err = err;
            this.startUpTime = startUpTime;

            log("Started up");
        }

        void printBoard(String type, Board board) {
            log(type + " " +
                    (board.playerToMove == Board.PLAYER ? "Pl" : "Op") + " " +
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

        private void log(String message) {
            err.println((System.nanoTime() - startUpTime) + " " + message);
        }

        private void separator() {
            err.println('-');
        }

        private void flush() {
            err.flush();
        }
    }

    @FunctionalInterface
    interface MoveGenerator {
        Move generateMove(Board board);
    }
}
