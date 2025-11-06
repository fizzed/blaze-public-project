package com.fizzed.blaze.incubating;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class VcVars implements AutoCloseable {

    private final Map<String,String> vcVars;
    private final List<Path> vcPaths;

    public VcVars(Map<String, String> vcVars, List<Path> vcPaths) {
        this.vcVars = vcVars;
        this.vcPaths = vcPaths;
    }

    public Map<String, String> getVcVars() {
        return vcVars;
    }

    public List<Path> getVcPaths() {
        return vcPaths;
    }

    @Override
    public void close() throws Exception {
        // do nothing
    }

}