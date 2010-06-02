package edu.ucar.unidata.dynaccn;

import java.io.Serializable;
import java.nio.file.Path;

/**
 * A data-specification comprising one or more pieces of data in a file.
 * 
 * @author Steven R. Emmerson
 */
abstract class FilePieceSpecSet implements PieceSpecSet, Serializable {
    /**
     * The serial version ID.
     */
    private static final long serialVersionUID = 1L;
    /**
     * Information on the file.
     */
    protected final FileInfo  fileInfo;

    /**
     * Constructs from information on a file.
     * 
     * @param fileInfo
     *            Information on the file.
     * @throws NullPointerException
     *             if {@code fileInfo == null}.
     */
    protected FilePieceSpecSet(final FileInfo fileInfo) {
        if (null == fileInfo) {
            throw new NullPointerException();
        }
        this.fileInfo = fileInfo;
    }

    /**
     * Returns a new instance.
     * 
     * @param fileInfo
     *            Information on the file
     * @param allPieces
     *            Whether or not the instance should specify all possible pieces
     *            or none.
     * @return A new Instance.
     */
    static FilePieceSpecSet newInstance(final FileInfo fileInfo,
            final boolean allPieces) {
        return allPieces
                ? (fileInfo.getPieceCount() == 1
                        ? new PieceSpec(fileInfo, 0)
                        : new FilePieceSpecs(fileInfo, true))
                : new FilePieceSpecs(fileInfo);
    }

    /**
     * Returns information on the associated file.
     * 
     * @return Information on the associated file.
     */
    final FileInfo getFileInfo() {
        return fileInfo;
    }

    /**
     * Returns the associated file's identifier.
     * 
     * @return The identifier of the associated file.
     */
    FileId getFileId() {
        return fileInfo.getFileId();
    }

    /**
     * Returns the pathname corresponding to this instance.
     * 
     * @return This instance's corresponding pathname.
     */
    Path getPath() {
        return fileInfo.getPath();
    }
}