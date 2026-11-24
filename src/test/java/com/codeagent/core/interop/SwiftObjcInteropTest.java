package com.codeagent.core.interop;

import com.codeagent.core.interop.SwiftObjcInterop.SwiftName;
import com.codeagent.core.model.CodeEdgeKind;
import com.codeagent.core.model.CodeGraphDraft;
import com.codeagent.core.model.CodeGraphDraft.EdgeDraft;
import com.codeagent.core.model.CodeNodeKind;
import com.codeagent.core.objc.ObjcGraphSupport;
import com.codeagent.core.objc.ObjcProject;
import com.codeagent.core.rules.SinkRule;
import com.codeagent.core.swift.SwiftGraphSupport;
import com.codeagent.core.swift.SwiftProject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SwiftObjcInteropTest {

    @TempDir
    static Path root;

    private static SwiftProject swift;
    private static CodeGraphDraft draft;

    @BeforeAll
    static void build() throws IOException {
        write("App/AppDelegate.swift", """
                import UIKit

                class AppDelegate: UIResponder, UIApplicationDelegate {
                    func application(_ app: UIApplication, open url: URL, options: [UIApplication.OpenURLOptionsKey: Any] = [:]) -> Bool {
                        let db = PayeeDatabase(name: "payees")
                        db.deletePayee(url.host ?? "")
                        LegacyStore.shared().save("x", forKey: "k")
                        return true
                    }
                }

                @objc class TokenVault: NSObject {
                    @objc func store(token value: String) {}
                    @objc func wipe() {}
                    @objc func remember(_ value: String) {}
                }
                """);
        write("App/PayeeDatabase.h", """
                #import <Foundation/Foundation.h>
                @interface PayeeDatabase : NSObject
                - (instancetype)initWithName:(NSString *)name;
                - (void)deletePayee:(NSString *)name;
                @end

                @interface LegacyStore : NSObject
                + (instancetype)shared;
                - (void)save:(NSString *)value forKey:(NSString *)key;
                @end
                """);
        write("App/PayeeDatabase.m", """
                #import "PayeeDatabase.h"
                #import "App-Swift.h"

                @implementation PayeeDatabase
                - (instancetype)initWithName:(NSString *)name { return [super init]; }
                - (void)deletePayee:(NSString *)name {
                    TokenVault *vault = [[TokenVault alloc] init];
                    [vault storeWithToken:name];
                    [vault wipe];
                    [vault remember:name];
                }
                @end

                @implementation LegacyStore
                + (instancetype)shared { return nil; }
                - (void)save:(NSString *)value forKey:(NSString *)key {}
                @end
                """);

        List<String> errors = new ArrayList<>();
        swift = SwiftProject.load(root, errors);
        ObjcProject objc = ObjcProject.load(root, errors);
        assertThat(errors).isEmpty();
        draft = new CodeGraphDraft();
        SwiftGraphSupport swiftSupport = new SwiftGraphSupport(swift, draft, Map.of());
        ObjcGraphSupport objcSupport = new ObjcGraphSupport(objc, draft);
        new SwiftObjcInterop(swift, objc).connect(swiftSupport, objcSupport);
        swift.types().forEach(swiftSupport::addStructure);
        objc.types().forEach(objcSupport::addStructure);
        swift.types().forEach(t -> t.functions().forEach(f -> swiftSupport.addCalls(f, SwiftObjcInteropTest::none, Map.of())));
        objc.types().forEach(t -> t.methods().forEach(m -> objcSupport.addCalls(m, SwiftObjcInteropTest::none, Map.of())));
    }

    private static Optional<SinkRule> none(String type, String method) {
        return Optional.empty();
    }

    @Test
    void 셀렉터와_Swift_이름을_서로_바꾼다() {
        assertThat(SwiftObjcInterop.swiftName("deletePayee:")).isEqualTo(new SwiftName("deletePayee", List.of("_")));
        assertThat(SwiftObjcInterop.swiftName("setObject:forKey:")).isEqualTo(new SwiftName("setObject", List.of("_", "forKey")));
        assertThat(SwiftObjcInterop.swiftName("initWithName:")).isEqualTo(new SwiftName("init", List.of("name")));
        assertThat(SwiftObjcInterop.swiftName("fetchWithURLString:")).isEqualTo(new SwiftName("fetch", List.of("urlString")));
        assertThat(SwiftObjcInterop.swiftName("reload")).isEqualTo(new SwiftName("reload", List.of()));
        assertThat(swift.type("TokenVault").orElseThrow().functions()).extracting(SwiftObjcInterop::objcSelector)
                .containsExactly("storeWithToken:", "wipe", "remember:");
    }

    @Test
    void Swift_에서_Objective_C_로_Objective_C_에서_Swift_로_잇는다() {
        assertThat(draft.edges()).contains(
                edge("AppDelegate#application(_:open:options:)", "PayeeDatabase#initWithName:"),
                edge("AppDelegate#application(_:open:options:)", "PayeeDatabase#deletePayee:"),
                edge("AppDelegate#application(_:open:options:)", "LegacyStore#save:forKey:"),
                edge("PayeeDatabase#deletePayee:", "TokenVault#store(token:)"),
                edge("PayeeDatabase#deletePayee:", "TokenVault#wipe()"),
                edge("PayeeDatabase#deletePayee:", "TokenVault#remember(_:)"));
    }

    private static EdgeDraft edge(String from, String to) {
        return new EdgeDraft(CodeGraphDraft.key(CodeNodeKind.METHOD, from), CodeGraphDraft.key(CodeNodeKind.METHOD, to), CodeEdgeKind.CALLS);
    }

    private static void write(String path, String text) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }
}
