import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;
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

    public static void main(String[] args) {
        getPatterns();
    }

    public static SjoerdsGomokuPlayer.Patterns getPatterns() {
        SjoerdsGomokuPlayer.Patterns patterns = new SjoerdsGomokuPlayer.Patterns();
        patterns.pat1 = genPatterns(4);
        patterns.pat2 = genPatterns(3);
        patterns.pat3 = genPatterns(2);
        patterns.pat4 = genPatterns(1);
        return patterns;
    }

    private static SjoerdsGomokuPlayer.Pattern[] genPatterns(int nrOfStonesToRemove) {
        Stream<GenPattern> stream = Stream.of(HORIZ, VERTI, DIAG_1, DIAG_2);

        for (int i = 0; i < nrOfStonesToRemove; i++) {
            stream = stream.flatMap(GenPatterns::removeOneStone);
        }

        return stream
                .flatMap(GenPatterns::shiftRight)
                .flatMap(GenPatterns::shiftDown)
                .distinct()
                .map(GenPattern::toPattern)
                // Do not sort here. Results in a 64% increase in compressed size.
                .toArray(SjoerdsGomokuPlayer.Pattern[]::new);
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
