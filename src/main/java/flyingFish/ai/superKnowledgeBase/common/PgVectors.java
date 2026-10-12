package flyingFish.ai.superKnowledgeBase.common;

/**
 * pgvector 的文本表示形式：[0.1,0.2,...]，SQL 中用 ?::vector 转换。
 */
public final class PgVectors {

    private PgVectors() {
    }

    public static String toLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 10).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }
}
