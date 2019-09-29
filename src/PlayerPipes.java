import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;

class PlayerPipes {
    Pipe in = new Pipe();
    Pipe out = new Pipe();
    Pipe err = new Pipe();

    PlayerPipes() throws IOException {
    }

    static class Pipe {
        PipedInputStream readEnd = new PipedInputStream();
        PrintStream writeEnd = new PrintStream(new PipedOutputStream(readEnd));

        Pipe() throws IOException {
        }
    }
}
