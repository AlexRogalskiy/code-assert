/*
 * Copyright (C) 2015 Stefan Niederhauser (nidin@gmx.ch)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package guru.nidi.codeassert.pmd;

import guru.nidi.codeassert.Analyzer;
import guru.nidi.codeassert.AnalyzerException;
import guru.nidi.codeassert.config.AnalyzerConfig;
import guru.nidi.codeassert.config.UsageCounter;
import net.sourceforge.pmd.PMD;
import net.sourceforge.pmd.PMDConfiguration;
import net.sourceforge.pmd.Report;
import net.sourceforge.pmd.RuleViolation;
import net.sourceforge.pmd.renderers.AbstractAccumulatingRenderer;
import net.sourceforge.pmd.renderers.Renderer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.*;

public class PmdAnalyzer implements Analyzer<List<RuleViolation>> {
    private static final Comparator<RuleViolation> VIOLATION_SORTER = new Comparator<RuleViolation>() {
        @Override
        public int compare(RuleViolation v1, RuleViolation v2) {
            final int prio = v1.getRule().getPriority().getPriority() - v2.getRule().getPriority().getPriority();
            if (prio != 0) {
                return prio;
            }
            return v1.getRule().getName().compareTo(v2.getRule().getName());
        }
    };

    private final AnalyzerConfig config;
    private final PmdViolationCollector collector;
    private final Map<String, Ruleset> rulesets;

    public PmdAnalyzer(AnalyzerConfig config, PmdViolationCollector collector) {
        this(config, collector, new HashMap<String, Ruleset>());
    }

    private PmdAnalyzer(AnalyzerConfig config, PmdViolationCollector collector, Map<String, Ruleset> rulesets) {
        this.config = config;
        this.collector = collector;
        this.rulesets = rulesets;
    }

    public PmdAnalyzer withRulesets(Ruleset... rulesets) {
        final Map<String, Ruleset> newRuleset = new HashMap<>();
        newRuleset.putAll(this.rulesets);
        for (final Ruleset ruleset : rulesets) {
            newRuleset.put(ruleset.name, ruleset);
        }
        return new PmdAnalyzer(config, collector, newRuleset);
    }

    public PmdAnalyzer withoutRulesets(Ruleset... rulesets) {
        final Map<String, Ruleset> newRuleset = new HashMap<>();
        newRuleset.putAll(this.rulesets);
        for (final Ruleset ruleset : rulesets) {
            newRuleset.remove(ruleset.name);
        }
        return new PmdAnalyzer(config, collector, newRuleset);
    }

    @Override
    public PmdResult analyze() {
        if (rulesets.isEmpty()) {
            throw new AnalyzerException("No rulesets defined. Use the withRulesets methods to define some. See Rulesets class for predefined rule sets.");
        }
        //avoid System.out from being closed
        final PrintStream originalSysOut = System.out;
        System.setOut(new NonCloseablePrintStream(originalSysOut));
        try {
            final PmdRenderer renderer = new PmdRenderer();
            final PMDConfiguration pmdConfig = createPmdConfig(renderer);
            PMD.doPMD(pmdConfig);
            return processViolations(renderer);
        } finally {
            System.setOut(originalSysOut);
        }
    }

    private PmdResult processViolations(PmdRenderer renderer) {
        final List<RuleViolation> violations = new ArrayList<>();
        final UsageCounter counter = new UsageCounter();
        if (renderer.getReport() != null) {
            for (final RuleViolation violation : renderer.getReport()) {
                if (counter.accept(collector.accept(violation))) {
                    violations.add(violation);
                }
            }
        }
        Collections.sort(violations, VIOLATION_SORTER);
        collector.printUnusedWarning(counter);
        return new PmdResult(this, violations, collector.unusedActions(counter));
    }

    private PMDConfiguration createPmdConfig(final PmdRenderer renderer) {
        final PMDConfiguration pmdConfig = new PMDConfiguration() {
            @Override
            public Renderer createRenderer() {
                for (final Ruleset ruleset : rulesets.values()) {
                    ruleset.apply(this);
                }
                return renderer;
            }
        };
        final StringBuilder inputs = new StringBuilder();
        for (final AnalyzerConfig.Path source : config.getSources()) {
            inputs.append(',').append(source.getPath());
        }
        pmdConfig.setInputPaths(inputs.substring(1));
        pmdConfig.setRuleSets(ruleSetNames());
        pmdConfig.setThreads(0);
        return pmdConfig;
    }

    private String ruleSetNames() {
        final StringBuilder s = new StringBuilder();
        for (final Ruleset ruleset : rulesets.values()) {
            s.append(',').append(ruleset.name);
        }
        return rulesets.isEmpty() ? "" : s.substring(1);
    }

    private static class PmdRenderer extends AbstractAccumulatingRenderer {
        public PmdRenderer() {
            super("", "");
        }

        @Override
        public String defaultFileExtension() {
            return null;
        }

        @Override
        public void end() throws IOException {
            //do nothing
        }

        public Report getReport() {
            return report;
        }
    }

    private static class NonCloseablePrintStream extends PrintStream {
        public NonCloseablePrintStream(OutputStream out) {
            super(out);
        }

        @Override
        public void close() {
            //do nothing
        }
    }

}
