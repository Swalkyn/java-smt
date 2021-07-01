// This file is part of JavaSMT,
// an API wrapper for a collection of SMT solvers:
// https://github.com/sosy-lab/java-smt
//
// SPDX-FileCopyrightText: 2020 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.java_smt.solvers.princess;

import static com.google.common.collect.Iterables.getOnlyElement;
import static scala.collection.JavaConverters.asJava;
import static scala.collection.JavaConverters.collectionAsScalaIterableConverter;

import ap.SimpleAPI;
import ap.parser.BooleanCompactifier;
import ap.parser.Environment.EnvironmentException;
import ap.parser.IAtom;
import ap.parser.IConstant;
import ap.parser.IExpression;
import ap.parser.IFormula;
import ap.parser.IFunApp;
import ap.parser.IFunction;
import ap.parser.IIntFormula;
import ap.parser.ITerm;
import ap.parser.Parser2InputAbsy.TranslationException;
import ap.parser.PartialEvaluator;
import ap.parser.SMTLineariser;
import ap.parser.SMTParser2InputAbsy.SMTFunctionType;
import ap.parser.SMTParser2InputAbsy.SMTType;
import ap.terfor.ConstantTerm;
import ap.terfor.preds.Predicate;
import ap.theories.ExtArray;
import ap.theories.bitvectors.ModuloArithmetic;
import ap.types.Sort;
import ap.types.Sort$;
import ap.types.Sort.MultipleValueBool$;
import ap.util.Debug;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.io.Files;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.sosy_lab.common.Appender;
import org.sosy_lab.common.Appenders;
import org.sosy_lab.common.ShutdownNotifier;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.FileOption;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.common.configuration.Option;
import org.sosy_lab.common.configuration.Options;
import org.sosy_lab.common.io.PathCounterTemplate;
import org.sosy_lab.java_smt.api.FormulaType;
import org.sosy_lab.java_smt.api.FormulaType.ArrayFormulaType;
import org.sosy_lab.java_smt.api.SolverContext.ProverOptions;
import scala.Tuple2;
import scala.Tuple4;
import scala.collection.immutable.Seq;

/**
 * This is a Wrapper around Princess. This Wrapper allows to set a logfile for all Smt-Queries
 * (default "princess.###.smt2"). It also manages the "shared variables": each variable is declared
 * for all stacks.
 */
@Options(prefix = "solver.princess")
class PrincessEnvironment {

  @Option(
      secure = true,
      description =
          "The number of atoms a term has to have before"
              + " it gets abbreviated if there are more identical terms.")
  private int minAtomsForAbbreviation = 100;

  @Option(
      secure = true,
      description =
          "Enable additional assertion checks within Princess. "
              + "The main usage is debugging. This option can cause a performance overhead.")
  private boolean enableAssertions = false;

  public static final Sort BOOL_SORT = Sort$.MODULE$.Bool();
  public static final Sort INTEGER_SORT = Sort.Integer$.MODULE$;

  @Option(secure = true, description = "log all queries as Princess-specific Scala code")
  private boolean logAllQueriesAsScala = false;

  @Option(secure = true, description = "file for Princess-specific dump of queries as Scala code")
  @FileOption(FileOption.Type.OUTPUT_FILE)
  private PathCounterTemplate logAllQueriesAsScalaFile =
      PathCounterTemplate.ofFormatString("princess-query-%03d-");

  /**
   * cache for variables, because they do not implement equals() and hashCode(), so we need to have
   * the same objects.
   */
  private final Map<String, IFormula> boolVariablesCache = new HashMap<>();

  private final Map<String, ITerm> sortedVariablesCache = new HashMap<>();

  private final Map<String, IFunction> functionsCache = new HashMap<>();

  private final int randomSeed;
  private final @Nullable PathCounterTemplate basicLogfile;
  private final ShutdownNotifier shutdownNotifier;

  /**
   * The wrapped API is the first created API. It will never be used outside of this class and never
   * be closed. If a variable is declared, it is declared in the first api, then copied into all
   * registered APIs. Each API has its own stack for formulas.
   */
  private final SimpleAPI api;

  private final List<PrincessAbstractProver<?, ?>> registeredProvers = new ArrayList<>();

  PrincessEnvironment(
      Configuration config,
      @Nullable final PathCounterTemplate pBasicLogfile,
      ShutdownNotifier pShutdownNotifier,
      final int pRandomSeed)
      throws InvalidConfigurationException {
    config.inject(this);

    basicLogfile = pBasicLogfile;
    shutdownNotifier = pShutdownNotifier;
    randomSeed = pRandomSeed;

    // this api is only used local in this environment, no need for interpolation
    api = getNewApi(false);
  }

  /**
   * This method returns a new prover, that is registered in this environment. All variables are
   * shared in all registered APIs.
   */
  PrincessAbstractProver<?, ?> getNewProver(
      boolean useForInterpolation,
      PrincessFormulaManager mgr,
      PrincessFormulaCreator creator,
      Set<ProverOptions> pOptions) {

    SimpleAPI newApi =
        getNewApi(useForInterpolation || pOptions.contains(ProverOptions.GENERATE_UNSAT_CORE));

    // add all symbols, that are available until now
    boolVariablesCache.values().forEach(newApi::addBooleanVariable);
    sortedVariablesCache.values().forEach(newApi::addConstant);
    functionsCache.values().forEach(newApi::addFunction);

    PrincessAbstractProver<?, ?> prover;
    if (useForInterpolation) {
      prover = new PrincessInterpolatingProver(mgr, creator, newApi, shutdownNotifier, pOptions);
    } else {
      prover = new PrincessTheoremProver(mgr, creator, newApi, shutdownNotifier, pOptions);
    }
    registeredProvers.add(prover);
    return prover;
  }

  @SuppressFBWarnings("NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE")
  private SimpleAPI getNewApi(boolean constructProofs) {
    File directory = null;
    String smtDumpBasename = null;
    String scalaDumpBasename = null;

    if (basicLogfile != null) {
      Path logPath = basicLogfile.getFreshPath();
      directory = getAbsoluteParent(logPath);
      smtDumpBasename = logPath.getFileName().toString();
      if (Files.getFileExtension(smtDumpBasename).equals("smt2")) {
        // Princess adds .smt2 anyway
        smtDumpBasename = Files.getNameWithoutExtension(smtDumpBasename);
      }
      smtDumpBasename += "-";
    }

    if (logAllQueriesAsScala && logAllQueriesAsScalaFile != null) {
      Path logPath = logAllQueriesAsScalaFile.getFreshPath();
      if (directory == null) {
        directory = getAbsoluteParent(logPath);
      }
      scalaDumpBasename = logPath.getFileName().toString();
    }

    Debug.enableAllAssertions(enableAssertions);

    final SimpleAPI newApi =
        SimpleAPI.apply(
            enableAssertions, // enableAssert, see above
            false, // no sanitiseNames, because variable names may contain chars like "@" and ":".
            smtDumpBasename != null, // dumpSMT
            smtDumpBasename, // smtDumpBasename
            scalaDumpBasename != null, // dumpScala
            scalaDumpBasename, // scalaDumpBasename
            directory, // dumpDirectory
            SimpleAPI.apply$default$8(), // tightFunctionScopes
            SimpleAPI.apply$default$9(), // genTotalityAxioms
            new scala.Some<>(randomSeed) // randomSeed
            );

    if (constructProofs) { // needed for interpolation and unsat cores
      newApi.setConstructProofs(true);
    }

    return newApi;
  }

  private File getAbsoluteParent(Path path) {
    return Optional.ofNullable(path.getParent()).orElse(Paths.get(".")).toAbsolutePath().toFile();
  }

  int getMinAtomsForAbbreviation() {
    return minAtomsForAbbreviation;
  }

  void unregisterStack(PrincessAbstractProver<?, ?> stack) {
    assert registeredProvers.contains(stack) : "cannot unregister stack, it is not registered";
    registeredProvers.remove(stack);
  }

  /** unregister and close all stacks. */
  void close() {
    for (PrincessAbstractProver<?, ?> prover : ImmutableList.copyOf(registeredProvers)) {
      prover.close();
    }
    api.shutDown();
    api.reset();
    Preconditions.checkState(registeredProvers.isEmpty());
  }

  public List<? extends IExpression> parseStringToTerms(String s, PrincessFormulaCreator creator) {

    Tuple4<
            Seq<IFormula>,
            scala.collection.immutable.Map<IFunction, SMTFunctionType>,
            scala.collection.immutable.Map<ConstantTerm, SMTType>,
            scala.collection.immutable.Map<Predicate, SMTFunctionType>>
        parserResult;

    try {
      parserResult = extractFromSTMLIB(s);
    } catch (TranslationException | EnvironmentException nested) {
      throw new IllegalArgumentException(nested);
    }

    final List<IFormula> formulas = asJava(parserResult._1());

    ImmutableSet.Builder<IExpression> declaredFunctions = ImmutableSet.builder();
    for (IExpression f : formulas) {
      declaredFunctions.addAll(creator.extractVariablesAndUFs(f, true).values());
    }
    for (IExpression var : declaredFunctions.build()) {
      if (var instanceof IConstant) {
        sortedVariablesCache.put(((IConstant) var).c().name(), (ITerm) var);
        addSymbol((IConstant) var);
      } else if (var instanceof IAtom) {
        boolVariablesCache.put(((IAtom) var).pred().name(), (IFormula) var);
        addSymbol((IAtom) var);
      } else if (var instanceof IFunApp) {
        IFunction fun = ((IFunApp) var).fun();
        functionsCache.put(fun.name(), fun);
        addFunction(fun);
      }
    }
    return formulas;
  }

  /**
   * Parse a SMTLIB query and returns a triple of the asserted formulas, the defined functions and
   * symbols.
   *
   * @throws EnvironmentException from Princess when the parsing fails
   * @throws TranslationException from Princess when the parsing fails due to type mismatch
   */
  /* EnvironmentException is not unused, but the Java compiler does not like Scala. */
  @SuppressWarnings("unused")
  private Tuple4<
          Seq<IFormula>,
          scala.collection.immutable.Map<IFunction, SMTFunctionType>,
          scala.collection.immutable.Map<ConstantTerm, SMTType>,
          scala.collection.immutable.Map<Predicate, SMTFunctionType>>
      extractFromSTMLIB(String s) throws EnvironmentException, TranslationException {
    // replace let-terms and function definitions by their full term.
    final boolean fullyInlineLetsAndFunctions = true;
    return api.extractSMTLIBAssertionsSymbols(new StringReader(s), fullyInlineLetsAndFunctions);
  }

  /**
   * Utility helper method to hide a checked exception as RuntimeException.
   *
   * <p>The generic E simulates a RuntimeException at compile time and lets us throw the correct
   * Exception at run time.
   */
  @SuppressWarnings("unchecked")
  private static <E extends Throwable> void throwCheckedAsUnchecked(Throwable e) throws E {
    throw (E) e;
  }

  public Appender dumpFormula(IFormula formula, final PrincessFormulaCreator creator) {
    // remove redundant expressions
    // TODO do we want to remove redundancy completely (as checked in the unit
    // tests (SolverFormulaIOTest class)) or do we want to remove redundancy up
    // to the point we do it for formulas that should be asserted
    Tuple2<IExpression, scala.collection.immutable.Map<IExpression, IExpression>> tuple =
        api.abbrevSharedExpressionsWithMap(formula, 1);
    final IExpression lettedFormula = tuple._1();
    final Map<IExpression, IExpression> abbrevMap = asJava(tuple._2());

    return new Appenders.AbstractAppender() {

      @Override
      public void appendTo(Appendable out) throws IOException {
        try {
          appendTo0(out);
        } catch (scala.MatchError e) {
          // exception might be thrown in case of interrupt, then we wrap it in an interrupt.
          if (shutdownNotifier.shouldShutdown()) {
            InterruptedException interrupt = new InterruptedException();
            interrupt.addSuppressed(e);
            throwCheckedAsUnchecked(interrupt);
          } else {
            // simply re-throw exception
            throw e;
          }
        }
      }

      private void appendTo0(Appendable out) throws IOException {
        // allVars needs to be mutable, but declaredFunctions should have deterministic order
        Set<IExpression> allVars =
            ImmutableSet.copyOf(creator.extractVariablesAndUFs(lettedFormula, true).values());
        Deque<IExpression> declaredFunctions = new ArrayDeque<>(allVars);
        allVars = new HashSet<>(allVars);

        Set<String> doneFunctions = new HashSet<>();
        Set<String> todoAbbrevs = new HashSet<>();

        while (!declaredFunctions.isEmpty()) {
          IExpression var = declaredFunctions.poll();
          String name = getName(var);

          // we don't want to declare variables twice, so doublecheck
          // if we have already found the current variable
          if (doneFunctions.contains(name)) {
            continue;
          }
          doneFunctions.add(name);

          // we do only want to add declare-funs for things we really declared
          // the rest is done afterwards
          if (name.startsWith("abbrev_")) {
            todoAbbrevs.add(name);
            Set<IExpression> varsFromAbbrev =
                ImmutableSet.copyOf(
                    creator.extractVariablesAndUFs(abbrevMap.get(var), true).values());
            Sets.difference(varsFromAbbrev, allVars).forEach(declaredFunctions::push);
            allVars.addAll(varsFromAbbrev);
          } else {
            out.append("(declare-fun ").append(SMTLineariser.quoteIdentifier(name));

            // function parameters
            out.append(" (");
            if (var instanceof IFunApp) {
              IFunApp function = (IFunApp) var;
              List<String> argSorts =
                  Lists.transform(asJava(function.args()), a -> getFormulaType(a).toSMTLIBString());
              Joiner.on(" ").appendTo(out, argSorts);
            }

            out.append(") ");
            out.append(getFormulaType(var).toSMTLIBString());
            out.append(")\n");
          }
        }

        // now as everything we know from the formula is declared we have to add
        // the abbreviations, too
        for (Map.Entry<IExpression, IExpression> entry : abbrevMap.entrySet()) {
          IExpression abbrev = entry.getKey();
          IExpression fullFormula = entry.getValue();
          String name =
              getName(getOnlyElement(creator.extractVariablesAndUFs(abbrev, true).values()));

          // only add the necessary abbreviations
          if (!todoAbbrevs.contains(name)) {
            continue;
          }

          // the type of each abbreviation and the abbreviated formula
          out.append(
              String.format(
                  "(define-fun %s () %s %s)%n",
                  SMTLineariser.quoteIdentifier(name),
                  getFormulaType(fullFormula).toSMTLIBString(),
                  SMTLineariser.asString(fullFormula)));
        }

        // now add the final assert
        out.append("(assert ").append(SMTLineariser.asString(lettedFormula)).append(')');
      }
    };
  }

  private static String getName(IExpression var) {
    if (var instanceof IAtom) {
      return ((IAtom) var).pred().name();
    } else if (var instanceof IConstant) {
      return var.toString();
    } else if (var instanceof IFunApp) {
      String fullStr = ((IFunApp) var).fun().toString();
      return fullStr.substring(0, fullStr.indexOf('/'));
    } else if (var instanceof IIntFormula) {
      return getName(((IIntFormula) var).t());
    }

    throw new IllegalArgumentException("The given parameter is no variable or function");
  }

  static FormulaType<?> getFormulaType(IExpression pFormula) {
    if (pFormula instanceof IFormula) {
      return FormulaType.BooleanType;
    } else if (pFormula instanceof ITerm) {
      final Sort sort = Sort$.MODULE$.sortOf((ITerm) pFormula);
      try {
        return getFormulaTypeFromSort(sort);
      } catch (IllegalArgumentException e) {
        // add more info about the formula, then rethrow
        throw new IllegalArgumentException(
            String.format(
                "Unknown formula type '%s' for formula '%s'.", pFormula.getClass(), pFormula),
            e);
      }
    }
    throw new IllegalArgumentException(
        String.format(
            "Unknown formula type '%s' for formula '%s'.", pFormula.getClass(), pFormula));
  }

  private static FormulaType<?> getFormulaTypeFromSort(final Sort sort) {
    if (sort == PrincessEnvironment.BOOL_SORT) {
      return FormulaType.BooleanType;
    } else if (sort == PrincessEnvironment.INTEGER_SORT) {
      return FormulaType.IntegerType;
    } else if (sort instanceof ExtArray.ArraySort) {
      Seq<Sort> indexSorts = ((ExtArray.ArraySort) sort).theory().indexSorts();
      Sort elementSort = ((ExtArray.ArraySort) sort).theory().objSort();
      assert indexSorts.iterator().size() == 1 : "unexpected index type in Array type:" + sort;
      // assert indexSorts.size() == 1; // TODO Eclipse does not like simpler code.
      return new ArrayFormulaType<>(
          getFormulaTypeFromSort(indexSorts.iterator().next()), // get single index-sort
          getFormulaTypeFromSort(elementSort));
    } else if (sort instanceof MultipleValueBool$) {
      return FormulaType.BooleanType;
    } else {
      scala.Option<Object> bitWidth = getBitWidth(sort);
      if (bitWidth.isDefined()) {
        return FormulaType.getBitvectorTypeWithSize((Integer) bitWidth.get());
      }
    }
    throw new IllegalArgumentException(
        String.format("Unknown formula type '%s' for sort '%s'.", sort.getClass(), sort));
  }

  static scala.Option<Object> getBitWidth(final Sort sort) {
    scala.Option<Object> bitWidth = ModuloArithmetic.UnsignedBVSort$.MODULE$.unapply(sort);
    if (!bitWidth.isDefined()) {
      bitWidth = ModuloArithmetic.SignedBVSort$.MODULE$.unapply(sort);
    }
    return bitWidth;
  }

  public IExpression makeVariable(Sort type, String varname) {
    if (type == BOOL_SORT) {
      if (boolVariablesCache.containsKey(varname)) {
        return boolVariablesCache.get(varname);
      } else {
        IFormula var = api.createBooleanVariable(varname);
        addSymbol(var);
        boolVariablesCache.put(varname, var);
        return var;
      }
    } else {
      if (sortedVariablesCache.containsKey(varname)) {
        return sortedVariablesCache.get(varname);
      } else {
        ITerm var = api.createConstant(varname, type);
        addSymbol(var);
        sortedVariablesCache.put(varname, var);
        return var;
      }
    }
  }

  /** This function declares a new functionSymbol with the given argument types and result. */
  public IFunction declareFun(String name, Sort returnType, List<Sort> args) {
    if (functionsCache.containsKey(name)) {
      return functionsCache.get(name);
    } else {
      IFunction funcDecl =
          api.createFunction(
              name, toSeq(args), returnType, false, SimpleAPI.FunctionalityMode$.MODULE$.Full());
      addFunction(funcDecl);
      functionsCache.put(name, funcDecl);
      return funcDecl;
    }
  }

  public ITerm makeSelect(ITerm array, ITerm index) {
    List<ITerm> args = ImmutableList.of(array, index);
    ExtArray.ArraySort arraySort = (ExtArray.ArraySort) Sort$.MODULE$.sortOf(array);
    return new IFunApp(arraySort.theory().select(), toSeq(args));
  }

  public ITerm makeStore(ITerm array, ITerm index, ITerm value) {
    List<ITerm> args = ImmutableList.of(array, index, value);
    ExtArray.ArraySort arraySort = (ExtArray.ArraySort) Sort$.MODULE$.sortOf(array);
    return new IFunApp(arraySort.theory().store(), toSeq(args));
  }

  public boolean hasArrayType(IExpression exp) {
    if (exp instanceof ITerm) {
      final ITerm t = (ITerm) exp;
      return Sort$.MODULE$.sortOf(t) instanceof ExtArray.ArraySort;
    } else {
      return false;
    }
  }

  public IFormula elimQuantifiers(IFormula formula) {
    return api.simplify(formula);
  }

  private void addSymbol(IFormula symbol) {
    for (PrincessAbstractProver<?, ?> prover : registeredProvers) {
      prover.addSymbol(symbol);
    }
  }

  private void addSymbol(ITerm symbol) {
    for (PrincessAbstractProver<?, ?> prover : registeredProvers) {
      prover.addSymbol(symbol);
    }
  }

  private void addFunction(IFunction funcDecl) {
    for (PrincessAbstractProver<?, ?> prover : registeredProvers) {
      prover.addSymbol(funcDecl);
    }
  }

  static <T> Seq<T> toSeq(List<T> list) {
    return collectionAsScalaIterableConverter(list).asScala().toSeq();
  }

  IExpression simplify(IExpression f) {
    // TODO this method is not tested, check it!
    if (f instanceof IFormula) {
      f = BooleanCompactifier.apply((IFormula) f);
    }
    return PartialEvaluator.apply(f);
  }
}
