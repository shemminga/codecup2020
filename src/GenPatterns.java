import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class GenPatterns {
    enum Direction {
        HORIZ, VERTI, NWSE /* \ */, NESW /* / */
    }

    private static final GenPattern HORIZ = new GenPattern(Direction.HORIZ,1, 5, "Aa", "Ab", "Ac", "Ad", "Ae");
    private static final GenPattern VERTI = new GenPattern(Direction.VERTI,5, 1, "Aa", "Ba", "Ca", "Da", "Ea");
    private static final GenPattern NWSE = new GenPattern(Direction.NWSE,5, 5, "Aa", "Bb", "Cc", "Dd", "Ee");
    private static final GenPattern NESW = new GenPattern(Direction.NESW,5, 5, "Ae", "Bd", "Cc", "Db", "Ea");

    private static final GenPattern OPEN_HORIZ =
            new GenPattern(Direction.HORIZ, 1, 6, "Ab", "Ac", "Ad", "Ae").addExtraEmptyFields("Aa", "Af");
    private static final GenPattern OPEN_VERTI =
            new GenPattern(Direction.VERTI, 6, 1, "Ba", "Ca", "Da", "Ea").addExtraEmptyFields("Aa", "Fa");
    private static final GenPattern OPEN_NWSE =
            new GenPattern(Direction.NWSE, 6, 6, "Bb", "Cc", "Dd", "Ee").addExtraEmptyFields("Aa", "Ff");
    private static final GenPattern OPEN_NESW =
            new GenPattern(Direction.NESW, 6, 6, "Be", "Cd", "Dc", "Eb").addExtraEmptyFields("Af", "Fa");

    private static final SjoerdsGomokuPlayer.MoveConverter MOVE_CONVERTER = new SjoerdsGomokuPlayer.MoveConverter();

    public static void main(String[] args) {
        System.out.println(getPatterns().allPatterns.length);
    }

    public static SjoerdsGomokuPlayer.Patterns getPatterns() {
        SjoerdsGomokuPlayer.Patterns patterns = new SjoerdsGomokuPlayer.Patterns();
        SjoerdsGomokuPlayer.Pattern[] line1 = genPatterns(4);
        SjoerdsGomokuPlayer.Pattern[] line2 = genPatterns(3);
        SjoerdsGomokuPlayer.Pattern[] line3 = genPatterns(2);
        SjoerdsGomokuPlayer.Pattern[] line4 = genPatterns(1);

        SjoerdsGomokuPlayer.Pattern[] open2 = genOpenPatterns(2);
        SjoerdsGomokuPlayer.Pattern[] open3 = genOpenPatterns(1);

        patterns.allPatterns = Stream.of(line1, line2, line3, line4, open2, open3)
                .map(Arrays::stream)
                .reduce(Stream::concat)
                .orElseGet(Stream::empty)
                .toArray(SjoerdsGomokuPlayer.Pattern[]::new);

        return patterns;
    }

    private static SjoerdsGomokuPlayer.Pattern[] genPatterns(int nrOfStonesToRemove) {
        Stream<GenPattern> stream = Stream.of(HORIZ, VERTI, NWSE, NESW);
        return genPatterns(nrOfStonesToRemove, stream);
    }

    private static SjoerdsGomokuPlayer.Pattern[] genOpenPatterns(int nrOfStonesToRemove) {
        Stream<GenPattern> stream = Stream.of(OPEN_HORIZ, OPEN_VERTI, OPEN_NWSE, OPEN_NESW);
        return genPatterns(nrOfStonesToRemove, stream);
    }

    private static SjoerdsGomokuPlayer.Pattern[] genPatterns(int nrOfStonesToRemove, Stream<GenPattern> stream) {
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

                    final GenPattern genPattern = new GenPattern(parent.direction, parent.height, parent.width, array);
                    genPattern.emptyFields.addAll(parent.emptyFields);
                    genPattern.emptyFields.add(skip);
                    Collections.sort(genPattern.emptyFields);
                    genPattern.extraEmptyFields.addAll(parent.extraEmptyFields);

                    if (parent.moves.length + parent.emptyFields.size() + parent.extraEmptyFields.size() !=
                            genPattern.moves.length + genPattern.emptyFields.size() +
                                    genPattern.extraEmptyFields.size()) throw new AssertionError();

                    return genPattern;
                });
    }

    private static Stream<GenPattern> shiftRight(GenPattern parent) {
        return Stream.iterate(parent, gp -> gp.width <= 16, GenPatterns::shiftRightOnce);
    }

    private static GenPattern shiftRightOnce(GenPattern parent) {
        final String[] moves = Stream.of(parent.moves)
                .map(m -> "" + m.charAt(0) + ((char) (m.charAt(1) + 1)))
                .toArray(String[]::new);

        final GenPattern child = new GenPattern(parent.direction, parent.height, parent.width + 1, moves);
        parent.emptyFields.forEach(r -> child.emptyFields.add("" + r.charAt(0) + ((char) (r.charAt(1) + 1))));
        Collections.sort(child.emptyFields);

        parent.extraEmptyFields.forEach(r -> child.extraEmptyFields.add("" + r.charAt(0) + ((char) (r.charAt(1) + 1))));
        Collections.sort(child.extraEmptyFields);

        if (parent.moves.length + parent.emptyFields.size() + parent.extraEmptyFields.size() !=
                child.moves.length + child.emptyFields.size() + child.extraEmptyFields.size())
            throw new AssertionError();

        return child;
    }

    private static Stream<GenPattern> shiftDown(GenPattern parent) {
        return Stream.iterate(parent, gp -> gp.height <= 16, GenPatterns::shiftDownOnce);
    }

    private static GenPattern shiftDownOnce(GenPattern parent) {
        final String[] moves = Stream.of(parent.moves)
                .map(m -> "" + ((char) (m.charAt(0) + 1)) + m.charAt(1))
                .toArray(String[]::new);

        final GenPattern child = new GenPattern(parent.direction, parent.height + 1, parent.width, moves);
        parent.emptyFields.forEach(r -> child.emptyFields.add("" + ((char) (r.charAt(0) + 1)) + r.charAt(1)));
        Collections.sort(child.emptyFields);

        parent.extraEmptyFields.forEach(r -> child.extraEmptyFields.add("" + ((char) (r.charAt(0) + 1)) + r.charAt(1)));
        Collections.sort(child.extraEmptyFields);

        if (parent.moves.length + parent.emptyFields.size() + parent.extraEmptyFields.size() !=
                child.moves.length + child.emptyFields.size() + child.extraEmptyFields.size())
            throw new AssertionError();

        return child;
    }

    private static class GenPattern {
        final Direction direction;
        final int height;
        final int width;
        final String[] moves;
        final List<String> emptyFields = new ArrayList<>();
        final List<String> extraEmptyFields = new ArrayList<>();
        int stones;

        private GenPattern(Direction direction, final int height, final int width, final String... moves) {
            this.direction = direction;
            this.height = height;
            this.width = width;
            this.moves = moves;
            this.stones = moves.length;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            final GenPattern that = (GenPattern) o;
            return height == that.height && width == that.width && stones == that.stones &&
                    direction == that.direction && Arrays.equals(moves, that.moves) &&
                    Objects.equals(emptyFields, that.emptyFields) &&
                    Objects.equals(extraEmptyFields, that.extraEmptyFields);
        }

        @Override
        public int hashCode() {
            int result = Objects.hash(direction, height, width, emptyFields, extraEmptyFields, stones);
            result = 31 * result + Arrays.hashCode(moves);
            return result;
        }

        private GenPattern addExtraEmptyFields(String... fields) {
            extraEmptyFields.addAll(Arrays.asList(fields));
            extraEmptyFields.sort(Comparator.naturalOrder());
            return this;
        }

        private SjoerdsGomokuPlayer.Pattern toPattern() {
            if (extraEmptyFields.size() > 0) {
                if (moves.length + emptyFields.size() + extraEmptyFields.size() != 6)
                    throw new AssertionError();
            }
            if (extraEmptyFields.size() <= 0) {
                if (moves.length + emptyFields.size() + extraEmptyFields.size() != 5) throw new AssertionError();
            }

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

            int[] moves = new int[this.extraEmptyFields.size() + this.emptyFields.size()];
            int[] moveTypes = new int[this.extraEmptyFields.size() + this.emptyFields.size()];

            int i = 0;
            for (; i < emptyFields.size(); i++) {
                String r = emptyFields.get(i);
                moves[i] = SjoerdsGomokuPlayer.MoveConverter.toFieldIdx(r.charAt(0), r.charAt(1));
                moveTypes[i] = getMoveType(false, !extraEmptyFields.isEmpty());
            }
            for (; (i - emptyFields.size()) < extraEmptyFields.size(); i++) {
                String r = extraEmptyFields.get(i - emptyFields.size());
                moves[i] = SjoerdsGomokuPlayer.MoveConverter.toFieldIdx(r.charAt(0), r.charAt(1));
                moveTypes[i] = getMoveType(true, !extraEmptyFields.isEmpty());
            }

            SjoerdsGomokuPlayer.Board emptyFieldsBoard = new SjoerdsGomokuPlayer.Board();
            IntStream.of(moves)
                    .mapToObj(MOVE_CONVERTER::toMove)
                    .forEach(m -> {
                        emptyFieldsBoard.playerToMove = SjoerdsGomokuPlayer.Board.PLAYER;
                        emptyFieldsBoard.apply(m);
                    });

            int total = 0;
            for (long mv : board.playerStones)
                total += Long.bitCount(mv);
            for (long mv : emptyFieldsBoard.playerStones)
                total += Long.bitCount(mv);
            if (board.moves + emptyFieldsBoard.moves != total) {
                throw new AssertionError();
            }

            int noOverlapTotal = 0;
            for (int idx = 0; idx < 4; idx++) {
                noOverlapTotal += Long.bitCount(board.playerStones[idx] | emptyFieldsBoard.playerStones[idx]);
            }
            if (total != noOverlapTotal) throw new AssertionError();

            return new SjoerdsGomokuPlayer.Pattern(emptyFieldsBoard.playerStones, playerStones, moves, moveTypes);
        }

        private int getMoveType(boolean isExtra, boolean hasExtra) {
            if (stones == 4) {
                return SjoerdsGomokuPlayer.Pattern.TYPE_LINE4;
            }

            if (stones == 3) {
                if (hasExtra && !isExtra) {
                    return SjoerdsGomokuPlayer.Pattern.TYPE_OPEN3;
                } else {
                    switch (direction) {
                    case HORIZ:
                        return SjoerdsGomokuPlayer.Pattern.TYPE_LINE3_HORIZ;
                    case VERTI:
                        return SjoerdsGomokuPlayer.Pattern.TYPE_LINE3_VERTI;
                    case NWSE:
                        return SjoerdsGomokuPlayer.Pattern.TYPE_LINE3_NWSE;
                    case NESW:
                        return SjoerdsGomokuPlayer.Pattern.TYPE_LINE3_NESW;
                    }
                }
            }

            if (stones == 2) {
                if (hasExtra && !isExtra) {
                    switch (direction) {
                    case HORIZ:
                        return SjoerdsGomokuPlayer.Pattern.TYPE_OPEN2_HORIZ;
                    case VERTI:
                        return SjoerdsGomokuPlayer.Pattern.TYPE_OPEN2_VERTI;
                    case NWSE:
                        return SjoerdsGomokuPlayer.Pattern.TYPE_OPEN2_NWSE;
                    case NESW:
                        return SjoerdsGomokuPlayer.Pattern.TYPE_OPEN2_NESW;
                    }
                } else {
                    switch (direction) {
                    case HORIZ:
                        return SjoerdsGomokuPlayer.Pattern.TYPE_LINE2_HORIZ;
                    case VERTI:
                        return SjoerdsGomokuPlayer.Pattern.TYPE_LINE2_VERTI;
                    case NWSE:
                        return SjoerdsGomokuPlayer.Pattern.TYPE_LINE2_NWSE;
                    case NESW:
                        return SjoerdsGomokuPlayer.Pattern.TYPE_LINE2_NESW;
                    }
                }
            }

            if (stones == 1) return SjoerdsGomokuPlayer.Pattern.TYPE_LINE1;

            throw new AssertionError(stones);
        }
    }
}
