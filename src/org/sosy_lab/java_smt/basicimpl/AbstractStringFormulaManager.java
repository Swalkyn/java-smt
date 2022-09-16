// This file is part of JavaSMT,
// an API wrapper for a collection of SMT solvers:
// https://github.com/sosy-lab/java-smt
//
// SPDX-FileCopyrightText: 2021 Alejandro SerranoMena
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.java_smt.basicimpl;

import static org.sosy_lab.java_smt.basicimpl.AbstractFormulaManager.checkVariableName;

import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import java.util.Collections;
import java.util.List;
import org.sosy_lab.java_smt.api.BooleanFormula;
import org.sosy_lab.java_smt.api.FormulaType;
import org.sosy_lab.java_smt.api.NumeralFormula;
import org.sosy_lab.java_smt.api.NumeralFormula.IntegerFormula;
import org.sosy_lab.java_smt.api.RegexFormula;
import org.sosy_lab.java_smt.api.StringFormula;
import org.sosy_lab.java_smt.api.StringFormulaManager;

@SuppressWarnings("ClassTypeParameterName")
public abstract class AbstractStringFormulaManager<TFormulaInfo, TType, TEnv, TFuncDecl>
    extends AbstractBaseFormulaManager<TFormulaInfo, TType, TEnv, TFuncDecl>
    implements StringFormulaManager {

  protected AbstractStringFormulaManager(
      FormulaCreator<TFormulaInfo, TType, TEnv, TFuncDecl> pCreator) {
    super(pCreator);
  }

  private StringFormula wrapString(TFormulaInfo formulaInfo) {
    return getFormulaCreator().encapsulateString(formulaInfo);
  }

  private RegexFormula wrapRegex(TFormulaInfo formulaInfo) {
    return getFormulaCreator().encapsulateRegex(formulaInfo);
  }

  @Override
  public StringFormula makeString(String value) {
    return wrapString(makeStringImpl(value));
  }

  protected abstract TFormulaInfo makeStringImpl(String value);

  @Override
  public StringFormula makeVariable(String pVar) {
    checkVariableName(pVar);
    return wrapString(makeVariableImpl(pVar));
  }

  protected abstract TFormulaInfo makeVariableImpl(String pVar);

  @Override
  public BooleanFormula equal(StringFormula str1, StringFormula str2) {
    return wrapBool(equal(extractInfo(str1), extractInfo(str2)));
  }

  protected abstract TFormulaInfo equal(TFormulaInfo pParam1, TFormulaInfo pParam2);

  @Override
  public BooleanFormula greaterThan(StringFormula str1, StringFormula str2) {
    return wrapBool(greaterThan(extractInfo(str1), extractInfo(str2)));
  }

  protected abstract TFormulaInfo greaterThan(TFormulaInfo pParam1, TFormulaInfo pParam2);

  @Override
  public BooleanFormula greaterOrEquals(StringFormula str1, StringFormula str2) {
    return wrapBool(greaterOrEquals(extractInfo(str1), extractInfo(str2)));
  }

  protected abstract TFormulaInfo greaterOrEquals(TFormulaInfo pParam1, TFormulaInfo pParam2);

  @Override
  public BooleanFormula lessThan(StringFormula str1, StringFormula str2) {
    return wrapBool(lessThan(extractInfo(str1), extractInfo(str2)));
  }

  protected abstract TFormulaInfo lessThan(TFormulaInfo pParam1, TFormulaInfo pParam2);

  @Override
  public BooleanFormula lessOrEquals(StringFormula str1, StringFormula str2) {
    return wrapBool(lessOrEquals(extractInfo(str1), extractInfo(str2)));
  }

  protected abstract TFormulaInfo lessOrEquals(TFormulaInfo pParam1, TFormulaInfo pParam2);

  @Override
  public NumeralFormula.IntegerFormula length(StringFormula str) {
    return getFormulaCreator().encapsulate(FormulaType.IntegerType, length(extractInfo(str)));
  }

  protected abstract TFormulaInfo length(TFormulaInfo pParam);

  @Override
  public StringFormula concat(List<StringFormula> parts) {
    switch (parts.size()) {
      case 0:
        return makeString(""); // empty sequence
      case 1:
        return Iterables.getOnlyElement(parts);
      default:
        return wrapString(concatImpl(Lists.transform(parts, this::extractInfo)));
    }
  }

  protected abstract TFormulaInfo concatImpl(List<TFormulaInfo> parts);

  @Override
  public BooleanFormula prefix(StringFormula prefix, StringFormula str) {
    return wrapBool(prefix(extractInfo(prefix), extractInfo(str)));
  }

  protected abstract TFormulaInfo prefix(TFormulaInfo prefix, TFormulaInfo str);

  @Override
  public BooleanFormula suffix(StringFormula suffix, StringFormula str) {
    return wrapBool(suffix(extractInfo(suffix), extractInfo(str)));
  }

  protected abstract TFormulaInfo suffix(TFormulaInfo suffix, TFormulaInfo str);

  @Override
  public BooleanFormula in(StringFormula str, RegexFormula regex) {
    return wrapBool(in(extractInfo(str), extractInfo(regex)));
  }

  protected abstract TFormulaInfo in(TFormulaInfo str, TFormulaInfo regex);

  @Override
  public BooleanFormula contains(StringFormula str, StringFormula part) {
    return wrapBool(contains(extractInfo(str), extractInfo(part)));
  }

  protected abstract TFormulaInfo contains(TFormulaInfo str, TFormulaInfo part);

  @Override
  public IntegerFormula indexOf(StringFormula str, StringFormula part, IntegerFormula startIndex) {
    return getFormulaCreator()
        .encapsulate(
            FormulaType.IntegerType,
            indexOf(extractInfo(str), extractInfo(part), extractInfo(startIndex)));
  }

  protected abstract TFormulaInfo indexOf(
      TFormulaInfo str, TFormulaInfo part, TFormulaInfo startIndex);

  @Override
  public StringFormula charAt(StringFormula str, IntegerFormula index) {
    return wrapString(charAt(extractInfo(str), extractInfo(index)));
  }

  protected abstract TFormulaInfo charAt(TFormulaInfo str, TFormulaInfo index);

  @Override
  public StringFormula substring(StringFormula str, IntegerFormula index, IntegerFormula length) {
    return wrapString(substring(extractInfo(str), extractInfo(index), extractInfo(length)));
  }

  protected abstract TFormulaInfo substring(
      TFormulaInfo str, TFormulaInfo index, TFormulaInfo length);

  @Override
  public StringFormula replace(
      StringFormula fullStr, StringFormula target, StringFormula replacement) {
    return wrapString(replace(extractInfo(fullStr), extractInfo(target), extractInfo(replacement)));
  }

  protected abstract TFormulaInfo replace(
      TFormulaInfo fullStr, TFormulaInfo target, TFormulaInfo replacement);

  @Override
  public StringFormula replaceAll(
      StringFormula fullStr, StringFormula target, StringFormula replacement) {
    return wrapString(
        replaceAll(extractInfo(fullStr), extractInfo(target), extractInfo(replacement)));
  }

  protected abstract TFormulaInfo replaceAll(
      TFormulaInfo fullStr, TFormulaInfo target, TFormulaInfo replacement);

  @Override
  public RegexFormula makeRegex(String value) {
    return wrapRegex(makeRegexImpl(value));
  }

  protected abstract TFormulaInfo makeRegexImpl(String value);

  @Override
  public RegexFormula none() {
    return wrapRegex(noneImpl());
  }

  protected abstract TFormulaInfo noneImpl();

  @Override
  public RegexFormula all() {
    return wrapRegex(allImpl());
  }

  protected abstract TFormulaInfo allImpl();

  @Override
  public RegexFormula allChar() {
    return wrapRegex(allCharImpl());
  }

  protected abstract TFormulaInfo allCharImpl();

  @Override
  public RegexFormula range(StringFormula start, StringFormula end) {
    return wrapRegex(range(extractInfo(start), extractInfo(end)));
  }

  protected abstract TFormulaInfo range(TFormulaInfo start, TFormulaInfo end);

  @Override
  public RegexFormula concatRegex(List<RegexFormula> parts) {
    switch (parts.size()) {
      case 0:
        return none(); // empty sequence
      case 1:
        return Iterables.getOnlyElement(parts);
      default:
        return wrapRegex(concatRegexImpl(Lists.transform(parts, this::extractInfo)));
    }
  }

  protected abstract TFormulaInfo concatRegexImpl(List<TFormulaInfo> parts);

  @Override
  public RegexFormula union(RegexFormula regex1, RegexFormula regex2) {
    return wrapRegex(union(extractInfo(regex1), extractInfo(regex2)));
  }

  protected abstract TFormulaInfo union(TFormulaInfo pParam1, TFormulaInfo pParam2);

  @Override
  public RegexFormula intersection(RegexFormula regex1, RegexFormula regex2) {
    return wrapRegex(union(extractInfo(regex1), extractInfo(regex2)));
  }

  protected abstract TFormulaInfo intersection(TFormulaInfo pParam1, TFormulaInfo pParam2);

  @Override
  public RegexFormula closure(RegexFormula regex) {
    return wrapRegex(closure(extractInfo(regex)));
  }

  protected abstract TFormulaInfo closure(TFormulaInfo pParam);

  @Override
  public RegexFormula complement(RegexFormula regex) {
    return wrapRegex(complement(extractInfo(regex)));
  }

  protected abstract TFormulaInfo complement(TFormulaInfo pParam);

  @Override
  public RegexFormula difference(RegexFormula regex1, RegexFormula regex2) {
    return wrapRegex(difference(extractInfo(regex1), extractInfo(regex2)));
  }

  protected TFormulaInfo difference(TFormulaInfo pParam1, TFormulaInfo pParam2) {
    return union(pParam1, complement(pParam2));
  }

  @Override
  public RegexFormula cross(RegexFormula regex) {
    return concat(regex, closure(regex));
  }

  @Override
  public RegexFormula optional(RegexFormula regex) {
    return union(regex, makeRegex(""));
  }

  @Override
  public RegexFormula times(RegexFormula regex, int repetitions) {
    return concatRegex(Collections.nCopies(repetitions, regex));
  }

  @Override
  public IntegerFormula toIntegerFormula(StringFormula str) {
    return getFormulaCreator()
        .encapsulate(FormulaType.IntegerType, toIntegerFormula(extractInfo(str)));
  }

  protected abstract TFormulaInfo toIntegerFormula(TFormulaInfo pParam);

  @Override
  public StringFormula toStringFormula(IntegerFormula number) {
    return wrapString(toStringFormula(extractInfo(number)));
  }

  protected abstract TFormulaInfo toStringFormula(TFormulaInfo pParam);
}
