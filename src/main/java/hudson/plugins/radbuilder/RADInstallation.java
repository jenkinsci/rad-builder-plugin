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

import hudson.EnvVars;
import hudson.Extension;
import hudson.Functions;
import hudson.Launcher;
import hudson.Util;
import hudson.model.EnvironmentSpecific;
import hudson.model.TaskListener;
import hudson.model.Hudson;
import hudson.model.Node;
import hudson.remoting.Callable;
import hudson.slaves.NodeSpecific;
import hudson.tools.ToolDescriptor;
import hudson.tools.ToolInstallation;
import hudson.util.FormValidation;

import java.io.File;
import java.io.IOException;
import java.util.Collections;

import net.sf.json.JSONObject;

import org.apache.commons.lang.StringUtils;
import org.jvnet.localizer.ResourceBundleHolder;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

/**
 * Corresponds to an IBM Rational Application Developer 7.0/7.5 or an IBM Rational
 * Application Developer Build Utility 7.5 installation.
 *
 * <p>To use a {@link RAD} build step, it is mandatory to define a installation:
 * No default installations can be assumed.</p>
 *
 * @author Romain Seguy (http://openromain.blogspot.com)
 */
public class RADInstallation extends ToolInstallation implements NodeSpecific<RADInstallation>, EnvironmentSpecific<RADInstallation> {

    @DataBoundConstructor
    public RADInstallation(String name, String home) {
        super(name, removeTrailingBackslash(home), Collections.EMPTY_LIST);
    }

    public RADInstallation forEnvironment(EnvVars environment) {
        return new RADInstallation(getName(), environment.expand(getHome()));
    }

    public RADInstallation forNode(Node node, TaskListener log) throws IOException, InterruptedException {
        return new RADInstallation(getName(), translateFor(node, log));
    }

    public String getRunAntExecutable(Launcher launcher) throws IOException, InterruptedException {
        return launcher.getChannel().call(new Callable<String,IOException>() {
            public String call() throws IOException {
                // 1st try: do we work with a RAD installation?
                File runAntFile = getRunAntFile("bin");
                if(runAntFile.exists()) {
                    return runAntFile.getPath();
                }
                else {
                    // 2nd try: do we work with a BU installation?
                    runAntFile = getRunAntFile("eclipse/bin");
                    if(runAntFile.exists()) {
                        return runAntFile.getPath();
                    }
                }
                return null;
            }
        });
    }

    /**
     * Returns a {@link File} representing {@code runAnt.bat}/{@code runAnt.sh}.
     */
    private File getRunAntFile(String binFolder) {
        String runAntFileName = "runAnt.sh";

        if(Functions.isWindows()) {
            runAntFileName = "runAnt.bat";
        }

        return new File(Util.replaceMacro(getHome(), EnvVars.masterEnvVars), binFolder + "/" + runAntFileName);
    }

    /**
     * Removes the '\' or '/' character that may be present at the end of the
     * specified string.
     */
    private static String removeTrailingBackslash(String s) {
        return StringUtils.removeEnd(StringUtils.removeEnd(s, "/"), "\\");
    }

    @Extension
    public static class DescriptorImpl extends ToolDescriptor<RADInstallation> {

        @Override
        public String getDisplayName() {
            return ResourceBundleHolder.get(RAD.class).format("DisplayName");
        }

        @Override
        public RADInstallation[] getInstallations() {
            return Hudson.getInstance().getDescriptorByType(RAD.DescriptorImpl.class).getInstallations();
        }

        @Override
        public void setInstallations(RADInstallation... installations) {
            Hudson.getInstance().getDescriptorByType(RAD.DescriptorImpl.class).setInstallations(installations);
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject json) throws FormException {
            setInstallations(
                    req.bindJSONToList(
                            RADInstallation.class,
                            json.get("rad")).toArray(new RADInstallation[0]));
            return true;
        }

        /**
         * Checks if the installation folder is valid.
         */
        public FormValidation doCheckHome(@QueryParameter File value) {
            if(!Hudson.getInstance().hasPermission(Hudson.ADMINISTER))
                return FormValidation.ok();

            if(value.getPath().equals("")) {
                return FormValidation.error(ResourceBundleHolder.get(RADInstallation.class).format("InstallationFolderMustBeSet"));
            }

            if(!value.isDirectory()) {
                return FormValidation.error(ResourceBundleHolder.get(RADInstallation.class).format("NotAFolder", value));
            }

            // let's check for the runAnt file existence
            if(Functions.isWindows()) {
                boolean noRADRunAntBat = false;  // RAD on Windows
                boolean noBURunAntBat = false;   // RAD BU on Windows

                File runAntFile = new File(value, "bin/runAnt.bat");
                if(!runAntFile.exists()) {
                    noRADRunAntBat = true;

                    runAntFile = new File(value, "eclipse/bin/runAnt.bat");
                    if(!runAntFile.exists()) {
                        noBURunAntBat = true;
                    }
                }

                if(noBURunAntBat || noBURunAntBat && noRADRunAntBat) {
                    return FormValidation.error(ResourceBundleHolder.get(RADInstallation.class).format("NotARADInstallationFolder", value));
                }
            }
            else {
                boolean noRADRunAntSh = false;   // RAD in Linux
                boolean noBURunAntSh = false;    // RAD BU on Linux

                File runAntFile = new File(value, "bin/runAnt.sh");
                if(!runAntFile.exists()) {
                    noRADRunAntSh = true;

                    runAntFile = new File(value, "eclipse/bin/runAnt.sh");
                    if(!runAntFile.exists()) {
                        noBURunAntSh = true;
                    }
                }

                if(noBURunAntSh || noBURunAntSh && noRADRunAntSh) {
                    return FormValidation.error(ResourceBundleHolder.get(RADInstallation.class).format("NotARADInstallationFolder", value));
                }
            }

            return FormValidation.ok();
        }

    }

}
