package com.codeagent.core.interop;

import com.codeagent.core.java.JavaGraphSupport;
import com.codeagent.core.java.JavaProject;
import com.codeagent.core.java.JavaProject.MethodInfo;
import com.codeagent.core.java.JavaProject.TypeInfo;
import com.codeagent.core.kotlin.KotlinGraphSupport;
import com.codeagent.core.kotlin.KotlinProject;
import com.codeagent.core.kotlin.KotlinProject.KotlinFunction;
import com.codeagent.core.kotlin.KotlinProject.KotlinType;

import java.util.List;
import java.util.Optional;

/**
 * Java 와 Kotlin 사이의 호출을 잇는다. 둘 다 JVM 바이트코드로 같은 이름을 쓰므로 Swift·Objective-C 보다 단순하다.
 * <ul>
 *   <li>Java → Kotlin: 함수 이름 그대로 (store.save(x) → ProfileStore#save), 파일 최상위 함수는 FileNameKt.top(x),
 *       object·companion 은 Foo.INSTANCE.bar() / Foo.Companion.make() / @JvmStatic Foo.make(),
 *       프로퍼티는 getter (getName() → name 의 타입)</li>
 *   <li>Kotlin → Java: 메서드 이름 그대로, 생성자 Foo(x) → Foo#&lt;init&gt;, getter 는 프로퍼티처럼 (user.name → getName())</li>
 * </ul>
 * 같은 패키지에서 import 없이 쓴 이름도 풀리도록 서로의 타입 이름을 알려 준다.
 * &#64;JvmName 으로 바꾼 이름, 기본값 인자(@JvmOverloads), 확장 함수의 Java 호출(FileKt.ext(receiver, …))은 따르지 않는다.
 */
public final class JavaKotlinInterop {

    private final JavaProject java;
    private final KotlinProject kotlin;

    public JavaKotlinInterop(JavaProject java, KotlinProject kotlin) {
        this.java = java;
        this.kotlin = kotlin;
        java.addExternalTypes(kotlin.types().stream().map(KotlinType::fqn).toList());
        kotlin.addExternalTypes(java.types().stream().map(TypeInfo::fqn).toList());
    }

    /** 두 그래프 부품에 서로를 부르는 길을 단다. 호출을 잇기(addCalls) 전에 부른다 */
    public void connect(JavaGraphSupport javaSupport, KotlinGraphSupport kotlinSupport) {
        javaSupport.foreignCalls(new JavaGraphSupport.ForeignCalls() {
            @Override
            public List<String> find(String type, String name, int argCount) {
                return kotlinFunctions(type, name, argCount);
            }

            @Override
            public Optional<String> returnType(String type, String name, int argCount) {
                return kotlinGetterType(type, name, argCount);
            }
        });
        kotlinSupport.foreignCalls(new KotlinGraphSupport.ForeignCalls() {
            @Override
            public List<String> find(String type, String name, int argCount) {
                return javaMethods(type, name, argCount);
            }

            @Override
            public Optional<String> returnType(String type, String name, int argCount) {
                return javaReturnType(type, name, argCount);
            }
        });
    }

    /** Java 에서 부른 Kotlin 함수의 그래프 키 */
    public List<String> kotlinFunctions(String type, String name, int argCount) {
        if (!kotlin.isProjectType(type)) {
            return List.of();
        }
        return kotlin.findFunctions(type, name, argCount).stream().map(KotlinGraphSupport::methodKey).toList();
    }

    /** Kotlin 에서 부른 Java 메서드·생성자의 그래프 키 */
    public List<String> javaMethods(String type, String name, int argCount) {
        if (!java.isProjectType(type)) {
            return List.of();
        }
        return java.findMethods(type, name, argCount).stream().map(JavaGraphSupport::methodKey).toList();
    }

    /** Java 에서 getName() 으로 읽은 Kotlin 프로퍼티의 타입 */
    private Optional<String> kotlinGetterType(String type, String name, int argCount) {
        Optional<KotlinType> owner = kotlin.type(type);
        if (owner.isEmpty() || argCount != 0) {
            return Optional.empty();
        }
        String property = name.startsWith("get") && name.length() > 3 ? Character.toLowerCase(name.charAt(3)) + name.substring(4)
                : name.startsWith("is") ? name : null;
        return property == null ? Optional.empty() : Optional.ofNullable(kotlin.propertyType(owner.get(), property));
    }

    /** Kotlin 에서 부른 Java 메서드의 반환 타입 */
    private Optional<String> javaReturnType(String type, String name, int argCount) {
        if (!java.isProjectType(type)) {
            return Optional.empty();
        }
        return java.findMethods(type, name, argCount).stream()
                .map(java::returnType)
                .filter(t -> t != null)
                .findFirst();
    }
}
