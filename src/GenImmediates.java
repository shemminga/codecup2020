import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class GenImmediates {
    protected static final SjoerdsGomokuPlayer.DbgPrinter DBG_PRINTER =
            new SjoerdsGomokuPlayer.DbgPrinter(System.err, SjoerdsGomokuPlayer.START_UP_TIME, false);
    protected static final SjoerdsGomokuPlayer.MoveConverter MOVE_CONVERTER =
            new SjoerdsGomokuPlayer.MoveConverter(DBG_PRINTER);

    public static void main(String[] args) {
        final GenPattern horiz = new GenPattern(1, 5, "Aa", "Ab", "Ac", "Ad", "Ae");
        final GenPattern verti = new GenPattern(5, 1, "Aa", "Ba", "Ca", "Da", "Ea");
        final GenPattern diag1 = new GenPattern(5, 5, "Aa", "Bb", "Cc", "Dd", "Ee");
        final GenPattern diag2 = new GenPattern(5, 5, "Ae", "Bd", "Cc", "Db", "Ea");

        final List<String> strings = Stream.of(horiz, verti, diag1, diag2)
                .flatMap(GenImmediates::removeOneStone)
                .flatMap(GenImmediates::shiftRight)
                .flatMap(GenImmediates::shiftDown)
                .map(GenPattern::toPattern)
                .map(GenImmediates::patternToString)
                .collect(Collectors.toList());

        final List<String> methodCalls = new ArrayList<>();

        System.out.print("final static Pattern[] immediates = new Pattern[" + strings.size() + "];");

        int methodCounter = 0;
        String methodName = "initImmediates" + methodCounter;
        methodCalls.add(methodName);
        System.out.print("private static void " + methodName + "(){");

        for (int i = 0; i < strings.size(); i++) {
            System.out.print("immediates[" + i + "] = " + strings.get(i) + ";");

            if (i % 500 == 499) {
                methodCounter++;
                methodName = "initImmediates" + methodCounter;
                methodCalls.add(methodName);
                System.out.print("}private static void " + methodName + "(){");
            }
        }

        System.out.print("}static{");
        methodCalls.forEach(mc -> System.out.print(mc + "();"));
        System.out.print("}");
        System.out.println();
    }

    private static Stream<GenPattern> removeOneStone(GenPattern parent) {
        return Arrays.stream(parent.moves)
                .map(skip -> {
                    final List<String> list = new ArrayList<>(Arrays.asList(parent.moves));
                    list.remove(skip);
                    final String[] array = list.toArray(String[]::new);

                    final GenPattern genPattern = new GenPattern(parent.height, parent.width, array);
                    genPattern.response = skip;
                    return genPattern;
                });
    }

    private static Stream<GenPattern> shiftRight(GenPattern parent) {
        return Stream.iterate(parent, gp -> gp.width <= 16, gp -> {
            final String[] moves = Stream.of(gp.moves)
                    .map(m -> "" + m.charAt(0) + ((char) (m.charAt(1) + 1)))
                    .toArray(String[]::new);

            final GenPattern child = new GenPattern(gp.height, gp.width + 1, moves);
            child.response = "" + gp.response.charAt(0) + ((char) (gp.response.charAt(1) + 1));
            return child;
        });
    }

    private static Stream<GenPattern> shiftDown(GenPattern parent) {
        return Stream.iterate(parent, gp -> gp.height <= 16, gp -> {
            final String[] moves = Stream.of(gp.moves)
                    .map(m -> "" + ((char) (m.charAt(0) + 1)) + m.charAt(1))
                    .toArray(String[]::new);

            final GenPattern child = new GenPattern(gp.height + 1, gp.width, moves);
            child.response = "" + ((char) (gp.response.charAt(0) + 1)) + gp.response.charAt(1);
            return child;
        });
    }

    private static void print(GenPattern gp) {
        final JudgeBoard judgeBoard = new JudgeBoard();

        Stream.of(gp.moves)
                .forEach(move -> {
                    judgeBoard.move(move);
                    judgeBoard.playerToMove = JudgeBoard.Stone.WHITE;
                });

        StringBuilder sb = new StringBuilder();
        JudgeDumper.printBoard(judgeBoard, sb);
        String movesBoard = sb.toString();

        if (gp.response == null) {
            System.out.println(movesBoard);
            return;
        }

        final JudgeBoard responseJB = new JudgeBoard();
        responseJB.move(gp.response);

        StringBuilder rsb = new StringBuilder();
        JudgeDumper.printBoard(responseJB, rsb);
        String responseBoard = rsb.toString();

        final List<String> mbl = movesBoard.lines().collect(Collectors.toList());
        final List<String> rbl = responseBoard.lines().collect(Collectors.toList());

        for (int i = 0; i < mbl.size(); i++) {
            System.out.println(mbl.get(i).strip() + "     " + rbl.get(i).strip());
        }
    }

    private static String patternToString(SjoerdsGomokuPlayer.Pattern pattern) {
        StringBuilder sb = new StringBuilder();

        sb.append("new Pattern(")
                .append("new long[]{");
        for (int i = 0; i < 4; i++) {
            if (i > 0) sb.append(',');
            sb.append(longToShortString(pattern.relevantFields[i]))
                    .append('L');
        }
        sb.append('}')
                .append(',');

        sb.append("new long[]{");
        for (int i = 0; i < 4; i++) {
            if (i > 0) sb.append(',');
            sb.append(longToShortString(pattern.playerStones[i]))
                    .append('L');
        }
        sb.append('}')
                .append(',');

        sb.append(pattern.fieldIdx)
                .append(')');
        return sb.toString();
    }

    private static String longToShortString(long l) {
        final String hex = "0x" + Long.toHexString(l);
        final String dec = Long.toString(l);

        if (hex.length() < dec.length()) {
            return hex;
        }
        return dec;
    }

    private static class GenPattern {
        final int height;
        final int width;
        final String[] moves;
        String response;

        private GenPattern(final int height, final int width, final String... moves) {
            this.height = height;
            this.width = width;
            this.moves = moves;
        }

        private SjoerdsGomokuPlayer.Pattern toPattern() {
            final SjoerdsGomokuPlayer.Board board = new SjoerdsGomokuPlayer.Board();

            Arrays.stream(moves)
                    .map(move -> MOVE_CONVERTER.toFieldIdx(move.charAt(0), move.charAt(1)))
                    .map(MOVE_CONVERTER::toMove)
                    .forEach(move -> {
                        board.apply(move);
                        board.playerToMove = SjoerdsGomokuPlayer.Board.PLAYER;
                    });

            long[] playerStones = new long[]{board.playerStones[0], board.playerStones[1], board.playerStones[2],
                    board.playerStones[3]};
            final int fieldIdx = MOVE_CONVERTER.toFieldIdx(this.response.charAt(0), this.response.charAt(1));
            board.apply(MOVE_CONVERTER.toMove(fieldIdx));

            return new SjoerdsGomokuPlayer.Pattern(board.playerStones, playerStones, fieldIdx);
        }
    }
}
