package Custom.Server;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Set;

public class TCPServer{
    ArrayList<ClientContext> clients;
    private ServerSocketChannel server;

    private Selector selector = null;

    ClientContext prevClient;
    private static SocketAddress prevClientAddr = null;

    TCPServer(int port){
        try {
            server = ServerSocketChannel.open();
            server.bind(new InetSocketAddress(port) , 5);
            server.configureBlocking(false);
        } catch (IOException e) {
            e.printStackTrace();
        }
        clients = new ArrayList<ClientContext>();
    }
    public void run(){
        try {
            selector = Selector.open();
        } catch (IOException e) {
            e.printStackTrace();
        }
        SelectionKey selk = null;
        try {
            selk = server.register(selector, SelectionKey.OP_ACCEPT);
        } catch (ClosedChannelException e) {
            e.printStackTrace();
        }
        NeedForAccept:
        while (true) {
            int readyChannels = 0;
            try {
                readyChannels = selector.select(20000);
            } catch (IOException e) {
                System.out.println(e.getMessage());
            }

            Set<SelectionKey> selectedKeys = selector.selectedKeys();
            Iterator<SelectionKey> keyIterator = selectedKeys.iterator();
            while (keyIterator.hasNext()) {
                SelectionKey key = keyIterator.next();
                if(key.isAcceptable()){
                    try {
                        clients.add(new ClientContext(server.accept(), selector));
                        keyIterator.remove();
                        System.out.println("Got connection from " + clients.get(clients.size() - 1).socket);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                if (key.isValid())
                    if (key.isReadable()) {
                        for (ClientContext c : clients) {
                            if (c.socket.equals(key.channel())) {
                                try {
                                    ByteBuffer buf = ByteBuffer.allocate(1024);
                                    c.socket.read(buf);
                                    c.addIncomingData(buf);
                                    while (c.processed()) ;
                                    if (c.getAction() == Action.Close){
                                        closeConnection(c);
                                    }
                                } catch (IOException e) {
                                    System.out.println(e.getMessage());
                                    closeConnection(c);
                                }
                                break;
                            }
                            if (c.socketUdp.equals(key.channel())) {
                                try {
                                    ByteBuffer buf = ByteBuffer.allocate(1024);
                                    c.socketUdp.read(buf);
                                    c.addIncomingData(buf);
                                    while (c.processed()) ;
                                } catch (IOException e) {
                                    System.out.println(e.getMessage());
                                    closeConnection(c);
                                }
                                break;
                            }
                        }
                    }
                if (key.isValid())
                    if (key.isWritable()) {
                        for (ClientContext c : clients) {
                            if (c.socket.equals(key.channel())) {
                                try {
                                    c.sendAnswer();
                                } catch (IOException e) {
                                    System.out.println(e.getMessage());
                                    closeConnection(c);
                                }
                                break;
                            }
                            if (c.socketUdp.equals(key.channel())) {
                                try {
                                    c.sendAnswer();
                                } catch (IOException e) {
                                    System.out.println(e.getMessage());
                                    closeConnection(c);
                                }
                                break;
                            }
                        }
                    }
            }
        }

    }
    private int closeConnection(ClientContext cc){
        try {
            prevClientAddr = cc.socket.getRemoteAddress();
            cc.dropKeys();
            cc.socket.close();
            cc.socketUdp.close();
            prevClient = cc;
            clients.remove(cc);
        } catch (IOException e) {
            System.out.println(e.getMessage());
        }
        return 1;
    }
}