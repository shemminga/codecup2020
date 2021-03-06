import java.io.IOException;
import java.util.zip.DataFormatException;

public class PvP {
    public static void main(String[] args) throws IOException, InterruptedException, DataFormatException {
        System.out.println("Waiting for enter");
        System.in.read();

        PlayerPipes player1Pipes = new PlayerPipes();
        PlayerPipes player2Pipes = new PlayerPipes();

        SjoerdsGomokuPlayer player1 = newPlayer(player1Pipes);
        SjoerdsGomokuPlayer player2 = newPlayer(player2Pipes);

        final Thread thread1 = new Thread(playerRunnable(player1));
        final Thread thread2 = new Thread(playerRunnable(player2));

        thread1.start();
        thread2.start();

        final Judge judge = new Judge(player1Pipes, player2Pipes);
        judge.play();

        thread1.join();
        thread2.join();
    }

    private static SjoerdsGomokuPlayer newPlayer(final PlayerPipes pipes) throws DataFormatException {
        final SjoerdsGomokuPlayer.IO io =
                new SjoerdsGomokuPlayer.IO(pipes.in.readEnd, pipes.out.writeEnd, pipes.err.writeEnd, false);
        return new SjoerdsGomokuPlayer(io);
    }

    private static Runnable playerRunnable(SjoerdsGomokuPlayer player) {
        return () -> {
            try {
                player.play();
            } catch (IOException | DataFormatException e) {
                throw new RuntimeException(e);
            }
        };
    }
}
