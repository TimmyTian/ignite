/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.hadoop.fs;

import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.ParentNotDirectoryException;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.PathExistsException;
import org.apache.hadoop.fs.PathIsNotEmptyDirectoryException;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.ignite.IgniteException;
import org.apache.ignite.igfs.IgfsDirectoryNotEmptyException;
import org.apache.ignite.igfs.IgfsException;
import org.apache.ignite.igfs.IgfsFile;
import org.apache.ignite.igfs.IgfsParentNotDirectoryException;
import org.apache.ignite.igfs.IgfsPath;
import org.apache.ignite.igfs.IgfsPathAlreadyExistsException;
import org.apache.ignite.igfs.IgfsPathNotFoundException;
import org.apache.ignite.igfs.IgfsUserContext;
import org.apache.ignite.igfs.secondary.IgfsSecondaryFileSystem;
import org.apache.ignite.igfs.secondary.IgfsSecondaryFileSystemPositionedReadable;
import org.apache.ignite.internal.processors.hadoop.igfs.HadoopIgfsProperties;
import org.apache.ignite.internal.processors.igfs.IgfsEntryInfo;
import org.apache.ignite.internal.processors.igfs.IgfsFileImpl;
import org.apache.ignite.internal.processors.igfs.IgfsUtils;
import org.apache.ignite.internal.util.io.GridFilenameUtils;
import org.apache.ignite.internal.util.typedef.F;
import org.apache.ignite.lang.IgniteUuid;
import org.apache.ignite.lifecycle.LifecycleAware;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Secondary file system which delegates to local file system.
 */
public class LocalIgfsSecondaryFileSystem implements IgfsSecondaryFileSystem, LifecycleAware {
    /** Default buffer size. */
    // TODO: Choose proper buffer size.
    public static final int DFLT_BUF_SIZE = 8 * 1024;

    /** The default user name. It is used if no user context is set. */
    private String dfltUsrName = IgfsUtils.fixUserName(null);

    /** Factory. */
    private HadoopFileSystemFactory fsFactory;

    /** Path that will be added to each passed path. */
    private String workDir;

    /** Buffer size. */
    private int bufSize = DFLT_BUF_SIZE;

    /**
     * Default constructor.
     */
    public LocalIgfsSecondaryFileSystem() {
        CachingHadoopFileSystemFactory fsFactory0 = new CachingHadoopFileSystemFactory();

        fsFactory0.setUri("file:///");

        fsFactory = fsFactory0;
    }

    /**
     * Convert IGFS path into Hadoop path.
     *
     * @param path IGFS path.
     * @return Hadoop path.
     */
    private Path convert(IgfsPath path) {
        URI uri = fileSystemForUser().getUri();

        return new Path(uri.getScheme(), uri.getAuthority(), addParent(path.toString()));
    }

    /**
     * @param path Path to which parrent should be added.
     * @return Path with added root.
     */
    private String addParent(String path) {
        if (path.startsWith("/"))
            path = path.substring(1, path.length());

        return GridFilenameUtils.concat(workDir, path);
    }

    /**
     * Heuristically checks if exception was caused by invalid HDFS version and returns appropriate exception.
     *
     * @param e Exception to check.
     * @param detailMsg Detailed error message.
     * @return Appropriate exception.
     */
    private IgfsException handleSecondaryFsError(IOException e, String detailMsg) {
        return cast(detailMsg, e);
    }

    /**
     * Cast IO exception to IGFS exception.
     *
     * @param msg Error message.
     * @param e IO exception.
     * @return IGFS exception.
     */
    public static IgfsException cast(String msg, IOException e) {
        if (e instanceof FileNotFoundException)
            return new IgfsPathNotFoundException(e);
        else if (e instanceof ParentNotDirectoryException)
            return new IgfsParentNotDirectoryException(msg, e);
        else if (e instanceof PathIsNotEmptyDirectoryException)
            return new IgfsDirectoryNotEmptyException(e);
        else if (e instanceof PathExistsException)
            return new IgfsPathAlreadyExistsException(msg, e);
        else
            return new IgfsException(msg, e);
    }

    /**
     * Convert Hadoop FileStatus properties to map.
     *
     * @param status File status.
     * @return IGFS attributes.
     */
    private static Map<String, String> properties(FileStatus status) {
        FsPermission perm = status.getPermission();

        if (perm == null)
            perm = FsPermission.getDefault();

        HashMap<String, String> res = new HashMap<>(3);

        res.put(IgfsUtils.PROP_PERMISSION, String.format("%04o", perm.toShort()));
        res.put(IgfsUtils.PROP_USER_NAME, status.getOwner());
        res.put(IgfsUtils.PROP_GROUP_NAME, status.getGroup());

        return res;
    }

    /** {@inheritDoc} */
    @Override public boolean exists(IgfsPath path) {
        try {
            // TODO
            return fileSystemForUser().exists(convert(path));
        }
        catch (IOException e) {
            throw handleSecondaryFsError(e, "Failed to check file existence [path=" + path + "]");
        }
    }

    /** {@inheritDoc} */
    @Nullable @Override public IgfsFile update(IgfsPath path, Map<String, String> props) {
        // TODO
        HadoopIgfsProperties props0 = new HadoopIgfsProperties(props);

        final FileSystem fileSys = fileSystemForUser();

        try {
            if (props0.userName() != null || props0.groupName() != null)
                fileSys.setOwner(convert(path), props0.userName(), props0.groupName());

            if (props0.permission() != null)
                fileSys.setPermission(convert(path), props0.permission());
        }
        catch (IOException e) {
            throw handleSecondaryFsError(e, "Failed to update file properties [path=" + path + "]");
        }

        //Result is not used in case of secondary FS.
        return null;
    }

    /** {@inheritDoc} */
    @Override public void rename(IgfsPath src, IgfsPath dest) {
        // TODO
        // Delegate to the secondary file system.
        try {
            if (!fileSystemForUser().rename(convert(src), convert(dest)))
                throw new IgfsException("Failed to rename (secondary file system returned false) " +
                    "[src=" + src + ", dest=" + dest + ']');
        }
        catch (IOException e) {
            throw handleSecondaryFsError(e, "Failed to rename file [src=" + src + ", dest=" + dest + ']');
        }
    }

    /** {@inheritDoc} */
    @Override public boolean delete(IgfsPath path, boolean recursive) {
        try {
            File f = fileForPath(path);
            if (!recursive || !f.isDirectory())
                return f.delete();
            else
                return deleteDir(f);
        }
        catch (IOException e) {
            throw handleSecondaryFsError(e, "Failed to delete file [path=" + path + ", recursive=" + recursive + "]");
        }
    }

    /** {@inheritDoc} */
    @Override public void mkdirs(IgfsPath path) {
        if (!mkdirs0(fileForPath(path)))
            throw new IgniteException("Failed to make directories [path=" + path + "]");
    }

    /** {@inheritDoc} */
    @Override public void mkdirs(IgfsPath path, @Nullable Map<String, String> props) {
        // TODO: Add properties handling.
        if (!mkdirs0(fileForPath(path)))
            throw new IgniteException("Failed to make directories [path=" + path + "]");
    }

    /** {@inheritDoc} */
    @Override public Collection<IgfsPath> listPaths(IgfsPath path) {
        try {
            // TODO
            FileStatus[] statuses = fileSystemForUser().listStatus(convert(path));

            if (statuses == null)
                throw new IgfsPathNotFoundException("Failed to list files (path not found): " + path);

            Collection<IgfsPath> res = new ArrayList<>(statuses.length);

            for (FileStatus status : statuses)
                res.add(new IgfsPath(path, status.getPath().getName()));

            return res;
        }
        catch (FileNotFoundException ignored) {
            throw new IgfsPathNotFoundException("Failed to list files (path not found): " + path);
        }
        catch (IOException e) {
            throw handleSecondaryFsError(e, "Failed to list statuses due to secondary file system exception: " + path);
        }
    }

    /** {@inheritDoc} */
    @Override public Collection<IgfsFile> listFiles(IgfsPath path) {
        try {
            // TODO
            FileStatus[] statuses = fileSystemForUser().listStatus(convert(path));

            if (statuses == null)
                throw new IgfsPathNotFoundException("Failed to list files (path not found): " + path);

            Collection<IgfsFile> res = new ArrayList<>(statuses.length);

            for (FileStatus s : statuses) {
                IgfsEntryInfo fsInfo = s.isDirectory() ?
                    IgfsUtils.createDirectory(
                        IgniteUuid.randomUuid(),
                        null,
                        properties(s),
                        s.getAccessTime(),
                        s.getModificationTime()
                    ) :
                    IgfsUtils.createFile(
                        IgniteUuid.randomUuid(),
                        (int)s.getBlockSize(),
                        s.getLen(),
                        null,
                        null,
                        false,
                        properties(s),
                        s.getAccessTime(),
                        s.getModificationTime()
                    );

                res.add(new IgfsFileImpl(new IgfsPath(path, s.getPath().getName()), fsInfo, 1));
            }

            return res;
        }
        catch (FileNotFoundException ignored) {
            throw new IgfsPathNotFoundException("Failed to list files (path not found): " + path);
        }
        catch (IOException e) {
            throw handleSecondaryFsError(e, "Failed to list statuses due to secondary file system exception: " + path);
        }
    }

    /** {@inheritDoc} */
    @Override public IgfsSecondaryFileSystemPositionedReadable open(IgfsPath path, int bufSize) {
        try {
            FileInputStream in = new FileInputStream(fileForPath(path));

            return new LocalIgfsSecondaryFileSystemPositionedReadable(in, bufSize);
        }
        catch (IOException e) {
            throw handleSecondaryFsError(e, "Failed to open file for read: " + path);
        }
    }

    /** {@inheritDoc} */
    @Override public OutputStream create(IgfsPath path, boolean overwrite) {
        return create0(path, overwrite, bufSize);
    }

    /** {@inheritDoc} */
    @Override public OutputStream create(IgfsPath path, int bufSize, boolean overwrite, int replication,
        long blockSize, @Nullable Map<String, String> props) {
        // TODO: Handle properties.
        return create0(path, overwrite, bufSize);
    }
    /** {@inheritDoc} */
    @Override public OutputStream append(IgfsPath path, int bufSize, boolean create,
        @Nullable Map<String, String> props) {
        return append0(path, bufSize);
    }

    /** {@inheritDoc} */
    @Override public IgfsFile info(final IgfsPath path) {
        try {
            // TODO
            final FileStatus status = fileSystemForUser().getFileStatus(convert(path));

            if (status == null)
                return null;

            final Map<String, String> props = properties(status);

            return new IgfsFile() {
                @Override public IgfsPath path() {
                    return path;
                }

                @Override public boolean isFile() {
                    return status.isFile();
                }

                @Override public boolean isDirectory() {
                    return status.isDirectory();
                }

                @Override public int blockSize() {
                    // By convention directory has blockSize == 0, while file has blockSize > 0:
                    return isDirectory() ? 0 : (int)status.getBlockSize();
                }

                @Override public long groupBlockSize() {
                    return status.getBlockSize();
                }

                @Override public long accessTime() {
                    return status.getAccessTime();
                }

                @Override public long modificationTime() {
                    return status.getModificationTime();
                }

                @Override public String property(String name) throws IllegalArgumentException {
                    String val = props.get(name);

                    if (val ==  null)
                        throw new IllegalArgumentException("File property not found [path=" + path + ", name=" + name + ']');

                    return val;
                }

                @Nullable @Override public String property(String name, @Nullable String dfltVal) {
                    String val = props.get(name);

                    return val == null ? dfltVal : val;
                }

                @Override public long length() {
                    return status.getLen();
                }

                /** {@inheritDoc} */
                @Override public Map<String, String> properties() {
                    return props;
                }
            };
        }
        catch (FileNotFoundException ignore) {
            return null;
        }
        catch (IOException e) {
            throw handleSecondaryFsError(e, "Failed to get file status [path=" + path + "]");
        }
    }

    /** {@inheritDoc} */
    @Override public long usedSpaceSize() {
        try {
            // TODO
            // We don't use FileSystem#getUsed() since it counts only the files
            // in the filesystem root, not all the files recursively.
            return fileSystemForUser().getContentSummary(new Path("/")).getSpaceConsumed();
        }
        catch (IOException e) {
            throw handleSecondaryFsError(e, "Failed to get used space size of file system.");
        }
    }

    /**
     * Gets the FileSystem for the current context user.
     * @return the FileSystem instance, never null.
     */
    private FileSystem fileSystemForUser() {
        String user = IgfsUserContext.currentUser();

        if (F.isEmpty(user))
            user = IgfsUtils.fixUserName(dfltUsrName);

        assert !F.isEmpty(user);

        try {
            return fsFactory.get(user);
        }
        catch (IOException ioe) {
            throw new IgniteException(ioe);
        }
    }

    /** {@inheritDoc} */
    @Override public void start() throws IgniteException {
        if (fsFactory == null)
            fsFactory = new CachingHadoopFileSystemFactory();

        if (fsFactory instanceof LifecycleAware)
            ((LifecycleAware) fsFactory).start();
    }

    /** {@inheritDoc} */
    @Override public void stop() throws IgniteException {
        if (fsFactory instanceof LifecycleAware)
             ((LifecycleAware)fsFactory).stop();
    }

    /**
     * Get work directory.
     *
     * @return Work directory.
     */
    public String getWorkDirectory() {
        return workDir;
    }

    /**
     * Set work directory.
     *
     * @param workDir Work directory.
     */
    public void setWorkDirectory(final String workDir) {
        this.workDir = workDir;
    }

    /**
     * Get buffer size.
     *
     * @return Buffer size.
     */
    public int getBufferSize() {
        return bufSize;
    }

    /**
     * Set buffer size.
     *
     * @param bufSize Buffer size.
     */
    public void setBufferSize(int bufSize) {
        this.bufSize = bufSize;
    }

    /**
     * Create file for IGFS path.
     *
     * @param path IGFS path.
     * @return File object.
     */
    private File fileForPath(IgfsPath path) {
        if (workDir == null)
            return new File(path.toString());
        else
            return new File(workDir, path.toString());
    }

    /**
     * @param dir Directory.
     * @throws IOException If fails.
     * @return {@code true} if successful.
     */
    private boolean deleteDir(File dir) throws IOException {
        File[] dirEntries = dir.listFiles();

        if (dirEntries != null) {
            for (int i = 0; i < dirEntries.length; ++i) {
                File f = dirEntries[i];

                if (!f.isDirectory()) { // TODO: should we support symlink?
                    if (!f.delete())
                        throw new IOException("Cannot remove [file=" + f + ']');
                }
                else
                    deleteDir(dirEntries[i]);
            }
        }

        if (!dir.delete())
            throw new IOException("Cannot remove [dir=" + dir + ']');

        return true;
    }

    /**
     * Create directories.
     *
     * @param dir Directory.
     * @return Result.
     */
    private boolean mkdirs0(File dir) {
        if (dir == null)
            return true; // Nothing to create.

        if (dir.exists()) {
            if (dir.isDirectory())
                return true; // Already exists, so no-op.
            else
                return false; // TODO: should we support symlink?
        }
        else {
            File parentDir = dir.getParentFile();

            if (!mkdirs0(parentDir)) // Create parent first.
                return false;

            boolean res = dir.mkdir();

            if (!res)
                res = dir.exists(); // Tolerate concurrent creation.

            return res;
        }
    }

    /**
     * Internal create routine.
     *
     * @param path Path.
     * @param overwrite Overwirte flag.
     * @param bufSize Buffer size.
     * @return Output stream.
     */
    private OutputStream create0(IgfsPath path, boolean overwrite, int bufSize) {
        try {
            File file = fileForPath(path);

            boolean exists = file.exists();

            if (exists) {
                if (!overwrite)
                    throw new IOException("File already exists.");
            }
            else {
                File parent = file.getParentFile();

                if (!mkdirs0(parent))
                    throw new IOException("Failed to create parent directory: " + parent);
            }

            return new BufferedOutputStream(new FileOutputStream(file), bufSize);
        }
        catch (IOException e) {
            throw handleSecondaryFsError(e, "Failed to create file [path=" + path + ", overwrite=" + overwrite + ']');
        }
    }

    /**
     * Internal create routine.
     *
     * @param path Path.
     * @param bufSize Buffer size.
     * @return Output stream.
     */
    private OutputStream append0(IgfsPath path, int bufSize) {
        try {
            File file = fileForPath(path);

            boolean exists = file.exists();

            if (!exists)
                throw new IOException("File not found.");

            return new BufferedOutputStream(new FileOutputStream(file, true), bufSize);
        }
        catch (IOException e) {
            throw handleSecondaryFsError(e, "Failed to append file [path=" + path + ']');
        }
    }
}