import dev.notai.tracker.*;
public class LegacySegmentHash {
  public static void main(String[] args) {
    String json = "{\"sessions\":[{\"id\":\"legacy-session\",\"status\":\"STOPPED\"}],\"segments\":[{\"id\":\"legacy-segment\",\"sessionId\":\"legacy-session\",\"start\":1000,\"end\":2000,\"durationMs\":1000}]}";
    TransferContent value = ModelKt.getCodec().decodeFromString(TransferContent.Companion.serializer(),json);
    System.out.println(SelectiveTransfer.Companion.contentHash(value));
    System.out.println(ModelKt.getCodec().encodeToString(TransferContent.Companion.serializer(),value));
  }
}
