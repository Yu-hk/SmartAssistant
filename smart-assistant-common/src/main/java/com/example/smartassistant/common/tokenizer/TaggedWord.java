package com.example.smartassistant.common.tokenizer;

import lombok.Data;

/**
 * 词性标注结果
 *
 * @since 1.1.0
 */
@Data
public class TaggedWord {
    
    /** 词语 */
    private final String word;
    
    /** 词性标注（HanLP 词性标注集） */
    private final String pos;
    
    /** 自然语言描述 */
    private final String posDescription;
    
    public TaggedWord(String word, String pos) {
        this.word = word;
        this.pos = pos;
        this.posDescription = translatePos(pos);
    }
    
    /**
     * 转换为标准词（用于同义词匹配）
     */
    public String toStandardForm() {
        return word;
    }
    
    /**
     * 判断是否为实词（名词、动词、形容词等）
     */
    public boolean isContentWord() {
        return pos != null && (
            pos.startsWith("n") ||   // 名词
            pos.startsWith("v") ||   // 动词
            pos.startsWith("a") ||   // 形容词
            pos.startsWith("d")      // 副词
        );
    }
    
    /**
     * 判断是否为名词
     */
    public boolean isNoun() {
        return pos != null && pos.startsWith("n");
    }
    
    /**
     * 判断是否为动词
     */
    public boolean isVerb() {
        return pos != null && pos.startsWith("v");
    }
    
    /**
     * 判断是否为形容词
     */
    public boolean isAdjective() {
        return pos != null && pos.startsWith("a");
    }
    
    /**
     * 翻译词性标注为中文描述
     */
    private static String translatePos(String pos) {
        if (pos == null) return "未知";
        
        return switch (pos) {
            case "n", "nr", "ns", "nt", "nz" -> "名词";
            case "v", "vd", "vn" -> "动词";
            case "a", "ad", "an" -> "形容词";
            case "d" -> "副词";
            case "m" -> "数词";
            case "q" -> "量词";
            case "r" -> "代词";
            case "p" -> "介词";
            case "c" -> "连词";
            case "u" -> "助词";
            case "x" -> "非语素词";
            case "w" -> "标点符号";
            case "i" -> "成语";
            case "l" -> "习惯用语";
            case "z" -> "其他";
            default -> pos;
        };
    }
    
    @Override
    public String toString() {
        return word + "/" + pos + "(" + posDescription + ")";
    }
}
