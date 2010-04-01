/*
 * Copyright (c) 2009-2010, Konrad-Zuse-Zentrum fuer Informationstechnik Berlin
 * 
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without 
 * modification, are permitted provided that the following conditions are met:
 * 
 * Redistributions of source code must retain the above copyright notice, this 
 * list of conditions and the following disclaimer.
 * Redistributions in binary form must reproduce the above copyright notice, 
 * this list of conditions and the following disclaimer in the documentation 
 * and/or other materials provided with the distribution.
 * Neither the name of the Konrad-Zuse-Zentrum fuer Informationstechnik Berlin 
 * nor the names of its contributors may be used to endorse or promote products 
 * derived from this software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" 
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE 
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE 
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE 
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR 
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF 
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS 
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN 
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) 
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE 
 * POSSIBILITY OF SUCH DAMAGE.
 */
/*
 * AUTHORS: Bjoern Kolbeck (ZIB)
 */
package org.xtreemfs.foundation.oncrpc.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import org.xtreemfs.foundation.LifeCycleThread;
import org.xtreemfs.foundation.SSLOptions;
import org.xtreemfs.foundation.buffer.BufferPool;
import org.xtreemfs.foundation.buffer.ReusableBuffer;
import org.xtreemfs.foundation.logging.Logging;
import org.xtreemfs.foundation.logging.Logging.Category;
import org.xtreemfs.foundation.oncrpc.channels.ChannelIO;
import org.xtreemfs.foundation.oncrpc.channels.SSLChannelIO;
import org.xtreemfs.foundation.oncrpc.channels.SSLHandshakeOnlyChannelIO;
import org.xtreemfs.foundation.oncrpc.server.RPCNIOSocketServer;
import org.xtreemfs.foundation.oncrpc.utils.ONCRPCException;
import org.xtreemfs.foundation.oncrpc.utils.ONCRPCRecordFragmentHeader;
import org.xtreemfs.foundation.oncrpc.utils.ONCRPCResponseHeader;
import org.xtreemfs.foundation.oncrpc.utils.XDRUnmarshaller;
import org.xtreemfs.foundation.oncrpc.utils.exceptions.ONCRPCProtocolException;
import org.xtreemfs.foundation.util.OutputUtils;
/**
 * 
 * @author bjko
 */
public class RPCNIOSocketClient extends LifeCycleThread {

    public static boolean ENABLE_STATISTICS = false;

    
    /**
     * Maxmimum tries to reconnect to the server
     */
    public static final int                                MAX_RECONNECT       = 4;
    
    /**
     * milliseconds between two timeout checks
     */
    public static final int                                TIMEOUT_GRANULARITY = 250;
    
    private final Map<InetSocketAddress, ServerConnection> connections;
    
    private final int                                      requestTimeout;
    
    private final int                                      connectionTimeout;
    
    private long                                           lastCheck;
    
    private final Selector                                 selector;
    
    private volatile boolean                               quit;
    
    private final SSLOptions                               sslOptions;
    
    private final AtomicInteger                            transactionId;
    
    private final ConcurrentLinkedQueue<ServerConnection>  toBeEstablished;

    private final RemoteExceptionParser[]                  interfaces;

    /**
     * on some platforms (e.g. FreeBSD 7.2 with openjdk6) Selector.select(int timeout)
     * returns immediately. If this problem is detected, the thread waits 25ms after each
     * invocation to avoid excessive CPU consumption. See also issue #75
     */
    private boolean                                        brokenSelect;
    
    public RPCNIOSocketClient(SSLOptions sslOptions, int requestTimeout, int connectionTimeout, RemoteExceptionParser[] interfaces)
        throws IOException {
        super("RPC Client");
        this.interfaces = interfaces;
        if (requestTimeout >= connectionTimeout - TIMEOUT_GRANULARITY * 2) {
            throw new IllegalArgumentException(
                "request timeout must be smaller than connection timeout less " + TIMEOUT_GRANULARITY * 2
                    + "ms");
        }
        this.requestTimeout = requestTimeout;
        this.connectionTimeout = connectionTimeout;
        connections = new HashMap<InetSocketAddress, ServerConnection>();
        selector = Selector.open();
        this.sslOptions = sslOptions;
        quit = false;
        transactionId = new AtomicInteger((int) (Math.random() * 1e6 + 1.0));
        toBeEstablished = new ConcurrentLinkedQueue<ServerConnection>();
    }
    
    public void sendRequest(RPCResponseListener listener, InetSocketAddress server, int programId,
        int versionId, int procedureId, yidl.runtime.Object message) {
        sendRequest(listener, server, programId, versionId, procedureId, message, null);
    }
    
    public void sendRequest(RPCResponseListener listener, InetSocketAddress server, int programId,
        int versionId, int procedureId, yidl.runtime.Object message, Object attachment) {
        sendRequest(listener, server, programId, versionId, procedureId, message, attachment, null);
    }
    
    public void sendRequest(RPCResponseListener listener, InetSocketAddress server, int programId,
        int versionId, int procedureId, yidl.runtime.Object message, Object attachment, yidl.runtime.Object credentials) {
        ONCRPCRequest rec = new ONCRPCRequest(listener, this.transactionId.getAndIncrement(), programId,
            versionId, procedureId, message, attachment, credentials);
        try {
            sendRequest(server, rec);
        } catch (Throwable e) { // CancelledKeyException, RuntimeException (caused by missing TimeSyncThread)
            //e.printStackTrace();
            listener.requestFailed(rec, new IOException(e));
        } 
    }
    
    private void sendRequest(InetSocketAddress server, ONCRPCRequest request) {
        if (Logging.isDebug()) {
            Logging.logMessage(Logging.LEVEL_DEBUG, Category.net, this, "sending request %s no %d", request
                    .toString(), transactionId.get());
        }
        // get connection
        ServerConnection con = null;
        synchronized (connections) {
            con = connections.get(server);
            if (con == null) {
                con = new ServerConnection(server);
                connections.put(server, con);
            }
        }
        synchronized (con) {
            boolean isEmpty = con.getSendQueue().isEmpty();
            request.queued();
            con.useConnection();
            con.getSendQueue().add(request);
            if (!con.isConnected()) {
                establishConnection(server, con);

            } else {
                if (isEmpty) {
                    final SelectionKey key = con.getChannel().keyFor(selector);
                    key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
                }
                selector.wakeup();
            }
        }
    }
    
    public void run() {

        brokenSelect = false;
        try {
            long now = System.currentTimeMillis();
            selector.select(20);
            long duration = System.currentTimeMillis()-now;
            if (duration < 15) {
                Logging.logMessage(Logging.LEVEL_WARN, this,"detected broken select(int timeout)!");
                brokenSelect = true;
            }
        } catch (Throwable th) {
            Logging.logMessage(Logging.LEVEL_DEBUG, this,"could not check Selector for broken select(int timeout): "+th);
        }
        
        notifyStarted();
        lastCheck = System.currentTimeMillis();
        
        while (!quit) {
            
            int numKeys = 0;
            
            try {
                numKeys = selector.select(TIMEOUT_GRANULARITY);
            } catch (CancelledKeyException ex) {
                Logging.logMessage(Logging.LEVEL_WARN, Category.net, this, "Exception while selecting: %s",
                    ex.toString());
                continue;
            } catch (IOException ex) {
                Logging.logMessage(Logging.LEVEL_WARN, Category.net, this, "Exception while selecting: %s",
                    ex.toString());
                continue;
            }
            
            if (!toBeEstablished.isEmpty()) {
                while (true) {
                    ServerConnection con = toBeEstablished.poll();
                    if (con == null) {
                        break;
                    }
                    try {
                        con.getChannel().register(selector,
                            SelectionKey.OP_CONNECT | SelectionKey.OP_WRITE | SelectionKey.OP_READ, con);
                    } catch (ClosedChannelException ex) {
                        closeConnection(con.getChannel().keyFor(selector), ex);
                    }
                }
                toBeEstablished.clear();
            }
            
            if (numKeys > 0) {
                // fetch events
                Set<SelectionKey> keys = selector.selectedKeys();
                Iterator<SelectionKey> iter = keys.iterator();
                
                // process all events
                while (iter.hasNext()) {
                    try {
                        SelectionKey key = iter.next();
                        
                        // remove key from the list
                        iter.remove();
                        
                        if (key.isConnectable()) {
                            connectConnection(key);
                        }
                        if (key.isReadable()) {
                            readConnection(key);
                        }
                        if (key.isWritable()) {
                            writeConnection(key);
                        }
                    } catch (CancelledKeyException ex) {
                        continue;
                    }
                }
            } else {
                if (brokenSelect) {
                    try {
                        sleep(25);
                    } catch (InterruptedException ex) {
                        break;
                    }
                }

            }
            checkForTimers();
        }
        
        synchronized (connections) {
            for (ServerConnection con : connections.values()) {
                synchronized (con) {
                    for (ONCRPCRequest rq : con.getSendQueue()) {
                        rq.getListener().requestFailed(rq, new IOException("client was shut down"));
                    }
                    for (ONCRPCRequest rq : con.getRequests().values()) {
                        rq.getListener().requestFailed(rq, new IOException("client was shut down"));
                    }
                    try {
                        if (con.getChannel() != null)
                            con.getChannel().close();
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                }
            }
        }
        
        notifyStopped();
    }
    
    private void establishConnection(InetSocketAddress server, ServerConnection con) {
        
        if (con.canReconnect()) {
            if (Logging.isDebug()) {
                Logging.logMessage(Logging.LEVEL_DEBUG, Category.net, this, "connect to %s", server
                        .toString());
            }
            ChannelIO channel;
            try {
                if (sslOptions == null) { // no SSL
                    channel = new ChannelIO(SocketChannel.open());
                } else {
                    if (sslOptions.isFakeSSLMode()) {
                        channel = new SSLHandshakeOnlyChannelIO(SocketChannel.open(), sslOptions, true);
                    } else {
                        channel = new SSLChannelIO(SocketChannel.open(), sslOptions, true);
                    }
                }
                channel.configureBlocking(false);
                channel.socket().setTcpNoDelay(true);
                channel.socket().setReceiveBufferSize(256 * 1024);
                channel.connect(server);
                con.setChannel(channel);
                toBeEstablished.add(con);
                selector.wakeup();
                if (Logging.isDebug())
                    Logging.logMessage(Logging.LEVEL_DEBUG, Category.net, this, "connection established");
            } catch (Exception ex) {
                if (Logging.isDebug()) {
                    Logging.logMessage(Logging.LEVEL_DEBUG, Category.net, this, "cannot contact server %s",
                        con.getEndpoint().toString());
                }
                con.connectFailed();
                for (ONCRPCRequest rq : con.getSendQueue()) {
                    rq.getListener().requestFailed(rq, new IOException("server '"+con.getEndpoint()+"' not reachable", ex));
                }
                con.getSendQueue().clear();
                
            }
        } else {
            if (Logging.isDebug()) {
                Logging.logMessage(Logging.LEVEL_DEBUG, Category.net, this,
                    "reconnect to server still blocked %s", con.getEndpoint().toString());
            }
            synchronized (con) {
                for (ONCRPCRequest rq : con.getSendQueue()) {
                    rq.getListener().requestFailed(rq, new IOException("server '"+con.getEndpoint()+"' not reachable"));
                }
                con.getSendQueue().clear();
            }
        }
        
    }
    
    private void readConnection(SelectionKey key) {
        final ServerConnection con = (ServerConnection) key.attachment();
        final ChannelIO channel = con.getChannel();
        
        try {
            
            if (!channel.isShutdownInProgress()) {
                if (channel.doHandshake(key)) {
                    
                    while (true) {
                        final ByteBuffer respFragHdr = con.getResponseFragHdr();
                        if (respFragHdr.hasRemaining()) {
                            
                            // do the read operation
                            final int numBytesRead = RPCNIOSocketServer.readData(key, channel, respFragHdr);
                            if (numBytesRead == -1) {
                                // connection closed
                                closeConnection(key, new IOException("server closed connection"));
                                return;
                            }
                            if (respFragHdr.hasRemaining()) {
                                // not enough data...
                                break;
                            } else {
                                // receive fragment
                                respFragHdr.position(0);
                                final int fragHdrInt = respFragHdr.getInt();
                                final int fragmentSize = ONCRPCRecordFragmentHeader
                                        .getFragmentLength(fragHdrInt);
                                final boolean isLastFragment = ONCRPCRecordFragmentHeader
                                        .isLastFragment(fragHdrInt);
                                assert (fragmentSize > 0) : "fragment has wrong size: " + fragmentSize;
                                ReusableBuffer fragment = BufferPool.allocate(fragmentSize);
                                con.addResponseFragment(fragment);
                                con.setLastResponseFragReceived(isLastFragment);
                            }
                        } else {
                            // read payload
                            final ReusableBuffer buf = con.getCurrentResponseFragment();
                            final int numBytesRead = RPCNIOSocketServer.readData(key, channel, buf
                                    .getBuffer());
                            if (numBytesRead == -1) {
                                // connection closed
                                closeConnection(key, new IOException("server closed connection"));
                                return;
                            }
                            if (buf.hasRemaining()) {
                                // not enough data to read...
                                break;
                            } else {
                                if (con.isLastResponseFragReceived()) {
                                    // request is complete
                                    assembleResponse(key, con);
                                } else {
                                    // next fragment
                                }
                                respFragHdr.position(0);
                            }
                        }
                    }
                }
            }
        } catch (IOException ex) {
            // simply close the connection
            if (Logging.isDebug()) {
                Logging.logMessage(Logging.LEVEL_DEBUG, Category.net, this, OutputUtils
                        .stackTraceToString(ex));
            }
            closeConnection(key, new IOException("server closed connection", ex));
        }
    }
    
    private void assembleResponse(SelectionKey key, ServerConnection con) {
        // parse the ONCRPCHeader to get XID
        
        if (Logging.isDebug())
            Logging.logMessage(Logging.LEVEL_DEBUG, Category.net, this, "assemble response");

        ONCRPCResponseHeader hdr = null;
        ReusableBuffer firstFragment = null;
        try {
            firstFragment = con.getResponseFragments().get(0);
            firstFragment.position(0);
            hdr = new ONCRPCResponseHeader();
            hdr.unmarshal(new XDRUnmarshaller(firstFragment));
            
        } catch (Exception ex) {
            if (Logging.isDebug()) {
                Logging.logMessage(Logging.LEVEL_DEBUG, Category.net, this,
                    "received invalid response from %s", con.getChannel().socket().getRemoteSocketAddress()
                            .toString());
                Logging.logMessage(Logging.LEVEL_DEBUG, Category.net, this, OutputUtils
                        .stackTraceToString(ex));
            }
            closeConnection(key, new IOException("invalid response header sent"));
            return;
        }
        final int xid = hdr.getXID();
        ONCRPCRequest rec = con.getRequest(xid);
        if (rec == null) {
            Logging.logMessage(Logging.LEVEL_WARN, Category.net, this,
                "received response for unknown request with XID %d", xid);
            for (ReusableBuffer frag : con.getResponseFragments()){
                BufferPool.free(frag);
            }
            con.clearResponseFragments();
            return;
        }
        if (ENABLE_STATISTICS) {
            rec.endT = System.nanoTime();
            con.bytesRX += firstFragment.capacity();
        }
        rec.setResponseFragments(con.getResponseFragments());
        con.clearResponseFragments();

        final int accept_stat = hdr.getAcceptStat();

        // check for result and exception stuff
        if (accept_stat == ONCRPCResponseHeader.ACCEPT_STAT_SUCCESS) {
            rec.getListener().responseAvailable(rec);
        } else {

            if (accept_stat <= ONCRPCResponseHeader.ACCEPT_STAT_SYSTEM_ERR) {
                //ONC RPC error message
                rec.getListener().requestFailed(rec, ONCRPCProtocolException.getException(accept_stat));
            } else {
                //exception
                try {
                    ONCRPCException exception = null;
                    for (RemoteExceptionParser interf : interfaces) {
                        if (interf.canParseException(accept_stat)) {
                            exception = interf.parseException(accept_stat, new XDRUnmarshaller(firstFragment));
                        }
                    }
                    if (exception == null) {
                        Logging.logMessage(Logging.LEVEL_ERROR, this,"received invalid remote exception id %d",accept_stat);
                        rec.getListener().requestFailed(rec, new IOException("received invalid remote exception with id "+accept_stat));
                    } else {
                        rec.getListener().remoteExceptionThrown(rec, exception);
                    }
                } catch (IOException ex) {
                    rec.getListener().requestFailed(rec, ex);
                } catch (Throwable ex) {
                    rec.getListener().requestFailed(rec, new IOException("invalid exception data received: "+ex));
                }

                    /*if (accept_stat >= DIRInterface.getVersion() && (accept_stat < DIRInterface.getVersion()+100)) {
                        exception = DIRInterface.createException(accept_stat);
                    } else if (accept_stat >= MRCInterface.getVersion() && (accept_stat < MRCInterface.getVersion()+100)) {
                        exception = MRCInterface.createException(accept_stat);
                    } else if (accept_stat >= OSDInterface.getVersion() && (accept_stat < OSDInterface.getVersion()+100)) {
                        exception = OSDInterface.createException(accept_stat);
                    } else {
                        throw new Exception();
                    }*/

            }
        }
    }
    
    private void writeConnection(SelectionKey key) {
        final ServerConnection con = (ServerConnection) key.attachment();
        final ChannelIO channel = con.getChannel();
        
        try {
            
            if (!channel.isShutdownInProgress()) {
                if (channel.doHandshake(key)) {
                    
                    while (true) {
                        ONCRPCRequest send = con.getSendRequest();
                        if (send == null) {
                            synchronized (con) {
                                send = con.getSendQueue().poll();
                                if (send == null) {
                                    // no more responses, stop writing...
                                    key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
                                    break;
                                }
                                con.setSendRequest(send);
                            }
                            // send request as single fragment
                            // create fragment header
                            final ByteBuffer fragHdrBuffer = con.getRequestFragHdr();
                            final int fragmentSize = send.getRequestSize();
                            final int fragHdrInt = ONCRPCRecordFragmentHeader.getFragmentHeader(fragmentSize,
                                true);
                            fragHdrBuffer.position(0);
                            fragHdrBuffer.putInt(fragHdrInt);
                            fragHdrBuffer.position(0);
                            if (ENABLE_STATISTICS) {
                                send.startT = System.nanoTime();
                                con.bytesTX += 4+fragmentSize;
                            }
                        }
                        
                        final ByteBuffer fragHdrBuffer = con.getRequestFragHdr();
                        if (fragHdrBuffer.hasRemaining()) {
                            // send header fragment
                            final int numBytesWritten = RPCNIOSocketServer.writeData(key, channel,
                                fragHdrBuffer);
                            if (numBytesWritten == -1) {
                                // connection closed
                                closeConnection(key, new IOException("server closed connection"));
                                return;
                            }
                            if (fragHdrBuffer.hasRemaining()) {
                                // not enough data...
                                break;
                            }
                        } else {
                            // send payload
                            final ReusableBuffer buf = send.getCurrentRequestBuffer();
                            final int numBytesWritten = RPCNIOSocketServer.writeData(key, channel, buf
                                    .getBuffer());
                            if (numBytesWritten == -1) {
                                // connection closed
                                closeConnection(key, new IOException("server closed connection"));
                                return;
                            }
                            if (buf.hasRemaining()) {
                                // not enough data...
                                break;
                            } else {
                                if (!send.isLastRequestBuffer()) {
                                    send.nextRequestBuffer();
                                    continue;
                                } else {
                                    con.addRequest(send.getXID(), send);
                                    con.setSendRequest(null);
                                    if (Logging.isDebug()) {
                                        Logging.logMessage(Logging.LEVEL_DEBUG, Category.net, this,
                                            "sent request %d to %s", send.getXID(), con.getEndpoint()
                                                    .toString());
                                    }
                                }
                                // otherwise the request is complete
                            }
                        }
                    }
                }
            }
        } catch (IOException ex) {
            // simply close the connection
            if (Logging.isDebug()) {
                Logging.logMessage(Logging.LEVEL_DEBUG, Category.net, this, OutputUtils
                        .stackTraceToString(ex));
            }
            closeConnection(key, new IOException("server closed connection", ex));
        }
    }
    
    private void connectConnection(SelectionKey key) {
        final ServerConnection con = (ServerConnection) key.attachment();
        final ChannelIO channel = con.getChannel();
        
        try {
            if (channel.isConnectionPending()) {
                channel.finishConnect();
            }
            synchronized (con) {
                if (!con.getSendQueue().isEmpty()) {
                    key.interestOps(SelectionKey.OP_WRITE | SelectionKey.OP_READ);
                }
            }
            con.connected();
            if (Logging.isDebug()) {
                Logging.logMessage(Logging.LEVEL_DEBUG, Category.net, this, "connected from %s to %s", con
                        .getChannel().socket().getLocalSocketAddress().toString(), con.getEndpoint()
                        .toString());
            }
        } catch (IOException ex) {
            con.connectFailed();
            String endpoint;
            try {
                endpoint = con.getEndpoint().toString();
            } catch (Exception ex2) {
                endpoint = "unknown";
            }
            closeConnection(key, new IOException("server '"+endpoint+"' not reachable", ex));
        }
        
    }
    
    private void closeConnection(SelectionKey key, IOException exception) {
        final ServerConnection con = (ServerConnection) key.attachment();
        final ChannelIO channel = con.getChannel();
        
        List<ONCRPCRequest> cancelRq = new LinkedList<ONCRPCRequest>();
        synchronized (con) {
            // remove the connection from the selector and close socket
            try {
                key.cancel();
                channel.close();
            } catch (Exception ex) {
            }
            cancelRq.addAll(con.getRequests().values());
            cancelRq.addAll(con.getSendQueue());
            con.getRequests().clear();
            con.getSendQueue().clear();
            con.setChannel(null);
        }
        
        // notify listeners
        for (ONCRPCRequest rq : cancelRq) {
            rq.getListener().requestFailed(rq, exception);
        }
        
        if (Logging.isDebug()) {
            Logging.logMessage(Logging.LEVEL_DEBUG, Category.net, this, "closing connection to %s", con
                    .getEndpoint().toString());
        }
    }
    
    private void checkForTimers() {
        // poor man's timer
        long now = System.currentTimeMillis();
        if (now >= lastCheck + TIMEOUT_GRANULARITY) {
            // check for timed out requests
            synchronized (connections) {
                Iterator<ServerConnection> conIter = connections.values().iterator();
                while (conIter.hasNext()) {
                    final ServerConnection con = conIter.next();
                    
                    if (con.getLastUsed() < (now - connectionTimeout)) {
                        if (Logging.isDebug()) {
                            Logging.logMessage(Logging.LEVEL_DEBUG, Category.net, this,
                                "removing idle connection");
                        }
                        try {
                            conIter.remove();
                            closeConnection(con.getChannel().keyFor(selector), null);
                        } catch (Exception ex) {
                        }
                    } else {
                        // check for request timeout
                        List<ONCRPCRequest> cancelRq = new LinkedList<ONCRPCRequest>();
                        synchronized (con) {
                            Iterator<ONCRPCRequest> iter = con.getRequests().values().iterator();
                            while (iter.hasNext()) {
                                final ONCRPCRequest rq = iter.next();
                                if (rq.getTimeQueued() + requestTimeout < now) {
                                    cancelRq.add(rq);
                                    iter.remove();
                                }
                            }
                            iter = con.getSendQueue().iterator();
                            while (iter.hasNext()) {
                                final ONCRPCRequest rq = iter.next();
                                if (rq.getTimeQueued() + requestTimeout < now) {
                                    cancelRq.add(rq);
                                    iter.remove();
                                } else {
                                    // requests are ordered :-)
                                    break;
                                }
                            }
                        }
                        for (ONCRPCRequest rq : cancelRq) {
                            rq.getListener().requestFailed(rq, new IOException("request timed out"));
                        }
                        
                    }
                }
                
                lastCheck = now;
            }
        }
    }
    
    public void shutdown() {
        this.quit = true;
        this.interrupt();
    }

    /**
     * Returns the number of bytes received and transferred from/to a server.
     * @param server
     * @return an array with the number of bytes received [0] and sent [1]
     */
    public long[] getTransferStats(InetSocketAddress server) {
        ServerConnection con = null;
         synchronized (connections) {
             con = connections.get(server);
         }
        if (con == null)
            return null;
        else
            return new long[]{con.bytesRX,con.bytesTX};
    }
}