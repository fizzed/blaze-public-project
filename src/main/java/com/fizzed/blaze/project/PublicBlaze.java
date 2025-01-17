package com.fizzed.blaze.project;

import com.fizzed.blaze.Config;
import com.fizzed.blaze.Contexts;
import com.fizzed.jne.*;
import org.slf4j.Logger;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.fizzed.blaze.Contexts.fail;
import static com.fizzed.blaze.Systems.exec;
import static java.util.stream.Collectors.joining;

public class PublicBlaze {
    protected final Logger log = Contexts.logger();
    protected final Config config = Contexts.config();
    protected final Path projectDir = Contexts.withBaseDir("..").toAbsolutePath().normalize();

    protected String repoLatestTag() {
        // get latest tag from git
        return exec("git", "describe", "--abbrev=0", "--tags")
            .runCaptureOutput()
            .toString()
            .trim();
    }

    protected boolean repoIsUpToDate() {
        final int exitValue = (int)exec("git", "diff-files", "--quiet")
            .exitValues(0,1)
            .run();

        return exitValue == 0;
    }

    protected void failIfUncommittedChanges() {
        if (!this.repoIsUpToDate()) {
            fail("Uncommitted changes in git. Commit them first then re-run this task");
        }
    }

    protected String detectLatestVersion() {
        final String v = this.repoLatestTag();
        String version = v;

        // find first digit, then substring to it
        for (int i = 0; i < v.length(); i++) {
            final char c = v.charAt(i);
            if (Character.isDigit(c)) {
                version = v.substring(i);
                break;
            }
        }

        return version;
    }

    protected void updateFileWithLatestVersion(Path file) throws IOException {
        this.updateFileWithLatestVersion(file, "<version>(.*)</version>");
    }

    protected void updateFileWithLatestVersion(Path file, String versionLocator) throws IOException {
        final Path newFile = file.resolveSibling(file.getFileName() + ".new");

        final String latestVersion = this.detectLatestVersion();

        log.info("Detected latest version (from repo): {}", latestVersion);

        // find current version in readme using a regex to match
        final Pattern versionLocatorPattern = Pattern.compile(versionLocator);
        final AtomicReference<String> fileVersionRef = new AtomicReference<>();
        try (Stream<String> lines = Files.lines(file)) {
            lines.forEach(line -> {
                if (fileVersionRef.get() == null) {
                    Matcher matcher = versionLocatorPattern.matcher(line);
                    if (matcher.find()) {
                        fileVersionRef.set(matcher.group(1));
                    }
                }
            });
        }

        final String fileVersion = fileVersionRef.get();

        if (fileVersion == null) {
            log.error("No version found in " + file + " from regex " + versionLocatorPattern);
            return;
        }

        log.info("Detected version (in {}): {}", file, fileVersion);

        // replace version in file and write a new version
        final Pattern versionPattern = Pattern.compile(fileVersion);
        try (BufferedWriter writer = Files.newBufferedWriter(newFile)) {
            try (Stream<String> lines = Files.lines(file)) {
                lines.forEach(line -> {
                    Matcher matcher = versionPattern.matcher(line);
                    String newLine = matcher.replaceAll(latestVersion);
                    try {
                        writer.append(newLine);
                        writer.append("\n");
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            }
            writer.flush();
        }

        Files.move(newFile, file, StandardCopyOption.REPLACE_EXISTING);
    }



    // these are default actions every public project should have

    protected int[] supportedJavaVersions() {
        return new int[] {21, 17, 11, 8};
    }

    protected int minimumSupportedJavaVersion() {
        int minVersion = -1;
        int[] javaVersions = this.supportedJavaVersions();
        for (int version : javaVersions) {
            if (minVersion == -1 || version < minVersion) {
                minVersion = version;
            }
        }
        return minVersion;
    }

    protected void mvnTestOnJdks(int... jdkVersions) throws Exception {
        final String jdkVersionStr = Arrays.stream(jdkVersions).mapToObj(Integer::toString).collect(joining(", "));
        final long start = System.currentTimeMillis();
        final List<JavaHome> javaHomes = new ArrayList<>();
        for (final int jdkVersion : jdkVersions) {
            final JavaHome jdkHome = new JavaHomeFinder()
                .jdk()
                .version(jdkVersion)
                .preferredDistributions()
                .sorted()
                .tryFind()
                .orElse(null);

            if (jdkHome != null) {
                javaHomes.add(jdkHome);
            }
        }

        log.info("");
        log.info("Detected JDKs for {} (in {} ms)", jdkVersionStr, (System.currentTimeMillis()-start));
        for (JavaHome javaHome : javaHomes) {
            log.info("  {}", javaHome);
        }
        log.info("");

        if (javaHomes.isEmpty()) {
            fail("No JDKs found matching versions " + jdkVersionStr);
        }

        for (JavaHome javaHome : javaHomes) {
            // this is enough to ensure maven uses the provided JDK
            try {
                exec("mvn", "test")
                    .workingDir(this.projectDir)
                    .env("JAVA_HOME", javaHome.getDirectory().toString())
                    .verbose()
                    .run();
            } catch (Exception e) {
                log.error("Using JDK {} -- test failed", javaHome);
                throw e;
            }
        }

        log.info("");
        log.info("Successfully passed tests on JDKs for {} using:", jdkVersionStr);
        for (JavaHome javaHome : javaHomes) {
            log.info("  {}", javaHome);
        }
        log.info("");
    }

    protected void mvnCommandsWithJdk(int jdkVersion, String... arguments) throws Exception {
        final long start = System.currentTimeMillis();
        final JavaHome jdkHome = new JavaHomeFinder()
            .jdk()
            .version(jdkVersion)
            .preferredDistributions()
            .sorted()
            .find();

        log.info("");
        log.info("Detected JDK for {} (in {} ms)", jdkVersion, (System.currentTimeMillis()-start));
        log.info("  {}", jdkHome);
        log.info("");

        exec("mvn", arguments)
            .workingDir(this.projectDir)
            .env("JAVA_HOME", jdkHome.getDirectory().toString())
            .verbose()
            .run();
    }

    public void test_on_all_jdks() throws Exception {
        this.mvnTestOnJdks(this.supportedJavaVersions());
    }

    public void release() throws Exception {
        // get the supported java versions, find the lowest version, then release with that
        int minJavaVersion = this.minimumSupportedJavaVersion();

        this.mvnCommandsWithJdk(minJavaVersion, "release:prepare", "release:perform");
    }

    public void after_release() throws IOException {
        this.failIfUncommittedChanges();

        final Path readmeFile = this.projectDir.resolve("README.md");

        this.updateFileWithLatestVersion(readmeFile);

        // commit changes, push to origin
        exec("git", "commit", "-am", "Update README with latest version").run();
        exec("git", "push", "origin").run();
    }

}