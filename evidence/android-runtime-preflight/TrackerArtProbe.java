public final class TrackerArtProbe {
  public static void main(String[] args) {
    System.out.println("TRACKER_ART_VM_RUNNING=" + System.getProperty("java.vm.name"));
    System.out.println("TRACKER_ART_VM_VERSION=" + System.getProperty("java.vm.version"));
  }
}
