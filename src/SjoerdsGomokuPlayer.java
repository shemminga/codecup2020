import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.Random;

public class SjoerdsGomokuPlayer {
    static final long START_UP_TIME = System.nanoTime();

    private final DbgPrinter dbgPrinter;
    private final MoveGenerator moveGenerator;
    private final Random rnd;
    private final IO io;

    public static void main(String[] args) throws IOException {
        final DbgPrinter dbgPrinter = new DbgPrinter(System.err, START_UP_TIME, true);

        final Random rnd = makeRandom(dbgPrinter);
        final IO io = makeIO(dbgPrinter, System.in, System.out);

        MoveGenerator gen = getMoveGenerator(rnd, io);

        final SjoerdsGomokuPlayer player = new SjoerdsGomokuPlayer(gen, rnd, io, dbgPrinter);

        player.play();
    }

    static MoveGenerator getMoveGenerator(final Random rnd, final IO io) {
        return new CombinedMoveGenerator(io,
                new WinImmediatePatternMoveGenerator(io.moveConverter),
                new PreventImmediateLossPatternMoveGenerator(io.moveConverter),
                new MonteCarloMoveGenerator(rnd, io.moveConverter)
        );
    }

    static IO makeIO(final DbgPrinter dbgPrinter, final InputStream in, final PrintStream out) {
        return new IO(new MoveConverter(dbgPrinter), in, out, dbgPrinter);
    }

    static Random makeRandom(final DbgPrinter dbgPrinter) {
        @SuppressWarnings("NumericOverflow")
        final long seed = 8682522807148012L * 1181783497276652981L * System.nanoTime();
        dbgPrinter.log("SD " + seed);

        return new Random(seed);
    }

    SjoerdsGomokuPlayer(final MoveGenerator moveGenerator, final Random rnd, final IO io, final DbgPrinter dbgPrinter) {
        this.moveGenerator = moveGenerator;
        this.rnd = rnd;
        this.io = io;
        this.dbgPrinter = dbgPrinter;
    }

    void play() throws IOException {
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

    static final class MoveConverter {
        private final DbgPrinter dbgPrinter;

        MoveConverter(DbgPrinter dbgPrinter) {
            this.dbgPrinter = dbgPrinter;
        }

        Move toMove(int fieldIdx) {
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

        int toFieldIdx(Move move) {
            return findBit(move.move[0], 0) + findBit(move.move[1], 64) + findBit(move.move[2], 128) +
                    findBit(move.move[3], 192);
        }

        int toFieldIdx(int rowInt, int colInt) {
            return (rowInt - 'A') * 16 + (colInt - 'a');
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

        private String toString(final int fieldIdx) {
            final int col = fieldIdx % 16;
            final int row = (fieldIdx - col) / 16;

            final char rowC = (char) (row + 'A');
            final char colC = (char) (col + 'a');
            return "" + rowC + colC;
        }
    }

    static final class IO {
        private final MoveConverter moveConverter;
        private final InputStream in;
        private final PrintStream out;
        private final DbgPrinter dbgPrinter;

        IO(MoveConverter moveConverter, InputStream in, PrintStream out, final DbgPrinter dbgPrinter) {
            this.moveConverter = moveConverter;
            this.in = in;
            this.out = out;
            this.dbgPrinter = dbgPrinter;
        }

        private Move readMove() throws IOException {
            dbgPrinter.separator();
            final int rowInt = robustRead();
            final int colInt = robustRead();

            final int fieldIdx = moveConverter.toFieldIdx(rowInt, colInt);
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
                moveStr = moveConverter.toString(fieldIdx);
            }

            dbgPrinter.printMove("OM", moveStr, move);

            dbgPrinter.flush(); // Flush debug output so debug and regular output are ordered correctly
            out.println(moveStr);
            out.flush();
        }
    }

    static final class Move {
        private static final Move START = new Move(new long[]{0, 0, 0, 0});
        private static final Move QUIT = new Move(new long[]{0, 0, 0, 0});
        private static final Move SWITCH = new Move(new long[]{0, 0, 0, 0});

        private long[] move;

        private Move(final long[] move) {
            this.move = move;
        }
    }

    static final class Board {
        static final int PLAYER = 0;
        static final int OPPONENT = ~0;

        int playerToMove = PLAYER;
        long[] playerStones = new long[]{0, 0, 0, 0};
        long[] opponentStones = new long[]{0, 0, 0, 0};

        Board apply(Move move) {
            if (move == Move.SWITCH) {
                return flip();
            }

            long[] updatee = playerToMove == PLAYER ? playerStones : opponentStones;

            for (int i = 0; i < 4; i++) {
                updatee[i] |= move.move[i];
            }

            playerToMove = ~playerToMove;

            return this;
        }

        private Board flip() {
            long[] swap = playerStones;
            playerStones = opponentStones;
            opponentStones = swap;
            playerToMove = ~playerToMove;
            return this;
        }

        boolean validMove(Move move) {
            long[] results = new long[4];
            long[] resultsP = new long[4];
            long[] resultsO = new long[4];

            for (int i = 0; i < 4; i++) {
                resultsP[i] = (playerStones[i] & move.move[i]);
            }

            for (int i = 0; i < 4; i++) {
                resultsO[i] = (opponentStones[i] & move.move[i]);
            }

            for (int i = 0; i < 4; i++) {
                results[i] = resultsP[i] | resultsO[i];
            }

            return (results[0] | results[1] | results[2] | results[3]) == 0;
        }
    }

    static class DbgPrinter {
        private final PrintStream err;
        private final long startUpTime;
        private final boolean printBoardAndMoves;

        DbgPrinter(final PrintStream err, final long startUpTime, final boolean printBoardAndMoves) {
            this.err = err;
            this.startUpTime = startUpTime;
            this.printBoardAndMoves = printBoardAndMoves;

            log("Started up");
        }

        void printBoard(String type, Board board) {
            if (printBoardAndMoves) {
                log(type + " " +
                        (board.playerToMove == Board.PLAYER ? "Pl" : "Op") + " " +
                        Long.toHexString(board.playerStones[0]) + " " +
                        Long.toHexString(board.playerStones[1]) + " " +
                        Long.toHexString(board.playerStones[2]) + " " +
                        Long.toHexString(board.playerStones[3]) + " / " +

                        Long.toHexString(board.opponentStones[0]) + " " +
                        Long.toHexString(board.opponentStones[1]) + " " +
                        Long.toHexString(board.opponentStones[2]) + " " +
                        Long.toHexString(board.opponentStones[3]));
            }
        }

        void printMove(String type, String moveStr, Move move) {
            if (printBoardAndMoves) {
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

    static class Pattern {
        final long[] relevantFields;
        final long[] playerStones;
        final int fieldIdx;

        Pattern(final long[] relevantFields, final long[] playerStones, final int fieldIdx) {
            this.relevantFields = relevantFields;
            this.playerStones = playerStones;
            this.fieldIdx = fieldIdx;
        }
    }

    @FunctionalInterface
    interface MoveGenerator {
        Move generateMove(Board board);
    }

    static class CombinedMoveGenerator implements MoveGenerator {
        private final IO io;
        private final MoveGenerator[] generators;

        CombinedMoveGenerator(final IO io, final MoveGenerator... generators) {
            this.io = io;
            this.generators = generators;
        }

        @Override
        public Move generateMove(final Board board) {
            for (MoveGenerator generator : generators) {
                final Move move = generator.generateMove(board);
                if (move != null) {
                    io.dbgPrinter.log("Using move from " + generator.getClass().getName());
                    return move;
                }
            }
            return null;
        }
    }

    static class MonteCarloMoveGenerator implements MoveGenerator {
        private final Random rnd;
        private final MoveConverter moveConverter;

        private MonteCarloMoveGenerator(final Random rnd, final MoveConverter moveConverter) {
            this.rnd = rnd;
            this.moveConverter = moveConverter;
        }

        @Override
        public Move generateMove(final Board board) {
            return rnd.ints(0, 256)
                    .mapToObj(moveConverter::toMove)
                    .filter(board::validMove)
                    .findAny()
                    .orElseThrow();
        }
    }

    static class WinImmediatePatternMoveGenerator implements MoveGenerator {
        private final MoveConverter moveConverter;

        WinImmediatePatternMoveGenerator(final MoveConverter moveConverter) {
            this.moveConverter = moveConverter;
        }

        @Override
        public Move generateMove(final Board board) {
            for (final Pattern p : Patterns.immediates) {
                long[] fields = new long[4];

                for (int i = 0; i < 4; i++) {
                    fields[i] = board.playerStones[i] & p.playerStones[i];
                }

                if (fields[0] == p.playerStones[0] && fields[1] == p.playerStones[1] &&
                        fields[2] == p.playerStones[2] && fields[3] == p.playerStones[3]) {
                    final Move move = moveConverter.toMove(p.fieldIdx);
                    if (board.validMove(move)) {
                        return move;
                    }
                }
            }

            return null;
        }
    }

    static class PreventImmediateLossPatternMoveGenerator implements MoveGenerator {
        private final MoveConverter moveConverter;

        PreventImmediateLossPatternMoveGenerator(final MoveConverter moveConverter) {
            this.moveConverter = moveConverter;
        }

        @Override
        public Move generateMove(final Board board) {
            for (final Pattern p : Patterns.immediates) {
                long[] fields = new long[4];

                for (int i = 0; i < 4; i++) {
                    fields[i] = board.opponentStones[i] & p.playerStones[i];
                }

                if (fields[0] == p.playerStones[0] && fields[1] == p.playerStones[1] &&
                        fields[2] == p.playerStones[2] && fields[3] == p.playerStones[3]) {
                    final Move move = moveConverter.toMove(p.fieldIdx);
                    if (board.validMove(move)) {
                        return move;
                    }
                }
            }

            return null;
        }
    }

    static class Patterns {
        final static Pattern[] immediates = new Pattern[3360];

        private static void initImmediates0() {
            immediates[0] = new Pattern(new long[]{0xf800000000000000L, 0L, 0L, 0L},
                    new long[]{0x7800000000000000L, 0L, 0L, 0L}, 0);
            immediates[1] =
                    new Pattern(new long[]{0xf80000000000L, 0L, 0L, 0L}, new long[]{0x780000000000L, 0L, 0L, 0L}, 16);
            immediates[2] = new Pattern(new long[]{4160749568L, 0L, 0L, 0L}, new long[]{2013265920L, 0L, 0L, 0L}, 32);
            immediates[3] = new Pattern(new long[]{63488L, 0L, 0L, 0L}, new long[]{30720L, 0L, 0L, 0L}, 48);
            immediates[4] = new Pattern(new long[]{0L, 0xf800000000000000L, 0L, 0L},
                    new long[]{0L, 0x7800000000000000L, 0L, 0L}, 64);
            immediates[5] =
                    new Pattern(new long[]{0L, 0xf80000000000L, 0L, 0L}, new long[]{0L, 0x780000000000L, 0L, 0L}, 80);
            immediates[6] = new Pattern(new long[]{0L, 4160749568L, 0L, 0L}, new long[]{0L, 2013265920L, 0L, 0L}, 96);
            immediates[7] = new Pattern(new long[]{0L, 63488L, 0L, 0L}, new long[]{0L, 30720L, 0L, 0L}, 112);
            immediates[8] = new Pattern(new long[]{0L, 0L, 0xf800000000000000L, 0L},
                    new long[]{0L, 0L, 0x7800000000000000L, 0L}, 128);
            immediates[9] =
                    new Pattern(new long[]{0L, 0L, 0xf80000000000L, 0L}, new long[]{0L, 0L, 0x780000000000L, 0L}, 144);
            immediates[10] = new Pattern(new long[]{0L, 0L, 4160749568L, 0L}, new long[]{0L, 0L, 2013265920L, 0L}, 160);
            immediates[11] = new Pattern(new long[]{0L, 0L, 63488L, 0L}, new long[]{0L, 0L, 30720L, 0L}, 176);
            immediates[12] = new Pattern(new long[]{0L, 0L, 0L, 0xf800000000000000L},
                    new long[]{0L, 0L, 0L, 0x7800000000000000L}, 192);
            immediates[13] =
                    new Pattern(new long[]{0L, 0L, 0L, 0xf80000000000L}, new long[]{0L, 0L, 0L, 0x780000000000L}, 208);
            immediates[14] = new Pattern(new long[]{0L, 0L, 0L, 4160749568L}, new long[]{0L, 0L, 0L, 2013265920L}, 224);
            immediates[15] = new Pattern(new long[]{0L, 0L, 0L, 63488L}, new long[]{0L, 0L, 0L, 30720L}, 240);
            immediates[16] = new Pattern(new long[]{0x7c00000000000000L, 0L, 0L, 0L},
                    new long[]{0x3c00000000000000L, 0L, 0L, 0L}, 1);
            immediates[17] =
                    new Pattern(new long[]{0x7c0000000000L, 0L, 0L, 0L}, new long[]{65970697666560L, 0L, 0L, 0L}, 17);
            immediates[18] = new Pattern(new long[]{2080374784L, 0L, 0L, 0L}, new long[]{1006632960L, 0L, 0L, 0L}, 33);
            immediates[19] = new Pattern(new long[]{31744L, 0L, 0L, 0L}, new long[]{15360L, 0L, 0L, 0L}, 49);
            immediates[20] = new Pattern(new long[]{0L, 0x7c00000000000000L, 0L, 0L},
                    new long[]{0L, 0x3c00000000000000L, 0L, 0L}, 65);
            immediates[21] =
                    new Pattern(new long[]{0L, 0x7c0000000000L, 0L, 0L}, new long[]{0L, 65970697666560L, 0L, 0L}, 81);
            immediates[22] = new Pattern(new long[]{0L, 2080374784L, 0L, 0L}, new long[]{0L, 1006632960L, 0L, 0L}, 97);
            immediates[23] = new Pattern(new long[]{0L, 31744L, 0L, 0L}, new long[]{0L, 15360L, 0L, 0L}, 113);
            immediates[24] = new Pattern(new long[]{0L, 0L, 0x7c00000000000000L, 0L},
                    new long[]{0L, 0L, 0x3c00000000000000L, 0L}, 129);
            immediates[25] =
                    new Pattern(new long[]{0L, 0L, 0x7c0000000000L, 0L}, new long[]{0L, 0L, 65970697666560L, 0L}, 145);
            immediates[26] = new Pattern(new long[]{0L, 0L, 2080374784L, 0L}, new long[]{0L, 0L, 1006632960L, 0L}, 161);
            immediates[27] = new Pattern(new long[]{0L, 0L, 31744L, 0L}, new long[]{0L, 0L, 15360L, 0L}, 177);
            immediates[28] = new Pattern(new long[]{0L, 0L, 0L, 0x7c00000000000000L},
                    new long[]{0L, 0L, 0L, 0x3c00000000000000L}, 193);
            immediates[29] =
                    new Pattern(new long[]{0L, 0L, 0L, 0x7c0000000000L}, new long[]{0L, 0L, 0L, 65970697666560L}, 209);
            immediates[30] = new Pattern(new long[]{0L, 0L, 0L, 2080374784L}, new long[]{0L, 0L, 0L, 1006632960L}, 225);
            immediates[31] = new Pattern(new long[]{0L, 0L, 0L, 31744L}, new long[]{0L, 0L, 0L, 15360L}, 241);
            immediates[32] = new Pattern(new long[]{0x3e00000000000000L, 0L, 0L, 0L},
                    new long[]{0x1e00000000000000L, 0L, 0L, 0L}, 2);
            immediates[33] =
                    new Pattern(new long[]{68169720922112L, 0L, 0L, 0L}, new long[]{32985348833280L, 0L, 0L, 0L}, 18);
            immediates[34] = new Pattern(new long[]{1040187392L, 0L, 0L, 0L}, new long[]{503316480L, 0L, 0L, 0L}, 34);
            immediates[35] = new Pattern(new long[]{15872L, 0L, 0L, 0L}, new long[]{7680L, 0L, 0L, 0L}, 50);
            immediates[36] = new Pattern(new long[]{0L, 0x3e00000000000000L, 0L, 0L},
                    new long[]{0L, 0x1e00000000000000L, 0L, 0L}, 66);
            immediates[37] =
                    new Pattern(new long[]{0L, 68169720922112L, 0L, 0L}, new long[]{0L, 32985348833280L, 0L, 0L}, 82);
            immediates[38] = new Pattern(new long[]{0L, 1040187392L, 0L, 0L}, new long[]{0L, 503316480L, 0L, 0L}, 98);
            immediates[39] = new Pattern(new long[]{0L, 15872L, 0L, 0L}, new long[]{0L, 7680L, 0L, 0L}, 114);
            immediates[40] = new Pattern(new long[]{0L, 0L, 0x3e00000000000000L, 0L},
                    new long[]{0L, 0L, 0x1e00000000000000L, 0L}, 130);
            immediates[41] =
                    new Pattern(new long[]{0L, 0L, 68169720922112L, 0L}, new long[]{0L, 0L, 32985348833280L, 0L}, 146);
            immediates[42] = new Pattern(new long[]{0L, 0L, 1040187392L, 0L}, new long[]{0L, 0L, 503316480L, 0L}, 162);
            immediates[43] = new Pattern(new long[]{0L, 0L, 15872L, 0L}, new long[]{0L, 0L, 7680L, 0L}, 178);
            immediates[44] = new Pattern(new long[]{0L, 0L, 0L, 0x3e00000000000000L},
                    new long[]{0L, 0L, 0L, 0x1e00000000000000L}, 194);
            immediates[45] =
                    new Pattern(new long[]{0L, 0L, 0L, 68169720922112L}, new long[]{0L, 0L, 0L, 32985348833280L}, 210);
            immediates[46] = new Pattern(new long[]{0L, 0L, 0L, 1040187392L}, new long[]{0L, 0L, 0L, 503316480L}, 226);
            immediates[47] = new Pattern(new long[]{0L, 0L, 0L, 15872L}, new long[]{0L, 0L, 0L, 7680L}, 242);
            immediates[48] =
                    new Pattern(new long[]{0x1f00000000000000L, 0L, 0L, 0L}, new long[]{0xf00000000000000L, 0L, 0L, 0L},
                            3);
            immediates[49] =
                    new Pattern(new long[]{34084860461056L, 0L, 0L, 0L}, new long[]{0xf0000000000L, 0L, 0L, 0L}, 19);
            immediates[50] = new Pattern(new long[]{520093696L, 0L, 0L, 0L}, new long[]{251658240L, 0L, 0L, 0L}, 35);
            immediates[51] = new Pattern(new long[]{7936L, 0L, 0L, 0L}, new long[]{3840L, 0L, 0L, 0L}, 51);
            immediates[52] =
                    new Pattern(new long[]{0L, 0x1f00000000000000L, 0L, 0L}, new long[]{0L, 0xf00000000000000L, 0L, 0L},
                            67);
            immediates[53] =
                    new Pattern(new long[]{0L, 34084860461056L, 0L, 0L}, new long[]{0L, 0xf0000000000L, 0L, 0L}, 83);
            immediates[54] = new Pattern(new long[]{0L, 520093696L, 0L, 0L}, new long[]{0L, 251658240L, 0L, 0L}, 99);
            immediates[55] = new Pattern(new long[]{0L, 7936L, 0L, 0L}, new long[]{0L, 3840L, 0L, 0L}, 115);
            immediates[56] =
                    new Pattern(new long[]{0L, 0L, 0x1f00000000000000L, 0L}, new long[]{0L, 0L, 0xf00000000000000L, 0L},
                            131);
            immediates[57] =
                    new Pattern(new long[]{0L, 0L, 34084860461056L, 0L}, new long[]{0L, 0L, 0xf0000000000L, 0L}, 147);
            immediates[58] = new Pattern(new long[]{0L, 0L, 520093696L, 0L}, new long[]{0L, 0L, 251658240L, 0L}, 163);
            immediates[59] = new Pattern(new long[]{0L, 0L, 7936L, 0L}, new long[]{0L, 0L, 3840L, 0L}, 179);
            immediates[60] =
                    new Pattern(new long[]{0L, 0L, 0L, 0x1f00000000000000L}, new long[]{0L, 0L, 0L, 0xf00000000000000L},
                            195);
            immediates[61] =
                    new Pattern(new long[]{0L, 0L, 0L, 34084860461056L}, new long[]{0L, 0L, 0L, 0xf0000000000L}, 211);
            immediates[62] = new Pattern(new long[]{0L, 0L, 0L, 520093696L}, new long[]{0L, 0L, 0L, 251658240L}, 227);
            immediates[63] = new Pattern(new long[]{0L, 0L, 0L, 7936L}, new long[]{0L, 0L, 0L, 3840L}, 243);
            immediates[64] =
                    new Pattern(new long[]{0xf80000000000000L, 0L, 0L, 0L}, new long[]{0x780000000000000L, 0L, 0L, 0L},
                            4);
            immediates[65] =
                    new Pattern(new long[]{0xf8000000000L, 0L, 0L, 0L}, new long[]{8246337208320L, 0L, 0L, 0L}, 20);
            immediates[66] = new Pattern(new long[]{260046848L, 0L, 0L, 0L}, new long[]{125829120L, 0L, 0L, 0L}, 36);
            immediates[67] = new Pattern(new long[]{3968L, 0L, 0L, 0L}, new long[]{1920L, 0L, 0L, 0L}, 52);
            immediates[68] =
                    new Pattern(new long[]{0L, 0xf80000000000000L, 0L, 0L}, new long[]{0L, 0x780000000000000L, 0L, 0L},
                            68);
            immediates[69] =
                    new Pattern(new long[]{0L, 0xf8000000000L, 0L, 0L}, new long[]{0L, 8246337208320L, 0L, 0L}, 84);
            immediates[70] = new Pattern(new long[]{0L, 260046848L, 0L, 0L}, new long[]{0L, 125829120L, 0L, 0L}, 100);
            immediates[71] = new Pattern(new long[]{0L, 3968L, 0L, 0L}, new long[]{0L, 1920L, 0L, 0L}, 116);
            immediates[72] =
                    new Pattern(new long[]{0L, 0L, 0xf80000000000000L, 0L}, new long[]{0L, 0L, 0x780000000000000L, 0L},
                            132);
            immediates[73] =
                    new Pattern(new long[]{0L, 0L, 0xf8000000000L, 0L}, new long[]{0L, 0L, 8246337208320L, 0L}, 148);
            immediates[74] = new Pattern(new long[]{0L, 0L, 260046848L, 0L}, new long[]{0L, 0L, 125829120L, 0L}, 164);
            immediates[75] = new Pattern(new long[]{0L, 0L, 3968L, 0L}, new long[]{0L, 0L, 1920L, 0L}, 180);
            immediates[76] =
                    new Pattern(new long[]{0L, 0L, 0L, 0xf80000000000000L}, new long[]{0L, 0L, 0L, 0x780000000000000L},
                            196);
            immediates[77] =
                    new Pattern(new long[]{0L, 0L, 0L, 0xf8000000000L}, new long[]{0L, 0L, 0L, 8246337208320L}, 212);
            immediates[78] = new Pattern(new long[]{0L, 0L, 0L, 260046848L}, new long[]{0L, 0L, 0L, 125829120L}, 228);
            immediates[79] = new Pattern(new long[]{0L, 0L, 0L, 3968L}, new long[]{0L, 0L, 0L, 1920L}, 244);
            immediates[80] =
                    new Pattern(new long[]{0x7c0000000000000L, 0L, 0L, 0L}, new long[]{0x3c0000000000000L, 0L, 0L, 0L},
                            5);
            immediates[81] =
                    new Pattern(new long[]{8521215115264L, 0L, 0L, 0L}, new long[]{4123168604160L, 0L, 0L, 0L}, 21);
            immediates[82] = new Pattern(new long[]{130023424L, 0L, 0L, 0L}, new long[]{62914560L, 0L, 0L, 0L}, 37);
            immediates[83] = new Pattern(new long[]{1984L, 0L, 0L, 0L}, new long[]{960L, 0L, 0L, 0L}, 53);
            immediates[84] =
                    new Pattern(new long[]{0L, 0x7c0000000000000L, 0L, 0L}, new long[]{0L, 0x3c0000000000000L, 0L, 0L},
                            69);
            immediates[85] =
                    new Pattern(new long[]{0L, 8521215115264L, 0L, 0L}, new long[]{0L, 4123168604160L, 0L, 0L}, 85);
            immediates[86] = new Pattern(new long[]{0L, 130023424L, 0L, 0L}, new long[]{0L, 62914560L, 0L, 0L}, 101);
            immediates[87] = new Pattern(new long[]{0L, 1984L, 0L, 0L}, new long[]{0L, 960L, 0L, 0L}, 117);
            immediates[88] =
                    new Pattern(new long[]{0L, 0L, 0x7c0000000000000L, 0L}, new long[]{0L, 0L, 0x3c0000000000000L, 0L},
                            133);
            immediates[89] =
                    new Pattern(new long[]{0L, 0L, 8521215115264L, 0L}, new long[]{0L, 0L, 4123168604160L, 0L}, 149);
            immediates[90] = new Pattern(new long[]{0L, 0L, 130023424L, 0L}, new long[]{0L, 0L, 62914560L, 0L}, 165);
            immediates[91] = new Pattern(new long[]{0L, 0L, 1984L, 0L}, new long[]{0L, 0L, 960L, 0L}, 181);
            immediates[92] =
                    new Pattern(new long[]{0L, 0L, 0L, 0x7c0000000000000L}, new long[]{0L, 0L, 0L, 0x3c0000000000000L},
                            197);
            immediates[93] =
                    new Pattern(new long[]{0L, 0L, 0L, 8521215115264L}, new long[]{0L, 0L, 0L, 4123168604160L}, 213);
            immediates[94] = new Pattern(new long[]{0L, 0L, 0L, 130023424L}, new long[]{0L, 0L, 0L, 62914560L}, 229);
            immediates[95] = new Pattern(new long[]{0L, 0L, 0L, 1984L}, new long[]{0L, 0L, 0L, 960L}, 245);
            immediates[96] =
                    new Pattern(new long[]{0x3e0000000000000L, 0L, 0L, 0L}, new long[]{0x1e0000000000000L, 0L, 0L, 0L},
                            6);
            immediates[97] =
                    new Pattern(new long[]{4260607557632L, 0L, 0L, 0L}, new long[]{2061584302080L, 0L, 0L, 0L}, 22);
            immediates[98] = new Pattern(new long[]{65011712L, 0L, 0L, 0L}, new long[]{31457280L, 0L, 0L, 0L}, 38);
            immediates[99] = new Pattern(new long[]{992L, 0L, 0L, 0L}, new long[]{480L, 0L, 0L, 0L}, 54);
            immediates[100] =
                    new Pattern(new long[]{0L, 0x3e0000000000000L, 0L, 0L}, new long[]{0L, 0x1e0000000000000L, 0L, 0L},
                            70);
            immediates[101] =
                    new Pattern(new long[]{0L, 4260607557632L, 0L, 0L}, new long[]{0L, 2061584302080L, 0L, 0L}, 86);
            immediates[102] = new Pattern(new long[]{0L, 65011712L, 0L, 0L}, new long[]{0L, 31457280L, 0L, 0L}, 102);
            immediates[103] = new Pattern(new long[]{0L, 992L, 0L, 0L}, new long[]{0L, 480L, 0L, 0L}, 118);
            immediates[104] =
                    new Pattern(new long[]{0L, 0L, 0x3e0000000000000L, 0L}, new long[]{0L, 0L, 0x1e0000000000000L, 0L},
                            134);
            immediates[105] =
                    new Pattern(new long[]{0L, 0L, 4260607557632L, 0L}, new long[]{0L, 0L, 2061584302080L, 0L}, 150);
            immediates[106] = new Pattern(new long[]{0L, 0L, 65011712L, 0L}, new long[]{0L, 0L, 31457280L, 0L}, 166);
            immediates[107] = new Pattern(new long[]{0L, 0L, 992L, 0L}, new long[]{0L, 0L, 480L, 0L}, 182);
            immediates[108] =
                    new Pattern(new long[]{0L, 0L, 0L, 0x3e0000000000000L}, new long[]{0L, 0L, 0L, 0x1e0000000000000L},
                            198);
            immediates[109] =
                    new Pattern(new long[]{0L, 0L, 0L, 4260607557632L}, new long[]{0L, 0L, 0L, 2061584302080L}, 214);
            immediates[110] = new Pattern(new long[]{0L, 0L, 0L, 65011712L}, new long[]{0L, 0L, 0L, 31457280L}, 230);
            immediates[111] = new Pattern(new long[]{0L, 0L, 0L, 992L}, new long[]{0L, 0L, 0L, 480L}, 246);
            immediates[112] =
                    new Pattern(new long[]{0x1f0000000000000L, 0L, 0L, 0L}, new long[]{0xf0000000000000L, 0L, 0L, 0L},
                            7);
            immediates[113] =
                    new Pattern(new long[]{2130303778816L, 0L, 0L, 0L}, new long[]{0xf000000000L, 0L, 0L, 0L}, 23);
            immediates[114] = new Pattern(new long[]{32505856L, 0L, 0L, 0L}, new long[]{15728640L, 0L, 0L, 0L}, 39);
            immediates[115] = new Pattern(new long[]{496L, 0L, 0L, 0L}, new long[]{240L, 0L, 0L, 0L}, 55);
            immediates[116] =
                    new Pattern(new long[]{0L, 0x1f0000000000000L, 0L, 0L}, new long[]{0L, 0xf0000000000000L, 0L, 0L},
                            71);
            immediates[117] =
                    new Pattern(new long[]{0L, 2130303778816L, 0L, 0L}, new long[]{0L, 0xf000000000L, 0L, 0L}, 87);
            immediates[118] = new Pattern(new long[]{0L, 32505856L, 0L, 0L}, new long[]{0L, 15728640L, 0L, 0L}, 103);
            immediates[119] = new Pattern(new long[]{0L, 496L, 0L, 0L}, new long[]{0L, 240L, 0L, 0L}, 119);
            immediates[120] =
                    new Pattern(new long[]{0L, 0L, 0x1f0000000000000L, 0L}, new long[]{0L, 0L, 0xf0000000000000L, 0L},
                            135);
            immediates[121] =
                    new Pattern(new long[]{0L, 0L, 2130303778816L, 0L}, new long[]{0L, 0L, 0xf000000000L, 0L}, 151);
            immediates[122] = new Pattern(new long[]{0L, 0L, 32505856L, 0L}, new long[]{0L, 0L, 15728640L, 0L}, 167);
            immediates[123] = new Pattern(new long[]{0L, 0L, 496L, 0L}, new long[]{0L, 0L, 240L, 0L}, 183);
            immediates[124] =
                    new Pattern(new long[]{0L, 0L, 0L, 0x1f0000000000000L}, new long[]{0L, 0L, 0L, 0xf0000000000000L},
                            199);
            immediates[125] =
                    new Pattern(new long[]{0L, 0L, 0L, 2130303778816L}, new long[]{0L, 0L, 0L, 0xf000000000L}, 215);
            immediates[126] = new Pattern(new long[]{0L, 0L, 0L, 32505856L}, new long[]{0L, 0L, 0L, 15728640L}, 231);
            immediates[127] = new Pattern(new long[]{0L, 0L, 0L, 496L}, new long[]{0L, 0L, 0L, 240L}, 247);
            immediates[128] =
                    new Pattern(new long[]{0xf8000000000000L, 0L, 0L, 0L}, new long[]{0x78000000000000L, 0L, 0L, 0L},
                            8);
            immediates[129] =
                    new Pattern(new long[]{0xf800000000L, 0L, 0L, 0L}, new long[]{515396075520L, 0L, 0L, 0L}, 24);
            immediates[130] = new Pattern(new long[]{16252928L, 0L, 0L, 0L}, new long[]{7864320L, 0L, 0L, 0L}, 40);
            immediates[131] = new Pattern(new long[]{248L, 0L, 0L, 0L}, new long[]{120L, 0L, 0L, 0L}, 56);
            immediates[132] =
                    new Pattern(new long[]{0L, 0xf8000000000000L, 0L, 0L}, new long[]{0L, 0x78000000000000L, 0L, 0L},
                            72);
            immediates[133] =
                    new Pattern(new long[]{0L, 0xf800000000L, 0L, 0L}, new long[]{0L, 515396075520L, 0L, 0L}, 88);
            immediates[134] = new Pattern(new long[]{0L, 16252928L, 0L, 0L}, new long[]{0L, 7864320L, 0L, 0L}, 104);
            immediates[135] = new Pattern(new long[]{0L, 248L, 0L, 0L}, new long[]{0L, 120L, 0L, 0L}, 120);
            immediates[136] =
                    new Pattern(new long[]{0L, 0L, 0xf8000000000000L, 0L}, new long[]{0L, 0L, 0x78000000000000L, 0L},
                            136);
            immediates[137] =
                    new Pattern(new long[]{0L, 0L, 0xf800000000L, 0L}, new long[]{0L, 0L, 515396075520L, 0L}, 152);
            immediates[138] = new Pattern(new long[]{0L, 0L, 16252928L, 0L}, new long[]{0L, 0L, 7864320L, 0L}, 168);
            immediates[139] = new Pattern(new long[]{0L, 0L, 248L, 0L}, new long[]{0L, 0L, 120L, 0L}, 184);
            immediates[140] =
                    new Pattern(new long[]{0L, 0L, 0L, 0xf8000000000000L}, new long[]{0L, 0L, 0L, 0x78000000000000L},
                            200);
            immediates[141] =
                    new Pattern(new long[]{0L, 0L, 0L, 0xf800000000L}, new long[]{0L, 0L, 0L, 515396075520L}, 216);
            immediates[142] = new Pattern(new long[]{0L, 0L, 0L, 16252928L}, new long[]{0L, 0L, 0L, 7864320L}, 232);
            immediates[143] = new Pattern(new long[]{0L, 0L, 0L, 248L}, new long[]{0L, 0L, 0L, 120L}, 248);
            immediates[144] =
                    new Pattern(new long[]{0x7c000000000000L, 0L, 0L, 0L}, new long[]{0x3c000000000000L, 0L, 0L, 0L},
                            9);
            immediates[145] =
                    new Pattern(new long[]{532575944704L, 0L, 0L, 0L}, new long[]{257698037760L, 0L, 0L, 0L}, 25);
            immediates[146] = new Pattern(new long[]{8126464L, 0L, 0L, 0L}, new long[]{3932160L, 0L, 0L, 0L}, 41);
            immediates[147] = new Pattern(new long[]{124L, 0L, 0L, 0L}, new long[]{60L, 0L, 0L, 0L}, 57);
            immediates[148] =
                    new Pattern(new long[]{0L, 0x7c000000000000L, 0L, 0L}, new long[]{0L, 0x3c000000000000L, 0L, 0L},
                            73);
            immediates[149] =
                    new Pattern(new long[]{0L, 532575944704L, 0L, 0L}, new long[]{0L, 257698037760L, 0L, 0L}, 89);
            immediates[150] = new Pattern(new long[]{0L, 8126464L, 0L, 0L}, new long[]{0L, 3932160L, 0L, 0L}, 105);
            immediates[151] = new Pattern(new long[]{0L, 124L, 0L, 0L}, new long[]{0L, 60L, 0L, 0L}, 121);
            immediates[152] =
                    new Pattern(new long[]{0L, 0L, 0x7c000000000000L, 0L}, new long[]{0L, 0L, 0x3c000000000000L, 0L},
                            137);
            immediates[153] =
                    new Pattern(new long[]{0L, 0L, 532575944704L, 0L}, new long[]{0L, 0L, 257698037760L, 0L}, 153);
            immediates[154] = new Pattern(new long[]{0L, 0L, 8126464L, 0L}, new long[]{0L, 0L, 3932160L, 0L}, 169);
            immediates[155] = new Pattern(new long[]{0L, 0L, 124L, 0L}, new long[]{0L, 0L, 60L, 0L}, 185);
            immediates[156] =
                    new Pattern(new long[]{0L, 0L, 0L, 0x7c000000000000L}, new long[]{0L, 0L, 0L, 0x3c000000000000L},
                            201);
            immediates[157] =
                    new Pattern(new long[]{0L, 0L, 0L, 532575944704L}, new long[]{0L, 0L, 0L, 257698037760L}, 217);
            immediates[158] = new Pattern(new long[]{0L, 0L, 0L, 8126464L}, new long[]{0L, 0L, 0L, 3932160L}, 233);
            immediates[159] = new Pattern(new long[]{0L, 0L, 0L, 124L}, new long[]{0L, 0L, 0L, 60L}, 249);
            immediates[160] =
                    new Pattern(new long[]{0x3e000000000000L, 0L, 0L, 0L}, new long[]{8444249301319680L, 0L, 0L, 0L},
                            10);
            immediates[161] =
                    new Pattern(new long[]{266287972352L, 0L, 0L, 0L}, new long[]{128849018880L, 0L, 0L, 0L}, 26);
            immediates[162] = new Pattern(new long[]{4063232L, 0L, 0L, 0L}, new long[]{1966080L, 0L, 0L, 0L}, 42);
            immediates[163] = new Pattern(new long[]{62L, 0L, 0L, 0L}, new long[]{30L, 0L, 0L, 0L}, 58);
            immediates[164] =
                    new Pattern(new long[]{0L, 0x3e000000000000L, 0L, 0L}, new long[]{0L, 8444249301319680L, 0L, 0L},
                            74);
            immediates[165] =
                    new Pattern(new long[]{0L, 266287972352L, 0L, 0L}, new long[]{0L, 128849018880L, 0L, 0L}, 90);
            immediates[166] = new Pattern(new long[]{0L, 4063232L, 0L, 0L}, new long[]{0L, 1966080L, 0L, 0L}, 106);
            immediates[167] = new Pattern(new long[]{0L, 62L, 0L, 0L}, new long[]{0L, 30L, 0L, 0L}, 122);
            immediates[168] =
                    new Pattern(new long[]{0L, 0L, 0x3e000000000000L, 0L}, new long[]{0L, 0L, 8444249301319680L, 0L},
                            138);
            immediates[169] =
                    new Pattern(new long[]{0L, 0L, 266287972352L, 0L}, new long[]{0L, 0L, 128849018880L, 0L}, 154);
            immediates[170] = new Pattern(new long[]{0L, 0L, 4063232L, 0L}, new long[]{0L, 0L, 1966080L, 0L}, 170);
            immediates[171] = new Pattern(new long[]{0L, 0L, 62L, 0L}, new long[]{0L, 0L, 30L, 0L}, 186);
            immediates[172] =
                    new Pattern(new long[]{0L, 0L, 0L, 0x3e000000000000L}, new long[]{0L, 0L, 0L, 8444249301319680L},
                            202);
            immediates[173] =
                    new Pattern(new long[]{0L, 0L, 0L, 266287972352L}, new long[]{0L, 0L, 0L, 128849018880L}, 218);
            immediates[174] = new Pattern(new long[]{0L, 0L, 0L, 4063232L}, new long[]{0L, 0L, 0L, 1966080L}, 234);
            immediates[175] = new Pattern(new long[]{0L, 0L, 0L, 62L}, new long[]{0L, 0L, 0L, 30L}, 250);
            immediates[176] =
                    new Pattern(new long[]{8725724278030336L, 0L, 0L, 0L}, new long[]{0xf000000000000L, 0L, 0L, 0L},
                            11);
            immediates[177] =
                    new Pattern(new long[]{133143986176L, 0L, 0L, 0L}, new long[]{64424509440L, 0L, 0L, 0L}, 27);
            immediates[178] = new Pattern(new long[]{2031616L, 0L, 0L, 0L}, new long[]{983040L, 0L, 0L, 0L}, 43);
            immediates[179] = new Pattern(new long[]{31L, 0L, 0L, 0L}, new long[]{15L, 0L, 0L, 0L}, 59);
            immediates[180] =
                    new Pattern(new long[]{0L, 8725724278030336L, 0L, 0L}, new long[]{0L, 0xf000000000000L, 0L, 0L},
                            75);
            immediates[181] =
                    new Pattern(new long[]{0L, 133143986176L, 0L, 0L}, new long[]{0L, 64424509440L, 0L, 0L}, 91);
            immediates[182] = new Pattern(new long[]{0L, 2031616L, 0L, 0L}, new long[]{0L, 983040L, 0L, 0L}, 107);
            immediates[183] = new Pattern(new long[]{0L, 31L, 0L, 0L}, new long[]{0L, 15L, 0L, 0L}, 123);
            immediates[184] =
                    new Pattern(new long[]{0L, 0L, 8725724278030336L, 0L}, new long[]{0L, 0L, 0xf000000000000L, 0L},
                            139);
            immediates[185] =
                    new Pattern(new long[]{0L, 0L, 133143986176L, 0L}, new long[]{0L, 0L, 64424509440L, 0L}, 155);
            immediates[186] = new Pattern(new long[]{0L, 0L, 2031616L, 0L}, new long[]{0L, 0L, 983040L, 0L}, 171);
            immediates[187] = new Pattern(new long[]{0L, 0L, 31L, 0L}, new long[]{0L, 0L, 15L, 0L}, 187);
            immediates[188] =
                    new Pattern(new long[]{0L, 0L, 0L, 8725724278030336L}, new long[]{0L, 0L, 0L, 0xf000000000000L},
                            203);
            immediates[189] =
                    new Pattern(new long[]{0L, 0L, 0L, 133143986176L}, new long[]{0L, 0L, 0L, 64424509440L}, 219);
            immediates[190] = new Pattern(new long[]{0L, 0L, 0L, 2031616L}, new long[]{0L, 0L, 0L, 983040L}, 235);
            immediates[191] = new Pattern(new long[]{0L, 0L, 0L, 31L}, new long[]{0L, 0L, 0L, 15L}, 251);
            immediates[192] = new Pattern(new long[]{0xf800000000000000L, 0L, 0L, 0L},
                    new long[]{0xb800000000000000L, 0L, 0L, 0L}, 1);
            immediates[193] =
                    new Pattern(new long[]{0xf80000000000L, 0L, 0L, 0L}, new long[]{0xb80000000000L, 0L, 0L, 0L}, 17);
            immediates[194] = new Pattern(new long[]{4160749568L, 0L, 0L, 0L}, new long[]{3087007744L, 0L, 0L, 0L}, 33);
            immediates[195] = new Pattern(new long[]{63488L, 0L, 0L, 0L}, new long[]{47104L, 0L, 0L, 0L}, 49);
            immediates[196] = new Pattern(new long[]{0L, 0xf800000000000000L, 0L, 0L},
                    new long[]{0L, 0xb800000000000000L, 0L, 0L}, 65);
            immediates[197] =
                    new Pattern(new long[]{0L, 0xf80000000000L, 0L, 0L}, new long[]{0L, 0xb80000000000L, 0L, 0L}, 81);
            immediates[198] = new Pattern(new long[]{0L, 4160749568L, 0L, 0L}, new long[]{0L, 3087007744L, 0L, 0L}, 97);
            immediates[199] = new Pattern(new long[]{0L, 63488L, 0L, 0L}, new long[]{0L, 47104L, 0L, 0L}, 113);
            immediates[200] = new Pattern(new long[]{0L, 0L, 0xf800000000000000L, 0L},
                    new long[]{0L, 0L, 0xb800000000000000L, 0L}, 129);
            immediates[201] =
                    new Pattern(new long[]{0L, 0L, 0xf80000000000L, 0L}, new long[]{0L, 0L, 0xb80000000000L, 0L}, 145);
            immediates[202] =
                    new Pattern(new long[]{0L, 0L, 4160749568L, 0L}, new long[]{0L, 0L, 3087007744L, 0L}, 161);
            immediates[203] = new Pattern(new long[]{0L, 0L, 63488L, 0L}, new long[]{0L, 0L, 47104L, 0L}, 177);
            immediates[204] = new Pattern(new long[]{0L, 0L, 0L, 0xf800000000000000L},
                    new long[]{0L, 0L, 0L, 0xb800000000000000L}, 193);
            immediates[205] =
                    new Pattern(new long[]{0L, 0L, 0L, 0xf80000000000L}, new long[]{0L, 0L, 0L, 0xb80000000000L}, 209);
            immediates[206] =
                    new Pattern(new long[]{0L, 0L, 0L, 4160749568L}, new long[]{0L, 0L, 0L, 3087007744L}, 225);
            immediates[207] = new Pattern(new long[]{0L, 0L, 0L, 63488L}, new long[]{0L, 0L, 0L, 47104L}, 241);
            immediates[208] = new Pattern(new long[]{0x7c00000000000000L, 0L, 0L, 0L},
                    new long[]{0x5c00000000000000L, 0L, 0L, 0L}, 2);
            immediates[209] =
                    new Pattern(new long[]{0x7c0000000000L, 0L, 0L, 0L}, new long[]{0x5c0000000000L, 0L, 0L, 0L}, 18);
            immediates[210] = new Pattern(new long[]{2080374784L, 0L, 0L, 0L}, new long[]{1543503872L, 0L, 0L, 0L}, 34);
            immediates[211] = new Pattern(new long[]{31744L, 0L, 0L, 0L}, new long[]{23552L, 0L, 0L, 0L}, 50);
            immediates[212] = new Pattern(new long[]{0L, 0x7c00000000000000L, 0L, 0L},
                    new long[]{0L, 0x5c00000000000000L, 0L, 0L}, 66);
            immediates[213] =
                    new Pattern(new long[]{0L, 0x7c0000000000L, 0L, 0L}, new long[]{0L, 0x5c0000000000L, 0L, 0L}, 82);
            immediates[214] = new Pattern(new long[]{0L, 2080374784L, 0L, 0L}, new long[]{0L, 1543503872L, 0L, 0L}, 98);
            immediates[215] = new Pattern(new long[]{0L, 31744L, 0L, 0L}, new long[]{0L, 23552L, 0L, 0L}, 114);
            immediates[216] = new Pattern(new long[]{0L, 0L, 0x7c00000000000000L, 0L},
                    new long[]{0L, 0L, 0x5c00000000000000L, 0L}, 130);
            immediates[217] =
                    new Pattern(new long[]{0L, 0L, 0x7c0000000000L, 0L}, new long[]{0L, 0L, 0x5c0000000000L, 0L}, 146);
            immediates[218] =
                    new Pattern(new long[]{0L, 0L, 2080374784L, 0L}, new long[]{0L, 0L, 1543503872L, 0L}, 162);
            immediates[219] = new Pattern(new long[]{0L, 0L, 31744L, 0L}, new long[]{0L, 0L, 23552L, 0L}, 178);
            immediates[220] = new Pattern(new long[]{0L, 0L, 0L, 0x7c00000000000000L},
                    new long[]{0L, 0L, 0L, 0x5c00000000000000L}, 194);
            immediates[221] =
                    new Pattern(new long[]{0L, 0L, 0L, 0x7c0000000000L}, new long[]{0L, 0L, 0L, 0x5c0000000000L}, 210);
            immediates[222] =
                    new Pattern(new long[]{0L, 0L, 0L, 2080374784L}, new long[]{0L, 0L, 0L, 1543503872L}, 226);
            immediates[223] = new Pattern(new long[]{0L, 0L, 0L, 31744L}, new long[]{0L, 0L, 0L, 23552L}, 242);
            immediates[224] = new Pattern(new long[]{0x3e00000000000000L, 0L, 0L, 0L},
                    new long[]{0x2e00000000000000L, 0L, 0L, 0L}, 3);
            immediates[225] =
                    new Pattern(new long[]{68169720922112L, 0L, 0L, 0L}, new long[]{50577534877696L, 0L, 0L, 0L}, 19);
            immediates[226] = new Pattern(new long[]{1040187392L, 0L, 0L, 0L}, new long[]{771751936L, 0L, 0L, 0L}, 35);
            immediates[227] = new Pattern(new long[]{15872L, 0L, 0L, 0L}, new long[]{11776L, 0L, 0L, 0L}, 51);
            immediates[228] = new Pattern(new long[]{0L, 0x3e00000000000000L, 0L, 0L},
                    new long[]{0L, 0x2e00000000000000L, 0L, 0L}, 67);
            immediates[229] =
                    new Pattern(new long[]{0L, 68169720922112L, 0L, 0L}, new long[]{0L, 50577534877696L, 0L, 0L}, 83);
            immediates[230] = new Pattern(new long[]{0L, 1040187392L, 0L, 0L}, new long[]{0L, 771751936L, 0L, 0L}, 99);
            immediates[231] = new Pattern(new long[]{0L, 15872L, 0L, 0L}, new long[]{0L, 11776L, 0L, 0L}, 115);
            immediates[232] = new Pattern(new long[]{0L, 0L, 0x3e00000000000000L, 0L},
                    new long[]{0L, 0L, 0x2e00000000000000L, 0L}, 131);
            immediates[233] =
                    new Pattern(new long[]{0L, 0L, 68169720922112L, 0L}, new long[]{0L, 0L, 50577534877696L, 0L}, 147);
            immediates[234] = new Pattern(new long[]{0L, 0L, 1040187392L, 0L}, new long[]{0L, 0L, 771751936L, 0L}, 163);
            immediates[235] = new Pattern(new long[]{0L, 0L, 15872L, 0L}, new long[]{0L, 0L, 11776L, 0L}, 179);
            immediates[236] = new Pattern(new long[]{0L, 0L, 0L, 0x3e00000000000000L},
                    new long[]{0L, 0L, 0L, 0x2e00000000000000L}, 195);
            immediates[237] =
                    new Pattern(new long[]{0L, 0L, 0L, 68169720922112L}, new long[]{0L, 0L, 0L, 50577534877696L}, 211);
            immediates[238] = new Pattern(new long[]{0L, 0L, 0L, 1040187392L}, new long[]{0L, 0L, 0L, 771751936L}, 227);
            immediates[239] = new Pattern(new long[]{0L, 0L, 0L, 15872L}, new long[]{0L, 0L, 0L, 11776L}, 243);
            immediates[240] = new Pattern(new long[]{0x1f00000000000000L, 0L, 0L, 0L},
                    new long[]{0x1700000000000000L, 0L, 0L, 0L}, 4);
            immediates[241] =
                    new Pattern(new long[]{34084860461056L, 0L, 0L, 0L}, new long[]{25288767438848L, 0L, 0L, 0L}, 20);
            immediates[242] = new Pattern(new long[]{520093696L, 0L, 0L, 0L}, new long[]{385875968L, 0L, 0L, 0L}, 36);
            immediates[243] = new Pattern(new long[]{7936L, 0L, 0L, 0L}, new long[]{5888L, 0L, 0L, 0L}, 52);
            immediates[244] = new Pattern(new long[]{0L, 0x1f00000000000000L, 0L, 0L},
                    new long[]{0L, 0x1700000000000000L, 0L, 0L}, 68);
            immediates[245] =
                    new Pattern(new long[]{0L, 34084860461056L, 0L, 0L}, new long[]{0L, 25288767438848L, 0L, 0L}, 84);
            immediates[246] = new Pattern(new long[]{0L, 520093696L, 0L, 0L}, new long[]{0L, 385875968L, 0L, 0L}, 100);
            immediates[247] = new Pattern(new long[]{0L, 7936L, 0L, 0L}, new long[]{0L, 5888L, 0L, 0L}, 116);
            immediates[248] = new Pattern(new long[]{0L, 0L, 0x1f00000000000000L, 0L},
                    new long[]{0L, 0L, 0x1700000000000000L, 0L}, 132);
            immediates[249] =
                    new Pattern(new long[]{0L, 0L, 34084860461056L, 0L}, new long[]{0L, 0L, 25288767438848L, 0L}, 148);
            immediates[250] = new Pattern(new long[]{0L, 0L, 520093696L, 0L}, new long[]{0L, 0L, 385875968L, 0L}, 164);
            immediates[251] = new Pattern(new long[]{0L, 0L, 7936L, 0L}, new long[]{0L, 0L, 5888L, 0L}, 180);
            immediates[252] = new Pattern(new long[]{0L, 0L, 0L, 0x1f00000000000000L},
                    new long[]{0L, 0L, 0L, 0x1700000000000000L}, 196);
            immediates[253] =
                    new Pattern(new long[]{0L, 0L, 0L, 34084860461056L}, new long[]{0L, 0L, 0L, 25288767438848L}, 212);
            immediates[254] = new Pattern(new long[]{0L, 0L, 0L, 520093696L}, new long[]{0L, 0L, 0L, 385875968L}, 228);
            immediates[255] = new Pattern(new long[]{0L, 0L, 0L, 7936L}, new long[]{0L, 0L, 0L, 5888L}, 244);
            immediates[256] =
                    new Pattern(new long[]{0xf80000000000000L, 0L, 0L, 0L}, new long[]{0xb80000000000000L, 0L, 0L, 0L},
                            5);
            immediates[257] =
                    new Pattern(new long[]{0xf8000000000L, 0L, 0L, 0L}, new long[]{0xb8000000000L, 0L, 0L, 0L}, 21);
            immediates[258] = new Pattern(new long[]{260046848L, 0L, 0L, 0L}, new long[]{192937984L, 0L, 0L, 0L}, 37);
            immediates[259] = new Pattern(new long[]{3968L, 0L, 0L, 0L}, new long[]{2944L, 0L, 0L, 0L}, 53);
            immediates[260] =
                    new Pattern(new long[]{0L, 0xf80000000000000L, 0L, 0L}, new long[]{0L, 0xb80000000000000L, 0L, 0L},
                            69);
            immediates[261] =
                    new Pattern(new long[]{0L, 0xf8000000000L, 0L, 0L}, new long[]{0L, 0xb8000000000L, 0L, 0L}, 85);
            immediates[262] = new Pattern(new long[]{0L, 260046848L, 0L, 0L}, new long[]{0L, 192937984L, 0L, 0L}, 101);
            immediates[263] = new Pattern(new long[]{0L, 3968L, 0L, 0L}, new long[]{0L, 2944L, 0L, 0L}, 117);
            immediates[264] =
                    new Pattern(new long[]{0L, 0L, 0xf80000000000000L, 0L}, new long[]{0L, 0L, 0xb80000000000000L, 0L},
                            133);
            immediates[265] =
                    new Pattern(new long[]{0L, 0L, 0xf8000000000L, 0L}, new long[]{0L, 0L, 0xb8000000000L, 0L}, 149);
            immediates[266] = new Pattern(new long[]{0L, 0L, 260046848L, 0L}, new long[]{0L, 0L, 192937984L, 0L}, 165);
            immediates[267] = new Pattern(new long[]{0L, 0L, 3968L, 0L}, new long[]{0L, 0L, 2944L, 0L}, 181);
            immediates[268] =
                    new Pattern(new long[]{0L, 0L, 0L, 0xf80000000000000L}, new long[]{0L, 0L, 0L, 0xb80000000000000L},
                            197);
            immediates[269] =
                    new Pattern(new long[]{0L, 0L, 0L, 0xf8000000000L}, new long[]{0L, 0L, 0L, 0xb8000000000L}, 213);
            immediates[270] = new Pattern(new long[]{0L, 0L, 0L, 260046848L}, new long[]{0L, 0L, 0L, 192937984L}, 229);
            immediates[271] = new Pattern(new long[]{0L, 0L, 0L, 3968L}, new long[]{0L, 0L, 0L, 2944L}, 245);
            immediates[272] =
                    new Pattern(new long[]{0x7c0000000000000L, 0L, 0L, 0L}, new long[]{0x5c0000000000000L, 0L, 0L, 0L},
                            6);
            immediates[273] =
                    new Pattern(new long[]{8521215115264L, 0L, 0L, 0L}, new long[]{6322191859712L, 0L, 0L, 0L}, 22);
            immediates[274] = new Pattern(new long[]{130023424L, 0L, 0L, 0L}, new long[]{96468992L, 0L, 0L, 0L}, 38);
            immediates[275] = new Pattern(new long[]{1984L, 0L, 0L, 0L}, new long[]{1472L, 0L, 0L, 0L}, 54);
            immediates[276] =
                    new Pattern(new long[]{0L, 0x7c0000000000000L, 0L, 0L}, new long[]{0L, 0x5c0000000000000L, 0L, 0L},
                            70);
            immediates[277] =
                    new Pattern(new long[]{0L, 8521215115264L, 0L, 0L}, new long[]{0L, 6322191859712L, 0L, 0L}, 86);
            immediates[278] = new Pattern(new long[]{0L, 130023424L, 0L, 0L}, new long[]{0L, 96468992L, 0L, 0L}, 102);
            immediates[279] = new Pattern(new long[]{0L, 1984L, 0L, 0L}, new long[]{0L, 1472L, 0L, 0L}, 118);
            immediates[280] =
                    new Pattern(new long[]{0L, 0L, 0x7c0000000000000L, 0L}, new long[]{0L, 0L, 0x5c0000000000000L, 0L},
                            134);
            immediates[281] =
                    new Pattern(new long[]{0L, 0L, 8521215115264L, 0L}, new long[]{0L, 0L, 6322191859712L, 0L}, 150);
            immediates[282] = new Pattern(new long[]{0L, 0L, 130023424L, 0L}, new long[]{0L, 0L, 96468992L, 0L}, 166);
            immediates[283] = new Pattern(new long[]{0L, 0L, 1984L, 0L}, new long[]{0L, 0L, 1472L, 0L}, 182);
            immediates[284] =
                    new Pattern(new long[]{0L, 0L, 0L, 0x7c0000000000000L}, new long[]{0L, 0L, 0L, 0x5c0000000000000L},
                            198);
            immediates[285] =
                    new Pattern(new long[]{0L, 0L, 0L, 8521215115264L}, new long[]{0L, 0L, 0L, 6322191859712L}, 214);
            immediates[286] = new Pattern(new long[]{0L, 0L, 0L, 130023424L}, new long[]{0L, 0L, 0L, 96468992L}, 230);
            immediates[287] = new Pattern(new long[]{0L, 0L, 0L, 1984L}, new long[]{0L, 0L, 0L, 1472L}, 246);
            immediates[288] =
                    new Pattern(new long[]{0x3e0000000000000L, 0L, 0L, 0L}, new long[]{0x2e0000000000000L, 0L, 0L, 0L},
                            7);
            immediates[289] =
                    new Pattern(new long[]{4260607557632L, 0L, 0L, 0L}, new long[]{3161095929856L, 0L, 0L, 0L}, 23);
            immediates[290] = new Pattern(new long[]{65011712L, 0L, 0L, 0L}, new long[]{48234496L, 0L, 0L, 0L}, 39);
            immediates[291] = new Pattern(new long[]{992L, 0L, 0L, 0L}, new long[]{736L, 0L, 0L, 0L}, 55);
            immediates[292] =
                    new Pattern(new long[]{0L, 0x3e0000000000000L, 0L, 0L}, new long[]{0L, 0x2e0000000000000L, 0L, 0L},
                            71);
            immediates[293] =
                    new Pattern(new long[]{0L, 4260607557632L, 0L, 0L}, new long[]{0L, 3161095929856L, 0L, 0L}, 87);
            immediates[294] = new Pattern(new long[]{0L, 65011712L, 0L, 0L}, new long[]{0L, 48234496L, 0L, 0L}, 103);
            immediates[295] = new Pattern(new long[]{0L, 992L, 0L, 0L}, new long[]{0L, 736L, 0L, 0L}, 119);
            immediates[296] =
                    new Pattern(new long[]{0L, 0L, 0x3e0000000000000L, 0L}, new long[]{0L, 0L, 0x2e0000000000000L, 0L},
                            135);
            immediates[297] =
                    new Pattern(new long[]{0L, 0L, 4260607557632L, 0L}, new long[]{0L, 0L, 3161095929856L, 0L}, 151);
            immediates[298] = new Pattern(new long[]{0L, 0L, 65011712L, 0L}, new long[]{0L, 0L, 48234496L, 0L}, 167);
            immediates[299] = new Pattern(new long[]{0L, 0L, 992L, 0L}, new long[]{0L, 0L, 736L, 0L}, 183);
            immediates[300] =
                    new Pattern(new long[]{0L, 0L, 0L, 0x3e0000000000000L}, new long[]{0L, 0L, 0L, 0x2e0000000000000L},
                            199);
            immediates[301] =
                    new Pattern(new long[]{0L, 0L, 0L, 4260607557632L}, new long[]{0L, 0L, 0L, 3161095929856L}, 215);
            immediates[302] = new Pattern(new long[]{0L, 0L, 0L, 65011712L}, new long[]{0L, 0L, 0L, 48234496L}, 231);
            immediates[303] = new Pattern(new long[]{0L, 0L, 0L, 992L}, new long[]{0L, 0L, 0L, 736L}, 247);
            immediates[304] =
                    new Pattern(new long[]{0x1f0000000000000L, 0L, 0L, 0L}, new long[]{0x170000000000000L, 0L, 0L, 0L},
                            8);
            immediates[305] =
                    new Pattern(new long[]{2130303778816L, 0L, 0L, 0L}, new long[]{1580547964928L, 0L, 0L, 0L}, 24);
            immediates[306] = new Pattern(new long[]{32505856L, 0L, 0L, 0L}, new long[]{24117248L, 0L, 0L, 0L}, 40);
            immediates[307] = new Pattern(new long[]{496L, 0L, 0L, 0L}, new long[]{368L, 0L, 0L, 0L}, 56);
            immediates[308] =
                    new Pattern(new long[]{0L, 0x1f0000000000000L, 0L, 0L}, new long[]{0L, 0x170000000000000L, 0L, 0L},
                            72);
            immediates[309] =
                    new Pattern(new long[]{0L, 2130303778816L, 0L, 0L}, new long[]{0L, 1580547964928L, 0L, 0L}, 88);
            immediates[310] = new Pattern(new long[]{0L, 32505856L, 0L, 0L}, new long[]{0L, 24117248L, 0L, 0L}, 104);
            immediates[311] = new Pattern(new long[]{0L, 496L, 0L, 0L}, new long[]{0L, 368L, 0L, 0L}, 120);
            immediates[312] =
                    new Pattern(new long[]{0L, 0L, 0x1f0000000000000L, 0L}, new long[]{0L, 0L, 0x170000000000000L, 0L},
                            136);
            immediates[313] =
                    new Pattern(new long[]{0L, 0L, 2130303778816L, 0L}, new long[]{0L, 0L, 1580547964928L, 0L}, 152);
            immediates[314] = new Pattern(new long[]{0L, 0L, 32505856L, 0L}, new long[]{0L, 0L, 24117248L, 0L}, 168);
            immediates[315] = new Pattern(new long[]{0L, 0L, 496L, 0L}, new long[]{0L, 0L, 368L, 0L}, 184);
            immediates[316] =
                    new Pattern(new long[]{0L, 0L, 0L, 0x1f0000000000000L}, new long[]{0L, 0L, 0L, 0x170000000000000L},
                            200);
            immediates[317] =
                    new Pattern(new long[]{0L, 0L, 0L, 2130303778816L}, new long[]{0L, 0L, 0L, 1580547964928L}, 216);
            immediates[318] = new Pattern(new long[]{0L, 0L, 0L, 32505856L}, new long[]{0L, 0L, 0L, 24117248L}, 232);
            immediates[319] = new Pattern(new long[]{0L, 0L, 0L, 496L}, new long[]{0L, 0L, 0L, 368L}, 248);
            immediates[320] =
                    new Pattern(new long[]{0xf8000000000000L, 0L, 0L, 0L}, new long[]{0xb8000000000000L, 0L, 0L, 0L},
                            9);
            immediates[321] =
                    new Pattern(new long[]{0xf800000000L, 0L, 0L, 0L}, new long[]{790273982464L, 0L, 0L, 0L}, 25);
            immediates[322] = new Pattern(new long[]{16252928L, 0L, 0L, 0L}, new long[]{12058624L, 0L, 0L, 0L}, 41);
            immediates[323] = new Pattern(new long[]{248L, 0L, 0L, 0L}, new long[]{184L, 0L, 0L, 0L}, 57);
            immediates[324] =
                    new Pattern(new long[]{0L, 0xf8000000000000L, 0L, 0L}, new long[]{0L, 0xb8000000000000L, 0L, 0L},
                            73);
            immediates[325] =
                    new Pattern(new long[]{0L, 0xf800000000L, 0L, 0L}, new long[]{0L, 790273982464L, 0L, 0L}, 89);
            immediates[326] = new Pattern(new long[]{0L, 16252928L, 0L, 0L}, new long[]{0L, 12058624L, 0L, 0L}, 105);
            immediates[327] = new Pattern(new long[]{0L, 248L, 0L, 0L}, new long[]{0L, 184L, 0L, 0L}, 121);
            immediates[328] =
                    new Pattern(new long[]{0L, 0L, 0xf8000000000000L, 0L}, new long[]{0L, 0L, 0xb8000000000000L, 0L},
                            137);
            immediates[329] =
                    new Pattern(new long[]{0L, 0L, 0xf800000000L, 0L}, new long[]{0L, 0L, 790273982464L, 0L}, 153);
            immediates[330] = new Pattern(new long[]{0L, 0L, 16252928L, 0L}, new long[]{0L, 0L, 12058624L, 0L}, 169);
            immediates[331] = new Pattern(new long[]{0L, 0L, 248L, 0L}, new long[]{0L, 0L, 184L, 0L}, 185);
            immediates[332] =
                    new Pattern(new long[]{0L, 0L, 0L, 0xf8000000000000L}, new long[]{0L, 0L, 0L, 0xb8000000000000L},
                            201);
            immediates[333] =
                    new Pattern(new long[]{0L, 0L, 0L, 0xf800000000L}, new long[]{0L, 0L, 0L, 790273982464L}, 217);
            immediates[334] = new Pattern(new long[]{0L, 0L, 0L, 16252928L}, new long[]{0L, 0L, 0L, 12058624L}, 233);
            immediates[335] = new Pattern(new long[]{0L, 0L, 0L, 248L}, new long[]{0L, 0L, 0L, 184L}, 249);
            immediates[336] =
                    new Pattern(new long[]{0x7c000000000000L, 0L, 0L, 0L}, new long[]{0x5c000000000000L, 0L, 0L, 0L},
                            10);
            immediates[337] =
                    new Pattern(new long[]{532575944704L, 0L, 0L, 0L}, new long[]{395136991232L, 0L, 0L, 0L}, 26);
            immediates[338] = new Pattern(new long[]{8126464L, 0L, 0L, 0L}, new long[]{6029312L, 0L, 0L, 0L}, 42);
            immediates[339] = new Pattern(new long[]{124L, 0L, 0L, 0L}, new long[]{92L, 0L, 0L, 0L}, 58);
            immediates[340] =
                    new Pattern(new long[]{0L, 0x7c000000000000L, 0L, 0L}, new long[]{0L, 0x5c000000000000L, 0L, 0L},
                            74);
            immediates[341] =
                    new Pattern(new long[]{0L, 532575944704L, 0L, 0L}, new long[]{0L, 395136991232L, 0L, 0L}, 90);
            immediates[342] = new Pattern(new long[]{0L, 8126464L, 0L, 0L}, new long[]{0L, 6029312L, 0L, 0L}, 106);
            immediates[343] = new Pattern(new long[]{0L, 124L, 0L, 0L}, new long[]{0L, 92L, 0L, 0L}, 122);
            immediates[344] =
                    new Pattern(new long[]{0L, 0L, 0x7c000000000000L, 0L}, new long[]{0L, 0L, 0x5c000000000000L, 0L},
                            138);
            immediates[345] =
                    new Pattern(new long[]{0L, 0L, 532575944704L, 0L}, new long[]{0L, 0L, 395136991232L, 0L}, 154);
            immediates[346] = new Pattern(new long[]{0L, 0L, 8126464L, 0L}, new long[]{0L, 0L, 6029312L, 0L}, 170);
            immediates[347] = new Pattern(new long[]{0L, 0L, 124L, 0L}, new long[]{0L, 0L, 92L, 0L}, 186);
            immediates[348] =
                    new Pattern(new long[]{0L, 0L, 0L, 0x7c000000000000L}, new long[]{0L, 0L, 0L, 0x5c000000000000L},
                            202);
            immediates[349] =
                    new Pattern(new long[]{0L, 0L, 0L, 532575944704L}, new long[]{0L, 0L, 0L, 395136991232L}, 218);
            immediates[350] = new Pattern(new long[]{0L, 0L, 0L, 8126464L}, new long[]{0L, 0L, 0L, 6029312L}, 234);
            immediates[351] = new Pattern(new long[]{0L, 0L, 0L, 124L}, new long[]{0L, 0L, 0L, 92L}, 250);
            immediates[352] =
                    new Pattern(new long[]{0x3e000000000000L, 0L, 0L, 0L}, new long[]{0x2e000000000000L, 0L, 0L, 0L},
                            11);
            immediates[353] =
                    new Pattern(new long[]{266287972352L, 0L, 0L, 0L}, new long[]{197568495616L, 0L, 0L, 0L}, 27);
            immediates[354] = new Pattern(new long[]{4063232L, 0L, 0L, 0L}, new long[]{3014656L, 0L, 0L, 0L}, 43);
            immediates[355] = new Pattern(new long[]{62L, 0L, 0L, 0L}, new long[]{46L, 0L, 0L, 0L}, 59);
            immediates[356] =
                    new Pattern(new long[]{0L, 0x3e000000000000L, 0L, 0L}, new long[]{0L, 0x2e000000000000L, 0L, 0L},
                            75);
            immediates[357] =
                    new Pattern(new long[]{0L, 266287972352L, 0L, 0L}, new long[]{0L, 197568495616L, 0L, 0L}, 91);
            immediates[358] = new Pattern(new long[]{0L, 4063232L, 0L, 0L}, new long[]{0L, 3014656L, 0L, 0L}, 107);
            immediates[359] = new Pattern(new long[]{0L, 62L, 0L, 0L}, new long[]{0L, 46L, 0L, 0L}, 123);
            immediates[360] =
                    new Pattern(new long[]{0L, 0L, 0x3e000000000000L, 0L}, new long[]{0L, 0L, 0x2e000000000000L, 0L},
                            139);
            immediates[361] =
                    new Pattern(new long[]{0L, 0L, 266287972352L, 0L}, new long[]{0L, 0L, 197568495616L, 0L}, 155);
            immediates[362] = new Pattern(new long[]{0L, 0L, 4063232L, 0L}, new long[]{0L, 0L, 3014656L, 0L}, 171);
            immediates[363] = new Pattern(new long[]{0L, 0L, 62L, 0L}, new long[]{0L, 0L, 46L, 0L}, 187);
            immediates[364] =
                    new Pattern(new long[]{0L, 0L, 0L, 0x3e000000000000L}, new long[]{0L, 0L, 0L, 0x2e000000000000L},
                            203);
            immediates[365] =
                    new Pattern(new long[]{0L, 0L, 0L, 266287972352L}, new long[]{0L, 0L, 0L, 197568495616L}, 219);
            immediates[366] = new Pattern(new long[]{0L, 0L, 0L, 4063232L}, new long[]{0L, 0L, 0L, 3014656L}, 235);
            immediates[367] = new Pattern(new long[]{0L, 0L, 0L, 62L}, new long[]{0L, 0L, 0L, 46L}, 251);
            immediates[368] =
                    new Pattern(new long[]{8725724278030336L, 0L, 0L, 0L}, new long[]{6473924464345088L, 0L, 0L, 0L},
                            12);
            immediates[369] =
                    new Pattern(new long[]{133143986176L, 0L, 0L, 0L}, new long[]{98784247808L, 0L, 0L, 0L}, 28);
            immediates[370] = new Pattern(new long[]{2031616L, 0L, 0L, 0L}, new long[]{1507328L, 0L, 0L, 0L}, 44);
            immediates[371] = new Pattern(new long[]{31L, 0L, 0L, 0L}, new long[]{23L, 0L, 0L, 0L}, 60);
            immediates[372] =
                    new Pattern(new long[]{0L, 8725724278030336L, 0L, 0L}, new long[]{0L, 6473924464345088L, 0L, 0L},
                            76);
            immediates[373] =
                    new Pattern(new long[]{0L, 133143986176L, 0L, 0L}, new long[]{0L, 98784247808L, 0L, 0L}, 92);
            immediates[374] = new Pattern(new long[]{0L, 2031616L, 0L, 0L}, new long[]{0L, 1507328L, 0L, 0L}, 108);
            immediates[375] = new Pattern(new long[]{0L, 31L, 0L, 0L}, new long[]{0L, 23L, 0L, 0L}, 124);
            immediates[376] =
                    new Pattern(new long[]{0L, 0L, 8725724278030336L, 0L}, new long[]{0L, 0L, 6473924464345088L, 0L},
                            140);
            immediates[377] =
                    new Pattern(new long[]{0L, 0L, 133143986176L, 0L}, new long[]{0L, 0L, 98784247808L, 0L}, 156);
            immediates[378] = new Pattern(new long[]{0L, 0L, 2031616L, 0L}, new long[]{0L, 0L, 1507328L, 0L}, 172);
            immediates[379] = new Pattern(new long[]{0L, 0L, 31L, 0L}, new long[]{0L, 0L, 23L, 0L}, 188);
            immediates[380] =
                    new Pattern(new long[]{0L, 0L, 0L, 8725724278030336L}, new long[]{0L, 0L, 0L, 6473924464345088L},
                            204);
            immediates[381] =
                    new Pattern(new long[]{0L, 0L, 0L, 133143986176L}, new long[]{0L, 0L, 0L, 98784247808L}, 220);
            immediates[382] = new Pattern(new long[]{0L, 0L, 0L, 2031616L}, new long[]{0L, 0L, 0L, 1507328L}, 236);
            immediates[383] = new Pattern(new long[]{0L, 0L, 0L, 31L}, new long[]{0L, 0L, 0L, 23L}, 252);
            immediates[384] = new Pattern(new long[]{0xf800000000000000L, 0L, 0L, 0L},
                    new long[]{0xd800000000000000L, 0L, 0L, 0L}, 2);
            immediates[385] =
                    new Pattern(new long[]{0xf80000000000L, 0L, 0L, 0L}, new long[]{0xd80000000000L, 0L, 0L, 0L}, 18);
            immediates[386] = new Pattern(new long[]{4160749568L, 0L, 0L, 0L}, new long[]{3623878656L, 0L, 0L, 0L}, 34);
            immediates[387] = new Pattern(new long[]{63488L, 0L, 0L, 0L}, new long[]{55296L, 0L, 0L, 0L}, 50);
            immediates[388] = new Pattern(new long[]{0L, 0xf800000000000000L, 0L, 0L},
                    new long[]{0L, 0xd800000000000000L, 0L, 0L}, 66);
            immediates[389] =
                    new Pattern(new long[]{0L, 0xf80000000000L, 0L, 0L}, new long[]{0L, 0xd80000000000L, 0L, 0L}, 82);
            immediates[390] = new Pattern(new long[]{0L, 4160749568L, 0L, 0L}, new long[]{0L, 3623878656L, 0L, 0L}, 98);
            immediates[391] = new Pattern(new long[]{0L, 63488L, 0L, 0L}, new long[]{0L, 55296L, 0L, 0L}, 114);
            immediates[392] = new Pattern(new long[]{0L, 0L, 0xf800000000000000L, 0L},
                    new long[]{0L, 0L, 0xd800000000000000L, 0L}, 130);
            immediates[393] =
                    new Pattern(new long[]{0L, 0L, 0xf80000000000L, 0L}, new long[]{0L, 0L, 0xd80000000000L, 0L}, 146);
            immediates[394] =
                    new Pattern(new long[]{0L, 0L, 4160749568L, 0L}, new long[]{0L, 0L, 3623878656L, 0L}, 162);
            immediates[395] = new Pattern(new long[]{0L, 0L, 63488L, 0L}, new long[]{0L, 0L, 55296L, 0L}, 178);
            immediates[396] = new Pattern(new long[]{0L, 0L, 0L, 0xf800000000000000L},
                    new long[]{0L, 0L, 0L, 0xd800000000000000L}, 194);
            immediates[397] =
                    new Pattern(new long[]{0L, 0L, 0L, 0xf80000000000L}, new long[]{0L, 0L, 0L, 0xd80000000000L}, 210);
            immediates[398] =
                    new Pattern(new long[]{0L, 0L, 0L, 4160749568L}, new long[]{0L, 0L, 0L, 3623878656L}, 226);
            immediates[399] = new Pattern(new long[]{0L, 0L, 0L, 63488L}, new long[]{0L, 0L, 0L, 55296L}, 242);
            immediates[400] = new Pattern(new long[]{0x7c00000000000000L, 0L, 0L, 0L},
                    new long[]{0x6c00000000000000L, 0L, 0L, 0L}, 3);
            immediates[401] =
                    new Pattern(new long[]{0x7c0000000000L, 0L, 0L, 0L}, new long[]{0x6c0000000000L, 0L, 0L, 0L}, 19);
            immediates[402] = new Pattern(new long[]{2080374784L, 0L, 0L, 0L}, new long[]{1811939328L, 0L, 0L, 0L}, 35);
            immediates[403] = new Pattern(new long[]{31744L, 0L, 0L, 0L}, new long[]{27648L, 0L, 0L, 0L}, 51);
            immediates[404] = new Pattern(new long[]{0L, 0x7c00000000000000L, 0L, 0L},
                    new long[]{0L, 0x6c00000000000000L, 0L, 0L}, 67);
            immediates[405] =
                    new Pattern(new long[]{0L, 0x7c0000000000L, 0L, 0L}, new long[]{0L, 0x6c0000000000L, 0L, 0L}, 83);
            immediates[406] = new Pattern(new long[]{0L, 2080374784L, 0L, 0L}, new long[]{0L, 1811939328L, 0L, 0L}, 99);
            immediates[407] = new Pattern(new long[]{0L, 31744L, 0L, 0L}, new long[]{0L, 27648L, 0L, 0L}, 115);
            immediates[408] = new Pattern(new long[]{0L, 0L, 0x7c00000000000000L, 0L},
                    new long[]{0L, 0L, 0x6c00000000000000L, 0L}, 131);
            immediates[409] =
                    new Pattern(new long[]{0L, 0L, 0x7c0000000000L, 0L}, new long[]{0L, 0L, 0x6c0000000000L, 0L}, 147);
            immediates[410] =
                    new Pattern(new long[]{0L, 0L, 2080374784L, 0L}, new long[]{0L, 0L, 1811939328L, 0L}, 163);
            immediates[411] = new Pattern(new long[]{0L, 0L, 31744L, 0L}, new long[]{0L, 0L, 27648L, 0L}, 179);
            immediates[412] = new Pattern(new long[]{0L, 0L, 0L, 0x7c00000000000000L},
                    new long[]{0L, 0L, 0L, 0x6c00000000000000L}, 195);
            immediates[413] =
                    new Pattern(new long[]{0L, 0L, 0L, 0x7c0000000000L}, new long[]{0L, 0L, 0L, 0x6c0000000000L}, 211);
            immediates[414] =
                    new Pattern(new long[]{0L, 0L, 0L, 2080374784L}, new long[]{0L, 0L, 0L, 1811939328L}, 227);
            immediates[415] = new Pattern(new long[]{0L, 0L, 0L, 31744L}, new long[]{0L, 0L, 0L, 27648L}, 243);
            immediates[416] = new Pattern(new long[]{0x3e00000000000000L, 0L, 0L, 0L},
                    new long[]{0x3600000000000000L, 0L, 0L, 0L}, 4);
            immediates[417] =
                    new Pattern(new long[]{68169720922112L, 0L, 0L, 0L}, new long[]{59373627899904L, 0L, 0L, 0L}, 20);
            immediates[418] = new Pattern(new long[]{1040187392L, 0L, 0L, 0L}, new long[]{905969664L, 0L, 0L, 0L}, 36);
            immediates[419] = new Pattern(new long[]{15872L, 0L, 0L, 0L}, new long[]{13824L, 0L, 0L, 0L}, 52);
            immediates[420] = new Pattern(new long[]{0L, 0x3e00000000000000L, 0L, 0L},
                    new long[]{0L, 0x3600000000000000L, 0L, 0L}, 68);
            immediates[421] =
                    new Pattern(new long[]{0L, 68169720922112L, 0L, 0L}, new long[]{0L, 59373627899904L, 0L, 0L}, 84);
            immediates[422] = new Pattern(new long[]{0L, 1040187392L, 0L, 0L}, new long[]{0L, 905969664L, 0L, 0L}, 100);
            immediates[423] = new Pattern(new long[]{0L, 15872L, 0L, 0L}, new long[]{0L, 13824L, 0L, 0L}, 116);
            immediates[424] = new Pattern(new long[]{0L, 0L, 0x3e00000000000000L, 0L},
                    new long[]{0L, 0L, 0x3600000000000000L, 0L}, 132);
            immediates[425] =
                    new Pattern(new long[]{0L, 0L, 68169720922112L, 0L}, new long[]{0L, 0L, 59373627899904L, 0L}, 148);
            immediates[426] = new Pattern(new long[]{0L, 0L, 1040187392L, 0L}, new long[]{0L, 0L, 905969664L, 0L}, 164);
            immediates[427] = new Pattern(new long[]{0L, 0L, 15872L, 0L}, new long[]{0L, 0L, 13824L, 0L}, 180);
            immediates[428] = new Pattern(new long[]{0L, 0L, 0L, 0x3e00000000000000L},
                    new long[]{0L, 0L, 0L, 0x3600000000000000L}, 196);
            immediates[429] =
                    new Pattern(new long[]{0L, 0L, 0L, 68169720922112L}, new long[]{0L, 0L, 0L, 59373627899904L}, 212);
            immediates[430] = new Pattern(new long[]{0L, 0L, 0L, 1040187392L}, new long[]{0L, 0L, 0L, 905969664L}, 228);
            immediates[431] = new Pattern(new long[]{0L, 0L, 0L, 15872L}, new long[]{0L, 0L, 0L, 13824L}, 244);
            immediates[432] = new Pattern(new long[]{0x1f00000000000000L, 0L, 0L, 0L},
                    new long[]{0x1b00000000000000L, 0L, 0L, 0L}, 5);
            immediates[433] =
                    new Pattern(new long[]{34084860461056L, 0L, 0L, 0L}, new long[]{29686813949952L, 0L, 0L, 0L}, 21);
            immediates[434] = new Pattern(new long[]{520093696L, 0L, 0L, 0L}, new long[]{452984832L, 0L, 0L, 0L}, 37);
            immediates[435] = new Pattern(new long[]{7936L, 0L, 0L, 0L}, new long[]{6912L, 0L, 0L, 0L}, 53);
            immediates[436] = new Pattern(new long[]{0L, 0x1f00000000000000L, 0L, 0L},
                    new long[]{0L, 0x1b00000000000000L, 0L, 0L}, 69);
            immediates[437] =
                    new Pattern(new long[]{0L, 34084860461056L, 0L, 0L}, new long[]{0L, 29686813949952L, 0L, 0L}, 85);
            immediates[438] = new Pattern(new long[]{0L, 520093696L, 0L, 0L}, new long[]{0L, 452984832L, 0L, 0L}, 101);
            immediates[439] = new Pattern(new long[]{0L, 7936L, 0L, 0L}, new long[]{0L, 6912L, 0L, 0L}, 117);
            immediates[440] = new Pattern(new long[]{0L, 0L, 0x1f00000000000000L, 0L},
                    new long[]{0L, 0L, 0x1b00000000000000L, 0L}, 133);
            immediates[441] =
                    new Pattern(new long[]{0L, 0L, 34084860461056L, 0L}, new long[]{0L, 0L, 29686813949952L, 0L}, 149);
            immediates[442] = new Pattern(new long[]{0L, 0L, 520093696L, 0L}, new long[]{0L, 0L, 452984832L, 0L}, 165);
            immediates[443] = new Pattern(new long[]{0L, 0L, 7936L, 0L}, new long[]{0L, 0L, 6912L, 0L}, 181);
            immediates[444] = new Pattern(new long[]{0L, 0L, 0L, 0x1f00000000000000L},
                    new long[]{0L, 0L, 0L, 0x1b00000000000000L}, 197);
            immediates[445] =
                    new Pattern(new long[]{0L, 0L, 0L, 34084860461056L}, new long[]{0L, 0L, 0L, 29686813949952L}, 213);
            immediates[446] = new Pattern(new long[]{0L, 0L, 0L, 520093696L}, new long[]{0L, 0L, 0L, 452984832L}, 229);
            immediates[447] = new Pattern(new long[]{0L, 0L, 0L, 7936L}, new long[]{0L, 0L, 0L, 6912L}, 245);
            immediates[448] =
                    new Pattern(new long[]{0xf80000000000000L, 0L, 0L, 0L}, new long[]{0xd80000000000000L, 0L, 0L, 0L},
                            6);
            immediates[449] =
                    new Pattern(new long[]{0xf8000000000L, 0L, 0L, 0L}, new long[]{0xd8000000000L, 0L, 0L, 0L}, 22);
            immediates[450] = new Pattern(new long[]{260046848L, 0L, 0L, 0L}, new long[]{226492416L, 0L, 0L, 0L}, 38);
            immediates[451] = new Pattern(new long[]{3968L, 0L, 0L, 0L}, new long[]{3456L, 0L, 0L, 0L}, 54);
            immediates[452] =
                    new Pattern(new long[]{0L, 0xf80000000000000L, 0L, 0L}, new long[]{0L, 0xd80000000000000L, 0L, 0L},
                            70);
            immediates[453] =
                    new Pattern(new long[]{0L, 0xf8000000000L, 0L, 0L}, new long[]{0L, 0xd8000000000L, 0L, 0L}, 86);
            immediates[454] = new Pattern(new long[]{0L, 260046848L, 0L, 0L}, new long[]{0L, 226492416L, 0L, 0L}, 102);
            immediates[455] = new Pattern(new long[]{0L, 3968L, 0L, 0L}, new long[]{0L, 3456L, 0L, 0L}, 118);
            immediates[456] =
                    new Pattern(new long[]{0L, 0L, 0xf80000000000000L, 0L}, new long[]{0L, 0L, 0xd80000000000000L, 0L},
                            134);
            immediates[457] =
                    new Pattern(new long[]{0L, 0L, 0xf8000000000L, 0L}, new long[]{0L, 0L, 0xd8000000000L, 0L}, 150);
            immediates[458] = new Pattern(new long[]{0L, 0L, 260046848L, 0L}, new long[]{0L, 0L, 226492416L, 0L}, 166);
            immediates[459] = new Pattern(new long[]{0L, 0L, 3968L, 0L}, new long[]{0L, 0L, 3456L, 0L}, 182);
            immediates[460] =
                    new Pattern(new long[]{0L, 0L, 0L, 0xf80000000000000L}, new long[]{0L, 0L, 0L, 0xd80000000000000L},
                            198);
            immediates[461] =
                    new Pattern(new long[]{0L, 0L, 0L, 0xf8000000000L}, new long[]{0L, 0L, 0L, 0xd8000000000L}, 214);
            immediates[462] = new Pattern(new long[]{0L, 0L, 0L, 260046848L}, new long[]{0L, 0L, 0L, 226492416L}, 230);
            immediates[463] = new Pattern(new long[]{0L, 0L, 0L, 3968L}, new long[]{0L, 0L, 0L, 3456L}, 246);
            immediates[464] =
                    new Pattern(new long[]{0x7c0000000000000L, 0L, 0L, 0L}, new long[]{0x6c0000000000000L, 0L, 0L, 0L},
                            7);
            immediates[465] =
                    new Pattern(new long[]{8521215115264L, 0L, 0L, 0L}, new long[]{7421703487488L, 0L, 0L, 0L}, 23);
            immediates[466] = new Pattern(new long[]{130023424L, 0L, 0L, 0L}, new long[]{113246208L, 0L, 0L, 0L}, 39);
            immediates[467] = new Pattern(new long[]{1984L, 0L, 0L, 0L}, new long[]{1728L, 0L, 0L, 0L}, 55);
            immediates[468] =
                    new Pattern(new long[]{0L, 0x7c0000000000000L, 0L, 0L}, new long[]{0L, 0x6c0000000000000L, 0L, 0L},
                            71);
            immediates[469] =
                    new Pattern(new long[]{0L, 8521215115264L, 0L, 0L}, new long[]{0L, 7421703487488L, 0L, 0L}, 87);
            immediates[470] = new Pattern(new long[]{0L, 130023424L, 0L, 0L}, new long[]{0L, 113246208L, 0L, 0L}, 103);
            immediates[471] = new Pattern(new long[]{0L, 1984L, 0L, 0L}, new long[]{0L, 1728L, 0L, 0L}, 119);
            immediates[472] =
                    new Pattern(new long[]{0L, 0L, 0x7c0000000000000L, 0L}, new long[]{0L, 0L, 0x6c0000000000000L, 0L},
                            135);
            immediates[473] =
                    new Pattern(new long[]{0L, 0L, 8521215115264L, 0L}, new long[]{0L, 0L, 7421703487488L, 0L}, 151);
            immediates[474] = new Pattern(new long[]{0L, 0L, 130023424L, 0L}, new long[]{0L, 0L, 113246208L, 0L}, 167);
            immediates[475] = new Pattern(new long[]{0L, 0L, 1984L, 0L}, new long[]{0L, 0L, 1728L, 0L}, 183);
            immediates[476] =
                    new Pattern(new long[]{0L, 0L, 0L, 0x7c0000000000000L}, new long[]{0L, 0L, 0L, 0x6c0000000000000L},
                            199);
            immediates[477] =
                    new Pattern(new long[]{0L, 0L, 0L, 8521215115264L}, new long[]{0L, 0L, 0L, 7421703487488L}, 215);
            immediates[478] = new Pattern(new long[]{0L, 0L, 0L, 130023424L}, new long[]{0L, 0L, 0L, 113246208L}, 231);
            immediates[479] = new Pattern(new long[]{0L, 0L, 0L, 1984L}, new long[]{0L, 0L, 0L, 1728L}, 247);
            immediates[480] =
                    new Pattern(new long[]{0x3e0000000000000L, 0L, 0L, 0L}, new long[]{0x360000000000000L, 0L, 0L, 0L},
                            8);
            immediates[481] =
                    new Pattern(new long[]{4260607557632L, 0L, 0L, 0L}, new long[]{3710851743744L, 0L, 0L, 0L}, 24);
            immediates[482] = new Pattern(new long[]{65011712L, 0L, 0L, 0L}, new long[]{56623104L, 0L, 0L, 0L}, 40);
            immediates[483] = new Pattern(new long[]{992L, 0L, 0L, 0L}, new long[]{864L, 0L, 0L, 0L}, 56);
            immediates[484] =
                    new Pattern(new long[]{0L, 0x3e0000000000000L, 0L, 0L}, new long[]{0L, 0x360000000000000L, 0L, 0L},
                            72);
            immediates[485] =
                    new Pattern(new long[]{0L, 4260607557632L, 0L, 0L}, new long[]{0L, 3710851743744L, 0L, 0L}, 88);
            immediates[486] = new Pattern(new long[]{0L, 65011712L, 0L, 0L}, new long[]{0L, 56623104L, 0L, 0L}, 104);
            immediates[487] = new Pattern(new long[]{0L, 992L, 0L, 0L}, new long[]{0L, 864L, 0L, 0L}, 120);
            immediates[488] =
                    new Pattern(new long[]{0L, 0L, 0x3e0000000000000L, 0L}, new long[]{0L, 0L, 0x360000000000000L, 0L},
                            136);
            immediates[489] =
                    new Pattern(new long[]{0L, 0L, 4260607557632L, 0L}, new long[]{0L, 0L, 3710851743744L, 0L}, 152);
            immediates[490] = new Pattern(new long[]{0L, 0L, 65011712L, 0L}, new long[]{0L, 0L, 56623104L, 0L}, 168);
            immediates[491] = new Pattern(new long[]{0L, 0L, 992L, 0L}, new long[]{0L, 0L, 864L, 0L}, 184);
            immediates[492] =
                    new Pattern(new long[]{0L, 0L, 0L, 0x3e0000000000000L}, new long[]{0L, 0L, 0L, 0x360000000000000L},
                            200);
            immediates[493] =
                    new Pattern(new long[]{0L, 0L, 0L, 4260607557632L}, new long[]{0L, 0L, 0L, 3710851743744L}, 216);
            immediates[494] = new Pattern(new long[]{0L, 0L, 0L, 65011712L}, new long[]{0L, 0L, 0L, 56623104L}, 232);
            immediates[495] = new Pattern(new long[]{0L, 0L, 0L, 992L}, new long[]{0L, 0L, 0L, 864L}, 248);
            immediates[496] =
                    new Pattern(new long[]{0x1f0000000000000L, 0L, 0L, 0L}, new long[]{0x1b0000000000000L, 0L, 0L, 0L},
                            9);
            immediates[497] =
                    new Pattern(new long[]{2130303778816L, 0L, 0L, 0L}, new long[]{1855425871872L, 0L, 0L, 0L}, 25);
            immediates[498] = new Pattern(new long[]{32505856L, 0L, 0L, 0L}, new long[]{28311552L, 0L, 0L, 0L}, 41);
            immediates[499] = new Pattern(new long[]{496L, 0L, 0L, 0L}, new long[]{432L, 0L, 0L, 0L}, 57);
        }

        private static void initImmediates1() {
            immediates[500] =
                    new Pattern(new long[]{0L, 0x1f0000000000000L, 0L, 0L}, new long[]{0L, 0x1b0000000000000L, 0L, 0L},
                            73);
            immediates[501] =
                    new Pattern(new long[]{0L, 2130303778816L, 0L, 0L}, new long[]{0L, 1855425871872L, 0L, 0L}, 89);
            immediates[502] = new Pattern(new long[]{0L, 32505856L, 0L, 0L}, new long[]{0L, 28311552L, 0L, 0L}, 105);
            immediates[503] = new Pattern(new long[]{0L, 496L, 0L, 0L}, new long[]{0L, 432L, 0L, 0L}, 121);
            immediates[504] =
                    new Pattern(new long[]{0L, 0L, 0x1f0000000000000L, 0L}, new long[]{0L, 0L, 0x1b0000000000000L, 0L},
                            137);
            immediates[505] =
                    new Pattern(new long[]{0L, 0L, 2130303778816L, 0L}, new long[]{0L, 0L, 1855425871872L, 0L}, 153);
            immediates[506] = new Pattern(new long[]{0L, 0L, 32505856L, 0L}, new long[]{0L, 0L, 28311552L, 0L}, 169);
            immediates[507] = new Pattern(new long[]{0L, 0L, 496L, 0L}, new long[]{0L, 0L, 432L, 0L}, 185);
            immediates[508] =
                    new Pattern(new long[]{0L, 0L, 0L, 0x1f0000000000000L}, new long[]{0L, 0L, 0L, 0x1b0000000000000L},
                            201);
            immediates[509] =
                    new Pattern(new long[]{0L, 0L, 0L, 2130303778816L}, new long[]{0L, 0L, 0L, 1855425871872L}, 217);
            immediates[510] = new Pattern(new long[]{0L, 0L, 0L, 32505856L}, new long[]{0L, 0L, 0L, 28311552L}, 233);
            immediates[511] = new Pattern(new long[]{0L, 0L, 0L, 496L}, new long[]{0L, 0L, 0L, 432L}, 249);
            immediates[512] =
                    new Pattern(new long[]{0xf8000000000000L, 0L, 0L, 0L}, new long[]{0xd8000000000000L, 0L, 0L, 0L},
                            10);
            immediates[513] =
                    new Pattern(new long[]{0xf800000000L, 0L, 0L, 0L}, new long[]{927712935936L, 0L, 0L, 0L}, 26);
            immediates[514] = new Pattern(new long[]{16252928L, 0L, 0L, 0L}, new long[]{14155776L, 0L, 0L, 0L}, 42);
            immediates[515] = new Pattern(new long[]{248L, 0L, 0L, 0L}, new long[]{216L, 0L, 0L, 0L}, 58);
            immediates[516] =
                    new Pattern(new long[]{0L, 0xf8000000000000L, 0L, 0L}, new long[]{0L, 0xd8000000000000L, 0L, 0L},
                            74);
            immediates[517] =
                    new Pattern(new long[]{0L, 0xf800000000L, 0L, 0L}, new long[]{0L, 927712935936L, 0L, 0L}, 90);
            immediates[518] = new Pattern(new long[]{0L, 16252928L, 0L, 0L}, new long[]{0L, 14155776L, 0L, 0L}, 106);
            immediates[519] = new Pattern(new long[]{0L, 248L, 0L, 0L}, new long[]{0L, 216L, 0L, 0L}, 122);
            immediates[520] =
                    new Pattern(new long[]{0L, 0L, 0xf8000000000000L, 0L}, new long[]{0L, 0L, 0xd8000000000000L, 0L},
                            138);
            immediates[521] =
                    new Pattern(new long[]{0L, 0L, 0xf800000000L, 0L}, new long[]{0L, 0L, 927712935936L, 0L}, 154);
            immediates[522] = new Pattern(new long[]{0L, 0L, 16252928L, 0L}, new long[]{0L, 0L, 14155776L, 0L}, 170);
            immediates[523] = new Pattern(new long[]{0L, 0L, 248L, 0L}, new long[]{0L, 0L, 216L, 0L}, 186);
            immediates[524] =
                    new Pattern(new long[]{0L, 0L, 0L, 0xf8000000000000L}, new long[]{0L, 0L, 0L, 0xd8000000000000L},
                            202);
            immediates[525] =
                    new Pattern(new long[]{0L, 0L, 0L, 0xf800000000L}, new long[]{0L, 0L, 0L, 927712935936L}, 218);
            immediates[526] = new Pattern(new long[]{0L, 0L, 0L, 16252928L}, new long[]{0L, 0L, 0L, 14155776L}, 234);
            immediates[527] = new Pattern(new long[]{0L, 0L, 0L, 248L}, new long[]{0L, 0L, 0L, 216L}, 250);
            immediates[528] =
                    new Pattern(new long[]{0x7c000000000000L, 0L, 0L, 0L}, new long[]{0x6c000000000000L, 0L, 0L, 0L},
                            11);
            immediates[529] =
                    new Pattern(new long[]{532575944704L, 0L, 0L, 0L}, new long[]{463856467968L, 0L, 0L, 0L}, 27);
            immediates[530] = new Pattern(new long[]{8126464L, 0L, 0L, 0L}, new long[]{7077888L, 0L, 0L, 0L}, 43);
            immediates[531] = new Pattern(new long[]{124L, 0L, 0L, 0L}, new long[]{108L, 0L, 0L, 0L}, 59);
            immediates[532] =
                    new Pattern(new long[]{0L, 0x7c000000000000L, 0L, 0L}, new long[]{0L, 0x6c000000000000L, 0L, 0L},
                            75);
            immediates[533] =
                    new Pattern(new long[]{0L, 532575944704L, 0L, 0L}, new long[]{0L, 463856467968L, 0L, 0L}, 91);
            immediates[534] = new Pattern(new long[]{0L, 8126464L, 0L, 0L}, new long[]{0L, 7077888L, 0L, 0L}, 107);
            immediates[535] = new Pattern(new long[]{0L, 124L, 0L, 0L}, new long[]{0L, 108L, 0L, 0L}, 123);
            immediates[536] =
                    new Pattern(new long[]{0L, 0L, 0x7c000000000000L, 0L}, new long[]{0L, 0L, 0x6c000000000000L, 0L},
                            139);
            immediates[537] =
                    new Pattern(new long[]{0L, 0L, 532575944704L, 0L}, new long[]{0L, 0L, 463856467968L, 0L}, 155);
            immediates[538] = new Pattern(new long[]{0L, 0L, 8126464L, 0L}, new long[]{0L, 0L, 7077888L, 0L}, 171);
            immediates[539] = new Pattern(new long[]{0L, 0L, 124L, 0L}, new long[]{0L, 0L, 108L, 0L}, 187);
            immediates[540] =
                    new Pattern(new long[]{0L, 0L, 0L, 0x7c000000000000L}, new long[]{0L, 0L, 0L, 0x6c000000000000L},
                            203);
            immediates[541] =
                    new Pattern(new long[]{0L, 0L, 0L, 532575944704L}, new long[]{0L, 0L, 0L, 463856467968L}, 219);
            immediates[542] = new Pattern(new long[]{0L, 0L, 0L, 8126464L}, new long[]{0L, 0L, 0L, 7077888L}, 235);
            immediates[543] = new Pattern(new long[]{0L, 0L, 0L, 124L}, new long[]{0L, 0L, 0L, 108L}, 251);
            immediates[544] =
                    new Pattern(new long[]{0x3e000000000000L, 0L, 0L, 0L}, new long[]{0x36000000000000L, 0L, 0L, 0L},
                            12);
            immediates[545] =
                    new Pattern(new long[]{266287972352L, 0L, 0L, 0L}, new long[]{231928233984L, 0L, 0L, 0L}, 28);
            immediates[546] = new Pattern(new long[]{4063232L, 0L, 0L, 0L}, new long[]{3538944L, 0L, 0L, 0L}, 44);
            immediates[547] = new Pattern(new long[]{62L, 0L, 0L, 0L}, new long[]{54L, 0L, 0L, 0L}, 60);
            immediates[548] =
                    new Pattern(new long[]{0L, 0x3e000000000000L, 0L, 0L}, new long[]{0L, 0x36000000000000L, 0L, 0L},
                            76);
            immediates[549] =
                    new Pattern(new long[]{0L, 266287972352L, 0L, 0L}, new long[]{0L, 231928233984L, 0L, 0L}, 92);
            immediates[550] = new Pattern(new long[]{0L, 4063232L, 0L, 0L}, new long[]{0L, 3538944L, 0L, 0L}, 108);
            immediates[551] = new Pattern(new long[]{0L, 62L, 0L, 0L}, new long[]{0L, 54L, 0L, 0L}, 124);
            immediates[552] =
                    new Pattern(new long[]{0L, 0L, 0x3e000000000000L, 0L}, new long[]{0L, 0L, 0x36000000000000L, 0L},
                            140);
            immediates[553] =
                    new Pattern(new long[]{0L, 0L, 266287972352L, 0L}, new long[]{0L, 0L, 231928233984L, 0L}, 156);
            immediates[554] = new Pattern(new long[]{0L, 0L, 4063232L, 0L}, new long[]{0L, 0L, 3538944L, 0L}, 172);
            immediates[555] = new Pattern(new long[]{0L, 0L, 62L, 0L}, new long[]{0L, 0L, 54L, 0L}, 188);
            immediates[556] =
                    new Pattern(new long[]{0L, 0L, 0L, 0x3e000000000000L}, new long[]{0L, 0L, 0L, 0x36000000000000L},
                            204);
            immediates[557] =
                    new Pattern(new long[]{0L, 0L, 0L, 266287972352L}, new long[]{0L, 0L, 0L, 231928233984L}, 220);
            immediates[558] = new Pattern(new long[]{0L, 0L, 0L, 4063232L}, new long[]{0L, 0L, 0L, 3538944L}, 236);
            immediates[559] = new Pattern(new long[]{0L, 0L, 0L, 62L}, new long[]{0L, 0L, 0L, 54L}, 252);
            immediates[560] =
                    new Pattern(new long[]{8725724278030336L, 0L, 0L, 0L}, new long[]{7599824371187712L, 0L, 0L, 0L},
                            13);
            immediates[561] =
                    new Pattern(new long[]{133143986176L, 0L, 0L, 0L}, new long[]{115964116992L, 0L, 0L, 0L}, 29);
            immediates[562] = new Pattern(new long[]{2031616L, 0L, 0L, 0L}, new long[]{1769472L, 0L, 0L, 0L}, 45);
            immediates[563] = new Pattern(new long[]{31L, 0L, 0L, 0L}, new long[]{27L, 0L, 0L, 0L}, 61);
            immediates[564] =
                    new Pattern(new long[]{0L, 8725724278030336L, 0L, 0L}, new long[]{0L, 7599824371187712L, 0L, 0L},
                            77);
            immediates[565] =
                    new Pattern(new long[]{0L, 133143986176L, 0L, 0L}, new long[]{0L, 115964116992L, 0L, 0L}, 93);
            immediates[566] = new Pattern(new long[]{0L, 2031616L, 0L, 0L}, new long[]{0L, 1769472L, 0L, 0L}, 109);
            immediates[567] = new Pattern(new long[]{0L, 31L, 0L, 0L}, new long[]{0L, 27L, 0L, 0L}, 125);
            immediates[568] =
                    new Pattern(new long[]{0L, 0L, 8725724278030336L, 0L}, new long[]{0L, 0L, 7599824371187712L, 0L},
                            141);
            immediates[569] =
                    new Pattern(new long[]{0L, 0L, 133143986176L, 0L}, new long[]{0L, 0L, 115964116992L, 0L}, 157);
            immediates[570] = new Pattern(new long[]{0L, 0L, 2031616L, 0L}, new long[]{0L, 0L, 1769472L, 0L}, 173);
            immediates[571] = new Pattern(new long[]{0L, 0L, 31L, 0L}, new long[]{0L, 0L, 27L, 0L}, 189);
            immediates[572] =
                    new Pattern(new long[]{0L, 0L, 0L, 8725724278030336L}, new long[]{0L, 0L, 0L, 7599824371187712L},
                            205);
            immediates[573] =
                    new Pattern(new long[]{0L, 0L, 0L, 133143986176L}, new long[]{0L, 0L, 0L, 115964116992L}, 221);
            immediates[574] = new Pattern(new long[]{0L, 0L, 0L, 2031616L}, new long[]{0L, 0L, 0L, 1769472L}, 237);
            immediates[575] = new Pattern(new long[]{0L, 0L, 0L, 31L}, new long[]{0L, 0L, 0L, 27L}, 253);
            immediates[576] = new Pattern(new long[]{0xf800000000000000L, 0L, 0L, 0L},
                    new long[]{0xe800000000000000L, 0L, 0L, 0L}, 3);
            immediates[577] =
                    new Pattern(new long[]{0xf80000000000L, 0L, 0L, 0L}, new long[]{0xe80000000000L, 0L, 0L, 0L}, 19);
            immediates[578] = new Pattern(new long[]{4160749568L, 0L, 0L, 0L}, new long[]{3892314112L, 0L, 0L, 0L}, 35);
            immediates[579] = new Pattern(new long[]{63488L, 0L, 0L, 0L}, new long[]{59392L, 0L, 0L, 0L}, 51);
            immediates[580] = new Pattern(new long[]{0L, 0xf800000000000000L, 0L, 0L},
                    new long[]{0L, 0xe800000000000000L, 0L, 0L}, 67);
            immediates[581] =
                    new Pattern(new long[]{0L, 0xf80000000000L, 0L, 0L}, new long[]{0L, 0xe80000000000L, 0L, 0L}, 83);
            immediates[582] = new Pattern(new long[]{0L, 4160749568L, 0L, 0L}, new long[]{0L, 3892314112L, 0L, 0L}, 99);
            immediates[583] = new Pattern(new long[]{0L, 63488L, 0L, 0L}, new long[]{0L, 59392L, 0L, 0L}, 115);
            immediates[584] = new Pattern(new long[]{0L, 0L, 0xf800000000000000L, 0L},
                    new long[]{0L, 0L, 0xe800000000000000L, 0L}, 131);
            immediates[585] =
                    new Pattern(new long[]{0L, 0L, 0xf80000000000L, 0L}, new long[]{0L, 0L, 0xe80000000000L, 0L}, 147);
            immediates[586] =
                    new Pattern(new long[]{0L, 0L, 4160749568L, 0L}, new long[]{0L, 0L, 3892314112L, 0L}, 163);
            immediates[587] = new Pattern(new long[]{0L, 0L, 63488L, 0L}, new long[]{0L, 0L, 59392L, 0L}, 179);
            immediates[588] = new Pattern(new long[]{0L, 0L, 0L, 0xf800000000000000L},
                    new long[]{0L, 0L, 0L, 0xe800000000000000L}, 195);
            immediates[589] =
                    new Pattern(new long[]{0L, 0L, 0L, 0xf80000000000L}, new long[]{0L, 0L, 0L, 0xe80000000000L}, 211);
            immediates[590] =
                    new Pattern(new long[]{0L, 0L, 0L, 4160749568L}, new long[]{0L, 0L, 0L, 3892314112L}, 227);
            immediates[591] = new Pattern(new long[]{0L, 0L, 0L, 63488L}, new long[]{0L, 0L, 0L, 59392L}, 243);
            immediates[592] = new Pattern(new long[]{0x7c00000000000000L, 0L, 0L, 0L},
                    new long[]{0x7400000000000000L, 0L, 0L, 0L}, 4);
            immediates[593] =
                    new Pattern(new long[]{0x7c0000000000L, 0L, 0L, 0L}, new long[]{0x740000000000L, 0L, 0L, 0L}, 20);
            immediates[594] = new Pattern(new long[]{2080374784L, 0L, 0L, 0L}, new long[]{1946157056L, 0L, 0L, 0L}, 36);
            immediates[595] = new Pattern(new long[]{31744L, 0L, 0L, 0L}, new long[]{29696L, 0L, 0L, 0L}, 52);
            immediates[596] = new Pattern(new long[]{0L, 0x7c00000000000000L, 0L, 0L},
                    new long[]{0L, 0x7400000000000000L, 0L, 0L}, 68);
            immediates[597] =
                    new Pattern(new long[]{0L, 0x7c0000000000L, 0L, 0L}, new long[]{0L, 0x740000000000L, 0L, 0L}, 84);
            immediates[598] =
                    new Pattern(new long[]{0L, 2080374784L, 0L, 0L}, new long[]{0L, 1946157056L, 0L, 0L}, 100);
            immediates[599] = new Pattern(new long[]{0L, 31744L, 0L, 0L}, new long[]{0L, 29696L, 0L, 0L}, 116);
            immediates[600] = new Pattern(new long[]{0L, 0L, 0x7c00000000000000L, 0L},
                    new long[]{0L, 0L, 0x7400000000000000L, 0L}, 132);
            immediates[601] =
                    new Pattern(new long[]{0L, 0L, 0x7c0000000000L, 0L}, new long[]{0L, 0L, 0x740000000000L, 0L}, 148);
            immediates[602] =
                    new Pattern(new long[]{0L, 0L, 2080374784L, 0L}, new long[]{0L, 0L, 1946157056L, 0L}, 164);
            immediates[603] = new Pattern(new long[]{0L, 0L, 31744L, 0L}, new long[]{0L, 0L, 29696L, 0L}, 180);
            immediates[604] = new Pattern(new long[]{0L, 0L, 0L, 0x7c00000000000000L},
                    new long[]{0L, 0L, 0L, 0x7400000000000000L}, 196);
            immediates[605] =
                    new Pattern(new long[]{0L, 0L, 0L, 0x7c0000000000L}, new long[]{0L, 0L, 0L, 0x740000000000L}, 212);
            immediates[606] =
                    new Pattern(new long[]{0L, 0L, 0L, 2080374784L}, new long[]{0L, 0L, 0L, 1946157056L}, 228);
            immediates[607] = new Pattern(new long[]{0L, 0L, 0L, 31744L}, new long[]{0L, 0L, 0L, 29696L}, 244);
            immediates[608] = new Pattern(new long[]{0x3e00000000000000L, 0L, 0L, 0L},
                    new long[]{0x3a00000000000000L, 0L, 0L, 0L}, 5);
            immediates[609] =
                    new Pattern(new long[]{68169720922112L, 0L, 0L, 0L}, new long[]{63771674411008L, 0L, 0L, 0L}, 21);
            immediates[610] = new Pattern(new long[]{1040187392L, 0L, 0L, 0L}, new long[]{973078528L, 0L, 0L, 0L}, 37);
            immediates[611] = new Pattern(new long[]{15872L, 0L, 0L, 0L}, new long[]{14848L, 0L, 0L, 0L}, 53);
            immediates[612] = new Pattern(new long[]{0L, 0x3e00000000000000L, 0L, 0L},
                    new long[]{0L, 0x3a00000000000000L, 0L, 0L}, 69);
            immediates[613] =
                    new Pattern(new long[]{0L, 68169720922112L, 0L, 0L}, new long[]{0L, 63771674411008L, 0L, 0L}, 85);
            immediates[614] = new Pattern(new long[]{0L, 1040187392L, 0L, 0L}, new long[]{0L, 973078528L, 0L, 0L}, 101);
            immediates[615] = new Pattern(new long[]{0L, 15872L, 0L, 0L}, new long[]{0L, 14848L, 0L, 0L}, 117);
            immediates[616] = new Pattern(new long[]{0L, 0L, 0x3e00000000000000L, 0L},
                    new long[]{0L, 0L, 0x3a00000000000000L, 0L}, 133);
            immediates[617] =
                    new Pattern(new long[]{0L, 0L, 68169720922112L, 0L}, new long[]{0L, 0L, 63771674411008L, 0L}, 149);
            immediates[618] = new Pattern(new long[]{0L, 0L, 1040187392L, 0L}, new long[]{0L, 0L, 973078528L, 0L}, 165);
            immediates[619] = new Pattern(new long[]{0L, 0L, 15872L, 0L}, new long[]{0L, 0L, 14848L, 0L}, 181);
            immediates[620] = new Pattern(new long[]{0L, 0L, 0L, 0x3e00000000000000L},
                    new long[]{0L, 0L, 0L, 0x3a00000000000000L}, 197);
            immediates[621] =
                    new Pattern(new long[]{0L, 0L, 0L, 68169720922112L}, new long[]{0L, 0L, 0L, 63771674411008L}, 213);
            immediates[622] = new Pattern(new long[]{0L, 0L, 0L, 1040187392L}, new long[]{0L, 0L, 0L, 973078528L}, 229);
            immediates[623] = new Pattern(new long[]{0L, 0L, 0L, 15872L}, new long[]{0L, 0L, 0L, 14848L}, 245);
            immediates[624] = new Pattern(new long[]{0x1f00000000000000L, 0L, 0L, 0L},
                    new long[]{0x1d00000000000000L, 0L, 0L, 0L}, 6);
            immediates[625] =
                    new Pattern(new long[]{34084860461056L, 0L, 0L, 0L}, new long[]{31885837205504L, 0L, 0L, 0L}, 22);
            immediates[626] = new Pattern(new long[]{520093696L, 0L, 0L, 0L}, new long[]{486539264L, 0L, 0L, 0L}, 38);
            immediates[627] = new Pattern(new long[]{7936L, 0L, 0L, 0L}, new long[]{7424L, 0L, 0L, 0L}, 54);
            immediates[628] = new Pattern(new long[]{0L, 0x1f00000000000000L, 0L, 0L},
                    new long[]{0L, 0x1d00000000000000L, 0L, 0L}, 70);
            immediates[629] =
                    new Pattern(new long[]{0L, 34084860461056L, 0L, 0L}, new long[]{0L, 31885837205504L, 0L, 0L}, 86);
            immediates[630] = new Pattern(new long[]{0L, 520093696L, 0L, 0L}, new long[]{0L, 486539264L, 0L, 0L}, 102);
            immediates[631] = new Pattern(new long[]{0L, 7936L, 0L, 0L}, new long[]{0L, 7424L, 0L, 0L}, 118);
            immediates[632] = new Pattern(new long[]{0L, 0L, 0x1f00000000000000L, 0L},
                    new long[]{0L, 0L, 0x1d00000000000000L, 0L}, 134);
            immediates[633] =
                    new Pattern(new long[]{0L, 0L, 34084860461056L, 0L}, new long[]{0L, 0L, 31885837205504L, 0L}, 150);
            immediates[634] = new Pattern(new long[]{0L, 0L, 520093696L, 0L}, new long[]{0L, 0L, 486539264L, 0L}, 166);
            immediates[635] = new Pattern(new long[]{0L, 0L, 7936L, 0L}, new long[]{0L, 0L, 7424L, 0L}, 182);
            immediates[636] = new Pattern(new long[]{0L, 0L, 0L, 0x1f00000000000000L},
                    new long[]{0L, 0L, 0L, 0x1d00000000000000L}, 198);
            immediates[637] =
                    new Pattern(new long[]{0L, 0L, 0L, 34084860461056L}, new long[]{0L, 0L, 0L, 31885837205504L}, 214);
            immediates[638] = new Pattern(new long[]{0L, 0L, 0L, 520093696L}, new long[]{0L, 0L, 0L, 486539264L}, 230);
            immediates[639] = new Pattern(new long[]{0L, 0L, 0L, 7936L}, new long[]{0L, 0L, 0L, 7424L}, 246);
            immediates[640] =
                    new Pattern(new long[]{0xf80000000000000L, 0L, 0L, 0L}, new long[]{0xe80000000000000L, 0L, 0L, 0L},
                            7);
            immediates[641] =
                    new Pattern(new long[]{0xf8000000000L, 0L, 0L, 0L}, new long[]{0xe8000000000L, 0L, 0L, 0L}, 23);
            immediates[642] = new Pattern(new long[]{260046848L, 0L, 0L, 0L}, new long[]{243269632L, 0L, 0L, 0L}, 39);
            immediates[643] = new Pattern(new long[]{3968L, 0L, 0L, 0L}, new long[]{3712L, 0L, 0L, 0L}, 55);
            immediates[644] =
                    new Pattern(new long[]{0L, 0xf80000000000000L, 0L, 0L}, new long[]{0L, 0xe80000000000000L, 0L, 0L},
                            71);
            immediates[645] =
                    new Pattern(new long[]{0L, 0xf8000000000L, 0L, 0L}, new long[]{0L, 0xe8000000000L, 0L, 0L}, 87);
            immediates[646] = new Pattern(new long[]{0L, 260046848L, 0L, 0L}, new long[]{0L, 243269632L, 0L, 0L}, 103);
            immediates[647] = new Pattern(new long[]{0L, 3968L, 0L, 0L}, new long[]{0L, 3712L, 0L, 0L}, 119);
            immediates[648] =
                    new Pattern(new long[]{0L, 0L, 0xf80000000000000L, 0L}, new long[]{0L, 0L, 0xe80000000000000L, 0L},
                            135);
            immediates[649] =
                    new Pattern(new long[]{0L, 0L, 0xf8000000000L, 0L}, new long[]{0L, 0L, 0xe8000000000L, 0L}, 151);
            immediates[650] = new Pattern(new long[]{0L, 0L, 260046848L, 0L}, new long[]{0L, 0L, 243269632L, 0L}, 167);
            immediates[651] = new Pattern(new long[]{0L, 0L, 3968L, 0L}, new long[]{0L, 0L, 3712L, 0L}, 183);
            immediates[652] =
                    new Pattern(new long[]{0L, 0L, 0L, 0xf80000000000000L}, new long[]{0L, 0L, 0L, 0xe80000000000000L},
                            199);
            immediates[653] =
                    new Pattern(new long[]{0L, 0L, 0L, 0xf8000000000L}, new long[]{0L, 0L, 0L, 0xe8000000000L}, 215);
            immediates[654] = new Pattern(new long[]{0L, 0L, 0L, 260046848L}, new long[]{0L, 0L, 0L, 243269632L}, 231);
            immediates[655] = new Pattern(new long[]{0L, 0L, 0L, 3968L}, new long[]{0L, 0L, 0L, 3712L}, 247);
            immediates[656] =
                    new Pattern(new long[]{0x7c0000000000000L, 0L, 0L, 0L}, new long[]{0x740000000000000L, 0L, 0L, 0L},
                            8);
            immediates[657] =
                    new Pattern(new long[]{8521215115264L, 0L, 0L, 0L}, new long[]{7971459301376L, 0L, 0L, 0L}, 24);
            immediates[658] = new Pattern(new long[]{130023424L, 0L, 0L, 0L}, new long[]{121634816L, 0L, 0L, 0L}, 40);
            immediates[659] = new Pattern(new long[]{1984L, 0L, 0L, 0L}, new long[]{1856L, 0L, 0L, 0L}, 56);
            immediates[660] =
                    new Pattern(new long[]{0L, 0x7c0000000000000L, 0L, 0L}, new long[]{0L, 0x740000000000000L, 0L, 0L},
                            72);
            immediates[661] =
                    new Pattern(new long[]{0L, 8521215115264L, 0L, 0L}, new long[]{0L, 7971459301376L, 0L, 0L}, 88);
            immediates[662] = new Pattern(new long[]{0L, 130023424L, 0L, 0L}, new long[]{0L, 121634816L, 0L, 0L}, 104);
            immediates[663] = new Pattern(new long[]{0L, 1984L, 0L, 0L}, new long[]{0L, 1856L, 0L, 0L}, 120);
            immediates[664] =
                    new Pattern(new long[]{0L, 0L, 0x7c0000000000000L, 0L}, new long[]{0L, 0L, 0x740000000000000L, 0L},
                            136);
            immediates[665] =
                    new Pattern(new long[]{0L, 0L, 8521215115264L, 0L}, new long[]{0L, 0L, 7971459301376L, 0L}, 152);
            immediates[666] = new Pattern(new long[]{0L, 0L, 130023424L, 0L}, new long[]{0L, 0L, 121634816L, 0L}, 168);
            immediates[667] = new Pattern(new long[]{0L, 0L, 1984L, 0L}, new long[]{0L, 0L, 1856L, 0L}, 184);
            immediates[668] =
                    new Pattern(new long[]{0L, 0L, 0L, 0x7c0000000000000L}, new long[]{0L, 0L, 0L, 0x740000000000000L},
                            200);
            immediates[669] =
                    new Pattern(new long[]{0L, 0L, 0L, 8521215115264L}, new long[]{0L, 0L, 0L, 7971459301376L}, 216);
            immediates[670] = new Pattern(new long[]{0L, 0L, 0L, 130023424L}, new long[]{0L, 0L, 0L, 121634816L}, 232);
            immediates[671] = new Pattern(new long[]{0L, 0L, 0L, 1984L}, new long[]{0L, 0L, 0L, 1856L}, 248);
            immediates[672] =
                    new Pattern(new long[]{0x3e0000000000000L, 0L, 0L, 0L}, new long[]{0x3a0000000000000L, 0L, 0L, 0L},
                            9);
            immediates[673] =
                    new Pattern(new long[]{4260607557632L, 0L, 0L, 0L}, new long[]{3985729650688L, 0L, 0L, 0L}, 25);
            immediates[674] = new Pattern(new long[]{65011712L, 0L, 0L, 0L}, new long[]{60817408L, 0L, 0L, 0L}, 41);
            immediates[675] = new Pattern(new long[]{992L, 0L, 0L, 0L}, new long[]{928L, 0L, 0L, 0L}, 57);
            immediates[676] =
                    new Pattern(new long[]{0L, 0x3e0000000000000L, 0L, 0L}, new long[]{0L, 0x3a0000000000000L, 0L, 0L},
                            73);
            immediates[677] =
                    new Pattern(new long[]{0L, 4260607557632L, 0L, 0L}, new long[]{0L, 3985729650688L, 0L, 0L}, 89);
            immediates[678] = new Pattern(new long[]{0L, 65011712L, 0L, 0L}, new long[]{0L, 60817408L, 0L, 0L}, 105);
            immediates[679] = new Pattern(new long[]{0L, 992L, 0L, 0L}, new long[]{0L, 928L, 0L, 0L}, 121);
            immediates[680] =
                    new Pattern(new long[]{0L, 0L, 0x3e0000000000000L, 0L}, new long[]{0L, 0L, 0x3a0000000000000L, 0L},
                            137);
            immediates[681] =
                    new Pattern(new long[]{0L, 0L, 4260607557632L, 0L}, new long[]{0L, 0L, 3985729650688L, 0L}, 153);
            immediates[682] = new Pattern(new long[]{0L, 0L, 65011712L, 0L}, new long[]{0L, 0L, 60817408L, 0L}, 169);
            immediates[683] = new Pattern(new long[]{0L, 0L, 992L, 0L}, new long[]{0L, 0L, 928L, 0L}, 185);
            immediates[684] =
                    new Pattern(new long[]{0L, 0L, 0L, 0x3e0000000000000L}, new long[]{0L, 0L, 0L, 0x3a0000000000000L},
                            201);
            immediates[685] =
                    new Pattern(new long[]{0L, 0L, 0L, 4260607557632L}, new long[]{0L, 0L, 0L, 3985729650688L}, 217);
            immediates[686] = new Pattern(new long[]{0L, 0L, 0L, 65011712L}, new long[]{0L, 0L, 0L, 60817408L}, 233);
            immediates[687] = new Pattern(new long[]{0L, 0L, 0L, 992L}, new long[]{0L, 0L, 0L, 928L}, 249);
            immediates[688] =
                    new Pattern(new long[]{0x1f0000000000000L, 0L, 0L, 0L}, new long[]{0x1d0000000000000L, 0L, 0L, 0L},
                            10);
            immediates[689] =
                    new Pattern(new long[]{2130303778816L, 0L, 0L, 0L}, new long[]{1992864825344L, 0L, 0L, 0L}, 26);
            immediates[690] = new Pattern(new long[]{32505856L, 0L, 0L, 0L}, new long[]{30408704L, 0L, 0L, 0L}, 42);
            immediates[691] = new Pattern(new long[]{496L, 0L, 0L, 0L}, new long[]{464L, 0L, 0L, 0L}, 58);
            immediates[692] =
                    new Pattern(new long[]{0L, 0x1f0000000000000L, 0L, 0L}, new long[]{0L, 0x1d0000000000000L, 0L, 0L},
                            74);
            immediates[693] =
                    new Pattern(new long[]{0L, 2130303778816L, 0L, 0L}, new long[]{0L, 1992864825344L, 0L, 0L}, 90);
            immediates[694] = new Pattern(new long[]{0L, 32505856L, 0L, 0L}, new long[]{0L, 30408704L, 0L, 0L}, 106);
            immediates[695] = new Pattern(new long[]{0L, 496L, 0L, 0L}, new long[]{0L, 464L, 0L, 0L}, 122);
            immediates[696] =
                    new Pattern(new long[]{0L, 0L, 0x1f0000000000000L, 0L}, new long[]{0L, 0L, 0x1d0000000000000L, 0L},
                            138);
            immediates[697] =
                    new Pattern(new long[]{0L, 0L, 2130303778816L, 0L}, new long[]{0L, 0L, 1992864825344L, 0L}, 154);
            immediates[698] = new Pattern(new long[]{0L, 0L, 32505856L, 0L}, new long[]{0L, 0L, 30408704L, 0L}, 170);
            immediates[699] = new Pattern(new long[]{0L, 0L, 496L, 0L}, new long[]{0L, 0L, 464L, 0L}, 186);
            immediates[700] =
                    new Pattern(new long[]{0L, 0L, 0L, 0x1f0000000000000L}, new long[]{0L, 0L, 0L, 0x1d0000000000000L},
                            202);
            immediates[701] =
                    new Pattern(new long[]{0L, 0L, 0L, 2130303778816L}, new long[]{0L, 0L, 0L, 1992864825344L}, 218);
            immediates[702] = new Pattern(new long[]{0L, 0L, 0L, 32505856L}, new long[]{0L, 0L, 0L, 30408704L}, 234);
            immediates[703] = new Pattern(new long[]{0L, 0L, 0L, 496L}, new long[]{0L, 0L, 0L, 464L}, 250);
            immediates[704] =
                    new Pattern(new long[]{0xf8000000000000L, 0L, 0L, 0L}, new long[]{0xe8000000000000L, 0L, 0L, 0L},
                            11);
            immediates[705] =
                    new Pattern(new long[]{0xf800000000L, 0L, 0L, 0L}, new long[]{996432412672L, 0L, 0L, 0L}, 27);
            immediates[706] = new Pattern(new long[]{16252928L, 0L, 0L, 0L}, new long[]{15204352L, 0L, 0L, 0L}, 43);
            immediates[707] = new Pattern(new long[]{248L, 0L, 0L, 0L}, new long[]{232L, 0L, 0L, 0L}, 59);
            immediates[708] =
                    new Pattern(new long[]{0L, 0xf8000000000000L, 0L, 0L}, new long[]{0L, 0xe8000000000000L, 0L, 0L},
                            75);
            immediates[709] =
                    new Pattern(new long[]{0L, 0xf800000000L, 0L, 0L}, new long[]{0L, 996432412672L, 0L, 0L}, 91);
            immediates[710] = new Pattern(new long[]{0L, 16252928L, 0L, 0L}, new long[]{0L, 15204352L, 0L, 0L}, 107);
            immediates[711] = new Pattern(new long[]{0L, 248L, 0L, 0L}, new long[]{0L, 232L, 0L, 0L}, 123);
            immediates[712] =
                    new Pattern(new long[]{0L, 0L, 0xf8000000000000L, 0L}, new long[]{0L, 0L, 0xe8000000000000L, 0L},
                            139);
            immediates[713] =
                    new Pattern(new long[]{0L, 0L, 0xf800000000L, 0L}, new long[]{0L, 0L, 996432412672L, 0L}, 155);
            immediates[714] = new Pattern(new long[]{0L, 0L, 16252928L, 0L}, new long[]{0L, 0L, 15204352L, 0L}, 171);
            immediates[715] = new Pattern(new long[]{0L, 0L, 248L, 0L}, new long[]{0L, 0L, 232L, 0L}, 187);
            immediates[716] =
                    new Pattern(new long[]{0L, 0L, 0L, 0xf8000000000000L}, new long[]{0L, 0L, 0L, 0xe8000000000000L},
                            203);
            immediates[717] =
                    new Pattern(new long[]{0L, 0L, 0L, 0xf800000000L}, new long[]{0L, 0L, 0L, 996432412672L}, 219);
            immediates[718] = new Pattern(new long[]{0L, 0L, 0L, 16252928L}, new long[]{0L, 0L, 0L, 15204352L}, 235);
            immediates[719] = new Pattern(new long[]{0L, 0L, 0L, 248L}, new long[]{0L, 0L, 0L, 232L}, 251);
            immediates[720] =
                    new Pattern(new long[]{0x7c000000000000L, 0L, 0L, 0L}, new long[]{0x74000000000000L, 0L, 0L, 0L},
                            12);
            immediates[721] =
                    new Pattern(new long[]{532575944704L, 0L, 0L, 0L}, new long[]{498216206336L, 0L, 0L, 0L}, 28);
            immediates[722] = new Pattern(new long[]{8126464L, 0L, 0L, 0L}, new long[]{7602176L, 0L, 0L, 0L}, 44);
            immediates[723] = new Pattern(new long[]{124L, 0L, 0L, 0L}, new long[]{116L, 0L, 0L, 0L}, 60);
            immediates[724] =
                    new Pattern(new long[]{0L, 0x7c000000000000L, 0L, 0L}, new long[]{0L, 0x74000000000000L, 0L, 0L},
                            76);
            immediates[725] =
                    new Pattern(new long[]{0L, 532575944704L, 0L, 0L}, new long[]{0L, 498216206336L, 0L, 0L}, 92);
            immediates[726] = new Pattern(new long[]{0L, 8126464L, 0L, 0L}, new long[]{0L, 7602176L, 0L, 0L}, 108);
            immediates[727] = new Pattern(new long[]{0L, 124L, 0L, 0L}, new long[]{0L, 116L, 0L, 0L}, 124);
            immediates[728] =
                    new Pattern(new long[]{0L, 0L, 0x7c000000000000L, 0L}, new long[]{0L, 0L, 0x74000000000000L, 0L},
                            140);
            immediates[729] =
                    new Pattern(new long[]{0L, 0L, 532575944704L, 0L}, new long[]{0L, 0L, 498216206336L, 0L}, 156);
            immediates[730] = new Pattern(new long[]{0L, 0L, 8126464L, 0L}, new long[]{0L, 0L, 7602176L, 0L}, 172);
            immediates[731] = new Pattern(new long[]{0L, 0L, 124L, 0L}, new long[]{0L, 0L, 116L, 0L}, 188);
            immediates[732] =
                    new Pattern(new long[]{0L, 0L, 0L, 0x7c000000000000L}, new long[]{0L, 0L, 0L, 0x74000000000000L},
                            204);
            immediates[733] =
                    new Pattern(new long[]{0L, 0L, 0L, 532575944704L}, new long[]{0L, 0L, 0L, 498216206336L}, 220);
            immediates[734] = new Pattern(new long[]{0L, 0L, 0L, 8126464L}, new long[]{0L, 0L, 0L, 7602176L}, 236);
            immediates[735] = new Pattern(new long[]{0L, 0L, 0L, 124L}, new long[]{0L, 0L, 0L, 116L}, 252);
            immediates[736] =
                    new Pattern(new long[]{0x3e000000000000L, 0L, 0L, 0L}, new long[]{0x3a000000000000L, 0L, 0L, 0L},
                            13);
            immediates[737] =
                    new Pattern(new long[]{266287972352L, 0L, 0L, 0L}, new long[]{249108103168L, 0L, 0L, 0L}, 29);
            immediates[738] = new Pattern(new long[]{4063232L, 0L, 0L, 0L}, new long[]{3801088L, 0L, 0L, 0L}, 45);
            immediates[739] = new Pattern(new long[]{62L, 0L, 0L, 0L}, new long[]{58L, 0L, 0L, 0L}, 61);
            immediates[740] =
                    new Pattern(new long[]{0L, 0x3e000000000000L, 0L, 0L}, new long[]{0L, 0x3a000000000000L, 0L, 0L},
                            77);
            immediates[741] =
                    new Pattern(new long[]{0L, 266287972352L, 0L, 0L}, new long[]{0L, 249108103168L, 0L, 0L}, 93);
            immediates[742] = new Pattern(new long[]{0L, 4063232L, 0L, 0L}, new long[]{0L, 3801088L, 0L, 0L}, 109);
            immediates[743] = new Pattern(new long[]{0L, 62L, 0L, 0L}, new long[]{0L, 58L, 0L, 0L}, 125);
            immediates[744] =
                    new Pattern(new long[]{0L, 0L, 0x3e000000000000L, 0L}, new long[]{0L, 0L, 0x3a000000000000L, 0L},
                            141);
            immediates[745] =
                    new Pattern(new long[]{0L, 0L, 266287972352L, 0L}, new long[]{0L, 0L, 249108103168L, 0L}, 157);
            immediates[746] = new Pattern(new long[]{0L, 0L, 4063232L, 0L}, new long[]{0L, 0L, 3801088L, 0L}, 173);
            immediates[747] = new Pattern(new long[]{0L, 0L, 62L, 0L}, new long[]{0L, 0L, 58L, 0L}, 189);
            immediates[748] =
                    new Pattern(new long[]{0L, 0L, 0L, 0x3e000000000000L}, new long[]{0L, 0L, 0L, 0x3a000000000000L},
                            205);
            immediates[749] =
                    new Pattern(new long[]{0L, 0L, 0L, 266287972352L}, new long[]{0L, 0L, 0L, 249108103168L}, 221);
            immediates[750] = new Pattern(new long[]{0L, 0L, 0L, 4063232L}, new long[]{0L, 0L, 0L, 3801088L}, 237);
            immediates[751] = new Pattern(new long[]{0L, 0L, 0L, 62L}, new long[]{0L, 0L, 0L, 58L}, 253);
            immediates[752] =
                    new Pattern(new long[]{8725724278030336L, 0L, 0L, 0L}, new long[]{8162774324609024L, 0L, 0L, 0L},
                            14);
            immediates[753] =
                    new Pattern(new long[]{133143986176L, 0L, 0L, 0L}, new long[]{124554051584L, 0L, 0L, 0L}, 30);
            immediates[754] = new Pattern(new long[]{2031616L, 0L, 0L, 0L}, new long[]{1900544L, 0L, 0L, 0L}, 46);
            immediates[755] = new Pattern(new long[]{31L, 0L, 0L, 0L}, new long[]{29L, 0L, 0L, 0L}, 62);
            immediates[756] =
                    new Pattern(new long[]{0L, 8725724278030336L, 0L, 0L}, new long[]{0L, 8162774324609024L, 0L, 0L},
                            78);
            immediates[757] =
                    new Pattern(new long[]{0L, 133143986176L, 0L, 0L}, new long[]{0L, 124554051584L, 0L, 0L}, 94);
            immediates[758] = new Pattern(new long[]{0L, 2031616L, 0L, 0L}, new long[]{0L, 1900544L, 0L, 0L}, 110);
            immediates[759] = new Pattern(new long[]{0L, 31L, 0L, 0L}, new long[]{0L, 29L, 0L, 0L}, 126);
            immediates[760] =
                    new Pattern(new long[]{0L, 0L, 8725724278030336L, 0L}, new long[]{0L, 0L, 8162774324609024L, 0L},
                            142);
            immediates[761] =
                    new Pattern(new long[]{0L, 0L, 133143986176L, 0L}, new long[]{0L, 0L, 124554051584L, 0L}, 158);
            immediates[762] = new Pattern(new long[]{0L, 0L, 2031616L, 0L}, new long[]{0L, 0L, 1900544L, 0L}, 174);
            immediates[763] = new Pattern(new long[]{0L, 0L, 31L, 0L}, new long[]{0L, 0L, 29L, 0L}, 190);
            immediates[764] =
                    new Pattern(new long[]{0L, 0L, 0L, 8725724278030336L}, new long[]{0L, 0L, 0L, 8162774324609024L},
                            206);
            immediates[765] =
                    new Pattern(new long[]{0L, 0L, 0L, 133143986176L}, new long[]{0L, 0L, 0L, 124554051584L}, 222);
            immediates[766] = new Pattern(new long[]{0L, 0L, 0L, 2031616L}, new long[]{0L, 0L, 0L, 1900544L}, 238);
            immediates[767] = new Pattern(new long[]{0L, 0L, 0L, 31L}, new long[]{0L, 0L, 0L, 29L}, 254);
            immediates[768] = new Pattern(new long[]{0xf800000000000000L, 0L, 0L, 0L},
                    new long[]{0xf000000000000000L, 0L, 0L, 0L}, 4);
            immediates[769] =
                    new Pattern(new long[]{0xf80000000000L, 0L, 0L, 0L}, new long[]{0xf00000000000L, 0L, 0L, 0L}, 20);
            immediates[770] = new Pattern(new long[]{4160749568L, 0L, 0L, 0L}, new long[]{4026531840L, 0L, 0L, 0L}, 36);
            immediates[771] = new Pattern(new long[]{63488L, 0L, 0L, 0L}, new long[]{61440L, 0L, 0L, 0L}, 52);
            immediates[772] = new Pattern(new long[]{0L, 0xf800000000000000L, 0L, 0L},
                    new long[]{0L, 0xf000000000000000L, 0L, 0L}, 68);
            immediates[773] =
                    new Pattern(new long[]{0L, 0xf80000000000L, 0L, 0L}, new long[]{0L, 0xf00000000000L, 0L, 0L}, 84);
            immediates[774] =
                    new Pattern(new long[]{0L, 4160749568L, 0L, 0L}, new long[]{0L, 4026531840L, 0L, 0L}, 100);
            immediates[775] = new Pattern(new long[]{0L, 63488L, 0L, 0L}, new long[]{0L, 61440L, 0L, 0L}, 116);
            immediates[776] = new Pattern(new long[]{0L, 0L, 0xf800000000000000L, 0L},
                    new long[]{0L, 0L, 0xf000000000000000L, 0L}, 132);
            immediates[777] =
                    new Pattern(new long[]{0L, 0L, 0xf80000000000L, 0L}, new long[]{0L, 0L, 0xf00000000000L, 0L}, 148);
            immediates[778] =
                    new Pattern(new long[]{0L, 0L, 4160749568L, 0L}, new long[]{0L, 0L, 4026531840L, 0L}, 164);
            immediates[779] = new Pattern(new long[]{0L, 0L, 63488L, 0L}, new long[]{0L, 0L, 61440L, 0L}, 180);
            immediates[780] = new Pattern(new long[]{0L, 0L, 0L, 0xf800000000000000L},
                    new long[]{0L, 0L, 0L, 0xf000000000000000L}, 196);
            immediates[781] =
                    new Pattern(new long[]{0L, 0L, 0L, 0xf80000000000L}, new long[]{0L, 0L, 0L, 0xf00000000000L}, 212);
            immediates[782] =
                    new Pattern(new long[]{0L, 0L, 0L, 4160749568L}, new long[]{0L, 0L, 0L, 4026531840L}, 228);
            immediates[783] = new Pattern(new long[]{0L, 0L, 0L, 63488L}, new long[]{0L, 0L, 0L, 61440L}, 244);
            immediates[784] = new Pattern(new long[]{0x7c00000000000000L, 0L, 0L, 0L},
                    new long[]{0x7800000000000000L, 0L, 0L, 0L}, 5);
            immediates[785] =
                    new Pattern(new long[]{0x7c0000000000L, 0L, 0L, 0L}, new long[]{0x780000000000L, 0L, 0L, 0L}, 21);
            immediates[786] = new Pattern(new long[]{2080374784L, 0L, 0L, 0L}, new long[]{2013265920L, 0L, 0L, 0L}, 37);
            immediates[787] = new Pattern(new long[]{31744L, 0L, 0L, 0L}, new long[]{30720L, 0L, 0L, 0L}, 53);
            immediates[788] = new Pattern(new long[]{0L, 0x7c00000000000000L, 0L, 0L},
                    new long[]{0L, 0x7800000000000000L, 0L, 0L}, 69);
            immediates[789] =
                    new Pattern(new long[]{0L, 0x7c0000000000L, 0L, 0L}, new long[]{0L, 0x780000000000L, 0L, 0L}, 85);
            immediates[790] =
                    new Pattern(new long[]{0L, 2080374784L, 0L, 0L}, new long[]{0L, 2013265920L, 0L, 0L}, 101);
            immediates[791] = new Pattern(new long[]{0L, 31744L, 0L, 0L}, new long[]{0L, 30720L, 0L, 0L}, 117);
            immediates[792] = new Pattern(new long[]{0L, 0L, 0x7c00000000000000L, 0L},
                    new long[]{0L, 0L, 0x7800000000000000L, 0L}, 133);
            immediates[793] =
                    new Pattern(new long[]{0L, 0L, 0x7c0000000000L, 0L}, new long[]{0L, 0L, 0x780000000000L, 0L}, 149);
            immediates[794] =
                    new Pattern(new long[]{0L, 0L, 2080374784L, 0L}, new long[]{0L, 0L, 2013265920L, 0L}, 165);
            immediates[795] = new Pattern(new long[]{0L, 0L, 31744L, 0L}, new long[]{0L, 0L, 30720L, 0L}, 181);
            immediates[796] = new Pattern(new long[]{0L, 0L, 0L, 0x7c00000000000000L},
                    new long[]{0L, 0L, 0L, 0x7800000000000000L}, 197);
            immediates[797] =
                    new Pattern(new long[]{0L, 0L, 0L, 0x7c0000000000L}, new long[]{0L, 0L, 0L, 0x780000000000L}, 213);
            immediates[798] =
                    new Pattern(new long[]{0L, 0L, 0L, 2080374784L}, new long[]{0L, 0L, 0L, 2013265920L}, 229);
            immediates[799] = new Pattern(new long[]{0L, 0L, 0L, 31744L}, new long[]{0L, 0L, 0L, 30720L}, 245);
            immediates[800] = new Pattern(new long[]{0x3e00000000000000L, 0L, 0L, 0L},
                    new long[]{0x3c00000000000000L, 0L, 0L, 0L}, 6);
            immediates[801] =
                    new Pattern(new long[]{68169720922112L, 0L, 0L, 0L}, new long[]{65970697666560L, 0L, 0L, 0L}, 22);
            immediates[802] = new Pattern(new long[]{1040187392L, 0L, 0L, 0L}, new long[]{1006632960L, 0L, 0L, 0L}, 38);
            immediates[803] = new Pattern(new long[]{15872L, 0L, 0L, 0L}, new long[]{15360L, 0L, 0L, 0L}, 54);
            immediates[804] = new Pattern(new long[]{0L, 0x3e00000000000000L, 0L, 0L},
                    new long[]{0L, 0x3c00000000000000L, 0L, 0L}, 70);
            immediates[805] =
                    new Pattern(new long[]{0L, 68169720922112L, 0L, 0L}, new long[]{0L, 65970697666560L, 0L, 0L}, 86);
            immediates[806] =
                    new Pattern(new long[]{0L, 1040187392L, 0L, 0L}, new long[]{0L, 1006632960L, 0L, 0L}, 102);
            immediates[807] = new Pattern(new long[]{0L, 15872L, 0L, 0L}, new long[]{0L, 15360L, 0L, 0L}, 118);
            immediates[808] = new Pattern(new long[]{0L, 0L, 0x3e00000000000000L, 0L},
                    new long[]{0L, 0L, 0x3c00000000000000L, 0L}, 134);
            immediates[809] =
                    new Pattern(new long[]{0L, 0L, 68169720922112L, 0L}, new long[]{0L, 0L, 65970697666560L, 0L}, 150);
            immediates[810] =
                    new Pattern(new long[]{0L, 0L, 1040187392L, 0L}, new long[]{0L, 0L, 1006632960L, 0L}, 166);
            immediates[811] = new Pattern(new long[]{0L, 0L, 15872L, 0L}, new long[]{0L, 0L, 15360L, 0L}, 182);
            immediates[812] = new Pattern(new long[]{0L, 0L, 0L, 0x3e00000000000000L},
                    new long[]{0L, 0L, 0L, 0x3c00000000000000L}, 198);
            immediates[813] =
                    new Pattern(new long[]{0L, 0L, 0L, 68169720922112L}, new long[]{0L, 0L, 0L, 65970697666560L}, 214);
            immediates[814] =
                    new Pattern(new long[]{0L, 0L, 0L, 1040187392L}, new long[]{0L, 0L, 0L, 1006632960L}, 230);
            immediates[815] = new Pattern(new long[]{0L, 0L, 0L, 15872L}, new long[]{0L, 0L, 0L, 15360L}, 246);
            immediates[816] = new Pattern(new long[]{0x1f00000000000000L, 0L, 0L, 0L},
                    new long[]{0x1e00000000000000L, 0L, 0L, 0L}, 7);
            immediates[817] =
                    new Pattern(new long[]{34084860461056L, 0L, 0L, 0L}, new long[]{32985348833280L, 0L, 0L, 0L}, 23);
            immediates[818] = new Pattern(new long[]{520093696L, 0L, 0L, 0L}, new long[]{503316480L, 0L, 0L, 0L}, 39);
            immediates[819] = new Pattern(new long[]{7936L, 0L, 0L, 0L}, new long[]{7680L, 0L, 0L, 0L}, 55);
            immediates[820] = new Pattern(new long[]{0L, 0x1f00000000000000L, 0L, 0L},
                    new long[]{0L, 0x1e00000000000000L, 0L, 0L}, 71);
            immediates[821] =
                    new Pattern(new long[]{0L, 34084860461056L, 0L, 0L}, new long[]{0L, 32985348833280L, 0L, 0L}, 87);
            immediates[822] = new Pattern(new long[]{0L, 520093696L, 0L, 0L}, new long[]{0L, 503316480L, 0L, 0L}, 103);
            immediates[823] = new Pattern(new long[]{0L, 7936L, 0L, 0L}, new long[]{0L, 7680L, 0L, 0L}, 119);
            immediates[824] = new Pattern(new long[]{0L, 0L, 0x1f00000000000000L, 0L},
                    new long[]{0L, 0L, 0x1e00000000000000L, 0L}, 135);
            immediates[825] =
                    new Pattern(new long[]{0L, 0L, 34084860461056L, 0L}, new long[]{0L, 0L, 32985348833280L, 0L}, 151);
            immediates[826] = new Pattern(new long[]{0L, 0L, 520093696L, 0L}, new long[]{0L, 0L, 503316480L, 0L}, 167);
            immediates[827] = new Pattern(new long[]{0L, 0L, 7936L, 0L}, new long[]{0L, 0L, 7680L, 0L}, 183);
            immediates[828] = new Pattern(new long[]{0L, 0L, 0L, 0x1f00000000000000L},
                    new long[]{0L, 0L, 0L, 0x1e00000000000000L}, 199);
            immediates[829] =
                    new Pattern(new long[]{0L, 0L, 0L, 34084860461056L}, new long[]{0L, 0L, 0L, 32985348833280L}, 215);
            immediates[830] = new Pattern(new long[]{0L, 0L, 0L, 520093696L}, new long[]{0L, 0L, 0L, 503316480L}, 231);
            immediates[831] = new Pattern(new long[]{0L, 0L, 0L, 7936L}, new long[]{0L, 0L, 0L, 7680L}, 247);
            immediates[832] =
                    new Pattern(new long[]{0xf80000000000000L, 0L, 0L, 0L}, new long[]{0xf00000000000000L, 0L, 0L, 0L},
                            8);
            immediates[833] =
                    new Pattern(new long[]{0xf8000000000L, 0L, 0L, 0L}, new long[]{0xf0000000000L, 0L, 0L, 0L}, 24);
            immediates[834] = new Pattern(new long[]{260046848L, 0L, 0L, 0L}, new long[]{251658240L, 0L, 0L, 0L}, 40);
            immediates[835] = new Pattern(new long[]{3968L, 0L, 0L, 0L}, new long[]{3840L, 0L, 0L, 0L}, 56);
            immediates[836] =
                    new Pattern(new long[]{0L, 0xf80000000000000L, 0L, 0L}, new long[]{0L, 0xf00000000000000L, 0L, 0L},
                            72);
            immediates[837] =
                    new Pattern(new long[]{0L, 0xf8000000000L, 0L, 0L}, new long[]{0L, 0xf0000000000L, 0L, 0L}, 88);
            immediates[838] = new Pattern(new long[]{0L, 260046848L, 0L, 0L}, new long[]{0L, 251658240L, 0L, 0L}, 104);
            immediates[839] = new Pattern(new long[]{0L, 3968L, 0L, 0L}, new long[]{0L, 3840L, 0L, 0L}, 120);
            immediates[840] =
                    new Pattern(new long[]{0L, 0L, 0xf80000000000000L, 0L}, new long[]{0L, 0L, 0xf00000000000000L, 0L},
                            136);
            immediates[841] =
                    new Pattern(new long[]{0L, 0L, 0xf8000000000L, 0L}, new long[]{0L, 0L, 0xf0000000000L, 0L}, 152);
            immediates[842] = new Pattern(new long[]{0L, 0L, 260046848L, 0L}, new long[]{0L, 0L, 251658240L, 0L}, 168);
            immediates[843] = new Pattern(new long[]{0L, 0L, 3968L, 0L}, new long[]{0L, 0L, 3840L, 0L}, 184);
            immediates[844] =
                    new Pattern(new long[]{0L, 0L, 0L, 0xf80000000000000L}, new long[]{0L, 0L, 0L, 0xf00000000000000L},
                            200);
            immediates[845] =
                    new Pattern(new long[]{0L, 0L, 0L, 0xf8000000000L}, new long[]{0L, 0L, 0L, 0xf0000000000L}, 216);
            immediates[846] = new Pattern(new long[]{0L, 0L, 0L, 260046848L}, new long[]{0L, 0L, 0L, 251658240L}, 232);
            immediates[847] = new Pattern(new long[]{0L, 0L, 0L, 3968L}, new long[]{0L, 0L, 0L, 3840L}, 248);
            immediates[848] =
                    new Pattern(new long[]{0x7c0000000000000L, 0L, 0L, 0L}, new long[]{0x780000000000000L, 0L, 0L, 0L},
                            9);
            immediates[849] =
                    new Pattern(new long[]{8521215115264L, 0L, 0L, 0L}, new long[]{8246337208320L, 0L, 0L, 0L}, 25);
            immediates[850] = new Pattern(new long[]{130023424L, 0L, 0L, 0L}, new long[]{125829120L, 0L, 0L, 0L}, 41);
            immediates[851] = new Pattern(new long[]{1984L, 0L, 0L, 0L}, new long[]{1920L, 0L, 0L, 0L}, 57);
            immediates[852] =
                    new Pattern(new long[]{0L, 0x7c0000000000000L, 0L, 0L}, new long[]{0L, 0x780000000000000L, 0L, 0L},
                            73);
            immediates[853] =
                    new Pattern(new long[]{0L, 8521215115264L, 0L, 0L}, new long[]{0L, 8246337208320L, 0L, 0L}, 89);
            immediates[854] = new Pattern(new long[]{0L, 130023424L, 0L, 0L}, new long[]{0L, 125829120L, 0L, 0L}, 105);
            immediates[855] = new Pattern(new long[]{0L, 1984L, 0L, 0L}, new long[]{0L, 1920L, 0L, 0L}, 121);
            immediates[856] =
                    new Pattern(new long[]{0L, 0L, 0x7c0000000000000L, 0L}, new long[]{0L, 0L, 0x780000000000000L, 0L},
                            137);
            immediates[857] =
                    new Pattern(new long[]{0L, 0L, 8521215115264L, 0L}, new long[]{0L, 0L, 8246337208320L, 0L}, 153);
            immediates[858] = new Pattern(new long[]{0L, 0L, 130023424L, 0L}, new long[]{0L, 0L, 125829120L, 0L}, 169);
            immediates[859] = new Pattern(new long[]{0L, 0L, 1984L, 0L}, new long[]{0L, 0L, 1920L, 0L}, 185);
            immediates[860] =
                    new Pattern(new long[]{0L, 0L, 0L, 0x7c0000000000000L}, new long[]{0L, 0L, 0L, 0x780000000000000L},
                            201);
            immediates[861] =
                    new Pattern(new long[]{0L, 0L, 0L, 8521215115264L}, new long[]{0L, 0L, 0L, 8246337208320L}, 217);
            immediates[862] = new Pattern(new long[]{0L, 0L, 0L, 130023424L}, new long[]{0L, 0L, 0L, 125829120L}, 233);
            immediates[863] = new Pattern(new long[]{0L, 0L, 0L, 1984L}, new long[]{0L, 0L, 0L, 1920L}, 249);
            immediates[864] =
                    new Pattern(new long[]{0x3e0000000000000L, 0L, 0L, 0L}, new long[]{0x3c0000000000000L, 0L, 0L, 0L},
                            10);
            immediates[865] =
                    new Pattern(new long[]{4260607557632L, 0L, 0L, 0L}, new long[]{4123168604160L, 0L, 0L, 0L}, 26);
            immediates[866] = new Pattern(new long[]{65011712L, 0L, 0L, 0L}, new long[]{62914560L, 0L, 0L, 0L}, 42);
            immediates[867] = new Pattern(new long[]{992L, 0L, 0L, 0L}, new long[]{960L, 0L, 0L, 0L}, 58);
            immediates[868] =
                    new Pattern(new long[]{0L, 0x3e0000000000000L, 0L, 0L}, new long[]{0L, 0x3c0000000000000L, 0L, 0L},
                            74);
            immediates[869] =
                    new Pattern(new long[]{0L, 4260607557632L, 0L, 0L}, new long[]{0L, 4123168604160L, 0L, 0L}, 90);
            immediates[870] = new Pattern(new long[]{0L, 65011712L, 0L, 0L}, new long[]{0L, 62914560L, 0L, 0L}, 106);
            immediates[871] = new Pattern(new long[]{0L, 992L, 0L, 0L}, new long[]{0L, 960L, 0L, 0L}, 122);
            immediates[872] =
                    new Pattern(new long[]{0L, 0L, 0x3e0000000000000L, 0L}, new long[]{0L, 0L, 0x3c0000000000000L, 0L},
                            138);
            immediates[873] =
                    new Pattern(new long[]{0L, 0L, 4260607557632L, 0L}, new long[]{0L, 0L, 4123168604160L, 0L}, 154);
            immediates[874] = new Pattern(new long[]{0L, 0L, 65011712L, 0L}, new long[]{0L, 0L, 62914560L, 0L}, 170);
            immediates[875] = new Pattern(new long[]{0L, 0L, 992L, 0L}, new long[]{0L, 0L, 960L, 0L}, 186);
            immediates[876] =
                    new Pattern(new long[]{0L, 0L, 0L, 0x3e0000000000000L}, new long[]{0L, 0L, 0L, 0x3c0000000000000L},
                            202);
            immediates[877] =
                    new Pattern(new long[]{0L, 0L, 0L, 4260607557632L}, new long[]{0L, 0L, 0L, 4123168604160L}, 218);
            immediates[878] = new Pattern(new long[]{0L, 0L, 0L, 65011712L}, new long[]{0L, 0L, 0L, 62914560L}, 234);
            immediates[879] = new Pattern(new long[]{0L, 0L, 0L, 992L}, new long[]{0L, 0L, 0L, 960L}, 250);
            immediates[880] =
                    new Pattern(new long[]{0x1f0000000000000L, 0L, 0L, 0L}, new long[]{0x1e0000000000000L, 0L, 0L, 0L},
                            11);
            immediates[881] =
                    new Pattern(new long[]{2130303778816L, 0L, 0L, 0L}, new long[]{2061584302080L, 0L, 0L, 0L}, 27);
            immediates[882] = new Pattern(new long[]{32505856L, 0L, 0L, 0L}, new long[]{31457280L, 0L, 0L, 0L}, 43);
            immediates[883] = new Pattern(new long[]{496L, 0L, 0L, 0L}, new long[]{480L, 0L, 0L, 0L}, 59);
            immediates[884] =
                    new Pattern(new long[]{0L, 0x1f0000000000000L, 0L, 0L}, new long[]{0L, 0x1e0000000000000L, 0L, 0L},
                            75);
            immediates[885] =
                    new Pattern(new long[]{0L, 2130303778816L, 0L, 0L}, new long[]{0L, 2061584302080L, 0L, 0L}, 91);
            immediates[886] = new Pattern(new long[]{0L, 32505856L, 0L, 0L}, new long[]{0L, 31457280L, 0L, 0L}, 107);
            immediates[887] = new Pattern(new long[]{0L, 496L, 0L, 0L}, new long[]{0L, 480L, 0L, 0L}, 123);
            immediates[888] =
                    new Pattern(new long[]{0L, 0L, 0x1f0000000000000L, 0L}, new long[]{0L, 0L, 0x1e0000000000000L, 0L},
                            139);
            immediates[889] =
                    new Pattern(new long[]{0L, 0L, 2130303778816L, 0L}, new long[]{0L, 0L, 2061584302080L, 0L}, 155);
            immediates[890] = new Pattern(new long[]{0L, 0L, 32505856L, 0L}, new long[]{0L, 0L, 31457280L, 0L}, 171);
            immediates[891] = new Pattern(new long[]{0L, 0L, 496L, 0L}, new long[]{0L, 0L, 480L, 0L}, 187);
            immediates[892] =
                    new Pattern(new long[]{0L, 0L, 0L, 0x1f0000000000000L}, new long[]{0L, 0L, 0L, 0x1e0000000000000L},
                            203);
            immediates[893] =
                    new Pattern(new long[]{0L, 0L, 0L, 2130303778816L}, new long[]{0L, 0L, 0L, 2061584302080L}, 219);
            immediates[894] = new Pattern(new long[]{0L, 0L, 0L, 32505856L}, new long[]{0L, 0L, 0L, 31457280L}, 235);
            immediates[895] = new Pattern(new long[]{0L, 0L, 0L, 496L}, new long[]{0L, 0L, 0L, 480L}, 251);
            immediates[896] =
                    new Pattern(new long[]{0xf8000000000000L, 0L, 0L, 0L}, new long[]{0xf0000000000000L, 0L, 0L, 0L},
                            12);
            immediates[897] =
                    new Pattern(new long[]{0xf800000000L, 0L, 0L, 0L}, new long[]{0xf000000000L, 0L, 0L, 0L}, 28);
            immediates[898] = new Pattern(new long[]{16252928L, 0L, 0L, 0L}, new long[]{15728640L, 0L, 0L, 0L}, 44);
            immediates[899] = new Pattern(new long[]{248L, 0L, 0L, 0L}, new long[]{240L, 0L, 0L, 0L}, 60);
            immediates[900] =
                    new Pattern(new long[]{0L, 0xf8000000000000L, 0L, 0L}, new long[]{0L, 0xf0000000000000L, 0L, 0L},
                            76);
            immediates[901] =
                    new Pattern(new long[]{0L, 0xf800000000L, 0L, 0L}, new long[]{0L, 0xf000000000L, 0L, 0L}, 92);
            immediates[902] = new Pattern(new long[]{0L, 16252928L, 0L, 0L}, new long[]{0L, 15728640L, 0L, 0L}, 108);
            immediates[903] = new Pattern(new long[]{0L, 248L, 0L, 0L}, new long[]{0L, 240L, 0L, 0L}, 124);
            immediates[904] =
                    new Pattern(new long[]{0L, 0L, 0xf8000000000000L, 0L}, new long[]{0L, 0L, 0xf0000000000000L, 0L},
                            140);
            immediates[905] =
                    new Pattern(new long[]{0L, 0L, 0xf800000000L, 0L}, new long[]{0L, 0L, 0xf000000000L, 0L}, 156);
            immediates[906] = new Pattern(new long[]{0L, 0L, 16252928L, 0L}, new long[]{0L, 0L, 15728640L, 0L}, 172);
            immediates[907] = new Pattern(new long[]{0L, 0L, 248L, 0L}, new long[]{0L, 0L, 240L, 0L}, 188);
            immediates[908] =
                    new Pattern(new long[]{0L, 0L, 0L, 0xf8000000000000L}, new long[]{0L, 0L, 0L, 0xf0000000000000L},
                            204);
            immediates[909] =
                    new Pattern(new long[]{0L, 0L, 0L, 0xf800000000L}, new long[]{0L, 0L, 0L, 0xf000000000L}, 220);
            immediates[910] = new Pattern(new long[]{0L, 0L, 0L, 16252928L}, new long[]{0L, 0L, 0L, 15728640L}, 236);
            immediates[911] = new Pattern(new long[]{0L, 0L, 0L, 248L}, new long[]{0L, 0L, 0L, 240L}, 252);
            immediates[912] =
                    new Pattern(new long[]{0x7c000000000000L, 0L, 0L, 0L}, new long[]{0x78000000000000L, 0L, 0L, 0L},
                            13);
            immediates[913] =
                    new Pattern(new long[]{532575944704L, 0L, 0L, 0L}, new long[]{515396075520L, 0L, 0L, 0L}, 29);
            immediates[914] = new Pattern(new long[]{8126464L, 0L, 0L, 0L}, new long[]{7864320L, 0L, 0L, 0L}, 45);
            immediates[915] = new Pattern(new long[]{124L, 0L, 0L, 0L}, new long[]{120L, 0L, 0L, 0L}, 61);
            immediates[916] =
                    new Pattern(new long[]{0L, 0x7c000000000000L, 0L, 0L}, new long[]{0L, 0x78000000000000L, 0L, 0L},
                            77);
            immediates[917] =
                    new Pattern(new long[]{0L, 532575944704L, 0L, 0L}, new long[]{0L, 515396075520L, 0L, 0L}, 93);
            immediates[918] = new Pattern(new long[]{0L, 8126464L, 0L, 0L}, new long[]{0L, 7864320L, 0L, 0L}, 109);
            immediates[919] = new Pattern(new long[]{0L, 124L, 0L, 0L}, new long[]{0L, 120L, 0L, 0L}, 125);
            immediates[920] =
                    new Pattern(new long[]{0L, 0L, 0x7c000000000000L, 0L}, new long[]{0L, 0L, 0x78000000000000L, 0L},
                            141);
            immediates[921] =
                    new Pattern(new long[]{0L, 0L, 532575944704L, 0L}, new long[]{0L, 0L, 515396075520L, 0L}, 157);
            immediates[922] = new Pattern(new long[]{0L, 0L, 8126464L, 0L}, new long[]{0L, 0L, 7864320L, 0L}, 173);
            immediates[923] = new Pattern(new long[]{0L, 0L, 124L, 0L}, new long[]{0L, 0L, 120L, 0L}, 189);
            immediates[924] =
                    new Pattern(new long[]{0L, 0L, 0L, 0x7c000000000000L}, new long[]{0L, 0L, 0L, 0x78000000000000L},
                            205);
            immediates[925] =
                    new Pattern(new long[]{0L, 0L, 0L, 532575944704L}, new long[]{0L, 0L, 0L, 515396075520L}, 221);
            immediates[926] = new Pattern(new long[]{0L, 0L, 0L, 8126464L}, new long[]{0L, 0L, 0L, 7864320L}, 237);
            immediates[927] = new Pattern(new long[]{0L, 0L, 0L, 124L}, new long[]{0L, 0L, 0L, 120L}, 253);
            immediates[928] =
                    new Pattern(new long[]{0x3e000000000000L, 0L, 0L, 0L}, new long[]{0x3c000000000000L, 0L, 0L, 0L},
                            14);
            immediates[929] =
                    new Pattern(new long[]{266287972352L, 0L, 0L, 0L}, new long[]{257698037760L, 0L, 0L, 0L}, 30);
            immediates[930] = new Pattern(new long[]{4063232L, 0L, 0L, 0L}, new long[]{3932160L, 0L, 0L, 0L}, 46);
            immediates[931] = new Pattern(new long[]{62L, 0L, 0L, 0L}, new long[]{60L, 0L, 0L, 0L}, 62);
            immediates[932] =
                    new Pattern(new long[]{0L, 0x3e000000000000L, 0L, 0L}, new long[]{0L, 0x3c000000000000L, 0L, 0L},
                            78);
            immediates[933] =
                    new Pattern(new long[]{0L, 266287972352L, 0L, 0L}, new long[]{0L, 257698037760L, 0L, 0L}, 94);
            immediates[934] = new Pattern(new long[]{0L, 4063232L, 0L, 0L}, new long[]{0L, 3932160L, 0L, 0L}, 110);
            immediates[935] = new Pattern(new long[]{0L, 62L, 0L, 0L}, new long[]{0L, 60L, 0L, 0L}, 126);
            immediates[936] =
                    new Pattern(new long[]{0L, 0L, 0x3e000000000000L, 0L}, new long[]{0L, 0L, 0x3c000000000000L, 0L},
                            142);
            immediates[937] =
                    new Pattern(new long[]{0L, 0L, 266287972352L, 0L}, new long[]{0L, 0L, 257698037760L, 0L}, 158);
            immediates[938] = new Pattern(new long[]{0L, 0L, 4063232L, 0L}, new long[]{0L, 0L, 3932160L, 0L}, 174);
            immediates[939] = new Pattern(new long[]{0L, 0L, 62L, 0L}, new long[]{0L, 0L, 60L, 0L}, 190);
            immediates[940] =
                    new Pattern(new long[]{0L, 0L, 0L, 0x3e000000000000L}, new long[]{0L, 0L, 0L, 0x3c000000000000L},
                            206);
            immediates[941] =
                    new Pattern(new long[]{0L, 0L, 0L, 266287972352L}, new long[]{0L, 0L, 0L, 257698037760L}, 222);
            immediates[942] = new Pattern(new long[]{0L, 0L, 0L, 4063232L}, new long[]{0L, 0L, 0L, 3932160L}, 238);
            immediates[943] = new Pattern(new long[]{0L, 0L, 0L, 62L}, new long[]{0L, 0L, 0L, 60L}, 254);
            immediates[944] =
                    new Pattern(new long[]{8725724278030336L, 0L, 0L, 0L}, new long[]{8444249301319680L, 0L, 0L, 0L},
                            15);
            immediates[945] =
                    new Pattern(new long[]{133143986176L, 0L, 0L, 0L}, new long[]{128849018880L, 0L, 0L, 0L}, 31);
            immediates[946] = new Pattern(new long[]{2031616L, 0L, 0L, 0L}, new long[]{1966080L, 0L, 0L, 0L}, 47);
            immediates[947] = new Pattern(new long[]{31L, 0L, 0L, 0L}, new long[]{30L, 0L, 0L, 0L}, 63);
            immediates[948] =
                    new Pattern(new long[]{0L, 8725724278030336L, 0L, 0L}, new long[]{0L, 8444249301319680L, 0L, 0L},
                            79);
            immediates[949] =
                    new Pattern(new long[]{0L, 133143986176L, 0L, 0L}, new long[]{0L, 128849018880L, 0L, 0L}, 95);
            immediates[950] = new Pattern(new long[]{0L, 2031616L, 0L, 0L}, new long[]{0L, 1966080L, 0L, 0L}, 111);
            immediates[951] = new Pattern(new long[]{0L, 31L, 0L, 0L}, new long[]{0L, 30L, 0L, 0L}, 127);
            immediates[952] =
                    new Pattern(new long[]{0L, 0L, 8725724278030336L, 0L}, new long[]{0L, 0L, 8444249301319680L, 0L},
                            143);
            immediates[953] =
                    new Pattern(new long[]{0L, 0L, 133143986176L, 0L}, new long[]{0L, 0L, 128849018880L, 0L}, 159);
            immediates[954] = new Pattern(new long[]{0L, 0L, 2031616L, 0L}, new long[]{0L, 0L, 1966080L, 0L}, 175);
            immediates[955] = new Pattern(new long[]{0L, 0L, 31L, 0L}, new long[]{0L, 0L, 30L, 0L}, 191);
            immediates[956] =
                    new Pattern(new long[]{0L, 0L, 0L, 8725724278030336L}, new long[]{0L, 0L, 0L, 8444249301319680L},
                            207);
            immediates[957] =
                    new Pattern(new long[]{0L, 0L, 0L, 133143986176L}, new long[]{0L, 0L, 0L, 128849018880L}, 223);
            immediates[958] = new Pattern(new long[]{0L, 0L, 0L, 2031616L}, new long[]{0L, 0L, 0L, 1966080L}, 239);
            immediates[959] = new Pattern(new long[]{0L, 0L, 0L, 31L}, new long[]{0L, 0L, 0L, 30L}, 255);
            immediates[960] = new Pattern(new long[]{0x8000800080008000L, 0x8000000000000000L, 0L, 0L},
                    new long[]{0x800080008000L, 0x8000000000000000L, 0L, 0L}, 0);
            immediates[961] = new Pattern(new long[]{0x800080008000L, 0x8000800000000000L, 0L, 0L},
                    new long[]{2147516416L, 0x8000800000000000L, 0L, 0L}, 16);
            immediates[962] = new Pattern(new long[]{2147516416L, 0x8000800080000000L, 0L, 0L},
                    new long[]{32768L, 0x8000800080000000L, 0L, 0L}, 32);
            immediates[963] = new Pattern(new long[]{32768L, 0x8000800080008000L, 0L, 0L},
                    new long[]{0L, 0x8000800080008000L, 0L, 0L}, 48);
            immediates[964] = new Pattern(new long[]{0L, 0x8000800080008000L, 0x8000000000000000L, 0L},
                    new long[]{0L, 0x800080008000L, 0x8000000000000000L, 0L}, 64);
            immediates[965] = new Pattern(new long[]{0L, 0x800080008000L, 0x8000800000000000L, 0L},
                    new long[]{0L, 2147516416L, 0x8000800000000000L, 0L}, 80);
            immediates[966] = new Pattern(new long[]{0L, 2147516416L, 0x8000800080000000L, 0L},
                    new long[]{0L, 32768L, 0x8000800080000000L, 0L}, 96);
            immediates[967] = new Pattern(new long[]{0L, 32768L, 0x8000800080008000L, 0L},
                    new long[]{0L, 0L, 0x8000800080008000L, 0L}, 112);
            immediates[968] = new Pattern(new long[]{0L, 0L, 0x8000800080008000L, 0x8000000000000000L},
                    new long[]{0L, 0L, 0x800080008000L, 0x8000000000000000L}, 128);
            immediates[969] = new Pattern(new long[]{0L, 0L, 0x800080008000L, 0x8000800000000000L},
                    new long[]{0L, 0L, 2147516416L, 0x8000800000000000L}, 144);
            immediates[970] = new Pattern(new long[]{0L, 0L, 2147516416L, 0x8000800080000000L},
                    new long[]{0L, 0L, 32768L, 0x8000800080000000L}, 160);
            immediates[971] = new Pattern(new long[]{0L, 0L, 32768L, 0x8000800080008000L},
                    new long[]{0L, 0L, 0L, 0x8000800080008000L}, 176);
            immediates[972] = new Pattern(new long[]{0x4000400040004000L, 0x4000000000000000L, 0L, 0L},
                    new long[]{70369817935872L, 0x4000000000000000L, 0L, 0L}, 1);
            immediates[973] = new Pattern(new long[]{70369817935872L, 0x4000400000000000L, 0L, 0L},
                    new long[]{1073758208L, 0x4000400000000000L, 0L, 0L}, 17);
            immediates[974] = new Pattern(new long[]{1073758208L, 0x4000400040000000L, 0L, 0L},
                    new long[]{16384L, 0x4000400040000000L, 0L, 0L}, 33);
            immediates[975] = new Pattern(new long[]{16384L, 0x4000400040004000L, 0L, 0L},
                    new long[]{0L, 0x4000400040004000L, 0L, 0L}, 49);
            immediates[976] = new Pattern(new long[]{0L, 0x4000400040004000L, 0x4000000000000000L, 0L},
                    new long[]{0L, 70369817935872L, 0x4000000000000000L, 0L}, 65);
            immediates[977] = new Pattern(new long[]{0L, 70369817935872L, 0x4000400000000000L, 0L},
                    new long[]{0L, 1073758208L, 0x4000400000000000L, 0L}, 81);
            immediates[978] = new Pattern(new long[]{0L, 1073758208L, 0x4000400040000000L, 0L},
                    new long[]{0L, 16384L, 0x4000400040000000L, 0L}, 97);
            immediates[979] = new Pattern(new long[]{0L, 16384L, 0x4000400040004000L, 0L},
                    new long[]{0L, 0L, 0x4000400040004000L, 0L}, 113);
            immediates[980] = new Pattern(new long[]{0L, 0L, 0x4000400040004000L, 0x4000000000000000L},
                    new long[]{0L, 0L, 70369817935872L, 0x4000000000000000L}, 129);
            immediates[981] = new Pattern(new long[]{0L, 0L, 70369817935872L, 0x4000400000000000L},
                    new long[]{0L, 0L, 1073758208L, 0x4000400000000000L}, 145);
            immediates[982] = new Pattern(new long[]{0L, 0L, 1073758208L, 0x4000400040000000L},
                    new long[]{0L, 0L, 16384L, 0x4000400040000000L}, 161);
            immediates[983] = new Pattern(new long[]{0L, 0L, 16384L, 0x4000400040004000L},
                    new long[]{0L, 0L, 0L, 0x4000400040004000L}, 177);
            immediates[984] = new Pattern(new long[]{0x2000200020002000L, 0x2000000000000000L, 0L, 0L},
                    new long[]{35184908967936L, 0x2000000000000000L, 0L, 0L}, 2);
            immediates[985] = new Pattern(new long[]{35184908967936L, 0x2000200000000000L, 0L, 0L},
                    new long[]{536879104L, 0x2000200000000000L, 0L, 0L}, 18);
            immediates[986] = new Pattern(new long[]{536879104L, 0x2000200020000000L, 0L, 0L},
                    new long[]{8192L, 0x2000200020000000L, 0L, 0L}, 34);
            immediates[987] = new Pattern(new long[]{8192L, 0x2000200020002000L, 0L, 0L},
                    new long[]{0L, 0x2000200020002000L, 0L, 0L}, 50);
            immediates[988] = new Pattern(new long[]{0L, 0x2000200020002000L, 0x2000000000000000L, 0L},
                    new long[]{0L, 35184908967936L, 0x2000000000000000L, 0L}, 66);
            immediates[989] = new Pattern(new long[]{0L, 35184908967936L, 0x2000200000000000L, 0L},
                    new long[]{0L, 536879104L, 0x2000200000000000L, 0L}, 82);
            immediates[990] = new Pattern(new long[]{0L, 536879104L, 0x2000200020000000L, 0L},
                    new long[]{0L, 8192L, 0x2000200020000000L, 0L}, 98);
            immediates[991] = new Pattern(new long[]{0L, 8192L, 0x2000200020002000L, 0L},
                    new long[]{0L, 0L, 0x2000200020002000L, 0L}, 114);
            immediates[992] = new Pattern(new long[]{0L, 0L, 0x2000200020002000L, 0x2000000000000000L},
                    new long[]{0L, 0L, 35184908967936L, 0x2000000000000000L}, 130);
            immediates[993] = new Pattern(new long[]{0L, 0L, 35184908967936L, 0x2000200000000000L},
                    new long[]{0L, 0L, 536879104L, 0x2000200000000000L}, 146);
            immediates[994] = new Pattern(new long[]{0L, 0L, 536879104L, 0x2000200020000000L},
                    new long[]{0L, 0L, 8192L, 0x2000200020000000L}, 162);
            immediates[995] = new Pattern(new long[]{0L, 0L, 8192L, 0x2000200020002000L},
                    new long[]{0L, 0L, 0L, 0x2000200020002000L}, 178);
            immediates[996] = new Pattern(new long[]{0x1000100010001000L, 0x1000000000000000L, 0L, 0L},
                    new long[]{17592454483968L, 0x1000000000000000L, 0L, 0L}, 3);
            immediates[997] = new Pattern(new long[]{17592454483968L, 0x1000100000000000L, 0L, 0L},
                    new long[]{268439552L, 0x1000100000000000L, 0L, 0L}, 19);
            immediates[998] = new Pattern(new long[]{268439552L, 0x1000100010000000L, 0L, 0L},
                    new long[]{4096L, 0x1000100010000000L, 0L, 0L}, 35);
            immediates[999] = new Pattern(new long[]{4096L, 0x1000100010001000L, 0L, 0L},
                    new long[]{0L, 0x1000100010001000L, 0L, 0L}, 51);
        }

        private static void initImmediates2() {
            immediates[1000] = new Pattern(new long[]{0L, 0x1000100010001000L, 0x1000000000000000L, 0L},
                    new long[]{0L, 17592454483968L, 0x1000000000000000L, 0L}, 67);
            immediates[1001] = new Pattern(new long[]{0L, 17592454483968L, 0x1000100000000000L, 0L},
                    new long[]{0L, 268439552L, 0x1000100000000000L, 0L}, 83);
            immediates[1002] = new Pattern(new long[]{0L, 268439552L, 0x1000100010000000L, 0L},
                    new long[]{0L, 4096L, 0x1000100010000000L, 0L}, 99);
            immediates[1003] = new Pattern(new long[]{0L, 4096L, 0x1000100010001000L, 0L},
                    new long[]{0L, 0L, 0x1000100010001000L, 0L}, 115);
            immediates[1004] = new Pattern(new long[]{0L, 0L, 0x1000100010001000L, 0x1000000000000000L},
                    new long[]{0L, 0L, 17592454483968L, 0x1000000000000000L}, 131);
            immediates[1005] = new Pattern(new long[]{0L, 0L, 17592454483968L, 0x1000100000000000L},
                    new long[]{0L, 0L, 268439552L, 0x1000100000000000L}, 147);
            immediates[1006] = new Pattern(new long[]{0L, 0L, 268439552L, 0x1000100010000000L},
                    new long[]{0L, 0L, 4096L, 0x1000100010000000L}, 163);
            immediates[1007] = new Pattern(new long[]{0L, 0L, 4096L, 0x1000100010001000L},
                    new long[]{0L, 0L, 0L, 0x1000100010001000L}, 179);
            immediates[1008] = new Pattern(new long[]{0x800080008000800L, 0x800000000000000L, 0L, 0L},
                    new long[]{8796227241984L, 0x800000000000000L, 0L, 0L}, 4);
            immediates[1009] = new Pattern(new long[]{8796227241984L, 0x800080000000000L, 0L, 0L},
                    new long[]{134219776L, 0x800080000000000L, 0L, 0L}, 20);
            immediates[1010] = new Pattern(new long[]{134219776L, 0x800080008000000L, 0L, 0L},
                    new long[]{2048L, 0x800080008000000L, 0L, 0L}, 36);
            immediates[1011] = new Pattern(new long[]{2048L, 0x800080008000800L, 0L, 0L},
                    new long[]{0L, 0x800080008000800L, 0L, 0L}, 52);
            immediates[1012] = new Pattern(new long[]{0L, 0x800080008000800L, 0x800000000000000L, 0L},
                    new long[]{0L, 8796227241984L, 0x800000000000000L, 0L}, 68);
            immediates[1013] = new Pattern(new long[]{0L, 8796227241984L, 0x800080000000000L, 0L},
                    new long[]{0L, 134219776L, 0x800080000000000L, 0L}, 84);
            immediates[1014] = new Pattern(new long[]{0L, 134219776L, 0x800080008000000L, 0L},
                    new long[]{0L, 2048L, 0x800080008000000L, 0L}, 100);
            immediates[1015] = new Pattern(new long[]{0L, 2048L, 0x800080008000800L, 0L},
                    new long[]{0L, 0L, 0x800080008000800L, 0L}, 116);
            immediates[1016] = new Pattern(new long[]{0L, 0L, 0x800080008000800L, 0x800000000000000L},
                    new long[]{0L, 0L, 8796227241984L, 0x800000000000000L}, 132);
            immediates[1017] = new Pattern(new long[]{0L, 0L, 8796227241984L, 0x800080000000000L},
                    new long[]{0L, 0L, 134219776L, 0x800080000000000L}, 148);
            immediates[1018] = new Pattern(new long[]{0L, 0L, 134219776L, 0x800080008000000L},
                    new long[]{0L, 0L, 2048L, 0x800080008000000L}, 164);
            immediates[1019] = new Pattern(new long[]{0L, 0L, 2048L, 0x800080008000800L},
                    new long[]{0L, 0L, 0L, 0x800080008000800L}, 180);
            immediates[1020] = new Pattern(new long[]{0x400040004000400L, 0x400000000000000L, 0L, 0L},
                    new long[]{4398113620992L, 0x400000000000000L, 0L, 0L}, 5);
            immediates[1021] = new Pattern(new long[]{4398113620992L, 0x400040000000000L, 0L, 0L},
                    new long[]{67109888L, 0x400040000000000L, 0L, 0L}, 21);
            immediates[1022] = new Pattern(new long[]{67109888L, 0x400040004000000L, 0L, 0L},
                    new long[]{1024L, 0x400040004000000L, 0L, 0L}, 37);
            immediates[1023] = new Pattern(new long[]{1024L, 0x400040004000400L, 0L, 0L},
                    new long[]{0L, 0x400040004000400L, 0L, 0L}, 53);
            immediates[1024] = new Pattern(new long[]{0L, 0x400040004000400L, 0x400000000000000L, 0L},
                    new long[]{0L, 4398113620992L, 0x400000000000000L, 0L}, 69);
            immediates[1025] = new Pattern(new long[]{0L, 4398113620992L, 0x400040000000000L, 0L},
                    new long[]{0L, 67109888L, 0x400040000000000L, 0L}, 85);
            immediates[1026] = new Pattern(new long[]{0L, 67109888L, 0x400040004000000L, 0L},
                    new long[]{0L, 1024L, 0x400040004000000L, 0L}, 101);
            immediates[1027] = new Pattern(new long[]{0L, 1024L, 0x400040004000400L, 0L},
                    new long[]{0L, 0L, 0x400040004000400L, 0L}, 117);
            immediates[1028] = new Pattern(new long[]{0L, 0L, 0x400040004000400L, 0x400000000000000L},
                    new long[]{0L, 0L, 4398113620992L, 0x400000000000000L}, 133);
            immediates[1029] = new Pattern(new long[]{0L, 0L, 4398113620992L, 0x400040000000000L},
                    new long[]{0L, 0L, 67109888L, 0x400040000000000L}, 149);
            immediates[1030] = new Pattern(new long[]{0L, 0L, 67109888L, 0x400040004000000L},
                    new long[]{0L, 0L, 1024L, 0x400040004000000L}, 165);
            immediates[1031] = new Pattern(new long[]{0L, 0L, 1024L, 0x400040004000400L},
                    new long[]{0L, 0L, 0L, 0x400040004000400L}, 181);
            immediates[1032] = new Pattern(new long[]{0x200020002000200L, 0x200000000000000L, 0L, 0L},
                    new long[]{2199056810496L, 0x200000000000000L, 0L, 0L}, 6);
            immediates[1033] = new Pattern(new long[]{2199056810496L, 0x200020000000000L, 0L, 0L},
                    new long[]{33554944L, 0x200020000000000L, 0L, 0L}, 22);
            immediates[1034] = new Pattern(new long[]{33554944L, 0x200020002000000L, 0L, 0L},
                    new long[]{512L, 0x200020002000000L, 0L, 0L}, 38);
            immediates[1035] = new Pattern(new long[]{512L, 0x200020002000200L, 0L, 0L},
                    new long[]{0L, 0x200020002000200L, 0L, 0L}, 54);
            immediates[1036] = new Pattern(new long[]{0L, 0x200020002000200L, 0x200000000000000L, 0L},
                    new long[]{0L, 2199056810496L, 0x200000000000000L, 0L}, 70);
            immediates[1037] = new Pattern(new long[]{0L, 2199056810496L, 0x200020000000000L, 0L},
                    new long[]{0L, 33554944L, 0x200020000000000L, 0L}, 86);
            immediates[1038] = new Pattern(new long[]{0L, 33554944L, 0x200020002000000L, 0L},
                    new long[]{0L, 512L, 0x200020002000000L, 0L}, 102);
            immediates[1039] = new Pattern(new long[]{0L, 512L, 0x200020002000200L, 0L},
                    new long[]{0L, 0L, 0x200020002000200L, 0L}, 118);
            immediates[1040] = new Pattern(new long[]{0L, 0L, 0x200020002000200L, 0x200000000000000L},
                    new long[]{0L, 0L, 2199056810496L, 0x200000000000000L}, 134);
            immediates[1041] = new Pattern(new long[]{0L, 0L, 2199056810496L, 0x200020000000000L},
                    new long[]{0L, 0L, 33554944L, 0x200020000000000L}, 150);
            immediates[1042] = new Pattern(new long[]{0L, 0L, 33554944L, 0x200020002000000L},
                    new long[]{0L, 0L, 512L, 0x200020002000000L}, 166);
            immediates[1043] = new Pattern(new long[]{0L, 0L, 512L, 0x200020002000200L},
                    new long[]{0L, 0L, 0L, 0x200020002000200L}, 182);
            immediates[1044] = new Pattern(new long[]{72058693566333184L, 72057594037927936L, 0L, 0L},
                    new long[]{1099528405248L, 72057594037927936L, 0L, 0L}, 7);
            immediates[1045] = new Pattern(new long[]{1099528405248L, 72058693549555712L, 0L, 0L},
                    new long[]{16777472L, 72058693549555712L, 0L, 0L}, 23);
            immediates[1046] = new Pattern(new long[]{16777472L, 72058693566332928L, 0L, 0L},
                    new long[]{256L, 72058693566332928L, 0L, 0L}, 39);
            immediates[1047] = new Pattern(new long[]{256L, 72058693566333184L, 0L, 0L},
                    new long[]{0L, 72058693566333184L, 0L, 0L}, 55);
            immediates[1048] = new Pattern(new long[]{0L, 72058693566333184L, 72057594037927936L, 0L},
                    new long[]{0L, 1099528405248L, 72057594037927936L, 0L}, 71);
            immediates[1049] = new Pattern(new long[]{0L, 1099528405248L, 72058693549555712L, 0L},
                    new long[]{0L, 16777472L, 72058693549555712L, 0L}, 87);
            immediates[1050] = new Pattern(new long[]{0L, 16777472L, 72058693566332928L, 0L},
                    new long[]{0L, 256L, 72058693566332928L, 0L}, 103);
            immediates[1051] = new Pattern(new long[]{0L, 256L, 72058693566333184L, 0L},
                    new long[]{0L, 0L, 72058693566333184L, 0L}, 119);
            immediates[1052] = new Pattern(new long[]{0L, 0L, 72058693566333184L, 72057594037927936L},
                    new long[]{0L, 0L, 1099528405248L, 72057594037927936L}, 135);
            immediates[1053] = new Pattern(new long[]{0L, 0L, 1099528405248L, 72058693549555712L},
                    new long[]{0L, 0L, 16777472L, 72058693549555712L}, 151);
            immediates[1054] = new Pattern(new long[]{0L, 0L, 16777472L, 72058693566332928L},
                    new long[]{0L, 0L, 256L, 72058693566332928L}, 167);
            immediates[1055] = new Pattern(new long[]{0L, 0L, 256L, 72058693566333184L},
                    new long[]{0L, 0L, 0L, 72058693566333184L}, 183);
            immediates[1056] = new Pattern(new long[]{0x80008000800080L, 0x80000000000000L, 0L, 0L},
                    new long[]{549764202624L, 0x80000000000000L, 0L, 0L}, 8);
            immediates[1057] = new Pattern(new long[]{549764202624L, 0x80008000000000L, 0L, 0L},
                    new long[]{8388736L, 0x80008000000000L, 0L, 0L}, 24);
            immediates[1058] = new Pattern(new long[]{8388736L, 0x80008000800000L, 0L, 0L},
                    new long[]{128L, 0x80008000800000L, 0L, 0L}, 40);
            immediates[1059] =
                    new Pattern(new long[]{128L, 0x80008000800080L, 0L, 0L}, new long[]{0L, 0x80008000800080L, 0L, 0L},
                            56);
            immediates[1060] = new Pattern(new long[]{0L, 0x80008000800080L, 0x80000000000000L, 0L},
                    new long[]{0L, 549764202624L, 0x80000000000000L, 0L}, 72);
            immediates[1061] = new Pattern(new long[]{0L, 549764202624L, 0x80008000000000L, 0L},
                    new long[]{0L, 8388736L, 0x80008000000000L, 0L}, 88);
            immediates[1062] = new Pattern(new long[]{0L, 8388736L, 0x80008000800000L, 0L},
                    new long[]{0L, 128L, 0x80008000800000L, 0L}, 104);
            immediates[1063] =
                    new Pattern(new long[]{0L, 128L, 0x80008000800080L, 0L}, new long[]{0L, 0L, 0x80008000800080L, 0L},
                            120);
            immediates[1064] = new Pattern(new long[]{0L, 0L, 0x80008000800080L, 0x80000000000000L},
                    new long[]{0L, 0L, 549764202624L, 0x80000000000000L}, 136);
            immediates[1065] = new Pattern(new long[]{0L, 0L, 549764202624L, 0x80008000000000L},
                    new long[]{0L, 0L, 8388736L, 0x80008000000000L}, 152);
            immediates[1066] = new Pattern(new long[]{0L, 0L, 8388736L, 0x80008000800000L},
                    new long[]{0L, 0L, 128L, 0x80008000800000L}, 168);
            immediates[1067] =
                    new Pattern(new long[]{0L, 0L, 128L, 0x80008000800080L}, new long[]{0L, 0L, 0L, 0x80008000800080L},
                            184);
            immediates[1068] = new Pattern(new long[]{0x40004000400040L, 0x40000000000000L, 0L, 0L},
                    new long[]{274882101312L, 0x40000000000000L, 0L, 0L}, 9);
            immediates[1069] = new Pattern(new long[]{274882101312L, 0x40004000000000L, 0L, 0L},
                    new long[]{4194368L, 0x40004000000000L, 0L, 0L}, 25);
            immediates[1070] = new Pattern(new long[]{4194368L, 0x40004000400000L, 0L, 0L},
                    new long[]{64L, 0x40004000400000L, 0L, 0L}, 41);
            immediates[1071] =
                    new Pattern(new long[]{64L, 0x40004000400040L, 0L, 0L}, new long[]{0L, 0x40004000400040L, 0L, 0L},
                            57);
            immediates[1072] = new Pattern(new long[]{0L, 0x40004000400040L, 0x40000000000000L, 0L},
                    new long[]{0L, 274882101312L, 0x40000000000000L, 0L}, 73);
            immediates[1073] = new Pattern(new long[]{0L, 274882101312L, 0x40004000000000L, 0L},
                    new long[]{0L, 4194368L, 0x40004000000000L, 0L}, 89);
            immediates[1074] = new Pattern(new long[]{0L, 4194368L, 0x40004000400000L, 0L},
                    new long[]{0L, 64L, 0x40004000400000L, 0L}, 105);
            immediates[1075] =
                    new Pattern(new long[]{0L, 64L, 0x40004000400040L, 0L}, new long[]{0L, 0L, 0x40004000400040L, 0L},
                            121);
            immediates[1076] = new Pattern(new long[]{0L, 0L, 0x40004000400040L, 0x40000000000000L},
                    new long[]{0L, 0L, 274882101312L, 0x40000000000000L}, 137);
            immediates[1077] = new Pattern(new long[]{0L, 0L, 274882101312L, 0x40004000000000L},
                    new long[]{0L, 0L, 4194368L, 0x40004000000000L}, 153);
            immediates[1078] = new Pattern(new long[]{0L, 0L, 4194368L, 0x40004000400000L},
                    new long[]{0L, 0L, 64L, 0x40004000400000L}, 169);
            immediates[1079] =
                    new Pattern(new long[]{0L, 0L, 64L, 0x40004000400040L}, new long[]{0L, 0L, 0L, 0x40004000400040L},
                            185);
            immediates[1080] = new Pattern(new long[]{9007336695791648L, 9007199254740992L, 0L, 0L},
                    new long[]{137441050656L, 9007199254740992L, 0L, 0L}, 10);
            immediates[1081] = new Pattern(new long[]{137441050656L, 9007336693694464L, 0L, 0L},
                    new long[]{2097184L, 9007336693694464L, 0L, 0L}, 26);
            immediates[1082] = new Pattern(new long[]{2097184L, 9007336695791616L, 0L, 0L},
                    new long[]{32L, 9007336695791616L, 0L, 0L}, 42);
            immediates[1083] =
                    new Pattern(new long[]{32L, 9007336695791648L, 0L, 0L}, new long[]{0L, 9007336695791648L, 0L, 0L},
                            58);
            immediates[1084] = new Pattern(new long[]{0L, 9007336695791648L, 9007199254740992L, 0L},
                    new long[]{0L, 137441050656L, 9007199254740992L, 0L}, 74);
            immediates[1085] = new Pattern(new long[]{0L, 137441050656L, 9007336693694464L, 0L},
                    new long[]{0L, 2097184L, 9007336693694464L, 0L}, 90);
            immediates[1086] = new Pattern(new long[]{0L, 2097184L, 9007336695791616L, 0L},
                    new long[]{0L, 32L, 9007336695791616L, 0L}, 106);
            immediates[1087] =
                    new Pattern(new long[]{0L, 32L, 9007336695791648L, 0L}, new long[]{0L, 0L, 9007336695791648L, 0L},
                            122);
            immediates[1088] = new Pattern(new long[]{0L, 0L, 9007336695791648L, 9007199254740992L},
                    new long[]{0L, 0L, 137441050656L, 9007199254740992L}, 138);
            immediates[1089] = new Pattern(new long[]{0L, 0L, 137441050656L, 9007336693694464L},
                    new long[]{0L, 0L, 2097184L, 9007336693694464L}, 154);
            immediates[1090] = new Pattern(new long[]{0L, 0L, 2097184L, 9007336695791616L},
                    new long[]{0L, 0L, 32L, 9007336695791616L}, 170);
            immediates[1091] =
                    new Pattern(new long[]{0L, 0L, 32L, 9007336695791648L}, new long[]{0L, 0L, 0L, 9007336695791648L},
                            186);
            immediates[1092] = new Pattern(new long[]{4503668347895824L, 4503599627370496L, 0L, 0L},
                    new long[]{68720525328L, 4503599627370496L, 0L, 0L}, 11);
            immediates[1093] = new Pattern(new long[]{68720525328L, 4503668346847232L, 0L, 0L},
                    new long[]{1048592L, 4503668346847232L, 0L, 0L}, 27);
            immediates[1094] = new Pattern(new long[]{1048592L, 4503668347895808L, 0L, 0L},
                    new long[]{16L, 4503668347895808L, 0L, 0L}, 43);
            immediates[1095] =
                    new Pattern(new long[]{16L, 4503668347895824L, 0L, 0L}, new long[]{0L, 4503668347895824L, 0L, 0L},
                            59);
            immediates[1096] = new Pattern(new long[]{0L, 4503668347895824L, 4503599627370496L, 0L},
                    new long[]{0L, 68720525328L, 4503599627370496L, 0L}, 75);
            immediates[1097] = new Pattern(new long[]{0L, 68720525328L, 4503668346847232L, 0L},
                    new long[]{0L, 1048592L, 4503668346847232L, 0L}, 91);
            immediates[1098] = new Pattern(new long[]{0L, 1048592L, 4503668347895808L, 0L},
                    new long[]{0L, 16L, 4503668347895808L, 0L}, 107);
            immediates[1099] =
                    new Pattern(new long[]{0L, 16L, 4503668347895824L, 0L}, new long[]{0L, 0L, 4503668347895824L, 0L},
                            123);
            immediates[1100] = new Pattern(new long[]{0L, 0L, 4503668347895824L, 4503599627370496L},
                    new long[]{0L, 0L, 68720525328L, 4503599627370496L}, 139);
            immediates[1101] = new Pattern(new long[]{0L, 0L, 68720525328L, 4503668346847232L},
                    new long[]{0L, 0L, 1048592L, 4503668346847232L}, 155);
            immediates[1102] = new Pattern(new long[]{0L, 0L, 1048592L, 4503668347895808L},
                    new long[]{0L, 0L, 16L, 4503668347895808L}, 171);
            immediates[1103] =
                    new Pattern(new long[]{0L, 0L, 16L, 4503668347895824L}, new long[]{0L, 0L, 0L, 4503668347895824L},
                            187);
            immediates[1104] = new Pattern(new long[]{0x8000800080008L, 0x8000000000000L, 0L, 0L},
                    new long[]{34360262664L, 0x8000000000000L, 0L, 0L}, 12);
            immediates[1105] = new Pattern(new long[]{34360262664L, 0x8000800000000L, 0L, 0L},
                    new long[]{524296L, 0x8000800000000L, 0L, 0L}, 28);
            immediates[1106] =
                    new Pattern(new long[]{524296L, 0x8000800080000L, 0L, 0L}, new long[]{8L, 0x8000800080000L, 0L, 0L},
                            44);
            immediates[1107] =
                    new Pattern(new long[]{8L, 0x8000800080008L, 0L, 0L}, new long[]{0L, 0x8000800080008L, 0L, 0L}, 60);
            immediates[1108] = new Pattern(new long[]{0L, 0x8000800080008L, 0x8000000000000L, 0L},
                    new long[]{0L, 34360262664L, 0x8000000000000L, 0L}, 76);
            immediates[1109] = new Pattern(new long[]{0L, 34360262664L, 0x8000800000000L, 0L},
                    new long[]{0L, 524296L, 0x8000800000000L, 0L}, 92);
            immediates[1110] =
                    new Pattern(new long[]{0L, 524296L, 0x8000800080000L, 0L}, new long[]{0L, 8L, 0x8000800080000L, 0L},
                            108);
            immediates[1111] =
                    new Pattern(new long[]{0L, 8L, 0x8000800080008L, 0L}, new long[]{0L, 0L, 0x8000800080008L, 0L},
                            124);
            immediates[1112] = new Pattern(new long[]{0L, 0L, 0x8000800080008L, 0x8000000000000L},
                    new long[]{0L, 0L, 34360262664L, 0x8000000000000L}, 140);
            immediates[1113] = new Pattern(new long[]{0L, 0L, 34360262664L, 0x8000800000000L},
                    new long[]{0L, 0L, 524296L, 0x8000800000000L}, 156);
            immediates[1114] =
                    new Pattern(new long[]{0L, 0L, 524296L, 0x8000800080000L}, new long[]{0L, 0L, 8L, 0x8000800080000L},
                            172);
            immediates[1115] =
                    new Pattern(new long[]{0L, 0L, 8L, 0x8000800080008L}, new long[]{0L, 0L, 0L, 0x8000800080008L},
                            188);
            immediates[1116] = new Pattern(new long[]{0x4000400040004L, 0x4000000000000L, 0L, 0L},
                    new long[]{17180131332L, 0x4000000000000L, 0L, 0L}, 13);
            immediates[1117] = new Pattern(new long[]{17180131332L, 0x4000400000000L, 0L, 0L},
                    new long[]{262148L, 0x4000400000000L, 0L, 0L}, 29);
            immediates[1118] =
                    new Pattern(new long[]{262148L, 0x4000400040000L, 0L, 0L}, new long[]{4L, 0x4000400040000L, 0L, 0L},
                            45);
            immediates[1119] =
                    new Pattern(new long[]{4L, 0x4000400040004L, 0L, 0L}, new long[]{0L, 0x4000400040004L, 0L, 0L}, 61);
            immediates[1120] = new Pattern(new long[]{0L, 0x4000400040004L, 0x4000000000000L, 0L},
                    new long[]{0L, 17180131332L, 0x4000000000000L, 0L}, 77);
            immediates[1121] = new Pattern(new long[]{0L, 17180131332L, 0x4000400000000L, 0L},
                    new long[]{0L, 262148L, 0x4000400000000L, 0L}, 93);
            immediates[1122] =
                    new Pattern(new long[]{0L, 262148L, 0x4000400040000L, 0L}, new long[]{0L, 4L, 0x4000400040000L, 0L},
                            109);
            immediates[1123] =
                    new Pattern(new long[]{0L, 4L, 0x4000400040004L, 0L}, new long[]{0L, 0L, 0x4000400040004L, 0L},
                            125);
            immediates[1124] = new Pattern(new long[]{0L, 0L, 0x4000400040004L, 0x4000000000000L},
                    new long[]{0L, 0L, 17180131332L, 0x4000000000000L}, 141);
            immediates[1125] = new Pattern(new long[]{0L, 0L, 17180131332L, 0x4000400000000L},
                    new long[]{0L, 0L, 262148L, 0x4000400000000L}, 157);
            immediates[1126] =
                    new Pattern(new long[]{0L, 0L, 262148L, 0x4000400040000L}, new long[]{0L, 0L, 4L, 0x4000400040000L},
                            173);
            immediates[1127] =
                    new Pattern(new long[]{0L, 0L, 4L, 0x4000400040004L}, new long[]{0L, 0L, 0L, 0x4000400040004L},
                            189);
            immediates[1128] = new Pattern(new long[]{562958543486978L, 562949953421312L, 0L, 0L},
                    new long[]{8590065666L, 562949953421312L, 0L, 0L}, 14);
            immediates[1129] = new Pattern(new long[]{8590065666L, 562958543355904L, 0L, 0L},
                    new long[]{131074L, 562958543355904L, 0L, 0L}, 30);
            immediates[1130] =
                    new Pattern(new long[]{131074L, 562958543486976L, 0L, 0L}, new long[]{2L, 562958543486976L, 0L, 0L},
                            46);
            immediates[1131] =
                    new Pattern(new long[]{2L, 562958543486978L, 0L, 0L}, new long[]{0L, 562958543486978L, 0L, 0L}, 62);
            immediates[1132] = new Pattern(new long[]{0L, 562958543486978L, 562949953421312L, 0L},
                    new long[]{0L, 8590065666L, 562949953421312L, 0L}, 78);
            immediates[1133] = new Pattern(new long[]{0L, 8590065666L, 562958543355904L, 0L},
                    new long[]{0L, 131074L, 562958543355904L, 0L}, 94);
            immediates[1134] =
                    new Pattern(new long[]{0L, 131074L, 562958543486976L, 0L}, new long[]{0L, 2L, 562958543486976L, 0L},
                            110);
            immediates[1135] =
                    new Pattern(new long[]{0L, 2L, 562958543486978L, 0L}, new long[]{0L, 0L, 562958543486978L, 0L},
                            126);
            immediates[1136] = new Pattern(new long[]{0L, 0L, 562958543486978L, 562949953421312L},
                    new long[]{0L, 0L, 8590065666L, 562949953421312L}, 142);
            immediates[1137] = new Pattern(new long[]{0L, 0L, 8590065666L, 562958543355904L},
                    new long[]{0L, 0L, 131074L, 562958543355904L}, 158);
            immediates[1138] =
                    new Pattern(new long[]{0L, 0L, 131074L, 562958543486976L}, new long[]{0L, 0L, 2L, 562958543486976L},
                            174);
            immediates[1139] =
                    new Pattern(new long[]{0L, 0L, 2L, 562958543486978L}, new long[]{0L, 0L, 0L, 562958543486978L},
                            190);
            immediates[1140] = new Pattern(new long[]{281479271743489L, 281474976710656L, 0L, 0L},
                    new long[]{4295032833L, 281474976710656L, 0L, 0L}, 15);
            immediates[1141] = new Pattern(new long[]{4295032833L, 281479271677952L, 0L, 0L},
                    new long[]{65537L, 281479271677952L, 0L, 0L}, 31);
            immediates[1142] =
                    new Pattern(new long[]{65537L, 281479271743488L, 0L, 0L}, new long[]{1L, 281479271743488L, 0L, 0L},
                            47);
            immediates[1143] =
                    new Pattern(new long[]{1L, 281479271743489L, 0L, 0L}, new long[]{0L, 281479271743489L, 0L, 0L}, 63);
            immediates[1144] = new Pattern(new long[]{0L, 281479271743489L, 281474976710656L, 0L},
                    new long[]{0L, 4295032833L, 281474976710656L, 0L}, 79);
            immediates[1145] = new Pattern(new long[]{0L, 4295032833L, 281479271677952L, 0L},
                    new long[]{0L, 65537L, 281479271677952L, 0L}, 95);
            immediates[1146] =
                    new Pattern(new long[]{0L, 65537L, 281479271743488L, 0L}, new long[]{0L, 1L, 281479271743488L, 0L},
                            111);
            immediates[1147] =
                    new Pattern(new long[]{0L, 1L, 281479271743489L, 0L}, new long[]{0L, 0L, 281479271743489L, 0L},
                            127);
            immediates[1148] = new Pattern(new long[]{0L, 0L, 281479271743489L, 281474976710656L},
                    new long[]{0L, 0L, 4295032833L, 281474976710656L}, 143);
            immediates[1149] = new Pattern(new long[]{0L, 0L, 4295032833L, 281479271677952L},
                    new long[]{0L, 0L, 65537L, 281479271677952L}, 159);
            immediates[1150] =
                    new Pattern(new long[]{0L, 0L, 65537L, 281479271743488L}, new long[]{0L, 0L, 1L, 281479271743488L},
                            175);
            immediates[1151] =
                    new Pattern(new long[]{0L, 0L, 1L, 281479271743489L}, new long[]{0L, 0L, 0L, 281479271743489L},
                            191);
            immediates[1152] = new Pattern(new long[]{0x8000800080008000L, 0x8000000000000000L, 0L, 0L},
                    new long[]{0x8000000080008000L, 0x8000000000000000L, 0L, 0L}, 16);
            immediates[1153] = new Pattern(new long[]{0x800080008000L, 0x8000800000000000L, 0L, 0L},
                    new long[]{0x800000008000L, 0x8000800000000000L, 0L, 0L}, 32);
            immediates[1154] = new Pattern(new long[]{2147516416L, 0x8000800080000000L, 0L, 0L},
                    new long[]{2147483648L, 0x8000800080000000L, 0L, 0L}, 48);
            immediates[1155] = new Pattern(new long[]{32768L, 0x8000800080008000L, 0L, 0L},
                    new long[]{32768L, 0x800080008000L, 0L, 0L}, 64);
            immediates[1156] = new Pattern(new long[]{0L, 0x8000800080008000L, 0x8000000000000000L, 0L},
                    new long[]{0L, 0x8000000080008000L, 0x8000000000000000L, 0L}, 80);
            immediates[1157] = new Pattern(new long[]{0L, 0x800080008000L, 0x8000800000000000L, 0L},
                    new long[]{0L, 0x800000008000L, 0x8000800000000000L, 0L}, 96);
            immediates[1158] = new Pattern(new long[]{0L, 2147516416L, 0x8000800080000000L, 0L},
                    new long[]{0L, 2147483648L, 0x8000800080000000L, 0L}, 112);
            immediates[1159] = new Pattern(new long[]{0L, 32768L, 0x8000800080008000L, 0L},
                    new long[]{0L, 32768L, 0x800080008000L, 0L}, 128);
            immediates[1160] = new Pattern(new long[]{0L, 0L, 0x8000800080008000L, 0x8000000000000000L},
                    new long[]{0L, 0L, 0x8000000080008000L, 0x8000000000000000L}, 144);
            immediates[1161] = new Pattern(new long[]{0L, 0L, 0x800080008000L, 0x8000800000000000L},
                    new long[]{0L, 0L, 0x800000008000L, 0x8000800000000000L}, 160);
            immediates[1162] = new Pattern(new long[]{0L, 0L, 2147516416L, 0x8000800080000000L},
                    new long[]{0L, 0L, 2147483648L, 0x8000800080000000L}, 176);
            immediates[1163] = new Pattern(new long[]{0L, 0L, 32768L, 0x8000800080008000L},
                    new long[]{0L, 0L, 32768L, 0x800080008000L}, 192);
            immediates[1164] = new Pattern(new long[]{0x4000400040004000L, 0x4000000000000000L, 0L, 0L},
                    new long[]{0x4000000040004000L, 0x4000000000000000L, 0L, 0L}, 17);
            immediates[1165] = new Pattern(new long[]{70369817935872L, 0x4000400000000000L, 0L, 0L},
                    new long[]{70368744194048L, 0x4000400000000000L, 0L, 0L}, 33);
            immediates[1166] = new Pattern(new long[]{1073758208L, 0x4000400040000000L, 0L, 0L},
                    new long[]{1073741824L, 0x4000400040000000L, 0L, 0L}, 49);
            immediates[1167] = new Pattern(new long[]{16384L, 0x4000400040004000L, 0L, 0L},
                    new long[]{16384L, 70369817935872L, 0L, 0L}, 65);
            immediates[1168] = new Pattern(new long[]{0L, 0x4000400040004000L, 0x4000000000000000L, 0L},
                    new long[]{0L, 0x4000000040004000L, 0x4000000000000000L, 0L}, 81);
            immediates[1169] = new Pattern(new long[]{0L, 70369817935872L, 0x4000400000000000L, 0L},
                    new long[]{0L, 70368744194048L, 0x4000400000000000L, 0L}, 97);
            immediates[1170] = new Pattern(new long[]{0L, 1073758208L, 0x4000400040000000L, 0L},
                    new long[]{0L, 1073741824L, 0x4000400040000000L, 0L}, 113);
            immediates[1171] = new Pattern(new long[]{0L, 16384L, 0x4000400040004000L, 0L},
                    new long[]{0L, 16384L, 70369817935872L, 0L}, 129);
            immediates[1172] = new Pattern(new long[]{0L, 0L, 0x4000400040004000L, 0x4000000000000000L},
                    new long[]{0L, 0L, 0x4000000040004000L, 0x4000000000000000L}, 145);
            immediates[1173] = new Pattern(new long[]{0L, 0L, 70369817935872L, 0x4000400000000000L},
                    new long[]{0L, 0L, 70368744194048L, 0x4000400000000000L}, 161);
            immediates[1174] = new Pattern(new long[]{0L, 0L, 1073758208L, 0x4000400040000000L},
                    new long[]{0L, 0L, 1073741824L, 0x4000400040000000L}, 177);
            immediates[1175] = new Pattern(new long[]{0L, 0L, 16384L, 0x4000400040004000L},
                    new long[]{0L, 0L, 16384L, 70369817935872L}, 193);
            immediates[1176] = new Pattern(new long[]{0x2000200020002000L, 0x2000000000000000L, 0L, 0L},
                    new long[]{0x2000000020002000L, 0x2000000000000000L, 0L, 0L}, 18);
            immediates[1177] = new Pattern(new long[]{35184908967936L, 0x2000200000000000L, 0L, 0L},
                    new long[]{35184372097024L, 0x2000200000000000L, 0L, 0L}, 34);
            immediates[1178] = new Pattern(new long[]{536879104L, 0x2000200020000000L, 0L, 0L},
                    new long[]{536870912L, 0x2000200020000000L, 0L, 0L}, 50);
            immediates[1179] = new Pattern(new long[]{8192L, 0x2000200020002000L, 0L, 0L},
                    new long[]{8192L, 35184908967936L, 0L, 0L}, 66);
            immediates[1180] = new Pattern(new long[]{0L, 0x2000200020002000L, 0x2000000000000000L, 0L},
                    new long[]{0L, 0x2000000020002000L, 0x2000000000000000L, 0L}, 82);
            immediates[1181] = new Pattern(new long[]{0L, 35184908967936L, 0x2000200000000000L, 0L},
                    new long[]{0L, 35184372097024L, 0x2000200000000000L, 0L}, 98);
            immediates[1182] = new Pattern(new long[]{0L, 536879104L, 0x2000200020000000L, 0L},
                    new long[]{0L, 536870912L, 0x2000200020000000L, 0L}, 114);
            immediates[1183] = new Pattern(new long[]{0L, 8192L, 0x2000200020002000L, 0L},
                    new long[]{0L, 8192L, 35184908967936L, 0L}, 130);
            immediates[1184] = new Pattern(new long[]{0L, 0L, 0x2000200020002000L, 0x2000000000000000L},
                    new long[]{0L, 0L, 0x2000000020002000L, 0x2000000000000000L}, 146);
            immediates[1185] = new Pattern(new long[]{0L, 0L, 35184908967936L, 0x2000200000000000L},
                    new long[]{0L, 0L, 35184372097024L, 0x2000200000000000L}, 162);
            immediates[1186] = new Pattern(new long[]{0L, 0L, 536879104L, 0x2000200020000000L},
                    new long[]{0L, 0L, 536870912L, 0x2000200020000000L}, 178);
            immediates[1187] = new Pattern(new long[]{0L, 0L, 8192L, 0x2000200020002000L},
                    new long[]{0L, 0L, 8192L, 35184908967936L}, 194);
            immediates[1188] = new Pattern(new long[]{0x1000100010001000L, 0x1000000000000000L, 0L, 0L},
                    new long[]{0x1000000010001000L, 0x1000000000000000L, 0L, 0L}, 19);
            immediates[1189] = new Pattern(new long[]{17592454483968L, 0x1000100000000000L, 0L, 0L},
                    new long[]{17592186048512L, 0x1000100000000000L, 0L, 0L}, 35);
            immediates[1190] = new Pattern(new long[]{268439552L, 0x1000100010000000L, 0L, 0L},
                    new long[]{268435456L, 0x1000100010000000L, 0L, 0L}, 51);
            immediates[1191] = new Pattern(new long[]{4096L, 0x1000100010001000L, 0L, 0L},
                    new long[]{4096L, 17592454483968L, 0L, 0L}, 67);
            immediates[1192] = new Pattern(new long[]{0L, 0x1000100010001000L, 0x1000000000000000L, 0L},
                    new long[]{0L, 0x1000000010001000L, 0x1000000000000000L, 0L}, 83);
            immediates[1193] = new Pattern(new long[]{0L, 17592454483968L, 0x1000100000000000L, 0L},
                    new long[]{0L, 17592186048512L, 0x1000100000000000L, 0L}, 99);
            immediates[1194] = new Pattern(new long[]{0L, 268439552L, 0x1000100010000000L, 0L},
                    new long[]{0L, 268435456L, 0x1000100010000000L, 0L}, 115);
            immediates[1195] = new Pattern(new long[]{0L, 4096L, 0x1000100010001000L, 0L},
                    new long[]{0L, 4096L, 17592454483968L, 0L}, 131);
            immediates[1196] = new Pattern(new long[]{0L, 0L, 0x1000100010001000L, 0x1000000000000000L},
                    new long[]{0L, 0L, 0x1000000010001000L, 0x1000000000000000L}, 147);
            immediates[1197] = new Pattern(new long[]{0L, 0L, 17592454483968L, 0x1000100000000000L},
                    new long[]{0L, 0L, 17592186048512L, 0x1000100000000000L}, 163);
            immediates[1198] = new Pattern(new long[]{0L, 0L, 268439552L, 0x1000100010000000L},
                    new long[]{0L, 0L, 268435456L, 0x1000100010000000L}, 179);
            immediates[1199] = new Pattern(new long[]{0L, 0L, 4096L, 0x1000100010001000L},
                    new long[]{0L, 0L, 4096L, 17592454483968L}, 195);
            immediates[1200] = new Pattern(new long[]{0x800080008000800L, 0x800000000000000L, 0L, 0L},
                    new long[]{0x800000008000800L, 0x800000000000000L, 0L, 0L}, 20);
            immediates[1201] = new Pattern(new long[]{8796227241984L, 0x800080000000000L, 0L, 0L},
                    new long[]{8796093024256L, 0x800080000000000L, 0L, 0L}, 36);
            immediates[1202] = new Pattern(new long[]{134219776L, 0x800080008000000L, 0L, 0L},
                    new long[]{134217728L, 0x800080008000000L, 0L, 0L}, 52);
            immediates[1203] = new Pattern(new long[]{2048L, 0x800080008000800L, 0L, 0L},
                    new long[]{2048L, 8796227241984L, 0L, 0L}, 68);
            immediates[1204] = new Pattern(new long[]{0L, 0x800080008000800L, 0x800000000000000L, 0L},
                    new long[]{0L, 0x800000008000800L, 0x800000000000000L, 0L}, 84);
            immediates[1205] = new Pattern(new long[]{0L, 8796227241984L, 0x800080000000000L, 0L},
                    new long[]{0L, 8796093024256L, 0x800080000000000L, 0L}, 100);
            immediates[1206] = new Pattern(new long[]{0L, 134219776L, 0x800080008000000L, 0L},
                    new long[]{0L, 134217728L, 0x800080008000000L, 0L}, 116);
            immediates[1207] = new Pattern(new long[]{0L, 2048L, 0x800080008000800L, 0L},
                    new long[]{0L, 2048L, 8796227241984L, 0L}, 132);
            immediates[1208] = new Pattern(new long[]{0L, 0L, 0x800080008000800L, 0x800000000000000L},
                    new long[]{0L, 0L, 0x800000008000800L, 0x800000000000000L}, 148);
            immediates[1209] = new Pattern(new long[]{0L, 0L, 8796227241984L, 0x800080000000000L},
                    new long[]{0L, 0L, 8796093024256L, 0x800080000000000L}, 164);
            immediates[1210] = new Pattern(new long[]{0L, 0L, 134219776L, 0x800080008000000L},
                    new long[]{0L, 0L, 134217728L, 0x800080008000000L}, 180);
            immediates[1211] = new Pattern(new long[]{0L, 0L, 2048L, 0x800080008000800L},
                    new long[]{0L, 0L, 2048L, 8796227241984L}, 196);
            immediates[1212] = new Pattern(new long[]{0x400040004000400L, 0x400000000000000L, 0L, 0L},
                    new long[]{0x400000004000400L, 0x400000000000000L, 0L, 0L}, 21);
            immediates[1213] = new Pattern(new long[]{4398113620992L, 0x400040000000000L, 0L, 0L},
                    new long[]{4398046512128L, 0x400040000000000L, 0L, 0L}, 37);
            immediates[1214] = new Pattern(new long[]{67109888L, 0x400040004000000L, 0L, 0L},
                    new long[]{67108864L, 0x400040004000000L, 0L, 0L}, 53);
            immediates[1215] = new Pattern(new long[]{1024L, 0x400040004000400L, 0L, 0L},
                    new long[]{1024L, 4398113620992L, 0L, 0L}, 69);
            immediates[1216] = new Pattern(new long[]{0L, 0x400040004000400L, 0x400000000000000L, 0L},
                    new long[]{0L, 0x400000004000400L, 0x400000000000000L, 0L}, 85);
            immediates[1217] = new Pattern(new long[]{0L, 4398113620992L, 0x400040000000000L, 0L},
                    new long[]{0L, 4398046512128L, 0x400040000000000L, 0L}, 101);
            immediates[1218] = new Pattern(new long[]{0L, 67109888L, 0x400040004000000L, 0L},
                    new long[]{0L, 67108864L, 0x400040004000000L, 0L}, 117);
            immediates[1219] = new Pattern(new long[]{0L, 1024L, 0x400040004000400L, 0L},
                    new long[]{0L, 1024L, 4398113620992L, 0L}, 133);
            immediates[1220] = new Pattern(new long[]{0L, 0L, 0x400040004000400L, 0x400000000000000L},
                    new long[]{0L, 0L, 0x400000004000400L, 0x400000000000000L}, 149);
            immediates[1221] = new Pattern(new long[]{0L, 0L, 4398113620992L, 0x400040000000000L},
                    new long[]{0L, 0L, 4398046512128L, 0x400040000000000L}, 165);
            immediates[1222] = new Pattern(new long[]{0L, 0L, 67109888L, 0x400040004000000L},
                    new long[]{0L, 0L, 67108864L, 0x400040004000000L}, 181);
            immediates[1223] = new Pattern(new long[]{0L, 0L, 1024L, 0x400040004000400L},
                    new long[]{0L, 0L, 1024L, 4398113620992L}, 197);
            immediates[1224] = new Pattern(new long[]{0x200020002000200L, 0x200000000000000L, 0L, 0L},
                    new long[]{0x200000002000200L, 0x200000000000000L, 0L, 0L}, 22);
            immediates[1225] = new Pattern(new long[]{2199056810496L, 0x200020000000000L, 0L, 0L},
                    new long[]{2199023256064L, 0x200020000000000L, 0L, 0L}, 38);
            immediates[1226] = new Pattern(new long[]{33554944L, 0x200020002000000L, 0L, 0L},
                    new long[]{33554432L, 0x200020002000000L, 0L, 0L}, 54);
            immediates[1227] =
                    new Pattern(new long[]{512L, 0x200020002000200L, 0L, 0L}, new long[]{512L, 2199056810496L, 0L, 0L},
                            70);
            immediates[1228] = new Pattern(new long[]{0L, 0x200020002000200L, 0x200000000000000L, 0L},
                    new long[]{0L, 0x200000002000200L, 0x200000000000000L, 0L}, 86);
            immediates[1229] = new Pattern(new long[]{0L, 2199056810496L, 0x200020000000000L, 0L},
                    new long[]{0L, 2199023256064L, 0x200020000000000L, 0L}, 102);
            immediates[1230] = new Pattern(new long[]{0L, 33554944L, 0x200020002000000L, 0L},
                    new long[]{0L, 33554432L, 0x200020002000000L, 0L}, 118);
            immediates[1231] =
                    new Pattern(new long[]{0L, 512L, 0x200020002000200L, 0L}, new long[]{0L, 512L, 2199056810496L, 0L},
                            134);
            immediates[1232] = new Pattern(new long[]{0L, 0L, 0x200020002000200L, 0x200000000000000L},
                    new long[]{0L, 0L, 0x200000002000200L, 0x200000000000000L}, 150);
            immediates[1233] = new Pattern(new long[]{0L, 0L, 2199056810496L, 0x200020000000000L},
                    new long[]{0L, 0L, 2199023256064L, 0x200020000000000L}, 166);
            immediates[1234] = new Pattern(new long[]{0L, 0L, 33554944L, 0x200020002000000L},
                    new long[]{0L, 0L, 33554432L, 0x200020002000000L}, 182);
            immediates[1235] =
                    new Pattern(new long[]{0L, 0L, 512L, 0x200020002000200L}, new long[]{0L, 0L, 512L, 2199056810496L},
                            198);
            immediates[1236] = new Pattern(new long[]{72058693566333184L, 72057594037927936L, 0L, 0L},
                    new long[]{72057594054705408L, 72057594037927936L, 0L, 0L}, 23);
            immediates[1237] = new Pattern(new long[]{1099528405248L, 72058693549555712L, 0L, 0L},
                    new long[]{1099511628032L, 72058693549555712L, 0L, 0L}, 39);
            immediates[1238] = new Pattern(new long[]{16777472L, 72058693566332928L, 0L, 0L},
                    new long[]{16777216L, 72058693566332928L, 0L, 0L}, 55);
            immediates[1239] =
                    new Pattern(new long[]{256L, 72058693566333184L, 0L, 0L}, new long[]{256L, 1099528405248L, 0L, 0L},
                            71);
            immediates[1240] = new Pattern(new long[]{0L, 72058693566333184L, 72057594037927936L, 0L},
                    new long[]{0L, 72057594054705408L, 72057594037927936L, 0L}, 87);
            immediates[1241] = new Pattern(new long[]{0L, 1099528405248L, 72058693549555712L, 0L},
                    new long[]{0L, 1099511628032L, 72058693549555712L, 0L}, 103);
            immediates[1242] = new Pattern(new long[]{0L, 16777472L, 72058693566332928L, 0L},
                    new long[]{0L, 16777216L, 72058693566332928L, 0L}, 119);
            immediates[1243] =
                    new Pattern(new long[]{0L, 256L, 72058693566333184L, 0L}, new long[]{0L, 256L, 1099528405248L, 0L},
                            135);
            immediates[1244] = new Pattern(new long[]{0L, 0L, 72058693566333184L, 72057594037927936L},
                    new long[]{0L, 0L, 72057594054705408L, 72057594037927936L}, 151);
            immediates[1245] = new Pattern(new long[]{0L, 0L, 1099528405248L, 72058693549555712L},
                    new long[]{0L, 0L, 1099511628032L, 72058693549555712L}, 167);
            immediates[1246] = new Pattern(new long[]{0L, 0L, 16777472L, 72058693566332928L},
                    new long[]{0L, 0L, 16777216L, 72058693566332928L}, 183);
            immediates[1247] =
                    new Pattern(new long[]{0L, 0L, 256L, 72058693566333184L}, new long[]{0L, 0L, 256L, 1099528405248L},
                            199);
            immediates[1248] = new Pattern(new long[]{0x80008000800080L, 0x80000000000000L, 0L, 0L},
                    new long[]{0x80000000800080L, 0x80000000000000L, 0L, 0L}, 24);
            immediates[1249] = new Pattern(new long[]{549764202624L, 0x80008000000000L, 0L, 0L},
                    new long[]{549755814016L, 0x80008000000000L, 0L, 0L}, 40);
            immediates[1250] = new Pattern(new long[]{8388736L, 0x80008000800000L, 0L, 0L},
                    new long[]{8388608L, 0x80008000800000L, 0L, 0L}, 56);
            immediates[1251] =
                    new Pattern(new long[]{128L, 0x80008000800080L, 0L, 0L}, new long[]{128L, 549764202624L, 0L, 0L},
                            72);
            immediates[1252] = new Pattern(new long[]{0L, 0x80008000800080L, 0x80000000000000L, 0L},
                    new long[]{0L, 0x80000000800080L, 0x80000000000000L, 0L}, 88);
            immediates[1253] = new Pattern(new long[]{0L, 549764202624L, 0x80008000000000L, 0L},
                    new long[]{0L, 549755814016L, 0x80008000000000L, 0L}, 104);
            immediates[1254] = new Pattern(new long[]{0L, 8388736L, 0x80008000800000L, 0L},
                    new long[]{0L, 8388608L, 0x80008000800000L, 0L}, 120);
            immediates[1255] =
                    new Pattern(new long[]{0L, 128L, 0x80008000800080L, 0L}, new long[]{0L, 128L, 549764202624L, 0L},
                            136);
            immediates[1256] = new Pattern(new long[]{0L, 0L, 0x80008000800080L, 0x80000000000000L},
                    new long[]{0L, 0L, 0x80000000800080L, 0x80000000000000L}, 152);
            immediates[1257] = new Pattern(new long[]{0L, 0L, 549764202624L, 0x80008000000000L},
                    new long[]{0L, 0L, 549755814016L, 0x80008000000000L}, 168);
            immediates[1258] = new Pattern(new long[]{0L, 0L, 8388736L, 0x80008000800000L},
                    new long[]{0L, 0L, 8388608L, 0x80008000800000L}, 184);
            immediates[1259] =
                    new Pattern(new long[]{0L, 0L, 128L, 0x80008000800080L}, new long[]{0L, 0L, 128L, 549764202624L},
                            200);
            immediates[1260] = new Pattern(new long[]{0x40004000400040L, 0x40000000000000L, 0L, 0L},
                    new long[]{0x40000000400040L, 0x40000000000000L, 0L, 0L}, 25);
            immediates[1261] = new Pattern(new long[]{274882101312L, 0x40004000000000L, 0L, 0L},
                    new long[]{274877907008L, 0x40004000000000L, 0L, 0L}, 41);
            immediates[1262] = new Pattern(new long[]{4194368L, 0x40004000400000L, 0L, 0L},
                    new long[]{4194304L, 0x40004000400000L, 0L, 0L}, 57);
            immediates[1263] =
                    new Pattern(new long[]{64L, 0x40004000400040L, 0L, 0L}, new long[]{64L, 274882101312L, 0L, 0L}, 73);
            immediates[1264] = new Pattern(new long[]{0L, 0x40004000400040L, 0x40000000000000L, 0L},
                    new long[]{0L, 0x40000000400040L, 0x40000000000000L, 0L}, 89);
            immediates[1265] = new Pattern(new long[]{0L, 274882101312L, 0x40004000000000L, 0L},
                    new long[]{0L, 274877907008L, 0x40004000000000L, 0L}, 105);
            immediates[1266] = new Pattern(new long[]{0L, 4194368L, 0x40004000400000L, 0L},
                    new long[]{0L, 4194304L, 0x40004000400000L, 0L}, 121);
            immediates[1267] =
                    new Pattern(new long[]{0L, 64L, 0x40004000400040L, 0L}, new long[]{0L, 64L, 274882101312L, 0L},
                            137);
            immediates[1268] = new Pattern(new long[]{0L, 0L, 0x40004000400040L, 0x40000000000000L},
                    new long[]{0L, 0L, 0x40000000400040L, 0x40000000000000L}, 153);
            immediates[1269] = new Pattern(new long[]{0L, 0L, 274882101312L, 0x40004000000000L},
                    new long[]{0L, 0L, 274877907008L, 0x40004000000000L}, 169);
            immediates[1270] = new Pattern(new long[]{0L, 0L, 4194368L, 0x40004000400000L},
                    new long[]{0L, 0L, 4194304L, 0x40004000400000L}, 185);
            immediates[1271] =
                    new Pattern(new long[]{0L, 0L, 64L, 0x40004000400040L}, new long[]{0L, 0L, 64L, 274882101312L},
                            201);
            immediates[1272] = new Pattern(new long[]{9007336695791648L, 9007199254740992L, 0L, 0L},
                    new long[]{9007199256838176L, 9007199254740992L, 0L, 0L}, 26);
            immediates[1273] = new Pattern(new long[]{137441050656L, 9007336693694464L, 0L, 0L},
                    new long[]{137438953504L, 9007336693694464L, 0L, 0L}, 42);
            immediates[1274] = new Pattern(new long[]{2097184L, 9007336695791616L, 0L, 0L},
                    new long[]{2097152L, 9007336695791616L, 0L, 0L}, 58);
            immediates[1275] =
                    new Pattern(new long[]{32L, 9007336695791648L, 0L, 0L}, new long[]{32L, 137441050656L, 0L, 0L}, 74);
            immediates[1276] = new Pattern(new long[]{0L, 9007336695791648L, 9007199254740992L, 0L},
                    new long[]{0L, 9007199256838176L, 9007199254740992L, 0L}, 90);
            immediates[1277] = new Pattern(new long[]{0L, 137441050656L, 9007336693694464L, 0L},
                    new long[]{0L, 137438953504L, 9007336693694464L, 0L}, 106);
            immediates[1278] = new Pattern(new long[]{0L, 2097184L, 9007336695791616L, 0L},
                    new long[]{0L, 2097152L, 9007336695791616L, 0L}, 122);
            immediates[1279] =
                    new Pattern(new long[]{0L, 32L, 9007336695791648L, 0L}, new long[]{0L, 32L, 137441050656L, 0L},
                            138);
            immediates[1280] = new Pattern(new long[]{0L, 0L, 9007336695791648L, 9007199254740992L},
                    new long[]{0L, 0L, 9007199256838176L, 9007199254740992L}, 154);
            immediates[1281] = new Pattern(new long[]{0L, 0L, 137441050656L, 9007336693694464L},
                    new long[]{0L, 0L, 137438953504L, 9007336693694464L}, 170);
            immediates[1282] = new Pattern(new long[]{0L, 0L, 2097184L, 9007336695791616L},
                    new long[]{0L, 0L, 2097152L, 9007336695791616L}, 186);
            immediates[1283] =
                    new Pattern(new long[]{0L, 0L, 32L, 9007336695791648L}, new long[]{0L, 0L, 32L, 137441050656L},
                            202);
            immediates[1284] = new Pattern(new long[]{4503668347895824L, 4503599627370496L, 0L, 0L},
                    new long[]{4503599628419088L, 4503599627370496L, 0L, 0L}, 27);
            immediates[1285] = new Pattern(new long[]{68720525328L, 4503668346847232L, 0L, 0L},
                    new long[]{68719476752L, 4503668346847232L, 0L, 0L}, 43);
            immediates[1286] = new Pattern(new long[]{1048592L, 4503668347895808L, 0L, 0L},
                    new long[]{1048576L, 4503668347895808L, 0L, 0L}, 59);
            immediates[1287] =
                    new Pattern(new long[]{16L, 4503668347895824L, 0L, 0L}, new long[]{16L, 68720525328L, 0L, 0L}, 75);
            immediates[1288] = new Pattern(new long[]{0L, 4503668347895824L, 4503599627370496L, 0L},
                    new long[]{0L, 4503599628419088L, 4503599627370496L, 0L}, 91);
            immediates[1289] = new Pattern(new long[]{0L, 68720525328L, 4503668346847232L, 0L},
                    new long[]{0L, 68719476752L, 4503668346847232L, 0L}, 107);
            immediates[1290] = new Pattern(new long[]{0L, 1048592L, 4503668347895808L, 0L},
                    new long[]{0L, 1048576L, 4503668347895808L, 0L}, 123);
            immediates[1291] =
                    new Pattern(new long[]{0L, 16L, 4503668347895824L, 0L}, new long[]{0L, 16L, 68720525328L, 0L}, 139);
            immediates[1292] = new Pattern(new long[]{0L, 0L, 4503668347895824L, 4503599627370496L},
                    new long[]{0L, 0L, 4503599628419088L, 4503599627370496L}, 155);
            immediates[1293] = new Pattern(new long[]{0L, 0L, 68720525328L, 4503668346847232L},
                    new long[]{0L, 0L, 68719476752L, 4503668346847232L}, 171);
            immediates[1294] = new Pattern(new long[]{0L, 0L, 1048592L, 4503668347895808L},
                    new long[]{0L, 0L, 1048576L, 4503668347895808L}, 187);
            immediates[1295] =
                    new Pattern(new long[]{0L, 0L, 16L, 4503668347895824L}, new long[]{0L, 0L, 16L, 68720525328L}, 203);
            immediates[1296] = new Pattern(new long[]{0x8000800080008L, 0x8000000000000L, 0L, 0L},
                    new long[]{0x8000000080008L, 0x8000000000000L, 0L, 0L}, 28);
            immediates[1297] = new Pattern(new long[]{34360262664L, 0x8000800000000L, 0L, 0L},
                    new long[]{34359738376L, 0x8000800000000L, 0L, 0L}, 44);
            immediates[1298] = new Pattern(new long[]{524296L, 0x8000800080000L, 0L, 0L},
                    new long[]{524288L, 0x8000800080000L, 0L, 0L}, 60);
            immediates[1299] =
                    new Pattern(new long[]{8L, 0x8000800080008L, 0L, 0L}, new long[]{8L, 34360262664L, 0L, 0L}, 76);
            immediates[1300] = new Pattern(new long[]{0L, 0x8000800080008L, 0x8000000000000L, 0L},
                    new long[]{0L, 0x8000000080008L, 0x8000000000000L, 0L}, 92);
            immediates[1301] = new Pattern(new long[]{0L, 34360262664L, 0x8000800000000L, 0L},
                    new long[]{0L, 34359738376L, 0x8000800000000L, 0L}, 108);
            immediates[1302] = new Pattern(new long[]{0L, 524296L, 0x8000800080000L, 0L},
                    new long[]{0L, 524288L, 0x8000800080000L, 0L}, 124);
            immediates[1303] =
                    new Pattern(new long[]{0L, 8L, 0x8000800080008L, 0L}, new long[]{0L, 8L, 34360262664L, 0L}, 140);
            immediates[1304] = new Pattern(new long[]{0L, 0L, 0x8000800080008L, 0x8000000000000L},
                    new long[]{0L, 0L, 0x8000000080008L, 0x8000000000000L}, 156);
            immediates[1305] = new Pattern(new long[]{0L, 0L, 34360262664L, 0x8000800000000L},
                    new long[]{0L, 0L, 34359738376L, 0x8000800000000L}, 172);
            immediates[1306] = new Pattern(new long[]{0L, 0L, 524296L, 0x8000800080000L},
                    new long[]{0L, 0L, 524288L, 0x8000800080000L}, 188);
            immediates[1307] =
                    new Pattern(new long[]{0L, 0L, 8L, 0x8000800080008L}, new long[]{0L, 0L, 8L, 34360262664L}, 204);
            immediates[1308] = new Pattern(new long[]{0x4000400040004L, 0x4000000000000L, 0L, 0L},
                    new long[]{0x4000000040004L, 0x4000000000000L, 0L, 0L}, 29);
            immediates[1309] = new Pattern(new long[]{17180131332L, 0x4000400000000L, 0L, 0L},
                    new long[]{17179869188L, 0x4000400000000L, 0L, 0L}, 45);
            immediates[1310] = new Pattern(new long[]{262148L, 0x4000400040000L, 0L, 0L},
                    new long[]{262144L, 0x4000400040000L, 0L, 0L}, 61);
            immediates[1311] =
                    new Pattern(new long[]{4L, 0x4000400040004L, 0L, 0L}, new long[]{4L, 17180131332L, 0L, 0L}, 77);
            immediates[1312] = new Pattern(new long[]{0L, 0x4000400040004L, 0x4000000000000L, 0L},
                    new long[]{0L, 0x4000000040004L, 0x4000000000000L, 0L}, 93);
            immediates[1313] = new Pattern(new long[]{0L, 17180131332L, 0x4000400000000L, 0L},
                    new long[]{0L, 17179869188L, 0x4000400000000L, 0L}, 109);
            immediates[1314] = new Pattern(new long[]{0L, 262148L, 0x4000400040000L, 0L},
                    new long[]{0L, 262144L, 0x4000400040000L, 0L}, 125);
            immediates[1315] =
                    new Pattern(new long[]{0L, 4L, 0x4000400040004L, 0L}, new long[]{0L, 4L, 17180131332L, 0L}, 141);
            immediates[1316] = new Pattern(new long[]{0L, 0L, 0x4000400040004L, 0x4000000000000L},
                    new long[]{0L, 0L, 0x4000000040004L, 0x4000000000000L}, 157);
            immediates[1317] = new Pattern(new long[]{0L, 0L, 17180131332L, 0x4000400000000L},
                    new long[]{0L, 0L, 17179869188L, 0x4000400000000L}, 173);
            immediates[1318] = new Pattern(new long[]{0L, 0L, 262148L, 0x4000400040000L},
                    new long[]{0L, 0L, 262144L, 0x4000400040000L}, 189);
            immediates[1319] =
                    new Pattern(new long[]{0L, 0L, 4L, 0x4000400040004L}, new long[]{0L, 0L, 4L, 17180131332L}, 205);
            immediates[1320] = new Pattern(new long[]{562958543486978L, 562949953421312L, 0L, 0L},
                    new long[]{562949953552386L, 562949953421312L, 0L, 0L}, 30);
            immediates[1321] = new Pattern(new long[]{8590065666L, 562958543355904L, 0L, 0L},
                    new long[]{8589934594L, 562958543355904L, 0L, 0L}, 46);
            immediates[1322] = new Pattern(new long[]{131074L, 562958543486976L, 0L, 0L},
                    new long[]{131072L, 562958543486976L, 0L, 0L}, 62);
            immediates[1323] =
                    new Pattern(new long[]{2L, 562958543486978L, 0L, 0L}, new long[]{2L, 8590065666L, 0L, 0L}, 78);
            immediates[1324] = new Pattern(new long[]{0L, 562958543486978L, 562949953421312L, 0L},
                    new long[]{0L, 562949953552386L, 562949953421312L, 0L}, 94);
            immediates[1325] = new Pattern(new long[]{0L, 8590065666L, 562958543355904L, 0L},
                    new long[]{0L, 8589934594L, 562958543355904L, 0L}, 110);
            immediates[1326] = new Pattern(new long[]{0L, 131074L, 562958543486976L, 0L},
                    new long[]{0L, 131072L, 562958543486976L, 0L}, 126);
            immediates[1327] =
                    new Pattern(new long[]{0L, 2L, 562958543486978L, 0L}, new long[]{0L, 2L, 8590065666L, 0L}, 142);
            immediates[1328] = new Pattern(new long[]{0L, 0L, 562958543486978L, 562949953421312L},
                    new long[]{0L, 0L, 562949953552386L, 562949953421312L}, 158);
            immediates[1329] = new Pattern(new long[]{0L, 0L, 8590065666L, 562958543355904L},
                    new long[]{0L, 0L, 8589934594L, 562958543355904L}, 174);
            immediates[1330] = new Pattern(new long[]{0L, 0L, 131074L, 562958543486976L},
                    new long[]{0L, 0L, 131072L, 562958543486976L}, 190);
            immediates[1331] =
                    new Pattern(new long[]{0L, 0L, 2L, 562958543486978L}, new long[]{0L, 0L, 2L, 8590065666L}, 206);
            immediates[1332] = new Pattern(new long[]{281479271743489L, 281474976710656L, 0L, 0L},
                    new long[]{281474976776193L, 281474976710656L, 0L, 0L}, 31);
            immediates[1333] = new Pattern(new long[]{4295032833L, 281479271677952L, 0L, 0L},
                    new long[]{4294967297L, 281479271677952L, 0L, 0L}, 47);
            immediates[1334] = new Pattern(new long[]{65537L, 281479271743488L, 0L, 0L},
                    new long[]{65536L, 281479271743488L, 0L, 0L}, 63);
            immediates[1335] =
                    new Pattern(new long[]{1L, 281479271743489L, 0L, 0L}, new long[]{1L, 4295032833L, 0L, 0L}, 79);
            immediates[1336] = new Pattern(new long[]{0L, 281479271743489L, 281474976710656L, 0L},
                    new long[]{0L, 281474976776193L, 281474976710656L, 0L}, 95);
            immediates[1337] = new Pattern(new long[]{0L, 4295032833L, 281479271677952L, 0L},
                    new long[]{0L, 4294967297L, 281479271677952L, 0L}, 111);
            immediates[1338] = new Pattern(new long[]{0L, 65537L, 281479271743488L, 0L},
                    new long[]{0L, 65536L, 281479271743488L, 0L}, 127);
            immediates[1339] =
                    new Pattern(new long[]{0L, 1L, 281479271743489L, 0L}, new long[]{0L, 1L, 4295032833L, 0L}, 143);
            immediates[1340] = new Pattern(new long[]{0L, 0L, 281479271743489L, 281474976710656L},
                    new long[]{0L, 0L, 281474976776193L, 281474976710656L}, 159);
            immediates[1341] = new Pattern(new long[]{0L, 0L, 4295032833L, 281479271677952L},
                    new long[]{0L, 0L, 4294967297L, 281479271677952L}, 175);
            immediates[1342] = new Pattern(new long[]{0L, 0L, 65537L, 281479271743488L},
                    new long[]{0L, 0L, 65536L, 281479271743488L}, 191);
            immediates[1343] =
                    new Pattern(new long[]{0L, 0L, 1L, 281479271743489L}, new long[]{0L, 0L, 1L, 4295032833L}, 207);
            immediates[1344] = new Pattern(new long[]{0x8000800080008000L, 0x8000000000000000L, 0L, 0L},
                    new long[]{0x8000800000008000L, 0x8000000000000000L, 0L, 0L}, 32);
            immediates[1345] = new Pattern(new long[]{0x800080008000L, 0x8000800000000000L, 0L, 0L},
                    new long[]{0x800080000000L, 0x8000800000000000L, 0L, 0L}, 48);
            immediates[1346] = new Pattern(new long[]{2147516416L, 0x8000800080000000L, 0L, 0L},
                    new long[]{2147516416L, 0x800080000000L, 0L, 0L}, 64);
            immediates[1347] = new Pattern(new long[]{32768L, 0x8000800080008000L, 0L, 0L},
                    new long[]{32768L, 0x8000000080008000L, 0L, 0L}, 80);
            immediates[1348] = new Pattern(new long[]{0L, 0x8000800080008000L, 0x8000000000000000L, 0L},
                    new long[]{0L, 0x8000800000008000L, 0x8000000000000000L, 0L}, 96);
            immediates[1349] = new Pattern(new long[]{0L, 0x800080008000L, 0x8000800000000000L, 0L},
                    new long[]{0L, 0x800080000000L, 0x8000800000000000L, 0L}, 112);
            immediates[1350] = new Pattern(new long[]{0L, 2147516416L, 0x8000800080000000L, 0L},
                    new long[]{0L, 2147516416L, 0x800080000000L, 0L}, 128);
            immediates[1351] = new Pattern(new long[]{0L, 32768L, 0x8000800080008000L, 0L},
                    new long[]{0L, 32768L, 0x8000000080008000L, 0L}, 144);
            immediates[1352] = new Pattern(new long[]{0L, 0L, 0x8000800080008000L, 0x8000000000000000L},
                    new long[]{0L, 0L, 0x8000800000008000L, 0x8000000000000000L}, 160);
            immediates[1353] = new Pattern(new long[]{0L, 0L, 0x800080008000L, 0x8000800000000000L},
                    new long[]{0L, 0L, 0x800080000000L, 0x8000800000000000L}, 176);
            immediates[1354] = new Pattern(new long[]{0L, 0L, 2147516416L, 0x8000800080000000L},
                    new long[]{0L, 0L, 2147516416L, 0x800080000000L}, 192);
            immediates[1355] = new Pattern(new long[]{0L, 0L, 32768L, 0x8000800080008000L},
                    new long[]{0L, 0L, 32768L, 0x8000000080008000L}, 208);
            immediates[1356] = new Pattern(new long[]{0x4000400040004000L, 0x4000000000000000L, 0L, 0L},
                    new long[]{0x4000400000004000L, 0x4000000000000000L, 0L, 0L}, 33);
            immediates[1357] = new Pattern(new long[]{70369817935872L, 0x4000400000000000L, 0L, 0L},
                    new long[]{70369817919488L, 0x4000400000000000L, 0L, 0L}, 49);
            immediates[1358] = new Pattern(new long[]{1073758208L, 0x4000400040000000L, 0L, 0L},
                    new long[]{1073758208L, 70369817919488L, 0L, 0L}, 65);
            immediates[1359] = new Pattern(new long[]{16384L, 0x4000400040004000L, 0L, 0L},
                    new long[]{16384L, 0x4000000040004000L, 0L, 0L}, 81);
            immediates[1360] = new Pattern(new long[]{0L, 0x4000400040004000L, 0x4000000000000000L, 0L},
                    new long[]{0L, 0x4000400000004000L, 0x4000000000000000L, 0L}, 97);
            immediates[1361] = new Pattern(new long[]{0L, 70369817935872L, 0x4000400000000000L, 0L},
                    new long[]{0L, 70369817919488L, 0x4000400000000000L, 0L}, 113);
            immediates[1362] = new Pattern(new long[]{0L, 1073758208L, 0x4000400040000000L, 0L},
                    new long[]{0L, 1073758208L, 70369817919488L, 0L}, 129);
            immediates[1363] = new Pattern(new long[]{0L, 16384L, 0x4000400040004000L, 0L},
                    new long[]{0L, 16384L, 0x4000000040004000L, 0L}, 145);
            immediates[1364] = new Pattern(new long[]{0L, 0L, 0x4000400040004000L, 0x4000000000000000L},
                    new long[]{0L, 0L, 0x4000400000004000L, 0x4000000000000000L}, 161);
            immediates[1365] = new Pattern(new long[]{0L, 0L, 70369817935872L, 0x4000400000000000L},
                    new long[]{0L, 0L, 70369817919488L, 0x4000400000000000L}, 177);
            immediates[1366] = new Pattern(new long[]{0L, 0L, 1073758208L, 0x4000400040000000L},
                    new long[]{0L, 0L, 1073758208L, 70369817919488L}, 193);
            immediates[1367] = new Pattern(new long[]{0L, 0L, 16384L, 0x4000400040004000L},
                    new long[]{0L, 0L, 16384L, 0x4000000040004000L}, 209);
            immediates[1368] = new Pattern(new long[]{0x2000200020002000L, 0x2000000000000000L, 0L, 0L},
                    new long[]{0x2000200000002000L, 0x2000000000000000L, 0L, 0L}, 34);
            immediates[1369] = new Pattern(new long[]{35184908967936L, 0x2000200000000000L, 0L, 0L},
                    new long[]{35184908959744L, 0x2000200000000000L, 0L, 0L}, 50);
            immediates[1370] = new Pattern(new long[]{536879104L, 0x2000200020000000L, 0L, 0L},
                    new long[]{536879104L, 35184908959744L, 0L, 0L}, 66);
            immediates[1371] = new Pattern(new long[]{8192L, 0x2000200020002000L, 0L, 0L},
                    new long[]{8192L, 0x2000000020002000L, 0L, 0L}, 82);
            immediates[1372] = new Pattern(new long[]{0L, 0x2000200020002000L, 0x2000000000000000L, 0L},
                    new long[]{0L, 0x2000200000002000L, 0x2000000000000000L, 0L}, 98);
            immediates[1373] = new Pattern(new long[]{0L, 35184908967936L, 0x2000200000000000L, 0L},
                    new long[]{0L, 35184908959744L, 0x2000200000000000L, 0L}, 114);
            immediates[1374] = new Pattern(new long[]{0L, 536879104L, 0x2000200020000000L, 0L},
                    new long[]{0L, 536879104L, 35184908959744L, 0L}, 130);
            immediates[1375] = new Pattern(new long[]{0L, 8192L, 0x2000200020002000L, 0L},
                    new long[]{0L, 8192L, 0x2000000020002000L, 0L}, 146);
            immediates[1376] = new Pattern(new long[]{0L, 0L, 0x2000200020002000L, 0x2000000000000000L},
                    new long[]{0L, 0L, 0x2000200000002000L, 0x2000000000000000L}, 162);
            immediates[1377] = new Pattern(new long[]{0L, 0L, 35184908967936L, 0x2000200000000000L},
                    new long[]{0L, 0L, 35184908959744L, 0x2000200000000000L}, 178);
            immediates[1378] = new Pattern(new long[]{0L, 0L, 536879104L, 0x2000200020000000L},
                    new long[]{0L, 0L, 536879104L, 35184908959744L}, 194);
            immediates[1379] = new Pattern(new long[]{0L, 0L, 8192L, 0x2000200020002000L},
                    new long[]{0L, 0L, 8192L, 0x2000000020002000L}, 210);
            immediates[1380] = new Pattern(new long[]{0x1000100010001000L, 0x1000000000000000L, 0L, 0L},
                    new long[]{0x1000100000001000L, 0x1000000000000000L, 0L, 0L}, 35);
            immediates[1381] = new Pattern(new long[]{17592454483968L, 0x1000100000000000L, 0L, 0L},
                    new long[]{17592454479872L, 0x1000100000000000L, 0L, 0L}, 51);
            immediates[1382] = new Pattern(new long[]{268439552L, 0x1000100010000000L, 0L, 0L},
                    new long[]{268439552L, 17592454479872L, 0L, 0L}, 67);
            immediates[1383] = new Pattern(new long[]{4096L, 0x1000100010001000L, 0L, 0L},
                    new long[]{4096L, 0x1000000010001000L, 0L, 0L}, 83);
            immediates[1384] = new Pattern(new long[]{0L, 0x1000100010001000L, 0x1000000000000000L, 0L},
                    new long[]{0L, 0x1000100000001000L, 0x1000000000000000L, 0L}, 99);
            immediates[1385] = new Pattern(new long[]{0L, 17592454483968L, 0x1000100000000000L, 0L},
                    new long[]{0L, 17592454479872L, 0x1000100000000000L, 0L}, 115);
            immediates[1386] = new Pattern(new long[]{0L, 268439552L, 0x1000100010000000L, 0L},
                    new long[]{0L, 268439552L, 17592454479872L, 0L}, 131);
            immediates[1387] = new Pattern(new long[]{0L, 4096L, 0x1000100010001000L, 0L},
                    new long[]{0L, 4096L, 0x1000000010001000L, 0L}, 147);
            immediates[1388] = new Pattern(new long[]{0L, 0L, 0x1000100010001000L, 0x1000000000000000L},
                    new long[]{0L, 0L, 0x1000100000001000L, 0x1000000000000000L}, 163);
            immediates[1389] = new Pattern(new long[]{0L, 0L, 17592454483968L, 0x1000100000000000L},
                    new long[]{0L, 0L, 17592454479872L, 0x1000100000000000L}, 179);
            immediates[1390] = new Pattern(new long[]{0L, 0L, 268439552L, 0x1000100010000000L},
                    new long[]{0L, 0L, 268439552L, 17592454479872L}, 195);
            immediates[1391] = new Pattern(new long[]{0L, 0L, 4096L, 0x1000100010001000L},
                    new long[]{0L, 0L, 4096L, 0x1000000010001000L}, 211);
            immediates[1392] = new Pattern(new long[]{0x800080008000800L, 0x800000000000000L, 0L, 0L},
                    new long[]{0x800080000000800L, 0x800000000000000L, 0L, 0L}, 36);
            immediates[1393] = new Pattern(new long[]{8796227241984L, 0x800080000000000L, 0L, 0L},
                    new long[]{8796227239936L, 0x800080000000000L, 0L, 0L}, 52);
            immediates[1394] = new Pattern(new long[]{134219776L, 0x800080008000000L, 0L, 0L},
                    new long[]{134219776L, 8796227239936L, 0L, 0L}, 68);
            immediates[1395] = new Pattern(new long[]{2048L, 0x800080008000800L, 0L, 0L},
                    new long[]{2048L, 0x800000008000800L, 0L, 0L}, 84);
            immediates[1396] = new Pattern(new long[]{0L, 0x800080008000800L, 0x800000000000000L, 0L},
                    new long[]{0L, 0x800080000000800L, 0x800000000000000L, 0L}, 100);
            immediates[1397] = new Pattern(new long[]{0L, 8796227241984L, 0x800080000000000L, 0L},
                    new long[]{0L, 8796227239936L, 0x800080000000000L, 0L}, 116);
            immediates[1398] = new Pattern(new long[]{0L, 134219776L, 0x800080008000000L, 0L},
                    new long[]{0L, 134219776L, 8796227239936L, 0L}, 132);
            immediates[1399] = new Pattern(new long[]{0L, 2048L, 0x800080008000800L, 0L},
                    new long[]{0L, 2048L, 0x800000008000800L, 0L}, 148);
            immediates[1400] = new Pattern(new long[]{0L, 0L, 0x800080008000800L, 0x800000000000000L},
                    new long[]{0L, 0L, 0x800080000000800L, 0x800000000000000L}, 164);
            immediates[1401] = new Pattern(new long[]{0L, 0L, 8796227241984L, 0x800080000000000L},
                    new long[]{0L, 0L, 8796227239936L, 0x800080000000000L}, 180);
            immediates[1402] = new Pattern(new long[]{0L, 0L, 134219776L, 0x800080008000000L},
                    new long[]{0L, 0L, 134219776L, 8796227239936L}, 196);
            immediates[1403] = new Pattern(new long[]{0L, 0L, 2048L, 0x800080008000800L},
                    new long[]{0L, 0L, 2048L, 0x800000008000800L}, 212);
            immediates[1404] = new Pattern(new long[]{0x400040004000400L, 0x400000000000000L, 0L, 0L},
                    new long[]{0x400040000000400L, 0x400000000000000L, 0L, 0L}, 37);
            immediates[1405] = new Pattern(new long[]{4398113620992L, 0x400040000000000L, 0L, 0L},
                    new long[]{4398113619968L, 0x400040000000000L, 0L, 0L}, 53);
            immediates[1406] = new Pattern(new long[]{67109888L, 0x400040004000000L, 0L, 0L},
                    new long[]{67109888L, 4398113619968L, 0L, 0L}, 69);
            immediates[1407] = new Pattern(new long[]{1024L, 0x400040004000400L, 0L, 0L},
                    new long[]{1024L, 0x400000004000400L, 0L, 0L}, 85);
            immediates[1408] = new Pattern(new long[]{0L, 0x400040004000400L, 0x400000000000000L, 0L},
                    new long[]{0L, 0x400040000000400L, 0x400000000000000L, 0L}, 101);
            immediates[1409] = new Pattern(new long[]{0L, 4398113620992L, 0x400040000000000L, 0L},
                    new long[]{0L, 4398113619968L, 0x400040000000000L, 0L}, 117);
            immediates[1410] = new Pattern(new long[]{0L, 67109888L, 0x400040004000000L, 0L},
                    new long[]{0L, 67109888L, 4398113619968L, 0L}, 133);
            immediates[1411] = new Pattern(new long[]{0L, 1024L, 0x400040004000400L, 0L},
                    new long[]{0L, 1024L, 0x400000004000400L, 0L}, 149);
            immediates[1412] = new Pattern(new long[]{0L, 0L, 0x400040004000400L, 0x400000000000000L},
                    new long[]{0L, 0L, 0x400040000000400L, 0x400000000000000L}, 165);
            immediates[1413] = new Pattern(new long[]{0L, 0L, 4398113620992L, 0x400040000000000L},
                    new long[]{0L, 0L, 4398113619968L, 0x400040000000000L}, 181);
            immediates[1414] = new Pattern(new long[]{0L, 0L, 67109888L, 0x400040004000000L},
                    new long[]{0L, 0L, 67109888L, 4398113619968L}, 197);
            immediates[1415] = new Pattern(new long[]{0L, 0L, 1024L, 0x400040004000400L},
                    new long[]{0L, 0L, 1024L, 0x400000004000400L}, 213);
            immediates[1416] = new Pattern(new long[]{0x200020002000200L, 0x200000000000000L, 0L, 0L},
                    new long[]{0x200020000000200L, 0x200000000000000L, 0L, 0L}, 38);
            immediates[1417] = new Pattern(new long[]{2199056810496L, 0x200020000000000L, 0L, 0L},
                    new long[]{2199056809984L, 0x200020000000000L, 0L, 0L}, 54);
            immediates[1418] = new Pattern(new long[]{33554944L, 0x200020002000000L, 0L, 0L},
                    new long[]{33554944L, 2199056809984L, 0L, 0L}, 70);
            immediates[1419] = new Pattern(new long[]{512L, 0x200020002000200L, 0L, 0L},
                    new long[]{512L, 0x200000002000200L, 0L, 0L}, 86);
            immediates[1420] = new Pattern(new long[]{0L, 0x200020002000200L, 0x200000000000000L, 0L},
                    new long[]{0L, 0x200020000000200L, 0x200000000000000L, 0L}, 102);
            immediates[1421] = new Pattern(new long[]{0L, 2199056810496L, 0x200020000000000L, 0L},
                    new long[]{0L, 2199056809984L, 0x200020000000000L, 0L}, 118);
            immediates[1422] = new Pattern(new long[]{0L, 33554944L, 0x200020002000000L, 0L},
                    new long[]{0L, 33554944L, 2199056809984L, 0L}, 134);
            immediates[1423] = new Pattern(new long[]{0L, 512L, 0x200020002000200L, 0L},
                    new long[]{0L, 512L, 0x200000002000200L, 0L}, 150);
            immediates[1424] = new Pattern(new long[]{0L, 0L, 0x200020002000200L, 0x200000000000000L},
                    new long[]{0L, 0L, 0x200020000000200L, 0x200000000000000L}, 166);
            immediates[1425] = new Pattern(new long[]{0L, 0L, 2199056810496L, 0x200020000000000L},
                    new long[]{0L, 0L, 2199056809984L, 0x200020000000000L}, 182);
            immediates[1426] = new Pattern(new long[]{0L, 0L, 33554944L, 0x200020002000000L},
                    new long[]{0L, 0L, 33554944L, 2199056809984L}, 198);
            immediates[1427] = new Pattern(new long[]{0L, 0L, 512L, 0x200020002000200L},
                    new long[]{0L, 0L, 512L, 0x200000002000200L}, 214);
            immediates[1428] = new Pattern(new long[]{72058693566333184L, 72057594037927936L, 0L, 0L},
                    new long[]{72058693549555968L, 72057594037927936L, 0L, 0L}, 39);
            immediates[1429] = new Pattern(new long[]{1099528405248L, 72058693549555712L, 0L, 0L},
                    new long[]{1099528404992L, 72058693549555712L, 0L, 0L}, 55);
            immediates[1430] = new Pattern(new long[]{16777472L, 72058693566332928L, 0L, 0L},
                    new long[]{16777472L, 1099528404992L, 0L, 0L}, 71);
            immediates[1431] = new Pattern(new long[]{256L, 72058693566333184L, 0L, 0L},
                    new long[]{256L, 72057594054705408L, 0L, 0L}, 87);
            immediates[1432] = new Pattern(new long[]{0L, 72058693566333184L, 72057594037927936L, 0L},
                    new long[]{0L, 72058693549555968L, 72057594037927936L, 0L}, 103);
            immediates[1433] = new Pattern(new long[]{0L, 1099528405248L, 72058693549555712L, 0L},
                    new long[]{0L, 1099528404992L, 72058693549555712L, 0L}, 119);
            immediates[1434] = new Pattern(new long[]{0L, 16777472L, 72058693566332928L, 0L},
                    new long[]{0L, 16777472L, 1099528404992L, 0L}, 135);
            immediates[1435] = new Pattern(new long[]{0L, 256L, 72058693566333184L, 0L},
                    new long[]{0L, 256L, 72057594054705408L, 0L}, 151);
            immediates[1436] = new Pattern(new long[]{0L, 0L, 72058693566333184L, 72057594037927936L},
                    new long[]{0L, 0L, 72058693549555968L, 72057594037927936L}, 167);
            immediates[1437] = new Pattern(new long[]{0L, 0L, 1099528405248L, 72058693549555712L},
                    new long[]{0L, 0L, 1099528404992L, 72058693549555712L}, 183);
            immediates[1438] = new Pattern(new long[]{0L, 0L, 16777472L, 72058693566332928L},
                    new long[]{0L, 0L, 16777472L, 1099528404992L}, 199);
            immediates[1439] = new Pattern(new long[]{0L, 0L, 256L, 72058693566333184L},
                    new long[]{0L, 0L, 256L, 72057594054705408L}, 215);
            immediates[1440] = new Pattern(new long[]{0x80008000800080L, 0x80000000000000L, 0L, 0L},
                    new long[]{0x80008000000080L, 0x80000000000000L, 0L, 0L}, 40);
            immediates[1441] = new Pattern(new long[]{549764202624L, 0x80008000000000L, 0L, 0L},
                    new long[]{549764202496L, 0x80008000000000L, 0L, 0L}, 56);
            immediates[1442] = new Pattern(new long[]{8388736L, 0x80008000800000L, 0L, 0L},
                    new long[]{8388736L, 549764202496L, 0L, 0L}, 72);
            immediates[1443] = new Pattern(new long[]{128L, 0x80008000800080L, 0L, 0L},
                    new long[]{128L, 0x80000000800080L, 0L, 0L}, 88);
            immediates[1444] = new Pattern(new long[]{0L, 0x80008000800080L, 0x80000000000000L, 0L},
                    new long[]{0L, 0x80008000000080L, 0x80000000000000L, 0L}, 104);
            immediates[1445] = new Pattern(new long[]{0L, 549764202624L, 0x80008000000000L, 0L},
                    new long[]{0L, 549764202496L, 0x80008000000000L, 0L}, 120);
            immediates[1446] = new Pattern(new long[]{0L, 8388736L, 0x80008000800000L, 0L},
                    new long[]{0L, 8388736L, 549764202496L, 0L}, 136);
            immediates[1447] = new Pattern(new long[]{0L, 128L, 0x80008000800080L, 0L},
                    new long[]{0L, 128L, 0x80000000800080L, 0L}, 152);
            immediates[1448] = new Pattern(new long[]{0L, 0L, 0x80008000800080L, 0x80000000000000L},
                    new long[]{0L, 0L, 0x80008000000080L, 0x80000000000000L}, 168);
            immediates[1449] = new Pattern(new long[]{0L, 0L, 549764202624L, 0x80008000000000L},
                    new long[]{0L, 0L, 549764202496L, 0x80008000000000L}, 184);
            immediates[1450] = new Pattern(new long[]{0L, 0L, 8388736L, 0x80008000800000L},
                    new long[]{0L, 0L, 8388736L, 549764202496L}, 200);
            immediates[1451] = new Pattern(new long[]{0L, 0L, 128L, 0x80008000800080L},
                    new long[]{0L, 0L, 128L, 0x80000000800080L}, 216);
            immediates[1452] = new Pattern(new long[]{0x40004000400040L, 0x40000000000000L, 0L, 0L},
                    new long[]{0x40004000000040L, 0x40000000000000L, 0L, 0L}, 41);
            immediates[1453] = new Pattern(new long[]{274882101312L, 0x40004000000000L, 0L, 0L},
                    new long[]{274882101248L, 0x40004000000000L, 0L, 0L}, 57);
            immediates[1454] = new Pattern(new long[]{4194368L, 0x40004000400000L, 0L, 0L},
                    new long[]{4194368L, 274882101248L, 0L, 0L}, 73);
            immediates[1455] =
                    new Pattern(new long[]{64L, 0x40004000400040L, 0L, 0L}, new long[]{64L, 0x40000000400040L, 0L, 0L},
                            89);
            immediates[1456] = new Pattern(new long[]{0L, 0x40004000400040L, 0x40000000000000L, 0L},
                    new long[]{0L, 0x40004000000040L, 0x40000000000000L, 0L}, 105);
            immediates[1457] = new Pattern(new long[]{0L, 274882101312L, 0x40004000000000L, 0L},
                    new long[]{0L, 274882101248L, 0x40004000000000L, 0L}, 121);
            immediates[1458] = new Pattern(new long[]{0L, 4194368L, 0x40004000400000L, 0L},
                    new long[]{0L, 4194368L, 274882101248L, 0L}, 137);
            immediates[1459] =
                    new Pattern(new long[]{0L, 64L, 0x40004000400040L, 0L}, new long[]{0L, 64L, 0x40000000400040L, 0L},
                            153);
            immediates[1460] = new Pattern(new long[]{0L, 0L, 0x40004000400040L, 0x40000000000000L},
                    new long[]{0L, 0L, 0x40004000000040L, 0x40000000000000L}, 169);
            immediates[1461] = new Pattern(new long[]{0L, 0L, 274882101312L, 0x40004000000000L},
                    new long[]{0L, 0L, 274882101248L, 0x40004000000000L}, 185);
            immediates[1462] = new Pattern(new long[]{0L, 0L, 4194368L, 0x40004000400000L},
                    new long[]{0L, 0L, 4194368L, 274882101248L}, 201);
            immediates[1463] =
                    new Pattern(new long[]{0L, 0L, 64L, 0x40004000400040L}, new long[]{0L, 0L, 64L, 0x40000000400040L},
                            217);
            immediates[1464] = new Pattern(new long[]{9007336695791648L, 9007199254740992L, 0L, 0L},
                    new long[]{9007336693694496L, 9007199254740992L, 0L, 0L}, 42);
            immediates[1465] = new Pattern(new long[]{137441050656L, 9007336693694464L, 0L, 0L},
                    new long[]{137441050624L, 9007336693694464L, 0L, 0L}, 58);
            immediates[1466] = new Pattern(new long[]{2097184L, 9007336695791616L, 0L, 0L},
                    new long[]{2097184L, 137441050624L, 0L, 0L}, 74);
            immediates[1467] =
                    new Pattern(new long[]{32L, 9007336695791648L, 0L, 0L}, new long[]{32L, 9007199256838176L, 0L, 0L},
                            90);
            immediates[1468] = new Pattern(new long[]{0L, 9007336695791648L, 9007199254740992L, 0L},
                    new long[]{0L, 9007336693694496L, 9007199254740992L, 0L}, 106);
            immediates[1469] = new Pattern(new long[]{0L, 137441050656L, 9007336693694464L, 0L},
                    new long[]{0L, 137441050624L, 9007336693694464L, 0L}, 122);
            immediates[1470] = new Pattern(new long[]{0L, 2097184L, 9007336695791616L, 0L},
                    new long[]{0L, 2097184L, 137441050624L, 0L}, 138);
            immediates[1471] =
                    new Pattern(new long[]{0L, 32L, 9007336695791648L, 0L}, new long[]{0L, 32L, 9007199256838176L, 0L},
                            154);
            immediates[1472] = new Pattern(new long[]{0L, 0L, 9007336695791648L, 9007199254740992L},
                    new long[]{0L, 0L, 9007336693694496L, 9007199254740992L}, 170);
            immediates[1473] = new Pattern(new long[]{0L, 0L, 137441050656L, 9007336693694464L},
                    new long[]{0L, 0L, 137441050624L, 9007336693694464L}, 186);
            immediates[1474] = new Pattern(new long[]{0L, 0L, 2097184L, 9007336695791616L},
                    new long[]{0L, 0L, 2097184L, 137441050624L}, 202);
            immediates[1475] =
                    new Pattern(new long[]{0L, 0L, 32L, 9007336695791648L}, new long[]{0L, 0L, 32L, 9007199256838176L},
                            218);
            immediates[1476] = new Pattern(new long[]{4503668347895824L, 4503599627370496L, 0L, 0L},
                    new long[]{4503668346847248L, 4503599627370496L, 0L, 0L}, 43);
            immediates[1477] = new Pattern(new long[]{68720525328L, 4503668346847232L, 0L, 0L},
                    new long[]{68720525312L, 4503668346847232L, 0L, 0L}, 59);
            immediates[1478] = new Pattern(new long[]{1048592L, 4503668347895808L, 0L, 0L},
                    new long[]{1048592L, 68720525312L, 0L, 0L}, 75);
            immediates[1479] =
                    new Pattern(new long[]{16L, 4503668347895824L, 0L, 0L}, new long[]{16L, 4503599628419088L, 0L, 0L},
                            91);
            immediates[1480] = new Pattern(new long[]{0L, 4503668347895824L, 4503599627370496L, 0L},
                    new long[]{0L, 4503668346847248L, 4503599627370496L, 0L}, 107);
            immediates[1481] = new Pattern(new long[]{0L, 68720525328L, 4503668346847232L, 0L},
                    new long[]{0L, 68720525312L, 4503668346847232L, 0L}, 123);
            immediates[1482] = new Pattern(new long[]{0L, 1048592L, 4503668347895808L, 0L},
                    new long[]{0L, 1048592L, 68720525312L, 0L}, 139);
            immediates[1483] =
                    new Pattern(new long[]{0L, 16L, 4503668347895824L, 0L}, new long[]{0L, 16L, 4503599628419088L, 0L},
                            155);
            immediates[1484] = new Pattern(new long[]{0L, 0L, 4503668347895824L, 4503599627370496L},
                    new long[]{0L, 0L, 4503668346847248L, 4503599627370496L}, 171);
            immediates[1485] = new Pattern(new long[]{0L, 0L, 68720525328L, 4503668346847232L},
                    new long[]{0L, 0L, 68720525312L, 4503668346847232L}, 187);
            immediates[1486] = new Pattern(new long[]{0L, 0L, 1048592L, 4503668347895808L},
                    new long[]{0L, 0L, 1048592L, 68720525312L}, 203);
            immediates[1487] =
                    new Pattern(new long[]{0L, 0L, 16L, 4503668347895824L}, new long[]{0L, 0L, 16L, 4503599628419088L},
                            219);
            immediates[1488] = new Pattern(new long[]{0x8000800080008L, 0x8000000000000L, 0L, 0L},
                    new long[]{0x8000800000008L, 0x8000000000000L, 0L, 0L}, 44);
            immediates[1489] = new Pattern(new long[]{34360262664L, 0x8000800000000L, 0L, 0L},
                    new long[]{34360262656L, 0x8000800000000L, 0L, 0L}, 60);
            immediates[1490] = new Pattern(new long[]{524296L, 0x8000800080000L, 0L, 0L},
                    new long[]{524296L, 34360262656L, 0L, 0L}, 76);
            immediates[1491] =
                    new Pattern(new long[]{8L, 0x8000800080008L, 0L, 0L}, new long[]{8L, 0x8000000080008L, 0L, 0L}, 92);
            immediates[1492] = new Pattern(new long[]{0L, 0x8000800080008L, 0x8000000000000L, 0L},
                    new long[]{0L, 0x8000800000008L, 0x8000000000000L, 0L}, 108);
            immediates[1493] = new Pattern(new long[]{0L, 34360262664L, 0x8000800000000L, 0L},
                    new long[]{0L, 34360262656L, 0x8000800000000L, 0L}, 124);
            immediates[1494] = new Pattern(new long[]{0L, 524296L, 0x8000800080000L, 0L},
                    new long[]{0L, 524296L, 34360262656L, 0L}, 140);
            immediates[1495] =
                    new Pattern(new long[]{0L, 8L, 0x8000800080008L, 0L}, new long[]{0L, 8L, 0x8000000080008L, 0L},
                            156);
            immediates[1496] = new Pattern(new long[]{0L, 0L, 0x8000800080008L, 0x8000000000000L},
                    new long[]{0L, 0L, 0x8000800000008L, 0x8000000000000L}, 172);
            immediates[1497] = new Pattern(new long[]{0L, 0L, 34360262664L, 0x8000800000000L},
                    new long[]{0L, 0L, 34360262656L, 0x8000800000000L}, 188);
            immediates[1498] = new Pattern(new long[]{0L, 0L, 524296L, 0x8000800080000L},
                    new long[]{0L, 0L, 524296L, 34360262656L}, 204);
            immediates[1499] =
                    new Pattern(new long[]{0L, 0L, 8L, 0x8000800080008L}, new long[]{0L, 0L, 8L, 0x8000000080008L},
                            220);
        }

        private static void initImmediates3() {
            immediates[1500] = new Pattern(new long[]{0x4000400040004L, 0x4000000000000L, 0L, 0L},
                    new long[]{0x4000400000004L, 0x4000000000000L, 0L, 0L}, 45);
            immediates[1501] = new Pattern(new long[]{17180131332L, 0x4000400000000L, 0L, 0L},
                    new long[]{17180131328L, 0x4000400000000L, 0L, 0L}, 61);
            immediates[1502] = new Pattern(new long[]{262148L, 0x4000400040000L, 0L, 0L},
                    new long[]{262148L, 17180131328L, 0L, 0L}, 77);
            immediates[1503] =
                    new Pattern(new long[]{4L, 0x4000400040004L, 0L, 0L}, new long[]{4L, 0x4000000040004L, 0L, 0L}, 93);
            immediates[1504] = new Pattern(new long[]{0L, 0x4000400040004L, 0x4000000000000L, 0L},
                    new long[]{0L, 0x4000400000004L, 0x4000000000000L, 0L}, 109);
            immediates[1505] = new Pattern(new long[]{0L, 17180131332L, 0x4000400000000L, 0L},
                    new long[]{0L, 17180131328L, 0x4000400000000L, 0L}, 125);
            immediates[1506] = new Pattern(new long[]{0L, 262148L, 0x4000400040000L, 0L},
                    new long[]{0L, 262148L, 17180131328L, 0L}, 141);
            immediates[1507] =
                    new Pattern(new long[]{0L, 4L, 0x4000400040004L, 0L}, new long[]{0L, 4L, 0x4000000040004L, 0L},
                            157);
            immediates[1508] = new Pattern(new long[]{0L, 0L, 0x4000400040004L, 0x4000000000000L},
                    new long[]{0L, 0L, 0x4000400000004L, 0x4000000000000L}, 173);
            immediates[1509] = new Pattern(new long[]{0L, 0L, 17180131332L, 0x4000400000000L},
                    new long[]{0L, 0L, 17180131328L, 0x4000400000000L}, 189);
            immediates[1510] = new Pattern(new long[]{0L, 0L, 262148L, 0x4000400040000L},
                    new long[]{0L, 0L, 262148L, 17180131328L}, 205);
            immediates[1511] =
                    new Pattern(new long[]{0L, 0L, 4L, 0x4000400040004L}, new long[]{0L, 0L, 4L, 0x4000000040004L},
                            221);
            immediates[1512] = new Pattern(new long[]{562958543486978L, 562949953421312L, 0L, 0L},
                    new long[]{562958543355906L, 562949953421312L, 0L, 0L}, 46);
            immediates[1513] = new Pattern(new long[]{8590065666L, 562958543355904L, 0L, 0L},
                    new long[]{8590065664L, 562958543355904L, 0L, 0L}, 62);
            immediates[1514] =
                    new Pattern(new long[]{131074L, 562958543486976L, 0L, 0L}, new long[]{131074L, 8590065664L, 0L, 0L},
                            78);
            immediates[1515] =
                    new Pattern(new long[]{2L, 562958543486978L, 0L, 0L}, new long[]{2L, 562949953552386L, 0L, 0L}, 94);
            immediates[1516] = new Pattern(new long[]{0L, 562958543486978L, 562949953421312L, 0L},
                    new long[]{0L, 562958543355906L, 562949953421312L, 0L}, 110);
            immediates[1517] = new Pattern(new long[]{0L, 8590065666L, 562958543355904L, 0L},
                    new long[]{0L, 8590065664L, 562958543355904L, 0L}, 126);
            immediates[1518] =
                    new Pattern(new long[]{0L, 131074L, 562958543486976L, 0L}, new long[]{0L, 131074L, 8590065664L, 0L},
                            142);
            immediates[1519] =
                    new Pattern(new long[]{0L, 2L, 562958543486978L, 0L}, new long[]{0L, 2L, 562949953552386L, 0L},
                            158);
            immediates[1520] = new Pattern(new long[]{0L, 0L, 562958543486978L, 562949953421312L},
                    new long[]{0L, 0L, 562958543355906L, 562949953421312L}, 174);
            immediates[1521] = new Pattern(new long[]{0L, 0L, 8590065666L, 562958543355904L},
                    new long[]{0L, 0L, 8590065664L, 562958543355904L}, 190);
            immediates[1522] =
                    new Pattern(new long[]{0L, 0L, 131074L, 562958543486976L}, new long[]{0L, 0L, 131074L, 8590065664L},
                            206);
            immediates[1523] =
                    new Pattern(new long[]{0L, 0L, 2L, 562958543486978L}, new long[]{0L, 0L, 2L, 562949953552386L},
                            222);
            immediates[1524] = new Pattern(new long[]{281479271743489L, 281474976710656L, 0L, 0L},
                    new long[]{281479271677953L, 281474976710656L, 0L, 0L}, 47);
            immediates[1525] = new Pattern(new long[]{4295032833L, 281479271677952L, 0L, 0L},
                    new long[]{4295032832L, 281479271677952L, 0L, 0L}, 63);
            immediates[1526] =
                    new Pattern(new long[]{65537L, 281479271743488L, 0L, 0L}, new long[]{65537L, 4295032832L, 0L, 0L},
                            79);
            immediates[1527] =
                    new Pattern(new long[]{1L, 281479271743489L, 0L, 0L}, new long[]{1L, 281474976776193L, 0L, 0L}, 95);
            immediates[1528] = new Pattern(new long[]{0L, 281479271743489L, 281474976710656L, 0L},
                    new long[]{0L, 281479271677953L, 281474976710656L, 0L}, 111);
            immediates[1529] = new Pattern(new long[]{0L, 4295032833L, 281479271677952L, 0L},
                    new long[]{0L, 4295032832L, 281479271677952L, 0L}, 127);
            immediates[1530] =
                    new Pattern(new long[]{0L, 65537L, 281479271743488L, 0L}, new long[]{0L, 65537L, 4295032832L, 0L},
                            143);
            immediates[1531] =
                    new Pattern(new long[]{0L, 1L, 281479271743489L, 0L}, new long[]{0L, 1L, 281474976776193L, 0L},
                            159);
            immediates[1532] = new Pattern(new long[]{0L, 0L, 281479271743489L, 281474976710656L},
                    new long[]{0L, 0L, 281479271677953L, 281474976710656L}, 175);
            immediates[1533] = new Pattern(new long[]{0L, 0L, 4295032833L, 281479271677952L},
                    new long[]{0L, 0L, 4295032832L, 281479271677952L}, 191);
            immediates[1534] =
                    new Pattern(new long[]{0L, 0L, 65537L, 281479271743488L}, new long[]{0L, 0L, 65537L, 4295032832L},
                            207);
            immediates[1535] =
                    new Pattern(new long[]{0L, 0L, 1L, 281479271743489L}, new long[]{0L, 0L, 1L, 281474976776193L},
                            223);
            immediates[1536] = new Pattern(new long[]{0x8000800080008000L, 0x8000000000000000L, 0L, 0L},
                    new long[]{0x8000800080000000L, 0x8000000000000000L, 0L, 0L}, 48);
            immediates[1537] = new Pattern(new long[]{0x800080008000L, 0x8000800000000000L, 0L, 0L},
                    new long[]{0x800080008000L, 0x800000000000L, 0L, 0L}, 64);
            immediates[1538] = new Pattern(new long[]{2147516416L, 0x8000800080000000L, 0L, 0L},
                    new long[]{2147516416L, 0x8000000080000000L, 0L, 0L}, 80);
            immediates[1539] = new Pattern(new long[]{32768L, 0x8000800080008000L, 0L, 0L},
                    new long[]{32768L, 0x8000800000008000L, 0L, 0L}, 96);
            immediates[1540] = new Pattern(new long[]{0L, 0x8000800080008000L, 0x8000000000000000L, 0L},
                    new long[]{0L, 0x8000800080000000L, 0x8000000000000000L, 0L}, 112);
            immediates[1541] = new Pattern(new long[]{0L, 0x800080008000L, 0x8000800000000000L, 0L},
                    new long[]{0L, 0x800080008000L, 0x800000000000L, 0L}, 128);
            immediates[1542] = new Pattern(new long[]{0L, 2147516416L, 0x8000800080000000L, 0L},
                    new long[]{0L, 2147516416L, 0x8000000080000000L, 0L}, 144);
            immediates[1543] = new Pattern(new long[]{0L, 32768L, 0x8000800080008000L, 0L},
                    new long[]{0L, 32768L, 0x8000800000008000L, 0L}, 160);
            immediates[1544] = new Pattern(new long[]{0L, 0L, 0x8000800080008000L, 0x8000000000000000L},
                    new long[]{0L, 0L, 0x8000800080000000L, 0x8000000000000000L}, 176);
            immediates[1545] = new Pattern(new long[]{0L, 0L, 0x800080008000L, 0x8000800000000000L},
                    new long[]{0L, 0L, 0x800080008000L, 0x800000000000L}, 192);
            immediates[1546] = new Pattern(new long[]{0L, 0L, 2147516416L, 0x8000800080000000L},
                    new long[]{0L, 0L, 2147516416L, 0x8000000080000000L}, 208);
            immediates[1547] = new Pattern(new long[]{0L, 0L, 32768L, 0x8000800080008000L},
                    new long[]{0L, 0L, 32768L, 0x8000800000008000L}, 224);
            immediates[1548] = new Pattern(new long[]{0x4000400040004000L, 0x4000000000000000L, 0L, 0L},
                    new long[]{0x4000400040000000L, 0x4000000000000000L, 0L, 0L}, 49);
            immediates[1549] = new Pattern(new long[]{70369817935872L, 0x4000400000000000L, 0L, 0L},
                    new long[]{70369817935872L, 70368744177664L, 0L, 0L}, 65);
            immediates[1550] = new Pattern(new long[]{1073758208L, 0x4000400040000000L, 0L, 0L},
                    new long[]{1073758208L, 0x4000000040000000L, 0L, 0L}, 81);
            immediates[1551] = new Pattern(new long[]{16384L, 0x4000400040004000L, 0L, 0L},
                    new long[]{16384L, 0x4000400000004000L, 0L, 0L}, 97);
            immediates[1552] = new Pattern(new long[]{0L, 0x4000400040004000L, 0x4000000000000000L, 0L},
                    new long[]{0L, 0x4000400040000000L, 0x4000000000000000L, 0L}, 113);
            immediates[1553] = new Pattern(new long[]{0L, 70369817935872L, 0x4000400000000000L, 0L},
                    new long[]{0L, 70369817935872L, 70368744177664L, 0L}, 129);
            immediates[1554] = new Pattern(new long[]{0L, 1073758208L, 0x4000400040000000L, 0L},
                    new long[]{0L, 1073758208L, 0x4000000040000000L, 0L}, 145);
            immediates[1555] = new Pattern(new long[]{0L, 16384L, 0x4000400040004000L, 0L},
                    new long[]{0L, 16384L, 0x4000400000004000L, 0L}, 161);
            immediates[1556] = new Pattern(new long[]{0L, 0L, 0x4000400040004000L, 0x4000000000000000L},
                    new long[]{0L, 0L, 0x4000400040000000L, 0x4000000000000000L}, 177);
            immediates[1557] = new Pattern(new long[]{0L, 0L, 70369817935872L, 0x4000400000000000L},
                    new long[]{0L, 0L, 70369817935872L, 70368744177664L}, 193);
            immediates[1558] = new Pattern(new long[]{0L, 0L, 1073758208L, 0x4000400040000000L},
                    new long[]{0L, 0L, 1073758208L, 0x4000000040000000L}, 209);
            immediates[1559] = new Pattern(new long[]{0L, 0L, 16384L, 0x4000400040004000L},
                    new long[]{0L, 0L, 16384L, 0x4000400000004000L}, 225);
            immediates[1560] = new Pattern(new long[]{0x2000200020002000L, 0x2000000000000000L, 0L, 0L},
                    new long[]{0x2000200020000000L, 0x2000000000000000L, 0L, 0L}, 50);
            immediates[1561] = new Pattern(new long[]{35184908967936L, 0x2000200000000000L, 0L, 0L},
                    new long[]{35184908967936L, 35184372088832L, 0L, 0L}, 66);
            immediates[1562] = new Pattern(new long[]{536879104L, 0x2000200020000000L, 0L, 0L},
                    new long[]{536879104L, 0x2000000020000000L, 0L, 0L}, 82);
            immediates[1563] = new Pattern(new long[]{8192L, 0x2000200020002000L, 0L, 0L},
                    new long[]{8192L, 0x2000200000002000L, 0L, 0L}, 98);
            immediates[1564] = new Pattern(new long[]{0L, 0x2000200020002000L, 0x2000000000000000L, 0L},
                    new long[]{0L, 0x2000200020000000L, 0x2000000000000000L, 0L}, 114);
            immediates[1565] = new Pattern(new long[]{0L, 35184908967936L, 0x2000200000000000L, 0L},
                    new long[]{0L, 35184908967936L, 35184372088832L, 0L}, 130);
            immediates[1566] = new Pattern(new long[]{0L, 536879104L, 0x2000200020000000L, 0L},
                    new long[]{0L, 536879104L, 0x2000000020000000L, 0L}, 146);
            immediates[1567] = new Pattern(new long[]{0L, 8192L, 0x2000200020002000L, 0L},
                    new long[]{0L, 8192L, 0x2000200000002000L, 0L}, 162);
            immediates[1568] = new Pattern(new long[]{0L, 0L, 0x2000200020002000L, 0x2000000000000000L},
                    new long[]{0L, 0L, 0x2000200020000000L, 0x2000000000000000L}, 178);
            immediates[1569] = new Pattern(new long[]{0L, 0L, 35184908967936L, 0x2000200000000000L},
                    new long[]{0L, 0L, 35184908967936L, 35184372088832L}, 194);
            immediates[1570] = new Pattern(new long[]{0L, 0L, 536879104L, 0x2000200020000000L},
                    new long[]{0L, 0L, 536879104L, 0x2000000020000000L}, 210);
            immediates[1571] = new Pattern(new long[]{0L, 0L, 8192L, 0x2000200020002000L},
                    new long[]{0L, 0L, 8192L, 0x2000200000002000L}, 226);
            immediates[1572] = new Pattern(new long[]{0x1000100010001000L, 0x1000000000000000L, 0L, 0L},
                    new long[]{0x1000100010000000L, 0x1000000000000000L, 0L, 0L}, 51);
            immediates[1573] = new Pattern(new long[]{17592454483968L, 0x1000100000000000L, 0L, 0L},
                    new long[]{17592454483968L, 17592186044416L, 0L, 0L}, 67);
            immediates[1574] = new Pattern(new long[]{268439552L, 0x1000100010000000L, 0L, 0L},
                    new long[]{268439552L, 0x1000000010000000L, 0L, 0L}, 83);
            immediates[1575] = new Pattern(new long[]{4096L, 0x1000100010001000L, 0L, 0L},
                    new long[]{4096L, 0x1000100000001000L, 0L, 0L}, 99);
            immediates[1576] = new Pattern(new long[]{0L, 0x1000100010001000L, 0x1000000000000000L, 0L},
                    new long[]{0L, 0x1000100010000000L, 0x1000000000000000L, 0L}, 115);
            immediates[1577] = new Pattern(new long[]{0L, 17592454483968L, 0x1000100000000000L, 0L},
                    new long[]{0L, 17592454483968L, 17592186044416L, 0L}, 131);
            immediates[1578] = new Pattern(new long[]{0L, 268439552L, 0x1000100010000000L, 0L},
                    new long[]{0L, 268439552L, 0x1000000010000000L, 0L}, 147);
            immediates[1579] = new Pattern(new long[]{0L, 4096L, 0x1000100010001000L, 0L},
                    new long[]{0L, 4096L, 0x1000100000001000L, 0L}, 163);
            immediates[1580] = new Pattern(new long[]{0L, 0L, 0x1000100010001000L, 0x1000000000000000L},
                    new long[]{0L, 0L, 0x1000100010000000L, 0x1000000000000000L}, 179);
            immediates[1581] = new Pattern(new long[]{0L, 0L, 17592454483968L, 0x1000100000000000L},
                    new long[]{0L, 0L, 17592454483968L, 17592186044416L}, 195);
            immediates[1582] = new Pattern(new long[]{0L, 0L, 268439552L, 0x1000100010000000L},
                    new long[]{0L, 0L, 268439552L, 0x1000000010000000L}, 211);
            immediates[1583] = new Pattern(new long[]{0L, 0L, 4096L, 0x1000100010001000L},
                    new long[]{0L, 0L, 4096L, 0x1000100000001000L}, 227);
            immediates[1584] = new Pattern(new long[]{0x800080008000800L, 0x800000000000000L, 0L, 0L},
                    new long[]{0x800080008000000L, 0x800000000000000L, 0L, 0L}, 52);
            immediates[1585] = new Pattern(new long[]{8796227241984L, 0x800080000000000L, 0L, 0L},
                    new long[]{8796227241984L, 8796093022208L, 0L, 0L}, 68);
            immediates[1586] = new Pattern(new long[]{134219776L, 0x800080008000000L, 0L, 0L},
                    new long[]{134219776L, 0x800000008000000L, 0L, 0L}, 84);
            immediates[1587] = new Pattern(new long[]{2048L, 0x800080008000800L, 0L, 0L},
                    new long[]{2048L, 0x800080000000800L, 0L, 0L}, 100);
            immediates[1588] = new Pattern(new long[]{0L, 0x800080008000800L, 0x800000000000000L, 0L},
                    new long[]{0L, 0x800080008000000L, 0x800000000000000L, 0L}, 116);
            immediates[1589] = new Pattern(new long[]{0L, 8796227241984L, 0x800080000000000L, 0L},
                    new long[]{0L, 8796227241984L, 8796093022208L, 0L}, 132);
            immediates[1590] = new Pattern(new long[]{0L, 134219776L, 0x800080008000000L, 0L},
                    new long[]{0L, 134219776L, 0x800000008000000L, 0L}, 148);
            immediates[1591] = new Pattern(new long[]{0L, 2048L, 0x800080008000800L, 0L},
                    new long[]{0L, 2048L, 0x800080000000800L, 0L}, 164);
            immediates[1592] = new Pattern(new long[]{0L, 0L, 0x800080008000800L, 0x800000000000000L},
                    new long[]{0L, 0L, 0x800080008000000L, 0x800000000000000L}, 180);
            immediates[1593] = new Pattern(new long[]{0L, 0L, 8796227241984L, 0x800080000000000L},
                    new long[]{0L, 0L, 8796227241984L, 8796093022208L}, 196);
            immediates[1594] = new Pattern(new long[]{0L, 0L, 134219776L, 0x800080008000000L},
                    new long[]{0L, 0L, 134219776L, 0x800000008000000L}, 212);
            immediates[1595] = new Pattern(new long[]{0L, 0L, 2048L, 0x800080008000800L},
                    new long[]{0L, 0L, 2048L, 0x800080000000800L}, 228);
            immediates[1596] = new Pattern(new long[]{0x400040004000400L, 0x400000000000000L, 0L, 0L},
                    new long[]{0x400040004000000L, 0x400000000000000L, 0L, 0L}, 53);
            immediates[1597] = new Pattern(new long[]{4398113620992L, 0x400040000000000L, 0L, 0L},
                    new long[]{4398113620992L, 4398046511104L, 0L, 0L}, 69);
            immediates[1598] = new Pattern(new long[]{67109888L, 0x400040004000000L, 0L, 0L},
                    new long[]{67109888L, 0x400000004000000L, 0L, 0L}, 85);
            immediates[1599] = new Pattern(new long[]{1024L, 0x400040004000400L, 0L, 0L},
                    new long[]{1024L, 0x400040000000400L, 0L, 0L}, 101);
            immediates[1600] = new Pattern(new long[]{0L, 0x400040004000400L, 0x400000000000000L, 0L},
                    new long[]{0L, 0x400040004000000L, 0x400000000000000L, 0L}, 117);
            immediates[1601] = new Pattern(new long[]{0L, 4398113620992L, 0x400040000000000L, 0L},
                    new long[]{0L, 4398113620992L, 4398046511104L, 0L}, 133);
            immediates[1602] = new Pattern(new long[]{0L, 67109888L, 0x400040004000000L, 0L},
                    new long[]{0L, 67109888L, 0x400000004000000L, 0L}, 149);
            immediates[1603] = new Pattern(new long[]{0L, 1024L, 0x400040004000400L, 0L},
                    new long[]{0L, 1024L, 0x400040000000400L, 0L}, 165);
            immediates[1604] = new Pattern(new long[]{0L, 0L, 0x400040004000400L, 0x400000000000000L},
                    new long[]{0L, 0L, 0x400040004000000L, 0x400000000000000L}, 181);
            immediates[1605] = new Pattern(new long[]{0L, 0L, 4398113620992L, 0x400040000000000L},
                    new long[]{0L, 0L, 4398113620992L, 4398046511104L}, 197);
            immediates[1606] = new Pattern(new long[]{0L, 0L, 67109888L, 0x400040004000000L},
                    new long[]{0L, 0L, 67109888L, 0x400000004000000L}, 213);
            immediates[1607] = new Pattern(new long[]{0L, 0L, 1024L, 0x400040004000400L},
                    new long[]{0L, 0L, 1024L, 0x400040000000400L}, 229);
            immediates[1608] = new Pattern(new long[]{0x200020002000200L, 0x200000000000000L, 0L, 0L},
                    new long[]{0x200020002000000L, 0x200000000000000L, 0L, 0L}, 54);
            immediates[1609] = new Pattern(new long[]{2199056810496L, 0x200020000000000L, 0L, 0L},
                    new long[]{2199056810496L, 2199023255552L, 0L, 0L}, 70);
            immediates[1610] = new Pattern(new long[]{33554944L, 0x200020002000000L, 0L, 0L},
                    new long[]{33554944L, 0x200000002000000L, 0L, 0L}, 86);
            immediates[1611] = new Pattern(new long[]{512L, 0x200020002000200L, 0L, 0L},
                    new long[]{512L, 0x200020000000200L, 0L, 0L}, 102);
            immediates[1612] = new Pattern(new long[]{0L, 0x200020002000200L, 0x200000000000000L, 0L},
                    new long[]{0L, 0x200020002000000L, 0x200000000000000L, 0L}, 118);
            immediates[1613] = new Pattern(new long[]{0L, 2199056810496L, 0x200020000000000L, 0L},
                    new long[]{0L, 2199056810496L, 2199023255552L, 0L}, 134);
            immediates[1614] = new Pattern(new long[]{0L, 33554944L, 0x200020002000000L, 0L},
                    new long[]{0L, 33554944L, 0x200000002000000L, 0L}, 150);
            immediates[1615] = new Pattern(new long[]{0L, 512L, 0x200020002000200L, 0L},
                    new long[]{0L, 512L, 0x200020000000200L, 0L}, 166);
            immediates[1616] = new Pattern(new long[]{0L, 0L, 0x200020002000200L, 0x200000000000000L},
                    new long[]{0L, 0L, 0x200020002000000L, 0x200000000000000L}, 182);
            immediates[1617] = new Pattern(new long[]{0L, 0L, 2199056810496L, 0x200020000000000L},
                    new long[]{0L, 0L, 2199056810496L, 2199023255552L}, 198);
            immediates[1618] = new Pattern(new long[]{0L, 0L, 33554944L, 0x200020002000000L},
                    new long[]{0L, 0L, 33554944L, 0x200000002000000L}, 214);
            immediates[1619] = new Pattern(new long[]{0L, 0L, 512L, 0x200020002000200L},
                    new long[]{0L, 0L, 512L, 0x200020000000200L}, 230);
            immediates[1620] = new Pattern(new long[]{72058693566333184L, 72057594037927936L, 0L, 0L},
                    new long[]{72058693566332928L, 72057594037927936L, 0L, 0L}, 55);
            immediates[1621] = new Pattern(new long[]{1099528405248L, 72058693549555712L, 0L, 0L},
                    new long[]{1099528405248L, 1099511627776L, 0L, 0L}, 71);
            immediates[1622] = new Pattern(new long[]{16777472L, 72058693566332928L, 0L, 0L},
                    new long[]{16777472L, 72057594054705152L, 0L, 0L}, 87);
            immediates[1623] = new Pattern(new long[]{256L, 72058693566333184L, 0L, 0L},
                    new long[]{256L, 72058693549555968L, 0L, 0L}, 103);
            immediates[1624] = new Pattern(new long[]{0L, 72058693566333184L, 72057594037927936L, 0L},
                    new long[]{0L, 72058693566332928L, 72057594037927936L, 0L}, 119);
            immediates[1625] = new Pattern(new long[]{0L, 1099528405248L, 72058693549555712L, 0L},
                    new long[]{0L, 1099528405248L, 1099511627776L, 0L}, 135);
            immediates[1626] = new Pattern(new long[]{0L, 16777472L, 72058693566332928L, 0L},
                    new long[]{0L, 16777472L, 72057594054705152L, 0L}, 151);
            immediates[1627] = new Pattern(new long[]{0L, 256L, 72058693566333184L, 0L},
                    new long[]{0L, 256L, 72058693549555968L, 0L}, 167);
            immediates[1628] = new Pattern(new long[]{0L, 0L, 72058693566333184L, 72057594037927936L},
                    new long[]{0L, 0L, 72058693566332928L, 72057594037927936L}, 183);
            immediates[1629] = new Pattern(new long[]{0L, 0L, 1099528405248L, 72058693549555712L},
                    new long[]{0L, 0L, 1099528405248L, 1099511627776L}, 199);
            immediates[1630] = new Pattern(new long[]{0L, 0L, 16777472L, 72058693566332928L},
                    new long[]{0L, 0L, 16777472L, 72057594054705152L}, 215);
            immediates[1631] = new Pattern(new long[]{0L, 0L, 256L, 72058693566333184L},
                    new long[]{0L, 0L, 256L, 72058693549555968L}, 231);
            immediates[1632] = new Pattern(new long[]{0x80008000800080L, 0x80000000000000L, 0L, 0L},
                    new long[]{0x80008000800000L, 0x80000000000000L, 0L, 0L}, 56);
            immediates[1633] = new Pattern(new long[]{549764202624L, 0x80008000000000L, 0L, 0L},
                    new long[]{549764202624L, 549755813888L, 0L, 0L}, 72);
            immediates[1634] = new Pattern(new long[]{8388736L, 0x80008000800000L, 0L, 0L},
                    new long[]{8388736L, 0x80000000800000L, 0L, 0L}, 88);
            immediates[1635] = new Pattern(new long[]{128L, 0x80008000800080L, 0L, 0L},
                    new long[]{128L, 0x80008000000080L, 0L, 0L}, 104);
            immediates[1636] = new Pattern(new long[]{0L, 0x80008000800080L, 0x80000000000000L, 0L},
                    new long[]{0L, 0x80008000800000L, 0x80000000000000L, 0L}, 120);
            immediates[1637] = new Pattern(new long[]{0L, 549764202624L, 0x80008000000000L, 0L},
                    new long[]{0L, 549764202624L, 549755813888L, 0L}, 136);
            immediates[1638] = new Pattern(new long[]{0L, 8388736L, 0x80008000800000L, 0L},
                    new long[]{0L, 8388736L, 0x80000000800000L, 0L}, 152);
            immediates[1639] = new Pattern(new long[]{0L, 128L, 0x80008000800080L, 0L},
                    new long[]{0L, 128L, 0x80008000000080L, 0L}, 168);
            immediates[1640] = new Pattern(new long[]{0L, 0L, 0x80008000800080L, 0x80000000000000L},
                    new long[]{0L, 0L, 0x80008000800000L, 0x80000000000000L}, 184);
            immediates[1641] = new Pattern(new long[]{0L, 0L, 549764202624L, 0x80008000000000L},
                    new long[]{0L, 0L, 549764202624L, 549755813888L}, 200);
            immediates[1642] = new Pattern(new long[]{0L, 0L, 8388736L, 0x80008000800000L},
                    new long[]{0L, 0L, 8388736L, 0x80000000800000L}, 216);
            immediates[1643] = new Pattern(new long[]{0L, 0L, 128L, 0x80008000800080L},
                    new long[]{0L, 0L, 128L, 0x80008000000080L}, 232);
            immediates[1644] = new Pattern(new long[]{0x40004000400040L, 0x40000000000000L, 0L, 0L},
                    new long[]{0x40004000400000L, 0x40000000000000L, 0L, 0L}, 57);
            immediates[1645] = new Pattern(new long[]{274882101312L, 0x40004000000000L, 0L, 0L},
                    new long[]{274882101312L, 274877906944L, 0L, 0L}, 73);
            immediates[1646] = new Pattern(new long[]{4194368L, 0x40004000400000L, 0L, 0L},
                    new long[]{4194368L, 0x40000000400000L, 0L, 0L}, 89);
            immediates[1647] =
                    new Pattern(new long[]{64L, 0x40004000400040L, 0L, 0L}, new long[]{64L, 0x40004000000040L, 0L, 0L},
                            105);
            immediates[1648] = new Pattern(new long[]{0L, 0x40004000400040L, 0x40000000000000L, 0L},
                    new long[]{0L, 0x40004000400000L, 0x40000000000000L, 0L}, 121);
            immediates[1649] = new Pattern(new long[]{0L, 274882101312L, 0x40004000000000L, 0L},
                    new long[]{0L, 274882101312L, 274877906944L, 0L}, 137);
            immediates[1650] = new Pattern(new long[]{0L, 4194368L, 0x40004000400000L, 0L},
                    new long[]{0L, 4194368L, 0x40000000400000L, 0L}, 153);
            immediates[1651] =
                    new Pattern(new long[]{0L, 64L, 0x40004000400040L, 0L}, new long[]{0L, 64L, 0x40004000000040L, 0L},
                            169);
            immediates[1652] = new Pattern(new long[]{0L, 0L, 0x40004000400040L, 0x40000000000000L},
                    new long[]{0L, 0L, 0x40004000400000L, 0x40000000000000L}, 185);
            immediates[1653] = new Pattern(new long[]{0L, 0L, 274882101312L, 0x40004000000000L},
                    new long[]{0L, 0L, 274882101312L, 274877906944L}, 201);
            immediates[1654] = new Pattern(new long[]{0L, 0L, 4194368L, 0x40004000400000L},
                    new long[]{0L, 0L, 4194368L, 0x40000000400000L}, 217);
            immediates[1655] =
                    new Pattern(new long[]{0L, 0L, 64L, 0x40004000400040L}, new long[]{0L, 0L, 64L, 0x40004000000040L},
                            233);
            immediates[1656] = new Pattern(new long[]{9007336695791648L, 9007199254740992L, 0L, 0L},
                    new long[]{9007336695791616L, 9007199254740992L, 0L, 0L}, 58);
            immediates[1657] = new Pattern(new long[]{137441050656L, 9007336693694464L, 0L, 0L},
                    new long[]{137441050656L, 137438953472L, 0L, 0L}, 74);
            immediates[1658] = new Pattern(new long[]{2097184L, 9007336695791616L, 0L, 0L},
                    new long[]{2097184L, 9007199256838144L, 0L, 0L}, 90);
            immediates[1659] =
                    new Pattern(new long[]{32L, 9007336695791648L, 0L, 0L}, new long[]{32L, 9007336693694496L, 0L, 0L},
                            106);
            immediates[1660] = new Pattern(new long[]{0L, 9007336695791648L, 9007199254740992L, 0L},
                    new long[]{0L, 9007336695791616L, 9007199254740992L, 0L}, 122);
            immediates[1661] = new Pattern(new long[]{0L, 137441050656L, 9007336693694464L, 0L},
                    new long[]{0L, 137441050656L, 137438953472L, 0L}, 138);
            immediates[1662] = new Pattern(new long[]{0L, 2097184L, 9007336695791616L, 0L},
                    new long[]{0L, 2097184L, 9007199256838144L, 0L}, 154);
            immediates[1663] =
                    new Pattern(new long[]{0L, 32L, 9007336695791648L, 0L}, new long[]{0L, 32L, 9007336693694496L, 0L},
                            170);
            immediates[1664] = new Pattern(new long[]{0L, 0L, 9007336695791648L, 9007199254740992L},
                    new long[]{0L, 0L, 9007336695791616L, 9007199254740992L}, 186);
            immediates[1665] = new Pattern(new long[]{0L, 0L, 137441050656L, 9007336693694464L},
                    new long[]{0L, 0L, 137441050656L, 137438953472L}, 202);
            immediates[1666] = new Pattern(new long[]{0L, 0L, 2097184L, 9007336695791616L},
                    new long[]{0L, 0L, 2097184L, 9007199256838144L}, 218);
            immediates[1667] =
                    new Pattern(new long[]{0L, 0L, 32L, 9007336695791648L}, new long[]{0L, 0L, 32L, 9007336693694496L},
                            234);
            immediates[1668] = new Pattern(new long[]{4503668347895824L, 4503599627370496L, 0L, 0L},
                    new long[]{4503668347895808L, 4503599627370496L, 0L, 0L}, 59);
            immediates[1669] = new Pattern(new long[]{68720525328L, 4503668346847232L, 0L, 0L},
                    new long[]{68720525328L, 68719476736L, 0L, 0L}, 75);
            immediates[1670] = new Pattern(new long[]{1048592L, 4503668347895808L, 0L, 0L},
                    new long[]{1048592L, 4503599628419072L, 0L, 0L}, 91);
            immediates[1671] =
                    new Pattern(new long[]{16L, 4503668347895824L, 0L, 0L}, new long[]{16L, 4503668346847248L, 0L, 0L},
                            107);
            immediates[1672] = new Pattern(new long[]{0L, 4503668347895824L, 4503599627370496L, 0L},
                    new long[]{0L, 4503668347895808L, 4503599627370496L, 0L}, 123);
            immediates[1673] = new Pattern(new long[]{0L, 68720525328L, 4503668346847232L, 0L},
                    new long[]{0L, 68720525328L, 68719476736L, 0L}, 139);
            immediates[1674] = new Pattern(new long[]{0L, 1048592L, 4503668347895808L, 0L},
                    new long[]{0L, 1048592L, 4503599628419072L, 0L}, 155);
            immediates[1675] =
                    new Pattern(new long[]{0L, 16L, 4503668347895824L, 0L}, new long[]{0L, 16L, 4503668346847248L, 0L},
                            171);
            immediates[1676] = new Pattern(new long[]{0L, 0L, 4503668347895824L, 4503599627370496L},
                    new long[]{0L, 0L, 4503668347895808L, 4503599627370496L}, 187);
            immediates[1677] = new Pattern(new long[]{0L, 0L, 68720525328L, 4503668346847232L},
                    new long[]{0L, 0L, 68720525328L, 68719476736L}, 203);
            immediates[1678] = new Pattern(new long[]{0L, 0L, 1048592L, 4503668347895808L},
                    new long[]{0L, 0L, 1048592L, 4503599628419072L}, 219);
            immediates[1679] =
                    new Pattern(new long[]{0L, 0L, 16L, 4503668347895824L}, new long[]{0L, 0L, 16L, 4503668346847248L},
                            235);
            immediates[1680] = new Pattern(new long[]{0x8000800080008L, 0x8000000000000L, 0L, 0L},
                    new long[]{0x8000800080000L, 0x8000000000000L, 0L, 0L}, 60);
            immediates[1681] = new Pattern(new long[]{34360262664L, 0x8000800000000L, 0L, 0L},
                    new long[]{34360262664L, 34359738368L, 0L, 0L}, 76);
            immediates[1682] = new Pattern(new long[]{524296L, 0x8000800080000L, 0L, 0L},
                    new long[]{524296L, 0x8000000080000L, 0L, 0L}, 92);
            immediates[1683] =
                    new Pattern(new long[]{8L, 0x8000800080008L, 0L, 0L}, new long[]{8L, 0x8000800000008L, 0L, 0L},
                            108);
            immediates[1684] = new Pattern(new long[]{0L, 0x8000800080008L, 0x8000000000000L, 0L},
                    new long[]{0L, 0x8000800080000L, 0x8000000000000L, 0L}, 124);
            immediates[1685] = new Pattern(new long[]{0L, 34360262664L, 0x8000800000000L, 0L},
                    new long[]{0L, 34360262664L, 34359738368L, 0L}, 140);
            immediates[1686] = new Pattern(new long[]{0L, 524296L, 0x8000800080000L, 0L},
                    new long[]{0L, 524296L, 0x8000000080000L, 0L}, 156);
            immediates[1687] =
                    new Pattern(new long[]{0L, 8L, 0x8000800080008L, 0L}, new long[]{0L, 8L, 0x8000800000008L, 0L},
                            172);
            immediates[1688] = new Pattern(new long[]{0L, 0L, 0x8000800080008L, 0x8000000000000L},
                    new long[]{0L, 0L, 0x8000800080000L, 0x8000000000000L}, 188);
            immediates[1689] = new Pattern(new long[]{0L, 0L, 34360262664L, 0x8000800000000L},
                    new long[]{0L, 0L, 34360262664L, 34359738368L}, 204);
            immediates[1690] = new Pattern(new long[]{0L, 0L, 524296L, 0x8000800080000L},
                    new long[]{0L, 0L, 524296L, 0x8000000080000L}, 220);
            immediates[1691] =
                    new Pattern(new long[]{0L, 0L, 8L, 0x8000800080008L}, new long[]{0L, 0L, 8L, 0x8000800000008L},
                            236);
            immediates[1692] = new Pattern(new long[]{0x4000400040004L, 0x4000000000000L, 0L, 0L},
                    new long[]{0x4000400040000L, 0x4000000000000L, 0L, 0L}, 61);
            immediates[1693] = new Pattern(new long[]{17180131332L, 0x4000400000000L, 0L, 0L},
                    new long[]{17180131332L, 17179869184L, 0L, 0L}, 77);
            immediates[1694] = new Pattern(new long[]{262148L, 0x4000400040000L, 0L, 0L},
                    new long[]{262148L, 0x4000000040000L, 0L, 0L}, 93);
            immediates[1695] =
                    new Pattern(new long[]{4L, 0x4000400040004L, 0L, 0L}, new long[]{4L, 0x4000400000004L, 0L, 0L},
                            109);
            immediates[1696] = new Pattern(new long[]{0L, 0x4000400040004L, 0x4000000000000L, 0L},
                    new long[]{0L, 0x4000400040000L, 0x4000000000000L, 0L}, 125);
            immediates[1697] = new Pattern(new long[]{0L, 17180131332L, 0x4000400000000L, 0L},
                    new long[]{0L, 17180131332L, 17179869184L, 0L}, 141);
            immediates[1698] = new Pattern(new long[]{0L, 262148L, 0x4000400040000L, 0L},
                    new long[]{0L, 262148L, 0x4000000040000L, 0L}, 157);
            immediates[1699] =
                    new Pattern(new long[]{0L, 4L, 0x4000400040004L, 0L}, new long[]{0L, 4L, 0x4000400000004L, 0L},
                            173);
            immediates[1700] = new Pattern(new long[]{0L, 0L, 0x4000400040004L, 0x4000000000000L},
                    new long[]{0L, 0L, 0x4000400040000L, 0x4000000000000L}, 189);
            immediates[1701] = new Pattern(new long[]{0L, 0L, 17180131332L, 0x4000400000000L},
                    new long[]{0L, 0L, 17180131332L, 17179869184L}, 205);
            immediates[1702] = new Pattern(new long[]{0L, 0L, 262148L, 0x4000400040000L},
                    new long[]{0L, 0L, 262148L, 0x4000000040000L}, 221);
            immediates[1703] =
                    new Pattern(new long[]{0L, 0L, 4L, 0x4000400040004L}, new long[]{0L, 0L, 4L, 0x4000400000004L},
                            237);
            immediates[1704] = new Pattern(new long[]{562958543486978L, 562949953421312L, 0L, 0L},
                    new long[]{562958543486976L, 562949953421312L, 0L, 0L}, 62);
            immediates[1705] = new Pattern(new long[]{8590065666L, 562958543355904L, 0L, 0L},
                    new long[]{8590065666L, 8589934592L, 0L, 0L}, 78);
            immediates[1706] = new Pattern(new long[]{131074L, 562958543486976L, 0L, 0L},
                    new long[]{131074L, 562949953552384L, 0L, 0L}, 94);
            immediates[1707] =
                    new Pattern(new long[]{2L, 562958543486978L, 0L, 0L}, new long[]{2L, 562958543355906L, 0L, 0L},
                            110);
            immediates[1708] = new Pattern(new long[]{0L, 562958543486978L, 562949953421312L, 0L},
                    new long[]{0L, 562958543486976L, 562949953421312L, 0L}, 126);
            immediates[1709] = new Pattern(new long[]{0L, 8590065666L, 562958543355904L, 0L},
                    new long[]{0L, 8590065666L, 8589934592L, 0L}, 142);
            immediates[1710] = new Pattern(new long[]{0L, 131074L, 562958543486976L, 0L},
                    new long[]{0L, 131074L, 562949953552384L, 0L}, 158);
            immediates[1711] =
                    new Pattern(new long[]{0L, 2L, 562958543486978L, 0L}, new long[]{0L, 2L, 562958543355906L, 0L},
                            174);
            immediates[1712] = new Pattern(new long[]{0L, 0L, 562958543486978L, 562949953421312L},
                    new long[]{0L, 0L, 562958543486976L, 562949953421312L}, 190);
            immediates[1713] = new Pattern(new long[]{0L, 0L, 8590065666L, 562958543355904L},
                    new long[]{0L, 0L, 8590065666L, 8589934592L}, 206);
            immediates[1714] = new Pattern(new long[]{0L, 0L, 131074L, 562958543486976L},
                    new long[]{0L, 0L, 131074L, 562949953552384L}, 222);
            immediates[1715] =
                    new Pattern(new long[]{0L, 0L, 2L, 562958543486978L}, new long[]{0L, 0L, 2L, 562958543355906L},
                            238);
            immediates[1716] = new Pattern(new long[]{281479271743489L, 281474976710656L, 0L, 0L},
                    new long[]{281479271743488L, 281474976710656L, 0L, 0L}, 63);
            immediates[1717] = new Pattern(new long[]{4295032833L, 281479271677952L, 0L, 0L},
                    new long[]{4295032833L, 4294967296L, 0L, 0L}, 79);
            immediates[1718] = new Pattern(new long[]{65537L, 281479271743488L, 0L, 0L},
                    new long[]{65537L, 281474976776192L, 0L, 0L}, 95);
            immediates[1719] =
                    new Pattern(new long[]{1L, 281479271743489L, 0L, 0L}, new long[]{1L, 281479271677953L, 0L, 0L},
                            111);
            immediates[1720] = new Pattern(new long[]{0L, 281479271743489L, 281474976710656L, 0L},
                    new long[]{0L, 281479271743488L, 281474976710656L, 0L}, 127);
            immediates[1721] = new Pattern(new long[]{0L, 4295032833L, 281479271677952L, 0L},
                    new long[]{0L, 4295032833L, 4294967296L, 0L}, 143);
            immediates[1722] = new Pattern(new long[]{0L, 65537L, 281479271743488L, 0L},
                    new long[]{0L, 65537L, 281474976776192L, 0L}, 159);
            immediates[1723] =
                    new Pattern(new long[]{0L, 1L, 281479271743489L, 0L}, new long[]{0L, 1L, 281479271677953L, 0L},
                            175);
            immediates[1724] = new Pattern(new long[]{0L, 0L, 281479271743489L, 281474976710656L},
                    new long[]{0L, 0L, 281479271743488L, 281474976710656L}, 191);
            immediates[1725] = new Pattern(new long[]{0L, 0L, 4295032833L, 281479271677952L},
                    new long[]{0L, 0L, 4295032833L, 4294967296L}, 207);
            immediates[1726] = new Pattern(new long[]{0L, 0L, 65537L, 281479271743488L},
                    new long[]{0L, 0L, 65537L, 281474976776192L}, 223);
            immediates[1727] =
                    new Pattern(new long[]{0L, 0L, 1L, 281479271743489L}, new long[]{0L, 0L, 1L, 281479271677953L},
                            239);
            immediates[1728] = new Pattern(new long[]{0x8000800080008000L, 0x8000000000000000L, 0L, 0L},
                    new long[]{0x8000800080008000L, 0L, 0L, 0L}, 64);
            immediates[1729] = new Pattern(new long[]{0x800080008000L, 0x8000800000000000L, 0L, 0L},
                    new long[]{0x800080008000L, 0x8000000000000000L, 0L, 0L}, 80);
            immediates[1730] = new Pattern(new long[]{2147516416L, 0x8000800080000000L, 0L, 0L},
                    new long[]{2147516416L, 0x8000800000000000L, 0L, 0L}, 96);
            immediates[1731] = new Pattern(new long[]{32768L, 0x8000800080008000L, 0L, 0L},
                    new long[]{32768L, 0x8000800080000000L, 0L, 0L}, 112);
            immediates[1732] = new Pattern(new long[]{0L, 0x8000800080008000L, 0x8000000000000000L, 0L},
                    new long[]{0L, 0x8000800080008000L, 0L, 0L}, 128);
            immediates[1733] = new Pattern(new long[]{0L, 0x800080008000L, 0x8000800000000000L, 0L},
                    new long[]{0L, 0x800080008000L, 0x8000000000000000L, 0L}, 144);
            immediates[1734] = new Pattern(new long[]{0L, 2147516416L, 0x8000800080000000L, 0L},
                    new long[]{0L, 2147516416L, 0x8000800000000000L, 0L}, 160);
            immediates[1735] = new Pattern(new long[]{0L, 32768L, 0x8000800080008000L, 0L},
                    new long[]{0L, 32768L, 0x8000800080000000L, 0L}, 176);
            immediates[1736] = new Pattern(new long[]{0L, 0L, 0x8000800080008000L, 0x8000000000000000L},
                    new long[]{0L, 0L, 0x8000800080008000L, 0L}, 192);
            immediates[1737] = new Pattern(new long[]{0L, 0L, 0x800080008000L, 0x8000800000000000L},
                    new long[]{0L, 0L, 0x800080008000L, 0x8000000000000000L}, 208);
            immediates[1738] = new Pattern(new long[]{0L, 0L, 2147516416L, 0x8000800080000000L},
                    new long[]{0L, 0L, 2147516416L, 0x8000800000000000L}, 224);
            immediates[1739] = new Pattern(new long[]{0L, 0L, 32768L, 0x8000800080008000L},
                    new long[]{0L, 0L, 32768L, 0x8000800080000000L}, 240);
            immediates[1740] = new Pattern(new long[]{0x4000400040004000L, 0x4000000000000000L, 0L, 0L},
                    new long[]{0x4000400040004000L, 0L, 0L, 0L}, 65);
            immediates[1741] = new Pattern(new long[]{70369817935872L, 0x4000400000000000L, 0L, 0L},
                    new long[]{70369817935872L, 0x4000000000000000L, 0L, 0L}, 81);
            immediates[1742] = new Pattern(new long[]{1073758208L, 0x4000400040000000L, 0L, 0L},
                    new long[]{1073758208L, 0x4000400000000000L, 0L, 0L}, 97);
            immediates[1743] = new Pattern(new long[]{16384L, 0x4000400040004000L, 0L, 0L},
                    new long[]{16384L, 0x4000400040000000L, 0L, 0L}, 113);
            immediates[1744] = new Pattern(new long[]{0L, 0x4000400040004000L, 0x4000000000000000L, 0L},
                    new long[]{0L, 0x4000400040004000L, 0L, 0L}, 129);
            immediates[1745] = new Pattern(new long[]{0L, 70369817935872L, 0x4000400000000000L, 0L},
                    new long[]{0L, 70369817935872L, 0x4000000000000000L, 0L}, 145);
            immediates[1746] = new Pattern(new long[]{0L, 1073758208L, 0x4000400040000000L, 0L},
                    new long[]{0L, 1073758208L, 0x4000400000000000L, 0L}, 161);
            immediates[1747] = new Pattern(new long[]{0L, 16384L, 0x4000400040004000L, 0L},
                    new long[]{0L, 16384L, 0x4000400040000000L, 0L}, 177);
            immediates[1748] = new Pattern(new long[]{0L, 0L, 0x4000400040004000L, 0x4000000000000000L},
                    new long[]{0L, 0L, 0x4000400040004000L, 0L}, 193);
            immediates[1749] = new Pattern(new long[]{0L, 0L, 70369817935872L, 0x4000400000000000L},
                    new long[]{0L, 0L, 70369817935872L, 0x4000000000000000L}, 209);
            immediates[1750] = new Pattern(new long[]{0L, 0L, 1073758208L, 0x4000400040000000L},
                    new long[]{0L, 0L, 1073758208L, 0x4000400000000000L}, 225);
            immediates[1751] = new Pattern(new long[]{0L, 0L, 16384L, 0x4000400040004000L},
                    new long[]{0L, 0L, 16384L, 0x4000400040000000L}, 241);
            immediates[1752] = new Pattern(new long[]{0x2000200020002000L, 0x2000000000000000L, 0L, 0L},
                    new long[]{0x2000200020002000L, 0L, 0L, 0L}, 66);
            immediates[1753] = new Pattern(new long[]{35184908967936L, 0x2000200000000000L, 0L, 0L},
                    new long[]{35184908967936L, 0x2000000000000000L, 0L, 0L}, 82);
            immediates[1754] = new Pattern(new long[]{536879104L, 0x2000200020000000L, 0L, 0L},
                    new long[]{536879104L, 0x2000200000000000L, 0L, 0L}, 98);
            immediates[1755] = new Pattern(new long[]{8192L, 0x2000200020002000L, 0L, 0L},
                    new long[]{8192L, 0x2000200020000000L, 0L, 0L}, 114);
            immediates[1756] = new Pattern(new long[]{0L, 0x2000200020002000L, 0x2000000000000000L, 0L},
                    new long[]{0L, 0x2000200020002000L, 0L, 0L}, 130);
            immediates[1757] = new Pattern(new long[]{0L, 35184908967936L, 0x2000200000000000L, 0L},
                    new long[]{0L, 35184908967936L, 0x2000000000000000L, 0L}, 146);
            immediates[1758] = new Pattern(new long[]{0L, 536879104L, 0x2000200020000000L, 0L},
                    new long[]{0L, 536879104L, 0x2000200000000000L, 0L}, 162);
            immediates[1759] = new Pattern(new long[]{0L, 8192L, 0x2000200020002000L, 0L},
                    new long[]{0L, 8192L, 0x2000200020000000L, 0L}, 178);
            immediates[1760] = new Pattern(new long[]{0L, 0L, 0x2000200020002000L, 0x2000000000000000L},
                    new long[]{0L, 0L, 0x2000200020002000L, 0L}, 194);
            immediates[1761] = new Pattern(new long[]{0L, 0L, 35184908967936L, 0x2000200000000000L},
                    new long[]{0L, 0L, 35184908967936L, 0x2000000000000000L}, 210);
            immediates[1762] = new Pattern(new long[]{0L, 0L, 536879104L, 0x2000200020000000L},
                    new long[]{0L, 0L, 536879104L, 0x2000200000000000L}, 226);
            immediates[1763] = new Pattern(new long[]{0L, 0L, 8192L, 0x2000200020002000L},
                    new long[]{0L, 0L, 8192L, 0x2000200020000000L}, 242);
            immediates[1764] = new Pattern(new long[]{0x1000100010001000L, 0x1000000000000000L, 0L, 0L},
                    new long[]{0x1000100010001000L, 0L, 0L, 0L}, 67);
            immediates[1765] = new Pattern(new long[]{17592454483968L, 0x1000100000000000L, 0L, 0L},
                    new long[]{17592454483968L, 0x1000000000000000L, 0L, 0L}, 83);
            immediates[1766] = new Pattern(new long[]{268439552L, 0x1000100010000000L, 0L, 0L},
                    new long[]{268439552L, 0x1000100000000000L, 0L, 0L}, 99);
            immediates[1767] = new Pattern(new long[]{4096L, 0x1000100010001000L, 0L, 0L},
                    new long[]{4096L, 0x1000100010000000L, 0L, 0L}, 115);
            immediates[1768] = new Pattern(new long[]{0L, 0x1000100010001000L, 0x1000000000000000L, 0L},
                    new long[]{0L, 0x1000100010001000L, 0L, 0L}, 131);
            immediates[1769] = new Pattern(new long[]{0L, 17592454483968L, 0x1000100000000000L, 0L},
                    new long[]{0L, 17592454483968L, 0x1000000000000000L, 0L}, 147);
            immediates[1770] = new Pattern(new long[]{0L, 268439552L, 0x1000100010000000L, 0L},
                    new long[]{0L, 268439552L, 0x1000100000000000L, 0L}, 163);
            immediates[1771] = new Pattern(new long[]{0L, 4096L, 0x1000100010001000L, 0L},
                    new long[]{0L, 4096L, 0x1000100010000000L, 0L}, 179);
            immediates[1772] = new Pattern(new long[]{0L, 0L, 0x1000100010001000L, 0x1000000000000000L},
                    new long[]{0L, 0L, 0x1000100010001000L, 0L}, 195);
            immediates[1773] = new Pattern(new long[]{0L, 0L, 17592454483968L, 0x1000100000000000L},
                    new long[]{0L, 0L, 17592454483968L, 0x1000000000000000L}, 211);
            immediates[1774] = new Pattern(new long[]{0L, 0L, 268439552L, 0x1000100010000000L},
                    new long[]{0L, 0L, 268439552L, 0x1000100000000000L}, 227);
            immediates[1775] = new Pattern(new long[]{0L, 0L, 4096L, 0x1000100010001000L},
                    new long[]{0L, 0L, 4096L, 0x1000100010000000L}, 243);
            immediates[1776] = new Pattern(new long[]{0x800080008000800L, 0x800000000000000L, 0L, 0L},
                    new long[]{0x800080008000800L, 0L, 0L, 0L}, 68);
            immediates[1777] = new Pattern(new long[]{8796227241984L, 0x800080000000000L, 0L, 0L},
                    new long[]{8796227241984L, 0x800000000000000L, 0L, 0L}, 84);
            immediates[1778] = new Pattern(new long[]{134219776L, 0x800080008000000L, 0L, 0L},
                    new long[]{134219776L, 0x800080000000000L, 0L, 0L}, 100);
            immediates[1779] = new Pattern(new long[]{2048L, 0x800080008000800L, 0L, 0L},
                    new long[]{2048L, 0x800080008000000L, 0L, 0L}, 116);
            immediates[1780] = new Pattern(new long[]{0L, 0x800080008000800L, 0x800000000000000L, 0L},
                    new long[]{0L, 0x800080008000800L, 0L, 0L}, 132);
            immediates[1781] = new Pattern(new long[]{0L, 8796227241984L, 0x800080000000000L, 0L},
                    new long[]{0L, 8796227241984L, 0x800000000000000L, 0L}, 148);
            immediates[1782] = new Pattern(new long[]{0L, 134219776L, 0x800080008000000L, 0L},
                    new long[]{0L, 134219776L, 0x800080000000000L, 0L}, 164);
            immediates[1783] = new Pattern(new long[]{0L, 2048L, 0x800080008000800L, 0L},
                    new long[]{0L, 2048L, 0x800080008000000L, 0L}, 180);
            immediates[1784] = new Pattern(new long[]{0L, 0L, 0x800080008000800L, 0x800000000000000L},
                    new long[]{0L, 0L, 0x800080008000800L, 0L}, 196);
            immediates[1785] = new Pattern(new long[]{0L, 0L, 8796227241984L, 0x800080000000000L},
                    new long[]{0L, 0L, 8796227241984L, 0x800000000000000L}, 212);
            immediates[1786] = new Pattern(new long[]{0L, 0L, 134219776L, 0x800080008000000L},
                    new long[]{0L, 0L, 134219776L, 0x800080000000000L}, 228);
            immediates[1787] = new Pattern(new long[]{0L, 0L, 2048L, 0x800080008000800L},
                    new long[]{0L, 0L, 2048L, 0x800080008000000L}, 244);
            immediates[1788] = new Pattern(new long[]{0x400040004000400L, 0x400000000000000L, 0L, 0L},
                    new long[]{0x400040004000400L, 0L, 0L, 0L}, 69);
            immediates[1789] = new Pattern(new long[]{4398113620992L, 0x400040000000000L, 0L, 0L},
                    new long[]{4398113620992L, 0x400000000000000L, 0L, 0L}, 85);
            immediates[1790] = new Pattern(new long[]{67109888L, 0x400040004000000L, 0L, 0L},
                    new long[]{67109888L, 0x400040000000000L, 0L, 0L}, 101);
            immediates[1791] = new Pattern(new long[]{1024L, 0x400040004000400L, 0L, 0L},
                    new long[]{1024L, 0x400040004000000L, 0L, 0L}, 117);
            immediates[1792] = new Pattern(new long[]{0L, 0x400040004000400L, 0x400000000000000L, 0L},
                    new long[]{0L, 0x400040004000400L, 0L, 0L}, 133);
            immediates[1793] = new Pattern(new long[]{0L, 4398113620992L, 0x400040000000000L, 0L},
                    new long[]{0L, 4398113620992L, 0x400000000000000L, 0L}, 149);
            immediates[1794] = new Pattern(new long[]{0L, 67109888L, 0x400040004000000L, 0L},
                    new long[]{0L, 67109888L, 0x400040000000000L, 0L}, 165);
            immediates[1795] = new Pattern(new long[]{0L, 1024L, 0x400040004000400L, 0L},
                    new long[]{0L, 1024L, 0x400040004000000L, 0L}, 181);
            immediates[1796] = new Pattern(new long[]{0L, 0L, 0x400040004000400L, 0x400000000000000L},
                    new long[]{0L, 0L, 0x400040004000400L, 0L}, 197);
            immediates[1797] = new Pattern(new long[]{0L, 0L, 4398113620992L, 0x400040000000000L},
                    new long[]{0L, 0L, 4398113620992L, 0x400000000000000L}, 213);
            immediates[1798] = new Pattern(new long[]{0L, 0L, 67109888L, 0x400040004000000L},
                    new long[]{0L, 0L, 67109888L, 0x400040000000000L}, 229);
            immediates[1799] = new Pattern(new long[]{0L, 0L, 1024L, 0x400040004000400L},
                    new long[]{0L, 0L, 1024L, 0x400040004000000L}, 245);
            immediates[1800] = new Pattern(new long[]{0x200020002000200L, 0x200000000000000L, 0L, 0L},
                    new long[]{0x200020002000200L, 0L, 0L, 0L}, 70);
            immediates[1801] = new Pattern(new long[]{2199056810496L, 0x200020000000000L, 0L, 0L},
                    new long[]{2199056810496L, 0x200000000000000L, 0L, 0L}, 86);
            immediates[1802] = new Pattern(new long[]{33554944L, 0x200020002000000L, 0L, 0L},
                    new long[]{33554944L, 0x200020000000000L, 0L, 0L}, 102);
            immediates[1803] = new Pattern(new long[]{512L, 0x200020002000200L, 0L, 0L},
                    new long[]{512L, 0x200020002000000L, 0L, 0L}, 118);
            immediates[1804] = new Pattern(new long[]{0L, 0x200020002000200L, 0x200000000000000L, 0L},
                    new long[]{0L, 0x200020002000200L, 0L, 0L}, 134);
            immediates[1805] = new Pattern(new long[]{0L, 2199056810496L, 0x200020000000000L, 0L},
                    new long[]{0L, 2199056810496L, 0x200000000000000L, 0L}, 150);
            immediates[1806] = new Pattern(new long[]{0L, 33554944L, 0x200020002000000L, 0L},
                    new long[]{0L, 33554944L, 0x200020000000000L, 0L}, 166);
            immediates[1807] = new Pattern(new long[]{0L, 512L, 0x200020002000200L, 0L},
                    new long[]{0L, 512L, 0x200020002000000L, 0L}, 182);
            immediates[1808] = new Pattern(new long[]{0L, 0L, 0x200020002000200L, 0x200000000000000L},
                    new long[]{0L, 0L, 0x200020002000200L, 0L}, 198);
            immediates[1809] = new Pattern(new long[]{0L, 0L, 2199056810496L, 0x200020000000000L},
                    new long[]{0L, 0L, 2199056810496L, 0x200000000000000L}, 214);
            immediates[1810] = new Pattern(new long[]{0L, 0L, 33554944L, 0x200020002000000L},
                    new long[]{0L, 0L, 33554944L, 0x200020000000000L}, 230);
            immediates[1811] = new Pattern(new long[]{0L, 0L, 512L, 0x200020002000200L},
                    new long[]{0L, 0L, 512L, 0x200020002000000L}, 246);
            immediates[1812] = new Pattern(new long[]{72058693566333184L, 72057594037927936L, 0L, 0L},
                    new long[]{72058693566333184L, 0L, 0L, 0L}, 71);
            immediates[1813] = new Pattern(new long[]{1099528405248L, 72058693549555712L, 0L, 0L},
                    new long[]{1099528405248L, 72057594037927936L, 0L, 0L}, 87);
            immediates[1814] = new Pattern(new long[]{16777472L, 72058693566332928L, 0L, 0L},
                    new long[]{16777472L, 72058693549555712L, 0L, 0L}, 103);
            immediates[1815] = new Pattern(new long[]{256L, 72058693566333184L, 0L, 0L},
                    new long[]{256L, 72058693566332928L, 0L, 0L}, 119);
            immediates[1816] = new Pattern(new long[]{0L, 72058693566333184L, 72057594037927936L, 0L},
                    new long[]{0L, 72058693566333184L, 0L, 0L}, 135);
            immediates[1817] = new Pattern(new long[]{0L, 1099528405248L, 72058693549555712L, 0L},
                    new long[]{0L, 1099528405248L, 72057594037927936L, 0L}, 151);
            immediates[1818] = new Pattern(new long[]{0L, 16777472L, 72058693566332928L, 0L},
                    new long[]{0L, 16777472L, 72058693549555712L, 0L}, 167);
            immediates[1819] = new Pattern(new long[]{0L, 256L, 72058693566333184L, 0L},
                    new long[]{0L, 256L, 72058693566332928L, 0L}, 183);
            immediates[1820] = new Pattern(new long[]{0L, 0L, 72058693566333184L, 72057594037927936L},
                    new long[]{0L, 0L, 72058693566333184L, 0L}, 199);
            immediates[1821] = new Pattern(new long[]{0L, 0L, 1099528405248L, 72058693549555712L},
                    new long[]{0L, 0L, 1099528405248L, 72057594037927936L}, 215);
            immediates[1822] = new Pattern(new long[]{0L, 0L, 16777472L, 72058693566332928L},
                    new long[]{0L, 0L, 16777472L, 72058693549555712L}, 231);
            immediates[1823] = new Pattern(new long[]{0L, 0L, 256L, 72058693566333184L},
                    new long[]{0L, 0L, 256L, 72058693566332928L}, 247);
            immediates[1824] = new Pattern(new long[]{0x80008000800080L, 0x80000000000000L, 0L, 0L},
                    new long[]{0x80008000800080L, 0L, 0L, 0L}, 72);
            immediates[1825] = new Pattern(new long[]{549764202624L, 0x80008000000000L, 0L, 0L},
                    new long[]{549764202624L, 0x80000000000000L, 0L, 0L}, 88);
            immediates[1826] = new Pattern(new long[]{8388736L, 0x80008000800000L, 0L, 0L},
                    new long[]{8388736L, 0x80008000000000L, 0L, 0L}, 104);
            immediates[1827] = new Pattern(new long[]{128L, 0x80008000800080L, 0L, 0L},
                    new long[]{128L, 0x80008000800000L, 0L, 0L}, 120);
            immediates[1828] = new Pattern(new long[]{0L, 0x80008000800080L, 0x80000000000000L, 0L},
                    new long[]{0L, 0x80008000800080L, 0L, 0L}, 136);
            immediates[1829] = new Pattern(new long[]{0L, 549764202624L, 0x80008000000000L, 0L},
                    new long[]{0L, 549764202624L, 0x80000000000000L, 0L}, 152);
            immediates[1830] = new Pattern(new long[]{0L, 8388736L, 0x80008000800000L, 0L},
                    new long[]{0L, 8388736L, 0x80008000000000L, 0L}, 168);
            immediates[1831] = new Pattern(new long[]{0L, 128L, 0x80008000800080L, 0L},
                    new long[]{0L, 128L, 0x80008000800000L, 0L}, 184);
            immediates[1832] = new Pattern(new long[]{0L, 0L, 0x80008000800080L, 0x80000000000000L},
                    new long[]{0L, 0L, 0x80008000800080L, 0L}, 200);
            immediates[1833] = new Pattern(new long[]{0L, 0L, 549764202624L, 0x80008000000000L},
                    new long[]{0L, 0L, 549764202624L, 0x80000000000000L}, 216);
            immediates[1834] = new Pattern(new long[]{0L, 0L, 8388736L, 0x80008000800000L},
                    new long[]{0L, 0L, 8388736L, 0x80008000000000L}, 232);
            immediates[1835] = new Pattern(new long[]{0L, 0L, 128L, 0x80008000800080L},
                    new long[]{0L, 0L, 128L, 0x80008000800000L}, 248);
            immediates[1836] = new Pattern(new long[]{0x40004000400040L, 0x40000000000000L, 0L, 0L},
                    new long[]{0x40004000400040L, 0L, 0L, 0L}, 73);
            immediates[1837] = new Pattern(new long[]{274882101312L, 0x40004000000000L, 0L, 0L},
                    new long[]{274882101312L, 0x40000000000000L, 0L, 0L}, 89);
            immediates[1838] = new Pattern(new long[]{4194368L, 0x40004000400000L, 0L, 0L},
                    new long[]{4194368L, 0x40004000000000L, 0L, 0L}, 105);
            immediates[1839] =
                    new Pattern(new long[]{64L, 0x40004000400040L, 0L, 0L}, new long[]{64L, 0x40004000400000L, 0L, 0L},
                            121);
            immediates[1840] = new Pattern(new long[]{0L, 0x40004000400040L, 0x40000000000000L, 0L},
                    new long[]{0L, 0x40004000400040L, 0L, 0L}, 137);
            immediates[1841] = new Pattern(new long[]{0L, 274882101312L, 0x40004000000000L, 0L},
                    new long[]{0L, 274882101312L, 0x40000000000000L, 0L}, 153);
            immediates[1842] = new Pattern(new long[]{0L, 4194368L, 0x40004000400000L, 0L},
                    new long[]{0L, 4194368L, 0x40004000000000L, 0L}, 169);
            immediates[1843] =
                    new Pattern(new long[]{0L, 64L, 0x40004000400040L, 0L}, new long[]{0L, 64L, 0x40004000400000L, 0L},
                            185);
            immediates[1844] = new Pattern(new long[]{0L, 0L, 0x40004000400040L, 0x40000000000000L},
                    new long[]{0L, 0L, 0x40004000400040L, 0L}, 201);
            immediates[1845] = new Pattern(new long[]{0L, 0L, 274882101312L, 0x40004000000000L},
                    new long[]{0L, 0L, 274882101312L, 0x40000000000000L}, 217);
            immediates[1846] = new Pattern(new long[]{0L, 0L, 4194368L, 0x40004000400000L},
                    new long[]{0L, 0L, 4194368L, 0x40004000000000L}, 233);
            immediates[1847] =
                    new Pattern(new long[]{0L, 0L, 64L, 0x40004000400040L}, new long[]{0L, 0L, 64L, 0x40004000400000L},
                            249);
            immediates[1848] = new Pattern(new long[]{9007336695791648L, 9007199254740992L, 0L, 0L},
                    new long[]{9007336695791648L, 0L, 0L, 0L}, 74);
            immediates[1849] = new Pattern(new long[]{137441050656L, 9007336693694464L, 0L, 0L},
                    new long[]{137441050656L, 9007199254740992L, 0L, 0L}, 90);
            immediates[1850] = new Pattern(new long[]{2097184L, 9007336695791616L, 0L, 0L},
                    new long[]{2097184L, 9007336693694464L, 0L, 0L}, 106);
            immediates[1851] =
                    new Pattern(new long[]{32L, 9007336695791648L, 0L, 0L}, new long[]{32L, 9007336695791616L, 0L, 0L},
                            122);
            immediates[1852] = new Pattern(new long[]{0L, 9007336695791648L, 9007199254740992L, 0L},
                    new long[]{0L, 9007336695791648L, 0L, 0L}, 138);
            immediates[1853] = new Pattern(new long[]{0L, 137441050656L, 9007336693694464L, 0L},
                    new long[]{0L, 137441050656L, 9007199254740992L, 0L}, 154);
            immediates[1854] = new Pattern(new long[]{0L, 2097184L, 9007336695791616L, 0L},
                    new long[]{0L, 2097184L, 9007336693694464L, 0L}, 170);
            immediates[1855] =
                    new Pattern(new long[]{0L, 32L, 9007336695791648L, 0L}, new long[]{0L, 32L, 9007336695791616L, 0L},
                            186);
            immediates[1856] = new Pattern(new long[]{0L, 0L, 9007336695791648L, 9007199254740992L},
                    new long[]{0L, 0L, 9007336695791648L, 0L}, 202);
            immediates[1857] = new Pattern(new long[]{0L, 0L, 137441050656L, 9007336693694464L},
                    new long[]{0L, 0L, 137441050656L, 9007199254740992L}, 218);
            immediates[1858] = new Pattern(new long[]{0L, 0L, 2097184L, 9007336695791616L},
                    new long[]{0L, 0L, 2097184L, 9007336693694464L}, 234);
            immediates[1859] =
                    new Pattern(new long[]{0L, 0L, 32L, 9007336695791648L}, new long[]{0L, 0L, 32L, 9007336695791616L},
                            250);
            immediates[1860] = new Pattern(new long[]{4503668347895824L, 4503599627370496L, 0L, 0L},
                    new long[]{4503668347895824L, 0L, 0L, 0L}, 75);
            immediates[1861] = new Pattern(new long[]{68720525328L, 4503668346847232L, 0L, 0L},
                    new long[]{68720525328L, 4503599627370496L, 0L, 0L}, 91);
            immediates[1862] = new Pattern(new long[]{1048592L, 4503668347895808L, 0L, 0L},
                    new long[]{1048592L, 4503668346847232L, 0L, 0L}, 107);
            immediates[1863] =
                    new Pattern(new long[]{16L, 4503668347895824L, 0L, 0L}, new long[]{16L, 4503668347895808L, 0L, 0L},
                            123);
            immediates[1864] = new Pattern(new long[]{0L, 4503668347895824L, 4503599627370496L, 0L},
                    new long[]{0L, 4503668347895824L, 0L, 0L}, 139);
            immediates[1865] = new Pattern(new long[]{0L, 68720525328L, 4503668346847232L, 0L},
                    new long[]{0L, 68720525328L, 4503599627370496L, 0L}, 155);
            immediates[1866] = new Pattern(new long[]{0L, 1048592L, 4503668347895808L, 0L},
                    new long[]{0L, 1048592L, 4503668346847232L, 0L}, 171);
            immediates[1867] =
                    new Pattern(new long[]{0L, 16L, 4503668347895824L, 0L}, new long[]{0L, 16L, 4503668347895808L, 0L},
                            187);
            immediates[1868] = new Pattern(new long[]{0L, 0L, 4503668347895824L, 4503599627370496L},
                    new long[]{0L, 0L, 4503668347895824L, 0L}, 203);
            immediates[1869] = new Pattern(new long[]{0L, 0L, 68720525328L, 4503668346847232L},
                    new long[]{0L, 0L, 68720525328L, 4503599627370496L}, 219);
            immediates[1870] = new Pattern(new long[]{0L, 0L, 1048592L, 4503668347895808L},
                    new long[]{0L, 0L, 1048592L, 4503668346847232L}, 235);
            immediates[1871] =
                    new Pattern(new long[]{0L, 0L, 16L, 4503668347895824L}, new long[]{0L, 0L, 16L, 4503668347895808L},
                            251);
            immediates[1872] = new Pattern(new long[]{0x8000800080008L, 0x8000000000000L, 0L, 0L},
                    new long[]{0x8000800080008L, 0L, 0L, 0L}, 76);
            immediates[1873] = new Pattern(new long[]{34360262664L, 0x8000800000000L, 0L, 0L},
                    new long[]{34360262664L, 0x8000000000000L, 0L, 0L}, 92);
            immediates[1874] = new Pattern(new long[]{524296L, 0x8000800080000L, 0L, 0L},
                    new long[]{524296L, 0x8000800000000L, 0L, 0L}, 108);
            immediates[1875] =
                    new Pattern(new long[]{8L, 0x8000800080008L, 0L, 0L}, new long[]{8L, 0x8000800080000L, 0L, 0L},
                            124);
            immediates[1876] = new Pattern(new long[]{0L, 0x8000800080008L, 0x8000000000000L, 0L},
                    new long[]{0L, 0x8000800080008L, 0L, 0L}, 140);
            immediates[1877] = new Pattern(new long[]{0L, 34360262664L, 0x8000800000000L, 0L},
                    new long[]{0L, 34360262664L, 0x8000000000000L, 0L}, 156);
            immediates[1878] = new Pattern(new long[]{0L, 524296L, 0x8000800080000L, 0L},
                    new long[]{0L, 524296L, 0x8000800000000L, 0L}, 172);
            immediates[1879] =
                    new Pattern(new long[]{0L, 8L, 0x8000800080008L, 0L}, new long[]{0L, 8L, 0x8000800080000L, 0L},
                            188);
            immediates[1880] = new Pattern(new long[]{0L, 0L, 0x8000800080008L, 0x8000000000000L},
                    new long[]{0L, 0L, 0x8000800080008L, 0L}, 204);
            immediates[1881] = new Pattern(new long[]{0L, 0L, 34360262664L, 0x8000800000000L},
                    new long[]{0L, 0L, 34360262664L, 0x8000000000000L}, 220);
            immediates[1882] = new Pattern(new long[]{0L, 0L, 524296L, 0x8000800080000L},
                    new long[]{0L, 0L, 524296L, 0x8000800000000L}, 236);
            immediates[1883] =
                    new Pattern(new long[]{0L, 0L, 8L, 0x8000800080008L}, new long[]{0L, 0L, 8L, 0x8000800080000L},
                            252);
            immediates[1884] = new Pattern(new long[]{0x4000400040004L, 0x4000000000000L, 0L, 0L},
                    new long[]{0x4000400040004L, 0L, 0L, 0L}, 77);
            immediates[1885] = new Pattern(new long[]{17180131332L, 0x4000400000000L, 0L, 0L},
                    new long[]{17180131332L, 0x4000000000000L, 0L, 0L}, 93);
            immediates[1886] = new Pattern(new long[]{262148L, 0x4000400040000L, 0L, 0L},
                    new long[]{262148L, 0x4000400000000L, 0L, 0L}, 109);
            immediates[1887] =
                    new Pattern(new long[]{4L, 0x4000400040004L, 0L, 0L}, new long[]{4L, 0x4000400040000L, 0L, 0L},
                            125);
            immediates[1888] = new Pattern(new long[]{0L, 0x4000400040004L, 0x4000000000000L, 0L},
                    new long[]{0L, 0x4000400040004L, 0L, 0L}, 141);
            immediates[1889] = new Pattern(new long[]{0L, 17180131332L, 0x4000400000000L, 0L},
                    new long[]{0L, 17180131332L, 0x4000000000000L, 0L}, 157);
            immediates[1890] = new Pattern(new long[]{0L, 262148L, 0x4000400040000L, 0L},
                    new long[]{0L, 262148L, 0x4000400000000L, 0L}, 173);
            immediates[1891] =
                    new Pattern(new long[]{0L, 4L, 0x4000400040004L, 0L}, new long[]{0L, 4L, 0x4000400040000L, 0L},
                            189);
            immediates[1892] = new Pattern(new long[]{0L, 0L, 0x4000400040004L, 0x4000000000000L},
                    new long[]{0L, 0L, 0x4000400040004L, 0L}, 205);
            immediates[1893] = new Pattern(new long[]{0L, 0L, 17180131332L, 0x4000400000000L},
                    new long[]{0L, 0L, 17180131332L, 0x4000000000000L}, 221);
            immediates[1894] = new Pattern(new long[]{0L, 0L, 262148L, 0x4000400040000L},
                    new long[]{0L, 0L, 262148L, 0x4000400000000L}, 237);
            immediates[1895] =
                    new Pattern(new long[]{0L, 0L, 4L, 0x4000400040004L}, new long[]{0L, 0L, 4L, 0x4000400040000L},
                            253);
            immediates[1896] = new Pattern(new long[]{562958543486978L, 562949953421312L, 0L, 0L},
                    new long[]{562958543486978L, 0L, 0L, 0L}, 78);
            immediates[1897] = new Pattern(new long[]{8590065666L, 562958543355904L, 0L, 0L},
                    new long[]{8590065666L, 562949953421312L, 0L, 0L}, 94);
            immediates[1898] = new Pattern(new long[]{131074L, 562958543486976L, 0L, 0L},
                    new long[]{131074L, 562958543355904L, 0L, 0L}, 110);
            immediates[1899] =
                    new Pattern(new long[]{2L, 562958543486978L, 0L, 0L}, new long[]{2L, 562958543486976L, 0L, 0L},
                            126);
            immediates[1900] = new Pattern(new long[]{0L, 562958543486978L, 562949953421312L, 0L},
                    new long[]{0L, 562958543486978L, 0L, 0L}, 142);
            immediates[1901] = new Pattern(new long[]{0L, 8590065666L, 562958543355904L, 0L},
                    new long[]{0L, 8590065666L, 562949953421312L, 0L}, 158);
            immediates[1902] = new Pattern(new long[]{0L, 131074L, 562958543486976L, 0L},
                    new long[]{0L, 131074L, 562958543355904L, 0L}, 174);
            immediates[1903] =
                    new Pattern(new long[]{0L, 2L, 562958543486978L, 0L}, new long[]{0L, 2L, 562958543486976L, 0L},
                            190);
            immediates[1904] = new Pattern(new long[]{0L, 0L, 562958543486978L, 562949953421312L},
                    new long[]{0L, 0L, 562958543486978L, 0L}, 206);
            immediates[1905] = new Pattern(new long[]{0L, 0L, 8590065666L, 562958543355904L},
                    new long[]{0L, 0L, 8590065666L, 562949953421312L}, 222);
            immediates[1906] = new Pattern(new long[]{0L, 0L, 131074L, 562958543486976L},
                    new long[]{0L, 0L, 131074L, 562958543355904L}, 238);
            immediates[1907] =
                    new Pattern(new long[]{0L, 0L, 2L, 562958543486978L}, new long[]{0L, 0L, 2L, 562958543486976L},
                            254);
            immediates[1908] = new Pattern(new long[]{281479271743489L, 281474976710656L, 0L, 0L},
                    new long[]{281479271743489L, 0L, 0L, 0L}, 79);
            immediates[1909] = new Pattern(new long[]{4295032833L, 281479271677952L, 0L, 0L},
                    new long[]{4295032833L, 281474976710656L, 0L, 0L}, 95);
            immediates[1910] = new Pattern(new long[]{65537L, 281479271743488L, 0L, 0L},
                    new long[]{65537L, 281479271677952L, 0L, 0L}, 111);
            immediates[1911] =
                    new Pattern(new long[]{1L, 281479271743489L, 0L, 0L}, new long[]{1L, 281479271743488L, 0L, 0L},
                            127);
            immediates[1912] = new Pattern(new long[]{0L, 281479271743489L, 281474976710656L, 0L},
                    new long[]{0L, 281479271743489L, 0L, 0L}, 143);
            immediates[1913] = new Pattern(new long[]{0L, 4295032833L, 281479271677952L, 0L},
                    new long[]{0L, 4295032833L, 281474976710656L, 0L}, 159);
            immediates[1914] = new Pattern(new long[]{0L, 65537L, 281479271743488L, 0L},
                    new long[]{0L, 65537L, 281479271677952L, 0L}, 175);
            immediates[1915] =
                    new Pattern(new long[]{0L, 1L, 281479271743489L, 0L}, new long[]{0L, 1L, 281479271743488L, 0L},
                            191);
            immediates[1916] = new Pattern(new long[]{0L, 0L, 281479271743489L, 281474976710656L},
                    new long[]{0L, 0L, 281479271743489L, 0L}, 207);
            immediates[1917] = new Pattern(new long[]{0L, 0L, 4295032833L, 281479271677952L},
                    new long[]{0L, 0L, 4295032833L, 281474976710656L}, 223);
            immediates[1918] = new Pattern(new long[]{0L, 0L, 65537L, 281479271743488L},
                    new long[]{0L, 0L, 65537L, 281479271677952L}, 239);
            immediates[1919] =
                    new Pattern(new long[]{0L, 0L, 1L, 281479271743489L}, new long[]{0L, 0L, 1L, 281479271743488L},
                            255);
            immediates[1920] = new Pattern(new long[]{0x8000400020001000L, 0x800000000000000L, 0L, 0L},
                    new long[]{70369281052672L, 0x800000000000000L, 0L, 0L}, 0);
            immediates[1921] = new Pattern(new long[]{0x800040002000L, 0x1000080000000000L, 0L, 0L},
                    new long[]{1073750016L, 0x1000080000000000L, 0L, 0L}, 16);
            immediates[1922] = new Pattern(new long[]{2147500032L, 0x2000100008000000L, 0L, 0L},
                    new long[]{16384L, 0x2000100008000000L, 0L, 0L}, 32);
            immediates[1923] = new Pattern(new long[]{32768L, 0x4000200010000800L, 0L, 0L},
                    new long[]{0L, 0x4000200010000800L, 0L, 0L}, 48);
            immediates[1924] = new Pattern(new long[]{0L, 0x8000400020001000L, 0x800000000000000L, 0L},
                    new long[]{0L, 70369281052672L, 0x800000000000000L, 0L}, 64);
            immediates[1925] = new Pattern(new long[]{0L, 0x800040002000L, 0x1000080000000000L, 0L},
                    new long[]{0L, 1073750016L, 0x1000080000000000L, 0L}, 80);
            immediates[1926] = new Pattern(new long[]{0L, 2147500032L, 0x2000100008000000L, 0L},
                    new long[]{0L, 16384L, 0x2000100008000000L, 0L}, 96);
            immediates[1927] = new Pattern(new long[]{0L, 32768L, 0x4000200010000800L, 0L},
                    new long[]{0L, 0L, 0x4000200010000800L, 0L}, 112);
            immediates[1928] = new Pattern(new long[]{0L, 0L, 0x8000400020001000L, 0x800000000000000L},
                    new long[]{0L, 0L, 70369281052672L, 0x800000000000000L}, 128);
            immediates[1929] = new Pattern(new long[]{0L, 0L, 0x800040002000L, 0x1000080000000000L},
                    new long[]{0L, 0L, 1073750016L, 0x1000080000000000L}, 144);
            immediates[1930] = new Pattern(new long[]{0L, 0L, 2147500032L, 0x2000100008000000L},
                    new long[]{0L, 0L, 16384L, 0x2000100008000000L}, 160);
            immediates[1931] = new Pattern(new long[]{0L, 0L, 32768L, 0x4000200010000800L},
                    new long[]{0L, 0L, 0L, 0x4000200010000800L}, 176);
            immediates[1932] = new Pattern(new long[]{0x4000200010000800L, 0x400000000000000L, 0L, 0L},
                    new long[]{35184640526336L, 0x400000000000000L, 0L, 0L}, 1);
            immediates[1933] = new Pattern(new long[]{70369281052672L, 0x800040000000000L, 0L, 0L},
                    new long[]{536875008L, 0x800040000000000L, 0L, 0L}, 17);
            immediates[1934] = new Pattern(new long[]{1073750016L, 0x1000080004000000L, 0L, 0L},
                    new long[]{8192L, 0x1000080004000000L, 0L, 0L}, 33);
            immediates[1935] = new Pattern(new long[]{16384L, 0x2000100008000400L, 0L, 0L},
                    new long[]{0L, 0x2000100008000400L, 0L, 0L}, 49);
            immediates[1936] = new Pattern(new long[]{0L, 0x4000200010000800L, 0x400000000000000L, 0L},
                    new long[]{0L, 35184640526336L, 0x400000000000000L, 0L}, 65);
            immediates[1937] = new Pattern(new long[]{0L, 70369281052672L, 0x800040000000000L, 0L},
                    new long[]{0L, 536875008L, 0x800040000000000L, 0L}, 81);
            immediates[1938] = new Pattern(new long[]{0L, 1073750016L, 0x1000080004000000L, 0L},
                    new long[]{0L, 8192L, 0x1000080004000000L, 0L}, 97);
            immediates[1939] = new Pattern(new long[]{0L, 16384L, 0x2000100008000400L, 0L},
                    new long[]{0L, 0L, 0x2000100008000400L, 0L}, 113);
            immediates[1940] = new Pattern(new long[]{0L, 0L, 0x4000200010000800L, 0x400000000000000L},
                    new long[]{0L, 0L, 35184640526336L, 0x400000000000000L}, 129);
            immediates[1941] = new Pattern(new long[]{0L, 0L, 70369281052672L, 0x800040000000000L},
                    new long[]{0L, 0L, 536875008L, 0x800040000000000L}, 145);
            immediates[1942] = new Pattern(new long[]{0L, 0L, 1073750016L, 0x1000080004000000L},
                    new long[]{0L, 0L, 8192L, 0x1000080004000000L}, 161);
            immediates[1943] = new Pattern(new long[]{0L, 0L, 16384L, 0x2000100008000400L},
                    new long[]{0L, 0L, 0L, 0x2000100008000400L}, 177);
            immediates[1944] = new Pattern(new long[]{0x2000100008000400L, 0x200000000000000L, 0L, 0L},
                    new long[]{17592320263168L, 0x200000000000000L, 0L, 0L}, 2);
            immediates[1945] = new Pattern(new long[]{35184640526336L, 0x400020000000000L, 0L, 0L},
                    new long[]{268437504L, 0x400020000000000L, 0L, 0L}, 18);
            immediates[1946] = new Pattern(new long[]{536875008L, 0x800040002000000L, 0L, 0L},
                    new long[]{4096L, 0x800040002000000L, 0L, 0L}, 34);
            immediates[1947] = new Pattern(new long[]{8192L, 0x1000080004000200L, 0L, 0L},
                    new long[]{0L, 0x1000080004000200L, 0L, 0L}, 50);
            immediates[1948] = new Pattern(new long[]{0L, 0x2000100008000400L, 0x200000000000000L, 0L},
                    new long[]{0L, 17592320263168L, 0x200000000000000L, 0L}, 66);
            immediates[1949] = new Pattern(new long[]{0L, 35184640526336L, 0x400020000000000L, 0L},
                    new long[]{0L, 268437504L, 0x400020000000000L, 0L}, 82);
            immediates[1950] = new Pattern(new long[]{0L, 536875008L, 0x800040002000000L, 0L},
                    new long[]{0L, 4096L, 0x800040002000000L, 0L}, 98);
            immediates[1951] = new Pattern(new long[]{0L, 8192L, 0x1000080004000200L, 0L},
                    new long[]{0L, 0L, 0x1000080004000200L, 0L}, 114);
            immediates[1952] = new Pattern(new long[]{0L, 0L, 0x2000100008000400L, 0x200000000000000L},
                    new long[]{0L, 0L, 17592320263168L, 0x200000000000000L}, 130);
            immediates[1953] = new Pattern(new long[]{0L, 0L, 35184640526336L, 0x400020000000000L},
                    new long[]{0L, 0L, 268437504L, 0x400020000000000L}, 146);
            immediates[1954] = new Pattern(new long[]{0L, 0L, 536875008L, 0x800040002000000L},
                    new long[]{0L, 0L, 4096L, 0x800040002000000L}, 162);
            immediates[1955] = new Pattern(new long[]{0L, 0L, 8192L, 0x1000080004000200L},
                    new long[]{0L, 0L, 0L, 0x1000080004000200L}, 178);
            immediates[1956] = new Pattern(new long[]{0x1000080004000200L, 72057594037927936L, 0L, 0L},
                    new long[]{8796160131584L, 72057594037927936L, 0L, 0L}, 3);
            immediates[1957] = new Pattern(new long[]{17592320263168L, 0x200010000000000L, 0L, 0L},
                    new long[]{134218752L, 0x200010000000000L, 0L, 0L}, 19);
            immediates[1958] = new Pattern(new long[]{268437504L, 0x400020001000000L, 0L, 0L},
                    new long[]{2048L, 0x400020001000000L, 0L, 0L}, 35);
            immediates[1959] = new Pattern(new long[]{4096L, 0x800040002000100L, 0L, 0L},
                    new long[]{0L, 0x800040002000100L, 0L, 0L}, 51);
            immediates[1960] = new Pattern(new long[]{0L, 0x1000080004000200L, 72057594037927936L, 0L},
                    new long[]{0L, 8796160131584L, 72057594037927936L, 0L}, 67);
            immediates[1961] = new Pattern(new long[]{0L, 17592320263168L, 0x200010000000000L, 0L},
                    new long[]{0L, 134218752L, 0x200010000000000L, 0L}, 83);
            immediates[1962] = new Pattern(new long[]{0L, 268437504L, 0x400020001000000L, 0L},
                    new long[]{0L, 2048L, 0x400020001000000L, 0L}, 99);
            immediates[1963] = new Pattern(new long[]{0L, 4096L, 0x800040002000100L, 0L},
                    new long[]{0L, 0L, 0x800040002000100L, 0L}, 115);
            immediates[1964] = new Pattern(new long[]{0L, 0L, 0x1000080004000200L, 72057594037927936L},
                    new long[]{0L, 0L, 8796160131584L, 72057594037927936L}, 131);
            immediates[1965] = new Pattern(new long[]{0L, 0L, 17592320263168L, 0x200010000000000L},
                    new long[]{0L, 0L, 134218752L, 0x200010000000000L}, 147);
            immediates[1966] = new Pattern(new long[]{0L, 0L, 268437504L, 0x400020001000000L},
                    new long[]{0L, 0L, 2048L, 0x400020001000000L}, 163);
            immediates[1967] = new Pattern(new long[]{0L, 0L, 4096L, 0x800040002000100L},
                    new long[]{0L, 0L, 0L, 0x800040002000100L}, 179);
            immediates[1968] = new Pattern(new long[]{0x800040002000100L, 0x80000000000000L, 0L, 0L},
                    new long[]{4398080065792L, 0x80000000000000L, 0L, 0L}, 4);
            immediates[1969] = new Pattern(new long[]{8796160131584L, 72058143793741824L, 0L, 0L},
                    new long[]{67109376L, 72058143793741824L, 0L, 0L}, 20);
            immediates[1970] = new Pattern(new long[]{134218752L, 0x200010000800000L, 0L, 0L},
                    new long[]{1024L, 0x200010000800000L, 0L, 0L}, 36);
            immediates[1971] = new Pattern(new long[]{2048L, 0x400020001000080L, 0L, 0L},
                    new long[]{0L, 0x400020001000080L, 0L, 0L}, 52);
            immediates[1972] = new Pattern(new long[]{0L, 0x800040002000100L, 0x80000000000000L, 0L},
                    new long[]{0L, 4398080065792L, 0x80000000000000L, 0L}, 68);
            immediates[1973] = new Pattern(new long[]{0L, 8796160131584L, 72058143793741824L, 0L},
                    new long[]{0L, 67109376L, 72058143793741824L, 0L}, 84);
            immediates[1974] = new Pattern(new long[]{0L, 134218752L, 0x200010000800000L, 0L},
                    new long[]{0L, 1024L, 0x200010000800000L, 0L}, 100);
            immediates[1975] = new Pattern(new long[]{0L, 2048L, 0x400020001000080L, 0L},
                    new long[]{0L, 0L, 0x400020001000080L, 0L}, 116);
            immediates[1976] = new Pattern(new long[]{0L, 0L, 0x800040002000100L, 0x80000000000000L},
                    new long[]{0L, 0L, 4398080065792L, 0x80000000000000L}, 132);
            immediates[1977] = new Pattern(new long[]{0L, 0L, 8796160131584L, 72058143793741824L},
                    new long[]{0L, 0L, 67109376L, 72058143793741824L}, 148);
            immediates[1978] = new Pattern(new long[]{0L, 0L, 134218752L, 0x200010000800000L},
                    new long[]{0L, 0L, 1024L, 0x200010000800000L}, 164);
            immediates[1979] = new Pattern(new long[]{0L, 0L, 2048L, 0x400020001000080L},
                    new long[]{0L, 0L, 0L, 0x400020001000080L}, 180);
            immediates[1980] = new Pattern(new long[]{0x400020001000080L, 0x40000000000000L, 0L, 0L},
                    new long[]{2199040032896L, 0x40000000000000L, 0L, 0L}, 5);
            immediates[1981] = new Pattern(new long[]{4398080065792L, 0x80004000000000L, 0L, 0L},
                    new long[]{33554688L, 0x80004000000000L, 0L, 0L}, 21);
            immediates[1982] = new Pattern(new long[]{67109376L, 72058143797936128L, 0L, 0L},
                    new long[]{512L, 72058143797936128L, 0L, 0L}, 37);
            immediates[1983] = new Pattern(new long[]{1024L, 0x200010000800040L, 0L, 0L},
                    new long[]{0L, 0x200010000800040L, 0L, 0L}, 53);
            immediates[1984] = new Pattern(new long[]{0L, 0x400020001000080L, 0x40000000000000L, 0L},
                    new long[]{0L, 2199040032896L, 0x40000000000000L, 0L}, 69);
            immediates[1985] = new Pattern(new long[]{0L, 4398080065792L, 0x80004000000000L, 0L},
                    new long[]{0L, 33554688L, 0x80004000000000L, 0L}, 85);
            immediates[1986] = new Pattern(new long[]{0L, 67109376L, 72058143797936128L, 0L},
                    new long[]{0L, 512L, 72058143797936128L, 0L}, 101);
            immediates[1987] = new Pattern(new long[]{0L, 1024L, 0x200010000800040L, 0L},
                    new long[]{0L, 0L, 0x200010000800040L, 0L}, 117);
            immediates[1988] = new Pattern(new long[]{0L, 0L, 0x400020001000080L, 0x40000000000000L},
                    new long[]{0L, 0L, 2199040032896L, 0x40000000000000L}, 133);
            immediates[1989] = new Pattern(new long[]{0L, 0L, 4398080065792L, 0x80004000000000L},
                    new long[]{0L, 0L, 33554688L, 0x80004000000000L}, 149);
            immediates[1990] = new Pattern(new long[]{0L, 0L, 67109376L, 72058143797936128L},
                    new long[]{0L, 0L, 512L, 72058143797936128L}, 165);
            immediates[1991] = new Pattern(new long[]{0L, 0L, 1024L, 0x200010000800040L},
                    new long[]{0L, 0L, 0L, 0x200010000800040L}, 181);
            immediates[1992] = new Pattern(new long[]{0x200010000800040L, 9007199254740992L, 0L, 0L},
                    new long[]{1099520016448L, 9007199254740992L, 0L, 0L}, 6);
            immediates[1993] = new Pattern(new long[]{2199040032896L, 0x40002000000000L, 0L, 0L},
                    new long[]{16777344L, 0x40002000000000L, 0L, 0L}, 22);
            immediates[1994] = new Pattern(new long[]{33554688L, 0x80004000200000L, 0L, 0L},
                    new long[]{256L, 0x80004000200000L, 0L, 0L}, 38);
            immediates[1995] = new Pattern(new long[]{512L, 72058143797936160L, 0L, 0L},
                    new long[]{0L, 72058143797936160L, 0L, 0L}, 54);
            immediates[1996] = new Pattern(new long[]{0L, 0x200010000800040L, 9007199254740992L, 0L},
                    new long[]{0L, 1099520016448L, 9007199254740992L, 0L}, 70);
            immediates[1997] = new Pattern(new long[]{0L, 2199040032896L, 0x40002000000000L, 0L},
                    new long[]{0L, 16777344L, 0x40002000000000L, 0L}, 86);
            immediates[1998] = new Pattern(new long[]{0L, 33554688L, 0x80004000200000L, 0L},
                    new long[]{0L, 256L, 0x80004000200000L, 0L}, 102);
            immediates[1999] = new Pattern(new long[]{0L, 512L, 72058143797936160L, 0L},
                    new long[]{0L, 0L, 72058143797936160L, 0L}, 118);
        }

        private static void initImmediates4() {
            immediates[2000] = new Pattern(new long[]{0L, 0L, 0x200010000800040L, 9007199254740992L},
                    new long[]{0L, 0L, 1099520016448L, 9007199254740992L}, 134);
            immediates[2001] = new Pattern(new long[]{0L, 0L, 2199040032896L, 0x40002000000000L},
                    new long[]{0L, 0L, 16777344L, 0x40002000000000L}, 150);
            immediates[2002] = new Pattern(new long[]{0L, 0L, 33554688L, 0x80004000200000L},
                    new long[]{0L, 0L, 256L, 0x80004000200000L}, 166);
            immediates[2003] = new Pattern(new long[]{0L, 0L, 512L, 72058143797936160L},
                    new long[]{0L, 0L, 0L, 72058143797936160L}, 182);
            immediates[2004] = new Pattern(new long[]{72058143797936160L, 4503599627370496L, 0L, 0L},
                    new long[]{549760008224L, 4503599627370496L, 0L, 0L}, 7);
            immediates[2005] = new Pattern(new long[]{1099520016448L, 9007267974217728L, 0L, 0L},
                    new long[]{8388672L, 9007267974217728L, 0L, 0L}, 23);
            immediates[2006] = new Pattern(new long[]{16777344L, 0x40002000100000L, 0L, 0L},
                    new long[]{128L, 0x40002000100000L, 0L, 0L}, 39);
            immediates[2007] =
                    new Pattern(new long[]{256L, 0x80004000200010L, 0L, 0L}, new long[]{0L, 0x80004000200010L, 0L, 0L},
                            55);
            immediates[2008] = new Pattern(new long[]{0L, 72058143797936160L, 4503599627370496L, 0L},
                    new long[]{0L, 549760008224L, 4503599627370496L, 0L}, 71);
            immediates[2009] = new Pattern(new long[]{0L, 1099520016448L, 9007267974217728L, 0L},
                    new long[]{0L, 8388672L, 9007267974217728L, 0L}, 87);
            immediates[2010] = new Pattern(new long[]{0L, 16777344L, 0x40002000100000L, 0L},
                    new long[]{0L, 128L, 0x40002000100000L, 0L}, 103);
            immediates[2011] =
                    new Pattern(new long[]{0L, 256L, 0x80004000200010L, 0L}, new long[]{0L, 0L, 0x80004000200010L, 0L},
                            119);
            immediates[2012] = new Pattern(new long[]{0L, 0L, 72058143797936160L, 4503599627370496L},
                    new long[]{0L, 0L, 549760008224L, 4503599627370496L}, 135);
            immediates[2013] = new Pattern(new long[]{0L, 0L, 1099520016448L, 9007267974217728L},
                    new long[]{0L, 0L, 8388672L, 9007267974217728L}, 151);
            immediates[2014] = new Pattern(new long[]{0L, 0L, 16777344L, 0x40002000100000L},
                    new long[]{0L, 0L, 128L, 0x40002000100000L}, 167);
            immediates[2015] =
                    new Pattern(new long[]{0L, 0L, 256L, 0x80004000200010L}, new long[]{0L, 0L, 0L, 0x80004000200010L},
                            183);
            immediates[2016] = new Pattern(new long[]{0x80004000200010L, 0x8000000000000L, 0L, 0L},
                    new long[]{274880004112L, 0x8000000000000L, 0L, 0L}, 8);
            immediates[2017] = new Pattern(new long[]{549760008224L, 4503633987108864L, 0L, 0L},
                    new long[]{4194336L, 4503633987108864L, 0L, 0L}, 24);
            immediates[2018] = new Pattern(new long[]{8388672L, 9007267974742016L, 0L, 0L},
                    new long[]{64L, 9007267974742016L, 0L, 0L}, 40);
            immediates[2019] =
                    new Pattern(new long[]{128L, 0x40002000100008L, 0L, 0L}, new long[]{0L, 0x40002000100008L, 0L, 0L},
                            56);
            immediates[2020] = new Pattern(new long[]{0L, 0x80004000200010L, 0x8000000000000L, 0L},
                    new long[]{0L, 274880004112L, 0x8000000000000L, 0L}, 72);
            immediates[2021] = new Pattern(new long[]{0L, 549760008224L, 4503633987108864L, 0L},
                    new long[]{0L, 4194336L, 4503633987108864L, 0L}, 88);
            immediates[2022] = new Pattern(new long[]{0L, 8388672L, 9007267974742016L, 0L},
                    new long[]{0L, 64L, 9007267974742016L, 0L}, 104);
            immediates[2023] =
                    new Pattern(new long[]{0L, 128L, 0x40002000100008L, 0L}, new long[]{0L, 0L, 0x40002000100008L, 0L},
                            120);
            immediates[2024] = new Pattern(new long[]{0L, 0L, 0x80004000200010L, 0x8000000000000L},
                    new long[]{0L, 0L, 274880004112L, 0x8000000000000L}, 136);
            immediates[2025] = new Pattern(new long[]{0L, 0L, 549760008224L, 4503633987108864L},
                    new long[]{0L, 0L, 4194336L, 4503633987108864L}, 152);
            immediates[2026] = new Pattern(new long[]{0L, 0L, 8388672L, 9007267974742016L},
                    new long[]{0L, 0L, 64L, 9007267974742016L}, 168);
            immediates[2027] =
                    new Pattern(new long[]{0L, 0L, 128L, 0x40002000100008L}, new long[]{0L, 0L, 0L, 0x40002000100008L},
                            184);
            immediates[2028] = new Pattern(new long[]{0x40002000100008L, 0x4000000000000L, 0L, 0L},
                    new long[]{137440002056L, 0x4000000000000L, 0L, 0L}, 9);
            immediates[2029] = new Pattern(new long[]{274880004112L, 0x8000400000000L, 0L, 0L},
                    new long[]{2097168L, 0x8000400000000L, 0L, 0L}, 25);
            immediates[2030] = new Pattern(new long[]{4194336L, 4503633987371008L, 0L, 0L},
                    new long[]{32L, 4503633987371008L, 0L, 0L}, 41);
            immediates[2031] =
                    new Pattern(new long[]{64L, 9007267974742020L, 0L, 0L}, new long[]{0L, 9007267974742020L, 0L, 0L},
                            57);
            immediates[2032] = new Pattern(new long[]{0L, 0x40002000100008L, 0x4000000000000L, 0L},
                    new long[]{0L, 137440002056L, 0x4000000000000L, 0L}, 73);
            immediates[2033] = new Pattern(new long[]{0L, 274880004112L, 0x8000400000000L, 0L},
                    new long[]{0L, 2097168L, 0x8000400000000L, 0L}, 89);
            immediates[2034] = new Pattern(new long[]{0L, 4194336L, 4503633987371008L, 0L},
                    new long[]{0L, 32L, 4503633987371008L, 0L}, 105);
            immediates[2035] =
                    new Pattern(new long[]{0L, 64L, 9007267974742020L, 0L}, new long[]{0L, 0L, 9007267974742020L, 0L},
                            121);
            immediates[2036] = new Pattern(new long[]{0L, 0L, 0x40002000100008L, 0x4000000000000L},
                    new long[]{0L, 0L, 137440002056L, 0x4000000000000L}, 137);
            immediates[2037] = new Pattern(new long[]{0L, 0L, 274880004112L, 0x8000400000000L},
                    new long[]{0L, 0L, 2097168L, 0x8000400000000L}, 153);
            immediates[2038] = new Pattern(new long[]{0L, 0L, 4194336L, 4503633987371008L},
                    new long[]{0L, 0L, 32L, 4503633987371008L}, 169);
            immediates[2039] =
                    new Pattern(new long[]{0L, 0L, 64L, 9007267974742020L}, new long[]{0L, 0L, 0L, 9007267974742020L},
                            185);
            immediates[2040] = new Pattern(new long[]{9007267974742020L, 562949953421312L, 0L, 0L},
                    new long[]{68720001028L, 562949953421312L, 0L, 0L}, 10);
            immediates[2041] = new Pattern(new long[]{137440002056L, 0x4000200000000L, 0L, 0L},
                    new long[]{1048584L, 0x4000200000000L, 0L, 0L}, 26);
            immediates[2042] = new Pattern(new long[]{2097168L, 0x8000400020000L, 0L, 0L},
                    new long[]{16L, 0x8000400020000L, 0L, 0L}, 42);
            immediates[2043] =
                    new Pattern(new long[]{32L, 4503633987371010L, 0L, 0L}, new long[]{0L, 4503633987371010L, 0L, 0L},
                            58);
            immediates[2044] = new Pattern(new long[]{0L, 9007267974742020L, 562949953421312L, 0L},
                    new long[]{0L, 68720001028L, 562949953421312L, 0L}, 74);
            immediates[2045] = new Pattern(new long[]{0L, 137440002056L, 0x4000200000000L, 0L},
                    new long[]{0L, 1048584L, 0x4000200000000L, 0L}, 90);
            immediates[2046] = new Pattern(new long[]{0L, 2097168L, 0x8000400020000L, 0L},
                    new long[]{0L, 16L, 0x8000400020000L, 0L}, 106);
            immediates[2047] =
                    new Pattern(new long[]{0L, 32L, 4503633987371010L, 0L}, new long[]{0L, 0L, 4503633987371010L, 0L},
                            122);
            immediates[2048] = new Pattern(new long[]{0L, 0L, 9007267974742020L, 562949953421312L},
                    new long[]{0L, 0L, 68720001028L, 562949953421312L}, 138);
            immediates[2049] = new Pattern(new long[]{0L, 0L, 137440002056L, 0x4000200000000L},
                    new long[]{0L, 0L, 1048584L, 0x4000200000000L}, 154);
            immediates[2050] = new Pattern(new long[]{0L, 0L, 2097168L, 0x8000400020000L},
                    new long[]{0L, 0L, 16L, 0x8000400020000L}, 170);
            immediates[2051] =
                    new Pattern(new long[]{0L, 0L, 32L, 4503633987371010L}, new long[]{0L, 0L, 0L, 4503633987371010L},
                            186);
            immediates[2052] = new Pattern(new long[]{4503633987371010L, 281474976710656L, 0L, 0L},
                    new long[]{34360000514L, 281474976710656L, 0L, 0L}, 11);
            immediates[2053] = new Pattern(new long[]{68720001028L, 562954248388608L, 0L, 0L},
                    new long[]{524292L, 562954248388608L, 0L, 0L}, 27);
            immediates[2054] = new Pattern(new long[]{1048584L, 0x4000200010000L, 0L, 0L},
                    new long[]{8L, 0x4000200010000L, 0L, 0L}, 43);
            immediates[2055] =
                    new Pattern(new long[]{16L, 0x8000400020001L, 0L, 0L}, new long[]{0L, 0x8000400020001L, 0L, 0L},
                            59);
            immediates[2056] = new Pattern(new long[]{0L, 4503633987371010L, 281474976710656L, 0L},
                    new long[]{0L, 34360000514L, 281474976710656L, 0L}, 75);
            immediates[2057] = new Pattern(new long[]{0L, 68720001028L, 562954248388608L, 0L},
                    new long[]{0L, 524292L, 562954248388608L, 0L}, 91);
            immediates[2058] = new Pattern(new long[]{0L, 1048584L, 0x4000200010000L, 0L},
                    new long[]{0L, 8L, 0x4000200010000L, 0L}, 107);
            immediates[2059] =
                    new Pattern(new long[]{0L, 16L, 0x8000400020001L, 0L}, new long[]{0L, 0L, 0x8000400020001L, 0L},
                            123);
            immediates[2060] = new Pattern(new long[]{0L, 0L, 4503633987371010L, 281474976710656L},
                    new long[]{0L, 0L, 34360000514L, 281474976710656L}, 139);
            immediates[2061] = new Pattern(new long[]{0L, 0L, 68720001028L, 562954248388608L},
                    new long[]{0L, 0L, 524292L, 562954248388608L}, 155);
            immediates[2062] = new Pattern(new long[]{0L, 0L, 1048584L, 0x4000200010000L},
                    new long[]{0L, 0L, 8L, 0x4000200010000L}, 171);
            immediates[2063] =
                    new Pattern(new long[]{0L, 0L, 16L, 0x8000400020001L}, new long[]{0L, 0L, 0L, 0x8000400020001L},
                            187);
            immediates[2064] = new Pattern(new long[]{0x8000400020001000L, 0x800000000000000L, 0L, 0L},
                    new long[]{0x8000000020001000L, 0x800000000000000L, 0L, 0L}, 17);
            immediates[2065] = new Pattern(new long[]{0x800040002000L, 0x1000080000000000L, 0L, 0L},
                    new long[]{0x800000002000L, 0x1000080000000000L, 0L, 0L}, 33);
            immediates[2066] = new Pattern(new long[]{2147500032L, 0x2000100008000000L, 0L, 0L},
                    new long[]{2147483648L, 0x2000100008000000L, 0L, 0L}, 49);
            immediates[2067] = new Pattern(new long[]{32768L, 0x4000200010000800L, 0L, 0L},
                    new long[]{32768L, 35184640526336L, 0L, 0L}, 65);
            immediates[2068] = new Pattern(new long[]{0L, 0x8000400020001000L, 0x800000000000000L, 0L},
                    new long[]{0L, 0x8000000020001000L, 0x800000000000000L, 0L}, 81);
            immediates[2069] = new Pattern(new long[]{0L, 0x800040002000L, 0x1000080000000000L, 0L},
                    new long[]{0L, 0x800000002000L, 0x1000080000000000L, 0L}, 97);
            immediates[2070] = new Pattern(new long[]{0L, 2147500032L, 0x2000100008000000L, 0L},
                    new long[]{0L, 2147483648L, 0x2000100008000000L, 0L}, 113);
            immediates[2071] = new Pattern(new long[]{0L, 32768L, 0x4000200010000800L, 0L},
                    new long[]{0L, 32768L, 35184640526336L, 0L}, 129);
            immediates[2072] = new Pattern(new long[]{0L, 0L, 0x8000400020001000L, 0x800000000000000L},
                    new long[]{0L, 0L, 0x8000000020001000L, 0x800000000000000L}, 145);
            immediates[2073] = new Pattern(new long[]{0L, 0L, 0x800040002000L, 0x1000080000000000L},
                    new long[]{0L, 0L, 0x800000002000L, 0x1000080000000000L}, 161);
            immediates[2074] = new Pattern(new long[]{0L, 0L, 2147500032L, 0x2000100008000000L},
                    new long[]{0L, 0L, 2147483648L, 0x2000100008000000L}, 177);
            immediates[2075] = new Pattern(new long[]{0L, 0L, 32768L, 0x4000200010000800L},
                    new long[]{0L, 0L, 32768L, 35184640526336L}, 193);
            immediates[2076] = new Pattern(new long[]{0x4000200010000800L, 0x400000000000000L, 0L, 0L},
                    new long[]{0x4000000010000800L, 0x400000000000000L, 0L, 0L}, 18);
            immediates[2077] = new Pattern(new long[]{70369281052672L, 0x800040000000000L, 0L, 0L},
                    new long[]{70368744181760L, 0x800040000000000L, 0L, 0L}, 34);
            immediates[2078] = new Pattern(new long[]{1073750016L, 0x1000080004000000L, 0L, 0L},
                    new long[]{1073741824L, 0x1000080004000000L, 0L, 0L}, 50);
            immediates[2079] = new Pattern(new long[]{16384L, 0x2000100008000400L, 0L, 0L},
                    new long[]{16384L, 17592320263168L, 0L, 0L}, 66);
            immediates[2080] = new Pattern(new long[]{0L, 0x4000200010000800L, 0x400000000000000L, 0L},
                    new long[]{0L, 0x4000000010000800L, 0x400000000000000L, 0L}, 82);
            immediates[2081] = new Pattern(new long[]{0L, 70369281052672L, 0x800040000000000L, 0L},
                    new long[]{0L, 70368744181760L, 0x800040000000000L, 0L}, 98);
            immediates[2082] = new Pattern(new long[]{0L, 1073750016L, 0x1000080004000000L, 0L},
                    new long[]{0L, 1073741824L, 0x1000080004000000L, 0L}, 114);
            immediates[2083] = new Pattern(new long[]{0L, 16384L, 0x2000100008000400L, 0L},
                    new long[]{0L, 16384L, 17592320263168L, 0L}, 130);
            immediates[2084] = new Pattern(new long[]{0L, 0L, 0x4000200010000800L, 0x400000000000000L},
                    new long[]{0L, 0L, 0x4000000010000800L, 0x400000000000000L}, 146);
            immediates[2085] = new Pattern(new long[]{0L, 0L, 70369281052672L, 0x800040000000000L},
                    new long[]{0L, 0L, 70368744181760L, 0x800040000000000L}, 162);
            immediates[2086] = new Pattern(new long[]{0L, 0L, 1073750016L, 0x1000080004000000L},
                    new long[]{0L, 0L, 1073741824L, 0x1000080004000000L}, 178);
            immediates[2087] = new Pattern(new long[]{0L, 0L, 16384L, 0x2000100008000400L},
                    new long[]{0L, 0L, 16384L, 17592320263168L}, 194);
            immediates[2088] = new Pattern(new long[]{0x2000100008000400L, 0x200000000000000L, 0L, 0L},
                    new long[]{0x2000000008000400L, 0x200000000000000L, 0L, 0L}, 19);
            immediates[2089] = new Pattern(new long[]{35184640526336L, 0x400020000000000L, 0L, 0L},
                    new long[]{35184372090880L, 0x400020000000000L, 0L, 0L}, 35);
            immediates[2090] = new Pattern(new long[]{536875008L, 0x800040002000000L, 0L, 0L},
                    new long[]{536870912L, 0x800040002000000L, 0L, 0L}, 51);
            immediates[2091] = new Pattern(new long[]{8192L, 0x1000080004000200L, 0L, 0L},
                    new long[]{8192L, 8796160131584L, 0L, 0L}, 67);
            immediates[2092] = new Pattern(new long[]{0L, 0x2000100008000400L, 0x200000000000000L, 0L},
                    new long[]{0L, 0x2000000008000400L, 0x200000000000000L, 0L}, 83);
            immediates[2093] = new Pattern(new long[]{0L, 35184640526336L, 0x400020000000000L, 0L},
                    new long[]{0L, 35184372090880L, 0x400020000000000L, 0L}, 99);
            immediates[2094] = new Pattern(new long[]{0L, 536875008L, 0x800040002000000L, 0L},
                    new long[]{0L, 536870912L, 0x800040002000000L, 0L}, 115);
            immediates[2095] = new Pattern(new long[]{0L, 8192L, 0x1000080004000200L, 0L},
                    new long[]{0L, 8192L, 8796160131584L, 0L}, 131);
            immediates[2096] = new Pattern(new long[]{0L, 0L, 0x2000100008000400L, 0x200000000000000L},
                    new long[]{0L, 0L, 0x2000000008000400L, 0x200000000000000L}, 147);
            immediates[2097] = new Pattern(new long[]{0L, 0L, 35184640526336L, 0x400020000000000L},
                    new long[]{0L, 0L, 35184372090880L, 0x400020000000000L}, 163);
            immediates[2098] = new Pattern(new long[]{0L, 0L, 536875008L, 0x800040002000000L},
                    new long[]{0L, 0L, 536870912L, 0x800040002000000L}, 179);
            immediates[2099] = new Pattern(new long[]{0L, 0L, 8192L, 0x1000080004000200L},
                    new long[]{0L, 0L, 8192L, 8796160131584L}, 195);
            immediates[2100] = new Pattern(new long[]{0x1000080004000200L, 72057594037927936L, 0L, 0L},
                    new long[]{0x1000000004000200L, 72057594037927936L, 0L, 0L}, 20);
            immediates[2101] = new Pattern(new long[]{17592320263168L, 0x200010000000000L, 0L, 0L},
                    new long[]{17592186045440L, 0x200010000000000L, 0L, 0L}, 36);
            immediates[2102] = new Pattern(new long[]{268437504L, 0x400020001000000L, 0L, 0L},
                    new long[]{268435456L, 0x400020001000000L, 0L, 0L}, 52);
            immediates[2103] = new Pattern(new long[]{4096L, 0x800040002000100L, 0L, 0L},
                    new long[]{4096L, 4398080065792L, 0L, 0L}, 68);
            immediates[2104] = new Pattern(new long[]{0L, 0x1000080004000200L, 72057594037927936L, 0L},
                    new long[]{0L, 0x1000000004000200L, 72057594037927936L, 0L}, 84);
            immediates[2105] = new Pattern(new long[]{0L, 17592320263168L, 0x200010000000000L, 0L},
                    new long[]{0L, 17592186045440L, 0x200010000000000L, 0L}, 100);
            immediates[2106] = new Pattern(new long[]{0L, 268437504L, 0x400020001000000L, 0L},
                    new long[]{0L, 268435456L, 0x400020001000000L, 0L}, 116);
            immediates[2107] = new Pattern(new long[]{0L, 4096L, 0x800040002000100L, 0L},
                    new long[]{0L, 4096L, 4398080065792L, 0L}, 132);
            immediates[2108] = new Pattern(new long[]{0L, 0L, 0x1000080004000200L, 72057594037927936L},
                    new long[]{0L, 0L, 0x1000000004000200L, 72057594037927936L}, 148);
            immediates[2109] = new Pattern(new long[]{0L, 0L, 17592320263168L, 0x200010000000000L},
                    new long[]{0L, 0L, 17592186045440L, 0x200010000000000L}, 164);
            immediates[2110] = new Pattern(new long[]{0L, 0L, 268437504L, 0x400020001000000L},
                    new long[]{0L, 0L, 268435456L, 0x400020001000000L}, 180);
            immediates[2111] = new Pattern(new long[]{0L, 0L, 4096L, 0x800040002000100L},
                    new long[]{0L, 0L, 4096L, 4398080065792L}, 196);
            immediates[2112] = new Pattern(new long[]{0x800040002000100L, 0x80000000000000L, 0L, 0L},
                    new long[]{0x800000002000100L, 0x80000000000000L, 0L, 0L}, 21);
            immediates[2113] = new Pattern(new long[]{8796160131584L, 72058143793741824L, 0L, 0L},
                    new long[]{8796093022720L, 72058143793741824L, 0L, 0L}, 37);
            immediates[2114] = new Pattern(new long[]{134218752L, 0x200010000800000L, 0L, 0L},
                    new long[]{134217728L, 0x200010000800000L, 0L, 0L}, 53);
            immediates[2115] = new Pattern(new long[]{2048L, 0x400020001000080L, 0L, 0L},
                    new long[]{2048L, 2199040032896L, 0L, 0L}, 69);
            immediates[2116] = new Pattern(new long[]{0L, 0x800040002000100L, 0x80000000000000L, 0L},
                    new long[]{0L, 0x800000002000100L, 0x80000000000000L, 0L}, 85);
            immediates[2117] = new Pattern(new long[]{0L, 8796160131584L, 72058143793741824L, 0L},
                    new long[]{0L, 8796093022720L, 72058143793741824L, 0L}, 101);
            immediates[2118] = new Pattern(new long[]{0L, 134218752L, 0x200010000800000L, 0L},
                    new long[]{0L, 134217728L, 0x200010000800000L, 0L}, 117);
            immediates[2119] = new Pattern(new long[]{0L, 2048L, 0x400020001000080L, 0L},
                    new long[]{0L, 2048L, 2199040032896L, 0L}, 133);
            immediates[2120] = new Pattern(new long[]{0L, 0L, 0x800040002000100L, 0x80000000000000L},
                    new long[]{0L, 0L, 0x800000002000100L, 0x80000000000000L}, 149);
            immediates[2121] = new Pattern(new long[]{0L, 0L, 8796160131584L, 72058143793741824L},
                    new long[]{0L, 0L, 8796093022720L, 72058143793741824L}, 165);
            immediates[2122] = new Pattern(new long[]{0L, 0L, 134218752L, 0x200010000800000L},
                    new long[]{0L, 0L, 134217728L, 0x200010000800000L}, 181);
            immediates[2123] = new Pattern(new long[]{0L, 0L, 2048L, 0x400020001000080L},
                    new long[]{0L, 0L, 2048L, 2199040032896L}, 197);
            immediates[2124] = new Pattern(new long[]{0x400020001000080L, 0x40000000000000L, 0L, 0L},
                    new long[]{0x400000001000080L, 0x40000000000000L, 0L, 0L}, 22);
            immediates[2125] = new Pattern(new long[]{4398080065792L, 0x80004000000000L, 0L, 0L},
                    new long[]{4398046511360L, 0x80004000000000L, 0L, 0L}, 38);
            immediates[2126] = new Pattern(new long[]{67109376L, 72058143797936128L, 0L, 0L},
                    new long[]{67108864L, 72058143797936128L, 0L, 0L}, 54);
            immediates[2127] = new Pattern(new long[]{1024L, 0x200010000800040L, 0L, 0L},
                    new long[]{1024L, 1099520016448L, 0L, 0L}, 70);
            immediates[2128] = new Pattern(new long[]{0L, 0x400020001000080L, 0x40000000000000L, 0L},
                    new long[]{0L, 0x400000001000080L, 0x40000000000000L, 0L}, 86);
            immediates[2129] = new Pattern(new long[]{0L, 4398080065792L, 0x80004000000000L, 0L},
                    new long[]{0L, 4398046511360L, 0x80004000000000L, 0L}, 102);
            immediates[2130] = new Pattern(new long[]{0L, 67109376L, 72058143797936128L, 0L},
                    new long[]{0L, 67108864L, 72058143797936128L, 0L}, 118);
            immediates[2131] = new Pattern(new long[]{0L, 1024L, 0x200010000800040L, 0L},
                    new long[]{0L, 1024L, 1099520016448L, 0L}, 134);
            immediates[2132] = new Pattern(new long[]{0L, 0L, 0x400020001000080L, 0x40000000000000L},
                    new long[]{0L, 0L, 0x400000001000080L, 0x40000000000000L}, 150);
            immediates[2133] = new Pattern(new long[]{0L, 0L, 4398080065792L, 0x80004000000000L},
                    new long[]{0L, 0L, 4398046511360L, 0x80004000000000L}, 166);
            immediates[2134] = new Pattern(new long[]{0L, 0L, 67109376L, 72058143797936128L},
                    new long[]{0L, 0L, 67108864L, 72058143797936128L}, 182);
            immediates[2135] = new Pattern(new long[]{0L, 0L, 1024L, 0x200010000800040L},
                    new long[]{0L, 0L, 1024L, 1099520016448L}, 198);
            immediates[2136] = new Pattern(new long[]{0x200010000800040L, 9007199254740992L, 0L, 0L},
                    new long[]{0x200000000800040L, 9007199254740992L, 0L, 0L}, 23);
            immediates[2137] = new Pattern(new long[]{2199040032896L, 0x40002000000000L, 0L, 0L},
                    new long[]{2199023255680L, 0x40002000000000L, 0L, 0L}, 39);
            immediates[2138] = new Pattern(new long[]{33554688L, 0x80004000200000L, 0L, 0L},
                    new long[]{33554432L, 0x80004000200000L, 0L, 0L}, 55);
            immediates[2139] =
                    new Pattern(new long[]{512L, 72058143797936160L, 0L, 0L}, new long[]{512L, 549760008224L, 0L, 0L},
                            71);
            immediates[2140] = new Pattern(new long[]{0L, 0x200010000800040L, 9007199254740992L, 0L},
                    new long[]{0L, 0x200000000800040L, 9007199254740992L, 0L}, 87);
            immediates[2141] = new Pattern(new long[]{0L, 2199040032896L, 0x40002000000000L, 0L},
                    new long[]{0L, 2199023255680L, 0x40002000000000L, 0L}, 103);
            immediates[2142] = new Pattern(new long[]{0L, 33554688L, 0x80004000200000L, 0L},
                    new long[]{0L, 33554432L, 0x80004000200000L, 0L}, 119);
            immediates[2143] =
                    new Pattern(new long[]{0L, 512L, 72058143797936160L, 0L}, new long[]{0L, 512L, 549760008224L, 0L},
                            135);
            immediates[2144] = new Pattern(new long[]{0L, 0L, 0x200010000800040L, 9007199254740992L},
                    new long[]{0L, 0L, 0x200000000800040L, 9007199254740992L}, 151);
            immediates[2145] = new Pattern(new long[]{0L, 0L, 2199040032896L, 0x40002000000000L},
                    new long[]{0L, 0L, 2199023255680L, 0x40002000000000L}, 167);
            immediates[2146] = new Pattern(new long[]{0L, 0L, 33554688L, 0x80004000200000L},
                    new long[]{0L, 0L, 33554432L, 0x80004000200000L}, 183);
            immediates[2147] =
                    new Pattern(new long[]{0L, 0L, 512L, 72058143797936160L}, new long[]{0L, 0L, 512L, 549760008224L},
                            199);
            immediates[2148] = new Pattern(new long[]{72058143797936160L, 4503599627370496L, 0L, 0L},
                    new long[]{72057594042122272L, 4503599627370496L, 0L, 0L}, 24);
            immediates[2149] = new Pattern(new long[]{1099520016448L, 9007267974217728L, 0L, 0L},
                    new long[]{1099511627840L, 9007267974217728L, 0L, 0L}, 40);
            immediates[2150] = new Pattern(new long[]{16777344L, 0x40002000100000L, 0L, 0L},
                    new long[]{16777216L, 0x40002000100000L, 0L, 0L}, 56);
            immediates[2151] =
                    new Pattern(new long[]{256L, 0x80004000200010L, 0L, 0L}, new long[]{256L, 274880004112L, 0L, 0L},
                            72);
            immediates[2152] = new Pattern(new long[]{0L, 72058143797936160L, 4503599627370496L, 0L},
                    new long[]{0L, 72057594042122272L, 4503599627370496L, 0L}, 88);
            immediates[2153] = new Pattern(new long[]{0L, 1099520016448L, 9007267974217728L, 0L},
                    new long[]{0L, 1099511627840L, 9007267974217728L, 0L}, 104);
            immediates[2154] = new Pattern(new long[]{0L, 16777344L, 0x40002000100000L, 0L},
                    new long[]{0L, 16777216L, 0x40002000100000L, 0L}, 120);
            immediates[2155] =
                    new Pattern(new long[]{0L, 256L, 0x80004000200010L, 0L}, new long[]{0L, 256L, 274880004112L, 0L},
                            136);
            immediates[2156] = new Pattern(new long[]{0L, 0L, 72058143797936160L, 4503599627370496L},
                    new long[]{0L, 0L, 72057594042122272L, 4503599627370496L}, 152);
            immediates[2157] = new Pattern(new long[]{0L, 0L, 1099520016448L, 9007267974217728L},
                    new long[]{0L, 0L, 1099511627840L, 9007267974217728L}, 168);
            immediates[2158] = new Pattern(new long[]{0L, 0L, 16777344L, 0x40002000100000L},
                    new long[]{0L, 0L, 16777216L, 0x40002000100000L}, 184);
            immediates[2159] =
                    new Pattern(new long[]{0L, 0L, 256L, 0x80004000200010L}, new long[]{0L, 0L, 256L, 274880004112L},
                            200);
            immediates[2160] = new Pattern(new long[]{0x80004000200010L, 0x8000000000000L, 0L, 0L},
                    new long[]{0x80000000200010L, 0x8000000000000L, 0L, 0L}, 25);
            immediates[2161] = new Pattern(new long[]{549760008224L, 4503633987108864L, 0L, 0L},
                    new long[]{549755813920L, 4503633987108864L, 0L, 0L}, 41);
            immediates[2162] = new Pattern(new long[]{8388672L, 9007267974742016L, 0L, 0L},
                    new long[]{8388608L, 9007267974742016L, 0L, 0L}, 57);
            immediates[2163] =
                    new Pattern(new long[]{128L, 0x40002000100008L, 0L, 0L}, new long[]{128L, 137440002056L, 0L, 0L},
                            73);
            immediates[2164] = new Pattern(new long[]{0L, 0x80004000200010L, 0x8000000000000L, 0L},
                    new long[]{0L, 0x80000000200010L, 0x8000000000000L, 0L}, 89);
            immediates[2165] = new Pattern(new long[]{0L, 549760008224L, 4503633987108864L, 0L},
                    new long[]{0L, 549755813920L, 4503633987108864L, 0L}, 105);
            immediates[2166] = new Pattern(new long[]{0L, 8388672L, 9007267974742016L, 0L},
                    new long[]{0L, 8388608L, 9007267974742016L, 0L}, 121);
            immediates[2167] =
                    new Pattern(new long[]{0L, 128L, 0x40002000100008L, 0L}, new long[]{0L, 128L, 137440002056L, 0L},
                            137);
            immediates[2168] = new Pattern(new long[]{0L, 0L, 0x80004000200010L, 0x8000000000000L},
                    new long[]{0L, 0L, 0x80000000200010L, 0x8000000000000L}, 153);
            immediates[2169] = new Pattern(new long[]{0L, 0L, 549760008224L, 4503633987108864L},
                    new long[]{0L, 0L, 549755813920L, 4503633987108864L}, 169);
            immediates[2170] = new Pattern(new long[]{0L, 0L, 8388672L, 9007267974742016L},
                    new long[]{0L, 0L, 8388608L, 9007267974742016L}, 185);
            immediates[2171] =
                    new Pattern(new long[]{0L, 0L, 128L, 0x40002000100008L}, new long[]{0L, 0L, 128L, 137440002056L},
                            201);
            immediates[2172] = new Pattern(new long[]{0x40002000100008L, 0x4000000000000L, 0L, 0L},
                    new long[]{0x40000000100008L, 0x4000000000000L, 0L, 0L}, 26);
            immediates[2173] = new Pattern(new long[]{274880004112L, 0x8000400000000L, 0L, 0L},
                    new long[]{274877906960L, 0x8000400000000L, 0L, 0L}, 42);
            immediates[2174] = new Pattern(new long[]{4194336L, 4503633987371008L, 0L, 0L},
                    new long[]{4194304L, 4503633987371008L, 0L, 0L}, 58);
            immediates[2175] =
                    new Pattern(new long[]{64L, 9007267974742020L, 0L, 0L}, new long[]{64L, 68720001028L, 0L, 0L}, 74);
            immediates[2176] = new Pattern(new long[]{0L, 0x40002000100008L, 0x4000000000000L, 0L},
                    new long[]{0L, 0x40000000100008L, 0x4000000000000L, 0L}, 90);
            immediates[2177] = new Pattern(new long[]{0L, 274880004112L, 0x8000400000000L, 0L},
                    new long[]{0L, 274877906960L, 0x8000400000000L, 0L}, 106);
            immediates[2178] = new Pattern(new long[]{0L, 4194336L, 4503633987371008L, 0L},
                    new long[]{0L, 4194304L, 4503633987371008L, 0L}, 122);
            immediates[2179] =
                    new Pattern(new long[]{0L, 64L, 9007267974742020L, 0L}, new long[]{0L, 64L, 68720001028L, 0L}, 138);
            immediates[2180] = new Pattern(new long[]{0L, 0L, 0x40002000100008L, 0x4000000000000L},
                    new long[]{0L, 0L, 0x40000000100008L, 0x4000000000000L}, 154);
            immediates[2181] = new Pattern(new long[]{0L, 0L, 274880004112L, 0x8000400000000L},
                    new long[]{0L, 0L, 274877906960L, 0x8000400000000L}, 170);
            immediates[2182] = new Pattern(new long[]{0L, 0L, 4194336L, 4503633987371008L},
                    new long[]{0L, 0L, 4194304L, 4503633987371008L}, 186);
            immediates[2183] =
                    new Pattern(new long[]{0L, 0L, 64L, 9007267974742020L}, new long[]{0L, 0L, 64L, 68720001028L}, 202);
            immediates[2184] = new Pattern(new long[]{9007267974742020L, 562949953421312L, 0L, 0L},
                    new long[]{9007199255265284L, 562949953421312L, 0L, 0L}, 27);
            immediates[2185] = new Pattern(new long[]{137440002056L, 0x4000200000000L, 0L, 0L},
                    new long[]{137438953480L, 0x4000200000000L, 0L, 0L}, 43);
            immediates[2186] = new Pattern(new long[]{2097168L, 0x8000400020000L, 0L, 0L},
                    new long[]{2097152L, 0x8000400020000L, 0L, 0L}, 59);
            immediates[2187] =
                    new Pattern(new long[]{32L, 4503633987371010L, 0L, 0L}, new long[]{32L, 34360000514L, 0L, 0L}, 75);
            immediates[2188] = new Pattern(new long[]{0L, 9007267974742020L, 562949953421312L, 0L},
                    new long[]{0L, 9007199255265284L, 562949953421312L, 0L}, 91);
            immediates[2189] = new Pattern(new long[]{0L, 137440002056L, 0x4000200000000L, 0L},
                    new long[]{0L, 137438953480L, 0x4000200000000L, 0L}, 107);
            immediates[2190] = new Pattern(new long[]{0L, 2097168L, 0x8000400020000L, 0L},
                    new long[]{0L, 2097152L, 0x8000400020000L, 0L}, 123);
            immediates[2191] =
                    new Pattern(new long[]{0L, 32L, 4503633987371010L, 0L}, new long[]{0L, 32L, 34360000514L, 0L}, 139);
            immediates[2192] = new Pattern(new long[]{0L, 0L, 9007267974742020L, 562949953421312L},
                    new long[]{0L, 0L, 9007199255265284L, 562949953421312L}, 155);
            immediates[2193] = new Pattern(new long[]{0L, 0L, 137440002056L, 0x4000200000000L},
                    new long[]{0L, 0L, 137438953480L, 0x4000200000000L}, 171);
            immediates[2194] = new Pattern(new long[]{0L, 0L, 2097168L, 0x8000400020000L},
                    new long[]{0L, 0L, 2097152L, 0x8000400020000L}, 187);
            immediates[2195] =
                    new Pattern(new long[]{0L, 0L, 32L, 4503633987371010L}, new long[]{0L, 0L, 32L, 34360000514L}, 203);
            immediates[2196] = new Pattern(new long[]{4503633987371010L, 281474976710656L, 0L, 0L},
                    new long[]{4503599627632642L, 281474976710656L, 0L, 0L}, 28);
            immediates[2197] = new Pattern(new long[]{68720001028L, 562954248388608L, 0L, 0L},
                    new long[]{68719476740L, 562954248388608L, 0L, 0L}, 44);
            immediates[2198] = new Pattern(new long[]{1048584L, 0x4000200010000L, 0L, 0L},
                    new long[]{1048576L, 0x4000200010000L, 0L, 0L}, 60);
            immediates[2199] =
                    new Pattern(new long[]{16L, 0x8000400020001L, 0L, 0L}, new long[]{16L, 17180000257L, 0L, 0L}, 76);
            immediates[2200] = new Pattern(new long[]{0L, 4503633987371010L, 281474976710656L, 0L},
                    new long[]{0L, 4503599627632642L, 281474976710656L, 0L}, 92);
            immediates[2201] = new Pattern(new long[]{0L, 68720001028L, 562954248388608L, 0L},
                    new long[]{0L, 68719476740L, 562954248388608L, 0L}, 108);
            immediates[2202] = new Pattern(new long[]{0L, 1048584L, 0x4000200010000L, 0L},
                    new long[]{0L, 1048576L, 0x4000200010000L, 0L}, 124);
            immediates[2203] =
                    new Pattern(new long[]{0L, 16L, 0x8000400020001L, 0L}, new long[]{0L, 16L, 17180000257L, 0L}, 140);
            immediates[2204] = new Pattern(new long[]{0L, 0L, 4503633987371010L, 281474976710656L},
                    new long[]{0L, 0L, 4503599627632642L, 281474976710656L}, 156);
            immediates[2205] = new Pattern(new long[]{0L, 0L, 68720001028L, 562954248388608L},
                    new long[]{0L, 0L, 68719476740L, 562954248388608L}, 172);
            immediates[2206] = new Pattern(new long[]{0L, 0L, 1048584L, 0x4000200010000L},
                    new long[]{0L, 0L, 1048576L, 0x4000200010000L}, 188);
            immediates[2207] =
                    new Pattern(new long[]{0L, 0L, 16L, 0x8000400020001L}, new long[]{0L, 0L, 16L, 17180000257L}, 204);
            immediates[2208] = new Pattern(new long[]{0x8000400020001000L, 0x800000000000000L, 0L, 0L},
                    new long[]{0x8000400000001000L, 0x800000000000000L, 0L, 0L}, 34);
            immediates[2209] = new Pattern(new long[]{0x800040002000L, 0x1000080000000000L, 0L, 0L},
                    new long[]{0x800040000000L, 0x1000080000000000L, 0L, 0L}, 50);
            immediates[2210] = new Pattern(new long[]{2147500032L, 0x2000100008000000L, 0L, 0L},
                    new long[]{2147500032L, 17592320262144L, 0L, 0L}, 66);
            immediates[2211] = new Pattern(new long[]{32768L, 0x4000200010000800L, 0L, 0L},
                    new long[]{32768L, 0x4000000010000800L, 0L, 0L}, 82);
            immediates[2212] = new Pattern(new long[]{0L, 0x8000400020001000L, 0x800000000000000L, 0L},
                    new long[]{0L, 0x8000400000001000L, 0x800000000000000L, 0L}, 98);
            immediates[2213] = new Pattern(new long[]{0L, 0x800040002000L, 0x1000080000000000L, 0L},
                    new long[]{0L, 0x800040000000L, 0x1000080000000000L, 0L}, 114);
            immediates[2214] = new Pattern(new long[]{0L, 2147500032L, 0x2000100008000000L, 0L},
                    new long[]{0L, 2147500032L, 17592320262144L, 0L}, 130);
            immediates[2215] = new Pattern(new long[]{0L, 32768L, 0x4000200010000800L, 0L},
                    new long[]{0L, 32768L, 0x4000000010000800L, 0L}, 146);
            immediates[2216] = new Pattern(new long[]{0L, 0L, 0x8000400020001000L, 0x800000000000000L},
                    new long[]{0L, 0L, 0x8000400000001000L, 0x800000000000000L}, 162);
            immediates[2217] = new Pattern(new long[]{0L, 0L, 0x800040002000L, 0x1000080000000000L},
                    new long[]{0L, 0L, 0x800040000000L, 0x1000080000000000L}, 178);
            immediates[2218] = new Pattern(new long[]{0L, 0L, 2147500032L, 0x2000100008000000L},
                    new long[]{0L, 0L, 2147500032L, 17592320262144L}, 194);
            immediates[2219] = new Pattern(new long[]{0L, 0L, 32768L, 0x4000200010000800L},
                    new long[]{0L, 0L, 32768L, 0x4000000010000800L}, 210);
            immediates[2220] = new Pattern(new long[]{0x4000200010000800L, 0x400000000000000L, 0L, 0L},
                    new long[]{0x4000200000000800L, 0x400000000000000L, 0L, 0L}, 35);
            immediates[2221] = new Pattern(new long[]{70369281052672L, 0x800040000000000L, 0L, 0L},
                    new long[]{70369281048576L, 0x800040000000000L, 0L, 0L}, 51);
            immediates[2222] = new Pattern(new long[]{1073750016L, 0x1000080004000000L, 0L, 0L},
                    new long[]{1073750016L, 8796160131072L, 0L, 0L}, 67);
            immediates[2223] = new Pattern(new long[]{16384L, 0x2000100008000400L, 0L, 0L},
                    new long[]{16384L, 0x2000000008000400L, 0L, 0L}, 83);
            immediates[2224] = new Pattern(new long[]{0L, 0x4000200010000800L, 0x400000000000000L, 0L},
                    new long[]{0L, 0x4000200000000800L, 0x400000000000000L, 0L}, 99);
            immediates[2225] = new Pattern(new long[]{0L, 70369281052672L, 0x800040000000000L, 0L},
                    new long[]{0L, 70369281048576L, 0x800040000000000L, 0L}, 115);
            immediates[2226] = new Pattern(new long[]{0L, 1073750016L, 0x1000080004000000L, 0L},
                    new long[]{0L, 1073750016L, 8796160131072L, 0L}, 131);
            immediates[2227] = new Pattern(new long[]{0L, 16384L, 0x2000100008000400L, 0L},
                    new long[]{0L, 16384L, 0x2000000008000400L, 0L}, 147);
            immediates[2228] = new Pattern(new long[]{0L, 0L, 0x4000200010000800L, 0x400000000000000L},
                    new long[]{0L, 0L, 0x4000200000000800L, 0x400000000000000L}, 163);
            immediates[2229] = new Pattern(new long[]{0L, 0L, 70369281052672L, 0x800040000000000L},
                    new long[]{0L, 0L, 70369281048576L, 0x800040000000000L}, 179);
            immediates[2230] = new Pattern(new long[]{0L, 0L, 1073750016L, 0x1000080004000000L},
                    new long[]{0L, 0L, 1073750016L, 8796160131072L}, 195);
            immediates[2231] = new Pattern(new long[]{0L, 0L, 16384L, 0x2000100008000400L},
                    new long[]{0L, 0L, 16384L, 0x2000000008000400L}, 211);
            immediates[2232] = new Pattern(new long[]{0x2000100008000400L, 0x200000000000000L, 0L, 0L},
                    new long[]{0x2000100000000400L, 0x200000000000000L, 0L, 0L}, 36);
            immediates[2233] = new Pattern(new long[]{35184640526336L, 0x400020000000000L, 0L, 0L},
                    new long[]{35184640524288L, 0x400020000000000L, 0L, 0L}, 52);
            immediates[2234] = new Pattern(new long[]{536875008L, 0x800040002000000L, 0L, 0L},
                    new long[]{536875008L, 4398080065536L, 0L, 0L}, 68);
            immediates[2235] = new Pattern(new long[]{8192L, 0x1000080004000200L, 0L, 0L},
                    new long[]{8192L, 0x1000000004000200L, 0L, 0L}, 84);
            immediates[2236] = new Pattern(new long[]{0L, 0x2000100008000400L, 0x200000000000000L, 0L},
                    new long[]{0L, 0x2000100000000400L, 0x200000000000000L, 0L}, 100);
            immediates[2237] = new Pattern(new long[]{0L, 35184640526336L, 0x400020000000000L, 0L},
                    new long[]{0L, 35184640524288L, 0x400020000000000L, 0L}, 116);
            immediates[2238] = new Pattern(new long[]{0L, 536875008L, 0x800040002000000L, 0L},
                    new long[]{0L, 536875008L, 4398080065536L, 0L}, 132);
            immediates[2239] = new Pattern(new long[]{0L, 8192L, 0x1000080004000200L, 0L},
                    new long[]{0L, 8192L, 0x1000000004000200L, 0L}, 148);
            immediates[2240] = new Pattern(new long[]{0L, 0L, 0x2000100008000400L, 0x200000000000000L},
                    new long[]{0L, 0L, 0x2000100000000400L, 0x200000000000000L}, 164);
            immediates[2241] = new Pattern(new long[]{0L, 0L, 35184640526336L, 0x400020000000000L},
                    new long[]{0L, 0L, 35184640524288L, 0x400020000000000L}, 180);
            immediates[2242] = new Pattern(new long[]{0L, 0L, 536875008L, 0x800040002000000L},
                    new long[]{0L, 0L, 536875008L, 4398080065536L}, 196);
            immediates[2243] = new Pattern(new long[]{0L, 0L, 8192L, 0x1000080004000200L},
                    new long[]{0L, 0L, 8192L, 0x1000000004000200L}, 212);
            immediates[2244] = new Pattern(new long[]{0x1000080004000200L, 72057594037927936L, 0L, 0L},
                    new long[]{0x1000080000000200L, 72057594037927936L, 0L, 0L}, 37);
            immediates[2245] = new Pattern(new long[]{17592320263168L, 0x200010000000000L, 0L, 0L},
                    new long[]{17592320262144L, 0x200010000000000L, 0L, 0L}, 53);
            immediates[2246] = new Pattern(new long[]{268437504L, 0x400020001000000L, 0L, 0L},
                    new long[]{268437504L, 2199040032768L, 0L, 0L}, 69);
            immediates[2247] = new Pattern(new long[]{4096L, 0x800040002000100L, 0L, 0L},
                    new long[]{4096L, 0x800000002000100L, 0L, 0L}, 85);
            immediates[2248] = new Pattern(new long[]{0L, 0x1000080004000200L, 72057594037927936L, 0L},
                    new long[]{0L, 0x1000080000000200L, 72057594037927936L, 0L}, 101);
            immediates[2249] = new Pattern(new long[]{0L, 17592320263168L, 0x200010000000000L, 0L},
                    new long[]{0L, 17592320262144L, 0x200010000000000L, 0L}, 117);
            immediates[2250] = new Pattern(new long[]{0L, 268437504L, 0x400020001000000L, 0L},
                    new long[]{0L, 268437504L, 2199040032768L, 0L}, 133);
            immediates[2251] = new Pattern(new long[]{0L, 4096L, 0x800040002000100L, 0L},
                    new long[]{0L, 4096L, 0x800000002000100L, 0L}, 149);
            immediates[2252] = new Pattern(new long[]{0L, 0L, 0x1000080004000200L, 72057594037927936L},
                    new long[]{0L, 0L, 0x1000080000000200L, 72057594037927936L}, 165);
            immediates[2253] = new Pattern(new long[]{0L, 0L, 17592320263168L, 0x200010000000000L},
                    new long[]{0L, 0L, 17592320262144L, 0x200010000000000L}, 181);
            immediates[2254] = new Pattern(new long[]{0L, 0L, 268437504L, 0x400020001000000L},
                    new long[]{0L, 0L, 268437504L, 2199040032768L}, 197);
            immediates[2255] = new Pattern(new long[]{0L, 0L, 4096L, 0x800040002000100L},
                    new long[]{0L, 0L, 4096L, 0x800000002000100L}, 213);
            immediates[2256] = new Pattern(new long[]{0x800040002000100L, 0x80000000000000L, 0L, 0L},
                    new long[]{0x800040000000100L, 0x80000000000000L, 0L, 0L}, 38);
            immediates[2257] = new Pattern(new long[]{8796160131584L, 72058143793741824L, 0L, 0L},
                    new long[]{8796160131072L, 72058143793741824L, 0L, 0L}, 54);
            immediates[2258] = new Pattern(new long[]{134218752L, 0x200010000800000L, 0L, 0L},
                    new long[]{134218752L, 1099520016384L, 0L, 0L}, 70);
            immediates[2259] = new Pattern(new long[]{2048L, 0x400020001000080L, 0L, 0L},
                    new long[]{2048L, 0x400000001000080L, 0L, 0L}, 86);
            immediates[2260] = new Pattern(new long[]{0L, 0x800040002000100L, 0x80000000000000L, 0L},
                    new long[]{0L, 0x800040000000100L, 0x80000000000000L, 0L}, 102);
            immediates[2261] = new Pattern(new long[]{0L, 8796160131584L, 72058143793741824L, 0L},
                    new long[]{0L, 8796160131072L, 72058143793741824L, 0L}, 118);
            immediates[2262] = new Pattern(new long[]{0L, 134218752L, 0x200010000800000L, 0L},
                    new long[]{0L, 134218752L, 1099520016384L, 0L}, 134);
            immediates[2263] = new Pattern(new long[]{0L, 2048L, 0x400020001000080L, 0L},
                    new long[]{0L, 2048L, 0x400000001000080L, 0L}, 150);
            immediates[2264] = new Pattern(new long[]{0L, 0L, 0x800040002000100L, 0x80000000000000L},
                    new long[]{0L, 0L, 0x800040000000100L, 0x80000000000000L}, 166);
            immediates[2265] = new Pattern(new long[]{0L, 0L, 8796160131584L, 72058143793741824L},
                    new long[]{0L, 0L, 8796160131072L, 72058143793741824L}, 182);
            immediates[2266] = new Pattern(new long[]{0L, 0L, 134218752L, 0x200010000800000L},
                    new long[]{0L, 0L, 134218752L, 1099520016384L}, 198);
            immediates[2267] = new Pattern(new long[]{0L, 0L, 2048L, 0x400020001000080L},
                    new long[]{0L, 0L, 2048L, 0x400000001000080L}, 214);
            immediates[2268] = new Pattern(new long[]{0x400020001000080L, 0x40000000000000L, 0L, 0L},
                    new long[]{0x400020000000080L, 0x40000000000000L, 0L, 0L}, 39);
            immediates[2269] = new Pattern(new long[]{4398080065792L, 0x80004000000000L, 0L, 0L},
                    new long[]{4398080065536L, 0x80004000000000L, 0L, 0L}, 55);
            immediates[2270] = new Pattern(new long[]{67109376L, 72058143797936128L, 0L, 0L},
                    new long[]{67109376L, 549760008192L, 0L, 0L}, 71);
            immediates[2271] = new Pattern(new long[]{1024L, 0x200010000800040L, 0L, 0L},
                    new long[]{1024L, 0x200000000800040L, 0L, 0L}, 87);
            immediates[2272] = new Pattern(new long[]{0L, 0x400020001000080L, 0x40000000000000L, 0L},
                    new long[]{0L, 0x400020000000080L, 0x40000000000000L, 0L}, 103);
            immediates[2273] = new Pattern(new long[]{0L, 4398080065792L, 0x80004000000000L, 0L},
                    new long[]{0L, 4398080065536L, 0x80004000000000L, 0L}, 119);
            immediates[2274] = new Pattern(new long[]{0L, 67109376L, 72058143797936128L, 0L},
                    new long[]{0L, 67109376L, 549760008192L, 0L}, 135);
            immediates[2275] = new Pattern(new long[]{0L, 1024L, 0x200010000800040L, 0L},
                    new long[]{0L, 1024L, 0x200000000800040L, 0L}, 151);
            immediates[2276] = new Pattern(new long[]{0L, 0L, 0x400020001000080L, 0x40000000000000L},
                    new long[]{0L, 0L, 0x400020000000080L, 0x40000000000000L}, 167);
            immediates[2277] = new Pattern(new long[]{0L, 0L, 4398080065792L, 0x80004000000000L},
                    new long[]{0L, 0L, 4398080065536L, 0x80004000000000L}, 183);
            immediates[2278] = new Pattern(new long[]{0L, 0L, 67109376L, 72058143797936128L},
                    new long[]{0L, 0L, 67109376L, 549760008192L}, 199);
            immediates[2279] = new Pattern(new long[]{0L, 0L, 1024L, 0x200010000800040L},
                    new long[]{0L, 0L, 1024L, 0x200000000800040L}, 215);
            immediates[2280] = new Pattern(new long[]{0x200010000800040L, 9007199254740992L, 0L, 0L},
                    new long[]{0x200010000000040L, 9007199254740992L, 0L, 0L}, 40);
            immediates[2281] = new Pattern(new long[]{2199040032896L, 0x40002000000000L, 0L, 0L},
                    new long[]{2199040032768L, 0x40002000000000L, 0L, 0L}, 56);
            immediates[2282] = new Pattern(new long[]{33554688L, 0x80004000200000L, 0L, 0L},
                    new long[]{33554688L, 274880004096L, 0L, 0L}, 72);
            immediates[2283] = new Pattern(new long[]{512L, 72058143797936160L, 0L, 0L},
                    new long[]{512L, 72057594042122272L, 0L, 0L}, 88);
            immediates[2284] = new Pattern(new long[]{0L, 0x200010000800040L, 9007199254740992L, 0L},
                    new long[]{0L, 0x200010000000040L, 9007199254740992L, 0L}, 104);
            immediates[2285] = new Pattern(new long[]{0L, 2199040032896L, 0x40002000000000L, 0L},
                    new long[]{0L, 2199040032768L, 0x40002000000000L, 0L}, 120);
            immediates[2286] = new Pattern(new long[]{0L, 33554688L, 0x80004000200000L, 0L},
                    new long[]{0L, 33554688L, 274880004096L, 0L}, 136);
            immediates[2287] = new Pattern(new long[]{0L, 512L, 72058143797936160L, 0L},
                    new long[]{0L, 512L, 72057594042122272L, 0L}, 152);
            immediates[2288] = new Pattern(new long[]{0L, 0L, 0x200010000800040L, 9007199254740992L},
                    new long[]{0L, 0L, 0x200010000000040L, 9007199254740992L}, 168);
            immediates[2289] = new Pattern(new long[]{0L, 0L, 2199040032896L, 0x40002000000000L},
                    new long[]{0L, 0L, 2199040032768L, 0x40002000000000L}, 184);
            immediates[2290] = new Pattern(new long[]{0L, 0L, 33554688L, 0x80004000200000L},
                    new long[]{0L, 0L, 33554688L, 274880004096L}, 200);
            immediates[2291] = new Pattern(new long[]{0L, 0L, 512L, 72058143797936160L},
                    new long[]{0L, 0L, 512L, 72057594042122272L}, 216);
            immediates[2292] = new Pattern(new long[]{72058143797936160L, 4503599627370496L, 0L, 0L},
                    new long[]{72058143793741856L, 4503599627370496L, 0L, 0L}, 41);
            immediates[2293] = new Pattern(new long[]{1099520016448L, 9007267974217728L, 0L, 0L},
                    new long[]{1099520016384L, 9007267974217728L, 0L, 0L}, 57);
            immediates[2294] = new Pattern(new long[]{16777344L, 0x40002000100000L, 0L, 0L},
                    new long[]{16777344L, 137440002048L, 0L, 0L}, 73);
            immediates[2295] = new Pattern(new long[]{256L, 0x80004000200010L, 0L, 0L},
                    new long[]{256L, 0x80000000200010L, 0L, 0L}, 89);
            immediates[2296] = new Pattern(new long[]{0L, 72058143797936160L, 4503599627370496L, 0L},
                    new long[]{0L, 72058143793741856L, 4503599627370496L, 0L}, 105);
            immediates[2297] = new Pattern(new long[]{0L, 1099520016448L, 9007267974217728L, 0L},
                    new long[]{0L, 1099520016384L, 9007267974217728L, 0L}, 121);
            immediates[2298] = new Pattern(new long[]{0L, 16777344L, 0x40002000100000L, 0L},
                    new long[]{0L, 16777344L, 137440002048L, 0L}, 137);
            immediates[2299] = new Pattern(new long[]{0L, 256L, 0x80004000200010L, 0L},
                    new long[]{0L, 256L, 0x80000000200010L, 0L}, 153);
            immediates[2300] = new Pattern(new long[]{0L, 0L, 72058143797936160L, 4503599627370496L},
                    new long[]{0L, 0L, 72058143793741856L, 4503599627370496L}, 169);
            immediates[2301] = new Pattern(new long[]{0L, 0L, 1099520016448L, 9007267974217728L},
                    new long[]{0L, 0L, 1099520016384L, 9007267974217728L}, 185);
            immediates[2302] = new Pattern(new long[]{0L, 0L, 16777344L, 0x40002000100000L},
                    new long[]{0L, 0L, 16777344L, 137440002048L}, 201);
            immediates[2303] = new Pattern(new long[]{0L, 0L, 256L, 0x80004000200010L},
                    new long[]{0L, 0L, 256L, 0x80000000200010L}, 217);
            immediates[2304] = new Pattern(new long[]{0x80004000200010L, 0x8000000000000L, 0L, 0L},
                    new long[]{0x80004000000010L, 0x8000000000000L, 0L, 0L}, 42);
            immediates[2305] = new Pattern(new long[]{549760008224L, 4503633987108864L, 0L, 0L},
                    new long[]{549760008192L, 4503633987108864L, 0L, 0L}, 58);
            immediates[2306] = new Pattern(new long[]{8388672L, 9007267974742016L, 0L, 0L},
                    new long[]{8388672L, 68720001024L, 0L, 0L}, 74);
            immediates[2307] = new Pattern(new long[]{128L, 0x40002000100008L, 0L, 0L},
                    new long[]{128L, 0x40000000100008L, 0L, 0L}, 90);
            immediates[2308] = new Pattern(new long[]{0L, 0x80004000200010L, 0x8000000000000L, 0L},
                    new long[]{0L, 0x80004000000010L, 0x8000000000000L, 0L}, 106);
            immediates[2309] = new Pattern(new long[]{0L, 549760008224L, 4503633987108864L, 0L},
                    new long[]{0L, 549760008192L, 4503633987108864L, 0L}, 122);
            immediates[2310] = new Pattern(new long[]{0L, 8388672L, 9007267974742016L, 0L},
                    new long[]{0L, 8388672L, 68720001024L, 0L}, 138);
            immediates[2311] = new Pattern(new long[]{0L, 128L, 0x40002000100008L, 0L},
                    new long[]{0L, 128L, 0x40000000100008L, 0L}, 154);
            immediates[2312] = new Pattern(new long[]{0L, 0L, 0x80004000200010L, 0x8000000000000L},
                    new long[]{0L, 0L, 0x80004000000010L, 0x8000000000000L}, 170);
            immediates[2313] = new Pattern(new long[]{0L, 0L, 549760008224L, 4503633987108864L},
                    new long[]{0L, 0L, 549760008192L, 4503633987108864L}, 186);
            immediates[2314] = new Pattern(new long[]{0L, 0L, 8388672L, 9007267974742016L},
                    new long[]{0L, 0L, 8388672L, 68720001024L}, 202);
            immediates[2315] = new Pattern(new long[]{0L, 0L, 128L, 0x40002000100008L},
                    new long[]{0L, 0L, 128L, 0x40000000100008L}, 218);
            immediates[2316] = new Pattern(new long[]{0x40002000100008L, 0x4000000000000L, 0L, 0L},
                    new long[]{0x40002000000008L, 0x4000000000000L, 0L, 0L}, 43);
            immediates[2317] = new Pattern(new long[]{274880004112L, 0x8000400000000L, 0L, 0L},
                    new long[]{274880004096L, 0x8000400000000L, 0L, 0L}, 59);
            immediates[2318] = new Pattern(new long[]{4194336L, 4503633987371008L, 0L, 0L},
                    new long[]{4194336L, 34360000512L, 0L, 0L}, 75);
            immediates[2319] =
                    new Pattern(new long[]{64L, 9007267974742020L, 0L, 0L}, new long[]{64L, 9007199255265284L, 0L, 0L},
                            91);
            immediates[2320] = new Pattern(new long[]{0L, 0x40002000100008L, 0x4000000000000L, 0L},
                    new long[]{0L, 0x40002000000008L, 0x4000000000000L, 0L}, 107);
            immediates[2321] = new Pattern(new long[]{0L, 274880004112L, 0x8000400000000L, 0L},
                    new long[]{0L, 274880004096L, 0x8000400000000L, 0L}, 123);
            immediates[2322] = new Pattern(new long[]{0L, 4194336L, 4503633987371008L, 0L},
                    new long[]{0L, 4194336L, 34360000512L, 0L}, 139);
            immediates[2323] =
                    new Pattern(new long[]{0L, 64L, 9007267974742020L, 0L}, new long[]{0L, 64L, 9007199255265284L, 0L},
                            155);
            immediates[2324] = new Pattern(new long[]{0L, 0L, 0x40002000100008L, 0x4000000000000L},
                    new long[]{0L, 0L, 0x40002000000008L, 0x4000000000000L}, 171);
            immediates[2325] = new Pattern(new long[]{0L, 0L, 274880004112L, 0x8000400000000L},
                    new long[]{0L, 0L, 274880004096L, 0x8000400000000L}, 187);
            immediates[2326] = new Pattern(new long[]{0L, 0L, 4194336L, 4503633987371008L},
                    new long[]{0L, 0L, 4194336L, 34360000512L}, 203);
            immediates[2327] =
                    new Pattern(new long[]{0L, 0L, 64L, 9007267974742020L}, new long[]{0L, 0L, 64L, 9007199255265284L},
                            219);
            immediates[2328] = new Pattern(new long[]{9007267974742020L, 562949953421312L, 0L, 0L},
                    new long[]{9007267974217732L, 562949953421312L, 0L, 0L}, 44);
            immediates[2329] = new Pattern(new long[]{137440002056L, 0x4000200000000L, 0L, 0L},
                    new long[]{137440002048L, 0x4000200000000L, 0L, 0L}, 60);
            immediates[2330] = new Pattern(new long[]{2097168L, 0x8000400020000L, 0L, 0L},
                    new long[]{2097168L, 17180000256L, 0L, 0L}, 76);
            immediates[2331] =
                    new Pattern(new long[]{32L, 4503633987371010L, 0L, 0L}, new long[]{32L, 4503599627632642L, 0L, 0L},
                            92);
            immediates[2332] = new Pattern(new long[]{0L, 9007267974742020L, 562949953421312L, 0L},
                    new long[]{0L, 9007267974217732L, 562949953421312L, 0L}, 108);
            immediates[2333] = new Pattern(new long[]{0L, 137440002056L, 0x4000200000000L, 0L},
                    new long[]{0L, 137440002048L, 0x4000200000000L, 0L}, 124);
            immediates[2334] = new Pattern(new long[]{0L, 2097168L, 0x8000400020000L, 0L},
                    new long[]{0L, 2097168L, 17180000256L, 0L}, 140);
            immediates[2335] =
                    new Pattern(new long[]{0L, 32L, 4503633987371010L, 0L}, new long[]{0L, 32L, 4503599627632642L, 0L},
                            156);
            immediates[2336] = new Pattern(new long[]{0L, 0L, 9007267974742020L, 562949953421312L},
                    new long[]{0L, 0L, 9007267974217732L, 562949953421312L}, 172);
            immediates[2337] = new Pattern(new long[]{0L, 0L, 137440002056L, 0x4000200000000L},
                    new long[]{0L, 0L, 137440002048L, 0x4000200000000L}, 188);
            immediates[2338] = new Pattern(new long[]{0L, 0L, 2097168L, 0x8000400020000L},
                    new long[]{0L, 0L, 2097168L, 17180000256L}, 204);
            immediates[2339] =
                    new Pattern(new long[]{0L, 0L, 32L, 4503633987371010L}, new long[]{0L, 0L, 32L, 4503599627632642L},
                            220);
            immediates[2340] = new Pattern(new long[]{4503633987371010L, 281474976710656L, 0L, 0L},
                    new long[]{4503633987108866L, 281474976710656L, 0L, 0L}, 45);
            immediates[2341] = new Pattern(new long[]{68720001028L, 562954248388608L, 0L, 0L},
                    new long[]{68720001024L, 562954248388608L, 0L, 0L}, 61);
            immediates[2342] = new Pattern(new long[]{1048584L, 0x4000200010000L, 0L, 0L},
                    new long[]{1048584L, 8590000128L, 0L, 0L}, 77);
            immediates[2343] =
                    new Pattern(new long[]{16L, 0x8000400020001L, 0L, 0L}, new long[]{16L, 0x8000000020001L, 0L, 0L},
                            93);
            immediates[2344] = new Pattern(new long[]{0L, 4503633987371010L, 281474976710656L, 0L},
                    new long[]{0L, 4503633987108866L, 281474976710656L, 0L}, 109);
            immediates[2345] = new Pattern(new long[]{0L, 68720001028L, 562954248388608L, 0L},
                    new long[]{0L, 68720001024L, 562954248388608L, 0L}, 125);
            immediates[2346] = new Pattern(new long[]{0L, 1048584L, 0x4000200010000L, 0L},
                    new long[]{0L, 1048584L, 8590000128L, 0L}, 141);
            immediates[2347] =
                    new Pattern(new long[]{0L, 16L, 0x8000400020001L, 0L}, new long[]{0L, 16L, 0x8000000020001L, 0L},
                            157);
            immediates[2348] = new Pattern(new long[]{0L, 0L, 4503633987371010L, 281474976710656L},
                    new long[]{0L, 0L, 4503633987108866L, 281474976710656L}, 173);
            immediates[2349] = new Pattern(new long[]{0L, 0L, 68720001028L, 562954248388608L},
                    new long[]{0L, 0L, 68720001024L, 562954248388608L}, 189);
            immediates[2350] = new Pattern(new long[]{0L, 0L, 1048584L, 0x4000200010000L},
                    new long[]{0L, 0L, 1048584L, 8590000128L}, 205);
            immediates[2351] =
                    new Pattern(new long[]{0L, 0L, 16L, 0x8000400020001L}, new long[]{0L, 0L, 16L, 0x8000000020001L},
                            221);
            immediates[2352] = new Pattern(new long[]{0x8000400020001000L, 0x800000000000000L, 0L, 0L},
                    new long[]{0x8000400020000000L, 0x800000000000000L, 0L, 0L}, 51);
            immediates[2353] = new Pattern(new long[]{0x800040002000L, 0x1000080000000000L, 0L, 0L},
                    new long[]{0x800040002000L, 8796093022208L, 0L, 0L}, 67);
            immediates[2354] = new Pattern(new long[]{2147500032L, 0x2000100008000000L, 0L, 0L},
                    new long[]{2147500032L, 0x2000000008000000L, 0L, 0L}, 83);
            immediates[2355] = new Pattern(new long[]{32768L, 0x4000200010000800L, 0L, 0L},
                    new long[]{32768L, 0x4000200000000800L, 0L, 0L}, 99);
            immediates[2356] = new Pattern(new long[]{0L, 0x8000400020001000L, 0x800000000000000L, 0L},
                    new long[]{0L, 0x8000400020000000L, 0x800000000000000L, 0L}, 115);
            immediates[2357] = new Pattern(new long[]{0L, 0x800040002000L, 0x1000080000000000L, 0L},
                    new long[]{0L, 0x800040002000L, 8796093022208L, 0L}, 131);
            immediates[2358] = new Pattern(new long[]{0L, 2147500032L, 0x2000100008000000L, 0L},
                    new long[]{0L, 2147500032L, 0x2000000008000000L, 0L}, 147);
            immediates[2359] = new Pattern(new long[]{0L, 32768L, 0x4000200010000800L, 0L},
                    new long[]{0L, 32768L, 0x4000200000000800L, 0L}, 163);
            immediates[2360] = new Pattern(new long[]{0L, 0L, 0x8000400020001000L, 0x800000000000000L},
                    new long[]{0L, 0L, 0x8000400020000000L, 0x800000000000000L}, 179);
            immediates[2361] = new Pattern(new long[]{0L, 0L, 0x800040002000L, 0x1000080000000000L},
                    new long[]{0L, 0L, 0x800040002000L, 8796093022208L}, 195);
            immediates[2362] = new Pattern(new long[]{0L, 0L, 2147500032L, 0x2000100008000000L},
                    new long[]{0L, 0L, 2147500032L, 0x2000000008000000L}, 211);
            immediates[2363] = new Pattern(new long[]{0L, 0L, 32768L, 0x4000200010000800L},
                    new long[]{0L, 0L, 32768L, 0x4000200000000800L}, 227);
            immediates[2364] = new Pattern(new long[]{0x4000200010000800L, 0x400000000000000L, 0L, 0L},
                    new long[]{0x4000200010000000L, 0x400000000000000L, 0L, 0L}, 52);
            immediates[2365] = new Pattern(new long[]{70369281052672L, 0x800040000000000L, 0L, 0L},
                    new long[]{70369281052672L, 4398046511104L, 0L, 0L}, 68);
            immediates[2366] = new Pattern(new long[]{1073750016L, 0x1000080004000000L, 0L, 0L},
                    new long[]{1073750016L, 0x1000000004000000L, 0L, 0L}, 84);
            immediates[2367] = new Pattern(new long[]{16384L, 0x2000100008000400L, 0L, 0L},
                    new long[]{16384L, 0x2000100000000400L, 0L, 0L}, 100);
            immediates[2368] = new Pattern(new long[]{0L, 0x4000200010000800L, 0x400000000000000L, 0L},
                    new long[]{0L, 0x4000200010000000L, 0x400000000000000L, 0L}, 116);
            immediates[2369] = new Pattern(new long[]{0L, 70369281052672L, 0x800040000000000L, 0L},
                    new long[]{0L, 70369281052672L, 4398046511104L, 0L}, 132);
            immediates[2370] = new Pattern(new long[]{0L, 1073750016L, 0x1000080004000000L, 0L},
                    new long[]{0L, 1073750016L, 0x1000000004000000L, 0L}, 148);
            immediates[2371] = new Pattern(new long[]{0L, 16384L, 0x2000100008000400L, 0L},
                    new long[]{0L, 16384L, 0x2000100000000400L, 0L}, 164);
            immediates[2372] = new Pattern(new long[]{0L, 0L, 0x4000200010000800L, 0x400000000000000L},
                    new long[]{0L, 0L, 0x4000200010000000L, 0x400000000000000L}, 180);
            immediates[2373] = new Pattern(new long[]{0L, 0L, 70369281052672L, 0x800040000000000L},
                    new long[]{0L, 0L, 70369281052672L, 4398046511104L}, 196);
            immediates[2374] = new Pattern(new long[]{0L, 0L, 1073750016L, 0x1000080004000000L},
                    new long[]{0L, 0L, 1073750016L, 0x1000000004000000L}, 212);
            immediates[2375] = new Pattern(new long[]{0L, 0L, 16384L, 0x2000100008000400L},
                    new long[]{0L, 0L, 16384L, 0x2000100000000400L}, 228);
            immediates[2376] = new Pattern(new long[]{0x2000100008000400L, 0x200000000000000L, 0L, 0L},
                    new long[]{0x2000100008000000L, 0x200000000000000L, 0L, 0L}, 53);
            immediates[2377] = new Pattern(new long[]{35184640526336L, 0x400020000000000L, 0L, 0L},
                    new long[]{35184640526336L, 2199023255552L, 0L, 0L}, 69);
            immediates[2378] = new Pattern(new long[]{536875008L, 0x800040002000000L, 0L, 0L},
                    new long[]{536875008L, 0x800000002000000L, 0L, 0L}, 85);
            immediates[2379] = new Pattern(new long[]{8192L, 0x1000080004000200L, 0L, 0L},
                    new long[]{8192L, 0x1000080000000200L, 0L, 0L}, 101);
            immediates[2380] = new Pattern(new long[]{0L, 0x2000100008000400L, 0x200000000000000L, 0L},
                    new long[]{0L, 0x2000100008000000L, 0x200000000000000L, 0L}, 117);
            immediates[2381] = new Pattern(new long[]{0L, 35184640526336L, 0x400020000000000L, 0L},
                    new long[]{0L, 35184640526336L, 2199023255552L, 0L}, 133);
            immediates[2382] = new Pattern(new long[]{0L, 536875008L, 0x800040002000000L, 0L},
                    new long[]{0L, 536875008L, 0x800000002000000L, 0L}, 149);
            immediates[2383] = new Pattern(new long[]{0L, 8192L, 0x1000080004000200L, 0L},
                    new long[]{0L, 8192L, 0x1000080000000200L, 0L}, 165);
            immediates[2384] = new Pattern(new long[]{0L, 0L, 0x2000100008000400L, 0x200000000000000L},
                    new long[]{0L, 0L, 0x2000100008000000L, 0x200000000000000L}, 181);
            immediates[2385] = new Pattern(new long[]{0L, 0L, 35184640526336L, 0x400020000000000L},
                    new long[]{0L, 0L, 35184640526336L, 2199023255552L}, 197);
            immediates[2386] = new Pattern(new long[]{0L, 0L, 536875008L, 0x800040002000000L},
                    new long[]{0L, 0L, 536875008L, 0x800000002000000L}, 213);
            immediates[2387] = new Pattern(new long[]{0L, 0L, 8192L, 0x1000080004000200L},
                    new long[]{0L, 0L, 8192L, 0x1000080000000200L}, 229);
            immediates[2388] = new Pattern(new long[]{0x1000080004000200L, 72057594037927936L, 0L, 0L},
                    new long[]{0x1000080004000000L, 72057594037927936L, 0L, 0L}, 54);
            immediates[2389] = new Pattern(new long[]{17592320263168L, 0x200010000000000L, 0L, 0L},
                    new long[]{17592320263168L, 1099511627776L, 0L, 0L}, 70);
            immediates[2390] = new Pattern(new long[]{268437504L, 0x400020001000000L, 0L, 0L},
                    new long[]{268437504L, 0x400000001000000L, 0L, 0L}, 86);
            immediates[2391] = new Pattern(new long[]{4096L, 0x800040002000100L, 0L, 0L},
                    new long[]{4096L, 0x800040000000100L, 0L, 0L}, 102);
            immediates[2392] = new Pattern(new long[]{0L, 0x1000080004000200L, 72057594037927936L, 0L},
                    new long[]{0L, 0x1000080004000000L, 72057594037927936L, 0L}, 118);
            immediates[2393] = new Pattern(new long[]{0L, 17592320263168L, 0x200010000000000L, 0L},
                    new long[]{0L, 17592320263168L, 1099511627776L, 0L}, 134);
            immediates[2394] = new Pattern(new long[]{0L, 268437504L, 0x400020001000000L, 0L},
                    new long[]{0L, 268437504L, 0x400000001000000L, 0L}, 150);
            immediates[2395] = new Pattern(new long[]{0L, 4096L, 0x800040002000100L, 0L},
                    new long[]{0L, 4096L, 0x800040000000100L, 0L}, 166);
            immediates[2396] = new Pattern(new long[]{0L, 0L, 0x1000080004000200L, 72057594037927936L},
                    new long[]{0L, 0L, 0x1000080004000000L, 72057594037927936L}, 182);
            immediates[2397] = new Pattern(new long[]{0L, 0L, 17592320263168L, 0x200010000000000L},
                    new long[]{0L, 0L, 17592320263168L, 1099511627776L}, 198);
            immediates[2398] = new Pattern(new long[]{0L, 0L, 268437504L, 0x400020001000000L},
                    new long[]{0L, 0L, 268437504L, 0x400000001000000L}, 214);
            immediates[2399] = new Pattern(new long[]{0L, 0L, 4096L, 0x800040002000100L},
                    new long[]{0L, 0L, 4096L, 0x800040000000100L}, 230);
            immediates[2400] = new Pattern(new long[]{0x800040002000100L, 0x80000000000000L, 0L, 0L},
                    new long[]{0x800040002000000L, 0x80000000000000L, 0L, 0L}, 55);
            immediates[2401] = new Pattern(new long[]{8796160131584L, 72058143793741824L, 0L, 0L},
                    new long[]{8796160131584L, 549755813888L, 0L, 0L}, 71);
            immediates[2402] = new Pattern(new long[]{134218752L, 0x200010000800000L, 0L, 0L},
                    new long[]{134218752L, 0x200000000800000L, 0L, 0L}, 87);
            immediates[2403] = new Pattern(new long[]{2048L, 0x400020001000080L, 0L, 0L},
                    new long[]{2048L, 0x400020000000080L, 0L, 0L}, 103);
            immediates[2404] = new Pattern(new long[]{0L, 0x800040002000100L, 0x80000000000000L, 0L},
                    new long[]{0L, 0x800040002000000L, 0x80000000000000L, 0L}, 119);
            immediates[2405] = new Pattern(new long[]{0L, 8796160131584L, 72058143793741824L, 0L},
                    new long[]{0L, 8796160131584L, 549755813888L, 0L}, 135);
            immediates[2406] = new Pattern(new long[]{0L, 134218752L, 0x200010000800000L, 0L},
                    new long[]{0L, 134218752L, 0x200000000800000L, 0L}, 151);
            immediates[2407] = new Pattern(new long[]{0L, 2048L, 0x400020001000080L, 0L},
                    new long[]{0L, 2048L, 0x400020000000080L, 0L}, 167);
            immediates[2408] = new Pattern(new long[]{0L, 0L, 0x800040002000100L, 0x80000000000000L},
                    new long[]{0L, 0L, 0x800040002000000L, 0x80000000000000L}, 183);
            immediates[2409] = new Pattern(new long[]{0L, 0L, 8796160131584L, 72058143793741824L},
                    new long[]{0L, 0L, 8796160131584L, 549755813888L}, 199);
            immediates[2410] = new Pattern(new long[]{0L, 0L, 134218752L, 0x200010000800000L},
                    new long[]{0L, 0L, 134218752L, 0x200000000800000L}, 215);
            immediates[2411] = new Pattern(new long[]{0L, 0L, 2048L, 0x400020001000080L},
                    new long[]{0L, 0L, 2048L, 0x400020000000080L}, 231);
            immediates[2412] = new Pattern(new long[]{0x400020001000080L, 0x40000000000000L, 0L, 0L},
                    new long[]{0x400020001000000L, 0x40000000000000L, 0L, 0L}, 56);
            immediates[2413] = new Pattern(new long[]{4398080065792L, 0x80004000000000L, 0L, 0L},
                    new long[]{4398080065792L, 274877906944L, 0L, 0L}, 72);
            immediates[2414] = new Pattern(new long[]{67109376L, 72058143797936128L, 0L, 0L},
                    new long[]{67109376L, 72057594042122240L, 0L, 0L}, 88);
            immediates[2415] = new Pattern(new long[]{1024L, 0x200010000800040L, 0L, 0L},
                    new long[]{1024L, 0x200010000000040L, 0L, 0L}, 104);
            immediates[2416] = new Pattern(new long[]{0L, 0x400020001000080L, 0x40000000000000L, 0L},
                    new long[]{0L, 0x400020001000000L, 0x40000000000000L, 0L}, 120);
            immediates[2417] = new Pattern(new long[]{0L, 4398080065792L, 0x80004000000000L, 0L},
                    new long[]{0L, 4398080065792L, 274877906944L, 0L}, 136);
            immediates[2418] = new Pattern(new long[]{0L, 67109376L, 72058143797936128L, 0L},
                    new long[]{0L, 67109376L, 72057594042122240L, 0L}, 152);
            immediates[2419] = new Pattern(new long[]{0L, 1024L, 0x200010000800040L, 0L},
                    new long[]{0L, 1024L, 0x200010000000040L, 0L}, 168);
            immediates[2420] = new Pattern(new long[]{0L, 0L, 0x400020001000080L, 0x40000000000000L},
                    new long[]{0L, 0L, 0x400020001000000L, 0x40000000000000L}, 184);
            immediates[2421] = new Pattern(new long[]{0L, 0L, 4398080065792L, 0x80004000000000L},
                    new long[]{0L, 0L, 4398080065792L, 274877906944L}, 200);
            immediates[2422] = new Pattern(new long[]{0L, 0L, 67109376L, 72058143797936128L},
                    new long[]{0L, 0L, 67109376L, 72057594042122240L}, 216);
            immediates[2423] = new Pattern(new long[]{0L, 0L, 1024L, 0x200010000800040L},
                    new long[]{0L, 0L, 1024L, 0x200010000000040L}, 232);
            immediates[2424] = new Pattern(new long[]{0x200010000800040L, 9007199254740992L, 0L, 0L},
                    new long[]{0x200010000800000L, 9007199254740992L, 0L, 0L}, 57);
            immediates[2425] = new Pattern(new long[]{2199040032896L, 0x40002000000000L, 0L, 0L},
                    new long[]{2199040032896L, 137438953472L, 0L, 0L}, 73);
            immediates[2426] = new Pattern(new long[]{33554688L, 0x80004000200000L, 0L, 0L},
                    new long[]{33554688L, 0x80000000200000L, 0L, 0L}, 89);
            immediates[2427] = new Pattern(new long[]{512L, 72058143797936160L, 0L, 0L},
                    new long[]{512L, 72058143793741856L, 0L, 0L}, 105);
            immediates[2428] = new Pattern(new long[]{0L, 0x200010000800040L, 9007199254740992L, 0L},
                    new long[]{0L, 0x200010000800000L, 9007199254740992L, 0L}, 121);
            immediates[2429] = new Pattern(new long[]{0L, 2199040032896L, 0x40002000000000L, 0L},
                    new long[]{0L, 2199040032896L, 137438953472L, 0L}, 137);
            immediates[2430] = new Pattern(new long[]{0L, 33554688L, 0x80004000200000L, 0L},
                    new long[]{0L, 33554688L, 0x80000000200000L, 0L}, 153);
            immediates[2431] = new Pattern(new long[]{0L, 512L, 72058143797936160L, 0L},
                    new long[]{0L, 512L, 72058143793741856L, 0L}, 169);
            immediates[2432] = new Pattern(new long[]{0L, 0L, 0x200010000800040L, 9007199254740992L},
                    new long[]{0L, 0L, 0x200010000800000L, 9007199254740992L}, 185);
            immediates[2433] = new Pattern(new long[]{0L, 0L, 2199040032896L, 0x40002000000000L},
                    new long[]{0L, 0L, 2199040032896L, 137438953472L}, 201);
            immediates[2434] = new Pattern(new long[]{0L, 0L, 33554688L, 0x80004000200000L},
                    new long[]{0L, 0L, 33554688L, 0x80000000200000L}, 217);
            immediates[2435] = new Pattern(new long[]{0L, 0L, 512L, 72058143797936160L},
                    new long[]{0L, 0L, 512L, 72058143793741856L}, 233);
            immediates[2436] = new Pattern(new long[]{72058143797936160L, 4503599627370496L, 0L, 0L},
                    new long[]{72058143797936128L, 4503599627370496L, 0L, 0L}, 58);
            immediates[2437] = new Pattern(new long[]{1099520016448L, 9007267974217728L, 0L, 0L},
                    new long[]{1099520016448L, 68719476736L, 0L, 0L}, 74);
            immediates[2438] = new Pattern(new long[]{16777344L, 0x40002000100000L, 0L, 0L},
                    new long[]{16777344L, 0x40000000100000L, 0L, 0L}, 90);
            immediates[2439] = new Pattern(new long[]{256L, 0x80004000200010L, 0L, 0L},
                    new long[]{256L, 0x80004000000010L, 0L, 0L}, 106);
            immediates[2440] = new Pattern(new long[]{0L, 72058143797936160L, 4503599627370496L, 0L},
                    new long[]{0L, 72058143797936128L, 4503599627370496L, 0L}, 122);
            immediates[2441] = new Pattern(new long[]{0L, 1099520016448L, 9007267974217728L, 0L},
                    new long[]{0L, 1099520016448L, 68719476736L, 0L}, 138);
            immediates[2442] = new Pattern(new long[]{0L, 16777344L, 0x40002000100000L, 0L},
                    new long[]{0L, 16777344L, 0x40000000100000L, 0L}, 154);
            immediates[2443] = new Pattern(new long[]{0L, 256L, 0x80004000200010L, 0L},
                    new long[]{0L, 256L, 0x80004000000010L, 0L}, 170);
            immediates[2444] = new Pattern(new long[]{0L, 0L, 72058143797936160L, 4503599627370496L},
                    new long[]{0L, 0L, 72058143797936128L, 4503599627370496L}, 186);
            immediates[2445] = new Pattern(new long[]{0L, 0L, 1099520016448L, 9007267974217728L},
                    new long[]{0L, 0L, 1099520016448L, 68719476736L}, 202);
            immediates[2446] = new Pattern(new long[]{0L, 0L, 16777344L, 0x40002000100000L},
                    new long[]{0L, 0L, 16777344L, 0x40000000100000L}, 218);
            immediates[2447] = new Pattern(new long[]{0L, 0L, 256L, 0x80004000200010L},
                    new long[]{0L, 0L, 256L, 0x80004000000010L}, 234);
            immediates[2448] = new Pattern(new long[]{0x80004000200010L, 0x8000000000000L, 0L, 0L},
                    new long[]{0x80004000200000L, 0x8000000000000L, 0L, 0L}, 59);
            immediates[2449] = new Pattern(new long[]{549760008224L, 4503633987108864L, 0L, 0L},
                    new long[]{549760008224L, 34359738368L, 0L, 0L}, 75);
            immediates[2450] = new Pattern(new long[]{8388672L, 9007267974742016L, 0L, 0L},
                    new long[]{8388672L, 9007199255265280L, 0L, 0L}, 91);
            immediates[2451] = new Pattern(new long[]{128L, 0x40002000100008L, 0L, 0L},
                    new long[]{128L, 0x40002000000008L, 0L, 0L}, 107);
            immediates[2452] = new Pattern(new long[]{0L, 0x80004000200010L, 0x8000000000000L, 0L},
                    new long[]{0L, 0x80004000200000L, 0x8000000000000L, 0L}, 123);
            immediates[2453] = new Pattern(new long[]{0L, 549760008224L, 4503633987108864L, 0L},
                    new long[]{0L, 549760008224L, 34359738368L, 0L}, 139);
            immediates[2454] = new Pattern(new long[]{0L, 8388672L, 9007267974742016L, 0L},
                    new long[]{0L, 8388672L, 9007199255265280L, 0L}, 155);
            immediates[2455] = new Pattern(new long[]{0L, 128L, 0x40002000100008L, 0L},
                    new long[]{0L, 128L, 0x40002000000008L, 0L}, 171);
            immediates[2456] = new Pattern(new long[]{0L, 0L, 0x80004000200010L, 0x8000000000000L},
                    new long[]{0L, 0L, 0x80004000200000L, 0x8000000000000L}, 187);
            immediates[2457] = new Pattern(new long[]{0L, 0L, 549760008224L, 4503633987108864L},
                    new long[]{0L, 0L, 549760008224L, 34359738368L}, 203);
            immediates[2458] = new Pattern(new long[]{0L, 0L, 8388672L, 9007267974742016L},
                    new long[]{0L, 0L, 8388672L, 9007199255265280L}, 219);
            immediates[2459] = new Pattern(new long[]{0L, 0L, 128L, 0x40002000100008L},
                    new long[]{0L, 0L, 128L, 0x40002000000008L}, 235);
            immediates[2460] = new Pattern(new long[]{0x40002000100008L, 0x4000000000000L, 0L, 0L},
                    new long[]{0x40002000100000L, 0x4000000000000L, 0L, 0L}, 60);
            immediates[2461] = new Pattern(new long[]{274880004112L, 0x8000400000000L, 0L, 0L},
                    new long[]{274880004112L, 17179869184L, 0L, 0L}, 76);
            immediates[2462] = new Pattern(new long[]{4194336L, 4503633987371008L, 0L, 0L},
                    new long[]{4194336L, 4503599627632640L, 0L, 0L}, 92);
            immediates[2463] =
                    new Pattern(new long[]{64L, 9007267974742020L, 0L, 0L}, new long[]{64L, 9007267974217732L, 0L, 0L},
                            108);
            immediates[2464] = new Pattern(new long[]{0L, 0x40002000100008L, 0x4000000000000L, 0L},
                    new long[]{0L, 0x40002000100000L, 0x4000000000000L, 0L}, 124);
            immediates[2465] = new Pattern(new long[]{0L, 274880004112L, 0x8000400000000L, 0L},
                    new long[]{0L, 274880004112L, 17179869184L, 0L}, 140);
            immediates[2466] = new Pattern(new long[]{0L, 4194336L, 4503633987371008L, 0L},
                    new long[]{0L, 4194336L, 4503599627632640L, 0L}, 156);
            immediates[2467] =
                    new Pattern(new long[]{0L, 64L, 9007267974742020L, 0L}, new long[]{0L, 64L, 9007267974217732L, 0L},
                            172);
            immediates[2468] = new Pattern(new long[]{0L, 0L, 0x40002000100008L, 0x4000000000000L},
                    new long[]{0L, 0L, 0x40002000100000L, 0x4000000000000L}, 188);
            immediates[2469] = new Pattern(new long[]{0L, 0L, 274880004112L, 0x8000400000000L},
                    new long[]{0L, 0L, 274880004112L, 17179869184L}, 204);
            immediates[2470] = new Pattern(new long[]{0L, 0L, 4194336L, 4503633987371008L},
                    new long[]{0L, 0L, 4194336L, 4503599627632640L}, 220);
            immediates[2471] =
                    new Pattern(new long[]{0L, 0L, 64L, 9007267974742020L}, new long[]{0L, 0L, 64L, 9007267974217732L},
                            236);
            immediates[2472] = new Pattern(new long[]{9007267974742020L, 562949953421312L, 0L, 0L},
                    new long[]{9007267974742016L, 562949953421312L, 0L, 0L}, 61);
            immediates[2473] = new Pattern(new long[]{137440002056L, 0x4000200000000L, 0L, 0L},
                    new long[]{137440002056L, 8589934592L, 0L, 0L}, 77);
            immediates[2474] = new Pattern(new long[]{2097168L, 0x8000400020000L, 0L, 0L},
                    new long[]{2097168L, 0x8000000020000L, 0L, 0L}, 93);
            immediates[2475] =
                    new Pattern(new long[]{32L, 4503633987371010L, 0L, 0L}, new long[]{32L, 4503633987108866L, 0L, 0L},
                            109);
            immediates[2476] = new Pattern(new long[]{0L, 9007267974742020L, 562949953421312L, 0L},
                    new long[]{0L, 9007267974742016L, 562949953421312L, 0L}, 125);
            immediates[2477] = new Pattern(new long[]{0L, 137440002056L, 0x4000200000000L, 0L},
                    new long[]{0L, 137440002056L, 8589934592L, 0L}, 141);
            immediates[2478] = new Pattern(new long[]{0L, 2097168L, 0x8000400020000L, 0L},
                    new long[]{0L, 2097168L, 0x8000000020000L, 0L}, 157);
            immediates[2479] =
                    new Pattern(new long[]{0L, 32L, 4503633987371010L, 0L}, new long[]{0L, 32L, 4503633987108866L, 0L},
                            173);
            immediates[2480] = new Pattern(new long[]{0L, 0L, 9007267974742020L, 562949953421312L},
                    new long[]{0L, 0L, 9007267974742016L, 562949953421312L}, 189);
            immediates[2481] = new Pattern(new long[]{0L, 0L, 137440002056L, 0x4000200000000L},
                    new long[]{0L, 0L, 137440002056L, 8589934592L}, 205);
            immediates[2482] = new Pattern(new long[]{0L, 0L, 2097168L, 0x8000400020000L},
                    new long[]{0L, 0L, 2097168L, 0x8000000020000L}, 221);
            immediates[2483] =
                    new Pattern(new long[]{0L, 0L, 32L, 4503633987371010L}, new long[]{0L, 0L, 32L, 4503633987108866L},
                            237);
            immediates[2484] = new Pattern(new long[]{4503633987371010L, 281474976710656L, 0L, 0L},
                    new long[]{4503633987371008L, 281474976710656L, 0L, 0L}, 62);
            immediates[2485] = new Pattern(new long[]{68720001028L, 562954248388608L, 0L, 0L},
                    new long[]{68720001028L, 4294967296L, 0L, 0L}, 78);
            immediates[2486] = new Pattern(new long[]{1048584L, 0x4000200010000L, 0L, 0L},
                    new long[]{1048584L, 0x4000000010000L, 0L, 0L}, 94);
            immediates[2487] =
                    new Pattern(new long[]{16L, 0x8000400020001L, 0L, 0L}, new long[]{16L, 0x8000400000001L, 0L, 0L},
                            110);
            immediates[2488] = new Pattern(new long[]{0L, 4503633987371010L, 281474976710656L, 0L},
                    new long[]{0L, 4503633987371008L, 281474976710656L, 0L}, 126);
            immediates[2489] = new Pattern(new long[]{0L, 68720001028L, 562954248388608L, 0L},
                    new long[]{0L, 68720001028L, 4294967296L, 0L}, 142);
            immediates[2490] = new Pattern(new long[]{0L, 1048584L, 0x4000200010000L, 0L},
                    new long[]{0L, 1048584L, 0x4000000010000L, 0L}, 158);
            immediates[2491] =
                    new Pattern(new long[]{0L, 16L, 0x8000400020001L, 0L}, new long[]{0L, 16L, 0x8000400000001L, 0L},
                            174);
            immediates[2492] = new Pattern(new long[]{0L, 0L, 4503633987371010L, 281474976710656L},
                    new long[]{0L, 0L, 4503633987371008L, 281474976710656L}, 190);
            immediates[2493] = new Pattern(new long[]{0L, 0L, 68720001028L, 562954248388608L},
                    new long[]{0L, 0L, 68720001028L, 4294967296L}, 206);
            immediates[2494] = new Pattern(new long[]{0L, 0L, 1048584L, 0x4000200010000L},
                    new long[]{0L, 0L, 1048584L, 0x4000000010000L}, 222);
            immediates[2495] =
                    new Pattern(new long[]{0L, 0L, 16L, 0x8000400020001L}, new long[]{0L, 0L, 16L, 0x8000400000001L},
                            238);
            immediates[2496] = new Pattern(new long[]{0x8000400020001000L, 0x800000000000000L, 0L, 0L},
                    new long[]{0x8000400020001000L, 0L, 0L, 0L}, 68);
            immediates[2497] = new Pattern(new long[]{0x800040002000L, 0x1000080000000000L, 0L, 0L},
                    new long[]{0x800040002000L, 0x1000000000000000L, 0L, 0L}, 84);
            immediates[2498] = new Pattern(new long[]{2147500032L, 0x2000100008000000L, 0L, 0L},
                    new long[]{2147500032L, 0x2000100000000000L, 0L, 0L}, 100);
            immediates[2499] = new Pattern(new long[]{32768L, 0x4000200010000800L, 0L, 0L},
                    new long[]{32768L, 0x4000200010000000L, 0L, 0L}, 116);
        }

        private static void initImmediates5() {
            immediates[2500] = new Pattern(new long[]{0L, 0x8000400020001000L, 0x800000000000000L, 0L},
                    new long[]{0L, 0x8000400020001000L, 0L, 0L}, 132);
            immediates[2501] = new Pattern(new long[]{0L, 0x800040002000L, 0x1000080000000000L, 0L},
                    new long[]{0L, 0x800040002000L, 0x1000000000000000L, 0L}, 148);
            immediates[2502] = new Pattern(new long[]{0L, 2147500032L, 0x2000100008000000L, 0L},
                    new long[]{0L, 2147500032L, 0x2000100000000000L, 0L}, 164);
            immediates[2503] = new Pattern(new long[]{0L, 32768L, 0x4000200010000800L, 0L},
                    new long[]{0L, 32768L, 0x4000200010000000L, 0L}, 180);
            immediates[2504] = new Pattern(new long[]{0L, 0L, 0x8000400020001000L, 0x800000000000000L},
                    new long[]{0L, 0L, 0x8000400020001000L, 0L}, 196);
            immediates[2505] = new Pattern(new long[]{0L, 0L, 0x800040002000L, 0x1000080000000000L},
                    new long[]{0L, 0L, 0x800040002000L, 0x1000000000000000L}, 212);
            immediates[2506] = new Pattern(new long[]{0L, 0L, 2147500032L, 0x2000100008000000L},
                    new long[]{0L, 0L, 2147500032L, 0x2000100000000000L}, 228);
            immediates[2507] = new Pattern(new long[]{0L, 0L, 32768L, 0x4000200010000800L},
                    new long[]{0L, 0L, 32768L, 0x4000200010000000L}, 244);
            immediates[2508] = new Pattern(new long[]{0x4000200010000800L, 0x400000000000000L, 0L, 0L},
                    new long[]{0x4000200010000800L, 0L, 0L, 0L}, 69);
            immediates[2509] = new Pattern(new long[]{70369281052672L, 0x800040000000000L, 0L, 0L},
                    new long[]{70369281052672L, 0x800000000000000L, 0L, 0L}, 85);
            immediates[2510] = new Pattern(new long[]{1073750016L, 0x1000080004000000L, 0L, 0L},
                    new long[]{1073750016L, 0x1000080000000000L, 0L, 0L}, 101);
            immediates[2511] = new Pattern(new long[]{16384L, 0x2000100008000400L, 0L, 0L},
                    new long[]{16384L, 0x2000100008000000L, 0L, 0L}, 117);
            immediates[2512] = new Pattern(new long[]{0L, 0x4000200010000800L, 0x400000000000000L, 0L},
                    new long[]{0L, 0x4000200010000800L, 0L, 0L}, 133);
            immediates[2513] = new Pattern(new long[]{0L, 70369281052672L, 0x800040000000000L, 0L},
                    new long[]{0L, 70369281052672L, 0x800000000000000L, 0L}, 149);
            immediates[2514] = new Pattern(new long[]{0L, 1073750016L, 0x1000080004000000L, 0L},
                    new long[]{0L, 1073750016L, 0x1000080000000000L, 0L}, 165);
            immediates[2515] = new Pattern(new long[]{0L, 16384L, 0x2000100008000400L, 0L},
                    new long[]{0L, 16384L, 0x2000100008000000L, 0L}, 181);
            immediates[2516] = new Pattern(new long[]{0L, 0L, 0x4000200010000800L, 0x400000000000000L},
                    new long[]{0L, 0L, 0x4000200010000800L, 0L}, 197);
            immediates[2517] = new Pattern(new long[]{0L, 0L, 70369281052672L, 0x800040000000000L},
                    new long[]{0L, 0L, 70369281052672L, 0x800000000000000L}, 213);
            immediates[2518] = new Pattern(new long[]{0L, 0L, 1073750016L, 0x1000080004000000L},
                    new long[]{0L, 0L, 1073750016L, 0x1000080000000000L}, 229);
            immediates[2519] = new Pattern(new long[]{0L, 0L, 16384L, 0x2000100008000400L},
                    new long[]{0L, 0L, 16384L, 0x2000100008000000L}, 245);
            immediates[2520] = new Pattern(new long[]{0x2000100008000400L, 0x200000000000000L, 0L, 0L},
                    new long[]{0x2000100008000400L, 0L, 0L, 0L}, 70);
            immediates[2521] = new Pattern(new long[]{35184640526336L, 0x400020000000000L, 0L, 0L},
                    new long[]{35184640526336L, 0x400000000000000L, 0L, 0L}, 86);
            immediates[2522] = new Pattern(new long[]{536875008L, 0x800040002000000L, 0L, 0L},
                    new long[]{536875008L, 0x800040000000000L, 0L, 0L}, 102);
            immediates[2523] = new Pattern(new long[]{8192L, 0x1000080004000200L, 0L, 0L},
                    new long[]{8192L, 0x1000080004000000L, 0L, 0L}, 118);
            immediates[2524] = new Pattern(new long[]{0L, 0x2000100008000400L, 0x200000000000000L, 0L},
                    new long[]{0L, 0x2000100008000400L, 0L, 0L}, 134);
            immediates[2525] = new Pattern(new long[]{0L, 35184640526336L, 0x400020000000000L, 0L},
                    new long[]{0L, 35184640526336L, 0x400000000000000L, 0L}, 150);
            immediates[2526] = new Pattern(new long[]{0L, 536875008L, 0x800040002000000L, 0L},
                    new long[]{0L, 536875008L, 0x800040000000000L, 0L}, 166);
            immediates[2527] = new Pattern(new long[]{0L, 8192L, 0x1000080004000200L, 0L},
                    new long[]{0L, 8192L, 0x1000080004000000L, 0L}, 182);
            immediates[2528] = new Pattern(new long[]{0L, 0L, 0x2000100008000400L, 0x200000000000000L},
                    new long[]{0L, 0L, 0x2000100008000400L, 0L}, 198);
            immediates[2529] = new Pattern(new long[]{0L, 0L, 35184640526336L, 0x400020000000000L},
                    new long[]{0L, 0L, 35184640526336L, 0x400000000000000L}, 214);
            immediates[2530] = new Pattern(new long[]{0L, 0L, 536875008L, 0x800040002000000L},
                    new long[]{0L, 0L, 536875008L, 0x800040000000000L}, 230);
            immediates[2531] = new Pattern(new long[]{0L, 0L, 8192L, 0x1000080004000200L},
                    new long[]{0L, 0L, 8192L, 0x1000080004000000L}, 246);
            immediates[2532] = new Pattern(new long[]{0x1000080004000200L, 72057594037927936L, 0L, 0L},
                    new long[]{0x1000080004000200L, 0L, 0L, 0L}, 71);
            immediates[2533] = new Pattern(new long[]{17592320263168L, 0x200010000000000L, 0L, 0L},
                    new long[]{17592320263168L, 0x200000000000000L, 0L, 0L}, 87);
            immediates[2534] = new Pattern(new long[]{268437504L, 0x400020001000000L, 0L, 0L},
                    new long[]{268437504L, 0x400020000000000L, 0L, 0L}, 103);
            immediates[2535] = new Pattern(new long[]{4096L, 0x800040002000100L, 0L, 0L},
                    new long[]{4096L, 0x800040002000000L, 0L, 0L}, 119);
            immediates[2536] = new Pattern(new long[]{0L, 0x1000080004000200L, 72057594037927936L, 0L},
                    new long[]{0L, 0x1000080004000200L, 0L, 0L}, 135);
            immediates[2537] = new Pattern(new long[]{0L, 17592320263168L, 0x200010000000000L, 0L},
                    new long[]{0L, 17592320263168L, 0x200000000000000L, 0L}, 151);
            immediates[2538] = new Pattern(new long[]{0L, 268437504L, 0x400020001000000L, 0L},
                    new long[]{0L, 268437504L, 0x400020000000000L, 0L}, 167);
            immediates[2539] = new Pattern(new long[]{0L, 4096L, 0x800040002000100L, 0L},
                    new long[]{0L, 4096L, 0x800040002000000L, 0L}, 183);
            immediates[2540] = new Pattern(new long[]{0L, 0L, 0x1000080004000200L, 72057594037927936L},
                    new long[]{0L, 0L, 0x1000080004000200L, 0L}, 199);
            immediates[2541] = new Pattern(new long[]{0L, 0L, 17592320263168L, 0x200010000000000L},
                    new long[]{0L, 0L, 17592320263168L, 0x200000000000000L}, 215);
            immediates[2542] = new Pattern(new long[]{0L, 0L, 268437504L, 0x400020001000000L},
                    new long[]{0L, 0L, 268437504L, 0x400020000000000L}, 231);
            immediates[2543] = new Pattern(new long[]{0L, 0L, 4096L, 0x800040002000100L},
                    new long[]{0L, 0L, 4096L, 0x800040002000000L}, 247);
            immediates[2544] = new Pattern(new long[]{0x800040002000100L, 0x80000000000000L, 0L, 0L},
                    new long[]{0x800040002000100L, 0L, 0L, 0L}, 72);
            immediates[2545] = new Pattern(new long[]{8796160131584L, 72058143793741824L, 0L, 0L},
                    new long[]{8796160131584L, 72057594037927936L, 0L, 0L}, 88);
            immediates[2546] = new Pattern(new long[]{134218752L, 0x200010000800000L, 0L, 0L},
                    new long[]{134218752L, 0x200010000000000L, 0L, 0L}, 104);
            immediates[2547] = new Pattern(new long[]{2048L, 0x400020001000080L, 0L, 0L},
                    new long[]{2048L, 0x400020001000000L, 0L, 0L}, 120);
            immediates[2548] = new Pattern(new long[]{0L, 0x800040002000100L, 0x80000000000000L, 0L},
                    new long[]{0L, 0x800040002000100L, 0L, 0L}, 136);
            immediates[2549] = new Pattern(new long[]{0L, 8796160131584L, 72058143793741824L, 0L},
                    new long[]{0L, 8796160131584L, 72057594037927936L, 0L}, 152);
            immediates[2550] = new Pattern(new long[]{0L, 134218752L, 0x200010000800000L, 0L},
                    new long[]{0L, 134218752L, 0x200010000000000L, 0L}, 168);
            immediates[2551] = new Pattern(new long[]{0L, 2048L, 0x400020001000080L, 0L},
                    new long[]{0L, 2048L, 0x400020001000000L, 0L}, 184);
            immediates[2552] = new Pattern(new long[]{0L, 0L, 0x800040002000100L, 0x80000000000000L},
                    new long[]{0L, 0L, 0x800040002000100L, 0L}, 200);
            immediates[2553] = new Pattern(new long[]{0L, 0L, 8796160131584L, 72058143793741824L},
                    new long[]{0L, 0L, 8796160131584L, 72057594037927936L}, 216);
            immediates[2554] = new Pattern(new long[]{0L, 0L, 134218752L, 0x200010000800000L},
                    new long[]{0L, 0L, 134218752L, 0x200010000000000L}, 232);
            immediates[2555] = new Pattern(new long[]{0L, 0L, 2048L, 0x400020001000080L},
                    new long[]{0L, 0L, 2048L, 0x400020001000000L}, 248);
            immediates[2556] = new Pattern(new long[]{0x400020001000080L, 0x40000000000000L, 0L, 0L},
                    new long[]{0x400020001000080L, 0L, 0L, 0L}, 73);
            immediates[2557] = new Pattern(new long[]{4398080065792L, 0x80004000000000L, 0L, 0L},
                    new long[]{4398080065792L, 0x80000000000000L, 0L, 0L}, 89);
            immediates[2558] = new Pattern(new long[]{67109376L, 72058143797936128L, 0L, 0L},
                    new long[]{67109376L, 72058143793741824L, 0L, 0L}, 105);
            immediates[2559] = new Pattern(new long[]{1024L, 0x200010000800040L, 0L, 0L},
                    new long[]{1024L, 0x200010000800000L, 0L, 0L}, 121);
            immediates[2560] = new Pattern(new long[]{0L, 0x400020001000080L, 0x40000000000000L, 0L},
                    new long[]{0L, 0x400020001000080L, 0L, 0L}, 137);
            immediates[2561] = new Pattern(new long[]{0L, 4398080065792L, 0x80004000000000L, 0L},
                    new long[]{0L, 4398080065792L, 0x80000000000000L, 0L}, 153);
            immediates[2562] = new Pattern(new long[]{0L, 67109376L, 72058143797936128L, 0L},
                    new long[]{0L, 67109376L, 72058143793741824L, 0L}, 169);
            immediates[2563] = new Pattern(new long[]{0L, 1024L, 0x200010000800040L, 0L},
                    new long[]{0L, 1024L, 0x200010000800000L, 0L}, 185);
            immediates[2564] = new Pattern(new long[]{0L, 0L, 0x400020001000080L, 0x40000000000000L},
                    new long[]{0L, 0L, 0x400020001000080L, 0L}, 201);
            immediates[2565] = new Pattern(new long[]{0L, 0L, 4398080065792L, 0x80004000000000L},
                    new long[]{0L, 0L, 4398080065792L, 0x80000000000000L}, 217);
            immediates[2566] = new Pattern(new long[]{0L, 0L, 67109376L, 72058143797936128L},
                    new long[]{0L, 0L, 67109376L, 72058143793741824L}, 233);
            immediates[2567] = new Pattern(new long[]{0L, 0L, 1024L, 0x200010000800040L},
                    new long[]{0L, 0L, 1024L, 0x200010000800000L}, 249);
            immediates[2568] = new Pattern(new long[]{0x200010000800040L, 9007199254740992L, 0L, 0L},
                    new long[]{0x200010000800040L, 0L, 0L, 0L}, 74);
            immediates[2569] = new Pattern(new long[]{2199040032896L, 0x40002000000000L, 0L, 0L},
                    new long[]{2199040032896L, 0x40000000000000L, 0L, 0L}, 90);
            immediates[2570] = new Pattern(new long[]{33554688L, 0x80004000200000L, 0L, 0L},
                    new long[]{33554688L, 0x80004000000000L, 0L, 0L}, 106);
            immediates[2571] = new Pattern(new long[]{512L, 72058143797936160L, 0L, 0L},
                    new long[]{512L, 72058143797936128L, 0L, 0L}, 122);
            immediates[2572] = new Pattern(new long[]{0L, 0x200010000800040L, 9007199254740992L, 0L},
                    new long[]{0L, 0x200010000800040L, 0L, 0L}, 138);
            immediates[2573] = new Pattern(new long[]{0L, 2199040032896L, 0x40002000000000L, 0L},
                    new long[]{0L, 2199040032896L, 0x40000000000000L, 0L}, 154);
            immediates[2574] = new Pattern(new long[]{0L, 33554688L, 0x80004000200000L, 0L},
                    new long[]{0L, 33554688L, 0x80004000000000L, 0L}, 170);
            immediates[2575] = new Pattern(new long[]{0L, 512L, 72058143797936160L, 0L},
                    new long[]{0L, 512L, 72058143797936128L, 0L}, 186);
            immediates[2576] = new Pattern(new long[]{0L, 0L, 0x200010000800040L, 9007199254740992L},
                    new long[]{0L, 0L, 0x200010000800040L, 0L}, 202);
            immediates[2577] = new Pattern(new long[]{0L, 0L, 2199040032896L, 0x40002000000000L},
                    new long[]{0L, 0L, 2199040032896L, 0x40000000000000L}, 218);
            immediates[2578] = new Pattern(new long[]{0L, 0L, 33554688L, 0x80004000200000L},
                    new long[]{0L, 0L, 33554688L, 0x80004000000000L}, 234);
            immediates[2579] = new Pattern(new long[]{0L, 0L, 512L, 72058143797936160L},
                    new long[]{0L, 0L, 512L, 72058143797936128L}, 250);
            immediates[2580] = new Pattern(new long[]{72058143797936160L, 4503599627370496L, 0L, 0L},
                    new long[]{72058143797936160L, 0L, 0L, 0L}, 75);
            immediates[2581] = new Pattern(new long[]{1099520016448L, 9007267974217728L, 0L, 0L},
                    new long[]{1099520016448L, 9007199254740992L, 0L, 0L}, 91);
            immediates[2582] = new Pattern(new long[]{16777344L, 0x40002000100000L, 0L, 0L},
                    new long[]{16777344L, 0x40002000000000L, 0L, 0L}, 107);
            immediates[2583] = new Pattern(new long[]{256L, 0x80004000200010L, 0L, 0L},
                    new long[]{256L, 0x80004000200000L, 0L, 0L}, 123);
            immediates[2584] = new Pattern(new long[]{0L, 72058143797936160L, 4503599627370496L, 0L},
                    new long[]{0L, 72058143797936160L, 0L, 0L}, 139);
            immediates[2585] = new Pattern(new long[]{0L, 1099520016448L, 9007267974217728L, 0L},
                    new long[]{0L, 1099520016448L, 9007199254740992L, 0L}, 155);
            immediates[2586] = new Pattern(new long[]{0L, 16777344L, 0x40002000100000L, 0L},
                    new long[]{0L, 16777344L, 0x40002000000000L, 0L}, 171);
            immediates[2587] = new Pattern(new long[]{0L, 256L, 0x80004000200010L, 0L},
                    new long[]{0L, 256L, 0x80004000200000L, 0L}, 187);
            immediates[2588] = new Pattern(new long[]{0L, 0L, 72058143797936160L, 4503599627370496L},
                    new long[]{0L, 0L, 72058143797936160L, 0L}, 203);
            immediates[2589] = new Pattern(new long[]{0L, 0L, 1099520016448L, 9007267974217728L},
                    new long[]{0L, 0L, 1099520016448L, 9007199254740992L}, 219);
            immediates[2590] = new Pattern(new long[]{0L, 0L, 16777344L, 0x40002000100000L},
                    new long[]{0L, 0L, 16777344L, 0x40002000000000L}, 235);
            immediates[2591] = new Pattern(new long[]{0L, 0L, 256L, 0x80004000200010L},
                    new long[]{0L, 0L, 256L, 0x80004000200000L}, 251);
            immediates[2592] = new Pattern(new long[]{0x80004000200010L, 0x8000000000000L, 0L, 0L},
                    new long[]{0x80004000200010L, 0L, 0L, 0L}, 76);
            immediates[2593] = new Pattern(new long[]{549760008224L, 4503633987108864L, 0L, 0L},
                    new long[]{549760008224L, 4503599627370496L, 0L, 0L}, 92);
            immediates[2594] = new Pattern(new long[]{8388672L, 9007267974742016L, 0L, 0L},
                    new long[]{8388672L, 9007267974217728L, 0L, 0L}, 108);
            immediates[2595] = new Pattern(new long[]{128L, 0x40002000100008L, 0L, 0L},
                    new long[]{128L, 0x40002000100000L, 0L, 0L}, 124);
            immediates[2596] = new Pattern(new long[]{0L, 0x80004000200010L, 0x8000000000000L, 0L},
                    new long[]{0L, 0x80004000200010L, 0L, 0L}, 140);
            immediates[2597] = new Pattern(new long[]{0L, 549760008224L, 4503633987108864L, 0L},
                    new long[]{0L, 549760008224L, 4503599627370496L, 0L}, 156);
            immediates[2598] = new Pattern(new long[]{0L, 8388672L, 9007267974742016L, 0L},
                    new long[]{0L, 8388672L, 9007267974217728L, 0L}, 172);
            immediates[2599] = new Pattern(new long[]{0L, 128L, 0x40002000100008L, 0L},
                    new long[]{0L, 128L, 0x40002000100000L, 0L}, 188);
            immediates[2600] = new Pattern(new long[]{0L, 0L, 0x80004000200010L, 0x8000000000000L},
                    new long[]{0L, 0L, 0x80004000200010L, 0L}, 204);
            immediates[2601] = new Pattern(new long[]{0L, 0L, 549760008224L, 4503633987108864L},
                    new long[]{0L, 0L, 549760008224L, 4503599627370496L}, 220);
            immediates[2602] = new Pattern(new long[]{0L, 0L, 8388672L, 9007267974742016L},
                    new long[]{0L, 0L, 8388672L, 9007267974217728L}, 236);
            immediates[2603] = new Pattern(new long[]{0L, 0L, 128L, 0x40002000100008L},
                    new long[]{0L, 0L, 128L, 0x40002000100000L}, 252);
            immediates[2604] = new Pattern(new long[]{0x40002000100008L, 0x4000000000000L, 0L, 0L},
                    new long[]{0x40002000100008L, 0L, 0L, 0L}, 77);
            immediates[2605] = new Pattern(new long[]{274880004112L, 0x8000400000000L, 0L, 0L},
                    new long[]{274880004112L, 0x8000000000000L, 0L, 0L}, 93);
            immediates[2606] = new Pattern(new long[]{4194336L, 4503633987371008L, 0L, 0L},
                    new long[]{4194336L, 4503633987108864L, 0L, 0L}, 109);
            immediates[2607] =
                    new Pattern(new long[]{64L, 9007267974742020L, 0L, 0L}, new long[]{64L, 9007267974742016L, 0L, 0L},
                            125);
            immediates[2608] = new Pattern(new long[]{0L, 0x40002000100008L, 0x4000000000000L, 0L},
                    new long[]{0L, 0x40002000100008L, 0L, 0L}, 141);
            immediates[2609] = new Pattern(new long[]{0L, 274880004112L, 0x8000400000000L, 0L},
                    new long[]{0L, 274880004112L, 0x8000000000000L, 0L}, 157);
            immediates[2610] = new Pattern(new long[]{0L, 4194336L, 4503633987371008L, 0L},
                    new long[]{0L, 4194336L, 4503633987108864L, 0L}, 173);
            immediates[2611] =
                    new Pattern(new long[]{0L, 64L, 9007267974742020L, 0L}, new long[]{0L, 64L, 9007267974742016L, 0L},
                            189);
            immediates[2612] = new Pattern(new long[]{0L, 0L, 0x40002000100008L, 0x4000000000000L},
                    new long[]{0L, 0L, 0x40002000100008L, 0L}, 205);
            immediates[2613] = new Pattern(new long[]{0L, 0L, 274880004112L, 0x8000400000000L},
                    new long[]{0L, 0L, 274880004112L, 0x8000000000000L}, 221);
            immediates[2614] = new Pattern(new long[]{0L, 0L, 4194336L, 4503633987371008L},
                    new long[]{0L, 0L, 4194336L, 4503633987108864L}, 237);
            immediates[2615] =
                    new Pattern(new long[]{0L, 0L, 64L, 9007267974742020L}, new long[]{0L, 0L, 64L, 9007267974742016L},
                            253);
            immediates[2616] = new Pattern(new long[]{9007267974742020L, 562949953421312L, 0L, 0L},
                    new long[]{9007267974742020L, 0L, 0L, 0L}, 78);
            immediates[2617] = new Pattern(new long[]{137440002056L, 0x4000200000000L, 0L, 0L},
                    new long[]{137440002056L, 0x4000000000000L, 0L, 0L}, 94);
            immediates[2618] = new Pattern(new long[]{2097168L, 0x8000400020000L, 0L, 0L},
                    new long[]{2097168L, 0x8000400000000L, 0L, 0L}, 110);
            immediates[2619] =
                    new Pattern(new long[]{32L, 4503633987371010L, 0L, 0L}, new long[]{32L, 4503633987371008L, 0L, 0L},
                            126);
            immediates[2620] = new Pattern(new long[]{0L, 9007267974742020L, 562949953421312L, 0L},
                    new long[]{0L, 9007267974742020L, 0L, 0L}, 142);
            immediates[2621] = new Pattern(new long[]{0L, 137440002056L, 0x4000200000000L, 0L},
                    new long[]{0L, 137440002056L, 0x4000000000000L, 0L}, 158);
            immediates[2622] = new Pattern(new long[]{0L, 2097168L, 0x8000400020000L, 0L},
                    new long[]{0L, 2097168L, 0x8000400000000L, 0L}, 174);
            immediates[2623] =
                    new Pattern(new long[]{0L, 32L, 4503633987371010L, 0L}, new long[]{0L, 32L, 4503633987371008L, 0L},
                            190);
            immediates[2624] = new Pattern(new long[]{0L, 0L, 9007267974742020L, 562949953421312L},
                    new long[]{0L, 0L, 9007267974742020L, 0L}, 206);
            immediates[2625] = new Pattern(new long[]{0L, 0L, 137440002056L, 0x4000200000000L},
                    new long[]{0L, 0L, 137440002056L, 0x4000000000000L}, 222);
            immediates[2626] = new Pattern(new long[]{0L, 0L, 2097168L, 0x8000400020000L},
                    new long[]{0L, 0L, 2097168L, 0x8000400000000L}, 238);
            immediates[2627] =
                    new Pattern(new long[]{0L, 0L, 32L, 4503633987371010L}, new long[]{0L, 0L, 32L, 4503633987371008L},
                            254);
            immediates[2628] = new Pattern(new long[]{4503633987371010L, 281474976710656L, 0L, 0L},
                    new long[]{4503633987371010L, 0L, 0L, 0L}, 79);
            immediates[2629] = new Pattern(new long[]{68720001028L, 562954248388608L, 0L, 0L},
                    new long[]{68720001028L, 562949953421312L, 0L, 0L}, 95);
            immediates[2630] = new Pattern(new long[]{1048584L, 0x4000200010000L, 0L, 0L},
                    new long[]{1048584L, 0x4000200000000L, 0L, 0L}, 111);
            immediates[2631] =
                    new Pattern(new long[]{16L, 0x8000400020001L, 0L, 0L}, new long[]{16L, 0x8000400020000L, 0L, 0L},
                            127);
            immediates[2632] = new Pattern(new long[]{0L, 4503633987371010L, 281474976710656L, 0L},
                    new long[]{0L, 4503633987371010L, 0L, 0L}, 143);
            immediates[2633] = new Pattern(new long[]{0L, 68720001028L, 562954248388608L, 0L},
                    new long[]{0L, 68720001028L, 562949953421312L, 0L}, 159);
            immediates[2634] = new Pattern(new long[]{0L, 1048584L, 0x4000200010000L, 0L},
                    new long[]{0L, 1048584L, 0x4000200000000L, 0L}, 175);
            immediates[2635] =
                    new Pattern(new long[]{0L, 16L, 0x8000400020001L, 0L}, new long[]{0L, 16L, 0x8000400020000L, 0L},
                            191);
            immediates[2636] = new Pattern(new long[]{0L, 0L, 4503633987371010L, 281474976710656L},
                    new long[]{0L, 0L, 4503633987371010L, 0L}, 207);
            immediates[2637] = new Pattern(new long[]{0L, 0L, 68720001028L, 562954248388608L},
                    new long[]{0L, 0L, 68720001028L, 562949953421312L}, 223);
            immediates[2638] = new Pattern(new long[]{0L, 0L, 1048584L, 0x4000200010000L},
                    new long[]{0L, 0L, 1048584L, 0x4000200000000L}, 239);
            immediates[2639] =
                    new Pattern(new long[]{0L, 0L, 16L, 0x8000400020001L}, new long[]{0L, 0L, 16L, 0x8000400020000L},
                            255);
            immediates[2640] = new Pattern(new long[]{0x800100020004000L, 0x8000000000000000L, 0L, 0L},
                    new long[]{17592722931712L, 0x8000000000000000L, 0L, 0L}, 4);
            immediates[2641] = new Pattern(new long[]{8796361465856L, 0x4000800000000000L, 0L, 0L},
                    new long[]{268443648L, 0x4000800000000000L, 0L, 0L}, 20);
            immediates[2642] = new Pattern(new long[]{134221824L, 0x2000400080000000L, 0L, 0L},
                    new long[]{4096L, 0x2000400080000000L, 0L, 0L}, 36);
            immediates[2643] = new Pattern(new long[]{2048L, 0x1000200040008000L, 0L, 0L},
                    new long[]{0L, 0x1000200040008000L, 0L, 0L}, 52);
            immediates[2644] = new Pattern(new long[]{0L, 0x800100020004000L, 0x8000000000000000L, 0L},
                    new long[]{0L, 17592722931712L, 0x8000000000000000L, 0L}, 68);
            immediates[2645] = new Pattern(new long[]{0L, 8796361465856L, 0x4000800000000000L, 0L},
                    new long[]{0L, 268443648L, 0x4000800000000000L, 0L}, 84);
            immediates[2646] = new Pattern(new long[]{0L, 134221824L, 0x2000400080000000L, 0L},
                    new long[]{0L, 4096L, 0x2000400080000000L, 0L}, 100);
            immediates[2647] = new Pattern(new long[]{0L, 2048L, 0x1000200040008000L, 0L},
                    new long[]{0L, 0L, 0x1000200040008000L, 0L}, 116);
            immediates[2648] = new Pattern(new long[]{0L, 0L, 0x800100020004000L, 0x8000000000000000L},
                    new long[]{0L, 0L, 17592722931712L, 0x8000000000000000L}, 132);
            immediates[2649] = new Pattern(new long[]{0L, 0L, 8796361465856L, 0x4000800000000000L},
                    new long[]{0L, 0L, 268443648L, 0x4000800000000000L}, 148);
            immediates[2650] = new Pattern(new long[]{0L, 0L, 134221824L, 0x2000400080000000L},
                    new long[]{0L, 0L, 4096L, 0x2000400080000000L}, 164);
            immediates[2651] = new Pattern(new long[]{0L, 0L, 2048L, 0x1000200040008000L},
                    new long[]{0L, 0L, 0L, 0x1000200040008000L}, 180);
            immediates[2652] = new Pattern(new long[]{0x400080010002000L, 0x4000000000000000L, 0L, 0L},
                    new long[]{8796361465856L, 0x4000000000000000L, 0L, 0L}, 5);
            immediates[2653] = new Pattern(new long[]{4398180732928L, 0x2000400000000000L, 0L, 0L},
                    new long[]{134221824L, 0x2000400000000000L, 0L, 0L}, 21);
            immediates[2654] = new Pattern(new long[]{67110912L, 0x1000200040000000L, 0L, 0L},
                    new long[]{2048L, 0x1000200040000000L, 0L, 0L}, 37);
            immediates[2655] = new Pattern(new long[]{1024L, 0x800100020004000L, 0L, 0L},
                    new long[]{0L, 0x800100020004000L, 0L, 0L}, 53);
            immediates[2656] = new Pattern(new long[]{0L, 0x400080010002000L, 0x4000000000000000L, 0L},
                    new long[]{0L, 8796361465856L, 0x4000000000000000L, 0L}, 69);
            immediates[2657] = new Pattern(new long[]{0L, 4398180732928L, 0x2000400000000000L, 0L},
                    new long[]{0L, 134221824L, 0x2000400000000000L, 0L}, 85);
            immediates[2658] = new Pattern(new long[]{0L, 67110912L, 0x1000200040000000L, 0L},
                    new long[]{0L, 2048L, 0x1000200040000000L, 0L}, 101);
            immediates[2659] = new Pattern(new long[]{0L, 1024L, 0x800100020004000L, 0L},
                    new long[]{0L, 0L, 0x800100020004000L, 0L}, 117);
            immediates[2660] = new Pattern(new long[]{0L, 0L, 0x400080010002000L, 0x4000000000000000L},
                    new long[]{0L, 0L, 8796361465856L, 0x4000000000000000L}, 133);
            immediates[2661] = new Pattern(new long[]{0L, 0L, 4398180732928L, 0x2000400000000000L},
                    new long[]{0L, 0L, 134221824L, 0x2000400000000000L}, 149);
            immediates[2662] = new Pattern(new long[]{0L, 0L, 67110912L, 0x1000200040000000L},
                    new long[]{0L, 0L, 2048L, 0x1000200040000000L}, 165);
            immediates[2663] = new Pattern(new long[]{0L, 0L, 1024L, 0x800100020004000L},
                    new long[]{0L, 0L, 0L, 0x800100020004000L}, 181);
            immediates[2664] = new Pattern(new long[]{0x200040008001000L, 0x2000000000000000L, 0L, 0L},
                    new long[]{4398180732928L, 0x2000000000000000L, 0L, 0L}, 6);
            immediates[2665] = new Pattern(new long[]{2199090366464L, 0x1000200000000000L, 0L, 0L},
                    new long[]{67110912L, 0x1000200000000000L, 0L, 0L}, 22);
            immediates[2666] = new Pattern(new long[]{33555456L, 0x800100020000000L, 0L, 0L},
                    new long[]{1024L, 0x800100020000000L, 0L, 0L}, 38);
            immediates[2667] = new Pattern(new long[]{512L, 0x400080010002000L, 0L, 0L},
                    new long[]{0L, 0x400080010002000L, 0L, 0L}, 54);
            immediates[2668] = new Pattern(new long[]{0L, 0x200040008001000L, 0x2000000000000000L, 0L},
                    new long[]{0L, 4398180732928L, 0x2000000000000000L, 0L}, 70);
            immediates[2669] = new Pattern(new long[]{0L, 2199090366464L, 0x1000200000000000L, 0L},
                    new long[]{0L, 67110912L, 0x1000200000000000L, 0L}, 86);
            immediates[2670] = new Pattern(new long[]{0L, 33555456L, 0x800100020000000L, 0L},
                    new long[]{0L, 1024L, 0x800100020000000L, 0L}, 102);
            immediates[2671] = new Pattern(new long[]{0L, 512L, 0x400080010002000L, 0L},
                    new long[]{0L, 0L, 0x400080010002000L, 0L}, 118);
            immediates[2672] = new Pattern(new long[]{0L, 0L, 0x200040008001000L, 0x2000000000000000L},
                    new long[]{0L, 0L, 4398180732928L, 0x2000000000000000L}, 134);
            immediates[2673] = new Pattern(new long[]{0L, 0L, 2199090366464L, 0x1000200000000000L},
                    new long[]{0L, 0L, 67110912L, 0x1000200000000000L}, 150);
            immediates[2674] = new Pattern(new long[]{0L, 0L, 33555456L, 0x800100020000000L},
                    new long[]{0L, 0L, 1024L, 0x800100020000000L}, 166);
            immediates[2675] = new Pattern(new long[]{0L, 0L, 512L, 0x400080010002000L},
                    new long[]{0L, 0L, 0L, 0x400080010002000L}, 182);
            immediates[2676] = new Pattern(new long[]{72059793128294400L, 0x1000000000000000L, 0L, 0L},
                    new long[]{2199090366464L, 0x1000000000000000L, 0L, 0L}, 7);
            immediates[2677] = new Pattern(new long[]{1099545183232L, 0x800100000000000L, 0L, 0L},
                    new long[]{33555456L, 0x800100000000000L, 0L, 0L}, 23);
            immediates[2678] = new Pattern(new long[]{16777728L, 0x400080010000000L, 0L, 0L},
                    new long[]{512L, 0x400080010000000L, 0L, 0L}, 39);
            immediates[2679] = new Pattern(new long[]{256L, 0x200040008001000L, 0L, 0L},
                    new long[]{0L, 0x200040008001000L, 0L, 0L}, 55);
            immediates[2680] = new Pattern(new long[]{0L, 72059793128294400L, 0x1000000000000000L, 0L},
                    new long[]{0L, 2199090366464L, 0x1000000000000000L, 0L}, 71);
            immediates[2681] = new Pattern(new long[]{0L, 1099545183232L, 0x800100000000000L, 0L},
                    new long[]{0L, 33555456L, 0x800100000000000L, 0L}, 87);
            immediates[2682] = new Pattern(new long[]{0L, 16777728L, 0x400080010000000L, 0L},
                    new long[]{0L, 512L, 0x400080010000000L, 0L}, 103);
            immediates[2683] = new Pattern(new long[]{0L, 256L, 0x200040008001000L, 0L},
                    new long[]{0L, 0L, 0x200040008001000L, 0L}, 119);
            immediates[2684] = new Pattern(new long[]{0L, 0L, 72059793128294400L, 0x1000000000000000L},
                    new long[]{0L, 0L, 2199090366464L, 0x1000000000000000L}, 135);
            immediates[2685] = new Pattern(new long[]{0L, 0L, 1099545183232L, 0x800100000000000L},
                    new long[]{0L, 0L, 33555456L, 0x800100000000000L}, 151);
            immediates[2686] = new Pattern(new long[]{0L, 0L, 16777728L, 0x400080010000000L},
                    new long[]{0L, 0L, 512L, 0x400080010000000L}, 167);
            immediates[2687] = new Pattern(new long[]{0L, 0L, 256L, 0x200040008001000L},
                    new long[]{0L, 0L, 0L, 0x200040008001000L}, 183);
            immediates[2688] = new Pattern(new long[]{0x80010002000400L, 0x800000000000000L, 0L, 0L},
                    new long[]{1099545183232L, 0x800000000000000L, 0L, 0L}, 8);
            immediates[2689] = new Pattern(new long[]{549772591616L, 0x400080000000000L, 0L, 0L},
                    new long[]{16777728L, 0x400080000000000L, 0L, 0L}, 24);
            immediates[2690] = new Pattern(new long[]{8388864L, 0x200040008000000L, 0L, 0L},
                    new long[]{256L, 0x200040008000000L, 0L, 0L}, 40);
            immediates[2691] = new Pattern(new long[]{128L, 72059793128294400L, 0L, 0L},
                    new long[]{0L, 72059793128294400L, 0L, 0L}, 56);
            immediates[2692] = new Pattern(new long[]{0L, 0x80010002000400L, 0x800000000000000L, 0L},
                    new long[]{0L, 1099545183232L, 0x800000000000000L, 0L}, 72);
            immediates[2693] = new Pattern(new long[]{0L, 549772591616L, 0x400080000000000L, 0L},
                    new long[]{0L, 16777728L, 0x400080000000000L, 0L}, 88);
            immediates[2694] = new Pattern(new long[]{0L, 8388864L, 0x200040008000000L, 0L},
                    new long[]{0L, 256L, 0x200040008000000L, 0L}, 104);
            immediates[2695] = new Pattern(new long[]{0L, 128L, 72059793128294400L, 0L},
                    new long[]{0L, 0L, 72059793128294400L, 0L}, 120);
            immediates[2696] = new Pattern(new long[]{0L, 0L, 0x80010002000400L, 0x800000000000000L},
                    new long[]{0L, 0L, 1099545183232L, 0x800000000000000L}, 136);
            immediates[2697] = new Pattern(new long[]{0L, 0L, 549772591616L, 0x400080000000000L},
                    new long[]{0L, 0L, 16777728L, 0x400080000000000L}, 152);
            immediates[2698] = new Pattern(new long[]{0L, 0L, 8388864L, 0x200040008000000L},
                    new long[]{0L, 0L, 256L, 0x200040008000000L}, 168);
            immediates[2699] = new Pattern(new long[]{0L, 0L, 128L, 72059793128294400L},
                    new long[]{0L, 0L, 0L, 72059793128294400L}, 184);
            immediates[2700] = new Pattern(new long[]{0x40008001000200L, 0x400000000000000L, 0L, 0L},
                    new long[]{549772591616L, 0x400000000000000L, 0L, 0L}, 9);
            immediates[2701] = new Pattern(new long[]{274886295808L, 0x200040000000000L, 0L, 0L},
                    new long[]{8388864L, 0x200040000000000L, 0L, 0L}, 25);
            immediates[2702] = new Pattern(new long[]{4194432L, 72059793128292352L, 0L, 0L},
                    new long[]{128L, 72059793128292352L, 0L, 0L}, 41);
            immediates[2703] =
                    new Pattern(new long[]{64L, 0x80010002000400L, 0L, 0L}, new long[]{0L, 0x80010002000400L, 0L, 0L},
                            57);
            immediates[2704] = new Pattern(new long[]{0L, 0x40008001000200L, 0x400000000000000L, 0L},
                    new long[]{0L, 549772591616L, 0x400000000000000L, 0L}, 73);
            immediates[2705] = new Pattern(new long[]{0L, 274886295808L, 0x200040000000000L, 0L},
                    new long[]{0L, 8388864L, 0x200040000000000L, 0L}, 89);
            immediates[2706] = new Pattern(new long[]{0L, 4194432L, 72059793128292352L, 0L},
                    new long[]{0L, 128L, 72059793128292352L, 0L}, 105);
            immediates[2707] =
                    new Pattern(new long[]{0L, 64L, 0x80010002000400L, 0L}, new long[]{0L, 0L, 0x80010002000400L, 0L},
                            121);
            immediates[2708] = new Pattern(new long[]{0L, 0L, 0x40008001000200L, 0x400000000000000L},
                    new long[]{0L, 0L, 549772591616L, 0x400000000000000L}, 137);
            immediates[2709] = new Pattern(new long[]{0L, 0L, 274886295808L, 0x200040000000000L},
                    new long[]{0L, 0L, 8388864L, 0x200040000000000L}, 153);
            immediates[2710] = new Pattern(new long[]{0L, 0L, 4194432L, 72059793128292352L},
                    new long[]{0L, 0L, 128L, 72059793128292352L}, 169);
            immediates[2711] =
                    new Pattern(new long[]{0L, 0L, 64L, 0x80010002000400L}, new long[]{0L, 0L, 0L, 0x80010002000400L},
                            185);
            immediates[2712] = new Pattern(new long[]{9007474141036800L, 0x200000000000000L, 0L, 0L},
                    new long[]{274886295808L, 0x200000000000000L, 0L, 0L}, 10);
            immediates[2713] = new Pattern(new long[]{137443147904L, 72059793061183488L, 0L, 0L},
                    new long[]{4194432L, 72059793061183488L, 0L, 0L}, 26);
            immediates[2714] = new Pattern(new long[]{2097216L, 0x80010002000000L, 0L, 0L},
                    new long[]{64L, 0x80010002000000L, 0L, 0L}, 42);
            immediates[2715] =
                    new Pattern(new long[]{32L, 0x40008001000200L, 0L, 0L}, new long[]{0L, 0x40008001000200L, 0L, 0L},
                            58);
            immediates[2716] = new Pattern(new long[]{0L, 9007474141036800L, 0x200000000000000L, 0L},
                    new long[]{0L, 274886295808L, 0x200000000000000L, 0L}, 74);
            immediates[2717] = new Pattern(new long[]{0L, 137443147904L, 72059793061183488L, 0L},
                    new long[]{0L, 4194432L, 72059793061183488L, 0L}, 90);
            immediates[2718] = new Pattern(new long[]{0L, 2097216L, 0x80010002000000L, 0L},
                    new long[]{0L, 64L, 0x80010002000000L, 0L}, 106);
            immediates[2719] =
                    new Pattern(new long[]{0L, 32L, 0x40008001000200L, 0L}, new long[]{0L, 0L, 0x40008001000200L, 0L},
                            122);
            immediates[2720] = new Pattern(new long[]{0L, 0L, 9007474141036800L, 0x200000000000000L},
                    new long[]{0L, 0L, 274886295808L, 0x200000000000000L}, 138);
            immediates[2721] = new Pattern(new long[]{0L, 0L, 137443147904L, 72059793061183488L},
                    new long[]{0L, 0L, 4194432L, 72059793061183488L}, 154);
            immediates[2722] = new Pattern(new long[]{0L, 0L, 2097216L, 0x80010002000000L},
                    new long[]{0L, 0L, 64L, 0x80010002000000L}, 170);
            immediates[2723] =
                    new Pattern(new long[]{0L, 0L, 32L, 0x40008001000200L}, new long[]{0L, 0L, 0L, 0x40008001000200L},
                            186);
            immediates[2724] = new Pattern(new long[]{4503737070518400L, 72057594037927936L, 0L, 0L},
                    new long[]{137443147904L, 72057594037927936L, 0L, 0L}, 11);
            immediates[2725] = new Pattern(new long[]{68721573952L, 0x80010000000000L, 0L, 0L},
                    new long[]{2097216L, 0x80010000000000L, 0L, 0L}, 27);
            immediates[2726] = new Pattern(new long[]{1048608L, 0x40008001000000L, 0L, 0L},
                    new long[]{32L, 0x40008001000000L, 0L, 0L}, 43);
            immediates[2727] =
                    new Pattern(new long[]{16L, 9007474141036800L, 0L, 0L}, new long[]{0L, 9007474141036800L, 0L, 0L},
                            59);
            immediates[2728] = new Pattern(new long[]{0L, 4503737070518400L, 72057594037927936L, 0L},
                    new long[]{0L, 137443147904L, 72057594037927936L, 0L}, 75);
            immediates[2729] = new Pattern(new long[]{0L, 68721573952L, 0x80010000000000L, 0L},
                    new long[]{0L, 2097216L, 0x80010000000000L, 0L}, 91);
            immediates[2730] = new Pattern(new long[]{0L, 1048608L, 0x40008001000000L, 0L},
                    new long[]{0L, 32L, 0x40008001000000L, 0L}, 107);
            immediates[2731] =
                    new Pattern(new long[]{0L, 16L, 9007474141036800L, 0L}, new long[]{0L, 0L, 9007474141036800L, 0L},
                            123);
            immediates[2732] = new Pattern(new long[]{0L, 0L, 4503737070518400L, 72057594037927936L},
                    new long[]{0L, 0L, 137443147904L, 72057594037927936L}, 139);
            immediates[2733] = new Pattern(new long[]{0L, 0L, 68721573952L, 0x80010000000000L},
                    new long[]{0L, 0L, 2097216L, 0x80010000000000L}, 155);
            immediates[2734] = new Pattern(new long[]{0L, 0L, 1048608L, 0x40008001000000L},
                    new long[]{0L, 0L, 32L, 0x40008001000000L}, 171);
            immediates[2735] =
                    new Pattern(new long[]{0L, 0L, 16L, 9007474141036800L}, new long[]{0L, 0L, 0L, 9007474141036800L},
                            187);
            immediates[2736] = new Pattern(new long[]{0x8001000200040L, 0x80000000000000L, 0L, 0L},
                    new long[]{68721573952L, 0x80000000000000L, 0L, 0L}, 12);
            immediates[2737] = new Pattern(new long[]{34360786976L, 0x40008000000000L, 0L, 0L},
                    new long[]{1048608L, 0x40008000000000L, 0L, 0L}, 28);
            immediates[2738] = new Pattern(new long[]{524304L, 9007474141036544L, 0L, 0L},
                    new long[]{16L, 9007474141036544L, 0L, 0L}, 44);
            immediates[2739] =
                    new Pattern(new long[]{8L, 4503737070518400L, 0L, 0L}, new long[]{0L, 4503737070518400L, 0L, 0L},
                            60);
            immediates[2740] = new Pattern(new long[]{0L, 0x8001000200040L, 0x80000000000000L, 0L},
                    new long[]{0L, 68721573952L, 0x80000000000000L, 0L}, 76);
            immediates[2741] = new Pattern(new long[]{0L, 34360786976L, 0x40008000000000L, 0L},
                    new long[]{0L, 1048608L, 0x40008000000000L, 0L}, 92);
            immediates[2742] = new Pattern(new long[]{0L, 524304L, 9007474141036544L, 0L},
                    new long[]{0L, 16L, 9007474141036544L, 0L}, 108);
            immediates[2743] =
                    new Pattern(new long[]{0L, 8L, 4503737070518400L, 0L}, new long[]{0L, 0L, 4503737070518400L, 0L},
                            124);
            immediates[2744] = new Pattern(new long[]{0L, 0L, 0x8001000200040L, 0x80000000000000L},
                    new long[]{0L, 0L, 68721573952L, 0x80000000000000L}, 140);
            immediates[2745] = new Pattern(new long[]{0L, 0L, 34360786976L, 0x40008000000000L},
                    new long[]{0L, 0L, 1048608L, 0x40008000000000L}, 156);
            immediates[2746] = new Pattern(new long[]{0L, 0L, 524304L, 9007474141036544L},
                    new long[]{0L, 0L, 16L, 9007474141036544L}, 172);
            immediates[2747] =
                    new Pattern(new long[]{0L, 0L, 8L, 4503737070518400L}, new long[]{0L, 0L, 0L, 4503737070518400L},
                            188);
            immediates[2748] = new Pattern(new long[]{0x4000800100020L, 0x40000000000000L, 0L, 0L},
                    new long[]{34360786976L, 0x40000000000000L, 0L, 0L}, 13);
            immediates[2749] = new Pattern(new long[]{17180393488L, 9007474132647936L, 0L, 0L},
                    new long[]{524304L, 9007474132647936L, 0L, 0L}, 29);
            immediates[2750] = new Pattern(new long[]{262152L, 4503737070518272L, 0L, 0L},
                    new long[]{8L, 4503737070518272L, 0L, 0L}, 45);
            immediates[2751] =
                    new Pattern(new long[]{4L, 0x8001000200040L, 0L, 0L}, new long[]{0L, 0x8001000200040L, 0L, 0L}, 61);
            immediates[2752] = new Pattern(new long[]{0L, 0x4000800100020L, 0x40000000000000L, 0L},
                    new long[]{0L, 34360786976L, 0x40000000000000L, 0L}, 77);
            immediates[2753] = new Pattern(new long[]{0L, 17180393488L, 9007474132647936L, 0L},
                    new long[]{0L, 524304L, 9007474132647936L, 0L}, 93);
            immediates[2754] = new Pattern(new long[]{0L, 262152L, 4503737070518272L, 0L},
                    new long[]{0L, 8L, 4503737070518272L, 0L}, 109);
            immediates[2755] =
                    new Pattern(new long[]{0L, 4L, 0x8001000200040L, 0L}, new long[]{0L, 0L, 0x8001000200040L, 0L},
                            125);
            immediates[2756] = new Pattern(new long[]{0L, 0L, 0x4000800100020L, 0x40000000000000L},
                    new long[]{0L, 0L, 34360786976L, 0x40000000000000L}, 141);
            immediates[2757] = new Pattern(new long[]{0L, 0L, 17180393488L, 9007474132647936L},
                    new long[]{0L, 0L, 524304L, 9007474132647936L}, 157);
            immediates[2758] = new Pattern(new long[]{0L, 0L, 262152L, 4503737070518272L},
                    new long[]{0L, 0L, 8L, 4503737070518272L}, 173);
            immediates[2759] =
                    new Pattern(new long[]{0L, 0L, 4L, 0x8001000200040L}, new long[]{0L, 0L, 0L, 0x8001000200040L},
                            189);
            immediates[2760] = new Pattern(new long[]{562967133814800L, 9007199254740992L, 0L, 0L},
                    new long[]{17180393488L, 9007199254740992L, 0L, 0L}, 14);
            immediates[2761] = new Pattern(new long[]{8590196744L, 4503737066323968L, 0L, 0L},
                    new long[]{262152L, 4503737066323968L, 0L, 0L}, 30);
            immediates[2762] =
                    new Pattern(new long[]{131076L, 0x8001000200000L, 0L, 0L}, new long[]{4L, 0x8001000200000L, 0L, 0L},
                            46);
            immediates[2763] =
                    new Pattern(new long[]{2L, 0x4000800100020L, 0L, 0L}, new long[]{0L, 0x4000800100020L, 0L, 0L}, 62);
            immediates[2764] = new Pattern(new long[]{0L, 562967133814800L, 9007199254740992L, 0L},
                    new long[]{0L, 17180393488L, 9007199254740992L, 0L}, 78);
            immediates[2765] = new Pattern(new long[]{0L, 8590196744L, 4503737066323968L, 0L},
                    new long[]{0L, 262152L, 4503737066323968L, 0L}, 94);
            immediates[2766] =
                    new Pattern(new long[]{0L, 131076L, 0x8001000200000L, 0L}, new long[]{0L, 4L, 0x8001000200000L, 0L},
                            110);
            immediates[2767] =
                    new Pattern(new long[]{0L, 2L, 0x4000800100020L, 0L}, new long[]{0L, 0L, 0x4000800100020L, 0L},
                            126);
            immediates[2768] = new Pattern(new long[]{0L, 0L, 562967133814800L, 9007199254740992L},
                    new long[]{0L, 0L, 17180393488L, 9007199254740992L}, 142);
            immediates[2769] = new Pattern(new long[]{0L, 0L, 8590196744L, 4503737066323968L},
                    new long[]{0L, 0L, 262152L, 4503737066323968L}, 158);
            immediates[2770] =
                    new Pattern(new long[]{0L, 0L, 131076L, 0x8001000200000L}, new long[]{0L, 0L, 4L, 0x8001000200000L},
                            174);
            immediates[2771] =
                    new Pattern(new long[]{0L, 0L, 2L, 0x4000800100020L}, new long[]{0L, 0L, 0L, 0x4000800100020L},
                            190);
            immediates[2772] = new Pattern(new long[]{281483566907400L, 4503599627370496L, 0L, 0L},
                    new long[]{8590196744L, 4503599627370496L, 0L, 0L}, 15);
            immediates[2773] = new Pattern(new long[]{4295098372L, 0x8001000000000L, 0L, 0L},
                    new long[]{131076L, 0x8001000000000L, 0L, 0L}, 31);
            immediates[2774] =
                    new Pattern(new long[]{65538L, 0x4000800100000L, 0L, 0L}, new long[]{2L, 0x4000800100000L, 0L, 0L},
                            47);
            immediates[2775] =
                    new Pattern(new long[]{1L, 562967133814800L, 0L, 0L}, new long[]{0L, 562967133814800L, 0L, 0L}, 63);
            immediates[2776] = new Pattern(new long[]{0L, 281483566907400L, 4503599627370496L, 0L},
                    new long[]{0L, 8590196744L, 4503599627370496L, 0L}, 79);
            immediates[2777] = new Pattern(new long[]{0L, 4295098372L, 0x8001000000000L, 0L},
                    new long[]{0L, 131076L, 0x8001000000000L, 0L}, 95);
            immediates[2778] =
                    new Pattern(new long[]{0L, 65538L, 0x4000800100000L, 0L}, new long[]{0L, 2L, 0x4000800100000L, 0L},
                            111);
            immediates[2779] =
                    new Pattern(new long[]{0L, 1L, 562967133814800L, 0L}, new long[]{0L, 0L, 562967133814800L, 0L},
                            127);
            immediates[2780] = new Pattern(new long[]{0L, 0L, 281483566907400L, 4503599627370496L},
                    new long[]{0L, 0L, 8590196744L, 4503599627370496L}, 143);
            immediates[2781] = new Pattern(new long[]{0L, 0L, 4295098372L, 0x8001000000000L},
                    new long[]{0L, 0L, 131076L, 0x8001000000000L}, 159);
            immediates[2782] =
                    new Pattern(new long[]{0L, 0L, 65538L, 0x4000800100000L}, new long[]{0L, 0L, 2L, 0x4000800100000L},
                            175);
            immediates[2783] =
                    new Pattern(new long[]{0L, 0L, 1L, 562967133814800L}, new long[]{0L, 0L, 0L, 562967133814800L},
                            191);
            immediates[2784] = new Pattern(new long[]{0x800100020004000L, 0x8000000000000000L, 0L, 0L},
                    new long[]{0x800000020004000L, 0x8000000000000000L, 0L, 0L}, 19);
            immediates[2785] = new Pattern(new long[]{8796361465856L, 0x4000800000000000L, 0L, 0L},
                    new long[]{8796093030400L, 0x4000800000000000L, 0L, 0L}, 35);
            immediates[2786] = new Pattern(new long[]{134221824L, 0x2000400080000000L, 0L, 0L},
                    new long[]{134217728L, 0x2000400080000000L, 0L, 0L}, 51);
            immediates[2787] = new Pattern(new long[]{2048L, 0x1000200040008000L, 0L, 0L},
                    new long[]{2048L, 35185445863424L, 0L, 0L}, 67);
            immediates[2788] = new Pattern(new long[]{0L, 0x800100020004000L, 0x8000000000000000L, 0L},
                    new long[]{0L, 0x800000020004000L, 0x8000000000000000L, 0L}, 83);
            immediates[2789] = new Pattern(new long[]{0L, 8796361465856L, 0x4000800000000000L, 0L},
                    new long[]{0L, 8796093030400L, 0x4000800000000000L, 0L}, 99);
            immediates[2790] = new Pattern(new long[]{0L, 134221824L, 0x2000400080000000L, 0L},
                    new long[]{0L, 134217728L, 0x2000400080000000L, 0L}, 115);
            immediates[2791] = new Pattern(new long[]{0L, 2048L, 0x1000200040008000L, 0L},
                    new long[]{0L, 2048L, 35185445863424L, 0L}, 131);
            immediates[2792] = new Pattern(new long[]{0L, 0L, 0x800100020004000L, 0x8000000000000000L},
                    new long[]{0L, 0L, 0x800000020004000L, 0x8000000000000000L}, 147);
            immediates[2793] = new Pattern(new long[]{0L, 0L, 8796361465856L, 0x4000800000000000L},
                    new long[]{0L, 0L, 8796093030400L, 0x4000800000000000L}, 163);
            immediates[2794] = new Pattern(new long[]{0L, 0L, 134221824L, 0x2000400080000000L},
                    new long[]{0L, 0L, 134217728L, 0x2000400080000000L}, 179);
            immediates[2795] = new Pattern(new long[]{0L, 0L, 2048L, 0x1000200040008000L},
                    new long[]{0L, 0L, 2048L, 35185445863424L}, 195);
            immediates[2796] = new Pattern(new long[]{0x400080010002000L, 0x4000000000000000L, 0L, 0L},
                    new long[]{0x400000010002000L, 0x4000000000000000L, 0L, 0L}, 20);
            immediates[2797] = new Pattern(new long[]{4398180732928L, 0x2000400000000000L, 0L, 0L},
                    new long[]{4398046515200L, 0x2000400000000000L, 0L, 0L}, 36);
            immediates[2798] = new Pattern(new long[]{67110912L, 0x1000200040000000L, 0L, 0L},
                    new long[]{67108864L, 0x1000200040000000L, 0L, 0L}, 52);
            immediates[2799] = new Pattern(new long[]{1024L, 0x800100020004000L, 0L, 0L},
                    new long[]{1024L, 17592722931712L, 0L, 0L}, 68);
            immediates[2800] = new Pattern(new long[]{0L, 0x400080010002000L, 0x4000000000000000L, 0L},
                    new long[]{0L, 0x400000010002000L, 0x4000000000000000L, 0L}, 84);
            immediates[2801] = new Pattern(new long[]{0L, 4398180732928L, 0x2000400000000000L, 0L},
                    new long[]{0L, 4398046515200L, 0x2000400000000000L, 0L}, 100);
            immediates[2802] = new Pattern(new long[]{0L, 67110912L, 0x1000200040000000L, 0L},
                    new long[]{0L, 67108864L, 0x1000200040000000L, 0L}, 116);
            immediates[2803] = new Pattern(new long[]{0L, 1024L, 0x800100020004000L, 0L},
                    new long[]{0L, 1024L, 17592722931712L, 0L}, 132);
            immediates[2804] = new Pattern(new long[]{0L, 0L, 0x400080010002000L, 0x4000000000000000L},
                    new long[]{0L, 0L, 0x400000010002000L, 0x4000000000000000L}, 148);
            immediates[2805] = new Pattern(new long[]{0L, 0L, 4398180732928L, 0x2000400000000000L},
                    new long[]{0L, 0L, 4398046515200L, 0x2000400000000000L}, 164);
            immediates[2806] = new Pattern(new long[]{0L, 0L, 67110912L, 0x1000200040000000L},
                    new long[]{0L, 0L, 67108864L, 0x1000200040000000L}, 180);
            immediates[2807] = new Pattern(new long[]{0L, 0L, 1024L, 0x800100020004000L},
                    new long[]{0L, 0L, 1024L, 17592722931712L}, 196);
            immediates[2808] = new Pattern(new long[]{0x200040008001000L, 0x2000000000000000L, 0L, 0L},
                    new long[]{0x200000008001000L, 0x2000000000000000L, 0L, 0L}, 21);
            immediates[2809] = new Pattern(new long[]{2199090366464L, 0x1000200000000000L, 0L, 0L},
                    new long[]{2199023257600L, 0x1000200000000000L, 0L, 0L}, 37);
            immediates[2810] = new Pattern(new long[]{33555456L, 0x800100020000000L, 0L, 0L},
                    new long[]{33554432L, 0x800100020000000L, 0L, 0L}, 53);
            immediates[2811] =
                    new Pattern(new long[]{512L, 0x400080010002000L, 0L, 0L}, new long[]{512L, 8796361465856L, 0L, 0L},
                            69);
            immediates[2812] = new Pattern(new long[]{0L, 0x200040008001000L, 0x2000000000000000L, 0L},
                    new long[]{0L, 0x200000008001000L, 0x2000000000000000L, 0L}, 85);
            immediates[2813] = new Pattern(new long[]{0L, 2199090366464L, 0x1000200000000000L, 0L},
                    new long[]{0L, 2199023257600L, 0x1000200000000000L, 0L}, 101);
            immediates[2814] = new Pattern(new long[]{0L, 33555456L, 0x800100020000000L, 0L},
                    new long[]{0L, 33554432L, 0x800100020000000L, 0L}, 117);
            immediates[2815] =
                    new Pattern(new long[]{0L, 512L, 0x400080010002000L, 0L}, new long[]{0L, 512L, 8796361465856L, 0L},
                            133);
            immediates[2816] = new Pattern(new long[]{0L, 0L, 0x200040008001000L, 0x2000000000000000L},
                    new long[]{0L, 0L, 0x200000008001000L, 0x2000000000000000L}, 149);
            immediates[2817] = new Pattern(new long[]{0L, 0L, 2199090366464L, 0x1000200000000000L},
                    new long[]{0L, 0L, 2199023257600L, 0x1000200000000000L}, 165);
            immediates[2818] = new Pattern(new long[]{0L, 0L, 33555456L, 0x800100020000000L},
                    new long[]{0L, 0L, 33554432L, 0x800100020000000L}, 181);
            immediates[2819] =
                    new Pattern(new long[]{0L, 0L, 512L, 0x400080010002000L}, new long[]{0L, 0L, 512L, 8796361465856L},
                            197);
            immediates[2820] = new Pattern(new long[]{72059793128294400L, 0x1000000000000000L, 0L, 0L},
                    new long[]{72057594105038848L, 0x1000000000000000L, 0L, 0L}, 22);
            immediates[2821] = new Pattern(new long[]{1099545183232L, 0x800100000000000L, 0L, 0L},
                    new long[]{1099511628800L, 0x800100000000000L, 0L, 0L}, 38);
            immediates[2822] = new Pattern(new long[]{16777728L, 0x400080010000000L, 0L, 0L},
                    new long[]{16777216L, 0x400080010000000L, 0L, 0L}, 54);
            immediates[2823] =
                    new Pattern(new long[]{256L, 0x200040008001000L, 0L, 0L}, new long[]{256L, 4398180732928L, 0L, 0L},
                            70);
            immediates[2824] = new Pattern(new long[]{0L, 72059793128294400L, 0x1000000000000000L, 0L},
                    new long[]{0L, 72057594105038848L, 0x1000000000000000L, 0L}, 86);
            immediates[2825] = new Pattern(new long[]{0L, 1099545183232L, 0x800100000000000L, 0L},
                    new long[]{0L, 1099511628800L, 0x800100000000000L, 0L}, 102);
            immediates[2826] = new Pattern(new long[]{0L, 16777728L, 0x400080010000000L, 0L},
                    new long[]{0L, 16777216L, 0x400080010000000L, 0L}, 118);
            immediates[2827] =
                    new Pattern(new long[]{0L, 256L, 0x200040008001000L, 0L}, new long[]{0L, 256L, 4398180732928L, 0L},
                            134);
            immediates[2828] = new Pattern(new long[]{0L, 0L, 72059793128294400L, 0x1000000000000000L},
                    new long[]{0L, 0L, 72057594105038848L, 0x1000000000000000L}, 150);
            immediates[2829] = new Pattern(new long[]{0L, 0L, 1099545183232L, 0x800100000000000L},
                    new long[]{0L, 0L, 1099511628800L, 0x800100000000000L}, 166);
            immediates[2830] = new Pattern(new long[]{0L, 0L, 16777728L, 0x400080010000000L},
                    new long[]{0L, 0L, 16777216L, 0x400080010000000L}, 182);
            immediates[2831] =
                    new Pattern(new long[]{0L, 0L, 256L, 0x200040008001000L}, new long[]{0L, 0L, 256L, 4398180732928L},
                            198);
            immediates[2832] = new Pattern(new long[]{0x80010002000400L, 0x800000000000000L, 0L, 0L},
                    new long[]{0x80000002000400L, 0x800000000000000L, 0L, 0L}, 23);
            immediates[2833] = new Pattern(new long[]{549772591616L, 0x400080000000000L, 0L, 0L},
                    new long[]{549755814400L, 0x400080000000000L, 0L, 0L}, 39);
            immediates[2834] = new Pattern(new long[]{8388864L, 0x200040008000000L, 0L, 0L},
                    new long[]{8388608L, 0x200040008000000L, 0L, 0L}, 55);
            immediates[2835] =
                    new Pattern(new long[]{128L, 72059793128294400L, 0L, 0L}, new long[]{128L, 2199090366464L, 0L, 0L},
                            71);
            immediates[2836] = new Pattern(new long[]{0L, 0x80010002000400L, 0x800000000000000L, 0L},
                    new long[]{0L, 0x80000002000400L, 0x800000000000000L, 0L}, 87);
            immediates[2837] = new Pattern(new long[]{0L, 549772591616L, 0x400080000000000L, 0L},
                    new long[]{0L, 549755814400L, 0x400080000000000L, 0L}, 103);
            immediates[2838] = new Pattern(new long[]{0L, 8388864L, 0x200040008000000L, 0L},
                    new long[]{0L, 8388608L, 0x200040008000000L, 0L}, 119);
            immediates[2839] =
                    new Pattern(new long[]{0L, 128L, 72059793128294400L, 0L}, new long[]{0L, 128L, 2199090366464L, 0L},
                            135);
            immediates[2840] = new Pattern(new long[]{0L, 0L, 0x80010002000400L, 0x800000000000000L},
                    new long[]{0L, 0L, 0x80000002000400L, 0x800000000000000L}, 151);
            immediates[2841] = new Pattern(new long[]{0L, 0L, 549772591616L, 0x400080000000000L},
                    new long[]{0L, 0L, 549755814400L, 0x400080000000000L}, 167);
            immediates[2842] = new Pattern(new long[]{0L, 0L, 8388864L, 0x200040008000000L},
                    new long[]{0L, 0L, 8388608L, 0x200040008000000L}, 183);
            immediates[2843] =
                    new Pattern(new long[]{0L, 0L, 128L, 72059793128294400L}, new long[]{0L, 0L, 128L, 2199090366464L},
                            199);
            immediates[2844] = new Pattern(new long[]{0x40008001000200L, 0x400000000000000L, 0L, 0L},
                    new long[]{0x40000001000200L, 0x400000000000000L, 0L, 0L}, 24);
            immediates[2845] = new Pattern(new long[]{274886295808L, 0x200040000000000L, 0L, 0L},
                    new long[]{274877907200L, 0x200040000000000L, 0L, 0L}, 40);
            immediates[2846] = new Pattern(new long[]{4194432L, 72059793128292352L, 0L, 0L},
                    new long[]{4194304L, 72059793128292352L, 0L, 0L}, 56);
            immediates[2847] =
                    new Pattern(new long[]{64L, 0x80010002000400L, 0L, 0L}, new long[]{64L, 1099545183232L, 0L, 0L},
                            72);
            immediates[2848] = new Pattern(new long[]{0L, 0x40008001000200L, 0x400000000000000L, 0L},
                    new long[]{0L, 0x40000001000200L, 0x400000000000000L, 0L}, 88);
            immediates[2849] = new Pattern(new long[]{0L, 274886295808L, 0x200040000000000L, 0L},
                    new long[]{0L, 274877907200L, 0x200040000000000L, 0L}, 104);
            immediates[2850] = new Pattern(new long[]{0L, 4194432L, 72059793128292352L, 0L},
                    new long[]{0L, 4194304L, 72059793128292352L, 0L}, 120);
            immediates[2851] =
                    new Pattern(new long[]{0L, 64L, 0x80010002000400L, 0L}, new long[]{0L, 64L, 1099545183232L, 0L},
                            136);
            immediates[2852] = new Pattern(new long[]{0L, 0L, 0x40008001000200L, 0x400000000000000L},
                    new long[]{0L, 0L, 0x40000001000200L, 0x400000000000000L}, 152);
            immediates[2853] = new Pattern(new long[]{0L, 0L, 274886295808L, 0x200040000000000L},
                    new long[]{0L, 0L, 274877907200L, 0x200040000000000L}, 168);
            immediates[2854] = new Pattern(new long[]{0L, 0L, 4194432L, 72059793128292352L},
                    new long[]{0L, 0L, 4194304L, 72059793128292352L}, 184);
            immediates[2855] =
                    new Pattern(new long[]{0L, 0L, 64L, 0x80010002000400L}, new long[]{0L, 0L, 64L, 1099545183232L},
                            200);
            immediates[2856] = new Pattern(new long[]{9007474141036800L, 0x200000000000000L, 0L, 0L},
                    new long[]{9007199263129856L, 0x200000000000000L, 0L, 0L}, 25);
            immediates[2857] = new Pattern(new long[]{137443147904L, 72059793061183488L, 0L, 0L},
                    new long[]{137438953600L, 72059793061183488L, 0L, 0L}, 41);
            immediates[2858] = new Pattern(new long[]{2097216L, 0x80010002000000L, 0L, 0L},
                    new long[]{2097152L, 0x80010002000000L, 0L, 0L}, 57);
            immediates[2859] =
                    new Pattern(new long[]{32L, 0x40008001000200L, 0L, 0L}, new long[]{32L, 549772591616L, 0L, 0L}, 73);
            immediates[2860] = new Pattern(new long[]{0L, 9007474141036800L, 0x200000000000000L, 0L},
                    new long[]{0L, 9007199263129856L, 0x200000000000000L, 0L}, 89);
            immediates[2861] = new Pattern(new long[]{0L, 137443147904L, 72059793061183488L, 0L},
                    new long[]{0L, 137438953600L, 72059793061183488L, 0L}, 105);
            immediates[2862] = new Pattern(new long[]{0L, 2097216L, 0x80010002000000L, 0L},
                    new long[]{0L, 2097152L, 0x80010002000000L, 0L}, 121);
            immediates[2863] =
                    new Pattern(new long[]{0L, 32L, 0x40008001000200L, 0L}, new long[]{0L, 32L, 549772591616L, 0L},
                            137);
            immediates[2864] = new Pattern(new long[]{0L, 0L, 9007474141036800L, 0x200000000000000L},
                    new long[]{0L, 0L, 9007199263129856L, 0x200000000000000L}, 153);
            immediates[2865] = new Pattern(new long[]{0L, 0L, 137443147904L, 72059793061183488L},
                    new long[]{0L, 0L, 137438953600L, 72059793061183488L}, 169);
            immediates[2866] = new Pattern(new long[]{0L, 0L, 2097216L, 0x80010002000000L},
                    new long[]{0L, 0L, 2097152L, 0x80010002000000L}, 185);
            immediates[2867] =
                    new Pattern(new long[]{0L, 0L, 32L, 0x40008001000200L}, new long[]{0L, 0L, 32L, 549772591616L},
                            201);
            immediates[2868] = new Pattern(new long[]{4503737070518400L, 72057594037927936L, 0L, 0L},
                    new long[]{4503599631564928L, 72057594037927936L, 0L, 0L}, 26);
            immediates[2869] = new Pattern(new long[]{68721573952L, 0x80010000000000L, 0L, 0L},
                    new long[]{68719476800L, 0x80010000000000L, 0L, 0L}, 42);
            immediates[2870] = new Pattern(new long[]{1048608L, 0x40008001000000L, 0L, 0L},
                    new long[]{1048576L, 0x40008001000000L, 0L, 0L}, 58);
            immediates[2871] =
                    new Pattern(new long[]{16L, 9007474141036800L, 0L, 0L}, new long[]{16L, 274886295808L, 0L, 0L}, 74);
            immediates[2872] = new Pattern(new long[]{0L, 4503737070518400L, 72057594037927936L, 0L},
                    new long[]{0L, 4503599631564928L, 72057594037927936L, 0L}, 90);
            immediates[2873] = new Pattern(new long[]{0L, 68721573952L, 0x80010000000000L, 0L},
                    new long[]{0L, 68719476800L, 0x80010000000000L, 0L}, 106);
            immediates[2874] = new Pattern(new long[]{0L, 1048608L, 0x40008001000000L, 0L},
                    new long[]{0L, 1048576L, 0x40008001000000L, 0L}, 122);
            immediates[2875] =
                    new Pattern(new long[]{0L, 16L, 9007474141036800L, 0L}, new long[]{0L, 16L, 274886295808L, 0L},
                            138);
            immediates[2876] = new Pattern(new long[]{0L, 0L, 4503737070518400L, 72057594037927936L},
                    new long[]{0L, 0L, 4503599631564928L, 72057594037927936L}, 154);
            immediates[2877] = new Pattern(new long[]{0L, 0L, 68721573952L, 0x80010000000000L},
                    new long[]{0L, 0L, 68719476800L, 0x80010000000000L}, 170);
            immediates[2878] = new Pattern(new long[]{0L, 0L, 1048608L, 0x40008001000000L},
                    new long[]{0L, 0L, 1048576L, 0x40008001000000L}, 186);
            immediates[2879] =
                    new Pattern(new long[]{0L, 0L, 16L, 9007474141036800L}, new long[]{0L, 0L, 16L, 274886295808L},
                            202);
            immediates[2880] = new Pattern(new long[]{0x8001000200040L, 0x80000000000000L, 0L, 0L},
                    new long[]{0x8000000200040L, 0x80000000000000L, 0L, 0L}, 27);
            immediates[2881] = new Pattern(new long[]{34360786976L, 0x40008000000000L, 0L, 0L},
                    new long[]{34359738400L, 0x40008000000000L, 0L, 0L}, 43);
            immediates[2882] = new Pattern(new long[]{524304L, 9007474141036544L, 0L, 0L},
                    new long[]{524288L, 9007474141036544L, 0L, 0L}, 59);
            immediates[2883] =
                    new Pattern(new long[]{8L, 4503737070518400L, 0L, 0L}, new long[]{8L, 137443147904L, 0L, 0L}, 75);
            immediates[2884] = new Pattern(new long[]{0L, 0x8001000200040L, 0x80000000000000L, 0L},
                    new long[]{0L, 0x8000000200040L, 0x80000000000000L, 0L}, 91);
            immediates[2885] = new Pattern(new long[]{0L, 34360786976L, 0x40008000000000L, 0L},
                    new long[]{0L, 34359738400L, 0x40008000000000L, 0L}, 107);
            immediates[2886] = new Pattern(new long[]{0L, 524304L, 9007474141036544L, 0L},
                    new long[]{0L, 524288L, 9007474141036544L, 0L}, 123);
            immediates[2887] =
                    new Pattern(new long[]{0L, 8L, 4503737070518400L, 0L}, new long[]{0L, 8L, 137443147904L, 0L}, 139);
            immediates[2888] = new Pattern(new long[]{0L, 0L, 0x8001000200040L, 0x80000000000000L},
                    new long[]{0L, 0L, 0x8000000200040L, 0x80000000000000L}, 155);
            immediates[2889] = new Pattern(new long[]{0L, 0L, 34360786976L, 0x40008000000000L},
                    new long[]{0L, 0L, 34359738400L, 0x40008000000000L}, 171);
            immediates[2890] = new Pattern(new long[]{0L, 0L, 524304L, 9007474141036544L},
                    new long[]{0L, 0L, 524288L, 9007474141036544L}, 187);
            immediates[2891] =
                    new Pattern(new long[]{0L, 0L, 8L, 4503737070518400L}, new long[]{0L, 0L, 8L, 137443147904L}, 203);
            immediates[2892] = new Pattern(new long[]{0x4000800100020L, 0x40000000000000L, 0L, 0L},
                    new long[]{0x4000000100020L, 0x40000000000000L, 0L, 0L}, 28);
            immediates[2893] = new Pattern(new long[]{17180393488L, 9007474132647936L, 0L, 0L},
                    new long[]{17179869200L, 9007474132647936L, 0L, 0L}, 44);
            immediates[2894] = new Pattern(new long[]{262152L, 4503737070518272L, 0L, 0L},
                    new long[]{262144L, 4503737070518272L, 0L, 0L}, 60);
            immediates[2895] =
                    new Pattern(new long[]{4L, 0x8001000200040L, 0L, 0L}, new long[]{4L, 68721573952L, 0L, 0L}, 76);
            immediates[2896] = new Pattern(new long[]{0L, 0x4000800100020L, 0x40000000000000L, 0L},
                    new long[]{0L, 0x4000000100020L, 0x40000000000000L, 0L}, 92);
            immediates[2897] = new Pattern(new long[]{0L, 17180393488L, 9007474132647936L, 0L},
                    new long[]{0L, 17179869200L, 9007474132647936L, 0L}, 108);
            immediates[2898] = new Pattern(new long[]{0L, 262152L, 4503737070518272L, 0L},
                    new long[]{0L, 262144L, 4503737070518272L, 0L}, 124);
            immediates[2899] =
                    new Pattern(new long[]{0L, 4L, 0x8001000200040L, 0L}, new long[]{0L, 4L, 68721573952L, 0L}, 140);
            immediates[2900] = new Pattern(new long[]{0L, 0L, 0x4000800100020L, 0x40000000000000L},
                    new long[]{0L, 0L, 0x4000000100020L, 0x40000000000000L}, 156);
            immediates[2901] = new Pattern(new long[]{0L, 0L, 17180393488L, 9007474132647936L},
                    new long[]{0L, 0L, 17179869200L, 9007474132647936L}, 172);
            immediates[2902] = new Pattern(new long[]{0L, 0L, 262152L, 4503737070518272L},
                    new long[]{0L, 0L, 262144L, 4503737070518272L}, 188);
            immediates[2903] =
                    new Pattern(new long[]{0L, 0L, 4L, 0x8001000200040L}, new long[]{0L, 0L, 4L, 68721573952L}, 204);
            immediates[2904] = new Pattern(new long[]{562967133814800L, 9007199254740992L, 0L, 0L},
                    new long[]{562949953945616L, 9007199254740992L, 0L, 0L}, 29);
            immediates[2905] = new Pattern(new long[]{8590196744L, 4503737066323968L, 0L, 0L},
                    new long[]{8589934600L, 4503737066323968L, 0L, 0L}, 45);
            immediates[2906] = new Pattern(new long[]{131076L, 0x8001000200000L, 0L, 0L},
                    new long[]{131072L, 0x8001000200000L, 0L, 0L}, 61);
            immediates[2907] =
                    new Pattern(new long[]{2L, 0x4000800100020L, 0L, 0L}, new long[]{2L, 34360786976L, 0L, 0L}, 77);
            immediates[2908] = new Pattern(new long[]{0L, 562967133814800L, 9007199254740992L, 0L},
                    new long[]{0L, 562949953945616L, 9007199254740992L, 0L}, 93);
            immediates[2909] = new Pattern(new long[]{0L, 8590196744L, 4503737066323968L, 0L},
                    new long[]{0L, 8589934600L, 4503737066323968L, 0L}, 109);
            immediates[2910] = new Pattern(new long[]{0L, 131076L, 0x8001000200000L, 0L},
                    new long[]{0L, 131072L, 0x8001000200000L, 0L}, 125);
            immediates[2911] =
                    new Pattern(new long[]{0L, 2L, 0x4000800100020L, 0L}, new long[]{0L, 2L, 34360786976L, 0L}, 141);
            immediates[2912] = new Pattern(new long[]{0L, 0L, 562967133814800L, 9007199254740992L},
                    new long[]{0L, 0L, 562949953945616L, 9007199254740992L}, 157);
            immediates[2913] = new Pattern(new long[]{0L, 0L, 8590196744L, 4503737066323968L},
                    new long[]{0L, 0L, 8589934600L, 4503737066323968L}, 173);
            immediates[2914] = new Pattern(new long[]{0L, 0L, 131076L, 0x8001000200000L},
                    new long[]{0L, 0L, 131072L, 0x8001000200000L}, 189);
            immediates[2915] =
                    new Pattern(new long[]{0L, 0L, 2L, 0x4000800100020L}, new long[]{0L, 0L, 2L, 34360786976L}, 205);
            immediates[2916] = new Pattern(new long[]{281483566907400L, 4503599627370496L, 0L, 0L},
                    new long[]{281474976972808L, 4503599627370496L, 0L, 0L}, 30);
            immediates[2917] = new Pattern(new long[]{4295098372L, 0x8001000000000L, 0L, 0L},
                    new long[]{4294967300L, 0x8001000000000L, 0L, 0L}, 46);
            immediates[2918] = new Pattern(new long[]{65538L, 0x4000800100000L, 0L, 0L},
                    new long[]{65536L, 0x4000800100000L, 0L, 0L}, 62);
            immediates[2919] =
                    new Pattern(new long[]{1L, 562967133814800L, 0L, 0L}, new long[]{1L, 17180393488L, 0L, 0L}, 78);
            immediates[2920] = new Pattern(new long[]{0L, 281483566907400L, 4503599627370496L, 0L},
                    new long[]{0L, 281474976972808L, 4503599627370496L, 0L}, 94);
            immediates[2921] = new Pattern(new long[]{0L, 4295098372L, 0x8001000000000L, 0L},
                    new long[]{0L, 4294967300L, 0x8001000000000L, 0L}, 110);
            immediates[2922] = new Pattern(new long[]{0L, 65538L, 0x4000800100000L, 0L},
                    new long[]{0L, 65536L, 0x4000800100000L, 0L}, 126);
            immediates[2923] =
                    new Pattern(new long[]{0L, 1L, 562967133814800L, 0L}, new long[]{0L, 1L, 17180393488L, 0L}, 142);
            immediates[2924] = new Pattern(new long[]{0L, 0L, 281483566907400L, 4503599627370496L},
                    new long[]{0L, 0L, 281474976972808L, 4503599627370496L}, 158);
            immediates[2925] = new Pattern(new long[]{0L, 0L, 4295098372L, 0x8001000000000L},
                    new long[]{0L, 0L, 4294967300L, 0x8001000000000L}, 174);
            immediates[2926] = new Pattern(new long[]{0L, 0L, 65538L, 0x4000800100000L},
                    new long[]{0L, 0L, 65536L, 0x4000800100000L}, 190);
            immediates[2927] =
                    new Pattern(new long[]{0L, 0L, 1L, 562967133814800L}, new long[]{0L, 0L, 1L, 17180393488L}, 206);
            immediates[2928] = new Pattern(new long[]{0x800100020004000L, 0x8000000000000000L, 0L, 0L},
                    new long[]{0x800100000004000L, 0x8000000000000000L, 0L, 0L}, 34);
            immediates[2929] = new Pattern(new long[]{8796361465856L, 0x4000800000000000L, 0L, 0L},
                    new long[]{8796361457664L, 0x4000800000000000L, 0L, 0L}, 50);
            immediates[2930] = new Pattern(new long[]{134221824L, 0x2000400080000000L, 0L, 0L},
                    new long[]{134221824L, 70370891661312L, 0L, 0L}, 66);
            immediates[2931] = new Pattern(new long[]{2048L, 0x1000200040008000L, 0L, 0L},
                    new long[]{2048L, 0x1000000040008000L, 0L, 0L}, 82);
            immediates[2932] = new Pattern(new long[]{0L, 0x800100020004000L, 0x8000000000000000L, 0L},
                    new long[]{0L, 0x800100000004000L, 0x8000000000000000L, 0L}, 98);
            immediates[2933] = new Pattern(new long[]{0L, 8796361465856L, 0x4000800000000000L, 0L},
                    new long[]{0L, 8796361457664L, 0x4000800000000000L, 0L}, 114);
            immediates[2934] = new Pattern(new long[]{0L, 134221824L, 0x2000400080000000L, 0L},
                    new long[]{0L, 134221824L, 70370891661312L, 0L}, 130);
            immediates[2935] = new Pattern(new long[]{0L, 2048L, 0x1000200040008000L, 0L},
                    new long[]{0L, 2048L, 0x1000000040008000L, 0L}, 146);
            immediates[2936] = new Pattern(new long[]{0L, 0L, 0x800100020004000L, 0x8000000000000000L},
                    new long[]{0L, 0L, 0x800100000004000L, 0x8000000000000000L}, 162);
            immediates[2937] = new Pattern(new long[]{0L, 0L, 8796361465856L, 0x4000800000000000L},
                    new long[]{0L, 0L, 8796361457664L, 0x4000800000000000L}, 178);
            immediates[2938] = new Pattern(new long[]{0L, 0L, 134221824L, 0x2000400080000000L},
                    new long[]{0L, 0L, 134221824L, 70370891661312L}, 194);
            immediates[2939] = new Pattern(new long[]{0L, 0L, 2048L, 0x1000200040008000L},
                    new long[]{0L, 0L, 2048L, 0x1000000040008000L}, 210);
            immediates[2940] = new Pattern(new long[]{0x400080010002000L, 0x4000000000000000L, 0L, 0L},
                    new long[]{0x400080000002000L, 0x4000000000000000L, 0L, 0L}, 35);
            immediates[2941] = new Pattern(new long[]{4398180732928L, 0x2000400000000000L, 0L, 0L},
                    new long[]{4398180728832L, 0x2000400000000000L, 0L, 0L}, 51);
            immediates[2942] = new Pattern(new long[]{67110912L, 0x1000200040000000L, 0L, 0L},
                    new long[]{67110912L, 35185445830656L, 0L, 0L}, 67);
            immediates[2943] = new Pattern(new long[]{1024L, 0x800100020004000L, 0L, 0L},
                    new long[]{1024L, 0x800000020004000L, 0L, 0L}, 83);
            immediates[2944] = new Pattern(new long[]{0L, 0x400080010002000L, 0x4000000000000000L, 0L},
                    new long[]{0L, 0x400080000002000L, 0x4000000000000000L, 0L}, 99);
            immediates[2945] = new Pattern(new long[]{0L, 4398180732928L, 0x2000400000000000L, 0L},
                    new long[]{0L, 4398180728832L, 0x2000400000000000L, 0L}, 115);
            immediates[2946] = new Pattern(new long[]{0L, 67110912L, 0x1000200040000000L, 0L},
                    new long[]{0L, 67110912L, 35185445830656L, 0L}, 131);
            immediates[2947] = new Pattern(new long[]{0L, 1024L, 0x800100020004000L, 0L},
                    new long[]{0L, 1024L, 0x800000020004000L, 0L}, 147);
            immediates[2948] = new Pattern(new long[]{0L, 0L, 0x400080010002000L, 0x4000000000000000L},
                    new long[]{0L, 0L, 0x400080000002000L, 0x4000000000000000L}, 163);
            immediates[2949] = new Pattern(new long[]{0L, 0L, 4398180732928L, 0x2000400000000000L},
                    new long[]{0L, 0L, 4398180728832L, 0x2000400000000000L}, 179);
            immediates[2950] = new Pattern(new long[]{0L, 0L, 67110912L, 0x1000200040000000L},
                    new long[]{0L, 0L, 67110912L, 35185445830656L}, 195);
            immediates[2951] = new Pattern(new long[]{0L, 0L, 1024L, 0x800100020004000L},
                    new long[]{0L, 0L, 1024L, 0x800000020004000L}, 211);
            immediates[2952] = new Pattern(new long[]{0x200040008001000L, 0x2000000000000000L, 0L, 0L},
                    new long[]{0x200040000001000L, 0x2000000000000000L, 0L, 0L}, 36);
            immediates[2953] = new Pattern(new long[]{2199090366464L, 0x1000200000000000L, 0L, 0L},
                    new long[]{2199090364416L, 0x1000200000000000L, 0L, 0L}, 52);
            immediates[2954] = new Pattern(new long[]{33555456L, 0x800100020000000L, 0L, 0L},
                    new long[]{33555456L, 17592722915328L, 0L, 0L}, 68);
            immediates[2955] = new Pattern(new long[]{512L, 0x400080010002000L, 0L, 0L},
                    new long[]{512L, 0x400000010002000L, 0L, 0L}, 84);
            immediates[2956] = new Pattern(new long[]{0L, 0x200040008001000L, 0x2000000000000000L, 0L},
                    new long[]{0L, 0x200040000001000L, 0x2000000000000000L, 0L}, 100);
            immediates[2957] = new Pattern(new long[]{0L, 2199090366464L, 0x1000200000000000L, 0L},
                    new long[]{0L, 2199090364416L, 0x1000200000000000L, 0L}, 116);
            immediates[2958] = new Pattern(new long[]{0L, 33555456L, 0x800100020000000L, 0L},
                    new long[]{0L, 33555456L, 17592722915328L, 0L}, 132);
            immediates[2959] = new Pattern(new long[]{0L, 512L, 0x400080010002000L, 0L},
                    new long[]{0L, 512L, 0x400000010002000L, 0L}, 148);
            immediates[2960] = new Pattern(new long[]{0L, 0L, 0x200040008001000L, 0x2000000000000000L},
                    new long[]{0L, 0L, 0x200040000001000L, 0x2000000000000000L}, 164);
            immediates[2961] = new Pattern(new long[]{0L, 0L, 2199090366464L, 0x1000200000000000L},
                    new long[]{0L, 0L, 2199090364416L, 0x1000200000000000L}, 180);
            immediates[2962] = new Pattern(new long[]{0L, 0L, 33555456L, 0x800100020000000L},
                    new long[]{0L, 0L, 33555456L, 17592722915328L}, 196);
            immediates[2963] = new Pattern(new long[]{0L, 0L, 512L, 0x400080010002000L},
                    new long[]{0L, 0L, 512L, 0x400000010002000L}, 212);
            immediates[2964] = new Pattern(new long[]{72059793128294400L, 0x1000000000000000L, 0L, 0L},
                    new long[]{72059793061185536L, 0x1000000000000000L, 0L, 0L}, 37);
            immediates[2965] = new Pattern(new long[]{1099545183232L, 0x800100000000000L, 0L, 0L},
                    new long[]{1099545182208L, 0x800100000000000L, 0L, 0L}, 53);
            immediates[2966] = new Pattern(new long[]{16777728L, 0x400080010000000L, 0L, 0L},
                    new long[]{16777728L, 8796361457664L, 0L, 0L}, 69);
            immediates[2967] = new Pattern(new long[]{256L, 0x200040008001000L, 0L, 0L},
                    new long[]{256L, 0x200000008001000L, 0L, 0L}, 85);
            immediates[2968] = new Pattern(new long[]{0L, 72059793128294400L, 0x1000000000000000L, 0L},
                    new long[]{0L, 72059793061185536L, 0x1000000000000000L, 0L}, 101);
            immediates[2969] = new Pattern(new long[]{0L, 1099545183232L, 0x800100000000000L, 0L},
                    new long[]{0L, 1099545182208L, 0x800100000000000L, 0L}, 117);
            immediates[2970] = new Pattern(new long[]{0L, 16777728L, 0x400080010000000L, 0L},
                    new long[]{0L, 16777728L, 8796361457664L, 0L}, 133);
            immediates[2971] = new Pattern(new long[]{0L, 256L, 0x200040008001000L, 0L},
                    new long[]{0L, 256L, 0x200000008001000L, 0L}, 149);
            immediates[2972] = new Pattern(new long[]{0L, 0L, 72059793128294400L, 0x1000000000000000L},
                    new long[]{0L, 0L, 72059793061185536L, 0x1000000000000000L}, 165);
            immediates[2973] = new Pattern(new long[]{0L, 0L, 1099545183232L, 0x800100000000000L},
                    new long[]{0L, 0L, 1099545182208L, 0x800100000000000L}, 181);
            immediates[2974] = new Pattern(new long[]{0L, 0L, 16777728L, 0x400080010000000L},
                    new long[]{0L, 0L, 16777728L, 8796361457664L}, 197);
            immediates[2975] = new Pattern(new long[]{0L, 0L, 256L, 0x200040008001000L},
                    new long[]{0L, 0L, 256L, 0x200000008001000L}, 213);
            immediates[2976] = new Pattern(new long[]{0x80010002000400L, 0x800000000000000L, 0L, 0L},
                    new long[]{0x80010000000400L, 0x800000000000000L, 0L, 0L}, 38);
            immediates[2977] = new Pattern(new long[]{549772591616L, 0x400080000000000L, 0L, 0L},
                    new long[]{549772591104L, 0x400080000000000L, 0L, 0L}, 54);
            immediates[2978] = new Pattern(new long[]{8388864L, 0x200040008000000L, 0L, 0L},
                    new long[]{8388864L, 4398180728832L, 0L, 0L}, 70);
            immediates[2979] = new Pattern(new long[]{128L, 72059793128294400L, 0L, 0L},
                    new long[]{128L, 72057594105038848L, 0L, 0L}, 86);
            immediates[2980] = new Pattern(new long[]{0L, 0x80010002000400L, 0x800000000000000L, 0L},
                    new long[]{0L, 0x80010000000400L, 0x800000000000000L, 0L}, 102);
            immediates[2981] = new Pattern(new long[]{0L, 549772591616L, 0x400080000000000L, 0L},
                    new long[]{0L, 549772591104L, 0x400080000000000L, 0L}, 118);
            immediates[2982] = new Pattern(new long[]{0L, 8388864L, 0x200040008000000L, 0L},
                    new long[]{0L, 8388864L, 4398180728832L, 0L}, 134);
            immediates[2983] = new Pattern(new long[]{0L, 128L, 72059793128294400L, 0L},
                    new long[]{0L, 128L, 72057594105038848L, 0L}, 150);
            immediates[2984] = new Pattern(new long[]{0L, 0L, 0x80010002000400L, 0x800000000000000L},
                    new long[]{0L, 0L, 0x80010000000400L, 0x800000000000000L}, 166);
            immediates[2985] = new Pattern(new long[]{0L, 0L, 549772591616L, 0x400080000000000L},
                    new long[]{0L, 0L, 549772591104L, 0x400080000000000L}, 182);
            immediates[2986] = new Pattern(new long[]{0L, 0L, 8388864L, 0x200040008000000L},
                    new long[]{0L, 0L, 8388864L, 4398180728832L}, 198);
            immediates[2987] = new Pattern(new long[]{0L, 0L, 128L, 72059793128294400L},
                    new long[]{0L, 0L, 128L, 72057594105038848L}, 214);
            immediates[2988] = new Pattern(new long[]{0x40008001000200L, 0x400000000000000L, 0L, 0L},
                    new long[]{0x40008000000200L, 0x400000000000000L, 0L, 0L}, 39);
            immediates[2989] = new Pattern(new long[]{274886295808L, 0x200040000000000L, 0L, 0L},
                    new long[]{274886295552L, 0x200040000000000L, 0L, 0L}, 55);
            immediates[2990] = new Pattern(new long[]{4194432L, 72059793128292352L, 0L, 0L},
                    new long[]{4194432L, 2199090364416L, 0L, 0L}, 71);
            immediates[2991] =
                    new Pattern(new long[]{64L, 0x80010002000400L, 0L, 0L}, new long[]{64L, 0x80000002000400L, 0L, 0L},
                            87);
            immediates[2992] = new Pattern(new long[]{0L, 0x40008001000200L, 0x400000000000000L, 0L},
                    new long[]{0L, 0x40008000000200L, 0x400000000000000L, 0L}, 103);
            immediates[2993] = new Pattern(new long[]{0L, 274886295808L, 0x200040000000000L, 0L},
                    new long[]{0L, 274886295552L, 0x200040000000000L, 0L}, 119);
            immediates[2994] = new Pattern(new long[]{0L, 4194432L, 72059793128292352L, 0L},
                    new long[]{0L, 4194432L, 2199090364416L, 0L}, 135);
            immediates[2995] =
                    new Pattern(new long[]{0L, 64L, 0x80010002000400L, 0L}, new long[]{0L, 64L, 0x80000002000400L, 0L},
                            151);
            immediates[2996] = new Pattern(new long[]{0L, 0L, 0x40008001000200L, 0x400000000000000L},
                    new long[]{0L, 0L, 0x40008000000200L, 0x400000000000000L}, 167);
            immediates[2997] = new Pattern(new long[]{0L, 0L, 274886295808L, 0x200040000000000L},
                    new long[]{0L, 0L, 274886295552L, 0x200040000000000L}, 183);
            immediates[2998] = new Pattern(new long[]{0L, 0L, 4194432L, 72059793128292352L},
                    new long[]{0L, 0L, 4194432L, 2199090364416L}, 199);
            immediates[2999] =
                    new Pattern(new long[]{0L, 0L, 64L, 0x80010002000400L}, new long[]{0L, 0L, 64L, 0x80000002000400L},
                            215);
        }

        private static void initImmediates6() {
            immediates[3000] = new Pattern(new long[]{9007474141036800L, 0x200000000000000L, 0L, 0L},
                    new long[]{9007474132648192L, 0x200000000000000L, 0L, 0L}, 40);
            immediates[3001] = new Pattern(new long[]{137443147904L, 72059793061183488L, 0L, 0L},
                    new long[]{137443147776L, 72059793061183488L, 0L, 0L}, 56);
            immediates[3002] = new Pattern(new long[]{2097216L, 0x80010002000000L, 0L, 0L},
                    new long[]{2097216L, 1099545182208L, 0L, 0L}, 72);
            immediates[3003] =
                    new Pattern(new long[]{32L, 0x40008001000200L, 0L, 0L}, new long[]{32L, 0x40000001000200L, 0L, 0L},
                            88);
            immediates[3004] = new Pattern(new long[]{0L, 9007474141036800L, 0x200000000000000L, 0L},
                    new long[]{0L, 9007474132648192L, 0x200000000000000L, 0L}, 104);
            immediates[3005] = new Pattern(new long[]{0L, 137443147904L, 72059793061183488L, 0L},
                    new long[]{0L, 137443147776L, 72059793061183488L, 0L}, 120);
            immediates[3006] = new Pattern(new long[]{0L, 2097216L, 0x80010002000000L, 0L},
                    new long[]{0L, 2097216L, 1099545182208L, 0L}, 136);
            immediates[3007] =
                    new Pattern(new long[]{0L, 32L, 0x40008001000200L, 0L}, new long[]{0L, 32L, 0x40000001000200L, 0L},
                            152);
            immediates[3008] = new Pattern(new long[]{0L, 0L, 9007474141036800L, 0x200000000000000L},
                    new long[]{0L, 0L, 9007474132648192L, 0x200000000000000L}, 168);
            immediates[3009] = new Pattern(new long[]{0L, 0L, 137443147904L, 72059793061183488L},
                    new long[]{0L, 0L, 137443147776L, 72059793061183488L}, 184);
            immediates[3010] = new Pattern(new long[]{0L, 0L, 2097216L, 0x80010002000000L},
                    new long[]{0L, 0L, 2097216L, 1099545182208L}, 200);
            immediates[3011] =
                    new Pattern(new long[]{0L, 0L, 32L, 0x40008001000200L}, new long[]{0L, 0L, 32L, 0x40000001000200L},
                            216);
            immediates[3012] = new Pattern(new long[]{4503737070518400L, 72057594037927936L, 0L, 0L},
                    new long[]{4503737066324096L, 72057594037927936L, 0L, 0L}, 41);
            immediates[3013] = new Pattern(new long[]{68721573952L, 0x80010000000000L, 0L, 0L},
                    new long[]{68721573888L, 0x80010000000000L, 0L, 0L}, 57);
            immediates[3014] = new Pattern(new long[]{1048608L, 0x40008001000000L, 0L, 0L},
                    new long[]{1048608L, 549772591104L, 0L, 0L}, 73);
            immediates[3015] =
                    new Pattern(new long[]{16L, 9007474141036800L, 0L, 0L}, new long[]{16L, 9007199263129856L, 0L, 0L},
                            89);
            immediates[3016] = new Pattern(new long[]{0L, 4503737070518400L, 72057594037927936L, 0L},
                    new long[]{0L, 4503737066324096L, 72057594037927936L, 0L}, 105);
            immediates[3017] = new Pattern(new long[]{0L, 68721573952L, 0x80010000000000L, 0L},
                    new long[]{0L, 68721573888L, 0x80010000000000L, 0L}, 121);
            immediates[3018] = new Pattern(new long[]{0L, 1048608L, 0x40008001000000L, 0L},
                    new long[]{0L, 1048608L, 549772591104L, 0L}, 137);
            immediates[3019] =
                    new Pattern(new long[]{0L, 16L, 9007474141036800L, 0L}, new long[]{0L, 16L, 9007199263129856L, 0L},
                            153);
            immediates[3020] = new Pattern(new long[]{0L, 0L, 4503737070518400L, 72057594037927936L},
                    new long[]{0L, 0L, 4503737066324096L, 72057594037927936L}, 169);
            immediates[3021] = new Pattern(new long[]{0L, 0L, 68721573952L, 0x80010000000000L},
                    new long[]{0L, 0L, 68721573888L, 0x80010000000000L}, 185);
            immediates[3022] = new Pattern(new long[]{0L, 0L, 1048608L, 0x40008001000000L},
                    new long[]{0L, 0L, 1048608L, 549772591104L}, 201);
            immediates[3023] =
                    new Pattern(new long[]{0L, 0L, 16L, 9007474141036800L}, new long[]{0L, 0L, 16L, 9007199263129856L},
                            217);
            immediates[3024] = new Pattern(new long[]{0x8001000200040L, 0x80000000000000L, 0L, 0L},
                    new long[]{0x8001000000040L, 0x80000000000000L, 0L, 0L}, 42);
            immediates[3025] = new Pattern(new long[]{34360786976L, 0x40008000000000L, 0L, 0L},
                    new long[]{34360786944L, 0x40008000000000L, 0L, 0L}, 58);
            immediates[3026] = new Pattern(new long[]{524304L, 9007474141036544L, 0L, 0L},
                    new long[]{524304L, 274886295552L, 0L, 0L}, 74);
            immediates[3027] =
                    new Pattern(new long[]{8L, 4503737070518400L, 0L, 0L}, new long[]{8L, 4503599631564928L, 0L, 0L},
                            90);
            immediates[3028] = new Pattern(new long[]{0L, 0x8001000200040L, 0x80000000000000L, 0L},
                    new long[]{0L, 0x8001000000040L, 0x80000000000000L, 0L}, 106);
            immediates[3029] = new Pattern(new long[]{0L, 34360786976L, 0x40008000000000L, 0L},
                    new long[]{0L, 34360786944L, 0x40008000000000L, 0L}, 122);
            immediates[3030] = new Pattern(new long[]{0L, 524304L, 9007474141036544L, 0L},
                    new long[]{0L, 524304L, 274886295552L, 0L}, 138);
            immediates[3031] =
                    new Pattern(new long[]{0L, 8L, 4503737070518400L, 0L}, new long[]{0L, 8L, 4503599631564928L, 0L},
                            154);
            immediates[3032] = new Pattern(new long[]{0L, 0L, 0x8001000200040L, 0x80000000000000L},
                    new long[]{0L, 0L, 0x8001000000040L, 0x80000000000000L}, 170);
            immediates[3033] = new Pattern(new long[]{0L, 0L, 34360786976L, 0x40008000000000L},
                    new long[]{0L, 0L, 34360786944L, 0x40008000000000L}, 186);
            immediates[3034] = new Pattern(new long[]{0L, 0L, 524304L, 9007474141036544L},
                    new long[]{0L, 0L, 524304L, 274886295552L}, 202);
            immediates[3035] =
                    new Pattern(new long[]{0L, 0L, 8L, 4503737070518400L}, new long[]{0L, 0L, 8L, 4503599631564928L},
                            218);
            immediates[3036] = new Pattern(new long[]{0x4000800100020L, 0x40000000000000L, 0L, 0L},
                    new long[]{0x4000800000020L, 0x40000000000000L, 0L, 0L}, 43);
            immediates[3037] = new Pattern(new long[]{17180393488L, 9007474132647936L, 0L, 0L},
                    new long[]{17180393472L, 9007474132647936L, 0L, 0L}, 59);
            immediates[3038] = new Pattern(new long[]{262152L, 4503737070518272L, 0L, 0L},
                    new long[]{262152L, 137443147776L, 0L, 0L}, 75);
            immediates[3039] =
                    new Pattern(new long[]{4L, 0x8001000200040L, 0L, 0L}, new long[]{4L, 0x8000000200040L, 0L, 0L}, 91);
            immediates[3040] = new Pattern(new long[]{0L, 0x4000800100020L, 0x40000000000000L, 0L},
                    new long[]{0L, 0x4000800000020L, 0x40000000000000L, 0L}, 107);
            immediates[3041] = new Pattern(new long[]{0L, 17180393488L, 9007474132647936L, 0L},
                    new long[]{0L, 17180393472L, 9007474132647936L, 0L}, 123);
            immediates[3042] = new Pattern(new long[]{0L, 262152L, 4503737070518272L, 0L},
                    new long[]{0L, 262152L, 137443147776L, 0L}, 139);
            immediates[3043] =
                    new Pattern(new long[]{0L, 4L, 0x8001000200040L, 0L}, new long[]{0L, 4L, 0x8000000200040L, 0L},
                            155);
            immediates[3044] = new Pattern(new long[]{0L, 0L, 0x4000800100020L, 0x40000000000000L},
                    new long[]{0L, 0L, 0x4000800000020L, 0x40000000000000L}, 171);
            immediates[3045] = new Pattern(new long[]{0L, 0L, 17180393488L, 9007474132647936L},
                    new long[]{0L, 0L, 17180393472L, 9007474132647936L}, 187);
            immediates[3046] = new Pattern(new long[]{0L, 0L, 262152L, 4503737070518272L},
                    new long[]{0L, 0L, 262152L, 137443147776L}, 203);
            immediates[3047] =
                    new Pattern(new long[]{0L, 0L, 4L, 0x8001000200040L}, new long[]{0L, 0L, 4L, 0x8000000200040L},
                            219);
            immediates[3048] = new Pattern(new long[]{562967133814800L, 9007199254740992L, 0L, 0L},
                    new long[]{562967133290512L, 9007199254740992L, 0L, 0L}, 44);
            immediates[3049] = new Pattern(new long[]{8590196744L, 4503737066323968L, 0L, 0L},
                    new long[]{8590196736L, 4503737066323968L, 0L, 0L}, 60);
            immediates[3050] = new Pattern(new long[]{131076L, 0x8001000200000L, 0L, 0L},
                    new long[]{131076L, 68721573888L, 0L, 0L}, 76);
            immediates[3051] =
                    new Pattern(new long[]{2L, 0x4000800100020L, 0L, 0L}, new long[]{2L, 0x4000000100020L, 0L, 0L}, 92);
            immediates[3052] = new Pattern(new long[]{0L, 562967133814800L, 9007199254740992L, 0L},
                    new long[]{0L, 562967133290512L, 9007199254740992L, 0L}, 108);
            immediates[3053] = new Pattern(new long[]{0L, 8590196744L, 4503737066323968L, 0L},
                    new long[]{0L, 8590196736L, 4503737066323968L, 0L}, 124);
            immediates[3054] = new Pattern(new long[]{0L, 131076L, 0x8001000200000L, 0L},
                    new long[]{0L, 131076L, 68721573888L, 0L}, 140);
            immediates[3055] =
                    new Pattern(new long[]{0L, 2L, 0x4000800100020L, 0L}, new long[]{0L, 2L, 0x4000000100020L, 0L},
                            156);
            immediates[3056] = new Pattern(new long[]{0L, 0L, 562967133814800L, 9007199254740992L},
                    new long[]{0L, 0L, 562967133290512L, 9007199254740992L}, 172);
            immediates[3057] = new Pattern(new long[]{0L, 0L, 8590196744L, 4503737066323968L},
                    new long[]{0L, 0L, 8590196736L, 4503737066323968L}, 188);
            immediates[3058] = new Pattern(new long[]{0L, 0L, 131076L, 0x8001000200000L},
                    new long[]{0L, 0L, 131076L, 68721573888L}, 204);
            immediates[3059] =
                    new Pattern(new long[]{0L, 0L, 2L, 0x4000800100020L}, new long[]{0L, 0L, 2L, 0x4000000100020L},
                            220);
            immediates[3060] = new Pattern(new long[]{281483566907400L, 4503599627370496L, 0L, 0L},
                    new long[]{281483566645256L, 4503599627370496L, 0L, 0L}, 45);
            immediates[3061] = new Pattern(new long[]{4295098372L, 0x8001000000000L, 0L, 0L},
                    new long[]{4295098368L, 0x8001000000000L, 0L, 0L}, 61);
            immediates[3062] =
                    new Pattern(new long[]{65538L, 0x4000800100000L, 0L, 0L}, new long[]{65538L, 34360786944L, 0L, 0L},
                            77);
            immediates[3063] =
                    new Pattern(new long[]{1L, 562967133814800L, 0L, 0L}, new long[]{1L, 562949953945616L, 0L, 0L}, 93);
            immediates[3064] = new Pattern(new long[]{0L, 281483566907400L, 4503599627370496L, 0L},
                    new long[]{0L, 281483566645256L, 4503599627370496L, 0L}, 109);
            immediates[3065] = new Pattern(new long[]{0L, 4295098372L, 0x8001000000000L, 0L},
                    new long[]{0L, 4295098368L, 0x8001000000000L, 0L}, 125);
            immediates[3066] =
                    new Pattern(new long[]{0L, 65538L, 0x4000800100000L, 0L}, new long[]{0L, 65538L, 34360786944L, 0L},
                            141);
            immediates[3067] =
                    new Pattern(new long[]{0L, 1L, 562967133814800L, 0L}, new long[]{0L, 1L, 562949953945616L, 0L},
                            157);
            immediates[3068] = new Pattern(new long[]{0L, 0L, 281483566907400L, 4503599627370496L},
                    new long[]{0L, 0L, 281483566645256L, 4503599627370496L}, 173);
            immediates[3069] = new Pattern(new long[]{0L, 0L, 4295098372L, 0x8001000000000L},
                    new long[]{0L, 0L, 4295098368L, 0x8001000000000L}, 189);
            immediates[3070] =
                    new Pattern(new long[]{0L, 0L, 65538L, 0x4000800100000L}, new long[]{0L, 0L, 65538L, 34360786944L},
                            205);
            immediates[3071] =
                    new Pattern(new long[]{0L, 0L, 1L, 562967133814800L}, new long[]{0L, 0L, 1L, 562949953945616L},
                            221);
            immediates[3072] = new Pattern(new long[]{0x800100020004000L, 0x8000000000000000L, 0L, 0L},
                    new long[]{0x800100020000000L, 0x8000000000000000L, 0L, 0L}, 49);
            immediates[3073] = new Pattern(new long[]{8796361465856L, 0x4000800000000000L, 0L, 0L},
                    new long[]{8796361465856L, 0x800000000000L, 0L, 0L}, 65);
            immediates[3074] = new Pattern(new long[]{134221824L, 0x2000400080000000L, 0L, 0L},
                    new long[]{134221824L, 0x2000000080000000L, 0L, 0L}, 81);
            immediates[3075] = new Pattern(new long[]{2048L, 0x1000200040008000L, 0L, 0L},
                    new long[]{2048L, 0x1000200000008000L, 0L, 0L}, 97);
            immediates[3076] = new Pattern(new long[]{0L, 0x800100020004000L, 0x8000000000000000L, 0L},
                    new long[]{0L, 0x800100020000000L, 0x8000000000000000L, 0L}, 113);
            immediates[3077] = new Pattern(new long[]{0L, 8796361465856L, 0x4000800000000000L, 0L},
                    new long[]{0L, 8796361465856L, 0x800000000000L, 0L}, 129);
            immediates[3078] = new Pattern(new long[]{0L, 134221824L, 0x2000400080000000L, 0L},
                    new long[]{0L, 134221824L, 0x2000000080000000L, 0L}, 145);
            immediates[3079] = new Pattern(new long[]{0L, 2048L, 0x1000200040008000L, 0L},
                    new long[]{0L, 2048L, 0x1000200000008000L, 0L}, 161);
            immediates[3080] = new Pattern(new long[]{0L, 0L, 0x800100020004000L, 0x8000000000000000L},
                    new long[]{0L, 0L, 0x800100020000000L, 0x8000000000000000L}, 177);
            immediates[3081] = new Pattern(new long[]{0L, 0L, 8796361465856L, 0x4000800000000000L},
                    new long[]{0L, 0L, 8796361465856L, 0x800000000000L}, 193);
            immediates[3082] = new Pattern(new long[]{0L, 0L, 134221824L, 0x2000400080000000L},
                    new long[]{0L, 0L, 134221824L, 0x2000000080000000L}, 209);
            immediates[3083] = new Pattern(new long[]{0L, 0L, 2048L, 0x1000200040008000L},
                    new long[]{0L, 0L, 2048L, 0x1000200000008000L}, 225);
            immediates[3084] = new Pattern(new long[]{0x400080010002000L, 0x4000000000000000L, 0L, 0L},
                    new long[]{0x400080010000000L, 0x4000000000000000L, 0L, 0L}, 50);
            immediates[3085] = new Pattern(new long[]{4398180732928L, 0x2000400000000000L, 0L, 0L},
                    new long[]{4398180732928L, 70368744177664L, 0L, 0L}, 66);
            immediates[3086] = new Pattern(new long[]{67110912L, 0x1000200040000000L, 0L, 0L},
                    new long[]{67110912L, 0x1000000040000000L, 0L, 0L}, 82);
            immediates[3087] = new Pattern(new long[]{1024L, 0x800100020004000L, 0L, 0L},
                    new long[]{1024L, 0x800100000004000L, 0L, 0L}, 98);
            immediates[3088] = new Pattern(new long[]{0L, 0x400080010002000L, 0x4000000000000000L, 0L},
                    new long[]{0L, 0x400080010000000L, 0x4000000000000000L, 0L}, 114);
            immediates[3089] = new Pattern(new long[]{0L, 4398180732928L, 0x2000400000000000L, 0L},
                    new long[]{0L, 4398180732928L, 70368744177664L, 0L}, 130);
            immediates[3090] = new Pattern(new long[]{0L, 67110912L, 0x1000200040000000L, 0L},
                    new long[]{0L, 67110912L, 0x1000000040000000L, 0L}, 146);
            immediates[3091] = new Pattern(new long[]{0L, 1024L, 0x800100020004000L, 0L},
                    new long[]{0L, 1024L, 0x800100000004000L, 0L}, 162);
            immediates[3092] = new Pattern(new long[]{0L, 0L, 0x400080010002000L, 0x4000000000000000L},
                    new long[]{0L, 0L, 0x400080010000000L, 0x4000000000000000L}, 178);
            immediates[3093] = new Pattern(new long[]{0L, 0L, 4398180732928L, 0x2000400000000000L},
                    new long[]{0L, 0L, 4398180732928L, 70368744177664L}, 194);
            immediates[3094] = new Pattern(new long[]{0L, 0L, 67110912L, 0x1000200040000000L},
                    new long[]{0L, 0L, 67110912L, 0x1000000040000000L}, 210);
            immediates[3095] = new Pattern(new long[]{0L, 0L, 1024L, 0x800100020004000L},
                    new long[]{0L, 0L, 1024L, 0x800100000004000L}, 226);
            immediates[3096] = new Pattern(new long[]{0x200040008001000L, 0x2000000000000000L, 0L, 0L},
                    new long[]{0x200040008000000L, 0x2000000000000000L, 0L, 0L}, 51);
            immediates[3097] = new Pattern(new long[]{2199090366464L, 0x1000200000000000L, 0L, 0L},
                    new long[]{2199090366464L, 35184372088832L, 0L, 0L}, 67);
            immediates[3098] = new Pattern(new long[]{33555456L, 0x800100020000000L, 0L, 0L},
                    new long[]{33555456L, 0x800000020000000L, 0L, 0L}, 83);
            immediates[3099] = new Pattern(new long[]{512L, 0x400080010002000L, 0L, 0L},
                    new long[]{512L, 0x400080000002000L, 0L, 0L}, 99);
            immediates[3100] = new Pattern(new long[]{0L, 0x200040008001000L, 0x2000000000000000L, 0L},
                    new long[]{0L, 0x200040008000000L, 0x2000000000000000L, 0L}, 115);
            immediates[3101] = new Pattern(new long[]{0L, 2199090366464L, 0x1000200000000000L, 0L},
                    new long[]{0L, 2199090366464L, 35184372088832L, 0L}, 131);
            immediates[3102] = new Pattern(new long[]{0L, 33555456L, 0x800100020000000L, 0L},
                    new long[]{0L, 33555456L, 0x800000020000000L, 0L}, 147);
            immediates[3103] = new Pattern(new long[]{0L, 512L, 0x400080010002000L, 0L},
                    new long[]{0L, 512L, 0x400080000002000L, 0L}, 163);
            immediates[3104] = new Pattern(new long[]{0L, 0L, 0x200040008001000L, 0x2000000000000000L},
                    new long[]{0L, 0L, 0x200040008000000L, 0x2000000000000000L}, 179);
            immediates[3105] = new Pattern(new long[]{0L, 0L, 2199090366464L, 0x1000200000000000L},
                    new long[]{0L, 0L, 2199090366464L, 35184372088832L}, 195);
            immediates[3106] = new Pattern(new long[]{0L, 0L, 33555456L, 0x800100020000000L},
                    new long[]{0L, 0L, 33555456L, 0x800000020000000L}, 211);
            immediates[3107] = new Pattern(new long[]{0L, 0L, 512L, 0x400080010002000L},
                    new long[]{0L, 0L, 512L, 0x400080000002000L}, 227);
            immediates[3108] = new Pattern(new long[]{72059793128294400L, 0x1000000000000000L, 0L, 0L},
                    new long[]{72059793128292352L, 0x1000000000000000L, 0L, 0L}, 52);
            immediates[3109] = new Pattern(new long[]{1099545183232L, 0x800100000000000L, 0L, 0L},
                    new long[]{1099545183232L, 17592186044416L, 0L, 0L}, 68);
            immediates[3110] = new Pattern(new long[]{16777728L, 0x400080010000000L, 0L, 0L},
                    new long[]{16777728L, 0x400000010000000L, 0L, 0L}, 84);
            immediates[3111] = new Pattern(new long[]{256L, 0x200040008001000L, 0L, 0L},
                    new long[]{256L, 0x200040000001000L, 0L, 0L}, 100);
            immediates[3112] = new Pattern(new long[]{0L, 72059793128294400L, 0x1000000000000000L, 0L},
                    new long[]{0L, 72059793128292352L, 0x1000000000000000L, 0L}, 116);
            immediates[3113] = new Pattern(new long[]{0L, 1099545183232L, 0x800100000000000L, 0L},
                    new long[]{0L, 1099545183232L, 17592186044416L, 0L}, 132);
            immediates[3114] = new Pattern(new long[]{0L, 16777728L, 0x400080010000000L, 0L},
                    new long[]{0L, 16777728L, 0x400000010000000L, 0L}, 148);
            immediates[3115] = new Pattern(new long[]{0L, 256L, 0x200040008001000L, 0L},
                    new long[]{0L, 256L, 0x200040000001000L, 0L}, 164);
            immediates[3116] = new Pattern(new long[]{0L, 0L, 72059793128294400L, 0x1000000000000000L},
                    new long[]{0L, 0L, 72059793128292352L, 0x1000000000000000L}, 180);
            immediates[3117] = new Pattern(new long[]{0L, 0L, 1099545183232L, 0x800100000000000L},
                    new long[]{0L, 0L, 1099545183232L, 17592186044416L}, 196);
            immediates[3118] = new Pattern(new long[]{0L, 0L, 16777728L, 0x400080010000000L},
                    new long[]{0L, 0L, 16777728L, 0x400000010000000L}, 212);
            immediates[3119] = new Pattern(new long[]{0L, 0L, 256L, 0x200040008001000L},
                    new long[]{0L, 0L, 256L, 0x200040000001000L}, 228);
            immediates[3120] = new Pattern(new long[]{0x80010002000400L, 0x800000000000000L, 0L, 0L},
                    new long[]{0x80010002000000L, 0x800000000000000L, 0L, 0L}, 53);
            immediates[3121] = new Pattern(new long[]{549772591616L, 0x400080000000000L, 0L, 0L},
                    new long[]{549772591616L, 8796093022208L, 0L, 0L}, 69);
            immediates[3122] = new Pattern(new long[]{8388864L, 0x200040008000000L, 0L, 0L},
                    new long[]{8388864L, 0x200000008000000L, 0L, 0L}, 85);
            immediates[3123] = new Pattern(new long[]{128L, 72059793128294400L, 0L, 0L},
                    new long[]{128L, 72059793061185536L, 0L, 0L}, 101);
            immediates[3124] = new Pattern(new long[]{0L, 0x80010002000400L, 0x800000000000000L, 0L},
                    new long[]{0L, 0x80010002000000L, 0x800000000000000L, 0L}, 117);
            immediates[3125] = new Pattern(new long[]{0L, 549772591616L, 0x400080000000000L, 0L},
                    new long[]{0L, 549772591616L, 8796093022208L, 0L}, 133);
            immediates[3126] = new Pattern(new long[]{0L, 8388864L, 0x200040008000000L, 0L},
                    new long[]{0L, 8388864L, 0x200000008000000L, 0L}, 149);
            immediates[3127] = new Pattern(new long[]{0L, 128L, 72059793128294400L, 0L},
                    new long[]{0L, 128L, 72059793061185536L, 0L}, 165);
            immediates[3128] = new Pattern(new long[]{0L, 0L, 0x80010002000400L, 0x800000000000000L},
                    new long[]{0L, 0L, 0x80010002000000L, 0x800000000000000L}, 181);
            immediates[3129] = new Pattern(new long[]{0L, 0L, 549772591616L, 0x400080000000000L},
                    new long[]{0L, 0L, 549772591616L, 8796093022208L}, 197);
            immediates[3130] = new Pattern(new long[]{0L, 0L, 8388864L, 0x200040008000000L},
                    new long[]{0L, 0L, 8388864L, 0x200000008000000L}, 213);
            immediates[3131] = new Pattern(new long[]{0L, 0L, 128L, 72059793128294400L},
                    new long[]{0L, 0L, 128L, 72059793061185536L}, 229);
            immediates[3132] = new Pattern(new long[]{0x40008001000200L, 0x400000000000000L, 0L, 0L},
                    new long[]{0x40008001000000L, 0x400000000000000L, 0L, 0L}, 54);
            immediates[3133] = new Pattern(new long[]{274886295808L, 0x200040000000000L, 0L, 0L},
                    new long[]{274886295808L, 4398046511104L, 0L, 0L}, 70);
            immediates[3134] = new Pattern(new long[]{4194432L, 72059793128292352L, 0L, 0L},
                    new long[]{4194432L, 72057594105036800L, 0L, 0L}, 86);
            immediates[3135] =
                    new Pattern(new long[]{64L, 0x80010002000400L, 0L, 0L}, new long[]{64L, 0x80010000000400L, 0L, 0L},
                            102);
            immediates[3136] = new Pattern(new long[]{0L, 0x40008001000200L, 0x400000000000000L, 0L},
                    new long[]{0L, 0x40008001000000L, 0x400000000000000L, 0L}, 118);
            immediates[3137] = new Pattern(new long[]{0L, 274886295808L, 0x200040000000000L, 0L},
                    new long[]{0L, 274886295808L, 4398046511104L, 0L}, 134);
            immediates[3138] = new Pattern(new long[]{0L, 4194432L, 72059793128292352L, 0L},
                    new long[]{0L, 4194432L, 72057594105036800L, 0L}, 150);
            immediates[3139] =
                    new Pattern(new long[]{0L, 64L, 0x80010002000400L, 0L}, new long[]{0L, 64L, 0x80010000000400L, 0L},
                            166);
            immediates[3140] = new Pattern(new long[]{0L, 0L, 0x40008001000200L, 0x400000000000000L},
                    new long[]{0L, 0L, 0x40008001000000L, 0x400000000000000L}, 182);
            immediates[3141] = new Pattern(new long[]{0L, 0L, 274886295808L, 0x200040000000000L},
                    new long[]{0L, 0L, 274886295808L, 4398046511104L}, 198);
            immediates[3142] = new Pattern(new long[]{0L, 0L, 4194432L, 72059793128292352L},
                    new long[]{0L, 0L, 4194432L, 72057594105036800L}, 214);
            immediates[3143] =
                    new Pattern(new long[]{0L, 0L, 64L, 0x80010002000400L}, new long[]{0L, 0L, 64L, 0x80010000000400L},
                            230);
            immediates[3144] = new Pattern(new long[]{9007474141036800L, 0x200000000000000L, 0L, 0L},
                    new long[]{9007474141036544L, 0x200000000000000L, 0L, 0L}, 55);
            immediates[3145] = new Pattern(new long[]{137443147904L, 72059793061183488L, 0L, 0L},
                    new long[]{137443147904L, 2199023255552L, 0L, 0L}, 71);
            immediates[3146] = new Pattern(new long[]{2097216L, 0x80010002000000L, 0L, 0L},
                    new long[]{2097216L, 0x80000002000000L, 0L, 0L}, 87);
            immediates[3147] =
                    new Pattern(new long[]{32L, 0x40008001000200L, 0L, 0L}, new long[]{32L, 0x40008000000200L, 0L, 0L},
                            103);
            immediates[3148] = new Pattern(new long[]{0L, 9007474141036800L, 0x200000000000000L, 0L},
                    new long[]{0L, 9007474141036544L, 0x200000000000000L, 0L}, 119);
            immediates[3149] = new Pattern(new long[]{0L, 137443147904L, 72059793061183488L, 0L},
                    new long[]{0L, 137443147904L, 2199023255552L, 0L}, 135);
            immediates[3150] = new Pattern(new long[]{0L, 2097216L, 0x80010002000000L, 0L},
                    new long[]{0L, 2097216L, 0x80000002000000L, 0L}, 151);
            immediates[3151] =
                    new Pattern(new long[]{0L, 32L, 0x40008001000200L, 0L}, new long[]{0L, 32L, 0x40008000000200L, 0L},
                            167);
            immediates[3152] = new Pattern(new long[]{0L, 0L, 9007474141036800L, 0x200000000000000L},
                    new long[]{0L, 0L, 9007474141036544L, 0x200000000000000L}, 183);
            immediates[3153] = new Pattern(new long[]{0L, 0L, 137443147904L, 72059793061183488L},
                    new long[]{0L, 0L, 137443147904L, 2199023255552L}, 199);
            immediates[3154] = new Pattern(new long[]{0L, 0L, 2097216L, 0x80010002000000L},
                    new long[]{0L, 0L, 2097216L, 0x80000002000000L}, 215);
            immediates[3155] =
                    new Pattern(new long[]{0L, 0L, 32L, 0x40008001000200L}, new long[]{0L, 0L, 32L, 0x40008000000200L},
                            231);
            immediates[3156] = new Pattern(new long[]{4503737070518400L, 72057594037927936L, 0L, 0L},
                    new long[]{4503737070518272L, 72057594037927936L, 0L, 0L}, 56);
            immediates[3157] = new Pattern(new long[]{68721573952L, 0x80010000000000L, 0L, 0L},
                    new long[]{68721573952L, 1099511627776L, 0L, 0L}, 72);
            immediates[3158] = new Pattern(new long[]{1048608L, 0x40008001000000L, 0L, 0L},
                    new long[]{1048608L, 0x40000001000000L, 0L, 0L}, 88);
            immediates[3159] =
                    new Pattern(new long[]{16L, 9007474141036800L, 0L, 0L}, new long[]{16L, 9007474132648192L, 0L, 0L},
                            104);
            immediates[3160] = new Pattern(new long[]{0L, 4503737070518400L, 72057594037927936L, 0L},
                    new long[]{0L, 4503737070518272L, 72057594037927936L, 0L}, 120);
            immediates[3161] = new Pattern(new long[]{0L, 68721573952L, 0x80010000000000L, 0L},
                    new long[]{0L, 68721573952L, 1099511627776L, 0L}, 136);
            immediates[3162] = new Pattern(new long[]{0L, 1048608L, 0x40008001000000L, 0L},
                    new long[]{0L, 1048608L, 0x40000001000000L, 0L}, 152);
            immediates[3163] =
                    new Pattern(new long[]{0L, 16L, 9007474141036800L, 0L}, new long[]{0L, 16L, 9007474132648192L, 0L},
                            168);
            immediates[3164] = new Pattern(new long[]{0L, 0L, 4503737070518400L, 72057594037927936L},
                    new long[]{0L, 0L, 4503737070518272L, 72057594037927936L}, 184);
            immediates[3165] = new Pattern(new long[]{0L, 0L, 68721573952L, 0x80010000000000L},
                    new long[]{0L, 0L, 68721573952L, 1099511627776L}, 200);
            immediates[3166] = new Pattern(new long[]{0L, 0L, 1048608L, 0x40008001000000L},
                    new long[]{0L, 0L, 1048608L, 0x40000001000000L}, 216);
            immediates[3167] =
                    new Pattern(new long[]{0L, 0L, 16L, 9007474141036800L}, new long[]{0L, 0L, 16L, 9007474132648192L},
                            232);
            immediates[3168] = new Pattern(new long[]{0x8001000200040L, 0x80000000000000L, 0L, 0L},
                    new long[]{0x8001000200000L, 0x80000000000000L, 0L, 0L}, 57);
            immediates[3169] = new Pattern(new long[]{34360786976L, 0x40008000000000L, 0L, 0L},
                    new long[]{34360786976L, 549755813888L, 0L, 0L}, 73);
            immediates[3170] = new Pattern(new long[]{524304L, 9007474141036544L, 0L, 0L},
                    new long[]{524304L, 9007199263129600L, 0L, 0L}, 89);
            immediates[3171] =
                    new Pattern(new long[]{8L, 4503737070518400L, 0L, 0L}, new long[]{8L, 4503737066324096L, 0L, 0L},
                            105);
            immediates[3172] = new Pattern(new long[]{0L, 0x8001000200040L, 0x80000000000000L, 0L},
                    new long[]{0L, 0x8001000200000L, 0x80000000000000L, 0L}, 121);
            immediates[3173] = new Pattern(new long[]{0L, 34360786976L, 0x40008000000000L, 0L},
                    new long[]{0L, 34360786976L, 549755813888L, 0L}, 137);
            immediates[3174] = new Pattern(new long[]{0L, 524304L, 9007474141036544L, 0L},
                    new long[]{0L, 524304L, 9007199263129600L, 0L}, 153);
            immediates[3175] =
                    new Pattern(new long[]{0L, 8L, 4503737070518400L, 0L}, new long[]{0L, 8L, 4503737066324096L, 0L},
                            169);
            immediates[3176] = new Pattern(new long[]{0L, 0L, 0x8001000200040L, 0x80000000000000L},
                    new long[]{0L, 0L, 0x8001000200000L, 0x80000000000000L}, 185);
            immediates[3177] = new Pattern(new long[]{0L, 0L, 34360786976L, 0x40008000000000L},
                    new long[]{0L, 0L, 34360786976L, 549755813888L}, 201);
            immediates[3178] = new Pattern(new long[]{0L, 0L, 524304L, 9007474141036544L},
                    new long[]{0L, 0L, 524304L, 9007199263129600L}, 217);
            immediates[3179] =
                    new Pattern(new long[]{0L, 0L, 8L, 4503737070518400L}, new long[]{0L, 0L, 8L, 4503737066324096L},
                            233);
            immediates[3180] = new Pattern(new long[]{0x4000800100020L, 0x40000000000000L, 0L, 0L},
                    new long[]{0x4000800100000L, 0x40000000000000L, 0L, 0L}, 58);
            immediates[3181] = new Pattern(new long[]{17180393488L, 9007474132647936L, 0L, 0L},
                    new long[]{17180393488L, 274877906944L, 0L, 0L}, 74);
            immediates[3182] = new Pattern(new long[]{262152L, 4503737070518272L, 0L, 0L},
                    new long[]{262152L, 4503599631564800L, 0L, 0L}, 90);
            immediates[3183] =
                    new Pattern(new long[]{4L, 0x8001000200040L, 0L, 0L}, new long[]{4L, 0x8001000000040L, 0L, 0L},
                            106);
            immediates[3184] = new Pattern(new long[]{0L, 0x4000800100020L, 0x40000000000000L, 0L},
                    new long[]{0L, 0x4000800100000L, 0x40000000000000L, 0L}, 122);
            immediates[3185] = new Pattern(new long[]{0L, 17180393488L, 9007474132647936L, 0L},
                    new long[]{0L, 17180393488L, 274877906944L, 0L}, 138);
            immediates[3186] = new Pattern(new long[]{0L, 262152L, 4503737070518272L, 0L},
                    new long[]{0L, 262152L, 4503599631564800L, 0L}, 154);
            immediates[3187] =
                    new Pattern(new long[]{0L, 4L, 0x8001000200040L, 0L}, new long[]{0L, 4L, 0x8001000000040L, 0L},
                            170);
            immediates[3188] = new Pattern(new long[]{0L, 0L, 0x4000800100020L, 0x40000000000000L},
                    new long[]{0L, 0L, 0x4000800100000L, 0x40000000000000L}, 186);
            immediates[3189] = new Pattern(new long[]{0L, 0L, 17180393488L, 9007474132647936L},
                    new long[]{0L, 0L, 17180393488L, 274877906944L}, 202);
            immediates[3190] = new Pattern(new long[]{0L, 0L, 262152L, 4503737070518272L},
                    new long[]{0L, 0L, 262152L, 4503599631564800L}, 218);
            immediates[3191] =
                    new Pattern(new long[]{0L, 0L, 4L, 0x8001000200040L}, new long[]{0L, 0L, 4L, 0x8001000000040L},
                            234);
            immediates[3192] = new Pattern(new long[]{562967133814800L, 9007199254740992L, 0L, 0L},
                    new long[]{562967133814784L, 9007199254740992L, 0L, 0L}, 59);
            immediates[3193] = new Pattern(new long[]{8590196744L, 4503737066323968L, 0L, 0L},
                    new long[]{8590196744L, 137438953472L, 0L, 0L}, 75);
            immediates[3194] = new Pattern(new long[]{131076L, 0x8001000200000L, 0L, 0L},
                    new long[]{131076L, 0x8000000200000L, 0L, 0L}, 91);
            immediates[3195] =
                    new Pattern(new long[]{2L, 0x4000800100020L, 0L, 0L}, new long[]{2L, 0x4000800000020L, 0L, 0L},
                            107);
            immediates[3196] = new Pattern(new long[]{0L, 562967133814800L, 9007199254740992L, 0L},
                    new long[]{0L, 562967133814784L, 9007199254740992L, 0L}, 123);
            immediates[3197] = new Pattern(new long[]{0L, 8590196744L, 4503737066323968L, 0L},
                    new long[]{0L, 8590196744L, 137438953472L, 0L}, 139);
            immediates[3198] = new Pattern(new long[]{0L, 131076L, 0x8001000200000L, 0L},
                    new long[]{0L, 131076L, 0x8000000200000L, 0L}, 155);
            immediates[3199] =
                    new Pattern(new long[]{0L, 2L, 0x4000800100020L, 0L}, new long[]{0L, 2L, 0x4000800000020L, 0L},
                            171);
            immediates[3200] = new Pattern(new long[]{0L, 0L, 562967133814800L, 9007199254740992L},
                    new long[]{0L, 0L, 562967133814784L, 9007199254740992L}, 187);
            immediates[3201] = new Pattern(new long[]{0L, 0L, 8590196744L, 4503737066323968L},
                    new long[]{0L, 0L, 8590196744L, 137438953472L}, 203);
            immediates[3202] = new Pattern(new long[]{0L, 0L, 131076L, 0x8001000200000L},
                    new long[]{0L, 0L, 131076L, 0x8000000200000L}, 219);
            immediates[3203] =
                    new Pattern(new long[]{0L, 0L, 2L, 0x4000800100020L}, new long[]{0L, 0L, 2L, 0x4000800000020L},
                            235);
            immediates[3204] = new Pattern(new long[]{281483566907400L, 4503599627370496L, 0L, 0L},
                    new long[]{281483566907392L, 4503599627370496L, 0L, 0L}, 60);
            immediates[3205] = new Pattern(new long[]{4295098372L, 0x8001000000000L, 0L, 0L},
                    new long[]{4295098372L, 68719476736L, 0L, 0L}, 76);
            immediates[3206] = new Pattern(new long[]{65538L, 0x4000800100000L, 0L, 0L},
                    new long[]{65538L, 0x4000000100000L, 0L, 0L}, 92);
            immediates[3207] =
                    new Pattern(new long[]{1L, 562967133814800L, 0L, 0L}, new long[]{1L, 562967133290512L, 0L, 0L},
                            108);
            immediates[3208] = new Pattern(new long[]{0L, 281483566907400L, 4503599627370496L, 0L},
                    new long[]{0L, 281483566907392L, 4503599627370496L, 0L}, 124);
            immediates[3209] = new Pattern(new long[]{0L, 4295098372L, 0x8001000000000L, 0L},
                    new long[]{0L, 4295098372L, 68719476736L, 0L}, 140);
            immediates[3210] = new Pattern(new long[]{0L, 65538L, 0x4000800100000L, 0L},
                    new long[]{0L, 65538L, 0x4000000100000L, 0L}, 156);
            immediates[3211] =
                    new Pattern(new long[]{0L, 1L, 562967133814800L, 0L}, new long[]{0L, 1L, 562967133290512L, 0L},
                            172);
            immediates[3212] = new Pattern(new long[]{0L, 0L, 281483566907400L, 4503599627370496L},
                    new long[]{0L, 0L, 281483566907392L, 4503599627370496L}, 188);
            immediates[3213] = new Pattern(new long[]{0L, 0L, 4295098372L, 0x8001000000000L},
                    new long[]{0L, 0L, 4295098372L, 68719476736L}, 204);
            immediates[3214] = new Pattern(new long[]{0L, 0L, 65538L, 0x4000800100000L},
                    new long[]{0L, 0L, 65538L, 0x4000000100000L}, 220);
            immediates[3215] =
                    new Pattern(new long[]{0L, 0L, 1L, 562967133814800L}, new long[]{0L, 0L, 1L, 562967133290512L},
                            236);
            immediates[3216] = new Pattern(new long[]{0x800100020004000L, 0x8000000000000000L, 0L, 0L},
                    new long[]{0x800100020004000L, 0L, 0L, 0L}, 64);
            immediates[3217] = new Pattern(new long[]{8796361465856L, 0x4000800000000000L, 0L, 0L},
                    new long[]{8796361465856L, 0x4000000000000000L, 0L, 0L}, 80);
            immediates[3218] = new Pattern(new long[]{134221824L, 0x2000400080000000L, 0L, 0L},
                    new long[]{134221824L, 0x2000400000000000L, 0L, 0L}, 96);
            immediates[3219] = new Pattern(new long[]{2048L, 0x1000200040008000L, 0L, 0L},
                    new long[]{2048L, 0x1000200040000000L, 0L, 0L}, 112);
            immediates[3220] = new Pattern(new long[]{0L, 0x800100020004000L, 0x8000000000000000L, 0L},
                    new long[]{0L, 0x800100020004000L, 0L, 0L}, 128);
            immediates[3221] = new Pattern(new long[]{0L, 8796361465856L, 0x4000800000000000L, 0L},
                    new long[]{0L, 8796361465856L, 0x4000000000000000L, 0L}, 144);
            immediates[3222] = new Pattern(new long[]{0L, 134221824L, 0x2000400080000000L, 0L},
                    new long[]{0L, 134221824L, 0x2000400000000000L, 0L}, 160);
            immediates[3223] = new Pattern(new long[]{0L, 2048L, 0x1000200040008000L, 0L},
                    new long[]{0L, 2048L, 0x1000200040000000L, 0L}, 176);
            immediates[3224] = new Pattern(new long[]{0L, 0L, 0x800100020004000L, 0x8000000000000000L},
                    new long[]{0L, 0L, 0x800100020004000L, 0L}, 192);
            immediates[3225] = new Pattern(new long[]{0L, 0L, 8796361465856L, 0x4000800000000000L},
                    new long[]{0L, 0L, 8796361465856L, 0x4000000000000000L}, 208);
            immediates[3226] = new Pattern(new long[]{0L, 0L, 134221824L, 0x2000400080000000L},
                    new long[]{0L, 0L, 134221824L, 0x2000400000000000L}, 224);
            immediates[3227] = new Pattern(new long[]{0L, 0L, 2048L, 0x1000200040008000L},
                    new long[]{0L, 0L, 2048L, 0x1000200040000000L}, 240);
            immediates[3228] = new Pattern(new long[]{0x400080010002000L, 0x4000000000000000L, 0L, 0L},
                    new long[]{0x400080010002000L, 0L, 0L, 0L}, 65);
            immediates[3229] = new Pattern(new long[]{4398180732928L, 0x2000400000000000L, 0L, 0L},
                    new long[]{4398180732928L, 0x2000000000000000L, 0L, 0L}, 81);
            immediates[3230] = new Pattern(new long[]{67110912L, 0x1000200040000000L, 0L, 0L},
                    new long[]{67110912L, 0x1000200000000000L, 0L, 0L}, 97);
            immediates[3231] = new Pattern(new long[]{1024L, 0x800100020004000L, 0L, 0L},
                    new long[]{1024L, 0x800100020000000L, 0L, 0L}, 113);
            immediates[3232] = new Pattern(new long[]{0L, 0x400080010002000L, 0x4000000000000000L, 0L},
                    new long[]{0L, 0x400080010002000L, 0L, 0L}, 129);
            immediates[3233] = new Pattern(new long[]{0L, 4398180732928L, 0x2000400000000000L, 0L},
                    new long[]{0L, 4398180732928L, 0x2000000000000000L, 0L}, 145);
            immediates[3234] = new Pattern(new long[]{0L, 67110912L, 0x1000200040000000L, 0L},
                    new long[]{0L, 67110912L, 0x1000200000000000L, 0L}, 161);
            immediates[3235] = new Pattern(new long[]{0L, 1024L, 0x800100020004000L, 0L},
                    new long[]{0L, 1024L, 0x800100020000000L, 0L}, 177);
            immediates[3236] = new Pattern(new long[]{0L, 0L, 0x400080010002000L, 0x4000000000000000L},
                    new long[]{0L, 0L, 0x400080010002000L, 0L}, 193);
            immediates[3237] = new Pattern(new long[]{0L, 0L, 4398180732928L, 0x2000400000000000L},
                    new long[]{0L, 0L, 4398180732928L, 0x2000000000000000L}, 209);
            immediates[3238] = new Pattern(new long[]{0L, 0L, 67110912L, 0x1000200040000000L},
                    new long[]{0L, 0L, 67110912L, 0x1000200000000000L}, 225);
            immediates[3239] = new Pattern(new long[]{0L, 0L, 1024L, 0x800100020004000L},
                    new long[]{0L, 0L, 1024L, 0x800100020000000L}, 241);
            immediates[3240] = new Pattern(new long[]{0x200040008001000L, 0x2000000000000000L, 0L, 0L},
                    new long[]{0x200040008001000L, 0L, 0L, 0L}, 66);
            immediates[3241] = new Pattern(new long[]{2199090366464L, 0x1000200000000000L, 0L, 0L},
                    new long[]{2199090366464L, 0x1000000000000000L, 0L, 0L}, 82);
            immediates[3242] = new Pattern(new long[]{33555456L, 0x800100020000000L, 0L, 0L},
                    new long[]{33555456L, 0x800100000000000L, 0L, 0L}, 98);
            immediates[3243] = new Pattern(new long[]{512L, 0x400080010002000L, 0L, 0L},
                    new long[]{512L, 0x400080010000000L, 0L, 0L}, 114);
            immediates[3244] = new Pattern(new long[]{0L, 0x200040008001000L, 0x2000000000000000L, 0L},
                    new long[]{0L, 0x200040008001000L, 0L, 0L}, 130);
            immediates[3245] = new Pattern(new long[]{0L, 2199090366464L, 0x1000200000000000L, 0L},
                    new long[]{0L, 2199090366464L, 0x1000000000000000L, 0L}, 146);
            immediates[3246] = new Pattern(new long[]{0L, 33555456L, 0x800100020000000L, 0L},
                    new long[]{0L, 33555456L, 0x800100000000000L, 0L}, 162);
            immediates[3247] = new Pattern(new long[]{0L, 512L, 0x400080010002000L, 0L},
                    new long[]{0L, 512L, 0x400080010000000L, 0L}, 178);
            immediates[3248] = new Pattern(new long[]{0L, 0L, 0x200040008001000L, 0x2000000000000000L},
                    new long[]{0L, 0L, 0x200040008001000L, 0L}, 194);
            immediates[3249] = new Pattern(new long[]{0L, 0L, 2199090366464L, 0x1000200000000000L},
                    new long[]{0L, 0L, 2199090366464L, 0x1000000000000000L}, 210);
            immediates[3250] = new Pattern(new long[]{0L, 0L, 33555456L, 0x800100020000000L},
                    new long[]{0L, 0L, 33555456L, 0x800100000000000L}, 226);
            immediates[3251] = new Pattern(new long[]{0L, 0L, 512L, 0x400080010002000L},
                    new long[]{0L, 0L, 512L, 0x400080010000000L}, 242);
            immediates[3252] = new Pattern(new long[]{72059793128294400L, 0x1000000000000000L, 0L, 0L},
                    new long[]{72059793128294400L, 0L, 0L, 0L}, 67);
            immediates[3253] = new Pattern(new long[]{1099545183232L, 0x800100000000000L, 0L, 0L},
                    new long[]{1099545183232L, 0x800000000000000L, 0L, 0L}, 83);
            immediates[3254] = new Pattern(new long[]{16777728L, 0x400080010000000L, 0L, 0L},
                    new long[]{16777728L, 0x400080000000000L, 0L, 0L}, 99);
            immediates[3255] = new Pattern(new long[]{256L, 0x200040008001000L, 0L, 0L},
                    new long[]{256L, 0x200040008000000L, 0L, 0L}, 115);
            immediates[3256] = new Pattern(new long[]{0L, 72059793128294400L, 0x1000000000000000L, 0L},
                    new long[]{0L, 72059793128294400L, 0L, 0L}, 131);
            immediates[3257] = new Pattern(new long[]{0L, 1099545183232L, 0x800100000000000L, 0L},
                    new long[]{0L, 1099545183232L, 0x800000000000000L, 0L}, 147);
            immediates[3258] = new Pattern(new long[]{0L, 16777728L, 0x400080010000000L, 0L},
                    new long[]{0L, 16777728L, 0x400080000000000L, 0L}, 163);
            immediates[3259] = new Pattern(new long[]{0L, 256L, 0x200040008001000L, 0L},
                    new long[]{0L, 256L, 0x200040008000000L, 0L}, 179);
            immediates[3260] = new Pattern(new long[]{0L, 0L, 72059793128294400L, 0x1000000000000000L},
                    new long[]{0L, 0L, 72059793128294400L, 0L}, 195);
            immediates[3261] = new Pattern(new long[]{0L, 0L, 1099545183232L, 0x800100000000000L},
                    new long[]{0L, 0L, 1099545183232L, 0x800000000000000L}, 211);
            immediates[3262] = new Pattern(new long[]{0L, 0L, 16777728L, 0x400080010000000L},
                    new long[]{0L, 0L, 16777728L, 0x400080000000000L}, 227);
            immediates[3263] = new Pattern(new long[]{0L, 0L, 256L, 0x200040008001000L},
                    new long[]{0L, 0L, 256L, 0x200040008000000L}, 243);
            immediates[3264] = new Pattern(new long[]{0x80010002000400L, 0x800000000000000L, 0L, 0L},
                    new long[]{0x80010002000400L, 0L, 0L, 0L}, 68);
            immediates[3265] = new Pattern(new long[]{549772591616L, 0x400080000000000L, 0L, 0L},
                    new long[]{549772591616L, 0x400000000000000L, 0L, 0L}, 84);
            immediates[3266] = new Pattern(new long[]{8388864L, 0x200040008000000L, 0L, 0L},
                    new long[]{8388864L, 0x200040000000000L, 0L, 0L}, 100);
            immediates[3267] = new Pattern(new long[]{128L, 72059793128294400L, 0L, 0L},
                    new long[]{128L, 72059793128292352L, 0L, 0L}, 116);
            immediates[3268] = new Pattern(new long[]{0L, 0x80010002000400L, 0x800000000000000L, 0L},
                    new long[]{0L, 0x80010002000400L, 0L, 0L}, 132);
            immediates[3269] = new Pattern(new long[]{0L, 549772591616L, 0x400080000000000L, 0L},
                    new long[]{0L, 549772591616L, 0x400000000000000L, 0L}, 148);
            immediates[3270] = new Pattern(new long[]{0L, 8388864L, 0x200040008000000L, 0L},
                    new long[]{0L, 8388864L, 0x200040000000000L, 0L}, 164);
            immediates[3271] = new Pattern(new long[]{0L, 128L, 72059793128294400L, 0L},
                    new long[]{0L, 128L, 72059793128292352L, 0L}, 180);
            immediates[3272] = new Pattern(new long[]{0L, 0L, 0x80010002000400L, 0x800000000000000L},
                    new long[]{0L, 0L, 0x80010002000400L, 0L}, 196);
            immediates[3273] = new Pattern(new long[]{0L, 0L, 549772591616L, 0x400080000000000L},
                    new long[]{0L, 0L, 549772591616L, 0x400000000000000L}, 212);
            immediates[3274] = new Pattern(new long[]{0L, 0L, 8388864L, 0x200040008000000L},
                    new long[]{0L, 0L, 8388864L, 0x200040000000000L}, 228);
            immediates[3275] = new Pattern(new long[]{0L, 0L, 128L, 72059793128294400L},
                    new long[]{0L, 0L, 128L, 72059793128292352L}, 244);
            immediates[3276] = new Pattern(new long[]{0x40008001000200L, 0x400000000000000L, 0L, 0L},
                    new long[]{0x40008001000200L, 0L, 0L, 0L}, 69);
            immediates[3277] = new Pattern(new long[]{274886295808L, 0x200040000000000L, 0L, 0L},
                    new long[]{274886295808L, 0x200000000000000L, 0L, 0L}, 85);
            immediates[3278] = new Pattern(new long[]{4194432L, 72059793128292352L, 0L, 0L},
                    new long[]{4194432L, 72059793061183488L, 0L, 0L}, 101);
            immediates[3279] =
                    new Pattern(new long[]{64L, 0x80010002000400L, 0L, 0L}, new long[]{64L, 0x80010002000000L, 0L, 0L},
                            117);
            immediates[3280] = new Pattern(new long[]{0L, 0x40008001000200L, 0x400000000000000L, 0L},
                    new long[]{0L, 0x40008001000200L, 0L, 0L}, 133);
            immediates[3281] = new Pattern(new long[]{0L, 274886295808L, 0x200040000000000L, 0L},
                    new long[]{0L, 274886295808L, 0x200000000000000L, 0L}, 149);
            immediates[3282] = new Pattern(new long[]{0L, 4194432L, 72059793128292352L, 0L},
                    new long[]{0L, 4194432L, 72059793061183488L, 0L}, 165);
            immediates[3283] =
                    new Pattern(new long[]{0L, 64L, 0x80010002000400L, 0L}, new long[]{0L, 64L, 0x80010002000000L, 0L},
                            181);
            immediates[3284] = new Pattern(new long[]{0L, 0L, 0x40008001000200L, 0x400000000000000L},
                    new long[]{0L, 0L, 0x40008001000200L, 0L}, 197);
            immediates[3285] = new Pattern(new long[]{0L, 0L, 274886295808L, 0x200040000000000L},
                    new long[]{0L, 0L, 274886295808L, 0x200000000000000L}, 213);
            immediates[3286] = new Pattern(new long[]{0L, 0L, 4194432L, 72059793128292352L},
                    new long[]{0L, 0L, 4194432L, 72059793061183488L}, 229);
            immediates[3287] =
                    new Pattern(new long[]{0L, 0L, 64L, 0x80010002000400L}, new long[]{0L, 0L, 64L, 0x80010002000000L},
                            245);
            immediates[3288] = new Pattern(new long[]{9007474141036800L, 0x200000000000000L, 0L, 0L},
                    new long[]{9007474141036800L, 0L, 0L, 0L}, 70);
            immediates[3289] = new Pattern(new long[]{137443147904L, 72059793061183488L, 0L, 0L},
                    new long[]{137443147904L, 72057594037927936L, 0L, 0L}, 86);
            immediates[3290] = new Pattern(new long[]{2097216L, 0x80010002000000L, 0L, 0L},
                    new long[]{2097216L, 0x80010000000000L, 0L, 0L}, 102);
            immediates[3291] =
                    new Pattern(new long[]{32L, 0x40008001000200L, 0L, 0L}, new long[]{32L, 0x40008001000000L, 0L, 0L},
                            118);
            immediates[3292] = new Pattern(new long[]{0L, 9007474141036800L, 0x200000000000000L, 0L},
                    new long[]{0L, 9007474141036800L, 0L, 0L}, 134);
            immediates[3293] = new Pattern(new long[]{0L, 137443147904L, 72059793061183488L, 0L},
                    new long[]{0L, 137443147904L, 72057594037927936L, 0L}, 150);
            immediates[3294] = new Pattern(new long[]{0L, 2097216L, 0x80010002000000L, 0L},
                    new long[]{0L, 2097216L, 0x80010000000000L, 0L}, 166);
            immediates[3295] =
                    new Pattern(new long[]{0L, 32L, 0x40008001000200L, 0L}, new long[]{0L, 32L, 0x40008001000000L, 0L},
                            182);
            immediates[3296] = new Pattern(new long[]{0L, 0L, 9007474141036800L, 0x200000000000000L},
                    new long[]{0L, 0L, 9007474141036800L, 0L}, 198);
            immediates[3297] = new Pattern(new long[]{0L, 0L, 137443147904L, 72059793061183488L},
                    new long[]{0L, 0L, 137443147904L, 72057594037927936L}, 214);
            immediates[3298] = new Pattern(new long[]{0L, 0L, 2097216L, 0x80010002000000L},
                    new long[]{0L, 0L, 2097216L, 0x80010000000000L}, 230);
            immediates[3299] =
                    new Pattern(new long[]{0L, 0L, 32L, 0x40008001000200L}, new long[]{0L, 0L, 32L, 0x40008001000000L},
                            246);
            immediates[3300] = new Pattern(new long[]{4503737070518400L, 72057594037927936L, 0L, 0L},
                    new long[]{4503737070518400L, 0L, 0L, 0L}, 71);
            immediates[3301] = new Pattern(new long[]{68721573952L, 0x80010000000000L, 0L, 0L},
                    new long[]{68721573952L, 0x80000000000000L, 0L, 0L}, 87);
            immediates[3302] = new Pattern(new long[]{1048608L, 0x40008001000000L, 0L, 0L},
                    new long[]{1048608L, 0x40008000000000L, 0L, 0L}, 103);
            immediates[3303] =
                    new Pattern(new long[]{16L, 9007474141036800L, 0L, 0L}, new long[]{16L, 9007474141036544L, 0L, 0L},
                            119);
            immediates[3304] = new Pattern(new long[]{0L, 4503737070518400L, 72057594037927936L, 0L},
                    new long[]{0L, 4503737070518400L, 0L, 0L}, 135);
            immediates[3305] = new Pattern(new long[]{0L, 68721573952L, 0x80010000000000L, 0L},
                    new long[]{0L, 68721573952L, 0x80000000000000L, 0L}, 151);
            immediates[3306] = new Pattern(new long[]{0L, 1048608L, 0x40008001000000L, 0L},
                    new long[]{0L, 1048608L, 0x40008000000000L, 0L}, 167);
            immediates[3307] =
                    new Pattern(new long[]{0L, 16L, 9007474141036800L, 0L}, new long[]{0L, 16L, 9007474141036544L, 0L},
                            183);
            immediates[3308] = new Pattern(new long[]{0L, 0L, 4503737070518400L, 72057594037927936L},
                    new long[]{0L, 0L, 4503737070518400L, 0L}, 199);
            immediates[3309] = new Pattern(new long[]{0L, 0L, 68721573952L, 0x80010000000000L},
                    new long[]{0L, 0L, 68721573952L, 0x80000000000000L}, 215);
            immediates[3310] = new Pattern(new long[]{0L, 0L, 1048608L, 0x40008001000000L},
                    new long[]{0L, 0L, 1048608L, 0x40008000000000L}, 231);
            immediates[3311] =
                    new Pattern(new long[]{0L, 0L, 16L, 9007474141036800L}, new long[]{0L, 0L, 16L, 9007474141036544L},
                            247);
            immediates[3312] = new Pattern(new long[]{0x8001000200040L, 0x80000000000000L, 0L, 0L},
                    new long[]{0x8001000200040L, 0L, 0L, 0L}, 72);
            immediates[3313] = new Pattern(new long[]{34360786976L, 0x40008000000000L, 0L, 0L},
                    new long[]{34360786976L, 0x40000000000000L, 0L, 0L}, 88);
            immediates[3314] = new Pattern(new long[]{524304L, 9007474141036544L, 0L, 0L},
                    new long[]{524304L, 9007474132647936L, 0L, 0L}, 104);
            immediates[3315] =
                    new Pattern(new long[]{8L, 4503737070518400L, 0L, 0L}, new long[]{8L, 4503737070518272L, 0L, 0L},
                            120);
            immediates[3316] = new Pattern(new long[]{0L, 0x8001000200040L, 0x80000000000000L, 0L},
                    new long[]{0L, 0x8001000200040L, 0L, 0L}, 136);
            immediates[3317] = new Pattern(new long[]{0L, 34360786976L, 0x40008000000000L, 0L},
                    new long[]{0L, 34360786976L, 0x40000000000000L, 0L}, 152);
            immediates[3318] = new Pattern(new long[]{0L, 524304L, 9007474141036544L, 0L},
                    new long[]{0L, 524304L, 9007474132647936L, 0L}, 168);
            immediates[3319] =
                    new Pattern(new long[]{0L, 8L, 4503737070518400L, 0L}, new long[]{0L, 8L, 4503737070518272L, 0L},
                            184);
            immediates[3320] = new Pattern(new long[]{0L, 0L, 0x8001000200040L, 0x80000000000000L},
                    new long[]{0L, 0L, 0x8001000200040L, 0L}, 200);
            immediates[3321] = new Pattern(new long[]{0L, 0L, 34360786976L, 0x40008000000000L},
                    new long[]{0L, 0L, 34360786976L, 0x40000000000000L}, 216);
            immediates[3322] = new Pattern(new long[]{0L, 0L, 524304L, 9007474141036544L},
                    new long[]{0L, 0L, 524304L, 9007474132647936L}, 232);
            immediates[3323] =
                    new Pattern(new long[]{0L, 0L, 8L, 4503737070518400L}, new long[]{0L, 0L, 8L, 4503737070518272L},
                            248);
            immediates[3324] = new Pattern(new long[]{0x4000800100020L, 0x40000000000000L, 0L, 0L},
                    new long[]{0x4000800100020L, 0L, 0L, 0L}, 73);
            immediates[3325] = new Pattern(new long[]{17180393488L, 9007474132647936L, 0L, 0L},
                    new long[]{17180393488L, 9007199254740992L, 0L, 0L}, 89);
            immediates[3326] = new Pattern(new long[]{262152L, 4503737070518272L, 0L, 0L},
                    new long[]{262152L, 4503737066323968L, 0L, 0L}, 105);
            immediates[3327] =
                    new Pattern(new long[]{4L, 0x8001000200040L, 0L, 0L}, new long[]{4L, 0x8001000200000L, 0L, 0L},
                            121);
            immediates[3328] = new Pattern(new long[]{0L, 0x4000800100020L, 0x40000000000000L, 0L},
                    new long[]{0L, 0x4000800100020L, 0L, 0L}, 137);
            immediates[3329] = new Pattern(new long[]{0L, 17180393488L, 9007474132647936L, 0L},
                    new long[]{0L, 17180393488L, 9007199254740992L, 0L}, 153);
            immediates[3330] = new Pattern(new long[]{0L, 262152L, 4503737070518272L, 0L},
                    new long[]{0L, 262152L, 4503737066323968L, 0L}, 169);
            immediates[3331] =
                    new Pattern(new long[]{0L, 4L, 0x8001000200040L, 0L}, new long[]{0L, 4L, 0x8001000200000L, 0L},
                            185);
            immediates[3332] = new Pattern(new long[]{0L, 0L, 0x4000800100020L, 0x40000000000000L},
                    new long[]{0L, 0L, 0x4000800100020L, 0L}, 201);
            immediates[3333] = new Pattern(new long[]{0L, 0L, 17180393488L, 9007474132647936L},
                    new long[]{0L, 0L, 17180393488L, 9007199254740992L}, 217);
            immediates[3334] = new Pattern(new long[]{0L, 0L, 262152L, 4503737070518272L},
                    new long[]{0L, 0L, 262152L, 4503737066323968L}, 233);
            immediates[3335] =
                    new Pattern(new long[]{0L, 0L, 4L, 0x8001000200040L}, new long[]{0L, 0L, 4L, 0x8001000200000L},
                            249);
            immediates[3336] = new Pattern(new long[]{562967133814800L, 9007199254740992L, 0L, 0L},
                    new long[]{562967133814800L, 0L, 0L, 0L}, 74);
            immediates[3337] = new Pattern(new long[]{8590196744L, 4503737066323968L, 0L, 0L},
                    new long[]{8590196744L, 4503599627370496L, 0L, 0L}, 90);
            immediates[3338] = new Pattern(new long[]{131076L, 0x8001000200000L, 0L, 0L},
                    new long[]{131076L, 0x8001000000000L, 0L, 0L}, 106);
            immediates[3339] =
                    new Pattern(new long[]{2L, 0x4000800100020L, 0L, 0L}, new long[]{2L, 0x4000800100000L, 0L, 0L},
                            122);
            immediates[3340] = new Pattern(new long[]{0L, 562967133814800L, 9007199254740992L, 0L},
                    new long[]{0L, 562967133814800L, 0L, 0L}, 138);
            immediates[3341] = new Pattern(new long[]{0L, 8590196744L, 4503737066323968L, 0L},
                    new long[]{0L, 8590196744L, 4503599627370496L, 0L}, 154);
            immediates[3342] = new Pattern(new long[]{0L, 131076L, 0x8001000200000L, 0L},
                    new long[]{0L, 131076L, 0x8001000000000L, 0L}, 170);
            immediates[3343] =
                    new Pattern(new long[]{0L, 2L, 0x4000800100020L, 0L}, new long[]{0L, 2L, 0x4000800100000L, 0L},
                            186);
            immediates[3344] = new Pattern(new long[]{0L, 0L, 562967133814800L, 9007199254740992L},
                    new long[]{0L, 0L, 562967133814800L, 0L}, 202);
            immediates[3345] = new Pattern(new long[]{0L, 0L, 8590196744L, 4503737066323968L},
                    new long[]{0L, 0L, 8590196744L, 4503599627370496L}, 218);
            immediates[3346] = new Pattern(new long[]{0L, 0L, 131076L, 0x8001000200000L},
                    new long[]{0L, 0L, 131076L, 0x8001000000000L}, 234);
            immediates[3347] =
                    new Pattern(new long[]{0L, 0L, 2L, 0x4000800100020L}, new long[]{0L, 0L, 2L, 0x4000800100000L},
                            250);
            immediates[3348] = new Pattern(new long[]{281483566907400L, 4503599627370496L, 0L, 0L},
                    new long[]{281483566907400L, 0L, 0L, 0L}, 75);
            immediates[3349] = new Pattern(new long[]{4295098372L, 0x8001000000000L, 0L, 0L},
                    new long[]{4295098372L, 0x8000000000000L, 0L, 0L}, 91);
            immediates[3350] = new Pattern(new long[]{65538L, 0x4000800100000L, 0L, 0L},
                    new long[]{65538L, 0x4000800000000L, 0L, 0L}, 107);
            immediates[3351] =
                    new Pattern(new long[]{1L, 562967133814800L, 0L, 0L}, new long[]{1L, 562967133814784L, 0L, 0L},
                            123);
            immediates[3352] = new Pattern(new long[]{0L, 281483566907400L, 4503599627370496L, 0L},
                    new long[]{0L, 281483566907400L, 0L, 0L}, 139);
            immediates[3353] = new Pattern(new long[]{0L, 4295098372L, 0x8001000000000L, 0L},
                    new long[]{0L, 4295098372L, 0x8000000000000L, 0L}, 155);
            immediates[3354] = new Pattern(new long[]{0L, 65538L, 0x4000800100000L, 0L},
                    new long[]{0L, 65538L, 0x4000800000000L, 0L}, 171);
            immediates[3355] =
                    new Pattern(new long[]{0L, 1L, 562967133814800L, 0L}, new long[]{0L, 1L, 562967133814784L, 0L},
                            187);
            immediates[3356] = new Pattern(new long[]{0L, 0L, 281483566907400L, 4503599627370496L},
                    new long[]{0L, 0L, 281483566907400L, 0L}, 203);
            immediates[3357] = new Pattern(new long[]{0L, 0L, 4295098372L, 0x8001000000000L},
                    new long[]{0L, 0L, 4295098372L, 0x8000000000000L}, 219);
            immediates[3358] = new Pattern(new long[]{0L, 0L, 65538L, 0x4000800100000L},
                    new long[]{0L, 0L, 65538L, 0x4000800000000L}, 235);
            immediates[3359] =
                    new Pattern(new long[]{0L, 0L, 1L, 562967133814800L}, new long[]{0L, 0L, 1L, 562967133814784L},
                            251);
        }

        static {
            initImmediates0();
            initImmediates1();
            initImmediates2();
            initImmediates3();
            initImmediates4();
            initImmediates5();
            initImmediates6();
        }
    }
}
