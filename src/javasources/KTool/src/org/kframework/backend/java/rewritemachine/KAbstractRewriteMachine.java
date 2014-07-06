// Copyright (c) 2014 K Team. All Rights Reserved.
package org.kframework.backend.java.rewritemachine;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.commons.collections15.ListUtils;
import org.kframework.backend.java.kil.Cell;
import org.kframework.backend.java.kil.CellCollection;
import org.kframework.backend.java.kil.Rule;
import org.kframework.backend.java.kil.Term;
import org.kframework.backend.java.kil.TermContext;
import org.kframework.backend.java.kil.Variable;
import org.kframework.backend.java.symbolic.CopyOnShareSubstAndEvalTransformer;
import org.kframework.backend.java.symbolic.DeepCloner;
import org.kframework.backend.java.symbolic.PatternMatcher;
import org.kframework.backend.java.symbolic.Transformer;
import org.kframework.backend.java.util.Profiler;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

/**
 * 
 * @author YilongL
 *
 */
public class KAbstractRewriteMachine {
    
    private final Rule rule;
    private final Cell<?> subject;
    private final List<Instruction> instructions;

    private ExtendedSubstitution fExtSubst = new ExtendedSubstitution();
    private List<List<ExtendedSubstitution>> fMultiExtSubsts = Lists.newArrayList();
    
    // program counter
    private int pc = 1;
    private Instruction nextInstr;
    private boolean success = true;
    private boolean isStarNested = false;
    
    private final TermContext context;
    
    private KAbstractRewriteMachine(Rule rule, Cell<?> subject, TermContext context) {
        this.rule = rule;
        this.subject = subject;
        this.instructions = rule.instructions();
        this.context = context;
    }
    
    public static boolean rewrite(Rule rule, Term subject, TermContext context) {
        KAbstractRewriteMachine machine = new KAbstractRewriteMachine(rule, (Cell<?>) subject, context);
        return machine.rewrite();
    }
    
    @SuppressWarnings({ "rawtypes", "unchecked" })
    private boolean rewrite() {
        match(subject);
        if (success) {
            List<ExtendedSubstitution> normalizedExtSubsts = getCNFExtendedSubstitutions(
                    fExtSubst, fMultiExtSubsts);
            
            Profiler.startTimer(Profiler.EVALUATE_SIDE_CONDITIONS_TIMER);
            /* take the first match that also satisfies the side-condition as solution */
            ExtendedSubstitution solution = null;
            for (ExtendedSubstitution extSubst : normalizedExtSubsts) {
                Map<Variable, Term> updatedSubst = evaluateConditions(extSubst.substitution());
                if (updatedSubst != null) {
                    /* update the substitution according to the result of evaluation */
                    extSubst.setSubst(updatedSubst);
                    solution = extSubst;
                    break;
                }
            }
            Profiler.stopTimer(Profiler.EVALUATE_SIDE_CONDITIONS_TIMER);
            
            if (solution != null) {
                Profiler.startTimer(Profiler.LOCAL_REWRITE_BUILD_RHS_TIMER);
                // YilongL: cannot use solution.keySet() as variablesToReuse
                // because read-only cell may have already used up the binding
                // term
                Transformer substAndEvalTransformer = new CopyOnShareSubstAndEvalTransformer(
                        solution.substitution(), rule.reusableLhsVariables().elementSet(),
                        context);
                
                /* perform local rewrites under write cells */
                for (Cell cell : solution.writeCells()) {
                    String cellLabel = cell.getLabel();
                    Term rightHandSide = getWriteCellRHS(cellLabel);
                    if (rule.cellsToCopy().contains(cellLabel)) {
                        rightHandSide = DeepCloner.clone(rightHandSide);
                    }

                    cell.unsafeSetContent((Term) rightHandSide.accept(substAndEvalTransformer));
                }
                Profiler.stopTimer(Profiler.LOCAL_REWRITE_BUILD_RHS_TIMER);
            } else {
                success = false;
            }
        }
        
        return success;
    }

    private void match(Cell<?> crntCell) {
        String cellLabel = crntCell.getLabel();
        if (isReadCell(cellLabel)) {
            /* 1) perform matching under read cell; 
             * 2) record the reference if it is also a write cell. */
            
            Profiler.startTimer(Profiler.PATTERN_MATCH_TIMER);
            /* there should be no AC-matching under the crntCell (violated rule
             * has been filtered out by the compiler) */
            Map<Variable, Term> subst = PatternMatcher.nonAssocCommPatternMatch(
                    crntCell.getContent(), getReadCellLHS(cellLabel), context);
            
            if (subst == null) {
                success = false;
            } else {
                Map<Variable, Term> composedSubst = PatternMatcher.composeSubstitution(fExtSubst.substitution(), subst);
                if (composedSubst == null) {
                    success = false;
                } else {
                    fExtSubst.setSubst(composedSubst);
                    if (isWriteCell(cellLabel)) {
                        fExtSubst.addWriteCell(crntCell);
                    }
                }
            }
            Profiler.stopTimer(Profiler.PATTERN_MATCH_TIMER);
            
            if (!success) {
                return;
            }
        }
        
        while (true) {
            nextInstr = nextInstruction();
           
            if (nextInstr == Instruction.UP) {
                return;
            }

            if (nextInstr == Instruction.CHOICE) {
                assert !isStarNested : "nested cells with multiplicity='*' not supported";
                isStarNested = true; // start of AC-matching
                
                ExtendedSubstitution oldExtSubst = fExtSubst;
                fExtSubst = new ExtendedSubstitution();
                List<ExtendedSubstitution> extSubsts = Lists.newArrayList();

                nextInstr = nextInstruction();
                int oldPC = pc; // pgm counter before AC-matching
                int newPC = -1; // pgm counter on success
                for (Cell<?> cell : getSubCellsByLabel(crntCell, nextInstr.cellLabel())) {
                    pc = oldPC;
                    match(cell);
                    if (success) {
                        newPC = pc;
                        extSubsts.add(fExtSubst);
                    }
                    
                    /* clean up side-effects of the previous match attempt */
                    success = true;
                    fExtSubst = new ExtendedSubstitution();
                }
                
                isStarNested = false; // end of AC-matching
                
                if (extSubsts.isEmpty()) {
                    success = false;
                    return;
                } else {
                    pc = newPC;
                    fMultiExtSubsts.add(extSubsts);
                    fExtSubst = oldExtSubst;
                }
            } else {
                Iterator<Cell> iter = getSubCellsByLabel(crntCell, nextInstr.cellLabel()).iterator();
                if (iter.hasNext()) {
                    Cell<?> nextCell = iter.next();
                    match(nextCell);
                } else {
                    success = false;
                }
                
                if (!success) {
                    return;
                }
            }
        }
    }
    
    /**
     * Evaluates the side-conditions of a rule according to a given
     * substitution.
     * 
     * @param substitution
     * @return the updated substitution on success; otherwise, {@code null}
     */
    private Map<Variable, Term> evaluateConditions(Map<Variable, Term> substitution) {
        List<Map<Variable, Term>> results = PatternMatcher.evaluateConditions(
                rule, Collections.singletonList(substitution), context);
        assert results.size() <= 1;
        return results.isEmpty() ? null : results.get(0);
    }
   
    private Instruction nextInstruction() {
        return instructions.get(pc++);
    }

    private boolean isReadCell(String cellLabel) {
        return rule.lhsOfReadCell().keySet().contains(cellLabel);
    }

    private boolean isWriteCell(String cellLabel) {
        return rule.rhsOfWriteCell().keySet().contains(cellLabel);
    }
    
    private Term getReadCellLHS(String cellLabel) {
        return rule.lhsOfReadCell().get(cellLabel);
    }

    private Term getWriteCellRHS(String cellLabel) {
        return rule.rhsOfWriteCell().get(cellLabel);
    }
    
    private Collection<Cell> getSubCellsByLabel(Cell<?> cell, String label) {
        return ((CellCollection) cell.getContent()).cellMap().get(label);
    }

    /**
     * Returns the CNF of the formula which consists of extanded substitutions.
     */
    private static List<ExtendedSubstitution> getCNFExtendedSubstitutions(
            ExtendedSubstitution fSubst,
            List<List<ExtendedSubstitution>> multiExtSubsts) {
        List<ExtendedSubstitution> result = Lists.newArrayList();
        
        if (!multiExtSubsts.isEmpty()) {
            assert multiExtSubsts.size() <= 2;

            if (multiExtSubsts.size() == 1) {
                for (ExtendedSubstitution extSubst : multiExtSubsts.get(0)) {
                    Map<Variable, Term> composedSubst = PatternMatcher
                            .composeSubstitution(fSubst.substitution(), extSubst.substitution());
                    if (composedSubst != null) {
                        List<Cell<?>> composedWrtCells = ListUtils.union(fSubst.writeCells(), extSubst.writeCells());
                        result.add(new ExtendedSubstitution(composedSubst, composedWrtCells));
                    }
                }
            } else {
                for (ExtendedSubstitution subst1 : multiExtSubsts.get(0)) {
                    for (ExtendedSubstitution subst2 : multiExtSubsts.get(1)) {
                        Map<Variable, Term> composedSubst = PatternMatcher
                                .composeSubstitution(
                                        fSubst.substitution(),
                                        subst1.substitution(),
                                        subst2.substitution());
                        
                        if (composedSubst != null) {
                            List<Cell<?>> composedWrtCells = ListUtils.union(
                                    fSubst.writeCells(), 
                                    ListUtils.union(subst1.writeCells(), subst2.writeCells()));
                            result.add(new ExtendedSubstitution(composedSubst, composedWrtCells));
                        }
                    }
                }
            }
        } else {
            result.add(new ExtendedSubstitution(
                    Maps.newHashMap(fSubst.substitution()), 
                    Lists.newArrayList(fSubst.writeCells())));
        }
        
        return result;
    }
    
}
