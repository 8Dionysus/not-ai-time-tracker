import dev.notai.tracker.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Metadata-only investigation on newly created synthetic bytes; no cleanup/delete call. */
public final class LegacyOwnerProbe {
  static final class Metadata implements CopyMetadata {
    private final Path file;
    Metadata(Path file) { this.file=file; }
    public String read() { try { return Files.exists(file) ? Files.readString(file) : null; } catch(Exception e) { throw new RuntimeException(e); } }
    public void writeDurably(String text) { try { Files.writeString(file,text); } catch(Exception e) { throw new RuntimeException(e); } }
  }
  static String hash(Path file) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))); }
  public static void main(String[] args) throws Exception {
    Path root=Path.of(args[0]);Files.createDirectories(root);
    Path bytes=root.resolve("owned-synthetic-copy.bin");Files.writeString(bytes,"Synthetic retained bytes only",StandardCharsets.UTF_8);
    String before=hash(bytes);
    CopyMaintenance gate=new CopyMaintenance(new Metadata(root.resolve("registry.json")),16*1024*1024);
    CopyReference old=gate.acquire("legacy-ui-lifecycle",null,CopyBlocker.UNKNOWN_FILE_OR_STATE,null);
    gate.adoptLifecycle(Set.of("old-copy","other-old-copy"));
    CopyReference migrated=gate.state().getReferences().stream().filter(r->r.getToken().equals(old.getToken())).findFirst().orElseThrow();
    CopyReference known=gate.retainLifetime("explicit-fixture-owner","form:fixture","old-copy",CopyBlocker.LIVE_FORM_REFERENCE,null);
    if(!gate.blockers("old-copy").containsAll(Set.of(CopyBlocker.UNKNOWN_FILE_OR_STATE,CopyBlocker.LIVE_FORM_REFERENCE)))throw new AssertionError();
    gate.finishLifetime("explicit-fixture-owner",Set.of());
    if(gate.state().getReferences().contains(known) || !gate.state().getReferences().contains(migrated))throw new AssertionError();
    if(!gate.blockers("old-copy").equals(Set.of(CopyBlocker.UNKNOWN_FILE_OR_STATE)))throw new AssertionError();
    if(!gate.blockers("new-independent-copy").isEmpty())throw new AssertionError();
    if(!hash(bytes).equals(before))throw new AssertionError();
    System.out.println("{\"legacyReferencePreserved\":true,\"newExactOwnerCompleted\":true,\"oldCopyStillProtected\":true,\"newIndependentCopyUnblocked\":true,\"syntheticBytesUnchanged\":true,\"sha256\":\""+before+"\",\"deletionOrCleanupCalled\":false,\"execution\":\"Metadata-only Java probe, not durability or Android acceptance\"}");
  }
}
