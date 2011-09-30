/**
 *
 */
package org.arachna.netweaver.javadoc;

import hudson.model.Hudson;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.HashSet;

import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.context.Context;
import org.apache.velocity.exception.MethodInvocationException;
import org.apache.velocity.exception.ParseErrorException;
import org.apache.velocity.exception.ResourceNotFoundException;
import org.arachna.ant.AntHelper;
import org.arachna.netweaver.dc.types.DevelopmentComponent;
import org.arachna.netweaver.dc.types.DevelopmentComponentFactory;
import org.arachna.netweaver.dc.types.DevelopmentConfiguration;
import org.arachna.netweaver.dc.types.PublicPartReference;
import org.arachna.netweaver.dctool.JdkHomeAlias;

/**
 * Wrapper for the Ant JavaDoc task. Sets up the task and executes it.
 * 
 * @author Dirk Weigenand
 */
final class BuildFileGenerator {
    /**
     * Helper class for setting up an ant task with class path, source file sets
     * etc.
     */
    private final AntHelper antHelper;

    /**
     * The HTTP-Proxy to use iff available.
     */
    private final InetSocketAddress proxy;

    /**
     * links to add to existing javadoc documentation
     */
    private final Collection<String> links;

    /**
     * Registry for development components.
     */
    private final DevelopmentComponentFactory dcFactory;

    private DevelopmentConfiguration developmentConfiguration;

    private VelocityEngine engine;

    private Collection<String> buildFilePaths = new HashSet<String>();

    /**
     * @return the buildFilePaths
     */
    final Collection<String> getBuildFilePaths() {
        return buildFilePaths;
    }

    /**
     * Create an executor for executing the Javadoc ant task using the given ant
     * helper object and links to related javadoc documentation.
     * 
     * @param developmentConfiguration
     * 
     * @param antHelper
     *            helper for populating an ant task with source filesets and
     *            class path for a given development component
     * @param links
     *            links to add to existing javadoc documentation
     */
    BuildFileGenerator(final DevelopmentConfiguration developmentConfiguration, final AntHelper antHelper,
        final DevelopmentComponentFactory dcFactory, Collection<String> links, VelocityEngine engine) {
        this.developmentConfiguration = developmentConfiguration;
        this.antHelper = antHelper;
        this.dcFactory = dcFactory;
        this.links = links;
        this.engine = engine;
        proxy = (InetSocketAddress)Hudson.getInstance().proxy.createProxy().address();
    }

    /**
     * Create an executor for executing the Javadoc ant task using the given ant
     * helper object and links to related javadoc documentation.
     * 
     * @param developmentConfiguration
     * 
     * @param antHelper
     *            helper for populating an ant task with source filesets and
     *            class path for a given development component
     * @param links
     *            links to add to existing javadoc documentation
     * @param proxy
     *            the wwwproxy to use for referencing external javadocs.
     */
    BuildFileGenerator(final DevelopmentConfiguration developmentConfiguration, final AntHelper antHelper,
        final DevelopmentComponentFactory dcFactory, Collection<String> links, InetSocketAddress proxy, VelocityEngine engine) {
        this.developmentConfiguration = developmentConfiguration;
        this.antHelper = antHelper;
        this.dcFactory = dcFactory;
        this.links = links;
        this.proxy = proxy;
        this.engine = engine;
    }

    /**
     * Run the Ant Javadoc task for the given development component.
     * 
     * @param component
     *            development component to document with JavaDoc.
     * @throws IOException
     * @throws ResourceNotFoundException
     * @throws MethodInvocationException
     * @throws ParseErrorException
     */
    public void execute(final DevelopmentComponent component) {
        final HashSet<String> excludes = new HashSet<String>();
        Collection<String> sources = antHelper.createSourceFileSets(component, excludes, excludes);

        if (!sources.isEmpty()) {
            Context context = new VelocityContext();
            context.put("sourcePaths", sources);
            context.put("classpaths", antHelper.createClassPath(component));
            context.put("javaDocDir", this.getJavaDocFolder(component));
            context.put("source", getSourceVersion());
            context.put("header", getHeader(component));
            context.put("links", this.getLinks(component));
            context.put("proxy", getProxyConfigurationParams());

            String baseLocation = antHelper.getBaseLocation(component);
            String location = String.format("%s/javadoc-build.xml", baseLocation);
            Writer buildFile = null;

            try {
                buildFile = new FileWriter(location);
                engine.evaluate(context, buildFile, "javadoc-build", getTemplateReader());
                this.buildFilePaths.add(location);
            }
            catch (Exception e) {
                throw new RuntimeException(e);
            }
            finally {
                if (buildFile != null) {
                    try {
                        buildFile.close();
                    }
                    catch (IOException e) {
                        // TODO Auto-generated catch block
                        // e.printStackTrace(logger);
                    }
                }
            }
        }
    }

    /**
     * @return
     */
    private Reader getTemplateReader() {
        return new InputStreamReader(this.getClass().getResourceAsStream(
            "/org/arachna/netweaver/javadoc/javadoc-build.vm"));
    }

    /**
     * @param component
     * @return
     */
    private String getHeader(final DevelopmentComponent component) {
        return String.format("&lt;div class='compartment'&gt;Compartment %s&lt;br/&gt;Development Component %s:%s&lt;/div&gt;", component
            .getCompartment().getName(), component.getVendor(), component.getName());
    }

    /**
     * Returns the source version to use for generating javadoc documentation.
     * 
     * Uses the {@link JdkHomeAlias} defined in the development configuration.
     * If there is no alias defined use the JDK version the ant task is run
     * with.
     * 
     * @return java source version to use generating javadoc documentation.
     */
    private String getSourceVersion() {
        JdkHomeAlias alias = this.developmentConfiguration.getJdkHomeAlias();
        String sourceVersion;
        if (alias != null) {
            sourceVersion = alias.getSourceVersion();
        }
        else {
            String[] versionParts = System.getProperty("java.version").replace('_', '.').split("\\.");
            sourceVersion = String.format("%s.%s", versionParts[0], versionParts[1]);
        }

        return sourceVersion;
    }

    /**
     * Set links to external javadocs. Dependencies will be determined from the
     * given DC and the links configured in the respective project.
     * 
     * @param task
     *            JavaDoc task to configure
     * @param component
     *            DC to use to determine which other projects (DCs) should be
     *            referenced.
     */
    private Collection<String> getLinks(final DevelopmentComponent component) {
        Collection<String> links = new HashSet<String>();
        links.addAll(this.links);

        for (PublicPartReference referencedDC : component.getUsedDevelopmentComponents()) {
            DevelopmentComponent usedDC = this.dcFactory.get(referencedDC.getVendor(), referencedDC.getName());

            if (usedDC != null && usedDC.getCompartment().isSourceState()) {
                links.add(getJavaDocFolder(usedDC));
            }
        }

        return links;
    }

    /**
     * Create proxy configuration parameters for the javadoc tool.
     * 
     * @return a string suited for javadocs additional params to configure http
     *         proxy access
     */
    private String getProxyConfigurationParams() {
        StringBuilder proxyConfig = new StringBuilder();

        if (proxy != null) {
            if (proxy.getPort() > 0) {
                proxyConfig.append(String.format("-J-Dhttp.proxyHost=%s -J-Dhttp.proxyPort=%d", proxy.getHostName(),
                    proxy.getPort()));
            }
            else {
                proxyConfig.append(String.format("-J-Dhttp.proxyHost=%s", proxy.getHostName()));
            }
        }

        return proxyConfig.toString();
    }

    /**
     * Calculate the folder to output javadoc to for the given development
     * component.
     * 
     * @param component
     *            development component to calculate the javadoc output folder
     *            for
     * @return folder to output javadoc to
     */
    private String getJavaDocFolder(DevelopmentComponent component) {
        return String.format("%s/javadoc/%s~%s", this.antHelper.getPathToWorkspace(), component.getVendor(),
            component.getName().replace('/', '~')).replace('/', File.separatorChar);
    }
}