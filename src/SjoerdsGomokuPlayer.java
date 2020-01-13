import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

public final class SjoerdsGomokuPlayer {
    private static final long START_UP_TIME = System.nanoTime();

    private final IO io;
    private final PatternMatchMoveGenerator moveGenerator;

    public static void main(String[] args) throws IOException, DataFormatException {
        final IO io = new IO(System.in, System.out, System.err, true);
        final SjoerdsGomokuPlayer player = new SjoerdsGomokuPlayer(io);
        player.play();
    }

    SjoerdsGomokuPlayer(final IO io) throws DataFormatException {
        this.io = io;
        this.moveGenerator = new PatternMatchMoveGenerator(io.moveConverter, io.dbgPrinter, io.timer);
    }

    void play() throws IOException, DataFormatException {
        boolean mustTryLoadCache = true;

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

            // TODO: Load cache here
            mustTryLoadCache = false;

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

            if (mustTryLoadCache) {
                if (move == Move.SWITCH) DataReader.loadOwnOpeningBookSwitch(moveGenerator.calcCache);
                else DataReader.loadOwnOpeningBookStraight(moveGenerator.calcCache);
                mustTryLoadCache = false;
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
                    log(type + " " + moveStr);
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
            return String.format("%6.5f s", ((double) nanos) / 1E9D);
        }
    }

    static class PatternMatchMoveGenerator {
        private static final int MAX_SCORE = Integer.MAX_VALUE;
        private static final int MIN_SCORE = -MAX_SCORE;
        private static final int UNCERTAINTY = 21474836;

        private static final int PLAYER = 0;
        private static final int OPPONENT = 1;
        private static final int FIELD_IDX = 0;
        private static final int SCORE = 1;

        @SuppressWarnings("MismatchedReadAndWriteOfArray")
        private static final byte[] NIL_COUNTS = new byte[256];

        private static final int MAX_MOVES = 125;

        Map<Board, CalcResult> calcCache = new HashMap<>(100_000);

        private final MoveConverter moveConverter;
        private final DbgPrinter dbgPrinter;
        private final Timer timer;
        private final Patterns patterns = DataReader.getPatterns();

        long maxNanos = 4 * 1_000_000_000L;
        int maxDepth = 16;
        int[][] killerMoves;

        PatternMatchMoveGenerator(final MoveConverter moveConverter, final DbgPrinter dbgPrinter, final Timer timer)
                throws DataFormatException {
            this.moveConverter = moveConverter;
            this.dbgPrinter = dbgPrinter;
            this.timer = timer;
        }

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

        public Move generateMove(final Board board) {
            long now = System.nanoTime();

            killerMoves = new int[2][maxDepth];
            Arrays.fill(killerMoves[0], -1);
            Arrays.fill(killerMoves[1], -1);

            final long remainingNanos = maxNanos - (timer.totalTime + now - timer.timerStart);
            int remainingMoves = MAX_MOVES - board.moves;

            long availableTime = (remainingNanos <= 0 || remainingMoves <= 0) ? 0 : remainingNanos / remainingMoves;
            final long maxNanoTime = now + availableTime;

            int[] fieldIdxAndScore = null;
            for (int searchDepth = 1; searchDepth <= maxDepth; searchDepth++) {
                final int[] newInts =
                        minimax(board, board.playerToMove == Board.PLAYER, 0, searchDepth, maxNanoTime, MIN_SCORE,
                                MAX_SCORE);

                if (newInts == null) break;

                fieldIdxAndScore = newInts;

                dbgPrinter.log("Depth " + searchDepth + ": best mv: " + fieldIdxAndScore[FIELD_IDX] + "; score: " +
                        fieldIdxAndScore[SCORE] + "; time left: " +
                        DbgPrinter.timeFmt(maxNanoTime - System.nanoTime()) + "; cache: " + calcCache.size());
            }

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

            List<Integer> moves = new ArrayList<>(calcResult.moves.size() + 2);
            if (killerMoves[0][level] >= 0 && board.validMove(moveConverter.toMove(killerMoves[0][level]))) {
                moves.add(killerMoves[0][level]);
            }
            if (killerMoves[1][level] >= 0 && board.validMove(moveConverter.toMove(killerMoves[1][level]))) {
                moves.add(killerMoves[1][level]);
            }
            moves.addAll(calcResult.moves);

            int[] retval = new int[]{moves.get(0), isPlayer ? MIN_SCORE : MAX_SCORE};
            for (int move : moves) {
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
                    killerMoves[1][level] = killerMoves[0][level];
                    killerMoves[0][level] = move;
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

            final List<Map.Entry<Integer, Integer>> genMoves = IntStream.range(0, 255)
                    .mapToObj(i1 -> Map.entry(i1, scores[i1]))
                    .filter(e -> e.getValue() > 0)
                    .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
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
                    (opportunityCounts[onMove][2] - Math.max(opportunityCounts[offMove][2] - 1, 0)) * 100;
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

        static final Move[] OPENING = {new Move(new long[]{0, 0x100, 0, 0}), // Hh
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
            final ByteBuffer byteBuffer = uncompress(patternsString, uncompressedSize);

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

        private static ByteBuffer uncompress(final String dataString, final int uncompressedSize)
                throws DataFormatException {
            final byte[] decode = Base64.getDecoder()
                    .decode(dataString);

            final Inflater inflater1 = new Inflater(true);
            inflater1.setInput(decode);
            final ByteBuffer byteBuffer1 = ByteBuffer.allocate(uncompressedSize);
            int len = inflater1.inflate(byteBuffer1);
            if (!inflater1.finished()) {
                throw new AssertionError();
            }
            inflater1.end();

            byte[] intermediate = new byte[len];
            byteBuffer1.rewind();
            byteBuffer1.get(intermediate);

            Inflater inflater2 = new Inflater(true);
            inflater2.setInput(intermediate);
            ByteBuffer byteBuffer2 = ByteBuffer.allocate(uncompressedSize);
            inflater2.inflate(byteBuffer2);
            if (!inflater2.finished()) {
                throw new AssertionError();
            }
            inflater2.end();

            byteBuffer2.rewind();
            return byteBuffer2;
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

        public static void loadOwnOpeningBookStraight(Map<Board, CalcResult> calcCache) throws DataFormatException {
            loadOwnOpeningBook(calcCache, true, Data.OWN_OPENING_BOOK, Data.OWN_OPENING_BOOK_UNCOMPRESSED_SIZE);
        }

        public static void loadOwnOpeningBookSwitch(Map<Board, CalcResult> calcCache) throws DataFormatException {
            loadOwnOpeningBook(calcCache, false, Data.OWN_OPENING_BOOK, Data.OWN_OPENING_BOOK_UNCOMPRESSED_SIZE);
        }

        static void loadOwnOpeningBook(final Map<Board, CalcResult> calcCache, final boolean flip,
                final String ownOpeningBookString, final int uncompressedSize) throws DataFormatException {
            ByteBuffer buffer = uncompress(ownOpeningBookString, uncompressedSize);

            final long longBufferLen = buffer.getLong();
            final long intBufferLen = buffer.getLong();
            final long count = buffer.getLong();

            final LongBuffer longBuffer = buffer.asLongBuffer();
            longBuffer.limit((int) longBufferLen);
            buffer.position(buffer.position() + (int) longBufferLen * Long.BYTES);
            final IntBuffer intBuffer = buffer.asIntBuffer();
            intBuffer.limit((int) intBufferLen);
            buffer.position(buffer.position() + (int) intBufferLen * Integer.BYTES);

            for (int i = 0; i < count; i++) {
                Board board = readBoard(longBuffer, intBuffer);
                CalcResult calcResult = readCalcResult(intBuffer, buffer);

                if (flip) {
                    board.flip();

                    byte[] swap;

                    if (calcResult.match4 != null) {
                        swap = calcResult.match4[0];
                        calcResult.match4[0] = calcResult.match4[1];
                        calcResult.match4[1] = swap;
                    }

                    if (calcResult.match3 != null) {
                        swap = calcResult.match3[0];
                        calcResult.match3[0] = calcResult.match3[1];
                        calcResult.match3[1] = swap;
                    }

                    if (calcResult.match2 != null) {
                        swap = calcResult.match2[0];
                        calcResult.match2[0] = calcResult.match2[1];
                        calcResult.match2[1] = swap;
                    }

                    if (calcResult.match1 != null) {
                        swap = calcResult.match1[0];
                        calcResult.match1[0] = calcResult.match1[1];
                        calcResult.match1[1] = swap;
                    }
                }

                calcCache.put(board, calcResult);
            }

            if (buffer.position() != buffer.capacity()) {
                System.err.println(buffer.position() + " :: " + buffer.capacity());
                throw new AssertionError();
            }
        }

        private static Board readBoard(final LongBuffer longBuffer, final IntBuffer intBuffer) {
            Board board = new Board();
            board.playerToMove = intBuffer.get();
            board.moves = intBuffer.get();

            longBuffer.get(board.playerStones);
            longBuffer.get(board.opponentStones);

            return board;
        }

        private static CalcResult readCalcResult(final IntBuffer intBuffer, final ByteBuffer buffer) {
            CalcResult calcResult = new CalcResult();

            byte bools = buffer.get();

            if ((bools & 1) == 1) {
                calcResult.match4 = new byte[2][256];
                buffer.get(calcResult.match4[0]);
                buffer.get(calcResult.match4[1]);
            }
            if ((bools & 2) == 2) {
                calcResult.match3 = new byte[2][256];
                buffer.get(calcResult.match3[0]);
                buffer.get(calcResult.match3[1]);
            }
            if ((bools & 4) == 4) {
                calcResult.match2 = new byte[2][256];
                buffer.get(calcResult.match2[0]);
                buffer.get(calcResult.match2[1]);
            }
            if ((bools & 8) == 8) {
                calcResult.match1 = new byte[2][256];
                buffer.get(calcResult.match1[0]);
                buffer.get(calcResult.match1[1]);
            }

            if ((bools & 16) == 16) {
                int count = intBuffer.get();
                calcResult.moves = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    calcResult.moves.add(intBuffer.get());
                }
            }

            return calcResult;
        }
    }

    @SuppressWarnings("StringBufferReplaceableByString") // They really can't be replaced by Strings.
    static final class Data {
        static final int PATTERNS_UNCOMPRESSED_SIZE = 1572504;
        static final String PATTERNS = new StringBuilder().append(
                "7Lt7XIxb+z/+cT6EspOiSKIoJEoUDSUpKlRCanaSIiQkpGZvREjpJEpSIklFDiE122GnhIrSJoxHpeQwVFNmmpn1fa97pr09n4dn29+/ft/X6/dH7rXWfa3rfF3rWtfc3r8IvLrS+WgbcavqodH90pyFAyvYBfvD9kdoT2tcW1Ixa+PmZRs3L9i48Upd44hppnMjBkSffBzdcqKu7kTdiVqzN0Z8/ubky/YG+/OfOD6WKDpk+QkCrr/oeP/uYo6z+97rfO/qp87OtZFt7lU5Z8We53KH9+7d2z00OFzb9M00JSWlG3311awvPdg0YsSIrSrmazUqJpX9+ssvr5y3xlsZPenCwF4bd/JK7+7du+fcil1dNHDGOw1m9cmmooFuzHh9jwlxI0fE6m/IGNQlZfmv80717l3fd7me1cwxi7d6bbHup9K1604Xj8inww0ZIocTPwzsRpf+ZXlxxoqZM2d+0rtx8egL2ZpH9rMRQ+UUxnqrWy+xCKXc+MxYqVFx8SHlbPGrRWO7WlzrGm87UOmeylWn/b8syP7t1KLuu1eE6q4KvLNAi9LYclROwyOjk6z51BrtnowUOx68j1BjRN9kX6rpQSK6MJD3nHb5nJPpYZmLmnXlE6qe5zJBXP6T6LkNEqDRYzAO7hRkx6or1v0Y5tvL2sK6dOlyM3Oiw+R8OWGNx83Wf3A9f2EkYsWzbkmHdaMvLHxN5KSvfi3Qxq/1usQj+ekwQycGt8qqoDsMki+f1kbKJOEndAqcvWqLnIWxX5Ob8Se5vFs5/W5O7wVg169t9bWI+1US1ryOFPhM4s2CUJX/ihScmcwMW29pvqso3dELcpX355yzNmkZRgVb8sVzRpEMeDOFKJFBDACEGQNhoS++MDzrfiBVaHj4QNvBakO0Y8dNnDRnzNKfV4YvyP2tWC1l5a9RhhYlXbU9d0f3mFPUu4eC//xnKxV9c3mWQHvHcHXSjNyHIYrAq3Q3reB9VMH7diq4//wEzbgtFzuBQpxMD0uGQw9aETYtJx/ZtwVRTUzVeRBpW/OUrwXbvj5ixNJb58XdC1f6Hu3RCv/O5JIwz0c6m5bEWCkpiUFxmXfEuYsNFjM/6YOiwaaVBkETunfnWmjEvNmb4dyWL6eo6Jj5PnCgnMXTi5/yR8qpz8/K5VnJ2T2yzIt74J9w4nP23pczlafvsSgrAc5DNOOWqTOstDIvjvt3smJua0xmylj8eNKpRfFHljeMM1IB5Y2h11eAn+zyTEpX/3tmSg3VMRuzTq/j119euZy5uOvR9rBNWiO+qGx/Omu1n2x4HcNA2bAod9ejC1oPQwHbuLLXqSTZ0Me716kP8qFXr1NZzHDJjypjsc/Z7Tf61YSu2FBHNTBfw1t1/80ohS43cyb6H3Mb09Oa6uLWHf8tKWoa16i+lO/2Xzgm4eaQvtSLInzva7/rHGc+0A7aNXkQHCdFZa/Vk5OJNh4w47d9wdhj7ZP9c4wv+owY8UV5hV3j/RUV0Oi4Ownlx39PhTKWhhmnGB+w6tbl5sWJS5UTluoP7d37eGhJe5+qp5pxOu16OxGPR3YkLzAIWpkUpEKDZn6hwxhO1tX4YGb2DX3ftDjXx/PiYiOkmzIMk5aOY3AuUX52cYmRktJ0hQs6l1ZdAENbB+v4Wkz2lREXmqds/xQ13jNsdydF1/SLlqNoxNZ17L7+1NTNixNBg+gbIXleyffWH+dU+nfvXv2PCP4ad+v6vgldd7I9moMtuumHCsZu7907hEofcPF22n2AXSh3Ar17Kk37Idg1ixzG1aikPlpkYdQc6HLDhDqWXu7w18vpTrG1VdueqaoU4zj12OuWx6h9y0sKHVeslXnWs6eBtptlEClxwemzg+jy1MEJz81GL2OgL3zDlX7v61lLLprcUbCNmTaQWl054vK7Oyd6USTD/hqqdA49U45OfZKtvMsSLuKacnzqk+2bN2pR1ylPxPjSJmY80XDMmnParteU4MTO33DinaFfJlXcf61g50z5fl06e8YBAyOqViwn/q46lBr3hv2xnL7XRtAw6F29r8VZq8O+L43Rq/bHODkLzNja1F7/MvWXFlhk7ThIz5zd7+4UBYUmrGUm3/SdHUd9Vn0O7QkLKbF1mpbuSJlF3TV+38txheXUgq8DXq9vS8+YlDq4605HreW6w0ulRl6aVPkbkq7GSFbfM6fBA6JtDRklx6n+lbKV85pZds5X5mojqX0jmf+sVTjf6OIXi100HAyUBZ2USmf7DWb7UAf6bOpv4lt/cjDVZ0a4a2hCciqTSLylufd/1u+gOWp+5FxhWd/Mg4yZbwW8Xvzy3gzKyTfc9dIhcubnDu07FLMwsu/HfrI8KD25s0bmMXj/6okSkynVNFWe/4ERsh1cp9JSfJ7ib3pvNnq73LtKCx1Tze5c+w6tc5+O3Rz38/He9HQ/n+h/54a8kCpVs76/+ng3ZvmQ/51BLbIyJnCo9aXOkubauBUJAf/DnNP7BbxDGYOZWstnKekvPBcwgDmcUVbR81jN7T8P5wu3LIu6HEhIZ0qN9z9rGHK3DWAKi5+KBvInyuq2JeVTVwyQUxaGaz87GCADOban4myWrLo5vaTo6RMZy+NLl1Sb/k0p8HHKgIl+hrK66ophxHk/Q1ntccx/d/CBLgzyXuHa7137McjfrEPRkafGsHh/aUPkeZbn/zBl0Clvpced5dXdIdbzL/9dNacf3m2xiQMj1B93pq7wkAv1e7j2y3UyLX8yrw27Ji9d3xhGNBrJpdqkNif/ykOZah1KNSualjYw/Fw6PXS3YMDrvtib9Z8Vlqxy/b2v6pBeRzQNlzDyTpkYcZ61QokhZlPbu0O750Cm2jL+azlCh7NbMnAXw9u0JvHAD0WMQl1SgrdIwxavS2I4Lc+dMPKYbPxfKKcP7TXfWCbxkpqpt6Rhsxjtjiwa2GbdbyTl56WnhgfnzgjGgT5+RfjhJfFAx/syTTxzbPnL4Q4bzz626u/o5g/ppeFxvCvjuOH+d+7J/VlhiPWOO0yh6PKvpKKBHE+Z1ifG+99Z4imTa+LvE00vyzzMwneZNGxLmcyb/jXNsjFhNAPyfbJmar00vOVkd/vfaeokO9R63Fo5qWT/O/mdBl7bbH1fbviJpx5qVqzu9Cbvhkifanm1rjjEoqzfECr69y8l8KZXa1sGdXrTE2P5jQfEUuU8tCCU/eS0rGNYr4fJ1z+s3vOzbLk8YU3vg/KQVdmzIJFEzaIu9p/k5KG7U9Bl1uP7jgzH5xR7J6yRofk5eGa5d6eEkyKW/CbTt4dtDGuFu9zjDxvOPvJ4ulyvtdwRb2Si5g8hPVbLbf7dW0NKxmlyYfg1T5rKBOGsJ9qB/ei5ZlIbKSgetosWYw9Ru2+8O4KWaKvEnaBJQ1C6f1SjKTSoSPNdlFlPmhYTPt/dXhcxC4nzv1buf1VMg8bND7dJfO8h1GaqL89HpkErpb2ZouzIpfgZkzjd6bG+gpbwbyKZjB9Oq3bxQHr27KPVfAKrKy3nDGkB32JNudQpShMuXE2x3P1uYfgfJ+ANO5zz54c0oTaX7t6aGGu6aeWMShTn4j6fp4wtuB9VkFKIKtwhLM+m5KBtvmWwapebyUp+MdHqcRH9BW7du3MohvVOz/7C8PzMpb8wXFu42vcM6vupg/7+XsPw2ndwwr3hpx6Kg8rbByrt6BvgFbnIWzqjsaN3b/NQ968n+xJP1N1Nm0yGtwZ37558647dgmbPR0c5iiLzrjsdPF7rjBEavr3P7RWs2eVm+kR/5Vjxkc1veLsKI3955Xjkqp1tyHzTFv4snvXMDXr/pLLfGXo6l2V7eVKvDMrD9a/G7l6c2DVeAxZRrl/ck46puqeRNnLEl0FFeh0nnfQ3HdoPwo1ObdYZi9+MUAFP5dnvIxdl3Z+lB3ZTnJ9qnlp2dJdT9+7pFo6GoO1yRh8aqe9bwJSC/+XSs2qtEa4SjvXDqWOYit0Up0/iqKvQeslA/EJ960rpFD1aR21Vj31+4lLiDFoLar3sOdpkzLBSWb3itytaPXZLruzSVnOiruepD++Z8/3zHL1rs1br0xvcXQtl6fqew+8Vq3lslhGJjjK81di34FOg36sosDTH5XtGtVI4aKgT3MIVqdFMuC7chifOMe9J08TkojQ/qYsmQs25cajnIxOSGUlr/PAhf403hxu+TSRVd5nkXXSk9QFp8mQOZ8/5ojccgSHtsPy9k8sNp9MxK8OuOdD4E8NJkuREpakwf+jWfvREisfM/c93JoB8Ucdf88qQJpkL73udihjNcekmYylXM/aZjTS753DKuI83a8zlJLFb/+mMSPdCFqzRlyN1/e83tK8KwpSMjcDp9OQE3OLiApdrirZZ+r7l8Jeljc6YfHCWvaGh3t82v+iaPGM8P7H5vqz6R5qACcOZYhBJAFar+erFlXBZ1S+7VLgx3tXozFw0vqexCouYYbFT4iQ08VTCvWbIKr6C9ycumciGLzCU14GeT2e9/UNWP27GVfahbHgJ1942+RCrTIH549lRz8h5xuyjH1d1ZcQQ+W85Ory0O1O3GnRsMro4od8A5ga0vq3BZ5WbjxfbSglR5/k0+eTRAI9PZhOYRP2+rCKxr9Ub5+402B5KNQoyFtS571VSWt43S/bqY0BvvPpmg0XZOMW3cve9sGU0sp8dOvsKnoj7jXWK91znDc+K1HCnOVlunKY3dbxnP1xIFqpNTtMztQthxo1qi1SSnqlZQumDnu1xskr3Dk9RUtLoO97ybGoMJ6cH4v2bee91Tp9wr12LTFxP0tbI5+rupk9PGB3OqqA++GVT5cOepx4qsfbRJIvZvZ6n2uSzkuSTqz5FCYNsmDOAE9F00/oNfzS9MRwRT2vqvzC9hcJ9pHAflQJMSUwY8vk3I6QofNHiQ9Rilatic3cOoseh95yx+nOZhkXnksfr6u7Nxh6TFvWPpUr/V7VPwk3rZfw06iu7O7pX7Yq89NKGUu/d3qfJy10vIW1iv2gKafc7yC1PyWbya6sek97kCf963Ao35yLXLCp4gNcLW0M+cyXirqs9lTZpND1kxHlx/W3XnZwLGQfpnFpVHvVEdyzj7qe2/zl+ttrtsOK7mFhq/fFBx2IDYGuF71PdsHXCGK/9c2CypWduK1pOGFPPmqNGj9TPjuUlPU+J7/bWpnfRnC51OxdJPXfjQOsbw51b3s8013NH+ETmHm9vITih3dI+EDobd+mBRRI3UqnrTu8UL9Wm98G7KfWDS88MGaIdnr0gMTSByZgzLi6YPGkOrjBMlK2r3L11a9zBRdTLji9UPvtp0SKvk4PogQDv07sR6/R0DNhzVJu85tYdbU5eH5qc7p7pExxlM50Kua422KJzPP7PsYbCwc/mKSbWOjPsLb53700qOLrcc3B/5pgImnztmayHsb5t1cYZF5nWRVGuie0lcx/oI6+8JCQhXDDrdu/ehaFb2h6vNF3tofZpJhPXyTqP7RVu/8J0AUqCE+brj+9X9Zv/T8io3/TwJr1As6wiGS33uPykxesYFm7NtvPxTmKCzT10X0L5PdO4gZdpmk/RMZqmy/Tkdrf3qVqq2yEfCnLt2+BhS46o9Qy4aC9lXF2tJ01sjp3PPwX2SYnvb7uYtqHGb744K8OkM2edqBwm66LYTxlzsL/sTmzWYb+vM33l8mN9FP2Zsy8mKH22r6x1Qq/CCbKu3DciyCL0wmeymnuk8ugtylPj/alPsn8KeHO3Hz0bjvufcxvdkxhRT102OeJy5p1XyToU/eOhr9dsbEvvA9db4MB6sn0lJ4FJtmuj7N0dco270vT1YPDbOsEoV20aR99qSU38tbKodLbX7PFMKVQ6u2zr0TPhZ6jvHNl6tHGogTKj0R19lV0GNjtr+VFRVi23KnbpcZsKe//q/kOcnEMelDUsT3ypH5tHWXDzjDX6/NhpCP8y1Df22w0U5YWOQ7yeUdsND/0ypeJ67r3N5mu0Rmwd9LpmdpCl2Kietk6staSD/QPDkk8vr8QpMHY3b+Rw9dfpq3/rRguQkANXJ99xeEy9iK016C3Smp1QL+KXVwt6W103vuPgWLhf+9vl488e9zsT1ub7Y7x6jKU2rOlcuiRfumvhWRB/a4duD5rOpvDmVdzfUXCINndvtSpVqVXv069112ccj7fT7+yNOG513++57+t1l4aetHWaAxk+jdk8/8H1qJEU57o1Tw4OotlhvOPF9WlIrkt8xj0QjxpNuVm3xs7rhW8Ppty5/OxqJK3qU3zXCGchRry/TaRm5qZZY9tl7SG/bg96IBHNHUmz25syx7tf1Gwo2lLxtknmq2nbqnwJy6EoeBRt7lwWtsRyKQXvFG/p9Hq+Nm24NiawHIQdc2lIf0ODPim+N+bv9l94ZS2tsIrC4/dNnXA2d2Ff6nKG7jqvr565NwcqTLaouWqvwXY4uzYWoo+9FP7ujp/9/GWWMzfoGxUFvDYZrZOlNeKqyppa08+JPz3+n/6MuNnK3LGG/cu/10Qtf/ByTIXoRInaZdnRrT56mabRvl9euTau9Iv1CS4f1XWnX0pSwcIV7+xqU28uVKAs3ePNv2Rz12qXEa6WFjGStDTTYar9H9EbX0BbTPT2t1d9Tuyh1aQXa2yGWYsgdeg5re/04FJc69d3UvE4d+8FZQWa5VmkfzXOx9gwSAXiFJSfzc17vO6+/gOaunSCzF48u/EonB4cF5NjKzPPydaHkPw4zVH1tD34rYpvWaPXrmgBNaxr46pdj+QlEz3yvWXtWpz3lfLW7vssi0R5T+5SMzLRe1kJh+zTpBXyB/MDR1vSTwX/JROt7pkbvm0o0yVxqg0Lld+GzX01PtvLL889wrXfyH/SPFI9dYVtZ+Mi3X926VAt2e9pYwd/PibryqV4eYW9GEdbElk/e/WmF+nw7IlVU3fS+/zJf+9eeKv3WrtC3r3I9r+zQt4hODDU+kaZ/BIfUhu2Tc7SSw8NQ+tO2nv9ZwclyLsmNV/85vqelU8yp4Tpy38HfDglgjYOcJh+oyUXMXnAHzen92CIBNSGjZYTGTwlYkm5vGHzomhgy1FZ2+v+Og1DbzntcsPwsZ0Nm5qJy7xd5Z2x7T0/H5J1M/6zFSfrKAzS1/DQ+Dnkzs/MD7JLnv7Vn7IO134fqTab4hEf9P+zJ3Xhq/6UfRLrT9WXf6yP9LG7KO/PBfR/Pe3vfkU+skZjw9ltfRli3ge1n0yRtzp/m7pioNzM2w5qv+zsT6X733ne2fYcVBv20KizF/aZd6jNSU6slqs13nc37fJdVTlH9lCqHpu/1aV6N7TX8pVyO5/xv9Ozs0Okbr3jkVzXw4oG8uVNpCO3f+psvV3xuL+wU739TgZfkal3p8elc/N6q/z3lutikwFXb8nt61EbZtfpROs0LhyRt3yfg+ZkGc1GI6H2G0PZ2Kca4z9/xp5heUTe/NtwTGu+sWVPivH7ZM8p9r5eIyN7U/TLiXVyslgWHZOTPVY0UFPeaT6yQqKadkFuxiFFxy7Lu2FGtdwRMyavlLWah5Ie0x7K1PRf6W4v/ovusL/oLrslD6UBtWE95G2yZ3bSMKEcZFmANGyausykoRdbrOdnPCDFzJsnxlKFjVdkXH/fpZdwR3w5JvOsnQKFnfnyiMHy1XDZ3p0Be1915pVzZt258kacxzJp2LX7slA9PbRPqlz9Ez8qPU9YdfxvGp92VUfmOtZHMHeQWs4566QZfWl7TZ14ztj+9CRz8fzMenIykVmeOEV6d3u4VyRTABSRJZH2bWOYvpw4UuDjzSxfosuL6fI/aIk5Ohed2fw6rOiLOb3xZet5Opn6z163Q5Pe+TJlszLmnYeI/mKeNDXVl3k5MZi26+w1DFf927TCkDadFh4xp5063QgfdZ416of5mjFvrE8pW18K52uPuDron9zOb1pM9jxZovGH2m0nKu4Mz8yHGkvVZRO/osUGm1aWqZ2mJYPOnrRrC1dXfzV5e8RwvhXtJvaz2XTyUcTXkytfTw7TyYZxRpOnJN5PYfo7Z7zXVh3VZi4z5YkXqko1Smgz6Lv6PF+++8GqV78e/OxxlmlMra3uFnnuoKIjJdF6a4vfq6gZtbedaNXkPzuBjKkS055YppJ1mjBy0QPFRczHAT/wIqt897AHv1nK7lh7P2yXN6MaQz+8/E3G7Pf6DPsH7xmSNsXyuW0Paqr7Iy4Pf71YjxaxHj0zCrTWn9pJb/WrDj0b9tey51fQk+9kOaHo6nu736Kar5aNmeXlP/4lh51X8KE1tGVbZuG2VDONaepeLM88p7jogSat386z5hrTXrD3TemIKtr9Pd6jY5cT7fduHfz+fwP5pmQw19xKWUPTnbn6fr8DbTR5i1G3AWv13i1mWoSmwo6syOu5jOhLG0dzuIKKzHOuGZTS9RKeXzA8I/+p7IuFaC5PdEQtOsKrjPlqYRRgDQ1HfVjJTH10ZdN13rLpaPmUAV7iQ/f60Hv4yXGVFC3D7MLB2+m4Vbb+Xz6rCDoZqyNODBrB/Cx5cFHTJ7+PzFXLyy/2kQJ5M1zWFZozVjhrDagtVyt9OWY0a26WktIxhSTAN++qROa4Vv7wr+WsP5fzf+DDCrlzO11TP1VXaNYyhlYLbqglozvWg557ozPzwhXlsOpGzbhnxiwfeGneoY4zlYYtuxYPVNqhcC3S9sMRzUv7UQYWBzutqRGfcKbF5seTi1zf9MoaOWKq6v9TnbqxRpb9orfXOntseM5kbcxOa5pMXc9878JMqh9KNTAbdF02uyebFe1RG/VgqmNMxzR6pdiM2eXcUPqLRvnwVYcq6/oy1zXn/p/tH5m6jKFNjW/b4Umv1ZU999IGwBcTtrKeNr16quhIH1Tv7uwncFqMU5jf7108XvN22J0Jb19GT6xb3KPWQakLKvfsxk3T5+716Z8qEsYMmmEJ2cdcWuGWHe6xqPoQjfDvN5BKbvfixtZxjjC/INUp3n4VHy1WVKaNodRRgbmzxnKM+zPtpKEVD3ueElcZMb/u+M7+uMz/7tWEPdRr00JnmT8rYt5YmI3v11wxKfs00x9Y7aFmNP/sptujmP7DoD2zbqfRaPYwCjUd22/XHhua5ZwbT4V6/DZxX5ThrTOdHD1YvqBp1YHuzFdeYUz/cPxPzEdXI9d471qk6zqKFmifndPi+tt+WBtFD/RwgfXpvOmmRemraUyZSqLejo+88nn1UBpIWzgrt5ee9NqR0JMao0rzaOXEsR30VUToadakfR4/0chVaZIWj+rkSGCtYzh/xcrwiWdl8frsi/GvX253NrW8xQkzxR4Z8gumcYjdz6lz6thrKU+eRTl95leGiatWMT0ubFugnXCT+fHtWVHwTguPM73oLwUuPkPa++S+M6INJ8OPyiurV0N130xulc7S6u7vFR9bwinrTjUu8ZuCo2fsv48CE2cyI5v0ITuYBpB7wuamm0wk1J3asiy+F9NNW5rhpRhDL9jbMmh6Dc89FE8/0Lk15mtqMXsVB9heY75xOjKmJGntgqGyn1cOkTPF+05Uyr4NmbjNIGz+gjWyvNh4P3x2N9/Z+rIv5GD9RyW8eUz2t1XYtSW3PXrmzA3jjWw8yu2l7j8x+Xlsv2ceUO11CwMAM6OvfmWZUeu5c9MsmsXKjc11pqqm6XXv3nDryGfDm9dGOOPyqGpadLd7w04nmhV91DWVNUY6vvmV+biMHxvN0bViYBKeJy1q5qc16lfSxrJpUHqszuzTz2mX5puHV4Fe+5y38t7h86zXa1rmnKRNQgy3vzk4h0l2rmO3PFA/iPpj8MZklcmrXvoMYtLhuSMRQhvP3r0fhp6+93zs76I0Q3BrsepY5STW6n5I0IfUdZH91AL+929JL9YuyN3fk17av/7iqeepC50fzf15mV72NND20l76M4hnSkmBY6oxTYEaCvTa/ECW7Fpz+bFnGAj2t+oDZKitWm8PcpcbHVUdQp3SO0w1+mO4Zy9uMVNrW944FP5g6pZPB5gfkIzvxNXZtwm604+4/nCJrhFWKzP9H7bN+nq2A5OStbJHmTyVLQeG6ZcEMNDfVqxnXIB2RbahPZOp9d7NSfUsmkr7g+XZ59T3bQmnHVYkx6UmP61Tr2VShOeLuZe8DsZQOpeFNivsvAqqu9ILZea5oIxK2d6Jhr3vzVi1ZLxvLDLewm/2/y8s2TN51lj2aVqWJZVMmv7HvfjVTEGd20/xSrPXUMqBrbtBz6XCSQfpUa53Uu/SOTpEdVjo3i83nBk3PthRoOZjHCdwV6Cto8A3736fmflw2Xe6IkvC9ho9PeHkzZ5L00BSif+CCWMEuQajaNZqq5s6RjX2+lPVn5ivKJoFI1RjN1Ifv1DuNMfZZ6nJY1pOVvrZz783af3pA5R2wTRDG32ymvmy7mOicsyl5uTTe7/dI0kNzf48pWLB4Yte3ejdeNUNO6PD9Xrqp2g/aljtutQ2B2Wmvfdc95Z0NfN1lEu0egft014oV2vfN5G2aTUULvsn92YWc8onf1T2kPV3v/nZF0f72tbErKIxyrTbLz5YZxon8XjGfMd3sO7Zn2Pdkkt6HbcvURMU6ZYYte1drT1i6uChteYDmeEXlTUYHqY/WhRHRl8/TQfftKXYuoeD+FCdu4/8u6/jX7Teysq1Yo7Tpzulc02COr8D0935aEkbbTv1qD25U73FXpnG86o370K3Vr5J0qH7BferFT4fGZ7SHJhAv+dsXQVrq42q5xv9+m3yqaHHPxtGMd/quTUO1VQ+qrBIpevOTR73vxwpXn7orKwaUz/VMussjtsJK3nzKtToD0gbJlxqDrRdvSoi5lyybvfuey0eT/K9JFivTdt9SQWOtn6v08cyvwF881KUc69g4bPrFRrIDlrpAffVH2jRrLOs5oZ86fJ9h4RZMz+NX3m5SJw2hH69uTjzgWzJ6KnZqco3catoGU4JrT+EGGEXr6aHmtv/LriS+ts+UehP25Px/W1fyD/c/U5DrzMHsVOSguU/IiA1CQNtjdqYdGSHdCRQYq39TnAsOr+m1XxLCW7eG/SWeO5W2TNmKe7z5j18w5v/pWWrg+u2w82h2gW+YdEHccd2PF/ks2mpiqxHd3Bg+nx5J2kif2T7MVnXZ6fQvJm9tBdzOT834Ca9h1fAo+T9vPLJ/9ZZW3y+6y3BxWGGSkpKL7N3qihdOfIhDAg/bVE598uno08j1MCLn8vN7XtfdcgbEOcCez98vETeKeArHfOU9/J2CgbcbNsk/xBpzh6fD6sO92Mabet6y77oS/HWkDPz9YduPfRHTDesGditS5d/fdBb0sUj/k+afXvPe7DlDuhsze/7xKw7y0f+zVCB5YZC+VdmlK9jj+U9w3CyZ/7RNXvkRMWqdX3l3bHjWt/vby2eudXrinbPrl1v1ThDFcO/UsXhxD9VEdg7pHj6nxTN5C2XHrePZE4h2rsY/YeP9U6UKaU1vPvnQ3/beunyL5evaLUd/Uvt52+ttYYQu/OoqJ9uy793spOGBTL9mQ36lULtN2vkTZntQu3T8+WdsIkvtf7Y/rftpqExPreSb9L+hF7lm6iAL5O/dDBK2rbSQDxEHEyVVz09V13aT2pO6+n70zeO61y/Oz1XWb6esm740wGcnhxNegJNilwbytrFjfzl1WKf+/KLeqL1JPobaIr38P/eRLhUvlLR9+w9tcsjcQdteh8V4Dzk5GLZb5Yrc86GL9THvViLpfdhcZzhGpwE4+qsT3k6e4UvG0hT3sLV6X+BrHP5CyTr7KQ5Wb17Z+My6UO/tVHzSnk4fKkLjfWljV6R752Z0fe/Z7F789uqjU7LmL6TXuVR7XclC1fHzWC+eC7VeJqoEbeYdqpu9BUargls5j9fSDUoVgtY+9f48mRh4Efm6hgS/uy42CyQfl2vxZqzzk6aZNaXdjVWDvBCfddkkTSNfmhzRqHgftQ3ctHXXD34rVsiGGF+DD3Ta/hShwhb2X9h8HsVVdD5ojj40BqNuDfMB8zPe2UU/ehyROiqV0wbiA3U1GatofL+SuH3+ysJXXpNYW60X1Sbulg/kQ+3d4t4f5S5/rLVjPQ+JPw5HDaZuStzJ/o4PTP8azjlr+EkOvTovnvVb5QXWyVghR+NmdXYfyq0ZNHnu5f2BT571LQERQduvTpPk/WZvWpadqzsmChqpQKtOXMfR/p602//d86YnTQ1O45Zb1MyPpYxZBy9Tr7XWjPVccYSau4/LCs3nH75cx9aMNxRutzndupN+mt3imHkSqYyXyfzaETMKDBTQQuBQeMr59jJfoxd2jhEfhf1/vPzkuOhhid1ZL8pjtscnrBA9mPruLocFQd4T+c4T7dpY5L+IGoJncCVvqkT2mWX5QXeHKcE2WWkAPFBr4ixX5lg+9MTg9h65zR1aSFmoh77el6ooHY3/aKgVDro9/j9awbT29fLlxa6HY5G27KmtjA/619kWcVU75/MeI9+h2Pl8aVqPc2Z0+pj+hnjixPr+hpTmR9KBxnHhGq5nKE9uYL3DgvdmGD6Rsne5Nw2rGqg/Gf3rPdmZ7Vlv3YZfV608MHrA/2RYN1o6X518bFgeuNbdrT6UInv70+Z7wqGiIdOAmvMZwCVu9iWOjkqDYynur8xHuWu/5D1ve/eJx7ub9t2dx8sOJjSmiLrgXwAO/JP4PXVY5/J/htEeT/bBcNaVPsjmSoITnSvmsI32k27Of1tF4xvsbfs3ftj6MffulcdCRwKM034RvXx607JyEfDiEfG5Ns6QI3ZpMEREt6ccUjV3J+5YZtvXSA1YaG/fGpTfZ9Z/GHDezJlBS5cSkPzLeZrmH1igvaBZtyq13/8yqi9bHru8albXtGb189sq492GolDRn/nt0zmN1y5A4UPkf/SP11hWK1XqszVtNgqy2KcUxJ+z/0Vh8jg8LPlTk+CxlMYu7Zi00KXMTngRAXp9bH2ImnBYObjjLbi9c5yT/xm08mgY9JPZ3cuTWc+dnxfOvdc38VJc3CXDvdiz1nhfL7JiF73Y/rbfiipPrBUXx1ntp+Wq0rCk7p3v4873B9KuXyoIGLx3fa+SwNwq8goX+m7eRjbYCPLgf5aYep6MuizYGRHy27mx1xfIxpK/9u6C+AvObl+ZyKY7t+uRXmdY1RiS7x4C4bRKw88IOleYYop/bLAUSVhicrZpeMpM5tSkvQH/qvAQsj8V5a4P8e4KsaGfXLXqmUMMnn0Tlnynb/vZ5qNvz4DpoxZSv+DSmGoqXrsn/83ouepj/Lu2sVZq+X/B6fnmoNCm8nUgx6+6l11ac+XqdvoCRa0YffZB2Z2I3DpXrZL26nsxuPuXXcGpgxRS6EthsIFiuELmHzb2F8+oEKnTeR9KSveKbhT3Uokp9l9E91PpDtV1QWFSleJ372oZfP7dJzWJJwirvkkqaDiPP9iLZtjIhVc+5zz++3CtzmWbA2yzF/se+q834vPOQaRnCwT6eWQP0wCxO8ya9muBznrhrV9mCIVCD7n+IVzPgxvsysL4A0lpj7id6/O869+zuGHc4axpAEWfwS2faluL+I+HEJMTd7nrxC/61XLdonguKm02akTUcWX6qxadl5osOJBjmSr+J1uLVtxb3AZJmvF7xpq2WV7g7lDiYhbyyYzpIJ353PItXetMwJb9ga/3ZS34Y9488B5u+lwIx2qM0M7Ogyiw8C5dKi7hw7d6dCFbgsMosNICtBuQYfcPh2PGtZHfPITPS8weT8vOT8ylOJZSd9lMig96LCG4snzpsO9DMpFdNjAoGRoBjIofejQgAK015w3CP74ph8xC/74UrXtVNmLkR5s0fPxKnR4lg7XDaLDLXT4gVn1oMMkDTrsQYdu6nSYRIczBtPhPDqUTBBuO1j2YrkHRaxJl+/eLmy95mpKma+6hWHNVDr8QFdrzOjwNh2ajaZDBTrMn0CH+RQ2aDodxtPVjoKtH4vomusIumbFYNVhsDKoRtHhMIaALh1q06GZMR0eoMN8cwYrhQ0aSYcudLXDkqpkE1VU+69UJbifgudcKkoWMzzNaECNDgvpcNgQOlzNaIBRRn86fKFMhzF0WDCUDjUZQtYUuwKj8BKK/bACNecDOpzCDNPpsI0OP56hQ3tFOoymw/X96PAlHR7vS4eX6VDKmGU6JRQ8kYrhwDjEDkrIlxluo0Mzxgk5dGjNeMEcOoxnfMORDtspbPtmOsxhpG9gHKI/ZSqFDgcz/IXS4ZMBdHiADrdQf/z4Cx1GMMP7dDiBEeANHX5kBDhKhy9706EBZfUB42J7GY0zNJMp2PokCnaFyrm+mg4HMCL/QYcPGZGbGUUwhBjrHKf8fVRlHI0xbSCj238xlmNMoMrQYNyfzezzZVQ1mQL7MYK+oMCFjGsqMkZi1JjMICplVGzdyoto5LYckOafb+TOC5Pm5zZy1fE42MgNCpMGhTVydfdLg443cl0OSIPyG7mRYdKOnY1c7kBJ4IAKDk9fxL87m7gtauU5zyYvFrTy1lmRF46tPEsrMsOmlddiRQrmtPLMZpNgp1aeqhWRZDVyN+2XdphXcFSBYmwFx0VFEqhdwclTlgQaVHAUlSXtKys4BkqS9mUVHP4gSbthBYf9k6Q9MJU8HC3idwDFVTAKfhPAKB4FYBSsYTEd/KY0cqv3SYPiwegBaQdk4apKAue38nqASXDghYdbK88OvM5q5QXPJjMsWnmOYHJuK+8dHq6tPF3watXKY4VJzfIauZpgRDmVTICcQ1PJej0RPyKVfMQsKpUc1xHxf08lL0eI+DNSiflYET8zlUgXtvICob8TjdzkQZLAaRWcajwmV3ACf5IEsio4kZBlMwQcKGmfAwFVJO3QARtSO0BAbRG/FwSMbeSWQrLDjdxEPC42cjsg4KVGrjcEzGjkmkDA5EauAwS83sjlDJYEukObQA9VuShJAtdBm3ioQpvgXq2CUwY1Tqrg+A2WtIdUcJJBun8Fh4wU8XekkhBITjFtA6MwyDwwCp5mglE8xoFRVUn74AoO1cPSCg5HS8RnW5HgdGgG2CZjP/RosE/aoVLB4UIfJmD/SiOXh80dUAYcggoEprnggQPVwjIEzHOgQh7IKgLoJJZGifgsTA0wzcQUmLiYamIKUgSGJ+/ecR2xPxKU4TH+EBUSB0NUyB8IUcFOd4gKwnoQFQr1gKhgvjqVbJlNktitvFAr4gZzx8FDZ8JR4QOWcFT4AEzWMpsULGvl1UC22XDU2USCANgEX4dqWeAmOpVkQ2F74APg/HgqGQ5XsIEPGIj4xvAB6OVkKikcI+L7WZEkBED7Pmk+gmceHmfhqHgkwVFhuSOIKJjzVzgqAgvSRUJ70VAQAmACIgoI74JRMFMFDpe08j6AX/jlbUQU/FIBXmrfysuHKpa38uKhXASAJzwUXpiKB1yoCUyC1wFwVMwGgknwapVK3IH6BRwVvOpDsTDcNWz/BY4Kb4Fxq/FwhP3hr2zYHwYzgv2h155wVDiNDRwV6o2EnFBcD+xNg6NCstNwVDxi4KgQEN6bDgH3wVEhYA4cFQIiWDmw13BoEzpKRxi6t/IuQ0AIkQQBYZ6TEBB450FAZI4OCAgdVIMKAqBlvzT/UCP3AKgg+fgiKkIbufkIB3B/Fw9wEA/SgGyH24Q3cv1gOgRAA/zAEk4KTGXYhEznCkzwsF5g7RwiCpuuNXLzAA1MZUhMCogMqMsMdJEQaawoYD8YygGMMZwcYZ6Hl9lwcnjXXugTAjlg5RQc9XE1pwbqgblonkTwu0CZM+CkiHp1OA3c/DPsAbPEwWlAxwGeBgbIeBF/EIyPF9YwPpQSBxVBKVnQDRLWLugG9toE3djCS6EbOEYDiF5u5A6DmEhycPd4GNCrgmOCh3oFpx0hq1TBcYAdp1dwcmDHLYhjCNQDcQxCGmDUBYxCp7BjJnIWkmcN1AOf3Av1wCcbYEfEAfJZx5lGrgHU4wpiQBgMT4OLLUJUQIg5cDhdEd8dAiJUSuBpcMbLEBC6gmsWQmo+QnAeogLWQejGI2ahSxM8gsAoWNsARsGaBRiFyRAHmlAuZOHAZxExCuAOHJTigZSXiMcuOBsMvxvOBl6jEE3gFQnbAbzuh7NBB+vhbGAECd1tcSvPGM6GE2AYFAqptaFQu1beASgUeg2CQnGEuUChSEZ87J2FveB+IwTEYxVCCUKEQECkorcQEMIfhIDIBY4QEFInQ0CcpYrQERJ6PAwyHgLi4Q0BIYsPchMMsgi5Cb6A4y8ZUgfA2caJ+DegTaBfAWLg9xm0iRCdAGKYGcJdwIEXiAFSAneD6eBKUuQCIZwHUUExtWAvXhwGozDIr9gL1paC0Qki/nhsAqaL2IT0x20TsEM4cTx2fbuLUOL8vWHVXX7OQ+CwAG8Qry/ww0cHAz9E7wL8oHYU+PHuAXiDmXAYFNLjYTbJuiV46X71eM3bmuyYd1z/oBd1Ir/nxxOWk4M1+aOrObVbYujwLh22PS0R8K4GW22UmKx3mJdNij7sjcaWjmtpfGLX2MKJb5Vclfyexr/7wUA3mLWeY/y2w7/jnQ0vsoZnKwp5LrV/1F7b1h5dqJnHOURBT1JQTQpKxr1t4A+QdGyyYUXW6OtIpq/PiW9tuVrg/Kists0kOmR4nqKtaNtzc/O3Df4dx9KS737Qj8Ginx0WpTYUqCzGXHK8PF7VQbT5ufvYt4/9gyRpDnc/ZMWYD8sLpIvmU7HYsZsubqKLObZYLBxPF/9FFxtipGacxrx5YR/iYqTqeSZzoYaXOm+hkcg0SGoZjUWH+Vg0N8BiRxNdrKGQmjZYDNF6m8nvH7y5JC2n6INzdOHQPJP5ojXPX055m+kfVEYXLWOw6GCLRXMWFjsS6KIZhdS0w2KI4dt4voLEPyONDQQxIUPyTGxFXs9fTngb7x+UQxdvR2PRwR6LhSOw2PGMLppRSM15WAwZ+3YXXzHY722aYtGHqmhztTwTe9HPz1+av93lH8Sni7djsOhgg8XCiVjsuE4X8ymkpgMWQ6a99VPYHXJl/iPe67YmG86BmsRRkvHr+Yda864WONDFgXQxXQeL7FgsBgfTxRd00YFCcqNbsxR3m19e+yjvddtQG97+mkSdYP31/COtWVcL/OhiFF1MH41F9jEsBlvSxRl00YFCchNaT/awJEsnvLX2D3qQpvn7hwfRIYPyAm1FC5+/NMBixxm6+DgGiwY2WCw0pIsv6WI6hWTPE93vZcleav52sn9QVlrZnQ8PYsyV8wLtRfOfv2RhsaORLk6OxqKBHRYLx9LFQrqYTiHZDqK7Finsc4daI68WbHnEet022IaE1eSPkmiv16SLwfPp4lG6GD8ai7xjdJFDFyfTRf+eE9mNi5PtTYOuDzP70b8OM8tI8fxHFx5/+OOH/3gqpi08naSpLyJ+8E+Kgt0XdQPqMdQ4+fukNcjIx3CqIhXjfH6MrIrqsgFZKq6Ry8axUYqj1BlHKU4BZEcuUkg7XqIUYiEn3kV+QqVOkHT2I9vgQFkMcFQEu1CioVrYhBINSdoVSRq1BQs5lYujEiezACkdB/wLpHScisqARoLPBDQS/F4UdNjLPvBPLjaoZU1wbuOsawfTC3A0gb0dOJpwQuHSg/qz3QpHExg9i1yNzL0FaREHgwdyNZJ0D6RFvEtC2kV2nAex7P9JMZcJXiGgGXhFsWsNXqFcVRQqeBcIzeHIo7VIr39SsP34IeaGU/oBmFzaypOASeh1MjSJgzMRD4dWXjoUioIExXo+6nxXMHkA1RSYRNHlDYUeRdkHhXJRTYHJPTiJoVCUKHlg0gVlOphcgqMQCt2IoxBMdsFRCCZVoSbnf3SVkEADBKU76sr2ZLxBuUeAgYP6gYepIqaow8lPkjwi4ntbEZRSbnCMDKhBR+QH3/OHuXCmqaQSe+zRAh94oFCRpMJboUXc5kIgqh9YggBcOKQiXqLUYOGlN17CdCpACq1k4QVk7QUr3UDZBHWgGsxDLaAMP8ER/BomgO5FcBdQ/AgTwE/6gD507wwTQNzbcBecoj8BEu96AhIsDgckrLQPkCBfBUgg04axRvyTK8mPl2mPAIl3RYAElpeABL2xgASWAEDCcWoBCQYnARLvdKBNYDEHJOhBt+7AIgAkxMS9uRAM5gIS704DElgKAQl6qwEJLP0BCTFjAKn3T6qtZkDi3RtAAosUkKA3BJDAYgdIiPkYkBP+SeGCctoNFdx4OD/cqBucH1XjRXg9QtMVEYo7I7xIgsTGRiGHsA5BBOfAOezgHCARiJeFcA54J6pHDjgxwArqW4IKk4NVRJcE1SDBNg6cmocpLq4EGY7gLVck8oMn4Q7iitjC9cIRjCCDzWnl1SKlIWnoo8QE17jPSDWxD95HK3IUyVzQohelw4gWRFQQAMCsJljzRBkLsZGC3RApAuQgXB9wNwpC2XgXYYoLkQuCFhciFjbCXSZAZbZQGdTyBCrD7CY8Cha+BpVB4+vgpdD4LUBCZffgJ3g3BZCYaQESFt4GSKjjAyBH/pPukBUgwZ0zQHBqfMAGpKLbEB2pSAFmgDVwKwvGEREJBV1CosTN5QKuLJih8N8LcVD4q0Ic3LQDIfxVXBhxnQn/oZT643fgH78XhECceqsfSalukEoF8sPK4yE4PKQbBIduJkNFeJeIB26D3nAZCJ6s+kMp9R90Z368HePNJsPqeQLqiGNINj9yNjMtQMQslLrRmykzdZNNzeRTR9k0Xz51lU2D5FMX2bRDPmXLpr1k0+AFsilRkuT1TfqkXXW/o0eApa54+SOt7SnK2Uc2h5Xtsb/q/kirqbxPnq3pnespyiHab5UcLXWnj3mrlB9qv3XHI62E8j5lNqZ3/FOU3Y9uDrtoqXvD+K2Sy277LzFplpcsdMU+xi0hs96KXOxN/ZeTnja6Ee94Om/v+EuCRiVNzSaH0pTvCpKPbH59VdoSba9RzQl4tK8WwUqBtSkwR+uatON4+b7D8+mSHrN/It1fg2oqm1NG92sm0P0xzP4Quj/HhgIPY/YbUWADnWpOg2KSTwYFz2HAD1JwrhsFb7el4B4UnDWNgqsyvD2jwLyjFPguBSaLNkraC1KUxyfRpSpmvz/dn8ew+5LZP53uT2f236L72fEUOJ/Zv5UCs+2zSaHB5lSvOyIXZp8OI5MW3dfB7AtjdMIweYCRKZju4zMKVGZkGg2dSKETO7o0lNnPiGnG6ITH6ITh+zGzn2HSby4FnsHsn0SBc6AT1d5JPtcYnTDgDxiZHBmdzKPgCxmZZlDweIa3IkYniRQ4nZFpGXTSDTpJpkuXmf3b6P5Ahl0ps9+U7vdm9j9i9sdS4A5m/2ZGJw7ZZIKaaUVfCs42o+CuFJwTz4hyiIJ7MqKsouAGDG8PKDCXRYHLGNwH60T8Xpa6oh0UpppxgcOMbowpTC+qG/IHo9s4irA0hiKcR4E1Gd1cpMAE1+XAlPJ9hQxRg9F0kz7zYhmF5DJ68tOly/nMMuNwHMaDe1GM5DElQRbVNcRZhbycWdewbnbIS8u6BsvZIeYL6xpaZocULqtrqLEKCZld16A6O0R6sLgsM0x8I6W4rGa/+EZ6cdne/eId0cVlDQfEO/AuMEwsPlNcZrBfLHaNSnZREW7TjkrOUxZuM4hKVlQWCldGJRsoCYXLopL5g4RCw6hk9k9CYeBIVo5Oc0vRSJafQXPLy5EszQnNLWNHsthjmlsCRrJ4o5tbakeyuOOaW/hWIe5udQ12YHJWXUMwmLSoa3C0CimcW9fwDg/Xugbd2SEhVnUNLDC5p7gsEUyeLC7rAJNpxWXp+8Q7LhSXmYSJd0QWlzmAybPFZZxBwm12UckmYFI3KrkdTI6JSnZQFQp/ikr2A5PuUcnJYNI4KpmMam5ZBibB3cORLD54rRjJStZqbjkFJvEueCSLNb655d1IFlla13AZCgV3bmASevXEw7GuoRQKxbsOKHROXYMJFHqguOwdeD1VXJYPJk8Ul93F43BxWTwUineKUOi54jIulJYzksWxqWvIwQqY4Y5tbsmzCpFmF5fxwOxe0Ia6HLACVES7uQUaEEeClRhMoToupsBAEjGFABxMkzGNLy6TtDT8Dr0CtQAsw9wvrELMl9Q1KINll7qGTLBsV9ewFyy71zWMnh3iDgH6wwIQYAZA4BjK2OAASFgA1rGGBWCd0cAJQ/QHzsWABIhTXUMMNrDrGnyBE15mDZzwslPA6VzXcBA47esaCgAyH5BABr354gG93QVOMHgKOMHLQeAELwXAuRz2B7IFsD8e8wAJnGDQa/aPeooXcIIXO+AEL8HAiWh4DGRQgToe1oAETjBYD5zg5QFwghcJcNoCEsigAnU8oKx04ASD9cAJXh4AJ3iRACfUOhnIoIJEPKCsdOAEgyrACV7GAyd46QacUOtkIIMKEvGAsryBEwyWHRDfiCguc4VHZxaX9YKXwC+84dHXisvyYM1fisvK4NEKcI0RzS3xcK39xWVsuHMi/AYk+HgZHJXMgZfsguHBCbutviESO6chUhEEMxCpcHR1xB+c+TNcG14UB9fWg18BHeL6AHhAsL/D4zIcFi56vrhMF+EEL3QBK3AuRbzD4jz4dG5xmToeSAtBAAkDJJg+Dkjsyy8uo6SRAeJBWgXxhwdSRjsibgHiD4zsiErOGSgUDohK1kQeGQjIn4TbnAGJhxkgATIRkIOFwq6AVBEK7QEJeZErDBCGaxCp8Ps2RCpm/4I4I5tbQpFVdJtb3BAtCIJUQELGJkDi3QBAYjYQkJDYCpCI8BeARITDV9zhxcYwExQ3DHaFsbVhVxj7AMwEHwuCmeA5yCo3jhWX+eJxtLjMbJ/4RnJxmTV0cx0xDTUgG7XDErHFZcgq27ZFJVfjsSkqOXCwcBsnKjkS4vRDkoQ4OkiSEGd1VLIqclMPQAIE5g2EUv4HkHjMByRAVgESOWBIVDLNPw1wPjjteDAJp+0GJsH5RXgdYsUVTCKqvDuaW8Zw29utR7Iim4M4ftBvLK8jCOY7KX7JQ7ofXt8ufaklA4JarYUFKKTF0+/xJQVwpYUhfP4M2Va+fCuEVICsR4rL9GHyK/BTPJC/ekHkcPgpLA91mMDy3OIyQ8/gUSvrjswyHzqpTjWmX+7W4kNs+0nFtlqWIcYlI97t7ScUjNq1dtikurzofrlBxYd4NpOKb+KlQckI3d39hLtG7TqmPqkuMrrfRufiQyzsXKdlScYdZZ96d2DIKDf2/RFvRSt62lSrTp57vfDIrEeC3/tFZxusOrQ52HBX2tcg12asbEzWbcn8jxd/szdwdz/Jjklc27+n8G+INnoXlxuM+r8gF9pPsuL/glwgyI3+5+TaId3G/wtyfsXlF7Qf1uT9+BYGpMNiT8Qw/8JCwe9BGwHaXje3WpVfEpVt8DJG2419Q9f6euFWh4jNwZ+Pa7wVvZ4x7dG/A/pzKNnCi37fA/gbTNOVJm1+H8Zvcv17Ut/EpLinnwSFBtLhu/3i6Qi4F7ND1iON4yS7iMSAU2c1UggS6iYkXZwXtDTyQo5GiqnGYY2TnodADsJLxKXmPrHYEzka1RBBYFkh5hH6+gBHLM5CYkZGmobEhLM/Cztw7iQfEIt5qJ9AQIQ0hvD/CDDkvT5IY/rNLc7I38B2Gzxgb47Vj57ePIQvzg91pLEs5G+EL9K/LtJYYXFZNWJ6L/I3JElFaaLyQ1l5NMTS/aGsXArJcLpXIX+Bu2HgFdlMG7xCuQeQxvAuH4cnNOuCU2kXci14zUCuBa85yLXgNaG4TBWpBu/accig8ssBr9bQJg6Zycig4JWFDApeNyODQv9zkEHB61gUpODVAQpF0fkGTIJXKRQKXoeASRjNDgoFr49RkILXZCRSHowGS9H61AUWRZ7nAbEZTl1oVRP0UQJz8LIM3OJk0AebOARdsas/imKAbo1KLgMPtqg3wYMuxEem/v9Lsv+Pl2QnDVgcUCnDqYcoeIdHHCyPOn0EPAunsQbcH8XMYhgfLg69ukMxccCN/VngApR2gQuoYhOQgu0aIIWAoYC0+KH7WCRopv3QfewAQFBxZAIkCpDYh7p/L6IZJZoqohkhHogIwemtqSrchorDBWkHiSgPMz84KcQZhECBOKYIFLisP4oSxD0qDheAKCOklITbNCE45HcCJEC2AxJZoA+KEiSvXIQUHqcRUihACxFSyGHIjGwERn+EFAqvGETMoh81qRTilKLURP5MxOMi7niQ+BIqDYiDZGAC4VCTOBz40ZqkDJqC4DRLmiJHgbtsMAleLyBHIRnUg0nYsRtyVE18MuX3x0yKSmoLbwKtpPI6BjCV1AuoGpXUBxoaC0P82l8wlVQ+fyCtpGqCc5gi7GOHfKuBbGsX2VZFlOxv20UJ0iMhDnlb/jgewr/+tr1ua+AfH52V22u/zP1Durb4WbJCTUBxiv6eDwGWPd0M1jr0F27KPF/gWnyc17vGrjipV+iHWos3083XJg9rfjPNo2P/IeOcfjWPLR7eMF7LGjTpjcOttk1abOn0tURvZSkZUPP2WfooX47DHxG+xAf/sKevvXs5Z9CV2mfVijX+H1z3frja9uL2luUdNz3MsiW7zw+rDlkPMK4ewHiDAaY5AGBlFIxN/9nKz7vaJqQbbmFD8D5sKNyIDbwR2OCnjA3tvbFh727AKlOwbRRvE8UbBDDOKIBxlQDGiRFzhufMm2/zRPoSb6UXKKaVlMPRFJMaQPL6A1PQHmCypJhsKCYxxWRHOdQGGGsowNj9AMb94NpxrOp+kDfXpPXQ2JJnqn2wOC8Ue53p3jmU2XxKwgV7WeOwN1kDew0os9WUhBkF86QkTlF2KKw5heVwjFvcww7svm8+emnHI4rmMkXjT2U2Bxr+MKBRpLpMpzIrUDQBFE02RbOVYpgGMEJhIyk/PW4BIpUiOkoRbaeIqFb4VCvtlG1risiRIrKiiERU5ECKaCRV3k8AIxS2gVpGkwqQTP8ppVIYU+SnKfK7FLkfFXYSFZaa0oBuMKHIgyhYDEVeRbmksNIIuuFn6ieMFSZQPxmCXSYK2GVGOZfQXQvprlbKkg21giH1E3WAJfcFWA4FY9F/tlHYXlSGdDpqpixlUQrbqLxUI3xN7IqkLHlTluZRWDuKvJBys5nKOwNgd8Wq1eZUDvYYzAyoHC5UXF/qjeMp7gl00y+Uo/lU3MkAY1MwP4q7nYJ5UzB1CmZA+WAIpNB/3tJ/hJQte8oW1RSfupQidam7VKEX6dZ6uvUwhZ1J2ZpC7UltQS4bEIe3pYSPP80PpeRAez2JEzUTUbCQ865QzA1iS1lXDUhyeTwpw2sXQT2pwevbwUIiLBQTgPCwswW7QjuayU/YVY9ds9hS3mkDwq+IJ4p4rdtWT1qwqwq72rBrLlvKSTEgBCBlAOHVxBPWk3gS/76UZLbWk/7AdAWYygrFHB/QzwB9XjzJAaZAvLbG6wPAlAeQh8BWCILAyD5rQHKexROTplJSAzYLQNADYHXAAn64VwwI72k80XxXSgzwmoe/awDpALa7+HsEbJeByR+sF4L1F2CdX0rSQVABrwOAKRvEtoL1a2AdIJHA0AMYUrHzKHZux8407ISq2sGqNXY6YqcVdorAQyB2phoQ7qN4QgDS8BEKbywlyfgrhX6Mgek0MN0FJj8InQuhoRMDgJkAUxBexwBTFXgACIkA2M9sKVkJwbMhOCQzgWRm4EkC0IUAbQVRGwh+wYDRcDL0koPXLPxtA0gv8JaOZzOIZgHbNrBPJSMfXnidd/M63i65aLJ+3dTdw0d0/XX3+tf776js0//JZVT23OVRV0f693rdd9Q03S32Xe+oDJ1rYdH1xeOD88ocHdsvN+1I5udvO/vwwbOy9xkJyzJXvfn9mZtjQ7uQSKuXvYXWDpN0uBms3My5IubMl7J0CRlH2GNJQww5UExU6ohpM0dByD0gZs2UamqTskMkvpio1xMNIbkq5rhLiZWUayvlWkkN9IhLLHl3j4yvJ9nNZKaQc07McpSy9YhfNAmE29WRnDrChq1LCOcw4UcRfgzRjCaaMSQ5miTHEHY0YceQ+BLCv0eC6khkPcmpJ5xSonqPVBeTHnXkp2aOipD7q5g3R8rXIopRRLeYbKojcc2c02IujDSWcLQJexRh6xPVWDLvHnGuJ3uaOa5CbrqY5SRNHkPK4ohLCVnaTLoISayYZIk5F8ScJDF7rtRgNHGJIeol5EMdcW8mE4WcaDHLQcoeT/xiiOI9kl5P+HVkXh15V0/M6sijZk6AkHtBzHOX8scTxXiii7CD2zSTLUJySExCxaRAzMkQc3aJOeli9hxpjhYxiSJmxSS4jgxsJoOEnF/F3MVSnhbRPEzKigmrmEpuco+E1pPcZo6dkJsh5i2U8vVJZCxJv0cU6klAM7EXkjAxZ7GULJRyl0u5NlLuPKmBLqmOJoklJKmOhDSTMUJOAiJQyobvR5PIe4R9j7BKiEsxMSmlZqivJ8+aOZuE3ItiHluqOZ44xBPVUqJeR9YKyRExx15Klkq5i6RlukQ1iugXk4A6MriZoyrk/CJmzZEmaxGDKMrntGbiLyQ3xOSAmBMn5hwQs52kOWNIdRxxvUfc6smvzWSJkBrGScobRfiHiUMxYZcQ7j3CjaEuxtMmPF3CGkVYOoQ1msDvuKMIV4cYxBBeHGkvJpqlhF1KyGHCQRjsFRNVIQkREg8hJbtaSLYJiZeQBAjJeCGZISTdhERZSPjNhHOT2AgIZxHh5IvIiiain0Fy0qXcOBFZ30R6ZRDNdCnnsIjUN5G8M4SXKSWTBYQ4Ec6vIpJZRRpcCHu5hOMlIIIqkreY8Nwk5GcBqa0iZYsJ10JCFgtIRyXhZEsJu4kYYN9BEenaROadITmpUi6QTGkiQWeIZqqUs1NEpjURl7OkzIlw2RLKFu8M4c6VkMtNROEsMciQcs+KyJ4mUnOW8DOknDMisq+JNJwl7FNS1jwJ6SIg6aAFHlcISHUGMUiTcmNFZE0TUc8gfhAqXkQ8mohuBkmGUJdFZF0TUTxLWFlSZB4SD1ruEjIMzGWSnGxkHhFpA3OZRDNbyskVEWETqYYKIMohETEAoRwpWSQgJtjnKOEMERDjSmKykPBmSTiaAqJdSRwWEu5MCVkmIGaVRHMx4cyREEVowZFwTorIrSZSepbkQKgMEYloIh1niSaEgoShTZR7XoaUhInIxSbCgdquiUhiJeGdknKPiciyJpKYQfgQL1lEFjaR9AzCTpNyCkTErYlEniVkkYQ4NJGcBYRzUUTyq0g8zOQq4WwSkKQq0u5CWMskZLOAHKgifgtIMuQ/IiKbQAg8nhKRHU3kAOxzQsqNEJHBTSQf9jkh5YSLyJAmqibeGSnLWkL8BaShipBUKdETUOcwOCvlporI9SbiCvuclXJOiMiFJuIN+2ASIyIvmojDGUJsJSSnicQ0EZImJVsE5PdK4rKIsB0kHH0BeVFJFBcRlr2EjBOQUvAHHbpIiDegoY+uAuKSScpypNyrInKvibzLJH5ZUi4892YTuZtJReGEikhWE2nPIGS5hHArCfuMlAwVkB6VJH4BYVtJON0EZF0laV9AWLMlpLuAbKokOU4kGbrniihb3IWEEyUivlVE1YmwF0k4zgLSH97tTHiOErJAQJTh3c6EayuhgdENQoHWYgn5HRp3JOy5Es5YAamCLywiPHsJx0BAbsMXIJSDhKwRkBr4AgwKCwTCF0ALZoN/xC8mbDcJx1NAPsBEiwkL3K8UkJYqkgMVIKy0BBCFELjoqSZqNk66iExqIr5niB8iaZeImDcR6zMkGZGEsELIBJ4hrHNSArNpQgunpWSOgITCF5wJG/7qKCDDQMiZsOAsTgKiUEW5hyeTgQLiDbtCbRvh3RmEO0/CmSYglyGRI+HZSDgsATkJiRyZ2NwuIPmQyJkQeE4yCKVIySoBCcwkBjDTFRF500TMMgk/R8q5JCJvm4hqJuGdkLJcJcQQDlRJCHi0FRBhJVFdSNgInmECMr6SBC4kLASPmoBMrqRq4i6UcOCOtU2kLJMQKwlxbqLOwXaScKwEJAD2cSI8ZwmZKSCeVUwKcZIQHQH1/uQFBFUhYVcR9giCE6ohnhwoJXH1RNTM8RVy88WsJVJNFHOHadZ3rSfBzaShmXDr6CHiW0e6NnP6CLn7xbyZUj9t0n6I7C0mynU0odsKcWDzUOrEEm4UMYgmOXFE4R4ZXU/ONnMshdwsMc9RqqlHHGJJwz2SWUfGCckvYo6tlCyWcpdIufZSg1EkPppklpCDdWRLM8dAyDkiZtlIk3VJzmGSV0J61ZPHzSSwmYxuJoJmMqyZs1rIvS7mLZH6GZD2w2RvKYmpJ8JmMlTIyRdz5kqJhZS4S3GIcGdJuY5SA21SfYgkFpNhdcS8mfQWcvaLuTOlOPH5h4hiKfGuI5p1pLSOVii3mjlLhdzTYp6TlD+GtMcR63vEEZVxMzEV4lDmzJay9AkZQ9gTCE4o3mgSH0N8S8iDOmLfzGEJObFilp0U5UROPAksIdb1xKCeJNcR/TqSWE/y60lzM2edkHtNzFsq5f+/UEndJm/5vDQuJ0YqtRN1mHxj0sJ922HiskZaki5MD3uT3DeB0+QjXZ0iTD/wJrlLAqfIR/p/2HPzqKaSr21URUEZjYoaZBBRaJwQcATliEBolO6oqDQOxCFtWlFRwQGFHBAIoqhBHMDptNqaBtS0jfMUBocwaKQRaRSFdAeCoJBOGHKSStVXCb9vrXvXfdfbfn/cu9Zd613L5py966m9d+1dp2o/6byLNC+zhTpyhtw5HTnWgbhojczrDPlHoySMRO4Gw9mu3tj/Qmgha3pjb7yhqUQ/FBGsJCe2ArbTebTpooZYEw95ya9QVjVNMf1QER6c2QbYR19h6zSl8UOaw6/QL43kdxJ0ho9mGGCMzlDXpRd/6uXUdEqumgZ0kk+Gukm7oAf1Co3Adiyxk1AluRI7GXUeMX/WEBuwE8ErNBkPHvFDOZc1xItdUPnoFQKXNMQnjdd/8w+dfSVZPQE1jkOjTqJJFWhXMzqlJiNp8mdArICUJ/I6iWaVG/fDcDWKVCOxmmTtVfaueIT2x+yV9Q5oIJJ7SZfBnXR0st5DBTuHy+c1Wm1HAz6iJNdHyv39OxqL9ZLYtN5Er2KDfi/qNaguxN6SaDf8GbE6Wb//puTerM+eU4sNl//kzHXoGTGqCUZ99rpgs/8QtjGxZ5RYMO/ZoHukhf7wntILfw1oINPodjbzPnqyM55jrYm8mdVAcuY8EGfQhdGsDonY8cOeMlA7xa23MfJox4t10OF0tr5zUmhvpTc5RSY19J68rH+cS914rnetTtpRxX72D//beI6LJiLpkcuwD3tegDdOnRnBvX97E0deYIjBsoo9SYUhEjuNbEZu7GX9sFxy4donRzrGrIWbkumZDzpTaJFFy/tW/oAWaogRcDKXXLEeA3o38O+P1GyK5wzTLAqrElUnBVV52XhTrN553pKup4kOH9hPgQ8DAySWmsqJGGDoX+VSt+5JVseidfCbdNrnQWwafZnRUtn6ZFTLsehcr8v6sFzC3QiI3AB/OIIBxEFavq8qozppU5XdFW8XVu8Rb2L688TRH3qfg9djNOviCaamjIsBBocqu5hpGJDRhMZMawzp/bEJ+eR2XtK7q8jhVXuqk1y0EruWh618S0AM0qyJl4yEnMP01AeEtaTxID35gZeV5PSzxJEfZqU3nl0fcLhDX9qZ5E2F9EbIe/NzZZf0u1R6vypetYGh1btXRVYnzdE+dGwpbH3CBB+sNcvjG+1gRwY98QFlTrLLEu0/iDMkdRueHOo4ViqbtSHgUEdlmazZ2yWk96Vc2ZJ77JIequLIqtBqQ7w2wa/lcivfDDzqr1kST1rDlxm0+wNxP7SiOHH4h7vJ5Kq1TzI7LIolZtMag3t9mxqZubGX9EdUnayqWdWGHdrukVUe1Ukh2tvjWnJan0wA74ZpwuKJoXyng/T4B9QQyaunicM+xB7gzFoXkNlxmVbsKXlfncj4EKoJiXd5Oao6aTonuPffFCCgyrI6cShWBWKVBVa5GDH/jcLgoTYebRYKFKownnT4RB+lQJEKNEuB7jaj3mZk14wk5ai3wkjLML9hVyBZBfKqQOIKFFuBOqXG4/mYArGb0Xo1OYGWXACNC2GnO7I7jl6UoyIFalajkTQpBigPcFZD8WRUdxqtqkQfmlGJGu2l8fEtWQAbJxpPVeI0WiRFyxXIXE2OoSUZgAiG1Fgky0aRFShPbWQkvwDOUiieiGadQO8r0LxmdFGNgmnyBsCHuew4spMikQItVZMzaEk2aPwedk5AdjnIoxxpFKhWjWbS6GdAroReU1DkaTSmEn1pRh/VaDtN/g4IDuS4odjTyK4ccaToRwVyUJPDaUkqaAyFLq6InY1GSVFMM75HjcQoC3BWQLEnmnUS+VegpGaUrUbLaPIiwBdzoydyyTYmqlmBVqpJX1qSAxrZ0GUCYuegUeVGWrtGjZbQaBEteQQ4q2DsVLTnFNJXosvN6JMaracxlaWmGO8YWflXXGZfQQV7y43rOlaO2OVGm17lSFxuvLr3NKN5avRCjerUyKBGZWr0t9rYNWjUqFKN2tVolRrtVyMLNcJbpfErtgoSIpcTyOWksa+hTiLOCcQ5iRpPoMZsIyd2KUdUBUKeKPI4ai9H1xVomxqNpclzgFgEcW8Qm23caxQ2cwJNqkTdzeitmtxDk78BIhrijIiF6G4F0iuQDN+x5f+2V9ARwFkOY79Bd0+i/RUoqBllqtFqGlNoagJyERpz0ViBahWIpSb9aclpQIRDajyS5aDISvRegVLVRvLr/zVN01f85DCjGV1Vk6G0RAyIpZCaiLxOoLpy5N+M3NQoikZ45BRoDIed41FvDgotRzUKFKJG+2jyNCCXYboIURgUT0GzTiP/SmRoRtVqFE+TNwHu1nD7iWurVCCq+Ss6H/EkdPcEeliBzJrRTTUKp8l8IAmDjVMQJUSS44g8+VW/FeEW8FglYlciWSXyqkTiShSLMeXIRYooKSJPowSoxBSexO19sg6FvkEEbu8x1fX6FZG4Bx/ThhpXIPREh+xqUSOmGNHdRkZN4tYbEykKz8KEMLIWSTB3w2392VpEfjXnI/kYVosiMS/iGMgYDKtFdpgXYQK0pRtNeoNiMQfDBjDrn4CtYeI930CO6UbzsDXMHQINyBHDao38sxEzEUxGj7ehwgIU+yuUXNehC20oowBRv0Lyhs7IJYowiVn81ewtQ4f+wNYw/bwGJZjAfcTWMP28Bkmcixlt6G4hIvBKv+9GP75BdYtRY7CBtOhGSW/QMcw7ceYwk8UEsxPTuB8M5DoMq0V1mMBgNhOKYbXo2DJEYDazoBu1v0Gd2CPmqpjcV+Yj8RUoydOh79uQPh+5XIHkGR0KaUN1hcZfB9CvX833x7WhSZjlX4YSzJintiELzPIvQ1KgQxPa0KwCJMPUdLWBXNKN7HFsmPxiJhzZjcxwbJg0Yp64tNv4k0TnCkQuMKB5bagzAhl/GXmBNwkOBDNGr3xEfmdAPt2oE9fgMkSY3JLYKqbZGbWIuAHRpm4jmyOjDKgd76SlCF3AO+kNIi9B9D+77n923f/3u06sRmZq42GOyYmvGlmp0SQ1GqNG79VIr0YZ+P5UoP3NRlqO+a1HM+I1o1HNKLIZzWpGexTopRqFqpFITXrTkuOAw4axE9CeHGRRjv5WGH9G30mTh4EkEqJw6DUVRZ5C7ZXoejPqUKO1NPkIEKsgxxXFnjRe9VQlmqBAE9WkGS05AhqDoMs4xBYipRRNajYe9xJALoVe+BY+icZUoK3N6IIaLabJS4CIhJwJiFduvOcj1KQnLTkDGr+FsR6o9zjKKEc5CpSgRptp8iwgF0NiCjp9ChVWoiPNqEdNxtLkPUBEQcoLibPR3UqUoUBeCuSjJm1oSSZoDISdbuiYEImkyEqBbquRLY1+AyQLek1CdSfQ2Qrk1IyeqNG3NFkAJIshxwN1nkDHpMZE7VSTU2hJHmgMg50e6NhxJCpHhxXGnzWm0uhXgK4ATvS/Xo6WatKJlqSDxhDoMhYdy0Y8qfH/MhxRk0cBuRoSXl/zUwBuPvC6XiiMnSa2eVqB6hToRzV6qEbONNpAo5iv+e1cRRYkdSqTXprl9zymXp6xWPplP7Fqut0v73r5dzY7f3u708D9z2OT6bGlZq5wc6O+/kbC2NonvUVBmvl4Pk/UkrJM/1E2tc/Mlr6HY9/jQ5/R/X1GA16b5idwlKRRexYTeKOdkD5xVZ+Yk68hjFMnn0dhJp80ZTS/u0/8/LpP/P4/4h8mkX3nFTI62BnfZ3FVn8X9faImxTiqf9LZDtgFjzsfRhlHkxqn+KGczZ36bbXYxP7GWRF9WpPb3qS7n/qwa/q0Tn3Yjjcm7J7TfQbr+7QtrX3YVX3YeX1a/s94Hbs/KoNoovaVPnE8Er1LoB6ryYLzvfUeJsFgEuaMNQmpfUIfrKxPGGcSLvcJ7ibh7z7B1STgJmiZf6cmE1AMv673N+3u2SirY22u3kijmwK1XSlr21bk2l1IY1ODrzpjVYi2K31tW1Su3cc0duPgqx8P0Gt9E88MdtrH/2y27UVCV+vdZc8TFw8JOMJfFFNlcb2ppvWu9fPEaYOxonJLlUWRUbHlWeJvgwK+E9g+7tfTn97hPE83Zfk0l2UC9uXGP8w1gTHa6bopQdNcrI0KHwtNyRajIi/X7k6paElG9FB98oDZx5wNpwpy7QqKRdWxmw7TP9skOhhOXcy1ExgVeYfptbZGxeQqizzVp/SLQV6/NC611ASGGQ0dmeYyPc3oykoTyDYqcqa5hBsVvztoSiYaFRb/wM5dzPsIbulatXBDwDeDKJbkindLKku1qWvVvg0BrUbFjz4tqWFGxdu1AT/YYEXz0I/ocYBbbyOF186ssrgix0udXpL4erQxGS5VFo+NivCSxGlMYzJWVlnEGJPh+FwvmcVVQH1RdawnXom1MfCruXZnjCv5hBV2RkV+rt1VoyIqg147xahw24t6n2c1kORM3ZTmaS4n0o0rstYERhgXYJjm0mVU+NhoSpYZFe3eLtdSsWLniE708US2vlOGVzS3JXWncQFnNgT8ZG5cItGSyjcqrm4IyDUqfjRrSe02Ko7KDY1nfXciQz0ub3HiYmfjiqyqLF4aV2RdnPjaxbgi6yqLGqNiy9PE3/obFTseobNe/0lISJWFvXG5t54mvh5gHJxfZWFmVFQ9TZxmZlS4V1mMMU4/JyQPVRInmymdmh1Dix4C3x8g0wtVnSJXSIlVzVSSWqZUs4fQokOgZj7c5Ia6hKRASgxXUHPUsoW0RwSs90LnTpALK4jlzZRAzV5Fi0TAdxnM+wZVnSRXlBNRall/WnkCRCyE+e5o+nHyXTnxWEGtU8vG07wLoGglrHdHjifI7yuJK81Um5q9gRY9BjWrIHMqCj9FtlQSMQrqi1rmSiutaFEWiAiCm8aheCGpkxJuCspTLRtA866DoiA4ZzI6l01WVBAzmqmranYoLRID36UwbyKafIJ8W074N1NualkULToHIhbBTe4o/jipKycuK6hNatk4mvcYFC2CcyaiRE904jS5uZJ42Uy9UrNJmncL+K6BUZPRFiH5ezO1QS2bQ4sOgIhQuMkVxWeT5lJig4JiqmXDaN5lkOOGHE+TryoJ+2bqgZq9iBYVAt/FkDkJVZ0gT1UQZxXUB7VsCK2cRCsDaCVelzet9KSVc2jlBFo5g+blAc0NUBQK6R8gvRLmj0PTheQ7KTFPQbmqZQNp3lFQtBSWnyB7KgmPZipWQRWo2UG06DqoiYDMiSj8BNlSQRQqqMm0MhnELYT0in/NfGM5UdtMPVez99Oiu8B3NcybjKpOk2+lxPtmKkgtG6WW9arZNrQoE9QEwk9uKEtIXpUSVgrqtlpmSyt/A3EsSAfCfE80/STpV0EkNVPZatkymncRFH0P6z2RYzb5qoIgKojbCmqjmu1BiyjguxDmuaPJx8m3lcRDBT41ZafVMraavZUW3Qc1UfCTF8o6RV6tJA43UzZq9h0QFw7neCAwGb3JJldKiTUKqr9aNprmCUBRMKz/BimkxItmSqOWeanZa2jRFVCzHDK/QVknyY0VxKJm6ju1bButTAOak0BTCDRCoMkFmhNAcw5ojgPNGVAUDhMiYb0bSpyMEqcgsAom/AATVsOElTAhGiZEwYQ1MGERTGBDej6kgyH9EJQZ6r60kYfyUf4vUHRBR61oIx/kI+YvkHdOR0W1kacKUf0lyBPrKIBhhSj/GhQ90VFvMawQMa9B3iMdVd9GrvgV1RdAHqWjBrSRC39F+ZegKEVHzWwj9/2KmNjAAR01B8MKUPkyVMQxsAO7iQm1aNlyVLPYwF7VTcyrRdbLke8Sg2x1N1H4BnHz0ZyfoUioo5a2kQX5aBMO77yOim4jBfkoD4eHY61tI+9g2K9QadtNXMHWIlHNDwb2/m7iMbYWiXyjDLLEbiLmDdoSieK+NcjOt5EVOLaLUJSpo2zbSB2O7SLkHdJRg9rItwWo/iJU/qajSjGsAOXnQ1G+jjqKYQWImQ95BToqHcPyUX0+VB7UUQ/foBMRKGKRge3bTZx/g7oikO9Cg2x6N3H4DboWiYoWGWRLuok9hchTDEV3dFRLG+lXiD6JIe+WjvrURo7Euf0ZeqwyyLy7iZA3aNliFBFqYFt1E2veIOvFyJdlkFl3E4tw/MtQzmWovKejLrWRE3FshVB0RUfdaCPNcWyFkHdVR11pI2fi2K5DZZqOel+LRuDYwgzsed3E9TcoHseGEzC7m8DsaTKOLcQgi+4maAyLRBFRBnYShtWieJy0HwyyvRhWi64tRkXLDbKAbmIQXimOLcTANusmtuKV4tiCDbKB3UQcXimODWdHoqMO4NLj2Aqg6KKOuoNLj2PDm+KSjrqPS5+PypeiuPkGtisuFl5sBKr51sD2x8XCi8XhhRlkc3Hpa9GWCBS32CBzwtbwfrsBRTd1VA+2hvfbDcj7XUfRuAp4v92ASrw9/GvRiSUoIsjAHt5NOOHYliDfBQbZiG7CCse2HBUFGWTruol0DFuOIiIM7AgMq0VdeLMtNciWYRheaQQqijDIGLhY+chTBEWndNRGXKx89EkEeSd01CZcrHwUhSt3WkddbiM7VqCIaAN7azfxEidtBarhGGQ/dRM1tWjyClQUaGDjam/A3wvebHgTH9VRI/H3gjfbz5CXpaOY+LPC8f8KPUINsh249HjrLkMRkQb2Ilx6vHWXId8VBlk4Lj3OxlJUhHfIkG7iWTNVoWYn0aLboGYNZE5GWafJjZXEJAV1XS3DJ3EcLUoDESFw01h0K5vcJyWCFNQQtcyR5l0BOZ7o82ny+wpiQvO/HbeeE9CyHNKhnPiioKLVsmk07zjwZcOoKWhLDmlbQYiaqT/U7F206CaoiYafpiDr06R7JaFppmaoZbtpJT6V0kEEC+a7ounZpJ+USFJQDLVsBM1LAUUrYL0rcjxFvpISPzZTZ9TsCFp0GdREQqYnCj9Jjqwg2hXUebWMQSuvgYjvYf4END2H9CsnDApqiVo2neZlg6JoWD8BOZ4kn5wgKyqJ281Ui5rNo0US4LsS5k1Fk0+RM6VEvVqG7yV8VeJT3Q3dEpIPpISZgvJWy6xpXiYoWgLLs8mjlYSymTr0r6dtHPVvp62NsT1fevY2Wni798odaVx+z5M3KxKm1n48XXFCiXv0/Mkm4Z277ArGvW++v2w/Z3PG3NObOxd96QOsUa8ueOx1coqsuQYWHpwbGXih0E12W6h8II0zU9z3VjdYJ2zLnHt3yYXKbBP+5TvSaGrVrT6Xm9FJbOvH/zw2mR5ba+Ako9Mbhkhs+naQXmz0KurhGKOb+kViNLPli0S9CS0P1ost35G11bAwvYez5hZy+NlQN/qLZONP6DYeOvKObP4Jm1Smz4oxPe72SXc3mx6+xmVrSqOMESXIt/Y9vvQ9nPoe5/sea/oe/0dO9Zceq9kFXw73lBP5d7zeeeFMP2lNBjl4INU0YGsa8JtsGjhoGrhsGjjaNzAVjThJTqwgdjVTp9TsSJr3M/BdAfM80eST5Mxy4mEzNVwtS7+Yes9JkwizGjqJUYOv/pqWeONctt6gnFgy4rpQdt13pwQ1uOeuH6pO2M7q6KUyBkZULQiQvZIaoObc2kNTpMrrzPuN5JPBMseXmkHbL+SPvTCnQjlxtmyZvcyxOW7MOd7mw7yj6oTBr3xtnvmG0nMtlHktf+VdTnz1NOHq4X+qqudyhirzLOVYc8OSWlGGIQ0jA7ZmGOd8k+hnjY1oVmvjnY1WJ2u7hs2Nmy4bVZpw1V9VtSQgMpVd+Deeuc2CuvPCaNw6wOmocaZzop+LceZMbddS44zGFE34HqPXG7bU22KjD+eAL8lG5OxEvwFGZKI2vr/RxxfVpx/3tvx0QTIcR2W0faM/9fapcUb/gC8HjTMGJvoNM84YoI13Nc5wUn1qNs7wysI+TOsYQr0tNc5gBHxJM84Yn+hnaZyxQBvPMM44r/pkb/JhpczreI6Rq60Crh/ByPvDE98xjchvtPHTjcgQ1af1c+NOyB421Uwy5oqc9crXvsQ4Y0zA9VTjDN/Ed4OMM37SxlsbZzxTfRpmnFGv+rR8b0trY9mkA5rwkH9wsupS2O3GFHQMoHabKmEe4GQqzZDEByOMtbJN1PnPjevqlNs/S7i6X1VVLTm2ZFRAEq6sQT96jbxGZFJ5jwxIGmJSLZLX/Gh08eJPGwo6Y9X+vb1Nc2s4Jtg/QxN10y7M0fV6XzjApvAiUcDzRapPlaYJP5hTT9J5R2FS1jzVJgW1v5myaKZCmyncFvOaqVHNVGQzNauZ2qOgXqploWqZSM32pkXHQQQbbpqA4nNI83LibwWFKcpOmncYFEVCOhx6TkXLTpFtlcT1ZqpDLVtL8x4B31UwyhVtOUnib4iqJCYoqIlqthktOgJqgiBzHAoXki1SYlIzFU7zJCBuKfT0RMtOkg4VxNZm6oJatpjmXQK+kTBqAuKWExYKKkLN9qRFZ0DNt3CTB+o6TgrKiRwFlaCWbaZ5Z0HcYjhnCjpxiiyoJI40Uz1qdizNuwd8o2CeF7qWTd6pJDIUlJeC8vnXrtxzEnpzgjxTQTg1U0/Usm9pXgEoWgyjPNDnE+RRKYETtVPNnkKL8kBNGPzkgbKOk1fLicMKqlstm0orfwWaKyAiGuZPQdNPk36VhKGZqlbL4mneTVAUCMtPkfiUUCooSzXbiRalg5oQyByLsrLJjVJikYI6omYfBXGr4RwvNHcsmjsOzXVFc93Qo7Ho0Tj0yBU9moIeeaHEbxD4DtIZQPMz0GCPvwDNNaC5BDQFQHMVaDDjiYL0t5BeBmn2vxaQFoGIKJjvhW6dIh9UEqnN1D9q2Raalw6KlsN6D5Q4FYFF/8Or/p+8yho14G4xV0ctbCPbMGW4AkVndVRgG/kcU4YrkHdfR01pI3swB8H9OO6lKzCsEG26DkUPdVQJhhWivOuQl66jrmMY7gRx74Z76YltpMOvaNNlKMrQUa5tpPuvKO8y5OH+G3doetxB495tqYG9vJuwqUW3MKPBreTibmJ4LarC7edCAxt3k2ZtpCU2iCM6o6NYbeQ73FriiHCsYW1kC24tsV8caypuLXGjvdLA3tVNHMHWMKPBzd/2biIHW8NtO2YEu7uJjDeoAbMT3Eo6tJFnfkWfcKDHdBSjjbz6K4rCTeZ1HYXbZ1vMPDBdu6ajzmBYAfr0KxRhIpeNYQUo6lfIw4QKU6Kjv6L7mMTM6SZuv0HTcVuOWQPRTVx+g8JxX4xZw75uAnMdx+Uo7q6OUreRm3HScKKLdBRsI1k4abgtxz26TRv5ey1yxLQj2MAe1E3seoNuLUY1mGn06yY2vEFVmE9g1sDqJjATPIpjw0QBc4MHbeRKHBsmCj/rqJtt5EYcGxZydNSHNvJ7HNsaAxvzo5Nv0BscG07nrG4i9Q3KwpQBE6yd3cSqN+jzcpSDSSXupU/Wojc4aTiDezCsFmVhRoMzaIlhtegzjg0TENxLL8crxbGxDGy7biIIrxQzGtyML+wm8nDpcWw4Vb/oqAJc+gK0CbMtkY66iEtfgPIw2zqpoyZ3ExZv0IilKAJHNAMXCy8Wh4cjmoaLhReLU7fSIJuJYbWoATNOTPnasDWcOrz5cB7l2BpOHaYUR3QUpuC2+eg+ZifMbmIGjm0JqsEswqWbcMOxLUFF8w2yld2EP64CZh6Y40ZhWC2ajjfbEgObg2G1KBxvNkyb/DAMV2EJisNUZScuFv4QrkIRTicfFwt/CFchL1tHdbeRuzF3LoDKbd1EM04aJjGrDWxeN2HASVuBfNcYZOu7iTGYiReiOdgAJryW+HvBHwJezGEd1R9/L/hDwPQZc+kjbeROHBumkssM7BBcerx1l6EazB7n49LjrYtJ/zKDzB1vpFp0bjGKE//7SenpjpYdJ9vKiesKaptaNpbmnQO+i2DUVLQlm+ypIKjy/2FE/+8wIjlVeBtVeLFHnPaYWDm8uznzrTp4T8L63+Z6R1/IncK+JvS4UzEcU4iVV+dmb+4s/LHcKLybmTCu9mPk+6lsTIQ2+ysyMdXYKjIBvhz3wEzj9hYToDLbJHxQBxc8Ft8J1YuNvsSGyB1fJBNj0HKOXvz2HbmrDsb81sP5+zY6c91QZ/lF0kdvCn/8z8NEbwr76E3kexO9Ed/pozdb++jNuz56c3tL38Ox7/HBxJk277+FHqzUi8E70r4OFv7ew+m+jTZfN0Tu+SIxj0EzovXi+nfk9dcwphDb2h3tH4Et+4vPX8F+ztd9vNr3uNH3uNj3yO979EFevcWBvYpRmkjS7T0mynS79ye06yGICIf549GbHHJlOfFBQQWqZbNp3ilQtBrWj0OfT5JLt3LusmHY7aQPfNqjtmcX7rWWva9zOmEc2POtaWCdaSC0b8A0Y0+4aWCHacCjieI+R7Q4+B4Kws/eYqiHb7OQJjbF0GvgpGYfLwmzE+hDD/geLwllC/S8A17HS0K8BPrIA5OOlwTFCvR3Uz2OlwSeLTNklDDKmyh2mYE44HJ8bw93bw+Rrn9/4Mtxef3hdL1F6gz80p6u90j1xi8P0/WjUqfglxfp+lmpnvjldIb+ZcmwctXn/WUGUclQ/IKexnGG3iAtk1b7zN0Pt3Mmj/ZkXni885541Llsa9lHv4Y9Vk++FfBcHDteDIocX1Yks+2txMHI6+8K9I9xMKrPVIaeNCfG7xAEMC8YzmyniD+nYgvB98SO14wWxjbctX2OLXQyOiwsVmEL7MG9RaZgIgX666Zg9GUGpSmYxjS9VRlTh1cVU5aUsvYhfjkuf7LwNxaOrWx7o/toN2yZd89l2BZsOcC7QTbkKrbMse/wTZ2ADdql6XP67Aj0h0tNdtpLk0pMdpbKn2w22bm8vXGmyU7kPRcHkx3PBpmNyY5TR9EBkx2BPqLPTrr+cF887WVJF012/pA/WWmy8/f2xqkmO3vuuYw02ZnTILMy2RnecXfQIrxSF8teUaorNiix6z2d5tuFLc5K0/ubqlWZpm8rccCa70qT9GuzsOUAOX/vbyE+c/UnthNE7jjmhaSt9xqdJ2PL/NkNJMMav8Cge5Lp5y6yBTyJd0Mjc8tpa1n0zIa6wW+x11iHDv+BG7DXXptetwPGqosE+kElI7CL9lLD0bWZ2IWbHIZPC8Yujsnh79tNBbu6nTPbVLDV98RMU8EmN+yxNhVsVEeoualgVr1WpoLVZejPmwoWWmaoX3sQG6RKDc1y6yRs8aUc3jAlSC1/ssKUIM32Rk9Tgsh7LvamBM1r8LI0JqhxaMeevgRZ9XLMg/BLKP/eONO6ZKM6Om3vLBLwxEM7YixcJpQV3R3cez71MPZukaavLgnC3n1LDX81/YxdHZEH+Gs98CL23iNGNeTg/FiBnDQSDkQNtmhEKjmxhOhuolao2LO0vKHA1xbmDUKTM8g7xcR+OSVroshSYlQxEVlG0HLqLxX7R60oANQ4wE+2yFpAPi8jiuSUvUq2QstzAZo5QOMFlHxXEGcDIvpBz/5oWQrpUExsbaKcVbKhWt4A4GsBo4Ygbimhl1M5KtkslSxEJbutYgdqReNBhD3cNBjdSif3lRJBcipTJVut5dmDnEHIMZn8voRo/IvQH5ZT7U3UAhXbUytyADWD4SYL1HWAFJQQOU1Ugkq2WcsbA+KGwjk2KNECrR6AVtuhhv7ohIAsKCOOyKkeFTtWy5sNfJkwzw5dSyHvlBEZTZRXE9UppxbJqTFNlL+c8lGxbbSiIaCmP/xkhrKSyavFhFUTdVsls9Uqp4E4c0j3h/et4X0G9LRGJ9LJzaXEd3LqqIrN0fLGA197GDUYXUsld5cSoU1UkUp2TCVrVsm+U8nWqNhztKKRoMYafhqIulJJVglR00ThLOzT8kaBuBGQHgbpIfD+AHifCe+PgZ526I2APFNGnJdTUCX7ScvzA0WjYX0/xC0jXjRRcSqZTCU7qWIHaEWDQUR/mG+GbiWTD4oJsybKWyWz1vKGgCIGLE8hj5YZL0lOE1XYRK1qoi6p2CFa0VRQMwx+skTWaeTzUsJXTl1Rydy1vFkgbgCkLSFtD+/3g/cdYLkdGpFBTiwjuuXUWxV7j5Y3Dfg6wzwbdC2ZvIML2kTJ5BRZQowqJeqKiUFN1DAV214r6g9qzOGn/sg6hXQvJuKaKByqOyhygXOGoEQztHogWm2FRqSRC0uJ5XJKoGKv0oo8gO8ImDcYVaWTK0qIKJWsv1Y5AmimgDhvEOcEIgZDz0FoWSrpUEJ8aaKiVbJpWt4w4GsHo2zQllTStpQQyanOJmpRE9Uup/ybqD9U7F1akTeocYafbJB1BuleRmjk1AyVbLdWORRoBgHNPEgql2qV5lrl91qRNYgwg5sGoPhkUldMuDVRnirZAC1vCigyg3Os0bkUkp9MTk0lt6WRlmXEj3LqjIodoRW5gZpRkDkEhaeTI0sJvE/Pq2QMrXIy0LiBuDkgbjiIGwUibGH+IDQ9lfQrIQxN1BKVbLqWxwBFzrAeb/R08kkayT9A3kgmtwnIDgFZUUbcllMtKjZPKyKArwPMs0WTBeTMYqJeJfPWKh2BxhfETQA5dtBzAFqWTLYVE1OaqKkqWT8tzxr4msGoAWhLGrmzifpbJUvQKkdplRO1ypFa0TcgggHzrdCtNPJBKWEmp26qZOFanicoGgLrbdC5ZPLJAZKfTvItEMBb0QLet4MJg2GCNUywhAm2MGEITLCBUebokRX6nEIGCMgnAhLaoNWD0WozNCKZXFhMLG+izFXsMVqRBfAdCPP6oaoUckUpkaeS8bXK8UBjA+IYIG4KiBgKPYegZemkQymxVU5dUMkWa3njgO8oGDUIcUsIiyYqQiW7q5I9U8l2qdiuWtEIEGEDNw1Et1LJfSVEWRMVppLN1fJmgCIbOGcwOoejSSWnppA3MsgOHEoZUSunnqvY+7WiWcDXEeZZo6oM8m0x8V5OBalko1SyXpUMb30bley8is3UisxBzUD4qR/qSiFZxUREEzVIJVup5VmAuNFwjhkC/dBqc9QwGI3AJSolZsipqyp2qFbkBXyHwTxLNDmNfFtC4KPETSWL0ipDAYL/vz0AaCeg4WmVzlplgFbpqlXiXeepVc7RKidolTO0PCbQTAVFFpAeDWkHSM8Cmv1aJdFESYqJ3jLCrow4VkawywhZGeFVRojLiNgSorOEcCkmqGKCzCDhpCS++3/1rwX9TedNkssSVTyGtmgAiLKAW/qTu1MIi2JqQ5OMqVIO08a5gTwz0jkDW8f+qE8a3//yH6rR+EZeklRkwILBukLLVnHbz5LNKbDASlfYv1X8/GfJmRS4eYiu8EireOdrjlMoucvZUOvVKv5Dyfsv/0GRknfjKZzk0sMJ+Q0VJBsinT5LVq5FtYP04v5/kkdKYIx5D2fRb+jdQUOkbQ/nxU3kd8xQ5/UneaU39JNhyT7yxEeX/8urgd0bGlkSe7c4NkR+95Bq1Rrt9QngrT1/zGDJkfTGjaWxi+R3v1O936b9MhD02IOeSeDU/23+/37tIj64rEmHypFdREg1WhaMIkYZ2Iu6iDXVyDoY+Y40yMK7iEXVaMsCVDTGIBvSRXAuItrewH5AhPV6fILjP1C/6HPiyeMd7D8M5egW8X9a+hzGx/3HPpIS+04Jwv8Ryb2NLiX6TnaToZengnqrvchg/whB848kOaZTIjmm+ZZ8mvDL++3On6LPhe4bqsx2fpbwi8cO509RWGQos6OxOAuLTudC32XxP7PvfTzKvzZad3Nh64u2iy4x6fxrY3Q3A1tfPL/ocjadv2WO7uYUVVutajN/7+3Zj1Yyo6/bie8IIh+WFabKa/9RtW3Zu2vQowf28JE5nNyPPJVMFBZTNk2ymSqenTZuMJjTH54zI2+k4a+gPEZefkDFDdv7+8S59UMvbLFi96R5ZJTmDJeX31cpJu71sQp4ZBsweaDLqQN2hSUZR5rKdqvWeWm3j06cPSTgnIXLDYHd3ZL9dWX79xTvvyJ/3KaK3rBXPO9R3ZiPY2w7vxP0tpTtj2l6/EX13kb1/rxqFVN73Ry8Hchv7yfpTmlkFcdGNN0dpHq/UrvVYu5ut7krRjtPsjpWm/biTOkGJ/nNJ6o/v927ZeLcO0OdV1kc+5Im3nZA3HFAXFESebupcKNquYe2wCVxhuWTM+acKQfEb8siHzZdOSu/8lB+Ra1aunVv/pxHb5jRbXZeRwSnr5YtOiy/YqNaOvMrbpC8VU15l1ThIXuvTp1bM+zCJ0uZdRrveamvrzzviqrKfe/GWYlqnlbhrFUEaBWuWoW3VuGpVczRKiZoFTO0XGaiemrisLy23ml/kMXfkinZHRnZzoJs54zsC4LsCxnZ0YLs6IzsjweyP6Zm+6y9QBTPvVs6l0xPcE5OcE5JuJCccCElITo5ITol4WNyAtSTPbjTxM2eF9CMBZpvgAbfJ/iqHQc0E4HGHWhsQRET0oPhfSfoKUBRE7QiZ1BjCT+ZI+sD5PMSoqiJwkfySC3PC+S4QE9btExAtpUR1+VUh0q2VsubC3zHwKj+aEs62VPyFQ2N52B0Io0sKCVs5NQdFZut5X0DfBkwzwpdO0D+3kSZfc299RW96r/3pxGjoacVepNGniklnOTUE5XsWy1vIigaCqMs0Oc08mgxsaeEaG6iVqrYvlrRcFBjB5mDUHgqObKEwB3TGpVsiVa5TSuaCyLGwE22KF5A6sqIy3Lqk0q2XsszAzk2yDGVPFRM2DdRfiq2lVZkBXz7QaYZqkomTxUTY+SUo1Y5G0SMgPmD0Zt0cmUpsUZOpahkP2h5OFsjYP1A9FlAfo/vCPyvhLArIY6VEOwSQlZCeJUQ4hKisonaI6fmqWQvVDJ8ZWHCkCGnXsgp3NrhS+y0nKqTU3ebqN4myg6zfXzdlBJ2xcSxYoJdTMiKCa9iQlxMxOJ/ZURnGeFSSpCpZEc66ZxGOqeTF9LIC+lkdBoZnU5+TCM/ppAzMaaEoEqJZ03UQhV7HuhcVUJ8aKICVbLZWt5IUOQI6wegz+lkAF53GXFSTulU7BityB/4joZMO1QlIFcUE6vkVJJKplSxh2hFlqCmH9xkhrqSSUExMbyJmqOSLdR6DIf1dujc1zS8EZYw3xxNP0C+KyEeN1HrVLLxWp4zKHKA9ebIMY38voy4IqfaVOwNWtE8UDMGMm1RuIBsKSNimqgvKhk+r62+ppf99/5F5AgirOAmcxR/gNSVEJebqE0q2Tgtbx4osoJzLFHiEHQig9xcRryUU69UbFLLmw58nWCUNdqSTP4upzaoZLjpGQAiLOCm/ig+hTQvJjY0UUyVbJiW5wZyzJBjBvmqjLCXUw9U7EVa0STgOxQyrVBVGnmqlDjbRH1QyYZolZO+6h667wITRsMER5jgABOcYQITJjjBBCuYYAfpfpAeCGl/oOkHNIOBZiDQWAPNAKCxBBpzoJkJNAFA4wo0s7RKF5UsVSW7rJJhnooZ3GGVrFAlO6uS+atkG7VKN60yAnc5ctNe0YrsQY0NupvaRO1QySZreXNA0SBYb4USzREYDvOt0ZsMcmUZ8UFOlahke7W8maBoAKy3RJ9TyQDcAQ36CqLMXq4VuYKakfDTEGSdTrqXEnFyarmKPRLE9YNzBqFEq69hRP+yWZT7v6bx/W8PFp6ccpH/9wfLIq3SQyuaCWoc4Sdr1JVBssqIGjmFW3tzrccYOMccnUsl+YKvYDJf0bx+DWOZa43m2qK5+MUOPbJGj2zRo0HokQVK7IcSByDgBGnx6PN0gsvUQ2V51MvtSxxfgS0ph//a3sKbtHpEhi+RZ1JxS0J2zNYU2ZhQX0yqz8kmlIcJ9bDp0uxzdLnAqPKwMKE+9KFMtpRm976x5fE/ho4ygs9WD3omr5KNjA802rRfs9jo4JKVcdLkPp2NSbelD+7/2gT/vm/Iqg9+2QR37NONMek+B5rgLj+bhnpvItOK/iRNvj5LTEvq4WB8TqYh0mhjHbLH+Pe/mYBf+oC2fUAPE7DISi82Lus5nISj39kH/NAHdDYBlWb48ZtGN3TkobJX7W4/fGPrL37822LH3dH6tRdnf37UU7Zgx58JTsnpGOQ32OrZOuWuwSbotMkjMs7XMUwTLpsmvDBNOGqacN40QTfaBB1uAtlVo6frlN0MHWU0ewlllr2KcekTLhuF9n4mwdY00j60T+gbGWwSjvaNjO4T+kYGmoQAFhqOg/FoJf96TQMbmBmk1HiC4RmvlL5aD9vzxiPf0Q9/qca/Eaa/Naa/Xk3U7PgAXt9fufEvp5TY0Zr4qsT0t8z4F9NsbDTRBj29qKH3P0d6vYx8GvXz+9dWH6zc5g12m2fpNm+I2zwrt8eD3R5buj0e4vbYzC3EPYyr4MfSx/b2ts38fLEjPcw5Lcw5PexCWtiF9LDotLDo9LCPaWEfU8Iu+c6qjvoZtKYfKvv25LpfdNOWxozO98+aPprlYOf2UpC9ovjb7LCpUqnho7p2b+9Eomfxs3XHK6aFJeX+MiP8Dyd3pnVOVkbYxrLxk9Yet/LdKbmbaPPx8cTkecuG5P5iGf5HP/dNZjldyWGC4vHD1x6fM618Ya7iUlZD5xTO4T1iy/V2y4e551vmTE8Le1c6ft664xenlQfncqeG/27pfn+2W+/+yjr5h8IFfwadPBBWUDL+yNrju6eFeeVyR4f7DHHPs8i5JgibWm4MrRuv0/bzxYVl42vXHX8+LWx/7i+zwn0c3fOsc6oywt4Wjz+90ZgO80f7d3n0LP5x7XGHaWHDc38xC//Dwp3ZPyc8JWxk8fiYdccrfYwrcMMryJq3LCr3F/fwP0a4fxqc05UexiodH7HuePq0cr9chdq4gsa0PeKOUucCx/ClVu6bzHPiD4TpSsZfXnt807TycbnceeE7Q1gdvadinz+M3LPBbrmze75NzvSMML+y8YZ1x6unlcfncr3Df+/vXj6fBfozd0zy+UssPWghZBwb63ZhPmtuatbe/sx/LH3+suE+fZ+7arj0IDHObVsQq+Fw1t6hzH++8fkrjPu0SHpwVDZDPM7tYzDrUer9GZlZ2uE+f+3mBntzgydwg+dxgydxg2dxn47lPp3NfTqZ+3Rn1fVRY91usFiKUNaIUNYyFutNKOsWixUfyrJmsbJCWVUs1rXihn2uPn85cIP7c4OHcIPtucFm3OAx3GA77tOh3KcDuU8duU8XYkOubjdCWCOCWSeCWctCWNODWbdCWF3BLOsQVngwqyqEda20YZ+3z19+3GBXbrAnN3gGN9iNG+zPDfbiPp3GfTqe+3Qu9+nmquuncUShrIaUrG+OZP16NOunrKyfjmW1HskafTQrNysr91jWD6n3u5dyn9pID/pnM34UMmYIGd8JGU5CRpKQ4SZkRAgZVkJGjPfdaEHWPQZzyWjmkpHMJcOZ1SOY1Qxm9Sim9zCmtz3z5lDmTdu49mbpwbNCxh5XtwpXt82ubmdc3fxc3XSubixXt6uubiNd3d5u2PZ4QKfyY1aR5Z4N26Ycu397ZsvbltxV/hu2fTl2f9f0lrefcleN2rDtQ/L9GWbMfyy4T69LD0rGuTnjqh3I2juA+Y+1z1+DuE8nSQ/OEjJiL78PH+sGD2bds2Hu8PL5K5D79LL0oEc2QzbOLRpX7VDW3uHMf2wfvBzv8xdbevB9NuOZkHFSyOgWMq4LGR+EjFQh428ho0jIqKzeGp2cdc+Bme3CXOLIXMJkLnFhVjswq52Y3qOZ3s5M7zHMmwM+tKRLD8YIGYOEjAlCxnIhw0bI2CpkzBMygoSM4UJGnJAxCRtKy7qHczSUuWQYc4k9s3oos3oks3o403sE05vB9B7FvGn5oeWZ9GC7kJEuZFwRMmqFjCNCxhch47GQUSZk5AgZGiGjEBs6kHXPmbnDwufSdJ9Lfj6XvH0uzfO5NMtn/Wyf9T4+6wN81ts8eLkomzFrrFsH3imBrDfzWdMDWfHzWV2BrKz5rPBA1uT5rGshdxOYPn+xuMHruMFR3ODvuMER3OBQbjCH+/Rb7tNl3KcLuE/94s+ednPbtoDVkJ71TUbWr+lZPwmyWtOyWjOyRqdn5QqyfkjL+uHYNo3lOd5D5oeWD60zKy+/v3ZkW9GcBy9ftc7UX37veGRbnN+Dl89aZ876+X3VV3x3f5I4h2QwqwFvrFHMf8b6/LWE+zRHepCXzfDC5QthPcrM2muhF/977W6iB/2YO2b4jCN8Ls32ueTjcynA59IMn0v+Puun+ayf67N+ps/6QT2cUePcbgSyRsxnnZjPWhbImj6fdSuQ1TWfZR3ICp/PqgpkXXsK28dxn96WHjwgPXhcerBAelAgPXhHevCC9OBF6cFM6cH70oPL/yTXZGXds2UuGcxcYslcYsOsHsystmVWWzG9rZneQ5jedsybDnrxIiGjzs2tA3tjsZaFsqazWLdCWV0slnUoK5zFqgplXSuB7f/+4eG1WTN3uPlcmuZzabLPJXefS54+l7Do5bN+vM/6iT7rXX3WO/VwvqJ4hjr7P8kPWYY63COcfAELZ/ZwXt5EbVmGSPKzhLUBzXDSi5/9SW4txv2E/9WXrXZ//7bh3YH6aVi4LjXM/pzrk63f8af3j669xgFBw7Gyo5v8dxJ42Mk0vHucaSDdOOA55j5lZ1jHqBLYC/W7hPopQv0aod5MqN8g1PsK9YuE+jHZ+jmmaTMvE1nYktXGMizcWT8TC57Di9Y57g6PyrUxeTKF8KVPSDEJ5/uEdJOwpUpAv3YZWbLlh/5F65yqBFOmjdldsmV2vyL56CqB77QxU0u23GMUrbM1Ofjf3nQmb5cIkzeDyVvfggXQ5K1PSDEJ5/uEdJOwpSvD6C2Q/KG/YZ1TV8aU1y67A8nZ/Qzy0V0Zvq9dpgaS9xiGdbbYQXz8fm/y2cXL7zO2HAkKvetS75BxtKPf7zNsnT4EcmMc/R4ESd9iUEb29R2trUfHrvr7dbVTyJ3nFy/7C4wT9jjWrxyhiQzhxqzhxizixkRy2+dz23/gtodz8b5pd+e2b+O2DzEZepwr+et19ZcyYJ9xdBvRIsZWLaYZXfsfpCdhd65VHOzCdgM8hM0O1ETiKZdNU5xMUzpmt4gv5UomrocFh+nCCS3iG7kS8/XQ4TAd494ivpIrmbkOvjtCtw/ElqtmU26Sv6b9fS6Y/2zd8znp4FBZVpwDbZ/BVFopIh03nvOVGkG8bM6O3B1P+kAZJpCzCTRJUZckFWcIOcdcJRcW8OdlgH1D6G4HdQxT3b5GUeesqHshFYuzOaNwU8H5y+GcYVJy9M2B+55NbLUf83PkltTomxb7nrm22nv8HHkuNXrHhH3PDuO+wmvHlFfQPlj8w7C8VfZ574fmvR+Z9354nv+IPH9Gnv+ovIeWeft34I5hwo2yesmCVvtFrnGFrnFjXOPeu8btd43LcI3zcI1TusZFusV54abnydPu2fo92NEf0oY2acNzaUOPtOGjtEEubSiXNtDShhnShkW+Rrdmr+CHzOib1j53zX3uDvG5a+Zz185nz0CfPZY+e/r77Jni0/s77mcWNRwsItOrJ50KaXkb3DIzpGV3cEtPSMvR4JbvQ1qmBrfcCGqJvpCtT8o8u72nc2rXoWbh/dvC+y+F988L7xuE9y8L79cI7x8W3m/Pvh/JNeZl4znDwzGP1ydwt23lbovjbuNxt5Hcjp+4HfHcjvXcDnNux0nm/cbT0owiMuT1pBXzW94GttyZ37I7sMV2fsvRwJZX81umBrZsC26JPml0u2p7j/ibfc8OSRseSBtOSRtuSBtSpA03pQ150oYr0obvpA2N6cqDcMrJ1s+N81rtO4NaAtKqkqzz9CN8eudwOxZJG+qE9yuF9zvHxZEuF9x0d5ddIt/3emFkaEtAVlWSY55+uk9vArejEiOz78e6xjXiFR/UaOqaQGL/c5RXikazfDv/Sf9XsrslNO08myAGKpUeTQA4nqM4mRrNKNcgcYhgZLDgVLBgRYhgZrDgToigJ1hgGyL4PljwKkRwo3QbXnjrLunrBdLXS6WvN0tfs6Svd0tfR0tfr5O+DpO+TpC+rs1d5RUoiD5m/diMudKBudKJ+W40850z890Ypp8j04/J9HNhPjCPa78ifT1GeHHPuKDKsUExY4POjg3yHxukHxsUOjZINDZo1NiguvXbHlsz97n53Jrmc2uyzy13n1uePrew6OUTP94nfqJPvKtPvFPL28rsi7NcgzoXCE4FCd4uEMwMEuxeIOgJEhxdIPg+SDB1geDGi4Z9g3y6NnJbP0hfq6Wv30lft0hfv5K+htLX1dLXn6Svn0lf2+SuEgcLWoIEIwMFK+YL3gYK7swX7A4U2M4XHA0UvJovmBoo2Pa0YZ+9T9dI7k9LuD+FcH9aw/1pEfenSG7rfG7rD9zWcG7rUm6re9V1SZAAMpn77Hy6AritQdLXL4QX2a5BVJDgSZp1kg0T3zW6qS1vG92CkBlTN4rbapC+zsi+eMwtiGIJAo5ZJzGZOj+frinc1v04l1VSwyv5DLzR5kwWZuvPlw0zvl47bnw1N75uyTG+OhtfHU3aIcZX0Cmpd3GVBITw7YP4yxfwa4P4txfwdwXxbRbwjwTxXy7gTwnlbw3kfwnhJwnAxKOgIAsUHAObj4C2o8AhCzgcA2eOgJUp4N0h8C4D6Bzp5QPo5YPo2n50rTlda0bPGEjP6E/PsKBvD6NPZoJ3x4BfJpiYCQoOgc0HwebDoC0TOBwCZw6CM4fBynRQsYDvNJ9/fgH/5AL+8iD+jAX820H87gV8myD+dwv4L4P411n8rUF8p2D+eRb/JIu/PJQ/g8W/HcrvZvFtQvnfsfgvQ/nXA/lbQ/lOC/hJKWBiKihIAZsPgLZk0JYKHFLAmQNgZTJYeRi8SwF+WcAvFUw8DAoyweZDoO0gaDsMHDLBmUNg5UGwMgO8ywR+B4DfYTAxAxSkg80C0JYG2jKAQzo4IwAr08DKY+BdOvA7BHTD6OXO9PIxdK0jXcuka13oGQ70DCf69mj6dn/65BHgJwB+2EgyKEgFm1NA2wHgkAwcUsGZFLDyAHh3EFQE8p1C+edD+fOSwb6BdPdAdTtTUWclFUcKObFjJR9Z/MfHgG4o3T1LUUcIOXbjJM4h/HmZYN9Iunucun25ou6sVLxHyDmbzemrqcFDXThMXeisLrRXF45RxwxVxziqY0aqY+aoY6zUMV7qdl9sKZtzMpvTnc25ns35kM1Jzeb8nc0pyuZMEnJWZXP0Qg6+Y2A6KDgANieDzamgLQU4HABnksGZVLAyE1QE87/goIP5San/XtQknGgB2JwGNmeAtnTgIABn0sCZDLDyCHiHs3MQ+B0BOlt6uRNdO5qudaZrx9AzHOkZTHqGC33bnL7tRO8aTHcPUrfvV0TaKCKHKyItFJEuirrBiroRiroBirqVijpbRd06RR1OnJ1UfEYqvioVH5WKn0jF2VJxvlScLhXvkorPS8VuUnGRVOySzbkt5LwUcs4LOQYh57KQUyPkHBZy2rM5D4Wc0GwOZ5yEjz8LFn9GKH8Xi98dyj/C4n8Xyp+Ct+F8/o9ukqOukleuEv5z/Xd3//7M8T7X/mtanFP39huX7V/tii+7fzRwdp23rdJvo3z1i53n2v88EOe0f8Ot1MqnjV9mf276XbXH39b/3lbtQ6cMp4bd4MPRMtttFnDbi3UWd4eTd//+LfV9uuT9jj9Lfvy7c7fj7u1z+oDbqgKw7ksguXeGrgzkJh3a0JOfVS8K2Ei9zU16sKGHmVXPm7uRqs9NWrG2p/5QvQf2EW6a8+O6nkzsYsxGCnu/s8EkWJiE3etNwiiT8Lu3/uk6i9OpppkfcpP+woGkQtVm3G9Vu4wMJX9gGtYldWVcr3bZHUrOHm2Q7+3KKKp2uTGfvGdvbOC25xnbtNTKBeRoHO7MrgzsemuoSbA1CV+CTcJUk/CjsQ+0nWphmvmw2uU5Xr2FQf4lV3LI1X+iq3+bq/9KV/8Hrv7mrv7PXf03uvqfcvO/M9a/Z5z/X6/XdQTtfnqxrOHY54NBGQn96hkZtpqxv7vZOrW4ckMd/arKpEdnx+eGCp12tE47ZprgbJow96hpwgBNndHPOliQRhc6t4hX5EoerIMOaXSMY4s4KldyagN8d4CO8TL2cbl6Yx+HXRn7uJb+xj5umt163MdlJAw29nFVZcY+bp1zCe7jbDXfmKa8ME2Ze9Q0ZUgVpxd/tMH8eYfAvmF092R1e4iirlAqnpXN6RwrmekmCcDfyCHcoVlrmepJtr5NSmMDl+xhbOD6s11xr2d2gYXbOHMwwNjAqaxNoChjr1fsZQLJ+kB2dHcjWsFl3YOuq2Nt6iSCdjO57tt7AYwbsrR2K7lu4b0njBuxGe16uW7dPThlNWLcqCaR9+pYq7r9Zd0l25Omr6as63hl3c+2J81Y3Whdt6d0V9w9/ogblKCdFMR0xW6Enqtj7erWy1d6rm5Pjync/mXkjdTSXavvvbKsa5Y/mLC6V9DOk+tk2x+PXS22rQPylXNXT8qIMWzf6nTjQ9mu7feO2tXVyx+MW31X0P5ku2HuamR/Y5SgfYZ85Vi33sbi79O2LExbvTAtfGHa3IVpkxemOS5Ms16YBsLSPoelNYSlVYWlPQpL650wbJ0sd7Dmx3GK9V2xee4LbCcsMJ+wAI5f0DN+Qcf4BS3jF3wcv+Dt+AWvxi94Pn7Bk/Glekbmb5HhaQk+jJ3TGL97MHZ6MHw8GGp3xu/ujJ3uDB93hnoC4/cJjJ0TGD4TGOrxjI8Z36bNWz+u6limcH+mcEam0DdT6J0p9MoUTskUTsoUemYKPTKFEzKFbplC10yhS6YKBo37bDN8XXR42rlF/6tJ8wltGozD8MHDIuqEzDGHSkmG/5UqomPTroJ1a9BZRHRMY4pQEVFcZE7NalvU6A4elk1nVdQdFHeQdSfpVr9abcGKfxJ0yKdiW4KZZUYN2GXBtkkMngrPy4+H9/Tef+xJgiUJdjfBbiPYdQS7lGDnE2zZzf50s1/d7Fs3C9ystgw94rFWHMGlg7iPwCU37nPjUhvua8OlVtzXiks7cd9OXHLhPhcu7cBBjkP8L1y3qmqwNIptRbFGFNuEYnYUW4tiK1GsAcVsKLYExepQrAbFFqJi4Krr05wVyXIYmRhCOgeQpgGkfgD5yyGfOWSCQ25zCMMhnRzSxCH13POcOZPLVZL7rihD4h07eY09cYUerYmsTz7ZNV6NwI1icwMZZuWX9Lu58JvY20KGCFNvr8CKQdVGouyJ9/To4khP6snR8bML4EWxeQt57Kr8gc4sijxNqa7xYG0k8cg0KjEehUKhukioGvqT6jM6sIq0zYMjSXWCDqwmvfPgUbF4avz3XNifUmVaP1dUuiqwommaWLSJxTAdd5K2aphOqdN0oIX0VkOYUkfpgJ10ItCTVBWx6DXDswkm7n0lG2vG8g8AnwM8BLwA+DTgE4CPAn4M8COAHwZ8GPD9gO8DLdp16J8C2UNSj/0m7O6ghP2Mo3FKJW/Abg8lbGYcUUklB2F3GyXUWo0lHMw7CsVteynlcaHXNggDhyjtOJMJT/XYbsJAB6V1MpkOS4ZgYB+lEUymzpIbMF8uFJcep4b3vZYd5yml809v/yD0D4LsZUm93zCmLWbiI69kh4tS6q2Kg/5LIDtHUkuHGd3YTg173skFjzVOkHWGUtKFXvMeLP0CWW9eDTaPaYJV8bJ+mlL6Jku5GaMxbrYrIdOMWadqsvRiJrghbiuPzl6ueLRJfDRWxb1upe5D6cpMUIx+sT8s5c+Yd38PT+qFro/OkZJwxrwdNJfrxpsu1dgVS7Rr0Wljix5Md6nBtlhojyZMG00Zp3lA7y5eCMacHi0/bZSjP4TJ/H/0UMw0E1nTdP4D")
                .toString();
        static final int OWN_OPENING_BOOK_UNCOMPRESSED_SIZE = 1285840;
        static final String OWN_OPENING_BOOK = new StringBuilder().append(
                "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")
                .toString();
    }
}
