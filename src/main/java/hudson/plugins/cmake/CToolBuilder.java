package hudson.plugins.cmake;

import java.io.IOException;

import javax.annotation.Nonnull;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.Computer;
import hudson.model.ModelObject;
import hudson.model.Node;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.util.ArgumentListBuilder;
import hudson.util.ListBoxModel;
import jenkins.tasks.SimpleBuildStep;

/**
 * Provides a build step that allows to invoke selected tools of the cmake-suite
 * ({@code cmake}, {@code cpack} and {@code ctest}) with arbitrary arguments.
 * 
 * @author Martin Weber
 */
public class CToolBuilder extends AbstractCmakeBuilder
        implements SimpleBuildStep {
    /** the ID of the tool in the CMake-suite to invoke {@link Tool}. */
    private String toolId;

    /**
     * Minimal constructor.
     *
     * @param installationName
     *            the name of the cmake tool installation from the global config
     *            page.
     */
    @DataBoundConstructor
    public CToolBuilder(String installationName) {
        super(installationName);
        setToolId("cmake");
    }

    @DataBoundSetter
    public void setToolId(String toolId) {
        this.toolId = Util.fixNull(toolId);
    }

    public String getToolId() {
        return toolId;
    }

    @DataBoundSetter
    public void setWorkingDir(String buildDir) {
        // because of: error: @DataBoundConstructor may not be used on an
        // abstract class
        super.setWorkingDir(buildDir);
    }

    public String getWorkingDir() {
        // because of: error: @DataBoundConstructor may not be used on an
        // abstract class
        return super.getWorkingDir();
    }

    @DataBoundSetter
    public void setArguments(String arguments) {
        // because of: error: @DataBoundConstructor may not be used on an
        // abstract class
        super.setArguments(arguments);
    }

    public String getArguments() {
        // because of: error: @DataBoundConstructor may not be used on an
        // abstract class
        return super.getArguments();
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

        // strip off the last path segment (usually 'cmake')
        String bindir;
        {
            int idx;
            if (launcher.isUnix()) {
                idx = cmakeBin.lastIndexOf('/');
            } else {
                if ((idx = cmakeBin.lastIndexOf('\\')) != -1
                        || (idx = cmakeBin.lastIndexOf('/')) != -1)
                    ;
            }
            if (idx >= 0) {
                bindir = cmakeBin.substring(0, idx + 1);
            } else {
                bindir = "";
            }
        }

        try {
            /* Determine remote working directory path. Create it. */
            final String workDir = getWorkingDir();
            final FilePath theWorkDir = makeRemotePath(workSpace,
                    envs.expand(workDir));
            if (workDir != null) {
                theWorkDir.mkdirs();
            }

            /* Invoke tool in working dir */
            ArgumentListBuilder cmakeCall = buildToolCall(bindir + getToolId(),
                    envs.expand(getArguments()));
            if (0 != launcher.launch().pwd(theWorkDir).envs(envs)
                    .stdout(listener).cmds(cmakeCall).join()) {
                run.setResult(Result.FAILURE);
                return; // invocation failed
            }
        } catch (IOException e) {
            Util.displayIOException(e, listener);
            listener.error(e.getLocalizedMessage());
            run.setResult(Result.FAILURE);
            return;
        }
    }

    /**
     * Constructs the command line to invoke the tool.
     *
     * @param toolBin
     *            the name of the build tool binary, either as an absolute or
     *            relative file system path.
     * @param toolArgs
     *            additional arguments, separated by spaces to pass to cmake or
     *            {@code null}
     * @return the argument list, never {@code null}
     */
    private static ArgumentListBuilder buildToolCall(final String toolBin,
            String toolArgs) {
        ArgumentListBuilder args = new ArgumentListBuilder();

        args.add(toolBin);
        if (toolArgs != null) {
            args.addTokenized(toolArgs);
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

        private static Tool[] tools = { new Tool("cmake", "CMake"),
                new Tool("cpack", "CPack"), new Tool("ctest", "CTest") };

        public DescriptorImpl() {
            super(CToolBuilder.class);
            load();
        }

        /**
         * This human readable name is used in the configuration screen.
         */
        public String getDisplayName() {
            return "CMake/CPack/CTest execution";
        }

        public ListBoxModel doFillToolIdItems() {
            ListBoxModel items = new ListBoxModel();
            for (Tool tool : tools) {
                items.add(tool.getDisplayName(), tool.getId());
            }
            return items;
        }

    } // DescriptorImpl

    /**
     * Represents one of the tools of the CMake-suite.
     * 
     * @author Martin Weber
     */
    private static class Tool implements ModelObject {
        private final String id;
        private final String displayName;

        /**
         * @param id
         * @param displayName
         */
        public Tool(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        public String getId() {
            return id;
        }

        @Override
        public String getDisplayName() {
            return displayName;
        }
    }
}
