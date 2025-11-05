package com.fizzed.blaze.incubating;

import com.fizzed.blaze.Context;
import com.fizzed.blaze.Systems;
import com.fizzed.blaze.core.Action;
import com.fizzed.blaze.core.BlazeException;
import com.fizzed.blaze.core.VerbosityMixin;
import com.fizzed.blaze.util.VerboseLogger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static com.fizzed.blaze.Systems.exec;
import static java.util.stream.Collectors.joining;

public class VcVarsInitializer extends Action<VcVarsInitializer.Result,VcVars> implements VerbosityMixin<VcVarsInitializer> {

    static public class Result extends com.fizzed.blaze.core.Result<VcVarsInitializer,VcVars,Result> {
        public Result(VcVarsInitializer action, VcVars value) {
            super(action, value);
        }
    }

    protected final VerboseLogger log;
    private String arch;
    private List<Integer> preferredYears;

    public VcVarsInitializer(Context context) {
        super(context);
        this.log = new VerboseLogger(this);
        this.arch = System.getProperty("os.arch");
        this.preferredYears = Arrays.asList(2022, 2019, 2017);
    }

    @Override
    public VerboseLogger getVerboseLogger() {
        return this.log;
    }

    public VcVarsInitializer arch(String arch) {
        this.arch = arch;
        return this;
    }

    public VcVarsInitializer preferredYears(Integer... preferredYears) {
        this.preferredYears = Arrays.asList(preferredYears);
        return this;
    }

    @Override
    protected Result doRun() throws BlazeException {
        final String vcVarsArch = resolveTargetArch(this.arch);

        // search for vcvarsall.bat file
        Path vcVarsAllBatFile = null;
        for (int year :  preferredYears) {
            Path f = Paths.get("C:\\Program Files\\Microsoft Visual Studio\\"+year+"\\Community\\VC\\Auxiliary\\Build\\vcvarsall.bat");
            log.debug("Searching vcvars @ {}", f);
            if (Files.exists(f)) {
                vcVarsAllBatFile = f;
                break;
            }
        }

        if (vcVarsAllBatFile == null) {
            throw new IllegalStateException("Could not find vcvarsall.bat for years " + this.preferredYears);
        }

        // get a snapshot of variables before running vcvarsall.bat
        final String preEnvOutput = exec("cmd", "/c", "set")
            .runCaptureOutput(false)
            .toString();

        // parse the before list into a map
        final Map<String,String> preEnv = parseEnvVars(preEnvOutput);

        // now call vcvarsall.bat, grab the adjusted env vars
        log.info("Loading visual studio variables for {} from {}", vcVarsArch, vcVarsAllBatFile);

        final String postEnvOutput = exec("cmd", "/c", "\"call \"" + vcVarsAllBatFile + "\" " + vcVarsArch + " & set\"")
            .runCaptureOutput(false)
            .toString();

        // parse the after list into a map
        final Map<String,String> postEnv = parseEnvVars(postEnvOutput);

        // calculate changes
        final Map<String,String> vcVars = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Map.Entry<String,String> afterEntry : postEnv.entrySet()) {
            String envBeforeValue = preEnv.get(afterEntry.getKey());
            if (envBeforeValue == null) {
                //log.info("NEW env: {} => {}",  afterEntry.getKey(), afterEntry.getValue());
                vcVars.put(afterEntry.getKey(), afterEntry.getValue());
            } else if (!envBeforeValue.equals(afterEntry.getValue())) {
                //log.info("CHANGED env: {} => {}",  afterEntry.getKey(), afterEntry.getValue());
                vcVars.put(afterEntry.getKey(), envBeforeValue + "," + afterEntry.getValue());
            } else {
                //log.info("SAME env: {} => {}",  afterEntry.getKey(), afterEntry.getValue());
            }
        }

        log.verbose("Detected {} environment variables we will inject to every vcVarsExec() call", vcVars.size());

        // for some reason the PATH is case sensitive when calling execs, and the PATH contains an odd "," in
        // some cases instead of ";", so we will remove the PATH, clean it up, and add it back
        final String uncleanPath = vcVars.remove("PATH");

        final List<Path> vcPaths = Arrays.stream(uncleanPath.split("[;,]"))
            .map(Paths::get)
            .collect(Collectors.toList());

        // build a better, improved path and set it as all caps
        final String sanitizedPath = vcPaths.stream()
            .map(Object::toString)
            .collect(joining(";"));

        vcVars.put("PATH", sanitizedPath);

        final VcVars v = new VcVars(vcVars, vcPaths);

        // we should try to find the "cl" to make sure it worked
        final Path clPath = Systems.which("cl")
            .paths(vcPaths)
            .run();

        if (clPath == null) {
            throw new IllegalStateException("Could not find cl.exe in any of the paths: " + vcPaths + " (vcvarsall.bat was not successful!)");
        }

        log.verbose("VcVars appears successful, found cl.exe @ {}", clPath);

        return new Result(this, v);
    }

    static private Map<String,String> parseEnvVars(String output) {
        final Map<String,String> envVars = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

        for (String line : output.split("\n")) {
            String[] nv = line.trim().split("=");
            if (nv.length == 2) {
                String name = nv[0].trim();
                String value = nv[1].trim();
                //log.info("{} => {}", name, value);
                envVars.put(name, value);
            }
        }

        return envVars;
    }

    static private String resolveTargetArch(String targetArch) {
        // arch can be a few things, that we need to match
        final String hostArch = System.getProperty("os.arch");              // x86_64 or aarch64

        if ("x64".equalsIgnoreCase(targetArch)) {
            if ("x86_64".equalsIgnoreCase(hostArch) || "amd64".equalsIgnoreCase(hostArch)) {
                return "x64";
            } else if ("aarch64".equalsIgnoreCase(hostArch)) {
                return "arm64_x64";
            } else {
                throw new IllegalArgumentException("Unknown mapping from host arch " + hostArch + " to target arch " + targetArch);
            }
        } else if ("arm64".equalsIgnoreCase(targetArch)) {
            if ("x86_64".equalsIgnoreCase(hostArch) || "amd64".equalsIgnoreCase(hostArch)) {
                return "x64_arm64";
            } else if ("aarch64".equalsIgnoreCase(hostArch)) {
                return "arm64";
            } else {
                throw new IllegalArgumentException("Unknown mapping from host arch " + hostArch + " to target arch " + targetArch);
            }
        } else {
            throw new IllegalArgumentException("Unknown target arch " + targetArch + " (valid are x64 or arm64)");
        }
    }

}