package com.codeagent.core.swift;

import com.codeagent.core.model.CodeEdgeKind;
import com.codeagent.core.model.CodeGraphDraft;
import com.codeagent.core.model.CodeGraphDraft.EdgeDraft;
import com.codeagent.core.model.CodeGraphDraft.NodeDraft;
import com.codeagent.core.model.CodeNodeKind;
import com.codeagent.core.rules.SinkRule;
import com.codeagent.core.swift.SwiftProject.SwiftFunction;
import com.codeagent.core.swift.SwiftProject.SwiftType;
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

class SwiftGraphTest {

    @TempDir
    static Path root;

    private static SwiftProject project;
    private static CodeGraphDraft draft;
    private static final List<String> errors = new ArrayList<>();

    private static final List<SinkRule> RULES = List.of(
            new SinkRule("UserDefaults", Set.of("set"), "insecure_storage", "CWE-312"),
            new SinkRule("", Set.of("sqlite3_exec"), "sql_injection", "CWE-89"),
            new SinkRule("WKWebView", Set.of("evaluateJavaScript"), "webview", "CWE-79"));

    @BeforeAll
    static void build() throws IOException {
        write("App/AppDelegate.swift", """
                import UIKit

                class AppDelegate: UIResponder, UIApplicationDelegate {
                    var window: UIWindow?
                    private let db = PayeeDatabase()

                    func application(_ app: UIApplication, open url: URL, options: [String: Any] = [:]) -> Bool {
                        let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
                        if let name = items.first(where: { $0.name == "remove" })?.value {
                            db.deletePayee(named: name)
                        }
                        UserDefaults.standard.set(url.absoluteString, forKey: "last")
                        self.remember(url.absoluteString)
                        return true
                    }
                }

                extension AppDelegate {
                    func remember(_ value: String) {
                        let store = Store.shared
                        store.save(value)
                        log(value)
                    }
                }
                """);
        write("App/PayeeDatabase.swift", """
                import SQLite3

                final class PayeeDatabase {
                    var handle: OpaquePointer?

                    func deletePayee(named name: String) {
                        sqlite3_exec(handle, "DELETE FROM payee WHERE name = '" + name + "'", nil, nil, nil)
                    }
                }

                class Store {
                    static let shared = Store()
                    func save(_ v: String) {
                        let defaults = UserDefaults.standard
                        defaults.set(v, forKey: "token")
                    }
                }

                func log(_ s: String) { print(s) }
                """);
        write("App/Bridge.swift", """
                import WebKit

                class Bridge: NSObject, WKScriptMessageHandler {
                    let webView: WKWebView

                    init(webView: WKWebView) { self.webView = webView }

                    func userContentController(_ c: WKUserContentController, didReceive message: WKScriptMessage) {
                        webView.evaluateJavaScript("show(" + String(describing: message.body) + ")")
                    }
                }

                class SubBridge: Bridge {
                    override func userContentController(_ c: WKUserContentController, didReceive message: WKScriptMessage) {}
                }

                protocol Handler { func handle(x: Int) }
                struct Point { let x: Int }
                enum Kind { case a; static func make() -> Kind { .a } }
                """);
        write("AppTests/AppTests.swift", "class AppTests { func t() { sqlite3_exec(nil, \"x\", nil, nil, nil) } }");
        write("Pods/Lib/Lib.swift", "class Lib {}");

        project = SwiftProject.load(root, errors);
        draft = new CodeGraphDraft();
        SwiftGraphSupport support = new SwiftGraphSupport(project, draft, Map.of());
        project.types().forEach(support::addStructure);
        project.types().forEach(support::addOverrides);
        project.types().forEach(t -> t.functions().forEach(f -> support.addCalls(f, SwiftGraphTest::rule, Map.of())));
    }

    private static Optional<SinkRule> rule(String type, String method) {
        return RULES.stream().filter(r -> r.matches(type, method)).findFirst();
    }

    @Test
    void 타입과_함수를_Swift_식_이름으로_색인한다() {
        assertThat(SwiftProject.available()).isTrue();
        assertThat(errors).isEmpty();
        assertThat(project.types()).extracting(SwiftType::name)
                .containsExactlyInAnyOrder("AppDelegate", "PayeeDatabase", "Store", "PayeeDatabaseSwift", "Bridge",
                        "SubBridge", "Handler", "Point", "Kind");
        SwiftType app = project.type("AppDelegate").orElseThrow();
        assertThat(app.functions()).extracting(SwiftFunction::key)
                .containsExactly("AppDelegate#application(_:open:options:)", "AppDelegate#remember(_:)");
        assertThat(app.propertyTypes()).containsEntry("window", "UIWindow").containsEntry("db", "PayeeDatabase");
        assertThat(project.type("Bridge").orElseThrow().functions()).extracting(SwiftFunction::key)
                .contains("Bridge#init(webView:)", "Bridge#userContentController(_:didReceive:)");
        assertThat(project.type("Handler").orElseThrow().declaration()).isEqualTo("protocol");
        assertThat(project.type("Point").orElseThrow().declaration()).isEqualTo("struct");
        assertThat(project.lineage(project.type("SubBridge").orElseThrow())).contains("Bridge", "WKScriptMessageHandler");
        assertThat(project.functionAt("App/PayeeDatabase.swift", 7)).map(SwiftFunction::key)
                .hasValue("PayeeDatabase#deletePayee(named:)");
    }

    @Test
    void 호출을_잇고_위험_지점을_찾는다() {
        assertThat(draft.edges()).contains(
                edge("AppDelegate#application(_:open:options:)", "PayeeDatabase#deletePayee(named:)"),
                edge("AppDelegate#application(_:open:options:)", "AppDelegate#remember(_:)"),
                edge("AppDelegate#remember(_:)", "Store#save(_:)"),
                edge("AppDelegate#remember(_:)", "PayeeDatabaseSwift#log(_:)"),
                new EdgeDraft(CodeGraphDraft.key(CodeNodeKind.METHOD, "Bridge#userContentController(_:didReceive:)"),
                        CodeGraphDraft.key(CodeNodeKind.METHOD, "SubBridge#userContentController(_:didReceive:)"),
                        CodeEdgeKind.OVERRIDDEN_BY));
        assertThat(draft.nodes()).filteredOn(n -> n.kind() == CodeNodeKind.SINK)
                .extracting(n -> n.props().get("method") + " " + n.props().get("api") + " L" + n.startLine())
                .containsExactlyInAnyOrder(
                        "PayeeDatabase#deletePayee(named:) sqlite3_exec L7",
                        "AppDelegate#application(_:open:options:) UserDefaults.set L12",
                        "Store#save(_:) UserDefaults.set L15",
                        "Bridge#userContentController(_:didReceive:) WKWebView.evaluateJavaScript L9");
        assertThat(draft.nodes()).filteredOn(n -> n.kind() == CodeNodeKind.METHOD).extracting(NodeDraft::filePath)
                .doesNotContain("AppTests/AppTests.swift", "Pods/Lib/Lib.swift");
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
