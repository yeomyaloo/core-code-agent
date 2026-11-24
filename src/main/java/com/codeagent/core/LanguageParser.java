package com.codeagent.core;

import com.codeagent.core.model.CodeGraphDraft;

import java.nio.file.Path;
import java.util.List;

/**
 * 언어별 파서. 엔진은 저장소에 그 언어 소스가 있는지 보고({@link #detect}) 있으면 그래프에 노드·연결선을 더하게 한다.
 * 새 언어는 이 인터페이스를 구현해 {@link CodeGraphEngine} 의 기본 파서 목록에 넣는다.
 */
public interface LanguageParser {

    /** 언어 이름 (java, python, javascript). 노드 props 의 language 와 같은 값 */
    String language();

    /** 저장소에 이 언어의 분석 대상 소스가 있는지 (확장자·매니페스트로 판단) */
    boolean detect(Path root);

    /**
     * 저장소를 읽어 그래프에 더한다. 분석 대상 코드는 읽기만 한다.
     *
     * @param parseErrors 파싱하지 못했거나 일부만 읽은 파일을 "경로: 이유" 로 더한다
     */
    void contribute(Path root, CodeGraphDraft draft, List<String> parseErrors);
}
