/*
 * Copyright 2000-2014 JetBrains s.r.o.
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
import jetbrains.buildServer.buildTriggers.vcs.git.submodules.SubmoduleException;
import jetbrains.buildServer.vcs.*;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.NotSupportedException;
import org.eclipse.jgit.errors.TransportException;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.transport.RefSpec;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.*;

import static com.intellij.openapi.util.text.StringUtil.isEmpty;

/**
* @author dmitry.neverov
*/
public class GitCollectChangesPolicy implements CollectChangesBetweenRoots, CollectChangesBetweenRepositories, ChangesInfoBuilder {

  private static final Logger LOG = Logger.getInstance(GitCollectChangesPolicy.class.getName());

  private final GitVcsSupport myVcs;
  private final VcsOperationProgressProvider myProgressProvider;
  private final CommitLoader myCommitLoader;
  private final ServerPluginConfig myConfig;

  public GitCollectChangesPolicy(@NotNull GitVcsSupport vcs,
                                 @NotNull VcsOperationProgressProvider progressProvider,
                                 @NotNull CommitLoader commitLoader,
                                 @NotNull ServerPluginConfig config) {
    myVcs = vcs;
    myProgressProvider = progressProvider;
    myCommitLoader = commitLoader;
    myConfig = config;
  }


  @NotNull
  public List<ModificationData> collectChanges(@NotNull VcsRoot fromRoot,
                                               @NotNull RepositoryStateData fromState,
                                               @NotNull VcsRoot toRoot,
                                               @NotNull RepositoryStateData toState,
                                               @NotNull CheckoutRules checkoutRules) throws VcsException {
    return collectChanges(toRoot, fromState, toState, checkoutRules);
  }

  @NotNull
  public List<ModificationData> collectChanges(@NotNull VcsRoot root,
                                               @NotNull RepositoryStateData fromState,
                                               @NotNull RepositoryStateData toState,
                                               @NotNull CheckoutRules checkoutRules) throws VcsException {
    List<ModificationData> changes = new ArrayList<ModificationData>();
    OperationContext context = myVcs.createContext(root, "collecting changes", createProgress());
    try {
      Repository r = context.getRepository();
      ModificationDataRevWalk revWalk = new ModificationDataRevWalk(myConfig, context);
      revWalk.sort(RevSort.TOPO);
      ensureRepositoryStateLoadedFor(context, r, true, toState, fromState);
      markStart(r, revWalk, toState);
      markUninteresting(r, revWalk, fromState, toState);
      while (revWalk.next() != null) {
        changes.add(revWalk.createModificationData());
      }
    } catch (Exception e) {
      if (e instanceof SubmoduleException) {
        SubmoduleException se = (SubmoduleException) e;
        Set<String> affectedBranches = getBranchesWithCommit(context.getRepository(), toState, se.getMainRepositoryCommit());
        throw context.wrapException(se.addBranches(affectedBranches));
      }
      throw context.wrapException(e);
    } finally {
      context.close();
    }
    return changes;
  }


  @NotNull
  private Set<String> getBranchesWithCommit(@NotNull Repository r, @NotNull RepositoryStateData state, @NotNull String commit) {
    try {
      RevWalk revWalk = new RevWalk(r);
      revWalk.sort(RevSort.REVERSE);
      revWalk.sort(RevSort.TOPO, true);
      Set<String> reverseReachable = new HashSet<String>();
      RevCommit revCommit = revWalk.parseCommit(ObjectId.fromString(commit));
      revWalk.markStart(revCommit);
      RevCommit c;
      while ((c = revWalk.next()) != null) {
        reverseReachable.add(c.name());
      }

      Set<String> branches = new HashSet<String>();
      for (Map.Entry<String, String> entry : state.getBranchRevisions().entrySet()) {
        String branchName = entry.getKey();
        String branchRevision = entry.getValue();
        if (reverseReachable.contains(branchRevision))
          branches.add(branchName);
      }

      return branches;
    } catch (Exception e1) {
      return Collections.emptySet();
    }
  }


  @NotNull
  public RepositoryStateData getCurrentState(@NotNull VcsRoot root) throws VcsException {
    return myVcs.getCurrentState(root);
  }

  @NotNull
  public List<ModificationData> collectChanges(@NotNull VcsRoot fromRoot,
                                               @NotNull String fromVersion,
                                               @NotNull VcsRoot toRoot,
                                               @Nullable String toVersion,
                                               @NotNull CheckoutRules checkoutRules) throws VcsException {
    logCollectChanges(fromRoot, fromVersion, toRoot, toVersion);
    if (toVersion == null) {
      LOG.warn("Version of root " + LogUtil.describe(toRoot) + " is null, return empty list of changes");
      return Collections.emptyList();
    }
    String forkPoint = getLastCommonVersion(fromRoot, fromVersion, toRoot, toVersion);
    return collectChanges(toRoot, forkPoint, toVersion, checkoutRules);
  }

  @NotNull
  public List<ModificationData> collectChanges(@NotNull VcsRoot root,
                                               @NotNull String fromVersion,
                                               @Nullable String currentVersion,
                                               @NotNull CheckoutRules checkoutRules) throws VcsException {
    List<ModificationData> result = new ArrayList<ModificationData>();
    OperationContext context = myVcs.createContext(root, "collecting changes", createProgress());
    try {
      logCollectChanges(fromVersion, currentVersion, context);
      if (currentVersion == null) {
        LOG.warn("Current version is null for " + context.getGitRoot().debugInfo() + ", return empty list of changes");
        return result;
      }
      String upperBoundSHA = GitUtils.versionRevision(currentVersion);
      myCommitLoader.loadCommit(context, context.getGitRoot(), upperBoundSHA);
      String lowerBoundSHA = GitUtils.versionRevision(fromVersion);
      Repository r = context.getRepository();
      result.addAll(getModifications(context, r, upperBoundSHA, lowerBoundSHA));
    } catch (Exception e) {
      throw context.wrapException(e);
    } finally {
      context.close();
    }
    return result;
  }

  public void ensureRepositoryStateLoadedFor(@NotNull final OperationContext context,
                                             @NotNull final Repository repo,
                                             final boolean failOnFirstError,
                                             @NotNull final RepositoryStateData... states) throws Exception {
    boolean isFirst = failOnFirstError;
    if (myConfig.usePerBranchFetch()) {
      for (RepositoryStateData state : states) {
        ensureRepositoryStateLoadedOneFetchPerBranch(context, state, isFirst);
        isFirst = false;
      }
    } else {
      FetchAllRefs fetch = new FetchAllRefs(context.getProgress(), repo, context.getGitRoot(), states);
      for (RepositoryStateData state : states) {
        ensureRepositoryStateLoaded(context, repo, state, fetch, isFirst);
        isFirst = false;
      }
    }
  }

  @NotNull
  public RepositoryStateData fetchAllRefs(@NotNull final OperationContext context,
                                          @NotNull final GitVcsRoot root) throws VcsException {
    try {
      final RepositoryStateData currentState = myVcs.getCurrentState(root);
      new FetchAllRefs(context.getProgress(), context.getRepository(), context.getGitRoot(), currentState).run();
      return currentState;
    } catch (TransportException e) {
      throw new VcsException(e.getMessage(), e);
    } catch (NotSupportedException e) {
      throw new VcsException(e.getMessage(), e);
    }
  }

  @NotNull
  public RepositoryStateData fetchAllRefs(@NotNull VcsRoot root) throws VcsException {
    final OperationContext context = myVcs.createContext(root, "fetch all");
    try {
      return fetchAllRefs(context, context.getGitRoot());
    } finally {
      context.close();
    }
  }


  public void fetchChangesInfo(@NotNull VcsRoot root,
                               @NotNull CheckoutRules checkoutRules,
                               @NotNull Collection<String> revisions,
                               @NotNull ChangesConsumer consumer) throws VcsException {
    OperationContext context = myVcs.createContext(root, "collecting changes");
    try {
      final Repository r = context.getRepository();

      for (String commitId : revisions) {
        final String branch = "fake-branch-" + commitId;
        final RepositoryStateData oneCommitData = RepositoryStateData.createVersionState(branch, commitId);

        final ModificationDataRevWalk revWalk = new ModificationDataRevWalk(myConfig, context);

        revWalk.sort(RevSort.TOPO);
        markStart(r, revWalk, oneCommitData);

        final List<RevCommit> commits = getCommits(oneCommitData, r, revWalk);
        if (commits.isEmpty()) {
          throw new VcsException("Commit was not found: " + commitId);
        }

        for (RevCommit commit : commits) {
          for (RevCommit parent : commit.getParents()) {
            revWalk.markUninteresting(parent);
          }
        }

        while (revWalk.next() != null) {
          consumer.consumeChange(revWalk.createModificationData());
        }
      }
    } catch (Exception e) {
      throw context.wrapException(e);
    } finally {
      context.close();
    }
  }

  private void ensureRepositoryStateLoaded(@NotNull OperationContext context,
                                           @NotNull Repository db,
                                           @NotNull RepositoryStateData state,
                                           @NotNull FetchAllRefs fetch,
                                           boolean throwErrors) throws Exception {
    GitVcsRoot root = context.getGitRoot();
    for (Map.Entry<String, String> entry : state.getBranchRevisions().entrySet()) {
      String ref = entry.getKey();
      String revision = GitUtils.versionRevision(entry.getValue());
      RevCommit commit = myCommitLoader.findCommit(db, revision);
      if (commit != null)
        continue;

      if (!fetch.isInvoked())
        fetch.run();

      try {
        myCommitLoader.getCommit(db, ObjectId.fromString(revision));
      } catch (IncorrectObjectTypeException e) {
        LOG.warn("Ref " + ref + " points to a non-commit " + revision);
      } catch (Exception e) {
        if (throwErrors) {
          throw new VcsException("Cannot find revision " + revision + " in branch " + ref + " in VCS root " + LogUtil.describe(root), e);
        } else {
          LOG.warn("Cannot find revision " + revision + " in branch " + ref + " in VCS root " + LogUtil.describe(root));
        }
      }
    }
  }

  private void ensureRepositoryStateLoadedOneFetchPerBranch(@NotNull OperationContext context, @NotNull RepositoryStateData state, boolean throwErrors) throws Exception {
    GitVcsRoot root = context.getGitRoot();
    for (Map.Entry<String, String> entry : state.getBranchRevisions().entrySet()) {
      String branch = entry.getKey();
      String revision = entry.getValue();
      GitVcsRoot branchRoot = root.getRootForBranch(branch);
      try {
        myCommitLoader.loadCommit(context, branchRoot, GitUtils.versionRevision(revision));
      } catch (Exception e) {
        if (throwErrors) {
          throw e;
        } else {
          LOG.warn("Cannot find revision " + revision + " in branch " + branch + " of root " + LogUtil.describe(context.getGitRoot()));
        }
      }
    }
  }

  private void markUninteresting(@NotNull Repository r,
                                 @NotNull ModificationDataRevWalk walk,
                                 @NotNull final RepositoryStateData fromState,
                                 @NotNull final RepositoryStateData toState) throws IOException {
    List<RevCommit> commits = getCommits(fromState, r, walk);
    if (commits.isEmpty())//if non of fromState revisions found - limit commits by toState
      commits = getCommits(toState, r, walk);
    for (RevCommit commit : commits) {
      walk.markUninteresting(commit);
    }
  }


  private void markStart(@NotNull Repository r, @NotNull RevWalk walk, @NotNull RepositoryStateData state) throws IOException {
    walk.markStart(getCommits(state, r, walk));
  }


  private List<RevCommit> getCommits(@NotNull RepositoryStateData state, @NotNull Repository r, @NotNull RevWalk walk) throws IOException {
    List<RevCommit> revisions = new ArrayList<RevCommit>();
    for (String revision : state.getBranchRevisions().values()) {
      ObjectId id = ObjectId.fromString(GitUtils.versionRevision(revision));
      if (r.hasObject(id)) {
        RevObject obj = walk.parseAny(id);
        if (obj.getType() == Constants.OBJ_COMMIT)
          revisions.add((RevCommit) obj);
      }
    }
    return revisions;
  }


  private String getLastCommonVersion(@NotNull VcsRoot baseRoot,
                                      @NotNull String baseVersion,
                                      @NotNull VcsRoot tipRoot,
                                      @NotNull String tipVersion) throws VcsException {
    OperationContext context = myVcs.createContext(tipRoot, "find fork version");
    GitVcsRoot baseGitRoot = context.getGitRoot(baseRoot);
    GitVcsRoot tipGitRoot = context.getGitRoot();
    logFindLastCommonAncestor(baseVersion, tipVersion, baseGitRoot, tipGitRoot);
    RevWalk walk = null;
    try {
      RevCommit baseCommit = myCommitLoader.loadCommit(context, baseGitRoot, baseVersion);
      RevCommit tipCommit = myCommitLoader.loadCommit(context, tipGitRoot, tipVersion);
      Repository tipRepository = context.getRepository(tipGitRoot);
      walk = new RevWalk(tipRepository);
      walk.setRevFilter(RevFilter.MERGE_BASE);
      walk.markStart(walk.parseCommit(baseCommit.getId()));
      walk.markStart(walk.parseCommit(tipCommit.getId()));
      final RevCommit base = walk.next();
      String result = base.getId().name();
      logLastCommonAncestor(baseGitRoot, tipGitRoot, result);
      return result;
    } catch (Exception e) {
      throw context.wrapException(e);
    } finally {
      try {
        if (walk != null)
          walk.release();
      } finally {
        context.close();
      }
    }
  }

  private List<ModificationData> getModifications(@NotNull final OperationContext context,
                                                  @NotNull final Repository r,
                                                  @NotNull final String upperBoundSHA,
                                                  @NotNull final String lowerBoundSHA) throws VcsException, IOException {
    List<ModificationData> modifications = new ArrayList<ModificationData>();
    ModificationDataRevWalk revWalk = new ModificationDataRevWalk(myConfig, context);
    revWalk.sort(RevSort.TOPO);
    try {
      revWalk.markStart(revWalk.parseCommit(ObjectId.fromString(upperBoundSHA)));
      ObjectId lowerBoundId = ObjectId.fromString(lowerBoundSHA);
      if (r.hasObject(lowerBoundId)) {
        revWalk.markUninteresting(revWalk.parseCommit(lowerBoundId));
      } else {
        logFromRevisionNotFound(lowerBoundSHA);
        revWalk.limitByNumberOfCommits(myConfig.getNumberOfCommitsWhenFromVersionNotFound());
      }
      while (revWalk.next() != null) {
        modifications.add(revWalk.createModificationData());
      }
      return modifications;
    } finally {
      revWalk.release();
    }
  }

  private void logCollectChanges(@NotNull VcsRoot fromRoot,
                                 @NotNull String fromVersion,
                                 @NotNull VcsRoot toRoot,
                                 @Nullable String toVersion) {
    LOG.debug("Collecting changes [" + LogUtil.describe(fromRoot) + "-" + fromVersion+ "].." +
              "[" + LogUtil.describe(toRoot) + "-" + toVersion + "]");
  }

  private void logCollectChanges(@NotNull String fromVersion,
                                 @Nullable String currentVersion,
                                 @NotNull OperationContext context) throws VcsException {
    LOG.debug("Collecting changes " + fromVersion + ".." + currentVersion + " for " + context.getGitRoot().debugInfo());
  }

  private void logFindLastCommonAncestor(@NotNull String baseVersion,
                                         @NotNull String tipVersion,
                                         @NotNull GitVcsRoot baseGitRoot,
                                         @NotNull GitVcsRoot tipGitRoot) {
    LOG.debug("Find last common version between [" + baseGitRoot.debugInfo() + "-" + baseVersion + "].." +
              "[" + tipGitRoot.debugInfo() + "-" + tipVersion + "]");
  }

  private void logLastCommonAncestor(@NotNull GitVcsRoot baseGitRoot,
                                     @NotNull GitVcsRoot tipGitRoot,
                                     @NotNull String ancestor) {
    LOG.debug("Last common revision between " + baseGitRoot.debugInfo() + " and " + tipGitRoot.debugInfo() + " is " + ancestor);
  }

  private void logFromRevisionNotFound(@NotNull String lowerBoundSHA) {
    LOG.warn("From version " + lowerBoundSHA + " is not found, collect last " +
             myConfig.getNumberOfCommitsWhenFromVersionNotFound() + " commits");
  }

  @NotNull
  private GitProgress createProgress() {
    try {
      return new GitVcsOperationProgress(myProgressProvider.getProgress());
    } catch (IllegalStateException e) {
      return GitProgress.NO_OP;
    }
  }

  private class FetchAllRefs {
    private final GitProgress myProgress;
    private final Repository myDb;
    private final GitVcsRoot myRoot;
    private final Set<String> myAllRefNames;
    private boolean myInvoked = false;

    private FetchAllRefs(@NotNull GitProgress progress,
                         @NotNull Repository db,
                         @NotNull GitVcsRoot root,
                         @NotNull RepositoryStateData... states) {
      myProgress = progress;
      myDb = db;
      myRoot = root;
      myAllRefNames = getAllRefNames(states);
    }

    void run() throws NotSupportedException, VcsException, TransportException {
      myInvoked = true;
      FetchSettings settings = new FetchSettings(myRoot.getAuthSettings(), myProgress);
      myCommitLoader.fetch(myDb, myRoot.getRepositoryFetchURL(), calculateRefSpecsForFetch(), settings);
    }

    boolean isInvoked() {
      return myInvoked;
    }

    private Collection<RefSpec> calculateRefSpecsForFetch() throws VcsException {
      List<RefSpec> specs = new ArrayList<RefSpec>();
      Map<String, Ref> remoteRepositoryRefs = myVcs.getRemoteRefs(myRoot.getOriginalRoot());
      for (String ref : myAllRefNames) {
        if (remoteRepositoryRefs.containsKey(ref))
          specs.add(new RefSpec(ref + ":" + ref).setForceUpdate(true));
      }
      return specs;
    }

    private Set<String> getAllRefNames(@NotNull RepositoryStateData... states) {
      Set<String> refs = new HashSet<String>();
      for (RepositoryStateData state : states) {
        for (String ref : state.getBranchRevisions().keySet()) {
          if (!isEmpty(ref))
            refs.add(GitUtils.expandRef(ref));
        }
      }
      return refs;
    }
  }
}
