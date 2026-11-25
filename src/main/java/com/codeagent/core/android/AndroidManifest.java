package com.codeagent.core.android;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.codeagent.core.SourceFiles;

/**
 * AndroidManifest.xml 에서 외부 공격면 정보를 뽑는다.
 * 평문 전송 허용({@code android:usesCleartextTraffic="true"})과,
 * 외부에서 띄울 수 있는 컴포넌트({@code android:exported="true"} 또는 intent-filter 를 가진 것)를 찾는다.
 *
 * @param filePath           저장소 기준 상대 경로
 * @param cleartextTraffic   application 에 usesCleartextTraffic="true" 가 있으면 true
 * @param cleartextLine      그 속성이 적힌 줄 (못 찾으면 1)
 * @param exportedComponents 외부에서 띄울 수 있는 컴포넌트
 */
public record AndroidManifest(String filePath, boolean cleartextTraffic, int cleartextLine,
                              List<Component> exportedComponents) {

    /**
     * 외부 진입점이 될 수 있는 매니페스트 컴포넌트.
     *
     * @param kind        activity / service / receiver / provider
     * @param name        android:name (점으로 시작하면 패키지 상대)
     * @param line        선언된 줄
     * @param launcherOnly 런처 아이콘을 띄우는 MAIN/LAUNCHER intent-filter 만 있으면 true (양성, 원격 공격면 아님)
     * @param actions     intent-filter 의 action 이름들
     * @param deepLinks   intent-filter 의 data(scheme/host) 로 들어오는 딥링크
     */
    public record Component(String kind, String name, int line, boolean launcherOnly,
                            List<String> actions, List<String> deepLinks) {
    }

    private static final Set<String> SKIP_DIRS = Set.of(".git", "node_modules", "dist", "out", ".gradle", "build");
    private static final Set<String> COMPONENT_TAGS = Set.of("activity", "activity-alias", "service", "receiver", "provider");

    /** 저장소에서 AndroidManifest.xml 을 찾아 파싱한다. main 소스의 것을 먼저 본다. 없으면 empty. */
    public static Optional<AndroidManifest> parse(Path root, List<String> parseErrors) {
        List<Path> manifests = new ArrayList<>();
        for (Path p : SourceFiles.find(root, SKIP_DIRS, 1_000_000L, f -> f.getFileName().toString().equals("AndroidManifest.xml"))) {
            manifests.add(p);
        }
        if (manifests.isEmpty()) {
            return Optional.empty();
        }
        // src/main 아래의 매니페스트를 우선한다 (debug/androidTest 변형보다)
        manifests.sort((a, b) -> Boolean.compare(!b.toString().replace('\\', '/').contains("/main/"),
                !a.toString().replace('\\', '/').contains("/main/")));
        Path chosen = manifests.get(0);
        String relative = SourceFiles.relative(root, chosen);
        try {
            String text = new String(Files.readAllBytes(chosen), StandardCharsets.UTF_8);
            return Optional.of(parse(relative, text));
        } catch (Exception e) {
            parseErrors.add(relative + ": AndroidManifest 파싱 실패 (" + e.getMessage() + ")");
            return Optional.empty();
        }
    }

    static AndroidManifest parse(String filePath, String text) throws Exception {
        Document doc = secureBuilder().parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
        Element application = firstElement(doc.getDocumentElement(), "application");

        boolean cleartext = application != null
                && "true".equalsIgnoreCase(attr(application, "usesCleartextTraffic"));
        int cleartextLine = cleartext ? lineOf(text, "usesCleartextTraffic") : 1;

        List<Component> components = new ArrayList<>();
        if (application != null) {
            NodeList children = application.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node node = children.item(i);
                if (!(node instanceof Element el) || !COMPONENT_TAGS.contains(el.getTagName())) {
                    continue;
                }
                List<Element> filters = elements(el, "intent-filter");
                boolean exported = "true".equalsIgnoreCase(attr(el, "exported"));
                // exported 를 안 적었어도 intent-filter 가 있으면 외부에서 띄울 수 있다 (activity/receiver/service)
                boolean reachable = exported || !filters.isEmpty();
                if (!reachable) {
                    continue;
                }
                List<String> actions = new ArrayList<>();
                List<String> deepLinks = new ArrayList<>();
                for (Element filter : filters) {
                    for (Element action : elements(filter, "action")) {
                        actions.add(attr(action, "name"));
                    }
                    for (Element data : elements(filter, "data")) {
                        String scheme = attr(data, "scheme");
                        String host = attr(data, "host");
                        if (!scheme.isEmpty() || !host.isEmpty()) {
                            deepLinks.add((scheme.isEmpty() ? "" : scheme + "://") + host);
                        }
                    }
                }
                boolean launcherOnly = actions.size() == 1 && actions.contains("android.intent.action.MAIN")
                        && deepLinks.isEmpty();
                String name = attr(el, "name");
                components.add(new Component(el.getTagName(), name, lineOf(text, name.isEmpty() ? el.getTagName() : name),
                        launcherOnly, List.copyOf(actions), List.copyOf(deepLinks)));
            }
        }
        return new AndroidManifest(filePath, cleartext, cleartextLine, List.copyOf(components));
    }

    /** 외부에서 원격으로 띄울 수 있는 컴포넌트 (런처 아이콘만 띄우는 MAIN 액티비티는 뺀다). */
    public List<Component> externalComponents() {
        return exportedComponents.stream().filter(c -> !c.launcherOnly()).toList();
    }

    private static DocumentBuilder secureBuilder() throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        // 매니페스트에 외부 엔티티가 있어도 읽지 않는다 (보안 도구가 XXE 를 만들지 않도록)
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
        dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        dbf.setXIncludeAware(false);
        dbf.setExpandEntityReferences(false);
        return dbf.newDocumentBuilder();
    }

    private static Element firstElement(Element parent, String tag) {
        List<Element> found = elements(parent, tag);
        return found.isEmpty() ? null : found.get(0);
    }

    private static List<Element> elements(Element parent, String tag) {
        List<Element> result = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element el && el.getTagName().equals(tag)) {
                result.add(el);
            }
        }
        return result;
    }

    /** android: 접두사와 상관없이 지역 이름으로 속성을 읽는다. 없으면 빈 문자열 */
    private static String attr(Element el, String localName) {
        String v = el.getAttribute("android:" + localName);
        if (v.isEmpty()) {
            v = el.getAttribute(localName);
        }
        return v == null ? "" : v;
    }

    /** 원문에서 조각이 처음 나오는 줄 번호 (1-based). 못 찾으면 1 */
    private static int lineOf(String text, String needle) {
        if (needle == null || needle.isEmpty()) {
            return 1;
        }
        int idx = text.indexOf(needle);
        if (idx < 0) {
            return 1;
        }
        int line = 1;
        for (int i = 0; i < idx; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
