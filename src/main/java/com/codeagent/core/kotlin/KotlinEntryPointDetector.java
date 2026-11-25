package com.codeagent.core.kotlin;

import com.codeagent.core.java.EntryPointDetector;
import com.codeagent.core.kotlin.KotlinProject.KotlinAnnotation;
import com.codeagent.core.kotlin.KotlinProject.KotlinFunction;
import com.codeagent.core.kotlin.KotlinProject.KotlinType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Kotlin 으로 쓴 Spring MVC 컨트롤러에서 HTTP 진입점을 찾는다 ({@link EntryPointDetector} 의 Kotlin 판).
 * {@code @RestController}·{@code @Controller} 클래스의 {@code @GetMapping("/x")}, {@code @RequestMapping(value = ["/a"], method = [RequestMethod.POST])} 등.
 */
public final class KotlinEntryPointDetector {

    public record KotlinEntryPoint(String httpMethod, String path, KotlinFunction handler) {

        public String qualifiedName() {
            return httpMethod + " " + path + " -> " + handler.key();
        }
    }

    public List<KotlinEntryPoint> detect(KotlinProject project, KotlinType type) {
        List<KotlinAnnotation> classAnnotations = project.annotationDetails(type);
        if (find(classAnnotations, "RestController", "Controller").isEmpty()) {
            return List.of();
        }
        List<String> classPaths = find(classAnnotations, "RequestMapping")
                .map(KotlinEntryPointDetector::paths)
                .orElse(List.of(""));
        List<KotlinEntryPoint> result = new ArrayList<>();
        for (KotlinFunction function : type.functions()) {
            for (KotlinAnnotation annotation : project.annotationDetails(function)) {
                String fixedMethod = EntryPointDetector.MAPPING_ANNOTATIONS.get(simpleName(annotation.name()));
                if (fixedMethod == null) {
                    continue;
                }
                List<String> httpMethods = fixedMethod.isEmpty() ? requestMethods(annotation) : List.of(fixedMethod);
                for (String classPath : classPaths) {
                    for (String methodPath : paths(annotation)) {
                        for (String httpMethod : httpMethods) {
                            result.add(new KotlinEntryPoint(httpMethod, EntryPointDetector.joinPath(classPath, methodPath), function));
                        }
                    }
                }
            }
        }
        return result;
    }

    private static Optional<KotlinAnnotation> find(List<KotlinAnnotation> annotations, String... names) {
        return annotations.stream()
                .filter(a -> List.of(names).contains(simpleName(a.name())))
                .findFirst();
    }

    private static String simpleName(String name) {
        return name.substring(name.lastIndexOf('.') + 1);
    }

    private static List<String> paths(KotlinAnnotation annotation) {
        List<String> paths = annotation.argument("value", "path");
        return paths.isEmpty() ? List.of("") : paths;
    }

    private static List<String> requestMethods(KotlinAnnotation annotation) {
        // ${RequestMethod.POST} → POST
        List<String> methods = annotation.argument("method").stream()
                .map(m -> m.replaceAll("^\\$\\{|}$", ""))
                .map(m -> m.substring(m.lastIndexOf('.') + 1))
                .toList();
        return methods.isEmpty() ? List.of("ANY") : methods;
    }
}
