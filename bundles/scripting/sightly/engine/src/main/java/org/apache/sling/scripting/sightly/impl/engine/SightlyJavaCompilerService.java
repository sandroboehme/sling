/*******************************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 ******************************************************************************/
package org.apache.sling.scripting.sightly.impl.engine;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.IOUtils;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.commons.classloader.ClassLoaderWriter;
import org.apache.sling.commons.compiler.CompilationResult;
import org.apache.sling.commons.compiler.CompilationUnit;
import org.apache.sling.commons.compiler.CompilerMessage;
import org.apache.sling.commons.compiler.JavaCompiler;
import org.apache.sling.commons.compiler.Options;
import org.apache.sling.scripting.api.resource.ScriptingResourceResolverProvider;
import org.apache.sling.scripting.sightly.SightlyException;
import org.apache.sling.scripting.sightly.impl.engine.compiled.SourceIdentifier;
import org.apache.sling.scripting.sightly.impl.utils.ScriptUtils;
import org.apache.sling.scripting.sightly.render.RenderContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * The {@code SightlyJavaCompiler} allows for simple instantiation of arbitrary classes that are either stored in the repository
 * or in regular OSGi bundles. It also compiles Java sources on-the-fly and can discover class' source files based on
 * {@link Resource}s (typically Sling components). It supports Sling Resource type inheritance.
 */
@Component(
        service = SightlyJavaCompilerService.class
)
public class SightlyJavaCompilerService {

    private static final Logger LOG = LoggerFactory.getLogger(SightlyJavaCompilerService.class);

    public static final Pattern PACKAGE_DECL_PATTERN = Pattern.compile("(\\s*)package\\s+([a-zA-Z_$][a-zA-Z\\d_$]*\\.?)+;");

    @Reference
    private ClassLoaderWriter classLoaderWriter = null;

    @Reference
    private JavaCompiler javaCompiler = null;

    @Reference
    private ResourceBackedPojoChangeMonitor resourceBackedPojoChangeMonitor = null;

    @Reference
    private SightlyEngineConfiguration sightlyEngineConfiguration = null;

    @Reference
    private ScriptingResourceResolverProvider scriptingResourceResolverProvider = null;

    private Options options;

    /**
     * This method returns an Object instance based on a class that is either found through regular classloading mechanisms or on-the-fly
     * compilation. In case the requested class does not denote a fully qualified classname, this service will try to find the class through
     * Sling's servlet resolution mechanism and compile the class on-the-fly if required.
     *
     * @param renderContext the render context
     * @param className     name of class to use for object instantiation
     * @return object instance of the requested class
     */
    public Object getInstance(RenderContext renderContext, String className) {
        LOG.debug("Attempting to load class {}.", className);
        if (className.contains(".")) {
            if (resourceBackedPojoChangeMonitor.getLastModifiedDateForJavaUseObject(className) > 0) {
                // it looks like the POJO comes from the repo and it was changed since it was last loaded
                LOG.debug("Class {} identifies a POJO from the repository that was changed since the last time it was instantiated.",
                        className);
                Object result = compileRepositoryJavaClass(scriptingResourceResolverProvider.getRequestScopedResourceResolver(), className);
                resourceBackedPojoChangeMonitor.clearJavaUseObject(className);
                return result;
            }
            try {
                // the object either comes from a bundle or from the repo but it was not registered by the ResourceBackedPojoChangeMonitor
                LOG.debug("Attempting to load class {} from the classloader cache.", className);
                return classLoaderWriter.getClassLoader().loadClass(className).newInstance();
            } catch (Exception e) {
                // the object definitely doesn't come from a bundle so we should attempt to compile it from the repo
                LOG.debug("Class {} identifies a POJO from the repository and it needs to be compiled.", className);
                return compileRepositoryJavaClass(scriptingResourceResolverProvider.getRequestScopedResourceResolver(), className);
            }
        } else {
            Resource pojoResource = ScriptUtils.resolveScript(
                    scriptingResourceResolverProvider.getRequestScopedResourceResolver(),
                    renderContext,
                    className + ".java"
            );
            if (pojoResource != null) {
                SourceIdentifier sourceIdentifier = new SourceIdentifier(sightlyEngineConfiguration, pojoResource.getPath());
                String fqcn = sourceIdentifier.getFullyQualifiedClassName();
                LOG.debug("Class {} has FQCN {}.", className, fqcn);
                return getInstance(renderContext, fqcn);
            }
        }
        throw new SightlyException("Cannot find class " + className + ".");
    }

    /**
     * Compiles a class using the passed fully qualified class name and its source code.
     *
     * @param sourceIdentifier the source identifier
     * @param sourceCode       the source code from which to generate the class
     * @return object instance of the class to compile
     */
    public Object compileSource(SourceIdentifier sourceIdentifier, String sourceCode) {
        try {
            return internalCompileSource(sourceIdentifier, sourceCode);
        } catch (Exception e) {
            throw new SightlyException(e);
        }
    }

    private Object internalCompileSource(SourceIdentifier sourceIdentifier, String sourceCode) throws Exception {
        String fqcn = sourceIdentifier.getFullyQualifiedClassName();
        if (sightlyEngineConfiguration.keepGenerated()) {
            String path = "/" + fqcn.replaceAll("\\.", "/") + ".java";
            OutputStream os = classLoaderWriter.getOutputStream(path);
            IOUtils.write(sourceCode, os, "UTF-8");
            IOUtils.closeQuietly(os);
        }
        String[] sourceCodeLines = sourceCode.split("\\r\\n|[\\n\\x0B\\x0C\\r\\u0085\\u2028\\u2029]");
        boolean foundPackageDeclaration = false;
        for (String line : sourceCodeLines) {
            Matcher matcher = PACKAGE_DECL_PATTERN.matcher(line);
            if (matcher.matches()) {
                /**
                 * This matching might return false positives like:
                 * // package a.b.c;
                 *
                 * where from a syntactic point of view the source code doesn't have a package declaration and the expectancy is that our
                 * SightlyJavaCompilerService will add one.
                 */
                foundPackageDeclaration = true;
                break;
            }
        }

        if (!foundPackageDeclaration) {
            sourceCode = "package " + sourceIdentifier.getPackageName() + ";\n" + sourceCode;
        }

        CompilationUnit compilationUnit = new SightlyCompilationUnit(sourceCode, fqcn);
        long start = System.currentTimeMillis();
        CompilationResult compilationResult = javaCompiler.compile(new CompilationUnit[]{compilationUnit}, options);
        long end = System.currentTimeMillis();
        List<CompilerMessage> errors = compilationResult.getErrors();
        if (errors != null && errors.size() > 0) {
            throw new SightlyException(createErrorMsg(errors));
        }
        if (compilationResult.didCompile()) {
            LOG.debug("Class {} was compiled in {}ms.", fqcn, end - start);
        }
        /**
         * the class loader might have become dirty, so let the {@link ClassLoaderWriter} decide which class loader to return
         */
        return classLoaderWriter.getClassLoader().loadClass(fqcn).newInstance();
    }

    private Object compileRepositoryJavaClass(ResourceResolver resolver, String className) {
        Resource pojoResource = SourceIdentifier.getPOJOFromFQCN(resolver, sightlyEngineConfiguration.getBundleSymbolicName(), className);
        if (pojoResource != null) {
            try {
                SourceIdentifier sourceIdentifier = new SourceIdentifier(sightlyEngineConfiguration, pojoResource.getPath());
                return compileSource(sourceIdentifier, IOUtils.toString(pojoResource.adaptTo(InputStream.class), "UTF-8"));
            } catch (IOException e) {
                throw new SightlyException(String.format("Unable to compile class %s from %s.", className, pojoResource.getPath()), e);
            }
        }
        throw new SightlyException("Cannot find a file corresponding to class " + className + " in the repository.");
    }

    @Activate
    protected void activate() {
        LOG.info("Activating {}", getClass().getName());

        String version = System.getProperty("java.specification.version");
        options = new Options();
        options.put(Options.KEY_GENERATE_DEBUG_INFO, true);
        options.put(Options.KEY_SOURCE_VERSION, version);
        options.put(Options.KEY_TARGET_VERSION, version);
        options.put(Options.KEY_CLASS_LOADER_WRITER, classLoaderWriter);
        options.put(Options.KEY_FORCE_COMPILATION, true);
    }

    //---------------------------------- private -----------------------------------
    private String createErrorMsg(List<CompilerMessage> errors) {
        final StringBuilder buffer = new StringBuilder();
        buffer.append("Compilation errors in ");
        buffer.append(errors.get(0).getFile());
        buffer.append(":");
        StringBuilder errorsBuffer = new StringBuilder();
        boolean duplicateVariable = false;
        for (final CompilerMessage e : errors) {
            if (!duplicateVariable) {
                if (e.getMessage().contains("Duplicate local variable")) {
                    duplicateVariable = true;
                    buffer.append(" Maybe you defined more than one identical block elements without defining a different variable for "
                            + "each one?");
                }
            }
            errorsBuffer.append("\nLine ");
            errorsBuffer.append(e.getLine());
            errorsBuffer.append(", column ");
            errorsBuffer.append(e.getColumn());
            errorsBuffer.append(" : ");
            errorsBuffer.append(e.getMessage());
        }
        buffer.append(errorsBuffer);
        return buffer.toString();
    }

    private static class SightlyCompilationUnit implements CompilationUnit {

        private String fqcn;
        private String sourceCode;

        SightlyCompilationUnit(String sourceCode, String fqcn) throws Exception {
            this.sourceCode = sourceCode;
            this.fqcn = fqcn;
        }

        @Override
        public Reader getSource() throws IOException {
            return new InputStreamReader(IOUtils.toInputStream(sourceCode, "UTF-8"), "UTF-8");
        }

        @Override
        public String getMainClassName() {
            return fqcn;
        }

        @Override
        public long getLastModified() {
            return System.currentTimeMillis();
        }
    }
}
