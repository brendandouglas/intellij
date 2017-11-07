/*
 * Copyright 2017 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.idea.blaze.python.resolve.provider;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.idea.blaze.base.ideinfo.ArtifactLocation;
import com.google.idea.blaze.base.ideinfo.TargetIdeInfo;
import com.google.idea.blaze.base.model.BlazeProjectData;
import com.google.idea.blaze.base.model.primitives.LanguageClass;
import com.google.idea.blaze.base.sync.SyncCache;
import com.google.idea.blaze.base.sync.workspace.ArtifactLocationDecoder;
import com.google.idea.blaze.python.resolve.BlazePyResolverUtils;
import com.google.idea.sdkcompat.python.PyQualifiedNameResolveContextAdapter;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiReference;
import com.intellij.psi.util.QualifiedName;
import com.jetbrains.python.PyNames;
import com.jetbrains.python.codeInsight.imports.AutoImportQuickFix;
import java.io.File;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * An abstract base class for {@link PyImportResolverStrategy}, for the most common case where
 * import strings are resolved to python packages and modules.
 */
public abstract class AbstractPyImportResolverStrategy implements PyImportResolverStrategy {

  @Nullable
  @Override
  public final PsiElement resolveFromSyncData(
      QualifiedName name, PyQualifiedNameResolveContextAdapter context) {
    PySourcesIndex index = getSourcesIndex(context.getProject());
    if (index == null) {
      return null;
    }
    PsiElementProvider resolver = index.sourceMap.get(name);
    return resolver != null ? resolver.get(context.getPsiManager()) : null;
  }

  @Override
  public final void addImportCandidates(
      PsiReference reference, String name, AutoImportQuickFix quickFix) {
    Project project = reference.getElement().getProject();
    PySourcesIndex index = getSourcesIndex(project);
    if (index == null) {
      return;
    }
    PsiManager psiManager = PsiManager.getInstance(project);
    for (QualifiedName candidate : index.shortNames.get(name)) {
      PsiElementProvider resolver = index.sourceMap.get(candidate);
      if (resolver == null) {
        continue;
      }
      PsiElement psi = resolver.get(psiManager);
      if (psi == null) {
        continue;
      }
      PsiFile file = psi.getContainingFile();
      if (file != null) {
        quickFix.addImport(psi, file, candidate.removeLastComponent());
      }
    }
  }

  @Nullable
  private PySourcesIndex getSourcesIndex(Project project) {
    return SyncCache.getInstance(project).get(getClass(), this::buildSourcesIndex);
  }

  @SuppressWarnings("unused")
  private PySourcesIndex buildSourcesIndex(Project project, BlazeProjectData projectData) {
    ImmutableSetMultimap.Builder<String, QualifiedName> shortNames = ImmutableSetMultimap.builder();
    Map<QualifiedName, PsiElementProvider> map = new HashMap<>();
    ArtifactLocationDecoder decoder = projectData.artifactLocationDecoder;
    for (TargetIdeInfo target : projectData.targetMap.targets()) {
      for (ArtifactLocation source : getPySources(target)) {
        QualifiedName name = toImportString(source);
        if (name == null || name.getLastComponent() == null) {
          continue;
        }
        shortNames.put(name.getLastComponent(), name);
        PsiElementProvider psiProvider = psiProviderFromArtifact(decoder, source);
        map.put(name, psiProvider);
        if (includeParentDirectory(source)) {
          map.put(name.removeTail(1), PsiElementProvider.getParent(psiProvider));
        }
      }
    }
    return new PySourcesIndex(shortNames.build(), ImmutableMap.copyOf(map));
  }

  private static PsiElementProvider psiProviderFromArtifact(
      ArtifactLocationDecoder decoder, ArtifactLocation source) {
    return (manager) -> {
      File file = decoder.decode(source);
      if (PyNames.INIT_DOT_PY.equals(file.getName())) {
        file = file.getParentFile();
      }
      return BlazePyResolverUtils.resolveFile(manager, file);
    };
  }

  private static Collection<ArtifactLocation> getPySources(TargetIdeInfo target) {
    if (target.pyIdeInfo != null) {
      return target.pyIdeInfo.sources;
    }
    if (target.kind.languageClass == LanguageClass.PYTHON) {
      return target.sources;
    }
    return ImmutableList.of();
  }

  /** Maps a blaze artifact to the import string used to reference it. */
  @Nullable
  abstract QualifiedName toImportString(ArtifactLocation source);

  private static boolean includeParentDirectory(ArtifactLocation source) {
    return source.relativePath.endsWith(".py");
  }

  static QualifiedName fromRelativePath(String relativePath) {
    relativePath = StringUtil.trimEnd(relativePath, File.separator + PyNames.INIT_DOT_PY);
    relativePath = StringUtil.trimExtensions(relativePath);
    return QualifiedName.fromComponents(StringUtil.split(relativePath, File.separator));
  }
}