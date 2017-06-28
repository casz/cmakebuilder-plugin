/*******************************************************************************
 * Copyright (c) 2015 Martin Weber.
 *
 * Contributors:
 *      Martin Weber - Initial implementation
 *******************************************************************************/
package hudson.plugins.cmake;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.tools.ant.DirectoryScanner;
import org.apache.tools.ant.types.FileSet;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundConstructor;

import hudson.AbortException;
import hudson.Extension;
import hudson.FilePath;
import hudson.FilePath.FileCallable;
import hudson.Util;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.model.DownloadService.Downloadable;
import hudson.remoting.VirtualChannel;
import hudson.tools.DownloadFromUrlInstaller;
import hudson.tools.ToolInstallation;
import hudson.tools.ToolInstaller;
import jenkins.MasterToSlaveFileCallable;
import jenkins.security.MasterToSlaveCallable;
import net.sf.json.JSONObject;

/**
 * Automatic Cmake installer from cmake.org.
 */
public class CmakeInstaller extends DownloadFromUrlInstaller {
//    private static Logger logger = Logger
//            .getLogger(CmakeInstaller.class.getName());

    @DataBoundConstructor
    public CmakeInstaller(String id) {
        super(id);
    }

    @Override
    public FilePath performInstallation(ToolInstallation tool, Node node,
            TaskListener log) throws IOException, InterruptedException {
        // Gather properties for the node to install on
        final VirtualChannel channel = node.getChannel();
        if (channel == null) {
            throw new AbortException(
                    "Node '" + node.getDisplayName() + "' went offline");
        }
        final String[] nodeProperties = channel
                .call(new GetSystemProperties("os.name", "os.arch"));

        final Installable inst = getInstallable(
                OsFamily.valueOfOsName(nodeProperties[0]), nodeProperties[1]);
        if (inst == null) {
            String msg = String.format(
                    "%s [%s]: No cmake download known for OS `%s` and arch `%s`.",
                    getDescriptor().getDisplayName(), tool.getName(),
                    nodeProperties[0], nodeProperties[1]);
            throw new AbortException(msg);
        }

        final FilePath toolPath = getFixedPreferredLocation(tool, node);
        // FilePath base0 = findPullUpDirectory(toolPath);
        if (!isUpToDate(toolPath, inst)) {
            String msg = String.format("%s [%s]: Unpacking %s to %s on %s...",
                    getDescriptor().getDisplayName(), tool.getName(), inst.url,
                    toolPath, node.getDisplayName());
            if (toolPath.installIfNecessaryFrom(new URL(inst.url), log, msg)) {
                // we don't use the timestamp..
                toolPath.child(".timestamp").delete();
                // pull up extra subdir...
                msg = String.format(
                        "%s [%s]: Inspecting unpacked files at %s...",
                        getDescriptor().getDisplayName(), tool.getName(),
                        toolPath);
                log.getLogger().println(msg);
                FilePath base = findPullUpDirectory(toolPath);
                if (base != null && !base.equals(toolPath)) {
                    // remove anything that might get in the way..
                    for (FilePath f : toolPath.list()) {
                        if (!base.getRemote().startsWith(f.getRemote()))
                            f.deleteRecursive();
                    }
                    base.moveAllChildrenTo(toolPath);
                }
                // remove unnecessary files (docs, man pages)..
                try {
                    toolPath.child("doc").deleteRecursive();
                } catch (IOException ignore) {
                }
                try {
                    toolPath.child("man").deleteRecursive();
                } catch (IOException ignore) {
                }
                // leave a record for the next up-to-date check
                toolPath.child(".installedFrom").write(inst.url, "UTF-8");
            }
        }

        return toolPath.child("bin/cmake");
    }

    /**
     * Overloaded to select the OS-ARCH specific variant and to fill in the
     * variant´s URL.
     *
     * @param nodeOsFamily
     *            the value of the JVM system property "os.name" of the node,
     *            represented as an enum
     * @param nodeOsArch
     *            the value of the JVM system property "os.arch" of the node
     * @return null if no such matching variant is found.
     */
    public Installable getInstallable(OsFamily nodeOsFamily, String nodeOsArch)
            throws IOException {
        List<CmakeInstallable> installables = ((DescriptorImpl) getDescriptor())
                .getInstallables();

        for (CmakeInstallable inst : installables)
            if (id.equals(inst.id)) {
                // Filter variants to install by system-properties
                // for the node to install on
                for (CmakeVariant variant : inst.variants) {
                    if (variant.appliesTo(nodeOsFamily, nodeOsArch)) {
                        // fill in URL for download machinery
                        inst.url = variant.url;
                        return inst;
                    }
                }
            }
        return null;
    }

    /**
     * Fixes the value returned by {@link ToolInstaller#preferredLocation} to
     * use the <strong>installer ID</strong> instead of the ToolInstallation
     * {@link ToolInstallation#getName name}. This fix avoids unnecessary
     * downloads when users change the name of the tool on the global config
     * page.
     *
     * @param tool
     *            the tool being installed
     * @param node
     *            the computer on which to install the tool
     *
     * @return a fixed file path (a path within the local Jenkins work area), if
     *         {@code tool#getHome()} is {@code null}, else the unchanged
     *         {@code ToolInstaller#preferredLocation()}
     */
    private FilePath getFixedPreferredLocation(ToolInstallation tool,
            Node node) {
        final FilePath toolPath = preferredLocation(tool, node);
        if (tool.getHome() == null) {
            // jenkins wants to download, having preferredLocation jam in
            // the NAME instead of the ID
            return toolPath.sibling(sanitize(id));
        }
        return toolPath;
    }

    private static String sanitize(String s) {
        return s != null ? s.replaceAll("[^A-Za-z0-9_.-]+", "_") : null;
    }

    /**
     * Overwritten since 3.x archives from cmake.org have more than just the
     * "cmake-<version>" directory
     */
    @Override
    protected FilePath findPullUpDirectory(final FilePath root)
            throws IOException, InterruptedException {
        // 3.x archives from cmake.org have more than just the "cmake-<version>"
        // directory at the top level
        final String glTopDir = "cmake-" + id + "-*";
        FileCallable<FilePath> callable = new RootDirScanner(glTopDir);
        return root.act(callable);
    }

    // //////////////////////////////////////////////////////////////////
    // inner classes
    // //////////////////////////////////////////////////////////////////

    /**
     * Scans the downloaded and expanded archive for the location of the cmake
     * binary.
     *
     * @author Martin Weber
     */
    private static class RootDirScanner
            extends MasterToSlaveFileCallable<FilePath> {
        private static final long serialVersionUID = 1L;
        /** Ant includes that matches the files and dirs */
        private final String includes;

        /**
         * @param glTopDir
         *            Ant includes that matches the requested top directory
         *            inside the unpacked archive
         */
        private RootDirScanner(String glTopDir) {
            // glob to match the cmake binary
            final String glBinCmake = glTopDir + "/**/bin/cmake";
            // glob to match the cmake binary on windows
            final String glBinCmakeW = glTopDir + "/**/bin/cmake.exe";
            // glob to match the shared files of cmake
            final String glShare = glTopDir + "/**/share";
            this.includes = glBinCmake + ',' + glBinCmakeW + ',' + glShare;
        }

        /**
         * @param baseDir
         *            the base dir to start scan from
         * @return the top level directory of the files needed to run cmake,
         *         ignoring any extra files in the archive
         */
        @Override
        public FilePath invoke(File baseDir, VirtualChannel channel)
                throws IOException, InterruptedException {
            FileSet fs = Util.createFileSet(baseDir, includes, null);
            if (baseDir.exists()) {
                DirectoryScanner ds = fs.getDirectoryScanner(
                        new org.apache.tools.ant.Project());
                // we expect only one file here: '**/bin/cmake'
                final String[] cmakeBinFiles = ds.getIncludedFiles();
                // we expect only one directory here: '**/share'
                final String[] shareDirs = ds.getIncludedDirectories();
                if (cmakeBinFiles.length > 1) {
                    String msg = String.format(
                            "Unkown layout of downloaded CMake archive: "
                                    + "Multiple candidates for cmake executable: %s",
                            Arrays.toString(cmakeBinFiles));
                    throw new AbortException(msg);
                } else if (cmakeBinFiles.length == 0) {
                    String msg = "Unkown layout of downloaded CMake archive: "
                            + "No candidate for cmake executable";
                    throw new AbortException(msg);
                }
                // determine top directory
                String topDir = new File(cmakeBinFiles[0]).getParent();
                if (topDir != null) {
                    topDir = new File(topDir).getParent();
                    if (topDir != null) {
                        // check if there is a 'share' directory
                        final String share = new File(topDir, "share")
                                .getPath();
                        for (String d : shareDirs) {
                            if (d.equals(share)) {
                                // fine: share dir found
                                File file = new File(baseDir, topDir);
                                return new FilePath(file);
                            }
                        }
                    }
                }
            } else {
                String msg = "Unkown layout of downloaded CMake archive: "
                        + "No `bin` and/or `share` subdirectory found";
                throw new AbortException(msg);
            }
            return null;
        }
    }

    @Extension
    public static final class DescriptorImpl
            extends DownloadFromUrlInstaller.DescriptorImpl<CmakeInstaller> {
        @Override
        public String getDisplayName() {
            return "Install from cmake.org";
        }

        /**
         * List of installable tools.
         *
         * <p>
         * The UI uses this information to populate the drop-down.
         *
         * @return never null.
         */
        @Override
        public List<CmakeInstallable> getInstallables() throws IOException {
            JSONObject d = Downloadable.get(getId()).getData();
            if (d == null)
                return Collections.emptyList();
            Map<String, Class<?>> classMap = new HashMap<String, Class<?>>();
            classMap.put("variants", CmakeVariant.class);
            return Arrays.asList(((CmakeInstallableList) JSONObject.toBean(d,
                    CmakeInstallableList.class, classMap)).list);
        }

        @Override
        public boolean isApplicable(
                Class<? extends ToolInstallation> toolType) {
            return toolType == CmakeTool.class;
        }
    } // DescriptorImpl

    /**
     * A Callable that gets the values of the given Java system properties from
     * the (remote) node.
     */
    private static class GetSystemProperties
            extends MasterToSlaveCallable<String[], InterruptedException> {
        private static final long serialVersionUID = 1L;

        private final String[] properties;

        GetSystemProperties(String... properties) {
            this.properties = properties;
        }

        public String[] call() {
            String[] values = new String[properties.length];
            for (int i = 0; i < properties.length; i++) {
                values[i] = System.getProperty(properties[i]);
            }
            return values;
        }
    } // GetSystemProperties

    private static enum OsFamily {
        Linux, Windows("win32"), OSX("Darwin"), SunOS, FreeBSD, IRIX(
                "IRIX64"), AIX, HPUX("HP-UX");
        private final String cmakeOrgName;

        /**
         * Gets the OS name as specified in the files on the cmake.org download
         * site.
         *
         * @return the current cmakeOrgName property.
         */
        public String getCmakeOrgName() {
            return cmakeOrgName != null ? cmakeOrgName : name();
        }

        private OsFamily() {
            this(null);
        }

        private OsFamily(String cmakeOrgName) {
            this.cmakeOrgName = cmakeOrgName;
        }

        /**
         * Gets the OS family from the value of the system property "os.name".
         *
         * @param osName
         *            the value of the system property "os.name"
         * @return the OsFalimly object or {@code null} if osName is unknown
         */
        public static OsFamily valueOfOsName(String osName) {
            if (osName != null) {
                if ("Linux".equals(osName)) {
                    return Linux;
                } else if (osName.startsWith("Windows")) {
                    return Windows;
                } else if (osName.contains("OS X")) {
                    return OSX;
                } else if ("SunOS".equals(osName)) {
                    return SunOS;
                } else if ("AIX".equals(osName)) {
                    return AIX;
                } else if ("HPUX".equals(osName)) {
                    return HPUX;
                } else if ("Irix".equals(osName)) {
                    return IRIX;
                } else if ("FreeBSD".equals(osName)) {
                    return FreeBSD; // not verified
                }
            }
            return null;
        }
    } // OsFamily

    // //////////////////////////////////////////////////////////////////
    // JSON de-serialization
    // //////////////////////////////////////////////////////////////////
    /**
     * Represents the de-serialized JSON data file containing all installable
     * Cmake versions. See file hudson.plugins.cmake.CmakeInstaller
     */
    @Extension
    @Restricted(NoExternalUse.class)
    public static final class CmakeInstallableList {
        // initialize with an empty array just in case JSON doesn't have the
        // list field (which shouldn't happen.)
        // Public for JSON de-serialization
        public CmakeInstallable[] list = new CmakeInstallable[0];
    } // CmakeInstallableList

    // Needs to be public for JSON de-serialization
    @Restricted(NoExternalUse.class)
    public static class CmakeVariant {
        public String url;
        // these come frome the JSON file and finally from cmake´s download site
        // URLs
        /** OS name as specified by the cmake.org download site */
        public String os = "";
        /** OS architecture as specified by the cmake.org download site */
        public String arch = "";

        /**
         * Checks whether an installation of this CmakeVariant will work on the
         * given node. This checks the given JVM system properties of a node.
         *
         * @param osFamily
         *            the OS family derived from the JVM system property
         *            "os.name" of the node
         * @param nodeOsArch
         *            the value of the JVM system property "os.arch" of the node
         */
        public boolean appliesTo(OsFamily osFamily, String nodeOsArch) {
            if (osFamily != null && osFamily.getCmakeOrgName().equals(os)) {
                switch (osFamily) {
                case Linux:
                    if (nodeOsArch.equals("i386") && nodeOsArch.equals(arch)) {
                        return true;
                    }
                    if (nodeOsArch.equals("amd64")
                            && (arch.equals("i386") || arch.equals("x86_64"))) {
                        return true; // allow both i386 and x86_64
                    }
                    return false;
                case OSX: // to be verified by the community..
                    // ..cmake.org has both Darwin and Darwin64
                    if (nodeOsArch.equals("i386") && nodeOsArch.equals(arch)) {
                        return true;
                    }
                    if ((nodeOsArch.equals("amd64")
                            || nodeOsArch.equals("x86_64"))
                            && (arch.equals("universal")
                                    || arch.equals("x86_64"))) {
                        return true; // allow both 32 bit and 64 bit
                    }
                    return false;
                case Windows:
                    return true; // only "x86" arch is provided by cmake.org
                case AIX:
                case HPUX:
                    // to be verified by the community
                    return true; // only one arch is provided by cmake.org
                case SunOS:
                    if (nodeOsArch.equals("sparc") && nodeOsArch.equals(arch)) {
                        return true;
                    }
                case IRIX:// to be verified by the community
                    // cmake.org provides arches "n32" & "64"
                    return true;
                default:
                    break;
                }
            }
            return false;
        }
    }

    // Needs to be public for JSON de-serialization
    @Restricted(NoExternalUse.class)
    public static class CmakeInstallable extends Installable {
        public CmakeVariant[] variants = new CmakeVariant[0];

        /**
         * Default ctor for JSON de-serialization.
         */
        public CmakeInstallable() {
        }

    }
}