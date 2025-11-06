package com.fizzed.blaze.incubating;

import com.fizzed.blaze.Contexts;

import java.nio.file.Path;

public class MavenProjects {

    /**
     * Initializes a new MavenInitializer instance using the current context.
     * This method provides a way to create a Maven project configuration
     * with a default `pom.xml` file, allowing further customization if required.
     *
     * @return a MavenInitializer instance configured with the current context
     */
    static public MavenInitializer maven() {
        return new MavenInitializer(Contexts.currentContext());
    }

    /**
     * Initializes a new MavenInitializer instance using the provided pom.xml file.
     * This method allows the specification of a custom pom.xml file location
     * for configuring a Maven project.
     *
     * @param pomFile the path to the pom.xml file used to configure the Maven project
     * @return a MavenInitializer instance configured with the specified pom.xml file
     */
    static public MavenInitializer maven(Path pomFile) {
        return new MavenInitializer(Contexts.currentContext())
            .pomFile(pomFile);
    }

    /**
     * Creates a MavenClasspathResolver using the provided Maven instance and the current context.
     * This resolver can be used to derive the classpath information related to the specified Maven project.
     *
     * @param maven the Maven instance representing the project, initialized with a pom.xml file
     * @return a MavenClasspathResolver instance for computing the classpath
     */
    static public MavenClasspathResolver mavenClasspath(Maven maven) {
        return new MavenClasspathResolver(Contexts.currentContext(), maven);
    }

    /**
     * Creates a MavenClasspathResolver instance using the provided Maven instance, scope, and phase.
     * This resolver can be used to compute the classpath information for the specified Maven project,
     * filtered by the given scope and phase.
     *
     * @param maven the Maven instance representing the project, initialized with a pom.xml file
     * @param scope the Maven dependency scope (e.g., "compile", "test") to filter the classpath entries
     * @param onePhase the Maven build phase (e.g., "compile", "test-compile") for which the classpath is resolved
     * @return a MavenClasspathResolver instance for computing the filtered classpath
     */
    static public MavenClasspathResolver mavenClasspath(Maven maven, String scope, String onePhase) {
        return new MavenClasspathResolver(Contexts.currentContext(), maven)
            .scope(scope)
            .phases(onePhase);
    }

    /**
     * Creates a MavenClasspathResolver instance using the provided Maven instance, scope, phase, and module.
     * This resolver can be used to compute the classpath information for the specified Maven project,
     * filtered by the given scope, phase, and module.
     *
     * @param maven the Maven instance representing the project, initialized with a pom.xml file
     * @param scope the Maven dependency scope (e.g., "compile", "test") to filter the classpath entries
     * @param onePhase the Maven build phase (e.g., "compile", "test-compile") for which the classpath is resolved
     * @param module the specific module of the Maven project for which the classpath is resolved for
     * @return a MavenClasspathResolver instance for computing the filtered classpath
     */
    static public MavenClasspathResolver mavenClasspath(Maven maven, String scope, String onePhase, String module) {
        return new MavenClasspathResolver(Contexts.currentContext(), maven)
            .scope(scope)
            .phases(onePhase)
            .module(module);
    }

}