/**
 * Copyright 2010 University Corporation for Atmospheric Research.  All rights
 * reserved. See file LICENSE in the top-level source directory for licensing
 * information.
 */
package edu.ucar.unidata.dynaccn;

import java.io.IOException;
import java.io.ObjectInputStream;

/**
 * Receives requests for data and acts upon them.
 * 
 * Instances are thread-safe.
 * 
 * @author Steven R. Emmerson
 */
final class RequestReceiver extends Receiver<Request> {
    /**
     * Constructs the local peer.
     * 
     * @param peer
     *            The local peer.
     */
    RequestReceiver(final Peer peer) {
        super(peer, Request.class);
    }

    @Override
    protected ObjectInputStream getInputStream(final Peer peer)
            throws IOException {
        return peer.getRequestInputStream();
    }

    @Override
    protected boolean process(final Request request) {
        System.out.println("Received request: " + request);
        return true;
    }
}
