/*
 * Copyright 2018 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.idea.blaze.golang.run;

import com.goide.dlv.location.DlvPositionConverter;
import com.goide.dlv.location.DlvPositionConverterFactory;
import com.goide.sdk.GoSdkService;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.File;
import com.google.idea.blaze.base.io.VfsUtils;
import com.google.idea.blaze.base.model.primitives.ExecutionRootPath;
import com.google.idea.blaze.base.model.primitives.WorkspaceRoot;
import com.google.idea.blaze.base.sync.workspace.ExecutionRootPathResolver;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import org.yaml.snakeyaml.Yaml;

class BlazeDlvPositionConverter implements DlvPositionConverter {
  private static final Logger logger = Logger.getInstance(BlazeDlvPositionConverter.class);

  private final WorkspaceRoot root;
  private final String goRoot;
  private final ExecutionRootPathResolver resolver;
  private final Map<VirtualFile, String> localToRemote;
  private final Map<String, VirtualFile> normalizedToLocal;

  private List<Map<String, String>> substitutePaths;

  private BlazeDlvPositionConverter(
      WorkspaceRoot workspaceRoot,
      String goRoot,
      ExecutionRootPathResolver resolver,
      Set<String> remotePaths) {
    this.root = workspaceRoot;
    this.goRoot = goRoot;
    this.resolver = resolver;
    this.localToRemote = Maps.newHashMapWithExpectedSize(remotePaths.size());
    this.normalizedToLocal = Maps.newHashMapWithExpectedSize(remotePaths.size());

    String filePath = "/Users/rogerhu/.dlv/config.yml";
    Yaml yaml = new Yaml();
    try {
      Map<String, List<Map<String, String>>> yamlData = yaml.load(new FileInputStream(filePath));
      substitutePaths = yamlData.get("substitute-path");
    } catch (FileNotFoundException e) {
      substitutePaths = null;
    }

    for (String path : remotePaths) {
      String normalized = normalizePath(path);
      if (normalizedToLocal.containsKey(normalized)) {
        continue;
      }
      VirtualFile localFile = resolve(normalized);
      if (localFile != null) {
        if (remotePaths.contains(normalized)) {
          localToRemote.put(localFile, normalized);
        } else {
          localToRemote.put(localFile, path);
        }
        normalizedToLocal.put(normalized, localFile);
      } else {
        logger.warn("Unable to find local file for debug path: " + path);
      }
    }
  }

  @Nullable
  @Override
  public String toRemotePath(VirtualFile localFile) {
    String remotePath = localToRemote.get(localFile);
    if (remotePath != null) {
      return remotePath;
    }
    remotePath =
        root.isInWorkspace(localFile)
            ? root.workspacePathFor(localFile).relativePath()
            : localFile.getPath();
    localToRemote.put(localFile, remotePath);
    return remotePath;
  }

  @Nullable
  @Override
  public VirtualFile toLocalFile(String remotePath) {
    String normalized = normalizePath(remotePath);
    VirtualFile localFile = normalizedToLocal.get(normalized);
    if (localFile == null || !localFile.isValid()) {
      localFile = resolve(normalized);
      if (localFile != null) {
        normalizedToLocal.put(normalized, localFile);
      }
    }
    return localFile;
  }

  private boolean isAbsolute(String path) {
    // Unix-like absolute path
    if(path.startsWith("/")) {
      return true;
    }
    return false; // todo - add windows support
  }

  private static boolean hasPathSeparatorSuffix(String path) {
    return path.endsWith("/") || path.endsWith("\\");
  }

  private static boolean hasPathSeparatorPrefix(String path) {
    return path.startsWith("/") || path.startsWith("\\");
  }

  public static char pickSeparator(String to) {
    char sep = 0;
    for (int i = 0; i < to.length(); i++) {
      char ch = to.charAt(i);
      if (ch == '/' || ch == '\\') {
        if (sep == 0) {
          sep = ch;
        } else if (sep != ch) {
          return 0; // Return null character to indicate mixed separators
        }
      }
    }
    return sep;
  }

  public static String joinPath(String to, String rest) {
    char sep = pickSeparator(to);

    switch (sep) {
      case '/':
        rest = rest.replace("\\", "/");
        break;
      case '\\':
        rest = rest.replace("/", "\\");
        break;
      default:
        sep = '/';
        break;
    }

    boolean toEndsWithSlash = hasPathSeparatorSuffix(to);
    boolean restStartsWithSlash = hasPathSeparatorPrefix(rest);

    if (toEndsWithSlash && restStartsWithSlash) {
      return to.substring(0, to.length() - 1) + rest;
    } else if (toEndsWithSlash && !restStartsWithSlash) {
      return to + rest;
    } else if (!toEndsWithSlash && restStartsWithSlash) {
      return to + rest;
    } else {
      return to + sep + rest;
    }
  }

  // https://github.com/go-delve/delve/blob/bba7547156f271842da912f2c213285e8fab0169/pkg/locspec/locations.go#L554
  private String substitutePath(String normalizedPath) {
    if (substitutePaths != null) {
      // See https://github.com/go-delve/delve/blob/master/Documentation/cli/substitutepath.md#how-are-path-substitution-rules-applied
      boolean match = false;
      for (Map<String, String> substitutePath : substitutePaths) {
        String rest = "";

        String from = substitutePath.get("from");
        String to = substitutePath.get("to");
        if (normalizedPath.equals(from)) {
          return to;
        }

        // wildcard
        if (from != null && from.isBlank()) {
          match = !isAbsolute(normalizedPath);
          rest = normalizedPath;
        } else {
          // todo - deal with case sensitivity for windows
          match = normalizedPath.startsWith(from);

          if (match) {
            rest = normalizedPath.substring(from.length());
            match = hasPathSeparatorSuffix(from) || hasPathSeparatorPrefix(rest);
          }
        }

        if (match) {
          //TODO - worry about wildcard blanks later
          return joinPath(to, rest);
        }
      }
    }
    return normalizedPath;
  }

  @Nullable
  private VirtualFile resolve(String normalizedPath) {
    normalizedPath = substitutePath(normalizedPath);
    return VfsUtils.resolveVirtualFile(
        resolver.resolveExecutionRootPath(new ExecutionRootPath(normalizedPath)),
        /* refreshIfNeeded= */ false);
  }

  private String normalizePath(String path) {
    if (path.startsWith("/build/work/")) {
      // /build/work/<hash>/<project>/actual/path
      return afterNthSlash(path, 5);
    } else if (path.startsWith("/tmp/go-build-release/buildroot/")) {
      return afterNthSlash(path, 4);
    } else if (path.startsWith("GOROOT/")) {
      return goRoot + '/' + afterNthSlash(path, 1);
    }
    return path;
  }

  /**
   * @return path substring after nth slash, if path contains at least n slashes, return path
   *     unchanged otherwise.
   */
  private static String afterNthSlash(String path, int n) {
    int index = 0;
    for (int i = 0; i < n; ++i) {
      index = path.indexOf('/', index) + 1;
      if (index == 0) { // -1 + 1
        return path;
      }
    }
    return path.substring(index);
  }

  static class Factory implements DlvPositionConverterFactory {
    @Nullable
    @Override
    public DlvPositionConverter createPositionConverter(
        Project project, @Nullable Module module, Set<String> remotePaths) {
      WorkspaceRoot workspaceRoot = WorkspaceRoot.fromProjectSafe(project);
      String goRoot = GoSdkService.getInstance(project).getSdk(module).getHomePath();
      ExecutionRootPathResolver resolver = ExecutionRootPathResolver.fromProject(project);
      return (workspaceRoot != null && resolver != null)
          ? new BlazeDlvPositionConverter(workspaceRoot, goRoot, resolver, remotePaths)
          : null;
    }
  }
}