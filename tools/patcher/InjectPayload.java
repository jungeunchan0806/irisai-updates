import com.reandroid.apk.ApkModule;
import com.reandroid.archive.InputSource;
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock;
import com.reandroid.arsc.chunk.xml.ResXmlElement;
import java.io.File;
import java.util.ArrayList;

/** Adds only our provider, DEX, namespaced assets and new native libraries. */
public final class InjectPayload {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("merged-game.apk payload.apk output-unsigned.apk");
        try (ApkModule game = ApkModule.loadApkFile(new File(args[0]));
             ApkModule payload = ApkModule.loadApkFile(new File(args[1]))) {
            AndroidManifestBlock manifest = game.getAndroidManifest();
            if (!"com.supercell.brawlstars".equals(manifest.getPackageName()) || manifest.getVersionCode() != 70757)
                throw new IllegalArgumentException("Only the verified 70.757 backup is supported");
            ResXmlElement provider = manifest.getApplicationElement().newElement("provider");
            provider.getOrCreateAndroidAttribute("name", 0x01010003).setValueAsString("com.irisai.autogoal.ingame.InitProvider");
            provider.getOrCreateAndroidAttribute("authorities", 0x01010018).setValueAsString("com.supercell.brawlstars.irisautogoal.init");
            provider.getOrCreateAndroidAttribute("exported", 0x01010010).setValueAsBoolean(false);
            manifest.refreshFull();
            game.keepManifestChanges();
            int dex = 1;
            for (InputSource source : new ArrayList<>(payload.listInputSources())) {
                String name = source.getAlias();
                if (name.matches("classes[0-9]*\\.dex")) {
                    String destination;
                    do { destination = "classes" + (dex == 1 ? "" : dex) + ".dex"; dex++; }
                    while (game.getInputSource(destination) != null);
                    source.setAlias(destination); game.add(source);
                    System.out.println("Add " + destination);
                } else if (name.startsWith("assets/iris_autogoal/") || name.startsWith("lib/arm64-v8a/")) {
                    if (game.getInputSource(name) != null) {
                        // Keep the game's C++ runtime. validate_apk.py checks its exports satisfy our JNI libraries.
                        if (!name.endsWith("/libc++_shared.so")) throw new IllegalStateException("File collision: " + name);
                        System.out.println("Keep original " + name); continue;
                    }
                    game.add(source);
                    if (name.endsWith(".so")) System.out.println("Add " + name);
                }
            }
            game.getZipEntryMap().removeDir("META-INF");
            game.removeInputSource("stamp-cert-sha256");
            game.writeApk(new File(args[2]));
        }
    }
}
