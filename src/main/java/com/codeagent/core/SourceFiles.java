package com.codeagent.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * 저장소에서 분석할 소스 파일을 찾는다. 심볼릭 링크와 빌드·가상환경 폴더는 건너뛴다.
 */
public final class SourceFiles {

    private static final int MAX_DEPTH = 20;

    private SourceFiles() {
    }

    /** 조건에 맞는 파일 목록 (저장소 루트 기준 상대 경로 순으로 정렬) */
    public static List<Path> find(Path root, Set<String> skipDirs, long maxBytes, Predicate<Path> fileFilter) {
        try (Stream<Path> walk = Files.walk(root, MAX_DEPTH)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> !Files.isSymbolicLink(p))
                    .filter(p -> !inSkippedDir(root.relativize(p), skipDirs))
                    .filter(fileFilter)
                    .filter(p -> p.toFile().length() <= maxBytes)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("소스 파일을 찾지 못함: " + e.getMessage(), e);
        }
    }

    /** 조건에 맞는 파일이 하나라도 있는지 */
    public static boolean any(Path root, Set<String> skipDirs, Predicate<Path> fileFilter) {
        try (Stream<Path> walk = Files.walk(root, MAX_DEPTH)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> !inSkippedDir(root.relativize(p), skipDirs))
                    .anyMatch(fileFilter);
        } catch (IOException e) {
            return false;
        }
    }

    /** 저장소 루트 기준, 슬래시로 구분한 경로 */
    public static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private static boolean inSkippedDir(Path relative, Set<String> skipDirs) {
        for (int i = 0; i < relative.getNameCount() - 1; i++) {
            if (skipDirs.contains(relative.getName(i).toString())) {
                return true;
            }
        }
        return false;
    }
}
