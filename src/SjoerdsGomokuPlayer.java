import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
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
            if (stopTimer) totalTime += elapsedNanos;
            dbgPrinter.log(
                    String.format("Move %3d; time used%s: %s, total %s", board.moves, stopTimer ? "" : " (running)",
                            DbgPrinter.timeFmt(elapsedNanos), DbgPrinter.timeFmt(totalTime)));
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
        private static final int MAX_SCORE = 1_000_000_000;
        private static final int MIN_SCORE = -1_000_000_000;

        private static final int PLAYER = 0;
        private static final int OPPONENT = 1;
        private static final int FIELD_IDX = 0;
        private static final int SCORE = 1;

        private static final int[] REMAINING_MOVES =
                {32, 31, 30, 29, 28, 27, 26, 25, 26, 26, 26, 26, 25, 25, 25, 24, 23, 22, 23, 22, 22, 21, 21, 21, 21, 20,
                        20, 20, 19, 21, 23, 24, 24, 25, 25, 27, 26, 26, 25, 27, 27, 26, 28, 27, 27, 30, 31, 30, 31, 30,
                        29, 28, 27, 28, 27, 31, 30, 29, 31, 30, 29, 28, 27, 26, 25, 24, 26, 25, 24, 23, 22, 28, 27, 26,
                        25, 24, 23, 22, 21, 20, 19, 26, 25, 24, 23, 22, 21, 20, 19, 18, 17, 16, 20, 19, 18, 17, 16, 15,
                        21, 20, 19, 18, 17, 16, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 9, 8, 7, 6, 5, 4, 3, 2, 1};

        Map<Board, CalcResult> calcCache = new HashMap<>(100_000);

        private final MoveConverter moveConverter;
        private final DbgPrinter dbgPrinter;
        private final Timer timer;
        private final Patterns patterns = DataReader.getPatterns();

        long maxNanos = 4_500_000_000L;
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
            return calcBoard(true, board, calcResult).ownScore;
        }

        public Move generateMove(final Board board) {
            long now = System.nanoTime();

            killerMoves = new int[2][maxDepth];
            Arrays.fill(killerMoves[0], -1);
            Arrays.fill(killerMoves[1], -1);

            final long remainingNanos = maxNanos - (timer.totalTime + now - timer.timerStart);
            int remainingMoves = board.moves >= REMAINING_MOVES.length ? 0 : REMAINING_MOVES[board.moves];

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

            if (calcResult.moves == null) calcBoard(isPlayer, board, calcResult);

            if (calcResult.ownScore == MAX_SCORE || calcResult.ownScore == MIN_SCORE) {
                // Terminal move
                return new int[]{calcResult.moves[0], calcResult.ownScore};
            }

            if (maxDepth <= 0) {
                return new int[]{-1, calcResult.ownScore};
            }

            int[] moves = new int[calcResult.moves.length + 2];
            Arrays.fill(moves, -1);
            int startIdx = 0;
            if (killerMoves[0][level] >= 0 && board.validMove(moveConverter.toMove(killerMoves[0][level]))) {
                moves[startIdx] = killerMoves[0][level];
                startIdx++;
            }
            if (killerMoves[1][level] >= 0 && board.validMove(moveConverter.toMove(killerMoves[1][level]))) {
                moves[startIdx] = killerMoves[1][level];
                startIdx++;
            }
            System.arraycopy(calcResult.moves, 0, moves, startIdx, calcResult.moves.length);

            int[] retval = new int[]{moves[0], isPlayer ? MIN_SCORE : MAX_SCORE};
            for (int move : moves) {
                if (move == -1) break; // End reached

                final Board nextBoard = board.copy().apply(moveConverter.toMove(move));

                final int[] idxAndScore =
                        minimax(nextBoard, !isPlayer, level + 1, maxDepth - 1, maxNanoTime, alpha, beta);

                if (idxAndScore == null) {
                    return null; // Time's up
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

        CalcResult calcBoard(final boolean isPlayer, final Board board, final CalcResult calcResult) {
            int playerToMoveFactor = isPlayer ? 1 : -1;
            int onMove = isPlayer ? PLAYER : OPPONENT;
            int offMove = isPlayer ? OPPONENT : PLAYER;

            int[][] matchInfo = match(board, patterns.allPatterns);

            int[] scores = new int[256];
            calcResult.ownScore = 0;

            for (int fieldIdx = 0; fieldIdx < 256; fieldIdx++) {
                int onMoveField = matchInfo[onMove][fieldIdx];
                int offMoveField = matchInfo[offMove][fieldIdx];

                // WINNING MOVES

                // Win immediately
                if (hasType(onMoveField, Pattern.TYPE_LINE4)) {
                    scores[fieldIdx] = 1000;
                    continue;
                }

                // Prevent immediate loss
                if (hasType(offMoveField, Pattern.TYPE_LINE4)) {
                    scores[fieldIdx] = 999;
                    continue;
                }

                // OPEN3 or 2 LINE3s in different directions is also winning
                if (hasType(onMoveField, Pattern.TYPE_OPEN3)) {
                    scores[fieldIdx] = 998;
                    continue;
                }
                if (multipleDirections(onMoveField, Pattern.TYPE_LINE3)) {
                    scores[fieldIdx] = 998;
                    continue;
                }

                // OPEN3 or 2 LINE3s for opponent will need to be blocked
                if (hasType(offMoveField, Pattern.TYPE_OPEN3)) {
                    scores[fieldIdx] = 997;
                    continue;
                }
                if (multipleDirections(offMoveField, Pattern.TYPE_LINE3)) {
                    scores[fieldIdx] = 997;
                    continue;
                }

                // LINE3 and an OPEN2 can be stopped with a lucky forcing move, but is generally winning
                if (hasType(onMoveField, Pattern.TYPE_LINE3) && hasType(onMoveField, Pattern.TYPE_OPEN2)) {
                    scores[fieldIdx] = 996;
                    continue;
                }
                // 2 OPEN2s are also blockable with a forcing move, but generally winning
                if (multipleDirections(onMoveField, Pattern.TYPE_OPEN2)) {
                    scores[fieldIdx] = 996;
                    continue;
                }

                // Same for opponent, block it
                if (hasType(offMoveField, Pattern.TYPE_LINE3) && hasType(offMoveField, Pattern.TYPE_OPEN2)) {
                    scores[fieldIdx] = 995;
                    continue;
                }
                if (multipleDirections(offMoveField, Pattern.TYPE_OPEN2)) {
                    scores[fieldIdx] = 995;
                    continue;
                }

                // NON-WINNING

                // Forcing, add much to the score
                scores[fieldIdx] = 0;
                if (hasType(onMoveField, Pattern.TYPE_LINE3)) {
                    scores[fieldIdx] += 200;
                    calcResult.ownScore += 200;
                }
                if (hasType(offMoveField, Pattern.TYPE_LINE3)) {
                    scores[fieldIdx] += 199;
                    calcResult.ownScore -= 200;
                }
                if (hasType(onMoveField, Pattern.TYPE_OPEN2)) {
                    scores[fieldIdx] += 150;
                    calcResult.ownScore += 200; // OPEN2 offers more opportunities in the future
                }
                if (hasType(offMoveField, Pattern.TYPE_OPEN2)) {
                    scores[fieldIdx] += 149;
                    calcResult.ownScore -= 200; // OPEN2 offers more opportunities in the future
                }

                // Non-forcing, but lengthening it may create opportunities
                if (hasType(onMoveField, Pattern.TYPE_LINE2)) {
                    scores[fieldIdx] += 100;
                    calcResult.ownScore += 100;
                }
                if (hasType(offMoveField, Pattern.TYPE_LINE2)) {
                    scores[fieldIdx] += 99;
                    calcResult.ownScore -= 100;
                }

                // Really only rarely useful
                if (hasType(onMoveField, Pattern.TYPE_LINE1)) {
                    scores[fieldIdx] += 10;
                    calcResult.ownScore += 1;
                }
                if (hasType(offMoveField, Pattern.TYPE_LINE1)) {
                    scores[fieldIdx] += 9;
                    calcResult.ownScore -= 1;
                }
            }

            Integer[] moves =
                    new Integer[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23,
                            24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46,
                            47, 48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 58, 59, 60, 61, 62, 63, 64, 65, 66, 67, 68, 69,
                            70, 71, 72, 73, 74, 75, 76, 77, 78, 79, 80, 81, 82, 83, 84, 85, 86, 87, 88, 89, 90, 91, 92,
                            93, 94, 95, 96, 97, 98, 99, 100, 101, 102, 103, 104, 105, 106, 107, 108, 109, 110, 111, 112,
                            113, 114, 115, 116, 117, 118, 119, 120, 121, 122, 123, 124, 125, 126, 127, 128, 129, 130,
                            131, 132, 133, 134, 135, 136, 137, 138, 139, 140, 141, 142, 143, 144, 145, 146, 147, 148,
                            149, 150, 151, 152, 153, 154, 155, 156, 157, 158, 159, 160, 161, 162, 163, 164, 165, 166,
                            167, 168, 169, 170, 171, 172, 173, 174, 175, 176, 177, 178, 179, 180, 181, 182, 183, 184,
                            185, 186, 187, 188, 189, 190, 191, 192, 193, 194, 195, 196, 197, 198, 199, 200, 201, 202,
                            203, 204, 205, 206, 207, 208, 209, 210, 211, 212, 213, 214, 215, 216, 217, 218, 219, 220,
                            221, 222, 223, 224, 225, 226, 227, 228, 229, 230, 231, 232, 233, 234, 235, 236, 237, 238,
                            239, 240, 241, 242, 243, 244, 245, 246, 247, 248, 249, 250, 251, 252, 253, 254, 255};

            Arrays.sort(moves, Comparator.comparingInt(fieldIdx -> scores[(int) fieldIdx]).reversed());

            calcResult.moves = Arrays.stream(moves)
                    .mapToInt(Integer::intValue)
                    .filter(fieldIdx -> scores[fieldIdx] > 0)
                    //.limit(10) // TODO: Check
                    .toArray();

            if (calcResult.moves.length == 0) {
                // No helpful move. Just finishing up the game. Pick first valid move.
                dbgPrinter.log("No helpful move found. Picking first valid move.");
                for (int i = 0; i < 256; i++) {
                    final Move move = moveConverter.toMove(i);
                    if (board.validMove(move)) {
                        calcResult.moves = new int[]{i};
                    }
                }
            }

            int bestMove = calcResult.moves[0];
            int bestScore = scores[bestMove];

            if (bestScore > 950) {
                if ((bestScore & 1) == 0) { // Even scores are for onMove
                    calcResult.ownScore = playerToMoveFactor * bestScore * 1_000_000;
                } else {
                    calcResult.ownScore = playerToMoveFactor * bestScore * -1_000_000;
                }
            } else {
                calcResult.ownScore *= playerToMoveFactor;
            }

            return calcResult;
        }

        static boolean multipleDirections(int fieldMatch, int type) {
            return hasType(fieldMatch, type) && multipleBitsSet(fieldMatch & type);
        }

        static boolean hasType(int fieldMatch, int type) {
            return (fieldMatch & type) != 0;
        }

        static boolean multipleBitsSet(int match) {
            return (match & (match - 1)) != 0;
        }

        private static int[][] match(final Board board, final Pattern[] patterns) {
            int[][] matchInfo = new int[2][256];

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
                    for (int i = 0; i < p.moves.length; i++)
                        matchInfo[PLAYER][p.moves[i]] = matchInfo[PLAYER][p.moves[i]] | p.moveTypes[i];
                }

                if (((nOS[0] & p.playerStones[0]) | (nOS[1] & p.playerStones[1]) | (nOS[2] & p.playerStones[2]) |
                        (nOS[3] & p.playerStones[3])) == 0) {
                    for (int i = 0; i < p.moves.length; i++)
                        matchInfo[OPPONENT][p.moves[i]] = matchInfo[OPPONENT][p.moves[i]] | p.moveTypes[i];
                }
            }

            return matchInfo;
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
        int[] moves;
        int ownScore;
    }

    static final class Patterns {
        Pattern[] allPatterns;
    }

    static final class Pattern {
        final static int TYPE_LINE4 = 1;
        final static int TYPE_OPEN3 = 2;
        final static int TYPE_LINE3_HORIZ = 4;
        final static int TYPE_LINE3_VERTI = 8;
        final static int TYPE_LINE3_NWSE = 16;
        final static int TYPE_LINE3_NESW = 32;
        final static int TYPE_OPEN2_HORIZ = 64;
        final static int TYPE_OPEN2_VERTI = 128;
        final static int TYPE_OPEN2_NWSE = 256;
        final static int TYPE_OPEN2_NESW = 512;
        final static int TYPE_LINE2_HORIZ = 1024;
        final static int TYPE_LINE2_VERTI = 2048;
        final static int TYPE_LINE2_NWSE = 4096;
        final static int TYPE_LINE2_NESW = 8192;
        final static int TYPE_LINE1 = 16384;

        final static int TYPE_LINE3 = TYPE_LINE3_HORIZ | TYPE_LINE3_VERTI | TYPE_LINE3_NWSE | TYPE_LINE3_NESW;
        final static int TYPE_LINE2 = TYPE_LINE2_HORIZ | TYPE_LINE2_VERTI | TYPE_LINE2_NWSE | TYPE_LINE2_NESW;
        final static int TYPE_OPEN2 = TYPE_OPEN2_HORIZ | TYPE_OPEN2_VERTI | TYPE_OPEN2_NWSE | TYPE_OPEN2_NESW;

        final long[] emptyFields;
        final long[] playerStones;
        final int[] moves;
        final int[] moveTypes;

        Pattern(final long[] emptyFields, final long[] playerStones, final int[] moves, final int[] moveTypes) {
            this.emptyFields = emptyFields;
            this.playerStones = playerStones;
            this.moves = moves;
            this.moveTypes = moveTypes;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) return true;
            if (!(o instanceof Pattern)) return false;
            final Pattern pattern = (Pattern) o;
            return Arrays.equals(emptyFields, pattern.emptyFields) && Arrays.equals(playerStones, pattern.playerStones) && Arrays.equals(moves, pattern.moves) &&
                    Arrays.equals(moveTypes, pattern.moveTypes);
        }

        @Override
        public int hashCode() {
            int result = Arrays.hashCode(emptyFields);
            result = 31 * result + Arrays.hashCode(playerStones);
            result = 31 * result + Arrays.hashCode(moves);
            result = 31 * result + Arrays.hashCode(moveTypes);
            return result;
        }
    }

    static final class DataReader {
        static Patterns getPatterns() throws DataFormatException {
            return deserializePatterns(Data.PATTERNS, Data.PATTERNS_UNCOMPRESSED_SIZE);
        }

        static Patterns deserializePatterns(final String patternsString, final int uncompressedSize) throws DataFormatException {
            final ByteBuffer byteBuffer = uncompress(patternsString, uncompressedSize);

            final Patterns patterns = new Patterns();

            final long longBufferLen = byteBuffer.getLong();
            final LongBuffer longBuffer = byteBuffer.asLongBuffer();
            longBuffer.limit((int) longBufferLen);
            byteBuffer.position(byteBuffer.position() + (int) longBufferLen * Long.BYTES);
            final IntBuffer intBuffer = byteBuffer.asIntBuffer();

            patterns.allPatterns = new Pattern[intBuffer.get()];
            readPatterns(patterns.allPatterns, longBuffer, intBuffer);
            return patterns;
        }

        private static void readPatterns(final Pattern[] pat, final LongBuffer longBuffer, final IntBuffer intBuffer) {
            for (int i = 0; i < pat.length; i++) {
                long[] emptyFields = new long[4];
                longBuffer.get(emptyFields);

                long[] playerStones = new long[4];
                longBuffer.get(playerStones);

                int moveCnt = intBuffer.get();
                int[] fieldIdx = new int[moveCnt];
                intBuffer.get(fieldIdx);
                int[] moveTypes = new int[moveCnt];
                intBuffer.get(moveTypes);

                pat[i] = new Pattern(emptyFields, playerStones, fieldIdx, moveTypes);
            }
        }

        private static ByteBuffer uncompress(final String dataString, final int uncompressedSize) throws DataFormatException {
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

        public static void loadOwnOpeningBookStraight(Map<Board, CalcResult> calcCache) throws DataFormatException {
            //loadOwnOpeningBook(calcCache, true, Data.OWN_OPENING_BOOK, Data.OWN_OPENING_BOOK_UNCOMPRESSED_SIZE);
        }

        public static void loadOwnOpeningBookSwitch(Map<Board, CalcResult> calcCache) throws DataFormatException {
            //loadOwnOpeningBook(calcCache, false, Data.OWN_OPENING_BOOK, Data.OWN_OPENING_BOOK_UNCOMPRESSED_SIZE);
        }

        //static void loadOwnOpeningBook(final Map<Board, CalcResult> calcCache, final boolean flip, final String ownOpeningBookString, final int uncompressedSize) throws DataFormatException {
        //    ByteBuffer buffer = uncompress(ownOpeningBookString, uncompressedSize);
        //
        //    final long longBufferLen = buffer.getLong();
        //    final long intBufferLen = buffer.getLong();
        //    final long count = buffer.getLong();
        //
        //    final LongBuffer longBuffer = buffer.asLongBuffer();
        //    longBuffer.limit((int) longBufferLen);
        //    buffer.position(buffer.position() + (int) longBufferLen * Long.BYTES);
        //    final IntBuffer intBuffer = buffer.asIntBuffer();
        //    intBuffer.limit((int) intBufferLen);
        //    buffer.position(buffer.position() + (int) intBufferLen * Integer.BYTES);
        //
        //    for (int i = 0; i < count; i++) {
        //        Board board = readBoard(longBuffer, intBuffer);
        //        CalcResult calcResult = readCalcResult(intBuffer, buffer);
        //
        //        if (flip) {
        //            board.flip();
        //
        //            byte[] swap;
        //
        //            if (calcResult.match4 != null) {
        //                swap = calcResult.match4[0];
        //                calcResult.match4[0] = calcResult.match4[1];
        //                calcResult.match4[1] = swap;
        //            }
        //
        //            if (calcResult.match3 != null) {
        //                swap = calcResult.match3[0];
        //                calcResult.match3[0] = calcResult.match3[1];
        //                calcResult.match3[1] = swap;
        //            }
        //
        //            if (calcResult.match2 != null) {
        //                swap = calcResult.match2[0];
        //                calcResult.match2[0] = calcResult.match2[1];
        //                calcResult.match2[1] = swap;
        //            }
        //
        //            if (calcResult.match1 != null) {
        //                swap = calcResult.match1[0];
        //                calcResult.match1[0] = calcResult.match1[1];
        //                calcResult.match1[1] = swap;
        //            }
        //        }
        //
        //        calcCache.put(board, calcResult);
        //    }
        //
        //    if (buffer.position() != buffer.capacity()) {
        //        System.err.println(buffer.position() + " :: " + buffer.capacity());
        //        throw new AssertionError();
        //    }
        //}

        private static Board readBoard(final LongBuffer longBuffer, final IntBuffer intBuffer) {
            Board board = new Board();
            board.playerToMove = intBuffer.get();
            board.moves = intBuffer.get();

            longBuffer.get(board.playerStones);
            longBuffer.get(board.opponentStones);

            return board;
        }

        //private static CalcResult readCalcResult(final IntBuffer intBuffer, final ByteBuffer buffer) {
        //    CalcResult calcResult = new CalcResult();
        //
        //    byte bools = buffer.get();
        //
        //    if ((bools & 1) == 1) {
        //        calcResult.match4 = new byte[2][256];
        //        buffer.get(calcResult.match4[0]);
        //        buffer.get(calcResult.match4[1]);
        //    }
        //    if ((bools & 2) == 2) {
        //        calcResult.match3 = new byte[2][256];
        //        buffer.get(calcResult.match3[0]);
        //        buffer.get(calcResult.match3[1]);
        //    }
        //    if ((bools & 4) == 4) {
        //        calcResult.match2 = new byte[2][256];
        //        buffer.get(calcResult.match2[0]);
        //        buffer.get(calcResult.match2[1]);
        //    }
        //    if ((bools & 8) == 8) {
        //        calcResult.match1 = new byte[2][256];
        //        buffer.get(calcResult.match1[0]);
        //        buffer.get(calcResult.match1[1]);
        //    }
        //
        //    if ((bools & 16) == 16) {
        //        int count = intBuffer.get();
        //        calcResult.moves = new ArrayList<>(count);
        //        for (int i = 0; i < count; i++) {
        //            calcResult.moves.add(intBuffer.get());
        //        }
        //    }
        //
        //    return calcResult;
        //}
    }

    @SuppressWarnings("StringBufferReplaceableByString") // They really can't be replaced by Strings.
    static final class Data {
        static final int PATTERNS_UNCOMPRESSED_SIZE = 2349084;
        static final String PATTERNS = new StringBuilder().append(
                "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")
                .append("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")
                .toString();
    }
}
