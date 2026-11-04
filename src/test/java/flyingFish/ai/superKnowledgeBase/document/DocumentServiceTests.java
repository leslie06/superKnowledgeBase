package flyingFish.ai.superKnowledgeBase.document;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentServiceTests {

    @Test
    void normalizesFolderImportPath() {
        assertThat(DocumentService.sourcePath("资料/人事制度/考勤管理办法.docx")).isEqualTo("资料/人事制度/考勤管理办法.docx");
        assertThat(DocumentService.sourcePath("/资料/./人事制度/报告,2024.docx")).isEqualTo("资料/人事制度/报告,2024.docx");
        assertThat(DocumentService.sourcePath("资料\\人事制度\\考勤.docx")).isEqualTo("资料/人事制度/考勤.docx");
    }

    @Test
    void ignoresBareFilenameAndEscapingPath() {
        assertThat(DocumentService.sourcePath("考勤管理办法.docx")).isNull();
        assertThat(DocumentService.sourcePath("  ")).isNull();
        assertThat(DocumentService.sourcePath(null)).isNull();
        assertThat(DocumentService.sourcePath("资料/../../etc/passwd")).isNull();
    }
}
