package com.fizzed.blaze.project;

import com.fizzed.blaze.Task;
import com.fizzed.blaze.TaskGroup;
import com.fizzed.buildx.Target;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static com.fizzed.blaze.Systems.exec;

@TaskGroup(value="maintainers", name="Maintainers Only")
public class PublicBlaze extends BaseBlaze {

    // public methods

    @Task(group="project", value="Runs tests across various JDK versions that this project supports.")
    public void cross_jdk_tests() throws Exception {
        final List<Target> crossJdkTestTargets = this.crossJdkTestTargets();

        this.mvnCrossJdkTests(crossJdkTestTargets);
    }

    @Task(group="maintainers", value="Runs tests across operating systems and hardware architectures.")
    public void cross_tests() throws Exception {
        final List<Target> crossTestTargets = this.crossTestTargets();

        this.mvnCrossTests(crossTestTargets);
    }

    @Task(group="maintainers", value="Releases artifacts to maven central.")
    public void release() throws Exception {
        // get the supported java versions, find the lowest version, then release with that
        int minJavaVersion = this.minimumSupportedJavaVersion();

        this.mvnCommandsWithJdk(minJavaVersion, "release:prepare", "release:perform");
    }

    @Task(group="maintainers", value="Modifies README docs with latest tagged version.")
    public void after_release() throws IOException {
        this.failIfUncommittedChanges();

        final Path readmeFile = this.projectDir.resolve("README.md");

        this.updateFileWithLatestVersion(readmeFile);

        // commit changes, push to origin
        exec("git", "commit", "-am", "Update README with latest version").run();
        exec("git", "push", "origin").run();
    }

}