/*
 * Copyright 2000-2011 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package jetbrains.buildServer.buildTriggers.vcs.git;

import com.intellij.openapi.diagnostic.Logger;
import jetbrains.buildServer.util.FileUtil;
import jetbrains.buildServer.vcs.VcsException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryCache;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.util.FS;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * @author dmitry.neverov
 */
public final class RepositoryManagerImpl implements RepositoryManager {

  private static final Logger LOG = Logger.getInstance(RepositoryManagerImpl.class.getName());

  private final MirrorManager myMirrorManager;
  private final long myExpirationTimeout;
  /**
   * During repository creation jgit checks existence of some files and directories. When several threads
   * try to create repository concurrently some of them could see it in inconsistent state. This map contains
   * locks for repository creation, so only one thread at a time will create repository at give dir.
   */
  private final ConcurrentMap<File, Object> myCreateLocks = new ConcurrentHashMap<File, Object>();
  /**
   * In the past jgit has some concurrency problems, in order to fix them we do only one fetch at a time.
   * Also several concurrent fetches in single repository does not make sense since only one of them succeed.
   * This map contains locks used for fetch and push operations.
   */
  private final ConcurrentMap<File, Object> myWriteLocks = new ConcurrentHashMap<File, Object>();
  /**
   * During cleanup unused bare repositories are removed. This map contains rw locks for repository removal.
   * Fetch/push/create operations should be done with read lock hold, remove operation is done with write lock hold.
   * @see Cleaner
   */
  private final ConcurrentMap<File, ReadWriteLock> myRmLocks = new ConcurrentHashMap<File, ReadWriteLock>();


  public RepositoryManagerImpl(@NotNull final ServerPluginConfig config, @NotNull final MirrorManager mirrorManager) {
    myExpirationTimeout = config.getMirrorExpirationTimeoutMillis();
    myMirrorManager = mirrorManager;
  }


  @NotNull
  public File getBaseMirrorsDir() {
    return myMirrorManager.getBaseMirrorsDir();
  }


  @NotNull
  public File getMirrorDir(@NotNull String repositoryUrl) {
    return myMirrorManager.getMirrorDir(repositoryUrl);
  }


  public void invalidate(@NotNull final File dir) {
    myMirrorManager.invalidate(dir);
  }


  @NotNull
  public List<File> getExpiredDirs() {
    long now = System.currentTimeMillis();
    List<File> result = new ArrayList<File>();
    for (File dir : myMirrorManager.getBaseMirrorsDir().listFiles()) {
      if (isExpired(dir, now))
        result.add(dir);
    }
    return result;
  }


  private boolean isExpired(@NotNull final File dir, long now) {
    long lastUsedTime = getLastUsedTime(dir);
    return now - lastUsedTime > myExpirationTimeout;
  }


  private long getLastUsedTime(@NotNull final File dir) {
    File timestamp = new File(dir, "timestamp");
    if (timestamp.exists()) {
      try {
        List<String> lines = FileUtil.readFile(timestamp);
        if (lines.isEmpty())
          return dir.lastModified();
        else
          return Long.valueOf(lines.get(0));
      } catch (IOException e) {
        return dir.lastModified();
      }
    } else {
      return dir.lastModified();
    }
  }


  @NotNull
  public Repository getRepository(@NotNull final URIish fetchUrl) throws VcsException {
    final URIish canonicalURI = getCanonicalURI(fetchUrl);
    final File dir = getMirrorDir(canonicalURI.toString());
    return getRepository(dir, canonicalURI);
  }


  @NotNull
  public Repository getRepository(@NotNull final File dir, @NotNull final URIish fetchUrl) throws VcsException {
    final URIish canonicalURI = getCanonicalURI(fetchUrl);
    if (isDefaultMirrorDir(dir))
      updateLastUsedTime(dir);
    try {
      Repository r = RepositoryCache.open(RepositoryCache.FileKey.exact(dir, FS.DETECTED), true);
      final StoredConfig config = r.getConfig();
      final String existingRemote = config.getString("teamcity", null, "remote");
      if (existingRemote == null || !canonicalURI.toString().equals(existingRemote)) {
        r = createRepository(dir, canonicalURI);
      }
      return r;
    } catch (Exception e) {
      return createRepository(dir, canonicalURI);
    }
  }


  @NotNull
  private Repository createRepository(@NotNull final File dir, @NotNull final URIish fetchUrl) throws VcsException {
    Lock rmLock = getRmLock(dir).readLock();
    rmLock.lock();
    try {
      synchronized (getCreateLock(dir)) {
        Repository result = GitServerUtil.getRepository(dir, fetchUrl);
        RepositoryCache.register(result);
        return result;
      }
    } finally {
      rmLock.unlock();
    }
  }


  private void updateLastUsedTime(@NotNull final File dir) {
    Lock rmLock = getRmLock(dir).readLock();
    try {
      rmLock.lock();
      synchronized (getWriteLock(dir)) {
        File timestamp = new File(dir, "timestamp");
        if (!dir.exists() && !dir.mkdirs())
          throw new IOException("Cannot create directory " + dir.getAbsolutePath());
        if (!timestamp.exists())
          timestamp.createNewFile();
        FileUtil.writeFileAndReportErrors(timestamp, String.valueOf(System.currentTimeMillis()));
      }
    } catch (IOException e) {
      LOG.error("Error while updating timestamp in " + dir.getAbsolutePath(), e);
    } finally {
      rmLock.unlock();
    }
  }


  private boolean isDefaultMirrorDir(@NotNull final File dir) {
    File baseDir = myMirrorManager.getBaseMirrorsDir();
    return baseDir.equals(dir.getParentFile());
  }


  @NotNull
  public Object getWriteLock(@NotNull final File dir) {
    return getOrCreate(myWriteLocks, dir, new Object());
  }


  @NotNull
  public ReadWriteLock getRmLock(@NotNull final File dir) {
    return getOrCreate(myRmLocks, dir, new ReentrantReadWriteLock());
  }


  @NotNull
  public Object getCreateLock(File dir) {
    return getOrCreate(myCreateLocks, dir, new Object());
  }


  private <K, V> V getOrCreate(ConcurrentMap<K, V> map, K key, V value) {
    V existing = map.putIfAbsent(key, value);
    if (existing != null)
      return existing;
    else
      return value;
  }


  @NotNull
  private URIish getCanonicalURI(@NotNull final URIish uri) {
    return new URIish()
      .setScheme(uri.getScheme())
      .setHost(uri.getHost())
      .setPort(uri.getPort())
      .setPath(uri.getPath());
  }
}