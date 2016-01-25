package Custom.Client;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.Iterator;
import java.util.Set;

public class Client {
    String remoteAddr;
    int port;
    SocketChannel client;
    DatagramChannel clientUdp;
    Selector selector = null;
    SelectionKey selk = null;

    int command = 0;
    String args = null;
    Client(String ra, int p){
        remoteAddr = ra;
        port = p;
    }
    int connect(){
        try {
            client = SocketChannel.open();
            clientUdp = DatagramChannel.open();
            client.connect(new InetSocketAddress(remoteAddr, port));
            clientUdp.bind(client.getLocalAddress());
            client.configureBlocking(false);
            clientUdp.configureBlocking(false);
            //clientUdp.setOption(StandardSocketOptions.SO_RCVBUF, 1024);
            //clientUdp.setOption(StandardSocketOptions.SO_SNDBUF, 1024);
            //clientSocket.setOOBInline(true);
        } catch (IOException ex) {
            System.out.println(ex.getMessage());
            return 0;
        }
        return 1;
    }
    int start(){
        connect();
        BufferedReader inFromUser = new BufferedReader( new InputStreamReader(System.in));


        try {
            selector = Selector.open();
        } catch (IOException e) {
            e.printStackTrace();
        }
        try {
            selk = client.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE);
        } catch (ClosedChannelException e) {
            e.printStackTrace();
        }
        ByteBuffer rdBuf = ByteBuffer.allocate(1024);
        while (true) {
            if(command == 0 && client.isConnected()){
                System.out.print(">");
                String[] comm = null;
                try {
                    comm = inFromUser.readLine().split(" ", 2);
                } catch (IOException ex) {
                    System.out.println(ex.getMessage());
                }
                switch (comm[0].toLowerCase()){
                    case "echo":
                        command = 1;
                        args = comm[1];
                        break;
                    case "time":
                        command = 2;
                        args = "";
                        break;
                    case "download":
                        command = 3;
                        args = comm[1];
                        try {
                            file = new FileWriter("./" + args + ".indownload", true);
                        } catch (IOException e) {
                            System.out.println(e.getMessage());
                        }
                        break;
                    case "udownload":
                        command = 4;
                        args = comm[1];
                        try {
                            file = new FileWriter("./" + args + ".indownload", true);
                        } catch (IOException e) {
                            System.out.println(e.getMessage());
                        }
                        break;
                    case "close":case "exit":case "quit":
                        command = 5;
                        break;
                    default:
                        System.out.println("Command not found");
                }
            }
            int readyChannels = 0;
            try {
                readyChannels = selector.select(20000);
            } catch (IOException e) {
                System.out.println(e.getMessage());
            }

            Set<SelectionKey> selectedKeys = selector.selectedKeys();
            Iterator<SelectionKey> keyIterator = selectedKeys.iterator();
            while(keyIterator.hasNext()) {
                SelectionKey key = keyIterator.next();
                if (key.isReadable()){
                    try {
                       if(command != 7) client.read(rdBuf);
                       else clientUdp.read(rdBuf);
                    } catch (IOException ex) {
                        System.out.println(ex.getMessage());
                    }
                    while(!process(rdBuf));
                }
                if (key.isValid())
                if (key.isWritable()){
                    if(args != null){
                        ByteBuffer bb = ByteBuffer.allocate(1 + Integer.BYTES + args.length());
                        bb.put((byte)command);
                        bb.putInt(args.length());
                        bb.put(args.getBytes());
                        bb.rewind();
                        try{
                            while(bb.hasRemaining())
                                client.write(bb);
                        } catch (IOException ex) {
                            System.out.println(ex.getMessage());
                        }
                        args = null;
                    }
                    else if( command == 5){
                        ByteBuffer bb = ByteBuffer.wrap(new byte[]{5});
                        try {
                            while(bb.hasRemaining())
                                client.write(bb);
                            client.close();
                        } catch (IOException ex) {
                            System.out.println(ex.getMessage());
                        }
                        return 1;
                    }
                    else if(command == 7 && expectedPacket != 0){
                        ByteBuffer bb = ByteBuffer.allocate(Integer.BYTES);
                        bb.putInt((int)expectedPacket);
                        bb.flip();
                        try {
                            clientUdp.write(bb);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                        if(expectedPacket == -1){
                            command = 0;

                            selk.cancel();
                            try {
                                clientUdp.disconnect();
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                            try {
                                selk = client.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE);
                            } catch (ClosedChannelException e) {
                                e.printStackTrace();
                            }
                        }
                        expectedPacket = 0;
                    }
                }
            }
        }
    }
    FileWriter file = null;
    long packetsLeft = 0;
    long packetsCount = 0;
    long expectedPacket = 0;
    boolean process(ByteBuffer bb){
        if(command == 0)
            return true;
        if(command == 1 || command == 2){
            bb.flip();
            String s = new String(bb.array());
            s = s.substring(0, bb.limit());
            System.out.println(s);
            command = 0;
            bb.clear();
        }
        if(command == 3 || command == 4){
            bb.flip();
            long length = bb.getLong();
            if(command == 3) {
                packetsLeft = (int)length/1024;
                if(length% 1024 != 0) packetsLeft++;
                packetsCount = packetsLeft;
                bb = bb.compact();
                command = 6;
            }
            else {
                packetsLeft = (int)length/1020;
                if(length% 1020 != 0) packetsLeft++;
                packetsCount = packetsLeft;
                bb = bb.compact();
                command = 7;
                selk.cancel();
                try {
                    clientUdp.connect(new InetSocketAddress(((InetSocketAddress)client.getRemoteAddress()).getHostString(),
                            ((InetSocketAddress)client.getLocalAddress()).getPort() + 1));
                } catch (IOException e) {
                    e.printStackTrace();
                }
                try {
                    selk = clientUdp.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE);
                } catch (ClosedChannelException e) {
                    e.printStackTrace();
                }
            }
        }
        if(command == 6){
            if(!bb.hasRemaining() || packetsLeft == 1){
                packetsLeft--;
                //System.out.print("\rPacketsLeft: " + String.valueOf(packetsLeft));
                if(packetsLeft == 0){
                    try {
                        file.append(new String(bb.array()), 0, bb.position());
                        file.close();
                        command = 0;
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    bb.clear();
                    System.out.println("\r" + packetsCount + " of " + packetsCount + " [100%]");
                }else {
                    try {
                        file.append(new String(bb.array()), 0, 1024);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    bb.clear();
                }
                if ((packetsCount - packetsLeft - 1) % (packetsCount / (100)) == 0)
                    System.out.print("\r" + (packetsCount - packetsLeft) + " of " + packetsCount + " [" + (((packetsCount - packetsLeft)*100/packetsCount)) + "%]");
            }
            else return true;
        }
        if(command == 7){
            if(!bb.hasRemaining() || packetsLeft == 1){
                //System.out.print("\rPacketsLeft: " + String.valueOf(packetsLeft));
                if(packetsLeft == 1){
                    try {
                        packetsLeft--;
                        file.append(new String(bb.array()), 4, bb.position());
                        file.close();
                        expectedPacket = -1;
                        System.out.println("\r" + packetsCount + " of " + packetsCount + " [100%]");
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    bb.clear();
                }else {
                    bb.flip();
                    if(bb.getInt() == packetsCount - packetsLeft)
                        try {
                            packetsLeft--;
                            file.append(new String(bb.array()), 4, 1024);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    else expectedPacket = packetsCount - packetsLeft;
                    bb.clear();
                }
                if ((packetsCount - packetsLeft - 1) % (packetsCount / (100)) == 0)
                    System.out.print("\r" + (packetsCount - packetsLeft) + " of " + packetsCount + " [" + (((packetsCount - packetsLeft)*100/packetsCount)) + "%]");
            }
            else return true;
        }
        return true;
    }
}