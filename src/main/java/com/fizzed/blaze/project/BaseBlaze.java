package com.fizzed.blaze.project;

import com.fizzed.blaze.Config;
import com.fizzed.blaze.Contexts;
import com.fizzed.blaze.Systems;
import com.fizzed.blaze.Task;
import com.fizzed.blaze.util.Streamables;
import com.fizzed.buildx.Buildx;
import com.fizzed.buildx.Target;
import com.fizzed.jne.JavaHome;
import com.fizzed.jne.JavaHomeFinder;
import com.fizzed.jne.NativeTarget;
import org.slf4j.Logger;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
import static com.fizzed.blaze.Systems.rm;
import static com.fizzed.blaze.util.TerminalHelper.fixedWidthCenter;
import static com.fizzed.blaze.util.TerminalHelper.fixedWidthLeft;
import static java.util.Arrays.asList;
import static java.util.stream.Collectors.joining;

public class BaseBlaze {

    protected final Logger log = Contexts.logger();
    protected final Config config = Contexts.config();
    protected final Path projectDir = this.resolveProjectDir();

    protected Path resolveProjectDir() {
        return Contexts.withBaseDir("..").toAbsolutePath().normalize();
    }

    // cdn or dl publishing

    private Path locateCdndlProjectDir() {
        // usual location is ~/workspace/fizzed/cdndl
        final Path cdndlProjectDir = Contexts.withUserDir("workspace/fizzed/cdndl").toAbsolutePath().normalize();

        if (!Files.exists(cdndlProjectDir) || !Files.isDirectory(cdndlProjectDir)) {
            fail("Unable to locate 'cdndl' project directory at usual location " + cdndlProjectDir + ". Perhaps you haven't clone it yet OR you are not a Fizzed maintainer and cannot do this?");
        }

        return cdndlProjectDir;
    }

    protected void publishToCdn(String cdnPath, Path sourceFileOrdir) throws Exception {
        this.publishToCdnOrDl("cdn", cdnPath, sourceFileOrdir);
    }

    protected void publishToDl(String dlPath, Path sourceFileOrdir) throws Exception {
        this.publishToCdnOrDl("dl", dlPath, sourceFileOrdir);
    }

    private void publishToCdnOrDl(String cdnSite, String cdnPath, Path sourceFileOrdir) throws Exception {
        final Path cdndlProjectDir = this.locateCdndlProjectDir();

        // NOTE: if we need to change how these are done we can either do it here, or solely in the cdndl project as well

        // now we simply need to trigger a cdn deploy
        exec("java", "-jar", "blaze.jar", "publish", "--cdn-site", cdnSite, "--cdn-path", cdnPath, "--source-file", sourceFileOrdir.toAbsolutePath().toString())
            .verbose()
            .workingDir(cdndlProjectDir)            // we must execute this command IN the cdndl directory
            .run();
    }

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

    // these are helpers for containers

    private String containerExe = null;

    protected String resolveContainerExe() {
        if (this.containerExe == null) {
            if (Systems.which("podman").run() != null) {
                this.containerExe = "podman";
            } else if (Systems.which("docker").run() != null) {
                this.containerExe = "docker";
            } else {
                fail("Neither 'podman' or 'docker' exist on your path. Please install either one.");
            }
        }
        return this.containerExe;
    }

    protected boolean containerExists(String name) {
        int statusCode = (int) exec(this.resolveContainerExe(), "container", "inspect", name)
            .exitValues(0, 1, 125)
            .pipeError(Streamables.nullOutput())
            .pipeOutput(Streamables.nullOutput())
            .runResult()
            .get();

        return statusCode == 0;
    }

    protected void containerNuke(String name) {
        log.info("Nuking container {} (if it exists)...", name);

        if (this.containerExists(name)) {
            exec(this.resolveContainerExe(), "rm", "-f", name)
                .exitValues(0, 1)
                .pipeOutput(Streamables.nullOutput())
                .run();
        } else {
            log.info("Container {} does not exist", name);
        }
    }

    protected void waitFor(String description, int limit, long waitTime, WaitForMethod method) {
        long now = System.currentTimeMillis();
        for (int i = 0; i < limit; i++) {
            try {
                log.info("Waiting for {} ({}/{})", description, i, limit);
                if (method.test()) {
                    log.info("Confirmed {} (in {} ms)", description, (System.currentTimeMillis() - now));
                    return;
                }
                Thread.sleep(waitTime);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        fail("Unable to confirm {} (in {} ms)...", description, (System.currentTimeMillis() - now));
    }

    // these are default actions every public project should have

    protected void projectSetup() throws Exception {
        // nothing by default
    }

    protected void projectNuke() throws Exception {
        // if maven project, run "clean"
        if (Files.exists(this.projectDir.resolve("pom.xml"))) {
            exec("mvn", "clean")
                .workingDir(this.projectDir)
                .run();
        }

        log.info("Cleaning up possible buildx dirs (e.g. .buildx, .buildx-cache, .buildx-logs)...");
        rm(this.projectDir.resolve(".buildx")).verbose().recursive().force().run();
        rm(this.projectDir.resolve(".buildx-cache")).verbose().recursive().force().run();
        rm(this.projectDir.resolve(".buildx-logs")).verbose().recursive().force().run();
    }

    protected int[] supportedJavaVersions() {
        return new int[] {25, 21, 17, 11, 8};
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

    protected List<Target> crossJdkTestTargets() {
        // dynamically build the target list
        final int[] javaVersions = this.supportedJavaVersions();
        final String jdkVersionStr = Arrays.stream(javaVersions).mapToObj(Integer::toString).collect(joining(", "));
        final long start = System.currentTimeMillis();
        final List<JavaHome> javaHomes = new ArrayList<>();
        for (final int javaVersion : javaVersions) {
            final JavaHome jdkHome = new JavaHomeFinder()
                .jdk()
                .version(javaVersion)
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

        final List<Target> crossJdkTargets = new ArrayList<>();
        for (JavaHome javaHome : javaHomes) {
            final Target target = new Target("jdk-" + javaHome.getVersion().getMajor())
                .setDescription(javaHome.toString())
                .putData("java_home", javaHome.getDirectory());

            crossJdkTargets.add(target);
        }

        return crossJdkTargets;
    }

    protected void mvnCrossJdkTests(List<Target> crossJdkTestTargets) throws Exception {
        final boolean serial = this.config.flag("serial").orElse(false);

        log.info(fixedWidthCenter("Usage", 100, '!'));
        log.info("");
        log.info("You can modify how this task runs with a few different arguments.");
        log.info("");
        log.info("Run these tests in serial mode (default is parallel) (useful for debugging):");
        log.info("  --serial");
        log.info("");
        log.info("Run these tests on a smaller subset of JDKs, as comma-delimited list:");
        log.info("  --targets jdk-11,jdk-17");
        log.info("");
        log.info(fixedWidthLeft("", 100, '!'));

        // pause slighly so you can see the usage info better
        Thread.sleep(1000);

        new Buildx(crossJdkTestTargets)
            .parallel(!serial)
            .resultsFile(null)      // disable results
            .execute((target, project) -> {
                // leverage the "java_home" data key to pass the java home to the test
                project.exec("mvn", "clean", "test")
                    .workingDir(this.projectDir)
                    .env("JAVA_HOME", target.getData().get("java_home").toString())
                    .run();
            });
    }

    protected List<Target> crossHostTestTargets() {
        final String arch = NativeTarget.detect().getHardwareArchitecture().toString().toLowerCase();
        return asList(
            // containers (which by their nature sometimes cause issues with code, so its good to validate they work)
            // ubuntu22 w/ jdk21 supports x64, arm64, riscv64
            new Target("linux", arch).setDescription("Ubuntu 22.04").setTags("container").setContainerImage("docker.io/fizzed/buildx:"+arch+"-ubuntu22-jdk21"),
            // ubuntu
            new Target("linux", "x64", "Ubuntu 24.04").setTags("latest").setHost("bmh-build-x64-linux-latest"),
            new Target("linux", "x64", "Ubuntu 20.04").setTags("baseline").setHost("bmh-build-x64-linux-baseline"),
            new Target("linux", "arm64", "Ubuntu 24.04").setTags("latest").setHost("bmh-build-arm64-linux-latest"),
            new Target("linux", "arm64", "Ubuntu 20.04").setTags("baseline").setHost("bmh-build-arm64-linux-baseline"),
            new Target("linux", "riscv64", "Ubuntu 24.04").setTags("latest").setHost("bmh-build-riscv64-linux-latest"),

            // alpine/musl
            new Target("linux_musl", "x64", "Alpine 3.22").setTags("latest").setHost("bmh-build-x64-linux-musl-latest"),
            new Target("linux_musl", "x64", "Alpine 3.15").setTags("baseline").setHost("bmh-build-x64-linux-musl-baseline"),
            new Target("linux_musl", "arm64", "Alpine 3.22").setTags("latest").setHost("bmh-build-arm64-linux-musl-latest"),
            new Target("linux_musl", "arm64", "Alpine 3.15").setTags("baseline").setHost("bmh-build-arm64-linux-musl-baseline"),
            new Target("linux_musl", "riscv64", "Alpine 3.22").setTags("latest").setHost("bmh-build-riscv64-linux-musl-latest"),

            // macos
            new Target("macos", "x64", "MacOS 15").setTags("latest").setHost("bmh-build-x64-macos-latest"),
            new Target("macos", "x64", "MacOS 11").setTags("baseline").setHost("bmh-build-x64-macos-baseline"),
            new Target("macos", "arm64", "MacOS 15").setTags("latest").setHost("bmh-build-arm64-macos-latest"),
            new Target("macos", "arm64", "MacOS 12").setTags("baseline").setHost("bmh-build-arm64-macos-baseline"),

            // windows
            new Target("windows", "x64", "Windows 11").setTags("latest").setHost("bmh-build-x64-windows-latest"),
            new Target("windows", "x64", "Windows 10").setTags("baseline").setHost("bmh-build-x64-windows-baseline"),
            new Target("windows", "arm64", "Windows 11").setTags("latest").setHost("bmh-build-arm64-windows-latest"),

            // freebsd
            new Target("freebsd", "x64", "FreeBSD 15").setTags("latest").setHost("bmh-build-x64-freebsd-latest"),
            new Target("freebsd", "x64", "FreeBSD 13").setTags("baseline").setHost("bmh-build-x64-freebsd-baseline"),
            new Target("freebsd", "arm64", "FreeBSD 15").setTags("latest").setHost("bmh-build-arm64-freebsd-latest"),
            new Target("freebsd", "arm64", "FreeBSD 14").setTags("baseline").setHost("bmh-build-arm64-freebsd-baseline"),
            // riscv freebsd not working yet

            // openbsd
            // unfortunately, openbsd sucks with ABI compat and so we really can only build & test on the latest versions
            // plus the latest riscv64 port does not have jdk yet :-(
            new Target("openbsd", "x64", "OpenBSD 7.8").setTags("latest").setHost("bmh-build-x64-openbsd-latest"),
            //new Target("openbsd", "x64", "OpenBSD 7.4").setTags("test", "baseline").setHost("bmh-build-x64-openbsd-baseline"),
            new Target("openbsd", "arm64", "OpenBSD 7.8").setTags("latest").setHost("bmh-build-arm64-openbsd-latest")
            //new Target("openbsd", "arm64", "OpenBSD 7.4").setTags("test", "baseline").setHost("bmh-build-arm64-openbsd-baseline"),
            //new Target("openbsd", "riscv64", "OpenBSD 7.8").setTags("test", "latest").setHost("bmh-build-riscv64-openbsd-latest")
        );
    }

    protected void mvnCrossHostTests(List<Target> crossHostTestTargets) throws Exception {
        final boolean serial = this.config.flag("serial").orElse(false);

        log.info(fixedWidthCenter("Usage", 100, '!'));
        log.info("");
        log.info("You can modify how this task runs with a few different arguments.");
        log.info("");
        log.info("Run these tests in serial mode (default is parallel) (useful for debugging):");
        log.info("  --serial");
        log.info("");
        log.info("Run these tests on a smaller subset of systems, as comma-delimited list:");
        log.info("  --targets linux-x64,linux-arm64");
        log.info("");
        log.info("Run these tests on a smaller subset of tags, as comma-delimited list:");
        log.info("  --tags latest,baseline");
        log.info("");
        log.info(fixedWidthLeft("", 100, '!'));

        // pause slighly so you can see the usage info better
        Thread.sleep(1000);

        new Buildx(crossHostTestTargets)
            .parallel(!serial)
            .resultsFile(this.projectDir.resolve("buildx-results.txt").toAbsolutePath().normalize())
            .execute((target, project) -> {
                project.exec("mvn", "clean", "test")
                    .run();
            });
    }

    protected void mvnCrossTests(List<Target> crossTestTargets) throws Exception {
        final boolean serial = this.config.flag("serial").orElse(false);

        log.info(fixedWidthCenter("Usage", 100, '!'));
        log.info("");
        log.info("You can modify how this task runs with a few different arguments.");
        log.info("");
        log.info("Run these tests in serial mode (default is parallel) (useful for debugging):");
        log.info("  --serial");
        log.info("");
        log.info("Run these tests on a smaller subset of systems, as comma-delimited list:");
        log.info("  --targets linux-x64,linux-arm64");
        log.info("");
        log.info("Run these tests on a smaller subset of tags, as comma-delimited list:");
        log.info("  --tags latest,baseline");
        log.info("");
        log.info(fixedWidthLeft("", 100, '!'));

        // pause slighly so you can see the usage info better
        Thread.sleep(1000);

        new Buildx(crossTestTargets)
            .parallel(!serial)
            .resultsFile(this.projectDir.resolve("buildx-results.txt").toAbsolutePath().normalize())
            .execute((target, project) -> {
                if (target.getName().startsWith("jck-")) {
                    // leverage the "java_home" data key to pass the java home to the test
                    project.exec("mvn", "clean", "test")
                        .workingDir(this.projectDir)
                        .env("JAVA_HOME", target.getData().get("java_home").toString())
                        .run();
                } else {
                    project.exec("mvn", "clean", "test")
                        .run();
                }
            });
    }

}