package svnserver.parser;

import org.testng.Assert;
import org.testng.annotations.Test;
import org.tmatesoft.svn.core.SVNCommitInfo;
import org.tmatesoft.svn.core.SVNDepth;
import org.tmatesoft.svn.core.io.ISVNEditor;
import org.tmatesoft.svn.core.io.SVNRepository;
import org.tmatesoft.svn.core.io.SVNRepositoryFactory;
import org.tmatesoft.svn.core.wc.SVNClientManager;
import org.tmatesoft.svn.core.wc.SVNRevision;
import org.tmatesoft.svn.core.wc2.SvnCheckout;
import org.tmatesoft.svn.core.wc2.SvnOperationFactory;
import org.tmatesoft.svn.core.wc2.SvnTarget;

import java.io.File;
import java.util.ArrayList;

import static svnserver.parser.SvnTestHelper.sendDeltaAndClose;

/**
 * Simple checkout tests.
 *
 * @author Artem V. Navrotskiy <bozaro@users.noreply.github.com>
 */
public class SvnCheckoutTest {
  @Test(timeOut = 60 * 1000)
  public void checkoutRootRevision() throws Exception {
    try (SvnTestServer server = SvnTestServer.createEmpty()) {
      final SvnOperationFactory factory = new SvnOperationFactory();
      factory.setAuthenticationManager(server.getAuthenticator());
      final SvnCheckout checkout = factory.createCheckout();
      checkout.setSource(SvnTarget.fromURL(server.getUrl()));
      checkout.setSingleTarget(SvnTarget.fromFile(server.getTempDirectory()));
      checkout.setRevision(SVNRevision.create(0));
      checkout.run();
    }
  }

  /**
   * <pre>
   * svn checkout
   * echo > test.txt
   * svn commit -m "create test.txt"
   *   rev N
   * echo foo > test.txt
   * svn commit -m "modify test.txt"
   * svn up rev N
   * </pre>
   *
   * @throws Exception
   */
  @Test(timeOut = 60 * 1000)
  public void checkoutAndUpdate() throws Exception {
    try (SvnTestServer server = SvnTestServer.createEmpty()) {
      final SVNRepository repo = SVNRepositoryFactory.create(server.getUrl());
      repo.setAuthenticationManager(server.getAuthenticator());
      final ISVNEditor editor = repo.getCommitEditor("Intital state", null, false, null);
      editor.openRoot(-1);
      editor.addDir("/src", null, -1);
      editor.addDir("/src/main", null, -1);
      editor.addFile("/src/main/source.txt", null, -1);
      sendDeltaAndClose(editor, "/src/main/source.txt", null, "Source content");
      editor.closeDir();
      editor.addDir("/src/test", null, -1);
      editor.addFile("/src/test/test.txt", null, -1);
      sendDeltaAndClose(editor, "/src/test/test.txt", null, "Test content");
      editor.closeDir();
      editor.closeDir();
      editor.closeDir();
      editor.closeEdit();

      // checkout
      final SvnOperationFactory factory = new SvnOperationFactory();
      factory.setAuthenticationManager(server.getAuthenticator());

      final SvnCheckout checkout = factory.createCheckout();
      checkout.setSource(SvnTarget.fromURL(server.getUrl()));
      checkout.setSingleTarget(SvnTarget.fromFile(server.getTempDirectory()));
      checkout.setRevision(SVNRevision.HEAD);
      checkout.run();

      final File file = new File(server.getTempDirectory(), "src/main/someFile.txt");
      final SVNClientManager client = SVNClientManager.newInstance(factory);
      // create file
      final SVNCommitInfo commit;
      {
        Assert.assertFalse(file.exists());
        TestHelper.saveFile(file, "New content");
        client.getWCClient().doAdd(file, false, false, false, SVNDepth.INFINITY, false, true);
        commit = client.getCommitClient().doCommit(new File[]{file}, false, "Commit new file", null, null, false, false, SVNDepth.INFINITY);
      }
      // modify file
      {
        Assert.assertTrue(file.exists());
        TestHelper.saveFile(file, "Modified content");
        client.getCommitClient().doCommit(new File[]{file}, false, "Modify up-to-date commit", null, null, false, false, SVNDepth.INFINITY);
      }
      // update to previous commit
      client.getUpdateClient().doUpdate(server.getTempDirectory(), SVNRevision.create(commit.getNewRevision()), SVNDepth.INFINITY, false, false);
      // check no tree conflist
      ArrayList<String> changeLists = new ArrayList<>();
      client.getStatusClient().doStatus(server.getTempDirectory(), SVNRevision.WORKING, SVNDepth.INFINITY, false, false, true, false, status -> {
        Assert.assertNull(status.getTreeConflict(), status.getFile().toString());
        Assert.assertNull(status.getConflictNewFile(), status.getFile().toString());
      }, changeLists);
      Assert.assertTrue(changeLists.isEmpty());
    }
  }
}
