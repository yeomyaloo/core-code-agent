package com.codeagent.core.interop;

import com.codeagent.core.objc.ObjcGraphSupport;
import com.codeagent.core.objc.ObjcProject;
import com.codeagent.core.objc.ObjcProject.ObjcMethod;
import com.codeagent.core.objc.ObjcProject.ObjcType;
import com.codeagent.core.swift.SwiftGraphSupport;
import com.codeagent.core.swift.SwiftProject;
import com.codeagent.core.swift.SwiftProject.SwiftFunction;
import com.codeagent.core.swift.SwiftProject.SwiftType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Swift 와 Objective-C 사이의 호출을 잇는다. 한 앱 안에서 두 언어는 브리징 헤더·생성 헤더로 서로의 클래스를 그대로 쓴다.
 * 이름은 컴파일러의 변환 규칙을 흉내 낸다:
 * <ul>
 *   <li>Objective-C → Swift 에서 보이는 이름: deletePayee: → deletePayee(_:), setObject:forKey: → setObject(_:forKey:),
 *       initWithName: → init(name:), fetchWithURL: → fetch(url:)</li>
 *   <li>Swift → Objective-C 에서 보이는 셀렉터: remember(_:) → remember:, deletePayee(named:) → deletePayeeWithNamed:,
 *       init(url:) → initWithUrl:, save() → save</li>
 * </ul>
 * 명시한 @objc(이름) 과 NS_SWIFT_NAME, 필요 없는 단어 빼기(omit needless words)는 따르지 않는다.
 */
public final class SwiftObjcInterop {

    /** Swift 에서 보이는 이름: 함수 이름과 인자 레이블 (레이블 없음은 _) */
    public record SwiftName(String name, List<String> labels) {
    }

    private final SwiftProject swift;
    private final ObjcProject objc;

    public SwiftObjcInterop(SwiftProject swift, ObjcProject objc) {
        this.swift = swift;
        this.objc = objc;
    }

    /** 두 그래프 부품에 서로를 부르는 길을 단다 */
    public void connect(SwiftGraphSupport swiftSupport, ObjcGraphSupport objcSupport) {
        swiftSupport.foreignCalls(new SwiftGraphSupport.ForeignCalls() {
            @Override
            public Optional<String> find(String type, String name, List<String> labels) {
                return objcMethod(type, name, labels).map(ObjcGraphSupport::methodKey);
            }

            @Override
            public Optional<String> returnType(String type, String name, List<String> labels) {
                return objcMethod(type, name, labels).map(ObjcMethod::returnType).filter(t -> !t.equals("id"));
            }
        });
        objcSupport.foreignCalls(this::objcMessageToSwift);
    }

    /** Swift 에서 type.name(labels...) 로 부른 Objective-C 메서드의 그래프 키 */
    public Optional<String> swiftCallToObjc(String type, String name, List<String> labels) {
        return objcMethod(type, name, labels).map(ObjcGraphSupport::methodKey);
    }

    private Optional<ObjcMethod> objcMethod(String type, String name, List<String> labels) {
        Optional<ObjcType> found = objc.type(type);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        List<ObjcType> candidates = new ArrayList<>();
        candidates.add(found.get());
        candidates.addAll(objc.ancestors(found.get()));
        for (ObjcType candidate : candidates) {
            for (ObjcMethod method : candidate.methods()) {
                SwiftName seen = swiftName(method.selector());
                if (seen.name().equals(name) && seen.labels().equals(labels)) {
                    return Optional.of(method);
                }
            }
        }
        return Optional.empty();
    }

    /** Objective-C 에서 [type selector] 로 보낸 메시지를 받는 Swift 함수의 그래프 키 */
    public Optional<String> objcMessageToSwift(String type, String selector) {
        Optional<SwiftType> found = swift.type(type);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        List<SwiftType> candidates = new ArrayList<>();
        candidates.add(found.get());
        candidates.addAll(swift.ancestors(found.get()));
        for (SwiftType candidate : candidates) {
            for (SwiftFunction function : candidate.functions()) {
                if (objcSelector(function).equalsIgnoreCase(selector)) {
                    return Optional.of(SwiftGraphSupport.methodKey(function));
                }
            }
        }
        return Optional.empty();
    }

    /** Objective-C 셀렉터가 Swift 에서 보이는 이름 */
    public static SwiftName swiftName(String selector) {
        if (!selector.contains(":")) {
            return new SwiftName(selector, List.of());
        }
        List<String> pieces = Arrays.stream(selector.split(":", -1)).filter(p -> !p.isEmpty()).toList();
        if (pieces.isEmpty()) {
            return new SwiftName(selector, List.of());
        }
        String first = pieces.get(0);
        String name = first;
        String firstLabel = "_";
        int with = first.indexOf("With");
        if (with > 0 && first.length() > with + 4 && Character.isUpperCase(first.charAt(with + 4))) {
            name = first.substring(0, with);
            firstLabel = lowerFirst(first.substring(with + 4));
        }
        List<String> labels = new ArrayList<>();
        labels.add(firstLabel);
        labels.addAll(pieces.subList(1, pieces.size()));
        return new SwiftName(name, List.copyOf(labels));
    }

    /** Swift 함수가 Objective-C 에서 보이는 셀렉터 */
    public static String objcSelector(SwiftFunction function) {
        List<String> labels = labels(function.key());
        if (labels.isEmpty()) {
            return function.name();
        }
        StringBuilder sb = new StringBuilder(function.name());
        String first = labels.get(0);
        if (!first.equals("_")) {
            sb.append("With").append(Character.toUpperCase(first.charAt(0))).append(first.substring(1));
        }
        sb.append(':');
        for (String label : labels.subList(1, labels.size())) {
            sb.append(label.equals("_") ? "" : label).append(':');
        }
        return sb.toString();
    }

    /** Type#name(a:b:) 의 [a, b] */
    static List<String> labels(String key) {
        int open = key.indexOf('(');
        int close = key.lastIndexOf(')');
        if (open < 0 || close <= open + 1) {
            return List.of();
        }
        return Arrays.stream(key.substring(open + 1, close).split(":")).filter(s -> !s.isEmpty()).toList();
    }

    /** URLString → urlString, Name → name (앞의 대문자 묶음을 소문자로) */
    static String lowerFirst(String text) {
        int upper = 0;
        while (upper < text.length() && Character.isUpperCase(text.charAt(upper))) {
            upper++;
        }
        if (upper == text.length()) {
            return text.toLowerCase(Locale.ROOT);
        }
        int cut = upper > 1 ? upper - 1 : 1;
        return text.substring(0, cut).toLowerCase(Locale.ROOT) + text.substring(cut);
    }
}
