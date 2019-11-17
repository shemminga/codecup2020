import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;

public class GenPatterns {
    private static final GenPattern HORIZ = new GenPattern(1, 5, "Aa", "Ab", "Ac", "Ad", "Ae");
    private static final GenPattern VERTI = new GenPattern(5, 1, "Aa", "Ba", "Ca", "Da", "Ea");
    private static final GenPattern DIAG_1 = new GenPattern(5, 5, "Aa", "Bb", "Cc", "Dd", "Ee");
    private static final GenPattern DIAG_2 = new GenPattern(5, 5, "Ae", "Bd", "Cc", "Db", "Ea");

    private static final SjoerdsGomokuPlayer.DbgPrinter DBG_PRINTER =
            new SjoerdsGomokuPlayer.DbgPrinter(System.err, SjoerdsGomokuPlayer.START_UP_TIME, false);
    private static final SjoerdsGomokuPlayer.MoveConverter MOVE_CONVERTER =
            new SjoerdsGomokuPlayer.MoveConverter(DBG_PRINTER);

    private static List<String> longCache;

    public static void main(String[] args) {
        @SuppressWarnings("unchecked")
        final List<SjoerdsGomokuPlayer.Pattern>[] patterns = new List[] {
                genPatterns(1),
                genPatterns(2),
                genPatterns(3),
                genPatterns(4)
        };

        fillLongCache(patterns);

        printCode(patterns);
    }

    private static void printCode(final List<SjoerdsGomokuPlayer.Pattern>[] patterns) {
        System.out.println("static class Patterns {");

        System.out.println("private static long[] la(long... ls) { return ls; }");

        System.out.println("private static long[] la0(long l) { return la(l, 0, 0, 0); }");
        System.out.println("private static long[] la1(long l) { return la(0, l, 0, 0); }");
        System.out.println("private static long[] la2(long l) { return la(0, 0, l, 0); }");
        System.out.println("private static long[] la3(long l) { return la(0, 0, 0, l); }");

        System.out.println("private static Pattern p(long[] rf, long[] ps, int... fi) { return new Pattern(rf, ps, fi); }");

        printLongCache();

        final List<String> methodCalls = new ArrayList<>();

        for (int i = 0; i < 4; i++) {
            final List<String> strings = printPatterns(4 - i, patterns[i]);
            methodCalls.addAll(strings);
        }

        System.out.println("static{");
        methodCalls.forEach(mc -> System.out.println(mc + "();"));
        System.out.println("}");

        System.out.println("}");
    }

    private static List<SjoerdsGomokuPlayer.Pattern> genPatterns(int nrOfStonesToRemove) {
        Stream<GenPattern> stream = Stream.of(HORIZ, VERTI, DIAG_1, DIAG_2);

        for (int i = 0; i < nrOfStonesToRemove; i++) {
            stream = stream.flatMap(GenPatterns::removeOneStone);
        }

        return stream
                .flatMap(GenPatterns::shiftRight)
                .flatMap(GenPatterns::shiftDown)
                .distinct()
                .map(GenPattern::toPattern)
                .collect(Collectors.toList());
    }

    private static List<String> printPatterns(int n, List<SjoerdsGomokuPlayer.Pattern> patterns) {
        final List<String> strings = patterns.stream()
                .map(GenPatterns::patternToString)
                .collect(Collectors.toList());

        final List<String> methodCalls = new ArrayList<>();

        System.out.println("final static Pattern[] pat" + n + " = new Pattern[" + strings.size() + "];");

        int methodCounter = 0;
        String methodName = "initPat" + n + "" + methodCounter;
        methodCalls.add(methodName);
        System.out.println("private static void " + methodName + "(){");
        System.out.println("var arr = new Pattern[]{");

        boolean first = true;
        int destPos = 0;
        for (int i = 0; i < strings.size(); i++) {
            if (!first) System.out.print(",");
            first = false;
            System.out.print(strings.get(i));
            if (i % 20 == 19) System.out.println();

            if (i % 500 == 499) {
                methodCounter++;
                methodName = "initPat" + n + "" + methodCounter;
                methodCalls.add(methodName);
                System.out.println("};");
                System.out.println("System.arraycopy(arr,0,pat" + n + "," + destPos + ",arr.length);");
                System.out.println("}");
                System.out.println("private static void " + methodName + "(){");
                System.out.println("var arr = new Pattern[]{");
                first = true;
                destPos = i + 1;
            }
        }

        System.out.println("};");
        System.out.println("System.arraycopy(arr,0,pat" + n + "," + destPos + ",arr.length);");
        System.out.println("}");

        return methodCalls;
    }

    private static void fillLongCache(final List<SjoerdsGomokuPlayer.Pattern>[] patterns) {
        GenPatterns.longCache = Arrays.stream(patterns)
                .flatMap(List::stream)
                .flatMap(p -> Stream.concat(LongStream.of(p.emptyFields)
                        .boxed(), LongStream.of(p.playerStones)
                        .boxed()))
                .map(GenPatterns::longToShortStringUncached)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
                .entrySet()
                .stream()
                .filter(e -> {
                    var uncached = e.getKey().length() * e.getValue();
                    var cached = 10 + e.getKey().length() + 7 * e.getValue();
                    return cached < uncached;
                })
                .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    private static void printLongCache() {
        System.out.println("private static long[] lc = new long[]{");

        boolean first = true;
        for (int i = 0; i < longCache.size(); i++) {
            if (!first) System.out.print(',');
            if (i % 10 == 9) System.out.println();
            System.out.print(longCache.get(i));
            first = false;
        }

        System.out.println("};");
    }

    private static Stream<GenPattern> removeOneStone(GenPattern parent) {
        return Arrays.stream(parent.moves)
                .map(skip -> {
                    final List<String> list = new ArrayList<>(Arrays.asList(parent.moves));
                    list.remove(skip);
                    final String[] array = list.toArray(String[]::new);

                    final GenPattern genPattern = new GenPattern(parent.height, parent.width, array);
                    genPattern.emptyFields.addAll(parent.emptyFields);
                    genPattern.emptyFields.add(skip);
                    Collections.sort(genPattern.emptyFields);
                    return genPattern;
                });
    }

    private static Stream<GenPattern> shiftRight(GenPattern parent) {
        return Stream.iterate(parent, gp -> gp.width <= 16, gp -> {
            final String[] moves = Stream.of(gp.moves)
                    .map(m -> "" + m.charAt(0) + ((char) (m.charAt(1) + 1)))
                    .toArray(String[]::new);

            final GenPattern child = new GenPattern(gp.height, gp.width + 1, moves);
            gp.emptyFields.forEach(r -> child.emptyFields.add("" + r.charAt(0) + ((char) (r.charAt(1) + 1))));
            Collections.sort(child.emptyFields);

            return child;
        });
    }

    private static Stream<GenPattern> shiftDown(GenPattern parent) {
        return Stream.iterate(parent, gp -> gp.height <= 16, gp -> {
            final String[] moves = Stream.of(gp.moves)
                    .map(m -> "" + ((char) (m.charAt(0) + 1)) + m.charAt(1))
                    .toArray(String[]::new);

            final GenPattern child = new GenPattern(gp.height + 1, gp.width, moves);
            gp.emptyFields.forEach(r -> child.emptyFields.add("" + ((char) (r.charAt(0) + 1)) + r.charAt(1)));
            Collections.sort(child.emptyFields);

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

        final JudgeBoard responseJB = new JudgeBoard();
        responseJB.move(gp.emptyFields.get(0));

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

        sb.append("p(");
        appendLongArrayCall(pattern.emptyFields, sb);
        sb.append(',');

        appendLongArrayCall(pattern.playerStones, sb);
        sb.append(',');

        boolean first = true;
        for (int fieldIdx : pattern.fieldIdxs) {
            if (!first) sb.append(',');
            sb.append(longToShortStringUncached(fieldIdx));
            first = false;
        }
        sb.append(')');
        return sb.toString();
    }

    private static void appendLongArrayCall(long[] arr, StringBuilder sb) {
        int filled = -1;
        boolean found = false;
        for (int i = 0; i < 4; i++) {
            if (arr[i] != 0) {
                if (found) {
                    filled = -1;
                } else {
                    filled = i;
                    found = true;
                }
            }
        }

        if (filled >= 0) {
            sb.append("la")
                    .append(filled)
                    .append('(')
                    .append(longToShortString(arr[filled]))
                    .append(')');
            return;
        }

        sb.append("la(");
        for (int i = 0; i < 4; i++) {
            if (i > 0) sb.append(',');
            sb.append(longToShortString(arr[i]));
        }
        sb.append(')');
    }

    private static String longToShortString(long l) {
        final String s = longToShortStringUncached(l);
        final int idx = longCache.indexOf(s);
        return idx >= 0 ? ("lc[" + idx + "]") : s;
    }

    private static String longToShortStringUncached(long l) {
        final String postfix = (l <= Integer.MAX_VALUE && l >= Integer.MIN_VALUE) ? "" : "L";

        final String hex = "0x" + Long.toHexString(l);
        final String dec = Long.toString(l);

        if (hex.length() < dec.length()) {
            return hex + postfix;
        }
        return dec + postfix;
    }

    private static class GenPattern {
        final int height;
        final int width;
        final String[] moves;
        final List<String> emptyFields = new ArrayList<>();

        private GenPattern(final int height, final int width, final String... moves) {
            this.height = height;
            this.width = width;
            this.moves = moves;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            final GenPattern that = (GenPattern) o;
            return height == that.height && width == that.width && Arrays.equals(moves, that.moves) &&
                    emptyFields.equals(that.emptyFields);
        }

        @Override
        public int hashCode() {
            int result = Objects.hash(height, width, emptyFields);
            result = 31 * result + Arrays.hashCode(moves);
            return result;
        }

        private SjoerdsGomokuPlayer.Pattern toPattern() {
            final SjoerdsGomokuPlayer.Board board = new SjoerdsGomokuPlayer.Board();

            Arrays.stream(moves)
                    .map(move -> SjoerdsGomokuPlayer.MoveConverter.toFieldIdx(move.charAt(0), move.charAt(1)))
                    .map(MOVE_CONVERTER::toMove)
                    .forEach(move -> {
                        board.playerToMove = SjoerdsGomokuPlayer.Board.PLAYER;
                        board.apply(move);
                    });

            long[] playerStones = new long[]{board.playerStones[0], board.playerStones[1], board.playerStones[2],
                    board.playerStones[3]};

            int[] fieldIdxs = this.emptyFields.stream()
                    .mapToInt(r -> SjoerdsGomokuPlayer.MoveConverter.toFieldIdx(r.charAt(0), r.charAt(1)))
                    .toArray();

            SjoerdsGomokuPlayer.Board emptyFieldsBoard = new SjoerdsGomokuPlayer.Board();
            IntStream.of(fieldIdxs)
                    .mapToObj(MOVE_CONVERTER::toMove)
                    .forEach(m -> {
                        emptyFieldsBoard.playerToMove = SjoerdsGomokuPlayer.Board.PLAYER;
                        emptyFieldsBoard.apply(m);
                    });

            return new SjoerdsGomokuPlayer.Pattern(emptyFieldsBoard.playerStones, playerStones, fieldIdxs);
        }
    }
}
