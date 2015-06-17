// Copyright (c) 2014-2015 K Team. All Rights Reserved.

package org.kframework.kore.convertors;

import junit.framework.Assert;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.rules.TestName;
import org.kframework.attributes.Source;
import org.kframework.builtin.Sorts;
import org.kframework.definition.Module;
import org.kframework.kompile.CompiledDefinition;
import org.kframework.kompile.Kompile;
import org.kframework.kompile.KompileOptions;
import org.kframework.kore.K;
import org.kframework.main.GlobalOptions;
import org.kframework.tiny.Rewriter;
import org.kframework.utils.Stopwatch;
import org.kframework.utils.errorsystem.KExceptionManager;
import org.kframework.utils.file.FileUtil;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Optional;
import java.util.function.BiFunction;


public class TstTinyOnKORE_IT {

    @org.junit.Rule
    public TestName name = new TestName();


    protected File testResource(String baseName) throws URISyntaxException {
        return new File(TstTinyOnKORE_IT.class.getResource(baseName).toURI());
    }

    @Test @Ignore
    public void kore_imp_tiny() throws IOException, URISyntaxException {

        String filename = "/convertor-tests/" + name.getMethodName() + ".k";

        File definitionFile = testResource(filename);
        KExceptionManager kem = new KExceptionManager(new GlobalOptions());
        try {
            CompiledDefinition compiledDef = new Kompile(new KompileOptions(), FileUtil.testFileUtil(), kem, false).run(definitionFile, "TEST", "TEST-PROGRAMS", Sorts.K());

            Module module = compiledDef.executionModule();
            BiFunction<String, Source, K> programParser = compiledDef.getProgramParser(kem);
            Rewriter rewriter = new org.kframework.tiny.Rewriter(module);

            K program = programParser.apply(
                    "<top><k> while(0<=n) { s = s + n; n = n + -1; } </k><state>n|->10 s|->0</state></top>", Source.apply("generated by " + getClass().getSimpleName()));

            long l = System.nanoTime();
            K result = rewriter.execute(program, Optional.empty());
            System.out.println("time = " + (System.nanoTime() - l) / 1000000);

            System.out.println("result = " + result.toString());

            Assert.assertEquals("<top>(<k>(#KSequence()),<state>(_Map_(_|->_(n:Id,-1),_|->_(s:Id,55))))", result.toString());
        } finally {
            kem.print();
        }
    }

}
