import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

class Judge {
    private final PlayerPipes player1Pipes;
    private final PlayerPipes player2Pipes;
    private final JudgeBoard board = new JudgeBoard();
    private final List<String> moves = new ArrayList<>();

    Judge(final PlayerPipes player1Pipes, final PlayerPipes player2Pipes) {
        this.player1Pipes = player1Pipes;
        this.player2Pipes = player2Pipes;
        JudgeDumper.sinkStreams(player1Pipes.err.readEnd, player2Pipes.err.readEnd);
    }

    void play() {
        player1Pipes.in.writeEnd.println("Start");

        // Read the opening 3 moves from player 1
        processMove(player1Pipes.out.readEnd, player2Pipes.in.writeEnd);
        processMove(player1Pipes.out.readEnd, player2Pipes.in.writeEnd);
        processMove(player1Pipes.out.readEnd, player2Pipes.in.writeEnd);

        // Read the Zz or a normal move from player 2
        processMove(player2Pipes.out.readEnd, player1Pipes.in.writeEnd);

        final Runnable[] processMoves = {
                () -> processMove(player1Pipes.out.readEnd, player2Pipes.in.writeEnd),
                () -> processMove(player2Pipes.out.readEnd, player1Pipes.in.writeEnd)
        };
        int currentMover = 0;

        while (!board.isGameEnded) {
            processMoves[currentMover].run();
            currentMover = currentMover == 0 ? 1 : 0;
        }

        player1Pipes.in.writeEnd.println("Quit");
        player2Pipes.in.writeEnd.println("Quit");

        JudgeDumper.printResult(board);
        JudgeDumper.printAllMoves(moves);
    }

    private void processMove(InputStream in, PrintStream out) {
        final String move = readMove(in);
        moves.add(move);
        board.move(move);

        JudgeDumper.printMove(move, board);

        out.println(move);
    }

    private static String readMove(InputStream in) {
        final int rowInt = robustRead(in);
        final int colInt = robustRead(in);
        return (char) rowInt + "" + (char) colInt;
    }

    private static int robustRead(InputStream in) {
        try {
            int val;

            do {
                val = in.read();
                if (val < 0) {
                    throw new IOException("Unexpected end of input");
                }
            } while (val == 32 || val == 9 || val == 10 || val == 13); // Whitespace

            return val;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
