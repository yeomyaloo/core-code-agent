package com.codeagent.core.objc;

import com.codeagent.core.model.CodeEdgeKind;
import com.codeagent.core.model.CodeGraphDraft;
import com.codeagent.core.model.CodeGraphDraft.EdgeDraft;
import com.codeagent.core.model.CodeGraphDraft.NodeDraft;
import com.codeagent.core.model.CodeNodeKind;
import com.codeagent.core.objc.ObjcProject.ObjcMethod;
import com.codeagent.core.objc.ObjcProject.ObjcType;
import com.codeagent.core.rules.SinkRule;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ObjcGraphTest {

    @TempDir
    static Path root;

    private static ObjcProject project;
    private static CodeGraphDraft draft;
    private static final List<String> errors = new ArrayList<>();

    private static final List<SinkRule> RULES = List.of(
            new SinkRule("NSUserDefaults", Set.of("setObject:forKey:"), "insecure_storage", "CWE-312"),
            new SinkRule("", Set.of("sqlite3_exec"), "sql_injection", "CWE-89"),
            new SinkRule("WKWebView", Set.of("evaluateJavaScript:completionHandler:"), "webview", "CWE-79"));

    @BeforeAll
    static void build() throws IOException {
        write("App/AppDelegate.h", """
                #import <UIKit/UIKit.h>
                @class PayeeDatabase;

                @interface AppDelegate : UIResponder <UIApplicationDelegate>
                @property (nonatomic, strong) PayeeDatabase *db;
                - (BOOL)application:(UIApplication *)app openURL:(NSURL *)url options:(NSDictionary *)options;
                @end
                """);
        write("App/AppDelegate.m", """
                #import "AppDelegate.h"
                #import "PayeeDatabase.h"

                @implementation AppDelegate

                - (BOOL)application:(UIApplication *)app openURL:(NSURL *)url options:(NSDictionary *)options {
                    PayeeDatabase *store = [[PayeeDatabase alloc] init];
                    [store deletePayee:url.host];
                    [self.db deletePayee:@"x"];
                    [self remember:url.absoluteString];
                    return YES;
                }

                - (void)remember:(NSString *)value {
                    [[NSUserDefaults standardUserDefaults] setObject:value forKey:@"last"];
                    [[Store sharedStore] save:value];
                    logLine(value);
                }

                @end
                """);
        write("App/PayeeDatabase.h", """
                #import <Foundation/Foundation.h>
                @interface PayeeDatabase : NSObject
                - (void)deletePayee:(NSString *)name;
                @end

                @interface Store : NSObject
                + (instancetype)sharedStore;
                - (void)save:(NSString *)value;
                @end
                """);
        write("App/PayeeDatabase.m", """
                #import "PayeeDatabase.h"
                #import <sqlite3.h>

                static sqlite3 *handle;

                void logLine(NSString *s) { NSLog(@"%@", s); }

                @implementation PayeeDatabase
                - (void)deletePayee:(NSString *)name {
                    NSString *sql = [NSString stringWithFormat:@"DELETE FROM payee WHERE name = '%@'", name];
                    sqlite3_exec(handle, [sql UTF8String], NULL, NULL, NULL);
                }
                @end

                @implementation Store
                + (instancetype)sharedStore { static Store *s; return s; }
                - (void)save:(NSString *)value {
                    NSUserDefaults *defaults = [NSUserDefaults standardUserDefaults];
                    [defaults setObject:value forKey:@"token"];
                }
                @end
                """);
        write("App/Bridge.m", """
                #import <WebKit/WebKit.h>

                @interface Bridge : NSObject <WKScriptMessageHandler>
                @property (nonatomic, strong) WKWebView *webView;
                @end

                @implementation Bridge
                - (void)userContentController:(WKUserContentController *)c didReceiveScriptMessage:(WKScriptMessage *)message {
                    [self.webView evaluateJavaScript:message.body completionHandler:nil];
                }
                @end

                @interface SubBridge : Bridge
                @end
                @implementation SubBridge
                - (void)userContentController:(WKUserContentController *)c didReceiveScriptMessage:(WKScriptMessage *)message {}
                @end
                """);
        write("App/plain.h", "int add(int a, int b);\n");
        write("AppTests/AppTests.m", "@implementation AppTests\n- (void)t { sqlite3_exec(NULL, \"x\", NULL, NULL, NULL); }\n@end\n");
        write("Pods/Lib/Lib.m", "@implementation Lib\n@end\n");

        project = ObjcProject.load(root, errors);
        draft = new CodeGraphDraft();
        ObjcGraphSupport support = new ObjcGraphSupport(project, draft);
        project.types().forEach(support::addStructure);
        project.types().forEach(support::addOverrides);
        project.types().forEach(t -> t.methods().forEach(m -> support.addCalls(m, ObjcGraphTest::rule, Map.of())));
    }

    private static Optional<SinkRule> rule(String type, String method) {
        return RULES.stream().filter(r -> r.matches(type, method)).findFirst();
    }

    @Test
    void 헤더와_구현을_합쳐_셀렉터로_색인한다() {
        assertThat(ObjcProject.available()).isTrue();
        assertThat(errors).isEmpty();
        assertThat(project.types()).extracting(ObjcType::name)
                .containsExactlyInAnyOrder("AppDelegate", "PayeeDatabase", "Store", "PayeeDatabaseObjc", "Bridge", "SubBridge");
        ObjcType app = project.type("AppDelegate").orElseThrow();
        assertThat(app.filePath()).isEqualTo("App/AppDelegate.m");
        assertThat(app.superTypes()).containsExactly("UIResponder", "UIApplicationDelegate");
        assertThat(app.propertyTypes()).containsEntry("db", "PayeeDatabase");
        assertThat(app.methods()).extracting(ObjcMethod::key)
                .containsExactly("AppDelegate#application:openURL:options:", "AppDelegate#remember:");
        ObjcMethod open = app.methods().get(0);
        assertThat(open.node()).isNotNull();
        assertThat(open.paramTypes()).containsEntry("url", "NSURL").containsEntry("app", "UIApplication");
        assertThat(open.returnType()).isEqualTo("BOOL");
        assertThat(project.findMethod("Store", "sharedStore")).map(ObjcMethod::returnType).hasValue("Store");
        assertThat(project.lineage(project.type("SubBridge").orElseThrow())).contains("Bridge", "WKScriptMessageHandler");
        assertThat(project.methodAt("App/PayeeDatabase.m", 11)).map(ObjcMethod::key).hasValue("PayeeDatabase#deletePayee:");
    }

    @Test
    void 메시지와_C_함수_호출을_잇고_위험_지점을_찾는다() {
        assertThat(draft.edges()).contains(
                edge("AppDelegate#application:openURL:options:", "PayeeDatabase#deletePayee:"),
                edge("AppDelegate#application:openURL:options:", "AppDelegate#remember:"),
                edge("AppDelegate#remember:", "Store#save:"),
                edge("AppDelegate#remember:", "PayeeDatabaseObjc#logLine"),
                new EdgeDraft(CodeGraphDraft.key(CodeNodeKind.METHOD, "Bridge#userContentController:didReceiveScriptMessage:"),
                        CodeGraphDraft.key(CodeNodeKind.METHOD, "SubBridge#userContentController:didReceiveScriptMessage:"),
                        CodeEdgeKind.OVERRIDDEN_BY));
        assertThat(draft.nodes()).filteredOn(n -> n.kind() == CodeNodeKind.SINK)
                .extracting(n -> n.props().get("method") + " " + n.props().get("api") + " L" + n.startLine())
                .containsExactlyInAnyOrder(
                        "PayeeDatabase#deletePayee: sqlite3_exec L11",
                        "AppDelegate#remember: NSUserDefaults.setObject:forKey: L15",
                        "Store#save: NSUserDefaults.setObject:forKey: L19",
                        "Bridge#userContentController:didReceiveScriptMessage: WKWebView.evaluateJavaScript:completionHandler: L9");
        assertThat(draft.nodes()).filteredOn(n -> n.kind() == CodeNodeKind.METHOD).extracting(NodeDraft::filePath)
                .doesNotContain("AppTests/AppTests.m", "Pods/Lib/Lib.m", "App/plain.h");
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
