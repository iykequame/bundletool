/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */
package com.android.tools.build.bundletool.utils;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.MoreCollectors.onlyElement;

import com.android.tools.build.bundletool.exceptions.CommandExecutionException;
import com.google.common.collect.ImmutableMap;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.stream.Stream;

/** Helper to locate various tools in the SDK dir. */
public final class SdkToolsLocator {

  private static final String ADB_GLOB = "glob:**/platform-tools/{adb,adb.exe}";
  private static final BiPredicate<Path, BasicFileAttributes> AAPT2_MATCHER =
      (file, attrs) -> file.getFileName().toString().matches("aapt2(\\.exe)?");

  private final PathMatcher adbPathMatcher;

  public SdkToolsLocator() {
    this(FileSystems.getDefault());
  }

  SdkToolsLocator(FileSystem fileSystem) {
    this.adbPathMatcher = fileSystem.getPathMatcher(ADB_GLOB);
  }

  /** Tries to extract aapt2 from the executable if found. */
  public Optional<Path> extractAapt2(Path tempDir) {
    String osDir = getOsSpecificJarDirectory();

    // Attempt at locating the directory in question inside the jar.
    URL osDirUrl = SdkToolsLocator.class.getResource(osDir);

    // If it's not found or we're not in a jar, fail.
    if (osDirUrl == null || !"jar".equals(osDirUrl.getProtocol())) {
      return Optional.empty();
    }

    Path aapt2;
    try {
      Path outputDir = tempDir.resolve("output");
      extractFilesFromJar(outputDir, osDirUrl, osDir);
      try (Stream<Path> aapt2Binaries = Files.find(outputDir, /* maxDepth= */ 3, AAPT2_MATCHER)) {
        aapt2 = aapt2Binaries.collect(onlyElement());
      }
    } catch (NoSuchElementException e) {
      throw new CommandExecutionException("Unable to locate aapt2 inside jar.", e);
    } catch (IOException | URISyntaxException e) {
      throw new CommandExecutionException("Unable to extract aapt2 from jar.", e);
    }

    // Sanity check.
    checkState(Files.exists(aapt2));

    // Ensure aapt2 is executable.
    try {
      aapt2.toFile().setExecutable(true);
    } catch (SecurityException e) {
      throw new CommandExecutionException(
          "Unable to make aapt2 executable. This may be a permission issue. If it persists, "
              + "consider passing the path to aapt2 using the flag --aapt2.",
          e);
    }

    return Optional.of(aapt2);
  }

  private void extractFilesFromJar(Path outputDir, URL directoryUrl, String startDir)
      throws IOException, URISyntaxException {
    // aapt2 is not statically built, so some other libraries are also included, sometimes in
    // subdirectories, hence we look down 3 directories down just in case to extract everything.
    try (FileSystem fs = FileSystems.newFileSystem(directoryUrl.toURI(), ImmutableMap.of());
        Stream<Path> paths = Files.walk(fs.getPath(startDir))) {
      for (Path path : paths.collect(toImmutableList())) {
        String pathStr = path.toString();
        try (InputStream is = sanitize(getClass().getResourceAsStream(pathStr))) {
          if (is.available() == 0) {
            // A directory.
            continue;
          }

          // Remove leading slash from the path because:
          //    Path("/tmp/dir/").resolve("/hello")
          // returns
          //    Path("/hello")
          Path target = outputDir.resolve(pathStr.replaceFirst("^/", ""));
          // Ensure all parent directories exist.
          Files.createDirectories(target.getParent());
          // Extract the file on disk.
          Files.copy(is, target);
        }
      }
    }
  }

  /** Returns the name of the OS-specific directory inside bundletool executable jar. */
  private static String getOsSpecificJarDirectory() {
    switch (OsPlatform.getCurrentPlatform()) {
      case WINDOWS:
        return "/windows";
      case MACOS:
        return "/macos";
      case LINUX:
        return "/linux";
      case OTHER:
        // Unrecognized OS; let's try Linux.
        return "/linux";
    }
    throw new IllegalStateException();
  }

  /** Hack to work around https://bugs.openjdk.java.net/browse/JDK-8144977 */
  private static InputStream sanitize(InputStream is) throws IOException {
    try {
      is.available();
      return is;
    } catch (NullPointerException e) {
      // The InputStream has an underlying null stream, which is a bug fixed in JDK9 only.
      // We return a safe empty InputStream, which can be read and closed safely.
      return new ByteArrayInputStream(new byte[0]);
    }
  }

  /** Tries to locate adb utility under "platform-tools". */
  public Optional<Path> locateAdb(Path sdkDir) {
    Path platformToolsDir = sdkDir.resolve("platform-tools");
    if (!Files.isDirectory(platformToolsDir)) {
      return Optional.empty();
    }

    // Expecting to find one entry.
    try (Stream<Path> pathStream =
        Files.find(
            platformToolsDir,
            /* maxDepth= */ 1,
            (path, attributes) -> adbPathMatcher.matches(path) && Files.isExecutable(path))) {
      return pathStream.findFirst();
    } catch (IOException e) {
      throw CommandExecutionException.builder()
          .withCause(e)
          .withMessage("Error while trying to locate adb in SDK dir '%s'.", sdkDir)
          .build();
    }
  }
}
