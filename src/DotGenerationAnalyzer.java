import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class DotGenerationAnalyzer implements SjoerdsGomokuPlayer.GenerationAnalyzer {
    private DotNode root;
    private DotNode current;

    @Override
    public void reset() {
        root = new DotNode(-1, null, null);
        current = root;
    }

    @Override
    public void addChildMove(final int move, final String note) {
        final DotNode child = new DotNode(move, current, note);
        current.children = Collections.singletonList(child);
    }

    @Override
    public void addChildMoves(final List<Map.Entry<Integer, Integer>> moveScores) {
        current.children = moveScores.stream()
                .map(e -> new DotNode(e.getKey(), current, e.getValue().toString()))
                .collect(Collectors.toList());
    }

    @Override
    public void selectCurrentMove(final int move) {
        current = current.children.stream()
                .filter(node -> node.move == move)
                .findAny()
                .orElseThrow();
    }

    @Override
    public void levelUp() {
        current = current.parent;
    }

    @Override
    public void setScore(final int score) {
        current.heuristicValue = score;
    }

    @Override
    public void setPreferredChild(final int move) {
        final DotNode child = current.children.stream()
                .filter(node -> node.move == move)
                .findAny()
                .orElseThrow();
        child.preferred = true;
    }

    public String toString(SjoerdsGomokuPlayer.MoveConverter moveConverter) {
        final StringBuilder sb = new StringBuilder();

        sb.append("digraph G {\n");
        //sb.append("ratio=compress;\n");
        //sb.append("size=\"8.3,11.7!\";\n");

        toString(root, 1, sb, moveConverter);

        sb.append("}\n");
        return sb.toString();
    }

    private void toString(final DotNode node, final int level, final StringBuilder sb,
            final SjoerdsGomokuPlayer.MoveConverter moveConverter) {
        sb.append(node.graphNodeName)
                .append('[');
        if (node.move < 0) {
            sb.append("label=root,shape=star,fillcolor=pink,style=filled");
        } else {
            sb.append("label=<")
                    .append(node.move)
                    .append(" (")
                    .append(moveConverter.toString(node.move))
                    .append(")<br/>")
                    .append(node.generationValue)
                    .append("<br/>")
                    .append(node.heuristicValue)
                    .append(">");
        }
        sb.append("];\n");

        if (node.parent != null) {
            sb.append(node.parent.graphNodeName)
                    .append(" -> ")
                    .append(node.graphNodeName);

            if (node.preferred) {
                sb.append("[color=red,penwidth=3.0]");
            }

            sb.append(";\n");
        }

        if (node.children != null) {
            node.children.forEach(child -> toString(child, level + 1, sb, moveConverter));
        }
    }

    private static class DotNode {
        private String graphNodeName;
        private final int move;
        private final DotNode parent;
        private String generationValue;
        private Integer heuristicValue;
        private List<DotNode> children;
        private boolean preferred;

        private DotNode(final int move, final DotNode parent, final String note) {
            if (parent == null) {
                graphNodeName = "R";
            } else {
                graphNodeName = parent.graphNodeName + "_" + move;
            }

            this.move = move;
            this.parent = parent;
            generationValue = note;
        }
    }
}
