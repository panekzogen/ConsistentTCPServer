package Custom.Server;

import javax.xml.crypto.Data;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.SocketOption;
import java.net.SocketOptions;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.*;

enum Action{
        None, Echo, Time, File, FileUdp, Close, FileContent, FileUdpContent
        };

/**
 * Поддерживаемые операции:
 *      Echo, Time, TCP and UDP file downloading
 *
 * Как вы можете заметить из первых строк, инкапсуляция почти на нуле
 * Что говорит о качестве кода очень многое
 */
class ClientContext {
    SocketChannel socket;
    DatagramChannel socketUdp;
    private SelectionKey tcpKey;
    private SelectionKey udpKey;
    private Selector sel;

    private Action action;
    private int argsSize;
    private String filename;
    private DataInputStream file;
    private long filePos;

    private ByteBuffer incomingData; //ArrayDeque mb
    private ByteBuffer answer;
    private Boolean NeedAnsWriting;

    ClientContext(SocketChannel s, Selector selc){
        clear();
        socket = s;
        sel = selc;
        try {
            socketUdp = DatagramChannel.open();
            socketUdp.bind(new InetSocketAddress(((InetSocketAddress)socket.getLocalAddress()).getHostString(),
                    ((InetSocketAddress)socket.getRemoteAddress()).getPort() + 1));
            //socketUdp.setOption(StandardSocketOptions.SO_SNDBUF, 1024);
            //socketUdp.setOption(StandardSocketOptions.SO_RCVBUF, 1024);
        } catch (IOException e) {
            e.printStackTrace();
        }
        change(true);
    }

    /**
     * Переключение между двумя сокетами tcp/udp
     * @param tcp
     */
    private void change(boolean tcp){
        if(tcp){
            try {
                socket.configureBlocking(false);
            } catch (IOException e) {
                e.printStackTrace();
            }
            try {
                tcpKey = socket.register(sel, SelectionKey.OP_READ | SelectionKey.OP_WRITE);
            } catch (ClosedChannelException e) {
                e.printStackTrace();
            }
            if(udpKey != null){
                udpKey.cancel();
                udpKey = null;
            }
        } else{
            try {
                socketUdp.configureBlocking(false);
            } catch (IOException e) {
                e.printStackTrace();
            }
            try {
                udpKey = socketUdp.register(sel, SelectionKey.OP_READ | SelectionKey.OP_WRITE);
            } catch (ClosedChannelException e) {
                e.printStackTrace();
            }
            if(tcpKey != null){
                tcpKey.cancel();
                tcpKey = null;
            }
        }
    }

    private void clear(){
        argsSize = 0;
        if( file != null)
            try {
                file.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        file = null;
        filename = null;
        filePos = 0;
        action = Action.None;
        incomingData = ByteBuffer.allocate(2048);
        answer = ByteBuffer.allocate(1024);
        NeedAnsWriting = false;

    }
    void addIncomingData(ByteBuffer bb){
        bb.flip();
        incomingData.put(bb.slice().duplicate());
        incomingData.flip();
    }

    /**
     * Отсылка ответа по необходимости
     * @throws IOException
     */
    void sendAnswer() throws IOException {
        if(NeedAnsWriting) {
            if (file == null) {     //если не файл то это echo или time для которых нужно просто отослать ответ
                answer.flip();
                socket.write(answer);
                if (!answer.hasRemaining()) clear();
            } else if (action == Action.FileContent){   //содержимое файла tcp
                if(!answer.hasRemaining()) {
                    answer.clear();
                    if(answer.position() == 0){
                    byte[] buf = new byte[1024];
                    if ((argsSize = file.read(buf, 0, 1024)) != -1) {
                        answer.put(buf, 0, argsSize);
                        answer.flip();
                        socket.write(answer);
                    } else clear();}
                }
                else socket.write(answer);
            } else if(action == Action.FileUdpContent){     //содежимое файла udp
                if(!answer.hasRemaining()) {
                    answer.clear();
                    byte[] buf = new byte[1020];
                    if ((argsSize = file.read(buf, 0, 1020)) != -1) {
                        answer.putInt((int) filePos);       //пакет udp содержит еще и номер
                        answer.put(buf, 0, argsSize);
                        answer.flip();
                        socketUdp.write(answer);
                        filePos++;
                    }
                }
                else socketUdp.write(answer);
            }else if(action == Action.File || action == Action.FileUdp){ //отсылаем размер файла и переходим к его содержимому
                answer.flip();
                socket.write(answer);
                answer.clear();
                answer.compact();
                if(action == Action.File)
                    action = Action.FileContent;
                else{
                    action = Action.FileUdpContent;
                    socketUdp.connect(socket.getRemoteAddress());
                    change(false);
                }
            }
        }
    }

    /**
     * Все операции кроме передачи файла по Udp(FileUdpContent) действуют по
     * принципу: подготовить ответ и выставить флаг на его отправку
     *
     * При передаче файла по udp мы сталкиваемся с проблемой потери пакетов
     * @return
     */
    boolean processed(){
        /**
         * Собственно при передаче файла по Udp вместе с его отправкой
         * принимаем еще и номер ожидаемого пакета, если произошла потеря пакетов
         */
        if( action == Action.FileUdpContent)
        {
            filePos = incomingData.getInt();
            incomingData.compact();
            try {
                file = new DataInputStream(new FileInputStream("./" + filename));
                file.skipBytes((int)filePos*1020);
            } catch (IOException e) {
                e.printStackTrace();
            }
            if(filePos == -1) {
                clear();
                change(true);
                try {
                    socketUdp.disconnect();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            return false;
        }

        if ( action == Action.None )
        {
            if(!incomingData.hasRemaining())
                return false;
            action = Action.values()[incomingData.get()];
        }

        if ( action == Action.File || action == Action.FileUdp)
        {
            argsSize = incomingData.getInt();
            byte[] buf = new byte[argsSize];
            incomingData.get(buf, 0, argsSize);
            String filename = new String(buf);
            this.filename = filename;
            try {
                file = new DataInputStream(new FileInputStream("./" + filename));
            } catch (FileNotFoundException e) {
                System.out.println(e.getMessage());
            }
            answer.putLong(new File("./" + filename).length());

            incomingData = incomingData.compact();
            NeedAnsWriting = true;
            return false;
        }

        if ( action == Action.Echo )
        {
            if(answer.position() == 0){
                argsSize = incomingData.getInt();
            }
            while ( incomingData.hasRemaining() && answer.position() < argsSize)
            {
                answer.put(incomingData.get());
            }
            if(answer.position() == argsSize){
                incomingData = incomingData.compact();
                NeedAnsWriting = true;
                action = Action.None;
            }
            return false;
        }

        if ( action == Action.Time )
        {
            incomingData.getInt();
            answer.put(java.util.Calendar.getInstance().getTime().toString().getBytes());
            NeedAnsWriting = true;
            incomingData = incomingData.compact();
            action = Action.None;
            return false;
        }
        if( action == Action.Close) return false;
        return true;
    }

    void dropKeys(){
        if(tcpKey != null)
            tcpKey.cancel();
        tcpKey = null;
        if(udpKey != null)
            udpKey.cancel();
        udpKey = null;
    }

    Action getAction() {
        return action;
    }

    @Override
    protected void finalize() throws Throwable {
        clear();
        super.finalize();
    }
}