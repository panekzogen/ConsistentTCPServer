package Custom.Server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;

public class Main {
    public static void main(String[] args) {
        TCPServer tc = new TCPServer(Integer.parseInt(args[0]));
        tc.run();
    }
}
