/*
 * The MIT License
 *
 * Copyright (c) 2009-2011, Romain Seguy
 * Copyright (c) 2004-2009, Sun Microsystems, Inc., Kohsuke Kawaguchi, Tom Huybrechts
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package hudson.plugins.radbuilder;

import hudson.CopyOnWrite;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Computer;
import hudson.model.Descriptor.FormException;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.tools.ToolInstallation;
import hudson.util.ArgumentListBuilder;
import hudson.util.VariableResolver;
import java.io.IOException;
import net.sf.json.JSONObject;
import org.jvnet.localizer.ResourceBundleHolder;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

/**
 * IBM Rational Application Developer builder, strongly based on the {@link Ant}
 * one.
 *
 * <p>Note that, when this builder runs on Windows, there's actually a competition
 * between Hudson's WORKSPACE environment variable and RAD's one, which is named
 * exactly the same (with lower case letters, but Windows doesn't care). This
 * confusion is dealt with at the beginning and at the end of the execution of
 * the builder</p>
 *
 * @author Romain Seguy
 * @version 1.1.2
 */
public class RAD extends Builder {

    /**
     * Name of the environment variable containing the RAD workspace to be used.
     */
    public final static String RAD_WORKSPACE_ENV_VAR = "workspace";
    /** Name of the .metadata folder of each RAD workspace. */
    public final static String RAD_WORKSAPCE_METADATA_FOLDER = ".metadata";
    /**
     * When set to true, a new {@code PROJECT_WORKSPACE} environment variable
     * will be created containing to replace the standard {@code WORKSPACE} one
     * which is overwritten because of RAD.
     */
    private final boolean activateProjectWorkspaceVar;
    /** Optional build script path relative to the workspace. */
    private final String buildFile;
    /**
     * Indicates that the content of the RAD workspace (including .metadata) has
     * to be removed.
     */
    private final boolean deleteRadWorkspaceContent;
    /**
     * Indicates that the .metadata folder of the RAD workspace has to be
     * removed.
     */
    private final boolean deleteRadWorkspaceMetadata;
    /** Optional properties. */
    private final String properties;
    /** Identifies {@link RADInstallation} to be used. */
    private final String radInstallationName;
    /** Optional RAD workspace relative to the Hudson project's workspace */
    private final String radWorkspace;
    /**
     * Optional lTargets, properties, and other Ant options, separated by
     * whitespaces or newlines.
     */
    private final String targets;

    @DataBoundConstructor
    public RAD(boolean activateProjectWorkspaceVar, String buildFile, boolean deleteRadWorkspaceContent, boolean deleteRadWorkspaceMetadata, String properties, String radInstallationName, String radWorkspace, String targets) {
        this.activateProjectWorkspaceVar = activateProjectWorkspaceVar;
        this.buildFile = Util.fixEmptyAndTrim(buildFile);
        this.deleteRadWorkspaceContent = deleteRadWorkspaceContent;
        this.deleteRadWorkspaceMetadata = deleteRadWorkspaceMetadata;
        this.properties = Util.fixEmptyAndTrim(properties);
        this.radInstallationName = radInstallationName;
        this.radWorkspace = Util.fixEmptyAndTrim(radWorkspace);
        this.targets = targets;
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    /**
     * Returns the {@link RADInstallation} to use when the build takes place
     * ({@code null} if none has been set).
     */
    public RADInstallation getRadInstallation(EnvVars env, Node node, BuildListener listener) {
        try {
            for(RADInstallation installation: getDescriptor().getInstallations()) {
                if(getRadInstallationName() != null && installation.getName().equals(getRadInstallationName())) {
                    return installation.forEnvironment(env).forNode(node, TaskListener.NULL);
                }
            }
        } catch (Exception e) { // IOException, InterruptedException
            listener.fatalError(ResourceBundleHolder.get(RAD.class).format("FailedToTranslateInstallationHome"));
        }
        
        return null;
    }

    public boolean getActivateProjectWorkspaceVar() {
        return activateProjectWorkspaceVar;
    }

    public String getBuildFile() {
        return buildFile;
    }

    public boolean getDeleteRadWorkspaceContent() {
        return deleteRadWorkspaceContent;
    }

    public boolean getDeleteRadWorkspaceMetadata() {
        return deleteRadWorkspaceMetadata;
    }

    public String getProperties() {
        return properties;
    }

    public String getRadInstallationName() {
        return radInstallationName;
    }

    public String getRadWorkspace() {
        return radWorkspace;
    }

    public String getTargets() {
        return targets;
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
        AbstractProject project = build.getProject();
        ArgumentListBuilder args = new ArgumentListBuilder();
        EnvVars env = build.getEnvironment(listener);
        VariableResolver<String> varResolver = build.getBuildVariableResolver();

        // --- RAD installation ---

        // has a RAD installation been set? if yes, is it really a RAD installation?
        RADInstallation radInstallation = getRadInstallation(env, build.getBuiltOn(), listener);
        if(radInstallation == null) {
            listener.fatalError(ResourceBundleHolder.get(RAD.class).format("NoInstallationSet"));
            return false;
        }
        else {
            radInstallation = radInstallation.forNode(Computer.currentComputer().getNode(), listener);
            radInstallation = radInstallation.forEnvironment(env);

            String runAntExecutable = radInstallation.getRunAntExecutable(launcher);
            if(runAntExecutable == null) {
                listener.fatalError(ResourceBundleHolder.get(RAD.class).format("NoRunAntExecutable", radInstallation.getName()));
                return false;
            }

            args.add(runAntExecutable);
        }

        // --- RAD workspace ---

        FilePath radWorkspaceFilePath;
        if(getRadWorkspace() != null) {
            // we're 100% sure that project.getWorkspace() is not null since we
            // handle this case some lines ago
            radWorkspaceFilePath = project.getWorkspace().child(getRadWorkspace());

            // is the workspace specified by the user the same as the project's
            // workspace?
            if(radWorkspaceFilePath.getName().equals(project.getWorkspace().getName())) {
                listener.fatalError("The RAD workspace is the same as the project's workspace: It must not.");
                return false;
            }
        }
        else {
            radWorkspaceFilePath = project.getWorkspace().child(project.getName() + "-rad-workspace");
        }
        if(!radWorkspaceFilePath.exists()) {
            // the workspace folder has to be created, other RAD will fail
            radWorkspaceFilePath.mkdirs();
        }

        // we need to backup and remove Hudson's WORKSPACE variable to restore
        // it at the end of the run
        String hudsonWorkspaceEnvVar = env.get("WORKSPACE");
        env.remove("WORKSPACE");

        // do the user want to have a variable equivalent to WORKSPACE? if yes,
        // PROJECT_WORKSPACE is created
        if(getActivateProjectWorkspaceVar()) {
            env.put("PROJECT_WORKSPACE", hudsonWorkspaceEnvVar.replaceAll("\\\\", "/")); // '\' will be changed to '/'
        }

        if(!launcher.isUnix()) {
            // on Windows, we need the WORKSPACE var to be an absolute path,
            // otherwise we get an "incorrect workspace=..." error
            env.put(RAD_WORKSPACE_ENV_VAR, radWorkspaceFilePath.toURI().getPath().substring(1));
        }
        else {
            env.put(RAD_WORKSPACE_ENV_VAR, radWorkspaceFilePath.getName());
        }

        if(getDeleteRadWorkspaceContent()) {
            // we remove the whole content of the workspace, including .metadata
            radWorkspaceFilePath.deleteContents();
        }
        else if(getDeleteRadWorkspaceMetadata()) {
            // we only remove the .metadata folder
            FilePath metadataFilePath = radWorkspaceFilePath.child(".metadata");
            if(metadataFilePath.exists()) {
                metadataFilePath.deleteRecursive();
            }
        }

        // --- build file ---

        String lBuildFile;
        if(getBuildFile() == null) {
            lBuildFile = "build.xml";
        }
        else {
            lBuildFile = Util.replaceMacro(env.expand(getBuildFile()), varResolver);
        }

        if(project.getWorkspace() == null) {
            listener.fatalError("Unable to find project's workspace");
            return false;
        }
        FilePath buildFilePath = project.getWorkspace().child(lBuildFile);
        if(!buildFilePath.exists()) {
            listener.fatalError("Unable to find build script at " + buildFilePath);
            return false;
        }

        args.add("-buildfile", buildFilePath.getName());

        // --- properties ---

        args.addKeyValuePairsFromPropertyString("-D", env.expand(getProperties()), varResolver);

        // --- targets ---

        String lTargets = Util.replaceMacro(env.expand(getTargets()), varResolver);
        args.addTokenized(lTargets.replaceAll("[\t\r\n]+", " "));

        if(!launcher.isUnix()) {
            args = args.toWindowsCommand();
        }

        long startTime = System.currentTimeMillis();
        try {
            int r = launcher.launch().cmds(args).envs(env).stdout(listener).pwd(buildFilePath.getParent()).join();

            return r == 0;
        }
        catch(IOException ioe) {
            Util.displayIOException(ioe, listener);

            String errorMessage = ResourceBundleHolder.get(RAD.class).format("ExecutionFailed");
            if(radInstallation == null && (System.currentTimeMillis()-startTime) < 1000) {
                if(getDescriptor().getInstallations() == null) {
                    // no RAD installation has been set at all
                    errorMessage += ResourceBundleHolder.get(RAD.class).format("NoInstallationAtAll");
                }
                else {
                    errorMessage += ResourceBundleHolder.get(RAD.class).format("NoInstallationSet");
                }
            }

            listener.fatalError(errorMessage);
            return false;
        }
        finally {
            // we need to restore Hudson's WORKSPACE environment variable
            env.remove(RAD_WORKSPACE_ENV_VAR);
            env.put("WORKSPACE", hudsonWorkspaceEnvVar);
            env.remove("PROJECT_WORKSPACE");
        }
    }

    @Extension
    public static class DescriptorImpl extends BuildStepDescriptor<Builder> {

        @CopyOnWrite
        private volatile RADInstallation[] installations = new RADInstallation[0];

        public DescriptorImpl() {
            load();
        }

        protected DescriptorImpl(Class<? extends RAD> clazz) {
            super(clazz);
        }

        @Override
        public String getDisplayName() {
            return ResourceBundleHolder.get(RAD.class).format("DisplayName");
        }

        public RADInstallation[] getInstallations() {
            return installations;
        }

        /**
         * Returns the {@link RADInstallation.DescriptorImpl} instance.
         */
        public RADInstallation.DescriptorImpl getToolDescriptor() {
            return ToolInstallation.all().get(RADInstallation.DescriptorImpl.class);
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            if(getInstallations() != null && getInstallations().length > 0) {
                return true;
            }
            return false;
        }

        @Override
        public Builder newInstance(StaplerRequest req, JSONObject formData) throws FormException {
            return req.bindJSON(RAD.class, formData);
        }

        public void setInstallations(RADInstallation... installations) {
            this.installations = installations;
            save();
        }

    }

}
