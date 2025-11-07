package com.fizzed.blaze.project;

import com.fizzed.blaze.Task;
import com.fizzed.blaze.TaskGroup;
import com.fizzed.buildx.Target;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.fizzed.blaze.Systems.exec;

@TaskGroup(value="main", name="Main", order=10)
@TaskGroup(value="project", name="Project", order=100)
@TaskGroup(value="maintainers", name="Maintainers Only", order=500)
public class PublicBlaze extends BaseBlaze {

    // public methods

    @Task(group="main", order=10, value="Sets project up (e.g. checks or downloads dependencies, prepare environment, etc.)")
    public void setup() throws Exception {
        this.projectSetup();
    }

    @Task(group="main", order=20, value="Cleans project up (e.g. by removing build dirs, project cache dirs, etc.)")
    public void nuke() throws Exception {
        this.projectNuke();
    }

    @Task(group="project", order=100, value="Runs tests across various JDK versions that this project supports.")
    public void cross_jdk_tests() throws Exception {
        final List<Target> crossJdkTestTargets = this.crossJdkTestTargets();

        this.mvnCrossJdkTests(crossJdkTestTargets);
    }

    @Task(group="maintainers", value="Runs tests across various hosts (os/arch combos) that this project supports.")
    public void cross_host_tests() throws Exception {
        final List<Target> crossHostTestTargets = this.crossHostTestTargets();

        this.mvnCrossHostTests(crossHostTestTargets);
    }

    @Task(group="maintainers", value="Runs tests across both cross_jdk_tests and cross_host_tests in one run with combined results.")
    public void cross_tests() throws Exception {
        // both jdk and host tests
        final List<Target> crossTestTargets = new ArrayList<>();
        crossTestTargets.addAll(this.crossJdkTestTargets());
        crossTestTargets.addAll(this.crossHostTestTargets());

        this.mvnCrossHostTests(crossTestTargets);
    }

    @Task(group="maintainers", value="Releases artifacts to maven central, using the minimum Java version this project supports for the release.")
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