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

                dbgPrinter.log(
                        "Search depth " + searchDepth + ": best move: " + fieldIdxAndScore[FIELD_IDX] + "; score: " +
                                fieldIdxAndScore[SCORE] + "; time remaining: " + (maxNanoTime - System.nanoTime()) +
                                "; calcCache size: " + calcCache.size());
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
                "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")
                .toString();
        static final int OWN_OPENING_BOOK_UNCOMPRESSED_SIZE = 1493698;
        static final String OWN_OPENING_BOOK = new StringBuilder().append(
                "RLoJPJTv9wb8zBhZRtlC2SVU9siSZYYWpSzVt5A9WyKUyj4zKSFrqSjLKEmyL9kZyZplaLEz9pFtMMbsM+/o9/7f92M+1nnu+37Ouc51Xec81pr5fkdNOUjUoH0IwVirCS9c8UrlQ3tG+rmS75U1cc8LaUfOaEFSzV4K+b52ExGpjhFQVbB3PeV650I171Hls46e3ZZga88Z6JVLCgoQs1XIsZUv/MklznfECr6OV5HH8Fn4sI16nzr85BaTuT0ViYsiTPmM+uCYjJyx+jj0VsFPzCSWlkfCULmsHzewkXO0LXgAbAWbY7iZ9H4rZJtBT2bNnMfarG95pyAPncZcm+9xYQxQyWh3HERNGn3+PjZ0pZ71njr/vQvmMEs1cdBCYKm+A0yYYSgjjYXXD2Mpl8FuNiHoZWnPOmGH0ZjmefRlNNtHug7ZvjUxRC+IwctNv69JW0IfiGRNvKc2Sk0jb3cSMKZ/dpixEWTcGQRS9yTSfZYSytJaZMkdmEJa7BbO4ggUpyQGYoKNgUUyBVNwSFKTG7NCKg2tM0L4fplqEslq3GJmfupmq1wrQ3tPIw9lY+5RD99YcHVfopS3sYRvjCJ3ndmblMYhZuAPxl2JRFxpEMIQ0bhNxfexdQ6kIUUXCI/aqGEx1Hnu+bAYUm/CCEymm72bLDePnBDbKehh3LXvvHy4LPke7ATMG8t24d8dDEnUus/6S5uYeo9Bx2MDZVjNBDWsfR3bxOMr3qKKolK2y+ikTIwSYu1S5RDfQl3cWUxJDCY2hkGd1WLek2Tn5MIm5XLZMLUZnODqJtmdmSSRhPfC4GLjV/E9P5kpu46uXSPYR+8ZkxrTGLkbgzjZA7JIDJjd7KrFQnBT7yGPsja1TrMQjxn1rsqYnMszgooMJgKEbHI1Qzb8ew8rFCPL/viNid/I3QopysZJeu38Mglih9mrYTaGZzBrb3oFTecoFWIDl9ZwU2ycTCl7iMV9Uo/l/o3Aduem936PxhG+N9Dwj6YZNoPsT93Y8miWq9ohWB15sr6DldZD+/5ojBBL2/8gganZFBRpDEtZs+JZRwrOrwYWsvQaECrO7MdYLAshJ56MkzVg+dml4kISFtlT91nwBCqPzEkWuew7su39PMY3hggztGUHKbImVKAUepovhq1EI0xS8s6sSfwawT0apqx/YSA1jTL0WIghKoEulvCQ0rVJJSgnIZf6iLj6P2XsABCywdEMMS0TyBgUMAn+S2D3tFGRlQfYsr8wzx785yIC0wtjlDPmz82jK9yYgXYUprHHLDGi8/3uk2+raAm2eeyypiB0AX+WZVW0iTfBvdVGJGJIPZ8rSN9VqWWxCzhpOo/MLCOKrq/+yvVapw2N2R/OejBIzJTHbKQbs02CYQqs4rSshBHcVAUCM9ExAouHTaofQtb+h8Q/GcEZU8O6KesCzJjhWvSpEXb5bcb3OSIhZUPmMLJ2FCHehUvqZ38XYendZchSyZ6PGeGVz1lMT24W0bMn1MUMQbxKRYYcQ2ZXsmqgnYE8WHYiNxa5Ovmc4Utwtv7K3pAh7Bsl3WIyA8SYbPdShkyCXBaZTuTZmPbY8rmGxrhqmdYtMvLs976wntz6RrnCQS0d+12XZQnFow9kYuizJJg666UEg9W17Toch8vww3x6O9+j/hvHU4r8wcQasllrXa/XeOilD0SQBCJBrZJ9eUSOYJeKIft1ExB8fdAuQRoarTbIRl+uQu7fcLURp6SooJHLIyF1LNNSQ40RtgrBNQTaiSspZ5tK17AT++duMtiDlObjtNXB0cY/pbiAO79II8JUnbBIAnkjWYZdbaKFWEMxXiAmNQ8hYbMMnSV2OSXpO2lEiQoN4eljPxpjVZj4M7eGdjB/5xO+IzGipILF+VgqbgrEUKlbpLrGONLoOPrE20OwQ+JsBOFGHK7RaI7yWGbF/RuLrdXC1oiHSS9qCloMw+x+sWOTdTwYzPusisbL3ok42TZ0bL2S+iEYiXEGI3e6h3KvPyKe6T9HbDQKZYyUlrMIN2axNm8SCzrwBR0Y2GlPBsHVh80Xm8z+JDbAXhjcmnhCxhVu5V3DBzaGMv0Fpdgmqk5s5octMnTNTjB2guA9KkiQSEYaXEZYVaEH27byBPzsz8uGMov+sCk5+TSdklyknDiyURcRvEIL62zb2lmwpZ7aYMvmsI/PqxTjQHynvJpcc5EVjhswEnQDbfcW01i/Su45SD+szmIGSvwRlJKE5SNGmygVfLRfa1vu3yhaq4xIefZUEbshjGQx2eKX4YkcL5vV+I2UFd/ExFZQC7i3VKrbCCniY7CaIhairY0kocxg7Raj6efoI0z+rUYyJ3ll5Wyc3BNYMhFf3kard3wMMyFubtm8cWJPbVPosdy7VbHznWWl32Hpv8sOvcXwhERT5Th1VuyOkcpkX6Rj07jn0cXlbGPDSOMyeSTjJoMQUMrI+MIWuPT3BQUde45Glk94i2BWU1hdFNyjUkaLozu70VGLFRDDxAd8ozSK7aIb8+l29mZINfFEQZ5NnJYZ+50H+4R4Il5sVxDjFhnUu17WEb219qYLR9vjcWav5mEkso4BVV1CP42Zp7Yyn0xurZRxMu+Uh2CxqJmmiDssG4NxCkt1UqOBPUUMFDzM9nzMXOgxp/emiS10pZHm7Gp4jNnr0aRa5zDmAaLdzduwwxWwKQ4mMXCEsYMCcp2lxQqqbSOQAzpw7Km2rQCstnci8uQ8GVnrz8ZZj7MLtnxufkOeKUIjp1nneBLRPzFxgr9xBV3oSkdZzIagrKvaKCzasQz559ESrSBmFRnJUNiCxrIR4llsRAwjls5hLLUGtgoHQGkx8zyLrvYG0/t9LTxYXSq989i2tq3vI4QDDYh32shm9HRKCjodJplcgZAVT0Ta32fVj9hAKfr9tWyT4ihmc3Ao5j/Wr+ita2RCeRtTQrUP5yJDGtyy+ZSDbIxm1WbXss6TMaQFC3s1ZHgILlaUgZjMoiiuLMb2LgWaMwaZ0Uz2nvBgUOypslY5gsQajNxVBeu/iRRULcbFnpuXO2DInuLfLfLJcWPM8EdU+jMloGv639Rr2eLQJc3iTcbzfCLsMZLIz8gWPIzUXyTr3Gc1fp8T7EIiRU9YXGYycWVuyJY96YqhkOnfu2JjiBYWg7BPyWRoDvsgHffIl00coj1RHP7FTZMqCWIHyCNDlygBFoYPGGlK+IJ8Mq7xvGARuqNolcB16pXEFnuWGXGZtZ84co9qNIBMyKeRmbMYpL4t0q2NGGFPphJez0BJNi4GiHLW6BFmwtQ2gwszQ4Z5Meu02QRm4QCsfED2xgxMsEsK1nJGDW8z20pFtyBub9Gu4TIXYKaTCMMCxK/dY1vX1FcNIhnMWcbDLyzDABj6DyaYQMQYZWBkHrEq8LApZLA9O+ketWt1u0Al1oW9SdsPHWbfpxdmXoV9k3mHMXjN/slP45FRw6gUY2LFGGRBaCesWAi5GUThcD97gauLzr3qk8zI8mNnNCJj8pLk8Dbu79lqdfNElXFFtxtJ7Mj7ADKnkpP0XkJs/jaym99SdSFliAMNPMYolLUhTqz1pc5XuxmXpGLOJ3TCPMToIE0Ea4KLQo9j+t5n/AliDwfN0MY0EtEOBYjuR98o6ydosVLrmGYJDDKb+OvnKuGP9idOkVFUNdFsExJUJQWngca00NBpXTjN05zqpxrZIqLW+VfJAeh9jbqsdk1pWAAs8zMGJm1qWiwEk+GUq0UC25rySWwYm7ZI99kLlzs2ZfgthuDgztqwS0UqMvERR9mnyWyt8ii5MbY2g36YjFE7DFsn4yWS5UbkfGcpD3fsTt9mTpbUsWgxmzMcOhjm7LyKF9tAk2/bdT1HZpXAjKUvsUerWHKdnZpv0BMx83g2y3scswzBIk8iowx2Q2+G7rELzyrbWNqAxWoloU+962CVccJLRsSQkEgKDR2OYCaxy8wZ39+y1/swmkuhtwpZSysk2PUymFOzy9XmlqLw5k/hzSXhzdXhzTXhze/DmyvCm6vCm4vCTZybcxybXeybc764YD6FM/X/rBr+WdUpGoF+GVEtGikqGrG7XlZ7oyywxEXuswumLpz5coV0qWhE5XqZq2Nzjl1zS114c3F4c1N485fwKCtS+CUSrWaFtLFCylkhvVghDa2QKlZI6OtlaOdmRACJdv3Pqp1dmatdcw5nY9dmhA8p3JkUfo0UjiTRiv6sWhSN4LobWUj7ZsRNEu3pCmm1aKTgRhm2m7j1YYU0t0J6vkJqLBrh/DazaGSiaKT2v7LAMhcC53BfXORKXWC2zYjP4czmFZLglxFYfSqJbM9q3iUIEMMWieq7tdiNMdx0Krm2YDtn92cTpvn6WiAWG9Hqty1L2WhCMneb0qPMSLS2ppBmeYJzWR/debKxRYHgintJ1mpERlmSaDtNrszOppCWICyC5IqPWrFbatbYqMWKRAwHd7SIkbFlfREjRduyx8hldSFRPtOYFqmI0THkJb/1sI1uTL/HvcNrpNj+kbUwoYg5n3iTrZgwQnjBqBw6k5zr7RrpOK2DUSak9OeQ3zXZmb4md2mKRsw4EhG/NlvqJFo8sC63yd/w+ObVP2Fs2kqTJiJ2TGI+hxIfZsP42KTCGjdx2ljkfO98C/bKaeNJmCajir57RhuJ/qV4zSUT/2V2QFM0khpGzckZGCG1GdfaLIYxr1OnMRf63L9nu/pQiCp8CMYSCMHgJF7zhzHdIQyBTsMf/VGCe4nfUacaOrwl6pzsYtWH5cBOlSVobrz9ZYBGPlAKWCv1lHOJwJnshF7rqtfpSbpED9FdYj/jejhdsG/nZg9XwXIZVjxiynSazBcWONpFWgjcfBi7XKgYMS61bYK51Gelo4IVnaSohyFcsspmJ7ZPIXyXesvW3paFaqI1l9QGRkYS6sMxb8sofaYlPtOSpfQV41ic/9ImZlxR5R2zeLbP9QV+cACH1tt295eFIdvGw7/4RJlolmUWEB8ibr5JSMrZYhjb9We1tBV31GZQme2R6v2TG46xsLJwJmOF5BoX1fJ2HtsbzIggF2N9+A231CpMYSPFNvV67EHhtWOD7K1ARoRJRI/cQIR9VcgpOquIOE3SU1DMZK5Szfc1qREcouisAq9SenRTStTY2TCqauVUXkVa0zFCjCY24lp1bWTeqN2aCwnNQX3GXeyNUtOJ7mH8+ovCa16F1YVIuzdcHow56q0WSZPZQn8i9Y+DuLEKdrWoP4vFiAzEHMONFDwPQ+S89euxmdY9iYc1hzP7VkhV21kThklOX/pqdmy6idTjYzxa7dcQOO27K3OGxrV4w6ZroyyXNHzF0FSLOizh/AGMriteCiJXcZ8sJcCzNn9Srp9M1M6LPXXSZGA9fcHnILpnJJ602yxq6I7uu7twVwfTP9kZxrz0Z7XsehmmxWc/sjSjMWTJ8M7S5vdbJvpkVvWQ72Ik4mhZlVx/MPnE9/fzUS1l4VGY2aEo4iH8ZtmCX61T+exx3Fs8J3+4EheYQzNrVsCUKaaYRbOBmo7WCjKqVkiaRSOYsQKP+DCWlUnkVj6V5eqx+7ysJ3h67XCgSM1Kwbb0qeCfo8Lvoj685SCygd0b4a8W2BOxLCcc0cbTl7PpEFaaJ7OYQhhyKM6mjezvC8nCs++5wW77/0YGbLSUd02TIiseub7EbzVuf23Ww16r0tT3Kx9iukx6oMPO5MwLLpg0vB0JlBm1g2kT/mIHgscYG7ghHbQcup6que6AwQoTqWAFUUyTD7Olo289pC/V535Siy4WR+wIj8q0d42qXB8L05CaWjIMS875Qdy86HCjORSOYXGHySLu5eBHrGYEruhTbarQdS/i0ccjR8qwRNZSTpjgmA1Djm9MOUqtzMAWy2ri8hfUFMqa8TJpmhmLrhXox2n2BVPiO5MN9GJhOquhaPnQ0aq+aXr+rf5mX2zk+dJNuUNTjWPBNhseuHnFSHfEvmHZ6WJ62Q/sCvecYfOlNVGs8Da5kNFX7EzunxtrUoGhXV+jcel4/BF0fKQsRnmiDq0e+QqjiKXqexsVZ9NdU5H6mMvFizZiq9QQfqPSLDbsnjz+6yrtq7cJxra7zlvw7u6ozQ/yaNA27hiiyvXo0qZfHy1g91nn9NSaRRTRdyT0+z1kwBsxLHa7JFAmcFuS5XsLxvrNMGKs8dXUw+o6JXHf7+FS8awkaiTuNd5yQ7NMRK9xKSwycwjWouCKq12iB1KmLNpNfy0f1OyNZPkJ2FTpfZTgQ+wO9w2Z6rXehTmsjfWfDg3ANrFk5ycClohIdLk3LAT7LuqE6/zEdvNEFFU9zCVHfHX5+i1T+s4T8uGAiT6HrDLChIcoy9L9F+ONMQZpiB1z7SXueoj6d2DUsexACWlv9BQlZX9xLyYNv6VCdGLlxfvtVJGDJvUzSFRrd56oklFH3IndWGz36c1AfW2JMomIbwVSprrK8wiv6ZA1BtIhh11GGONB5IzarU/qE1lK3jmZhqwlqXjFiGLTj4X3Xu33Cc3b5HNsHIPG9BfMCgSKhI+nJzAXipL6It6kpqCVdtM1e7NyZ0OQbp7M3tEefByl53MKQzNizTdJfR2je7/cpotO4MakShNsBmYkrg6YvMZXv8WP4On+Szt/2mqIehXoSPjoMdcTu8n4xag5L8Xwiaoqm0Voh+wWHWJDmUEZ0/dHq0R2yk5ZbYhhQlKmh/tGyEuGdJZxIMxAKwQrHEwejyxJUsd7UQ0CtYJ3Qe7kqEs51/5y3d2dDFkk6vsRxG5NRfnlYAhymxEtBlhcRKWzygAuZcGQsSrqOZEeV7864ZHpcxy/EVvpfGzgDw5LJKXwPJj2LpWU3ZQUFvW4W6cptPsd200m/5eTYiOxJUHfqT6v7zd33wW2PcGkMBwZHWHJ76jM6KbYnXuj7KPB0+i7O4/xSyTmB9l6EinMBXdPqdKvTG18vij7TkH67kDBtlMTDGuGhu1GvcWROngio1wKtscQPqy7yI9d01RyOAJWJhQxKPejpgfjmUPGod3pkc4Dn8q185A/Ro3YNhVYRsIK6Zrqkrjx63UB9MlNI2rYWAFxy9B0V7RDls5hYVddReX5t9TaLZ37k61luphCrNgjuvcGHeGqQT//oZTSmxTIqN3YSpDrVzF144nyRYgTfmDxvu7QqLzyuy3X2tUjHZck+oMp5h6ONfcGk6zq3xIC9YM1PE6bBh8VX7KLc0rxWcxKs5HZPOVAr8le7SQI4WnYvuqpU5MbSmEHYIpYZx2JsoHg3esRcyY52Xi6Pr/z/GtzWMBSbQWRKalg1HK7813ALSPExRwPozKYycQF5ICsyRL0pKBMpW/kmZxfsPvr4f2yLWPGq97IO7uVeHxExNfR3ne43kZum7sGk6pJmp/CnvUHLkqdi1wLIQd9MnX9hkbPSDGimx59MragC8Dkc+YttoMZRWMbm/pkhvWC6a81sg00e1kpbHpafodRJmz6uJa8Rif0jWyb6SDLeokTXaeQQTZZUxHthNbxK4jI6GLGVe1Y14AHUr2GJeh8qgzjbE4tMgO/wR730PTdxY/wJm9zRSz4zJfRP2c06vMZTfNEbF93UaL8dQwfnondbmJdyFE0Qve7nBzpoj3PiaKWmNzfjLoVyLLPwWDch6fYjs2shyQamQuDV3DECcmFPczBdA6TXRRwLaZs7+l3+LUok4rcJOk7a1Gu6Xhq5n48vQUk8/fc2AD1kz86AlkTeDPq4kDvYPDtnG/swe9mLT3t/LoRZLa2GSwcD2D81j5YFS8nbIehRSo+nNSYIR82PGY4lb5Qz/xRrKLvRHPct+CmI3eS8GX2Qb8xMmZmiLD+4jX2C/HFn8jxeunMrrzeVj4/F5qnpFFc9cVm181NO6izaynZuwRp3/fitR/Gbk0VN0mMnFZ9kK0g9c0u7is7XXkUgvDDmjKfP8YrPnzX0lwf2K9fMXR/HXM/pWwxyfz8KekP8doRFQ7E+8kw+eEID/SdpZ1MnuGGRF+/YYpEHNV/9yUsGz1P75Cc8sE0EHp0/B90RNF6yQJBf00Pkx9gXW6RXyzJ4tLwbHXqNPIB3I4xI8ts5HQxWKfZrsr+nlLYYZXNA9quSJfN9+exrNixN7p2B3b70Vm1yLdek5iJvJSmK+07+/+O9wcyvMIOoAeI2w05DA7/M5L7oF5ZjvN2TOe2FoZFRxbCImX9v3GTzHqm+tgwg6cKUz97HHmeoRE7h5uLZIVE3H/JwpsQaZhFvP4pwTlf7XS5QzP8P13ZFcZoWLmPM4Kdc2qs3bNFxXXedPpPe2MLrGzKez/Lc7e33xnpof0WpzJMT9ndLEN/cWE/asfr/9n91YdrwQUTjTJfBhOvaKtENcCD0f2mlKln15gZM9dhCmvtl+4H0x7MI1/jN10Fu4maBm7WaBFMsCG630nv0a6cXAa29rkhc4Mx4Y+YgGWMnMsT3O2/J6oySqGzbyBk2aGzxgiPXZv9Y9JzwYwJ72bWatMNNjOnPiolAye4GGzl3HhmdRe7UC/pWIt7QbRdlGYHIc4jdbCuOGOMIeFXv6kkfkYzg0mU83EmomUNyOyitwSDEeRQHwGjHUyvt+iu9dvw+CG7qaCYMDdOx5D73OdXGcFUB2b5W8zSYV3GJrphMSwcebUv/7coC91kzc5+i8WO6aADpVPJBPF7y/et0T+CaettZVvfGEbsCy4R9CXKetuNwe5nz84wn5gQ6cNN53FoLzedWvbtb8YskDv2LoINU9tdR74k2zVqIKuo/fqTxNi+QNbfF0zSRH+WpdRGyELY3d1+Oekxrmn30N88/S6b2hWu7JLIwEc5runU/6qSF2kmnv2sAA9ceNmMLUweQYd5Uf4Gnp4JYZfM1eJG/67JboLeFzHiUoc8Ir7xxJRStV+wB2utYSdCO9+v3u/zoZ0Ze0acN3FiF2yHr0+ckSplv4zMIZysKLQRXAPtxwZukBZNtpRwtw3YtdDGuSqrrJ7es8XxiTphNm/ociKRGgNbSKNyPfpSfTyTkZXTBOrdWpQdX7NA3vPO2RiUpVXPkAJFKjtKbYyQhI0xnrWbUc42xQNyEhj6efGxr7TtLNiWSmKz5XgVu6aOfdwvTM3Fx8K6xK2IZuUdhTmOZYbEP2SNM5ymvYbpRkmmdOOKgXxNM9PMZxbGPvLWdTaxUYhdTEc9e47RJNk8i00uxttIEJs1b1BIVPJQreuoPRqjdSaWaZviVBWxaffW4Iu3HMLBZq0hu+uZDgNVx8bHh2bU7lDOmZ6cK6I6n0fefhBbSN8i6EZs18Zl7AoS4tnUAtEHoy3Ns8crQiIzy98jdHH02Ijd+EDhim9Jl1fS3DmJb4OlLg6nCAQVl/ViFyI6qig9XAujhL/xXC+2/iTd/TRrz3YaLaRFPu4PpJrcMmmxovC4qC6R999Sb5loB5B968MZkdmbrmORfXb1pbuDPB0Nqwlk5rZxbJT0w76NpjK9gcypgKgswQQf6gixYXprv0A79ZKjq0csoqr7cGShfWZgb/DoKG26xcL7VGWLQ+xJV/sfDw1DXG/7h2lKGHLJHayJfm9Yrc1C/1FczcQFyY0Eeg4JYuOIgcNTv0xWN/7ccr3jyZywisRk69fzbMy/T8pmYcnFzWUDxIm1JAJOFErVV2Ek381iE5ZnP3G67z9R9yRPdMJWez0me36k1a7Pkh6tR9m31zGXz2azjGIELWnW7Ps5VS1RWynxxhudKTuiPBtJ/caIkjhkxUlHmjWyr7gfmbP+iW3Zt0oUHEx38b21ZKpNN4BZtasiv8EFprrwxs9sureC0QGMYtfTOojSwsObPW3T7MwkTQKJ616w1XQ+yYhk8oQnJLQ5D6tPXSegtfC4E9tjmYbIwBY2ZryqdJE2WfupRI4QwS0xmo5nhc8WsJ6pJSgRaI1hzmwm1/BDXNYGcZGp406XG5eaVr71efcvw0qt9RcVkXnaIsl1h4tKg2HXh12iXsTxpN5DWjV16O+MVqyPMxdA6CrnAkykjfGcbWJM9i+Y15ygXZKw/aEndMstNReF3VTNqaqUyOjRYFPsmlyS2p2clMSYw709tIWcr+yJp2za6jnGQ9eSdFdTEcXN7ZxmxnpA2FR1fs06OuMYF85vqbjwYQUWS2I1STDFdFyRFstml/zHTlpPLrmyFqVdh7pbkC/IyabsS2kqpEmT4Dt4ouERC9ZiWAD69V0LrOjpxv4C2okXZQF+/qzxPryUHHk0/O4tf447RvriXwZPLThHOtlMvyJ5OUr1GYUzNK8NL/uQl90IIvStibOmNcWntgeMy5Ks6L7uweEnXO1cEe+K6bTDpUUEXJ8XOrtRU6+JAy9TRhWR+EqvTJC0LIFB2PjXVfuQxyoL+t47pQUKGZPzj4hTkm41N6liO+zeUnAShPjv/hRPHU2CTn2nR9gXnM/OcBTmL1ZHe1FznDjBOl3g5zf2kKy9JcV+kUPpq8j4ph93KMEbG1mU0ZiyRA//WlHVdF/+Tduwe3rEEn37FCGh5Pa2r+nt+7eXKJ9bfu4fEaJZF6yWIdvbG+2dFVb42I9uTdKuLYVMbnZwmiX/9/1OE5TXBUSd8Px4seUiRWXSeEr8pI6vShS625AR8C2dvgZC35ob8VSvwMcbIi7aMH7Yrj0nQQXGbrYMLlzSqw+95jn0q7Qoafu/gSvUPrWIZ+EfGD/Vgj1lCRLQxp1XiGMwi250Z8722BE0uWiKFa5vVSKlmm7qA9/Vpy8r0EZ8+A1xy66affPvJn+ExGf9VpYgXT/yjFLIyFpoapkqtnPk9Ei3SvQw9GeKkb6Izsqc3MSScfIEmxHfAl/l8V/blhMLJtlt61C3+MIGNbVXA46dX1c3b2lC2jiNT/Ds/hoR7Js3hX0p5vm91ohTXyDhhLSvXyNfd6hlfg/boN36yTo3n2J6fWMQuQYAAOqrezj7rXcRYuA9Y+b98g753Hb1q9K4RX17p/sk7XtCnqcoM1/sQsa2MM0GnowmI08aYha6vb4F5j7gjAuwmU7/o/D23d/CZLvyJpdHl1f4iTA/G0uiprgFfgdy0cT6HvlPx13zENKfiRVE7eNFfafZ3xMrAb8ZhCzkCThn+zr/i5YPXqfvBw/+8MAJAc/npgJPg4Uw9fGQXA+uePnqNm2E2mKepvlRYGqoSLPuUWTTm2NrHaQgxNBx5UUtsG2yCOqnCBDIBziCtf4izDgrGhR4JiLlVbfxsmCDGP8i7lDx0hKg7HrwBsTgYZYSf5Br/MIAeCYhqRo4C5KNFpn5o2DA23DNUcCet4Yi0ZHStHChmK/ngLuDZWjdrPEU9fiAWUg0XCoM9Ef6VYsQPgXskpX7OaBgLaBc0r6ft+g4eVv/2zs3tXozIc7eGkmigxlWbssTbjwqYTc+snP3r3Vyz3Q6A1dzCSrAKZAwJLdGjxcMV+ODH2xROJlqbYb2s7lpCbw8LwjmXA6JnH6Vna5s7fd7OkwQ2sptcmnFhRp2PtMoBJsYufPjEthW/L0iYJq0m32W+P5yye442esc0LD1/gKkNQRf5gwchZRXAAYeIIOed28XiXwr5FwnIN4s9aj00r6EswYQ0OZ9077Zn8JxpJgaCx7Qv1idnVI6i5e/faPXK29R7+YjlsvB3nW3pYURLFmoI4Fs9cpljl8hHBTpA/r8ygUnC1shumThoHJIP21bDUBPAzD9AHV7OZmu8vnJgad9Vikjqn9d95l9vnNa6T+GUM32HdWTi81awMfG1aUn7y4+4+agTVWX3/iODotbCGUIUbD4U1sBfE2CWRpo6vZZsqDg1kzrOJ/HLb58Z48AZhlbfuBB3q1nQLm8XPoF4IIoMAxp9QOXc3NOPvPiQvhX/fVRMjNSotVayCRFSMDt7pJy1GCbSs5ZYaOyuX2G5UEgys/XJ/ehavJ3ZrtSepMFrxyr/QEWOrPyMcroheC9hpv0nI0juB6+hR2Y0uPmOb/hdtnI2VeXoseKdJ+uvIOeQirEQdcM3eSZXFs0mhAXALR+/UIPSBe9DRpADGxpnLi0USEK6F/lQR173jtYWP0nRP/0pQFVUCAvvEMu+gjvX6n7+38v8fqC3NKUCjqUeDnhbl0UgnwEuYiBbrexLgoBN2SjK3vE/S/TFmuWFxzjfMF9d78g1Yi/xGkXcZoPFXKPXFBFGlcfO/rA3RclWgmsGF+8F+m5lzbwoOXBG0guUJ0aFCVlw/XxfG/ALeDCqwO5C/gNXhDPS0jrSMCmnqhWOy/XXw/yFQsfBfgvmAzI10XCEgj/CWQrAQMiqGyoMUSes1jWzVZH+f2bNWrAtD1wux3c2gmesbkNykgDDfEGBVZRnlsiOImpFLI9yvszXggmxIiJE21+PcIjwblWHigHnPK5UYncqPNqgL8awHoqjoLKA77mTc9BQW8AAx4o510jJ3Rvw3MJENCBTIAXMlOMfZl18HP9afRliBuOb+c0rxYEY5Gr8pjQ5L+R1jL7qkO+ge/mY9l5wIuY65WPEoZwQhd0BAqcALmQq4zNlVqTCiEXFF8D2YWQ5dI6kZlkbtQ1Jbjv4zhlyEUALGb96fpN6FMVD2352gKxI7z/TtlqyIdS516z4AWkwG5Q0JBYOtga/fLH1UQakpx+20RRhV8p6EuTfB7k+ur81PeQc7FwAJUGRfGhgLoNvJFdrmYU68HjNK1IQcQA81e+iZ/vvYEWlHXrRxeSC6+bXDDbSye3ZOJRDOqLINREDnHJ8V5LeSFk9Bk3Lg4Cl4Wl9xKUAxctUfPu4G//Vd06nCekqiB5WFpUhnT8P6qHtS+qLX7Gy8KjDgA4HAIBVmcTIa2+4BldkLwVEJoKPNURRZ2ZA57wQz8HvBehVjoFjt6OQeZ1qiByj1Dtd9lmApx7TIkBbQ86SmlfGAnrNe4Ca/lrGJRFuVlMp75+80HEbf3EBWu0sgNK74tMEP9sugOXAjfz6/2h8wKfNLjMHfXmVw8CmBvCKC574cXeVH9z6Oahk8cldNO/SLnpjgU+OTnW+JMBfrA17wQ4aMZeJEP3uAoABwmBcotTa654k6Rm21JAvmavwI86atZ44S8fZe5XBdvumz1C/o9WmZZSOIhMf5aucVRiTvjXGRX/fdUZwFlLCGeR1n7+78JQ4DDIQt8NVOd1S8cbtMlBn8U/KOneAgVxfuBAK4m7NXsQPLgtbzXbdBNzcv9gwbHm6HO2HoPC4PyjQPu5jrfG0GdplWBP26P18U95IRyaEfX7noc+0+3KBxr1AB1L5G6FQuHLFUDoOYDfa67g5CnBq+KAjxrgY8OVZQZkKQE9Z47wAQ5g20QRFLB3sOtCqN9Wsr4oO//+t2n7li9LuhFlXu5HPpR0036zb7lZPDSBH6R+EhmX9qIlo3TAPUj9zEXxpLodI5DrVE7fr0sgpLOkfKP07IUpqoOjrhAHwa2nHAE4oGHfi1PER2sR7UYhr6mu1QHrkwLHVkocci3CJB464Z53n36JCVtrSTqcu0uXcT967XnIXbCWkhLKyhKQeDnd2Wb8HLfvxF0B1CE3J+CVz9Od/Uf26mtm9+N6gPvHoiM8no3zpF1wqi/q5D75eKOC8amOQ8CdGCd6FE4k9axQZK/BB+vsxH2Xzd30SRHEr51zBhE/ZkvgAfnnnv5K8NIEnC/bh3Ad/bJ+vDIdPnoiOq1N/jQV++XR7odDPHvq5gYKXUuJ3dlZ5cm1nKUXp0KhCNuUvjTL1YQ5GyGU/cHP5RC4328X/QnDx9xupVBk9eHU2n5Utx7Xyqey2sNCKxyUXgAS+SC8qNXwlmOQBxPYcGsluI8qqFIDsvCuj7lB77itIF8Tg6JkvAa6fPJUdVuPiCqwvxW5xJaWSv6etsqeP6IGVPQ9/mhBuj12HC4ErBS2c3CE+lfiHElpOKi1aonK/Ha19Tnwu9mf3wuU+hqY2roEskG/khE1Z7X2Gp+7wUSspuwKo84KRVcC7Q1tbeDW/2TeG7GbGv9PEaMWtAFPD+D5q5GBKXLvc62DLwF/S98nmozO11OQG1xQTb4L62y79Yc3iqFBrw6hrro4X5berOwLOpGswJfB0FfjrOJUHeN2eT6We7mBt3XaHO6gRxMGt+rwwVMh8tmqqQdbp4WAkLOcIgXZ3mnJBRTXUc2+PH1c66jMZa5qrn17JFB3Apw6R+WJBqAynnZ8Va1SYp5dfUeWUKuY5a9X48emEXV4FaB5HfXEl2fNysgtZ+hAKNl76MAhAJWQzzOvwDWfv0Od/AjqR50Gtz6B5P68na3mhffIcI1op9GlxDvY4MKAhsLaABWzeo1zR8tGjBytwr1EgXcgTgykP1T8Si6XnmoqRQGtYK0/YFvfnWfAIC8o6BkQegbIUgAizcFa3iC4JudOAywyUtggDdRqOtm6N+Ic0ITwPPqLttIA8kpXtqnoS0oGHXs4mEv4GGWnPzP5Gni9r2zien51+wA4dVzqQX7CL5zlKvhYPnerEGqcG2XJyaQxGArwtood3xlyEY1+FWL2eiVEO87m3e1bOvIqaoApJUkeePWTdVkAQNcJDTrFTHNIx0lABDVa3NW7un6cW9rzuNseVuGcj9Ic7cs94E1lKApKYmZwzyiBjnm+kxZr0fJFWfuADLK5ZzROQFv3gZTBudGskp73Qg9jV/4q+/DGJR9/UC2q9xLGNCj22VsKxeWVreh3BFsSdRS0Ca0ErN1Bo/H8bo6CDl7bBN6xlTf5V+UUSdAwK4Xr529dBBre/PjuwFFGjmwNgaMuu8W+RdPf4nmSP88SeP+7FpbywKtlXKX8BXjizW7JAfkcg4LpkG3K47BYre55qkcj4G1z5XmbT+HHVXlAzvLrpwQr3au2Xcp6i+dFUNFNdwpSpyuavg3w/qMpDqkwzNu0vK8A7vZsh5esJ6W6Hk5AQCs3vF5Kns985uj78mYga+KE+Tcasdu6+xK49eSlAF2emZlHrWe0gfwkkJAZymQdJbxnbvKUeHWJ9NaKHeZk3/nk7uOhJbSbXctV/7kqgXiHzk0JA59secGDcY29/OBJ/UtzzXMZZ08+ex+uyi+mZG776LXAr1f+/vl5Sc/dP3ufdcFoQPYIHuAFgg5Ew88DYVd3/14AzooDHZJRxWrAxNV8EM1S8rl8mLUZKnODcTBa4CXMELScd8FTDfwMgIwfSrvw7R3grw46tmfpQ4tvnSe46Er/N8NDbiCK/4jQ9bwrIwH3ALlZAe1D5vdBvFD4hRjkR4SM3g/PmWZl4Ou3nPbFPoeDl/jgZdzAniB0fZlN6fb3BGVd+Y4jhUpJwAc1UE6vbzgA3XVA9EOQvy7ITezPvXE1FFXHDYtukKtaW/IJFztrPfdnLrl2UqCmHyVduad6IABUdyh60ERSJHvDLzgx5BkgxCcBV2IklP/1N64e0gAeZdoekkDB3kPdJPeO97H5ycOqDzsq6+DBf6K+1/OETkLgyCez4DqKj+ECWH4enCssAUj976JC7txYTtVx8uHxekP0HVdiJMgMVRDxKJh/5qzvCO+88bEbW8IQVFXN2sR8hChXq0kMSsMQCmjvnU1lQUgekNhxS9zJU3OWAs9EB+w7ePWiseZHuSI11C1HS7Atn/6YQNw1GZT8TbYS1Z6iOgD+7O3Ptbeb+gkGTwaEo1B2rSljmKg3A58SS+SBNrBtLC8nuOfk9drAqQP7OaW556fE5AE7YvGNi1yTCsBXbjhvqI3Xiuzoj64jcVjHvydWlsRdRFD2Hy7UqXn7gHJigFArwF4Ulb0E3bObTqXcgANQ6wfa/Bp5XRw4KQ5IVgCbhzNB6jZcxU8fwjm3AMDZykZNhm9zkTLPzm953CxdAyzmBM6so6rGpx5yLdx0Cxfmgm95XNFHmVzaD9vH9yA7+KpK7sz1PGnNzuA7B4Wd0osu9K9mPC2kZwZW7qtvnC6VCTqTcdZoZjx36KS0q4/pj+nOaEBo71b0vU+huGcs+eCsFI19uQcED4YxW4YyMn3EnYaEUVkdEkdBuS9fQ3M3vVTkrkk12YaGabcUngtK5XbTBsmfAbqFAEkFoPBerjxwYA9kjmIso59T2BTQZh4Pt4XBNnFSlCuVdFcTVrrg6NNeuMPk9CP7U2uitf47Uvkgs9Jvw0JB7MCKE9AiWSIwl3M9eO70FXcxqsFR4h/aub04tLo3oRIBnNjmmZ9TgvdBWo/TRAB9EciFg1fZcFyPEWaoP7BZiG0EQX242FyNS/VDiSjamj34ILPpf49vr/rKbZ9IVgJ9j0Ct4KCh/4u1fyJ3rvF7buA1B8uAdf4GmdHzHzpYANp63RHYZwki3wRG7QEGnzqwly2gQAk4c2Qy5gUhJWoAr8UzKK36DB3MfOCLvX9fHK4MIAI1UTvrr/aN8s2sVMlDQle4y1/4ohzHLUHFsJTE6KYNbnjlRPvpfGBHncej89bYuorrB5+Lwm22RlwHjqbgftsp/4+sNV4Axh98fBMGM3gP5Jcr72lWK/otIH0LwvtzqbXB3f2y/36pZjf9O5FzPJzmWdpqj10B4xPxkjKyV02aIIUDNeNOE6K11DvCDU7IIxrmEYJfobl/RV45A5O8i++A3+4BhkT1hTdm/YVHQMsaJQVCQpnm8DXrV/vqUiZfegtq2Mn5/y1JZ+73EMotT7V1LoQ3CLXdcLn75Yl8T8xp0xd+wZr/phXGh2PN4fo+oIybvJ/Dd78IpX6BZECpF0Cy0fJAVAzqVlGufPxF4wYNSPsnodQ8xbLDjcfG7M8lMuHDHMUAaTkARhD507x61vuujx+ClD3xdPAZuwDl/f+bFH1Ia4oQoAdB7Qdt2p0Fzp5R2qq4/35ZF27te+CI1RFxi6tLksqyxpE+d/eyN3PQ3CMVlNiEjjB22yC+B1l+OndBbyKCI2xjRXli2QlG9cBnk9gkUNDGyXDQmti00c/G07C3Y0OSBqtqwHnxf7umvAEG+6R2dFQSrSNU/bxBRx9jUoD2b49fbSaUhDwFO/9tG5pTX/CTnwXP6OFTH/3GiToAKzcuusQe+nHMQqpjefINtEryglCiPVV++QfPCqX60q1dYcj/sipLzBvavVdz/fDHXUmlEyF3AZHnH8vuq5REr3ELBW/G6zsbuViaMrSSgCyOzwjZd+dF749bBGgY2DVqzUvEVc3lv89fpT4Ccnv4/aclvK0dxpeOB8Y9GJRRhcJPblyCwknoj+9A139a63Pxu+hxfYGJb1fQljpXG2AadTp6XHMaA7liw5WHbXO/ecO1s9sm5Y+DW5/9r/RbT72Q/e7LyDojg/k926YL1/NF+f8Ewo7uT70smStVAaRyXgrAA85f6ALdpkdqbAZXzcXb85u+eQqN7hOrxlsG73NmjBpeWwnarvyXjOU2IQdA3w9kbwSVPw2EakFRSqALx+97VgJZ/eCgkXRut+50u6cWoqj/7owNTX/XfvvDan3o38TqvXKT4mD2vMzdsGoZrUOtY7KfrIvmlfHJRu7npo8/lm0IQGXDA5MmjxMwdET2sET7+Cjv4xtoWGhg/uqWE7C8j3M75S/v/7YAQgePcPqye//XPl6FoBq94SYQeV/GM4AiKoJ68g4Ultcq+An00pqr2EHQEQr85Bw99OANALoDrjM1r/WWzP1xK7hBAu7cXZqqDXTc1gQqm4HbF3VyqWp2588N73/RTPNU3D5UuJlfPRYsZCjSLiZ14E542zOOMUoiPd55S9cU2GNCqCLQDTYouwpTFUy9AOPif287FdhTdeiFpAykZh2VoGrNpZtPf/Hv4OImM0MK82Y7kac+zoq8A6lX/4t8BTCYyy0fzjh30DELOqamuRxQX3hYqPdYg9A1odN8krHvzGOmxwx+kvyBtlNDJcZvAtQCjz99DoLrcFgr4PJzbG+4ItFL0uBgJaAxb3ZNZT0J6izxqSDjk6bqVsx9VV5AW6IhE523fv4m0KTplSjdcRow6+jusEQ9KOd205h1MOLVKiFH/xs9jD4JwxXIPwGcBEBCLT4lwVDu19nlAGVG/wdHT0NfdxF+lKTzV47wnzScfnMVUUn+8tsUj2wgiqrHSTnHAxArYOHBujcoVRmwFgV8/HRRe7aw/h8Tjy6p/o92u/8NXmaS/hHzTK4akNlZVgVED4A3v/ej7lz93s2B/8yV25TvkNZ27e/goIf/x+XlFcBT0ZuA9VmOwAKya/sufHMq2Xr+ba9agkqUX1xS5TYmtnT+BMYgoG1bIfmxq63nhKaezpN7LfGanuLwKyvmp2PUPypCApwL7cXTT5vLyl9MixMe9OcOVeMupvER3oA1rI7PJCW57zRCBPJRIKDuPBToZ5gGTfuEJZ4GKiUAX7CQGihoMUjJhPAaivraeXlVs/xwPirkJ2DoADSY8yT753hwup6vzseatBQ8T5tF9HH7Q+/Fk/9NntKBQYYlqvbVPsoyH7xYREtz/2ChLrxMakc5ybEAtC38C8CLAOl8cKPfDcBl8VZrSbeHxGph4uF6oIgYwaS7MaFpIYL/r820Bs9wONME0goFt6pZ/t39DfrMxbMQXMtU1ogFEsXAR6Qg3cpDleFnaAz/yttrs40o3j2SuiG08sR040XzsOBDrjUpAXeEciEhPHLSc1H1uPB/vnVmd+ou5kpcbc8PQJyAWHcIUNRaG4hljnybQHTeE/HVQzwTSTaRiqm/phhZaz5rX59+Out7WRxQ5HTlqMxqIPfgplfhjj+z05JLyWf8zy6lPFvza2nz36ORVsAtKm53U5nXtprrkcoxbuMPfYTbRD2rUz/8fHjh73mBofMuuTlyRkgjYoIufGaYuzUDjgLqSv/fYZS9oM8CtDqPaKGCOthawcIkP9c8LRVFcn+vfh5xKeYoUDFmUyAq2nhBqP1v87X+pzdKIQY9cPm721vR/wVD+D+MmWUPHnMG/iZ1B+885PG6ZxRT1xz+xez/mz9lHYUz9Fctw14CoSp8cBLOsXb9FhDO1zosgkpSAiKPgLXUFxQMpmQuPtUqT3Yv5m5Jdt95CQSt8Hzer/Gv7amCbK5MgGBH5LrGzVCeE6CqAJ4mTuc4kVzDtVsjo2UTyBN52ys3+f3osS6Ktba0Co5gfYFk7azgtKfqdpfDZa8pyM+NL+L0fhQddAm4b/KySTVqzOv+2TE7/mwD2mCRomctUl2u10phQfH1VYhK2KSzucxBQgc1xdU0fMocPn338iF/3ydqzUrAMQgEaH14E3D7iSn1qR8E2/63Xbr8wMmVD6nQqqggBG5JAS5E2/aGm8nfeexYPV81cNkCCg3q5QeFSZ39W5TyOqnoFQRVFzcltsAoaLvYIJR64PnHKA3hPeiggi1/53jz2J0HukdHj1t8+1qycsbnTaDa9Dk/7pQd+fJAvHw94NSNxhMk1imURfn+mT88lVK4e08Aek+Rv17r345KCVRaP/9lIzdh1ghCtmbb5pOQUEHjqtMRzwEYzz/7ZVEbPlkDbf1nCSx4gcPgmfN7n+GcfpyPD/UVkF4CC/kmD8mXn2oGZvZY4l3MbCpcN+Z6560FY+dUeGRxu3egfuvH5+EKQqoOn5wBDpDtXoCUcM/vK0bNFQbAjz8BeiROl02fZR43r6wTsrURyHUoexzD11JwAK47xPFIHw6gCIVcM5dlAJ9oIAQvZ8YhMKfHek1VV7aSw41hF6YJsg8eCAQdBM9cMElZWMWVrElc+Nj/4kTpfHxMa3EmiA9s69uX/+XuqnOh5aLFiy/rwrqKdxBi4HJtSK7u/7mdf95KTGTmWREz+TfNJ/o2RgucqzprcIWvQBlwCSGrWv7Zn/pASfr6KqZAArUiZjuRcvYI64c2EPUU9ZvmtR4dtxqs2HD4026Ls/73jzMSX34rjP148qQZSOVITOv+NK6JuphrWRJnWa9vAnErnx/egdA+XQ09h2nM4Jb3W7wxFzUR8+3ahamN8QDkcStEkepf/wXKQVTXpHDqBrJLAZgWQWlZAjXf1xO99P/PPoyc0H3mAfTFlgNOwiB5P5DbSXGUmzrgeBfk5gHaVONrtTaaua4OxA4z711tlXCu+kAxDFwART6ICP06+qQ8aMEofURFHTiwZ8ICLn7HvVh+QnAH1elBgcNQIMke4OcsolINZKGB36+5TB7/b+Aa9fq+79J+yltR8/SHRXqffwnpPYx4deb07mvwo+PPXJOWIWpfp7jqZw9XSKCC3ttFm+6b5KNK1onOvA5Srwxaj+b9Wy4lu/Km+50r5PqjYyFctjHRNc/MlrF/KNnzT/bK9ivEzS7PwmjGL/zN75U/VMErToWngSt56b4RTevgqcrzchkxPbjXV/Ns8onNRwS4r+Gvdu88pp3aqTdDGTWDRpfyP580dXC32x6XeDelnWkZefrSUE9xXAvXVfnez90nNf49ITv3V+bVmMcV5o0nEFCdrgk63wammm+Oupp6ZcuJN/4NL5DwqOWrjeuD7/GSTWvoXstWnwRQkYuhGXTy4d6EFp4HQWWf6Og9ffbbFZvjM9+42rg5Vi7pZYzzPgDauniJpZb4DhVy5ymaYzGTjFze7VxbcxmByofx/Ew3l6i0MOvtenVIs3pWFjxjdxTw60s9daEbGtfWUt/6+6+K+PmVAK2Dz4GPgY8gqNVDXc8A3a03/8qwVv6+ZrME6o3W3fcm4iL1DYgr/OUi4/lCra8ZuM5y/fdDEos3vEEGopALR35glAFn36wGCZQyEGkJHrQ4v8g3+lOZcPng+y9ctiqgz8lAcQRIK5zjGDdD1SDBjTzgVb7QWpUDOYuTPFeKct6Nh/xcxVF3OKVn5rtzGorylHS9DJWuAor/gjMKNzMBOP7d0SgHnlbBYZCbM4987oF/Nl4eGHH/Ny/PVouSOletAJxL/CMhjiqpATLM4VI3xFHlHiADHpBBk5BtfiVPba2UPD8IBGx+hjRx3wI0cOCZr9zyh0BHP9DSoHBhVK468OkA/PLzEXEAaQ5t3T4N1C8BK3uRQAGTGheXV1ip23y3ocBxCbiNQGvN5HIASuq4lv7e05974NY3wOZtBflAPlQVpNUBjNobiMoqgcodot0X6B5o1yCZ7IkqiVSxjI8Q5R41oNaR9zOtxuf4bAYXt0Irs3O4Z+zllzwPU58JmYzs5QOTabu1J2AvT95MCLpr9ncp+dbU/DPgo8jhl8BvJeFxNdTzmwvveH9XqNxDnLlxm3z1d370v1oBpBMAg0tAkxIwNK/JhyI/ByDqM3/bIW4Ul6rW9ZaFNP/AlleQR0oTjXxbhYC5HyhOQjVSur5TsAEE3rscvHlIDXgDPBUG294B294Gw98BddAqwMAX1H3UvZs7lxdYFIBvSJ/sIcsb6zb4nleWn9M84Sy0533TRFA5TSHtgNNQVXUY/qvJzWHQZuUjF35wsecDH9AFkd608vsSpa9+VnoKn0Lvi9XolNeT2D3pHbSohjpu9tg/GuzgZKOI7dA/8n7r3cZzutXDlA/OGCOkWlZx+H6OLxbai+dkHDcAmXl5PVIdVHV+JR/gNy03hd8+ZfXDsqkceFW66UUWSIB/qS4fAZPaHwLioqhIPKdtducFOHwbyAvs6eykhkpuZtrm92YRTg9E/XZmyHfjXHOduEd/7JqnRcvt97tm7fVXElcDpya/wnEPOjZXzOE/rWSjjEukTESBjQG5U3cKxDwDxRbSroP/U7l3Vy3niNBNVNdNzM9j2HxPg57o8v0//AmWEwLdjuDWFcY1gsS/2NxhGx/BHRCKN+HJhzYSipSBJ5InHbhAHbfY2x5y3xlnhdz+Wo4dyKgxNyvdwedGGx975egF2iw9mi8YoRx2au22OpRqyAu/e/CFQ8xeZU7qatYdplJG+eAeoFScyoE2c9lM/PMG/T8h+uMz61/rfkHklYQP5gfkOC7/1k/jTo2QkE4T/NdNag3xu82Dk2zzpOSBWoaahCdoR2RPivbxwn8mcaysuRak9YFq6SUJdByg6xdUfAAu8f2zBzCXfsZW3/4OWIipedSPu+sQl9hBwzEl8N4I7Tik0xR093eerzmd3wlIeQUclUDFC+8g2rnlT4P+4w8q1GWYSqB0NAAfJ2BpOxkjb30BuDo+f/6BK2TvyUPdjfjwv+uoLr49BeC89nMc7AQo4volFX5dGYh99dvH9TLlBE9P/3PVLzp25mDn0uJG7bubW9r/3AHZRqRFJ1ReTkhMGSHc3ff71wGDp1dd3pHvhP1eR/XUcP1r9VMs9mdTeYRQvcqA+UzhMXaLGnDgas+uQ1PV75b6F62vPJesS2SchfA/eTk+73//UQH/N7DLaz1jNKM67/JFKLUqBpWlDlk4D/1/mPjycCjf7//7mYVhyNhlHfuSbAkhhmzZk+zLFIUIiSLbg7yTfY1CJGslEpJ1FIUKqRBhkrKWLWX3m5n6/K7vdT1/cM3MM8/c97nPeb3OeZ0DHc+4IoMScwL6JqCGpHFeuDCGIWXJyzL1B8y+IeV+Vzv/u6Xn5edBwviL6hyU1SEvS/3Cg+ziyfnnhzR2Zk+eFUyPBacit8QeBAoXFmKhEGdMV/5TXH/rrMKKbX7BryLTQWZ9wKgPBsIWcuU1CjRWk3QpIMGpEjaUJs9H3GQ3hjIeAw5RLjhJGr1gYccYbPxN+2SP9nqr1W6NgL/CDzh/6AfsbHvksRf9QskNuEuJyY66xszME0mYMByMfYzwvDv8toVh9UoNh7/4NtYNktIrjZRDO3VZke7vx4WIk1MuQGq1TV/w1LJHV2Hcr7uJ0IwX3VT3ON4YQgLwsvo1y3pjNyIaNOEJ9zu9bemjBS5khiXqRzSnFmT5vnX+clfWNEZKVudNozlLZZMYtQgIqouaDZlItw5HPDjb+KhhX93VJo4RxSMtrAPWkUfXk4WuGRNDbg1yIx4bbZ3UCTxCR8p/iVDIXaoW19Q6pZWKuKMSveQfsEzPiWrIik1gFECSKgQUzlB/ls7ZO4bXE4eg5spG7RN3nCrQhTZHu+sQB+oYRG5y+QmZrDFgCTIL7Ld/r2XpOpYbChrf9IKFfcSguLPhyAPLgo0cJFEc7OYIigUA1VTAs8OlYn6N3/iaT3MTROgH0KQj5VUf48IZb9iiCmcwBH1cetgihu3isDEWakpibwHpM99usqauTxxhAN6I6v2B/+huGQOMhMAjc2zeA/JYeusfzJ3Fz4WtJX1C81/CUPin4/Moq7m307F0P912IoXHtgcvaNwweJydcnUywkREGk5HE0UxhPia7FdXZOKWMFzwO9Mo6227Z8LPDco2s2V65VFVD3auskWxyHGIpUw0AqM8fjlqhJrH68/QDbPC+XgCHzJVafZJqnfbRCe+2DiqVZzzhLqB9lVbRrW116suLvLsQhVc1ZcMZ+hmLqgSwpV+//7sk42kRgD/NC6E56+nblDOJ+bqbFAtDJR1qZWvXhzwwMAawhDOuDFz41AuJN1rSwnyyki2VVdVJJvkMWr+4dF/wC5cLTEscuFSK9DVuTSudQZBuAUCeS/NofsZZOcR/r4mjtK4tWnpOcs65Pd6fbLLJ21RbJFzjJVR2Tmki0vMbnP67PkwzNhyrjqQTUlSByRRNuDGtz7wFSoLb42DgjW5YH4kQZ0ODmQCIcfBGSa4FtgxqkmQLQSRCuoCk6iguDsNwG+V45i/MyAk72bowBz1Otz7Hu1KbgmU4Y6dGiL3SxnS6B6A1NY4lmUTusjDh14BU2bEZ0Mwhyd8ZLZkhacWSwnTTIg8TelN3kaLa7/7Dse+22wRAXcbtQdQOo5At1+Rbhx9jFA6rkXFxPMmtfPMAS+2mUy23c2HJDz7Fmp7emoOC15PKg+iLI9MCRq2rtye4zO//H618YMvO0qqzKOmbwvFEiaJSflxckXdo0oe488+PAw+jBrFJ2LhP3eEaPaIg4/L7Bip5q3X6dI9VtxKYjzzYCpL/tsXpF/jOFSxj/AudqlkoE3DHqTvW7BBNAdTCRJlzyJksfhhWSqrBYKx1XrS5APBVFGW6IIZrpCvBzFMQUnEE/vxh1lGAt/GiEBSJrnuVSazXC6N+DkmMGCrHMJsJoeWHYX8TDXIVnXIwSywZQyNXib5GEPve7dFMTgAv/7gb/hbYmzL9IFJ6u/spUKPRmD8FSFl6AR8nIDyJMK//dFhP9vJ0yHLkus6H7ii05QJNqp29/GkPDop32pzZDFSVuSvCufFxGnwfedgMST4UUx3uqU2wRhpqo/MJOUW0DUMFsehG6r2EVrgswfdcum6pJktEyc8e9i1WV4iCHoDEncdWIofXtvlxeBpEM1pOzGtKTW0sVsdTIuBrTkQaMNVwC5gJ/j5tW9i/b52azZAy5qQnk1f33Cw63mMgd+jYGVIinLJJwBc3WzqDwRzgHkYx6v7HATPIwR9EDa2XNVyPR50uUBaPb99NYcuXlNqI935K+IDEJAbQeDPzDx6h0i9iu6u8tH9k6Vi3G+CJVii8HTo2huIIPEoka5Li8qit22YqEZH2dKEAkUHze4hU+Dbj+7f1y0HZn+z6Lw9HQAFXoODzruH499rkTiEPhtiaOxQGaFwU7wvZ+DVWYgSmZNo5/AQiuRMMf2uryko0ucUHtdhdXCfrfCIUzhq/NJjWuzKRZPkNYUo1KkCVfgYxB0rO0coWrJimxWR759tNmX/+TR0PdmZlpK7gCL/dgJ6gfp9bHLwHbQkt/NYlWHpdz8fVdLgOVeTGfy9dVEIpxJe1cQQoB5xYLVievsk9C/35IIg3QZLeEjK+kmh2FdsxRcZBFlOnTlVr+Ser7tlC1uLLz2/yocSDJukHzzXW7y65+nHCv6myHu1jkp3b6WVQS6P0cQyYb8LwLIkTOV82vVKT8yb2aaPewkRcGwg27o1Y9cV1a5uNOn3wuoiw98F9M59vHqKTh0JxSkurQQP5aBxH1Stjk0YwJLcJAObPTMv+LxyghL0ORD1s1cg73GIqQ8DExWoPOvcn9KsnfFQ4+OUkwVnIRJDAeqw2aHC4S2D8ImyxafP1X/3SKtjj3TyB3OZxB41mX3Vfbf/2zD3Mxad0IiiNY97D56fLHCwhiZfxk04E/1FT8F0TuBH+6jdy++fPelQtMWE3ZyfmvwGUrcKQneOswc+PHbSKDbD6dP58oqQC5+6l0YQuHp9ycnokyI1j0F1LU+tkmYdt8pbeFjAEuVhFtA0umsbhA0cOSj02UbF5P48htGFLfhB/KbBmmvH+rwsYONH0bLM/XMhycZ7DW6/ei3Apvp4zWzwKONMfuMh9+fDbNCSzBszrvuvEeHvP7bcGDjB1WOCKmxqKHw/LHcTUOLTQwZ4CIVnYoMLGWCq9IP8LpXh9kY4+VdffO21wzd+63uXmvdcnNleMWMivLOKm9mHWXBkm8qA/aQMdiPeJafIxSzolv2Xr3dZ55UwCF+IY9IQCdmd2/QUHdmvmhXxBwePvLp7hqeIm+DS9ghJnmqRb25PeXGVDpAfDkYbCiL9Y/sCjuOIm+fEFWOzR18ClPYZmvjJwgOaeXqzS1dz+QxjWfpuJ/Pg/VYZVK300xW6B4v/Wd3Yel4xajHO7RD7t0r8jAHUAEEWKCcTTaZ/D1TVsfgZWfjBfSQ5sKJ1ey4aPKvwkZqBMmpB8S2fV78zd9xO0tuVYv9HkkzGSwrbTSqRCgc277I2HH2e2+SmvD/icxJ3olRkP7/oqWU+OfRnwRdvVGT/cj88VHjEz+8dQiEGNGSKRYaPgS0GoKoIAtxT32dsoi4NHVfISk0XzlT/Cl9nhP59S14+UDMDjcuK865BNRUYgqInVdk28wYF5yVbJdZFBGVSQL8zz2nH6s2QDgNI9jPCxfJaoO+i5jOIruaV3j9ftWb2RfQ+KnjjLXwZA6u7vVmxPsEglznGJK76vD7zx06gNXHto5IUN0zBfUG4Sf4CWqk6W2IslS54U5Jo3H1o0Ut69MO9C0/Wz78/nED4U5ymUowvgxpfmvkyEARelj4B69E14PLLGnGcomEKVemlylz9y6pd254XT71VNQBlNUBsbS3+IGj5M777eN+nbB24e2zCFPMtgN4Zf5clNhkyMt8jrKzHOh7/QjOpRllwtHNUuL9v76HRng797e6BP58Q/dZvVoWzplneKiwyekHlWlxcsJSCqo4DCGMtRsMnH2jal1b/7BhyZmnq3axm/GLvgunyxhL6ucAAF6DpKVNuAn7iCVDat506q81AzL/7Mqru85+6h5PmbuIjJuDz2jOwWRthBobZgHYbG56WVE2xpHMNO1R4OinYEF/qAla29I6iCt/3nDyEP3u4rP8Za+NAezTwEZM2agGN31Gfr3Vv/Lz66eyL7avFhLR3H0Mhqeivpw7d/Ywt5Fvc98eL6jEB/9j908//Rx5nF72NmeB1weVSdlIdIeVy+CcsiX6W64q+R4jnbfrjCILO+R2ti+W/eWvQ8Ju6AWmJzrq7oPHX4yGdGLlyq/CS8yXWjkxGAi3KN1LVsYS1M4VHXiOoXg6giGZUSSpJlFbzUD1DTa/h3aBCdgeQrAvqG2/76VMFfL1sgJ56Hr5hX7zr+/K1bcHjmYEAOA6CS7EEB8z5iGIGarTITwWYA9cyN1wqf+CsbfSCYmJNt7jf3hQ1Pg7iEoL3xHoF3E8Xm8Z9qOtvDaVtYC3bNfzExzAnWyY4/n5fy6w69KLCJz9xrqrjFDeyX39NVd0/8/PdQwHF5buP2Ij5h6vTdODP7Kiusnm6YQsX2xQo1Tb3DNBjIgog+9lklKYLkFS3b+6DLmy44SUpyXdLTVdYIX86LDmzAKJgdNzFxTrS6uTW3fdeB5LGh5Qq3y48cAPqUdx2DetND6GfyYLWa1sZHAgHRWacPbUALvdFWw8M4wAd1BAoUkaNiIVscfHAkqyDwN1bb8GD3Wsocup/8LgKSvZ+LisKjtEkiLeclAenhTEDCNiXKrja96Ptj4UGitR3ZFy/s+sor9IVIhNBn85KGC8vA/kGPhVyNf7PUFHavKNLf5bPmFOU+wNv+Q7LQ3mLzpl37yT0X3sefC5+1YEx1AyYCuOrMIRahdrV6SuYuJ/Y4ia2Qgq9Bh7vd6/M/1JvW5UU1Pi+T4fMkIdUgyAjxrYFM1vvBVn0Ta8Iw9Gk80knG3XrXzI0HjYPWhq4NajWa90TpBHLaJFNp5YNUsWBDwP8FIVPpJJT0p1k0l+sQ0U4JcLgGwYiHqOuRKMIgmzgBDQUQd5VqOt7VP1+VnYWwVasGupfwXzGwh/6zAT5X3IGWWBiRjmkqofMg0jlGE+6CPkkYgmqyLnNtQY3cTTNyE09QEAT/4r9SFz7PdsgpMTRnuNv4blftVNZfq0GfB+x+NtPW944pnhF1exPvcFjehipj3PhXdTnPc1kyFSkCqW9l4gquUZP3Svw6iu9J12gOAp2OskBa3wploEMLmh2nSP81cwm0zSzPecId4xdL/P6DESMFhHqyKsjs+EXvGC5c0epvtT/ga5bB4LID3rCGLGkNft/hcN/im01PLNCYink68Nb+NQLLrpl4ccFawpRC7w01dI+hP9bOVj6GswsTC7is+Q73MzkxmDdAz3mhst/eBtDJyUATZN7kCGphYk4tfP5KyQpAqb0WFoN1BgQ5ADCnQCII0YORJ7jJulQAycYpGJGo7W0s/J/ITYrVK2zR8vxHcK8OFHwTdge/NgqPDieKQMxilqIE05xRA53/G6XjXt1s94qbK7vXuCgPZBt+januV5n1C7HcMN2LNzJiC3jLf/zHtb9x4RfXo+eWh7lAQjqziFiN9/WNh3d0XMJPoQXJZYPPi8BK+55yvftBS6mhkTeABxn+2Pn3Z/wG7+FR/DqBe+H1dmf1eIaEjzppkwP4T1uQ0feM5XzeNHVKr6Fed7CNPSV/GiAxbC9s4qzJv/gJdMFbqU2EdCaeT3lxHL+SyHm9djyHYtnfMShfU8px9meqRhNXmEA+6kKgkxq0Z0k+hgkLphFSGH8a+Nx/pexMPN72SR0If9jsGSKhbGXK0Oa2EIMCa4fWM4UcyjkWpESrUhUm6UF0qX/wHqsHNVe+x0ZUs3ECQbiFKDra/18uOVSLa4/OA66jeS37zUzRvpwmKeh++v+cPJWsp/+lWZxrTfONTLyhchSG5tHzU1fo9NwZdxX94uS4YdVMgKv8uBlqLh0gb34aySgk/xVFANCY3bNrFviGy1cIliVUmU/nPW421u3o4HA5SKP2Eu+8suMbf4p4P9JEbifAcMdIJgdWa1PFZWTlNMVS8dTwiLeZU4NPAESCogRW4bqNDMkF5xDN+NeFKpk72rzNzqSYtmuPWuhCsiugwZTcI100h7kPQHqGVdrQNwq58dc3Rrtc6wGzx++0phF0pS2DQex7eK4aMfKDIUSHI6P5uEbz2Dh25nfXR+6UQ5Kmx2jnYGYv/QxHqs3JJXq5PvKwkwLF5jguSuaIfY0zD7Lu+OEAG9yw9n/69bE6ZtXXG0RA930E0lkd4govNfVXb4rSMulpXyAliToSE1fLyOtvZMPYleDrO/3kwWiqWejlMHnF0Ltz/t3/2MVfDSyT3ElkboIheOk3JE932K+wkNjQICWGXihKxAEfPOF8qr3Ue+LPwZm4rGQ/X9ZjZDScd0zA/zxHCpFNQo+h0Ty/1TNIaQSWKjrk1AbUgzJ3aQXVfNgIhXT1CZ8GXQN+48jxTLoZgwGwCsMyFdg7uc9Qe00ofgdDsGnOej+iuIgLsKFw54HwWlRmQ29mAhp7JbP8/qMuNmt0QEThCVK+sQ9qGhGLq4uaVJ9HkhF32MWO0IL0innKQ6vt+hwMtAiz0e0XgrfNCnQHnwxywBzEGSwB29V2XuS73SF3kJXBzmmoXHzdLM8QOCb3xNgyos/i91odTHIwJDMzwFHw0y2Lxrnfuf0WKqJF2NDLISfPbOLC75kFg/wdFTx+bIdePQSVKsyp17rtaizgDWwcIqSpR3Yrp+P4zsIFPdVs5ZTKLqnKcpZmjjhAIJFY5v9xMUEjEGvL2RJEKDlm0zGVaCGH7oEM0uhw4jwjBHokiFQEUlvSwHXbL72lsDHpo/FuR9LnBYTtcelymakHENYYjAAfiWZseboDn1eiXFJRZNNKUvVkW0P3J+A4Id8hWxlho+QCm8iOrqNXJmeBkoEasfNDYatpAVSKKkyJlJX6RzKngPWlxe9X8ZCSxzxCRuJNqwbw+MNYLgBBHegCS/R1FjiTiPzQzfR5FNXXGkNOHFHFzedgBT6c4RpxP99G+UK1OUm8aIAFSJpcl0I/nUpZ11+yz7ZHjQ/BuusWNDHhAikkHaKyzZ3xnTd5hnuV/5yz8j1tT1Y8+g+SP6WjW66ISUO4eVEotK9uKIYQ3b3B7FSGbDat/GG3K/T09mI8ROFIXLTIJmCghbe227j49dtcKGuDMr2jDn1JakpTuHNrDqoZ/6W3L++Ox+Xke593RiSJGeabQycJMBzRLUjg0KOMSxI1f81CGBJu5OrvdLhntvB3GeFIxdQhTeNjsrBBlrfeiJasZdW+S4I/1H/z5Id5mC3y/Erz9va/OUjn/LbiZbvoXJfFILMBBmpN2ejYFlIahsDSan+J8JD9ZH9DiiiKxfhj+pEP0sWmmzGReDpRmAksCSaPOKVCc9/7ttBtslhqCAgpWbP4F/GV3hywkIHHvujurmVeQZl/Y1gQeHpggU1dKbhvEsO3pxBABBbmHlK4KqtjSKOpTcMNUuHJOPMQw+kgykr0kMTKNSZUU1WCp1HYUzbisZQ1WFq3qrvspxCTsQLjmIwEXDaBnnj8os1VD8vO/jXOCB5B6e9Grwbj6FWF8UO/+ZAVnPqhBffnxlCEe7IryGWargJ92Wl1kIG5eBVdqDUmd8dEhTBqGJS+wTgbJkqUiWEq94Tn92xvuRbFDUfhaZGWwJ4NEV/sV0ruZML+B0EPgdBpyclBh0EGr4bn2qWPLlgh2vejJLRIrftU5mEg7OmJ/ONb5eH898/WmRbmViqyoKg3oMSVcnWqMLN8uYGX/h6z9VbZ1ITcGL28vEcxcHarKic50f1OO9taufywFfD+FB2Qm3+41NHRDA42gNUCz5KAm+SIP/WpWw98N0BDBi2PjiTqXWegSDB5IwjiqAEIKNrwnGjD7SyecMPa/FiO8wcvr4gt9Aqwb4INSOeq0VTwkvy10HiIFSYRzfcVWtZLLXzk6/V/SNT1/Ui3IkVF8Nuw4Qryoxxi690r46t84v3RRfy84u3ISDPb81X5Z8Au9xfR2wYb97aWh4vjoFjeu4rvE2fBM5SCRkfCS7e8wpU/wtQQE2SGg0zRVFEN2qHFsFWD2zK/OnKhPMQwulgGOV8DpJnQ4uHzxwJ47bCUEHKjePgyJj25WzEkojWxbPsl4KCQJzD68gDd92RE+VZ9fyFj5A4hygL4W91V0r25tVBkXIyi9j5iaCQnc935DzaD3+tCzv6GAuCaCVNR2FwzHnRseCy9zlzU7xPca/Q6x1VYw8z0PB2zQTRsnTWo9QcRfjYKypMlvZkVnEAdms9z5ioCjv/29RMQq4OgkzV8TXo/2jjVj31aTFN0+T9hVcaH3FDJs8Jisn5mQauPtiKZVultGdPMNX1PCjbzWj1BN1AEaPn/cpCRYcxJPzWrCj/8jl91Vb3gIlg+7fql+dPju3myYaT7T+9uO0C4r+Yms0jqTjoJYaU/BqgyJMYOFnpUAl8YGO8kanGoE24YllD/jmdCFQ4KE50ppP+cbTYbt1cy4Bd40qCJCa7I/n1mXAEzoCB4MVAkGYgnNFr3J/aaHdSn9ipS5Oa5Fmv/6lF3klyPE8f5tgCzl+qlwDjepXZjdwE0833P2Dsx31qM5dJU4HypHJdIWmziJXN8SSoUIeboDiD8B9Bk2uxJC9uOIZ6tue/o5thdqcQVdKrj0zlTwWqD/2AvYr5yC9rkeN59/nIgQgYTzFv+NhDFLXlYLbgoyk3yVyV9CjMLwAqnwzhb7R+/n0racBpCKoWCAznVra80n/2RKhkxdddUgOslQ32Z4PDncE1oCxJ4YZ/8unhY4CfcxYrOw/KdQ3XrFPrwYILWZhiXL2P21MgHeLQTtgJhqX6grUFYziimq1wyIZ/IAu4zUa2nX7PtKguA1m9vt68SchEPJJveQI6ZujsGfo1TjFUF6nz7UfNOK9KC/iv4qnejpoyyQJMmYheL3iSKXsMW3hFaRhDPN3ruTfUf6txsIqDYseoW2icoW/j6AOWXtkBFvu/KWy5HP4FG2jy2cpevdju4U6wcO3DUH3QaRcLToQ7E1xVbM+HTEW5bJZxXhSWQqTqbkoHmkQVP2vTukLMj72uS4iDaGlWu1Mti91R74//eWxrMRM22f3FOXj/LxfUTR34Tg8HiqexOFwHzMkciypO2H6OhY4MicKcEN4EBGeCTOoek3z6rz8o5wt74LXv5FWomgExdQdPmEVQIEzjl5eIflwqvlP0ij29s1t+MlB4PV/yq83NLH5eE0o/Nqur9XgfbHqGbDVObIy90H8rueRRsXOaRO50+QC/hDyWiv97D/SFfkapEoDvhn3vebqpgEOFEII0iFD4X+VE5f8S8Fc4PesCOVTm5Ga00sXMdIDjruhPVbjYYRwzue1ScnrkIXpt+2/iNxpWJmHZVx4iR3PJl5YVVHTi1CbNDtRe4sU/9Qq7AfRYp7PQhDIo3nvmIrROpEliM94DjWJlkjmxTFa76j0Qdx0xQz4811EKpZsh9aSJADyyfu4pIIQib3bp8AW7tLFGLS6z8EmuLIlCmP+Aj55gpBjQnU1CkSiuI8hr7D/4Gq74TRMOUX1egEZ1fK1fPOUx5VtgSEdCDfh5UhwWH0S/b6Dy65FzEEZS55YSUj2qp0Q2az4o1UkMrKxynH51JixfYuh213F3Of63ymc+xc1dyBlhWz93sKxWm7XTTlHU8WNctKk08Z1nRcX5qcuMilTJj38MCnDBotq8YxKSjX2HoYznt9TcvT7uk0Z0G9pozGhIanVG6kq2S4k3LjIgKEFmMtHDH0p89svsEP72BLO/6Ia5xSF8zVu4nxlHCbiGYLNmambAr3+8/3OEYcRUcOC4Y/5pCN9zI+LCCDFfVGpgKrxidCxrFP9kqrk3/QGmsz4TBbf8kVQCC3WIcmOjII47Ye5R0W8RrKodFUrp/BYy5fB8NeOliCLoJa0CvqFHFvX9eWn417ssFaTxibXiDuHjW/ADPftU16634SNnnlYYwdJo2YPbFaHEClnAt69H34XB0MLbAZyT30bHwiNeBGGqnT457GL7pDm3rf3WGMhAE+qRv9v9MrF4xmA3iMyKJb20A+uZXPALDPBGkAx+eVPFGWpmIE/ibxfGvAKzqfQvDV958rsnJLEANoSlieBP2Q8VKgpYluAPvQnVfYjUokmVnjJB45shu8I+7kyE+ML3v5fIRr3dluNbjyU7BSwxXc9EDPSLcJTNJiVHLzQ36SKBDKQyCER+Ro/k6Mq4v48nGe8m3AY3gI8GgpDHu6dwhddPOfW1LsH3q/UswTzwWudGivWOhziC1tX2uYbWIX3uycq93CFOO+C7E7KPBgl9DP7Xd01ORJP2Y+FDXPC9qlTxLPB+KsQagQLky7Ev8/pm6WRBSgxoSJOFR2gNsrGPQX8c7QOqtE7Wn9e44E8oUAJHvZIDBpyaM6G2a2HT39eoncuz7MQvHubAefCJDSrJx4Z/fxT7gY3gT1j8uZZYufij1W8RhZXB0/fQxPeswHwMfFvOeH3aFfmiUxGExu9J5Wp5/8caeAhR5YsXOONtweY5sZ7/9d4+QMNPTj+vFno3kV98O/1/Euq67KCKgaCmBOX0I+xteQMcjvnx4h7vOiVVBV6ua4xZPtMhCw7febgDYikrQ81LHFvxrTzy7BOe5IYDqrrUtholFLmnBkTLBCXiCeTHjqDwi7YvFtzZ+1oPEln/V/qxxRV6qwvW0iE7KDazZIfBX4XytpZnIoDPFhRnfPnZElPoieY70bK+Fo+MokJ+vNKy+elt+jlIHtj/GKf3uwqtn/yNyAn6Iy2b9rY9WhWhNqhDlIZbDvxmg1/G4t3+6illrm2sC50TYv8By9t88YvZPX6f7HIW6mqOmCwf+WmGNJ2sy+Q9oNF2IgvcCz568VBhO0POp31qe+KFAgoUcMlcDZxqYSFs1D2fQl/vjUO3DG3Ka1s/YAtrS+CE/k8sYXfPbxQMI/yNmAqflmzbZ6+c1QdhCx7kj5P7im7p1INgqmghODFnVFP+jaYg5sWme++vex47zPITaZQXP+5rGNiN+f0l9MfXp4qgd1YWGiwo6znmwgZuKgYcldj76gDSw+TDIWrOmIRKinjUCezph0xA0UsLCTpCA+Cnzz/LHQNaemoSfv+nK5c8U7LotNRkf/WJg3w0ldEzAHLNnSEn5vGkMCE5+PXVQZm5ArMP2iGcawXxI6hP+X3cj8eOXkERzkJq3Fj0a+WQC9CwMJA7C+V8/Ppj8tmwLHwb0YWn/4pQQBHLBPx9/qtl/fPtwZdYj3ZcEVrPmWl/xKADgUZtDp/mEQVuEhENQG6Uyo0IonJASQ6o38USvZgI6RTWKEpH7T+G0YAL2IFSu3+ZL+pbbX2gai84kwEWU74axnq9XJYp/cStJvtY04fy9UfVMX/tFFAwcPA1ak1Y02r9+20MXAcWOsDU5VdFybCo1VuGTNIiBVsiqYkTUgVDDB/rOh4MHcP81wQNfr+szYYqX2U6gMAzzk/9jJJlxGZZ90DjnW90W4Ibe65mukvMOzR6q9I3i2FJvnGitP5fJah/gnyajuxFlwvs1XqfOEliC1mv1Pi6sAa65SdRwCliq7Ck7CE/h8DPxn2EC0K4ICw9d2dmDlBRhOKrOw6wXROP8GVIwJYEPxiFFnGAauH+r1FABx7ygPxHEIQRBIniCK7HgI1EHXgnku8Avc+buWUJxrfEs4rF59tlrAYyEaXjMcLUGvWoBKSZwPq5Jln7wIGdAAxBEk4/futsJmGBrl+zpPEUQTKNHRq+2YMmZ35rFAGfP3V5P++fXU/z6xhHDHiSzIdJtnJoTS/njPm8X06u6W8Q/b90hqAZxU/Xq8U7j1rz4sua3D4lHs8XojpfvqJ9J3TcG8dGE9gmz/+skqeDDSixuO3SwX5s2uzPkwJAHgNxpoPiGq03n9WZEX4xSt2vvh9LZ/mwF2h7zNB/y0IS+DCARTbgbKzGcZMq4csT/9tKyzyyl2O81xANqhInFhgQynKAs9P8Dl0M+DP9KUMq8H3FH0VZy7itGT9ue/ooDSy4OCdW3Nv2qD1W6VU/HcYreuk33UnVjp+z3hEZ5zz5PgT63t+7Mftog7my3e9vDVttjcOpin5qxh7U7Ldkt+hKGjaX9qfYza/iOMOOFfYxgYZG5P5vXeo9ijYMjMXMKHU6fBarlT3i+lWaEQO5JDbm49sZ7EBcr8b0HJtI8j1Ol9ixLJ2pH5ekNMRbJcBpR0yURd2tpnDXbMS54zwO+7VKcLh8BTqSFnKUT3HnqeovOSmsZcUAcDMlq5Q3h9vePjSjTJC8x+/x8SiVKRHDWV9e4nC5ilp9cSlw+3QMaQ5FtDn8eTPh7oG1Y/VufsbN2IF4oVO/SrXCd1dKTXYSno8lrIaemm+rEljSZwMD1HQt3gxQBySA1NjWvrT6KxdT/G6iSXxYgiKtsFJIia76KkhekebxsFPfN3LVQUUvwVK1IQ0FN8glivkqWApXnaYTpiVkh2kdKJQQkAy7vqA2r5BS7Kitg5Rj6f92jBKacXQMhH5UIQoCglkKUdTmhVgXkHJd4M0PNKGUm7CWbXwvSwdu/40gP3YB446zc5VLR5OxRKFTc7s2rokNsF7sbexDCdGzYRhIiLHT36rH1RYTl2tFcv6ip0uFCXI2WwUWGArV1tvhRtit3bM9sfMu4UdWVBnbpJKigsO26mTLI5Tdg44YLvgJO3gwR8MJS3hoiVY4sDTlpvwxHWlTj7OUjJkrhA3oBlvAmdLb2Yjw0ZIV/ZXU54B3P0/TTp3N0iO2wkUMyYBxY3P4x4atBAhH3WDSEVlZQUWpjHFbWbrOnvUSOsXQf+JEGmJZUTI3rjlijBlHbZcJtkaRxPxigBMX5M9+G6qLDzCKDS9KgjUQlpiDWqzUMOmZ5X4fi2ZQDeF/NvvyyeuSAAdEesKqQL1vsi4wNgPpJws4qBCeH0OTIZMP9u9b32SAawHOC0tQXqHsnPTB/w1eeSaHkzzAqYLQKlXDIEixmoSgCzVHiNv4LmPtTwfxFvA5KucCDeMOG5QfHn7zCYX3PEINT2E61CN0DBjWlS43sa7I+ehVaK802kSIgU4Dg+7QAnfPpmC7j5OuH2Q9fhZ77j84Ma/AXc+k+91Sjx0xaxwpY1ra33lT2sPOcvHL+YDSSEvnMeLfSUj4RHuAUw4O4QI7Sc7cJEtp4phmuTnSp61t1kvoYTRTS/ZK5NMx/4OgwgII6gYn45C4NqIToDAdwRQ6tVLWM8fafKFew4hdO5bpJwB3q5YgkcXFw2nNZg8+RvqN2WA8769OdbyfGLDJ6/NLxxIwZPx71LzuVkQVw96HLBB6EBwNAtfW9OzUrNykZhHlfOK19mDhZDfg0mdnDIkY/Mk40sIRVEHjvrnAIUmLJtii01HbD9mNMMXNGcO6sGoWyO08EXwc3I/0jiZpoNozhvIZ9Eo+S3hHJStJH0Xhw6eawH+N+y1x3I7lyqT1EybAivXsvjNXw+ZQHflfOlUH6W/jl05DlNBFvczPQv6DCHIlmsR9G7FpxbBeiNbMH0YomF6ueXlwDoh9GjcgGCS1pP9Q6mzHYgmD645YQj2mC09whwAeDC/pUBNL6cDUH7JDVAsIAxfqSCTiI64wdywh3ORlF+dD5gTronkXROrPSt8X/dtRq0jPfJSMgwo7LkiERtHwyo1Hc99GVOhr9S0IgLxf9mx1ARPfJrfNSiO5NsZfj0PWFZ0XhgU71SNmhXzHhmtCiYyMmS30DtnQFYRil3iQcwuQhQr56xf/oKDGqzXFaDx6/33jRHXmxz+a+vlTTRPPbS38UwT1HgfNwiDib9Ad2P6ezkBwHXcpQMMdKTF2v8+QjUSmNk6NuPZ90ThAK9V7TKImmd+mUw+lsH/NsLljt/yOsE3j8uSoVhzhSoC+R7xvI3TbilTPRpw24H4L64kTRJ1ThrDELGVSkum2IS9lJ1NOjfwhSW0r8yIJhahsAt3t6+1zaSGS2An9hF8SXZLWPjJr2rUhF8YvRHOQ74pJk4/FQ57f3AJh+qqOUH+XV9/yq/nwn3xh9yZowE6VZHnFkO0tzE8diuF/b27X96x2JqK1yMPrwLw4e+2vEUum0OyQRY6PO70e+c30d/Tuh/oEy2OOzaJFu7KQjsY6mQdBbTqaKHkwtdKk57V6bDhXaOhRWenjBx9iWy7H4MhH6cVOMuaYcoFKPaC1b34/gF/XACdJqLA9dOnSvm/XmcW4z0E5jvSwmbWD87xBi5A3fZguN+na/nJm/CQq7VH5alZjQ6Q+fY794M+xmz3HhaLlpr/e+iDwuPwc5p8wyfhfksoTPfqeKE0cRMF9pqC0jt7qjxPKRmZH4uM8u0y41Efp+09d7IFKj3d6/I4nOz1V6O/Prou0B0M7sil30CRRe+BeA2bi34PQXAj9nqkr9xzB5zbEwI2irc0pLviVM2POo/j+vEaOJXPhQhMZ9GdTvQfVHm5RC6cW6gBLUKWrdGVM5vCss+XIXmnunU3yZztw+9IhGlZq/d32YHdxZs+NDuSA3srdK2y1b+mnzImdeWrvFlSvPeIjxkdb7NWKyKE+MitEfG8qPkIxBKWgaZZxx7WrR7lJHPTw7E++BgXehsczJ6Xp16cQM+MSbJaU7yWhwedGii9pP3hU0H9kOsGKlHL9w2HxhaA7zTLoBRs5dLOLPHxrPzhaEjQ/snufr6ABLh2daPWFBxhry3B/BwSYgSuYF0tgVTFG9o7OetU3hKU9CJpv7q/oe4mwzH5WGT5eee27HF1h95NsFGz/CDlSoV6nIjrnXsv1peecfzMPKOthnJrA0mS6XNGEQ20sebv2absxcip+tFLTlLt4ix1DqsPBpzutfllzmWjyaLVugePcubvZnEsGQX4HOZqr8Lumzy8ZiZMGuK3ahapQMz7iBMOt4c50fqqCEwxmf5FQfNP3GORwHBC0qYefsMEG+Z9zOSyeBVkYgp5KPMiKduEpUL+gaqV11dV4w24n9GtMekq3FHHq61oHKP40CfPQFJL/aZuCxOgnjmo4FPmqLMisPXsaKlf+k1QDrBEutvuknrqovDbRR6lzh4jLPi0JvGvo7Ko2N4/fUEnO1BuZpGl/LeOeNunAHIkFzz9wBjQ7JkA0TCgGeh2rtDGkkIJvv3hgdzx+gkHZvCRc1cW6acJ60tJgOXu095VFHmK3sTHksbGL8CrB7WYenj9pweNCYcO2OnChh2oEK0eg954axIyzxxoZ6UcgJPB3iHpsO7K4251yDlpyiof4r4PUC3Qd9ftxFPMcQVuqIlca9+PeG/2v+NvlfEIY33MiNMJClWBbZmZMzYbCT1Gwzj8FYwrI8YU1B5lTmalSgpnbMtDvaz9eK5PyL5PyzuWe7deercZyrk2c3F+9WkYrP0JKZ1dGR5u27uCvPxAGlQYV1qZK9kAMA17LwsoKnb+uBArHa5/gbrCMha0Ed1++bmzX2Zw6kpZis2vlXFZe5tvemGBLt/9D1oae4gG/BwzlsrB6s85HCHP6QFW/HZhTisk36bjskUeTjHAZzUs3Sd/d33941Nu0gG567RCZIEDxxO8kUE5rRx7gKS4QOTV83PAl04c6qJNToGm/pZ8iXiBaknkpnLdO/hdDTpAENsTWDksiahBpinyu48dcLYUwpDXNbon5Kk6ydW6xhhdnHtNcgk6oAOQZs6nOyt1TyUF+J4ofALmFOqU/t+/Hky5iSLvn4fAVByrHp2w4AQ9I94Z4Pc5fw4GhtbATjOuXd3htxqfq4SIOhU9WpMDHJbAhk5AEd0Ed22JWj1Ax3/W302mc3cvCOV/Zj7cdpZ2xY/twqODQvtUcHViTDWUkgUl9pIYl3cITaMOQGg4+iPumRvGI9Cz6ZW1Vd9WB2Dz4oBPcegnyF+3nExh64AufqNnQ/b6xdeYA2uGh9FQTvl09LvVU/5fnxrAJA+Fksp0DspZOCAtVFEZb0R+xvlTNLPOSlCWR5soOJLcE+kQ0aWLRmw+bmb033MRBnfKAPdBMRxMksKQkd6i6G4HPQBMk3yDscoEYsyDNprOWNIRG1SPLt0I/9QUcVyVpIUiXBsDRA1idH95DKNJ+BNncCPDebetDV7OjPI2hMhj6ISLFCcVBnHzyJsIoJZ0492gRrwJ9mpLxkneDiiQxEcDseRaT731xt4dSHqYbPWT8VEyIsnmRhCqUinnk9GBywS3VdciszwPR79UwXwr1imuGj4H8WTqpqrbepaEgEJ/mC5s28uFN0hOpCXbfYV34Mmnaeja0cYKHWcWI+WYlnSMrcO15Qf901sjFeP5A47nW5M0RyETdf0odvMKDWC/6ZsUf8OvHl0nuoxCtFJTPBae1dI9aHfrbq87oBeGTx0JHzqiHcwGVv/1hprsZqIaJH99EvZcPYA0kMC47Vka/Dv30puuQYE6ldvmTkjzXuaRQgZ1c8MgRefX+UyjS/5Qr/j3GcAJljzps2eDntBxxCg58oBKMxzjAp4gzaNX+faQgV6H/zZ+hN9zn6KB0JsTDNoFA4YLM70MMRo6YriPOUnFr0xVLMMUBn03d9v3WdoyHYKHhem7nGa+QVd/kz/WVSz/5WBBAUwRSqI86hmx+NJ1gDD81wfvWsJEdMoqqdBL83+LfPMnMAkdYgWLEV/cz7pthknTHWDFjoV2DKlxMVMpELarBq6xU8dG5TKocSYNx2+VT2EUU3uK2L/1MHUu74b3/1Bq4VaJnjkklsrSH1jDBmzzgfwkI27PQ0k1QLQ4CnVGE7j0XbsIVxkevD8Q70FfwP6n4c+AgIlyAieHf9zya5D6WvcIHY8lGKNJLECj7ctrRPWRJJVEpoltFNIB9jDac5aHZTOHdvlum5t9W5PULUzpJh0+sVxJ+q5dXyU2JtvlhCM6f/ERjQFClk/wB6Pc5qh2TRQehnF+O+BO96d4XDxxcN+B9ZdVm8EdVEK7k+tZT3Df7045NIWj3CI1uSkMwqc0FoeD41ePecoYtbyTHJ4pzM2RZq0848YQLcOdC9/Eh+ehUE/orX6nQlHJUnbBgZbOqbH+/uAO1Rw1mgnKctnaHZbvv7qd4sZREILu2EQPyUoBTEZqUZEcb0wXzcUkpCLuqQkuHUl4F73LvPJRPAg1E29ynCTd+ODRm4kklY+BGAk2s5sQE8bDPRHz/owN6JclD/lDwVwSZlRu8ZUJQeCYEfI2IiQzXSl4he71ZCbzkSVemyl1Rx9cisDIpZgCs4UTk4/liC3uky5RJyezVEvprUbpLv6+xI0T/Nb+TFw4ZDQWw40GLRdWt67Lt77VFWPGlEIUBJKYX8IrU/7FjBRshj1AzTXxMjbj+3o0yxP9MI0ECtOIkluafAL3ZytYahu8Hsjq4OyOuYAjNXweZ0w1e3omf/DzefaY9Yxr87zNYEfCtHA0fhAoPLKKrTzLaCSqOrj7TaMj8EyRNtJcuLFuRn1dnz5Rc2PNbPM/B1XmamFFLdvy81fbtXbQyg9E3R/3+W6MtjtqsG01vA9NN/5aMfVDgz5+nnHA/HrRLU68vHfrwHdAfyGm3n4EZRXrz+8mLcDWr6XiF97gfpzjFj3ATryow4WjPVEiriJ8Antbg10ZDfKv1G9HjrmS7tsgLG4vfUadaUP978kOssA0jzA/hDcHMcxQepcGc+kGZdHEirwqaL8KI/NB0R+6+OA7/q5r5xx8wCrwbmiQHZ9HbaNdq7kNaVm44W4omHoKqw0NDzPwawa+tW0CPcpcX/Kte37YK0P87NwVU3R/h6XHQxQYTGUg9iO3mr/D0gxAP2O1GmxUo/gDKW14l3/+ocLag084YZX0CPqocYQ39r7rv0RCNEoIfyADlPTcUWOO4og9Zosjf08GxHJvYTUWJnYhBljM72w2rz6pTbk6w/Kvet67v8Wz273EI4cEn+qrW1lJ2orJjx0wuZ9LOxWVIoWBQS2fj3ln6taZ7xxU0IxkI/yYnBHNDmGQw5QXuDqHgo9DMbUY4LA5o8uZZRqrYLDxApvJ4M8J2Yn6Vz7d4eqY5CGlblP8XnYF1W8GP2vbIIzr/GnLAbiM5f/dNPaOrT9au/UkQKik8hryaJTpXrluTEPqowPbl7nu+P+FvJnYasMTx644TMdSfCjAkbi2W2ulv3ZyACcmYkgC4UfCiAWEz40SByzLmjga4idLreAYufADRf98P/OUQ/X5soB4Fy31GKyzCRs7D16XhGkmJSpB54Ejk5ZLZAqFnIOrf3aXlvjy9AqFMwDB1Kwgjx8D6C0aY95VqVaOhELyRSJfjsXn5axCdwnJMZlEJy8jY003wgV15Yk/z/y+QEESsAoFm1Hl/He0PPU0YCQJ9OQiMkCjokwJHIubFIqdVut6DF24I3HXs/evowLQgZsJ4i8OadiT6303IV/ewO5Z8ZLQ3kOIZFPEZ+aW74GyOF7cab915O7POtukZDzp7EZb1sEYfDzW2WrCTWq5F9UZE76Lcp3eLH2tvLlTcXtKNaDeR+Ja/EaV3wdrQvc+oyCyNpSrrhGCJ18hYoLASZDnvti/vxV22LtGhr/aHeraGnF47TMOnCW5j2qNrLU33KgRf+idKSFoNbF6943rj98fxSMbfevJENapj5udUFoytRUPD+3Iu1x/5VZjin28Myg1KjuieujKRBj6gFb4tRbw3GXmcWGmgd+NNnfEjHyENmzy4c32meu3ThiGafKuXUM908Jwk8lBSFfsOxlAJGyHNSAi37/28uannNn9dhbIYEaegwu0/lQybmA0PEbB7My1NIb774sPO6RI6ot2LIfELPx8OmT87FtzmhbB0CvIo++wkJq+6mDkaUua0++Dkz2VUofVPSdr8LfBHNWC3yTrHP6LM6lIgEDEERuzwG3Am66E0dv1EpoRc+KMM2+XeD6Qk6RgZnDuiP6jUwDzn4/Qnri/sOy1Gu166xX/6rrAVIbRyv29H4ctXRUMjxHcllaazzyLCI3QqiKtB1MGSAFrJvH+d/chlTnTX90cz66vnP4LqB1Sw3cEOn27xZiB44uAPn8ARTREJAtso2DBHEOV6drQjYn23eHzYEgJa7w1uc6KF9Us/tgzxmbOI3XIuo845I7W3V29o7aQj/NtDnz3ApJaKEMpAOWdA39VYRw4wIgbaZcDl2B825ATOzVwG0g6PU1pCjofjH8V41cU03I/2398DLt1+u/czGqsfeqwW8ZWT99IerSzBp+PJClKj+iYelHmDAM3zxaAZ0X+YAx4vAVNJ0rCh/t5tOPXCakrxyHbZbXBwzhFxcoT/6rsLu0ioH3KMPhI2bg0u0yiQxQGQq0/xhtmiINQbDLPCuwTE0vUQQp3ZsiD5Um6RCPAJi3DnR3hyMB5+e6Vm8SNv3EnQobVCtaZtRmj9xa7c5SC5rK/rD1hI2+pXm65eNFt54c35wSYMDG+CpcdawNt2yvUhPHQfnEYzAVJ2OpCJjLoQG9Yasb03KUIKOXFUvvt1FW+aWkpxRuPRV2r0G1FgCr7p/WdxdT6AtXBuPqOce+xPkc79A653y4/EDeep+k01T7yNf27Mire4Xu7noawP7xRhlr7Hg1BXPalW5tRwVvLJaeooYNKJBy8NUfDN/L02UeC06KLCAKsh8MwIUjKwjAEoQxAtCtpZ4afayhql0M23Zx7magjBAVcvOxCrGAjSiAVTVmJxcVsk/b+0MkbXjG+rxRlJukgUIHjWtBQg34xl+e0jpE9ao9QO+h3jLoO19LdWPBjWVp0/1EdaJcb4JOUlpcgZ7k3aA3nK/ZxZyZUnUDzOrIUNIgQequGFoUUnX/NCZo929bKAu0EhIsDcxdDqCRiorKIcglfih5ikXrb6aEe2ZreywRe5CImbxBqzqelSSOssSqwXM8OV/dCODxrLY01VE8VPX1qaVqER7303eEcTeq7Lh7eS5232JhZQRie54Q/noLkOKeDm/wFFkIGkUO1T2Br3wldQdt8TK5yp1pm0KjUhyC4IQZOjq05GPsESS84BNXYouAhLTG4/ad72IEjoLG8hF/N/wILFm66caeir58iC0Vyt8J3ohxeyQyXph08hFWKDjwEz1sLQJnf9N2Xvl1hfxOkPvUn9wymT/99v4vRxJM0N7d/OPgg+TyK39ndu9G5pNAKuA+cFE0bmdY5z7XxK/H3jHme187u+oKV6OqIV6szRoeOKxLqBZLHfK4VY4EzNiwZ3NuiDsOpij8uMYVPntCgHlRV44cBlFMyLwsdNG84gCNQx4NmW0IgM5H9Z9+kQxnNfx7sP4FoFs+WPo3MihDt5yOl1nicW8OPuWQ6Ih+fl8CHSf/63PeJd9sJa8kCcOPDKoILpdjaY7K1Mw5DGpGczsviR1ZXXGPhOeMbEOX1QWt9qDIrfIPqXf3IFJuUN8z4THh9LtBA8/o5/btVKnT3lNl0zHvgZQahcBEYF7d82FRnynDZqM7x5i/erhlPqBYSlTI+Po/otfoubC/aOyy+DT6KIw2q3lttfpChDXcstQ4ykpQlzaVQnz5LpYdDODpMZCNUoopW0ijeYDMij+Cn8ByodiiUOhxW0EqD176HPRNGWHCAfQQlplvvpiu99TVLlpBCY0/xoHw9I+qZ1E8s+CSCHJto7ulfONnEQhI9f3msZdSoDSvlk8nnQlZEWNIGnGtJ4C1n80+rpTz5sgF2NE7KTAk5SfLOhQm8V7gzLc4CLjIQVRoIfJ4xLOjH+a1RTfkgaIcudIhpPQQrr3TmT6BPn9cJS4b5DLZUXSe2cEN0DICVJGvj+8yFc+c5CyRnkzep50NHrXUC/eVZqboaUNXw+lZx7+Y78Q5hihMlpLApY8vq8he+UZpP4w/LZJ+Klb9mvvrr/tLf4W/c+JK+uYN+LMANE6hw3rxCswAtVuF36GvOENTWMyAU7nqA3oSKzhuOAxmMLmJcEmSUJODhj7PXRC3B7HXRTPfzdBZjjx+8S+Uc114s/vVTD4m+UyoS8rYrA2UsDWS8yVgK9LL8DvAMA343T7qJz0ZlHrv9BymphXgwaP4/rVshjW3ATa4m89eSMsovW365JOObe0eySctThBNUHP+dufrsW6LybjXia82rng9ZuMQNhsDf04BcPRKoyFHcbmFJwahl4qBXsXmB9jOqM7upQPDAI+Dd2yEjB9VM0hXKxk8cCXLYDXMTue8FhFjO2FHLa3IqqvQ+qT4Hyu+AavTR8iJ36KT4UPsISWmfZo9bV0WD9gEdwFHuhKDhhbo2sni8adqu+Xv5YkckzLHe7rHuRn9arCchWqMLgQUXXTKCZampqrudkLmaQzy4td/w2KO5Vg76bbXn+lv/IGDxXbccLRf3rzjNE3LFfupkatB9dmM9ubzr9bbjrhl7NEdZ3jGCUnf9KYA4C5v27Hvc4gCEIOH76EaM1q636fQPtZ3r9+6FOxw/mUZ3cduywpoUdK1xRcoIVc3WD/ReLNLitFqPpiWT6+kvPDMT3wcLAtPGZzsNlmzSnN497ucFf3AT8OHj4Rp+Oe+D+ey0FlM2QD5kIAULhzOTHfPWLtQj/e7nqXPWLHnQ6RZyg/knAb0V4et2cApTtnvK8CC3LZz6jLA3bmRPPqTkpo9nHRTkoz1oI8IWRuQgcrzR8Okr76cpE95lXJ7xXnr9xgKSq7rOwpIWei9M6sm/4AKgUIcWy4laVl6uN+d3f5CDfR0O5lx7RxnCuK0PD8yhMGDdENARSg348Ty0uWb6mnFrL/7bLzAPX+V5987ynq3EwWysHYfcGgdNmituRhT/koat56NxTYgUFZCjQlypQZj2DDq7carqiv1f1p6A3Avr/+JDXLzxgfL1E15kdSHtthoRK3/O2O7iccfT0cYPJvneWZWAl+HS+CE9LnODNHy3NzEvt/iA/poBO7CPn0Y2Ldt3duN91o+M/+sKFrP4RoEn67YQA/hSqyJh0E8TZAN8vpecpgJz0fT2cGbTdAtvt/NK8N2JaOtJcP8G+LtnfNG54XVI4xTiDABEHYDzp624MuDM/Xg40YzdCjhe4ZZpDHDci9tl+St6zEfe2ALoz/2lfkWm9wbB0pcxpcHk5SQtYmO1dWnZN+doiif/ApfhrE/mMudpW/gJjofWOfkbxP+0tirxzYLMOLT+aF3Fr0FY8vI1cYvSuFETbgJfLrfk/EXj2L72qzArXSraqGGZ8+f1JakQAI+CtDf5+NECTTzKSXBAcEgiOQLjvRfwvNbL5IiHgQFvOdQ2DU8gvYUAXps5KaztGrdOK0vdPjRyy+JPh+XLxu2eONbOj3Kyt9EqztXkQL7E78pWyxrebZkpnh23AI2XmVN4XcAcz8kwikg6QyzDwWPTT/XD/PhWoZulc8pLFBVj54HX7sRb3sKUzC7JQV+6rxl1BjW0OqFCXJZE1dS49NIGVfD0fXHor/oIBRz2BOPg1K7iIIkhydLCD5DfxIJEz0CGDmwJ2oyXBYqNUJB70mSM/HAaDGKlgUfz7mIaa0GgW2ghcjBRCc69FZAvZ1GE93iA+aXAnZsAIqHMiDu2fqVP1zfY97J71w69lNn2+SJpX9uvKdyGFJGGrB9+/TMSDXGfoESZ19xlGYV2EREgZ7mzLo9VyhTArSYcjGrZ5I7fCrhxBdt8yj6wAw7Cj+wZ/6oNLQuJ1LwsQjRcRYm42uQmMMto5mBfNL+XvIE/Qtyu5PhVMw/bLqyLOSwHrlLvjco+dw4VvrwUrmYffUvhcZP/YW5K0vNAxBKo3dj36/s7dOXndia7T1nFtbx9kVwp8RvGs2a2/Q/QnYstXt9kuTeTV/H4LWbICudXtBoctg4FV8FpWcG1DJOX/EfXl8VC97/vPOTPDGNvYhTL2NST7OmTLvi/ZplKKRErJeqIkVNqQbG+EZCslW0wR2izZKaYSQtmz85uZ+vy+r5c/8no1zjnPnOd6rvu+r/u697vA9juaUhDFy3+JxikivkxFyxEU7IF3DojYePnZHjTLAM8DEKkQ2B6AsNb/fkI3HBZsKkF8hPmrj/Dkg0pgH1QJTrk0H/ib7ox1afq48pOZGxkVoe5zouhJafBWBvTYghgH2t+j5SyIGJI+NNdEx+hVEbjiJyqZ7+4rx31HP0bHhNd4oouw4LrM+gacHUcJ/Dj/sDBHe0Pi/MHnb+/lbtNDlAtaxASsf97urKiPo/o721yvLrbwFx3GWGZWwuNxPEqachRKEpLSL6xHeygRGsNGXpSutAqE91qmPmg3Hn2aVqQQauqbU9YN7Q3pxZ7wYHuwys+ZqMwgVAi1LPJDa251p6L09iSowQmHu7HSLmbFAsi3BI9hwGRCu+VRwaOpuNZ70tBPbnDdc8oppj1L97hQRf6DBahtQuGgDepu2miiKllALKdroXB/wI4pJ2Lq4yjH4hG1eECONMqg4WkcICEU6MPNdhTdrMOj1TctfF5aiJ6mliZ+uqC4qmgNJP1334E7NU8ojOiq2po/goqcyZy1q8lgzP/OXpTfrfoxNCwXk7y4U1JyWWpt9C524RoTmX+dVu5Y7eP33YEVPA9VHX+XsE09sA1xlLyVYOvHo9H49+VaZabBZIdj4IDjzQJZguKm//fhuawTRwm8z4vK2y9VtiWvlO15xJ4PIi4hVtCAG3iyj5lQ4wZiaF2OEfsFB9ZiFTU+vl8Hq70NaaGtDxnILdo/UMkuc3ZG7CjzzCp43H7ROgyTXTluLjJx7zBxjMqzPZejyMb03UZuapQvl7j4PUz+B5+Dk81Dadvd0WXDfHdEzB7xQtZOW/bHMizZw3Z3nkXKA85yQXhjoMHQ/acluwzqjCbZdDffqbZTul8+IYohdoWhzG37zz71IOLojcQUq8eDjJFBKjzb0+TU/PYXHd2rtxeqFNE5iWH8tvoROS6e0Jn0nPCdzerXUlRSw4UEKcDEFDAgAIJVgWceQ44pK+3+FPGgwb7v6ENscttScWptxBd+Yk8/x/OjxNlAmRMYgnNhdi5wlMt4GrmnwKESfUYoZb/v7qcOd9vNPYqZkRTjMmlaxjBt58ax7ZGRHcVZWPqJTF/uq5PGo7d+3u03jr1P6yhQbiHXcCktEKFzyA8hUg0DyfNutfQhHQlozvMfNZ1DUxQZ6d6n8MgDOHbdf7ihfk4p554Acma9vf5N0Flj91H783+GiyGzKoYc9gIGwqAAwEkaByWIkvGvm0R1/lx4u2ujlAeSdEGneXAQ/qO7oaKRkSCKtj/QckF11gggZXYoYhkDQcUPWPdjOkXByGCqIDrHlEb5R/lZsuf+mF4lX5v9wYVDNDA5gdIg04HGlNCFtKDvdVc3uKDUcKayRYkQ3ITcFmS4q8SCBYh/WdyD2VgQsXp8Kw/u1KZ/rr6SmWAk7G5jDdo4wGk8eEULBJBAEd1RBxQ+xRPM1ILcaZhiwgdqFJDOSRjfjyGWMVOOmBAzFc5coR0wyLbgbIwn0DcCn6s9lCPXVNrhgRmY0I/BC3iCsVrQFgK1XfIEigQgZ418oy0fIRSe43uDoqNR7OHFyHzQHqnojiMG45ASafBuQ3/hNSNSCieXwPgS2NYRlILLeQ7A6wjJtLv5IeiRR77aa4Y9+HjrY2GqKlDDEMxohn6laEpdJCizAby0JSEK4MDs0j9EpALkZRQP4stEPs5K5LYAYXv5k9BEz50nDAR6MTGBP/CYn5/0yHn/J+UXN2YPUfLQiCSGKDPSbD1/40hu4FQcstv3jXYHs7Ib29R6UfZuBweNPqGEG75VkvPB3UCMLy6R1nO2mQ+TBcv7I2KJQCEPjH10tUJ9QIvIwsnfLwS2rMU1JA5J25TYjeDlA1+O6AwD2UKd8Z/9W4tl/vMH0MheqCjVP0BIyY1m6otNAZvMcgy0MPUU+r/7yqu3br32bXA5jdszvPcUjiANKz0EjrsyjMTcJu4izqBgtU21/Z5CjcDR2X2pVpYNDgin4mAVZ4ppfJa+t/Bi6fKZGwO52dVmmX4Pxh4l/hdFdoUYzY+hnPfpmyhzmq7hpM4QaZdSlmtZH5JaGpQDH+qYQFDPx2cSy2VjDWi5T0xb9XCBGyQtR1nwlwRnsBO1gDCxKAfx77lnjQlML0GszE/eX2STUoAzrA9F9j3lkg28Iyl0RD/71G0JPshv9LLE4OHgAvTiCN1yO+zK7Kvr/anuIw7fL/9lfVp/erZt0dWuXf4YclZWmFU3rPnlRQcPQ9PN3OM3NYWKuZEg3ayvpSoR98sMci5UmAZbxj4bVrxpMKSUckJgJ3AZnVq+9ScqOnF2+D8svXUcRpgXTsO2pUgkDsn1JFhIsEyjCZKRx1wH7qKNdGXLOZEgbijUBFLiu7yFeet054h+gKJLz4RjsGjBO/uSWbLJOzoNF4NNIq9zk+8Fjmvbgxl70EONcucX2kIcgAoHuGiLUrqO6oNe9vDzG95j4jzJcGxnR9Qld9/o/yIlG07gjfqbjQnKm936gCbtPSm8HDPKBynbLKaOd/hy/tc4OrZ58jHgpF1tf/Ll3dDIH7Ta0pmgKyufH784B90lysqStWEl1a3p9JgZJqXWftnCx/JAMYJ5m3O4C0N71HqN3DL+3RlGz90EsRrbHvdXkvyIhXIraXWaJ1KNw1kiFD8LMhH3/BHbD2ELQqIyGDSEm96pEG8M44CwBkv8lGpRxN29BhWcUZKgJPV73yShcCmwRWCgAKS1fjK1StnxO0efdjLPwSC6fZ+JKEeZXwpni9K58vDirS9Oi3W3gJEp0PoRyWWRS5A1JvS1v3ga6YLb+o0WN+8x2BKnNSa6uvLo21KZHZFutbeapRTovZDVsUePNagcJltD+EIQdAAiULdoHEykspehjW2kzmKb90lj7NGuy6KIXDfYx8YPgDTwFpgv16WFJzPc4DwOqHODYBzxhDT1V8QBj6TeQNE3jDYNUcYY9aIbt42heGsoOQ4mU2HVcGlzEh2AuMM3E/tFFsZViNfG0eFWwLkFMqXTXC00WYbm5FmbuhdDzIAJrBjCTVSVKjjFg/jwIBRuRG4NfG6FqCH3t3ENCr3ZIRC7/fmzSXoCNNvLj7iMdlzU2X2/RH0D+bB2Pl2NrNerRj7C0jko2pAVcq+x2VoONEVrpp8kGyeShCO8U+DQMtbkAEHS51Mo8W4G1z0T7RugIo/9BBUVKepm8GDj7jcXtsU+Z1+hrgs78WRdh3+PEuOcq3PFWd6I+J0u1403285M4Jhy8vKVdDh+/ngEiPtxhu/uGbIKu2vkzvb4zhc0oPErwL4Df7EHEVc8upnwOPJbbqCMA0+cQK4TOO0F4S2AK+Pq6nEM6fHIfwrk6+LCbwTX3ORG2jJHLvnUee1jD09aX1VuVbWGhpiUZub/0I05ZuQ2GaDV7cNEqTrtdrcz3WYpRh9005Im9kJBOKCaAQbZUAnu3ai3GIoxsn0ARyq8o6jKRFxCI8JcQJ/5nwPVxec3uckneZCKdNiVE/nFTQyWAZpKmLkzf2fGzCbMq+XfmREBR7wX//ixamybhAdGDv+xrrXQHQ3fX9pXAIIv25yuF/6itZtkHJYp2lHV5MN9bteR8uRwr7L9LNJJ429L1lePikca7EYd/IObuHNzKk7+6k6F1X0ZLbrVQg0P8tBBAUs+iSW/FG2XBrXTpQN9RdyI0U9LHAk7p6bnhyH097sVfhZxHbl9I1veMOySp/POWYHvetXJepZgRmyPsK+bySxqwcQw98JxGV5aXCHFzs+eF0nkoPC8/ezMQQoyRj5Jwz4u6DZGXkiNqd3cHPE0R+4wkzOt16+avWSlNGlOff/oVrZkC9FoEjLqBGxRQV4wvqGMdS4IpmQAemdjaJIpK/mFnFZ5CYR3QSULUGPtXnCZuR9YOm/Ls0cA3m6A4suGivtb+UQStnauvy8CW1cWf9HnInzGASqBsARe9ihyGQMiqAq6/n5WvhZU9wD+N69o5SRAExsADKgugil1YD2Oti2pZ2bnlTrQ8uLg7JAWFCwjQFIVIrtzPMhtvJdLfldA49kIDCLsabToyX/g3P//hMltoz9u6miCK4yn53iInaHci3xokvs+4kHKI2rkcGXm0F22g6OO7ypjei3YIssPeGRFFhpu4jtq7JEupooHlbBKXMTzaIWmaxOz0fNKOb/uZDdY3pTk3j8cKvRL+6baB8djA4f2ZaRdjnjl7QRWf6OIyxt2NIfHOQmOEONvGoHVvsK/75uvls5nOjhaI5p3Cwd6i8/X/jyJHOUjdjptNo07NkXfiGOWI92IurgV6Ewur7b04QFVOCRN6OoeBhVl4U+op0cbvNabxuf5GRX7pw3kRmlsj/y8757gbcT4gDC2/xOjtRXIsgOb53SUFyctToVpMJNOhe1/v2YJekYy2ImS6JJhdOseLXcVEhPZjN1MeO/L1be9UJouhLcBR+6AzzoQrb8JNIjWq49eLYreC1fYoWyP3NHZw3DX//Ntr1fs0b/NWhe9wh6DZvf4dL0fL+5YX5s48X58zjJh8w0L4bKnMaISt1q7NonCy6G4dWUJRzUMQmpqVqUrdkmlxWgbzF9tfXyVGaZ1/6nwntmp1weXodBfL/fJ2Xqm2m3nv1yP6/XntgKnvzo7AKN9bnamp885jLpwI2WcQEEGvNx52hLt8/ksJEUWdccnIYASCP2tznoZgCwR9BgW+qHnIvH1TjqDK9agXAL3AObv0OXUVYIPPaM5T9kCRO7L7IXRaB12AoiWAMLCjDqemBYNWvdfx+8hfRBzjSAsB3vcbT5DqXBqbB/sKxzLLmQ/yP1SVCFW0I+tVt47gIMwLIA8ZTYFuY+g+OApBcD9zx24IuRTFOVrlufb8bLJUbYFHjSym5aLqjagMlha4X0MCzRhovAJlGMAw/kr7d0iU8OPXFabX+5izOC54q7SovvfBdWkIf8zApKkhp3BRfpI05mvyjPXsqROourR0OpV1xe9GHIZMyIYgIwNgQN5rMkegqRzAcg5kuBfw409t0jzDYt/NiSEEeaR9/3PoRto5MI6jXo3cyAULBA2A799n1SyKg2qEcXOLKWDkmeQyTBD9QEOWkmEcu+VkAzykm+pUhWEy1F8cF7538qLJCJudMiCH2/PFTOQQssd0DkzWFVQ9Lf3tDofnvOVImMgirKWGSb0vwYW9vRym4SprUetCsVn7txO4tEfZZzofEC9Czx4QiOTBAsA027VO1+WzPitfl/268ht1v4Li0vCleO/J/RecAZydeyLEAfzKUsJjSXB09NPPt5UPZwmhbvj3+Qez529ANlCehtAOkrdY/fiLxTUKbPlaMYyvabfx4h8pY0sexRbhQlsWKqDIY3+3Cg5DqXT3HnmfnXXKq9wNBcxIXcdEbZJz48CYxSFqY03fUul+cD6INj2Qz8zBCTaXIB+PGJiN/otgN/AMwHc1ZZOAMlioIcAZrCIkBnwIqHFDSy9Dmdz2JbmFWn/TWQZ8KZAwmhKPfrrfW4wyA0s9NN/9CKnHWjMLJiEoxyzRuhwhkRWL+Oir9AEPyz0cRyenxYV9UCh36mIR+xkR7RZGVZpQJTskwhqzjNZrtcWCelIdXFuDSeCj8ZMgfMQto+R4CqMbEgU6utI/J3EJAmy98OH3PZMPH+s3HdLxDVREnDeCYbHhF4ePb+RjVrde1KK/Plbyh5OkG4/xhcMs2QBtA0tju1Rgafoz7sGD+93J+NWm6l4MwfFtzMimexAPQKQMCxStILyXRVS7ejz3z+mM0Ar0nEzCeHFEe0IKgYUIRZV03QtrKsM/kfTcujPSL43C9IWeUuVL/uZKobPwT7T6BOR/FN7D2l60ADlBk7gZsqTRFFy3KDiJYWMeNB5l78IFX7WKqeYI1/BPCDxPZrSpw3nZAYxwGRV+Mbl/74Xxbr1cFQ59vaKHdkw6+KwecsGK70VX87rvu//hiCw32cCnWaB1g7xespvKt+XgqDcopzfE+ipoWdwxRcM8VizkMiYtyOUI7owOqHLAr0U5K2Xbf5RfMn6xUfj2kdqNW3HjEQWPVrU0TovjdpHe3sD5bN9+qxw34vTRqLtto1ASeJrIxBDq0OBQXHQnhr/73DJcKCRQzN6gqvCAUwa4pAQqKHRKqy/Dxv/vvf4Pa42ccNoff8t8mLivGChPr1t9zSGtLONrLzZ1HeF9lPXt90Hs2npKa7tDawVKOf7+JCw8Po4UC0ABPpXq+bpKSgihMBzzP8IJfCsZiDLARUZkN/UuBYh6+LKAURC1uXkoiRdYI3Z3TT2QrnDsVzZDV4srtVPwpb70tTI9c9csnfl1pQZdnzT6T+JOKqZ1TIThhm8LwrNfQhW6VbqBoM2KG460byQmqwdDeAgzuUC8fznlXtuXt9OB0bGYKHGmOMHWvrgBrjbuZsaWpmO7oLJUlJglp+R5d/Q10OGOQehGgUQrAeefUWRuJFbIN8chMbHtUjb3JaGbbEmrbUI8bYkeHipmVupfohplRVNlIdI9DIRncbP+mGj9xkwQhrMEPY6N2IHTDCGMkDlyupUZdMhiJ8nnjPp5I0XtnRBmUaAUaAWo8IQOnT2AtgJLW/bJqula8N4ATTZDZq4a+D5zHRazaMWWLp5bIslLkCE1l3KViR1nYmT7sjzMhDsCeULLp7tbRRg1PkoZ76EgCTnqy9XqEjCMswQTzN9qZDdSZG7AGNIItf5ZjNJucFOP68G+YmdYntXfLrRaDP8coMcUH7THE4sf7zgzwcZ0rAwdHf4gK42CPUe891d51y/fPRIKWB5lSBdpACOUGPiwJ29GDJ6VRWesxZG9vIQX1bJsD8GQQ7Q5H/Up8/eMTQFq782RpXZXi/Ob4cvS2OIehiyHqbzIhxUOXzhBfU/6r9gnepvspH2s7cvgJ6uN3TJUYzRlHIZ8BRNPIVD1GiVSRq3SfkeZwAQ70MQPBq5c3FiAnOZooLOcUKRZTFKB1k0MTl63tgYZ8AiQqoqb717yRl4WWBtqcAZ6PV1PnGF0SOx89fLHVuQA+rNodCW9f77Ive5jZjmzvPbjFlHZ49d0pWMesCz7fOlUCIG/3a/B84WADQLhQ+bQ4Sqqfuul6G6ACRTNwURQ/GkAT6VyX+ThCq6QSyJ5nlQyDpZyhPxea1hZjOK8nzlvBzhk2NIOdypUj+xB390/rcLpNZlNWtj7ljIiEYKRIn9aqtdouTxH115ePFJO6xERmsYcrSm8T6mc1k9Mqap6ol6jAFufOm9kCRdKaAMPWw4zlaW+5YDe9oahUatDnzMGG7//fWgX+Iof4PZOXevPwxZKK0zP08KRn30/yyiZTLcoyj6jilZqz/Y7A0jJQRNMmUeq//2oul88G3DXwZPzf/z5fkhb6w8pmpinON8HkubVz6dAKtddjh6sOP0VqOq7NyvWE+k8PSSzssUnJSt3BFllptPQxR/8kS6zbVcDN1d+E0gp+Hr8doxozBPMfXNFw5Mlz3ETO+HeKQAy3y6FsmbxMgKk7zL18YQkBEHSg5B/v4jm1kpS1eXo3wTwFUQeHZma69fttATPc2mEVaDNhOM5Jv27vL1rWc/75xlgeJTwFwBTKTlECmCTIBn8XxYOti8Ygtm6+5cYPcsZo46+77FtTBxVbRfAXyzV1TarDNn2LLiYw2eXFjNXpP44fJFVy9lVl0XXV4GhRZId3JcTtOckr82FqonVD7O0lP3NGUg3ORWPUy0pe2+Lb+GLT72rUQq4wla+vKFLVrnynCtsVv5om769fVGEVDAqjSkRvb4XpABHp2aPyhFnsAiv3CkPHD3qZkVyJUAR5gQ17828qCR82z0CQHKa1zarAlRduDnGTJKt/rRGXIezxnhOwk3Digd21pzPIRzZebef/hiEwnR/whPqvtCFZkY4nleIq3sBBpcncjyyFHFKDnGstTKFQNiSX+dXTSBrzokYZRbjBA1iQpacAOJ6YzOOw2MoRNofgqdknuQTXCU7+qtZUq7ISwrDTwQARphS0YxkR2gkYPb/x1Gc2dw2Go85uiMDt2EaLi/GWJcf5aXHCk051QARS3/NZHOAaBcHLg7k/3RlG4coJfPR/hVqjJieNGkYY02ng+X2CJSwN0+nT5UkNvFrym1ja4b3yyIjctxIP5RjKDX2RCYn4mongg7vmMIiR9/tJy8XQAiPjMl8y6HE+inQNr2u+V2CLs2YQ/UkpAEO4LpD2HbJOQ2Z43E+f/2j78K7kYjFkbjg0yEtJWzESrZjYLQmV4xszW+jJuAjOOnbtB7hSgdLLkGS3TtAx9EThZD0lynxnkibzcMHZg4R7Tn8LSfcZrwtgPr1jLAbp1gGrK99R8DMV0VZJZDZg0MxGh9GmdrLAbUNzQD4DG/WO9+j2Rtuw+CvFk2YnGTL/JdNu+LfDi9WsNAaL+ccyobVeQIJ+c6r024gfxv3+/FQWs/XW3plDFN//d2AhrSSIUrMuE2rphpcs6jovI8AfJ3DRFbjhizdGCJQk9cQq4QJkXrlFmQXlztZjF6bHsjH02SwOB1IGwWkBdPaKSLUYgQ+PPZYhbddgNMXryhWY0LPA/ZVr4bYfyMGgYXPO9uCJ8zPCJ0X14yfRqbXXuihM3i9YFwxVYvOaAjofNBPq5RUudfn28/z1fd1Ngb+gtqUAUOURkCP2IDkHIJMJ24FIac04+tfaj9Klitqw2iwmiXiK4cUVLz0naQajJMMzE5BEFyZP8voy+hLe9Z/ayrHRzIQfdyuXMKekB2DGvX03Qpnz1TNjhvZiawYONLUTc6Ry4Aef42aOBij0iAUPuBVg7kmCfhowNHodPL5zbtKOakllu1mg/3BSRb3iMp57Zxd7tlk6xO5jD8T3GOp8kzjeXAi6DH/p8vgYrKoZddVpHC7rCSjtB8E5p0AFo9gMtx3PzyEbBZQ8g/rWbN9s1qexzFDUOKBeIWoJkTeQgHHYFJN0TSVOtHbAvBu5Re1slObmpoTq+nG3FQQngQX3/gR+TznRa3iwn/eDzcG0UQpsJEAC6nealsNpnmhyAI64ivB0wflbr4gvCgLjdav1M3IMi/G+Q9YJA+LUCYFWIUEjBG/oU/jqBoz4GXnnFfx7iRsIOfp7nkIyi8qsa5nYyLykV33y19OfW/4AfgyOWjWRKYCmeV3MhoHCbtgCMT8UnlhcJDizeskZBLOgxLRUYzZ61QHqemf0R0j2koZ72OOwdmkwC5pw6V9HLIsybjoLd1ZAhMDRTB6mmY9IISwEB4FmuFyNdPeP1WgcM6GTTitP0f3LHEEVIEgDFgmLKjhsUmWk38RlR64u8w2dZu0wETaTUeW+AVEVPKlZzJd7k9pHgsihKnfr9bThUcvsmBdxMGaxXs5NiLr3wkoEqfm5ZTQPLGkQR0YJZd2srHCb4NurkhmqIltWlszOHti14ikBebFLEiLTyPDatmH3SYOnHvs96ww70amvjvL6WygudQKwuqMshZPKDRM7DKGXMmDrNyrBaM5IBTHIgxH0gVJS6v3V7sRxM2GHoU4KpyC47DPWhiCTvknh3z4hwmhxmA7YYjTQm/X8DKv3eez/7ihQg2YEAOZLqA0z5Vs5ZIYX1DX/nV7BMxSs78A4iwBzl6Y3/VEfRY35wPDwgYO1oHYi7gPkTee8nFovVgH6GQqPOFly6vLGQi2APhBl2vMqXvx0uNPLfF1HJPXgvPGPGQmMsF+V37gnCTvpZQWmHHe9WF1eAkbnE3Ex97cJmbPpmI/StmfmCgYXn7WuJkowmEdYaU9F3PQZ3fBRD5fnc573YTfdWoMDnKnxLkszNpCBxUT/9k/mG0T8P5MfxRs7BNMvfC8YPZYmGBwqRjLBVHhPBiwxL0tqFGQ3yy8moE0v+1w7xNeSsrmoRSMxWucKhe4ya69FeXMSCSYgQtKrNZcBN7r8C6W/ok1n7z2WPBu6sjibv+8J0+9M3sWOyhMVtuELl55e9QL5fmkapV52tCWPLG24E8RL1sdi+E5ZMEe19Xtj3SlvvGSekOimDHEi+mhwhDHRab84sOOCJ9X7i73HU/RZ7weKAKWMyD2GJsJQd5wylhcq1q1WGBV24zTW5ZaUILeIG6ItSpGbcPzzy+o9P2Buemze0pJUQ/VyMvaDDTd+LvnPkSQ5J/Z8yDPVeDomWvkLoZc5JR3w4tt3M7WF/sEFFo3WnnRB5Ure/Ni77/nGfOiuuyTqhZpF6ge9Sj2extTRkI4aE5j4AHoq/WmMAvTcbBs7qTpwrXnKyRaI0fxk45XCKw3PUdr8KiAxFj1artiAUrkov/PvLXUx54fLrJRRN3V9Cy5hQrmCAsCaKVUWq5xJ7vHAd++kx+GH8VL3SUFfjDyfpWV+UAre4FhPZiIx5xkS7Gwnc+tKZfR444gcthP1Wh+Cfl3xGLg0Tz2o79X5LsvE7PT/tz7OFzqdfU6g59Hu9VDGaOGO9MoV1ve+lm0NRsHBhK4vEfm0nwXCE3sPIM/GkNzMHNYmzQatHSUjy426ssGKNUNfC4o3Ahhgd6tL5WN0BO4CJsqNZHVYWwUv9JemaNvJQAr2umifuENCC4nw3sSUbKI6NXzNd3wnbmdYSiyyd2rG/Vl4XKoIf7TAdrXR6Cp72BqnBuWkmbSN9kK/VAbc8ZrjLyFz9Xo4AE82CNaWU1UWjzpwAyIxT+3/6hxwDtjW5rYCCsihL9+oB8Sv9OJ8NqwmOD/Vf4G7X6UBRRvyIuIWaGqV7+0TIpDaNoK1Bg4/+AYAx6LsT+s0S/Bjb9+ciTY++zZ+DQ7dyBo0WB8bIJbY21RZPWssYcCaDZHJzQWPx0NEcLW1vEg9T7PxptnJ25BdPBpXW2nY014tDF20/WKz0DX0+hasST62QXOlTBkhCkcYK/c1EGiPXZ4FqcIsE/wRRZKWfUDhTwvNzDwB3A5q2nw3Kif3DRJ8p1WGhBfz2m2f67tJ+xzfHPgVOnHjTN2Qh/3N4faau6bT8l/6+fG1rlZ6qw61RwW65yp0IVB1IlATZwjFkpi/kpi7dUIKWkos19xXomYmeStA05qIeQ/gGQDK96wkHBVF6IvnmiynVokz9b3xOd5n0om6hw9/XllXgaRHgbgpTf2wTgUpf6zjP6XMMTi5nOn+cNcI8KNmV4C7dd368r75p8/strzDV1TgwysyxhQm6EUo/rjhNgRgVYr/9M0Zkm5y+95eAVO3ytFJIe3Z3ZnxsqRldWtlcWjyYN9lQ0rDg/JQBf19Hyq0Rc2XxZiZ0VB6kzZOO7nFO/FxxW2EfW/CMRECnshlnNox6DEZd2Q/ElCcAixXsmH0wtMypDk7oQla80m/Vp4WlyAm9OEi+O7HVUGI/YfEIt4EA4hlgPB/nLgi45sCCCoewyJN7AJx+rvqVViF1y5sCqb2HeniYbQ6QDheJ4A1r8koAj+q/uLr3Mj6f1p5xS+fVYBkRLAh/a5KVkeSj+AZpwCCakAHpd9KkPpApGy4ARXWdDKgRtl3BkbxMoXdRYoYHh+JWOTAlp8T+rMb+D0wQYjXzX7Gj5sRfSoAha3YshaGEQARzQxhDZHIGXCTUSp3IKIv0SgKZWiZBzc1UG0sDlMGbFvyOfHzrGOnpupFDz9Y9lv5lp0Yssk3bXKMdelmv/WOTttVRvn781YgCyguCxcSXCk9amU9+fS5LzuAgBv5P3QnT3hzJh3T3NoS5OX0SBobt77G4hw/GMJ9YvmYy+b5Xsgo9MozqHMeVG2r/1locEG/FIjz8GEcdw2WcudrRZTx5Dk3ZUiMHO5LES5NFhjFAGTLnR4b2jr08fGitUDDqDt2YNq2dcQL6ELMInZAzgXYapo2poIhXvj2UcnVi7xjR3BZxLAHjBmOTZSEVJPVyink67k9RNlw/Z/Izy9GIoHe9HicC54j7Mvx0jJPeG44PiQXBCFCwm6PHAti+SG/LBM9Hk4hYb+/Ev7WKhwbic2dzrHYfyO1TjPKbN46dmm7iBDU1JehoPYmj7cm5v/lXBhDe8Pmddlta/R82Eu/t2j/j2N+cXjrLdUjTpY9oSPwTx6+lzgPSVbPexZ0eSv/+nlXVPlDhbYko9rayV2AbQJ7KbXqiCFlz13KUHXUB6qYGZWJ972H6PjT4v/ekFd/2U4p1zkQB755bC2SiOzsMR0S7mNx+IAjsOxBJfW+QLmclHp9jqjzrBxIfiFd1owlUbvX10LsAF5tE59OqZesxX3F8JJVsHniaKq8IiSd1gesLB8+tNNOEijK9/0fBPte8QXiB2zOgeTPLH6BSLj6O1rUC8w33VHGul2xYGOlb0RJlGDEiTSPpznem9MnE8Lg6xWH8bfrBbN01wy8KWrXjexs9d+PpiQJ7yn1112+NpXtzgF7PSON5nZIRlYG/4TUnmM0/dwHclwnPMcdppkhRtECZMzm4kJIq27r+6s97Bfb1o85tA6ZEuMG7TnHaiDCKMZ4iQen/u2Xjg9YrvWs5TSME41vM5T+6MxIn1MU9i1BOeuf2XRSZFm+xEDwegfd5jS3nmXEfMDvElzh9vV6yUUJ5Ji51KCPpbUtF2WRzWx268oEYGTFDocf4ybkzDabuIadfS6DzrT+xoxOw51DLwk5s8V+OxoPiqJFrQGGlgoEknAa0i7Cth7m2Pri4DStNlL/if2bCfsawvBWkKwAEd6kIrcSr0YoL0jVjd0fwPjJG3fJAyTbeN0EYMXsQgmXByBkxmxSAZcNAPNeI52vPrxNv540AtKDsr5Y5D7rohVQxEAVVQX8lAqBZA5EKgpErgyIesKoB5T6BbC5DZQ6pqyGGl/obG8Ksbm9NbrYtBMy/VrGvPzTJkHDh/ZsKl66aZ03N2uc/uZvaFKHs27yMuS+rl4FwVAxLhpHfx4zcJ44VoAlhZrv59jdRdQJR4Pc1u6oRSwoGW4eolfPKRzlyb5N+Pow89FLX918mlcRcU8fhKgtyr0JfXpc7PWQMlwxl/mBy82trOCRSsa48bT7f82MtLyS6CQnNAkrvftaMCfCp88wd/X+vRTkwHLF8b5cIMjmlw52UGEM47fjKxEv/X8Ve/JTKh62sm1divSABdnOAJmlJ18G8ugcxcC7Lm4SA7NZP54iv3XcSPdwl/mbj6mGDem3xwn50J4xxE7yglUiP1CAiafGzTvouJfDqG0I39tsbffM5w7NKVOJBvPbMpbH7w0Z4aBmKdzLgC+FUVGm1AAGR1BvzmlW1PdA4GgnS2XvZyRnRC2eYcWVkMaS6Ny15P8w+/8S5c1MQQLp9ePVt4uiENdHT7HfrRs2jzuql31Quea1YhnrZE+XhfniiAGPTZNNQKIJMFKXoeaVYcTQATTHMNCSMug91jb/SjrY1eLN/D23T11LthcuSGL8ge2009ly0Rb39UhCqETTxTdzNzs/9biJkXG12qC5gO/VDpz2SojldPvPXK8QPi6CNxI8KF7bfTVstGtmG5Ye0Y8i4icDOxSj7rtYYbkYOh3Vpzkd0cFzOQ+sa7lRHRFH+xMhK5K3Hnd+Nz+jgB2mKnAP43ZH7oOpp4s+4tmoDxhNV53z765VDOjZr6ZqV4/+qkQHhfqhTDHWXpnxfDfJ3ICjA5C+QXcNgumRBv8iCevpj/34Elvn129D0vLV/elgDnlETHXXACHYHFgAp4eQD9GLA0PoeV4uzKf2V/xJox8SemQNMolZKdvO/MmUMRJPW78gaXaTUW6jdxFYs4gWZp2lm6WxtDCMNQCngQkj26O9Sz21Wx+PRsVKipDOjgRBq1oZTbTJFbX9FYQL54QC/AZfnt8WFC4I4nc8R4ux+cQ/0iXIyzf1w4EqXBTGAMdP7agKV4FlrgwsVW2bsUZw10d7+9spEZs8PlCT5Jw9x8yCAPxEndoJsP5/k3v21BE7Wj5Q07e7MhHeFsUbByEgoTICl9R95yIZ9XAqtmgx8QHqdf8vGqU4gV8rw+C9y9Y2w4wFOajuPZQTBTEluRITlZyEpd7UZuJWRWEnl6dqdUzVDHppUDceag9PQLT+NqnkHFHReqrgctsRbhwHD/w3hbLnjkIDaHPmTlAHh60LxiOvNL+yXsZHbdPYcK8d6Q1Ohed0lxeSof6XGCbXUCC6NtJgNfnvl4x8tx+EHz282GqOgsBTM1vYZ+LN3ti2IATmNmqhcjB9e2yP6zv1LYt1+HpWaoKpoe/aJNOOY/uq6luFpXVBKbV8k2fmw4Zz/MKSy8cwnUf/vWk32dMY8thwWqFiYc82s+zm9rOY62qChHI7OV6HuPICw9yCNjsESvJw6z/kAFantiwwUS0sHdeKnTb7bMpcguXirhLMsTi5B1oCNMWJs6GcfFGKXChdxSMoqskTifDsZuWFDZ7eKYAUBs0EkV+xEC2Pyz2BXmlbyov+11a+uSDMxShlVSSQBNUongAmeOEgfho/eyPJI7eeH10AASNouunoOKNG/Mc72SgGzR2E1rQPamNFoBO3SoJ0xLJKWAEh7qq4DmvyEPR3DRMtXNnEjUHWBkAWgZcfJFlJY9GA0J9M6CqwMegtBoVnKuicLq6mpH4nd9z2eMrRT2LmlwZV/+V7TTPSNJlRL9veJhE7nv0NcW6t9Ir+fGV4u6n/d67TAo9m9BJrBIKIxf4YOCz16KOA13/oYJe6vaNM5WRF144q5smn8WL+dtgP7n06MME6zgiviyc2cwJAOwtKdQ1tQZRyy6TTYDS+nRx25+KDuO5PyNHCIyaALLywwQtkt/7zfhlsbrrVsAypHxSJzaFMQiZZp4uZ9n860zU+WVsVre+JavxCqjo390/+e0fQvGP6IVqUGtEjQ3vWzMQdjQa3X88Kxnma+4ic8ldUdBGz/+8v+ilGf2YAYL1AtGPa+ByyR/YNYLkjYU8m70aFB0Tr1awqQjO0o79GNUCnvgBB7rCglHhHukrjlvtmmGnWU/gt8dVNOT3Tuo7ji1yQd5bgAjQeE3/FslfaB+ABv/ebgdp6FSimhqyJEWnMldOnUVMANt5k/km0aXfSc4cOubBxf2JByQakyNSmPTFgZn9MBTA/AuwdtUQ0EGESCbviFyIe7O3H6bz2Z9NREjLpAV9eCEzU5ujLwZ4/XM8JtmFiOuiusHHLOzsUTHDIMCbvBvljYEzx1YWchmQhbeaopBA5GsPkIJjsJkna3W0J0l2iFxPEaJE2mwOATFrwuYH9zo6E+b4KOrdh7CZtvDI2oSv87HGBF3PFYby63emuTVhrF2cSL8DqVTUgrL8JvNiT2/1h8ofLvT+cYxVsIhqveLS/ePDSv465dK0NcDgv3Aj6fhP74f8PyS07ziddt5tclqQsN+EBE+B8n70RLNICi9V+fXjpk/Co90l8zyfVO3+Vp12q6vquS1J7fTQxeTYRkMYcgNNE3Yl6BJJZcmYv3poR5gI7BS2UMCaLSgKc9y6EKaSSrvPz+xsRtyO7vwo11r7Xb8mYW9Yju/s1f0AESLbyNK/Sryqa+tSx4oTs0I7gcPxMPVN/OG28d432p2GfctbhvOtnzvTdaWBSdPBwiWIi5JCyP5QOcNb9sTjJXwU6Lovb13Tb0zf5EDnzXvK/sj9nOr2xJIDnvTxx1x90R+SNGFhrXq1iXlYWh19bozFJciZyui7m9yb+wHz+mZHtwL9FKPqb+HVENcg/mk8htTezTlngxibEfYkLJND0D2DagRP0It/vc+VFpcVyOex4EDdKFd22vYFlhzjgUkPdnn7B3+pbX9GL/nJ2soM6FTxfybY/+GjMW4OSq7b0MV8epgJGjApOscJh8tvPUH9Vkm96Ip+jrr9ClC0VzorNOoc5BnCUdyw3dO4xsSoK5jsJ8p2RUVxVZ7Q6R/bVmypdU1EsJBk+KPOlqNwnben0T+jiCKaHrj9iihfg9N8EANrYbOwaf4BksP/mbkRMZ/2HoHKV7zCQ/N7V95h0tLs3hncH06LzRRLS/XQtbj7ZtccdRfLZqDd8yv/Xd1XRJv6N9y0YKqShDT1gd+3jYTouCPxcy+ngunDoKrRQou8tYmJntsdTm/3x/mJv4UUWqKyRUjdGTp/YOdkfCDfxjPj5TQu9zaOBCbdaDTzdtm/YTCPaPSYO22+7g3MjIExw08TtV5rtkzq94NuhiwNBDCkqV4Vx//PM2FPD1T+uflwfaVOHvhZd28u4/RvdPWhp8g3f6tbkkomV0GJO6WsRkZavFly8n4uQUm1IjHzpGGQaJc8LMA5MjwX8WYNNRW+SFW6RLwWHS239IdeRxuH3ajdfCPiRyw4wICzLAZD/RHvY6YkrUMa5oX8rUdqw96xuwTjCZ4hArNGUKkjiD7tei2Z4dNCokD6Uz792hMxkB0dYETIKHP/Qqv6cDGZ3Dg39gvFyQhwRY5++CEHiUY1yUAXdmPR0oZQ48O9b5mUir/ncc2eFtKyd/4oStrx4QEs5RhdzK+4LDZuutEuuX/iWQn0Pw1/wNGVwfQJgvGarH/w1wp6IQbVClGg0LPNGDpH1FH6m9C4liv+v9Xy6D5GLvOb0n2Ht6q30+/QSlouO8Va9t4GXZi4/fi0CmUuAAq+AdjcxVzzooJ8W2G0UmBjNgen90lieDa7QZJx4L5n/HebJf2CM1qK77b0vd5LB0rN9VfuRWH/SKIycmoyc4gRfJaehM+ar2Keo2iY63Y93dh3n9mHdO1H3Zkjc/iEeJx46y8+yJA+Tl022vWT5F8SbAIeGOJhVgyXumO2ydzsyO1Y8FsHoIT6DCn3s1KWv0Lbwp8YsFdNcP9rARvdroUjDBejgcrYoM7FxbXsGT10ffRyvAhEdpzB3Agojlyvjkzsd+xxCA0QcgM/LYZHZfHfJmYjADv1ir2aU2qQtXqtkWL18biOOzEJJSFFWgvpSnmuimUft6aXac7gR9Nug/jU+FkExyZjd36+JXGFBirgMF74Aidwi7N5WHkn/OohjSGahNan+bxUJJszF8TIk0MCSMNb/JgEJPRnCm0RoTCLjkSW2YmpnPZ8wequakw8El3QpiNFsVqT8TK96/hzEK6ubmMcgBzJrye4C5FXMhW9wRi2QO/4SBG7Ua6RtcMBmI5/qPvLvBDIe3YeAET4qtd0BVmQah0DRhNowj/REbUnTG3G03WhsxjQYUYKDyBirjFRVmtOoniHmZoo0tln+1rWHvK03lyNBVSrA6vWklqvV+lJZ+YEKJV1Mldmdug/WRo1KYJ8Um4tnKYqBO95NGE7HlzPoyoZN6eaRRt4PZfJRv5vIah9ZEiRbNjqKgcII8FcTRjvdwTox5PWjNn37fPMno1pgVYNXVDAdiBUy4ZmUwD57qNkYvrAS791R5YBBcqBuWIYcls387qa75IT7ndnt1ImvKtFgZWT4YuQDO2AC6AYDBncRnGkddme6c0KJzxSxoUUZfpM2TDJj/Xm6gb+5wwJA+FFTtWxiT1bsnsxeFICaDJbx6AS2tI/9mEIw45ggFZcIQHofjTDijvP3wRzG1QdKooObBh59zrxLlS56t6I5YNIadK/H4lZC0ECR/DfGblqGuw0fz8gJu8V47hlSyDE0Qq1Ag1/pb5HRG2gx/EKmZixzfC6vI7JjoKWCQljrLx5/06y216csB1X+z/TEqEaPQNbUGT5+QzIZ9fl8qCK7v5YbKwJOgQN2jWy5OZcsOeesfbKhEZ2CD658YY0893W0Wy4rA9bft2cCEWHDHPq+KZ8WA3coosddTIIaEnrxzCPxffg7002PAefYnnsNl4SBWbWuTSER7GqoTmOl/ow8hOcbzipOzWkS3/D3uPVj+S3ena8NYUHFrhy76w4R1Br5KOclNWZBkiJAznjtEaSvDyEKkXo7RN8F0yjmzAox+CeBH0hz8hP0U7LyM3JdA3sWAYKKSAZisYvxeq0AMfUkCnPh7ZmbvACoAw2+QcVN33crPQDXBBtpDHqQc61FfXjfet8CBD9b/G4uwv4XzEw5JC00w3PNHFzKSMpAgb6K7C5TMqCy+ln3Jbpiugp29/YHb5BmiKLi++TrvnO7o4ohyqoa3mTiAqqYFV6fkQiPVH5ZsIErSpaygO6LXaHLCZgZtrjrkw82HYGeRqiBE032GxdPH2rBMaX2FTAmZYYnTeyOlet87dJkZ1PaIVxDYrGHI6URagyIAmIjGD6HrfbhwSeHt0WmjAnptI9IpWfJEABnvBXBxsm7FjKegtAlSRDTUy/tQDwvDKYurwT3JJIUqRqcMaflZFSuW27B8h3p7aU60iK14O8Nx0fE6GV+dK52Pva6zOHdVTmupfXBp0jev6KRHa070l3Y9dKmAmXHjb6ndU7OQq7nGxsCYzshler3Qm2A0Il8fSvQVITMS/QfRviJI6BAqRLDOepzeNER0uYK5acHO4UKF36+GJL5eu2vx5zjG39ZtsoVyriCF85CZOOIAxB/CqkHZMvC8D7nKGJaA8JPorenXr9ivvUuTqgweGrUNMc4bOZPYw8fztokmOdXAkKaFdaVE+t2eFB2p7IDtB0c4UsfHN+fdCZ129eQss+1Y1BbNA2PO85Ne4VSN0TgDqg57U6ztZNncy9jtpSMOkWvBO+OizjWwmcnVekz2gQ003em5BFexlENdekbq+1eVrs5ydhJhoumtPR5exzjmJIoeJrw5xHGcjleD229/bPPlxKLn+uchF/wNhV56L/KUIGq+asOT7EWBbEo7ng3I1Df5IpXkPCoAnXq1le4+EHpPlogmZySG89YcnNpa2+ieVuy91UND8Atb/K+s+gwvCWJbP3wdZDiDsj+KD2y2vhU/b8V2xn4y9uMtVByItbnVkKdKuNisJShsiB2v0KWSebSYmYvz2T46duYpo5w415pwhBu43RCv2lCcKFbn9aETjTaebmGSwHtqGi+5Vy8aq1D8V1oQ5Y2qxvLLFWPPmRi+vYa4DuGvPOrn2ZaNxdv5jEopN9KXXV4t51iLmMUvp9jF06A7nUzSiznLlQCWrkuowYLKmmYY5qQZxvfNxODwXAwX8xOTwXv1s+lSfdIBjYStD4qfI+WGeSH30uS5V8Fb68yN1Dn058KgfBw4+FZvNWB4dWLIpFwE3/bZVIBnVcFlkgkfTJSo18/Y5M2GvadGrKqc6H19svYj6VCj2qvmCV902TS1LCbMknHjQ1wt2mhVSNENORzOEMkPVK8WPkQAsYqkaGMJ4xlKuZ7hDlswHd8rAxM8rLZo2cXMn/caaC9o0O/W5P25dA+dugCXqz/8lq/nYBNxxoA602wKFUpruqogueZ9kdwTbh5aEZ3DZzy93HJzNwaP33ZTrdTNTCWGNP4wdG9yLoRsyN05624GIa5vbZZA08LUHalQST+thQYSKQP0V2uXixcAnmviVHiLNsT6O9mKY/RKwEL/iKwEcNNMnXxyLP5EOlg40O18POtc9n3X1hVO9MJYYvd+TihGb2aJo6yeycts5w6ta+3fL2AcWC13NXkr/na6AbGbPoM1SLpEZqWxZ0RY4R42pghTdDe1nVntWFpgwpDwGQgfGLOyVNrq7PU77pxVXvQQwJk5u8SqIkXdsuehutnNlYEEsAHFRhf0yUwJRQuFgUHr11BA4OiUQgLx9DjHsFm6df1XAmpx7/aYSHENLJPFh4/zRoCmy7Kxctgj4hiWOEkDmCnQCT5NHJyF0qUB1y7r6jBtbdF1+9L6DPgG8RFal7xnCqsw3LfiQQxyt39BFfuBIxzJHWl9jZjE+SBVkRKJyo7Q+EfWNSm/MHgOt0mBFJCp/aJUaN4SK5QyowLUXxQjzJkQWMQKd13NxIPOMN+tA3MrCQhUnIkTOG0c4a4sytycLIi8ldM3tDTkEJvPa307M1E29PurOsxqmNacOEx78E1BTzzyInsExFMDuNJW1m4JNFeJ5mgkFQj8WxW3AM0N8tXp2hx9RJxmkfYl70QPQ7ij8KVby1u1u4T0CdErKFs3mfR9e/SRKZoD46WLlz2ZQsjnEMix5ADjbg66HoO0hmCRzkx8zEQeNpegCM20sUMWSj3MQhtatL/i6o4lCEJ4H/4YL/L7/wh/clgKvOIDTAbzkmc1nxggX8yenyDJ6l/uAPKzidSnXX8mWBiSPnJs+rnCMlZuDFuF4C8hMDEPW9gPNMugaWjobnJFBgki4HGPQogAGZ2CSBbhtAY1BH8KGCLW0yoJD0o6F4cJjxbYaB3Bux6mx6f7+j8dOHbuuX9uv7ATn8JZqjrVojn/xB78Nalw3WrqYUyWtyNax3NQleCM9dheVJcjIIgzyaSFJEUw5cKgblyYYvtq0D5hKg3tg8jDRWYTvq2tj7umOe4A/fFv0Yr9y+B5h/KPNfFteFxVTxUjCdjJsy155WINZivinCduSzhiy9RCfa04QH6g89o1Rakc4yxeAdmMA73zftr7wWmjbALgOZulfMNg2B3tKJ01hknVfvZcd+LC9ubL2XfMQVL1z/9cm6UnflTBu+H2pJVfY3FDg7MqL1YbdUix/Z11fZTKQo7xQGMBQGR2GbD3MtVB2yy7X2yvROzL6BgjFULz4TsYxGy3rTRS3WTPOue9SyMlmsO9m0DS6YQK5/t55y/x3aDZQaDuMJurQqj9qPLGGNKcD9phZjleNxsCZbV8a8+vtGJbyD/UWTL+D78+/+hGThrsxcZXxPwag8X30wk6tPsVjZePLinBH00UsoL7WfSZEzagQL6h61wTx9+mvBXBU1p4ib3b9fdw5zeZ2zFPztxbeBJnKAR8mZCvMHV1dBGGrGMgc2Ceq4LAdimIvL6hKi1F5UAxzw8xe+NeLnEgKTKaymE2D7cqijg")
                .append("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")
                .toString();
    }
}
