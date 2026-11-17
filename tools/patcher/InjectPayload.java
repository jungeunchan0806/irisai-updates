import com.reandroid.apk.ApkModule;
import com.reandroid.archive.InputSource;
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock;
import com.reandroid.arsc.chunk.xml.ResXmlElement;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Adds only our provider, DEX, namespaced assets and new native libraries. */
public final class InjectPayload {
    private static final Pattern STALE_JAR_SIGNATURE = Pattern.compile(
        "META-INF/(?:MANIFEST\\.MF|[^/]+\\.(?:SF|RSA|DSA|EC)|SIG-[^/]+)",
        Pattern.CASE_INSENSITIVE);

    public static void main(String[] args) throws Exception {
        if (args.length != 3 && args.length != 4) throw new IllegalArgumentException("merged-game.apk payload.apk output-unsigned.apk [verified-old-iris-dex-list]");
        try (ApkModule game = ApkModule.loadApkFile(new File(args[0]));
             ApkModule payload = ApkModule.loadApkFile(new File(args[1]))) {
            AndroidManifestBlock manifest = game.getAndroidManifest();
            if (!"com.supercell.brawlstars".equals(manifest.getPackageName()) || manifest.getVersionCode() != 70757)
                throw new IllegalArgumentException("Only the verified 70.757 backup is supported");
            if (args.length == 4) {
                // update_ingame.py verifies the exact previous APK checksum and
                // every class in these DEX files before granting this replacement.
                Set<String> oldDex = new HashSet<>(Arrays.asList(args[3].split(",")));
                if (oldDex.isEmpty() || oldDex.stream().anyMatch(n -> !n.matches("classes[0-9]*\\.dex") || game.getInputSource(n) == null))
                    throw new IllegalArgumentException("Invalid verified Iris DEX list");
                Set<String> irisLibraries = Set.of("lib/arm64-v8a/libopencv_java4.so",
                    "lib/arm64-v8a/libonnxruntime.so", "lib/arm64-v8a/libonnxruntime4j_jni.so");
                game.getZipEntryMap().removeIf(source -> oldDex.contains(source.getAlias())
                    || source.getAlias().startsWith("assets/iris_autogoal/") || irisLibraries.contains(source.getAlias()));
                System.out.println("Replace old Iris DEX: " + oldDex);
            }
            if (manifest.getUsesPermission("android.permission.DETECT_SCREEN_RECORDING") == null)
                manifest.addUsesPermission("android.permission.DETECT_SCREEN_RECORDING");
            ResXmlElement provider = null;
            for (ResXmlElement element : manifest.listApplicationElementsByTag("provider")) {
                if ("com.irisai.autogoal.ingame.InitProvider".equals(AndroidManifestBlock.getAndroidNameValue(element))) {
                    if (provider != null) throw new IllegalStateException("Duplicate Iris provider");
                    provider = element;
                }
            }
            if (provider == null) provider = manifest.getApplicationElement().newElement("provider");
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
            // Keep ServiceLoader registrations, Kotlin/Android metadata and notices.
            // They are application content, not signatures.
            game.getZipEntryMap().removeIf(source ->
                STALE_JAR_SIGNATURE.matcher(source.getAlias()).matches());
            game.setApkSignatureBlock(null);
            game.removeInputSource("stamp-cert-sha256");
            game.writeApk(new File(args[2]));
        }
    }
}
