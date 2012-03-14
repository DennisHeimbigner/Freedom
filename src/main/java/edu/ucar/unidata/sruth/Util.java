/**
 * Copyright 2012 University Corporation for Atmospheric Research.  All rights
 * reserved.  See file LICENSE.txt in the top-level directory for licensing
 * information.
 */
package edu.ucar.unidata.sruth;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class containing useful static methods and fields.
 * 
 * @author Steven R. Emmerson
 */
final class Util {
    /**
     * The name of the package.
     */
    static final String PACKAGE_NAME;

    static {
        final String packagePath = Util.class.getPackage().getName();
        PACKAGE_NAME = packagePath.substring(packagePath.lastIndexOf('.') + 1)
                .toUpperCase();
    }

    /**
     * Launder a {@link Throwable}. If the throwable is an error, then throw it;
     * if it's a {@link RuntimeException}, then return it; otherwise, throw the
     * runtime-exception {@link IllegalStateException} to indicate a logic
     * error.
     */
    static RuntimeException launderThrowable(final Throwable t) {
        if (t instanceof RuntimeException) {
            return (RuntimeException) t;
        }
        else if (t instanceof Error) {
            throw (Error) t;
        }
        else {
            throw new IllegalStateException("Unhandled checked exception", t);
        }
    }

    /**
     * Returns the single-string form of a command.
     * 
     * @param command
     *            The command arguments.
     * @return The command arguments concatenated together with spaces.
     */
    static String getCommand(final List<String> command) {
        return formatCommand(command.toArray(new String[command.size()]));
    }

    /**
     * Formats an individual-arguments form of a command into a single-string
     * form.
     * 
     * @param command
     *            The command arguments.
     * @return The command arguments concatenated together with spaces.
     */
    static String formatCommand(final String[] command) {
        final StringBuilder builder = new StringBuilder();
        for (final String arg : command) {
            builder.append(arg.replace(" ", "\\ ")); // escape spaces
            builder.append(" ");
        }
        if (builder.length() > 0) {
            builder.setLength(builder.length() - 1);
        }
        return builder.toString();
    }

    /**
     * Opens a URL or file. Tries to open the specification first as a URL and
     * then as a file pathname.
     * 
     * @param spec
     *            The URL or pathname specification.
     * @return A connection to the URL or file.
     * @throws IOException
     *             if the URL or file can't be opened.
     */
    static InputStream openUrlOrFile(final String spec) throws IOException {
        InputStream input = null;
        try {
            final URL url = new URL(spec);
            input = url.openStream();
        }
        catch (final MalformedURLException e) {
            input = new FileInputStream(spec);
        }
        catch (final IOException e) {
            input = new FileInputStream(spec);
        }
        return input;
    }

    /**
     * Decodes an object that's encoded in a URL or file. Opens the URL or file
     * via {@link #openUrlOrFile(String)}, uses the given decoder to decode the
     * object, and then closes the URL or file.
     * 
     * @param spec
     *            Specification of the URL or file.
     * @return An object corresponding to the URL or file.
     * @throws IOException
     *             if an I/O error occurs.
     */
    static <T> T decodeUrlOrFile(final String spec, final Decoder<T> decoder)
            throws IOException {
        final InputStream input = openUrlOrFile(spec);
        try {
            return decoder.decode(input);
        }
        finally {
            try {
                input.close();
            }
            catch (final IOException ignored) {
            }
        }
    }

    /**
     * Returns the object corresponding to the deserialization of a byte-array.
     * 
     * @param buf
     *            The byte-array to be deserialized.
     * @param offset
     *            The offset into the array of the first byte to read.
     * @param length
     *            The number of bytes to read.
     * @return The corresponding object.
     * @throws ClassNotFoundException
     *             if the byte-array specifies an unknown class.
     * @throws IOException
     *             if an I/O error occurs.
     * @throws NullPointerException
     *             if {@code buf == null}.
     */
    static Object deserialize(final byte[] buf, final int offset,
            final int length) throws IOException, ClassNotFoundException {
        final InputStream inputStream = new ByteArrayInputStream(buf, offset,
                length);
        final ObjectInputStream ois = new ObjectInputStream(inputStream);
        return ois.readObject();
    }

    /**
     * Serializes an object into a byte array.
     * 
     * @param obj
     *            The object to be serialized
     * @return A newly-allocated byte array containing the serialized object.
     * @throws IOException
     *             if an I/O error occurs.
     */
    static byte[] serialize(final Serializable obj) throws IOException {
        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        final ObjectOutputStream oos = new ObjectOutputStream(outputStream);
        oos.writeObject(obj);
        return outputStream.toByteArray();
    }

    /**
     * Returns the logger for this package.
     */
    static Logger getLogger() {
        return LoggerFactory.getLogger(Util.class);
    }
}
