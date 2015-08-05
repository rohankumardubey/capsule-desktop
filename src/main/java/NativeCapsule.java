/*
 * Copyright (c) 2014-2015, Parallel Universe Software Co. and Contributors. All rights reserved.
 *
 * This program and the accompanying materials are licensed under the terms
 * of the Eclipse Public License v1.0, available at
 * http://www.eclipse.org/legal/epl-v10.html
 */

import capsule.GUIListener;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map.Entry;
import co.paralleluniverse.capsule.Jar;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import net.sf.launch4j.Builder;
import net.sf.launch4j.Log;
import net.sf.launch4j.config.Config;
import net.sf.launch4j.config.ConfigPersister;
import net.sf.launch4j.config.Jre;
import net.sf.launch4j.config.SingleInstance;
import net.sf.launch4j.config.VersionInfo;

public class NativeCapsule extends Capsule {
    private static final String PROP_VERSION = OPTION("capsule.build", "false", "build", true, "Builds the native application.");

    protected static final Entry<String, Boolean> ATTR_GUI = ATTRIBUTE("GUI", T_BOOL(), false, true, "Whether or not this Capsule uses a GUI");
    protected static final Entry<String, String> ATTR_ICON = ATTRIBUTE("Icon", T_STRING(), null, true, "The path of the application's icon file(s), with no suffix, relative to the capsule root");
    protected static final Entry<String, Boolean> ATTR_SINGLE_INSTANCE = ATTRIBUTE("Single-Instance", T_BOOL(), false, true, "Whether or not the application should only have a single running instance at a time");

    protected static final Entry<String, String> ATTR_IMPLEMENTATION_VENDOR = ATTRIBUTE("Implementation-Vendor", T_STRING(), null, true, null);

    private static final String GUI_CAPSULE_NAME = "GUICapsule";
    private static final String MAVEN_CAPSULE_NAME = "MavenCapsule";
    private static final String GUI_MAVEN_CAPSULE_NAME = "GUIMavenCapsule";

    public NativeCapsule(Capsule pred) {
        super(pred);
    }

    public NativeCapsule(Path jarFile) {
        super(jarFile);
    }

    void build(List<String> args) {
        final Path out = Paths.get(args.get(0));
        try {
            buildWindowsApp(out);
            buildLinuxApp(out);
            buildMacApp(out);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private String getSimpleCapsuleName() {
        final String filename = getJarFile().getFileName().toString();
        return filename.endsWith(".jar") ? filename.substring(0, filename.length() - 4) : filename;
    }

    private Jar createJar(Path out) throws IOException {
        final Jar jar = new Jar(getJarFile());
        if (out != null)
            jar.setOutput(out);
        return jar;
    }

    private boolean isGUIApp() {
        return getAttribute(ATTR_GUI);
    }

    private Path buildWindowsApp(Path out) throws IOException {
        setLaunch4JBinDir();
        setLaunch4JLibDir();
        setLaunch4JTmpDir();

        Path tmpJar = null;
        Path icon = null;
        try {
            if (isGUIApp()) {
                tmpJar = Files.createTempFile("native-capsule-", ".jar");
                Jar j = createJar(tmpJar);
                makeGUICapsule(j);
                j.close();
            }
            final Path jar = tmpJar != null ? tmpJar : getJarFile();

            ConfigPersister.getInstance().createBlank();
            final Config c = ConfigPersister.getInstance().getConfig();
            c.setHeaderType(isGUIApp() ? Config.GUI_HEADER : Config.CONSOLE_HEADER);
            c.setOutfile(withSuffix(out, ".exe").toFile());
            c.setJar(jar.toFile());

            if (hasAttribute(ATTR_MIN_JAVA_VERSION))
                c.getJre().setMinVersion(getAttribute(ATTR_MIN_JAVA_VERSION));
            if (hasAttribute(ATTR_JAVA_VERSION))
                c.getJre().setMaxVersion(getAttribute(ATTR_JAVA_VERSION));
            if (hasAttribute(ATTR_JDK_REQUIRED))
                c.getJre().setJdkPreference(getAttribute(ATTR_JDK_REQUIRED) ? Jre.JDK_PREFERENCE_JDK_ONLY : null);

            if (getAttribute(ATTR_SINGLE_INSTANCE)) {
                final SingleInstance si = new SingleInstance();
                si.setWindowTitle(getAttribute(ATTR_APP_NAME));
                si.setMutexName(getAppId());
                c.setSingleInstance(si);
            }

            if (false) {
                final VersionInfo versionInfo = new VersionInfo();
                versionInfo.setProductName(getAttribute(ATTR_APP_NAME));
                versionInfo.setCompanyName(getAttribute(ATTR_IMPLEMENTATION_VENDOR));
                versionInfo.setFileVersion(verstionToWindowsVersion(getAttribute(ATTR_APP_VERSION)));
                versionInfo.setProductVersion(verstionToWindowsVersion(getAttribute(ATTR_APP_VERSION)));
                versionInfo.setTxtFileVersion(getAttribute(ATTR_APP_VERSION));
                versionInfo.setTxtProductVersion(getAttribute(ATTR_APP_VERSION));
                c.setVersionInfo(versionInfo);
            }

            if (hasAttribute(ATTR_ICON)) {
                Path icon0 = verifyAppCache().resolve(getAttribute(ATTR_ICON) + ".ico");
                if (Files.exists(icon0)) {
                    icon = Files.createTempFile("", ".ico");
                    Files.copy(icon0, icon);
                    c.setIcon(icon.toFile());
                }
            }

            final Builder builder = new Builder(Log.getConsoleLog());
            builder.build();
            return out;
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if (tmpJar != null)
                Files.delete(tmpJar);
            if (icon != null)
                Files.delete(icon);
        }
    }

    private static String verstionToWindowsVersion(String version) {
        for (int count = version.split("\\.").length; count < 4; count++)
            version += ".0";
        return version;
    }

    private Path setLaunch4JTmpDir() {
        try {
            final Path tmpdir = addTempFile(Files.createTempDirectory("capsule-launch4j-tmp-"));
            System.setProperty("launch4j.tmpdir", tmpdir.toString());
            return tmpdir;
        } catch (IOException e) {
            throw new RuntimeException("Could not extract libraries necessary for building a Windows executable", e);
        }
    }

    private Path setLaunch4JLibDir() {
        try {
            final Path libdir = findOwnJarFile(NativeCapsule.class).toAbsolutePath().getParent().resolve("w32api");
            if(Files.exists(libdir))
                delete(libdir);
            addTempFile(Files.createDirectory(libdir));

            for (String filename : new String[]{
                "crt2.o", "libadvapi32.a", "libgcc.a", "libkernel32.a", "libmingw32.a",
                "libmingwex.a", "libmoldname.a", "libmsvcrt.a", "libshell32.a", "libuser32.a"})
                copy(filename, "w32api", libdir);

            return libdir;
        } catch (IOException e) {
            throw new RuntimeException("Could not extract libraries necessary for building a Windows executable", e);
        }
    }

    private void setLaunch4JBinDir() {
        if (isMac())
            copyBin("mac");
        else if (!isWindows())
            copyBin("linux");
    }

    private Path copyBin(String os) {
        try {
            final Path bindir = addTempFile(Files.createTempDirectory("capsule-launch4j-bin-"));
            for (String filename : new String[]{"ld", "windres"})
                ensureExecutable(copy(filename, "bin/" + os, bindir));
            System.setProperty("launch4j.bindir", bindir.toString());
            return bindir;
        } catch (IOException e) {
            throw new RuntimeException("Could not extract binaries necessary for building a Windows executable", e);
        }
    }

    private static Path copy(String filename, String resourceDir, Path targetDir) throws IOException {
        try (InputStream in = NativeCapsule.class.getClassLoader().getResourceAsStream(resourceDir + '/' + filename);
             OutputStream out = Files.newOutputStream(targetDir.resolve(filename))) {
            copy(in, out);
            return targetDir.resolve(filename);
        }
    }

    private Path buildLinuxApp(Path out) throws IOException {
        final Jar jar = createJar(out);
        makeUnixExecutable(jar);
        if (isGUIApp())
            makeGUICapsule(jar);
        jar.close();
        ensureExecutable(out);
        return out;
    }

    private Path buildMacApp(Path out) throws IOException {
        out = withSuffix(out, ".app");
        delete(out);
        Files.createDirectory(out);

        final Path contents = out.resolve("Contents");
        Files.createDirectory(contents);
        try (PrintWriter info = new PrintWriter(Files.newBufferedWriter(contents.resolve("Info.plist"), Charset.forName("UTF-8")))) {
            writeInfo(info);
        }

        final Path resources = contents.resolve("Resources");
        Files.createDirectory(resources);
        if (hasAttribute(ATTR_ICON)) {
            final Path icon = verifyAppCache().resolve(getAttribute(ATTR_ICON) + ".icns");
            if (Files.exists(icon))
                Files.copy(icon, resources.resolve(icon.getFileName()));
        }

        final Path macos = contents.resolve("MacOS");
        Files.createDirectory(macos);
        final Path outJarPath = macos.resolve(getSimpleCapsuleName());
        final Jar jar = createJar(outJarPath);
        makeUnixExecutable(jar);
        if (isGUIApp())
            makeGUICapsule(jar);
        jar.close();
        ensureExecutable(outJarPath);
        return out;
    }

    private void writeInfo(PrintWriter out) {
        out.println("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        out.println("<!DOCTYPE plist PUBLIC \"-//Apple Computer//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">");
        out.println("<plist version=\"1.0\">");
        out.println("<dict>");
        out.println("  <key>CFBundleGetInfoString</key>");
        out.println("  <string>" + getSimpleCapsuleName() + "</string>");
        out.println("  <key>CFBundleExecutable</key>");
        out.println("  <string>" + getSimpleCapsuleName() + "</string>");
        out.println("  <key>CFBundleIdentifier</key>");
        out.println("  <string>" + getAttribute(ATTR_APP_NAME) + "</string>");
        out.println("  <key>CFBundleName</key>");
        out.println("  <string>" + getAttribute(ATTR_APP_NAME) + "</string>");
        if (hasAttribute(ATTR_ICON)) {
            out.println("  <key>CFBundleIconFile</key>");
            out.println("  <string>" + getAttribute(ATTR_ICON) + "</string>");
        }
        if (hasAttribute(ATTR_APP_VERSION)) {
            out.println("  <key>CFBundleShortVersionString</key>");
            out.println("  <string>" + getAttribute(ATTR_APP_VERSION) + "</string>");
        }
        out.println("  <key>CFBundleShortVersionString</key>");
        out.println("  <string>1.0</string>");
        out.println("  <key>CFBundleInfoDictionaryVersion</key>");
        out.println("  <string>6.0</string>");
        out.println("  <key>CFBundlePackageType</key>");
        out.println("  <string>APPL</string>");
        out.println("  <key>CFBundleSignature</key>");
        out.println("  <string>????</string>");
        out.println("</dict>");
        out.println("</plist>");
    }

    private static Jar makeUnixExecutable(Jar jar) {
        return jar.setJarPrefix("#!/bin/sh\n\nexec java -jar $0 \"$@\"\n");
    }

    private Jar makeGUICapsule(Jar jar) throws IOException {
        List<String> caplets = getAttribute(ATTR_CAPLETS);
        caplets = caplets == null ? new ArrayList<String>() : new ArrayList<String>(caplets);

        log(LOG_VERBOSE, "Adding caplet " + GUI_CAPSULE_NAME);
        caplets.add(NativeCapsule.class.getName());
        caplets.add(GUI_CAPSULE_NAME);
        if (hasCaplet(MAVEN_CAPSULE_NAME)) {
            log(LOG_VERBOSE, "Adding caplet " + GUI_MAVEN_CAPSULE_NAME);
            caplets.add(GUI_MAVEN_CAPSULE_NAME);
        }

        jar.setListAttribute("Caplets", caplets);

        jar.addClass(NativeCapsule.class);
        jar.addClass(GUICapsule.class);
        if (hasCaplet(MAVEN_CAPSULE_NAME)) {
            jar.addEntry("GUIMavenCapsule.class", NativeCapsule.class.getResourceAsStream("GUIMavenCapsule.class"));
            jar.addPackageOf(GUIListener.class, Jar.matches("capsule/((GUIDependencyManager)|(GUIListener)).*"));
        }
        return jar;
    }

    private static Path withSuffix(Path path, String suffix) {
        return path.getFileName().toString().endsWith(suffix) ? path : path.toAbsolutePath().getParent().resolve(path.getFileName().toString() + suffix);
    }

    private static Path findOwnJarFile(Class clazz) {
        final URL url = clazz.getClassLoader().getResource(clazz.getName().replace('.', '/') + ".class");
        if (!"jar".equals(url.getProtocol()))
            throw new AssertionError("Not in JAR");
        final String path = url.getPath();
        if (path == null || !path.startsWith("file:"))
            throw new IllegalStateException("Not in a local JAR file; loaded from: " + url);

        try {
            final URI jarUri = new URI(path.substring(0, path.indexOf('!')));
            return Paths.get(jarUri);
        } catch (URISyntaxException e) {
            throw new AssertionError(e);
        }
    }

    protected static void copy(InputStream is, OutputStream out) throws IOException {
        final byte[] buffer = new byte[1024];
        for (int bytesRead; (bytesRead = is.read(buffer)) != -1;)
            out.write(buffer, 0, bytesRead);
        out.flush();
    }

    private static Path ensureExecutable(Path file) {
        if (!Files.isExecutable(file)) {
            try {
                Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
                if (!perms.contains(PosixFilePermission.OWNER_EXECUTE)) {
                    Set<PosixFilePermission> newPerms = EnumSet.copyOf(perms);
                    newPerms.add(PosixFilePermission.OWNER_EXECUTE);
                    Files.setPosixFilePermissions(file, newPerms);
                }

            } catch (UnsupportedOperationException e) {
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return file;
    }
}