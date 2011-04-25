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
import jetbrains.buildServer.buildTriggers.vcs.git.submodules.SubmoduleResolver;
import jetbrains.buildServer.buildTriggers.vcs.git.submodules.TeamCitySubmoduleResolver;
import jetbrains.buildServer.util.StringUtil;
import jetbrains.buildServer.vcs.VcsException;
import jetbrains.buildServer.vcs.VcsRoot;
import org.eclipse.jgit.errors.TransportException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;

import static jetbrains.buildServer.buildTriggers.vcs.git.SubmodulesCheckoutPolicy.getPolicyWithErrorsIgnored;
import static jetbrains.buildServer.buildTriggers.vcs.git.submodules.SubmoduleAwareTreeIterator.create;

/**
 *
 */
public class OperationContext {

  private static Logger LOG = Logger.getInstance(OperationContext.class.getName());

  private final GitVcsSupport mySupport;
  private final VcsRoot myRoot;
  private final String myOperation;
  private final Map<Long, Settings> myRootSettings = new HashMap<Long, Settings>(); //root id -> settings
  private final Map<String, Repository> myRepositories = new HashMap<String, Repository>(); //repository path -> repository

  public OperationContext(GitVcsSupport support, VcsRoot root, String operation) {
    mySupport = support;
    myRoot = root;
    myOperation = operation;
  }


  public VcsRoot getRoot() {
    return myRoot;
  }

  public String getOperation() {
    return myOperation;
  }

  public GitVcsSupport getSupport() {
    return mySupport;
  }

  public Map<String, Repository> getRepositories() {
    return myRepositories;
  }

  public Repository getRepository() throws VcsException {
    return getRepository(getSettings());
  }

  public Repository getRepository(Settings settings) throws VcsException {
    return getRepository(settings.getRepositoryDir(), settings.getRepositoryFetchURL());
  }

  public Repository getRepository(File repositoryDir, URIish fetchUrl) throws VcsException {
    Repository result = myRepositories.get(repositoryDir.getPath());
    if (result == null) {
      result = mySupport.getRepository(repositoryDir, fetchUrl);
      myRepositories.put(repositoryDir.getPath(), result);
    }
    return result;
  }

  public Repository getRepositoryFor(File dir) {
    return myRepositories.get(dir.getPath());
  }

  public Settings getSettings() throws VcsException {
    return getSettings(myRoot);
  }

  public Settings getSettings(VcsRoot root) throws VcsException {
    Settings s = myRootSettings.get(root.getId());
    if (s == null) {
      s = createSettings(myRoot);
      myRootSettings.put(root.getId(), s);
    }
    return s;
  }

  private Settings createSettings(VcsRoot root) throws VcsException {
    return new Settings(root, mySupport.getCachesDir());
  }

  public VcsException wrapException(Exception ex) {
    if (LOG.isDebugEnabled()) {
      LOG.debug("The error during GIT vcs operation " + myOperation, ex);
    }
    if (ex instanceof VcsException) {
      return (VcsException)ex;
    }

    String message;
    if (ex instanceof TransportException && ex.getCause() != null) {
      Throwable t = ex.getCause();
      if (t instanceof FileNotFoundException) {
        message = "File not found: " + t.getMessage();
      } else if (t instanceof UnknownHostException) {
        message = "Unknown host: " + t.getMessage();
      } else {
        message = t.toString();
      }
    } else {
      message = ex.toString();
    }
    return new VcsException(StringUtil.capitalize(myOperation) + " failed: " + message, ex);
  }

  /**
   * Release all resources acquired during operation
   */
  public void close() {
    RuntimeException e = null;
    for (Repository r : myRepositories.values()) {
      try {
        r.close();
      } catch (RuntimeException ex) {
        LOG.error("Exception during closing repository: " + r, ex);
        e = ex;
      }
    }
    if (e != null) {
      throw e;
    }
  }

  /**
   * Adds tree to tree walker.
   * If we should checkout submodules - adds submodule-aware tree iterator
   */
  public void addTree(TreeWalk tw, Repository db, RevCommit commit, boolean ignoreSubmodulesErrors) throws IOException, VcsException {
    Settings s = getSettings();
    if (getSettings().isCheckoutSubmodules()) {
      SubmoduleResolver submoduleResolver = new TeamCitySubmoduleResolver(this, db, commit);
      SubmodulesCheckoutPolicy checkoutPolicy = getPolicyWithErrorsIgnored(s.getSubmodulesCheckoutPolicy(), ignoreSubmodulesErrors);
      tw.addTree(create(db, commit, submoduleResolver, s.getRepositoryFetchURL().toString(), "", checkoutPolicy));
    } else {
      tw.addTree(commit.getTree().getId());
    }
  }
}