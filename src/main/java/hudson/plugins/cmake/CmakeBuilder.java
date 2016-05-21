package hudson.plugins.cmake;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.servlet.ServletException;

import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Computer;
import hudson.model.Environment;
import hudson.model.Job;
import hudson.model.Node;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.util.ArgumentListBuilder;
import hudson.util.FormValidation;
import jenkins.tasks.SimpleBuildStep;

/**
 * Executes <tt>cmake</tt> as a build step.
 *
 * @author Volker Kaiser (initial implementation)
 * @author Martin Weber
 */
public class CmakeBuilder extends AbstractCmakeBuilder
        implements SimpleBuildStep {

    /**
     * the key for the build variable that holds the build tool that the
     * build-scripts have been generated for (e.g. /usr/bin/make or
     * /usr/bin/ninja)
     */
    public static final String ENV_VAR_NAME_CMAKE_BUILD_TOOL = "CMAKE_BUILD_TOOL";

    /**
     * the name of cmake´s buildscript generator or {@code null} if the default
     * generator should be used
     */
    private String generator;
    private String sourceDir;
    private String buildType;
    private String preloadScript;
    private boolean cleanBuild;

    // for backward compatibility with < 2.4.0
    // see
    // https://wiki.jenkins-ci.org/display/JENKINS/Hint+on+retaining+backward+compatibility
    private transient String buildDir;
    private transient String cmakeArgs;

    private List<BuildToolStep> toolSteps = new ArrayList<BuildToolStep>(0);

    /**
     * Minimal constructor.
     *
     * @param installationName
     *            the name of the cmake tool installation from the global config
     *            page.
     */
    @DataBoundConstructor
    public CmakeBuilder(String installationName) {
        super(installationName);
    }

    /**
     * Old constructor.
     *
     * @param installationName
     *            the name of the cmake tool installation from the global config
     *            page.
     * @param generator
     *            the name of cmake´s buildscript generator. May be empty but
     *            not {@code null}
     * @deprecated to minimize number of mandatory field values.
     */
    @Deprecated
    public CmakeBuilder(String installationName, String generator) {
        super(installationName);
        setGenerator(generator);
    }

    // for backward compatibility with < 2.4.
    protected Object readResolve() {
        // convert to new format
        if (buildDir != null) {
            super.setWorkingDir(buildDir);
        }
        if (cmakeArgs != null) {
            super.setArguments(cmakeArgs);
        }
        return this;
    }

    /**
     * Sets the name of the build-script generator.
     * 
     * @param generator
     *            the name of cmake´s build-script generator or {@code null} or
     *            empty if the default generator should be used
     */
    @DataBoundSetter
    public void setGenerator(String generator) {
        generator = Util.fixEmptyAndTrim(generator);
        this.generator = DescriptorImpl.getDefaultGenerator().equals(generator)
                ? null : generator;
    }

    public String getGenerator() {
        return this.generator == null ? DescriptorImpl.getDefaultGenerator()
                : generator;
    }

    @DataBoundSetter
    public void setSourceDir(String sourceDir) {
        this.sourceDir = Util.fixEmptyAndTrim(sourceDir);
    }

    public String getSourceDir() {
        return this.sourceDir;
    }

    @DataBoundSetter
    public void setBuildDir(String buildDir) {
        // because of: error: @DataBoundConstructor may not be used on an
        // abstract class
        super.setWorkingDir(buildDir);
    }

    public String getBuildDir() {
        // because of: error: @DataBoundConstructor may not be used on an
        // abstract class
        return super.getWorkingDir();
    }

    @DataBoundSetter
    public void setBuildType(String buildType) {
        this.buildType = Util.fixEmptyAndTrim(buildType);
    }

    public String getBuildType() {
        return this.buildType;
    }

    @DataBoundSetter
    public void setCleanBuild(boolean cleanBuild) {
        this.cleanBuild = cleanBuild;
    }

    public boolean getCleanBuild() {
        return this.cleanBuild;
    }

    @DataBoundSetter
    public void setPreloadScript(String preloadScript) {
        this.preloadScript = Util.fixEmptyAndTrim(preloadScript);
    }

    public String getPreloadScript() {
        return this.preloadScript;
    }

    @DataBoundSetter
    public void setCmakeArgs(String cmakeArgs) {
        // because of: error: @DataBoundConstructor may not be used on an
        // abstract class
        super.setArguments(cmakeArgs);
    }

    public String getCmakeArgs() {
        // because of: error: @DataBoundConstructor may not be used on an
        // abstract class
        return super.getArguments();
    }

    /**
     * Sets the toolSteps property.
     */
    @DataBoundSetter
    public void setSteps(List<BuildToolStep> toolSteps) {
        this.toolSteps = toolSteps;
    }

    /**
     * Gets the toolSteps property.
     *
     * @return the current toolSteps property.
     */
    public List<BuildToolStep> getSteps() {
        return toolSteps;
    }

    public void perform(@Nonnull Run<?, ?> run, @Nonnull FilePath workSpace,
            @Nonnull Launcher launcher, @Nonnull TaskListener listener)
            throws InterruptedException, IOException {

        CmakeTool installToUse = getSelectedInstallation();
        // Raise an error if the cmake installation isn't found
        if (installToUse == null) {
            listener.fatalError("There is no CMake installation selected."
                    + " Please review the build step configuration.");
            run.setResult(Result.FAILURE);
            return;
        }
        {
            final Computer compi = workSpace.toComputer();
            if (compi == null) {
                listener.fatalError("Workspace %1s does not exist.",
                        workSpace.getRemote());
                run.setResult(Result.FAILURE);
                return;
            }
            final Node node = compi.getNode();
            if (node == null) {
                listener.fatalError("Node for workspace %1s does not exist.",
                        workSpace.getRemote());
                run.setResult(Result.FAILURE);
                return;
            }
            // Get the CMake version for this node, installing it if necessary
            installToUse = (CmakeTool) installToUse.translate(node,
                    EnvVars.getRemote(compi.getChannel()), listener);
        }
        final String cmakeBin = installToUse.getCmakeExe();

        final EnvVars envs = run.getEnvironment(listener);
        if (run instanceof AbstractBuild) {
            // NOT running as a pipeline step, expand variables.
            // (Take any value literally, if in pipeline step.)
            envs.overrideAll(((AbstractBuild<?, ?>) run).getBuildVariables());
        }
        try {
            /*
             * Determine remote build directory path. Clean it, if requested.
             * Create it.
             */
            final String buildDir = getWorkingDir();
            FilePath theBuildDir = makeRemotePath(workSpace,
                    envs.expand(buildDir));
            if (buildDir != null) {
                if (this.cleanBuild && !buildDir.equals(sourceDir)) {
                    // avoid deleting source dir
                    listener.getLogger().println(
                            "Cleaning build dir... " + theBuildDir.getRemote());
                    theBuildDir.deleteRecursive();
                }
                theBuildDir.mkdirs();
            }

            /* Invoke cmake in build dir */
            FilePath theSourceDir = makeRemotePath(workSpace,
                    envs.expand(sourceDir));
            ArgumentListBuilder cmakeCall = buildCMakeCall(cmakeBin,
                    envs.expand(getGenerator()),
                    envs.expand(this.preloadScript), theSourceDir,
                    envs.expand(this.buildType), envs.expand(getCmakeArgs()));
            // invoke cmake
            if (0 != launcher.launch().pwd(theBuildDir).envs(envs)
                    .stdout(listener).cmds(cmakeCall).join()) {
                run.setResult(Result.FAILURE);
                return;
            }

            /* parse CMakeCache.txt to get the actual build tool */
            FilePath cacheFile = theBuildDir.child("CMakeCache.txt");
            String buildTool = cacheFile.act(new BuildToolEntryParser());
            if (buildTool == null) {
                listener.getLogger().printf(
                        "WARNING: Failed to get value for variable `%1s` from %2$s.",
                        CmakeBuilder.ENV_VAR_NAME_CMAKE_BUILD_TOOL,
                        cacheFile.getRemote());
            } else {
                // add CMAKE_BUILD_TOOL variable for toolSteps
                envs.put(CmakeBuilder.ENV_VAR_NAME_CMAKE_BUILD_TOOL, buildTool);
                if (run instanceof AbstractBuild) {
                    // add CMAKE_BUILD_TOOL to env for other build-steps
                    EnvVars exportedEnvVars = new EnvVars();
                    exportedEnvVars.put(
                            CmakeBuilder.ENV_VAR_NAME_CMAKE_BUILD_TOOL,
                            buildTool);
                    // export environment
                    ((AbstractBuild<?, ?>) run).getEnvironments()
                            .add(Environment.create(exportedEnvVars));
                }
            }

            /* invoke each build tool step in build dir */
            for (BuildToolStep step : toolSteps) {
                ArgumentListBuilder toolCall;
                if (!step.getWithCmake()) {
                    // invoke directly
                    // if buildTool == null, let the unexpanded macro show up in
                    // the log
                    final String buildToolMacro = envs.expand("${"
                            + CmakeBuilder.ENV_VAR_NAME_CMAKE_BUILD_TOOL + "}");
                    toolCall = buildBuildToolCall(buildToolMacro,
                            step.getCommandArguments(envs));
                } else {
                    // invoke through 'cmake --build <dir>'
                    toolCall = buildBuildToolCallWithCmake(cmakeBin,
                            theBuildDir, step.getCommandArguments(envs));
                }
                final EnvVars stepEnv = new EnvVars(envs)
                        .overrideAll(step.getEnvironmentVars(envs, listener));
                if (0 != launcher.launch().pwd(theBuildDir).envs(stepEnv)
                        .stdout(listener).cmds(toolCall).join()) {
                    run.setResult(Result.FAILURE);
                    return;
                }
            }
        } catch (IOException e) {
            Util.displayIOException(e, listener);
            listener.error(e.getLocalizedMessage());
            run.setResult(Result.FAILURE);
            return;
        }
    }

    /**
     * Constructs the command line to invoke cmake.
     *
     * @param cmakeBin
     *            the name of the cmake binary, either as an absolute or
     *            relative file system path.
     * @param generator
     *            the name of the build-script generator
     * @param preloadScript
     *            name of the pre-load a script to populate the cache or
     *            {@code null}
     * @param theSourceDir
     *            source directory, must not be {@code null}
     * @param buildType
     *            build type argument for cmake or {@code null} to pass none
     * @param cmakeArgs
     *            additional arguments, separated by spaces to pass to cmake or
     *            {@code null}
     * @return the argument list, never {@code null}
     */
    private static ArgumentListBuilder buildCMakeCall(final String cmakeBin,
            final String generator, final String preloadScript,
            final FilePath theSourceDir, final String buildType,
            final String cmakeArgs) {
        ArgumentListBuilder args = new ArgumentListBuilder();

        args.add(cmakeBin);
        args.add("-G").add(generator);
        if (preloadScript != null) {
            args.add("-C").add(preloadScript);
        }
        if (buildType != null) {
            args.add("-D").add("CMAKE_BUILD_TYPE=" + buildType);
        }
        if (cmakeArgs != null) {
            args.addTokenized(cmakeArgs);
        }
        args.add(theSourceDir.getRemote());
        return args;
    }

    /**
     * Constructs the command line to invoke the actual build tool.
     *
     * @param toolBin
     *            the name of the build tool binary, either as an absolute or
     *            relative file system path.
     * @param toolArgs
     *            addional arguments, separated by spaces to pass to cmake or
     *            {@code null}
     * @return the argument list, never {@code null}
     */
    private static ArgumentListBuilder buildBuildToolCall(final String toolBin,
            String... toolArgs) {
        ArgumentListBuilder args = new ArgumentListBuilder();

        args.add(toolBin);
        if (toolArgs != null) {
            args.add(toolArgs);
        }
        return args;
    }

    /**
     * Constructs the command line to have the actual build tool invoked with
     * cmake.
     *
     * @param cmakeBin
     *            the name of the cmake tool binary, either as an absolute or
     *            relative file system path.
     * @param theBuildDir
     *            the build directory path
     * @param toolArgs
     *            addional build tool arguments, separated by spaces to pass to
     *            cmake or {@code null}
     * @return the argument list, never {@code null}
     */
    private static ArgumentListBuilder buildBuildToolCallWithCmake(
            final String cmakeBin, FilePath theBuildDir, String... toolArgs) {
        ArgumentListBuilder args = new ArgumentListBuilder();

        args.add(cmakeBin);
        args.add("--build");
        args.add(theBuildDir.getRemote());
        if (toolArgs != null) {
            args.add(toolArgs);
        }
        return args;
    }

    /**
     * Overridden for better type safety.
     */
    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    // //////////////////////////////////////////////////////////////////
    // inner classes
    // //////////////////////////////////////////////////////////////////
    /**
     * Descriptor for {@link CmakeBuilder}. Used as a singleton. The class is
     * marked as public so that it can be accessed from views.
     */
    @Extension
    public static final class DescriptorImpl
            extends AbstractCmakeBuilder.DescriptorImpl {

        public DescriptorImpl() {
            super(CmakeBuilder.class);
            load();
        }

        /**
         * Gets the default generator to use if the builder`s generator field is
         * <code>null</code>.
         */
        public static String getDefaultGenerator() {
            return "Unix Makefiles";
        }

        /**
         * This human readable name is used in the configuration screen.
         */
        public String getDisplayName() {
            return "CMake Build";
        }

        /**
         * Performs on-the-fly validation of the form field 'sourceDir'.
         *
         * @param value
         */
        public FormValidation doCheckSourceDir(
                @AncestorInPath Job<?, ?> project,
                @QueryParameter final String value)
                throws IOException, ServletException {
            FilePath ws = project instanceof AbstractProject<?, ?>
                    ? ((AbstractProject<?, ?>) project).getSomeWorkspace()
                    : null;
            if (ws == null)
                return FormValidation.ok();
            return ws.validateRelativePath(value, false, false);
        }

    } // DescriptorImpl
}
