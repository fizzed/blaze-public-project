package com.fizzed.blaze.incubating;

import com.fizzed.blaze.Contexts;
import com.fizzed.blaze.Systems;
import com.fizzed.blaze.system.Exec;

public class VisualStudios {

    static public VcVarsInitializer vcVars() {
        return new VcVarsInitializer(Contexts.currentContext());
    }

    static public Exec vcVarsExec(VcVars vcVars, String command, Object... arguments) {
        Exec exec = Systems.exec(command, arguments)
            .paths(vcVars.getVcPaths());

        vcVars.getVcVars().forEach(exec::env);

        return exec;
    }

}