/**
 * Copyright 2012 University Corporation for Atmospheric Research.  All rights
 * reserved.  See file LICENSE.txt in the top-level directory for licensing
 * information.
 */
package edu.ucar.unidata.sruth;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOError;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.io.Serializable;
import java.io.StreamCorruptedException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.prefs.Preferences;

import net.jcip.annotations.GuardedBy;
import net.jcip.annotations.NotThreadSafe;
import net.jcip.annotations.ThreadSafe;

import org.slf4j.Logger;

/**
 * An archive of files.
 * 
 * Instances are thread-safe.
 * 
 * @author Steven R. Emmerson
 */
@ThreadSafe
final class Archive {
    /**
     * Factory for obtaining an object to manage the distribution of
     * tracker-specific administrative files.
     * 
     * Instances are thread-safe.
     * 
     * @author Steven R. Emmerson
     */
    @ThreadSafe
    private final class DistributedTrackerFilesFactory {
        /**
         * The map from a tracker address to an instance of a
         * {@link DistributeTrackerFiles}.
         */
        private final ConcurrentMap<InetSocketAddress, DistributedTrackerFiles> instances = new ConcurrentHashMap<InetSocketAddress, DistributedTrackerFiles>();

        /**
         * Returns an object for managing the distribution of tracker-specific
         * administrative files.
         * 
         * @param trackerAddress
         *            The address of the tracker.
         * @return An object for managing the distribution of tracker-specific
         *         administrative files.
         */
        DistributedTrackerFiles getInstance(
                final InetSocketAddress trackerAddress) {
            DistributedTrackerFiles instance = instances.get(trackerAddress);
            if (instance == null) {
                instance = new DistributedTrackerFiles(Archive.this,
                        trackerAddress);
                final DistributedTrackerFiles prevInstance = instances
                        .putIfAbsent(trackerAddress, instance);
                if (prevInstance != null) {
                    instance = prevInstance;
                }
            }
            return instance;
        }
    }

    /**
     * Watches for newly-created files in the file-tree.
     * 
     * Instances are thread-compatible but not thread-safe.
     * 
     * @author Steven R. Emmerson
     */
    @NotThreadSafe
    private final class FileWatcher {
        /**
         * Directory watch service.
         */
        private final WatchService        watchService;
        /**
         * Map from watch-key to pathname.
         */
        private final Map<WatchKey, Path> dirs = new HashMap<WatchKey, Path>();
        /**
         * Map from pathname to watch-key.
         */
        private final Map<Path, WatchKey> keys = new HashMap<Path, WatchKey>();
        /**
         * The associated local server.
         */
        private final Server              server;

        /**
         * Constructs from the local server to notify about new files. Doesn't
         * return.
         * 
         * @param server
         *            The local server to notify about new files.
         * @throws IOException
         *             if an I/O error occurs.
         * @throws InterruptedException
         *             if the current thread is interrupted.
         * @throws NullPointerException
         *             if {@code server == null}.
         */
        FileWatcher(final Server server) throws IOException,
                InterruptedException {
            final String origThreadName = Thread.currentThread().getName();
            Thread.currentThread().setName(toString());
            this.server = server;
            try {
                if (null == server) {
                    throw new NullPointerException();
                }
                watchService = rootDir.getFileSystem().newWatchService();
                try {
                    registerAll(rootDir);
                    for (;;) {
                        final WatchKey key = watchService.take();
                        for (final WatchEvent<?> event : key.pollEvents()) {
                            final WatchEvent.Kind<?> kind = event.kind();
                            if (kind == StandardWatchEventKinds.OVERFLOW) {
                                logger.error(
                                        "Couldn't keep-up watching file-tree rooted at \"{}\"",
                                        rootDir);
                            }
                            else {
                                final Path name = (Path) event.context();
                                Path path = dirs.get(key);
                                path = path.resolve(name);
                                if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
                                    try {
                                        newFile(path);
                                    }
                                    catch (final NoSuchFileException e) {
                                        // The file was just deleted
                                        logger.debug(
                                                "New file was just deleted: {}",
                                                path);
                                    }
                                    catch (final IOException e) {
                                        logger.error("Error with new file "
                                                + path, e);
                                    }
                                }
                                else if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
                                    try {
                                        removedFile(path);
                                    }
                                    catch (final IOException e) {
                                        logger.error(
                                                "Error with removed file \""
                                                        + path + "\"", e);
                                    }
                                }
                            }
                        }
                        if (!key.reset()) {
                            final Path dir = dirs.remove(key);
                            if (null != dir) {
                                keys.remove(dir);
                            }
                        }
                    }
                }
                finally {
                    watchService.close();
                }
            }
            finally {
                Thread.currentThread().setName(origThreadName);
            }
        }

        /**
         * Handles the creation of a new file.
         * 
         * @param path
         *            The pathname of the new file.
         * @throws IOException
         *             if an I/O error occurs.
         */
        private void newFile(final Path path) throws IOException {
            final BasicFileAttributes attributes = Files.readAttributes(path,
                    BasicFileAttributes.class);
            if (attributes.isDirectory()) {
                registerAll(path);
                walkDirectory(path, new FilePieceSpecSetConsumer() {
                    @Override
                    public void consume(final FilePieceSpecSet spec) {
                        server.newData(spec);
                    }
                }, Filter.EVERYTHING);
            }
            else if (attributes.isRegularFile()) {
                ArchiveTime.adjustTime(path);
                final FileInfo fileInfo;
                final ArchivePath archivePath = new ArchivePath(path, rootDir);
                final FileId fileId = new FileId(archivePath, new ArchiveTime(
                        attributes));
                if (archivePath.startsWith(adminDir)) {
                    // Indefinite time-to-live
                    fileInfo = new FileInfo(fileId, attributes.size(),
                            PIECE_SIZE, -1);
                }
                else {
                    // Default time-to-live
                    fileInfo = new FileInfo(fileId, attributes.size(),
                            PIECE_SIZE);
                }
                server.newData(FilePieceSpecSet.newInstance(fileInfo, true));
            }
        }

        /**
         * Handles the removal of a file.
         * 
         * @param path
         *            The pathname of the removed file.
         * @throws IOException
         *             if an I/O error occurs.
         */
        private void removedFile(final Path path) throws IOException {
            final WatchKey k = keys.remove(path);
            if (null != k) {
                dirs.remove(k);
                k.cancel();
            }
            final ArchivePath archivePath = new ArchivePath(path, rootDir);
            final FileId fileId = new FileId(archivePath);
            server.removed(fileId);
        }

        /**
         * Registers a directory.
         * 
         * @param dir
         *            Pathname of the directory.
         * @throws IOException
         *             if an I/O error occurs.
         */
        private void register(final Path dir) throws IOException {
            final WatchKey key = dir.register(watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE);
            dirs.put(key, dir);
            keys.put(dir, key);
        }

        /**
         * Registers a directory and recursively registers all sub-directories.
         * 
         * @param dir
         *            Pathname of the directory.
         * @throws IOException
         *             if an I/O error occurs.
         */
        private void registerAll(final Path dir) throws IOException {
            final EnumSet<FileVisitOption> opts = EnumSet
                    .of(FileVisitOption.FOLLOW_LINKS);
            Files.walkFileTree(dir, opts, Integer.MAX_VALUE,
                    new SimpleFileVisitor<Path>() {
                        @Override
                        public FileVisitResult preVisitDirectory(
                                final Path dir,
                                final BasicFileAttributes attributes) {
                            if (pathname.isHidden(dir)) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            try {
                                register(dir);
                            }
                            catch (final IOException e) {
                                throw new IOError(e);
                            }
                            return FileVisitResult.CONTINUE;
                        }
                    });
        }

        /*
         * (non-Javadoc)
         * 
         * @see java.lang.Object#toString()
         */
        @Override
        public String toString() {
            return "FileWatcher [rootDir=" + rootDir + "]";
        }
    }

    /**
     * Utility class for hiding and revealing files based on pathnames.
     * <p>
     * Instances are thread-safe.
     * 
     * @author Steven R. Emmerson
     */
    final class Pathname {
        /**
         * Indicates whether or not a file or directory is hidden.
         * 
         * @param path
         *            Pathname of the file or directory in question. May be
         *            absolute or relative to the root-directory.
         * @return {@code true} if and only if the file or directory is hidden.
         * @throws NullPointerException
         *             if {@code path == null}.
         */
        boolean isHidden(Path path) {
            if (path.isAbsolute()) {
                path = rootDir.relativize(path);
            }
            return (null == path)
                    ? false
                    : path.startsWith(HIDDEN_DIR);
        }

        /**
         * Returns the hidden form of a visible pathname.
         * 
         * @param path
         *            Pathname of the file to be hidden. May be absolute or
         *            relative to the root-directory.
         * @return The hidden pathname. If {@code path} is absolute, then the
         *         returned path is absolute; otherwise, it is relative to the
         *         root-directory.
         * @throws NullPointerException
         *             if {@code path == null}.
         */
        Path hide(Path path) {
            if (path.isAbsolute()) {
                path = rootDir.relativize(path);
                path = HIDDEN_DIR.resolve(path);
                return rootDir.resolve(path);
            }
            return HIDDEN_DIR.resolve(path);
        }

        /**
         * Returns the visible form of a hidden pathname.
         * 
         * @param path
         *            The hidden pathname.
         * @return The visible pathname.
         * @throws IllegalArgumentException
         *             if {@code path} isn't hidden.
         */
        Path reveal(Path path) {
            if (path.isAbsolute()) {
                path = rootDir.relativize(path);
                path = path.subpath(1, path.getNameCount());
                return rootDir.resolve(path);
            }
            return path.subpath(1, path.getNameCount());
        }
    }

    /**
     * A data-file that resides on a disk.
     * 
     * Instances are thread-safe.
     * 
     * @author Steven R. Emmerson
     */
    @ThreadSafe
    private final class DiskFile {
        /**
         * The set of existing pieces.
         */
        @GuardedBy("lock")
        private FiniteBitSet        indexes;
        /**
         * The pathname of the file.
         */
        @GuardedBy("lock")
        private Path                path;
        /**
         * The I/O channel for the file.
         */
        @GuardedBy("lock")
        private SeekableByteChannel channel;
        /**
         * Whether or not the file is hidden.
         */
        @GuardedBy("lock")
        private boolean             isComplete;
        /**
         * The lock for this instance.
         */
        private final ReentrantLock lock = new ReentrantLock();
        /**
         * The archive time of the file.
         */
        private ArchiveTime         archiveTime;
        /**
         * Information on the data-product.
         */
        private final FileInfo      fileInfo;

        /**
         * Constructs from a pathname, the number of pieces in the file, and the
         * time to associate with the file. If the file exists, then it is
         * opened read-only; otherwise, it is opened as a isComplete, but
         * hidden, file.
         * 
         * @param fileInfo
         *            Information on the data-product.
         * @throws IllegalArgumentException
         *             if the path isn't absolute.
         * @throws FileNotFoundException
         *             if the file doesn't exist and can't be created.
         * @throws FileSystemException
         *             if too many files are open.
         * @throws NullPointerException
         *             if {@code path == null}.
         * @throws NullPointerException
         *             if the file doesn't exist and {@code archiveTime == null}
         *             .
         */
        DiskFile(final FileInfo fileInfo) throws FileSystemException,
                IOException {
            lock.lock();
            path = fileInfo.getAbsolutePath(rootDir);
            this.fileInfo = fileInfo;
            try {
                /*
                 * First, try to open a complete file in read-only mode
                 */
                if (Files.exists(path)) {
                    // The complete file exists.
                    isComplete = true;
                    try {
                        ensureOpen();
                        archiveTime = new ArchiveTime(path);
                    }
                    catch (final NoSuchFileException e) {
                        // The file was just deleted by another thread.
                        logger.warn("Complete archive file {} was just "
                                + "deleted by another thread. Continuing with "
                                + "new hidden file.", path);
                    }
                }

                if (channel == null) {
                    /*
                     * The complete file must not exist. Try opening the hidden
                     * version for writing.
                     */
                    path = pathname.hide(path);
                    isComplete = false;

                    if (Files.exists(path)) {
                        ensureOpen();

                        try {
                            archiveTime = new ArchiveTime(path);
                        }
                        catch (final NoSuchFileException e) {
                            // The file was just deleted by another thread.
                            throw (FileNotFoundException) new FileNotFoundException(
                                    "Hidden archive file was just "
                                            + "deleted by another thread: "
                                            + path).initCause(e);
                        }
                    }
                    else {
                        Files.createDirectories(path.getParent());
                        ensureOpen();
                        archiveTime = fileInfo.getTime();
                        archiveTime.setTime(path);
                    }
                }
            }
            catch (final FileSystemException e) {
                throw e;
            }
            catch (final IOException e) {
                try {
                    close();
                }
                catch (final IOException ignored) {
                }
                throw e;
            }
            finally {
                lock.unlock();
            }
        }

        /**
         * Locks this instance.
         */
        void lock() {
            lock.lock();
        }

        /**
         * Unlocks this instance.
         */
        void unlock() {
            lock.unlock();
        }

        /**
         * Ensures that the file is open for access.
         * 
         * @throws FileSystemException
         *             if too many files are open.
         * @throws NoSuchFileException
         *             if the file doesn't exist.
         * @throws IOException
         *             if an I/O error occurs.
         */
        private void ensureOpen() throws FileSystemException,
                NoSuchFileException, IOException {
            lock.lock();
            try {
                if (channel == null) {
                    if (isComplete) {
                        channel = Files.newByteChannel(path,
                                StandardOpenOption.READ);
                        if (indexes == null) {
                            indexes = new CompleteBitSet(
                                    fileInfo.getPieceCount());
                        }
                    }
                    else {
                        boolean closeIt = true;
                        final RandomAccessFile randomFile = new RandomAccessFile(
                                path.toString(), "rw");
                        try {
                            if (randomFile.length() == 0) {
                                indexes = new PartialBitSet(
                                        fileInfo.getPieceCount());
                                channel = randomFile.getChannel();
                                closeIt = false;
                            }
                            else {
                                randomFile.seek(fileInfo.getSize());
                                final FileInputStream inputStream = new FileInputStream(
                                        randomFile.getFD());
                                try {
                                    final ObjectInputStream ois = new ObjectInputStream(
                                            inputStream);
                                    try {
                                        indexes = (FiniteBitSet) ois
                                                .readObject();
                                        channel = randomFile.getChannel();
                                        closeIt = false;
                                    }
                                    catch (final ClassNotFoundException e) {
                                        throw (IOException) new IOException(
                                                "Couldn't read piece bit-mask: "
                                                        + path).initCause(e);
                                    }
                                    finally {
                                        if (closeIt) {
                                            try {
                                                ois.close();
                                            }
                                            catch (final IOException ignored) {
                                            }
                                            closeIt = false;
                                        }
                                    }
                                }
                                finally {
                                    if (closeIt) {
                                        try {
                                            inputStream.close();
                                        }
                                        catch (final IOException ignored) {
                                        }
                                        closeIt = false;
                                    }
                                }
                            }
                        }
                        finally {
                            if (closeIt) {
                                try {
                                    randomFile.close();
                                }
                                catch (final IOException ignored) {
                                }
                            }
                        }
                    }
                }
            }
            finally {
                lock.unlock();
            }
        }

        /**
         * Closes this instance. Does nothing if the file is already closed.
         * 
         * @throws IOException
         *             if an I/O error occurs.
         */
        void close() throws IOException {
            lock.lock();
            try {
                if (channel != null) {
                    try {
                        if (!pathname.isHidden(path)) {
                            channel.close();
                        }
                        else {
                            if (isComplete) {
                                channel.truncate(fileInfo.getSize());
                            }
                            else {
                                final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                                final ObjectOutputStream oos = new ObjectOutputStream(
                                        outputStream);
                                oos.writeObject(indexes);
                                final byte[] bytes = outputStream.toByteArray();
                                final ByteBuffer buf = ByteBuffer.wrap(bytes);
                                channel.position(fileInfo.getSize());
                                channel.write(buf);
                            }
                            channel.close();
                            archiveTime.setTime(path);
                        }
                    }
                    finally {
                        channel = null;
                    }
                }
            }
            finally {
                lock.unlock();
            }
        }

        /**
         * Returns the time associated with this instance.
         * 
         * @return the time associated with this instance.
         */
        ArchiveTime getTime() {
            return archiveTime;
        }

        /**
         * Writes a piece of data. If the data-piece completes the file, then
         * the file is moved from the hidden file-tree to the visible file-tree
         * in a manner that is robust in the face of removal of necessary
         * directories by another thread.
         * 
         * @param piece
         *            The piece of data.
         * @return {@code true} if and only if the file is complete, in which
         *         case the file is closed.
         * @throws FileSystemException
         *             if too many files are open.
         * @throws NoSuchFileException
         *             if the file no longer exists.
         * @throws IOException
         *             if an I/O error occurs.
         * @throws NullPointerException
         *             if {@code piece == null}.
         */
        boolean putPiece(final Piece piece) throws FileSystemException,
                IOException {
            lock.lock();
            try {
                final int index = piece.getIndex();
                if (!indexes.isSet(index)) {
                    ensureOpen();
                    channel.position(piece.getOffset());
                    final ByteBuffer buf = ByteBuffer.wrap(piece.getData());
                    while (buf.hasRemaining()) {
                        channel.write(buf);
                    }
                    indexes = indexes.setBit(index);
                    archiveTime.setTime(path);
                    isComplete = indexes.areAllSet();
                    if (isComplete) {
                        close();
                        final Path newPath = pathname.reveal(path);
                        for (;;) {
                            try {
                                Files.createDirectories(newPath.getParent());
                                try {
                                    Files.move(path, newPath,
                                            StandardCopyOption.ATOMIC_MOVE);
                                    logger.debug("Received file: {}",
                                            piece.getFileInfo());
                                    break;
                                }
                                catch (final NoSuchFileException e) {
                                    // A directory in the path was just
                                    // deleted
                                    logger.trace(
                                            "Directory in path just deleted: {}",
                                            newPath);
                                }
                                catch (final IOException e) {
                                    try {
                                        Files.delete(path);
                                    }
                                    catch (final IOException ignored) {
                                    }
                                    throw e;
                                }
                            }
                            catch (final NoSuchFileException ignored) {
                                // A directory in the path was just deleted
                                logger.trace(
                                        "Directory in path just deleted: {}",
                                        newPath);
                            }
                        }
                        final int timeToLive = piece.getTimeToLive();
                        if (timeToLive >= 0) {
                            delayedPathActionQueue.actUponEventurally(newPath,
                                    1000 * timeToLive);
                        }
                        path = newPath;
                        isComplete = true;
                    }
                }
                return isComplete;
            }
            finally {
                lock.unlock();
            }
        }

        /**
         * Indicates if the disk file contains a particular piece of data.
         * 
         * @param index
         *            Index of the piece of data.
         * @return {@code true} if and only if the disk file contains the piece
         *         of data.
         */
        boolean hasPiece(final int index) {
            lock.lock();
            try {
                return indexes.isSet(index);
            }
            finally {
                lock.unlock();
            }
        }

        /**
         * Returns a piece of data.
         * 
         * @param pieceSpec
         *            Information on the piece of data.
         * @throws FileSystemException
         *             if too many files are open.
         * @throws IOException
         *             if an I/O error occurs.
         */
        Piece getPiece(final PieceSpec pieceSpec) throws FileSystemException,
                IOException {
            lock.lock();
            try {
                final byte[] data = new byte[pieceSpec.getSize()];
                final ByteBuffer buf = ByteBuffer.wrap(data);
                ensureOpen();
                channel.position(pieceSpec.getOffset());
                int nread;
                do {
                    nread = channel.read(buf);
                } while (nread != -1 && buf.hasRemaining());
                return new Piece(pieceSpec, data);
            }
            finally {
                lock.unlock();
            }
        }

        /**
         * Deletes the disk-file. Closes it first if necessary.
         * 
         * @throws IOException
         *             if an I/O error occurs.
         */
        void delete() throws IOException {
            lock.lock();
            try {
                try {
                    close();
                }
                catch (final IOException ignored) {
                }
                Files.delete(path);
            }
            finally {
                lock.unlock();
            }
        }
    }

    private final class DiskFileMap extends
            LinkedHashMap<ArchivePath, DiskFile> {
        /**
         * The serial version identifier.
         */
        private static final long serialVersionUID = 1L;

        private DiskFileMap() {
            super(16, 0.75f, true);
        }

        @Override
        protected boolean removeEldestEntry(
                final Map.Entry<ArchivePath, DiskFile> entry) {
            if (size() > ACTIVE_FILE_CACHE_SIZE) {
                final DiskFile diskFile = entry.getValue();
                try {
                    diskFile.close();
                }
                catch (final IOException e) {
                    logger.error("Couldn't close file " + diskFile, e);
                }
                return true;
            }
            return false;
        }
    }

    /**
     * The logger for this class.
     */
    private static final Logger                  logger                         = Util.getLogger();
    /**
     * The name of the hidden directory that will be ignored for the most part.
     */
    private static final Path                    HIDDEN_DIR                     = Paths.get(".sruth");
    /**
     * The canonical size, in bytes, of a piece of data (131072).
     */
    private static final int                     PIECE_SIZE                     = 0x20000;
    /**
     * The maximum number of open files.
     */
    private static final int                     ACTIVE_FILE_CACHE_SIZE;
    private static final int                     ACTIVE_FILE_CACHE_SIZE_DEFAULT = 512;
    private static final String                  ACTIVE_FILE_CACHE_SIZE_KEY     = "active file cache size";
    /**
     * The pathname utility for hidden pathnames.
     */
    private final Pathname                       pathname                       = new Pathname();
    /**
     * The set of active disk files.
     */
    @GuardedBy("itself")
    private final DiskFileMap                    diskFiles                      = new DiskFileMap();
    /**
     * The pathname of the root of the file-tree.
     */
    private final Path                           rootDir;
    /**
     * The file-deleter.
     */
    private final DelayedPathActionQueue         delayedPathActionQueue;
    /**
     * The listeners for data-products.
     */
    @GuardedBy("itself")
    private final List<DataProductListener>      dataProductListeners           = new LinkedList<DataProductListener>();
    /**
     * The factory for obtaining an object for managing the distribution of
     * tracker-specific administrative files.
     */
    private final DistributedTrackerFilesFactory distributedTrackerFilesFactory = new DistributedTrackerFilesFactory();
    /**
     * The archive pathname of the administrative-files directory.
     */
    private final ArchivePath                    adminDir                       = new ArchivePath(
                                                                                        Util.PACKAGE_NAME);

    static {
        final Preferences prefs = Preferences.userNodeForPackage(Archive.class);
        ACTIVE_FILE_CACHE_SIZE = prefs.getInt(ACTIVE_FILE_CACHE_SIZE_KEY,
                ACTIVE_FILE_CACHE_SIZE_DEFAULT);
        if (ACTIVE_FILE_CACHE_SIZE <= 0) {
            throw new IllegalArgumentException("Invalid user-preference \""
                    + ACTIVE_FILE_CACHE_SIZE_KEY + "\": "
                    + ACTIVE_FILE_CACHE_SIZE);
        }
    }

    /**
     * Constructs from the pathname of the root of the file-tree.
     * 
     * @param rootDir
     *            The pathname of the root of the file-tree.
     * @throws IOException
     *             if an I/O error occurs.
     * @throws NullPointerException
     *             if {@code rootDir == null}.
     */
    Archive(final String rootDir) throws IOException {
        this(Paths.get(rootDir));
    }

    /**
     * Constructs from the pathname of the root of the file-tree.
     * 
     * @param rootDir
     *            The pathname of the root of the file-tree.
     * @throws IOException
     *             if an I/O error occurs.
     * @throws NullPointerException
     *             if {@code rootDir == null}.
     */
    Archive(final Path rootDir) throws IOException {
        if (null == rootDir) {
            throw new NullPointerException();
        }
        final Path hiddenDir = rootDir.resolve(HIDDEN_DIR);
        final Path fileDeletionQueuePath = hiddenDir
                .resolve("fileDeletionQueue");
        Files.createDirectories(hiddenDir);
        purgeHiddenDir(hiddenDir, fileDeletionQueuePath);
        /*
         * According to the Java 7 tutorial, the following is valid:
         * 
         * Attributes.setAttribute(hiddenDir, "dos:hidden", true);
         * 
         * but the given method doesn't exist in reality. Hence, the following:
         */
        try {
            final Boolean hidden = (Boolean) Files.getAttribute(hiddenDir,
                    "dos:hidden", LinkOption.NOFOLLOW_LINKS);
            if (null != hidden && !hidden) {
                // The file-system is DOS and the hidden directory isn't hidden
                Files.setAttribute(hiddenDir, "dos:hidden", Boolean.TRUE,
                        LinkOption.NOFOLLOW_LINKS);
            }
        }
        catch (final FileSystemException ignored) {
            // The file-system isn't DOS
        }
        this.rootDir = rootDir;
        delayedPathActionQueue = new DelayedPathActionQueue(rootDir,
                new PathDelayQueue(fileDeletionQueuePath),
                new DelayedPathActionQueue.Action() {
                    @Override
                    void act(final Path path) throws IOException {
                        delete(path);
                    }

                    @Override
                    public String toString() {
                        return "DELETE";
                    }
                });
    }

    /**
     * Purges the hidden directory of all files that shouldn't exist at the
     * start of a session (i.e., cleans-up from a previous session).
     * 
     * @param hiddenDir
     *            Pathname of the hidden directory
     * @param keepPath
     *            Pathname of the only file to keep.
     * @throws IOException
     *             if an I/O error occurs.
     */
    private static void purgeHiddenDir(final Path hiddenDir, final Path keepPath)
            throws IOException {
        final EnumSet<FileVisitOption> opts = EnumSet
                .of(FileVisitOption.FOLLOW_LINKS);
        Files.walkFileTree(hiddenDir, opts, Integer.MAX_VALUE,
                new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(final Path path,
                            final BasicFileAttributes attributes)
                            throws IOException {
                        if (!path.equals(keepPath)) {
                            try {
                                Files.delete(path);
                            }
                            catch (final IOException e) {
                                logger.error("Couldn't purge file: " + path, e);
                                throw e;
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(final Path dir,
                            final IOException e) throws IOException {
                        if (e != null) {
                            throw e;
                        }
                        if (!dir.equals(hiddenDir)) {
                            try {
                                Files.delete(dir);
                            }
                            catch (final IOException e2) {
                                logger.error(
                                        "Couldn't purge directory: " + dir, e2);
                                throw e2;
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
    }

    /**
     * Deletes a file. Recursively deletes parent directories if they are now
     * empty.
     * 
     * @param path
     *            Pathname of the file to be deleted.
     * @throws IOException
     *             if an I/O error occurs.
     */
    private void delete(final Path path) throws IOException {
        /*
         * The following should work if renaming a file and deleting a file are
         * atomic.
         */
        try {
            // This should handle most files
            Files.delete(path);
        }
        catch (final NoSuchFileException e) {
            // The file might still be hidden
            final Path hiddenPath = getHiddenPath(path);
            try {
                Files.delete(hiddenPath);
            }
            catch (final NoSuchFileException e2) {
                // The file might have just been renamed
                try {
                    Files.delete(path);
                }
                catch (final NoSuchFileException e3) {
                    logger.info("File doesn't exist: {}", path);
                }
            }
        }
        try {
            for (Path dir = path.getParent(); dir != null
                    && !Files.isSameFile(dir, rootDir); dir = dir.getParent()) {
                if (isEmpty(dir)) {
                    try {
                        Files.delete(dir);
                    }
                    catch (final DirectoryNotEmptyException e) {
                        // A file must have just been added.
                        break;
                    }
                }
            }
        }
        catch (final NoSuchFileException ignored) {
            // A parent directory, "dir", has ceased to exist
        }
    }

    /**
     * Returns the pathname of the root directory of the file-tree.
     * 
     * @return Pathname of the root directory of the file-tree.
     */
    Path getRootDir() {
        return rootDir;
    }

    /**
     * Returns the pathname of the administrative-files directory relative to
     * this archive.
     * 
     * @return the pathname of the administrative-files directory relative to
     *         this archive.
     */
    ArchivePath getAdminDir() {
        return adminDir;
    }

    /**
     * Returns the archive pathname corresponding to an absolute pathname.
     * 
     * @param path
     *            The absolute pathname to be made relative to this archive.
     * @return The archive pathname corresponding to the absolute pathname.
     * @throws IllegalArgumentException
     *             if {@code !path.isAbsolute()}.
     * @throws IllegalArgumentException
     *             if {@code path} can't be made relative to this archive (i.e.,
     *             {@code path} lies outside this archive).
     * @throws NullPointerException
     *             if {@code path == null}.
     * @see #resolve(ArchivePath)
     */
    ArchivePath relativize(final Path path) {
        return new ArchivePath(path, rootDir);
    }

    /**
     * Returns the absolute pathname corresponding to an archive pathname.
     * 
     * @param path
     *            The archive pathname.
     * @return The corresponding absolute pathname.
     * @see #relativize(Path)
     */
    Path resolve(final ArchivePath path) {
        return path.getAbsolutePath(rootDir);
    }

    /**
     * Returns an object for managing the distribution of tracker-specific,
     * administrative files.
     * 
     * @param trackerAddress
     *            The address of the tracker.
     * @return An object for managing the distribution of the tracker-specific
     *         administrative files.
     */
    DistributedTrackerFiles getDistributedTrackerFiles(
            final InetSocketAddress trackerAddress) {
        return distributedTrackerFilesFactory.getInstance(trackerAddress);
    }

    /**
     * Returns the on-disk file associated with a product. Creates the file if
     * it doesn't already exist. The file is returned in a locked state. If a
     * version of the file exists with an earlier associated time, then it is
     * deleted. If a version exists with a later associated time, then
     * {@code NULL} is returned.
     * <p>
     * The number of active disk-files is limited by the smaller of the
     * {@value #ACTIVE_FILE_CACHE_SIZE_KEY} user-preference (default
     * {@value #ACTIVE_FILE_CACHE_SIZE_DEFAULT}) and the maximum number of open
     * files allowed by the operating system.
     * 
     * @param fileInfo
     *            Information on the product
     * @return The associated, locked file
     * @throws FileSystemException
     *             if too many files are open. The map is now empty and all
     *             files are closed.
     * @throws IOException
     *             if an I/O error occurs.
     * @throws NullPointerException
     *             if {@code fileInfo == null}.
     */
    private DiskFile getDiskFile(final FileInfo fileInfo)
            throws FileSystemException, IOException {
        DiskFile diskFile;
        synchronized (diskFiles) {
            final ArchivePath archivePath = fileInfo.getPath();
            diskFile = diskFiles.get(archivePath);
            if (diskFile != null) {
                final int cmp = fileInfo.getTime()
                        .compareTo(diskFile.getTime());
                if (cmp < 0) {
                    // The existing file by that name is a newer version of the
                    // product
                    return null;
                }
                if (cmp > 0) {
                    // The existing file by that name is an older version of the
                    // product
                    try {
                        diskFile.delete();
                    }
                    catch (final NoSuchFileException e) {
                        // The file was deleted by another thread
                        logger.debug(
                                "Older file was deleted by another thread: {}",
                                archivePath);
                    }
                    finally {
                        diskFiles.remove(archivePath);
                        diskFile = null;
                    }
                }
            }
            if (diskFile == null) {
                /*
                 * Create an entry for this product.
                 */
                do {
                    try {
                        diskFile = new DiskFile(fileInfo);
                    }
                    catch (final FileSystemException e) {
                        // Too many open files
                        if (removeLru() == null) {
                            throw e;
                        }
                    }
                } while (diskFile == null);
                diskFiles.put(archivePath, diskFile);
            }
            diskFile.lock();
        }
        return diskFile;
    }

    /**
     * Removes the least-recently-used (LRU) disk-file from the map: gets the
     * LRU disk-file, ensures that it's closed, and removes it from the map.
     * 
     * @return The removed disk-file or {@code null} if the map is empty.
     * @throws IOException
     *             if an I/O error occurs.
     */
    private DiskFile removeLru() throws IOException {
        DiskFile diskFile;
        synchronized (diskFiles) {
            final Iterator<Map.Entry<ArchivePath, DiskFile>> iter = diskFiles
                    .entrySet().iterator();
            if (!iter.hasNext()) {
                return null;
            }
            diskFile = iter.next().getValue();
            diskFile.close();
            iter.remove();
        }
        return diskFile;
    }

    /**
     * Indicates whether or not a piece of data exists.
     * 
     * @param dir
     *            Pathname of the output directory.
     * @param pieceSpec
     *            Specification of the piece of data.
     * @return {@code true} if and only if the piece of data exists.
     * @throws FileSystemException
     *             if too many files are open.
     * @throws IOException
     *             if an I/O error occurs.
     */
    boolean exists(final PieceSpec pieceSpec) throws FileSystemException,
            IOException {
        final DiskFile diskFile = getDiskFile(pieceSpec.getFileInfo());
        if (diskFile == null) {
            // A newer version of the file exists.
            return true;
        }
        try {
            return diskFile.hasPiece(pieceSpec.getIndex());
        }
        finally {
            diskFile.unlock();
        }
    }

    /**
     * Returns a piece of data.
     * 
     * @param pieceSpec
     *            Information on the piece of data.
     * @return The piece of data or {@code null} if the piece is unavailable.
     * @throws FileSystemException
     *             if too many files are open.
     * @throws IOException
     *             if an I/O error occurred.
     */
    Piece getPiece(final PieceSpec pieceSpec) throws FileSystemException,
            IOException {
        final DiskFile diskFile = getDiskFile(pieceSpec.getFileInfo());
        if (diskFile == null) {
            // A newer version of the file exists.
            return null;
        }
        try {
            for (;;) {
                try {
                    return diskFile.getPiece(pieceSpec);
                }
                catch (final FileSystemException e) {
                    if (diskFile.equals(removeLru())) {
                        throw e;
                    }
                }
            }
        }
        finally {
            diskFile.unlock();
        }
    }

    /**
     * Writes a piece of data. If a newer version of the file exists, then the
     * data isn't written.
     * 
     * @param piece
     *            Piece of data to be written.
     * @return {@code true} if and only if the file is now complete.
     * @throws FileSystemException
     *             if too many files are open.
     * @throws NoSuchFileException
     *             if the destination file was deleted.
     * @throws IOException
     *             if an I/O error occurred.
     * @throws NullPointerException
     *             if {@code piece == null}.
     */
    boolean putPiece(final Piece piece) throws FileSystemException,
            NoSuchFileException, IOException {
        final FileInfo fileInfo = piece.getFileInfo();
        final DiskFile diskFile = getDiskFile(fileInfo);
        if (diskFile == null) {
            // A newer version of the file exists.
            logger.trace("Newer file version exists: {}", fileInfo);
            return false;
        }
        try {
            for (;;) {
                try {
                    final boolean isComplete = diskFile.putPiece(piece);
                    if (isComplete) {
                        synchronized (dataProductListeners) {
                            for (final DataProductListener listener : dataProductListeners) {
                                final DataProduct product = new DataProduct(
                                        rootDir, fileInfo);
                                listener.process(product);
                            }
                        }
                    }
                    return isComplete;
                }
                catch (final FileSystemException e) {
                    if (diskFile.equals(removeLru())) {
                        throw e;
                    }
                }
            }
        }
        finally {
            diskFile.unlock();
        }
    }

    /**
     * Saves data in the archive.
     * 
     * @param path
     *            The pathname for the data in the archive.
     * @param data
     *            The data.
     * @throws FileAlreadyExistsException
     *             the file is being actively written by another thread.
     * @throws IOException
     *             if an I/O error occurs.
     */
    void save(final ArchivePath path, final ByteBuffer data)
            throws FileAlreadyExistsException, IOException {
        Path hiddenPath = getHiddenAbsolutePath(path);
        Files.createDirectories(hiddenPath.getParent());
        final SeekableByteChannel channel = Files.newByteChannel(hiddenPath,
                StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW);
        try {
            channel.write(data);
            ArchiveTime.adjustTime(hiddenPath);
            final Path visiblePath = getVisiblePath(hiddenPath);
            Files.createDirectories(visiblePath.getParent());
            Files.move(hiddenPath, visiblePath, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            hiddenPath = null;
        }
        finally {
            try {
                channel.close();
            }
            catch (final IOException ignored) {
            }
            if (hiddenPath != null) {
                try {
                    Files.delete(hiddenPath);
                }
                catch (final IOException ignored) {
                }
            }
        }
    }

    /**
     * Saves an object in the archive by first writing it into a hidden file and
     * then revealing the hidden file.
     * 
     * @param archivePath
     *            The pathname for the object in the archive.
     * @param serializable
     *            The object to be saved in the file.
     * @throws FileAlreadyExistsException
     *             the hidden file was created by another thread.
     * @throws IOException
     *             if an I/O error occurs.
     */
    void save(final ArchivePath archivePath, final Serializable serializable)
            throws FileAlreadyExistsException, IOException {
        Path hiddenPath = getHiddenAbsolutePath(archivePath);
        Files.deleteIfExists(hiddenPath);
        Files.createDirectories(hiddenPath.getParent());
        OutputStream outStream = Files.newOutputStream(hiddenPath,
                StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW);
        try {
            ObjectOutputStream objOutStream = new ObjectOutputStream(outStream);
            try {
                objOutStream.writeObject(serializable);
                objOutStream.close();
                objOutStream = null;
                outStream = null;
                ArchiveTime.adjustTime(hiddenPath);
                final Path visiblePath = getVisiblePath(hiddenPath);
                Files.createDirectories(visiblePath.getParent());
                Files.move(hiddenPath, visiblePath,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                hiddenPath = null;
            }
            finally {
                if (objOutStream != null) {
                    try {
                        objOutStream.close();
                        outStream = null;
                    }
                    catch (final IOException ignored) {
                    }
                }
            }
        }
        finally {
            if (outStream != null) {
                try {
                    outStream.close();
                }
                catch (final IOException ignored) {
                }
            }
            if (hiddenPath != null) {
                try {
                    Files.delete(hiddenPath);
                }
                catch (final IOException ignored) {
                }
            }
        }
    }

    /**
     * Saves an object in a hidden file in the archive. The hidden file will not
     * be distributed.
     * 
     * @param archivePath
     *            The pathname for the (visible) file in the archive.
     * @param serializable
     *            The object to be saved in the file.
     * @throws FileAlreadyExistsException
     *             the file is being actively written by another thread.
     * @throws IOException
     *             if an I/O error occurs.
     */
    void hide(final ArchivePath archivePath, final Serializable serializable)
            throws FileAlreadyExistsException, IOException {
        Path hiddenPath = getHiddenAbsolutePath(archivePath);
        Files.createDirectories(hiddenPath.getParent());
        OutputStream outStream = Files.newOutputStream(hiddenPath,
                StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW);
        try {
            ObjectOutputStream objOutStream = new ObjectOutputStream(outStream);
            try {
                objOutStream.writeObject(serializable);
                objOutStream.close();
                objOutStream = null;
                outStream = null;
                ArchiveTime.adjustTime(hiddenPath);
                hiddenPath = null;
            }
            finally {
                if (objOutStream != null) {
                    try {
                        objOutStream.close();
                        outStream = null;
                    }
                    catch (final IOException ignored) {
                    }
                }
            }
        }
        finally {
            if (outStream != null) {
                try {
                    outStream.close();
                }
                catch (final IOException ignored) {
                }
            }
            if (hiddenPath != null) {
                try {
                    Files.delete(hiddenPath);
                }
                catch (final IOException ignored) {
                }
            }
        }
    }

    /**
     * Returns the archive-time of a hidden file in the archive.
     * 
     * @param archivePath
     *            The pathname for the file in the archive.
     * @return The archive-time of the hidden file.
     * @throws IOException
     *             if an I/O error occurs.
     * @throws NoSuchFileException
     *             if the hidden file doesn't exist.
     */
    ArchiveTime getArchiveTime(final ArchivePath archivePath)
            throws NoSuchFileException, IOException {
        final Path hiddenPath = getHiddenAbsolutePath(archivePath);
        return new ArchiveTime(hiddenPath);
    }

    /**
     * Reveals a hidden object in the archive.
     * 
     * @param archivePath
     *            The pathname for the object in the archive.
     * @throws IOException
     *             if an I/O error occurs.
     */
    void reveal(final ArchivePath archivePath) throws IOException {
        final Path hiddenPath = getHiddenAbsolutePath(archivePath);
        final Path visiblePath = getVisiblePath(hiddenPath);
        Files.createDirectories(visiblePath.getParent());
        Files.move(hiddenPath, visiblePath, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Ensures that a hidden file doesn't exist.
     * 
     * @param archivePath
     *            Pathname of the file in the archive.
     * @throws IOException
     *             if an I/O error occurs.
     */
    void removeHidden(final ArchivePath archivePath) throws IOException {
        final Path hiddenPath = getHiddenAbsolutePath(archivePath);
        Files.deleteIfExists(hiddenPath);
    }

    /**
     * Restores an object from a file.
     * 
     * @param archivePath
     *            Pathname of the file in the archive.
     * @param type
     *            Expected type of the restored object.
     * @throws ClassNotFoundException
     *             if the type of the restored object is unknown.
     * @throws ClassCastException
     *             if the object isn't the expected type.
     * @throws FileNotFoundException
     *             if the file doesn't exist.
     * @throws IOException
     *             if an I/O error occurs.
     * @throws StreamCorruptedException
     *             if the file is corrupt.
     */
    Object restore(final ArchivePath archivePath, final Class<?> type)
            throws FileNotFoundException, IOException, ClassNotFoundException {
        final Path path = resolve(archivePath);
        InputStream inputStream = Files.newInputStream(path);
        try {
            final ObjectInputStream ois = new ObjectInputStream(inputStream);
            try {
                final Object obj = ois.readObject();
                if (!type.isInstance(obj)) {
                    throw new ClassCastException("expected=" + type
                            + ", actual=" + obj.getClass());
                }
                return obj;
            }
            finally {
                try {
                    ois.close();
                    inputStream = null;
                }
                catch (final IOException ignored) {
                }
            }
        }
        catch (final StreamCorruptedException e) {
            throw (StreamCorruptedException) new StreamCorruptedException(
                    "Corrupted file: " + path).initCause(e);
        }
        finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                }
                catch (final IOException ignored) {
                }
            }
        }
    }

    /**
     * Removes a file from both the visible and hidden directory hierarchies.
     * 
     * @param archivePath
     *            Pathname of the file to be removed.
     * @throws IOException
     *             if an I/O error occurs.
     */
    void remove(final ArchivePath archivePath) throws IOException {
        synchronized (diskFiles) {
            final DiskFile diskFile = diskFiles.get(archivePath);
            if (diskFile != null) {
                diskFile.delete();
                diskFiles.remove(archivePath);
            }
            else {
                // The following works because renaming a file is atomic
                final Path visiblePath = archivePath.getAbsolutePath(rootDir);
                if (!remove(visiblePath)) {
                    final Path hiddenPath = pathname.hide(visiblePath);
                    if (!remove(hiddenPath)) {
                        remove(visiblePath);
                    }
                }
            }
        }
    }

    /**
     * Removes the file that matches a file identifier from both the visible and
     * hidden directory hierarchies.
     * 
     * @param fileId
     *            Identifier of the file to be removed. All attributes must be
     *            matched.
     * @throws IOException
     *             if an I/O error occurs.
     */
    void remove(final FileId fileId) throws IOException {
        remove(fileId.getPath());
    }

    /**
     * Removes a file or directory corresponding to a pathname if it exists. The
     * file or directory can be a hidden one.
     * 
     * @param path
     *            The pathname of the file or directory.
     * @return {@code true} if and only if the file or directory existed and was
     *         deleted.
     * @throws IOException
     *             if an I/O error occurs.
     */
    private boolean remove(final Path path) throws IOException {
        if (!Files.exists(path)) {
            logger.trace("File doesn't exist: {}", path);
            return false;
        }
        logger.trace("Removing file {}", path);
        final EnumSet<FileVisitOption> opts = EnumSet
                .of(FileVisitOption.FOLLOW_LINKS);
        Files.walkFileTree(path, opts, Integer.MAX_VALUE,
                new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult postVisitDirectory(final Path dir,
                            final IOException e) throws IOException {
                        if (null != e) {
                            throw e;
                        }
                        Files.delete(dir);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(final Path path,
                            final BasicFileAttributes attr) throws IOException {
                        if (!attr.isDirectory()) {
                            Files.delete(path);
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
        return true;
    }

    /**
     * Watches the archive for new files and removed files and directories.
     * Ignores hidden directories. Doesn't return.
     * 
     * @param server
     *            The local server.
     * @throws InterruptedException
     *             if the current thread is interrupted.
     * @throws IOException
     */
    void watchArchive(final Server server) throws IOException,
            InterruptedException {
        new FileWatcher(server);
    }

    /**
     * Returns the hidden form of a pathname.
     * 
     * @param path
     *            The pathname whose hidden form is to be returned.
     * @return The hidden form of {@code path}.
     */
    Path getHiddenPath(final Path path) {
        return pathname.hide(path);
    }

    /**
     * Returns the visible form of a hidden pathname
     * 
     * @param path
     *            The hidden pathname whose visible form is to be returned.
     * @return The visible form of {@code path}.
     */
    Path getVisiblePath(final Path path) {
        return pathname.reveal(path);
    }

    /**
     * Returns the absolute pathname of the hidden form of a visible pathname
     * that's relative to the root of the archive.
     * 
     * @param path
     *            The visible pathname relative to the root of the archive.
     * @return The corresponding absolute, hidden pathname.
     */
    Path getHiddenAbsolutePath(final Path path) {
        return rootDir.resolve(getHiddenPath(path));
    }

    /**
     * Returns the absolute pathname of the hidden form of a visible pathname
     * that's relative to the archive.
     * 
     * @param path
     *            The visible pathname relative to the archive.
     * @return The corresponding absolute, hidden pathname.
     */
    private Path getHiddenAbsolutePath(final ArchivePath archivePath) {
        return getHiddenAbsolutePath(archivePath.getPath());
    }

    /**
     * Recursively visits all the file-based data-specifications in a directory
     * that match a selection criteria. Doesn't visit files in hidden
     * directories.
     * 
     * @param root
     *            The directory to recursively walk.
     * @param consumer
     *            The consumer of file-based data-specifications.
     * @param filter
     *            The selection criteria.
     * @throws IOException
     *             if an I/O error occurs.
     */
    private void walkDirectory(final Path root,
            final FilePieceSpecSetConsumer consumer, final Filter filter)
            throws IOException {
        final EnumSet<FileVisitOption> opts = EnumSet
                .of(FileVisitOption.FOLLOW_LINKS);
        Files.walkFileTree(root, opts, Integer.MAX_VALUE,
                new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(final Path dir,
                            final BasicFileAttributes attributes) {
                        return pathname.isHidden(dir)
                                ? FileVisitResult.SKIP_SUBTREE
                                : FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(final Path path,
                            final BasicFileAttributes attributes) {
                        if (attributes.isRegularFile()) {
                            final ArchiveTime archiveTime = new ArchiveTime(
                                    attributes);
                            try {
                                archiveTime.setTime(path);
                                final ArchivePath archivePath = new ArchivePath(
                                        path, rootDir);
                                if (filter.matches(archivePath)) {
                                    final FileId fileId = new FileId(
                                            archivePath, archiveTime);
                                    final FileInfo fileInfo;
                                    if (archivePath.startsWith(adminDir)) {
                                        // Indefinite time-to-live
                                        fileInfo = new FileInfo(fileId,
                                                attributes.size(), PIECE_SIZE,
                                                -1);
                                    }
                                    else {
                                        // Default time-to-live
                                        fileInfo = new FileInfo(fileId,
                                                attributes.size(), PIECE_SIZE);
                                    }
                                    final FilePieceSpecSet specSet = FilePieceSpecSet
                                            .newInstance(fileInfo, true);
                                    logger.trace("Path={}", archivePath);
                                    consumer.consume(specSet);
                                }
                            }
                            catch (final IOException e) {
                                logger.error(
                                        "Couldn't adjust time of file {}: {}",
                                        path, e.toString());
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
    }

    /**
     * Visits all the file-based data-specifications in the archive that match a
     * selection criteria. Doesn't visit files in hidden directories.
     * 
     * @param consumer
     *            The consumer of file-based data-specifications.
     * @param filter
     *            The selection criteria.
     * @throws IOException
     *             if an I/O error occurs.
     */
    void walkArchive(final FilePieceSpecSetConsumer consumer,
            final Filter filter) throws IOException {
        walkDirectory(rootDir, consumer, filter);
    }

    /**
     * Adds a listener for data-products.
     * 
     * @param dataProductListener
     *            The listener to be added.
     */
    void addDataProductListener(final DataProductListener dataProductListener) {
        synchronized (dataProductListeners) {
            dataProductListeners.add(dataProductListener);
        }
    }

    /**
     * Removes a listener for data-products.
     * 
     * @param dataProductListener
     *            The listener to be removed.
     */
    void removeDataProductListener(final DataProductListener dataProductListener) {
        synchronized (dataProductListeners) {
            dataProductListeners.remove(dataProductListener);
        }
    }

    /**
     * Indicates if a directory is empty.
     * 
     * @param dir
     *            Pathname of the directory in question.
     * @return {@code true} if and only if the directory is empty.
     * @throws IOException
     *             if an I/O error occurs.
     */
    private static boolean isEmpty(final Path dir) throws IOException {
        final DirectoryStream<Path> stream = Files.newDirectoryStream(dir);
        try {
            return !stream.iterator().hasNext();
        }
        finally {
            stream.close();
        }
    }

    /**
     * Closes this instance. Closes all open files and stops the file-deleter.
     * 
     * @throws IOException
     *             if an I/O error occurs.
     */
    void close() throws IOException {
        try {
            delayedPathActionQueue.stop();
        }
        finally {
            synchronized (diskFiles) {
                for (final Iterator<DiskFile> iter = diskFiles.values()
                        .iterator(); iter.hasNext();) {
                    iter.next().close();
                    iter.remove();
                }
            }
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return "Archive [rootDir=" + rootDir + "]";
    }
}
