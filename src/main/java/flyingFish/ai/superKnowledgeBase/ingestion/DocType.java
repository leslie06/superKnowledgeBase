package flyingFish.ai.superKnowledgeBase.ingestion;

import java.util.Locale;

public enum DocType {
    WORD, EXCEL, PPT, PDF, TEXT;

    public static DocType fromFilename(String filename) {
        String ext = extension(filename);
        return switch (ext) {
            case "doc", "docx" -> WORD;
            case "xls", "xlsx" -> EXCEL;
            case "ppt", "pptx" -> PPT;
            case "pdf" -> PDF;
            case "txt", "md" -> TEXT;
            default -> throw new IllegalArgumentException("不支持的文件类型：" + (ext.isEmpty() ? "无扩展名" : "." + ext)
                    + "，目前支持 doc/docx/xls/xlsx/ppt/pptx/pdf/txt/md");
        };
    }

    public static String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
