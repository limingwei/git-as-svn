package svnserver.repository.git;

import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.revwalk.RevCommit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.SVNNodeKind;
import org.tmatesoft.svn.core.SVNProperty;
import svnserver.StringHelper;
import svnserver.repository.VcsFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Git file.
 *
 * @author Artem V. Navrotskiy <bozaro@users.noreply.github.com>
 */
public class GitFile implements VcsFile {
  @NotNull
  private final GitRepository repo;
  @NotNull
  private final GitObject<ObjectId> objectId;
  @NotNull
  private final FileMode fileMode;
  @NotNull
  private final String fullPath;
  private final int lastChange;
  @Nullable
  private ObjectLoader objectLoader;

  public GitFile(@NotNull GitRepository repo, @NotNull GitObject<ObjectId> objectId, @NotNull FileMode fileMode, @NotNull String fullPath, int revision) {
    this.repo = repo;
    this.objectId = objectId;
    this.fileMode = fileMode;
    this.fullPath = fullPath;
    this.lastChange = repo.getLastChange(fullPath, revision);
  }

  @NotNull
  @Override
  public String getFileName() {
    return StringHelper.baseName(fullPath);
  }

  @NotNull
  public String getFullPath() {
    return fullPath;
  }

  @NotNull
  public FileMode getFileMode() {
    return fileMode;
  }

  @NotNull
  public GitObject<ObjectId> getObjectId() {
    return objectId;
  }

  @NotNull
  @Override
  public Map<String, String> getProperties(boolean includeInternalProps) throws IOException, SVNException {
    final Map<String, String> props = new HashMap<>();
    if (fileMode.equals(FileMode.EXECUTABLE_FILE)) {
      props.put(SVNProperty.EXECUTABLE, "*");
    } else if (fileMode.equals(FileMode.SYMLINK)) {
      props.put(SVNProperty.SPECIAL, "*");
    }
    if (includeInternalProps) {
      final GitRevision last = getLastChange();
      props.put(SVNProperty.UUID, repo.getUuid());
      props.put(SVNProperty.COMMITTED_REVISION, String.valueOf(last.getId()));
      props.put(SVNProperty.COMMITTED_DATE, last.getDate());
      props.put(SVNProperty.LAST_AUTHOR, last.getAuthor());
    }
    return props;
  }

  @NotNull
  @Override
  public String getMd5() throws IOException {
    return repo.getObjectMD5(objectId);
  }

  @Override
  public long getSize() throws IOException {
    return ((fileMode.getBits() & (FileMode.TYPE_FILE | FileMode.TYPE_GITLINK)) == FileMode.TYPE_FILE) ? getObjectLoader().getSize() : 0;
  }

  @Override
  public boolean isDirectory() throws IOException {
    return getKind().equals(SVNNodeKind.DIR);
  }

  @NotNull
  @Override
  public SVNNodeKind getKind() {
    return GitHelper.getKind(fileMode);
  }

  private ObjectLoader getObjectLoader() throws IOException {
    if (objectLoader == null) {
      objectLoader = repo.openObject(objectId);
    }
    return objectLoader;
  }

  @Override
  public void copyTo(@NotNull OutputStream stream) throws IOException {
    getObjectLoader().copyTo(stream);
  }

  @NotNull
  @Override
  public InputStream openStream() throws IOException {
    return getObjectLoader().openStream();
  }

  @NotNull
  @Override
  public Iterable<VcsFile> getEntries() throws IOException {
    if (fileMode.equals(FileMode.TREE)) {
      return getEntries(GitRepository.loadTree(objectId));
    }
    if (fileMode.equals(FileMode.GITLINK)) {
      GitObject<RevCommit> linkedCommit = repo.loadLinkedCommit(objectId.getObject());
      if (linkedCommit == null) {
        return Collections.emptyList();
      }
      return getEntries(GitRepository.loadTree(new GitObject<>(linkedCommit.getRepo(), linkedCommit.getObject().getTree())));
    }
    throw new IOException("Unsupported operation for: " + fullPath + " (mode: " + fileMode + ")");
  }

  private Iterable<VcsFile> getEntries(@NotNull Map<String, GitTreeEntry> treeEntries) {
    return treeEntries.entrySet()
        .stream()
        .map(entry -> new GitFile(repo, entry.getValue().getObjectId(), entry.getValue().getFileMode(), StringHelper.joinPath(fullPath, entry.getKey()), lastChange))
        .collect(Collectors.toList());
  }

  @NotNull
  @Override
  public GitRevision getLastChange() throws IOException, SVNException {
    return repo.getRevisionInfo(lastChange);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    GitFile that = (GitFile) o;
    return fileMode.equals(that.fileMode)
        && fullPath.equals(that.fullPath)
        && (lastChange == that.lastChange);
  }

  @Override
  public int hashCode() {
    int result = objectId.hashCode();
    result = 31 * result + fileMode.hashCode();
    result = 31 * result + fullPath.hashCode();
    result = 31 * result + lastChange;
    return result;
  }

  @Override
  public String toString() {
    return "GitFileInfo{" +
        "fullPath='" + fullPath + '\'' +
        ", objectId=" + objectId +
        '}';
  }
}
