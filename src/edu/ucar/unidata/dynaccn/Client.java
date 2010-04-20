/**
 * Copyright 2010 University Corporation for Atmospheric Research.  All rights reserved.
 * See file LICENSE in the top-level source directory for licensing information.
 */
package edu.ucar.unidata.dynaccn;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

/**
 * Connects to a remote server and exchanges data.
 * 
 * @author Steven R. Emmerson
 */
final class Client implements Callable<Void> {
    /**
     * The connection to the remote server.
     */
    private final Connection connection = new Connection();
    /**
     * Pathname of the root of the file hierarchy.
     */
    private final File       dir;
    /**
     * Pathname of the directory into which to put received files.
     */
    private final Predicate  predicate;

    /**
     * Constructs from the Internet address of the remote server. Executes
     * immediately. NOTE: Connections are made to the server in sequence from
     * the lowest port number to the highest.
     * 
     * @param inetAddress
     *            The Internet address of the remote server.
     * @param dir
     *            Pathname of the root of the file hierarchy.
     * @param predicate
     *            The predicate for selecting locally-desired data.
     * @throws IOException
     *             if an I/O error occurs while attempting to connect to the
     *             remote server.
     * @throws NullPointerException
     *             if {@code inetAddress == null || dir == null || predicate ==
     *             null}.
     */
    Client(final InetAddress inetAddress, final String dir,
            final Predicate predicate) throws IOException {
        if (null == predicate) {
            throw new NullPointerException();
        }

        this.dir = new File(dir);
        this.predicate = predicate;

        for (int i = 0; i < Connection.SOCKET_COUNT; i++) {
            final int port = Server.START_PORT + i;

            try {
                connection.add(new Socket(inetAddress, port));
            }
            catch (final IOException e) {
                connection.close();
                throw (IOException) new IOException("Couldn't connect to port "
                        + port + " on host " + inetAddress).initCause(e);
            }
        }
    }

    @Override
    public Void call() throws IOException, InterruptedException,
            ExecutionException {
        try {
            return new Peer(connection, dir, predicate).call();
        }
        finally {
            connection.close();
        }
    }
}