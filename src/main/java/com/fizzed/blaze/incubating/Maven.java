package com.fizzed.blaze.incubating;

import java.nio.file.Path;

public class Maven {

    private final Path pomFile;

    public Maven(Path pomFile) {
        this.pomFile = pomFile;
    }

    public Path getPomFile() {
        return pomFile;
    }

}