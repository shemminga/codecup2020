import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

public final class SjoerdsGomokuPlayer {
    private static final long START_UP_TIME = System.nanoTime();

    private final IO io;
    private final MoveGenerator moveGenerator;

    public static void main(String[] args) throws IOException, DataFormatException {
        final IO io = new IO(System.in, System.out, System.err, true);
        final SjoerdsGomokuPlayer player = new SjoerdsGomokuPlayer(io);
        player.play();
    }

    SjoerdsGomokuPlayer(final IO io) throws DataFormatException {
        this.io = io;
        this.moveGenerator = new PatternMatchMoveGenerator(io.moveConverter, io.dbgPrinter, io.timer);
    }

    void play() throws IOException {
        final Board board = new Board();

        final Move firstMove = io.readMove();

        if (firstMove == Move.START) {
            for (int i = 0; i < 3; i++) {
                applyMove(board, Move.OPENING[i]);
                io.outputMove(Move.OPENING[i], board, i == 2);
            }
        } else {
            board.playerToMove = Board.OPPONENT;
            applyMove(board, firstMove);

            for (int i = 0; i < 2; i++) {
                Move move = io.readMove(false);
                applyMove(board, move);
            }

            final Move move = moveGenerator.decideSwitch(board);
            applyMove(board, move);
            io.outputMove(move, board, true);
        }

        while (true) {
            Move move = io.readMove();
            if (move == Move.QUIT) {
                io.timer.endMove(board, true);
                return;
            }

            applyMove(board, move);

            final Move myMove = moveGenerator.generateMove(board);
            applyMove(board, myMove);
            io.outputMove(myMove, board, true);
        }
    }

    private static void applyMove(Board board, Move move) {
        board.apply(move);
    }

    static final class MoveConverter {
        private final DbgPrinter dbgPrinter;

        MoveConverter() {
            this(null);
        }

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

        static int toFieldIdx(Move move) {
            return findBit(move.move[0], 0) + findBit(move.move[1], 64) + findBit(move.move[2], 128) +
                    findBit(move.move[3], 192);
        }

        static int toFieldIdx(int rowInt, int colInt) {
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

        static String toString(final int fieldIdx) {
            final int col = fieldIdx % 16;
            final int row = (fieldIdx - col) / 16;

            final char rowC = (char) (row + 'A');
            final char colC = (char) (col + 'a');
            return "" + rowC + colC;
        }
    }

    static final class IO {
        final DbgPrinter dbgPrinter;
        final MoveConverter moveConverter;
        final Timer timer;
        private final InputStream in;
        private final PrintStream out;

        IO(InputStream in, PrintStream out, PrintStream err, boolean dbgPrintMoves) {
            this.in = in;
            this.out = out;
            dbgPrinter = new DbgPrinter(err, START_UP_TIME, dbgPrintMoves);
            moveConverter = new MoveConverter(dbgPrinter);
            timer = new Timer(dbgPrinter);
        }

        private Move readMove() throws IOException {
            return readMove(true);
        }

        private Move readMove(boolean useTimer) throws IOException {
            dbgPrinter.separator();
            final int rowInt = robustRead();
            final int colInt = robustRead();
            if (useTimer) timer.startMove();

            final int fieldIdx = MoveConverter.toFieldIdx(rowInt, colInt);
            String moveStr = (char) rowInt + "" + (char) colInt;

            if (fieldIdx == 307) {
                // = "St", first letters of Start
                in.skip(3);
                dbgPrinter.printMove("In ", moveStr, Move.START);
                return Move.START;
            }

            if (fieldIdx == 276) {
                // = "Qu", first letters of Quit
                dbgPrinter.printMove("In ", moveStr, Move.QUIT);
                return Move.QUIT;
            }

            final Move move = moveConverter.toMove(fieldIdx);

            dbgPrinter.printMove("In ", moveStr, move);
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

        private void outputMove(Move move, Board board, boolean stopTimer) {
            final String moveStr;
            if (move == Move.SWITCH) {
                moveStr = "Zz";
            } else {
                int fieldIdx = MoveConverter.toFieldIdx(move);
                moveStr = MoveConverter.toString(fieldIdx);
            }

            dbgPrinter.printMove("Out", moveStr, move);

            timer.endMove(board, stopTimer);
            out.println(moveStr);
        }
    }

    static final class Timer {
        private final DbgPrinter dbgPrinter;
        int generatedMoves = 0;
        int boardsScored = 0;
        long timerStart;
        long totalTime = 0;

        Timer(DbgPrinter dbgPrinter) {
            this.dbgPrinter = dbgPrinter;
        }

        private void startMove() {
            timerStart = System.nanoTime();
        }

        private void endMove(Board board, boolean stopTimer) {
            dbgPrinter.log(String.format("Generated moves: %d, boards scored: %d", generatedMoves, boardsScored));
            if (stopTimer) {
                generatedMoves = 0;
                boardsScored = 0;
            }

            long timerEnd = System.nanoTime();
            long elapsedNanos = timerEnd - timerStart;
            if (stopTimer)
                totalTime += elapsedNanos;
            dbgPrinter.log(String.format("Move %3d; time used%s: %s, total %s", board.moves,
                    stopTimer ? "" : " (running)", DbgPrinter.timeFmt(elapsedNanos), DbgPrinter.timeFmt(totalTime)));
        }
    }

    static final class DbgPrinter {
        private final PrintStream err;
        private final long startUpTime;
        boolean printMoves;

        DbgPrinter(final PrintStream err, final long startUpTime, final boolean printMoves) {
            this.err = err;
            this.startUpTime = startUpTime;
            this.printMoves = printMoves;

            log("Started up");
        }

        void printMove(String type, String moveStr, Move move) {
            if (printMoves) {
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
            err.println(timeFmt(System.nanoTime() - startUpTime) + " " + message);
        }

        private void separator() {
            err.println('-');
        }

        private static String timeFmt(long nanos) {
            return String.format("%10d ns (%6.5f s)", nanos, ((double) nanos) / 1E9D);
        }
    }

    interface MoveGenerator {
        Move decideSwitch(Board board);
        Move generateMove(Board board);
    }

    static class PatternMatchMoveGenerator implements MoveGenerator {
        protected static final int MAX_SCORE = Integer.MAX_VALUE;
        protected static final int MIN_SCORE = -MAX_SCORE;
        protected static final int UNCERTAINTY = 21474836;

        private static final int PLAYER = 0;
        private static final int OPPONENT = 1;
        private static final int FIELD_IDX = 0;
        private static final int SCORE = 1;

        @SuppressWarnings("MismatchedReadAndWriteOfArray")
        private static final byte[] NIL_COUNTS = new byte[256];

        private static final int MAX_DEPTH = 16;
        private static final long MAX_NANOS = 4 * 1_000_000_000L;
        private static final int MAX_MOVES = 125;

        private final MoveConverter moveConverter;
        private final DbgPrinter dbgPrinter;
        private final Timer timer;

        private final Patterns patterns = DataReader.getPatterns();
        private final Map<Board, CalcResult> calcCache = new HashMap<>(100_000, 1.0f);

        PatternMatchMoveGenerator(final MoveConverter moveConverter, final DbgPrinter dbgPrinter, final Timer timer)
                throws DataFormatException {
            this.moveConverter = moveConverter;
            this.dbgPrinter = dbgPrinter;
            this.timer = timer;
        }

        @Override
        public Move decideSwitch(final Board board) {
            // Apply best possible move to board and then see who we'd rather be.
            Move move = generateMove(board);
            Board newBoard = board.copy().apply(move);

            int scoreKeep = scoreBoard(newBoard);
            int scoreFlip = scoreBoard(newBoard.flip());

            return scoreKeep >= scoreFlip ? move : Move.SWITCH;
        }

        private int scoreBoard(Board board) {
            if (!calcCache.containsKey(board)) calcCache.put(board, new CalcResult());
            CalcResult calcResult = calcCache.get(board);

            return scoreBoard(true, board, calcResult);
        }

        @Override
        public Move generateMove(final Board board) {
            long now = System.nanoTime();

            final long remainingNanos = MAX_NANOS - (timer.totalTime + now - timer.timerStart);
            int remainingMoves = MAX_MOVES - board.moves;

            long availableTime = remainingNanos / remainingMoves;
            final long maxNanoTime = now + availableTime;

            int[] fieldIdxAndScore = null;
            for (int searchDepth = 1; searchDepth <= MAX_DEPTH; searchDepth++) {
                final int[] newInts =
                        minimax(board, board.playerToMove == Board.PLAYER, 0, searchDepth, maxNanoTime, MIN_SCORE,
                                MAX_SCORE);

                if (newInts == null) break;

                fieldIdxAndScore = newInts;

                dbgPrinter.log("Search depth " + searchDepth + ": best move: " + fieldIdxAndScore[FIELD_IDX] +
                        "; time remaining: " + (maxNanoTime - System.nanoTime()));
            }

            dbgPrinter.log("Board cache size: " + calcCache.size());
            assert fieldIdxAndScore != null;
            return fieldIdxAndScore[FIELD_IDX] < 0 ? null : moveConverter.toMove(fieldIdxAndScore[FIELD_IDX]);
        }

        private int[] minimax(Board board, boolean isPlayer, final int level, int maxDepth, final long maxNanoTime, int alpha, int beta) {
            if (level > 1 && System.nanoTime() >= maxNanoTime) {
                return null;
            }

            if (!calcCache.containsKey(board)) calcCache.put(board, new CalcResult());
            CalcResult calcResult = calcCache.get(board);

            if (calcResult.match4 == null) calcResult.match4 = match(board, patterns.pat4);

            if (calcResult.immediateWin == CalcResult.UNKNOWN)
                calcResult.immediateWin = Arrays.mismatch(calcResult.match4[isPlayer ? PLAYER : OPPONENT], NIL_COUNTS);

            if (calcResult.immediateWin >= 0) {
                return fieldIdxAndScore(calcResult.immediateWin, isPlayer ? MAX_SCORE : MIN_SCORE);
            }

            if (!calcResult.hasOwnScore) {
                calcResult.ownScore = scoreBoard(isPlayer, board, calcResult);
                calcResult.hasOwnScore = true;
            }

            if (maxDepth <= 0) {
                return new int[]{-1, calcResult.ownScore};
            }

            if (calcResult.moves == null) {
                calcResult.moves = listTopMoves(isPlayer, board, calcResult);
                timer.generatedMoves += calcResult.moves.size();
            }

            int[] retval = new int[]{calcResult.moves.get(0), isPlayer ? MIN_SCORE : MAX_SCORE};
            for (int move : calcResult.moves) {
                final Board nextBoard = board.copy().apply(moveConverter.toMove(move));

                final int[] idxAndScore = minimax(nextBoard, !isPlayer, level + 1, maxDepth - 1, maxNanoTime, alpha, beta);

                if (idxAndScore == null) {
                    return null;
                }

                if (isPlayer && idxAndScore[SCORE] > retval[SCORE]) {
                    retval[FIELD_IDX] = move;
                    retval[SCORE] = idxAndScore[SCORE];
                    alpha = Math.max(alpha, retval[SCORE]);
                } else if (!isPlayer && idxAndScore[SCORE] < retval[SCORE]) {
                    retval[FIELD_IDX] = move;
                    retval[SCORE] = idxAndScore[SCORE];
                    beta = Math.min(beta, retval[SCORE]);
                }

                if (alpha >= beta) {
                    break;
                }
            }

            return retval;
        }

        private List<Integer> listTopMoves(final boolean isPlayer, final Board board, final CalcResult calcResult) {
            if (calcResult.match4 == null) calcResult.match4 = match(board, patterns.pat4);

            final int immediateLoss = Arrays.mismatch(calcResult.match4[isPlayer ? OPPONENT : PLAYER], NIL_COUNTS);
            if (immediateLoss >= 0) {
                // We will need to do this move, no other will have to be searched at this level.
                return Collections.singletonList(immediateLoss);
            }

            if (calcResult.match3 == null) calcResult.match3 = match(board, patterns.pat3);
            if (calcResult.match2 == null) calcResult.match2 = match(board, patterns.pat2);
            if (calcResult.match1 == null) calcResult.match1 = match(board, patterns.pat1);

            int onMove = isPlayer ? PLAYER : OPPONENT;
            int offMove = isPlayer ? OPPONENT : PLAYER;
            int[] scores = new int[256];
            for (int i = 0; i < 256; i++) {
                if (calcResult.match3[onMove][i] >= 2) {
                    // Winning move.
                    scores[i] = 2_000_000_000;
                    continue;
                }

                if (calcResult.match3[offMove][i] >= 2) {
                    // Losing if not handled.
                    scores[i] = 1_900_000_000;
                    continue;
                }

                if (calcResult.match3[onMove][i] >= 1) {
                    // Possible chaining opportunity
                    scores[i] = 1_600_000_000;
                    continue;
                }

                if (calcResult.match3[offMove][i] >= 1) {
                    // Possible opponent chaining opportunity
                    scores[i] = 1_500_000_000;
                    continue;
                }

                if (calcResult.match2[onMove][i] >= 2) {
                    // Maybe there's an opportunity to create 4 match3s
                    scores[i] = 1_300_000_000;
                    continue;
                }

                if (calcResult.match2[offMove][i] >= 2) {
                    // Maybe there's an opportunity to create 4 match3s
                    scores[i] = 1_200_000_000;
                    continue;
                }

                scores[i] =
                        calcResult.match2[onMove ][i] * 20 + // Lenghtening 2s can create opportunities
                        calcResult.match2[offMove][i] * 20 +
                        calcResult.match1[onMove ][i] +
                        calcResult.match1[offMove][i];
            }

            final List<Map.Entry<Integer, Integer>> allMoves = IntStream.range(0, 255)
                    .mapToObj(i -> Map.entry(i, scores[i]))
                    .filter(e -> e.getValue() > 0)
                    .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                    .collect(Collectors.toList());

            final Stream<Map.Entry<Integer, Integer>> strongMoves = allMoves.stream()
                    .filter(e -> e.getValue() >= 1_000_000_000);
            final Stream<Map.Entry<Integer, Integer>> weakMoves = allMoves.stream()
                    .filter(e -> e.getValue() < 1_000_000_000)
                    .limit(2);

            final List<Map.Entry<Integer, Integer>> genMoves = Stream.concat(strongMoves, weakMoves)
                    //.limit(3) // Unfortunately, not enough power
                    .collect(Collectors.toList());

            if (genMoves.size() > 0) {
                return genMoves.stream()
                        .map(Map.Entry::getKey)
                        .collect(Collectors.toList());
            }

            // No helpful move. Just finishing up the game. Pick first valid move.
            dbgPrinter.log("No helpful move found. Picking first valid move.");
            for (int i = 0; i < 256; i++) {
                final Move move = moveConverter.toMove(i);
                if (board.validMove(move)) {
                    return Collections.singletonList(i);
                }
            }

            // No move ?!
            dbgPrinter.log("No valid moves found");
            return Collections.emptyList();
        }

        private int scoreBoard(final boolean isPlayer, final Board board, final CalcResult calcResult) {
            if (calcResult.match4 == null) calcResult.match4 = match(board, patterns.pat4);
            if (calcResult.match3 == null) calcResult.match3 = match(board, patterns.pat3);
            if (calcResult.match2 == null) calcResult.match2 = match(board, patterns.pat2);
            if (calcResult.match1 == null) calcResult.match1 = match(board, patterns.pat1);

            timer.boardsScored++;

            int playerToMoveFactor = isPlayer ? 1 : -1;
            int onMove = isPlayer ? PLAYER : OPPONENT;
            int offMove = isPlayer ? OPPONENT : PLAYER;

            int[][] winCounts = new int[2][5];
            int[][] opportunityCounts = new int[2][5];

            for (int i = 0; i < 256; i++) {
                // There's an immediate win.
                if (calcResult.match4[onMove][i] >= 1) {
                    return playerToMoveFactor * MAX_SCORE;
                }

                if (calcResult.match4[offMove][i] >= 1) {
                    winCounts[offMove][4]++;
                }

                if (calcResult.match3[onMove][i] >= 2) {
                    // Either open 3 or (less likely) almost overline: xooo..o. Open 3 is a win when playing.
                    winCounts[onMove][3]++;
                } else if (calcResult.match3[onMove][i] == 1 && calcResult.match2[onMove][i] >= 3) {
                    // Create a 4 and an open 3.
                    winCounts[onMove][2]++;
                }

                if (calcResult.match3[offMove][i] >= 2) {
                    // Either open 3 or (less likely) almost overline: xooo..o. Open 3 is a win when playing.
                    winCounts[offMove][3]++;
                } else if (calcResult.match3[offMove][i] == 1 && calcResult.match2[offMove][i] >= 3) {
                    // Create a 4 and an open 3.
                    winCounts[offMove][2]++;
                }

                if (calcResult.match2[onMove][i] >= 4) {
                    winCounts[onMove][2]++;
                }

                if (calcResult.match2[offMove][i] >= 4) {
                    winCounts[offMove][2]++;
                }

                opportunityCounts[onMove][1] += calcResult.match1[onMove][i];
                opportunityCounts[onMove][2] += calcResult.match2[onMove][i];
                opportunityCounts[onMove][3] += calcResult.match3[onMove][i];
                opportunityCounts[onMove][4] += calcResult.match4[onMove][i];

                opportunityCounts[offMove][1] += calcResult.match1[offMove][i];
                opportunityCounts[offMove][2] += calcResult.match2[offMove][i];
                opportunityCounts[offMove][3] += calcResult.match3[offMove][i];
                opportunityCounts[offMove][4] += calcResult.match4[offMove][i];
            }

            // Two immediate wins for the opponent. Can't block that.
            if (winCounts[offMove][4] >= 2) {
                return playerToMoveFactor * MIN_SCORE;
            }

            if (winCounts[onMove][3] >= 1) {
                return playerToMoveFactor * (MAX_SCORE - 1 - UNCERTAINTY);
            }

            if (winCounts[offMove][3] >= 2) {
                return playerToMoveFactor * (MIN_SCORE + 1 + UNCERTAINTY);
            }

            if (winCounts[onMove][2] >= 1) {
                return playerToMoveFactor * (MAX_SCORE - 1 - 2*UNCERTAINTY);
            }

            if (winCounts[offMove][2] >= 2) {
                return playerToMoveFactor * (MIN_SCORE + 1 + 2*UNCERTAINTY);
            }

            return (opportunityCounts[onMove][4] - Math.max(opportunityCounts[offMove][4] - 1, 0)) * 100_000 +
                    (opportunityCounts[onMove][3] - Math.max(opportunityCounts[offMove][3] - 1, 0)) * 10_000 +
                    (opportunityCounts[onMove][2] - Math.max(opportunityCounts[offMove][2] - 1, 0)) * 100 +
                    (opportunityCounts[onMove][1] - Math.max(opportunityCounts[offMove][1] - 1, 0));
        }

        private static int[] fieldIdxAndScore(int fieldIdx, int score) {
            return new int[]{fieldIdx, score};
        }

        private static byte[][] match(final Board board, final Pattern[] patterns) {
            final byte[][] possibleMoves = new byte[2][256];

            long[] nPS = new long[]{~board.playerStones[0], ~board.playerStones[1], ~board.playerStones[2],
                    ~board.playerStones[3]};
            long[] nOS = new long[]{~board.opponentStones[0], ~board.opponentStones[1], ~board.opponentStones[2],
                    ~board.opponentStones[3]};

            long[] occFlds = new long[]{(board.playerStones[0] | board.opponentStones[0]),
                    (board.playerStones[1] | board.opponentStones[1]),
                    (board.playerStones[2] | board.opponentStones[2]),
                    (board.playerStones[3] | board.opponentStones[3])};

            for (final Pattern p : patterns) {
                if (((occFlds[0] & p.emptyFields[0]) | (occFlds[1] & p.emptyFields[1]) |
                        (occFlds[2] & p.emptyFields[2]) | (occFlds[3] & p.emptyFields[3])) != 0) {
                    continue;
                }

                if (((nPS[0] & p.playerStones[0]) | (nPS[1] & p.playerStones[1]) | (nPS[2] & p.playerStones[2]) |
                        (nPS[3] & p.playerStones[3])) == 0) {
                    for (int fieldIdx : p.fieldIdxs) {
                        possibleMoves[PLAYER][fieldIdx]++;
                    }
                }

                if (((nOS[0] & p.playerStones[0]) | (nOS[1] & p.playerStones[1]) | (nOS[2] & p.playerStones[2]) |
                        (nOS[3] & p.playerStones[3])) == 0) {
                    for (int fieldIdx : p.fieldIdxs) {
                        possibleMoves[OPPONENT][fieldIdx]++;
                    }
                }
            }

            return possibleMoves;
        }
    }

    static final class Move {
        private static final Move START = new Move(new long[]{0, 0, 0, 0});
        private static final Move QUIT = new Move(new long[]{0, 0, 0, 0});
        static final Move SWITCH = new Move(new long[]{0, 0, 0, 0});

        private static final Move[] OPENING = {new Move(new long[]{0, 0x100, 0, 0}), // Hh
                new Move(new long[]{0, 0, 0x80000000000000L, 0}), // Ii
                new Move(new long[]{0, 0, 0x1000000, 0}) // Kh
        };

        long[] move;

        private Move(final long[] move) {
            this.move = move;
        }
    }

    static final class Board {
        static final int PLAYER = 0;
        @SuppressWarnings("unused")
        static final int OPPONENT = ~0;

        int playerToMove = PLAYER;
        long[] playerStones = {0, 0, 0, 0};
        long[] opponentStones = {0, 0, 0, 0};
        int moves = 0;

        Board() {
        }

        private Board(final int playerToMove, final long[] playerStones, final long[] opponentStones) {
            this.playerToMove = playerToMove;
            this.playerStones = playerStones;
            this.opponentStones = opponentStones;
        }

        Board copy() {
            return new Board(playerToMove, Arrays.copyOf(playerStones, 4), Arrays.copyOf(opponentStones, 4));
        }

        Board apply(Move move) {
            if (move == Move.SWITCH) {
                return flip();
            }
            moves++;

            long[] updatee = playerToMove == PLAYER ? playerStones : opponentStones;

            for (int i = 0; i < 4; i++) {
                updatee[i] |= move.move[i];
            }

            playerToMove = ~playerToMove;

            return this;
        }

        protected Board flip() {
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

        @SuppressWarnings("EqualsWhichDoesntCheckParameterClass")
        @Override
        public boolean equals(final Object o) {
            if (this == o) return true;
            final Board board = (Board) o;
            return playerToMove == board.playerToMove && playerStones[0] == board.playerStones[0] &&
                    playerStones[1] == board.playerStones[1] && playerStones[2] == board.playerStones[2] &&
                    playerStones[3] == board.playerStones[3] && opponentStones[0] == board.opponentStones[0] &&
                    opponentStones[1] == board.opponentStones[1] && opponentStones[2] == board.opponentStones[2] &&
                    opponentStones[3] == board.opponentStones[3];
        }

        @Override
        public int hashCode() {
            int result = playerToMove;
            result = 31 * result + (int) (playerStones[0] ^ (playerStones[0] >>> 32));
            result = 31 * result + (int) (playerStones[1] ^ (playerStones[1] >>> 32));
            result = 31 * result + (int) (playerStones[2] ^ (playerStones[2] >>> 32));
            result = 31 * result + (int) (playerStones[3] ^ (playerStones[3] >>> 32));
            result = 31 * result + (int) (opponentStones[0] ^ (opponentStones[0] >>> 32));
            result = 31 * result + (int) (opponentStones[1] ^ (opponentStones[1] >>> 32));
            result = 31 * result + (int) (opponentStones[2] ^ (opponentStones[2] >>> 32));
            result = 31 * result + (int) (opponentStones[3] ^ (opponentStones[3] >>> 32));
            return result;
        }
    }

    static final class CalcResult {
        public static final int UNKNOWN = -2;
        @SuppressWarnings("unused")
        public static final int NO = -1;
        byte[][] match4;
        byte[][] match3;
        byte[][] match2;
        byte[][] match1;
        int immediateWin = UNKNOWN;
        List<Integer> moves;
        int ownScore;
        boolean hasOwnScore;
    }

    static final class Patterns {
        Pattern[] pat1;
        Pattern[] pat2;
        Pattern[] pat3;
        Pattern[] pat4;
    }

    static final class Pattern {
        final long[] emptyFields;
        final long[] playerStones;
        final int[] fieldIdxs;

        Pattern(final long[] emptyFields, final long[] playerStones, final int... fieldIdxs) {
            this.emptyFields = emptyFields;
            this.playerStones = playerStones;
            this.fieldIdxs = fieldIdxs;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) return true;
            if (!(o instanceof Pattern)) return false;

            final Pattern pattern = (Pattern) o;

            if (!Arrays.equals(emptyFields, pattern.emptyFields)) return false;
            if (!Arrays.equals(playerStones, pattern.playerStones)) return false;
            return Arrays.equals(fieldIdxs, pattern.fieldIdxs);
        }

        @Override
        public int hashCode() {
            int result = Arrays.hashCode(emptyFields);
            result = 31 * result + Arrays.hashCode(playerStones);
            result = 31 * result + Arrays.hashCode(fieldIdxs);
            return result;
        }
    }

    static final class DataReader {
        static Patterns getPatterns() throws DataFormatException {
            return deserializePatterns(Data.PATTERNS, Data.PATTERNS_UNCOMPRESSED_SIZE);
        }

        static Patterns deserializePatterns(final String patternsString, final int uncompressedSize)
                throws DataFormatException {
            final byte[] decode = Base64.getDecoder()
                    .decode(patternsString);

            final Inflater inflater = new Inflater(true);
            inflater.setInput(decode);
            final ByteBuffer byteBuffer = ByteBuffer.allocate(uncompressedSize);
            inflater.inflate(byteBuffer);
            if (!inflater.finished()) {
                throw new AssertionError();
            }
            inflater.end();
            byteBuffer.rewind();

            final Patterns patterns = new Patterns();

            final long longBufferLen = byteBuffer.getLong();
            final LongBuffer longBuffer = byteBuffer.asLongBuffer();
            longBuffer.limit((int) longBufferLen);
            byteBuffer.position(byteBuffer.position() + (int) longBufferLen * Long.BYTES);
            final IntBuffer intBuffer = byteBuffer.asIntBuffer();

            patterns.pat1 = new Pattern[intBuffer.get()];
            patterns.pat2 = new Pattern[intBuffer.get()];
            patterns.pat3 = new Pattern[intBuffer.get()];
            patterns.pat4 = new Pattern[intBuffer.get()];

            readPatterns(patterns.pat1, longBuffer, intBuffer);
            readPatterns(patterns.pat2, longBuffer, intBuffer);
            readPatterns(patterns.pat3, longBuffer, intBuffer);
            readPatterns(patterns.pat4, longBuffer, intBuffer);

            return patterns;
        }

        private static void readPatterns(final Pattern[] pat, final LongBuffer longBuffer, final IntBuffer intBuffer) {
            for (int i = 0; i < pat.length; i++) {
                long[] emptyFields = new long[4];
                longBuffer.get(emptyFields);

                long[] playerStones = new long[4];
                longBuffer.get(playerStones);

                int[] fieldIdx = new int[intBuffer.get()];
                intBuffer.get(fieldIdx);

                pat[i] = new Pattern(emptyFields, playerStones, fieldIdx);
            }
        }
    }

    static final class Data {
        static final int PATTERNS_UNCOMPRESSED_SIZE = 1572504;
        @SuppressWarnings("StringBufferReplaceableByString") // It really can't be replaced by String.
        static final String PATTERNS = new StringBuilder().append(
                "7N13uGRVmfb/XdUFHAS0RlER0V+9ioiKjiM56GzJ0UFydFpydFBycrbk6CA5OkiODpKj05LzoOTkoOSg4zvmNO/vdJ+1Ti+Ku9ZT0/0PT61v9XW63fvs67OvVV6Huu9n19lVVeOP9l7Vr6r8Y6yyHggICF6FfYwjOuY5EBAQvAorGEe0zXMgICB4FT5sHNEyz4GAgOBVeJdFNNYBCAgIXoW5LKO2CAQEBK/ClGnGET3rHAgICF6F1nPGEV3rHAgICG4FLpUiIJQrcKkUAaFcgUulCAjlClwqRUAoVnhldkcMFQICgldhj9keMSAgIHgVlpvtEQMCAoJX4UOzPWJAQEDwKryzmd0RAwICgldhrtogGuscCAgIXoUpZxtHWEKFgIDgVWg9ZBzRs86BgIDgVuBSKQJCuQKXShEQyhW4VIqAUK7ApVIEhGKFJ2Z3xFAhICB4FXaZ7REDAgKCV2Gp2R4xICAgeBU+ONsjBgQEBK/CfM3sjhgQEBC8CnNOyx9h/44BAgKCV2HKVINorHMgICB4FVpXGEfU1jkQEBDcClwqRUAoV+BSKQJCuQKXShEQyhW4VIqAUKxw/eyOGCoEBASvwhazPWJAQEDwKnxmtkcMCAgIXoX3z/aIAQEBwaswTzO7IwYEBASvwhzT8kfYv2OAgIDgVWg/lz/C/h0DBAQEr0JrN4NorHMgICC4FbhUioBQrsClUgSEcgUulSIglCtwqRQBoVhhn9kdMVQICAhehRVme8SAgIDgVfjwbI8YEBAQvArvmu0RAwICgldhrmZ2RwwICAhehSnT8kfYv2OAgIDgVWg9lz/C/h0DBAQEt8KvDME8BwICgluBS6UICOUKXCpFQChX4FIpAkK5ApdKERCKFZpq4o/1aDLfQUDwKtThj/WoM99BQPAq9MIf69HLfAcBwavQDX+sRzfzHQQEr8JY+GM9xjLfQUDwKnTCH+vRyXwHAcGr0A5/rEc78x0EBK9CK/yxHq3MdxAQ3AqMjBFKFhgZI5QsMDJGKFlgZIxQssDIGKFkgZExQskCI2OEkgVGxggFC3FM2mQPa7LfQ0DwKsQxaZ09ss5+DwHBqxDHpL3skb3s9xAQvApxTJoflXaz30NA8CrEMWl+VDqW/R4Cglchjknzo9JO9nsICF6FOCbNj0rb2e8hIHgV4pg0PyptZb+HgOBWYGSMULLAyBihZIGRMULJAiNjhJIFRsYIJQuMjBFKFhgZI5QsMDJGKFiIY9Imc1iTRRAQ/ApxTFpnjqyz50JA8CvEMWkvc2Qvey4EBL9CHJPmh6lV9rsICF6FOCbND1Or7HcRELwKcUyaH6ZW2e8iIHgV4pg0P0ytst9FQPAqxDFpfphaZb+LgOBWYGSMULLAyBihZIGRMULJAiNjhJIFRsYIJQuMjBFKFhgZI5QsMDJGKFhoqsr4qNSmyj8QEPwKdVUZH5VaG+dCQPAr9KrK+KjUnnEuBAS/QhyT5j5KNf9AQPArxDFp7qNU8w8EBL9CHJPmPko1/0BA8CvEMWnuo1TzDwQEv0Ick+Y+SjX/QEBwLDQVI2OEcoW6YmSMUK7QqxgZI5QrMDJGKFlgZIxQssDIGKFkgZExQskCI2OEooUmOyhtKvuBgOBXqLOD0nqIcyEg+BV62UFpb4hzISD4FbrZQWl3iHMhIPgVxrKD0rEhzoWA4FfoZAelnSHOhYDgV2hnB6XtIc6FgOBXaGUHpa0hzoWA4FhgZIxQssDIGKFkgZExQskCI2OEkgVGxgglC4yMEUoWGBkjlCwwMkYoVmiqerwM24VicORCQPArTBxnF4rBkQsBwa8wcZxdKAZHLgQEv8LEcXahGBy5EBD8ChPHDVEomsEZCgHBqxCOa8wj68EZCgHBqxCOq80je4MzFAKCV6EVh0XWo5tJYQgIXgVGxgglC4yMEUoWGBkjlCwwMkYoWIg1YWzWikY1s2ggIPgTYk3ozGLRQEDwLMSa0J7FooGA4FmINaE1i0UDAcGzMFkTmlkrGggInoXJ9wnV2R+VJvcqgoDgVZh8n1Ave2SdexVBQPAqTL5PKH+tuJd9FUFA8CowMkYoWWBkjFCywMgYoWSBkTFCwUKsCWOzVjQqBATPQqwJnVksGggInoVYE9qzWDQQEDwLsSa0ZrFoICB4FiZrQjNrRQMBwbMw+T6hevCRLWOIhIDgVZh8n1AvW6ezQyQEBK/C5PuEutk6XWV/jhAQvAqMjBFKFhgZI5QsMDJGKFlgZIxQsBBrwtisFQ0EBNdCrAmdWSwaCAiehVgT2rNYNBAQPAuxJrRmsWggIHgWJmtCM2tFAwHBszD5PqGBlcK6iS4Cgl9h8n1CAyuF9Sl6CAh+hcn3CXWzw6TMAwHBsdBUjIwRyhXqipExQrlCr2JkjFCuwMgYoWgh/1GRRtFAQHAu5D8qsh7iXAgIfoX8R0X2hjgXAoJfYSwblLpDnAsBwa/Qyb5TYmyIcyEg+BXa2XdKdIY4FwKCX6GVfadEe4hzISA4FprcOyVaQ5wLAcGxwMgYoWSBkTFCyQIjY4SSBUbGCMUKY+P/szfEC0qTORECglehE46168SgBwKCX6EdjrXrxKAHAoJfoRWOtevEoAcCgmOhmTjWrhODQxQCgluhnjjWenRyIQoBwavQmzjWerRzIQoBwaswMShqrCNbuRCFgOBVYGSMULLAyBihZIGRMULJAiNjhIKF+DLRzOILTVUhIPgV4stEPYsvNAgInoX4MtGbxRcaBATPQrxOnK8KuSCFgOBYCNeJ81Uh+04KBAS/QrhOnH+vUDtfIxAQvArhOnH+vUKtfI1AQPAqhOvExnuFmmyNQEDwKjAyRihZYGSMULLAyBihZIGRMULBQnyZaGbxhQYBwbMQXybqWXyhQUDwLMSXid4svtAgIHgW4nXibvanqTKiFgKCUyFcJx7L/jRlHwgIfoVwnbiT/WnKPhAQ/ArhOnH256nJngsBwa8QrhO38j9NVbZIIyB4FRgZI5QsMDJGKFlgZIxQssDIGKFgIb5MNLP4QoOA4FmILxP1LL7QICB4FuLLRG8WX2gQEDwL8TpxN2vloxYCgluhqYxbhZqfooGA4FeoZ/6yzGAr+0BA8CtMj0j5zwqrjXMhIPgVwnXiVt7KPRAQ/AqMjBFKFhgZI5QsMDJGKFlgZIxQtJD/qGD7I8QQEDwL+Y8K7gxxLgQEv0L+o4LbQ5wLAcGvkP+o4NYQ50JA8CsYHxXc2OdCQHAsNNl3StRD/LAhIPgV6uw7JXpDnAsBwa/Qy75TojvEuRAQ/AqMjBFKFhgZI5QsMDJGKFlgZIxQrPCcwSxgnggBAcGrsJtxxLzmORAQELwKyxhHzGmeAwEBwauwkHHEFPMcCAgIXoV3Gke0GuscCAgIXoW5LGKadQACAoJXYYr135CpFoGAgOBVaFn/DfmsdQ4EBAS3wnPGEVwqRUAYXYFLpQgI5QpcKkVAKFfgUikCQrHCQ8YBi5qnQEBA8CrsYBwxv3kOBAQEr8ISxhHvMM+BgIDgVVjQOGIO8xwICAhehfmMI9qNdQ4EBASvwpyG0aqtcyAgIHgVpljG2dY5EBAQvAoty1jXOgcCAoJbgUulCAjlClwqRUAoV+BSKQJCuQKXShEQihXuNg4wbzFQISAgeBW2MY6wbzGAgIDgVficcYR9iwEEBASvwgeMI+xbDCAgIHgV5m3yR9i3GEBAQPAqzFnnj7A/YwABAcGrMKVnENOscyAgIHgVWscZR0y1zoGAgOBW4FIpAkK5ApdKERDKFbhUioBQrsClUgSEYoUrjANWN0+BgIDgVdjEOGJh8xwICAhehcWMI95tngMBAcGr8D7jiLnNcyAgIHgV5jGO6DTWORAQELwKcxhGu7bOgYCA4FVoT8sf0epZ50BAQPAqtKYahPUO4goBAcGtwKVSBIRyBS6VIiCUK3CpFAGhXIFLpQgIxQoXGQeYHzFYISAgeBU2MI6wP2IQAQHBq/BJ4wj7IwYREBC8Cu81jrA/YhABAcGr8I4mf4T9EYMICAhehTnq/BHtxjoHAgKCV6F9dv6IVm2dAwEBwavQsi4inG2dAwEBwa3ApVIEhHIFLpUiIJQrcKkUAaFcgUulCAjFCqcaB0w1T4GAgOBVWNs4wv6IQQQEBK/CIsYR9kcMIiAgeBXeYxxhf8QgAgKCV2HuJn+E/RGDCAgIXoXOtPwRU8xzICAgeBXaU/NHtBrrHAgICF6FljVEnGadAwEBwa3ApVIEhHIFLpUiIJQrcKkUAaFcgUulCAjFCrsZBxxhngIBAcGrsIxxxMrmORAQELwKCxlHfMQ8BwICglfhncYRf2OeAwEBwaswl3HEWGOdAwEBwaswxTA6tXUOBAQEr0JrWv6Ids86BwICglvhOUPomiUDAQHBq8ClUgSEcgUulSIglCtwqRQBoVyBS6UICMUKOxgHHGeeAgEBwauwhHHE6uY5EBAQvAoLGkcsbJ4DAQHBqzCfccS7zXMgICB4FeZs8kfMbZ4DAQHBqzClzh/RaaxzICAgeBVaZ+ePaNfWORAQENwKDxlCzwwZCAgIXgUulSIglCtwqRQBoVyBS6UICOUKXCpFQChW2MQ44GzzFAgICF6FxYwj1jXPgYCA4FV4n3HEouY5EBAQvArzGEfMb54DAQHBqzBHkz/iHeY5EBAQvArtaYZgngMBAcGr0JpqCI11DgQEBLfCFYZQm/+RQUBA8CpwqRQBoVyBS6UICOUKXCpFQChX4FIpAkKxgvUBQdPMUyAgIHgVrA8ImmqeAwEBwatgfUDQZ81zICAgeBXmMo5YwDwHAgKCV2FKkz9iXvMcCAgIXoWWMUSY0zwHAgKCW+E5478g5jkQEBDcCrsZ/wVpzHMgICB4FbhUioBQrsClUgSEcgUulSIglCtwqRQBoVihqab/sR65YxAQ/Ar1jD/WI3cMAoJfoTfjj/XIHYOA4FfozvhjPXLHICD4FcZm/LEeuWMQEPwKnRl/rEfuGAQEv0J7xh/rkTsGAcGv0Jrxx3rkjkFAcCwwMkYoWWBkjFCywMgYoWSBkTFCyQIjY4SSBUbGCCULjIwRShYYGSMULEyMSZvsYfkjEBD8ChNj0jp7ZP4IBAS/wsSYtJc9Mn8EAoJfYWJMmh+V5o9AQPArTIxJ86PS/BEICH6FiTFpflSaPwIBwa8wMSbNj0rzRyAg+BUmxqT5UWn+CAQExwIjY4SSBUbGCCULjIwRShYYGSOULDAyRihZYGSMULLAyBihZIGRMULBQrxNSpM5sBnquwgI/oR4m5Q6c2Q91HcREPwJ8TYpvcyRvaG+i4DgT4i3ScmNSrtDfRcBwZ8Qb5OSG5WODfVdBAR/QrxNSm5U2hnquwgI/oR4m5TcqLQ91HcREPwJ8TYpuVFpa6jvIiA4FBgZI5QsMDJGKFlgZIxQssDIGKFkgZExQskCI2OEkgVGxgglC4yMEQoWmsoepc78e9D3ERB8CnVlj1Jn/j3o+wgIPoVeZY9SZ/496PsICD6FiZug5MekM/8e9H0EBJ/CxE1Q8mPSmX8P+j4Cgk9h4iYo+THpzL8HfR8BwacwcROU/Jh05t+Dvo+A4FOYuE1Kfkw68+9B30dAcCo0FSNjhHKFumJkjFCu0KsYGSOUKzAyRihZYGSMULLAyBihZIGRMULJAiNjhIKFprI/SrUyB6kICD6FurI/SrUyB6kICD6FXmV/lGplDlIREHwK3cr+KNXKHKQiIPgUxir7o1Qrc5CKgOBT6FT2R6lW5iAVAcGn0K7sj1KtzEEqAoJPoVXZH6VamYNUBASnQlMxMkYoV6grRsYI5Qq9ipExQrkCI2OEkgVGxgglC4yMEUoWGBkjlCwwMkYoWGhmbDTZQWlT5R4ICH6FOvxdDzwyfxsVBATPQi/83Rt4ZP42KggInoV4G5TcoLSbPRcCgl8h3gYlNygdy54LAcGvEG+DkhuUdrLnQkDwK8TboOQGpe3suRAQ/ArxRiq5QWkrey4EBMdCE/5uBh7KyBhhdIU6/F0PPJKRMcLoCr3wd2/gkYyMEUZXYGSMULLAyBihZIGRMULJAiNjhJIFRsYIRQuNMSZ987/6GAQEr0JtjEnf/K8+BgHBq9AzxqRv/lcfg4DgVegaY9I3/6uPQUDwKowZY9I3/6uPQUDwKnSMMemb/9XHICB4FdrGmPTN/+pjEBC8Ci1jTPrmf/UxCAhuBUbGCCULjIwRShYYGSOULDAyRihZYGSMULLAyBihZIGRMULJAiNjhIKFJvtBqU3ydzXwGAQEr0Kd/aDUOvm7GngMAoJXoZf9oNRe8nc18BgEBK9CN/tBqd3k72rgMQgIXoWx7AeljiV/VwOPQUDwKnSyH5TaSf6uBh6DgOBVaGc/KLWd/F0NPAYBwavQyn5Qaiv5uxp4DAKCW4GRMULJAiNjhJIFRsYIJQuMjBFKFhgZI5QsMDJGKFlgZIxQssDIGKFgoalyH5TaVNagFQHBs1BXuQ9KrStr0IqA4FnoVbkPSu1V1qAVAcGzMDEmzQ1Ku8a5EBD8ChNj0tygdMw4FwKCX2FiTJoblHaMcyEg+BUmxqS5QWnbOBcCgl9hYkyaG5S2jHMhIDgWmoqRMUK5Ql0xMkYoV+hVjIwRyhUYGSOULDAyRihZYGSMULLAyBihZIGRMULRQpMdlDaV/UBA8CvU2UFpPcS5EBD8Cr3soLQ3xLkQEPwK3eygtDvEuRAQ/Apj2UHp2BDnQkDwK3Syg9LOEOdCQPArtLOD0vYQ50JA8Cu0soPS1hDnQkBwLDAyRihZYGSMULLAyBihZIGRMULJAiNjhJIFRsYIJQuMjBFKFhgZIxQqNFU9RJnuZgIXAoJfYfpxdpkeywQuBAS/Qi/7czSzLgwOXAgIfoVu9udoZl1oZV9FEBB8CmPZn6OZdWHwOy4QEPwKnezP0ZsKReZVBAHBp9DO/hy9qVDkXocQEFwKrezP0ZsKRe51CAHBp8DIGKFkgZExQskCI2OEkgVGxggFCxM1oWvUhMqIWggIPoXakKrgdIwxFAKCR6FnSFVw2sYYCgHBo9A1pCo4LWMMhYDgURgzpCo6TX4MhYDgUegYUhWd2hhDISA4FNqGVEWnZ4yhEBAcCi1DqqKTf68FAoJPgZExQskCI2OEkgVGxgglC4yMEQoW4q8JjGWDUjdboxEQvArx1wQ62aA0lq3RCAhehfhz1M4Gpc4QP4kICP6E+HPUygal9hA/iQgI/oTJn6Nm8JH5e0kgIPgVJn+O6sFH5u8lgYDgV5j8OeoNUafzP4kICO6EyZ+j7hB1Ov+TiIDgT2BkjFCywMgYoWSBkTFCyQIjY4SChcaMUlaQQkDwK9RmlLKCFAKCX6FnRikrSCEg+BW6ZpSyghQCgl9h4tcE8jVhMlINlBAQfArh12iawUda76RAQPArhPsE1YOPtN5JgYDgVwj3CeplXiqapDAMkhAQfApNxcgYoVyhrhgZI5Qr9CpGxgjlCoyMEQoWmsq6lWJdVcZbrREQvAp1Zd1KMe8gIHgW4r9t46WkMiUEBH9C/LeVDUpto0ggIPgUJqVm0JHWJykhIPgVJqV60JHWJykhIPgV2mldlg/rk5QQEPwKLXuclHUQEFwLTcXIGKFcoa4YGSOUK/QqRsYI5QqMjBEKFppweC4oZYpGhYDgWajDD0MuKOU/RhgBwa8w8VPQyQal/McIIyD4FeKvAeSCUv5jhBEQ/AqTv0jQDDqyY3yMMAKCX2HyTkL1oCPz9xFCQPAstM1Cnb+PEAKCZ6FlF+rsfYQQEFwLjVmoGRkjjK5Qm4WakTHC6Ao9s1AzMkYYXYGRMULRQm3UhHh4lY1SCAg+hZ5RE6rwUlJloxQCgk+ha9SEKryUVNkohYDgUxgzasLEI3+3CQQEr0LHqAl9kUk8EBD8Cm3jfUJ9kUk8EBD8Ci3jfUJ9kUk8EBAcC43xPqE3RyY9TkJA8CowMkYoWWBkjFCywMgYoWSBkTFCwUKdvZlcYxYNBATPQi97M7naLBoICJ6FbvZmcj2zaCAgeBbGsjdKtD5oGAHBt9DJfpqM9UHDCAi+hXb202Q6aWEY8EBA8Cu0sp8m004Lw4AHAoJjocl9mkwrLQzVwHESAoJXgZExQskCI2OEkgVGxgglC4yMEQoW6ip3O63GLBoICJ6FXpW7nVZtFg0EBM/CRE1oZ53KiFoICF6FiZrQyjqVEbUQELwKoSY0OSf/QEDwK4T3CdVZJ/tAQPArhPcJ9bJO9oGA4FiYGAZ1s07+gYDgV5gYBjEyRihTmBgGMTJGKFNgZIxQssDIGKFooZcNSlbRQEDwLXSzQak3xLkQEPwKY9mg1B3iXAgIfoVONiiNDXEuBAS/Qjv7TonOEOdCQPArtLLvlGgPcS4EBMdCk3unRGuIcyEgOBbq7DslmiHOhYDgV2BkjFCywMgYoWSBkTFCyQIjY4RChbHx/2m/mNSZwoGA4FfozDjWrhJ15scMAcGr0J5xrF0lBv80ISD4FVozjrWrxOCfJgQEx0LTGuJqcvanCQHBr1BPP9Z6ZH+aEBD8Cr1skU5eT7I1AgHBp9DNFunk9SRbIxAQfAqMjBFKFhgZI5QsMDJGKFlgZIxQsDCB1MbLRGVELQQEn8LEy0TPeJmojKiFgOBTmHiZ6BovE5URtRAQfAoTVxHyVSFvISA4FmZcRchXBcNCQPArzLiKkH+vkGEhIPgVZlxFMN4rlLcQEPwKXWOUNPHTVBnvtUBA8CkwMkYoWWBkjFCywMgYoWSBkTFCwUJ8E1FTGWXaiFoICB6F+DbRurLKdD5qISB4FOLbRHtD/DTlohYCgkch/hZxd4ifplzUQkBwKYTfIh4b4qdpwAMBwa8Qfou4M8RP04AHAoJfIRTp9hA/TfkqjoDgUAhFujXET1O+iiMgOBQYGSOULDAyRihZYGSMULLAyBihYGHMjFJWkEJA8Ct0zChlBSkEBL9C24xSVpBCQPArtMwoZQUpBATHQmO9l8J8JwUCgl+hNt9L0cx8vZAPBAS/wvQQ1RjXiSvjXisICF6Fid+Cb6q8lb/XCgKCV4GRMULJAiNjhJIFRsYIJQuMjBEKFuJttBqjSlRG1EJA8Ch0TM26kSICgl+hbWrWjRQREPwKLVOzbqSIgOBYaCzN/CQlBAS/Qm1qTWXdawUBwavQM7XauI8EAoJfoWtq+UESAoJngZExQskCI2OEkgVGxgglC4yMEQoW4ttAc0Ep90KDgOBZiG8DzQWl/MdIIiD4Fdrmz1PH+BhJBAS/Qsv8eTI+aQYBwbPQWD9Pxn0kEBA8C7X585S/jwQCgmehZ/485e8jgYDgWeiaP0/5+0ggIHgWGBkjlCwwMkYoWWBkjFCywMgYoWiha7xMhHFRtmwgIHgVxoyXiTguqrJRCgHBp9AxXibiuKjKRikEBJ9C27hOHF9PqmyUQkDwKbSM68RvTkxaQ0BwKzTGdeI3JSatISC4FWrjOvGbEtMADQHBq9AzrhO/KTEN0BAQvAqMjBFKFhgZI5QsMDJGKFlgZIxQsNDN3ijR+qDJGLUQEHwKY9kbJVofNImA4FvoZG+UaH3QJAKCb6GdvVFi603josFRCwHBp9DK30y0ScdFgzQEBLdCk/2kpDodFw3SEBDcCnX2k5J66bhooIaA4FXoZT8pqTvz9aIarCEgeBUYGSOULDAyRihZYGSMULLAyBihYKFb5W6UaH3QJAKCb2Gsyt0o0fqgSQQE30Knyt0o0fqgSQQE30K7yt0oMXsVDQHBvdCqsrfTbcwrzQgInoUmezvd2rzSjIDgWaizn5TUq6wrzQgInoUZ/7uVHyTlHwgIfgVGxgglC4yMEUoWGBkjlCwwMkYoWuhlg5L1QoOA4FvoZoPS2BDnQkDwK4xlg1JniHMhIPgVOtmg1B7iXAgIfoXsrUKb1hDnQkDwK7Ty75Ro7HMhIDgWmuw7JeohftgQEPwKdfadEr0hzoWA4FdgZIxQssDIGKFkgZExQskCI2OEQoVpBrKMeRoEBASvwlTjiIXMcyAgIHgVPmsc8U7zHAgICF6FBYwj5jLPgYCA4FWY1zhiSmOdAwEBwaswp3FEyxohVAgICF6FKRbxnHUAAgKCV6FlZYjdLAIBAcGtYGUILpUiIIyuMNU4gkulCAijK3CpFAGhXIFLpQgIxQpnGwdsYp4CAQHBq7CuccRi5jkQEBC8CosaR7zPPAcCAoJXYX7jiHnMcyAgIHgV3mEcMUdjnQMBAcGrMIdxRHuadQ4EBASvQtv4b0hrqnUOBAQEr0KrNogrrHMgICC4Fc42juBSKQLC6ApcKkVAKFfgUikCQrkCl0oREIoVjjMO2ME8BQICgldhdeOIJcxzICAgeBUWNo5Y0DwHAgKCV+HdxhHzmedAQEDwKsxtHDFnY50DAQHBq9AxjCm1dQ4EBASvQtswWmdb50BAQPAqtHoG8ZB1DgQEBLcCl0oREMoVuFSKgFCuwKVSBIRyBS6VIiAUKxxhHGB+xGCFgIDgVVjZOML+iEEEBASvwkeMI+yPGERAQPAq/I1xhP0RgwgICF6FsSZ/hP0RgwgICF6FTp0/YkpjnQMBAcGr0O7lj2hNs86BgIDgVWh1DeI56xwICAhuBS6VIiCUK3CpFAGhXIFLpQgI5QpcKkVAKFaYahxwqnkKBAQEr8JnjSPWNs+BgIDgVVjAOGIR8xwICAhehXmNI95jngMBAcGrMKdxxNyNdQ4EBASvwhTjiM406xwICAhehZbx35D2VOscCAgIbgXjvyEta4RYISAguBWs/4ZwqRQBYXQFLpUiIJQrcKkUAaFcgUulCAjFCusaB1xkngIBAcGrsKhxxAbmORAQELwK8xtHfNI8BwICglfhHcYR7zXPgYCA4FWYwxIa6xwICAhehbZhzFFb50BAQPAqtAyjfbZ1DgQEBLeCYbSsSwgVAgKCW4FLpQgI5QpcKkVAKFfgUikCQrkCl0oREIoVVjcOuMI8BQICgldhYeOITcxzICAgeBXebRyxmHkOBAQEr8LcxhHvM8+BgIDgVeg0+SPmMc+BgIDgVWjX+SPmaKxzICAgeBVaPUOYZp0DAQHBrXCcIUw1QwYCAoJXgUulCAjlClwqRUAoV+BSKQJCuQKXShEQihWsDwi62zwFAgKCV8H6gKBtzHMgICB4FawPCPqceQ4EBASvwpzGER8wz4GAgOBVmGL9F6SxzoGAgOBVaBnGnLV1DgQEBLfCNOO/ID0zZCAgIHgVphr/BTnOPAcCAoJXgUulCAjlClwqRUAoV+BSKQJCuQKXShEQihWsDwh6yDwFAgKCV8H6gKAdzHMgICB4FawPCFrCPAcCAoJXYQ7jiAXNcyAgIHgV2k3+iPnMcyAgIHgVWnX+iDkb6xwICAhuhbPzR0ypzf/IICAgeBXWNf4LcrZ5DgQEBK8Cl0oREMoVuFSKgFCuwKVSBIRyBS6VIiAUK1i/IPiceQoEBASvgvULgruZ50BAQPAqWL8guIx5DgQEBK+CdYfghcxzICAgeBWsOwS/0zwHAgKCW2Fa/oi5zHMgICC4FaYaCaIxz4GAgOBVMO4Q3JpmngMBAcGrwKVSBIRyBS6VIiCUK3CpFAGhXIFLpQgIxQpN1ZhIU+WOQkDwK9Tjf6xHnT0KAcGv0Bv/Yz162aMQEPwK3fE/1qObPQoBwa8wNv7Heoxlj0JA8Ct0xv9Yj072KAQEv0J7/I/1aGePQkDwK7TG/1iPVvYoBATHAiNjhJIFRsYIJQuMjBFKFhgZI5QsMDJGKFlgZIxQssDIGKFkgZExQsFCU1XGqHTiiMb4PgKCR6GuKmNUOnFEbXwfAcGj0KsqY1Q6cUTP+D4Cgkdh+pg0PyqdOKJrfB8BwaMwfUyaH5VOHDFmfB8BwaMwfUyaH5VOHNExvo+A4FGYPibNj0onjmgb30dA8ChMH5PmR6UTR7SM7yMguBSaipExQrlCXTEyRihX6FWMjBHKFRgZI5QsMDJGKFlgZIxQssDIGKFkgZExQsFCM2OjyRzYGMNUBAS/Qj1jq84cWRvDVAQEv0JvxlYvc2TPGKYiIPgVJoakuVFp1ximIiD4FSaGpLlR6ZgxTEVA8CtMDElzo9KOMUxFQPArTAxJc6PStjFMRUDwK0wMSXOj0pYxTEVAcCw0MzabzKGMjBFGV6hnbNWZIxkZI4yu0Jux1cscycgYYXQFRsYIJQuMjBFKFhgZI5QsMDJGKFlgZIxQsNCEzWbggY3xYaoICH6FOmzXA4+sjQ9TRUDwK/TCdm/gkT3jw1QREPwKcUSa/yjV/CAVAcGrEEek+Y9SzQ9SERC8CnFEmv8o1fwgFQHBqxBHpPmPUs0PUhEQvApxRJr/KNX8IBUBwa3QhB3NwEMZGSOMrlCH7XrgkYyMEUZX6IXt3sAjGRkjjK7AyBihZIGRMULJAiNjhJIFRsYIJQuMjBGKFoYZpDZD3EoFAcGjMMwgtR7iVioICB6FYQapvSFupYKA4FEYZpDaHeJWKggIHoVhBqljQ9xKBQHBozDMILUzxK1UEBA8CsMMUttD3EoFAcGjMMwgtTXErVQQEFwKjIwRShYYGSOULDAyRihZYGSMULLAyBihZIGRMULJAiNjhJIFRsYIBQtNZX2Qav4IBATPQl1ZH6SaPwIBwbPQq6wPUs0fgYDgWbA/SDV/BAKCZ8H+INX8EQgIngX7g1TzRyAgeBbsD1LNH4GA4FmwP0g1fwQCgmuhqRgZI5Qr1BUjY4RyhV7FyBihXIGRMULJAiNjhJIFRsYIJQuMjBFKFhgZIxQsNDN2NAMObCrrVioICJ6FOvn7rY+6sm6lgoDgWeglf7/10ausW6kgIHgWusnfb31MDEm72XMhIPgVxpK/3/qYGJKOZc+FgOBX6CR/v/UxMSTtZM+FgOBXaCd/v/UxMSRtZ8+FgOBXaCV/v/UxMSRtZc+FgOBYaJK/3/poKkbGCKMs1Mnfb33UFSNjhFEWesnfb330KkbGCKMsMDJGKFlgZIxQssDIGKFkgZExQskCI2OEooUme6OUJvybeyAg+BXq7I1S6vBv7oGA4FfoZW+U0gv/5h4ICH6FbvZGKflBaoWA4FwYy94oJT9IrRAQnAud7I1S8oPUCgHBudDO3iglP0itEBCcC63sjVLyg9QKAcG7wMgYoWSBkTFCyQIjY4SSBUbGCCULjIwRShYYGSOULDAyRihZYGSMULDQVLkbpeS+O/MoBASvQl3lbpSS+26FgOBe6FW5G6XkvlshILgXch+V2jU/aBUBwbeQ+6jUMfODVhEQfAu5j0rtmB+0ioDgW8h9VGrb/KBVBATfQu6jUlvmB60iIDgXmoqRMUK5Ql0xMkYoV+hVjIwRyhUYGSOULDAyRihZYGSMULLAyBihZIGRMULRQpMdlDaV/UBA8CvU2UFpPcS5EBD8Cr3soLQ3xLkQEPwK3eygtDvEuRAQ/Apj2UHp2BDnQkDwK3Syg9LOEOdCQPArtLOD0vYQ50JA8Cu0soPS1hDnQkBwLDAyRihZYGSMULLAyBihZIGRMULJAiNjhJIFRsYIJQuMjBFKFhgZIxQpNEMMknrjVWFw3EJA8CvUQwySph83OG4hIPgVekMMkqYf187+HCEg+BS6QwySph/Xyv4cISD4FMaGGCTNOFOT+zlCQPApdIYYJLWyhQIBwa/QHmaQ1OQKBQKCX6FVDfFO0jpXKBAQHAuMjBFKFhgZI5QsMDJGKFlgZIxQsNBU1gdp1AaHgOBXmPg3XxMqI2ohIHgVJv7N14TKiFoICF6FiX/zNaEyohYCgldh4t98TaiM91ogIHgVwr+ZR3uiUNSGhIDgUGjHujzw0ZooFD1DQkBwKLRiXa6MYVLXkBAQPApNxcgYoVyhrhgZI5Qr9CpGxgjlCoyMEQoWmsnDB7+U5H+YEBD8CvXkD8Pgl5L8DxMCgl9hoiR3skEpfzUNAcGvMPEa0c4GpfzVNAQEv8LEa0TuWnHHuJqGgOBX6KSFQT7axtU0BAS/QjstDPLRMq6mISD4FVppYRg0TKpzwyQEBMdCkxSGQcOkXsXIGGE0hTopDIOGSYyMEUZV6KWFYcAwiZExwqgKjIwRChaayc1cUOpmiwQCglchjog62aA0li0SCAhehTgiameDUidbJBAQvArxVaSVDUrtbJFAQPAqjPVHprc8OsanaSAg+BU6/ZHpLY+28WkaCAh+hXZ/ZHrLo2V8mgYCgl+h1R+Z1DAp+2kaCAiOhaY/MolhEiNjhFEV6v7IJIZJjIwRRlXo9UcmMUxiZIwwqgIjY4Sihdr4NYH4w1JloxQCgk+hZ/yaQPxhqbJRCgHBp9A1fk1gokbkX2wQELwKY8avCUzUiPzVZgQEr0LH+DWBUCOawccgIPgV2sZ9gkJhqAcfg4DgV2gZ9wmy6zQCgmOhMe4TZNdpBAS/AiNjhJIFRsYIJQuMjBFKFhgZIxQs1FX+1wTsD9NDQPAr5KXoWG82RUDwKeSl6FhvNkVA8CnkpehYd5tAQPAp5KVJpxl8LgQEv0JemnTqwedCQPAr5KV2WpcHPBAQHAtZqWUPkxAQPAt5qakYGSOMspCX6oqRMcIoC4yMEUoWGBkjFCzUk4cPDkq5ooGA4FmYKMmdbFAay54LAcGvkP+oyImg1MmeCwHBrzB5L4hMUGpnz4WA4FfozIxEA2tEK3suBAS/QjstDNLJ30sCAcGz0EoLg3ay95JAQHAtNElh0E7+XhIICJ6FOikM2mFkjDC6Qi8tDNJhZIwwugIjY4SSBUbGCEULPeNjgnNBCgHBu9A1PiY4F6QqBATnwpjxMcG5IFUhIDgXOsbHBOeCVIWA4FxoGx8THCJT5oGA4FdoZe+Ukn8nRVUhIDgXmtydUvLvpOgr1AgI/oQ6e6eUJkSm3AMBwa/AyBihZIGRMULJAiNjhJIFRsYIBQu9KvdG6dosGggInoXcreJq80ajCAi+hdyt4nrmjUYREHwLuVspds0bjSIg+BZynxUzZt5oFAHBt5D7rJiO+UljCAjOhabK3CnF+qQxBATnQmac1DI/aQwBwbmQGycxMkYYdYGRMULJAiNjhJIFRsYIRQvdbFCyigYCgm9hLBuUukOcCwHBr9DJBqWxIc6FgOBXaGeDUmeIcyEg+BVa2XdKtIc4FwKCY6HJvVOiNcS5EBAcC3X2nRLNEOdCQPAr9LLvlKiHOBcCgl+BkTFCyQIjY4SSBUbGCCULjIwRihTGhngh6Y2/XDSZFxkEBK9CZ4gXku6MYwe/yCAgeBXaQ7yQjM04dvCLDAKCV6E1xJXkTvb1BAHBsdDYV5Lb+dcTBAS/Qm1fSW7lX08QEPwKvSGuJDfZ1xMEBL9Cd4gryXX29QQBwa/AyBihZIGRMULJAiNjhJIFRsYIBQtjlXUj0a4xTkJA8Ct0KutGomPGOAkBwa/QrqwbiXaMcRICgl+hVVk3Em0b4yQEBMdCY91ItGWNkxAQ/Aq1+UliTWW91wIBwavQq6x7qdTGOAkBwa8wMQjKPWZYLWuUhIDgUWBkjFCywMgYoWSBkTFCyQIjY4SChbFYhjNBKV+mERD8Cp3JMjzYypdpBAS/QnuyDA+28mUaAcGv0EpeLwZZ+TKNgOBYaCqjKrSsMo2A4FeoZ75eDLKMMo2A4FfozXy9GGg1lfVeCwQEn0J35uvFQKvOWggIfgVGxgglC4yMEUoWGBkjlCwwMkYoWIiHNNmgVGdfSBAQvAqdyXFQLij1si8kCAhehfbkOCgXlLrZFxIEBK9Ca/L1IBeUckEKAcGx0FRGVWhZN9NFQPAr1G9OTMoybqaLgOBX6L05MUkr/2l6CAh+he6bE5O08p+mh4DgV2BkjFCywMgYoWSBkTFCyQIjY4Siha7xNtFQlrNlAwHBqzBmvE00luUqG6UQEHwKHeNtovZPEwKCX6Ft3CfC/mlCQPArGFeSG/unCQHBsdAY94kwf5oQEBwLtXGfCPunCQHBr9Az7hNh/zQhIPgVGBkjlCwwMkYoWWBkjFCywMgYoWAhfyO5eJutxigTCAg+hfyN5DqV/WHaCAh+hfyN5NqV/WHaCAh+hfwnybQq+8O0ERD8CsYnyTT2h2kjIDgWGuM+EfaHaSMg+BVq4z4RloWA4FnIj5K6poWA4FlgZIxQssDIGKFkgZExQskCI2OEgoX8RwVP/JDkXmgQEDwL+Y8Knvgh6WXPhYDgV8h/VPDEy0g3ey4EBL9C/qOCJ4LSWPZcCAh+BeOjghvzThMICJ6FZubrhXjU5p0mEBA8C/XM1wvxmB6h8neaQEDwLPRmvl4MGCTl7zSBgOBZYGSMULLAyBihZIGRMULJAiNjhKKFnvExkbkghYDgXegaHxOZC1IVAoJzYcz4mMhckKoQEJwLnewbpfNBqkJAcC5k30vRGO+kQEBwLrTyd0ppZo6TrJ8nBASHQtMya7T1biMEBLdCnb1TSm/ipyn7QEDwKzAyRihZYGSMULLAyBihZIGRMULBQq/KvVHautEcAoJvIad1zBvNISD4FnJa27zRHAKCbyGntcxPmkFA8C1ktcb8pBkEBNdCVqvNT5pBQPAtNNk7pdTmlWYEBM9Cnb1TSm6QhIDgX8hpjIwRRl1gZIxQssDIGKFkgZExQtFCnQ1K1gsNAoJvoZcNSt0hzoWA4FfoZoPS2BDnQkDwK4xlg1JniHMhIPgVOtl3SrSHOBcCgl+hnX2nRGuIcyEg+BVa+XdKNPa5EBAcC032nRL1ED9sCAh+BUbGCCULjIwRShYYGSOULDAyRihSsGr0PnbJQEBAcCpYY4QVhihZCAgIPgVrjPLhIUomAgKCT8EaI71riJKNgIDgU7DGaHNZFQIBAcGtYI0Rp0yzzoGAgOBVMO8i95x1DgQEBK+C+T7yX1kHICAguBWsDsGlUgSE0RVq4wgulSIgjK7QM47gUikCwugKXCpFQChWsALC9bMdMRAQEN6ughUQtpjtiIGAgPB2FayA8JnZjhgICAhvV8F6h+D7zXMgICB4Fax3CM7TWOdAQEDwKljvEJxjmnUOBAQEr4L1DsH2c9Y5EBAQ3ApGhmjtZpYMBAQEr0JtCFwqRUAYXaFnHMGlUgSE0RW4VIqAUK7ApVIEhGIFKyA8MdsRAwEB4e0qWAFhl9mOGAgICG9XwQoIS812xEBAQHi7CtY7BD9ongMBAcGrYL1DcL7GOgcCAoJXwXqH4JzTrHMgICC4FYz/hkyZaoYMBAQEr0Jt/BfkCvMcCAgIXoWeIXCpFAFhdAUulSIglCtwqRQBoVyBS6UICMUKVkB4ZbYjBgICwttVsALCHrMdMRAQEN6ughUQlpvtiIGAgPB2Fax3CH7IPAcCAoJXwXqH4Dsb6xwICAhuBcOYqzb/I4OAgOBVMIwpZ5vnQEBA8Cr0jATxkHkOBAQErwKXShEQyhW4VIqAUK7ApVIEhHIFLpUiIBQrWAHhV7MdMRAQEN6ughUQ9pntiIGAgPB2FayAsMJsRwwEBIS3q2C9Q/DD5jkQEBDcCk3+iHeZ50BAQHAr1Pkj5mrMcyAgIHgVevkjpkwzz4GAgOBVMN4h2HrOPAcCAoJXgUulCAjlClwqRUAoV+BSKQJCuQKXShEQihUak2jCn8HfR0DwKtTmkXX4M/j7CAhehZ55ZC/8Gfx9BASvQtc8shv+DP4+AoJXYcw8ciz8Gfx9BASvQsc8shP+DP4+AoJXoW0e2Q5/Bn8fAcGr0DKPbIU/g7+PgOBWaMxDGRkjjK5Qm0cyMkYYXaFnHsnIGGF0BUbGCCULjIwRShYYGSOULDAyRihZYGSMULTQZA9rqsoYtSIgeBbq7JF1VRmjVgQEz0Ive2SvqoxRKwKCZyE/Ku1WlTFqRUDwLORHpWNVZYxaERA8C/lRaaeqjFErAoJnIT8qbVeVMWpFQPAs5EelraoyRq0ICK6FJntoUzEyRhhloc4eWVeMjBFGWehlj+xVjIwRRllgZIxQssDIGKFkgZExQskCI2OEkgVGxghFC03mwGZyVFplj0JA8CrUmSPryVFplT0KAcGr0Msc2ZsclVbZoxAQvAr5MWp38u/cUQgIXoX8GHVs8u/cUQgIXoX8GLUz+XfuKAQEr0J+jNqe/Dt3FAKCVyE/Rm1N/p07CgHBrdBkDmVkjDDqQp05kpExwqgLvcyRjIwRRl1gZIxQssDIGKFkgZExQskCI2OEkgVGxghFC83AA5vJUWn+gYDgV6gHHllPjkrzDwQEv0Jv4JG9yVFp/oGA4FfIfZBqHJXmHwgIfoXcB6nGUWn+gYDgV8h9kGocleYfCAh+hdwHqcZRaf6BgOBXyH2QahyV5h8ICI6FZuChjIwRRl+oBx7JyBhh9IXewCMZGSOMvsDIGKFkgZExQskCI2OEkgVGxgglC4yMEYoWmgEHNuYHrSIg+BfqAUfW5getIiD4F3oDjuyZH7SKgOBfGDQq7ZoftIqA4F8YNCodMz9oFQHBvzBoVNoxP2gVAcG/MGhU2jY/aBUBwb8waFTaMj9oFQFhBIRmwKGMjBFKEOoBRzIyRihB6A04kpExQgkCI2OEkgVGxgglC4yMEUoWGBkjlCwwMkYoUGiGGCH1xovC4LCFgOBXqIcYIU0/rpOREBC8Cr0hRkjTj2tnJAQEr4I9RJ04rpWREBC8CvYQNZypGSwhIHgV7CFqOFM9WEJA8CrYQ9Rwpt5gCQHBqzDEELUJw6LBEgKCV6Exj2RkjDC6Qm0eycgYYXSFnnkkI2OE0RUYGSMULdRGTaiMqIWA4FnoGTWhMqIWAoJnoWvUhMqIWggInoUxoyZURtRCQPAsdIyaUBnvtUBA8CzkrxVPd/LvtUBA8CzkrxW3J+pynSsUCAiOhSZ3ZGuiLveyhQIBwa9QZ39UmoqRMcIoC73skXXFyBhhlAVGxgglC4yMEYoWetmgFF8qrB8nBASfQjcblOJLhfXjhIDgUxjL/hTFl4pqFl9sEBDe3kIn+1M0ea0480BA8Cu0sz9Fk9eKMw8EBL9CK/tT1J5ZGAY+EBAcC03up6g1szDkCjUCglehzv4UNZOFIVeoERC8Cr3sT1FdMTJGGGWBkTFCyQIjY4SSBUbGCEUL3WxQ6lX2HdUREPwKY9mg1K3sO6ojIPgVOtmgNPFSUc3iiw0CwttdaGeDUrhWnH0gIPgVWpnXkMlrxdkHAoJjoRn8GjJ5rTj/QEDwK9SDX0MmrxXnHwgIfoVe5jUkXivOPxAQ/AqMjBFKFhgZI5QsMDJGKFlgZIxQtDCWDUr2x4khIHgWOtmgNDbEuRAQ/ArtbFDqDHEuBAS/QisblNpDnAsBwbHQDEpQHfMXDRAQ3Av1oATVNu80hIDgXugNSlAt805DCAjuhe7ABNWYdxpCQPAuMDJGKFlgZIxQssDIGKFkgZExQoGC/SLSHf9hqTPDJgQEv4L9IjIWjv3fRiwEhLe/YL+IdMKx/9uIhYDw9hfsq8jtcOz/NmIhIDgQGrtCTBw78IGA4FeozR+VZuLYgQ8EBL9Czzyynjh24AMBwa9gX0XuTRybGSUhIHgVGBkjlCwwMkYoWWBkjFCywMgYoWiha7xMVEbUQkDwLIwZLxOVEbUQEDwLHeNlojKiFgKCZyFfFVqTVaGaxbKBgPB2Foyq0FTWey0QEDwLTfbIOr5XKK8hIDgV6uyR0yNS/r0WCAiehV72yG5VGe+1QEDwLDAyRihZYGSMULLAyBihZIGRMULRQi/7MhKqglmnERB8Ct3sy0isCladRkDwKYxlX0ZiVbDqNAKCT6GTDUrtydeLWSsbCAhvbyFbFZrW5OvFLJYNBIS3tZC9jlbH9wpZdRoBwanQZGt0HV8vcj9PCAhuhTpbo3vx9SL784SA4FXoZWs0I2OE0RYYGSOULDAyRihZYGSMULRQZ4NSr7JvqI6A4FfoZYNSt7JvqI6A4FfoZoPSWGXfUB0Bwa8wlg1Kncq+oToCgl8hUxWaWBWqWS0bCAhvc6GdKxHhvUL5BwKCX6GVKxHhvUL5BwKCY6HJlIjwXiHjgYDgV6gzJYKRMcKoC71MiWBkjDDqAiNjhJIFRsYIRQtNNihZLzQICL6FOhuUekOcCwHBr9DLBqXuEOdCQPArdLNBaWyIcyEg+BUGao35STMICO6FzuAI1bSG+Dh5BATPQntwhKrNO00gIDgXWoMjVM+80wQCgnehGRihGBkjjL5QD4xQjIwRRl/oDYxQjIwRRl9gZIxQpDDf+M/IgvXE1/T//aZfCpteoKdU1eR7hf5m/Ovd41/vCfumH/9/xr8+Mv710bDvs+Nffzf+9bnxr8XDvumvQ18c/1px/GulsG/d8a8vj3+tN/61ftg3dfzrq+NfW41/bR327Tb+9fXxr2+Mf+0e9k3Pdd8a/zpo/OvgsO+48a/vjH8dP/51Qth39vjX98a/zhn/Ojfsu2L86wfjX1eOf10V9k0b//rR+Net41+3hX0PjX/9ePzrJ+NfD4d9z41//Wz86+fjX8+Hfb8a//q/41//Pf7167Cv/7nr9D1384d96XO3cNiXPndLhH3pc7dy2Jc+dxuEfelzt03Ylz53e4R96XN3SNiXPncnhn3pc3de2Jc+d1eHfelzd3vYlz53j4R96XP3QtiXPne/CfvS526O8G/63L037Eufu4+Ffelzt2TYlz53q4R96XO3YdiXPnfbhn3pc7dn2Jc+d4eGfelzd1LYlz5354d96XN3TdiXPnd3hH3pc/do2Jc+dy+Gfelz99uwL33u5gz/O33u3hf2pc/dImFf+twtFfalz92qYV/63G0U9qXP3XZhX/rc7RX2pc/dYWFf+tydHPalz90FYV/63F0b9qXP3Z1hX/rcPRb2pc/dS2Ff+tz9Lvl5jc/dXGE7fe7eH/alz93Hw770uVs67Eufu9XCvvS52zjsS5+77cO+9LnbO+xLn7vDw770uTsl7EufuwvDvvS5uy7sS5+7u8K+9Ll7POxLn7uXw770ufu9eO7Gwr70uVsg7Eufu0XDvvS5WybsS5+71cO+9LnbJOxLn7sdwr70udsn7EufuyPCvvS5OzXsS5+7i8K+9Lm7PuxLn7u7w770uXsi7Eufu1fCvvS5+0PYlz53c4d96XP3gbAvfe4+Efalz92yYV/63K0R9qXP3aZhX/rc7Rj2pc/dvmFf+twdGfalz91pYV/63F0c9qXP3Q1hX/rc3RP2pc/dk2Ff+ty9Gvalz90fw770uXtH2Jc+dwuGfelz98mwL33ulgv70uduzbAvfe42C/vS526nsC997vYL+9Ln7qiwL33uTg/70ufukrAvfe5uDPvS5+7esC997p4K+9Ln7rWwL33u/hT2pc/dPGFf+tx9MOxLn7tPhX3pc7d82Jc+d2uFfelzt3nYlz53O4d96XO3f9iXPndHh33pc3dG2Jc+d5eGfelzd1PYlz5394V96XP3dNiXPnevh33pc/fnsC997uYN+9LnbqGwL33uFgv70uduhbAvfe7WDvvS526LsC997nYJ+9Ln7oCwL33ujgn70ufuzLAvfe4uC/vS5+7msC997u4P+9Ln7pmwL33u3gj70ufuL2Ff+tzNF/alz92Hwr70uft02Jc+d58P+9Lnbp2wL33utgz70udu17Avfe4ODPvS5+7YsC997s4K+9Ln7vKwL33ubgn70ufugbAvfe6eDfvS5+4XYV/63P017Eufu3eGfelz9+GwL33uPhP2pc/dF8K+9Ln7UtiXPndfCfvS5+5rYV/63H0z7Eufu2+Hfelz992wL33uvh/2pc/dD8O+9Ll7MOxLn7ufhn3pc/fLsC997v5H9N+O6L/zi/67sOi/S4j+u7LovxuI/ruN6L97iP57iOi/J4r+e57ov1eL/nu76L+PiP77gui/vxH9dw7Rf98r+u/HRP9dUvTfVUT/3VD0321F/91T9N9DRf89SfTf80X/vUb03ztE/31U9N8XRf/9rei/c4r++z7RfxcR/Xcp0X9XFf13I9F/txP9dy/Rfw8T/fdk0X8vEP33WtF/7xT99zHRf18S/fd3ov/OJfrv+0X//bjov0uL/rua6L8bi/67vei/e4v+e7jov6eI/nuh6L/Xif57l+i/j4v++7Lov78X/XdM9N8FRP9dVPTfZUT/XV30301E/91B9N99RP89QvTfU0X/vUj03+tF/71b9N8nRP99RfTfP4j+O7fovx8Q/fcTov8uK/rvGqL/bir6746i/+4r+u+Rov+eJvrvxaL/3iD67z2i/z4p+u+rov/+UfTfd4j+u6Dov58U/Xc50X/XFP13M9F/dxL9dz/Rf48S/fd00X8vEf33RtF/7xX99ynRf18T/fdPov/OI/rvB0X//ZTov8uL/ruW6L+bi/67s+i/+4v+e7Tov2eI/nup6L83if57n+i/T4v++7rov38W/Xde0X8XEv13MdF/VxD9d23Rf7cQ/XcX0X8PEP33GNF/zxT99zLRf28W/fd+0X+fEf33DdF//yL673yi/35I9N9Pi/77edF/1xH9d0vRf3cV/fdA0X+PFf33LNF/Lxf99xbRfx8Q/fdZ0X9/IfrvX0X/fafovx8W/fczov9+QfTfL4n++xXRf78m+u83Rf/9tui/3xX99/ui//5Q9N8HRf/9qei/vxT9939E/32X6L//n+i/fyv679+L/vsPov/+o+i//yT67z+L/vsvov/+q+i//yb677+L/vsfov/+p+i//yX67//r679TRP99j+i/HxX9d3HRf1cS/Xd90X+3Fv13d9F/Dxb99wTRf88V/fcq0X9vE/33YdF/nxf999ei/3ZE/51f9N+FRf9dQvTflUX/3UD0321E/91D9N9DRP89UfTf80T/vVr039tF/31E9N8XRP/9jei/c4j++17Rfz8m+u+Sov+uIvrvhqL/biv6756i/x4q+u9Jov+eL/rvNaL/3iH676Oi/74o+u9vRf+dU/Tf94n+u4jov0uJ/ruq6L8bif67nei/e4n+e5jovyeL/nuB6L/Xiv57p+i/j4n++5Lov78T/Xcu0X/fL/rvx0X/XVr039VE/91Y9N/tRf/dW/Tfw0X/PUX03wtF/71O9N+7RP99XPTfl0X//b3ov2Oi/y4g+u+iov8uI/rv6qL/biL67w6i/+4j+u8Rov+eKvrvRaL/Xi/6792i/z4h+u8rov/+QfTfuUX//YDov58Q/XdZ0X/XEP13U9F/dxT9d1/Rf48U/fc00X8vFv33BtF/7xH990nRf18V/fePov++Q/TfBUX//aTov8uJ/rum6L+bif67k+i/+4n+e5Tov6eL/nuJ6L83iv57r+i/T4n++5rov38S/Xce0X8/KPrvp0T/XV7037VE/91c9N+dRf/dX/Tfo0X/PUP030tF/71J9N/7RP99WvTf10X//bPov/OK/ruQ6L+Lif67gui/a4v+u4Xov7uI/nuA6L/HiP57pui/l4n+e7Pov/eL/vuM6L9viP77F9F/5xP990Oi/35a9N/Pi/67jui/W4r+u6vovweK/nus6L9nif57uei/t4j++4Dov8+K/vsL0X//KvrvO0X//bDov58R/fcLov9+SfTfr4j++zXRf78p+u+3Rf/9rui/3xf994ei/z4o+u9PRf/9pei//9PXf9ui/75b9N+PiP77OdF/VxT9dz3Rf7cS/fcbov8eJPrv8aL/niP675Wi/94q+u9PRP/9uei//y367xTRf98j+u9HRf9dXPTflUT/XV/0361F/91d9N+DRf89QfTfc0X/vUr039tE/31Y9N/nRf/9tei/HdF/5xf9d2HRf5cQ/Xdl0X83EP13G9F/9xD99xDRf08U/fc80X+vFv33dtF/HxH99wXRf38j+u8cov++V/Tfj4n+u6Tov6uI/ruh6L/biv67p+i/h4r+e5Lov+eL/nuN6L93iP77qOi/L4r++1vRf+cU/fd9ov8uIvrvUqL/rir670ai/24n+u9eov8eJvrvyaL/XiD677Wi/94p+u9jov++JPrv70T/nUv03/eL/vtx0X+XFv13NdF/Nxb9d3vRf/cW/fdw0X9PEf33QtF/rxP99y7Rfx8X/fdl0X9/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if57vei/d4v++4Tov6+I/vsH0X/XEP13U9F/dxT9d1/Rf48U/fc00X8vFv33BtF/7xH990nRf18V/fePov+uKfrvZqL/7iT6736i/x4l+u/pov9eIvrvjaL/3iv671Oi/74m+u+fRP9dS/TfzUX/3Vn03/1F/z1a9N8zRP+9VPTfm0T/vU/036dF/31d9N8/i/67tui/W4j+u4vovweI/nuM6L9niv57mei/N4v+e7/ov8+I/vuG6L9/Ef13HdF/txT9d1fRfw8U/fdY0X/PEv33ctF/bxH99wHRf58V/fcXov/+VfTfL4n++xXRf78m+u83Rf/9tui/3xX99/ui//5Q9N8HRf/9qei/vxT9939E//0H0X//UfTffxL9959F//0X0X//VfTffxP9999F//0P0X//U/Tf/xL9t//672dF/61F/11X9N+pov/uJvpvI/rvcaL/ni367xWi/04T/fch0X+fE/3370T//aLov18W/ferov9+XfTfb4n++x3Rf78n+u8PRP/9kei/Pxb992ei/35O9N8VRf9dT/TfrUT//YbovweJ/nu86L/niP57pei/t4r++xPRf38u+u/iov+uJPrv+qL/bi367+6i/x4s+u8Jov+eK/rvVaL/3ib678Oi/z4v+u8Sov+uLPrvBqL/biP67x6i/x4i+u+Jov+eJ/rv1aL/3i767yOi/74g+u+Sov+uIvrvhqL/biv6756i/x4q+u9Jov+eL/rvNaL/3iH676Oi/74o+u9Sov+uKvrvRqL/bif6716i/x4m+u/Jov9eIPrvtaL/3in672Oi/74k+u/Sov+uJvrvxqL/bi/6796i/x4u+u8pov9eKPrvdaL/3iX67+Oi/74s+u8yov+uLvrvJqL/7iD67z6i/x4h+u+pov9eJPrv9aL/3i367xOi/74i+u+yov+uIfrvpqL/7ij6776i/x4p+u9pov9eLPrvDaL/3iP675Oi/74q+u9yov+uKfrvZqL/7iT6736i/x4l+u/pov9eIvrvjaL/3iv671Oi/74m+u/yov+uJfrv5qL/7iz67/6i/x4t+u8Zov9eKvrvTaL/3if679Oi/74u+u8Kov+uLfrvFqL/7iL67wGi/x4j+u+Zov9eJvrvzaL/3i/67zOi/74h+u/nRf9dR/TfLUX/3VX03wNF/z1W9N+zRP+9XPTfW0T/fUD032dF//2F6L9fEP33S6L/fkX036+J/vtN0X+/Lfrvd0X//b7ovz8U/fdB0X9/KvrvL0X//XvRf/9B9N9/FP33n0T//WfRf/9F9N9/Ff3330T//XfRf/9D9N//FP33v/r6b0/038+K/luL/ruu6L9TRf/dTfTfRvTf40T/PVv03ytE/50m+u9Dov/+H9F//0703y+K/vtl0X+/Kvrv10X//Zbov98R/fd7ov/+QPTfH4n++2PRfz8i+u/nRP9dUfTf9UT/3Ur032+I/nuQ6L/Hi/57jui/V4r+e6vovz8R/fejov8uLvrvSqL/ri/679ai/+4u+u/Bov+eIPrvuaL/XiX6722i/z4s+u/Cov8uIfrvyqL/biD67zai/+4h+u8hov+eKPrveaL/Xi367+2i/z4i+u/HRP9dUvTfVUT/3VD0321F/91T9N9DRf89SfTf80X/vUb03ztE/31U9N9FRP9dSvTfVUX/3Uj03+1E/91L9N/DRP89WfTfC0T/vVb03ztF/31M9N+Pi/67tOi/q4n+u7Hov9uL/ru36L+Hi/57iui/F4r+e53ov3eJ/vu46L+Liv67jOi/q4v+u4novzuI/ruP6L9HiP57qui/F4n+e73ov3eL/vuE6L+fEP13WdF/1xD9d1PRf3cU/Xdf0X+PFP33NNF/Lxb99wbRf+8R/fdJ0X8/KfrvcqL/rin672ai/+4k+u9+ov8eJfrv6aL/XiL6742i/94r+u9Tov9+SvTf5UX/XUv0381F/91Z9N/9Rf89WvTfM0T/vVT035tE/71P9N+nRf9dTPTfFUT/XVv03y1E/91F9N8DRP89RvTfM0X/vUz035tF/71f9N9nRP/9tOi/nxf9dx3Rf7cU/XdX0X8PFP33WNF/zxL993LRf28R/fcB0X+fFf33M6L/fkH03y+J/vsV0X+/JvrvN0X//bbov98V/ff7ov/+UPTfB0X//anov38r+u/fi/77D6L//qPov/8k+u8/i/77L6L//qvov/8m+u+/i/77H6L//mdf/x3l3/WdJvrvKP+u749E/x3l3/W9VfTfUf5d39tE/x3l3/W9XfTfUf5d3ztE/x3l3/W9U/TfUf5d37tE/x3l3/W9W/TfUf5d33tE/x3l3/W9V/TfUf5d3/tE/x3l3/W9X/TfUf5d3wdE/x3l3/V9UPTfUf5d3/8Q/bcKnfAj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+v9u6v8Hqf8fp/5/lvr/5er/t6j/36v+/zP1//9U/3+B+v+r1f/nV/9fXP1/ZfX/9dX/t1b/3139/2D1/+PV/89W/79C/f9W9f/71P9/rv7/X+r/L1T/f436/wLq/0uo/6+i/r+B+v826v97qP8fov5/gvr/Oer/V6r/z1T/v1/9/0n1/9+o/79I/f+16v8Lqv8vqf6/qvr/hur/26r/76n+f6j6/4nq/+eq/1+l/j9L/f8B9f+n1P9/q/7/YvX/16n/L6T+v5T6/2rq/xup/2+n/r+X+v9h6v8nqf+fp/5/tfr/ber/D6r/P63+/zv1/5eo/79e/f8d6v9Lq/+vrv6/sfr/9ur/e6v/H67+f7L6//nq/9eo/9+u/v+Q+v8v1P9/r/7/UvX/N6j/v1P9fxn1/zXU/zdR/99B/X8f9f8j1P9PUf+/QP3/WvX/O9T/H1b/f0b9/w/q/y9T/3+j+v/C6v/Lqv+vqf6/qfr/jur/+6r/H6n+f6r6/4Xq/9ep/9+p/v+I+v+z6v9/VP9/ufr/m9T/36X+v5z6/1rq/5up/++k/r+f+v9R6v+nqf9fpP5/vfr/Xer/j6r//1L9/0/q/69Q/3+z+v+71f+XV/9fW/1/c/X/ndX/91f/P1r9/3T1/4vV/29Q/5+t/v+Y+v9z6v9/Vv9/pfr/fOr/i6j/r6D+v476/xbq/7uo/x+g/n+M+v8Z6v+XqP/fqP4/R/3/cfX/59X//6L+/yr1/7eo/y+q/r+i+v+66v9bqv/vqv5/oPr/ser/Z6r/X6r+f5P6/93q/0+o//9K/f+v6v+T6v//pP7/dvX/xdT/V1L/X0/9fyv1/93U/w9S/z9O/f8s9f/L1f9vUf+/V/3/Z+r//6n+/wL1/1er/8+v/r+4+v/K6v/rq/9vrf6/u/r/wer/x6v/n63+f4X6/63q//ep//9c/f+/1P9fqP7/GvX/BdT/l1D/X0X9fwP1/23U//dQ/z9E/f8E9f9z1P+vVP+fqf5/v/r/k+r/v1H/f5H6/2vV/xdU/19S/X9V9f8N1f+3Vf/fU/3/UPX/E9X/z1X/v0r9f5b6/wPq/0+p//9W/f/F6v+vU/9fSP1/KfX/1dT/N1L/3079fy/1/8PU/09S/z9P/f9q9f/b1P8fVP9/Wv3/d+r/L1H/f736/zvU/5dW/19d/X9j9f/t1f/3Vv8/XP3/ZPX/89X/r1H/v139/yH1/1+o//9e/f+l6v9vUP9/p/r/Mur/a6j/b6L+v4P6/z7q/0eo/5+i/n+B+v+16v93qP8/rP7/jPr/H9T/X6b+/0b1/4XV/5dV/19T/X9T9f8d1f/3Vf8/Uv3/VPX/C9X/r1P/v1P9/xH1/2fV//+o/v9y9f83qf+/S/1/OfX/tdT/N1P/30n9fz/1/6PU/09T/79I/f969f+71P8fVf//pfr/n9T/X6H+/2b1/3er/y+v/r+2+v/m6v87q//vr/5/tPr/6er/F6v/36D+P1v9/zH1/+fU//+s/v9K9f/51P8XUf9fQf1/HfX/LdT/d1H/P0D9/xj1/zPU/y9R/79R/X+O+v/j6v/Pq///Rf3/Ver/b1H/X1T9f0X1/3XV/7dU/99V/f9A9f9j1f/PVP+/VP3/JvX/u9X/n1D//5X6/1/V/18g/18t/+eX/4vL/5Xl//ryf2v5v7v8P1j+Hy//z5b/V8j/W+X/ffL/5/L/v+T/C+X/a+T/AvJ/Cfm/ivzfQP5vI//3kP+HyP8T5P858v9K+T9T/t8v/5+U/7+R/y+S/6+V/wvK/yXl/6ryf0P5v63831P+Hyr/T5T/58r/q+T/LPn/gPx/Sv7/Vv6/WP6/Tv4vJP+Xkv+ryf+N5P928n8v+X+Y/D9J/p8n/6+W/7fJ/wfl/9Py/3fy/yXy//Xy/x3yf2n5v7r831j+by//95b/h8v/k+X/+fL/Gvl/u/x/SP7/Qv7/Xv6/VP6/Qf6/U/4vI//XkP+byP8d5P8+8v8I+X+K/L9A/l8r/++Q/w/L/2fk/x/k/8vk/xvl/8Lyf1n5v6b831T+7yj/95X/R8r/U+X/hfL/Ovl/p/x/RP4/K///KP9fLv/fJP/fJf+Xk/9ryf/N5P9O8n8/+X+U/D9N/l8k/6+X/3fJ/0fl/y/l/5/k/yvk/5vl/7vl//Lyf235v7n831n+7y//j5b/p8v/i+X/DfJ/tvx/TP4/J///LP9fKf/nk/+LyP8V5P868n8L+b+L/D9A/h8j/8+Q/5fI/xvl/xz5/7j8f17+/0X+v0r+v0X+Lyr/V5T/68r/LeX/rvL/QPl/rPw/U/5fKv9vkv93y/8n5P+v5P9f5f/fyf9/lv/vkf/vk/8fkv8fk/+fkv+fk/9fkv9fk/+Xyf/vyv975P+P5f+v5f9/w//p79+k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35T/35H/N8v/H8n/n8r//yn/V5L/68n/reT/bvL/IPl/nPw/S/5fLv9vkf/3yv+fyf//lP8ry//15f/W8n93+X+w/D9e/p8t/6+Q/7fK//vk/8/l/3/J/1Xk/wbyfxv5v4f8P0T+nyD/z5H/V8r/mfL/fvn/pPz/jfxfVf5vKP+3lf97yv9D5f+J8v9c+X+V/J8l/x+Q/0/J/9/K/9Xk/0byfzv5v5f8P0z+nyT/z5P/V8v/2+T/g/L/afn/O/m/uvzfWP5vL//3lv+Hy/+T5f/58v8a+X+7/H9I/v9C/v9e/q8h/zeR/zvI/33k/xHy/xT5f4H8v1b+3yH/H5b/z8j/P8j/NeX/pvJ/R/m/r/w/Uv6fKv8vlP/Xyf875f8j8v9Z+f9H+b+W/N9M/u8k//eT/0fJ/9Pk/0Xy/3r5f5f8f1T+/1L+/0n+ry3/N5f/O8v//eX/0fL/dPl/sfy/Qf7Plv+Pyf/n5P+f5f868n8L+b+L/D9A/h8j/8+Q/5fI/xvl/xz5/7j8f17+/0X+ryv/t5T/u8r/A+X/sfL/TPl/qfy/Sf7fLf+fkP+/kv9/lf8fkv8fk/+fkv+fk/9fkv9fk/+Xyf/vyv975P+P5f+v5f9/y/8Py/+Py/9Py//Py/8vy/+vy/9vyf/vyf8fyP+fyP9/l/95/udW9f9/Vf//gPr/R9T/P6H+/xn1/y+o/39F/f8b6v/fVv//vvr/D9X/36b+/171/w+q/39U/f+T6v+fVf//ovr/V9X/v6n+/x31/5vV/3+k/v929f/F1P9XUv9fT/1/K/X/3dT/D1L/P079/yz1/8vV/29R/79X/X9+9f/F1f9XVv9fX/1/a/X/3dX/D1b/P179/2z1/yvU/29V/79P/X8B9f8l1P9XUf/fQP1/G/X/PdT/D1H/P0H9/xz1/yvV/2eq/9+v/r+g+v+S6v+rqv9vqP6/rfr/nur/h6r/n6j+f676/1Xq/7PU/x9Q/19I/X8p9f/V1P83Uv/fTv1/L/X/w9T/T1L/P0/9/2r1/9vU/x9U/3+H+v/S6v+rq/9vrP6/vfr/3ur/h6v/n6z+f776/zXq/7er/z+k/v9O9f9l1P/XUP/fRP1/B/X/fdT/j1D/P0X9/wL1/2vV/+9Q/39Y/X9h9f9l1f/XVP/fVP1/R/X/fdX/j1T/P1X9/0L1/+vU/+9U/39E/f9d6v/Lqf+vpf6/mfr/Tur/+6n/H6X+f5r6/0Xq/9er/9+l/v+o+v+71f+XV/9fW/1/c/X/ndX/91f/P1r9/3T1/4vV/29Q/5+t/v+Y+v8i6v8rqP+vo/6/hfr/Lur/B6j/H6P+f4b6/yXq/zeq/89R/39c/X9R9f8V1f/XVf/fUv1/V/X/A9X/j1X/P1P9/1L1/5vU/+9W/39C/f896v/vU///kPr/x9T/P6X+/zn1/y+p/39N/f8y9f/vqv/fo/7/Y/X/f1H/f7/6/4fV/z+u/v9p9f/Pq/9/Wf3/6+r/31L//576/w/U/3+i/v9W9f9/Vf//gPr/R9T/P6H+/xn1/y+o/39F/f8b6v/fVv//vvr/D9X/36b+/171/w+q/39U/f+T6v+fVf//ovr/V9X/v6n+/x31/5vV/3+k/v929f/F1P9XUv9fT/1/K/X/3dT/D1L/P079/yz1/8vV/29R/79X/X9+9f/F1f9XVv9fX/1/a/X/3dX/D1b/P179/2z1/yvU/29V/79P/X8B9f8l1P9XUf/fQP1/G/X/PdT/D1H/P0H9/xz1/yvV/2eq/9+v/r+g+v+S6v+rqv9vqP6/rfr/nur/h6r/n6j+f676/1Xq/7PU/x9Q/19I/X8p9f/V1P83Uv/fTv1/L/X/w9T/T1L/P0/9/2r1/9vU/x9U/3+H+v/S6v+rq/9vrP6/vfr/3ur/h6v/n6z+f776/zXq/7er/z+k/v9O9f9l1P/XUP/fRP1/B/X/fdT/j1D/P0X9/wL1/2vV/+9Q/39Y/X9h9f9l1f/XVP/fVP1/R/X/fdX/j1T/P1X9/0L1/+vU/+9U/39E/f9d6v/Lqf+vpf6/mfr/Tur/+6n/H6X+f5r6/0Xq/9er/9+l/v+o+v+71f+XV/9fW/1/c/X/ndX/91f/P1r9/3T1/4vV/29Q/5+t/v+Y+v8i6v8rqP+vo/6/hfr/Lur/B6j/H6P+f4b6/yXq/zeq/89R/39c/X9R9f8V1f/XVf/fUv1/V/X/A9X/j1X/P1P9/1L1/5vU/+9W/39C/f896v/vU///kPr/x9T/P6X+/zn1/y+p/39N/f8y9f/vqv/fo/7/Y/X/f1H/f7/6/4fV/z+u/v9p9f/Pq/9/Wf3/6+r/31L//576/w/U/3+i/v+v8v8D8v8j8v8T8v8z8v8L8v8r8v8b8v/b8v/78v+H8v/f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")
                .append("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")
                .toString();
    }
}
